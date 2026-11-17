# Review plant sizing (2026-11-16)

**Status:** In progress since 2026-11-16, on branch `claude/rebuild-default-column-test-76bbc6`.

The owner asked to rebuild the default column test world (`ColumnOperationReviewWorld`, review batch `review-operation`)
on the latest code, with the plant's units sized by the process simulator's current rules ("Resize and rebuild"). The
sized world goes to a new save; the first rebuild's save `column-operation-2026-11-16` stays. The other rigs that share
the `Rig` class keep their sizes.

## History

- 2026-11-16, `7dfc05a3c`: `ColumnOperationReviewWorld.sized` builds a one-column sheet of the network's default template at
  1/16 (the feed at its own temperature and 5 bar, the steam rows as wired steam at 5 bar, a stream on every product port,
  the condenser's and pumparound coolers' water ports free, so each is rated on tower water), runs it, runs
  `Proposal.sizeAll` and logs one `review_operation sizing` line per unit and line. `review` places the sized units by
  their extents (`extent` moved here from `ColumnFailureModesReviewWorld`) and hands the rig the sized diameter, condenser
  shells (further shells joined in parallel), receiver, pumps, coolers and boundary bores. `Options` gains five fields
  behind a constructor with the old signature, so `ColumnOperationGameTests`, `TutorialUnitGameTests` and the
  failure-modes plants build what they built before. `runReviewWorldServer -PreviewBatch=review-operation`: the sized plant
  built, validated complete (21 slots, 645 devices), STARTED at 159.8 kmol/h and RUNNING in reports 2 to 4 with only the
  four model ADVISORY notices, every duty booked as solved (condenser -1942.4 kW, pumparounds -818.3, -1313.2, -827.4);
  report 1 (t=915, before the feed reached the column) read LIMITED with FEED_SHORT at 0 %. All 4 required tests passed.
  The world is copied to `run/saves/column-operation-sized-2026-11-16` in the main checkout.

- 2026-11-17, branch `claude/column-testing-sizing-26b5b9` (the batch's branch merged in): the owner found the sized world's
  lines thin, the column's top and side lines looking unconnected, and the pumps and exchangers small. The rig applied only the
  boundary bores; every internal line stayed at the laid 0.05 m, and a nozzle draws at its pipe's size. `SizedPlant.lines` and
  `Options.lineBores` now carry the sized bores of the column's own lines, `Rig.routes` keeps each laid path, and `finish`
  resizes them: overhead 0.20 m (the condenser's outlet to the receiver too: the sheet has no line for it), receiver out
  0.10, reflux 0.075 (the larger of pump out and reflux, one laid line), pumparound draws 0.15, returns 0.075 / 0.10 / 0.075.
  Every resize of the rig now takes the nozzles' largest bore as the pipeline tool does (`Rig.largest`; a path is laid at the
  least of its sized bore and its nozzles' largest, with a log line) and throws where a line refuses its bore. Compiled
  (`compileFluidGameTestJava`); the world is not rebuilt and nothing was run.

## Sized units against the old fixed ones

| Item | Fixed (before) | Sized | Rows off green |
|---|---|---|---|
| Column | 2.0 m (preset x sqrt(1/16)) | 2.0 m, 40 trays, flood 71.2 % | none |
| Condenser | 1 shell, 1.5 m | 1 shell, 0.5 m x 2 (37.5 m²), 1.938 MW | none |
| PA 1 cooler | least exchanger (0.5 m) | 0.5 m x 2 | EXCHANGER_OVER_NEED 301 % |
| PA 2 cooler | least exchanger | 0.5 m x 2 | EXCHANGER_OVER_NEED 381 % |
| PA 3 cooler | least exchanger | 0.5 m x 2 | EXCHANGER_OVER_NEED 737 % |
| Reflux pump | least pump (0.05 m) | 0.05 m | none |
| PA 1 / 2 / 3 pump | least pump | 0.05 / 0.10 / 0.05 m | PUMP_RATED 170 % / 60 % / 143 % |
| Receiver | 1.5 m x 4, support 5 | 2.0 m x 5 lying with boot, support 5 | none |
| Feed / steam bore | 0.25 / 0.15 m | 0.20 / 0.10 m | none |
| Products | as laid | offgas 0.075, distillate 0.05, water 0.025, draws 0.075 / 0.075 / 0.05, bottoms 0.15 m | none |
| Cooling water | as laid | as laid (the sizer gives no line for tower water on a free port) | n/a |

The sized condenser is smaller than the fixed 1.5 m shell, not larger: the dirty rating with the 20 % spare at 1.10
design flow needs under 31 m² for the overhead, much of which is steam condensing (73.5 kmol/h of sour water). The
pumparound coolers are the catalogue's least shell and still larger than needed. No pump size puts the pumparound
circulations inside the 70 to 120 % rated window, so Auto keeps the nearest.

## Code areas

`src/fluidGameTest/java/com/wormzjl/icheme/fluid/gametest/ColumnOperationReviewWorld.java` (`sized`, `review`, `extent`,
`Options`, `Rig.layout`, `Rig.finish`), `ColumnFailureModesReviewWorld.java` (calls `extent`).

## Open items

- `ColumnOperationGameTests` and the failure-modes plants were compiled but not run on this change.
  `runFluidGameTestServer` selects by namespace only, not by class. The parent session read the diff and chose not to
  run `icheme_fluid_test`: the other rigs call the old `Options` constructor, whose defaults reproduce their old build.
  The next final lane runs the fluid GameTests once. The 15-minute failure-modes review world was not run.
- Pumparound pumps read PUMP_RATED outside the window, and the coolers are 3 to 8 times the need: these are the sizer's catalogue gaps, not
  the rig's.
- The cooling water lines stay as laid; a tower-water line size would need the sizer to size the free port's water.
- The world with the sized internal lines is not rebuilt: `runReviewWorldServer -PreviewBatch=review-operation`, then a look
  at the top and side nozzles in game (the "not connected" reading is inferred from the code, not seen).
- Owner decision open: where no pump size lands in the 70 to 120 % window, Auto keeps the nearest, so pumparounds 1 and 3
  run 0.05 m pumps at 170 % and 143 % of rated flow. The next size up would read about 42 % and 36 %.
- No merge preparation (owner).
