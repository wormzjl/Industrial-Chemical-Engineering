# reference

Purpose: Living guides that are not tied to one batch

Status: Reference. Index: [../INDEX.md](../INDEX.md).

## Documents

- [code-guide/README.md](code-guide/README.md)
- [code-guide/HISTORY.md](code-guide/HISTORY.md): the guide's old snapshot paragraphs and batch sections, kept for reference, not maintained (moved 2026-11-13)
- [FORMATS.md](FORMATS.md): every saved and wire format with its current value, the constant that declares it, its kind (facts, state, wire) and what a mismatch does; read from the code at `259d22a2c`, 2026-11-13
- [rules/](rules/): the full agent rules by subject (saves, engine-schedule, physics, measurement, in-game-checks, equipment, documentation, native); `AGENTS.md` keeps the core of each and says when to read the file (2026-11-16, batch `2026-11-16-agents-md-token-usage`)
- [COLLABORATION.md](COLLABORATION.md): working together on the code base (lanes, numbers, briefs, results that move, hand-over); proposed 2026-11-13, batch `2026-11-13-codebase-review`

## Code areas

whole `src/`

## History

- Reference
- progressive-disclosure code guide `code-guide/` rewritten 2026-10-15 at `main` `ac3a00fc` (0.7.0), snapshot moved to `6275d4d6` at the merge: README (levels 1-2, routing, open findings for the owner) plus six chapters (column-v3, fluid-science, fluid-runtime, thermo-materials, client-gui, verification)
- refreshed 2026-10-19 for release 0.9.0 (snapshot `5b49f940`; new chapter `equilibrium-engine.md` for `science/thermo/phase/**`, so README plus seven chapters; t47 from `2026-09-24-coolprop-low-temperature/MERGE_STACK_DRAFTS.md`)
- refreshed 2026-10-19 for release 0.9.1 (snapshot `a08c7134`: fluid-science, fluid-runtime, equilibrium-engine, thermo-materials, verification and README; column-v3 and client-gui unchanged; t55)
- updated before every merge to `main` (AGENTS.md rule, Implemented 2026-10-15 on `main` as `bef39fc1`, with the stale-comment fixes `6275d4d6`, no version bump by owner decision)
- `HYBRID_SOLVER_CODE_GUIDE.md` (e8d8937, 2026-09-08) superseded 2026-10-15, now a pointer, full text in `archive/HYBRID_SOLVER_CODE_GUIDE_e8d8937.md`
