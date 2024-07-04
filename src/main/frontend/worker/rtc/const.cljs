(ns frontend.worker.rtc.const
  "RTC constants/schema"
  (:require [logseq.db.frontend.malli-schema :as db-malli-schema]
            [malli.core :as m]
            [malli.transform :as mt]
            [malli.util :as mu]))

(def block-pos-schema
  [:catn
   [:parent-uuid [:maybe :uuid]]
   [:order [:maybe db-malli-schema/block-order]]])

(def av-schema
  [:cat
   :keyword
   [:or
    :uuid   ;; reference type
    :string ;; all other type value convert to string by transit
    ]
   :int     ;; t
   :boolean ;; add(true) or retract
   ])

(def to-ws-op-schema
  [:multi {:dispatch first :decode/string #(update % 0 keyword)}
   [:move
    [:cat :keyword
     [:map
      [:block-uuid :uuid]
      [:pos block-pos-schema]]]]
   [:remove
    [:cat :keyword
     [:map
      [:block-uuids [:sequential :uuid]]]]]
   [:update-page
    [:cat :keyword
     [:map
      [:block-uuid :uuid]
      [:page-name :string]
      [:original-name :string]]]]
   [:remove-page
    [:cat :keyword
     [:map
      [:block-uuid :uuid]]]]
   [:update
    [:cat :keyword
     [:map
      [:block-uuid :uuid]
      [:db/ident {:optional true} :keyword]
      [:pos block-pos-schema]
      [:av-coll [:sequential av-schema]]
      [:card-one-attrs {:optional true} [:sequential :keyword]]]]]
   [:update-schema
    [:cat :keyword
     [:map
      [:block-uuid :uuid]
      [:db/ident :keyword]
      [:db/valueType :keyword]
      [:db/cardinality {:optional true} :keyword]
      [:db/index {:optional true} :boolean]]]]])

(def to-ws-ops-validator (m/validator [:sequential to-ws-op-schema]))
(def to-ws-ops-decoder (m/decoder [:sequential to-ws-op-schema] mt/string-transformer))

(def ^:private extra-attr-map-schema
  [:map-of
   :keyword
   [:or
    [:or :uuid :string]
    [:sequential [:or :uuid :string]]]])

(def data-from-ws-schema
  [:map
   [:req-id :string]
   [:t {:optional true} :int]
   [:t-before {:optional true} :int]
   [:failed-ops {:optional true} [:sequential to-ws-op-schema]]
   [:s3-presign-url {:optional true} :string]
   [:diff-data {:optional true} [:map-of :keyword :any]]
   [:online-users {:optional true} [:sequential [:map
                                                 [:user/uuid :uuid]
                                                 [:user/name :string]
                                                 [:user/email :string]]]]
   [:refed-blocks {:optional true}
    [:maybe
     [:sequential
      [:map
       [:block/uuid :uuid]
       [:db/ident {:optional true} :keyword]
       [:block/order {:optional true} db-malli-schema/block-order]
       [:block/parent {:optional true} :uuid]
       [::m/default extra-attr-map-schema]]]]]
   [:affected-blocks {:optional true}
    [:map-of :uuid
     [:multi {:dispatch :op :decode/string #(update % :op keyword)}
      [:move
       [:map {:closed true}
        [:op :keyword]
        [:self :uuid]
        [:parents [:sequential :uuid]]
        [:block/order {:optional true} db-malli-schema/block-order]
        [:hash {:optional true} :int]
        [:db/ident {:optional true} :keyword]]]
      [:remove
       [:map
        [:op :keyword]
        [:block-uuid :uuid]]]
      [:update-attrs
       [:map
        [:op :keyword]
        [:self :uuid]
        [:parents {:optional true} [:sequential :uuid]]
        [:block/order {:optional true} db-malli-schema/block-order]
        [:hash {:optional true} :int]
        [:db/ident {:optional true} :keyword]
        [::m/default extra-attr-map-schema]]]
      [:move+update-attrs
       [:map
        [:op :keyword]
        [:self :uuid]
        [:parents {:optional true} [:sequential :uuid]]
        [:block/order {:optional true} db-malli-schema/block-order]
        [:hash {:optional true} :int]
        [:db/ident {:optional true} :keyword]
        [::m/default extra-attr-map-schema]]]
      [:update-page
       [:map
        [:op :keyword]
        [:self :uuid]
        [:page-name :string]
        [:block/original-name :string]
        [:db/ident {:optional true} :keyword]
        [:block/order {:optional true} db-malli-schema/block-order]
        [::m/default extra-attr-map-schema]]]
      [:remove-page
       [:map
        [:op :keyword]
        [:block-uuid :uuid]]]]]]
   [:ex-data {:optional true} [:map [:type :keyword]]]
   [:ex-message {:optional true} :string]])

(def data-from-ws-coercer (m/coercer data-from-ws-schema mt/string-transformer))
(def data-from-ws-validator (m/validator data-from-ws-schema))

(def data-to-ws-schema
  (mu/closed-schema
   [:multi {:dispatch :action}
    ["list-graphs"
     [:map
      [:req-id :string]
      [:action :string]]]
    ["register-graph-updates"
     [:map
      [:req-id :string]
      [:action :string]
      [:graph-uuid :string]]]
    ["apply-ops"
     [:map
      [:req-id :string]
      [:action :string]
      [:graph-uuid :string]
      [:ops [:sequential to-ws-op-schema]]
      [:t-before :int]]]
    ["presign-put-temp-s3-obj"
     [:map
      [:req-id :string]
      [:action :string]]]
    ["upload-graph"
     [:map
      [:req-id :string]
      [:action :string]
      [:s3-key :string]
      [:graph-name :string]]]
    ["download-graph"
     [:map
      [:req-id :string]
      [:action :string]
      [:graph-uuid :string]]]
    ["download-info-list"
     [:map
      [:req-id :string]
      [:action :string]
      [:graph-uuid :string]]]
    ["snapshot-list"
     [:map
      [:req-id :string]
      [:action :string]
      [:graph-uuid :string]]]
    ["snapshot-graph"
     [:map
      [:req-id :string]
      [:action :string]
      [:graph-uuid :string]]]
    ["grant-access"
     [:map
      [:req-id :string]
      [:action :string]
      [:graph-uuid :uuid]
      [:target-user-uuids {:optional true} [:sequential :uuid]]
      [:target-user-emails {:optional true} [:sequential :string]]]]
    ["get-users-info"
     [:map
      [:req-id :string]
      [:action :string]
      [:graph-uuid :uuid]]]
    ["delete-graph"
     [:map
      [:req-id :string]
      [:action :string]
      [:graph-uuid :uuid]]]
    ["query-block-content-versions"
     [:map
      [:req-id :string]
      [:action :string]
      [:graph-uuid :string]
      [:block-uuids [:sequential :uuid]]]]
    ["calibrate-graph-skeleton"
     [:map
      [:req-id :string]
      [:action :string]
      [:graph-uuid :string]
      [:t :int]
      [:db-ident-blocks [:sequential
                         [:map
                          [:block/uuid :uuid]
                          [:db/ident :keyword]
                          [:block/parent {:optional true} :uuid]
                          [:block/type {:optional true} [:set :string]]
                          [:block/order {:optional true} :string]
                          [:block/content {:optional true} :string]]]]]]]))
(def data-to-ws-encoder (m/encoder data-to-ws-schema (mt/transformer
                                                      mt/string-transformer
                                                      (mt/key-transformer {:encode m/-keyword->string}))))
(def data-to-ws-coercer (m/coercer data-to-ws-schema mt/string-transformer nil #(m/-fail! ::data-to-ws-schema %)))
