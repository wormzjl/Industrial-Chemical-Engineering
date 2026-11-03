# 2026-11-03-save-corruption

Purpose: how save corruption can damage an IChemE world, and how to stop it from causing total data loss, as an extension of the backward-compat facts/state system (owner 2026-11-03: "Research about how to handle eventual save corruption to prevent total data loss"; "I'd imagine it will be somehow combined with the backward compat system").

Status: Planned. The review waits for the owner's decisions S1-S9. Index: [../INDEX.md](../INDEX.md).

## Documents

- [SAVE_CORRUPTION_REVIEW.md](SAVE_CORRUPTION_REVIEW.md) holds:
  - the causes of damage in practice;
  - what the fluid store, the accessory store, the assembly store and vanilla do today;
  - gaps G1-G10;
  - the recovery ladder (rungs OLDER GENERATION and EMPTY PART below FACTS);
  - the supporting pieces P1-P9;
  - section 5, on chunks and the fluid store disagreeing: a device generation per chunk, so that the newer side decides which devices exist;
  - the AGENTS.md text, the owner decisions and the work packages.

## Code areas (read, not changed)

- `runtime/fluid/FluidCheckpointStore`, `FluidSavedData`, `FluidCheckpointCodec` (core seal) and `FluidFacts.rebuild`.
- `FluidWorldAuthority.loadedChunk`, `FluidDeviceBlockEntity.bindIdentity`, `EquipmentStructure.restoreFluidBinding`, `runtime/instrument/InstrumentService.Store` and `runtime/assembly/AssemblyStore`.
- Vanilla and NeoForge 21.1.219: `SavedData`, `DimensionDataStorage`, `IOUtilities`, `Util.safeReplaceFile`, `LevelStorageSource`, `ChunkMap.handleChunkLoadFailure` and `scheduleUnload`, `ChunkStatusTasks`, `RegionFile` and `ChunkEvent.Load`.

## History

- 2026-11-03: review written on `claude/save-corruption-prevention-2bd7ac` from `main` `744ef4513` (0.19.3).
  - Mod code and vanilla/NeoForge sources were read directly.
  - An Opus subagent did the web pass (review section 10).
  - Research only: no code changed, no tests run. Code guide: no change.
- 2026-11-03: section 5 added after the owner's note that the chunk data can differ from the mod's data.
  - A hard kill can leave the chunks newer than the fluid store, because chunks are also saved on unload. A broken device then comes back as a phantom, and a block of another kind binds to the old registration.
  - Proposed: a device generation per chunk, kept in both stores (decisions S5 and S9, work package W5).

## Open items

- Owner decisions S1-S9 (review section 8).
- `SolidMaterialCatalog.validate` still says "create a fresh world". The start guard reaches it only when the basis check misses a change to the solid data. Its text changes with P8 (review G9).
