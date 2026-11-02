# 2026-11-02-test-suite-optimization

Purpose: shorten what a merge costs. Tests that cannot be affected by a change do not run, the fluid GameTests stop sleeping, and one tracked lane replaces a copy per batch.

Status: In progress since 2026-11-02, on branch `claude/test-suite-optimization-8fbeab`. Not merged, not pushed. Version proposal: 0.18.2, a patch. Index: [../INDEX.md](../INDEX.md).

## Documents

- [TEST_SUITE_REVIEW.md](TEST_SUITE_REVIEW.md): what a merge cost, what changed, the proof, the archived tests.
- [CRITERIA.md](CRITERIA.md): the bar of this pass, on top of the [2026-10-21 criteria](../2026-10-21-test-review/CRITERIA.md).
- [parts/](parts/): the five reviews (runtime, equipment and assembly, client and network, science, column run time).

## Code areas

- `build.gradle`: `gateGroup`, `columnGates`, `equipmentGates`, `holdGates`, `slowGates` as an aggregate, the `natives/**` normalization.
- `ColumnOperationReviewWorld.Rig.paced`: lockstep with the solve workers.
- `AGENTS.md`: the gate groups, the tracked lane, "No repeated tests".
- Code guide: `verification.md`, `fluid-runtime.md`.
- Tools: [tools/merge-lane/](../../tools/merge-lane/README.md), [tools/test-archive-2026-11-02/](../../tools/test-archive-2026-11-02/README.md).

## History

- 2026-11-02: owner request. Timings and lane durations read from stored lanes, because another lane was measuring. Five Opus reviews. Owner: "how about not running column test at all when nothing is changed": `columnGates` built on the classes the JVM loads, `equipmentGates` the same way. Lockstep in `paced()`. Code `c123e20d9`. Gates: `test slowGates` 2071 runs, name by name equal to the baseline; fluid GameTests 38/38; chain-100 exact 0; the lane end to end in 50 s. The test removals wait for the owner.
- 2026-11-02: owner: "Archive the 20". `c1b35ce2d` removed the 20 firm methods, with the two imports and the `link` helper only they used; the 11 weak ones stay. Gates: `test` 1298, `holdGates` 66, `equipmentGates` 175, `columnGates` up to date, 0 failures; 2071 to 2051 runs, exactly those 20 names, none added. No main code changed, so the fluid GameTests, chain-100 and the campaign did not run. Owner: "why you are doing tests that are already done?": that `test slowGates` reran tests that had passed on the same product code; the compile and the 10 touched classes would have decided it. Decision: `FlowsheetPainter.rounded`, left with no caller, stays, as an open item. Owner: "Change agent.md and prevent running repetitive tests completely": `AGENTS.md` gains "No repeated tests"; "Run both before a merge" and the cleanup's "Re-run the gates" are gone. This task ran no further tests. Owner on a Gradle guard that would refuse a whole run outside the final lane: "Rule is enough"; the build stays as it is. Owner: "Sync it to other active worktrees": the section went to the three worktrees with an open session and unmerged work (`a137fbdc9` process simulator, `d3949dfc2` distillation integration, `f06ba64d7` mod logos), each commit `AGENTS.md` only, and the two busy sessions were told. A merge of this branch after one of them has one `AGENTS.md` conflict: keep the `columnGates` line. Owner: "Yes, merge the rule": the section reached `main` alone in `a6ecf6312`, a documentation-only merge of `AGENTS.md`, not pushed. The three synced branches merge `main` with no `AGENTS.md` conflict; this branch keeps the one above.

## Open items

See [TEST_SUITE_REVIEW.md](TEST_SUITE_REVIEW.md), "Open items".
