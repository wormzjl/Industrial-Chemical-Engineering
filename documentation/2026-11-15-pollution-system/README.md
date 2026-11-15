# 2026-11-15 pollution system

Status: In progress since 2026-11-15. D1 to D6 decided; WP1 to WP3 built and tested; WP4 in work; WP5, WP6, WP8 to WP10 not started. Branch `claude/pollution-system-a8e213`, cut from `main` at `e9edc974b` (0.31.0).

Purpose: air pollution in 16 × 16 × 16 cubes: toxic, combustible, corrosive and particulate hazard indices from what the fluid network releases, spreading and decaying on the 5 s engine, with effects on players and mobs and vapour cloud explosions at flame sources.

Lane: new packages `science/pollution` and `runtime/pollution`. Hot files touched, each in one work package: `MaterialCatalog` (WP1), `ProcessSolveJobs` (WP3), `ShaftTrains` and `FluidWorldAuthority` (WP4), `EquipmentStructure` and the equipment blocks (WP6). Class: new feature; no reference moves.

## History

- 2026-11-15, plan: survey of the release paths (`BoundaryTransfer` kinds, chimneys, flares), the material records (`flammability` exists, no model reads it), the explosion handling of equipment (ignored today) and the store patterns; the standard (NIOSH IDLH, GHS H314, LFL with Le Chatelier); [POLLUTION_PLAN.md](POLLUTION_PLAN.md). No code, no test run.

- 2026-11-15, owner: "also needs to be crash-proof when world corruption happens". Plan section 8: a damaged store is set aside and the dimension starts empty, a damaged cube is dropped alone, a run-time failure keeps the last field and at worst turns pollution off; the world is never refused for it.

