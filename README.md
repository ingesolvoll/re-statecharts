# Statecharts for re-frame

A richer re-frame wrapper for [https://github.com/lucywang000/clj-statecharts](clj-statecharts)

[![Clojars Project](https://img.shields.io/clojars/v/com.github.ingesolvoll/re-statecharts.svg)](https://clojars.org/com.github.ingesolvoll/re-statecharts)

# Rationale
While the [default re-frame integration](https://lucywang000.github.io/clj-statecharts/docs/integration/re-frame/) is perfectly
fine for many cases, there are also several other ways to do it using re-frame. This integration tries to minimize
boilerplate in the lower level API, while providing several higher level utilities to make it easier to work with FSMs.

# Examples


## Closed mode

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
              ::editing {:on {::edit-ended ::dirty}}
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
                        ::editing [:div "User is editing..."]
                        ::clean [:div "No changes made yet"]
                        ::dirty [:div
                                 "Form has been modified and is "
                                 (if (seq @text)
                                   "valid"
                                   [:span {:style {:color :red}} "invalid"])]
                        nil [:div])
       [:input {:type      :text
                :on-change #(update-text %)
                :on-focus  #(f/dispatch [::rs/transition :validation ::edit-started])
                :on-blur   #(f/dispatch [::rs/transition :validation ::edit-ended])}]
       [:button {:on-click #(f/dispatch [::rs/restart :validation])} "Reset input FSM"]])))
```

There' a lot going on here, so I'll walk through them in sequence
1. Since the FSM is only of interest to us within the scope of this component, we use the `with-fsm` macro, that 
   implicitly starts the FSM when component mounts and stops it on unmount. It also automagically subscribes to the FSM 
   state through the `state` binding.
   
2. `match-state` is useful for simply declaring a view for each possible state of the FSM.

3. We indicate validation error if and only if the user has finished editing and the value is invalid.
   
4. In the event handlers, we see how to trigger state transitions.

## Open mode
The above example requires using the event `::rs/transition` to transition the machine. Alternatively you can use "open"
mode. This mode will consider every incoming re-frame event as a potential transition for our FSM. 

The open mode is more flexible, but also less efficient since it will run the `transition` function of clj-statecharts 
a lot more often. For most apps this is probably fine, but if you are trying to save CPU cycles you might want to 
consider your options carefully.

**For this mode to work, the FSM id needs to be the second element of the event vector.**

Here's an example:

```clojure
(def open-validation-fsm
  ^{:open? true}
  {:id      :validation-open
   :initial ::clean
   :states  {::clean   {:on {::edit-started ::editing}}
             ::editing {:on {::edit-ended ::dirty}}
             ::dirty   {:on {::edit-started ::editing}}}})
```
Notice the metadata that sets the `:open?` option. Now we can use this FSM in a UI using only plain re-frame events:

```clojure

;; These particular events are just no-ops. But you can of course have your events do interesting things, while still
;; triggering FSM transitions. 
(f/reg-event-fx ::edit-started (constantly nil))
(f/reg-event-fx ::edit-ended (constantly nil))

(defn form []
  (fsm/with-fsm [state open-validation-fsm]
    (r/with-let [text        (r/atom "")
                 update-text #(reset! text (-> % .-target .-value))]
      [:div
       [:input {:type      :text
                :on-change #(update-text %)
                :on-focus  #(f/dispatch [::edit-started :validation-open])
                :on-blur   #(f/dispatch [::edit-ended :validation-open])}]])))
```


## Lower level API

The FSM above can also be started and stopped like this:

```clojure
(f/dispatch [::rs/start validation-fsm])

(f/dispatch [::rs/stop (:id validation-fsm)])

(f/dispatch [::rs/restart (:id validation-fsm)])
```

## Options

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
(defmethod rs/get-state UUID
  [db id]
  (db/pull db [:by-uuid id]))

(defmethod rs/set-state UUID
  [db id new-state]
  (db/transact! db (assoc new-state :uuid id)))
```

Since the built-in implementation is `:default`, any implementation that you provide that is more specific will take 
presedence. So if you prefer a path for example, just implement the multimethod for the vector type.

## Implementation details
clj-statecharts' built-in re-frame integration creates a separate init and transition handler per FSM.

This integration goes in a different direction. There is only one event for init and one (optional) for transition.

The FSM instance is maintained within the scope of a re-frame global interceptor. There is one interceptor per FSM, and 
the interceptor is cleared when FSM is stopped.
