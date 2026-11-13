# D-R2 The island coordinator in four files (R13): report

Batch D-R2 of `WAVE_D_BRIEFS.md` (subagent, Opus high). Branch `worktree-agent-a6e3ae407fd3a23f1`, fast-forwarded to `claude/codebase-review-refactor-8a85ef` at `a91f6f9fa`. Four code commits, then this report with the code guide:

| Step | Commit | What moved |
|---|---|---|
| 1 | `49055efaf` | The worker commands and results: `ProcessSolveServices` → `runtime/ProcessSolveJobs` |
| 2 | `93c4dd803` | The exchanger coupling: `IslandCoordinator` → `ExchangerCoupling` |
| 3 | `c7cc76a19` | The certificates: `IslandCoordinator` → `Certificates` |
| 4 | `520b91df7` | The topology change: `IslandCoordinator` → `TopologyChange` |

**Nothing was compiled or run** (the brief: no Gradle, no JVM). Structure only:
- Every moved body is the same text, but for qualifiers (`coordinator.`, `IslandCoordinator.`, `ExchangerCoupling.`), access modifiers and a record component read as its accessor (`attempt.slice` → `attempt.slice()`). Section 4 lists each kind.
- The coordinator calls a part where it called the method, so each tick runs the same steps in the same order.
- No collection or allocation was added. A forwarder is one call. The pump's predicate lambda captures `this` as before.

Summary:
- **Step 1 done, not in `runtime/solve`.** The sealed `ProcessSolveCommand` and `ProcessSolveResult` and their six records move to `runtime/ProcessSolveJobs`, beside `ProcessSolveServices`. The new class has no Minecraft import. `IslandCoordinator` no longer imports `ProcessSolveServices`. Section 2 gives the reason for the package.
- **Step 2 done.** `ExchangerCoupling` takes the coupling section and the bodies of the nine public coupling methods. It owns the fields `transits`, `operations` and `waking`.
- **Step 3 done.** `Certificates` takes `materialise`, `revalidate`, `qualify`, `certify`, `restore` and `certifiedStatus`. No field moves: the certificate state is the island's.
- **Step 4 done.** `TopologyChange` takes `fence`, both `aligned`, `acceptAtFence`, `releaseFence`, `topology` and `sameContents`. No field moves.
- **What stayed on the coordinator.** Its public signatures, each part's public entry as a one-line forwarder with its documentation, the public types `Replacement` and `Evidence`, and every field that two parts write (section 1.2).
- **Checks run (node scripts in `tmp/d-r2/`, gitignored).** Statement by statement over all of `a91f6f9fa..HEAD`, every removed body line reappears among the added lines, after the qualifiers and modifiers are normalised. The lines left unmatched are only the new class headers, the forwarders, five rewritten comments and the split `reconsideringPartners,waking` declaration. Braces, parentheses and brackets balance in all 34 changed files. Two more scripts find no bare reference in a part to a coordinator member, and no `private` coordinator member that a part names.

## 1. The map

The full scratch map is `tmp/d-r2/MAP.md`. Each field and method is listed there with its part, its callers and what it writes.

### 1.1 What each part took

| Part (file, lines) | Methods | Own fields | Called from |
|---|---|---|---|
| `ExchangerCoupling` (279) | `SideRecord`, `SideKind`, `Booked`, `MAXIMUM_BOOKED`, `WAKE_*`, `Coupled`; `coupledSides`, `kindOf`, `partnerOf`, `stream`, `heat` (static); `other`, `partners`, `stoppedSide`, `coupledThrough`, `reads`, `booked`, `moved`, `flushWakes`, `KEEP_UNREAD_TICKS`; the bodies of `operations`, `operation`, `sideTemperature`, `transits`, `settledTransit`, `owe`, `transitAsOf`, `transit`, `removeTransit` | `transits`, `operations`, `waking` | coordinator: `Island(...)`, `runDue`, `pump` (predicate, reads, flushWakes), `closeRound` (booked, flushWakes), `reconsider` (coupledThrough, partners), `verify`, `indexStock` (the statics), the forwarders; `Certificates.materialise` (booked) |
| `Certificates` (139) | `materialise`, `revalidate`, `qualify`, `certify`, `restore`, `certifiedStatus` (static) | none | coordinator: `register` (restore), `snapshot`, `snapshots`, `through`, `suspendForPropertyChange`, `runDue` (materialise), `presentation` (certifiedStatus), `closeRound` (qualify); `ExchangerCoupling.reads`, `flushWakes`; `TopologyChange.aligned` ×2 |
| `TopologyChange` (150) | `fence`, `aligned(UUID, …)`, `aligned(Set, …)`, `acceptAtFence`, `releaseFence`, `topology`, `sameContents` | none | the coordinator's forwarders only |

