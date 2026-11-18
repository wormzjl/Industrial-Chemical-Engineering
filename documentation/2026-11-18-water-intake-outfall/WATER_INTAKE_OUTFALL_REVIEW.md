# Water intake and waste outfall: build review

Batch `2026-11-18-water-intake-outfall`, branch `claude/water-intake-outfall-0e90c8`. Class: PHYSICS (two new boundary devices; no existing reference may move). The main thread (Fable) wrote the design; this thread built it.

## 1. The owner's request (2026-11-18)

"Add in world fluid intake (pumping water) and fluid dump (waste water), hook dump to pollution system." Owner choices: the intake needs **a real water body** (its strainer touches world water joined to at least about 100 water blocks; no blocks removed); the dump's non-evaporating toxic and corrosive parts **pollute at the outfall**; the dump **places no blocks** (an effect only).

## 2. The water intake

### Physics

A pump's suction line ends in a strainer under the surface of a river or lake. The water stands at atmospheric pressure at its surface; the strainer adds an entry loss. A large body is unchanged by what a pump draws from it.

### What it is

- One block, `icheme:water_intake` (`ModBlocks.WATER_INTAKE`, `FluidDeviceBlock.waterIntake`): a generator boundary (flow enters the plant only), registered by `Placement.waterIntake`.
- State: the catalog's pure water at 298.15 K (`CoolingTower.AIR_TEMPERATURE`, as the gas intake's air, fluid-interfaces D7) and 101 325 Pa (`PhaseLayout.AMBIENT_PRESSURE`); 1 m at the joined pipe's bore, entry loss K = 3 (`Placement.INTAKE_LOSS`, through `PhysicalFluidTopology.boundaryHalf`, as the gas intake, D8). No pump of its own: a pump on the line draws from it. The depth of the strainer below the surface is not modelled (the boundary stands at the surface's pressure).
- The edit handler refuses every setting on it (`FluidNetwork`: "A water intake draws from the water body it touches; it has no settings."). The screen shows it as a fixed boundary (`FluidDeviceScreen.boundaryBlock`): no switch, no fields.

### A pump 5 m above the intake (existing laws, no new law)

