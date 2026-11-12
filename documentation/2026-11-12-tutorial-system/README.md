# Tutorial system

Status: **In progress since 2026-11-12**. Branch `claude/tutorial-system-chem-mod-950ca9`.

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
- [P0_SCENE_PLAYER.md](P0_SCENE_PLAYER.md): phase P0, the scene player, its view, the render tool and the film "inside
  the still": classes, decisions, tests, renders, what the in-game check looks at.
- [P1_GUIDE_AND_HELP.md](P1_GUIDE_AND_HELP.md): phase P1, the field guide, progress as advancements, the `?` key and
  its tours, the card view: classes, decisions, tests, renders, what the in-game check looks at.
- [P2A_CRUDE_CHAPTER.md](P2A_CRUDE_CHAPTER.md): phase P2a, chapter 1 "Crude oil": the card interface, the cards chain,
  barrel, products and road, the crude barrel recording, decisions, band shares, tests, renders.
- [P2B_STILL_CHAPTER.md](P2B_STILL_CHAPTER.md): phase P2b, chapter 2 "The still": the recorded run, the film tracks,
  the films still_build and still_run, the card pot_temperature, the still criteria, decisions, derived lines, tests.
- [P3_ITEMS_AND_JEI.md](P3_ITEMS_AND_JEI.md): phase P3, the 38 equipment reference items, their icons and lines, the
  Equipment guide tab, the JEI pages, the shape-only kinds, the gate.
- [SMALL_COLUMN_CANDIDATES_REVIEW.md](SMALL_COLUMN_CANDIDATES_REVIEW.md): P4, the three small crude tower candidates
  solved and sized on the crude unit at 1/64, the acceptance rules, the steam source, the recommendation (D4).

## History

- 2026-11-12: plan written from the code and the Ponder 1.0.82 sources (unpacked to `tmp/ponder-src/`). Ponder serves
  the world films only; GUI help and the guided build need an own layer. Documents only, no test run.
- 2026-11-12: owner answers. D1: own framework if Ponder is not sufficient; decided own (plan section 2: no GUI help,
  no objectives, no input in a film, no software-render review, Ponder and Flywheel in every pack), Ponder dropped. D6:
  the first crude from a Forge fluid converter, later an extraction system (neither in the code). D2 to D5, D7 as
  recommended. New: chapter 1 on crude oil, an item for each equipment kind, JEI pages. Plan revision 2. Documents only,
  no test run.
- 2026-11-12: P0 built: `science/tutorial/scene`, film `still_inside` (three cut actors, two-sided cut faces),
  `client/gui/tutorial` (`SceneView`, `FilmScreen`, `/icheme-film`), `tools/tutorial-renders`, `ShapeRasterizer.stillParts`.
  Filtered tests pass (tutorial 12, HeatDiagram, KitLayout, DeterministicMathSource, ProcessUnit, ShapeRasterizer);
  renders in `tmp/tutorial-renders/`. The owner's in-game check is open.
- 2026-11-12: P1 built (a78faaed0, 4ae9e8371, 41e1e81ae, 31b5ba02a): guide data and chapter 0, progress as
  advancements (`guide_step`, `equipment_built`, protocol `tutorial-1`), the Field Guide item (placeholder icon),
  `GuideScreen`, HUD line, key H, the `?` key and tours on the guide and the still's page, the card `three_parts`.
  21 filtered test classes pass; renders in `tmp/tutorial-renders/`. The fluid GameTests wait for the final lane.
- 2026-11-12: P2a built (07ae749e5, 849cf20ef, aa9120b0d, 3450240c6, 923f39d53, 207577d1f, 5aa24fd86): three_parts mixed
  at heat 0, the card interface (options, hits, steps, temperature format), cards chain, barrel, products, road, the
  crude barrel recording (`tools/tutorial-recordings`), chapter 1 and its advancements. 17 filtered test classes pass.
- 2026-11-12: P2b built (5e5d3e25a, 8922ffd73, 05c2e81da, a90748849, 7d1cbd1f1): the recorded still run, film tracks,
  films still_build and still_run, still_inside reworked, card pot_temperature, chapter 2 with criteria still_state,
  still_stopped and diverter_switched. 21 tutorial test classes (93 tests) pass; renders in `tmp/tutorial-renders/p2b/`.
  Then, on the parent's answer: the step `water` (media `read`), and the recording's `ended_at` set to `law_refusal`.
- 2026-11-12: P2b re-recorded on the fixed still law (97e1a949f, dcfbe7eb1): the run ends at the overheat cut, 350 °C,
  41.8 % distilled; film still_run in four stops, the card's fire out at the end of kerosene, the stop step at 21
  minutes of play, the term boil dry dropped. 21 tutorial test classes pass.
- 2026-11-12: P3 built (a917bf05e, a122ccb00, 2f6a50f8a, 8fe18f508, a69441950): `EquipmentInfoItem` for each of the 38
  kinds and the Equipment guide tab, 64 px icons baked by `IconRender` (owner: 32 px too coarse), serves, needs and
  place lines, `IChemEJeiPlugin` pages for every item, `EquipmentInfoCoverageTest`; O1 decided: reference only.
- 2026-11-12: merged into this branch, on the owner's word ("Merge now"): the still branch (59f05855f: the still law per
  mole of distillate on the aqueous branch's column, the partial condenser at the water plus 5 K, the closed valve on
  dry trays) and the aqueous branch's newer tip (5106f0c15: main 0.28.0, fluid conversion). Conflicts only in
  `documentation/INDEX.md` (every row kept). No test run.
