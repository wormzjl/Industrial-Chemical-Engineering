# D-K2 The device screen's pages, meters and drafts (C1, C2): report

Batch `2026-11-13-codebase-review`, wave D, package D-K2 (brief: [WAVE_D_BRIEFS.md](../WAVE_D_BRIEFS.md), "D-K2"; review: [CLIENT_REVIEW.md](../CLIENT_REVIEW.md), rows C1 and C2). Written by the D-K2 subagent on 2026-11-14, on the batch branch's tip `79e8bd0f9`. Structure only. Nothing was compiled or run: the main thread compiles and runs.

Summary:
- **Step 1** (`4973b00dd`): `FluidDeviceScreen.Page`, a private enum of the eight pages in the order of the integers they replace. All 36 page literals, `CONTROL=6` and `ACCESSORY=7` are gone. No page is saved, sent or read from the menu, so no ordinal is used anywhere.
- **Step 2** (`29316b039`): `client/gui/common/DeviceMeters.java` (304 lines), a Minecraft-free record beside `DeviceColumn`. It builds the meters, phases, level, suction, drive and power. `PhaseRatioBasis` moved with the Phases. New test `DeviceMetersTest` (129 lines) covers a valve, a tank and a driven pump.
- **Step 3** (`f5bff4e26`): `ConverterDrafts` (48 lines) and `StillDrafts` (39 lines) sit in `screens/inventory` beside the screen. Nothing sent changes, and nothing is sent at another time.
- **Step 4, C2** (`4045e0563`): **done**. Read side by side, the screen's door copy and `KitDoor` behave the same for every row these builders make (section 4). The screen now holds one `KitDoor`; the Control tab uses `layout(areas, bar, rows, m)`.
- `FluidDeviceScreen.java`: 1,508 lines before, **1,245** after.
- Last commit: this report, the code guide's client-gui chapter (8 lines), and one line of the GUI skill (the users of `KitDoor`). `map.mjs --readme` changed nothing and `--check` passes.
- No text key, colour, coordinate, packet or lang file changed.

## 1. Step 1: the pages

`OVERVIEW` 0, `COMPOSITION` 1, `SOLIDS` 2, `CONNECTIONS` 3, `OUTLETS` 4, `DATASHEET` 5, `CONTROL` 6, `ACCESSORY` 7. The following now hold a `Page`: the field `page` (starts at `OVERVIEW`, as the int started at 0), `preferredHeatPage` (static, kept for the session only), the tab list `pages`/`tabPages`, the tab key's `target`, and `changePage(Page)`.

Where a page meets a wire, menu or saved value: **nowhere**. `FluidDeviceMenu` has no page. No packet carries one; the side selector sends `PartnerPayload` with no page. `preferredHeatPage` is a static field and is not saved. The tours key a page by its tab id string (`tabIds`, `DeviceTour.page`). The `tabs.add("…")` lines, which `DeviceTourTest` reads from the source, are unchanged.

## 2. Step 2: `DeviceMeters`

`DeviceMeters(Data data, @Nullable FluidView.Port port, PhaseRatioBasis phaseBasis, TemperatureUnit, PressureUnit, FlowRateUnit, boolean mass, HeatControlColumn.Texts texts)`.
- `Data` holds what the bodies read from the menu's `MenuData`: kind, view, components, molecular weights, `powerOn`, `heatControl`, `tower`, sensors.
- It imports no `net.minecraft`, `com.mojang`, `network` or `screens` class. `DeviceMetersTest.theMetersNameNoMinecraftClass` checks the source. `LayeringTest` keeps no Minecraft-free list, so nothing was added to it.

