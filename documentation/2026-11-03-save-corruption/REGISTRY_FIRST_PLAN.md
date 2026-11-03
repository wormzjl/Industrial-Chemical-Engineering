# Registry first: plan for W5a (registry facts) and W5b (re-alignment)

Batch `2026-11-03-save-corruption`. The decisions are in [SAVE_CORRUPTION_REVIEW.md](SAVE_CORRUPTION_REVIEW.md), section 8, all decided on 2026-11-03:
- S5 registry first;
- S7 never overwrite;
- S9 no item drop;
- S10 unknown blocks register;
- S11 held.

The rule is in review section 5. This plan says how to build it. Base: `main` at `744ef4513` (0.19.3). W1 to W4 (the corruption ladder) follow in a later plan.

## 1. Goal and done criteria

After any load, the IChemE layer of the world matches the fluid registry:
- every registration that owns a block has that block, with the kind, facing, fittings and paint the registration holds;
- a registration that cannot be placed is held and listed;
- a device block the registry never saw is registered from the block.

Done means:
- facts schema 8 is on the branch with its corpus image;
- the GameTests of section 7 pass;
- an ordinary save and load re-aligns nothing;
- the code guide is updated.

## 2. Which registrations own which block

| Registration | How it is recognised | Its block |
|---|---|---|
| One-block device (reservoir, pipe, generator, void, inline filter; pipes with a cutoff, valve, filter or inline mover) | `position.lane() == 0`, `ports` empty, not `HEAT_SIDE` | The `FluidDeviceBlock` at `position` |
| Rack lane | `0 < lane < PORT_LANE` | The `PipeRackBlock` at the lane's cell, which holds every lane of the cell |
| Structure member (vessel, mover unit, heat side) | `ports` not empty, or `HEAT_SIDE` | The equipment structure whose record lists it (5.3) |
| Column port | `lane == PORT_LANE` | None: it is part of its column. Re-alignment skips it |

## 3. W5a: registry facts (facts schema 8)

### 3.1 What is added

A new part of the ledger snapshot, `WorldTopologyLedger.Builds`. It holds the facts that live only in block entities today.

| Record | Key | Fields | Source today |
|---|---|---|---|
| Line paint | device identity: a pipe, a one-block device or a rack lane | primary, secondary (0xRRGGBB, or bare) | `FluidDeviceBlockEntity.paint/secondary`, `PipeRackBlockEntity.Lane.paint/secondary` |
| Structure | origin position | the shape as SNBT text, and the paint | `EquipmentShellBlockEntity` (`writeShape`, `Paint`) |
| Rack frame (schema 9, step 5) | rack lane identity | the facing and the shape of the rack cell the lane lies in | `PipeRackBlock.FACING`, `PipeRackBlock.SHAPE` (block state) |

Facts the first draft listed that step 1 found to be derived. They stay out of the registry:
- **The rack lanes.** `PipeRackBlockEntity` already rebuilds a lane from its registration (line 299). It takes the option from `position.lane() - 1`, the width from `slots`, the arms from the joints and facing, and the bore from the geometry. The rack's drawn `Frame` (its steel) and `Joined` are not saved at all, so they are derived. A rack lane needs its paint, which is a line paint.
- **Corrected in step 5: the rack cell's frame is a fact.** Its facing and shape are block state, decided once at placement from the neighbours and the player's look (RACK_JUNCTION_PLAN J1, J2). A lane's registered face points do not fix them. Over every valid lane of every frame and seven bores, 544 of 1,376 lane cases fit more than one frame, and 228 fit frames of different shapes (a straight cell and a tee's leg give the same points; `tmp/rack-frame/ambiguity.jsh`). So the registry keeps each lane's frame, keyed by the lane's identity. It leaves with its lane, as paint does (facts schema 9).
- **The nozzle sizes.** The shell's `Nozzles` are per-cell drawing codes that `EquipmentStructure` redraws from the bores of the pipes the nozzles meet (`ShapeRasterizer.cellCodes`). Those bores are in the registry.
- **The member identities.** A structure's registrations sit at the node cells that its shape gives at its origin.
- **The layout stamp, the box and the roof level.** These are derived from the shape and the contents.

