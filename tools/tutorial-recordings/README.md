# Tutorial recordings

Batch `2026-11-12-tutorial-system` (P2a). Offline tools that record, from the mod's own data, what a tutorial card
draws, so a card reads one small asset once and computes nothing while it draws.

## CrudeBarrel

Writes `src/main/resources/assets/icheme/tutorial/recordings/crude_barrel.json` for the cards `barrel` and `products`:
per grade (medium, light, heavy, oil sand), the components in boiling order (mass share, colour from the component
record's `appearance.color`, the range it boils over), the six bands' shares (gas below 20 °C, naphtha 20 to 180,
kerosene 180 to 240, diesel 240 to 360, gas oil 360 to 565, residue above), the share boiled off below every 5 K from
250 K to 900 K, and the sulfur (`provenance.specification.sulfur_mass_percent`). The computation is
`BarrelData.compute` in `science/tutorial/cards/`; `CrudeBarrelTest` recomputes it and holds the asset equal.

Rules: amounts by mass; a pure compound boils at its normal boiling point (its property record); a petroleum fraction
boils evenly between `cut.lower_kelvin` and `cut.upper_kelvin`, and one with no upper bound boils above its lower one.

Run after a change to an assay, a component's cut or colour, or a light end's boiling point:

```
./gradlew --offline --init-script tools/tutorial-recordings/record.init.gradle recordCrudeBarrel
```

It prints the band shares and writes the asset; commit the asset.
