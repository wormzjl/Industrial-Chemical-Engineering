# Saved and wire formats

Every saved and wire format of the mod, with its current value and the constant that declares it. Read from the code at `259d22a2c`, 2026-11-13. Paths are under `src/main/java/com/wormzjl/icheme/` unless they start with `src/test`. Kinds: **facts** (what the player built and put in; never discarded), **state** (the engine computes it again; a mismatch drops it), **wire** (client and server packets; no compatibility, AGENTS.md "Saves and data formats").

A batch that changes a format changes its row here in the same commit. Where to take the next number: [COLLABORATION.md](COLLABORATION.md), "Numbers". `FormatsTableTest` checks each row: every `Class#FIELD` against the row's value, every inline string and digest tag against the file named.

## Saves

| Format | Value | Declared at | Kind | What a mismatch does |
|---|---|---|---|---|
| Fluid checkpoint envelope | 1 | [`runtime/fluid/FluidCheckpointCodec.java`](../../src/main/java/com/wormzjl/icheme/runtime/fluid/FluidCheckpointCodec.java), `FluidCheckpointCodec#ENVELOPE` (digest tag `icheme-fluid-checkpoint-envelope-1`) | facts frame | Another envelope: `Unreadable`, the world is refused and its files kept (`readCore`, line 792). No envelope (state format 19 or older): `FormatMismatchException`, the fluid network is reset once (owner decision D4, `FluidSavedData.open`) |
| Fluid checkpoint state format | 50 | `FluidCheckpointCodec.java`, `FluidCheckpointCodec#VERSION` | state | Any other state format reads; the islands are rebuilt from the facts (route FACTS). Below 20 inside an envelope: `Unreadable` (line 794) |
| Fluid storage unit magic, unit envelope | `0x43434655`, 13 | `FluidCheckpointCodec.java`, `FluidCheckpointCodec#UNIT_MAGIC`, `FluidCheckpointCodec#UNIT_ENVELOPE` | facts frame | The unit is not read (`IllegalArgumentException`, lines 316-317); the checkpoint does not load |
| Fluid facts schema | 27; enum aliases: none (batch 2026-11-15-fluid-interfaces: 26 a device spec's `ambient`, the gas intake's; 27 the control valve source `ON_OFF`) | [`runtime/fluid/FluidFacts.java`](../../src/main/java/com/wormzjl/icheme/runtime/fluid/FluidFacts.java), `FluidFacts#SCHEMA` (aliases line 88, `ALIASES`) | facts | Schemas 1 to 27 read, a missing later field at its default. Above 27: `Unreadable`, the world is refused (`FluidCheckpointCodec` lines 205, 795) |
| Fluid pack magic, pack format | `0x43434650`, 1 | [`runtime/fluid/FluidCheckpointStore.java`](../../src/main/java/com/wormzjl/icheme/runtime/fluid/FluidCheckpointStore.java), `FluidCheckpointStore#PACK_MAGIC`, `FluidCheckpointStore#PACK_FORMAT` | facts frame | Another pack format: `Unreadable`, the world is refused (line 408); another magic: the pack is not read (line 407) |
| Certificate replay revision | `replay-1` (in the checkpoint's 32-byte field, zero-padded; replaced graph identity 10) | [`runtime/fluid/IslandCertificate.java`](../../src/main/java/com/wormzjl/icheme/runtime/fluid/IslandCertificate.java), `IslandCertificate#REPLAY_REVISION` | state | A saved certificate whose `Signature` differs is discarded, the inventory kept; the island solves again. An older save's graph digest reads as its 64 hex digits (`FluidCheckpointCodec.replayRevision`), never the declared revision |
| Equipment shape fields | 12 | No production constant. The corpus image is `src/test/resources/compat/equipment/shapes-12.snbt`; the test constant is `src/test/java/com/wormzjl/icheme/science/equipment/EquipmentShapeCompatTest.java`, `EquipmentShapeCompatTest#SHAPE_FIELDS` | facts | Not saved as a number. Fields are read by name; a shape without a later field reads its default (`science/equipment/ShapeRepair`); a retired kind is removed whole |
| Accessory store `icheme_accessories`: facts schema, state format | 7, 1 (7: the fitting types `ORIFICE` and `ACTUATED_VALVE` and a fitting's `ratio`, batch 2026-11-15-fluid-interfaces) | [`runtime/instrument/AccessoryFacts.java`](../../src/main/java/com/wormzjl/icheme/runtime/instrument/AccessoryFacts.java), `AccessoryFacts#SCHEMA`, `AccessoryFacts#STATE` | facts; state | A newer schema, or damaged facts, refuses the store (`InstrumentService.Store.load`, line 325); state of another format is dropped, the facts kept |
| Control store `icheme_control` | 5 (5: the alarm beacons' list `alarms`, batch 2026-11-15-fluid-interfaces) | [`runtime/control/ControlFacts.java`](../../src/main/java/com/wormzjl/icheme/runtime/control/ControlFacts.java), `ControlFacts#FORMAT` | facts | Formats 1 to 5 read (2 and 3 without a lane frame, 1 to 4 with no alarm beacons); any other throws "Unsupported or damaged control facts" (`load`, line 466) |
| Assembly store `icheme_assemblies` | 2 | [`runtime/assembly/AssemblyStore.java`](../../src/main/java/com/wormzjl/icheme/runtime/assembly/AssemblyStore.java), `AssemblyStore#FORMAT` | state | The store is dropped (`assembly_store status=STATE_DROPPED`) and rebuilt (`load`, line 188) |
| Pipe rack lane copy | `rack-3` | [`world/level/block/entity/PipeRackBlockEntity.java`](../../src/main/java/com/wormzjl/icheme/world/level/block/entity/PipeRackBlockEntity.java), `PipeRackBlockEntity#FORMAT` (a name, not written to the tag) | facts copy | Lanes are read by name; a damaged lane is dropped from the copy and the registry's ledger puts it back at the next load; a rack-1 copy reads as before |
| Process simulator sheet file | 2 | [`science/flowsheet/FlowsheetFile.java`](../../src/main/java/com/wormzjl/icheme/science/flowsheet/FlowsheetFile.java), `FlowsheetFile#FORMAT` | facts (no compatibility) | Newer or older: the file is refused (`FlowsheetFailure` `file.newer`, `file.older`, lines 71-73) |
| Process simulator block body | 1 | [`science/flowsheet/SheetBody.java`](../../src/main/java/com/wormzjl/icheme/science/flowsheet/SheetBody.java), `SheetBody#FORMAT` | wire and block store | Another first byte: refused as damaged (`Refused(DAMAGED)`, line 93) |
| Process simulator run results | 4 | [`network/SimulatorResults.java`](../../src/main/java/com/wormzjl/icheme/network/SimulatorResults.java), `SimulatorResults#FORMAT` | state | The results are dropped (`read` answers null, line 116); the next run rebuilds them |
| V3 column input schema | 1 | [`science/column/v3/V3ColumnInput.java`](../../src/main/java/com/wormzjl/icheme/science/column/v3/V3ColumnInput.java), `V3ColumnInput#SCHEMA_VERSION` | wire and design | `ColumnWire` refuses the packet (`DecoderException`, line 147); `V3ColumnProblemResolver` refuses the input (line 131) |

## Wire protocols

Every payload registrar of the mod carries one version, [`network/NetworkProtocol.java`](../../src/main/java/com/wormzjl/icheme/network/NetworkProtocol.java), `NetworkProtocol.VERSION` (codebase review P3-22). NeoForge negotiates each payload channel (its type id) at login, with the version of the registrar it was registered on: a client and a server whose versions differ on any channel, or where one lacks a channel the other requires, are refused with the channels and the reasons (`NetworkComponentNegotiator`). None is optional. So client and server must be the same version of the mod. Bump the version at any change of a payload's bytes, type ids or registration. `icheme-2` (batch 2026-11-15-equipment-visual-design): a steam turbine's inlet class gained LP, declared first, so the design point's steam ordinal (`EquipmentNetwork.writeDuty`, 0 for none, else the ordinal plus one) moved for every class. `icheme-3` (batch 2026-11-15-fluid-interfaces): the conversion rules sync, `FluidNetwork.ConversionRulesSync` (S→C, each loaded rule's id and its file with names resolved, at login and after each reload, on `OnDatapackSyncEvent`); and the alarm beacon's edit (`alarm_edit`) and view (`alarm_view`) payloads (W9); the controllers' `control_browse` also pages a beacon's input choices.

| Registrar | Value | Declared at | Read by |
|---|---|---|---|
| Fluid menu, edits, tank watch, converter view, conversion rules sync | `icheme-3` (was `fluid-24`) | `NetworkProtocol#VERSION` | `FluidNetwork.register` |
| Sight glass watch and display | `icheme-3` (was `sight-glass-3`) | `NetworkProtocol#VERSION` | `FluidNetwork.registerDisplays` |
| Mover and driver watches | `icheme-3` (was `mover-4`) | `NetworkProtocol#VERSION` | `FluidNetwork.registerDisplays` |
| Flare display | `icheme-3` (was `flare-2`) | `NetworkProtocol#VERSION` | `FluidNetwork.registerDisplays` |
| Still display | `icheme-3` (was `still-1`) | `NetworkProtocol#VERSION` | `FluidNetwork.registerDisplays` |
| Gauge watch, display and save | `icheme-3` (was `instruments-1`) | `NetworkProtocol#VERSION` | `FluidNetwork.registerDisplays` |
| Pipe tool settings | `icheme-3` (was `pipeline-tool-5`) | `NetworkProtocol#VERSION` | `FluidNetwork.registerDisplays` |
| Equipment constructor, rename, far silhouettes | `icheme-3` (was `equipment-23`) | `NetworkProtocol#VERSION` | `EquipmentNetwork.register` |
| Machine assemblies | `icheme-3` (was `assembly-6`) | `NetworkProtocol#VERSION` | `AssemblyNetwork.register` |
| Machine pages | `icheme-3` (was `machine-4`) | `NetworkProtocol#VERSION` | `MachineNetwork.register` |
| Controllers, DCS and alarm beacons | `icheme-3` (was `control-5`) | `NetworkProtocol#VERSION` | `ControlNetwork.register` |
| Shared line commands | `icheme-3` (was `lines-1`) | `NetworkProtocol#VERSION` | `LineNetwork.register` |
| Shaft speeds | `icheme-3` (was `rotation-1`) | `NetworkProtocol#VERSION` | `RotationNetwork.register` (called from `FluidNetwork.registerDisplays`) |
| Tutorial | `icheme-3` (was `tutorial-1`) | `NetworkProtocol#VERSION` | `TutorialNetwork.register` |
| Process simulator | `icheme-3` (was `3`) | `NetworkProtocol#VERSION` | `ProcessSimulatorNetwork.register` |

Fifteen registrars in nine classes, one version. A new payload joins one of them; a new registrar gets a row here.

## Native library

The JNI layout between Java and the Rust column library `icheme_column` (`src/main/rust/LAYOUT.md`). No compatibility: the library is built with the jar, and a library of another ABI or formulation is refused at load and the column runs on its Java path. Nothing is saved.

| Format | Value | Declared at | What a mismatch does |
|---|---|---|---|
| Native column ABI | 1 | [`science/column/v3/NativeColumnSolver.java`](../../src/main/java/com/wormzjl/icheme/science/column/v3/NativeColumnSolver.java), `NativeColumnSolver#ABI`; its twin `src/main/rust/icheme-jni/src/layout.rs` line 7, `ABI` | The library is refused (one log line) and the column runs on its Java path; the copy in the game folder is named `icheme_column-<ABI>`, so another ABI is never picked up |
| Native formulation token | `FORMULATION_REVISION;WET_ASSUMPTIONS_REVISION;HEAT_ASSUMPTIONS_REVISION` of `V3ColumnLabels` | `NativeColumnSolver.java` line 40, `FORMULATION`; its twin `layout.rs` line 13, `FORMULATION` | The same: refused, Java path |
