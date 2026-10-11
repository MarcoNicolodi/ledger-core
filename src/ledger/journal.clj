(ns ledger.journal
  (:require [ledger.money :as money]))

(defn empty-ledger [accounts]
  {:accounts accounts
   :journal  []})

(defn currency [state account-id]
  (get-in state [:accounts account-id :currency]))

(defn opening-balance [state account-id]
  (get-in state [:accounts account-id :opening-balance]))

(defn append [state record]
  (update state :journal conj (assoc record :seq (inc (count (:journal state))))))

(defn known-at? [known-at record]
  (or (nil? known-at) (<= (:posted-day record) known-at)))

(defn records [state account-id & {:keys [known-at]}]
  (->> (:journal state)
       (filter #(= account-id (:account/id %)))
       (filter (partial known-at? known-at))))

(defn signed [{:keys [direction amount]}]
  (case direction
    :credit amount
    :debit  (- amount)))

(defn entries [state account-id & {:keys [known-at]}]
  (->> (records state account-id :known-at known-at)
       (filter #(= :entry (:record/type %)))))

(defn balance [state account-id day & {:keys [known-at]}]
  (->> (entries state account-id :known-at known-at)
       (filter #(<= (:value-date %) day))
       (map signed)
       (reduce + (opening-balance state account-id))))

(defn active-holds [state account-id & {:keys [known-at]}]
  (->> (records state account-id :known-at known-at)
       (keep (fn [record]
               (case (:record/type record)
                 :hold/placed   (:amount record)
                 :hold/released (- (:amount record))
                 nil)))
       (reduce + (money/zero (currency state account-id)))))

(defn available [state account-id day & {:keys [known-at]}]
  (- (balance state account-id day :known-at known-at)
     (active-holds state account-id :known-at known-at)))

(defn event-record [event day record-type]
  (cond-> {:record/type record-type
           :source      (:event/id event)
           :posted-day  day
           :account/id  (:account/id event)}
    (:late? event) (assoc :late? true :event-day (:event/day event))))

(defn reject [state event day code detail]
  (append state (assoc (event-record event day :rejection)
                       :error/code code
                       :error/detail detail)))
