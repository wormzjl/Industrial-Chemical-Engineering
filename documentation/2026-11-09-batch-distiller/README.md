# Batch distiller

Status: **In progress since 2026-11-09** (owner decisions B1 to B8 answered; build in three phases). Branch
`claude/furnace-fuel-power-requirements-f7a46e`: it reuses the burners and FE draw of
[2026-11-09-furnace-fuel-power](../2026-11-09-furnace-fuel-power/README.md).

## Scope

Owner, 2026-11-09: a batch distiller, less industrial than the columns and the step before them. The pot is heated by a
solid- or liquid-fuelled firebox or an electric heater (no jacket) at a realistic efficiency. Solid fuel uses Minecraft's
fuel values (burn ticks), converted at mod-market values with coal coke as a starting point; the item mass is free. The
burner is animated. Options: a built-in water-cooled condenser (U constant, A by size, matched to the pot) and a small
column with a reflux pot and a manual reflux valve. Dynamic batch runs with the distillate collected by hand, and a batch
distiller in the process simulator.

## Documents

- [BATCH_DISTILLER_PLAN.md](BATCH_DISTILLER_PLAN.md): reference photos, physics in numbers, what the code has, proposal
  W1 to W8, owner decisions B1 to B8, what the request leaves open, the B3 and B6 evaluations (section 7).
- [BATCH_STILL_LAW.md](BATCH_STILL_LAW.md): W1, the law, the still's column and the solid-fuel records.
- [BATCH_STILL_STRUCTURE.md](BATCH_STILL_STRUCTURE.md): W2, sizes, parts, nozzles and the data gland.
- [BATCH_STILL_SIMULATOR.md](BATCH_STILL_SIMULATOR.md): W7, the simulator unit, its batch curve and sizing.
- [BATCH_STILL_ENGINE.md](BATCH_STILL_ENGINE.md): W3 to W5, the still in the fluid engine, its fuel, its data channels and
  controllers, the diverter valve, owner decisions O1 to O10.
- [BATCH_STILL_GUI.md](BATCH_STILL_GUI.md): W6, the still's page, the constructor's still options, the animation, the diverter's and the
  electric still's sockets, owner questions G1 to G6.
- [OWNER_FEEDBACK_REVIEW.md](OWNER_FEEDBACK_REVIEW.md): the owner's four items on the built still (condenser, textures,
  data port and fuel, volume) and what was built.
- Run output of B3 (local): `research/2026-11-09-batch-distiller/b3-column/` (`report.txt`, `snapshots.csv`).

## Code areas

- `science/fluid/network/BatchStill` (the law), `StillColumn` (bubble point, energy balance, pot mode, on the network's
  phase evaluator), `science/material/SolidFuelCatalog` and `materials/fuels/`; config `energy.joulesPerBurnTick`.
  Reached by game code since phase 2.
- `runtime/fluid/Stills`, `StillUnit` (fluid facts schema 15), `science/fluid/network/StillHead`, the diverter valve
  (`TopologyCompiler.Kind.DIVERTER_VALVE`, its block), the still's controllers (`runtime/control/ControlLaw`,
  `ControlPlans`, `ControlService`), `EquipmentStructure` (register, refresh in place, the fuel slot).
- `science/equipment/BatchStillDesign`, `BatchStillOptions` (shape fields 11), `ProcessUnit` (pot, firebox or skirt,
  worm tub, column), `EquipmentServices` (data gland); the constructor entry and a DRAWING_ONLY simulator tile.

- `src/test/java/com/wormzjl/icheme/science/column/batch/`: `BubblePointColumn` (the B3 candidate, both forms) and
  `BatchColumnComparisonTest` (a quick sanity test in `test`; the comparison runs only with `ICHEME_BATCH_COLUMN=1`).

## History

