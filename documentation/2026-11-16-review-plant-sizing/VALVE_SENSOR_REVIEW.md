# Control valve: select a linked sensor (review)

Batch `2026-11-16-review-plant-sizing`, branch `claude/nozzle-join-b7040b`, 2026-11-17. Owner: "Control valve needs to be
connected physically to sensors in order to function", "The control valve needs to select sensor first in order to
function, like other controlling systems". Owner's choices: the valve's screen lists the sensors physically linked to it;
nothing is selected until the player picks; valves that equipment works stay as they are; any linked sensor of the right
kind is selectable; the law holds its own flow or upstream pressure and does not read the sensor's value (approved
simplification). Screen: option A of the sheets (a list of marks in a Sensor group).

## Summary

- **Physics.** A control valve throttles to hold what a transmitter measures. With no measurement it has nothing to
  hold, and it fails open: it passes what the line gives. A transmitter is the valve's only when it is wired to it.
- **Before.** Fitting a probe or a flowmeter on the valve's own mount wrote the valve's law at once (pressure for a probe,
  volume flow for a flowmeter), and any saved law regulated whatever sensor it named, linked or not.
- **Now** (`runtime/control/ValveSignal`):
  - *Linked:* a pressure probe or flowmeter on the valve itself or on a host its data cables reach
    (`ControlService.connectedHosts`). `choices` lists them by label ("FT-0012 / FLOW", the controller box's naming).
  - *No preset:* fitting a sensor writes no law (`InstrumentService.changeSensor`).
  - *Selection:* the screen's Sensor group (None, then each linked sensor) is a draft until Apply. `Controls.sensor`
    carries it; the edit handler refuses a sensor not in `choices` and writes the law with `ValveSignal.select`: a probe
    holds the upstream pressure, a flowmeter the mass or volume flow the Set point's keys choose; None clears the sensor,
    targets kept. Each selection moves the law's revision on.
  - *Slice:* `ControlPlans.apply` takes the loop's plan, else the law where its sensor is linked, else the law bare with
    its revision kept (the valve stands open; its line keeps its identity from slice to slice).
  - *Screen:* `FluidNetwork.ValveData.sensors` (null for a valve that takes no selection) and `NO_SIGNAL`. With no signal
    the message display reads NO SIGNAL (its hover says to select a sensor, the engine's line under it), not again under
    the opening; the valve reads 100 % open; the Set point shows only once a sensor is selected.
  - *Exempt* (`ValveSignal.exempt`): the sources equipment works (`SHAFT_SPEED`, `BURNER`, `FLARE`, `STILL`, `EJECTOR`,
    `ENGINE`, `BLANKET`, `CONVERTER`, `ON_OFF`) and a running column's valves (`PortPlan.COLUMN_SENSOR`): no Sensor
    group, edited as before. A valve a controller loop plans has its signal over the loop's cables (`ValveSignal.signal`,
    `ControlService.planned`).
  - *Readers:* `FluidNetwork.opening` is NaN with no signal; `ExchangerLines` counts a line metered when its law is not
    bare and linked.
- **Removal.** Removing the selected sensor clears the law's sensor by a queued edit (`InstrumentService.release`) on
  every path that removes a sensor: the wrench (`changeSensor`), a broken or recovered host (`recoverSensors`), a mount
  that no longer exists (`recoverInvalidSensors`). It frees the valves on that host and on the hosts its cables reach at
  that moment. A cut data cable, or a host whose control port went before its sensors, leaves the law naming the sensor:
  the valve reads NO SIGNAL and stands open (the predicate), and the signal returns when the link does. No saved law is
  rewritten or discarded otherwise.
- **Saved worlds.** A law the old install preset wrote names the valve's own sensor, which is linked: it regulates as
  before. A law naming a sensor elsewhere with no cable (the review rig's old charge valve) reads NO SIGNAL.
- **Hover.** A choice row's hover stands beside the door column, level with its row (`KitDoor.choice`,
  `FluidDeviceScreen.render`), so it covers none of the list's other choices. This holds for every choice row of a device
  door (a flow valve's mass and volume keys too).
- **Wire.** The fluid menu's JSON gains `Controls.sensor` and `ValveData.sensors`. `NetworkProtocol.VERSION` is not bumped
  here: `icheme-8` is reserved by the solute batch; the version this lands under is the main thread's call at the merge.
  No saved format changes.
- **Not done.** The review rig's charge valve (owner: the charge pump gets a built-in control valve, `PUMP_VALVE_PLAN.md`
  on `claude/column-testing-sizing-26b5b9`); `PUMP_SENSOR` is to be exempted once it exists. No in-game check.

## Files

| File | Change |
|---|---|
| `runtime/control/ValveSignal.java` | new: `exempt`, `linked`, `signal`, `choices`, `slice`, `select`, `released` |
| `runtime/control/ControlPlans.java` | the slice's law through `ValveSignal.slice` |
| `runtime/control/ControlService.java` | `planned(valve)` |
| `runtime/instrument/InstrumentService.java` | `sensors(host)`; no law on install; `release` on the three removal paths |
| `network/FluidNetwork.java` | `Controls.sensor` (`withSensor`), `ValveSensor`, `ValveData.sensors`/`NO_SIGNAL`, `valveData`/`valveState`, the edit handler |
| `runtime/assembly/ExchangerLines.java` | metered: not bare and linked |
| `client/gui/common/DeviceColumn.java` | `SensorChoice`, `sensors`, `SENSOR_KEY`, `SENSOR_NONE` |
| `client/gui/common/kit/KitDoor.java` | `choice(x, y)` |
| `client/gui/screens/inventory/FluidDeviceScreen.java` | the Sensor group, its draft and Apply, NO SIGNAL on the display, the rebuild on new choices, the choice hover |
| `assets/icheme/lang/en_us.json` | 8 lines under `gui.icheme.fluid.` |

## Tests

| Test | What it proves |
|---|---|
| `ValveSignalTest` (7) | the linked rule, the slice's law (bare with its revision kept), exempt valves, a loop's plan, the law a selection writes, the law a removed sensor frees |
| `DeviceColumnTest.aControlValveLeadsWithItsSensorGroup` | the group in four states, editor and reader, its English lines, the fit at 300 to 790 px, boxes in line, no widget moved by readings, the design view with three sensors |
| `ValveSignalGameTests.aValveRegulatesOnlyOnTheSensorThePlayerSelects` | fitting writes no law and the screen lists the sensor with NO SIGNAL; the player's edit selects it and the valve reads an opening; a law on an unlinked sensor reads no opening |

Results: selected classes (test, holdGates, columnGates, equipmentGates) 1457 tests, 1 failure, the expected `SizingPageTest.theDoorFitsInEveryViewForEveryItemInChinese` (translations come with the translation commit); `DeviceColumnTest` 15 of 15 after its design-view check was narrowed to the widgets (the page with three sensors scrolls at the design height: 377 px in a 338 px view at 464); fluid GameTests 117 of 117 (`valve-signal` among them). `LanguageFilesTest` and `UnitTextTest` left out (before the translation commit).
