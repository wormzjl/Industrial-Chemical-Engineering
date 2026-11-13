# Phase 2: implementation roadmap

Base `259d22a2c`, 2026-11-13. The batches that follow from the phase 1 documents, in dependency order. Each batch is its own branch, review, final lane and merge, under AGENTS.md and [`COLLABORATION.md`](../reference/COLLABORATION.md). None has started; each needs the owner's go, and each delegation its own approval. Ids are the reviews' ([CODEBASE_REVIEW.md](CODEBASE_REVIEW.md), section 2). The profile (B1) orders wave B and decides three rows of wave E; this file is updated when it reports.

Model for the thread that implements a batch, by the owner's table: Opus medium unless the row says otherwise.

## Wave A: defects (small, independent, first)

| Batch | Lane | Contents | Class | Notes |
|---|---|---|---|---|
| A1 column and plant | column, plant | C4 (feed water above 647 K), F1 (column identity names the derivation), F3 (steam continued past its data), D4 (audit limits equal the closure), D2 (hydraulic budget in polls), C10 (a still's readiness test reads, never materialises), C19 (the sheet's still ends its step instead of throwing) | DEFECT | F1 rebuilds every cached column state once. Opus high |
| A2 saves | equipment | E10: shape fields 9 to 11 and the column's exchanger types through `ShapeRepair` with aliases; a corpus image with an unknown name | DEFECT | no saved field changes |
| A3 machines | machines | M-02 (repair kit and status for every worn machine), M-04 (one run plan per mover; a test first confirms the flip-flop), M-16, M-17, M-19; M-01 and M-07 (the step result carries the shaft power) on the owner's yes | DEFECT | M-01 moves electricity draws by −11 % to +54 % in the named cases |
| A4 sheet against world | flowsheet | P3-01 (ejector sizing on the world's capacity), P3-04 (one still fill limit) | DEFECT | sizes move at non-default motive pressure |
| A5 runtime | runtime | R5 (a read previews, never books), R26 option A (one flash, reused at apply) | DEFECT | R5 needs the new test: two coordinators, one read every tick, bookings bit-equal. Opus high |
| A6 fluid | fluid step, fluid laws | D-01 (wall branches kept with the factorization), D-02 (heat curves sampled before the pass), L-F2 (tower calibration per model), L-F19, L-F20, L-F21 | DEFECT | Opus high for D-01 and D-02 |

A1, A3 and A5 touch different lanes and can run two at a time (the Gradle limit).

## Wave B: free performance (references exact)

One batch per lane; the order inside and between them comes from B1's cost table.

| Batch | Contents |
|---|---|
| B step | P-01, P-02, P-03, P-05, P-06, P-12, P-13, P-14, P-15; L-F3 (junction basis cached), L-F5 (one adjacency), L-F10 |
| B thermo | P20, P21, P23, P27, P24, S13 of the thermo review |
| B column | P2 (audit only a converged attempt), P6, F10, F11, F12 |
| B runtime | R1, R2, R4, R10, R22, R23, R24 |
| B machines | M-08, M-09, M-10, M-11, M-18 |
| B equipment | E11, E12, E13 |
| B client | G-C4 on the seven all-kit screens, G-C5, G-C13, G-C14, G-C20 |
| B control | P3-18, P3-19, P3-27, P3-28 |

Proof of each: the lane's exact references, the area's tests, and the counter or the B1 scenario that showed the cost, measured again.

## Wave C: layering (code moves; references exact unless stated)

Order matters: each batch moves files the next one builds on. `LayeringTest` gains a rule at the end of each.

| # | Batch | Contents | Stated shifts |
|---|---|---|---|
| C1 | water and the thermo core | the water module (F2, F6, X39); the engine free of the network's package and of the catalog factories (S04, S05, F9); one cubic derivative function, one root finder, small dense solves and constants in `science/math` (S08, S12, S16, L-F9, P3-11, L-F13) | Wagner-Pruss about 1e-16 for one solver; R and air density about 1e-11 on flare, chimney and saturation-table readings |
| C2 | material | F4, F5, F7; `SolidInventory` out of the cycle (L-F8) | none |
| C3 | machines | the ejector law and its constants into `science/machine`; `InlineMachines` split; the vacuum pump catalogue out of geometry (M-14, P3-09, M-15, E6) | none |
| C4 | plant | `science/plant`: `ColumnPricing` (C1), `PortAccounts` and `UtilityDebt` (C6), `StillColumn` behind `StageThermo` (C17 a), the operating point a plain fact (E18), the catalog an argument (C16), heat-side law construction out of geometry (E19, C13), one port hook (C23, C24, R18) | none |
| C5 | fluid | `FluidThermodynamics` split (F8); node predicates and constants below the laws; `PassiveNetwork` and `NetworkReduction` out of their cycles (S-10, S-11, L-F8) | none |
| C6 | typed codes | refusal types and status codes in the kernel, the engine and the step (S07, S06, S-13); enums for the column's codes (S2), the drivers' words (M-20), tile descriptors (P3-07); a structured status on the device view (G-C18) | none |

C1 and C4 are the two that de-correlate the solvers. Opus high for C1, C4, C5 and C6.

## Wave D: monolith splits (code moves; after the lane's wave C batch)

| Batch | Contents |
|---|---|
| D engine | S03 (delete 170 dead lines), S02 (the dry core on an explicit feed), S01 (ten files); the engine's digest gate first |
| D step | S-02 (`SolveConstants`), S-03, S-04, S-05, S-07, S-06, S-08, then S-01 (13 files, pure groups first) |
| D column | S4 per the owner's choice (delete or move to `tools/`), S3 (one rung runner), S1 (nine files) |
| D runtime | R8, R9, R13, R12 (facts sections, bytes identical on the compat corpus), R14, R19, R21 |
| D equipment | E3, E1, E2, E4, E5, E7, E8, E9, E15, E17 |
| D client | G-C9, G-C10, G-C1, G-C2, G-C6, G-C7, G-C8, G-C11, G-C15, G-C16, G-C17; G-C3 after the owner has seen the render options |
| D flowsheet and control | P3-08, P3-21, P3-22, P3-23, P3-25, P3-26 |

Each split is Opus high: the diff is large and the proof is that nothing moved.

## Wave E: the column in Rust

As [RUST_COLUMN_PORT_PLAN.md](RUST_COLUMN_PORT_PLAN.md), with the decisions of [SOLVER_DECOUPLING_PLAN.md](SOLVER_DECOUPLING_PLAN.md) section 6.

| Stage | Contents | Depends on |
|---|---|---|
| 0 | Java: the C++ pass off (D1, owner); the flat layout and one workspace per solve (P3, P4, P5); the analytic Jacobian authoritative and the trial and cascade decisions (P1, L1, L2: owner, measured); the learned budget's unit (D6: owner); the parity fixtures recorded | A1, B column, C6, D column; B1 |
| (a) | Cargo workspace, the math layer with Java's bits, the handshake and self-test; the C++ lane deleted | stage 0 |
| (b) | the band solver and the thermo kernel behind the column port; kernel bits gate | C1, (a) |
| (c) | one attempt in one call; attempt parity gate | (b) |
| (d) | the whole solve in one call; the Java audit publishes; the running column and the simulator on it | (c), C4 |

Each stage ends with B1's column scenarios measured again. Stages (a) to (d) are very hard by the owner's table: Fable, or Opus xhigh.

## Wave F: the fluid step in Rust (gated)

The thermo kernel itself is ported with the column (wave E stage (b), the `icheme-thermo` crate). What is gated here is the fluid step's use of it. Owner, 2026-11-13.

### F0 (Java, always)

Needed for any port, and expected to give most of the gain by itself. It is not new work beside the other waves: it is the named subset of them, plus two items.

| Item | Findings | Where it lives |
|---|---|---|
| Typed refusals: a status code in the kernel, a `FailureKind` on the engine's result, one refusal type in the step | S07, S06, S-13 | wave C6 |
| The splits of `PassiveStepSolver` and `FluidTpEquilibrium` on explicit inputs | S-01 to S-07, S01, S02, S03 | D step, D engine |
| Flat `IslandTables` and `PassTables` and an evaluation workspace, shared by every pass | FLUID_STEP_REVIEW 3.1, 3.2, 3.5; P-07 | new, after D step |
| The kernel as one call with a `want` mask; flat arrays in place of the views and the three-call protocol | X37 | new, with C6 or after D engine |

F0 is done when: the step's residual, Jacobian and Newton read only the flat tables and the workspace; no exception is control flow in a pass; the lane's exact references stand (every F0 item is STRUCTURE or identical PERF).

### The gate

After F0 and the wave B batches of the fluid step and the thermo core (B step, B thermo), measure an island step again with `tools/codebase-review-bench` on B1's fluid scenario, each timed run alone. The port goes ahead only if both hold:

1. **Share.** Kernel calls, residual and Jacobian assembly and the LU together are still the large majority of the step's time. Proposed reading, for the owner to confirm when the gate is run: at least two thirds.
2. **Need.** The step's cost still limits plant size: on the bench's largest paced scenario the islands do not keep up with online time on the default worker count, by the benchmark's own pace counters.

The gate's review states both numbers and the decision. If the gate fails, wave F ends at F0 and the fluid step stays in Java.

### F1 (only if the gate passes)

1. The kernel half of the network port native: prepare at T; the state at T, P and amounts; tangents; transport properties (FLUID_STEP_REVIEW 3.4), on the column's `icheme-thermo` crate. Function gates are exact, as for the column.
2. One pass per call: residual, Jacobian, Newton and LU for one frozen active set, on the flat tables. The sparse LU either copies EJML's pivot rule or takes a stated shift, decided from the gate's LU share.

What stays in Java for good: the pass loop and its active-set decisions, the booking (`ConservativeTransport`), the interval solver, the device laws' planning, and the equilibrium engine. **The Java booking and the residual gate of the booked point check every Rust pass**, as they check a Java pass today: a Rust defect can cost a repeated pass or a fall back to Java, never an accepted step the Java checks refuse.

F1's build plan is written after the gate, from its numbers and from what the column's stages (b) and (c) measured.

### The equilibrium engine is not ported

It stays in Java behind the network port. Reconsider only if a profile shows flashes as the top cost of a step after P20, P21, P24 and P25 are in.

## Wave G: machines

1. `MachineHealth` and the fault table (M-12): structure only.
2. The failure modes the owner picks ([MACHINE_FAILURE_MODES_REVIEW.md](MACHINE_FAILURE_MODES_REVIEW.md), 3.2), one mode per commit with its `WearTest` case and its lang line.
3. The trips (3.3) and the gearbox rating (N7).

## Wave H: owner options

Each row of [SIMPLIFICATIONS_REVIEW.md](SIMPLIFICATIONS_REVIEW.md) the owner takes becomes one batch in its lane, with the quick check, one campaign where it touches the network, and the old and new references in its review. Suggested first: C2 and C5 (they complete the de-correlation), D3, L-F1, L-F11, X32, X33, P3-02, P3-03, P3-15, then E20, R11 and P3-06 (declared revisions in place of hashes).

## Wave I: room for supercritical fluids

Requests by slot and `PhaseTraits` (X36); one mapping from phases to the network's slots by a continuous trait; "no saturation" instead of infinity (F21); films and heads by density (L-F24). References exact for subcritical states. The supercritical batch itself is the owner's to open.

## Wave J: documents and tools (no Gradle; can run beside any code batch)

The code guide's restructure, `FORMATS.md`, the map script, `LayeringTest`, the `tools/` cleanup, the stale texts ([DOCS_AND_COLLABORATION_PLAN.md](DOCS_AND_COLLABORATION_PLAN.md), sections 2 to 4), and the AGENTS.md amendments the owner accepts.

## Order and parallel work

1. Wave A and wave J at once (J has no Gradle; two A batches at a time).
2. Wave B, by B1's order, two lanes at a time.
3. Waves C and D lane by lane: C1, then C4; D engine and D column may follow in parallel with C5 and D step.
4. Wave E from stage 0 as soon as A1, B column and D column are in; wave G beside it (machines lane).
5. Wave F's F0 is complete when C6, D step, D engine and its two own items are in; the gate is measured then, with B step and B thermo in. F1 follows only on a passed gate, and after wave E's stage (b) (the shared crates).
6. Waves H and I as their dependencies land.

## Decisions for the owner

In the order they unblock work. "Rec." is the main thread's recommendation.

| # | Decision | Rec. | Unblocks |
|---|---|---|---|
| 1 | Start phase 2 with wave A and wave J | yes | everything |
| 2 | The C++ pass off by default now (D1) | yes | determinism; stage 0 |
| 3 | The driver pays the solver's shaft power (M-01, M-07) | yes | A3 |
| 4 | Failure modes: which of N1 to N7; the unreachable trips, option A or B | N1, N2, N4, N5, N7 option A; trips A | wave G |
| 5 | Scheduling: one worker kept for islands, the plant deadline from the worker (R6 option A) | yes | de-correlation step 9 |
| 6 | Pricing on specific enthalpy (C2) and one column job for the sheet and the world (C5) | yes | wave H, first batch |
| 7 | The analytic Jacobian authoritative (P1), the inside-out trial (L1), the verification cascade (L2) | P1 yes after B1; L1 by its measure; L2 with P1 | stage 0 |
| 8 | The running column's closure policy (D3 option A or B) | A | stage 0 fixtures |
| 9 | Declared revisions in place of the three hashes (E20, R11, P3-06) | yes | saves coherence |
| 10 | Rust targets: Windows and Linux first, macOS on the Java path until built; unverified targets guarded or held back | as stated; guarded | stage (a) packaging |
| 11 | AGENTS.md amendments 1 to 7 | yes | wave J |
| 12 | `tools/` cleanup: the 1 MB rule, the vendored dependencies, the dead tasks, the 113 unreferenced folders | yes | wave J |
| 13 | A standing approval for read-only review subagents inside a named batch | yes | faster reviews |
| 14 | The rest of SIMPLIFICATIONS_REVIEW, row by row, when its wave comes | per row | wave H |
| 15 | Fluid step in Rust: gated on a re-measurement after the Java groundwork; equilibrium engine stays in Java | yes | wave F |
