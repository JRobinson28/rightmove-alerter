(ns rightmove-alerter.config)

(def aws-creds {:endpoint "eu-west-1"})
(def table-name "latest-homes")
(def topic-arn "arn:aws:sns:eu-west-1:110701928951:new-homes")
