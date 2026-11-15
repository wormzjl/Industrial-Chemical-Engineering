# J2-A report: `fluid-science.md` and `fluid-runtime.md` to the template

Scope J2-A of [WAVE_D_BRIEFS.md](../WAVE_D_BRIEFS.md), "J2". Documents only: no code, no build, no test run. Base `d6f721b94`; commits `48ee3d0b3` (fluid-science) and `68cb72ae4` (fluid-runtime).

## Chapters made

| Chapter | KB | Packages (every file in its class map) |
|---|---|---|
| [fluid-step.md](../../reference/code-guide/fluid-step.md) | 33.9 | `science/fluid/network`: `PassiveStepSolver`, `StepEquations`, `BalanceStart`, `StartSeeds`, `MoverSuction`, `PhaseActiveSet`, `OneWayClosures`, `DeviceModes`, `StepObservations`, `PortStreams`, `ReleasePressure` (11) |
| [fluid-solver.md](../../reference/code-guide/fluid-solver.md) | 13.9 | `science/fluid/solver`, `linalg`, `diagnostics`, `SolverOwnership` (8) |
| [fluid-thermo-port.md](../../reference/code-guide/fluid-thermo-port.md) | 19.2 | `science/fluid/thermo`, `state` (17) |
| [fluid-laws.md](../../reference/code-guide/fluid-laws.md) | 28.8 | `science/fluid/network` devices, vessels, heat laws, burners, flare, chimney, towers; `transport`, `conversion` (37) |
| [fluid-interval.md](../../reference/code-guide/fluid-interval.md) | 24.1 | `science/fluid/network` interval, booking, refresh, crystals, reduction, snapshot; `topology`; `SimulationTime` (12) |
| [runtime-engine.md](../../reference/code-guide/runtime-engine.md) | 24.1 | coordinator, coupling, certificates, scheduler, clock, retained solver, island sets, reload guard, diagnostics; `runtime/ProcessSolve*`, `BoundedCpuSolveService`, `WorkerAllocation` (17) |
| [runtime-registry.md](../../reference/code-guide/runtime-registry.md) | 25.3 | `FluidWorldAuthority`, `Placement`, `ChunkBinding`, `PhysicalRegistry`, `PhysicalFluidTopology`, `WorldTopologyLedger`, `TopologyChange`, specs, presets, held devices, slurry feed (11) |
| [runtime-saves.md](../../reference/code-guide/runtime-saves.md) | 18.5 | saved data, codec, store, `FluidFacts` and its six sections (11) |
| [runtime-presentation.md](../../reference/code-guide/runtime-presentation.md) | 19.0 | buckets, `ViewBuilder`, `FluidView`, `Display(s)`, tank, pipe, heat and machine presentation, `Noise`, `Silhouettes`, `runtime/PresentationMailbox` (14) |
| [runtime-units.md](../../reference/code-guide/runtime-units.md) | 22.2 | columns, stills (with the still's law and column, which have no chapter of their own), converters, ejectors, fired heaters, flares, chimneys, prime movers, shafts, stores, health, heat units, alarms (18) |

Each follows plan 2.2's sections in order; constants are given by name, formats as links to `FORMATS.md`. `fluid-science.md` and `fluid-runtime.md` are removed (no name kept).

## Moved to HISTORY.md

One block at its end, `## From the chapters of scope J2-A`, with `### fluid-science.md` and `### fluid-runtime.md`: 112 lines (each subsection a typed list of the cut sentences, then verbatim lines of the old chapter: the 0.9.2 and 0.25.0 test lists; the state format and facts schema histories, the corpus list, the process-control paragraph, the two paragraphs on control and power integration that belong to other chapters, the design background). No other part of `HISTORY.md` changed.

## Corrected against the code (main cases)

- Sealed pockets: `relieveSealedPockets` closes a running mover with a sealed side; there is no `yieldPrescribed`, no head-limit mode.
- The balance's tables and the suction floors' caches are static and shared (`BalanceNet.TABLE_CACHE`, `MoverSuction`); "per solver, P-09" was taken back out.
- `HydrocarbonModel` is the derived cubic with every phase at the state pressure and water in the kernel; the two-path, partial-pressure text was stale. The vapour's velocity limit reads the cubic's compliance (no steam virial term).
- `FlowControl.Mode` has 8 constants (a trap said 9); `ValveSource.planned()` lists eight sources.
- Removed members cut: `FreeWaterIce`, `omittedTrace`, `OMITTED_TRACE_VOLUME`, `omittedArrival`, `idealGasFitRange`, `coefficientEvaluator`.
- Structure entry points are in `StructureRegistration`, `StructureRefresh`, `StructureRealignment`; `notices` is `ProcessSolveJobs`'; the pool's entry is `trySubmit`; `PackageRecord` is the codec's; `EquipmentNetwork.PROTOCOL` is `NetworkProtocol.VERSION`; test names fixed (`FluidPacketCodecTest`, a removed codec test).
- The solid integrator's water test reads an aqueous phase.

## Checks (scripts under `tmp/j2a/`, not tracked)

`node tmp/j2a/check.mjs <chapter>...` (size, relative links, class-like identifiers and their `Class.member` segments against the sources, class map against the packages named in each chapter's `j2a-packages` comment): 10 chapters, 285 relative links all resolve, 1,497 class-like identifiers all resolve, 156 of 156 package files in the class maps, every chapter under 40 KB. `node tmp/j2a/files.mjs <packages>` gives the line counts. `node tools/code-guide/map.mjs --check`: counts current.

## For the main thread (not edited here)

README rows ([README.md](../../reference/code-guide/README.md)):
- Code map line 51 (`science/fluid/**`): five rows, step → fluid-step, solver → fluid-solver, thermo/state → fluid-thermo-port, laws/transport/conversion → fluid-laws, interval/topology → fluid-interval. Line 53 (`runtime/**`): five rows to runtime-engine, -registry, -saves, -presentation, -units. Line 66 (batch still): runtime-units.md for both links.
- Routing lines 77 → fluid-step.md (flow, holds) and fluid-interval.md (conservation); 78 → runtime-registry.md; 79 → fluid-thermo-port.md; 80 → runtime-presentation.md (buckets) and runtime-engine.md (threads); 81 → runtime-saves.md; 82 → runtime-registry.md; 84 → fluid-laws.md; 85 → runtime-registry.md; 86 → fluid-laws.md (heat in the step), fluid-interval.md (booking), runtime-engine.md (coupling); 88 → fluid-step.md; 93 → fluid-laws.md.
- Reading path "Fluid" (line 100) is stale (`PassiveStepSolver` 4.9 k lines with inner `BalanceNet` and `Equations`): `PhysicalRegistry` → `PhysicalFluidTopology.compile` → `IslandCoordinator` → `PassiveIntervalSolver` → `PassiveStepSolver` (`BalanceStart`, `StepEquations`) → `ConservativeTransport` → `FluidPresentation`. Outside J2-A: the "Equilibrium" path still says 4.7 k lines.

Links to retarget (74; no anchors; none in `AGENTS.md`, the skills or `COLLABORATION.md`; plain mentions in batch documents are history and stay):
- client-gui 21, 27, 399, 410 → runtime-presentation; 406 → fluid-thermo-port.
- column-operation 9 → fluid-step; 16 → runtime-engine; 109 → fluid-step; 198 → fluid-laws; 242 → fluid-interval; 311 → fluid-step.
- cooling-towers 31 → fluid-interval; 43 → runtime-saves.
- equilibrium-engine 14 → fluid-thermo-port; 216 → fluid-thermo-port; 350 → fluid-thermo-port; 504 → fluid-thermo-port.
- equipment 17, 174 → runtime-registry; 193 → runtime-saves; 260 → runtime-registry.
- heat-exchangers 15 → fluid-laws and runtime-engine; 198 → fluid-laws; 210 → fluid-laws and runtime-units.
- instrument-accessories 11, 28, 122, 125 → runtime-registry.
- machine-assemblies 16 → runtime-registry; 132 → runtime-presentation (and drop "protocol fluid-16").
- pipe-laying 3, 30 → runtime-registry.
- process-control 22 → fluid-step; 24 → runtime-registry; 68 → fluid-step (its omitted-trace sentence is stale: no trace is omitted since W3b); 72 → runtime-saves.
- process-simulator 262 → runtime-units.
- rotating-machinery 14 → fluid-step and runtime-engine; 44, 77, 159, 187 → fluid-laws; 112 → runtime-saves; 136 → fluid-interval.
- thermo-materials 13, 84, 183, 296, 487, 549 → fluid-thermo-port; 190 → runtime-engine; 569 → fluid-laws.
- verification 66 → runtime-registry; wired-control-power 43 → runtime-registry.
- README lines above (16 links); `2026-09-24-coolprop-low-temperature/MERGE_STACK_DRAFTS.md` 1358 is a draft of an old routing row: leave it.

Plan 2.5: the numbers of `fluid-runtime.md` are gone (format values are links); "three job families" stands in the chapters and in `ProcessSolveServices`' class comment. The other stale texts of 2.5 are outside J2-A.

## Left

- The still's law and column (`science/plant/still`, `science/column/still`) have no chapter of their own; their text is in runtime-units.md for now.
- The old runtime paragraphs on `ControlService` and its parts and on a motor's live supply at the shaft decisions are kept verbatim in `HISTORY.md` for [wired-control-power.md](../../reference/code-guide/wired-control-power.md) and [rotating-machinery.md](../../reference/code-guide/rotating-machinery.md) to check.
- Lower-case members were spot-checked (about 150), not all; the "Terms" sections were dropped (not in the template).
