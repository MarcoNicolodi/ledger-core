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
