# Ethanol compound

Status: **Planned** (2026-11-11). Branch `claude/ethanol-compound-matrix-4b318d`, cut from main `ff6f6892f` (0.26.0).

## Scope

Owner, 2026-11-11: add ethanol to the compound matrix, identify what it affects and propose a solution.

## Documents

- [ETHANOL_COMPOUND_REVIEW.md](ETHANOL_COMPOUND_REVIEW.md): the physics, what the code does, what the addition touches, and the proposal (step 1 the data import; step 2 options A to E for ethanol with water).

## History

- 2026-11-11: review written. No code or data changed; no test run.

## Code areas

None yet. Named by the review: `src/main/resources/data/icheme/materials/`, `tools/compound-import/`, `science/thermo/phase/FluidTpEquilibrium` (`solveWet`), `science/fluid/network/StillColumn`.

## Open items

- Owner decisions: step 1 go and the creative-source preset; step 2 option A, B or E.
