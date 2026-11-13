# D-R1 The runtime's small structures (R14, R19, R21): report

Batch D-R1 of `WAVE_D_BRIEFS.md` (subagent, Opus high). Branch `worktree-agent-ab5ca36a6287f0834`, fast-forwarded to `claude/codebase-review-refactor-8a85ef` at `d33c0a18a`. Three code commits: `f23f2d143` (R14 with its test), `c90d3cdb3` (R19, comments only) and `f955da59f` (R21), then this report with the code guide. **Nothing was compiled or run** (the brief: no Gradle, no JVM). Structure only: the scheduler picks the same islands in the same order, a caller sees the same refusals, and the saved facts are the same bytes.

Summary:
- **R14 done.** `FairQueue` keeps one key per island in three collections (a key map, a ready index, the served cycle) in place of eight (four `TreeMap`s, two sets, two maps). The order and the predicate calls are the same on every sequence of operations. The review's form (an `ArrayDeque` and a rotating cycle of ready ids) is not taken: it loses a waiting island's place or walks every waiting island on each request (section 1.2).
- **R19 partly done.** The class comment now names the three job families. The owner scan stays: the service's own check does not give the same refusal on two paths (section 2). The `Family` record is not done: it is not a plain regrouping (section 2.3).
- **R21 done.** `Device.builder(...)` replaces the nine telescoping constructors at 187 call sites. The canonical constructor, its checks, the field order and every saved name stay. The facts codec keeps the canonical constructor (section 3).

## 1. R14, the scheduler's ready queue

### 1.1 The contract (from the code, its comment, `IslandSchedulerTest` and `IslandCoordinator`)

Operations, all on the coordinator's thread:
- `register(id)` = `register(id, true)`. `register(id, ready)` throws `IllegalArgumentException` for `id <= 0`. A registered id is ignored: its readiness does not change. Otherwise the id becomes a **newcomer** with the next arrival number, ready or not. The coordinator registers every island not ready (`IslandCoordinator` 380, 1013) and then states its readiness.
- `remove(id)`: forgets the id (newcomer or served). An unknown id is ignored. The service position does not move.
- `setReady(id, ready)`: an unregistered id is ignored. A registered one keeps its place: a newcomer stays a newcomer, and a served island keeps its place in the cycle.
- `size()` (registered), `readyCount()` (ready), `isReady(id)` (false for an unknown id).
- `nextReady(accept)`: throws `NullPointerException` for a null predicate. The predicate is asked only about **ready** islands (the coordinator counts each call as `islandVisits`). It is asked in this order, and the first island it accepts is served:
  1. Ready newcomers, in arrival order. The served one leaves the newcomers and joins the cycle at its back: just after the service position, so the islands not served since it was set come first. It becomes the service position.
  2. Otherwise, the ready islands of the cycle strictly after the service position, then from the cycle's start up to and including the position (the island served last comes last). The served one becomes the position.
  3. Otherwise empty. Nothing changes.

  An island the predicate refuses is passed over and stays ready. A predicate that throws leaves the queue as it was, because nothing changes before an island is accepted.
- The cycle's order is fixed. An island enters it only once, when it is first served, and leaves only by `remove`. The order among ready islands is the order a single deque would give if every island in it (ready or not) were rotated to the back when passed over (`theReadyIndexServesTheRotatingDequeOrderUnderArbitraryChurn`).
- Renumbering (internal): positions are ordinal keys with gaps of 2^20. Once the gap after the position is exhausted, the cycle is keyed again from the position (`renumber`). The circular order and the position stay the same, so nothing observable changes (`reKeyingAnExhaustedGapKeepsTheServiceOrder`).
- Cost (class comment, and the certified and idle island gates of `IslandSchedulerTest`): a request touches only ready islands. A waiting island costs nothing per request.

### 1.2 The new structure

- `keyOf`: every registered island's key. A newcomer's key is `Long.MIN_VALUE + arrival` (below zero, in arrival order). A served island's key is above zero, in cycle order.
- `ready`: one `TreeMap` of the ready islands by key. Its part below zero is the ready newcomers. Its part above zero is the ready islands of the cycle.
- `cycle`: every served island by key, ready or not. It keeps a waiting island's place, and `positionAtBack` and `renumber` need it.
- `positionAtBack` is unchanged. `renumber` keys the cycle again as before. It takes readiness from `ready`'s part above zero (it clears and refills only that part) and leaves the newcomers' keys alone.
- Gone: the `registered` and `ready` sets (now `keyOf` and `ready`), `arrival` (`keyOf`), `newcomers` (never read, only written) and `readyNewcomers` with `readyCycle` (now the two halves of `ready`).
- Each operation looks up the same keys in the same order as before.
- `IslandScheduler.java`: 130 to 135 lines. The queue's class body is 58 lines in place of 60, three of them new field comments. Its class comment is 22 lines in place of 15: it states the contract above briefly. The review's estimate of about −40 lines assumed the deque form.

