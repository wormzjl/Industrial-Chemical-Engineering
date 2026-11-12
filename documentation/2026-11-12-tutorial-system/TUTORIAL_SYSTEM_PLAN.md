# Tutorial system: plan

Owner request, 2026-11-12: a tutorial for players of 18 to 30 with no chemical engineering. It teaches how to use the
mod and the basic ideas together, in small steps, with pictures and animation before words. It starts at the still,
goes on to the process simulator and the column, and guides the player to a first small crude distillation unit. Ponder
where it can do the job, an own framework where it cannot. No code yet: this is the proposal for the owner.

## 1. What exists (read from the code and the Ponder 1.0.82 sources)

| Fact | Where |
|---|---|
| Ponder 1.0.82 is compiled against and loaded in dev runs, and no class uses it. | `build.gradle` 240, 248 |
| Ponder is its own mod (`ponder`, MIT), not part of Create. It requires Flywheel. | its `neoforge.mods.toml` |
| A Ponder scene is a film: blocks from an `.nbt` schematic in a virtual level, block entity renderers, timed text, outlines, input icons, keyframes, tags, an index. | `api/scene/*`, `WorldSectionElementImpl` |
| A scene takes own instructions and own elements (`PonderInstruction`, `AnimatedSceneElement`, `AnimatedOverlayElement`), and opens from code (`PonderUI.of(id)`). | `api/element/*`, `PonderUI` 145 |
| Ponder draws nothing on a GUI screen, checks no objective and never looks at the player's world. | whole API |
| Equipment has no item of its own kind. The Equipment Constructor builds every kind as `equipment_shell` cells; Ponder keys its scenes by an item id. | `EquipmentStructure`, `PonderSceneRegistrationHelper` |
| Flames, levels, drips and moving parts are drawn from state the server publishes. A Ponder level has no engine. | `client/fx/state/ProcessStates` |
| No screen has a help key. The title strip already keeps a place for one key at its right (the theme key). | `TitleStrip.place(width, key, …)` |
| Bundled column templates are 8 m crude towers for about 100,000 bbl/d (42 trays, 3 draws, 3 pumparounds) and a two-tray pot. A column's diameter is 0.5 to 15 m in half metres. | `assets/icheme/columnpreset/`, `ColumnDesign` 23 |
| The still: pots of 100 to 2000 L (500 L default), solid, liquid or electric heat, a top condenser, an optional small column with a reflux valve, a diverter valve for cuts; it is a simulator unit too. | batch `2026-11-09-batch-distiller` |
| The mod has no advancements. | `data/icheme/` |

## 2. Verdict on Ponder

Ponder does one third of the request well: showing how equipment is built and what happens inside it. It cannot do
the other two thirds: help on a GUI, and guiding the player through their own build. Proposal: three layers, each with
one job, joined by one table of contents.

| Layer | Job | Built on |
|---|---|---|
| **Films** | See it: how a unit is built, what flows where, what happens inside | Ponder |
| **Help tours and concept cards** | Read a screen: a `?` key on every GUI; one idea on one animated card | Own, in the GUI kit |
| **Field guide** | Do it: chapters of small steps, each checked off when the player has done it | Own; progress in vanilla advancements |

## 3. The three layers

### 3.1 Films (Ponder)

- `compat/ponder/` is the only package that names a Ponder type (as `compat/create` for Create). Schematics in
  `assets/icheme/ponder/`.
- One film is 20 to 40 s and makes one point. Text is one sentence at a time. A cutaway shows the inside: the film
  hides the front half of the shell cells.
- Film state is recorded, not computed. A tool (`tools/tutorial-recordings/`) runs the real law offline (the still:
  `BatchStill`) and writes the series the film replays (pot temperature, level, cut, flame). The film shows true
  numbers and the client calculates nothing. A `RecordedState` instruction feeds `ProcessStates` for the scene's cells.
- Molecules are drawn as particles of two or three colours (light, middle, heavy): the light colour leaves the pot
  first. This is the one picture that carries the idea of separation through every chapter.
- Scenes are keyed to the items a player holds (constructor, simulator, pipe, tools, valves) and to page ids the
  field guide opens with `PonderUI.of(id)`. Tags: Separation basics, The crude unit, Pipes and tools, Utilities.

Risks, decided by a spike before anything else (section 7, P0):

1. Do `equipment_shell` cells draw in a Ponder level? Catnip takes model data from `level.getModelData(pos)`, which is
   probably empty in a virtual level. If so, the shell model reads its block entity in
   `BakedModel.getModelData(level, pos, state, data)` (as `ShaftModel` 70 does).
