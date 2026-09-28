(ns rightmove-alerter.notify
  (:require
   [amazonica.aws.sns :as sns]
   [rightmove-alerter.config :as config]
   [taoensso.timbre :as log]))

(defn email-message
  [urls]
  (apply str "New home(s) just uploaded to rightmove: \n" (map #(str "\n" % "\n") urls)))

(defn alert-SNS
  [new-urls]
  (if (seq new-urls)
    (do (log/info "Publishing SNS message")
        (sns/publish config/aws-creds
                     :topic-arn config/topic-arn
                     :message (email-message new-urls)))
    (log/info "Not publishing SNS message")))
