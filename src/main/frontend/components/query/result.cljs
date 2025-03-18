(ns frontend.components.query.result
  "Query result related functionality for query components"
  (:require [clojure.string :as string]
            [frontend.db :as db]
            [frontend.db.query-custom :as query-custom]
            [frontend.db.query-dsl :as query-dsl]
            [frontend.db.query-react :as query-react]
            [frontend.db.utils :as db-utils]
            [frontend.modules.outliner.tree :as tree]
            [frontend.search :as search]
            [frontend.state :as state]
            [frontend.template :as template]
            [logseq.common.util :as common-util]
            [promesa.core :as p]
            [logseq.db :as ldb]))

(defn trigger-custom-query!
  [config query *query-error set-result!]
  (let [repo (state/get-current-repo)
        current-block-uuid (or (:block/uuid (:block config))
                               (:block/uuid config))
        _ (reset! *query-error nil)]
    (try
      (cond
        (:dsl-query? config)
        (let [q (:query query)
              form (common-util/safe-read-string {:log-error? false} q)]
          (cond
            (and (symbol? form)
                                ;; Queries only containing template should trigger a query
                 (not (re-matches template/template-re (string/trim q))))
            nil

            (re-matches #"^\".*\"$" q) ; full-text search
            (p/let [blocks (search/block-search repo (string/trim form) {:limit 30})]
              (when (seq blocks)
                (let [result (->> blocks
                                  (keep (fn [b]
                                          (when-not (= (:block/uuid b) current-block-uuid)
                                            (let [entity (db/entity [:block/uuid (:block/uuid b)])]
                                              (when-not (ldb/hidden? entity)
                                                entity))))))]
                  (set-result! (atom result)))))

            :else
            (let [result (query-dsl/query (state/get-current-repo) q {:cards? (:cards? config)})]
              (set-result! (or result (atom []))))))

        :else
        (set-result! (query-custom/custom-query query {:current-block-uuid current-block-uuid
                                                       ;; FIXME: Remove this temporary workaround for reactivity not working
                                                       :use-cache? false})))
      (catch :default e
        (reset! *query-error e)))))

(defn get-group-by-page [{:keys [result-transform query] :as query-m}
                         {:keys [table? db-graph?]}]
  (if (or table? db-graph?)
    false ;; Immediately return false as table view can't handle grouping
    (get query-m :group-by-page?
         (and (not result-transform)
              (not (and (string? query) (string/includes? query "(by-page false)")))))))

(defn transform-query-result
  "Transforms a query result if query conditions and config indicate a transformation"
  [{:keys [current-block-uuid] :as config} query-m query-result]
  (let [;; exclude the current one, otherwise it'll loop forever
        remove-blocks (if current-block-uuid [current-block-uuid] nil)
        transformed-query-result (when query-result
                                   (let [result (query-react/custom-query-result-transform query-result remove-blocks query-m)]
                                     (if (and query-result (coll? result) (:block/uuid (first result)))
                                       (cond-> result
                                         (and (not (:db-graph? config)) (get query-m :remove-block-children? true))
                                         tree/filter-top-level-blocks)
                                       result)))
        group-by-page? (get-group-by-page query-m config)
        result (if (and group-by-page? (:block/uuid (first transformed-query-result)))
                 (let [result (db-utils/group-by-page transformed-query-result)]
                   (if (map? result)
                     (dissoc result nil)
                     result))
                 transformed-query-result)]
    (when-let [query-result (:query-result config)]
      (reset! query-result result))
    result))
