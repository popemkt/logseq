(ns frontend.components.class
  (:require [frontend.config :as config]
            [frontend.db.model :as model]
            [frontend.db :as db]
            [frontend.handler.route :as route-handler]
            [frontend.state :as state]
            [frontend.ui :as ui]
            [frontend.util :as util]
            [rum.core :as rum]
            [frontend.components.block :as block]))

(rum/defc class-select
  [page class on-select]
  (let [repo (state/get-current-repo)
        children-pages (model/get-class-children repo (:db/id page))
        ;; Disallows cyclic hierarchies
        exclude-ids (-> (set (map (fn [id] (:block/uuid (db/entity id))) children-pages))
                        (conj (:block/uuid page))) ; break cycle
        classes (->> (model/get-all-classes repo)
                     (remove (fn [[_name id]] (contains? exclude-ids id))))
        options (sort-by :label
                         (map (fn [[name id]] {:label name
                                               :value id
                                               :selected (= class id)})
                              classes))
        options (cons (if class
                        {:label "Choose parent class"
                         :value ""}
                        {:label "Choose parent class"
                         :disabled true
                         :selected true
                         :value ""})
                      options)]
    (ui/select options
               (fn [_e value]
                 (on-select value))
               {:on-pointer-down
                (fn [e]
                  (when (util/meta-key? e)
                    (if-let [page-name (:block/name (db/entity [:block/uuid (some-> (util/evalue e) uuid)]))]
                      (do
                        (route-handler/redirect-to-page! page-name)
                        (.preventDefault e))
                      (js/console.error "No selected option found to navigate to"))))})))

(rum/defcs page-parent <
  (rum/local false ::show?)
  [state page parent]
  (let [*show? (::show? state)
        parent-id (:block/uuid parent)]
    (if (or parent-id @*show?)
      [:div.w-60
       (class-select page parent-id (fn [value]
                                      (if (seq value)
                                        (db/transact!
                                         [{:db/id (:db/id page)
                                           :class/parent [:block/uuid (uuid value)]}])
                                        (db/transact!
                                          [[:db.fn/retractAttribute (:db/id page) :class/parent]]))))]
      [:div.opacity-50.pointer.text-sm.cursor-pointer {:on-click #(reset! *show? true)}
       "Empty"])))

(rum/defcs configure < rum/reactive
  "Configure a class page"
  [state page {:keys [show-title?]
               :or {show-title? true}}]
  (let [page-id (:db/id page)
        page (when page-id (db/sub-block page-id))]
    (when page
      [:div.property-configure.grid.gap-2
       (when show-title? [:h1.title.mb-4 "Configure class"])

       [:div.grid.grid-cols-5.gap-1.items-center.class-parent
        [:div.col-span-2 "Parent class:"]
        (if config/publishing?
          [:div.col-span-3
           (if-let [parent-class (some-> (:db/id (:class/parent page))
                                         db/entity
                                         :block/original-name)]
             [:a {:on-click #(route-handler/redirect-to-page! parent-class)}
              parent-class]
             "None")]
          [:div.col-span-3
           (let [parent (some-> (:db/id (:class/parent page))
                                db/entity)]
             (page-parent page parent))])]

       (when (:class/parent page)
         (let [ancestor-pages (loop [parents [page]]
                                (if-let [parent (:class/parent (last parents))]
                                  (recur (conj parents parent))
                                  parents))
               class-ancestors (map :block/original-name (reverse ancestor-pages))]
           (when (> (count class-ancestors) 2)
             [:div.grid.grid-cols-5.gap-1.items-center.class-ancestors
              [:div.col-span-2 "Ancestor classes:"]
              [:div.col-span-3
               (interpose [:span.opacity-50.text-sm " > "]
                          (map (fn [class-name]
                                 (if (= class-name (:block/original-name page))
                                   [:span class-name]
                                   [:a {:on-click #(route-handler/redirect-to-page! class-name)} class-name]))
                               class-ancestors))]])))])))

(defn class-children-aux
  [class {:keys [default-collapsed?] :as opts}]
  (let [children (:class/_parent class)]
    (when (seq children)
      [:ul
       (for [child (sort-by :block/original-name children)]
         (let [title [:li.ml-2 (block/page-reference false (:block/original-name child) {:show-brackets? false} nil)]]
           (if (seq (:class/_parent child))
             (ui/foldable
              title
              (class-children-aux child opts)
              {:default-collapsed? default-collapsed?})
             title)))])))

(rum/defc class-children
  [class]
  (when (seq (:class/_parent class))
    (let [children-pages (model/get-class-children (state/get-current-repo) (:db/id class))
          ;; Expand children if there are about a pageful of total blocks to display
          default-collapsed? (> (count children-pages) 30)]
      [:div.mt-4
       (ui/foldable
        [:h2.font-medium "Child classes (" (count children-pages) ")"]
        [:div.mt-2.ml-1 (class-children-aux class {:default-collapsed? default-collapsed?})]
        {:default-collapsed? false
         :title-trigger? true})])))
