# Batch still: the law, the column and the solid fuels (W1)

Plan: [BATCH_DISTILLER_PLAN.md](BATCH_DISTILLER_PLAN.md) sections 2, 4 (W1, W3, W4), 5 and 7. Minecraft-free; the engine
(W3) and the simulator unit (W7) call it.

## Classes

| Class | Role |
|---|---|
| `science/fluid/network/BatchStill` | Heater, sizes, condenser, statuses; `step(column, design, pot, inputs, dt)`, the pot's energy balance over a step |
| `science/fluid/network/StillColumn` | The column: bubble-point method (Wang and Henke), energy-balance flows, pot mode, on the network's `HydrocarbonModel` |
| `science/material/SolidFuelCatalog` | Records of kind `fuels` (`data/icheme/materials/fuels/`), loaded with the material catalog (`MaterialCatalog.fuels()`) |
| `IChemE.joulesPerBurnTick()` | Config `energy.joulesPerBurnTick`, default 1.45e6 J: an item without a fuel record |

## Equations and defaults

- **Sizes.** Pot a cylinder as tall as wide: D = (4V/π)^(1/3). Heated surface A = 1.73 D². Rating (heat into the pot at
  full firing) = 25 kW/m² × A. Firing 0 to 1 of the rating. Fuel or power drawn = heat / η: SOLID 0.45, LIQUID 0.55,
  ELECTRIC 0.90; FE/t = drawn / (20 × J/FE).
- **Condenser.** U = 500 W/m²K; A = 1.2 × rating / (U × 40 K). Capacity ε C_w (T_c − T_w), ε = 1 − exp(−UA/C_w), T_c the
  mean of the head and condensate temperatures. Short of the need, it condenses the share φ of the top vapour; the valve
  divides the condensate, so the column runs at R_eff = φR / (R + 1 − φR) (closed valve: φ / (1 − φ)); the liquid
  product is φ(R_eff + 1)/(R + 1) of the net top product, the rest vents as vapour. Design water: 1.2 × rating over 10 K.
- **Column.** Sized at F = 1.5 Pa^0.5 at the full boil-up of a naphtha (330 kJ/kg, vapour 3.3 kg/m³). Floods above
  F = 1.8 (design + 20 %, the larger of the bottom and top vapour loads): the firing is held at the flood boil-up.
- **Pot below its bubble point:** N (h(T1) − h(T0)) = Q dt (Q / (m cp)).
- **Pot at its bubble point:** Q dt = N1 h(x1, Tb(x1)) − N0 h0 + δ e, solved for the moles δ distilled by regula falsi,
  with e = (R + 1) H1 − R h0 the energy leaving per mole of distillate (no column: the vapour's H). The distillate's
  composition and e are the step start's; the pot ends on its bubble point, so its warming (N cp dT_b/dt) is in the
  balance. Warming reported = N1 (h(x1, T1) − h(x1, T0)); boil-up = heat − warming. No heat loss (B6 A).
- **Ledger.** heat = Δ(pot enthalpy) + liquid H + vented H + condenser duty, exactly; each component conserved exactly.
- **Column solve** (port of `BubblePointColumn`, energy form only): per mole of distillate (flows proportional to D in pot
  mode); CMO start until no stage moves 1 K, then V(j+1) = ((R+1)(H1 − h0) + h0 − hj)/(H(j+1) − hj), Lj = V(j+1) − 1;
  converged at 1e-4 K. Thomas per component, bubble points with Wilson starts, K from the model's ln φ (liquid at P,
  vapour at P). Stages 0 or no reflux: one equilibrium stage. Closed valve: profile at R = 1e4, D = 0. A refused solve
  keeps the last answer at the same setting, else one stage (`Step.columnFailure`).
- **Statuses** (most severe first): NO FUEL (firing asked, nothing delivered), OVERHEAT (pot ≥ 623.15 K: firing cut),
  BOIL DRY (liquid ≤ 5 % of the pot volume), FLOODING, COOLING LIMITED, DISTILLING, BOILING (closed valve), HEATING,
  IDLE. `Step.alarms` carries every alarm raised.
- **Solid fuel** (B1 B): energy = bulk density / items per m³ × LHV. Records: coal 833 kg/m³, 9/m³, 25 MJ/kg (2.31 GJ);
  block of coal 1/m³ (20.8 GJ); coke, tag `c:coal_coke`, 450, 29 (1.45 GJ); charcoal 220, 29 (0.71 GJ); logs, tag
  `minecraft:logs_that_burn`, 550, 1/m³, 15 (8.25 GJ). Item record first, then its tags in id order; else burn ticks ×
  `joulesPerBurnTick`. One record per item or tag; each declares its `revision` (`r1`).

## What the tests hold (`BatchStillTest`, checked by a plain-Java probe before hand-back)

| Test | Holds | Measured |
|---|---|---|
| sizes | 100/500/2000 L: D, rating, condenser A, column D of plan section 2 | 500 L: 0.860 m, 32.0 kW, 1.92 m², 213 mm |
| heaters | 71.1 kW solid, 58.2 kW liquid, 711 FE/t electric (500 L) | (the plan's 674 FE/t was at 95 %) |
| condenser | design water takes ≥ 1.2 × rating at 75 °C vapour | |
| heat-up | 245 kg naphtha at 32 kW: 0.06 ± 0.01 K/s | 0.068 K/s |
| Rayleigh | iC5/nC5 50/50 to half: pot iC5 within 0.002 of the Rayleigh integral | 0.44541 vs 0.44545 |
| V3 | naphtha, N 2/4/8 × R 0.5/2/8 at 0 % (snapshots.csv), the B3 proxy's reboiler | max 0.00085 (limit 0.004); refinery = tjl20 |
| warming | crude 2.1 ± 0.5 × boil-up; naphtha 0.07 to 0.25; iC5/nC5 < 0.03 | 2.16, 0.12, 0.002 (B3: 2.16, 0.12, 0.00) |
| statuses | each status; flood held at F 1.8 (30 kPa); ledgers | flood: firing held at 73 % |
| run | 4 trays naphtha, R 0.5 and 4, first 10 %: conserves, sharper at R 4 | pentanes 0.90 → 0.995 |

Column cost (refinery view, cold / warm): naphtha 0.5 to 3.8 / 0.3 to 3.1 ms; stabilised light crude 0.6 to 4.5 / 0.4 to
3.9 ms; all 18 cases converge. A whole 500 L crude batch to 50 % (4 trays, R 2, 30 s steps): 1.8 h simulated, 0.1 s.

## Found on the way

- A light naphtha cannot be condensed on ambient water. Its pentane-rich head is near 27 °C (300 K): against 15 °C water
  the 500 L condenser takes 48 % of the head vapour at full firing; at 25 °C almost none. Plan section 2 foresaw the
  light-cut limit; the tests use 5 °C water or 40 % firing where they need full condensation.
- The pot's warming share on the network's thermo equals B3's on V3's (2.16 crude, 0.12 naphtha at the start).
