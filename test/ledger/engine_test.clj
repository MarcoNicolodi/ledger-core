(ns ledger.engine-test
  (:require [clojure.test :refer [deftest is testing]]
            [ledger.engine :as engine]
            [ledger.eod :as eod]
            [ledger.journal :as journal]))

(defn- event [id day type & {:as fields}]
  (merge {:event/id id :event/day day :event/type type :account/id "ACC-001"
          :amount 100.00M :currency :AED :value-date day}
         fields))

(defn- run [& events]
  (reduce engine/process (engine/initial-state engine/accounts) events))

(defn- of-type [state record-type]
  (filter #(= record-type (:record/type %)) (:journal state)))

(defn- error-codes [state]
  (map :error/code (of-type state :rejection)))

(defn- are-rejected [cases]
  (doseq [[code bad-event] cases]
    (let [state (run bad-event)]
      (is (= [code] (error-codes state)) (str (:event/id bad-event)))
      (is (empty? (journal/entries state "ACC-001")) (str (:event/id bad-event))))))

(deftest credits-and-debits
  (testing "an entry keeps the posting day and the value date apart"
    (let [state (run (event "E1" 1 :credit :amount 1200.00M)
                     (event "E7" 5 :debit :amount 620.00M :value-date 2))
          e7    (last (journal/entries state "ACC-001"))]
      (is (= [5 2] [(:posted-day e7) (:value-date e7)]))
      (is (= 1200.00M (journal/balance state "ACC-001" 1)))
      (is (= 580.00M (journal/balance state "ACC-001" 2)))))

  (testing "instalments become separate entries that add up to the amount"
    (let [state   (run (event "E10" 5 :credit :account/id "ACC-002" :amount 10.000M
                              :currency :BHD :instalments 3))
          entries (journal/entries state "ACC-002")]
      (is (= [3.333M 3.333M 3.334M] (map :amount entries)))
      (is (= [[1 3] [2 3] [3 3]] (map :instalment entries)))
      (is (= 10.000M (journal/balance state "ACC-002" 5))))))

(deftest validation
  (testing "each invalid event is rejected and books nothing"
    (are-rejected
     [[:account/unknown         (event "X1" 1 :credit :account/id "ACC-999")]
      [:currency/mismatch       (event "X2" 1 :credit :currency :BHD :amount 1.000M)]
      [:amount/invalid          (event "X3" 1 :credit :amount 1.5M)]
      [:amount/invalid          (event "X4" 1 :debit :amount 0.00M)]
      [:value-date/outside-window (event "X5" 1 :credit :value-date 0)]
      [:event/outside-window    (event "X6" 7 :credit)]
      [:event/unknown-type      (event "X7" 1 :transfer)]])))

(deftest reversal
  (let [posted (run (event "E1" 1 :credit :amount 1200.00M)
                    (event "E7" 5 :debit :amount 620.00M :value-date 2))]
    (testing "a reversal books the opposite entry and keeps the target unchanged"
      (let [state (engine/process posted {:event/id "E9" :event/day 6 :event/type :reversal
                                          :account/id "ACC-001" :reversal/target "E7" :value-date 2})
            e9    (last (journal/entries state "ACC-001"))]
        (is (= [:credit 620.00M 2 "E7"] ((juxt :direction :amount :value-date :reversal/target) e9)))
        (is (= 1200.00M (journal/balance state "ACC-001" 2)))
        (is (= (:journal posted) (subvec (:journal state) 0 (count (:journal posted)))))))

    (testing "unknown target, second reversal and reversal of a reversal are rejected"
      (let [reverse-of (fn [id target] {:event/id id :event/day 6 :event/type :reversal
                                        :account/id "ACC-001" :reversal/target target :value-date 2})
            state      (reduce engine/process posted [(reverse-of "R1" "E404")
                                                      (reverse-of "R2" "E7")
                                                      (reverse-of "R3" "E7")
                                                      (reverse-of "R4" "R2")])]
        (is (= [:reversal/unknown-target :reversal/already-reversed :reversal/target-is-reversal]
               (error-codes state)))
        (is (= 1 (count (filter #(= :reversal (:entry/kind %)) (journal/entries state "ACC-001")))))))))

(deftest day-clock
  (testing "a late event is posted in the current day and flagged"
    (let [state (run (event "E9" 6 :credit)
                     (event "E10" 5 :credit :account/id "ACC-002" :amount 10.000M :currency :BHD))
          e10   (first (journal/entries state "ACC-002"))]
      (is (= [6 5 true] ((juxt :posted-day :value-date :late?) e10)))
      (is (= 5 (:event-day e10)))))

  (testing "every day of the window is closed once, in order"
    (let [closed (atom [])]
      (with-redefs [eod/close-day (fn [state day] (swap! closed conj day) state)]
        (let [state (engine/replay engine/accounts [(event "E1" 3 :credit)])]
          (is (= [1 2 3 4 5 6] @closed))
          (is (= 7 (:day state)))))))

  (testing "the stream in resources replays without rejections other than Auth-Z"
    (let [state (engine/replay engine/accounts (engine/load-events))]
      (is (= [:settlement/unknown-authorization] (error-codes state))))))
