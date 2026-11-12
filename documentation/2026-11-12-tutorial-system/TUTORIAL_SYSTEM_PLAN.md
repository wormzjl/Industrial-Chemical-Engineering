# Tutorial system: plan

Owner request, 2026-11-12: a tutorial for players of 18 to 30 with no chemical engineering. It teaches how to use the
mod and the basic ideas together, in small steps, with pictures and animation before words. It starts at crude oil
and the still, goes on to the process simulator and the column, and guides the player to a first small crude
distillation unit. Revision 2 (same day) holds the owner's answers: Ponder dropped if it is not sufficient, an item
for each equipment kind, JEI pages, a chapter on crude oil from the well to daily products. No code yet.

## 1. What exists (read from the code and the Ponder 1.0.82 sources)

| Fact | Where |
|---|---|
| Ponder 1.0.82 is compiled against, and no class uses it. It is its own mod (MIT) and requires Flywheel. | `build.gradle` 240; its `neoforge.mods.toml` |
| A Ponder scene is a film of blocks in a virtual level. It draws nothing on a GUI screen, checks no objective, never looks at the player's world, and takes no input but play, pause and rotate. | `api/scene/*`, `PonderUI` |
| Equipment geometry has no Minecraft types: every kind is a list of textured polygons. | `science/equipment/Mesh`, `ShapeRasterizer.surface` |
| A tool already draws those polygons offline, textured and depth-tested, as the chunk renderer receives them. | `tools/pump-sprites/probes/PumpRender.java` |
| The owner judges screens on software renders; a task has at most one in-game check. | `AGENTS.md`, skill `gui-creation` |
| No screen has a help key. The title strip keeps a place for one key at its right (the theme key). | `TitleStrip.place(width, key, …)` |
| Equipment has no item of its kind: the Equipment Constructor builds all 38 kinds as shell cells. | `EquipmentShape.Kind` |
| The JEI API is on the compile path; the mod has no JEI plugin. | `build.gradle` 284 to 287 |
| Bundled column templates are 8 m crude towers for about 100,000 bbl/d (42 trays, 3 draws, 3 pumparounds) and a two-tray pot. A column's diameter is 0.5 to 15 m in half metres. | `assets/icheme/columnpreset/`, `ColumnDesign` 23 |
| The still: pots of 100 to 2000 L (500 L default), solid, liquid or electric heat, a top condenser, an optional small column with a reflux valve, a diverter valve for cuts; a simulator unit too. | batch `2026-11-09-batch-distiller` |
| Medium crude is a generated grade: API 31, 2.5 % C1 to C5, 18 % boiling above 565 °C, on 12 shared cuts. | `materials/assays/medium_crude.json` |
| The mod has no advancements, and no block that turns a Forge fluid into a process fluid. | `data/icheme/`, `src/main` |

## 2. D1 decided: an own framework, no Ponder

| Need | Ponder | Own |
|---|---|---|
| Help on a GUI screen | No | Yes |
| Steps the player does, checked off | No | Yes |
| The player acts inside a film (opens the reflux valve, moves the heat) | No | Yes |
| A film inside a guide page, beside a card | No: its own screen | Yes |
| Glossary hovers and the word limits in film text | No | Yes |
| Review on software renders, frame by frame | No: in game only | Yes: the tools draw `Mesh` already |
| Extra mods in every pack | Ponder and Flywheel (a rendering mod with mixins) | None |
| Equipment in a film | Shell cells in a virtual level: model data, block entity loads and effect state all need work | The kind's polygons, drawn directly |
| Cost | Scenes and schematics only | A scene player, about the size of one GUI screen |

Ponder serves one of the three layers and is the weaker choice even there, so it is dropped. One framework carries
all of it; films, cards, tours and guide share the text rules, the glossary and the render tools.

## 3. The framework

