# Respirator and consumable filter: review

Batch `2026-11-18-respirator`, branch `claude/respirator-bb985a`. Law designed by the main thread; built by an Opus sub-thread. Nothing here has been seen in a client.

## The request

Owner, 2026-11-18: "Add a respirator as player gear to allow players move within heavily polluted regions, with consumable filter". Choices the same day: worn in the helmet slot, and in a Curios slot when Curios is installed ("Curios slot if present"); "One combined filter".

## Expected behaviour (real life, simplified)

A full-face air-purifying respirator lowers what its wearer breathes by an assigned protection factor of 50. Its cartridge is used up by the mass of contaminant it takes out, so it lasts long in light pollution and briefly in heavy pollution. A spent cartridge passes everything. It does not protect skin, clothing or metal, and it does nothing about a flammable cloud igniting.

In the mod, with a working filter:

| Cube index (any channel) | Breathed | Level without | Level with |
|---|---|---|---|
| 0.1 | 0.002 | LOW (effects) | NONE |
| 5 | 0.1 | EXTREME | LOW: effects start |
| 15 | 0.3 | EXTREME | MEDIUM |
| 50 | 1 | EXTREME | HIGH: damage starts |
| 150 | 3 | EXTREME | EXTREME |

Filter life, one channel at a constant index (one effects pass = 1 s of play):

| Index | Load per second | Life |
|---|---|---|
| below 0.1 on every channel | 0 | not used |
| 0.1 | 0.1 | 6000 s = 100 min |
| 1 | 1 | 600 s = 10 min |
| 5 | 5 | 120 s = 2 min |
| 50 | 50 | 12 s |

## The law (`science/pollution/Respirator`, pure)

- `breathed(index, remaining) = index / PROTECTION_FACTOR` while `remaining > 0`, else `index`.
- `load(indices, seconds) = Σ(index of each channel where index ≥ Exposure.LOW) × seconds`.
- `after(remaining, load) = max(0, remaining − load)`; a result at or below `EMPTY` is 0, so many small loads leave no crumb (0.1 × 6000 passes empties the filter in exactly 6000 passes).
- A pass is protected by the capacity before its own load: the pass that empties the filter is still protected, the next is not.
- `protect(worn, remaining, indices, levels)` returns `levels` itself when no respirator is worn or its filter does not protect; otherwise the breathed levels.

### Constants (for the owner's veto)

| Constant | Value | Where |
|---|---|---|
| `Respirator.PROTECTION_FACTOR` | 50 | assigned protection factor of a full-face air-purifying respirator |
| `Respirator.FILTER_CAPACITY` | 600 index-seconds | a new filter: 10 min at one channel at index 1 |
| `Respirator.EMPTY` | 1e-9 index-seconds | numeric floor of the remaining capacity (not a physics value) |
| Load threshold | `Exposure.LOW` = 0.1 | no use below the first effects level |
| Pass seconds | `SimulationTime.playSeconds(PollutionEffects.EFFECT_TICKS)` = 1 s | the damage's conversion |
| Filter recipe yield | 2 filters per craft | `data/icheme/recipe/respirator_filter.json` |

## Where it acts

