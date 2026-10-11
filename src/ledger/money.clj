(ns ledger.money
  (:import (java.math BigDecimal RoundingMode)))

(def currencies
  {:AED {:scale 2}
   :BHD {:scale 3}})

(defn scale [currency]
  (get-in currencies [currency :scale]))

(defn valid-amount? [currency amount]
  (boolean
   (and (scale currency)
        (decimal? amount)
        (= (.scale ^BigDecimal amount) (scale currency))
        (pos? amount))))

(defn round [currency x]
  (.setScale (bigdec x) (int (scale currency)) RoundingMode/HALF_EVEN))

(defn zero [currency]
  (round currency 0M))

(defn split [currency ^BigDecimal amount n]
  (let [base (.divide amount (BigDecimal/valueOf (long n)) (int (scale currency)) RoundingMode/DOWN)
        last-part (- amount (* base (dec n)))]
    (conj (vec (repeat (dec n) base)) (round currency last-part))))
