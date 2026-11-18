# Nozzle join: one rule for "a pipe meets this nozzle" (2026-11-17)

Owner, on the review plant in a dev client: "top connection still broken", then "I think you need better connection
checking for the multistructure system instead of relying on 'the block required is there'". Approved: "Yes, start it".
Branch `claude/nozzle-join-b7040b`, cut from `claude/column-testing-sizing-26b5b9` at `e25dae469`.

## Summary

- **Expected.** A pipe in front of a nozzle with a joint towards it is bolted to it: the pipe's block draws an arm and a
  flange towards the nozzle, and the nozzle is drawn at the pipe's size, not at its default under a blind flange. Undone
  when either the pipe or the structure goes, in either placing order.
- **What the code did.** Three readers each decided the join their own way. The drawn joint (`FluidDeviceBlock.connectedState`
  → `Placement.drawnFaces` → `PhysicalFluidTopology.builtFaces`) joins a face only to a registered device. A column is
  no fluid device: it has no registration and no ports, so no pipe ever drew a joint to a column nozzle, and the nozzle
  (`EquipmentStructure.refreshNozzles`, which reads the pipe's block state) stayed at its default. A line that arrives
  head-on looked nearly joined, as a dead end is drawn straight through to a blind flange; the overhead line, which bends
  at its first block, showed the blind flange sideways. The assembly walk and the column start passed, because both
  only asked for a pipe with a joint bit there.
- **Built.** `science/equipment/NozzlePorts.at(shape, x, y, z, dx, dy, dz)`: the port whose flange cell and outward step
  these are, and `front(port)`. The shell cell's `port(Direction)`, the assembly walk's `port(Shell, BlockPos, Direction)`
  and `ColumnStarts.build` use it. `runtime/fluid/StructureNozzles`: the recorded structures that are no fluid device, by
  chunk of their box, with `faces(cell, outward)` and `origin(cell)`; kept where `ChunkBinding.indexStructure` is (the
  authority's load and `structure(origin, structure)`). `builtFaces(at, device, nozzle)`: where no device stands across a
  face of a lane-0 pipe without ports, the face is joined when the predicate says a nozzle there faces back.
  `Placement.drawnFaces` and `placedFaces` pass `StructureNozzles::faces`. The two-argument `builtFaces` meets no nozzle.
- **Not a flange-cell map.** The brief asked for a map from each nozzle's flange cell. The ports come from the shape's
  `Geometry`, its whole mesh: such a map would build it for every recorded column at the load, loaded or not. The index
  keeps chunk → (origin, shape) and finds the port only when a pipe beside the structure is drawn.
- **Kinds covered** (`!EquipmentShape.fluidDevice(kind)`): COLUMN, STIRRED_REACTOR, ADSORBER, TRICKLE_BED_REACTOR,
  EBULLATED_BED_REACTOR, CYCLONE, FCC_CONVERTER, DELAYED_COKER, FLUIDIZED_BED_BOILER, FLUIDIZED_BED_GASIFIER,
  GRATE_BOILER, FIXED_BED_GASIFIER, and MOTOR, GENERATOR, GEARBOX. The three drivers are expected to have no nozzles,
  but that is not checked. Every fluid device keeps its registration's ports, unchanged.
- **Redraw orders and chunk load.** Every order records the structure first, then redraws its pipes; a chunk load
  redraws a pipe from the registry and the index, and the re-alignment now does it for a chunk loaded before the
  fluid world opened (3 lines in `RegistryRealignment.restate`). Details below.
- **The column start** keeps `NOZZLE_UNREGISTERED`. Option, not built: `ColumnStarts.build` lists the block in front of
  **every** port, and `startColumn` takes any registration there into the column, with no check that it meets the nozzle.
  A nozzle that the blueprint does not walk (a spare), with a pipe of another line passing its front cell (no joint
  towards it), would take that pipe into the column unit. A fix is one test in `startColumn` (lines 1299-1300): take a
  nozzle's registration only if it `mechanicallyConnects(opposite(outward))` and is a pipe, else leave the nozzle unconnected.
  The rig's `free` keeps every line off a nozzle's front cell, so no current test or plant reaches this.
- **Open for the owner.** The reactors and the other kinds above now draw a pipe joined to their nozzles, though the
  network carries nothing there (they are not fluid devices yet). This is the column's state while it is not running. A
  narrower rule, COLUMN alone, would be one condition in `StructureNozzles.index`.
- **Gates.** `PhysicalFluidTopologyTest` (2 new tests): the three-argument face drawn and not drawn (no nozzle, a nozzle
  facing away, no joint that way, a rack lane, a device across); `NozzlePorts.at` and `StructureNozzles.faces` on every
  port of a 2 m column, never at the opposite step, nothing for a drum (a fluid device) or a removed record.
  `DrawnJointGameTests.aPipeOnAColumnNozzleDrawsItsJointInEitherOrder` (batch `drawn-joints-nozzle`, its own batch
  because its box reaches beyond its structure): top nozzle (line bending at its first block) and feed nozzle, both
  orders, a pipe removed and the column removed. `AssemblyGameTests.Plant.joined(Vessel, Role)` and `unjoined()`: block
  state joined, face flanged, nozzle at the pipe's size; `aPlantBuiltAsDesignedValidatesComplete` and the review rig
  (`ColumnOperationReviewWorld.aCrudeColumnRuns`, after the valves) assert `unjoined() == null` for every nozzle with a
  plant pipe in front. Nothing was run: a dev client was open. The fluid GameTests run by namespace only.
- **Results.** No saved format, fact or shape field changes; `FORMATS.md` does not change. `Device.joins`, islands, the
  solver and every reference are untouched by construction: only `drawnFaces` and `placedFaces` pass the predicate, and
  nothing the network compiles reads them. Not yet confirmed by a test run.

## Redraw orders and chunk load

Pipe placed or removed: `updateShape` → `connectedState`, and the pipe's `refreshGeometry` or the
  shell's `neighborChanged` → `refreshNozzlesAt`, as before. Structure placed after its pipes: `EquipmentStructure.place`
  records it (line 149, which sets the index), then `reconnect` (154) redraws the pipes in front of its nozzles, then
  `refreshNozzles` (155). Structure removed: `dismantle` drops the record (393), clears the cells, then `reconnect` (404).
  Rebuilt (`StructureRefresh.refresh`): `record` (300), then `settle` → `reconnect`, `refreshNozzles`. `reconnect` runs
  for every kind. Each order records first, then redraws.

A pipe's `onLoad` (authority open) calls `refreshGeometry` → `connectedState`, which draws from the
  registry and the index. The index is filled from the saved structure records when the authority opens, so the existing
  review world's overhead pipe takes its joint at its chunk's next load, and its nozzle redraws through
  `refreshNozzlesAt` when the shell is loaded. A chunk loaded before the authority opened (the spawn chunks) gets no
  `onLoad` redraw: `ChunkBinding.loadedChunk` only binds the block, and the re-alignment restated only a changed fitting.
  `RegistryRealignment.restate` now compares the connected state too: 3 lines. Not covered: a pipe whose chunk loads
  while the column's chunk is not loaded keeps a correct block state, but the nozzle redraws only at the pipe's next
  change: `EquipmentShellBlockEntity.onLoad` calls no `refreshNozzles` (read, not traced through `restateLater`). The nozzle sizes are saved on
  the shell cells, so this is a first-load case of an old world only.

Main-thread review of `2abc30a5a`: `StructureNozzles` answered from the first structure whose box held the cell, so a
nozzle inside another structure's (bounding) box could be missed. It now asks every one; `origin(cell, outward)`
replaces `origin(cell)` (only the tests call it). Gate: `PhysicalFluidTopologyTest.aNozzleInsideAnotherStructuresBoxIsFoundInEitherOrder`.

## Test run (2026-11-17, dev client closed)

- Selected classes (`affected.mjs select --since e25dae469`, 371 picks): `test` 1238 tests, 3 failed:
  `SizingPageTest.theDoorFitsInEveryViewForEveryItemInChinese` and `LanguageFilesTest` (both until the translation commit;
  `LanguageFilesTest` was in the selection) and `PhysicalFluidTopologyTest.aPipeBesideANozzleFacingItDrawsThatFace`, a
  fault of the test (its predicate threw on every face, not only across the tank). Fixed; the class alone: 37 of 37.
  `holdGates` 80 tests, `columnGates` 5 (from the cache), `equipmentGates` 34: none failed.
- Fluid GameTests (`icheme_fluid_test`, 116 tests; the review rigs are in `icheme_review` and did not run): 114 passed,
  2 failed, both new checks. `drawn-joints-nozzle`: the test's own bend search asked for air below the bend, which stands
  on the column head; fixed, not run again. `assembly-complete` (`Plant.unjoined`): 7 nozzles at the ends of routed lines
  not drawn as joined, on the exchanger, the drum, the pump and the column's REFLUX: at a route's last pipe the face is
  not flanged; at its first the nozzle stays at its default. The column's VAPOUR_OUTLET (a route's first pipe) and its
  straight boundary lines pass. These are fluid devices too: not the new rule's. Diagnosis open; see the open items.

## The 7 unjoined nozzles: trace and fix (2026-11-17)

Main thread's choice: trace first, then fix where the trace points. One trace run (log lines in `computeFaces`,
`refreshGeometry`, `FluidDeviceBlockEntity.setBlockState` and `refreshNozzles`; patch in `tmp/nozzle-join/trace.patch`,
never committed):

