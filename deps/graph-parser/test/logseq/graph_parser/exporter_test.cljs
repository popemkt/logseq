(ns ^:node-only logseq.graph-parser.exporter-test
  (:require [cljs.test :refer [testing is]]
            [logseq.graph-parser.test.helper :as test-helper :include-macros true :refer [deftest-async]]
            [datascript.core :as d]
            [clojure.string :as string]
            ["path" :as node-path]
            ["fs" :as fs]
            [logseq.common.graph :as common-graph]
            [promesa.core :as p]
            [logseq.db.frontend.schema :as db-schema]
            [logseq.db.frontend.validate :as db-validate]
            [logseq.db.sqlite.create-graph :as sqlite-create-graph]
            [logseq.graph-parser.exporter :as gp-exporter]
            [logseq.db.frontend.malli-schema :as db-malli-schema]
            [logseq.db.frontend.property :as db-property]
            [logseq.db.frontend.property.type :as db-property-type]))

;; Helpers
;; =======
;; some have been copied from db-import script

(defn- find-block-by-content [db content]
  (if (instance? js/RegExp content)
    (->> content
         (d/q '[:find [(pull ?b [*]) ...]
                :in $ ?pattern
                :where [?b :block/content ?content] [(re-find ?pattern ?content)]]
              db)
         first)
    (->> content
         (d/q '[:find [(pull ?b [*]) ...]
                :in $ ?content
                :where [?b :block/content ?content]]
              db)
         first)))

(defn- find-page-by-name [db name]
  (->> name
       (d/q '[:find [(pull ?b [*]) ...]
              :in $ ?name
              :where [?b :block/original-name ?name]]
            db)
       first))

(defn- build-graph-files
  "Given a file graph directory, return all files including assets and adds relative paths
   on ::rpath since paths are absolute by default and exporter needs relative paths for
   some operations"
  [dir*]
  (let [dir (node-path/resolve dir*)]
    (->> (common-graph/get-files dir)
         (concat (when (fs/existsSync (node-path/join dir* "assets"))
                   (common-graph/readdir (node-path/join dir* "assets"))))
         (mapv #(hash-map :path %
                          ::rpath (node-path/relative dir* %))))))

(defn- <read-file
  [file]
  (p/let [s (fs/readFileSync (:path file))]
    (str s)))

(defn- notify-user [m]
  (println (:msg m))
  (println "Ex-data:" (pr-str (dissoc (:ex-data m) :error)))
  (println "Stacktrace:")
  (if-let [stack (some-> (get-in m [:ex-data :error]) ex-data :sci.impl/callstack deref)]
    (println (string/join
              "\n"
              (map
               #(str (:file %)
                     (when (:line %) (str ":" (:line %)))
                     (when (:sci.impl/f-meta %)
                       (str " calls #'" (get-in % [:sci.impl/f-meta :ns]) "/" (get-in % [:sci.impl/f-meta :name]))))
               (reverse stack))))
    (println (some-> (get-in m [:ex-data :error]) .-stack))))

(def default-export-options
  {;; common options
   :rpath-key ::rpath
   :notify-user notify-user
   :<read-file <read-file
   ;; :set-ui-state prn
   ;; config file options
   ;; TODO: Add actual default
   :default-config {}})

