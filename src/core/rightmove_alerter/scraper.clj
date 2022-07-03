(ns core.rightmove-alerter.scraper
  (:require [clojure.java.io :as io]
            [net.cgrand.enlive-html :as html]))

(defn- build-url
  [{:keys [location min-bedrooms max-bedrooms max-price radius] :as input-map}]
  (str "https://www.rightmove.co.uk/property-to-rent/find.html?locationIdentifier=" location
       "&maxBedrooms=" max-bedrooms
       "&minBedrooms=" min-bedrooms
       "&maxPrice=" max-price
       "&radius=" (or radius 0.0)
       "&propertyTypes=&includeLetAgreed=false&mustHave=&dontShow=&furnishTypes=&keywords="))

(defn- extract-link
  [details]
  (str "https://www.rightmove.co.uk" (-> details
                                         :content
                                         second
                                         (get-in [:attrs :href]))))

(defn scrape-page
  [input-map]
  (let [url (build-url input-map)
        page-content (-> input-map build-url io/as-url html/html-resource)]
    (->> (html/select page-content [:div.propertyCard-details])
         (mapv extract-link)
         rest)))
