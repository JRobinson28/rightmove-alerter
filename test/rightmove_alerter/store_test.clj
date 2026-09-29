(ns rightmove-alerter.store-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.walk :as walk]
   [cognitect.aws.client.test-double :as test-double]
   [rightmove-alerter.store :as store]))

(defn- fake-table
  "A store backed by an in-memory table. Responses are keywordized, as the real
  aws-api client does. `unprocessed-first` makes each listed op report its first
  request as entirely unprocessed, to exercise the retries."
  [{:keys [seen unprocessed-first]}]
  (let [items (atom (into {} (map (fn [id] [id {"id" {:S id}}])) seen))
        calls (atom [])
        first-call? (fn [op] (and (contains? unprocessed-first op)
                                  (= 1 (count (filter #{op} (map :op @calls))))))
        handle (fn [f]
                 (fn [{:keys [op request]}]
                   (swap! calls conj {:op op :request request})
                   (walk/keywordize-keys (f op request))))
        client (test-double/client
                {:api :dynamodb
                 :ops {:BatchGetItem
                       (handle (fn [op request]
                                 (let [[table {:keys [Keys]}] (first (:RequestItems request))]
                                   (if (first-call? op)
                                     {:Responses {table []} :UnprocessedKeys (:RequestItems request)}
                                     {:Responses {table (keep #(get @items (get-in % ["id" :S])) Keys)}}))))
                       :BatchWriteItem
                       (handle (fn [op request]
                                 (if (first-call? op)
                                   {:UnprocessedItems (:RequestItems request)}
                                   (do (doseq [{{item :Item} :PutRequest} (val (first (:RequestItems request)))]
                                         (swap! items assoc (get-in item ["id" :S]) item))
                                       {}))))}})]
    {:store {:client client :table-name "seen-listings"}
     :items items
     :calls calls}))

(defn- ids [n] (map str (range n)))

(deftest unseen-ids-test
  (testing "returns only ids that aren't in the table, batching 100 keys per request"
    (let [{:keys [store calls]} (fake-table {:seen ["5" "150" "249"]})]
      (is (= (set (remove #{"5" "150" "249"} (ids 250)))
             (store/unseen-ids store (ids 250))))
      (is (= [100 100 50]
             (map #(count (get-in % [:request :RequestItems "seen-listings" :Keys])) @calls)))))

  (testing "duplicate ids are only requested once"
    (let [{:keys [store calls]} (fake-table {})]
      (is (= #{"1"} (store/unseen-ids store ["1" "1"])))
      (is (= 1 (count (get-in (first @calls) [:request :RequestItems "seen-listings" :Keys]))))))

  (testing "no ids means no requests"
    (let [{:keys [store calls]} (fake-table {})]
      (is (= #{} (store/unseen-ids store [])))
      (is (empty? @calls))))

  (testing "unprocessed keys are retried"
    (let [{:keys [store calls]} (fake-table {:seen ["1"] :unprocessed-first #{:BatchGetItem}})]
      (is (= #{"2"} (store/unseen-ids store ["1" "2"])))
      (is (= 2 (count @calls))))))

(deftest mark-seen-test
  (testing "writes listings with an expiry, batching 25 items per request"
    (let [{:keys [store items calls]} (fake-table {})
          listings (map (fn [id] {:id id :url (str "https://www.rightmove.co.uk/properties/" id)}) (ids 60))
          now (quot (System/currentTimeMillis) 1000)]
      (store/mark-seen! store listings)
      (is (= [25 25 10] (map #(count (get-in % [:request :RequestItems "seen-listings"])) @calls)))
      (is (= (set (ids 60)) (set (keys @items))))
      (let [{:strs [url expires_at]} (get @items "7")]
        (is (= {:S "https://www.rightmove.co.uk/properties/7"} url))
        (is (< (+ now (* 59 24 60 60)) (parse-long (:N expires_at)) (+ now (* 61 24 60 60)))))))

  (testing "unprocessed items are retried"
    (let [{:keys [store items calls]} (fake-table {:unprocessed-first #{:BatchWriteItem}})]
      (store/mark-seen! store [{:id "1" :url "u"}])
      (is (= 2 (count @calls)))
      (is (contains? @items "1")))))

(deftest errors-test
  (testing "DynamoDB errors throw"
    (let [client (test-double/client {:api :dynamodb
                                      :ops {:BatchGetItem {:cognitect.anomalies/category :cognitect.anomalies/unavailable}}})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"DynamoDB BatchGetItem failed"
                            (store/unseen-ids {:client client :table-name "t"} ["1"])))))

  (testing "gives up if items stay unprocessed"
    (let [client (test-double/client {:api :dynamodb
                                      :ops {:BatchWriteItem (fn [{:keys [request]}]
                                                              (walk/keywordize-keys {:UnprocessedItems (:RequestItems request)}))}})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"still had unprocessed items"
                            (store/mark-seen! {:client client :table-name "t"} [{:id "1" :url "u"}]))))))
