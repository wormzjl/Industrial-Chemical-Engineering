# D-K1 The client's shared layout and units out of the screens (C9, C16, C8): report

Batch `2026-11-13-codebase-review`, wave D, package D-K1 (brief: [WAVE_D_BRIEFS.md](../WAVE_D_BRIEFS.md), "D-K1"; review: [CLIENT_REVIEW.md](../CLIENT_REVIEW.md), section 2 and rows C9, C16, C8). Written by the D-K1 subagent on 2026-11-14, on the batch branch's tip `380015b56`. Structure only. Nothing was compiled or run: the main thread compiles and runs.

Summary:
- **C9** (`168fbafe8`): `FluidGuiLayout` is now the top-level class `client/gui/common/FluidGuiLayout.java` (78 lines). Its body is the nested class's body, byte for byte apart from the indent; a script compared the 72 lines. `FluidDeviceScreen` loses 73 lines. Every importer changed only its import or qualifier. `MachineScreen`'s theme key now reads the shared layout (the values are equal). Its footer stays, because its values differ.
- **C16** (`01ead6ac3`): only one pair was identical: `AssemblyScreen.units()` and `ControlPanelScreen.units()`. Both screens now call `ClientConfig.operationUnits()`. Every text formatter differs from its nearest twin, so each one stays; section 2 names the difference. Both `Basis` enums stay, because `ClientConfig.Basis` is saved by name.
- **C8** (no commit): the constructor's rebuild signature and the machine's are **not** the same, so nothing was merged. The other screens' signatures are not the same either (section 3).
- The report and the code guide are in the third commit. No lang key, no displayed string, no rounding and no unit choice changed. Nothing in `runtime/**`, `network/**`, `science/**` or `world/**` changed.

## 1. C9: what moved

`FluidDeviceScreen.FluidGuiLayout` → `com.wormzjl.icheme.client.gui.common.FluidGuiLayout`:
- The members are unchanged: `imageWidth`, `imageHeight`, `titleKeys`, `DOOR_TOP`, `DOOR_FOOT`, `HEIGHT`, `doorView`, `fits`, `Slot`, `KEY` (package-private, used only inside the class), `footer`, `softKeys`, `side`, `tabs`, `ControlSlots`, `SPLIT_MIN`, `control`.
- Two lines changed. `public static final class` became `public final class`. The class javadoc gained the sentence "Moved out of FluidDeviceScreen, where it was nested (finding C9)."
- Imports: `kit.Rect`, `java.util.ArrayList`, `java.util.List`. `TitleStrip` is in the same package. The class holds no Minecraft class, so the dedicated server loads nothing new.

Importers:

| File | Change |
|---|---|
| `client/gui/common/AssemblyColumn`, `CalculatorColumn`, `DeviceTour`, `ScreenTours` | Import removed: they are in the class's package now |
| `client/gui/flowsheet/DoorFrame` | Import → `client.gui.common.FluidGuiLayout` |
| `client/gui/flowsheet/UnitDoors`, `UnitGlass` | An unused import removed (each file named the class only in that import) |
| `screens/inventory/FluidDeviceScreen` | Nested class removed. Its uses resolve through its existing `import com.wormzjl.icheme.client.gui.common.*;` (line 8) |
| `screens/inventory/MachineScreen` | Import → the new name |
| `screens/inventory/ProcessSimulatorScreen` | New import (line 6). `FluidDeviceScreen.FluidGuiLayout.imageWidth/imageHeight` → `FluidGuiLayout.…` (lines 488, 492) |
| Tests in `client/gui/common` (`ConverterDoorTest`, `DeviceColumnTest`, `DeviceTourTest`, `FluidGuiConsistencyTest`, `HeatControlColumnTest`, `MachineColumnTest`, `ScreenToursTest`, `StillDoorTest`, `TitleStripTest`) | Import removed (same package) |
| `client/gui/tutorial/HelpCoverageTest` | Import → the new name |
| `tools/heat-gui-renders/GuiPrototypes`, `HeatDiagramRender`, `SimulatorPrototypes`; `tools/tutorial-renders/GuideRender`, `CoverageRender` | Import → the new name (one line each) |

