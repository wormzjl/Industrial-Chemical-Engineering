# Part: client and network tests

Scope: the 17 rows of `added-by-file.csv` under `client/` and `network/`, all new files since `f91c992d`.
That is 98 methods (client 88, network 10) and 3.9 s of the 365 s suite, about 1 %.

Finding: this area has little to remove. Most methods are gates the gui-creation skill names (K3) or the only round trip of a codec (K2). Five methods are proposed. The four C1 changes below save more time than the removals, and the whole area is too small to move the suite's time.

## Proposals

| class | methods | verdict | methods to remove | reason and named kept guard |
|---|---|---|---|---|
| `client.gui.common.kit.KitLayoutTest` | 18 | TRIM | `compactMetricsPlaceTheSameRowsShorterAndAChoiceDrawsItsTitleOnly` | S1. The door sweeps run in COMPACT and keep its checks on widgets and keys: `CalculatorColumnTest.everyPageFitsAtEveryWidthTheScreenTakes`, `HeatControlColumnTest.theColumnsAndTheirWidgetsFitThePanelAtEverySupportedWidth`. `noRowCutsItsTextTheyWrapAndButtonsStack` keeps "a choice draws its title" in COMPACT. `aRowHoversItsHintOutsideItsWidgets` keeps a choice's hover, on the same line of code. The 11 px height is S3: the renders judge it. The two `Metrics` refusals guard only two constants in `Column.java`, and a bad constant fails every column test at class load. |
| same | | TRIM | `aSkinDrawsTheMarksTheBarsAndTheTonesItsRowsName` | S3. It pins bar rects, the thumb's height and a tone. No rule names them. `GuiPrototypes` runs the same `Column.draw` and `ScrollView.drawBar` on every sheet, in both skins, opened and scrolled. `aReadingIsPlacedAsAFieldAndDrawnAsAMeter` and `aSettingStepsBeforeItsBoxInLineWithTheEntries` keep an automatic check that `Column.draw` calls the skin. |
| `client.gui.common.kit.FlowsheetPainterTest` | 3 | TRIM | `marksStayInsideTheBoxTheyAreDrawnFor` | S3. It pins mark pixels and a dash count. No rule names them. `HeatDiagramRender` and `GuiPrototypes` draw these marks through `HeatFlowsheet` and `AssemblyFlowsheet`. `AssemblyPortColoursTest.physicalNozzleIdentitiesMatchDistinctDrawnColoursBeforeAndAfterConnection` keeps the square's colour and frame. |
| same | | TRIM | `aLabelDrawsOnlyWhereItFitsAndIsAnchored` | S3, weak. It pins anchor pixels, which the renders judge. Its refusal branch is close to rule 3 and feeds the pill fallback in `HeatFlowsheet`, so a person must see a fault on a render. The owner may keep it. |
| `client.gui.common.DialogColumnsTest` | 8 | TRIM | `thePaletteFillsItsGap` | S3. Two rows of eight swatches is a layout number no rule names. `ConstructorColumnTest.eachPageShowsItsKeysWithTheCurrentPositionsLatched` keeps 16 swatches at 16 px or wider. `theWarningMovesNoWidget` keeps their place. The `pipeline-tool` and paint sheets of `GuiPrototypes` draw the real places. Lost: the check that a tool with no Paint group gets no swatches. |
| `client.gui.common.OperationColumnTest` | 5 | C1 | none | `theDoorAndTheGlassFitAtEveryWidthThePageTakes` (0.80 s): no assertion in its sweep depends on height (`area`, `glass`, `display` and `footer` widths use the width only, and `area.y` is fixed). Remove the `HEIGHTS` loop and build `inputs()` once. Saves about 0.5 s. |
| `client.gui.common.AssemblyColumnTest` | 8 | C1 | none | `theDoorFitsAtEveryWidthTheScreenTakes` (0.26 s): run the `Column.place` loop at the lowest height only, where `column.bounds().h() <= area.h()` binds. Keep the other checks at each height. Saves about 0.18 s. |
| `client.gui.common.DeviceColumnTest` | 6 | C1 | none | `theColumnFitsTheDoorAtEverySupportedWidth` (0.26 s): build `DeviceColumn.rows` once per case, as it takes no width. Saves about 0.1 s. |
| `client.gui.common.CalculatorColumnTest` | 12 | C1 | none | `theRefluxEntryShowsAtEveryWidth` (0.26 s): build `CalculatorColumn.column` once per input, as it takes no width. Saves about 0.1 s. |

Totals: 98 methods reviewed, 5 methods removed, 0.01 s saved. The C1 rows save about 0.9 s more (estimates, nothing measured).

## Looked at and kept

- Network: 0 of 10. Each method is the only round trip of its codec or path. `anAnsweredDesignReadsBackAsTheServerWroteIt` looks like `EquipmentShapeCompatTest.aConstructorKeepsEveryFormsFieldsAcrossAReload`, but only it writes and reads a constructor's turns with a moved, repainted draft. `anImportLandsOnTheDraftTheScreenSent` is the only guard that paint and turns survive an import, and `references/server-actions.md` names this flow. `aWatchedViewOrItsEndCrossesWithItsMaster` shares the view codec, but `WatchedPayload` and `WatchPayload` are their own packets.
- `CalculatorColumnTest.theRefluxEntryShowsAtEveryWidth`: no owner rule, and the largest possible removal (0.26 s). No kept test implies "in view with no scroll", and the renders no longer draw the 710 to 823 px range of the defect. It stays, with the C1 above.
- `AssemblyColumnTest.theGlassSplitsBetweenTheDrawingAndTheList`, `HeatDiagramTest.everyPartLiesInsideTheDrawingAndNoPartCoversAnother` and `CalculatorColumnTest.theSaveFormStandsInsideTheGlass`: no rule names them, but the renders do not cover their sweeps or draw the form.
- `FlowsheetPainterTest.theGridIsFittedAndCentredInItsBox`: `HeatFlowsheet`'s hit tests use `designX` and `designY`, and no other test reaches them.
- `aChoiceRunsItsActionOnlyInsideItsRow`, `thePlotsStayOnTheGlassAndAnswerTheirClicks`, `theDrawingAnswersItsOwnPartsAndPorts` and `portsOutsideTheDrawingHaveNoAssemblyColour`: not K6 under the narrow reading, but no render can judge a click or a colour left out, so no guard can be named.
- `OperationColumnTest.theTablesShowEveryPortAndDuty` and `FluidGuiConsistencyTest.everyPressureUnitRetainsGaugeResolutionAndItsOwnLabel`: they look like label pins, but they guard the numbers shown (K6).

## Notes

- S3 here rests on a person reading the renders. No gate task runs them.
- `FlowsheetPainter.rounded` has no caller in `src/main` or the render tools. `marksStayInsideTheBoxTheyAreDrawnFor` is its only user, so that removal leaves it dead code.
- `cases.csv` holds `DeviceColumnTest.aTowersControlFitsTheDoorAtEverySupportedWidth`, which is not in this tree. It is not reviewed here.
