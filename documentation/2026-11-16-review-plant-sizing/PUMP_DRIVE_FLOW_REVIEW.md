# Pump drive flow: packages 0 and 1 (review)

Branch `claude/pump-drive-flow` from `07a36f3ea`. Brief: [PUMP_DRIVE_FLOW_PLAN.md](PUMP_DRIVE_FLOW_PLAN.md), packages 0
and 1 only. No production change.

## Package 0

(a) A creative motor regulates, with no data link.
- `ShaftTrains` 883 enters the data-link block for `Kind.MOTOR` only.
- For any other driver, `run.measured` and `run.target` come from the train's first machine (774-777): q = flow at
  suction density, target = setFlow.
- `ShaftTrain.regulated` (217-226) then runs `SpeedControl.regulate` in REGULATE_FLOW. For `Drive.CREATIVE`, design = base
  = `setSpeed()`, which is its setPoint in rpm, or `CREATIVE_SPEED` 2950 when that is 0 (`ShaftTrain` 76-79). The range is
  0.1 to 1 of that speed, at most 0.2 per decision.
- `ShaftUnit.Settings` (running, mode, setPoint, powerLimit, ratio) takes any mode. `ShaftTrains.Configure` (435-443)
  stores it with no check. The page's check (`MachineNetwork` 348-351) keeps setPoint in a regulating mode.
- Status: RUNNING, or POWER LIMITED below its set speed (`ShaftTrain` 151-155).

(b) A motor on FE regulates only over a data link.
- `host.linked(id)` is `ControlService.connectedHosts` (`FluidWorldAuthority` 2108). That is `ControlHosts.connectedHosts`
  (392-402): the hosts whose ports share a bus with the motor's port (`DataPorts.motor`, bound at `ControlHosts` 281).
- The pump is a candidate when its fluid id is among them (`ShaftTrains` 885). Otherwise the status is NO DATA LINK /
  LAST SPEED (910).
- The motor's speed is then `Duty.flowSpeed()`: `SpeedControl.requiredSpeed` (58-65) inverts the curve at the set flow and
  this slice's booked head. It goes in through `driver.atSpeed` (907, mode SPEED), so the 0.2 step does not apply going
  down.
- No GameTest wires a motor to a pump. The rig uses the review world's own helpers: `ColumnFailureModesReviewWorld.port`
  (480) for each structure's DATA port, and `lay` (495) for the cable between them.

(c) At the upper bound there is no hold and no SPEED LIMIT status.
- `regulate` clamps to `highest`: 1 for a creative motor, `Motor.maximumShare` = 3000 rpm / base for a motor.
- `requiredSpeed` returns its upper bound when even that speed's rise is short: `MAXIMUM_CURVE_SPEED` 1.5 share, then
  clamped to `Motor.MAXIMUM_RPM` 3000 (907).
- The drive stands at that speed and reads RUNNING, or CURRENT/POWER LIMITED where its power runs out. A status SPEED LIMIT
  (plan D3) does not exist. Adding it is a production change for package 2 or 3; package 1 does not need it.

## Package 1: the rig (`ColumnOperationReviewWorld`)

- Charge valve removed: its placement, flowmeter, law and readout, and the `chargeValve` and `chargeMeters` fields.
- The set flow is `CHARGE_SHARE` × the design charge mass flow / the suction density: 1.05 × 159.42 = 167.39 kg/s, at
  865.4 kg/m3, giving **0.19344 m3/s**. The density is that of the flowsheet's `crude` stream state (the pump's suction,
  298.15 K, 10 bar). The pump's rated flow is 0.21206 m3/s.
- The set flow is written by the device edit a page's Apply queues (`world.edit`, `Machine` with setFlow), once the
  pump's train has compiled (`chargeDriven`).
- The drive is put in REGULATE_FLOW, stopped, through `ShaftTrains.Configure`. `openCrude` starts it and opens the
  crude's supply. The supply stays shut while the heaters light: at 10 bar it would push crude through a standing pump.
- With `options.motors()`, a DATA cable is laid from the motor's port to the pump's. No rig option combines motors with
  the crude unit, so this path is compiled but not run.
