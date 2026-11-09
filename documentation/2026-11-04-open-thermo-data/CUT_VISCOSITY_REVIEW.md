# Cut viscosity and mixture term review (O4)

Batch [2026-11-04-open-thermo-data](README.md), work package O4, decisions E5, E12, E14, E15. Commits `34573f70e` (cut tables, citations), `7972a2a9a` (one-term mixture term), `77152ecaf` (chain-100). Data from [`tools/crude-grades/`](../../tools/crude-grades/README.md); probes in [`tools/open-thermo-data/`](../../tools/open-thermo-data/README.md); scratch `tmp/open-thermo-o4/`.

## 1. Expected behaviour

A crude's liquid viscosity is a property of its cuts and of how they mix. With cut tables from a published correlation (Twu 1985 + ASTM D341) and one pair coefficient fitted to open whole-crude data (ECCC, G = 1.0956), light and medium crudes should move by tens of percent at most, because the old Dalia rule already gave about the same pair factor (1.6 to 2.1). Heavy crudes move most, because the Dalia tables gave pc10, pc11 and pc12 one shared residue curve and Twu separates them (pc10 x0.007, pc12 x1.6e7 at 40 C). The one term has no temperature slope, so the hot end moves up relative to the old rule. The column solve reads no liquid viscosity, so it cannot move.

## 2. What the code does now

- Cut tables: `twu-1985-d341-r1` in `crude_pc01..12`; source text cites the DWSIM 10.2.3 characterisation of the Ledezma-Martinez 2019 Tia Juana Light TBP data (Watkins 1979 via Chen 2008), Riazi-Daubert Tc and Pc. Record revisions unchanged (metadata only).
- Mixture term: `methods/crude_pr78.json` `liquid_mixture` = `cut_pair_v1`, `coefficient` 1.0956, `components` crude_pc01..12, revision `eccc-cut-pair-r1`. `LiquidMixtureCorrection` is the coefficient, the participants' indices and the axis size; `logCorrection(amounts)` has no temperature.
- Participation design: the method's component list. It deletes the per-record descriptor field, its catalog map and the `Descriptor` type; a flag per cut would keep a field and a map. A listed compound that a package lacks does not take part; an unknown id is refused; a stray `liquid_mixture_descriptor` is refused, naming the record.
- Bit-for-bit proof (before the old form was deleted): `O4MixtureEquivalenceProbe` holds the old class verbatim and compares raw bits at [G, 0, 0, 0], descriptors {1, 0, 0} from the parent's records: 5 packages, every assay, single compounds, 20,000 random mixtures each at 150 to 1200 K; 100,132 compared, 0 differing.

## 3. Measurements

Whole-crude liquid viscosity, `MixtureViscosity` on `icheme:tjl20_methane`, mPa s (pair factor exp(delta)):

| Assay | 15 C before -> after | 40 C | 100 C |
|---|---|---|---|
| Tia Juana (tjl20) | 14.7 (1.86) -> 15.2 (2.25) | 5.90 -> 6.60 | 1.52 -> 1.75 |
| WTI | 2.70 (1.68) -> 2.94 (2.00) | 1.61 -> 1.80 | 0.697 -> 0.798 |
| Cold Lake Blend | 77.4 (1.65) -> 58.5 (1.90) | 20.4 -> 17.6 | 2.95 -> 2.81 |
| Dalia | 180 (2.07) -> 125 (2.55) | 42.6 -> 35.6 | 5.42 -> 5.76 |
| Bonga | 16.8 (1.94) -> 15.3 (2.38) | 6.90 -> 6.96 | 1.83 -> 2.13 |
| Upper Zakum | 13.1 (1.73) -> 11.7 (2.04) | 5.39 -> 5.24 | 1.41 -> 1.51 |

- Pinned hydraulic references that moved (old -> new): pure pc12 100 Pa s crossing 333.94 -> 382.40 K (`SolidClosureFeasibilityTest`, `SolidTransportAcceptanceTest` fixtures moved to 380 to 390 K); line drops, bar/100 m (`LineSizingSampleTest`): CRUDE 0.058 -> 0.059, S-1 0.233 -> 0.236, S-2 0.196 -> 0.199, S-3 0.085 -> 0.086, S-4 0.084 -> 0.085, DIESEL 0.165 -> 0.166, S-10 0.179 -> 0.183 (+2.2 %), RESIDUE 0.345 -> 0.342 (-0.8 %); P-101 path 3.07 -> 3.10 bar (PA 2 0.40 -> 0.41, PA 3 0.29 -> 0.30, E-101 0.17 -> 0.18); residue path 0.37 -> 0.38; E-101 kept at 0.75 m 1.29/0.41 -> 1.31/0.43; E-102 kept bank 0.05 -> 0.06 (the residue cooled to 45 C). No bore, size, lamp or deciding limit moved. Every other sizing, column-sizing, utility and simulator-run reference holds.
- chain-100 exact before re-capture: 40 differences; moles 1.0e-4 relative, temperature 9.6e-4 K, phase fraction 4.5e-6, flow 4.4e-2 relative (pipe 58, 2.8e-4 kg/s); outside the declared gate, re-captured.
- 200-seed hold quick check against O2's stored run: cold random 197 clean (E1 85, E6 101, F 118, as the baseline); bundled defaults 200/200 with identical counters; bundled random 199/200, seed 6 class F (wall deadline, 17.6 s against 16.4 s in the baseline) and clean when replayed alone (18.8 s); refused 0 everywhere. Counters, bundled random: Newton iterations 130,767 -> 129,640 (-0.9 %), residual evaluations -0.5 %: no cost change.
- Column: no liquid viscosity is read by the column solve (`V3TrayHydraulics` uses Rackett densities and a constant surface tension), so the default preset does not move. The process simulator's sizing reads it (above).

## 4. Owner decisions (least change taken, reversible)

1. Participation lives in the method's `components` list (deletes more than a per-cut flag).
2. Record revisions kept: the cut records (`crude-regrouped-r1`, citation and descriptor removal only) and the method record (`crude-pr78-r2`; the term carries its own revision).
3. `MixtureViscosity`'s formulation label `log-liquid-pair-groups-wilke-v2` kept: equal numbers at equal parameters; the data revisions move the identity.
4. Sizing pins re-pinned to the run's values (the probe tables' third place for eight lines).

## 5. Open items

- `FluidTpEquilibriumPhUvTest.freeWaterRoundTripsAndBoiling` fails at 50d6c506f as well (UV pressure 499999.9935 against 500000 at 460 K, 5 bar): not O4; it passed in the refinery final lane, so O2 or O7 moved it.
- Fluid GameTests not run (final lane). Code guide: no change (updated at merge).
- The dilbit grade stays about 20 times too thin (E11), and pc11/pc12 are Twu extrapolations (SG at or above 1.0).
