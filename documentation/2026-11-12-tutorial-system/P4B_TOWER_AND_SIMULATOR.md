# P4b: chapter 3 "Pot to tower" and chapter 4 "The simulator"

Batch `2026-11-12-tutorial-system`, 2026-11-12. Commits: recorder 66ed0baa4, chapter 3 3a7bb0d1c, chapter 4 7cbd240e0, tours 402e1b4a9, gates 8295a579e; corrections: film 759b1f940, units d23ea3b4c.
## Part 1: the still with a column (`still_trays.json`, `StillTraysRecorder`, the still law unchanged)
| form | first cut (18 % over): naphtha, pot, play min | second (29 %): kerosene, play min | column after the light gas |
|---|---|---|---|
| pot still | 75.2 %, 232.7 °C, 16.3 | 39.1 %, 20.9 | none |
| 4 trays, reflux 1 | 84.9 % (+9.7), 249.7 °C, 19.1 | 55.1 %, 24.6 | 99 % solved (dry 33 steps at 30-112 °C, fallback 5 at 114-138 °C) |
| 4 trays, reflux 2 | 88.1 % (+12.9), 254.5 °C, 21.5 | 64.4 %, 28.0 | 91 % solved (dry 39 at 30-124 °C, fallback 11 at 124-148 °C) |

500 L still, medium crude, full firing, 4 trays (`StillColumn.MAX_STAGES` 8; the constructor offers 0 or 2 to 8).
The claim holds at reflux 2 (+12.9 points, 91 % solved, 21.5 minutes). All three runs end at the law's 350 °C cut.
Failed premise, reported: a cut takes 1.17 to 1.32 times the pot still's time, not R + 1 times, because the charge's
sensible heat (about 124 MJ) dwarfs the cut's latent heat (about 14 MJ). The "dry" steps are the column with no liquid
on its trays while the pot warms: the old open item (a trayed still answers as a pot still) holds only below 124 °C.
## Chapter 3 "Pot to tower" (`tower`, after `still`)
- `tray` film `still_tray` (3 stops), guide_step. `build` server `equipment_built` {BATCH_STILL, column}. `reflux` card
  `reflux` (slider over the three forms), server `still_state` {column, min_reflux 2}. `compare` card `compare`, server
  `still_state` {column, liquid, min_pot_temperature 528 = the reflux-2 first cut in whole K}. `many` film `small_tower`
  (4 stops on `columnpreset/small_crude.json`; stop 2 cut open: round plates, downcomers, liquid), guide_step. Terms: tray, reflux, tower.
- Shell halves: none was needed; the film draws its column from `ShapeRasterizer.stillParts`. The still's `?` tour has
  a variant for a still with a column and its condenser, `fluid_device.still_column` (7 stops, with `still_reflux:open`).
## Chapter 4 "The simulator" (`simulator`, after `tower`)
- `open` client (the screen reports the example "Small crude unit" opened). `sheet` reading step, completed by finishing
  the sheet page's tour. `run` server `sheet_run` (SheetStore stores results that ran on the stored sheet). `balance`
  card `balance`. `heat` server `sheet_run` {max_heater_outlet 643.15 K}; its text writes `{643.15 K}`, shown in the player's unit. `size` client (Size all's sizes landed).
  `send` server `design_sent`. Terms: flowsheet, stream, side draw, heat duty.
- `small_unit.json` (`SmallUnitRecorder`, gate `SmallUnitRecordingTest` in holdGates): at 390 °C STEADY, crude 8,968
  kg/h, top 18.2 / side 34.9 / bottom 46.9 % by mass, H-101 1,442 kW; at 370 °C 15.1 / 31.9 / 53.0 %, H-101 1,280 kW.
- The size step's "yellow means bigger than this small unit needs" holds for all seven yellow lamps: S-6 is yellow for
  2.7 m/s two-phase under the 3 m/s good-practice floor, its bore wider than the flow needs.
- Tours (one sentence a stop, anchors by key): `simulator.sheet` 7 (last stop shows chapter 4's card), `results` 5,
  `sizing` 4, `basis` 4. The `?` key stands left of the theme key. A tour stop draws in one batch: 2 draw calls.
## Changes outside the tutorial packages
`SimulatorFrame.help()`; `ProcessSimulatorScreen` (`?` key, tour input, two client step reports);
`FluidDeviceScreen.tourPage`; `EquipmentNetwork` (the built shape to the criterion); `ProcessSimulatorNetwork` and
`SimulatorResults.ranOnBody` (the two criteria). No change to the still law, the column solver, the catalogue, Size all,
the column preset or the example.
## Gates
New: SimulatorTourTest (3), TowerFilmsTest (2), TowerCardsTest (3), SheetConditionTest (1). Extended: GuideCoverageTest
(8), HelpCoverageTest (6), DrawCallTest (7), GuideFileTest (8), GuidePageTest (5). One run, `--tests`, 4 workers: 35 classes,
265 tests in `test`, and SimulatorRunTest (6) in holdGates; two failures fixed (a chapter list, a share tolerance) and
their two classes rerun alone. SmallUnitRecordingTest (2, holdGates) and StillTraysRecordingTest (4) passed when
written. Not run: LanguageFilesTest, UnitTextTest (the translation commit), the still law's classes, columnGates.
## Renders (`tmp/tutorial-renders/p4b/`)
Films `still_tray-*`, `small_tower-*`; cards `card-reflux`, `card-compare`, `card-balance`; guide `guide-tower`,
`guide-simulator`; tours `tour-fluid_device-still_column`, `tour-simulator-{sheet,results,sizing,basis}` (light, dark).
Fixed from them: a finished chapter's title under its done mark (GuidePage now centres it right of the mark); chapter 3's
title shortened from "From pot to tower" (clipped in the small window); "1 parts back" (card reflux); the balance
card's labels (now a legend) and its flow (now the player's time unit); a lone "%" line (heat step reworded).
