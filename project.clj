(defproject org.sharetribe/aws-sig4 "0.1.0-SNAPSHOT"
  :description "Middleware to add AWS signature v4 signing to clj-http requests."
  :url "http://github.com/sharetribe/aws-sig4"
  :license {:name "Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [pathetic "0.5.1"]
                 [buddy/buddy-core "0.8.1"]
                 [clj-time "0.11.0"]]
  :profiles {:dev {:dependencies [[clj-http "2.0.0"]
                                  [criterium "0.4.3"]]}})
