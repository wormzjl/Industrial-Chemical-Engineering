# Level loop GameTests: why one of three fails at random (wave A / review branch)

Read-only diagnosis, 2026-11-13. No Gradle, JVM or GameTest run. Sources: the five lanes' `gametests.gradle.txt` (two baselines, wave A lane and its rerun, review lane a0b157325), `LevelLoopGameTests`, `ControlService`, `ControlLaw` (LEVEL), `InventoryControl`, `IslandCoordinator` (pump, presentation, flushWakes), `IslandClock`, and `git diff 259d22a2c..a0b157325` on `runtime/`.

**Verdict: (c) with (b), not (a).** The level law is a deadbeat inventory law. It is stable only while the delay between the sample it reads and the slice that first runs its output is under 0.38 control cadence (38 ticks), and while the interval its output is held stays under 2 cadences of process time. The test's island runs adaptive slices of about 30 to 70 ticks and falls behind online time by a worker's solve. With slices of about 67 ticks (tests 1 and 2), each decision made while a slice is in flight is outside the limit. When the island catches up after a backlog (test 3 at the step), one output is held for about 2 cadences of process time. The law has had this margin since it was built. The baselines show it already (baseline head test 1: 30.17 % low, oscillating to cycle 25). No wave A or B line changes the control law, the control schedule, when an output reaches the solve, or admission for a one-island batch. One indirect effect is possible but not proven: B-R1 makes the server tick cheaper, so on the unthrottled GameTest server a solve can span more ticks.

## 1. Expected behaviour (control physics, numbers)

- **Drum.** Lying drum, 1.5 m by 4 m, 7.952 m3. Water feed 198.1 kg/s = 0.199 m3/s. Full residence 40 s of process = 2 cadences (cadence: 100 online ticks = 5 s of play = 20 s of process at factor 4). Free surface near 50 %: about 6.5 to 6.75 m2, so 1 % of height is about 0.10 m3. The step adds +0.099 m3/s, which is +2.0 m3 (about +20 % near mid-height) per uncorrected cadence. The room from 50 % to 99 % is about 3.9 m3, which is 40 s = **2.0 cadences of headroom**. With the feed raised, the drum overflows when the first applied correction comes 2 cadences or more after the step.
- **Law** (`InventoryControl.target`, `ControlLaw` LEVEL): u_k = Q_k + (V_k - V_set)/T, with T = 20 s. Q_k = q_meas + (V_k - V_{k-1})/dt. q_meas is the outlet's end-of-slice flow at the sample. In velocity form this is a PI with Kc = 1/T (m3/s per m3) and Ti = T: a deadbeat design. With no delay its poles are z^2 = 0, and the error is gone after 2 samples. The law has no windup: it uses the measured outflow and clamps at 0 (seen 0.075 m3/s at each start).
- **When an output acts.** The controller runs at online ticks that are multiples of 100 (`ControlService.tick`). It reads the last published state, at the committed tick C. `controls.apply` is read only at the next dispatch (`IslandCoordinator.pump`). A slice is [C, C+S], with S the island's adaptive cadence (`IslandClock.nextSlice`, 20 to 400 ticks; `adaptiveCadence = true` in the GameTest config), and it is dispatched when online reaches C+S. A decision made while the next slice is not yet dispatched acts from C: no dead time. A decision made while a slice is in flight acts from C+S: dead time S. The share of decisions with dead time S is about W/S. W is the worker's solve measured in server ticks, which is the logged `lag`. When W > S the island has a backlog. It then catches up faster than online time, so two decisions can be 1.5 to 2.5 cadences apart in process time.
- **Stability against dead time f = theta/T.** The output acts at fraction f of the sample interval. This gives the characteristic polynomial z^3 - 2f z^2 + 3f z - f. Jury test: stable only while f^2 - 3f + 1 > 0, that is **f < (3 - sqrt 5)/2 = 0.382**. At the limit the loop rings with a period of about 4.6 cadences. Largest pole per cadence: f 0.2 gives 0.76, 0.3 gives 0.90, 0.4 gives 1.02, 0.5 gives 1.12, 0.6 gives 1.22, 1.0 gives 1.52. The cause is the rate term: dV over an interval in which the old output ran for fraction f biases Q by f(u_{k-1} - u_{k-2}).
- **Stability against the hold interval** n = (process time an output stays in force)/T, with no dead time: z = 1 - n, so it is **unstable for n > 2**. This happens when S > 200 ticks (the adaptive cadence reaches 400) or during a catch-up.
- **Slice length per run** is 3000 loop ticks over the result line's `intervals`. This is an estimate: certified spans are not counted.
  - Test 1 (blanket, direct): 30 / 67 / 68 / 70 / 67 ticks.
  - Test 2 (open drum): 24 / 60 / 64 / 56 / 60 ticks.
  - Test 3 (blanket, ramp): 30 / 38 / 37 / 32 / 33 ticks.

  Runs in order: baseline fix/head, baseline head, wave A lane, wave A rerun, review lane.

  So a decision made in flight has f of about 0.6 to 0.7 in tests 1 and 2, outside the limit, and about 0.33 in test 3, just inside. Baseline fix/head ran 24- to 30-tick slices (f no more than 0.3 always), and it is the best run in every test.
