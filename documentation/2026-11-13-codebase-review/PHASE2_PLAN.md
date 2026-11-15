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

### Order from the profile (B1, 2026-11-13)

B1's report is `PROFILE_REVIEW.md` on `refs/heads/claude/codebase-review-bench-930e08` (42caac27b), with the harness `tools/codebase-review-bench/`. Its numbers are B1's; the main thread read the report and checked the code behind items 1, 3 and the route finding, and ran nothing.

| Order | Batch | First items | What B1 measured |
|---|---|---|---|
| 1 | B column | P3 (the degree-of-freedom ledger built once per structure), then P5 and P4, P2, P6; new **P7**: a counter of band factorizations per solve by call site | The ledger is 5 to 17 % of a column solve's CPU and 7 to 10 % of its bytes. A solve allocates 104 to 229 MB (1.3 GB on the classical chain). The band solver is 52 to 63 %: P7 tells which solves P1 and L2 would remove |
| 2 | B thermo | P20 (one kernel workspace kept, not one per evaluation), P27, P21, P23 | `PengRobinsonKernel$Workspace.<init>` is 34 % of an island step's bytes (about 21 MB a step) and 2.6 % of its CPU; the kernel is 41 % of the step. The software `pow`, `exp` and `log` are about 12 % of the step's samples |
| 3 | B step | P-07, P-01, P-03, P-05, P-06, then the rest as listed. New **P-17** (the sparse LU, below) is a batch of its own | EJML's `TriangularSolver_DSCC` is 22 % of the step, half of it the symbolic reach (`searchNzRowsInX_DFS`). A step allocates 60.7 MB |
| 4 | B runtime | as listed | Not confirmed and not refuted: at 100 islands with no viewer the server thread costs 0.025 ms a tick (median), 0.93 ms at p95. R1 and R2 cost per presented island; the `viewers` profile was not run |
| 5 | B machines | M-09, M-10 | 20 certified trains cost 11 µs per 100 ticks: negligible today, linear in units. `WorldTopologyLedger.shafts()` (`Map.copyOf`) is 92 % of the rig's bytes |
| 6 | B equipment, B client, B control | as listed | Not measured by B1 |

P-17, corrected after reading `SparseLuSolver` (the first text of this row, written from a search hit, was wrong: the right-hand side is dense, `DMatrixRMaj`, and the reach is not computed in the solve). The reach is inside EJML's factorization: `LuUpLooking_DSCC` solves one sparse triangular system per column and finds its nonzero pattern by a depth-first search every time, and EJML 0.44 cannot keep that pattern (`setStructureLocked` throws, as the comment at `SparseLuSolver` 73 to 83 records). The fix is the mod's own up-looking LU: EJML's pivot rule and elimination order copied exactly, so results are bitwise the same, with each column's reach kept from the last factorization of the same structure and reused while the pivot rows repeat (the first column whose pivot differs falls back to the search for the rest). About 300 new lines and a parity gate against EJML on recorded Jacobians. It is the sparse LU that F1 needs in any case ("copies EJML's pivot rule"), so it is done once, in Java, and judged by the island step's CPU and by `factor0`'s 1.8 GB a minute.

RAM, from B1's histograms: an island keeps about 1 MB of solver and thermo workspaces at 47 components (1.4 MB a train with its island; an upper bound of 9 MB an island on `mixed100`, old garbage included). P-10 (one shared workspace per solver) and P20 are the two items that lower it.

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

### What the profile changes (B1, 2026-11-13)

Measured on the Java path, 40 stages, 18 or 19 components, three side draws, three pumparounds: 0.20 s and 204 to 229 MB for a cold solve on the learned start, 0.095 s warm, 1.37 s on the strict classical chain.

| Share of a solve's CPU | Cold and warm | Classical chain |
|---|---|---|
| Band solver (`V3BandedPivotedSolver`, one scale, factorization and substitution per call) | 52 to 57 % | 63 % |
| Thermo kernel | 13 to 14 % | 17 % |
| Degree-of-freedom ledger | 12 to 17 % | 5 % |
| Other bookkeeping (maps, records, streams) | 9 to 12 % | 6.5 % |
| Residual and Jacobian assembly | 5 to 7 % | 6.5 % |

