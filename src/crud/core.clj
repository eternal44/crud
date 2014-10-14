(ns crud.core
  "Maps datomic attribute definitions to prismatic schemata
and vice versa"
  (:require [datomic.api :as d]
            [schema.core :as s :refer [Str Num Inst Int Bool Keyword]]
            [schema.coerce :refer [coercer string-coercion-matcher]]
            [integrity.datomic :as dat]
            [compojure.core :as http]
            [compojure.route :as route]
            [liberator.core :as rest :refer [by-method]]
            [ring.util.response :as resp])
  (:import [java.net URL URI]))

(defn find-referrer [referrer refs]
  (first (filter #(= (:referrer %) referrer) refs)))

(defn find-entities [db params]
  "Find all entities in `db` where the predicates specified by `params` are true" 
  (let [build-predicate (fn [[k v]] ['?e k v])
        q {:find '[?e]
           :in '[$]
           :where (map build-predicate params)}]
    (map (partial d/entity db) (apply concat (d/q q db)))))

(defn branch? [v]
  (some #{(class v)} [clojure.lang.PersistentArrayMap clojure.lang.PersistentHashMap]))

(defn as-response [entity schema refs]
  "Walk `entity` using the specified `schema` to decide which attributes to traverse. Use refs to specify
which attributes are expected to be datomic refs. The values returned for those refs will be transformed
via the :as-response key of the corresponding ref"
  (letfn [(walk-entity [entity schema]
            (reduce (fn [m [schema-name schema-type]]
                      (if (branch? schema-type)
                        (merge m {schema-name (walk-entity (schema-name entity)
                                                           {schema-name schema-type})})
                        (merge m (if-let [ref (find-referrer schema-name refs)]
                                   {schema-name ((:as-response ref) entity)}
                                   {schema-name (schema-name entity)}))))
                    {}
                    schema))]
    (walk-entity entity (seq schema))))

(defn as-facts
  ([tmp-id object refs]
     "Generates datomic facts by recursively walking the specified map converting
(key val) -> [:db/add tmp-id attr val]

For any values that are themselves maps, we recur on the map and add a ref for
the current key"
     (reduce (fn [acc [k v]]
               (if (branch? v)
                 (into acc (let [ref-id (d/tempid :db.part/user)
                                 ret (concat [[:db/add tmp-id k ref-id]]
                                             (as-facts ref-id (into {} v)))]
                             ret))
                 (into acc [[:db/add tmp-id k (if-let [ref (find-referrer k refs)]
                                                (condp instance? v
                                                  datomic.db.DbId v
                                                  ((:as-lookup-ref ref) v))
                                                v)]])))
             []
             (seq object)))
  ([objects]
     (mapcat (fn [obj]
               (as-facts (d/tempid :db.part/user) obj []))
             objects)))

(defn apply-tx [c facts]
  "Apply a transaction against `c` using the facts generated by calling `as-facts` with `params`"
  @(d/transact c facts))

(def ^{:private true} type-map
  {Str                      :db.type/string
   Bool                     :db.type/boolean
   Long                     :db.type/long
   ;java.Math.BigInteger     :db.type/bigint
   Num                      :db.type/double
   Int                      :db.type/long
   Float                    :db.type/float
   Inst                     :db.type/instant

   URI                      :db.type/string})

(defn datomic-schema [schema uniqueness refs]
  "Generate datomic attributes for the specified resource.

If `uniqueness` is specified, it should be a hash where each key means
  the attribute with that name is marked as unique in datomic. The
  value can be used to determine the type of uniqueness. For details
  about the different types of uniqueness, refer to the datomic
  documentation which can be found at

http://docs.datomic.com/identity.html

If `refs` is specified, it should be a sequence of attributes which
represent references to other entities. For each ref, at attribute of type
:db.type/ref will be generated."
  (letfn [(generate-ref [k v]
            (merge (generate-attr k v)
                   {:db/valueType :db.type/ref}))
          
          (generate-attr [k v]
            (let [cardinality (if (vector? v)
                                :db.cardinality/many :db.cardinality/one)
                  value-type (get type-map v :db.type/string)]
              (merge
               {:db/id (d/tempid :db.part/db)
                :db/ident k
                :db/valueType value-type
                :db/cardinality cardinality
                :db.install/_attribute :db.part/db}
               (if-let [uniq (k uniqueness)] {:db/unique uniq} {}))))

          (reducer [acc [k v]]
            (cond
             (find-referrer k refs)
             (into acc [(generate-ref k v)])

             (branch? v)
             (into acc (conj (generate-attrs (into {} v))
                             ((:attr-factory dat/Ref) k)))

             :else
             (into acc [(generate-attr k v)])))
          
          (generate-attrs [schema]
            (reduce reducer [] (seq schema)))]
    (generate-attrs schema)))

(defn only
  "Return the only item from a query result"
  [query-result]
  (assert (= 1 (count query-result)))
  (assert (= 1 (count (first query-result))))
  (ffirst query-result))

(defn qe
  "Returns the single entity returned by a query."
  [query db & args]
  (let [res (apply d/q query db args)]
    (if (empty? res)
      nil
      (d/entity db (only res)))))

(defn find-by
  "Returns the unique entity identified by attr and val."
  [db attr val]
  (qe '[:find ?e
        :in $ ?attr ?val
        :where [?e ?attr ?val]]
      db (d/entid db attr) val))

(defn find-entity [db params]
  (let [build-predicate (fn [[k v]] ['?e k v])
        q {:find '[?e]
           :in '[$]
           :where (map build-predicate params)}]
    (map (partial d/entity db) (apply concat (d/q q db)))))

;; HTTP Helpers
(defn get? [ctx]
  (= :get (get-in ctx [:request :request-method])))

(defn known-content-type? [ctx]
  (if (= "application/edn" (get-in ctx [:request :content-type]))
    true
    [false {:error "Unsupported content type"}]))

(defn coerce-id [schema id]
  (let [c (coercer (apply hash-map (find schema :id)) string-coercion-matcher)]
    (:id (c {:id id}))))

;; Schema Helpers
(defn build-ref [resource referrer referent]
  "Builds a reference to the `referent` attribute of `resource` from the `referrer` attribute of
the current context"
  {:referrer referrer
   :referent referent
   :resource resource
   :as-response (fn [entity]
                  (format "%s/%s"
                          (:name resource)
                          ((comp referent referrer) entity)))
   :as-lookup-ref (fn [uri]
                    (let [[_ id] (take-last 2 (clojure.string/split (.getPath (URI. uri)) #"/"))
                          coerce (coercer (apply hash-map (find (:schema resource) referent))
                                          string-coercion-matcher)]
                      [referent (referent (coerce {referent id}))]))})
      
(defn optionalize [schema]
  "TODO: consider optionalizing recursively"
  (into {} (map (fn [[name type]]
                  [(s/optional-key name) type])
                (seq schema))))

;;## CRUD Protocol
;;
;; This file defines the basic protocol for handling CRUD HTTP requests in a generic way. The "!" at the end of a
;; method name below indicates that it is likely to add new facts (or replace existing ones). Each of the functions
;; here return a function designed to be used in the liberator web-machine. That is, they accept a context map
;; containing the the HTTP request and response along with any other keys added by prior steps in the machine

(defn find-by-id [db id path]
  (fn [ctx]
    (if-let [entity (find-by db :id id)]
      [true (assoc-in ctx path entity)]
      [false (assoc-in ctx [::parsed-input :id] id)])))

(defn creator! [c refs]
  (fn [ctx]
    (apply-tx c (as-facts (d/tempid :db.part/user)
                          (get-in ctx [::valid-parsed-input]) refs))))

(defn destroyer! [c]
  (fn [ctx]
    (if-let [e (:entity ctx)]
      (apply-tx c [[:db.fn/retractEntity (:db/id e)]])
      (throw (Exception. "wtf????"))))) 

(defn validator [schema]
  (let [parsed-input-path [::parsed-input]
        valid-input-path [::valid-parsed-input]
        error-input-path [::validation-error]]
    (fn [ctx]
      (let [validate-with (fn [s]
                            (let [validator (coercer s string-coercion-matcher)
                                  validated (validator (get-in ctx parsed-input-path))]
                              (if (schema.utils/error? validated)
                                [false (assoc-in {} error-input-path validated)]
                                [true (assoc-in {} valid-input-path validated)])))]
        (if (get? ctx)
          (validate-with (optionalize schema))
          (validate-with schema))))))

(defn malformed? [ctx]
  (let [input-path [:request :body]
        output-path [::parsed-input]]
    (try
      (let [body-as-str (if-let [body (get-in ctx input-path)]
                          (condp instance? body
                            java.lang.String body
                            (slurp (clojure.java.io/reader body))))]
        [false (assoc-in {} output-path (clojure.edn/read-string body-as-str))])
      (catch RuntimeException e
        [true {:representation {:media-type "application/edn"}
               :parser-error (.getLocalizedMessage e)}]))))

(defn redirector [ctx]
  (let [request (get-in ctx [:request])]
    (URL. (format "%s://%s:%s%s/%s"
                  (name (:scheme request))
                  (:server-name request)
                  (:server-port request)
                  (:uri request)
                  (get-in ctx [::valid-parsed-input :id])))))

(defn handler [db cardinality resource]
  (let [{:keys [schema refs]} resource
        input-path [::valid-parsed-input]
        handle (fn [e] (as-response e schema refs))]
    (fn [ctx]
      (let [entities (find-entities db (get-in ctx input-path))]
        (case cardinality
          :collection (into [] (map handle entities))
          :single (handle (first entities)))))))

(defn api-routes [cnx definition]
  (let [{:keys [name schema uniqueness refs]} definition
        with-overrides (fn [& b] (merge (apply hash-map b) definition))]
    (http/routes
     ;; a collection of resources can be queried, or appended to
     (http/ANY "/" []
       (rest/resource
        (with-overrides
          :available-media-types ["application/edn"]
          :allowed-methods       [:get :post]
          :known-content-type?   known-content-type?
          :malformed?            malformed?
          :processable?          (validator schema)
          :post!                 (creator! cnx refs)
          :post-redirect         true
          :location              redirector
          :handle-ok             (handler (d/db cnx) :collection definition)
          :handle-created        (pr-str "Created.")
          :handle-unprocessable-entity (comp schema.utils/error-val ::validation-error))))

     (http/GET "/:id" [id]
       (rest/resource
        (with-overrides
          :allowed-methods              [:get :patch :put :delete]
          :available-media-types        ["application/edn"]
          :known-content-type?          known-content-type?
          :malformed?                   malformed?
          :processable?                 (comp (validator schema)
                                              (fn [ctx]
                                                (assoc-in ctx [::parsed-input :id] id)))
          :exists?                      (if-let [id (coerce-id schema id)]
                                          (find-by-id (d/db cnx) id [:entity]))
          :new?                         (fn [ctx]
                                          (not (:entity ctx)))
          :handle-not-found             (fn [_]
                                          {:error (str "Could not find " name " with id: " id)})
          :can-put-to-missing?          true
          :put!                         (creator! cnx refs)
          :handle-malformed             (fn [ctx]
                                          {:error (:parser-error ctx)})
          :handle-ok                    #(as-response (:entity %) schema refs)
          :handle-created               (pr-str "Created.")
          :handle-unprocessable-entity  (comp schema.utils/error-val ::validation-error))))

     (http/PUT "/:id" [id]
       (rest/resource
        (with-overrides
          :allowed-methods       [:get :patch :put :delete]
          :available-media-types ["application/edn"]
          :known-content-type?   known-content-type?
          :malformed?            malformed?
          :processable?          (comp (validator schema)
                                       (fn [ctx]
                                         (assoc-in ctx [::parsed-input :id] id)))
          :exists?               (if-let [id (coerce-id schema id)]
                                   (find-by-id (d/db cnx) id [:entity]))
          :new?                  (fn [ctx]
                                   (not (:entity ctx)))
          :handle-not-found      (fn [_]
                                   {:error (str "Could not find " name " with id: " id)})
          :can-put-to-missing?   true
          :put!                  (creator! cnx refs)
          :handle-malformed      (fn [ctx]
                                   {:error (:parser-error ctx)})
          :handle-ok             #(as-response (:entity %) schema refs)
          :handle-created        (pr-str "Created.")
          :handle-unprocessable-entity (comp schema.utils/error-val ::validation-error))))

     (http/PATCH "/:id" [id]
       (rest/resource
        (with-overrides
          :allowed-methods       [:get :patch :put :delete]
          :available-media-types ["application/edn"]
          :known-content-type?   known-content-type?
          :malformed?            malformed?
          :processable?          (comp (validator (optionalize schema))
                                       (fn [ctx]
                                         (assoc-in ctx [::parsed-input :id] id)))
          :exists?               (if-let [id (coerce-id schema id)]
                                   (find-by-id (d/db cnx) id [:entity]))
          :new?                  (fn [ctx]
                                   (not (:entity ctx)))
          :handle-not-found      (fn [_]
                                   {:error (str "Could not find " name " with id: " id)})
          :can-put-to-missing?   true
          :patch!                (creator! cnx refs)
          :handle-malformed      (fn [ctx]
                                   {:error (:parser-error ctx)})
          :handle-ok             #(as-response (:entity %) schema refs)
          :handle-created        (pr-str "Created.")
          :handle-unprocessable-entity (comp schema.utils/error-val ::validation-error))))
     
     (http/DELETE "/:id" [id]
       (rest/resource
        (with-overrides
          :allowed-methods       [:get :patch :put :delete]
          :available-media-types ["application/edn"]
          :known-content-type?   known-content-type?
          :exists?               (if-let [id (coerce-id schema id)]
                                   (find-by-id (d/db cnx) id [:entity]))
          :delete!               (destroyer! cnx)
          :handle-not-found      (fn [_]
                                   {:error (str "Could not find " name " with id: " id)})
          :handle-no-content     (pr-str "Deleted.")))))))

                               ;; :new?                (has-path? [::entity])

                               ;; :delete!             (deleter! cnx [::entity])

