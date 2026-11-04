# Build recipes

Read the recipe for the job. Each names the code that did it last: open that code and copy its shape. The rules are
in `../SKILL.md`. A press that reaches the server is in `server-actions.md`. A page that does not fit is in
`fit-and-scale.md`.

1. A prototype or an option sheet
2. A builder
3. A key on the door
4. A page on a paged screen
5. A dialog
6. A row on a device page
7. A drawing on the glass
8. Finish

## 1. A prototype or an option sheet

`tools/heat-gui-renders/GuiPrototypes.java` holds one `Prototype` per sheet: its file name, its size in GUI px, a body
that draws on a `Door`, and `scrolls` (true also draws the column scrolled to its end). Every sheet shows the light
and the dark door side by side.

```java
static Prototype widget() {
    var tallest = DialogColumns.widget(new DialogColumns.Widget(true, false), DIALOG);
    return new Prototype("widget", 246, dialogHeight(tallest, 246), (d, s) -> {
        d.skin.title(d.c, 10, 10, "Widget");
        d.column(DialogColumns.widget(new DialogColumns.Widget(true, true), DIALOG), 10, DialogColumns.TOP, d.w - 20, Map.of("bore", "0.150"));
        dialogFooter(d, "Cancel", "Save");
    });
}
```

- Add the method to `prototypes()`.
- Build a prototype's inputs inside its method, never in a static field. The language lines load in `main`, so a
  static input throws at class init.
- `DIALOG` reads keys under `gui.icheme.`, `TEXTS` under `gui.icheme.fluid.`. Add the lines to `en_us.json` before you
  render: a key with no line draws as `?` and the key.
- Sizes: a dialog at its own size (`dialogHeight`), the constructor and the assembly at 464 by 254, the calculator at
  464 by 254, 380 by 254 and 533 by 280, a device page through `devicePage` (`fit-and-scale.md`).
- Before a builder exists, make rows with `Door.group`, `Door.switchRow`, `Door.field`, `Door.reading` and `Door.rows`.
  Once the owner picks, the rows move to the builder and the prototype calls the builder. A sheet drawn from the
  shipped builder cannot differ from the game.
- Option sheet: two to four options, one `Prototype` each (`widget-a`, `widget-b`). Show them to the owner, recommend
  one, and let the owner pick (in the Claude app, with `SendUserFile` and `AskUserQuestion`). After the pick, delete the options from the tool and write one line in the
  History of `tools/heat-gui-renders/README.md`: what the sheet drew and which the owner picked.
- The tools hold their own copies of the press logic, one per door. Search both for `Press.CURRENT`. When the kit
  changes how a widget draws, change every copy (the latched key of 2026-10-28), or the sheets show the owner
  something the game does not.

## 2. A builder

A class in `client/gui/common/` with no Minecraft classes. It may use `science.*`, `java.*`, `@Nullable` and the kit,
except `KitDoor`, `KitWidgets`, `GuiCanvas`, `SkinButton`, `SkinField` and `WindowScale`, which hold Minecraft classes.
The tools run with only the mod's classes, so a Minecraft import fails the render.

- `KEY`: the keys' prefix under `gui.icheme.` (`ConstructorColumn.KEY`).
- One input record per page, holding what the page shows: numbers already formatted as strings, states as booleans,
  words as lists. The screen makes the record from its draft; the tests and the tool make it from illustrative data.
- `public static List<Column.Row> rows(Input in, HeatControlColumn.Texts t)`, or a `Page` record where the page has a
  footer (`ConstructorColumn.Page`: rows, footer, whether it shows the message display).
- Door sizes as constants, slots as pure functions of the height (`PANEL`, `WIDTH`, `area`, `footer`, `display`), so
  the screen, the tests and the tool place the same frame.
- Texts: `t.get(KEY + "name", args)`. The screen passes its `tr` (`EquipmentConstructorScreen::tr`).
- Small helpers keep a page readable: `position`, `push`, `step`, `shown` in `ConstructorColumn`.

Copy the gates of `DialogColumnsTest` for a few rows, or of `ConstructorColumnTest` for many pages.

## 3. A key on the door

