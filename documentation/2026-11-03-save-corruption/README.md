# 2026-11-03-save-corruption

Purpose: how save corruption can damage an IChemE world, and how to stop it from causing total data loss, as an extension of the backward-compat facts/state system (owner 2026-11-03: "Research about how to handle eventual save corruption to prevent total data loss"; "I'd imagine it will be somehow combined with the backward compat system").

Status: In progress since 2026-11-03. Decisions S1-S11 were all taken on 2026-11-03. W5 (registry first) is being built: steps 1 to 3 of 8 are done. The W1-W4 plan (corruption ladder) follows. W6 (save encoding off the server thread) is proposed. Index: [../INDEX.md](../INDEX.md).

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

## Open items

- W5 steps 4-8 per REGISTRY_FIRST_PLAN.md section 5. Next: step 4, `stateFor`, the restore flag, the kind check, the queue and one-block re-alignment (which also releases holds).
- Before step 4: every GameTest that places a structure must end with its dismantle or clear its record (plan section 7).
- W6 (save encoding off the server thread): proposed, owner to decide.
- `SolidMaterialCatalog.validate` still says "create a fresh world". The start guard reaches it only when the basis check misses a change to the solid data. Its text changes with P8 (review G9).
