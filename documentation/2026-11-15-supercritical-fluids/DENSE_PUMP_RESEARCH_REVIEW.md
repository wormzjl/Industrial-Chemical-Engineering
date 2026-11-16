# How a centrifugal pump behaves on a supercritical fluid

Batch 2026-11-15-supercritical-fluids. Research review, main thread, 2026-11-16, at the owner's request ("currently
implemented centrifugal pump is not designed to handle supercritical fluids, research how it actually behaves").
No code changed. Numbers of section 2: `tmp/dense-pump-research/pump_numbers.py` (CoolProp 8.0.0, isentropic steps).

## Summary

**Expected (what real pumps do).** A centrifugal pump gives a fluid work per unit mass, its head, and the head does
not depend on the fluid. On one dense fluid (liquid or supercritical, no second phase) the pump keeps its head. Its
pressure rise is the head times the fluid's average density through the pump, so the rise falls as the fluid gets
lighter. There is no gas lock: nothing separates. Pumps are run on carbon dioxide down to a minimum suction density of
400 to 500 kg/m³ (reduced density 0.86 to 1.07), one test pump down to 340 kg/m³ (0.73). Below that they are not run:
the rotor loses the stiffness and damping the fluid gives it, and the efficiency falls with a warmer, lighter inlet. The lower suction limit is the
saturated-liquid curve plus a margin, on the liquid side only. A pump built for liquids needs changes for this service:
seals, wear parts and rotor.

**What the mod's pump does.** Its rise is `ρ_s g H` on the suction's density: right in kind, 2 to 14 % low on a dense
fluid because the density rises through the pump. Its head share on one dense fluid follows the liquid-like weight `w`
between reduced densities 0.5 and 1.5 (S4): 0.95 at 1.0, 0.74 at 0.8, 0.52 at 0.6. Real pumps keep their full head at
all of these. The bounds 0.5 and 1.5 are the heat-transfer film's, not a pump's. Nothing in the mod wears a pump on a
light dense fluid except the dry-running fault, which reads the same share.

**Proposal (owner options, section 4).** Give the pump its own bounds, 0.5 and 1.0: the full head from the critical
density, 0.92 at reduced density 0.73, the gas rule at 0.5. Show the minimum density on the simulator's pump sheet (S5).
Leave the rise on the suction's density.

## 1. What the sources say

1. **Head and rise.** "Polytropic head", not differential head, is the work per unit mass a pump gives a compressible
   fluid; perfect-gas formulas give large errors near the critical point (Bergamini et al. 2011; Adams and Miller 2011).
   A GE ten-stage pump at 7,600 rpm and 10 MPa suction was tested on carbon dioxide at 15 °C and at 40 °C inlet: "with
   the same suction pressure but different inlet density, the discharge pressure varies accordingly with variation of
   average density" (Bergamini, figure 6: about 56 MPa against 44 MPa at low flow, so a rise of 46 against 34 MPa,
   ratio 0.74). Checked here with CoolProp: the inlet densities are 890 and 629 kg/m³ (ratio 0.71), and the isentropic
   heads of the two steps are 48.5 and 46.0 kJ/kg. So the pump made nearly the same head on both, and the rise followed
   the density.
2. **Minimum density.** The operating envelope of carbon dioxide pumps is bounded "on the right" by a minimum
   operating density "between 500 and 400 kg/m³", set by rotor dynamics and by the pressure ratio; a larger ratio
   means a larger change of density through the pump, which costs efficiency and range unless the stages differ
   (Bergamini, figure 1). A Sulzer four-stage diffuser pump ran water, carbon dioxide and nitrogen with "stable
   hydraulic and rotordynamic performance even at densities as low as 340 kg/m3" (Turbomachinery Magazine 2025, on
   Wharton's session). A train is started by pressurising the pump "until the minimum density is achieved"
   (Bergamini).
3. **Efficiency.** The same test: the overall efficiency peaks near 65 % at 15 °C and near 63 % at 40 °C (read from
   figure 6), with a changed curve shape; "further increase of suction temperature led to progressive decrease of the
   efficiency".
