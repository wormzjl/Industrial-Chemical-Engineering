# Air pollution

Up: [code guide](README.md)

## Read this when

- A release (vent, spill, chimney, flare, burst pipe, breached vessel) does not reach the air, or reaches the wrong cube.
- A cube's index, its spread, its decay or the ledger looks wrong; `/icheme pollution` answers oddly.
- A player or mob gets no effect or the wrong one; a cloud does not ignite, a blast is too strong, a chain does not follow.
- The handheld meter or the fixed monitor reads wrong; a breached vessel will not repair.
- The FTB Chunks map shows no pollution squares, or the wrong ones.
- The ground of a chunk column looks wrong, a crop will not grow, or a swimmer is harmed.
- A log line starts with `pollution status=` or `pollution_store status=`.

## In one paragraph

The air of every dimension is divided into 16 m cubes (a chunk section). Each cube holds four amounts: toxic, combustible, corrosive, particulate. An amount is a sum of released moles, each divided by its compound's reference (IDLH for toxic and corrosive, the lower flammability limit for combustible; particulate is mass over 1.75 g/m³). The **index** of a cube is its amount over the air it holds, so index 1 means "at the reference". The fluid network's releases are queued; every 100 ticks a worker job steps the field (exchange with the six face neighbours, an upward drift, settling, decay, truncation) and the server thread installs the result. Effects, ignition, the meter and the monitor only read the installed field. Pollution never refuses a world and never stops the server: every entry point is guarded, and a part that fails three passes in a row turns itself off for the session.

## Class map

Paths are relative to `src/main/java/com/wormzjl/icheme/`.

