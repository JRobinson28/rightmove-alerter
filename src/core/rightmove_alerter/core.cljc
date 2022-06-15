(ns core.rightmove-alerter.core
  (:require
   [core.rightmove-alerter.aws :as aws]
   [core.rightmove-alerter.scraper :as scraper]
   [taoensso.timbre :as log])
  (:gen-class
   :implements [com.amazonaws.services.lambda.runtime.RequestStreamHandler]))


(def my-event {:location "REGION%5E20676"
               :max-price 1400
               :min-bedrooms 1
               :max-bedrooms 2
               :radius 1.0})

(defn -handleRequest
  [_ _ _ _]
  (let [urls (scraper/scrape-page my-event)]
    (log/info "Scraped" (count urls) "URLs")
    (aws/alert-new-uploads urls)))


(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (-handleRequest 1 2 3 4))
