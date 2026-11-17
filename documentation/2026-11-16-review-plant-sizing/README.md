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
- 2026-11-17, same branch (main thread, owner: "Yes, derive it"): the hold of builds 6 and 7 reviewed on a small line,
  [VALVE_HOLD_REVIEW.md](VALVE_HOLD_REVIEW.md), with `tools/valve-hold/`. Two mechanisms. M1, derived: a carried
  regulating flow valve whose new target is above what its line passes is solved as regulating, which has no solution,
  so the mode never becomes open and the island holds (no pump or second valve needed). M2, located only: with a pump
  on its curve and a parallel pair, the pass that regulates again from the open line stalls at a residual of 14.93.
  The carried start fails on M1 and the fresh start on M2; one of the two solved every case tried. No mod code, no fix.
- 2026-11-17, same branch (sub-thread of the main thread): M2 traced, [VALVE_HOLD_TRACE.md](VALVE_HOLD_TRACE.md), with
  `tools/valve-hold/trace.patch` (temporary instrumentation, not applied). The regulating pass starts from a balance that
  did not converge. From the open line's pressures, the balance's Newton crosses below the void's 200 kPa, where the
  one-way inlet passes nothing. Its one-junction sweep cannot lift the pair's junctions together, so they end at 1 kPa.
  The pass loop uses those law flows anyway. The inlet then starts 8.5 times beyond its reverse cap, the Jacobian is
  singular (cond 2.9e9), and every trial leaves the property box. The 14.932 is the pump's rated 19.932 kg/s start
  against the 5 kg/s line. M1 is a different stall: it has no root, and the pump's discharge walks to 1 Pa. Options A to
  C4 go to the owner. No mod code.
- 2026-11-17, same branch (sub-thread, owner: "Yes, build them"): options C3 and B built (`e75044d7c`, `2919f7b83`),
  C1 tried and withdrawn; [VALVE_HOLD_TRACE.md](VALVE_HOLD_TRACE.md), section 9. C3: a flow valve moved into
  regulating starts its pass on the balance only where the balance converged, else on the open pass's flows (A2 solves:
  pass 1 in 11 iterations). B: a pass that fails with nothing else to change solves its regulating flow valves open,
  once per solve (M1). C1 (a balance step bounded at a one-way stop) moved every balance that crosses and recovers and
  held `JunctionSeedingGateTest`'s back-pressure valve case; its junction-keyed variant left balances unconverged.
  `FlowValveCapacityTest` (15) added; the selected classes pass but the expected `SizingPageTest` Chinese case.
- 2026-11-17, same branch (sub-thread, owner: "Yes, run it"): build 8 of the review world on C3 and B. The charge valve
  regulates at 167.39 kg/s from the first report, both heaters hold 634 to 635 K, and the column runs on a solved table
  from t=26619 (three reports in a row). No island of the new plant held but for "round deadline" lines; the world
  still runs the plants of builds 6 and 7. The rig's reports run to 900 s or end after three solved reports.