- **Observed lags.** Steady W is 5 to 22 ticks, so W/S is 0.1 to 0.3 and most decisions are deadbeat. After the step W is 40 to 76, so in tests 1 and 2 most decisions are made in flight (f about 0.67, outside the limit). In test 3, W > S means a backlog and a held interval of n about 2 (outside the limit).

## 2. What happened, cycle by cycle (level %, outlet m3/s, lag ticks; island FULL unless noted)

| cycle | T1 review (FAIL) | T1 wave A lane (pass) | T1 baseline head (pass) | T3 wave A rerun (FAIL) | T3 review (pass) |
|---|---|---|---|---|---|
| 15 | 50.02 .1988 14 | 50.02 .1988 2 | 50.02 .1988 12 | 50.02 .1988 5 | 50.02 .1988 14 |
| 16 | 62.47 .1989 51 | 64.08 .1989 43 | 62.47 .1989 51 | 64.08 .1989 62 SOLVING | 61.50 .3339 76 SOLVING |
| 17 | 76.01 .1989 40 | 46.17 .3695 6 | 76.01 .1989 18 | 86.16 .1989 15 | 53.92 .3339 12 |
| 18 | 45.98 .4657 48 | 48.64 .2783 25 | 51.03 .4251 35 | 66.50 .4967 72 SOLVING | 45.17 .3452 13 |
| 19 | 41.07 .1700 48 | 52.00 .2910 8 | 30.17 .3348 33 | 28.54 .4962 29 | 49.83 .2732 13 |
| 20 | 57.03 .1700 19 | 49.96 .3147 38 | 48.67 .1537 45 | 32.93 .1982 12 | 50.03 .2971 15 |
| 21 | 58.78 .3342 55 | 47.96 .2978 14 | 57.18 .3836 46 | 60.80 .1298 9 | 50.03 .2982 9 |
| 22 | 54.76 .3342 22 | 52.69 .2791 17 | 47.73 .3835 9 | 60.97 .3534 31 SOLVING | 50.03 |
| 23 | 35.04 .3862 17 | 51.18 .3119 30 | 49.98 .2411 37 | 32.22 .4437 12 | 50.03 |
| 24 | 48.54 .1782 54 | 49.01 .3041 11 | 52.73 .3304 45 | 35.55 .2281 8 | 50.03 |
| 25 | 62.79 .2906 46 | 51.03 .2891 3 | 49.65 .2938 20 | 56.21 .1708 10 | 50.03 |
| 26-28 | 63.67 / 41.09 / 39.38 (lags 12, 27, 39) | 50.46 / 49.65 / 49.79 | 50.14 / 50.56 / 49.91 | 55.57 / 49.27 / 49.45 | 50.03 |
| 29-30 | 59.14 / **71.20** (27, 16) | 50.52 / 50.09 | 49.79 / 50.04 | 51.28 / 50.73 (high 99.71) | 50.03 |

