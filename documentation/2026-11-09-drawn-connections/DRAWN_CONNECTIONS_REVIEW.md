# Drawn connections review (2026-11-09)

Owner's question: can a pipe or cable look joined while the engine does not join it, or the other way round, for example after version changes?

## 1. Expected behaviour

A drawn joint says that fluid can pass between two blocks. Fluid passes only where `PhysicalFluidTopology.compile` links two devices. So the drawing must show exactly those links, as built: a closed valve, a cutoff or a generator switched off stops the flow, not the pipe, and keeps the joint. A neighbour in a chunk that is not loaded keeps its registration, so the joint toward it is known without reading its block.

## 2. What the code did (main, 0.23.0)

The block state ran its own join rules on the neighbouring block states (`FluidDeviceBlock.connects`, `transition`, `boundary`), and the rack scanned its own lane points. Four splits:

1. Since 0.22.0 (`2260953dd`), an In-line Pump or Compressor installed with the item registers an in-line machine (`FlowControl.Machine`). The redraw made a mover only of the older `Pump` and `Compressor` laws, so the block drew a plain pipe. Its menu and its FE and data sockets were gone, and a pipe placed beside it drew a branch that the engine never joins (a mover joins along its axis only).
2. A registration whose block is gone (UNBOUND) still joins its neighbours in the engine, and the neighbours drew no joint. Cable facts are not pruned at load either.
3. A legacy standalone FILTER block drew flanges to tanks and other devices that D20 (a pipe on one side) never joins.
4. Racks: the drawing compared recomputed lane points (`RackGeometry.point`), the engine the points each lane saved. After a geometry change they can differ.

## 3. The fix (option A, owner's choice 2026-11-09)

The drawn joints are a projection of the registry through the engine's own join rule:

- `PhysicalFluidTopology.builtFaces`: the faces that `compile` links as built (`across` and `mechanicallyJoins`; column ports left out).
- `FluidWorldAuthority.drawnFaces` (a registered device) and `placedFaces` (a block being placed) read the projected registry, queued events included, on the server thread.
- `FluidDeviceBlock.connectedState`, `getStateForPlacement` and `PipeRackBlockEntity.refreshDrawn` use them. `FluidDeviceBlockEntity.refreshGeometry` sets the fitting from `Device.hydraulicKind()`, so a machine draws as the pump or compressor it is.
- Removed: the block-state join rules, the "a neighbour in an unloaded chunk keeps its saved joint" special case, and the rack's own point scan.
- No format change. CONNECTIONS, the entity's faces and the rack's `joined` are derived state; the next redraw rewrites them, and existing worlds redraw at load.

Effect: findings 1, 3 and 4 are closed for the joints (rack offsets still come from `RackGeometry`). Finding 2 becomes visible: a pipe beside a ghost now draws an arm into the empty cell, as the engine joins it. On a client the placed block shows no joints until the server's block update arrives.

## 4. Owner decisions (not part of option A)

1. Ghosts (finding 2): prune UNBOUND registrations and dead cable facts at load, keep them (now drawn), or name them on the island's status line.
2. A block placed already in an INLINE_MOVER state (templates, `/setblock`) gets the legacy `Pump` law from `FluidWorldAuthority.place`; the item installs a `Machine`. Place a `Machine` there too?
3. Client prediction at placement: none now. Add a client-side guess from the neighbour states, or keep the joints server-only?

## 5. Verification

- Unit: `PhysicalFluidTopologyTest`, four new tests (equipment side by side, an in-line machine on its axis only, rack lanes by saved points, a closed valve and a generator switched off).
- GameTests: `ChunkLoadGameTests` D1 rewritten (the neighbour registered with no block draws joined, no block read); `DrawnJointGameTests` new (the item's pump path draws a pump and no branch).
- Results at `8680edbb6`: `PhysicalFluidTopologyTest` 33 and `PipeLineCompatTest` 8 (it loads the rack's format), no failures. 11 focused GameTests on a fresh world (`tools/simulator-sizing/focused-gametests.init.gradle`), all passed: the two above, the four in `PipeLayingGameTests` (rack lanes, a column nozzle), the modular valve's redraw, in-line movers' sockets (`ConduitLayingGameTests`), two assembly reports and the flow controller's valve plan.
- The whole quick suite, the gate groups and every fluid GameTest run in the final lane.
- Code guide: `fluid-runtime.md` and `pipe-laying.md` at merge time.
