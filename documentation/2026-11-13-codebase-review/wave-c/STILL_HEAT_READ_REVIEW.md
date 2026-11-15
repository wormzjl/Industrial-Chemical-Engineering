# The still's condenser water read: does it depend on worker timing? (review of C4C finding F4)

Diagnosis subagent, 2026-11-13, branch `claude/codebase-review-refactor-8a85ef` at `8e4644144`. Code read only; nothing built or run.

**Verdict: timing-dependent.** The still's water side reads the heat in transit as it stands when its slice is dispatched. Commits of the still's pot and outlet islands land in that number at the moment their workers finish. So the heat the water takes in a slice can differ between two machines by a whole slice of condenser duty: 0, 1 or 2 times the nominal amount. Long-run energy stays conserved. The column has no such dependence, because it reads as of a fence and waits for it.

## 1. The expected behaviour

- Heat path: pot heat Q (firing × rating × heater efficiency) boils the charge. The vapour goes to the condenser, and the condenser duty goes to the cooling water. At steady boiling the condenser duty is close to Q. The reflux returns inside the still and does not count.
- Per slice: at the default cadence of 100 ticks (`IChemE.java:98`, 5 s play) and factor 4, a slice is 20 s of process time. The water should take about Q·20 s per slice. For a 20 kW pot that is 400 kJ. With the FEED valve, the flow is Q/(cp·10 K) ≈ 0.48 kg/s and the outlet rises 10 K (`HeatSides.WATER_RANGE`, `HeatSides.java:57`).
- What is certain at a slice start on any machine: only the heat booked by commits that the reading island has waited for. A partner island's commit lands when its worker finishes. Without a wait, "booked so far" means different things on different machines.
- What the fence does: a slice that starts at *s* reads data dated at or before *s* − 440 ticks (`PortAccounts.FENCE_TICKS`, `PortAccounts.java:22`; 22 s play, 88 s process at factor 4). The reader waits until every partner is through that tick. The result is a fixed lag, similar to a hold-up, and it is the same on every machine. History: `1834c0510` (C4, 2026-10-29) says "Every plan reads data dated at or before its slice start less 440 ticks, so the products do not depend on job timing". `6769f178d` (C6b) fenced the water read (`due`). `b22f33a2f` extended the fence to room, owed heat and temperature, and its message says "not what a job's completion left". The rule is restated in `code-guide/column-operation.md:91` and `:241`. Its gate is `ColumnRunTest.aColumnsProductsDoNotDependOnWhenItsIslandsJobsComplete` (`ColumnRunTest.java:538`), which delays island jobs through `EventPathWorld.lag` (`EventPathWorld.java:179`).

## 2. What the code does

All the steps below run on the server thread (`owned()`, for example `IslandCoordinator.java:1340`). The question is therefore one of order, not of races.

