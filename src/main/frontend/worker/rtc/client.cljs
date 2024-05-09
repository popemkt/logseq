(ns frontend.worker.rtc.client
  "Fns about push local updates"
  (:require [clojure.set :as set]
            [cognitect.transit :as transit]
            [datascript.core :as d]
            [frontend.common.missionary-util :as c.m]
            [frontend.worker.rtc.const :as rtc-const]
            [frontend.worker.rtc.exception :as r.ex]
            [frontend.worker.rtc.op-mem-layer :as op-mem-layer]
            [frontend.worker.rtc.remote-update :as r.remote-update]
            [frontend.worker.rtc.ws2 :as ws]
            [logseq.db :as ldb]
            [missionary.core :as m]))

(def ^:private transit-w (transit/writer :json))

(defn- handle-remote-ex
  [resp]
  (if-let [e ({:graph-not-exist r.ex/ex-remote-graph-not-exist
               :graph-not-ready r.ex/ex-remote-graph-not-ready}
              (:type (:ex-data resp)))]
    (throw e)
    resp))

(defn send&recv
  "Return a task: throw exception if recv ex-data response"
  [get-ws-create-task message]
  (m/sp
    (let [ws (m/? get-ws-create-task)]
      (handle-remote-ex (m/? (ws/send&recv ws message))))))

(defn- register-graph-updates
  [get-ws-create-task graph-uuid]
  (m/sp
   (try
     (m/? (send&recv get-ws-create-task {:action "register-graph-updates"
                                         :graph-uuid graph-uuid}))
     (catch :default e
       (if (= :rtc.exception/remote-graph-not-ready (:type (ex-data e)))
         (throw (ex-info "remote graph is still creating" {:missionary/retry true} e))
         (throw e))))))

(defn- ensure-register-graph-updates*
  "Return a task: get or create a mws(missionary wrapped websocket).
  see also `ws/get-mws-create`.
  But ensure `register-graph-updates` has been sent"
  [get-ws-create-task graph-uuid]
  (assert (some? graph-uuid))
  (let [*sent (atom {}) ;; ws->bool
        ]
    (m/sp
      (let [ws (m/? get-ws-create-task)]
        (when-not (contains? @*sent ws)
          (swap! *sent assoc ws false))
        (when (not (@*sent ws))
          (m/? (c.m/backoff
                (take 5 c.m/delays)     ;retry 5 times (32s) if remote-graph is creating
                (register-graph-updates get-ws-create-task graph-uuid)))
          (swap! *sent assoc ws true))
        ws))))

(def ensure-register-graph-updates (memoize ensure-register-graph-updates*))

(defn- remove-non-exist-block-uuids-in-add-retract-map
  [conn add-retract-map]
  (let [{:keys [add retract]} add-retract-map
        add* (->> add
                  (map (fn [x] [:block/uuid x]))
                  (d/pull-many @conn [:block/uuid])
                  (keep :block/uuid))]
    (cond-> {}
      (seq add*) (assoc :add add*)
      (seq retract) (assoc :retract retract))))

(defn- ->pos
  [left-uuid parent-uuid]
  (cond
    (or (nil? left-uuid) (nil? parent-uuid)) :no-order
    (not= left-uuid parent-uuid)             :sibling
    :else                                    :child))

(defmulti ^:private local-block-ops->remote-ops-aux (fn [tp & _] tp))

(defmethod local-block-ops->remote-ops-aux :move-op
  [_ & {:keys [parent-uuid left-uuid block-uuid *remote-ops *depend-on-block-uuid-set]}]
  (when parent-uuid
    (let [target-uuid (or left-uuid parent-uuid)
          pos         (->pos left-uuid parent-uuid)]
      (swap! *remote-ops conj [:move {:block-uuid block-uuid :target-uuid target-uuid :pos pos}])
      (swap! *depend-on-block-uuid-set conj target-uuid))))

