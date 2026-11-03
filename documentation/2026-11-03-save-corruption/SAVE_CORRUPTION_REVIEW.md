# Save corruption: how the mod's saves fail today, and a recovery ladder that prevents total loss

Batch `2026-11-03-save-corruption`. Owner request 2026-11-03: "Research about how to handle eventual save corruption to prevent total data loss", and "I'd imagine it will be somehow combined with the backward compat system". Research only: no code changed. Base: `main` at `744ef4513` (0.19.3).

## 1. Expected behaviour

- A crash, a full disk, a locked file, a torn backup copy, a flipped bit or a writer bug damages at most the part it touches. It never makes a world unplayable and never discards everything.
- The loss is bounded and named: at most one autosave interval (6000 ticks, 5 min) of the damaged part, written to the log and to the affected island's status line. The damaged bytes are kept.
- This is the same rule as the backward-compat ladder (batch `2026-10-26-backward-compat`, plan 4.2), carried one rung further. Drop state before facts. Take an older copy of a fact before you drop it. Drop the facts of one part before you refuse the world.

## 2. What damages saves in practice

The causes, roughly from most to least likely for this mod. External sources: section 10.

| Cause | Effect on our files | Protection today |
|---|---|---|
| One bad entry that fails to encode (Refined Storage 2: 113 failed saves in 9 h of play, then everything rolled back, [S1]) | `prepare` throws, so no part of that save is written, and the same thing happens at every later save | None. The exception reaches Minecraft's save loop |
| A writer bug: a save holds a value that the next start's reader rejects (NaN, an out-of-range fact, a validator tightened by a later build) | The world plays on, then refuses at the next start | Corpus gates check old images against the reader. Nothing checks the live writer's output |
| Crash, kill or power loss during a save | Temporary files are left. The renamed files are whole | Atomic write (temp, `force`, `ATOMIC_MOVE`), and packs commit before the core |
| A locked file on Windows (OneDrive, antivirus, a backup tool) or a full disk | The commit fails. The previous save stays in force | Logged only. No retry. Every later save fails the same way, and nobody is told |
| A backup or copy taken while the server writes, or a partial restore | The core and the packs come from different moments, so a pack is missing | Refuse. A backup taken with `save-off`, `save-all flush`, copy is consistent, because `ServerLevel.save` with flush waits for NeoForge's IO worker (`IOUtilities.waitUntilIOWorkerComplete`), and our commits run on that worker |
| Bit rot, a sector error, or a consumer SSD that undoes or reorders flushed writes on power loss (13 of 15 drives misbehaved in the FAST 2013 study, [S5]) | The digest no longer matches, or a pack goes missing | Found only at the next start, when memory no longer holds the true contents |
| A damaged region chunk | Vanilla regenerates the chunk without asking (`ChunkMap.handleChunkLoadFailure` → `createEmptyChunk`). Its blocks and block entities are gone | The fluid registry keeps the chunk's devices, with no block and no notice |
| A version downgrade | A newer pack format or facts schema is found | Refuse (`Unreadable`), files untouched. This is correct |

## 3. What the code does now

**Fluid store** ([`FluidCheckpointStore`](../../src/main/java/com/wormzjl/icheme/runtime/fluid/FluidCheckpointStore.java), [`FluidSavedData`](../../src/main/java/com/wormzjl/icheme/runtime/fluid/FluidSavedData.java)). This is copy-on-write (shadow paging):
- Packs are immutable. A save writes one new pack with the changed units, then the core record. Each file is written atomically and durably.
- The core is sealed by a manifest SHA-256. Every unit has its own SHA-256 in the core's index.
- A failed commit keeps the previous save in force, and the next save writes every unit afresh.
- During a session, the packs of the current and the previous core are kept. The previous core file itself is overwritten.
- A load validates everything. It takes the EXACT route, or FACTS when the state cannot be used. Anything damaged throws, the server does not start, and the files are left as they are.

**Accessory store** (`InstrumentService.Store`, `icheme_accessories.dat`): a single file of facts. It reads itself, refuses when damaged, and is written by NeoForge's atomic `IOUtilities.writeNbtCompressed`. It keeps no previous copy.

