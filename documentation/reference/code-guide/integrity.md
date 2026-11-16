# Integrity: corrosion, erosion and fouling laws

Up: [code guide](README.md)

## Read this when

- A wall's corrosion rate, its governing mechanism or its life looks wrong for its material and fluid.
- A hydrogen or oxygen service limit, or a material factor, needs a number.
- A valve trim, a heat-side wall or a pump erodes too fast or not at all; the `SOLIDS` wear fault.
- A heat side's fouling deposit, its asymptote or its level steps.
- A crude cut's sulfur, acid number or fouling number (`corrosion` sub-record).

## What it does

`science/integrity/` holds the pure laws of batch `2026-11-16-fouling-corrosion` (plan
`documentation/2026-11-16-fouling-corrosion/FOULING_CORROSION_PLAN.md`): no Minecraft types, no state. A
`CorrosionStandard` built per compound list turns a node's state into a `CorrosionDigest` (temperature, wet and oil
flags, the oil's sulfur, acid and fouling numbers, partial pressures of H2S, CO2, SO2, NH3, O2, H2). `CorrosionLaw`
gives a wall's inside rate on it for one of four materials (`Metallurgy`) and the outside rate from the corrosive air
index; `ErosionLaw` gives particle erosion; `FoulingLaw` the Kern–Seaton deposit; `WallState` the stage and life. Since
WP1 nothing in the runtime reads them yet (WP2 to WP6 book them).

## Class map

Paths are relative to `src/main/java/com/wormzjl/icheme/`.

| Class | Role |
|---|---|
| `science/integrity/Metallurgy` | The four materials (saved by name, append-only, `byName` defaults to carbon steel): allowance, overheating limit, cost index, factors of table 6.3, the hydrogen and oxygen limit lines |
| `science/integrity/Mechanism` | M1 to M11 and erosion; the status names the governing one |
| `science/integrity/CorrosionDigest` | The digest record (plan 6.1) |
| `science/integrity/CorrosionStandard` | Per list: molar masses, sulfur (formula, else the sub-record), acid and fouling numbers, the gases found by exact formula; `digest(...)` |
| `science/integrity/CorrosionLaw` | M1 to M7 on carbon steel (`rate`), the material's M8, M10, M11, the inside rate (`inside`), M9 (`outside`) |
| `science/integrity/ErosionLaw` | Wall erosion, trim speed and trim wear, the pump's solids intensity |
| `science/integrity/FoulingLaw` | `Rf∞` from the digest, the exact exponential step, levels of 1e-4, thickness |
| `science/integrity/WallState` | `SOUND`, `THIN`, `WEEPING`, `THROUGH` at u = 0.5, 0.9, 1; the life in process seconds |
| `science/machine/Wear.Fault.SOLIDS` | The pump's erosion-class fault; its intensity is `ErosionLaw.solidsIntensity` |
| `science/material/MaterialCatalog.Corrosion` | The `corrosion` sub-record; `corrosion(...)`, `sulfurShares`, `acidNumbers`, `foulingNumbers` |

## Laws and constants

- Rates in mm/y; one corrosion year is `corrosion.yearProcessHours` (20 process hours). Every rate is finite, 0 for NaN
  or negative inputs, at most `CorrosionLaw.MAX_RATE` (1e4 mm/y).
- M1 sulfidation `1.3 exp(−8698 (1/T − 1/643)) (S_e/0.6)^0.4` from 503 K; M2 naphthenic acid; M3 to M7 the wet
  mechanisms, each a partial-pressure term times a temperature term. Material factors multiply M1 to M7 and M9;
  `p_H2` ≥ 7 bar lifts chrome steel's sulfidation factor from 0.12 to 0.6.
- M8 `0.25 · 2^((T − limit)/25)`; M10 `0.5 · 2^((T − T_H)/25)` above `max(T_floor, T_7 − s log10(p/7))`; M11
  `50 p_O2 / p_limit` above `max(0.3, p_150 2^(−(T − 150 °C)/100))`.
- Erosion `(φ/0.01) (v/10)^2.6` mm/y; a trim `(φ/0.01) (v/30)^2.6` health per corrosion year, `v = sqrt(2Δp/ρ)`; a pump
  `clamp((φ − 0.001)/0.05, 0, 1)`.
- Fouling cap 0.002 m²K/W, step 1e-4, deposit conductivity 1 W/mK; oil `max(f, min(1.8 f, 0.0009))` from 260 °C.

## Tests and gates

`src/test/java/com/wormzjl/icheme/science/integrity/` (`MetallurgyTest`, `CorrosionLawTest`, `ErosionLawTest`,
`FoulingAndWallTest`, `CorrosionStandardTest`) hold every anchor of plan sections 3.1, 5, 6.2 and 8 (to 1 %, or the
formula's own value where the plan rounds further), the factor table, monotony and the NaN guards.
`MaterialCorrosionTest` holds the twelve records, the assays, refusals and the no-identity check; `WearTest` the
`SOLIDS` row; `LayeringTest` keeps `science/integrity` below the machine, network, column, plant, sheet and equipment
packages.

## Traps

- Build a `CorrosionStandard` per compound list: an island's view has other indices than the world's.
- The water is the last conserved entry and has no dataset record; its arrays read 0.
- A compound with sulfur in its formula (H2S, mercaptans, COS) takes the formula's sulfur, never a sub-record.
- The digest's `wet` and `oil` are flags: a side with both reads the oil's fouling row.
- `Metallurgy` and `WallState.Stage` are saved by name later: append, never rename.
- The crude cuts' sulfur cannot meet all four assays within 20 % (the heavy crude's cuts dominate the dilbit's); the
  fit's residuals are in `tools/fouling-corrosion/README.md`.

## Snapshot

2026-11-16, WP1: the laws, the four materials, the `corrosion` sub-record on `crude_pc01` to `crude_pc12`
(`corrosion-fit-r1`) and `Wear.Fault.SOLIDS`, read by nothing yet.
