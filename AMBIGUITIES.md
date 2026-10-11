# Ambiguities and how I resolved them

This file lists each place where the rules or the event stream permit more than one reading.
Each entry gives the question, the options, the resolution and the reason.
Where an option changes an amount, the entry gives the number.

Engineering choices (how the code is built) are in DECISIONS.md.
Constants are in NUMBERS.md. Refused criteria are in REJECTED.md.

## Decision principle

I used these three steps, in this order:

1. Do what the text says.
2. Where the text says nothing, add no money movement.
3. For amounts, choose one point of evaluation and give the reason.

All amounts below are for ACC-001 (AED) unless the entry names ACC-002 (BHD).
"Dn" means "value date Day n". "EOD n" means "the end-of-day run of Day n".

---

## A. Overdraft fee

### 1. When is "that day's closing ledger balance" evaluated?

- **Question:** E7 arrives on Day 5 with value date Day 2. Does the fee run look back at earlier days?
- **Options:**
  - (a) Each EOD checks all days ≤ today, in ascending order, by value date. **3 fees** (D2, D4, D5). End of Day 5: -230.00. Day 6 close: 390.93.
  - (b) Each EOD checks only today. **1 fee** (D5).
  - (c) Assess fees once, from the final ledger after all events. **0 fees**. Day 6 close: 466.03.
- **Resolution:** (a).
- **Why:** The rule defines the close as "all entries with value_date ≤ that day". After E7, Day 2 has a negative close by that definition. Criterion 1 says "before any fee is assessed" at the end of Day 5, so a fee run occurs at the end of Day 5. The rule says fees are "booked", so each fee is a record at the time of its assessment. Option (c) conflicts with both points. The order is important: a fee on D2 changes the close of D3, D4 and D5.
- **The EOD 5 cascade:** D2 -370.00 → fee → -395.00. D3 = -395.00 + 400.00 = 5.00 (positive, no fee). D4 = 5.00 - 185.00 = -180.00 → fee → -205.00. D5 = -205.00 → fee → -230.00.

### 2. What is "the day assessed" for the fee value date?

- **Question:** EOD 5 finds negative closes on D2, D4 and D5. Which value date does each fee get?
- **Options:**
  - (a) The negative day (D2, D4, D5). Day 6 close: 390.93.
  - (b) The processing day (all three fees on D5). Day 6 close: 390.97.
- **Resolution:** (a).
- **Why:** The rule links the fee to "that day's closing ledger balance". The day assessed is the day whose close is negative. Option (b) puts the fee on a day that the check did not examine.

### 3. Does "once per day" mean once per checked day or once per processing day?

- **Question:** EOD 5 finds three negative days. Is it one fee for each day, or one fee for each EOD run?
- **Options:**
  - (a) At most one fee for each account and each value day (chosen). EOD 5 books 3 fees.
  - (b) At most one fee for each EOD run. EOD 5 books 1 fee (25.00), not 3.
- **Resolution:** (a).
- **Why:** In the rule, "day" is the day whose close is checked. Each negative value day gets its own fee. The same limit stops a second fee on the same value day: EOD 6 does not book again on D2, D4 or D5.

### 4. Are the fees refunded after the reversal E9?

- **Question:** E9 reverses E7. After E9, D2, D4 and D5 are positive. Do the 75.00 of fees go back?
- **Options:**
  - (a) No refund. Fees stay. Balance after E9: 390.00 (before interest).
  - (b) Refund the 3 fees. Balance after E9: 465.00 (before interest).
- **Resolution:** (a).
- **Why:** No rule and no event asks for a refund (principle 2). The ledger is append-only, so booked fees stay. The fee rule checks a close at the time of assessment. It does not define a later correction. I tried (b) during design and abandoned it (see REJECTED.md).

### 5. Is a debit checked against the available balance?

- **Question:** Do debits need funds, as authorizations do?
- **Options:**
  - (a) No check on debits (chosen). E7 is booked.
  - (b) Check debits. E7 fails at Day 5 (465.00 - 620.00 = -155.00). 0 fees. The Day 2 close at the end of Day 5 is 250.00, not -370.00.
- **Resolution:** (a).
- **Why:** The rules check funds only for authorizations. The fee rule assumes that overdrafts occur. With (b), criterion 1 becomes wrong.

### 6. What is the overdraft fee for a BHD account?

- **Question:** The fee is AED 25.00. ACC-002 is in BHD.
- **Options:** Convert at some rate; charge BHD 25.000; skip without a record; record an error.
- **Resolution:** The engine records the error `:fee/no-fx-rate`. No money moves.
- **Why:** The fee is defined only in AED. The text gives no FX rate. The engine never converts (see DECISIONS.md D9). ACC-002 is never negative, so the replay never triggers this case. A unit test covers it.

---

## B. Interest

### 7. Which balances are the basis for interest?

