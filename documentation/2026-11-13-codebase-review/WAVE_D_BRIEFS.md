# Wave D briefs (monolith splits) and the rest of wave C's plant layer

Batch `2026-11-13-codebase-review`, phase 2, unit 3. Written by the main thread on 2026-11-13. [PHASE2_PLAN.md](PHASE2_PLAN.md) lists wave D's batches and wave C's rows. A split or a move is structure only: every result bit for bit. Subagents write and run nothing; the main thread compiles and runs.

## D-E0 The engine's digest gate, then S03 (subagent, Opus high)

Reviews: THERMO_CORE_REVIEW section 3 ("digest (to add)", above the findings table), rows S03 and S01, section 2.2. The engine is `science/thermo/phase/FluidTpEquilibrium` (about 6,300 lines) with `TangentPlaneStability` in the kernel; its split (S01) needs a gate that reads every bit it answers, which no test does today.

Commit 1, the gate (no production code changes):

- `src/test/java/com/wormzjl/icheme/science/thermo/phase/EngineDigestTest.java`, in the `test` group. For each package the tests can reach without a game (the bundled catalog's packages through `PackageBinding`, and the pilot catalogs the engine's tests already use: read `FluidTpEquilibriumTest`, `FluidTpEquilibriumPhUvTest`, `AqueousEquilibriumTest`, `GasSolidEquilibriumTest` and `FluidTpEquilibriumP3Test` for how they build an engine and which states matter), a fixed list of requests: TP on a grid that crosses each kind of answer (one phase gas, one phase liquid, two phases, three phases with water, a crystal where the package has one, near a bubble and a dew point, a trace component, a refused and an unsupported request), and PH and UV requests made from the enthalpy, internal energy and volume of converged TP answers (so they are consistent by construction). About 150 to 400 requests in all; the whole test under about 20 s of CPU by your estimate from the existing tests' request counts.
- For each request one text line: a stable request id, the status and typed reason, and for a converged answer the raw long bits in hex of every phase's kind, amounts, temperature and pressure and of the result's enthalpy, internal energy, volume and entropy aggregates, and those integer counts of the diagnostics that the existing tests treat as stable (say which you included and why).
- The reference is a resource, `src/test/resources/thermo/engine-digest.txt` (lines in request order). The test compares line by line and fails naming the first differing request with both lines. With `-Dicheme.engineDigest.record=true` it writes the lines to `build/engine-digest.txt` and passes; without a reference resource it fails with the text "no reference: record it". Do not write a reference yourself: the main thread records it from a run at commit 1.

Commit 2, S03 (production code): delete the per-branch pressures of the removed free-water rule: `branched`, `vaporShifts`, `pressureOf`, `shift`, `admissible` and the branched overloads, in `FluidTpEquilibrium` and `TangentPlaneStability` (the review gives the old line numbers; `setBranches` has one call, with equal pressures: prove that from the code first and put the proof in the report). No arithmetic on a live path changes: where a live expression read a per-branch pressure that always equalled the node pressure, it reads the node pressure, the same double. If any deleted path can be reached with unequal pressures, stop and report.

Tests for the main thread: `EngineDigestTest` (recorded at commit 1, unchanged at commit 2), `FluidTpEquilibriumTest`, `FluidTpEquilibriumP3Test`, `FluidTpEquilibriumPhUvTest`, `AqueousEquilibriumTest`, `GasSolidEquilibriumTest`, `TangentPlaneStabilityTest`, `ThermoLayeringTest`; chain-100 exact. Report: `wave-d/D-E0_REPORT.md`: the request list by kind and package, what a line holds, the proof for S03, lines removed.

## C4b The still's column solver out of the network: C17 option (a) (subagent, Opus high)

Reviews: PLANT_COUPLING_REVIEW row C17 and section 3.4; THERMO_ADAPTERS_REVIEW section 2.5 (the `StageThermo` signature); SOLVER_DECOUPLING_PLAN section 4 row 6. Structure only: `StillModelTest`, `StillHeadTest`, `BatchStillTest` and `StillRuntimeTest` bit for bit.