**Assembly store** (`icheme_assemblies.dat`): state only. Vanilla's `computeIfAbsent` resets it silently when it cannot be read, and the store rebuilds. This is correct by design.

**Equipment shapes and block entities**: they live in region files and get vanilla's handling. A chunk that does not read is regenerated, and the event is logged only.

**Vanilla, for comparison** (read from `neoforge-21.1.219-sources.jar`):
- `level.dat` uses `Util.safeReplaceFile`, which writes the new file, renames the current one to `level.dat_old` and retries each step 10 times. `LevelStorageSource.restoreLevelDataFromOld` restores from `level.dat_old`.
- `data/*.dat` files get NeoForge's atomic write, but no `_old` copy.
- `DimensionDataStorage.readSavedData` catches a load failure, logs it and returns null. A SavedData opened with `computeIfAbsent` is then replaced by an empty one, and the next save overwrites the file. Our two fact stores read their own files to avoid this.

### Gaps

- **G1. One bad byte refuses the whole world.** A missing pack or one unit digest refuses everything, although the other islands are intact and an older generation could exist. The player has two ways out:
  - restore a whole-world backup;
  - delete the fluid files by hand. Placed devices then re-register from their blocks as chunks load (`FluidWorldAuthority.loadedChunk`). Contents, the ledger and every setting that only the registry holds are lost, so this is total loss of the fluid facts.
- **G2. There is never a second generation after a restart.** The previous core is overwritten at every save. `cleanOrphans` deletes, at each accepted load, every pack the loaded core does not reference. Rollback is impossible even in principle.
- **G3. Resting units are never re-read.** An unchanged island keeps its unit in an old pack for as long as it rests. Its bytes are checked only at the next start (and when a compaction moves them), so rot is found only after memory has lost the true contents.
- **G4. The writer is not checked against the reader, and one island can block every save.**
  - A unit that the reader would refuse is committed, and the world bricks at the next start.
  - An exception while one island is encoded propagates out of `prepare`. No part of that save is written, and every later save fails the same way. This is the Refined Storage 2 loss pattern [S1].
- **G5. Failed commits are silent.**
  - `WRITE_FAILED` and `NOT_WRITTEN` go to the log only. A player whose world folder is locked by OneDrive can play for hours on failed saves and lose all of it in a crash.
  - Vanilla retries a locked rename 10 times. We do not retry.
- **G6. A FACTS-route load destroys its own undo.**
  - When a model or data change rebuilds from facts, the first save rewrites every unit. The pre-update packs are deleted two saves (about 10 min) later.
  - A wrong restatement then becomes permanent unless the player has a world backup.
- **G7. The accessory store has one copy.** Damage to it refuses the world.
- **G8. A regenerated chunk is silent.**
  - Its devices stay registered and keep simulating (by design, the simulation does not need chunks), but they have no blocks.
  - Nobody is told.
  - Chunk load only adds missing registrations. It never reconciles extra ones.
- **G9. The refusal UX.**
  - The server start throws, and the message names the file but not the way out.
  - Two texts still tell the player to create a fresh world, which is the total-loss route:
    - the class comment of `FluidCheckpointStore` (line 42);
    - `SolidMaterialCatalog.validate` (line 24, "no migration; create a fresh world"). The start guard `FluidWorldAuthority` line 90 reaches it, and it fires only if the basis check missed a change to the solid data.
- **G10. The chunks and the fluid store can disagree after a hard kill, even when no file is damaged.** Section 5 proposes registry first.
  - A device broken just before the kill comes back as a phantom.
  - A block of another kind binds to the old registration at its cell.

## 4. Proposal: the recovery ladder, as rungs below FACTS

The load ladder of backward-compat plan 4.2 gets two rungs between FACTS and refuse. Rungs 1 and 2 do not change.

1. **EXACT**: today's route.
2. **FACTS**: today's route (state dropped, contents restated when the basis changed).
3. **OLDER GENERATION** (new). When a part of the newest generation does not validate, take that part from the newest retained generation that validates:
   - A damaged core, or a missing pack: use the previous core whole, through EXACT or FACTS as usual.
   - A damaged island unit: take the FACTS route. Take that island's vessels from the newest generation that holds a valid unit for them. `FluidFacts.rebuild` already attaches contents by vessel identity, so it accepts units from mixed generations as long as each vessel comes from exactly one of them. It throws "saved in two islands" otherwise.
   - A damaged topology unit (the registry): use the newest valid topology unit of an older generation, then FACTS.
