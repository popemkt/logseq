(ns frontend.worker.rtc.full-upload-download-graph
  "- upload local graph to remote
  - download remote graph"
  (:require-macros [frontend.worker.rtc.macro :refer [with-sub-data-from-ws get-req-id get-result-ch]])
  (:require [cljs-http.client :as http]
            [cljs.core.async :as async :refer [<! go]]
            [cljs.core.async.interop :refer [p->c]]
            [cognitect.transit :as transit]
            [datascript.core :as d]
            [frontend.worker.async-util :include-macros true :refer [<? go-try]]
            [frontend.worker.rtc.op-mem-layer :as op-mem-layer]
            [frontend.worker.rtc.ws :as ws :refer [<send!]]
            [frontend.worker.state :as worker-state]
            [frontend.worker.util :as worker-util]
            [logseq.db.frontend.schema :as db-schema]
            [logseq.outliner.core :as outliner-core]
            [logseq.db.frontend.content :as db-content]
            [promesa.core :as p]
            [clojure.string :as string]
            [logseq.common.util.page-ref :as page-ref]))

(def transit-r (transit/reader :json))

(defn- export-as-blocks
  [db]
  (let [datoms (d/datoms db :eavt)]
    (->> datoms
         (partition-by :e)
         (keep (fn [datoms]
                 (when (seq datoms)
                   (reduce
                    (fn [r datom]
                      (when (and (contains? #{:block/parent :block/left} (:a datom))
                                 (not (pos-int? (:v datom))))
                        (throw (ex-info "invalid block data" {:datom datom})))
                      (if (contains? db-schema/card-many-attributes (:a datom))
                        (update r (:a datom) conj (:v datom))
                        (assoc r (:a datom) (:v datom))))
                    {:db/id (:e (first datoms))}
                    datoms)))))))

(defn <upload-graph
  "Upload current repo to remote, return remote {:req-id xxx :graph-uuid <new-remote-graph-uuid>}"
  [state repo conn remote-graph-name]
  (go
    (let [{:keys [url key all-blocks-str]}
          (with-sub-data-from-ws state
            (<? (<send! state {:req-id (get-req-id) :action "presign-put-temp-s3-obj"}))
            (let [all-blocks (export-as-blocks @conn)
                  all-blocks-str (transit/write (transit/writer :json) all-blocks)]
              (merge (<! (get-result-ch)) {:all-blocks-str all-blocks-str})))]
      (<! (http/put url {:body all-blocks-str}))
      (let [r (<? (ws/<send&receive state {:action "full-upload-graph"
                                           :s3-key key
                                           :graph-name remote-graph-name}))]
        (if-not (:graph-uuid r)
          (ex-info "upload graph failed" r)
          (let [^js worker-obj (:worker/object @worker-state/*state)]
            (d/transact! conn
                         [{:db/ident :graph/uuid :graph/uuid (:graph-uuid r)}
                          {:db/ident :graph/local-tx :graph/local-tx (:graph-uuid r)}])
            (<! (p->c
                 (p/do!
                  (.storeMetadata worker-obj repo (pr-str {:graph/uuid (:graph-uuid r)})))))
            (op-mem-layer/init-empty-ops-store! repo)
            (op-mem-layer/update-graph-uuid! repo (:graph-uuid r))
            (op-mem-layer/update-local-tx! repo (:t r))
            (<! (op-mem-layer/<sync-to-idb-layer! repo))
            r))))))

(def block-type-kw->str
  {:block-type/property     "property"
   :block-type/class        "class"
   :block-type/whiteboard   "whiteboard"
   :block-type/macro        "macro"
   :block-type/hidden       "hidden"
   :block-type/closed-value "closed value"})

(defn- replace-db-id-with-temp-id
  [blocks]
  (mapv
   (fn [block]
     (let [db-id            (:db/id block)
           block-parent     (:db/id (:block/parent block))
           block-left       (:db/id (:block/left block))
           block-alias      (map :db/id (:block/alias block))
           block-tags       (map :db/id (:block/tags block))
           block-type       (keep block-type-kw->str (:block/type block))
           block-schema     (some->> (:block/schema block)
                                     (transit/read transit-r))
           block-properties (some->> (:block/properties block)
                                     (transit/read transit-r))
           block-link       (:db/id (:block/link block))]
       (cond-> (assoc block :db/id (str db-id))
         block-parent      (assoc :block/parent (str block-parent))
         block-left        (assoc :block/left (str block-left))
         (seq block-alias) (assoc :block/alias (map str block-alias))
         (seq block-tags)  (assoc :block/tags (map str block-tags))
         (seq block-type)  (assoc :block/type block-type)
         block-schema      (assoc :block/schema block-schema)
         block-properties  (assoc :block/properties block-properties)
         block-link        (assoc :block/link (str block-link)))))
   blocks))

(def page-of-block
  (memoize
   (fn [id->block-map block]
     (when-let [parent-id (:block/parent block)]
       (when-let [parent (id->block-map parent-id)]
         (if (:block/name parent)
           parent
           (page-of-block id->block-map parent)))))))

(defn- convert-block-fields
  [block]
  (cond-> block
    (:block/journal-day block) (assoc :block/journal? true)
    true                       (assoc :block/format :markdown)))

(defn- fill-block-fields
  [blocks]
  (let [groups (group-by #(boolean (:block/name %)) blocks)
        other-blocks (set (get groups false))
        id->block (into {} (map (juxt :db/id identity) blocks))
        block-id->page-id (into {} (map (fn [b] [(:db/id b) (:db/id (page-of-block id->block b))]) other-blocks))]
    (mapv (fn [b]
            (let [b (convert-block-fields b)]
              (if-let [page-id (block-id->page-id (:db/id b))]
                (assoc b :block/page page-id)
                b)))
          blocks)))

(defn- transact-block-refs!
  [repo]
  (when-let [conn (worker-state/get-datascript-conn repo)]
    (let [date-formatter (worker-state/get-date-formatter repo)
          db @conn
          ;; get all the block datoms
          datoms (d/datoms db :avet :block/uuid)
          refs-tx (keep
                   (fn [d]
                     (let [block (d/entity @conn (:e d))
                           block' (let [content (:block/content block)]
                                    (if (and content (string/includes? content (str page-ref/left-brackets db-content/page-ref-special-chars)))
                                      (assoc block :block/content (db-content/db-special-id-ref->page db content))
                                      block))
                           refs (outliner-core/rebuild-block-refs repo conn date-formatter block' {})]
                       (when (seq refs)
                         {:db/id (:db/id block)
                          :block/refs refs})))
                   datoms)]
      (d/transact! conn refs-tx {:outliner-op :rtc-download-rebuild-block-refs}))))

(defn- <transact-remote-all-blocks-to-sqlite
  [all-blocks repo graph-uuid]
  (go-try
   (let [{:keys [t blocks]} all-blocks
         blocks* (replace-db-id-with-temp-id blocks)
         blocks-with-page-id (fill-block-fields blocks*)
         tx-data (concat blocks-with-page-id
                       [{:db/ident :graph/uuid :graph/uuid graph-uuid}])
         ^js worker-obj (:worker/object @worker-state/*state)
         work (p/do!
               (.createOrOpenDB worker-obj repo {:close-other-db? false})
               (.exportDB worker-obj repo)
               (.transact worker-obj repo tx-data {:rtc-download-graph? true} (worker-state/get-context))
               (transact-block-refs! repo))]
     (<? (p->c work))

     (worker-util/post-message :add-repo {:repo repo})
     (op-mem-layer/update-local-tx! repo t))))

(defn <download-graph
  [state repo graph-uuid]
  (let [^js worker-obj (:worker/object @worker-state/*state)]
    (go-try
     (let [{:keys [url]}
           (<? (ws/<send&receive state {:action "full-download-graph"
                                        :graph-uuid graph-uuid}))
           {:keys [status body] :as r} (<! (http/get url))
           repo (str "logseq_db_" repo)]
       (if (not= 200 status)
         (ex-info "<download-graph failed" r)
         (let [all-blocks (transit/read transit-r body)]
           (worker-state/set-rtc-downloading-graph! true)
           (op-mem-layer/init-empty-ops-store! repo)
           (<? (<transact-remote-all-blocks-to-sqlite all-blocks repo graph-uuid))
           (op-mem-layer/update-graph-uuid! repo graph-uuid)
           ;; (prn ::download-graph repo (@@#'op-mem-layer/*ops-store repo))
           (<! (op-mem-layer/<sync-to-idb-layer! repo))
           (<! (p->c (.storeMetadata worker-obj repo (pr-str {:graph/uuid graph-uuid}))))
           (worker-state/set-rtc-downloading-graph! false)))))))