- `chargeCheck` logs `review_operation t=… charge_pump set_m3_s flow_m3_s kg_s suction_kg_m3 speed_rpm top_rpm
  speed_share mode status` at every report. At report 1 it fails the test unless the flow is within 2 % of the set flow
  and the speed is below top.
- The other pumps are unchanged. The sizing log line `charge_valve` is now `charge_pump`. Heater figures are unchanged:
  cold start at the charge flow 144.8 MW, held 183.8 MW, 26.9 % spare.

## Test run

Selector `affected.mjs select --since 07a36f3ea`:
- The GameTest step is due.
- It picks 5 `test` classes and 1 `columnGates` class (VesselSectionTest, FlowValveCapacityTest, DrumDemisterFileTest,
  DrumSettlingTest, SettlingBandTest, V3RefluxFreeWaterTest). They are picked only because they have no load set; they
  cannot load a `fluidGameTest` source. Not run.

Run: `runReviewWorldServer -PreviewBatch=review-operation --max-workers=4`, log `tmp/pump-drive-flow/review-operation.log`.
- 1 test, **failed** on the new check at report 1 (t=1426).
- The connection check (`Plant.unjoined`) passed.
- The column started after `HOT_TICKS`, as in builds 8 and 9.

| t | set m3/s | flow m3/s | kg/s | speed rpm | share | status |
|---|---|---|---|---|---|---|
| 1426 | 0.19344 | 0.36845 | 318.85 | 1770 of 2950 | 0.600 | RUNNING (creative, REGULATE_FLOW) |

Tower feed valve FEED0: VALVE_REGULATING, feed account 0.27 of its aim.

## Finding: the suction pressure sets the flow, not the pump

- The crude supply stands at `CRUDE_SUPPLY` = 10 bar and the tower at 2.5 bar. That leaves 7.5 bar across the line with no
  pump head.
- The tower's feed valve asks up to twice its rate while its account is short (`ColumnOperation.inletPlan`). 318.85 kg/s
  = 2.0 × 159.4.