2. `EquipmentShellBlockEntity.onLoad` registers instrument hosts and client trackers; it must do nothing harmful in a
   level that is not the client's world.
3. Region body meshes and LOD (0.23.2) do not run in a Ponder level; the cells must fall back to the per-cell mesh.
4. Can a scene be keyed to an id that is no item (index icon, tag screen)?

### 3.2 Help tours and concept cards (own)

- **`?` key** on every screen's title strip, beside the theme key, a sign and not a word (panel rule 7). It opens that
  page's tour. Screens: fluid device (each tab), machine, assembly, constructor, simulator (sheet, doors, sizing),
  control panel and DCS, gauge, pipe tools, datasheet.
- **Tour**: the screen dims, one control is lit, one sentence says what it is for. Next, Back, Close. At most 7 stops
  a page. A stop names its control by the key its builder already gives it (`Column` row keys, tab ids), never by
  pixels, so a tour survives a layout change. A stop can wait for the player ("Press Run"): the tour goes on when the
  press happens. The tour sends nothing to the server.
- **Concept card**: one animated drawing on the kit's canvas with a caption of at most two sentences, built in a class
  with no Minecraft types, so `tools/heat-gui-renders` draws its frames for the owner. Cards: boiling points on one
  line; vapour is richer in the light part; one tray is one small still; reflux; a side draw; in equals out; heat duty.
- **Glossary**: a term is defined once in plain words, on the card that first needs it. After that it is underlined
  everywhere in tutorial text, and its hover shows the definition.

### 3.3 Field guide (own)

- A Field Guide item (given once) and a key open the guide: chapters at the left, the steps of one chapter at the
  right. A step is one sentence, one picture or film link, and one objective. A small HUD line shows the current step;
  the player can hide it.
- Objectives are checked by the server from events it already has, on the engine's schedule: a structure placed
  (`EquipmentStructure`), a network edit (`FluidWorldAuthority.listen`), a still's first distillate booked (`Stills`),
  a sheet's results stored (`SheetStore`: the run itself stays on the client), a design sent, "Check parts" passed, a
  column started (`AssemblyService`). No objective forces a process update.
- Progress is stored as vanilla advancements under `data/icheme/advancement/tutorial/` with custom criteria
  (`runtime/tutorial/TutorialTriggers`). Vanilla saves and syncs them per player, so the mod gains no save format of
  its own and the saves rules stay untouched. The guide screen is only another view of them.
- The guide's content is data: `assets/icheme/tutorial/*.json` (chapters, steps, glossary, tours) with lang keys.

## 4. Teaching rules (gated, section 6)

1. One idea a step. Picture or animation first; the words are its caption.
2. At most two sentences and 25 words on screen at once. A film line is one sentence.
3. Plain words. A professional term is defined before its first use, and a step uses only terms defined in an
   earlier step or its own.
4. Everyday anchors first (boiling water, a kettle lid collecting drops), numbers second, and only numbers the
   player can read on their own screen.
5. Each step ends with something the player did and saw. No step is only reading.
6. The player can leave and come back: every chapter starts from what the world holds, not from the step before.

## 5. The path

| Chapter | The player does | The idea it carries | Films · tours · cards |
|---|---|---|---|
| 0 Welcome | Opens the guide, plays one film | How the guide, films and `?` work | 1 film |
| 1 The still | Builds a 500 L still, charges crude, fires it, collects two cuts, stops before it boils dry | A mixture; each liquid boils at its own temperature; the vapour is richer in the light part; cooling turns it back to liquid; a cut; the pot temperature tells what is coming over | Films: build, inside the still, a run, changing the receiver. Tour: the still's page. Cards: boiling points, vapour is richer |
| 2 From pot to tower | Adds the small column to the still and opens the reflux valve; compares the cut | Reflux: sending liquid back makes the product purer; a tray is one small still; a tower is many, running without stopping | Film: still with column. Cards: reflux, one tray, batch against continuous |
| 3 The simulator | Opens the bundled sheet "Small crude unit", runs it, reads the products, changes the feed temperature, runs again, sizes it, sends it | Try on paper first; a flowsheet; in equals out; heat duty; a side draw is a cut taken at its own height | Film: the simulator block. Tours: sheet, column door, sizing. Cards: in equals out, side draw |
| 4 The crude unit | Reads the unit page, then each equipment page as the build needs it | The whole before the parts: crude tank, pump, fired heater, tower with steam, air cooler, reflux drum, side products, residue | Film: the unit, the crude followed by a moving outline. One page per equipment (film, card, tour). A flowsheet picture whose symbols open the pages |
| 5 Build and start | Imports the design, places the tower, lays the lines, passes "Check parts", starts, fills the product tanks | Start-up order; what a limit means; reading the Operation tab | Films: import and place, laying lines. Tours: assembly, Operation. Cards for the common conditions in plain words |

