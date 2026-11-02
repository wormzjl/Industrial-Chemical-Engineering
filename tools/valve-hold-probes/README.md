# valve-hold-probes

Purpose: trace a column outlet's held job in the review world, and replay it on the job path with one change each.

Batch: `documentation/2026-11-02-valve-hold/`.

## How to run

In a scratch worktree that merges `claude/valve-hold` and the column branch, from the worktree root:

1. Apply `tools/valve-hold-probes/held-dump.patch`. It logs every island on `HELD: Substep` as `HELD_DUMP`, with node states and pipes.
2. Copy `trace/TraceDump.java` to `src/main/java/com/wormzjl/icheme/science/fluid/solver/` and `trace/LeadProbe.java` to `src/main/java/com/wormzjl/icheme/runtime/fluid/`. Make `ROOT` in `LeadProbe` and `FILE` in `TraceDump` absolute: the world server runs in `run/`.
3. Run `perl tools/valve-hold-probes/trace/add.pl`, then `perl tools/valve-hold-probes/trace/add2.pl`.
4. Make `tmp/trace/`, then run `./gradlew runReviewWorldServer --offline > tmp/trace/run.log`, about 3 minutes.

`final-lane/lane.sh base` and `head` run the final lane, one Gradle run at a time, in the two scratch worktrees named at its top (`gates-base-scratch`, `gates-head-scratch`). It takes the hold-fuzz harness and the classifiers from the head worktree's `tools/`. `final-lane/linediff.pl <base> <head>` gives the largest number change per test between two `printed.txt`. `final-lane/replay183.sh` replays cold seed 183 alone on both sides; the worktree names and the seed are at its top. Change the names at the top of both scripts for other worktrees.

## Entry points

- `TraceDump.write`: each singular matrix, with its unknowns, node states and a full-pivot elimination. Writes `tmp/trace/singular.txt`.
- `LeadProbe.level`: each job's junction temperature, pressure and trace shares. Writes `tmp/trace/levels.txt`.
- `LeadProbe.run`: on a held job, replays its snapshot and committed interval through `RetainedSolver`: unchanged, 20 K colder, nitrogen removed, nitrogen at 1e-8. Writes `tmp/trace/leads.txt`.

The sources need the column branch's `PortPlan`, so they do not compile on `main`.

## History

- 2026-11-02: written for the valve hold as `OutletProbeTest`, which found the liquid outlets held and that a set point was read as a new network. It ran one solver with no `replayStart`, off the production path, and is removed; `git show 75833c66c:tools/valve-hold-probes/OutletProbeTest.java` has it.
- 2026-11-02: `TraceDump` and `LeadProbe` found the outlet holds' cause (`OUTLET_HOLD_REVIEW.md`). The scripts that tried the fix and its rule-off gate runs are in `git show 59080795c:tools/valve-hold-probes/trace/`; the fix is on the branch as c39e2bcd3.
- 2026-11-02: `final-lane` ran the lane of `FINAL_LANE_REVIEW.md`, adapted from `tools/heat-exchanger-design/merge-lane`.
- 2026-11-02: `final-lane` ran the replacement lane from the branch's own worktree: `HEAD_WT=<worktree> RUNID=<fresh id> bash tools/valve-hold-probes/final-lane/lane.sh head`. The base logs stood, main's `src/main` not having moved.
- Logs: `valve-hold-logs-2026-11-02.tar.gz`, `valve-hold-fix-logs-2026-11-02.tar.gz` and `valve-hold-lane-logs-2026-11-02.tar.gz` in the main checkout's `tools/valve-hold-probes/logs/`, local only. The first review world's logs were lost at cleanup.
