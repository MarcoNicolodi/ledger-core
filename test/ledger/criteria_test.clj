(ns ledger.criteria-test
  (:require [clojure.test :refer [deftest is testing]]
            [ledger.auth :as auth]
            [ledger.engine :as engine]
            [ledger.journal :as journal]
            [ledger.report :as report]))

(def ^:private events (engine/load-events))

(defn- event [id]
  (first (filter #(= id (:event/id %)) events)))

(def ^:private state (engine/replay engine/accounts events))

(defn- without [& ids]
  (remove #(contains? (set ids) (:event/id %)) events))

(defn- fee-days [state]
  (map :fee/for-day (filter #(= :fee (:entry/kind %)) (journal/entries state "ACC-001"))))

(deftest criterion-1-accepted-day-2-is-minus-370-before-fees
  (testing "after E7 is processed and before the end-of-day run of Day 5"
    (let [after-e7 (reduce engine/process (engine/initial-state engine/accounts) (take 7 events))]
      (is (= "E7" (:source (last (:journal after-e7)))))
      (is (= -370.00M (journal/balance after-e7 "ACC-001" 2)))
      (is (empty? (fee-days after-e7))))))

(deftest criterion-2-rejected-e7-causes-three-fees-not-one
  (testing "REJECTED.md: without E7 there are no fees; with E7 there are fees for Day 2, Day 4 and Day 5"
    (is (empty? (fee-days (engine/replay engine/accounts (without "E7" "E9")))))
    (is (= [2 4 5] (fee-days state)))))

(deftest criterion-3-accepted-auth-a-settlement-is-accepted
  (is (= :settled (auth/auth-state state "Auth-A")))
  (is (= [185.00M] (map :amount (filter #(= "E5" (:source %)) (journal/entries state "ACC-001"))))))

(deftest criterion-4-accepted-unknown-authorization-is-rejected-and-no-money-moves
  (is (= [:settlement/unknown-authorization]
         (map :error/code (filter #(= "E6" (:source %)) (:journal state)))))
  (is (empty? (filter #(= "E6" (:source %)) (journal/entries state "ACC-001"))))
  (is (= 465.00M (journal/balance state "ACC-001" 4 :known-at 4))))

(deftest criterion-5-accepted-hold-reduces-available-not-ledger
  (testing "the rule holds, shown with Auth-A on Day 2"
    (is (= 250.00M (journal/balance state "ACC-001" 2 :known-at 2)))
    (is (= 50.00M (journal/available state "ACC-001" 2 :known-at 2))))

  (testing "the premise is false: Auth-B is declined, so it never places a hold"
    (is (= :declined (auth/auth-state state "Auth-B")))
    (is (empty? (filter #(and (= "Auth-B" (:auth/id %)) (= :hold/placed (:record/type %))) (:journal state))))))

(deftest criterion-6-rejected-balances-and-fees-do-not-return-to-pre-e7-values
  (testing "REJECTED.md: pre-E7 value is the Day 4 close known before E7 arrived"
    (let [pre-e7 (journal/balance state "ACC-001" 4 :known-at 4)]
      (is (= 465.00M pre-e7))
      (is (= 390.00M (journal/balance state "ACC-001" 5)))
      (is (not= pre-e7 (journal/balance state "ACC-001" 5)))))

  (testing "the three fees stay after E9"
    (is (= 75.00M (reduce + (map :amount (filter #(= :fee (:entry/kind %)) (journal/entries state "ACC-001")))))))

  (testing "Auth-B stays declined after E9"
    (is (= :declined (auth/auth-state state "Auth-B")))))

(deftest criterion-7-rejected-instalments-are-not-3-334-each
  (let [instalments (map :amount (filter #(= "E10" (:source %)) (journal/entries state "ACC-002")))]
    (testing "REJECTED.md: 3 x 3.334 = 10.002, which creates 0.002 BHD"
      (is (= 10.002M (* 3 3.334M)))
      (is (not= [3.334M 3.334M 3.334M] instalments)))

    (testing "the split that adds up exactly"
      (is (= [3.333M 3.333M 3.334M] instalments))
      (is (= (:amount (event "E10")) (reduce + instalments))))))

(deftest criterion-8-rejected-no-remainder-is-ever-discarded
  (testing "REJECTED.md: the capitalized credit is the sum of the rounded accruals, so nothing remains"
    (doseq [{:keys [account/id accrual-sum capitalized]} (:accounts (report/final-report state))]
      (is (= accrual-sum capitalized) id))))
