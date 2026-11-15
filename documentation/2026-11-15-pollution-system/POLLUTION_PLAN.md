# Pollution system: plan

Status: plan, 2026-11-15, for the owner's validation. Nothing is built. Branch `claude/pollution-system-a8e213`, cut from `main` at `e9edc974b` (0.31.0).

Items marked **(verify)** were not read in the code during the survey; the work package that touches them reads them first and reports a difference before it builds.

## 1. The owner's request (2026-11-15)

- The world is divided into 16 × 16 × 16 cubes; each cube stores its own pollution.
- Kinds: toxic, combustible, corrosive. The potency depends on the released material ("study and build a standard first").
- Pollution spreads to nearby cubes with time, on the 5 s engine, cheap, with a truncation of small numbers.
- Find the release sources and hook into them (not the blanketing valve for now).
- Toxic: a debuff on players and mobs at a low concentration, death at a high one. Particulates count as toxic, less potent.
- Combustible: nausea from a low concentration; at a flame source it explodes, with chain reactions; the blast damages or destroys equipment and blocks.
- Corrosive: equipment corrosion (later), a debuff, death at a high concentration.

## 2. The standard

Three sources, each already used by industry for the same purpose:

| Kind | Classification | Potency (reference concentration) | Mixture rule |
|---|---|---|---|
| Toxic | every compound with a NIOSH IDLH that is not a corrosive | NIOSH IDLH, ppm by volume | additive hazard index, `I = Σ y_i / ref_i` (the ACGIH mixture formula) |
| Corrosive | GHS H314 ("causes severe skin burns and eye damage") | NIOSH IDLH, ppm | the same |
| Combustible | has a lower flammability limit | the LFL in the dataset's `flammability` sub-record (exists: NIOSH, Bureau of Mines 627) | Le Chatelier, `I = Σ y_i / LFL_i` |
| Particulate | every released solid | 1.75 g/m³ (the NIOSH IDLH of carbon black, 1750 mg/m³) | added to the toxic index at effect time |

One number per kind, the **hazard index** `I`: the concentration divided by the reference. `I = 1` means the IDLH (toxic, corrosive) or the LFL (combustible). The indices are linear in the released moles, so a cube needs no per-compound amounts (section 4).

Thresholds, the same for every kind:

| Index | Toxic | Corrosive | Combustible |
|---|---|---|---|
| ≥ 0.1 | Poison I, Weakness I | Weakness I, Mining Fatigue I | Nausea (players). 10 % LEL is the gas detector alarm level in industry |
| ≥ 0.3 | Poison II, Slowness I, Blindness | 1 HP every 2 s, armour loses durability | Nausea, Slowness I |
| ≥ 1 | 2 HP/s, damage type `icheme:toxic_gas` (ignores armour) | 2 HP/s, `icheme:corrosive_gas` | flammable: explodes at a flame source |
| ≥ 3 | 6 HP/s | 6 HP/s | (oxygen-limited above 2, section 7) |

A player at 20 HP dies in 10 s at the IDLH and in 3.3 s at three times the IDLH.

Potency table for the compounds of `icheme:refinery`. WP1 read each value from its NIOSH Pocket Guide page on 2026-11-15 and cites it in the record's `source`. Two differences from the first draft: n-butane has an IDLH (1600 ppm, toxic); isobutane (N.D.) and isopentane (no NIOSH entry) have none. Carbonyl sulfide: 150 ppm, the interim AEGL-3 60-minute value (EPA), estimated. The LFLs are in the datasets already.

