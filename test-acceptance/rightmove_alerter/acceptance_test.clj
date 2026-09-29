(ns rightmove-alerter.acceptance-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [rightmove-alerter.acceptance.setup :as setup]))

(use-fixtures :each (fn [test] (setup/reset-fakes!) (test)))

(defn- entry
  "How a listing made by `setup/listing` with the defaults appears in an email."
  [id]
  (str "£1,000 pcm · 1 bed · " id " Test Street, Bristol\n"
       "https://www.rightmove.co.uk/properties/" id))

(deftest new-listing-is-emailed
  (setup/stub-search! "REGION^1498" (slurp (io/resource "search-page.html")))
  (let [before (quot (System/currentTimeMillis) 1000)
        response (setup/invoke [{:name "Home" :location "REGION^1498" :max-price 1200}])]
    (is (= {:result {:searches 1 :new-listings 2}} response))

    (testing "one email lists the new listings, leaving out featured ones"
      (is (= [{:subject "2 new Rightmove listings"
               :message (str "Home (2 new)\n\n"
                             "£1,200 pcm · 1 bed · Redland Road, Redland, Bristol, BS6\n"
                             "https://www.rightmove.co.uk/properties/93665742\n\n"
                             "£1,200 pcm · 1 bed · Crown & Anchor House, Sweetman Place, BS2\n"
                             "https://www.rightmove.co.uk/properties/91679058")}]
             (setup/emails))))

    (testing "the listings are saved with an expiry about 60 days away"
      (let [items (setup/seen-items)
            sixty-days (* 60 24 60 60)]
        (is (= #{"93665742" "91679058"} (set (keys items))))
        (is (= "https://www.rightmove.co.uk/properties/93665742" (get-in items ["93665742" :url])))
        (doseq [{:keys [expires-at]} (vals items)]
          (is (<= (+ before sixty-days) expires-at (+ before sixty-days 120))))))

    (testing "Rightmove was asked for the newest listings matching the search"
      (is (= [{"locationIdentifier" "REGION^1498"
               "maxPrice" "1200"
               "includeLetAgreed" "false"
               "sortType" "6"}]
             (setup/rightmove-requests))))))

(deftest seen-listings-are-not-re-emailed
  (setup/stub-search! "REGION^1" (setup/page [(setup/listing "101")]))
  (let [event [{:name "Home" :location "REGION^1"}]]
    (setup/invoke event)
    (is (= 1 (count (setup/emails))))

    (is (= {:result {:searches 1 :new-listings 0}} (setup/invoke event)))
    (is (= [] (setup/emails)))))

(deftest overlapping-searches-send-one-email
  (setup/stub-search! "REGION^1" (setup/page [(setup/listing "101") (setup/listing "102")]))
  (setup/stub-search! "REGION^2" (setup/page [(setup/listing "102") (setup/listing "103")]))
  (is (= {:result {:searches 2 :new-listings 3}}
         (setup/invoke [{:name "Clifton" :location "REGION^1"}
                          {:name "Redland" :location "REGION^2"}])))
  (is (= [{:subject "3 new Rightmove listings"
           :message (str "Clifton (2 new)\n\n" (entry "101") "\n\n" (entry "102") "\n\n"
                         "Redland (1 new)\n\n" (entry "103"))}]
         (setup/emails)))
  (is (= #{"101" "102" "103"} (set (keys (setup/seen-items))))))

(deftest failed-search-does-not-block-others
  (setup/stub-search! "REGION^1" "Service Unavailable" :status 503)
  (setup/stub-search! "REGION^2" (setup/page [(setup/listing "201")]))
  (is (= {:error "1 of 2 searches failed"}
         (setup/invoke [{:name "Clifton" :location "REGION^1"}
                          {:name "Redland" :location "REGION^2"}])))

  (testing "the search that worked is still emailed and saved"
    (is (= [{:subject "1 new Rightmove listing"
             :message (str "Redland (1 new)\n\n" (entry "201"))}]
           (setup/emails)))
    (is (= #{"201"} (set (keys (setup/seen-items)))))))

(deftest markup-change-is-an-error
  (setup/stub-search! "REGION^1" "<html><body><div class=\"propertyCard-details\"></div></body></html>")
  (is (= {:error "1 of 1 searches failed"}
         (setup/invoke [{:name "Home" :location "REGION^1"}])))
  (is (= [] (setup/emails)))
  (is (= {} (setup/seen-items))))
