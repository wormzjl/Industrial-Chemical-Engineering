# 2026-10-27-heat-exchanger-design

Purpose: Owner request 2026-10-27: design a heat exchanger system:

- a UA that scales with each unit's size;
- built-in control that keeps the fluid solver converging;
- a view on input/output buffers that decouple the networks on both sides with a relayed pressure.

Status: Planned 2026-10-27 (design only). The owner answered Q1 to Q13; no question is open. Index: [../INDEX.md](../INDEX.md).

## Documents

- [HEAT_EXCHANGER_PLAN.md](HEAT_EXCHANGER_PLAN.md), revision 3. It covers:
  - the physics in numbers and the owner's decisions;
  - UA from size with a film per phase and the approximations that keep it free of lag;
  - the air cooler (air at 25 °C) and the fired heater (heat to a set temperature, capped at its duty);
  - the coupling of the two sides (a relay of the heat in transit between islands; the wall holds no heat);
  - built-in control and the exchanger assembly with real lines and valves (owner Q7);
  - saves, work packages W1 to W9, simplifications and risks.
- [HX_COUPLING_STUDY_REVIEW.md](HX_COUPLING_STUDY_REVIEW.md): the first offline study: cell count, the drawn 1-2 shell against counterflow, four coupling schemes across equal and unequal slices, holds, and the controller.
- [HX_FILMS_STUDY_REVIEW.md](HX_FILMS_STUDY_REVIEW.md): the revision 2 study: films per phase, the physical and the coupling lag, three coupling forms, and the stability map behind the joint steady wall.
- [HX_ZERO_WALL_STUDY_REVIEW.md](HX_ZERO_WALL_STUDY_REVIEW.md): the revision 3 study: the wall without heat, the relay planned at dispatch, three give-back splits, holds, and the controller.

## Code areas

None changed. The plan touches:

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

## Notes

- No owner question is open.
- Not measured yet:
  - phase change from heat inside an island's Newton;
  - the quasi-steady wall solved on phase-change curves;
  - the solver cost.

  W1 and W3 measure them.
- Code guide: no change (no code changed).
