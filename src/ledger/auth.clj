(ns ledger.auth
  (:require [ledger.journal :as journal]))

(defn- auth-records [state auth-id]
  (filter #(= auth-id (:auth/id %)) (:journal state)))

(def ^:private status-by-record-type
  {:hold/placed   :approved
   :hold/released :settled
   :auth/declined :declined})

(defn auth-state [state auth-id & {:keys [known-at]}]
  (->> (auth-records state auth-id)
       (filter (partial journal/known-at? known-at))
       (keep (comp status-by-record-type :record/type))
       last))

(defn- placed-hold [state auth-id]
  (first (filter #(= :hold/placed (:record/type %)) (auth-records state auth-id))))

(defn authorize [state {:keys [amount currency] :as event} day]
  (if (auth-state state (:auth/id event))
    (journal/reject state event day :authorization/duplicate-id {:auth/id (:auth/id event)})
    (let [available-after (- (journal/available state (:account/id event) day) amount)
          base            (assoc (journal/event-record event day nil)
                                 :auth/id (:auth/id event) :amount amount :currency currency)]
      (if (neg? available-after)
        (journal/append state (assoc base :record/type :auth/declined :available-after available-after))
        (journal/append state (assoc base :record/type :hold/placed))))))

(defn settle [state {:keys [amount currency value-date] :as event} day]
  (let [auth-id (:auth/id event)
        hold    (placed-hold state auth-id)
        reject  (fn [code] (journal/reject state event day code {:auth/id auth-id :amount amount}))]
    (case (auth-state state auth-id)
      nil       (reject :settlement/unknown-authorization)
      :declined (reject :settlement/authorization-declined)
      :settled  (reject :settlement/already-settled)
      :approved (cond
                  (not= (:account/id hold) (:account/id event)) (reject :settlement/account-mismatch)
                  (> amount (:amount hold))                     (reject :settlement/exceeds-hold)
                  :else
                  (-> state
                      (journal/append (assoc (journal/event-record event day :entry)
                                             :entry/kind :settlement :direction :debit
                                             :amount amount :currency currency
                                             :value-date value-date :auth/id auth-id))
                      (journal/append (assoc (journal/event-record event day :hold/released)
                                             :auth/id auth-id :amount (:amount hold)
                                             :currency currency :reason :settled)))))))
