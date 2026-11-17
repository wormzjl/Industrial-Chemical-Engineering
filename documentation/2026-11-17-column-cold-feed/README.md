# Column on a cold feed (2026-11-17)

**Status:** Planned 2026-11-17 (feasibility study and plan), on branch `claude/column-testing-sizing-26b5b9`; nothing built. Class:
PHYSICS. Lanes: column operation (plant layer and engine).

A running column that gets a feed too cold for its solved table trips and holds the feed, and where the hot feed
stands behind the cold one it never recovers. The owner asked that a cold feed runs to the bottoms and that draws and
pumparounds work only as far as their trays hold liquid.

## History

- 2026-11-17: the full-scale review plant's column stayed in UPSET at start (batch `2026-11-16-review-plant-sizing`).
  The owner: "if i'm running a cold feed into it, it should route them to the bottom (and maybe get heated up by steam
  a bit) instead of holding. PA should only work if there is something in these stages", then "Perform feasibility
  study first". The study: [COLD_FEED_REVIEW.md](COLD_FEED_REVIEW.md), on `tools/column-cold-feed/ColdFeedProbe.java`
  (the tray solver run directly at feeds from 638 K to 300 K, three sweeps). Result: feasible as a pass to the bottoms
  with no solve (T0) and a turndown solve at a share of the draws and pumparounds (T1), which V3 solves from 460 K up;
  a flash table below that (T2) can wait. No mod code, no test run.
- 2026-11-17: the owner: "the PA duty comes from it flowrate and temperature, if we keep the design pumping rate, and
  simulate temperature impact then we should be able to model the actual exchanged heat during startup". The probe run
  with the pumparound duties alone cut (`-DdrawShare=1`): V3 solves every duty from 1 % to 100 % at feeds of 575 to
  638 K with the draws kept. The review's section 9: the duty follows the cooler, a band and a solve, no ladder.
- 2026-11-17: the owner: "Yes, plan it". [COLD_FEED_PLAN.md](COLD_FEED_PLAN.md): the pass (a tripping cycle whose
  condenser would heat, or whose state stands refused, books its feed to the bottoms), the pumparound's duty capped by
  its cooler with a 5 % band and a solve at a relaxed duty, the cooler's wall law against the pumparound's own stream,
  the texts; a draw share later. The probe's duty loop (`-Diterate`) on the real solver at 638 K, from 1 % of the
  design duties: undamped it overshoots (20.3, 10.5, 14.5 MW on the first) and settles in about 7 solves; with a
  relaxation of 0.5 it settles in 4 without overshoot; both end at 13.1, 21.0 and 13.2 MW. No mod code, no test run.

## Documents

- [COLD_FEED_REVIEW.md](COLD_FEED_REVIEW.md)
- [COLD_FEED_PLAN.md](COLD_FEED_PLAN.md)

## Code areas (when built)

`science/plant/column` (`ColumnCycle`, `ColumnSolve`, `SplitTable`, `DriftBands`, `Condition`, `ColumnStatus`),
`runtime/fluid/ColumnOperation`, `EquipmentAlarm`, the Operation tab.

## Open items

- The owner's go-ahead for the build: packages 1 to 4 of the plan as Opus threads; decisions D1 to D6 of the plan are
  the main thread's and open to the owner.
- Not measured: where the heat-integrated review plant settles when its pumparounds are cut; T2's flash against V3
  at the 455 to 460 K edge; another crude.
