(ns logseq.graph-parser.exporter
  "Exports a file graph to DB graph. Used by the File to DB graph importer and
  by nbb-logseq CLIs"
  (:require [clojure.set :as set]
            [clojure.string :as string]
            [clojure.edn :as edn]
            [datascript.core :as d]
            [logseq.graph-parser.extract :as extract]
            [logseq.common.path :as path]
            [logseq.common.util :as common-util]
            [logseq.common.config :as common-config]
            [logseq.db.frontend.content :as db-content]
            [logseq.db.frontend.property :as db-property]
            [logseq.db.frontend.property.type :as db-property-type]
            [logseq.common.util.macro :as macro-util]
            [logseq.common.util.date-time :as date-time-util]
            [logseq.db.sqlite.util :as sqlite-util]
            [logseq.db :as ldb]
            [logseq.db.frontend.rules :as rules]
            [logseq.db.frontend.class :as db-class]
            [logseq.common.util.page-ref :as page-ref]
            [promesa.core :as p]))

(defn- get-pid
  "Get a property's id (name or uuid) given its name. For db graphs"
  [db property-name]
  (:block/uuid (d/entity db [:block/name (common-util/page-name-sanity-lc (name property-name))])))

(defn- add-missing-timestamps
  "Add updated-at or created-at timestamps if they doesn't exist"
  [block]
  (let [updated-at (common-util/time-ms)
        block (cond-> block
                (nil? (:block/updated-at block))
                (assoc :block/updated-at updated-at)
                (nil? (:block/created-at block))
                (assoc :block/created-at updated-at))]
    block))

(defn- convert-tag-to-class
  "Converts a tag block with class or returns nil if this tag should be removed
   because it has been moved"
  [tag-block tag-classes]
  (if-let [new-class (:block.temp/new-class tag-block)]
    (sqlite-util/build-new-class
     {:block/original-name new-class
      :block/name (common-util/page-name-sanity-lc new-class)})
    (when (contains? tag-classes (:block/name tag-block))
      (-> tag-block
          add-missing-timestamps
          ;; don't use build-new-class b/c of timestamps
          (merge {:block/journal? false
                  :block/format :markdown
                  :block/type "class"})))))