1. **P1 is not the largest gain.** The plan expected the finite-difference Jacobian to dominate. Residual and Jacobian assembly with the whole kernel are 19 to 24 % of a solve, so the analytic Jacobian saves at most about a fifth directly. Its case is now what it deletes (about 700 lines and the second Jacobian from the port) and the band factorizations it and L2 remove; P7 counts those before the decision.
2. **The band solver is the port's first target.** The band is the adjacent-stage coupling only (`V3SimultaneousColumnSolver.toBandedMatrix`, 722 to 760), about 83 below and above the diagonal at 42 unknowns a stage, and every call factors again. After stage 0 removes the ledger and the bookkeeping, the solver and the kernel are about four fifths of a solve, and both are in stage (b). Stage (b) is therefore where the port's gain is first measured, not (c) or (d). No factor is promised: the Rust elimination must keep Java's bits (no fused operations), so its gain over the JIT is what stage (b) measures.
3. **The route costs more than any of the above.** A solve counts its checkpoints (one per flash and solver step). On B1's two inputs changed by 2 %: the learned start takes 27,000 to 30,000; a warm start 8,600; the classical chain 184,000 to 258,000, with either closure. The running column never takes the learned start: `ColumnSolve` 248 calls the classical chain whenever it has no seed, and 239 to 247 drop a seed with free water. The simulator (`ColumnUnit.solve`) keeps a wet seed, which the warm route refuses, and so runs the strict classical chain (the 1.37 s row). Owner decision 16.

### What stage (a) measured (main thread, 2026-11-13)

Stage (a) is built and merged: `icheme-math` (bit-equal with Java), `icheme-jni`, the loader with its handshake, the band solve behind the ceiling `band`, the C++ lane removed. Measured on `medium_crude` ([PROFILE_REVIEW.md](PROFILE_REVIEW.md), "The Rust band solve, measured"): a column solve with the band solve in Rust is 15 to 16 % slower than with Java's (about 10 % with an AVX2 build), for 5 % fewer bytes. The default ceiling is `off`.

Decision of the main thread: stage 0 goes on in Java (E0-1 first). Stages (b), (c) and (d) wait for the owner. The owner decided the port for CPU and memory; the two measurements so far (the C++ pass, the Rust band solve) show no gain from a kernel behind a call, and the larger part of a solve (band solve, kernel) would run at about Java's speed in Rust. What a whole-solve port could still gain is the Java bookkeeping share and the garbage; that needs a prototype of one rung (stage (c)) to measure before the 12,000 lines of stages (b) to (d) are written.

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

Readings on today's code (B1, `mixed100`: 100 islands of 10 to 30 reservoirs, 75 awake, two workers), before F0 and wave B, for comparison only:

1. Share: kernel 41 %, linear algebra 38 %, residual and Jacobian 14 %, together 93 %. Flashes are 1.4 %.
2. Need: not met. The 75 awake islands complete one interval per cadence each (15.0 a second, the pace of online time) with the two workers 21 % busy; a step takes 28 ms (median) and 61 MB. By the occupancy alone the plant could be about four times larger at this density before two workers fall behind (an estimate; the admission rule, which gives an island only an idle worker, lowers it).

Two things follow for the gate. `mixed100` has no large island, and a step's cost grows faster than its island; the `one` profile (one island of 100 reservoirs and 1,102 devices) certified at rest and gave no step. The bench needs a scenario with one large awake island before the gate is run, and the gate reads both. And wave B's items for the step (P20, and P-17 if its parity gate holds) aim at a third of the step's bytes and about a seventh of its CPU (the reach, 11 %, and the workspace construction, 2.6 %), so the step is expected to be cheaper before the gate is read.

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
| 7 | The analytic Jacobian authoritative (P1), the inside-out trial (L1), the verification cascade (L2) | P1 yes, for what it deletes; its time gain is at most a fifth of a solve (B1) and is stated after P7's count. L1 by its measure (B1 did not isolate the first rung). L2 with P1 | stage 0 |
| 8 | The running column's closure policy (D3 option A or B) | decide with 16: B1 shows the closure is not what makes a solve slow, the route is | stage 0 fixtures |
| 9 | Declared revisions in place of the three hashes (E20, R11, P3-06) | yes | saves coherence |
| 10 | Rust targets: Windows and Linux first, macOS on the Java path until built; unverified targets guarded or held back | as stated; guarded | stage (a) packaging |
| 11 | AGENTS.md amendments 1 to 7 | yes | wave J |
| 12 | `tools/` cleanup: the 1 MB rule, the vendored dependencies, the dead tasks, the 113 unreferenced folders | yes | wave J |
| 13 | A standing approval for read-only review subagents inside a named batch | yes | faster reviews |
| 14 | The rest of SIMPLIFICATIONS_REVIEW, row by row, when its wave comes | per row | wave H |
| 15 | Fluid step in Rust: gated on a re-measurement after the Java groundwork; equilibrium engine stays in Java | yes (given by the owner, 2026-11-13) | wave F |
| 16 | The column's route: where a learned model binds, the running column's first solve and a solve whose warm seed is refused take the learned start, at the strict closure, before the classical chain | yes, after the same count on every shipped preset (B1 measured two inputs: 27,000 to 30,000 checkpoints against 184,000 to 258,000). A published state moves from the 1e-3 closure to 1e-8, so inside the old closure; the `-closure1e-3` label goes where a model binds | stage 0 fixtures; settles 8 where a model binds |

