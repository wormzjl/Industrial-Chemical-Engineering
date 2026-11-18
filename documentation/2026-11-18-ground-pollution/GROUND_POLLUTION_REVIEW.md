# Ground pollution: review

Batch `2026-11-18-ground-pollution`, branch `claude/water-intake-outfall-0e90c8`. Commits: G1 `93537c4f0`, G2 `a911c9dd0`, G3 `d3e3083ec`, G4 `966bee2c0`, G5 display `1370f9df8`, G4b `874115b86`, G3 key `1a62007f0`, the one-amount rework (this review's commit and the one before it). Constants: [GROUND_POLLUTION_PLAN.md](GROUND_POLLUTION_PLAN.md).

## Summary

- The ground is one entry per chunk column with **one amount** in air-mole equivalents and a compound inventory (`science/pollution/GroundField`). It lands, spreads, decays, truncates and caps once per engine pollution pass, on the worker, beside the air field.
- The ground's only sources are liquid: the non-evaporating part of a waste outfall's stream and of a spill (open vessel, destroyed device), with its solids and compounds. **The air gives the ground nothing** (owner, 2026-11-18); the ground gives the air nothing.
- The ground ledger closes for the amount and per compound: deposited = stored + decayed + drawn + capped (`GroundLedger.closure`), within 1e-9 relative in every test. Its `deposited` is the sum, over the four air channels, of what the outfalls and spills sent to the ground.
- The store is schema 2; schema 1 reads with an empty ground. Damaged ground arrays drop that dimension's ground alone. Unknown compound names are kept. The schema 2 reservation on main was skipped on the owner's word (2026-11-18, "Skip it"); the FORMATS row stands on this branch only.
- The map answer, the FTB Chunks overlay (a layer key, a brown ground scale), the meter (one ground line) and the ledger command show the ground. Without FTB Chunks the meter's ground line and `/icheme pollution ledger` are the only views.
- Polluted ground (index ≥ 0.3) stops crops, saplings and bonemeal; its water treats a swimmer as a toxic cube at the ground index; random picks remove vegetation and turn soil into polluted soil, which recovers to dirt below index 0.1 (G4b).
- A water intake names its column's ground contaminants as mole fractions; it delivers none of them yet (G5, open package).

## Owner changes, in order

1. "store ground pollution on a chunk basis instead of 16block cubes, and expand its function": the store (G1 to G5).
2. "ground only have one type of pollution": the four ground channels were built first and reworked into one amount. Removed: the per-channel ground amounts, ledger rows, snapshot bytes and map bytes; the "one compound under one channel" rule (compounds move and decay with the column's one fraction); the per-channel ground lines of the meter, the tooltip and the ledger command; the per-channel water rule (now the toxic table at the ground index, one damage source).
3. "do not let ground absorb air pollution for now": `PollutionField.settle` is back to what it did before the ground package. Settled particulate that reaches a closed cube or the bottom is booked to the air ledger's `GROUND` and is gone; `PollutionField.Result` has no deposit list. `PollutionField.java` is byte for byte the file of `b57774830`, so every air-field test value is as before the ground package, bit for bit.
4. "Do random block transformations like vanilla, convert grass/farmland/dirt block into polluted soil, and destroy other blocks": G4b, replacing the tint.

## Physics, in numbers

- A column holds 1.674e7 equivalent moles at index 1: what 100 air cubes hold at index 1.
- A steady source with spread off stands at its decay balance, S / (1 − 2^(−20/14 400)) = S / 9.62e-4 per 20 s pass: in `GroundFieldTest`, 1 000 eq mol/s gives 2.072e7 (index 1.238).
- With spread (5e-4 per face and 20 s) the source column stands at index 0.464, its face neighbour at 0.208 of it, its diagonal at 0.038 of it.
- Rain: spread ×4, half-life ÷4. A water face: spread ×4. Every face share is capped at 0.2.
- Conservation: every mole a deposit brings is in the store or in one ledger total; spread moves the amount and the compounds by the same donor share.
- The spoiling (G4b) is an effect, like damage: it takes nothing from the ground.

## Open owner items

- **Air to ground deposition: off for now** (owner, 2026-11-18). Turning it on takes: `PollutionField.settle` returning what it books `GROUND` by chunk column (the merge into a `TreeMap<Long, Double>` at its three ground bookings, removed in this rework), those deposits added to the pass's ground deposits in `PollutionEngine.Pass.run`, and the closure test stating deposited = the whole air `GROUND` total. No format change; no config switch.
- The reading of "other blocks" (G4b): A vegetation only (built); B also logs; C a wider list.
- G3 option B: a map page of the mod's own (see below).
- G5 delivery: an open package after the solute batch's WP2.

## Options taken and deviations

| Item | What was built | Why |
|---|---|---|
| The spill law | One law (`Releases.spill`) for SPILL, the outfall and a destroyed device's contents: vapour to the air, the rest and its solids to the ground | One stage, no special cases; open spills and burst devices now deposit to the ground as the outfall does |
| A deposit's amount | The sum of its toxic, corrosive and particulate (solids) amounts, each in equivalents; the air ledger books each channel `RELEASED` and `GROUND` as before | Owner: one type of ground pollution |
| `/icheme pollution clear` | Books the cleared ground as `capped` | The ledger keeps closing |
| Grass spread (growth rule) | Not stopped by an event | NeoForge 21.1 has no grass-spread event; polluted soil is not dirt, so grass does not spread onto it |
| Growth events | `CropGrowEvent.Pre` (`DO_NOT_GROW`), `BlockGrowFeatureEvent` (cancelled), `BonemealEvent` (cancelled, not successful) | The exact NeoForge 21.1.219 classes |
| Snapshot | One byte per column, steps of 0.02, the air map's scale | Effects and the map read one published value; growth at 0.3 is byte 15 |
| Map wire | 13 bytes a column: x, z, the air's four bytes, the ground's byte | Display only: no protocol number |
| Ground colour | Brown `0x8D5524` at the air's alpha law; Both shows the air's worst channel or the ground, whichever index is higher (the air on a tie) | Owner: one scale of its own |
| Ground word | The toxic channel's word table (`Reading.groundWord`) | The ground's water acts on the toxic table |
| Damaged corpus | `pollution-1-damaged.snbt`'s "newer schema" entry moved from 2 to 3 | Schema 2 is now valid |
| Schema 2 layout | Changed in place to one amount (`pollution-2.snbt` rewritten by hand: amounts summed, ledger summed) | Schema 2 has not shipped; no migration |
| G3 layer (main thread: option A) | A key mapping, unbound by default in the mod's category, cycles Air, Ground, Both (`MapLayer`); the tooltip names the chunk and the layer shown and always lists "Air (worst cube)" with four lines and one "Ground" line; the layer is kept in `icheme-client.toml` (`gui.pollution_map_layer`); the key works with no screen open and on the open map | |
| G3 option B (owner option, not built) | A map page of the mod's own, opened from the meter | Cost: a new screen (a chunk grid with pan and zoom, terrain colours sampled from loaded chunks on the client, the same squares and tooltip), its Minecraft-free builder, render sheets and layout gates; about 600 to 900 lines; no format or protocol change |
| G5 now (main thread: A with C's line) | The intake's status line names up to three compounds with x = moles / 5.68e7, from the installed ground, on the normal view publication; nothing drawn or delivered | The generator stock path belongs to the solute batch until its WP2 lands |
| G4b random rounding | Picks per pass = expected picks rounded up or down at random with the right mean | A plain round of 0.295 × index gives no pick at all below index 1.7 |
| G4b walk start | The block at the motion-blocking height (the first free position, where a flower or crop stands) and three below | A flower or crop does not block motion |
| G4b water plants | Kelp and seagrass are reached only where the water is shallow and become water | The walk is 4 blocks from above the surface |
| Polluted soil texture (owner, 2026-11-18: "C: dark oily") | Option C, dark soil with an oily violet sheen, drawn by `tools/polluted-soil-texture/` | Options A grey ash (the placeholder before) and B brown with crust were not picked |
| Field Guide | Only the polluted soil's "serves" line | The guide has no pollution chapter |
| GameTest batches | The growth test and the spoiling test in batches of their own | Tests of one batch run together, and each clears the dimension's pollution |

## G4b: the owner's block changes

Config `pollution.groundBlocks` (server, default on).

| Constant | Value | Where |
|---|---|---|
| `SPOIL_LIMIT` | 0.3 (`Exposure.MEDIUM`, the growth limit) | `GroundSpoil` |
| `RECOVERY_LIMIT` | 0.1 (`Exposure.LOW`) | `GroundSpoil` |
| `ATTEMPTS_PER_INDEX` | 0.2952 picks per pass and unit index: ln 2 / (−ln(1 − 1/256)) / 600 | `GroundSpoil` |
| `MAXIMUM_ATTEMPTS` | 8 per column and pass (reached at index 27) | `GroundSpoil` |
| Pass | the world pass, 20 ticks (1 s), at most 256 columns | `PollutionEffects` |
| `WALK` | 3 blocks below the motion-blocking height | `GroundSoil` |
| Recovery | vanilla random ticks (on average one per block every 68 s at the default rate of 3) | `PollutedSoilBlock` |

Half of a column's 256 surface positions picked at least once: index 0.3, 2 000 s (33 min); index 1, 600 s (10 min); index 3, 200 s (3.3 min). Below 0.3 nothing spoils.

- A pick removes each block of `icheme:pollution_kills` it meets (no drops; water where it stood in water), turns the first block of `icheme:pollution_spoils` into `icheme:polluted_soil`, and stops at any other block that blocks motion or holds a block entity. Every change is a plain `level.setBlock`, so protection mods veto it.
- Polluted soil: shovel, hardness 0.5, gravel sound, drops itself. No plant stands or grows on it (`canSustainPlant` FALSE); bonemeal does nothing; grass does not spread onto it.

## G5: dirty water intake (open package)

- Built: the law `GroundField.draw(field, ledger, column, n_water, W, held)` takes min(1, n_w / W) of every compound the package holds, with the column's amount in proportion to the moles that leave, booked `drawn`; compounds the package lacks stay; closure per compound is tested. Nothing in the runtime calls it yet.
- Built: the status line (display only): "Ground water (not delivered): Hydrogen sulfide x = 1.20e-06, ..." with at most three compounds and "and n more".
- Open, its design: per water intake an engine-state override of its delivered composition (x_i = moles_i / W from its column's inventory, W = 5.68e7 mol), refreshed by a queued engine event each pollution pass, never on the tick or a block-entity load; the draw booked from the island's actual GENERATOR boundary transfer, so the moles entering the island equal the moles drawn, per compound. The spec stays a fact (pure water).
- Precondition: the solute batch's WP2 (a generator spec's contents, facts schema 31) merged.

## Tests

- New: `GroundFieldTest` (11), `GroundStoreTest` (5), `MapLayersTest`, `GroundEffectsTest`, `GroundSpoilTest` (3), `MapLayerTest` (3); GameTests `GroundPollutionGameTests` (batches `ground-pollution` and `ground-pollution-blocks`).
- Updated: `PollutionEngineTest` (the ground's deposited equals what the spills sent, 47 000, and the air settles nothing onto it), `OutfallReleasesTest`, `PollutionMapNetworkCodecTest`, `PollutionStoreCorpusTest` (`pollution-2.snbt`), `PollutionDataTest`, GameTest `PollutionMeterGameTests`.
- Removed with the rework: the test that settling comes back as column deposits; the per-channel ground assertions.
- Runs (classes from `affected.mjs select`, 4 workers): G1 to G4 green; G5 display, G4b and the G3 key: 1 532 tests, one failure (the polluted soil's missing "serves" line), fixed and its two classes green; fluid GameTests: `ground-pollution-1` all 124 passed, `ground-pollution-2` one failure (the two ground tests in one batch), fixed by the batch split; after the one-amount rework: the affected unit and gate classes green (1 165 tests, plus the two fixed classes), fluid GameTests `ground-pollution-3` all 125 passed. No run was held back for a dev client.
- Moved references: none in the air field (`PollutionField.java` restored to `b57774830`). `PollutionEngineTest` amounts were raised to 40 000 and 7 000 so they stand above the ground cutoff; the asserted values are the test's own.

## Not seen in a client

- The overlay's squares, tooltip and layer key; the meter's ground line; the ledger command's text; the intake's ground line.
- Growth stopping, spoiling and recovery in a live world (the GameTests call the events and picks themselves); a swimmer's effects; the polluted soil's look.
