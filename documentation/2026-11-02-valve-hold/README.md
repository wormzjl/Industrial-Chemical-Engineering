# 2026-11-02-valve-hold

Purpose: a regulating flow valve held its island; the column review world took no feed.

Status: In progress since 2026-11-02, on branch `claude/valve-hold`, not merged. Later outlet holds open.

## History

- 2026-11-02: reproduced on `main`; the choked ratio refuted; two start defects fixed (commits f730d61de, 83802e11e, 22fd0d2e4). Quick suite 1807 green. Review world runs its column at design feed.

## Documents

- [VALVE_HOLD_REVIEW.md](VALVE_HOLD_REVIEW.md): physics, what the code did, the change, results, the open item.

## Code areas

`science/fluid/network/PassiveStepSolver` (pass loop, `balanceStart`, `sameLines`, `backwardEulerCold`, `carriedPoint`), `PassiveNetwork.Pipe.Identity.line`. Test: `runtime/fluid/RegulatingValveHoldTest`. Tools: `tools/valve-hold-probes`.

## Open items

- Outlets hold after some slices: the review's last section.
- Before a merge: code guide, `slowGates`, version. A pump's head limit to target transition carries its point the same way; not shown to hold.
