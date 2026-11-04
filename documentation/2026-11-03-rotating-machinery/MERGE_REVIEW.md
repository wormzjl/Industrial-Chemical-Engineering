# Rotating machinery: merge review

Branch `claude/realistic-pump-compressor-6fdfd9`, merged into main `fb9607dad` as 0.21.0. The owner said "Start W9", then "auto merge when done" (2026-11-05). This review covers W9: the translation commit, the tooling cleanup, the code guide, the CHANGELOG and version, and the final lane.

## 1. Merge contents

- **Translations** `7d19dc2eb`: zh_cn, fr_fr, es_es and ja_jp each get 154 lines added and 3 removed (the column door's pump keys). No line needed a rewrite, because the 25 lines whose English changed had already been rewritten by W4's commits.
  - `stale-translations.js` reports 0 missing and 0 stale in every locale.
  - zh_cn still flags 9 english_changed lines, all from before this batch, and every one still reads correctly.
- **Tooling cleanup** `388ea9c4c`.
  - `main-reach.pl` reports 583 of 583 classes reachable, so no whole class was test-only.
  - These method-level helpers moved to `src/test`: the `MachineColumn` samples, `SpeedControl.displacementSpeed`, `CentrifugalPump.efficiencyAt`, `SteamTurbine.steamFor` and `steamRate`, and `Motor.rating` and `ratingCount`.
  - These unused methods were removed: `CentrifugalPump.minimumFlow`, `ShaftTrains.deviceLine` and `governorTarget`, and `WorldTopologyLedger.Snapshot.withStores` and `withHealth`.
  - Tool folders: `tools/rotating-machinery/` (worked numbers, W2 traces and probes, the campaign scripts), `machine-sounds/`, `machine-parts/`, `pump-sprites/`, `machine-nozzle-probe/` and `heat-gui-renders/`, each with a README and an INDEX row.
  - Kept in `src/main` deliberately: `FluidWorldAuthority.publishedMover`, which a GameTest reads and GameTests cannot see `src/test`; and `ShaftCommand`, an operator debug command from the W3 plan.
- **Code guide:** see section 3.
- **CHANGELOG** `c6001b442`: one Added line for the batch (its commits, formats and protocols) and two Changed lines (the column door's pump keys, and the cleanup's detached code). `mod_version` goes from 0.20.0 to 0.21.0, minor, because this is a feature batch.

## 2. Final lane (`tools/merge-lane/lane.sh head`, at `c6001b442`)

- **`test slowGates`:** 2776 tests, 0 failures, 0 errors. All four tasks ran (test, holdGates, columnGates, equipmentGates), including `LanguageFilesTest`, `UnitTextTest` and `SimulatorColumnsTest`.
- **Junction lines:** 33. Newton solve counts per line and `MIXED_GAS_COST` (12 cases, 299 Newton solves) match the latest stored lane (`8396d8d98`). No lane of main itself is stored.
- **chain-100 exact:** 0 deviation in moles, temperature, phase fraction and flow.
- **Fluid GameTests:** 45 of 45 (run `w9-lane-1`).
- **Campaign, 1000 seeds, 8 workers,** against main 0.20.0's lane (`tmp/rotating-machinery/baseline/`):

  | Distribution | Clean (main) | Clean (branch) | Change |
  |---|---|---|---|
  | Cold random | 991 | 992 | 431 cleared |
  | Bundled random | 993 | 993 | 301 cleared; 896 held |
  | Bundled defaults | 1000 | 1000 | none |

  - 896 is W2's known hold (E5; D17, accepted by the owner as D18). 241 moves from F to E6.
  - The only F hold is seed 6, a baseline wall-deadline hold, so no new budget hold needs a replay.
  - Another session's `columnGates` (worktree `cleanup-stale-branches-worktrees-9bc4aa`) overlapped the bundled campaign from 01:16:51. Cost is judged by counters.

## 3. Code guide

`18836ab63`: a new chapter `rotating-machinery.md` (machine laws, shaft trains and drivers, FE, wear and repair, pages and sounds, the Create coupling, formats and config) with its README rows; fluid-runtime, client-gui, verification, fluid-science, equipment, process-simulator, heat-exchangers, cooling-towers, instrument-accessories, process-control, machine-assemblies and pipe-laying updated, each with its snapshot line (2026-11-05). Inconsistencies found and fixed in comments here: `ShaftTrain.Step` (the motor draw is booked, W5), `ShaftCommand` (W7 pages send the events), `CentrifugalCompressorDesign` (the driver bay replaces the drawn gear and motor). `CompressorDesign`'s "its motor by 0.1 m" is left for a reader who knows that drawing. Counts the guide had wrong before the batch were corrected (unit kinds 26, constructor choices 31, certificate identity 9).

## 4. Open after the merge

- Owner items:
  - W10a options O1 to O3 and the turbine's T6 and T7;
  - the listening check of the machine loops (D26, W10c);
  - the steam pocket trace (branch `claude/agitated-hopper-2cac2e`).
- Not tested, or checked only on renders:
  - the generator's FE push (untested);
  - the W6a outline look in game;
  - the W8 Feed page stream glass overflow (A173);
  - a compact pump table at 380 px (A174).
- Not pushed.