**Why not the review's form.** If the cycle held only ready ids, an island that became ready again would lose its place: the order needs the full cycle. If the deque held every served island (ready or not), as the earlier queue did, each request would walk every waiting island. The predicate counter would not show it, but the certified and idle island gates (`IslandSchedulerTest`) are there to guard against that cost. The key index keeps the cost and the order with three collections.

### 1.3 The equality test

`src/test/java/com/wormzjl/icheme/runtime/fluid/FairQueueEquivalenceTest.java`. The earlier `FairQueue` is copied into it verbatim from `d33c0a18a`, as the private nested class `EarlierFairQueue`. Only its header changed (name and `private`). A comment says what it is. The class was assembled by a node script from `git show`, and a `diff` against the source lines found no differences.

- `theQueueServesWhatItsEarlierFormServedOnEverySequenceOfOperations`: 24 seeds (`SplittableRandom`, fixed seeds), 5,000 operations each.
  - Id ranges: 2 to 7, 8 to 31 and 32 to 64, a third of the seeds each.
  - Operations:
    - `register` with one argument and with two;
    - `register` of an invalid id (0, -1, -2);
    - `remove`;
    - `setReady` (unregistered ids included);
    - a new veto set;
    - `nextReady` with the veto, with every island accepted, with a predicate that throws at its first to fourth call, and with null;
    - a burst: up to the whole range removed, registered again ready and served in a row. The burst forces renumberings.
  - After every operation, both queues are compared: the outcome (the island served, or the exception's class), the list of islands the predicate was asked about, in order, `size`, `readyCount`, and `isReady` for every id from -1 to the range + 1.
  - Each seed ends with a drain of three times the range, compared the same way.
  - Floors on the paths covered, read from the earlier queue's own fields: more than 20,000 served, 5,000 from the cycle, 2,000 newcomers, 500 wrap-arounds, 100 failing predicates, and at least one renumbering.
- `aRenumberingMidCycleKeepsTheOrder`: the scenario of `IslandSchedulerTest.reKeyingAnExhaustedGapKeepsTheServiceOrder`, compared call by call against the earlier queue, with at least one renumbering asserted.
- **Offline check (not a substitute for the run):** `tmp/d-r1/fairqueue-sim.mjs` (gitignored) ports both queues to JavaScript, with BigInt keys and sorted-array maps. It runs a sequence mix like the test's: 24 seeds, 5,000 operations each.
  - Every pick, every predicate call and every observable were equal.
  - Counts: 123,470 served, 900 renumberings, 1,440 failing predicates, 12,899 wrap-arounds. The test's floors are well below these.
  - This checks the algorithm, not the Java. The Java test is the proof once it runs.

## 2. R19, `ProcessSolveServices`

### 2.1 The owner scan cannot go

`submitFluidIsland` checks three things, in this order:
1. Its scan: whether any request context holds the target. If so, it returns `OWNER_BUSY` with the diagnostics read just before.
2. `activeWorkers >= workerCount`: it returns `QUEUE_FULL`.
3. `submit`. This throws on a duplicate request id. It then calls `allocateWorkers` (`allocator.observe(demand, tick)` and `setWorkerLimit`) and only then `service.trySubmit`. `trySubmit` checks `STOPPING`, then `STALE_EPOCH`, then its own `OWNER_BUSY` (`jobsByOwner.containsKey`).

The two owner sets are equal: a context is added only when a job is `ACCEPTED`, which puts the owner in `jobsByOwner`, and both leave together at the drain (or at `stopServer`, which clears the contexts after the drain). So the scan and the service's check name the same busy islands. Their outcome for the caller still differs:

| Case | With the scan | Without it |
|---|---|---|
| Busy island, every worker active | `OWNER_BUSY` | `QUEUE_FULL` (check 2 comes first) |
| Busy island, a worker free | `OWNER_BUSY`; `allocateWorkers` not called | `OWNER_BUSY` from the service; `allocateWorkers` first called with an incoming 0 or 1. This moves the allocator's demand window and shrink time (`fluidDemandShrinkDelay`, the worker limit) |
| Busy island, a reused request id | `OWNER_BUSY` | `IllegalStateException` ("Duplicate process-solve request sequence") |

- `MinecraftFluidRuntime` reads only `== ACCEPTED`, so the first row's name does not reach the coordinator.
- The second row changes the worker allocation: a result.
- The public `AdmissionResult` names the refusal, and `FluidCompletionBudgetGameTests` reads `accepted()`.

The scan stays, as the brief says for a case that differs. Its comment now says why it stands where it does.

A form that keeps every outcome and drops the linear walk: an O(1) owner query on `BoundedCpuSolveService` (`jobsByOwner.containsKey`), called at the scan's place. That class is outside this lane, so it is offered here, not done.

### 2.2 The comments

- The class comment named "V3 jobs". It now names the three families: a fluid island's slice, a running column unit's solve and a batch still's column solve.
- `nextRequestId` said it serves "calculator requests" and is allocated by "the network admission boundary". It is allocated by the fluid dispatcher (`MinecraftFluidRuntime`) and by the column and still admissions (`FluidWorldAuthority`), all on the server thread.
- `submitFluidIsland` ("share ... with V3") and `fluidWorkerDemand` ("other calculator jobs") now name the column and still jobs.
- `submitColumnUnit`'s "a V3 job" stays: a running column's solve is the V3 column solver (`ColumnSolve` imports `science.column.v3`).

### 2.3 One `Family` record per job type: not done

The five sealed lists (`ProcessSolveCommand`, `ProcessSolveResult`, `SolveTarget`, `ProcessSolveRequest`, `ProcessSolveCompletion`) are types. Two exhaustive pattern switches, `network/ProcessSolveCoordinator.route` and `routeAbandoned`, rely on them, and so do `FluidContentionProbe` (GameTest source) and the `drain` decode. A `Family` record in their place would turn these compile-time checks into a runtime table. It would also edit the router, which is outside this lane. That is not a plain regrouping.

The decode alone could move into each request record (a `complete(...)` method per family, in place of `drain`'s `instanceof` chain), but the brief asks for the whole record or nothing.

## 3. R21, `PhysicalFluidTopology.Device`

### 3.1 The constructors and their call sites

The record has 16 components. Besides the canonical constructor it had nine telescoping ones, one for each prefix of the canonical order. There was none of arity 12, because `slots` and `points` came together. Each filled the rest with a default:

| Arity | Defaults it filled | Call sites (main, test, GameTest, campaign harness) |
|---|---|---|
| 6 | `ports` `List.of()` and every default below | 91 |
| 7 | `generatorEnabled` true | 20 |
| 8 | `cutoff` `NONE` | 3 |
| 9 | `inlineFilter` false | 1 |
| 10 | `joints` `ALL_FACES` | 7 |
| 11 | `slots` 0, `points` 0 | 15 |
| 13 | `heat` null | 5 |
| 14 | `suspendedBy` 0 | 38 |
| 15 | `held` false | 7 |
| **total** | | **187**: main 26, test 154, GameTest 3, the two campaign harnesses 4 |

There were 12 canonical calls before: the 11 copy methods (`withGeometry` ... `withHeld`) and none elsewhere. They stay. The builder's `build()` is a 13th.

### 3.2 The builder, and why a builder

`Device.builder(id, position, kind, facing, geometry, control)` takes the six fields every call states. It has one setter per other field: `ports`, `generatorEnabled`, `cutoff`, `inlineFilter`, `joints`, `slots`, `points`, `heat`, `suspendedBy`, `held`. `build()` calls the canonical constructor once, so every rule of the compact constructor holds as before. A device is checked whole, never field by field.

Named factories per kind were set aside:
- `kind` is a variable at many sites (the registry's edits, `place`, the tests' `pipe(kind)` helpers).
- One kind takes several shapes (a `VOID` is a plain void, a burner, a flare, a chimney, a fuel void or a converter drain, told apart by `heat`, `control` and `ports`).
- Factories would split those rules across names, or need as many overloads as before.

With the builder, a call states only what differs from the defaults, by name. It is not a string of positional `true, Cutoff.NONE, false, ALL_FACES, 0, 0, null, 0`.

The rewrite: `tmp/d-r1/devices.mjs` (gitignored).
- It parses each call's arguments, string, character and comment aware. Each arity maps to its fields by position.
- It keeps the first six arguments' text as written.
- It writes a setter only for an argument whose text is not exactly that field's default literal: `List.of()`, `true`, `Cutoff.NONE` (any qualifier), `false`, `ALL_FACES` or `63`, `0`, `0L`, `null`. Dropping such a literal gives the same value as before.
- A setter argument that started on a new line keeps its line break.
- No call had a comment among its arguments: no comment was lost (checked over the removed lines). The one call with a `<` (FluidWorldAuthority, the still's port loop) is the ternary `k<2?`, read by hand.
- After the rewrite, the script finds no call of a removed arity in `src` or in the two campaign harnesses.

The facts codec (`FluidFacts.registration`, line 453) called the 15-argument constructor. It now calls the canonical one with `held` false, the value the 15-argument one gave: the same object, built the same way, from the same reads in the same order. Nothing about how the codec reads or writes a `Device` changes. The record's components, order and names are unchanged. `FluidFactsCompatTest` and the corpus decide it.

No call site was in `world/**` or `science/equipment/**`: they all reach the record through `FluidWorldAuthority`, `PhysicalRegistry` and the tests. Edits outside the three lane files are in the constructor call expressions only. Where the dropped defaults stood on their own line, the call became one line shorter: 11 calls in `FluidWorldAuthority`, and calls in 10 test files.

### 3.3 Left as it is

- Under `tools/`, 62 archived probe sources still hold 93 calls of the removed arities. None is compiled: `build.gradle` attaches only `tools/development.gradle` (`tools/neural`, which has no such call), and `lane.sh` compiles only `tools/unified-thermo-port-forms/hold-fuzz-{cold,bundled}/src`. Those two harnesses were rewritten (2 calls each), because the campaign runs them when `runtime/**` changes.
- The archives already call other removed APIs (some still use `createcheme` packages), so this commit does not touch them.

## 4. Tests for the main thread

Files changed since the last pass: `IslandScheduler.java`, `PhysicalFluidTopology.java`, `FluidWorldAuthority.java`, `PhysicalRegistry.java`, `FluidFacts.java`, `ProcessSolveServices.java` (comments only), 59 test sources (the new `FairQueueEquivalenceTest` and 58 edited by R21), 3 GameTest sources and the 2 campaign harnesses.

| Task | Classes | What it proves |
|---|---|---|
| `compileJava`, `compileTestJava`, `compileFluidGameTestJava` (`test` depends on the last) | all | The builder and the 187 rewritten sites compile; no removed constructor is left |
| `test --tests` | `FairQueueEquivalenceTest` (new) | R14: the same picks, predicate calls and observables as the earlier queue |
| `holdGates --tests` | `IslandSchedulerTest` (a slow gate class in `build.gradle`) | R14: the queue's own tests and the cost gates (certified and idle islands), unchanged |
| `test --tests` | `FluidFactsCompatTest` | R21: the corpus under `src/test/resources/compat/` reads and re-encodes as before |
| `test --tests` | `PhysicalFluidTopologyTest`, `HeatSideTopologyTest`, `WorldTopologyLedgerTest`, `PhysicalRegistryTest`, `PipelineFittingTest`, `ColumnRunTest`, `StillRuntimeTest`, `FluidCheckpointCodecTest`, `FluidCheckpointStoreTest`, `FluidPacketCodecTest`, `IslandCertificateTest`, `ExchangerCouplingTest`, `MachineHealthTest`, and every other edited test class (the 55 classes with tests among the 58 files of `f955da59f` under `src/test`; the list is `git diff --name-only f955da59f~1 f955da59f -- src/test`; `ExtremeTopologyIslandTest` is a slow gate: `holdGates`) | R21: devices built by the builder are the devices the constructors built |
| `test --tests` | the users of the edited helpers `EventPathWorld`, `BlockLineHarness`, `CertifyingRig` (as `affected.mjs` lists them) | R21 through the shared rigs |
| `test --tests` | `LayeringTest`, `FormatsTableTest` | No new dependency across layers; the formats table is unchanged |
| `test --tests` | `BoundedCpuSolveServiceTest`, `ProcessSolveServicesRequestTest` | R19 changed comments only: by AGENTS.md no run is needed. They are listed because the brief names them |
| final lane | the fluid GameTests, the campaign (its inputs include `runtime/**` and the two harnesses) | The GameTest sources and harnesses compile and run the same |

## 5. Points not verified (compile risks)

- `IslandScheduler.java` 110: `long arrival=entry.getKey(),island=entry.getValue();` unboxes both values. Line 111 `ready.remove(arrival)` boxes `long` to `Long` for `remove(Object)`: it should compile and remove the newcomer's entry.
- `IslandScheduler.java` 116: `ready.subMap(0L,false,last,true)`. `last` is never below 0 (it starts at 0 and only takes cycle keys, which are above zero), so `fromKey > toKey` never throws. The JS port exercised this.
- `FairQueueEquivalenceTest`: the test reads the private fields `cycle`, `last` and `readyNewcomers` of its nested `EarlierFairQueue`. This is allowed between nestmates. The count floors (section 1.3) are lower bounds taken from the JS port's counts. If one fails, it is the floor, not the equality.
- `PhysicalFluidTopology.java` 106 to 125: a static nested class `Builder` inside the record, with fields named like the components. This is legal. The setter names shadow nothing outside `Builder`.
- R21 call sites: each telescoping arity is a prefix of the canonical order, so the setter for each argument has the constructor parameter's type (`int joints`, `int slots`, `long points`, `long suspendedBy`). No argument type changes. A site that passed `(int)joints` keeps the cast in its setter.
- The two campaign harnesses compile only in the lane's campaign step, not in `compileTestJava`.
