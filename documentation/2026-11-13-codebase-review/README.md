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

## History

- 2026-11-13: survey in the main thread (sizes, import matrix, native lane, wear, documentation and `tools/` sizes); [REVIEW_PLAN.md](REVIEW_PLAN.md) written (042031a03). No test run: documents only.
- 2026-11-13: plan revised for the owner's notes; [BRIEFS.md](BRIEFS.md) written (5f61d3139). No test run.
- 2026-11-13: the twelve reviews delivered by subagents (S1, S3, S5 on Fable high; S2, S4, S6, S7, S8 on Opus high; P1, P2, P3 on Opus medium; P4 on Sonnet medium) and committed (375d3c746). The main thread read the cited lines of 28 findings: 20 held in full, 8 in the part read, none refuted. No test run.
- 2026-11-13: synthesis by the main thread: the six documents below and `documentation/reference/COLLABORATION.md`. No test run.

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

Plan and briefs: [REVIEW_PLAN.md](REVIEW_PLAN.md) (its section 2 is replaced by SOLVER_DECOUPLING_PLAN), [BRIEFS.md](BRIEFS.md).

## Code areas

None changed.

## Open items

- B1 (bench sub-thread): `PROFILE_REVIEW.md` is not in. It orders wave B, decides P1, L1 and P25, and confirms or refutes the cost estimates of R1, G-C4 and the machines' per-step work. PHASE2_PLAN and SOLVER_DECOUPLING_PLAN section 6.3 are updated when it reports.
- Owner: the fourteen decisions of PHASE2_PLAN's last section.
- Owner: accept or change `documentation/reference/COLLABORATION.md` (it is marked "proposed").
- Not verified by the main thread: the findings marked "no" in CODEBASE_REVIEW section 4, and every number marked as an estimate.
