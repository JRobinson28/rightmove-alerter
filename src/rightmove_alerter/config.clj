(ns rightmove-alerter.config
  (:require
   [clojure.string :as str]))

(def ^:private numeric-keys [:min-price :max-price :min-bedrooms :max-bedrooms :radius])

(defn- search-problems
  [{:keys [location furnish-types] :as search}]
  (cond-> (vec (for [k numeric-keys
                     :let [v (get search k)]
                     :when (and (some? v) (not (number? v)))]
                 (str (name k) " must be a number")))
    (not (and (string? location) (not (str/blank? location))))
    (conj "location is required")

    (and (some? furnish-types) (not (string? furnish-types)))
    (conj "furnish-types must be a string")))

(defn searches
  "Validates the searches in an event payload, which may be a single search map
  or a sequence of them. Each search gets a `:name`, defaulting to its location.
  Throws, listing every problem, if any search is invalid."
  [event]
  (let [searches (if (map? event) [event] event)
        problems (when (sequential? searches)
                   (into {} (keep-indexed (fn [i search]
                                            (let [ps (if (map? search)
                                                       (search-problems search)
                                                       ["must be a map"])]
                                              (when (seq ps) [i ps]))))
                         searches))]
    (cond
      (or (not (sequential? searches)) (empty? searches))
      (throw (ex-info "Event must be a search or a non-empty list of searches" {:event event}))

      (seq problems)
      (throw (ex-info (str "Invalid searches: "
                           (str/join "; " (for [[i ps] problems]
                                            (str "search " i ": " (str/join ", " ps)))))
                      {:problems problems}))

      :else
      (mapv #(assoc % :name (or (:name %) (:location %))) searches))))

(defn env
  []
  (let [settings {:table-name (System/getenv "TABLE_NAME")
                  :topic-arn (System/getenv "TOPIC_ARN")}
        missing (keep (fn [[k v]] (when (str/blank? v) k)) settings)]
    (when (seq missing)
      (throw (ex-info "Missing environment variables" {:missing (vec missing)})))
    settings))
