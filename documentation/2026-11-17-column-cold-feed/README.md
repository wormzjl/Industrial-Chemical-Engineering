# Column on a cold feed (2026-11-17)

**Status:** Feasibility study done 2026-11-17, on branch `claude/column-testing-sizing-26b5b9`; nothing built. Class:
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

## Documents

- [COLD_FEED_REVIEW.md](COLD_FEED_REVIEW.md)

## Code areas (when built)

`science/plant/column` (`ColumnCycle`, `ColumnSolve`, `SplitTable`, `DriftBands`, `Condition`, `ColumnStatus`),
`runtime/fluid/ColumnOperation`, `EquipmentAlarm`, the Operation tab.

## Open items

- The owner's go-ahead for a detailed plan; then T0, T1 and the notices as Opus packages.
- Not measured: where the heat-integrated review plant settles when its pumparounds are cut; T2's flash against V3
  at the 455 to 460 K edge; another crude.
