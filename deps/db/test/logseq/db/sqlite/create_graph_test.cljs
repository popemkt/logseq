(ns logseq.db.sqlite.create-graph-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as string]
            [clojure.set :as set]
            [datascript.core :as d]
            [logseq.db.frontend.schema :as db-schema]
            [logseq.db.sqlite.create-graph :as sqlite-create-graph]
            [logseq.db.frontend.validate :as db-validate]
            [logseq.db :as ldb]))

(deftest new-graph-db-idents
  (testing "a new graph follows :db/ident conventions for"
    (let [conn (d/create-conn db-schema/schema-for-db-based-graph)
          _ (d/transact! conn (sqlite-create-graph/build-db-initial-data @conn "{}"))
          ident-ents (->> (d/q '[:find (pull ?b [:db/ident :block/type])
                                 :where [?b :db/ident]]
                               @conn)
                          (map first))
          default-idents (map :db/ident ident-ents)]
      (is (> (count default-idents) 75)
          "Approximate number of default idents is correct")

      (testing "namespaces"
        (is (= '() (remove namespace default-idents))
            "All default :db/ident's have namespaces")
        (is (= []
               (->> (keep namespace default-idents)
                    (remove #(string/starts-with? % "logseq."))))
            "All default :db/ident namespaces start with logseq."))

      (testing "closed values"
        (let [closed-value-ents (filter #(string/includes? (name (:db/ident %)) ".") ident-ents)
              closed-value-properties (->> closed-value-ents
                                           (map :db/ident)
                                           (map #(keyword (namespace %) (string/replace (name %) #".[^.]+$" "")))
                                           set)]
          (is (= []
                 (remove #(= ["closed value"] (:block/type %)) closed-value-ents))
              "All property names that contain a '.' are closed values")
          (is (= #{}
                 (set/difference closed-value-properties (set default-idents)))
              "All closed values start with a prefix that is a property name"))))))

(deftest new-graph-marks-built-ins
  (let [conn (d/create-conn db-schema/schema-for-db-based-graph)
        _ (d/transact! conn (sqlite-create-graph/build-db-initial-data @conn "{}"))
        idents (->> (d/q '[:find [(pull ?b [:db/ident :block/properties]) ...]
                           :where [?b :db/ident]]
                         @conn)
                    ;; only kv's don't have built-in property
                    (remove #(= "logseq.kv" (namespace (:db/ident %)))))]
    (is (= []
           (remove #(ldb/built-in? @conn %) idents))
        "All entities with :db/ident have built-in property (except for kv idents)")))

(deftest new-graph-creates-class
  (let [conn (d/create-conn db-schema/schema-for-db-based-graph)
        _ (d/transact! conn (sqlite-create-graph/build-db-initial-data @conn "{}"))
        task (d/entity @conn :logseq.class/task)]
    (is (contains? (:block/type task) "class")
        "Task class has correct type")
    (is (= 4 (count (get-in task [:block/schema :properties])))
        "Has correct number of task properties")
    (is (every? #(contains? (:block/type (d/entity @conn [:block/uuid %])) "property")
                (get-in task [:schema :properties]))
        "Each task property has correct type")))

(deftest new-graph-is-valid
  (let [conn (d/create-conn db-schema/schema-for-db-based-graph)
        _ (d/transact! conn (sqlite-create-graph/build-db-initial-data @conn "{}"))
        validation (db-validate/validate-db! @conn)]
    (is (nil? (:errors validation))
        "New graph has no validation errors")))