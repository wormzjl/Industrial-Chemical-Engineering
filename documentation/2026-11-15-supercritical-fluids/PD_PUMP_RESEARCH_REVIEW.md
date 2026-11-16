# A positive displacement pump for supercritical service

Batch 2026-11-15-supercritical-fluids. Research review, main thread, 2026-11-16, at the owner's request ("add a
positive displacement pump head for supercritical service, research first"). No code changed. Numbers of section 2:
`tmp/dense-pump-research/pd_numbers.py` (CoolProp 8.0.0, isentropic steps).

## Summary

**Expected (what real machines do).** A reciprocating pump (plungers in a liquid end, the "pump head") moves a fixed
volume each stroke. It does not make a head: it delivers its displacement against whatever pressure the system
needs, up to its relief valve. So it does not care how light the fluid is, where a centrifugal pump's rise falls with
the density. Its losses on a compressible fluid are in volume, not in pressure: the fluid left in the clearance at
the end of the stroke re-expands before the suction valve opens, and the delivery falls by
`c (ρ_d/ρ_s − 1)` of the displacement (`c` the clearance as a share of the displacement). The same machine is a pump
on a liquid and a compressor on a gas; on a supercritical fluid it is simply in between. That is why reciprocating
machines carry carbon dioxide injection at low flows and high pressures, supercritical extraction, and ethylene to
300 MPa. A vapour in the chamber is what stops it: the vapour re-expands and fills the stroke.

**What the mod has.** The law exists: `PositiveDisplacement` (the displacement less a slip of 0.4 % per relief rise,
then the relief line), used by the reciprocating compressor and by the set-flow pumps. It has no clearance term (its
volumetric efficiency is 1 at any ratio). There is no reciprocating pump among the equipment: the constructed pump is
the centrifugal one (API 610 OH2), which fades below the critical density.

**Proposal (owner options, section 4).** A reciprocating plunger pump as a new piece of equipment on a driver's
shaft, on the existing law; the clearance term left out, as it is for the compressor (0.4 to 4.5 % on a dense fluid
at a clearance of 10 %); no chilled head (the player cools the suction with an exchanger).

## 1. What the sources say

1. **Use.** A carbon dioxide injection pump "could be a reciprocating positive displacement pump depending on flow
   and pressures"; dense-phase boosters are "multistage centrifugal or reciprocating pumps" (CO2 pump patents;
   Southwestern Petroleum Short Course). A triplex plunger pump in a supercritical carbon dioxide loop (51 L/min, 60
   to 160 bar inlet, 100 to 1,000 rev/min) "was shown to perform exceptionally with liquid or supercritical inlet
   conditions" (Aakre, Anderson, Brooks 2021). Ethylene is compressed to 2,500 to 3,500 bar by reciprocating plunger
   machines (Burckhardt; LDPE patents), where "the influence of the cylinder clearance on the volumetric efficiency
   is slight because of the small reduction in specific volume".
2. **Volumetric efficiency.** It is the delivered volume over the swept volume. The chamber volume beyond the
   displacement matters with a compressible liquid: the efficiency "decreases with an increase in the (c + d)/d
   ratio", because the trapped fluid re-expands and holds the suction valve shut (reciprocating pump texts). In a
   carbon dioxide piston pump the "compression retention volume loss" was 28.6 % of the displacement and the main
   loss; leakage past the pistons was about 0.5 % (MDPI 2025, a swash plate pump). In the triplex test the
   efficiency rose with speed from 10 to 40 % of the range and then stayed level: a leak is a rate, so it costs
   less of a faster stroke.
3. **The chilled head.** Liquid carbon dioxide is "as much as ten fold" more compressible than ordinary liquids, and
   compression warms it; so laboratory and extraction pumps chill the head and the feed "to insure that CO2 remains
   in the liquid state during pump aspiration" (chromatography pump patents). It keeps the suction dense and free of
   vapour: the clearance loss small and the mass per stroke large.
4. **What a real one also needs** (not proposed for the game): a relief valve (a displacement pump against a shut
   valve raises the pressure until something yields), suction and discharge dampers against pulsation, an
   acceleration head on top of NPSH, seals for a fluid that does not lubricate.

## 2. The clearance loss, in numbers

An isentropic step with CoolProp. `η_v = 1 − c (ρ_d/ρ_s − 1)`; the last column is the isentropic work over the
liquid form `ΔP/ρ_s`.

| Fluid, suction → discharge | δ | ρ_d/ρ_s | η_v at c 0.1 | c 0.3 | c 1.0 | work / (ΔP/ρ_s) |
|---|---|---|---|---|---|---|
| water 300 K, 1 → 21 MPa | 3.10 | 1.009 | 0.999 | 0.997 | 0.991 | 0.996 |
| CO2 268 K, 5 → 25 MPa (a chilled head) | 2.08 | 1.044 | 0.996 | 0.987 | 0.956 | 0.977 |
| CO2 290 K, 10 → 30 MPa | 1.88 | 1.067 | 0.993 | 0.980 | 0.933 | 0.965 |
| CO2 310 K, 10 → 30 MPa | 1.47 | 1.168 | 0.983 | 0.950 | 0.832 | 0.912 |
| CO2 310 K, 8.5 → 25 MPa | 1.10 | 1.336 | 0.966 | 0.899 | 0.664 | 0.834 |
| CO2 320 K, 10 → 30 MPa | 0.96 | 1.447 | 0.955 | 0.866 | 0.553 | 0.794 |
| ethylene 300 K, 8 → 25 MPa | 1.27 | 1.300 | 0.970 | 0.910 | 0.700 | 0.851 |
| nitrogen 300 K, 15 → 30 MPa | 0.52 | 1.448 | 0.955 | 0.866 | 0.552 | 0.815 |
| CO2 gas 300 K, 1 → 3 MPa | 0.04 | 2.381 | 0.862 | 0.586 | 0 | 0.619 |
| nitrogen gas 300 K, 0.1 → 0.4 MPa | 0.004 | 2.690 | 0.831 | 0.493 | 0 | 0.567 |

