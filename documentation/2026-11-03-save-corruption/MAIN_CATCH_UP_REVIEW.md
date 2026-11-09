# Main catch-up review (2026-11-09)

Owner: "A lot of work landed on main, review and see what needs to be done. Also check DRAWN_CONNECTIONS_REVIEW.md". Read-only: the merge was computed with `git merge-tree`, no worktree touched, no Gradle run.

## 1. The merge now

- Main moved from `744ef4513` to `08cf75ea6`: 574 commits, 0.20.0 to 0.23.4 (process simulator, rotating machinery, simulator sizing, refinery and open thermo data, gate fixes, LOD, drawn connections, final-lane selection).
- 19 files conflict, 34 hunks: `FluidFacts` 9, `WorldTopologyLedger` 5, `EquipmentStructure` 4, `FluidFactsCompatTest` 4, `FluidWorldAuthority` 3, `FluidCheckpointCodec` 2, `PipeLayingGameTests` 2, one each in `FluidDeviceBlock`, `FluidDeviceBlockEntity`, `PhysicalFluidTopology`, `IChemE`, `documentation/INDEX.md` (keep both rows); add/add in `compat/fluid/schema-8` and `schema-9`.
- `PipeLaying.java` is gone on main (`df24eb6ac`, folded into `LineLaying`, `LinePlacement`, `FluidLineBackend`). W5's laying edits move there: paints and rack frames into the registry at laying, and the flag rename. `LinePlacement.apply` still sets `FluidDeviceBlock.laying`, which W5 renamed `registered` and widened to replacement and removal. Check at the merge that no line-tool edit replaces a registered block.
- Main's fluid facts are at schema 14 (10 shaft units, 11 electric stores, 12 pump health, 13 crossflow exchangers, 14 VFD drives, Create shafts, powered movers). The renumbering options become: one schema 15, or 15 and 16.
- `InstrumentService.fitting` and `occupied` merge cleanly on main's accessory store (schemas 3 and 4). Main's `EquipmentDeconstruction` removes through `EquipmentStructure.dismantle`, which already clears W5's structure record.

## 2. Drawn connections against W5

Option A (the drawn joints are the registry seen through the engine's join rule) is W5's principle, for joints.

1. W5's `connectedState` calls become registry-driven with no edit. Stale text: the `RegistryRealignment` Javadoc still says that neighbours in an unloaded chunk keep their saved joints. Main removed that special case.
2. **A defect after the merge.**
   - Expected: the re-alignment restates a block only when it differs from its registration.
   - Found: `FluidDeviceBlock.fittings` turns only `FlowControl.Pump` and `Compressor` into a mover. An in-line machine (`FlowControl.Machine`, 0.22.0) gets `INLINE_MOVER=NONE`. `refreshGeometry` then sets PUMP back from `Device.hydraulicKind()`. So every chunk load restates each in-line machine: two block updates and a log line. On the way, the block stands for a moment as a plain pipe, so its FE and data sockets may drop. That is not checked yet.
   - Proposal: `fittings` takes the fitting from `hydraulicKind()`, through one helper shared with `refreshGeometry`. W5's own switch is deleted.
3. **Decision 1 (ghosts).** W5 answers it inside the saves rule ("a code or data change never discards a fact").
   - A registration whose block is gone gets its block back when its chunk loads (PLACED).
   - A registration whose cell another block holds is HELD, and the island's status line names it (step 7). Pruning would discard facts.
   - Left: a HELD registration still draws an arm into the cell it is held at. That is correct, because the engine joins it.
4. **Decision 2** (a block placed already as INLINE_MOVER gets the legacy Pump law).
   - W5's re-placement is quiet and keeps the registration's Machine law.
   - W5's learning path (test j: a block the registry never saw registers fresh) goes through the same `world.place`. The owner's choice holds for both.
5. **Decision 3** (client prediction): no W5 interaction.

## 3. Scope: main's new blocks

Main added blocks that W5 does not re-align: `ShaftBlock` (shaft units), `ConduitBlock` (data and FE cables in `ControlFacts`, a new saved store), `DcsBlock`, `ControlPanelBlock`, `LinePieceBlock`, the creative power blocks and `ProcessSimulatorBlock`. Their facts can disagree with the world, as fluid devices could before W5. Options:

- (a) Merge W5 as built (fluid devices, racks, structures), and plan a W5c for shafts and cables. Recommended.
- (b) Extend W5 before the merge.

## 4. Process changes on main

- The final lane runs only the affected test classes (`tools/merge-lane/affected.mjs`). `lane.sh head --all` runs only on the owner's word.
- Translations: only `en_us` changes during a batch, and one translation commit goes in before the merge. Step 7 already wrote all five files. The merge reruns `check.js` and `stale-translations.js`.
- The new-equipment rule (`FlowsheetCatalog`): W5 adds no kind, so nothing to do.

## 5. Gate and order

- The gate is "after the simulator works". Both simulator batches are merged (0.20.0 and 0.22.0). The simulator-sizing README keeps the owner's in-game acceptance open.
- Once the owner says go:
  1. Merge main here, resolve the 34 hunks, port the laying edits, renumber the schemas (owner's choice) and add the corpus image(s).
  2. Fix 2.2 and the Javadoc of 2.1.
  3. Run the affected tests, then one final lane, CHANGELOG, version, code guide, tooling cleanup and the translation check.
