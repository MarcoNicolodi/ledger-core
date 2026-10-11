(ns ledger.eod
  (:require [ledger.journal :as journal]
            [ledger.money :as money]
            [ledger.window :as window]))

(def overdraft-fee {:AED 25.00M})

(def daily-interest-rate 0.0004M)

(defn- eod-record [account-id closing-day record-type]
  {:record/type record-type
   :source      :eod
   :posted-day  closing-day
   :account/id  account-id})

(defn- fee-assessed? [state account-id day]
  (some #(= day (:fee/for-day %)) (journal/records state account-id)))

(defn- assess-fee [state account-id closing-day day]
  (let [balance  (journal/balance state account-id day)
        currency (journal/currency state account-id)
        fee      (overdraft-fee currency)]
    (cond
      (not (neg? balance))                 state
      (fee-assessed? state account-id day) state
      (nil? fee)                           (journal/append state (assoc (eod-record account-id closing-day :rejection)
                                                                        :error/code :fee/no-fx-rate
                                                                        :error/detail {:currency currency :closing-balance balance}
                                                                        :fee/for-day day))
      :else                                (journal/append state (assoc (eod-record account-id closing-day :entry)
                                                                        :entry/kind :fee :direction :debit
                                                                        :amount fee :currency currency
                                                                        :value-date day
                                                                        :fee/for-day day
                                                                        :fee/closing-balance balance)))))

(defn assess-fees [state account-id closing-day]
  (reduce #(assess-fee %1 account-id closing-day %2)
          state
          (range window/first-day (inc closing-day))))

(defn accruals [state account-id]
  (let [currency (journal/currency state account-id)]
    (for [day (range window/first-day (inc window/last-day))
          :let [balance (journal/balance state account-id day)
                raw     (if (pos? balance) (* balance daily-interest-rate) 0M)]]
      {:value-date day
       :balance    balance
       :raw        raw
       :amount     (money/round currency raw)})))

(defn capitalize [state account-id closing-day]
  (let [currency (journal/currency state account-id)
        rows     (accruals state account-id)
        total    (reduce + (money/zero currency) (map :amount rows))
        state    (reduce #(journal/append %1 (merge (eod-record account-id closing-day :accrual)
                                                    %2
                                                    {:currency currency}))
                         state
                         rows)]
    (if (pos? total)
      (journal/append state (assoc (eod-record account-id closing-day :entry)
                                   :entry/kind :interest :direction :credit
                                   :amount total :currency currency
                                   :value-date window/last-day))
      state)))

(defn close-day [state day]
  (reduce (fn [state account-id]
            (cond-> (assess-fees state account-id day)
              (= day window/last-day) (capitalize account-id day)))
          state
          (keys (:accounts state))))
