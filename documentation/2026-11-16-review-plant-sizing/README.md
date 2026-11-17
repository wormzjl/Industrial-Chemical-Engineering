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
  coil's and the fuel's islands. (A first reading here, 17 m³ of cold crude standing in the charge line, was wrong: pipes
  and heat-side passages are junctions, whose buffer is a 0.05 s relaxation state and not stock (owner decision D46). No
  crude stands in a line; the cold crude is what passed the heaters before they fired, and what the column's own port
  reservoir holds.) Build 4 tried the
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

  Part 2, heat into still contents (owner: "Build it here"), was built and reverted the same day. It was a still heat planned
  per step, added to the coil's first node in the energy rows and booked as the coil's heat in the reconstruction, with a
  not-at-rest rule. It heated the coil's node, a junction: its 988 kg buffer is the relaxation state, not crude standing in
  the tubes. The test water went from 323 K to 453 K in one slice on 10.5 MJ, so the term had no stock to act on. Part 1
  stays, with its still test reading the coil's own node (`FiredHeaters.booked`).

  With part 1, build 5b started the column on lit, fueled burners: the heaters' radiant outlets read 508 and 608 K, not
  298 K. They fell short of 638 K ("DUTY INSUFFICIENT: outlet 29.7 K below the set point"). An empty feed account opens the
  column's inlet valve to twice its rate (`ColumnOperation.inletPlan`, gain at most 2), and the heaters are sized for the
  cold start at design flow. The account took that crude to 0.60 of its aim and the column tripped CLOSURE as before.
  Owner, 2026-11-17: "the pump should be paired with a control valve to regulate the flow". Build 6:
  - **Rig:** a mass-flow control valve on P-101's discharge, set to 105 % of the design feed (167.4 kg/s; the built heaters
    hold 183.8 MW against 144.8 MW for the cold start at that flow, a 26.9 % spare). The crude opens only once the burners
    are powered as well as fueled.
  - **The valve did not regulate.** At t=1426 the charge line carried 318.9 kg/s (twice the design feed: the column's inlet
    plan at gain 2) with the charge valve VALVE_OPEN and the column's feed valve VALVE_REGULATING. Correction (build 7): the
    rig set the valve's law with no sensor, and a control valve without one is a bare line (`ControlValve.bare`) that
    regulates nothing and stays out of `ControlConstraints.standing`. The ranking was not the cause.
  - **Heaters at twice the flow:** 62.5 MW each, outlets 527 and 531 K. Once the flow fell to 131 kg/s (t=2624) both held
    638.6 K.
  - **Column** (on the cold-feed thread's committed law):
    - t=1426: RUNNING with PASSING (2557 kmol/h at 427 K, the feed to the bottoms).
    - From t=2025: PRODUCT_FULL and refused enthalpy solves. First "pumparound cooling of 47.34 MW exceeds the 9.880 MW made
      available by the feed", then NONCONVERGENCE on the tray 11 side draw.
    - From t=4421: LIMITED, outlet 0 %, with the bottoms account full and taking nothing.
    - The feed account went 0.27, 1.32, 2.32 of its aim, then stood at 2.13 with its valve closed.
    - Bottoms stop: the residue island (column bottoms port, E-101 shell, residue void) held from committed tick 3630 to the
      end ("Newton line search stalled at residual 23.51", later a singular Jacobian). A held island passes nothing, so
      the bottoms account filled to 1.00 and the column read LIMITED, outlet 0 %. Before the hold the drain was already below
      the pass: the account went from 0.26 to 0.97 of its aim in 30 s at 2557 kmol/h, against 816 to 842 kmol/h of design
      bottoms (build 1).
  Build 7 (2026-11-17): the rig fits a flowmeter on the charge line and wires the charge valve to it; the report runs
  600 s after the start (20 reports).
  - **Start hold:** the crude island (generator, P-101, charge valve, coolers' tubes, both heaters, the feed port) held at
    the column's start, from committed tick 9296 to about t=13500 ("Singular Newton Jacobian"). The column read FEED_SHORT,
    0 %. Reproduced in small (`tmp/review-plant-fix/src/FlowValvesInSeriesTest.java`): generator, a pump on its curve, a
    flow valve at 5 kg/s, a parallel pair and a column-sensor valve into a void. When the inlet's plan goes from 3 to
    10 kg/s, the line holds ("Newton line search stalled at residual 14.93", active-set pass 1). It holds with or without
    a cross link in the pair. It solves with no pair, with no pump, and from a fresh solver on the same graph (5 kg/s,
    the charge valve regulating), so the stall comes from the start carried from the committed interval. Not fixed: solver
    work.
  - **"Lower target wins" holds:** at t=13719 the charge valve regulated at 167.39 kg/s with the feed valve STANDING_OPEN.
    The heaters fired 63.0 and 63.2 MW, outlets 609 and 618 K, still on a cold heater inlet.
  - The hold's interval came in at once. The feed account went to 4.19 of its aim at 377 K, and the column read PASSING.
    The feed valve then closed (t=14318 to 17912) and regulated below 167.4 kg/s, with the charge valve standing open:
    19 to 72 kg/s from t=18511. Only one heater took duty (22 to 39 MW); the other read 0 to 0.4 MW at 638 K.
  - Column to t=20907: RUNNING with PASSING, PRODUCT_FULL and refused enthalpy solves; alpha 0.31 to 0.39. The bottoms
    account held 0.52 to 0.87 of its aim, and its island did not hold.

  Options: a start-up circulation in the rig, a trip that clears with its
  condition (engine), or the world as it stands. The owner's direction for the engine: a cold feed goes to the bottom
  instead of holding, and a pumparound works only on what its stages hold. The column screen reads "Upset: inputs closed;
  last solve failed", which does not name the cold feed.
- Layout defects seen in the full-scale shots, not fixed:
  - the condenser stands at grade below a receiver on 6-block saddles, so the condensate runs uphill;
  - long unsupported aerial runs;
  - E-101 stands on tall legs so its shell outlet's boundary line clears the ground;
  - the GameTest pass beacons (4 green beams) and structure blocks stay in the world.
- No merge preparation (owner).