- **Question:** Interest is booked at the end of Day 6. Does it use the final balances by value date, or the balances as known at each EOD?
- **Options:**
  - (a) Final balances by value date, at the end of Day 6 (chosen). Interest 0.93. Day 6 close: 390.93. ACC-002: 10.008.
  - (b) Balances as known at each EOD. Interest 0.81. Day 6 close: 390.81. ACC-002: 10.004.
- **Resolution:** (a).
- **Why:** Interest is booked only once, at the end of Day 6. At that moment, "closing ledger balance (value_date ≤ that day)" already includes E9 and E10. Fees are different: the engine books each fee at its own assessment, so each fee uses the balance known at that time (principle 3).

### 8. Is the capitalized total the sum of the rounded accruals, or the rounded exact sum?

- **Question:** "The rounded daily accruals must sum exactly to the capitalized total." Which value is primary?
- **Options:**
  - (a) Round each daily accrual. The total is their sum: 0.93 (chosen).
  - (b) Round the exact sum (0.918 → 0.92). Reallocate the daily values so they add up to 0.92. Day 6 close: 390.92.
- **Resolution:** (a).
- **Why:** "Rounded daily accruals" means: round each day. Option (b) adds a reallocation step that the rules do not ask for. The cost of (a) is a drift of 0.01. The one failing test shows this drift (see README.md).

### 9. Does the Day 6 interest base include the capitalization credit?

- **Question:** The capitalization credit has value date Day 6. Does the Day 6 accrual use it?
- **Options:**
  - (a) No. Day 6 base is 390.00, accrual 0.16 (chosen).
  - (b) Yes. The credit depends on the base, and the base depends on the credit.
- **Resolution:** (a).
- **Why:** Option (b) is circular. A fee and interest cannot both apply to one day: interest needs a close > 0, and a fee needs a close < 0. So the order of fee and interest on Day 6 has no effect.

### 10. Is the interest simple or compound inside the window?

- **Question:** Does each daily accrual add to the base of the next day?
- **Options:**
  - (a) Simple: rate × closing ledger balance. Accruals are not in the ledger until the Day 6 capitalization (chosen).
  - (b) Compound: each accrual adds to the next base.
- **Resolution:** (a).
- **Why:** The rate applies to the "closing ledger balance". Accruals enter the ledger only when they capitalize at the end of Day 6. In this replay, both options give 0.93 for ACC-001 and 0.008 for ACC-002.

### 11. What happens on a zero balance?

- **Question:** Zero is neither positive nor negative.
- **Options:** Treat zero as negative (fee); treat zero as positive (accrual of 0); treat zero as neither.
- **Resolution:** Neither. No fee and no interest.
- **Why:** The fee needs a "negative" close. Interest applies to "positive balances only". ACC-002 is 0.000 on Days 1-4, so it gets no fee and no accrual on those days.

---

## C. Money and rounding

### 12. How do you split BHD 10.000 into "three equal instalments"?

- **Question:** 10.000 / 3 is not exact at 3 decimals.
- **Options:**
  - (a) 3.334 × 3 = 10.002 (criterion 7). It creates 0.002 from nothing.
  - (b) 3.333 × 3 = 9.999. It loses 0.001.
  - (c) 3.333 / 3.333 / 3.334. The remainder goes to the last part (chosen).
  - (d) 3.334 / 3.333 / 3.333. The remainder goes to the first part.
- **Resolution:** (c).
- **Why:** Equal parts are not possible at 3 decimals. Only (c) and (d) add up to 10.000. The choice of last or first is arbitrary (see DECISIONS.md D10).

### 13. Which rounding mode?

- **Question:** The rules give the precision, but not the rounding mode.
- **Options:** HALF_EVEN; HALF_UP; DOWN.
- **Resolution:** HALF_EVEN.
- **Why:** HALF_EVEN has no upward bias over many roundings. No value in this replay is exactly on a half, so HALF_UP gives the same numbers.

---

## D. Event order and time

### 14. How does the engine process E10?

- **Question:** E10 is labelled Day 5, but it comes after the Day 6 event E9 in the stream.
- **Options:**
  - (a) Accept it as a late arrival in the Day 6 window, with value date 5. Flag it `:late?` (chosen).
  - (b) Sort the stream by day. EOD 5 has already run, so time goes backwards.
  - (c) Reject it as out of order. ACC-002 stays 0.000.
- **Resolution:** (a).
- **Why:** The stream is "replayed in this order", so the order is the authority. As known at the end of Day 5, ACC-002 is 0.000. Restated by value date, it is 10.000 on Day 5.

### 15. What is the opening balance?

- **Question:** Is the opening balance a ledger entry on a "Day 0"?
- **Options:** An entry with value date 0; account data.
- **Resolution:** Account data: ACC-001 0.00, ACC-002 0.000.
- **Why:** The window is Day 1 to Day 6. No Day 0 record is necessary (see DECISIONS.md D8).

