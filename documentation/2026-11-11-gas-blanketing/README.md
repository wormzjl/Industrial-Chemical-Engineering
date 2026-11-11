# 2026-11-11 gas blanketing

Status: in progress since 2026-11-11. Branch `claude/gas-vent-hold`, cut from `8f91ccd11` (the drum-operation-holds tip, which holds the time-factor batch). Not merged; merge preparation waits for the owner.

Purpose: a gas blanketing valve as an optional accessory on vertical and horizontal drums. It holds the drum's gas pressure at a set value: supply gas in through piping below it, gas out to atmosphere above it. Then the design of a pressure safety valve.

## History

- 2026-11-11 (`0283f41e5`): engine. `GasBlanket` (set pressure, pad plan, vent release); the run into a vessel's `BLANKET` port carries the valve; fluid facts schema 22, state format 47.
- 2026-11-11 (`e2737f3b4`): drum side. Accessory on the first top flange (F1), its inlet the drum's blanket gas port, drawing, the drum screen's Accessory page; accessory facts schema 5; corpus images of fluid facts 22 and accessory facts 5. Lying drums get the F1/F2 reservations.
- 2026-11-11 (`e2bad7cf3`): `BlanketValveWorldTest`; the pad asks for nothing below 1e-6 of the gas room, so the island rests.
- 2026-11-11 (`085e5d3dd`): the pad planned at each solver step (owner: a lag is allowed; 1 s response time) with the forecast of the room the liquid vacates; the supply connection's face in the drum's view (a GameTest server crash); `LevelLoopGameTests` blanketed variant (batch `level-loop-blanket`), tool switches `blanket` and `direct`; accessory page in `GuiPrototypes`.
- 2026-11-11 (`4c3130472`): the valve stands on a nozzle stub at a station clear of nozzles and probes (it stood behind a lying drum's head); drawing scenes; review and the PSV plan.
- 2026-11-11 (HASH): owner: "this location is not good and it's too big". The valve is a slim nozzle with its inlet on top, no taller than the drum's nozzles; a lying drum's F1 at the rear end of the crown, F2 at the front end; none on a 1.5 m standing drum; the supply-pipe gate; the rig lays its supply line and waits for each ramp edit.
- 2026-11-11: owner: "PSV and Blanketing valve are both accessory, with PSV usable on pipes. PSV set pressure is set in its GUI. PSV on pipe get a standalone GUI and use accessory page when on drum. Start implementing the PSV using a sub-thread". The build runs in a sub-thread on its own branch off this one.

## Documents

- [GAS_BLANKET_REVIEW.md](GAS_BLANKET_REVIEW.md): the law, the measurements, the open items.
- [PRESSURE_SAFETY_VALVE_PLAN.md](PRESSURE_SAFETY_VALVE_PLAN.md): the pressure safety valve's design, for the owner.

## Code areas

- `science/fluid/network/GasBlanket` (law), `OpenVessel.releases` and `releasing` (vent), `PassiveIntervalSolver.run` (per-step plan, carried transfers), `NetworkReduction.atRest`, `FlowControl.ValveSource.BLANKET`, `PassiveNetwork.Inventory.blanket`.
- `runtime/fluid`: `FluidDeviceSpec.blanket`, `FluidFacts` (schema 22), `FluidCheckpointCodec` (47), `PhysicalFluidTopology` (`ConnectionRole.BLANKET`, the valved-sink run, tank faces), `FluidWorldAuthority.blanketValve` and `blanketPressure`.
- `runtime/instrument`: `Instrument.Type.BLANKET_VALVE`, `InstrumentHosts.BlanketValve`, `InstrumentService`, `AccessoryFacts` (schema 5).
- `science/equipment`: `BlanketValveGeometry`, `EquipmentInstruments` (lying drum reservations), `ShapeRasterizer.blanketValve`. `client/InstrumentRenderer`. `world/level/block/EquipmentStructure.reregister`.
- GUI: `FluidNetwork.Controls.blanket`, `DeviceColumn.blanketValve`, `FluidDeviceScreen` page `ACCESSORY`.
- Tests: `GasBlanketTest`, `BlanketValveWorldTest`, `FluidFactsCompatTest`, `AccessoryStoreCompatTest`, `LevelLoopGameTests`.
- Tools: `tools/equipment-drawings` scenes `VERTICAL_BLANKET`, `LENGTHWISE_BLANKET`, `LENGTHWISE_BLANKET_SMALL`, `GAS_BLANKETING_VALVE`; `tools/heat-gui-renders/GuiPrototypes` page `fluid-drum-accessory`; `tools/minecraft-time-delta/level-loop/run.sh` (`LEVEL_LOOP_TAG`, the two switches).

## Open items

- Review section 5: the direct-start hold (with defect 1 of the drum batch), the vent's lag on a fast fill, the item icon, the fifth tab, the in-game check.
- Owner: judge the valve's drawings and the Accessory page. PSV plan: D4 decided (own accessory); D1, D2, D3, D5 are built as recommended unless the owner overrules.
- Before the merge: final lane, CHANGELOG, version, translation commit (`en_us.json` has new keys), code guide (`fluid-science.md`: GasBlanket and the per-step plan; `fluid-runtime.md`: the BLANKET role; equipment and GUI chapters), with the time-factor and drum batches.