(defmethod local-block-ops->remote-ops-aux :update-op
  [_ & {:keys [conn user-uuid block update-op left-uuid parent-uuid *remote-ops]}]
  (let [block-uuid          (:block/uuid block)
        attr-map            (:updated-attrs (second update-op))
        attr-alias-map      (when (contains? attr-map :alias)
                              (remove-non-exist-block-uuids-in-add-retract-map conn (:alias attr-map)))
        attr-tags-map       (when (contains? attr-map :tags)
                              (remove-non-exist-block-uuids-in-add-retract-map conn (:tags attr-map)))
        attr-type-map       (when (contains? attr-map :type)
                              (let [{:keys [add retract]} (:type attr-map)
                                    current-type-value    (set (:block/type block))
                                    add                   (set/intersection add current-type-value)
                                    retract               (set/difference retract current-type-value)]
                                (cond-> {}
                                  (seq add)     (assoc :add add)
                                  (seq retract) (assoc :retract retract))))
        attr-properties-map (when (contains? attr-map :properties)
                              (let [{:keys [add retract]} (:properties attr-map)
                                    properties            (:block/properties block)
                                    add*                  (into []
                                                                (update-vals (select-keys properties add)
                                                                             (partial transit/write transit-w)))]
                                (cond-> {}
                                  (seq add*)    (assoc :add add*)
                                  (seq retract) (assoc :retract retract))))
        target-uuid         (or left-uuid parent-uuid)
        pos                 (->pos left-uuid parent-uuid)]
    (swap! *remote-ops conj
           [:update
            (cond-> {:block-uuid block-uuid}
              (:block/journal-day block)    (assoc :journal-day (:block/journal-day block))
              (:block/updated-at block)     (assoc :updated-at (:block/updated-at block))
              (:block/created-at block)     (assoc :created-at (:block/created-at block))
              (= (:block/updated-at block)
                 (:block/created-at block)) (assoc :created-by user-uuid)
              (contains? attr-map :schema)  (assoc :schema
                                                   (transit/write transit-w (:block/schema block)))
              attr-alias-map                (assoc :alias attr-alias-map)
              attr-type-map                 (assoc :type attr-type-map)
              attr-tags-map                 (assoc :tags attr-tags-map)
              attr-properties-map           (assoc :properties attr-properties-map)
              (and (contains? attr-map :content)
                   (:block/raw-content block))
              (assoc :content (:block/raw-content block))
              (and (contains? attr-map :link)
                   (:block/uuid (:block/link block)))
              (assoc :link (:block/uuid (:block/link block)))
              target-uuid                   (assoc :target-uuid target-uuid :pos pos))])))

(defmethod local-block-ops->remote-ops-aux :update-page-op
  [_ & {:keys [conn block-uuid *remote-ops]}]
  (when-let [{page-name :block/name original-name :block/original-name}
             (d/entity @conn [:block/uuid block-uuid])]
    (swap! *remote-ops conj
           [:update-page {:block-uuid block-uuid
                          :page-name page-name
                          :original-name (or original-name page-name)}])))

(defmethod local-block-ops->remote-ops-aux :remove-op
  [_ & {:keys [conn remove-op *remote-ops]}]
  (when-let [block-uuid (:block-uuid (second remove-op))]
    (when (nil? (d/entity @conn [:block/uuid block-uuid]))
      (swap! *remote-ops conj [:remove {:block-uuids [block-uuid]}]))))

(defmethod local-block-ops->remote-ops-aux :remove-page-op
  [_ & {:keys [conn remove-page-op *remote-ops]}]
  (when-let [block-uuid (:block-uuid (second remove-page-op))]
    (when (nil? (d/entity @conn [:block/uuid block-uuid]))
      (swap! *remote-ops conj [:remove-page {:block-uuid block-uuid}]))))

