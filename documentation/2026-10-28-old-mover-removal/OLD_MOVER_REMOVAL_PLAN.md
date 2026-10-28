# Old one-block pump and compressor removal: plan

Owner request 2026-10-28: "The old single block process fluid pump/compressor should be removed and use the in-line pump instead". Base `96806b8a` (main, 0.16.1).

## 1. Expected behaviour

A centrifugal pump or a compressor on a plant sits in the line: flanged to the pipe on its suction and discharge, with the line's bore. The game has this since 0.12.0: the In-line Pump and In-line Compressor items install on a horizontal pipe (`FluidDeviceBlock.INLINE_MOVER`, a `PIPE` registration that carries `FlowControl.Pump` or `FlowControl.Compressor`; `Device.hydraulicKind()` compiles it as `PUMP` or `COMPRESSOR`). The old blocks `icheme:fluid_pump` and `icheme:fluid_compressor` stayed as command-only authoring blocks. They duplicate the in-line movers with other rules (D20: they join only a pipe, so one set beside a tank or a heater moves nothing) and other drawings.

After the change: no old block exists. A world that has one finds it as a pipe with an in-line mover, at the same place, facing the same way, with the same identity and settings (requested flow, maximum added pressure or ratio).

## 2. What the code has now

- Blocks `ModBlocks.FLUID_PUMP`, `FLUID_COMPRESSOR` (kind `PUMP`, `COMPRESSOR`; no block item: the item ids `fluid_pump`, `fluid_compressor` are the in-line fittings). Blockstates, block models, loot tables, `block.icheme.*` lang lines, the pickaxe and Create `non_movable` tags, the block entity's valid blocks. No recipe, no JEI plugin, not in the creative tab (the tab shows the in-line items).
- Drawing: `LineGeometry.pump` and `.compressor` draw only these blocks.
- A placed old block registers a portless `Device` of kind `PUMP`/`COMPRESSOR` (`FluidDeviceBlock.onPlace`). The facts save it by kind name with its control.
- The engine keeps portless `PUMP`/`COMPRESSOR` devices as an authoring form: 27 unit tests and the campaign worlds build them directly. Constructed movers are `PUMP`/`COMPRESSOR` with two ports. Both stay (the task's scope).

## 3. Change

1. Remove the two blocks and their assets: registrations, blockstates, block models, loot tables, `block.icheme.fluid_pump`/`fluid_compressor` lang lines, tag entries, `ModBlockEntities` entries, `LineGeometry.pump`/`compressor`, the `PUMP`/`COMPRESSOR` block case in `FluidDeviceBlock.face`. Keep the item ids, item models and `item.icheme.*` lines (the in-line items).
2. Block id aliases (`ModBlocks.BLOCKS.addAlias`): `icheme:fluid_pump` and `icheme:fluid_compressor` resolve to `icheme:fluid_pipe`. A saved chunk keeps the block: its `facing` and connection properties carry over, the pipe's own properties take their defaults (no accessory). The block entity type is the same (`icheme:fluid_device`), so the identity, gauges and sensors stay.
3. Registration conversion where the authority rebinds a loaded chunk (`FluidWorldAuthority.loadedChunk`, the place that already re-registers placed blocks): a pipe block whose registration is a portless `PUMP` or `COMPRESSOR` gets one queued edit to the in-line form, `PhysicalRegistry.convertOneBlockMover`. It keeps the identity, position, facing, control, spec, generator switch and joints; kind becomes `PIPE`; the bore follows option B1; a vertical one follows option V1. The block entity's refresh then draws the mover from the projected registration (attachment presence is immediate, as at install). No solve, no forced update: an engine event, applied on the engine schedule. A world that never loads the chunk keeps the old registration, which the engine still compiles as before.
4. Tests: the four GameTests that place the old blocks move to an in-line mover (a `FLUID_PIPE` state with `INLINE_MOVER`), each with the same line and physics. A new GameTest checks the aliases and the conversion through a chunk unload and reload. A unit test checks `convertOneBlockMover` keeps every fact. `LineGeometryTest` loses the two drawings.

No facts-schema or shape change: the registration record is unchanged; only its kind and bore values change, through an ordinary edit. So no corpus image. The block alias is a registry fact outside the fluid facts.

## 4. Owner options (choices the physics does not force)

**B. The converted mover's bore.** The old block registered 0.05 m, but each half of its link took the bore of the pipe it met (`PhysicalFluidTopology.half`, owner decision 2026-10-16). An in-line mover uses its own bore for both halves.
- **B1 (recommended)**: the largest bore of the pipes it meets on its axis, else 0.05 m. Same hydraulics as before where both sides have one bore (every line laid by the tool); where they differ, the smaller side's half-block now takes the larger bore. Drawn at the line's size.
- B2: keep 0.05 m. Changes the hydraulics on every line that is not 0.05 m: on a 0.2 m line, 1 m at 0.05 m bore instead of 0.2 m (at 0.01 m³/s, 5.1 m/s instead of 0.32 m/s), and the mover is drawn with reducers.

**J. What it joins.** The old block joined only pipes (D20). An in-line mover is a pipe: it also joins a tank, generator, void or filter block standing on its axis.
- **J1 (recommended)**: join as an in-line mover does. Physically right (a pump flanged to a nozzle pumps). A world that had an old pump touching such a block on its axis starts to flow there after the conversion.
- J2: give the converted pipe joints only on the axial faces that met a pipe. Keeps the old connectivity, but marks the mover as a laid pipe and refuses a later non-pipe neighbour on that face.

**V. A vertical old block.** Only `/setblock` could make one since 2026-10-16 (placement faced horizontally; older worlds were reset by D4). An in-line mover is horizontal only (owner rule 2026-10-25).
- **V1 (recommended)**: leave its registration as it is, with a warning in the log naming the position. It keeps pumping (no fact lost), drawn as a plain pipe without controls; breaking it removes it.
- V2: it becomes a passive pipe on its axis; the log names the position and the control it lost. This discards a fact (AGENTS.md forbids it without the owner).

## 5. Gates

`./gradlew test`, `test slowGates`, the fluid GameTests (fresh run id). No dev client running. No in-game check (owner feedback 2026-10-18, 2026-10-28).
