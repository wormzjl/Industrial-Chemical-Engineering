# R-ST2 report: the still's fire and its job cadence by ticks

Subagent (Opus high), 2026-11-13, worktree branch `worktree-agent-aedbc5e7a53e8aaa0` from `claude/codebase-review-refactor-8a85ef` at `df0eff832`. Brief: [WAVE_D_BRIEFS.md](../WAVE_D_BRIEFS.md), "R-ST2"; the pattern: [R-ST1_REPORT.md](R-ST1_REPORT.md). Code commit `b4e987855`. Code written and read only: **nothing was compiled or run**.

## 1. The expected behaviour

F = `FENCE_TICKS` = 440 ticks (22 s of play; 22 s of process at factor 1, 88 s at factor 4). The rule: a result does not depend on when a worker finished.

- **The column jobs.** Whether the pot's commit asks a column job depends on ticks only. A job asked at the commit at *e* applies at *e* + `APPLY_TICKS` (100). The next job is asked once that answer is certain on every machine. That is at the commit of the first pot slice whose start is at or after *e* + 100: the dispatch of that slice waits for the answer.
- **The liquid fire.** The pot's heat follows the fuel that its burners burned, read as of the pot slice's start − F. The burners' valve follows the pot's state (the column answer that lights the fire, the OVERHEAT and BOIL DRY cuts) as of the burners' slice start − F. Each side waits for the other's island through its fence. The result is a fixed lag of one fence each way, the same on every machine.
- **Energy.** The fuel's heating value is on no energy account. The pot's HEAT boundary books the heat the pot took (`StillUnit.heated`, a fact). The burners' void books the fuel's mass and its fluid energy as a sink, as before. So the lag creates no energy and loses none on the ledger. Heat fired but not yet taken by the pot is booked nowhere, as before: the pot's heat is a rate (the firing × the rating × min(1, fire × 0.55 / asked)), not an integral of the fuel burned.

## 2. What the code did

