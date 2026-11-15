# Phase 1 briefs

Each delegated thread reads "Common rules", then its own section, then [REVIEW_PLAN.md](REVIEW_PLAN.md) sections 1 and 2. Worktree (full path, the folder name does not match the branch): `D:\Minecraft\Modding\1.21\CreateChemE\.claude\worktrees\batch-still-models-b3de99`, branch `claude/codebase-review-refactor-8a85ef`. Production code: `src/main/java/com/wormzjl/icheme/`.

## Common rules (review subagents S1 to S8, P1 to P4)

- Read-only. Write exactly one file, the one your section names, in `documentation/2026-11-13-codebase-review/`. No other write, no Gradle, no test run, no git command that changes anything (no commit, stash, checkout, add), no web access, no further delegation.
- You never wait: no background command, no sleep, no monitor.
- Read your scope's code closely. For code outside your scope read signatures only (`Grep`), and name what you did not read.
- Code guide: `documentation/reference/code-guide/<chapter>.md`. Each chapter opens with long snapshot paragraphs; skip to "Read this when" and the class map. The code is the authority.
- Rules of `AGENTS.md` you must hold to (read these sections): "Saves and data formats", "Engine schedule and player-facing updates", "Physics and defects" with "Thermo validity" and "Small physics simplifications".
- Owner rules not in AGENTS.md:
  - Results are deterministic: the same inputs give the same results on every JDK and platform (`science/math/DeterministicMath`). An optimization may move results; the shift must be small and justified in numbers (owner, 2026-11-13). Conservation of mass, energy and components stays exact.
  - Prefer deleting a rule, mode or switch to adding one. No content hashes. No hold for a data range. Machines at process-simulator depth (curves, efficiency, duty), not mechanical detail. Real equipment gets a 20 % spare.
  - The column solver will be ported to Rust with a Java fallback, and the C++ lane (`src/main/cpp`) is its limited start; the fluid solver follows. Supercritical fluids come later in the thermo engine: name every place that assumes a subcritical state.
- Do not re-derive REVIEW_PLAN section 1. Say where you found it wrong.
- Ruled out as proposals: dropping a saved fact; a migration for a wire protocol; a second reference model; a new top-level folder.

**Output file format**

1. Summary, at most 50 lines: what the area does in three lines, its five largest problems, your ten highest-value changes ranked by gain over cost.
2. Module map: sub-modules you would draw, files and lines in each, and the imports that cross them the wrong way.
3. Findings table: `id | file:line and symbol | what it is | proposed change | class | lines removed or moved | risk | proof`. Class is `STRUCTURE` (code moves, results bitwise identical), `PERF` (same model, results identical or within a stated tolerance), `PHYSICS` (a simplification or model change that moves references; give the expected size at usual states and at the package extremes, what it deletes, what it risks) or `DEFECT`. Proof names the existing test or gate, or the one to add.
4. Open questions for the main thread, at most ten.

Every finding cites a file and line you read. No finding from a name alone. No praise, no restating of the guide.

**Final message to the main thread:** at most 15 lines: the output path, the five top findings, anything that contradicts REVIEW_PLAN sections 1 and 2.

## S1 Thermo core and equilibrium engine (Fable high)

Scope: `science/thermo/**` (`PengRobinsonKernel` 2 077, `TangentPlaneStability` 898, `HuronVidalExcess`, `PairInteractions`, `NumericalBox`, `phase/` 20 files with `FluidTpEquilibrium` 6 290). Guide: `equilibrium-engine.md`, `thermo-materials.md`. Output: `THERMO_CORE_REVIEW.md`.

