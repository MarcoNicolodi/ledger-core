(ns ledger.known-failure-test
  (:require [clojure.test :refer [deftest is]]
            [ledger.engine :as engine]
            [ledger.journal :as journal]))

;; KNOWN FAILURE: this test fails against my design on purpose.
;; What it reveals: the engine rounds each daily accrual, then adds them (0.93).
;; The exact interest is 0.918, which rounds to 0.92. My reading of the interest
;; rule pays 0.01 AED too much for each account in each window. Across millions
;; of accounts this is real money. The fix (largest-remainder reallocation of the
;; daily values) adds a step that the rules do not ask for. See AMBIGUITIES.md #8.
(deftest capitalized-interest-equals-exact-interest
  (let [state    (engine/replay engine/accounts (engine/load-events))
        interest (first (filter #(= :interest (:entry/kind %)) (journal/entries state "ACC-001")))]
    (is (= 0.92M (:amount interest)))))
