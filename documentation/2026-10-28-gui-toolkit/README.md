# 2026-10-28-gui-toolkit

Purpose: owner choice 2026-10-28, option C of the GUI toolkit review: a small GUI toolkit in our own code, first used on the heat Control tab.

Status: In progress since 2026-10-28, on branch `claude/gui-toolkit` (stacked on `claude/heat-gui-redesign-abb1db`). The kit, the Control tab pilot and the shared flowsheet painter are built. The fluid screen draws the light or the dark control panel door, with layout C and fuller drawings. The Control column is framed function groups with short plates: an Off and On switch, then a target. The unit keys are display keys, and the theme and the units are the client's. The other screens are reviewed with prototypes (owner: "Good, proceed"); every review order is built on the kit: the device pages, the small dialogs, the equipment constructor, the assembly screen and the column calculator (owner: "finish this"). Index: [../INDEX.md](../INDEX.md).

## Documents

- [GUI_TOOLKIT_PLAN.md](GUI_TOOLKIT_PLAN.md): the kit, the pilot, the shared flowsheet painter, the gates, the as-built notes (section 5), choices T1 to T3 (section 6), the Control tab styles tried (section 7), the industrial looks (section 8), look 3 refined (section 9), the light and dark panels shipped with layout C, whole texts and fuller drawings (section 10), function groups, plates and the On and Off switch (section 11), display keys, the client's units and the theme sign (section 12), labels beside their boxes and LCD entries against LED readings (section 13), the device pages' door column (section 14), the small dialogs (section 15), the equipment constructor (section 16), the assembly screen (section 17), the column calculator (section 18), the screens' own scale and the review's choices (a) to (e) (section 19).
- [GUI_REVIEW.md](GUI_REVIEW.md): the other screens against the panel rules, their prototypes, the owner's choices (a) to (e) and the order to build them.
- The evaluation that chose it: [../2026-10-28-gui-toolkit-evaluation/GUI_TOOLKIT_REVIEW.md](../2026-10-28-gui-toolkit-evaluation/GUI_TOOLKIT_REVIEW.md).

## Code areas

