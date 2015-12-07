(ns aws-sig4.core-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-http.client :as http]
            [aws-sig4.core :as aws-sig4]
            [clj-time.format :as format]
            [clj-time.core :as time])

  (:import [java.net URL]))


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
            ;; path (->> tokens rest drop-last (apply str))
            path (->> tokens second)
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
      ((http/wrap-method identity))))

(defmacro def-aws-test
  "Generate a deftest definition using the given testcase file base
  name."
  [testcase]
  (let [basename (name testcase)]
    `(deftest ~testcase
       (let [request# (-> ~basename read-req ->request-map)
             expected-canonical-req# (read-can ~basename)]
         (is (= expected-canonical-req#
                (:canonical-request (aws-sig4/canonical-request request#)))
             "Canonical request")))))


;; The actual test cases

(deftest header-trimming
  (is (= ""
         (aws-sig4/trim-all nil)))
  (is (= ""
         (aws-sig4/trim-all "")))
  (is (= "foobar"
         (aws-sig4/trim-all "foobar")))
  (is (= "foo bar"
         (aws-sig4/trim-all "  foo  bar ")))
  (is (= "- foo bar baz"
         (aws-sig4/trim-all "\t\n\n  -  foo  bar   baz")))
  (is (= "\"foo  bar\""
         (aws-sig4/trim-all "\"foo  bar\"")))
  (is (= "foo \"bar  baz  \" yarr"
         (aws-sig4/trim-all "  foo  \"bar  baz  \" yarr ")))
  (is (= "foo \"bar  baz  \" ya rr \"bar  baz  \""
         (aws-sig4/trim-all "  foo  \"bar  baz  \" ya  rr \"bar  baz  \" "))))

(def rfc1123-formatter (format/formatter "EEE, dd MMM yyyy HH:mm:ss 'GMT'"
                                         time/utc))
(deftest date-header-parsing
  (let [date-header-str "Mon, 09 Sep 2011 23:36:00 GMT"
        date-header-date (format/parse rfc1123-formatter date-header-str)
        x-amz-date (time/date-time 2015 12 06 16 40 22)
        x-amz-date-str (format/unparse (format/formatters :basic-date-time-no-ms)
                                x-amz-date)]
    (is (= date-header-date
           (-> "get-vanilla-query"
               read-req
               ->request-map
               aws-sig4/canonical-request
               :request-time))
        "Parse Date header")
    (is (= x-amz-date
           (-> "get-vanilla-query"
               read-req
               ->request-map
               (assoc-in [:headers "X-Amz-Date"] x-amz-date-str)
               aws-sig4/canonical-request
               :request-time))
        "Prioritize X-Amz-Date")
    (is (= x-amz-date
           (-> "get-vanilla-query"
               read-req
               ->request-map
               (assoc-in [:headers "x-Amz-DATE"] x-amz-date-str)
               aws-sig4/canonical-request
               :request-time))
        "Ignore header case")))


;; AWS TEST CASES
;; ==============

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
(def-aws-test get-vanilla-query-unreserved)
(def-aws-test get-vanilla-query)
(def-aws-test get-vanilla-ut8-query)
(def-aws-test get-vanilla)

(def-aws-test post-header-key-case)
(def-aws-test post-header-key-sort)
(def-aws-test post-header-value-case)
(def-aws-test post-vanilla-empty-query-value)

;; Test skipped. java.net.URL that is used underneath by clj-http url
;; parsing drops the illegal characters here. This is mostly an issue
;; with the way tests are constructed. Default clj-http middleware
;; already takes care of url encoding query param names and values.
;; (def-aws-test post-vanilla-query-nonunreserved)

(def-aws-test post-vanilla-query-space)
(def-aws-test post-vanilla-query)
(def-aws-test post-vanilla)
(def-aws-test post-x-www-form-urlencoded-parameters)
(def-aws-test post-x-www-form-urlencoded)

(comment
  (run-tests)
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
  (aws-sig4/canonical-request (-> "get-vanilla-query" read-req ->request-map))
  (aws-sig4/canonical-request (assoc-in (-> "get-vanilla-query" read-req ->request-map)
                               [:headers "X-Amz-Date"]
                               "20150830T123600Z"))
  (-> "get-vanilla-empty-query-key" read-req ->request-map)
  (-> "get-space" read-req ->request-map)
  (-> "get-slashes" read-req ->request-map)
  (-> "post-vanilla-query-space" read-req ->request-map)
  (-> "post-x-www-form-urlencoded" read-req ->request-map)
  (-> "post-x-www-form-urlencoded-parameters" read-req ->request-map)
  )
