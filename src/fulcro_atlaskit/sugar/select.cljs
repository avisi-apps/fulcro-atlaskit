(ns fulcro-atlaskit.sugar.select
  (:require [fulcro-atlaskit.select :as select]
            [goog.object :as gobj]
            [cljs-bean.core :refer [->js]]
            [edn-query-language.core :as eql]
            [com.fulcrologic.fulcro.routing.dynamic-routing]
            [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
            [com.fulcrologic.guardrails.core :refer [>defn >def => ?]]
            [com.fulcrologic.fulcro.algorithms.react-interop]
            [com.fulcrologic.fulcro.ui-state-machines :as uism :refer [defstatemachine]]
            [com.fulcrologic.fulcro.application :as app]
            [com.fulcrologic.fulcro-css.localized-dom :as dom]
            [cljs.spec.alpha :as s]))

(>def ::option-class comp/component-class?)
(>def ::server-property keyword?)
(>def ::debounce-timeout number?)
(>def ::filtering #{:local :remote})

(defn component-option [env prop]
  (-> env
      (uism/actor-class :actor/wrapper)
      (comp/component-options prop)))

(>defn option-class
  [env]
  [::uism/env => ::option-class]
  (component-option env ::option-class))

(>defn server-property
  [env]
  [::uism/env => ::server-property]
  (component-option env ::server-property))

(>defn debounce-timeout
  [env]
  [::uism/env => ::debounce-timeout]
  (or
   (component-option env ::debounce-timeout)
   300))

(>defn filtering-type
  [env]
  [::uism/env => ::filtering]
  (component-option env ::filtering))

(defn local-filtering? [env]
  (= (filtering-type env) :local))

(defn remote-filtering? [env]
  (= (filtering-type env) :remote))

(defn handle-event-typing [{{:keys [new-filter-value]} ::uism/event-data :as env}]
  (when (remote-filtering? env)
    (app/abort! (::uism/fulcro-app env) (::uism/asm-id env)))
  (-> env
      (uism/activate :debouncing)
      (uism/assoc-aliased :filter-value new-filter-value)
      (uism/assoc-aliased :open? true)
      (uism/assoc-aliased :loading? false)
      (uism/set-timeout :timer/typing-timeout
                        :event/load-options
                        {}
                        (debounce-timeout env)
                        #{:event/update-filter-value})))

(defn load-options [env]
  (let [Option (option-class env)
        server-property (server-property env)
        initialized? (uism/alias-value env :results-initialized?)]
    (if (and (local-filtering? env) initialized?)
      env
      (-> env
          (uism/assoc-aliased :failed? false
                              :loading? true
                              :results-initialized? true)
          (uism/activate :loading)
          (uism/load server-property
                     Option
                     {:target (uism/resolve-alias env :results)
                      :params {:filter-value (uism/alias-value env :filter-value)}
                      :only-refresh [(uism/actor->ident env :actor/results)]
                      ::uism/ok-event :event/load-ok
                      ::uism/error-event :event/load-failed})))))

(defn activate-loading-or-closed [env]
  (let [open? (uism/alias-value env :open?)
        closed? (not open?)]
    (cond-> env
            open? (uism/activate :open)
            closed? (uism/activate :closed))))

(defn handle-load-ok [env]
  (-> env
      (uism/assoc-aliased :loading? false)
      (activate-loading-or-closed)))

(defn handle-load-failed [env]
  (-> env
      (uism/assoc-aliased :loading? false)
      (uism/assoc-aliased :error? true)
      (activate-loading-or-closed)))

(def global-events {:event/gc {::uism/handler uism/exit}
                    :event/load-ok {::uism/target-states #{:open :closed}
                                    ::uism/handler handle-load-ok}
                    :event/load-failed {::uism/target-states #{:open :closed}
                                        ::uism/handler handle-load-failed}
                    :event/close {::uism/target-state :closed
                                  ::uism/handler (fn [env]
                                                   (-> env
                                                       (uism/assoc-aliased :open? false)))}})

(defstatemachine select-machine
  {::uism/actor-names #{:actor/select :actor/results :actor/wrapper}
   ::uism/aliases {:loading? [:actor/select :ui/loading?]
                   :failed? [:actor/select :ui/failed?]
                   :filter-value [:actor/select :ui/filter-value]
                   :filtering-type [:actor/select :ui/filtering]
                   :results [:actor/results :ui/options]
                   :results-initialized? [:actor/results :ui/initialized?]
                   :open? [:actor/select :ui/open?]}
   ::uism/states {:initial {::uism/events {::uism/started {::uism/handler
                                                           (fn [env]
                                                             (let [filtering (filtering-type env)]
                                                               (cond-> env
                                                                       true (uism/set-aliased-value :filtering-type filtering)
                                                                       true (uism/activate :closed)
                                                                       (= filtering :local) load-options)))}}}
                  :closed {::uism/events
                           (merge global-events
                                  {:event/open {::uism/target-state :open
                                                ::uism/handler (fn [env]
                                                                 (-> env
                                                                     (uism/assoc-aliased :open? true)
                                                                     load-options))}})}
                  :open {::uism/events
                         (merge global-events
                                {:event/update-filter-value {::uism/target:thu-state :debouncing
                                                             ::uism/handler handle-event-typing}})}
                  :debouncing {::uism/events (merge global-events
                                                    {:event/update-filter-value {::uism/target-states :debouncing
                                                                                 ::uism/handler handle-event-typing}
                                                     :event/load-options {::uism/target-state :loading
                                                                          ::uism/handler load-options}
                                                     :event/load-ok {::uism/handler identity}})}
                  :loading {::uism/events (merge global-events
                                                 {:event/update-filter-value {::uism/target-state :debouncing
                                                                              ::uism/handler handle-event-typing}})}}})

(defn update-filter! [component uism-id new-filter-value]
  (uism/trigger! component
                 uism-id
                 :event/update-filter-value
                 {:new-filter-value new-filter-value
                  ::uism/transact-options {:only-refresh [(comp/get-ident component)]}}))

(>def ::on-select-mutation (s/and
                      list?
                      #(= (count %) 2)))

(defn add-ident-to-tx-args [tx ident]
  [::on-select-mutation (? eql/ident?) => ::on-select-mutation]
  (map
   #(cond-> %
            (map? %) (assoc :ident ident))
   tx))

(>defn handle-select!
  [component tx ident]
  [comp/component-instance? ::on-select-mutation (? eql/ident?) => any?]
  (comp/transact! component [(-> tx
                                 (add-ident-to-tx-args ident))]))

(defsc Select [this {:ui/keys [failed? open? filtering loading?]} {::keys [react-select-props options selected uism-id field-props
                                                                           on-select-mutation]
                                                                   :or {options []
                                                                        field-props #js {}
                                                                        react-select-props {}}}]
  {:initLocalState (fn [] {:filter-value ""})
   :css [[:.red {:color "red"}]]
   :initial-state (fn [m]
                    (merge
                     {:ui/filter-value ""
                      :ui/open? false
                      :ui/loading? false
                      :ui/failed? false
                      ::id (gensym)}
                     m))
   :ident ::id
   :query [::id
           :ui/open?
           :ui/filter-value
           :ui/filtering
           :ui/failed?
           :ui/loading?]}
  (let [remote-filtering? (= filtering :remote)]
    (select/ui-select
     (fulcro-atlaskit.utils/js-spread
      field-props
      (->js
       (merge
        (cond->
         {:inputId (gobj/get field-props "id")
          :onInputChange (fn [value action]
                           (comp/update-state! this assoc :filter-value value)
                           (update-filter! this uism-id value))
          :maxMenuHeight 200
          :spacing "compact"
          :menuPlacement "auto"
          :onMenuOpen (fn []
                        (when-not open?
                          (uism/trigger! this uism-id :event/open)))
          :onMenuClose #(when open?
                          (uism/trigger! this uism-id :event/close))
          :noOptionsMessage (fn [opts]
                              (let [input-value (gobj/get opts "inputValue")]
                                (if failed?
                                  (comp/with-parent-context this
                                                            (dom/span :.red (str "Failed loading results for: " input-value)))
                                  (str "No results found for: " input-value))))
          :onChange (fn [option, action]
                      (case (gobj/get action "action")
                        "clear" (handle-select! this on-select-mutation nil)
                        "select-option" (handle-select! this on-select-mutation (gobj/get option "ident"))
                        nil))
          :inputValue (or (comp/get-state this :filter-value) "")
          :value selected
          :isLoading loading?
          :options (if-not failed? options [])
          :getOptionLabel (fn [x]
                            (gobj/get x "label"))
          :hideSelectedOptions true
          :menuIsOpen open?}
         remote-filtering? (assoc :filterOption nil))
        react-select-props))))))

(def ui-select (comp/factory Select))

(defn start-select! [component {::keys [results-actor
                                        id] }]
  (uism/begin!
   component
   select-machine
   id
   {:actor/select (uism/with-actor-class [::id id] Select)
    :actor/wrapper  component
    :actor/results results-actor}))

(defn stop-select! [component uism-id]
  (uism/trigger! component uism-id :event/gc))