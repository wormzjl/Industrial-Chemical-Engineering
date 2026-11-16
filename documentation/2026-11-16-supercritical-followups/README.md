# Supercritical follow-ups

Status: **Planned** 2026-11-16. Branch `claude/supercritical-fluid-support-d0ffa4`, base `dfbdc583c` (main 0.34.0).

Purpose: the open owner items of [2026-11-15-supercritical-fluids](../2026-11-15-supercritical-fluids/README.md), on the
owner's "fix them": the plunger pump's clearance loss and work, one dense fluid in the level, sight glass, release,
line service and device pages, the column's feed flash by a stability test, the plunger pump's own sound.

## History

- 2026-11-16: the plan, by the main thread: 17 items sorted (13 fixed in four packages, two kept as they are with the
  reason, one offered, one outside the list). No code changed. [FOLLOWUPS_PLAN.md](FOLLOWUPS_PLAN.md).

## Documents

- [FOLLOWUPS_PLAN.md](FOLLOWUPS_PLAN.md): the items, the common rules, packages P1 to P4, the order.

## Code areas (planned)

- P1: `science/machine/PlungerPump`, the solve's `suctionKappa`, the simulator's `MoverUnit` and `PumpSizing`.
- P2: `runtime/fluid` views and releases, the level readers, `network`, `client`, `Service`.
- P3: `science/column/v3/V3FeedFlash`.
- P4: the mover sounds.

## Open items

- The owner's approval of the four threads; the reservation of protocol `icheme-4` on `main` before P2's code.
- Kept, open to the owner's veto: Auto's rule (item 2); "Dense" in the aqueous slot (item 13).
- An in-game check of the plunger pump (constructor, model, sound), offered at the end.
