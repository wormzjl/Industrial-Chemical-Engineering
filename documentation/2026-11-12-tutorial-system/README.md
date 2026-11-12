# Tutorial system

Status: **Planned** (2026-11-12). Branch `claude/tutorial-system-chem-mod-950ca9`. No code.

## Scope

Owner, 2026-11-12: a tutorial for players of 18 to 30 with no chemical engineering knowledge. It teaches the mod's
handling and the basic ideas together, progressively and interactively, with animation before words: the still and
the principles of separation first, a help button on each GUI, then the process simulator and the distillation
column, each equipment on its own page chained by a description of the whole unit, and a guided first small crude
distillation unit with a small column template for medium crude. Added the same day: a chapter on what crude oil is,
why it is separated and how it becomes daily products; an item for each equipment kind; JEI pages saying what each
equipment serves; Ponder only if it is sufficient, an own framework otherwise.

## Documents

- [TUTORIAL_SYSTEM_PLAN.md](TUTORIAL_SYSTEM_PLAN.md): what exists, why Ponder is dropped, the framework (films, help
  tours and cards, field guide, equipment items and JEI), the teaching rules, the path of seven chapters, the small
  column template, gates, phases P0 to P6, owner decisions D1 to D8 and open items O1, O2.

## History

- 2026-11-12: plan written from the code and the Ponder 1.0.82 sources (unpacked to `tmp/ponder-src/`). Ponder serves
  the world films only; GUI help and the guided build need an own layer. Documents only, no test run.
- 2026-11-12: owner answers. D1: own framework if Ponder is not sufficient; decided own (plan section 2: no GUI help,
  no objectives, no input in a film, no software-render review, Ponder and Flywheel in every pack), Ponder dropped. D6:
  the first crude from a Forge fluid converter, later an extraction system (neither in the code). D2 to D5, D7 as
  recommended. New: chapter 1 on crude oil, an item for each equipment kind, JEI pages. Plan revision 2. Documents only,
  no test run.

## Code areas (proposed)

- `science/tutorial/` (guide, tours, scenes, films, cards), `client/gui/tutorial/` and the GUI kit (`?` key, scene
  view, tours, cards, guide screen), `runtime/tutorial/` (advancement criteria), `compat/jei/`, the equipment items,
  `assets/icheme/tutorial/`, `assets/icheme/columnpreset/` (the small crude tower), `tools/tutorial-renders/`,
  `tools/tutorial-recordings/`.

## Open items

- O1: equipment items reference only, or given to a survival player (proposed: reference only).
- O2: the Forge fluid converter is a prerequisite of chapter 2 in survival and is not in this plan.
- D4: the small column, chosen by the owner after the three candidates are solved.
- The first batch (P0 to P2) waits for the owner's word to start.
