(ns core.rightmove-alerter.core
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [core.rightmove-alerter.aws :as aws]
   [core.rightmove-alerter.scraper :as scraper]
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
    (aws/alert-new-uploads urls)))


(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (-handleRequest 1 2 3 4))
