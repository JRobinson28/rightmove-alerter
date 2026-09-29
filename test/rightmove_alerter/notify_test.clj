(ns rightmove-alerter.notify-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [cognitect.aws.client.test-double :as test-double]
   [rightmove-alerter.notify :as notify]))

(def ^:private listing
  {:id "1"
   :url "https://www.rightmove.co.uk/properties/1"
   :price "£1,200 pcm"
   :address "Redland Road, Bristol"
   :bedrooms 2})

(deftest email-test
  (testing "listings are grouped by search, with a count in the subject"
    (is (= {:subject "3 new Rightmove listings"
            :message (str "Home (2 new)\n\n"
                          "£1,200 pcm · 2 bed · Redland Road, Bristol\n"
                          "https://www.rightmove.co.uk/properties/1\n\n"
                          "£900 pcm · Studio · Cotham Hill\n"
                          "https://www.rightmove.co.uk/properties/2\n\n"
                          "Work (1 new)\n\n"
                          "£1,000 pcm · 1 bed · Temple Quay\n"
                          "https://www.rightmove.co.uk/properties/3")}
           (notify/email [{:search {:name "Home"}
                           :listings [listing
                                      {:id "2" :url "https://www.rightmove.co.uk/properties/2"
                                       :price "£900 pcm" :address "Cotham Hill" :bedrooms 0}]}
                          {:search {:name "Work"}
                           :listings [{:id "3" :url "https://www.rightmove.co.uk/properties/3"
                                       :price "£1,000 pcm" :address "Temple Quay" :bedrooms 1}]}]))))

  (testing "a single listing isn't pluralised"
    (is (= "1 new Rightmove listing"
           (:subject (notify/email [{:search {:name "Home"} :listings [listing]}])))))

  (testing "missing details are left out"
    (is (= "Home (1 new)\n\nRedland Road, Bristol\nhttps://www.rightmove.co.uk/properties/1"
           (:message (notify/email [{:search {:name "Home"}
                                     :listings [(dissoc listing :price :bedrooms)]}]))))))

(deftest publish-test
  (testing "publishes the email to the topic"
    (let [requests (atom [])
          client (test-double/client {:api :sns
                                      :ops {:Publish (fn [{:keys [request]}]
                                                       (swap! requests conj request)
                                                       {:MessageId "m-1"})}})]
      (notify/publish! client "arn:aws:sns:eu-west-1:123456789012:topic" {:subject "S" :message "M"})
      (is (= [{:TopicArn "arn:aws:sns:eu-west-1:123456789012:topic" :Subject "S" :Message "M"}]
             @requests))))

  (testing "throws when SNS returns an error"
    (let [client (test-double/client {:api :sns
                                      :ops {:Publish {:cognitect.anomalies/category :cognitect.anomalies/forbidden}}})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"SNS Publish failed"
                            (notify/publish! client "arn:aws:sns:eu-west-1:123456789012:topic"
                                             {:subject "S" :message "M"}))))))
