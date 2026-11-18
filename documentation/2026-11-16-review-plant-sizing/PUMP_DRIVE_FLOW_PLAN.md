# A pump's flow held by its drive: plan (2026-11-17)

Owner, on the series-valve limit the built-in valve met: "use drive regulation instead"; then, asked whether the rig
and the DCS use the drive's flow regulation with no built-in pump valve: "Yes, drive only". This replaces
[PUMP_VALVE_PLAN.md](PUMP_VALVE_PLAN.md), which is withdrawn.

Status: plan. Nothing is built. Lines are at `5c6a35c6c`.

## 1. Expected (physics)

A centrifugal pump on a variable-speed drive follows the affinity laws: flow with speed, head with speed squared,
power with speed cubed. To hold a set flow the drive lowers the speed until the curve at that speed meets the line at
the set flow. Nothing is throttled, so the shaft power is what the line needs. Numbers, `ShaftCouplingTest`'s pump
(10 L/s at 50 m, 2950 rpm; static head 31.92 m, friction about 0): open flow at full speed 17.6 L/s; held at 8.81 L/s
the speed is 0.83 of rated and the shaft power 4.17 kW, against 6.47 kW for the same flow throttled at full speed.

The drive can only lower the flow below what the pump gives at its top speed. A set flow above that is not reached:
the drive stands at its top speed and says so.

## 2. What the code does now

- **The law exists.** A motor whose mode is `ShaftTrain.Mode.REGULATE_FLOW` holds its first machine's
  `FlowControl.Machine.setFlow` (m³/s at suction): `ShaftTrains` 883-893 reads the pump's booked duty
  (`run.measured = sample.flow()`, `run.target = sample.setFlow()`), `ShaftTrain.regulated` (217-226) steps the speed by
  `SpeedControl.regulate` (a secant on the measured flow, else the affinity guess, at most 0.2 of rated per decision),
  and the first decision uses the inversion `Duty.flowSpeed()`. It needs a data link from the motor to the pump
  (`host.linked(d.id())`), else NO DATA LINK; with no set flow, NO DUTY TARGET.
- **Nothing sets it by default.** A placed drive is `ShaftUnit.Settings.DEFAULT` (running, `SPEED`, set point 0). A
  placed pump's `setFlow` is 0.
- **The review rig never used it.** Its pumps get `plant.drive(pump)` (a creative motor) or `plant.motor(pump)`
  (`ColumnOperationReviewWorld` 801, 1015); no pump flow and no drive mode is set. The charge flow is held by a
  hand-made valve after the charge pump (`chargeValve`, `chargeMeters`, `openCrude`, `CHARGE_SHARE = 1.05`).
- **No loop output.** `ControlPlans` plans control valves and heat units (`valvePlans`, `heatPlans`); a pump is a host
  with inputs only.
- **The pump's page writes a set flow nobody chose.** `FluidNetwork.Controls.from` (124) fills the page's flow with
  the law's rated flow, and any Apply writes it into `setFlow` (450). A regulating drive then holds the rated flow.

## 3. Design

**No new solver form.** The pump stays one connection, `MOVER_LAW`. The series-valve hold is never met by a pump.

**The set point is the existing fact**, `Machine.setFlow`. No saved field is added; no format number changes.

**Loop output.** A constructed centrifugal pump's host becomes a flow output of a controller loop. `ControlPlans` gets
`pumpPlans` (host id to set flow, beside `valvePlans`); `apply` overlays it on the slice's graph as `valvePlans` is
(`pipe.control() instanceof FlowControl.Machine m` on a host with a plan: the same `Machine` with the planned
`setFlow`). `ShaftTrains` 773 reads `m.setFlow()` from the solved graph, so the drive follows the plan with no change
in `ShaftTrains`. The saved set flow is a fact and resumes when the loop is off. The loop acts only while the pump's
drive regulates flow; otherwise the output's status says DRIVE NOT REGULATING (and NO DATA LINK as the drive says it).
`ControlLaw` decides the set flow as it decides a flow valve's target (flow loop, level loop), bounded by 0 and the
law's flow at the drive's top speed.

**The drive trims the valve (built 2026-11-17, LOAD_HOLD_REVIEW.md "Built").** Where a flow valve regulates in the
pump's series, the valve holds the flow and the drive lowers its speed until the valve keeps 5 % of the pump's rise
(`SpeedControl.VALVE_TRIM_MARGIN`); the set flow is an upper limit, not a second target, and with no regulating
series valve the drive holds it as above. This replaces "the loop acts only while the pump's drive regulates flow"
where they conflict: a loop's set flow on a pump behind a regulating valve bounds the flow from above; the valve's
target decides it.