- **The jobs.** `submit` (at the pot's commit) returned while `r.pending` was true, and `completed` cleared `pending` when the worker's answer was drained. With 100-tick slices and `SOLVE_TICKS` = `APPLY_TICKS` = 100: a job asked at *e* applies at *e* + 100. The slice from *e* was dispatched before that tick, so it did not wait. At the commit at *e* + 100 the next job was asked if the answer had landed (a fast worker) and skipped if not (a slow worker, which then asked at *e* + 200). `replanned` reset `lastSubmit`, so after a settings change the same race decided whether the next commit asked a job.
- **The fire.** The burners' commit wrote `r.fired` and called `controlsMoved(potIsland)` at once when the fire lit or went out. The pot's dispatch read `r.fired` live (`heat`, case LIQUID). The burners' dispatch read the pot's live `r.solved` (`waits`) and `r.alarms` (`fuelTarget`). `r.solved` is promoted at the pot's commit or dispatch, so whether the burners saw the first answer depended on whether the pot's round had closed.

## 3. What was done (`Stills.java`)

1. **The job cadence by ticks** (`submit`). The test is now `start < r.lastSubmit + APPLY_TICKS` (the slice's start, not its end). `lastSubmit` at its initial value `Long.MIN_VALUE/2` passes. The `r.pending` test stays as a guard, and a comment says that the rule makes it false: the slice's dispatch waited in `ready` (`r.pending && r.applyTick <= start`), and only this pot's commits submit. The old `end < lastSubmit + SOLVE_TICKS` test is implied, so it was removed, together with `SOLVE_TICKS` (its readers changed, see below).
   - `RESPONSE_TICKS`, which `ControlLaw` reads, keeps its value: 840 = `CYCLE_TICKS` + `APPLY_TICKS` + F + 2 × `CYCLE_TICKS`. Its comment states the case it does not count (see section 7).
   - `replanned` no longer resets `lastSubmit`. With the rule, a reset can only allow a job before the last apply tick, and that job would depend on whether the answer had landed. A settings change is taken by the next job on the cadence.
   - Still open, only listed: `solves.submit` returning false (the service's capacity) skips a job by load.
2. **The liquid fire as of the fence.**
   - `Run.fires` is a log of `Fire(tick, watts)`, with `r.fired` removed. The burners' commit appends `(end, flow × perKilogram)`. A re-booked span with the same end replaces its entry, so the log stays in tick order. The log is trimmed like R-ST1's logs (`keepAsOf`, `end − 2F − KEEP_TICKS`).
   - `heat` reads `fireAt(r, start − F)`: the latest span that ends at or before the fence, or 0 (not lit) when there is none. The pot's `ready` waits for `through(island, u.burners(), fence)`. A burners' island that is through the fence has booked every span that ends by then.
   - When the fire lights or goes out, the pot wakes through `wakeAt(end + F, pot)`. The old immediate `controlsMoved` is gone. `r.perKilogram` stays the burners' own island's value.
   - The burners' side: `fuelTarget(u, r, fence)` reads `waitsAt(u, r, fence)` (no `solvedAt` answer by then) and `heatAt(r, fence).cut()`. `cut` is a new field of the `Heat` entry that the pot's commit logs: `r.alarms & CUT` (OVERHEAT | BOIL DRY). Its dispatch sets these bits, and `status` keeps them. The burners' island waits in `ready` with `fenced(island, pot, fence, false)`: the pot's island through the fence and the answer due by then.
3. **The gate** (section 8).

## 4. The waits cannot block each other

R-ST1's edges were W→P, W→D, W→V, D→P, V→P and P→W. R-ST2 adds P→B (the pot on its burners) and B→P (the burners on the pot). Each edge has a lag of F: X waits on Y only while c_Y < c_X − F.

- A blocked cycle X1 → … → Xk → X1 would give c_X1 < c_X1 − kF, which is impossible. A cycle that mixes these edges with the coordinator's fence (lag 0) or a column's waits (lag ≥ 0) has a total lag ≥ F, so the same sum excludes it.
- The answer waits depend on a worker's completion, not on an island. Every still request completes (`StillHead.Failed` included).
- In the gate the waits are never binding: the largest completion lag is 60 ticks, far below F.

Special cases of the burners' island:

- **Certified:** `through` materialises it. Its fire entries are dated at the materialised spans, and a lit or out change wakes the pot at end + F.
- **Held, parked or suspended:** `through` is true, and the pot keeps the last fire entry. This is unchanged: `r.fired` also kept its last value.
- **Absent or removed:** there is no wait, and no entry after the last one.

## 5. Saves

`Run` is memory only, as before. The fires log and the `cut` field are state; no format number changed, `FORMATS.md` is unchanged, and no fact changed.

After a load (or `Stills.removed`) a lit liquid still runs as follows (A = the first answer's apply tick, about 200 ticks after the load):

1. The pot waits for the first answer (heat 0, no NO FUEL) and asks its first job at its first commit, as before.
2. The burners' fuel stays 0 until their fence reaches A: fuel flows from their slice that starts at or after A + F.
3. After A the pot reads no fire, so it shows NO FUEL and asks no job until the fire reaches it.
4. Once the burners' first lit span (end e_b) is a fence old (pot slice start ≥ e_b + F), the heat is back. That is about A + 2F + one to two slices: about 1,200 to 1,300 ticks after the load (60 to 65 s of play), against about 400 before.

The still relights without the player. Nothing that the burners read depends on the fire being lit: only the firing, the answer as of the fence and the cut alarms as of the fence.

## 6. Values that move (estimates from the code; not measured)

| Value | Old | New | Size |
|---|---|---|---|
| Column jobs, 100-tick slices | every commit (fast worker), every second (slow) | every second commit on any worker | answers 200 ticks apart: 10 s of play, 10 s of process at factor 1, 40 s at factor 4 |
| A job's pot composition (pentane rig) | answer stands 100 ticks | 200 ticks | 60 L charge ≈ 516 mol; 10.95 kW / ≈25 kJ/mol ≈ 0.43 mol/s boil-up; at R = 2, D ≈ 0.15 mol/s. 100 more ticks: 0.73 mol out at factor 1 (2.9 at factor 4); pot x shifts by ≈ 2.8e-4 (1.1e-3), head temperature by ≈ 0.002 K (0.009 K) |
| Response to a reflux set just after a job | ≤ 200 ticks to the next answer (fast worker) | ≤ 300 ticks | `RESPONSE_TICKS` kept at 840 (it would be 940): the controller's cadence is unchanged, an open point |
| Liquid pot's heat after a fuel change | next pot slice after the burners' commit (≤ 1 to 2 slices) | one fence later (+440 ticks) | 22 s of play |
| Burners after a cut (OVERHEAT, BOIL DRY) | next burners' slice | one fence later | extra fuel at the rig's firing: fired = 10.95 kW / 0.55 × 1.01 = 20.1 kW, at 45.25 MJ/kg (isopentane, 3,264.7 kJ/mol) 0.444 g/s; over 440 ticks: 9.8 g (0.44 MJ) at factor 1, 39 g (1.77 MJ) at factor 4, plus up to a slice of grid rounding. It goes to the burners' void; the pot is cut |
| Fire at a start or after a load | ≈ one slice after the first answer | ≈ two fences after it (+880 ticks, 44 s of play) | NO FUEL is shown meanwhile (before: for about one slice), and no job is asked while it shows |

Conservation is exact. Every mole and joule is booked by the same boundaries as before; only the dates of the reads changed.

## 7. Test expectations changed or expected to move

- **Changed (constants, not values):** `aBoilingStillsFireWaitsForItsColumnsFirstAnswerAfterALoad` (`limit`) and `aWashAtTimeFactorFourBoilsInAQuarterOfTheTicks` (loop bound) read `4 × (CYCLE_TICKS + APPLY_TICKS)` in place of `4 × (SOLVE_TICKS + APPLY_TICKS)`: 800 ticks both, old and new. A comment says why. The first job is still asked at the first commit, so the first answer's time does not move (the printed "finding 2b" wait stays about 200 ticks).
- **Rig only:** `Rig.jobs` entries gain a third value (the slice end that asked the job). `aLightOilsGasGoesToTheVentAndTheNextJobTakesTheWatersStep` reads `[0]` and `[1]`, unchanged.
- **Expected to move (the main thread reads the printed value):**
  - The job counts: the R-ST1 gate's printed `%d jobs` falls to about half. Its asserts compare the two runs and hold.
  - `aLightOils…`: about 6 jobs after the step instead of 12 (`> queued` holds). The solid rig's jobs are at commits of even hundreds (the first commit has no fuel yet), so the last job is still at the commit at 6000, fence 5460, as before.
  - `aWashInAPlacedStillIsRectified`: `head.recovery(0)` (0.727 ± 0.05) is from an answer up to 100 ticks staler. It should move only in the last digits; I cannot tell from reading.
  - `aPentanePot…` and the wash tests: no asserted value counts jobs. Their pot heat does not depend on the cadence (no flooding at these firings; reflux 0 in the wash pots).

## 8. Tests for the main thread

`./gradlew test --tests 'com.wormzjl.icheme.runtime.fluid.StillRuntimeTest' --tests 'com.wormzjl.icheme.science.fluid.network.BatchStillTest' --tests 'com.wormzjl.icheme.science.fluid.network.StillHeadTest' --tests 'com.wormzjl.icheme.client.fx.emitter.StillModelTest' --tests 'com.wormzjl.icheme.runtime.fluid.FluidFactsCompatTest' --tests 'com.wormzjl.icheme.runtime.fluid.ColumnRunTest' --tests 'com.wormzjl.icheme.science.plant.port.PortAccountsTest'`, then `./gradlew compileFluidGameTestJava`.

- `StillRuntimeTest` proves both fixes (the new gate), keeps R-ST1's gate, and holds the old behaviours' asserts.
- `ColumnRunTest` shares the coordinator's ports.
- The others load nothing changed and are in the brief's list.
- Only `StillRuntimeTest` read `SOLVE_TICKS`. `ControlLaw` reads `RESPONSE_TICKS`, whose value is unchanged, and no test runs a still's controller. Nothing in `src/fluidGameTest` names `Stills`.

The new gate is `aLiquidStillsFireAndJobsDoNotDependOnWhenItsIslandsJobsAndSolvesComplete`. It runs the pentane still fired from its burners (isopentane vapour at 400 K and 5 bar into the burners' void, an island of its own) twice, on the same grids: the water's island offset 10 ticks and the burners' 50.

- **Run a:** every job completes in its tick, and every column solve answers at the next drain.
- **Run b:** completion lags of 7 (pot), 12 (distillate), 4 (vent), 5 (water) and 60 (burners) ticks; every column solve answers 150 ticks after it was asked.
- **Stop:** at 5,920 ticks. Both runs have then committed the same slices (pot to 5,900, water to 5,910, burners to 5,850; b's last commits land at 5,912, 5,915 and 5,910, and a's next at 5,950).
- **Compared bit for bit:** the accounts, the totals, the heat and energy of every boundary, the signed exchange moles of every node (the fuel burned among them), the pot, the transits, the number of jobs, the list of (slice end, outlet) per job, the share and the reflux.

Why the gate fails on today's code, from the schedules:

- **The jobs.** In run a, the job asked at the pot's commit of the slice to 100 (tick 100) answers at once. At the commit of the slice to 200 (tick 200) `pending` is false, and a job is asked. In run b the commit lands at 112 and the answer at 262. At the commit at 212 `pending` is true and the job is skipped; it is asked at the slice to 300. The job lists differ, and so do the answers, the head and everything after them.
- **The fire, forward.** The burners' slice to *e* + 50 commits at *e* + 50 in run a, before the pot's dispatch at *e* + 100, and at *e* + 110 in run b, after it. Today's pot read `r.fired` live, so run b's pot heats on a fire one burners' slice older. At the lighting (fire 0 to lit) the pot's heat differs by a whole slice.
- **The fire, reverse.** The first answer applies at 200. In run a it is promoted at the pot's commit at 200. In run b it lands at 262, after that commit (212), and is promoted at the dispatch at 300. The burners' slice dispatched at 250 read `r.solved` live: run a asked fuel and run b did not.
- Each defect alone fails the gate: with only the fire fixed, the job lists differ; with only the cadence fixed, the fire differs.

## 9. Where I am unsure

- **Compiling** (all read, none built):
  - `coordinator.observe(island).clock().committedTick()` inside the `solves` lambda in the `Rig` constructor. The blank final `coordinator` is assigned earlier in the constructor.
  - `Fire::tick` as a `ToLongFunction<Fire>`.
  - `assertEquals(Long, Long, String)` overload resolution.
  - The record `Solve`, whose component `done()` stands beside `Rig.done(long)`.
  - `new ArrayList<>(List.of(...Device...))` inferring `ArrayList<Device>`.
  - `FluidWorldAuthority.heatingValues` (package-private static, same package as the test, as `FiredHeaterFuelTest` uses it).
- **Running:**
  - The fuel line uses a still's valve (`ControlValve.still()`), not the fired heater's. It mirrors `FiredHeaterFuelTest`'s ethanol vapour line (two pipes into a void at `Burners.AIR`, `Burners.PRESSURE`), but no test has run a still's burners before. A hold there fails the gate's `fuel > 0` or DISTILLING asserts.
  - DISTILLING at 5,920 in run a is estimated from the solid gate. There the heat starts at tick 300: the first commit has no item burning, so the first job is asked at 200 and applies at 300. The gate asserts DISTILLING by 4,820. Here the heat starts at about tick 1,200: the first job at 100, the burners' first fuel over 650 to 750, the pot's slice from 1,200. That is 4,720 ticks of heat by 5,920, against 4,520.
  - Bit equality rests, as in R-ST1, on the commits keeping their order: the pot's round (12) before the water (15). The burners' island touches no transit.
- **Not covered by the gate:** the reverse read through a cut (only through the first answer); certified islands (certificates are off in the rig).
- **For the owner:** a lighting fire shows NO FUEL for about two fences, where it showed it for about one slice before. Option: treat a fire whose fuel is asked but not yet booked as of the fence as HEATING. That is a status change, not implemented.
- **Presentation only:** `view().headTemperature()` and `statusLine` read the live `r.solved`. A late answer is promoted at the next dispatch rather than at the commit, so a viewer can see the new head a slice later on a slow worker. No result reads it.
