# 2026-11-02-valve-hold

Purpose: a regulating flow valve held its island; the column review world took no feed.

Status: In progress since 2026-11-02, on branch `claude/valve-hold`, not merged. Later outlet holds diagnosed; the fix waits for the owner.

## History

- 2026-11-02: reproduced on `main`; the choked ratio refuted; two start defects fixed (commits f730d61de, 83802e11e, 22fd0d2e4). Quick suite 1807 green. Review world runs its column at design feed.
- 2026-11-02: outlet holds traced: the closure row loses a dry junction's washing-out nitrogen below 1.1e-10 (`OUTLET_HOLD_REVIEW.md`). Gate moved to the production path; two of its tests stay red until the owner chooses. Solver behaviour unchanged.

## Documents

- [VALVE_HOLD_REVIEW.md](VALVE_HOLD_REVIEW.md): physics, what the code did, the change, results, the open item.
- [OUTLET_HOLD_REVIEW.md](OUTLET_HOLD_REVIEW.md): why the outlets hold, the door left in `replayStart`, the options.

## Code areas

`science/fluid/network/PassiveStepSolver` (pass loop, `balanceStart`, `sameLines`, `backwardEulerCold`, `carriedPoint`), `PassiveNetwork.Pipe.Identity.line`. Test: `runtime/fluid/RegulatingValveHoldTest`. Tools: `tools/valve-hold-probes`.

## Open items

- Owner: option A, B or C for the outlet holds, with the door fix (`OUTLET_HOLD_REVIEW.md` section 4).
- A cold start seeds a junction without a component its stock holds: a zero column, same review.
- Before a merge: code guide, `slowGates`, version. A pump's head limit to target transition carries its point the same way; not shown to hold.
