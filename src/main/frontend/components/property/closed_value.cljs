(ns frontend.components.property.closed-value
  "Enum property config"
  (:require [rum.core :as rum]
            [clojure.string :as string]
            [frontend.modules.shortcut.core :as shortcut]
            [frontend.util :as util]
            [frontend.ui :as ui]
            [logseq.shui.ui :as shui]
            [frontend.components.dnd :as dnd]
            [frontend.components.icon :as icon-component]
            [frontend.components.property.util :as pu-component]
            [frontend.handler.property :as property-handler]
            [frontend.handler.db-based.property :as db-property-handler]
            [frontend.config :as config]
            [frontend.components.property.value :as property-value]
            [frontend.db :as db]
            [frontend.db.async :as db-async]
            [frontend.state :as state]
            [frontend.handler.property.util :as pu]
            [promesa.core :as p]
            [logseq.db.frontend.property :as db-property]))

(defn- <upsert-closed-value!
  "Create new closed value and returns its block UUID."
  [property item]
  (let [{:keys [block-id tx-data]} (db-property-handler/upsert-closed-value property item)]
    (p/do!
     (when (seq tx-data) (db/transact! tx-data))
     (when (seq tx-data) (db-property-handler/re-init-commands! property))
     block-id)))

(rum/defc item-value
  [type *value]
  (let [*input-ref (rum/use-ref nil)]
    (rum/use-effect!
      (fn []
        (when-let [^js el (rum/deref *input-ref)]
          (js/setTimeout #(.focus el) 100)))
      [])
    (case type
      ;; :page
      :date
      (let [value (if (string/blank? @*value) nil @*value)]
        (property-value/date-picker value
          {:on-change (fn [page]
                        (reset! *value (:block/uuid page)))}))

      (shui/input
        {:default-value @*value
         :class         "col-span-3"
         :auto-focus    true
         :ref           *input-ref
         :on-change     #(reset! *value (util/evalue %))}))))

(rum/defcs item-config < rum/reactive
  shortcut/disable-all-shortcuts
  {:init (fn [state]
           (let [block (second (:rum/args state))
                 value (or (str (get-in block [:block/schema :value])) "")
                 icon (when block (pu/get-block-property-value block :logseq.property/icon))
                 description (or (get-in block [:block/schema :description]) "")]
             (assoc state
                    ::value (atom value)
                    ::icon (atom icon)
                    ::description (atom description))))}
  [state property _item {:keys [toggle-fn on-save]}]
  (let [*value (::value state)
        *icon (::icon state)
        *description (::description state)
        save-handler (fn [e]
                       (util/stop e)
                       (when-not (string/blank? @*value)
                         (p/do!
                          (when on-save
                            (let [value (if (string? @*value)
                                          (string/trim @*value)
                                          @*value)]
                              (on-save value @*icon @*description)))
                          (when toggle-fn (toggle-fn)))))
        property-type (get-in property [:block/schema :type])]
    [:div.flex.flex-col.gap-4.p-4.whitespace-nowrap.w-96
     {:on-key-down (fn [e]
                     (when (= e.key "Enter")
                       (save-handler e)))}
     [:div.grid.grid-cols-5.gap-1.items-center.leading-8
      [:label.col-span-2 "Value:"]
      (item-value property-type *value)]
     [:div.grid.grid-cols-5.gap-1.items-center.leading-8
      [:label.col-span-2 "Icon:"]
      [:div.col-span-3.flex.flex-row.items-center.gap-2
       (icon-component/icon-picker (rum/react *icon)
                                   {:on-chosen (fn [_e icon]
                                                 (reset! *icon icon))})
       (when (rum/react *icon)
         [:a.fade-link.flex {:on-click (fn [_e]
                                         (reset! *icon nil))
                             :title "Delete this icon"}
          (ui/icon "X")])]]
     ;; Disable description for types that can't edit them
     (when-not (#{:page :date} property-type)
       [:div.grid.grid-cols-5.gap-1.items-start.leading-8
        [:label.col-span-2 "Description:"]
        [:div.col-span-3
         (shui/textarea
          {:on-change #(reset! *description (util/evalue %))
           :default-value @*description})]])
     [:div.flex.justify-end
      (shui/button {:on-click save-handler :size :sm} "Save")]]))

(rum/defcs choice-with-close <
  (rum/local false ::hover?)
  [state property item {:keys [toggle-fn delete-choice update-icon]} parent-opts]
  (let [*hover? (::hover? state)
        value (db-property/closed-value-name item)
        page? (:block/original-name item)
        date? (= :date (:type (:block/schema property)))]
    [:div.flex.flex-1.flex-row.items-center.gap-2.justify-between
     {:on-mouse-over #(reset! *hover? true)
      :on-mouse-out #(reset! *hover? false)}
     [:div.flex.flex-row.items-center.gap-2
      (icon-component/icon-picker (pu/get-block-property-value item :logseq.property/icon)
                                  {:on-chosen (fn [_e icon]
                                                (update-icon icon))})
      (cond
        date?
        [:div.flex.flex-row.items-center.gap-1
         (property-value/date-picker (:block/original-name item)
                                    {:on-change (fn [page]
                                                  (db-property-handler/replace-closed-value property
                                                                                            (:block/uuid page)
                                                                                            (:block/uuid item)))})
         ((:page-cp parent-opts) {:preview? false} item)]

        (and page? (:page-cp parent-opts))
        ((:page-cp parent-opts) {:preview? false} item)

        :else
        [:a {:on-click toggle-fn}
         value])]
     (when @*hover?
       [:a.fade-link.flex {:on-click delete-choice
                           :title "Delete this choice"}
        (ui/icon "X")])]))

(rum/defc choice-item-content
  [property *property-schema block parent-opts]
  (let [{:block/keys [uuid]} block]
    (let [content-fn
          (if config/publishing?
            (constantly [])
            (fn [{:keys [id]}]
              (let [opts {:toggle-fn #(shui/popup-hide! id)}]
                (item-config
                 property
                 block
                 (merge
                  parent-opts
                  (assoc opts :on-save
                         (fn [value icon description]
                           (<upsert-closed-value! property {:id          uuid
                                                            :value       value
                                                            :description description
                                                            :icon        icon}))))))))
          opts {:toggle-fn #(shui/popup-show! % content-fn)}]

      (choice-with-close
       property
       block
       (assoc opts
              :delete-choice
              (fn []
                (p/let [success? (db-property-handler/delete-closed-value! (db/get-db) property block)]
                  (when success?
                    (swap! *property-schema update :values (fn [vs] (vec (remove #(= uuid %) vs)))))))
              :update-icon
              (fn [icon]
                (property-handler/set-block-property! (state/get-current-repo) (:block/uuid block) :icon icon)))
       parent-opts))))

(rum/defc add-existing-values
  [property *property-schema values {:keys [toggle-fn]}]
  [:div.flex.flex-col.gap-1.w-64.p-4.overflow-y-auto
   {:class "max-h-[50dvh]"}
   [:div "Existing values:"]
   [:ol
    (for [value values]
      [:li (if (uuid? value)
             (let [result (db/entity [:block/uuid value])]
               (:block/original-name result))
             (str value))])]
   (ui/button
    "Add choices"
    {:on-click (fn []
                 (p/let [closed-values (db-property-handler/<add-existing-values-to-closed-values! property values)]
                   (swap! *property-schema assoc :values closed-values)
                   (toggle-fn)))})])

(rum/defc choices < rum/reactive
  [property *property-name *property-schema opts]
  (let [schema (:block/schema property)
        property-type (:type schema)
        values (:values schema)
        dropdown-opts {:modal-class (util/hiccup->class
                                     "origin-top-right.absolute.left-0.rounded-md.shadow-lg")}]
    [:div.closed-values.flex.flex-col
     (let [choices (doall
                    (keep (fn [id]
                            (when-let [block (db/sub-block (:db/id (db/entity [:block/uuid id])))]
                              {:id (str id)
                               :value id
                               :content (choice-item-content property *property-schema block (merge opts dropdown-opts))}))
                          values))]
       (dnd/items choices
                  {:on-drag-end (fn [new-values]
                                  (when (seq new-values)
                                    (swap! *property-schema assoc :values new-values)
                                    (pu-component/update-property! property @*property-name @*property-schema)))}))
     (if config/publishing?
       (constantly [])
       (shui/button
        {:variant :secondary
         :size :sm
         :on-click
         (fn [e]
           (p/let [values (db-async/<get-block-property-values (state/get-current-repo) (:block/uuid property))]
             (shui/popup-show! (.-target e)
                               (fn [{:keys [id]}]
                                 (let [opts {:toggle-fn (fn [] (shui/popup-hide! id))}
                                       values' (->> (if (= :many (get-in property [:block/schema :cardinality]))
                                                      (mapcat second values)
                                                      (map second values))
                                                    (remove string/blank?)
                                                    (remove (set (get-in property [:block/schema :values])))
                                                    distinct)]
                                   (if (seq values')
                                     (add-existing-values property *property-schema values' opts)
                                     (if (= :page property-type)
                                       (property-value/select-page property
                                                                   {:multiple-choices? false
                                                                    :dropdown? false
                                                                    :close-modal? false
                                                                    :on-chosen (fn [chosen]
                                                                                 (p/let [closed-value (<upsert-closed-value! property {:value chosen})]
                                                                                   (swap! *property-schema update :values (fnil conj []) closed-value)
                                                                                   (shui/popup-hide! id)))})
                                       (item-config
                                        property
                                        nil
                                        (assoc opts :on-save
                                               (fn [value icon description]
                                                 (p/let [closed-value (<upsert-closed-value! property {:value value
                                                                                                       :description description
                                                                                                       :icon icon})]
                                                   (swap! *property-schema update :values (fnil conj []) closed-value)))))))))
                               {:content-props {:class "w-auto"}})))}
        (ui/icon "plus" {:size 16})
        "Add choice"))]))
