# 2026-11-02-valve-hold

Purpose: a regulating flow valve held its island; the column review world took no feed.

Status: In progress since 2026-11-02, on branch `claude/valve-hold`, not merged. Later outlet holds fixed.

## History

- 2026-11-02: reproduced on `main`; the choked ratio refuted; two start defects fixed (commits f730d61de, 83802e11e, 22fd0d2e4). Quick suite 1807 green. Review world runs its column at design feed.
- 2026-11-02: outlet holds traced: the closure row loses a dry junction's washing-out nitrogen below 1.1e-10 (`OUTLET_HOLD_REVIEW.md`). Gate moved to the production path (59080795c).
- 2026-11-02: owner chose option A: closure on the largest component, with `sameLines` in `replayStart` (c39e2bcd3). Quick suite 1808 green. Clean merge of both branches: review world no hold, quick suite 1846 green.
- 2026-11-02: final lane on a scratch merge of both branches against main 3ffeb0519: `test slowGates` 2034 green, chain-100 exact, GameTests 36 of 36, campaign 975 / 994 / 1000 against 975 / 993 / 1000 once two budget holds are replayed alone (`FINAL_LANE_REVIEW.md`).
- 2026-11-02: merged into `claude/column-operation-5c3005` (548456d5c) for one merge. Code guide done there (`fluid-science`, `fluid-runtime`, `verification`); tools complete in `tools/valve-hold-probes/`.

## Documents

- [VALVE_HOLD_REVIEW.md](VALVE_HOLD_REVIEW.md): physics, what the code did, the change, results, the open item.
- [OUTLET_HOLD_REVIEW.md](OUTLET_HOLD_REVIEW.md): why the outlets hold, the door left in `replayStart`, the options.
- [FINAL_LANE_REVIEW.md](FINAL_LANE_REVIEW.md): gates, campaign, cost and printed lines on the merge of both branches.

## Code areas

`science/fluid/network/PassiveStepSolver` (pass loop, `balanceStart`, `sameLines`, `backwardEulerCold`, `carriedPoint`), `PassiveIntervalSolver.replayStart`, `PassiveNetwork.Pipe.Identity.line`, `solver/PhaseLayout` (closure row). Test: `runtime/fluid/RegulatingValveHoldTest`. Tools: `tools/valve-hold-probes`.

## Open items

- A cold start seeds a junction without a component its stock holds: a zero column, same review.
- The merge: `documentation/2026-10-28-column-operation/MERGE_REVIEW.md`; the version and the word are the owner's.
- The heaviest cold seeds cost more and cold 431 and 475 hold more often (`FINAL_LANE_REVIEW.md`). A pump's head limit to target transition carries its point the same way; not shown to hold.
