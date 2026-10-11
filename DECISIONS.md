# Design decisions and trade-offs

This file records engineering choices: how the code is built.
How I read the rules is in AMBIGUITIES.md. Constants are in NUMBERS.md.
Refused criteria and abandoned approaches are in REJECTED.md.

Each entry has the decision, the alternatives, and the trade-off.

---

## D1. Tests are the only entry point

- **Decision:** No `-main`. The test suite replays the stream and prints the report (`clojure -X:test`).
- **Alternatives:** A `-main` script, or a REPL session.
- **Trade-off:** One command does both things: it runs the checks and shows the output. The cost is that the report prints between test results, and a failed assertion can hide part of the report.

## D2. Money is `BigDecimal` at the currency scale

- **Decision:** Every amount is a `BigDecimal` whose scale equals the currency scale (AED 2, BHD 3). Rounding is HALF_EVEN.
- **Alternatives:** Integer minor units (fils as `long`); `double`.
- **Trade-off:** `BigDecimal` is exact and easy to read (`1200.00M`), and the scale is part of the value. Clojure `=` ignores the scale (`(= 1.0M 1.00M)` is `true`), so the code checks `.scale` explicitly at the boundary. Minor units remove that problem, but each boundary needs conversions, and so does the interest calculation (0.0004 is not an integer). `double` is not exact, so I rejected it at once.

## D3. Amounts are positive; direction is a separate field

- **Decision:** Events and entries carry a positive `:amount` and a `:direction` (`:credit` / `:debit`). Only `journal/signed` makes a signed number.
- **Alternatives:** Signed amounts (`-950.00`).
- **Trade-off:** A wrong sign cannot enter through the input. One function owns the sign rule. The cost is one extra field on each record.

## D4. One immutable state map with one journal vector

- **Decision:** The state is `{:accounts … :journal […]}`. Every operation is a pure function `state → state`. Only `journal/append` adds to the journal (`conj`). There are no atoms.
- **Alternatives:** An atom with in-place updates; separate collections for entries, holds and errors.
- **Trade-off:** Append-only is true by construction, and a test checks that the old journal is a prefix of the new one. Every step is easy to test and to replay. The cost is that one vector holds records of different kinds, so queries filter by `:record/type`.

## D5. Balances and authorization states are derived, not stored

- **Decision:** `balance`, `available` and the authorization state are calculated from the journal on every call.
- **Alternatives:** Keep running balances and a mutable status for each authorization.
- **Trade-off:** There is one source of truth, so the derived values cannot drift from the records. A back-dated entry needs no special update logic. The cost is a full scan for each query, O(n). With 10 events this is not important. At production scale, it needs indexes or cached balances for each value date, and those must be rebuilt from the journal.

## D6. "As known at Day N" comes from `:known-at`, not from snapshots

- **Decision:** Each record has two days: `:value-date` (when the money counts) and `:posted-day` (when the bank received it). `balance … :known-at N` uses only records with `posted-day ≤ N`. The per-day report uses this to show what the bank knew at the end of each day.
- **Alternatives:** Take a copy of the state, or of the report data, at each end of day and print it later.
- **Trade-off:** The journal stays the only source of truth, and any past view can be rebuilt exactly, because no record is ever changed or removed. Snapshots are simpler to print, but they are a second copy of the truth that can disagree with the journal, and they only exist for the moments when someone took them. The cost of `:known-at` is that every record must have a correct `:posted-day`, and the queries take one more parameter.

## D7. Holds are journal records but not ledger entries

- **Decision:** An approved authorization appends `:hold/placed`. A settlement appends `:hold/released`. Only `:entry` records count for `balance`. Holds count only for `available`.
- **Alternatives:** A separate mutable map of active holds.
- **Trade-off:** Holds follow the same append-only rule and the same `:known-at` view as the money. Criterion 5 (a hold reduces available balance, not ledger balance) is true by construction. The cost is that active holds are calculated as the placed holds minus the released ones.

## D8. The opening balance is account data

- **Decision:** Each account has `:opening-balance` in its configuration (0.00 / 0.000). `balance` starts from it.
- **Alternatives:** An opening entry with value date 0.
- **Trade-off:** The window is Day 1 to Day 6, so no "Day 0" record is necessary, and the journal holds only real events. An opening entry would make the journal self-contained, but it adds a value date outside the window.

## D9. Each event carries its currency; the engine never converts

- **Decision:** Every money event has `:currency`. If it does not match the account currency, the engine rejects the event.
- **Alternatives:** Take the currency only from the account.
- **Trade-off:** The engine detects a mismatch instead of booking a wrong amount without an error. The cost is a redundant field on each event.

