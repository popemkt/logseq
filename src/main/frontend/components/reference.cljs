(ns frontend.components.reference
  (:require [clojure.string :as string]
            [frontend.config :as config]
            [frontend.components.block :as block]
            [frontend.components.content :as content]
            [frontend.components.editor :as editor]
            [frontend.context.i18n :refer [t]]
            [frontend.db :as db]
            [frontend.db-mixins :as db-mixins]
            [frontend.db.utils :as db-utils]
            [frontend.db.model :as model-db]
            [frontend.handler.block :as block-handler]
            [frontend.handler.page :as page-handler]
            [frontend.search :as search]
            [frontend.state :as state]
            [frontend.ui :as ui]
            [logseq.shui.ui :as shui]
            [frontend.util :as util]
            [rum.core :as rum]
            [frontend.modules.outliner.tree :as tree]
            [frontend.db.async :as db-async]
            [promesa.core :as p]))

(defn- frequencies-sort
  [references]
  (sort-by second #(> %1 %2) references))

(defn filtered-refs
  [page filters filters-atom filtered-references]
  [:div.flex.gap-2.flex-wrap.items-center
   (for [[ref-name ref-count] filtered-references]
     (when ref-name
       (let [lc-reference (string/lower-case ref-name)]
         (ui/button
           [:span
            ref-name
            (when ref-count [:sup " " ref-count])]
           :on-click (fn [e]
                       (swap! filters-atom #(if (nil? (get filters lc-reference))
                                              (assoc % lc-reference (not (.-shiftKey e)))
                                              (dissoc % lc-reference)))
                       (page-handler/save-filter! page @filters-atom))
           :small? true
           :variant :outline
           :key ref-name))))])

(rum/defcs filter-dialog-inner < rum/reactive (rum/local "" ::filterSearch)
  [state page-entity filters-atom *references]
  (let [filter-search (get state ::filterSearch)
        references (rum/react *references)
        filtered-references  (frequencies-sort
                              (if (= @filter-search "")
                                references
                                (search/fuzzy-search references @filter-search :limit 500 :extract-fn first)))
        filters (rum/react filters-atom)
        includes (keep (fn [[page include?]]
                         (let [page' (model-db/get-page-original-name page)]
                           (when include? [page'])))
                       filters)
        excludes (keep (fn [[page include?]]
                         (let [page' (model-db/get-page-original-name page)]
                           (when-not include? [page'])))
                       filters)]
    [:div.ls-filters.filters
     [:div.sm:flex.sm:items-start
      [:div.mx-auto.flex-shrink-0.flex.items-center.justify-center.h-12.w-12.rounded-full.bg-gray-200.text-gray-500.sm:mx-0.sm:h-10.sm:w-10
       (ui/icon "filter" {:size 20})]
      [:div.mt-3.text-center.sm:mt-0.sm:ml-4.sm:text-left.pb-2
       [:h3#modal-headline.text-lg.leading-6.font-medium (t :linked-references/filter-heading)]
       [:span.text-xs
        (t :linked-references/filter-directions)]]]
     (when (seq filters)
       [:div.cp__filters.mb-4.ml-2
        (when (seq includes)
          [:div.flex.flex-row.flex-wrap.center-items
           [:div.mr-1.font-medium.py-1 (t :linked-references/filter-includes)]
           (filtered-refs page-entity filters filters-atom includes)])
        (when (seq excludes)
          [:div.flex.flex-row.flex-wrap
           [:div.mr-1.font-medium.py-1 (t :linked-references/filter-excludes)]

           (filtered-refs page-entity filters filters-atom excludes)])])
     [:div.cp__filters-input-panel.flex.focus-within:bg-gray-03
      (ui/icon "search")
      [:input.cp__filters-input.w-full.bg-transparent
       {:placeholder (t :linked-references/filter-search)
        :autofocus true
        :on-change (fn [e]
                     (reset! filter-search (util/evalue e)))}]]
     (let [all-filters (set (keys filters))
           refs (remove (fn [[page _]] (all-filters (util/page-name-sanity-lc page)))
                        filtered-references)]
       (when (seq refs)
         [:div.mt-4
          (filtered-refs page-entity filters filters-atom refs)]))]))

(defn filter-dialog
  [page-entity filters-atom *references]
  (fn []
    (filter-dialog-inner page-entity filters-atom *references)))

(rum/defc block-linked-references < rum/reactive db-mixins/query
  {:init (fn [state]
           (when-let [e (db/entity [:block/uuid (first (:rum/args state))])]
             (db-async/<get-block-refs (state/get-current-repo) (:db/id e)))
           state)}
  [block-id]
  (when-let [e (db/entity [:block/uuid block-id])]
    (when-not (state/sub-async-query-loading (str (:db/id e) "-refs"))
      (let [page? (some? (:block/name e))
            ref-blocks (if page?
                         (-> (db/get-page-referenced-blocks (:db/id e))
                             db-utils/group-by-page)
                         (db/get-block-referenced-blocks (:db/id e)))]
        (when (> (count ref-blocks) 0)
          (let [ref-hiccup (block/->hiccup ref-blocks
                                           {:id (str block-id)
                                            :ref? true
                                            :breadcrumb-show? true
                                            :group-by-page? true
                                            :editor-box editor/box}
                                           {})]
            [:div.references-blocks
             (content/content block-id
                              {:hiccup ref-hiccup})]))))))

(rum/defc references-inner
  [page-name filters filtered-ref-blocks]
  [:div.references-blocks.faster.fade-in
   (let [ref-hiccup (block/->hiccup filtered-ref-blocks
                                    {:id page-name
                                     :ref? true
                                     :breadcrumb-show? true
                                     :group-by-page? true
                                     :editor-box editor/box
                                     :filters filters}
                                    {})]
     (content/content page-name {:hiccup ref-hiccup}))])

(rum/defc references-cp
  [page-entity page-name filters filters-atom filter-state total filter-n filtered-ref-blocks *ref-pages]
  (let [threshold (state/get-linked-references-collapsed-threshold)
        default-collapsed? (>= total threshold)
        *collapsed? (atom nil)]
    (ui/foldable
     [:div.flex.flex-row.flex-1.justify-between.items-center
      [:h2.font-medium (t :linked-references/reference-count (if (seq filters) filter-n nil) total)]
      [:a.filter.fade-link
       {:title (t :linked-references/filter-heading)
        :on-mouse-over (fn [_e]
                         (when @*collapsed? ; collapsed
                           ;; expand
                           (reset! @*collapsed? false)))
        :on-pointer-down (fn [e]
                           (util/stop-propagation e)
                           (shui/dialog-open!
                             (filter-dialog page-entity filters-atom *ref-pages)))}
       (ui/icon "filter" {:class (cond
                                   (empty? filter-state)
                                   "opacity-60 hover:opacity-100"
                                   (every? true? (vals filter-state))
                                   "text-success"
                                   (every? false? (vals filter-state))
                                   "text-error"
                                   :else
                                   "text-warning")
                          :size  22})]]

     (fn []
       (references-inner page-name filters filtered-ref-blocks))

     {:default-collapsed? default-collapsed?
      :title-trigger? true
      :init-collapsed (fn [collapsed-atom]
                        (reset! *collapsed? collapsed-atom))})))

(defn- get-filtered-children
  [block parent->blocks]
  (let [children (get parent->blocks (:db/id block))]
    (set
     (loop [blocks children
            result (vec children)]
       (if (empty? blocks)
         result
         (let [fb (first blocks)
               children (get parent->blocks (:db/id fb))]
           (recur
            (concat children (rest blocks))
            (conj result fb))))))))

(rum/defc sub-page-properties-changed < rum/static
  [page-entity v filters-atom]
  (rum/use-effect!
   (fn []
     (reset! filters-atom
             (page-handler/get-filters page-entity)))
   [page-entity v filters-atom])
  [:<>])

(rum/defcs references* < rum/reactive db-mixins/query
  (rum/local nil ::ref-pages)
  {:init (fn [state]
           (let [page (first (:rum/args state))
                 filters (when page (atom nil))]
             (when page (db-async/<get-block-refs (state/get-current-repo) (:db/id page)))
             (assoc state ::filters filters)))}
  [state page-entity]
  (when page-entity
    (let [repo (state/get-current-repo)]
      (when page-entity
        (when-not (state/sub-async-query-loading (str (:db/id page-entity) "-refs"))
          (let [page-name (:block/name page-entity)
                page-props-v (state/sub-page-properties-changed page-name)
                *ref-pages (::ref-pages state)
                filters-atom (get state ::filters)
                filter-state (rum/react filters-atom)
                page-id (:db/id page-entity)
                ref-blocks (db/get-page-referenced-blocks page-id)
                aliases (db/page-alias-set repo page-id)
                aliases-exclude-self (set (remove #{page-id} aliases))
                top-level-blocks (filter (fn [b] (some aliases (set (map :db/id (:block/refs b))))) ref-blocks)
                top-level-blocks-ids (set (map :db/id top-level-blocks))
                filters (when (seq filter-state)
                          (-> (group-by second filter-state)
                              (update-vals #(map first %))))
                filtered-ref-blocks (->> (block-handler/filter-blocks ref-blocks filters)
                                         (block-handler/get-filtered-ref-blocks-with-parents ref-blocks))
                total (count top-level-blocks)
                filtered-top-blocks (filter (fn [b] (top-level-blocks-ids (:db/id b))) filtered-ref-blocks)
                filter-n (count filtered-top-blocks)
                parent->blocks (group-by (fn [x] (:db/id (x :block/parent))) filtered-ref-blocks)
                result (->> (group-by :block/page filtered-top-blocks)
                            (map (fn [[page blocks]]
                                   (let [blocks (sort-by (fn [b] (not= (:db/id page) (:db/id (:block/parent b)))) blocks)
                                         result (map (fn [block]
                                                       (let [filtered-children (get-filtered-children block parent->blocks)
                                                             refs (when-not (contains? top-level-blocks-ids (:db/id (:block/parent block)))
                                                                    (block-handler/get-blocks-refed-pages aliases (cons block filtered-children)))
                                                             block' (assoc (tree/block-entity->map block) :block/children filtered-children)]
                                                         [block' refs])) blocks)
                                         blocks' (map first result)
                                         page' (if (contains? aliases-exclude-self (:db/id page))
                                                 {:db/id (:db/id page)
                                                  :block/alias? true
                                                  :block/journal-day (:block/journal-day page)}
                                                 page)]
                                     [[page' blocks'] (mapcat second result)]))))
                filtered-ref-blocks' (map first result)
                ref-pages (->>
                           (mapcat second result)
                           (map :block/original-name)
                           frequencies)]
            (reset! *ref-pages ref-pages)
            (when (or (seq filter-state) (> filter-n 0))
              [:div.references.page-linked.flex-1.flex-row
               (sub-page-properties-changed page-entity page-props-v filters-atom)
               [:div.content.pt-6
                (references-cp page-entity page-name filters filters-atom filter-state total filter-n filtered-ref-blocks' *ref-pages)]])))))))

(rum/defc references
  [page-entity]
  (ui/catch-error
   (ui/component-error (if (config/db-based-graph? (state/get-current-repo))
                         "Linked References: Unexpected error."
                         "Linked References: Unexpected error. Please re-index your graph first."))
   (references* page-entity)))

(rum/defcs unlinked-references-aux
  < rum/reactive db-mixins/query
  {:init
   (fn [state]
     (let [*result (atom nil)
           [page *n-ref] (:rum/args state)]
       (p/let [result (search/get-page-unlinked-refs (:db/id page))]
         (reset! *n-ref (count result))
         (reset! *result result))
       (assoc state ::result *result)))}
  [state page _n-ref]
  (let [ref-blocks (rum/react (::result state))]
    (when (seq ref-blocks)
      [:div.references-blocks
       (let [ref-hiccup (block/->hiccup ref-blocks
                                        {:id (str (:block/original-name page) "-unlinked-")
                                         :ref? true
                                         :group-by-page? true
                                         :editor-box editor/box}
                                        {})]
         (content/content (:block/name page)
                          {:hiccup ref-hiccup}))])))

(rum/defcs unlinked-references < rum/reactive
  (rum/local nil ::n-ref)
  [state page]
  (let [n-ref (get state ::n-ref)]
    (when page
      [:div.references.page-unlinked.mt-6.flex-1.flex-row.faster.fade-in
       [:div.content.flex-1
        (ui/foldable
         [:h2.font-medium (t :unlinked-references/reference-count @n-ref)]
         (fn [] (unlinked-references-aux page n-ref))
         {:default-collapsed? true
          :title-trigger? true})]])))
