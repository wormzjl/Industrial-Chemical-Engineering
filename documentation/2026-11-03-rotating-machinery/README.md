# 2026-11-03-rotating-machinery

Purpose: realistic pumps and compressors at process level, separate drivers (motor, steam turbine, creative motor) joined by shafts and gearboxes, electricity (FE) for motors and air coolers, Create as an optional power source.

Status: Planned (plan revision 2, 2026-11-03); implementation starts with W1. Branch `claude/realistic-pump-compressor-6fdfd9`, base `744ef4513` (main, 0.19.3).

## History

- 2026-11-03: owner request (ten points) and note: "The model shouldn't be too deep into the details, remember it's for chemical engineers". Code read (solver movers, equipment, Create references, the simulator branch); engineering and Minecraft conventions researched; plan revision 1 with forms F1 and F2, questions Q1 to Q10 (5e7f1f84d).
- 2026-11-03: owner answers "Q4 no backward compat, Q6 auto start, Others as recommended": decisions D1 to D9, readings A1 to A4 (`DECISION_LOG.md`); plan revision 2. Owner: "Create duty a bit overrated, research about actual water wheel duty": Create's wheels are 2 m and 4 to 5 m stream wheels (from Create's code); real ones give 0.2 to 0.6 and 0.6 to 1.4 kW; the Create rate is now 1.5 W per su, FE stays 10 J per FE (D7 revised). W4 (equipment) and W6a (Create and KubeJS optional) proposed as their own sessions; W1 and W2 here.
- 2026-11-03: W6a, Create and KubeJS optional (43731e4d3, branch `claude/interesting-wozniak-cb5e6a`): mods.toml `optional`; Create, Ponder, Flywheel, Registrate, KubeJS `compileOnly` plus `optionalModRuntime` (dev runs, GameTests, tests), `-PwithoutCreate` drops them; `client/Outlines` replaces Catnip's outliner in `EquipmentGhost` and `PipeLayingClient` (Catnip's look read from its bytecode: 1/16 and 1/32 bars, full bright, 8-tick fade, gliding boxes); constructor on the calculator's `computer_*` textures, interior particle `vessel_shell`; the `BlockMovementChecks` assertion runs only with Create. Compile of all source sets; fluid GameTests 39/39 with Create (run `w6a-create`) and 39/39 without it (run `w6a-nocreate`, four mods loaded). No unit test loads the changed classes; no lang change; outline look not checked in game.

## Documents

- [ROTATING_MACHINERY_PLAN.md](ROTATING_MACHINERY_PLAN.md): expected behaviour in numbers, today's code, the machine laws, convergence, shaft trains, equipment, electricity, Create, controls, saves, work packages, owner questions.
- [MACHINE_RESEARCH_REVIEW.md](MACHINE_RESEARCH_REVIEW.md): the research behind the laws and the conversion ratios, with sources.
- [DECISION_LOG.md](DECISION_LOG.md): the owner's decisions and the readings taken under the standing instruction.

## Code areas (planned)

`science/fluid/network/PassiveStepSolver` and `FlowControl` (mover laws, energy booking), `ConservativeTransport` (work booking), `runtime/fluid/IslandCoordinator` (driver reads and bookings), `science/equipment/ProcessUnit`, `PumpDesign`, `CompressorDesign`, `CentrifugalCompressorDesign`, `SteamTurbineDesign`, `SteamTurbineTrain`, `CondensingTurbineDesign` (casings, motor, turbine), new `science/machine/**` and `compat/create/**`, `IChemE` (config), `client/EquipmentGhost` and `PipeLayingClient` (Catnip outline), `neoforge.mods.toml`, `build.gradle`.

## Open items

- W1 to W7 to build (plan section 12); W8 after the simulator merges; W9 only when the owner says so.
- W6a: the outline look (colours, widths, fade) is untested in game; offer one check to the owner with the W7 screens. W6b adds the Create coupling under `compat/create/**`.
- Scratch: `tmp/rotating-machinery/` (worked numbers); moves to `tools/rotating-machinery/` at the cleanup.
