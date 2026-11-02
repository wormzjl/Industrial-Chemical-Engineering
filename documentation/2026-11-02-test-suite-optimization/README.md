# 2026-11-02-test-suite-optimization

Purpose: shorten what a merge costs. Tests that cannot be affected by a change do not run, the fluid GameTests stop sleeping, and one tracked lane replaces a copy per batch.

Status: In progress since 2026-11-02, on branch `claude/test-suite-optimization-8fbeab`. Not merged, not pushed. Version proposal: 0.18.2, a patch. Index: [../INDEX.md](../INDEX.md).

## Documents

- [TEST_SUITE_REVIEW.md](TEST_SUITE_REVIEW.md): what a merge cost, what changed, the proof, the open proposal.
- [CRITERIA.md](CRITERIA.md): the bar of this pass, on top of the [2026-10-21 criteria](../2026-10-21-test-review/CRITERIA.md).
- [parts/](parts/): the five reviews (runtime, equipment and assembly, client and network, science, column run time).

## Code areas

- `build.gradle`: `gateGroup`, `columnGates`, `equipmentGates`, `holdGates`, `slowGates` as an aggregate, the `natives/**` normalization.
- `ColumnOperationReviewWorld.Rig.paced`: lockstep with the solve workers.
- `AGENTS.md`: the gate groups, the tracked lane.
- Code guide: `verification.md`, `fluid-runtime.md`.
- Tools: [tools/merge-lane/](../../tools/merge-lane/README.md).

## History

- 2026-11-02: owner request. Timings and lane durations read from stored lanes, because another lane was measuring. Five Opus reviews. Owner: "how about not running column test at all when nothing is changed": `columnGates` built on the classes the JVM loads, `equipmentGates` the same way. Lockstep in `paced()`. Code `c123e20d9`. Gates: `test slowGates` 2071 runs, name by name equal to the baseline; fluid GameTests 38/38; chain-100 exact 0; the lane end to end in 50 s. The test removals wait for the owner.

## Open items

See [TEST_SUITE_REVIEW.md](TEST_SUITE_REVIEW.md), "Test count" and "Open items".
