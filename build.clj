(ns build
  (:require [clojure.tools.build.api :as b]))

(def target-dir "target")
(def class-dir (str target-dir "/classes"))
(def deps-file "deps.edn")

(defn clean
  [_]
  (b/delete {:path target-dir}))

(defn uber
  [_]
  (let [basis (b/create-basis {:project deps-file})
        file-name (str target-dir "/rightmove-alerter.jar")]
    (clean nil)
    (b/copy-dir {:src-dirs ["src" "resources"]
                 :target-dir class-dir})
    (b/compile-clj {:basis basis
                    :src-dirs ["src"]
                    :class-dir class-dir})
    (b/uber {:class-dir class-dir
             :uber-file file-name
             :basis basis})
    file-name))
