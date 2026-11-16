# Pollution system: merge review

Up: [batch README](README.md)

## Summary

The final lane ran once on `claude/pollution-system-a8e213` at `a2ea53029` (tree clean), 2026-11-16, as `LANE_WORKERS=4 bash tools/merge-lane/lane.sh head`, by the main thread (owner: "Run it here"). Output: `tmp/merge-lane/head/`. It found two defects of the batch. Both are fixed in `73783db02`, and the two steps they failed were run again on that commit. Every other result stands from the lane.

- **Tests:** 3,822 tests in 600 classes, 1 failure (`SimulationTimeTest`, see below). After the fix that class passes (3 tests).
- **chain-100:** exact (every deviation 0).
- **Fluid GameTests:** the server stopped at mod loading in the lane (see below). After the fix: 106 of 106 pass (run id `lane-1116b`), the eight pollution tests among them; the log holds no `pollution status=` failure line.
- **Campaign:** every new hold is a wall-deadline hold that was clean on its one replay alone.
- **Status:** ready for the merge. Proposed version: 0.32.0 (a new feature); the owner chooses.

## Selection

- `build.gradle` and `gradle.properties` changed: no test selected for them. The `gradle.properties` rule changed in this batch (owner, 2026-11-16: "gradle.properties change should not trigger full test"; `a2ea53029`). A first lane start with the old rule was stopped before it finished; nothing of it is kept.
- `test` 385 of 482 classes (the filter list was too long, so the task ran whole), `holdGates` 10 of 10, `columnGates` 79 of 87, `equipmentGates` 26 of 29, chain-100 due, GameTests due, campaign due.
- Why so many: 327 classes load a changed source (most of them `MaterialCatalog`, which gained the hazard record), 91 use a changed constant, 42 read a changed data or lang file. 18 classes had no load set (new in this batch).
- The lane saved 595 load sets at `a2ea53029`.

## The two defects

| Step | What failed | Cause | Fix (`73783db02`) |
|---|---|---|---|
| GameTests | Mod loading failed with a `NullPointerException` at config creation | `pollution.mapOverlay` (WP9) used `defineInList` with `List.of(...)`; NeoForge tests the missing value `null` against the list, and `List.of` throws on `contains(null)` | The list is `Arrays.asList(...)` |
| `SimulationTimeTest` | "tick-to-second conversions outside SimulationTime: `PollutionEffects.java:239`" | The damage per effects pass was `perSecond * EFFECT_TICKS / 20.0` | `SimulationTime.playSeconds(EFFECT_TICKS)`: the same value, in play seconds (damage does not scale with the time factor) |

- The first defect stopped every server and client with a new config file. No unit test creates the config, so only the GameTest server showed it.
- After the fix, run again: `SimulationTimeTest` (with `DeterministicMathSourceTest`) and the GameTests. Not run again: the other classes that load `IChemE.java` or `PollutionEffects.java`. The change to each is one expression with the same value; the GameTest server loads both.

## Named gates

`LanguageFilesTest`, `UnitTextTest`, `FluidFactsCompatTest`, `FormatsTableTest`, `LayeringTest`, `TutorialTextTest`, `DeterministicMathSourceTest` and every pollution test class pass in the lane (`tmp/merge-lane/head/results/`).

## References

- **chain-100:** exact.
- **Junction lines and `MIXED_GAS_COST`:** 33 lines; `MIXED_GAS_COST` 313 Newton solves, 125.0 MB (the baseline review gives 313 and 125.1 MB). The baseline lane's folder (batch `2026-11-15-fluid-step-cost`, at `4ee2cb0f8`) no longer exists, so the 33 lines were not compared one by one. The batch changes no solver code.
- **Printed lines:** 9,141 lines, not compared for the same reason.
- No reference was re-recorded.

## Campaign (factor 4, 1000 seeds, 8 threads)

The baseline is the head lane of `2026-11-15-fluid-step-cost`, from its `MERGE_REVIEW.md`. The lane shared the machine with the `pipe-fittings` worktree's lane.

| Run | Clean, baseline lane → this lane | After replays | New holds | Clean here, held in the baseline lane |
|---|---|---|---|---|
| cold random | 991 → 991 | 992 | 118 F | 826 F |
| bundled random | 993 → 992 | 994 | 12 F, 59 F | 47 F |
| bundled defaults | 1000 → 1000 | 1000 | none | none |

- **Replays:** the three new F holds (wall deadline) were replayed alone once on one thread, with no other test JVM on the machine. All three are clean: cold 118 ends at tick 14651, bundled 12 at 14096, bundled 59 at 13446.
- **Held again, not replayed:** cold 6 and bundled 707 (F). Both were new in the baseline lane and clean in its replay.
- **Standing holds:** cold 431 (F) and 65, 101, 629, 752, 803, 919 (E5); bundled 579 and 941 (F), 241 (E6), 145 and 431 (E9). Refusals 0 on every run.

## Merge preparation

| Item | State |
|---|---|
| `CHANGELOG.md` | One line under `[Unreleased]` (`a7c48e96a`) |
| Version | 0.32.0 proposed; the owner chooses at the merge |
| Translation commit | `0b6f67249`: 53 keys in each of `zh_cn`, `fr_fr`, `es_es`, `ja_jp`; `check.js` OK for all four |
| Code guide | `pollution.md` (the map overlay, snapshot line), the README row, three snapshot lines (`a7c48e96a`); `verification.md` for the lane rule (`a2ea53029`) |
| Tooling cleanup | `git ls-files -i -c --exclude-standard` is empty; `main-reach.pl` names no pollution class; no tool folder (the batch made no probe or rig) |
| `FORMATS.md` at the merge | Drop the `icheme-2` reservation row of `main` (`b41f7f9c7`); this branch's rows replace it |

## Not proved

- The FTB Chunks overlay on a client with FTB Chunks installed (look, tooltip, the open-map check). No test loads those classes.
- A breached vessel at 20 bar, or one that holds a superheated liquid, in a live island.
- The sounds in game (the owner's picks are open).
