(ns logseq.db.sqlite.util
  "Utils fns for backend sqlite db"
  (:require [clojure.string :as string]
            [logseq.db.frontend.schema :as db-schema]
            [logseq.common.util :as common-util]
            [cognitect.transit :as transit]
            [datascript.transit :as dt]
            [datascript.impl.entity :as de]
            [datascript.core :as d]
            [cljs-bean.transit]))

(defonce db-version-prefix "logseq_db_")
(defonce file-version-prefix "logseq_local_")

(def transit-w (transit/writer :json))
(def transit-r (transit/reader :json))
(defn transit-write
  [data]
  (transit/write transit-w data))

(defn transit-read
  [str]
  (transit/read transit-r str))

(def write-transit-str
  (let [write-handlers (->> (assoc dt/write-handlers
                                   de/Entity (transit/write-handler (constantly "datascript/Entity")
                                                                    (fn [^de/entity entity]
                                                                      (assert (some? (:db/id entity)))
                                                                      (assoc (.-kv entity)
                                                                             :db/id (:db/id entity)))))
                            (merge (cljs-bean.transit/writer-handlers)))]
    (fn write-transit-str* [o]
      (transit/write (transit/writer :json {:handlers write-handlers}) o))))

(def read-transit-str
  (let [read-handlers (assoc dt/read-handlers
                             "datascript/Entity" identity)]
    (fn read-transit-str* [s] (transit/read (transit/reader :json {:handlers read-handlers}) s))))

(defn db-based-graph?
  [graph-name]
  (string/starts-with? graph-name db-version-prefix))

(defn local-file-based-graph?
  [s]
  (and (string? s)
       (string/starts-with? s file-version-prefix)))

(defn get-schema
  "Returns schema for given repo"
  [repo]
  (if (db-based-graph? repo)
    db-schema/schema-for-db-based-graph
    db-schema/schema))

(defn block-with-timestamps
  "Adds updated-at timestamp and created-at if it doesn't exist"
  [block]
  (let [updated-at (common-util/time-ms)
        block (cond->
               (assoc block :block/updated-at updated-at)
                (nil? (:block/created-at block))
                (assoc :block/created-at updated-at))]
    block))

(defn build-new-property
  "Build a standard new property so that it is is consistent across contexts"
  [prop-name prop-schema prop-uuid & {:keys [db-ident]}]
  (block-with-timestamps
   (cond->
    {:block/type "property"
     :block/journal? false
     :block/format :markdown
     :block/uuid prop-uuid
     :block/schema (merge {:type :default} prop-schema)
     :block/original-name (name prop-name)
     :block/name (common-util/page-name-sanity-lc (name prop-name))}
     (and db-ident (keyword? db-ident))
     (assoc :db/ident db-ident))))


(defn build-new-class
  "Build a standard new class so that it is is consistent across contexts"
  [block]
  (block-with-timestamps
   (merge {:block/type "class"
           :block/journal? false
           :block/format :markdown}
          block)))

(defn build-new-page
  "Builds a basic page to be transacted. A minimal version of gp-block/page-name->map"
  [page-name]
  (block-with-timestamps
   {:block/name (common-util/page-name-sanity-lc page-name)
    :block/original-name page-name
    :block/journal? false
    :block/uuid (d/squuid)}))