```
science/tutorial/            no Minecraft types: the gates and the render tools use it
  Guide, Chapter, Step       the table of contents, read from assets/icheme/tutorial/guide.json
  Glossary                   term -> the step that defines it
  Tour                       a screen page's stops, by the keys its builder gives its rows
  Series                     recorded numbers over time (recordings/*.json)
  scene/Scene, Frame         a film: actors, tracks, stops; Scene.at(t) -> Frame, a pure function
  films/*, cards/*           one builder a film or card
client/gui/tutorial/
  SceneView                  draws a Frame in a rectangle; drag turns, wheel zooms, hover names a part
  CardView, HelpTour         a card on the kit's canvas; the tour's overlay on any kit screen
  GuideScreen, GuideHud      the field guide and the one-line tracker
runtime/tutorial/TutorialTriggers     advancement criteria, fired from engine events
compat/jei/                  the only package that names a JEI type
tools/tutorial-renders/      every film stop, card frame and tour stop as PNG sheets; the item icons
tools/tutorial-recordings/   runs the real laws offline and writes the Series files
```

### 3.1 Films

- **Actors** are polygon lists: an equipment kind (`ShapeRasterizer.surface`), a pipe run, a valve, a ground plate.
- **Tracks** over time: camera, show and hide, cutaway (the polygons are clipped by a plane in `Scene.at`, so the game
  and the tool show the same cut), glow on a part, caption, pointer (a mouse button or key sign at a place), liquid
  level, flame, a readout (a number with its unit), and dots.
- **Dots** are the one picture that runs through every chapter: molecules as dots of three colours (light, middle,
  heavy). The light colour leaves the pot first; on a tray the heavy ones fall back.
- **Stops**: a film runs to its next stop and waits. The player presses Next, or acts: a stop can hold a handle (the
  reflux valve, the firing rate), and the handle's position picks one of the recorded series. A film is 3 to 6 stops.
- **Recorded, not computed**: `tools/tutorial-recordings` runs the real law (the still: `BatchStill`, `StillColumn`)
  and writes the series a film replays. The film shows true numbers and the client calculates nothing.
- `Scene.at(t)` is pure, so scrubbing, replay and offline rendering are the same call.

Pitfalls: one vertex buffer an actor, built once and closed with the screen (a fill is a draw call each in 1.21.1);
GUI depth needs its own clear and scissor; the shade is the tool's fixed directional shade, not world light; a 30 m
column uses its coarse body (`RenderLod`) when small on screen; translucent cut faces draw last.

### 3.2 Help tours and concept cards

- **`?` key** on every screen's title strip, beside the theme key, a sign and not a word (panel rule 7). It opens the
  current page's tour. `TitleStrip.place` takes the width of both keys; `Skin.Icon.HELP`.
- **Tour**: the screen dims, one control is lit, one sentence says what it is for. Next, Back, Close; at most 7 stops
  a page. A stop names its control by its row's key, never by pixels. A stop can wait for the player ("Press Run").
  A tour sends nothing to the server. Its last stop offers the page's film or card.
- **Card**: one animated drawing on the kit's canvas with a caption, with a slider or a switch where the idea has one.
- **Glossary**: a term is defined once in plain words, on the step that first needs it; afterwards it is underlined in
  every tutorial text and its hover shows the definition.

### 3.3 Field guide

- A Field Guide item (given at first join) and a key open the guide: chapters at the left, the steps of one chapter
  at the right. A step is one sentence, one film, card or picture, and one objective. The HUD line shows the current
  step; the player can hide it. The first step offers °C, bar, m³/h and kg/h as display units (D5).
- Objectives are checked by the server from events it already has, on the engine's schedule: a structure placed
  (`EquipmentStructure`), a network edit (`FluidWorldAuthority.listen`), a still's first distillate booked (`Stills`),
  a sheet's results stored (`SheetStore`; the run stays on the client), a design sent, "Check parts" passed, a column
  started (`AssemblyService`). No objective forces a process update. Reading steps are checked off by the client.
- Progress is vanilla advancements (`data/icheme/advancement/tutorial/`, D2): no save format of the mod's own.

