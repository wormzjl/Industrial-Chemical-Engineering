# Skirt shadow: review

Batch `2026-10-27-skirt-shadow` · branch `claude/modest-nightingale-c9932d` · 2026-10-27 · owner request: "Remove the shadow in drum/vessel skirt"; clarified: "the bottom plane looked through the windows, drums with very large diameter makes this worse".

## 1. Expected behaviour

The floor inside a skirt is the base plate under the vessel, seen through the access port and the slots. In a real skirt it is dim but even; nothing makes it darker in steps towards the middle. The owner wants it lit like the ground beside the vessel, at any diameter.

## 2. What the code did

- A vessel's interior cells are opaque full blocks (`EquipmentInteriorBlock`, closed). They cast the vessel's shadow: no skylight comes down through the vessel into its skirt.
- The floor plate's cells are shell cells, which pass light, so the floor got light only sideways from the skirt ring, Minecraft's one level less per block, and the shell model lights each quad by its own cell (no ambient occlusion). The result is a stepped diamond, darkest at the centre:

| Vessel | Floor and inside skirt cells, skylight | Noon brightness |
|---|---|---|
| Drum D 3 | 14 | 89 % |
| Drum D 6 | 12 to 13 | 74 to 81 % |
| Drum D 10 | 9 to 14 | 54 to 89 % |
| Column D 8 | 10 to 14 | 60 to 89 % |

- First reading, rejected by the owner and reverted (`f2b15b31`): the skirt's darker paint (structure channel, RAL 7031). The skirt's outside was already at full skylight.

## 3. Change (owner choice: "Skylight through")

- `ShapeRasterizer.skylight(shape)`: true when the vessel's skirt shows its inside: a drum's or a column's hollow skirt, a unit's skirt with openings (`ProcessUnit.openSkirt`, set by `skirt`: trickle-bed and ebullated-bed reactors). False for lying and bare drums, spheres, tanks, the adsorber and coker (closed openings) and every other unit: they keep their shadow on the ground.
- `EquipmentInteriorBlock.SKYLIGHT` (blockstate `skylight`, default false): light block 0 and skylight down, as a shell cell; still solid, still hides the faces beside it (Distant Horizons and face culling as before). `EquipmentStructure.interior(shape)` sets it.
- Placed vessels: an interior cell's state follows the code at load, as a cell's drawing does. The controller cell's load queues `restateLater`; `restate` runs one vessel a tick after retired and stale ones, only on a current, own vessel, sets each interior cell holding another state and waits for light before chunks are sent. A refresh restates the cells it keeps. No cell is added or removed, the stamp and the layout table do not change, and a column's assembly is not reverted (a refresh would have reverted it).
- Effect: the floor and the inside of the skirt take the skylight of the open sky above the vessel, 15 outdoors at every size; a roof, a cave or the night darkens them like the ground beside.

## 4. For the owner to validate

- The ground under a skirted vessel is no longer shaded; it is all inside the skirt.
- Under an Iris shader pack the sun's shadow map may still darken the inside (geometry casts it); not checked.
- Distant Horizons: interior cells keep their full occlusion shape; not checked in game.

## 5. Gates

- `./gradlew test` at `d02d221f`: 1644 / 1644, 0 failures (new `ShapeRasterizerTest.aSkirtShowingItsInsideLetsSkylightThrough`).
- `./gradlew runFluidGameTestServer` (fresh run id `skirt1`): 35 / 35, including the new `EquipmentCompatGameTests.aSkirtsFloorTakesTheSkylightAboveTheDrum` (floor skylight equals the sky above; cells set back to closed are restated at the controller's load, `equipment_restate … interior=102`).
- Software renders and the skylight probe (`tools/skirt-shadow/`): every floor and inside skirt cell at skylight 15 for the D 3, D 6, D 10 drums and the D 8 column.
- `slowGates`: not run; the owner waived the tests for the merge ("no test"). No in-game check (lighting judged by the GameTest and the probe).

Code guide: `equipment.md` (interior light contract, `EquipmentInteriorBlock` row, refresh tick order, test map); `verification.md` and `fluid-runtime.md` (35 fluid GameTests, three `EquipmentCompatGameTests`); README. Snapshot lines at `d02d221f`. Tool folder: `tools/skirt-shadow/`.

## 6. The GameTest's light waits (owner: "Fix the flaky skylight GameTest here: make it wait for the light engine to settle, not a tick count.")

- Expected: each light reading follows the light engine's run of the blocks just set; the 400-tick budget is only a bound. What the test did: it polled the floor's light until the budget ran out. A GameTest server does not pace its ticks (`waitUntilNextTick` only runs queued tasks; 400 ticks took 143 ms) and the engine runs on its own worker, so on a loaded machine the budget ran out first: one failure at the "old shadow" step in the 0.15.1 merge's run `sump2`.
- Fix (`33936ffd`, branch `claude/distillation-sump-enlargement-59e8e7`): after each change the test takes a future of the engine's run of the work queued in the drum's chunks (`ThreadedLevelLightEngine.waitForPendingTasks`: a batch runs its block checks, the propagation, then these tasks) and reads the light once when it completes. A waiting tick is held up to the nominal 50 ms, the engine's update scheduled first, so the budget is about 20 s of real time for the engine.
- Proof (`tools/skirt-shadow/gametest-probe/`): with the engine asleep 8 s before the interior turns opaque, the old poll fails as `sump2` did and the new wait passes (shadow after 129 ticks, 8,051 ms). Rule off (interior cells never pass skylight): the gate fails at its first reading, "the floor's skylight 12 is the sky's above the drum 15", not by timeout. Fluid GameTests 35/35 on a fresh run id (`skyfix1`). Code guide: `verification.md` (GameTests that read light). Release tree 0.15.2 (`1699862c`): `test` 1644/1644, `slowGates` 188/188.
