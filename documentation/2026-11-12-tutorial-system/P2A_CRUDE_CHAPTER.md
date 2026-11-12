# P2a: chapter 1 "Crude oil"

Built 2026-11-12 on `claude/tutorial-system-chem-mod-950ca9`. Commits 07ae749e5 (three_parts mixed at heat 0),
849cf20ef (card interface), aa9120b0d (chain), 3450240c6 (barrel, products, recording), 923f39d53 (road), 207577d1f
(chapter data), 5aa24fd86 (render review fixes), and this report.

## Built
- `Card` gains `State` (phase, stepped control, option, last hit, hits pressed, the client's temperature format),
  `options()` (soft keys over the drawing, flowing to a second row where narrow), `hits()`, `steps()`, `done(control,
  visited)`. `CardView` keeps option, hit and visited; Left and Right step through the hits, or the slider's steps.
  `GuideScreen` passes `Card.temperature(ClientConfig.temperature())`; the render tool passes °C.
- Cards (`science/tutorial/cards/`, no Minecraft class): `Chain` (13 chains, C1 to C40), `Barrel` (four grades, a
  temperature mark from 0 to 600 °C), `Products` (six bands with pictograms), `Road` (nine boxes).
- `BarrelData` reads `assets/icheme/tutorial/recordings/crude_barrel.json` once, when a card is built; it is written by
  `tools/tutorial-recordings/CrudeBarrel.java` (task `recordCrudeBarrel`) from the assays, components and properties.
- Guide: `welcome` = guide, units, help; `crude` = what (chain), barrel, why (products), road, parts (three_parts),
  first (film still_inside). Terms: hydrocarbon (what); gas, naphtha, kerosene, diesel, gas_oil, residue (barrel); still
  (first). Advancements chain root, welcome, crude; `still/build` hangs on `crude/first`.

## Decisions
1. `FlowsheetCatalog` (the catalog `FlowsheetPaletteCoverageTest` reads) lives in test code. `Road` holds the modelled
   kinds it names; `RoadCardTest` holds them equal to the catalog. The brief's `TANK` is the constructor's
   `FLOATING_ROOF_TANK` (the short names are keyed by constructor equipment).
2. `crude_pc01` has no `cut.lower_kelvin`. Its cut is taken centred on its own normal boiling point: 305.4 K, the
   cuts' "adjacent NBP midpoints" rule. Either choice leaves it in naphtha.
3. `crude_pc12` (open above, from 1023 K) never boils below 900 K and counts as residue.
4. Barrel and products share the recording, bands and colours: one commit.
5. The marks: "You build this in this guide" (boil); runs (vacuum, blend, products); shape only (break, sulfur); not in
   the mod yet (well, clean, reshape). The road sheet shows one box of each of the four.
6. C8 to C40 are CRC Handbook / NIST WebBook values. C30 matches NIST/PNNL (449.7 / 65.8 °C). C40 melts at about
   81 °C in the NIST entries (table 82, within 2 K). Its boiling point (522 °C) is an extrapolated handbook value with
   no measurement found.
7. The P1 advancements `welcome/dots` and `welcome/film` are removed (moved to `crude/parts`, `crude/first`). The
   branch is not merged, so no world holds them.

## Band shares (whole percents by largest remainder)
| Grade | gas | naphtha | kerosene | diesel | gas oil | residue |
|---|---|---|---|---|---|---|
| medium | 1 | 17 | 11 | 24 | 28 | 19 |
| light | 2 | 30 | 16 | 28 | 20 | 4 |
| heavy | 0 | 11 | 7 | 14 | 22 | 46 |
| oil sand | 2 | 20 | 3 | 13 | 23 | 39 |

## Tests (filtered, all pass)
New: ChainCardTest 3, CrudeBarrelTest 5, RoadCardTest 3. Extended: CardViewTest 7, GuidePageTest 5, GuideCoverageTest 5,
GuideFileTest 7, TutorialTextTest 3. Reached: HelpCoverageTest 5, TourLayoutTest 2, MarkedTest 2, ProgressTest 3,
SceneTest 8, StillInsideFilmTest 4, HeatDiagramTest 5, KitLayoutTest 17, DrawCallTest 6.

## Renders (`renderTutorialGuide`, into `tmp/tutorial-renders/`)
`card-chain.png`, `card-barrel.png`, `card-products.png`, `card-road.png`, `card-three_parts.png`, `guide-crude.png`,
plus the P1 sheets. Fixed after review: options in one row at 320 by 240, the barrel legend in two columns there, the
road's mark ahead of the equipment names, "1 carbon atom".