**DCS.** The pump output is listed as a valve output is: set flow, measured flow, the drive's speed share and status.

**Pump page.** `Controls.from` shows `setFlow` (0 = none, shown as no set flow), never the rated flow. The row says
the flow is held by the drive, and shows the drive's status where it is not regulating.

**Rig.** The charge pump's `setFlow` is the design volume flow times `CHARGE_SHARE`; its drive is set to
`REGULATE_FLOW` and data-linked to the pump (both rig options: a creative motor and a real motor). `chargeValve`,
`chargeMeters` and the valve's part of `openCrude` go; the crude is held back until the heaters are lit by the drive
not running, and `openCrude` starts it. The rig asserts the charge pump's flow within 2 % of the set flow and the
drive's speed below its top at the column's start. Member pumps (reflux, pumparound) are the column's and stay as they
are.

**Sizer.** A sized pump is built with its set flow (design volume flow times 1.05) and its motor in `REGULATE_FLOW`.

**Model.** No change: nothing is added to the pump's drawing.

## 4. Decisions for the owner

| # | Question | Proposal |
|---|---|---|
| D1 | A pump saved with a set flow from its page (the rated flow, written unasked) on a regulating drive. | Leave saved values alone (facts). Only the page stops writing it from now on. |
| D2 | The set flow of a sized pump. | Design volume flow times 1.05 (`CHARGE_SHARE`, owner 2026-11-17). |
| D3 | A set flow above what the top speed gives. | The drive stands at top speed, status SPEED LIMIT; no hold. |

## 5. Packages

All packages touch files the valve sensor selector holds (`runtime/control/**`, `network/FluidNetwork`, the rig), so
they start after `refs/heads/claude/nozzle-join-b7040b` is merged here.

0. **Confirm, no production change.** (a) Whether a creative motor regulates (`ShaftTrains` 883 names `MOTOR` only;
   774-777 set target and measure for any driver; `ShaftTrain.regulated` 220 handles `CREATIVE`), and what a creative
   motor's settings can hold. (b) How the rig makes a data link from a motor to its pump (`ControlService.connectedHosts`,
   the data cable route). (c) What `SpeedControl.regulate` does at its upper bound (D3) and which status it gives.
   Report before package 1 if any of these needs a production change this plan does not name.
1. **Rig.** As section 3. GameTests: the review world's batches that hold the charge line; no other class.
2. **Control and DCS.** `ControlHosts` (the pump as an output), `ControlLaw`, `ControlPlans.pumpPlans` and `apply`,
   `ControlPublication`, the controller's and the DCS's pages by the `gui-creation` skill (sheets first, owner picks).
   Tests in `ControlSystemTest`: a flow loop and a level loop on a pump; the saved set flow resumes when the loop is
   off; a drive in `SPEED` gives DRIVE NOT REGULATING and no plan; no data link, no plan.
3. **Pump page.** `Controls.from`, the PUMP case at 450, `DeviceColumn`'s pump rows; `DeviceColumnTest`.
4. **Sizer.** The built set flow and the motor's mode; the sizer's tests of a built pump.

Test classes for each package come from `node tools/merge-lane/affected.mjs select` after `testClasses`.

## 6. Pitfalls

- `MachineNetwork` 346: in a regulating mode the drive keeps its speed set point; the mode is what changes. Use
  `world.shafts().queue(new ShaftTrains.Configure(id, settings))` in the rig, as `CreateCouplingGameTests` does.
- A column's member pump is held while the column runs; a plan for it must not reach `ColumnPower` or `ColumnStarts`.
  The output is offered only for a pump that is no column member.
- The drive steps once per decision, at most 0.2 of rated: the rig's start must allow about five decisions before it
  asserts the flow.
- Results that move: none with no plan and `setFlow == 0`. The rig's own numbers move (no valve drop on the charge
  line); they are reported old and new.

## 7. Kept from the withdrawn work

On `refs/heads/claude/pump-valve-c779f2` (not merged): the probe (`51e34fc10`), the interim review with the
series-valve hold (`70eda3d80`), the drawn valve (`5fb3a179d`) and the stopped engine work (`9187b8817`). The
series-valve hold (a volume-flow valve in series with a mass-flow valve) is a defect any two player valves can meet;
it is its own task.
