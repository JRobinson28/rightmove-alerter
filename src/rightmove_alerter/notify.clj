(ns rightmove-alerter.notify
  (:require
   [clojure.string :as str]
   [cognitect.aws.client.api :as aws]
   [taoensso.timbre :as log]))

(defn- bedrooms
  [n]
  (cond
    (nil? n) nil
    (zero? n) "Studio"
    :else (str n " bed")))

(defn- format-listing
  [{:keys [price address url] :as listing}]
  (str (str/join " · " (remove str/blank? [price (bedrooms (:bedrooms listing)) address]))
       "\n" url))

(defn- plural
  [n word]
  (str n " " word (when (not= 1 n) "s")))

(defn email
  "Builds the alert email for `results`, a sequence of `{:search :listings}`
  where every listing is new."
  [results]
  (let [total (transduce (map (comp count :listings)) + results)]
    {:subject (plural total "new Rightmove listing")
     :message (str/join "\n\n"
                        (for [{:keys [search listings]} results]
                          (str (:name search) " (" (count listings) " new)\n\n"
                               (str/join "\n\n" (map format-listing listings)))))}))

(defn publish!
  [client topic-arn {:keys [subject message]}]
  (log/info "Publishing" (pr-str subject))
  (let [response (aws/invoke client {:op :Publish
                                     :request {:TopicArn topic-arn
                                               :Subject subject
                                               :Message message}})]
    (when (:cognitect.anomalies/category response)
      (throw (ex-info "SNS Publish failed" response)))
    response))
