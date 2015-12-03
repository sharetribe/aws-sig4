(ns aws-sig4.core
  (:require [clojure.string :as str]
            [cemerick.url :as url]
            [buddy.core.hash :as hash]
            [buddy.core.codecs :as codecs]))

(def nl (with-out-str (newline)))

(defn- url-encode [str]
  (if-let [encoded (url/url-encode str)]
    (str/replace encoded #"%7E" "~")
    ""))

(defn- canonical-query-string [query-string]
  (if-not (empty? query-string)
    (->> (str/split query-string #"&")
         (map #(str/split % #"="))
         (map (fn [[name value]]
                [(url-encode name) (url-encode value)]))
         (sort-by first)
         (map #(str/join "=" %))
         (str/join "&"))
    ""))

(defn- canonical-headers [headers server-name]
  (let [with-host (assoc headers "host" server-name)]
    (->> with-host
         (map (fn [[header-n header-v]]
                [(str/lower-case header-n)
                 ;; Known limitation: doesn't trim sequential spaces
                 ;; from inside header values.
                 (str/trim header-v)]))
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
               (if (or (empty? uri) (= uri "/"))
                 "/"
                 (url/url-encode uri))
               (canonical-query-string query-string)
               (canonical-headers headers server-name)
               (signed-headers headers)
               (hashed-payload body)]]
    (->> parts
         (str/join nl))))

(defn string-to-sign [crequest])

(defn signature [str-to-sign])

(defn with-signature [request sig])