4. **EMPTY PART** (new). When no generation holds a vessel's contents, the vessel starts empty. `FluidFacts.rebuild` already does this, with "has no saved contents; it starts empty". The ledger books the vessel as lost to damage, and its status line names it.
5. **REFUSE**: only when no generation holds a valid topology unit (the registry itself is gone), or a newer build wrote the files. Files are untouched. The message names both ways out (section 3, G1). The second one becomes an explicit option rather than file surgery (decision S6).

Rungs 3 and 4 always do four things:
- before anything is written, move the damaged files to `data/icheme_fluid/damaged-<date>/`, which the mod never deletes (LMDB lost a recoverable database to a write made after the crash, [S4]);
- pin the generation they used (P2);
- log at ERROR with the parts and the generations taken;
- show a status line on each affected island, and a chat line to operators when they join, until an operator acknowledges it.

### Supporting pieces

- **P1. Retained generations.**
  - Keep the core records of the last K saves as `data/icheme_fluid/core-<sequence>.dat`, beside the main core file that Minecraft reads. Recommended K = 3.
  - A pack is deleted only when no retained core references it. `write` and `cleanOrphans` read that rule instead of "current and previous".
  - Cost: one more small atomic write per save, about 2.5 ms on the IO worker (f3 measurement). The core is 6.2 KB on disk per 100 islands.
  - Dead packs stay K saves longer. That is at most K × (the changed bytes per save). When 10,000 lone tanks all change, a save writes 2.4 MB (f3 tables), so the extra is about 7 MB.
  - P1 also fixes torn backups. A copy taken mid-commit falls back one generation instead of refusing.
- **P2. Pins.**
  - A load that takes FACTS, OLDER GENERATION or EMPTY PART pins the core it read (`data/icheme_fluid/pinned/<date>-<reason>.dat`). Its packs then stay until the pin is removed.
  - Also pin at the first load under a new `mod_version`.
  - Keep the newest 3 pins, plus an operator command to list and remove them.
  - Packs are not copied, so a pin costs a few KB until its units die.
  - This gives the backward-compat system an undo: put the pinned core back and start the older jar.
- **P3. Write verification, per island.**
  - Before the core commits, the IO worker decodes each newly encoded unit's facts section with the reader.
  - An island whose encoding throws, or whose unit fails the check, keeps its last good unit, and the rest of the save commits. The log names the island, and the save-failure notice (P5) fires.
  - Cost: about one decode per changed unit, on the IO worker: 13.7 ms per 1,000 lone tanks (f3 tables), for facts and state together.
  - Fixes G4.
- **P4. Scrub.**
  - Once after an accepted load, and then once per hour of uptime, the IO worker re-reads the referenced packs and checks every digest.
  - A mismatch calls `forget()`, so the next save rewrites every unit from memory. While the server runs, memory holds the true contents, so the repair loses nothing.
  - Cost: reading 2.4 MB per 10,000 islands.
  - Fixes G3.
- **P5. Save-failure notice and retries.**
  - Retry the rename of a locked file as vanilla does (a few tries, short sleeps, on the IO worker). `ATOMIC_MOVE` can throw `AccessDeniedException` on Windows under load (JDK-8303225 [S2]), and Refined Storage 1 lost data after a failed rename [S3].
  - Check free space before writing a pack. Factorio lost both a save and its autosave on a full disk [S6].
  - After 2 failed commits in a row, show operators a chat line and a status note: "IChemE fluid data has not been saved since <time>: <reason>".
  - Fixes G5.
- **P6. Accessory store generations.**
  - Keep the previous copy (`icheme_accessories.dat_old`, written as `Util.safeReplaceFile` does).
  - When the current copy does not read, fall back to the previous one with the same notice.
  - Fixes G7.
