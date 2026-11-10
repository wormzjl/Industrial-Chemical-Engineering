# Rigid reservoirs once filled

Status: **Planned** (2026-11-10; phase 1, plan for the owner's decisions O1, V1, R1, K1). Branch `claude/ecstatic-dubinsky-c24b8a`, after the steam ejector batch.

## Scope

Owner, 2026-11-10: "Resevoir should be rigid when it's filled once (except the floating roof tank)". A closed vessel that has once been full keeps its volume and can stand below the ambient; today (decision D54) it is a bag that shrinks at the ambient.

## Documents

- [RIGID_RESERVOIR_PLAN.md](RIGID_RESERVOIR_PLAN.md): today's bag code, the expected behaviour in numbers (water and crude tanks drawn 10 %, a gravity drain's balance), the `rigid` fact (facts schema 18), the limits, what moves and the least measurement, decisions O1, V1, R1, K1.

## History

- 2026-11-10: plan written.

## Code areas

`science/fluid/solver/PhaseLayout` (wall row), `science/fluid/network/PassiveNetwork` (`Inventory`), `PassiveStepSolver`, `ConservativeTransport`, `NetworkReduction`, `VesselCrystals`, `InventoryEquilibrium`, `runtime/fluid/FluidFacts`, `IslandCertificate`, `FluidWorldAuthority`.

## Open items

- The owner's decisions O1, V1, R1, K1.
