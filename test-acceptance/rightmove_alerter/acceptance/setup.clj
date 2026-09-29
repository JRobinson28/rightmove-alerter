(ns rightmove-alerter.acceptance.setup
  (:require
   [cheshire.core :as json]
   [clj-http.client :as http]
   [clj-http.util :as http-util]
   [clojure.string :as str]
   [cognitect.aws.client.api :as aws]
   [cognitect.aws.credentials :as credentials]))

(def ^:private moto-url "http://localhost:5000")
(def ^:private wiremock-url "http://localhost:8080")
(def ^:private invoke-url "http://localhost:9000/2015-03-31/functions/function/invocations")

;; Must match the lambda service's environment in docker-compose.yml.
(def ^:private table-name "rightmove-alerter-seen")
(def ^:private topic-arn "arn:aws:sns:eu-west-2:123456789012:rightmove-alerter-alerts")
(def ^:private queue-name "rightmove-alerter-emails")

(def ^:private queue-url (atom nil))

(def ^:private clients
  (delay
    (into {}
          (for [api [:dynamodb :sns :sqs]]
            [api (aws/client {:api api
                              :region "eu-west-2"
                              :credentials-provider (credentials/basic-credentials-provider
                                                     {:access-key-id "test" :secret-access-key "test"})
                              :endpoint-override {:protocol :http :hostname "localhost" :port 5000}})]))))

(defn- invoke!
  [api op request]
  (let [response (aws/invoke (get @clients api) {:op op :request request})]
    (when (:cognitect.anomalies/category response)
      (throw (ex-info (str (name api) " " (name op) " failed") response)))
    response))

(defn reset-fakes!
  "Clears every fake and recreates the table and topic the function expects,
  with an SQS queue subscribed to the topic to capture the emails."
  []
  (http/post (str moto-url "/moto-api/reset"))
  (http/post (str wiremock-url "/__admin/reset"))
  ;; DynamoDB
  (invoke! :dynamodb :CreateTable {:TableName table-name
                                   :BillingMode "PAY_PER_REQUEST"
                                   :AttributeDefinitions [{:AttributeName "id" :AttributeType "S"}]
                                   :KeySchema [{:AttributeName "id" :KeyType "HASH"}]})
  (invoke! :dynamodb :UpdateTimeToLive {:TableName table-name
                                        :TimeToLiveSpecification {:AttributeName "expires_at" :Enabled true}})
  ;; SNS
  (let [arn (:TopicArn (invoke! :sns :CreateTopic {:Name (last (str/split topic-arn #":"))}))]
    (when (not= topic-arn arn)
      (throw (ex-info "Topic ARN doesn't match the lambda's TOPIC_ARN" {:expected topic-arn :actual arn}))))
  ;; SQS
  (let [url (:QueueUrl (invoke! :sqs :CreateQueue {:QueueName queue-name}))
        queue-arn (get-in (invoke! :sqs :GetQueueAttributes {:QueueUrl url :AttributeNames ["QueueArn"]})
                          [:Attributes :QueueArn])]
    (invoke! :sns :Subscribe {:TopicArn topic-arn :Protocol "sqs" :Endpoint queue-arn})
    (reset! queue-url url)))

(defn listing
  "A search result as it appears in Rightmove's page data."
  [id & {:keys [price address bedrooms featured?]
         :or {price "£1,000 pcm" address (str id " Test Street, Bristol") bedrooms 1}}]
  {:id (parse-long id)
   :propertyUrl (str "/properties/" id "#/?channel=RES_LET")
   :price {:displayPrices [{:displayPrice price}]}
   :displayAddress address
   :bedrooms bedrooms
   :featuredProperty (boolean featured?)
   :firstVisibleDate "2026-09-28T12:00:00Z"})

(defn page
  "A search results page containing `listings`."
  [listings]
  (str "<html><body><script id=\"__NEXT_DATA__\" type=\"application/json\">"
       (json/generate-string {:props {:pageProps {:searchResults {:properties listings}}}})
       "</script></body></html>"))

(defn stub-search!
  "Makes the fake Rightmove answer searches for `location` with `body`."
  [location body & {:keys [status] :or {status 200}}]
  (http/post (str wiremock-url "/__admin/mappings")
             {:content-type :json
              :body (json/generate-string
                     {:request {:method "GET"
                                :urlPath "/property-to-rent/find.html"
                                :queryParameters {:locationIdentifier {:equalTo location}}}
                      :response {:status status
                                 :headers {"Content-Type" "text/html; charset=utf-8"}
                                 :body body}})}))

(defn rightmove-requests
  "The query params of each request the fake Rightmove received."
  []
  (for [{:keys [request]} (:requests (json/parse-string
                                      (:body (http/get (str wiremock-url "/__admin/requests")))
                                      true))
        :let [query (second (str/split (:url request) #"\?" 2))]]
    (into {}
          (for [param (str/split (or query "") #"&")
                :let [[k v] (str/split param #"=" 2)]]
            [k (http-util/url-decode (or v ""))]))))

(defn invoke
  "Invokes the function with `event` as its payload. Returns `{:error message}`
  if it threw, or `{:result summary}`."
  [event]
  (let [body (json/parse-string
              (:body (http/post invoke-url {:body (json/generate-string event)
                                            :socket-timeout 90000
                                            :connection-timeout 5000}))
              true)]
    (if-let [message (:errorMessage body)]
      {:error message}
      {:result body})))

(defn emails
  "Takes every email published since the last call, as `{:subject :message}`.
  moto delivers SNS messages to SQS during Publish, so nothing is in flight."
  []
  (loop [emails []]
    (let [messages (:Messages (invoke! :sqs :ReceiveMessage {:QueueUrl @queue-url
                                                             :MaxNumberOfMessages 10
                                                             :WaitTimeSeconds 1}))]
      (if (empty? messages)
        emails
        (do (invoke! :sqs :DeleteMessageBatch
                     {:QueueUrl @queue-url
                      :Entries (map-indexed (fn [i m] {:Id (str i) :ReceiptHandle (:ReceiptHandle m)})
                                            messages)})
            (recur (into emails
                         (map (fn [m]
                                (let [{:keys [Subject Message]} (json/parse-string (:Body m) true)]
                                  {:subject Subject :message Message})))
                         messages)))))))

(defn seen-items
  "Every item in the seen-listings table, keyed by id."
  []
  (into {}
        (for [item (:Items (invoke! :dynamodb :Scan {:TableName table-name}))]
          [(get-in item [:id :S]) {:url (get-in item [:url :S])
                                   :expires-at (some-> (get-in item [:expires_at :N]) parse-long)}])))