Left alone:
- `tools/simulator-sizing/archive/gearbox-ports/GuiPrototypes.java` still imports the old name. It is an archive and already fails to compile: it uses the removed `WindowScale`.
- `tools/fluid-gui-consistency/implementation.patch` is a historical patch.

`MachineScreen`'s literals compared with the shared layout:

| Literal | Shared value | Result |
|---|---|---|
| Theme key `new Rect(imageWidth - 28, 5, 18, 18)` | `FluidGuiLayout.titleKeys(w, true).get(0)` = `TitleStrip.keys(w, 2).get(0)` = `Rect(w - 10 - KEY - 0, TOP, KEY, KEY)` with `KEY` = `HEIGHT` = `Column.WIDGET` = 18 and `TOP` = 5: `Rect(w - 28, 5, 18, 18)` | Equal for every width. **Replaced** (line 143-144) |
| Help key `TitleStrip.keys(imageWidth, 2).get(1)` (not a literal) | `titleKeys(w, true)` returns `TitleStrip.keys(w, 2)`, so its `get(1)` is the same | Equal. Now read from the same list (line 149) |
| Footer: Close `Rect(10, by, 50, 18)`, Datasheet or Apply at x 66, 60 wide, Apply at x 132, 60 wide | `FluidGuiLayout.footer(w)`: slot 0 is `(10, sizes[0])`, slot 1 is `(16 + sizes[0], sizes[1])`. `sizes` = (50, 60) only while `available = w - 212` ≥ 110, so at an image 322 px wide or wider. Below that the keys shrink: at the smallest window (320, image 304) Close is 43 px and Apply 47 px. The footer has no slot for a third push button (its slots 2 to 5 are the display keys) | **Not equal. Kept** |
| `by = imageHeight - 27` | Not a `FluidGuiLayout` value. `FluidDeviceScreen` writes the same literal | Kept |

## 2. C16: the units table

N1(x) = `kit.Format.number(x)` (`ProcessUi.number` forwards to it): `"%.3g"` if x ≠ 0 and |x| < 0.05, else `"%.1f"`. It has no finite guard, so it prints `NaN` and `Infinity`. F(d, x) = `"%.<d>f"`, `Locale.ROOT`. Java writes an exact −0.0 as `-0.0`. The scratch table with the callers is `tmp/d-k1/units-table.md` (git-ignored).

