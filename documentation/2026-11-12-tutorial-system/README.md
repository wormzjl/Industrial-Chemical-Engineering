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
  tours and cards, field guide, equipment items and JEI), the teaching rules, the path of eight chapters, the small
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
- [P4B_TOWER_AND_SIMULATOR.md](P4B_TOWER_AND_SIMULATOR.md): phase P4b, the still with a column measured and recorded,
  chapter 3 "Pot to tower", chapter 4 "The simulator", the small unit's recording, the simulator's tours, gates, renders.
- [P5_PART1_UNIT_CHAPTER.md](P5_PART1_UNIT_CHAPTER.md): phase P5 part 1, the equipment pages (41), the unit chain,
  chapter 5 "Crude unit" (film crude_unit, card unit_map, the column's page), the corrections, gates, renders.
- [P5_PART2_BUILD_AND_START.md](P5_PART2_BUILD_AND_START.md): phase P5 part 2, the supplies (steam form A, crude fuel,
  power), the start-up order, chapters 6 "Building" and 7 "Start-up", the limit card, the assembly tours, the proofs.
- [SMALL_COLUMN_CANDIDATES_REVIEW.md](SMALL_COLUMN_CANDIDATES_REVIEW.md): P4, the three small crude tower candidates
  solved and sized on the crude unit at 1/64, the acceptance rules, the steam source, the recommendation (D4).
- [P6_COVERAGE.md](P6_COVERAGE.md): phase P6, a help tour on every screen page and a film on every equipment page, the
  owner's rule for an inside stop, gates, renders.
- [CODE_REVIEW.md](CODE_REVIEW.md): the code review of the branch at e135ebcdc (tutorial and still law), findings 1 to 9
  and the unconfirmed item.
- [MERGE_PREP.md](MERGE_PREP.md): the merge preparation: changelog, version proposal, code guide, tooling cleanup.

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
- 2026-11-12: P4 built (owner chose C, variation 1): column preset `small_crude.json`, its paired stream
  `mixturepreset/small_crude.json` (medium crude at 39.96 kmol/h, 384 °C: the feed T-101 meets on the example's run; at
  the grades' heater outlet convention the calculator leaves 44.9 % in the bottoms) and the example
  `small_crude_unit.json`, "Small crude unit" (unsized, as `crude_unit`). The calculator on the pair (PairCheck) gives
  18.4 / 35.2 / 46.4 %. Gate `SmallCrudeUnitTest` (holdGates) names the one real fail (S-6) and the six larger-than-needed
  lamps. Preset counts and the "40 trays, 8 m" check in the tests now read each template. SmallCrudeUnitTest (3),
  FlowsheetExamplesTest (6), V3ClientPresetsTest (10), ColumnTemplatesTest (5), SimulatorRunnerTest (28), OpenDeleteTest (5),
  V3ColumnTemplateTest (6) pass.
- 2026-11-12: P4b (P4B_TOWER_AND_SIMULATOR.md). Measured the still with 4 trays: at reflux 2 the first cut holds 88 %
  naphtha against 75 %, the column solves itself on 91 % of boiling steps after the light gas, 21.5 minutes of play.
  Recorded `still_trays.json` and `small_unit.json`; chapters 3 "Pot to tower" and 4 "The simulator"; films still_tray,
  small_tower; cards reflux, compare, balance; criteria sheet_run, design_sent; tours on the still with a column and the
  simulator's four tabs. Commits 66ed0baa4, 3a7bb0d1c, 7cbd240e0, 402e1b4a9, 8295a579e. 35 test classes
  (265 tests) and SimulatorRunTest (6) pass, run once with `--tests`.

- 2026-11-13: P5 part 1 (P5_PART1_UNIT_CHAPTER.md). Equipment pages for the 40 kinds and the control valve (`pages.json`),
  opened by the item and the map, Back to where the player came from; the unit chain of nine pages; seven page films
  and the film `crude_unit`; card `unit_map` on the simulator's painter; chapter 5 "Crude unit" (whole, map, tower,
  next). `--tests` run once, 4 workers: 135 tests in the tutorial packages, DrawCallTest and SimulatorTourTest; three
  failures fixed and their classes rerun once each.
- 2026-11-13: P5 stage A (P5_PART2_BUILD_AND_START.md): the column's slots and links, Import, the supplies in
  survival (no in-mod steam source; fuel, water, power and products), refusals and limits in code order, the headless
  proof and the criteria points. Documents only, no test run. Chapter 6 waits for the owner.
- 2026-11-13: P5 part 1 corrections (P5_PART1_UNIT_CHAPTER.md): pages open on their picture (film's first stop or the
  body), unit films framed large with their insides and readable dots, map tags clear of tags and symbols, one new
  term per step. `--tests` run once, 4 workers: 13 classes, 75 tests pass.
- 2026-11-13: P5 stage B (P5_PART2_BUILD_AND_START.md): steam form A (H-102 on water; products unchanged), crude fuel
  on both heaters, power in the mod; chapters `building` and `start`, film `unit_build`, card `limit`, criteria
  `assembly_state` and `column_state`, the assembly screen's two tours; chapter 4's heat step names H-101. Proofs:
  SteamHeaterCoilTest, SmallCrudeUnitTest, SmallUnitRecordingTest pass; TutorialUnitGameTests compiled, its first run is
  the final lane's. Quick suite 18 classes, 85 tests pass after six gate fixes. Commits e5b1c6ac9 to the docs commit.
- 2026-11-13: P5 stage B corrections: one thing to build a step (`building` adds pumps and loop; `start` crude, fuel,
  power, cooling, steam, tanks, each opening its kind's page), the tanks before On, an end that names only what runs
  (no vacuum tower). Gate: the supplies come before On. GuideFile, GuideCoverage (11), GuidePage, TutorialText, CardView,
  DrawCall tests pass, run once with `--tests`.
- 2026-11-13: main 0.29.0 (aqueous solvents, a182f36f2) merged in (1cfca0ab0). Conflicts: `build.gradle` (the slow gate
  list), `documentation/INDEX.md`. No recording moved: StillRunRecordingTest (5), StillTraysRecordingTest (4),
  SteamHeaterCoilTest (1), SmallCrudeUnitTest (4), SmallUnitRecordingTest (2) pass, run once with `--tests`.
- 2026-11-13: P6 (P6_COVERAGE.md): a help tour on every screen page (32 tours, `TourHost`, `TourAnchors`, gates
  DeviceTourTest and ScreenToursTest, `FilmScreen` exempt) and a film on every equipment page (`PageFilms`, 29 films,
  gate PageFilmsTest; motor, generator, gearbox and the control valve picture only); tour render `CoverageRender`.
  18 test classes pass, run once with `--tests`.
- 2026-11-13: CODE_REVIEW.md's findings (owner approved), each fixed: 1 the stop step reads the still's stored view
  (ae47b06ef); 2 and 2b trays that do not solve run as the pot's single stage, and a still that solves a column keeps its
  fire out until the first answer (ab4620e78; History of `2026-11-12-still-heat-per-distillate`); 3 `building/site` a
  film step, gate `noTwoStepsShareATriggerAndItsConditions` (1ca54ee67); 4 and 5 the guide, its HUD line and the tour
  lay out only on a change, tours read once and checked by name, caches drop at a resource reload (5cd85b8a8; the page
  picture was already built once a page: sound); 6 and 7 one Hello a player a second, `/icheme-film` removed
  (cace5dd6d); 8 and 9 the cards and `Labels` in `client/gui/tutorial/`, `TutorialNetworkCodecTest`'s loader gate
  (e6fe48dc8). Unconfirmed item checked, not changed (open item below). Every changed test class run once with `--tests`.
- 2026-11-13: P6 page films corrected (owner's rule for an inside stop): no kind keeps stop 2; all 29 page films are one
  stop, gated (dots on the body, leaders on drawn nozzles), the 14 stop-2 sentences removed (P6_COVERAGE.md). Films and
  page sheets rendered again in `tmp/tutorial-renders/p6/` (the render tools compile after the cards' move).
  PageFilms 3, TutorialText 6, GuideFile 10, GuideCoverage 12, EquipmentInfoCoverage 6, GuidePage 6, HelpCoverage 6 pass.
- 2026-11-13: translation commit cda7e7129 (zh_cn, fr_fr, es_es, ja_jp brought up to en_us).
- 2026-11-13: merge preparation (MERGE_PREP.md), on the owner's word: CHANGELOG lines for both batches, version proposal,
  code guide chapter `tutorial.md` and seven chapters updated, tool READMEs and `tools/INDEX.md` rows, the detached
  `/icheme-film` command stored in `tools/tutorial-renders/film-command/`. Documents and tools only, no test run.
- 2026-11-13: final lane on df15654c0 (FINAL_LANE_REVIEW.md): 3505 tests with 1 failure, GameTests 96 of 97; both
  test-side, fixed: `SmallTower` on `DeterministicMath` (d1e35e4d3), the tower GameTest built from its asset templates
  (df5b43c26), each proven by one run (GameTests 97 of 97). chain-100, junction lines, `MIXED_GAS_COST` as the
  baseline; campaign cold 992 clean (991), bundled 994, defaults 1000; bundled 431 F replayed alone: E6, as the baseline.

## Code areas

Code guide chapter: `documentation/reference/code-guide/tutorial.md`. Paths under `src/main/java/com/wormzjl/icheme/`:

- `science/tutorial/`: `scene/` (the scene player), `films/` (43 films), `guide/` (guide, tours, pages, progress, the
  criteria's conditions), `cards/` (the recordings' readers), `equipment/` (`EquipmentInfo`, `ReferenceShapes`).
- `client/gui/tutorial/` (guide screen and page, HUD line, film screen and view, labels, tours: `HelpTour`, `TourHost`,
  `TourLayout`) and `client/gui/tutorial/cards/` (11 cards); the anchor maps `client/gui/common/{TourAnchors,DeviceTour,
  ScreenTours,AssemblyTour}` and `client/gui/flowsheet/SimulatorTour`; the `?` key in `TitleStrip`, `Skin`, `SimulatorFrame`
  and every screen.
- `runtime/tutorial/TutorialTriggers` (ten criteria) and `network/TutorialNetwork` (`tutorial-1`); the criteria fired from
  `network/{AssemblyNetwork,EquipmentNetwork,FluidNetwork,ProcessSimulatorNetwork}` and `world/level/block/FluidDeviceBlock`;
  `network/SimulatorResults.ranOnBody`.
- `world/item/{FieldGuideItem,EquipmentInfoItem}`, `registry/{ModItems,ModCreativeTabs}`, `compat/jei/IChemEJeiPlugin`,
  `science/equipment/ShapeRasterizer.stillParts`, `ProcessUnit.StillPlan`.
- Resources: `assets/icheme/tutorial/` (`guide.json`, `tours.json`, `pages.json`, `recordings/`),
  `data/icheme/advancement/tutorial/`, `data/icheme/loot_table/tutorial/field_guide.json`, the item models and textures,
  `columnpreset/small_crude.json`, `mixturepreset/small_crude.json`, `flowsheet_examples/small_crude_unit.json`.
- Tools: `tools/tutorial-renders/` (with the detached `/icheme-film` command), `tools/tutorial-recordings/`,
  `tools/tutorial-column/`, `tools/heat-gui-renders/SimulatorRender.java -Donly=tours`.

## Open items

- Code review, for the owner: (a) overlapping criteria, not identical (the new gate passes): `simulator/run` (any run)
  and `simulator/heat` (a run with H-101 at most 643 K), `start/go` (RUNNING among its phases) and `start/limit`
  (RUNNING): a first event that meets the second completes both. (b) The unconfirmed `BatchStill:457`: a propane-rich pot
  with water (300, 150, 50 mol, bubble 237.5 K), outlet 290 K, valve closed: the one-stage split has head and condensate
  at 239.0 K and a condenser duty of 0.00397 J/mol, but `closed` is false (0 and 4 trays alike), so the step takes the
  open path (line 514), not 457; it reads COOLING LIMITED, condensed 0, duty -0.0009 J over 10 s, heat 109 kJ. Taken,
  line 457 would give 2.75e6 mol/s, which `need` multiplies back by the duty (the heat) and `flood` uses only with trays.

- Merge order: the aqueous batch reached main as 0.29.0 and this branch holds main (1cfca0ab0, 2026-11-13). Before that
  (owner, 2026-11-12): this branch merges after `claude/ethanol-compound-matrix-4b318d`. It held that
  branch (at 6b8d03389, with main 0.28.0) and the still branch `claude/still-on-aqueous-fdba0c` (at 1b1ad1d07); when the
  aqueous branch changes again, merge it again. The still branch has had no code review and no final lane.
- After the merges: done. The tutorial compiles, is recorded and gated on the merged base; the still law's own classes
  and the fluid GameTests wait for the final lane.
- The still with trays on crude: measured in P4b. The column is dry (the law's single stage) only while the pot warms,
  below 124 °C; it solves itself on 91 to 99 % of the boiling steps after the light gas. Chapter 3 is built on it.

- O1: decided 2026-11-12, reference only (creative tab and JEI).
- P3: the owner's review of the icon sheet and the 160 lines; the in-game rows of P3_ITEMS_AND_JEI.md; icons alike at
  1x (exchanger and kettle, grate boiler and fixed-bed gasifier) wait for their models.
- O2: the charge step names the Fluid converter (bundled rules: `#c:crude_oil` of TFMG and PneumaticCraft, `ad_astra:oil`).
  A survival player without one of those mods has no crude source until an extraction system exists. Steam's in-mod
  path (P5 stage B): water from a `minecraft:water` converter through H-102, a crude-fired heater. FE: a generator on a
  shaft train turned by a combustion engine (crude) or a gas turbine (off-gas); pumps on the engine or a motor.
- P5 stage B: the owner's in-game check of the survival supplies together (converters, crude-fired burners, driven pumps,
  FE fans, H-102 feeding the tower), which no test proves; the review of chapters 6 and 7 and the renders in
  `tmp/tutorial-renders/p5/`; TutorialUnitGameTests' first run in the final lane.
- D4: decided 2026-11-12, candidate C (variation 1): 14 trays, 1.0 m, one middle draw (kerosene and diesel together),
  one pumparound above it, reflux 1.5, heater outlet 390 °C, V-101 at 3.2 bar. Built 2026-11-12: column preset
  `small_crude`, its paired stream `small_crude` and the example "Small crude unit" (gate `SmallCrudeUnitTest`).
  Still open for the owner: the yellow "larger than needed" pumps and exchangers (default: a tutorial sentence; else a
  unit of about 195 kmol/h, or smaller catalogue sizes).
- Found while building the small unit (sub-thread report, 2026-11-12), nothing changed:
  - Fuel: the simulator's fired heater has no fuel port or setting (its code keeps the fuel off the sheet), so the sheet
    cannot draw the bottoms slip (about 124 kg/h; the off-gas covers 27 % of the firing). Settled in P5 stage B: both
    heaters burn crude in the world (about 150 and 18 kg/h), step `start/fire`.
  - Line S-6 (H-101 to V-101): Size all picks line bores on its first pass, on the heater's typed drop (10.2 m/s; 0.075 m
    would exceed the momentum limit). After its re-run on the sized drops the vapour share falls from 0.72 to 0.45 and
    the speed to 2.7 m/s, under the 3 m/s minimum; it re-rates but does not re-size lines. At 0.075 m the line would be
    green (4.8 m/s). Options: re-size lines after the re-run, or keep S-6 as the known fail the gate names.
  - First run: a cold run of the small example costs 223,073 polls, a quarter of `crude_unit`'s 897,274; no warm start
    needed.
  - The grade streams stand at the heater outlet; the small stream stands at the tower's feed (384 °C), because the
    sheet's valve changes the state between the two.
- P4b: the owner's review of chapters 3 and 4 (text, renders in `tmp/tutorial-renders/p4b/`), the title "Pot to tower"
  (the brief's "From pot to tower" clips in the small window), and the in-game check of the simulator's `?` tours and
  the client steps (`open`, `sheet`, `size`). The step `build` accepts any column (2 to 8 trays); its text names 4.
  The basis page names the package by its id (`icheme:refinery`): no lang line `gui.icheme.flowsheet.basis.pack.icheme.refinery`.
  Doubled term underlines on the guide's glass (each term also marks the line below), in chapter 2 already.
- P0: the owner's one in-game check of the scene player (P0_SCENE_PLAYER.md, last section).
- P1: the owner's review of the English text and the renders; the in-game rows of P1_GUIDE_AND_HELP.md; the Field
  Guide's final icon.
- P2a: the owner's review of chapter 1's text, the four marks and the renders; decisions 1 and 2 of
  P2A_CRUDE_CHAPTER.md (the road's marks from a test-code catalog, the lightest cut's lower edge).
- P2b: the owner's review of chapter 2's text and renders (`tmp/tutorial-renders/p2b/`); whether `first` should wait for
  a least distillate flow (it fires at the first trace, about 1 minute, while its text names the trickle at 4); the
  in-game check named in P2B_STILL_CHAPTER.md.
