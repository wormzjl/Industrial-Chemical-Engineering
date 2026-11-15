# R-ST3 report: the still's last readings that depend on the machine

Subagent (Opus high), 2026-11-14, worktree branch `worktree-agent-ad86b6da0b9268a55` from `claude/codebase-review-refactor-8a85ef` at `e056e149b`. Brief: [WAVE_D_BRIEFS.md](../WAVE_D_BRIEFS.md), "R-ST3"; the pattern: [R-ST1_REPORT.md](R-ST1_REPORT.md), [R-ST2_REPORT.md](R-ST2_REPORT.md). Code written and read only: **nothing was compiled or run**. F = `FENCE_TICKS` = 440.

## 1. Item 1, diagnosis (written before any code)

**Expected.** A still's island reads its partners as of its slice start − F. What a certified partner books for a span must be dated by a tick that the world's ticks fix, so two machines read the same values and book the same amounts and energies.

**(a) The tick a certified still island materialises at.** Two paths, read in the code at `e056e149b`:
- **A still's wake.** `Stills.decide` polls `wakes` for keys ≤ now and calls `coordinator.controlsMoved`; that only adds the island to `toWake`. `ExchangerCoupling.flushWakes` runs right after `ports.decide()` (in `runDue`, `pump`, `closeRound`). It calls `Certificates.materialise`, whose target is `min(online, fence, hold tick, horizon)`, with online = the epoch's now. Then it ends the certificate, and the island solves again from that tick. `runDue` runs every tick. So a wake that was registered before its tick w materialises at exactly w: the event's own tick, deterministic. A wake registered after w materialises at the online tick of the first decision point after the registration. That is the drain tick of a worker's completion, so it depends on the machine. Two cases: a column answer that completes after its apply tick (pot commit latency + solve latency > `APPLY_TICKS` 100), and an owe or a fire booked by a commit that lands more than F after its span's end.
- **A still's read.** `Stills.ready` → `through` → `IslandCoordinator.through` (lines 529-533) materialises a certified partner whose committed tick is behind the fence, again to the online now. Now is the tick of the pump that evaluates the reader's readiness. On a quiet machine that is the reader's slice end. It is later when one of these holds: the reader's previous commit landed after its slice end; an earlier test in `ready` waited (the pot's pending answer, another partner's commit) and released at a drain; no worker was free (`pump` returns before `nextReady`). So this tick depends on the machine, whatever the event.