| Compound | Channel | IDLH, ppm (draft) |
|---|---|---|
| Hydrogen sulfide | toxic | 100 |
| Carbon monoxide | toxic | 1200 |
| Methyl mercaptan | toxic | 150 |
| Ethyl mercaptan | toxic | 500 |
| Benzene, toluene | toxic | 500 |
| Ethylbenzene | toxic | 800 |
| Xylenes (o, m, p) | toxic | 900 |
| n-Hexane | toxic | 1100 |
| Cyclohexane | toxic | 1300 |
| n-Pentane, isopentane | toxic | 1500 |
| 1,3-Butadiene | toxic | 2000 |
| Propane | toxic | 2100 |
| Ethanol | toxic | 3300 |
| Carbon dioxide | toxic | 40 000 |
| Carbonyl sulfide | toxic | no NIOSH IDLH; AEGL-3 (1 h), estimated flag |
| Crude cuts `crude_pc01..12` | toxic | 1100 (petroleum distillates, naphtha), estimated flag |
| Sulfur dioxide | corrosive | 100 |
| Ammonia | corrosive | 300 |
| Methane, ethane, ethylene, propylene, butanes, butenes, hydrogen | none (simple asphyxiants) | combustible only |
| Nitrogen, oxygen, water | none | none |

Not in version 1: oxygen displacement by asphyxiants; dose over time (Haber's rule); autoignition; upper flammability limits (a cube average hides the flammable zone at its edge, so a rich cube still ignites).

Data form: an optional `hazard` sub-record in a properties dataset, beside `flammability`:

```json
"hazard": { "channel": "toxic", "reference_ppm": 100, "estimated": false,
            "source": "NIOSH Pocket Guide ..., IDLH", "revision": "npg-idlh-r1" }
```

It has its own revision and is in no identity (`Package.scientificRevision`, `fluidRevision`, `physicsContent`), as `Flammability`: an edit moves no world, result or neural model.

## 3. Release sources

All amounts come from what an island booked at commit: `PassiveIntervalSolver.Result.boundaries()` (`ConservativeTransport.BoundaryTransfer`: node id, moles per compound, solids, kind). Nothing new is solved.

| # | Source | Where it is booked | Released | In version 1 |
|---|---|---|---|---|
| S1 | Open (floating-roof) tank vent | `Kind.VENT` at an open vessel (`OpenVessel`) | gas, at the roof (`cell + levelHeight`) | yes |
| S2 | Open tank spill | `Kind.SPILL` | liquid; its volatile share (rule below), at the vessel's foot | yes |
| S3 | Chimney tip | `EXCHANGE` at a `VOID` whose device is `chimney()` (`Chimneys.booked`) | flue gas and solids, at the stack top (`cell + geometry.length`) | yes |
| S4 | Flare tip | the run into a `flare()` void (`Flares.booked`) | burning: SO2 by the gas's S atoms, soot by `Flare.soot` as particulate; FLAME OUT: the whole gas unburnt | yes |
| S5 | A pipe (any one-block fluid device) destroyed by an explosion | the applied removal (`Placement.remove`, `WorldTopologyLedger`) **(verify: where the removed contents are booked)**, marked as a blast's by `FluidDeviceBlock.onBlockExploded` | gas whole, liquid by its volatile share, at the device's cell | WP6 |
| — | A pipe or vessel a player breaks or dismantles (owner, 2026-11-15: "Pipe does not release under normal circumstance, unless they are destroyed by explosion") | — | nothing; its contents are booked as removed, as today | no |
| S6 | One-time flush of a vessel's trace or gas | `Kind.FLUSH` | gas, at the node | yes |
| S7 | Cooling tower air | `Kind.ATMOSPHERE` | the compounds its `losses` carry (water has no hazard; sour water strips H2S) | yes, at no extra code |
| S8 | Firebox, engine and burner fuel voids (`ValveSource.sink()`) | `EXCHANGE` at the sink void **(verify: whether the flue gas is a real stream to a chimney)** | SO2 by the fuel's S atoms, only where no flue stream exists | yes if verified |
| S9 | Safety valve outlet to air **(verify: where its run ends)** | the run out of the guarded node | gas | yes if it ends in the open |
| S10 | Explosion breach of equipment | section 7 | the contents, through S5's path | WP6 |
| — | Blanketing valve vent (`GasBlanket`, also `Kind.VENT`) | told apart from S1 by `Inventory.blanket` | — | no (owner) |
| — | A plain `VOID` device (creative sink), converter drains, a still's distillate and vent outlets (they feed pipes) | — | — | no |
| — | Solid fuel fires (a still's firebox items) | no stream exists | — | later |

Volatile share of a liquid (S2, S5): `min(1, Psat_i(298.15 K) / 101325 Pa)` with Wilson's correlation `Psat = Pc exp(5.373 (1 + ω)(1 − Tc/T))` on the package's own Tc, Pc, ω. Propane and butane evaporate whole, benzene 12 %, a heavy cut almost nothing. The rest leaves to the ground and is counted in the pollution ledger's `ground` total (a later soil system can read it).

## 4. The field

**Storage.** Per dimension, a sparse map from a cube (the chunk section, `SectionPos.asLong`) to four `double` amounts: toxic, combustible, corrosive, particulate, each in air-mole equivalents (`Σ n_i / ref_i`). The index is the amount over the cube's air moles; amounts are stored, not indices, so the ledger stays exact when a cube's air share changes (WP2 brief, 2026-11-15). A release adds `Δ I = Σ n_i / (ref_i · N)` to its cube, where `N` is the cube's air amount: `φ · 4096 m³ · 40.9 mol/m³` (298.15 K, 1 atm; `φ` the cube's air share, below). An open cube holds 167 500 mol of air. Particulate: `Δ I = m / (1.75 g/m³ · φ · 4096 m³)`.

Storing indices and not compounds loses the compound's name (a later detector shows "toxic 1.4", not "H2S 140 ppm"). It makes a cube 16 bytes and the step independent of the package. Owner decision D4.

**Air share φ.** Read once when a cube becomes active and again every 60 s: the share of the section's blocks without a collision shape (one pass over the section's palette counts, `PalettedContainer.count`). `φ < 0.02`: the cube is closed, stores nothing and passes nothing. A release in a room of a few blocks therefore reaches a high index fast, and an open field does not. The face between two cubes passes in proportion to `min(φ_a, φ_b)`.

