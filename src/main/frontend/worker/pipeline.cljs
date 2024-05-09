(ns frontend.worker.pipeline
  "Pipeline work after transaction"
  (:require [datascript.core :as d]
            [frontend.worker.file :as file]
            [frontend.worker.react :as worker-react]
            [frontend.worker.util :as worker-util]
            [logseq.db :as ldb]
            [logseq.db.frontend.validate :as db-validate]
            [logseq.db.sqlite.util :as sqlite-util]
            [logseq.outliner.datascript-report :as ds-report]
            [logseq.outliner.pipeline :as outliner-pipeline]))

(defn- path-refs-need-recalculated?
  [tx-meta]
  (let [outliner-op (:outliner-op tx-meta)]
    (not (or
          (contains? #{:collapse-expand-blocks :delete-blocks} outliner-op)
          (:undo? tx-meta) (:redo? tx-meta)))))

(defn compute-block-path-refs-tx
  [{:keys [tx-meta] :as tx-report} blocks]
  (when (or (and (:outliner-op tx-meta) (path-refs-need-recalculated? tx-meta))
            (:from-disk? tx-meta)
            (:new-graph? tx-meta))
    (outliner-pipeline/compute-block-path-refs-tx tx-report blocks)))

(defn validate-db!
  [repo conn tx-report context]
  (when (and (:dev? context) (not (:importing? context)) (sqlite-util/db-based-graph? repo))
    (let [valid? (db-validate/validate-tx-report! tx-report (:validate-db-options context))]
      (when (and (get-in context [:validate-db-options :fail-invalid?]) (not valid?))
        (worker-util/post-message :notification
                                  [["Invalid DB!"] :error]))))

  ;; Ensure :block/order is unique for any block that has :block/parent
  (when (or (:dev? context) (exists? js/process))
    (let [order-datoms (filter (fn [d] (= :block/order (:a d))) (:tx-data tx-report))]
      (doseq [datom order-datoms]
        (let [entity (d/entity @conn (:db/id datom))
              parent (:block/parent entity)]
          (when parent
            (let [children (:block/_parent parent)]
              (assert (= (count (distinct (map :block/order children))) (count children))
                      (str ":block/order is not unique for children blocks, parent id: " (:db/id parent))))))))))

(defn invoke-hooks
  [repo conn {:keys [tx-meta] :as tx-report} context]
  (when-not (:pipeline-replace? tx-meta)
    (let [{:keys [from-disk? new-graph?]} tx-meta]
      (cond
        (or from-disk? new-graph?)
        (let [{:keys [blocks]} (ds-report/get-blocks-and-pages tx-report)
              path-refs (distinct (compute-block-path-refs-tx tx-report blocks))
              tx-report' (or
                          (when (seq path-refs)
                            (ldb/transact! conn path-refs {:pipeline-replace? true}))
                          (do
                            (when-not (exists? js/process) (d/store @conn))
                            tx-report))
              full-tx-data (concat (:tx-data tx-report) (:tx-data tx-report'))
              final-tx-report (assoc tx-report'
                                     :tx-meta (:tx-meta tx-report)
                                     :tx-data full-tx-data
                                     :db-before (:db-before tx-report))]
          {:tx-report final-tx-report})

        :else
        (let [{:keys [pages blocks]} (ds-report/get-blocks-and-pages tx-report)
              _ (when (sqlite-util/local-file-based-graph? repo)
                  (let [page-ids (distinct (map :db/id pages))]
                    (doseq [page-id page-ids]
                      (when (d/entity @conn page-id)
                        (file/sync-to-file repo page-id tx-meta)))))
              deleted-block-uuids (set (outliner-pipeline/filter-deleted-blocks (:tx-data tx-report)))
              replace-tx (concat
                        ;; block path refs
                          (set (compute-block-path-refs-tx tx-report blocks))

                          ;; update block/tx-id
                          (let [updated-blocks (remove (fn [b] (contains? (set deleted-block-uuids) (:block/uuid b)))
                                                       (concat pages blocks))
                                tx-id (get-in tx-report [:tempids :db/current-tx])]
                            (keep (fn [b]
                                    (when-let [db-id (:db/id b)]
                                      {:db/id db-id
                                       :block/tx-id tx-id})) updated-blocks)))
              tx-report' (or
                          (when (seq replace-tx)
                          ;; TODO: remove this since transact! is really slow
                            (ldb/transact! conn replace-tx {:pipeline-replace? true}))
                          (do
                            (when-not (exists? js/process) (d/store @conn))
                            tx-report))
              fix-tx-data (validate-db! repo conn tx-report context)
              full-tx-data (concat (:tx-data tx-report)
                                   fix-tx-data
                                   (:tx-data tx-report'))
              final-tx-report (assoc tx-report' :tx-data full-tx-data)
              affected-query-keys (when-not (:importing? context)
                                    (worker-react/get-affected-queries-keys final-tx-report))]
          {:tx-report final-tx-report
           :affected-keys affected-query-keys
           :deleted-block-uuids deleted-block-uuids
           :pages pages
           :blocks blocks})))))
