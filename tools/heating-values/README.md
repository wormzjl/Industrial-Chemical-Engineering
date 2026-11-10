# heating-values

Purpose: prebuild each material record's lower heating value into its `combustion` sub-record (owner decision F2: "This LHV value goes into the material DB"). A fired heater's burners read it (`MaterialCatalog.heatingValues`, `Burners.perKilogram`).

Batch: [2026-11-09-furnace-fuel-power](../../documentation/2026-11-09-furnace-fuel-power/README.md). Status: In use since 2026-11-09. Index: [../INDEX.md](../INDEX.md).

## Method

Hess's law on the record's own `thermochemistry` sub-record, the ideal gas at 298.15 K burnt to CO2, H2O (gas), SO2 and N2: LHV = Hf − C·Hf(CO2) − H/2·Hf(H2O) − S·Hf(SO2), J/mol, with the product heats from the bundled CO2, water and SO2 records. A negative result (CO2, SO2, water) is written as 0. `estimated` follows the thermochemistry's; `revision` is `hess-lhv-r1`; `source` names the revisions it read.

## How to run

From the repository root (Node 22):

```
node tools/heating-values/heating-values.mjs src/main/resources/data/icheme/materials tmp/lhv/combustion
node tools/compound-import/insert-subrecord.mjs combustion tmp/lhv/combustion src/main/resources/data/icheme/materials [--replace]
```

The first writes one wrapper per record (`{id, path, component, combustion}`); the second inserts the sub-records before `viscosity` (`--replace` after a thermochemistry edit; then bump `revision`).

## Entry points

- `heating-values.mjs <materials dir> <out dir>`: reads `properties/*.json` and `water/*.json`.

## Gate

`MaterialThermochemistryTest.everyRecordCarriesItsLowerHeatingValue`: the stored values equal Hess's law on the shipped thermochemistry.

## History

- 2026-11-09: written; 45 records and the water gained `combustion` (methane 802 618 J/mol, 50.0 MJ/kg).
