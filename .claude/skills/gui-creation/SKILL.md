---
name: gui-creation
description: Rules and workflow for creating or changing any IChemE GUI screen (client/gui/**, FluidDeviceScreen, the column calculator, dialogs, the equipment constructor, the assembly screen). Use before designing, building, prototyping or reviewing a screen, a panel column, a field, a meter or a button layout, and when the owner comments on how a screen looks ("align", "cut", "too big", "looks weird").
---

# GUI creation (IChemE)

The owner's rules for every screen, from batch `documentation/2026-10-28-gui-toolkit/` (plan sections 10 to 18,
`GUI_REVIEW.md`). The owner judges screens on rendered images, not in the dev client.

## Workflow

1. Read `documentation/2026-10-28-gui-toolkit/README.md` and `GUI_REVIEW.md` for the current state.
2. Prototype first: draw the screen with `tools/heat-gui-renders/GuiPrototypes.java` (both doors, opened and scrolled
   to the end) and show the owner. Where the choice is open, render two to four options side by side and let the
   owner pick; build only the picked one.
3. Build the rows in a builder class with no Minecraft classes (as `HeatControlColumn`, `DeviceColumn`,
   `DialogColumns`, `ConstructorColumn`, `AssemblyColumn`, `CalculatorColumn`), so the gates and the render tool place
   the same rows the screen draws. A drawing with hit tests goes in its own Minecraft-free class (`AssemblyFlowsheet`).
4. Gate it (below), render the shipped code again, look at every sheet, then commit.
5. No dev-client check unless the owner asks for one.

## The kit (`client/gui/common/kit/`, no Minecraft classes)

- `Column.place(rows, x, y, width, measure, Column.Metrics.COMPACT)`. Rows: `Group` (a framed function group under a
  plate), `Line`, `Paragraph`, `Choice` (a mark and a title, an action key), `Field` (an entry), `Reading` (a meter),
  `Buttons` of `Button` (`latched` for a selector position, `soft` for a display key), `Bar`, `Split` (a share bar
  with one legend line a share), `Gap`, `Setting` (an LCD entry with − and + steppers before its box, for values the
  player steps; its box stays in line with the other entries).
- `Skin`: `Skin.PLAIN` and `PanelSkin.LIGHT` / `PanelSkin.DARK` (the client's theme, `ClientConfig.skin()`).
- In the game: `GuiCanvas` (the canvas), `ScrollView` (a viewport with its own bar), `KitWidgets` (keeps widgets at
  their content slots and moves them as the view scrolls: no rebuild on scroll), `SkinButton` (`SkinButton.of` makes a
  column's button), `SkinField`, `KitDoor` (a door's columns: one view, one bar where the window is short, kept
  widgets, drawing and hover; two columns side by side share the view and the bar, `layout(areas, bar, rows, m)`;
  used by the dialogs, the constructor, the assembly screen and the column calculator).
- A dialog: title at (10, 10), column from `DialogColumns.TOP`, push buttons from `DialogColumns.footer` (two share
  the width, Save the primary one at the right; Done stands alone at the right; no keys, no slots). Tables go on the
  HMI glass, as the datasheet's.
- A screen with pages: tabs always shown, the current one latched, a page that is not available yet off with its
  hover saying when. The screen rebuilds its widgets only when the page's signature (page, tabs, selection, button
  states) changes.

## The panel rules

1. Function groups in frames under flat plates of one or two words. Sentences go to hovers, never on the door.
2. A device that runs has an Off and On switch, then its target. Selector positions are latched. A reader sees the
   switch latched, with nothing active.
3. No cut text: wrap, stack buttons, keep units whole. No noise: no timestamps, no raw engine lines on the door, no
   status said twice. The status is said once, on the message display; the engine's own line goes in its hover.
4. The door holds switches, set points and meters; the HMI glass (`Skin.well`) holds tables and drawings.
5. What the player types is an LCD entry (`Column.Field`); what the plant reads is an LED meter (`Column.Reading`).
6. Display keys (a unit, a basis) change what the screens show, not the plant: flat black keys (`soft`) in a tray
   engraved into the door. Push buttons (Close, Apply) stay raised. Only a key that confirms is primary (Apply, a
   dialog's Save, Solve, Build); a key that opens or shows (Datasheet, Open unit, Templates, the calculator's Save
   form) is a plain push button.
7. The theme and the units are the client's (`icheme-client.toml`, `ClientConfig`), one switch for every screen, never
   sent to the server. Controls cross the network in SI. The theme key shows a sign (`Skin.Icon.THEME`), not a word.
8. Every screen draws through the kit and the light or dark theme.

## Alignment (owner: "make alignment a rule for GUI creating")

- In a column, every entry's and meter's box is `Column.BOX` (46 px) wide at one place in its row, and its unit sits
  after it in one place, `Column.UNIT` (23 px). Boxes and units stand in line down the whole column, whatever unit a
  row holds. Never size a box from its own unit or value.
- A unit wider than its place (kmol/min, m³/min, kPa/m) is the label's last line ("Flow" over "kmol/min").
- A group's labels stand all beside their boxes (two lines at most, words whole) or all above them. Never mix.
- Buttons in a row share its width; where a text does not fit, every button of the row stacks to the full width.
- Shared frame parts come from one layout class (`FluidDeviceScreen.FluidGuiLayout`: `footer`, `softKeys`, `tabs`,
  `control`, `side`), so every page puts its keys, tabs and columns at the same places.
- Gate: `DeviceColumnTest.assertInLine(column, what)` on every case at every width from 300 to 790 px.

## Stable widgets

- Rows with widgets (switches, set points, selectors, display keys) come before rows whose texts follow the plant
  (levels, openings, readings). The screen then places widgets at a rebuild and the texts again each frame.
- A widget never moves while the plant's values change (`DeviceColumnTest.aWidgetKeepsItsSlotWhileTheReadingsChange`).
- A warning that follows what the player types (no range, no size) is the column's last row, and the panel is sized
  for the rows with it, so neither the panel nor a widget moves while the player types (`DialogColumnsTest`).
- No cut text in a table either: a long name wraps in its column, its other cells on its first line. Where a word is
  wider than its column, the table stacks (the name across the glass, its other cells on the line under it).
- Entries on the glass are told apart (owner: "different lines needs some distinguishing", picked "B: bands"): every
  second entry's lines lie on a faint band (`DialogColumns.BAND`), a wrapped line on its entry's band.
- Opening a screen replays the last published snapshot; a GUI never asks for a solve (`AGENTS.md`, engine schedule).

## Gates for a column builder

As in `HeatControlColumnTest`, `DeviceColumnTest`, `DialogColumnsTest`, `ConstructorColumnTest`,
`AssemblyColumnTest` and `CalculatorColumnTest`:
- every text the builder names has its English line (`HeatControlColumnTest.Recording`, which fills lines as the
  game does; a line with no arguments, such as "Volume %", stands as written; `new Recording("gui.icheme.")` for keys
  outside `gui.icheme.fluid.`);
- at every width from 300 to 790 px in steps of 7: rows inside the column, no text wider than its row, widgets at
  least 40 px, plates in one line, one widget per key, units whole, `assertInLine`;
- what each kind or mode shows, for an editor and for a reader.
- `KitLayoutTest` holds the kit's own rules; `FluidGuiConsistencyTest` the frame layout; `HeatDiagramTest` checks
  that no language key is defined twice.

## Texts

- English lines in `src/main/resources/assets/icheme/lang/en_us.json` under `gui.icheme.*`. Plates and labels are
  short; hints say what a control does and end with "Then press Apply." where Apply sends it.
- Remove the keys a change leaves without a user; check with a search over `src` and `tools` first.

## Renders

From the worktree root, after `./gradlew compileJava`:

```
java -Duser.language=en -Djava.awt.headless=true -cp build/classes/java/main tools/heat-gui-renders/GuiPrototypes.java src/main/resources/assets/icheme/lang/en_us.json tmp/gui-prototypes
java -Duser.language=en -Djava.awt.headless=true -cp build/classes/java/main tools/heat-gui-renders/HeatDiagramRender.java src/main/resources/assets/icheme/lang/en_us.json tmp/heat-gui-renders
```

Both draw in the game's bitmap font from the Minecraft resources jar (`build/moddev/artifacts/`), at 464 by 254 GUI
px (the automatic scale at 1920 by 1080), and print each column's height against its view. Send the owner the
sheets with `SendUserFile`.

## Pitfalls

- In the tools, import `FluidDeviceScreen.FluidGuiLayout`; its qualified name fails to compile without the game's
  classes on the class path.
- Tests measure with `Measure.fixed(6)`; the game font is narrower for i, l, t and k. Check close calls on a render.
- Some sources are CRLF (`ColumnCalculatorV3Screen`, `ProcessUi`, `DisplayUnit`, `AssemblyScreen`, `GaugeSettings`): edit them with
  the Edit tool or line-based perl, never `sed -i` or `perl -0`.
- Perl `s{...}{...}` breaks on an unbalanced brace in the replacement, and `$/` in a pattern is a variable.
