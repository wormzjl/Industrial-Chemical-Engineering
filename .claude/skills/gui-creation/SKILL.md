---
name: gui-creation
description: Rules, workflow and build recipes for every IChemE GUI, from one new key or label to a whole screen, including pages, tabs, dialogs, panel columns, fields, meters, buttons, drawings on the glass, GuiPrototypes renders and option sheets, window scale, and the GUI side of a server action (client/gui/**, FluidDeviceScreen, the column calculator, the equipment constructor, the assembly screen). Use before designing, prototyping, building, fixing or reviewing any of these, and whenever the owner comments on how a screen looks or works ("align", "cut", "too big", "should scale", "looks weird", "cannot click").
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
   the same rows the screen draws. A drawing with hit tests goes in its own Minecraft-free class (`AssemblyBlock`, `HeatFlowsheet`).
4. Gate it (below), render the shipped code again, look at every sheet, then commit.
5. No dev-client check unless the owner asks for one.

The steps for each kind of job are in `references/`. Read the one the job needs:

| Job | Read |
| --- | --- |
| A prototype, an option sheet, a builder, a key, a page, a dialog, a device row, a drawing, the finish | `references/recipes.md` |
| A press that changes something on the server | `references/server-actions.md` |
| A page that does not fit, a screen's scale, a design height | `references/fit-and-scale.md` |

## The kit (`client/gui/common/kit/`)

Builders, tests and the render tool use the Minecraft-free part. `KitDoor`, `KitWidgets`, `GuiCanvas`, `SkinButton`,
`SkinField` and `WindowScale` hold Minecraft classes and belong to the screens.

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
- A loop of fills (a shape drawn dot by dot, row by row or segment by segment) draws inside `Canvas.batch`: one draw
  call in the game, where each fill outside a batch is its own. `DrawCallTest` holds the counts; the render tools'
  `-Dcount` prints each page's draw calls.
- A dialog: title at (10, 10), column from `DialogColumns.TOP`, push buttons from `DialogColumns.footer` (two or more
  share the width, Save the primary one at the right; Done alone stands at the right; a dialog may have none). Tables
  go on the HMI glass, as the datasheet's.
- A screen with tabs (the device screen, the calculator): tabs always shown, the current one latched, a page that is
  not available yet off with its hover saying when. The constructor moves between its pages by keys instead.
- A screen with pages rebuilds its widgets only when its signature changes: what its widgets show and where they stand
  (`FluidDeviceScreen`: page, tabs, selection, button states; `EquipmentConstructorScreen`: slots and button states).

## The panel rules

1. Function groups in frames under flat plates of one or two words. Sentences go to hovers, never on the door.
2. A device that runs has an Off and On switch, then its target. Selector positions are latched. A reader sees the
   switch latched, with nothing active. A latched key whose press has a meaning of its own still acts (the
   constructor's current kind opens its design; owner report 2026-10-28); `SkinButton` draws it latched.
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
9. Every screen takes its own whole scale (owner: "This should be scaled to window size"): `WindowScale.apply(minecraft,
   this)` first in `init()`, `WindowScale.restore(minecraft)` in `removed()` (after `super.removed()`). Design and
   render every screen at 464 by 254. A fluid device page has a design height of 480 (owner: "It should scale"): keep
   it within 338 px, or tell the owner what keeps a bar. Never size a widget from the player's GUI scale. The calls,
   the numbers and what to do with a page that does not fit are in `references/fit-and-scale.md`.

## When the owner comments on a screen

The owner reports what they see. Find the cause in the code before you change the screen.

| The owner says | Look at |
| --- | --- |
| "cut", "cannot read" | Rule 3: wrap, stack, keep units whole. Check a close call on a render: the tests' font is wider. |
| "align", "looks weird" | Alignment, below: boxes and units at one place. |
| "too big", "should fit", "slider", "scale" | `references/fit-and-scale.md`. Scale before a new layout. |
| "cannot click", "does nothing" | Is the key active? A latched key may still act (rule 2). Does `act` take its key? |
| "it says X, but I did Y" | The server path first (`references/server-actions.md`). The constructor's "save a design first" was a press lost on close, not a screen fault. |

Words can differ from the code's: the owner's "templates" in the constructor meant a column design. When a word could
mean two things, ask. When a fix changes what the player sees, or rests on an assumption, render the options.

## Alignment (owner: "make alignment a rule for GUI creating")

- In a column, every entry's and meter's box is `Column.BOX` (46 px) wide at one place in its row, and its unit sits
  after it in one place, `Column.UNIT` (23 px). Boxes and units stand in line down the whole column, whatever unit a
  row holds. Never size a box from its own unit or value.
- A unit wider than its place (kmol/min, m³/min, kPa/m) is the label's last line ("Flow" over "kmol/min").
- A group's labels stand all beside their boxes (two lines at most, words whole) or all above them. Never mix.
- Buttons in a row share its width; where a text does not fit, every button of the row stacks to the full width.
- Shared frame parts come from one layout class (`FluidDeviceScreen.FluidGuiLayout`: `footer`, `softKeys`, `tabs`,
  `control`, `side`), so every page puts its keys, tabs and columns at the same places.
- Gate: `DeviceColumnTest.assertInLine(column, what)` on every case at every width the door can take (see the gates
  below).

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
- at every width the door can take: rows inside the column, no text wider than its row, widgets at least 40 px, plates
  in one line, one widget per key, units whole, `assertInLine`. A door that follows the window sweeps its range in
  steps of 7 (300 to 790 in `HeatControlColumnTest` and `DeviceColumnTest`, 320 to 760 in `AssemblyColumnTest`,
  `MIN_WIDTH` to `MAX_WIDTH` in `CalculatorColumnTest`). A door of fixed width tests that width and that width less
  the bar (`DialogColumnsTest`, `ConstructorColumnTest`);
- what each kind or mode shows, for an editor and for a reader.
- `KitLayoutTest` holds the kit's own rules; `FluidGuiConsistencyTest` the frame layout; `HeatDiagramTest` checks
  that no language key is defined twice.

## Texts

- English lines in `src/main/resources/assets/icheme/lang/en_us.json` under `gui.icheme.*`. Plates and labels are
  short; hints say what a control does and end with "Then press Apply." where Apply sends it.
- Remove the keys a change leaves without a user; check with a search over `src` and `tools` first.

## Renders

From the worktree root, after `./gradlew compileJava` (and `compileTestJava` for `GuiPrototypes`, whose machine pages take `MachineColumnSamples` from the test classes):

```
java -Duser.language=en -Djava.awt.headless=true -cp "build/classes/java/main;build/classes/java/test" tools/heat-gui-renders/GuiPrototypes.java src/main/resources/assets/icheme/lang/en_us.json tmp/gui-prototypes
java -Duser.language=en -Djava.awt.headless=true -cp build/classes/java/main tools/heat-gui-renders/HeatDiagramRender.java src/main/resources/assets/icheme/lang/en_us.json tmp/heat-gui-renders
```

Both draw in the game's bitmap font from the Minecraft resources jar (`build/moddev/artifacts/`), at 464 by 254 GUI
px (the design size), and print each column's height against its view. The fluid device pages are drawn at the size a
window takes for them (`-Dwindow=2560x1440`, 1920x1080 by default; the tool prints the scale). Show the owner the
sheets (in the Claude app, with `SendUserFile`).

## Pitfalls

- In the tools, import `FluidDeviceScreen.FluidGuiLayout`; its qualified name fails to compile without the game's
  classes on the class path.
- Tests measure with `Measure.fixed(6)`; the game font is narrower for i, l, t and k. Check close calls on a render.
- Working copies are CRLF (autocrlf): edit them with the Edit tool or line-based perl (`\r?$`), never `sed -i` or
  `perl -0`. `printf >>` appends an LF line to a CRLF file.
- Perl `s{...}{...}` breaks on an unbalanced brace in the replacement, and `$/` in a pattern is a variable.
