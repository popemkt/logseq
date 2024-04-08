(ns frontend.worker.state
  "State hub for worker"
  (:require [logseq.common.util :as common-util]
            [logseq.common.config :as common-config]))

(defonce *state (atom {:worker/object nil

                       :db/latest-transact-time {}
                       :worker/context {}

                       ;; FIXME: this name :config is too general
                       :config {}
                       :git/current-repo nil
                       :rtc/batch-processing? false
                       :rtc/remote-batch-txs nil
                       :rtc/downloading-graph? false

                       :undo/repo->undo-stack (atom {})
                       :undo/repo->redo-stack (atom {})}))

(defonce *rtc-ws-url (atom nil))

(defonce *sqlite (atom nil))
;; repo -> {:db conn :search conn}
(defonce *sqlite-conns (atom nil))
;; repo -> conn
(defonce *datascript-conns (atom nil))
;; repo -> pool
(defonce *opfs-pools (atom nil))

(defn get-sqlite-conn
  [repo & {:keys [search?]
           :or {search? false}
           :as _opts}]
  (let [k (if search? :search :db)]
    (get-in @*sqlite-conns [repo k])))

(defn get-datascript-conn
  [repo]
  (get @*datascript-conns repo))

(defn get-opfs-pool
  [repo]
  (get @*opfs-pools repo))

(defn tx-idle?
  [repo & {:keys [diff]
           :or {diff 1000}}]
  (when repo
    (let [last-input-time (get-in @*state [:db/latest-transact-time repo])]
      (or
       (nil? last-input-time)

       (let [now (common-util/time-ms)]
         (>= (- now last-input-time) diff))))))

(defn set-db-latest-tx-time!
  [repo]
  (swap! *state assoc-in [:db/latest-transact-time repo] (common-util/time-ms)))

(defn get-context
  []
  (:worker/context @*state))

(defn set-context!
  [context]
  (swap! *state assoc :worker/context context))

(defn get-config
  [repo]
  (get-in @*state [:config repo]))

(defn get-current-repo
  []
  (:git/current-repo @*state))

(defn set-new-state!
  [new-state]
  (swap! *state (fn [old-state]
                  (merge old-state new-state))))

(defn set-worker-object!
  [worker]
  (swap! *state assoc :worker/object worker))

(defn get-worker-object
  []
  (:worker/object @*state))

(defn get-date-formatter
  [repo]
  (common-config/get-date-formatter (get-config repo)))

(defn set-rtc-downloading-graph!
  [value]
  (swap! *state assoc :rtc/downloading-graph? value))

(defn rtc-downloading-graph?
  []
  (:rtc/downloading-graph? @*state))

(defn start-batch-tx-mode!
  []
  (swap! *state assoc :rtc/batch-processing? true))

(defn rtc-batch-processing?
  []
  (:rtc/batch-processing? @*state))

(defn get-batch-txs
  []
  (:rtc/remote-batch-txs @*state))

(defn conj-batch-txs!
  [tx-data]
  (swap! *state update :rtc/remote-batch-txs (fn [data] (into data tx-data))))

(defn exit-batch-tx-mode!
  []
  (swap! *state assoc :rtc/batch-processing? false)
  (swap! *state assoc :rtc/remote-batch-txs nil))
