(ns frontend.search
  "Provides search functionality for a number of features including Cmd-K
  search. Most of these fns depend on the search protocol"
  (:require [clojure.string :as string]
            [datascript.core :as d]
            [frontend.common.search-fuzzy :as fuzzy]
            [frontend.config :as config]
            [frontend.db :as db]
            [frontend.db.async :as db-async]
            [frontend.db.model :as db-model]
            [frontend.db.utils :as db-utils]
            [frontend.search.agency :as search-agency]
            [frontend.search.protocol :as protocol]
            [frontend.state :as state]
            [frontend.util :as util]
            [logseq.common.config :as common-config]
            [logseq.db :as ldb]
            [promesa.core :as p]))

(def fuzzy-search fuzzy/fuzzy-search)

(defn get-engine
  [repo]
  (search-agency/->Agency repo))

(defn block-search
  [repo q option]
  (when-let [engine (get-engine repo)]
    (let [q (fuzzy/search-normalize q (state/enable-search-remove-accents?))]
      (when-not (string/blank? q)
        (protocol/query engine q option)))))

(defn file-search
  ([q]
   (file-search q 3))
  ([q limit]
   (when-let [repo (state/get-current-repo)]
     (let [q (fuzzy/clean-str q)]
       (when-not (string/blank? q)
         (p/let [mldoc-exts (set (map name common-config/mldoc-support-formats))
                 result (db-async/<get-files repo)
                 files (->> result
                            (map first)
                            (remove (fn [file]
                                      (mldoc-exts (util/get-file-ext file)))))]
           (when (seq files)
             (fuzzy/fuzzy-search files q :limit limit))))))))

(defn template-search
  ([q]
   (template-search q 100))
  ([q limit]
   (when-let [repo (state/get-current-repo)]
     (when q
       (let [db-based? (config/db-based-graph?)]
         (p/let [q (fuzzy/clean-str q)
                 templates (if db-based?
                             (db-async/<get-tag-objects repo (:db/id (db/entity :logseq.class/Template)))
                             (p/let [result (db-async/<get-all-templates repo)]
                               (vals result)))]
           (when (seq templates)
             (let [extract-fn (if db-based? :block/title :template)]
               (fuzzy/fuzzy-search templates q {:limit limit
                                                :extract-fn extract-fn})))))))))

(defn property-search
  ([q]
   (property-search q 100))
  ([q limit]
   (when q
     (p/let [q (fuzzy/clean-str q)
             properties* (db-async/<get-all-properties)
             properties (map :block/title properties*)]
       (when (seq properties)
         (if (string/blank? q)
           properties
           (let [result (fuzzy/fuzzy-search properties q :limit limit)]
             (vec result))))))))

;; file-based graph only
(defn property-value-search
  ([property q]
   (property-value-search property q 100))
  ([property q limit]
   (when-let [repo (state/get-current-repo)]
     (when q
       (p/let [q (fuzzy/clean-str q)
               result (db-async/<file-get-property-values repo (keyword property))]
         (when (seq result)
           (if (string/blank? q)
             result
             (let [result (fuzzy/fuzzy-search result q :limit limit)]
               (vec result)))))))))

(defn rebuild-indices!
  ([]
   (rebuild-indices! (state/get-current-repo)))
  ([repo]
   (when repo
     (when-let [engine (get-engine repo)]
       (p/do!
        (protocol/rebuild-pages-indice! engine)
        (protocol/rebuild-blocks-indice! engine))))))

(defn reset-indice!
  [repo]
  (when-let [engine (get-engine repo)]
    (protocol/truncate-blocks! engine)))

(defn remove-db!
  [repo]
  (when-let [engine (get-engine repo)]
    (protocol/remove-db! engine)))

(defn get-unlinked-refs
  "Get matched result from search first, and then filter by worker db"
  [eid]
  (when-let [repo (state/get-current-repo)]
    (p/let [entity (db/entity eid)
            alias-names (conj (set (map util/safe-page-name-sanity-lc
                                        (db/get-page-alias-names repo eid)))
                              (:block/title entity))
            q (string/join " " alias-names)
            result (block-search repo q {:limit 100})
            eids (->> result
                      (remove :page?)
                      (remove (fn [b] (= (:block/uuid b) (:block/uuid entity))))
                      (map (fn [b] [:block/uuid (:block/uuid b)])))
            result (when (seq eids)
                     (.get-page-unlinked-refs ^Object @state/*db-worker repo (:db/id entity) (ldb/write-transit-str eids)))
            result' (when result (ldb/read-transit-str result))]
      (when result' (d/transact! (db/get-db repo false) result'))
      (some->> result'
               db-model/sort-by-order-recursive
               db-utils/group-by-page))))