(defn- update-page-tags
  [block tag-classes names-uuids page-tags-uuid]
  (if (seq (:block/tags block))
    (let [page-tags (->> (:block/tags block)
                         (remove #(or (:block.temp/new-class %) (contains? tag-classes (:block/name %))))
                         (map #(or (get names-uuids (:block/name %))
                                   (throw (ex-info (str "No uuid found for tag " (pr-str (:block/name %)))
                                                   {:tag %}))))
                         set)]
      (cond-> block
        true
        (update :block/tags
                (fn [tags]
                  (keep #(convert-tag-to-class % tag-classes) tags)))
        (seq page-tags)
        (update :block/properties merge {page-tags-uuid page-tags})))
    block))

(defn- add-uuid-to-page-map [m page-names-to-uuids]
  (assoc m
         :block/uuid
         (or (get page-names-to-uuids (:block/name m))
             (throw (ex-info (str "No uuid found for page " (pr-str (:block/name m)))
                             {:page m})))))

(defn- content-without-tags-ignore-case
  "Ignore case because tags in content can have any case and still have a valid ref"
  [content tags]
  (->
   (reduce
    (fn [content tag]
      (-> content
          (common-util/replace-ignore-case (str "#" tag) "")
          (common-util/replace-ignore-case (str "#" page-ref/left-brackets tag page-ref/right-brackets) "")))
    content
    tags)
   (string/trim)))

(defn- update-block-tags
  [block tag-classes page-names-to-uuids]
  (if (seq (:block/tags block))
    (let [original-tags (remove :block.temp/new-class (:block/tags block))]
      (-> block
          (update :block/content
                  content-without-tags-ignore-case
                  (->> original-tags
                       (filter #(tag-classes (:block/name %)))
                       (map :block/original-name)))
          (update :block/content
                  db-content/replace-tags-with-page-refs
                  (->> original-tags
                       (remove #(tag-classes (:block/name %)))
                       (map #(add-uuid-to-page-map % page-names-to-uuids))))
          (update :block/tags
                  (fn [tags]
                    (keep #(convert-tag-to-class % tag-classes) tags)))))
    block))

(defn- update-block-marker
  "If a block has a marker, convert it to a task object"
  [block db {:keys [log-fn]}]
  (if-let [marker (:block/marker block)]
    (let [old-to-new {"TODO" :logseq.task/status.todo
                      "LATER" :logseq.task/status.todo
                      "IN-PROGRESS" :logseq.task/status.doing
                      "NOW" :logseq.task/status.doing
                      "DOING" :logseq.task/status.doing
                      "DONE" :logseq.task/status.done
                      "WAIT" :logseq.task/status.backlog
                      "WAITING" :logseq.task/status.backlog
                      "CANCELED" :logseq.task/status.canceled
                      "CANCELLED" :logseq.task/status.canceled}
          status-prop (:block/uuid (d/entity db :logseq.task/status))
          status-ident (or (old-to-new marker)
                           (do
                             (log-fn :invalid-todo (str (pr-str marker) " is not a valid marker so setting it to TODO"))
                             :logseq.task/status.todo))
          status-value (:block/uuid (d/entity db status-ident))]
      (-> block
          (update :block/properties assoc status-prop status-value)
          (update :block/content string/replace-first (re-pattern (str marker "\\s*")) "")
          (update :block/tags (fnil conj []) :logseq.class/task)
          (update :block/refs (fn [refs]
                                (into (remove #(= marker (:block/original-name %)) refs)
                                      [:logseq.class/task :logseq.task/status status-ident])))
          (update :block/path-refs (fn [refs]
                                     (into (remove #(= marker (:block/original-name %)) refs)
                                           [:logseq.class/task :logseq.task/status status-ident])))
          (dissoc :block/marker)))
    block))

(defn- update-block-priority
  [block db {:keys [log-fn]}]
  (if-let [priority (:block/priority block)]
    (let [old-to-new {"A" :logseq.task/priority.high
                      "B" :logseq.task/priority.medium
                      "C" :logseq.task/priority.low}
          priority-prop (:block/uuid (d/entity db :logseq.task/priority))
          priority-ident (or (old-to-new priority)
                             (do
                               (log-fn :invalid-priority (str (pr-str priority) " is not a valid priority so setting it to low"))
                               :logseq.task/priority.low))
          priority-value (:block/uuid (d/entity db priority-ident))]
      (-> block
          (update :block/properties assoc priority-prop priority-value)
          (update :block/content string/replace-first (re-pattern (str "\\[#" priority "\\]" "\\s*")) "")
          (update :block/refs (fn [refs]
                                (into (remove #(= priority (:block/original-name %)) refs)
                                      [:logseq.task/priority priority-ident])))
          (update :block/path-refs (fn [refs]
                                     (into (remove #(= priority (:block/original-name %)) refs)
                                           [:logseq.task/priority priority-ident])))
          (dissoc :block/priority)))
    block))

(defn- update-block-deadline
  ":block/content doesn't contain DEADLINE.* text so unable to detect timestamp
  or repeater usage and notify user that they aren't supported"
  [block db {:keys [user-config]}]
  (if-let [deadline (:block/deadline block)]
    (let [deadline-prop (:block/uuid (d/entity db :logseq.task/deadline))
          deadline-page (or (ffirst (d/q '[:find (pull ?b [:block/uuid])
                                           :in $ ?journal-day
                                           :where [?b :block/journal-day ?journal-day]]
                                         db deadline))
                            ;; FIXME: Register new pages so that two different refs to same new page
                            ;; don't create different uuids and thus an invalid page
                            (assoc (sqlite-util/build-new-page
                                    (date-time-util/int->journal-title deadline (common-config/get-date-formatter user-config)))
                                   :block/journal? true
                                   :block/journal-day deadline
                                   :block/format :markdown))]
      (-> block
          (update :block/properties assoc deadline-prop (:block/uuid deadline-page))
          (update :block/refs (fnil into []) [:logseq.task/deadline deadline-page])
          (update :block/path-refs (fnil into []) [:logseq.task/deadline deadline-page])
          (dissoc :block/deadline)))
    block))

(defn- update-block-scheduled
  "Should have same implementation as update-block-deadline"
  [block db {:keys [user-config]}]
  (if-let [scheduled (:block/scheduled block)]
    (let [scheduled-prop (:block/uuid (d/entity db :logseq.task/scheduled))
          scheduled-page (or (ffirst (d/q '[:find (pull ?b [:block/uuid])
                                           :in $ ?journal-day
                                           :where [?b :block/journal-day ?journal-day]]
                                         db scheduled))
                            (assoc (sqlite-util/build-new-page
                                    (date-time-util/int->journal-title scheduled (common-config/get-date-formatter user-config)))
                                   :block/journal? true
                                   :block/journal-day scheduled
                                   :block/format :markdown))]
      (-> block
          (update :block/properties assoc scheduled-prop (:block/uuid scheduled-page))
          (update :block/refs (fnil into []) [:logseq.task/scheduled scheduled-page])
          (update :block/path-refs (fnil into []) [:logseq.task/scheduled scheduled-page])
          (dissoc :block/scheduled)))
    block))

(defn- text-with-refs?
  "Detects if a property value has text with refs e.g. `#Logseq is #awesome`
  instead of `#Logseq #awesome`. If so the property type is :default instead of :page"
  [vals val-text]
  (let [replace-regex (re-pattern
                       ;; Regex removes all characters of a tag or page-ref
                       ;; so that only ref chars are left
                       (str "([#[])"
                            "("
                            ;; Sorts ref names in descending order so that longer names
                            ;; come first. Order matters since (foo-bar|foo) correctly replaces
                            ;; "foo-bar" whereas (foo|foo-bar) does not
                            (->> vals (sort >) (map common-util/escape-regex-chars) (string/join "|"))
                            ")"))
        remaining-text (string/replace val-text replace-regex "$1")
        non-ref-char (some #(if (or (string/blank? %) (#{"[" "]" "," "#"} %))
                              false
                              %)
                           remaining-text)]
    (some? non-ref-char)))

(defn- infer-property-schema-and-get-property-change
  "Infers a property's schema from the given _user_ property value and adds new ones to
  the property-schemas atom. If a property's :type changes, returns a map of
  the schema attribute changed and how it changed e.g. `{:type {:from :default :to :url}}`"
  [prop-val prop prop-val-text refs property-schemas macros]
  ;; Explicitly fail an unexpected case rather cause silent downstream failures
  (when (and (coll? prop-val) (not (every? string? prop-val)))
    (throw (ex-info "Import cannot infer schema of unknown property value"
                    {:value prop-val :property prop})))
  (let [prop-type (cond (and (coll? prop-val)
                             (seq prop-val)
                             (set/subset? prop-val
                                          (set (keep #(when (:block/journal? %) (:block/original-name %)) refs))))
                        :date
                        (and (coll? prop-val) (seq prop-val) (text-with-refs? prop-val prop-val-text))
                        :default
                        :else
                        (db-property-type/infer-property-type-from-value
                         (macro-util/expand-value-if-macro prop-val macros)))
        prev-type (get-in @property-schemas [prop :type])]
    (when-not prev-type
      (let [schema (cond-> {:type prop-type}
                     (#{:page :date} prop-type)
                     ;; Assume :many for now as detecting that detecting property values across files are consistent
                     ;; isn't possible yet
                     (assoc :cardinality :many))]
        (swap! property-schemas assoc prop schema)))
    (when (and prev-type (not= prev-type prop-type))
      {:type {:from prev-type :to prop-type}})))

(defn- update-built-in-property-values
  [props db ignored-properties {:block/keys [content name]}]
  (->> props
       (keep (fn [[prop val]]
               (if (= :icon prop)
                 (do (swap! ignored-properties
                            conj
                            {:property prop :value val :location (if name {:page name} {:block content})})
                     nil)
                 [prop
                  (case prop
                    :query-properties
                    (try
                      (mapv #(if (#{:page :block :created-at :updated-at} %) % (get-pid db %))
                            (edn/read-string val))
                      (catch :default e
                        (js/console.error "Translating query properties failed with:" e)
                        []))
                    :query-sort-by
                    (if (#{:page :block :created-at :updated-at} val) val (get-pid db val))
                    (:logseq.color :logseq.table.headers :logseq.table.hover)
                    (:block/uuid (db-property/get-closed-value-entity-by-name db prop val))
                    :logseq.table.version
                    (parse-long val)
                    :filters
                    (try (edn/read-string val)
                         (catch :default e
                           (js/console.error "Translating filters failed with:" e)
                           {}))
                    val)])))
       (into {})))

(defn- handle-changed-property
  "Handles a property's schema changing across blocks. Handling usually means
  converting a property value to a new changed value or nil if the property is
  to be ignored. Sometimes handling a property change results in changing a
  property's previous usages instead of its current value e.g. when changing to
  a :default type. This is done by adding an entry to upstream-properties and
  building the additional tx to ensure this happens"
  [val prop prop-name->uuid properties-text-values
   {:keys [ignored-properties property-schemas]}
   {:keys [property-changes log-fn upstream-properties]}]
  (let [type-change (get-in property-changes [prop :type])]
    (cond
      ;; ignore :to as any property value gets stringified
      (= :default (:from type-change))
      (or (get properties-text-values prop) (str val))
      (= {:from :page :to :date} type-change)
      ;; treat it the same as a :page
      (set (map (comp prop-name->uuid common-util/page-name-sanity-lc) val))
      ;; Unlike the other property changes, this one changes all the previous values of a property
      ;; in order to accommodate the change
      (= :default (:to type-change))
      (if (get @upstream-properties prop)
        ;; Ignore more than one property schema change per file to keep it simple
        (do
          (log-fn :prop-to-change-ignored {:property prop :val val :change type-change})
          (swap! ignored-properties conj {:property prop :value val :schema (get property-changes prop)})
          nil)
        (do
          (swap! upstream-properties assoc prop {:schema {:type :default}})
          (swap! property-schemas assoc prop {:type :default})
          (get properties-text-values prop)))
      :else
      (do
        (log-fn :prop-change-ignored {:property prop :val val :change type-change})
        (swap! ignored-properties conj {:property prop :value val :schema (get property-changes prop)})
        nil))))

(defn- update-user-property-values
  [props prop-name->uuid properties-text-values
   {:keys [property-schemas] :as import-state}
   {:keys [property-changes] :as options}]
  (->> props
       (keep (fn [[prop val]]
               (if (get-in property-changes [prop :type])
                 (when-let [val' (handle-changed-property val prop prop-name->uuid properties-text-values import-state options)]
                   [prop val'])
                 [prop
                  (if (set? val)
                    (if (= :default (get-in @property-schemas [prop :type]))
                      (get properties-text-values prop)
                      ;; assume for now a ref's :block/name can always be translated by lc helper
                      (set (map (comp prop-name->uuid common-util/page-name-sanity-lc) val)))
                    val)])))
       (into {})))

(defn- cached-prop-name->uuid [db page-names-to-uuids k]
  (or (get page-names-to-uuids (name k))
      (get-pid db k)
      (throw (ex-info (str "No uuid found for page " (pr-str k))
                      {:page k}))))

(def built-in-property-names
  "Set of all built-in property names as keywords. Using in-memory property
  names because these are legacy names already in a user's file graph"
  (->> db-property/built-in-properties
       vals
       (map :name)
       set))

(defn- update-properties
  "Updates block property names and values"
  [props db page-names-to-uuids
   {:block/keys [properties-text-values] :as block}
   {:keys [whiteboard? import-state] :as options}]
  (let [prop-name->uuid (if whiteboard?
                          (fn prop-name->uuid [k]
                            (or (get-pid db k)
                                (throw (ex-info (str "No uuid found for page " (pr-str k))
                                                {:page k}))))
                          (fn prop-name->uuid [k]
                            (cached-prop-name->uuid db page-names-to-uuids k)))
        user-properties (apply dissoc props built-in-property-names)]
    (when (seq user-properties)
      (swap! (:block-properties-text-values import-state)
             assoc
             ;; For pages, valid uuid is in page-names-to-uuids, not in block
             (if (:block/name block) (get page-names-to-uuids (:block/name block)) (:block/uuid block))
             properties-text-values))
    ;; TODO: Add import support for :template. Ignore for now as they cause invalid property types
    (if (contains? props :template)
      {}
      (-> (update-built-in-property-values
           (select-keys props built-in-property-names)
           db
           (:ignored-properties import-state)
           (select-keys block [:block/name :block/content]))
          (merge (update-user-property-values user-properties prop-name->uuid properties-text-values import-state options))
          (update-keys prop-name->uuid)))))

(def ignored-built-in-properties
  "Ignore built-in properties that are already imported or not supported in db graphs"
  ;; Already imported via a datascript attribute i.e. have :attribute on property config
  [:tags :alias :collapsed
   ;; Not supported as they have been ignored for a long time and cause invalid built-in pages
   :now :later :doing :done :canceled :cancelled :in-progress :todo :wait :waiting
   ;; deprecated in db graphs
   :macros :logseq.query/nlp-date])

(defn- pre-update-properties
  "Updates page and block properties before their property types are inferred"
  [properties class-related-properties]
  (let [dissoced-props (concat ignored-built-in-properties
                               ;; TODO: Add import support for these dissoced built-in properties
                               [:title :id :created-at :updated-at
                                :card-last-interval :card-repeats :card-last-reviewed :card-next-schedule
                                :card-ease-factor :card-last-score]
                               class-related-properties)]
    (->> (apply dissoc properties dissoced-props)
         (keep (fn [[prop val]]
                 (if (not (contains? built-in-property-names prop))
                  ;; only update user properties
                   (if (string? val)
                    ;; Ignore blank values as they were usually generated by templates
                     (when-not (string/blank? val)
                       [prop
                       ;; handle float strings b/c graph-parser doesn't
                        (or (parse-double val) val)])
                     [prop val])
                   [prop val])))
         (into {}))))

(defn- handle-page-and-block-properties
  "Handles modifying :block/properties, updating classes from property-classes
  and removing any deprecated property related attributes. Before updating most
  :block/properties, their property schemas are inferred as that can affect how
  a property is updated. Only infers property schemas on user properties as
  built-in ones must not change"
  [{:block/keys [properties] :as block} db page-names-to-uuids refs
   {:keys [import-state macros property-classes property-parent-classes] :as options}]
  (-> (if (seq properties)
        (let [classes-from-properties (->> (select-keys properties property-classes)
                                           (mapcat (fn [[_k v]] (if (coll? v) v [v])))
                                           distinct)
              properties' (pre-update-properties properties (into property-classes property-parent-classes))
              properties-to-infer (if (:template properties')
                                    ;; Ignore template properties as they don't consistently have representative property values
                                    {}
                                    (apply dissoc properties' built-in-property-names))
              property-changes
              (->> properties-to-infer
                   (keep (fn [[prop val]]
                           (when-let [property-change
                                      (infer-property-schema-and-get-property-change val prop (get (:block/properties-text-values block) prop) refs (:property-schemas import-state) macros)]
                             [prop property-change])))
                   (into {}))
              ;; _ (when (seq property-changes) (prn :prop-changes property-changes))
              options' (assoc options :property-changes property-changes)]
          (cond-> (assoc-in block [:block/properties]
                            (update-properties properties' db page-names-to-uuids
                                               (select-keys block [:block/properties-text-values :block/name :block/content :block/uuid])
                                               options'))
            (seq classes-from-properties)
            ;; Add a map of {:block.temp/new-class TAG} to be processed later
            (update :block/tags
                    (fnil into [])
                    (map #(hash-map :block.temp/new-class %
                                    :block/uuid (or (get-pid db %) (d/squuid)))
                         classes-from-properties))))
        block)
      (dissoc :block/properties-text-values :block/properties-order :block/invalid-properties)))

(defn- handle-page-properties
  [{:block/keys [properties] :as block} db page-names-to-uuids refs
   {:keys [property-parent-classes log-fn] :as options}]
  (-> (if (seq properties)
        (let [parent-classes-from-properties (->> (select-keys properties property-parent-classes)
                                                  (mapcat (fn [[_k v]] (if (coll? v) v [v])))
                                                  distinct)]
          (cond-> block
            (seq parent-classes-from-properties)
            (assoc :block/type "class")
            (seq parent-classes-from-properties)
            (assoc :class/parent
                   (let [new-class (first parent-classes-from-properties)]
                     (when (> (count parent-classes-from-properties) 1)
                       (log-fn :skipped-parent-classes "Only one parent class is allowed so skipped ones after the first one" :classes parent-classes-from-properties))
                     (sqlite-util/build-new-class
                      {:block/original-name new-class
                       :block/uuid (or (get-pid db new-class) (d/squuid))
                       :block/name (common-util/page-name-sanity-lc new-class)})))))
        block)
      (handle-page-and-block-properties db page-names-to-uuids refs options)))

(defn- handle-block-properties
  "Does everything page properties does and updates a couple of block specific attributes"
  [block db page-names-to-uuids refs {:keys [property-classes] :as options}]
  (cond-> (handle-page-and-block-properties block db page-names-to-uuids refs options)
    (and (seq property-classes) (seq (:block/refs block)))
    ;; remove unused, nonexistent property page
    (update :block/refs (fn [refs] (remove #(property-classes (keyword (:block/name %))) refs)))
    (and (seq property-classes) (seq (:block/path-refs block)))
    ;; remove unused, nonexistent property page
    (update :block/path-refs (fn [refs] (remove #(property-classes (keyword (:block/name %))) refs)))))

(defn- update-block-refs
  "Updates the attributes of a block ref as this is where a new page is defined. Also
   updates block content effected by refs"
  [block page-names-to-uuids old-property-schemas {:keys [whiteboard? import-state]}]
  (let [ref-to-ignore? (if whiteboard?
                         #(and (map? %) (:block/uuid %))
                         #(and (vector? %) (= :block/uuid (first %))))
        new-property-schemas (apply dissoc @(:property-schemas import-state) (keys old-property-schemas))]
    (if (seq (:block/refs block))
      (cond-> block
        true
        (update
         :block/refs
         (fn [refs]
           (mapv (fn [ref]
                   (if (ref-to-ignore? ref)
                     ref
                     (merge (assoc ref :block/format :markdown)
                            (when-let [schema (get new-property-schemas (keyword (:block/name ref)))]
                              {:block/type "property"
                               :block/schema schema}))))
                 refs)))
        (:block/content block)
        (update :block/content
                db-content/page-ref->special-id-ref
                ;; TODO: Handle refs for whiteboard block which has none
                (->> (:block/refs block)
                     (remove ref-to-ignore?)
                     (map #(add-uuid-to-page-map % page-names-to-uuids)))))
      block)))

(defn- update-block-macros
  [block db page-names-to-uuids]
  (if (seq (:block/macros block))
    (update block :block/macros
            (fn [macros]
              (mapv (fn [m]
                      (-> m
                          (update :block/properties
                                  (fn [props]
                                    (update-keys props #(cached-prop-name->uuid db page-names-to-uuids %))))
                          (assoc :block/uuid (d/squuid))))
                    macros)))
    block))

(defn- fix-pre-block-references
  [{:block/keys [left parent page] :as block} pre-blocks]
  (cond-> block
    (and (vector? left) (contains? pre-blocks (second left)))
    (assoc :block/left page)
    ;; Children blocks of pre-blocks get lifted up to the next level which can cause conflicts
    ;; TODO: Detect sibling blocks to avoid parent-left conflicts
    (and (vector? parent) (contains? pre-blocks (second parent)))
    (assoc :block/parent page)))

(defn- build-block-tx
  [db block pre-blocks page-names-to-uuids {:keys [import-state tag-classes] :as options}]
  ;; (prn ::block-in block)
  (let [old-property-schemas @(:property-schemas import-state)]
    (-> block
        (fix-pre-block-references pre-blocks)
        (update-block-macros db page-names-to-uuids)
        ;; needs to come before update-block-refs to detect new property schemas
        (handle-block-properties db page-names-to-uuids (:block/refs block) options)
        (update-block-refs page-names-to-uuids old-property-schemas options)
        (update-block-tags tag-classes page-names-to-uuids)
        (update-block-marker db options)
        (update-block-priority db options)
        (update-block-deadline db options)
        (update-block-scheduled db options)
        add-missing-timestamps
        ;; ((fn [x] (prn :block-out x) x))
        ;; TODO: org-mode content needs to be handled
        (assoc :block/format :markdown))))

(defn- build-new-page
  [m new-property-schemas tag-classes page-names-to-uuids page-tags-uuid]
  (-> (merge {:block/journal? false} m)
      ;; Fix pages missing :block/original-name. Shouldn't happen
      ((fn [m']
         (if-not (:block/original-name m')
           (assoc m' :block/original-name (:block/name m'))
           m')))
      (merge (when-let [schema (get new-property-schemas (keyword (:block/name m)))]
               {:block/type "property"
                :block/schema schema}))
      add-missing-timestamps
      ;; TODO: org-mode content needs to be handled
      (assoc :block/format :markdown)
      (dissoc :block/whiteboard?)
      (update-page-tags tag-classes page-names-to-uuids page-tags-uuid)))

(defn- build-pages-tx
  "Given all the pages and blocks parsed from a file, return all non-whiteboard pages to be transacted"
  [conn pages blocks {:keys [page-tags-uuid import-state tag-classes property-classes property-parent-classes notify-user]
                      :as options}]
  (let [all-pages (->> (extract/with-ref-pages pages blocks)
                       ;; remove unused property pages unless the page has content
                       (remove #(and (contains? (into property-classes property-parent-classes) (keyword (:block/name %)))
                                     (not (:block/file %))))
                       ;; remove file path relative
                       (map #(dissoc % :block/file)))
        existing-pages (keep #(d/entity @conn [:block/name (:block/name %)]) all-pages)
        existing-page-names (set (map :block/name existing-pages))
        new-pages (remove #(contains? existing-page-names (:block/name %)) all-pages)
        page-names-to-uuids (into {}
                                  (map (juxt :block/name :block/uuid) (concat new-pages existing-pages)))
        old-property-schemas @(:property-schemas import-state)
        ;; must come before building tx to detect new-property-schemas
        all-pages' (mapv #(handle-page-properties % @conn page-names-to-uuids all-pages options)
                         all-pages)
        new-property-schemas (apply dissoc @(:property-schemas import-state) (keys old-property-schemas))
        pages-tx (keep #(if (existing-page-names (:block/name %))
                          (let [schema (get new-property-schemas (keyword (:block/name %)))
                                ;; These attributes are not allowed to be transacted because they must not change across files
                                ;; block/uuid was particularly bad as it actually changed the page's identity across files
                                disallowed-attributes [:block/name :block/uuid :block/format :block/journal? :block/original-name :block/journal-day
                                                       :block/created-at :block/updated-at]
                                allowed-attributes [:block/properties :block/tags :block/alias :block/namespace :class/parent :block/type]
                                block-changes (select-keys % allowed-attributes)]
                            (when-let [ignored-attrs (not-empty (apply dissoc % (into disallowed-attributes allowed-attributes)))]
                              (notify-user {:msg (str "Import ignored the following attributes on page " (pr-str (:block/original-name %)) ": "
                                                      ignored-attrs)}))
                            (when (or schema (seq block-changes))
                              (cond-> (merge {:block/name (:block/name %)} block-changes)
                                (:block/tags %)
                                (update-page-tags tag-classes page-names-to-uuids page-tags-uuid)
                                schema
                                (assoc :block/type "property" :block/schema schema))))
                          (build-new-page % new-property-schemas tag-classes page-names-to-uuids page-tags-uuid))
                       all-pages')]
    {:pages-tx pages-tx
     :page-names-to-uuids page-names-to-uuids}))

(defn- build-upstream-properties-tx
  "Builds tx for upstream properties that have changed and any instances of its
  use in db or in given blocks-tx. Upstream properties can be properties that
  already exist in the DB from another file or from earlier uses of a property
  in the same file"
  [db page-names-to-uuids upstream-properties block-properties-text-values blocks-tx log-fn]
  (if (seq upstream-properties)
    (do
      (log-fn :props-upstream-to-change upstream-properties)
      (mapcat
       (fn [[prop {:keys [schema]}]]
         ;; property schema change
         (let [prop-uuid (cached-prop-name->uuid db page-names-to-uuids prop)]
           (into [{:block/name (name prop) :block/schema schema}]
                 ;; property value changes
                 (when (= :default (:type schema))
                   (let [existing-blocks
                         (map first
                              (d/q '[:find (pull ?b [:block/uuid :block/properties])
                                     :in $ ?p %
                                     :where (or (has-page-property ?b ?p)
                                                (has-property ?b ?p))]
                                   db
                                   prop
                                   (rules/extract-rules rules/db-query-dsl-rules)))
                         ;; blocks in current file
                         pending-blocks (keep #(when (get-in % [:block/properties prop-uuid])
                                                 (select-keys % [:block/uuid :block/properties]))
                                              blocks-tx)]
                     (mapv (fn [m]
                             {:block/uuid (:block/uuid m)
                              :block/properties
                              (merge (:block/properties m)
                                     {prop-uuid (or (get-in block-properties-text-values [(:block/uuid m) prop])
                                                    (throw (ex-info (str "No :block/text-properties-values found when changing property values: " (:block/uuid m))
                                                                    {:property prop
                                                                     :block/uuid (:block/uuid m)})))})})
                           ;; there should always be some blocks to update or else the change wouldn't have
                           ;; been detected
                           (concat existing-blocks pending-blocks)))))))
       upstream-properties))
    []))

(defn new-import-state
  "New import state that is used in add-file-to-db-graph. State is atom per
   key to make code more readable and encourage local mutations"
  []
  {;; Vec of maps with keys :property, :value, :schema and :location.
   ;; Properties are ignored to keep graph valid and notify users of ignored properties.
   ;; Properties with :schema are ignored due to property schema changes
   :ignored-properties (atom [])
   ;; Map of property names (keyword) and their current schemas (map).
   ;; Used for adding schemas to properties and detecting changes across a property's usage
   :property-schemas (atom {})
   ;; Map of block uuids to their :block/properties-text-values value.
   ;; Used if a property value changes to :default
   :block-properties-text-values (atom {})})

(defn- build-tx-options [{:keys [user-options] :as options}]
  (merge
   (dissoc options :extract-options :user-options)
   {:import-state (or (:import-state options) (new-import-state))
    ;; Track per file changes to make to existing properties
    ;; Map of property names (keyword) and their changes (map)
    :upstream-properties (atom {})
    :tag-classes (set (map string/lower-case (:tag-classes user-options)))
    :property-classes (set/difference
                       (set (map (comp keyword string/lower-case) (:property-classes user-options)))
                       built-in-property-names)
    :property-parent-classes (set/difference
                              (set (map (comp keyword string/lower-case) (:property-parent-classes user-options)))
                              built-in-property-names)}))

(defn add-file-to-db-graph
  "Parse file and save parsed data to the given db graph. Options available:

* :extract-options - Options map to pass to extract/extract
* :user-options - User provided options maps that alter how a file is converted to db graph. Current options
   are :tag-classes (set) and :property-classes (set).
* :page-tags-uuid - uuid of pageTags property
* :import-state - useful import state to maintain across files e.g. property schemas or ignored properties
* :macros - map of macros for use with macro expansion
* :notify-user - Displays warnings to user without failing the import. Fn receives a map with :msg
* :log-fn - Logs messages for development. Defaults to prn"
  [conn file content {:keys [extract-options notify-user log-fn]
                      :or {notify-user #(println "[WARNING]" (:msg %))
                           log-fn prn}
                      :as *options}]
  (let [options (assoc *options :notify-user notify-user :log-fn log-fn)
        format (common-util/get-format file)
        extract-options' (merge {:block-pattern (common-config/get-block-pattern format)
                                 :date-formatter "MMM do, yyyy"
                                 :uri-encoded? false
                                 :db-graph-mode? true
                                 :filename-format :legacy}
                                extract-options
                                {:db @conn})
        {:keys [pages blocks]}
        (cond (contains? common-config/mldoc-support-formats format)
              (extract/extract file content extract-options')

              (common-config/whiteboard? file)
              (extract/extract-whiteboard-edn file content extract-options')

              :else
              (notify-user {:msg (str "Skipped file since its format is not supported: " file)}))
        tx-options (build-tx-options options)
        ;; Build page and block txs
        {:keys [pages-tx page-names-to-uuids]} (build-pages-tx conn pages blocks tx-options)
        whiteboard-pages (->> pages-tx
                              ;; support old and new whiteboards
                              (filter #(#{"whiteboard" ["whiteboard"]} (:block/type %)))
                              (map (fn [page-block]
                                     (-> page-block
                                         (assoc :block/journal? false
                                                :block/format :markdown
                                                 ;; fixme: missing properties
                                                :block/properties {(get-pid @conn :ls-type) :whiteboard-page})))))
        pre-blocks (->> blocks (keep #(when (:block/pre-block? %) (:block/uuid %))) set)
        blocks-tx (->> blocks
                       (remove :block/pre-block?)
                       (mapv #(build-block-tx @conn % pre-blocks page-names-to-uuids
                                              (assoc tx-options :whiteboard? (some? (seq whiteboard-pages))))))
        upstream-properties-tx (build-upstream-properties-tx
                                @conn
                                page-names-to-uuids
                                @(:upstream-properties tx-options)
                                @(get-in tx-options [:import-state :block-properties-text-values])
                                blocks-tx
                                log-fn)
        ;; Build indices
        pages-index (map #(select-keys % [:block/name]) pages-tx)
        block-ids (map (fn [block] {:block/uuid (:block/uuid block)}) blocks-tx)
        block-refs-ids (->> (mapcat :block/refs blocks-tx)
                            (filter (fn [ref] (and (vector? ref)
                                                   (= :block/uuid (first ref)))))
                            (map (fn [ref] {:block/uuid (second ref)}))
                            (seq))
        ;; To prevent "unique constraint" on datascript
        block-ids (set/union (set block-ids) (set block-refs-ids))
        ;; Order matters as upstream-properties-tx can override some blocks-tx and indices need
        ;; to come before their corresponding tx
        tx (concat whiteboard-pages pages-index pages-tx block-ids blocks-tx upstream-properties-tx)
        tx' (common-util/fast-remove-nils tx)
        result (d/transact! conn tx')]
    result))

;; Higher level export fns
;; =======================

(defn- export-doc-file
  [{:keys [path idx] :as file} conn <read-file
   {:keys [notify-user set-ui-state export-file]
    :or {set-ui-state (constantly nil)
         export-file (fn export-file [conn m opts]
                       (add-file-to-db-graph conn (:file/path m) (:file/content m) opts))}
    :as options}]
  ;; (prn :export-doc-file path idx)
  (-> (p/let [_ (set-ui-state [:graph/importing-state :current-idx] (inc idx))
              _ (set-ui-state [:graph/importing-state :current-page] path)
              content (<read-file file)
              m {:file/path path :file/content content}]
        (export-file conn m (dissoc options :set-ui-state :export-file))
        ;; returning val results in smoother ui updates
        m)
      (p/catch (fn [error]
                 (notify-user {:msg (str "Import failed on " (pr-str path) " with error:\n" error)
                               :level :error
                               :ex-data {:path path :error error}})))))

(defn export-doc-files
  "Exports all user created files i.e. under journals/ and pages/.
   Recommended to use build-doc-options and pass that as options"
  [conn *doc-files <read-file {:keys [notify-user set-ui-state]
                               :or {set-ui-state (constantly nil) notify-user prn}
                               :as options}]
  (set-ui-state [:graph/importing-state :total] (count *doc-files))
  (let [doc-files (mapv #(assoc %1 :idx %2)
                        ;; Sort files to ensure reproducible import behavior
                        (sort-by :path *doc-files)
                        (range 0 (count *doc-files)))]
    (-> (p/loop [_file-map (export-doc-file (get doc-files 0) conn <read-file options)
                 i 0]
          (when-not (>= i (dec (count doc-files)))
            (p/recur (export-doc-file (get doc-files (inc i)) conn <read-file options)
                     (inc i))))
        (p/catch (fn [e]
                   (notify-user {:msg (str "Import has unexpected error:\n" e)
                                 :level :error}))))))

(defn- default-save-file [conn path content]
  (ldb/transact! conn [{:file/path path
                        :file/content content
                        :file/last-modified-at (js/Date.)}]))

(defn- export-logseq-files
  "Exports files under logseq/"
  [repo-or-conn logseq-files <read-file {:keys [<save-file notify-user]
                                         :or {<save-file default-save-file}}]
  (let [custom-css (first (filter #(string/ends-with? (:path %) "logseq/custom.css") logseq-files))
        custom-js (first (filter #(string/ends-with? (:path %) "logseq/custom.js") logseq-files))]
    (-> (p/do!
         (when custom-css
           (-> (<read-file custom-css)
               (p/then #(<save-file repo-or-conn "logseq/custom.css" %))))
         (when custom-js
           (-> (<read-file custom-js)
               (p/then #(<save-file repo-or-conn "logseq/custom.js" %)))))
        (p/catch (fn [error]
                   (notify-user {:msg (str "Import unexpectedly failed while reading logseq files:\n" error)
                                 :level :error}))))))

(defn- export-config-file
  [repo-or-conn config-file <read-file {:keys [<save-file notify-user default-config]
                                        :or {default-config {}
                                             <save-file default-save-file}}]
  (-> (<read-file config-file)
      (p/then #(p/do!
                (<save-file repo-or-conn "logseq/config.edn" %)
                ;; Return original config as import process depends on original config e.g. :hidden
                (edn/read-string %)))
      (p/catch (fn [err]
                 (notify-user {:msg "Import may have mistakes due to an invalid config.edn. Recommend re-importing with a valid config.edn"
                               :level :error
                               :ex-data {:error err}})
                 (edn/read-string default-config)))))

(defn- export-class-properties
  [conn repo-or-conn]
  (let [user-classes (->> (d/q '[:find (pull ?b [:db/id :block/name])
                                 :where [?b :block/type "class"]] @conn)
                          (map first)
                          (remove #(db-class/built-in-classes (keyword (:block/name %)))))
        class-to-prop-uuids
        (->> (d/q '[:find ?t ?prop-name ?prop-uuid #_?class
                    :in $ ?user-classes
                    :where
                    [?b :block/tags ?t]
                    [?t :block/name ?class]
                    [(contains? ?user-classes ?class)]
                    [?b :block/properties ?bp]
                    [?prop-b :block/name ?prop-name]
                    [?prop-b :block/uuid ?prop-uuid]
                    [(get ?bp ?prop-uuid) ?_v]]
                  @conn
                  (set (map :block/name user-classes)))
             (remove #(ldb/built-in? @conn (d/entity @conn [:block/name (second %)])))
             (reduce (fn [acc [class-id _prop-name prop-uuid]]
                       (update acc class-id (fnil conj #{}) prop-uuid))
                     {}))
        tx (mapv (fn [[class-id prop-ids]]
                   {:db/id class-id
                    :block/schema {:properties (vec prop-ids)}})
                 class-to-prop-uuids)]
    (ldb/transact! repo-or-conn tx)))

(defn- export-asset-files
  "Exports files under assets/"
  [*asset-files <copy-asset-file {:keys [notify-user set-ui-state]
                                  :or {set-ui-state (constantly nil)}}]
  (let [asset-files (mapv #(assoc %1 :idx %2)
                          ;; Sort files to ensure reproducible import behavior
                          (sort-by :path *asset-files)
                          (range 0 (count *asset-files)))
        copy-asset (fn copy-asset [{:keys [path] :as file}]
                     (p/catch
                      (<copy-asset-file file)
                      (fn [error]
                        (notify-user {:msg (str "Import failed on " (pr-str path) " with error:\n" error)
                                      :level :error
                                      :ex-data {:path path :error error}}))))]
    (when (seq asset-files)
      (set-ui-state [:graph/importing-state :current-page] "Asset files")
      (-> (p/loop [_ (copy-asset (get asset-files 0))
                   i 0]
            (when-not (>= i (dec (count asset-files)))
              (p/recur (copy-asset (get asset-files (inc i)))
                       (inc i))))
          (p/catch (fn [e]
                     (notify-user {:msg (str "Import has an unexpected error:\n" e)
                                   :level :error})))))))

(defn- insert-favorites
  "Inserts favorited pages as uuids into a new favorite page"
  [repo-or-conn favorited-ids page-id]
  (let [tx (reduce (fn [acc favorite-id]
                     (conj acc
                           (sqlite-util/block-with-timestamps
                            (merge (ldb/build-favorite-tx favorite-id)
                                   {:block/uuid (d/squuid)
                                    :db/id (or (some-> (:db/id (last acc)) dec) -1)
                                    :block/left {:db/id (or (:db/id (last acc)) page-id)}
                                    :block/parent page-id
                                    :block/page page-id}))))
                   []
                   favorited-ids)]
    (ldb/transact! repo-or-conn tx)))

(defn- export-favorites-from-config-edn
  [conn repo config {:keys [log-fn] :or {log-fn prn}}]
  (when-let [favorites (seq (:favorites config))]
    (p/do!
     (ldb/create-favorites-page repo)
     (if-let [favorited-ids
              (keep (fn [page-name]
                      (some-> (d/entity @conn [:block/name (common-util/page-name-sanity-lc page-name)])
                              :block/uuid))
                    favorites)]
       (let [page-entity (d/entity @conn [:block/name common-config/favorites-page-name])]
         (insert-favorites repo favorited-ids (:db/id page-entity)))
       (log-fn :no-favorites-found {:favorites favorites})))))

(defn build-doc-options
  "Builds options for use with export-doc-files"
  [conn config options]
  (-> {:extract-options {:date-formatter (common-config/get-date-formatter config)
                         :user-config config
                         :filename-format (or (:file/name-format config) :legacy)
                         :verbose (:verbose options)}
       :user-config config
       :user-options (select-keys options [:tag-classes :property-classes :property-parent-classes])
       :page-tags-uuid (:block/uuid (d/entity @conn :logseq.property/page-tags))
       :import-state (new-import-state)
       :macros (or (:macros options) (:macros config))}
      (merge (select-keys options [:set-ui-state :export-file :notify-user]))))

(defn export-file-graph
  "Main fn which exports a file graph given its files and imports them
   into a DB graph. Files is expected to be a seq of maps with a :path key.
   The user experiences this as an import so all user-facing messages are
   described as import. options map contains the following keys:
   * :set-ui-state - fn which updates ui to indicate progress of import
   * :notify-user - fn which notifies user of important messages with a map
     containing keys :msg, :level and optionally :ex-data when there is an error
   * :log-fn - fn which logs developer messages
   * :rpath-key - keyword used to get relative path in file map. Default to :path
   * :<read-file - fn which reads a file across multiple steps
   * :default-config - default config if config is unable to be read
   * :<save-config-file - fn which saves a config file
   * :<save-logseq-file - fn which saves a logseq file
   * :<copy-asset - fn which copies asset file
   
   Note: See export-doc-files for additional options that are only for it"
  [repo-or-conn conn config-file *files {:keys [<read-file <copy-asset rpath-key log-fn]
                                         :or {rpath-key :path log-fn println}
                                         :as options}]
  (p/let [config (export-config-file
                  repo-or-conn config-file <read-file
                  (-> (select-keys options [:notify-user :default-config :<save-config-file])
                      (set/rename-keys {:<save-config-file :<save-file})))]
    (let [files (common-config/remove-hidden-files *files config rpath-key)
          logseq-file? #(string/starts-with? (get % rpath-key) "logseq/")
          doc-files (->> files
                         (remove logseq-file?)
                         (filter #(contains? #{"md" "org" "markdown" "edn"} (path/file-ext (:path %)))))
          asset-files (filter #(string/starts-with? (get % rpath-key) "assets/") files)
          doc-options (build-doc-options conn config options)]
      (log-fn "Importing" (count files) "files ...")
      ;; These export* fns are all the major export/import steps
      (p/do!
       (export-logseq-files repo-or-conn (filter logseq-file? files) <read-file
                            (-> (select-keys options [:notify-user :<save-logseq-file])
                                (set/rename-keys {:<save-logseq-file :<save-file})))
       (export-asset-files asset-files <copy-asset (select-keys options [:notify-user :set-ui-state]))
       (export-doc-files conn doc-files <read-file doc-options)
       (export-favorites-from-config-edn conn repo-or-conn config {})
       (export-class-properties conn repo-or-conn)
       {:import-state (:import-state doc-options)
        :files files}))))