# 2026-11-03-save-corruption

Purpose: how save corruption can damage an IChemE world, and how to stop it from causing total data loss, as an extension of the backward-compat facts/state system (owner 2026-11-03: "Research about how to handle eventual save corruption to prevent total data loss"; "I'd imagine it will be somehow combined with the backward compat system").

Status: Planned. Decisions S1-S11 were all taken on 2026-11-03. The W5 plan (registry first) is written. The W1-W4 plan (corruption ladder) follows. Index: [../INDEX.md](../INDEX.md).

## Documents

- [SAVE_CORRUPTION_REVIEW.md](SAVE_CORRUPTION_REVIEW.md) holds:
  - the causes of damage in practice;
  - what the fluid store, the accessory store, the assembly store and vanilla do today;
  - gaps G1-G10;
  - the recovery ladder (rungs OLDER GENERATION and EMPTY PART below FACTS);
  - the supporting pieces P1-P9;
  - section 5, on chunks and the fluid store disagreeing: registry first. The world is re-aligned to the registry, and the registry gains the facts it needs (paint, rack records, structure shapes);
  - the AGENTS.md text, the owner decisions and the work packages.
- [REGISTRY_FIRST_PLAN.md](REGISTRY_FIRST_PLAN.md): W5a (facts schema 8 with line paints, rack records and structure records, the write paths, learning from blocks) and W5b (held registrations, the re-alignment queue, one-block, rack and structure placement, commands, notices), in eight steps with GameTests a to j.

## Code areas (read, not changed)

- `runtime/fluid/FluidCheckpointStore`, `FluidSavedData`, `FluidCheckpointCodec` (core seal) and `FluidFacts.rebuild`.
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

## Open items

- Implement W5 per REGISTRY_FIRST_PLAN.md (not started).
- `SolidMaterialCatalog.validate` still says "create a fresh world". The start guard reaches it only when the basis check misses a change to the solid data. Its text changes with P8 (review G9).
