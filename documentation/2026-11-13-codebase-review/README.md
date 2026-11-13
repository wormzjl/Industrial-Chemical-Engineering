# 2026-11-13 code base review and revamp

**Status:** In progress since 2026-11-13. Phase 1 (review, no production code change) is written but for the profile: the bench sub-thread (B1) still runs. Phase 2 (implementation) has not started; it waits for the owner's decisions ([PHASE2_PLAN.md](PHASE2_PLAN.md), last section).

Lane: documents and tools. Class: research (phase 1).

Owner request: a general review of the code base (performance, coherence, maintainability), the fluid network solver and the column solver de-correlated, the column solver ported to Rust (Java fallback), room for supercritical fluids, the rotating machines benched and their failure modes made complete and coherent, a better documentation structure and a multi-agent collaboration guide.

Branch `claude/codebase-review-refactor-8a85ef`, base `259d22a2c` (main 0.30.0). Bench branch `claude/codebase-review-bench-930e08` (its own worktree; not merged here).

## Owner decisions (quoted, 2026-11-13)

- "there is currently a C++ column solver but might be quiet limited, I want to extend it and make the solver to rust": the column solver goes to Rust, Java as the fallback.
- "results does not need to be byte identical with the optimizations (but shouldn't be too far off either, justify)": a reference may move with a stated, justified shift. That each path stays deterministic, and that Rust need not match Java bit for bit, is the main thread's reading.
- "For some difficult tasks, you may want to use fable as subagent."
- "Approve all": the twelve review subagents and the bench sub-thread of REVIEW_PLAN section 3.
- "Start A and J": phase 2 begins with wave A (defects) and wave J (documents); PHASE2_PLAN decision 1. The other thirteen decisions are open.
- Plan update given by the owner on 2026-11-13, which it says came from the owner's question of that day (the question itself was not put in this session, so it is not quoted): the thermo kernel is ported with the column, at wave E stage (b); the fluid step's port is gated (F0 in Java always; a re-measurement of an island step after F0 and the wave B fluid and thermo batches; F1 only if kernel calls, residual and Jacobian assembly and the LU are still the large majority of step time and step cost still limits plant size); the equilibrium engine is not ported unless a profile shows flashes as the top cost after P20, P21, P24 and P25. Recorded as decision 15 of PHASE2_PLAN.
- "You have full authority over this subject, drive it end-to-end." (2026-11-13). The main thread's reading and the decisions it took under it: PHASE2_PLAN, "Decided (2026-11-13)". Still the owner's: a merge to `main`, a push, the version number.

## History

