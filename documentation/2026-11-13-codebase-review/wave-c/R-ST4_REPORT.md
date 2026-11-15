# R-ST4 report: the column's and the exchanger coupling's readings that depend on the machine

Subagent (Opus high), 2026-11-15, worktree branch `worktree-agent-af39089e07f0e34cd` from `claude/codebase-review-refactor-8a85ef` at `d6f721b94`. Brief: [WAVE_D_BRIEFS.md](../WAVE_D_BRIEFS.md), "R-ST4"; the forms: [R-ST3_REPORT.md](R-ST3_REPORT.md), [R-ST1_REPORT.md](R-ST1_REPORT.md). Code written and read only: **nothing was compiled or run**. F = `FENCE_TICKS` = 440; a cycle is 100 ticks; the ticks in the examples are examples.

**Expected behaviour (all four items).** A replay of a certified island books its certified rates over a span of ticks. The span's ends must be ticks that the world fixes (an event's tick, a cycle's tick, a slice start, a committed end), never the online tick at which a worker's completion is drained. Then two machines book the same amounts and energies, and the same spans.

## 1. Diagnoses (written before any code)

### Item 1: `ColumnOperation.portsThrough`
- **The tick.** `due()` runs the cycle at T while `portsThrough(r, T)` holds. It calls `IslandCoordinator.through(owner, t)`, with t = T for an inlet and T − F for an outlet or a utility side. For a certified island that is behind t, `through` materialises it to `min(online, fence, hold tick, horizon)`, where online = now. `due()` runs in three places:
  - the COLUMN_CYCLE deadline at T, where now = T;
  - the decision point after a port island's booking while a cycle is due (`decide`, `bookedWhileDue`);
  - a solve's completion (`done`).

  At the deadline an awake inlet is never through T: its slice that ends at e ≥ T commits at e plus the worker's latency. So the loop returns false at the first awake inlet and runs again at the decision point after that inlet's commit. A certified station listed after that inlet, and every certified utility side (they are checked after the stations), is then materialised to e + latency. **That tick depends on the machine.** A certified island that is checked before every awake inlet is materialised at the deadline (deterministic).
- **The column's wakes.** They are a second dependence. `levels` and `owe` date them at T + F, and `due` fires them at their tick. `portsMoved` then lets `flushWakes` materialise to now = the wake tick, which is deterministic. But the wake ends a certificate only if the island is certified at that moment, and is dropped for an island that is awake. An island whose certifying commit lands a few ticks late therefore certifies on an interval that read the old plan, and rests on that plan for a cycle. Whether that happens follows the latency.
- **What is dated with the tick.** The replay [c, t) books through `ColumnOperation.booked`:
  - an inlet's arrivals as `Span(c, t)`, which the cycle takes up to T;
  - an outlet's `Gift(c, t)` and its debit; where the water takes the booking difference, an owe **dated t** into the condenser's transit, and a wake of the water sides at t + F;
  - a utility side's heat leaves its transit;
  - the coordinator scales the replayed boundaries by (t − c)/D.

  The island's committed tick becomes t, so the cycle that materialises it next is also another one.
- **Can two runs book differently? Yes.** Take the condenser rig with an air-cooled condenser (water share 0).
  - The water island W carries steady water with no heat. It certifies at c_W = 900, and nothing wakes it (no owe).
  - The cycle at T = 1400 needs W through 960.
  - The feed island's slice [1350, 1450) commits at 1450 on machine A, or at 1480 on machine B (a 30-tick job).
  - W is replayed over [900, 1450) on A and over [900, 1480) on B. Its source and sink amounts and energies split there. Its next replay starts from another tick: the first cycle with T′ − F > 1450 or 1480.

  With a certified giving outlet instead, the owe lump dated 1450 or 1480 falls on different sides of the water's fence 1460. That water slice then takes a whole lump more on one machine.

