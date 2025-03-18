(ns query
  "A script that queries any db graph from the commandline e.g.

  $ yarn -s nbb-logseq script/query.cljs db-name '[:find (pull ?b [:block/name :block/title]) :where [?b :block/created-at]]'"
  (:require ["child_process" :as child-process]
            ["os" :as os]
            ["path" :as node-path]
            [babashka.cli :as cli]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as string]
            [datascript.core :as d]
            [logseq.db.frontend.rules :as rules]
            [logseq.db.sqlite.cli :as sqlite-cli]
            [nbb.core :as nbb]))

(defn- sh
  "Run shell cmd synchronously and print to inherited streams by default. Aims
  to be similar to babashka.tasks/shell"
  [cmd opts]
  (child-process/spawnSync (first cmd)
                           (clj->js (rest cmd))
                           (clj->js (merge {:stdio "inherit"} opts))))

(defn- get-dir-and-db-name
  "Gets dir and db name for use with open-db! Works for relative and absolute paths and
   defaults to ~/logseq/graphs/ when no '/' present in name"
  [graph-dir]
  (if (string/includes? graph-dir "/")
    (let [resolve-path' #(if (node-path/isAbsolute %) %
                             ;; $ORIGINAL_PWD used by bb tasks to correct current dir
                             (node-path/join (or js/process.env.ORIGINAL_PWD ".") %))]
      ((juxt node-path/dirname node-path/basename) (resolve-path' graph-dir)))
    [(node-path/join (os/homedir) "logseq" "graphs") graph-dir]))

(def spec
  "Options spec"
  {:help {:alias :h
          :desc "Print help"}
   :verbose {:alias :v
             :desc "Print more info"}
   :raw {:alias :r
         :desc "Print results plainly. Useful when piped to bb"}
   :additional-graphs {:alias :a
                       :coerce []
                       :desc "Additional graphs to query"}
   :entity {:alias :e
            :coerce []
            :desc "Lookup entities instead of query"}})

(defn query-graph [graph-dir args'' options]
  (let [[dir db-name] (get-dir-and-db-name graph-dir)
        conn (sqlite-cli/open-db! dir db-name)
        results (if (:entity options)
                  (map #(when-let [ent (d/entity @conn
                                                 (if (string? %) (edn/read-string %) %))]
                          (cond-> (into {:db/id (:db/id ent)} ent)
                            (seq (:block/properties ent))
                            (update :block/properties (fn [props] (map (fn [m] (into {} m)) props)))))
                       (:entity options))
                  ;; assumes no :in are in queries
                  (let [query (into (edn/read-string (first args'')) [:in '$ '%])
                        res (d/q query @conn (rules/extract-rules rules/db-query-dsl-rules))]
                    ;; Remove nesting for most queries which just have one :find binding
                    (if (= 1 (count (first res))) (mapv first res) res)))]
    (when (:verbose options) (println "DB contains" (count (d/datoms @conn :eavt)) "datoms"))
    (if (:raw options)
      (prn results)
      (if (zero? (.-status (child-process/spawnSync "which" #js ["puget"])))
        (sh ["puget"] {:input (pr-str results) :stdio ["pipe" "inherit" "inherit"]})
        (pprint/pprint results)))))

(defn -main [args]
  (let [{options :opts args' :args} (cli/parse-args args {:spec spec})
        [graph-dir & args''] args'
        _ (when (or (nil? graph-dir) (:help options))
            (println (str "Usage: $0 GRAPH-NAME [& ARGS] [OPTIONS]\nOptions:\n"
                          (cli/format-opts {:spec spec})))
            (js/process.exit 1))
        graph-dirs (cond-> [graph-dir]
                     (:additional-graphs options)
                     (into (:additional-graphs options)))]
    (doseq [graph-dir graph-dirs]
      (query-graph graph-dir args'' options))))

(when (= nbb/*file* (nbb/invoked-file))
  (-main *command-line-args*))
