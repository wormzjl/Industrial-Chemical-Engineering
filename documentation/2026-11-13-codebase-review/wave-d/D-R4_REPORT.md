# D-R4 The fluid world authority in files (R8): report

Batch D-R4 of `WAVE_D_BRIEFS.md` (subagent, Opus high). Branch `worktree-agent-ad41afb0c4210716f`, fast-forwarded to `claude/codebase-review-refactor-8a85ef` at `380015b56`. Four code commits, then this report with the code guide:

| Part | Commit | What moved |
|---|---|---|
| 1 | `c68495110` | The displays: `FluidWorldAuthority` → `Displays` |
| 2 | `43d89a636` | The placement: `FluidWorldAuthority` → `Placement` |
| 3 | `6384a834d` | The view builder: `FluidWorldAuthority` → `ViewBuilder` |
| 4 | `87275c533` | The chunk binding: `FluidWorldAuthority` → `ChunkBinding` |

**Nothing was compiled or run** (the brief: no Gradle, no JVM). Structure only:
- Every moved body is the same text, but for qualifiers (`authority.`, `FluidWorldAuthority.`, `Displays::`, `authority::`) and access modifiers. Section 4 lists each kind.
- The authority calls a part where the code stood, so `tick`, the presentation host's `present` and the commit hook run the same steps in the same order. Where the authority changed a part's state, it calls one part method in the same place, and that method makes the same calls in the same order.
- No collection or allocation was added on a per-tick path. A forwarder is one call.
- Every public entry point keeps its signature on the authority. It runs `owned()` and, where the body did, the closed check first, then forwards. No dependent outside the five files changed: no test, no GameTest, no `network/**`, no tool.
- No packet, payload, wire name or saved field changed. `FORMATS.md` names no authority constant, so `FormatsTableTest`'s table is unchanged.

Summary:
- `FluidWorldAuthority.java`: 2,827 → 1,959 lines. New: `Displays` 416, `Placement` 256, `ViewBuilder` 336, `ChunkBinding` 123. `Display.java` is unchanged (168).
- **Part 1 done.** `Displays` takes the eight `Display` objects and their two lists, the rotations' and drivers' maps, the watch bodies, the target checks, the state reads, the bucket and cadence publications and `refreshObserved`.
- **Part 2 done.** `Placement` takes the generic registrations, edits, fittings on vessels and pipes, removal, holds, drop and filter recovery.
- **Part 3 done.** `ViewBuilder` takes the private `view(id, islands)`, its index cache, its status clauses and the groups only a view reads.
- **Part 4 done.** `ChunkBinding` takes a chunk load's body, the re-alignment queues, the structure chunk index and the hold without a level.
- **What stayed.** The plant and machine code, the hosts, the lifecycle, the instruments and controls, the silhouettes and noise, `submit`, `capture`, the static helpers the tests name, and the nested types. Section 1.5 gives the reason for each.
- **Checks run** (node scripts in `tmp/d-r4/`, gitignored):
  - `verify.mjs`, statement by statement over `380015b56..HEAD`: every removed statement reappears among the added ones after the qualifiers and modifiers are normalised. Four are left unmatched, all named in section 4: `rack.bind(this)` and `pipe.bind(this,…)` (now `authority`), and two statements split into a delegating call. The added statements left unmatched are the class headers, the constructors, the forwarders and the part methods of section 1.
  - Braces, parentheses and brackets balance in all five files.
  - `access.mjs` finds no `authority.X` naming a private or missing member, no `part.X` naming a private or missing member, and no bare identifier in a part naming an authority member. The four hits it prints are the local `chunks` in `indexStructure`.
  - A last script finds no bare reference in the authority to a member that moved. Its hits are locals and parameters.
  - Each new file was read in full and its imports checked one by one. Every moved member was grepped over `src/main`, `src/test`, `src/fluidGameTest` and `tools/`.

## 1. The map

The scratch map is in `tmp/d-r4/`: `outline*.tsv` (every member with its line range, kind and modifiers, before each part) and the analysis printed by `move.mjs`/`analyze.mjs` (the references in and out of each moved range). The tables below are its result.

