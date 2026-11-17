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

- 2026-11-17, same branch (sub-thread "review plant fix"): the review world at full scale (`SIZED_SCALE = 1`; the other
  rigs keep 1/16) and, on the owner's words "make it a full case including preheating crude with PA and furnace", a crude
  unit around the column. The crude runs from storage through the charge pump P-101, the three pumparound coolers (their
  cold side is the crude, no tower water), the residue exchanger E-101 (bottoms on the shell) and two cabin heaters H-101 in
  parallel into the tower's feed. Owner choices: preheat by the pumparounds and a bottoms exchanger; the heater sized for the
  cold start ("Bigger furnace": HeaterSizing Auto on 151.7 MW gives 2 cabins, 10 m x 47 m). The router gains a bend cost
  (`Plant.bendCost`, 8 here; 0 keeps the shortest path for every other rig), on the owner's "keep the pipe straight when
  possible". The rig fixes:
  - the receiver stands high enough for its boot drain;
  - the blueprint takes the placed exchangers' types;
  - the condenser's tower water is sized from its rating (0.6 m);
  - an exchanger stands as high as its lowest boundary line needs;
  - the column starts only once every heater's fuel line holds gas.

  On the owner's "the command blocks don't work": they were the GameTest framework's start controls (`test runclosest` and a
  button beside each test's structure block). The dev client has no `/test` command, and where one ran it would rebuild the
  test over the plant. `ReviewBatch.skipped`, the first call of every review test, now removes them
  (`AssemblyGameTests.clearStartControls`); a region scan of the rebuilt world finds no command block or button.

  Build 3 (`runReviewWorldServer -PreviewBatch=review-operation`): complete, 21 slots, 1336 devices, STARTED at
  2557.2 kmol/h, heaters at 638 K by t=1524. The column then trips UPSET on CLOSURE at its first solve and stays tripped.
  Copied to `run/saves/column-operation-full-2026-11-17` (worktree) after `nbt.js review`. Shots
  `tmp/review-plant-fix/shots/21` to `29`. Tests: `compileFluidGameTestJava`, `testClasses`, and the 5 classes
  `affected.mjs select` named (all new on this branch with no load set; 4 ran and passed, `V3RefluxFreeWaterTest` up to date).

## Full crude unit, sized (2026-11-17)

| Item | Sized |
|---|---|
| Column | 7.5 m, 40 trays, flood 73.4 % |
| Condenser | 2.0 m x 6, CROSSFLOW, 408 % over need; tower water 654.8 kg/s at 0.6 m |
| Receiver | 4 m x 10 with boot, support 6 |
| PA 1 / 2 / 3 coolers (crude side) | 1.25 m x 5 / 1.25 m x 5 / 1.0 m x 4, green |
| PA 1 / 2 / 3 pumps, reflux pump | 0.25 / 0.30 / 0.25 m, 0.20 m (PUMP_RATED 65 %) |
| P-101 charge pump | 0.30 m, 729.5 m³/h, 70.7 m head, 16 bar out |
| E-101 residue exchanger | 1.5 m x 9, 1519 m², residue out at 200 °C |
| H-101 | running duty 66.3 MW (1 cabin 12 m x 40 would hold it); cold start 151.7 MW, so 2 cabins 10 m x 47 |
| Bores | crude 0.4; crude 1 to 2 0.3; crude 3 to 5 0.35; charge 0.6; bottoms 0.45; residue 0.25; fuel 0.25 per heater (methane, 3 bar) |

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
`Options`, `Rig.layout`, `Rig.finish`; 2026-11-17: `sizing`, `Preheat`, `Rig.layPreheat`, `Rig.fire`, `Rig.fueled`),
`ColumnFailureModesReviewWorld.java` (calls `extent`), `AssemblyGameTests.java` (`Plant.bendCost`, `Plant.straightRoute`,
`clearStartControls`), `ReviewBatch.java` (`skipped` clears the start controls).

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
- Full crude unit, start-up trip (with the owner, 2026-11-17). Nothing flows before the column starts, so its first feed
  is the crude standing in the charge line, at about 300 K. `ColumnCycle` then finds a heating condenser
  (`condenser > 0`), trips CLOSURE, and the trip latches: alpha stays 0 and no feed is taken. The feed account fills to
  180 % of its aim and the inlet valve closes. The cold first feed is not a start-order fault. The fuel lines read FULL
  before the start. A heater fires only on a demand read from flow through its radiant coil (`FiredHeaters.booked`: no
  flow, no demand, no fuel), so it cannot be lit ahead of the feed, and its duty then follows a slice behind on each of the
  coil's and the fuel's islands. More decisive still: the charge line (0.6 m bore, about 0.28 m³ per metre, some 60 m to the
  feed nozzle) and the coils hold cold crude from the plant's fill. That is roughly 17 m³, about 85 s of design flow
  (0.2 m³/s), and it reaches the tower before any heated crude. The trip took the first 9 s of it. Only a path that sends
  that inventory elsewhere (a start-up circulation) or a column that accepts a cold feed removes it. Build 4 tried the
  owner's "light the furnace before running any feed": the burners are lit and fueled, then the crude supply opens into the
  empty train. The train filled at once and stood still against the tower's shut feed valve. The heaters read 297.7 K at
  0 kW for 12000 ticks, because with no flow through a coil its burners ask for no fuel. The column then started on the
  298 K charge and tripped the same way. The rig keeps this order (`Rig.openCrude`, `Rig.warmed`).

  Owner, 2026-11-17: "if it is set to a temperature it should output at given temperature unless duty is not satisfied";
  chosen law, "a lit coil heats its standing contents". Part 1 is built (`ec3d5eee5`, `FiredHeaters.booked`): a lit coil
  whose still contents (under 1e-6 kg/s) stand below the set point keeps its design duty's fuel burning
  (`FiredHeaterFuelTest.stillTubesBelowTheSetPointKeepTheBurnersLit`, 6.3 MW fired, 0 kW into the still coil; 23 affected
  classes pass). In builds 5b and 5c:
  - each heater fires 115 MW on its still coils, but the coils stay at 297.7 K, because a still coil takes no heat (the
    coil law is beta times mass flow times the enthalpy rise);
  - the crude fills the whole train in under 20 ticks when its supply opens: the burners then still read NO POWER
    (their store not yet filled) and get no demand while the coils are empty (an empty island is not solved), so the line
    fills cold.

  Part 2, heat into still contents, is a solver change: a heat term on a reservoir at no flow, in the residual, the Jacobian
  and the reconstruction. Until then, a lit heater with still cold tubes burns its duty's fuel and heats nothing. Options: a start-up circulation in the rig, a trip that clears with its
  condition (engine), or the world as it stands. The owner's direction for the engine: a cold feed goes to the bottom
  instead of holding, and a pumparound works only on what its stages hold. The column screen reads "Upset: inputs closed;
  last solve failed", which does not name the cold feed.
- Layout defects seen in the full-scale shots, not fixed:
  - the condenser stands at grade below a receiver on 6-block saddles, so the condensate runs uphill;
  - long unsupported aerial runs;
  - E-101 stands on tall legs so its shell outlet's boundary line clears the ground;
  - the GameTest pass beacons (4 green beams) and structure blocks stay in the world.
- No merge preparation (owner).
