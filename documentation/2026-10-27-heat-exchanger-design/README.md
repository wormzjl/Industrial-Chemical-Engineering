# 2026-10-27-heat-exchanger-design

Purpose: Owner request 2026-10-27: design a heat exchanger system:

- a UA that scales with each unit's size;
- built-in control that keeps the fluid solver converging;
- a view on input/output buffers that decouple the networks on both sides with a relayed pressure.

Status: In progress since 2026-10-27. W1 (the heat law), W2 (the device), W5 (the air cooler), W6 (the fired heaters), W3 (the two-sided coupling, form C), W4 (the built-in control), W7 (the kettle) and W8 (the exchanger assembly) are built; study W3a is done. W9 (the column hook) is deferred until the column's operation stage exists. The owner's review world is ready ([REVIEW_WORLD.md](REVIEW_WORLD.md)); the owner's test findings are in [OWNER_TEST_REVIEW.md](OWNER_TEST_REVIEW.md): the air cooler, exchanger and heater-hold fixes are built; the water steam virial and the GUI are merged here; the furnace convection model F1 is built. Main 0.16.2 (the old one-block pump and compressor removed) is merged in; the review world's heater pump is an In-line Pump. The owner answered Q1 to Q16 (Q16 form C; Q15 no curve-span notice). Index: [../INDEX.md](../INDEX.md).

## Documents

- [REVIEW_WORLD.md](REVIEW_WORLD.md): the owner's test world, five units under control, and what to check.
- [OWNER_TEST_REVIEW.md](OWNER_TEST_REVIEW.md): the owner's findings from that world, each as expected, found, proposed; [HEAT_GUI_MOCKUP.svg](HEAT_GUI_MOCKUP.svg), the Control tab and stream switch for approval.
- [HEAT_EXCHANGER_PLAN.md](HEAT_EXCHANGER_PLAN.md), revision 4, with W1, W2, W5, W6, W3a, W3, W4, W7 and W8 as built and W9 deferred (section 10). It covers:
  - the physics in numbers and the owner's decisions;
  - UA from size with a film per phase and the approximations that keep it free of lag;
  - the air cooler (air at 25 °C) and the fired heater (heat to a set temperature, capped at its duty);
  - the coupling of the two sides (a relay of the heat in transit between islands; the wall holds no heat);
  - built-in control and the exchanger assembly with real lines and valves (owner Q7);
  - saves, work packages W1 to W9, simplifications and risks.
- [HX_COUPLING_STUDY_REVIEW.md](HX_COUPLING_STUDY_REVIEW.md): the first offline study: cell count, the drawn 1-2 shell against counterflow, four coupling schemes across equal and unequal slices, holds, and the controller.
- [HX_FILMS_STUDY_REVIEW.md](HX_FILMS_STUDY_REVIEW.md): the revision 2 study: films per phase, the physical and the coupling lag, three coupling forms, and the stability map behind the joint steady wall.
- [HX_ZERO_WALL_STUDY_REVIEW.md](HX_ZERO_WALL_STUDY_REVIEW.md): the revision 3 study: the wall without heat, the relay planned at dispatch, three give-back splits, holds, and the controller.
- [HX_TIMING_STUDY_REVIEW.md](HX_TIMING_STUDY_REVIEW.md): study W3a, how a side reads its partner on the game's clock: forms A, B and C (harness part 8); recommends C, the fence (Q16).
- [HEAT_EXCHANGER_PLAN_REVIEW.md](HEAT_EXCHANGER_PLAN_REVIEW.md): the review of revision 3 against the code (a Fable subagent, read-only; 22 findings, taken into revision 4).
- [HEAT_GUI_PLAN.md](HEAT_GUI_PLAN.md): the mockup built: the Control tab, the stream switch, a pipe's temperature (fluid-16), the owner options Q-G1 to Q-G4 and the as-built notes.

## Code areas

Changed by W1 (`science/fluid/network/`):

- `HeatCurve` (new): a stream's enthalpy-temperature curve with a film per knot, sampled from the engine, and the exact march along it;
- `HeatSide` (new): a side's area, films and 8-cell wall, bypass and give-back, and its heat;
- `PassiveNetwork.Pipe.heat` and its identity's shape;
- `PassiveStepSolver`: the heat in `Equations.nodeAccumulate`, the curves per connection end (`sideCurve`), and the delivered stream in `BalanceNet.seeded`, the junction start mix and `arrivingPhases`;
- `ConservativeTransport`: the `HEAT` boundary kind and the `EdgeHeat` hook of the reconstruction.

Changed by W2:

- `science/equipment/HeatSides` (new): a unit's sides, their nozzle roles, surfaces and passages; `ShapeRasterizer.fluidCells` (a node per side); `EquipmentShape.fluidDevice`;
- `PipeResistance.Geometry.channels` and the sections' merge key in `PassiveNetwork`;
- `TopologyCompiler.Kind.HEAT_SIDE`; `PhysicalFluidTopology` (the `INLET` and `OUTLET` roles, `Device.heat`, `sideIdentity`, the side compiled as an inline filter is);
- `runtime/fluid/HeatUnit` (new): the pair record; `FluidFacts` (schema 5), `FluidCheckpointCodec` (state format 23), `IslandCertificate` (graph identity tag -3);
- `FluidWorldAuthority` (`placeUnit`, `removeUnit`, the side's view); `EquipmentStructure` (register, restore, restate and remove a unit's sides together);
- the screen: `EquipmentShellBlockEntity.open` (the side's title), `FluidDeviceScreen` (the "Other side" button), `FluidNetwork` (`PartnerPayload`; a side's edit refused).