- `PollutionEffects.dimension` keeps each cube's real indices beside its levels (`indices`). Per entity it calls `treat(entity, levels, RespiratorGear.protect(entity, here, indices, levels), sources, pass)`.
- The new `treat` overload uses `breathed` for the effects and damage of all four channels (toxic, corrosive, particulate, and the combustible channel's nausea and slowness) and the cube's own `levels` for armour corrosion.
- The old `treat(entity, levels, sources, pass)` is `treat(entity, levels, levels, sources, pass)`. An entity without working gear gets `levels` itself back from `protect`, so it takes exactly the path before: every `PollutionEffects` and `Exposure` value is unchanged, bit for bit (`ExposureTest` untouched; `RespiratorTest.withoutWorkingGearTheCubesLevelsAreUsedAsTheyAre` checks the identity).
- Not changed: armour corrosion (the cube's real corrosive level), gas ignition, blasts, the water rule of the water intake batch. The respirator is not damageable (no durability), so corrosive air does not wear it.
- `RespiratorGear.protect`: players only (a mob never wears it); creative and spectator players take neither protection nor load. The load is booked only by the cube that holds the player's block position (`here`), so a player whose box touches two cubes is charged once a pass.
- Nothing runs on the tick loop: the effects pass already ran every 20 ticks. No engine or process state; the filter is item data.

## Items

- `icheme:respirator`: an `ArmorItem` (helmet type) of a new armour material `icheme:respirator`: defence 0, toughness 0, enchantment value 0, no durability, leather equip sound. Its default attribute modifiers are empty (no "+0 Armour" line). Stacks to 1.
- The fitted filter: item component `icheme:respirator_filter`, a `Double`, the remaining capacity 0 to 600 (codec refuses anything else; network `ByteBufCodecs.DOUBLE`). Absent: no filter. 0: spent filter still fitted.
- Use in hand: when no filter works (none fitted, or spent) and a filter is in the inventory, it fits one; otherwise it is put on, as any helmet.
- `icheme:respirator_filter`: stacks to 16. Used while a respirator is worn (either slot), it replaces the fitted filter with a full one: one filter is used, and a part-used filter that is replaced is lost (said in its tooltip).
- Tooltip: "Filter: 73 %", "Filter spent" or "No filter" (a working filter never reads 0 %), then two plain lines. Item bar: the remaining share, green to red.
- The meter (`PollutionMeters`): while a respirator is worn, the action bar gains " | Mask 73% protected" (or "Mask 0% filter spent", "Mask 0% no filter"), and the full chat reading gains the same line. Texts only in `en_us.json`.
- Field Guide: there is no pollution chapter in `assets/icheme/tutorial/guide.json`. The respirator and its filter get item pages instead (`tutorial.icheme.item.respirator.serves`, `.needs`, `tutorial.icheme.item.respirator_filter.serves`; `EquipmentInfo.ITEMS_THAT_NEED` gains `respirator`), within the 14-word gate. A guide chapter on pollution is item R1 below.
- No new sound.

## Recipes (vanilla items only)

- Respirator: `LLL / SGS / _I_`: leather (L) ×3, string (S) ×2, glass pane (G), iron ingot (I).
- Filter ×2: a column of paper, charcoal, any wool (`#minecraft:wool`).

## Curios (optional)

- Dependency: `top.theillusivec4.curios:curios-neoforge:9.5.1+1.21.1:api`, `compileOnly`, from Curios' own maven `https://maven.theillusivec4.top/` (coordinates from Curios' README; the version from its `maven-metadata.xml`, the newest 1.21.1 release). Never on a runtime, no digest pin. `neoforge.mods.toml`: `curios`, optional, `[9.0.0,)`, both sides.
- License: Curios is LGPL-3.0-or-later. The mod compiles against its API and neither bundles nor modifies it; that use is allowed.
- Class loading: only `compat/curios/CuriosGear` (the slot lookup) and `compat/curios/CuriosRespiratorClient` (the worn look) name Curios types. `RespiratorGear.worn` reaches `CuriosGear` only after `ModList.get().isLoaded("curios")`; `RespiratorClient` registers the renderer in client setup behind the same check (a method reference, linked only when that line runs). No Curios type appears in a signature or field of any other class, so without Curios the JVM never resolves one. `RespiratorDataTest.curiosTypesStayInTheirPackage` holds this.
- Data: `data/icheme/curios/entities/respirator.json` gives the player Curios' `head` slot; `data/curios/tags/item/head.json` (not replacing) lets the respirator into it.
- A respirator in the helmet slot wins over one in a Curios slot; the Curios renderer draws nothing when the helmet slot holds a respirator.

## Look

- Placeholder: option B's look, flat on the outer head (`textures/models/armor/respirator_layer_1.png`, 64 × 32), drawn by vanilla's armour layer from the helmet slot and by `CuriosRespiratorClient` (the outer armour model's head, following the wearer's head) from a Curios slot. Item icons: option B's.
- Options for the owner: `tmp/respirator/sheets/respirator_options.png` (A half mask with twin cartridges, B full face with one front canister, C full face with twin cartridges; iso and side on a grey head form, the respirator and filter icons at 8×). Drawn by `tools/respirator-sheets/sheets.mjs`. No people.
- The picked option becomes a 3D model in a follow-up (a custom armour model through `IClientItemExtensions.getHumanoidArmorModel` and the same model in the Curios renderer).

## Formats

Item data only: no engine format, no `FORMATS.md` row (the table lists no item components), no wire protocol change (the component syncs through the ordinary inventory protocol).

## Tests

- New: `RespiratorTest` (9: constants, the four thresholds, no protection without a working filter, the four lives exact in passes, no use below 0.1, the emptying pass still protected and the next not, the no-gear identity, the protected levels, the share), `RespiratorDataTest` (6: lines, models and icons, worn texture, vanilla-only recipes, Curios files, Curios types only in `compat/curios`), `RespiratorFilterCodecTest` (2: round trip, out of range refused).
- Run 1: the picks of `affected.mjs select --since 809a88cd2` (`LanguageFilesTest` and `UnitTextTest` left out), through `record.init.gradle -PlaneTests`, `--max-workers=4`. `test`: 192 classes, 1045 tests, 2 failed, both in `RespiratorTest` and both errors in the test itself (`Math.nextDown(15.0) / 50` rounds back to 0.3; index 50 without protection is EXTREME, not HIGH). `holdGates`: 9 classes, 50 tests, 0 failed. `equipmentGates`: 6 classes, 87 tests, 0 failed. The law was not changed.
- Run 2: the edited test class alone, `RespiratorTest`: 9 tests, 0 failed.
- Not run: the GameTest and campaign steps that the selector marks due (final lane only), `LanguageFilesTest`, `UnitTextTest`.

## Not verified

- Nothing has been seen in a client: the worn look from either slot, the item bar, the tooltip, the meter segment, fitting by use.
- The Curios slot, its renderer and the mod starting without Curios are argued from the class-loading structure; no run loads Curios.
- No GameTest treats a player with a respirator (`PollutionEffectsGameTests` cover the path without gear, unchanged).

## Findings and owner items

- R1: no pollution chapter in the Field Guide; the respirator has item pages only. A chapter (the four kinds, the meter, the respirator) needs steps and advancements of its own.
- R2 (found, not changed): an entity whose box touches two cubes is treated by both in one pass (each cube's entity search finds every box that crosses it), so it takes effects and damage twice. The filter load is booked once (by the cube of its block position); the double damage is the existing behaviour.
- R3: when a player stands in a clean cube with the head in a polluted one, the polluted cube protects but books no load. Small and on the player's side.
- R4: the look (A, B or C).