Each part holds `private final IslandCoordinator coordinator`, set at construction. The coordinator creates the three parts in field initialisers (`IslandCoordinator.java` 252, 254, 256). A part's constructor only stores the reference.

### 1.2 What stayed on the coordinator, and why

The coordinator is now 1,075 lines. It keeps:
- the types `Dispatcher`, `Publisher`, `Settings`, `Attempt`, `Advance`, `Metrics`, `Certified`, `Snapshot`, `Island`, `Pending`, `Ports`, `Shafts`, `Round`, `PhaseHolds`, `Transported`, `Evidence`, `Replacement`;
- the constructors and the installers;
- the presentation and facts reads (`presentation`, `previewTarget`, `bulkVolumeRates`, `transported`, `snapshot`, `observe`, …);
- `through`, `now`, `time`, `schedule(kind, …)`, the suspension and resume;
- the tick: `tick`, `runDue`, `pumpIfUseful`, `pump`, `capacity`, `completed`, `closeRound`;
- the hold policy: `hold`, `budgetHold`, `inputsChanged`, `phaseHoldStatus`, `warnPhaseHold`;
- the commit check: `validCandidate`, `sameConnections`, `sameControl`;
- `stop`, `require`, `reconsider`, the deadline helpers, `releaseLine`, the indices and `verify`.

Fields that stayed although a part reads or writes them. They are now package-private (no modifier):

| Field | Written by | Read by a part |
|---|---|---|
| `islands` | `register` (C), `topology` (T) | X, R, T |
| `pending` | `pump`, `completed`, `closeRound` (C) | T (`fence`) |
| `ready` (the `FairQueue`) | `register`, `reconsider` (C), `topology` (T) | T |
| `generations` | `reconsider`, the deadline helpers (C), `topology` (T) | T |
| `fenceOwners` | `index`, `unindex`, `removeFence` (C), `fence` (T) | T |
| `stockOwners` | `indexStock`, `unindexStock` (C) | T; `verify` (C) |
| `coupled`, `pairSides` | `indexStock` (C), `topology` (T) | X |
| `sideOwners` | `indexStock`, `unindexStock` (C) | X, T |
| `toWake` | `controlsMoved`, `shaftsMoved`, `portsMoved` (C), `booked`, `operations`, `flushWakes` (X) | X |
| `settings`, `epoch`, `publisher`, `replayed`, `ports`, `shafts`, `stopped`, `suspension` | the constructor, the installers, `stop`, the suspension (C) | X, R, T |

Methods that stayed although a part calls them. They are now package-private:
- `owned`, `require`, `reconsider`: every part calls them.
- `timed`: `register` and `topology` call it.
- `inputsChanged` (static): `resumeQualifiedProperties`, `fence` and `releaseFence` call it.
- `index`, `indexStock`, `unindex`, `unindexStock`: `register` and `topology` call them. `indexStock` writes the stock index and the coupled sides, so it stays with both indices.
- `removeFence(Island, UUID)`: it keeps `fenceOwners` with `index` and `unindex`.
- `observedAs`, `observationPoints` (static): `attachObservationPoints` and `presentation` (C) call them, and `materialise` (R).
- `Island` and its members, and `Pending` (class, `attempt`, `closed`): the parts read and write them.

Not moved, with the reason:
- `releaseLine`: it stood in the certificate section, but `Island(...)` and `closeRound` call it. It builds the status line's open-vessel notice, not a certificate.
- `previewTarget`: only the coordinator's presentation reads call it.
- `flushWakes` went to `ExchangerCoupling`, with the section it stood in. It ends the certificates of every island in `toWake`, which also holds the islands that `controlsMoved`, `shaftsMoved` and `portsMoved` woke.
- `IslandCertificate` and `CertificatePolicy` are unchanged and stay separate classes, as the brief says.

