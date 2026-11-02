# merge-lane

Purpose: the one final lane before a merge to `main` (AGENTS.md, "Gradle, measurement and verification"). Batch: [`2026-11-02-test-suite-optimization`](../../documentation/2026-11-02-test-suite-optimization/README.md). It replaces the copy of `lane.sh` that each batch made, with its fixed worktree paths.

## How to run

From the branch's worktree, with no dev client and no other Gradle run (the campaign's wall deadlines must not share the machine):

```bash
bash tools/merge-lane/lane.sh head
```

A step list after the side runs only those steps, for example `lane.sh head campaign`. `--all` runs the campaign even when none of its inputs changed. `base` runs `test slowGates` and the campaign on main, in a scratch worktree under the main checkout's `.claude/worktrees/`. An archived baseline of the same code serves: then run only `head`.

## What it skips, and why that is safe

- `columnGates` and `equipmentGates` see only what their tests load, so Gradle leaves them up to date or restores them from its build cache. A test's result can depend only on what its JVM loads. A file outside the set cannot reach the test, and a change that would reach a new file must change a file inside the set, which reruns the group.
- The campaign runs only when a path of `campaign-inputs.txt` differs between the branch and its merge base with `main`.
- The lane does not pass `--rerun-tasks`.

## Entry points

- `lane.sh`: the steps. Logs, results, the printed lines (`printed.txt`, by `tools/floating-roof-tanks/final-lane/printed.sh`), the 33 junction lines (`junction.txt`) and the campaign reports land in `tmp/merge-lane/<side>/`, or `$LANE_OUT/<side>`.
- `campaign-inputs.txt`: the campaign's inputs as path patterns, one per line. A test JVM loads 206 production sources for a run of the campaign, some only because JUnit reads every test class. The list keeps the packages the solver needs. It is wider than the loaded set on purpose: a rarely loaded class in these packages must still run the campaign.

## History

- 2026-11-02: written from `tools/ph-false-root/lane.sh` and `tools/heat-exchanger-design/merge-lane/lane.sh`. Inputs measured with `-Xlog:class+load` on 80 seeds, cold and bundled.