Moved whole: `meters`, `stillMeters`, `range`, `meter`, `displayedFlow`, `phases`, `amounts`, `aqueousWater`, `level`, `suction`, `boxed`, `drive`, `power`, `PhaseRatioBasis`. A node script cut them by their braces and applied only these changes:
- `menu.clientData()` becomes the field `data`, and `var data=menu.clientData();` is dropped (5 places).
- `data.controls().power()==FluidNetwork.Power.ON` becomes `data.powerOn()`; `data.controls().heat()` becomes `data.heatControl()`.
- `this::value` becomes `DeviceMeters::value`: `value` and `quantity` are now public static.
- `private` becomes `public` on what the screen calls.
- Inline full names stay as they were, so the bodies diff line for line.

New one-liners in `DeviceMeters`: `pipe`, `tank`, `generator`, `tower`, `fans`, `burners`, `converter`, `selectedPort`, `flowUnit`, `temperatureValue`, `pressureValue`, `tr`. Each reads `data` where the screen's version reads the menu.

The screen keeps its own `temperatureValue`, `pressureValue` and `flowUnit`, because the tables use them. `value` and `quantity` forward to `DeviceMeters`. So do `amounts` and `aqueousWater`: the screen reads them in `refresh`, `present` and the tables.

`deviceMeters()` builds one record per call. In `deviceInput` one record serves all six groups (`m.level()`, `m.phases()`, `m.meters()`, `m.drive()`, `m.power()`, `m.suction()`), in the old argument order; none has a side effect. `controlInput` calls `deviceMeters().power()`.

C21: the head arithmetic is in `DeviceMeters.meters` now. `g` is still written twice: there, and in the screen's `curveInput`, which the brief does not move. Making it one constant changes a literal, so I left it out of this structure-only package.

`DeviceMetersTest` expects the values below. Each was derived by reading the code, and the test's comments give the arithmetic.

| Case | Units | Rows (key = value unit) |
|---|---|---|
| Valve | K, kPa, /s, mole | temperature 300.0 K, inlet_pressure 200.0000 kPa, throughput 0.0250 kmol/s, pressure_change -50.0000 kPa; phases by volume; no level, suction, drive or power |
| Tank | K, kPa, /h, mass | temperature, top_pressure 101.3250, bottom_pressure 120.0000, mass 800.0 kg, net_flow 0.0 kg/h; Level(0.25, "1000.0", "4000.0"); phases by mole {100, 50, 0, 0} with the tank hint, then the mole hint |
| Driven pump | °C, bar, /min, mole | 10 rows: temperature 50.0, suction_pressure 1.500000, throughput 15.0 kmol/min, pressure_change 3.000000, head 30.6 m, speed_share 80.0, shaft_power 10.0, efficiency 15.0, pressure_probe 2.500000, temperature_probe "—" (unavailable); Suction("8.0", "3.0", "—", "95.0", "—"); the Drive's line and state |

## 3. Step 3: the drafts

- `StillDrafts`: `firing`, `open`, `shown` (package-private) and `door` (private). `refresh(Still, fields, Runnable rebuild)` was `refreshStill`. `controls(fields)` was `stillControls`.
- `ConverterDrafts`: `mode`, `shown` and `door`. `refresh(Converter, fields, flowRateUnit, rebuild)` was `refreshConverter`. `apply(fields, Edit)` holds the body of `converterApply`.
- The screen keeps the `try`, `message` and the send. It passes the send in as `(mode,rate,band,t)->FluidNetwork.converterEdit(menu,mode,rate,band,t)`, so `converterEdit` still runs inside the same `try`, and `message=tr("waiting")` still follows it.
- Each `rebuildNext=true` became `rebuild.run()` at the same statement, with the screen passing `()->rebuildNext=true`.
- `stillShown=null` and `converterShown=null` became `stillDrafts.shown=null` and `converterDrafts.shown=null`.
- Renames, which the line check maps: `stillFiring`→`firing`, `stillOpen`→`open`, `stillShown`/`converterShown`→`shown`, `stillDoor`/`converterDoor`→`door`, `converterMode`→`mode`. Where a local shares a field's name, the field is written `this.door`, `this.shown` or `this.firing`. The draft keys (`"stillFiring"`, `"converterRate"`, …) are unchanged.

