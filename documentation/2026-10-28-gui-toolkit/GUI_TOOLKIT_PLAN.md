# GUI toolkit plan (option C), 2026-10-28

The owner chose option C of [GUI_TOOLKIT_REVIEW.md](../2026-10-28-gui-toolkit-evaluation/GUI_TOOLKIT_REVIEW.md): a small toolkit in our own code. Branch `claude/gui-toolkit`, stacked on `claude/heat-gui-redesign-abb1db` (`c434dbb8`), because the pilot screen is the heat Control tab built there.

## 1. The kit (`client/gui/common/kit/`, no Minecraft classes)

- `Rect`; `Measure` (text width and wrapping: the game font in the game, a fixed-width font in tests); `Canvas` (fill, outline, lines, text at a scale, clip). `GuiCanvas` draws on `GuiGraphics`; `Java2dCanvas` (in the tool) on an image.
- `Column`: a top-to-bottom list of elements (line, paragraph, choice, field row, button row, bar, gap). One pass from the width places them all. A placed element keeps its rect, its widget slot (a field or button key) and its click.
- `ScrollView`: a viewport over a column, offset in pixels, with its own scrollbar.
  - The wheel, the bar and clicks act only inside its viewport and only while it is shown.
  - A widget shows only when it lies wholly inside the viewport.
  - A scroll moves the widgets; it does not rebuild the screen.
- `KitWidgets` (the only part with Minecraft classes): makes the EditBoxes and Buttons for the placed slots, keeps them by key and moves them on a scroll.
- Events: each page holds its own regions, and the screen sends mouse events to the shown page's regions only.

## 2. Pilot: the heat Control tab

- `HeatControlColumn` (new, no Minecraft classes) builds the Control column from the side's view, its controls and a text lookup: the side selector, modes, fields, "Acting now" with its bar, the notes.
- `FluidDeviceScreen` draws it through `ScrollView` and `GuiCanvas`. These go: the walker `controlColumn`, `ModeRow`, `clickMode`, the column's `ProcessScroll` and its rebuild on scroll.
- The screen's other pages and the other screens stay as they are; each moves when a task touches it.

## 3. One painter for the flowsheets

- `FlowsheetPainter` over `Canvas` does the design-grid fit, the scaled and fitted labels, the glyph boxes, and the wires with their arrows.
- `HeatFlowsheet` and `AssemblyScreen`'s drawing go through it. Each model keeps its own records (the assembly its trays and port marks; heat its pills, tags and nozzles).
- The PNG tool draws through the same painter on `Java2dCanvas` and renders the whole Control page (drawing and column). Its own copy of the drawing code goes.

## 4. Gates

- `KitLayoutTest`:
  - elements are placed in order, inside the width and apart;
  - paragraph heights come from the measure;
  - the scroll offset clamps;
  - a scrolled-out element or a hidden view takes no click;
  - a widget shows only inside the viewport.
- `HeatControlColumnTest`: for each unit, side and mode, the column's rows and keys; every text has its English line; the fields lie inside the column at 300 to 790 px.
- `HeatDiagramTest`, `AssemblyDiagramTest` and `FluidGuiConsistencyTest` do not change. Then the quick suite and the PNG renders. No client check (owner).

## 5. As built (2026-10-28)

