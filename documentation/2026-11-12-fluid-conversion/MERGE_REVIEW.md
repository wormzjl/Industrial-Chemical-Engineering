# Fluid conversion: merge review

Branch `claude/fluid-conversion-build-656f98`, cut from `main` at `ccde84295` (0.27.1); `main` has not moved. Merge preparation by the owner's word of 2026-11-12 ("Yes, prepare merge", "Yes, go"). The version is the owner's choice; proposed: **0.28.0** (a new feature, minor).

## What the batch changed

- Two equipment kinds, `FLUID_CONVERTER` and `GAS_CONVERTER` (`ConverterDesign`), that convert between other mods' Forge fluids (1 mB = 1 L) and IChemE streams. Each has a Forge port and two hidden ports on its process vessel: a drain void and a fill generator, each behind its own valve (`ValveSource.CONVERTER`).
- `Converters`, a port host joined with `Ports.both`. It plans at dispatch and books on accept, and its settings are queued engine events. Its facts are `ConverterUnit`, in fluid facts schema 24.
- Rules by datapack: `science/fluid/conversion/` and `ConversionReloadListener`, with 26 bundled rules (BUNDLED_RULES.md) and a KubeJS example (KUBEJS_EXAMPLE.md).
- Control: four channels plus six alarm channels, the six alarms appended to `EquipmentAlarm`, `ConverterCommands`, the DATA socket on the cabinet, and the DCS converter page.
- The converter's page on the fluid device screen (`ConverterDoor`).
- Gas converter: the pressure safety valve and the blanketing valve. Liquid converter: the level gauge.
- Formats: fluid facts schema 24, protocols `fluid-24` and `control-5`. The state format is unchanged (47).

## Owner decisions applied

| # | Decision | Where |
|---|---|---|
| D1 | The IChemE tank is open | the liquid converter's vessel is open, under a vent |
| D2 | Few forward rules; no power charge, no loader tolerance check | forward rules only for water and crude (5 of 26) |
| D3 | Liquids, plus gas rules for gaseous Forge fluids | 9 gas rules |
| D4 | Gas rules only on the closed variant | `ConversionRules.forward`/`reverse` (`closed`) |
| D5 | Rate in process time, L/s | `Settings.rate`, the page's "Set rate" |
| D6 | Balance by fill fraction, with a deadband | `Converters.fill`, deadband 0.01 to 0.5 |
| D7 | A constructor kind with sizes; no FE; the Forge side is passive | `ConverterPort` |
| D8 | Datapack JSON only; KubeJS writes JSON | KUBEJS_EXAMPLE.md |
| D9 | A PSV on the gas variant | `ReliefValveGeometry.of`, `ConverterDesign.reliefFoot` |
| D10 | The model in the planning session; 1 to 20 m, 2 to 24 m, one tank in 1000 s | `ConverterDesign` |
| D11 | The liquid gauge as an optional accessory | `GaugePlace`, `TankGaugeGeometry.draw` |
| Label | "Set rate", not "max rate" (owner, 2026-11-12) | `converter_field.rate`, en and all four languages |

## Open to the owner's veto

1. The bundled windows and ids (BUNDLED_RULES.md). Immersive Petroleum and Thermal are unconfirmed and not bundled.
2. When two mods have the same product, the first rule by priority then id wins, and a tank holding the other mod's fluid reads DESTINATION_FULL. Proposal, not built: prefer the rule naming the tank's fluid.
3. W3: the hidden ports' pressures (drain 80 kPa; fill 500 kPa liquid, 1 MPa gas), the port bore (2 m/s at the rated flow), the blanketing valve's place (none under 2.5 m), the gauge's chamber, the 1 % drain heel.
4. W4:
   - the gas converter's fill is by moles at the blanket band's top;
   - the level channels read m over the tank height, with % as the second value;
   - `rate` is a FLOW channel in kg/s;
   - `direction` is an ALARM-kind channel.
5. W5: the two tanks are in one Tanks group; the rule rows are text lines; the page's column scrolls at 1920 by 1080, scale 3.
6. The model's look: no ladders.

Not checked in game: the page, the gauge and valve drawings on the converter, the KubeJS script. An in-game check is the owner's to start.

## Tool folders

- [`tools/converter-gametest/`](../../tools/converter-gametest/README.md): the converter's GameTests alone on the review server.
- [`tools/conversion-rules/`](../../tools/conversion-rules/README.md): the bundled rules' generator, moved from `tmp/` in the cleanup.
- `tools/heat-gui-renders/GuiPrototypes.fluidConverter`: the page's software renders, an existing folder.

The cleanup detached `Converters.Totals` and its accessor, the four-value `settings` overload and the unused `rules(Supplier)` setter (`44cad3da1`). Only tests read them. `main-reach.pl` reports 3 unreachable classes, all from before this batch (the dh and iris compat bridges).

## Code guide: updated

`fluid-runtime`, `fluid-science`, `equipment`, `instrument-accessories`, `process-control`, `wired-control-power`, `client-gui`, `thermo-materials`, `verification`, and `README` (headline, code map, versions, snapshot `44cad3da1`).

## What ran in the merge preparation (`--max-workers 4`)

| Run | Result | What it proves |
|---|---|---|
| `test` ConvertersTest | 6 pass | conservation, reservations, balance and queued settings without `Totals` |
| `tools/converter-gametest/run.sh` | 6 required pass | both directions in a world; the vessel's numbers match W3's to the last digit |
| `test` LanguageFilesTest | 2 pass | the four languages carry every key and argument |
| `equipmentGates` UnitTextTest | failed, then 1 pass | fr and es held lines over 180 px, shortened |
| Offline `check.js`, `status-words.js` | OK, 3,583 keys each | |

The final lane: [FINAL_LANE_REVIEW.md](FINAL_LANE_REVIEW.md).
