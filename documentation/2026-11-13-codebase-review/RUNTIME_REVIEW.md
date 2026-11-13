# S6 Fluid runtime and scheduling: review

Scope read closely: `runtime/ProcessSolveServices`, `BoundedCpuSolveService` (1-420), `WorkerAllocation`, `runtime/fluid/FluidWorldAuthority` (whole), `IslandCoordinator` (whole), `IslandScheduler`, `IslandSets`, `RetainedSolver`, `MinecraftFluidRuntime`, `FluidPresentation` (95-252), `FluidSavedData`, `FluidPropertyReloadGuard`, `FluidCheckpointStore` (20-230), `FluidCheckpointCodec` (90-370), `FluidFacts` (1-270, 760-870), `IslandCertificate` (296-528), `PhysicalRegistry` (297-546), `WorldTopologyLedger` (240-370), `PhysicalFluidTopology` (86-125 and the outline), `network/FluidNetwork` (60-319). Read by signature or excerpt only: `Converters`, `Flares`, `FiredHeaters`, `Chimneys`, `Noise`, `Silhouettes`, `HeatPresentation`, `PipePresentation`, `TankOutlets`, `TankDisplay`, `FluidView`, `IslandClock`, `CertificatePolicy`, `HeldDevicesCommand`, `EquipmentAlarm`, `FluidDeviceSpec` (97-109 only), the rest of `FluidNetwork` (handlers 320-962), `PhysicalFluidTopology.compile`, the decode half of the codec. Out of scope, cited only where a line was read: `ControlLaw`, `InstrumentService`, `FluidThermodynamics`, `ColumnOperation`, `FluidDeviceBlockEntity`.

## 1. Summary

**What it does.** `FluidWorldAuthority` turns block events into queued ledger events, `PhysicalRegistry` compiles the touched components into islands, `IslandCoordinator` dispatches one slice per island to the shared pool (`ProcessSolveServices` over `BoundedCpuSolveService`), commits rounds, certifies steady islands and couples exchanger sides; `FluidPresentation` builds views on 100-tick buckets, and `FluidFacts`/`FluidCheckpointCodec`/`FluidCheckpointStore` save facts and state.

**Five largest problems.**
1. Presentation is quadratic and copies whole islands. Each plain pipe's view pads its whole island onto the world list two or three times (R1), every view scans every node and pipe (R2), and every loaded device gets a view and a kept `MenuData` at every bucket after a commit, while production never reads the block entity's copy (R3).
2. A presentation or instrument read commits state. It materialises a certified island and books its replayed span into column accounts, shaft energy and the exchanger heat in transit (a saved fact). Those bits then depend on when players look (R5).
3. Column and still jobs can stop every island. Islands take only idle workers and never queue, plant jobs queue first, and the plant deadline counts queue time (R6). Also, one slow island holds the commit of its whole round (R7).
4. `FluidWorldAuthority` (2 898 lines) holds 14 responsibilities and is reached from 60 files. It has 86 references into `network` and 40 into `world`, and about 800 lines of column, still, converter and machine code (R8, R9).
5. Save code is coupled to every subsystem. `FluidFacts` has 47 references to column, machine, equipment and world types and telescoping `World` constructors (R12). The certificate signature hashes a hand-kept list of every law's fields (R11). `IslandCoordinator` mixes four concerns and depends on a Minecraft-bound class (R13).

**Ten changes, ranked by gain over cost.**
1. R1: cache the presented island per bucket and the axis map per model pair; skip the pipe read when no sensor exists. Bitwise identical. About 40 lines.
2. R2: build a per-island index once per bucket. Bitwise identical.
3. R5: presentation and instrument reads use a non-committing preview. This deletes the `presentation` flag and the `storedOnly` mode, and bookings become independent of viewers.
4. R4: cache the constant menu parts (components, presets, names, weights).
5. R6: reserve one worker for islands, or split the pools; start the plant deadline at worker start. Owner option.
6. R26: reuse the validation flash at apply. This removes the second flash on the server thread.
7. R11: replace the graph digest with a declared replay revision. Deletes about 60 lines and the per-law edits.
8. R9 and R10: one generic world-space display. Deletes about 300 lines and spreads the 100-tick spike.
9. R8 and R13: split the authority and the coordinator into the module map of section 2. Moves about 3 000 lines, bitwise identical.
10. R12: one facts subsection per subsystem, in the current order. Bytes identical, proved by the compat corpus.

