# 2026-11-03-rotating-machinery

Purpose: realistic pumps and compressors at process level, separate drivers (motor, steam turbine, creative motor) joined by shafts and gearboxes, electricity (FE) for motors and air coolers, Create as an optional power source.

Status: Planned (plan revision 2, 2026-11-03); implementation starts with W1. Branch `claude/realistic-pump-compressor-6fdfd9`, base `744ef4513` (main, 0.19.3).

## History

- 2026-11-03: owner request (ten points) and note: "The model shouldn't be too deep into the details, remember it's for chemical engineers". Code read (solver movers, equipment, Create references, the simulator branch); engineering and Minecraft conventions researched; plan revision 1 with forms F1 and F2, questions Q1 to Q10 (5e7f1f84d).
- 2026-11-03: owner answers "Q4 no backward compat, Q6 auto start, Others as recommended": decisions D1 to D9, readings A1 to A4 (`DECISION_LOG.md`); plan revision 2. Owner: "Create duty a bit overrated, research about actual water wheel duty": Create's wheels are 2 m and 4 to 5 m stream wheels (from Create's code); real ones give 0.2 to 0.6 and 0.6 to 1.4 kW. Owner: "FE needs to match SU to prevent loopholes": 2.5 J per FE, one FE per tick for 34.13 su (the Create addons' rate), so 1.46 W per su (D7 revised). W4 (equipment) and W6a (Create and KubeJS optional) proposed as their own sessions; W1 and W2 here.
- 2026-11-03 (W4, branch `claude/sad-elgamal-92e0e4`): casings without drivers (pump, reciprocating and centrifugal compressor), one steam turbine (condensing turbines alias to it with a 10 kPa exhaust), new kinds motor, generator and gearbox, shaft ends (`EquipmentShape.ShaftEnd`, `ShapeRasterizer.shafts`, `terminal`), shape fields 6 (`MachineDuty`, corpus `shapes-6.snbt`), shaft and creative motor blocks, 45 new lang keys in five languages. Nozzles: every pump, compressor and centrifugal-compressor nozzle stays (the compressor's bottles keep their old reference height, 0.5 + 0.35 b, with the crank on 0.5 m); 19 of 25 back-pressure turbine sizes move the exhaust one block and every condensing turbine's steam inlet moves (D5) (`tools/machine-nozzle-probe/`). Gates: 33 affected test classes pass (equipmentGates and quick suite, `--tests`), `UnitTextTest`, `LanguageFilesTest`. Renders `research/2026-11-03-rotating-machinery/renders/machines-w4.png`; constructor duty rows await the owner's pick of `duty-options-w4.png` (A keys, B steppers, C speed stepper).

## Documents

- [ROTATING_MACHINERY_PLAN.md](ROTATING_MACHINERY_PLAN.md): expected behaviour in numbers, today's code, the machine laws, convergence, shaft trains, equipment, electricity, Create, controls, saves, work packages, owner questions.
- [MACHINE_RESEARCH_REVIEW.md](MACHINE_RESEARCH_REVIEW.md): the research behind the laws and the conversion ratios, with sources.
- [DECISION_LOG.md](DECISION_LOG.md): the owner's decisions and the readings taken under the standing instruction.

## Code areas (planned)

`science/fluid/network/PassiveStepSolver` and `FlowControl` (mover laws, energy booking), `ConservativeTransport` (work booking), `runtime/fluid/IslandCoordinator` (driver reads and bookings), `science/equipment/ProcessUnit`, `PumpDesign`, `CompressorDesign`, `CentrifugalCompressorDesign`, `SteamTurbineDesign`, `SteamTurbineTrain`, `CondensingTurbineDesign` (casings, motor, turbine), new `science/machine/**` and `compat/create/**`, `IChemE` (config), `client/EquipmentGhost` and `PipeLayingClient` (Catnip outline), `neoforge.mods.toml`, `build.gradle`.

## Open items

- W1 to W7 to build (plan section 12); W8 after the simulator merges; W9 only when the owner says so.
- Scratch: `tmp/rotating-machinery/` (worked numbers); moves to `tools/rotating-machinery/` at the cleanup.
