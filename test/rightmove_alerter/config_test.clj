(ns rightmove-alerter.config-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [rightmove-alerter.config :as config]))

(deftest searches-test
  (testing "a single search is accepted for backwards compatibility"
    (is (= [{:location "REGION^1" :max-price 1200 :name "REGION^1"}]
           (config/searches {:location "REGION^1" :max-price 1200}))))

  (testing "a list of searches keeps its names, defaulting to the location"
    (is (= [{:location "REGION^1" :name "Home"}
            {:location "REGION^2" :name "REGION^2"}]
           (config/searches [{:location "REGION^1" :name "Home"}
                             {:location "REGION^2"}])))))

(deftest invalid-searches-test
  (testing "every problem is reported, by search index"
    (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                  #"search 1: max-price must be a number, location is required"
                                  (config/searches [{:location "REGION^1"}
                                                    {:location " " :max-price "1200"}
                                                    {:location "REGION^3" :furnish-types ["furnished"]}
                                                    "REGION^4"])))]
      (is (= {1 ["max-price must be a number" "location is required"]
              2 ["furnish-types must be a string"]
              3 ["must be a map"]}
             (:problems (ex-data e))))))

  (testing "the event must contain at least one search"
    (doseq [event [[] nil "REGION^1" 42]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-empty list of searches"
                            (config/searches event))))))

(def ^:private required-env
  {"TABLE_NAME" "seen" "TOPIC_ARN" "arn:aws:sns:eu-west-2:123456789012:alerts"})

(deftest env-test
  (testing "only the required settings are returned when the overrides aren't set"
    (is (= {:table-name "seen" :topic-arn "arn:aws:sns:eu-west-2:123456789012:alerts"}
           (config/env required-env))))

  (testing "AWS_ENDPOINT_URL becomes an aws-api endpoint override"
    (is (= {:protocol :http :hostname "aws" :port 5000}
           (:aws-endpoint (config/env (assoc required-env "AWS_ENDPOINT_URL" "http://aws:5000")))))
    (is (= {:protocol :https :hostname "example.com"}
           (:aws-endpoint (config/env (assoc required-env "AWS_ENDPOINT_URL" "https://example.com"))))))

  (testing "RIGHTMOVE_BASE_URL is passed through"
    (is (= "http://rightmove:8080"
           (:rightmove-base-url (config/env (assoc required-env "RIGHTMOVE_BASE_URL" "http://rightmove:8080"))))))

  (testing "blank overrides are ignored"
    (is (= #{:table-name :topic-arn}
           (set (keys (config/env (assoc required-env "AWS_ENDPOINT_URL" "" "RIGHTMOVE_BASE_URL" " ")))))))

  (testing "every missing required setting is reported"
    (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing environment variables"
                                  (config/env {"TABLE_NAME" " "})))]
      (is (= [:table-name :topic-arn] (:missing (ex-data e)))))))
