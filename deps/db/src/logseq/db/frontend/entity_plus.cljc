(ns logseq.db.frontend.entity-plus
  "Add map ops such as assoc/dissoc to datascript Entity.

   NOTE: This doesn't work for nbb/sci yet because of https://github.com/babashka/sci/issues/639"
  ;; Disable clj linters since we don't support clj
  #?(:clj {:clj-kondo/config {:linters {:unresolved-namespace {:level :off}
                                        :unresolved-symbol {:level :off}}}})
  (:require [cljs.core]
            #?(:org.babashka/nbb [datascript.db])
            [datascript.impl.entity :as entity :refer [Entity]]
            [logseq.db.frontend.content :as db-content]
            [datascript.core :as d]
            [logseq.db.frontend.property :as db-property]))

(defn db-based-graph?
  "Whether the current graph is db-only"
  [db]
  (= "db" (:kv/value (d/entity db :logseq.kv/db-type))))

(def lookup-entity @#'entity/lookup-entity)
(defn lookup-kv-then-entity
  ([e k] (lookup-kv-then-entity e k nil))
  ([^Entity e k default-value]
   (when k
     (case k
       :block/raw-title
       (lookup-entity e :block/title default-value)

       :block/properties
       (let [db (.-db e)]
         (if (db-based-graph? db)
           (lookup-entity e :block/properties
                          (->> (into {} e)
                               (filter (fn [[k _]] (db-property/property? k)))
                               (into {})))
           (lookup-entity e :block/properties nil)))

       :block/title
       (or
        (get (.-kv e) k)
        (let [result (lookup-entity e k default-value)]
          (or
           (if (string? result)
             (db-content/special-id-ref->page-ref result (:block/refs e))
             result)
           default-value)))

       :block/_parent
       (->> (lookup-entity e k default-value)
            (remove (fn [e] (or (:logseq.property/created-from-property e)
                                (:block/closed-value-property e))))
            seq)

       :block/_raw-parent
       (lookup-entity e :block/_parent default-value)

       :property/closed-values
       (->> (lookup-entity e :block/_closed-value-property default-value)
            (sort-by :block/order))

       (or (get (.-kv e) k)
           (lookup-entity e k default-value))))))

#?(:org.babashka/nbb
   nil
   :default
   (extend-type Entity
     cljs.core/IEncodeJS
     (-clj->js [_this] nil)                 ; avoid `clj->js` overhead when entity was passed to rum components

     IAssociative
     (-assoc [this k v]
       (assert (keyword? k) "attribute must be keyword")
       (set! (.-kv this) (assoc (.-kv this) k v))
       this)
     (-contains-key? [e k] (not= ::nf (lookup-kv-then-entity e k ::nf)))

     IMap
     (-dissoc [this k]
       (assert (keyword? k) (str "attribute must be keyword: " k))
       (set! (.-kv this) (dissoc (.-kv this) k))
       this)

     ICollection
     (-conj [this entry]
       (if (vector? entry)
         (let [[k v] entry]
           (-assoc this k v))
         (reduce (fn [this [k v]]
                   (-assoc this k v)) this entry)))

     ILookup
     (-lookup
       ([this attr] (lookup-kv-then-entity this attr))
       ([this attr not-found] (lookup-kv-then-entity this attr not-found)))))