- **P7. Regenerated chunk notice.**
  - NeoForge's `ChunkEvent.Load.isNewChunk()` is true exactly when the chunk was promoted from a `ProtoChunk` and not read as a full chunk (`ChunkStatusTasks`). That includes a chunk vanilla regenerated after a read failure (`createEmptyChunk`). If the registry holds devices in such a chunk, the chunk's saved data was lost: damaged region data, or a region file someone deleted.
  - Log a WARN and add a status line: "<n> devices lost their blocks: chunk <x, z> was regenerated". Keep the registrations.
  - Fixes G8 together with section 5. A new chunk is re-aligned like any other: its devices are placed again from the registry where the new terrain leaves room. This notice says the chunk was regenerated.
- **P8. Refusal text.**
  - Name the recovery steps, and remove the "fresh world" instructions.
  - Fixes G9.
- **P9. Low priority.**
  - Separate digests for the facts and state sections, in the index row: state-only damage could then take FACTS instead of an older generation. Random damage is about equally likely in either section, so the gain is small.
  - On Linux, `force` the parent directory after each rename (fsync(2); OSDI 2014 found that rename ordering and atomicity vary by file system, [S5]).
  - None of these is measured, so none is proposed for now.

## 5. When the chunks and the fluid store disagree: registry first

The owner, 2026-11-03:
- "since the data are not stored in chunks, the chunk data could be different to the mod's system";
- "How about registry first? since it has backups, re-align the world if necessary";
- "add additional data to registry if necessary".

A disagreement needs no damaged file. A hard kill is enough.

### 5.1 Expected behaviour

- **The authority.** The fluid registry is the authority for IChemE devices and structures: which exist, where, of what kind and shape, and what they hold. It is the only store with checksums, kept generations and pins (sections 3 and 4). Region chunks have none of these, and vanilla replaces a damaged chunk silently.
- **One consistent snapshot.** After any load, the IChemE layer of the world matches the registry: every registration has its block, of the same kind, facing, fittings and paint. The topology and the contents then come from one save, so they agree with each other.
- **Re-alignment.** The world is re-aligned to the registry where the world is free:
  - a missing block is placed again from its registration;
  - a block of the wrong kind or state is set back to it.
- **Two limits:**
  - Another block is never overwritten. A registration that cannot be placed is held and listed until its cell is cleared or an operator drops it.
  - A block the registry never saw is registered from the block, as today. The registry cannot decide about something it never recorded.
- **Contents** come only from the registry and are never invented.

### 5.2 How they diverge, and what registry first does

The two saves are not taken together:
- Vanilla saves a chunk at every autosave, and also whenever the chunk unloads (`ChunkMap.scheduleUnload` → `save`).
- The fluid store commits only at autosave (6000 ticks).
- Chunk writes go to the region IO worker, and the fluid commit goes to NeoForge's save-data worker. Nothing orders the two.

An ordinary crash or stop runs `saveAllChunks` and saves both sides. Divergence needs a hard kill (power loss, an OOM kill, the task manager), or a failure inside one side's save.

| Cause | Today | Registry first |
|---|---|---|
| Hard kill: a device broken after the last fluid save, its chunk saved on unload | Its registration and contents come back with no block, still joined to the real pipes beside it (a phantom) | The block is placed again, with its contents. The player's break is undone |
| Hard kill: a device placed after the last fluid save | It registers fresh and empty | The same: the registry never saw it |
| Hard kill: a pipe replaced by a filter after the last fluid save | The filter binds to the old pipe registration (`FluidDeviceBlockEntity.bindIdentity` checks the position, not the kind). The world shows a filter, and the simulation runs a pipe | The block is set back to the registered pipe |
| The mod's own save throws (G4), but the server's stop still saves the chunks | As the three rows above, for every edit since the last good fluid save | As the three rows above. P3 makes this case rare |
| A torn autosave: the fluid commit lands, the chunk write does not | Blocks placed in the window are gone, their registrations stay. Blocks broken in the window come back and register fresh | Placed blocks are put back. Broken blocks come back and register fresh, empty (decision S10) |
| Ladder rung 3 (an older generation), or `data/` restored from a backup | As after a hard kill, for one autosave or for the backup's age | Device edits since that save are undone. The world matches the save that was loaded |
| Region files restored without `data/` | Devices built since the backup are phantoms | The newer devices are placed again |
| Vanilla regenerates a damaged chunk, or a tool deletes it | Phantoms (G8) | The devices are placed again where the new terrain leaves room. Cells held by terrain are held and listed (S7) |
| The mod that adds a dimension is removed | Its devices keep simulating | They are held and listed |

