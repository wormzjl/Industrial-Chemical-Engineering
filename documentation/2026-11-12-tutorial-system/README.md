# Tutorial system

Status: **Planned** (2026-11-12). Branch `claude/tutorial-system-chem-mod-950ca9`. No code.

## Scope

Owner, 2026-11-12: a tutorial for players of 18 to 30 with no chemical engineering knowledge. It teaches the mod's
handling and the basic ideas together, progressively and interactively, with animation before words: the still and
the principles of separation first, a help button on each GUI, then the process simulator and the distillation
column, each equipment on its own page chained by a description of the whole unit, and a guided first small crude
distillation unit with a small column template for medium crude. Ponder where possible, an own framework otherwise.

## Documents

- [TUTORIAL_SYSTEM_PLAN.md](TUTORIAL_SYSTEM_PLAN.md): what exists, the verdict on Ponder, the three layers (films,
  help tours and concept cards, field guide), the teaching rules, the path of chapters, the small column template,
  gates, phases P0 to P4, owner decisions D1 to D7.

## History

- 2026-11-12: plan written from the code and the Ponder 1.0.82 sources (unpacked to `tmp/ponder-src/`). Ponder serves
  the world films only; GUI help and the guided build need an own layer. Documents only, no test run.

## Code areas (proposed)

- `compat/ponder/` (films), `client/gui/tutorial/` and the GUI kit (`?` key, tours, cards, guide screen),
  `science/tutorial/` (guide data), `runtime/tutorial/` (advancement criteria), `assets/icheme/tutorial/`,
  `assets/icheme/ponder/`, `assets/icheme/columnpreset/` (the small crude tower), `tools/tutorial-recordings/`.

## Open items

- Owner decisions D1 to D7 (plan section 8). D6: where a survival player's first crude comes from.
- P0 spike: `equipment_shell` cells in a Ponder level, scene keys that are no item, nested-jar packaging.
