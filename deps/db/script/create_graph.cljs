(ns create-graph
  "A script that creates a DB graph given a sqlite.build EDN file"
  (:require ["fs" :as fs]
            ["os" :as os]
            ["path" :as node-path]
            #_:clj-kondo/ignore
            [babashka.cli :as cli]
            [clojure.edn :as edn]
            [clojure.string :as string]
            [datascript.core :as d]
            [logseq.outliner.cli :as outliner-cli]
            [nbb.classpath :as cp]
            [nbb.core :as nbb]
            [validate-db]))

(defn- resolve-path
  "If relative path, resolve with $ORIGINAL_PWD"
  [path]
  (if (node-path/isAbsolute path)
    path
    (node-path/join (or js/process.env.ORIGINAL_PWD ".") path)))

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
   :validate {:alias :v
              :desc "Validate db after creation"}})

(defn -main [args]
  (let [{options :opts args' :args} (cli/parse-args args {:spec spec})
        [graph-dir edn-path] args'
        _ (when (or (nil? graph-dir) (nil? edn-path) (:help options))
            (println (str "Usage: $0 GRAPH-NAME EDN-PATH [OPTIONS]\nOptions:\n"
                          (cli/format-opts {:spec spec})))
            (js/process.exit 1))
        [dir db-name] (get-dir-and-db-name graph-dir)
        sqlite-build-edn (merge {:auto-create-ontology? true}
                                (-> (resolve-path edn-path) fs/readFileSync str edn/read-string))
        conn (outliner-cli/init-conn dir db-name {:classpath (cp/get-classpath) :import-type :cli/create-graph})
        {:keys [init-tx block-props-tx] :as _txs} (outliner-cli/build-blocks-tx sqlite-build-edn)]
    (println "Generating" (count (filter :block/name init-tx)) "pages and"
             (count (filter :block/title init-tx)) "blocks ...")
    ;; (cljs.pprint/pprint _txs)
    (d/transact! conn init-tx)
    (d/transact! conn block-props-tx)
    (println "Created graph" (str db-name "!"))
    (when (:validate options)
      (validate-db/validate-db @conn db-name {:group-errors true :closed-maps true :humanize true}))))

(when (= nbb/*file* (nbb/invoked-file))
  (-main *command-line-args*))
