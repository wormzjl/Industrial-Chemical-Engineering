# Rules: Gradle, measurement and verification

The full rules of the `AGENTS.md` section "Gradle, measurement and verification", with the same force. `AGENTS.md` keeps the core and says when to read this file.

- At most two Gradle invocations run at a time on the machine, each in its own worktree; the final lane may be one of them. Never run a test suite while a dev client is running.
- Worker threads: a large solver campaign 8 to 10, never beside another of that size; a run on the second Gradle lane at most 4; the final lane's test step 8 (`LANE_WORKERS`).
- Record time only in a performance optimization task; a timed run runs alone. In every other task judge cost by counters, in wall-time cost gates and campaigns too, and report no times. Replay a wall-clock budget hold alone before you count it.
- The test tasks are `test` (the quick suite), `holdGates`, `columnGates` and `equipmentGates`. Outside the final lane, every run names its test classes with `--tests`. `check`, `build` and `slowGates` are the lane's alone; `assemble` builds the jar.
- `columnGates` and `equipmentGates` hold the column and equipment tests, which `test` leaves out, and see only what their tests load. When one fails with a class or resource missing, add it to the group's list in `build.gradle`.
- The final lane is `bash tools/merge-lane/lane.sh head`; `tools/merge-lane/README.md` holds its steps and options.
  - It runs once per batch, when the code is final, on the test classes the batch's changes can reach (`tools/merge-lane/affected.mjs`).
  - References are read there, once: the 33 junction lines, the printed lines and `MIXED_GAS_COST` when their tests are selected; chain-100 exact, the fluid GameTests and the campaign when what they load changed. A reference whose test the lane skips stands from the baseline lane.
  - Do not run a second lane. Where the owner asks for one, it is the same command and runs only what changed since the first. Do not copy the lane into a batch's tool folder.
  - The full suite (`lane.sh head --all`, `lane.sh base`) runs only when the owner asks, with `LANE_FULL=owner`. Never propose one; none runs on a schedule, before a merge or after one.
  - The lane measures and prints no time, and ends within 8 minutes with every step due. A batch whose new tests would take it past that moves less important tests to `tools/merge-lane/left-out.txt` in the same batch: the costliest that never failed in the archived lanes first, each with its record, named in the batch's review. A listed test that fails in any run leaves the list. Listed tests stay in the sources; a `--tests` run of their class runs them.
- Use the least measurement that decides the task:
  - Compare at most three forms, on the 200-seed quick check (all distributions), then choose one.
  - Run one campaign, for the chosen form only: the watch seeds of `tools/merge-lane/campaign-seeds.txt` (`lane.sh head campaign`, 8 workers). A seed that holds in any run joins the list; the list stays under 200 seeds. The 1000 seeds of each distribution run only when the owner asks (`LANE_FULL=owner LANE_SEEDS=1-1000`).
  - Do not replay worse seeds alone. Replay alone only new budget-class holds (classes C and F not in the baseline), batched in one run.
  - List worse worlds by class with the campaign's classifier. Minimize and trace at most the two smallest new ones. Split a hold class by mechanism only when the task targets that class, on the final code, with the existing classifier script.
  - A fuzz seed stops at its first hold (both hold-fuzz harnesses). A held seed's one replay alone, in the lane, is its measurement. Do not replay it again to confirm a fix, bisect it or trace it: diagnose from the lane's trace, the code and git history. Its next run is the next final lane's.
  - Take counters from the campaign. No separate per-seed Newton runs.
  - Never run a test with its fix or rule disabled to prove the fix. The failure seen before the fix is the proof.
- A report's summary is at most 50 lines. A findings table may follow it.

### No repeated tests

- A pass stands until the test or something it loads changes. Read stored results first (`build/test-results`, the lane's output, the batch review); if they answer the question, do not run.
- Before every test run, list the files changed since the last pass and run only the tests that can load them. If there are none, do not run.
  - Documents, `tools/`, comments, `AGENTS.md`, `CHANGELOG.md`: no test run.
  - A test removed, or test code left without a user removed: `compileTestJava` only. Prove the removal from the test names in the stored results.
  - A test added or edited: that test class.
  - Production code: the tests of the changed classes and the gates that name the rule.
- Take the test classes from `node tools/merge-lane/affected.mjs select` (after `testClasses`), not from a hand list. A `--tests` filter names test classes, never a package tree (`*`, `*Test`, `com.wormzjl.icheme.<package>.*`).
- Do not run a whole task to confirm a fix, between commits, or after a cleanup or an archive. Gradle reruns a whole task when one of its files changes, so narrow every run with `--tests`.
- Do not force a result again: no `--rerun-tasks`, `--no-build-cache`, `cleanTest` or deleted `build/`. An up-to-date or restored task counts as run. The one exception is a replay this file asks for (a wall-clock budget hold): `--rerun` on that one filtered test task.
- `tools/agent-guard` blocks the commands this section forbids; its README lists them.
