# Column failure modes

Purpose: the test world of the running column's failure-mode scheme, the evidence of its full-size run, the dropped downcomer-backup draft, and the scripts of the batch's merge preparation.

Batch: `documentation/2026-11-10-column-failure-modes/` (plan `COLUMN_FAILURE_MODES_PLAN.md`, stages in `EXECUTION_PLAN.md`, the owner's cases in `MANUAL_TEST.md`).

## How to run

The test world's code stays in `src/fluidGameTest/` (`ColumnFailureModesReviewWorld`, batch `review-failure-modes` of the namespace `icheme_review`). The review-world server builds both plants at full size, runs the 13 cases at 20 TPS and stops (about 15 minutes). Run it alone on the machine, with no dev client open:

```text
./gradlew runReviewWorldServer -PreviewBatch=review-failure-modes
```

- The run prints one `review_failure case=<n> expected=… got=…` line per case, then `review_failure end plantA=… plantB=… held=…` and `summary missed=[…] failure=…`.
- The server's world is `run/review-world/world`. To hand it to the owner, copy it to `run/saves/column-failure-modes` (move the previous copy aside first).
- Without `-PreviewBatch` the task runs every review batch (`ReviewBatch.skipped` filters by the property).

## Entry points

| Path | What it is |
|---|---|
| `results/full6-case-lines.txt` | The case lines and the end line of the full-size run of 2026-11-10 (code at `4b0fdd8a7`), the run the handed-over world was saved from. |
| `w34-downcomer-backup/w34.patch` | The dropped W3.4 draft: downcomer backup as a second flood limit in `V3TrayHydraulics`, with its test. Never committed. Applies to `bf5741da0` (`git apply`); it also applies after the merge of the shader branch (`16829d22e`). |
| `merge-prep/resolve.js` | Resolved the four text conflicts of the merge of `claude/custom-shader-system-caff9e` into this branch (`16829d22e`). One-off. |
| `merge-prep/renumber.js` | Renumbered this batch's fluid facts schema mentions from 17 and 18 to 21 in that merge. One-off. |
| `merge-prep/resolve-main.js` | Resolved the merge of main 0.25.0 into this branch (`33d018c0f`): two code guide snapshot lines, and this batch's CHANGELOG lines moved under `[Unreleased]`. One-off. |
| `merge-prep/apply-tr.js` | Wrote the translation commit (`0893d2339`) from per-locale work files in en_us key order. Reusable with new `tmp/<name>/tr-<locale>.json` files after a path edit. |

Logs: the stage and review-world logs are archived as `logs/column-failure-modes-logs-2026-11-10.tar.gz` (local, never staged). The archive was written in the batch's worktree; its place is the main checkout, at the same path.

## History

- 2026-11-10: W3.4 (downcomer backup, plan decision D9) drafted in stage 2 and stopped: the fixed 40 mm apron clearance moved the crude sample from 8 m to 10 m and eight pinned tests. The owner dropped it ("if too expensive just use the original plan"); flooding stays Fair's fraction alone.
- 2026-11-10: six full-size runs of the review world in stage 5. Runs 4 and 5 ended with held fluid islands (plant A's off-gas round-off, fixed in `40428c5e1`; plant B's flashing distillate, a fluid-solver item recorded in the batch README, the plant changed to a 2 bar rundown in `4b0fdd8a7`). Run 6 ended with both plants RUNNING and no held island.
- 2026-11-10: merge preparation. The shader branch merges to `main` first; it was merged into this branch (`16829d22e`), its fluid facts schemas 17 to 20 stand, and this batch's facts became one schema 21 (corpus image `45039eac9`).
