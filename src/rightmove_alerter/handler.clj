(ns rightmove-alerter.handler
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [rightmove-alerter.alerter :as alerter]
   [rightmove-alerter.scraper :as scraper]
   [taoensso.timbre :as log])
  (:gen-class
   :implements [com.amazonaws.services.lambda.runtime.RequestStreamHandler]))

(defn- input-stream->json
  [input-stream]
  (json/parse-stream (io/reader input-stream) true))

(defn -handleRequest
  [_ input-stream _ _]
  (let [event-map (input-stream->json input-stream)
        urls (scraper/scrape-page event-map)]
    (log/info "Scraped" (count urls) "URLs")
    (alerter/alert-new-uploads urls)))
