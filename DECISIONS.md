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
