(ns aws-sig4.middleware
  (:require [clojure.string :as str]
            [clj-time.format :as format]
            [clj-time.core :as time]
            [aws-sig4.auth :as auth]))

(defn wrap-aws-date
  "clj-http middleware that adds an X-Amz-Date header into the request
  unless the request already defines a standard Date header."
  [client]
  (fn [request]
    (let [headers (->> request
                       :headers
                       (map (fn [[n v]]
                              [(str/lower-case n) v]))
                       (into {}))]
      (if (or (headers "date") (headers "x-amz-date"))
        (client request)
        (client (assoc-in request
                          [:headers "X-Amz-Date"]
                          (format/unparse auth/basic-date-time-no-ms (time/now))))))))


(defn build-wrap-aws-auth
  "Build an clj-http middleware instance that adds the Authorization
  header into the outgoing request as specified by AWS Signature
  Version 4 Signing Process.

  Takes an aws parameter map with keys:
  * region - AWS region, e.g. 'us-east-1'
  * service - The service, e.g. 'iam' or 'es'
  * access-key - AWS access key
  * secret-key - AWS secret key

  Expects the request to define either a Date or an X-Amz-Date header.
  Use wrap-aws-date middleware to ensure one of these is in place."
  [{:keys [region service access-key secret-key] :as aws-opts}]
  {:pre [(some? region) (some? service)
         (some? access-key) (some? secret-key)]}
  (fn [client]
    (fn [request]
      (let [auth (-> request
                     auth/canonical-request
                     (auth/string-to-sign aws-opts)
                     (auth/authorization aws-opts)
                     :authorization)]
        (client (assoc-in request [:headers "Authorization"] auth))))))
