# Bundled fluid conversion rules (W6)

The mod ships 26 rule files under `src/main/resources/data/icheme/icheme/fluid_conversion/<mod>/<fluid>.json` (rule id `icheme:<mod>/<fluid>`, revision `1`). The listener (`ConversionReloadListener`, folder `icheme/fluid_conversion`, read recursively) skips a rule whose fluid's mod is absent; that is not a refusal. `ConversionRuleTest.everyBundledRuleLoadsOnTheRealCatalog` loads all 26 on the bundled catalog (network package `icheme:refinery`) with no refusal. The generator is [`tools/conversion-rules/rules.js`](../../tools/conversion-rules/README.md); the JSON files are the source of record.

## The windows

Liquid product windows are on a mass basis over the package's light ends, aromatics and crude pseudo-cuts (`crude_pc01` to `crude_pc12`, normal boiling points 58, 110, 159, 207, 256, 305, 354, 414, 493, 595, 692, 822 °C). A compound named with `0 to 1` is allowed freely; everything the window does not name totals at most `others_max`. Products follow the usual refinery cut points:

| Product | Cuts it takes (boiling range) | Bounds that keep the windows apart | others | Priority |
|---|---|---|---|---|
| Gasoline | C5, hexane, cyclohexane, BTX, ethylbenzene, pc01, pc02 (30 to 180 °C) | pc03 ≤ 0.15, isobutane ≤ 0.05, n-butane ≤ 0.08 | 0.02 | 41 |
| Naphtha | as gasoline, heavier end | pc03 ≤ 0.4, pc04 ≤ 0.05, isobutane ≤ 0.03, n-butane ≤ 0.05 | 0.02 | 40 |
| Kerosene | pc03, pc04, pc05 (150 to 275 °C) | pc02 ≤ 0.08, pc06 ≤ 0.08 | 0.01 | 30 |
| Diesel | pc05, pc06, pc04 ≤ 0.4, pc07 ≤ 0.5 (180 to 360 °C) | pc06 ≥ 0.15 (disjoint from kerosene), pc03 ≤ 0.05, pc08 ≤ 0.05 | 0.01 | 20 |
| Lubricant | pc08 ≥ 0.4, pc07 ≤ 0.25, pc09 ≤ 0.5, pc10 ≤ 0.05 (vacuum gas oil, 350 to 550 °C) | | 0.01 | 15 |
| Heavy oil | pc07 to pc12 (residue) | pc05 ≤ 0.05, pc06 ≤ 0.15 | 0.01 | 10 |
| LPG | propane, propylene, butanes, butenes, butadiene | ethane ≤ 0.02 | 0.03 | 45 |

Liquids: pressure 0.8 to 5 bar (the liquid converter is an atmospheric tank). Temperatures: gasoline and naphtha −40 to 50 °C, kerosene −40 to 80 °C, diesel −20 to 80 °C, lubricant −10 to 100 °C, heavy oil 0 to 150 °C. Gases (closed converter only): 200 to 400 K, 0.5 to 10 bar; pure gases on a mole basis, at least 0.98, others 0.02. Where two windows overlap (gasoline and naphtha), the higher priority is tried first; the drawn phase keeps its last rule inside the 2 % hysteresis.

## The table