How the shape is held:
- **Shapes as SNBT text.** The shape is written by `EquipmentShellBlockEntity.writeShape` and read by `readShape` → `ShapeRepair.read`. The shape keeps its own compat rules and corpus (`compat/equipment/shapes-N.snbt`), and there is no second shape format. SNBT sorts its keys, so equal shapes give equal text.
- **Text size.** A facts text may be up to 1 MiB (`FluidCheckpointCodec.MAXIMUM_TEXT`), and a shape is a few KB. Identical shapes share one entry of the section's text table.
- **A retired kind.** A structure whose shape reads as a retired kind is dropped from the registry with a log line. The shell's load removes it whole (D6).

### 3.2 Encoding

- `FluidFacts.world`/`World`: two lists are appended after `columns`: paints by identity, then structures by origin. Every entry is a record, as the existing ones are. Absent lists read as empty, which is the behaviour before the field.
- `FluidFacts.SCHEMA` goes from 7 to 8, with the schema comment line.
- Validation on read:
  - a paint is a line colour (`Paint.line`) and not bare on both sides;
  - a structure's origin is a block (lane 0), and its paint is a colour pair;
  - duplicates refuse;
  - a shape that `ShapeRepair` repairs is logged as on a shell load;
  - a shape that does not read refuses, as any damaged fact does until W2 lands.
- `WorldTopologyLedger.Snapshot` carries `Builds` (one more constructor overload, as for `columns`). `sameWorldBody` compares it by reference, so a build edit rewrites the topology unit at the next save.
- How the ledger holds them (built in step 1):
  - The ledger keeps live maps, and an edit is one map update.
  - The immutable `Builds` copy is made at the next `snapshot()` after an edit, and at no other time.
  - A build edit does not bump the registry's `version`, so the registrations, events and columns are not copied again. The snapshot shares them with the previous one.
  - `Structure.of` makes the shape's SNBT text once, when the structure is recorded. A save writes the stored text and encodes no shape.

### 3.3 Write paths: every edit reaches the registry

All on the server thread, as direct ledger updates: `WorldTopologyLedger.paint(id, …)` and `structure(origin, …)`, built in step 1, reached through `FluidWorldAuthority.paint` and `structure` (step 2). A build fact changes no flow, so it needs no queued event and does not bump the registry's `version` (3.2).

The writers, listed by `grep` before coding (step 2): line paint has one, `PipeLaying.lay`, for pipes and rack lanes alike. Structures are placed only by the constructor's build, whole. Nothing repaints a laid line or a built structure.

| Edit | Hook (built in step 2) |
|---|---|
| Line paint of laid pipes and rack lanes | `FluidWorldAuthority.lay(…, primary, secondary)` paints the new identities as it registers them. The blocks copy the paint as they bind (3.5), so `PipeLaying` no longer paints a block, and the pipe block's paint setter is gone |
| Structure built | `EquipmentStructure.place`, for a whole build (`rows` covers the shape) |
| Structure refreshed to a new layout | `EquipmentStructure.refresh`: the record follows the structure to its shifted origin, cells left out or not |
| Device removed (any path) | the ledger drops the line's paint when an applied edit removes its identity: one place for every removal path, no hook |
| Structure dismantled, or removed as a retired kind | `EquipmentStructure.dismantle` and `removeRetired` |

The shell's `bind(shape, cell, paint)` is no hook: it runs once per cell, and a structure's origin cell may be no shell. A rack's lanes and joins need no hook: they are rebuilt from the lane registrations (3.1). A rack frame (schema 9) has one writer, `PipeLaying.lay`, which records each new lane's cell frame; a rack's shape never changes after it is placed (J1). It leaves with its lane in the ledger, as paint does. A rack's bind learns the frame of a lane that has none, unless another lane of the cell holds a different one (then the block is not the cell the registry knows). A nozzle size change is a pipe change, which the registry already holds.

