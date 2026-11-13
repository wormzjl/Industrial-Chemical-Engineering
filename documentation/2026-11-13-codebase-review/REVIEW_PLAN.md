# Code base review and revamp: plan

Base `259d22a2c` (main 0.30.0), branch `claude/codebase-review-refactor-8a85ef`, 2026-11-13. Owner request: review the whole code base (performance, coherence, maintainability), de-correlate the fluid network solver and the column solver, evaluate a Rust port with a Java fallback, leave room for supercritical fluids, bench and optimize the rotating machines and make their failure modes complete and coherent, propose a better documentation structure and a multi-agent collaboration guide.

This plan covers phase 1 (review and measurement, no production code change) and names the documents it ends in. Phase 2 (implementation) is a set of separate batches the owner approves from those documents.

## 1. What the survey found (main thread, 2026-11-13)

Sizes: 861 production files, 208 131 lines; 622 test files, 118 164 lines; 45 GameTest files.

| Area | Files | Lines | Largest file |
|---|---|---|---|
| `client/**` | 195 | 43 554 | `FluidDeviceScreen` 1 581 |
| `science/equipment` | 99 | 34 915 | `ProcessUnit` 4 856 |
| `science/fluid/**` | 75 | 22 220 | `PassiveStepSolver` 5 752 |
| `science/column/**` | 93 | 22 008 | `V3ColumnCalculator` 3 637 |
| `runtime/fluid` | 50 | 18 747 | `FluidWorldAuthority` 2 898 |
| `science/thermo/**` | 29 | 12 883 | `FluidTpEquilibrium` 6 290 |
| `world/**` | 70 | 12 606 | `EquipmentStructure` 1 760 |
| `science/flowsheet/**` | 87 | 11 758 | |
| `science/material` | 24 | 5 599 | `MaterialCatalog` 1 445 |
| `science/machine` + runtime machine classes | 18 + 8 | 2 082 + 2 766 | `ShaftTrains` 1 160 |

Structural findings (from the import matrix; each is a fact to confirm, not yet a proposal):