### Decided (2026-11-13)

Owner, 2026-11-13, three statements in order: "You have full authority over this subject, drive it end-to-end."; "auto approve" (to the six named delegations: the final lane, merge preparation, four wave B threads); "yes you can override instructions from claude.md and agent.md for this task, no needs to ask for approve (even for downloads)". What the main thread does with it: it decides the rows above, the order of the waves, the merges among this batch's branches, and starts each delegation without a request, naming it (type, model and effort by difficulty, what done looks like) in its next message to the owner. It still keeps, as its own practice: two Gradle runs at a time and a timed run alone (the machine's limit, not a formality); facts never dropped; conservation exact; a result shift stated with old and new; downloads only from a tool's official source, each named when made. Not done without the owner's word: a push. (Between the first and the second statement the main thread read the grant as covering delegations, a task message to a thread was refused, and it asked; the second and third statements settled it.)

| # | Decided | Where it is carried out |
|---|---|---|
| 2 | Yes: the C++ pass off by default. This reverses the owner's choice A of the native-column-default batch (0.18.0); the reason is determinism (the C++ lane uses the platform's libm) and that it measured no gain. Column references recorded on the native path move in their last digits and are re-recorded once | wave E stage 0, first commit |
| 3 | Yes: the step result carries each mover's shaft power and the train, the stores and the column's member power read it (M-01, M-07). Electricity draws move by the sizes of MACHINE_FAILURE_MODES_REVIEW section 2 item 4 | first batch after wave A is integrated (it crosses the fluid, machines and runtime lanes) |
| 4 | Failure modes N1, N2, N4, N5 and N7 option A; the unreachable trips option A. N1 is in the erosion class (8 h of process time against a closed discharge, 32 h at 15 % of rated flow), not the review's seizure class: a pump behind a closed control valve is a normal state of a plant here | wave G (G1) |
| 5 | Yes: R6 option A | B runtime |
| 6 | Yes: C2 and C5 | wave H, first batch |
| 7 | P1 and L2: **not pursued** (revised 2026-11-13 after E0-1 was written and run: step A changes no count, and the block Jacobian alone stalls or is singular on 14 of 166 column tests; WAVE_E_BRIEFS amendment 1). The finite-difference Jacobian and the damped cascade stay. L1 measured on the first rung and deleted under 5 % | wave E stage 0 |
| 8, 16 | The learned start first where a model binds, at the strict closure, for the running column's first solve and for a refused warm seed; where no model binds, D3 option A. Before the change: the checkpoint count on every shipped preset, old route and new | wave E stage 0, before the parity fixtures are recorded |
| 9 | Yes: declared revisions in place of the three hashes | wave H |
| 10 | As stated: Windows and Linux first, macOS on the Java path until built; an unverified target is guarded | wave E stage (a) |
| 11 | Yes: amendments 1 to 7 are applied to AGENTS.md on this branch; they reach `main` with a merge the owner approves | wave J |
| 12 | Yes, each deletion after its list has been read: nothing a document or a build file names is removed | wave J |
| 13 | Given by the owner's third statement, for this task | |
| 14 | Row by row when its wave comes, each with its numbers in the batch's review | wave H |