### 3.4 Learning from blocks (worlds saved before schema 8)

Built in step 2, at the same points as the restatement (3.5): a block whose fact the registry has no record of teaches it.
- A painted pipe or rack lane whose line the registry holds bare records its paint at bind. Paint is born with its registration at the lay, so a bare line with a painted block is a world saved before schema 8, or a block registered from what it holds (S10).
- A current vessel with no record records its shape and paint at its restate, which runs once per vessel load when every chunk of it is ticking.

A world saved at schema 7 therefore fills its records chunk by chunk as it is played. Until a chunk loads once:
- one-block devices and rack lanes are re-placeable without a record (bare paint);
- structures are not. If their blocks are missing, they keep today's behaviour and are listed as "cannot be rebuilt: saved before the registry held its shape".

### 3.5 Block-entity copies

The block entities keep their copies, because the client draws from them. Each takes the registry's fact where they differ (registry first), as `bindIdentity` already does for bore and joints:
- pipes and one-block devices: `FluidDeviceBlockEntity.bind(world, id)`, now the one bind of both load paths (`onLoad` and `FluidWorldAuthority.loadedChunk`), and `bindIdentity`;
- rack lanes: `PipeRackBlockEntity.bind(world)`;
- structures: `EquipmentStructure.restate`, once per vessel. A vessel's cells share one paint, so the cells are walked only when the controller's paint differs from the record. A record of another shape waits for the re-alignment (4.4).

Each is one map read per block or vessel. A block entity never writes the registry except through 3.3 and 3.4.

## 4. W5b: re-alignment

### 4.1 Held registrations (S11)

- **Meaning.** A held registration keeps its contents and joins no flow.
- **Compilation (built in step 3).** Column suspension leaves the network through `Device.joins`: a suspended device joins nothing. Holding reuses that path. `Device` gains `held`, and a held device joins nothing, as built either (it has no block). A held vessel is an island of its own and rests. A held pipe leaves dead ends on both sides ("NO FLOW: unconnected pipe"), as a missing pipe block does. Every `with…` copy keeps the hold; a column's port cannot be held.
- **Holding and releasing.** Both are topology edits at the registration's next revision, submitted through the registry's queue like any network change (`FluidWorldAuthority.hold(id, held)`). Their results arrive on the engine schedule. A hold must travel with its event: `PhysicalRegistry.load` matches the saved islands against a compile of the applied registry, so a hold that took effect before its edit applied would refuse the load.
- **State, not a fact.** Holding is recomputed at every re-alignment of its chunk. The topology unit's state section carries it: a list of the held applied devices, and per queued event the edits whose replacement is held. The registration record, which facts share, never carries it, so facts do not change (no schema bump). A state written before holding reads as none. A state reset or a load from facts releases a hold until the chunk's next re-alignment.
- **Reason.** Not stored. The listing reads it from the world when asked: no level for the dimension, the chunk not loaded, or the block the cell holds.

### 4.2 The queue

- `FluidWorldAuthority.loadedChunk` keeps its binding and its "register a block that has none" (S10). It then queues the chunk's registrations for re-alignment, each once (built in step 4: a queue per dimension in the authority, `nextRealignment`). It places no block itself.
- The queue is served on the level tick (`RegistryRealignment.serve`, from `IChemE.onLevelTickPost`). Each tick serves at most 32 devices and stops after 1 ms, whichever comes first (section 6). Measure both with the counters.
- A one-block device is served once its own chunk is loaded. Neighbours in a chunk that is not loaded keep their saved joints, and their own load settles both sides (`FluidDeviceBlock.connectedState`, review D1). A registration whose chunk unloaded before it was served is dropped from the queue; its next load queues it again. Structures (step 6) wait until every chunk they need is loaded and block-ticking, as refresh requires (`shouldTickBlocksAt`).
- The queue never loads a chunk and never solves. A dimension that has no level holds its registrations and lists them (built in step 4: at the authority's start, every such registration but a column's port is held in one queued event, with one warning line).

