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
