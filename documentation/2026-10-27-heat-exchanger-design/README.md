# 2026-10-27-heat-exchanger-design

Purpose: Owner request 2026-10-27: design a heat exchanger system: a UA that scales with each unit's size at a constant U, built-in control that keeps the fluid solver converging, and a view on input/output buffers that decouple the networks on both sides with a relayed pressure.

Status: Planned 2026-10-27 (design only, awaiting the owner's answers to Q1 to Q9). Index: [../INDEX.md](../INDEX.md).

## Documents

- [HEAT_EXCHANGER_PLAN.md](HEAT_EXCHANGER_PLAN.md): the physics in numbers, UA from size, three coupling options (one island; a wall account between islands, recommended; the owner's material buffers with a pressure relay), built-in control, saves, work packages W1 to W8, simplifications, owner questions (section 11).
- [HX_COUPLING_STUDY_REVIEW.md](HX_COUPLING_STUDY_REVIEW.md): offline study behind the recommendation: cell count, the drawn 1-2 shell against counterflow, four coupling schemes across equal and unequal slices, holds, and the controller.

## Code areas

None changed. The plan touches: `science/fluid/network/PassiveStepSolver` (`Equations.nodeAccumulate`, `BalanceNet`, `arrivingPhases`), `ConservativeTransport` (beside `pumpWork`), `PassiveNetwork.Pipe`, the restored `PipeHeatExchange` (`tools/pipe-thermal-loss/prototype.patch`); `runtime/fluid/IslandCoordinator` (command inputs, commit, wake), `FluidFacts` (facts schema 5), `FluidWorldAuthority` (exchanger registration); `science/equipment/ProcessUnit` (`tubeSurface`, `bundleFace`, `heaterDuty`); the exchanger's screen.

## History

- 2026-10-27: design written on worktree `research-folder-reorganize-b8443b` (branch `claude/heat-exchanger-design-cf05d1`, base `995dbd9c`, 0.15.0). Findings: the units are drawn only and the network has no heat transfer; the pipe thermal-loss kernel (2026-09-24) fits each side; the causal module system that once gave equipment buffers was removed on 2026-10-15 for want of a producer; the drawn exchanger is a 1-2 shell, which counterflow overstates by up to 42 %. Study harnesses `tools/heat-exchanger-design/` (coupling: as-of read with the slice mean reaches the exact steady state for every slicing; 4 x 2 cells within 2.3 % of the 1-2 formula). Recommendation: option B, utility exchangers first; a controller between slices, dead-beat.

## Notes

- Open: owner questions Q1 to Q9 (plan section 11).
- Not measured yet: phase change from heat inside an island's Newton, and the solver cost; W1 and W4 measure them.
- Code guide: no change (no code changed).
