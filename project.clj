(defproject org.clojars.osbert/clj-oauth2 "0.1.8"
  :description "clj-http and ring middlewares for OAuth 2.0"
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [cheshire "5.3.1"]
                 [clj-http "0.1.2"]
                 [uri "1.1.0"]
                 [commons-codec/commons-codec "1.9"]]
  :profiles {:dev {:source-paths ["dev"]
                   :dependencies [[midje "1.6.3"]
                                  [ring "1.3.0"]]}}
  :aot [clj-oauth2.OAuth2Exception
        clj-oauth2.OAuth2StateMismatchException])
