(ns clj-oauth2.test-client
  (:require (midje [sweet :refer :all]
                   [util :refer [testable-privates]])
            [clj-oauth2.client :as client]
            [cheshire.core :as json]
            [uri.core :as uri]))

(def endpoint
  {:client-id "foo"
   :client-secret "bar"
   :access-query-param :access_token
   :scope ["foo" "bar"]})

(def access-token
  {:access-token "sesame"
   :query-param :access_token
   :token-type "bearer"
   :expires-in 120
   :refresh-token "new-foo"})

(def endpoint-auth-code
  (assoc endpoint
    :redirect-uri "http://my.host/cb"
    :grant-type "authorization_code"
    :authorization-uri "http://localhost:18080/auth"
    :access-token-uri "http://localhost:18080/token-auth-code"))

(def endpoint-resource-owner
  (assoc endpoint
    :grant-type "password"
    :access-token-uri "http://localhost:18080/token-password"))

(def resource-owner-credentials
  {:username "foo"
   :password "bar"})

(let [req (client/make-auth-request endpoint-auth-code "bazqux")]
  (fact "Constructs a URI for the authorization redirect."
    (uri/uri->map (uri/make (:uri req)) true)
    => (contains
        {:scheme "http"
         :host "localhost"
         :port 18080
         :path "/auth"
         :query {:response_type "code"
                 :client_id "foo"
                 :redirect_uri "http://my.host/cb"
                 :scope "foo bar"
                 :state "bazqux"}})))

(defn token-response [req]
  {:status 200
   :headers {"content-type" (str "application/"
                                 (if (contains? (:query-params req) :formurlenc)
                                   "x-www-form-urlencoded"
                                   "json")
                                 "; charset=UTF-8")}
   :body ((if (contains? (:query-params req) :formurlenc)
            uri/form-url-encode
            json/generate-string)
          (let [{:keys [access-token
                        token-type
                        expires-in
                        refresh-token]}
                access-token]
            {:access_token access-token
             :token_type token-type
             :expires_in expires-in
             :refresh_token refresh-token}))})

(def default-response
  {:access-token "sesame"
   :query-param :access_token
   :refresh-token "new-foo"
   :token-type "bearer"
   :params {:expires_in 120
            :refresh_token "new-foo"}})

(fact "Correct headers generated for getting access token."
  (client/get-access-token endpoint-auth-code {:code "abracadabra"
                                               :state "foo"})
  => default-response
  (provided (clj-http.client/post anything
                                  (contains
                                   {:content-type "application/x-www-form-urlencoded"}))
            => (token-response {})))

(fact "Client credentials passed in authorization header work."
  (client/get-access-token (assoc endpoint-auth-code
                             :authorization-header? true)
                           {:code "abracadabra" :state "foo"})
  => default-response
  (provided (clj-http.client/post anything
                                  (contains {:headers {"Authorization" "Basic Zm9vOmJhcg=="}}))
            => (token-response {})))

(fact "application/x-www-form-urlencoded responses (Facebook)"
  (client/get-access-token (assoc endpoint-auth-code :access-token-uri
                                  (str (:access-token-uri endpoint-auth-code)
                                       "?formurlenc"))
                           {:code "abracadabra" :state "foo"})
  => default-response
  (provided (clj-http.client/post #"\?formurlenc$"
                                  anything) => (token-response {})))