### Item 2: `ColumnOperation.request`
- **The tick.** No island is materialised here. A solve asked at cycle T is skipped (`r.pending = null`) when `solves.submit` returns false (the service's queue is full). The next one is asked at the next triggering cycle, ≥ T + 100, and applies 200 ticks after that. Whether the queue is full follows the machine's load.
- **What is dated.** The table that applies, `tableTick`, `applied`, and every cycle on it: the accounts and the totals (facts).
- **Can two runs book differently? Yes.** START is refused at the first cycle, 140.
  - A applies the table at 340.
  - B asks again at 240 and applies at 440.
  - B's feed starts 100 ticks later: 2.5 mol less feed in the rig (0.5 mol/s for 5 s), less product and less condenser duty.

### Item 3: `ExchangerCoupling.reads`
- **The tick.** `reads` runs at the island's dispatch in `pump`. It materialises a certified other side whose committed tick is behind the slice start to `min(online, …)`, the dispatch tick. That tick is the slice end on a quiet machine. It is later when:
  - the island's previous commit landed late (latency > cadence);
  - it waited at the coupling fence for an awake side;
  - it waited at a port's `ready`;
  - it waited for a free worker.

  So it depends on the machine. "A pure read: nothing is materialised here" is the comment of `coupledThrough` (the fence test), and it is true there: `coupledThrough` only compares ticks. The comment of `reads` ("materialised first") is also true. They describe two different methods.
- **What is dated.** The other side's replay [c, t):
  - its heat leaves the transit, and it keeps a `Booked(c, t)` span;
  - its `SideRecord` is dated t;
  - its ports' and shafts' bookings are dated (c, t): for a column's port, the arrivals, gifts and owes at t;
  - its committed tick becomes t.

  The reader's `due = transit + bookedPast(start)` is the same for any t ≥ start in exact arithmetic, but not in its last bits.
- **Can two runs book differently? Yes.** H's slice [1100, 1200) commits at 1200 on A, or at 1330 on B (a 130-tick job). H's next slice is then dispatched at 1300 on A and 1330 on B. The certified C (committed 1100) is replayed over [1100, 1300) or [1100, 1330). Its amounts, energies and later spans split differently, and H's read and solve differ in their last bits.

### Item 4: the coupling's wakes (`flushWakes`)
- **The tick.** In `booked`, a partner's committed or replayed span [s, e) whose record moved past decision D4 adds the certified other side to `toWake`. `flushWakes` runs at the decision point that follows (the close of that round, a pump, a tick's deadlines). It materialises the side to now, the commit's landing tick e + latency, and ends its certificate. **Machine dependent.**
- **What is dated.** The replay [c, now), and the island's whole slice grid after it: it solves from now, so every later boundary, commit, read and booking is at now + k·cadence.
- **Can two runs book differently? Yes, materially.**
  - H commits [1100, 1200) at 1200 on A, or at 1207 on B (every job 7 ticks slower).
  - C solves [1200, 1300), [1300, 1400), … on A, and [1207, 1307), … on B, for good. Its heat, its outlet, the transit and everything downstream differ.
  - With two partners, the order in which their commits land decides which of their ends C solves from.

## 2. What was done (R-ST3's forms; one commit per fix, the gate with it)

| Item | Commit | Change |
|---|---|---|
| 1 | `1a7030bfb` | `IslandCoordinator.through` materialises to the tick asked (`Certificates.materialise(island, tick)`). `ColumnOperation`'s port wakes act as of their tick (`movedAt`), first in `due` and at every `decide`; a wake for an island behind its tick is kept. `schedule` takes the next wake after now (`higherKey`). |
| 2 | `a3bd7fc8c`, `e2e46c06d` | `request` keeps a refused solve pending (`Run.heldJob`, the unit in `refusedSolves`) and sets `lastSolve`; `decide` asks the same job again (`resend`) until it is taken. The cycle at its apply tick waits in `applied`, as it waits for a slow solve. The second commit renames the set: `request` has a local `held`. |
| 3 | `440664a0d` | `ExchangerCoupling.reads` materialises a certified other side to the slice start. The fence (`coupledThrough`) already let it through only that far. |
| 4 | `57d523a97`, `26c8991f4` (comment) | D4 dates its wake by the end of the partner's span that moved (`ExchangerCoupling.dated`, by island, in tick order). `flushWakes` acts on each with `movedAt` once every partner of the woken island is `reached` through the tick. Until then the fence holds the island's partners whose start is past the tick (`wokenBefore`). Each change reconsiders the island and, one level down, its partners, so `verify` stays exact. The undated wakes (controls, shafts, ports, valves) are unchanged. |

The cycle has no saved field, so no format changes (`Run`, the wakes and `dated` are memory-only state). After a load a held solve is asked again at its next trigger, as before. Conservation stays exact: no booking changed, only the ticks at which replays end.

## 3. The waits cannot cycle, and each ends

