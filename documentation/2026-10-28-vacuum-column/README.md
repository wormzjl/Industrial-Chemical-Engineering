# Vacuum-column feed and distillation test

Status: Concluded 2026-10-29 (offline investigation on `codex/vacuum-column-test`; not merged).

## History

- 2026-10-28 — baseline `79b853e0` (0.17.1): accepted default CDU solve; exact bottoms feed export; three operating forms (wet, dry, reduced-stage) plus an initial wet-route replay and inlet-energy correction. Every VDU attempt returned NONCONVERGENCE. Independent CDU component closure < 1.61e-8 mol/s, every transferred feed component unchanged; no production code, Gradle gates, client or world changes.
- 2026-10-29 — follow-up to `8d511f71`: traced the unchanged failure, independently reproduced two accepted startup states containing the same liquid labelled as vapor, measured the projection's energy-defect amplification and located the 900 K range wall. Three collapsed pairs and two valid controls verified; diagnosis only, no production changes.

## Documents

- [VACUUM_COLUMN_REVIEW.md](VACUUM_COLUMN_REVIEW.md): expected physics, sources, complete chosen configuration, measured outcomes and proposed next work.
- [VACUUM_STARTUP_REVIEW.md](VACUUM_STARTUP_REVIEW.md): confirmed phase-admission defect, bad-seed amplification and property-range rejection wall.
- [../../tools/vacuum-column-test/README.md](../../tools/vacuum-column-test/README.md): executable harness, input cases, generated artifacts, import instructions and limitations.

## Code areas and open items

Read: `science/column/v3/`, `science/material/`, material presets/packages/bases/properties, and the client preset decoder. Changed: documentation and the offline tool only; code guide: no change.
Open: repair interior phase admission, continuation handoff and package-range handling, then solve the actual requested vacuum column. A packed-column pressure model and validated side-cut/cooling settings remain separate design work. The exported column is an unconverged test input.
Raw outputs and loadable exports are retained in this worktree's `tmp/vacuum-test/` while the branch remains unmerged; they are not tracked. Re-run the documented commands to regenerate them.