### 1.1 Part 1: `Displays` (416 lines)

| Took | Notes |
|---|---|
| `tankDisplay`, `sightGlassDisplay`, `moverDisplay`, `flareDisplay`, `stillDisplay`, `towerDisplay`, `heaterDisplay`, `chimneyDisplay`, `displays`, `cadenceDisplays` | Private finals. Their factories ran in the authority's field initialisers. They now run in the `Displays` constructor, after `this.authority=authority`, in the old declaration order (section 4, point 1) |
| `rotationWatchers`, `driverWatchers` | Package-private: `tick` reads `isEmpty()` before the pass |
| `publishedRotations`, `publishedDrivers` | Private |
| Bodies of `watchTanks`, `watchRotations`, `watchDrivers`, `watchMovers`, `watchFlares`, `watchStills`, `watchTowers`, `watchHeaters`, `watchChimneys`, `watchSightGlasses`, `publishedMover` | The authority keeps each as a forwarder: `owned();if(closed)return;displays.watchX(…)` (`publishedMover`: `owned()` only) |
| `publishRotations`, `rotationSpeeds`, `rotationSpeed`, `driverState`, `publishDrivers`, `flareState`, `stillState`, `chimneyState`, `watchable`, `flued`, `towerPlume`, `heaterFlue`, `sideHeat`, `moverRange`, `inRange`, `refreshObserved`, `observeWatched`, `online`, `publishMover`, `sightGlassWatchable`, `publishSightGlass`, `publishTank` | Moved whole |
| New delegating methods | `forget(UUID)` (from `forgetWatcher`), `unloaded(long)` (from `deviceUnloaded`), `removed(long)` and `recompiled(Set)` (from `RegistryHost.committed`), `forgetSightGlass(long)` (from `PresentationHost.present`), `publishDue(long)` (from `tick`), `clearPublished()` and `clearWatches()` (from `close`). Each makes the calls the authority made, in the same order |

### 1.2 Part 2: `Placement` (256 lines)

| Took | Notes |
|---|---|
| Bodies of `drawnFaces`, `placedFaces`, `lay`, `placeLane`, `placeMover`, `placeVessel(node, ports, volume, levelHeight, open, geometry, tower)`, `edit`, `blanketValve`, `blanketPressure`, `reliefValve`, `pipeRelief`, `reliefPressure`, `divert`, `diverterOutlet`, `remove`, `hold`, `heldCount`, `held` | Forwarders on the authority run `owned()` first (`divert`: also its closed check). `placeMover` had no check, and its forwarder has none: its body ends in the 9-argument `place`, which still runs `authority.owned()` |
| `latestDevice`, the three private `place` overloads (with ports), `placedPipeDiameter`, `fittedRelief` | Moved whole. The `place` overloads lost `private` because the authority's public overloads call them |
| Calls of public entry points from moved bodies | `blanketValve`, `blanketPressure`, `reliefValve`, `pipeRelief`, `reliefPressure` and `divert` call `authority.edit`. `heldCount` calls `authority.held()`. `lay` calls `authority.paint`. Each goes through the authority's check as before |

The public `place(…)` overloads (six one-liners) and the three short `placeVessel` overloads stay on the authority. Each `place` overload's delegation now reads `placement.place(…)`. The `placeVessel` overloads still call the authority's public 7-argument forwarder.

### 1.3 Part 3: `ViewBuilder` (336 lines)

| Took | Notes |
|---|---|
| `view(long, Map)` | Package-private. The authority's public `view(id)` runs `owned()` first and calls `views.view(id,new HashMap<>())`. `PresentationHost.view` and `heatSource`'s `Source.view` call `views.view(device,islands)` where they called `FluidWorldAuthority.this.view` |
| `index`, `indexedFor`, `viewIndexes` | The index cache per bucket. Only `index` writes the two fields |
| `heldClause`, `heldBeside`, `heldBesideVersion`, `islandless`, `inletReason`, `nodeLabel`, `lineReading` | The status clauses and the line reading. The two orphaned javadoc lines above `heldBeside` moved with it |
| `drive`, `pump`, `finite`, `stillView`, `positive`, `finiteOr`, `stillFuel` | The groups that only `view` reads. They stood among the displays, but they build `FluidView` records, not display states |

