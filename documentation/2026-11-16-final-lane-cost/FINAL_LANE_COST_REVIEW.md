# Final lane cost: review

Batch 2026-11-16-final-lane-cost, a performance optimization of the lane itself, so this document gives times. Machine: Ryzen 7 9700X, 8 cores, 16 threads, 48 GB. Reference: the first final lane of the fouling and corrosion batch (`96a6fa733`, all four steps due), about 28 minutes. Every rehearsal below is `LANE_SINCE=adb413b4d..96a6fa733 lane.sh head` on the batch tip `0892cbd6e`: that lane's selection on today's load sets, with a copy of the load map (`LANE_MAP`), so the shared map is untouched.

## 1. Expected behaviour

A final lane proves that a batch broke nothing, once, in a time the project can wait for. Owner, 2026-11-16: under 8 minutes with every step due; only the relevant tests; no timing inside the lane.

## 2. What the lane did

| Step of the 28-minute lane | Time | Why |
|---|---|---|
| Test step | 811 s | 637 classes, a JVM for every class. The `test` task ran 517 one-class JVMs at an effective two at a time; the tests themselves were 2,944 s. 233 classes of the second lane ran unselected because the filter list was too long for the command line. 125 classes were selected by a rule that took the initialized locals of any file with an interface for changed constants. |
| Results | 30 to 77 s | a `basename`, `cp`, `awk` and `sed` process per result file |
| GameTests | 101 s | always against `main`, in a second lane too |
| Campaign | 345 s | 3 x 1000 seeds; in 23 archived campaigns the bundled defaults never held, and 2,946 of the other 3,000 seed runs never did |
| Test content | | 4,088 test methods, 3,264 s. 28 methods failed at least once in 19 archived lanes. The 50 costliest methods that never failed took 1,716 s. |

One file decided the selection: `MaterialCatalog.java` is loaded by 396 of 665 test classes, so a batch that touches it selects most of the suite whatever the rule. Selection alone could not reach 8 minutes.

## 3. What changed

| Change | Effect, measured |
|---|---|
| Campaign on its watch seeds (`campaign-seeds.txt`: 14 cold, 13 bundled random, no defaults) | 345 s to 65 to 102 s |
| The GameTests and the campaign by their last run on the branch's commits (stamps) | a second lane skips them when its changes cannot reach them |
| Filters in a file, `nativeSmoke` in the lane, exact constants | no task runs whole; 551 to 515 classes on the reference lane |
| Test classes share JVMs, 8 to one; 8 workers | test step 811 s to 590 s on the same tests |
| Results copied and read without a process per file | about 30 s less |
| `left-out.txt` at 10 s (50 methods, 1,716 s) | test step 371 s, lane 543 s, measured |
| `left-out.txt` at 1 s (363 methods of 4,088, 77 % of the test time), all classes sharing JVMs | lane 386 s by the model below; 421 s with the slowest campaign and GameTest runs seen |

The model, from the 543 s rehearsal: 8 workers x 368 s = 2,944 worker-seconds, of which 1,924 s were tests and the rest JVM starts and idle workers. Leaving out a further 809 s of recorded test time removes 1,005 worker-seconds, and one JVM for 8 classes instead of one for each class of 5 s and more removes about 250. Idle time is kept as measured, which is the cautious choice. The owner asked for no further rehearsal ("you should be able to tell without running the test again"), so the last form is modelled, not measured: the first real lane is its measurement.

Tried and dropped:

- 12 and 10 workers. Every test ran about 1.5 times as long as at 8, and wall-deadline holds appeared in tests that have none (`RegulatingValveHoldTest`, `BlanketDrainWorldTest`, `CoolingTowerRuntimeTest`, `LevelLoopBlanketStartTest`). At 8 workers none appeared.
- Cutting long classes by test method into parallel parts. Each part builds the class's shared fixtures again: `CrudeUnitSampleTest` took 217 s whole and 606 s in four parts.

## 4. Enforcement

- `tools/agent-guard` blocks a test task with no `--tests`, `check`, `build`, `slowGates`, a filter over a package tree, a campaign of more than 200 seeds, and `lane.sh --all` or `base` without `LANE_FULL=owner`. 38 blocked and 35 allowed commands in its self-test.
- `lane.sh` stops on `--all` and `base` without `LANE_FULL=owner`, prints no time, and lists failed tests.
- `AGENTS.md`: the test tasks never run whole outside the lane; the watch seeds; the left-out list; the lane's 8-minute size.

## 5. What this costs, for the owner to validate

1. The left-out tests no longer guard the lane. The choice is by record (never failed in 6 to 21 lanes), not by proof. `LANE_FULL=owner lane.sh head --all` runs them. Decided (owner, 2026-11-16: "Archive the full suite and runs only when asked"): no periodic full lane; the full suite runs only on the owner's word.
2. The watch seeds leave out 27 seeds that held only at time factor 1 (listed in `campaign-seeds.txt`).
3. Shared JVMs give each class the load set of its 8 JVM mates: more classes are selected later, never fewer.
4. Open: the campaign's watch seeds move between clean and the budget class F from run to run (118, 678, 707, 241 between two runs of the same code). That is timing inside the lane; removing it means a deadline the harness counts in steps, which is a change to the harness.
5. Decided (owner, 2026-11-16): the left-out tests stay in the sources. No cut into `tools/test-archive-*`: it would change about 187 test classes that the unmerged fouling branch and `main` also change.
6. Open: `AGENTS.md` still names a 200-seed quick check for comparing forms, which is now larger than the campaign.
