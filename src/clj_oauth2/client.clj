(ns clj-oauth2.client
  (:refer-clojure :exclude [get])
  (:use [clj-http.client :only [wrap-request]])
  (:require [clj-http.client :as http]
            [clojure.string :as str]
            [cheshire.core :as json]
            [uri.core :as uri])
  (:import [clj_oauth2 OAuth2Exception OAuth2StateMismatchException]
           [org.apache.commons.codec.binary Base64]))

(defn has-keys? [m keys]
  (apply = (map count [keys (select-keys m keys)])))

(defn make-auth-request
  "Create the auth request, takes a map which must have the following
  keys:

    :authorization-uri - URI for sending the user to approve the request.
    :client-id - Client ID.

  Can optionally have the following:
    :redirect-uri - URI for the resource owner to be redirected to
                    after authorization.
    :scope - Scope of the authorization, dependent service OAuth is
             being used with.
    :state - Param that will be sent back when resource owner is
             redirected, recommended to prevent cross-site attacks.

  Returns a map of the request, with :uri as the URI to use for the
  request, :scope of the expected scope, and :state."
  [{:keys [authorization-uri client-id redirect-uri scope access-type prompt include-granted-scopes login-hint]
    :as oauth2-map}
   & [state]]
  {:pre [(has-keys? oauth2-map [:authorization-uri :client-id])]}
  (let [uri (uri/uri->map (uri/make authorization-uri) true)
        query (assoc (:query uri)
                :client_id client-id
                :redirect_uri redirect-uri
                :response_type "code")
        query (if state (assoc query :state state) query)
        query (if access-type (assoc query :access_type access-type) query)
        query (if scope (assoc query :scope (str/join " " scope)) query)
        query (if prompt (assoc query :prompt (str/join " " prompt)) query)
        query (if include-granted-scopes (assoc query :include_granted_scopes include-granted-scopes) query)
        query (if login-hint (assoc query :login_hint login-hint) query)]
    {:uri (.toString (uri/make (assoc uri :query query)))
     :scope scope
     :state state}))

(defn- add-auth-header
  "Returns an update request map, consisting of the original request
  map with the 'Authorisation' header. scheme is just a string of the
  type of authorisation \"OAuth\", \"Basic\", with param following."
  [req scheme param]
  (let [header (str scheme " " param)]
    (assoc-in req [:headers "Authorization"] header)))

(defn- add-base64-auth-header
  "Same as add-auth-header, with param being Base64 encoded instead."
  [req scheme param]
  (add-auth-header req scheme
                   (Base64/encodeBase64String (.getBytes param))))

(defmulti prepare-access-token-request
  "We will dispatch based on the grant-type."
  (fn [request endpoint params]
    (name (:grant-type endpoint))))

(defmethod prepare-access-token-request
  "authorization_code"
  [request endpoint params]
  {:pre [(has-keys? params [:code])
         (has-keys? endpoint [:redirect-uri])]}
  (merge-with merge request
              {:body {:code
                      (:code params)
                      :redirect_uri
                      (:redirect-uri endpoint)}}))

(defmethod prepare-access-token-request
  "password"
  [request endpoint params]
  (merge-with merge request
              {:body {:username (:username params)
                      :password (:password params)}}))

(defn- add-client-authentication
  [request endpoint]
  {:pre [(has-keys? endpoint [:client-id :client-secret])]}
  (let [{:keys [client-id client-secret authorization-header?]} endpoint]
    (if authorization-header?
      (add-base64-auth-header request "Basic"
                              (str client-id ":" client-secret))
      (merge-with merge request
                  {:body {:client_id client-id
                          :client_secret client-secret}}))))

