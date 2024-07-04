(ns logseq.db.frontend.class
  "Class related fns for DB graphs and frontend/datascript usage"
  (:require [logseq.db.sqlite.util :as sqlite-util]
            [logseq.db.frontend.db-ident :as db-ident]))

(def ^:large-vars/data-var built-in-classes
  "Map of built-in classes for db graphs with their :db/ident as keys"
  {:logseq.class/Root {:original-name "Root class"}

   :logseq.class/task
   {:original-name "Task"
    :schema {:properties [:logseq.task/status :logseq.task/priority :logseq.task/deadline]}}

   :logseq.class/Card {:original-name "Card"
                       ;; :schema {:property []}
                       }
   ;; TODO: Add more classes such as :book, :paper, :movie, :music, :project
   })

(defn create-user-class-ident-from-name
  "Creates a class :db/ident for a default user namespace.
   NOTE: Only use this when creating a db-ident for a new class."
  [class-name]
  (db-ident/create-db-ident-from-name "user.class" class-name))

(defn build-new-class
  "Builds a new class with a unique :db/ident. Also throws exception for user
  facing messages when name is invalid"
  [db page-m]
  {:pre [(string? (:block/original-name page-m))]}
  (let [db-ident (try (create-user-class-ident-from-name (:block/original-name page-m))
                      (catch :default e
                        (throw (ex-info (str e)
                                        {:type :notification
                                         :payload {:message "Failed to create class. Please try a different class name."
                                                   :type :error}}))))
        db-ident' (db-ident/ensure-unique-db-ident db db-ident)]
    (sqlite-util/build-new-class (assoc page-m :db/ident db-ident'))))