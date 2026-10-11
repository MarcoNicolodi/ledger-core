(ns ledger.journal-test
  (:require [clojure.test :refer [deftest is testing]]
            [ledger.journal :as journal]))

(def accounts
  {"ACC-001" {:currency :AED :opening-balance 0.00M}
   "ACC-002" {:currency :BHD :opening-balance 0.000M}})

(defn- entry [posted-day value-date direction amount]
  {:record/type :entry :account/id "ACC-001" :posted-day posted-day
   :value-date value-date :direction direction :amount amount :currency :AED})

(defn- hold [record-type posted-day amount]
  {:record/type record-type :account/id "ACC-001" :posted-day posted-day
   :auth/id "Auth-X" :amount amount :currency :AED})

(defn- ledger-with [& records]
  (reduce journal/append (journal/empty-ledger accounts) records))

(deftest append-only
  (testing "append assigns an increasing sequence number"
    (let [state (ledger-with (entry 1 1 :credit 100.00M) (entry 1 1 :debit 30.00M))]
      (is (= [1 2] (map :seq (:journal state))))))

  (testing "the previous journal is always a prefix of the new journal"
    (let [before (ledger-with (entry 1 1 :credit 100.00M))
          after  (journal/append before (entry 2 2 :debit 30.00M))]
      (is (= (:journal before) (subvec (:journal after) 0 (count (:journal before))))))))

(deftest balance-by-value-date
  (testing "only entries with value date on or before the day count"
    (let [state (ledger-with (entry 1 1 :credit 100.00M) (entry 3 3 :debit 30.00M))]
      (is (= 100.00M (journal/balance state "ACC-001" 1)))
      (is (= 100.00M (journal/balance state "ACC-001" 2)))
      (is (= 70.00M (journal/balance state "ACC-001" 3)))))

  (testing "a back-dated entry changes a past day"
    (let [state (ledger-with (entry 1 1 :credit 250.00M) (entry 5 2 :debit 620.00M))]
      (is (= -370.00M (journal/balance state "ACC-001" 2)))))

  (testing "known-at hides entries posted after that day"
    (let [state (ledger-with (entry 1 1 :credit 250.00M) (entry 5 2 :debit 620.00M))]
      (is (= 250.00M (journal/balance state "ACC-001" 2 :known-at 4)))
      (is (= -370.00M (journal/balance state "ACC-001" 2 :known-at 5)))))

  (testing "an account with no entries has its opening balance at its own scale"
    (is (= "0.000" (str (journal/balance (ledger-with) "ACC-002" 6))))))

(deftest holds-change-available-not-ledger
  (let [placed   (ledger-with (entry 1 1 :credit 250.00M) (hold :hold/placed 2 200.00M))
        released (journal/append placed (hold :hold/released 4 200.00M))]
    (testing "a placed hold reduces available balance only"
      (is (= 250.00M (journal/balance placed "ACC-001" 2)))
      (is (= 50.00M (journal/available placed "ACC-001" 2))))

    (testing "a released hold restores available balance"
      (is (= 250.00M (journal/available released "ACC-001" 4))))

    (testing "known-at applies to holds"
      (is (= 50.00M (journal/available released "ACC-001" 4 :known-at 3))))))
