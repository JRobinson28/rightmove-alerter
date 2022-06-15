(ns core.rightmove-alerter.aws
  (:require
   [amazonica.aws.dynamodbv2 :as dynamo]
   [amazonica.aws.sns :as sns]
   [clojure.set :as set]
   [taoensso.timbre :as log]))

(def creds {:endpoint "eu-west-1"})

(defn upload-urls
  [urls]
  (log/info "Uploading" (count urls) "URLS to DynamoDB")
  (mapv #(dynamo/put-item creds
                          :table-name "latest-homes"
                          :item {:URL %}) urls))

(defn determine-new-uploads
  [urls]
  (let [previous (->> (dynamo/scan creds
                                   :table-name "latest-homes")
                      :items
                      (map :URL)
                      set)]
    (log/info (count previous) "URLS present in table")
    (set/difference (set urls) previous)))

(defn email-message
  [urls]
  (apply (partial str "New home(s) just uploaded to rightmove: \n") (map #(str "\n" % "\n") urls)))

(defn alert-SNS
  [new-urls]
  (if (seq new-urls)
    (do (log/info "Publishing SNS message")
        (sns/publish creds
                     :topic-arn "arn:aws:sns:eu-west-1:110701928951:new-homes"
                     :message (email-message new-urls)))
    (log/info "Not publishing SNS message")))

(defn alert-new-uploads
  [urls]
  (let [new-urls (determine-new-uploads urls)]
    (do (if (seq new-urls)
          (log/info "New URLS:" new-urls)
          (log/info "Found no new URLS"))
        (upload-urls new-urls)
        (alert-SNS new-urls))))
