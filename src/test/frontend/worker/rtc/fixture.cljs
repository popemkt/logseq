(ns frontend.worker.rtc.fixture
  (:require [cljs.core.async :as async :refer [<! >! chan go]]
            [cljs.test :as t]
            [datascript.core :as d]
            [frontend.db :as db]
            [frontend.db.conn :as conn]
            [frontend.state :as state]
            [frontend.test.helper :as test-helper]
            [frontend.worker.db-listener :as worker-db-listener]
            [frontend.worker.rtc.asset-sync :as asset-sync]
            [frontend.worker.rtc.core :as rtc-core]
            [frontend.worker.rtc.db-listener :as db-listener]
            [frontend.worker.rtc.mock :as rtc-mock]
            [frontend.worker.rtc.op-mem-layer :as op-mem-layer]))

(def *test-rtc-state (atom nil))
(def *test-asset-sync-state (atom nil))
(def test-graph-uuid "e6d04ed7-bbc4-4ed2-a91b-69f3c0b9459d")
(def test-graph-init-local-t 1)

(defn- init-state-helper
  []
  (let [data-from-ws-chan (chan (async/sliding-buffer 100))
        ws (rtc-mock/mock-websocket data-from-ws-chan)]
    (assoc (rtc-core/init-state ws data-from-ws-chan "" "user-uuid" true)
           :*auto-push-client-ops? (atom false))))

(defn- init-state-helper-for-asset-sync-loop
  []
  (let [data-from-ws-chan (chan (async/sliding-buffer 100))
        ws (rtc-mock/mock-websocket data-from-ws-chan)
        rtc-state (rtc-core/init-state ws data-from-ws-chan "" "user-uuid" true)]
    (assoc (asset-sync/init-state-from-rtc-state rtc-state)
           :*auto-push-assets-update-ops? (atom false))))

(defn- <start-rtc-loop
  []
  (go
    (let [graph-uuid test-graph-uuid
          repo test-helper/test-db-name-db-version
          state (init-state-helper)
          loop-started-ch (chan)]
      (reset! *test-rtc-state state)
      (rtc-core/<loop-for-rtc state graph-uuid repo (db/get-db repo false) (state/get-date-formatter) :loop-started-ch loop-started-ch)
      (<! loop-started-ch))))

(defn- <start-asset-sync-loop
  []
  (go
    (let [graph-uuid test-graph-uuid
          repo test-helper/test-db-name-db-version
          state (init-state-helper-for-asset-sync-loop)
          loop-started-ch (chan)]
      (reset! *test-asset-sync-state state)
      (asset-sync/<loop-for-assets-sync state graph-uuid repo (db/get-db repo false) :loop-started-ch loop-started-ch)
      (<! loop-started-ch))))

(def start-and-stop-rtc-loop-fixture
  {:before
   #(t/async done
      (go
        (<! (<start-rtc-loop))
        (prn :<started-rtc-loop)
        (done)))
   :after
   #(t/async done
      (go
        (when-let [stop-rtc-loop-chan (some-> (:*stop-rtc-loop-chan @*test-rtc-state) deref)]
          (prn :stopping-rtc-loop)
          (>! stop-rtc-loop-chan true))
        (reset! *test-rtc-state nil)
        (done)))})

(def start-and-stop-asset-sync-loop-fixture
  {:before
   #(t/async done
      (go
        (<! (<start-asset-sync-loop))
        (prn :<start-asset-sync-loop)
        (done)))
   :after
   #(t/async done
      (go
        (when-let [stop-asset-sync-loop-chan (some-> (:*stop-asset-sync-loop-chan @*test-asset-sync-state) deref)]
          (prn :stopping-asset-sync-loop)
          (>! stop-asset-sync-loop-chan true))
        (reset! *test-asset-sync-state nil)
        (done)))})


(def listen-test-db-fixture
  {:before
   #(let [test-db-conn (conn/get-db test-helper/test-db-name-db-version false)]
      (assert (some? test-db-conn))
      (d/listen! test-db-conn
                 ::gen-ops
                 (fn [{:keys [tx-data tx-meta db-before db-after]}]
                   (let [datom-vec-coll (map vec tx-data)
                         id->same-entity-datoms (group-by first datom-vec-coll)
                         id-order (distinct (map first datom-vec-coll))
                         same-entity-datoms-coll (map id->same-entity-datoms id-order)
                         id->attr->datom (update-vals
                                          id->same-entity-datoms
                                          #'worker-db-listener/entity-datoms=>attr->datom)]
                     (when (:persist-op? tx-meta true)
                       (db-listener/generate-rtc-ops test-helper/test-db-name-db-version db-before db-after
                                                     same-entity-datoms-coll id->attr->datom))))))
   :after
   #(when-let [test-db-conn (conn/get-db test-helper/test-db-name-db-version false)]
      (d/unlisten! test-db-conn ::gen-ops))})


(def clear-op-mem-stores-fixture
  {:before #(do (op-mem-layer/remove-ops-store! test-helper/test-db-name-db-version)
                (op-mem-layer/init-empty-ops-store! test-helper/test-db-name-db-version))
   :after #(op-mem-layer/remove-ops-store! test-helper/test-db-name-db-version)})