4. **Suction.** The envelope's lower bound is the saturated-liquid curve plus a margin for the pump's NPSH, the inlet
   temperature control and the composition (Bergamini). Cavitation is "possible" but "significantly less severe than
   in liquid-only service"; operators keep "a clear margin between inlet conditions and the saturation line"
   (Turbomachinery Magazine 2025). Near the critical point a compressor inlet can condense or cavitate locally; away
   from it, it does not (Lettieri, Yang and Spakovszky 2015).
5. **Temperature.** 30 to 54 MPa warms the Tupi mixture from 40 to 76 °C (Bergamini). The temperature rise limits the
   ratio of one pump; pumps in series are cooled between (Turbomachinery Magazine 2025).
6. **The machine.** Low viscosity: poor lubrication, little damping from the wear parts, so a lateral rotor analysis
   is mandatory, swirl brakes and non-metallic wear parts are used, and a minimum speed applies on a light fluid.
   Seals: dry gas seals or double seals, not a liquid pump's single seal. Compressible fluid: stall and pressure
   pulsations, so the impellers are changed (Bergamini; Turbomachinery Magazine 2025). "With some hydraulic and
   mechanical modifications, conventional centrifugal pumps can handle supercritical fluids."
7. **Pump or compressor.** Up to 10 to 13 MPa discharge a geared compressor is the better machine; above it,
   compression, cooling to the dense phase and a pump saves 5 to 15 % of the power (Bergamini, figure 8).