### 1.4 Part 4: `ChunkBinding` (123 lines)

| Took | Notes |
|---|---|
| Body of `loadedChunk(ResourceKey, long)` | The forwarder runs `owned();if(closed)return;` first. Block entities still bind to the authority (`rack.bind(authority)`, `pipe.bind(authority,…)`). A missing registration is placed through the public `authority.place(…)`, as before through `this.place` |
| Bodies of `nextRealignment`, `nextShaftRealignment` | The forwarders run `owned()` first. The bodies read `authority.closed` inside their expression, where they read `closed` |
| `holdWithoutLevel` | Package-private. The static `start` calls `world.chunks.holdWithoutLevel()` |
| `indexStructure` | Called from the authority's constructor and from `structure(origin, structure)` |
| `unboundStructures`, `realignment`, `realigning`, `shaftRealignment`, `shaftRealigning`, `structureChunks` | Written only by `ChunkBinding`. `close` clears all six with one call, `chunks.clear()`, in the place and order of the six clears. `structureChunks` is package-private: `structuresOf` (silhouettes) reads it |

### 1.5 What stayed in the authority, and why

Fields that more than one part writes, or that the authority writes and a part reads. They are now package-private (no modifier):

| Field | Written by | Read by |
|---|---|---|
| `lastLines` | `ViewBuilder.lineReading` (put), the authority's `deviceUnloaded` (remove) | ViewBuilder |
| `unboundBindings` | `PresentationHost.present` (add, remove), `RegistryHost.committed` (removeAll), the authority | ViewBuilder (`contains`) |
| `closed` | `close` | ChunkBinding (the re-alignment polls) |
| `server`, `catalog`, `model`, `options`, `componentNames`, `topology`, `runtime`, `registry`, `shafts`, `electric`, `burners`, `flares`, `chimneys`, `stills`, `converters`, `health`, `presentation`, `instruments` | the constructor (blank finals) | the parts |

Methods the parts call, now package-private: `owned`, `dimension` (static), `submit` (both overloads), `storedIsland`, `presentation(long)`, `radiantCoil` (static), `heatSource`, `deviceLabel`, `linesStatus`, `converterView`. Public methods the parts call stay public: `registrations`, `controls`, `place`, `paint`, `edit`, `held`, `instrumentPipeReading`.

