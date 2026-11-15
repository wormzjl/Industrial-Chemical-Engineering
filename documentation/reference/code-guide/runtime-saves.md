# The fluid runtime's saves: facts, state and the checkpoint

Up: [code guide](README.md). The rule: AGENTS.md, "Saves and data formats". The numbers: [FORMATS.md](../FORMATS.md). Beside it: [runtime-registry.md](runtime-registry.md), [runtime-engine.md](runtime-engine.md). The corpus gates: [verification.md](verification.md).

<!-- j2a-packages: runtime/fluid/FluidSavedData.java runtime/fluid/FluidCheckpointCodec.java runtime/fluid/FluidCheckpointStore.java runtime/fluid/FluidFacts.java runtime/fluid/FactsSection.java runtime/fluid/RegistryFacts.java runtime/fluid/ColumnFacts.java runtime/fluid/MachineFacts.java runtime/fluid/BuildFacts.java runtime/fluid/StillFacts.java runtime/fluid/ConverterFacts.java -->

## Read this when

- A world's fluid network loads rebuilt from its facts, restated, reset or refused (`fluid_world status=LOADED … route=…`, `fluid_checkpoint status=FACTS|RESTATED|RESET|FACTS_DISAGREE|CERTIFICATE_DISCARDED`).
- You add a saved field (a fact or a state field), a subsystem's facts, a corpus image, or change a state format.
- A save is slow, writes too much, or leaves packs behind.
- A certificate does not survive a reload, or a reloaded island runs on the wrong model.

## What it does

The fluid world is saved in two layers. **Facts** are what the player built and put in: the registry with every setting, vessel contents, filter cakes, the ledger's totals and recoveries, the column units, shaft units, electric stores, pump health, build facts, stills and converters, a certified island's drift. Every later build reads them. **State** is what the engine can compute again: graphs, solved intervals, junction states, certificates, statuses, fences, queued events. It is read only in this build's state format; anything else sends the load to its facts. `FluidSavedData` (the overworld `SavedData` `icheme_fluid_core`) captures on the server thread and commits on Minecraft's IO worker through `FluidCheckpointStore` (pack files, an index of SHA-256 digests, dirty tracking, atomic commit). `FluidCheckpointCodec` holds the frozen envelope, the state format and the binary primitives; `FluidFacts` the facts encoding, read through its `FactsSection`s, and the rebuild from facts alone.

## Class map

Under `src/main/java/com/wormzjl/icheme/runtime/fluid/`. Lines at `d6f721b94`.

| Class | Role | Lines |
|---|---|---|
| [`FluidSavedData`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/FluidSavedData.java) | `SavedData` `icheme_fluid_core`: capture, `SaveTiming`, `open(server, models, catalog)` (the one reset, refusals), `load`, `route()`, `reason()`; `Capture` | 171 |
| [`FluidCheckpointCodec`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/FluidCheckpointCodec.java) | The envelope (`ENVELOPE`, `UNIT_MAGIC`, `UNIT_ENVELOPE`), the state format `VERSION`, the core record and the binary island and topology units; `PackageKey`, `PackageRecord`, `IslandEntry`, `MAXIMUM_COMPOUNDS`; `FormatMismatchException`, `Unreadable`; `Writer`, `Reader` | 1069 |
| [`FluidCheckpointStore`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/FluidCheckpointStore.java) | Packs, the SHA-256 index, dirty tracking, atomic commit, orphan cleanup; `load(core, models, catalog)` → `Loaded(checkpoint, world, route, reason)`, `Route` EXACT or FACTS; `resetForFormatChange` | 514 |
| [`FluidFacts`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/FluidFacts.java) | Package-private: facts `SCHEMA`, `ALIASES`; `world` and `island` (the units' facts), `events` (the topology unit's state), `topologyState`, `disagreement`, `rebuild` (`Placed.restate`), `REBUILT_CADENCE`; the records the sections share (registration, account, set points, solids); `World` with its builder, `Vessel`, `Drift` | 904 |
| [`FactsSection`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/FactsSection.java) | Package-private: one subsystem's run of fields in the topology unit's facts record; `ALL` in stream order | 28 |
| [`RegistryFacts`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/RegistryFacts.java) | The identity counter, basis, registrations, the two totals, recoveries, `Restated` | 64 |
| [`ColumnFacts`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/ColumnFacts.java) | The running column units | 79 |
| [`MachineFacts`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/MachineFacts.java) | Shaft units, electric stores, pump health | 67 |
| [`BuildFacts`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/BuildFacts.java) | Line paints, structure records, rack lane frames, device names | 76 |
| [`StillFacts`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/StillFacts.java) | The batch stills | 47 |
| [`ConverterFacts`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/ConverterFacts.java) | The fluid converters | 44 |

