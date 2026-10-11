# Numbers

This file lists every constant in the ledger.
For each constant, it gives the value, the source, and why the value is not half of it.

- **given:** the assessment text states the value.
- **chosen:** I selected the value. The reason is in the last column.
- **derived:** the value follows from other values.

## Constants

| Constant | Value | Source | Why this value and not half of it |
|---|---|---|---|
| Overdraft fee | AED 25.00 | given | The rule fixes 25.00. Half (12.50) has no source. The replay books 3 fees, so 75.00 in total. |
| Daily interest rate | 0.0004 | given (0.04%) | 0.04% = 0.0004. The value 0.04 is 100 times too large (4% per day). Half (0.0002) is 0.02% per day and halves each daily accrual. |
| AED scale | 2 | given | AED has 2 decimals (100 fils per dirham). Half (scale 1) cannot store fils: the interest 0.93 becomes 0.9. |
| BHD scale | 3 | given | BHD has 3 decimals (1000 fils per dinar). Half (1.5) is not a valid scale: a scale is a whole number of digits. Scale 2 cannot store 3.333 or 0.004. |
| Rounding mode | HALF_EVEN | chosen | The rules do not give a mode. HALF_EVEN has no upward bias over many roundings. "Half" does not apply to a mode. No replay value is exactly on a half, so HALF_UP gives the same numbers. |
| Rounding of the regular instalment | DOWN | chosen | `money/split` rounds the regular part down, so the parts never add up to more than the total. The last part takes the rest. |
| E10 instalments | 3 | given | E10 has `:instalments 3`. |
| Instalment remainder | BHD 0.001, to the last instalment | derived | 10.000 - 2 × 3.333 = 3.334. The remainder is one minor unit. Half (0.0005) cannot exist at scale 3. See DECISIONS.md D10. |
| Window | Day 1 to Day 6 | given | The text fixes the window. Interest capitalizes at the end of Day 6. |
| Daily accruals for each account | 6 | derived | One accrual for each day of the window (Day 1 to Day 6). |
| Fee threshold | close < 0 (strict) | given | The rule says "negative". Zero is not negative. |
| Interest threshold | close > 0 (strict) | given | The rule says "positive balances only". Zero is not positive. |
| Approval threshold | available after the hold ≥ 0 (zero approved) | given | The rule says "≥ 0". A test checks that an exact zero is approved. |
| Fees for each account and value day | at most 1 | given + chosen reading | The rule says "once per day". I read "day" as the value day that is checked (see AMBIGUITIES.md 3). |
| Opening balance ACC-001 | AED 0.00 | given | Account data, not a Day 0 entry (see DECISIONS.md D8). |
| Opening balance ACC-002 | BHD 0.000 | given | Same as ACC-001. The scale matches the currency. |

## Derived target values

The replay tests assert these values.

| Value | Amount | How |
|---|---|---|
| ACC-001 Day 2 close at the end of Day 5, before fees | -370.00 | 1200.00 - 950.00 - 620.00 (criterion 1) |
| Auth-B available after the hold | -245.00 | -155.00 - 90.00, so declined |
| ACC-001 end of Day 5 (as known) | -230.00 | 3 fees: D2, D4, D5 |
| ACC-001 interest, sum of rounded daily accruals | 0.93 | 0.10 + 0.09 + 0.25 + 0.17 + 0.16 + 0.16 |
| ACC-001 interest, exact | 0.918 | 0.100 + 0.090 + 0.250 + 0.166 + 0.156 + 0.156 |
| ACC-001 Day 6 close | 390.93 | 390.00 + 0.93 |
| ACC-002 Day 6 close | 10.008 | 10.000 + 0.004 (D5) + 0.004 (D6) |

### The rounding drift of 0.01

The exact interest for ACC-001 is 0.918. Rounded once, it is 0.92.
The engine rounds each day and adds the results, so it pays 0.93.
This is 0.01 AED more than the exact value, for one account in one window.
The rule "the rounded daily accruals must sum exactly to the capitalized total" permits this result.
The one failing test shows this drift on purpose (see README.md).

## A Clojure pitfall: `=` ignores the scale

In Clojure, `(= 1.0M 1.00M)` is `true`. So `=` cannot detect an amount with the wrong scale.
The code checks `(.scale amount)` explicitly at the boundary (`money/valid-amount?`).
An amount with a wrong scale is an error. See DECISIONS.md D2.
