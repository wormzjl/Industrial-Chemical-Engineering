# D-F1 The sheet's and the wire's small structures (P3-08, P3-25, P3-23, P3-22): report

Batch D-F1 of `WAVE_D_BRIEFS.md` (subagent, Opus high). Branch `worktree-agent-a41f68f154eae4e89`, fast-forwarded to `claude/codebase-review-refactor-8a85ef` at `380015b56`.

| Item | Commit | Kind | Done |
|---|---|---|---|
| P3-08 | `bf8bad52c` | structure only | Partly: the unit <-> sizing cycle is removed; the core <-> sizing cycle stays (section 1) |
| P3-25 | `1c2103590` | structure only | Yes: `network/ProcessSolveCoordinator` is `runtime/ProcessSolveCoordinator` |
| P3-23 | `d89eb6f02`, `f59857e2c` | structure only for valid input | Yes: one `network/Wire` helper for every list count of `network/**`; the watch payloads stay |
| P3-22 | `94a58406d` | wire change | Yes: `NetworkProtocol.VERSION = "icheme-1"` on all fifteen registrars |
| Report | this commit | documents | This report and the code guide |

**Nothing was compiled or run** (the brief: no Gradle, no JVM). Every claim below is from reading the code; section 6 lists what I could not verify.

Summary:
- **P3-08.** The names of a block's members (`trim`, `cooler`, `pa <id> <part>`, `<block>/<member>`) are in a new core class `science/flowsheet/Members`. `unit/ColumnDoor` reads them there, so `unit/` names nothing of `sizing/`. `SheetSizing.TRIM`, `SheetSizing.member` and `SheetSizing.PumparoundMember` stay (the client, the network and tests use them) and forward to `Members`. The brief's two moves (`SizingFacts` into the core, `SizingFactsFile` into sizing) are **not made**: they would not remove the core <-> sizing cycle, because the facts' own component types are sizing types. Section 1 draws the cycle and gives three options.
- **P3-25.** The class moves with its body unchanged. Its callers follow: `IChemE` (import), `ProcessSolveServices` (two qualified calls, now same-package names), `FluidPropertyReloadGameTests` (import). `FluidWorldAuthority` is not edited: the coordinator names it by its full name, and it does not name the coordinator.
- **P3-23.** `Wire.count(buffer, maximum, minimumBytes, what)`, `Wire.doubles`, `Wire.positions` (read and write) and `Wire.finite`. 21 count guards in 9 classes read through it, each with its own bound (section 3.1; `ColumnWire`'s twelve call sites count as one guard). The bytes read and written are unchanged. Eight guards threw `IllegalArgumentException` and now throw `DecoderException`. No test expected the old type at those guards. 17 guards had no byte check; they now also refuse a count longer than the bytes left, at the elements' least size. The three position-list watch payloads stay apart from the generic `FluidNetwork.Watch` (section 3.2).
- **P3-22.** All fifteen registrars carry `NetworkProtocol.VERSION`, one registrar per network class as before. The six `PROTOCOL` constants and the nine inline strings are removed. `icheme-1` differs from every string in use before. A mismatch is refused at login (section 4). `FORMATS.md` keeps one row per registrar: `FormatsTableTest` needs at least 25 checked rows, and one wire row would leave 17. CHANGELOG: one line under Changed.
- **One file outside the lane: `build.gradle`** (`f59857e2c`, one entry). `columnGates` copies only the main classes its list names (`network/ColumnWire*`). `ColumnWire` now calls `network/Wire`, so the column tests that load it through `ColumnCodecs` would fail with `NoClassDefFoundError`. AGENTS.md says a class missing from a gate group joins that group's list. I made this one-entry change so the column gates still load. The main thread may move it.
- **No facts schema, state format, shape field or saved name moved.** The sheet's facts are written by explicit JSON keys (`SizingFactsFile`), not by record component names, and no saved record changed.

## 1. P3-08: the cycles, what moved, what stayed

### 1.1 The imports, from the code (before `bf8bad52c`)

```
                 Flowsheet (component SizingFacts), FlowsheetCompiler (SizingFacts, SizedPassages.of per unit),
                 SizingFactsFile (15 sizing types: the size codec), WiredUnit (DesignBasis.DEFAULT.paFall())
   core (29) ─────────────────────────────────────────────────────────────────────────────► sizing (35)
      ▲  │                                                                                     │   │
      │  │ UnitTypes (the tile registry)                    20 files: FlowStream, FlowThermo,  │   │
      │  ▼                                                  Flowsheet, FlowsheetRun, Params ... │   │
   unit (22) ◄───────────────────────────────────── 12 files (ColumnDoor, ColumnUnit, CoolerUnit ...) │
      │   12 tiles: Calculation, WiredUnit, Port, Params ...  ──► core                              │
      └─── ColumnDoor: SheetSizing.TRIM, SheetSizing.PumparoundMember ──────────────────────────────┘  (removed)
```

After `bf8bad52c`: `unit/` names nothing of `sizing/` (grep `flowsheet.sizing` over `unit/`: no match). Core <-> sizing stays. Core <-> unit (`UnitTypes` registers the tiles; the tiles read the core) is outside the brief and stays.

### 1.2 Why the brief's moves would not remove core <-> sizing

The brief puts `SizingFacts` in the core and `SizingFactsFile` in sizing. The facts' components are sizing types:

- `UnitFacts.size` is `UnitSize`. That is a sealed interface over 16 `Size` records nested in the sizers (`PumpSizing.Size` ...). Its static helpers read `Limit` and `Reading`. On the class path (the unnamed module, as Gradle compiles), Java requires the permitted subclasses of a sealed type to be in its own package. So `UnitSize` cannot move into the core alone.
- `UnitFacts.type` is `UnitType`, and `LineFacts.size` is `LineSizing.Size`.
- `LineFacts.service` is `Service`, an enum built on `Limit` rows, with the stream rule `Service.of`.
- `SizingFacts.basis` is `DesignBasis`, and `UnitFacts.typeOf` switches over six `Size` records.

A core `SizingFacts` would therefore import at least eight sizing types. Two more edges would also stay:
- `FlowsheetFile` (core) would call the moved `SizingFactsFile`.
- `FlowsheetCompiler` calls `SizedPassages.of` for every unit.

`SizingFactsFile` also needs `JsonFields`, which is package-private in the core. That is about 15 methods and two fields that would have to become public.

### 1.3 Options for the core <-> sizing cycle (not implemented)

| Option | What | Cost | Risk |
|---|---|---|---|
| A. A facts package | `science/flowsheet/facts` below the core and sizing holds `SizingFacts`, `Mode`, `UnitFacts`, `LineFacts`, `DesignBasis`, `UnitType`, `Service` as a plain enum (its `Limit` rows and `of` stay in sizing), `LineSizing.Size`, `UnitSize` and the 16 `Size` records with `shape()`; `SizingFactsFile` beside them. `FlowsheetCompiler` takes the passages as a function from its caller (`FlowsheetEngine`'s callers: sizing and the client's runner). | Several hundred lines move; client imports in about 20 files | `UnitSize`'s helpers that read `Limit` move to sizing, which changes call sites in the client (not only imports) |
| B. One seam per edge | Keep the types. The core declares what it reads of sizing as interfaces: the sheet's sizing slot, a passages source, a facts codec. Sizing implements them. | `sheet.sizing()` is read in 77 places outside sizing; they would need a typed accessor | Wide touch for little gain |
| C. Accept and pin | Declare sizing's facts part of the sheet model, and pin today's four core -> sizing references in a test so they cannot grow | A small gate | None; the cycle stays |

My reading: C now, and A as its own item if the sheet's facts are to stand alone (it is the P3-07 "Tile descriptor" area, which touches the same files).

### 1.4 What `bf8bad52c` changed

- New `science/flowsheet/Members` (41 lines): `TRIM = "trim"`, `COOLER = "cooler"`, `PUMPAROUND = "pa"`, `member(block, member)` and the record `Pumparound(pumparound, part)` with `name` and `of`. The record is a copy of the parsing in `SheetSizing.PumparoundMember`.
- `unit/ColumnDoor.exchanger`: `Members.TRIM`, `Members.Pumparound.of`, `Members.COOLER` (it compared against the literal `"cooler"` before).
- `sizing/SheetSizing`: `TRIM = Members.TRIM` stays a compile-time constant, so `SizingPage`'s `case SheetSizing.TRIM ->` still compiles. `member` and `PumparoundMember.name`, `of` and `key` forward to `Members`. `PumparoundMember.COOLER = Members.COOLER`. The private `HEAD` is gone.
- Saved names: unchanged. `"pa " + id + " " + part`, `block + "/" + member`, `"trim"` and `"cooler"` are the same strings.

## 2. P3-25

| Where | Before | After |
|---|---|---|
| The class | `network/ProcessSolveCoordinator.java` | `runtime/ProcessSolveCoordinator.java`. The package line changes and the same-package import of `ProcessSolveServices` goes. The nested-type imports `ProcessSolveServices.ProcessSolveCompletion` and `ProcessSolveRequest` stay. The body is byte for byte the same. |
| `IChemE` | `import ...network.ProcessSolveCoordinator` | `import ...runtime.ProcessSolveCoordinator` |
| `runtime/ProcessSolveServices` lines 70 and 243 | `com.wormzjl.icheme.network.ProcessSolveCoordinator.drainCompletedCalculations(server)` | `ProcessSolveCoordinator.drainCompletedCalculations(server)` |
| `src/fluidGameTest/.../FluidPropertyReloadGameTests` | `import ...network.ProcessSolveCoordinator` | `import ...runtime.ProcessSolveCoordinator` |
| Not edited | `tools/cloud-science-harness/stubs/com/wormzjl/createcheme/network/ProcessSolveCoordinator.java` (a stub in the old `createcheme` package, not compiled) and `tools/test-archive-2026-10-21/` (an archive) | — |

`LayeringTest`:
- The class names the root `IChemE`, `runtime.*` and `science.plant`. No rule forbids `runtime` from naming the root or `science`.
- It names no `client` type, so `runtime-free-of-client` holds.
- `runtime -> network` (`map.mjs` FORBIDDEN, not yet pinned) loses this reference.

## 3. P3-23

### 3.1 The count guards (every one in `network/**`)

The review counted six guards. Reading `network/**` I found 21 count guards in 9 classes (one row each below; `ColumnWire.readCount` is one guard with twelve call sites), and all of them now read through `Wire.count`. "Least bytes" is the smallest encoding of one element, from its encoder:
- a VarInt, an enum and a UTF length take 1 byte at least;
- a `BlockPos`, a long and a double take 8; a UUID takes 16;
- an NBT tag takes 1 (its type byte); a float takes 4.

A valid packet always holds at least that much, so the new byte checks refuse only malformed input.

| Codec, site | Counted | Bound | Least bytes / element now | Before | After |
|---|---|---|---|---|---|
| `AssemblyNetwork.readOperation` | conditions | `MAX_STREAMS` 32 | 11 (enum, double, VarInt, UTF) | `DecoderException`, no byte check | `DecoderException`, 11 |
| same | streams | 32 | 42 (enum, VarInt, 5 doubles) | `DecoderException`, none | `DecoderException`, 42 |
| same | duties | 32 | 18 (enum, VarInt, 2 doubles) | `DecoderException`, none | `DecoderException`, 18 |
| `AssemblyNetwork.readSetpoints` (3 lists) | doubles | `MAX_VALUES` 64 | 8 | `DecoderException`, none | `Wire.doubles`, 8 |
| `AssemblyNetwork.read` | slots | `MAX_SLOTS` 256 | 21 (UTF, VarInt, enum, 2 longs, UTF, count) | `DecoderException`, none | `DecoderException`, 21 |
| same | a slot's parallel origins | `ColumnComponents.MAX_UNITS - 1` | 8 | `DecoderException`, none | `DecoderException`, 8 |
| same | ports | `MAX_PORTS` 1024 | 11 (long, enum, VarInt, enum) | `DecoderException`, none | `DecoderException`, 11 |
| same | members | `MAX_MEMBERS` 256 | 8 | `DecoderException`, none | `DecoderException`, 8 |
| `SimulatorResults.count` (every list) | items | `MAX_LIST` 4096 | 1 | `DecoderException`, count > readable | the same check |
| `SimulatorResults.doubles` | doubles | 4096 | 8 | `DecoderException`, 8 × count > readable | the same check |
| `V3InspectionCodec.boundedCount` | checks, advisories | 64, 16 | 1 | `DecoderException`, count > readable | the same check |
| `ColumnWire.readCount` (12 sites) | per site | per site | per site, unchanged (0, 1, 9, 11, 17) | `DecoderException` | the same |
| `ColumnWire.finite` | — | — | — | `DecoderException` "Non-finite V3 x" | `DecoderException` "Non-finite x" |
| `ControlNetwork.ColumnCommand` | set point values | `ColumnCommands.Command.MAX_VALUES` | 8 | `DecoderException`, none | `DecoderException`, 8 |
| `LineNetwork.Command` | route cells | `PipeRoute.Limits.DEFAULT.cells()` | 9 (position, VarInt) | **`IllegalArgumentException`**, none | `DecoderException`, 9 |
| `RotationNetwork.Watch` (watch, unwatch) | positions | `MAXIMUM_WATCHED` 256 | 8 | **`IllegalArgumentException`**, none | `Wire.positions`, 8 |
| `EquipmentNetwork.SilhouetteBatch` | placements | `SILHOUETTES_PER_BATCH` 32 | 10 (position, NBT type byte, VarInt length) | **`IllegalArgumentException`**, none | `DecoderException`, 10 |
| same | removals | `Silhouettes.MAXIMUM_STRUCTURES` | 8 | **`IllegalArgumentException`**, none | `Wire.positions`, 8 |
| `FluidNetwork.positions` (`TankWatch`, `DriverWatch`) | positions | `MAXIMUM_WATCHED` 64 | 8 | **`IllegalArgumentException`**, none | `Wire.positions`, 8 |
| `FluidNetwork.targets` (every `Watch` kind) | targets | 64 | 16 (position, identity) | **`IllegalArgumentException`**, none | `DecoderException`, 16 |
| `FluidNetwork.instrumentTargets` | instrument targets | 64 | 33 (position, host, UTF, UUID) | **`IllegalArgumentException`**, none | `DecoderException`, 33 |
| `FluidNetwork.NoiseSources` | sources | `Noise.MAXIMUM_SOURCES` | 17 (position, byte, 2 floats) | **`IllegalArgumentException`**, none | `DecoderException`, 17 |

The eight guards in bold change their exception type. None of them sits inside a `catch (IllegalArgumentException)`: they are in `StreamCodec.decode`, which Netty calls. Netty's `ByteToMessageDecoder` passes a `DecoderException` through and wraps any other exception in one, so the connection closes on a decode error either way.

Tests that could expect the old type:
- `FluidPacketCodecTest.theNoiseAnswerRoundTripsAndHoldsAtMostSixteenSources` expects `IllegalArgumentException` for an unknown noise kind. That is the kind check, which is unchanged. Its buffer holds 17 bytes after the count, so it passes the new minimum.
- `EquipmentNetworkCodecTest`'s `IllegalArgumentException` at line 283 is the `SilhouetteBatch` constructor, not the decode.
- `PipePresentationTest` lines 148 and 151 test `SightGlassPayload`'s flag and direction, not a count.

So no test changed for P3-23.

Messages changed, and no test reads them. The format is now "Invalid <what> count <n> (0..<max>)" and "Truncated <what> list". `ColumnWire` loses its "V3 " prefix, so no string is built per call.

The record constructors keep their own `IllegalArgumentException` checks (`TankWatch` and `DriverWatch` at most 64, `RotationNetwork.Watch` at most 256 with no duplicates, `SilhouetteBatch` at most 32). They run after a valid decode, as before.

### 3.2 The position-list watch payloads beside the generic one

| Payload | Type id | Stream codec | Registrar | Handler | Element on the wire | Bound and checks |
|---|---|---|---|---|---|---|
| `FluidNetwork.TankWatch` | `icheme:tank_watch` | `positions`, `positions` | `fluid` (`FluidNetwork.registerDisplays`) | `world.watchTanks` | `BlockPos`, 8 bytes | 64, no duplicate check |
| `FluidNetwork.DriverWatch` | `icheme:driver_watch` | `positions`, `positions` | `mover` | `world.watchDrivers` | `BlockPos`, 8 bytes | 64, no duplicate check |
| `RotationNetwork.Watch` | `icheme:rotation_watch` | `Wire.positions` ×2 | `rotation` (`RotationNetwork.register`) | `world.watchRotations` | `BlockPos`, 8 bytes | 256, duplicates refused, immutable copies |
| generic `FluidNetwork.Watch` (7 types: sight glasses, movers, flares, stills, towers, heaters, chimneys) | one per kind | `Watch.codec(type)`: `targets` ×2 | `sight-glass`, `mover`, `fluid`, `flare`, `still` | per kind | `Target`: `BlockPos` and long identity, 16 bytes | 64, duplicates refused |

The three do not become the generic one:
- its element carries an identity (16 bytes, not 8);
- their bounds differ (64, 64 and 256);
- only the rotation watch refuses duplicates.

They now share `Wire.positions`.

## 4. P3-22: the protocol's new form

- `network/NetworkProtocol`: `public static final String VERSION = "icheme-1"`. Every `event.registrar(...)` in `network/**` reads it. That is 15 calls: `FluidNetwork.register`, six in `FluidNetwork.registerDisplays`, `EquipmentNetwork`, `AssemblyNetwork`, `MachineNetwork`, `ControlNetwork`, `LineNetwork`, `RotationNetwork`, `TutorialNetwork` and `ProcessSimulatorNetwork`. The registrars stay one per class as before, and every payload keeps its type id, codec, direction and handler.
- `icheme-1` differs from every string in use before: `fluid-24`, `sight-glass-3`, `mover-4`, `flare-2`, `still-1`, `instruments-1`, `pipeline-tool-5`, `equipment-23`, `assembly-6`, `machine-4`, `control-5`, `lines-1`, `rotation-1`, `tutorial-1` and `3`.
- How NeoForge uses the argument (read in NeoForge 21.1.219's sources: `RegisterPayloadHandlersEvent.registrar`, `PayloadRegistrar`, `NetworkComponentNegotiator.negotiate`):
  - `registrar(version)` returns a `PayloadRegistrar` that stamps its version on each payload registered through it.
  - At login, each payload channel (its type id) is compared between client and server. The connection is refused if the flow directions differ, if `left.version().equals(right.version())` fails (`neoforge.network.negotiation.failure.version.mismatch`), or if a non-optional channel is missing on one side.
  - The reasons are listed per channel. None of the mod's payloads is optional.
  - So a client and a server of different builds (one before and one after this commit) are refused on every channel. That is the same effect as any earlier protocol bump, now for all channels at once.
- Removed constants: `FluidNetwork.PROTOCOL`, `EquipmentNetwork.PROTOCOL`, `AssemblyNetwork.PROTOCOL`, `MachineNetwork.PROTOCOL`, `TutorialNetwork.PROTOCOL` and the private `ProcessSimulatorNetwork.PROTOCOL`. Each class comment now names its old string. Grep over `src/**` and `tools/**/*.java`: no reference is left.
- Tests that pinned the old strings now pin `"icheme-1"`, with a comment:
  - `EquipmentNetworkCodecTest.deconstructionRequestsRoundTrip` (was `equipment-23`);
  - `FluidPacketCodecTest.theProtocolIsFluidNineteenWithDistinctStaticAndLivePayloads` (was `fluid-24`);
  - `FluidPacketCodecTest.theMachinePayloadsRoundTripOnTheirOwnRegistrar` (was `machine-4`).

### 4.1 `FORMATS.md` rows changed

The "Wire protocols" table keeps its 15 rows, one per registrar. Each row now reads value `icheme-1` (with "was `<old>`" as a note), declared at `NetworkProtocol#VERSION`, and a new fourth column names the registrar's method.

`FormatsTableTest` reads each row through the `Class#FIELD` pattern: the field is `NetworkProtocol#VERSION`, and the value parses as `icheme-1` (the text before " ("). No row says `(inline` any more.

One row in place of fifteen would fail the test: it needs at least 25 checked rows, and the saves and native rows give 16. The intro paragraph states the negotiation and the bump rule.

## 5. Tests for the main thread

The files changed since `380015b56`, and the tests that load them. The groups come from `build.gradle`:
- `test` leaves out `science/column/v3/**`, `science/equipment/**` and the slow gate classes;
- `columnGates` runs `science/column/v3/**`;
- `holdGates` runs the slow classes.

| Gradle task, `--tests` | What it proves |
|---|---|
| `compileJava`, `compileTestJava`, `compileFluidGameTestJava` | The moves compile. Points to check: section 6. |
| `test --tests com.wormzjl.icheme.network.AssemblyNetworkCodecTest` | The assembly view, operation and set points round-trip through `Wire`; a count out of range is refused (`aReadRefusesCountsOutOfRange`, `DecoderException`) |
| `test --tests com.wormzjl.icheme.network.ColumnWireTest` | `ColumnWire.readCount` and `finite` forward unchanged; the forged-count rejections hold |
| `test --tests com.wormzjl.icheme.network.V3InspectionCodecTest` | `boundedCount` forwards; truncated and long blobs are refused with `DecoderException` |
| `test --tests com.wormzjl.icheme.network.EquipmentNetworkCodecTest` | The silhouette batch round-trips with `Wire.positions`; the protocol pin `icheme-1` |
| `test --tests com.wormzjl.icheme.network.TutorialNetworkCodecTest` | The tutorial payloads still round-trip (their registrar changed only) |
| `test --tests com.wormzjl.icheme.runtime.fluid.FluidPacketCodecTest` | The fluid and machine payloads, the noise sources (count minimum 17 and the unchanged kind check), the protocol pins |
| `test --tests com.wormzjl.icheme.runtime.fluid.PipePresentationTest` | The sight glass payloads and their watch path are unchanged |
| `test --tests com.wormzjl.icheme.world.level.block.LineContractTest` | `LineNetwork.Command` round-trips through the new count (minimum 9) |
| `test --tests com.wormzjl.icheme.client.gui.flowsheet.SimulatorBlockTest` | `SimulatorResults` writes and reads through `Wire` |
| `test --tests com.wormzjl.icheme.science.flowsheet.sizing.SheetSizingTest --tests com.wormzjl.icheme.science.flowsheet.FlowsheetExamplesTest --tests com.wormzjl.icheme.science.flowsheet.FlowsheetFileTest --tests com.wormzjl.icheme.client.gui.flowsheet.SizingPageTest --tests com.wormzjl.icheme.client.gui.flowsheet.SheetEditorTest` | The member names (`Members`) keep sizing, the sheet file, the column door's exchanger types and the sizing page as before |
| `test --tests com.wormzjl.icheme.FormatsTableTest` | The new wire rows state `NetworkProtocol#VERSION` = `icheme-1`, with at least 25 rows checked |
| `test --tests com.wormzjl.icheme.LayeringTest` | `runtime/ProcessSolveCoordinator` and `science/flowsheet/Members` keep every layer and package rule |
| `test --tests com.wormzjl.icheme.runtime.ProcessSolveServicesRequestTest --tests com.wormzjl.icheme.runtime.fluid.CompletionWakeupTest` | The completion router at its new package, called by `ProcessSolveServices` |
| `columnGates` (whole group, after `f59857e2c`) | The column tests that load `ColumnWire` through `ColumnCodecs` find `network/Wire` in the group's copy |
| `holdGates --tests com.wormzjl.icheme.client.gui.flowsheet.CrudeUnitSampleTest --tests com.wormzjl.icheme.science.flowsheet.ColumnEjectorTest` | `SheetSizing.member`, `TRIM` and `PumparoundMember` forward to `Members` with the same keys (both name them) |
| The final lane's fluid GameTests | A client and a server on `icheme-1` connect and every payload registers once |

`FluidFactsCompatTest` is not reached: no facts file changed. `CrudeUnitSampleTest` and `ColumnEjectorTest` are slow gates (`holdGates`), not `test`.

## 6. Points I could not verify

1. **Not compiled.** The places where a compile error is most likely:
   - `SheetSizing.java` line 48: `TRIM = Members.TRIM` must stay a constant expression for `SizingPage`'s `case SheetSizing.TRIM ->`. By JLS 15.29 a qualified name of a constant variable is constant.
   - `Wire.java`: `FriendlyByteBuf.readVarInt`, `readBlockPos`, `writeBlockPos` and `readDouble` are called on `RegistryFriendlyByteBuf` arguments (a subclass).
   - `RotationNetwork.java` line 36: `Wire.positions` gives `List<BlockPos>` (an `ArrayList`) to the record's compact constructor, as `read` did.
   - `EquipmentNetwork.java` around line 425: `Wire.positions` replaces the removal loop; the record takes `List<BlockPos>`.
2. **Bytes.** Every `Wire` writer writes `writeVarInt(size)` and then the elements, as the replaced loops did. The readers read the same fields in the same order.
   - `AssemblyNetwork.readSetpoints` still evaluates its three lists left to right.
   - The minimums in 3.1 are my reading of each encoder. If one were too high, a valid packet with that many elements and nothing after them would be refused. The tightest are `AssemblyNetwork` slots (21), streams (42) and instrument targets (33).
3. **Netty's wrapping** of a non-`DecoderException` (`ByteToMessageDecoder.callDecode`) is from memory, not read in this session. Either way both exception types end the decode.
4. **`build.gradle` change** (`f59857e2c`) is outside my lane, and the column group may need more if `Wire` pulls in a class it lacks. `Wire` names only `DecoderException`, `BlockPos`, `FriendlyByteBuf` and `java.util`, which come from the libraries on the classpath.
5. **Left stale, not in my lane:**
   - `.claude/skills/gui-creation/references/server-actions.md` lines 36 to 39 still tell an agent to bump `EquipmentNetwork.PROTOCOL` and `FluidNetwork.PROTOCOL` and the per-display literals. It should read: bump `NetworkProtocol.VERSION` and its `FORMATS.md` rows.
   - The javadoc of `TutorialNetworkCodecTest` still says "protocol tutorial-1".
   - History lines in code guide chapters (`fluid-runtime.md` "Formats:" bullets, `HISTORY.md`, `machine-assemblies.md` line 202 onwards, `rotating-machinery.md` lines 106 and 116, `client-gui.md` lines 299 and 307, `heat-exchangers.md` line 11, `verification.md` line 80) name old protocol strings as history and are left as they are.

## 7. Code guide

- `fluid-runtime.md`: the `FluidNetwork` row and the wire table read the `fluid` registrar on `NetworkProtocol.VERSION`. New bullets: one protocol, and the `Wire` counts. The coordinator's path is `runtime/`.
- `process-simulator.md`: a new `Members` row; the formats table and the packets section give the one protocol; `PumparoundMember` forwards to `Members`.
- `column-v3.md`: the coordinator's link and the wire row.
- `wired-control-power.md`, `tutorial.md`, `rotating-machinery.md`, `machine-assemblies.md`, `instrument-accessories.md`: the class-map rows name `NetworkProtocol.VERSION`.
- `README.md`: the coordinator's row. `node tools/code-guide/map.mjs --readme` moved `science/flowsheet/**` from 29 to 30 and `runtime/**` from 5 to 6; `--check` says the counts are current.