## Main flow

Load (`FluidSavedData.open` → `FluidCheckpointStore.load(core, models, catalog)`):

1. `readCore`: no `Envelope` but a `FluidFormat` is a `FormatMismatchException` (a world saved before the envelope); another `Envelope`, a `StateFormat` older than the envelope's first, or a `FactsSchema` outside 1 to `SCHEMA` is `Unreadable`. Any other `StateFormat` reads.
2. Each package record (a package and its compound list) is read on the current network package, whatever id it names (`PackageKey.on`): a compound the package no longer has, or a `PropertyRevision`, `EnergyRevision` or `EnergyComponents` that are not the current model's, marks the world for restatement and that record as moved.
3. Each listed pack: size, magic, pack format, id. Each unit: its SHA-256, its header (magic, `UNIT_ENVELOPE`, kind, identity, revision and generation as the index gives them, else `Unreadable`) and its facts, read at every load. Its state is decoded only while no reason to rebuild is known; a state of another format, or one that does not validate (a malformed field, a fence before the committed tick, a solid id that names no record), names the reason and sends the load to its facts.
4. The world's basis is read at every load: one that is not `sameAs` `FluidBasis.capture(catalog)` marks the world for restatement too.
5. Route **EXACT** (empty `reason`): the islands resume as saved; each island's facts are compared with its state (`FluidFacts.disagreement`): a disagreement fails a run with `icheme.fluid.scheduler.verify` and is otherwise logged `FACTS_DISAGREE`, the state kept. The same basis under another package id loads EXACT and names the current package (`Snapshot.withBasis`).
6. Route **FACTS**: `FluidFacts.rebuild`, logged `fluid_checkpoint status=FACTS reason=… saved_islands=… islands=… vessels=… restated=…`; nothing stored is reused, the data marks itself dirty and the next save writes every unit afresh.
7. Certificates are restored where their `Signature` holds (`Certificates.restore`), else discarded (`CERTIFICATE_DISCARDED`), the materialised inventory kept. `fluid_world status=LOADED format=… route=… online_tick=… islands=… certificates_saved=… certificates_restored=… awake=…[ reason=…]` summarises the load.

Save: `FluidWorldAuthority.capture` (on the server thread; it materialises certified islands and refuses an island with no device) → `FluidSavedData` → the IO worker writes one new pack (more only past 256 MiB) holding only the units whose island revision or payload generation changed, plus units moved out of mostly-dead packs; the pack first and the core second, both atomically.

## Contracts

