# merge-lane

Purpose: the final lane of the heat batch's merge to main (batch [`2026-10-27-heat-exchanger-design`](../../../documentation/2026-10-27-heat-exchanger-design/README.md), review [MERGE_REVIEW.md](../../../documentation/2026-10-27-heat-exchanger-design/MERGE_REVIEW.md)), and a baseline of main run by the same script.

## How to run

From the branch's worktree, with no dev client and no other Gradle run (the campaign's wall deadlines must not share the machine):

```bash
git worktree add --detach <repo>/.claude/worktrees/heat-merge-baseline-scratch main
bash tools/heat-exchanger-design/merge-lane/lane.sh base
bash tools/heat-exchanger-design/merge-lane/lane.sh head
```

`base` runs `test slowGates` and the campaign in the main worktree; `head` runs `test slowGates`, chain-100 exact, the fluid GameTests and the campaign in this one. A step list after the side runs only those steps, for example `lane.sh head campaign`. The worktree paths are fixed at the top of the script.

## Entry points

- `lane.sh`: the steps. Results, the printed lines (`printed.txt`, by `tools/floating-roof-tanks/final-lane/printed.sh`), the 33 junction lines (`junction.txt`) and the campaign reports land in `tmp/merge-lane/<side>/`.
- The campaign: `tools/unified-thermo-port-forms/hold-fuzz-cold` and `hold-fuzz-bundled`, 1000 seeds, 8 workers, classified by `tools/phase-ports-probes/hold-fuzz/classify.sh`.

## History

- 2026-10-28: the merge lane at `cbefb793` (campaign `36d21cd2`) against main 0.16.2 `cc5da444`; results in MERGE_REVIEW.md. Logs: `tools/heat-exchanger-design/logs/merge-lane-logs-2026-10-28.tar.gz` in the main checkout (local, never staged).