- At 0.6 share the pump adds about nothing at 1.74 × its rated flow (its curve's head is near 0 or below there). The feed
  valve still regulates, so the open line needs less than 7.5 bar at 2 × design. Under the square law that is less than
  1.9 bar at design and less than 2.1 bar at 1.05 × design.
- A drive can only add head. While suction minus tower pressure is above the line's drop at the set flow, the flow is the
  downstream valve's, at any speed. The drive steps down to its lowest speed (0.1) and holds nothing.
- Under the valve the charge valve took this excess: 167.39 kg/s regulating, feed valve open (build 8).
- A real charge pump takes suction from an atmospheric tank at about 1 to 2 bar, and makes the line's whole drop.
- Moved number: the charge flow at report 1 went from 167.39 kg/s (valve regulating, build 8) to 318.85 kg/s (the tower
  valve's 2 × ask).

Options (owner):
- **A.** Crude supply at a storage tank's pressure, for example 1.5 bar.
  - The pump makes the line's drop, and the drive can hold 1.05.
  - `CHARGE_PRESSURE` 16 bar stays, so the pump's design rise grows from 6 to 14.5 bar. The pump is resized (head, motor
    rating, NPSH), and the sizing log numbers move.
- **B.** The charge pump's set point is the tower's feed pressure (REGULATE_PRESSURE) instead of a flow. The flow is the
  feed valve's. Not what the plan asks.
- **C.** Keep 10 bar and accept the drive at its lowest speed with the tower valve holding the flow. That needs the
  assertion changed: not allowed without the owner.

Recommendation: A (real physics; the plan's expected state, "the pump runs slower than rated", holds there).

## Not verified

- The motor-on-FE path: data cable, `requiredSpeed`.
- Whether 6 decisions had passed by report 1. The report came about 400 ticks after the start. Share 0.6 is two
  0.2 steps from 1.

## Option A: crude supply at 1.5 bar (main thread's call)

Change: `CRUDE_SUPPLY` 10e5 → 1.5e5. Nothing else is touched. The rig's sizing log now prints each reading's limits.
One replay, `tmp/pump-drive-flow/review-operation-run2.log`, on a fresh world (run 1's world set aside in
`tmp/pump-drive-flow/world-run1`). 1 test, **failed** at report 1.

The sizer resizes P-101 (old at 10 bar → new at 1.5 bar):

| P-101 | old | new |
|---|---|---|
| flow, m3/h | 729.5 | 730.0 |
| rise, bar / head, m | 6.0 / 70.70 | 14.5 / 170.98 |
| hydraulic / shaft, kW | 110.5 / 132.1 | 267.3 / 319.4 |
| motor rating / electric, kW | 160 / 138.2 | 400 / 333.4 |
| share of rated flow, % | 95.56 | 95.63 |
| NPSH available, m | 110.9 | 11.03 |

NPSH at 1.5 bar:
- Available 11.03 m.
- Against NPSH3 + 1 m = 4.30 m (`PUMP_NPSH`): NPSH3 3.30 m, margin 6.73 m.
- Against the world's suction limit 8.47 m (`PUMP_NPSH_WORLD`, the clean zone's edge, above 0.85 NPSH3 = 2.8 m): margin
  2.56 m.
- The crude does not cavitate at design. Its gas zone edge, not its vapour pressure, sets the limit: about 0.22 bar
  above suction pressure − 11.03 m, so near 1.28 bar at the pump's suction, if suction height and line drop are left
  out. Not separated further.

Other rig numbers that move:

| number | old | new |
|---|---|---|
| crude suction line bore, m | 0.4 | 0.5 |
| set flow, m3/s / suction density, kg/m3 | 0.19344 / 865.4 | 0.19357 / 864.8 |
| cold start, MW / at charge flow, MW / spare % | 151.7 / 144.8 / 26.9 | 151.6 / 144.7 / 27.0 |
| H-101 duty, MW / heater fuel, MW | 66.31 / 69.0 | 66.25 / 68.9 |

Unchanged: column 7.5 m, 40 trays, 73.4 % flood, condenser 28.317 MW, every other bore, heaters held 183.8 MW.

At report 1 (t=1426):
- The crude island (generator, P-101, coolers' tubes, E-101's tubes, both heaters, the feed port) HELD from committed tick
  1206, at the column's start: "Substep refinement exhausted: Newton line search stalled at residual 305.46; active-set
  pass 0".
- Charge flow 0. Drive at 2950 of 2950 rpm (share 1.000), REGULATE_FLOW, RUNNING. The tower's feed valve was held with
  its island; the column read LIMITED, FEED_SHORT, 0 %.
- Run 1 (10 bar) did not hold at the start.

## Finding 2: the placed P-101 never got the sizer's head

- The rig places `PumpSizing.Size.shape()`, which is `UnitSize.unit(PUMP, discharge, …)`: a shape with no duty.
- `MachineRatings.machineControl` then builds the curve from `UnitDesign.pumpRating`: the bore's rated flow 0.21206 m3/s
  (763 m3/h) and `UnitRatings.PUMP_HEAD` = 100 m, whatever the sizer found. The same is true in both runs.
- At 10 bar the 100 m curve (8.5 bar) was above the 6 bar the line needed.
- At 1.5 bar the line needs 14.5 bar (171 m). The placed curve's shut-off is 1.21 × 100 = 121 m (10.3 bar): the pump
  cannot make the line's head at any speed.
- The island's start (feed valve opening onto a line its pump cannot fill against 2.5 bar + drops, the pump on its curve
  near shut-off) is where it held. The mechanism was not traced: one replay only.