- A route's last pipe (the column's REFLUX): `Plant.pipe` set the default state (mask 0). The pipe took its joints later,
  from its neighbour's placement, through `updateShape`. `computeFaces` ran (mask 0) just before `setBlockState 0->33`:
  `server.execute` on the server thread runs at once, before the returned state is installed. Nothing recomputed the faces
  until the plant was removed, so the face to the nozzle stayed unflanged.
- A route's first pipe (the exchanger's SHELL_OUTLET): the shell heard the placement only at mask 0
  (`refreshNozzles ... joined=false`). The later 0->3 came through a shape update, which does not notify neighbours.
  So the nozzle stayed at its default.
- `refreshGeometry` never ran for these hand-set pipes.

A player's pipe is drawn joined at placement (`getStateForPlacement` → `placedFaces`), nozzles included, and the
laying tool sets joined states. So only the rig's bare default state reaches this gap. Fix, in the rig only:
`Plant.pipe` places `FluidDeviceBlock.withFaces(default, world.placedFaces(...))`, the state a player's placement gives.
`world/**` is unchanged. Fluid GameTests: 116 of 116 (with the `drawn-joints-nozzle` fix). No unit test is selected:
only the GameTest source set changed. Finding, not fixed: the same immediate `server.execute` in
`FluidDeviceBlock.updateShape` leaves a neighbour's faces computed from its old state whenever a shape update changes
its joints. Inferred for a player's sequence, not traced: a pipe with a fitting, then a pipe placed on its axis; the
fitted pipe's face towards the new one is drawn from before. The option is to refresh the faces in
`FluidDeviceBlockEntity.setBlockState` when the connection bits change, and drop that `server.execute`. The selection
named `LanguageFilesTest` in the first run; it is left out of later picks until the translation commit.

## Hot-file lines

| File | Lines (after the change) | What |
|---|---|---|
| `FluidWorldAuthority` | 84-85 | the `nozzles` field |
| `FluidWorldAuthority` | 129 | load: `nozzles.index` beside `chunks.indexStructure` |
| `FluidWorldAuthority` | 328-329 | `structure(origin, structure)`: the same, both ways |

`EquipmentStructure`, `ShapeRasterizer` and `EquipmentShape` are not edited.

## Tests owed

Selected with `node tools/merge-lane/affected.mjs select --since e25dae469`: `test` 186 classes, `holdGates` 5,
`columnGates` 1, `equipmentGates` 4 (picks in `tmp/nozzle-join/picks.txt`, 371 lines, git-ignored). With no dev client:

```
./gradlew -I tools/merge-lane/record.init.gradle -PlaneTests=tmp/nozzle-join/picks.txt test holdGates columnGates equipmentGates --max-workers=4
./gradlew runFluidGameTestServer -PfluidGameTestRunId=nozzle-join -PfluidGameTestNamespaces=icheme_fluid_test --max-workers=4
```

`SizingPageTest.theDoorFitsInEveryViewForEveryItemInChinese` fails until the translation commit: expected.