- **Test 1, review lane.** The run matches baseline head exactly through cycle 17. In both, LIC-1's panel still reads 0.7502 m (50 %) at cycle 16, and the outlet is unchanged at cycle 17: the first correction missed one cadence, and the peak is 76.01 %. From there on, baseline head's lags fall to 9 to 20 at cycles 22, 25 and 27, and it decays. The review run has lags of 40 to 55 in 8 of the 15 cycles after the step. It keeps a non-decaying swing of about ±20 % with peaks at cycles 17, 21, 25/26 and 30 (period about 4.3 cadences, the f ≈ 0.4 to 0.5 mode above) and ends at a peak (71.2 %). Wave A lane test 1 corrected within cycle 17 (lag 6), and later lags were mostly under 30, so it settles.
- **Test 3, wave A rerun.** At the step the island fell behind (lags 62 and 72, SOLVING). Panel LIC-1 still read 50 % at committed cycle 17 (86 %). Two control ticks passed with no sample taken after the rise. The next decision read 1.496 m (99.7 %, the run's high of 99.71), and its 0.497 m3/s was held for about 2 cadences (lag 72 again). The level fell to 28.5 % and rang down slowly (f ≈ 0.33, pole about 0.93). The review lane's test 3 corrected within the first cadence (0.334 m3/s at cycle 16) and settled by cycle 19.
- The flows are steady (FIC 297.2 kg/s). The mass and energy books close to 1e-10. The island never held, and `unread` = 0. The plant behaves physically. Only the timing of the loop differs.

## 3. Verdict: (c) with (b), not (a)

- **(c) A control defect (for).** The derivation above. The non-decaying swing appears exactly when post-step lags sit near or above S·0.38 with S ≈ 67. The swing period (about 4.3 cadences) matches the 0.4 to 0.5 mode (4.5 to 4.9). The overflow comes with a backlog (W > S) at the step. The defect is not specific to the harness: any island whose slice exceeds 38 ticks and is often in flight (a heavy plant, or the adaptive cadence above 100), or whose cadence exceeds 200 ticks, gives a level loop that rings or diverges in play too.
- **(b) A thin test margin (for).** The drum has 2.0 cadences of headroom over 50 % for a +50 % step, and the first correction needs 1 cadence plus a dead time. `pace()` throttles only above CADENCE + LAG = 110 ticks, so lags of 40 to 76 are unthrottled, although its comment intends "a server at 20 TPS with quick workers". The trajectories already differed between the two baseline runs, on the same code.
- **(a) A regression (against).** Changes on or near the loop:
  - `ControlService`, `ControlLaw`, `InventoryControl`, `FlowControl` and `IslandClock`: unchanged since 0.30.0.
  - **R5** (2828ae388):
    - In the failing test-1 run the island showed no STEADY line during the loop, so no preview was in play.
    - Where an island is certified (other runs, cycles 9 and 14 to 16), a control change goes through `controlsMoved` → `flushWakes`. That path materialises to min(online, fence, hold, horizon) and then dispatches with the new law, before R5 and after it.
    - The preview shows the same target tick (horizon − 1 rule kept) and the same state that the old materialisation committed. So the LIC's sample tick, its dt and the actuation tick are unchanged. Since R1 the outlet reading is `lastResult`, which the preview carried unchanged.
  - **B-R1 R6** (c3aaea2f4): each level-loop test is alone in its GameTest batch (unique batch names), on one island, with no plant job. A reserved island job is refused only when every worker is busy, which cannot happen with one outstanding slice and two workers. The start budget applies only to plant jobs.
  - **R1, R2 (ViewIndex), R10, R22, R23 (exchanger pairs only), IslandSets, B-M1/G0/G1 (no shafts or worn movers here), A3 M-04, `validatedStock`, `withHealth`:** these return the same values, or are not in this plant.
  - **The only plausible indirect effect.** Steady lag before the step is about 1.3 to 1.7 times higher in the review lane in tests 1 and 2 (mean 22 and 11 ticks, against 12 to 14 and 5 to 8 in the other four runs). B-R1 removes server-thread work per tick. On the unthrottled GameTest server that raises a solve's length in ticks (W) without changing the solve. On a server at 20 TPS it has no effect. This is unproven without counters (see 5).

## 4. Proposal (options for the owner)

- **A. Controller: the inventory balance over the actuation interval (recommended).** Changes in `InventoryControl.target` and its two callers in `ControlLaw`: LEVEL, and the column LEVEL feed, which shares the law.
  - **A1. Horizon.** H = max(T, the process time between the decision's actuation and the next one's). This means at least the island's slice S and the measured dt between samples. It removes the n > 2 failure (S > 200 ticks, catch-up).
  - **A2. Exact balance.** Q = dV/dt + the mean outflow over the same [t_{k-1}, t_k]. The mean comes from the volume the island booked through the output pipe in its accepted intervals: a read of the `PipePresentation.SpeedWindow` transfers, kept as state, never a fact. It replaces the end-of-slice q_meas. With A2 alone the poles are z^2 - f z + f, |z| = sqrt f, which is stable for any dead time under 1 cadence (f 0.67 gives 0.82).
  - **A3. Age correction.** The coordinator states the tick from which a new control value acts: the end of the outstanding slice, or C. The law predicts V there with the law in force. With A2 and A3 the poles are z^2 = 0 (deadbeat) for any known delay.
  - Risk: a new coordinator read, and moved references in ControlLoop and LevelLoop GameTests and the column level loops (their review states old and new values).
  - Gate: a deterministic JUnit loop test. A discrete drum integrator with a +50 % step, f in {0, 0.3, 0.6, 0.9} and n in {0.5, 1, 2, 3}. It asserts the peak and that the error falls by at least half per cadence. This proves the margin without a GameTest.
- **B. Controller, minimal: H = 2T, nothing else.** The limit rises to f < 2 - sqrt 2 = 0.586 and n < 4. That is not enough for in-flight decisions with S ≈ 67 (f 0.67). The uncorrected rise at the step is larger, so the peak is about 75 to 80 % against 2 cadences of headroom. Not recommended alone.
- **C. Engine: process-time control for coupled loops.** A loop whose sensor and output share an island (`controlCoupled`) decides at a fence every cadence of process time, on the state at that fence, and acts from it. Its dead time is zero by construction, and the gate becomes reproducible. Risk: a redesign of the schedule. Every controlled island is held to slices of 100 ticks or less, and a certified island wakes at each fence. Loops across islands still need A.
- **D. Test only: `pace()` above LAG (10) instead of CADENCE + LAG.** The harness then behaves as its comment states (W ≤ 10, W/S ≤ 0.15), and the three tests pass with margin. Risk: it hides (c), and the gate no longer covers slow solves. A wider settle window does not help: at f ≥ 0.4 the swing does not decay. Use it only as a stopgap beside A, not instead of it.
- **One GameTest run after A must show:**
  - all three result lines pass;
  - post-step peaks at or below about 70 %, and |level - 50| < 2 % from cycle 20;
  - no swing that grows or persists;
  - and, so the run exercises the failure condition, at least two cycles after the step with lag ≥ 40, or a SOLVING line.

  Add the decision's dead time and H to the `cycle=` line, so the run proves the margin from its own record.

## 5. Not determined without a run

- Each island's actual adaptive cadence S and the in-flight share W/S at each decision. The logs record neither: S is inferred from `intervals`, and certified spans are not counted.
- Whether B-R1 changed server-thread cost per tick enough to shift W. This needs a counter of server ticks per accepted interval on both codes (no times).
- The exact control-tick phase relative to each publication. The panel's value in the log is the last view, not each decision's sample tick and actuation tick.
- How often the controller decides on a reading that has not advanced (dt = 0, then Q = q_meas, which drops the rate term). That is a latent mis-timing for S > 100 ticks. Its comment ("it is what the last decision set") holds only when the last output has already acted. It did not occur in these logs (S < 100).