- Row heights: line 12, paragraph 10 per line plus 4, choice 24, field 24, buttons 24, bar 12. Gaps of 2 keep the Control column's old spacing. The wheel moves 30 px per notch, as before (3 rows of 10 px).
- `HeatFlowsheet` moved to `client/gui/common` with no Minecraft classes. Its part hover text moved into `FluidDeviceScreen` (`partHover`). `Measure.height` (the font's 9 px) places the painter's labels.
- `ProcessUi`'s palette and `number()` now alias the kit's (`Palette`, `Format`), so every screen draws in one palette.
- `AssemblyScreen` draws through `FlowsheetPainter`; its look rules and port states stay its own.
- The render tool draws whole Control pages through the shipped code at 464 by 254 px, as shown and scrolled to the end (22 PNGs). It runs with only the mod's classes, which also shows the kit, `HeatControlColumn` and `HeatFlowsheet` hold no Minecraft classes.
- Seen in the renders:
  - at 464 by 254 the column is taller than its view, so the fields, "Acting now" and the notes sit below the fold;
  - the ratio field's label "Ratio to the other feed" is cut in a 167 px column (its hover shows it).

## 6. Choices (built as the first option; each is reversible)

- **T1, where.** (a) Stacked on the heat GUI branch: it merges after the heat batch, or with it. (b) Wait until the heat batch is on `main`.
- **T2, how far.** (a) The Control tab only; other pages and screens move when a task touches them. (b) All of `FluidDeviceScreen` now.
- **T3, the drawings.** (a) One painter, two models. (b) One model for both drawings as well (assembly and heat records merged).

## 7. Control tab styles tried (2026-10-28, owner: "Try a few different styles")

The four styles use the same rows from `HeatControlColumn`. `Column.Metrics` sets the row heights: `DEFAULT` 12/24/24/24/12 (line, choice, field, buttons, bar) and `COMPACT` 11/20/20/20/10. A choice lower than 20 px shows only its title; its line shows on hover. The sheets are in `tmp/heat-gui-renders/styles/`.

- **A, List** (as shipped): radio modes, each with its line, at `DEFAULT` heights.
- **B, Compact**: the modes are buttons, two to a row, and the chosen mode has an accent edge. The heading and the small gaps are removed. One line under the buttons explains the chosen mode. The acting line carries the share ("· 43.0 %"). `COMPACT` heights.
- **C, Split**: the drawing takes 38 % of the width. Two columns at `COMPACT` heights: the side and the modes on the left (105 px), and the settings, what acts and the notes on the right.
- **D, Cards**: the drawing takes 45 % of the width. Each section sits in a `PANEL` panel under an accent header: the mode (title only), "Set points", and what acts now. Gaps of 8 px between the sections.

The column's height against its 128 px view, at 464 by 254:

| Case | A | B | C | D |
|---|---|---|---|---|
| Exchanger, shell side with bypass | 236 | 146 | 111 | 211 |
| Exchanger, feed ratio | 224 | 147 | 111 | 201 |
| Air cooler | 172 | 112 | 72 | 149 |
| Fired heater | 150 | 92 | 71 | 145 |

- A: every word shows. Every unit except the pool scrolls.
- B: the air cooler and the fired heater fit. An exchanger scrolls about 20 px. The buttons cut long mode titles ("Outlet temperatur", "Fire to the set poin"). Shorter button titles or three to a row would fix that.
- C: everything fits, with no scroll. The drawing shrinks (166 px), so its long tags drop. The 105 px column cuts the mode lines and the field labels ("Never cool bel", "Convectio").
- D: the easiest to scan. After A it is the tallest (145 to 211 px), and the drawing loses 10 % of the width.

Owner: "C looks better"; the buttons and colours "look too bare"; "a more industrial aesthetic", "real industrial style".

## 8. Industrial looks for layout C (2026-10-28)

- `kit/Skin` decides how the rows draw: the text tones (the rows name them by the palette's colours), a choice's mark, the bar and the scroll bar. `Skin.PLAIN` draws what the screens drew before, and `Column.draw(c, dy)` and `ScrollView.drawBar(c)` use it.
- The render tool adds a `Look` over `Skin`: the page, the title, the buttons, the fields, the drawing's well and the dividers. `PLAIN` reproduces the sheets of section 7. In every look the flowsheet sits on a dark process screen. The sheets are in `tmp/heat-gui-renders/industrial/`.
- LDLib2 was read for its widget vocabulary only: its ORE and MC sprites (a dark outline with cut corners, a light top-left edge, a dark bottom-right edge, sunken slots, lamps), and its flat themes (one accent, used only for focus, selection and a filled bar).
- The four looks, after real plant screens and panels:
  1. **HMI, ISA-101 high-performance.** Flat grey, with colour only for state. A latched button is dark grey. Entered values are dark blue. A grey analog bar with quarter ticks. A grey title bar.
  2. **SCADA, classic** (the WinCC and InTouch era). 3D grey bevels, a navy title bar, white entry fields and a black process screen. The running mode button is green and pushed in. Navy block progress bar.
  3. **Control panel door.** RAL 7035 grey with screws and an engraved black title plate. Push buttons in IEC 60073 colours: black general, illuminated green for running, blue for confirm. Green pilot lights for the modes. Red LED panel meters for the fields and a green LED bar graph. The flowsheet sits on an HMI screen in a black bezel.
  4. **Control room, dark** (low ambient light). Flat dark grey, with pale blue for what is chosen and amber for warnings. A grey bar with quarter ticks.
- In the game the buttons and fields are vanilla widgets. A chosen look then needs kit widgets that draw through the skin: a button drawn in the look, and an `EditBox` without its border over the look's field.

## 9. Look 3 refined: the control panel door (2026-10-28, owner: "Refine look 3")

- The kit, for every look:
  - `Column.Heading`: a section heading, which the skin draws (`PLAIN`: a muted line; a panel: a legend plate). "What this side holds" is now a heading.
  - `Skin.text`: a skin may engrave the rows' texts.
  - A field whose label does not fit beside its box puts the label on its own line above the box. The box stays in line with the other boxes. This fixes the cut "Ratio to the other feed" and "Never cool below".
- The panel look (the tool's `Panel`):
  - Legend plates for the title and the headings, and engraved text.
  - Push buttons in a black bezel with a chrome ring, lit from behind when latched (the page and the side shown).
  - Pilot lights with a glow for the modes.
  - 7-segment LED panel meters for the fields: right-aligned over unlit segments; an empty limit reads OFF.
  - An LED bar graph whose top tenth has its own colour, as a meter's printed zone.
  - The status on a message display. The flowsheet on an HMI screen with a power LED. A quarter-turn latch on the door.
  - The legends keep their colour; the lamps show state, as on a real panel.
- The four doors (sheets in `tmp/heat-gui-renders/panel/`):
  - 3a: RAL 7035, green lamps, red LED meters.
  - 3b: RAL 7035 with IEC 60073 white for a selection (white is the neutral colour that confirms one), white legend plates, green LED meters.
  - 3c: RAL 7016 anthracite, aluminium legend plates, amber LED meters, a red top zone.
  - 3d: brushed stainless steel, red LED meters, an amber display.

## 10. Light and dark panels, layout C, whole texts, fuller drawings (2026-10-28)

Owner: "Both 3a and 3c, configurable as light and dark mod. Also you need to improve the equipment drawing so they are more comprehensive"; then "make sure characters are rendered correctly" (a button read "Convectio") and "this looks a bit weird" (the heading plate).

- **Themes.** `kit/PanelSkin` is the control panel door: `LIGHT` is door 3a, `DARK` is door 3c (section 9). `kit/Skin` now holds the page hooks: the page, the title, the status, the buttons (`Press`: rest, off, latched, primary; and hover), the fields, the well and the dividers. `Skin.PLAIN` draws what the screens drew before.
- **Setting.** `client/ClientConfig` writes `icheme-client.toml`, `[gui] theme = LIGHT | DARK` (default `LIGHT`). The client loads it; a dedicated server does not. A theme button on the title row switches the theme and saves it.
- **Widgets.** `kit/SkinButton` is a vanilla button drawn by the skin. Inactive and latched, it is lit (the tab or side shown); Apply is the blue confirm button. `kit/SkinField` is an unbordered `EditBox`. At rest the skin draws it as a 7-segment meter. While it has focus, the game draws the text, the cursor and the selection 4 px in. Its inner width and clicks keep the bordered field's margin.
- **FluidDeviceScreen.**
  - The door holds the frame, the title row, the tabs, the status display, the footer and the Control column.
  - Every other page sits in one HMI screen (`pageWell`): the rail, the tables and the game's own widgets, in the dark palette.
  - Layout C: `FluidGuiLayout.control` gives two columns from about 455 px wide (left 120 to 170 px, right at least 120 px), else one column. `HeatControlColumn.columns` splits the rows: the selector and modes on the left, the rest on the right. Both columns scroll together.
  - Check lines moved from the drawing into the column, under the lines' note. The drawing's legend gets the full width.
- **No text is cut.** In `Column`, lines, headings, choice titles and stacked field labels wrap. A button row whose texts do not fit side by side stacks its buttons. Only the chosen mode shows its line; the other modes show theirs on hover (a radio list). A heading's row has room for a plate with 3 to 4 px margins. In `FlowsheetPainter`, a reading never covers another text (`tag`, `take`); it is skipped, and its pill still says it.
- **Drawings** (`HeatDiagram` glyphs, drawn in `HeatFlowsheet`):
  - shell-and-tube: a TEMA U-tube shell with its tubesheet, U-bundle, segmental baffles and dished rear head; a bonnet with its pass partition; two saddles.
  - air cooler: a finned bundle between plug headers, the plenum, and fans with ring, blades, shaft and motor; air arrows.
  - vertical cylindrical heater: a stack with breeching and damper, convection rows (the shock row bare), the transition cone, a firebox with refractory, the wall coil and three flames, burner tiles.
  - kettle: the cone from the channel, the U-bundle in the pool, the weir, both liquid levels and bubbles.
  - Labels and readings sit on plates over the insides.
- **Render tool.** It now draws in the game's bitmap font, read from the Minecraft resources jar, so widths and cuts are the game's. It renders layout C in both doors, and a `drawings.png` sheet at the design scale.
- **Measured** (464 by 254 GUI px, the game font, the 128 px view; the tallest column counts):
  - exchanger with no lines: 103 px;
  - exchangers whose lines need a note: 147 to 167 px; they scroll, and the note and Check lines sit below the fold;
  - air cooler: 93 px (a reader 127 px);
  - fired heater: 98 px;
  - kettle tubes: 103 px; its pool: 74 px.

## 11. Function groups, plates, the On and Off switch (2026-10-28)

Owner: "The logic should be something like a On/off switch plus a target duty mode and target temperature mode"; "Text plate and function group should be better designed so new users are not overwhelmed"; "that "at xxx s" message can be removed from gui"; "Never cool below" in the air cooler "is useless, remove it, you should never cool below target"; "AC should have duty ratio mode and target temperature mode as well".

- **Expected.** On a real control panel door, the devices of one function sit together inside an engraved line, and a short legend plate names the group. Each device carries a one- or two-word legend. A new operator reads the groups in order: what to switch, what to hold, at what value, what acts. Explanations belong in the manual; here, in hovers.
- **What the column did.** One heading plate ("What this side holds") over a radio list of four modes. The chosen mode had a sentence under it. Then came the fields, an "Acting now: ..." line, the bar, a sentence on the effort, a note on the lines, and Check lines. On the light door the plates were black, like the buttons, so they read as buttons.
- **As built.**
  - Kit: `Column.Group(title, hint, rows)` replaces `Column.Heading`. Its rows sit `INSET` (4 px) inside a frame, under a plate `Column.plate(lines)` high, with `GROUP_GAP` (4 px) below. A column ends at its last frame.
  - `Skin.group` draws the frame and the plate. `PanelSkin.group` draws an engraved line broken by a flat plate (a rim, no bevel). The light door's plates are white with black legends; the dark door keeps its aluminium plates.
  - `Column.Button.latched` marks the position a switch or a selector stands in. `Column.Choice` is a title with a hover; a choice no longer shows a line. `Metrics` lost its choice height. `BOX` is 54 px (six 7-segment digits) and `FIELD_TAIL` 76 px.
  - `HeatControlColumn.columns(input, texts)` builds layout C directly. The left column holds the side selector, then **Control** (Off and On), then **Target** while On: outlet temperature, duty, an exchanger's feed ratio, an air cooler's fan speed. The right column holds **Set point** (while On) or **By hand** (while Off: an exchanger's bypass), then the actuator's group.
  - An air cooler (read as: "duty ratio" is the fans' share of full speed): Off stops its fans, leaving natural draught. On holds the outlet temperature, a duty, or a fixed fan speed in % (no controller: mode `OFF` with the fans' share). Run and Stop are gone. The server took an air cooler's share from its power (full or still); `FluidNetwork` now keeps the share the client sends. The fans' law is continuous in the share (`AirCooled.Air.films`: draught = natural + (1 − natural) × fans). A side loaded with mode `OFF` and a share above 0 reads On at its fan speed, so the built default (fans at full) shows as On, 100 %.
  - The actuator's plate is named by the server: Internal bypass, Bypass valve, Feed valve, Fans, Louvers and fans, Burners. It holds the bar, a short effort line, a note on incomplete lines and Check lines. Each plate's hover says what that part is.
  - Complete lines take no note: the plate already says "Bypass valve" or "Feed valve". Check lines shows only while a target needs lines.
  - The switch maps onto the engine's modes; the engine is unchanged. A heater's Off and On are its burners; an air cooler's, its fans. Elsewhere Off is mode `OFF` (no controller, the manual share holds), and On is the target last chosen (`FluidDeviceScreen.heatTarget`; the outlet temperature for a side loaded Off). Every press is a draft until Apply.
  - An air cooler has no low limit field. A low limit an older world stored stays as it was and is resent on Apply.
  - The status band shows only messages; the view's time is gone.
- **Measured** (464 by 254 GUI px, the game font, the 128 px view; the tallest column): exchanger Off 105 px, held with complete lines 125, held with incomplete lines 149 to 159 (they scroll to the warning); feed ratio 136; air cooler at a fan speed 97 (a reader held at its outlet 123); fired heater 85; kettle tubes 110; its pool 78.
- **Open.**
  - A fired heater's duty target needs the heat engine (`FiredCoil`, `HeatControl`); the column lists none for it.
  - "Duty ratio" read as the fans' speed (the Fan speed target). If it meant something else, the target list is one line in `HeatControlColumn.targets`.
  - A low limit already stored on an air cooler stays hidden: keep it (as built), or clear it on the next Apply.
  - No in-game check (owner: enough client testing).

## 12. Display keys, the client's units, the theme sign (2026-10-28)

Owner: "these buttons should distinguish themselves from the left ones" (the footer's Mole, °C, bar and /s); "Panel color and unit should be a client-wide switch, so it persists between GUI and does not affect server side"; "Make the panel color switch an icon instead of word"; the wide keys are "too big, latter one looks better" (the prototype's 40 px keys); "add a min between s and h".

- **Expected.** On a real panel, a push button acts on the plant (Close, Apply). A unit or a basis acts on nothing: it changes what a display shows. It belongs with the displays, and it is the operator's own setting, the same on every screen they open. The plant never learns it.
- **What the code did.** The four unit keys were black push buttons like Close and Apply. The fluid screen kept its units in static fields (the game session only); the column calculator reset °C or K on every open. The theme was already the client's, in `icheme-client.toml`, but its key was a word ("Light panel", 70 px).
- **As built.**
  - Kit: `Skin.softKeys` and `Skin.softKey` (`PanelSkin`: flat black keys in a thin rim, no chrome ring, in a tray engraved into the door. First they stood on the message display's glass in its mint, which "feels slightly out of place"; of three colours rendered, the meters' LED colour, white on black glass and flat black keys in a tray, the owner chose the last: "C looks better"); `SkinButton.soft`. `FluidGuiLayout.softKeys(width, height)` is the strip, 4 px round the footer's slots 2 to 5. Those are display keys of a fixed `FluidGuiLayout.KEY` (40 px), each showing its value alone (Mole, °C, kPa, /min; the long labels and their lang keys are gone, the hovers say what each switches); Close and Apply take what is left, up to 50 and 60 px. The gate (`FluidGuiConsistencyTest`): from 300 to 790 px wide, the keys are 40 px, the strip clears Apply by 4 px and stays inside the door's edge, under the divider.
  - Units: flows per minute between per second and per hour (`FlowRateUnit.PER_MINUTE`, the key steps s, min, h). Celsius reads "°C": the game showed a bare "C" in every temperature, where the renders already wrote "°C".
  - `Skin.Icon.THEME`, `Skin.iconKey`, `SkinButton.icon`: the theme key is an 18 px square with a contrast sign (a ring, its left half filled). Its hover names the panel shown; its message stays for narration.
  - `ClientConfig` (`icheme-client.toml`, section `units`): `temperature`, `pressure`, `flow_time` and `basis`, beside `gui.theme`. Each key saves the file. `FluidDeviceScreen` and `ColumnCalculatorV3Screen` read them on open. `TemperatureUnit` moved from `ProcessUi` into `DisplayUnit`, beside the pressure and flow units, so the config names no screen class (a dedicated server builds the spec, then never loads it).
  - Controls cross the network in SI (`DisplayUnit`, `NumericDraft.si`) and the column's inputs in kelvin, so no unit reaches the server.
- **Open.**
  - The phase ratio's basis (the rail's own key) stays a session setting; it goes with the rail (`GUI_REVIEW.md`, order 1).
  - The column calculator shows pressures in bar and flows in kmol/h whatever the config says; it takes the units when it moves onto the kit.
  - A gauge's unit is the gauge's own, a fact the item saves (`GaugeSettings.unit`): it is the dial's scale in the world, the same for every player. Option 1: keep it (as built). Option 2: the dial follows each player's config, and the saved unit is only a default.

## 13. Labels beside their boxes; entries against readings (2026-10-28)

Owner, on the Set point group: "This Arrangement is not good, also need to differentiate editable value fields with non-editable / toggled". Of the options rendered: "B with E".

- **Expected.** On a panel door every legend of one group stands in the same place, left of its instrument. A set point the operator dials in and a value the plant measures are different devices: a set-point display (here a reflective LCD) and a lit indicator (the LED meter). An operator tells them apart at a glance, without colour alone.
- **What the code did.** A label that did not fit its 40 px share went above its box, so "Feed ratio" stood above while "Low limit" stood beside. Every box was the same LED meter, whether the player typed it or the plant read it; an empty optional entry showed "OFF" like a reading.
- **Options rendered.** Labels: A above every box, B beside in two lines, C all above in a group. Fields: E an LCD for entries and the LED for readings, F the LED in a light entry frame, G green set-point LEDs.
- **As built (B and E).**
  - `Column.Field`: a label wider than its share wraps beside the box in two lines, its words whole, in the same 24 px row; a label needing three lines, or holding a word wider than the share, goes above the box. Gate: `KitLayoutTest.aFieldLabelWrapsBesideItsBoxInTwoLinesElseGoesAboveIt`.
  - `Column.Reading` (new): an indicating meter laid out as a field, with no widget; its hover over its box too. `Skin.meter` draws it. Gate: `KitLayoutTest.aReadingIsPlacedAsAFieldAndDrawnAsAMeter`.
  - `PanelSkin.field` draws entries as an LCD (grey-green glass, near-black segments, the same on both doors); `PanelSkin.meter` keeps the door's LED meter for readings; `fieldText` is the LCD's ink, so typed text is dark on the glass.
  - The Control tab's feed-ratio column is 11 px shorter (136 to 125 px): Feed ratio no longer stacks.
- **Open.**
  - While a value is edited, the game draws the cursor bar inside the text in its own light grey, faint on the LCD; the cursor at the end ("_") takes the LCD's ink. Not checked in game.
  - The door's readings so far are in the prototypes only (a tank's pressures, a vessel's volume); the Control tab has none.

## 14. The device pages' door column (2026-10-28, GUI_REVIEW.md order 1)

Owner, on the review's prototypes and order: "Good, proceed".

- **Expected.** A device's page is a panel door: what the operator switches and sets, and what the plant reads, sit on the door in function groups; the tables sit on the HMI glass. A widget never moves while the plant's values change, so a click lands where the operator aimed.
- **What the code did.** The rail drew its own fields, buttons and value lines at fixed steps (42 px a field, 24 px a value) in the vanilla look, on the HMI glass, with its own scroll bar, a rebuild on every scroll, an "Operating conditions" heading and the whole status again under the values. A paired heat side had an "Other side" button; a reader saw no switch.
- **As built.**
  - `client/gui/common/DeviceColumn` (new, no Minecraft classes) builds the kit rows. The groups with widgets come first: a heat side's side selector (`HeatControlColumn.sideSelector`, shared with the Control tab); Control (Off, On) where a generator or a built mover switches, shown latched to a reader too; Set point and Limit (pump, compressor), Set point (generator, void, valve), a flow valve's Target (Mass flow, Volume flow), Size (a pipe's bore); a filter's Solids (Recover); Phases (the basis as a display key, then the split, one share a line). The groups whose texts follow the plant come after them: a tank's Level (bar and line), a valve's Valve (bar, "% open", the controller's words), Readings (LED meters).
  - `FluidDeviceScreen`: the column sits on the door from x 10 to `FluidGuiLayout.side` (3/8 of the width, 120 to 190 px), its bar right of it, the tables' HMI screen from side + 15. One `ScrollView` serves the Control tab and the device pages. Widgets are placed at a rebuild and moved as the view scrolls; the texts are placed again each frame, which is safe because no widget lies below a changing row (gate). The rail, its scroll, `readingsTop`, the phase-basis button's per-frame layout and the status block are gone; the status is said once, on the message display (a pipe's reversed interval first), the engine's line on hover.
  - Readings: one value a meter. A level gauge shows its share of span (height on hover); a flowmeter its mass flow (volume on hover); a pipe one speed and one drop, or the lowest and the highest; the power state only where no switch shows it (a heat side's fans and burners). The throughput stays: the status says "Flowing normally." with no number, so the prototypes' "the status already says it" did not hold.
  - Kit: `Column.Split` (the phase bar and its legend), `Column.Button.soft` (a display key in a column), `Skin.split`. A unit wider than its place after the box (kmol/min, kPa/m) is the label's last line ("Flow" over "kmol/min"). Before, a wide unit narrowed its box: kmol/min was cut and its value fell back to plain text.
  - Alignment, a rule for every GUI (owner, on the renders: "Try to align them somehow", then "align" on the Set point and Limit boxes, then "make alignment a rule for GUI creating"). Every entry's and meter's box is `Column.BOX` (46 px, five 7-segment digits) at one place in its row, its unit after it in `Column.UNIT` (23 px: °C, bar, kPa, m/s, kg/s, m³/h), so boxes and units stand in line down a column whatever the rows hold. A group's labels stand all beside their boxes or all above them. Before, each row chose alone: Temperature stood above its meter while its neighbours stood beside, and a unit wider than 15 px narrowed its own box, so the Set point's m³/h box was narrower than the Limit's bar box. The rule is kept in the `gui-creation` skill and gated (`DeviceColumnTest.assertInLine`, run on every device page and every Control tab case at every width).
  - Lang: plates `device_group.*`, fields `device_field.*`, `valve_target.*`, `valve_open`, `level_line`, `reading.*` hovers; the readings keep their keys with shorter words (Speed, Pressure drop, Flow). The rail's keys are gone (`power_*`, `fans_*`, `burners_*`, `partner_side`, `operating`, `pipe_status`, `*_input`, `fill`, `volume`, `controller`, `valve_opening`, `power_state`, the phase-ratio headings).
  - Gates: `DeviceColumnTest` (5: every text has its English line; the column fits at every width from 300 to 790 px with no cut text or unit; a widget keeps its slot while the readings change; each kind's set points and none for a reader; the switch, the valve's target, the recovery and the basis key). `KitLayoutTest` 14 (the label test rewritten: a group's labels all beside or all above; one new: every box and unit stands in line, a wide unit goes under its label). `HeatControlColumnTest` checks the alignment too.
- **Open.**
  - At 464 by 254 the column is 273 to 347 px in a 128 px view: it scrolls, as the rail did.
  - The valve's controller words ("Regulating", "Fully open / target limited") are English from the server (`FluidNetwork.valve`).

## 15. The small dialogs (2026-10-28, GUI_REVIEW.md order 2)

Owner, on the next step: "Yes, go on".

- **Expected.** A dialog is a small panel door under the same rules: function groups under short plates, selector positions latched, sentences in hovers, LCD entries and LED meters with their boxes and units in line, the client's theme, Cancel and Save (or Done) as push buttons.
- **What the code did.** The gauge, the pipeline tools and the vessel datasheet drew vanilla widgets on a flat panel. The gauge stepped its unit with one button and named its basis "Atmospheric-relative" or "Absolute pressure". The pipeline tool marked the edited paint with "▶" and wrote three note lines under the palette. The datasheet was a list of sentences as wide as its longest line, its nozzles facing world compass names ("faces north").
- **As built.**
  - `client/gui/common/DialogColumns` (new, no Minecraft classes): the three columns, the footer's slots, the palette's places, and the datasheet's glass (its lines and their drawing).
    - Gauge: Range (Minimum, Maximum, the kind's units as a latched selector: Pa, kPa, bar, MPa, or °C, K); a pressure gauge's Reading (Gauge, Absolute). The unit stays saved on the gauge (choice (e) open).
    - Pipeline tools: Size (Bore, m); the laying tool's Paint (Primary or Secondary latched, the palette in the group's gap, Custom as RRGGBB, Bare steel latched while bare). The notes went to the group's hover.
    - Datasheet: on the door, Size (a drum's or a column's Diameter and Length as LCD entries, as chosen on the constructor; Overall, Top, Volume and Area as LED meters; a unit's Overall and Top) and a moving unit's Moving parts (Still, Running). On the glass: the blocked-cells warning first; the kind, the design's lines, the support, the cells and the controller, wrapped; a blank line; the nozzle table (Nozzle, Port, Side), each role wrapped in its column. Where a role's word is wider than its column, the table stacks: each role across the glass, its port and side on the line under it. The panel is 400 px wide where the window has room (the prototype's 340 left the role column 45 px, narrower than "Regeneration"), so the three columns are the norm.
    - The warning line (no range, no size) comes after the widgets. Each panel is sized for it, so neither the panel nor a widget moves while the player types.
  - `kit/KitDoor` (new): a dialog's column in a view that scrolls where the window is short (narrowed for its bar), its widgets kept at their slots. `SkinButton.of` makes a column's button.
  - `GaugeScreen`, `PipelineToolScreen` and `VesselScreen` draw through the kit in the client's theme. `VesselScreen.sheet` replaces `datasheet`; a drum's Datasheet tab on `FluidDeviceScreen` draws the same glass, its sizes first.
  - Sides: Front, Back, Left, Right, Top and Bottom, the constructor's words, seen from the vessel's front (`EquipmentShape.front()`): a standing vessel's liquid outlet side, a lying drum's front head. A crosswise lying drum's front head is the constructor's right, so its sides are a quarter turn from the constructor's; the shape does not keep the constructor's view.
  - The prototype's nozzle Size column was dropped: a nozzle takes its pipe's size (owner decision 2026-10-21). The Port column shows the phase it draws first.
  - `GaugeSettings.Unit.measures` and `Unit.of` replace `Unit.next` and the record's own kind check.
  - Lang: plates, labels and hovers came (`gauge.range`, `gauge.reading`, `pipeline_tool.size`, `.bore`, `.paint`, `vessel.size.*`, `vessel.motion`, `vessel.nozzles.*`). The sentences the sizes and the table replace left (`vessel.diameter`, `.tangent_length`, `.length`, `.top`, `.volume`, `.area`, `.unit_length`, `.nozzle`, `.facing.*`; `gauge.dial_only`; `pipeline_tool.diameter`, `.range`, `.paint.named`, `.paint.unpainted`, `.paint.*.parts`). Ports and the motion switch's words are short table words.
  - Gates: `DialogColumnsTest` (7): every text has its English line; each column fits its door at full width and narrowed for its bar, boxes in line; each mode's keys and latched positions; the warning moves no widget; the palette fills its gap; the glass cuts no name; the footer. `HeatControlColumnTest.Recording` takes a key prefix.
  - Render tool: `GuiPrototypes` draws the gauge, a thermometer with its warning, the laying tool, and two datasheets (a drum; an air cooler with moving parts and a blocked-cells warning) through the shipped builders.
- **Open.**
  - Choice (e), the gauge's unit. A crosswise lying drum's sides (above).
  - Owner, on the glass: "different lines needs some distinguishing" (a wrapped line read as an entry of its own). Each glass line now carries its entry and whether it continues one (`GlassLine.entry`, `wrapped`; `glass(..., indent, ...)` never splits a word). Three ways are rendered for the owner (`tmp/gui-prototypes/glass-lines.png`): A, an entry's wrapped lines 8 px in; B, every second entry on a faint band; C, a faint rule between entries. Shipped as none until the owner picks.