(defn read-json-from-body
  "convert body to a reader to be compatible with clojure.data.json 0.2.1
   In case body is a byte array, aka class [B"
  [body]
  (if (instance? String body)
    (json/parse-string body true)
    (with-open [reader (clojure.java.io/reader body)]
      (json/parse-stream reader true))))

(defn- build-access-request
  "Given the endpoint, and params, will return cthe request to be sent
  to the resource server."
  [{:keys [access-token-uri access-query-param grant-type] :as endpoint}
   params]
  {:pre [(has-keys? endpoint [:grant-type])]}
  (let [request {:content-type "application/x-www-form-urlencoded"
                 :throw-exceptions false
                 :body {:grant_type grant-type}}
        request (prepare-access-token-request request endpoint params)
        request (add-client-authentication request endpoint)
        request (update-in request [:body] uri/form-url-encode)]
    request))

(defn- request-access-token
  [endpoint params]
  {:pre [(has-keys? endpoint [:access-token-uri :grant-type])]}
  (let [{:keys [access-token-uri access-query-param grant-type]} endpoint
        request
        {:content-type "application/x-www-form-urlencoded"
         :throw-exceptions false
         :body {:grant_type grant-type}
         :conn-timeout 10000
         :socket-timeout 10000}
        request (prepare-access-token-request request endpoint params)
        request (add-client-authentication request endpoint)
        request (update-in request [:body] uri/form-url-encode)
        {:keys [body headers status]} (http/post access-token-uri request)
        content-type (headers "content-type")
        body (if (and content-type
                      (or (.startsWith content-type "application/json")
                          (.startsWith content-type "text/javascript"))) ; Facebookism
               (read-json-from-body body)
               (uri/form-url-decode body)) ; Facebookism
        error (:error body)]
    (if (or error (not= status 200))
      (throw (OAuth2Exception. (if error
                                 (if (string? error)
                                   (:error_description body)
                                   (:message error)) ; Facebookism
                                 "error requesting access token")
                               (if error
                                 (if (string? error)
                                   error
                                   (:type error)) ; Facebookism
                                 "unknown")))
      {:access-token (:access_token body)
       :token-type (or (:token_type body) "draft-10") ; Force.com
       :query-param access-query-param
       :params (dissoc body :access_token :token_type)
       :refresh-token (:refresh_token body)})))

(defn get-access-token
  [endpoint & [params {expected-state :state expected-scope :scope}]]
  (let [{:keys [state error]} params]
    (cond (string? error)
          (throw (OAuth2Exception. (:error_description params) error))
          (and expected-state (not (= state expected-state)))
          (throw (OAuth2StateMismatchException.
                  (format "Expected state %s but got %s"
                          state expected-state)
                  state
                  expected-state))
          :else
          (request-access-token endpoint params))))

(defn with-access-token [uri {:keys [access-token query-param]}]
  (str (uri/make (assoc-in (uri/uri->map (uri/make uri) true)
                           [:query query-param]
                           access-token))))

(defmulti add-access-token-to-request
  (fn [req {:keys [token-type]}]
    (if token-type
      (str/lower-case token-type))))

(defmethod add-access-token-to-request
  :default [req oauth2]
  (let [{:keys [token-type]} oauth2]
    (if (:throw-exceptions req)
      (throw (OAuth2Exception. (str "Unknown token type: " token-type)))
      [req false])))

(defmethod add-access-token-to-request
  "bearer" [req oauth2]
  (let [{:keys [access-token query-param]} oauth2]
    (if access-token
      [(if query-param
         (assoc-in req [:query-params query-param] access-token)
         (add-auth-header req "Bearer" access-token))
       true]
      [req false])))

(defmethod add-access-token-to-request ; Force.com
  "draft-10" [req oauth2]
  (let [{:keys [access-token query-param]} oauth2]
    (if access-token
      [(if query-param
         (assoc-in req [:query-params query-param] access-token)
         (add-auth-header req "OAuth" access-token))
       true]
      [req false])))

(defn wrap-oauth2 [client]
  (fn [req]
    (let [{:keys [oauth2 throw-exceptions]} req
          [req token-added?] (add-access-token-to-request req oauth2)
          req (dissoc req :oauth2)]
      (if token-added?
        (client req)
        (if throw-exceptions
          (throw (OAuth2Exception. "Missing :oauth2 params"))
          (client req))))))

(defn refresh-access-token
  [refresh-token {:keys [client-id client-secret access-token-uri]}]
  (let [req (http/post access-token-uri {:form-params
                                         {:client_id client-id
                                          :client_secret client-secret
                                          :refresh_token refresh-token
                                          :grant_type "refresh_token"}})]
    (when (= (:status req) 200)
      (read-json-from-body (:body req)))))

(def request
  (wrap-oauth2 http/request))

(defmacro def-request-shortcut-fn [method]
  (let [method-key (keyword method)]
    `(defn ~method [url# & [req#]]
       (request (merge req#
                       {:method ~method-key
                        :url url#})))))

(def-request-shortcut-fn get)
(def-request-shortcut-fn post)
(def-request-shortcut-fn put)
(def-request-shortcut-fn delete)
(def-request-shortcut-fn head)
