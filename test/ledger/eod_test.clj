(ns ledger.eod-test
  (:require [clojure.test :refer [deftest is testing]]
            [ledger.engine :as engine]
            [ledger.eod :as eod]
            [ledger.journal :as journal]))

(defn- event [id day type amount & {:as fields}]
  (merge {:event/id id :event/day day :event/type type :account/id "ACC-001"
          :amount amount :currency :AED :value-date day}
         fields))

(defn- run [& events]
  (reduce engine/process (engine/initial-state engine/accounts) events))

(defn- fees [state account-id]
  (filter #(= :fee (:entry/kind %)) (journal/entries state account-id)))

(def ^:private before-e7
  [(event "E1" 1 :credit 1200.00M)
   (event "E2" 1 :debit 950.00M)
   (event "E4" 3 :credit 400.00M)
   (event "S4" 4 :debit 185.00M)])

(def ^:private e7 (event "E7" 5 :debit 620.00M :value-date 2))

(deftest back-dated-fee-cascade
  (let [state (eod/close-day (apply run (conj before-e7 e7)) 5)]
    (testing "no fee is booked before the back-dated debit arrives"
      (is (empty? (fees (eod/close-day (apply run before-e7) 4) "ACC-001"))))

    (testing "the end of Day 5 books fees for Day 2, Day 4 and Day 5, in that order"
      (is (= [2 4 5] (map :fee/for-day (fees state "ACC-001"))))
      (is (= [-370.00M -180.00M -205.00M] (map :fee/closing-balance (fees state "ACC-001")))))

    (testing "each fee has the value date of its day and the posting day of the run"
      (is (every? #(= (:fee/for-day %) (:value-date %)) (fees state "ACC-001")))
      (is (every? #(= 5 (:posted-day %)) (fees state "ACC-001"))))

    (testing "the Day 2 fee keeps Day 3 positive, so Day 3 has no fee"
      (is (= 5.00M (journal/balance state "ACC-001" 3))))

    (testing "the end of Day 5 closes at -230.00"
      (is (= -230.00M (journal/balance state "ACC-001" 5))))

    (testing "a second run on the same day books no new fee"
      (is (= 3 (count (fees (eod/close-day state 5) "ACC-001")))))

    (testing "a later reversal does not refund the fees"
      (let [reversed (-> state
                         (engine/process {:event/id "E9" :event/day 6 :event/type :reversal
                                          :account/id "ACC-001" :reversal/target "E7" :value-date 2})
                         (eod/close-day 6))]
        (is (= 3 (count (fees reversed "ACC-001"))))
        (is (= 390.00M (journal/balance reversed "ACC-001" 6)))))))

(deftest fee-thresholds
  (testing "a balance of exactly zero is not negative"
    (let [state (eod/close-day (run (event "C" 1 :credit 100.00M) (event "D" 1 :debit 100.00M)) 1)]
      (is (empty? (fees state "ACC-001")))))

  (testing "a BHD account has no fee in its own currency, so the run records one error for each negative day"
    (let [state (-> (run (event "D" 1 :debit 1.000M :account/id "ACC-002" :currency :BHD))
                    (eod/close-day 1)
                    (eod/close-day 2))]
      (is (empty? (fees state "ACC-002")))
      (is (= [[:fee/no-fx-rate 1] [:fee/no-fx-rate 2]]
             (->> (journal/records state "ACC-002")
                  (filter #(= :rejection (:record/type %)))
                  (map (juxt :error/code :fee/for-day)))))
      (is (= -1.000M (journal/balance state "ACC-002" 2))))))
