# Vacuum-column test — 2026-10-28

## 1. Expected behaviour

- Atmospheric CDU bottoms are the appropriate feed: retain the whole hydrocarbon composition, reheat it, then reduce pressure to recover gas oils and leave a heavier residue.
- [Penn State, FSC 432](https://courses.ems.psu.edu/fsc432/node/534) describes atmospheric residue reheating to 730–850 F (388–454 C), vacuum gas-oil recovery and low pressure-drop packing. This supports a 400 C heater test, not the specific authored draws or steam rate below.
- [Sulzer, refinery applications, p. 12](https://www.sulzer.com/-/media/files/products/separation-technology/brochures/english/refinery_applications_e10531_en_web.pdf) gives a typical deep-cut example: top 15 mmHg, flash zone 30 mmHg, coil outlet 420 C and flash zone 400 C. These are distinct pressure/temperature locations, not interchangeable controls.
- [Sulzer, crude vacuum distillation](https://www.sulzer.com/en/shared/applications/refining-crude-vacuum-distillation) describes structured packing and the need to minimize pressure drop. The current calculator offers fixed sieve-tray geometry, not packing, so this is a model-capability test rather than a validated refinery design.

## 2. What the code did

- Production baseline `79b853e0` (0.17.1); standalone JDK 21/Gson harness, production AGGRESSIVE cold route, no requested trace cutoff, strict closure and the production liquid-supply screen. No solver/model/data changes.
- The default catalog preset is **Tia Juana Light**, `tia_juana`, package `icheme:tjl20_methane`, assay `icheme:tia_juana_light_methane`; the template/feed pair is not WTI or the old CDU17 fixture.
- Its 40 interior-stage CDU accepted at maximum scaled residual `6.3061e-14`. Bottoms: **763.4858996 kmol/h, 339.6424258 t/h, 303.176 C, 272.019 kPa**. Fresh checks close every hydrocarbon component across all CDU products to < `1.61e-8 mol/s`; the vacuum feed exactly reproduces every bottoms component and preserves zeros/traces.
- The reconstructed package carries estimated heavy-residue properties; the CDU also retains its existing tray-2 water-dew-point advisory. These outputs do not establish experimental crude-assay accuracy.

Chosen wet request (draws, steam, diameter, stage count, reflux and cooler duties are authored trial settings, not source-reported specifications):

| Setting | Value |
|---|---|
| Hydrocarbon feed | Exact accepted CDU bottoms, 339.6424 t/h |
| Heater/letdown | 400 C at 272.019 kPa, isenthalpic letdown to nominal feed pressure; 26.9173 MW heating |
| Column inlet | 355.4291 C at nominal 2 kPa; hydrocarbon-only TP flash vapour fraction 0.834760 |
| Top pressure | 2 kPa absolute = 15.0012 mmHg = 0.02 bar |
| Stages/feed | 10 interior stages, feed at stage 8; client shows 12 trays/feed tray 9 |
| Diameter/drop | 15 m; initial nominal drop 0; production sieve-tray correction would determine the final profile |
| Condenser/reflux/reboiler | 60 C / 0.5 organic reflux ratio / 0 MW |
| Bottom steam | 1 wt% of hydrocarbon feed = 3.3964 t/h = 188.5302 kmol/h, 260 C |
| Side products | Stages 3 and 6: 76.3486 and 190.8715 kmol/h (10% and 25% of feed molar rate) |
| Pumparounds | Draw/return 3/2 at 1 MW cooling; 6/5 at 2 MW cooling |

- **Final outcome: NONCONVERGENCE.** Stage continuation stopped at eight interior stages, before the requested ten-stage state: no admissible Armijo-reducing step; maximum scaled residual `6.510834`, dominant stage-2 energy imbalance `+138.0814 MW`. This is a failed intermediate numerical state, not an operating temperature/profile or a physical yield prediction.
- Initial hot wet request (400 C column inlet, 250 Pa nominal stage drop) stopped at four stages, residual `0.0171299`; DRIFT_SPEC replay gave the same failure. Dry hot ten-stage and two-stage diagnostics also failed (four-stage VLE/iteration limit; pressure-anchor PC12 VLE failure respectively). No accepted VDU streams, duties or hydraulics were published.
- The 400 C heater enthalpy is matched at the **nominal** feed pressure. With no accepted hydraulic profile, actual flash-zone pressure/temperature cannot be certified. The independent feed flash omits stripping steam and is not a whole-column separation result.
- Deliverables: `tmp/vacuum-test/vacuum-feed-mixture.json`, `vacuum-column-template.json` (name includes UNCONVERGED), `vacuum-feed.csv`, complete input/outcome JSON and `verification.json`. [Harness and import instructions](../../tools/vacuum-column-test/README.md). No GUI/world validation or Gradle suite; no production code changed; code guide: no change.

## 3. Proposal for owner validation

- Retain this exact feed/request as a reproducible low-pressure regression case; inspect stage-growth seeds, phase support and trace-component VLE before changing physical specifications to chase convergence. The observations locate failure, but do not prove its numerical root cause.
- A reusable refinery VDU template needs a deliberate low pressure-drop internals model and qualified LVGO/HVGO cut/duty choices; the current sieve-tray-only calculator cannot certify that packed-column design. No physical simplification or solver fix was implemented.