8. **A pump on gas alone** makes the same head and almost no pressure (the density is a thousandth of a liquid's),
   and its seal and bearings, which the liquid cools and lubricates, are destroyed quickly (Pumps & Systems;
   WaterTech).

## 2. The rise on the suction's density, in numbers

An isentropic step with CoolProp: the head `gH = h₂ₛ − h₁`, and the rise the mod's law `ρ_s g H` gives for that head.

| Fluid, suction | δ | ρ in → out, kg/m³ | T rise, K | mod's rise / real rise |
|---|---|---|---|---|
| CO2 290 K, 10 → 20 MPa | 1.88 | 878 → 911 | 8.8 | 0.981 |
| CO2 320 K, 15 → 25 MPa | 1.55 | 727 → 780 | 14.4 | 0.962 |
| CO2 310 K, 10 → 20 MPa | 1.47 | 686 → 757 | 17.7 | 0.946 |
| CO2 310 K, 8.5 → 15 MPa | 1.10 | 515 → 613 | 22.3 | 0.904 |
| CO2 320 K, 10 → 20 MPa | 0.96 | 448 → 580 | 35.4 | 0.861 |
| ethylene 300 K, 8 → 16 MPa | 1.27 | 272 → 323 | 24.3 | 0.906 |
| nitrogen 300 K, 15 → 25 MPa | 0.52 | 164 → 217 | 48.0 | 0.861 |
| water 300 K, 1 → 11 MPa | 3.10 | 997 → 1001 | 0.2 | 0.998 |

The mod's rise is 2 to 14 % low on a dense fluid and exact on a liquid. The engine flashes the discharge at the
suction enthalpy plus the work, so the temperature rise is the equation of state's.

## 3. The mod's pump against the sources

| Behaviour | Real pump | The mod (after S4 and S4b) |
|---|---|---|
| Head on one dense fluid | the curve's, at any density it is run at | the curve's times `M(1 − w)`: 0.52 at δ 0.6, 0.74 at 0.8, 0.95 at 1.0, 1 from 1.5 |
| Rise | head times the average density | head times the suction's density (2 to 14 % low) |
| Gas lock | none (one phase) | none after S4b |
| Lowest density run | 400 to 500 kg/m³ (δ 0.86 to 1.07); 340 kg/m³ (δ 0.73) shown | no limit; the head share fades to the gas rule at δ 0.5 |
| Efficiency as the fluid lightens | falls slowly (about 65 to 63 % from 15 to 40 °C inlet, then further) | falls with the head share (`withHeadShare` scales both) |
| Suction limit | saturated-liquid curve plus margin | E only at a real flash (S4b) |
| Wear on a light dense fluid | seals, wear parts, rotor of a liquid pump suffer | the dry-running fault on `1 − w`; nothing else |
| On gas alone | the head, almost no rise; destroyed | `M(1)`: about 0 at 1 bar, 0.5 at 80 bar; the dry-running fault |

The head share is where the mod differs most. With the film's bounds a pump at the critical density of carbon
dioxide (δ 1.0, 468 kg/m³, inside every source's operating range) loses 5 % of its head, and at 340 kg/m³, where the
Sulzer pump ran stably, a third.

## 4. Options for the owner

1. **The pump's bounds.** `M(1 − w)` with `w` between reduced densities:
   - A. 0.5 and 1.5, the film's (implemented): head share 0.52, 0.66, 0.74, 0.95 at δ 0.6, 0.73, 0.8, 1.0 (carbon
     dioxide at 310 K; S4 review, and the law at 8 MPa for 0.73).
   - B. 0.5 and 1.0, the pump's own (recommended): 0.62, 0.92, 0.99, 1 at the same densities (the law at 8 MPa, by
     hand; to be measured if chosen). The full head from
     the critical density (GE's range starts at δ 0.86 to 1.07), nearly full at Sulzer's 340 kg/m³, and the gas rule
     at δ 0.5, so no gas above its critical temperature at ordinary network pressures moves. The compressor keeps
     0.5 and 1.5.
   - C. No share on one fluid at all (head share 1 at any density). The physics of one phase, but a pump on
     nitrogen or methane gas then blows gas at its full head where today it stands still: a change to the pump-on-gas
     rule (owner decisions D19, D20), not proposed.
2. **The minimum density on the sheet (S5).** A SHOULD row on the simulator's pump sizing, "suction lighter than a
   pump's range", below reduced density 0.86 (400 kg/m³ for carbon dioxide), in place of the bubble-point warning a
   dense fluid no longer gets. A text and a lang line: S5's.
3. **The rise on the average density.** Not proposed: 2 to 14 % low, on the safe side, and the fix needs the
   discharge density inside the pump's law. One law for pump and compressor on the suction's real isentropic
   exponent would give both (S4 review, section 5); it is its own batch.
4. **Wear of a liquid pump on a light dense fluid.** The dry-running fault already reads `1 − w`. With bounds B it
   reads the same weight as the head. Whether its intensity on a dense fluid is right is S5's table (S4b lists the
   pump's readers with numbers).

## Sources

- Bergamini, del Vescovo, Milone (GE Oil & Gas), "Centrifugal Pumps for CO2 Applications", Proceedings of the 27th
  International Pump Users Symposium, 2011 (read in full; the owner's copy).
- [Turbomachinery Magazine, "TPS 2025: Centrifugal Pumps Challenge Compressors in Supercritical Fluid Applications"](https://www.turbomachinerymag.com/view/tps-2025-centrifugal-pumps-for-compressible-supercritical-applications), 2025 (read in full).
- [Adams (Sulzer) and Miller (Dresser-Rand), "Pumping & Compression of CO2", tutorial, 2011](https://oaktrust.library.tamu.edu/server/api/core/bitstreams/9b5ef696-a6b2-4e43-8b85-71eba0874313/content) (search summary only).
- [Lettieri, Yang, Spakovszky, "An Investigation of Condensation Effects in Supercritical Carbon Dioxide Compressors", 2015](https://dspace.mit.edu/handle/1721.1/117029) (abstract).
- [Pumps & Systems, "How to reduce or eliminate air entrainment"](https://pumpsandsystems.com/how-reduce-or-eliminate-air-entrainment) and [WaterTech, "Negative effects of air/gas on pump performance"](https://www.watertechonline.com/wastewater/article/16194170/negative-effects-of-airgas-on-pump-performance-npshanpshr) (search summaries).

Not reached: the Adams and Miller tutorial's slides (the host asks for a browser check) and the pump makers' own
"equivalent water" selection method.