**The schedule (brief item 2).**
- There are three job families, not two: islands, column units and stills (`StillCommand`, `ProcessSolveServices` 470).
- Island admission: `availableWorkers()` is 0 while any job waits (`MinecraftFluidRuntime` 26), and `submitFluidIsland` refuses when `readyJobs>0` or every worker is busy (`ProcessSolveServices` 108).
- Island budget: 2 s of wall time from worker start, polled by the step (389).
- Plant jobs: queued FIFO (capacity 8, `IChemE` 73-75). Their deadline is 45 s from admission, queue wait included (166, `IChemE` 76-78).
- Workers: min(12, CPUs − 2) (`WorkerAllocation` 38). The pool grows at once on demand and shrinks after 200 ticks (52-69).
- Completions: 64 per tick, shared by all families (`ProcessSolveServices` 31, 198-206).

**Server-thread cost (brief item 5).** `tick()` (1489-1528) runs, in order:
- the property check (an identity check, cheap);
- `holdValves`;
- the electric and shaft cadence (machine arithmetic, S7);
- `grow` for columns and converters;
- the coordinator's deadlines, which materialise certificates and run `ports.decide`/`shafts.decide`;
- `applyPending`, which compiles islands. A widened list re-evaluates node states (`IslandSets` 81-82), and a respecified generator runs a TP flash (`FluidDeviceSpec` 106, R26);
- instruments and controls;
- a scan of every shaft unit every 100 ticks to bind motors (1507-1511);
- the bucket views (R1-R3) and nine 100-tick display publishes (R10);
- the FE push of every charged generator every tick (1534-1551).

Two of these break AGENTS.md:
- the flash in the edit path. An edit packet reaches `submit` (`FluidWorldAuthority` 1459, `PhysicalRegistry` 307), and the same flash runs again at apply (427);
- the bookings a presentation read makes (R5).

## 2. Module map

`FluidWorldAuthority` today, by line range, with its proposed home:
- 125-214: wiring of 15 subsystems → stays, as the facade.
- 225-295: lifecycle and model accessors → stays.
- 295-528: build facts, silhouettes, noise and drawn faces → `present/` (noise, silhouettes) and `registry/` (faces).
- 530-1166: placement → `registry/Placement`, except converters (721-875) and stills (876-1013), which go to `plant/`.
- 1167-1377: running columns → `plant/`.
- 1378-1452: fittings and held devices → `registry/`.
- 1459-1582: submit, tick, generator FE and recoveries → stays, except the FE push (1534-1551), which goes to `machine/` (S7).
- 1583-1651: exchanger lines → `plant/`.
- 1652-1736: chunk binding and realignment → `world/ChunkBinding`.
- 1737-2300: the nine world-space displays → `present/Displays` (R9).
- 2295-2365: instrument and control reads, menu subscription → `present/`.
- 2366-2377: capture → `save/`.
- 2378-2707: the view builder → `present/ViewBuilder`.
- 2709-2836: hosts → split by role.
- 2865-2897: `Watchers` → `present/`.

What 60 files reach into the authority for (grep counts):
- placement and editing: `EquipmentStructure` 53, `FluidDeviceBlock` 8, `LineLaying` 5;
- registry reads (`registrations`, `at`): `RegistryRealignment` 15, `PipeRackBlockEntity` 8;
- menus and watches: `FluidNetwork` 22, `MachineNetwork` 7;
- assemblies and columns: `AssemblyService` 12, `ExchangerLines` 9, `ColumnStarts` 8;
- instruments and controls: the `runtime/control` files.