| Key | Written as | Reads as |
| --- | --- | --- |
| Push button | `new Column.Button(key, text, true, hint)` | raised, acts |
| Selector position | `new Column.Button(key, text, !current, hint, current)` | latched where it stands, else a key that moves it there |
| Latched key that acts | `new Column.Button(key, text, true, hint, current)` | latched, and a press means something (the constructor's current kind) |
| Off for now | `new Column.Button(key, text, false, hint)` | off; the hint says when it comes on |
| Display key | `new Column.Button(key, text, true, hint, false, true)` | a flat `soft` key on a tray |
| Primary | `SkinButton.of(...).primary(true)` in the screen | only a key that confirms |

- Name keys `what` or `what:position` (`kind:column`, `template:3`). A setting's steppers are its key plus `-` and `+`,
  and `act` tests those ends first, so no other key ends in either.
- Two widgets with one key in a door's columns throw (`Column.slots`).
- `act(String key)` splits at the colon and switches on `what`, as `EquipmentConstructorScreen.act` does.

## 4. A page on a paged screen

Worked example: the constructor's Templates page (`545c827a`).

1. Builder: an input record (`Template`) and a page method (`templates`). Say the empty case with a line
   (`templates.none`) and a failure with a WARN line (`templates.unreadable`).
2. Screen: add the page to its `Page` enum, a case to `panel()`, and the keys to `act`.
3. Read what the page lists when it opens, not each frame: `templates = ColumnTemplates.load(minecraft); show(Page.TEMPLATES);`.
4. Give the page its way back. `init()` lists the pages with a key beside the title. The constructor's `back` always
   goes to the design page and its hint says so, so a page that returns elsewhere needs a key of its own.
   `renderBackground` lists the pages with a glass or a schematic.
5. Fix the page's frame before the prototype: `theFooterTheTitleKeyAndTheDisplayFit` holds that a page with no footer
   shows no message display, and any other page has two or three footer keys, Build last. A new title key joins that
   test's list.
6. `show(next)` sets the page and calls `rebuildWidgets()`. An edit calls `refresh()`, which rebuilds only when
   `signature(panel())` changes. Every slot and button state goes in the signature by itself. A widget state held
   outside a `Column.Button` does not: add it to `signature`, or the widget goes stale.
7. On a page change call `door.reset()`, as `ColumnCalculatorV3Screen.changePage` does; `ScrollView.layout` only clamps
   the offset, so a page can open scrolled. The constructor's `show` does not call it yet.
8. Text the player wrote can have any length. Cut it on its key (`m.cut(name, ConstructorColumn.WIDTH - 12)`) and give
   it whole in the hover. The mod's own texts never cut (rule 3).
9. Lang lines. Tests: add cases to `cases(t)`, including the empty and the failed one, so the fit gate
   (`eachPageFitsTheDoorWithItsBoxesInLine`) and the English-line check cover the page. Add a test of what the page
   shows. Add the keys the screen reads itself to the list in `everyTextTheConstructorNamesHasItsEnglishLine`: `cases`
   cannot see them.
10. Prototype and render; compare the printed column height with its view.

## 5. A dialog

Worked example: `GaugeScreen` with `DialogColumns.gauge`.

1. Rows: a record and a method in `DialogColumns`, the warning line last (`valid` false shows it). `tallest` must hold
   the longest line that row can show, or a longer one brings a bar.
2. `init()`, in this order:

   ```java
   WindowScale.apply(minecraft, this);
   var m = GuiCanvas.measure(font);
   var tallest = rows(false); // with its warning line, so the panel keeps its size while the player types
   int column = Column.place(tallest, 10, DialogColumns.TOP, WIDTH - 20, m, Column.Metrics.COMPACT).bounds().h();
   imageHeight = Math.min(DialogColumns.TOP + column + DialogColumns.FOOTER, height - 8);
   super.init();
   door.layout(new Rect(10, DialogColumns.TOP, WIDTH - 20, imageHeight - DialogColumns.TOP - DialogColumns.FOOTER), tallest, m);
   door.place(rows(true), m);
   door.keep(this::box, (spec, slot) -> addRenderableWidget(SkinButton.of(spec, slot, this::act, GaugeScreen::skin)));
   // the footer's push buttons from DialogColumns.footer(WIDTH, imageHeight, 2)
   door.place(leftPos, topPos);
   ```

3. `removed()`: `super.removed()`, then `WindowScale.restore(minecraft)`.
4. Each frame place the rows again for their texts, draw the door, take its hover, and draw it with `KitDoor.hint`.
   Pass the wheel, press, drag and release to `door`.
5. A dialog has no `signature`. A key that changes a widget calls `rebuildWidgets()` itself (`switchDisplay`).
6. Opening it: a menu screen registers in `IChemEClient.registerScreens` (`ModMenus`); a screen with no menu opens with
   `mc.setScreen` (`EquipmentClient`). `GaugeScreen` and `PipelineToolScreen` edit a held item, whose menu has its own
   trap: read the held-item route in `server-actions.md` before you copy them.
7. Gates: `DialogColumnsTest`. Prototype: `dialogHeight`, `dialogFooter` (it marks a primary key only among exactly
   two).

## 6. A row on a device page

- `DeviceColumn.rows(Input in, Texts t)` branches on `in.kind()`; keys sit under `gui.icheme.fluid.`.
- `DeviceColumn.Input` is a long positional record, built in `FluidDeviceScreen`, `DeviceColumnTest` and
  `GuiPrototypes` (six times). A new field changes all of them; search first.
- Rows with widgets come first, rows that follow the plant after them.
- A page stays within 338 px (`fit-and-scale.md`). Each new line costs 11 px, each entry or button row 20.
- `FluidDeviceScreen` and `ColumnCalculatorV3Screen` are written compactly, without spaces: match them there.
- Gates: `DeviceColumnTest`, `FluidGuiConsistencyTest`. Prototype: `devicePage`, drawn with `device`.
- A heat unit's Control page is `HeatControlColumn` with `HeatFlowsheet`, rendered by `HeatDiagramRender`.

## 7. A drawing on the glass

- A Minecraft-free class drawing on the kit's `Canvas`: `GuiCanvas` in the game, `Java2dCanvas` in the tool.
- Sizes and hit tests are pure methods of that class (`AssemblyBlock`, `HeatFlowsheet`), so a test can click it.
- Draw inside `skin.well(canvas, rect)`, which returns the box inside the glass. The glass keeps the screens' dark
  `Palette` in both themes.
- Gates: `AssemblyBlockTest`, `HeatDiagramTest`.

## 8. Finish

- While you work, run only the tests of the screen you changed:

  ```bash
  ./gradlew test --tests 'com.wormzjl.icheme.client.gui.*'
  ```

  When a payload changed, add its codec test: `--tests '*EquipmentNetworkCodecTest' --tests '*FluidPacketCodecTest'`.
- A new rule from the owner goes in `../SKILL.md`, not in `AGENTS.md`.
- Add one History line to `tools/heat-gui-renders/README.md` for new prototypes.
- Follow `AGENTS.md` for the batch folder, `documentation/INDEX.md`, the code guide (`client-gui.md`) and the changelog.
- New lang keys go in `en_us.json` only. The other lang files catch up in one translation commit before the batch
  merges (`AGENTS.md`, Translations).
