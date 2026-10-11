# ledger-core

An in-memory, append-only account ledger in Clojure.
The engine is pure: each step takes a state and returns a new state.
The test suite replays the event stream E1..E10 over Day 1 to Day 6.
For each day, it prints the closing balance, the fees, the authorization states and the errors.
There is no web layer, no database and no `-main` (see DECISIONS.md D1).

## How to run

With Docker only (no Java or Clojure needed):

```sh
docker build -t ledger-core .
docker run --rm ledger-core
```

With a local Clojure CLI and Java 21:

```sh
clojure -X:test
```

Both commands run all tests and print the report. The command exits with a non-zero code because of the one expected failure below.

**The suite has exactly one expected failure:**

```
ledger.known-failure-test/capitalized-interest-equals-exact-interest
expected: 0.92M
actual:   0.93M
```

The engine rounds each daily accrual and adds the results, so ACC-001 gets 0.93.
The exact interest is 0.918, which rounds to 0.92, so the design pays 0.01 AED too much for each account in each window.
The fix (a reallocation of the daily values) adds a step that the rules do not ask for.
The test stays red on purpose. All other tests must pass.

## Project layout

```
deps.edn
resources/events.edn          the event stream E1..E10, in replay order
src/ledger/money.clj          currency scales, HALF_EVEN rounding, split into instalments
src/ledger/journal.clj        append-only journal, balance by value date, holds, available balance
src/ledger/auth.clj           authorization decision, settlement, settlement rejections
src/ledger/engine.clj         event processing, day clock, late arrival, reversal
src/ledger/eod.clj            end of day: overdraft fees with look-back, accruals, capitalization
src/ledger/report.clj         the daily report and the restated tables
src/ledger/window.clj         window constants (Day 1 to Day 6)
Dockerfile                    runs the suite with no local Java or Clojure
test/ledger/                  unit tests for each namespace, the scenario replay,
                              and known_failure_test.clj (the one expected failure)
```

## Reading the report

The report prints inside `ledger.scenario-test`.

- One block per day: `=== Day N — as known at end of day ===`. It shows what the bank knew at the end of that day.
- Per account: `close` (ledger balance, value date ≤ Day N), `available` (close − active holds), `auths` (state of each known authorization), `fees` (fees booked that day, with the day they are for and the closing balance that caused them), `errors` (rejected events), `notes` (back-dated entries and late arrivals).
- The last block, `Window close — restated by value date`, shows the final balances of each day after all back-dated events, and the interest check: sum of rounded accruals = capitalized credit.

## Key results

ACC-001 (AED), as known at the end of each day:

| Day | Events | Ledger | Available | What happens |
|---|---|---|---|---|
| 1 | E1, E2 | 250.00 | 250.00 | Credit 1200.00, debit 950.00 |
| 2 | E3 | 250.00 | 50.00 | Auth-A approved (250.00 - 200.00 = 50.00) |
| 3 | E4 | 650.00 | 450.00 | Credit 400.00 |
| 4 | E5, E6 | 465.00 | 465.00 | Auth-A settled for 185.00, full hold released. E6 rejected (unknown Auth-Z) |
| 5 | E7, E8 | -230.00 | -230.00 | E7 (-620.00, value date D2). Auth-B declined (-245.00). 3 fees: D2, D4, D5 |
| 6 | E9, E10 | 390.93 | 390.93 | E9 reverses E7. The fees stay. Interest 0.93 capitalized |

ACC-001 restated by value date, at the end of the window:

| | D1 | D2 | D3 | D4 | D5 | D6 |
|---|---|---|---|---|---|---|
| Close | 250.00 | 225.00 | 625.00 | 415.00 | 390.00 | 390.93 |
| Accrual (rounded) | 0.10 | 0.09 | 0.25 | 0.17 | 0.16 | 0.16 |

ACC-002 (BHD):

- E10 credits 10.000 in 3 instalments: 3.333 + 3.333 + 3.334.
- E10 arrives late, in the Day 6 window, with value date 5.
- Day 5 close: 0.000 as known, 10.000 restated.
- Day 6 close: 10.008 (interest 0.008).

## Criteria verdicts

| # | Verdict |
|---|---|
| 1, 3, 4 | Accepted |
| 5 | Accepted, flagged as a trap |
| 2, 6, 7, 8 | Refused |

The reasons are in REJECTED.md.

## Other documents

- [DECISIONS.md](DECISIONS.md): decision principles, engineering decisions and trade-offs (D1-D24).
- [AMBIGUITIES.md](AMBIGUITIES.md): each ambiguity in the rules, the options with their numbers, and the resolution.
- [NUMBERS.md](NUMBERS.md): each constant, its source, and why it has that value.
- [REJECTED.md](REJECTED.md): refused criteria with reasons, and abandoned approaches.
- [docs/architecture.html](docs/architecture.html) (print to PDF): append-only at scale, value dates in production, authorization lifecycle, what was cut.
- [WORKLOG.md](WORKLOG.md): the build steps with real timestamps.
