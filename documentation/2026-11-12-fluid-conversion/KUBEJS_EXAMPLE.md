# Fluid conversion rules from KubeJS (plan D8)

Rules are datapack JSON only (owner D8). The mod has no KubeJS type. A KubeJS script writes the same JSON into KubeJS's virtual data pack. The listener reads it as it reads any pack: `data/<namespace>/icheme/fluid_conversion/<path>.json`, with rule id `<namespace>:<path>`. The fields are those of [BUNDLED_RULES.md](BUNDLED_RULES.md) and plan 3.3.

## The script (KubeJS 2101.7.2, the pinned `kubejs_version` 2101.7.2-build.368)

Save it as `kubejs/server_scripts/icheme_conversion.js`, then run `/reload`.

```js
// A reverse rule: a light naphtha from IChemE to a modpack's own fluid.
ServerEvents.generateData('after_mods', event => {
  event.json('mypack:icheme/fluid_conversion/light_naphtha', {
    revision: '1',
    priority: 45,
    fluid: 'mypack:light_naphtha',
    phase: 'liquid',
    to_forge: {
      basis: 'mass',
      components: {
        'Isopentane': { min: 0, max: 1 },
        'N-pentane': { min: 0, max: 1 },
        'N-hexane': { min: 0, max: 1 },
        'crude_pc01': { min: 0.3, max: 1 }
      },
      others_max: 0.02,
      temperature: { min: 253.15, max: 313.15 },
      pressure: { min: 80000, max: 300000 }
    }
  })

  // A bundled rule replaced: the same id (icheme:tfmg/kerosene) in a pack above the mod's wins.
  event.json('icheme:icheme/fluid_conversion/tfmg/kerosene', {
    revision: 'pack-1',
    priority: 35,
    fluid: 'tfmg:kerosene',
    phase: 'liquid',
    to_forge: {
      basis: 'mass',
      components: { 'crude_pc03': { min: 0, max: 1 }, 'crude_pc04': { min: 0, max: 1 }, 'crude_pc05': { min: 0, max: 1 } },
      others_max: 0.02
    }
  })
})
```

## How the event was confirmed

Checked on 2026-11-12 in the KubeJS source, `KubeJS-Mods/KubeJS` branch `2101` (commit `2461c98`, `gradle.properties` `mod_version=2101.7.2`, Minecraft 1.21.1):

- `plugin/builtin/event/ServerEvents.java`: `GENERATE_DATA = GROUP.server("generateData", () -> KubeDataGenerator.class).requiredTarget(GeneratedDataStage.TARGET)`. The event is `ServerEvents.generateData` and needs a stage.
- `script/data/GeneratedDataStage.java`: the stages are `internal`, `registries`, `before_mods`, `after_mods` and `last`. `after_mods` puts the pack above the mods' data, so a script's file with a bundled rule's id replaces it.
- `generator/KubeResourceGenerator.java`: `json(ResourceLocation id, JsonElement json)`. `GeneratedData.json` adds `.json` to the path when it is missing.

Not verified: the script has not been run in game (no dev client in this task). The conversion of the script's object literal to a `JsonElement` relies on KubeJS's type wrapper; it was not read in the source. If it fails, use the datapack route below.

## The plain datapack route

Put the same JSON in a datapack as `data/<namespace>/icheme/fluid_conversion/<path>.json` and run `/reload`. A file with the path of a bundled rule (`data/icheme/icheme/fluid_conversion/tfmg/kerosene.json`) in a pack above the mod replaces that rule. The server log names each refused rule with its reason (`ConversionRule.Reason`); a rule whose fluid's mod is absent is skipped without a message.
