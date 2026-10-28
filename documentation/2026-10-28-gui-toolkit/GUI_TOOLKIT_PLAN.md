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
