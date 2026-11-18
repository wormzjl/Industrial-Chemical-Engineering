# Ground pollution: review

Batch `2026-11-18-ground-pollution`, branch `claude/water-intake-outfall-0e90c8`, commits G1 `93537c4f0`, G2 `a911c9dd0`, G3 `d3e3083ec`, G4 `966bee2c0`. Constants: [GROUND_POLLUTION_PLAN.md](GROUND_POLLUTION_PLAN.md).

## Summary

- The ground is one entry per chunk column with four channel amounts and a compound inventory (`science/pollution/GroundField`). It lands, spreads, decays, truncates and caps once per engine pollution pass, on the worker, with the air field.
- The ground ledger closes per channel and per compound: deposited = stored + decayed + drawn + capped (`GroundLedger.closure`), within 1e-9 relative in every test.
- The store is schema 2, append-only; schema 1 reads with an empty ground. Damaged ground arrays drop that dimension's ground alone. Unknown compound names are kept.
- The map answer, the overlay, the meter and the ledger command show the ground. Polluted ground (worst index ≥ 0.3) stops crops, saplings and bonemeal; its water treats a swimmer by the toxic and corrosive index.
- The dirty water intake (G5) is not delivered: the stop rule of the brief applies (section G5). The draw law is built and tested.

## Physics, in numbers

- A column holds 1.674e7 reference moles at index 1: what 100 air cubes at index 1 settle.
- A steady source with spread off stands at its decay balance, S / (1 − 2^(−20/14 400)) = S / 9.62e-4 per 20 s pass: in `GroundFieldTest` 2.072e7 (index 1.238).
- With spread (5e-4 per face and 20 s) the source column stands at index 0.464, its face neighbour at 0.208 of it, its diagonal at 0.038 of it.
- Rain: spread ×4, half-life ÷4. A water face: spread ×4. Every face share is capped at 0.2.
- Conservation: every mole a deposit brings is in the store or in one ledger total; spread moves amounts and compounds by the same donor share, so no mole is made or lost.

## Options taken and deviations

| Item | What was built | Why |
|---|---|---|
| A compound under two channels | One inventory entry per compound, under the channel of its larger term (toxic or corrosive); it moves with that channel's share | Two entries would count its moles twice; the brief's two entries are replaced |
| The spill law | One law (`Releases.spill`) for SPILL, the outfall and a destroyed device's contents: vapour to the air, the rest and its solids to the ground | One stage, no special cases; open spills and burst devices now deposit to the ground as the outfall does |
| `/icheme pollution clear` | Books the cleared ground as `capped` | The ledger keeps closing |
| Grass spread | Not stopped | NeoForge 21.1 has no grass-spread event; no mixin was added |
| Growth events | `CropGrowEvent.Pre` (`DO_NOT_GROW`), `BlockGrowFeatureEvent` (cancelled), `BonemealEvent` (cancelled, not successful) | The exact NeoForge 21.1.219 classes |
| Snapshot | Four bytes per column, steps of 0.02, as the air map | Effects and the map read one published value; growth at 0.3 is byte 15 |
| Damaged corpus | `pollution-1-damaged.snbt`'s "newer schema" entry moved from 2 to 3 | Schema 2 is now valid |
| Store keys | The six ground keys are absent for a dimension with no ground | A schema 1 world saves the same keys as before, plus the schema number |
| G3 layer | `PollutionMapStore.Layer` (BOTH, AIR, GROUND), default BOTH (the worse of the two per channel) | The key that cycles it waits for the main thread's answer (A: a key on the overlay, B: a map screen, C: no key) |
| G4 tint | Not built | Options sent: A skip (follow-up), B grass and leaves through BlockColors with a capped re-mesh (24 sections per column), C a BiomeColors mixin |

## G5: dirty water intake (held)

- Law built: `GroundField.draw(field, ledger, column, n_water, W, held)` takes min(1, n_w / W) of every compound the package holds, with the matching share of the non-particulate channel amounts, booked `drawn`. Compounds the package lacks stay. Tested for closure per compound.
- Not built: the delivery. A generator delivers its owned reservoir state, built once from its spec (`FluidDeviceSpec.initialize`, `PhysicalRegistry` stock). Delivering contaminants means replacing that state's composition by a queued engine event and booking the draw from the island's own boundary transfer. The solute batch's WP2 changes the same generator contents path (facts schema 31) in `PhysicalRegistry` and `FluidWorldAuthority`.
- Options sent to the main thread: A wait for solute WP2 and build on its generator contents (pick); B build now beside it, with a merge conflict; C display only (the intake names the column's contaminants).

## Tests

- New: `GroundFieldTest` (10), `GroundStoreTest` (5), `MapLayersTest`, `GroundEffectsTest`, GameTest `GroundPollutionGameTests` (batch `ground-pollution`).
- Updated: `PollutionEngineTest`, `OutfallReleasesTest`, `PollutionMapNetworkCodecTest`, `PollutionStoreCorpusTest` (new image `pollution-2.snbt`), GameTest `PollutionMeterGameTests`.
- Runs (classes from `affected.mjs select`, 4 workers): G1 16 classes 83 tests; G2 1 064 unit and 110 gate tests; G3 201 classes 1 157 tests; G4 79 classes 513 tests; all green. Fluid GameTests once at the end (run id `ground-pollution-1`): all 124 required tests passed, batch `ground-pollution` among them.
- Moved references: none. `PollutionEngineTest` amounts were raised to 40 000 and 7 000 so they stand above the new ground cutoff; the asserted values are the test's own.

## Not seen in a client

- The overlay's ground squares and tooltip, the meter's ground lines, the ledger command's text.
- Growth stopping in a live world (the GameTest posts the events itself), a swimmer's effects.
- The GuiPrototypes sheets of the layers: not made; the layer key's form waits for the main thread's answer.
