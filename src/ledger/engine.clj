(ns ledger.engine
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [ledger.auth :as auth]
            [ledger.eod :as eod]
            [ledger.journal :as journal]
            [ledger.money :as money]
            [ledger.window :refer [first-day last-day in-window?]]))

(def accounts
  {"ACC-001" {:currency :AED :opening-balance 0.00M}
   "ACC-002" {:currency :BHD :opening-balance 0.000M}})

(defn load-events []
  (edn/read-string (slurp (io/resource "events.edn"))))

(defn initial-state [accounts]
  (assoc (journal/empty-ledger accounts) :day first-day))

(def ^:private money-event? #{:credit :debit :authorization :settlement})

(defn- validation-error [state {:keys [currency amount value-date] :as event}]
  (let [account-currency (journal/currency state (:account/id event))]
    (cond
      (not (in-window? (:event/day event)))     [:event/outside-window {:event/day (:event/day event)}]
      (nil? account-currency)                   [:account/unknown {:account/id (:account/id event)}]
      (not (in-window? value-date))             [:value-date/outside-window {:value-date value-date}]
      (not (money-event? (:event/type event)))  nil
      (not= currency account-currency)          [:currency/mismatch {:event currency :account account-currency}]
      (not (money/valid-amount? currency amount)) [:amount/invalid {:amount amount :currency currency}])))

(defmulti apply-event (fn [_state event _day] (:event/type event)))

(defn- post-entries [state {:keys [amount currency value-date instalments] :as event} day]
  (let [parts (if instalments (money/split currency amount instalments) [amount])]
    (reduce (fn [state [index part]]
              (journal/append state (cond-> (assoc (journal/event-record event day :entry)
                                                   :entry/kind (:event/type event)
                                                   :direction (:event/type event)
                                                   :amount part :currency currency
                                                   :value-date value-date)
                                      instalments (assoc :instalment [(inc index) instalments]))))
            state
            (map-indexed vector parts))))

(defmethod apply-event :credit [state event day]
  (post-entries state event day))

(defmethod apply-event :debit [state event day]
  (post-entries state event day))

(defmethod apply-event :authorization [state event day]
  (auth/authorize state event day))

(defmethod apply-event :settlement [state event day]
  (auth/settle state event day))

(def ^:private opposite {:credit :debit, :debit :credit})

(defmethod apply-event :reversal [state {:keys [reversal/target value-date] :as event} day]
  (let [account-entries (journal/entries state (:account/id event))
        targets         (filter #(= target (:source %)) account-entries)
        reject          (fn [code] (journal/reject state event day code {:reversal/target target}))]
    (cond
      (empty? targets)                                        (reject :reversal/unknown-target)
      (some #(= :reversal (:entry/kind %)) targets)           (reject :reversal/target-is-reversal)
      (some #(= target (:reversal/target %)) account-entries) (reject :reversal/already-reversed)
      :else
      (reduce (fn [state entry]
                (journal/append state (assoc (journal/event-record event day :entry)
                                             :entry/kind :reversal
                                             :direction (opposite (:direction entry))
                                             :amount (:amount entry) :currency (:currency entry)
                                             :value-date value-date :reversal/target target)))
              state
              targets))))

(defmethod apply-event :default [state event day]
  (journal/reject state event day :event/unknown-type {:event/type (:event/type event)}))

(defn advance-to [state day]
  (reduce (fn [state closing-day]
            (-> (eod/close-day state closing-day)
                (assoc :day (inc closing-day))))
          state
          (range (:day state) day)))

(defn process [state event]
  (let [state (if (and (in-window? (:event/day event)) (> (:event/day event) (:day state)))
                (advance-to state (:event/day event))
                state)
        day   (:day state)
        event (cond-> event (< (:event/day event) day) (assoc :late? true))]
    (if-let [[code detail] (validation-error state event)]
      (journal/reject state event day code detail)
      (apply-event state event day))))

(defn replay [accounts events]
  (-> (reduce process (initial-state accounts) events)
      (advance-to (inc last-day))))