For each registration of the chunk:

1. Skip a column port.
2. **One-block device** (built in step 4, `RegistryRealignment.realign`):
   - Block present and same kind: restate the block state (facing, fittings) and the block entity (joints, bore, paint) where they differ. A lever's open or closed position stays as drawn, because it follows the commit (`refreshGeometry`). Connections alone never count as a difference.
   - Block missing (air or a replaceable block), or another `FluidDeviceBlock` at the cell: place `FluidDeviceBlock.stateFor(device)` with the restore flag and without shape updates, then `bindIdentity(id)`. Then connect it and update its neighbours' shapes, as the laying tool does: the block's joints are set before any neighbour reads them.
   - Another block at the cell: hold.
   - A held registration whose block stands again (present, restated or placed) is released.
3. **Rack lane:** handled per cell. A rack block present with the lane: restate. A rack missing: place a `PipeRackBlock` if the cell is free, and let its block entity rebuild the lanes from their registrations, as it does at load. Else hold every lane of the cell.
   - Built in step 5 (`RegistryRealignment.lane`). The cell's frame is the one the registry holds for its lowest lane identity. A rack present with another frame takes that frame (same block, no lane removed) and binds again. A rack present without the lane binds again. A free cell gets a rack of that frame, which binds. A lane its cell still does not carry after the bind is held, and so is a lane whose cell another block holds.
   - Lanes saved before schema 9 have no frame. A free cell then gets the first frame that takes every lane of the cell at its registered points, straight cells first (`PipeRackBlockEntity.frames`). The lanes stand where they stood, but a tee or a corner may come back straight. With no such frame, the lanes are held.
   - Rack blocks follow the restore flag too: `PipeRackBlock.onRemove` removes no lane while `FluidDeviceBlock.quietly` runs.
4. **Structure member:** handled per structure (4.4).

### 4.3 Placing one-block devices

- **`FluidDeviceBlock.stateFor(Device)`.** A new function, the inverse of the arguments `loadedChunk` and `onPlace` pass to `place(...)`: kind → block, `FACING`, cutoff, pressure valve, filter, `INLINE_MOVER` (`FluidDeviceBlock.fittings`). No connection and no sight glass. Null for a device no such block stands for: a structure's (it has ports), a rack lane, a column's port, a heat side.
- **The restore flag.** The laying tool's `laying` flag, renamed `FluidDeviceBlock.registered`, set through `FluidDeviceBlock.quietly(...)`. While it is set, `onPlace` does not register, and `onRemove` neither removes the registration nor recovers instruments. The placer binds the existing identity instead.
- **A replaced IChemE block** drops no item (S9) and gives no recovery. Its old block entity's instruments are not recovered: accessories belong to the registration, by identity.
- **The kind check.** A block binds only a one-block registration of its kind (`FluidDeviceBlockEntity.standsFor`). `bindIdentity` refuses another: an `IllegalStateException`, as for another position. `bind(world, device)` returns false instead, at `onLoad` and at `loadedChunk`, and the cell waits for its re-alignment, which `loadedChunk` queues.

### 4.4 Structures

