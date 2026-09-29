(ns rightmove-alerter.handler
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [cognitect.aws.client.api :as aws]
   [rightmove-alerter.alerter :as alerter]
   [rightmove-alerter.config :as config]
   [rightmove-alerter.notify :as notify]
   [rightmove-alerter.scraper :as scraper]
   [rightmove-alerter.store :as store]
   [taoensso.timbre :as log])
  (:gen-class
   :implements [com.amazonaws.services.lambda.runtime.RequestStreamHandler]))

(def ^:private deps
  (delay
    (let [{:keys [table-name topic-arn]} (config/env)
          store {:client (aws/client {:api :dynamodb}) :table-name table-name}
          sns (aws/client {:api :sns})]
      {:scrape scraper/scrape
       :unseen-ids #(store/unseen-ids store %)
       :notify! #(notify/publish! sns topic-arn (notify/email %))
       :mark-seen! #(store/mark-seen! store %)})))

(defn -handleRequest
  [_ input-stream output-stream _]
  (let [searches (config/searches (json/parse-stream (io/reader input-stream) true))
        summary (alerter/alert! @deps searches)]
    (log/info "Finished" summary)
    (with-open [writer (io/writer output-stream)]
      (json/generate-stream summary writer))))
