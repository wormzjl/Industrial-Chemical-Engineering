# 2026-10-28-gui-toolkit

Purpose: owner choice 2026-10-28, option C of the GUI toolkit review: a small GUI toolkit in our own code, first used on the heat Control tab.

Status: In progress since 2026-10-28, on branch `claude/gui-toolkit` (stacked on `claude/heat-gui-redesign-abb1db`). The kit, the Control tab pilot and the shared flowsheet painter are built. The fluid screen draws the light or the dark control panel door, with layout C and fuller drawings. The Control column is framed function groups with short plates: an Off and On switch, then a target. The unit keys are display keys, the theme and the units are the client's, and the other screens are reviewed with prototypes awaiting the owner. Index: [../INDEX.md](../INDEX.md).

## Documents

- [GUI_TOOLKIT_PLAN.md](GUI_TOOLKIT_PLAN.md): the kit, the pilot, the shared flowsheet painter, the gates, the as-built notes (section 5), choices T1 to T3 (section 6), the Control tab styles tried (section 7), the industrial looks (section 8), look 3 refined (section 9), the light and dark panels shipped with layout C, whole texts and fuller drawings (section 10), function groups, plates and the On and Off switch (section 11), display keys, the client's units and the theme sign (section 12).
- [GUI_REVIEW.md](GUI_REVIEW.md): the other screens against the panel rules, their prototypes, the owner's choices (a) to (e) and the order to build them.
- The evaluation that chose it: [../2026-10-28-gui-toolkit-evaluation/GUI_TOOLKIT_REVIEW.md](../2026-10-28-gui-toolkit-evaluation/GUI_TOOLKIT_REVIEW.md).

## Code areas

- `client/gui/common/kit/` (new): `Rect`, `Measure`, `Canvas`, `Palette`, `Format`, `Column`, `ScrollView`, `Skin`, `PanelSkin`, `FlowsheetPainter`; the game adapters `GuiCanvas`, `KitWidgets`, `SkinButton` and `SkinField`.
- `client/ClientConfig` (new, `icheme-client.toml`: the theme and the display units), registered in `IChemE`. `science/display/DisplayUnit` (holds `TemperatureUnit`, from `ProcessUi`).
- `client/gui/common/HeatControlColumn` (new): the Control column as kit rows, and layout C's split. `client/gui/common/HeatFlowsheet` (moved from `screens/inventory`): draws through the painter, with each unit's insides. `HeatDiagram`: the equipment glyphs.
- `client/gui/screens/inventory/FluidDeviceScreen` (the panel door, the HMI screen, layout C on the kit), `AssemblyScreen` (its drawing through the painter); `ColumnCalculatorV3Screen` (no tray dP field; the client's temperature unit); `client/gui/common/ProcessUi` (palette and `number()` alias the kit's). Lang: `gui.icheme.fluid.theme.*`, the unit keys' hovers.
- `network/FluidNetwork` (an air cooler's edit keeps the fans' share the client sends).
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

## Open

- T1 to T3 (plan section 6) await the owner; built as (a).
- At 464 by 254, an exchanger whose lines are incomplete scrolls its right column by 21 to 31 px to its warning (plan section 11). The drawing is small at that width (0.36 of its design size).
- A fired heater's duty target needs the heat engine (`FiredCoil`, `HeatControl`); "duty ratio" was read as the fans' speed; an air cooler's stored low limit stays hidden (plan section 11).
- Themed so far: `FluidDeviceScreen` only. The other screens are reviewed ([GUI_REVIEW.md](GUI_REVIEW.md)); their prototypes await the owner's approval, then they are built in the review's order.
- The owner's choices (a) to (e) in [GUI_REVIEW.md](GUI_REVIEW.md): tray dP in column presets, a diameter of 0, the "duty ratio" reading, the heater's duty target, the gauge's unit.
- At the merge to `main`: a `CHANGELOG.md` line, the version, and the code guide's GUI chapter.
- No in-game check (owner: enough client testing).
