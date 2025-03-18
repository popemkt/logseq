(ns transact
  "This script generically runs transactions against the queried blocks"
  (:require ["os" :as os]
            ["path" :as node-path]
            [clojure.edn :as edn]
            [clojure.string :as string]
            [datascript.core :as d]
            [logseq.db.frontend.rules :as rules]
            [logseq.db.sqlite.cli :as sqlite-cli]
            [logseq.outliner.db-pipeline :as db-pipeline]
            [nbb.core :as nbb]))

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

(defn -main [args]
  (when (< (count args) 3)
    (println "Usage: $0 GRAPH-DIR QUERY TRANSACT-FN")
    (js/process.exit 1))
  (let [[graph-dir query* transact-fn*] args
        dry-run? (contains? (set args) "-n")
        [dir db-name] (get-dir-and-db-name graph-dir)
        conn (sqlite-cli/open-db! dir db-name)
        ;; find blocks to update
        query (into (edn/read-string query*) [:in '$ '%]) ;; assumes no :in are in queries
        transact-fn (edn/read-string transact-fn*)
        blocks-to-update (mapv first (d/q query @conn (rules/extract-rules rules/db-query-dsl-rules)))
        ;; TODO: Use sci eval when it's available in nbb-logseq
        update-tx (mapv (fn [id] (eval (list transact-fn id)))
                        blocks-to-update)]
    (if dry-run?
      (do (println "Would update" (count blocks-to-update) "blocks with the following tx:")
          (prn update-tx)
          (println "With the following blocks updated:")
          (prn (map #(select-keys (d/entity @conn %) [:block/name :block/title]) blocks-to-update)))
      (do
        (db-pipeline/add-listener conn)
        (d/transact! conn update-tx)
        (println "Updated" (count update-tx) "block(s) for graph" (str db-name "!"))))))

(when (= nbb/*file* (nbb/invoked-file))
  (-main *command-line-args*))
