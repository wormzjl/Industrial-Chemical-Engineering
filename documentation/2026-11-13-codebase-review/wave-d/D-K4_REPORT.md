# D-K4 One door input and one name entry for the screens (C6, C7): report

Batch `2026-11-13-codebase-review`, wave D, package D-K4 (brief: [WAVE_D_BRIEFS.md](../WAVE_D_BRIEFS.md), "D-K4"; review: [CLIENT_REVIEW.md](../CLIENT_REVIEW.md), rows C6 and C7). Written by the D-K4 subagent on 2026-11-14, on the batch branch's tip `fe6731f76`. Structure only. Nothing was compiled or run: the main thread compiles and runs.

Summary:
- **C6** (`2250037c0`): `KitDoor.action(x, y)` takes the Choice loop of the device screen and the control panel (the same statements). `client/gui/common/kit/DoorInput.java` (51 lines, static, holds nothing) takes the routing where a screen's order equals it: `press` (tour, `SkinField.clickOutside`, the door's bar) in 5 screens, `wheel` (open tour, door) in 6, `overlay` (open tour, else `KitDoor.hint`) in 5. `TourHost` implements `DoorInput.Overlay`.
- **C7** (`e1dd4a1b3`): `client/gui/common/NameEntry.java` (107 lines) replaces the name entry of the device, assembly and datasheet screens. `MachineScreen` keeps its own: its entry behaves differently (table 2).
- Screens: 142 lines removed, 66 added. Helpers: 171 lines added. Tests: `KitLayoutTest.theDoorsActionIsTheChoiceUnderThePoint` (new case) and `NameEntryTest` (new, 53 lines).
- No text key, colour, coordinate, packet or lang file changed. No input override changed its signature. Nothing is allocated per frame or per event that was not before.
- Three departures from the brief's names, each for a layering reason (section 3).

## 1. Input routing, per screen and event (C6)

Abbreviations: **T** the tour (`mouseClicked`, `isOpen`, `keyPressed`: true ends the event); **CO** `SkinField.clickOutside`; **DP**, **DD**, **DR**, **DW** the door's press, drag, release, wheel (door coordinates); **CH** the Choice loop; **TK** `ProcessUi.textKeyPressed`; **S** the game's (`super`). `a || b` stops at the first true. "Now" says what the event uses after C6.