The review's estimate was a coordinator of about 600 lines. The estimate placed the hold policy, the presentation reads and the indices elsewhere. The brief places them in no part, so they stay.

## 2. The records' new home

`src/main/java/com/wormzjl/icheme/runtime/ProcessSolveJobs.java` (133 lines; package `com.wormzjl.icheme.runtime`; no Minecraft import). It holds:
- `ProcessSolveCommand`, `ProcessSolveResult` (sealed);
- `FluidIslandCommand` with `notices`, `WALL_DEADLINE`, `PHASE_HOLD` and the private `FluidWallDeadline`;
- `FluidIslandSolveResult`;
- `ColumnUnitCommand`, `ColumnUnitSolveResult`;
- `StillCommand`, `StillSolveResult`.

`ProcessSolveServices` (654 → 545 lines) keeps the targets, requests, completions and the adapter. It imports the eight types by name, and drops the four imports it no longer uses.

Why this package and not `runtime/solve` (the review's map):
- The mod has no `module-info.java`, so it is an unnamed module. A sealed interface and every class it permits must then be in one package. Moving `FluidIslandCommand` alone would leave the new class pointing back at `ProcessSolveServices` through its supertype. So the whole worker protocol moves (the column's and the still's commands with it), and the new class names nothing of `ProcessSolveServices`.
- `FluidIslandCommand.solve` reads `WorkerAllocation.CpuTime`, which is package-private in `runtime`. Its package-private `solve(token, nanoClock)` is called by three tests in `runtime`: `FluidIslandCommandTest`, `FluidDataRangeIslandTest`, `FluidPumpedFillLineTest`.
- `runtime/solve` would take this class away from the `BoundedCpuSolveService` and `WorkerAllocation` that the map puts beside it. Neither is in this lane.

The new class can move to `runtime/solve` with those two classes, in one later step.

These are in-memory records. `FORMATS.md` names none of them and none of their constants, so `FormatsTableTest`'s table is unchanged. No reflection names them: `FluidContentionProbe` reflects only on `ProcessSolveServices.STATES` and `ServerState`'s fields.

Callers changed in qualifiers and imports only (98 qualifiers):
- main: `IslandCoordinator`, `MinecraftFluidRuntime`, `FluidWorldAuthority` (the column's and the still's command, `PHASE_HOLD`);
- 23 test sources;
- 3 GameTest sources (`FluidCheckpointGameTests`, `FluidCompletionBudgetGameTests`, `FluidContentionProbe`);
- the two campaign harnesses (`tools/unified-thermo-port-forms/hold-fuzz-{cold,bundled}/src/HoldFuzzTest.java`) and `tools/codebase-review-bench/MachineBench.java` (in use in this batch).

The 62 archived probes under `tools/` are not compiled and are left as they are (as in D-R1). `network/ProcessSolveCoordinator` names none of the moved types and is unchanged.

## 3. Lines per file

| File | Before | After |
|---|---|---|
| `runtime/fluid/IslandCoordinator.java` | 1,543 | 1,075 |
| `runtime/fluid/ExchangerCoupling.java` | (new) | 279 |
| `runtime/fluid/Certificates.java` | (new) | 139 |
| `runtime/fluid/TopologyChange.java` | (new) | 150 |
| `runtime/ProcessSolveServices.java` | 654 | 545 |
| `runtime/ProcessSolveJobs.java` | (new) | 133 |

The code guide's README counts: `runtime/**` goes from 4 + `fluid/` 50 to 5 + `fluid/` 53 (`map.mjs --readme`; `--check` passes).

## 4. What a moved body may differ in, and the guards

- **Qualifiers.** `coordinator.x` for a coordinator field or method. `IslandCoordinator.x` for its statics `observedAs`, `observationPoints` and `inputsChanged`. `ExchangerCoupling.x` for the side statics that `Island(...)` and `indexStock` call. `coordinator.certificates.` and `coordinator.coupling.` where one part calls another.
- **Method reference.** In `topology`, `this::require` is now `coordinator::require` (`TopologyChange.java` 73). It is the same bound reference to the same object.
- **Record accessors.** A part cannot read a record's private component field, so:
  - `attempt.slice` → `attempt.slice()` (`Certificates.java` 84, `TopologyChange.java` 28);
  - `attempt.islandId` → `attempt.islandId()` (`TopologyChange.java` 28);
  - `settings.time` → `settings.time()` (six times in `ExchangerCoupling`);
  - `settings.initialCadenceTicks` → `settings.initialCadenceTicks()` (`TopologyChange.java` 74);
  - the `Replacement`'s `id`, `graph`, `model`, `held` → their accessors (`TopologyChange.java` 94 to 116).

  None of these records overrides an accessor, so the value is the same.
- **Modifiers.** `private` is removed where a part or the coordinator now reaches across files. `public` is removed from the moved bodies of public methods: they are package-private in the part, and the public signature stays on the coordinator.
- **Javadoc links.** In moved comments, `{@link #through}` and `{@link #presentation preview}` now name `IslandCoordinator#…`. In the coordinator, `{@link #acceptAtFence}` now names `TopologyChange#acceptAtFence`.
- **Thread guard.** The class has no `synchronized` and no assertion about the calling thread. Its only guard is `owned()`, which compares `Thread.currentThread()` with the coordinator's `owner`. Every moved public body keeps its check first, as `coordinator.owned()`:
  - `ExchangerCoupling.java` 246, 252, 253, 254, 256, 268, 272, 275, 278;
  - `TopologyChange.java` 25, 35, 67, 71.

  So it guards the same thread of the same coordinator. A forwarder adds no check. The internal call of `settledTransit` from `moved` still passes the check, as it did before.

## 5. Tests for the main thread

Files changed since the last pass:
- main: `IslandCoordinator`, `ExchangerCoupling`, `Certificates`, `TopologyChange`, `ProcessSolveServices`, `ProcessSolveJobs`, `MinecraftFluidRuntime`, `FluidWorldAuthority` (qualifiers only);
- 23 test sources, 3 GameTest sources, the two campaign harnesses, `MachineBench`.

Almost every class under `runtime/fluid` in the test source set loads the coordinator. The precise set is what `node tools/merge-lane/affected.mjs select` gives after `testClasses` and `compileFluidGameTestJava`. The base:

| Task | Classes | What it proves |
|---|---|---|
| `compileJava`, `compileTestJava`, `compileFluidGameTestJava` | all | The moves, the widened access and the 98 rewritten qualifiers compile |
| `test --tests` | `StillRuntimeTest` (its two determinism gates), `ColumnRunTest` | Islands run on different completion schedules give the same results bit for bit: dispatch, round and commit order did not move. The still's and the column's fences (`through`, `controlsMoved`, `sideTemperature`, `owe`, `transitAsOf`, `operation`) reach the same state |
| `test --tests` | `ExchangerCouplingTest`, `FiredHeaterFuelTest`, `SteamHeaterCoilTest`, `ShaftCouplingTest`, `MachineHealthTest` | Step 2: the coupling's fence, reads, bookings, wakes and transits, and the side records as the coordinator keeps them |
| `test --tests` | `IslandCertificateTest`, `RestIslandTest`, `RestGaugeTest`, `RestResolutionWorldTest`, `CadenceTrajectoryQualificationTest`, `CrystalIslandRuntimeTest` | Step 3: entry, issue, replay, horizon, restore |
| `test --tests` | `IslandCoordinatorTest`, `PhysicalRegistryTest`, `PropertyReloadCoordinatorTest`, `InlineFilterLineHoldTest`, `LoneBoundaryJoinTest`, `QueuedRemovalWalkTest`, `PresentationBucketTest` | Step 4: fences, alignment, a held owner's acceptance, replacement; the hold policy and the schedule (`VERIFY` is on in `test`) |
| `test --tests` | `FluidIslandCommandTest`, `FluidDataRangeIslandTest`, `FluidPumpedFillLineTest`, `WorkerTrajectoryEquivalenceTest`, `TimeFactorElectricTest`, `CompletionWakeupTest`, `FluidCheckpointFormatTest`, `FluidCheckpointStoreTest`, `FluidFactsCompatTest`, `BoundedCpuSolveServiceTest`, `ProcessSolveServicesRequestTest` | Step 1: the commands and results in their new class, and the save corpus (no saved name moved) |
| `test --tests` | `RetainedSolverTest`, `KeptLuStorageTest` | The brief names them: the retained solver as the command carries it |
| `test --tests` | `LayeringTest`, `FormatsTableTest` | No new reference across layers (`runtime` names no `client`); the formats table unchanged |
| `holdGates --tests` | `IslandSchedulerTest`, `ExtremeTopologyIslandTest`, `HoldClassWorldsTest`, `JunctionSeedingGateTest` | The scheduler's order and cost gates; extreme topologies; the hold classes (`PHASE_HOLD` now in `ProcessSolveJobs`); the warm carry across a replacement (`TopologyChange.topology`) |
| final lane | chain-100 exact, the fluid GameTests, the campaign (its inputs include `runtime/**` and the two harnesses edited here) | Results bit for bit on the full network; the GameTest sources and the harnesses compile and run the same |

- `CrossflowHeatTest` and `RestResolutionTest` (both `science/fluid/network`) are in the brief's list. They cannot load a changed file: `science` imports nothing from `runtime` (`LayeringTest`). By AGENTS.md's "No repeated tests" they need no run.
- The test classes that only gained a qualifier also prove that their own sources compile.

## 6. Points not verified (compile risks), with file and line

Every point below was read and checked against the language rules by hand. None was compiled.

1. **`ProcessSolveJobs.java` 22 and 27.** The sealed interfaces permit records nested in the same top-level class. This is legal, the unnamed module needs one package, and all are in `runtime`. `ProcessSolveServices.java` 9 to 16 imports those nested types from a class of its own package (also legal).
2. **`ExchangerCoupling.java` 3 and `IslandCoordinator.java` 5 to 6.** Each imports a nested type of the other, in one package. This is legal. `science.fluid.network` has no type named `Island`, `Coupled` or `SideRecord` (checked), and a single-type import shadows an on-demand one anyway.
3. **`ExchangerCoupling.java` 252.** `@org.jetbrains.annotations.Nullable ExchangerRelay.Operation operation(long side)` now has no other modifier. The annotation stands in the same place as before, after `public`. If javac read it as a type-use annotation on a qualified type, it would refuse it here as it would have before; the earlier form compiled.
4. **`IslandCoordinator.java` 128 (`Island`) and 213 (`Pending`).** These nested classes are now package-private, and every `Island` member is too. `TopologyChange.java` 114 calls the `Island` constructor, and 28 reads `Pending.attempt` and `Pending.closed`. All are without `private` (checked by `tmp/d-r2/access.mjs`).
5. **`IslandCoordinator.java` 252, 254 and 256.** `new ExchangerCoupling(this)` and the other two run in field initialisers, before the constructor body. The part constructors only store the reference, and no initialiser calls a part.
6. **`TopologyChange.java` 73.** `coordinator::require` is a bound method reference to a package-private method that returns the package-private `Island`. Both are accessible from the package.
7. **`IslandCoordinator.java` 868 and `Certificates.java` 31 to 32.** The javadoc links now name another class's member, a private one in the first case. Javadoc may warn; javac does not, and `build.gradle` sets no `-Xdoclint` or `-Werror`.
8. **`ProcessSolveServices.java`.** The class comment's `{@link ProcessSolveCommand}` resolves through the new import.
9. **Not checked: the code guide's other chapters.** These are outside this lane and still name a moved member under its old class:
   - `heat-exchangers.md` 70 and 163/243 (`IslandCoordinator.coupledThrough` and `.reads`, now `ExchangerCoupling`);
   - `heat-exchangers.md` 234 (`ProcessSolveServices.FluidIslandCommand`);
   - `verification.md` 147 (`ProcessSolveServices.PHASE_HOLD` and `WALL_DEADLINE`);
   - `client-gui.md` 167 (`ProcessSolveServices.PHASE_HOLD`).

   They are for the merge's code guide update. `HISTORY.md` 104 is history and stays.
