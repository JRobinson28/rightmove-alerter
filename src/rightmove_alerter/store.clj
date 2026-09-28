(ns rightmove-alerter.store
  (:require
   [amazonica.aws.dynamodbv2 :as dynamo]
   [clojure.set :as set]
   [rightmove-alerter.config :as config]
   [taoensso.timbre :as log]))

(defn upload-urls
  [urls]
  (log/info "Uploading" (count urls) "URLS to DynamoDB")
  (mapv #(dynamo/put-item config/aws-creds
                          :table-name config/table-name
                          :item {:URL %}) urls))

(defn determine-new-uploads
  [urls]
  (let [previous (->> (dynamo/scan config/aws-creds
                                   :table-name config/table-name)
                      :items
                      (map :URL)
                      set)]
    (log/info (count previous) "URLS present in table")
    (set/difference (set urls) previous)))
