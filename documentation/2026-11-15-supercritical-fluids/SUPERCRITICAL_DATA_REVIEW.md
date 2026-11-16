# Supercritical fluids: data availability and what to do where data lack

Status: review, 2026-11-15, base `e9edc974b`. Read from the datasets under
`src/main/resources/data/icheme/materials/`, the test fixtures and the 2026-09-24 qualification report. No run.
Companion of [SUPERCRITICAL_LIMITS_REVIEW.md](SUPERCRITICAL_LIMITS_REVIEW.md) and
[SUPERCRITICAL_PLAN.md](SUPERCRITICAL_PLAN.md) (its S0 and S3 follow this document).

## Summary

1. **The model never lacks an input.** PR78 needs `Tc`, `Pc`, the acentric factor and an ideal-gas `cp`; all 47
   datasets have them, to 900 K. A supercritical state is always computed. What lacks is evidence of how good the
   number is, and three kinds of data the cubic does not supply: pairs, dense viscosity, water above 647 K.
2. **Reference data exist for six fluids** (nitrogen, carbon dioxide, methane, ethane, hydrogen, water): a test-only
   Java port of CoolProp's Helmholtz equations (`HelmholtzFluid`) and parity tables to 20 MPa and 1000 K. The
   2026-09-24 batch already measured the supercritical region to 10 MPa: density within 5.9 %, `cp` within 6.6 %,
   and 10.6 % next to band box 1 (`G3_QUALIFICATION_REPORT.md`, rows 43, 93, 94). That was on the pilot engine for
   four fluids and on the network's model for nitrogen and methane only.
3. **The twelve crude cuts have no reference and cannot have one**: a cut is not a substance. Their `Tc`, `Pc` and
   acentric factor are estimates; five of the twelve are flagged `estimated_heavy_residue`. Their real data are the
   boiling point, the standard liquid density and the viscosity fits. Section 3 is about them.
4. **Corrected by S0 (2026-11-15): the network's cut-gas pairs are not zero.** No library names a cut and the method
   record says `missing_interactions: zero`, but the network runs on the derived pairs (`DerivedPairs`): a gas with
   Henry data gets an E-PPR78 pair with every cut, the cut taking paraffinic counts from its molar mass (carbon
   dioxide 0.10 to 0.12, nitrogen 0.007 to 0.15, hydrogen sulfide 0.01 to 0.06, methane 0.02 to 0.06). Hydrogen with
   anything is 0 by the owner's rule of 2026-11-04. Two compounds that are neither a gas nor polar are 0. The first
   version of this review read the method record only and was wrong on this point
   ([SUPERCRITICAL_QUALIFICATION_REVIEW.md](SUPERCRITICAL_QUALIFICATION_REVIEW.md), M6).
5. **Rule proposed for a lack**, in this order: the licensed source already in use; an estimate from fields the
   record already has, flagged `estimated`; continuation with the grade `EXTRAPOLATED` and a notice. Never a hold,
   never a refusal (AGENTS.md, "Thermo validity").

## 1. What exists

| Need | Where | Coverage | Range |
|---|---|---|---|
| `Tc`, `Pc`, acentric factor | `properties/*.json`, `models.pr78` | 47 of 47 | n/a |
| Alpha function | Soave form of PR78; Mathias-Copeman for water only | 47 | continues above `Tc` |
| Ideal-gas `cp` | `ideal_gas_cp`, `shifted_polynomial_5` | 47 | fit to 900 K, held and integrated outside |
| Volume translation | from `standard_liquid_density_kg_per_m3` (a liquid anchor) | every compound with a liquid root at its anchor; one supercritical at its anchor has none (hydrogen, to confirm) | constant in T and P |
| Critical volume or `Zc` | not stored | 0 | the cubic's own `Zc` 0.3074 is used |
| Pairs `k_ij` | `interactions/` three libraries: 57, 8 (carbon dioxide with C1 to C5 and nitrogen), 11 | real compounds only; missing pairs zero | constants fitted to low and moderate pressure VLE |
| Group counts (E-PPR78) | `group_counts` | 35 real compounds; no cut. The E-PPR78 table is in the data; the bundled method `crude_pr78` does not name it | `k_ij(T)` |
| Viscosity | liquid and vapour log tables per compound, `transport/` calibrations, the cut-pair term | 46 and water's own | at 100 kPa or saturation; no pressure dependence |
| Thermal conductivity, diffusivity | none | 0 | films are constants (2000 and 100 W/m²K), so none is read |
| Water | IF97 Region 1, the Region 2 second virial, Wagner-Pruss | liquid to 623 K, dilute steam | nothing above 647 K at density |
| Crystals | CO2-I to 500 MPa, ice Ih to 210 MPa | both | enough for dense carbon dioxide |
| Reference for tests | `src/test/resources/science/thermo/coolprop/` (`<Fluid>.json`, `parity-<Fluid>.json`), `HelmholtzFluid`, `tools/coolprop-parity/` | six fluids | to 20 MPa, 1000 K |

