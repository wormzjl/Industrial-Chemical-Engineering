# Tutorial: the Field Guide, films, cards, help tours and equipment pages

**Snapshot:** 3063488da (branch `claude/tutorial-system-chem-mod-950ca9`, main 0.29.0 merged in at 1cfca0ab0), 2026-11-13. Batch `2026-11-12-tutorial-system`, to merge: the scene player and 43 films (7 chapter films, 7 crude unit page films, 29 generic page films), 11 cards, the Field Guide (chapter 0 and seven chapters, 51 steps as vanilla advancements, ten criteria, protocol `tutorial-1`), 41 help tours on every screen page, 41 equipment pages, the equipment reference items and their JEI pages, four recordings, the small crude tower preset and the "Small crude unit" example.

## Read this when

- You add or change a guide chapter or step, its advancement or its criterion.
- You add a screen or a screen page (it needs a help tour or an exemption), or move a control a tour names.
- You add an `EquipmentShape.Kind` (it needs a reference item, a page, an icon and lines).
- You add or change a film, a card or a recording, or the data a recording was made from (an assay, the still law, the small crude unit).
- A tutorial text, a tour stop, a page or a film looks wrong.

## In one paragraph

The tutorial is the mod's own framework (owner D1: Ponder dropped). A **film** is a pure function of time (`Scene.at`) over actors made from the mod's equipment geometry, drawn by the game's `SceneView` and by the offline render tool through one `Projection`. A **card** is one animated drawing on the kit's canvas with a slider, switch, options or places to press. The **Field Guide** (`GuideScreen`, item `field_guide`, key H, a one-line HUD tracker) lists chapters of steps read from `guide.json`; each step is a vanilla advancement under `icheme:tutorial/`, so progress is the game's own save (owner D2). A CLIENT step is reported by the client; a SERVER step is earned by a criterion the server fires only where it already sends or stores the data. Every screen page has a **help tour** (`?` key): the screen dims, one control is lit and one sentence says what it is for. Each equipment kind has a **reference item** (creative tab "Equipment guide", JEI page) that opens its **equipment page** in the guide, with a picture and a film. Numbers a film or card shows come from **recordings** written offline by `tools/tutorial-recordings` and held to the model by gates.

## Class map