- **The envelope is frozen** (`ENVELOPE`): the core record's frame and every unit's header, then the unit's facts section (its schema and length) and its state section (its format and length).
- **A fact is never discarded.** A facts field is only appended and never changes meaning or unit: a record an older build wrote lacks trailing fields only, and each reads the default written beside its read (the behaviour before the field). A record with more fields than this build reads was written by a newer build and is refused. Enums are written by name; `FluidFacts.ALIASES` maps a renamed or removed constant. An optional value is a list of at most one.
- **Sections** (`FactsSection.ALL`: `RegistryFacts`, `ColumnFacts`, `MachineFacts`, `BuildFacts`, `StillFacts`, `ConverterFacts`, in stream order). Each writes and reads its own fields in one fixed order with their older-schema defaults and holds its units' record codecs. A section that is not last never gains a root field: its new facts go inside one of its own nested records, or into a new section. A new subsystem appends a section at the end of `ALL`, a `World` component and its builder setter.
- **What the facts hold.** The topology unit's (`FluidFacts.world`): the next identity, the `FluidBasis`, every registration (id, position, kind, facing, geometry, control, generator flag, cutoff, filter flag, ports with cell, face, phase port and role; the spec with volume, T, p, composition, slurry feed, level height, open flag, vessel geometry, cooling tower, blanket and relief; the revision, joints, lane width and points; a heat side's or kettle shell's heat unit with its control and the exchanger's heat in transit, held by the lower-id side), the totals, recoveries, `Restated`, then each section's units. An island unit's (`FluidFacts.island`): dimension, package, component axis, every vessel's inventory (volume, level height, open flag, moles, energy, solids, rigid flag, the oil and aqueous slot amounts) with its state's T, P and split, every filter's cake by device identity, and for a certified island its certificate base with the drift its replay adds (`Drift`). A registration's facts never carry its hold.
- **What the state holds.** An island unit: dimension, package, the compound list (0 for the whole package, else its ids) and its water choice, status, fences, the graph (nodes with their inventories, geometry and pool; pipes with sections, control, ports, masks, actuator area and heat law with its slice inputs), state, the last solved interval (endpoint modes, heads, end flows, boundary transfers with their kinds), and for a certified island its signature and base graph (the base, not the materialised state, so the unit does not change while the island stays certified). The topology unit: the queued events, each running column's `ColumnUnit.State`, each driver's `ShaftUnit.State`, and the held registrations and shaft parts by identity. A column state that does not validate is dropped alone and that column solves again.
- **Rebuild from facts** (`FluidFacts.rebuild`): the registry, its settings and the ledger's totals are the saved ones; the queued events are state and are dropped. Vessel contents and filter cakes are placed by device identity, a certified island's at its committed tick. The devices compile into islands as a topology change compiles them, under new identities, awake at the epoch with no solved interval, cadence `REBUILT_CADENCE`, status `WAITING: rebuilt from saved facts (<reason>)[; removed by the property data: …]`. Generators and voids start from their specs (clamped into the package's input box). A registered vessel with no saved contents starts empty; the contents of a vessel that is no registered reservoir are dropped and booked as removed. Each rebuilt island runs on its own list (`IslandSets.held`). A portless `PUMP` or `COMPRESSOR` registration (the removed one-block mover) is dropped and named in a WARN line (`World.removedMovers`), and the load takes the FACTS route.
- **Restatement** on changed property data or basis (owner decisions D2, D3), per island: a vessel of an island whose records did not move keeps its contents bit for bit, on its own list (a compound added to the package restates no island). Elsewhere every amount is mapped onto the current axis by id, through the package's aliases; a vessel keeps its moles, volume, solids and temperature; `flashTP` at its saved T and P gives its energy and split, and the island's first interval closes it to its wall. A component or solid the data no longer has leaves the world: its moles and kilograms, and the vessels' energy difference, go to `WorldTopologyLedger.Restated` (cumulative; not live stock); the islands' status names what was removed; the load logs `fluid_checkpoint status=RESTATED vessels=… energy_delta_J=… removed=…`. A row with the oil and aqueous slots rebuilds its three slots bit for bit with no flash; an older row reads through `FluidThermodynamics.fromLegacySplit`.
- **A damaged facts section refuses the world** and leaves every file untouched: `Unreadable`, a missing, short or damaged pack, a digest mismatch, or a world-less checkpoint whose state cannot be used. `FluidSavedData.open` reads the checkpoint again to name the reason and throws `Existing fluid authority could not be read: …`. The authority opens the accessory store first (`InstrumentService.Store.open`), before this data and before any runtime starts ([instrument-accessories.md](instrument-accessories.md)).
- **The one reset** (owner decision D4): `FluidSavedData.open` catches the `FormatMismatchException` of a world saved before the envelope, logs `fluid_checkpoint status=RESET`, and starts an empty authority on `resetForFormatChange()` (new pack numbers skip the old packs, which stay until the new core commits). Placed blocks and vessels register again as their chunks load (`loadedChunk`, `StructureRegistration.restoreFluidBinding`). `FluidSavedData.load` itself never resets.
- **A unit is reused only for the package key it names** (`Ref.packageId`), so a renamed world writes its islands again. A pack is deleted only when neither the new core nor the previous core references it.
- **One value, one encoding.** `Writer`/`Reader`: LEB128, sparse doubles, exact bits. A solid population is written as its material's id, revision, density and heat capacity, its size and mass; the state reads it back as the record that id names in the island's package (a crystal or a custom solid), and an unknown id or other constants fail the state.

## Laws and constants

**Core record** (`data/icheme_fluid_core.dat`): `Envelope`, `StateFormat`, `FactsSchema`, `Epoch` (the ledger's online tick), the `Strings` table, `Packages` (per package and compound list: `Package`, `PropertyRevision`, `EnergyRevision`, `EnergyComponents`, `Compounds` when the list is not the whole package, `Dry` for a list without water, `TimeScale` when the factor is not 1), the pack list with sizes, `NextPack`, `NextGeneration`, the topology unit reference and `Islands` in columns (identity, revision, unit generation, `Online`/`Committed`/`Retry`/`Cadence`, the certificate summary `Base` (or `AWAKE`)/`Since`/`Start`/`Horizon`, the unit location with its SHA-256); `SHA256` covers every field. Packs: `data/icheme_fluid/fluid-<n>.pack`.

**Package key** (`PackageKey(packageId, compounds, wet)`, `IslandEntry`): a null list is the package's whole list, which a world never writes, and is always wet; at most `MAXIMUM_COMPOUNDS`, no water, no duplicates; `wet` false is the model on the list without water in its cubic, so an island without water reloads on its own model and keeps its certificate (copy a key or an `IslandEntry` with its `wet`). `PackageKey.on(network)` is `network.on(compounds, wet)`. A load on another time factor rebuilds the islands from their facts ("the time factor changed from a to b") and restates nothing.

**Endpoint modes** are `u8` ordinals of `FlowControl.Mode` in the state; an ordinal past the last fails the state.

**Certificate field**: the replay revision is written in the unit's 32-byte field, its characters zero-padded; an older save's digest reads as its hex digits, which is another revision, so its certificate is discarded once and its islands keep their inventory.

| Constant | Meaning | File |
|---|---|---|
| `ENVELOPE`, `UNIT_MAGIC`, `UNIT_ENVELOPE` | the frozen envelope | `FluidCheckpointCodec` |
| `VERSION` | the state format | `FluidCheckpointCodec` |
| `MAXIMUM_COMPOUNDS` | a package key's list | `FluidCheckpointCodec` |
| `SCHEMA`, `ALIASES` | the facts schema; renamed constants | `FluidFacts` |
| `REBUILT_CADENCE` | a rebuilt island's first cadence | `FluidFacts` |

## Formats

The current values of `ENVELOPE`, `VERSION`, `SCHEMA` and `IslandCertificate.REPLAY_REVISION` are rows of [FORMATS.md](../FORMATS.md). A change to a facts schema or a shape field adds a corpus image under `src/test/resources/compat/fluid/` (AGENTS.md) and changes its row in `FORMATS.md` in the same commit.

## Tests and gates

| Rule | Test |
|---|---|
| Save fields, packs, the envelope, refusal | `FluidCheckpointCodecTest` (incl. `aCrystalIsReadBackAsItsRecordBesideACustomSolidAndAnIdNamingNoRecordIsRefused`, `aChangedThermodynamicBasisRebuildsFromFactsAndNeverReinterpretsStock`, `anIslandWithoutWaterComesBackOnItsOwnModel`, `aVesselsLevelHeightRoundTrips`), `FluidCheckpointFormatTest.preEnvelopeFormatsAreAMismatchAndANewerPackIsUnreadable`, `FluidCheckpointStoreTest`, GameTest `FluidCheckpointGameTests` (the saved world loads EXACT) |
| The facts route, restatement, the corpus | `FluidFactsCompatTest` (quick suite: every image under `compat/fluid/` rebuilds to its recorded totals, and the test fails while this build's schema has no image, writing one under `build/compat/fluid/`; a world with one-block movers opens without them; an exchanger's heat in transit is a fact of its sides; a changed basis restates keeping amounts and T; unreadable facts refuse the world; a missing field reads its default and an extra one refuses) |
| A crystal booked as its component | `WorldTopologyLedgerTest.aTankBrokenHoldingDryIceBooksItsCrystalAsItsComponent` |
| Device names (block-entity NBT) | `DeviceNamesCompatTest` (image `compat/names/names-1.snbt`) |
| Gameplay regression | `McpGameplayRegressionTest` |

The reset itself has no test: no unit test opens a world. Tests edit images with `CheckpointImages`.

## Traps

- **A world-less checkpoint is a fixture, not a world.** The codec writes checkpoints with islands and no topology unit (in-memory images, regression fixtures) and `FluidSavedData.load` reads them exactly; one whose state cannot be used has no registry to rebuild from and is refused; `FluidSavedData.open` refuses one as a world with the fresh-world message.
- **A certified island's unit holds its base, not its materialised state.**
- **A new subsystem's facts never go into the root record of a section that is not last**: append a section.
- **The state format is not compatibility**: a mismatch drops the state and the engine rebuilds it; never write a migration for it.
- **Do not test against player worlds**: the corpus images are the gates.

**Snapshot:** `d6f721b94`, 2026-11-15.
