# A pump's built-in control valve: plan (2026-11-17)

Owner, on the review plant: "The pump should have a built-in control valve to regulate the flow and exposed to DCS,
instead of relying on external valves", then "add it to pump model as well". Choices taken the same day: the drawn
model, the simulator's pump unit and the sizer all get it; the driver's speed regulation stays beside it; constructed
centrifugal pumps only (the in-line pump stays a fitting, the plunger pump keeps its set displacement).

Status: plan. Nothing is built. Lines are at `6ec5f433c`.

## 1. Expected (physics)

A centrifugal pump on a fixed-speed driver runs where its curve meets the line. A control valve on its discharge takes
the head the line does not need, so the flow is the smaller of the set flow and what the curve and the line allow:

- set flow below the open flow: the pump works at the set flow on its curve, head `H(Q_set)`; the valve drops
  `H(Q_set)` less the line's need; shaft power is the curve's at `Q_set`; the throttle adds no work, so the liquid
  leaves with the pump's whole work as enthalpy (pressure rise plus heat);
- set flow above the open flow, or no set flow: the valve stands open and the pump is on its curve against the line.

A drive that also regulates speed lowers the speed until the open flow is the set flow; the valve then stands open.
The valve only ever caps the flow, so the two never fight.

## 2. What the code does now

- `FlowControl.Machine(law, speed, enabled, setFlow, setPressure)` (line 215): the set points are held only by a
  regulating driver, one speed step per decision (`ShaftTrains` 764-777: `run.target`, `run.measured`; `ShaftUnit.Settings`
  mode and set point). On a plain motor nothing holds them. The pump's screen already sets `setFlow` for a constructed
  pump (`network/FluidNetwork`, the PUMP case: `controls.volumeFlow`).
- A running `Machine` is one row, `MOVER_LAW`: `head − rise(Q, s)` (`StepEquations` 1512). A flow valve is
  `VALVE_REGULATING` (`flow − target`, 1519) or `VALVE_OPEN`, chosen by the active set with the rules of
  `ControlConstraints` (`standing`, `moverBounds`) and `DeviceModes` (C3 and B of this batch's valve hold).
- The control system gives a loop no pump output: `ControlLaw` plans control valves and heat units; a mover is a host
  with input channels only (`ControlHosts.inputs`, `BuiltInInputs.inline`). `wired-control-power.md`: "mover demand
  control remains the motor path".
- The review rig put a hand-made control valve after the charge pump (`ColumnOperationReviewWorld`, `chargeValve`).

## 3. Design

**The pump compiles to its curve and a flow valve in series.** Where a constructed centrifugal pump (`Kind.PUMP` with
SUCTION and DISCHARGE ports, a `Machine` whose law is a `CentrifugalPump`) has `setFlow > 0`, the graph gets, in place of
its one connection: the mover connection as now, an internal junction, and a connection carrying
`ControlValve(VOLUME_FLOW, setFlow, PUMP_SENSOR)` to the discharge node, of the discharge's bore and no length. With
`setFlow == 0` the graph is today's, byte for byte.

Why this form: every rule a flow valve already has applies with no new solver mode (the active set, "lower target
wins" with a valve further down the line, the starved start, C3 and B). The mover connection keeps its law, its power
(`InlineMovers.shaftPower` on its own head) and its energy booking, so conservation needs no new term: throttling adds
no work (`fluid-laws.md`, Valves). Ruled out: a new `MOVER_THROTTLED` row on the mover's connection (a new mode, and the
power and the enthalpy would have to be restated on the curve's head rather than the connection's).

`PUMP_SENSOR` is an equipment sentinel like `PortPlan.COLUMN_SENSOR` and `BLANKET_SENSOR`: the pump measures its own
flow, so nothing is selected or cabled (the valve sensor selector exempts it by its source list or by this sentinel).

**The set point is the existing fact.** `Machine.setFlow` keeps its unit (m³/s at suction) and its meaning (the flow
the pump holds). No saved field is added; no format number changes. A regulating drive reads the same number.

**Control system.** A constructed pump's host becomes a loop output: the plan is the pump's `Machine` with another
`setFlow` for the slice (`ControlPlans`, beside `valvePlans`; the saved set point resumes when the loop is off), decided
by `ControlLaw` as a valve's flow or level loop is. The DCS lists it as it lists a valve output and shows its set point,
its flow and its valve's opening.

**Shown.** The pump's page: the set flow's row says it is held by the pump's valve; an Opening meter (the valve
connection's opening, as a control valve's) and the status NO SET FLOW / REGULATING / OPEN. The drawn model: a control
valve body with its actuator on the discharge nozzle (read the `models-and-sfx` skill first).

