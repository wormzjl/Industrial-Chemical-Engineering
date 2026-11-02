# Test suite and merge lane: what a merge costs, and what changed (2026-11-02)
Owner: "every time a merge lands a full suite is required and it takes too long, test numbers and runtime needs to be minimized"; then "how about not running column test at all when nothing is changed". Tree `a3720726c` (0.18.1): 2071 test runs of 1991 test methods. Code: `c123e20d9`; tests archived in `c1b35ce2d`.

## What a merge cost (head side, every merge, lanes of 2026-11-02)
| Step | Before | Why |
|---|---|---|
| `test slowGates --rerun-tasks` | 267 to 364 s | `--rerun-tasks` forced every task; a change anywhere reruns every test, because every test's input is the whole mod |
| chain-100 exact | 3 s | |
| Fluid GameTests | 249 to 270 s | one test took 226 s: it slept each tick to hold the server to 20 ticks a second |
| Campaign | 402 to 494 s | run for every merge, even one that changes nothing it loads |
| **Total** | **15 to 19 min** | the base side adds about 11 min when no archived baseline fits |

The time is not in the test count. 20 tests took 197 s of the 361 s of test time; 1857 tests took 20 s.

## What changed
- **`columnGates`, `equipmentGates`.** The column tests load 115 source files and the equipment tests 113 (`-Xlog:class+load`). Each group's task sees only those files and their resources, so a change anywhere else leaves it up to date or restores it from the build cache. It does not run at all. A static dependency walk gives 97 %, because test helpers reach the network layer; only the JVM's own record is usable.
- **Safe by construction.** A test's result can depend only on what its JVM loads. A change that reaches a new file must change a file already in the set, which reruns the group, and the file left out then fails loudly. The lists caught three missing resources this way during the build.
- **`slowGates` is an aggregate** of `holdGates` and the groups, so they run side by side. `test` stays independent of the groups: campaigns run `test --tests`, and a group started beside them would disturb their wall deadlines.
- **The C++ library counts by its sources.** An MSVC build stamps its DLL, so its bytes would rerun every test after every rebuild.
- **GameTest lockstep.** `paced()` slept 50 ms a tick because an unthrottled server ran ahead of the solves (`1352b885`). It now holds a tick only while a solve runs on a worker. The batch takes 5 s for 226 s.
- **`tools/merge-lane/lane.sh`** replaces a copy per batch (24 lane scripts in `tools/`). It drops `--rerun-tasks` and skips the campaign when nothing in `campaign-inputs.txt` changed since `main`. Extending the owner's rule to the campaign is my decision; `--all` overrides it.

## Result
| Case | Before | After |
|---|---|---|
| Lane, nothing it depends on changed | 15 to 19 min | **50 s** (measured) |
| `test slowGates`, every task forced | 267 to 364 s | **153 s** (measured, alone) |
| Fluid GameTests | 249 to 270 s | **31 to 34 s** (measured) |
| Lane, worst case with the campaign | 15 to 19 min | 10 to 11.5 min (from the steps) |

Replay of the 21 releases since `f91c992d`: `columnGates` would have run on 7, `equipmentGates` on 14, the campaign on 14. By the step costs that averages about 8 minutes a lane against about 16 (an estimate, not a measurement). The campaign is now most of what remains.

## Proof
- Every test still runs: 2071 runs over 2033 names before and after, no name's count differs (against `focused-raman-3bbde7`'s results at the same commit).
- Skips hold both ways: a real change in a client class left both groups up to date; a real change in `V3PumparoundSpec` ran both; reverting it restored both from the cache in 4 s.
- A second worktree restored both groups from the cache in 5 s, with its own DLL build.
- Results unchanged: of 8554 printed lines, the only differences are wall times and allocated bytes. Every `worstMoles`, `worstEnergy`, `junctionShare` and `newtonSolves` matches, and so do the 33 junction lines. `MIXED_GAS_COST`: 299 Newton solves and 94.2 MB in both. Chain-100 exact: 0.
- Fluid GameTests 38/38. The column's printed values match the 20 tick run to the digits it logs (feed 46.0465, taken 334.399, condenser -2.46955e+06 W, warm 26.7287 K, accounts 7556.47).
- `holdGates` holds its wall budget beside the groups, with all four tasks side by side.

## Test count: 20 archived, 11 kept
Five Opus reviews of the 558 methods added since the last review ([parts/](parts/), bar in [CRITERIA.md](CRITERIA.md)) proposed 31 removals, 1.5 % of the suite, saving 2.25 s. The owner approved the 20 firm ones; `c1b35ce2d` archived them to [tools/test-archive-2026-11-02/](../../tools/test-archive-2026-11-02/README.md). The suite went from 2071 to 2051 runs: exactly those 20 names, none added, 0 failures (`test` 1298, `holdGates` 66, `columnGates` 512 up to date, `equipmentGates` 175). The 11 weak ones stay: `aTanksRoofAndLadderAreDrawnApart`, `anOpenRouteCostsTheSearchAboutOneNodePerBlock`, `aPumpKeepsItsArrowsAtEverySize`, `eligibilityAndMeshesCoverBothAxesHeadingsAndDiameterExtremes`, `futureTargetsUseHeightGeometryAndRetainBootDatum`, `housingIsClosedAtObliqueAnglesAndEveryConnectorRemainsFinite`, `aLabelDrawsOnlyWhereItFitsAndIsAnchored`, `thePaletteFillsItsGap`, `aSkinDrawsTheMarksTheBarsAndTheTonesItsRowsName`, `heatingAndEitherFlowSignGiveTheSameMarch`, `bundledCrudeFractionsDarkenWithNormalBoilingPoint`. Several rest on a person reading software renders, which no gate runs. The count falls little, and that is the true number.

## Open items
- Column time, not applied: 12 s SAFE and 26.8 s OWNER in [parts/column-time.md](parts/column-time.md). Skipping saves more; the memo now works, as `columnGates` keeps every column test in one JVM.
- Geometry caches hold 64 entries, and `everySize()` overflows them, so each sweep rebuilds what the last one built: about 20 s when the group runs. It needs a production switch, so it is left.
- `HoldClassWorldsTest` keeps a real 2 s wall budget (`EventPathWorld`), so a heavily loaded machine can still hold it. `ExchangerCouplingTest` uses 30 s.
- Open branches: a new test in a group that loads a class outside its list fails loudly until the list is extended. The cooling tower branch will need this.
- Test-only classes left `src/main` in `0c88691e5` (22 affected tests, 0 failures; `main-reach.pl` finds none left). Still there: `FlowsheetPainter.rounded`, no caller; the 12-argument `V3ColumnInput` constructor, 16 test callers and no main caller.
- Code guide: verification, fluid-runtime, equipment, pipe-laying, column-v3, process-control. Tool folders: `tools/merge-lane/`, `tools/test-archive-2026-11-02/`.