Options:
- **R (recommended for package 1).** The rig builds P-101 with the sizer's own duty: `shape.withDuty(MachineDuty.pump(730
  m3/h, 171 m, poles))`, from the sizer's readings, not a hand-set rise. Rig file only. The hold is then rerun on the right
  curve (one replay).
- **P.** `PumpSizing.Size` carries its duty into its shape, so every sized pump a player builds from Size all gets its
  head. A production change; it belongs with package 4 ("a sized pump built regulating").
- If the hold stays on the right curve, it is a solver finding (start of a liquid line onto an opening valve), as in
  the series-valve start holds of builds 7 and 8.

## Option R: every sized pump built at the sizer's duty (rig stand-in)

`ColumnOperationReviewWorld.pump(SizedUnit)` gives each sized pump's shape
`withDuty(MachineDuty.pump(Duty.of(rating).flow(), .head(), shape poles))`. The flow is in m³/h and the head in m, which
are `MachineDuty`'s units: `UnitDesign.pumpRating` 483-486 divides the flow by 3600. `MachineRatings.machineControl` 27-30
builds the curve from that rating, and `PumpDesign.checkDuty` 54-61 accepts it.

One replay, `tmp/pump-drive-flow/review-operation-run3.log`, fresh world. 1 test, failed at report 1.

Pumps as placed, old (bore-only, default duty) → new:

| pump | sizer duty | discharge, m | rated head, m |
|---|---|---|---|
| reflux | 220.5 m³/h, 38.81 m | 0.20 → 0.20 | 100 → 38.81 |
| pa1 | 585.3 m³/h, 13.20 m | 0.25 → 0.30 | 100 → 13.20 |
| pa2 | 825.3 m³/h, 14.52 m | 0.30 → 0.35 | 100 → 14.52 |
| pa3 | 493.6 m³/h, 14.09 m | 0.25 → 0.25 | 100 → 14.09 |
| P-101 | 730.0 m³/h, 170.98 m | 0.30 → 0.30 | 100 → 170.98 |

- Where the design flow is above the bore's own flow, `withDuty` sets the bore from the duty, so pa1 and pa2 grow a grid
  step. The layout moved one block south from pa3 on: pump z 54 → 55, cooler 53 → 54, E-101 71 → 72.
- The member pumps' heads fall from 100 m to 13 to 39 m. The column did not run, so its effect on the column's own
  numbers is not seen.

P-101 placed: rated flow 0.20279 m³/s (730 m³/h), rated head 170.98 m, shut-off head 206.89 m. The rise at zero flow,
read in the world, is 1754.5 kPa. The line needs 14.5 bar at design (the sizer's 16 bar discharge less 1.5 bar suction).
On the curve at the set flow (0.19357 m³/s, q = 0.955) the pump makes 173.7 m, 14.7 bar.

## Finding 3: the crude island holds on the right curve

- Island 1402 (supply, P-101, the coolers' and E-101's tubes, both heaters, the feed port) HELD at committed tick 1226,
  at the column's start: "Substep refinement exhausted: Newton line search stalled at residual 295.89; active-set pass 0".
- At report 1 (t=1326) the island read SOLVING. Charge flow 0. Drive at 2950 of 2950 rpm (share 1.0), RUNNING. Pump
  suction 150.0 kPa, rise 1754.5 kPa (shut-off). Tower feed valve `SOLVING / CLOSED`. Column LIMITED, FEED_SHORT, 0 %.
- Run 2 (100 m curve, 11.8 bar at shut-off) held the same way (residual 305.46, committed tick 1206). Run 1 (10 bar
  supply, same 100 m curve) did not hold.
- What changed between the run that solves and the two that hold is the suction pressure, 10 bar → 1.5 bar, not the
  pump head.
- At 1.5 bar the sizer's NPSH margin over the world's suction limit (the crude's gas zone edge) is 2.56 m (about
  0.22 bar) at design flow. The tower's feed valve asks up to twice its rate at the start. At twice the flow the suction
  line's drop is four times larger and NPSH3 grows. Whether the suction crosses the gas edge in that step is not traced:
  one replay only.
- Separate finding, not to fix here. A pump too weak to fill its line (run 2) gave a HOLD ("Newton line search stalled
  at residual 305.46, active-set pass 0"), not a low or zero flow. A hold is only for a capability limit.

## Option P, form B: a Size-all centrifugal pump is built with its sized duty (owner: "Yes, fix it")

The main thread's call was form B. Form A (rate at 1.2 × the design flow) was dropped: the duty would move the bore and
override a player's saved manual discharge.

- **`PumpSizing.built(Size, Duty)`** and `built(SizedUnit)`:
  - The shape is `withDuty(MachineDuty.pump(pumpFlow(d), H_r, poles))`.
  - H_r is the rated head for which the pump's own curve (`CentrifugalPump`, the law `MachineRatings.machineControl`
    builds) gives the design head at the design flow: H_r = H_d / head(Q_d) of a 1 m curve.
  - The poles are the pump design's default for the bore.
  - With no duty, or a design point outside `PumpDesign.checkDuty` (5 to 300 m, 1 to 3053 m³/h), it gives the bore-only
    shape; the PUMP_HEAD and PUMP_FLOW rows say why.
  - `PumpSizing.Size` and its saved form are unchanged.
- **Auto:** `Selection.smallest(Selection.spareFirst(SIZES, pumpFlow(d), spared(Q)), rate)`, as `autoPlunger`. It takes the
  smallest bore whose rated flow holds the design flow with the 20 % spare (`Limit.AUTO_SPARE`, factor 1.2). A manual
  pick keeps the player's bore. PUMP_RATED keeps its window (70 to 120 %) and its meaning: the design flow over the
  bore's rated flow.
- **Build list:** `SizingPage.buildText`, used by the glass's Build list and the selected unit's requirements.
  - A centrifugal pump with a duty reads "1039 m³/h at 64.6 m, discharge 0.35 m": the rated flow in the client's flow
    unit, the rated head, the bore. These are what the constructor asks for.
  - Key `gui.icheme.flowsheet.sizing.size.pump_duty` (en_us only). `sizing.size.pump` still serves the Units table.
- **Rig:** `ColumnOperationReviewWorld.pump` builds with `PumpSizing.built(unit)`; R's stand-in is gone.
- **`PumpDesign.size(pump(pumpFlow(d), …))` = d on every grid size:** asserted (`everyPumpBoreIsTheBoreOfItsRatedFlow`).

Renders (`SimulatorRender -Donly=sizing` and `-Donly=sizing-controls`; `GuiPrototypes` does not draw the sizing page),
light and dark:
- `tmp/pump-drive-flow/sizing-render/04-sizing-build.png`
- `04-sizing-build-dark.png`
- `09-build-requirements.png`
- `09-build-requirements-dark.png`

The longest line, "1039 m³/h at 64.6 m, discharge 0.35 m", fits the Size column with no cut.

Moved references:

| reference | old | new | why |
|---|---|---|---|
| crude sample P-101 (`CrudeUnitSampleTest.p101`) | 0.30 m, PUMP_RATED 96 % | 0.35 m, 70.3 % | 730 × 1.2 = 876 m³/h > 763 |
| crude sample PA 1 pump | 0.25 m, 109 % | 0.30 m, 75.7 % | spare |
| crude sample PA 2 pump | 0.30 m, 107 % | 0.35 m, 78.6 % | spare |
| crude sample PA 3, reflux, P-102 | 0.25 / 0.20 / 0.15 m | unchanged | their bores hold the spare |
| `UnitSizingTest` 731 m³/h at 71 m | Auto 0.30 m | 0.35 m | spare |

Review world, worked from run 3's sizer duties (not rerun, by instruction):

| pump | sizer duty | old bore | new bore |
|---|---|---|---|
| reflux | 220.5 m³/h at 38.8 m | 0.20 m | 0.20 m |
| PA 1 | 585.3 m³/h at 13.2 m | 0.25 m | 0.30 m |
| PA 2 | 825.3 m³/h at 14.5 m | 0.30 m | 0.35 m |
| PA 3 | 493.6 m³/h at 14.1 m | 0.25 m | 0.30 m |
| P-101 | 730 m³/h at 171 m | 0.30 m | 0.35 m |

- P-101 at 0.35 m: rated 1039 m³/h at 156.3 m, shut-off 189.2 m (16.0 bar), against the line's 14.5 bar.
- No sample or review-world pump lands outside 5 to 300 m.
- PUMP_RATED: after B the row still reads design over the bore's flow, and Auto's spare keeps it at or under 83 %.

Other kinds (no change):
- **Motor:** the sizer has no motor unit. `Duty.motor` is a reading shown on the column door's calculator; the player
  places a motor in the constructor. Not the same gap.
- **Plunger and vacuum pumps:** their shapes are catalogue models (the model sets capacity and law). They have no
  flow-and-head design point to lose.

Tests: `affected.mjs select --since c0a5eff0e`, 383 filters, without LanguageFilesTest and UnitTextTest.
- test 1387, 1 failure: `SizingPageTest.theDoorFitsInEveryViewForEveryItemInChinese`, missing zh keys
  `sizing.demister`, `demister_hint` from the base branch. Owed to the translation commit, which also takes
  `size.pump_duty`.
- holdGates 49, 2 failures: the moved CrudeUnitSampleTest references, fixed, then CrudeUnitSampleTest 40 of 40.
- columnGates 5 of 5. equipmentGates 76 of 76.
- `compileFluidGameTestJava` passes. The fluid GameTest step and the review world were not run.

## The rig's charge check after the start-hold fixes (07ae5a351 merged)

Correction to package 1's check (main thread):
- The pump stands dead-headed until the column's start, so a check at report 1 saw about one drive decision.
- `chargeCheck` now remembers the first report that sees the charge pump's flow (`chargeFlowing`). It checks once, at
  the first report at least `CHARGE_TICKS` = 800 online ticks later, and logs that it did.
- The assertion is unchanged: within 2 %, speed below top.
- A run that ends before that report fails: "the charge pump's check was never reached".

One replay, fresh world, `tmp/pump-drive-flow/review-operation-run4.log`: **passed** ("All 4 required tests passed").
- First flow at report 1 (t=1326). The check came at report 3 (t=2524, 1198 ticks later): 0.19711 m3/s against
  0.19357 (+1.8 %), 1726 of 2950 rpm (share 0.585), RUNNING.
- The reports ended at 13 (t=8514), on three solved reports in a row.

| t | flow m3/s | share | FEED0 valve | column |
|---|---|---|---|---|
| 1326 | 0.24411 | 0.800 | OPEN | LIMITED FEED_SHORT |
| 1925 | 0.19134 | 0.582 | OPEN | RUNNING, PASSING, refused |
| 2524 | 0.19711 | 0.585 | OPEN | UPSET, CLOSURE |
| 3123 | 0 | 0.987 | CLOSED | RUNNING, solved |
| 3722 | 0.21453 | 0.999 | REGULATING | LIMITED PRODUCT_FULL |
| 4321 | 0.20117 | 0.562 | OPEN | RUNNING, solved |
| 4920 | 0.19249 | 0.617 | OPEN | LIMITED COOLING_SHORT, PUMPAROUND_SHORT |
| 5519 to 6717 | 0.16317 (held) | 0.865 | REGULATING (island held) | LIMITED, then RUNNING |
| 7316 | 0.19573 | 0.578 | OPEN | RUNNING, solved |
| 7915 | 0.03052 | 1.000 | REGULATING | RUNNING, solved |
| 8514 | 0.06653 | 1.000 | REGULATING | RUNNING, solved |

- The drive holds the set flow while the tower's feed valve stands open.
- When the feed valve regulates or closes (its account full, `inletPlan`), the flow is the valve's, and the drive runs
  up to its top speed against it (t=3123, 7915, 8514). This is the expected series behaviour, not a hold: the downstream
  valve wins and the speed loop saturates.
- At the last report: column RUNNING on a solved table; feed 2557.2 kmol/h at 614.9 K (feed account 1.36 of its aim);
  bottoms 810.5 kmol/h.
- Heaters at the last report:
  - Heater 2: radiant outlet 634.2 K at 20.5 MW, "DUTY INSUFFICIENT: outlet 3.9 K below the set point" (set point about
    638.1 K).
  - Heater 1: 643.5 K at 0 MW, on the low flow.
- Holds: the crude island 1402 "HELD: round deadline" at committed tick 5364 (reports 8 to 10 read the held values),
  and once more at 6877. Islands 1418 (5805) and 1412 (7284) once each. No other HELD.
- NPSH: the sizer's margin over the world's suction limit is 2.56 m at 730 m3/h. The held flow (0.197 m3/s, 709 m3/h)
  is under the design flow, so the margin is not less. No world NPSH reading.
