# S8: the plunger pump

Batch 2026-11-15-supercritical-fluids, stage S8, sub-thread, 2026-11-16. Plan: [PLUNGER_PUMP_PLAN.md](PLUNGER_PUMP_PLAN.md).
Owner: "Form A" for the model; the plan's section 2b for the law (a positive-displacement law, no clearance or work).
Commits: W1 `9b8ffce6e`, W2 `fc972bfc4`, W3 `5930c3932`, W4 `dc84378ea`, W5 `5042fca6b`, W6 the GameTest and these documents.

## Summary

**Expected.** A triplex plunger pump displaces its swept volume each turn. It delivers that volume at any density,
less a slip that grows with the rise, up to its relief valve. It has no head curve and does not lose its grip on a
light fluid. Its shaft power is the swept volume times the rise over its mechanical efficiency (about 0.88). On carbon
dioxide at 310 K and reduced density 0.7, a centrifugal pump keeps only part of its head (S4b's head share). A plunger
pump keeps all of its flow.

**What the code did.** The mod had one liquid casing, the centrifugal pump. On a light dense fluid, its head share fell
to 0.60 at δ 0.6 (S4b), and the simulator's Suction density row lit below 0.86 (S5). Nothing could pump such a fluid
at its full flow.

**What S8 does.**
- **W1, the law.** `PlungerPump` on `PositiveDisplacement` (form F2e). Its terms:
  - displacement × speed, a slip of 0.004 per relief rise, and a relief rise of 25 MPa;
  - a rated speed of 300 rpm and a mechanical efficiency of 0.88;
  - four models: PP-3, PP-10, PP-30 and PP-100 (m³/h at 300 rpm).

  Its shaft power is the swept volume times the rise over 0.88. `FlowControl`, the solver rows and the suction rules
  are unchanged (W3's table).
- **W2, the saves.** Fluid facts schema 28 (`plunger_pump`: displacement, relief rise, efficiency, rated speed) and
  checkpoint state format 51 (tag 9), each with a corpus image (`compat/fluid/schema-28`) and its `FORMATS.md` row.
  Protocol `icheme-4` was not needed: the constructor sends the form's ordinal, and the new form is appended, so no
  existing ordinal moves (as for the vacuum pump, prime mover and converter batches). The reservation row is removed.
- **W3, the network.** The probe below: the pump is never refused, limited or held from δ 0.3 to 1.6. Every reader
  of a liquid machine takes it as a pump (table below). No code change was needed.
- **W4, the equipment.**
  - `EquipmentShape.Kind.PLUNGER_PUMP`, `PlungerPumpDesign`, a constructor entry and its texts, the item and its icon,
    the tutorial page, the drawing scenes, and the running sound (the compressor's pulsing loop).
  - The model is form A with the main thread's shaft fix, moved unchanged. Its geometry is byte-identical to
    `tmp/plunger-forms/geometry-neck/A_*.json` for all four models (the shaft fix and the longer necks). The first placement (`geometry-merged/`) failed
    `everyShaftEndMeetsAFreeBlockForItsShaft` on PP-3 and PP-10: the shaft block stood diagonally next to the discharge
    pipe block. The fix (section 3) moves PP-10's discharge riser one cell forward and the coupling face of both small
    models one block back. PP-30 and PP-100 are unchanged to the polygon.
  - The plunger pump's two nozzles take the column's bore rule (`NozzleBore`): the largest bore whose neck stands clear,
    never less than the design pipe. Both decided by the owner (2026-11-16): asked "Keep the nozzle rule and the longer
    guard on the two small pumps?", the owner answered "Keep both".
  - `StructureRegistration` now chooses PUMP or COMPRESSOR by the control's law (a liquid mover is a PUMP). Before,
    it chose by `Kind.PUMP` alone.
- **W5, the simulator.**
  - The Pump tile is built as `CENTRIFUGAL_PUMP` or `PLUNGER`.
  - A plunger size is its model number. Its rows are Flow to rated (20 % spare) and a new SHOULD row, Rise to relief
    (25 MPa). It has no head, density or NPSH row. Its power is Q·ΔP/0.88.
  - Auto picks the plunger pump at ≤ 100 m³/h where the head is above the centrifugal casing's, or the suction is a
    dense fluid below δ 0.86. Otherwise it picks the centrifugal pump, so every example keeps its pump.
  - The sheet file needs no new version: the kind `PLUNGER_PUMP` and the type are read by name.
- **W6.** The dense GameTest (motor, gearbox, PP-10, CO2 at δ 0.7 from 8 to 20 MPa), the affected classes, and this
  review.

No layout row, nozzle pipe block or reference moved. The golden layout table gained only the plunger pump's rows.

## 1. The probe (W3): a PP-30 on carbon dioxide at 310 K

`PlungerPumpMoverTest`: full speed, a 10 m DN50 suction line, a discharge vessel 10 MPa above the supply. Output in
`tmp/supercritical-s8/probe/plunger-co2-310K.txt`.

| δ | p suction, MPa | ρ, kg/m³ | mode | flow, m³/h | share of displacement | rise, MPa | limit E | centrifugal head share (S4b) |
|---|---|---|---|---|---|---|---|---|
| 0.30 | 5.18 | 124.7 | MOVER_LAW | 29.951 | 0.99838 | 10.005 | none | (gas side) |
| 0.45 | 6.55 | 186.6 | MOVER_LAW | 29.951 | 0.99838 | 10.007 | none | (gas side) |
| 0.60 | 7.41 | 248.3 | MOVER_LAW | 29.951 | 0.99838 | 10.010 | none | 0.5958 |
| 0.80 | 8.01 | 330.2 | MOVER_LAW | 29.951 | 0.99838 | 10.013 | none | 0.9858 |
| 1.00 | 8.33 | 411.6 | MOVER_LAW | 29.951 | 0.99838 | 10.016 | none | 1 |
| 1.60 | 10.98 | 653.1 | MOVER_LAW | 29.951 | 0.99838 | 10.025 | 6.23 MPa (suction 10.98) | 1 |

The share is 1 − 0.004 × 10/25 = 0.99840, less the smooth minimum's rounding (2e-5). The pump work booked equals
Q·ΔP (83.3 kW at δ 0.6). No lock at any state. The gas side moves its volume too (owner item 1).

## 2. The readers of a liquid machine (W3)

| Reader | File, member | What it does with a plunger pump |
|---|---|---|
| Faults | `MachineHealth.faults` (line 136) | LIQUID_MOVER faults (dry, gas) as any liquid machine |
| Low flow | `MachineHealth` (line 211) | not reached: low flow is read for `CentrifugalPump` only |
| Suction | `MoverSuction` (384–398) | the liquid path; `npsh()` is null, so no NPSH design |
| Suction limit | `DeviceModes` (81, 120) | PUMP_SUCTION_LIMIT through the cavitation margin, as any liquid machine |
| Step solver, transport | `PassiveStepSolver` (1208, 1219), `ConservativeTransport` (597) | k 1.4 for the law's arguments (unused by the law) |
| Shaft trains | `ShaftTrains` | planned as SPEED, as every machine |
| Device kind | `StructureRegistration` | PUMP, from the control's liquid law (changed in W4) |
| Topology | `PhysicalFluidTopology` (183, 281, 289) | PUMP is valid for a liquid mover; line 289 applies to pipes only |
| Inlet phase | `InletPhase` (335) | the dry text is a pump's |
| View | `ViewBuilder` (44, 170–179) | no curve; pump status and "(limit 25000000 Pa on this fluid)" |
| Displays | `Displays` (360) | MoverState as a pump |
| Simulator run | `MoverUnit` (96) | unchanged: the run's door shows a centrifugal efficiency (owner item 5) |
| In-line rating | `InlineMovers.ratedShaftPower` | 0 for a plunger (its rise at the rated flow is ≤ 0); not used for casings (owner item 7) |

## 3. The equipment (W4)

| Model | Capacity, m³/h | Scale | Polygons | Shaft power at relief, kW | Motor (`Motor.ratingFor`: × 1.2, next standard), kW |
|---|---|---|---|---|---|
| PP-3 | 3 | 0.55 | 1,275 | 23.7 | 30 |
| PP-10 | 10 | 0.75 | 1,275 | 78.9 | 110 |
| PP-30 | 30 | 1 | 1,275 | 236.7 | 315 |
| PP-100 | 100 | 1.35 | 1,283 | 789.1 | 1,000 |

Polygons by material (PP-3 to PP-30): PUMP_CASTING 385, PUMP_JOINT 246, FLANGE 196, HEAD 128, BOLT_FACE 102, BOLT 96,
SHAFT 57, PIPE 16, CONCRETE 14, GLASS 10, COUPLING_GUARD 10, PUMP_COVER 7, PUMP_CHANNEL 4, OPENING 2, PUMP_DISC 1,
PUMP_BASE 1. PP-100: PUMP_CASTING 393. For comparison, the dry screw pump has 435 to 775 polygons. Every model has two
nozzles (suction and discharge), four motions and twelve solids.

**The placement, and the stop.** The first placement, `geometry-merged/`, failed three equipment gates. The main thread
decided a variant of option A; no gate is exempted. The owner kept both changes (2026-11-16, "Keep both"): the coupling face
one block further back on PP-3 and PP-10, and the plunger pump's nozzles on the column's bore rule.

| Model | Shaft block (x,y,z) | Discharge pipe block | Distance before | After the fix: shaft block, discharge pipe block, distance |
|---|---|---|---|---|
| PP-3 | (0,1,3) | (1,2,2) | 1 | (0,1,4), (1,2,2), 2 |
| PP-10 | (1,1,3) | (2,2,3) | 1 | (1,1,4), (2,2,2), 2 |
| PP-30 | (1,1,3) | (3,2,3) | 2 | unchanged |
| PP-100 | (1,1,5) | (3,2,4) | 2 | unchanged |

- The fix is in `monoblock` and `drive`. The discharge riser's clearance is `2 rb + 0.05` (was 0.1), which moves PP-10's
  riser from z 4.5 to 3.5. `drive` takes a least face: where the risers are in the cell row next to the shaft line
  (`floor(xd) − floor(XC) < 2`), the face is at least the riser's z plus 1.5. The coupling face moves to z 5 on both small
  models (was 4).
- **The necks.** All eight necks failed `everyNozzleNeckAtItsLargestStandsClear` at a pipe's largest bore (1 m). The
  plunger pump's nozzles now take the column's rule in `NozzleBore.largest`. The kind condition was enough: the column
  path keys its cache by the shape and searches the shape's own solid. The resulting bores (design pipe, then largest),
  on the final necks:

| Model | Suction, m | Discharge, m |
|---|---|---|
| PP-3 | 0.045 → 0.80 (a pipe's largest) | 0.045 → 0.60 |
| PP-10 | 0.045 → 0.80 | 0.045 → 0.20 (its dampener beside the neck) |
| PP-30 | 0.091 → 0.60 | 0.045 → 0.80 |
| PP-100 | 0.136 → 0.80 | 0.091 → 0.80 |

  Under a pipe's largest, the next step (0.05 m larger) is not clear. On the first necks (3 cm shorter) seven nozzles took
  0.50 m and PP-10's discharge 0.20 m.
- **The arrows** (`PortMarkingsTest`, a second stop). The count is taken at the design size, so the bore rule cannot
  change it. At first none of the eight plunger necks had a band: 473 of 485 nozzles carried arrows (97.5 %, needing
  98 %). Each neck was 0.2112 m long, bare to 0.125 m from its base (length less the flange 0.0313 and the band's flange
  clearance 0.055). The riser pipe under the neck (radius od/2 + 0.015, drawn to the neck's base) hid its first 5 mm, so
  the band was shown 0.110 m, less its margin 0.02: 0.090 m against SHORTEST 0.100. The main thread lengthened the neck
  by 3 cm (`riser`'s stand-out 0.05, was 0.02; 1 cm would tie at 0.100). The flange faces stay at y 2, and the cells,
  pipe blocks and polygon counts do not change. Each neck is now 0.2412 m, bare to 0.155 m, and shows 0.120 m on all four
  sides on every model. The geometry is byte-identical to `tmp/plunger-forms/geometry-neck/A_*.json`.

The drawings (`tools/equipment-drawings/update.ps1 -Only PLUNGER_PUMP`) ran into
`tmp/equipment-drawings/20261116-043157-cdb869/`. The iso and the sheet were checked.

## 4. The simulator (W5)

Renders in `tmp/supercritical-s8/renders/` (`GuiPrototypes -Donly=plunger`, `SimulatorRender -Donly=plunger`), carbon
dioxide at 310 K, δ 0.7 (7.765 MPa, 289.6 kg/m³):
- `plunger-pump-0_7.png`, the device page of a PP-30 at 20 m³/h and a 10 MPa rise. It reads: shaft power 63.1 kW,
  efficiency 88.0 %, head 3,521 m, speed 66.7 %. NPSH available and required read "—".
- `plunger-0_7-sizing.png`, the Sizing tab:
  - Size all: Auto proposes PP-100 for 60.2 m³/h, Flow to rated 72.2 %, Rise to relief 0.20 MPa, every limit met.
  - The same pump fixed as a centrifugal pump (discharge 0.10 m): Suction density 0.70 against at least 0.86, yellow.
- `plunger-0_7-doors.png`, the feed and the pump's door after the run.

`PlungerSizingTest` (6 tests) proves Auto's rule, the model choice with its spare, the rows (no density, head or NPSH
row), the power Q·ΔP/0.88, and the sheet file's round trip of a plunger size and its type.

## 5. The dense GameTest (W6)

`DenseFluidGameTests.aPlungerPumpDeliversItsDisplacementOfDenseCarbonDioxideAgainstTwentyMegapascals`, namespace
`icheme_dense_test`, run with the namespace alone. The first run (`s8-plunger-1`) failed on the test's own reading. The
wait passed on the intervals from before the motor ran: with no power the island certified at rest and published nothing
new. So the last interval it read carried no transfer ("No flow from the generator or no suction state: []"). The
wait now counts from the first interval in which the generator gives in, and needs the last one to deliver. The test
changed, so each later run is a new run, not a repeat. Every failure was in the test's own construction or reading, never
in the engine:

| Run | What the test did wrong | Fixed by |
|---|---|---|
| `s8-plunger-1` | the wait passed on the rest intervals from before the motor ran | counting from the first delivering interval |
| `s8-plunger-2` | a new gearbox starts at ratio 4 (fast over slow), not Auto: the pump turned at 19.67 × 300 rpm | selecting Auto, as `CreateCouplingGameTests` does |
| `s8-plunger-3` | it read the suction pipe's node, but a pipe block is an edge, not a node | the generator's own state on the network's model |
| `s8-plunger-4` | it read the first of the generator's three per-substep transfers | their sum over the interval |

`s8-plunger-5`: all 4 tests of the namespace passed (the plunger pump and S2c's loop, let-down and two nozzles). The plunger
pump, PP-10 at 300 rpm (speed share 1.000) on a 110 kW motor through its gearbox:

| Quantity | Value |
|---|---|
| Suction | 312.41 K, 8.000 MPa, δ 0.700, 289.57 kg/m³ |
| Rise | 12.000 MPa (the void at 20 MPa) |
| Delivered volume | 9.9856 m³/h (the island STEADY at 0.8032 kg/s) |
| Displacement less slip | 9.9808 m³/h: 10 × (1 − 0.004 × 12/25) |
| Difference | +0.05 % (bound 0.5 %) |
| Shaft power | 37.88 kW = Q·ΔP/0.88 |
| Hold | none; worst ledger closure 0 (components), 2.2e-16 (energy) |

RESULT-GAMETEST

## 6. Runs

From `node tools/merge-lane/affected.mjs select --since HEAD` (the W4 to W6 working tree, `tmp/supercritical-s8/affected.txt`),
one filtered invocation per task, 4 workers (`tmp/supercritical-s8/runs.sh`, logs in `runs/`). Left out: `LanguageFilesTest`
and `UnitTextTest` (translation commit), `EngineDigestTest` (final lane), `NativeColumnSolverTest`. The selection chose no
chain-100 run.

| Run | Task | Classes | Tests | Failed |
|---|---|---|---|---|
| 1 | `test` | 121 | 1,130 | 5, all S8: the coverage sample, the guide's page count (41 → 42, two tests), two tutorial lines over 14 words, the Chinese sizing door |
| 1 | `columnGates` | 2 | | 0 |
| 1 | `equipmentGates` | 24 | 215 | 5: the golden table (new rows), the shaft rule, the necks, my plunger test, the arrows (the stop) |
| 1 | `holdGates` | 6 (2 filters) | | 0 |
| 2 | `equipmentGates` | 21 | 200 | 2: the golden table (only the four new plunger rows: copied), the arrows |
| 2 | `test` | 85 | 710 | 2: the Chinese sizing door, `ConstructorColumnTest` (my sample shifted its index: moved to the end) |
| 2 | `holdGates` | 9 | | 0 |
| 3 | `test` | 2 (`ConstructorColumnTest`, `SizingCoverageTest`) | | 0 |
| 5 | GameTest `icheme_dense_test` | 4 tests | | 0 |
| 6 | `equipmentGates` | 3 (`PortMarkingsTest`, `ProcessUnitTest`, `EquipmentLayoutGoldenTest`), after the longer necks | | 0: arrows on 481 of 485 nozzles |

Run 2 took the classes whose recorded load set holds a file changed since run 1 (`tmp/supercritical-s8/reach.js`: the
design, `NozzleBore`, the changed tests) and the readers of `en_us.json`.

Left for the final lane: `SizingPageTest.theDoorFitsInEveryViewForEveryItemInChinese` (the zh_cn keys of the Pump tile's
types come with the translation commit), `LanguageFilesTest`, `UnitTextTest`, `EngineDigestTest`, and the golden table's
re-run with its new rows (green in run 6).

Earlier runs of this stage (W1 to W3): `PlungerPumpTest` 9, `FluidCheckpointCodecTest`, `FluidFactsCompatTest` (the
corpus image written and read back), `FormatsTableTest`, `PlungerPumpMoverTest`, all green.

## 7. Owner items

1. **The gas side.** At δ 0.3 and 0.45 the plunger pump moves its volume of gas as it does a dense fluid (share 0.99838).
   A real plunger pump on a gas loses volume to the re-expansion of its clearance (`PD_PUMP_RESEARCH_REVIEW.md` section 2,
   at a 10 % clearance: η_v 0.862 for CO2 gas from 1 to 3 MPa, 0.966 for CO2 at δ 1.10 from 8.5 to 25 MPa). Options: (A) leave it, the game-level law; (B) the clearance term as its own batch,
   which would also give the work above Q·ΔP.
2. **Auto's rule.** A plunger at ≤ 100 m³/h where the head is above the centrifugal casing's, or the suction is dense
   below δ 0.86. A sheet whose pump was centrifugal and meets that rule now proposes a plunger pump. None of the bundled
   examples does.
3. **The sound.** The plunger plays the compressor's pulsing loop at its pitch law (300 rpm clamps to 0.5).
   Synthesised candidates can follow with the `models-and-sfx` tools.
4. **Bolts as relief.** The cover bolts (BOLT 96, BOLT_FACE 102 polygons, 16 % of the model) could become a normal-map
   relief on the cover. That would save about 190 polygons a model.
5. **The simulator's run door.** The run (`MoverUnit`) is unchanged, so the pump's door after a run shows a centrifugal
   efficiency (64 % at 60 m³/h). The sizing duty is Q·ΔP/0.88. Options: (A) leave it; (B) the run reads the sized type's
   efficiency.
6. **The model entry on the Sizing tab.** The Model field shows the model number (1 to 4) with the unit "m³/h". This is
   the shared `dimensions` path: the vacuum pump and the prime mover show the same. A follow-up could show the capacity
   while the steppers keep stepping the model.
7. **`InlineMovers.ratedShaftPower`** gives 0 for a plunger, because its rise at its rated flow is ≤ 0. Only in-line
   movers use it, and a plunger pump is never in-line. Noted.
8. **The pump page's gas meter on a dense fluid.** The client's dense label reads the head share, `M(α, p)`, a
   centrifugal pump's quantity. A plunger keeps its whole flow, so the meter understates it. The page has no law field.
   A follow-up could send the law's kind.
9. **An in-game check** of the constructor, the model and its sound can be offered. Nothing else needs one.
