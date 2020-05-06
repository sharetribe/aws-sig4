(ns aws-sig4.middleware
  (:require [clojure.string :as str]
            [clj-http.client :as http]
            [clj-time.format :as format]
            [clj-time.core :as time]
            [aws-sig4.auth :as auth]))

(defn ensure-aws-date [request]
  (let [headers (->> request
                     :headers
                     (map (fn [[n v]]
                            [(str/lower-case n) v]))
                     (into {}))]
    (if (or (headers "date") (headers "x-amz-date"))
      request
      (assoc-in request
                [:headers "X-Amz-Date"]
                (format/unparse auth/basic-date-time-no-ms (time/now))))))

(defn wrap-aws-date
  "clj-http middleware that adds an X-Amz-Date header into the request
  unless the request already defines a standard Date header."
  [client]
  (fn
    ([request]
     (client (ensure-aws-date request)))
    ([request respond raise]
     (client (ensure-aws-date request) respond raise))))

(defn sign [request {:keys [token] :as aws-opts}]
  (let [auth (-> request
                 auth/canonical-request
                 (auth/string-to-sign aws-opts)
                 (auth/authorization aws-opts)
                 :authorization)]
    (cond-> request
      true (assoc-in [:headers "Authorization"] auth)
      token (assoc-in [:headers "X-Amz-Security-Token"] token))))

(defn build-wrap-aws-auth
  "Build an clj-http middleware instance that adds the Authorization
  header into the outgoing request as specified by AWS Signature
  Version 4 Signing Process.

  Takes an aws parameter map with keys:
  * region - AWS region, e.g. 'us-east-1'
  * service - The service, e.g. 'iam' or 'es'
  * access-key - AWS access key
  * secret-key - AWS secret key
  * token - AWS session token in case temporary security credentials are used

  Expects the request to define either a Date or an X-Amz-Date header.
  Use wrap-aws-date middleware to ensure one of these is in place."
  [{:keys [region service access-key secret-key token] :as aws-opts}]
  {:pre [(some? region) (some? service)
         (some? access-key) (some? secret-key)]}
  (fn [client]
    (fn
      ([request]
       (client (sign request aws-opts)))
      ([request respond raise]
       (client (sign request aws-opts) respond raise)))))

(defn build-wrap-aws-auth-all [params]
  (let [aws-auth (build-wrap-aws-auth params)]
    (fn [client]
      (-> client
          (aws-auth)
          (wrap-aws-date)
          (http/wrap-url)))))