Chapter 4 is the chain the owner asked for: the unit page comes first and links every equipment page; each equipment
page ends with "next in the unit".

### The small column template

"Small crude tower (Medium crude)", a bundled `columnpreset` and the column of the bundled sheet "Small crude unit".

- Size by constant vapour load per area from the 8 m tower: 1.0 m carries 1/64 of its feed, about 40 kmol/h, 10 m³/h,
  1,500 bbl/d. 1.5 m carries about 90 kmol/h.
- Shape, at most three candidates, each solved once in the simulator and sized, then one chosen:
  - A: the 42-tray tower scaled down (known to converge; tall and with 3 pumparounds, much to build).
  - B: about 20 trays, naphtha overhead, kerosene and diesel draws, one pumparound under each draw, bottom steam.
  - C: about 14 trays, one draw (diesel), one pumparound, bottom steam: the least to build.
- Constraints already known: a large draw needs pumparound cooling above it; the learned start has 3 draw slots and may
  decline a short tower, which then starts from the classical seed.
- Accepted when: the solve converges and passes its audit, every sizing lamp is green with the 20 % spare, the
  products' boiling ranges are apart, and the whole unit fits a plot a new player can build (target: under 16 by 16).
- The owner picks from rendered datasheets of the candidates (decision D4).

## 6. Gates and checks

- `TutorialTextTest`: every step and film line within the word limits; every glossary term defined before its use;
  every key has its English line.
- `HelpCoverageTest`: every screen and page has a tour; every tour stop's key exists in its builder at every width.
- `GuideCoverageTest`: every `EquipmentShape.Kind` has an equipment page or is listed as "later"; every objective
  names a registered criterion (as `FlowsheetPaletteCoverageTest` does for the simulator).
- Cards and tours: software renders (`tools/heat-gui-renders`), shown to the owner.
- Films cannot be judged by a software render. Each batch of films ends with one in-game check that the owner starts.
- Text: `en_us.json` only during the batch, about 300 keys in all; one translation commit before the merge.

## 7. Phases

| Phase | Content | Done when |
|---|---|---|
| P0 Spike | One film of a 500 L still: the four risks of 3.1; the packaging check for D1 | A short report; D1 and D2 decided |
| P1 The still, end to end | `?` key and tour on the still's page; two cards; the guide screen with chapters 0 and 1; triggers; the still's films | A new player finishes chapter 1 with no outside help |
| P2 Simulator | Chapters 2 and 3; simulator tours; the small column template and the bundled sheet | The sheet runs and sizes green |
| P3 The unit | Chapters 4 and 5; equipment pages; the unit film | A player builds and starts the unit from the guide alone |
| P4 Coverage | `?` on every remaining screen; remaining equipment pages; translation; merge preparation | The three coverage gates pass |

P1 is a vertical slice on purpose: it proves all three layers on the first equipment before any content is written
in bulk.

## 8. Owner decisions

| | Question | Options | Proposed |
|---|---|---|---|
| D1 | How Ponder reaches the player | A: optional; without Ponder (in practice without Create) the guide shows still pictures in place of films. B: Ponder and Flywheel shipped inside the mod's jar. C: Ponder a required download | B if the spike shows the nested jars resolve against Create's own copies and Flywheel's licence allows it; otherwise A. Flywheel is a rendering mod with mixins, so B adds it to every pack |
| D2 | Where progress is stored | A: vanilla advancements. B: an own per-player store (a new facts format, with its corpus) | A |
| D3 | How the guide starts | A: a Field Guide item given at first join, plus a key. B: the key only, with a toast at first join | A |
| D4 | The small column | Candidates A, B, C of section 5, after their solves | B, if it converges and fits the plot |
| D5 | Units for a new player | A: leave the client's units. B: the guide's first step offers °C, bar, m³/h and kg/h | B; kmol is introduced in chapter 3 or not at all |
| D6 | Where the first crude comes from in a survival world | Not found in the code read for this plan | Owner to say; chapter 1's second step depends on it |
| D7 | First batch | A: P0 and P1 only, then review. B: all phases planned in detail now | A |

## 9. Not in this plan

- Tutorials for control loops, heat integration, rotating machinery and cooling towers: equipment pages in P4, guided
  chapters later.
- Voice, a mascot, quests with rewards.
