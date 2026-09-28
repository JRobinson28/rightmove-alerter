(ns rightmove-alerter.scraper-test
  (:require
   [clj-http.fake :refer [with-fake-routes-in-isolation]]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [clojure.test.check.clojure-test :refer [defspec]]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]
   [rightmove-alerter.scraper :as scraper]))

(def ^:private search-page (slurp (io/resource "search-page.html")))

(def ^:private search-url #"https://www\.rightmove\.co\.uk/property-to-rent/find\.html.*")

(defn- query-params
  [query-string]
  (->> (str/split query-string #"&")
       (map #(str/split % #"=" 2))
       (into {})))

(defn- scrape-with
  "Scrapes `search` against a fake Rightmove that returns `response`. Returns the
  listings along with the query params of the request that was made."
  [response search]
  (let [request (atom nil)]
    (with-fake-routes-in-isolation
      {search-url (fn [req] (reset! request req) response)}
      {:listings (scraper/scrape search)
       :query-params (query-params (:query-string @request))})))

(defn- request-params
  [search]
  (:query-params (scrape-with {:status 200 :body search-page} search)))

(deftest search-request-test
  (testing "minimal search"
    (is (= {"locationIdentifier" "REGION%5E1498"
            "includeLetAgreed" "false"
            "sortType" "6"}
           (request-params {:location "REGION^1498"}))))

  (testing "full search"
    (is (= {"locationIdentifier" "REGION%5E1498"
            "minPrice" "800"
            "maxPrice" "1200"
            "minBedrooms" "1"
            "maxBedrooms" "2"
            "radius" "1.0"
            "furnishTypes" "furnished%2CpartFurnished"
            "includeLetAgreed" "false"
            "sortType" "6"}
           (request-params {:location "REGION^1498"
                            :min-price 800
                            :max-price 1200
                            :min-bedrooms 1
                            :max-bedrooms 2
                            :radius 1.0
                            :furnish-types "furnished,partFurnished"}))))

  (testing "nil values are left out rather than sent empty"
    (is (not (contains? (request-params {:location "REGION^1" :max-price nil})
                        "maxPrice"))))

  (testing "an already URL-encoded location isn't double encoded"
    (is (= "REGION%5E1498"
           (get (request-params {:location "REGION%5E1498"}) "locationIdentifier"))))

  (testing "unknown keys are ignored"
    (is (= #{"locationIdentifier" "includeLetAgreed" "sortType"}
           (set (keys (request-params {:location "REGION^1" :name "Home"})))))))

(defspec search-request-always-has-location-and-newest-first-sort 100
  (prop/for-all [location gen/string-alphanumeric
                 max-price (gen/one-of [(gen/return nil) gen/nat])]
    (let [params (request-params {:location location :max-price max-price})]
      (and (= location (get params "locationIdentifier"))
           (= "6" (get params "sortType"))))))

(deftest listings-test
  (let [{:keys [listings]} (scrape-with {:status 200 :body search-page}
                                        {:location "REGION^1498"})]
    (testing "featured listings are excluded"
      (is (= ["93665742" "91679058"] (map :id listings))))

    (testing "fields are mapped and the URL fragment is dropped"
      (is (= {:id "93665742"
              :url "https://www.rightmove.co.uk/properties/93665742"
              :price "£1,200 pcm"
              :address "Redland Road, Redland, Bristol, BS6"
              :bedrooms 1
              :first-visible "2026-09-28T16:22:18Z"}
             (first listings))))))

(deftest no-results-test
  (is (= [] (:listings (scrape-with {:status 200
                                     :body "<script id=\"__NEXT_DATA__\">{\"props\":{\"pageProps\":{\"searchResults\":{\"properties\":[]}}}}</script>"}
                                    {:location "REGION^1"})))))

(deftest unexpected-page-test
  (testing "a page without the embedded data throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Could not find search results"
                          (scrape-with {:status 200
                                        :body "<html><body><div class=\"propertyCard-details\"></div></body></html>"}
                                       {:location "REGION^1"}))))

  (testing "embedded data with a different shape throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Could not find search results"
                          (scrape-with {:status 200
                                        :body "<script id=\"__NEXT_DATA__\">{\"props\":{}}</script>"}
                                       {:location "REGION^1"})))))

(deftest error-response-test
  (is (= 503 (try (scrape-with {:status 503 :body "Service Unavailable"} {:location "REGION^1"})
                  nil
                  (catch clojure.lang.ExceptionInfo e
                    (:status (ex-data e)))))))