## 4. C2: the copy and `KitDoor` side by side

| Part | The screen's copy (main lines 1171-1283, 1460) | `KitDoor` | Same? |
|---|---|---|---|
| Control tab view | `Rect(leftX-2, top, rightX+rightWidth-leftX+4, h)`, bar `scrollX`, tall = highest column | `layout(areas…)`: `Rect(first.x-2, top, last.right-first.x+4, h)`, bar given, tall = highest | Yes. One column: `leftX == rightX` (`FluidGuiLayout.control`, the narrow branch). Two: same span |
| Device page view | `Rect(8, top, side-6, h)`, bar `side+3`, column at x 10, width `side-10`, never narrowed | area `Rect(10, top, side-10, h)` gives view `Rect(8, top, side-6, h)` | Yes. I used the area-list `layout`; the one-area `layout(area, tallest, m)` narrows by the bar, which the copy never did |
| Placed each frame | `Column.place` and `doorView.layout` | `place(rows, m)`: the same placement and `view.layout`, and returns the last columns when the rows and measure are equal (C5) | Same columns |
| Kept widgets | `Field` and `Buttons` rows, in row order | Those, plus `Wide` and `Setting` steppers | Yes: no builder used here (`DeviceColumn`, `HeatControlColumn`, `StillDoor`, `ConverterDoor`) makes a `Wide` or `Setting` row (grep) |
| Drawing | clip, columns, unclip, bar | clip, columns, unclip, bar | Yes |
| Hover | the last non-null among the columns | the first non-null | Yes: layout C's columns are 12 px apart, so `Column.at` finds at most one |
| Reset at rebuild | `doorWidgets.clear(); doorColumns=List.of()` before the `data==null` return | `layout` clears the widgets; the columns stay | Yes: `clientData` is never null again once set (`FluidDeviceMenu` line 61), and before that the door is empty |
| Input | `press`, `drag`, `release`, `wheel`, `inside`, `contentY` on the view | the same through `door` and `door.view()` | Yes |
| Choice loop (`mouseClicked`) | `column.action` over the columns | `KitDoor` has no `action` method (that is C6) | The loop stays, over `door.columns()` |

## 5. Checks without a compiler (`tmp/dk2/check.mjs`; `tmp/` is git-ignored)

