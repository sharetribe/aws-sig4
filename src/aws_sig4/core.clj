(ns aws-sig4.core
  (:require [clojure.string :as str]
            [pathetic.core :as pathetic]
            [buddy.core.hash :as hash]
            [buddy.core.codecs :as codecs]
            [clj-time.core :as time]
            [clj-time.format :as format])
  (:import [java.net URLEncoder]))

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
    (let [^String normalized (pathetic/normalize uri)]
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

(defn- canonical-query-string [query-string]
  (if-not (empty? query-string)
    (->> (str/split query-string #"&")
         (map #(str/split % #"="))
         (map (fn [[name value]]
                [name value]))
         (sort query-param-comp)
         (map #(str/join "=" %))
         (str/join "&"))
    ""))


(defn- parse-date [headers]
  (if-let [request-time (or
                         (some->> (headers "x-amz-date")
                                  (format/parse basic-date-time-no-ms))
                         (some->> (headers "date")
                                  parse-rfc1123))]
    request-time
    (throw (ex-info (str "Request must define either X-Amz-Date or Date "
                         "header in order to be signed with AWS v4 signature.")
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

(def hashed-payload (fnil (comp codecs/bytes->hex hash/sha256) ""))

(defn ensure-aws-date
  "clj-http middleware that adds an X-Amz-Date header into the request
  unless it already defines a standard Date header."
  [request]
  (throw (ex-info "Not implemented" {:method :ensure-aws-date})))

(defn canonical-request
  "Build a canonical request string from the given clj-http request
  map."
  [request]
  (let [{:keys [request-method uri query-string
                headers server-name body]} request
        normalized-headers (normalize-headers headers server-name)
        request-time (parse-date normalized-headers)
        parts [(str/upper-case (name request-method))
               (canonical-uri uri)
               (canonical-query-string query-string)
               (canonical-headers normalized-headers)
               (signed-headers normalized-headers)
               (hashed-payload body)]]
    {:request request
     :canonical-request (str/join nl parts)
     :request-time request-time}))

(defn string-to-sign [crequest {:keys [region service]}]
  (let [{:keys [canonical-request request-time]} crequest
        parts ["AWS4-HMAC-SHA256"
               (format/unparse basic-date-time-no-ms request-time)
               (str (format/unparse basic-date request-time) "/"
                    region "/"
                    service "/"
                    "aws4_request")
               (-> canonical-request hash/sha256 codecs/bytes->hex)]]
    (assoc crequest :string-to-sign (str/join nl parts))))

(defn signature [str-to-sign])

(defn with-signature [request sig])
