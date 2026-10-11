(ns ledger.report
  (:require [clojure.string :as str]
            [ledger.auth :as auth]
            [ledger.journal :as journal]
            [ledger.window :as window]))

(defn- of-type [records record-type]
  (filter #(= record-type (:record/type %)) records))

(defn- authorization [state account-id day auth-id]
  (let [records  (filter #(= auth-id (:auth/id %)) (journal/records state account-id :known-at day))
        hold     (first (of-type records :hold/placed))
        declined (first (of-type records :auth/declined))
        settled  (first (filter #(= :settlement (:entry/kind %)) records))]
    {:auth/id         auth-id
     :state           (auth/auth-state state auth-id :known-at day)
     :amount          (:amount (or hold declined))
     :settled         (:amount settled)
     :available-after (:available-after declined)}))

(defn account-day [state account-id day]
  (let [known  (journal/records state account-id :known-at day)
        posted (filter #(= day (:posted-day %)) known)
        events (filter #(and (= :entry (:record/type %)) (string? (:source %))) posted)]
    {:account/id      account-id
     :currency        (journal/currency state account-id)
     :closing-balance (journal/balance state account-id day :known-at day)
     :available       (journal/available state account-id day :known-at day)
     :authorizations  (map (partial authorization state account-id day)
                           (distinct (keep :auth/id (concat (of-type known :hold/placed)
                                                            (of-type known :auth/declined)))))
     :fees            (filter #(= :fee (:entry/kind %)) posted)
     :errors          (of-type posted :rejection)
     :late            (distinct (map (juxt :source :event-day :value-date) (filter :late? events)))
     :back-dated      (distinct (map (juxt :source :value-date)
                                     (filter #(and (not (:late? %)) (< (:value-date %) day)) events)))}))

(defn day-report [state day]
  {:day      day
   :accounts (map #(account-day state % day) (keys (:accounts state)))})

(defn final-report [state]
  {:accounts
   (for [account-id (keys (:accounts state))
         :let [accruals (of-type (journal/records state account-id) :accrual)
               interest (first (filter #(= :interest (:entry/kind %)) (journal/entries state account-id)))]]
     {:account/id  account-id
      :currency    (journal/currency state account-id)
      :restated    (mapv #(journal/balance state account-id %)
                         (range window/first-day (inc window/last-day)))
      :accruals    (mapv :amount accruals)
      :accrual-sum (reduce + (map :amount accruals))
      :capitalized (:amount interest)})})

(defn- authorization-line [{:keys [auth/id state amount settled available-after]}]
  (case state
    :approved (format "%s APPROVED hold %s" id amount)
    :settled  (format "%s SETTLED %s (hold %s released)" id settled amount)
    :declined (format "%s DECLINED %s (available after hold %s)" id amount available-after)))

(defn- fee-line [fee]
  (format "%s for Day %d (closing %s)" (:amount fee) (:fee/for-day fee) (:fee/closing-balance fee)))

(defn- error-line [{:keys [source error/code error/detail]}]
  (format "%s %s (%s)" (if (keyword? source) (name source) source) (subs (str code) 1)
          (str/join ", " (map (fn [[k v]] (str (name k) " " v)) detail))))

(defn- or-none [lines]
  (if (seq lines) (str/join " | " lines) "none"))

(defn- account-day-lines [{:keys [account/id currency closing-balance available authorizations
                                  fees errors late back-dated]}]
  [(format "%s %s  close %10s   available %10s" id (name currency) closing-balance available)
   (str "  auths  : " (or-none (map authorization-line authorizations)))
   (str "  fees   : " (or-none (map fee-line fees)))
   (str "  errors : " (or-none (map error-line errors)))
   (str "  notes  : " (or-none (concat
                                (map (fn [[source value-date]] (format "%s back-dated to Day %d" source value-date)) back-dated)
                                (map (fn [[source event-day value-date]]
                                       (format "%s arrived late (event day %d, value date Day %d)" source event-day value-date))
                                     late))))])

(defn report-lines [state]
  (concat
   (mapcat (fn [day]
             (concat [(format "=== Day %d — as known at end of day ===" day)]
                     (mapcat account-day-lines (:accounts (day-report state day)))
                     [""]))
           (range window/first-day (inc window/last-day)))
   ["=== Window close — restated by value date ==="]
   (mapcat (fn [{:keys [account/id currency restated accruals accrual-sum capitalized]}]
             [(format "%s %s  %s" id (name currency)
                      (str/join " | " (map-indexed #(format "D%d %s" (inc %1) %2) restated)))
              (format "  interest: %s = %s | capitalized %s | match: %s"
                      (str/join " + " accruals) accrual-sum (or capitalized "none")
                      (if (or (= accrual-sum capitalized) (and (nil? capitalized) (zero? accrual-sum))) "yes" "NO"))])
           (:accounts (final-report state)))))

(defn print-report [state]
  (doseq [line (report-lines state)]
    (println line)))
