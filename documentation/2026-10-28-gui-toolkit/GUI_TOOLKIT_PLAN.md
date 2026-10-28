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
