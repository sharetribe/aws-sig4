(ns aws-sig4.core
  (:require [clojure.string :as str]
            [pathetic.core :as pathetic]
            [buddy.core.hash :as hash]
            [buddy.core.codecs :as codecs])
  (:import [java.net URLEncoder]))

(def nl (with-out-str (newline)))

;; Adapted from
;; https://github.com/cemerick/url/blob/master/src/cemerick/url.cljx#L9
(defn url-encode
  [string]
  (some-> string
          str
          (URLEncoder/encode "UTF-8")
          (.replace "+" "%20")
          (.replace "%7E" "~")))

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

(defn- canonical-headers [headers server-name]
  (let [with-host (assoc headers "host" server-name)]
    (->> with-host
         (map (fn [[header-n header-v]]
                [(str/lower-case header-n)
                 (trim-all header-v)]))
         (sort-by first)
         (map #(str (str/join ":" %) nl))
         str/join)))

(defn- signed-headers [headers]
  (->> (assoc headers "host" "")
       (map first)
       (map str/lower-case)
       sort
       (str/join ";")))

(def hashed-payload (fnil (comp codecs/bytes->hex hash/sha256) ""))

(defn canonical-request
  "Build a canonical request string from the given clj-http request
  map."
  [request]
  (let [{:keys [request-method uri query-string
                headers server-name body]} request
        parts [(str/upper-case (name request-method))
               (canonical-uri uri)
               (canonical-query-string query-string)
               (canonical-headers headers server-name)
               (signed-headers headers)
               (hashed-payload body)]]
    (->> parts
         (str/join nl))))

(defn string-to-sign [crequest])

(defn signature [str-to-sign])

(defn with-signature [request sig])