Not moved, with the reason:
- **The plant and machine code** stays, as the brief says: the running columns, the stills, the converters (with `converterView` and `converterFill`), the heat units (`placeUnit`, `placeBurners`, `burnerRecord`, `removeUnit`, the `Unit*` records), the flare, chimney, prime mover and machine registrations (`placeFlare`, `placeChimney`, `placePrimeMover`, `coilOf`, `placeMachine`), and the exchanger lines (`Lines`, `lines`, `holdValves`, `operate`, `linesStatus`).
- **`submit`, `changes`, `refusedEvents`, `refuseEvent`.** Every part and the plant code call `submit`. The registry host and the presentation host both use `refusedEvents`.
- **`committedDevice`, `resizeLine`, the `installPipe*` fittings, `togglePipeCutoff`, `flowmeter*`, `removePipeAccessory`.** Each is already one call into `PhysicalRegistry` or the ledger, so moving it would only add a hop.
- **`deviceLoaded`, `deviceUnloaded`, `forgetWatcher`.** They touch the presentation, the instruments, the menu data, `lastLines`, the silhouettes and the noise. Each calls one `Displays` method where it touched displays.
- **The hosts** (`PresentationHost`, `ShaftHost`, `RegistryHost`) are inner classes of the authority. They capture `FluidWorldAuthority.this` and are the authority's role towards `FluidPresentation`, `ShaftTrains` and `PhysicalRegistry`.
- **The silhouettes and the noise** (`structuresOf`, `watchSilhouettes`, `publishSilhouettes`, `watchNoise`, `publishNoise`, `islandNoise`, `pipeCell`, `GEAR_LOOP_RPM`). D-R3's table of the ten displays does not list them, and the brief's part 1 is that table.
- **`capture`, `logLoad`, the lifecycle, the property reload, `tick`, `pushGenerators`, `deliverRecoveries`, `scheduleRecoveryRetry`, `applyPending`, `flush`, `replayed`, `published`, `phaseHeld`.** These are the façade.
- **`storedIsland`, `presentation(long)`, `onWorldList`.** Displays, ViewBuilder, the noise and the instrument reads use them.
- **`deviceLabel`.** The coordinator's node names (`nodeNames(this::deviceLabel)`) and `ViewBuilder.inletReason` use it.
- **Static helpers and constants that tests or other files name on the authority** keep their place: `displayDue`, `cell`, `plumeAir`, `withHealth`, `controlStatus`, `solidClosure` (with `SOLID_CLOSURE`, `closureField`, `speed`), `heatingValues`, `formulas`, `sharePlace`, `HELD_DEVICE`, `APPLY_BATCH`, `LINE_LIMIT`. So does the static façade: `start`, `find`, `tick`, `loadedChunk(server, …)`, `listen`, `forget`, `SERVERS`, `EARLY_CHUNKS`, `LoadedChunk`.
- **Nested types other classes name** keep their names and place: `Watchers` (`Display`, `OpenTankRuntimeTest`), `ViewIndex` (`ViewIndexTest`; `ViewBuilder` imports it), `Laid` (`Placement` imports it), `Lines`, `UnitSide`, `UnitVessel`, `UnitBurners`, `DeviceChange`, `EditListener`, `InstrumentReading`, `PortSpot`, `ColumnStart`, `ConverterDevices`, `StillDevices`, `ColumnInstrument`.
- **The import lines of the authority** are untouched, though `EquipmentShellBlock` is now unused there. The brief warns that another package may change one import line. `ProcessSolveCoordinator` is not imported by the authority at all.

The review's estimate was about 2,400 lines moved. This slice moved about 1,000. The rest is the plant and machine code (about 800 lines, a later batch), the hosts and the parts the brief names in no part.

## 2. Lines per file

| File | Before | After |
|---|---|---|
| `runtime/fluid/FluidWorldAuthority.java` | 2,827 | 1,959 (part 1: 2,503; part 2: 2,349; part 3: 2,037; part 4: 1,959) |
| `runtime/fluid/Displays.java` | (new) | 416 |
| `runtime/fluid/Placement.java` | (new) | 256 |
| `runtime/fluid/ViewBuilder.java` | (new) | 336 |
| `runtime/fluid/ChunkBinding.java` | (new) | 123 |
| `runtime/fluid/Display.java` | 168 | 168 (unchanged) |

The code guide's README count goes from `runtime/**` (5 + `fluid/` 54) to (5 + `fluid/` 58) (`map.mjs --readme`; `--check` passes). `fluid-runtime.md`: the authority's row names its four parts, a row for each part, the `Display` row's lists now `Displays`', and ten rows of the owner table name the part that holds each body.

## 3. Tests for the main thread

Files changed since the last pass: `runtime/fluid/FluidWorldAuthority.java`; new `Displays`, `Placement`, `ViewBuilder`, `ChunkBinding`. No test, GameTest, `network/**` or tool source changed.

Test classes that construct an authority: **none in `src/test`**. The constructor is private, and no unit test calls `FluidWorldAuthority.start` or `find`. Only the fluid GameTests build one, through a real server. The gate groups load none of the changed files (`equipmentGates` loads only `runtime/fluid/FluidView*`, `columnGates` no `runtime` file). No `holdGates` class names the authority in code (`ExtremeTopologyIslandTest` names it in comments only). So the unit runs are in `test`. The precise set is what `node tools/merge-lane/affected.mjs select` gives after `testClasses`.

