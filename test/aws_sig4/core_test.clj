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

(defn- read-sts [tc]
  (slurp (io/resource (str tc-dir tc ".sts"))))

(defn- read-authz [tc]
  (slurp (io/resource (str tc-dir tc ".authz"))))

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
  (let [basename (name testcase)
        opts {:region "us-east-1"
              :service "host"
              :access-key "AKIDEXAMPLE"
              :secret-key "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"}]
    `(deftest ~testcase
       (let [request# (-> ~basename read-req ->request-map)
             canonical-req# (aws-sig4/canonical-request request#)
             sts-req# (aws-sig4/string-to-sign canonical-req# ~opts)
             authorization-req# (aws-sig4/authorization sts-req# ~opts)
             expected-canonical-req# (read-can ~basename)
             expected-sts# (read-sts ~basename)
             expected-auth# (read-authz ~basename)]
         (is (= expected-canonical-req#
                (:canonical-request canonical-req#))
             "Canonical request")
         (is (= expected-sts#
                (:string-to-sign sts-req#))
             "String to sign")
         (is (= expected-auth#
                (:authorization authorization-req#))
             "Authorization header value")))))


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
        date-header-date (time/date-time 2011 9 9 23 36 00)
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


(deftest calculating-signature
  (let [opts {:region "us-east-1"
              :service "iam"
              :access-key "AKIDEXAMPLE"
              :secret-key "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"}
        sts  (str "AWS4-HMAC-SHA256\n"
                  "20150830T123600Z\n"
                  "20150830/us-east-1/iam/aws4_request\n"
                  "f536975d06c0309214f805bb90ccff089219ecd68b2577efef23edd43b7e1a59")
        skey (apply aws-sig4/signing-key
                    (->> (assoc opts :date-str "20150830")
                         (into [])
                         flatten))]
    (is (= [196 175 177 204 87 113 216 113 118 58 57 62 68 183 3 87 27 85 204 40 66 77 26 94 134 218 110 211 193 84 164 185]
           (->> skey
                (into [])
                (mapv #(mod % 256))))
        "Signing key")
    (is (= "5d672d79c15b13162d9279b0855cfba6789a8edb4c82c400e06b5924a6f2b5d7"
           (aws-sig4/calc-signature skey sts))
        "Signature")))

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

  (:string-to-sign (aws-sig4/string-to-sign (aws-sig4/canonical-request (-> "get-vanilla-query" read-req ->request-map))))
  (read-sts "get-vanilla-query")

  (let [opts {:region "us-east-1"
              :service "iam"
              :access-key "AKIDEXAMPLE"
              :secret-key "K7MDENG+bPxRfiCYEXAMPLEKEY"}]
    (-> "get-vanilla-query"
        read-req
        ->request-map
        aws-sig4/canonical-request
        (aws-sig4/string-to-sign opts)
        (aws-sig4/authorization opts)))

  (-> "get-vanilla-empty-query-key" read-req ->request-map)
  (-> "get-space" read-req ->request-map)
  (-> "get-slashes" read-req ->request-map)
  (-> "post-vanilla-query-space" read-req ->request-map)
  (-> "post-x-www-form-urlencoded" read-req ->request-map)
  (-> "post-x-www-form-urlencoded-parameters" read-req ->request-map)
  )
