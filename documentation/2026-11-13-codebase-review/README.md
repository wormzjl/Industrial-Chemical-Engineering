# 2026-11-13 code base review and revamp

**Status:** In progress since 2026-11-13. Phase 1 (review and measurement, no production code change) is planned; its delegations wait for the owner's approval.

Owner request: a general review of the code base (performance, coherence, maintainability), the fluid network solver and the column solver de-correlated, a Rust port evaluated (Java fallback), room for supercritical fluids, the rotating machines benched and their failure modes made complete and coherent, a better documentation structure and a multi-agent collaboration guide.

Branch `claude/codebase-review-refactor-8a85ef`, base `259d22a2c` (main 0.30.0).

## History

- 2026-11-13: survey in the main thread (sizes, import matrix, native lane, wear, documentation and `tools/` sizes); [REVIEW_PLAN.md](REVIEW_PLAN.md) written. No test run: documents only.
- 2026-11-13: owner notes: the column solver goes to Rust, extending the limited C++ lane, Java as the fallback; Fable may be a subagent for difficult tasks; an optimization need not keep results byte-identical, the shift small and justified. Plan revised (section 2 "Rust" and "Results", S1, S3 and S5 on Fable, S8 added for the column and network meeting points, S5 writes the Rust port plan); [BRIEFS.md](BRIEFS.md) written. Read for it: `src/main/cpp` (PR outer pass only; platform `cbrt`, `acos`, `cos`), Rust target `x86_64-pc-windows-msvc` only, no zig. No test run: documents only.

## Documents

- [REVIEW_PLAN.md](REVIEW_PLAN.md): the survey's findings, the direction, the phase 1 work packages, the synthesis documents, and where AGENTS.md and CLAUDE.md do not fit.
- [BRIEFS.md](BRIEFS.md): the common rules and the full brief of each phase 1 package (S1 to S8, P1 to P4, B1).

## Code areas

None changed.

## Open items

- Owner: approve the phase 1 delegations (REVIEW_PLAN.md section 3) and answer section 5, items 1, 4 and 7.
- Owner decisions recorded 2026-11-13: Rust column solver with a Java fallback; results may move by a small, justified amount; Fable allowed as a subagent for difficult tasks.
