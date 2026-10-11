# Rejected criteria and abandoned approaches

The assessment says: "Some of the following criteria are wrong."
I checked each criterion against the fixed rules. The rules have priority over the criteria.

- Refused: criteria 2, 6, 7, 8.
- Accepted: criteria 1, 3, 4.
- Accepted, but flagged as a trap: criterion 5.

The criterion text below is shortened.

---

## 1. Refused criteria

### Criterion 2 — refused

> E7 causes exactly one fee, on Day 2.

**Verdict:** Refused. E7 causes 3 fees: D2, D4 and D5.

**Reason:** Before E7, no day had a negative close, so there were no fees.
E7 makes the Day 2 close -370.00. The fee on D2 then changes the later days.

The EOD 5 cascade:

| Value day | Close before the fee | Fee? | Close after the fee |
|---|---|---|---|
| D2 | 250.00 - 620.00 = -370.00 | yes | -395.00 |
| D3 | -395.00 + 400.00 = 5.00 | no (positive) | 5.00 |
| D4 | 5.00 - 185.00 = -180.00 | yes | -205.00 |
| D5 | -205.00 | yes | -230.00 |

No reading of the fee rule that keeps one meaning of "day" gives "exactly one fee, on Day 2":

| Reading | Fees | Matches criterion 2? |
|---|---|---|
| Look-back by value date (chosen) | 3 (D2, D4, D5) | no |
| No look-back (each EOD checks only today) | 1 (D5) | no |
| Fee value date = processing day | 3 (all on D5) | no |
| Fees from the final ledger only | 0 | no |
| Each day checked on its close without fees | 3 (D2, D4, D5) | no |
| One fee per EOD run ("day" = processing day), value date = processing day | 1 (on D5) | no |
| One fee per EOD run, but value date = first negative day | 1 (on D2) | yes, but see below |

In the reading "each day checked on its close without fees", D3 is -370.00 + 400.00 = 30.00 and D4 is 30.00 - 185.00 = -155.00. So D4 and D5 are still negative.

The last row is the only way to get "one fee, on Day 2". It uses two meanings of "day" in one rule: "once per day" counts processing days, but the value date uses the checked day. The rule uses "day" for both, so I read it with one meaning. With one meaning, the result is 3 fees (value day) or 1 fee on D5 (processing day).

### Criterion 6 — refused

> After E9, all balances and fees return to their pre-E7 values.

**Meaning of "pre-E7":** the values just before the engine processed E7 on Day 5.
At that point, the ACC-001 ledger was 465.00 and there were no fees.

**Verdict:** Refused.

**Reason:**

1. No rule has a trigger to reverse a booked fee. E9 reverses E7 only. A refund would be a new money movement that no rule and no event asks for (DECISIONS.md, principle 3).
2. The append-only rule does not forbid a refund: a refund would be a new compensating entry. It only means that the fee records stay visible in any case.
3. The 75.00 of fees stay. After E9, the balance is 390.00, not 465.00 (both before interest).

**Secondary point:** Without E7, Auth-B would have been approved (465.00 - 90.00 = 375.00).
The engine never checks that decline again, so Auth-B stays declined after E9.

### Criterion 7 — refused

> Each E10 instalment is BHD 3.334.

**Verdict:** Refused.

**Reason:** 3 × 3.334 = 10.002 ≠ 10.000. That creates BHD 0.002 from nothing.
Three equal parts are not possible at 3 decimals.
The engine splits E10 into 3.333 / 3.333 / 3.334 (see AMBIGUITIES.md 12).

### Criterion 8 — refused

> If the accruals do not add up to the total, discard the remainder.

**Verdict:** Refused.

**Reason:** The criterion plans a forbidden behaviour (it discards money).
It plans it for a case that a fixed rule forbids: the rounded daily accruals "must sum exactly" to the capitalized total.
In this design, the capitalized total is the sum of the rounded accruals, so no remainder exists.

**Compare with criterion 5:** criterion 5 describes a correct behaviour with a false premise.
Criterion 8 describes a wrong behaviour. So I accept 5 and refuse 8.

---

## 2. Accepted with notes

### Criterion 4 — accepted, with a note

> A settlement with an unknown authorization is rejected, and no money moves.

E6 refers to Auth-Z, which does not exist. The engine appends a rejection (`:settlement/unknown-authorization`). No money moves.

**Note:** I read "ledger" as the event journal, because the text says "no event record is mutated".
The engine also rejects a settlement of a declined authorization and of an already settled authorization.
Real card networks have "force posts" (a settlement with no authorization). No rule here covers them, so the engine rejects them.

### Criterion 5 — accepted, flagged as a trap

> If Auth-B is approved, its hold reduces available but not ledger.

**The rule is true.** A hold reduces the available balance and does not change the ledger balance. The tests check this with Auth-A (available 50.00, ledger 250.00 on Day 2).

**The premise is false.** Auth-B is declined: the available balance after the hold is -155.00 - 90.00 = -245.00.

**Why I do not refuse it:** The criterion is a conditional statement.
Its behaviour is correct, and the engine implements it.
To refuse it would mean to refuse a correct rule.

### Criterion 1 — accepted

The Day 2 close at the end of Day 5, before fees, is 1200.00 - 950.00 - 620.00 = -370.00.

### Criterion 3 — accepted

Auth-A was approved. A settlement of 185.00 on a hold of 200.00 is a normal partial settlement.

---

## 3. Approaches abandoned

### Abandoned during design (before code)

These approaches existed in the design. I abandoned them before I wrote code.

a. **Refund the overdraft fees after the reversal E9.**
   Abandoned: it adds a money movement that no rule or event asks for.

b. **The failing test "fees are refunded after E9".**
   Replaced by the rounding-drift test.
   Reason: it only tested a feature that I decided not to build, and it repeated the refusal of criterion 6.

c. **The decision principle "choose the reading that adds the least behaviour".**
   Replaced by the ordered decision principles in DECISIONS.md (rules over criteria; do what the text says; where the text is silent, add no money movement; one point of evaluation for amounts; make every decision visible).

   Reason: an independent review showed that the old principle did not explain the fee look-back.

### Abandoned during the build

- `auth-state` first used `reduce` with a `nil` start value. Replaced by `keep` + `last` over a status map: simpler, no start value.
- Window constants first lived in `engine`. `eod` needed them and `engine` requires `eod` (circular dependency). Moved to `ledger.window`.
- The first fee test closed Day 6 with the full `close-day`. After interest was added it also capitalized interest, so the test was too broad. It now calls only `assess-fees`.
- The first report printed one note per E10 instalment and showed a declined authorization only on its own day. Fixed with distinct sources and all known authorizations.
- Snapshots of the state at each end of day were considered for the per-day report and not built; `:known-at` replaced them (DECISIONS.md D6).
- Code comments and docstrings were removed after review.
