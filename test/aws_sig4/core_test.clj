(ns aws-sig4.core-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [aws-sig4.core :refer :all]))


(def ^:const tc-dir "aws4_testsuite/")

(defn- read-req [tc]
  (slurp (io/resource (str tc-dir tc ".req"))))

(defn- ->request-map
  "Parse the given HTTP request string to clj-http request map."
  [request-str]
  (-> (let [lines (str/split-lines request-str)
            tokens (-> lines first (str/split #" +"))
            method (first tokens)
            path (->> tokens rest drop-last (apply str))
            [headers body] (split-with (complement empty?)
                                       (rest lines))
            headers (->> headers
                         (map (fn [hstr]
                                (let [n (.indexOf hstr ":")
                                      [f s] (split-at n hstr)]
                                  [(apply str f)
                                   (apply str (rest s))])))
                         (into {}))
            host (get headers "Host")]
        {:method (keyword (.toLowerCase method))
         :url (str "http://" host path)
         :headers (dissoc headers "Host")
         :body (first (rest body))})
      ((http/wrap-url identity))
      ((http/wrap-method identity))))

(defn- read-can [tc]
  (slurp (io/resource (str tc-dir tc ".creq"))))

(deftest "get-vanilla-query"
  (let [req-cmd (-> "get-vanilla-query" read-req ->request-cmd)
        can-req (read-can "get-vanilla-query")]))

(comment
  (require '[clj-http.client :as http])
  (def last-req (atom nil))
  (defn store-request [client]
    (fn [request]
      (reset! last-req request)
      (client request)))
  (http/with-middleware [store-request http/wrap-url http/wrap-method]
    (http/get "http://host.foo.com/"))

  )

(comment
  (-> "get-vanilla-query" read-req ->request-map)
  (-> "get-space" read-req ->request-map)
  (-> "get-slashes" read-req ->request-map)
  (-> "post-vanilla-query-space" read-req ->request-map)
  (-> "post-x-www-form-urlencoded" read-req ->request-map)
  (-> "post-x-www-form-urlencoded-parameters" read-req ->request-map)
  )