| # | Function | Unit choice read | Conversion | Format | Non-finite or null | Unit text | Same as | Result |
|---|---|---|---|---|---|---|---|---|
| 1 | `SheetUnits.number(d, v)` | none | none | F(d, v) | "—" | none | — | kept: the sheet's base |
| 2 | `OperationColumn.fixed(v, d)` | none | none | F(d, `v == 0 ? 0 : v`) | "—" | none | 1 except at v = −0.0 ("0.0" against "-0.0") | **kept**: differs at −0.0 (also the argument order) |
| 3 | `CalculatorColumn.fixed(d, v)` | none | none | F(d, v) | `NONE` for NaN; ±∞ prints `Infinity` | none | 1 except at ±∞ | kept |
| 4 | `SheetUnits.reading(d, v)` | none | none | E form below 0.01 and from 1e6; under 1, three significant figures | "—" | none | — | kept |
| 5 | `SheetUnits.t(K)` | the record's temperature (the simulator: `ClientConfig`) | `display` | F(1) | "—" | the caller adds the symbol | — | kept |
| 6 | `OperationColumn` ports temperature (inline) | `Units.temperature` | `display`, NaN kept | `fixed(…, 1)` | "—" | the header's symbol | 5 except at −0.0 | kept (inline, not a function) |
| 7 | `FluidDeviceScreen.temperatureValue(K)` | the screen's field (from `ClientConfig` at open, then toggled) | `display` | N1 | "—" | the caller | 5 except below 0.05 (0.01 °C: "0.0100" against "0.0") | kept |
| 8 | `SheetUnits.p(Pa)` | the record's pressure | `display` | F(0, 1, 2, 3 for Pa, kPa, bar, MPa) | "—" | the caller | — | kept |
| 9 | `OperationColumn.pressure(Pa, unit)` | `Units.pressure` | `display` | `fixed` with the same 0, 1, 2, 3 | "—" | the caller | 8 except at −0.0 | **kept**: differs at −0.0 |
| 10 | `FluidDeviceScreen.pressureValue(Double)` | the screen's field | `PressureUnit.number`: `rint(Pa / 0.1) · 0.1`, −0 → 0 | F(1, 4, 6, 7) | "—" for null or non-finite | the caller | — | kept: other decimals than 8 |
| 11 | `FluidDeviceScreen.displayPressure(Double)` | the screen's field | as 10 | as 10 | "—" | `" " + symbol` (`PressureUnit.format`) | 12 only in kPa | kept |
| 12 | `ProcessUi.pressure(Pa)` | none: always kPa | as 10 | F(4) | "—" | `" kPa"` | 11 only while the unit is kPa | kept (no caller, C10's item) |
| 13 | `FluidDeviceScreen.value(v)` | none | none | N1 | "—" | none | the inline `Double.isFinite(x) ? Format.number(x) : "—"` (`HeatControlColumn` line 125, `FluidDeviceScreen` lines 1126 and 1142); no other function | kept (only inline twins) |
| 14 | `FluidDeviceScreen.quantity(v, unit)` | none | none | N1 | "—", and still `" " + unit` | `" " + unit` | — | kept |
| 15 | `SheetUnits.moles(mol/s)` | the record's time | `display / 1000` | `reading(1)` | "—" | `molesUnit()` = `time.unit("kmol")` | — | kept |
| 16 | `OperationColumn` feed entry (inline) | `Units.time` | `display / 1000` | `fixed(…, 2)` | "—" | `unit("feed")` = `time.unit("kmol")` | 15's unit text only | kept |
| 17 | `ControlPanelScreen` converter rate (inline lambda) | `ClientConfig.flowTime()`, read now | `display` | N1 | none: prints `NaN` | `flow.unit("L")` | 18 except for non-finite | kept |
| 18 | `FluidDeviceScreen` converter litres (inline lambda) | the screen's field | `display` | `value`: N1 | "—" | `flowRateUnit.unit("L")` | 17 except for non-finite | kept |
| 19 | `AssemblyScreen.units()` | `ClientConfig.temperature()`, `.pressure()`, `.flowTime()`, in that order | — | — | — | — | 20, the same expression | **merged** |
| 20 | `ControlPanelScreen.units()` | the same | — | — | — | — | 19 | **merged** |
| 21 | `ProcessSimulatorScreen.units()` | `ClientConfig` T, P, time, the basis by name, the conditions | — | — | — | — | — | kept (another record) |

The merge:
- `ClientConfig.operationUnits()` (line 134) returns `new OperationColumn.Units(temperature(), pressure(), flowTime())`, in the same read order.
- Both screens' private `units()` are removed. Their 9 calls (6 in `AssemblyScreen`, 3 in `ControlPanelScreen`) call it.
- It allocates one record per call, as before; the calls in `AssemblyScreen.renderLabels` already allocated one per frame.
- Home: the skill puts the unit choice in `ClientConfig` ("The theme and the units are the client's (`icheme-client.toml`, `ClientConfig`)"). The builders must stay free of `ClientConfig` (it holds `ModConfigSpec`), so the method cannot go into `OperationColumn`.

What I did not merge, and why:
- `OperationColumn.Units` and `SheetUnits` could become one record: `Units` is `SheetUnits` without the basis and the conditions. Merging them changes no function's text, but it changes `OperationColumn`'s API across 3 tests and a tool. The brief limits C16 to identical formatters, so I left it.
- In `FluidDeviceScreen`, the inline `Double.isFinite(kg) ? number(kg) : "—"` equals `value(kg)`. I left it: it is not on the brief's list.

The two `Basis` enums:
- `ClientConfig.Basis` (MOLE, MASS, VOLUME) is saved by name in `icheme-client.toml` (`defineEnum("basis", Basis.MOLE)`, `ClientConfig.java` line 66).
- `SheetUnits.Basis` (the same three constants, in the same order, plus `next()`) is client-only. It is in no codec, no facts and no wire: the grep found `Basis.valueOf` only in `ProcessSimulatorScreen`. It maps one to one by name (`ProcessSimulatorScreen.units()` and `unitKey`).
- A saved name is involved, so both stay, as the brief says.
- `FluidDeviceScreen` reads VOLUME as MOLE (`mass = basis() == MASS`), as the review noted.

## 3. C8: the signatures compared

| | `EquipmentConstructorScreen.signature(Page)` | `MachineScreen.signature(rows)` |
|---|---|---|
| Type | `List<String>` | `String` |
| Leading state | none | `view != null`, `canEdit()`, `waiting()` |
| Slots | every entry of `door.place(rows, m).slots()`, as `key@rect`, for every widget kind | from `Column.place(rows, 10, DOOR_TOP, side − 10, m, COMPACT)`: the slots of `Buttons` and `Field` rows only, written after their key |
| Buttons | `ConstructorColumn.buttons(rows)` plus the footer: `key|text|active|latched|hint` | each `Buttons` row's buttons: `key`, `active`, `latched`, slot. **No text, no hint, no footer** |
| Fields | through the slots | `key`, slot |

They differ in their fields and their order, and in what they cover:
- The machine's signature leaves out the button texts and hints. If it took them in, the machine page would rebuild when a button's text changes, and a rebuild resets a focused entry.
- The constructor's signature leaves out the three state flags.

So no `Column.signature()` was written.

The other three screens' signatures are not equal to either of these, nor to each other:
- `AssemblyScreen`: the page flags, the tabs, the selection, the door's and the Operation rows' keys.
- `ControlPanelScreen`: a string of the page, the controllers and the draft.
- `ProcessSimulatorScreen`: the tab, the door view, the units and the sizing state.

`FluidDeviceScreen` has no signature: it sets `rebuildNext`. C8 is left for the owner: a shared helper would need one of the two screens to change what triggers its rebuild.

## 4. Tests for the main thread

Every test named here is in `test`. None is in `columnGates` (`science/column/v3`) or `equipmentGates` (`science/equipment`). `holdGates` holds `client.gui.flowsheet.CrudeUnitSampleTest`, `UtilityRegulationTest`, `SmallCrudeUnitTest` and `SmallUnitRecordingTest`; run them only if `affected.mjs` finds `DoorFrame` (changed bytecode) in their load sets.

- First `compileJava compileTestJava` (the moved class, the imports, `ClientConfig.operationUnits()`).
- Then confirm the set with `node tools/merge-lane/affected.mjs select`.
- Changed bytecode: `FluidGuiLayout` (new), `FluidDeviceScreen`, `MachineScreen`, `ProcessSimulatorScreen`, `AssemblyScreen`, `ControlPanelScreen`, `ClientConfig`, and every class that calls a `FluidGuiLayout` method (`AssemblyColumn`, `CalculatorColumn`, `DeviceTour`, `ScreenTours`, `DoorFrame`; the tests that call it). `UnitDoors` and `UnitGlass` lost only an unused import, so their class files are unchanged.

Test classes under `src/test/java/com/wormzjl/icheme/client/` that load a changed class, by name (task `test`):

| Test class | What it proves here |
|---|---|
| `gui.common.FluidGuiConsistencyTest` | The moved layout places the same footer, tabs, soft keys, side and Control slots at every width |
| `gui.common.TitleStripTest` | `titleKeys(w, help)` and `TitleStrip.keys` agree (the basis of the `MachineScreen` replacement) |
| `gui.common.DeviceColumnTest`, `HeatControlColumnTest`, `ConverterDoorTest`, `StillDoorTest`, `MachineColumnTest` | The door columns at `FluidGuiLayout.side` and `doorView`, as before |
| `gui.common.DeviceTourTest`, `ScreenToursTest`, `AssemblyTourTest`, `gui.tutorial.HelpCoverageTest` | Tour anchors from `DeviceTour` and `ScreenTours` (new import resolution); `DeviceTourTest` also reads `FluidDeviceScreen.java`'s `tabs.add` lines, which are unchanged |
| `gui.common.AssemblyColumnTest`, `AssemblyDiagramTest`, `CalculatorColumnTest` (it also reads `ProcessSimulatorScreen.java`'s `tr`/`lang` keys, which are unchanged), `HeatDiagramTest`, `OperationColumnTest` | Builders that call `FluidGuiLayout.tabs`, and the Operation door |
| `gui.flowsheet.DoorUnitsTest`, `SimulatorBlockTest` (it reads `FluidDeviceScreen.java`'s `copyContents`, which is unchanged), `SimulatorColumnsTest`, `SizingPageTest`, `AssemblyBlockTest`, `ColumnBlockTest`, `DrawCallTest`, `RunKeyTest`, `SimulatorFrameTest`, `SimulatorRunnerTest`, `SimulatorTourTest`, `StreamFlowBasisTest` | The simulator's doors through `DoorFrame` (`StreamFlowBasisTest` and `DoorUnitsTest` are the sheet's unit tests; `SheetUnits` itself did not change) |
| `gui.screens.inventory.V3ColumnInputDraftTest` | It names `CalculatorColumn` |
| `gui.common.kit.KitLayoutTest` | It scans every `client/` source for a GUI-scale call; three sources changed |
| `com.wormzjl.icheme.LayeringTest` (outside `client/`) | No common class imports `client`: `ClientConfig` → `client.gui.common` is client to client |

A command that covers them: `./gradlew test --tests 'com.wormzjl.icheme.client.gui.*' --tests 'com.wormzjl.icheme.LayeringTest'` (narrow it with `affected.mjs`).

Not run by any test:
- `tools/heat-gui-renders` and `tools/tutorial-renders`: compile them with the documented commands. `GuiPrototypes`' sheets should be pixel-identical.
- `UnitTextTest` and `LanguageFilesTest`: no key changed.

## 5. What only an in-game look can confirm

- The machine page's theme key and help key stand where they stood. The arithmetic says they do: `(w − 28, 5)` and `(w − 50, 5)`, both 18 px.
- The assembly screen's and the DCS console's Operation entries show the same values. They read the same three settings through the new method.

Nothing else changes what a screen draws.

## 6. Points where I am not certain

What I checked by reading:
- The moved body is unchanged: a script compared the old lines 1479-1550, with the indent removed, against the new file's lines 7-78. 2 lines differ, both named above.
- No file in `src/**/*.java` or `tools/**/*.java` names `FluidDeviceScreen.FluidGuiLayout`, apart from the archive named in section 1.
- Every edited file keeps its CRLF line endings. `git diff --stat` shows only the lines I changed.

Points I could not check without a compile:
1. `FluidDeviceScreen.java` relies on its on-demand import `client.gui.common.*` (line 8) for `FluidGuiLayout`. No other class of that name exists in its package or in its other on-demand imports. The `kit.Rect` import is still used (14 uses).
2. `ClientConfig.java` lines 130-136 name `com.wormzjl.icheme.client.gui.common.OperationColumn.Units` by its full name. `IChemE` loads `ClientConfig` on the dedicated server too (line 219 registers `ClientConfig.SPEC` with no dist check, so the class doc's "a dedicated server never loads it" is not literally true). The new method is never called there. The JVM resolves the reference lazily, so `OperationColumn` is not loaded on the server, as `skin()` already does with `PanelSkin`. I am confident of this but have not run a server.
3. `FluidGuiLayout.KEY` stays package-private. A grep found no use outside the class.
4. The −0.0 differences in section 2 rest on `java.util.Formatter` writing `-0.0` for negative zero. I know this behaviour; I did not run it here.

Out of my lane, for the main thread:
- `.claude/skills/gui-creation/SKILL.md` lines 104 and 160 still name `FluidDeviceScreen.FluidGuiLayout`. The pitfall on line 160 (tools must import the nested name) no longer applies: the class is now a plain Minecraft-free class.
- `documentation/reference/code-guide/README.md` line 58 says `client/**` has 89 files. That count is not maintained by `map.mjs` ("unmapped (left alone)"), and it was already out of date before this package (122 now, outside `gui/flowsheet` and `fx`). I did not edit it.
- The batch README's history line is the main thread's job, as for the other packages.