**(b) What is dated with it.** `Certificates.materialise` books the replayed span [c, t) through `ExchangerCoupling.booked` → `Stills.booked(island, graph, c, t, replay)`:
- The pot: arrivals (`Arrival(t, …)`, the outlets' accounts), an energy-only owe dated t (+ `wakeAt(t + F, water)`), its `Heat` entry dated c (values unchanged: no dispatch ran), a column job if the fire is asked (`submit(…, c, t)`: apply t + 100, `lastSubmit` = t) with its share dated t.
- An outlet: its gift's owe dated t (+ the water's wake at t + F).
- The burners: a `Fire(t, …)` entry (+ the pot's wake at t + F on a lit or out change).
- The water: `taken`, `since` (not dated).
- The coordinator: each replayed span's boundaries are scaled by (t − c)/D, so the ledger's sums depend on where the spans split.
- The island solves again from t when a wake ends its certificate.

Later reads: an outlet gives the arrivals dated ≤ its fence. The water's `due` takes the owes dated ≤ its fence. The pot takes the fire in force at its fence. The answer of a job asked at t applies at t + 100 (`solvedAt`), and the next job waits for `start ≥ lastSubmit + 100`.

**(c) Can two machines book differently? Yes.** Worked example: the pentane rig of `StillRuntimeTest` with certificates on (defaults) and the water island offset by 10 ticks.
- The water flows with no heat yet and certifies at its committed tick c_W, say 210.
- The pot's slice [700, 800) reads the water as of 260 > 210.
- Machine A dispatches it at 800, and `through` materialises the water over [210, 800).
- On machine B the pot's previous job took 130 ticks (its commit of [600, 700) lands at 830). The slice is dispatched at 830, and the water is materialised over [210, 830).
- The water's replayed boundaries now split at 800 or at 830. Its source and sink totals (amounts and energies on the ledger) differ in their last bits, and its later spans start at different ticks.

A material case is a certified pot whose fire is asked. A liquid still that is lighting has heat 0 for about two fences with `asked` set. A reader's `through` materialises that pot at t_A or t_B. The materialisation books a column job applying at t + 100 and sets `lastSubmit` = t. From then on the answers and the job cadence differ, so the head's accounts (facts) differ by whole answers. A certified pot whose answer completes after its apply tick (an answer at 262 for apply 200) is woken at 262 instead of 200, and its slices then run on another grid.

**Found while reading (deterministic, but a defect with certificates on, the world's default).** Nothing wakes a still's outlet island or its burners' island. An outlet with an empty account rests exactly, certifies (exact zero, horizon ∞ with the default recheck 0) and never plans again: its account fills and nothing flows. Burners idle while their pot waits for its first answer certify and never ask fuel. The fix of item 1 needs these wakes, and so does any gate run with certificates on.

## 2. Item 1, what was done (`96dfeab26`)

The fix follows R-ST1 and R-ST2: values dated by ticks, waits in `ready`, wakes by `wakeAt`. It adds two small coordinator entry points, because the still cannot fix a materialisation tick by itself.
- **The waits read the certificate's reach.** `Stills.through` calls the new `IslandCoordinator.reached(id, tick)`. That is `through` without the materialisation: true where the certified island's `min(online, fence, hold tick, horizon) ≥ tick`, the reach `ExchangerCoupling.coupledThrough` already uses. Why this is exact for a still: a certified still island rests. A replay books no arrival, owe or fire that a fenced read takes, and its `Heat` entries carry unchanged values (no dispatch ran). A drift certificate (steady burners, steady water at no heat) books the same values its last solved slice logged. Every value a still island plans from now wakes it (below). So a still's read never materialises anything. A certified still island is materialised only at its wakes, its horizon, a topology fence or a save: ticks the world fixes. The waits themselves are unchanged: `reached` is true exactly where `through` was true after its materialisation.
- **A wake acts as of its own tick.** `Stills.decide` calls the new `IslandCoordinator.movedAt(island, tick)`, which returns whether the wake is settled:
  - Certified and committed at or before the tick: `Certificates.materialise(island, cap)` replays to the tick, not to now, and `Certificates.end` (the end that `flushWakes` used, moved there) ends the certificate there.
  - Certified past the tick: the certificate stands if the interval it replays started at or after the tick, and ends where it stands if not.
  - Awake behind the tick: the wake is kept until the island gets there (it may certify on the way).
  - Awake through the tick, held, parked, capability-held, suspended or gone: settled.

  Each outcome depends on the island's own committed ticks, not on when the call runs.
- **Wakes for every planned-from value**, each a fence after the change (the first slice that reads it):
  - The outlets, at an arrival (`arrive`), an answer (`completed`, apply + F) and a share change (`condensed`).
  - The burners, at an answer (apply + F) and a cut set or lifted (the pot commit's `Heat` cut bits differ from the last entry's: start + F + 1, since `heatAt` takes entries strictly before the fence).
  - Kept from R-ST1 and R-ST2: the water at an owe, the pot at an apply tick and at a fire lit or out.

  Without these, a certified idle outlet or burners' island rested for good with certificates on (section 1).
- Status: `Certificates.end` keeps `flushWakes`' line ("WAITING: an exchanger's other side changed"). No new text.

## 3. Item 2: a job refused by capacity (`109cdf6b1`)

**Expected.** The answer a pot applies at tick A is the job its commit at A − 100 asked, on any machine.
**What the code did.** `submit` returned when `solves.submit` was false: no pending flag, and `lastSubmit` kept. The next commit (100 ticks later) asked a job of its own pot, applying 100 ticks later. A loaded machine therefore applied another answer, later.
**What was done.** `submit` marks the job pending (`pending`, `applyTick`, `lastSubmit`) before the submission, which is also right for a solver that answers at once. If the service refuses it, the job is held (`Run.heldJob`, the pot in `held`). `decide` asks again (`resend`) at each decision point (every tick's `runDue`, each pump and each round close) until the job is taken. The retry keeps the same job and the same apply tick (`completed(pot, apply, …)`). The pot's slice that needs the answer waits in `ready` (`pending && applyTick ≤ start`), and so do the fenced readers (`fenced`), as for a late answer.
- **No deadlock.** The service's room is freed by the completions of the jobs it already holds (islands, columns, stills). None of them waits for this pot's island. The new wait is the existing answer wait and depends on the service, not on an island, so it adds no edge to section 5's graph.
- **What a player sees.** The still keeps its last status line. Its pot's island holds its committed tick while it waits (flows and level freeze). No existing text fits "waiting for the solver" for a still, so the status is left as it is.
- **Risk.** A refusal that never ends now holds the pot's island, which before skipped the job. Two cases: a stopped service, and the world's lambda returning false when `registrations().get(pot)` is null (`FluidWorldAuthority.java:176`).

## 4. What moves (estimates from the code; nothing was run)

| Case | Old | New |
|---|---|---|
| Certificates off (every rig before this one) | — | nothing: `reached` ≡ `through`, `movedAt` settles at once, the new wakes find no certificate |
| Certified idle outlet (world default) | never gives again (account grows) | gives from a fence (440 ticks) after the arrival that filled it |
| Certified idle burners waiting for the first answer | never ask fuel | ask from apply + 440: the fire reaches the pot about two fences after the answer (R-ST2's figure) |
| A certified partner read by a still | replayed to each reader's look (e.g. [210, 800) or [210, 830)) | replayed at its wakes only: one span [c, w), same amounts to the last bits, dated w |
| A wake acted on late (an answer at 262 for apply 200) | island solves from 262 | from 200 |
| A refused job (the gate: refused 1950–2229) | skipped; a later commit's job (+100 to +300 ticks, another pot state) | the same job applies at its tick; the pot's slice from 2100 waits 30 ticks (dispatched at 2230, not 2200) |

`RESPONSE_TICKS` stays 1280: no lag of an awake still changes. Conservation is exact: no booking changed, only when replays are booked and which job is asked.

## 5. The waits

R-ST1's and R-ST2's edges (W→P, W→D, W→V, D→P, V→P, P→W, P→B, B→P, each with lag F) are unchanged. `reached` only removes a materialisation. The held job's wait is the answer wait. A kept wake is a map entry, not a wait. So no cycle is added.

## 6. Saves

No format change. `Run` is memory only. `heldJob` and the kept wakes are state: after a load the pot asks its first job at its first commit, as before. Certificates and their replay are unchanged (`REPLAY_REVISION` stays `replay-1`).

## 7. Gates (in `StillRuntimeTest`; neither has a switch)

- `aCertifiedStillIslandIsMaterialisedAtTicksThatDoNotDependOnWhenItsIslandsJobsComplete`. The pentane rig, certificates on (defaults), the water offset by 10. It runs until the water certifies at c. Then s = the first pot slice start with s − 440 > c. In run b the pot's job of the slice ending at s takes 130 ticks once. Both runs stop at 4820 (or later in steps of 100). The gate compares the replayed spans (the rig records each), then everything R-ST1's gate compares, plus `exchanged`.
  - **Today it fails** on the spans: the pot's `ready` → `IslandCoordinator.through` materialises the water to [c, s + 100) in run a and [c, s + 130) in run b.
  - It also fails on "the distillate flows": the certified outlets never wake.
- `aColumnJobTheSolveServiceRefusesIsAskedAgainForTheSameSlice`. The pentane rig; in run b the service refuses still jobs from tick 1950 to 2229, which holds one job's commit. It compares the jobs by content (`Rig.asked`: the pot's moles, temperature, reflux, outlet), the count, and every booking.
  - **Today it fails** on `asked`: the job at 2000 (or 2100) is skipped, and the next one, at 2300, is of another pot.

## 8. Tests for the main thread (`test` group, filtered)

`./gradlew test --tests 'com.wormzjl.icheme.runtime.fluid.StillRuntimeTest' --tests 'com.wormzjl.icheme.runtime.fluid.ColumnRunTest' --tests 'com.wormzjl.icheme.runtime.fluid.ConvertersTest' --tests 'com.wormzjl.icheme.runtime.fluid.IslandSchedulerTest' --tests 'com.wormzjl.icheme.runtime.fluid.RestIslandTest' --tests 'com.wormzjl.icheme.runtime.fluid.ExchangerCouplingTest' --tests 'com.wormzjl.icheme.runtime.fluid.ShaftCouplingTest' --tests 'com.wormzjl.icheme.runtime.fluid.IslandCertificateTest' --tests 'com.wormzjl.icheme.runtime.fluid.RestGaugeTest' --tests 'com.wormzjl.icheme.runtime.fluid.RestResolutionWorldTest' --tests 'com.wormzjl.icheme.runtime.fluid.WorkerTrajectoryEquivalenceTest' --tests 'com.wormzjl.icheme.runtime.fluid.FluidCheckpointStoreTest' --tests 'com.wormzjl.icheme.LayeringTest'`, then `./gradlew compileFluidGameTestJava`.
- `StillRuntimeTest`: the two new gates, R-ST1's and R-ST2's gates, and the old asserts.
- `ExchangerCouplingTest`, `ShaftCouplingTest`, `Rest*`, `IslandCertificateTest`: the `flushWakes` end moved into `Certificates.end` (same statements, same count).
- `ColumnRunTest`, `ConvertersTest`: they share the ports and `Ports.both`.
- The rest: from the brief.
- The fluid GameTests run in the final lane.

## 9. Not verified without a compile or a run

- `Stills.java:639`: the `removeIf` lambda captures the enhanced-for `long tick`, and `wakes.remove(tick)` boxes it. `Stills.java:675`, `resend`: a lambda inside a for over `List.copyOf(held)`.
- `IslandCoordinator.java:568`: `certificate.interval().startTick()`.
- `StillRuntimeTest.java:193`: a lambda as `Publisher`. `:220`: `List.<Object>of`. `:736`: `observe(id).certificate()`.
- **Running.** The first gate assumes the water island certifies before tick 2000: steady flow and exact stationarity within 1e-7 over two intervals. If it never does, the gate fails on that assert, not on the fix. The second gate assumes one job in the window, by R-ST2's cadence. Bit equality rests, as in R-ST1, on the commits keeping their order: in gate 2 the delayed pot commit (2230) only carries the pot's own bookings.

## 10. The same class elsewhere, not fixed

- `ColumnOperation.portsThrough` (`ColumnOperation.java:640`, `:643`) waits with `IslandCoordinator.through`. It materialises a certified port or utility island to the moment `due()` runs, which is a later decision point when a port island committed late. `ColumnOperation.request` (`:586`) drops a solve the service refuses (`r.pending=null; return`), so a cycle's solve depends on load. Both are reported here, not fixed (brief).
- `ExchangerCoupling.reads` (`:166`) materialises a certified other side at the reader's dispatch, and `flushWakes` materialises the coupling's own wakes (decision D4, registered at a partner's commit) to now. Both are generic coordinator materialisations at timing-dependent ticks. They reach a still only through an island it shares with other exchangers.