### 3.4 Equipment items and JEI

- One item for each `EquipmentShape.Kind` (`icheme:equipment_<kind>`, class `EquipmentInfoItem`), in a creative tab
  "Equipment guide". It builds nothing: its tooltip says in one line what the equipment serves, and using it opens
  the kind's guide page. Its icon is a baked render of the kind (`tools/tutorial-renders`, `-Dicons`).
- `compat/jei/IChemEJeiPlugin`: an information page for every equipment item and for the mod's real items and blocks
  (constructor, simulator, pipe, tools, valves, gauges). Three short lines: what it serves, what it needs (heat,
  cooling, power, steam), where it stands in a plant. The lines are the same lang keys the guide page's header uses.
- The constructor's existing hints stay; they are for a player who already knows the equipment.

## 4. Teaching rules (gated, section 7)

1. One idea a step. Picture or animation first; the words are its caption.
2. At most two sentences and 25 words on screen at once. A film caption is one sentence.
3. Plain words. A professional term is defined before its first use.
4. Everyday anchors first (a boiling kettle, drops under its lid), numbers second, and only numbers the player can
   read on their own screen or that come from the mod's own data.
5. Each step ends with something the player did and saw.
6. The player can leave and come back: a chapter starts from what the world holds.

## 5. The path

| Chapter | The player does | The idea it carries |
|---|---|---|
| 0 Welcome | Opens the guide, chooses units, takes the guide's own `?` tour | How the guide and `?` work |
| 1 Crude oil | Works five cards and one film (below) | What crude is, what is in it, why it is taken apart, the road to daily products |
| 2 The still | Builds a 500 L still, charges crude, fires it, collects two cuts, stops before it boils dry | Each liquid boils at its own temperature; the vapour is richer in the light part; cooling turns it back to liquid; a cut; the pot temperature tells what is coming over |
| 3 From pot to tower | Adds the small column to the still, opens the reflux valve, compares the cut | Reflux makes the product purer; a tray is one small still; a tower is many, running without stopping |
| 4 The simulator | Opens the bundled sheet "Small crude unit", runs it, reads the products, changes the feed temperature, runs again, sizes it, sends it | Try on paper first; a flowsheet; in equals out; heat duty; a side draw is a cut taken at its own height |
| 5 The crude unit | Reads the unit page, then each equipment page as the build needs it | The whole before the parts |
| 6 Build and start | Imports the design, places the tower, lays the lines, passes "Check parts", starts, fills the product tanks | Start-up order; what a limit means; reading the Operation tab |

### Chapter 1, crude oil

| Step | Shown | The player does |
|---|---|---|
| 1.1 What it is | Crude is a liquid from under the ground: a mix of thousands of kinds of molecule, each a chain of carbon with hydrogen round it (first term: hydrocarbon) | Drags a chain from 1 carbon to 40: a gas, then a thin liquid, then a thick one, then a wax; its boiling point climbs on a thermometer (the values of the mod's own compounds) |
| 1.2 What is in a barrel | One barrel of medium crude as a bar cut by boiling range: gas, naphtha, kerosene, diesel, gas oil, residue; sulfur, salt and water as unwanted guests | Moves a temperature slider and sees how much has boiled off; switches to light and heavy crude and sees the bar change |
| 1.3 Why take it apart | No engine burns the whole mix. Petrol must evaporate easily, jet fuel must stay liquid in the cold, diesel must light under pressure, bitumen must stay on the road | Matches four products to their places on the bar |
| 1.4 The road to daily products | A map from the well to the home: clean (take out salt and water), separate by boiling (this guide's unit), separate again under vacuum, convert (break long chains into short ones, reshape them, take out sulfur), blend; then fuel gas, petrol, jet fuel, diesel, lubricating oil, bitumen, and the feed for plastics, fibres, soap and medicine | Presses each box: one sentence, its equipment item, and a mark: built in this guide, in the mod, or not yet |
| 1.5 The first step | A film: dots of three colours in a heated pot; the light ones leave first | Presses Next into chapter 2 |

- Every number comes from the mod's data: the bar from the assays, written once by `tools/tutorial-recordings`
  (`CrudeBarrel`) and held to the data by `CrudeBarrelTest`; the grades from the four bundled assays.
- One exception, decided 2026-11-12: the chain card's boiling and melting points beyond 6 carbons are handbook values
  (CRC, NIST), since the mod's own compounds stop at hexane. `ChainCardTest` holds the first six to the mod's records.
- The bar's parts are cut at 20, 180, 240, 360 and 565 °C (565 °C is the assays' own residue line).
- The map's marks are held to the code (`RoadCardTest` against the simulator's catalog), so they stay true.
- Chapter 1 needs no equipment and no world: a player can do it in the first minute.
- As built (P2A_CRUDE_CHAPTER.md): steps `what` (card chain), `barrel`, `why` (products), `road`, `parts` (the three
  dot colours) and `first` (the film "inside the still"). The last two moved here from chapter 0.

