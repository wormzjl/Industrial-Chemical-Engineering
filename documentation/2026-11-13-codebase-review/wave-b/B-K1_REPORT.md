# B-K1 Client drawing cost: report

Thread wave-a2 (Opus medium), branch `claude/review-wave-a2-saves-96a998`, after merging the review branch (efc7f612b). Rows C4, C5, C13, C14, C20 of `CLIENT_REVIEW.md`. Every change keeps the pixels. Open: two decisions sent to the main thread (PaintSwatch for C4, the C14 form).

## C4: a frame batch (71f2d5170, test 2b61f2717)
- Done: `AssemblyScreen`, `ControlPanelScreen`, `GaugeScreen`, `MachineScreen` and `VesselScreen` draw their frame, widgets too, in one `GuiCanvas` batch, as the simulator does. The tour and the hover hint draw after it.
- Proof by the draws in each `render`, read from the 1.21.1 sources:
  - Kit draws: the door, title and glass go through the canvas. `SkinButton` overrides `renderWidget` and draws no vanilla sprite. `SkinField` is unbordered (no `blitSprite`) and releases before its vanilla text.
  - Vanilla draws: the transparent background (`fillGradient`, into the shared buffer), and the vessel's blur and menu background (drawn at once). All come before anything is held.
  - No slots: the five menus add none, so there is no item, slot highlight or carried item. Each `renderLabels` override draws through the kit.
  - The NeoForge events inside `render` (container `Background` and `Foreground`, `ScreenEvent.BackgroundRendered`): a kit listener at the highest priority (`FrameBatchEvents`) calls `GuiCanvas.drawOut` first. It sends out what the batch holds and draws the buffer, so another mod's blit there lies on top, as without a batch. Residual: another listener at the highest priority registered before ours.
- Waiting for the decision: `EquipmentConstructorScreen` and `PipelineToolScreen`. Their `PaintSwatch` widgets fill straight on `GuiGraphics` (outside the lane), so the batch cannot see them. The constructor's schematic draws `g.drawString`/`g.fill` directly too; in a batch that needs a `GuiCanvas.release()` before them, which is in the lane.
- Counter: `FrameBatchDrawsTest` counts a door's frame (page, title, column, its buttons, the footer), plain and batched, for the gauge, pipeline tool, vessel and machine doors in both themes. Counts: pending the lane.
- Renders: no changed class is on the render tools' draw path (`Java2dCanvas`, `Column`, `PanelSkin` and the builders are unchanged). The tools draw no frame batch and have no `-Dcount` for these doors, so their images are identical by construction. The lane renders them once.

## C5: KitDoor keeps its column (445dbed44)
- `KitDoor.placeAll` keeps the rows it last placed (copied), the measure and the tallest height. Equal rows with the same measure return the same columns; `layout` forgets them. The rows are records of texts and numbers, with lists copied by `List.copyOf`, and `Column` is immutable. A caller's list changed after the call is not taken as equal (the copy).
- This reaches every `door.place` per frame: constructor 1033, assembly 540 and 544, control panel 797, gauge, pipeline tool, machine 324, vessel.
- `MachineScreen` gathers its sensors' ids at init and each tick before its signature check, no longer on every frame (twice a frame before). A changed id shows at the next tick, where its widgets rebuilt before.
- Not done: the simulator's signature string (590 to 602). Its parts include objects whose `toString` snapshot is the comparison (gestures, the door view, the sizing state). A list of references could miss a change made in place, and an event-driven form needs every writer.
- Test: `KitLayoutTest.equalRowsGiveTheSameColumn` (equal rows give the same object; a changed row, a list changed in place, another measure or a new layout place them again).

## C13: data ports every 5 ticks (3e31846be)
- `DataControlRenderer` gathers its ports every `HOST_TICKS` (5) or on a new level, as `InstrumentRenderer` does, and keeps each port's frustum box. Before: two scans of the loaded holders, three map copies and an `AABB` per port, every frame. Effect: a port placed, removed or entering the 96-block radius shows up to 5 ticks late. Gathers fall from one a frame to 4 a second; no new counter (`FxStats` is outside the lane).

## C14: the body region map (0b3e2e8e0, option B; decision sent)
- What the signature reads: each client structure's origin, shape object, paint object and light, and the level, detail and atlas.
- Its writers:
  - `LoadedEquipment.add`: the shell's load (`EquipmentShellBlockEntity` 216-217), which also carries a new paint or shape.
  - `LoadedEquipment.remove`: the shell's leave (210, 297).
  - The renderer's light timer.
  - `release()`: on a detail or atlas change.
- Done (B, in the lane): a frame walks the structures without allocating, runs the light timers and hashes those inputs. The map and the region signatures are rebuilt only when the hash or the count changes, or after `release()`. Every input is still read each frame, so no writer can be missed; the residual risk is a 64-bit hash collision, as with the region signatures before.
- Option A (version counter in `LoadedEquipment`, outside the lane) would also skip the walk. It waits for the decision.

## C20: small per-frame allocations (35a7782e5)
- One `Measure` per font (`GuiCanvas.measure` keeps the last).
- `GuiCanvas.of(g, font)`: one canvas per frame (the game makes a new `GuiGraphics` each frame), used by `SkinButton` and `SkinField` in place of a new canvas per widget per frame.
- A text outside a batch draws at once, with no pose copy and no closure; `HeldDraws.textNow` counts it.
- A sprite's texture name is parsed once. `setFilter` stays per blit, since another user may change the texture's filter.
- Inside a batch a text still copies its pose: it draws later.

## Tests after "lane free"
- `./gradlew compileJava compileTestJava --max-workers 4`
- `./gradlew test --tests com.wormzjl.icheme.client.gui.common.kit.KitLayoutTest --tests com.wormzjl.icheme.client.gui.common.FrameBatchDrawsTest --tests com.wormzjl.icheme.client.gui.flowsheet.DrawCallTest --tests com.wormzjl.icheme.client.gui.common.DialogColumnsTest --tests com.wormzjl.icheme.client.gui.common.ConstructorColumnTest --tests com.wormzjl.icheme.client.gui.common.MachineColumnTest --tests com.wormzjl.icheme.client.gui.common.AssemblyColumnTest --tests com.wormzjl.icheme.client.gui.common.OperationColumnTest --max-workers 4`
- The render tools once (`GuiPrototypes`, `HeatDiagramRender`), as the skill gives them.

Results: pending.
