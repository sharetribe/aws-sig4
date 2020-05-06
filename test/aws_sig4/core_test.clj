(ns aws-sig4.core-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-http.client :as http]
            [aws-sig4.auth :as auth]
            [aws-sig4.middleware :as aws-sig4]
            [clj-time.format :as format]
            [clj-time.core :as time])

  (:import [org.apache.http.entity StringEntity]))

(def ^:const tc-dir "aws4_testsuite/")

(defn- read-req [tc]
  (slurp (io/resource (str tc-dir tc ".req"))))

(defn- read-can [tc]
  (slurp (io/resource (str tc-dir tc ".creq"))))

(defn- read-sts [tc]
  (slurp (io/resource (str tc-dir tc ".sts"))))

(defn- read-authz [tc]
  (slurp (io/resource (str tc-dir tc ".authz"))))

(defn- read-sreq [tc]
  (slurp (io/resource (str tc-dir tc ".sreq"))))

(defn- str->request-map
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
                                  [(str/trim (apply str f))
                                   (str/trim (apply str (rest s)))])))
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
       (let [request#                (-> ~basename read-req str->request-map)
             canonical-req#          (auth/canonical-request request#)
             sts-req#                (auth/string-to-sign canonical-req#
                                                              ~opts)
             authorization-req#      (auth/authorization sts-req# ~opts)
             signed-req#             (((aws-sig4/build-wrap-aws-auth ~opts) identity) request#)
             expected-canonical-req# (read-can ~basename)
             expected-sts#           (read-sts ~basename)
             expected-auth#          (read-authz ~basename)
             expected-signed-req#    (-> ~basename read-sreq str->request-map)]
         (is (= expected-canonical-req#
                (:canonical-request canonical-req#))
             "Canonical request")
         (is (= expected-sts#
                (:string-to-sign sts-req#))
             "String to sign")
         (is (= expected-auth#
                (:authorization authorization-req#))
             "Authorization header value")
         (is (= expected-signed-req#
                signed-req#)
             "Signed request")))))


;; Test cases
;;

(deftest header-trimming
  (is (= ""
         (auth/trim-all nil)))
  (is (= ""
         (auth/trim-all "")))
  (is (= "foobar"
         (auth/trim-all "foobar")))
  (is (= "foo bar"
         (auth/trim-all "  foo  bar ")))
  (is (= "- foo bar baz"
         (auth/trim-all "\t\n\n  -  foo  bar   baz")))
  (is (= "\"foo  bar\""
         (auth/trim-all "\"foo  bar\"")))
  (is (= "foo \"bar  baz  \" yarr"
         (auth/trim-all "  foo  \"bar  baz  \" yarr ")))
  (is (= "foo \"bar  baz  \" ya rr \"bar  baz  \""
         (auth/trim-all "  foo  \"bar  baz  \" ya  rr \"bar  baz  \" "))))

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
               str->request-map
               auth/canonical-request
               :request-time))
        "Parse Date header")
    (is (= x-amz-date
           (-> "get-vanilla-query"
               read-req
               str->request-map
               (assoc-in [:headers "X-Amz-Date"] x-amz-date-str)
               auth/canonical-request
               :request-time))
        "Prioritize X-Amz-Date")
    (is (= x-amz-date
           (-> "get-vanilla-query"
               read-req
               str->request-map
               (assoc-in [:headers "x-Amz-DATE"] x-amz-date-str)
               auth/canonical-request
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
        skey (apply auth/signing-key
                    (->> (assoc opts :date-str "20150830")
                         (into [])
                         flatten))]
    (is (= [196 175 177 204 87 113 216 113 118 58 57 62 68 183 3 87 27 85 204 40 66 77 26 94 134 218 110 211 193 84 164 185]
           (->> skey
                (into [])
                (mapv #(mod % 256))))
        "Signing key")
    (is (= "5d672d79c15b13162d9279b0855cfba6789a8edb4c82c400e06b5924a6f2b5d7"
           (auth/calc-signature skey sts))
        "Signature")))

(deftest wrap-aws-date
  (let [request-with-date (-> "get-vanilla-query" read-req str->request-map)
        request-with-DAtE (update (-> "get-vanilla-query" read-req str->request-map)
                                :headers
                                (fn [headers]
                                  (let [datev (headers "Date")]
                                    (-> headers
                                        (dissoc "Date")
                                        (assoc "DAtE" datev)))))
        request-no-date (update (-> "get-vanilla-query" read-req str->request-map)
                                :headers
                                dissoc "Date")
        amz-date (format/unparse (format/formatters :basic-date-time-no-ms)
                                 (time/minus (time/now) (time/days 5)))
        request-no-date-amz (update (-> "get-vanilla-query" read-req str->request-map)
                                :headers
                                (fn [headers]
                                  (let [datev (headers "Date")]
                                    (-> headers
                                        (dissoc "Date")
                                        (assoc "X-Amz-Date" amz-date)))))]
    (is (= nil
           (get-in ((aws-sig4/wrap-aws-date identity) request-with-date)
                   [:headers "X-Amz-Date"]))
        "Request already has Date")
    (is (= nil
           (get-in ((aws-sig4/wrap-aws-date identity) request-with-DAtE)
                   [:headers "X-Amz-Date"]))
        "Request already has Date and case is ignored")
   (is (some? (get-in ((aws-sig4/wrap-aws-date identity) request-no-date)
                   [:headers "X-Amz-Date"]))
       "Request doesn't have Date. X-Amz-Date is added.")
   (is (= amz-date
          (get-in ((aws-sig4/wrap-aws-date identity) request-no-date-amz)
                  [:headers "X-Amz-Date"]))
       "No Date header but X-Amz-Date already in place")))

(deftest add-token-header
  (let [opts {:region "us-east-1"
              :service "host"
              :access-key "AKIDEXAMPLE"
              :secret-key "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"
              :token "token"}
        req (-> "get-vanilla-query" read-req str->request-map)]
    (is (= "token"
           (-> (((aws-sig4/build-wrap-aws-auth opts) identity) req)
               (get-in [:headers "X-Amz-Security-Token"])))
        "Token given")
    (is (= nil
           (-> (((aws-sig4/build-wrap-aws-auth (dissoc opts :token)) identity) req)
               (get-in [:headers "X-Amz-Security-Token"])))
        "Token not given")))

(deftest stringentity-body
  (let [opts          {:region "us-east-1"
                       :service "host"
                       :access-key "AKIDEXAMPLE"
                       :secret-key "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"}
        req           (-> "post-x-www-form-urlencoded"
                          read-req
                          str->request-map
                          (update :body #(StringEntity. %)))
        sreq          (((aws-sig4/build-wrap-aws-auth opts)
                        identity)
                       req)
        expected-sreq (-> "post-x-www-form-urlencoded"
                          read-sreq
                          str->request-map)]
    (is (= (:headers expected-sreq)
           (:headers sreq))
        "Body as StringEntity")))


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

(comment (run-tests))
