# 2026-11-03-save-corruption

Purpose: how save corruption can damage an IChemE world, and how to stop it from causing total data loss, as an extension of the backward-compat facts/state system (owner 2026-11-03: "Research about how to handle eventual save corruption to prevent total data loss"; "I'd imagine it will be somehow combined with the backward compat system").

Status: In progress since 2026-11-03. Decisions S1-S11 were all taken on 2026-11-03. W5 (registry first) is being built: steps 1 to 6 of 8 are done. The W1-W4 plan (corruption ladder) follows. W6 (save encoding off the server thread) is proposed. Index: [../INDEX.md](../INDEX.md).

## Documents

- [SAVE_CORRUPTION_REVIEW.md](SAVE_CORRUPTION_REVIEW.md) holds:
  - the causes of damage in practice;
  - what the fluid store, the accessory store, the assembly store and vanilla do today;
  - gaps G1-G10;
  - the recovery ladder (rungs OLDER GENERATION and EMPTY PART below FACTS);
  - the supporting pieces P1-P9;
  - section 5, on chunks and the fluid store disagreeing: registry first. The world is re-aligned to the registry, and the registry gains the facts it needs (line paints, structure shapes);
  - the AGENTS.md text, the owner decisions and the work packages, with the W6 proposal.
- [REGISTRY_FIRST_PLAN.md](REGISTRY_FIRST_PLAN.md): W5a (facts schema 8 with line paints and structure records, the write paths, learning from blocks) and W5b (held registrations, the re-alignment queue, one-block, rack and structure placement, commands, notices), in eight steps with GameTests a to j. Section 6 is the lag budget.

## Code areas

