# Unloaded plant review

## Owner's save

A disposable copy of `run/saves/New World` ran for 6,000 ticks on the headless review server with no player. Spawn was prepared away from the plant before any test-server level initialization. All five monitored positions stayed unloaded on every test tick, including the column shell, both burner nodes and the creative sources at (48, −59, 44) and (89, −59, 44).

Burners 20 and 23 both received FE and stayed powered. At tick 300 the review raised the requested condenser temperature by 0.01 K. By tick 2,400 the column had accepted the changed set point on a table solved after the edit. The column was running at tick 6,000. An earlier attempt failed its chunk-absence assertion because GameTestServer prepared its default spawn before applying its spawn argument. The successful run started from a fresh copy.

## Engine and save behavior

| Area | Change |
|---|---|
| FE | Creative supplies have explicit saved identities. Cables and direct adjacency resolve mod terminals to their engine stores; foreign capabilities participate while loaded. Store rates, FE granularity and motor supply checks remain in force. |
| Columns and controls | The build registry already owns the complete as-built shape, including trays, built diameter and condenser. Column solves use it, with an existing-region fallback for older geometry. The saved assembly report, socket hosts and shapes restore member inputs and motor telemetry. An unloaded beacon still samples and can clear an acknowledgement. |
| Solid stills | Fluid schema 32 appends the complete fuel item stack to the pot. The empty-slot marker prevents a reload from reviving consumed fuel. Hoppers, the menu, dismantling and fire accounting share this inventory. Older pots read their saved slot through Minecraft's region I/O without a chunk ticket. |
| Environment | Pollution samples generated terrain from loaded sections or saved palettes and heightmaps. Ignition and the equipment damage calculation use plant facts. Vanilla block/entity explosions require a loaded footprint. Outside-air wall loss uses the existing `corrosion.external` switch and can advance on idle walls. |

Fluid schema 31 is reserved for the separate solute batch. This branch reads schemas 1–30 and 32. Control format 6 reads historic formats 1–5 and persists verified creative source positions and completion of legacy discovery. The column reuses the existing structure format. Format records and compatibility fixtures are on the task branch only.

## Verification

Final lane at the tested source tree: 1,616 selected unit and gate tests and all 134 required headless GameTests passed. Across 14 cold and 13 bundled watch seeds, the classifications matched the recorded ancestor campaign at `7d43d327e`: 8/14 and 5/13 clean respectively, with zero new, resolved, or reclassified holds. All 33 common junction reference lines and their counters matched. The selector skipped the unchanged chain-100 regression.

The initial focused JUnit run passed 220 of 221 tests, including all 28 fluid checkpoint tests and the format table check. The remaining case reproduced the documented cold-feed fixture issue in `ColumnRunTest.aColumnsOutletFeedsAnother`. Its downstream table assumed the original generator supplied both columns. The line transferred 6.209581785313949 mol before the downstream column reported an 85% condenser duty departure. [Earlier diagnosis](../2026-11-16-review-plant-sizing/README.md)

With the owner's approval, the fixture now prices the second table against the stream the first column actually delivers. It recovers temperatures at the outlet and inlet pressures from the conserved stream enthalpy, retaining the fixture's 1 kJ/mol condenser removal. The full 61-test column class passed with the original valve, throughput, and conservation assertions. The production solver and trip threshold were unchanged. The corrected method now participates in the merge lane.

The headless review command and fixture selectors are in [tools/column-soak](../../tools/column-soak/README.md).

| Headless regression | Observation |
|---|---|
| Source, cable and motor | A metered motor remains supplied with all three unloaded. Cutting its cable stops it at a decision and retains charge. |
| Direct FE | An unloaded adjacent creative source sustains the motor. Removing it removes supply. A finite generator transfers its stored FE to an unloaded neighbour. |
| Foreign FE | A real NeoForge source delivered FE to a metered motor. When the source chunk unloaded, the registered motor stopped with its buffered joules intact. An ordinary receiver stopped receiving while unloaded and resumed after reload. |
| Still | A hopper-compatible slot supplies two coal items. The unloaded pot consumes one; its booked heat and fire reserve account for that item's heat, and its reloaded capability exposes one coal. |
| Legacy still | A saved pot with three named coal items and custom item data imports the exact stack from disk. Extracting it leaves the engine slot empty after a reload even though the old NBT still exists. |
| Pollution | A saved terrain palette yields the drum's air fraction. A seeded ignition burns the combustible release and breaches the unloaded vessel. Both a registered shell and a legacy shell with no build fact are covered. |
| Beacon | The loaded beacon acknowledges and clears. The separate controller regression exercises the clear and re-arm sequence with no level available for drawing. |

The cable fixture initially could not unload. Reading a foreign block through the level to query FE renewed a chunk ticket. The corrected path peeks at an already-loaded chunk and passes its existing block state and entity to NeoForge. Both the unload assertion and the subsequent supply checks passed.
