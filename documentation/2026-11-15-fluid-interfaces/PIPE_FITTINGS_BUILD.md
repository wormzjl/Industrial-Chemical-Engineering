# Pipe fittings build notes: orifice plate (W6) and automated shutoff valve (W7)

Batch `2026-11-15-fluid-interfaces`, plan `FLUID_INTERFACES_PLAN.md` (decisions D1 to D4, sections 4.1 and 4.2, packages
W6 and W7 with their share of W8). Built on branch `claude/pipe-fittings-7f3f4b` from `claude/equipment-visual-design-d4e22c`
at `9c777e86e`, beside the threads that build W1 to W5. Format numbers taken: fluid facts schema 27 (26 is the gas
intake's, same batch), accessory facts schema 7. No wire change: `NetworkProtocol.VERSION` stays.

## History

- W6, `f204a8a8d`: the restriction orifice plate. `science/fluid/network/Orifice` (K(β) = (1 − β⁴)(1 − β^1.9)/(C²β⁴),
  C = 0.61, β 0.20 to 0.80 by 0.05), the pipe's minor loss plus K (`PhysicalRegistry.installPipeOrifice`, removal with
  its K), item `orifice_plate` with its ratio on the data component `orifice_settings`, `Instrument.Type.ORIFICE`,
  `Fitting.ratio` (accessory facts schema 7), the pipe block's `SHUTOFF` value `ORIFICE` restored from the store
  (`RegistryRealignment.plate`), `OrificeGeometry`, recipe, lang. Ran: every source set compiled; 14 test classes
  (110 tests): 2 failed and were fixed (the air case's density, below; the missing schema-7 image, copied in); then
  `OrificeTest` 4/4 and `AccessoryStoreCompatTest` 8/8.
- W7, `2af154bd4`: the automated shutoff valve. `ValveSource.ON_OFF`, `ControlValve.onOff()`, item
  `automated_shutoff_valve` (`Type.ACTUATED_VALVE`), install and hand toggle, the on/off loop (D4), the data socket and
  model, fluid facts schema 27 with its corpus image, the accessory image regenerated, FORMATS.md rows. Ran: main and test
  compiled; 22 test classes plus `LineGeometryTest` (276 tests): 3 failed, the two corpus tests asking for their new
  images (copied in) and one assertion of `ActuatedValveTest` (below); then `ActuatedValveTest` 4/4, the two corpus tests
  and `LineGeometryTest` 13/13 passed; `compileFluidGameTestJava` and `compileMcpCompatJava` passed. No existing expected
  value moved.
- W8 share, this commit: code guide rows (`fluid-laws.md`, `instrument-accessories.md`, `process-control.md`,
  `wired-control-power.md`), the drawing scenes `ORIFICE_PLATE`, `ORIFICE_PLATE_SMALL`, `AUTOMATED_SHUTOFF_VALVE`,
  `AUTOMATED_SHUTOFF_VALVE_SMALL` in `tools/equipment-drawings/EngineeringExport.java` (150 mm and 50 mm lines), these
  notes. Renders: `tools/equipment-drawings/update.ps1 -Only <the four>`, views in the worktree's
  `tmp/pipe-fittings/views/` (iso, +X, −Z). No test run: documents and `tools/` only.

## Decisions taken in the build

1. Block states: both fittings are values of the pipe's existing `SHUTOFF` enum (`ORIFICE`, `ACTUATED`), not new
   properties. The pipe already has 221,184 states; a boolean would double them. With the two values it has 331,776.
2. The orifice plate's β is set on the item by a sneak-use step (0.05, wrapping), shown in the tooltip; a use on the
   placed plate reads β and K on the action bar. No dialog, no device page row, so no wire change (open item 1).
3. Accessory facts schema 7 carries both W6 and W7 (one batch, one number); its corpus image was written again at W7.
4. The registry does not refuse a second plate on a pipe: the item checks the block state and the store's `occupied`, as
   for every fitting.
5. The actuated valve's block state is derived from its control (`FluidDeviceBlock.body`, `fitted`: `PRESSURE_CONTROL`
   false), registry first. So it opens no pressure-valve screen, takes no `valve_sensor` and has its own socket
   (`ValveGeometry.actuatedSocket`). `ControlHosts` already took any `ControlValve` as an actuator, `BuiltInInputs` needs
   nothing, the output choices come from the hosts: the only place the plan's grep found was `DataPorts`.
6. The hand toggle answers on the action bar (`opening`, `closing`, or why not). No lever turn, no sound: the valve has
   no moving part.
7. The on/off rule is `ControlSettings.onOff` (public, so the test reaches it); its memory is `ControlLaw.switches`,
   keyed by panel with its output, forgotten with the panel; a first decision starts from the valve's registered mass
   target (the saved position). An on/off loop switches at the cadence like a still or a diverter, so its sensor may be
   on another island (no `NOT SAME PROCESS`), and it returns before the `CHECK FLOW DIRECTION` test: the action's meaning
   is D4's. Any measurement may drive it; an alarm is refused as for every loop.
8. The device menu's valve edit refuses an `ON_OFF` valve, so a crafted packet cannot turn it into a flow controller.
9. Recipe: piston (the actuator), copper ingot (the solenoid), two iron ingots and a pipe.

## What the plan had wrong

- Fluid facts schema 26 is the gas intake's; W7 took 27 (the brief's numbering), so the image is `compat/fluid/schema-27/`
  and carries the orifice pipe and the actuated valve only, not the intake or the hopper.
- `AccessoryFactsCompatTest` does not exist; the accessory corpus test is `AccessoryStoreCompatTest`.
- Section 4.1's air case (5 bar, 20 m/s, head 1.16 kPa) holds at 300 K, 5.81 kg/m³; at 298 K the head is 1.19 kPa. The
  test uses 5.81.
- "`ACTUATED` derived in `FluidDeviceBlock.fittings`": the derivation sits in `body` and `fitted`, which `fittings`
  calls, so the block entity's refresh and the registry realignment take it too.
- The closed-valve rest case needs both vessels above the ambient: a bag vessel below it is slack, and its wall row alone
  keeps the island from resting (`NetworkReduction.atRest`, `WALL_RESOLUTION`). The test uses 160 and 120 kPa.

## Open items

1. A β stepper dialog on the placed plate and a plate group on the pipe's device page: both need a GUI prototype for the
   owner (`gui-creation` skill).
2. A simplification to propose: merge the pipe's fitting properties (`SHUTOFF` 6, `PRESSURE_CONTROL` 2, `INLINE_FILTER`
   2, `INLINE_MOVER` 3: 72 combinations, of which 10 are reachable, as one fitting fits a pipe) into one enum of 10
   values. The pipe's states fall from 331,776 to 46,080. It deletes three properties and the checks that keep them
   exclusive; it risks a blockstate JSON rewrite and needs aliases for the old property names in placed blocks (block
   states are restored from the registry, so no fact is lost).
3. The actuated valve shows its position only on a toggle's action-bar line and on its loop's status; a look-at or
   nameplate line would show it at any time.
4. An in-game check of both fittings, if the owner wants one: the drawings judge the models, the gates the laws.
5. The drawing renders are flat grey (the tool draws no textures): the plate's handle and the actuator's paint are
   texture work in game.