Licences: ChemSep 8.3 data (Artistic 2.0), CoolProp fluid files (MIT), IAPWS formulations (free). The project's
licence position is in [../2026-11-04-refinery-compounds/DATA_LICENSE_REVIEW.md](../2026-11-04-refinery-compounds/DATA_LICENSE_REVIEW.md).

## 2. What lacks, and the proposal for each

| Id | Lack | Effect | Proposal | Cost |
|---|---|---|---|---|
| L1 | Reference equations for the other real compounds the scope names (propane, ethylene; later the C4, C5, C6 and aromatics) | Their supercritical error is unknown | Fetch the CoolProp fluid file of each named compound into the test resources (the oracle reads that format; check that it implements the file's term types). Only the compounds O1 names | one file and one table per compound |
| L2 | The network's own model is measured above 2 MPa for nitrogen and methane only | The 5.9 % and 6.6 % figures are the pilot's | S0 runs the same grid on the network's model for carbon dioxide and ethane and extends all to 30 MPa | S0 |
| L3 | Critical volume | The reduced density `δ` of the plan is the cubic's (12 % off for carbon dioxide at the critical point), and a residual viscosity needs the real one | Append `critical_volume_m3_per_mol` to the datasets from the ChemSep source already imported (`tools/compound-import`); absent means the cubic's value, which is today's behaviour. Each record's own revision moves | a data import; no facts change |
| L4 | Dense viscosity | A dense fluid reads its dilute value | The residual-viscosity correlation in reduced density (plan O5 A) on L3's volume. Truth for six fluids from CoolProp's viscosity models, as test tables | S3 |
| L5 | Pairs between two real compounds that are neither a gas nor polar | `k_ij = 0` for 197 such pairs with E-PPR78 counts (S0: 15 of them at 0.05 or more at 300 K) | Leave them at zero. S0 found E-PPR78 unusable as a blanket rule: ethylene with isobutene gives -0.21 at 300 K and -368 at 500 K. The large ones are sulfur dioxide and mercaptan pairs, outside this batch's fluids | none |
| L6 | Mixture reference data at pressure | No truth for a dense mixture | Three binaries from CoolProp's mixture models (methane-carbon dioxide, methane-ethane, nitrogen-methane) as density tables generated by the tool. No more: the rule is proportionate research | one tool run |
| L7 | Water above 647 K | The bare cubic | Plan S6 (only with O1 B): IF97 Regions 2 and 3 are free formulas, and the oracle already has IAPWS-95 water as truth | owner decision |
| L8 | Ideal-gas `cp` above 900 K | Held and integrated | None: no supercritical use needs it | none |

## 3. The crude cuts

### What a cut's record holds

| Cut | `Tc` K | `Pc` MPa | Acentric factor | Molar mass g/mol | Boiling point K | Flag |
|---|---|---|---|---|---|---|
| pc01 | 509.0 | 3.64 | 0.238 | 81.1 | 331.4 | |
| pc02 | 570.1 | 3.08 | 0.301 | 105.5 | 383.5 | |
| pc03 | 623.6 | 2.63 | 0.363 | 131.6 | 431.8 | |
| pc04 | 674.6 | 2.26 | 0.431 | 161.7 | 480.2 | |
| pc05 | 723.9 | 1.94 | 0.502 | 197.1 | 529.1 | |
| pc06 | 771.2 | 1.68 | 0.580 | 238.3 | 577.9 | |
| pc07 | 816.8 | 1.46 | 0.666 | 286.5 | 626.7 | |
| pc08 | 871.0 | 1.24 | 0.785 | 358.2 | 687.0 | estimated heavy residue |
| pc09 | 939.6 | 1.01 | 0.970 | 475.4 | 766.0 | estimated heavy residue |
| pc10 | 1024.8 | 0.80 | 1.279 | 678.4 | 868.2 | estimated heavy residue |
| pc11 | 1106.3 | 0.67 | 1.648 | 937.8 | 965.0 | estimated heavy residue |
| pc12 | 1220.1 | 0.57 | 2.258 | 1474.8 | 1095.1 | estimated heavy residue |

- Measured or assay-based: the boiling point (TBP curve), the standard liquid density, the viscosity (Twu tables and
  the cut-pair term fitted to 23 whole-crude viscosities).
- Estimated: molar mass (Riazi 1986), `Tc` and `Pc` (Riazi-Daubert 1985, fitted on C5 to C20: pc01 to pc07 lie inside
  by molar mass, pc08 to pc12 outside), the acentric factor (fitted so that PR78 gives the boiling point: one vapour
  pressure point at 1 atm), the melting point.
- Absent: group counts of its own (the derivation gives it a normal paraffin's by molar mass), a library pair, a
  critical volume, any reference.

### Where a cut is supercritical in play

- **Pressure alone means nothing for a cut.** `Pc` is 0.57 to 3.64 MPa, so almost any pumped oil is above it. Below
  its `Tc` that is a compressed liquid, which the model already carries, anchored on the measured liquid density.
- **pc01 to pc05 as nearly pure streams** (naphtha, kerosene, light gas oil) above 509 to 724 K and above 2 to
  3.6 MPa: a fired heater's coil, a hydrotreater's feed and effluent. This is the real case. pc01's band box 1 is
  484 to 560 K at 2.9 to 5.5 MPa.
- **pc06 to pc12 are never supercritical as a fluid of their own**: their `Tc` (771 to 1220 K) is above cracking
  temperatures and mostly above the 900 K data range. In a dense state they are the solute: oil in hydrogen-rich gas
  at 6 to 9 MPa, or in a dense solvent (plan S7).
- **A whole crude** has no component at 0.95, so the pure-fluid band never applies to it. Its near-critical region is
  found by the stability test, and only the short-tie-line rule (review A2, plan O3) touches it.

### What can be done without data

| Id | Step | What it gives | What it cannot give |
|---|---|---|---|
| P1 | Nothing refuses, nothing holds (already true): the cubic continues from `Tc`, `Pc` and the acentric factor | A state for every cut at every T and P | Accuracy |
| P2 | Surrogates: real compounds with a reference equation at about the same `Tc`, `Pc` and acentric factor. pc01: n-hexane (507.8 K, 3.03 MPa) and cyclohexane; pc02: n-heptane and toluene; pc03: n-decane; pc04: n-dodecane. S0 measures the model against them on the supercritical grid and declares that error for pc01 to pc04 at equal reduced state | A declared error for the four light cuts, grade `ESTIMATED_DECLARED_ERROR`, and a pressure range raised on that evidence | Anything for pc05 to pc12: CoolProp's heaviest n-alkane is n-dodecane (acentric factor 0.57; to confirm against its fluid list) |
| P3 | pc05 to pc12 above 2 MPa stay `EXTRAPOLATED` with the notice. Their compressed-liquid density still rests on the measured standard density through the translation | Honest grade; play is not blocked | A number for the error |
| P4 | Critical volume of a cut from its own measured density: the Rackett compressibility that reproduces `standard_liquid_density` at 288.7 K, then `Vc = Z_RA R Tc / Pc`. Flagged `estimated` | A reduced density and a dense viscosity anchored on assay data, not on the cubic's `Zc` | A check of it |
| P5 | Pairs of a cut with a gas: already there (summary, point 4), by the E-PPR78 table on paraffinic counts. Keep them. S0's sensitivity: carbon dioxide dissolved in the medium crude at 320 K and 2 MPa is 0.196 today, 0.293 with the pairs at zero and 0.160 at 0.15; the oil carried in the gas moves by less than 10 % except at 10 MPa. Open, for the owner only if hydrotreating at pressure matters: hydrogen with a cut is 0 by rule, where E-PPR78 gives 0.28 to 0.47 for hydrogen with real compounds (hydrogen in the liquid at 450 K, 10 MPa: 0.132 at 0, 0.120 at 0.15) | The solubility of a dense gas in oil and of oil in a dense gas | Validation against a cut; a cut is treated as a normal paraffin, so its aromatic part is not seen |
| P6 | A cut's dissolved-gas viscosity and the cut-pair term are untouched: they are liquid laws at low pressure | No moved reference | n/a |

S0's surrogate result (M5): PR78 on a surrogate's own constants keeps density within 5.7 % to 30 MPa for n-hexane,
n-heptane and toluene (pc01, pc02); n-decane (pc03) reaches +7.1 % and n-dodecane (pc04) +9.5 % at 30 MPa. The error
grows with the acentric factor, so pc05 to pc12 are expected worse and stay `EXTRAPOLATED`.

### What stays unknown for cuts, stated to the player

- The dense-phase density and enthalpy of pc05 to pc12 (grade `EXTRAPOLATED`).
- How well a cut's paraffinic E-PPR78 pair fits an aromatic or naphthenic cut.
- Thermal cracking: not a property; a cut held above about 650 K stays the same cut.

## 4. Changes this brings to the plan

- S0 adds: the network's model on the existing tables (L2), the surrogate run for pc01 to pc04 (P2), the pair sizing
  (L5, P5), the three binaries (L6). It reuses the 10 MPa results of the 2026-09-24 batch and measures only above them.
- S3 adds: the critical volume field (L3, P4), the cuts' pressure ranges by surrogate (P2), the pair library if the
  owner takes P5 B, C or D.
- Owner decisions: **O8** pairs for cuts: closed by S0 (they exist; only hydrogen's rule is open); **O9** E-PPR78 as the
  rule for a missing pair (L5): S0 recommends no; **O10** the critical volume field (L3, P4) or the cubic's own value.
