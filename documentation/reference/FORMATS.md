# Saved and wire formats

Every saved and wire format of the mod, with its current value and the constant that declares it. Read from the code at `259d22a2c`, 2026-11-13. Paths are under `src/main/java/com/wormzjl/icheme/` unless they start with `src/test`. Kinds: **facts** (what the player built and put in; never discarded), **state** (the engine computes it again; a mismatch drops it), **wire** (client and server packets; no compatibility, AGENTS.md "Saves and data formats").

A batch that changes a format changes its row here in the same commit. Where to take the next number: [COLLABORATION.md](COLLABORATION.md), "Numbers". `FormatsTableTest` checks each row: every `Class#FIELD` against the row's value, every inline string and digest tag against the file named.

## Saves

| Format | Value | Declared at | Kind | What a mismatch does |
|---|---|---|---|---|
| Fluid checkpoint envelope | 1 | [`runtime/fluid/FluidCheckpointCodec.java`](../../src/main/java/com/wormzjl/icheme/runtime/fluid/FluidCheckpointCodec.java) line 124, `FluidCheckpointCodec#ENVELOPE` (digest tag `icheme-fluid-checkpoint-envelope-1`) | facts frame | Another envelope: `Unreadable`, the world is refused and its files kept (`readCore`, line 792). No envelope (state format 19 or older): `FormatMismatchException`, the fluid network is reset once (owner decision D4, `FluidSavedData.open`) |
| Fluid checkpoint state format | 50 | `FluidCheckpointCodec.java` line 120, `FluidCheckpointCodec#VERSION` | state | Any other state format reads; the islands are rebuilt from the facts (route FACTS). Below 20 inside an envelope: `Unreadable` (line 794) |
| Fluid storage unit magic, unit envelope | `0x43434655`, 13 | `FluidCheckpointCodec.java` line 128, `FluidCheckpointCodec#UNIT_MAGIC`, `FluidCheckpointCodec#UNIT_ENVELOPE` | facts frame | The unit is not read (`IllegalArgumentException`, lines 316-317); the checkpoint does not load |
| Fluid facts schema | 25; enum aliases: none | [`runtime/fluid/FluidFacts.java`](../../src/main/java/com/wormzjl/icheme/runtime/fluid/FluidFacts.java) line 82, `FluidFacts#SCHEMA` (aliases line 84, `ALIASES`) | facts | Schemas 1 to 25 read, a missing later field at its default. Above 25: `Unreadable`, the world is refused (`FluidCheckpointCodec` lines 205, 795) |
| Fluid pack magic, pack format | `0x43434650`, 1 | [`runtime/fluid/FluidCheckpointStore.java`](../../src/main/java/com/wormzjl/icheme/runtime/fluid/FluidCheckpointStore.java) line 46, `FluidCheckpointStore#PACK_MAGIC`, `FluidCheckpointStore#PACK_FORMAT` | facts frame | Another pack format: `Unreadable`, the world is refused (line 408); another magic: the pack is not read (line 407) |
| Certificate graph identity | 10 (`icheme-certificate-graph-identity-10`) | [`runtime/fluid/IslandCertificate.java`](../../src/main/java/com/wormzjl/icheme/runtime/fluid/IslandCertificate.java) line 357, `graphIdentity` (a digest tag, no constant) | state | A saved certificate whose `Signature` differs is discarded, the inventory kept; the island solves again |
| Equipment shape fields | 12 | No production constant. The corpus image is `src/test/resources/compat/equipment/shapes-12.snbt`; the test constant is `src/test/java/com/wormzjl/icheme/science/equipment/EquipmentShapeCompatTest.java` line 503, `EquipmentShapeCompatTest#SHAPE_FIELDS` | facts | Not saved as a number. Fields are read by name; a shape without a later field reads its default (`science/equipment/ShapeRepair`); a retired kind is removed whole |
| Accessory store `icheme_accessories`: facts schema, state format | 6, 1 | [`runtime/instrument/AccessoryFacts.java`](../../src/main/java/com/wormzjl/icheme/runtime/instrument/AccessoryFacts.java) line 28, `AccessoryFacts#SCHEMA`, `AccessoryFacts#STATE` | facts; state | A newer schema, or damaged facts, refuses the store (`InstrumentService.Store.load`, line 325); state of another format is dropped, the facts kept |
| Control store `icheme_control` | 4 | [`runtime/control/ControlFacts.java`](../../src/main/java/com/wormzjl/icheme/runtime/control/ControlFacts.java) line 18, `ControlFacts#FORMAT` | facts | Formats 1 to 4 read (2 and 3 without a lane frame); any other throws "Unsupported or damaged control facts" (`load`, line 354) |
| Assembly store `icheme_assemblies` | 2 | [`runtime/assembly/AssemblyStore.java`](../../src/main/java/com/wormzjl/icheme/runtime/assembly/AssemblyStore.java) line 31, `AssemblyStore#FORMAT` | state | The store is dropped (`assembly_store status=STATE_DROPPED`) and rebuilt (`load`, line 188) |
| Pipe rack lane copy | `rack-3` | [`world/level/block/entity/PipeRackBlockEntity.java`](../../src/main/java/com/wormzjl/icheme/world/level/block/entity/PipeRackBlockEntity.java) line 58, `PipeRackBlockEntity#FORMAT` (a name, not written to the tag) | facts copy | Lanes are read by name; a damaged lane is dropped from the copy and the registry's ledger puts it back at the next load; a rack-1 copy reads as before |
| Process simulator sheet file | 2 | [`science/flowsheet/FlowsheetFile.java`](../../src/main/java/com/wormzjl/icheme/science/flowsheet/FlowsheetFile.java) line 41, `FlowsheetFile#FORMAT` | facts (no compatibility) | Newer or older: the file is refused (`FlowsheetFailure` `file.newer`, `file.older`, lines 71-73) |
| Process simulator block body | 1 | [`science/flowsheet/SheetBody.java`](../../src/main/java/com/wormzjl/icheme/science/flowsheet/SheetBody.java) line 22, `SheetBody#FORMAT` | wire and block store | Another first byte: refused as damaged (`Refused(DAMAGED)`, line 93) |
| Process simulator run results | 4 | [`network/SimulatorResults.java`](../../src/main/java/com/wormzjl/icheme/network/SimulatorResults.java) line 43, `SimulatorResults#FORMAT` | state | The results are dropped (`read` answers null, line 116); the next run rebuilds them |
| V3 column input schema | 1 | [`science/column/v3/V3ColumnInput.java`](../../src/main/java/com/wormzjl/icheme/science/column/v3/V3ColumnInput.java) line 36, `V3ColumnInput#SCHEMA_VERSION` | wire and design | `ColumnWire` refuses the packet (`DecoderException`, line 147); `V3ColumnProblemResolver` refuses the input (line 131) |