- **Where the transit is written.** (a) The still's `owe` calls `coordinator.owe` (`Stills.java:398`, `IslandCoordinator.java:1339-1342`). `owe` is called from `give` (`Stills.java:391`), which runs when an outlet island commits (`booked`, `Stills.java:337`). It is also called from `arrive`'s energy-only branch (`Stills.java:374`), which runs when the pot island commits. (b) The water side's own heat is subtracted when the water island commits (`IslandCoordinator.java:1245`). Every commit happens in `closeRound` (`IslandCoordinator.java:753`). A round closes when all its entries are terminal (`:670`), and an entry becomes terminal when its worker's completion is drained (`:711-717`). **So the online tick of a commit is the dispatch tick plus the worker's latency.**
- **How a slice's start is chosen.** The start is the committed tick, and the end is committed + cadence, or an earlier fence (`IslandClock.java:59-61`). The grid depends on ticks alone while the cadence is fixed. A slice is dispatched at the first pump with online ≥ end (`:60`, `IslandCoordinator.java:682-685`). That is late if the previous slice committed late.
- **What the read takes.** The read takes `transits.getOrDefault(pair)` at dispatch (`IslandCoordinator.java:1219`). That value equals all the still's owes committed so far, less the water's bookings through *s*. The water's part is certain, because the water island is committed exactly through *s*. **The still's part is not.** It holds the owes of every outlet or pot slice whose worker has already finished, whatever its end tick. The generic two-stream read corrects its transit to the slice start (`bookedPast(start)`, `:1227`). The still's read skips that correction (`continue`, `:1221`).
- **No wait.** `Stills.ready` checks only reservoirs, that is pots and outlets (`Stills.java:253-259`). The water island's graph holds the water-side pipe, so the water island never waits for the pot or the outlets. The column, by contrast, refuses a utility side while its cycle at *s* − fence has not run (`ColumnOperation.java:824`), and it subtracts owes dated after *s* − fence (`:1091`).
- **Every quantity in the still's read, and what fixes it** (`Stills.java:416-426`):
  - `transitDue` = live transit. Fixed by when workers finished.
  - `rate` = `r.heat` + backlog. `r.heat` is set at the pot's latest dispatch (`:169`, `:216`), so it depends on how far the pot has run. That depends on worker timing.
  - The ceiling uses `r.solved`, which `promote` applies at the pot's dispatch or commit (`:168`, `:434`). Its tick is fixed, but when the water sees it depends on the pot's progress.
  - The read also records `r.transit` and `Paid(start, 0, transit)` (`:419`). `condensed` uses these at the pot's commit (`:405-413`, from `submit`, `:436`). The test `r.transit ≤ hold·need` and the share computed from them inherit the live value. The `Paid` log holds owes (tick = outlet end) and reads (tick = water start) in arrival order, not in tick order. `keep` trims it from the head (`PortAccounts.java:82-84`).
  - Result: the share sets COOLING LIMITED and the effective reflux of the next column job (`:437`). Through the reflux it reaches the head's accounts, which are facts.
- **Concrete scenario** (fixed cadence 100 ticks, factor 4, no holds). The distillate island D commits on ticks …1000, 1100. The water island W is offset by 10 ticks, for example because a topology event recompiled it, since an island starts at its event's tick (`IslandCoordinator.java:954`). W's slice [1010, 1110) is dispatched at tick 1110.
  - Machine 1: D's slice [1000, 1100) finishes in under 10 ticks. W's read includes D's owe, about Q·20 s, so W takes 400 kJ and its outlet rises 10 K.
  - Machine 2: the same job takes 12 ticks (0.6 s, under the 2 s budget, so it is accepted and is not a hold). W reads about 0 and takes nothing, and its outlet rises 0 K. The next slice reads about 2·Q·20 s and rises 20 K, or the ceiling clamp binds (`ExchangerRelay.java:320-323`).
  - Size: **±100 % of one slice's condenser duty per slice**, on the water's heat and outlet temperature, and on whatever lies downstream (a tower, a tank). The totals agree over a long run.
- **Smaller effects.** `rate` starts to differ only when the transit passes hold·Q = 40 s·Q. At cadence 100 the two schedules give 20 s·Q and 40 s·Q: equal to the bound, so the margin is zero. `condensed` uses the same bound (`Stills.java:409`). A firing step or the owes' uneven lumps can therefore flip COOLING LIMITED on one machine only.
- **Tests.** `StillRuntimeTest`'s rig drains every job within the same tick and starts all islands on one grid (`StillRuntimeTest.java:150`, `:187`, `:213-235`). It therefore tests one schedule only. No still test varies completion timing. `BatchStillTest` is science only.

## 3. Proposals for the owner

