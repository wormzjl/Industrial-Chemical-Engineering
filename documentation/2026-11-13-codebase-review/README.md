# 2026-11-13 code base review and revamp

**Status:** In progress since 2026-11-13. Phase 1 (review and measurement, no production code change) is planned; its delegations wait for the owner's approval.

Owner request: a general review of the code base (performance, coherence, maintainability), the fluid network solver and the column solver de-correlated, a Rust port evaluated (Java fallback), room for supercritical fluids, the rotating machines benched and their failure modes made complete and coherent, a better documentation structure and a multi-agent collaboration guide.

Branch `claude/codebase-review-refactor-8a85ef`, base `259d22a2c` (main 0.30.0).

## History

- 2026-11-13: survey in the main thread (sizes, import matrix, native lane, wear, documentation and `tools/` sizes); [REVIEW_PLAN.md](REVIEW_PLAN.md) written. No test run: documents only.

## Documents

- [REVIEW_PLAN.md](REVIEW_PLAN.md): the survey's findings, the direction, the phase 1 work packages, the synthesis documents, and where AGENTS.md and CLAUDE.md do not fit.

## Code areas

None changed.

## Open items

- Owner: approve the phase 1 delegations (REVIEW_PLAN.md section 3) and answer section 5, items 1, 4 and 7.