| Screen | Press | Drag | Release | Wheel | Key | Hover (render's end) |
|---|---|---|---|---|---|---|
| Constructor | T; CO; DP (door at 0,0) ‖ S. **Now `DoorInput.press`** | DD ‖ S | DR; S | T; DW ‖ S. **Now `DoorInput.wheel`** | T ‖ S | place(0,0); hovered=null; render at `tour.pointer`, no batch; T render else hint. **Now `overlay`** |
| Gauge | T; CO; DP ‖ S. **press** | DD ‖ S | DR; S | T; DW ‖ S. **wheel** | T; Enter → save; TK; S (own) | place; hovered=null; batch; T else hint. **overlay** |
| Machine | as Gauge. **press** | DD ‖ S | DR; S | **wheel** | T; Enter and editable and settable → apply; TK; S (own) | as Gauge. **overlay** |
| Pipeline tool | as Gauge. **press** | DD ‖ S | DR; S | **wheel** | as Gauge (own) | as Constructor, no batch. **overlay** |
| Vessel | T; CO; DP ‖ glass.press ‖ S. **press**, then the glass | DD ‖ glass ‖ S | DR; glass; S | T; DW ‖ glass ‖ S. **wheel**, then the glass | T ‖ S | T render at the raw pointer, hint at `tour.pointer(x)`: own (same values while closed, other arguments) |
| Control panel | T; CO; **CH**; DP ‖ S: own (a choice before the bar). CH **now `door.action`** | DD ‖ S | DR; S | T; DW ‖ S. **wheel** | T ‖ TK ‖ S | place; batch; T else hint (no hovered reset: `renderLabels` assigns it). **overlay** |
| Device | T; dropdown; CO; DP; **CH**; table scroll; S: own (dropdown between T and CO). CH **now `door.action`** | dropdown; DD; scroll; S | dropdown; scroll; DR; S | T; dropdown; DW; scroll; S: own | T; TK; S | own (tour over a page drawn at -1, vanilla tooltip) |
| Assembly | T; CO; `door()` && DP ‖ list ‖ glass; the drawing's hit; S: own (DP conditional) | DD ‖ list ‖ glass ‖ S | DR; list; glass; S | T; `door()` && DW ‖ list ‖ glass ‖ S: own | T; TK; S | own (pointer -1 under a tour, the glass's hover after the tour's return) |
| Simulator | T; CO; dialog, door and tab branches: own | the door's view only with a door open, else gestures | kit, glass, gestures; S | T; the door's view only with a door open: own | T; Escape, Enter, S, undo: own | `touring` read before drawing: own |

Not taken by a helper, though equal across screens:
- **Char typed**: all nine `tour.isOpen() || super.charTyped(...)`. That is already one call on the tour host.
- **Drag and release**: the shared part is one `KitDoor` call (`door.drag(y - top)`, `door.release()`). A helper cannot call `super`, so it would add a call and remove nothing.
- **Key**: the shared part is T then TK (assembly, control panel, device). TK is in `ProcessUi` (`client/gui/common`). A kit helper calling it would import upward, so it stays in the screens. TK stays first after the tour wherever it was.

Why each helper is the same behaviour:
- `DoorInput.press` runs the screen's statements in order: `if (tour.mouseClicked(font, width, height, x, y)) return true; SkinField.clickOutside(screen, x, y); return door.press(x - left, y - top, button);`. Each screen then writes `|| [own handlers] || super...`. The vessel's glass press came after DP, so it stays after the call.
- The constructor passes left and top as 0. `x - 0` is the same double as `x`, -0.0 and NaN included.
- `width` and `height` are the public `Screen` fields the screens read. `font` is passed in (`Screen.font` is protected).
- `KitDoor.action` holds the loop's statements as written: `view.inside`, then each column's `action(x, view.contentY(y))`, the first non-null. Each screen keeps `button == 0` before it, and keeps its own order: the device presses the bar first, the panel the choice.

## 2. The name entry, per screen and part (C7)

| Part | Device | Assembly | Vessel (datasheet) | Machine |
|---|---|---|---|---|
| Current name | `EquipmentStructure.name(level, menu.position())` | the column's | the vessel's | the origin's, "" without a datasheet |
| Draft seeded | at init, always (draft and name followed) | when the box is made | when the box is made | at init, draft only (no name followed) |
| `renames()` | draft ≠ null && `nameable()` && clean(draft) ≠ name | draft ≠ null && `names()` && … | draft ≠ null && … | inline in `apply`: `datasheet()` && draft ≠ null && … |
| Follow on tick | `containerTick`; the box from `editors.get("name")`; `draft.equals` unguarded (draft is set with the name followed) | `containerTick`, the `nameBox` field | `tick()`, the nested-if form | **none** |
| Box | SkinField at the place moved to the screen; message, hint `gui.icheme.fluid.name`; max `DeviceName.MAX`; value; responder; hint; Enter → `saveName`; tooltip name + hint | same, same order | same, same order | same **without Enter** |
| Reader's text | when no "name" editor; cut, inset; hover when cut | when `nameBox` null; same | same; the hover goes to `stripHover` | when !(datasheet && editable): **another condition, no hover** |
| Now | `NameEntry(this::currentName, this::nameable)` | `NameEntry(this::currentName, this::names)` | `NameEntry(this::currentName)` | own |

Notes on the three screens that changed:
- `seed`, `renames` (the renamer is asked only once a draft exists, as `&&` did), `follow`, `box` and `draw` are the screens' statements in the same order.
- Where the screens nulled their box (`nameBox = null` at init), they call `forget()`. The device calls it beside `editors.clear()`, the only place its "name" editor went.
- The device keeps its box in `editors` too, so that a rebuild restores the focus and the cursor.
- `saveName` stays in each screen: who may send, and to which controller.
- The device's `followName` and the assembly's `followName`/`nameEntry(Rect)` are gone; the assembly's box method is `nameBox(Rect)`.

## 3. Departures from the brief, and why

1. **`NameEntry` is top-level in `client/gui/common`, not `TitleStrip.NameEntry`.** The box is a `SkinField`, a game class. `TitleStrip` is documented Minecraft-free, and the render tools (`tools/heat-gui-renders/GuiPrototypes.java`, `tools/tutorial-renders/*`) compile it without the game on the class path.
2. **`DoorInput` is static, and the tour is an interface of the kit (`DoorInput.Overlay`) that `TourHost` implements.** `TourHost` (`client/gui/tutorial`) imports the kit. With the interface, the kit imports nothing of the tutorial. Static methods allocate nothing per event.
3. **No drag, release or key helper** (section 1).

## 4. Checks without a compiler (`tmp/dk4/check.mjs`, `tmp/` is git-ignored)

- Braces, parentheses and brackets balance in all 14 touched Java files. Every touched file is CRLF on every line (the Write tool's files converted).
- The nine screens' `mouseClicked`, `mouseDragged`, `mouseReleased`, `mouseScrolled`, `keyPressed`, `charTyped` and `mouseMoved` signatures and their `@Override`s are unchanged. The signatures were checked against `Screen` and `AbstractContainerScreen` of the NeoForge 21.1.219 sources.
- Lines tests read from the screens:
  - `DeviceTourTest`: the `tabs.add("…")` list is unchanged.
  - `SimulatorBlockTest`: the `copyContents` body is unchanged.
  - `HelpCoverageTest`: the screen class lines are unchanged.
  - `CalculatorColumnTest`: `ProcessSimulatorScreen` is untouched.
  - `KitLayoutTest`: no GUI-scale call was added.
- No class named `DoorInput` or `NameEntry` exists in Minecraft or the mod, so the wildcard imports (`ControlPanelScreen`, `FluidDeviceScreen`) stay unambiguous.

## 5. Tests for the main thread (task `test`)

First run `./gradlew compileJava compileTestJava compileFluidGameTestJava`. No GameTest names a changed class; the compile proves it. Then run one command: `./gradlew test --tests 'com.wormzjl.icheme.client.gui.*' --tests 'com.wormzjl.icheme.LayeringTest'`.

| Class | What it proves here |
|---|---|
| `client.gui.common.kit.KitLayoutTest` | `theDoorsActionIsTheChoiceUnderThePoint`: the choice under a point in either column, nothing outside the view, read at the offset; the GUI-scale scan over the new files |
| `client.gui.common.NameEntryTest` (new) | seed once, follow while untouched, a second seed keeps the draft, the renamer asked only once a draft exists |
| `client.gui.common.TitleStripTest`, `ScreenToursTest`, `DeviceTourTest`, `client.gui.tutorial.HelpCoverageTest` | The strip and the tour anchors unchanged; every screen still toured; the device's tabs read from its source |
| `client.gui.flowsheet.SimulatorBlockTest` | The device's `copyContents`, read from the source |
| `client.gui.common.AssemblyColumnTest`, `DeviceColumnTest`, `HeatControlColumnTest`, `DialogColumnsTest` | The builders of the changed screens are unchanged |
| `com.wormzjl.icheme.LayeringTest` | No common class names a client class |

Not provable by a gate: presses, drags, the wheel and typed names in the game (section 7).

## 6. Points I could not verify without a compile

1. `FluidDeviceScreen.java` line 107, `AssemblyScreen.java` line 177, `VesselScreen.java` line 220: method references (`this::currentName`, `this::nameable`, `this::names`) in field initializers, as `Supplier<String>` and `BooleanSupplier`. The same form exists in the screens' `TourHost` fields.
2. `TourHost.java`: `@Override` on `isOpen`, `mouseClicked` and `render` against `DoorInput.Overlay`. The signatures are identical.
3. `DoorInput.java` line 36: `SkinField.clickOutside(screen, …)` with a `Screen`, which is a `ContainerEventHandler`.
4. `AssemblyScreen.java` line 217: `addRenderableWidget(nameEntry.box(…))` returns `SkinField`, the method's return type.
5. `NameEntryTest`: the two constructors are chosen by arity. The test loads `NameEntry` without a font; no test path touches `SkinField`.

## 7. What the owner should try in the game

- **Constructor, gauge, pipeline tool, machine**:
  - Press a key and an entry; click outside a focused entry: the edit ends.
  - At 320 by 240, wheel the door and drag its bar.
  - Hover a hint.
  - Open `?`: presses and the wheel under the tour do nothing; hints come back after it closes.
- **Control panel**: press a Choice row (a mode) in the door, then scroll the door. Check the tour as above.
- **Vessel datasheet**:
  - Wheel the door, and the glass beside it.
  - Type a name: Apply lights only when the name changed; Enter saves it.
  - Rename the structure from another player while the box is untouched: the box follows.
- **Device screen**:
  - A heat side's Control tab: a mode Choice. A flow valve: its target Choice.
  - Type a name; Enter and Apply save it; another player's rename is followed while untouched.
  - Focus the name, change units (a rebuild): the focus and the cursor stay.
  - As a reader (no edit), the name shows as text, and a long name shows whole on hover.
- **Assembly screen**: type a name, then Enter; Apply on the Structure and the Operation tabs; follow, as above.
- **Machine and simulator names and keys**: unchanged; a look for regressions only.

## 8. Found by reading, not changed

1. **Machine datasheet name may never be saved for a Create coupling.**
   - The box shows when `datasheet() && canEdit()`. Apply and Enter exist only when `kind().settable()`, and `CREATE_COUPLING` is not settable. The box has no `onEnter`.
   - So, if a coupling is a structure with a datasheet, the player can type a name that nothing sends.
   - Proposal: give the machine screen `NameEntry` (`onEnter` to a rename-only save). That also adds the follow and the cut-name hover that it lacks.
2. **The machine's draft never follows another player's rename.** It has no follow on tick: a name typed elsewhere stays stale in an open screen until the next init.
3. The device screen's Accessory-tab `PipeInfo` question (D-K2 report, section 9) is still open; D-K4 did not touch it.
