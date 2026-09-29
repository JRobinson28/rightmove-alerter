(ns rightmove-alerter.alerter
  (:require
   [taoensso.timbre :as log]))

(defn- scrape-all
  [scrape searches]
  (for [search searches]
    (try
      (let [listings (scrape search)]
        (log/info "Scraped" (count listings) "listings for" (pr-str (:name search)))
        {:search search :listings listings})
      (catch Exception e
        (log/error e "Scrape failed for" (pr-str (:name search)))
        {:search search :error e}))))

(defn- dedupe-across-searches
  "Keeps each listing only under the first search that found it."
  [results]
  (first (reduce (fn [[deduped seen] result]
                   (let [listings (into [] (remove (comp seen :id)) (:listings result))]
                     [(conj deduped (assoc result :listings listings))
                      (into seen (map :id) listings)]))
                 [[] #{}]
                 results)))

(defn alert!
  "Scrapes each search and sends one alert for any listings not seen before.

  `deps` is a map of functions:
    :scrape      search -> listings
    :unseen-ids  ids -> the set of those ids not seen before
    :notify!     results -> nil, where results is [{:search :listings}]
    :mark-seen!  listings -> nil"
  [{:keys [scrape unseen-ids notify! mark-seen!]} searches]
  (let [results (doall (scrape-all scrape searches))
        failed (filter :error results)
        succeeded (dedupe-across-searches (remove :error results))
        unseen (unseen-ids (into [] (comp (mapcat :listings) (map :id)) succeeded))
        new-results (into []
                          (comp (map (fn [result] (update result :listings #(filterv (comp unseen :id) %))))
                                (filter (comp seq :listings)))
                          succeeded)
        new-listings (into [] (mapcat :listings) new-results)]
    (if (seq new-listings)
      (do (notify! new-results)
          (mark-seen! new-listings))
      (log/info "No new listings"))
    (when (seq failed)
      (throw (ex-info (str (count failed) " of " (count searches) " searches failed")
                      {:failed (mapv (comp :name :search) failed)}
                      (:error (first failed)))))
    {:searches (count searches)
     :new-listings (count new-listings)}))