- **Shell present, record shape equal:** bind as today.
- **Shell present, shapes differ:** rebuild to the record's shape. Run `EquipmentStructure.refresh` with the record's shape, origin and paint. This undoes a constructor edit made after the last fluid save, which is registry first by design.
- **Shell missing:** the same refresh, with no shell found. Its plan places every cell where the world is free and lists the blocked ones.
- **The shift.** A restore uses shift 0, because the registry knows the origin. `EquipmentRefresh.anchor` is used only for a layout refresh.
- **Blocked cells.** With any cell blocked, the structure's members are held. The shells written get stamp 0 (`layoutIncomplete`), so a later load completes it once the player clears the cells.
- **Binding.** Members bind through `restoreFluidBinding`, with the record's shape. Its geometry branch (`reregister` when `spec.geometry` differs) now reads the record's shape. A geometry that differs for the same shape is a code change to `fluidGeometry`, and the registry takes the derived geometry, as today.
- **Built in step 6** (`EquipmentStructure.serveRealignment`, `realign`):
  - The queue: the authority indexes each record's origin by the chunks its box spans (built at start, kept by `structure(origin, record)`). A chunk load queues the structures it touches (`EquipmentStructure.realignLater`). `RegistryRealignment.serve` serves them after the devices, within the same 1 ms: at most 8 checks a tick, none after a rebuild.
  - Waiting: a structure waits while the layout refresh has it queued, and until every chunk of its box and of the blocks beside it is block-ticking. One with a chunk of its box not loaded leaves the queue; its next load queues it again.
  - The check: a structure whose layout cells all stand as its own (a shell cell of its origin and shape, an interior cell) is left as it is, and holds on its registrations are released. Stamps stay the layout refresh's.
  - The rebuild shares `survey`, `write` and `settle` with the layout refresh. Same-origin cells of another shape, and interior cells that no shell cell reaches, count as the structure's own. When cells of its origin reach beyond the record's box, the region is widened and read again on a later tick. Every shell cell takes the record's paint. A shell cell that is not a node releases its fluid identity.
  - Removal: `dismantle` follows the restore flag (`FluidDeviceBlock.quietly`).

### 4.5 Operator commands and notices

- Under `/icheme`, permission 2, next to `equipment` (built in step 3, `HeldDevicesCommand`):
  - `devices held` lists each held registration with identity, kind, dimension, position and reason (what its cell holds, no level, chunk not loaded), at most 20, then a count of the rest.
  - `devices drop <id>` removes a held one through the break path. Its contents are booked as removed, and it drops no item: its accessories leave the accessory store (`InstrumentService.discardHost`), and a filter's cake gets no recovery. A device that is not held is refused.
- **Notices:**
  - the affected island's status line names the held devices;
  - a chat line tells operators on join while any registration is held;
  - a re-alignment log line has the form `fluid_realign dimension=… checked=… placed=… restated=… held=… released=…` (built in step 4): one line per serving tick that changed something, because the queue serves devices, not chunks. Learning is counted nowhere: it happens at bind (step 2).
- **Text.** The command replies are operator text in English, as every other `/icheme` command's (`Component.literal`): no lang keys. The chat line and the status words are player text: new `en_us` keys in step 7, with every other lang file updated in the same commit (AGENTS.md, Translations).

## 5. Order of work

Each step is one commit and runs only the tests that its files can load.

| Step | Content | Tests |
|---|---|---|
| 1 | `Builds` in the snapshot, schema 8, encoding, validation. No behaviour change. **Done** (see the batch README) | `FluidFactsCompatTest` (with the new `compat/fluid/schema-8` image), facts round trip |
| 2 | Write paths (3.3), the block-entity restatement (3.5) and learning (3.4, moved here from step 8). **Done** (see the batch README) | GameTests: the painted lay in `PipeLayingGameTests` (registry holds the paints, restatement, learning, paint leaves with a dismantled lane); `EquipmentCompatGameTests` structure record (built, restated, learned, dismantled). The save and reload round trip is the step-1 unit tests' |
| 3 | Held registrations: compilation, state, commands (4.1, 4.5 commands). **Done** (see the batch README) | `PhysicalFluidTopologyTest` (a held tank is its own island, a held pipe parts its line); `WorldTopologyLedgerTest` (a hold survives an exact load, queued or applied, and never reaches facts). GameTest `RegistryFirstGameTests`: hold, list, then drop |
| 4 | `stateFor`, the restore flag, the kind check, the queue, one-block re-alignment (4.2, 4.3). **Done** (see the batch README) | `stateFor` round trip over every kind and fitting. GameTests a to d (section 7) |
| 5 | Rack cells placed again from their lane registrations (4.2 item 3), with the rack frame as a fact (schema 9). **Done** (see the batch README) | GameTest e; `WorldTopologyLedgerTest` (a frame leaves with its lane); `FluidFactsCompatTest` with the new `compat/fluid/schema-9` image |
| 6 | Structures (4.4). **Done** (see the batch README) | GameTests f and g. `equipmentGates` only if refresh code changed |
| 7 | Notices, lang keys (4.5) | `LanguageFilesTest`; `tools/localization/check.js` |
| 8 | Learning from blocks (3.4): built in step 2; what is left is its world-level check | GameTest h, and the schema-7 image still loads |