### 16. What does the report show for back-dated events?

- **Question:** E7 and E10 change the past. Does the report show what the bank knew, or the corrected history?
- **Options:** As known at the end of Day N; restated by value date; both.
- **Resolution:** Both.
- **Why:** Each view alone hides the effect of back-dated entries. Example: the ACC-002 Day 5 line is 0.000 as known and 10.000 restated (see DECISIONS.md D6).

---

## E. Authorizations and settlements

### 17. Which balance does an authorization use?

- **Question:** "Available balance (ledger - active holds) ≥ 0 after the hold." On which day, and is zero approved?
- **Options:** Balance with value date ≤ processing day; balance at the event value date; all entries, future-dated included.
- **Resolution:** Balance with value date ≤ processing day, minus active holds. Approve when the result after the hold is ≥ 0. Zero is approved.
- **Why:** This is what the bank can know at the moment of the decision. Auth-A: 250.00 - 200.00 = 50.00, approved. Auth-B: -155.00 - 90.00 = -245.00, declined. No event in this stream has a value date after its processing day, so the options give the same result (see DECISIONS.md D12).

### 18. Is an authorization decision final?

- **Question:** E9 removes E7 on Day 6. Does the engine check Auth-B again?
- **Options:** Keep the decision; check it again after each back-dated change.
- **Resolution:** Keep the decision. Auth-B stays declined.
- **Why:** The merchant already received the answer. Without E7, Auth-B would have been approved (465.00 - 90.00 = 375.00).

### 19. Is a decline an error?

- **Question:** Does a declined authorization go in the error list?
- **Options:** Error (`:rejection` record); state (`:auth/declined` record).
- **Resolution:** State. The report shows it under authorization states.
- **Why:** The authorization event is valid. The bank answers "no". An error is an event that the engine cannot process.

### 20. Do holds expire?

- **Question:** Real card holds expire after some days. The rules give no expiry.
- **Options:** No expiry; an expiry period.
- **Resolution:** No expiry inside the 6 days.
- **Why:** No rule gives a period (principle 2). The replay has no effect: Auth-A is settled, and Auth-B has no hold.

### 21. What does "present in the ledger" mean in criterion 4?

- **Question:** A settlement needs an authorization "present in the ledger". Is the ledger only the money entries?
- **Options:** Money entries only; the full event journal.
- **Resolution:** The full event journal. A settlement must refer to an approved authorization that is not settled. The engine also rejects a settlement of a declined authorization (`:settlement/authorization-declined`) and of an already settled one (`:settlement/already-settled`). E6 gets `:settlement/unknown-authorization`.
- **Why:** The text says "no event record is mutated", so the ledger is the journal of event records. A declined or settled authorization has no hold to settle.

### 22. Are force posts permitted?

- **Question:** Real card networks have "force posts": a settlement with no authorization.
- **Options:** Book the debit; reject the settlement.
- **Resolution:** Reject.
- **Why:** The rules require a matching authorization (criterion 4). No rule covers force posts.

### 23. What happens on a partial settlement, or on a settlement above the hold?

- **Question:** E5 settles 185.00 on a hold of 200.00.
- **Options:**
  - Partial: release the full hold (chosen); keep 15.00 on hold. With 15.00 on hold, Auth-B is still declined (-260.00 instead of -245.00).
  - Above the hold: accept with a tolerance; reject.
- **Resolution:** A partial settlement releases the full hold. A settlement above the hold is rejected (`:settlement/exceeds-hold`).
- **Why:** Partial settlements are normal, for example at a fuel station. The text gives no tolerance for a larger amount (see DECISIONS.md D13).

---

## F. Reversals

### 24. What does a reversal book?

- **Question:** E9 names a target. Which amount and value date does the reversal entry get? Which reversals are invalid?
- **Options:** Amount from the event or from the target; value date from the target, from the event, or the processing day.
- **Resolution:**
  - The amount comes from the target (620.00).
  - The value date comes from the reversal event (E9: Day 2).
  - A reversal of a reversal is rejected.
  - A second reversal of the same target is rejected.
  - A reversal of an unknown target is rejected. A rejected event (for example E6) has no entry, so a reversal of it is also rejected.
- **Why:** A reversal cancels one entry exactly. A second cancellation would move money that no event asks for (principle 2).

---

## G. The intent of the author

### 25. Did the author expect Auth-B to be approved?

- **Question:** The assessment text says "Auth-B is never settled inside the window". This suggests that Auth-B has an active hold.
- **Options:** Approve Auth-B to match that intent; follow the rules.
- **Resolution:** Follow the rules. Auth-B is declined at -245.00.
- **Why:** The rules have priority over the criteria and the notes. This is also why criterion 5 is a trap (see REJECTED.md).
