(ns frontend.db.utils
  "Some utils are required by other namespace in frontend.db package."
  (:require [datascript.core :as d]
            [frontend.state :as state]
            [frontend.db.conn :as conn]
            [frontend.config :as config]
            [logseq.db.frontend.content :as db-content]))

;; transit serialization

(defn seq-flatten [col]
  (flatten (seq col)))

(defn group-by-page
  [blocks]
  (if (:block/page (first blocks))
    (some->> blocks
             (group-by :block/page))
    blocks))

(defn entity
  "This function will return nil if passed `eid` is an integer and
  the entity doesn't exist in db.
  `repo-or-db`: a repo string or a db,
  `eid`: same as d/entity."
  ([eid]
   (entity (state/get-current-repo) eid))
  ([repo-or-db eid]
   (when eid
     (when-let [db (if (string? repo-or-db)
                   ;; repo
                     (let [repo (or repo-or-db (state/get-current-repo))]
                       (conn/get-db repo))
                   ;; db
                     repo-or-db)]
       (d/entity db eid)))))

(defn update-block-content
  "Replace `[[internal-id]]` with `[[page name]]`"
  [item eid]
  (let [repo (state/get-current-repo)
        db (conn/get-db repo)]
    (db-content/update-block-content repo db item eid)))

(defn pull
  ([eid]
   (pull (state/get-current-repo) '[*] eid))
  ([selector eid]
   (pull (state/get-current-repo) selector eid))
  ([repo selector eid]
   (when-let [db (conn/get-db repo)]
     (let [result (d/pull db selector eid)]
       (update-block-content result eid)))))

(defn pull-many
  ([eids]
   (pull-many '[*] eids))
  ([selector eids]
   (pull-many (state/get-current-repo) selector eids))
  ([repo selector eids]
   (when-let [db (conn/get-db repo)]
     (let [selector (if (some #{:db/id} selector) selector (conj selector :db/id))]
       (->> (d/pull-many db selector eids)
            (map #(update-block-content % (:db/id %))))))))

(if config/publishing?
  (defn- transact!*
    [repo-url tx-data tx-meta]
    ;; :save-block is for query-table actions like sorting and choosing columns
    (when (or (#{:collapse-expand-blocks :save-block} (:outliner-op tx-meta))
              (:init-db? tx-meta))
      (conn/transact! repo-url tx-data tx-meta)))
  (def transact!* conn/transact!))

(defn transact!
  ([tx-data]
   (transact! (state/get-current-repo) tx-data))
  ([repo-url tx-data]
   (transact! repo-url tx-data nil))
  ([repo-url tx-data tx-meta]
   (transact!* repo-url tx-data tx-meta)))

(defn get-key-value
  ([key]
   (get-key-value (state/get-current-repo) key))
  ([repo-url key]
   (when-let [db (conn/get-db repo-url)]
     (some-> (d/entity db key)
             key))))

(defn q
  [query & inputs]
  (when-let [repo (state/get-current-repo)]
    (apply d/q query (conn/get-db repo) inputs)))