At 0.01 m³/s (an in-line pump's default) in a 0.1 m line, v = 1.27 m/s, ½ρv² = 806 Pa:

| Term | Pa |
|---|---|
| Surface | 101 325 |
| 5 m of water, ρ g h = 997 × 9.81 × 5 | −48 903 |
| Strainer, K = 3 | −2 419 |
| 5 m of 0.1 m pipe, f ≈ 0.02, f L/D ½ρv² | −806 |
| Suction at the pump | ≈ 49 200 |

NPSH available = (49 200 − p_v 3 170) / (ρ g) = 4.7 m. The in-line pump's NPSH3 at its default duty is 1.7 m (`Npsh`, D21), its suction floor p_v + 0.85 ρ g NPSH3 = 17.3 kPa: it runs. The floor is reached at a lift of about 8.3 m at this flow; above that the pump delivers what its suction can feed (the existing `PUMP_SUCTION_LIMIT` row), as a real pump cavitates. Nothing new decides this; it is the line's own hydrostatics, the boundary's loss and the pump's NPSH law.

### The water body rule

- `runtime/fluid/WaterBody` (pure): from each of the intake's six faces, a bounded flood fill through face-adjacent water blocks; it stops at `WATER_BODY` = 100 blocks. Each face's water is its own body: two pools that meet only at the intake are two bodies.
- What counts as water (`FluidDeviceBlock.touchesWaterBody`): a block whose fluid state is in the `minecraft:water` tag: a source, a flowing block, a waterlogged block. Another mod's fluid counts only if it is in that tag. No biome, no "ocean", no block removed.
- Bound: the six faces are read first; each fill takes fewer than 100 blocks before it stops or ends and reads each one's six neighbours once: at most `MAX_READS` = 6 + 36 × 100 = 3 606 block reads per count (606 for one body; `WaterBodyTest` checks an ocean and the worst case of six separate 99-block lines).
- Loaded chunks only: a block in a chunk that is not loaded counts as no water (`Level.isLoaded`, no chunk is loaded). The intake then counts too few and closes; the next recheck after the chunk loads opens it again.

### Engine schedule

- The count is a fact about the world, not process state. It runs on the server thread in the block: at placement (`onPlace`, before the registration, which carries the answer), on a neighbour's change (`neighborChanged`: a neighbour that changed may have joined or cut the body), and on a scheduled block tick every `WATER_BODY_RECHECK` = 1 200 ticks (60 s at 20 TPS) for a body that changed beyond its neighbours or a neighbour chunk that loaded since. The scheduled tick is a due deadline the server keeps with the chunk (it survives a reload), not a per-tick poll. Never inside a solve, never on the engine's schedule.
- The result is the generator's switch (`Device.generatorEnabled`), changed by a queued ledger edit (`FluidWorldAuthority.waterBody`), as a player's input is; the island recompiles on the engine's schedule. No edit is queued when the switch is already so or an edit of the intake is pending (the next recheck sees it).
- Off: the boundary is closed (a disabled generator supplies nothing); the status line reads `NO WATER BODY: the strainer touches no water body of 100 blocks` (`ViewBuilder`), shown on the screen as "No water body: touch 100 connected water blocks".

### What is saved, and why

Nothing new. The switch is the generator's existing `generatorEnabled` fact, saved with the registration, so a reload keeps the last answer without a recount and the first recheck after the load corrects it. The intake is told from the gas intake by facts that already exist: `FluidDeviceSpec.ambient` (the boundary is the world outside) and its charge, water above half of it (`Placement.waterCharge`; the network's list ends with water, and the air's water is 1.6 %). `FluidDeviceBlock.stateFor` stands it as the water intake block. No fluid facts schema, state format or protocol number is taken; `FORMATS.md` does not change.

## 3. The waste outfall

### Physics

A discharge pipe ends open to the air above ground or water: the liquid leaves at atmospheric pressure and is gone from the plant. What evaporates from it reaches the air.

### What it is

- One block, `icheme:waste_outfall` (`ModBlocks.WASTE_OUTFALL`, `FluidDeviceBlock.outfall`): a void (receives only; nothing enters through it) registered by `Placement.outfall` with an ambient spec at 101 325 Pa, 1 m at the joined pipe's bore, exit loss K = 1 (`Placement.OUTFALL_LOSS`, a pipe's free discharge). It stands at its spec's pressure, as the brief's void, not at `PhaseLayout.atmosphere` of its height (the chimney's and flare's rule; `atmospheric()` is unchanged).
- `PhysicalFluidTopology.boundaryHalf` now adds a one-block sink's own minor loss (a passive void without ports or heat, lane 0) to its link, as it did a generator's. Every existing void has a minor loss of 0 (`Placement.place` builds it so; chimneys, flares, burners and converter drains are excluded by their ports or controls), so every other link is bit for bit as before.
- Edits refused ("A waste outfall discharges to the open air; it has no settings."); the screen is a fixed boundary.

### Pollution ("Pollute at the outfall")

Booked by `runtime/fluid/Releases` at the outfall's own cube, from its void's `EXCHANGE` transfers (`Releases.outfall`):

1. The volatile share to the air: the spill law, `HazardStandard.volatileMoles`, exactly as an open vessel's SPILL.
2. New: the rest of the liquid (the moles the spill law leaves on the ground) through the same `HazardStandard` references, `toxic` and `corrosive`, into the same cube, times `OUTFALL_YIELD` = 1 (owner decision pending, section 5). Not `combustible`: liquid that did not evaporate makes no flammable cloud.
3. Gas that reaches the outfall: a boundary transfer carries no phase, so the spill law runs on the whole stream: a compound volatile at 25 °C (share 1: hydrogen sulfide, ammonia above its boiling point, every permanent gas) goes whole, as a vent's gas. A condensable vapour (steam, a hot hydrocarbon vapour) is split by its share at 25 °C; its rest still counts toxic and corrosive. The only difference from "gas whole" is that such a vapour's non-volatile part is not counted combustible.
4. Solids it carries: particulate (`HazardStandard.particulate`), as the chimney's.

This is a stated game simplification, not physics: dissolved or floating waste does not become air pollution. It stands for the outfall's whole harm to its surroundings in the one field the game has. The javadoc of `Releases.OUTFALL_YIELD` and `Releases.outfall` says so.

Clean water books nothing: water has no toxic or corrosive reference and no flammability limit in the bundled data (`cubic_water.json` has no `hazard` or `flammability` record; `OutfallReleasesTest.cleanWaterBooksNothing`).

### Hand numbers (laws verified in planning)

