# Supercritical follow-ups

Status: **Planned** 2026-11-16. Branch `claude/supercritical-fluid-support-d0ffa4`, base `dfbdc583c` (main 0.34.0).

Purpose: the open owner items of [2026-11-15-supercritical-fluids](../2026-11-15-supercritical-fluids/README.md), on the
owner's "fix them": the plunger pump's clearance loss and work, one dense fluid in the level, sight glass, release,
line service and device pages, the column's feed flash by a stability test, the plunger pump's own sound.

## History

- 2026-11-16: the plan, by the main thread: 17 items sorted (13 fixed in four packages, two kept as they are with the
  reason, one offered, one outside the list). No code changed. [FOLLOWUPS_PLAN.md](FOLLOWUPS_PLAN.md).
- 2026-11-16: P1, sub-thread (branch `claude/gallant-leavitt-f29f1e`): the plunger pump's clearance loss and isentropic work
  on the suction's isentropic exponent (`NetworkStreams.isentropicExponent`, `suctionKappa`), the run and sizing of a
  plunger-typed Pump tile (`Passages.pumpType`). Six reference rows within 0.35 % of η_v. Stop decisions: exponent by
  differences (1 A), a pump with no type runs centrifugal (2, narrowed), the view and transport read `suctionKappa` (3).
  [P1_PLUNGER_REVIEW.md](P1_PLUNGER_REVIEW.md).

## Documents

- [FOLLOWUPS_PLAN.md](FOLLOWUPS_PLAN.md): the items, the common rules, packages P1 to P4, the order.
- [P1_PLUNGER_REVIEW.md](P1_PLUNGER_REVIEW.md): P1, the plunger pump's clearance and work: reference rows, moved values, runs.

## Code areas (planned)

- P1: `science/machine/PlungerPump` (and `PositiveDisplacement.share`), `NetworkStreams.isentropicExponent`, the solve's `suctionKappa` (read by `ConservativeTransport` and `ViewBuilder` too), the simulator's `MoverUnit`, `PumpSizing`, `Passages` and `SizedPassages`.
- P2: `runtime/fluid` views and releases, the level readers, `network`, `client`, `Service`.
- P3: `science/column/v3/V3FeedFlash`.
- P4: the mover sounds.

## Open items

- The owner's approval of the four threads; the reservation of protocol `icheme-4` on `main` before P2's code.
- Kept, open to the owner's veto: Auto's rule (item 2); "Dense" in the aqueous slot (item 13).
- An in-game check of the plunger pump (constructor, model, sound), offered at the end.