- 2026-11-09: research and plan; reference photos (Yoichi coal-fired pot stills, Koval's Kothe hybrid still, the 1880
  Pioneer oil refinery, Coupier's still); schema drawn. No code.
- 2026-11-09: owner: a block is 9 items and 1 m³, at bulk density. Solid fuel redone: one coal 93 kg, 2.31 GJ; B1 now
  between burn ticks on a coal anchor (coke 4.63 GJ) and mass × LHV per material (coke 1.45 GJ).
- 2026-11-09: owner chose B1 B, mass × LHV per material at bulk density; burn ticks × 1.45 MJ only without a record.
- 2026-11-09: owner answers B2 to B8 (electric 90 %; B3 try a simpler column model against V3; B4 A plus an automatable
  switching valve; B5, B7, B8 A; B6 evaluate the solver cost first). Plan section 7: the B3 experiment's design and the
  B6 evaluation (no Newton cost; a never-resting island for about 5 to 7 h after each batch; fold the loss into the
  efficiency instead).
- 2026-11-09: B3 measured (plan section 7). The bubble-point method with its energy-balance flow update converges on
  every pot step, about 13 × faster than V3 cold and 5 × warm, within 0.004 mole fraction and 0.01 K of V3; the
  constant-molar-overflow form is off by up to 0.13 on crude. Found on the way: V3 refuses `icheme:refinery` (CO₂ crystal
  phases), and a crude pot's own warming takes 2 to 2.7 times the boil-up heat (plan section 6, item 12).
- 2026-11-09: owner adopted B3: the still's column is the energy-balance bubble-point form in pot mode on the network's
  phase evaluator; V3 stays for the big columns; the constant-molar-overflow form is dropped.
- 2026-11-09: owner chose B6 A: no heat loss to ambient, folded into the heater efficiency.
- 2026-11-09: owner: start building. Phase 1 in two Opus sub-sessions, still-law (W1 and the fuel records) and
  still-structure (W2); phase 2 still-engine (W3 to W5) and still-simulator (W7); phase 3 still-gui (W6). Each hands
  back a diff; the main thread tests and commits.
- 2026-11-09: phase 1 landed. W1 (still-law): the law, `StillColumn` and five fuel records; `BatchStillTest`. W2
  (still-structure): `BATCH_STILL` with heater, condenser and column options, 6 new layout lines, shapes corpus image 11.
  Tests: `BatchStillTest`, `science.material.*`, the GUI and codec classes the patches reach, and the equipment gates'
  shape, service, golden and compat classes, all passing. Fixed on the way (this branch's flare and furnace work): the
  flare's `flare_gas` role and unit-support keys, the example datapack's methane `combustion` record. Drawings not run
  (no Python or Poppler on this machine).
- 2026-11-09: owner kept every phase-1 answer as built (option A). Law: flood at F 1.8 Pa^0.5 (design 1.5 + 20 %); a
  light naphtha head on ambient water vents its excess (COOLING LIMITED); an IDLE status; condenser capacity at the mean
  of head and condensate temperatures; BOIL DRY at 5 % of the pot volume; the distillate leaves at its bubble point; the
  column takes hydrocarbons only. Structure: O1 a column needs the built-in condenser; O2 the column has an overhead
  tube condenser, the worm tub stays for the pot still; O3 placeholder textures; O4 the kettle symbol until W7; O5 the
  charge nozzle at row 2.5 from 750 L. Phase 2 started: still-engine (W3 to W5) and still-simulator (W7).
- 2026-11-09: W7 landed (still-simulator): `BatchStillUnit` and `StillBatch` on the W1 law, `Report.Series` and
  `SimulatorResults` format 2, `StillSizing` with its limits, the doors' Batch tab (`StillCurve`), `Symbol.STILL`, the
  tile MODELLED. Tests: `BatchStillUnitTest` (8), the flowsheet sizing, block, frame, page, door, editor and column
  classes, and `holdGates` `SimulatorRunTest`, all passing; renders in `tmp/still-render/`. Main-thread fixes: a
  one-point series the round-trip test meant as empty; the condenser-sized checks on a heavier naphtha (C6 to xylene),
  since a pentane-rich head on 25 °C water vents 65 to 79 % of its vapour; the render scene's stills with the drop
  defaults. The diverter's simulator case waits for still-engine's `DIVERTER_VALVE`.
- 2026-11-09: phase 2's engine landed (still-engine, fixed and finished on the main thread: owner, "Fix here instead"). W3
  to W5: the fired pot, the column head on the worker, the outlets and their accounts, the condenser's water coupling,
  solid, liquid and electric fuel, the data channels; the diverter valve (fluid facts schema 15, state format 42; corpus
  image schema-15). Owner kept O3 to O8 and O10 as built; O1, O2 and O9 built as option A (BATCH_STILL_ENGINE.md
  sections 4 and 5): a controller on the pot holds the distillate flow by its firing (action INLET) or its reflux (OUTLET),
  one correction per 42 s response; a controller switches a diverter at its target (straight below, first side at or
  above); a refresh moves the still's devices in place, keeping identities, facts and contents. Main-thread fixes: a
  closed reflux valve saved as a flag (the facts refuse +Infinity); the corpus still moved off the crossflow exchanger's
  cell; the pentane rig's basis with nitrogen; the water side's first read before the pot's (NaN ceiling); the refresh
  had left the old outlets and burners registered. The diverter is the simulator's splitter (FlowsheetCatalog).

- 2026-11-09: W6 written (still-gui): the still's groups on its pot's page (Firing, Reflux valve, Fuel, a controller's
  hold, the readings; `FluidView.Still`, `fluid-20`), the constructor's Heater, Condenser and Stages (`equipment-22`), the
  fires, smoke, lamp, boiling and drip (`StillFires`, `still-1`), the diverter's actuator and data gland, and the electric
  still's missing FE socket. Owner questions G1 to G6 built as option A (BATCH_STILL_GUI.md).
- 2026-11-09: W6 landed (main thread): compiled and tested, 66 classes (47 test, 17 equipmentGates, 2 holdGates), 597 tests passing (the two corpus classes after their images were copied); renders in `tmp/still-gui-renders/` (three
  still pages, two constructor pages). Main-thread fixes: a duplicate `finite` helper; the FE gland moved opposite the
  data socket, the rule every powered unit keeps (G6 settled by that gate); the constructor draft's still options got
  their corpus image `designs-2.snbt` (G5: the draft has a corpus); the layout table's electric still row gains its
  terminal cell (cells, stamp and pipes unchanged); the W2 and terminal tests now expect the FE socket; a key-presence
  test read a no-argument line with arguments.
- 2026-11-09: owner kept G1 to G4 as built (option A): the controls on the pot's Overview, Load and Take out for the
  fuel, Off sends 0 % (the opening kept on screen only), vanilla particles and sounds.
- 2026-11-09: drawings (`update.ps1 -Only BATCH_STILL`, Python 3.13 and Poppler 25.07 installed): 5 stills, 10 sheets at
  5cb073213, inspected; run `tmp/equipment-drawings/20261109-135451-f050c6/` (local). The electric still's FE gland
  faces the pot at 0.5 m, under the heater conduit, opposite its data gland.
- 2026-11-09: owner feedback on the drawings (OWNER_FEEDBACK_REVIEW.md), built on the main thread. Every condenser is the top
  condenser; a pot still has no reflux valve (option A; `BatchStill.Design.refluxes` is the one rule for the law and the
  engine). The condenser stands on two posts. The fired still's data box stands in front of the setting with a conduit to
  the pot. The liquid fuel connection is on the back face's right column. Baked sprites (`tools/still-sprites`): the pot and
  condenser are riveted copper, welded carbon steel or welded stainless by heater tier; the lines are drawn copper, the
  setting brick, the doors cast iron. The layout table was re-recorded (still rows only).

## Open items

- The still-models session's style mapping (one firebox style per heater): owner's confirmation in that session; its
  concept doc and renderer are on `claude/batch-still-models-b3de99` (e82242bdb), not merged here.
- W7 owner questions (built as option A; details in BATCH_STILL_SIMULATOR.md): water in the charge refused; a hot
  charge's let-down booked as the still's duty; the condenser water a setting, not ports; a head cut ends late by up to
  one step; defaults 2 h cycle and 25 °C water. Seen in the renders: a cut collected at each step's bubble point and
  averaged over the cycle is slightly two-phase (cut 2 about 2 % vapour), as the distillate is not subcooled in the
  worm (phase-1 law answer 7).
- An in-game check of the still's page and its effects, only if the owner asks.
- Feedback item 4: the owner rechecks the pot's drawn volume against its design volume (measured within 3 %).
- `UnitTextTest` fails on the flare stack's datasheet widths (417 > 411 px); not this batch's lines.
- Engine options not built (BATCH_STILL_ENGINE.md section 6): a head-temperature reflux law (its gain is not set by the
  physics), a second diverter threshold for the second side outlet.
- At the merge: move `BubblePointColumn`'s measurement harness to `tools/batch-still-column/` (tooling cleanup); code guide
  rows for `BatchStill`, `StillColumn` (fluid-science.md) and `fuels` (thermo-materials.md).
