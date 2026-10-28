# Vacuum-column feed and distillation test

Status: Concluded 2026-10-28 (offline investigation on `codex/vacuum-column-test`; not merged).

## History

- 2026-10-28 — baseline `79b853e0` (0.17.1): accepted default CDU solve; exact bottoms feed export; three operating forms (wet, dry, reduced-stage) plus an initial wet-route replay and inlet-energy correction. Every VDU attempt returned NONCONVERGENCE. Independent CDU component closure < 1.61e-8 mol/s, every transferred feed component unchanged; no production code, Gradle gates, client or world changes.

## Documents

- [VACUUM_COLUMN_REVIEW.md](VACUUM_COLUMN_REVIEW.md): expected physics, sources, complete chosen configuration, measured outcomes and proposed next work.
- [../../tools/vacuum-column-test/README.md](../../tools/vacuum-column-test/README.md): executable harness, input cases, generated artifacts, import instructions and limitations.

## Code areas and open items

Read: `science/column/v3/`, `science/material/`, material presets/packages/bases/properties, and the client preset decoder. Changed: documentation and the offline tool only; code guide: no change.
Open: investigate low-pressure stage continuation and phase/trace support before claiming successful vacuum yields. A packed-column pressure model and validated side-cut/cooling settings remain separate design work. The exported column is an unconverged test input.
Raw outputs and loadable exports are retained in this worktree's `tmp/vacuum-test/` while the branch remains unmerged; they are not tracked. Re-run the documented commands to regenerate them.
