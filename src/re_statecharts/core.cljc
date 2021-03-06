(ns re-statecharts.core
  (:require
   [clojure.spec.alpha :as s]
   [re-frame.core :as f]
   [statecharts.clock :as clock]
   [statecharts.core :as fsm]
   [statecharts.delayed :as delayed]
   [statecharts.integrations.re-frame :as sc.rf]
   [statecharts.utils :as u]))

(defmulti get-state (fn [db id]
                      (type id)))

(defmulti set-state (fn [db id new-state]
                      (type id)))

(defmethod get-state :default
  [db id]
  (get-in db [::fsm-state id]))

(defmethod set-state :default
  [db id new-state]
  (if new-state
    (assoc-in db [::fsm-state id] new-state)
    (update db ::fsm-state dissoc id)))

(defonce epochs (volatile! {}))

(defn new-epoch [id]
  (get (vswap! epochs update id sc.rf/safe-inc) id))

(f/reg-event-fx
 ::init
  (fn [{db :db} [_ {:keys [id] :as machine} initialize-args]]
    (when-not (get-state db id)
      (let [new-state (-> (fsm/initialize machine initialize-args)
                          (assoc :_epoch (new-epoch id)))]
        {:db (set-state db id new-state)}))))

(f/reg-event-db
 ::restart
  (fn [db [_ {:keys [id] :as machine}]]
    (set-state db id (-> (fsm/initialize machine)
                         (assoc :_epoch (new-epoch id))))))

(defn transition [db {:keys [id epoch?] :as machine} opts fsm-event data more-data]
  (when-let [current-state (get-state db id)]
    (let [fsm-event (u/ensure-event-map fsm-event)]
      (if (and epoch?
               (sc.rf/should-discard fsm-event (:_epoch current-state)))
        (do
          (sc.rf/log-discarded-event fsm-event)
          db)
        (let [next-state (fsm/transition machine
                                         current-state
                                         (cond-> (assoc fsm-event :data data)
                                           (some? more-data)
                                           (assoc :more-data more-data))
                                         opts)]
          ;; TODO Debug logging for state changes
          (set-state db id next-state))))))

(defn open-interceptor
  [id fsm transition-opts]
  (f/->interceptor
   :id id

   :before (fn intercept-init
             [context]
             (let [[event-id fsm-id data & more-data] (f/get-coeffect context :event)]
               (cond-> context
                 (= [::restart fsm-id] [event-id id]) (f/assoc-coeffect :event (into [event-id fsm] (concat data more-data))))))

   :after (fn open-interceptor
            [context]
            (let [[event-id fsm-id data & more-data] (f/get-coeffect context :event)
                  db (or (f/get-effect context :db)
                         (f/get-coeffect context :db))]
              (cond-> context
                (and (= id fsm-id)
                     (not (#{::start ::stop ::init ::restart} event-id)))
                (f/assoc-effect :db (transition db fsm transition-opts event-id data more-data)))))))

(defn closed-interceptor
  [id fsm transition-opts]
  (f/->interceptor
   :id id

   :before (fn closed-interceptor
             [context]
             (let [[event-id fsm-id & args] (f/get-coeffect context :event)]
               (cond-> context

                 (= [::transition fsm-id] [event-id id])
                 (f/assoc-coeffect :event (into [event-id fsm transition-opts] args))

                 (= [::restart fsm-id] [event-id id])
                 (f/assoc-coeffect :event (into [event-id fsm] args)))))))

(f/reg-event-fx
 ::transition
  (fn [{db :db} [_ machine opts fsm-event data & more-data]]
    (when-let [new-db (transition db machine opts fsm-event data more-data)]
      {:db new-db})))

(deftype Scheduler [fsm-id ids clock open?]
  delayed/IScheduler
  (schedule [_ event delay]
    (let [id (clock/setTimeout clock #(f/dispatch (if open?
                                                    event
                                                    [::transition fsm-id event])) delay)]
      (swap! ids assoc event id)))

  (unschedule [_ event]
    (when-let [id (get @ids event)]
      (clock/clearTimeout clock id)
      (swap! ids dissoc event))))

(defn integrate
  ([machine]
   (integrate machine sc.rf/default-opts))
  ([{:keys [id] :as machine}
    {::keys [open?]
     :keys  [clock] :as opts}]
   (let [clock           (or clock (clock/wall-clock))
         machine         (assoc machine :scheduler (Scheduler. id (atom {}) clock open?))
         transition-opts (:transition-opts opts)]
     (f/dispatch [::init machine])
     (f/reg-global-interceptor (if open?
                                 (open-interceptor id machine transition-opts)
                                 (closed-interceptor id machine transition-opts))))))

(f/reg-fx ::start
  (fn [fsm]
    (let [machine (fsm/machine fsm)]
      (if-let [opts (meta fsm)]
        (integrate machine opts)
        (integrate machine)))))

(f/reg-fx ::stop
  (fn [id]
    (f/clear-global-interceptor id)))

(f/reg-event-fx ::start
  (fn [_ [_ fsm]]
    {::start fsm}))

(f/reg-event-fx ::stop
  (fn [{db :db} [_ id]]
    {:db    (set-state db id nil)
     ::stop id}))

(f/reg-sub ::state
  (fn [db [_ id]]
    (:_state (get-state db id))))

(f/reg-sub ::state-full
  (fn [db [_ id]]
    (get-state db id)))

(defn match-state [state & pairs]
  (loop [[first-pair & rest-pairs] (partition-all 2 pairs)]
    (cond

      (some-> first-pair seq count (= 2))
      (let [[value component] first-pair]
        (if (fsm/matches state value)
          component
          (recur rest-pairs)))

      (some-> first-pair seq count (= 1))
      (first first-pair)

      :else
      (throw (ex-info "Could not find a component to match state."
                      {:state state
                       :pairs pairs})))))

(s/def ::binding (s/and vector?
                        (s/cat :subscription-symbol symbol? :fsm any?)))

(defmacro with-fsm [binding & body]
  (let [parsed (s/conform ::binding binding)]
    (when (= ::s/invalid parsed)
      (throw (ex-info "with-fsm accepts exactly one binding pair, the subscription symbol and the FSM declaration."
                      (s/explain-data ::binding binding))))
    (let [{:keys [:subscription-symbol :fsm]} parsed]
      `(reagent.core/with-let [~subscription-symbol (f/subscribe [::state (:id ~fsm)])
                               _# (f/dispatch [::start ~fsm])]
         ~@body
         ~(list
           'finally
           ;; If we queue this event, it will break hot reloading as the stop will execute after the next start.
           `(re-frame.core/dispatch-sync [::stop (:id ~fsm)]))))))
