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

Run (no dev client open; one Gradle lane):

```
cp tools/column-soak/ColumnSoakReviewWorld.java src/fluidGameTest/java/com/wormzjl/icheme/fluid/gametest/
./gradlew runReviewWorldServer -PreviewBatch=review-column-soak --max-workers=4
python -I tools/column-soak/drift.py run/review-world
rm src/fluidGameTest/java/com/wormzjl/icheme/fluid/gametest/ColumnSoakReviewWorld.java
```

## History

- 2026-11-18: written for the owner's stability question after a hold in the test world.
- 2026-11-18: feed batch `review-column-soak-feed` (column phase and conditions in `soak-column.csv`, ends 10 s after the first TRIP): the feed stop is a CLOSURE trip while the heater still warms up; fixed-MW pumparound specs have no solution for the cooler feed.
- 2026-11-18: burner batch `review-column-soak-burners` (`soak-burners.csv`: burners, radiant coils, fuel valves): the burners fire what the coils ask (firing 1, fuel 98-101 % of asked, 74 % of full fire); the cold feed is the coils' holdup warming after a cold start.
- 2026-11-18: save batch `review-column-soak-save` (6 000 ticks, nothing built): soaks the world in `run/review-world/world`, a copy of a client's save (`run/saves/<name>`, copied fresh before each run, since the run saves over it). The columns are found by their members' status, and every chunk with a device (plus one around) is force-loaded, as a player standing at the plant would. The owner's save without forced chunks: the creative power sources did not tick, so `NO POWER` at tick 2000 (batch `2026-11-18-unloaded-chunks`).
