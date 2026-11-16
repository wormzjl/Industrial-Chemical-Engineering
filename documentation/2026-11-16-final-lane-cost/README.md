# 2026-11-16 final lane cost

Status: In progress since 2026-11-16. Branch `claude/final-lane-testing-optimization-7c599e`, on `claude/fouling-corrosion-system-ba0275` at `0892cbd6e` (owner: "Base yourself on claude/fouling-corrosion-system-ba0275"). Tools, `AGENTS.md` and documents only: a documentation-only merge, which reaches `main` with or after the fouling and corrosion batch.

Purpose: the final lane took about 28 minutes with every step due, several times a day. Owner, 2026-11-16: "Perform an aggressive optimization of this testing procedure. Give harder enforcement of test only relevant part policy"; then, in order: "for the 3x1000 tests remove all which have no tendency to fail at all and keep it below 200"; "your target is a test procedure done under 8 min instead of 28min"; "still too long, remove the least useful tests"; "if it exceeds 8min your job is not done"; "you should be able to tell without running the test again"; "no timing in this final lane, timing belongs to benchmark runs"; "For future: if an added test causes timing to increase above 8min, something less important needs to go" (now a rule of `AGENTS.md` and of `left-out.txt`).

Lane: `tools/merge-lane/`, `tools/agent-guard/`, `AGENTS.md`. No source, no test, no format.

## History

- 2026-11-16: study from the two fouling lanes' results, the load map and the archives of 15 final lanes (23 campaigns of each kind, 19 lanes of JUnit results). Findings and numbers: [`FINAL_LANE_COST_REVIEW.md`](FINAL_LANE_COST_REVIEW.md).
- 2026-11-16: built, by the main thread: the campaign's watch seeds (27); stamps for the GameTests and the campaign; filters in a file; `nativeSmoke` in the lane; constants from the class files; test classes sharing JVMs; `printed.mjs`; `compare.mjs` on the watch seeds and the printed lines; the guard's test rules; `left-out.txt`.
- 2026-11-16: five timed rehearsals of the whole lane on the first fouling lane's selection (`LANE_SINCE`), each alone on the machine: 815 s (12 workers, classes cut by method), 783 s (8 workers), 567 s (10 workers, the list at 10 s), 543 s (8 workers, the list at 10 s). 12 and 10 workers and the cut by method were dropped (review, section 3). Runs outside the lane: one compile and one five-filter smoke run of `record.init.gradle`.
- 2026-11-16: the list lowered to 1 s and every class to shared JVMs, by the model of the review (386 s; 421 s with the slowest campaign and GameTest runs seen), with no further rehearsal on the owner's word. The time-based parts (recorded test times, classes cut by method, tasks of their own for long classes) removed, and with them every time the lane printed.
- 2026-11-16: owner, "Archive the full suite and runs only when asked": the full suite (`lane.sh head --all`, `base`) runs only on the owner's word, with no periodic full lane; the left-out tests stay in the sources (owner's choice of three forms). `AGENTS.md`, the lane README, `left-out.txt` and the review, sections 5.1 and 5.5.
- Failing in the rehearsals for reasons outside this batch: `LanguageFilesTest`, until the fouling batch's translation commit.

## Documents

- [`FINAL_LANE_COST_REVIEW.md`](FINAL_LANE_COST_REVIEW.md): where the time went, what changed, what was dropped, what it costs.
- [`tools/merge-lane/README.md`](../../tools/merge-lane/README.md): the lane as it is.

## Code areas

`tools/merge-lane/` (`lane.sh`, `affected.mjs`, `record.init.gradle`, `compare.mjs`, `printed.mjs`, `campaign-seeds.txt`, `left-out.txt`, `campaign-inputs.txt`), `tools/agent-guard/` (`guard.js`, `guard.test.js`), `AGENTS.md`, the code guide's `verification.md` and `verification-campaigns.md`.

## Open items

1. The first real lane is the measurement of the final form. Over 8 minutes with every step due is a defect of this batch.
2. The shared load map (`tools/merge-lane/logs/` in the main checkout) is untouched: the rehearsals used a copy. The first lane with these tools records load sets with 8 classes to a JVM; `steps.json` starts empty, so the GameTests and the campaign run once against `main`.
3. Owner decisions in the review, section 5: timing in the campaign's budget class; the 200-seed quick check.
4. Scratch: `tmp/lane-study/`, `tmp/lane-smoke/`, `tmp/lane-rehearsal-*/` in this worktree, to delete when the batch is merged.