| Class | Role |
|---|---|
| **Standard and field (`science/pollution/`, pure)** | |
| `Channel` | `TOXIC`, `COMBUSTIBLE`, `CORROSIVE`, `PARTICULATE`; saved by name. |
| `HazardStandard` | Moles on the world's compound list to amounts: `toxic`, `corrosive`, `combustible` (Le Chatelier: Σ n/LFL), `particulate(kg)`; `volatileShare`, `volatileMoles` for a spilled liquid. Built from `MaterialCatalog.Hazard` and `flammability` ([materials.md](materials.md)). |
| `CubeKey` | A cube as a long, packed as `SectionPos.asLong`; `offset`, `inside(key, minY, maxY)`. |
| `PollutionField` | Immutable sorted keys and amounts. `step` (one pass), `burn`, `capped` (drops cubes outside the height, booked dissipated), `index`. `CUBE_AIR_MOLES` ≈ 167 400. |
| `PollutionSettings` | Exchange 0.12 per face and 20 s, half-lives 300 s open and 3600 s enclosed, rain factor 4, settling 0.05, cutoff 1e-3, at most 8192 cubes. |
| `Environment`, `Cell`, `MapEnvironment` | A cube's air share, open to the sky or not, loaded or not. |
| `PollutionLedger` | Per channel: `RELEASED`, `DECAYED`, `DISSIPATED`, `BURNT`, `GROUND`; `closure` = released − stored − the rest. |
| `ExplosionLaw` | `flammable` (index ≥ 1), `burntIndex` (at most 2), `energy`, `tntKilograms`, `power`, `strengthAt(power, distance)`, `reach`. |
| `Exposure` | Levels at 0.1, 0.3, 1 and 3; the effects and damage of each kind. |
| `Reading` | The meter's words and numbers. |
| `GroundField`, `GroundSettings`, `GroundLedger`, `GroundEnvironment` | The ground: one entry per chunk column, one amount and a compound inventory (name → moles); `step` (land, spread, decay, truncate, cap), `draw` (a water draw, G5 law); reference 100 × `CUBE_AIR_MOLES`; ledger `DEPOSITED` = stored + `DECAYED` + `DRAWN` + `CAPPED`, for the amount and per compound. |
| `GroundSpoil` | The block changes' law: limits 0.3 and 0.1, 0.2952 picks per pass and unit index (half a column in 10 min at index 1), at most 8, random rounding. |
| `GroundSnapshot`, `GroundEffects` | The published ground: column → one byte (the map's scale); growth stops at index ≥ 0.3; the water acts on the toxic table at the ground index. |
| `MapColumns` | The map overlay's summary: per chunk column the largest index of each kind, one byte each (steps of 0.02, 255 = 5.1 or more); the 32-chunk radius, the cap of 4096 columns (nearest first), the square's colour and alpha. |
| **Runtime (`runtime/pollution/`)** | |
| `PollutionService` | One per server, server thread. `tick` (pass every 100 ticks, effects every 20, ignition every 10, blasts every tick), `release`, `burn`, `index`, `field`, `cell`. Cells are read with `getChunkNow` only. |
| `PollutionEngine` | One dimension: installed field and ground, their ledgers, pending releases and deposits, the pass in flight (`begin`, `complete`, `failed`), `burn`. |
| `PollutionStore` | `icheme_pollution` on the overworld, for every dimension. Never throws. |
| `PollutionCommands` | `/icheme pollution get|add|clear|ledger`. |
| `PollutionEffects` | Status effects, damage, corroded armour, dust particles; at most 256 cubes a pass; `water`: a swimmer in a polluted column. |
| `GroundGrowth` | Crop, tree and bonemeal events on polluted ground. |
| `GroundSoil`, `world/level/block/PollutedSoilBlock` | The picks on the world pass (kills, then spoils, along a 4-block walk; plain `setBlock`); the soil's recovery on its random tick. |
| `Ignition`, `Blasts` | Ignition sources in flammable cubes; the burn, `Level.explode`, the chain queue. |
| `BurstPipes` | A one-block device a blast destroys: the mark, the release, one sound per cube and tick. |
| `Breaches` | Constructed equipment in a blast: a `Detonate` listener, one breach per structure. |
| `PollutionMeters`, `MonitorSignals` | The handheld meter's server side; the fixed monitor's lamp and alarm levels. |
| `PollutionMaps` | The map overlay's server side: subscriptions by player, one answer on request and one per pass, access by `pollution.mapOverlay`. |
| **Hooks elsewhere** | |
| `runtime/fluid/Releases` | On `ShaftTrains.booked`: turns what an island let out into releases. |
| `FluidWorldAuthority` | `releasePollution` (the sink), `flames` (the plant's own ignition sources), `releaseContents` (a burst device), `breach`, `repair`. |
| `ProcessSolveJobs.PollutionCommand` | The pass as the fourth job family ([runtime-engine.md](runtime-engine.md)). |
| `world/item/PollutionMeterItem`, `world/level/block/PollutionMonitorBlock` | The meter; the monitor, a data host `AT-nnnn` ([wired-control-power.md](wired-control-power.md)). |
| `network/PollutionMapNetwork` | `Request(watching)` and `Answer(dimension, denied, columns, values)`. |
| `client/pollution/PollutionMapClient`, `PollutionMapStore` | Finds the map integration once; asks while the map is open; holds the last answer. |
| `client/pollution/ftbchunks/FtbChunksOverlay`, `ColumnIcon` | The only classes that name FTB Chunks, FTB Library or Architectury types. |

## Main flow

1. **Release.** `Releases` reads an island's booked boundary transfers, maps its compound list onto the world's by name and calls `PollutionService.release(dimension, position, channel, amount)`. The release waits in the engine's pending list. A release outside the dimension's height is dropped.
2. **Pass.** Every 100 ticks `begin` hands the installed field, the pending releases and the cells of the active cubes to a worker (`PollutionField.step`). Process time per pass is 5 s × `fluid.timeScale`.
3. **Step.** Releases are added; each face exchanges `k × min(φa, φb) × (index difference)` with k = min(1/7, 0.12 × dt/20) and φ the air share; each open, loaded cube also drifts `r × min(φa, φb) / φa` of its amounts to the cube above, whatever the index there, with r = min(1/7, `riseShare` × dt/20), `riseShare` 0.12 (config `pollution.riseShare`; above the top it is `DISSIPATED`, into a closed or unloaded cube nothing, into a new cube in the same front slot as the face share); particulate settles 0.05 downward and to `GROUND` at a floor; each amount decays by its half-life (rain ×4 for corrosive and particulate); an amount below the cutoff with no neighbour above it is dropped and booked `DISSIPATED`; above 8192 cubes the smallest are dropped.
4. **Install.** `complete` replaces the field and the ledger. Burns made while the pass was in flight are applied again to its result.
5. **Read.** `PollutionEffects`, `Ignition`, `PollutionMeters`, the monitor's block entity and `ControlHosts.readMonitor` read `index` of the installed field. None of them steps it.

## Sources

| Source | Where | Released |
|---|---|---|
| Open vessel vent and spill | `Releases`, at the roof and the foot | The gas; a liquid's volatile share |
| Chimney | `Releases`, above the stack top | Gas and solids |
| Flare | `Releases` | Burning: SO2 and soot (yield 0.01). Flame out: the gas |
| Flush, cooling tower air | `Releases` | As booked |
| Burner and engine fuel | `Releases` | The SO2 of the fuel's sulfur |
| Waste outfall | `Releases` (`outfall`), at its own cube | The spill law (`Releases.spill`, also used by an open spill and a destroyed device) on all it discharges (a gas at 25 °C whole): the vapour to the air; the rest (toxic, corrosive) and its solids (particulate) to the ground of its chunk column (`Sink.deposit`, `PollutionService.deposit`; the air ledger books it `RELEASED` and `GROUND` together), never the air field |
| A pipe, valve or one-block tank a blast destroys | `BurstPipes` → `releaseContents` | A vessel node's inventory; a line block's holdup, estimated from its last interval. One dense fluid splits by its liquid-like weight `w` instead of its slot (`DenseReadings.release`, `moved`: a line's side by the node it left), and the volatile share of its liquid-like part counts as the gas heard (`DenseReadings.heard`): dense carbon dioxide reads gas, a dense heavy cut liquid |
| A breached vessel | It stands open, so the first row applies | |

Not sources: the blanketing vent, a still pot, a relief run, a device a player breaks, a plain fluid void (only a void whose spec is `ambient`, the waste outfall, books), clean water at an outfall (water has no reference).

## The ground

- Deposits: the spill law only (`PollutionService.deposit`, queued like a release); the amount is the sum of its channels' amounts, which the air ledger books `RELEASED` and `GROUND` each. The air's settling does not reach the ground store (owner, 2026-11-18: off for now); the ground gives the air nothing.
- Each pass, on the worker with the air: land; spread to the four face columns (5e-4 per 20 s, ×4 in rain, ×4 across a water face, each face at most 0.2), amounts and compounds by the same donor share; decay, loaded columns only (half-life 4 h, ÷4 in rain); truncate below index 1e-4 (booked decayed); cap at 16 384 columns (lowest peak first, booked capped).
- Water columns: a surface mostly water (9 of 16 samples), read at most 256 columns a pass and cached.
- `GroundSnapshot` is installed with the field; the map, the meter, `GroundGrowth` and `PollutionEffects.water` read only it.
- `clear` books the cleared ground as capped.
- Block changes (`pollution.groundBlocks`): each world pass a column at or above 0.3 gets random picks; a pick removes `icheme:pollution_kills` blocks with no drops and turns the first `icheme:pollution_spoils` block into polluted soil; polluted soil turns back to dirt on a random tick below 0.1. Nothing is taken from the ground.
- The dirty water intake's draw law (`GroundField.draw`) is built; nothing calls it yet (batch 2026-11-18-ground-pollution, G5, after the solute batch's WP2). A water intake's status names its column's compounds as x = moles / 5.68e7 (`FluidWorldAuthority.groundWater`), display only.

## Ignition, blasts and breaches

- A cube ignites when its combustible index is ≥ 1 and it holds a source: a block of the tag `icheme:ignition_sources` (a `lit` state only while lit), a burning entity or one of the entity tag, any explosion, or one of the plant's flames (`FluidWorldAuthority.flames`: a flare, a fired heater, a fired still, an engine or gas turbine, from cached views).
- The blast burns up to index 2. Energy: 44 kJ per mole of mixture, yield 3 %, TNT 4.6 MJ/kg; power = 4 × (m/50 kg)^(1/3), capped by `pollution.explosionPowerCap`. Below power 0.5 the pocket burns without a blast.
- Each flammable face neighbour follows 3 ticks later; at most 4 blasts a tick and dimension.
- A one-block fluid device breaks as an ordinary block (resistance 3.5) and releases what it holds.
- Constructed equipment loses no block. A blast of strength ≥ 2 at one of its cells (`strengthAt`) sets `FluidDeviceSpec.breached` on its closed vessels: the graph sees them open (`vented()`), through the registry's respecify edit, so they keep their contents and then vent and spill by the open-vessel law. A machine with a health record fails; heat sides close and release their holdup once. Every device of the structure is wrecked (`Integrity.wreck`): the Equipment Constructor refuses to repair it, it is deconstructed and rebuilt ([integrity.md](integrity.md)). Not breached: still pots, kettle shells, members of a running column.

## The map overlay (FTB Chunks, optional)

- FTB Chunks, FTB Library and Architectury are compile-only dependencies. `PollutionMapClient` reaches `FtbChunksOverlay.install()` only when the mod `ftbchunks` is loaded, inside a `LinkageError | RuntimeException` guard. A failure in any FTB call turns the overlay off for the session with one warning (`pollution status=MAP_OVERLAY_OFF`).
- The client sends `Request(true)` when FTB's large map opens and `Request(false)` when it closes. The server answers at once (at most once a second per player) and then every 100 ticks while the subscription lasts. A subscription ends at close, logout, a dimension change or server stop. With no subscriber the server does one map check per tick.
- An answer is a read of the installed field and the cached cells. It holds the columns within 32 chunks of the player whose largest index is at least 0.1, at most 4096, 12 bytes each.
- `pollution.mapOverlay`: `METER` (default; the player carries a pollution meter anywhere in the inventory), `ALL`, `OFF`. A player without access gets a refused answer and the client shows nothing.
- FTB gathers the large map's icons when its panel builds (at opening, at a dimension switch, on `LargeMapScreen.refreshIconsIfOpen`), not each frame. Each column is one `ColumnIcon` that reads its colour from the store when it draws; a changed set of columns asks FTB for a refresh. The square is one chunk, drawn below FTB's own icons, coloured by the worst kind, with a tooltip of the four values.
- There is no minimap line: the store is empty while the large map is closed.
- Layers (`MapLayer`): a key (`key.icheme.pollution_layer`, unbound) cycles Air, Ground, Both (the air's worst channel or the ground, whichever index is higher; the ground in brown); kept in `icheme-client.toml` (`gui.pollution_map_layer`). The tooltip names the layer shown and always lists the four air lines and one ground line. Without FTB Chunks the meter and `/icheme pollution ledger` are the only views of the ground.

## Formats

- `PollutionStore#SCHEMA` 2 (2 adds the ground: `ground_columns`, `ground_amounts` (one per column), `ground_ledger`, `ground_ledger_compounds`, `ground_compounds`, `ground_pending`, absent for a dimension with no ground; damaged ground arrays drop that dimension's ground alone; unknown compound names are kept). Schema 1 state the engine cannot compute again, so it is saved, but under its own rule: a damaged store is copied aside (`icheme_pollution.damaged-<n>.dat`) or, when that fails, erased, and the dimension starts clean. A damaged cube is dropped alone. Corpus: `src/test/resources/compat/pollution/`.
- `FluidDeviceSpec.breached` is a fluid fact (schema 26, [runtime-saves.md](runtime-saves.md)); `wrecked` is one of the integrity facts (schema 29).
- The monitor adds no control fact: its socket is an ordinary sensor port; `Sensor.Kind.ANALYSER` and `Sensor.Unit.INDEX` are appended and saved by name.
- The map answer's ground bytes are display only: no protocol number.
- The map payloads moved the wire protocol to `icheme-2` (`NetworkProtocol#VERSION`); they add no saved format.
- Rows: [FORMATS.md](../FORMATS.md).

## Config (`pollution`)

`enabled`, `halfLifeOpenMinutes`, `halfLifeEnclosedMinutes`, `cutoff`, `maxActiveCubes`, `effects`, `particles`, `explosions`, `blockDamage`, `explosionPowerCap`, `equipmentBreach`, `mapOverlay`, `groundGrowth`, `groundWater`, `groundBlocks`.

## Tests and gates

| What | Test |
|---|---|
| The standard, the hazard records | `HazardStandardTest`, `MaterialHazardTest` |
| The step, the ledger's closure, the height cap | `PollutionFieldTest`, `CubeKeyTest` |
| Blast numbers, strength at a cell | `ExplosionLawTest`, `BlastsTest` |
| Engine passes, a burn during a pass | `PollutionEngineTest` |
| The store, damaged images | `PollutionStoreTest`, `PollutionStoreCorpusTest` |
| Releases from the network | `ReleasesTest`, `OutfallReleasesTest` |
| Breach and repair, schema 26 | `VesselBreachTest`, `FluidFactsCompatTest`, `MachineHealthTest` |
| Levels, readings, monitor signals, data files | `ExposureTest`, `ReadingTest`, `MonitorSignalsTest`, `PollutionDataTest` |
| The monitor on a bus | `ControlSystemTest` |
| The ground: step, closure, draw; its store; layers; effects; spoiling; the layer key | `GroundFieldTest`, `GroundStoreTest`, `MapLayersTest`, `GroundEffectsTest`, `GroundSpoilTest`, `MapLayerTest` |
| The map summary, its payloads, access | `MapColumnsTest`, `PollutionMapNetworkCodecTest`, `PollutionMapsTest` |
| In a server | `Pollution*GameTests` in `src/fluidGameTest` (batches `pollution…`) |

## Traps

- One cube filled alone loses about 84 % in one pass: 0.12 to each face and 0.12 more upward. A test that needs an index after a pass fills the cube and its neighbours.
- `PollutionService.tick` returns early when pollution is off; the meters and the monitor's ticker run outside it so they can show "off".
- A burn must go through `PollutionEngine.burn`, never straight to the field: a pass in flight would overwrite it.
- Equipment cells have a resistance of 3.6e6, so `onBlockExploded` never fires for them in a vanilla blast. The breach comes from the `Detonate` listener.
- A layout refresh that rebuilds a spec must carry `breached` (`StructureRegistration`), or a reload repairs the vessel.
- Pollution code never loads a chunk and never throws to its caller. A new entry point gets its own guard and log line.
- `Math.log10` and its like are banned here as everywhere (`DeterministicMathSourceTest`).
- No class outside `client/pollution/ftbchunks/` may name an FTB or Architectury type: the mod must load without FTB Chunks. No test or GameTest loads those two classes; only a dev client with FTB Chunks proves the overlay's look.
- `LargeMapScreen` and `refreshIconsIfOpen` are FTB internals, not its API. A new FTB version that moves them turns the overlay off, not the game.

**Snapshot:** the upward drift and the faster step, 2026-11-18.