1. `FluidTpEquilibrium`: its stages (request, stability, flash, three phases, crystals, PH and UV outer loops) with line ranges; the split into files you would make; which state is shared between stages and why.
2. The engine's contract: what `EquilibriumRequest` and `EquilibriumResult` promise; what the package contract (`PhaseContract`, `PhaseDomain`) needs from `science/fluid/thermo` (`CoefficientIdealGas`, `HydrocarbonModel`, `SpinePackageData`, `TranslatedPengRobinson`, `FluidDomain`) and what must move down so the engine imports nothing from `science/fluid`.
3. Supercritical readiness: every `Root.LIQUID`/`Root.VAPOR` request and every branch on a critical temperature or pressure, a saturation curve, or a latent heat; `PhaseKind` and `PhaseIdentification` as they stand; the API change that lets a caller receive "one dense phase" without a special case.
4. A flat-array kernel boundary fit for a Rust port: inputs and outputs of the kernel's evaluation and derivative calls, the workspaces, what allocates per call.
5. Duplicated cubic code (`HydrocarbonModel.pureLiquidCompressibility`, `TranslatedPengRobinson`, the kernel's own roots).
6. Simplifications: iteration ladders, acceleration cycles, tolerances and fallbacks that a simpler rule replaces.

## S2 Thermo adapters, water, material (Opus high)

Scope: `science/fluid/thermo` (13 files, `FluidThermodynamics` 1 556), `science/column/v3/thermo` (18 files), `science/material` (24 files, `MaterialCatalog` 1 445, `DerivedPairs` 822), `science/math`. Guide: `thermo-materials.md`. Output: `THERMO_ADAPTERS_REVIEW.md`.

1. The two adapters on `PengRobinsonKernel` side by side: `V3PengRobinsonThermo`/`V3PengRobinsonSession`/`V3ThermoModel` and `FluidThermodynamics`/`NetworkPhaseEngine`. Per property (K-values, fugacity, enthalpy, volume, heat capacity, ideal gas, water): which code computes it for each solver, whether the values are the same, where they differ and by how much (from the formulas).
2. Water: `V3WaterProperties`, `WaterRegion1`, `WaterSaturation`, the kernel's water share (`WaterShare`), `HenryConstant`. The one water module you would write, who calls it, and what each solver's values would move by.
3. The exact signature of each solver's thermo port after a split: a column port and a network port. Flat arrays where a Rust port needs them.
4. Remove `science/material` → `science/column/v3` (`MaterialPresets`, `MaterialCatalog` line 778, `HenryConstant`) and → `science/fluid/state` (`SolidMaterialCatalog`): the inversion for each.
5. Revisions and identities (`MaterialCatalog.Package.scientificRevision`, `fluidRevision`, `FluidThermodynamics.thermodynamicRevision`): what a move of code does to them, and what a merged adapter needs.
6. `DeterministicMath`, `DeterministicPow`, `FdlibmPow`: the call census by function and what a Rust port must reproduce.

## S3 Fluid step solver (Fable high)

Scope: `science/fluid/network/PassiveStepSolver` (5 752, inner `BalanceNet` at 3404, `Equations` at 4286), `PassiveNetwork`, `NetworkReduction`, `science/fluid/solver` (`SparseNewton`, `PhaseLayout`), `science/fluid/linalg`, `science/fluid/topology/TopologyCompiler`. Guide: `fluid-science.md`. Output: `FLUID_STEP_REVIEW.md`.

1. The step's stages with line ranges (plan, start and `BalanceNet`, equations and rows, Newton passes, polish, ports, heat coupling, observations); the files you would split it into; the state each stage reads and writes.
2. Unknowns and rows: the variable layout, the row kinds, the Jacobian's structure; what a flat state (`double[]`, `int[]` index tables) would look like; what a step allocates today (maps, lists, records, `new double[]` in loops) and what a workspace would keep.
3. Special cases: modes, latches, carried flags and per-device branches. For each: the physical statement it stands for and whether a row or a constitutive law absorbs it.
4. The thermo calls the step makes (which `FluidThermodynamics` and `NetworkPhaseEngine` methods, how often per Newton iteration, on what inputs): this is the network's thermo port.
5. The boundary for a Rust port: one call per island step on flat arrays. What has to cross (topology tables, device laws, thermo, controls), and which callbacks into Java would remain if the port stopped at the linear algebra, at the Newton, or at the whole step.
6. Where a running column, a still, a heat unit and a shaft train enter the step (`ports`, `plan`, `couple`, `MOVER_LAW`), as the list of entry points only.

## S4 Fluid laws, transport, state (Opus high)

Scope: `science/fluid/network` except the S3 files and except `StillColumn`, `StillHead`, `BatchStill` (S8); `science/fluid/transport`, `state`, `conversion`, `diagnostics`. Guide: `fluid-science.md`, `heat-exchangers.md`, `process-control.md`, `cooling-towers.md`. Output: `FLUID_LAWS_REVIEW.md`.

1. The device laws (pipe resistance, valves and `FlowControl`, heat laws, cooling towers, flare, chimney, ejector, open vessels, crystals and solids, `ConservativeTransport`): one row per law with its file, its unknowns, its inputs and its callers.
2. Laws that say the same physics twice (here and in `science/flowsheet/unit`, `science/machine`, `science/equipment`): name both sites.
3. Simplifications with their expected size: negligible terms, modes a law replaces, coarse models that absorb a special case.
4. `ConservativeTransport` (869 lines, 11 maps): what it books, its cost per step, a flat form.
5. Imports from `science/equipment` into these laws (14): which types should live with the law and which with the geometry.

## S5 Column V3 and the Rust port (Fable high)

Scope: `science/column/v3/**` (81 files, 20 001 lines; the numerical core: `V3ColumnCalculator` 3 637, `V3SimultaneousColumnSolver` 1 216, `V3ColumnInitializer` 1 108, `V3AcceptanceAuditor` 1 055, `V3MeshResidualEvaluator` 862, `V3TruncationSupport` 817, `V3BlockJacobianAssembler` 699, `linalg/`, `thermo/`), `NativeColumnSolver`, `src/main/cpp` (742 lines), the `native*` tasks of `build.gradle`. Guide: `column-v3.md`. Outputs: `COLUMN_REVIEW.md` and `RUST_COLUMN_PORT_PLAN.md`.

Known: the C++ lane runs only the PR outer pass of the first rung (`V3MeshResidualEvaluator` lines 83 to 116); its Newton is a dense finite-difference probe; it calls the platform's `cbrt`, `acos` and `cos`, so its results depend on the platform; measured 2026-10-28: no speed-up. This machine has Rust 1.99.1 with the target `x86_64-pc-windows-msvc` only. The game runs on Java 21, where the foreign-function API is a preview: JNI.

1. `COLUMN_REVIEW.md`: the solve's call graph from `V3ColumnCalculator.solve` to the audit; the split of the calculator; the ladder's rungs and budgets; simplifications of the ladder, the truncation support and the audit; what `V3BandedPivotedSolver` and the fluid's `SparseLuSolver` could share.
2. `RUST_COLUMN_PORT_PLAN.md`, detailed enough to build from:
   - the state and problem as flat arrays (the layout of `V3DryMeshState`, `V3StageBlockLayout`, `V3ColumnProblem`), and the single call that carries a whole solve: inputs, outputs, failure codes, the cancel and budget signal (`V3SolveControl`);
   - the crates (math, thermo, column, JNI) and what each holds; which Java classes each stage ports, in order, with the lines of each;
   - stages that each leave a shippable state: (a) the toolchain and the PR batch in Rust, the C++ lane removed; (b) linear algebra and thermo; (c) one rung's residual, Jacobian and Newton; (d) the whole solve; what stays in Java for good (input contract, neural seed, results for display);
   - determinism: no FMA, no fast-math, no platform libm; the port of `DeterministicMath` it needs; one thread per solve;
   - parity with the Java path: results need not be bit-identical; propose the tolerance per quantity (flows, temperatures, duties, accept or refuse) and justify it from the solver's convergence tolerances and the audit's limits; the fixture set (recorded inputs and Java results) and the gate that reads it;
   - packaging: targets (Windows x64, Linux x64 and arm64, macOS x64 and arm64), how each is built, what the jar carries, the loader's change, the version handshake;
   - the cost of two implementations and how a later physics change lands in both.

## S6 Fluid runtime and scheduling (Opus high)

Scope: `runtime/fluid` without the machine classes (S7) and without `ColumnOperation`, `ColumnUnit`, `ColumnPower`, `Stills`, `StillUnit` (S8); `runtime/ProcessSolveServices`, `runtime/*.java`, `network/FluidNetwork`. The large files: `FluidWorldAuthority` 2 898, `IslandCoordinator` 1 458, `FluidFacts` 1 128, `FluidCheckpointCodec` 1 043, `PhysicalFluidTopology` 955. Guide: `fluid-runtime.md`. Output: `RUNTIME_REVIEW.md`.

1. `FluidWorldAuthority`: its responsibilities with line ranges and the split you would make; what every other class reaches into it for.
2. The schedule: how an island step and a column solve share `ProcessSolveServices` (queue, workers, wall budgets, deadlines), where one can starve the other, and what separate budgets would change.
3. Memory: the maps keyed by boxed ids, per-island and per-device objects kept between steps, snapshot copies for presentation; the flat or pooled form.
4. Facts and state: confirm which classes hold facts (`FluidFacts`) and which hold state; a split must not change a saved field. List the format numbers and where each is declared.
5. The server-thread cost: what runs on the tick hook, and any process calculation that runs there against AGENTS.md.

## S7 Rotating machinery (Opus high)

Scope: `science/machine` (18 files), `runtime/fluid/ShaftTrains` 1 160, `ShaftUnit`, `ElectricStores` 602, `PumpHealth` 330, `PrimeMovers`, `Ejectors`, `MachineView`, `MachinePresentation`, `science/fluid/network/ShaftPlan`, `FlowControl.Machine`, `compat/create`, `world/item/RepairKitItem`, the `machines.*` and `energy.*` config in `IChemE.java`. Guide: `rotating-machinery.md`. Batch decisions: `documentation/2026-11-03-rotating-machinery/` (D34 wear), `2026-11-10-prime-movers/` (P11: no overspeed, no trip), `2026-11-10-vacuum-pump/`. Output: `MACHINES_REVIEW.md`.

1. One row per machine kind (centrifugal pump, positive-displacement pump, in-line pump, centrifugal compressor, in-line compressor, liquid ring and dry screw vacuum pumps, steam turbine, motor, generator, gearbox, shaft, combustion engine, gas turbine, creative motor, Create coupling, fans of air coolers and towers, steam ejector): its law class; the operating conditions the solve already computes for it (σ = NPSHA/NPSH3, gas share, surge margin, wetness, load share, speed, temperature); its status words; its trips; its failure modes and rates today.
2. The gaps and incoherences: a fault that damages one machine and is free on another; a trip one driver has and another lacks; rates in play time against process time; status words that differ for the same state.
3. A proposed failure-mode matrix under the owner's rule D34 (wear only from faults the player can see and fix; none from clean running; lives in process time through config; one repair kit; a protective trip prevents the damage, wear is the fault left standing). For each proposed mode: the physics in one line, the driving quantity (which must already be in the solve), the rate law and a life at a typical fault, the status word, and what the player does about it. Mark each as an owner option. Do not contradict P11; say where you would ask the owner to revisit it.
4. Structure and cost: `ShaftTrains` (36 map constructions), `ElectricStores`, `PumpHealth`: what runs per tick, per slice and per booking; the flat form; a `Health` record every machine shares.
5. `science/machine` → `science/flowsheet` (`EjectorLaw`, `sizing.Limit`): the inversion.

## S8 Where the column and the network meet (Opus high)

Scope: `science/column/operation` (12 files), `runtime/fluid/ColumnOperation` 1 117, `ColumnUnit`, `ColumnPower`, `Stills`, `StillUnit`, `science/fluid/network/StillColumn` 1 292, `StillHead`, `BatchStill`, `PortPlan`, the column and still hooks of `IslandCoordinator` (`plans`, `booked`, `Ports`, `decide`), `science/flowsheet/unit/ColumnUnit`. Guide: `column-operation.md`, `fluid-runtime.md` (the coupling), `fluid-science.md` (the still). Output: `PLANT_COUPLING_REVIEW.md`.

1. Every point where column code and fluid code call each other or share a type, as a table: caller, callee, the data that crosses, on which thread.
2. The running column's contract with the network (split table, ports, accounts, duties owed, power): is it a clean boundary, and what leaks through it.
3. `StillColumn` against V3: what it solves, with which thermo calls, and three options with their cost and the size of the result's shift: keep, rebuild on V3's stage model, or a shared stage kernel both use.
4. The shape of a `plant` layer that holds these meeting points and nothing else: its packages and what moves into it.
5. Accuracy trades that would loosen the coupling, each deterministic, with the expected shift.

## P1 Equipment and world (Opus medium)

Scope: `science/equipment` (99 files, 34 915 lines; `ProcessUnit` 4 856, `ShapeRasterizer` 2 037, `EquipmentShape` 1 347, the `*Design` classes), `world/**`, `registry`. Guide: `equipment.md`. Output: `EQUIPMENT_REVIEW.md`.

1. `ProcessUnit` and the `*Design` classes: repeated geometry code, parameter tables that data files or one builder could hold; the split of `ProcessUnit`.
2. Imports of fluid and column types (`HeatSides`, `ColumnDesign`, `ColumnOperatingPoint`, `ExchangerType`, `FlareStackDesign`, `ChimneyDesign`, `CoolingTower*`): which belong to a law, which to geometry.
3. `EquipmentStructure` (1 760) and the block classes: repeated placement, removal and refresh paths.
4. Shape fields are append-only facts: no proposal may change a saved field.

## P2 Client and tutorial (Opus medium)

Scope: `client/**` (195 files, 43 554 lines), `science/tutorial`, `science/display`. Guide: `client-gui.md`, `effects.md`, `tutorial.md`; the skill `.claude/skills/gui-creation/SKILL.md` for the kit's rules. Output: `CLIENT_REVIEW.md`.

1. Screens against the kit: code that re-implements a kit part; the largest screens' split.
2. Draw calls per frame (1.21.1 `GuiGraphics` issues one draw per fill, text or blit) and per-frame allocation in the screens and world renderers.
3. Client copies of server state and their lifetimes; caches that never empty.
4. Dead screens, widgets and render paths.

## P3 Flowsheet, assembly, control, power, instruments (Opus medium)

Scope: `science/flowsheet/**` (87 files), `science/assembly`, `runtime/control`, `runtime/power`, `runtime/assembly`, `runtime/instrument`, `network/` except `FluidNetwork`. Guide: `process-simulator.md`, `machine-assemblies.md`, `wired-control-power.md`, `process-control.md`, `instrument-accessories.md`. Output: `FLOWSHEET_CONTROL_REVIEW.md`.

1. Unit models in `flowsheet/unit` that repeat a network or machine law: both sites, and the one law both should call.
2. Sizing rules in `flowsheet/sizing` that repeat a `*Design` class's numbers.
3. `network/`: packet and codec code that repeats per screen; protocol version strings and where each is declared.
4. The control and power runtimes: what runs per tick, and their stores as facts or state.

## P4 Inventory of tests, build, tools and documents (Sonnet medium)

Scope: `src/test`, `src/fluidGameTest`, `build.gradle`, `tools/`, `documentation/` (listing and sizes; no review of content). Guide: `verification.md`, `tools/INDEX.md`, `documentation/INDEX.md`. Output: `INVENTORY.md`.

1. Test classes by production area and by Gradle task or gate group, with line counts; shared rigs; classes over 1 500 lines.
2. `tools/`: every folder with its tracked size, file count, status in `tools/INDEX.md`, and whether the build, a test or a skill names it; tracked files over 1 MB; vendored dependencies.
3. `documentation/`: batch folders by status, folders with no `README.md`, files over 1 MB, the code guide chapters with the line where the snapshot paragraphs end.
4. `build.gradle`: the task list with what each runs.
Tables only; no proposals beyond "unused", "duplicate" or "over the size rule".

## B1 Profile (sub-thread, Opus medium)

A performance task: times are recorded, each timed run runs alone. Own worktree `.claude/worktrees/codebase-review-bench`, branch `claude/codebase-review-bench` cut from this branch's tip. Scratch in that worktree's `tmp/codebase-review-bench/` (a row in its `tmp/INDEX.md`). Tracked harness in `tools/codebase-review-bench/` with a `README.md` and a row in `tools/INDEX.md`. Output: `documentation/2026-11-13-codebase-review/PROFILE_REVIEW.md`. Commit on its own branch; change nothing under `src/main`.

Rules: AGENTS.md "Gradle, measurement and verification" (at most two Gradle invocations on the machine; a timed run alone; no suite run; no forced rerun). Before each timed run check that no other Gradle daemon is busy and no dev client runs, and that free RAM is over 8 GB. A timed run is 60 s of warm-up and 60 s of measurement, one pair per claim. `python` in Git Bash is a stub: use `py` or PowerShell. A benchmark world needs a fresh `-PfluidBenchmarkRunId`.

Existing harnesses to use before writing any: `fluidServerBenchmark` (`build.gradle`, `-PfluidBenchmarkProfile`, `-PfluidBenchmarkMemory`), `examples/Fluid-Benchmarks.py`, `tools/v3-cold-core-benchmark/` (with its JFR profiler), `tools/simulator-bench/` (JFR, `alloc-sites.js`), `tools/column-operation/` (C1 bench), `tools/fluid-in-game-rig/`.

Scenarios, each with JFR CPU and allocation profiling:
1. Fluid: `fluidServerBenchmark` on its default profile.
2. Column: the bundled `medium_crude` column and the literature preset, cold; one warm re-solve after a 2 % feed change.
3. Machines: a server-side rig with 20 shaft trains (motor, gearbox, pump). If no test rig can be reused within the first hour, report the options instead of building one.

Report, per scenario: wall time per island step or per solve; the top 20 frames by self time, grouped by class; the top 20 allocation sites; bytes allocated per step or per solve; GC share; retained heap per island and per device from a heap histogram. Then one table: the share of time in linear algebra, residual and Jacobian assembly, thermo kernel, flash, bookkeeping (maps, records, streams), other. Also report `rustup target list --installed` and whether `zig` or `cargo-zigbuild` exist; install nothing.

Cadence: message the main thread only on a timed update (at most one every 50 minutes, a few lines), when a decision is needed (at once, with options), and when finished. Between these keep working; do not end the turn for progress notes. Waits on Gradle and benchmark runs: about 2 to 3 hours.