(defn- local-block-ops->remote-ops
  [repo conn user-uuid block-ops]
  (let [*depend-on-block-uuid-set (atom #{})
        *remote-ops (atom [])
        {move-op :move remove-op :remove update-op :update update-page-op :update-page remove-page-op :remove-page}
        block-ops]
    (when-let [block-uuid
               (some (comp :block-uuid second) [move-op update-op update-page-op])]
      (when-let [block (d/entity @conn [:block/uuid block-uuid])]
        (let [left-uuid (:block/uuid (ldb/get-left-sibling block))
              parent-uuid (some-> block :block/parent :block/uuid)]
          (when parent-uuid
            ;; remote-move-op
            (when move-op
              (local-block-ops->remote-ops-aux :move-op
                                               :parent-uuid parent-uuid
                                               :left-uuid left-uuid
                                               :block-uuid block-uuid
                                               :*remote-ops *remote-ops
                                               :*depend-on-block-uuid-set *depend-on-block-uuid-set)))
          ;; remote-update-op
          (when update-op
            (local-block-ops->remote-ops-aux :update-op
                                             :repo repo
                                             :user-uuid user-uuid
                                             :conn conn
                                             :block block
                                             :update-op update-op
                                             :parent-uuid parent-uuid
                                             :left-uuid left-uuid
                                             :*remote-ops *remote-ops
                                             :created-by user-uuid)))
        ;; remote-update-page-op
        (when update-page-op
          (local-block-ops->remote-ops-aux :update-page-op
                                           :repo repo
                                           :conn conn
                                           :block-uuid block-uuid
                                           :*remote-ops *remote-ops))))
    ;; remote-remove-op
    (when remove-op
      (local-block-ops->remote-ops-aux :remove-op
                                       :repo repo
                                       :conn conn
                                       :remove-op remove-op
                                       :*remote-ops *remote-ops))

    ;; remote-remove-page-op
    (when remove-page-op
      (local-block-ops->remote-ops-aux :remove-page-op
                                       :repo repo
                                       :conn conn
                                       :remove-page-op remove-page-op
                                       :*remote-ops *remote-ops))

    {:remote-ops @*remote-ops
     :depend-on-block-uuids @*depend-on-block-uuid-set}))

(defn- gen-block-uuid->remote-ops
  [repo conn user-uuid & {:keys [n] :or {n 50}}]
  (loop [current-handling-block-ops nil
         current-handling-block-uuid nil
         depend-on-block-uuid-coll nil
         r {}]
    (cond
      (and (empty? current-handling-block-ops)
           (empty? depend-on-block-uuid-coll)
           (>= (count r) n))
      r

      (and (empty? current-handling-block-ops)
           (empty? depend-on-block-uuid-coll))
      (if-let [{min-epoch-block-ops :ops block-uuid :block-uuid} (op-mem-layer/get-min-epoch-block-ops repo)]
        (do (assert (not (contains? r block-uuid)) {:r r :block-uuid block-uuid})
            (op-mem-layer/remove-block-ops! repo block-uuid)
            (recur min-epoch-block-ops block-uuid depend-on-block-uuid-coll r))
        ;; finish
        r)

      (and (empty? current-handling-block-ops)
           (seq depend-on-block-uuid-coll))
      (let [[block-uuid & other-block-uuids] depend-on-block-uuid-coll
            block-ops (op-mem-layer/get-block-ops repo block-uuid)]
        (op-mem-layer/remove-block-ops! repo block-uuid)
        (recur block-ops block-uuid other-block-uuids r))

      (seq current-handling-block-ops)
      (let [{:keys [remote-ops depend-on-block-uuids]}
            (local-block-ops->remote-ops repo conn user-uuid current-handling-block-ops)]
        (recur nil nil
               (set/union (set depend-on-block-uuid-coll)
                          (op-mem-layer/intersection-block-uuids repo depend-on-block-uuids))
               (assoc r current-handling-block-uuid (into {} remote-ops)))))))

(defn- merge-remove-remove-ops
  [remote-remove-ops]
  (when-let [block-uuids (->> remote-remove-ops
                              (mapcat (fn [[_ {:keys [block-uuids]}]] block-uuids))
                              distinct
                              seq)]
    [[:remove {:block-uuids block-uuids}]]))

(defn- sort-remote-ops
  [block-uuid->remote-ops]
  (let [block-uuid->dep-uuid
        (into {}
              (keep (fn [[block-uuid remote-ops]]
                      (when-let [move-op (get remote-ops :move)]
                        [block-uuid (:target-uuid move-op)])))
              block-uuid->remote-ops)
        all-move-uuids (set (keys block-uuid->dep-uuid))
        sorted-uuids
        (loop [r []
               rest-uuids all-move-uuids
               uuid (first rest-uuids)]
          (if-not uuid
            r
            (let [dep-uuid (block-uuid->dep-uuid uuid)]
              (if-let [next-uuid (get rest-uuids dep-uuid)]
                (recur r rest-uuids next-uuid)
                (let [rest-uuids* (disj rest-uuids uuid)]
                  (recur (conj r uuid) rest-uuids* (first rest-uuids*)))))))
        sorted-move-ops (keep
                         (fn [block-uuid]
                           (some->> (get-in block-uuid->remote-ops [block-uuid :move])
                                    (vector :move)))
                         sorted-uuids)
        remove-ops (merge-remove-remove-ops
                    (keep
                     (fn [[_ remote-ops]]
                       (some->> (:remove remote-ops) (vector :remove)))
                     block-uuid->remote-ops))
        update-ops (keep
                    (fn [[_ remote-ops]]
                      (some->> (:update remote-ops) (vector :update)))
                    block-uuid->remote-ops)
        update-page-ops (keep
                         (fn [[_ remote-ops]]
                           (some->> (:update-page remote-ops) (vector :update-page)))
                         block-uuid->remote-ops)
        remove-page-ops (keep
                         (fn [[_ remote-ops]]
                           (some->> (:remove-page remote-ops) (vector :remove-page)))
                         block-uuid->remote-ops)]
    (concat update-page-ops remove-ops sorted-move-ops update-ops remove-page-ops)))

(defn new-task--push-local-ops
  "Return a task: push local updates"
  [repo conn user-uuid graph-uuid date-formatter get-ws-create-task add-log-fn]
  (m/sp
    (when-let [ops-for-remote (rtc-const/to-ws-ops-decoder
                               (sort-remote-ops
                                (gen-block-uuid->remote-ops repo conn user-uuid)))]
      (op-mem-layer/new-branch! repo)
      (let [local-tx (op-mem-layer/get-local-tx repo)
            r (m/? (send&recv get-ws-create-task {:action "apply-ops" :graph-uuid graph-uuid
                                                  :ops ops-for-remote :t-before (or local-tx 1)}))]
        (if-let [remote-ex (:ex-data r)]
          (do (add-log-fn remote-ex)
              (case (:type remote-ex)
              ;; - :graph-lock-failed
              ;;   conflict-update remote-graph, keep these local-pending-ops
              ;;   and try to send ops later
                :graph-lock-failed
                (do (op-mem-layer/rollback! repo)
                    nil)
              ;; - :graph-lock-missing
              ;;   this case means something wrong in remote-graph data,
              ;;   nothing to do at client-side
                :graph-lock-missing
                (do (op-mem-layer/rollback! repo)
                    (throw r.ex/ex-remote-graph-lock-missing))

                :rtc.exception/get-s3-object-failed
                (do (op-mem-layer/rollback! repo)
                    nil)
              ;; else
                (do (op-mem-layer/rollback! repo)
                    (throw (ex-info "Unavailable" {:remote-ex remote-ex})))))

          (do (assert (pos? (:t r)) r)
              (op-mem-layer/commit! repo)
              (r.remote-update/apply-remote-update
               repo conn date-formatter {:type :remote-update :value r} add-log-fn)
              (add-log-fn {:type ::push-client-updates :remote-t (:t r)})))))))
