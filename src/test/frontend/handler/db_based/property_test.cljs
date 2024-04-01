(ns frontend.handler.db-based.property-test
  (:require [frontend.handler.db-based.property :as db-property-handler]
            [frontend.db :as db]
            [clojure.test :refer [deftest is testing are use-fixtures]]
            [frontend.test.helper :as test-helper]
            [datascript.core :as d]
            [frontend.state :as state]
            [frontend.handler.page :as page-handler]
            [frontend.handler.editor :as editor-handler]))

(def repo test-helper/test-db-name-db-version)

(def init-data (test-helper/initial-test-page-and-blocks))
(defn start-and-destroy-db
  [f]
  (test-helper/db-based-start-and-destroy-db
   f
   {:init-data (fn [conn] (d/transact! conn init-data))}))

;; init page id
;; (def pid (:block/uuid (first init-data)))
;; first block id
(def fbid (:block/uuid (second init-data)))
(def sbid (:block/uuid (nth init-data 2)))

(use-fixtures :each start-and-destroy-db)

;; set-block-property!
;; delete-property-value!
;; remove-block-property!
;; batch-set-property!
;; batch-remove-property!
;; upsert-property!
;; update-property!
(deftest ^:large-vars/cleanup-todo block-property-test
  (testing "Add a property to a block"
    (db-property-handler/set-block-property! repo fbid "property-1" "value" {})
    (let [block (db/entity [:block/uuid fbid])
          properties (:block/properties block)
          property (db/entity [:block/name "property-1"])]
      ;; ensure property exists
      (are [x y] (= x y)
        (:block/schema property)
        {:type :default}
        (:block/type property)
        #{"property"})
      ;; check block's properties
      (are [x y] (= x y)
        (count properties)
        1
        (uuid? (ffirst properties))
        true
        (second (first properties))
        "value")))

  (testing "Add another property"
    (db-property-handler/set-block-property! repo fbid "property-2" "1" {})
    (let [block (db/entity [:block/uuid fbid])
          properties (:block/properties block)
          property (db/entity [:block/name "property-2"])]
      ;; ensure property exists
      (are [x y] (= x y)
        (:block/schema property)
        {:type :number}
        (:block/type property)
        #{"property"})
      ;; check block's properties
      (are [x y] (= x y)
        (count properties)
        2
        (every? uuid? (map first properties))
        true
        (second (second properties))
        1)))

  (testing "Update property value"
    (db-property-handler/set-block-property! repo fbid "property-2" 2 {})
    (let [block (db/entity [:block/uuid fbid])
          properties (:block/properties block)]
      ;; check block's properties
      (are [x y] (= x y)
        (count properties)
        2
        (second (second properties))
        2)))

  (testing "Wrong type property value shouldn't transacted"
    (db-property-handler/set-block-property! repo fbid "property-2" "Not a number" {})
    (let [block (db/entity [:block/uuid fbid])
          properties (:block/properties block)]
      ;; check block's properties
      (are [x y] (= x y)
        (count properties)
        2
        (second (second properties))
        2)))

  (testing "Add a multi-values property"
    (db-property-handler/upsert-property! repo "property-3" {:type :number :cardinality :many} {})
    (db-property-handler/set-block-property! repo fbid "property-3" 1 {})
    (db-property-handler/set-block-property! repo fbid "property-3" 2 {})
    (db-property-handler/set-block-property! repo fbid "property-3" 3 {})
    (let [block (db/entity [:block/uuid fbid])
          properties (:block/properties block)
          property (db/entity [:block/name "property-3"])]
      ;; ensure property exists
      (are [x y] (= x y)
        (:block/schema property)
        {:type :number :cardinality :many}
        (:block/type property)
        #{"property"})
      ;; check block's properties
      (are [x y] (= x y)
        (count properties)
        3
        (get properties (:block/uuid property))
        #{1 2 3}))

    ;; update property value from 1 to 4
    (db-property-handler/set-block-property! repo fbid "property-3" 4 {:old-value 1})
    (let [block (db/entity [:block/uuid fbid])
          properties (:block/properties block)
          property (db/entity [:block/name "property-3"])]
      ;; check block's properties
      (are [x y] (= x y)
        (count properties)
        3
        (get properties (:block/uuid property))
        #{4 2 3})

      (db-property-handler/delete-property-value! repo block (:block/uuid property) "value 4")
      (let [properties (:block/properties block)]
        (is (get properties (:block/uuid property))
            #{"value 2" "value 3"}))))

  (testing "Remove a property"
    (db-property-handler/remove-block-property! repo fbid "property-3")
    (let [block (db/entity [:block/uuid fbid])
          properties (:block/properties block)
          property (db/entity [:block/name "property-3"])]
      ;; check block's properties
      (are [x y] (= x y)
        (count properties)
        2
        (contains? (set (keys properties)) (:block/uuid property))
        false)))

  (testing "Batch set properties"
    (let [k "property-4"
          v "batch value"]
      (db-property-handler/batch-set-property! repo [fbid sbid] k v)
      (let [fb (db/entity [:block/uuid fbid])
            sb (db/entity [:block/uuid sbid])]
        (are [x y] (= x y)
          (get (:block/properties fb) (:block/uuid (db/entity [:block/name k])))
          v
          (get (:block/properties sb) (:block/uuid (db/entity [:block/name k])))
          v))))

  (testing "Batch remove properties"
    (let [k "property-4"]
      (db-property-handler/batch-remove-property! repo [fbid sbid] k)
      (let [fb (db/entity [:block/uuid fbid])
            sb (db/entity [:block/uuid sbid])]
        (are [x y] (= x y)
          (count (:block/properties fb))
          2
          (count (:block/properties sb))
          0)))))

;; class related
;; class-add-property!
;; class-remove-property!
;; class-set-schema!
;; get-block-classes-properties
(deftest property-class-test
  (let [opts {:redirect? false :create-first-block? false :class? true}
        _ (page-handler/create! "class1" opts)
        _ (page-handler/create! "class2" opts)
        _ (page-handler/create! "class3" opts)
        c1 (db/entity [:block/name "class1"])
        c2 (db/entity [:block/name "class2"])
        c1id (:block/uuid c1)
        c2id (:block/uuid c2)]

    (testing "Create classes"
      (are [x y] (= x y)
        (:block/type (db/entity [:block/name "class1"]))
        #{"class"}
        (:block/type (db/entity [:block/name "class2"]))
        #{"class"}))

    (testing "Class add property"
      (db-property-handler/class-add-property! repo c1id "property-1")
      (db-property-handler/class-add-property! repo c1id "property-2")
      ;; repeated adding property-2
      (db-property-handler/class-add-property! repo c1id "property-2")
      (is (= 2 (count (:properties (:block/schema (db/entity (:db/id c1))))))))

    (testing "Class remove property"
      (db-property-handler/class-remove-property! repo c1id (:block/uuid (db/entity [:block/name "property-1"])))
      (is (= 1 (count (:properties (:block/schema (db/entity (:db/id c1))))))))
    (testing "Add classes to a block"
        (test-helper/save-block! repo fbid "Block 1" {:tags ["class1" "class2" "class3"]})
        (is (= 3 (count (:block/tags (db/entity [:block/uuid fbid]))))))
    ;; FIXME: @tiensonqin https://github.com/logseq/logseq/commit/575624c650b2b7e919033a79aa5d14b97507d86f
    #_(testing "Remove a class from a block"
      ;; make sure class2 will not be deleted when removing it from the first block
        (editor-handler/save-block! repo sbid "Block 2 #class2")
        (editor-handler/save-block! repo fbid "Block 1 #class1 #class3")
        (is (= 2 (count (:block/tags (db/entity [:block/uuid fbid]))))))
    (testing "Get block's classes properties"
      ;; set c2 as parent of c3
      (let [c3 (db/entity [:block/name "class3"])]
        (db/transact! [{:db/id (:db/id c3)
                        :class/parent (:db/id c2)}]))
      (db-property-handler/class-add-property! repo c2id "property-3")
      (db-property-handler/class-add-property! repo c2id "property-4")
      (is (= 3 (count (:classes-properties
                       (db-property-handler/get-block-classes-properties (:db/id (db/entity [:block/uuid fbid]))))))))))


;; property-create-new-block
;; get-property-block-created-block
(deftest text-block-test
  (testing "Add property and create a block value"
    (let [repo (state/get-current-repo)
          fb (db/entity [:block/uuid fbid])
          k "property-1"]
      ;; add property
      (db-property-handler/upsert-property! repo k {:type :default} {})
      (let [property (db/entity [:block/name k])
            {:keys [last-block-id]} (db-property-handler/create-property-text-block! fb property "Block content" editor-handler/wrap-parse-block {})
            {:keys [from-block-id from-property-id]} (db-property-handler/get-property-block-created-block [:block/uuid last-block-id])]
        (is (= from-block-id (:db/id fb)))
        (is (= from-property-id (:db/id property)))))))

;; convert-property-input-string
(deftest convert-property-input-string
  (testing "Convert property input string according to its schema type"
    (let [test-uuid (random-uuid)]
      (are [x y]
           (= (let [[schema-type value] x]
                (db-property-handler/convert-property-input-string schema-type value)) y)
        [:number "1"] 1
        [:number "1.2"] 1.2
        [:page (str test-uuid)] test-uuid
        [:url test-uuid] test-uuid
        [:date test-uuid] test-uuid
        [:any test-uuid] test-uuid
        [nil test-uuid] test-uuid))))

;; replace-key-with-id
(deftest replace-key-with-id-test
  (db-property-handler/upsert-property! repo "property 1" {:type :default} {})
  (db-property-handler/upsert-property! repo "property 2" {:type :default} {})
  (testing "Replace property key with its uuid"
    (let [result (db-property-handler/replace-key-with-id {"property 1" "value 1"
                                                           "property 2" "value 2"})]
      (is (every? uuid? (keys result)))))
  (testing "Throw an error if a property doesn't exists"
    (is (thrown? js/Error (db-property-handler/replace-key-with-id {"property not exists yet" "value 1"})))))

;; collapse-expand-property!
(deftest collapse-expand-property-test
  (testing "Collapse and expand property"
    (let [repo (state/get-current-repo)
          fb (db/entity [:block/uuid fbid])
          k "property-1"]
      ;; add property
      (db-property-handler/upsert-property! repo k {:type :default} {})
      (let [property (db/entity [:block/name k])]
        (db-property-handler/create-property-text-block! fb property "Block content" editor-handler/wrap-parse-block {})
        ;; collapse property-1
        (db-property-handler/collapse-expand-property! repo fb property true)
        (is (=
             [(:db/id property)]
             (map :db/id (:block/collapsed-properties (db/entity [:block/uuid fbid])))))

        ;; expand property-1
        (db-property-handler/collapse-expand-property! repo fb property false)
        (is (nil? (:block/collapsed-properties (db/entity [:block/uuid fbid]))))))))

;; template (TBD, template implementation not settle down yet)
;; property-create-new-block-from-template

#_(cljs.test/run-tests)