Paths are relative to `src/main/java/com/wormzjl/icheme/`. Every class under `science/tutorial/` and `runtime/tutorial/` and `TutorialNetwork` loads with no client class (`TutorialNetworkCodecTest`'s loader gate).

| Class or package | Role |
|---|---|
| `science/tutorial/scene/` (11) | `Scene` (title key, actors, tracks, stops; `at(t)` pure), `Track` (camera, visible, cut, caption, dots, label, legend, flame, level and the rest; a track acts on (from, to]), `Frame`, `Actor`/`Actors` (polygons from the mod's geometry), `Clip` (cutaways), `DotMotion` (dots by a fixed hash, no `Random`), `Bodies` (fires and liquids), `Projection` (the one orthographic projection), `Series` and `Value` (recorded channels, linear between points) |
| `science/tutorial/films/` (11) | `Films` (name to builder; built anew on Watch), `StillInside`, `StillBuild`, `StillRun` (+ `StillRunData`), `StillTray`, `SmallTower`, `CrudeUnit` (`crude_unit`, `unit_build`), `EquipmentFilms` (7: the crude unit's kinds), `PageFilms` (29 `page_<kind>`, one stop, nozzles named), `PagePicture` (a page's still picture) |
| `science/tutorial/guide/` (13) | `Guide` (chapters, steps, media, check, criterion; derived lang keys), `GuideFile`, `Progress`, `Glossary`, `Marked` (`{term:words}`), `Tour`, `TourFile`, `Pages`, `PageFile`, the conditions `StillCondition`, `SheetCondition`, `AssemblyCondition`, `ColumnCondition` |
| `science/tutorial/cards/` | Recording readers: `BarrelData` (`crude_barrel.json`, also `compute` for the recorder), `StillTraysData`, `SmallUnitData` |
| `science/tutorial/equipment/` | `EquipmentInfo` (item id `equipment_<kind>`, name key, serves/needs/place/status lines, shape-only kinds), `ReferenceShapes` (each kind at the constructor's default size: icons, pictures, page films) |
| `runtime/tutorial/TutorialTriggers` | The ten criteria (`DeferredRegister` of `TRIGGER_TYPE`, registered from `IChemE`) |
| `network/TutorialNetwork` | Protocol `tutorial-1`: `StepDone`, `Hello` (to server), `Progress` (to client); `earned` on `AdvancementEarnEvent` |
| `client/gui/tutorial/` (13) | `GuideClient` (guide, pages, progress, key, reload), `GuideScreen` and `GuidePage` (Minecraft-free layout), `GuideHud`, `FilmScreen`, `FilmLayout`, `FilmOverlay`, `Labels`, `SceneView` (render thread; one vertex buffer per actor list), `CardView`, `HelpTour`, `TourHost`, `TourLayout` |
| `client/gui/tutorial/cards/` (14) | `Card`, `Cards` (name to builder), `Bars`, and the 11 cards: `three_parts`, `chain`, `barrel`, `products`, `road`, `pot_temperature`, `reflux`, `compare`, `balance`, `unit_map`, `limit` |
| `client/gui/common/` `TourAnchors`, `DeviceTour`, `ScreenTours`, `AssemblyTour`; `client/gui/flowsheet/SimulatorTour` | Minecraft-free anchor maps: where each stop stands on a page, in the screen's own coordinates; the screens resolve the same maps the gates check |
| `world/item/FieldGuideItem`, `EquipmentInfoItem`; `registry/ModItems`, `ModCreativeTabs.EQUIPMENT_GUIDE` | The Field Guide; one reference item per kind (builds nothing, no recipe, no loot; use opens its page). Client calls stay behind the client-side check |
| `compat/jei/IChemEJeiPlugin` | JEI information pages for every reference item and every other mod item; found by `@JeiPlugin`, named by nothing in the mod |

## Main flow

1. First join: the advancement `icheme:tutorial/root` (trigger `minecraft:tick`) rewards the loot table `icheme:tutorial/field_guide`: one Field Guide.
2. Login: `IChemEClient` calls `GuideClient.hello()`, which clears the local progress and sends `Hello`. The server answers with `Progress`: the paths under `tutorial/` of every tutorial advancement that player has done, the root left out. It answers a player at most once in 20 ticks (`HELLO_TICKS`, a weak map of the last answer).
3. The player opens the guide (item use or key H while no screen is open). `GuideScreen` lays out `GuidePage`; a chapter key opens its steps; Watch opens a `FilmScreen`, Open shows a card in place of the steps, a `page:` step opens an equipment page.
4. A CLIENT step: `GuideClient.report(step)` marks it done at once and sends `StepDone`; the server fires `icheme:guide_step` with that step (ids over 64 characters are ignored). Only the advancement listening to that criterion with that step completes, so a client cannot complete a SERVER step.
5. A SERVER step: the server fires its criterion at the site in the table below. When any tutorial advancement is earned, `TutorialNetwork.earned` sends `Progress` again. No tick work and no polling.

<details><summary>The criteria and where each fires</summary>

Each criterion mirrors the game's `ConstructBeaconTrigger`: an instance record with an optional player predicate and a record codec. Every site is a place where the server already sends a view to that player or stores what the player sent; none runs a solver, materialises an island or applies a queued event (AGENTS.md, "Engine schedule").

| Criterion | Fired from | Conditions |
|---|---|---|
| `guide_step` | `TutorialNetwork.stepDone` (the client's `StepDone`) | `step` (`<chapter>/<step>`) |
| `equipment_built` | `EquipmentNetwork`, after `EquipmentStructure.place` of a constructor build | `kind` (an `EquipmentShape.Kind` name), `column` (a still with its column) |
| `still_state` | `FluidNetwork.deliver`, each scheduled publication or open replay a still's menu receives | `StillCondition`: `status` list, `min_filled`, `min_pot_temperature` (K), `liquid` (at the spout), `column`, `min_reflux` |
| `still_stopped` | `FluidNetwork`'s still settings edit with firing 0, read against the stored view (`world.stored`; code review finding 1) | none; fires only if the still was firing |
| `diverter_switched` | `FluidDeviceBlock`, a diverter valve turned by hand and accepted | none |
| `sheet_run` | `ProcessSimulatorNetwork.receive(SheetPart)`, after the block took the whole body; `SimulatorResults.ranOnBody` reads the results' head | `SheetCondition`: `max_heater_outlet` (K), `heater` (a unit tag) |
| `design_sent` | `ProcessSimulatorNetwork.receive(SendDesigns)`, after `block.send` | none |
| `assembly_state` | `AssemblyNetwork`'s presentation `send`, the scheduled view to an open assembly screen | `AssemblyCondition`: `status` (NOT_VALIDATED, CHECKED, COMPLETE) |
| `column_state` | the same send, when the view carries an Operation view | `ColumnCondition`: `phase` list, `product` (an outlet port's rate above 0) |

Client steps a screen reports: `simulator/open` (the bundled small unit opened) and `simulator/size` (Size all's sizes landed) in `ProcessSimulatorScreen` (`SimulatorTour.OPEN_STEP`, `SIZE_STEP`); `simulator/sheet` when the sheet page's tour is finished (its last stop shows the `simulator/balance` card); `building/lines` when the assembly screen's Structure tour is finished (`AssemblyScreen.LINES_STEP`); `welcome/guide` (the guide opened) and `welcome/help` (its tour finished) in `GuideScreen`. Otherwise a `read` step reports on its key, a film or card step when it ends, a `page:` step when its page is scrolled to its end.

</details>

<details><summary>Data files and their formats</summary>

All under `src/main/resources/assets/icheme/tutorial/` are client assets: no compatibility work; each reader refuses an unknown field.

- `guide.json` (`GuideFile`): `terms` (term id to its defining step) and `chapters` in order, each `{id, steps}`; a step `{id, media?, check?, criterion?}`. Media: none, `units`, `read`, `card:<name>`, `film:<name>`, `page:<item id>`. `check` is `client` (default) or `server` with a `criterion`. Ids `[a-z0-9_]{1,32}`. Refused: a duplicate id, an unknown film or card, a term whose step is not in the guide. At this snapshot: 20 terms; chapters `welcome` (3), `crude` (6), `still` (9), `tower` (5), `simulator` (7), `unit` (4), `building` (7), `start` (10).
- `tours.json` (`TourFile`): page name (`guide`, `fluid_device.still`, `simulator.sheet`, `constructor.paint`) to `{film?, show?, stops: [{anchor, wait?}]}`, at most 7 stops. Anchors are widget keys, `reading:<meter>`, `tab:<id>`, `status`, `title`, `glass`, `group:<n>` or `group:<plate key>`, never pixels. `show` names a guide step whose media the last stop shows. 41 tours.
- `pages.json` (`PageFile`): `unit` (the crude unit's chain, 9 pages, in order) and `pages`: item id to `{film?, tour?}`. Every kind's item id must have an entry; other ids only from `PageFile.ITEMS` (`pressure_control_valve`). A page's name and lines are the reference item's own keys (`EquipmentInfo.nameKey`, `pageKeys`). 41 pages, 37 with a film.
- `recordings/*.json`: `crude_barrel.json` (per grade: components in boiling order, six band shares, the boiled-off curve 250 to 900 K every 5 K, sulfur), `still_run.json` (at most 240 points of time, pot and head K, level, distilled and vented shares, rate, part, status; lines `first`, `naphtha`, `kerosene`, `end`), `still_trays.json` (pot still and 4 trays at reflux 1 and 2: two cuts each), `small_unit.json` (the small unit at H-101 390 °C and 370 °C: products and duties). Read once when a film or card is built.
- `data/icheme/advancement/tutorial/`: `root.json` and one file per step, `<chapter>/<step>.json`, parented to the step before it; display keys `tutorial.icheme.advancement.<chapter>.<step>[.description]`, no toast, no chat.
- Language keys (all in `en_us.json`): `tutorial.icheme.guide.<chapter>[.<step>]`, `tutorial.icheme.term.<id>[.def]` (definition at most 25 words), `tutorial.icheme.tour.<page>.<n>`, `tutorial.icheme.film.<name>[.s<n>]`, `tutorial.icheme.card.*`, `tutorial.icheme.equipment.<kind>.{serves,needs,place}`, `tutorial.icheme.item.*` (JEI lines of the other items). A text writes a temperature as `{373.15 K}`; `Guide.temperatures` shows it in the client's unit.
- Also bundled: `columnpreset/small_crude.json` (14 trays, 1.0 m, one middle draw, one pumparound), `mixturepreset/small_crude.json` (medium crude at 39.96 kmol/h, 657.15 K) and `flowsheet_examples/small_crude_unit.json` ([process-simulator.md](process-simulator.md)).

</details>

<details><summary>Client caches and what drops them</summary>

- `GuideClient.guide()` and `pages()` and `HelpTour.of`'s tour map are read once. A resource reload (a language change is one; the listener is registered in `IChemEClient`) calls `GuideClient.reloaded()`: all three drop and `generation` counts up.
- `GuideScreen`, `GuideHud` and `HelpTour` lay out only when their key changes: generation, language, progress, the open chapter, card or page, the size, the temperature unit (code review finding 4).
- `GuideClient.progress` is replaced by each server `Progress` and cleared at login.
- Films and cards are built anew when opened (`Films.build`, `Cards.build`), never at load; a page's picture is built when the page opens. `SceneView` keeps one vertex buffer per actor polygon list (a cut keeps at most the current and the next) and closes them with the view.
- The client config `guide_hud` (`ClientConfig.guideHud`, default on) hides the tracker.

</details>

<details><summary>How to add a step, a tour, a page, a film or a card</summary>

- **A step:** add it to its chapter in `guide.json`; its sentence `tutorial.icheme.guide.<chapter>.<step>` in `en_us.json`; its advancement `data/icheme/advancement/tutorial/<chapter>/<step>.json` parented to the step before (and the next step's parent moved to it). A CLIENT step listens to `icheme:guide_step` with `"step": "<chapter>/<step>"` and needs media or a screen that reports it. A SERVER step names its criterion in the file and the advancement; no two steps share a trigger with the same conditions. A new criterion goes in `TutorialTriggers` with its condition record in `science/tutorial/guide/`, fired only where the server already sends or stores the data.
- **A tour:** give the screen a `TourHost` with its page name and an anchor resolver (the screen's anchor map in `DeviceTour`, `ScreenTours`, `AssemblyTour` or `SimulatorTour`, door rows through `TourAnchors`), its `?` key placed by `TitleStrip.keys`; add the tour to `tours.json` and its sentences `tutorial.icheme.tour.<page>.<n>`. A screen with no tour is listed as exempt in `HelpCoverageTest` (`FilmScreen` is).
- **A kind's page:** a new `EquipmentShape.Kind` gets its reference item by the loop in `ModItems` (`EquipmentInfo.itemIds`), lines `tutorial.icheme.equipment.<kind>.*`, an item model and an icon (`IconRender`), an entry in `pages.json` and, if it can be cut, a page film (`PageFilms.KINDS`, or its own in `EquipmentFilms`). A renamed `Kind` constant needs an item alias from the old id (`ModItems.ITEMS.addAlias`).
- **A film:** a builder class in `science/tutorial/films` with its `NAME`, registered in `Films`; places from the shape's own geometry (`ShapeRasterizer`, `ReferenceShapes`, `EquipmentFilms.face`), numbers from a recording; captions `tutorial.icheme.film.<name>.s<n>`. Render its stops with `tools/tutorial-renders` before review.
- **A card:** a `Card` in `client/gui/tutorial/cards`, registered in `Cards`; a pure function of its state, reading a recording once when built.
- **A recording:** a recorder in `tools/tutorial-recordings` (and in `src/test` where its gate reruns the same code), the reader in `science/tutorial/`, a gate that holds the file to the model within 1e-9.

</details>

## Change the layer that owns it

| Change | Owner | Proof |
|---|---|---|
| A step's order, media, check or criterion | `guide.json`, the advancement files, `TutorialTriggers` | `GuideFileTest`, `GuideCoverageTest`, `ProgressTest`, `TutorialTextTest` |
| What a criterion accepts | `StillCondition`, `SheetCondition`, `AssemblyCondition`, `ColumnCondition` | `StillConditionTest`, `SheetConditionTest`, `ColumnConditionTest` |
| The wire, Hello's admission, the server side's class loading | `TutorialNetwork` | `TutorialNetworkCodecTest` |
| A tour's stops or a screen's anchors | `tours.json`, the anchor maps | `HelpCoverageTest`, `DeviceTourTest`, `ScreenToursTest`, `AssemblyTourTest`, `SimulatorTourTest`, `TourLayoutTest` |
| A film's frames | `science/tutorial/scene`, `films/` | `SceneTest`, `SeriesTest`, `StillInsideFilmTest`, `StillBuildFilmTest`, `StillRunFilmTest`, `TowerFilmsTest`, `UnitFilmsTest`, `PageFilmsTest` |
| The guide's page, a card, a label | `GuidePage`, `cards/`, `Labels`, `CardView` | `GuidePageTest`, `CardViewTest`, `LabelsTest`, the card tests in `client/gui/tutorial/cards` |
| A reference item, its lines, its JEI page | `EquipmentInfo`, `ModItems`, `IChemEJeiPlugin` | `EquipmentInfoCoverageTest` |
| A recording | `tools/tutorial-recordings`, the readers | `CrudeBarrelTest`, `StillRunRecordingTest`, `StillTraysRecordingTest`, `SmallUnitRecordingTest` (holdGates) |

## Tests that document it

- `science/tutorial/` (`test`): `GuideFileTest` (10), `GuideCoverageTest` (12: every step has its advancement with the criterion its check asks, no two steps share a trigger and its conditions, the start-up chapter asks for every supply before On, the root gives the Field Guide once, the still, tower and simulator steps hold the recordings' numbers, every kind has a page), `TutorialTextTest` (6: every text has its English line within its limits, every marked term is defined on the step of its first use, no line names a value in a fixed unit), `PageFilmsTest` (3: every page has a film or is picture only; an inside stop keeps its dots on the body and its leaders on drawn nozzles), `SceneTest` (12), `SeriesTest`, `MarkedTest`, `ProgressTest`, `StillConditionTest`, `EquipmentInfoCoverageTest` (6), the film tests, `StillRunRecordingTest` (5), `StillTraysRecordingTest` (4), `cards/CrudeBarrelTest`. Helpers: `FilmChecks`, `MinecraftFree`, `TutorialText`, the recorders `StillRunRecorder`, `StillTraysRecorder`.
- `client/gui/tutorial/` (`test`): `HelpCoverageTest` (6: every tour has at most seven stops and its lines, every screen has a tour or is exempt), `GuidePageTest`, `CardViewTest`, `LabelsTest`, `TourLayoutTest`, `cards/*CardTest`.
- `client/gui/common/` `DeviceTourTest`, `ScreenToursTest`, `AssemblyTourTest`, `ColumnConditionTest`; `client/gui/flowsheet/` `SimulatorTourTest`, `SheetConditionTest`; `network/TutorialNetworkCodecTest` (3: payloads round-trip, a Hello answered at most once a second, the server side loads in a loader that refuses client classes).
- `holdGates`: `client/gui/flowsheet/SmallCrudeUnitTest` (the small unit's one real fail S-6 and its larger-than-needed lamps) and `SmallUnitRecordingTest` (helper `SmallUnitRecorder`).
- Fluid GameTests: `TutorialUnitGameTests` (2: the guide's small crude tower runs at its design feed).

## Tools

- `tools/tutorial-renders/`: software renders of every film stop, the guide pages, tours, cards, equipment pages and the item icons (`renderTutorial`, `renderTutorialGuide`, `renderTutorialIcons` by its init script). The simulator's tours: `tools/heat-gui-renders/SimulatorRender.java -Donly=tours`.
- `tools/tutorial-recordings/`: writes the four recordings (`recordCrudeBarrel`, `recordStillRun`, `recordStillTrays`, `recordSmallUnit`).
- `tools/tutorial-column/`: the small crude tower candidates (P4, D4) and `bundle.js`, which writes the bundled preset, stream and example.
- Removed: the P0 client command `/icheme-film` (cace5dd6d); re-attach it as `tools/tutorial-renders/README.md` says.

## Terms

- **Step**: one line of a chapter, one advancement; CLIENT (reported) or SERVER (a criterion).
- **Film**: a `Scene`, played to each stop and waiting there. **Card**: an interactive drawing. **Tour**: a screen page's stops, opened by `?`.
- **Reference item**: `equipment_<kind>`, which builds nothing and opens the kind's page.
- **Recording**: a JSON file of numbers written offline from the model and held to it by a gate.
</content>
</invoke>
<invoke name="Bash">
<parameter name="command">cd "D:/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/chimney-b7484c/src/main/java/com/wormzjl/icheme" && grep -c "ALL.put\|KINDS" science/tutorial/films/Films.java; grep -n "KINDS *=" -A3 science/tutorial/films/EquipmentFilms.java science/tutorial/films/PageFilms.java | head; grep -n "BUILD_NAME\|NAME =" science/tutorial/films/*.java | cut -c1-150