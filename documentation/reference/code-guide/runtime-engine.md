# The fluid runtime's engine: coordinator, scheduler, certificates, solve services

Up: [code guide](README.md). Beside it: [runtime-registry.md](runtime-registry.md) (the authority, placement, topology), [runtime-saves.md](runtime-saves.md), [runtime-presentation.md](runtime-presentation.md), [runtime-units.md](runtime-units.md). The solver it runs: [fluid-interval.md](fluid-interval.md).

<!-- j2a-packages: runtime/fluid/IslandCoordinator.java runtime/fluid/ExchangerCoupling.java runtime/fluid/Certificates.java runtime/fluid/IslandScheduler.java runtime/fluid/IslandClock.java runtime/fluid/IslandCertificate.java runtime/fluid/CertificatePolicy.java runtime/fluid/RetainedSolver.java runtime/fluid/MinecraftFluidRuntime.java runtime/fluid/IslandSets.java runtime/fluid/FluidRuntimeDiagnostics.java runtime/fluid/FluidPropertyReloadGuard.java runtime/ProcessSolveCoordinator.java runtime/ProcessSolveJobs.java runtime/ProcessSolveServices.java runtime/BoundedCpuSolveService.java runtime/WorkerAllocation.java -->

## Read this when

- An island solves late, too often or never; it holds, retries, parks on a phase hold, certifies (STEADY) or wakes at the wrong time.
- Something runs on the tick loop, or a result depends on which worker finished first.
- Two coupled islands (an exchanger's sides, a kettle's tubes and pool, a fired heater's coils) wait for each other wrongly or drift apart.
- An island carries the wrong compound list, or arrays move wrongly between lists (`IslandSets`).
- The worker pool is sized wrongly, a job is refused (`OWNER_BUSY`) or a completion is late.
- A datapack reload holds the network (`HELD: property data changed …`).
- You change a `[solver]` or `[fluid]` key of `icheme-common.toml`.

## What it does

Only online time moves the plant: one server tick is one online tick at a nominal 20 TPS, times the time factor in process seconds. Nothing advances while the server is off. `IslandCoordinator` keeps one deadline per island in `IslandScheduler`, on an `IslandClock` over the shared epoch (the ledger's online tick). When a slice falls due, the coordinator sends an immutable command to the shared worker pool (`BoundedCpuSolveService` through `ProcessSolveServices`); the worker runs the interval solver on the island's `RetainedSolver`. Completions are drained on the server thread (`ProcessSolveCoordinator`), and a round's results are committed together, all or nothing. A commit is a publication: it only marks loaded devices dirty ([runtime-presentation.md](runtime-presentation.md)). An island whose interval repeats is certified (`Certificates`, `IslandCertificate`) and advances by a linear replay instead of solving. Coupled heat sides in two islands wait for each other behind a fence (`ExchangerCoupling`); a running column's or still's ports and the shaft trains join through the coordinator's `Ports` and `Shafts` hooks ([runtime-units.md](runtime-units.md)).

## Class map

Under `src/main/java/com/wormzjl/icheme/runtime/`. Lines at `d6f721b94`.

| Class | Role | Lines |
|---|---|---|
| [`fluid/IslandCoordinator`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/IslandCoordinator.java) | Clocks, rounds, commits (`validCandidate`, `sameControl`), holds and the phase-hold park, the schedule (`reconsider`, `runDue`, `pump`, `verify`), the status line's `notice`; the islands, attempts, ready queue and indices; the `Ports` and `Shafts` hooks; `reached`, `movedAt`, `model(island)`, `register`, `entry`, `attachObservationPoints`; nested `Island`, `Pending`, `Settings` | 1111 |
| [`fluid/ExchangerCoupling`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/ExchangerCoupling.java) | The coupling of heat sides across islands: the fence (`coupledThrough`), the reads at dispatch (`reads`), the booking (`booked`), the wake (`moved`, `flushWakes`), the transit by pair (`transits`, `settledTransit`, `transit`, `owe`, `transitAsOf`, `removeTransit`), the real valves (`operations`, `operation`) | 277 |
| [`fluid/Certificates`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/Certificates.java) | The islands at rest: entry evidence (`qualify`), issue (`certify`), restore at registration (`restore`), materialisation and horizon revalidation (`materialise`), `certifiedStatus` | 153 |
| [`fluid/IslandCertificate`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/IslandCertificate.java) | Stationarity evidence, the linear replay of the last interval (`graphAt`), the saved form, `Signature`, `REPLAY_REVISION`, `MAXIMUM_PROPERTY_REVISION` | 459 |
| [`fluid/CertificatePolicy`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/CertificatePolicy.java) | Certificate settings captured at server start: defaults and bounds | 29 |
| [`fluid/IslandScheduler`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/IslandScheduler.java) | Deadline heap on the online epoch, lazy invalidation by generation, `Kind`; nested `FairQueue` (round-robin among ready islands) | 135 |
| [`fluid/IslandClock`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/IslandClock.java) | Online, committed and retry ticks and the cadence on the shared epoch: `nextSlice`, `readyAtTick`, `rest`, adaptive cadence | 114 |
| [`fluid/RetainedSolver`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/RetainedSolver.java) | Per-island solver cache, reset per job to the committed interval (`replayStart`); `solve(…, reads, ports)` | 76 |
| [`fluid/MinecraftFluidRuntime`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/MinecraftFluidRuntime.java) | Bridges the coordinator to `ProcessSolveServices`: the `Dispatcher`, the readiness pump, island to dimension, `topology`, `failure` | 75 |
| [`fluid/IslandSets`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/IslandSets.java) | Island compound sets: a new island's list, `MAXIMUM_COMPOUNDS`; arrays moved by id (`amounts`, `state`, `node`, `graph`, `result`, `held`, `onto`, `presented`) | 247 |
| [`fluid/FluidPropertyReloadGuard`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/FluidPropertyReloadGuard.java) | Compares the model revision rebuilt from the live catalog with the startup one (`HOLD`); `validateSolids` | 44 |
| [`fluid/FluidRuntimeDiagnostics`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/FluidRuntimeDiagnostics.java) | Opt-in counters (island visits, pumps, views, packets, `couplingWakes`, `phaseHolds`, `heldEventAcceptances`, `droppedHeldTicks`, `realign…`), off by default; nested `Meter` | 146 |
| [`ProcessSolveCoordinator`](../../../src/main/java/com/wormzjl/icheme/runtime/ProcessSolveCoordinator.java) | The one completion router (column unit, still or fluid) and stop routing: `drainCompletedCalculations` | 83 |
| [`ProcessSolveJobs`](../../../src/main/java/com/wormzjl/icheme/runtime/ProcessSolveJobs.java) | The worker side of the pool's protocol, no Minecraft type: the sealed `ProcessSolveCommand` and `ProcessSolveResult` and their three families' records; `WALL_DEADLINE`, `PHASE_HOLD`, `notices` | 133 |
| [`ProcessSolveServices`](../../../src/main/java/com/wormzjl/icheme/runtime/ProcessSolveServices.java) | The pool's server lifecycle adapter: targets, requests and completions of the three families (`submitFluidIsland`, `submitColumnUnit`, `submitStill`), `MAXIMUM_COMPLETIONS_PER_TICK`, `owe`; nested `CompletionWakeup` | 545 |
| [`BoundedCpuSolveService`](../../../src/main/java/com/wormzjl/icheme/runtime/BoundedCpuSolveService.java) | Generic bounded pool: one job per owner, a bounded ready queue, two lanes (`trySubmit` with `reserved`, `setReserve`, `ordinaryLimit`), cancellation, two-phase shutdown | 870 |
| [`WorkerAllocation`](../../../src/main/java/com/wormzjl/icheme/runtime/WorkerAllocation.java) | Worker count resolution; demand grows at once and shrinks after 200 ticks | 77 |

## Main flow

A due deadline to a published round:

1. `IslandClock.readyAtTick` gives the online tick at which time alone makes a slice ready; `reconsider` books it as one `SLICE_DUE` (or `RETRY`) entry in `IslandScheduler`.
2. Each tick `IslandCoordinator.tick` pops due entries (`runDue`); the island becomes ready in `FairQueue`, and `pumpIfUseful` → `pump` runs. Capacity is min(idle workers, 64 − dispatched this tick, 64 − pending).
3. Per admitted island: `IslandClock.nextSlice` → the `Shafts` hook's `plans` (`ShaftPlan.apply`), the `Ports` hook's `plans`, `ExchangerCoupling.reads` → `FluidIslandCommand(model, graph, seconds, settings, hardBudget, retainedSolver, reads, ports)` → `ProcessSolveServices.submitFluidIsland`. The admitted islands form one round with a `ROUND_TIMEOUT` deadline.
4. The worker runs the full solve on the whole wall budget and posts a terminal message: an accepted result carries the data-range notices of its end state (`ProcessSolveJobs.notices`); a failed one may carry a phase hold. `CompletionWakeup` posts one `TickTask`, so the drain may run between ticks.
5. `ProcessSolveCoordinator.drainCompletedCalculations` routes at most `MAXIMUM_COMPLETIONS_PER_TICK` completions (the rest are owed to the next tick), then calls the readiness pump.
6. `IslandCoordinator.completed` marks the attempt terminal; a result after the hard budget counts as none.
7. When every entry is terminal or the budget has run out, `closeRound` validates each candidate (revision, interval, commit hook), commits it (the `Ports` hook's `booked`, the `Shafts` hook's `booked`, `ExchangerCoupling.booked`) or applies the hold policy, samples cadence, runs `Certificates.qualify` and calls `publisher.published(changed)`.
8. At each decision point after the round: `shafts.decide()`, then `ports.decide()`, `flushWakes`.

## Contracts

- **Server thread owns everything.** `FluidWorldAuthority`, `MinecraftFluidRuntime`, `IslandCoordinator`, `IslandScheduler`, `IslandClock` and `FluidPresentation` check `owned()` and throw on any other thread.
- **Tick hook order** (`IChemE.onServerTickPost`): meter enter (`FluidRuntimeDiagnostics.Meter`) → `drainCompletedCalculations` (property refresh, route, `pumpReady`) → `FluidWorldAuthority.tick` (property refresh, online tick +1, `coordinator.tick`, apply pending events and pump, `InstrumentService.tick`, the presentation bucket, the assembly presentation tick, `setDirty`) → `AssemblyService.tick` → meter exit. Between ticks, `CompletionWakeup` → `server.tell(TickTask)` → `drainCompletedCalculations`, so a completion may commit between ticks; presentation still waits for the bucket. The level tick (`IChemE.onLevelTickPost`) runs `FloatingRoofs.carry` (a read of each tank's glide) and the registry's re-alignment. Packet handlers run on `HandlerThread.MAIN`. A save's capture and encoding run on the server thread (a capture materialises certified islands); the pack and the core record are written on Minecraft's IO worker.
- **Workers** (thread prefix `icheme-cpu-solve-`) receive only an immutable `ProcessSolveCommand` and the island's `RetainedSolver`, latched by `SolverOwnership`. They never call back; they put an immutable terminal message on a queue and fire `CompletionWakeup`.
- **Two lanes.** An island's slice is a reserved job: it never waits; it runs on a free worker ahead of queued ordinary jobs or is refused. While any island is ready, `ProcessSolveServices` keeps one worker free of ordinary (plant) jobs; with a worker limit of one nothing is reserved. A plant job's deadline (`solver.deadlineMilliseconds`) counts from the moment a worker starts it. `submitFluidIsland` refuses a busy island `OWNER_BUSY` by its own owner scan, before the worker check and the allocation.
- **Three job families**: fluid islands, column units (`ColumnUnitCommand`, a running column's split-table solve) and stills (`StillCommand`). A fluid island is admitted only while no job waits and a worker is idle, so islands never wait in the ready queue; column-unit and still jobs do. A worker failure, cancellation, deadline or shutdown gives the column `ColumnSolve.Failed(reason, true)`, a transient failure.
- **Each island has at most one live deadline**; a stale entry is dropped on pop by its generation. Presentation buckets are not in the heap.
- **Clock.** `onlineTick = epoch − base`; debt = online − committed; a slice is `[committed, min(committed + min(cadence, maxSlice), fence)]`. A slice dispatches `slice.seconds(time)` process seconds with `PassiveIntervalSolver.Settings.defaults(time)`; every island model carries the coordinator's time factor (`timed`). Schedule quantities stay in play ticks.
- **Wall time decides only the round budget and cadence samples**, never simulated time. The `ROUND_TIMEOUT` deadline assumes 50 ms ticks, so `tick()` also checks every open round's elapsed time.
- **Fences.** An island keeps its nearest fence and a frozen copy of its fences, updated where fences change, so a readiness test and a snapshot read them in O(1).
- **Certified island.** Never ready; it drops its solver cache and holds one `CERTIFICATE_HORIZON` deadline. Its committed tick moves only by `Certificates.materialise` (a linear replay to `min(online, fence, hold tick, horizon)`) on a snapshot read, an alignment, a save, a coupled side's read, a property hold or its own deadline; never on a view. A presentation read (`IslandCoordinator.presentation`) is a preview: below `previewTarget` it shows the certificate's state at that tick (`graphAt`, with the island's taps) and changes nothing on the island. A topology event or a property resume discards the certificate.
- **Reads and wakes as of a tick.** A reader of a certified partner takes it without materialising it (`reached`); a dated change wakes an island at the change's own tick (`movedAt`: materialised to that tick, its certificate ended there, however late the call).
- **Coupled islands** ([heat-exchangers.md](heat-exchangers.md)): an island with a coupled side is ready only when each partner has committed through its committed tick, is certified (materialised at dispatch), is stopped (held, phase-parked or suspended) or has no island (`coupledThrough`). Its command carries each side's read of its partner. A commit or replay books each side's heat against the pair's transit; a certified partner whose read moved loses its certificate at the next decision point (`flushWakes`), never inside a presentation read. The settled transit adds its sides in a canonical order (the lower island's side first, then the lower side id), so a loaded world and one that ran on give the same bits.
- **A running column** ([column-operation.md](column-operation.md)): an island with a port is ready only when `ports.ready` says its run has cycled past the slice start less the fence; `plans` go into the command; a commit calls `ports.booked`, which only records; `ports.decide()` runs a cycle that fell due; `schedule` takes a `COLUMN_CYCLE` deadline; `portsMoved` wakes the island of a port a fence after its level moved; `through(id, tick)` tells the column an island has committed through a tick.
- **Island compound sets** (`IslandSets`, owner option B): a new island's list is nitrogen, every compound its stock holds, every compound a running column's port in it may deliver, and the whole list of every island it takes a device from unless the batch splits that island; a list only grows while its island lives. Water is on every conserved basis, last; the set names `Water` only where the island holds some (a list without it runs dry, `FluidThermodynamics.island`); a running column's or still's outlet names `Water` from its start. At most `MAXIMUM_COMPOUNDS`; an island formed past it holds as a capability limit and accepts the next topology event at its fence. A cut is exact only between models of the same water; a widening pads zeros and evaluates each state again; a nonzero amount off the target list is refused; a cut phase keeps the kernel support it was evaluated on.
- **Property reload.** `FluidPropertyReloadGuard` sets a hold and `suspendForPropertyChange` freezes committed time while the debt keeps accruing; a running world never restates (a reopened one restates from its facts, [runtime-saves.md](runtime-saves.md)); `resumeQualifiedProperties` discards every certificate. The guard and the certificate `Signature` compare `FluidThermodynamics.revision()`; the guard keeps the time factor.

## Laws and constants

**Adaptive cadence** (`adaptiveCadence`): 3 CPU-bound samples (CPU/wall ≥ 0.75 and wall ≥ the soft budget) give ×1.25, up to 400 ticks; 10 light samples give ×0.9, down to 20 ticks. **Budgets**: hard = `fluid.wallBudgetMilliseconds`, every attempt the full solve on it; soft = 75 % of it, the cadence's load threshold only.

**Hold policy** (`hold`): every hold gets a fresh `RetainedSolver`. A slice longer than one tick is halved; at one tick the retry waits cadence × 2^k, k ≤ 6. Each accepted interval doubles the slice cap back, lifted once it reaches cadence/2. A phase hold whose fresh-solver retry meets the same hold (`ThermoHold.sameAs`) parks the island (`holdUntilInputsChange`): `HELD (phases): …; the retry failed the same way, so the island waits for a change to its network`, its WARN at most every `PHASE_HOLD_WARNING_TICKS`. The park ends at `inputsChanged`: a fence, a released fence or a property resume. A completion without a result names why in `HELD: …` (`MinecraftFluidRuntime.failure`: a worker failure, its terminal status, an abandoned solve, the round deadline).

**Held island and a topology event** (`acceptAtFence`, owner decision 2026-10-15): `Island.held` is set in `closeRound` when the attempt was not accepted (outside a suspension, at the current revision) and cleared by an accepted interval. The fence install calls `inputsChanged`, so a held island first gets one fresh attempt. When the registry asks for alignment and the island is still held (no certificate, no pending entry, no attempt, no suspension, its fence in (committed, online]), the clock moves to the fence without a solve (`clock.rest(fence, fence)`): no flow, so mass and energy stay exact, and the online time between is dropped (`droppedHeldTicks`). A property hold still freezes topology events.

**Certificate entry** (`qualify`): the interval accepted, outside a stop or a property suspension, free of solid-transport transitions and phase-hold rejections (reasons with `ThermoHold.REASON_PREFIX`). An open vessel's vent and spill are rates a steady island repeats and do not stop it; a changed closure, forbidden direction or filter cake does. The interval must repeat the previous one within `certificateStationaryTolerance` for `restConfirmIntervals` intervals in a row (at least 2 unless the map is the exact identity); then `IslandCertificate.issue`. REST and STEADY are one certificate kind: `STEADY: no flow since … s of play` or `STEADY: replaying X kg/s since … s of play[, next check at … s of play]`, then the open vessels' release line (`releaseLine`) and the data-range notice. At the horizon one revalidating slice renews a stationary result.

**Status of an accepted island**: its detail plus its notice (`outcome.detail() + island.notice`): the release line, then the data-range notice.

**Signature** (`IslandCertificate.Signature`): the property revision (at most `MAXIMUM_PROPERTY_REVISION` characters; it names the package's fluid identity twice), the policy values and `REPLAY_REVISION` (bump it when what a replay holds fixed about an island, or the saved interval's decoding, changes a replayed result).

| Constant | Meaning | File |
|---|---|---|
| `PHASE_HOLD_WARNING_TICKS` | a parked island's WARN interval | `IslandCoordinator` |
| `REPLAY_REVISION`, `MAXIMUM_PROPERTY_REVISION` | the certificate's declared replay revision; the signature's bound | `IslandCertificate` |
| `MAXIMUM_COMPOUNDS` | an island's compound limit | `IslandSets` |
| `MAXIMUM_COMPLETIONS_PER_TICK` | completions routed per tick | `ProcessSolveServices` |
| `HOLD` | the property hold's status text | `FluidPropertyReloadGuard` |

**Config** (`config/icheme-common.toml`, read once by `IChemE.fluidOptions()` when the authority is built): `solver.workers` (0 automatic, at most `automaticWorkerLimit` and processors − 2), `solver.automaticWorkerLimit`, `solver.readyQueueCapacity`, `solver.deadlineMilliseconds` (plant jobs), `solver.gracefulShutdownMilliseconds`, `solver.forcedShutdownMilliseconds`; `fluid.initialIntervalSeconds` (new islands), `fluid.adaptiveCadence`, `fluid.wallBudgetMilliseconds`, `fluid.maximumVelocityMetresPerSecond`, `fluid.reservoirVolumeCubicMetres`, `fluid.fluidTraceCutoffMoleFraction`, the solid keys, `fluid.debugChat`, `fluid.restDetection` (certificates on or off), `fluid.certificateStationaryTolerance`, `certificateInventoryBudget`, `certificateMaximumIntervals`, `restConfirmIntervals`, `restRecheckSeconds`, `fluid.timeScale` (`SimulationTime.DEFAULT_FACTOR`, captured at start); `energy.*`, `create.*` and `machines.wear.*` are [rotating-machinery.md](rotating-machinery.md)'s. The defaults are in `IChemE`.

## Formats

The coordinator's state is saved through the checkpoint ([runtime-saves.md](runtime-saves.md)): each island's clock, cadence and certificate summary in the core record, its certificate base and signature in its unit. `REPLAY_REVISION` is a row of [FORMATS.md](../FORMATS.md). The pool's commands and results are in memory only.

## Tests and gates

| Rule | Test |
|---|---|
| Ready and deadline logic, fairness | `IslandCoordinatorTest`, `IslandSchedulerTest` (`holdGates`), `FairQueueEquivalenceTest`, `IslandClockTest` |
| Certificates: entry, replay, horizon | `IslandCertificateTest`, `RestIslandTest`, `RestGaugeTest`, `RestResolutionWorldTest`, `CrystalIslandRuntimeTest.aDepositedVesselCertifiesAndReplaysItsCrystalsExactly` |
| Hold and retry, phase-hold park | `IslandCoordinatorTest.aReproducedPhaseHoldWaitsForAnInputChangeInsteadOfTheLadder`, `aDifferentHoldOnTheRetryKeepsTheLadder`, `RetainedSolverTest`, `WorkerTrajectoryEquivalenceTest`, `HoldClassWorldsTest` |
| A held island at a fence | `IslandCoordinatorTest.aHeldOwnerAcceptsAFencedEventAtItsCommittedTickWithItsInventoryUnchanged` |
| The wall budget of an attempt | `FluidIslandCommandTest.aJobPastTheOldSoftBudgetIsTheFullSolveOnTheWholeWallBudget` |
| Data-range notice on the status line | `FluidDataRangeIslandTest.aCompressorHeatingNitrogenPastItsRecordKeepsRunning` |
| The coupling and the settled transit | `ExchangerCouplingTest` (incl. `theSettledTransitIsTheSameWhicheverOrderTheSidesAreRegisteredIn`), `HeatSideTopologyTest` |
| Island compound sets | `IslandSetsPresentedTest`, `FluidFactsCompatTest.twoIslandsOnDifferentListsLoadEachOnItsOwnList` |
| Worker pool, drain budget, wakeups | `BoundedCpuSolveServiceTest` (the reserve, the start-relative deadline), `ProcessSolveServicesRequestTest`, `CompletionWakeupTest`, `WorkerAllocationTest`, GameTest `FluidCompletionBudgetGameTests` |
| Property reload | `FluidPropertyReloadGuardTest`, `PropertyReloadCoordinatorTest`, GameTests `FluidPropertyReloadGameTests`, `MaterialReloadGameTests` |
| Time factor | `TimeScaledSolverSettingsTest` |
| Counters | `FluidRuntimeDiagnosticsTest` |

`-Dicheme.fluid.scheduler.verify=true` re-derives every island's schedule at each pump and tick and fails on drift; `test`, `fluidRuntimeTest`, `fluidScienceTest` and `fluidGameTestServer` set it. `fluidRuntimeTest` takes `runtime.fluid.*` and `runtime.Fluid*Test`; `BoundedCpuSolveServiceTest`, `PresentationMailboxTest` and `ProcessSolveServicesRequestTest` run only under `test`. The benchmark is `fluidServerBenchmark` (a timing run: alone, AGENTS.md).

## Traps

- **An accepted interval's status reads `FULL`** (`ProcessSolveJobs`), a name left from a removed mode; the fluid screen shows "Ready.".
- **A held island loses its unsolved interval** when an event fenced on it applies: online time between its committed tick and the fence is dropped, not simulated (`droppedHeldTicks`). This is the owner's choice for a network that cannot solve.
- **A data-range notice is not a hold.** An island past a data range keeps running; a test that waits for `HELD` on an out-of-range state waits forever (`FluidDataRangeIslandTest`).
- **A certified island's view is a preview**, not a materialisation: no clock, booking, metrics, replay listener or payload moves, so bookings and the heat in transit are the same bits with or without viewers.
- **`IslandSets` positions are not compound ids** across islands; map by id.

**Snapshot:** `d6f721b94`, 2026-11-15.
