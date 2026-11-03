# 2026-11-03-rotating-machinery

Purpose: realistic pumps and compressors at process level, separate drivers (motor, steam turbine, creative motor) joined by shafts and gearboxes, electricity (FE) for motors and air coolers, Create as an optional power source.

Status: Planned (2026-11-03). Branch `claude/realistic-pump-compressor-6fdfd9`, base `744ef4513` (main, 0.19.3). Waiting for the owner's answers to the plan's questions.

## History

- 2026-11-03: owner request (ten points) and note: "The model shouldn't be too deep into the details, remember it's for chemical engineers". Code read (solver movers, equipment, Create references, the simulator branch); engineering and Minecraft conventions researched; plan revision 1 with forms F1 and F2, questions Q1 to Q10.

## Documents

- [ROTATING_MACHINERY_PLAN.md](ROTATING_MACHINERY_PLAN.md): expected behaviour in numbers, today's code, the machine laws, convergence, shaft trains, equipment, electricity, Create, controls, saves, work packages, owner questions.
- [MACHINE_RESEARCH_REVIEW.md](MACHINE_RESEARCH_REVIEW.md): the research behind the laws and the conversion ratios, with sources.

## Code areas (planned)

`science/fluid/network/PassiveStepSolver` and `FlowControl` (mover laws, energy booking), `ConservativeTransport` (work booking), `runtime/fluid/IslandCoordinator` (driver reads and bookings), `science/equipment/ProcessUnit`, `PumpDesign`, `CompressorDesign`, `CentrifugalCompressorDesign`, `SteamTurbineDesign`, `SteamTurbineTrain`, `CondensingTurbineDesign` (casings, motor, turbine), new `science/machine/**` and `compat/create/**`, `IChemE` (config), `client/EquipmentGhost` and `PipeLayingClient` (Catnip outline), `neoforge.mods.toml`, `build.gradle`.

## Open items

- Owner questions Q1 to Q10 in the plan, section 13.
- Scratch: `tmp/rotating-machinery/` (worked numbers); moves to `tools/rotating-machinery/` at the cleanup.
