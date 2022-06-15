(defproject rightmove-alerter "0.1.0-SNAPSHOT"
  :dependencies [[amazonica "0.3.161"]
                 [com.amazonaws/aws-lambda-java-runtime-interface-client "2.0.0"]
                 [com.taoensso/timbre "5.2.1"]
                 [enlive "1.1.6"]
                 [org.clojure/clojure "1.10.3"]]
  :repl-options {:init-ns rightmove.core}
  :profiles {:uberjar {:aot :all}})
