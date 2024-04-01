(ns logseq.tasks.db-graph.create-graph-with-properties
  "Script that generates all the permutations of property types and cardinality.
   Also creates a page of queries that exercises most properties
   NOTE: This script is also used in CI to confirm graph creation works"
  (:require [logseq.tasks.db-graph.create-graph :as create-graph]
            [logseq.common.util.date-time :as date-time-util]
            [logseq.common.util.page-ref :as page-ref]
            [logseq.db.frontend.property.type :as db-property-type]
            [clojure.string :as string]
            [clojure.edn :as edn]
            [datascript.core :as d]
            ["path" :as node-path]
            ["os" :as os]
            [babashka.cli :as cli]
            [nbb.core :as nbb]))

(defn- date-journal-title [date]
  (string/lower-case (date-time-util/int->journal-title (date-time-util/date->int date) "MMM do, yyyy")))

(def date-journal-day date-time-util/date->int)

(defn- subtract-days
  [date days]
  (new js/Date (- (.getTime date) (* days 24 60 60 1000))))

(defn- build-closed-values-config
  [{:keys [dates]}]
  {:default-closed
   (mapv #(hash-map :value %
                    :uuid (random-uuid)
                    :icon {:id % :name % :type :emoji})
         ["joy" "sob" "upside_down_face"])
   :url-closed
   (mapv #(hash-map :value %
                    :uuid (random-uuid))
         ["https://logseq.com" "https://docs.logseq.com" "https://github.com/logseq/logseq"])
   :number-closed
   (mapv #(hash-map :value %
                    :uuid (random-uuid))
         [10 42 (rand 100)])
   :page-closed
   (mapv #(hash-map :value [:page %])
         ["page 1" "page 2" "page 3"])
   :date-closed
   (mapv #(hash-map :value [:page (date-journal-title %)])
         dates)})

