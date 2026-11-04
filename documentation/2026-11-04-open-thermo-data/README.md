# 2026-11-04-open-thermo-data

Purpose: thermo data under open terms. Generated light, medium, heavy and oil-sand crude grades replace the ExxonMobil and Tia Juana assays. The crude viscosity gets a new source. The NIST-derived values are re-derived from CoolProp, NASA CEA and IAPWS. Unused data is removed. This follows the decisions of [DATA_LICENSE_REVIEW.md](../2026-11-04-refinery-compounds/DATA_LICENSE_REVIEW.md).

Status: In progress since 2026-11-04, on `claude/refinery-compounds-thermos-252973` after the refinery-compounds batch. Index: [../INDEX.md](../INDEX.md).

## Documents

- [OPEN_DATA_PLAN.md](OPEN_DATA_PLAN.md): expected behaviour, the data today, work packages O1 to O6 and decisions E1 to E6.

## Code areas

- `src/main/resources/data/icheme/materials/`: assays, presets, networks, properties, water, transport and spine records; `assets/icheme/flowsheet_examples/crude_unit.json`.
- The neural registry and sidecar (read only until decision E6).

## History

- 2026-11-04: plan written; the owner gave E1 to E3.

## Open items

- R1: open sources for crude viscosity and a generation method for the grades.
- R2: an inventory of unused data.
- E6: the neural model, after O3.
