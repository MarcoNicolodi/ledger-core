(ns ledger.eod
  (:require [ledger.journal :as journal]
            [ledger.window :as window]))

(def overdraft-fee {:AED 25.00M})

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

(defn close-day [state day]
  (reduce #(assess-fees %1 %2 day) state (keys (:accounts state))))