- `client/gui/common/kit/` (new): `Rect`, `Measure`, `Canvas`, `Palette`, `Format`, `Column`, `ScrollView`, `Skin`, `PanelSkin`, `FlowsheetPainter`; `Column.Setting` (an LCD entry with steppers); the game adapters `GuiCanvas`, `KitWidgets`, `SkinButton` and `SkinField`, `KitDoor` (a door's columns, side by side in one scroll view), and `WindowScale` (a screen's own whole GUI scale).
- `client/ClientConfig` (new, `icheme-client.toml`: the theme and the display units), registered in `IChemE`. `science/display/DisplayUnit` (holds `TemperatureUnit`, from `ProcessUi`).
- `client/gui/common/HeatControlColumn` (new): the Control column as kit rows, and layout C's split. `client/gui/common/DeviceColumn` (new): a device page's door column as kit rows. `client/gui/common/DialogColumns` (new): the small dialogs' columns and the datasheet's glass. `ConstructorColumn`, `AssemblyColumn` with `AssemblyFlowsheet`, and `CalculatorColumn` (new): the constructor's, the assembly screen's and the column calculator's doors and glasses. `ColumnDiagram` draws on `kit.Canvas`. `client/gui/common/HeatFlowsheet` (moved from `screens/inventory`): draws through the painter, with each unit's insides. `HeatDiagram`: the equipment glyphs.
- `client/gui/screens/inventory/FluidDeviceScreen` (the panel door, the HMI screen, layout C on the kit), `GaugeScreen`, `PipelineToolScreen`, `screens/VesselScreen` (the small dialogs; `VesselScreen.sheet`), `world/item/GaugeSettings` (`Unit.of`), `screens/EquipmentConstructorScreen`, `AssemblyScreen` and `ColumnCalculatorV3Screen` (on the kit, in the client's theme; the calculator without its tray dP field, in the client's temperature unit and basis); `client/gui/common/ProcessUi` (palette and `number()` alias the kit's). Lang: `gui.icheme.fluid.theme.*`, the unit keys' hovers.
- `network/FluidNetwork` (an air cooler's edit keeps the fans' share the client sends).
- The column calculator's diameter and tray drop (choices (a) and (b)): `V3EditorDraft`, `V3ClientPresets`, the bundled `columnpreset/` and `mixturepreset/` files, `network/ColumnV3Network`, `science/material/MaterialPresets`, `MATERIALS.md`. Holland Example 3-2 left the game: `ColumnInputPreset`, `V3EditorCatalog`, `runtime/ProcessSolveServices`, `ColumnCalculatorV3BlockEntity`.
- `tools/heat-gui-renders/` (whole Control pages through the shipped code; `GuiPrototypes`, the other screens' prototypes).

## History

- 2026-10-28: plan written (owner: "Option C"), commit `b6659c07`.
- 2026-10-28: kit core and the Control tab pilot, commit `a779ce89`.
  - `FluidDeviceScreen` lost its walker, `ModeRow`, `clickMode`, the column's `ProcessScroll` and the rebuild on scroll.
  - Gates: `KitLayoutTest` 7, `HeatControlColumnTest` 4 (one wrong expectation fixed: the ratio mode's line names the feed valve in fixed words).
- 2026-10-28: the shared painter, in the painter commit (quick suite 1728, no failures).
  - `HeatFlowsheet` and `AssemblyScreen` draw through `FlowsheetPainter`. `AssemblyScreen` keeps its CRLF line endings.
  - The render tool renders whole Control pages. Gates: `FlowsheetPainterTest` 3; quick suite 1728, no failures.
- 2026-10-28: four Control tab styles tried (owner: "Try a few different styles"; plan section 7), in the styles commit.
  - `Column.Metrics` (`DEFAULT`, `COMPACT`; title-only choices). The render tool writes four sheets in styles A to D.
  - Gates: `KitLayoutTest` 8 (one new, for compact and title-only rows), `HeatControlColumnTest` 4, `FlowsheetPainterTest` 3.
- 2026-10-28: industrial looks for layout C (owner: "C looks better", "real industrial style"; plan section 8), in the skins commit.
  - `kit/Skin` with `PLAIN`, used by `Column.draw` and `ScrollView.drawBar`. The render tool draws four looks: an ISA-101 HMI, classic SCADA, a control panel door, and a dark control room.
  - Gates: `KitLayoutTest` 9 (one new: a skin gets the marks, the bars and the tones), `HeatControlColumnTest` 4, `FlowsheetPainterTest` 3, `HeatDiagramTest` 5.
- 2026-10-28: look 3 refined, the control panel door (owner: "Refine look 3"; plan section 9), in the panel commit.
  - Kit: `Column.Heading`, `Skin.text`, `Skin.heading`; field labels that do not fit go above their box.
  - The render tool's `Panel` look in four doors: RAL 7035 green, RAL 7035 IEC white, RAL 7016 anthracite, brushed stainless. The tool's titles are now the server's (vessel and side).
  - Gates: `KitLayoutTest` 10 (one new: stacked fields and headings), `HeatControlColumnTest` 4, `FlowsheetPainterTest` 3, `HeatDiagramTest` 5, `FluidGuiConsistencyTest` 5.
- 2026-10-28: doors 3a and 3c shipped as the light and dark themes, with layout C, whole texts and fuller drawings (owner: "Both 3a and 3c, configurable as light and dark", "more comprehensive" drawings, "make sure characters are rendered correctly", "this looks a bit weird"; plan section 10), in the themes commit.
  - Kit: `PanelSkin`, `SkinButton`, `SkinField`; `Skin` page hooks. `Column` cuts no text (wraps, stacks buttons, shows the chosen mode's line). The title-only choice of style D is gone.
  - `ClientConfig` (`icheme-client.toml`) and a theme button on the title row. `FluidDeviceScreen`: the door, one HMI screen for the other pages, layout C, Check lines in the column.
  - Drawings: TEMA shell, air cooler bundle with plenum and fans, the heater's sections and flames, the kettle's pool and weir. Readings never overlap.
  - Render tool: the game's bitmap font, both doors, `drawings.png`; the rejected styles and looks are gone from it.
  - Gates: quick suite 1733, no failures; `KitLayoutTest` 11 (one new: no text cut), `HeatControlColumnTest` 5 (one new: layout C's split), `FluidGuiConsistencyTest` 5, `HeatDiagramTest` 5, `FlowsheetPainterTest` 3.
    The heading plate's margins came after the suite; `KitLayoutTest` and `HeatControlColumnTest` ran again, no failures.
- 2026-10-28: function groups, plates and the On and Off switch, in the groups commit (owner: "a On/off switch plus a target duty mode and target temperature mode", "Text plate and function group should be better designed so new users are not overwhelmed", the view time "can be removed", "Never cool below" in the air cooler "is useless", "AC should have duty ratio mode and target temperature mode as well"; plan section 11).
  - Kit: `Column.Group` replaces `Column.Heading`; `Skin.group`, flat plates (white on the light door); `Column.Button.latched`; a choice is a title and a hover.
  - `HeatControlColumn`: Control (Off, On), Target, Set point or By hand, and the actuator's group named by the server. Complete lines take no note. An air cooler has a fan speed target and no low limit.
  - `FluidDeviceScreen`: the switch and target drafts (`heatTarget`), the air cooler's fan speed, no view time. `FluidNetwork`: an air cooler keeps the sent share.
  - Lang: the modes' titles and lines, the heading and "Acting now" left; plates, switch, targets and hovers came.
  - Gates: quick suite 1734, no failures (`KitLayoutTest` 12, one new: a group frames its rows; `HeatControlColumnTest` 5, rewritten for the switch and targets; `HeatDiagramTest` 5).
- 2026-10-28: the meters' decimal point is a 2 by 2 px dot in 2 px of its own (owner: "the point should be slightly larger"), in the point commit. Checked on the renders; drawing only, no gate covers it.
- 2026-10-28: the other screens reviewed with prototypes, the column calculator's tray dP field removed, display keys, the client's units and the theme sign, in the review commit (owner: "Review other GUI based on above rule, prototype first", "remove the tray dP set value from column calculator (outdated)", "these buttons should distinguish themselves from the left ones", "Panel color and unit should be a client-wide switch", "an icon instead of word", "too big, latter one looks better", "add a min between s and h"; plan section 12, [GUI_REVIEW.md](GUI_REVIEW.md)).
  - Kit: `Skin.softKeys`, `softKey`, `iconKey`, `Skin.Icon`; `SkinButton.soft`, `icon`; `Column.UNIT` (a wider unit narrows its box; units stay whole). `FluidGuiLayout`: 40 px display keys, `softKeys`.
  - `ClientConfig` section `units`; `TemperatureUnit` in `DisplayUnit`, Celsius as "°C"; `FlowRateUnit.PER_MINUTE`. The long unit labels and their lang keys are gone.
  - Prototypes: `tools/heat-gui-renders/GuiPrototypes.java` (ten screens).
  - Gates: quick suite 1734, no failures (`FluidGuiConsistencyTest` 5, the footer gate widened to the keys and their strip; `ProcessPresentationTest` 5, its Celsius symbol now "°C"; `KitLayoutTest` 12). The first run failed the new strip check at 300 px (the strip came 2 px from Apply); the 40 px keys fixed it.
- 2026-10-28: the display keys are flat black keys in a tray engraved into the door (owner: the mint on glass "feels slightly out of place"; of three colours rendered, "C looks better"; plan section 12), in the tray commit. The options' sheet left the prototypes tool. Drawing only: judged on the renders; `KitLayoutTest` and `FluidGuiConsistencyTest` ran again.
- 2026-10-28: field labels beside their boxes in two lines, LCD entries and LED readings (owner: "This Arrangement is not good, also need to differentiate editable value fields with non-editable / toggled"; of the options rendered, "B with E"; plan section 13), in the fields commit.
  - Kit: `Column.Reading`, `Skin.meter`; `Column.Field` labels wrap beside the box; `PanelSkin.field` an LCD, `PanelSkin.meter` the LED. The options' sheets left the prototypes tool; its tank pressures and vessel volume are readings.
  - Gates: `KitLayoutTest` 13 (the stacked-label test rewritten for labels beside, one new for readings), `HeatControlColumnTest` 5, `FluidGuiConsistencyTest` 5, `HeatDiagramTest` 5, `FlowsheetPainterTest` 3, `ProcessPresentationTest` 5: no failures. The full quick suite did not run again (GUI only).

- 2026-10-28: the device pages' door column (owner: "Good, proceed"; [GUI_REVIEW.md](GUI_REVIEW.md) order 1; plan section 14), in the device commit.
  - `DeviceColumn` (new) replaces the rail: function groups, widgets above the rows that follow the plant, LED meters for the readings, the status said once. One `ScrollView` for the Control tab and the device pages; `FluidGuiLayout.side`.
  - Kit: `Column.Split`, `Column.Button.soft`, `Skin.split`, `Column.UNIT_STEAL` (a unit wider than the box can spare is the label's last line; kmol/min was cut). A group's entries and meters share one arrangement and one box width (owner: "Try to align them somehow").
  - Lang: plates, fields, the valve's targets and the readings' hovers came; the rail's keys left; the readings' words are shorter.
  - Render tool: the device pages through the shipped column, opened and scrolled to the end, and a pipe page.
  - Gates: quick suite 1741, no failures (`DeviceColumnTest` 5, new; `KitLayoutTest` 14, one new; `HeatDiagramTest` 5, its key list without the rail's keys). The group alignment came after the suite; the GUI gates ran again (`KitLayoutTest` 14 with the label test rewritten, `DeviceColumnTest` 5, `HeatControlColumnTest` 5, `HeatDiagramTest` 5, `FluidGuiConsistencyTest` 5, `FlowsheetPainterTest` 3): no failures.

- 2026-10-28: alignment as a rule for every GUI (owner: "align" on the Set point and Limit boxes, "make alignment a rule for GUI creating", "make a skill when job is done"; plan section 14), in the alignment commit.
  - Kit: every box `Column.BOX` (46 px) at one place, every unit in `Column.UNIT` (23 px) after it; a wider unit is the label's last line. `UNIT_STEAL` and the per-group box width are gone.
  - Gate: `DeviceColumnTest.assertInLine`, run by `DeviceColumnTest` and `HeatControlColumnTest` on every case at every width; `KitLayoutTest` 14 (the wide-unit test now checks the line). The GUI gates ran (`KitLayoutTest` 14, `DeviceColumnTest` 5, `HeatControlColumnTest` 5, `HeatDiagramTest` 5, `FluidGuiConsistencyTest` 5, `FlowsheetPainterTest` 3): no failures.
  - The GUI rules are a project skill, `.claude/skills/gui-creation` (owner: not in `AGENTS.md`).

- 2026-10-28: the small dialogs on the kit (owner: "Yes, go on"; [GUI_REVIEW.md](GUI_REVIEW.md) order 2; plan section 15), in the dialogs commit.
  - `DialogColumns` (new): the gauge's, the pipeline tools' and the datasheet's columns, the footer, the palette's places and the datasheet's glass. `kit/KitDoor` (new), `SkinButton.of`. `GaugeScreen`, `PipelineToolScreen` and `VesselScreen` on the kit; a drum's Datasheet tab draws the same glass. Sides named from the vessel's front; the nozzle size column dropped; the datasheet panel 400 px wide. `GaugeSettings.Unit.of`.
  - Lang: plates, labels and hovers came; the sentences the sizes and the nozzle table replace left.
  - Gates: quick suite 1748, no failures (`DialogColumnsTest` 7, new). The glass's entry marks, the stacked table and the wider panel came after the suite, from the owner's "different lines needs some distinguishing" and a word the new check found split; the GUI gates ran again (`DialogColumnsTest` 8, `KitLayoutTest` 14, `DeviceColumnTest` 5, `HeatControlColumnTest` 5, `HeatDiagramTest` 5, `FluidGuiConsistencyTest` 5, `FlowsheetPainterTest` 3, `UnitTextTest` 1): no failures.

- 2026-10-28: every second entry on the datasheet's glass lies on a faint band (owner: "different lines needs some distinguishing"; of three ways rendered, "B: bands"; plan section 15), in the bands commit. `DialogColumns.BAND`, `GlassLine.entry`; the indent option and the option sheet left. Gates: `DialogColumnsTest` 8 (the wrapped-line test is now the bands test), and the other GUI gates: no failures.

- 2026-10-28: the equipment constructor's panel on the kit (owner: "finish this"; [GUI_REVIEW.md](GUI_REVIEW.md) order 3; plan section 16), commit `709dee11`. `ConstructorColumn` (new), `Column.Setting` (steppers), latched side selectors, the status on the display, the schematic on the glass. Gates: `client.gui.common.*` and `science.equipment.*`, 218 tests, no failures (`ConstructorColumnTest` 5, new).
- 2026-10-28: a footer of no keys returns no slots (`DialogColumns.footer` divided by zero on the constructor's choose page), commit `ccf7c5f1`. Gates: the GUI tests, 59, no failures.
- 2026-10-28: the assembly screen on the kit ([GUI_REVIEW.md](GUI_REVIEW.md) order 4; plan section 17), commit `3d777743`. `AssemblyColumn` and `AssemblyFlowsheet` (new), tabs always shown, every part's hover, no coordinates on the door. Gates: the GUI and assembly tests, 123, no failures (`AssemblyColumnTest` 8, new).
- 2026-10-28: the column calculator on the kit ([GUI_REVIEW.md](GUI_REVIEW.md) order 5; plan section 18), commit `407688f7`. `CalculatorColumn` (new), two door columns in one `KitDoor` view, one status, latched selectors, the solver's lines on the glass. Gates: `client.gui.*`, 113 tests, no failures (`CalculatorColumnTest` 12, new; `KitLayoutTest` 16).
- 2026-10-28: after the three screens, quick suite 1774, no failures. Docs and the `gui-creation` skill, in the docs commit.
- 2026-10-28: every screen at its own whole scale (owner: "This should be scaled to window size"; of A zoom in whole steps, B stretch, C zoom to fill, "A, every screen"), and the review's choices (a) to (e) decided (plan section 19), in the scale commit. `kit/WindowScale` on the seven kit screens. The calculator and its server request need a diameter above 0; presets no longer carry the tray drop; Holland Example 3-2 left the game (owner: "Remove the preset"), its benchmark kept in the solver's tests. Gates: quick suite 1774, no failures (`KitLayoutTest` 17, one new for the scale; the Holland files test removed with its files; `V3ClientPresetsTest`, `V3ColumnInputDraftTest`, `V3EditorCatalogCodecTest` adapted); the fluid GameTests compile.
- 2026-10-28: merged into the heat branch on the owner's word ("merge to the heat branch"), `09e84a81` (first parent the heat tip `eaf9f6e7`, with main 0.16.2). The kit screens took main's changes to the screens they replace: the constructor's Import protocol (equipment-13: the refusal shows on the display until the next edit) and the calculator's rule that a replayed STALE result is not drawn as current; `ProcessSolveServices` keeps main's solve strategy without the Holland dispatch; `tools/INDEX.md` keeps every row. Gates on the merge: `test` 1796, `slowGates` 188, fluid GameTests 36/36 (`guimerge`), no failures.

## Open

- T1 to T3 (plan section 6) await the owner; built as (a).
- At 464 by 254, an exchanger whose lines are incomplete scrolls its right column by 21 to 31 px to its warning (plan section 11). The drawing is small at that width (0.36 of its design size).
- A fired heater's duty target needs the heat engine (`FiredCoil`, `HeatControl`): moved to the heat batch (choice (d), plan section 19). An air cooler's stored low limit stays hidden (plan section 11).
- Every reviewed screen is on the kit. The constructor's connections page scrolls about 82 px at 464 by 254 (plan section 16). The assembly screen's "a in-line pump" (older templates write "a %s"; plan section 17). The calculator: client units for pressure and flow, condenser and reboiler marks on the drawing, a kit text entry for the glass (plan section 18). A crosswise lying drum's sides on the datasheet are a quarter turn from the constructor's (plan section 15). The valve's controller words are English from the server (plan section 14).
- The screens' own scale has had no in-game check (plan section 19).
- `zh_cn.json` (from main) holds 124 keys this batch's screens no longer use, and none of the kit's new keys (they show in English): a translation pass.
- At the merge to `main`: a `CHANGELOG.md` line, the version, and the code guide's GUI chapter.
- No in-game check (owner: enough client testing).
