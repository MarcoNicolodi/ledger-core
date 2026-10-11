# Worklog

Times are local (UTC-03:00), taken from `date` when each step ended.
The build was done with Claude Code (AI coding agent) under my direction.

## Planning (before the repository existed — no timestamps recorded)

- Read the brief. Calculated the replay by hand: ACC-001 end of Day 5 = -230.00
  (3 back-dated fees), Day 6 close = 390.93; ACC-002 Day 6 close = 10.008.
- Checked the 8 criteria. Refuse 2, 6, 7, 8. Accept 1, 3, 4, and 5 (flagged as a trap).
- First design refunded fees after the reversal E9; abandoned (see REJECTED.md).
- Ran an independent challenger review of the analysis. Arithmetic confirmed;
  reasons for criteria 4, 6, 8 rewritten; alternative readings added to AMBIGUITIES.md.
- Chose the failing test: interest rounding drift.

## Build

- 2026-10-11 10:08 — Step 1: repository, `deps.edn`, event stream `resources/events.edn`.
- 2026-10-11 10:14 — Step 2: `money` — scale check, HALF_EVEN rounding, split with remainder to the last part.
- 2026-10-11 10:20 — Step 3: `journal` — append-only journal, balance by value date, known-at view, holds and available balance.
- 2026-10-11 10:29 — Added DECISIONS.md (design decisions and trade-offs D1–D10) after review feedback: trade-offs must be recorded as they are made.
- 2026-10-11 10:33 — Step 4: `auth` — approve/decline at available ≥ 0, partial settlement releases the full hold, five settlement rejection codes, duplicate id. DECISIONS D11–D14.
- 2026-10-11 10:41 — Step 5: `engine` — boundary validation, multimethod dispatch, instalments, reversal, forward-only day clock with late arrivals; `eod/close-day` is a no-op stub. DECISIONS D15–D18. Started a subagent to draft README/NUMBERS/AMBIGUITIES/REJECTED outside the repo.
- 2026-10-11 10:43 — Step 6: end-of-day fee run with look-back; window constants moved to `ledger.window` to avoid a circular dependency; BHD fee error once per day. My first test expected one BHD error in total, but Day 2 is also negative, so one error per negative day is correct; fixed the test. DECISIONS D19–D21.