R-ST1's argument: an island X waits on Y only while c_Y < c_X (minus a lag of 0 or F). Summing any closed chain gives c < c, so there is no cycle.
- **Item 4's fence.** X waits on certified C's dated wake w only when w < c_X. The wake waits for every partner Y of C with c_Y < w. So c_Y < c_X: strict again.
- **Ends.** Y is behind w and is not held by C (its start ≤ w). It solves to w, or is held, parked, suspended or gone (`reached` counts these). The wake then acts, and X waits on the awake C through the ordinary fence.
- **Items 1 and 3** add no wait: they only cap a materialisation.
- **The kept column wakes** are not waits: the island solves on.
- **Item 2.** The held solve waits for the service, not for an island. Its room is freed by the completions of the jobs it holds (islands, columns, stills), and none of those waits for a cycle. The port islands then wait for the cycle (lag F) as for a slow solve.
- **Risk** (as R-ST3's for the still): a refusal that never ends now holds the cycle at the solve's apply tick. That happens with a stopped service, or with the world's lambda refusing because the feed port is in no island (`FluidWorldAuthority.java:145`). The second case ends when the feed port's island forms, or when the unit stops.

## 4. What moves (estimates from the code; nothing was run)

| Case | Old | New |
|---|---|---|
| Certified island read by a column cycle | replayed to the cycle's look, every ~5 cycles | replayed to T or T − F at every cycle that needs it; same totals to the last bits |
| A port island that certifies just after its wake | rests on the old plan up to a cycle | materialised to the wake tick and solves from it |
| A refused column solve | dropped; a later cycle asks; its table applies ≥ 100 ticks later | the same solve applies at its tick; meanwhile the status is the in-flight one (`STARTING: the first solve`, or `RE-SOLVING: <trigger>; …`), no new text |
| A coupled read of a certified side | replayed to the dispatch tick (≥ the slice end) | replayed to the slice start: one cadence less replay at each read, the read's `asOf` takes the record at the start |
| A D4 wake | the woken side solves from the drain tick of the partner's completion | from the end of the partner's slice (on a synchronous rig the same tick) |
| Two partners, one behind | the wake acts at once | a partner ahead of the wake tick waits at the fence until the one behind reaches it: a delay in wall time, none in ticks |

`RESPONSE_TICKS` (the still's) is unchanged. The column's response is unchanged: no lag in ticks is added. No campaign outcome line is expected to move. The hold-fuzz harnesses (`tools/unified-thermo-port-forms/hold-fuzz-*/src/HoldFuzzTest.java`) place no exchanger, column or still, and `through`, `reads` and D4 act only there. The fluid GameTests with exchangers or columns may move in their last bits (item 3: a cadence less replay at each read).

## 5. Gates (no switch; why each fails on `d6f721b94`, by reading)

- `ColumnRunTest.aCertifiedPortIslandIsMaterialisedAtTheCyclesTickWhateverItsOtherPortsJobsTake`. The condenser rig, air-cooled (water share 0): the water island takes no heat and certifies, and nothing wakes it. Then 1000 ticks with the feed island's jobs 30 ticks slower in run b. The gate compares the replay spans per island, the totals, every boundary's moles and energy, the heat and the transits. **On `d6f721b94`** every cycle waits for the awake feed island, then `through` replays the water to the drain tick of the feed's commit: e on a, e + 30 on b. The spans differ. Premise asserts: the water certifies before 4000; it was replayed more than once.
- `ColumnRunTest.aSolveTheServiceRefusesIsAskedAgainForTheSameCycle`. Fake solver; run b refuses every job before the first cycle + 100. The gate compares the jobs by content, `tableTick`, `applied`, the totals and the bookings. **On `d6f721b94`** START is dropped and asked again at the next cycle: `tableTick` is first + 100 against first, and the feed is taken a cycle later.
- `ExchangerCouplingTest.aCertifiedOtherSideIsReadAsOfTheSliceStartWhateverItsPartnersJobsTake`. The water pair at rest; its hot side is woken by `controlsMoved` at t; run b's first hot job takes 130 ticks. The gate compares the spans, heat, transits and clocks at t + 831. **On `d6f721b94`** the second dispatch replays the cold side to t + 201 on a and to t + 231 on b. Premise assert: the cold side is still certified at t + 231 (no D4 wake).
- `ExchangerCouplingTest.aWokenCertifiedSideSolvesFromItsPartnersCommittedTickWhateverTheJobsTake`. The water pair at rest; the hot inlet steps +10 K at t; every job of run b takes 7 ticks more. **On `d6f721b94`** the D4 wake replays the cold side to t + 100 on a and t + 107 on b. The cold side then solves on two grids, so its clock at t + 2450 differs (t + 2400 against t + 2407), and so do its spans and heat.
- The rig of `ExchangerCouplingTest` gained `lag`, `lags`, `replays` and `ticks`. With none set, `drain` completes and pumps as before.
- Bit equality rests, as in R-ST1, on commits keeping their order. Each gate lags either one island alone or every job alike.

## 6. Tests for the main thread

`./gradlew test --tests 'com.wormzjl.icheme.runtime.fluid.ColumnRunTest' --tests 'com.wormzjl.icheme.runtime.fluid.ExchangerCouplingTest' --tests 'com.wormzjl.icheme.runtime.fluid.StillRuntimeTest' --tests 'com.wormzjl.icheme.runtime.fluid.ConvertersTest' --tests 'com.wormzjl.icheme.runtime.fluid.IslandSchedulerTest' --tests 'com.wormzjl.icheme.runtime.fluid.RestIslandTest' --tests 'com.wormzjl.icheme.runtime.fluid.RestResolutionWorldTest' --tests 'com.wormzjl.icheme.runtime.fluid.IslandCertificateTest' --tests 'com.wormzjl.icheme.runtime.fluid.ShaftCouplingTest' --tests 'com.wormzjl.icheme.runtime.fluid.CoolingTowerRuntimeTest' --tests 'com.wormzjl.icheme.runtime.fluid.FiredHeaterFuelTest' --tests 'com.wormzjl.icheme.runtime.fluid.SteamHeaterCoilTest' --tests 'com.wormzjl.icheme.runtime.fluid.WorkerTrajectoryEquivalenceTest' --tests 'com.wormzjl.icheme.runtime.fluid.FluidCheckpointStoreTest' --tests 'com.wormzjl.icheme.LayeringTest'` (`IslandSchedulerTest` is in `holdGates` if `test` skips it), then `compileFluidGameTestJava`.
- `ColumnRunTest` and `ExchangerCouplingTest`: the four gates, and the old asserts under the new wake and read ticks. `VERIFY` checks the fence's new condition against the incremental readiness.
- `StillRuntimeTest`, `ConvertersTest`: they share the ports and `movedAt`.
- The coupling's users (`ShaftCouplingTest`, `CoolingTowerRuntimeTest`, `FiredHeaterFuelTest`, `SteamHeaterCoilTest`) and the certificates' (`Rest*`, `IslandCertificateTest`): D4's dated wakes and the capped reads.
- The rest are from the brief. The fluid GameTests run in the final lane.

## 7. Not verified without a compile

- `ColumnOperation.java:534`: the `removeIf` lambda captures the enhanced-for `long tick` and calls `movedAt(Long owner, long)`.
- `ColumnOperation.java:627`: the lambda captures the locals `r` and `pending`.
- `ExchangerCoupling.java:210` and `:270`: `TreeSet<Long>` through `computeIfAbsent`, and `ticks.first()` unboxed in a `while` condition.
- `ColumnRunTest.java` (item 1's gate): a `spans` local is declared in the `for` block and again after it.
- `ExchangerCouplingTest.java`: the `s.get(1)==t+1` lambda compares a `Long` with a `long`.
- **Premises not run:**
  - item 1: the feed island stays awake in the window (a certified feed would be materialised at the deadline on both runs, and the gate would pass on old code);
  - item 3: the hot side's re-solve moves nothing past D4.

## 8. Code guide lines that became wrong (for the main thread)

- `heat-exchangers.md:64` (the diagram's "woken at flushWakes"), `:70` ("certified (materialised at dispatch)": now to the slice start, and not past a dated wake), `:76` (`flushWakes` now acts as of the partner's end once every partner is through it).
- `fluid-runtime.md:60` and `:205` (the same two points). `fluid-runtime.md:207`:
  - `portsMoved` is no longer the column's wake: `movedAt` is;
  - `through` materialises to the tick;
  - the wake is due at the cycle's tick plus the fence.
- `column-operation.md:93` (`through` materialises a certified island to the tick asked), `:119` (a refused solve stays pending and is asked again), `:309` (`ColumnRunTest` now has 34 tests).

## 9. Like defects seen, not fixed

- The undated wakes still materialise to now. They come from `controlsMoved`, `shaftsMoved` (`ShaftTrains`, `FiredHeaters`, `ElectricStores`, `MachineHealth`), `portsMoved` (`Converters`) and `operations`. Whether such a wake is drained at its call's tick depends on where it is called: from the server tick it is deterministic; from a commit (`decide` after `closeRound`) it is not.
- A dated wake that is kept while the island's horizon, a save or an undated wake materialises the island past the tick then ends the island where it stands (`movedAt`'s "certified further on"). That is timing-dependent in this rare overlap.
- Floating-point order: the pair's transit is a sum in commit order. Commits of two islands in different rounds land in a machine-dependent order, so its last bits can differ (R-ST1 §8). None of the gates exercises this.