- With a small clearance (10 %, a head built for the service) a dense fluid costs 0.4 to 4.5 % of the delivery.
  Chilling the suction from 310 to 268 K takes the loss from 1.7 to 0.4 % and raises the mass per stroke by 42 %
  (686 to 971 kg/m³): that is what a chilled head buys.
- The delivery stops where `ρ_d/ρ_s` reaches `1 + 1/c`: never on these states at c 0.1 (a ratio of 11).
- The work a displacement pump puts into a compressible fluid is less than `Q_s ΔP`, by 2 to 21 % on the dense
  states: the volume shrinks as the pressure rises.

## 3. The mod against the sources

| Behaviour | Real reciprocating pump | The mod's `PositiveDisplacement` movers |
|---|---|---|
| Delivery | displacement × speed × η_v | displacement × speed, less the slip |
| Rise | the system's, to the relief setting | the same (the relief line) |
| Slip | about 0.5 %, more at low speed | 0.4 % per relief rise, at any speed |
| Clearance loss | `c (ρ_d/ρ_s − 1)` | none |
| A light dense fluid | no loss of rise | no loss of rise (the set-flow pump counts the fluid's volume) |
| Vapour at the suction | locks the chamber | a liquid mover's suction limit and vapour lock (E, p_v) |
| Work | the isentropic step | `Q ΔP` for a pump; the gas law for a compressor |
| As equipment | triplex plunger pumps (API 674) | none: only the reciprocating compressor (API 618) and in-line set-flow pumps |

## 4. Options for the owner

1. **The equipment.**
   - A. A reciprocating plunger pump of its own (a triplex power pump, API 674): a frame, a liquid end with suction
     and discharge nozzles, a shaft end for a driver, as the centrifugal pump and the reciprocating compressor
     have. Recommended: it is what plants install, and "pump head" is its liquid end.
   - B. A pump liquid end as an option of the reciprocating compressor's frame (one model, two cylinder kinds).
     Fewer models, but a compressor frame (API 618, balanced-opposed) is not a pump's.
2. **The law.**
   - A. The existing `PositiveDisplacement` as it is: the set displacement at any density, the relief line, no
     clearance term (recommended). The same simplification already stands for the reciprocating compressor. It
     leaves out 0.4 to 4.5 % of the delivery on a dense fluid at a 10 % clearance.
   - B. The clearance term, `η_v = 1 − c (ρ_d/ρ_s − 1)` with `ρ_d/ρ_s` from the suction's real isentropic exponent,
     for the pump and the compressor together. It makes a warm suction cost delivery and gives the compressor its
     textbook volumetric efficiency (0.83 to 0.86 at a ratio of 3 to 4), which moves every reciprocating
     compressor in a saved world: its own batch.
3. **The work.** `Q ΔP` as the set-flow pump has it (2 to 21 % high on a dense fluid, on the safe side for the
   driver), or the real isentropic step. Recommended: leave it; it goes with option 2 B.
4. **A chilled head.** None (recommended): the player cools the suction with an exchanger, which the network
   already rewards with density. Or a jacket on the head as an accessory: more to build for a small effect.
5. **Its range and its place in the simulator.** A sheet type "Plunger pump" beside the centrifugal pump: chosen
   below a flow limit and above a rise limit, or by the player; no minimum suction density row for it (that is the
   point of it). The limits come with the plan.

## Sources

- [Aakre, Anderson, Brooks, "Volumetric efficiency and pressure pulsations of a triplex reciprocating supercritical CO2 pump", The Journal of Supercritical Fluids, 2021](https://ouci.dntb.gov.ua/works/42Oy508l) (abstract).
- ["Numerical Simulation Study on the Volumetric Efficiency Loss of CO2 Swash Plate Axial Piston Pumps", Applied Sciences 15(13), 2025](https://mdpi.com/2076-3417/15/13/7032) (search summary).
- [Burckhardt Compression, hyper compressors for LDPE](https://www.burckhardtcompression.com/products/hyper-compressors/) and [a compressor text's chapter on hyper compressors](https://tte-lisa.ac.uk/wp-content/uploads/vle_content/Library/mechanical/Theory%20of%20Compressors/5%20HYPER%20COMPRESSORSch05.pdf) (search summaries).
- [US 8,215,922, a compressible fluid pumping system](https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/8215922) and [US 5,214,925, liquefied gases as a refrigerant against cavitation and compressibility](https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/5214925) (search summaries: the chilled head).
- [US 6,224,355, a carbon dioxide pump and pumping system](https://patents.google.com/patent/US6224355) and [Southwestern Petroleum Short Course, centrifugal and reciprocating pumps for CO2 pipeline and injection services](https://www.swpshortcourse.org/paper/total-systems-approach-centrifugal-reciprocating-pumps-co2-pipeline-injection-services) (search summaries).
- [Pumps & Systems, "Positive displacement pumps, part one: reciprocating pumps"](https://pumpsandsystems.com/positive-displacement-pumps-part-one-reciprocating-pumps) (search summary; the page asks for a browser check).

Not reached: API 674's own volumetric efficiency formula and the full text of the triplex paper. The clearance law of
section 2 is the standard derivation (the trapped fluid's re-expansion); for a gas it is the textbook compressor
formula `1 − c (r^(1/k) − 1)`.