## Wire protocols

Each string is a NeoForge payload registrar version. Client and server must carry the same string for every registrar; with another one NeoForge refuses the connection. None is optional.

| Registrar | Value | Declared at |
|---|---|---|
| Fluid menu, edits, tank watch, converter view | `fluid-24` | [`network/FluidNetwork.java`](../../src/main/java/com/wormzjl/icheme/network/FluidNetwork.java) line 70, `FluidNetwork#PROTOCOL` |
| Sight glass watch and display | `sight-glass-3` | `FluidNetwork.java` line 855 (inline) |
| Mover and driver watches | `mover-4` | `FluidNetwork.java` line 862 (inline) |
| Flare display | `flare-2` | `FluidNetwork.java` line 892 (inline) |
| Still display | `still-1` | `FluidNetwork.java` line 898 (inline) |
| Gauge watch, display and save | `instruments-1` | `FluidNetwork.java` line 904 (inline) |
| Pipe tool settings | `pipeline-tool-5` | `FluidNetwork.java` line 908 (inline) |
| Equipment constructor, rename, far silhouettes | `equipment-23` | [`network/EquipmentNetwork.java`](../../src/main/java/com/wormzjl/icheme/network/EquipmentNetwork.java) line 53, `EquipmentNetwork#PROTOCOL` |
| Machine assemblies | `assembly-6` | [`network/AssemblyNetwork.java`](../../src/main/java/com/wormzjl/icheme/network/AssemblyNetwork.java) line 57, `AssemblyNetwork#PROTOCOL` |
| Machine pages | `machine-4` | [`network/MachineNetwork.java`](../../src/main/java/com/wormzjl/icheme/network/MachineNetwork.java) line 48, `MachineNetwork#PROTOCOL` |
| Controllers and DCS | `control-5` | [`network/ControlNetwork.java`](../../src/main/java/com/wormzjl/icheme/network/ControlNetwork.java) line 174 (inline) |
| Shared line commands | `lines-1` | [`network/LineNetwork.java`](../../src/main/java/com/wormzjl/icheme/network/LineNetwork.java) line 130 (inline) |
| Shaft speeds | `rotation-1` | [`network/RotationNetwork.java`](../../src/main/java/com/wormzjl/icheme/network/RotationNetwork.java) line 104 (inline; registered from `FluidNetwork` line 850) |
| Tutorial | `tutorial-1` | [`network/TutorialNetwork.java`](../../src/main/java/com/wormzjl/icheme/network/TutorialNetwork.java) line 33, `TutorialNetwork#PROTOCOL` |
| Process simulator | `3` (no prefix) | [`network/ProcessSimulatorNetwork.java`](../../src/main/java/com/wormzjl/icheme/network/ProcessSimulatorNetwork.java) line 39, `ProcessSimulatorNetwork#PROTOCOL` |

Fifteen strings in nine registrar classes, nine of them inline literals (codebase review P3-22 proposes one `NetworkProtocol.VERSION`).

## Native library

The JNI layout between Java and the Rust column library `icheme_column` (`src/main/rust/LAYOUT.md`). No compatibility: the library is built with the jar, and a library of another ABI or formulation is refused at load and the column runs on its Java path. Nothing is saved.

| Format | Value | Declared at | What a mismatch does |
|---|---|---|---|
| Native column ABI | 1 | [`science/column/v3/NativeColumnSolver.java`](../../src/main/java/com/wormzjl/icheme/science/column/v3/NativeColumnSolver.java) line 38, `NativeColumnSolver#ABI`; its twin `src/main/rust/icheme-jni/src/layout.rs` line 7, `ABI` | The library is refused (one log line) and the column runs on its Java path; the copy in the game folder is named `icheme_column-<ABI>`, so another ABI is never picked up |
| Native formulation token | `FORMULATION_REVISION;WET_ASSUMPTIONS_REVISION;HEAT_ASSUMPTIONS_REVISION` of `V3ColumnCalculator` | `NativeColumnSolver.java` line 40, `FORMULATION`; its twin `layout.rs` line 13, `FORMULATION` | The same: refused, Java path |
