(ns aws-sig4.core-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-http.client :as http]
            [aws-sig4.core :refer :all]))


(def ^:const tc-dir "aws4_testsuite/")

(defn- read-req [tc]
  (slurp (io/resource (str tc-dir tc ".req"))))

(defn- read-can [tc]
  (slurp (io/resource (str tc-dir tc ".creq"))))

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
            host (or (get headers "Host") (get headers "host"))]
        {:method (keyword (.toLowerCase method))
         :url (str "http://" host path)
         :headers (dissoc headers "Host")
         :body (first (rest body))})
      ((http/wrap-url identity))
      ((http/wrap-method identity))
      ))

(defmacro def-aws-test
  "Generate a deftest definition using the given testcase file base
  name."
  [testcase]
  (let [basename (name testcase)]
    `(deftest ~testcase
       (let [request# (-> ~basename read-req ->request-map)
             expected-canonical-req# (read-can ~basename)]
         (is (= expected-canonical-req#
                (canonical-request request#))
             "Canonical request")))))


;; The actual test cases


;; Ignoring the duplicate header tests because clj-http models headers
;; as a map. This means that multiple headers with same name are
;; eliminated when the header map is built way before the middleware
;; is invoked.
;; (def-aws-test get-header-key-duplicate)
;; (def-aws-test get-header-value-order)

(def-aws-test get-header-value-trim)
(def-aws-test get-relative)
(def-aws-test get-relative-relative)
(def-aws-test get-slash-dot-slash)
(def-aws-test get-slash-pointless-dot)
(def-aws-test get-slash)
(def-aws-test get-slashes)
(def-aws-test get-space)
(def-aws-test get-unreserved)
(def-aws-test get-utf8)
(def-aws-test get-vanilla-empty-query-key)
(def-aws-test get-vanilla-query-order-key-case)
(def-aws-test get-vanilla-query-order-key)
(def-aws-test get-vanilla-query-order-value)

(def-aws-test post-x-www-form-urlencoded-parameters)

(comment
  (run-tests)
  (read-can "get-slashes")
  (->request-map (read-req "get-slashes"))
  )

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
  (-> "get-vanilla-empty-query-key" read-req ->request-map)
  (-> "get-space" read-req ->request-map)
  (-> "get-slashes" read-req ->request-map)
  (-> "post-vanilla-query-space" read-req ->request-map)
  (-> "post-x-www-form-urlencoded" read-req ->request-map)
  (-> "post-x-www-form-urlencoded-parameters" read-req ->request-map)
  )
