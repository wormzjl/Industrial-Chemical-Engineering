# Old one-block pump and compressor removal: review

Branch `claude/vigorous-bell-dab92b`, base `96806b8a` (main 0.16.1). Plan: [OLD_MOVER_REMOVAL_PLAN.md](OLD_MOVER_REMOVAL_PLAN.md).

## 1. Expected behaviour

A pump or a compressor sits in the line, flanged to its pipe at the line's bore. The In-line Pump and In-line Compressor already work that way. No old one-block block remains. A saved old pump or compressor comes back as a pipe with an in-line mover, at the same place, facing the same way, with the same identity and settings.

## 2. What the code did

- The blocks `fluid_pump` and `fluid_compressor` stayed as command-only blocks. They had their own drawing, and their own connection rule: they joined only pipes (D20).
- They registered portless `PUMP`/`COMPRESSOR` devices. The ledger refused any edit that changed a device's kind, so no in-place conversion could be queued.

## 3. Change

- Removed: both block registrations, blockstates, block models, loot tables, `block.icheme.*` lang lines (en, zh), pickaxe and Create `non_movable` tag entries, the block entity's valid-block entries, `LineGeometry.pump`/`compressor`, and the `PUMP`/`COMPRESSOR` block case in `FluidDeviceBlock.face`. Kept: the item ids, item models and `item.icheme.*` lines (the in-line items), the creative tab entries for them, and the engine's `PUMP`/`COMPRESSOR` kinds.
- `ModBlocks`: `icheme:fluid_pump` and `icheme:fluid_compressor` are block aliases of `icheme:fluid_pipe`. The block keeps its facing, its joints and its block entity.
- `PhysicalRegistry.convertOneBlockMover`: queues a portless horizontal mover as a `PIPE` carrying the same control. Identity, position, facing, spec, joints, length, roughness and minor loss stay. The bore is the largest bore among the pipes it meets on its axis (B1). An upright one is left unchanged (V1).
- `FluidWorldAuthority.loadedChunk` calls it for each pipe block whose registration is such a mover; an upright one logs `mover_compat`. This is one queued engine event: no solve and no forced update.
- `WorldTopologyLedger.Overlay.sameKind`: an edit keeps its kind, with one exception. A portless `PUMP`/`COMPRESSOR` may become a `PIPE` that has the same hydraulic kind.
- No facts-schema or shape change, so no corpus image. The registration record is the same; the conversion is an ordinary edit.

## 4. Physics check

On a line of one bore, the converted mover compiles to the same network: same pipes, sections, controls and ports (`PhysicalRegistryTest.aOneBlockMoverConvertsToTheInlineMoverWithItsFacts` asserts that the island's pipes are equal). It differs only where the two sides of the line had different bores (B1), or where the old block touched a tank, generator, void or filter on its axis: it now joins it (J1).

## 5. Owner options (defaults implemented)

B1 (largest axial bore), J1 (joins as an in-line mover) and V1 (upright ones keep their registration). The alternatives are in plan section 4.

## 6. Tests

- Moved to in-line movers, with each line and its physics unchanged: `FluidPumpStartupGameTests`, `SolidPhaseGameTests`, `FluidPhasePortGameTests` (helpers `FluidPumpStartupGameTests.inlinePump`/`inlineMover`). `FluidPacketGameTests` no longer lists the old blocks.
- New: `OldMoverConversionGameTests` has two tests. The first checks that the aliases resolve and that a saved palette entry reads as a pipe. The second unloads and reloads a chunk: the pump takes the 0.1 m bore, the compressor keeps its settings, the upright pump is unchanged, and the test cleans up. Also new: `PhysicalRegistryTest.aOneBlockMoverConvertsToTheInlineMoverWithItsFacts`.
- `LineGeometryTest`: without the two drawings, the flange-pair check uses the strainer, and the pump-motor assertion is gone.

## 7. Gates

On `83ed8a58`'s tree, with no dev client running:
- `test slowGates`: `slowGates` 188/188. `test` 1638 of 1654, with 16 failures, all present on main (section 8). The new registry test passes.
- Fluid GameTests 37/37 (fresh run id `movers1`), including both `OldMoverConversionGameTests`. The upright pump logged its `mover_compat` warning.
- No in-game check.

## 8. Found on main (not this batch)

- `test` on main `96806b8a` fails 16 tests. Fifteen fail with `NoClassDefFoundError: IChemE`: `IChemE`'s static init registers the creative tab (`f0b86f39`, 2026-10-26), which needs bootstrapped registries, and unit tests do not bootstrap them. Every test that reaches `IChemE.LOGGER` fails (`PipeLineCompatTest`, `FluidFactsCompatTest`, `FluidCheckpointStoreTest`, `AccessoryStoreCompatTest`, `AssemblyStoreTest`, `GaugeAccessoriesTest`). The sixteenth is `V3SimultaneousColumnSolverTest.theDampedCascadeCapIsHonouredAndTheRampsTwoDampingsAreEnough`. Five of them were re-run on a clean main checkout and fail the same way.

## 9. Not changed

- `examples/Generate-Fluid-Models.py` still generates an old box model named `fluid_pump`. It is stale for every device (the models use the `icheme:fluid_device` loader) and no build step runs it.
- The `tools/` rigs and probes that place `icheme:fluid_pump` by command: historical, not run by any gate.
- Code guide: `fluid-runtime.md` (the blocks, `loadedChunk`). Its snapshot line is set at the merge.
- Tool folders: none (no probe or rig left in the code).