**Ventilation.** A cube is *open* when its top is at or above the `WORLD_SURFACE` height at its centre column, else *enclosed* (indoors, underground). Read with φ.

**The step** (one pass per 5 s of online time, `dt` = 5 s × `fluid.timeScale`, 20 process seconds by default). For each active cube and channel, explicit finite volumes on amounts `A = I · φ`:

1. Exchange with each of the 6 face neighbours: `F = k · min(φ_a, φ_b) · (I_a − I_b)`, `k = min(1/7, K_FACE · dt / 20 s)`, `K_FACE = 0.12` (an eddy diffusivity of 1.5 m²/s over 16 m). `k ≤ 1/7` keeps the explicit step stable and positive.
2. Decay: `I ← I · 2^(−dt / t½)`, `t½` = 5 process minutes in an open cube, 60 in an enclosed one. In rain, corrosive and particulate decay 4 times faster in open cubes (washout).
3. Particulate settles: an extra share `k_s = 0.05` moves to the cube below; at the ground it is removed (to the ledger's `ground`).
4. The world's top and bottom are sinks. A face to an unloaded chunk is closed, and cubes of unloaded chunks keep their values and do not step.

**Truncation.** `CUTOFF = 1e-3` (1 % of the first effect threshold):

- A flow into a neighbour that is not active and would stay below `CUTOFF` does not happen: the amount stays in its cube. So a release of total index `Q` can activate at most `Q / CUTOFF` cubes.
- A channel below `CUTOFF` after the step is set to 0 and booked as `dissipated`. A cube with four zeros leaves the map.
- A hard cap `pollution.maxActiveCubes` (default 8192 per dimension): above it the lowest cubes are dissipated first and a log line says so.

**Ledger.** Per dimension and channel: `released = stored + decayed + dissipated + burnt + ground`, exact to rounding; a test holds it.

**Expected numbers** (open cube, defaults):

- 1 mol/s of H2S (34 g/s) from a tank roof: 20 mol per pass, 119 ppm, `ΔI_toxic = 1.19` per pass. The source cube stands at 0.9 after a pass and 2.1 just after the next release lands (lethal at its peak), a face neighbour near 0.49 (damage level 0.3), the second ring near 0.14 (debuff). (Corrected 2026-11-15 from WP2's steady state, held by `PollutionFieldTest`; the first draft said 1.5 to 2, 0.2 to 0.3 and below 0.1.)
- 10 mol/s of methane: `ΔI_comb = 0.024` per pass, steady near 0.03: no hazard in the open. A flammable open cube needs about 200 mol/s (3.2 kg/s), or the same leak in a room with `φ·V` of a few hundred m³: 10 mol/s reaches the LFL in a 200 m³ room in one pass.
- Cost: one pass over 2000 active cubes is about 50 000 float operations, well under 1 ms.

## 5. Schedule and threads

- `science/pollution/` is pure Java with no Minecraft type: `HazardStandard` (indices from moles), `PollutionField` (immutable snapshot, `step`), `PollutionLedger`, `Explosion` arithmetic. Deterministic: cubes iterate in key order, `DeterministicMath` for `2^x`.
- `runtime/pollution/PollutionService` (server thread, one per server, a field per dimension):
  - **Collect**: at each island commit, a new `Releases` hook beside `chimneys.booked` and `flares.booked` in `ShaftTrains.booked` (`runtime/fluid/Releases`) turns the result's boundaries into `(dimension, cube, 4 index amounts)` and adds them to the service's pending buffer. Reads only.
  - **Pass**, every 100 ticks of online time (the deadline is checked on the tick, as the presentation bucket's): on the server thread, refresh φ and ventilation for due cubes, mark loaded chunks, take the pending buffer, and submit an immutable `PollutionCommand(snapshot, pending, dt, settings)` as a fourth job family of `ProcessSolveJobs` (`ProcessSolveCommand permits …, PollutionCommand`). The worker runs `PollutionField.step` and posts the next field. The drain installs it. Releases that arrive meanwhile wait in the buffer for the next pass, so nothing is lost. One job at a time; a refused job (pool at capacity) is asked again at the next tick.
  - **World pass**, every 20 ticks on the server thread, on the installed field only (reads, no step): entity effects (section 6), ignition (section 7), particles.
- No packet carries the field. Players see particles (`ServerLevel.sendParticles`, a few per cube above 0.1 within 48 blocks: green toxic, yellow corrosive, grey particulate, a faint shimmer for combustible) and their status effects. A detector item and a haze shader are follow-ups.
- Command `/icheme pollution` (permission 2): `get` (the cube at the player), `add <channel> <index>`, `clear [radius]`, `ledger`.
- Config `pollution.*`: `enabled`, `halfLifeOpenMinutes`, `halfLifeEnclosedMinutes`, `cutoff`, `maxActiveCubes`, `effects`, `explosions`, `blockDamage`, `explosionPowerCap`.

## 6. Effects on players and mobs

Each second, for each cube with any index ≥ 0.1: the living entities inside its box (`getEntitiesOfClass(LivingEntity, box)`), by the table of section 2. Toxic index = toxic + particulate. Effects are applied for 3 s and renewed. Not affected: creative and spectator players, entity types in the tag `icheme:pollution_immune` (default: `#minecraft:undead`, golems, the wither, the ender dragon). Nausea is given to players only. Death messages `death.attack.icheme.toxic_gas`, `…corrosive_gas`.

For equipment corrosion (later) the service gives `corrosive(dimension, pos)`; nothing reads it yet.

## 7. Ignition and explosion

**Ignition sources**, checked only for cubes with a combustible index ≥ 1 (rare, so cheap):

| Source | Detection |
|---|---|
| Fire, soul fire, lava; lit campfires; lit furnace, blast furnace, smoker; torches and wall torches (all kinds but redstone); lit candles and candle cakes; jack o'lantern; Create's lit blaze burner | block tag `icheme:ignition_sources` (data-driven); a block with a `LIT` property counts only while lit. First the section's palette (`maybeHas`, O(palette)); one full scan of the section only when the palette says yes |
| Burning entities, blazes, fireballs, lightning, primed TNT, firework rockets | entities in the cube that are on fire or in the entity tag `icheme:ignition_sources` |
| Any explosion | `ExplosionEvent.Detonate` inside a flammable cube |
| A flare with a flame, firing burners (fired heater, liquid-fired still), a still's solid fire, a running combustion engine or gas turbine | the runtime's published state (`Flares.view` heat > 0, the burners' and prime movers' views), at the device's cell |
| Not sources (owner decision D2) | lanterns, redstone, magma blocks, electric motors |

**The blast.** The energy of a flammable mixture per mole of mixture at its LFL is nearly the same for hydrocarbons (Burgess-Wheeler: `LFL · LHV ≈ 44 kJ/mol`), so the combustible index alone gives the energy; no fifth channel. A cube burns at most the index 2 (about stoichiometric; above it oxygen limits).

- `E = min(I, 2) · φ · 167 500 mol · 44 kJ/mol`; an open cube at the LFL: 7.4 GJ.
- TNT equivalent with a vapour cloud yield of 3 %: `m = 0.03 E / 4.6 MJ/kg`; the open cube at the LFL: 48 kg.
- Minecraft power by cube-root (Hopkinson) scaling: `power = 4 · (m / 50 kg)^(1/3)`, capped at `pollution.explosionPowerCap` (default 8). An open cube at the LFL is one TNT (power 4), at index 2 power 5; a 200 m³ room at the LFL power 1.5.
- `level.explode(null, source, power, fire = true, BLOCK)` at the ignition source. The cube's combustible index falls by what burnt (`burnt` in the ledger); 2 % of it becomes particulate (soot).
- **Chain reaction**: each of the 6 neighbours with index ≥ 1 ignites 3 ticks later at its centre (a flame speed near 100 m/s), and so on. At most 4 blasts per tick per dimension; the rest wait in a queue. Blasts that break pipes release their contents (S5), which the next pass adds to the field.

**Equipment under a blast** (owner decision D1). Today `EquipmentShellBlock.onBlockExploded` and `EquipmentInteriorBlock.onBlockExploded` ignore every explosion, and a structure is only ever removed whole.

- One-block fluid devices (pipes, valves, one-block tanks): a blast destroys them outright, as ordinary blocks; they have no damage meter (owner, 2026-11-15: "they don't get a damage meter for the sake of simplicity"). `FluidDeviceBlock.onRemove` already unregisters them; only this removal releases their contents (S5). A pipe a player breaks releases nothing.
- **The burst pipe's sound** (owner, 2026-11-15: "need a damaged pipe SFX as well"): a pipe a blast destroys while it holds contents plays a new sound at its cell, `icheme:pipe_burst`: a metal rupture, then the escaping contents (a gas hiss, or a liquid gush by the phase released), its level by the amount released; an empty pipe plays the rupture alone. One sound per blast and cube at most, so a chain of blasts does not stack hundreds. Built by the `models-and-sfx` skill's rules and `tools/process-sounds`, played server-side with `level.playSound` (a one-shot world event, not a looped `Noise` source).
- Constructed equipment, proposal A: it is not removed. A blast whose power at the cell exceeds `BREACH_POWER` (2) **breaches** it: its vessel vents and spills as an open vessel does (`OpenVessel`'s law on its inventory), its machines stop, its status line reads `BREACHED`, until a player repairs it with the repair kit (`RepairKitItem`, as pump wear). The breach is a fact (it survives a save): one appended field on the structure's record.
- Proposal B: above a second power (6) the structure is destroyed whole (`EquipmentStructure.dismantle`, no refund), its contents released.

## 8. Saves

- New store per dimension, `icheme_pollution` (`level.getDataStorage()`): **facts**, schema 1. Cubes (key, 4 floats), the ledger totals, the pending buffer. φ, ventilation and the blast queue are state and are not saved.
- **Crash-proof under world corruption** (owner, 2026-11-15: "also needs to be crash-proof when world corruption happens"). Pollution never refuses a world and never stops the server. This differs from the fluid facts' rule (a damaged facts section refuses the world), on the owner's word, for this store only:
  - *A damaged or newer store.* The load catches every failure (a truncated or unreadable NBT, a wrong type, a newer schema). It copies the file to `icheme_pollution.damaged-<n>.dat` beside it, untouched, logs one line `pollution_store status=SET_ASIDE dimension=… detail=…`, and starts that dimension with an empty field. The load never throws into `computeIfAbsent`.
  - *A damaged cube.* Each cube validates alone: a key outside the dimension's height, a non-finite or negative value, or a value above `MAXIMUM_INDEX` (1e6) drops that cube and keeps the others; the count is in the log line and in the ledger's `dissipated`. Ledger totals that do not validate are rebuilt from the cubes that were kept.
  - *A world that no longer matches.* A cube whose chunk is missing, unreadable or regenerated just keeps its value until the chunk loads; φ and ventilation are read again from the blocks. Every read of the world (section, heightmap, entities, block states) goes through a guard that treats a failure as "closed cube, no source", never as an exception.
  - *A failure at run time.* A pass, a world pass, a worker job or a hook that throws is caught at the service's edge: the last good field stays, one log line names the stage, and after 3 failures in a row pollution turns itself off for the session (`pollution status=DISABLED`), the server and the fluid engine running on. A hook failure never reaches `ShaftTrains.booked`'s other units. A non-finite value produced by a step is rejected with that step (the previous field stays).
  - *The save.* Written through `SavedData` (Minecraft's own temp-file-and-rename); a failed write logs and leaves the earlier file.
- A corpus image `src/test/resources/compat/pollution/pollution-1.nbt` and its gate.
- A row in `documentation/reference/FORMATS.md`. No fluid facts schema changes until WP6 (the breach field): WP6 reserves its number first (COLLABORATION.md, "Numbers").
- No wire format changes in WP1 to WP5.

## 9. Work packages

Lane: new packages `science/pollution`, `runtime/pollution`; touches of hot files are named per package. Class: new feature; no existing reference moves (the hooks only read). Each package is under 1 500 production lines and leaves the branch compiling.

| WP | Content | Files | Tests (Gradle `test --tests`) |
|---|---|---|---|
| 1 | Standard and data: `MaterialCatalog.Hazard` sub-record and `readHazard` beside `readFlammability` (line 698, 910); accessors `hazards(packageId)` (channel and reference per conserved compound) beside `heatingValues` (877); `hazard` records in the datasets of section 2, each read from its NIOSH page; `science/pollution/HazardStandard` (indices from moles and solids, the volatile share) | `MaterialCatalog` (hot, thermo lane), `data/icheme/materials/properties/*.json`, `science/pollution/HazardStandard.java` | `HazardStandardTest` (H2S 100 ppm gives index 1; Le Chatelier on a two-gas mix; Wilson share of propane 1, of a heavy cut < 0.01); a data gate: every compound with an LFL or a listed IDLH has its record, every record a source; `MaterialCatalog` load tests that the sub-record is in no identity |
| 2 | The field: `PollutionField`, `PollutionLedger`, `PollutionSettings`; step, truncation, cap | `science/pollution/*` | `PollutionFieldTest`: the ledger closes to 1e-6 relative over 1000 passes; no negative value; a point release never activates more than `Q / CUTOFF` cubes; a closed cube (φ 0) takes nothing; the same input gives the same bits in any insertion order; the numbers of section 4 within 20 % |
| 3 | The service: `PollutionService`, `PollutionStore`, `PollutionCommand` in `ProcessSolveJobs`, the tick deadline in `IChemE.onServerTickPost`, φ and ventilation, the config, the command | `runtime/pollution/*`, `runtime/ProcessSolveJobs` and its service (hot, runtime lane), `IChemE` | `PollutionStoreTest` (round trip; a truncated tag, a wrong type and a newer schema each set the file aside and give an empty field without throwing; one NaN cube among ten drops one and keeps nine), the corpus gate (a good image and a damaged one), `PollutionServiceTest` (a release during a running job lands in the next pass; a step that throws keeps the last field; three failures disable the service and nothing propagates), `FormatsTableTest` |
| 4 | The hooks: `runtime/fluid/Releases` on `ShaftTrains.booked`; S1 to S9 after their verification; positions from the registrations | `runtime/fluid/Releases.java`, `ShaftTrains`, `FluidWorldAuthority` (wiring, hot), the removal path | `ReleasesTest` on built graphs: an open tank's vent books its gas at the roof cube; a blanketed drum's vent books nothing; a chimney's solids go to particulate; a flare burning H2S books SO2 as corrosive, and unburnt books H2S as toxic; a pipe a player breaks books nothing (the blast's release is WP6's test) |
| 5 | Effects: the world pass, damage types, tags, particles, `en_us.json` keys | `runtime/pollution/PollutionEffects.java`, `data/icheme/damage_type/*`, `data/icheme/tags/**`, `en_us.json` | `PollutionEffectsTest` (the threshold table as a pure function); a GameTest: a zombie is immune, a cow dies in a cube at index 3 |
| 6 | Ignition, blasts, chain, block damage; then equipment under a blast per D1 | `runtime/pollution/Ignition.java`, `Blasts.java`, `FluidDeviceBlock`, `EquipmentShellBlock`, `EquipmentInteriorBlock`, `EquipmentStructure` (hot, equipment lane), the facts field, the `pipe_burst` sound (`sounds.json`, the ogg, its subtitle key) | `ExplosionLawTest` (48 kg and power 4 at the LFL in an open cube); a GameTest: a torch in a cube at index 1.2 explodes and two flammable neighbours follow within 10 ticks; a cube at 0.9 does not |
| 7 | Documents: code guide chapter `pollution.md` and its README rows, `FORMATS.md`, the batch README | `documentation/**` | none |
| 8 | Pollution meter item (section 11) | `world/item/PollutionMeterItem.java`, registries, model, texture, sound, lang | `PollutionReadingTest`, one GameTest |
| 9 | FTB Chunks map overlay (section 12), optional mod | `compat/ftbchunks/**`, `network/` (one payload pair, `NetworkProtocol` version), `build.gradle` (compile-only dependency) | the column summary as a pure function; the look by the owner's in-game check |

Order: 1 → 2 → 3 → 4 → 5 → 8 → 6 → 9 → 7. WP1 and WP2 share no file and may run side by side.

Pitfalls for every package: `ShaftTrains.booked` also runs on a replayed span (`Flares` says "committed or replayed"), so a release must be counted once: collect only where the ledger books, and prove it with a test that replays a span. The blanket's vent and the open vessel's vent have the same `Kind`. The `equipmentGates` and `columnGates` class lists in `build.gradle` see only what their tests load. `en_us.json` only; the other languages wait for the translation commit. No dev client; GameTests need a fresh `fluidGameTestRunId` and must remove what they register.

## 10. Owner decisions

Decided 2026-11-15 (owner: "Yes, start"): D1 to D6 as recommended.

| # | Question | Recommended |
|---|---|---|
| D1 | Constructed equipment in a blast | A: breach and repair. B (destroyed whole above power 6) as a config option, off by default |
| D2 | Torches, candles and campfires as ignition sources | Yes (open flames); lanterns and redstone no; the tag lets a pack change it |
| D3 | A liquid spill | Its volatile share by vapour pressure enters the air; the rest goes to the ledger's `ground` |
| D4 | A cube stores four hazard indices, not compound amounts | Yes |
| D5 | Decay half-life: 5 process minutes in the open, 60 enclosed (with `fluid.timeScale` 4: 75 s and 15 min of play) | Yes; in the config |
| D6 | Simple asphyxiants (nitrogen, methane, CO2 displacing oxygen) | Not in version 1 |

## 11. Pollution meter (owner, 2026-11-15: "also add a pollution meter item")

A handheld item, `icheme:pollution_meter` (WP8).

- **Reading.** The four indices of the cube the player stands in, each with a word: SAFE (< 0.1), WARNING (≥ 0.1), DANGER (≥ 0.3), LETHAL (≥ 1; FLAMMABLE for combustible). Toxic is shown with particulate added, as the effects count it, and particulate again on its own line.
- **On request only** (AGENTS.md, engine schedule): holding the meter in either hand is the request. While it is held the server sends that player the reading on the action bar once a second, from the installed field (a read; nothing steps). Using the item prints the full reading in chat, with the cube's air share and whether it is open or enclosed. No other client receives anything.
- **Warning tick.** While held, a short click whose rate rises with the largest index (none below 0.1), so a player hears a cloud before walking into it. Built by the `models-and-sfx` skill; one sound event `icheme:meter_tick`.
- **Files.** `world/item/PollutionMeterItem.java`, `registry/ModItems`, `ModCreativeTabs`, the item model and texture (`models-and-sfx`), `en_us.json` keys, a recipe; the server side in `runtime/pollution/PollutionEffects` (the world pass already runs each second). No menu, no screen, so no GUI work; one action-bar payload is vanilla's (`displayClientMessage`), so no wire format changes.
- **Tests.** The reading's words as a pure function (`PollutionReadingTest`); a GameTest: a player holding the meter in a cube at toxic 1.2 receives LETHAL, a player without it receives nothing.

## 12. Map overlay on FTB Chunks (owner, 2026-11-15: "research if you can add pollution overlay to ftb chunk map")

**Finding: possible with FTB Chunks' public client API, without mixins.** Read at the `1.21.1/main` branch of `FTBTeam/FTB-Chunks`, 2026-11-15 (`common/src/main/java/dev/ftb/mods/ftbchunks/`):

- There is no layer or overlay API. There is `api/client/event/MapIconEvent`: `LARGE_MAP` (fired when the full-screen map gathers its icons) and `MINIMAP` (fired often; "handlers should run quickly"), each taking `MapIcon`s through `add`, with `getDimension()`.
- A `MapIcon` (`api/client/icon/MapIcon`) has a world position, `isZoomDependant`, `getIconScale`, `getPriority`, `addTooltip` and `draw(mapType, graphics, x, y, w, h, …)` with a plain `GuiGraphics`, so it can draw any quad.
- Sizing (`client/gui/RegionMapPanel`, `MapIconWidget`): a zoom-dependent icon is `regionTileSize / 128 × getIconScale` pixels wide and is centred on its position; a region tile is 512 blocks. So `getIconScale = 4` is exactly one chunk (16 blocks) at every zoom.
- `FTBChunksClientAPI.registerMinimapComponent(MinimapInfoComponent)` adds a text line under the minimap.

**Design (WP9, optional mod, as `compat/create`).**

- Large map: one translucent square per polluted chunk column, centred on the chunk, coloured by its worst channel (green toxic, orange combustible, yellow corrosive, grey particulate), its opacity by the index (0.1 faint, ≥ 1 strong), low priority so waypoints stay on top. The tooltip lists the four indices. The map is flat, so a column shows the largest index over its cubes.
- Minimap: an info line "Pollution: toxic 0.4" for the player's own cube through `registerMinimapComponent`; no squares there (the event is hot, and squares do not turn with a rotating minimap).
- Data, on request only: opening the large map sends one request for the map's dimension; the server answers with the column summary (chunk x, z, 4 bytes) of the cubes within 512 blocks of the player and repeats it each pass (5 s) while the map stays open; closing ends it. One new payload pair on `NetworkProtocol`, so the protocol version is bumped. At the 8192 cube cap a full answer is under 100 kB; a usual one is a few hundred bytes.
- A config switch `pollution.mapOverlay` (server: who may see it: everyone, or only players carrying a pollution meter; default: meter).

**Open points for WP9, read before it builds:** FTB Chunks' licence and Maven coordinates for a compile-only dependency (with FTB Library and Architectury, which it needs); whether `MapIconEvent`'s `Consumer` runs while the map is open or once at its opening (it decides how the squares refresh; `RegionMapPanel` gathers icons in `addWidgets`); a dev-client check is needed to judge the look, so the owner starts it (AGENTS.md, in-game checks).

## 13. Follow-ups outside this batch

A fixed gas alarm on the data network; a gas mask; a haze shader in the effects system; equipment corrosion from the corrosive index; soil and water pollution from the `ground` total; solid-fuel smoke; wind.