### Chapter 2, the still, as built (P2B_STILL_CHAPTER.md)

- Steps: `build`, `charge`, `water`, `watch`, `fire`, `first`, `cut`, `switch`, `stop`. The server checks `charge`,
  `fire`, `first` and `cut` where it already sends the player the still's screen data (`FluidNetwork.deliver`), `stop`
  where it handles the player's "firing off" against the cached view, `switch` where a player turns a Diverter Valve.
- Films `still_build`, `still_run` and the reworked `still_inside`; card `pot_temperature`. Their numbers come from
  `recordings/still_run.json`, a run of the real law (500 L, 60 % full of medium crude, full firing).
- The recording is on the still law per mole of distillate with the partial condenser (batch
  `2026-11-12-still-heat-per-distillate`, merged into this branch): the light gas vents first (BOILING), the first liquid
  is a trace at 1.2 minutes of play and a trickle at 4; the run ends where the law cuts the fire at 350 °C, 41.0 %
  distilled. `first` and `cut` count liquid at the spout. The fire goes out at the end of the kerosene; the
  film's last stop and the card's dimmed stretch show the fire left on, the heavy part staying in the pot (chapter 3's hook).
- `water` is a reading step: by the code a still's condenser with no cooling water condenses only part of its vapour.

### Chapter 5, the unit and its pages

The unit page comes first: a film that follows the crude (tank, pump, fired heater, tower with bottom steam, air
cooler, reflux drum, side products, residue) and a flowsheet picture whose symbols open the pages. Each equipment page
has a film, a card, the `?` tour of its screen, its JEI lines, and "next in the unit".

### The first crude (D6)

The owner: a converter from a Forge fluid, and later a crude extraction system. The Fluid converter is on main since
0.28.0; its bundled rules turn `#c:crude_oil` (TFMG, PneumaticCraft) and `ad_astra:oil` into medium crude at ambient.
Chapter 2's charging step names it, and the creative Fluid Generator, through one key. Without one of those mods a
survival player has no crude until the extraction system exists.

## 6. The small column template

"Small crude tower (Medium crude)", a bundled `columnpreset` and the column of the bundled sheet "Small crude unit".

- Size by constant vapour load per area from the 8 m tower: 1.0 m carries 1/64 of its feed, about 40 kmol/h, 10 m³/h,
  1,500 bbl/d. 1.5 m carries about 90 kmol/h.
- Three candidates, each solved once in the simulator and sized, then one chosen (D4, the owner's choice from rendered
  datasheets; proposed B):
  - A: the 42-tray tower scaled down (known to converge; tall, 3 pumparounds).
  - B: about 20 trays, naphtha overhead, kerosene and diesel draws, a pumparound under each draw, bottom steam.
  - C: about 14 trays, one draw (diesel), one pumparound, bottom steam.
- Known constraints: a large draw needs pumparound cooling above it; the learned start may decline a short tower,
  which then starts from the classical seed.
- Accepted when the solve passes its audit, every sizing lamp is green with the 20 % spare, the products' boiling
  ranges are apart, and the whole unit fits a plot of 16 by 16 blocks.

## 7. Gates and checks

- `TutorialTextTest`: the word limits; every term defined before its use; every key has its English line.
- `SceneTest`: every film's actors exist, its stops are in order, its series files read, `Scene.at` is the same twice.
- `HelpCoverageTest`: every screen page has a tour; every stop's key exists in its builder at every width.
- `GuideCoverageTest`: every `EquipmentShape.Kind` has an item, a name, its three lines and a page; every objective
  names a registered criterion.
- Renders: `tools/tutorial-renders` draws every film stop, card frame and tour stop; the owner reviews the sheets.
- One in-game check for the scene player (P0), started by the owner; none for content.
- Text: `en_us.json` only during the batch (about 450 keys in all); one translation commit before the merge.

## 8. Phases

| Phase | Content | Done when | Difficulty |
|---|---|---|---|
| P0 Scene player | `Scene`, `Frame`, `SceneView`, the render tool; the film "inside the still" with 3 stops and a cutaway | The game and the tool show the same stops; the owner's one in-game check | Hard |
| P1 Guide and help | `Guide`, `GuideScreen`, `GuideHud`, the triggers, `HelpTour`, the `?` key, `CardView`, the gates | Chapter 0 runs; the still's page has its tour | Standard |
| P2 Chapters 1 and 2 | The crude cards and map, the still's films and steps, the recordings tool | A new player finishes chapter 2 with no outside help | Standard |
| P3 Items and JEI | `EquipmentInfoItem`, the icons, the JEI plugin, the three lines for 38 kinds | `GuideCoverageTest` passes for items and lines | Standard |
| P4 Simulator and column | Chapters 3 and 4, the simulator tours, the small column template, the bundled sheet | The sheet runs and sizes green | Standard; the template's solves need a sub-thread |
| P5 The unit | Chapters 5 and 6, the equipment pages of the unit | A player builds and starts the unit from the guide alone | Standard |
| P6 Coverage | `?` on every remaining screen, the remaining pages, translation, merge preparation | Every gate passes | Standard |

Built so far (2026-11-12): P0 (P0_SCENE_PLAYER.md), P1 (P1_GUIDE_AND_HELP.md) and P2: chapter 1
(P2A_CRUDE_CHAPTER.md) and chapter 2 (P2B_STILL_CHAPTER.md). Open in P2: the second recording of the still's run after
the law's fix. Nothing has been checked in the game yet.

P0 to P2 are the first batch (D7): they prove every layer on the first equipment before content is written in bulk.
P3 touches no file of P0 to P2 but the lang file and can run beside them.

## 9. Owner decisions

| | Decision | Answer |
|---|---|---|
| D1 | Ponder or own | Owner, 2026-11-12: own if Ponder is not sufficient. It is not (section 2): own framework |
| D2 | Progress store | Vanilla advancements (as recommended) |
| D3 | How the guide starts | A Field Guide item at first join, plus a key (as recommended) |
| D4 | The small column | Candidate B if it converges and fits; the owner picks from the datasheets |
| D5 | Units | The first step offers °C, bar, m³/h, kg/h (as recommended) |
| D6 | The first crude | Owner: a converter from a Forge fluid, later an extraction system; see section 5 |
| D7 | First batch | P0 to P2, then review (as recommended) |
| D8 | Equipment items | Owner: an item for each kind, and JEI pages saying what each equipment serves |

Open for the owner:

- O1: are the equipment items reference only (creative tab and JEI), or does a survival player get them? Proposed:
  reference only.
- O2: the converter's place in the order of work, since chapter 2 in survival waits for it.

## 10. Not in this plan

- Guided chapters for control loops, heat integration, rotating machinery, cooling towers and the conversion units:
  equipment pages and JEI lines in P3 and P6, chapters later.
- The Forge fluid converter and the crude extraction system.
- Voice, a mascot, quests with rewards.
