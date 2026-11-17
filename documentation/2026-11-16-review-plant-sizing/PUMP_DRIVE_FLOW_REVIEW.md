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
