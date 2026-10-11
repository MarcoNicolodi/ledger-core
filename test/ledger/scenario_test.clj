(ns ledger.scenario-test
  (:require [clojure.test :refer [deftest is testing]]
            [ledger.engine :as engine]
            [ledger.report :as report]))

(def ^:private events (engine/load-events))

(def ^:private state (engine/replay engine/accounts events))

(defn- account [day account-id]
  (first (filter #(= account-id (:account/id %)) (:accounts (report/day-report state day)))))

(defn- per-day [account-id f]
  (mapv #(f (account % account-id)) (range 1 7)))

(deftest replay-prints-the-report
  (println)
  (report/print-report state)
  (is (seq (report/report-lines state))))

(deftest balances-as-known-at-each-end-of-day
  (testing "ACC-001 closing ledger balance"
    (is (= [250.00M 250.00M 650.00M 465.00M -230.00M 390.93M] (per-day "ACC-001" :closing-balance))))

  (testing "ACC-001 available balance"
    (is (= [250.00M 50.00M 450.00M 465.00M -230.00M 390.93M] (per-day "ACC-001" :available))))

  (testing "ACC-002 is empty until E10 arrives late on Day 6"
    (is (= ["0.000" "0.000" "0.000" "0.000" "0.000" "10.008"] (map str (per-day "ACC-002" :closing-balance))))))

(deftest balances-restated-by-value-date
  (let [[acc-001 acc-002] (:accounts (report/final-report state))]
    (is (= [250.00M 225.00M 625.00M 415.00M 390.00M 390.93M] (:restated acc-001)))
    (is (= [0.000M 0.000M 0.000M 0.000M 10.000M 10.008M] (:restated acc-002)))
    (is (= [0.93M 0.93M] [(:accrual-sum acc-001) (:capitalized acc-001)]))
    (is (= [0.008M 0.008M] [(:accrual-sum acc-002) (:capitalized acc-002)]))))

(deftest fees-per-day
  (is (= [[] [] [] [] [2 4 5] []] (per-day "ACC-001" #(mapv :fee/for-day (:fees %)))))
  (is (= [[] [] [] [] [] []] (per-day "ACC-002" #(mapv :fee/for-day (:fees %))))))

(deftest authorization-states-per-day
  (let [states (per-day "ACC-001" #(into {} (map (juxt :auth/id :state) (:authorizations %))))]
    (is (= [{}
            {"Auth-A" :approved}
            {"Auth-A" :approved}
            {"Auth-A" :settled}
            {"Auth-A" :settled "Auth-B" :declined}
            {"Auth-A" :settled "Auth-B" :declined}]
           states)))

  (testing "Auth-B is declined because the hold would make available balance -245.00"
    (is (= -245.00M (:available-after (second (:authorizations (account 5 "ACC-001")))))))

  (testing "Auth-A settles for 185.00 and releases its full 200.00 hold"
    (is (= [185.00M 200.00M] ((juxt :settled :amount) (first (:authorizations (account 4 "ACC-001"))))))))

(deftest errors-and-notes-per-day
  (is (= [[] [] [] [:settlement/unknown-authorization] [] []]
         (per-day "ACC-001" #(mapv :error/code (:errors %)))))
  (is (= [["E7" 2]] (:back-dated (account 5 "ACC-001"))))
  (is (= [["E10" 5 5]] (:late (account 6 "ACC-002")))))

(deftest journal-is-append-only-through-the-whole-replay
  (let [states (reductions engine/process (engine/initial-state engine/accounts) events)]
    (is (every? (fn [[before after]]
                  (= (:journal before) (subvec (:journal after) 0 (count (:journal before)))))
                (partition 2 1 (concat states [state]))))))
