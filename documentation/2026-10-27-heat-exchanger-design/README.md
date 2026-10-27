# 2026-10-27-heat-exchanger-design

Purpose: Owner request 2026-10-27: design a heat exchanger system:

- a UA that scales with each unit's size;
- built-in control that keeps the fluid solver converging;
- a view on input/output buffers that decouple the networks on both sides with a relayed pressure.

Status: In progress since 2026-10-27. W1 (the heat law) and W2 (the device) are built; W5 (the air cooler) is next; the owner answered Q1 to Q14. Index: [../INDEX.md](../INDEX.md).

## Documents

- [HEAT_EXCHANGER_PLAN.md](HEAT_EXCHANGER_PLAN.md), revision 4, with W1 and W2 as built (section 10). It covers:
  - the physics in numbers and the owner's decisions;
  - UA from size with a film per phase and the approximations that keep it free of lag;
  - the air cooler (air at 25 °C) and the fired heater (heat to a set temperature, capped at its duty);
  - the coupling of the two sides (a relay of the heat in transit between islands; the wall holds no heat);
  - built-in control and the exchanger assembly with real lines and valves (owner Q7);
  - saves, work packages W1 to W9, simplifications and risks.
- [HX_COUPLING_STUDY_REVIEW.md](HX_COUPLING_STUDY_REVIEW.md): the first offline study: cell count, the drawn 1-2 shell against counterflow, four coupling schemes across equal and unequal slices, holds, and the controller.
- [HX_FILMS_STUDY_REVIEW.md](HX_FILMS_STUDY_REVIEW.md): the revision 2 study: films per phase, the physical and the coupling lag, three coupling forms, and the stability map behind the joint steady wall.
- [HX_ZERO_WALL_STUDY_REVIEW.md](HX_ZERO_WALL_STUDY_REVIEW.md): the revision 3 study: the wall without heat, the relay planned at dispatch, three give-back splits, holds, and the controller.
- [HEAT_EXCHANGER_PLAN_REVIEW.md](HEAT_EXCHANGER_PLAN_REVIEW.md): the review of revision 3 against the code (a Fable subagent, read-only; 22 findings, taken into revision 4).

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
- 2026-10-27, Q14 answered (commit `6a96b09a`): the one-sided units before the coupling. W2 the device, in the W2 commit.
  - An exchanger registers a tube and a shell side, partners, placed and removed in one ledger event. Each side compiles as an inline filter does: a passive pipe of its passage between its inlet node and an outlet junction. No side moves heat yet.
  - The passage law: a channel count on the section geometry. The example tube side (shell 1 m, tubes 6 m) loses 4 kPa at 50 kg/s of water and 35 kPa at 1.5 m/s.
  - Formats: facts schema 5 (the channel count and the pair record appended) with its corpus image `compat/fluid/schema-5`; checkpoint state format 23; certificate graph identity tag -3.
  - Screen: the side's title and the "Other side" button; a side's edit refused until W4. The one in-game check moves to W5.
  - Gates: `HeatSidesTest` 3, `HeatSideTopologyTest` 2, `FluidFactsCompatTest` 7 (five images); quick suite 1663 (four failures fixed, their classes re-run: an archived capture read through the live geometry record, the line-piece sweep and the rasterizer's node test meeting the new kind).

## Notes

- Open: after W3a, the coupling's timing form. Q14 answered 2026-10-27: W1, W2, W5, W6, W3a, W3, W4, W7, W8, W9.
- Open from W1:
  - for W5's screen and its in-game check (moved from W2): the extrapolation notice on the status line and the gauges' outlet temperature;
  - the step accepts a junction laid out without a phase its enthalpy holds; only the seeds guard it (W1 saw liquid 22 K superheated when the seeds missed the heat). To be put to the owner;
  - the curve is fluid only: a stream carrying crystals reads its enthalpy on the fluid's curve.
- Measured in W1: a junction boils from heat inside the Newton. Not measured yet: the quasi-steady wall on phase-change curves and the solver cost (W3 and its campaign).
- Code guide: not updated yet; the fluid network chapter is updated before the merge.