`FluidWorldAuthority.loadedChunk` today only adds. It registers a block that has none and binds by position, never removes, and never compares kind, facing or fittings. Equipment vessels are the exception: `EquipmentStructure.restoreFluidBinding` compares the geometry and re-registers.

### 5.3 Why registry first, not "the newer side wins"

- **No recency to work out.** The earlier draft kept a device counter per chunk in both stores, to tell which side was newer. Under registry first that question disappears, and so does the counter: no chunk attachment, no new comparison.
- **The trustworthy side wins.** Every damage case of section 2 hits chunks without any check. A regenerated chunk loses its devices today. Registry first rebuilds them from a store that has three generations and digests.
- **One snapshot.** Topology and contents come from the same save, so a restored tank holds what it held when it was saved. Under "the newer side wins", a newer chunk's topology mixes with older contents.
- **One mechanism for three gaps.** It covers the regenerated-chunk gap (G8, and P7 shrinks to a notice), restores blocks from the registry (once a later batch), and settles what happens to a registration whose block is missing.
- **The price:** after a hard kill, IChemE device edits made since the last fluid save are undone. That is at most one autosave (5 min), or K saves after rung 3. The contents roll back by the same amount today, so device layout and contents roll back together.

### 5.4 The rule at chunk load

Re-alignment never runs while a chunk loads. `loadedChunk` only queues the chunk, exactly as equipment refresh defers (code guide, equipment, "Contract: layout refresh"). The queue is served on the level tick, a bounded number of devices per tick, once the region's chunks are all loaded and block-ticking. It never loads a chunk and never solves.

