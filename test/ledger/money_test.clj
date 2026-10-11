(ns ledger.money-test
  (:require [clojure.test :refer [deftest is testing]]
            [ledger.money :as money]))

(defn- scale-of [^java.math.BigDecimal x] (.scale x))

(deftest valid-amount
  (testing "scale must match the currency"
    (is (money/valid-amount? :AED 1200.00M))
    (is (money/valid-amount? :BHD 10.000M))
    (is (not (money/valid-amount? :BHD 3.3333M)))
    (is (not (money/valid-amount? :AED 1.5M))))

  (testing "must be positive"
    (is (not (money/valid-amount? :AED 0.00M)))
    (is (not (money/valid-amount? :AED -1.00M))))

  (testing "unknown currency and non-decimal values are invalid"
    (is (not (money/valid-amount? :USD 1.00M)))
    (is (not (money/valid-amount? :AED 1.0)))))

(deftest round-half-even
  (is (= 0.12M (money/round :AED 0.125M)) "tie goes to the even digit")
  (is (= 0.14M (money/round :AED 0.135M)) "tie goes to the even digit")
  (is (= 0.17M (money/round :AED 0.166M)))
  (is (= 0.004M (money/round :BHD 0.0040000M)))
  (is (= 2 (scale-of (money/round :AED 1M))))
  (is (= 3 (scale-of (money/round :BHD 1M)))))

(deftest split-into-instalments
  (testing "10.000 BHD in 3 parts: the last part takes the extra 0.001"
    (let [parts (money/split :BHD 10.000M 3)]
      (is (= [3.333M 3.333M 3.334M] parts))
      (is (= 10.000M (reduce + parts)))
      (is (every? #(= 3 (scale-of %)) parts))))

  (testing "an amount that divides evenly gives equal parts"
    (is (= [400.00M 400.00M 400.00M] (money/split :AED 1200.00M 3)))))
