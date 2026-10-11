(ns ledger.window)

(def first-day 1)
(def last-day 6)

(defn in-window? [day]
  (<= first-day day last-day))
