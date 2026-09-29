(ns rightmove-alerter.scraper
  (:require
   [cheshire.core :as json]
   [clj-http.client :as http]
   [clj-http.util :as http-util]
   [clojure.string :as str]
   [hickory.core :as hickory]
   [hickory.select :as select]))

(def ^:private rightmove-url "https://www.rightmove.co.uk")
(def ^:private search-path "/property-to-rent/find.html")

;; Rightmove's "Newest listed" ordering, so new listings always appear on page 1.
(def ^:private sort-newest-first 6)

(def ^:private search-params
  {:location "locationIdentifier"
   :min-price "minPrice"
   :max-price "maxPrice"
   :min-bedrooms "minBedrooms"
   :max-bedrooms "maxBedrooms"
   :radius "radius"
   :furnish-types "furnishTypes"})

(defn- build-url
  [base-url search]
  (let [params (into {"includeLetAgreed" false
                      "sortType" sort-newest-first}
                     (keep (fn [[k param]]
                             (when-some [v (get search k)]
                               [param v])))
                     search-params)]
    (str base-url search-path "?"
         (http/generate-query-string
          (update params "locationIdentifier" http-util/url-decode)))))

(defn- fetch-page
  "GETs `url` and returns the body. clj-http throws on non-2xx responses."
  [url]
  (:body (http/get url {:connection-timeout 10000
                        :socket-timeout 20000
                        ;; We don't need Rightmove's session cookie, and Apache
                        ;; HttpClient logs a warning about its expires format.
                        :cookie-policy :none})))

(defn- ->listing
  [{:keys [id propertyUrl price displayAddress bedrooms firstVisibleDate]}]
  {:id (str id)
   :url (str rightmove-url (first (str/split propertyUrl #"#")))
   :price (-> price :displayPrices first :displayPrice)
   :address displayAddress
   :bedrooms bedrooms
   :first-visible firstVisibleDate})

(defn- parse-listings
  "Extracts the non-featured listings from a search results page. Throws if the
  page doesn't have the expected structure, so that a page redesign shows
  up as an error rather than as a search that never finds anything."
  [html]
  (let [script (->> (hickory/as-hickory (hickory/parse html))
                    (select/select (select/id "__NEXT_DATA__"))
                    first
                    :content
                    first)
        properties (some-> script
                           (json/parse-string true)
                           (get-in [:props :pageProps :searchResults :properties]))]
    (when-not (sequential? properties)
      (throw (ex-info "Could not find search results in Rightmove page"
                      {:found-script? (some? script)})))
    (into [] (comp (remove :featuredProperty) (map ->listing)) properties)))

(defn scrape
  "Returns the current listings for a search, newest first. `:base-url` sends
  the request somewhere other than Rightmove (for tests); the listing URLs
  always point at Rightmove."
  ([search] (scrape search {}))
  ([search {:keys [base-url]}]
   (->> search (build-url (or base-url rightmove-url)) fetch-page parse-listings)))
