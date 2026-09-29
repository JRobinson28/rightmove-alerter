(ns rightmove-alerter.store
  (:require
   [cognitect.aws.client.api :as aws]
   [taoensso.timbre :as log]))

;; 60 days
(def ^:private ttl-seconds (* 60 24 60 60))

(def ^:private max-attempts 5)

;; DynamoDB limits on the number of items per batch request.
(def ^:private batch-get-limit 100)
(def ^:private batch-write-limit 25)

(defn- invoke!
  [client op request]
  (let [response (aws/invoke client {:op op :request request})]
    (when (:cognitect.anomalies/category response)
      (throw (ex-info (str "DynamoDB " (name op) " failed") response)))
    response))

(defn- batch!
  "Invokes a batch `op` with the request `->request` builds for `items`,
  retrying with backoff the items whose ids `unprocessed-ids` finds in the
  response. Returns every response."
  [client op ->request id-fn unprocessed-ids items]
  (loop [items items
         attempt 1
         responses []]
    (let [response (invoke! client op (->request items))
          responses (conj responses response)
          retry-ids (set (unprocessed-ids response))
          items (filterv (comp retry-ids id-fn) items)]
      (cond
        (empty? items)
        responses

        (= attempt max-attempts)
        (throw (ex-info (str "DynamoDB " (name op) " still had unprocessed items after retries")
                        {:unprocessed-ids (mapv id-fn items)}))

        :else
        (do (Thread/sleep (* 100 (bit-shift-left 1 attempt)))
            (recur items (inc attempt) responses))))))

(defn unseen-ids
  "Returns the subset of `ids` that aren't in the store."
  [{:keys [client table-name]} ids]
  (let [ids (distinct ids)
        ->request (fn [ids]
                    {:RequestItems {table-name {:Keys (mapv (fn [id] {"id" {:S id}}) ids)
                                                :ProjectionExpression "id"}}})
        unprocessed-ids (fn [response]
                          (->> (:UnprocessedKeys response) vals (mapcat :Keys) (map #(get-in % [:id :S]))))
        seen (into #{}
                   (comp (partition-all batch-get-limit)
                         (mapcat #(batch! client :BatchGetItem ->request identity unprocessed-ids %))
                         (mapcat #(mapcat val (:Responses %)))
                         (map #(get-in % [:id :S])))
                   ids)]
    (log/info (count seen) "of" (count ids) "listings already seen")
    (into #{} (remove seen) ids)))

(defn mark-seen!
  "Records listings as seen, so they won't be alerted on again."
  [{:keys [client table-name]} listings]
  (let [expires-at (str (+ (quot (System/currentTimeMillis) 1000) ttl-seconds))
        ->request (fn [listings]
                    {:RequestItems {table-name (mapv (fn [{:keys [id url]}]
                                                       {:PutRequest {:Item {"id" {:S id}
                                                                            "url" {:S url}
                                                                            "expires_at" {:N expires-at}}}})
                                                     listings)}})
        unprocessed-ids (fn [response]
                          (->> (:UnprocessedItems response) vals (apply concat)
                               (map #(get-in % [:PutRequest :Item :id :S]))))]
    (doseq [chunk (partition-all batch-write-limit listings)]
      (batch! client :BatchWriteItem ->request :id unprocessed-ids chunk))
    (log/info "Marked" (count listings) "listings as seen")))
