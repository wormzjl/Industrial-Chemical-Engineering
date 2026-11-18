# 2026-11-18-ground-pollution

Status: in progress since 2026-11-18, on `claude/water-intake-outfall-0e90c8` (not merged).

Purpose: ground pollution stored by chunk column, with a compound inventory, spread, slow decay, the map and meter, and world effects.

## The owner's request (2026-11-18)

"store ground pollution on a chunk basis instead of 16block cubes, and expand its function" ("Yes, queue it").

## Documents

- [GROUND_POLLUTION_PLAN.md](GROUND_POLLUTION_PLAN.md): the model, every constant, the steps.
- [GROUND_POLLUTION_REVIEW.md](GROUND_POLLUTION_REVIEW.md): the build, the numbers, the options taken, G5 held, not seen in a client.

## History

- 2026-11-18: G1 (`93537c4f0`) `GroundField`, `GroundSettings`, `GroundLedger`, `GroundEnvironment`; settling returns deposits by column.
- 2026-11-18: G2 (`a911c9dd0`) the engine pass carries the ground; `PollutionStore` schema 2; `GroundSnapshot`; one spill law in `Releases`.
- 2026-11-18: G3 (`d3e3083ec`) the map answer's ground bytes, `PollutionMapStore.Layer`, the meter's and the ledger command's ground lines.
- 2026-11-18: G4 (`966bee2c0`) `GroundGrowth` and `PollutionEffects.water`, configs `pollution.groundGrowth` and `pollution.groundWater`.
- 2026-11-18: G5 held at the stop rule (the generator contents path the solute batch changes); options sent. Documents.

## Code areas

- Science: `science/pollution/GroundField`, `GroundSettings`, `GroundLedger`, `GroundEnvironment`, `GroundSnapshot`, `GroundEffects`, `PollutionField` (settle), `HazardStandard.term`, `MapColumns` (layers).
- Runtime: `runtime/pollution/PollutionEngine`, `PollutionService`, `PollutionStore`, `PollutionCommands`, `PollutionEffects`, `PollutionMeters`, `PollutionMaps`, `GroundGrowth`; `runtime/fluid/Releases`, `FluidWorldAuthority` (the sink); `ProcessSolveJobs`.
- Client and network: `network/PollutionMapNetwork`, `client/pollution/PollutionMapStore`, `ColumnIcon`.
- Tests: `GroundFieldTest`, `GroundStoreTest`, `MapLayersTest`, `GroundEffectsTest`, `PollutionStoreCorpusTest` (`pollution-2.snbt`); GameTests `GroundPollutionGameTests`, `PollutionMeterGameTests`.

## Open items

- G5 delivery: main thread's choice of A, B or C.
- G3 layer key and the GuiPrototypes sheets; G4 tint: main thread's answers.
- Schema 2 reservation on main (FORMATS row).
- Not checked in a client: the overlay, the meter, growth and water effects in a live world.