- 2026-11-17, same branch (sub-thread, owner: "Yes, do that"): build 9 on a fresh world, the old one set aside as
  `run/review-world/world-before-build-9`. 0 islands at load. The column runs on a solved table from t=5620 (reports 8
  to 10); at t=6818 charge 167.39 kg/s regulating, feed 634.0 K, heaters 634.4 and 634.6 K (26.0 and 25.2 MW), bottoms
  831.9 kmol/h. Build 8's one "round deadline" recurs on the same island (column unit + 11: 1399 here, 4215 in build 8),
  so it is the plant's own, not the old plants'. The world (9.5 MB) is saved for the owner's client.

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
  - Correction (valve-hold trace): the failing start was the fresh one, not the carried one (VALVE_HOLD_TRACE.md).
  Build 8 (2026-11-17, on C3 `e75044d7c` and B `2919f7b83`; log `tmp/review-plant-fix/build-8.log`): reports every 30 s
  up to 30 (900 s), ending after three in a row read RUNNING on a solved table (RUNNING, not refused, no PASSING or
  OFF_TABLE). It ended at report 10, t=27817. The column started, as in every build, after `HOT_TICKS` (200) with the
  charge line still reading "NO FLOW: dead-end pipe".
  - **No start hold.** The charge valve read VALVE_REGULATING at 167.39 kg/s at every report but t=23624 (0 kg/s,
    STANDING_OPEN, with the feed valve CLOSED while the column was UPSET); the feed valve otherwise STANDING_OPEN.
  - **Heaters:** t=22426 DUTY INSUFFICIENT (20.8 and 54.8 K short) on the cold first charge; t=23025 both 637.4 K at 53.3
    and 53.6 MW; one heater 0 MW at t=23624 and 24223 (no flow, then NO FUEL while its fuel line woke); from t=25421 both
    take duty, 634.4 to 635.1 K, 25.1 to 32.1 MW each.
  - **Column:** t=22426 RUNNING, PASSING, OFF_TABLE (feed 2557 kmol/h at 383.9 K); t=23025 and 23624 UPSET, CLOSURE ("the
    condenser duty departs 79 %", then 144 %), the first refused; t=24223 RUNNING, OFF_TABLE (first without PASSING);
    t=24822 LIMITED, COOLING_SHORT, condenser 82 %; t=25421 and 26020 RUNNING, OFF_TABLE; t=26619 to 27817 RUNNING on the
    solved table. No PUMPAROUND_SHORT; pumparounds booked 0 at t=22426 to 23624 (passing, then upset), 82 % of solved at
    t=24822, and as solved (13.09, 21.01, 13.24 MW) from t=24223 otherwise.
  - **Steady (t=27817):** feed 2557.2 kmol/h at 634.0 K, steam 1190.6; offgas 184.5, sour water 1176.5, distillate 375.8,
    draws 518.3, 476.5, 184.0, bottoms 832.1 kmol/h at 572.9 K (design 816 to 842). In 3747.8, out 3747.7 kmol/h. Feed
    account 0.59 of its aim, bottoms 0.68; E-101's shell (bottoms) FULL, -28.9 MW, out at 438.6 K.
  - **Holds:** island 4215 of the new plant "HELD: round deadline" once (committed tick 22838); no view of the rig read
    HELD at any report. At the end, islands 4214 and 4225 to 4229 read "round deadline" as the plant was released. The
    world is the one builds 6 and 7 ran in: their plants still run (26 islands loaded, the solver on one worker), and
    build 6's bottoms island 1408 still holds ("Substep refinement exhausted: Singular Newton Jacobian"), with build 6's
    column (unit 1388) logging OVERDRAWN on its bottoms. The holds of island 1408 in build 7's log were this leftover.

  Options: a start-up circulation in the rig, a trip that clears with its
  condition (engine), or the world as it stands. The owner's direction for the engine: a cold feed goes to the bottom
  instead of holding, and a pumparound works only on what its stages hold. The column screen reads "Upset: inputs closed;
  last solve failed", which does not name the cold feed.
- Layout defects seen in the full-scale shots, not fixed:
  - the condenser stands at grade below a receiver on 6-block saddles, so the condensate runs uphill;
  - long unsupported aerial runs;
  - E-101 stands on tall legs so its shell outlet's boundary line clears the ground;
  - the GameTest pass beacons (4 green beams) and structure blocks stay in the world.
- The review world keeps the plants of earlier builds running unless `run/review-world/world` is set aside before a
  build (build 8 loaded 26 islands from builds 6 and 7). Build 9 ran fresh; a later build reuses build 9's world unless
  it is renamed first.
- One "HELD: round deadline" about 420 ticks after the column's start, on the island numbered the column unit + 11
  (build 8: 4215 at committed tick 22838; build 9: 1399 at 1846), on a fresh world too. No view of the rig read HELD at
  any report, and the column reached a solved table after it. The island's line is not named; not chased.
- Valve hold (VALVE_HOLD_TRACE.md, section 9), not done: the review world rebuilt on C3 and B (build 8, above); the solver
  regression not run; `openStarved` may be redundant beside B (its test passes without it), owner to decide; the
  viscosity guard `MixtureViscosity.own` line 216 seen reached under C1 with a bundle prepared at another temperature,
  caller not named, not reproduced on the final code.
- `ColumnRunTest.aColumnsOutletFeedsAnother` (left out of the lane) trips the second column on its feed enthalpy,
  column side (cold feed), seen in the valve-hold work: its feed came at 900.8, 831.8 and 692.6 J/mol against the
  table's 1203.42 (slope 30 J/mol/K), the condenser duty departed 30, 37, then 51 %, and it tripped at tick 1281 while
  the line still flowed (inlet regulating at 0.0032 kg/s at tick 1300). Not changed.
- No merge preparation (owner).
