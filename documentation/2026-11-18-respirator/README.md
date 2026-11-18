# 2026-11-18-respirator

Status: in progress since 2026-11-18, on `claude/respirator-bb985a` (not merged).

Purpose: a respirator worn on the head (or a Curios head slot) that lets a player move through polluted air while its consumable filter lasts.

## The owner's request (2026-11-18)

"Add a respirator as player gear to allow players move within heavily polluted regions, with consumable filter". Choices: helmet slot, and "Curios slot if present"; "One combined filter".

## Documents

- [RESPIRATOR_REVIEW.md](RESPIRATOR_REVIEW.md): the law and its numbers, every constant, the items, recipes, Curios and its license, the tests, what is not verified, the owner items.

## History

- 2026-11-18: build (`a7863e281`, documents in the next commit). `Respirator` (protection factor 50, filter 600 index-seconds), `RespiratorGear` (`worn`, `protect`), the `treat` overload in `PollutionEffects`, the items and the `icheme:respirator_filter` component, the armour material, recipes, the meter segment, item pages, optional Curios (9.5.1 API, compile only), placeholder look of option B; option sheet by `tools/respirator-sheets/`.

## Code areas

- `science/pollution/Respirator`; `runtime/pollution/RespiratorGear`, `PollutionEffects` (`dimension`, `treat`), `PollutionMeters` (`respirator`, `withMask`); `world/item/RespiratorItem`, `RespiratorFilterItem`, `RespiratorFilterCodec`; `registry/ModItems` (`RESPIRATOR_FILTER`, `RESPIRATOR_MATERIAL`, `RESPIRATOR`, `RESPIRATOR_FILTER_ITEM`), `ModCreativeTabs`; `science/tutorial/equipment/EquipmentInfo.ITEMS_THAT_NEED`; `compat/curios/CuriosGear`, `CuriosRespiratorClient`, `client/RespiratorClient`.
- Data: recipes `respirator`, `respirator_filter`; `data/icheme/curios/entities/respirator.json`, `data/curios/tags/item/head.json`; item models and icons, `textures/models/armor/respirator_layer_1.png`; `en_us.json`.
- Build: `build.gradle` (Curios maven and `compileOnly`), `gradle.properties` (`curios_version`, `curios_version_range`), `neoforge.mods.toml`.
- Tests: `RespiratorTest`, `RespiratorDataTest`, `RespiratorFilterCodecTest`.

## Open items

- R1 to R4 of the review: a Field Guide chapter on pollution, double treatment across a cube face (existing), the head-in-another-cube load, the look (A, B or C) and its 3D model.
- In-game check of the worn look, the meter segment and the Curios slot.