## 6. Cost: no lag at edits, saves and loads

Owner requirement (2026-11-03): the system must be lag-free, above all during saving.

| Moment | Server-thread work W5 adds | Bound |
|---|---|---|
| A build edit (paint, structure) | one map update | O(1) |
| The save's capture after a build edit (`FluidWorldAuthority.capture`, the only taker of a ledger snapshot in play) | one copy of the two build maps; registrations, events and columns are shared | once per save, and only after an edit |
| A save with no build edit | nothing: the topology unit is reused (`sameWorldBody` compares `builds` by reference) | zero |
| A save after a build edit | one re-encoding of the topology unit, the same cost as after placing a pipe today; shape texts are already made | one unit per save |
| A load | each structure's SNBT parsed once, at world open | not during play |
| A block's bind at load (pipe, rack) | one map read for its paint; a rack also reads the frames of its cell's at most 14 lane positions; a write only when it learns | O(1) per block |
| A vessel's restate at load | one map read for its record; the shell cells walked only when the paint differs | O(1) per vessel, as a rule |
| Chunk load | the chunk queued; no block placed, no solve | O(devices of the chunk) to queue |
| Re-alignment | at most 32 devices and 1 ms per tick (4.2) | 1 ms per tick |
| A structure's check (4.4) | one read per layout cell, once per load of the structure; no write while it stands as recorded | at most 8 checks in the tick's 1 ms; a check is not split, so its cost grows with the structure's cells |
| A structure's rebuild (4.4) | the cells written, as a layout refresh writes them | at most one per tick, only after damage |

Measurement:
- The re-alignment's cost goes into `FluidRuntimeDiagnostics` (built in step 4): `realignChunks` (loaded chunks that queued registrations), `realignChecked`, `realignPlaced`, `realignRestated`, `realignHeld`, `realignReleased`, and `realignNanos` (the serving ticks' time).
- One fluid GameTest batch reads them. No campaign is needed: no solver code changes, only `compile` for held devices. Run the campaign only if step 3 changes `compile` beyond held devices.

The save itself, before W5 (outside this plan, proposal W6 in the review, section 9):
- A save is prepared on the server thread and written on Minecraft's IO worker (`FluidSavedData`). The preparation captures the authority and encodes and hashes every unit whose island changed since the last save.
- f3 tables, 10,000 lone tanks, cold save: capture 7.6 ms, then prepare 48.4 ms. A world whose islands all keep flowing pays about that at every autosave: one tick of lag.
- The f3 warm-save prepare time (40.3 ms) is the tests' self-verification (`icheme.fluid.scheduler.verify`), which re-encodes every reused unit. A game save does not run it.

## 7. GameTests (fluid GameTest source set)

Each test edits the world or the registry directly, so it needs no crash. Each removes everything it registers, because GameTest areas are not cleared between batches. Use a fresh `fluidGameTestRunId` after a failed run.

Structure records persist in the GameTest world as registrations do. Once re-alignment places structures (step 6), a record left behind would rebuild its structure in a later run. Step 4 re-aligns one-block devices only, so the check moves to step 6. Before step 6, check that every GameTest that places a structure ends with its dismantle (breaking a shell or interior cell runs it), or clears its record (`FluidWorldAuthority.structure(origin, null)`). `EquipmentCompatGameTests` clears cells without the dismantle on purpose in two tests.
- Checked before step 6: every such test ends with its dismantle or a retired removal, both of which clear the record. The exception is `CoolingTowerGameTests`, which leaves its tower whole; re-alignment finds nothing to do there.