| Forge fluid | Mod | Direction | Composition window | T | P | Source of the id |
|---|---|---|---|---|---|---|
| `minecraft:water` | Minecraft | both | forward pure Water; reverse Water ≥ 0.99 mole, others 0.01 | forward ambient; reverse 5 to 60 °C | forward ambient; reverse 0.9 to 1.5 bar | vanilla |
| `#c:crude_oil` | tag | forward | preset `medium_crude` | ambient | ambient | TFMG and PneumaticCraft both tag their crude `c:crude_oil` (generated tag files, below) |
| `tfmg:crude_oil` | Create: The Factory Must Grow | forward | `medium_crude` (priority 1, before the tag) | ambient | ambient | TFMG `TFMGFluids.java`, `data/c/tags/fluid/crude_oil.json`, branch `1.21.1` |
| `pneumaticcraft:oil` | PneumaticCraft: Repressurized | forward | `medium_crude` | ambient | ambient | PNC `ModFluids.java`, `data/c/tags/fluid/crude_oil.json`, branch `1.21` |
| `ad_astra:oil` | Ad Astra | forward | `medium_crude` | ambient | ambient | Ad Astra `ModFluids.java`, `MOD_ID = "ad_astra"`, branch `1.21.1` (not in `c:crude_oil`) |
| `tfmg:gasoline` | TFMG | reverse | gasoline | −40 to 50 °C | 0.8 to 5 bar | `TFMGFluids.java` (`fuel("gasoline")`), `c:gasoline` |
| `tfmg:naphtha` | TFMG | reverse | naphtha | −40 to 50 °C | 0.8 to 5 bar | `TFMGFluids.java`, `c:naphtha` |
| `tfmg:kerosene` | TFMG | reverse | kerosene | −40 to 80 °C | 0.8 to 5 bar | `TFMGFluids.java`, `c:kerosene` |
| `tfmg:diesel` | TFMG | reverse | diesel | −20 to 80 °C | 0.8 to 5 bar | `TFMGFluids.java`, `c:diesel` |
| `tfmg:lubrication_oil` | TFMG | reverse | lubricant | −10 to 100 °C | 0.8 to 5 bar | `TFMGFluids.java`, `c:lubrication_oil` |
| `tfmg:heavy_oil` | TFMG | reverse | heavy oil | 0 to 150 °C | 0.8 to 5 bar | `TFMGFluids.java`, `c:heavy_oil` |
| `tfmg:lpg` | TFMG | reverse, gas | LPG | 200 to 400 K | 0.5 to 10 bar | `TFMGFluids.java` (`gasFuel`, `GasFluidType` density −1), `c:lpg` |
| `tfmg:propane` | TFMG | reverse, gas | Propane ≥ 0.98 mole (priority 50) | 200 to 400 K | 0.5 to 10 bar | `TFMGFluids.java` (`gasFuel`) |
| `tfmg:butane` | TFMG | reverse, gas | isobutane and n-butane, others 0.02 (priority 50) | 280 to 400 K | 0.5 to 10 bar | `TFMGFluids.java` (`gasFuel`) |
| `tfmg:hydrogen` | TFMG | reverse, gas | Hydrogen ≥ 0.98 mole | 200 to 400 K | 0.5 to 10 bar | `TFMGFluids.java` (`gasFuel`) |
| `tfmg:ethylene` | TFMG | reverse, gas | Ethylene ≥ 0.98 mole | 200 to 400 K | 0.5 to 10 bar | `TFMGFluids.java` (`gas`) |
| `tfmg:propylene` | TFMG | reverse, gas | Propylene ≥ 0.98 mole | 200 to 400 K | 0.5 to 10 bar | `TFMGFluids.java` (`gas`) |
| `tfmg:carbon_dioxide` | TFMG | reverse, gas | CarbonDioxide ≥ 0.98 mole | 200 to 400 K | 0.5 to 10 bar | `TFMGFluids.java` (`gas`) |
| `tfmg:air` | TFMG | reverse, gas | Nitrogen 0.76 to 0.80, Oxygen 0.19 to 0.23 mole, others 0.02 | 200 to 400 K | 0.5 to 10 bar | `TFMGFluids.java` (`gas`) |
| `pneumaticcraft:gasoline` | PNC | reverse | gasoline | −40 to 50 °C | 0.8 to 5 bar | PNC `ModFluids.java`, `c:gasoline` |
| `pneumaticcraft:kerosene` | PNC | reverse | kerosene | −40 to 80 °C | 0.8 to 5 bar | `ModFluids.java`, `c:kerosene` |
| `pneumaticcraft:diesel` | PNC | reverse | diesel | −20 to 80 °C | 0.8 to 5 bar | `ModFluids.java`, `c:diesel` |
| `pneumaticcraft:lubricant` | PNC | reverse | lubricant | −10 to 100 °C | 0.8 to 5 bar | `ModFluids.java`, `c:lubricant` |
| `pneumaticcraft:lpg` | PNC | reverse, liquid | LPG, refrigerated (PNC's LPG is a liquid Forge fluid; at about 1 atm LPG is liquid only below its bubble point, propane −42 °C, n-butane −0.5 °C) | −90 to 2 °C | 0.8 to 2 bar | `ModFluids.java` (`FluidLPG`, standard liquid density), `c:lpg` |
| `ad_astra:fuel` | Ad Astra | reverse | kerosene (rocket fuel as an RP-1 cut), priority 29, below the kerosene fluids | −40 to 80 °C | 0.8 to 5 bar | Ad Astra `ModFluids.java` |
| `mekanism:steam` | Mekanism | reverse, gas | Water ≥ 0.99 mole | 95 to 350 °C | 0.8 to 5 bar | Mekanism `MekanismFluids.java` (`steam`, density 0), branch `1.21.x` |

Rules by mod: Minecraft and the tag 2 files (water both ways, the crude tag), TFMG 15, PneumaticCraft 6, Ad Astra 2, Mekanism 1. Forward rules: 5 (water, the tag, three explicit crude ids). Gas rules: 9 (8 TFMG, 1 Mekanism).

All ids were read on 2026-11-12 from each mod's own public source repository on its 1.21.1 branch (`DrMango14/Create-The_Factory_Must_Grow` `1.21.1`, `TeamPneumatic/pnc-repressurized` `1.21`, `terrarium-earth/Ad-Astra` `1.21.1`, `mekanism/Mekanism` `1.21.x`, `BluSunrize/ImmersiveEngineering` `1.21.1`): the fluid registration class and, where present, the generated `data/c/tags/fluid/` files.

## Not bundled

| Fluid | Why |
|---|---|
| Immersive Petroleum (`immersivepetroleum:crudeoil`, `diesel`, `gasoline`, `kerosene`, `lubricant`, `naphtha`, `petroleum_gas`) | Unconfirmed: the 1.21.1 builds (4.4.x, TwistedGate) have no public source; the public repository `Flaxbeard/ImmersivePetroleum` stops at 1.16.3. The ids above come only from a third-party ATM10 KubeJS script. Open for the owner. |
| Thermal (`thermal`) | Unconfirmed: no 1.21.1 branch of the CoFH repositories was found. |
| Immersive Engineering (`creosote`, `plantoil`, `ethanol`, `biodiesel`, `acetaldehyde`, `phenolic_resin`, ...) | Confirmed ids, none expressible: the network package has no ethanol, acetaldehyde, glycerides or tar compounds. |
| Mekanism liquids (`hydrogen`, `oxygen`, `chlorine`, `sulfur_dioxide`, `ethene`, `sodium`, `brine`, `heavy_water`, ...) | Confirmed ids; cryogenic liquefied chemicals (liquid hydrogen 20 K, oxygen 90 K, ethene 169 K) or compounds the package lacks. Liquid SO2 (below −10 °C) is expressible and left out to keep the list short. |
| Ad Astra `hydrogen`, `oxygen`, `cryo_fuel` | Confirmed ids; cryogenic liquids. |
| TFMG `creosote`, `neon`, `furnace_gas`, `hot_air`, molten metals, acids, concrete | No package compound (neon, coal tar) or not a defined composition (furnace gas). |

## Notes for the owner

- Two mods with the same product (TFMG and PneumaticCraft kerosene) both load. The drawn phase picks the first rule by priority, then by id (`icheme:pneumaticcraft/...` before `icheme:tfmg/...`). A converter whose Forge tank already holds the other mod's fluid reads `DESTINATION_FULL`. A pack picks its own by overriding the rule file (same id) or by priority. Proposal, not built: prefer the rule naming the fluid the Forge tank already holds.
- The windows are game-level cut ranges, not product specifications (no flash point, RVP or cetane). They are open to the owner's veto.