| Task | Classes | What it proves |
|---|---|---|
| `compileJava compileTestJava compileFluidGameTestJava` | all | The four files, the widened access, the forwarders, the constructor-built displays and the qualified calls compile (section 5) |
| `test --tests` | `LayeringTest` | The new files name no `client` type (`runtime` may not) |
| `test --tests` | `DisplayCadenceTest`, `CoolingTowerRuntimeTest`, `OpenTankRuntimeTest`, `ViewIndexTest` | `displayDue`, `plumeAir`, `Watchers` and `ViewIndex` stay on the authority under the names the tests use (the brief names these four) |
| `test --tests` | `MoverHealthStatusTest`, `SolidRuntimeTest`, `ExchangerCouplingTest`, `HeatSideTopologyTest`, `HeatUnitPowerTest`, `FiredHeaterFuelTest`, `FlareRuntimeTest`, `ReliefValveWorldTest`, `SteamHeaterCoilTest`, `StillRuntimeTest`, `ColumnSuspensionTest`, `HeatPresentationTest`, `client.gui.common.HeatDiagramTest`, `runtime.instrument.SensorReadingTest` | The test classes that load the authority in code (my grep). Their statics (`withHealth`, `solidClosure`, `controlStatus`, `heatingValues`, `formulas`, `sharePlace`) and nested types (`PortSpot`, `Lines`, `InstrumentReading`) did not move. `StillRuntimeTest` also carries its two determinism gates |
| `test --tests` | `PresentationBucketTest`, `ColumnRunTest`, `FluidFactsCompatTest`, `PhysicalRegistryTest` | The brief names them. My grep finds that none loads a changed class: `PresentationBucketTest` names the authority only in a comment, `PhysicalRegistryTest` only the compile-time constant `APPLY_BATCH` (inlined), the other two not at all. By AGENTS.md, "No repeated tests", they need a run only if `affected.mjs` selects them |
| final lane | the fluid GameTests | See the next table |

GameTests that exercise each part (for the final lane; every GameTest that starts a server constructs the authority, so it runs `holdWithoutLevel`, the chunk loads, `tick` with the cadence passes, and `close`):

| Part | GameTests |
|---|---|
| Displays | `PumpHealthGameTests.anInlinePumpPublishesItsCrackle` (`publishedMover`, `publishMover` in `present`). The open-tank path through `present` → `publishTank`: `CoolingTowerGameTests.parallelCellsRegisterAsOneEmptyOpenBasin`, `ConverterGameTests`, `FluidCheckpointGameTests.aSavedRestingTankReloadsCertifiedWithItsStatusLineAndNoSolve`. The sight-glass path: `RegistryFirstGameTests.aBrokenLineIsSetBackFromTheRegistry`. The cadence passes: `ReliefFlareGameTests.aDrumRelievesNitrogenToASmallFlareWithoutAHold` and every ticking GameTest. **No GameTest sends a watch request**: grep finds no `watch*(` call in `src/fluidGameTest`, as D-R3 found. The watch, replay and fan-out send paths rest on this reading |
| Placement | `.place(…)`: `AssemblyGameTests`, `ChunkLoadGameTests`, `PipeLayingGameTests`, `PumpHealthGameTests`, `RegistryFirstGameTests` and others. `.lay(…)`: `PipeLayingGameTests`, `ConduitLayingGameTests`, `ColumnOperationGameTests`, `CreativePowerGameTests`, `LevelLoopGameTests`, `RegistryFirstGameTests`. `.edit(…)`: `ControlLoopGameTests`, `FluidPresentationGameTests`, `FluidPumpStartupGameTests`, `PumpHealthGameTests`, `ElectricGameTests`. `blanketValve`, `blanketPressure`: `ConverterGameTests`, `LevelLoopGameTests`. `reliefValve`, `pipeRelief`: `ConverterGameTests`, `ReliefFlareGameTests`. `held`, `heldCount`: `RegistryFirstGameTests`, `LevelLoopGameTests`. `recoverFilter`: `SolidPhaseGameTests`. `drawnFaces`: `DrawnJointGameTests` (through the block entities). `placeVessel`, `placeMover`, `placeLane`: the structure and rack GameTests (through `EquipmentStructure`, `PipeRackBlockEntity`). `divert`: no GameTest calls it directly |
| ViewBuilder | Every `.view(…)` caller: `ColumnOperationGameTests`, `ConverterGameTests`, `FluidPhasePortGameTests`, `PumpHealthGameTests`, `SolidPhaseGameTests`, `ReliefFlareGameTests`, `ShaftTrainGameTests`; `RegistryFirstGameTests` (the `HELD_DEVICE` clause, `heldClause`). Every presentation bucket: `FluidPresentationGameTests` |
| ChunkBinding | `ChunkLoadGameTests`, `RegistryFirstGameTests` (`loadedChunk`; `nextRealignment` and `nextShaftRealignment` through `RegistryRealignment`); the hold without a level at every server start |