- Of 382 lines that left the screen, 314 reappear in `DeviceMeters`, the two drafts classes or the screen, once qualifiers, modifiers, the step 1 page names and the step 3 renames are normalised.
- The other 68 changed on purpose. By step:
  - Step 1: the declarations of `page`, `CONTROL`, `ACCESSORY` and `changePage`.
  - Step 2: three call sites (`deviceInput` twice, `controlInput`).
  - Step 3: field declarations and javadocs, the two `refresh` signatures, the `converterEdit` line (now `edit.send` plus the screen's lambda), and eight call sites.
  - Step 4: 42 lines of the door copy and its two imports. Their counterparts are in `KitDoor.java` (lines 50-57 `layout`, 69-94 `placeAll`, 116-133 `keep`, 146-157 `draw`, 159-173 input).
- Braces and parentheses balance in every touched Java file. Every touched file is CRLF.
- `DeviceMeters.java` and `DeviceMetersTest.java` import no `net.minecraft` or `com.mojang` class.
- Page literals: 36 before, 0 after.

## 6. Tests for the main thread (task `test`)

First `./gradlew compileJava compileTestJava`, then confirm the set with `node tools/merge-lane/affected.mjs select`.

| Class | What it proves here |
|---|---|
| `client.gui.common.DeviceMetersTest` (new) | The moved meters, phases, level, suction and drive give the rows the screen's code gave, for three kinds |
| `client.gui.common.FluidGuiConsistencyTest` | `PhaseRatioBasis` behaves the same from its new home (import changed) |
| `client.gui.common.DeviceColumnTest`, `HeatControlColumnTest`, `StillDoorTest`, `ConverterDoorTest` | The builders the door places are unchanged (comment edits only in `DeviceColumnTest`) |
| `client.gui.common.DeviceTourTest`, `ScreenToursTest`, `client.gui.tutorial.HelpCoverageTest` | The tabs that `DeviceTourTest` reads from the screen's source (`tabs.add`), the tour anchors, and every screen still has its tour (a comment edit in `HelpCoverageTest`) |
| `client.gui.flowsheet.SimulatorBlockTest` | It reads `copyContents` from the screen's source; that method is unchanged |
| `client.gui.common.kit.KitLayoutTest` | Its scan of `client/` sources (three new files; none sets the GUI scale) |
| `com.wormzjl.icheme.LayeringTest` | `DeviceMeters` (runtime, science) and the drafts (network) reference only layers the client may reference |

One command covers them: `./gradlew test --tests 'com.wormzjl.icheme.client.gui.*' --tests 'com.wormzjl.icheme.LayeringTest'`.

Not run by any gate: `tools/heat-gui-renders` (no tool names a moved class), `LanguageFilesTest` and `UnitTextTest` (no key changed).

## 7. What the owner should look at in the game

The pages should look and act exactly as before:
- A pump with its curve: the Readings (head, speed, shaft power, efficiency), the Suction group, the Drive line.
- A drum: the Overview, Outlets, Datasheet and Accessory tabs; a valve's Readings; a pipe with three connections.
- A heat exchanger side's Control tab, at a wide window (two columns) and at 320 by 240 (one column, the bar). Scroll it; hover a hint in each column; press Off/On, a target and Check lines.
- A still: Firing, the Reflux valve and Apply. A converter: the Mode keys and Apply.
- A device's page at the smallest window: the door scrolls, its widgets stay in their slots, the hint shows on hover.

## 8. Points I could not verify without a compile

1. `DeviceMeters.java` lines 29 and 37: `@Nullable FluidView.Port` and `@Nullable HeatUnit.Control` on record components. The same form compiles in `HeatControlColumn.Input`.
2. `DeviceMeters.java` line 103: `DeviceMeters::value` (static) as a `DoubleFunction<String>`.
3. `FluidDeviceScreen.java` line 1009, `keepWidgets`: `door.keep(this::doorBox,(spec,slot)->{…return button;})`. The other overload, `keep(T, Rect)`, cannot take a lambda for its `Rect` parameter, so the call is not ambiguous. `GaugeScreen` writes the same call.
4. `FluidDeviceScreen.java` line 156, `converterApply`: the lambda's parameters `(mode,rate,band,t)` are typed by `ConverterDrafts.Edit`, which is package-private in the same package.
5. `DeviceMetersTest`: the `FluidView` constructors with 15 and 24 arguments, chosen by arity (every overload has a different arity). The expected strings rest on `String.format` rounding; I checked each value with its binary error in mind (comments in the test).
6. `FluidGuiConsistencyTest` line 4 imports the nested `DeviceMeters.PhaseRatioBasis` from the same package.

## 9. Found, not changed (for the main thread)

- **A probable crash on a drum's Accessory tab.**
  - Expected: the tab opens like the others.
  - The code: `rebuild()`'s last `else configureScroll(data.view().pipeInfo().connections().size())` also runs on `Page.ACCESSORY`. A tank's view has no `PipeInfo`: `ViewBuilder` line 221 and `FluidWorldAuthority` line 1776 give one only to pipes, filters, actuators, voids and heat sides. So the tab would throw a NullPointerException.
  - I kept the code as it was, because this package is structure only.
  - Proposal: give the Accessory page its own branch, `configureScroll(0)`, since the page has no table. A pipe's own safety-valve page has `PipeInfo`, so this question is about drums only.
- `deviceRows()` keeps the unused local `state` that `deviceColumn` had.
