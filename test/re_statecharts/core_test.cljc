(ns re-statecharts.core-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [day8.re-frame.test :as rf-test]
   [re-frame.core :as f]
   [re-statecharts.core :as rs]))

(def validation-fsm
  {:id      :validation
   :initial ::clean
   :states  {::clean   {:on {::edit-started ::editing}}
             ::editing {:on {::edit-ended ::dirty}}
             ::dirty   {:on {::edit-started ::editing}}}})

(def global-validation-fsm
  (-> validation-fsm
      (with-meta {::rs/open? true})
      (assoc :id :validation-open)))


(deftest initializing
  (testing "Closed machine lifecycle"
    (rf-test/run-test-sync
     (f/dispatch [::rs/start validation-fsm])
     (is (= ::clean @(f/subscribe [::rs/state :validation])))
     (f/dispatch [::rs/transition :validation ::edit-started])
     (is (= ::editing @(f/subscribe [::rs/state :validation])))
     (f/dispatch [::rs/stop :validation])
     (is (nil? @(f/subscribe [::rs/state :validation])))))

  (testing "Open machine lifecycle"
    (rf-test/run-test-sync
     (let [state (f/subscribe [::rs/state :validation-open])]
       (f/reg-event-fx ::edit-started (constantly nil))
       (f/dispatch [::rs/start global-validation-fsm])
       (is (= ::clean @state))
       (f/dispatch [::edit-started :validation-open])
       (is (= ::editing @state))
       (f/dispatch [::rs/stop :validation-open])
       (is (nil? @state))))))