The archived probe `tools/custom-equipment-rendering-poc/frame-time-probe/FrameTimeProbe.java` (lines 1150-1168) is compiled by nothing and is left as it is. It already failed at run time after D-R3 (`publishTowers`, `towerWatchers`). It would now need `displays` → `towerDisplay` → `watchers`.

## 4. What a moved body may differ in

- **Qualifiers.**
  - `authority.x` stands for an authority field or method.
  - `FluidWorldAuthority.x` stands for its statics `cell`, `dimension`, `radiantCoil`, `plumeAir`, `withHealth`, `controlStatus`, `solidClosure`, `HELD_DEVICE`.
  - `Displays::inRange`, `::moverRange`, `::flued` stand where `FluidWorldAuthority::` stood.
  - `authority::storedIsland` and `authority::presentation` stand where `this::` stood.
  - `displays.`, `placement.`, `views.`, `chunks.` stand in the authority where a moved member was called.
- **Captures.** `rack.bind(this)` and `pipe.bind(this,…)` in the chunk load are now `bind(authority…)`: the same object. Placement's `this::latestDevice` is now `Placement.this::latestDevice`, a method with the same body that reads `authority.topology`. Every `this::` in `Displays` names a `Displays` method with the authority's body, qualified. The hosts' `FluidWorldAuthority.this` captures are unchanged.
- **Modifiers.** `public` is removed from the moved bodies of public methods: the public signature stays on the authority. `private` is removed where the authority or a part reaches across files. It stays on members used only inside their part.
- **The display factories** moved from field initialisers into the `Displays` constructor body. The reason is section 5, point 1.
- **Leading checks.** A forwarded body's leading `owned();` (and `if(closed)return…;`) now stands in the authority's forwarder, before the call. The rest of the body is unchanged.
- **Two statements became a delegating call.** `else sightGlassDisplay.forget(id)` is now `else displays.forgetSightGlass(id)`. The loop `for(long device:removed){tank…;sightGlass…;mover…forget(device);}` is now `for(long device:removed)displays.removed(device);`.
- **Javadoc links.** In `Displays`, `{@link #storedIsland}` now names `FluidWorldAuthority#storedIsland`.

## 5. Points not verified (compile risks), with file and line

Every point below was read and checked against the language rules by hand. None was compiled.

1. **`Displays.java` 48-81: the eight displays are built in the constructor.**
   - Why: their lambdas read the authority (`id->authority.registrations().get(id)`, `authority::storedIsland`). In a field initialiser, javac refuses a read of the blank final `authority` before the constructor assigns it ("might not have been initialized").
   - How it reads now: in the constructor, `authority` is the parameter, which shadows the field. It is effectively final, so the lambdas capture the parameter, the same object as the field.
   - Inference: the generic `S` of each `Display.bucket` and `cadence` is still fixed by the assignment target (`tankDisplay=Display.bucket(…)` is an assignment context), as D-R3's field initialisers were.
   - Order: the eight run in the old order. Nothing in them calls the authority while they are built.
