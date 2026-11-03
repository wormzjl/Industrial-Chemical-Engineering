# 2026-11-03-rotating-machinery

Purpose: realistic pumps and compressors at process level, separate drivers (motor, steam turbine, creative motor) joined by shafts and gearboxes, electricity (FE) for motors and air coolers, Create as an optional power source.

Status: In progress since 2026-11-03 (plan revision 2; W1 built; W2 waits on the owner's form choice, `W2_FORMS_REVIEW.md` section 4). Branch `claude/realistic-pump-compressor-6fdfd9`, base `744ef4513` (main, 0.19.3).

## History

- 2026-11-03: owner request (ten points) and note: "The model shouldn't be too deep into the details, remember it's for chemical engineers". Code read (solver movers, equipment, Create references, the simulator branch); engineering and Minecraft conventions researched; plan revision 1 with forms F1 and F2, questions Q1 to Q10 (5e7f1f84d).
- 2026-11-03: owner answers "Q4 no backward compat, Q6 auto start, Others as recommended": decisions D1 to D9, readings A1 to A4 (`DECISION_LOG.md`); plan revision 2. Owner: "Create duty a bit overrated, research about actual water wheel duty": Create's wheels are 2 m and 4 to 5 m stream wheels (from Create's code); real ones give 0.2 to 0.6 and 0.6 to 1.4 kW. Owner: "FE needs to match SU to prevent loopholes": 2.5 J per FE, one FE per tick for 34.13 su (the Create addons' rate), so 1.46 W per su (D7 revised). W4 (equipment) and W6a (Create and KubeJS optional) proposed as their own sessions; W1 and W2 here.
- 2026-11-03: W1, `science/machine/**` (Minecraft-free, deterministic math): `CentrifugalPump`, `CentrifugalCompressor`, `PositiveDisplacement`, `SteamTurbine`, `Motor`, `SpeedControl`, with 28 unit tests against the plan's worked numbers (all pass; `DeterministicMathSourceTest` passes on the new files). Efficiencies of a design (decision A5, plan section 3.5): Corripio for pumps, the IE3 table by pole count (checked whole against EU 2019/1781), a single-stage to multistage blend for turbines.
- 2026-11-04: W2 measurement point (218f9c5ff): `MachineLaw`; machines on their curves (`FlowControl.Machine`, mode `MOVER_LAW`, one row head − rise(Q) in place of the target and head-limit pair); pumps book the ideal work, compressors the polytropic work at η_p 0.78 with k from the suction per step; checkpoint version 31; the facts refuse a machine until schema 8 (W3); forms F1 (default) and F2 behind `icheme.fluid.cornerLaw`. Its tests pass except `CompressorTest`'s 5 s transfer under F1 (6.87 Pa short of 3 Pa: the switch latches `CLOSED`).
- 2026-11-04: quick check (`tools/rotating-machinery/`) of F1, F2 and the centrifugal variant Q3. F2 held cold seed 200 (singular Jacobian: the norm residual has no gradient at no flow and no rise), so F2b (ffae9fc4d) states the corner as a finite rise with a knee line; F2b holds as the baseline and lands the transfer; Q3 cold seed 6 is clean alone. `W2_FORMS_REVIEW.md`: options A (F2b, recommended under D1) and B (F1) for the owner.

## Documents

- [ROTATING_MACHINERY_PLAN.md](ROTATING_MACHINERY_PLAN.md): expected behaviour in numbers, today's code, the machine laws, convergence, shaft trains, equipment, electricity, Create, controls, saves, work packages, owner questions.
- [MACHINE_RESEARCH_REVIEW.md](MACHINE_RESEARCH_REVIEW.md): the research behind the laws and the conversion ratios, with sources.
- [DECISION_LOG.md](DECISION_LOG.md): the owner's decisions and the readings taken under the standing instruction.
- [W2_FORMS_REVIEW.md](W2_FORMS_REVIEW.md): the quick check of forms F1, F2, F2b and the centrifugal variant, and the form proposal.

## Code areas (planned)

`science/fluid/network/PassiveStepSolver` and `FlowControl` (mover laws, energy booking), `ConservativeTransport` (work booking), `runtime/fluid/IslandCoordinator` (driver reads and bookings), `science/equipment/ProcessUnit`, `PumpDesign`, `CompressorDesign`, `CentrifugalCompressorDesign`, `SteamTurbineDesign`, `SteamTurbineTrain`, `CondensingTurbineDesign` (casings, motor, turbine), new `science/machine/**` and `compat/create/**`, `IChemE` (config), `client/EquipmentGhost` and `PipeLayingClient` (Catnip outline), `neoforge.mods.toml`, `build.gradle`.

## Open items

- Owner: form A (F2b) or B (F1), `W2_FORMS_REVIEW.md` section 4.
- W2 rest: delete the other form and restate `CompressorTest` for it; one full campaign of the chosen form; the turbine connection (plan 3.4). Q3's cold worlds cost 34 % more backtracks than F1.
- W3, W5 and W7 to build (plan section 12); W8 after the simulator merges; W9 only when the owner says so. W4 runs as its own session; W6a is done on `claude/interesting-wozniak-cb5e6a` (43731e4d3, 2b2339d59), not merged.
- Scratch: `tmp/rotating-machinery/` (worked numbers, campaign reports, test logs, the `CompressorTest` corner-law probe); the measurement tools are in `tools/rotating-machinery/`.
