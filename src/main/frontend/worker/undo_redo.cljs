(ns frontend.worker.undo-redo
  "undo/redo related fns and op-schema"
  (:require [clojure.set :as set]
            [datascript.core :as d]
            [frontend.schema-register :include-macros true :as sr]
            [frontend.worker.batch-tx :include-macros true :as batch-tx]
            [frontend.worker.db-listener :as db-listener]
            [frontend.worker.state :as worker-state]
            [logseq.common.config :as common-config]
            [logseq.common.util :as common-util]
            [logseq.outliner.core :as outliner-core]
            [logseq.outliner.transaction :as outliner-tx]
            [malli.core :as m]
            [malli.util :as mu]))

(sr/defkeyword :gen-undo-op?
  "tx-meta option, generate undo ops from tx-data when true (default true)")

(sr/defkeyword :gen-undo-boundary-op?
  "tx-meta option, generate `::boundary` undo-op when true (default true).
usually every transaction's tx-data will generate ops like: [<boundary> <op1> <op2> ...],
push to undo-stack, result in [...<boundary> <op0> <boundary> <op1> <op2> ...].

when this option is false, only generate [<op1> <op2> ...]. undo-stack: [...<boundary> <op0> <op1> <op2> ...]
so when undo, it will undo [<op0> <op1> <op2>] instead of [<op1> <op2>]")

(sr/defkeyword ::boundary
  "boundary of one or more undo-ops.
when one undo/redo will operate on all ops between two ::boundary")

(sr/defkeyword ::insert-blocks
  "when some blocks are inserted, generate a ::insert-blocks undo-op.
when undo this op, the related blocks will be removed.")

(sr/defkeyword ::move-block
  "when a block is moved, generate a ::move-block undo-op.")

(sr/defkeyword ::remove-block
  "when a block is removed, generate a ::remove-block undo-op.
when undo this op, this original entity-map will be transacted back into db")

(sr/defkeyword ::update-block
  "when a block is updated, generate a ::update-block undo-op.")

(sr/defkeyword ::empty-undo-stack
  "return by undo, when no more undo ops")

(sr/defkeyword ::empty-redo-stack
  "return by redo, when no more redo ops")

(def ^:private boundary [::boundary])

(def ^:private undo-op-schema
  (mu/closed-schema
   [:multi {:dispatch first}
    [::boundary
     [:cat :keyword]]
    [::insert-blocks
     [:cat :keyword
      [:map
       [:block-uuids [:sequential :uuid]]]]]
    [::move-block
     [:cat :keyword
      [:map
       [:block-uuid :uuid]
       [:block-origin-left :uuid]
       [:block-origin-parent :uuid]]]]
    [::remove-block
     [:cat :keyword
      [:map
       [:block-uuid :uuid]
       [:block-entity-map
        [:map
         [:block/uuid :uuid]
         [:block/left :uuid]
         [:block/parent :uuid]
         [:block/content :string]
         [:block/created-at {:optional true} :int]
         [:block/updated-at {:optional true} :int]
         [:block/format {:optional true} :any]
         [:block/tags {:optional true} [:sequential :uuid]]]]]]]
    [::update-block
     [:cat :keyword
      [:map
       [:block-uuid :uuid]
       [:block-origin-content {:optional true} :string]
       [:block-origin-tags {:optional true} [:sequential :uuid]]
       [:block-origin-collapsed {:optional true} :boolean]
       ;; TODO: add more attrs
       ]]]]))

(def ^:private undo-ops-validator (m/validator [:sequential undo-op-schema]))

(def ^:dynamic *undo-redo-info-for-test*
  "record undo-op info when running-test"
  nil)

(def ^:private entity-map-pull-pattern
  [:block/uuid
   {:block/left [:block/uuid]}
   {:block/parent [:block/uuid]}
   :block/content
   :block/created-at
   :block/updated-at
   :block/format
   {:block/tags [:block/uuid]}])

(defn- ->block-entity-map
  [db eid]
  (let [m (d/pull db entity-map-pull-pattern eid)]
    (cond-> m
      true                  (update :block/left :block/uuid)
      true                  (update :block/parent :block/uuid)
      (seq (:block/tags m)) (update :block/tags (partial mapv :block/uuid)))))

(defn- reverse-op
  "return ops"
  [db op]
  (let [block-uuid (:block-uuid (second op))]
    (case (first op)
      ::boundary [op]

      ::insert-blocks
      (mapv
       (fn [block-uuid]
         [::remove-block
          {:block-uuid       block-uuid
           :block-entity-map (->block-entity-map db [:block/uuid block-uuid])}])
       (:block-uuids (second op)))

      ::move-block
      (let [b (d/entity db [:block/uuid block-uuid])]
        [[::move-block
          {:block-uuid          block-uuid
           :block-origin-left   (:block/uuid (:block/left b))
           :block-origin-parent (:block/uuid (:block/parent b))}]])

      ::remove-block
      [[::insert-blocks {:block-uuids [block-uuid]}]]

      ::update-block
      (let [value-keys             (set (keys (second op)))
            block-entity           (d/entity db [:block/uuid block-uuid])
            block-origin-content   (when (contains? value-keys :block-origin-content)
                                     (:block/content block-entity))
            block-origin-tags      (when (contains? value-keys :block-origin-tags)
                                     (mapv :block/uuid (:block/tags block-entity)))
            block-origin-collapsed (when (contains? value-keys :block-origin-collapsed)
                                     (boolean (:block/collapsed? block-entity)))]
        [[::update-block
          (cond-> {:block-uuid block-uuid}
            (some? block-origin-content)   (assoc :block-origin-content block-origin-content)
            (some? block-origin-tags)      (assoc :block-origin-tags block-origin-tags)
            (some? block-origin-collapsed) (assoc :block-origin-collapsed block-origin-collapsed))]]))))

(def ^:private apply-conj-vec (partial apply (fnil conj [])))

(comment
  (def ^:private op-count-hard-limit 3000)
  (def ^:private op-count-limit 2000))

(defn- push-undo-ops
  [repo page-block-uuid ops]
  (assert (and (undo-ops-validator ops)
               (uuid? page-block-uuid))
          {:ops ops :page-block-uuid page-block-uuid})
  (swap! (:undo/repo->pege-block-uuid->undo-ops @worker-state/*state)
         update-in [repo page-block-uuid]
         apply-conj-vec ops))

(defn- pop-ops-helper
  [stack]
  (let [[ops i]
        (loop [i (dec (count stack)) r []]
          (let [peek-op (nth stack i nil)]
            (cond
              (neg? i)
              [r 0]

              (nil? peek-op)
              [r i]

              (= boundary peek-op)
              [r i]

              :else
              (recur (dec i) (conj r peek-op)))))]
    [ops (subvec (vec stack) 0 i)]))

(defn- pop-undo-ops
  [repo page-block-uuid]
  (assert (uuid? page-block-uuid) page-block-uuid)
  (let [repo->pege-block-uuid->undo-ops (:undo/repo->pege-block-uuid->undo-ops @worker-state/*state)
        undo-stack (get-in @repo->pege-block-uuid->undo-ops [repo page-block-uuid])
        [ops undo-stack*] (pop-ops-helper undo-stack)]
    (swap! repo->pege-block-uuid->undo-ops assoc-in [repo page-block-uuid] undo-stack*)
    ops))

(defn- empty-undo-stack?
  [repo page-block-uuid]
  (empty? (get-in @(:undo/repo->pege-block-uuid->undo-ops @worker-state/*state) [repo page-block-uuid])))

(defn- empty-redo-stack?
  [repo page-block-uuid]
  (empty? (get-in @(:undo/repo->pege-block-uuid->redo-ops @worker-state/*state) [repo page-block-uuid])))

(defn- push-redo-ops
  [repo page-block-uuid ops]
  (assert (and (undo-ops-validator ops)
               (uuid? page-block-uuid))
          {:ops ops :page-block-uuid page-block-uuid})
  (swap! (:undo/repo->pege-block-uuid->redo-ops @worker-state/*state)
         update-in [repo page-block-uuid]
         apply-conj-vec ops))

(defn- pop-redo-ops
  [repo page-block-uuid]
  (assert (uuid? page-block-uuid) page-block-uuid)
  (let [repo->pege-block-uuid->redo-ops (:undo/repo->pege-block-uuid->redo-ops @worker-state/*state)
        undo-stack (get-in @repo->pege-block-uuid->redo-ops [repo page-block-uuid])
        [ops undo-stack*] (pop-ops-helper undo-stack)]
    (swap! repo->pege-block-uuid->redo-ops assoc-in [repo page-block-uuid] undo-stack*)
    ops))

(defn- normal-block?
  [entity]
  (and (:block/uuid entity)
       (:block/parent entity)
       (:block/left entity)))

(defmulti ^:private reverse-apply-op (fn [op _conn _repo] (first op)))
(defmethod reverse-apply-op ::remove-block
  [op conn repo]
  (let [[_ {:keys [block-uuid block-entity-map]}] op
        block-entity (d/entity @conn [:block/uuid block-uuid])]
    (when-not block-entity ;; this block shouldn't exist now
      (when-let [left-entity (d/entity @conn [:block/uuid (:block/left block-entity-map)])]
        (let [sibling? (not= (:block/left block-entity-map) (:block/parent block-entity-map))]
          (some->>
           (outliner-tx/transact!
            {:gen-undo-op? false
             :outliner-op :insert-blocks
             :transact-opts {:repo repo
                             :conn conn}}
            (outliner-core/insert-blocks! repo conn
                                          [(cond-> {:block/uuid block-uuid
                                                    :block/content (:block/content block-entity-map)
                                                    :block/format :markdown}
                                             (:block/created-at block-entity-map)
                                             (assoc :block/created-at (:block/created-at block-entity-map))

                                             (:block/updated-at block-entity-map)
                                             (assoc :block/updated-at (:block/updated-at block-entity-map))

                                             (seq (:block/tags block-entity-map))
                                             (assoc :block/tags (some->> (:block/tags block-entity-map)
                                                                         (map (partial vector :block/uuid))
                                                                         (d/pull-many @conn [:db/id])
                                                                         (keep :db/id))))]
                                          left-entity {:sibling? sibling? :keep-uuid? true}))
           (conj [:push-undo-redo])))))))

(defn- sort-block-entities
  "return nil when there are other children existing"
  [block-entities]
  (let [sorted-block-entities (common-util/sort-coll-by-dependency
                               :block/uuid (comp :block/uuid :block/parent) block-entities)
        block-uuid-set (set (map :block/uuid sorted-block-entities))]
    (when-not
     (some ;; check no other children
      (fn [ent]
        (not-empty (set/difference (set (map :block/uuid (:block/_parent ent))) block-uuid-set)))
      sorted-block-entities)

      sorted-block-entities)))

(defmethod reverse-apply-op ::insert-blocks
  [op conn repo]
  (let [[_ {:keys [block-uuids]}] op]
    (when-let [block-entities (->> block-uuids
                                   (keep #(d/entity @conn [:block/uuid %]))
                                   sort-block-entities
                                   reverse
                                   not-empty)]
      (some->>
       (outliner-tx/transact!
        {:gen-undo-op? false
         :outliner-op :delete-blocks
         :transact-opts {:repo repo
                         :conn conn}}
        (outliner-core/delete-blocks! repo conn
                                      (common-config/get-date-formatter (worker-state/get-config repo))
                                      block-entities
                                      {:children? false}))
       (conj [:push-undo-redo])))))

(defmethod reverse-apply-op ::move-block
  [op conn repo]
  (let [[_ {:keys [block-uuid block-origin-left block-origin-parent]}] op]
    (when-let [block-entity (d/entity @conn [:block/uuid block-uuid])]
      (when-let [left-entity (d/entity @conn [:block/uuid block-origin-left])]
        (let [sibling? (not= block-origin-left block-origin-parent)]
          (some->>
           (outliner-tx/transact!
            {:gen-undo-op? false
             :outliner-op :move-blocks
             :transact-opts {:repo repo
                             :conn conn}}
            (outliner-core/move-blocks! repo conn [block-entity] left-entity sibling?))
           (conj [:push-undo-redo])))))))

(defmethod reverse-apply-op ::update-block
  [op conn repo]
  (let [[_ {:keys [block-uuid block-origin-content block-origin-tags block-origin-collapsed]}] op]
    (when-let [block-entity (d/entity @conn [:block/uuid block-uuid])]
      (when (normal-block? block-entity)
        (let [db-id (:db/id block-entity)
              _ (when (some? block-origin-tags)
                  (d/transact! conn [[:db/retract db-id :block/tags]] {:gen-undo-op? false}))
              new-block (cond-> block-entity
                          (some? block-origin-content)
                          (assoc :block/content block-origin-content)
                          (some? block-origin-tags)
                          (assoc :block/tags (some->> block-origin-tags
                                                      (map (partial vector :block/uuid))
                                                      (d/pull-many @conn [:db/id])
                                                      (keep :db/id)))
                          (some? block-origin-collapsed)
                          (assoc :block/collapsed? (boolean block-origin-collapsed)))
              r2 (outliner-tx/transact!
                  {:gen-undo-op? false
                   :outliner-op :save-block
                   :transact-opts {:repo repo
                                   :conn conn}}
                  (outliner-core/save-block! repo conn
                                             (common-config/get-date-formatter (worker-state/get-config repo))
                                             new-block))]

          (when r2 [:push-undo-redo r2]))))))

(defn- sort&merge-ops
  [ops]
  (let [groups (group-by first ops)
        remove-ops (groups ::remove-block)
        insert-ops (groups ::insert-blocks)
        other-ops (apply concat (vals (dissoc groups ::remove-block ::insert-blocks)))
        sorted-remove-ops (reverse
                           (common-util/sort-coll-by-dependency (comp :block-uuid second)
                                                                (comp :block/left :block-entity-map second)
                                                                remove-ops))
        insert-op (some->> (seq insert-ops)
                           (mapcat (fn [op] (:block-uuids (second op))))
                           (hash-map :block-uuids)
                           (vector ::insert-blocks))]
    (cond-> (concat sorted-remove-ops other-ops)
      insert-op (conj insert-op))))

(defn undo
  [repo page-block-uuid conn]
  (if-let [ops (not-empty (pop-undo-ops repo page-block-uuid))]
    (let [redo-ops-to-push (transient [])]
      (batch-tx/with-batch-tx-mode conn
        (doseq [op ops]
          (let [rev-ops (reverse-op @conn op)
                r (reverse-apply-op op conn repo)]
            (when (= :push-undo-redo (first r))
              (some-> *undo-redo-info-for-test* (reset! {:op op :tx (second r)}))
              (apply conj! redo-ops-to-push rev-ops)))))
      (when-let [rev-ops (not-empty (sort&merge-ops (persistent! redo-ops-to-push)))]
        (push-redo-ops repo page-block-uuid (cons boundary rev-ops)))
      nil)

    (when (empty-undo-stack? repo page-block-uuid)
      (prn "No further undo information")
      ::empty-undo-stack)))

(defn redo
  [repo page-block-uuid conn]
  (if-let [ops (not-empty (pop-redo-ops repo page-block-uuid))]
    (let [undo-ops-to-push (transient [])]
      (batch-tx/with-batch-tx-mode conn
        (doseq [op ops]
          (let [rev-ops (reverse-op @conn op)
                r (reverse-apply-op op conn repo)]
            (when (= :push-undo-redo (first r))
              (some-> *undo-redo-info-for-test* (reset! {:op op :tx (second r)}))
              (apply conj! undo-ops-to-push rev-ops)))))
      (when-let [rev-ops (not-empty (sort&merge-ops (persistent! undo-ops-to-push)))]
        (push-undo-ops repo page-block-uuid (cons boundary rev-ops)))
      nil)

    (when (empty-redo-stack? repo page-block-uuid)
      (prn "No further redo information")
      ::empty-redo-stack)))

;;; listen db changes and push undo-ops

(defn- entity-datoms=>ops
  [db-before db-after id->attr->datom entity-datoms]
  (when-let [e (ffirst entity-datoms)]
    (let [attr->datom (id->attr->datom e)]
      (when (seq attr->datom)
        (let [updated-key-set (set (keys attr->datom))
              {[_ _ block-uuid _ add1?]    :block/uuid
               [_ _ _ _ add3?]             :block/left
               [_ _ _ _ add4?]             :block/parent} attr->datom
              entity-before (d/entity db-before e)
              entity-after (d/entity db-after e)
              ops
              (cond
                (and (not add1?) block-uuid
                     (normal-block? entity-before))
                [[::remove-block
                  {:block-uuid (:block/uuid entity-before)
                   :block-entity-map (->block-entity-map db-before e)}]]

                (and add1? block-uuid
                     (normal-block? entity-after))
                [[::insert-blocks {:block-uuids [(:block/uuid entity-after)]}]]

                (and (or add3? add4?)
                     (normal-block? entity-after))
                (let [origin-left (:block/left entity-before)
                      origin-parent (:block/parent entity-before)
                      origin-left-in-db-after (d/entity db-after [:block/uuid (:block/uuid origin-left)])
                      origin-parent-in-db-after (d/entity db-after [:block/uuid (:block/uuid origin-parent)])
                      origin-left-and-parent-available-in-db-after?
                      (and origin-left-in-db-after origin-parent-in-db-after
                           (if (not= (:block/uuid origin-left) (:block/uuid origin-parent))
                             (= (:block/uuid (:block/parent origin-left))
                                (:block/uuid (:block/parent origin-left-in-db-after)))
                             true))]
                  (when origin-left-and-parent-available-in-db-after?
                    [[::move-block
                      {:block-uuid (:block/uuid entity-after)
                       :block-origin-left (:block/uuid (:block/left entity-before))
                       :block-origin-parent (:block/uuid (:block/parent entity-before))}]])))
              other-ops
              (let [updated-attrs (seq (set/intersection
                                        updated-key-set
                                        #{:block/content :block/tags :block/collapsed?}))]
                (when-let [update-block-op-value
                           (when (normal-block? entity-after)
                             (some->> updated-attrs
                                      (keep
                                       (fn [attr-name]
                                         (case attr-name
                                           :block/content
                                           (when-let [origin-content (:block/content entity-before)]
                                             [:block-origin-content origin-content])

                                           :block/tags
                                           [:block-origin-tags (mapv :block/uuid (:block/tags entity-before))]

                                           :block/collapsed?
                                           [:block-origin-collapsed (boolean (:block/collapsed? entity-before))]

                                           nil)))
                                      seq
                                      (into {:block-uuid (:block/uuid entity-after)})))]
                  [[::update-block update-block-op-value]]))]
          (concat ops other-ops))))))

(defn- find-page-block-uuid
  [db-before db-after same-entity-datoms-coll]
  (some
   (fn [entity-datoms]
     (when-let [e (ffirst entity-datoms)]
       (or (some-> (d/entity db-before e) :block/page :block/uuid)
           (some-> (d/entity db-after e) :block/page :block/uuid))))
   same-entity-datoms-coll))

(defn- generate-undo-ops
  [repo db-before db-after same-entity-datoms-coll id->attr->datom gen-boundary-op?]
  (when-let [page-block-uuid (find-page-block-uuid db-before db-after same-entity-datoms-coll)]
    (let [ops (mapcat (partial entity-datoms=>ops db-before db-after id->attr->datom) same-entity-datoms-coll)
          ops (sort&merge-ops ops)]
      (when (seq ops)
        (push-undo-ops repo page-block-uuid (if gen-boundary-op? (cons boundary ops) ops))))))

(defmethod db-listener/listen-db-changes :gen-undo-ops
  [_ {:keys [_tx-data tx-meta db-before db-after
             repo id->attr->datom same-entity-datoms-coll]}]
  (when (:gen-undo-op? tx-meta true)
    (generate-undo-ops repo db-before db-after same-entity-datoms-coll id->attr->datom
                       (:gen-undo-boundary-op? tx-meta true))))

;;; listen db changes and push undo-ops (ends)

(defn clear-undo-redo-stack
  []
  (reset! (:undo/repo->pege-block-uuid->redo-ops @worker-state/*state) {})
  (reset! (:undo/repo->pege-block-uuid->undo-ops @worker-state/*state) {}))

(comment

  (clear-undo-redo-stack)
  (add-watch (:undo/repo->pege-block-uuid->undo-ops @worker-state/*state)
             :xxx
             (fn [_ _ o n]
               (cljs.pprint/pprint {:k :undo
                                    :o o
                                    :n n})))

  (add-watch (:undo/repo->pege-block-uuid->redo-ops @worker-state/*state)
             :xxx
             (fn [_ _ o n]
               (cljs.pprint/pprint {:k :redo
                                    :o o
                                    :n n})))

  (remove-watch (:undo/repo->pege-block-uuid->undo-ops @worker-state/*state) :xxx)
  (remove-watch (:undo/repo->pege-block-uuid->redo-ops @worker-state/*state) :xxx)
  )
