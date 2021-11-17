(ns re-statecharts.core
  (:require
   [re-frame.core :as f]
   [statecharts.clock :as clock]
   [statecharts.core :as fsm]
   [statecharts.delayed :as delayed]
   [statecharts.integrations.re-frame :as sc.rf]
   [statecharts.utils :as u])
  (:require-macros re-statecharts.core))

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

(f/reg-event-db
 ::init
  (fn [db [_ {:keys [id] :as machine} initialize-args]]
    (when-not (get-state db id)
      (let [new-state (-> (fsm/initialize machine initialize-args)
                          (assoc :_epoch (new-epoch id)))]
        (set-state db id new-state)))))

(f/reg-event-db
 ::restart
  (fn [db [_ {:keys [id] :as machine}]]
    (let [new-state (-> (fsm/initialize machine)
                        (assoc :_epoch (new-epoch id)))]
      (set-state db id new-state))))

(defn enrich
  [id fsm opts]
  (f/->interceptor
   :id id
   :before (fn enrich-before
             [context]
             (let [[event-id fsm-id & args] (f/get-coeffect context :event)]
               (cond

                 (and (= event-id ::transition)
                      (= fsm-id id))
                 (f/assoc-coeffect context :event (into [event-id fsm opts] args))

                 (and (= event-id ::restart)
                      (= fsm-id id))
                 (f/assoc-coeffect context :event (into [event-id fsm] args))

                 :else
                 context)))))

(f/reg-event-db
 ::transition
  (fn [db [_ {:keys [id epoch?] :as machine} opts fsm-event data :as args]]
    (when-let [current-state (get-state db id)]
      (let [fsm-event (u/ensure-event-map fsm-event)
            more-data (when (> (count args) 4)
                        (subvec args 3))]
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
            (set-state db id next-state)))))))

(deftype Scheduler [fsm-id ids clock]
  delayed/IScheduler
  (schedule [_ event delay]
    (let [id (clock/setTimeout clock #(f/dispatch [::transition fsm-id event]) delay)]
      (swap! ids assoc event id)))

  (unschedule [_ event]
    (when-let [id (get @ids event)]
      (clock/clearTimeout clock id)
      (swap! ids dissoc event))))

(defn integrate
  ([machine]
   (integrate machine sc.rf/default-opts))
  ([{:keys [id] :as machine} {:keys [clock] :as opts}]
   (let [clock   (or clock (clock/wall-clock))
         machine (assoc machine :scheduler (Scheduler. id (atom {}) clock))]
     (f/dispatch [::init machine])
     (f/reg-global-interceptor (enrich id machine (:transition-opts opts))))))

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
