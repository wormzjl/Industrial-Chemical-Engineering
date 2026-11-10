# Rigid reservoirs once filled

Status: **In progress since 2026-11-10** (implemented on the branch, 3041f3f7e; not merged). Branch `claude/ecstatic-dubinsky-c24b8a`, after the steam ejector batch.

## Scope

Owner, 2026-11-10: "Resevoir should be rigid when it's filled once (except the floating roof tank)". A closed vessel that has once been full keeps its volume and can stand below the ambient. Before, by decision D54, it was a bag that shrank at the ambient.

## Documents

- [RIGID_RESERVOIR_PLAN.md](RIGID_RESERVOIR_PLAN.md): the old bag code, the expected behaviour in numbers, the `rigid` fact (facts schema 18), the limits, the measurement, decisions O1, V1, R1, K1. Section 7 has the implementation and the owner's two decisions made during it.

## History

- 2026-11-10: plan written (88dc5113c).
- 2026-11-10: owner "All A, floating roof tank stays at it is." (O1, V1, R1, K1 A).
- 2026-11-10: implemented (3041f3f7e). Two more owner decisions, both A:
  - First bubble: in a rigid vessel, a gas phase is judged by its volume share in D34 and D38 C.
  - Breakthrough pass order (amending A272): a pass with a forbidden direction is phase-checked before a boundary closes.
- Tests run, all passed:
  - `RigidVesselTest` 4/4;
  - `FluidFactsCompatTest` (corpus schema-18, 2 rigid vessels), `FluidCheckpointCodecTest`, `IslandCertificateTest`;
  - the `science.fluid.network` package and 17 `runtime.fluid` tank, pump, still and ejector classes (80 classes).
- 2026-11-10: 200-seed quick check against the 0.24.0 lane archive (`batch-distiller-final-lane-logs-2026-11-10.tar.gz`, seeds 1-200):
  - clean seeds: cold 197/200, bundled random 199/200, defaults 200/200, the same as the baseline;
  - held seeds: the same (cold 85, 101, 118; bundled 200), 0 refused, no new class;
  - cold 118 went from 12 holds (one wall-deadline hold) to 1;
  - moved outcomes: 5 cold seeds (6, 48, 118 among them, crystal masses within 0.1 %); bundled is identical.
- Campaign: the owner dropped the separate 1000-seed campaign ("simplify the tests, it's taking forever"). It runs once in the merge's final lane, where its inputs have changed since main.

## Code areas

- Science: `science/fluid/solver/PhaseLayout` (wall row), `science/fluid/network/PassiveNetwork` (`Inventory.rigid`), `PassiveStepSolver` (`flashes`, the closure order), `ConservativeTransport` (where the flag is set; `repartition`), `VesselCrystals`, `InventoryEquilibrium`, `science/fluid/thermo/FluidThermodynamics` (`rigidGas`, `belowShare`, `equilibriumAtVolume`).
- Runtime: `runtime/fluid/FluidFacts`, `FluidCheckpointCodec`, `IslandCertificate`, `IslandSets`, `PhysicalRegistry`.

## Open items

- The final lane at the merge: the affected test classes (238 of 366 by the load sets, since `FluidThermodynamics` changed), chain-100 exact, the fluid GameTests, the campaign.
- Presentation is unchanged. A rigid tank below the ambient shows its pressure in the GUI. No vacuum notice, by V1 A.