Proposed packages (Minecraft-free marked MF):

| Package | Files (lines today) | Notes |
|---|---|---|
| `runtime/solve` MF | `BoundedCpuSolveService` 801, `WorkerAllocation` 77, command and result records from `ProcessSolveServices` (~250 of 643) | `ProcessSolveServices` keeps only the NeoForge adapter |
| `runtime/fluid/engine` MF | `IslandCoordinator` 1 458 split into `Coordinator` (~600), `ExchangerCoupling` (1000-1267), `Certificates` (1269-1377), `TopologyChange` (800-944); `IslandScheduler` 130, `IslandClock` 114, `IslandCertificate` 528, `IslandSets` 234, `RetainedSolver` 74, `CertificatePolicy` 29 | The Rust fluid step's caller |
| `runtime/fluid/registry` | `WorldTopologyLedger` 631, `PhysicalRegistry` 632, `PhysicalFluidTopology` 955, `FluidDeviceSpec` 117, `HeatUnit` 134, placement from the authority (~640) | |
| `runtime/fluid/save` | `FluidFacts` 1 128 (split into subsections, R12), `FluidCheckpointCodec` 1 043, `FluidCheckpointStore` 514, `FluidSavedData` 171, `FluidPropertyReloadGuard` 44 | |
| `runtime/fluid/present` | `FluidPresentation` 253, `FluidView` 428, `PipePresentation` 203, `HeatPresentation` 199, `TankOutlets` 80, `TankDisplay` 90, `Noise` 204, `Silhouettes` 224, the view builder and displays from the authority (~1 100) | Display records move here from `FluidNetwork` |
| `plant` (S8) | `ColumnOperation`, `ColumnUnit`, `ColumnPower`, `Stills`, `StillUnit`, `Converters` 461, `ConverterUnit`, `FiredHeaters` 225, `Flares` 144, `Chimneys` 96, `Ejectors`, the authority's 721-1013 and 1167-1377 | The ports chain at authority 192 |

Imports that cross the wrong way:
- `FluidWorldAuthority` → `network.*` (86 references): wire records and `send*` calls. They belong behind a `Displays.Sink` interface that `network` implements.
- `ProcessSolveServices` → `network.ProcessSolveCoordinator` (65, 228): runtime calls the router, and the router calls runtime.
- `IslandCoordinator` → `runtime.ProcessSolveServices` (4, 613, 631), a class bound to `MinecraftServer`, for `FluidIslandCommand` and its result.
- `FluidFacts` → `science.column.operation.SplitTable` (763), plus 46 more references to column, machine, equipment and world types.
- `PhysicalFluidTopology.Device` → `science.equipment.HeatSides` in its validation (119).
- The view builder → `PassiveStepSolver.portHead` (2407) and `InletPhase` (2499, 2701): presentation reaches into solver internals.
- `science` imports nothing from `runtime`, `world` or `network` (grep: 0 files).

**Facts and state (brief item 4).** No proposal below changes a saved field.

Facts (`FluidFacts`, positional and append-only):
- topology unit, `World` (204-261): registrations (with `FluidDeviceSpec`, `HeatUnit`, ports, joints), basis, constructed and destroyed totals, recoveries, restatements, column units, shaft units, electric stores, pump health, build facts (paints, structures, racks, names), stills, converters;
- island unit, `Island` (792-805): vessel contents with the saved phase split and the three slots (777-779), filter cakes, and a certified island's drift (790);
- heat in transit, a fact of the sides (authority 150-151, 2369).

State:
- the topology state section: queued events, the columns' engine state, shaft speeds, the held set (`FluidFacts.topologyState`; `held` is state, `PhysicalFluidTopology` 88);
- the island state section: graph, junctions, last result, certificate, status, fences (`islandState`, codec 336-370);
- the core record's index rows: clock and certificate ticks in NBT (`FluidCheckpointStore` 228-229).

