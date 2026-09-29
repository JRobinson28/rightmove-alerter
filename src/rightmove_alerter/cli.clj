(ns rightmove-alerter.cli
  "Runs searches locally without touching AWS, printing the email that would be
  sent. Every listing counts as new, since there's no store to check against."
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.tools.cli :as cli]
   [rightmove-alerter.alerter :as alerter]
   [rightmove-alerter.config :as config]
   [rightmove-alerter.notify :as notify]
   [rightmove-alerter.scraper :as scraper]
   [taoensso.timbre :as log]))

(def ^:private cli-options
  [[nil "--search JSON" "A search as JSON, e.g. '{\"location\":\"REGION^1498\",\"max-price\":1200}'"
    :parse-fn #(json/parse-string % true)]
   [nil "--searches FILE" "A JSON file containing a list of searches"
    :parse-fn #(json/parse-string (slurp %) true)]
   ["-v" "--verbose" "Show info logging"]
   ["-h" "--help"]])

(def ^:private dry-run-deps
  {:scrape scraper/scrape
   :unseen-ids set
   :notify! (fn [results]
              (let [{:keys [subject message]} (notify/email results)]
                (println "Subject:" subject)
                (println)
                (println message)))
   :mark-seen! (constantly nil)})

(defn- usage
  [summary]
  (str "Usage: clojure -M:run (--search JSON | --searches FILE) [-v]\n\n" summary))

(defn -main
  [& args]
  (let [{:keys [options errors summary]} (cli/parse-opts args cli-options)
        event (or (:search options) (:searches options))]
    (cond
      (:help options)
      (println (usage summary))

      (or (seq errors) (nil? event))
      (do (binding [*out* *err*]
            (println (str/join "\n" (or (seq errors) ["One of --search or --searches is required"])))
            (println (usage summary)))
          (System/exit 1))

      :else
      (do (log/merge-config! {:min-level (if (:verbose options) :info :warn)})
          (println (alerter/alert! dry-run-deps (config/searches event)))))
    (shutdown-agents)))