Today `science/fluid/network/StillColumn` (about 1,300 lines) is a second column solver inside the network's package, on `HydrocarbonModel.prepare` and `.phase(T, P, x, root)` (ln phi, H, V) and on `FluidThermodynamics.flashTP` for wet liquids. After this batch:

- `science/column/still/StillColumn` (and the classes that are only its parts) imports nothing from `science/fluid`: it reads its thermo through an interface in its own package, `StageThermo` (what section 2.5 lists: ln phi of the liquid and of the vapour, molar enthalpy and volume at T, P, x; a TP split of a wet liquid; the component list and molecular weights it needs), on plain arrays and small records of its own.
- `science/plant/still/NetworkStageThermo` (the plant layer, which may import both) implements `StageThermo` on the island's `FluidThermodynamics` and `HydrocarbonModel`, with the same calls in the same order that `StillColumn` makes today, returning the same doubles. Callers that built a `StillColumn` on a model (`runtime/fluid/Stills`, the sheet's still unit if it has one, the tests) build it on the adapter.
- `StillHead` and other still laws that stay in the network's package keep their place unless they import the moved class (then they move to `science/plant/still` with it or take the interface: choose the smaller change and say so).
- `LayeringTest.PACKAGE_RULES` already holds `fluid-free-of-column`, `column-free-of-fluid`, `fluid-free-of-plant` and `column-free-of-plant`: they must still hold. Add nothing unless a new rule holds.

Rules for the move: no arithmetic changes; a value the model object returned and `StillColumn` read (a phase record's fields) becomes a field of the interface's result record filled from the same accessor; no extra thermo call, no dropped one, the same order (the checkpoint and poll counts are part of results); per-solve state stays where it is (the review's "fields into a solve context" is not in this batch). If the interface cannot carry something without a second evaluation, stop at that point and report what blocks it.

Pitfalls: `build.gradle`'s gate groups list class files by path (`columnGates` takes `science/column/v3` classes; a class in `science/column/still` is not in its list, and the still's tests are in `test` today and should stay there: say which list changes, if any); saved facts name still kinds by enum name, not by class; the batch still's GameTests compile against these classes.

Tests for the main thread: `StillModelTest`, `StillHeadTest`, `BatchStillTest`, `StillRuntimeTest`, the flowsheet's still tests, `LayeringTest`, and every other test that names a moved class (grep), each with its Gradle task. Report: `wave-c/C4B_REPORT.md`.

## C4c One port arithmetic for columns and stills: C6 (subagent, Opus high)

Review: PLANT_COUPLING_REVIEW row C6 and section 3.2. Structure only: `ColumnRunTest` and `StillRuntimeTest` bit for bit.

`runtime/fluid/ColumnOperation` and `runtime/fluid/Stills` each carry the same port and transit arithmetic (the review's old lines: ColumnOperation 40 to 42, 858 to 921, 941 to 966, 1077 to 1112; Stills 49 to 55, 273 to 294, 382 to 403, 416 to 427, 542 to 553: find them by reading both). They become two Minecraft-free classes in `science/plant/port`: `PortAccounts` (what a unit's port has drawn, delivered and holds in transit, on the island's booking) and `UtilityDebt` (the utility owed and repaid), used by both units.

Method: read both copies side by side first and write, in the report, a table of each duplicated block: what it computes, the two places, and every difference between the copies (an operation order, a guard, a constant, a rounding). Where the copies are identical in operations, one function serves both. Where they differ, keep both behaviours exactly (a parameter or two functions) and name the difference in the report as a finding; do not unify a difference. The same floating-point operations in the same order for each unit; saved facts (ledger totals, debts) keep their fields, names and units; no format changes.

Do not touch `Stills`' construction of its column solver or any `StillColumn` import (another subagent moves that class now), nor `ColumnOperation`'s solve dispatch.

Tests for the main thread: `ColumnRunTest`, `StillRuntimeTest`, `ColumnSuspensionTest`, `ColumnConditionTest`, `FluidFactsCompatTest`, a new `PortAccountsTest` for the shared functions on hand-computed cases, `LayeringTest`, each with its Gradle task. Report: `wave-c/C4C_REPORT.md`.