**Simulator, sizer, rig.** A sized pump is built with its set flow; the rig's charge valve and its flowmeter go, and the
charge pump holds the charge flow itself.

## 4. Decisions for the owner

| # | Question | Proposal |
|---|---|---|
| D1 | A pump saved with a set flow on a plain motor never held it; with this change it throttles to it at the next slice. | Accept: it is what the number on its screen says. Package 0 counts how a placed pump's `setFlow` defaults; if a new pump defaults to a non-zero set flow, this comes back as options before anything is built. |
| D2 | The set flow a sized pump is built with. | The stream's design volume flow times 1.05, the share the owner set for the charge valve on 2026-11-17 (`CHARGE_SHARE`). |
| D3 | Set discharge pressure (`setPressure`) on the built-in valve. | Not now: the valve holds flow only; pressure stays the drive's. |

## 5. Packages

Package 0 is reading and one probe; it reports before any production change.

0. **Confirm the form.** (a) Where a `Kind.PUMP` device with ports becomes a connection (`PhysicalFluidTopology` compile,
   `TopologyCompiler`), and how a second connection and an internal junction get stable identities (as a heat passage's
   connection does, `pipe.id() − Long.MIN_VALUE/2`); what reads a pump's connection by its device id (`ShaftTrains`
   764-777, `FluidView`, `ColumnPower`, `MachineHealth`, `MoverSuction`, NPSH) and must keep reading the mover's. (b) A
   placed pump's default `setFlow`, and how many saved fixtures hold a non-zero one on a plain motor (D1). (c) A probe in
   `tools/pump-valve/` on the valve-hold line (`tools/valve-hold/SeriesHoldScratchTest`: supply 200 kPa, pump 20 L/s at
   50 m, open flow 51.0 kg/s): the two-connection form built by hand, targets 3, 10, 30, 50, 60 kg/s, fresh and
   carried; expected: regulates at each target up to 50, stands open at 51.0 for 60, no hold; shaft power at 10 kg/s
   equal to the curve's at 10 L/s within 1e-9 relative; the island's energy balance closed to the solver's tolerance.
1. **Engine.** The expansion in the compile, `PUMP_SENSOR`, the view's opening and status. Tests: the probe's cases as
   `PumpValveTest`; `setFlow == 0` graphs identical to before (a fixture compared connection by connection); a pump with
   a flow valve further down the line (lower target wins, either order); a regulating drive and the valve together
   (speed falls, the valve opens, no oscillation over 20 decisions); every existing reference exact.
2. **Control.** The pump as a loop output and on the DCS (`ControlHosts`, `ControlLaw`, `ControlPlans`,
   `ControlPublication`, the controller's and the DCS's pages by the `gui-creation` skill, sheets first). Tests in
   `ControlSystemTest`: a flow loop and a level loop on a pump; the saved set point resumes when the loop is off; no
   data link, no plan.
3. **Model.** The valve body and actuator on the discharge nozzle; software renders for the owner; no in-game check.
4. **Simulator, sizer, rig.** The built set flow (D2); the simulator's pump unit says its flow is held; the rig drops
   `chargeValve`, `chargeMeters` and the valve's part of `openCrude`, sets the charge pump's set flow and asserts the
   pump's status is REGULATING at the column's start.

Order and files: packages 0, 1 and 3 touch `science/fluid/**`, `runtime/fluid/**`, `science/equipment/**` and can run
beside the valve sensor selector (which holds `runtime/control/**`, `network/FluidNetwork`, `DeviceColumn`, the rig).
Packages 2 and 4 touch the selector's files and start after it is merged. The selector's rig step is dropped: the charge
loop it would rebuild no longer exists.

## 6. Pitfalls

- The internal junction is a junction, not stock (D46): it holds nothing, and its relaxation buffer must not delay the
  pump's NPSH or suction reads.
- `ControlConstraints.moverBounds` skips `Machine` movers; the pump's own valve needs no bound from its own pump.
- The valve hold of this batch (M2: a pump on its curve and a valve that regulates again) is exactly this pair; C3 and
  B are what make it solve. A hold in the probe is a finding to report, not something to work around in the compile.
- A column's member pump (reflux, pumparound) is held while the column runs: its connection is out of the islands, and
  the column books its power. The expansion must not change what `ColumnPower` and `ColumnStarts` find.
- Results that move: none are expected with `setFlow == 0`. Any moved reference is reported with old and new values.
