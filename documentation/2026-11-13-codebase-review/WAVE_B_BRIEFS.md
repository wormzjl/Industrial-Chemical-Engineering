# Wave B briefs, first round

Batch `2026-11-13-codebase-review`, phase 2. Written by the main thread on 2026-11-13 from the reviews, B1's profile ([PROFILE_REVIEW.md](PROFILE_REVIEW.md)) and a reading of the code named below. Order and reasons: [PHASE2_PLAN.md](PHASE2_PLAN.md), "Order from the profile". Each section is one thread's lane. The rows it names (file, lines, proposal, risk, test) are in the review it cites; read the row before the code. Line numbers are of `259d22a2c`; wave A moved some by a few lines.

## Common rules

1. Read `AGENTS.md` first. In force here: "Engine schedule", "Physics and defects", "Gradle, measurement and verification" with "No repeated tests", "Git and worktrees".
2. Every item here keeps results **bitwise identical** unless its section says otherwise. Proof: the lane's exact tests named in the row, and one counter that shows the saving (allocated bytes per solve or per step, a count of factorizations, of checkpoints, of a call). If an item cannot be made identical, stop that item, write why in the report, and go on with the next; do not ship a shift.
3. No Gradle until the main thread's message says "lane free". Then: `compileJava compileTestJava` once; named test classes only, with `--tests` and `--max-workers 4`; no whole task; no `--rerun-tasks`; each class once after it last changed.
4. Times: this is a performance batch, so a time may be recorded, but only from a run that runs alone, and only the main thread schedules one (the bench of `tools/codebase-review-bench`, after the round). Until then judge by counters and bytes; they do not need the machine alone.
5. One commit per item, message `Wave B <lane>, <id>: <what>`. No facts schema, state format, protocol or shape-field change. No new public API beyond what the item needs.
6. Never run a test with the change switched off to prove it. Never loop a test.
7. Report: `documentation/2026-11-13-codebase-review/wave-b/<LANE>_REPORT.md`, at most 50 lines: per item the commit, what changed, the counter before (from the code as you found it: read it from an existing counter or from B1's tables, do not build a second copy of the old code) and after, the tests run and their counts, anything not done and why.
8. Cadence: no progress messages. Message the main thread only for a decision (at once, with options) or when done. A premise that fails is a decision.

## B-C1 Column (thread wave-a1, Opus high)

Lane: `science/column/v3/**` (not `thermo/V3WaterProperties`), their tests. Reviews: COLUMN_REVIEW P2, P3, P6. B1: the band solver is 52 to 63 % of a solve, the ledger 5 to 17 %, a solve allocates 104 to 229 MB.

1. **P7 (new), count the band factorizations.** `V3BandedPivotedSolver.solve` (25 to 122) scales, factors and substitutes on every call. Add a counter per call site, carried with the solve's diagnostics (not a static): the Newton step, the finite-difference step evidence (solver 485, 602), `verifyFinalNewtonCorrection`'s damped solves (633 to 720), the normal equations (`V3NormalEquations` 73), the hydraulic correction. Publish the counts in the solver events as the hydraulic correction's polls are. Report the counts for `medium_crude` and the literature column, cold and warm (the inputs of `tools/codebase-review-bench/ColumnBench.java`). These numbers decide P1 and L2; do not change either.
2. **P3, the degree-of-freedom ledger.** As the row: the layout as a prefix sum over the phase mask and three int tables; references built only for the diagnostic text; the matching on int tables. B1: `augment` (369) with its `HashMap` of `UnknownId` is 11 to 16 % of column CPU and 7 to 10 % of its bytes. Keep the matching's verdict exactly (calculator 2765 to 2770 falls back to identity support on it): same augmenting order, so the same matching, not only the same size. `V3StructuralMatchingOracleTest` is the proof.
3. **P8 (new), the elimination's width.** In `solve`, 72 and 92: every row is updated out to `pivot + fillBandwidth` (lower plus upper, about 166 at 42 unknowns a stage) although a pivot row holds entries only as far as its own last nonzero. Track that extent as LAPACK's `dgbtf2` does (`ju = max(ju, min(pivotRow's last stored column, size - 1))`, raised when a swap brings a longer row up) and update only to it. Identical: a skipped update is `x - m * (+0.0)` on an entry no write has touched, which is `x`. Check the claim on the sign of zero in the code (an entry beyond the extent must be the `+0.0` of `Arrays.fill`); if any path can leave `-0.0` there, say so and stop the item. The growth scan and the non-finite guard keep their values.
4. **P9 (new), the inner loop as a plain update.** Lines 92 to 96 fold the growth maximum into the update loop, which keeps the JIT from vectorising it. Split: first `band[rowBase + c] -= multiplier * band[pivotBase + c]` over the extent (no `Math.fma`: Java must not fuse here, and does not), then the maximum of `|band[rowBase + c]|` over the same extent. The maximum is the same number (a NaN or infinity still reaches the guard at 101). Same for `backSubstitute` only if it needs it.
5. **P2, audit a converged attempt only**, and **P6, the feed flash cached per solve**, as their rows.

Not in this round: P4, P5, F10, F11, F12 (the flat workspace: stage 0 of the port, after this round's numbers), P1, L1 to L4, D3 and the route (decided; they follow P7's count).

Tests: the rows' own, plus `V3BandedPivotedSolverTest` and the column digests (`V3ConvergenceClosureTest`, `V3StageTraceCalculatorTest`): every digest and trace unchanged. Counters for the report: band factorizations per solve by site; allocated bytes per solve (ThreadMXBean, as `ColumnBench` reads them) before and after.

## B-S1 Fluid step (thread wave-a6, Opus high)

Lane: `science/fluid/network/PassiveStepSolver.java`, `PassiveNetwork.java`, `science/fluid/solver/**`, `science/fluid/thermo/FluidThermodynamics.java` (only `Prepared` and `prepare`), their tests. Reviews: FLUID_STEP_REVIEW P-01, P-03, P-05, P-06, P-12 to P-15. B1: a step allocates 60.7 MB, 34 % of it in `PengRobinsonKernel$Workspace.<init>`.

1. **P-18 (new), a node's prepared terms refilled in place.** `PassiveStepSolver` 5222 (`cachedPrepared[i]=model.prepare(temperature)`) and 5563 (the probe), and `PhaseLayout` 640 and 669, build a new `Prepared` whenever a node's temperature changes, which is every trial; each holds a `TranslatedPengRobinson.Workspace` with a whole kernel workspace (`TranslatedPengRobinson` 243 to 249, 368 to 372). The in-place form exists (`TranslatedPengRobinson.prepare(temperature, workspace)`, 373). Change: each node slot of the pass keeps one `Prepared` for the evaluation and one for the probe, refilled in place when the temperature differs. Pitfalls: a `Prepared` must not be read after its slot is refilled (`cachedStates` and `cachedTransport` of the same node are rebuilt with it: confirm in the code; `basePrepared` at 5563 is the reason for the second slot); the kernel workspace's stamps and support growth (`PengRobinsonKernel` 429 to 456) must behave as on a fresh workspace: a refilled workspace gives the same bits only if nothing but the temperature terms and the support carries over, so read `prepare` and `carryOnto` before relying on it. If a refill is not bit-equal to a fresh one, report where and stop this item.
2. **P-01** (delete the unused sparsity build at 5540), **P-03** (one edge pre-pass), **P-05** (a structure number in place of the identity list), **P-06** (trusted constructor, no graph per trial), **P-12**, **P-13**, **P-14**, **P-15**, as their rows, in this order.

Not in this round: P-07 beyond item 1 (the evaluation workspace: wave F0), P-04 and P-11 (they move last digits), P-10 (RAM against refactorizations: after the bench), P-17 (the sparse LU: its own batch), P-02 and P-09.

Tests: the rows' own, `PassiveStepSolverTest`, `RetainedSolverTest`, `BlockJacobianTest`. Chain-100 and the junction lines are the main thread's lane. Counters for the report: bytes allocated per step on a test island with and without a heat side (ThreadMXBean around the step), kernel workspace constructions per step (expected: 0 after the first step of an island), `luFactorizations`.

## B-T1 Thermo kernel (new thread, Opus high)

Lane: `science/thermo/**` (the kernel, the stability test, `phase/`), `science/fluid/thermo/TranslatedPengRobinson.java`, their tests. Review: THERMO_CORE_REVIEW P27, P21, P23, S13, P20, and its section 2.5 (what allocates per call). B1: the kernel is 41 % of an island step (`correctExcess` 16 %, `evaluatePrepared` 5 %) and 11 to 14 % of a column solve; `selectRoot`'s record is 2 to 3 % of a column solve's bytes; the software `pow`, `exp` and `log` are about 12 % of a step's samples.

1. **P27**, **P21**, **P23**, **S13**, as their rows, in this order. Each is small and identical.
2. **P20, the kernel workspace split** into shared temperature terms and per-user mixture scratch: write the design first (which arrays are temperature-only, which are per mixture, how the stamps and the support growth at 429 to 456 carry over) as a section of your report, send it to the main thread as a decision, and implement it only on the answer. B-S1 item 1 removes the per-trial construction on the network side; P20 is what removes the three or four preparations per engine workspace.
3. **`correctExcess` (1008 onward), a reading, no change.** It is the largest self time of a step. Its comment records a measured reason for forming the classical part over the non-partners directly (W3B_NETWORK_PLAN section 11), so that loop stays (AGENTS.md, "Small physics simplifications", last rule). Report: its cost in operations per evaluation at 47 compounds with water present (every centre active), what in it depends on the temperature alone and is recomputed per evaluation, and whether any saving keeps the bits. No code.

Not in this round: P22, P25, P26 (they move results: wave H), P24 (after the engine's split, wave D).

Tests: the rows' own; the engine's digest gate and `G3F8ConservationAndDerivativeTest`; `PengRobinsonKernelPairInteractionsTest`. Counters: `pow` calls per preparation (a test counter around `PairInteractions.evaluatePair`), kernel evaluations per `derivatives` call (P23: one in place of two), records allocated per evaluation (P27: none).

## B-R1 Runtime (thread wave-a5, Opus high)

Lane: `runtime/**` except `runtime/fluid/ShaftTrains.java`, `PumpHealth.java`, `ElectricStores.java` (machines), `network/FluidNetwork.java`, their tests. Review: RUNTIME_REVIEW R6, R1, R2, R4, R22, R23, R24, R10; the follow-up A5 named (`stored(read)` and its two callers in `FluidNetwork` 288, 436: call the read directly, delete `stored`).

1. **R6 option A** (decided, PHASE2_PLAN decision 5): one worker is kept for islands while any island is ready, and the plant job's deadline starts when a worker starts it, not when it is queued. Expected: with two workers and two running columns or stills, islands still step on one worker; a plant job waits longer under island load and no longer times out in the queue. This changes scheduling, not results: each island's and each job's result is the same bits, their order of completion may differ. Test as the row: a scheduling unit test with a stub kernel (two workers; two long plant jobs and three ready islands: an island is admitted while both plant jobs are known; the second plant job waits; its deadline counts from its start).
2. **The `stored` follow-up**, then **R1**, **R2**, **R4**, **R22**, **R23**, **R24**, as their rows. R1's saving is stated by the row's counter (`islandSnapshots` per bucket, before and after); B1 did not measure it (no viewer in its scenario).
3. **R10** last: it changes the tick a display is published on (wire timing only). List the GameTests that pin a publish tick.

Tests: the rows' own; `PresentationBucketTest`, `IslandSchedulerTest` (holdGates), `BoundedCpuSolveServiceTest`. GameTests are the main thread's lane.

## B-M1 Machines (thread wave-a3, Opus medium)

Lane: `runtime/fluid/ShaftTrains.java`, `ElectricStores.java`, `Ejectors.java`, and in `runtime/fluid/WorldTopologyLedger.java` only the shaft and store accessors (`shafts`, `shaft`, `stores`, 485 to 503) with the position index M-09 needs; their tests. The rest of `runtime/**` is B-R1's. Review: MACHINES_REVIEW M-08, M-09, M-10, M-11, M-18. B1: with 20 certified trains `WorldTopologyLedger.shafts()` (`Map.copyOf`) is 92 % of the bytes and `ShaftTrains.decide` walks its `TreeMap` every tick; the cost is 11 µs per 100 ticks today and linear in units.

Items, in order, as their rows: M-09 (publish the stepped train's members only; the ledger gives an unmodifiable view and a position index; no use may come to depend on a map's iteration order), M-08 (one `out` map and one walk, the overwrite precedence kept, which now includes wave A's M-04 rule in `failures`), M-10, M-11, M-18. M-01 and M-07 (the step result carries each mover's shaft power) are decided but not in this round: they wait for B-S1, which changes the step's result type.

Tests: the rows' own (`ShaftPlanTest`, `ShaftCouplingTest`, `WorldTopologyLedgerTest`, `SteamEjectorRuntimeTest`, `PumpHealthTest`). Counter for the report: bytes allocated per 100 ticks for a rig of certified trains before and after (ThreadMXBean, as `tools/codebase-review-bench/MachineBench.java` reads them; read its numbers from PROFILE_REVIEW for "before").

## B-E1 Equipment geometry (thread wave-a2, Opus medium)

Lane: `science/equipment/**`, the caches E13 lists in `client/**` (only the cache fields and their eviction), their tests. Review: EQUIPMENT_REVIEW E11, E12, E13.

Items, in order, as their rows: E12 (the built-unit cache keyed by the shape with its front set to north; first confirm in the code that no design reads `front`), E11 (a designed unit's drawing built once per detail level when `drawing` is on; the server path unchanged), E13 (one `BoundedCache`, least recently used, bounded by weight, in place of the 22 caches that clear everything when full). Every geometry, mesh and layout stays bit for bit: a cache changes when a value is built, never the value. E13 pitfall: a cache read on the render thread and on a worker needs the same thread safety its old form had; say for each of the 22 which threads read it.

Tests: `EquipmentLayoutGoldenTest`, the facet-area tests, `EquipmentShapeCompatTest` (`equipmentGates`). Counter for the report: builds per designed unit per geometry (E11: one in place of two or three), cache entries per heading (E12: one in place of four), rebuilds when a 65th shape enters view (E13).
