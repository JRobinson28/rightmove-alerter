(ns build
  (:require [clojure.tools.build.api :as b]))

(def target-dir "target")
(def class-dir (str target-dir "/classes"))
(def jar-file (str target-dir "/rightmove-alerter.jar"))

(defn clean
  [_]
  (b/delete {:path target-dir}))

(defn uber
  "Build the Lambda deployment jar. Only the handler namespace is AOT compiled
  (it and its transitive deps), which is what the Lambda runtime needs."
  [_]
  (let [basis (b/create-basis {:project "deps.edn"})]
    (clean nil)
    (b/copy-dir {:src-dirs ["src"] :target-dir class-dir})
    (b/compile-clj {:basis basis
                    :ns-compile ['rightmove-alerter.handler]
                    :class-dir class-dir})
    (b/uber {:class-dir class-dir
             :uber-file jar-file
             :basis basis})
    jar-file))
