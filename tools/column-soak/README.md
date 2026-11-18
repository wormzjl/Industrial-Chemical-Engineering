# column-soak

Stability soak of the default column test world (owner, 2026-11-18: "do a stability test on your side and see if
anything's drifting abnormally"). Not a gate; never committed under `src/`.

- `ColumnSoakReviewWorld.java`: a review GameTest (`icheme_review`, batch `review-column-soak`). It builds and starts the
  plant as `ColumnTestWorldReviewWorld` does (paced start), then runs it unpaced for 24 000 ticks
  (20 minutes of game time), held to 20 ticks a second of wall time. Every 100 ticks it writes each island's status, clock and solver counters to
  `soak-islands.csv`, and each non-pipe device's status, pressure, temperature, mass, flow, level and heat to
  `soak-devices.csv`, both in the game directory (`run/review-world/`). It only reads.
- `drift.py <dir> [window]`: fits a line per device and quantity over the last part of the soak (default the second
  half) and lists those beyond the bands: temperature 0.5 K/min, pressure 1 kPa/min and 1 %/min, mass, flow and heat
  1 %/min, level 1 cm/min, scatter 5 %. Per island: statuses, held samples, commit lag, rejected substeps.

Use the review source set and an empty, disposable game directory. The fresh feed batch and saved-world soak write `junction-lag.csv` alongside its existing measurements. Each row covers consecutive commits on the same charge island and topology. Feed energy comes from the column port's booked exchange. Adding the change in downstream junction stock reconstructs the energy discharged by the radiant coils. Component amounts follow the same balance. Both temperatures are pressure–enthalpy flashes at the feed pressure. The firing flag describes the endpoint. The feed ID distinguishes columns in a saved world. A missing window, including the first observation, supplies no measurement.

```powershell
.\gradlew.bat runReviewWorldServer -I tools/column-soak/unloaded-save.gradle -PunloadedSaveDir=tmp/column-soak/charge-review -PreviewBatch=review-column-soak-feed --max-workers=4
```

## Unloaded plant review

The unloaded save review uses a fresh, disposable game directory. Put a copy of the two-heater save in its `world/` subdirectory and run the command below from the worktree. The review prepares spawn outside the plant, checks both creative sources, the heater nodes and the column shell every tick, changes the column condenser set point by 0.01 K, and checks for an accepted table and actual FE receipts during 6,000 ticks.

```powershell
.\gradlew.bat runReviewWorldServer -I tools/column-soak/unloaded-save.gradle -PunloadedSaveDir=tmp/unloaded-chunks/review -PreviewBatch=unloaded-save --max-workers=4
```

Use a separate empty game directory and `-PreviewBatch=unloaded-fixtures` for the targeted power, still, alarm and blast GameTests. The fixture batch also accepts a single case such as `unloaded-still`.

The five follow-up fixes have an isolated review group, `-PreviewBatch=chunk-dependencies`. Individual selectors are `chunk-exchanger`, `chunk-binding`, `chunk-dcs`, `chunk-water`, `chunk-blast-loaded` and `chunk-blast-mixed`. Use `-PreviewBatch=exchanger-restart-prepare` in a fresh disposable game directory, then `-PreviewBatch=exchanger-restart-verify` in the same directory, to exercise startup before an exchanger is visited, a saved pool with a stale intake switch and the saved blast cleanup of a sleeping tank.

## History

- 2026-11-18: written for the owner's stability question after a hold in the test world.
- 2026-11-18: feed batch `review-column-soak-feed` (column phase and conditions in `soak-column.csv`, ends 10 s after the first TRIP): the feed stop is a CLOSURE trip while the heater still warms up; fixed-MW pumparound specs have no solution for the cooler feed.
- 2026-11-18: burner batch `review-column-soak-burners` (`soak-burners.csv`: burners, radiant coils, fuel valves): the burners fire what the coils ask (firing 1, fuel 98-101 % of asked, 74 % of full fire); the cold feed is the coils' holdup warming after a cold start.
- 2026-11-18: save batch `review-column-soak-save` (6 000 ticks, nothing built): soaks the world in `run/review-world/world`, a copy of a client's save (`run/saves/<name>`, copied fresh before each run, since the run saves over it). The columns are found by their members' status, and every chunk with a device (plus one around) is force-loaded, as a player standing at the plant would. The owner's save without forced chunks: the creative power sources did not tick, so `NO POWER` at tick 2000 (batch `2026-11-18-unloaded-chunks`).