Never saved: presentation caches and watchers, `RetainedSolver`, the scheduler and `FairQueue`, the `Coupled` relay records, `lastLines`, `SpeedWindow`.

Format numbers and where each is declared:

| Number | Value | Where |
|---|---|---|
| Envelope | 1 | `FluidCheckpointCodec` 124; digest tag `icheme-fluid-checkpoint-envelope-1`, 871 |
| State format | 50 | `FluidCheckpointCodec.VERSION`, 120 |
| Unit magic, unit envelope | magic, 13 | `FluidCheckpointCodec` 128 |
| Facts schema | 25 (aliases empty) | `FluidFacts.SCHEMA`, 82 (84) |
| Pack magic, pack format | magic, 1 | `FluidCheckpointStore` 46 |
| Certificate graph identity | 10 | `IslandCertificate` 357 |
| Fluid protocol (wire, no compatibility) | `fluid-24` | `FluidNetwork.PROTOCOL`, 70 |
| Accessory facts (outside scope) | 6, state 1 | `AccessoryFacts.SCHEMA`, 28 |
| Control facts (outside scope) | 4 | `ControlFacts.FORMAT`, 18 |

## 3. Findings

| id | file:line and symbol | what it is | proposed change | class | lines removed or moved | risk | proof |
|---|---|---|---|---|---|---|---|
| R1 | `FluidWorldAuthority` 2326 `instrumentPipeReading` → `presented` 2456/2461 → `IslandSets.presented` 223-233, `map` 32-39 | Each call pads the whole island (every node 164-169, every boundary and stream 226-230) onto the world list, and builds one HashMap per node and per stream. It runs for every plain pipe at every bucket twice: in `lineReading` (2390) and in `sensorReadings` (`InstrumentService` 183, reached from `FluidNetwork.snapshot` 285 and `PresentationHost.view` 2722). It runs a third time for a valve (`FluidNetwork` 295). Allocation per bucket is about 2·D·(7N+6T)·C doubles; at D=N=T=200 and C=100 that is about 0.8 GB (derived from the code, not measured; B1 to confirm) | Read through the bucket's `islands` cache (`view(id,islands)`); cache the `int[]` axis map per (from, world) model pair; read the pipe only when the host has a sensor | PERF, identical | about −10, +30 | none | FluidPresentationGameTests; counter `islandSnapshots` per bucket, before and after |
| R2 | `FluidWorldAuthority.view` 2464-2644 | Per device: linear scans at 2468, 2476, 2477, 2481, 2513, 2516, 2521, 2526, 2532, 2559, 2593, 2631, and `ControlConstraints.dependent`/`standing` recomputed per actuator (2482-2483). That is O(D·(N+P)) per island bucket, on the server thread | Build one index per presented island per bucket: node and pipe index by id, transfers by pipe, HEAT boundaries by node, both constraint bitsets | PERF, identical | about 40 changed, +50 | none | view tests and GameTests unchanged |
| R3 | `published` 2848, `PresentationHost.present` 2731-2760, `publishedMenuData` 86 and 2722; `FluidDeviceBlockEntity` 308 | After a commit, every loaded member is marked; each gets a full view and a kept `MenuData`. The block entity's `lastView` is read only by GameTests (`FluidPresentationGameTests` 241-258). The real consumers are open menus, watched displays, instruments, tank roofs and sight glasses. Retained memory is about 5 KB per loaded pipe at C=100 (3×C contents plus 2×3×C history) | Owner option. A: present and keep data only for devices that have a consumer (the observed set, an instrument, an open tank, a menu); a first open of an unpresented device waits at most one bucket, which AGENTS.md allows. B: keep as is | PERF | about −20 | GUI opens of idle pipes show "waiting" for ≤5 s | FluidPresentationGameTests (adjust the 241-258 load assertion) |
| R4 | `materialNames` 274; `FluidNetwork.snapshot` 284-285; `MenuData` 148-172 | Every snapshot rebuilds the name map and the boxed weights list and revalidates the axis, all constant per server (catalog captured at 126) | Build the static part once at construction and share it | PERF, identical | −5, +10 | none | existing menu tests |
| R5 | `IslandCoordinator.presentation` 419 → `materialise(island,true)` 1280-1303 → `booked` 1293 (1159-1164); `IslandCertificate.replay` 510-521 | A read moves the committed tick and books the span, scaled by g. Split spans sum differently from one span, so column accounts, shaft energy and the heat in transit (a fact) depend in their last bits on GUI opens, watches and instrument reads (`ControlLaw` 153-180 → `instrumentPipeReading`). The graph is absolute (`graphAt`); the bookings are not | Presentation and instrument reads take a preview (`graphAt(target)` into a temporary snapshot: no clock move, no booking). Only deadlines, fences, saves and coupling materialise. This deletes the flag (1279-1304) and the authority's `storedOnly` mode (2453-2456) | DEFECT (determinism, about 1 ulp per extra split) | about −25 | a view of a certified island must not show the stale committed tick (the preview covers it) | new unit test: two coordinators, the same events, one read every tick; `transits()` and the port bookings bit-equal |
| R6 | `MinecraftFluidRuntime` 26; `ProcessSolveServices` 88-110, 164-166; `IChemE` 73-78, 100; `WorkerAllocation` 38 | Plant jobs queue first; islands get only idle workers, and none at all while one job waits. On a 4-core host (2 workers), two running columns or stills stop every island for the length of their solves, and online time accrues as debt. The 45 s plant deadline counts queue time; the island budget does not | Owner option. A: keep one worker for islands while any island is ready, and start the plant deadline at worker start. B: separate pools and budgets. C: keep | PERF | +15 | plant latency grows under island load | a scheduling unit test with a stub kernel; counters `solvesDispatched` per tick |
| R7 | `IslandCoordinator.pump` 589-593, `closeRound` 651-698 | A round closes only when every entry is terminal, so a fast island's commit waits for the slowest island in its round (up to 2 s) and cannot re-dispatch. The code read gives no reason for the all-or-nothing round | Close each entry at its own completion | PERF (coupled sides read partners at dispatch, 1133-1151, so their results can move) | about −10 | a coupled island's read may see a newer partner | campaign counters; heat-coupling gates |
| R8 | `FluidWorldAuthority` (map in section 2) | 14 responsibilities, 60 files depending on it, about 800 lines of plant and machine code | Split into a facade plus `registry/Placement`, `present/ViewBuilder`, `present/Displays`, `world/ChunkBinding`, and `plant/` (S8) | STRUCTURE | about 2 400 moved | merge conflicts while it moves | compile plus the unchanged gates |
| R9 | 1737-2273: tanks, rotations (1782-1858), drivers (1861-1904), movers, flares, stills, towers, heaters, chimneys, sight glasses | Ten copies of watch, validate, replay and publish, each with its own `published*` map; two ad-hoc watcher maps (1782, 1861) beside `Watchers` (2865) | One `Display<S>` with four parts (target check, state read, cache, sink) and one registry | STRUCTURE, identical | about −300 | none | the display GameTests |
| R10 | `tick` 1507, 1515-1520, 1524-1526 | All 100-tick publishes run on the same tick (`onlineTick%100==0`), while island buckets are spread by id (`FluidPresentation` 108) | Publish each display at the tick of its device id mod 100, as buckets are | PERF, wire timing only | +5 | none | display GameTests |
| R11 | `IslandCertificate.graphIdentity` 356-414, `restore` 469, `certify` 1355 | A SHA-256 of a hand-kept field list of every law and node, versioned by hand ("identity-10", and 399-405 "named only below 1 so older signatures stand"). `restore` hashes the saved interval's own graph, so it changes only when the codec or the digest code changes. This is a content hash against the owner rule | Drop the graph digest, keep the property revision and policy; add a declared `REPLAY_REVISION` constant (or rely on the state `VERSION`) | STRUCTURE | about −60, and the per-law edit every equipment batch makes | a decode drift would go unseen; the codec's round-trip validation covers it | IslandCertificate tests, checkpoint round-trip, compat corpus |
| R12 | `FluidFacts` 204-261 (`World` with five telescoping constructors, 207-236), 262 ff.; `WorldTopologyLedger.Snapshot` 258-259 and 322 (16 components) | Every subsystem's facts are written in one file and one record. A new subsystem edits the `World` record, both codecs and the ledger snapshot | One `FactsSection` per subsystem, registered in today's field order, so the bytes are identical; the ledger snapshot holds a map of sections | STRUCTURE | about 600 moved | the encoding order must stay exact | compat corpus images under `src/test/resources/compat/` read and re-encode bit-equal |
| R13 | `IslandCoordinator` 1-1458; imports `ProcessSolveServices` (4, 613) | Scheduling, topology change, exchanger coupling and certificates in one class; it depends on a Minecraft-bound class for `FluidIslandCommand` (353-357: PassiveNetwork objects, model, read and port maps, which is what a Rust step must flatten, S3) | Split as in section 2; move the command and result records to `runtime/solve` | STRUCTURE | about 900 moved | none | coordinator tests with `VERIFY` (120) |
| R14 | `IslandScheduler.FairQueue` 70-129 | 4 TreeMaps, 2 HashSets and 2 HashMaps of boxed ids, plus renumbering, for a round robin over ready islands; `Island.ready` (166) already holds readiness | An `ArrayDeque` for newcomers and one rotating cycle of ready ids, keeping the documented order (newcomers first, then the cycle) | STRUCTURE (order kept) | about −40 | a changed order changes round composition | `IslandScheduler` tests, `VERIFY` runs |
| R15 | `FluidThermodynamics.on` 215-220 (`whole.views`) | Every compound list an island ever had keeps a full model view for the server's life; growth and splits add more | A weak-value cache, or release when no island uses the view | PERF (memory), identical | +10 | none | island-set tests |
| R16 | Index maps: `PhysicalRegistry` 47-69, `WorldTopologyLedger` 279-304, `IslandCoordinator` 223-238, `FluidPresentation` 90-96, authority 86-113 | Eight or more boxed-key map entries per device, with `Position` keys (a String and five ints): about 0.5 KB per device before the records, about 50 MB at 100 k devices (estimate). fastutil is on Minecraft's classpath and unused (0 imports) | A dense device index (one long→int open-address map), `int[]` owner, chunk and member arrays; interned positions | PERF (memory), identical | about 300 changed | iteration order of the HashMaps must not reach results (check `LinkedHashMap` users) | registry and ledger tests; heap counter in B1 |
| R17 | `FluidCheckpointCodec` 20-117, `FluidFacts` 40-81, `FluidNetwork` 1-68; guide `fluid-runtime.md` 112, 160, 179 | Javadoc change logs of formats grow at every merge. The guide is stale: it says protocol `fluid-19` (the code has `fluid-24`), graph identity 9 (code 10), state format 40 and facts schema 15 (code 50 and 25) | Move the histories into one format table in `documentation/reference`; the code keeps the current meaning only | STRUCTURE | about −220 comment lines | none | none needed (documents) |
| R18 | `ColumnOperation.grow` 887-894 ≡ `Converters.grow` 263-270; `Ports.both` (authority 192; coordinator 267-282) | A duplicated method; four port providers in a binary tree, whose `plans` merge one HashMap per level per dispatch | One growth helper; a `List<Ports>` in the coordinator | STRUCTURE, identical | about −30 | none | column and converter tests |
| R19 | `ProcessSolveServices` 105, 28, 344-513 | A linear owner scan (105) repeats the service's own `OWNER_BUSY` check (`BoundedCpuSolveService` 127). The class doc names V3 calculator jobs, which were removed. A new job family edits five sealed lists and the drain switch (290-304) | Delete 105; one `Family` record per job type (submit, decode, route) | STRUCTURE, identical | about −40 | none | `BoundedCpuSolveServiceTest` |
| R20 | `FluidSavedData` 143-147 | A world from before the envelope is reset, which discards its facts (owner D4), against "never discards a fact" | Owner option: delete the reset path and refuse such a world | DEFECT (rule) | −8 | an old world refuses to open | save tests |
| R21 | `PhysicalFluidTopology.Device` 91-106, 107 ff. | A 16-field record with 12 telescoping constructors and per-kind invariants (burner, flare, diverter) in its compact constructor; every equipment batch edits it | A per-kind rule table and a builder | STRUCTURE, identical | about −40 | none | topology tests |
| R22 | `Island.fence` 201; `Snapshot` 110-111 | The fence map is streamed at every pump test (602) and every snapshot | Keep the minimum fence; copy fences only when they change | PERF, identical | +5 | none | `VERIFY` runs |
| R23 | `settledTransit` 1242-1249, called from `moved` 1198 | Scans every coupled side per booking: O(sides²) per commit | Index sides by pair | PERF, identical | +5 | none | heat-coupling tests |
| R24 | `FluidWorldAuthority` 1507-1511 | Every 100 ticks, scans all shaft units and reads block entities to bind motors | Bind on chunk load and on unit creation | PERF, identical | about −5 | a missed bind | control GameTests |
| R25 | `IslandCoordinator.hold` 734-741, budget 2 s (`IChemE` 100) | A wall-deadline hold halves the slice, so results depend on machine speed whenever an island hits its budget (owner D37 design) | None; record it under REVIEW_PLAN section 2, rule 1 | (observation) | 0 | none | none |
| R26 | `PhysicalRegistry.submit` 307 and `apply` 427 → `FluidDeviceSpec.initialize` 104-106 (`flashTP`) | A respecified generator gets a TP flash on the server thread inside the edit path (packet → `FluidWorldAuthority.edit` → `submit`), only to validate its solids, and the same flash again at apply | A: keep the validation and reuse its reservoir at apply (one flash, identical). B: move the initialization into the island's first slice and report a refusal on its status line | DEFECT (AGENTS.md schedule rule); A is PERF, identical | about −5 | B delays the refusal to a bucket | generator edit tests |