(defn- create-init-data
  []
  (let [today (new js/Date)
        yesterday (subtract-days today 1)
        two-days-ago (subtract-days today 2)
        closed-values-config (build-closed-values-config {:dates [today yesterday two-days-ago]})
        ;; Stores random closed values for use with queries
        closed-values (atom {})
        random-closed-value #(let [val (-> closed-values-config % rand-nth)]
                               (swap! closed-values assoc % (:value val))
                               (:uuid val))
        random-page-closed-value #(let [val (-> closed-values-config % rand-nth :value)]
                                    (swap! closed-values assoc % (second val))
                                    val)
        get-closed-value #(get @closed-values %)]
    {:pages-and-blocks
     ;; Journals
     [{:page
       {:block/name (date-journal-title today) :block/journal? true :block/journal-day (date-journal-day today)}
       :blocks
       [{:block/content "[[Block Properties]]"}
        {:block/content "[[Block Property Queries]]"}
        {:block/content "[[Page Property Queries]]"}]}
      {:page
       {:block/name (date-journal-title yesterday) :block/journal? true :block/journal-day (date-journal-day yesterday)}}
      {:page
       {:block/name (date-journal-title two-days-ago) :block/journal? true :block/journal-day (date-journal-day two-days-ago)}}

      ;; Block property blocks and queries
      {:page {:block/original-name "Block Properties"}
       :blocks
       [{:block/content "default property block" :properties {:default "haha"}}
        {:block/content "default-closed property block" :properties {:default-closed (random-closed-value :default-closed)}}
        {:block/content "url property block" :properties {:url "https://logseq.com"}}
        {:block/content "url-many property block" :properties {:url-many #{"https://logseq.com" "https://docs.logseq.com"}}}
        {:block/content "url-closed property block" :properties {:url-closed (random-closed-value :url-closed)}}
        {:block/content "checkbox property block" :properties {:checkbox true}}
        {:block/content "number property block" :properties {:number 5}}
        {:block/content "number-many property block" :properties {:number-many #{5 10}}}
        {:block/content "number-closed property block" :properties {:number-closed (random-closed-value :number-closed)}}
        {:block/content "page property block" :properties {:page [:page "page 1"]}}
        {:block/content "page-many property block" :properties {:page-many #{[:page "page 1"] [:page "page 2"]}}}
        {:block/content "page-closed property block" :properties {:page-closed (random-page-closed-value :page-closed)}}
        {:block/content "date property block" :properties {:date [:page (date-journal-title today)]}}
        {:block/content "date-many property block" :properties {:date-many #{[:page (date-journal-title today)]
                                                                             [:page (date-journal-title yesterday)]}}}
        {:block/content "date-closed property block" :properties {:date-closed (random-page-closed-value :date-closed)}}]}
      {:page {:block/original-name "Block Property Queries"}
       :blocks
       [{:block/content "{{query (property :default \"haha\")}}"}
        {:block/content (str "{{query (property :default-closed " (pr-str (get-closed-value :default-closed)) ")}}")}
        {:block/content "{{query (property :url \"https://logseq.com\")}}"}
        {:block/content "{{query (property :url-many \"https://logseq.com\")}}"}
        {:block/content (str "{{query (property :url-closed " (pr-str (get-closed-value :url-closed)) ")}}")}
        {:block/content "{{query (property :checkbox true)}}"}
        {:block/content "{{query (property :number 5)}}"}
        {:block/content "{{query (property :number-many 10)}}"}
        {:block/content (str "{{query (property :number-closed " (pr-str (get-closed-value :number-closed)) ")}}")}
        {:block/content "{{query (property :page [[Page 1]])}}"}
        {:block/content "{{query (property :page-many [[Page 2]])}}"}
        {:block/content (str "{{query (property :page-closed " (page-ref/->page-ref (string/capitalize (get-closed-value :page-closed))) ")}}")}
        {:block/content (str "{{query (property :date " (page-ref/->page-ref (string/capitalize (date-journal-title today))) ")}}")}
        {:block/content (str "{{query (property :date-many " (page-ref/->page-ref (string/capitalize (date-journal-title yesterday))) ")}}")}
        {:block/content (str "{{query (property :date-closed " (page-ref/->page-ref (string/capitalize (get-closed-value :date-closed))) ")}}")}]}

      ;; Page property pages and queries
      {:page {:block/name "default page" :properties {:default "yolo"}}}
      {:page {:block/name "default-closed page" :properties {:default-closed (random-closed-value :default-closed)}}}
      {:page {:block/name "url page" :properties {:url "https://logseq.com"}}}
      {:page {:block/name "url-many page" :properties {:url-many #{"https://logseq.com" "https://docs.logseq.com"}}}}
      {:page {:block/name "url-closed page" :properties {:url-closed (random-closed-value :url-closed)}}}
      {:page {:block/name "checkbox page" :properties {:checkbox true}}}
      {:page {:block/name "number page" :properties {:number 5}}}
      {:page {:block/name "number-many page" :properties {:number-many #{5 10}}}}
      {:page {:block/name "number-closed page" :properties {:number-closed (random-closed-value :number-closed)}}}
      {:page {:block/name "page page" :properties {:page [:page "page 1"]}}}
      {:page {:block/name "page-many page" :properties {:page-many #{[:page "page 1"] [:page "page 2"]}}}}
      {:page {:block/name "page-closed page" :properties {:page-closed (random-page-closed-value :page-closed)}}}
      {:page {:block/name "date page" :properties {:date [:page (date-journal-title today)]}}}
      {:page {:block/name "date-many page" :properties {:date-many #{[:page (date-journal-title today)]
                                                                     [:page (date-journal-title yesterday)]}}}}
      {:page {:block/name "date-closed page" :properties {:date-closed (random-page-closed-value :date-closed)}}}
      {:page {:block/original-name "Page Property Queries"}
       :blocks
       [{:block/content "{{query (page-property :default \"yolo\")}}"}
        {:block/content (str "{{query (page-property :default-closed " (pr-str (get-closed-value :default-closed)) ")}}")}
        {:block/content "{{query (page-property :url \"https://logseq.com\")}}"}
        {:block/content "{{query (page-property :url-many \"https://logseq.com\")}}"}
        {:block/content (str "{{query (page-property :url-closed " (pr-str (get-closed-value :url-closed)) ")}}")}
        {:block/content "{{query (page-property :checkbox true)}}"}
        {:block/content "{{query (page-property :number 5)}}"}
        {:block/content "{{query (page-property :number-many 10)}}"}
        {:block/content (str "{{query (page-property :number-closed " (pr-str (get-closed-value :number-closed)) ")}}")}
        {:block/content "{{query (page-property :page [[Page 1]])}}"}
        {:block/content "{{query (page-property :page-many [[Page 2]])}}"}
        {:block/content (str "{{query (page-property :page-closed " (page-ref/->page-ref (string/capitalize (get-closed-value :page-closed))) ")}}")}
        {:block/content (str "{{query (page-property :date " (page-ref/->page-ref (string/capitalize (date-journal-title today))) ")}}")}
        {:block/content (str "{{query (page-property :date-many " (page-ref/->page-ref (string/capitalize (date-journal-title yesterday))) ")}}")}
        {:block/content (str "{{query (page-property :date-closed " (page-ref/->page-ref (string/capitalize (get-closed-value :date-closed))) ")}}")}]}

      ;; Property values
      {:page {:block/name "page 1"}
       :blocks
       [{:block/content "yee"}
        {:block/content "haw"}]}
      {:page {:block/name "page 2"}}
      {:page {:block/name "page 3"}}]

     ;; Properties
     :properties
     (->> [:default :url :checkbox :number :page :date]
          (mapcat #(cond-> [[% {:block/schema {:type %}}]]
                     (db-property-type/property-type-allows-schema-attribute? % :cardinality)
                     (conj [(keyword (str (name %) "-many")) {:block/schema {:type % :cardinality :many}}])))
          (into (mapv #(vector (keyword (str (name %) "-closed"))
                               {:closed-values (closed-values-config (keyword (str (name %) "-closed")))
                                :block/schema {:type %}})
                      [:default :url :number :page :date]))
          (into {}))}))

(def spec
  "Options spec"
  {:help {:alias :h
          :desc "Print help"}
   :config {:alias :c
            :coerce edn/read-string
            :desc "EDN map to add to config.edn"}})

(defn -main [args]
  (let [graph-dir (first args)
        options (cli/parse-opts args {:spec spec})
        _ (when (or (nil? graph-dir) (:help options))
            (println (str "Usage: $0 GRAPH-NAME [OPTIONS]\nOptions:\n"
                          (cli/format-opts {:spec spec})))
            (js/process.exit 1))
        [dir db-name] (if (string/includes? graph-dir "/")
                        ((juxt node-path/dirname node-path/basename) graph-dir)
                        [(node-path/join (os/homedir) "logseq" "graphs") graph-dir])
        conn (create-graph/init-conn dir db-name {:additional-config (:config options)})
        blocks-tx (create-graph/create-blocks-tx
                   @conn
                   (create-init-data)
                   {:property-uuids {:icon (:block/uuid (d/entity @conn :logseq.property/icon))}})]
    (println "Generating" (count (filter :block/name blocks-tx)) "pages and"
             (count (filter :block/content blocks-tx)) "blocks ...")
    (d/transact! conn blocks-tx)
    (println "Created graph" (str db-name " with " (count (d/datoms @conn :eavt)) " datoms!"))))

(when (= nbb/*file* (:file (meta #'-main)))
  (-main *command-line-args*))