- **A. Leave as is.** Nothing moves. Rule 1 of `SOLVER_DECOUPLING_PLAN.md` §5 is broken outside the wall-budget hold, and the saved transit differs between machines.
- **B. Fence the still's read as the column's (recommended).** In the still's own quantities:
  - `due = transit − Σ owed(p)` over the still's owes with `p.tick > start − FENCE_TICKS`. The edge is strict because `through` means committed ≥ the tick, so an owe dated exactly at the fence is booked.
  - `rate = heat(start − F) + max(0, due − hold·heat(start − F))/(6·hold)`, read from a dated log of `r.heat` written at the pot's dispatch with `slice.startTick()`.
  - Ceiling: the condensate temperature of the answer in force at *start* − F, through a dated log or the answer's apply tick.
  - Wait: `Stills.ready`, for a pipe whose side is in `waters`, returns false until the pot's island and the islands of `u.distillate()` and `u.vent()` are `through(…, start − F)`. Skip the island itself. Nothing waits on the water today, so this adds no cycle.
  - `r.transit` and the `Paid` reads carry `due`.
  - `condensed` uses only reads and owes dated before the pot slice's start − F, and the pot's `ready` also waits for the water island through that tick. The two waits lag by F each way, so they cannot block each other.
  - What it changes: the water takes the duty about 88 s of process time later, and the transit holds about Q·88 s more in steady state (1.76 MJ at 20 kW).
  - Risks: a new wait (normally met, since the lag is 22 s of play), and one more dated log.
  - Tests that would move:
    - `StillRuntimeTest.aPentanePotWithItsColumnDistilsIsopentaneFirst`: its asserts hold (water heat > 0 after 240 s, `condensed` 1 if `r.transit` carries `due`). Its printed water heat drops by about Q·88 s.
    - The fire, electric and wash tests have no water side or do not read it (`:651-675` sums pot heat), so they stay.
    - Add the still's determinism gate: the pentane rig with a per-island completion lag and an offset water island, with the accounts, totals and water heat equal bit for bit.
- **C. Fence `due` only.** This removes the 0/1/2× swing. It leaves `rate`, the ceiling and `condensed` live, so the COOLING LIMITED flip remains. It does not satisfy rule 1, and I do not recommend it.
- **F5, F6, F7.**
  - F5 belongs to the decision only for `due`, which must be strict. The `≥` in `condensed` is its own window, and once that window's inputs are fenced it affects no determinism.
  - **F6 belongs.** The pot's commit trims the shared log at *now* − 2F (`Stills.java:414`), and nothing stops the pot from running more than F ahead of the water. A trim could then drop owes that the fenced read must subtract, and whether it does depends on timing. Give the owes their own log, trimmed only by the water's reads, or kept for F + `KEEP_TICKS` like the column (`ColumnOperation.java:1077`).
  - F7 belongs only in that the water side gains a wait. The still's `through` form is the right one, because the still has no cycle clock. The two mechanisms need not be unified.

## Not read or not decided

- **The same class, outside F4.**
  - `outletPlan` reads `r.solved` and `r.condensed` (`Stills.java:283`). The pot sets both at its own dispatch or commit, while the outlet waits only for the pot through *start* − F (`:258`).
  - `outlet()` reads `sideTemperature(water, end)` (`:450`, `IslandCoordinator.java:1315`) without the water island being through `end`.
  - The wakes at `Stills.java:347`, `:400` and `:462` fire at commit or completion time. A certified island materialises to the online tick of that moment (`IslandCoordinator.java:1283-1293`, `:1367`). The column instead wakes at tick + F (`ColumnOperation.java:1073`).
  - Option B's dated logs would cover the first two items. The wakes need a fixed wake tick.
- With `adaptiveCadence` on, which is the default (`IChemE.java:99`), slice grids follow measured CPU (`IslandClock.java:109-113`, `IslandCoordinator.java:764-768`). That moves every island and is outside this review. The fenced column does not depend on the slice grid.
- I did not read the `src/fluidGameTest` still scenarios' assertions, `StillHead`, or the dispatcher's real worker pool. Lockstep can also break through worker capacity (`IslandCoordinator.java:707`), and I did not trace that case.
- I did not compute the owes per slice exactly, so the zero margin of the COOLING LIMITED bound is argued from its terms, not from numbers.
