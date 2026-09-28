(ns rightmove-alerter.alerter
  (:require
   [rightmove-alerter.notify :as notify]
   [rightmove-alerter.store :as store]
   [taoensso.timbre :as log]))

(defn alert-new-uploads
  [urls]
  (let [new-urls (store/determine-new-uploads urls)]
    (if (seq new-urls)
      (log/info "New URLS:" new-urls)
      (log/info "Found no new URLS"))
    (store/upload-urls new-urls)
    (notify/alert-SNS new-urls)))
