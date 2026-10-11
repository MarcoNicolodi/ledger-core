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
- 2026-10-11 10:47 — Step 7: interest accruals and Day 6 capitalization (ACC-001 0.93 → 390.93; ACC-002 0.008 → 10.008). The step 6 test 'a later reversal does not refund the fees' broke, because it called the full `close-day 6`, which now also capitalizes interest (390.93, not 390.00). The test was too broad; it now calls only `assess-fees`. DECISIONS D22.
- 2026-10-11 10:51 — Step 8: report (data maps + text lines) and scenario test. Output matches the hand calculation for every day. First report version listed E10 three times (one note per instalment) and showed a declined authorization only on its own day; fixed with distinct sources and all known authorizations. DECISIONS D23–D24.
- 2026-10-11 10:55 — Added the decision principles to the top of DECISIONS.md after review feedback (the reason for 'no fee refund' must be stated, not only the choice). Step 9: one test per acceptance criterion; accepted criteria assert the criterion, refused ones assert the correct behaviour and name REJECTED.md.
- 2026-10-11 10:57 — Added Docker support on request, so reviewers need no Java or Clojure. Docker is not installed on this machine, so I could not build the image here. I checked that the base image tag exists on Docker Hub and that `clojure -P -X:test` (the build step) works locally.
- 2026-10-11 11:01 — Step 10–11: docs (README with Docker, AMBIGUITIES, NUMBERS, REJECTED with build-time abandoned approaches), known failing test (0.92 expected, 0.93 actual), docs/architecture.html (headless Chrome failed to render ARCHITECTURE.pdf on this machine; print the HTML to PDF from a browser). Delivered under time pressure; docs were drafted by a subagent from my analysis and reviewed only in part.