- 2026-11-12: the tutorial on the merged base (29d05694c, c074b11a9, 49175330f, 818bfb8a8): the recorder compiles on the
  W4 column; the still run recorded again (OVERHEAT at 350 °C, 145 steps, 41.0 % distilled, 1.5 % vented, no
  COOLING_LIMITED; first drop a trace at 1.2 min, a new trickle line at 4.3 min); first and cut count liquid at the spout;
  the charge step names the Fluid converter; the card's cursor breaks round its words; items for the two converter kinds
  (40 items). `crude_barrel.json` unchanged. 29 classes (222 tests) pass. P2B_STILL_CHAPTER.md.
- 2026-11-12: P4 small column candidates (`tools/tutorial-column`): A (42 trays / 64), B (20), C (14) each run once on the
  crude unit at 1/64 with Size all. A needs 1.5 m (no nozzle row at 1.0 m), B floods at 1.0 m (Size all 1.5 m), C as asked
  hits the draw wall and its variation 1 (pumparound above the draw) stands at 1.0 m. Every candidate is yellow on the
  smallest pump and exchanger at 10 m³/h and red on the V-101 path. Recommended: C variation 1. No preset registered, no
  production change, no test run. SMALL_COLUMN_CANDIDATES_REVIEW.md.
- 2026-11-12: P4 on the parent's decisions: A kept as the scaled reference (refused at 1.0 m, Size all 1.5 m). B and C
  aimed at the barrel with 14 recorded solves each (tools/tutorial-column/tuning.json): reflux 1.5, heater outlet 390 C;
  B 17.5/11.1/23.6/47.8 %, C 18.2/34.9/46.9 %, both at 1.0 m. V-101 at 3.20 bar makes the path green on all three (no
  pump head). Datasheets split the lamps: real fails (S-6 slow two-phase line on all three) and "larger than needed"
  (catalogue's smallest pump and shell; C all green from about 195 kmol/h, 2.5 m). B and C need about 123 kg/h of bottoms
  as fuel beside the off-gas. Recommended: C variation 1. Tool and documents only, no test run.

## Code areas (proposed)

- `science/tutorial/` (guide, tours, scenes, films, cards), `client/gui/tutorial/` and the GUI kit (`?` key, scene
  view, tours, cards, guide screen), `runtime/tutorial/` (advancement criteria), `compat/jei/`, the equipment items,
  `assets/icheme/tutorial/`, `assets/icheme/columnpreset/` (the small crude tower), `tools/tutorial-renders/`,
  `tools/tutorial-recordings/`.

## Open items

- Merge order (owner, 2026-11-12): this branch merges after `claude/ethanol-compound-matrix-4b318d`. It now holds that
  branch (at 6b8d03389, with main 0.28.0) and the still branch `claude/still-on-aqueous-fdba0c` (at 1b1ad1d07); when the
  aqueous branch changes again, merge it again. The still branch has had no code review and no final lane.
- After the merges: done. The tutorial compiles, is recorded and gated on the merged base; the still law's own classes
  and the fluid GameTests wait for the final lane.
- The still with trays on crude light ends answers as a pot still (the still batch's option B is open): chapter 3 must
  not show a trayed still on crude until it is fixed.

- O1: decided 2026-11-12, reference only (creative tab and JEI).
- P3: the owner's review of the icon sheet and the 160 lines; the in-game rows of P3_ITEMS_AND_JEI.md; icons alike at
  1x (exchanger and kettle, grate boiler and fixed-bed gasifier) wait for their models.
- O2: the charge step names the Fluid converter (bundled rules: `#c:crude_oil` of TFMG and PneumaticCraft, `ad_astra:oil`).
  A survival player without one of those mods has no crude source until an extraction system exists.
- D4: decided 2026-11-12, candidate C (variation 1): 14 trays, 1.0 m, one middle draw (kerosene and diesel together),
  one pumparound above it, reflux 1.5, heater outlet 390 °C, V-101 at 3.2 bar. Not yet a preset or a bundled sheet.
  Still open for the owner: the yellow "larger than needed" pumps and exchangers (a tutorial note, a unit of about
  195 kmol/h, or smaller catalogue sizes); the bottoms as fuel for the heater (the sheet draws no line for it); why Size
  all keeps S-6 at 0.1 m (SMALL_COLUMN_CANDIDATES_REVIEW.md).
- Chapter 3 adds a column to the still on crude: the still law answers a trayed still on crude light ends as a pot
  still (the still batch's option B is open). Measure a trayed run before the chapter is written.
- P0: the owner's one in-game check of the scene player (P0_SCENE_PLAYER.md, last section).
- P1: the owner's review of the English text and the renders; the in-game rows of P1_GUIDE_AND_HELP.md; the Field
  Guide's final icon.
- P2a: the owner's review of chapter 1's text, the four marks and the renders; decisions 1 and 2 of
  P2A_CRUDE_CHAPTER.md (the road's marks from a test-code catalog, the lightest cut's lower edge).
- P2b: the owner's review of chapter 2's text and renders (`tmp/tutorial-renders/p2b/`); whether `first` should wait for
  a least distillate flow (it fires at the first trace, about 1 minute, while its text names the trickle at 4); the
  in-game check named in P2B_STILL_CHAPTER.md.