;; Copied from db-import script and tweaked for an in-memory import
(defn- import-file-graph-to-db
  "Import a file graph dir just like UI does. However, unlike the UI the
  exporter receives file maps containing keys :path and ::rpath since :path
  are full paths"
  [file-graph-dir conn {:keys [assets] :as options}]
  (let [*files (build-graph-files file-graph-dir)
        config-file (first (filter #(string/ends-with? (:path %) "logseq/config.edn") *files))
        _ (assert config-file "No 'logseq/config.edn' found for file graph dir")
        options' (-> (merge options
                            default-export-options
                            ;; asset file options
                            {:<copy-asset #(swap! assets conj %)})
                     (dissoc :assets))]
    (gp-exporter/export-file-graph conn conn config-file *files options')))

(defn- import-files-to-db
  "Import specific doc files for dev purposes"
  [files conn options]
  (let [doc-options (gp-exporter/build-doc-options {:macros {}} (merge options default-export-options))
        files' (mapv #(hash-map :path %) files)]
    (gp-exporter/export-doc-files conn files' <read-file doc-options)))

(defn- readable-properties
  [db query-ent]
  (->> (db-property/properties query-ent)
       (map (fn [[k v]]
              [k
               (if-let [built-in-type (get-in db-property/built-in-properties [k :schema :type])]
                 (if (= :block/tags k)
                   (mapv #(:db/ident (d/entity db (:db/id %))) v)
                   (if (db-property-type/ref-property-types built-in-type)
                     (db-property/ref->property-value-contents db v)
                     v))
                 (db-property/ref->property-value-contents db v))]))
       (into {})))

;; Tests
;; =====

(deftest-async export-basic-graph
  ;; This graph will contain basic examples of different features to import
  (p/let [file-graph-dir "test/resources/exporter-test-graph"
          conn (d/create-conn db-schema/schema-for-db-based-graph)
          _ (d/transact! conn (sqlite-create-graph/build-db-initial-data "{}"))
          assets (atom [])
          {:keys [import-state]} (import-file-graph-to-db file-graph-dir conn {:assets assets})]

    (is (nil? (:errors (db-validate/validate-db! @conn)))
        "Created graph has no validation errors")

    (testing "logseq files"
      (is (= ".foo {}\n"
             (ffirst (d/q '[:find ?content :where [?b :file/path "logseq/custom.css"] [?b :file/content ?content]] @conn))))
      (is (= "logseq.api.show_msg('hello good sir!');\n"
             (ffirst (d/q '[:find ?content :where [?b :file/path "logseq/custom.js"] [?b :file/content ?content]] @conn)))))

    (testing "graph wide counts"
      ;; Includes 2 journals as property values for :logseq.task/deadline
      (is (= 8 (count (d/q '[:find ?b :where [?b :block/type "journal"]] @conn))))
      ;; Count includes Contents and page references
      (is (= 7
             (count (d/q '[:find (pull ?b [*]) :where [?b :block/original-name ?name] (not [?b :block/type])] @conn))))
      (is (= 1 (count @assets))))

    (testing "user properties"
      (is (= #{{:db/ident :user.property/prop-bool :block/schema {:type :checkbox}}
               {:db/ident :user.property/prop-string :block/schema {:type :default}}
               {:db/ident :user.property/prop-num :block/schema {:type :number}}
               {:db/ident :user.property/prop-num2 :block/schema {:type :number}}}
             (->> @conn
                  (d/q '[:find [(pull ?b [:db/ident :block/schema]) ...]
                         :where [?b :block/type "property"]])
                  (remove #(db-malli-schema/internal-ident? (:db/ident %)))
                  set))
          "Properties defined correctly")

      (is (= {:user.property/prop-bool true
              :user.property/prop-num 5
              :user.property/prop-string "woot"}
             (update-vals (db-property/properties (find-block-by-content @conn "b1"))
                          #(db-property/ref->property-value-content @conn %)))
          "Basic block has correct properties")
      (is (= #{"prop-num" "prop-string" "prop-bool"}
             (->> (d/entity @conn (:db/id (find-block-by-content @conn "b1")))
                  :block/refs
                  (map :block/original-name)
                  set))
          "Block with properties has correct refs")

      (is (= {:user.property/prop-num2 10}
             (readable-properties @conn (find-page-by-name @conn "new page")))
          "New page has correct properties")
      (is (= {:user.property/prop-bool true
              :user.property/prop-num 5
              :user.property/prop-string "yeehaw"}
             (readable-properties @conn (find-page-by-name @conn "some page")))
          "Existing page has correct properties"))

    (testing "built-in properties"
      (is (= 2
             (count (filter #(= :icon (:property %)) @(:ignored-properties import-state))))
          "icon properties are visibly ignored in order to not fail import")

      (is (= {:logseq.task/deadline "Nov 26th, 2022"}
             (readable-properties @conn (find-block-by-content @conn "only deadline")))
          "deadline block has correct journal as property value")

      (is (= {:logseq.task/deadline "Nov 25th, 2022"}
             (readable-properties @conn (find-block-by-content @conn "only scheduled")))
          "scheduled block converted to correct deadline")

      (is (= {:logseq.task/priority "High"}
             (readable-properties @conn (find-block-by-content @conn "high priority")))
          "priority block has correct property")

      (is (= {:logseq.task/status "Doing" :logseq.task/priority "Medium" :block/tags [:logseq.class/task]}
             (readable-properties @conn (find-block-by-content @conn "status test")))
          "status block has correct task properties and class")

      (is (= #{:logseq.task/status :block/tags}
             (set (keys (readable-properties @conn (find-block-by-content @conn "old todo block")))))
          "old task properties are ignored")

      (is (= {:logseq.property/query-sort-by :user.property/prop-num
              :logseq.property/query-properties [:block :page :user.property/prop-string :user.property/prop-num]
              :logseq.property/query-table true}
             (readable-properties @conn (find-block-by-content @conn "{{query (property :prop-string)}}")))
          "query block has correct query properties"))

    (testing "tags without tag options"
      (let [block (find-block-by-content @conn #"Inception")
            tag-page (find-page-by-name @conn "Movie")
            tagged-page (find-page-by-name @conn "Interstellar")]
        (is (string/starts-with? (str (:block/content block)) "Inception [[")
            "tagged block tag converts tag to page ref")
        (is (= [(:db/id tag-page)] (map :db/id (:block/refs block)))
            "tagged block has correct refs")
        (is (and tag-page (not (:block/type tag-page)))
            "tag page is not a class")

        (is (= {:logseq.property/page-tags #{"Movie"}}
               (readable-properties @conn tagged-page))
            "tagged page has tags imported as page-tags property by default")))))

(deftest-async export-file-with-tag-classes-option
  (p/let [file-graph-dir "test/resources/exporter-test-graph"
          files (mapv #(node-path/join file-graph-dir %) ["journals/2024_02_07.md" "pages/Interstellar.md"])
          conn (d/create-conn db-schema/schema-for-db-based-graph)
          _ (d/transact! conn (sqlite-create-graph/build-db-initial-data "{}"))
          _ (import-files-to-db files conn {:tag-classes ["movie"]})]
    (let [block (find-block-by-content @conn #"Inception")
          tag-page (find-page-by-name @conn "Movie")
          another-tag-page (find-page-by-name @conn "p0")]
      (is (= (:block/content block) "Inception")
          "tagged block with configured tag strips tag from content")

      (is (= ["class"] (:block/type tag-page))
          "configured tag page in :tag-classes is a class")
      (is (and another-tag-page (not (:block/type another-tag-page)))
          "unconfigured tag page is not a class")

      (is (= {:block/tags [:user.class/Movie]}
             (readable-properties @conn (find-page-by-name @conn "Interstellar")))
          "tagged page has configured tag imported as a class"))))