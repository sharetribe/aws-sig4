(ns aws-sig4.auth
  (:require [clojure.string :as str]
            [pathetic.core :as pathetic]
            [buddy.core.hash :as hash]
            [buddy.core.mac :as mac]
            [buddy.core.codecs :as codecs]
            [clj-time.core :as time]
            [clj-time.format :as format])
  (:import [org.apache.http.entity StringEntity]))

(def nl (with-out-str (newline)))
(def rfc-1123-formatter (format/formatter "dd MMM yyyy HH:mm:ss 'GMT'"
                                          time/utc))
(defn- parse-rfc1123
  "Parse a date string from RFC1123 format by dropping the redundant
  weekday from the start effectively prioritizing day-of-month over
  weekday in case the two are in conflict."
  [s]
  (some->> s
           (re-seq #"\S+")
           rest
           (str/join " ")
           (format/parse rfc-1123-formatter)))

(def basic-date-time-no-ms (format/formatters :basic-date-time-no-ms))
(def basic-date (format/formatters :basic-date))

(defn trim-all [s]
  (if-some [trimmed (some->> s (re-seq #"\".*?\"|\S+") (str/join " "))]
    trimmed
    ""))

(defn- canonical-uri
  [^String uri]
  (if (empty? uri)
    "/"
    (let [^String normalized (-> uri
                                 pathetic/normalize
                                 (str/replace #"\*" "%2A")
                                 (str/replace #"," "%2C"))]
      (if (and (> (.length normalized) 1)
               (= (.charAt uri (- (.length uri) 1)) \/))
        (str normalized "/")
        normalized))))

(defn- query-param-comp
  [[p1n p1v] [p2n p2v]]
  (let [name-order (compare p1n p2n)]
    (if (= name-order 0)
      (compare p1v p2v)
      name-order)))

(defn- canonical-query-string [{:keys [query-params query-string]}]
  (let [query-params' (if query-string
                       (->> (str/split query-string #"&")
                            (map #(str/split % #"=")))
                       query-params)]
    (if-not (empty? query-params')
      (->> (or query-params')
           (map (fn [[name value]]
                  [name value]))
           (sort query-param-comp)
           (map #(str/join "=" %))
           (str/join "&"))
      "")))


(defn- parse-date [headers]
  (if-let [request-time (or
                         (some->> (headers "x-amz-date")
                                  (format/parse basic-date-time-no-ms))
                         (some->> (headers "date")
                                  parse-rfc1123))]
    request-time
    (throw (ex-info (str "Request must define either X-Amz-Date or Date header "
                         "in order to be signed with the AWS v4 signature.")
                    {:headers headers}))))

(defn- normalize-headers [headers server-name]
  (->> (assoc headers "host" server-name)
       (map (fn [[header-n header-v]]
              [(str/lower-case header-n)
               (trim-all header-v)]))
       (into {})))

(defn- canonical-headers [normalized]
  (->> normalized
       (sort-by first)
       (map #(str (str/join ":" %) nl))
       str/join))

(defn- signed-headers [normalized]
  (->> normalized
       (map first)
       sort
       (map str/lower-case)
       (str/join ";")))

(defn hashed-payload [body]
  (let [body-is-or-s (if (and (instance? StringEntity body)
                              (.isRepeatable ^StringEntity body))
                       (.getContent ^StringEntity body)
                       body)]
    ((fnil (comp codecs/bytes->hex hash/sha256) "") body-is-or-s)))

(defn canonical-request
  "Build a canonical request string from the given clj-http request
  map."
  [request]
  (let [{:keys [request-method method uri query-params query-string
                headers server-name body]} request

        normalized-headers (normalize-headers headers server-name)
        request-time (parse-date normalized-headers)
        signed-headers (signed-headers normalized-headers)
        method (or method request-method)
        parts [(str/upper-case (name method))
               (canonical-uri uri)
               (canonical-query-string {:query-string query-string
                                        :query-params query-params })
               (canonical-headers normalized-headers)
               signed-headers
               (hashed-payload body)]]
    {:request request
     :canonical-request (str/join nl parts)
     :request-time request-time
     :signed-headers signed-headers}))

(defn string-to-sign [aws-request {:keys [region service]}]
  (let [{:keys [canonical-request request-time]} aws-request
        date-stamp (format/unparse basic-date request-time)
        credential-scope (str/join "/"
                                   [date-stamp region service "aws4_request"])
        algorithm "AWS4-HMAC-SHA256"
        parts [algorithm
               (format/unparse basic-date-time-no-ms request-time)
               credential-scope
               (-> canonical-request hash/sha256 codecs/bytes->hex)]]

    (assoc aws-request
           :string-to-sign (str/join nl parts)
           :date-stamp date-stamp
           :credential-scope credential-scope
           :algorithm algorithm)))


(defn- hmac-sha256 [key data]
  (mac/hash data {:key key :alg :hmac+sha256}))

(defn signing-key
  [& {:keys [region service secret-key date-str]}]
  (-> (str "AWS4" secret-key)
      (hmac-sha256 date-str)
      (hmac-sha256 region)
      (hmac-sha256 service)
      (hmac-sha256 "aws4_request")))

(defn calc-signature [skey sts]
  (codecs/bytes->hex (hmac-sha256 skey sts)))

(defn authorization [aws-request {:keys [region service access-key secret-key]}]
  (let [{:keys [string-to-sign algorithm date-stamp
                credential-scope signed-headers]} aws-request
        skey (signing-key :region region
                          :service service
                          :secret-key secret-key
                          :date-str date-stamp)
        signature (calc-signature skey string-to-sign)
        authorization (str algorithm " "
                           "Credential=" access-key "/" credential-scope ", "
                           "SignedHeaders=" signed-headers ", "
                           "Signature=" signature)]
    (assoc aws-request :authorization authorization)))