## 4. Open questions for the main thread

1. R3: may an idle device's first GUI open wait up to one bucket (5 s), so that only consumed devices are presented?
2. R5: confirm that presentation never materialises. Saves and decision points still do.
3. R6: reserve one worker for islands (A), or separate pools (B)? Should the plant deadline start at worker start?
4. R7: is there a reason, not stated in the code, for all-or-nothing rounds (coupled sides, determinism)?
5. R11: declared `REPLAY_REVISION`, or rely on the state `VERSION` alone?
6. R20: are there still worlds from before the envelope that the D4 reset must serve?
7. R26: option A (reuse the flash) or B (no flash on the server thread)?
8. Supercritical: the facts carry two liquid slots plus gas (`FluidFacts` 777-779, legacy `liquid`/`vapor` fields). One dense phase needs a slot mapping, and an appended field if it is a new slot. The tower plume (`TowerTransfer.exhaust` via authority 2103) assumes a saturation pressure. The pump limit uses 1.4 or the vapour cp/cv (2500). The phase-hold log says "one hydrocarbon liquid, one vapour, water" (2690).
9. REVIEW_PLAN section 1, item 6 says "one worker pool and queue for V3 solves and island steps". In fact islands never enter the queue (`ProcessSolveServices` 106-108), the queue holds column-unit and still jobs, and stills are a third family. The guide (`fluid-runtime.md` 182) says two families. Correct the plan's wording?
10. REVIEW_PLAN section 2, rule 1: Java-path determinism also fails through R5 (read cadence) and R25 (wall-budget holds), not only through math. Add both to the rule's scope?