- 2026-11-13: survey in the main thread (sizes, import matrix, native lane, wear, documentation and `tools/` sizes); [REVIEW_PLAN.md](REVIEW_PLAN.md) written (042031a03). No test run: documents only.
- 2026-11-13: plan revised for the owner's notes; [BRIEFS.md](BRIEFS.md) written (5f61d3139). No test run.
- 2026-11-13: the twelve reviews delivered by subagents (S1, S3, S5 on Fable high; S2, S4, S6, S7, S8 on Opus high; P1, P2, P3 on Opus medium; P4 on Sonnet medium) and committed (375d3c746). The main thread read the cited lines of 28 findings: 20 held in full, 8 in the part read, none refuted. No test run.
- 2026-11-13: synthesis by the main thread: the six documents below and `documentation/reference/COLLABORATION.md` (f6e2e38d6). No test run.
- 2026-11-13: owner "Start A and J". The main thread read the code of wave A's findings and wrote [WAVE_A_BRIEFS.md](WAVE_A_BRIEFS.md): six lanes (A1 column, A2 saves, A3 machines, A4 sheet against world, A5 runtime, A6 fluid) and J1 (guide README, history out of the chapters, `FORMATS.md`). M-04 confirmed by reading; C10, C19, D-01, L-F19 moved out of wave A; M-01 and M-07 wait for decision 3. No test run.
- 2026-11-13: owner's plan update on the Rust ports applied: PHASE2_PLAN wave F in three parts (F0, the gate, F1) with the engine left in Java, its order and decision 15; SOLVER_DECOUPLING_PLAN sections 6.1 to 6.3; CODEBASE_REVIEW section 6 item 8 and the X30 row of SIMPLIFICATIONS_REVIEW aligned. The gate's two readings (two thirds of step time; the bench's pace counters) are the main thread's proposal, to be confirmed when the gate is run. No test run.
- 2026-11-13: three wave A briefs amended after the threads asked for a decision, each claim checked in the code by the main thread first (6d054cf9f): C4 is an inverted superheat test (`ColumnSolve.java:218`), not only a fault above 647 K; F1 has four more readers of the column identity (the learned route would have fallen to the classical backup without a failure); P3-01 needs its two callers. See WAVE_A_BRIEFS, "Amendments". No test run.
- 2026-11-13: J1 delivered and checked by the main thread: `code-guide/README.md` 397 to 112 lines (99 to 32 KB), new `code-guide/HISTORY.md` and `documentation/reference/FORMATS.md`, stale texts corrected in `fluid-runtime.md` and `rotating-machinery.md`. Checks: of 5,111 old non-blank guide lines, 30 changed (all the brief's corrections) and 126 headings lost their date, none dropped; all 30 rows of FORMATS.md read against their constants; 490 relative links of the touched files resolve. The chapters did not shrink (about 105 dated sections hold the only description of a current behaviour and stayed, without their date). No test run.
- 2026-11-13: B1 delivered `PROFILE_REVIEW.md` and `tools/codebase-review-bench/` on `refs/heads/claude/codebase-review-bench-930e08` (42caac27b), not merged here. The main thread read it, checked the code behind the band solver, the route and the sparse solve findings, and folded it into PHASE2_PLAN (wave B order with new items P7 and P-17, wave E "What the profile changes", the gate's readings on today's code, decisions 7, 8 and new 16) and SOLVER_DECOUPLING_PLAN (6.1, 6.3). Three plan statements changed: the analytic Jacobian is not the column's largest gain (the band solver is 52 to 63 % of a solve); the running column never takes the learned start, which costs a ninth of the classical chain in checkpoints; on `mixed100` the islands keep pace with the workers 21 % busy, so the fluid gate's need is not met on today's code. The wave A threads were then given the Gradle lane two at a time. No test run in this worktree.
- 2026-11-13: owner "Yes, merge": B1's branch merged into this branch (documents and `tools/` only).

- 2026-11-13: B1 profile (sub-thread, branch `claude/codebase-review-bench-930e08`): [PROFILE_REVIEW.md](PROFILE_REVIEW.md), JFR CPU and allocation of the fluid step (`fluidServerBenchmark` on `mixed100`, 60 + 60 s; the default `one` stops collecting once its island certifies), the column cold and warm (`medium_crude`, literature) and 20 shaft trains; harness `tools/codebase-review-bench/`. Timed runs alone; no suite run.

## Documents

Synthesis (main thread):

- [CODEBASE_REVIEW.md](CODEBASE_REVIEW.md): modules, structural issues ranked, 23 defects, optimizations by class, what the reviews corrected in the plan.
- [SOLVER_DECOUPLING_PLAN.md](SOLVER_DECOUPLING_PLAN.md): how the solvers are correlated, the target layers, the two thermo ports, the de-correlation steps, determinism and results, the Rust decisions, room for supercritical fluids.
- [MACHINE_FAILURE_MODES_REVIEW.md](MACHINE_FAILURE_MODES_REVIEW.md): expected behaviour, what the code does, fixes, the failure-mode matrix as owner options.
- [SIMPLIFICATIONS_REVIEW.md](SIMPLIFICATIONS_REVIEW.md): every change that moves a reference, with its effect, what it deletes, its risk and a recommendation.
- [DOCS_AND_COLLABORATION_PLAN.md](DOCS_AND_COLLABORATION_PLAN.md): the code guide's new shape, `tools/`, the proposed AGENTS.md amendments, what the delegation showed.
- [PHASE2_PLAN.md](PHASE2_PLAN.md): the implementation batches in order, and the decisions for the owner.
- [`../reference/COLLABORATION.md`](../reference/COLLABORATION.md): the guide for agents (lanes, numbers, sizes, briefs, results that move, Java and Rust twins, hand-over).

Reviews (subagents): [THERMO_CORE_REVIEW.md](THERMO_CORE_REVIEW.md) (S1), [THERMO_ADAPTERS_REVIEW.md](THERMO_ADAPTERS_REVIEW.md) (S2), [FLUID_STEP_REVIEW.md](FLUID_STEP_REVIEW.md) (S3), [FLUID_LAWS_REVIEW.md](FLUID_LAWS_REVIEW.md) (S4), [COLUMN_REVIEW.md](COLUMN_REVIEW.md) and [RUST_COLUMN_PORT_PLAN.md](RUST_COLUMN_PORT_PLAN.md) (S5), [RUNTIME_REVIEW.md](RUNTIME_REVIEW.md) (S6), [MACHINES_REVIEW.md](MACHINES_REVIEW.md) (S7), [PLANT_COUPLING_REVIEW.md](PLANT_COUPLING_REVIEW.md) (S8), [EQUIPMENT_REVIEW.md](EQUIPMENT_REVIEW.md) (P1), [CLIENT_REVIEW.md](CLIENT_REVIEW.md) (P2), [FLOWSHEET_CONTROL_REVIEW.md](FLOWSHEET_CONTROL_REVIEW.md) (P3), [INVENTORY.md](INVENTORY.md) (P4).

Plan and briefs: [REVIEW_PLAN.md](REVIEW_PLAN.md) (its section 2 is replaced by SOLVER_DECOUPLING_PLAN), [BRIEFS.md](BRIEFS.md), [WAVE_A_BRIEFS.md](WAVE_A_BRIEFS.md).

Profile (bench sub-thread B1): [PROFILE_REVIEW.md](PROFILE_REVIEW.md): the Java baseline (wall, CPU, allocation, GC, retained heap, time share by kind) of an island step, a column solve and 20 shaft trains; harness `tools/codebase-review-bench/`.

## Code areas

None changed.

## Open items

- Running threads (owner: "Approve all seven", 2026-11-13), each on its own branch and worktree cut from this branch, none merged here: `wave-a1` (column, Opus high), `wave-a2` (saves, Opus medium), `wave-a3` (machines, Opus medium), `wave-a4` (sheet against world, Opus medium), `wave-a5` (runtime, Opus high), `wave-a6` (fluid, Opus high). The wave A threads run no Gradle until the main thread sends "lane free" (after the bench thread's timed runs; then two at a time). Their reports: `wave-a/A<n>_REPORT.md` on their branches.
- Not measured by B1: the `viewers` profile (R1, R2), one large awake island, the native column path, the first rung with and without the inside-out trial (L1).
- Code guide, left by J1: the chapters still carry their batch sections (shrinking them needs the chapter split that follows waves C and D); old format numbers stated as current outside `fluid-runtime.md` (`process-control.md` "formats are 14/4/3", `equipment.md` `equipment-21`, `cooling-towers.md` `fluid-21`); `verification.md` links `LegacyNetworkPathPinTest.java`, which does not exist, and names an old lane baseline (`891c8acf5`); the items of DOCS_AND_COLLABORATION_PLAN 2.5 outside the guide (the gui-creation skill, the `PumpHealth`, `PhaseDraw` and `ProcessSolveServices` comments).
- Shape fields (12) have no production constant, only `EquipmentShapeCompatTest.SHAPE_FIELDS`; the planned `FormatsTableTest` needs one or reads the test constant.
- The plan's decisions are taken (PHASE2_PLAN, "Decided (2026-11-13)"); the owner can change any of them.
- Owner: accept or change `documentation/reference/COLLABORATION.md` (it is marked "proposed").
- Not verified by the main thread: the findings marked "no" in CODEBASE_REVIEW section 4, and every number marked as an estimate.
