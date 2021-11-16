# Statecharts for re-frame

Provides an alternative to the built-in re-frame integration, and some added
utilities.

[![Clojars Project](https://img.shields.io/clojars/v/re-statecharts.svg)](https://clojars.org/re-statecharts)


# Learning by example

Let's start by looking at form validation as an example. Validation is simple enough, 
but you might want to make an effort to provide nice UX. Telling the user she did something wrong
before she even had a chance to do anything is not very nice. We can use an FSM to solve that by 
tracking the possible state the user can be in:

```clojure
(require '[re-statecharts.core :as rs])

(def validation-fsm
  {:id      :validation
   :initial ::clean
   :states  {::clean   {:on {::edit-started ::editing}}
             ::editing {:on {::edit-started ::editing
                             ::edit-ended   ::dirty}}
             ::dirty   {:on {::edit-started ::editing}}}})
```

The above code is an FSM as defined by clj-statecharts. It doesn't look like much, but tracking the same logic with ad
hoc state flags is often messier than you think. And we are probably covering some edge cases that you didn't think
about.

With the FSM in place, we can create a UI:

```clojure
(defn form []
  (rs/with-fsm [state validation-fsm]
    (r/with-let [text        (r/atom "")
                 update-text #(reset! text (-> % .-target .-value))]
      [:div
       (rs/match-state @state
                       ::editing  [:div "User is editing..."]
                       ::clean    [:div "No changes made yet"]
                       ::dirty    [:div
                                   "Form has been modified and is "
                                   (if (seq @text)
                                     "valid"
                                     [:span {:style {:color :red}} "invalid"])]
                       nil        [:div])
       [:input {:type      :text
                :on-change (fn [e] 
                              (f/dispatch [::rs/transition :validation ::edit-started])
                              (update-text e))
                :on-blur   #(f/dispatch [::rs/transition :validation ::edit-ended])}]])))
```

There' a lot going on here, so I'll walk through them in sequence
1. Since the FSM is only of interest to us within the scope of this component, we use the `with-fsm` macro, that 
   implicitly starts the FSM when component mounts and stops it on unmount. It also automagically subscribes to the FSM 
   state through the `state` binding.
   
2. `match-state` is useful for simply declaring a view for each possible state of the FSM.

3. We indicate validation error if and only if the user has finished editing and the value is invalid.
   
4. In the event handlers, we see how to trigger state transitions.

## Lower level API

The FSM above can also be started and stopped like this:

```clojure
(f/dispatch [::rs/start validation-fsm])

(f/dispatch [::rs/stop (:id validation-fsm)])
```

If you need to provide clj-statecharts options, then you can add them as metadata:

```clojure
(def validation-fsm
  ^{:transition-opts {:ignore-unknown-event? true}}
  {:id      :validation
   ....})
```

## Handling state
This library wants to be open to different shapes of dbs. Therefore, a multimethod exists for reading and writing FSM 
state to DB. The default implementation assumes a regular map, and stores the state under the `::rs/fsm-state` key.

If you have some other preference, for example a normalized DB implementation that identifies things by UUID, you could 
do the following:

```clojure
(defmulti rs/get-state UUID
  [db id]
  (db/pull db [:by-uuid id]))

(defmulti rs/set-state UUID
  [db id new-state]
  (db/transact! db (assoc new-state :uuid id)))
```

Since the built-in implementation is `:default`, any implementation that you provide that is more specific than
that will take presedence. So if you prefer a path for example, just implement the multimethod for the vector type.

## Implementation details
The built-in clj-statecharts re-frame integration creates a separate init and transition handler per FSM.

This integration goes in a different direction. There is only one event for init and one for transition. And
the FSM instance is maintained within the scope of a re-frame global interceptor. There is one interceptor per FSM, and 
the interceptor is cleared when FSM is stopped.
