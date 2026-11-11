# 2026-11-11 gas blanketing

Status: in progress since 2026-11-11. Branch `claude/gas-vent-hold`, cut from `8f91ccd11` (the drum-operation-holds tip, which holds the time-factor batch). Not merged; merge preparation waits for the owner.

Purpose: a gas blanketing valve as an optional accessory on vertical and horizontal drums. It holds the drum's gas pressure at a set value: supply gas in through piping below it, gas out to atmosphere above it. Then the design of a pressure safety valve.

## History

- 2026-11-11 (`0283f41e5`): engine. `GasBlanket` (set pressure, pad plan, vent release); the run into a vessel's `BLANKET` port carries the valve; fluid facts schema 22, state format 47.
- 2026-11-11 (`e2737f3b4`): drum side. Accessory on the first top flange (F1), its inlet the drum's blanket gas port, drawing, the drum screen's Accessory page; accessory facts schema 5; corpus images of fluid facts 22 and accessory facts 5. Lying drums get the F1/F2 reservations.
- 2026-11-11 (`e2bad7cf3`): `BlanketValveWorldTest`; the pad asks for nothing below 1e-6 of the gas room, so the island rests.
- 2026-11-11 (`085e5d3dd`): the pad planned at each solver step (owner: a lag is allowed; 1 s response time) with the forecast of the room the liquid vacates; the supply connection's face in the drum's view (a GameTest server crash); `LevelLoopGameTests` blanketed variant (batch `level-loop-blanket`), tool switches `blanket` and `direct`; accessory page in `GuiPrototypes`.
- 2026-11-11 (`4c3130472`): the valve stands on a nozzle stub at a station clear of nozzles and probes (it stood behind a lying drum's head); drawing scenes; review and the PSV plan.
- 2026-11-11 (`9777378a4`): owner: "this location is not good and it's too big". The valve is a slim nozzle with its inlet on top, no taller than the drum's nozzles; a lying drum's F1 at the rear end of the crown, F2 at the front end; none on a 1.5 m standing drum; the supply-pipe gate; the rig lays its supply line and waits for each ramp edit.
- 2026-11-11: owner: "PSV and Blanketing valve are both accessory, with PSV usable on pipes. PSV set pressure is set in its GUI. PSV on pipe get a standalone GUI and use accessory page when on drum. Start implementing the PSV using a sub-thread". The build runs in a sub-thread on its own branch off this one.
- 2026-11-11 (`c919a0ec9`, branch `claude/pressure-safety-valve-e38517`): PSV engine. The relief run carries `FlowControl.PressureValve`; an open outlet is a stub to a built-in void; `FluidDeviceSpec.relief` and `reliefFace`, fluid facts schema 23 with a corpus image; `ReliefValveWorldTest`.
- 2026-11-11 (`439a22868`): PSV accessory, one item for drums (reservation F2) and pipes (the only fitting); a second fitting per host, accessory facts schema 6 with a corpus image; drawing, ghost, geometry gates.
- 2026-11-11 (`db6a8f34c`): PSV GUI. A group on the drum's Accessory page; a pipe's own valve screen; `Controls.relief`, `FluidView.Relief`; `GuiPrototypes` renders; a `DeviceColumn` gate.
- 2026-11-11 (`aae56cf0a`): PSV tests. Fluid GameTest to a flare (`tools/psv-flare`); ten drums on one header (D5: 4.2 substeps per commit).
- 2026-11-11: owner: "connection should point to side", "outlet should face the user when placed". The outlet leaves the body sideways toward the fitting player, its face kept as a fact; drawing scenes `*_VALVES`; [PSV_REVIEW.md](PSV_REVIEW.md). Found: an undersized flare holds the island (D6).
- 2026-11-11: D6, owner direction "Flare flowrate should vary with backpressure, and increasing backpressure will eventually lead to dysfunctional PSV" (replaces A2, which was built in `d9d0898ec` and deleted). The flare tip is an open line, its bore the run's last section, shut only while solids arrive; a relief run into a flare is one line; X4's limits are status only (flame out above the stable velocity, over capacity above the rating). `ReliefValves` and checkpoint state 48 removed. A 0.1 m tip holds a 5 bar drum at 4.9 bar, the valve wide open; no hold in the step, the ramp or the GameTests.
- 2026-11-11: owner: "Blanketing valve and PSV should be in obvious different groups in GUI". The Accessory page has one framed group per valve ("Blanketing valve": set pressure, drum; "Safety valve"), a gap between them (`DeviceColumn.accessories`), and an accent rule between their sections on the glass; DeviceColumn gate.
- 2026-11-11: owner decision on X4 as status only: asked "Keep the rule that the flame goes out above its stable exit velocity?", the owner answered "Yes, keep it". PSV_REVIEW.md section 4 and the flare batch README record it.
- 2026-11-11: the PSV branch (`claude/pressure-safety-valve-e38517`, tip `905c3f10e`) merged into `claude/gas-vent-hold` on the owner's word ("ok go ahead"); the merged tree is the PSV branch's plus this README, so no test ran again.

## Documents

- [GAS_BLANKET_REVIEW.md](GAS_BLANKET_REVIEW.md): the law, the measurements, the open items.
- [PRESSURE_SAFETY_VALVE_PLAN.md](PRESSURE_SAFETY_VALVE_PLAN.md): the pressure safety valve's design, for the owner.
- [PSV_REVIEW.md](PSV_REVIEW.md): the PSV build, measurements, D6 (the flare follows its back pressure) and its curve.

## Code areas

- `science/fluid/network/GasBlanket` (law), `OpenVessel.releases` and `releasing` (vent), `PassiveIntervalSolver.run` (per-step plan, carried transfers), `NetworkReduction.atRest`, `FlowControl.ValveSource.BLANKET`, `PassiveNetwork.Inventory.blanket`.
- `runtime/fluid`: `FluidDeviceSpec.blanket`, `FluidFacts` (schema 22), `FluidCheckpointCodec` (47), `PhysicalFluidTopology` (`ConnectionRole.BLANKET`, the valved-sink run, tank faces), `FluidWorldAuthority.blanketValve` and `blanketPressure`.
- `runtime/instrument`: `Instrument.Type.BLANKET_VALVE`, `InstrumentHosts.BlanketValve`, `InstrumentService`, `AccessoryFacts` (schema 5).
- `science/equipment`: `BlanketValveGeometry`, `EquipmentInstruments` (lying drum reservations), `ShapeRasterizer.blanketValve`. `client/InstrumentRenderer`. `world/level/block/EquipmentStructure.reregister`.
- GUI: `FluidNetwork.Controls.blanket`, `DeviceColumn.blanketValve`, `FluidDeviceScreen` page `ACCESSORY`.
- PSV: `PhysicalFluidTopology` (`ConnectionRole.RELIEF`, `Relief`, the relief run), `PhysicalRegistry.reliefs`, `FluidWorldAuthority.reliefValve`, `pipeRelief`, `reliefPressure`, `FluidDeviceSpec.relief`, `FluidView.Relief`; `Instrument.Type.SAFETY_VALVE`, `InstrumentHosts.ReliefValve`, `InstrumentService` (`reliefs`); `ReliefValveGeometry`, `ShapeRasterizer.reliefValve`; `DeviceColumn.reliefValve`, `FluidDeviceScreen` (`valveOnly`), `FluidDeviceBlock` (a pipe with a PSV opens). D6: `FlowControl.ControlValve.bare` (an open flare tip), `Flares` (status from the exit velocity and rating), `PhysicalFluidTopology` (the tip bore, one relief line into a flare). Tests: `ReliefValveWorldTest`, `ReliefFlareGameTests`.
- Tests: `GasBlanketTest`, `BlanketValveWorldTest`, `FluidFactsCompatTest`, `AccessoryStoreCompatTest`, `LevelLoopGameTests`.
- Tools: `tools/equipment-drawings` scenes `VERTICAL_BLANKET`, `LENGTHWISE_BLANKET`, `LENGTHWISE_BLANKET_SMALL`, `GAS_BLANKETING_VALVE`; `tools/heat-gui-renders/GuiPrototypes` page `fluid-drum-accessory`; `tools/minecraft-time-delta/level-loop/run.sh` (`LEVEL_LOOP_TAG`, the two switches).

## Open items

- Review section 5: the direct-start hold (with defect 1 of the drum batch), the vent's lag on a fast fill, the item icon, the fifth tab, the in-game check.
- PSV: D6 open (PSV_REVIEW.md section 4): a relief run into a firebox, a blanketed vessel or a set-flow mover keeps two regulators; a relief run straight into a flare has no solids block; the PSV drawings and screens for the owner; no in-game check.
- Owner, 2026-11-11, on the redrawn valve (`9777378a4`): "Yes, keep it". Still to judge: the Accessory page. PSV plan: D4 decided (own accessory); D1, D2, D3, D5 are built as recommended unless the owner overrules.
- Before the merge: final lane, CHANGELOG, version, translation commit (`en_us.json` has new keys), code guide (`fluid-science.md`: GasBlanket and the per-step plan; `fluid-runtime.md`: the BLANKET role; equipment and GUI chapters), with the time-factor and drum batches.
