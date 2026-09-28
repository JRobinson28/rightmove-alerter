(ns rightmove-alerter.notify-test
  (:require [clojure.test :refer [deftest is]]
            [rightmove-alerter.handler]
            [rightmove-alerter.notify :as notify]))

(deftest email-message-lists-each-url
  (is (= "New home(s) just uploaded to rightmove: \n\na\n\nb\n"
         (notify/email-message ["a" "b"]))))