Changed by W5:

- `HeatSides`: the air cooler's side, passage, air films (`airFilms`), fan power and the side's heat law (`heatSide`); `AirCoolerDesign` (the datasheet's air-side line);
- `HeatUnit`: the manual share (bypass or fans), `Mode.BYPASS` removed, `heatSide`; `PhysicalFluidTopology` (the side's pipe carries it; `Device.withGeneratorEnabled`);
- `FluidCheckpointCodec` (state format 24: a connection's heat side);
- `FluidView.Heat` and the authority's view; `FluidNetwork` (the fans as power, the edit through withers); `FluidDeviceScreen` (fans button, heat readings).

Changed by W6:

- `science/fluid/network`: `HeatLaw` (new, sealed: `HeatSide` or `FiredCoil`), `FiredCoil` (new: the set-point law and its planned firing share), `HeatCurve.enthalpyAt`; `PassiveNetwork.Pipe.heat` typed `HeatLaw`; `PassiveStepSolver` (`sideCurve` keyed on the law's span and films, `fire`); `PassiveIntervalSolver.run` (plans the firing at each step's start);
- `HeatSides`: the heaters' coils, areas, passages, `coilDuty` and their heat law; `HeatUnit` (`FIRING` default, `burners`, a heater coil needs a set point); `FluidDomain.requireInputTemperature`;
- `FluidCheckpointCodec` (state format 25: the tagged heat law), `IslandCertificate` (graph identity tag -4);
- `FluidWorldAuthority` (an edit moves the partner coil; the DUTY INSUFFICIENT status; the coil's view), `FluidView.Heat` (burners, set point, most duty), `FluidNetwork` (the burners as power, the set point as temperature), `FluidDeviceScreen` and `en_us.json`.

Changed by W3:

- `science/fluid/network`: `ExchangerRelay` (new: the 8-cell quasi-steady wall on two curves, the capacity-rate share and the plan of a side's wall, give-back and active share); `HeatSide` (the partner's connection id and the active share); `HeatCurve.temperatureSlope`; `PassiveStepSolver.couple`; `PassiveIntervalSolver.solve` and `RetainedSolver.solve` (the reads);
- `science/equipment/HeatSides`: an exchanger's side law, 8 cells with its partner's identity;
- `runtime/fluid`: `IslandCoordinator` (the fence in `reconsider`, `verify` and the pump; the reads at dispatch; the booking at commit and replay; the records, transits and wake queue), `ProcessSolveServices.FluidIslandCommand` (the reads), `HeatUnit.withTransit`, `WorldTopologyLedger` (`heatTransits`, `withHeatTransits`, `removedHeat`), `FluidWorldAuthority` (the transit loaded, captured and booked at removal), `FluidCheckpointCodec` (state format 26), `IslandCertificate` (graph identity tag -5), `FluidRuntimeDiagnostics.couplingWakes`.

Changed by W4:

- `science/fluid/network`: `HeatControl` (new: the controller's targets, low limit, manual effort and its setting), `AirCooled` (new: the air cooler's law, its fans the actuator, its air's films), `HeatSide` (the controller and its planned limit), `HeatLaw` (permits `AirCooled`), `HeatCurve` (the knots' vapour fractions, `withFilms`, `lowestEnthalpyAt`), `ExchangerRelay.plan` (the bypass and the wall in turn), `PassiveStepSolver.plan` (W6's `fire`: firing and fans per step; the curve cache re-films);
- `science/equipment/HeatSides` (`AIR`; `airFilms` reads it; `heatSide` takes the controller);
- `runtime/fluid`: `HeatUnit` (`Mode.FAN` removed, `controller`, `controllable`), `FluidCheckpointCodec` (state format 27), `IslandCertificate` (graph identity 6), `IslandCoordinator` (a moved bypass wakes), `FluidView.Heat.bypass`, `FluidWorldAuthority` (`controlStatus`, the planned fans and bypass in the view);
- `network/FluidNetwork` (`Controls.heat`, the side's edit), `FluidDeviceScreen` (the control's rail and readings), `en_us.json`.

Changed by W7:

- `science/fluid/network`: `Pool` (new: the kettle's pool on its vessel's inventory, its wetted share, film and heat), `PassiveNetwork.Inventory.pool`, `ExchangerRelay` (`Partner.pool`, `Bath`, `planPool`, the wall against a bath), `PassiveStepSolver` (`poolHeat`, `boilUp` in the phase draw, the pool's plan), `ConservativeTransport` (the pool's `HEAT` boundary), `NetworkReduction.atRest`, `VesselGeometry.segmentArea`;
- `science/equipment`: `HeatSides` (the kettle's tubes, `kettleShell`, `kettleBundle`, `pool`), `EquipmentShape` (the kettle's volume and geometry), `ProcessUnit.kettle` (the pool's node), `ShapeRasterizer` (the pool's cell);
- `runtime/fluid`: `HeatUnit` (`KETTLE`, `pool`), `PhysicalFluidTopology` (the shell's pool compiled), `IslandCoordinator` (the pool as a coupled side), `FluidCheckpointCodec` (state format 28), `IslandCertificate` (graph identity 7), `FluidWorldAuthority` (`UnitVessel`, the shell's view);
- `world/level/block/EquipmentStructure` (the shell registered, restored and restated with the tubes).

Changed by W8:

- `science/assembly`: `ExchangerBlueprint` (new: the design per side, the slots and links), `PortGraph.Fitting.FLOWMETER`, `AssemblyMatcher` (flowmeters passed over, the tee scoring of a nested tee);
- `runtime/assembly`: `ExchangerLines` (new: the design from the sides' controls, the report published as each side's lines), `AssemblyService` (validate an exchanger, `metered`, `redesigned`, publish on revert, removal and chunk load), `LevelGround` (a metered pipe);
- `science/fluid/network`: `ExchangerRelay.Operation` and `Read.operation`, `PassiveStepSolver.couple` (the valve's planned target, `VALVE_DEADBAND`), `PassiveNetwork.Pipe.withControl`;
- `runtime/fluid`: `HeatUnit` (`Mode.RATIO`, `lines`), `IslandCoordinator` (`operations`, `sameControl`), `FluidWorldAuthority` (`Lines`, the held valves, `operate`, the lines' status), `PhysicalRegistry.flowmeterOn`; `runtime/instrument/InstrumentService` (a flowmeter installed or removed);
- `world/level/block/EquipmentStructure.sideIdentities`; `network/FluidNetwork` (`LinesPayload`), `FluidDeviceScreen` (the ratio mode and field, Check lines), `en_us.json`.

Changed by the heat GUI task (branch `claude/heat-gui-redesign-abb1db`):

- `runtime/fluid`: `FluidView` (`Heat.actuator`, `effort`, `limit`, `ports`, `lines`; the records `Port`, `Lines`, `Part`; `PipeInfo.reading`), `HeatPresentation` (new: a unit's ports, actuator and lines, read where the side's view is built), `FluidWorldAuthority` (`Lines.slots`, two lines in `view`, `lineReading` and its memory of a pipe's last flowing reading);
- `runtime/assembly/ExchangerLines.publish` (passes the report's slots); `network/FluidNetwork` (fluid-16; a port's phase axis checked);
- `client/gui/common/HeatDiagram` (new: each unit's flowsheet on a design grid), `client/gui/screens/inventory/HeatFlowsheet` (new: draws it), `FluidDeviceScreen` (the Control tab, the stream switch, a pipe's temperature and pressure, the statuses in words; the rail's control rows removed), `AssemblyScreen.stateText` (the hover text shared);
- `en_us.json` (`heat_ratio_input` and the Control tab's lines).

The plan also touches:

- `science/fluid/network/PassiveStepSolver` (`Equations.nodeAccumulate`, `BalanceNet`, `arrivingPhases`);
- `ConservativeTransport` (beside `pumpWork`);
- `PassiveNetwork.Pipe`;
- the restored `PipeHeatExchange` (`tools/pipe-thermal-loss/prototype.patch`);
- `runtime/fluid/IslandCoordinator` (command inputs, dispatch wait, commit, wake);
- `FluidFacts` (facts schema 5);
- `FluidWorldAuthority` (exchanger registration);
- `science/equipment/ProcessUnit` (`tubeSurface`, `bundleFace`, `heaterDuty`);
- `science/assembly` and `runtime/assembly` (an exchanger blueprint, a FLOWMETER element);
- `FlowControl.ControlValve` (targets as slice inputs);
- the exchanger's screen.

## History

- 2026-10-27: design written on worktree `research-folder-reorganize-b8443b` (branch `claude/heat-exchanger-design-cf05d1`, base `995dbd9c`, 0.15.0), commit `dc493256`.
  - Findings:
    - The units are drawn only, and the network has no heat transfer.
    - The pipe thermal-loss kernel (2026-09-24) fits each side.
    - The causal module system that once gave equipment buffers was removed on 2026-10-15 for want of a producer.
    - The drawn exchanger is a 1-2 shell, which counterflow overstates by up to 42 %.
  - Study harnesses `tools/heat-exchanger-design/`. Recommendation: option B, utility exchangers first; a controller between slices, dead-beat.
- 2026-10-27, revision 2: the owner answered Q1 to Q9.
  - The answers: B; a film per phase without lag; counterflow; steel wall; real fluid on both sides; the recommended control; ratio control as an assembly with real lines and valves; the recommended order; old builds register.
  - The films study (harness parts 5 and 6):
    - The steel wall makes gas exchangers settle in minutes (physics).
    - The first coupling scheme overshoots at unequal slices (254 s against 7 s).
    - Its cell-local form diverges for strong films on two finite streams, even a condensing mixture against water with the full steel wall.
    - Chosen: the lagging side's open-slice rate plus the joint steady wall. Stable on the whole map, at transients 1.5 to 2 times larger.
  - Plan revised: utilities removed; work packages reordered; W8 the exchanger assembly; new owner questions Q10 to Q13.
- 2026-10-27, revision 3: the owner answered Q10 to Q13.
  - The answers: no vessel heat capacity; the air cooler's air at a constant 25 °C; a fired heater heats to a set temperature, fires at the maximum with an alert when its duty falls short, and fuel is another scope; the recommended order; the internal bypass kept beside the assembly.
  - The zero-wall study (harness part 7):
    - The exchanger is quasi-steady, and the coupling becomes a relay of the heat in transit, planned at each slice's dispatch.
    - Parts 2 to 6 planned slices at their commit, so their equal-slice results were optimistic.
    - Give-back split by slice heat, chosen among three forms. Within 1 K in two slices at equal slices, 59 s at worst at unequal ones; give-back 0 to 15.5 K for one slice.
    - The controller's give-back offset was measured and rejected: 4 to 5 times slower at 20/5 s.
  - Plan revised to revision 3: wall state, slice mean and wall clamp removed; the heat in transit becomes the saved fact; the fired heater's set-temperature law; W3, W5 and W6 gates.

- 2026-10-27, revision 4: plan review by a Fable subagent before W1; the code claims checked by the author.
  - Blocker: the game dispatches a slice after its end tick and solves it without callbacks. So the relay's open-slice rate and dispatch wait do not exist. W3 now waits for a timing study (W3a, forms A, B, C) and an owner choice.
  - Into the plan:
    - the curve's span with C1 extrapolation;
    - film knots at a vapour fraction of 0.05 and 0.95 (pure plateaus);
    - the give-back as an enthalpy shift on the flow;
    - a side device per side and a pair record in the topology unit's facts;
    - a new certificate discard entry;
    - the interval result's heat and checkpoint state format 23;
    - minor numbers and wording.
  - New owner question Q14: the one-sided air cooler and fired heater before the coupling.
- 2026-10-27, W1 the heat law, started after the Fable review on the owner's instruction. Commits `5281f53e` (curve and side kernel, 11 tests) and `2f170a24` (solver wiring).
  - The heat enters the receiver's energy row like a mover's work. The reconstruction books it as a `HEAT` boundary at the connection, so the audit, the certificate replay and the checkpoint codec carry it with state format 22 kept.
  - The curve is the stream the donor end's port draws, not the donor's whole mixture.
  - The seeds read the heated stream. Without that, a junction heated past saturation (water, 10 bar, 20 m² at 520 K, 2 s step) converged as liquid 22 K superheated; with it, two-phase at saturation.
  - Changes from the plan (section 10): the side is a `Pipe` component, not a `FlowControl` kind; the heat is a boundary kind, not a result field; the passage law moves to W2.
  - The kernel uses `DeterministicMath` (`log1p`, `expm1`), as the determinism gate asks.
  - Gates: `HeatCurveTest` 11 and `HeatSideNetworkTest` 4 (ledger, boiling junction, reversal, zero flow and bypass); quick suite 1657 (the one failure, the determinism gate, fixed and its classes re-run); chain-100 exact (zero deviation).
- 2026-10-27, Q14 answered (commit `6a96b09a`): the one-sided units before the coupling. W2 the device, commit `d47c80dc`.
  - An exchanger registers a tube and a shell side, partners, placed and removed in one ledger event. Each side compiles as an inline filter does: a passive pipe of its passage between its inlet node and an outlet junction. No side moves heat yet.
  - The passage law: a channel count on the section geometry. The example tube side (shell 1 m, tubes 6 m) loses 4 kPa at 50 kg/s of water and 35 kPa at 1.5 m/s.
  - Formats: facts schema 5 (the channel count and the pair record appended) with its corpus image `compat/fluid/schema-5`; checkpoint state format 23; certificate graph identity tag -3.
  - Screen: the side's title and the "Other side" button; a side's edit refused until W4. The one in-game check moves to W5.
  - Gates: `HeatSidesTest` 3, `HeatSideTopologyTest` 2, `FluidFactsCompatTest` 7 (five images); quick suite 1663 (four failures fixed, their classes re-run: an archived capture read through the live geometry record, the line-piece sweep and the rasterizer's node test meeting the new kind).
- 2026-10-27, W5 the air cooler, commit `4840441d`.
  - One PROCESS side per bank; its heat law a flat wall of air at 25 °C under the effective films UA_eff / A of plan section 4 (no new kernel). The fans are the control's manual share (natural draught when still); `Mode.BYPASS` removed as a duplicate.
  - The screen: inlet and outlet temperature, duty, fans and fan power. Fan power is a datasheet and screen line, not a fluid energy term (it heats the air).
  - State format 24: the checkpoint carries a connection's heat side; format 23 dropped it, so a restored island would have lost its air cooler's heat.
  - Measured: the outlet junction holds half the tubes' liquid, a lag of about the tubes' residence time (14 s for a 3 × 6 m bay at 13.5 kg/s of water). The gate runs 300 s.
  - Fixed in passing: the menu edit dropped a rack lane's width and face points, so a rack pipe's diameter edit was refused. The edit now goes through withers.
  - Gates: `HeatSidesTest` 5 (the effective films per phase at fans on and off, the bay's side and passage), `HeatSideTopologyTest` 3 (85 °C water through a 3 × 6 m bay settles within 0.1 K of 25 °C + 60 K × exp(−hA/ṁc_p), fans on and off), `FluidCheckpointCodecTest` 11 (a heat side round trip); quick suite 1667, no failures.
  - In-game check (the task's one, moved from W2; an opus subagent on the runMcpClient lane and the bridge, screenshots in the worktree's `tmp/w5-ingame/`). Passed:
    - the exchanger's tube and shell side titles and the Other side round trip;
    - the air cooler on 28.4 kg/s of water at 85 °C: outlet 73.1 °C, duty 1416 kW, fan power 12.2 kW;
    - fans off: outlet 81.3 °C, 447 kW, 0 kW; fans on again: the first readings back.
  - Two layout defects it found, fixed in the follow-up commit:
    - the rail buttons covered the readings: `railControls` now counts the fans and partner buttons;
    - the one-sided air cooler showed a dead Other side button: `FluidView.Heat.paired` hides it.
    The fix was judged by the rail's row count and the affected tests, not by a second in-game check.
- 2026-10-27, W6 the fired heaters, in the W6 commit.
  - A heater registers its convection and radiant coils as partner sides, 30 % and 70 % of the datasheet duty at 15 and 35 kW/m². The coil law is β · reach · |ṁ| · (h_set − h_in), with h_set at the plateau's vapour end, so a set point above saturation raises steam.
  - β is planned at each step's start, not each slice's: a change to plan section 4, and it shortens the in-slice overshoot of section 13 to one step (reversible; the as-built notes say why).
  - Burners and set point are `Mode.FIRING` on W2's control fields, edited for both coils in one event; no facts change. "DUTY INSUFFICIENT" on the status line when a coil fires at its maximum.
  - Formats: state format 25 (tagged heat law), certificate graph identity tag -4.
  - Gates: `FiredCoilTest` 4 (the law, the planned share, `enthalpyAt` on a plateau), `HeatSidesTest` 6 (the coils' duty split and passage), `HeatSideTopologyTest` 8 (five W6 gates: the set point exactly within the duty; h_in + Q/ṁ at the maximum; no firing above the set point; steam at 200 °C from 50 °C water at 10 bar; 30/70 in series, numbers in plan section 10); quick suite 1677, no failures (the layout table did not move: a coil's node is its inlet nozzle's cell, already a shell cell).
  - No in-game check (the task's one went to W5). The screen's new rows and buttons follow W5's rail rule: the burners button and set point field count two rail rows.
- 2026-10-27, W3a the timing study, in the W3a commit.
  - Harness part 8 (`HxCouplingSim.java timing`, about 5 s): part 7's relay on the game's clock. A slice is dispatched when online time reaches its end and commits 0 to 40 ticks later; cadences are fixed or adaptive; a side is stopped only while held. It measures the three forms on part 7's cases, holds and controller, at latency 0, at 40 and at five seeds (1512 runs). Parts 1 to 7 are byte-identical.
  - Result: C, the fence, is deterministic, keeps the transit under 2e-5 J, and settles fastest (mean 34 s against 45 s for B and 69 s for A; worst 79 s against 239 and 259 s). It recovers from holds 1.1 to 9 times sooner and controls fastest. A varies by up to 7.2 K with job times alone, B by 1.0 K.
  - Found: a stopped-by-lag rule is not needed (0 firings in normal running, no faster recovery). A side whose jobs take as long as its cadence never works off a hold's debt; the harness caps latency at half the cadence.
  - Q16 put to the owner: C recommended, B the alternative if screens must never wait.

- 2026-10-27, W3 the two-sided coupling (form C, owner Q16), in the W3 commit.
  - Each side plans at its slice start from its partner's record as of the slice start: the quasi-steady 8-cell wall (alternating sweeps, an Illinois root per cell, 0.01 K), its share of the heat in transit by capacity rate times slice length, and the give-back as an enthalpy shift held between the inlets. A side's slice waits until its partner has committed through it, is certified there, or is stopped.
  - Three decisions found in the build (reversible, plan section 10): a steady side keeps its wall (otherwise coupled islands never certify); a still or stopped partner gives an active share of 0 (otherwise 70 W leaks against a flat wall); the booked and the settled transit are distinct readings. Two more for loads: a side's last span is rebuilt from its saved interval, and a certified partner's first record is adopted as its baseline.
  - Formats: state format 26, certificate graph identity tag -5. Facts unchanged.
  - Gates: `ExchangerCouplingTest` 7 (steady state at three cadence pairs within 0.002 K; a 60 s hold; a feed/effluent loop; steam in methane against water at 20 s slices; a side without flow books 0 J; a load while the sides stand apart; certify and wake), `ExchangerRelayTest` 2, `FluidFactsCompatTest` +1 (the transit fact); quick suite 1687, no failures. The wall: 7 sweeps, 0.10 ms.
  - No in-game check: the screen did not change.

- 2026-10-27, W4 the built-in control, in the W4 commit.
  - One controller per side (`HeatControl`: manual, outlet temperature or duty, a low limit) on the unit's own actuator: an exchanger side's bypass, planned with the wall at each slice start; an air cooler's fans, planned at each step as W6's firing. Its root is dead-beat on the side's own law, the give-back not counted; at an end short of the target the status says LIMITED, and a held low limit says LOW LIMIT.
  - Decisions (reversible, plan section 10): `Mode.FAN` removed (no save held it); the air cooler gets its own law, `AirCooled`, since its fans set its films; the bypass and the wall solved in turn, not once; the low limit in every mode on a cooled side's own outlet; the planned limit kept in the law for the status line.
  - Fixed in passing: a fired heater coil's "Other side" button sat on its set point field's row (W6).
  - Formats: state format 27, certificate graph identity 6. Facts unchanged.
  - Gates: `HeatControlTest` 5, `ExchangerRelayTest` +1, `ExchangerCouplingTest` +1 (45 °C held through a +15 K hot inlet step: at most 0.70 K off, within 0.1 K from 44 s), `HeatSideTopologyTest` +1 (65 °C held by the fans through an inlet step, the LIMITED ends, a 67 °C low limit), the codec round trip; quick suite 1695, no failures.
  - In-game check: the owner stopped it ("that's enough client testing") after the air cooler's manual screen (an opus subagent on the bridge lane; screenshots in the worktree's `tmp/w4-ingame/`). The rail showed the fans, the mode button and the low limit in order. The low limit's label was cut at the rail's width at GUI scale 2; it now reads "Low limit (C) or blank". The rest of the screen is judged by the gates, the compile and the rail's row count, not in the client.

- 2026-10-28, W7 the kettle, in the W7 commit.
  - The kettle registers its tubes as a side and its shell as a closed vessel whose inventory carries the pool (`Pool`): the bundle's surface and where it lies, and the wall, give-back and active share planned at each slice start from the tubes as read. The pool's heat enters the vessel's energy row explicitly on each step's start state and is booked as a `HEAT` boundary at its key. It takes all the heat in transit.
  - Found in the build: the uncontrolled first probe lost 750 kg of liquid through the vapour port in 5 s, because the phase draw capped the port at the vapour held while the pool boiled more per step. The boil-up now counts in the gas capacity (D11 extended, as an open vessel's release is). The same probe ran dry and superheated its last holdup to 583 K; the film now follows the wetted share of the bundle under the level.
  - Formats: state format 28, certificate graph identity 7; facts unchanged.
  - Gates: `ExchangerCouplingTest` +2 (boil-up 1.2095 kg/s against Q/λ 1.2101 at 2.79 MW, the pool at 373.13 K at 1 atm; a 1 MW duty held by the tubes' bypass 0.800, boil-up within 0.05 %; an empty shell takes nothing), `HeatSidesTest` (the kettle's tubes and shell), `ShapeRasterizerTest`, the codec round trip; quick suite 1698, no failures.
  - No in-game check (owner, W4: "that's enough client testing").

- 2026-10-28, W8 the exchanger assembly, in the W8 commit.
  - The sides' controls are the design: an outlet temperature or a duty asks for a bypass (inlet tee, control valve, outlet tee), the new `RATIO` mode for a feed valve and a flowmeter on the other side's feed. Check lines on a side's screen validates the exchanger as an assembly; the side's status line reports it. A FLOWMETER element joins the walk.
  - Operation is a slice input the island plans with the side (the controller's bypass share, or the ratio times the other side's flow as read), not section 7's override kept out of the digest; a 1e-3 deadband keeps a steady plant certifiable. Found in the build: a control valve without its flowmeter stands open in the solver, so a bare valve reports UNMETERED and does not operate. The candidate check now accepts a planned valve target.
  - No format change: `RATIO` is an enum constant saved by name; the read is not saved.
  - Gates: `ExchangerBlueprintTest` 4, `ExchangerCouplingTest` +2 (45 °C held at the outlet tee through the real valve, within 2.85 K through a +15 K step; a ratio of 0.5000 after the hot flow steps), fluid GameTest +1 (all 35 pass); quick suite 1704, no failures.
  - W9 deferred: the column's operation stage, its prerequisite, does not exist (plan section 10).
- 2026-10-28, review world, in the review world commit: the owner asked for a world to do the final test themself (no client check by the agent).
  - `HeatExchangerReviewWorld` (fluidGameTest, namespace `icheme_review`, run by `runReviewWorldServer` beside the column plant; not a gate) builds five units with water lines and controls: both exchangers at 30 °C (one with a 0.1 m real bypass, lines not checked, valve without its flowmeter), the largest air cooler at 80 °C, a vertical heater fed by a pump to 200 °C, a 1 m kettle at 0.5 MW fed from a generator 1 m over its shell floor with a 0.15 m vapour line.
  - Four builds to tune: a 45 °C set point was out of reach of the internal exchanger; the default air cooler was limited at full fans; a pump beside a generator does not join (a device joins only a pipe); the kettle's 0.05 m vapour line held the shell at 147 kPa, over the feed's head, so the pool boiled down.
  - Level renamed with the column batch's `nbt.js`; copied to `run/saves/IChemE heat exchanger review`.
- 2026-10-28, owner test of the review world, in the owner test commit: findings and proposals in OWNER_TEST_REVIEW.md, mockup HEAT_GUI_MOCKUP.svg. Two read-only Opus surveys (GUI, water data). The water saturation data already span the triple to the critical point (IAPWS); the notice is the ideal steam's 2 % limit. No reproduction yet: the owner's dev client ran from this worktree. The old pump and compressor removal went to its own session (owner's choice).
- 2026-10-28, owner test reproductions, in the probe commit: client closed, probe runs on a fresh build and on a copy of the owner's save. Heater hold reproduced (25 °C feed at 100 kPa against the 10 bar void, residual 1.7870486723459762); air cooler: controller right (fans 7.7 %), the outlet node's 260 kg liquid holdup flushes with τ ≈ 33 min; exchanger: holdup τ ≈ 50 s, then a wake ping-pong on the booked transit (−1.07 MJ against 4 kJ). Owner approved the GUI mockup and asked for its own thread (session `heat-gui`).
- 2026-10-28, Q15 answered (yes: drop the curve-span notice), in the Q15 commit. `HeatCurve.extrapolated`, never wired to a status line, removed with its test assertion; plan sections 3, 4 and 12 record the answer. The property models' data-range notices stay (AGENTS.md "Thermo validity"); the heater coils' "water vapour partial pressure extrapolated above 250 kPa" is one of them.
- 2026-10-28, the heat GUI (owner: "GUI seems ok but it needs its own thread"), on branch `claude/heat-gui-redesign-abb1db` (base `8f78119b`): plan `cef28f4a`, the build in the heat GUI commit.
  - Control tab on every heat side and the kettle's shell: the flowsheet (nozzles, the W8 parts in the report's colours, T and flow on every line, Check lines on the drawing), the side selector, the modes naming the actuator, the set point and "Never cool below", "Acting now" with its effort bar, the lines' note. Overview: a stream switch, each port's T, P, flow, phases and composition. A pipe: its temperature and pressure, "(no flow)" when still.
  - Fluid wire fluid-16 (no compatibility work): the side's actuator, effort, limit, ports and lines, and a pipe's reading, read by `HeatPresentation` where the view is built; a replay sends the cache. The actuator's name comes from the server, so louvers need one server line.
  - Fixes: the duplicate `ratio_input` (the exchanger's is `heat_ratio_input`); statuses in words clause by clause, LOW LIMIT and DUTY INSUFFICIENT in the player's unit.
  - Owner options Q-G1 to Q-G4 built as (a) (plan section 3).
  - Gates: `HeatDiagramTest` 5, `HeatPresentationTest` 2, `FluidPacketCodecTest` +2, `FluidGuiConsistencyTest` +1; quick suite 1714 (one failure, the new test's valve built without a valve's control, fixed and its classes re-run). Layout judged by PNG renders (`tools/heat-gui-renders/`); no in-game check (owner).
- 2026-10-28, owner test fixes, in the owner fixes commit. The owner's answer: "I don't think furnace convection section can heat to 800C, try to find a better model. Others as recommended".
  - The exchanger's wake reads the settled transit (`IslandCoordinator.moved`). Probe: no wakes in the first 170 s, against 1.25 per s before; then about one per 8 s at the kettle, whose pool really fills.
  - A heat side's passage no longer sizes its nodes' holdups (`PassiveNetwork.sizeJunctionHoldups`): the outlet held half a bundle. The exchanger's outlets settle by 70 s. The same oversized coil nodes caused the fired heater's 100 kPa hold. An A/B on the probe, with only this rule reverted, brings the hold back at residual 1.7870486723459762; with the fix, stage 1 is STEADY at no flow, the owner's end state.
  - Air cooler louvers (`AirCooled.draught`, `fans()`, `louvers()`): one effort from shut louvers through natural draught to full fans; the status "LIMITED: the louvers are shut". The manual share keeps its meaning (fans, louvers open).
  - `ExchangerCouplingTest.certifiedSidesWakeWhenTheTransitMoves` moves the booked transit: it moved the settled one and was off by one slice's heat (11.7 MJ).
  - Furnace: proposal F (a counterflow convection coil against the flue gas leaving the radiant box) replaces A; F1 recommended.
  - The side sessions finished: the water steam virial (`claude/water-steam-virial`) and the GUI (`claude/heat-gui-redesign-abb1db`), not merged here; the old pump and compressor removal is its own batch (`claude/vigorous-bell-dab92b`).
  - Gates: the heat classes (48, then the coupling class, 12), quick suite 1704 with no failures, fluid GameTests 35/35 (`ownerfixes1`), and the probe.
- 2026-10-28, F1 the furnace convection model, in the F1 commit. The owner: "Build F1".
  - `ConvectionCoil` (new): a counterflow exchanger against the flue gas leaving the radiant box (T_bw 800 °C, C_g = Q_r / 1000 K, 54.9 W/m²K). `FiredCoil` is the radiant coil: the reach is gone, and it names its partner.
  - The flue gas is planned per step in one island; across islands the relay carries the radiant coil's heat rate, and only the convection coil waits at the fence. The coils book no heat in transit.
  - Formats: checkpoint state 29, certificate graph identity 8.
  - Gates: `ConvectionCoilTest` 5, the series and two-island tests, quick suite 1710 with no failures, fluid GameTests 35/35 (`furnacef1`). Probe: the owner's case 701.5 kW, out at 41.8 °C, steady, no hold.
- 2026-10-28, the side branches merged on the owner's word ("Merge both"): `claude/water-steam-virial` (`c6e7d480`, WATER_STEAM_VIRIAL_REVIEW.md) cleanly; `claude/heat-gui-redesign-abb1db` (`c434dbb8`, HEAT_GUI_PLAN.md, with the GUI toolkit review of batch 2026-10-28-gui-toolkit-evaluation) with the history lines kept from both. The air cooler's actuator on the Control tab is now "louvers", its effort the air's share of full fans; the convection coil shows the burners without an effort bar. Gates on the merged branch: test 1725 and slowGates 188 with no failures, fluid GameTests 35/35 (`mergedsides`).
  - Open from the water branch: start a seeded junction's flows from its seeds (owner's call; WATER_STEAM_VIRIAL_REVIEW.md finding 1); a restatement at fixed volume (finding 3).
- 2026-10-28, main synced on the owner's word ("Yes, sync main"): `342c6d22` merges main 0.16.2 (batches 2026-10-28-old-mover-removal and 2026-10-28-main-test-repair, and those released as 0.15.1 to 0.16.2).
  - Conflicts kept both sides: `FluidFacts` drops a removed one-block mover before it returns a registration with its heat unit; `ShapeRasterizer` keeps `fluidCells` and `skylight`; `FluidFactsCompatTest` keeps the steam-v2 and removed-movers images; `LineGeometryTest` skips heat sides and in-line movers; CHANGELOG and the indexes keep every entry and row; the code guide takes main's snapshot and names this branch's water steam update.
  - Formats: main kept checkpoint 22, certificate identity 2 and facts schema 4, so this branch's 29, 8 and 5 stand.
  - The review world's heater pump is an In-line Pump on the feed line's second pipe (`HeatExchangerReviewWorld.pump`, installed as the fitting item installs it). A save of the review world made before the sync opens without its one-block pump (main's facts path removes it whole): rebuild the world, or fit an In-line Pump on the heater's feed line.
  - Gates on the synced branch: `test` 1736 with one failure, main's removed-movers compat test, whose image predates the virial steam, so the load names the property data's change first; the test now expects that reason (`8190466a`; `FluidFactsCompatTest` 10/10). `slowGates` 188, fluid GameTests 36/36 (`sync-main`). Probe: stage 1 steady at no flow after 79.8 s ("pump cannot drive forward flow"), stage 2 at 9.970 kg/s, convection 701.5 kW out at 41.8 °C, radiant 1148.6 kW, as with the one-block pump; no hold.
- 2026-10-28, the review world rebuilt on the owner's word ("Rebuild review world") with the heater's In-line Pump: `run/saves/IChemE heat exchanger review`; the owner's test save kept as `IChemE heat exchanger review (owner test 2026-10-28)`. REVIEW_WORLD.md's build column now gives this build: the kettle's tubes carry 21.8 kg/s since the owner-test fixes (out 144.7 °C, not 132.6 °C), both exchangers hold 30.0 °C through the internal bypass until the lines are checked, and the heater's coils are still filling at the snapshot.
- 2026-10-28, the GUI toolkit batch merged on the owner's word ("merge to the heat branch"): `claude/gui-toolkit` (`c266a36c`, batch 2026-10-28-gui-toolkit, its README and GUI_TOOLKIT_PLAN.md) in `09e84a81`, made in that batch's worktree. Every screen on the kit at its own whole GUI scale; the column calculator needs a diameter above 0, and Holland Example 3-2 left the game (its benchmark stays in the solver's tests). Conflicts: the kit's constructor and calculator screens took main's Import protocol and STALE rule; `ProcessSolveServices` keeps main's solve strategy without the Holland dispatch; `tools/INDEX.md` keeps every row. Gates: `test` 1796, `slowGates` 188, fluid GameTests 36/36 (`guimerge`), no failures.

## Notes

- Q15 answered 2026-10-28: no curve-span notice (plan sections 4 and 12). Q16 answered 2026-10-27: form C, the coupling fence (W3a). Q14 answered 2026-10-27: W1, W2, W5, W6, W3a, W3, W4, W7, W8, W9.
- Open from W1:
  - a pipe gauge downstream of a side reads the outlet junction; a gauge on the unit itself still reads its inlet node;
  - the step accepts a junction laid out without a phase its enthalpy holds; only the seeds guard it (W1 saw liquid 22 K superheated when the seeds missed the heat). To be put to the owner;
  - the curve is fluid only: a stream carrying crystals reads its enthalpy on the fluid's curve.
- Open from W5: the drawn fans keep turning when switched off (the motion switch is the client's own).
- Open from W6: the heaters' FUEL nozzle stays unconnected (fuel is another scope, Q11); the datasheet does not list the coils' surfaces yet.
- Measured in W1: a junction boils from heat inside the Newton. Measured in W3: the quasi-steady wall on a condensing curve (7 sweeps, 0.10 ms). Not measured yet: the campaign's cost and holds in worlds with exchangers (it has none).
- Owner test (2026-10-28): F1 built (the furnace's convection coil against the flue gas). At the GUI merge, `FluidWorldAuthority`'s coil status line conflicts (the reach is gone). Both side branches are merged (2026-10-28). The probe (`runReviewWorldServer -PreviewProbe=true|owner`, `HeatExchangerReviewWorld` and `ColumnReviewWorld` owner mode, the `build.gradle` switch) is scratch, to `tools/` at the cleanup. The GUI is merged (`acc6cddf`).
- Code guide at the merge: the review world row (`verification.md`, `machine-assemblies.md`) names one column plant; `runReviewWorldServer` now builds the heat plant too.
- Open from W8: the split-range pass valve; a LIMITED status for a saturated real valve; the hold after a revert has no gate; a valve outside the side's island is not operated.
- Open from W4: the target vapour fraction (plan section 6) is not built; a controller on both sides of one exchanger is allowed and untested; the kettle's steam valve waits for W7 and the assembly's real valves for W8.
- Open from W3:
  - the authority's live load, capture and removal of the transit are gated through the ledger's snapshot methods, not by a GameTest;
  - after a load only the current record exists, so a side whose partner is a slice ahead reads it (D1);
  - D6, a budget hold counted as stopped, has no gate.
- 2026-10-28 (W7 open): the kettle's LIQUID_OUTLET is a plain vessel port, so the level is the player's (no weir); the pool's film follows the level, not a level controller.
- Code guide: not updated yet; the fluid network chapter is updated before the merge.
- Open from the heat GUI (2026-10-28): Q-G1 to Q-G4 await the owner; the screen reads the LOW LIMIT and DUTY INSUFFICIENT clauses by their `%.1f K` formats; not gated: a pipe's remembered reading and the kettle's shell ports; the Control tab not seen in the client.
