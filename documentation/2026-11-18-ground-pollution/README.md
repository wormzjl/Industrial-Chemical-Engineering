# 2026-11-18-ground-pollution

Status: in progress since 2026-11-18, on `claude/water-intake-outfall-0e90c8` (not merged).

Purpose: ground pollution stored by chunk column, with a compound inventory, spread, slow decay, the map and meter, and world effects.

## The owner's request (2026-11-18)

"store ground pollution on a chunk basis instead of 16block cubes, and expand its function" ("Yes, queue it").

## Documents

- [GROUND_POLLUTION_PLAN.md](GROUND_POLLUTION_PLAN.md): the model, every constant, the steps.
- [GROUND_POLLUTION_REVIEW.md](GROUND_POLLUTION_REVIEW.md): the build, the numbers, the options taken, G5 held, not seen in a client.

## History

- 2026-11-18: G1 (`93537c4f0`) `GroundField`, `GroundSettings`, `GroundLedger`, `GroundEnvironment`; settling returns deposits by column (both reworked later the same day).
- 2026-11-18: G2 (`a911c9dd0`) the engine pass carries the ground; `PollutionStore` schema 2; `GroundSnapshot`; one spill law in `Releases`.
- 2026-11-18: G3 (`d3e3083ec`) the map answer's ground bytes, `PollutionMapStore.Layer`, the meter's and the ledger command's ground lines.
- 2026-11-18: G4 (`966bee2c0`) `GroundGrowth` and `PollutionEffects.water`, configs `pollution.groundGrowth` and `pollution.groundWater`.
- 2026-11-18: G5 held at the stop rule (the generator contents path the solute batch changes); options sent. Documents (`90d612159`).
- 2026-11-18: main thread's answers: schema 2 reservation skipped (owner, "Skip it"); G5 option A with C's status line now (`1370f9df8`); G4b, the owner's block changes, replace the tint (`874115b86`); G3 option A, the layer key (`1a62007f0`). Review rewritten.

## Code areas

- Science: `science/pollution/GroundSpoil`, `GroundField`, `GroundSettings`, `GroundLedger`, `GroundEnvironment`, `GroundSnapshot`, `GroundEffects`, `PollutionField` (settle), `HazardStandard.term`, `MapColumns` (layers).
- Runtime: `runtime/pollution/PollutionEngine`, `PollutionService`, `PollutionStore`, `PollutionCommands`, `PollutionEffects`, `PollutionMeters`, `PollutionMaps`, `GroundGrowth`, `GroundSoil`; `world/level/block/PollutedSoilBlock`, `registry/ModBlocks`, `ModItems`, `ModCreativeTabs`; data tags `icheme:pollution_spoils`, `icheme:pollution_kills`; `runtime/fluid/Releases`, `FluidWorldAuthority` (the sink); `ProcessSolveJobs`.
- Client and network: `network/PollutionMapNetwork`, `client/pollution/MapLayer`, `PollutionMapStore`, `PollutionMapClient` (the layer key), `ColumnIcon`, `client/ClientConfig` (`pollution_map_layer`); the intake's ground line in `FluidDeviceScreen`, `ViewBuilder`, `FluidWorldAuthority.groundWater`.
- Tests: `GroundFieldTest`, `GroundStoreTest`, `MapLayersTest`, `GroundEffectsTest`, `GroundSpoilTest`, `MapLayerTest`, `PollutionDataTest`, `PollutionStoreCorpusTest` (`pollution-2.snbt`); GameTests `GroundPollutionGameTests`, `PollutionMeterGameTests`.

## Open items

- G5 delivery: an open package after the solute batch's WP2 (design in the review).
- Owner items: the reading of "other blocks" (A vegetation, built; B also logs; C wider); the polluted soil texture (A, B or C); G3 option B (a map page of the mod's own).
- No pollution chapter in the Field Guide for the sentence the brief asked.
- Not checked in a client: the overlay and its key, the meter, growth, spoiling and water effects in a live world, the soil's look.
- Tools: `tools/heat-gui-renders/GuiPrototypes.pollutionMap`.