1. **Layering is inverted in the thermo stack.** `science/thermo/phase` (the equilibrium engine) imports `science/fluid/thermo` (`CoefficientIdealGas`, `HydrocarbonModel`, `SpinePackageData`, `TranslatedPengRobinson`, `FluidDomain`). `science/material` imports `science/column/v3` (`MaterialPresets`, `MaterialCatalog` → `V3ColumnProblemResolver`, `HenryConstant` → `V3WaterProperties`) and `science/fluid/state` (`SolidInventory`).
2. **Water is a cycle between the two solvers.** `V3WaterProperties` (column) imports `WaterRegion1` (fluid); `FluidThermodynamics` calls `V3WaterProperties` at five sites by full name. This is the direct column ↔ fluid correlation in code.
3. **Two property adapters on one kernel.** `V3PengRobinsonThermo`/`V3PengRobinsonSession` (column) and `FluidThermodynamics`/`NetworkPhaseEngine`/`FluidTpEquilibrium` (fluid) both wrap `PengRobinsonKernel`. A kernel, water or pair change moves both reference sets (column digests; 33 junction lines, chain-100, campaign).
4. **A second column solver lives in the fluid network.** `science/fluid/network/StillColumn` (1 292 lines) on `PengRobinsonKernel` directly.
5. **Two linear-algebra and Newton stacks.** `V3BandedPivotedSolver` + the column's Newton; `SparseLuSolver` + `SparseNewton`.
6. **The meeting points of the solvers are spread out.** `science/column/operation` → fluid (`HeatCurve`, `FluidThermodynamics`); `runtime/fluid` holds `ColumnOperation`, `ColumnUnit`, `Stills`; one worker pool and queue for V3 solves and island steps (`ProcessSolveServices`).
7. **Other inversions.** `science/machine` → `science/flowsheet` (`EjectorLaw`, `sizing.Limit`); `science/equipment` → `science/fluid/network` (14 imports, `HeatSides` builds the heat laws) and → `science/column/v3` (9).
8. **Monoliths.** Five files over 2 800 lines; `PassiveStepSolver` has lines of 705 characters, 12 map and 74 list constructions, 100 `new double[]`; `IslandCoordinator` 20 maps and 20 streams; `ShaftTrains` 36 maps.
9. **Native lane.** `src/main/cpp` (742 lines, JNI) covers only the PR outer pass of the column's first rung; measured 2026-10-28: no speed-up, products to 7e-13 relative. Rust 1.99.1 is installed on this machine.
10. **Wear exists for pumps only** (`Wear`: cavitation by Gülich's intensity, running dry; `PumpHealth`). Compressors, vacuum pumps, turbines, motors, generators, gearboxes, engines and gas turbines have status words and trips but no health.
11. **Documentation.** `code-guide/README.md` is 398 lines and about 40 000 tokens: over the 25 000-token cap of an agent's file read, so "read README.md first" cannot be done in one read. It and the chapters open with snapshot paragraphs that grow at every merge (a change log inside the guide). `documentation/` has 141 batch folders.
12. **`tools/`** has 176 folders and 15 012 tracked files, about 500 MB (`junction-holdup-prototype` 110 MB, `new-units` 84 MB, `custom-equipment-rendering-poc` 55 MB, `pump-compressor-controls` 43 MB with a vendored numpy and its DLLs).

## 2. Direction (to be confirmed or changed by the reviews)

Target dependency order in `science/`, one way only:

```
math (deterministic math, banded and sparse LU, Newton)
  └ thermo core (kernel, mixing rules, ideal gas, water, crystals, stability and flash, phase descriptors)
      ├ material (records, catalog, derived pairs)        no solver type
      ├ column (V3; the still's small column)            thermo core + math
      ├ fluid (network step)                             thermo core + math + machine laws
      └ machine (laws, health)                           math
  plant (column operation, heat units, stills, shaft plans)   the only place column and fluid meet
  flowsheet, equipment geometry                               above plant
```

- **De-correlation.** Each solver reads the thermo core through its own narrow port (column: K-values, enthalpies and their derivatives at T, P, x, y; network: TP/PH/UV requests and phase properties). The core carries one property identity; a solver's references move only when its own port's values move. Accuracy trades to evaluate, each deterministic: one water model for both solvers; the still on the column's stage model; a cheaper property path for the network where the full engine is not needed.
- **Supercritical room.** Solvers stop asking the kernel for a `Root.LIQUID` or `Root.VAPOR`; they ask for a phase slot and receive a phase descriptor (kind, density, properties) that can say "one dense phase". Review S1/S2 lists every site that assumes a subcritical state (root requests, saturation curves, latent heats, the bubble-pressure suction floor, `t >= Tc ? ∞` branches).
- **Rust.** Port by kernel, not by solver, behind flat-array interfaces (`double[]`/`int[]` in and out, no callbacks, coarse calls): first linear algebra, then the PR78 kernel and fugacity derivatives, then flash inner loops, then a whole column Newton or island step once their state is flat. JNI (Java 21 has the foreign-function API only as preview). The Rust code ports `DeterministicMath` operation for operation, no FMA, no fast-math, so it is bit-identical to the Java path; the Java path stays as the fallback and as the oracle of a parity gate. The C++ lane retires (one native toolchain). Whether any step is worth its double maintenance is decided from the profile (B1): the flat-array rewrite in Java is expected to give most of the CPU and RAM gain by itself.
- **Machines.** One health record for every rotating machine, the pump's rule kept (owner D34: wear only from faults the player can see and fix, never from clean running; lives in process time; one repair kit). Draft matrix to verify in S7: compressor: surge and liquid carry-over; vacuum pump: seal-liquid loss (liquid ring), liquid slug or over-temperature (dry screw); steam turbine: wet exhaust; motor and generator: overload heating; gearbox: over-torque; engine and gas turbine: per owner P11 no overspeed and no trip, so only what a fuel or load fault can cause; ejectors and static equipment: none. A protective trip prevents damage; wear is what happens when the fault is left standing.

## 3. Phase 1 work packages

Every reviewer is read-only on `src/`, runs no Gradle, and writes one file in this folder: a summary of at most 50 lines, then a findings table (id, file and symbol, what it is, proposed change, effect, risk). Every brief names the AGENTS.md sections it must hold to ("Saves and data formats", "Physics and defects", "Thermo validity", "Small physics simplifications") and what section 1 already established.

| Id | Scope (close read) | Questions | Output | Model |
|---|---|---|---|---|
| S1 | `science/thermo/**` (kernel, `TangentPlaneStability`, `phase/` with `FluidTpEquilibrium`) | Decomposition of the 6 290-line engine; the engine's contract; what must move down from `fluid/thermo`; supercritical readiness list; flat-array kernel boundary; simplifications | `THERMO_CORE_REVIEW.md` | Opus high |
| S2 | `science/fluid/thermo`, `science/column/v3/thermo`, `science/material`, `science/math` | Duplication between the two adapters; one water module; removing material → column/fluid imports; the two solver ports' exact signatures; revision and identity effects | `THERMO_ADAPTERS_REVIEW.md` | Opus high |
| S3 | `PassiveStepSolver`, `fluid/solver`, `fluid/linalg`, `NetworkReduction`, `TopologyCompiler`, `PassiveNetwork` | Split by stage (start, equations, polish, ports, heat coupling); state as flat arrays; allocation per step; special cases a stage absorbs; kernel boundary | `FLUID_STEP_REVIEW.md` | Opus high |
| S4 | the rest of `science/fluid/network`, `transport`, `state`, `conversion` (with `StillColumn`, `ConservativeTransport`, heat laws, `FlowControl`) | Consolidation of laws; `StillColumn` against a V3 stage model; what belongs to a `plant` layer; simplifications with their expected size | `FLUID_LAWS_REVIEW.md` | Opus high |
| S5 | `science/column/v3/**`, `science/column/operation`, `src/main/cpp` | Split of `V3ColumnCalculator`; shared linear algebra; the column's thermo port; ladder and audit simplifications; what the native lane should become | `COLUMN_REVIEW.md` | Opus high |
| S6 | `runtime/fluid` (without the machine classes), `runtime/ProcessSolveServices`, `network/FluidNetwork` | Split of `FluidWorldAuthority`; column and island scheduling on one pool; memory per island and per device (maps, boxed keys); formats and facts untouched by a split | `RUNTIME_REVIEW.md` | Opus high |
| S7 | `science/machine`, `ShaftTrains`, `ShaftUnit`, `ElectricStores`, `PumpHealth`, `PrimeMovers`, `Ejectors`, `ShaftPlan`, `FlowControl.Machine`, `compat/create` | Per machine: the conditions the solve already knows (σ, surge margin, wetness, load, speed), its trips and status words, its failure modes present and missing, rates in process time; coherence table; hot paths and maps | `MACHINES_REVIEW.md` | Opus high |
| P1 | `science/equipment`, `world/**`, `registry` | `ProcessUnit` (4 856 lines) and the `*Design` classes: repeated geometry code, tables that data can hold; imports of fluid and column types | `EQUIPMENT_REVIEW.md` | Opus medium |
| P2 | `client/**`, `science/tutorial` | Repeated screen code against the kit, draw-call cost, dead screens and widgets, client state copies | `CLIENT_REVIEW.md` | Opus medium |
| P3 | `science/flowsheet/**`, `science/assembly`, `runtime/control`, `power`, `assembly`, `instrument` | Unit models that repeat a network law; sizing rules that repeat a design class; one law per physical thing | `FLOWSHEET_CONTROL_REVIEW.md` | Opus medium |
| P4 | `src/test`, `build.gradle`, `tools/`, `documentation/` (inventory only) | Test classes by area and gate; rigs that repeat; `tools/` folders by size, last use and status; batch folders by status; files over 1 MB | `INVENTORY.md` | Sonnet medium |
| B1 | sub-thread, own worktree `.claude/worktrees/codebase-review-bench` | CPU and allocation by class with JFR, each run alone (a performance task: times are recorded): `fluidServerBenchmark` 60 s + 60 s; chain-100; one bundled crude column cold and warm; a world with 20 shaft trains; heap per island and per device | `PROFILE_REVIEW.md`, harness in `tools/codebase-review-bench/` | Opus medium |

Ruled out for the reviewers: re-deriving section 1; proposing a change that drops a saved fact; proposing a hold for a data range; proposing a content hash; a second reference model.

## 4. Main-thread synthesis (after the packages report)

1. `CODEBASE_REVIEW.md`: module breakdown, structural issues ranked by cost and gain, consolidation list.
2. `SOLVER_DECOUPLING_PLAN.md`: target packages, the two thermo ports, the move order (each move bitwise identical where it only moves code), the accuracy trades as owner options with numbers, the Rust decision from B1, the supercritical readiness list.
3. `MACHINE_FAILURE_MODES_REVIEW.md`: expected behaviour, what the code does, the proposed matrix and rates as owner options.
4. `SIMPLIFICATIONS_REVIEW.md`: every physics simplification with its effect at usual states and package extremes, what it deletes, what it risks.
5. `DOCS_AND_COLLABORATION_PLAN.md` and the collaboration guide (`documentation/reference/COLLABORATION.md`): module owners and hot files, number allocation for schemas and protocols, batch sizing, briefs, the lane; the code guide's new shape (README under 150 lines, history out of the chapters, counts generated by a script).
6. Phase 2 roadmap: implementation batches in dependency order, each with its gates and its final lane.

## 5. Where AGENTS.md and CLAUDE.md do not fit this job

1. **One approval per delegation** (CLAUDE.md): phase 1 is 11 subagents and one sub-thread, and a reviewer idle for 5 minutes cannot take a follow-up question. Asked: one approval for the table above, and a standing approval for at most 6 follow-up read-only review subagents (Opus high or lower, no Gradle) inside this batch.
2. **Reports of at most 50 lines** (AGENTS.md): a module review needs a findings table. Used here: a summary of at most 50 lines, the table after it.
3. **Times only in a performance task** (AGENTS.md): B1 and the machine bench are declared performance tasks; each timed run runs alone.
4. **Simplifications need the owner's approval** (AGENTS.md) against "what it's for and how it's built are your call" (this request). Used here: a change that keeps results bitwise identical is the agent's call; a change that moves a reference (a simplification, an accuracy trade, a new failure mode or rate) is an owner option with numbers.
5. **"Read `README.md` first"** (AGENTS.md, "Code guide") cannot be followed in one read (finding 11).
6. **No rule for parallel batches** (AGENTS.md): facts schema, state format and protocol numbers are taken by each batch on its own (0.26.0 renumbered two schema steps at merge); hot files (`PassiveStepSolver`, `FluidWorldAuthority`, `FluidFacts`, `en_us.json`) have no owner. The collaboration guide proposes the rule.
7. **The 1 MB rule covers `documentation/` only**; `tools/` holds about 500 MB (finding 12). Proposed: the same rule for `tools/`, large files to the main checkout's `research/`.
8. The session's worktree folder is `batch-still-models-b3de99` on branch `claude/codebase-review-refactor-8a85ef`; every brief gives the full path.