- **a. Broken pipe.** A registered pipe whose block was set to air behind the authority's back is placed again with the same identity.
- **b. Broken vessel.** A registered one-block reservoir removed the same way comes back with its contents.
- **c. Wrong kind.** A filter block at a pipe registration's cell is set back to a pipe and dropped no item.
- **d. Blocked cell.**
  - A pipe registration whose cell holds stone is held and listed.
  - Its vessel neighbour sees no flow.
  - After the stone is removed and the chunk is re-queued, the pipe is placed and released.
- Built in step 4 (`RegistryFirstGameTests`): a to c as `aBrokenLineIsSetBackFromTheRegistry` (it first checks that an intact line re-aligns nothing, the core of i), d as `aBlockedCellHoldsItsPipeUntilItIsFree`, and the `stateFor` round trip as `everyOneBlockDeviceStandsAsItsRegistration`. Each runs in a batch of its own, because a re-queued chunk also queues its neighbours' registrations. "Behind the authority's back" is `FluidDeviceBlock.quietly`, and a chunk's next load is `FluidWorldAuthority.loadedChunk`.
- **e. Rack.** A rack cell removed behind the authority's back is rebuilt with its lanes. Built in step 5 as `aLostRackCellIsSetBackWithItsLanes`: a corner cell removed comes back as the corner it was, and a straight rack turned the other way faces as laid again.
- **f. Drum.** A drum whose shells were removed is rebuilt from its structure record, its contents kept.
- **g. Constructor edit.** A drum whose shell shape differs from its record is rebuilt to the record.
- Built in step 6:
  - f as `aLostDrumIsRebuiltFromItsRecordWithWhatItHeld`. The drum holds nitrogen. A stone in one of its cells holds it, and the drum is released once the cell is free.
  - g as `aDrumOfAnotherShapeIsRebuiltToItsRecord`. A taller drum of the same origin stands in its place, so the region is widened.
- **h. Learning.** A schema-7 world's painted pipe learns its paint into the registry at its chunk's first load.
- **i. Nothing to do.** An ordinary placed network re-aligns nothing (all counters zero) across a save and a reload.
- **j. Unknown block.** An IChemE block the registry never saw registers fresh (today's path, kept).

## 8. Risks and checks

- **A registry bug now edits the world.** Mitigations:
  - only IChemE blocks are replaced (`FluidDeviceBlock`, `PipeRackBlock`, equipment shells and interiors);
  - every placement is logged;
  - test i guards ordinary loads;
  - the review's pins (P2, W1) give an undo once W1 lands.
- **Missed write paths** (3.3) would make re-alignment undo the player's own edits at every load. Test i, run after each kind of edit (paint, rack, constructor), catches this. Extend it with one edit of each kind.
- **Cost at load.** A chunk with many devices queues many checks. The served bound keeps each tick small. Watch the counter on a large world once.
- **Interaction with the layout refresh.** Both rebuild structures. A structure is served by one of the two at a time: the refresh queue first, then re-alignment with the refreshed shape.
- **Not checked in game.** The rendering of placed structures and of their light is already covered by the refresh path. Offer the owner one in-game check after step 6, and do not start one unasked.

## 9. Documentation at the end

- **Code guide:**
  - fluid-runtime: facts schema 8, `Builds`, the chunk-load queue, held registrations;
  - equipment: the structure record, and refresh fed by the registry;
  - pipe-laying: paint into the registry;
  - verification: the new GameTests.
- **The batch README:** one history line per step.
- **At the merge:** a `CHANGELOG.md` line. The version is the owner's choice; a minor bump fits a new feature with a facts-schema change.