## D10. The remainder of a split goes to the last instalment

- **Decision:** `money/split` gives n-1 parts of amount/n, rounded down. The last part takes the remainder (10.000 / 3 → 3.333, 3.333, 3.334).
- **Alternatives:** The remainder goes to the first part; it is spread one minor unit at a time; the split is rejected.
- **Trade-off:** The parts always add up exactly, and the rule is easy to state and to test. The choice of first or last part is arbitrary. "Last" means the customer sees the regular amount first.

## D11. Rejections are journal records with an error code

- **Decision:** A refused event appends a `:rejection` record with `:error/code` and `:error/detail`. It never throws, and no money moves.
- **Alternatives:** Throw an exception; return an error and keep no record.
- **Trade-off:** Errors are part of the audit trail, and the report reads them from the journal like any other record ("no record is ever deleted" also covers refused events). The replay never stops at a bad event. The cost is that callers must look for rejection records instead of catching an exception.

## D12. The authorization decision uses the balance of the processing day

- **Decision:** `available = balance(value date ≤ today) − active holds`, where "today" is the processing day of the authorization. The hold is approved when `available − amount ≥ 0` (zero is approved).
- **Alternatives:** Use the event's value date; include future-dated entries.
- **Trade-off:** It matches what a bank can know at the moment of the decision. In this stream no event has a value date later than its processing day, so the alternatives give the same results. The decision is final: a later back-dated entry does not re-open it (see AMBIGUITIES.md, Auth-B).

## D13. Settlement rules

- **Decision:** A settlement must refer to an authorization that is approved and not settled, on the same account, for an amount not above the hold. It books a `:settlement` debit for the settled amount and releases the **full** hold. Each failed check is its own error code: `unknown-authorization`, `authorization-declined`, `already-settled`, `account-mismatch`, `exceeds-hold`. A second authorization with an existing id is rejected (`duplicate-id`).
- **Alternatives:** Release only the settled amount and keep the rest on hold; accept a settlement above the hold with a tolerance; accept settlements with no authorization (force post).
- **Trade-off:** One settlement closes one authorization, so no hold stays open after it with no owner. Separate codes make each refusal visible in the report. The cost is that real card flows with several partial settlements, or tips above the authorized amount, are not supported. If the extra 15.00 of Auth-A stayed on hold, Auth-B would still be declined (−260.00 instead of −245.00), so this choice does not change any verdict.

## D14. The authorization state is derived from records

- **Decision:** `auth-state` folds the records of one authorization id: `:hold/placed` → approved, `:hold/released` → settled, `:auth/declined` → declined. With `:known-at`, it gives the state on any past day.
- **Alternatives:** Store a status field and update it.
- **Trade-off:** Same as D5 and D6: one source of truth, past states available for the report. The cost is a scan of the journal for each lookup.

## D15. Events are dispatched with a multimethod on `:event/type`

- **Decision:** `engine/apply-event` is a multimethod. Validation runs once, before dispatch. An unknown type is rejected by the `:default` method.
- **Alternatives:** One `case` on the event type.
- **Trade-off:** A new event type is one new method, and no existing code changes. The cost is that the dispatch table is spread over several definitions.

## D16. Validation happens at the boundary, in a fixed order

- **Decision:** Before any handler runs, the engine checks: event day in the window, known account, value date in the window, and (for money events) currency equal to the account currency and a valid amount. The first failed check gives the error code.
- **Alternatives:** Each handler validates its own input.
- **Trade-off:** Handlers can trust their input, and every event type gets the same checks. The cost is that an event with several problems reports only the first one.

## D17. The day clock moves forward only

- **Decision:** The engine keeps `:day`. When an event has a later day, the engine first closes every day in between (`eod/close-day`). An event with an earlier day is not rejected: it is posted in the current day with `:late? true` and its original `:event-day`. After the stream, the engine closes the remaining days up to Day 6.
- **Alternatives:** Reject late events; sort the stream by day before the replay; re-open closed days.
- **Trade-off:** The stream order is the authority ("replayed in this order"), and a closed day stays closed, as in a real bank. E10 keeps its value date (Day 5), so its money still counts for Day 5. The cost is that the as-known report for Day 5 cannot show E10, because the bank did not have it at the end of Day 5.

## D18. A reversal copies its target