2. **`FluidWorldAuthority.java` 79, 85, 87, 89: `new ChunkBinding(this)`, `new Displays(this)`, `new Placement(this)`, `new ViewBuilder(this)` are field initialisers.** They let `this` escape before the constructor body. Each part constructor only stores it (`Displays` also builds the `Display` objects, which store their functions and call nothing). The constructor body (123) calls `chunks.indexStructure`, a field already assigned.
3. **`Displays.java` 68, 73 and `ViewBuilder.java` 139, 315: `authority::presentation`.** `presentation` is both a field and the method `presentation(long)` on the authority. A method reference finds only the method, as `this::presentation` did. `authority.presentation.observe(…)` (`Displays.java`, `refreshObserved` and `observeWatched`) is the field. Both are package-private now.
4. **`Placement.java` 7 and `ViewBuilder.java` 10: single-type imports of nested types of a class in the same package** (`FluidWorldAuthority.Laid`, `FluidWorldAuthority.ViewIndex`). This is legal. Both types are package-visible (`Laid` public, `ViewIndex` package-private). The `ViewIndex` members `ViewBuilder` reads are package-private: `snapshot`, `nodes`, `pipes`, `transfers`, `incident(int)`, `heat(long)`, `dependent()`, `standing()`.
5. **`Placement.java` 199 and `ViewBuilder.java` 93: `@org.jetbrains.annotations.Nullable` leads the declaration.** For `divert`, the `public` before it is gone. `lineReading` keeps `private`. As in D-R2 point 3: if javac took the annotation as a type-use annotation on a qualified type, it would refuse it here as it did before, and the earlier form compiled.
6. **`FluidWorldAuthority.java` 212: `world.chunks.holdWithoutLevel()`.** A static method of the authority reads the private field `chunks` of an instance of its own class (legal) and calls a package-private method.
7. **`FluidWorldAuthority.java` 1647 and 1774: `views.view(device,islands)` inside the anonymous `HeatPresentation.Source` and the inner `PresentationHost`.** `views` must resolve to the authority's field. `HeatPresentation.Source` and `FluidPresentation.Host` declare no field `views` or `displays` (checked by grep), so lookup reaches the enclosing class.
8. **Overload resolution after the private `place(…, ports, …)` overloads left the authority.** The six public `place` overloads now call `placement.place(…)`. No other authority code called the private ones: `analyze.mjs` and the last check found none. The 1-argument call `view(id)` in `converterFill` still finds the public `view(long)`.
9. **`Displays.java` 345 (`refreshObserved`) reads `authority.instruments==null`.** It is a blank final of another class, so no definite-assignment rule applies. At run time it reads the same field as before, null before the authority's constructor assigns it.
10. **Same-package simple names.**
    - No type named `Displays`, `Placement`, `ViewBuilder` or `ChunkBinding` existed under `src/main` or `src/test` before.
    - `Silhouettes.Placement` and the test-private records named `Placement` (`ExtremeTopologyIslandTest`, `FedTankOpenOutletsTest`) are nested, so they shadow the new top-level `Placement` where they are used.
    - `SilhouettesTest` and the authority name `Silhouettes.Placement` qualified.
    - Each new file keeps the authority's on-demand imports (`science.fluid.network.*`, `science.material.*`, `java.util.*`), so every simple name a moved body uses resolves as it did in the authority. The unused single-type imports were dropped.
11. **Not checked: the code guide's other chapters.** They name moved members under the authority. These are for the merge's code guide update:
    - `fluid-runtime.md` main flow 155-156 (`watchTanks`, …; their bodies are now `Displays`');
    - `heat-exchangers.md` (`FluidWorldAuthority.view`, `lineReading`);
    - `pipe-laying.md` (`lay`, `placeLane`, `drawnFaces`);
    - `equipment.md` (`placeVessel`, `placeMover`);
    - `machine-assemblies.md` 184 (`loadedChunk`, now `ChunkBinding`'s body).

    Their public names are still the authority's, so these lines stay true as entry points.