For each registration in the chunk:
- **Block present, same kind:** bind as today. Facing, fittings, joints and paint are set from the registration where they differ (a block-state update, a restatement of the block entity).
- **Block missing, or another IChemE device block at the cell:** place the registered block. One-block devices and rack lanes use a small placer. A structure goes through the equipment refresh plan with the registry's shape.
  - The placement binds to the existing registration and does not register anew (a restore flag, as refresh's rebind).
  - It keeps the identity, contents and accessories. The accessory store is keyed by the device identity.
  - A replaced IChemE block drops no item (S9).
- **Cell held by a block that is not ours** (terrain, the player's wall): do not overwrite it.
  - The registration is held. It keeps its contents and joins no flow, like a device that a running column suspends (`Device.suspendedBy`).
  - The island's status line and the operator list `/icheme devices held` name it.
  - It is placed when its cell is free at a later load. `/icheme devices drop <id>` removes it through the break path, booking its contents as removed.

For each IChemE device block with no registration at its cell:
- **The registry never saw it:** register it from the block, as today (S10).

Exception: rung 5. When no generation holds the registry, the registry is rebuilt from the blocks. The world then decides by necessity. The block entities' copies of the registry's facts are the backup of last resort.

### 5.5 What the registry has to gain

Facts that live only in blocks today. Each becomes an append-only facts field of the topology unit. That is a facts-schema bump, with a corpus image under `src/test/resources/compat/`.

| Block | Held only in the block entity today | Added to the registration |
|---|---|---|
| Pipe and one-block device (`FluidDeviceBlockEntity`) | Paint and secondary paint | Both paints |
| Pipe rack (`PipeRackBlockEntity`) | Frame, joined faces, and each lane's option, width and arms | A rack record per cell; the lanes are already devices |
| Equipment structure (`EquipmentShellBlockEntity`) | Shape: kind, diameter, tangent length, orientation, front, support, trays, connections, bank, boot, outlet, tower bank, components and turns. Also the origin, paint and nozzle sizes | A structure record per vessel identity, written with the shell's own shape codec and its repair rules (`ShapeRepair`). No second format |

Already in the registry, and kept as it is:
- kind, position and rack lane, facing, cutoff, filter, the flow control (valve and mover), joints, bore, ports, vessel geometry and the heat unit;
- the accessories, held in their own store by device identity.

The layout stamp, the box and the roof level are state, derived from the shape and the contents, so they stay out of the registry.

- **A missing field** (a world saved before the bump) means "learn it from the block". The first load of each chunk under the new build copies the block's facts into the registry. Until then, that device keeps today's behaviour. This meets the append-only rule that the default equals the old behaviour.
- **Size.** About 5 B of paint per painted pipe, and a few hundred bytes per structure: roughly 50 KB per 10,000 pipes and 250 KB per 500 structures, on top of the topology unit. That unit is rewritten only in a save in which the registry changed.
- **Two copies.** The block entity keeps its copy, because the client draws from it. It is restated from the registry at bind, as `bindIdentity` already does for bore and joints.

### 5.6 Limits

- **Undone edits.** Edits made in the window are undone, and a player whose inventory was saved later (a logout on a server) can lose or duplicate the blocks involved. Vanilla has the same class of problem with chests after a crash.
- **A registry bug now edits the world.** Mitigations:
  - only IChemE blocks are ever replaced, never another block;
  - every placement and replacement is logged with its position;
  - pins (P2) keep an undo;
  - a gate asserts that an ordinary save and load re-aligns nothing.
- **A held registration** keeps its contents indefinitely until its cell is cleared or an operator drops it. The status line keeps it visible.

## 6. Fit with the backward-compat rules: AGENTS.md text for the owner

Replace "A damaged facts section refuses the world and leaves its files untouched" with:

- A damaged part of a save is taken from the newest retained generation that validates. A part that no generation holds starts empty. The ledger books it, the island's status line and the log name it, and the damaged files move to quarantine and are never deleted. A world refuses only when no generation holds its registry, or when a newer build wrote it. Its files are left untouched.
- Keep the core records of the last three saves and every pack they reference. A load that rebuilds from facts or recovers, and the first load under a new `mod_version`, pins the core it read.
- A save checks every unit it writes with the reader before it commits. A failed save keeps the previous one, and operators are told after two failures in a row.
- The fluid registry is the authority for IChemE devices and structures. It holds every fact needed to rebuild their blocks: kind, facing, fittings, joints, paint, shapes, nozzle sizes and rack frames. After a chunk loads, the world is re-aligned to it on the engine's queue, never during the load.
  - A missing block, or an IChemE block of another kind or state, is placed again from its registration. The registration keeps its identity and contents.
  - A cell held by a block that is not ours is never overwritten. The registration is held (it keeps its contents and joins no flow) and is listed until the cell is free or an operator drops it.
  - A block the registry never saw registers from the block.
  - Block-entity copies of registry facts are caches restated at bind. They rebuild the registry only when no generation of it survives.
- The compat corpus gains damaged images: a flipped byte in an island unit, in the topology unit and in the core; a missing pack; a damaged newest core with a valid previous one. The gates read every image.

## 7. Risks

- **A rolled-back island.** Its vessels jump back by at most one autosave. Fluid moved into items or other stores in that interval is duplicated. This happens only on damage, so accept it.
- **A topology rollback undoes device edits.**
  - Under registry first (section 5), a device removed after the older topology unit was written is placed again, with its contents.
  - A device placed after it registers fresh and empty on chunk load (today's path).
- **A fallback that hides a systematic bug.** If every load fell back a generation, a writer bug would go unnoticed. Mitigations:
  - the fallback logs at ERROR and its notice stays until acknowledged;
  - a gate asserts that an ordinary save and load never leaves rung 2.
- **Disk.** K = 3 plus 3 pins holds at most about six generations of dead units. That is small next to region files (section 4, P1).
- **Generations do not protect against wrong data that still reads.** A valid but wrong in-memory state is written into every generation. Factorio's rotated autosaves were all corrupted this way, and Steam Cloud then synced them [S6]. The main source of wrong data is a new build, which the pin at a new `mod_version` (P2) covers.

## 8. Owner decisions

| | Question | Recommended | Alternative |
|---|---|---|---|
| S1 | When a part is damaged | Automatic OLDER GENERATION with a notice | Refuse, and recover only after an explicit opt-in |
| S2 | When no generation holds a vessel | Starts empty, booked as lost, notice, bytes in quarantine | Refuse |
| S3 | Generations kept | K = 3 | K = 2 (current and previous only); K = 5 |
| S4 | Pins | At FACTS and recovery loads and at a new `mod_version`; newest 3 kept | No pins |
| S5 | Who decides at chunk load (section 5) | **Owner direction 2026-11-03: registry first**, with the missing facts added to the registry | Rejected: the newer side wins, by a device counter per chunk. Rejected: the world always wins except for a new chunk |
| S6 | Rung 5: rebuild devices from their blocks with empty contents | An opt-in config switch named in the refusal message | Manual file deletion, documented only |
| S7 | A regenerated chunk whose new terrain fills device cells | Never overwrite: those registrations are held and listed until the player clears the cells | In a chunk known to be regenerated (`isNewChunk()`), replace natural terrain blocks |
| S8 | Whole-world backups | Not the mod's job. Document vanilla Make Backup and backup mods | The mod zips `data/icheme_*` on a schedule |
| S9 | An IChemE block that re-alignment replaces | No item drop. A hard kill reverts the player's inventory too in the common case | Drop it as an item (a duplicate when the inventory reverted) |
| S10 | An IChemE block the registry never saw | Register it from the block, as today | Remove it (strict registry first). That is right only after a torn autosave, and it deletes the player's newest builds after a hard kill |
| S11 | A registration that cannot be placed | Held: it keeps its contents, joins no flow, and is listed | It keeps simulating with no block, as today |

## 9. Work packages (once the owner decides)

- **W1. Generations and pins (P1, P2).** Code: `FluidCheckpointStore` (`Backend.writeCore`, the deletion rule, `cleanOrphans`) and pins. Gate: `FluidCheckpointStoreTest`.
- **W2. Ladder rungs 3 and 4.** Code: `FluidCheckpointStore.load`, `FluidFacts.rebuild` given per-vessel sources, the quarantine, a ledger line (an append-only facts field) and the status notice. Gates: the damaged corpus images, plus one test per rung.
- **W3. Checks and notices (P3, P4, P5).** Write verification, scrub, retries and the save-failure notice.
- **W4. Accessory store (P6) and refusal text (P8).** P8 changes `en_us` and the other lang files in the same commit. P7 is part of W5b.
- **W5a. Registry facts (section 5.5).**
  - Code:
    - paints on device registrations, rack records and structure records, as facts fields of the topology unit (a facts-schema bump);
    - the structure record written with the shell's shape codec;
    - learning a missing field from the block at its chunk's first load;
    - restating block-entity copies from the registry at bind.
  - Gates: the facts round trip, a corpus image of the new schema, the previous schema's image reading with absent fields, and `EquipmentCompatTest` for the shape inside the record.
- **W5b. Re-alignment (section 5.4).**
  - Code:
    - the queue fed by `loadedChunk` and served on the level tick;
    - a placer for one-block devices and rack lanes;
    - equipment refresh fed with the registry's shape;
    - placement that binds to the existing registration;
    - the kind check in `bindIdentity`;
    - the held state of a registration;
    - `/icheme devices held` and `drop`;
    - the status notice.
  - GameTests:
    - a registered pipe whose block was removed behind the authority's back is placed again and keeps its identity;
    - a vessel removed the same way comes back with its contents;
    - a block of another kind is set back;
    - a cell held by stone keeps the registration held and listed, and is placed after the stone is cleared;
    - an unknown device block registers fresh.
  - Each test edits the world or the registry directly, so it needs no real crash.
- **Order.** W5a and W5b do not depend on W1 to W4. They fix a case that needs no damaged file, so they can come first. W5b needs W5a for structures and paint, but one-block devices can go first.
- **Code guide:** fluid-runtime (checkpoint section and chunk load), equipment (`restoreFluidBinding`), instrument-accessories, verification.

## 10. External sources

A web pass by an Opus subagent on 2026-11-03, about 25 searches. Items marked (snippet) were read from search results only, not from the fetched page. No tracker publishes how often each cause occurs, so the order in section 2 is a reading of these reports, not a statistic.

- **[S1] Refined Storage, one file for every storage.**
  - RS2 2.0.9 (1.21.1): one item serialized without an `id` made every save of `refinedstorage_storages` fail. The failure was logged 113 times in 9 h of play, and the restart rolled everything back. The per-entry isolation the reporters asked for is not implemented. https://github.com/refinedmods/refinedstorage2/issues/1416
  - RS2 on NeoForge 21.1.54: a version mismatch removed every disk. https://github.com/refinedmods/refinedstorage2/issues/691
  - RS1 (snippet): a maintainer wrote that the disks file "contains all disk data for every disk". https://github.com/refinedmods/refinedstorage/issues/2778
- **[S2] Windows renames.** `ATOMIC_MOVE` threw `AccessDeniedException` under frequent use. `MoveFileEx` with `MOVEFILE_WRITE_THROUGH` fixed it for the reporter. https://mail.openjdk.org/pipermail/nio-dev/2023-February/013252.html
- **[S3] Refined Storage 1, failed rename.** "Failed to rename refinedstorage_disks.dat.temp"; the data files shrank, and the reporter restored them from a backup. https://github.com/refinedmods/refinedstorage/issues/3714
- **[S4] LMDB and ZFS.**
  - LMDB keeps two meta pages and opens the newest valid one. A write made after a crash overwrote the older page and made recovery impossible. https://openldap.org/lists/openldap-bugs/201308/msg00045.html
  - ZFS keeps a ring of uberblocks. `zpool import -F` drops the last transactions to reach a pool it can import. https://man.freebsd.org/cgi/man.cgi?query=zpool-import&sektion=8
- **[S5] File systems and drives.**
  - Pillai et al., OSDI 2014: 60 vulnerabilities in 11 applications, ordering the largest class; rename atomicity and ordering vary by file system. https://research.cs.wisc.edu/adsl/Publications/alice-osdi14.html
  - Zheng et al., FAST 2013: 13 of 15 SSDs misbehaved under power faults. https://www.usenix.org/conference/fast13/understanding-robustness-ssds-under-power-fault
  - fsync(2): the directory entry needs a separate fsync of the directory. https://man7.org/linux/man-pages/man2/fsync.2.html
  - The 2009 ext4 zero-length files after replace-by-rename. https://bugzilla.kernel.org/show_bug.cgi?id=15910
- **[S6] Factorio.**
  - A full disk corrupted both the save and its autosave. https://forums.factorio.com/viewtopic.php?p=571098
  - A deserialization defect corrupted every rotated autosave, and Steam Cloud then synced them. https://forums.factorio.com/viewtopic.php?p=633726
- **[S7] Vanilla.**
  - 1.20.3 added a screen for a corrupted level file, which offers the backup. https://minecraft.wiki/w/Java_Edition_1.20.3
  - A chunk that does not parse is regenerated from the seed. https://mojira.dev/MC-239610
  - Paper has a patch that detects, backs up and repairs corrupt region files. https://ayakael.net/mirrors/papermc/src/commit/7e59b6e18eb362606bdb1dee01f660adc736ef40/Spigot-Server-Patches/0326-Detect-and-repair-corrupt-Region-Files.patch
- **[S8] Other games and mods.**
  - Stardew Valley keeps a `_old` save one day behind. https://forums.stardewvalley.net/threads/help-corrupted-file-after-alt-f4-and-even-_old-dont-work.8552/latest
  - Terraria keeps a `.wld.bak` of the previous save. https://github.com/TEdit/tedit-gitbooks/blob/master/advanced/corrupted-world-recovery.md
  - ATM-10: machines kept their blocks after a crash but lost their block-entity data. https://github.com/AllTheMods/ATM-10/issues/4226
  - FTB Backups 2 checks free space and keeps N backups. https://www.curseforge.com/minecraft/mc-mods/ftb-backups-2
- **Read from `neoforge-21.1.219-sources.jar`, not from the web.** The chunk facts:
  - a chunk is saved on unload (`ChunkMap.scheduleUnload`);
  - `ChunkEvent.Load` reports a new chunk exactly when it was promoted from a `ProtoChunk` (`ChunkStatusTasks`).
- **The save facts, from the same jar:**
  - NeoForge makes SavedData writes atomic (`SavedData.save` → `IOUtilities.writeNbtCompressed`: temp, `force`, `ATOMIC_MOVE`).
  - `ServerLevel.save` with flush waits for that worker.
  - `Util.safeReplaceFile` retries each step 10 times.
  - `ChunkMap.handleChunkLoadFailure` regenerates an IOException or NBT failure as an empty chunk.
  - `ChunkEvent.Load.isNewChunk()` exists.