- **Decision:** A reversal event carries no amount. The engine finds the target entries by `:source`, and books one opposite entry for each, with the reversal's value date and `:reversal/target`. It rejects an unknown target, a target that is itself a reversal, and a second reversal of the same target.
- **Alternatives:** The reversal event carries its own amount; reversals of reversals are allowed.
- **Trade-off:** The reversed amount cannot differ from the original, and a target with several entries (instalments) is reversed completely. Rejecting chains keeps the meaning of each entry simple. The cost is that a correction of a wrong reversal needs a new normal event, not a reversal of the reversal.

## D19. Fees are booked by an end-of-day run that looks back

- **Decision:** At the close of day N, `eod/assess-fees` checks every day from Day 1 to Day N, in ascending order. A day with a negative closing balance and no fee yet gets one `:fee` entry: value date = that day, posted day = N, with `:fee/for-day` and `:fee/closing-balance` for audit. The run never removes or refunds a fee.
- **Alternatives:** Check only day N; recalculate all fees from the final ledger; refund a fee when a later event makes its day positive.
- **Trade-off:** A back-dated entry (E7) gets its fees at the next close, and each fee shows the balance that caused it. The ascending order is necessary, because a fee changes the balance of later days (the Day 2 fee makes Day 3 5.00, not 30.00). The cost is a scan of all past days at every close, and fees that stay after the reversal that removed their cause (see AMBIGUITIES.md and REJECTED.md).

## D20. The window constants have their own namespace

- **Decision:** `ledger.window` holds `first-day`, `last-day` and `in-window?`. Both the engine and the end-of-day run use it.
- **Alternatives:** Keep the constants in `engine`.
- **Trade-off:** `engine` requires `eod`, so `eod` cannot require `engine` (circular dependency). A small shared namespace removes the cycle.

## D21. A fee that cannot be booked is an error record, once per day

- **Decision:** The fee is defined only in AED (`overdraft-fee {:AED 25.00M}`). For a negative BHD day, the run appends a `:rejection` with `:fee/no-fx-rate` and `:fee/for-day`. The same day never gets a second error.
- **Alternatives:** Convert 25.00 AED with an invented rate; book 25.000 BHD; ignore the case.
- **Trade-off:** The engine never invents a rate or an amount, and the gap is visible in the report. The cost is that a negative BHD account pays no fee until someone defines the fee for BHD. The replay never makes ACC-002 negative.

## D22. Interest is calculated once, at the close of Day 6, and keeps an audit trail

- **Decision:** At the close of Day 6, `eod/capitalize` calculates one accrual for each day of the window from the balances by value date at that moment (so E9 is included). Each day: `raw = balance × 0.0004` when the balance is above zero, else 0; `amount = round(raw)` at the currency scale. The run appends one `:accrual` record for each day (balance, raw, amount), then one `:interest` credit with value date Day 6 whose amount is the sum of the rounded accruals. All accruals are calculated before any record is appended, so the Day 6 accrual never includes the capitalization credit.
- **Alternatives:** Book an accrual at each end of day from the balances known then; calculate the credit as `round(sum of raw)` and adjust the daily values to match.
- **Trade-off:** The `:accrual` records make "the rounded accruals sum exactly to the capitalized total" checkable from the journal itself, and the equality holds by construction. The credit follows the value dates, like the rule's definition of the closing balance. The cost is the rounding drift: the rounded accruals add up to 0.93, the exact interest is 0.918 (0.92). The known failing test shows this.
- **Detail:** A day with zero interest still gets an `:accrual` record (amount 0). An account whose total is zero gets no `:interest` entry, because an entry of 0.00 moves no money.

## D23. The report is data first, text second

- **Decision:** `report/day-report` and `report/final-report` return plain maps. `report-lines` turns them into text, and `print-report` prints the lines. The scenario test checks the maps, and prints the text once.
- **Alternatives:** Build the text directly while walking the journal; check the printed text in the tests.
- **Trade-off:** The tests check numbers and states, not spacing, so a change of layout breaks no test. The cost is one more layer between the journal and the output.

## D24. Each day block shows the view "as known at the end of that day"

- **Decision:** The block for Day N uses `:known-at N` for balances, available balance and authorization states, and lists the fees, errors, back-dated entries and late arrivals posted on Day N. A final block shows the balances restated by value date and the interest check (`sum of accruals = capitalized`).
- **Alternatives:** Print only the final restated balances; print each day block from the final journal without `:known-at`.
- **Trade-off:** The reader sees what the bank knew each day (Day 4: 465.00) and how back-dated events changed the past (restated Day 4: 415.00). Both views come from the same journal. The cost is a longer report, and the reader must know which view each block shows (README, "Reading the report").
