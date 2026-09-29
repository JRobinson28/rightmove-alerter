(ns rightmove-alerter.config
  (:require
   [clj-http.client :as http]
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
  or a sequence of them. Each search gets a `:name`, defaulting to its location."
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

(defn- endpoint-override
  [url]
  (let [{:keys [scheme server-name server-port]} (http/parse-url url)]
    (cond-> {:protocol scheme :hostname server-name}
      server-port (assoc :port server-port))))

(defn env
  "Reads the settings from `getenv`. TABLE_NAME and TOPIC_ARN are required.
  AWS_ENDPOINT_URL and RIGHTMOVE_BASE_URL are only set by the acceptance
  tests, to point the function at fakes."
  ([] (env #(System/getenv %)))
  ([getenv]
   (let [required {:table-name (getenv "TABLE_NAME")
                   :topic-arn (getenv "TOPIC_ARN")}
         missing (keep (fn [[k v]] (when (str/blank? v) k)) required)
         aws-endpoint (getenv "AWS_ENDPOINT_URL")
         rightmove-base-url (getenv "RIGHTMOVE_BASE_URL")]
     (when (seq missing)
       (throw (ex-info "Missing environment variables" {:missing (vec missing)})))
     (cond-> required
       (not (str/blank? aws-endpoint)) (assoc :aws-endpoint (endpoint-override aws-endpoint))
       (not (str/blank? rightmove-base-url)) (assoc :rightmove-base-url rightmove-base-url)))))