The bundled catalog's own data (`FluidWorldAuthority.hazardColumns`), Wilson's share at 25 °C; the field by the step's own constants (passes of 20 s process time, exchange 0.12 per face, half-life 300 s open), the centre cube's index:

| Stream, 1 kg/s | mol/s | Volatile toxic | Remaining toxic | Combustible | Index after 60 s | Steady |
|---|---|---|---|---|---|---|
| Water + 1 mol % H2S | 55.02 (H2S 0.550) | 5 502 air-mol/s (H2S share 1) | 0 (water: no reference) | 13.75 | 0.33 (1.97 with no exchange) | ≈ 0.5 |
| Heavy cut `crude_pc08` (358 g/mol, Tb 687 K, 1 100 ppm) | 2.792 | 3.1e-4 (share 1.2e-7) | 2 538 | 1.2e-4 | 0.15 (0.91 with no exchange) | ≈ 0.23 |

An open tank's spill of the same streams: the first identical, the second about 3e-4 air-mol/s, an index of nothing. The heavy cut's remaining part is about 8e6 times its spill, so the brief's stop rule tripped: the main thread was told at once with options (section 5) and the build carries `OUTFALL_YIELD` = 1 meanwhile. In size it is half of what the H2S stream gives. The unit test reproduces the rates to 1e-9 of the formula (5 501.8 and 2 538.2 air-mol/s printed).

### Effect and sound

- No existing fx kind fits a falling stream (`FxKind`: flames, smoke, plume, haze); a stream needs a new kind and a new watch on the wire protocol, whose next number the solute batch holds. Not built; left as an owner option (section 5). No shader, `ClientConfig`, `FxShaders` or shader asset was touched.
- An existing loop fits: the outfall's falling liquid is a `Noise.Kind.WATER_FALL` source (the cooling tower's law, 72 + 10 log10 of the mass flow in kg/s, from 1 kg/s up), from what its void took over the island's interval (`FluidWorldAuthority` noise, server side, on the presentation schedule, no new wire field). 5 kg/s gives 79 dB at the block.

## 4. Both blocks

- Registration: `ModBlocks`, `ModItems`, the creative tab, the fluid device block entity's valid blocks, blockstates, block and item models, loot tables, recipes (intake: iron, iron bars, a pipe; outfall: three pipes and two stone bricks).
- No new `EquipmentShape.Kind`, `ConstructorColumn.Equipment` or `TopologyCompiler.Kind`: both are one-block boundaries, as the gas intake (fluid-interfaces D11); the process simulator's palette does not change.
- Equipment info: `tutorial.icheme.item.water_intake.serves` and `.needs` (the intake is in `EquipmentInfo.ITEMS_THAT_NEED`: a water body), `tutorial.icheme.item.waste_outfall.serves`. English lines in `en_us.json` only.
- Tutorial: no new step. The guide's one step about water, `still/water` (the condenser's cooling water), now names the two blocks; a new step would need a criterion the guide's path does not have (the still chapter builds and runs a still, it has no step about sourcing water). Recorded here as the brief asks.
- Models: placeholders, the simplest option of each (`WaterIntakeGeometry.BASKET`, `OutfallGeometry.GOOSENECK`), drawn by `FluidDeviceModel` (`"body": "water_intake"`, `"outfall"`). Three options each are rendered as sheets for the owner (section 6). Not final.

## 5. Owner items

| # | Item | Options |
|---|---|---|
| O1 | The outfall's remaining liquid (stop rule tripped, heavy cut 8e6 × its spill) | **A** yield 1, as built (heavy oil 0.15 per minute at 1 kg/s, steady 0.2 to 0.3 near the outfall); **B** yield 0.1 (0.015 per minute, steady about 0.02); **C** the rest to the ledger's `GROUND` only, not the air (reverses "Pollute at the outfall") |
| O2 | A falling stream under the outfall | **A** none (as built; the falling-water sound plays); **B** a new fx kind and a watch on the wire protocol, after the solute batch's protocol number, a later batch |
| O3 | The look of each block | The option sheets (section 6) |
| O4 | A waterlogged intake (it could stand in a water block, keeping it) | **A** as built: placing it replaces the water block; **B** a `WATERLOGGED` state on the intake block |

## 6. Option sheets

See the README's history line for the paths under `tmp/`.

## 7. Tests and runs

See the README's history line. Not verified: nothing is seen in a client (look, screen, sound, the recheck in a live world); no dev client was started.
