(ns ledger.auth-test
  (:require [clojure.test :refer [deftest is testing]]
            [ledger.auth :as auth]
            [ledger.journal :as journal]))

(def accounts
  {"ACC-001" {:currency :AED :opening-balance 0.00M}
   "ACC-003" {:currency :AED :opening-balance 0.00M}})

(defn- funded [amount]
  (journal/append (journal/empty-ledger accounts)
                  {:record/type :entry :account/id "ACC-001" :posted-day 1 :value-date 1
                   :direction :credit :amount amount :currency :AED :entry/kind :credit}))

(defn- authorization [auth-id amount]
  {:event/id (str "AUTH-" auth-id) :event/type :authorization :account/id "ACC-001"
   :auth/id auth-id :amount amount :currency :AED :value-date 2})

(defn- settlement [auth-id amount & {:keys [account] :or {account "ACC-001"}}]
  {:event/id (str "SETTLE-" auth-id) :event/type :settlement :account/id account
   :auth/id auth-id :amount amount :currency :AED :value-date 4})

(defn- of-type [state record-type]
  (filter #(= record-type (:record/type %)) (:journal state)))

(defn- error-codes [state]
  (map :error/code (of-type state :rejection)))

(deftest authorization-decision
  (testing "approved when available balance is exactly zero after the hold"
    (let [state (auth/authorize (funded 200.00M) (authorization "A" 200.00M) 2)]
      (is (= :approved (auth/auth-state state "A")))
      (is (= 0.00M (journal/available state "ACC-001" 2)))))

  (testing "declined when available balance would be below zero"
    (let [state (auth/authorize (funded 200.00M) (authorization "A" 200.01M) 2)]
      (is (= :declined (auth/auth-state state "A")))
      (is (= -0.01M (:available-after (first (of-type state :auth/declined)))))
      (is (empty? (of-type state :hold/placed)))
      (is (= 200.00M (journal/available state "ACC-001" 2)))))

  (testing "existing holds count against the next authorization"
    (let [state (-> (funded 250.00M)
                    (auth/authorize (authorization "A" 200.00M) 2)
                    (auth/authorize (authorization "B" 60.00M) 2))]
      (is (= :approved (auth/auth-state state "A")))
      (is (= :declined (auth/auth-state state "B")))))

  (testing "a second authorization with the same id is rejected"
    (let [state (-> (funded 500.00M)
                    (auth/authorize (authorization "A" 100.00M) 2)
                    (auth/authorize (authorization "A" 100.00M) 2))]
      (is (= [:authorization/duplicate-id] (error-codes state)))
      (is (= 1 (count (of-type state :hold/placed)))))))

(deftest settlement-of-approved-authorization
  (let [state (-> (funded 650.00M)
                  (auth/authorize (authorization "A" 200.00M) 2)
                  (auth/settle (settlement "A" 185.00M) 4))]
    (testing "a partial settlement debits the settled amount and releases the full hold"
      (is (= :settled (auth/auth-state state "A")))
      (is (= 465.00M (journal/balance state "ACC-001" 4)))
      (is (= 465.00M (journal/available state "ACC-001" 4)))
      (is (= 200.00M (:amount (first (of-type state :hold/released))))))

    (testing "known-at shows the state before the settlement"
      (is (= :approved (auth/auth-state state "A" :known-at 3))))))

(deftest settlement-rejections
  (let [approved (auth/authorize (funded 650.00M) (authorization "A" 200.00M) 2)]
    (testing "unknown authorization: rejected and no money moves"
      (let [state (auth/settle (funded 650.00M) (settlement "Z" 180.00M) 4)]
        (is (= [:settlement/unknown-authorization] (error-codes state)))
        (is (= 650.00M (journal/balance state "ACC-001" 4)))
        (is (= 1 (count (journal/entries state "ACC-001"))))))

    (testing "declined authorization cannot be settled"
      (let [state (-> (funded 100.00M)
                      (auth/authorize (authorization "B" 200.00M) 2)
                      (auth/settle (settlement "B" 50.00M) 4))]
        (is (= [:settlement/authorization-declined] (error-codes state)))
        (is (= 100.00M (journal/balance state "ACC-001" 4)))))

    (testing "an authorization settles only once"
      (let [state (-> approved
                      (auth/settle (settlement "A" 185.00M) 4)
                      (auth/settle (settlement "A" 10.00M) 4))]
        (is (= [:settlement/already-settled] (error-codes state)))
        (is (= 465.00M (journal/balance state "ACC-001" 4)))))

    (testing "a settlement above the hold is rejected and the hold stays"
      (let [state (auth/settle approved (settlement "A" 200.01M) 4)]
        (is (= [:settlement/exceeds-hold] (error-codes state)))
        (is (= :approved (auth/auth-state state "A")))
        (is (= 450.00M (journal/available state "ACC-001" 4)))))

    (testing "a settlement on another account is rejected"
      (let [state (auth/settle approved (settlement "A" 185.00M :account "ACC-003") 4)]
        (is (= [:settlement/account-mismatch] (error-codes state)))
        (is (= :approved (auth/auth-state state "A")))))))