- Changed (W5a step 1): `runtime/fluid/WorldTopologyLedger` (`Builds`, `LinePaint`, `Structure`, `paint`, `structure`), `FluidFacts` (schema 8), `FluidCheckpointCodec` (`sameWorldBody`); tests `WorldTopologyLedgerTest`, `FluidFactsCompatTest`; corpus image `src/test/resources/compat/fluid/schema-8`.
- Changed (W5a step 2): `WorldTopologyLedger` (live reads; paint leaves with an applied removal), `FluidWorldAuthority` (`linePaint`, `paint`, `structure`, `lay` with paints, `loadedChunk` through the pipe's `bind`), `FluidDeviceBlockEntity` (`bind`, `restatePaint`; paint setter removed), `PipeRackBlockEntity.bind`, `PipeLaying.lay`, `EquipmentStructure` (`record`, `restatePaint`; hooks in `place`, `refresh`, `dismantle`, `removeRetired`, `restate`), `EquipmentShellBlockEntity.repaint`; GameTests `PipeLayingGameTests`, `EquipmentCompatGameTests`.
- Changed (W5b step 3): `PhysicalFluidTopology.Device` (`held`, `withHeld`; `joins`), `FluidFacts.topologyState` (held lists), `FluidCheckpointCodec.topology`, `FluidWorldAuthority` (`hold`, `held`, `drop`; the column start refuses a held device), `InstrumentService.discardHost`, new `runtime/fluid/HeldDevicesCommand` (attached to `/icheme` in `EquipmentStructure.registerCommands`); tests `PhysicalFluidTopologyTest`, `WorldTopologyLedgerTest`; new GameTest class `RegistryFirstGameTests`.
- Changed (W5b step 4): `FluidDeviceBlock` (`stateFor`, `fittings`, the restore flag `registered` with `quietly`, which also covers `onRemove`), `FluidDeviceBlockEntity` (`bind(world, device)`, `standsFor`, the kind check in `bindIdentity`), `PipeLaying` (the renamed flag), `FluidWorldAuthority` (the re-alignment queue, `nextRealignment`, `holdWithoutLevel` at start), `FluidRuntimeDiagnostics` (`realign…` counters), `IChemE.onLevelTickPost`, new `world/level/block/RegistryRealignment`; GameTests `RegistryFirstGameTests` (3 new), `PipeLayingGameTests` (the new `bind` signature).
- Changed (W5b step 5): `WorldTopologyLedger` (`RackFrame`, `RackShape`, `Builds.racks`, `rack`; a frame leaves with its lane), `FluidFacts` (schema 9), `FluidWorldAuthority.rackFrame`, `PipeRackBlock` (`frame`, `stateFor`; `onRemove` follows the restore flag), `PipeRackBlockEntity` (`bind` learns frames, `carries`, `frames`), `PipeLaying.lay` (records frames), `RegistryRealignment.lane`; tests `WorldTopologyLedgerTest`, `FluidFactsCompatTest`; corpus image `src/test/resources/compat/fluid/schema-9`; GameTest `RegistryFirstGameTests` (1 new).
- Changed (W5b step 6): `EquipmentStructure` (`refresh` split into `survey`, `write`, `settle`; new `realignLater`, `serveRealignment`, `realign`, `stands`, `members`; `dismantle` follows the restore flag), `EquipmentRefresh.plan` with a fixed shift, `FluidWorldAuthority` (structure chunk index; `loadedChunk` queues structures), `RegistryRealignment.serve` (structures after devices); tests `EquipmentRefreshTest`; GameTests `RegistryFirstGameTests` (2 new).
- Read: `runtime/fluid/FluidCheckpointStore`, `FluidSavedData`, `FluidCheckpointCodec` (core seal) and `FluidFacts.rebuild`.
- `FluidWorldAuthority.loadedChunk`, `PhysicalFluidTopology.Device`, `FluidDeviceBlockEntity` (`bindIdentity`, saved fields), `PipeRackBlockEntity`, `EquipmentShellBlockEntity`, `EquipmentStructure.restoreFluidBinding`, `EquipmentRefresh`, `runtime/instrument/InstrumentService.Store` and `runtime/assembly/AssemblyStore`.
- Vanilla and NeoForge 21.1.219: `SavedData`, `DimensionDataStorage`, `IOUtilities`, `Util.safeReplaceFile`, `LevelStorageSource`, `ChunkMap.handleChunkLoadFailure` and `scheduleUnload`, `ChunkStatusTasks`, `RegionFile` and `ChunkEvent.Load`.

## History

- 2026-11-03: review written on `claude/save-corruption-prevention-2bd7ac` from `main` `744ef4513` (0.19.3).
  - Mod code and vanilla/NeoForge sources were read directly.
  - An Opus subagent did the web pass (review section 10).
  - Research only: no code changed, no tests run. Code guide: no change.
- 2026-11-03: section 5 added after the owner's note that the chunk data can differ from the mod's data.
  - A hard kill can leave the chunks newer than the fluid store, because chunks are also saved on unload. A broken device then comes back as a phantom, and a block of another kind binds to the old registration.
  - Proposed: a device generation per chunk, kept in both stores (decisions S5 and S9, work package W5).
- 2026-11-03: the owner chose registry first ("since it has backups, re-align the world if necessary"; "add additional data to registry if necessary").
  - Section 5 rewritten: the registry is the authority for IChemE devices and structures, and the world is re-aligned to it after chunk load. A cell held by another block is never overwritten; its registration is held and listed.
  - The registry gains paint, rack records and structure shapes as facts (5.5). The per-chunk counter is dropped.
  - New decisions S9-S11 cover item drops, unknown blocks and held registrations. W5 is split into W5a (facts) and W5b (re-alignment).
- 2026-11-03: the owner accepted the recommended options for every remaining decision ("Accept, plan W5"). REGISTRY_FIRST_PLAN.md is written. The W1-W4 plan follows.
- 2026-11-03: W5a step 1, the commit that adds this line. Facts schema 7 to 8: the topology unit carries `Builds`, line paints by device identity and structures by origin (shape as the shell's SNBT, and paint).
  - Found derived, so not stored: rack records (lanes rebuilt from registrations), nozzle sizes (from pipe bores), structure members (origin plus shape). Plan 3.1 and 3.3 updated.
  - Owner requirement: "The system needs to be lag-free, especially during saving". Edits are O(1) on live maps. The immutable copy is made at the next save's capture, and only after an edit. A build edit does not bump the registry version. Shape text is made once per structure. Plan section 6 holds the lag budget.
  - The save path costs about one tick on the server thread when every island changed (f3: 7.6 + 48.4 ms at 10,000 tanks). W6 is proposed in the review, section 9.
  - Gates: `FluidFactsCompatTest` 15/15 (schema-8 image added; images 1-7 read with empty build facts), `WorldTopologyLedgerTest`, `FluidCheckpointCodecTest`, `FluidCheckpointStoreTest` (44 tests with the compat test's first run), `FluidCheckpointFormatTest` 8/8. Code guide: waits for the merge.

- 2026-11-03: W5a step 2, the commit that adds this line. The registry takes every build edit, and the blocks copy from it.
  - Write paths: line paint has one writer (`PipeLaying.lay` → `FluidWorldAuthority.lay` with the paints). Structures are recorded at their whole build, follow a refresh's shifted origin, and leave at their dismantle or retired removal. A line's paint leaves with its identity when the removal applies, in the ledger: one place for every removal path.
  - Restatement (registry first): pipes at bind (one `bind` now serves both load paths), rack lanes at the rack's bind, vessels at their once-per-load restate (cells walked only when the controller's paint differs).
  - Learning moved here from step 8: a painted block whose line the registry holds bare teaches it; a current vessel with no record teaches its shape and paint. Step 8 keeps only its world-level check (GameTest h).
  - Removed: the pipe block's public paint setter, and `PipeLaying`'s painting of blocks; the duplicated bind branch of `onLoad` and `loadedChunk`.
  - Gates: `WorldTopologyLedgerTest` 10/10 (new: paint leaves when its removal applies), `FluidFactsCompatTest` 15/15, `PipeLineCompatTest` 8/8; fluid GameTests 40/40 on the fresh run id `save-w5-step2` (edited: the painted lay in `PipeLayingGameTests`; new: `EquipmentCompatGameTests` structure record). Code guide: waits for the merge.

- 2026-11-03: W5b step 3, the commit that adds this line. Held registrations (S11).
  - Mechanism: column suspension leaves the network through `Device.joins`. Holding reuses it as a new `Device.held`, which joins nothing, as built either. A held tank is an island of its own with its contents; a held pipe leaves dead ends, as a missing block does. Hold and release are queued edits at the next revision, because `PhysicalRegistry.load` matches saved islands against a compile of the applied registry.
  - State, not a fact: the topology unit's state section lists the held applied devices, and per queued event its held edits. Facts are unchanged (no schema bump, no corpus image); a state written before holding reads as none; a load from facts releases.
  - `/icheme devices held` (reason read from the world when asked, not stored) and `/icheme devices drop <id>` (held only; booked as removed, accessories leave the store, nothing drops). Operator text in English as the other `/icheme` commands, so no lang keys.
  - Gates: `PhysicalFluidTopologyTest` 30/30, `WorldTopologyLedgerTest` 11/11 (2 new tests), `FluidCheckpointCodecTest` 11/11, `FluidCheckpointFormatTest` 8/8, `FluidFactsCompatTest` 15/15 (images 1-8 read with no holds), `FluidCheckpointStoreTest` 9/9, `ColumnSuspensionTest` 2/2; fluid GameTests 41/41 on the fresh run id `save-w5-step3` (new: `registry-first-held`). Code guide: waits for the merge.

- 2026-11-03: W5b step 4, the commit that adds this line. One-block re-alignment (plan 4.2, 4.3).
  - The queue: each chunk load queues its registrations once, per dimension, in the authority. The level tick serves at most 32 devices and stops after 1 ms. A one-block device is served once its own chunk is loaded; neighbours in unloaded chunks settle at their own load (D1), so the block-ticking wait stays for structures (step 6) only.
  - Per device: its block present is restated (facing, fittings; a lever keeps its committed position); the cell free or holding another fluid device block gets `stateFor` placed quietly, bound, then connected as the laying tool does; any other block holds the registration. A held registration whose block stands again is released.
  - The restore flag is the laying flag renamed (`registered`, `quietly`). It now also stops `onRemove`, so a replaced block removes no registration and drops nothing (S9). The kind check: a block binds only a one-block registration of its own kind (`standsFor`); `bindIdentity` throws, `bind` waits for the re-alignment.
  - Dimensions with no level: their registrations are held at start, in one queued event. The log line is per serving tick (`fluid_realign dimension=… checked=… placed=… restated=… held=… released=…`), and the counters are `realign…` in `FluidRuntimeDiagnostics`.
  - Gates: `FluidRuntimeDiagnosticsTest` 1/1, `PipeLineCompatTest` 8/8; fluid GameTests 44/44 on the fresh run id `save-w5-step4` (new: `registry-first-state`, `registry-first-realign-line` for GameTests a to c, `registry-first-realign-blocked` for d). The run logged exactly the three expected re-alignments; the other batches' chunk loads re-aligned nothing. Column GameTests not run: they reach only the queue, and column ports, structures and lanes are skipped in this step. Code guide: waits for the merge.

- 2026-11-04: W5b step 5, the commit that adds this line. Rack cells set back from their lane registrations (plan 4.2 item 3).
  - Found: step 1 called the rack record derived, but the cell's frame (facing and shape, decided once at placement) is a fact. A lane's face points do not fix it: 544 of 1,376 lane cases fit more than one frame, 228 of them frames of different shapes (a straight cell and a tee's leg; jshell probe in `tmp/rack-frame/`). The owner's direction "add additional data to registry if necessary" covers it, so facts schema 8 to 9: each rack lane's frame, keyed by its identity, leaving with it as paint does.
  - Write path: `PipeLaying.lay` records each new lane's frame. Learning: a rack's bind teaches the frame of a lane that has none, unless another lane of the cell holds a different one.
  - Re-alignment per lane: a rack of another frame takes the cell's recorded frame and binds again; a free cell gets a rack of it. A lane the cell cannot carry, or whose cell another block holds, is held. Lanes saved before schema 9 get the first frame that takes them all at their points, straight first; none, and they are held. `PipeRackBlock.onRemove` follows the restore flag.
  - Gates: `WorldTopologyLedgerTest` 12/12 (new: a frame is a lane's and leaves with it), `FluidFactsCompatTest` 15/15 (schema-9 image added; images 1-8 read with no frames), `FluidCheckpointCodecTest` 11/11, `FluidCheckpointFormatTest` 8/8, `FluidCheckpointStoreTest` 9/9, `PipeLineCompatTest` 8/8; fluid GameTests 45/45 on the fresh run id `save-w5-step5c` (new: `registry-first-realign-rack`, GameTest e, which logged the corner cell placed and the turned rack restated).
  - Two earlier runs failed in the new test only, both test faults. In `save-w5-step5`, its cells were not cleared first, so a start cell was refused. It threw before its cleanup, and the two tanks it left failed the world-wide cleanup check of `fluid-world-unloaded`. In `save-w5-step5b`, it read the frames before the lanes' removals applied. Code guide: waits for the merge.

- 2026-11-04: W5b step 6, the commit that adds this line. Structures rebuilt from their records (plan 4.4).
  - Queue: the authority indexes each record's origin by the chunks of its box, and a chunk load queues the structures it touches. They are served after the devices in the same 1 ms: at most 8 checks a tick, none after a rebuild. A structure waits while the layout refresh has it queued (plan section 8), and until its box and the blocks beside it are block-ticking.
  - Check: a structure whose layout cells all stand as its own is left as it is, and holds on its registrations are released. Cost: one read per cell, once per load.
  - Rebuild: `refresh` was split into `survey`, `write` and `settle`, which the restore shares.
    - The restore keeps the record's origin (`EquipmentRefresh.plan` with shift 0) and paints every shell cell with the record's paint.
    - Cells of the same origin with another shape count as its own, as do interior cells that no shell cell reaches. When such cells reach beyond the record's box, a wider region is read.
    - Members bind through `restoreFluidBinding`.
    - With a cell left out, the members are held and the written cells keep no stamp.
    - `dismantle` follows the restore flag.
  - Checked before the step: each GameTest that places a structure ends with its dismantle or a retired removal. The exception is `CoolingTowerGameTests`, which leaves its tower whole and intact.
  - Gates: `EquipmentRefreshTest` 8/8 (equipmentGates; new: a restore keeps its origin), `ColumnSuspensionTest` 2/2, `ExchangerCouplingTest` 13/13; fluid GameTests 47/47 on the fresh run id `save-w5-step6b` (new: `registry-first-realign-drum` for GameTest f, `registry-first-realign-shape` for g).
    - In f, the stone held the drum. Once the stone was gone, the layout refresh placed the cell first (the written cells had no stamp), then the re-alignment released the drum. This is the order plan section 8 asks for.
  - The first run, `save-w5-step6`, failed in f only, a test fault: a 28 m³ drum cannot reach 2 bar through one pipe in 1,200 ticks (about 2,300 mol at the 100 m/s cap). f now charges until the drum holds 10 mol. Its leftovers failed the cleanup check of `fluid-world-unloaded`. Code guide: waits for the merge.

## Open items

- W5 steps 7-8 per REGISTRY_FIRST_PLAN.md section 5. Next: step 7, the notices and their lang keys (plan 4.5).
- One in-game check of a rebuilt structure (rendering, light) to offer the owner after step 6 (plan section 8); not started unasked.
- Tooling cleanup before the merge: the rack-frame probe `tmp/rack-frame/ambiguity.jsh` (plan 3.1) moves to a `tools/` folder.
- A pipe's sight glass is an accessory of the registration (accessory store). Check in step 6 or 8 that a re-placed pipe shows it again, through `deviceLoaded` → `instruments.bind`; no GameTest covers it yet.
- W6 (save encoding off the server thread): proposed, owner to decide.
- `SolidMaterialCatalog.validate` still says "create a fresh world". The start guard reaches it only when the basis check misses a change to the solid data. Its text changes with P8 (review G9).
