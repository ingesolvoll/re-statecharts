(ns build
  (:require
   [clojure.string :as str]
   [org.corfield.build :as bb]))

(def lib 'com.github.ingesolvoll/re-statecharts)
(def version "0.0.1")

(defn install [opts]
  (-> opts
      (assoc :lib lib :version version)
      (bb/jar)
      (bb/install)))

(def release-marker "Release-")

(defn extract-version [tag]
  (str/replace-first tag release-marker ""))

(defn maybe-deploy [opts]
  (if-let [tag (System/getenv "CIRCLE_TAG")]
    (do
      (println "Found tag " tag)
      (if (re-find (re-pattern release-marker) tag)
        (do
          (println "Releasing to clojars...")
          (-> opts
              (assoc :lib lib :version (extract-version tag))
              (bb/jar)
              (bb/deploy)))
        (do
          (println "Tag is not a release tag, skipping deploy")
          opts)))
    (do
      (println "No tag found, skipping deploy")
      opts)))
