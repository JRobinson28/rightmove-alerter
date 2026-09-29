(ns rightmove-alerter.alerter-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [rightmove-alerter.alerter :as alerter]))

(defn- listing [id] {:id id :url (str "https://www.rightmove.co.uk/properties/" id)})

(defn- run-alert
  [results seen searches]
  (let [calls (atom [])
        deps {:scrape (fn [{:keys [name]}]
                        (let [result (get results name)]
                          (if (instance? Throwable result) (throw result) result)))
              :unseen-ids (fn [ids] (swap! calls conj [:unseen-ids ids]) (into #{} (remove seen) ids))
              :notify! (fn [results] (swap! calls conj [:notify! results]))
              :mark-seen! (fn [listings] (swap! calls conj [:mark-seen! listings]))}
        outcome (try {:summary (alerter/alert! deps searches)}
                     (catch clojure.lang.ExceptionInfo e {:error e}))]
    (assoc outcome :calls @calls)))

(deftest new-listings-test
  (let [{:keys [summary calls]} (run-alert {"Home" [(listing "1") (listing "2")]}
                                           #{"1"}
                                           [{:name "Home"}])]
    (testing "only unseen listings are notified, before being marked seen"
      (is (= [[:unseen-ids ["1" "2"]]
              [:notify! [{:search {:name "Home"} :listings [(listing "2")]}]]
              [:mark-seen! [(listing "2")]]]
             calls)))
    (is (= {:searches 1 :new-listings 1} summary))))

(deftest nothing-new-test
  (let [{:keys [summary calls]} (run-alert {"Home" [(listing "1")]} #{"1"} [{:name "Home"}])]
    (is (= [[:unseen-ids ["1"]]] calls))
    (is (= {:searches 1 :new-listings 0} summary))))

(deftest cross-search-dedup-test
  (testing "a listing found by several searches is only included under the first"
    (let [{:keys [calls]} (run-alert {"Home" [(listing "1") (listing "2")]
                                      "Work" [(listing "2") (listing "3")]
                                      "Gym" [(listing "3")]}
                                     #{}
                                     [{:name "Home"} {:name "Work"} {:name "Gym"}])]
      (is (= [[:unseen-ids ["1" "2" "3"]]
              [:notify! [{:search {:name "Home"} :listings [(listing "1") (listing "2")]}
                         {:search {:name "Work"} :listings [(listing "3")]}]]
              [:mark-seen! [(listing "1") (listing "2") (listing "3")]]]
             calls)))))

(deftest failed-search-test
  (let [{:keys [error calls]} (run-alert {"Home" (ex-info "Rightmove is down" {})
                                          "Work" [(listing "1")]}
                                         #{}
                                         [{:name "Home"} {:name "Work"}])]
    (testing "the other searches still alert"
      (is (= [[:unseen-ids ["1"]]
              [:notify! [{:search {:name "Work"} :listings [(listing "1")]}]]
              [:mark-seen! [(listing "1")]]]
             calls)))
    (testing "the failure is rethrown at the end"
      (is (= "1 of 2 searches failed" (ex-message error)))
      (is (= {:failed ["Home"]} (ex-data error)))
      (is (= "Rightmove is down" (ex-message (ex-cause error)))))))

(deftest notify-failure-test
  (testing "listings aren't marked seen if the notification fails"
    (let [marked (atom [])]
      (is (thrown? Exception
                   (alerter/alert! {:scrape (constantly [(listing "1")])
                                    :unseen-ids set
                                    :notify! (fn [_] (throw (Exception. "SNS down")))
                                    :mark-seen! #(swap! marked into %)}
                                   [{:name "Home"}])))
      (is (empty? @marked)))))