- 2026-11-15, owner: "Yes, start". D1 to D6 decided as recommended (breach and repair; torches, candles and campfires ignite; a spill's volatile share; four channels per cube; half-lives 5 and 60 process minutes; no asphyxiants). WP1 (hazard sub-record, data, `HazardStandard`) and WP2 (`PollutionField`, ledger, `ExplosionLaw`) started as two Opus medium subagents in this worktree, on separate files, no builds.

- 2026-11-15, owner: "Pipe does not release under normal circumstance, unless they are destroyed by explosion (they don't get a damage meter for the sake of simplicity)". Plan S5 and section 7: a pipe releases its contents only when a blast destroys it, and a blast destroys it outright; breaking or dismantling by a player releases nothing (read by the main thread to hold for vessels too). S5 moves from WP4 to WP6.

- 2026-11-15, owner: "need a damaged pipe SFX as well". Plan section 7: a pipe a blast destroys plays `icheme:pipe_burst` (rupture, then hiss or gush by what it released), in WP6, by the `models-and-sfx` skill.

- 2026-11-15, owner: "also add a pollution meter item. Also research if you can add pollution overlay to ftb chunk map". Plan section 11: the meter (WP8; held = the request, action bar each second, a warning tick). Plan section 12: the research; FTB Chunks 1.21.1 has no layer API, but `MapIconEvent.LARGE_MAP` with zoom-dependent `MapIcon`s of scale 4 draws one square per chunk, and `registerMinimapComponent` a minimap line; proposed as WP9, an optional compat.
- 2026-11-15, WP2 reported: files written, not built. Its dense-grid estimate corrects the plan's plume numbers (source 0.9 after a pass and 2.1 at its peak, face neighbour 0.49, second ring 0.14); to carry into section 4 once the tests confirm them.

- 2026-11-15, WP1 and WP2 built by the main thread: both compiled at the first build; 58 tests in 9 classes pass (`science.pollution.*`, `MaterialHazardTest`, `MaterialThermochemistryTest`, `MaterialCatalogTest`, `DeterministicMathSourceTest`, `LayeringTest`). One test assertion was wrong and was corrected (a particulate source 8 cubes over the bottom thins out below the cutoff before it reaches the ground). WP1: `MaterialCatalog.Hazard`, the per-package arrays, 33 dataset records read from NIOSH, `HazardStandard`. WP2: `PollutionField` (amounts in doubles), `PollutionLedger`, `CubeKey`, `Environment`, `ExplosionLaw`. Not run: `columnGates` and `equipmentGates`, which load the material data (the final lane's).

- 2026-11-15, owner: "Yes, start WP3" (WP3 as an Opus high subagent, then WP4). WP3 started. Read for WP4 meanwhile: a safety valve's outlet is a relief port into a pipe run, never the open air (S9 is no source of its own); `ShaftTrains.booked` runs for committed and replayed spans, each its own stretch of time, so a release is a rate times the span; whether a fired heater's flue gas is a real stream is still open (S8).
- 2026-11-15, owner: "Add a fixed pollution meter that can be connected to data cables and can be read with the DCS as well". Plan section 11, "Fixed pollution monitor" (WP10): a one-block monitor with a data socket, four index channels and a gas alarm channel on the chimney's built-in channel pattern, a lamp and a horn; read on the DCS's instrument list; not a loop input in this batch.

- 2026-11-15, WP3 built by the main thread (`9d58d7ed4`): all four source sets compiled at the first build; 52 tests in 10 classes pass (`runtime.pollution` 22, `FormatsTableTest`, `LayeringTest`, `DeterministicMathSourceTest`, `ProcessSolveServicesRequestTest`, `CompletionWakeupTest`, `FluidIslandCommandTest`, `StillRuntimeTest`). `PollutionEngine`, `PollutionService`, `PollutionStore` (one store on the overworld for every dimension, images as `.snbt`), the pollution job as a fourth family beside the still's, the config block, `/icheme pollution`. Differences from the plan: a saved cube outside the dimension's height is not dropped (the codec does not know the height; it stays unloaded); a failed save writes the last good tag; a set-aside copy that fails leaves the file and turns pollution off for the session. Only a GameTest can prove the tick wiring, the cell read on real chunks, the set-aside through `DimensionDataStorage` and the disable after 3 failures. WP4 started (Opus medium subagent).

- 2026-11-15, owner on two of WP3's differences: "erase instead" (a damaged store that cannot be copied aside is erased and pollution runs on; it no longer turns itself off) and "cap at world height" (a cube outside the dimension's height joins its column's highest or lowest cube, at the load and for every release; read by the main thread as a merge, not a drop, so the ledger closes). `PollutionStore.open`, `PollutionService.dimension` and `release`, `PollutionField.capped`, `CubeKey.capped`; 55 tests in 8 classes pass (`science.pollution.*`, `runtime.pollution.*`, `LayeringTest`).

- 2026-11-15, owner: cubes outside the world's height are dropped, not merged ("cubes outside should be dropped": "yes"). At the load they are booked `dissipated`; a release outside the height is dropped (a chimney top above the build limit releases nothing into the field). `PollutionField.capped(field, ledger, …)`, `CubeKey.inside`, `PollutionService.release`; 55 tests in 8 classes pass.

## Documents

- [POLLUTION_PLAN.md](POLLUTION_PLAN.md): the standard, the release sources, the field and its step, the schedule, effects, ignition and blasts, saves, work packages WP1 to WP7, owner decisions.

## Open items

- Approval of the implementation threads for WP3 to WP6, WP8 and WP9 (WP9: the owner's go-ahead is open).
- WP3's notes: the code guide's line "a fluid island is admitted only while no job waits" does not match the code (`activeWorkers >= workerCount`), to correct in WP7; `equipmentGates` copies `compat/**`, so the new images put it out of date (the final lane's); a GameTest for the service is owed (with WP5's).
- WP1's notes: `tools/compound-import/import.mjs` may drop the `hazard` blocks on a re-import (not checked); carbonyl sulfide has no `flammability` record, so it is not combustible here; `MATERIALS.md` and the code guide do not name the sub-record yet (WP7).
- Three points to verify in the code before their package builds: where a removed device's contents are booked (S5), whether fuel voids have a flue stream (S8), where a safety valve's run ends (S9).
- The IDLH values of the plan's table are drafts; WP1 reads each from its NIOSH page.
