# Open thermo data plan

Batch [2026-11-04-open-thermo-data](README.md). This plan follows the owner's answers to decisions D-A and D-B of [DATA_LICENSE_REVIEW.md](../2026-11-04-refinery-compounds/DATA_LICENSE_REVIEW.md).

## 1. Expected behaviour

- A crude in the game is a distribution over a shared set of boiling cuts (`crude_pc01` to `crude_pc12`) plus the light ends. A generated grade sets that distribution from a few bulk specifications: API gravity, light-end content and residue share. It takes no third-party assay values.
- The open sources give the same physics, because they implement the same correlations:
  - ideal-gas Cp of nitrogen, methane and water vapour: CoolProp alpha0, NASA CEA, IAPWS-95;
  - nitrogen viscosity: Lemmon and Jacobsen 2004, as implemented in CoolProp;
  - the liquid-volume calibration points: the reference equations of state in CoolProp.

  The expected differences are well under 1 %. O2 measures them.
- The liquid viscosity of a crude cut comes from a correlation or an open dataset, not from the Dalia assay.

## 2. What the data holds now

- **Seven assays.**
  - Five from ExxonMobil: `wti_light_export`, `upper_zakum`, `bonga`, `dalia` and `cold_lake_blend`. They are rebinned onto the shared cuts.
  - `tjl19` and `tjl20`: Tia Juana Light, from Ledezma-Martinez 2019, which takes it from Watkins 1979. The cut properties come from the project's DWSIM characterisation of this crude.
- **Presets.**
  - Fluid presets: `tia_juana_light_methane`, `wti_light_export` and `cold_lake_blend`.
  - The three column presets use Tia Juana.
  - The network's default column preset is `tia_juana`.
  - The example sheet `assets/icheme/flowsheet_examples/crude_unit.json` names Tia Juana identities.
- **Cut viscosity.**
  - The Dalia Walther curves: one 608-node log table per cut.
  - The global Dalia correction `methods/crude_pr78.json` `liquid_mixture` (`symmetric_pair_groups_v1`, 4 coefficients), with a `liquid_mixture_descriptor` per cut.
- **Values derived from NIST.**
  - The nitrogen Shomate Cp and its viscosity tables (`properties/nitrogen.json`).
  - The methane Cp fit (`properties/tjl20_methane.json`).
  - The H2O(g) Shomate in `water/water.json`.
  - Eight points in `transport/liquid_calibration.json`.
  - The JANAF comparisons in `spine/*.json`.
  - The test references `fluid/reference/{nitrogen-cp-checks,water-vapor-checks,water-vapor-compression}.json`.
  - The raw WebBook output in `tools/fluid-probes/f4/nist/` (7 TSV files).
- **The column's neural model.** It was trained on six crudes and the blends between neighbours on a ring: Tia Juana, WTI, Upper Zakum, Bonga, Dalia and Cold Lake. The sidecar's `compositionEdges` hold those six compositions exactly (to about 1e-17), so the sidecar carries ExxonMobil-derived data.
  - It binds only to feeds within 1e-8 of a ring segment, and its global feature bounds include the 19 feed fractions.
  - It needs the trained records to exist at their exact revisions and to match by content. Those records include methane `tjl20-methane-nist-r1` and water `water-iapws-shomate-r2`.
  - After O2 or O3 it fits no game feed, and the column falls back to its classical initializer.
- **Client libraries.** `assets/icheme/columnpreset/` and `assets/icheme/mixturepreset/` hold one file per crude, Tia Juana and the five ExxonMobil crudes. They are read by `ColumnTemplates`, `V3ClientPresets` and `V3ColumnTemplate`, and their lang keys go with them.
- **Other copies.** Test fixtures and inline test values are built from the ExxonMobil assays, and so are two world dumps in `tools/`, `tools/wp5-legacy-digest` and the Dalia numbers in `CRUDE_REGROUPING_PLAN_REVIEW.md`. [UNUSED_DATA_REVIEW.md](UNUSED_DATA_REVIEW.md) lists them all.

## 3. Work packages

| WP | Content | Physics change |
|---|---|---|
| O1 | Move the raw NIST copies in `tools/` to the main checkout's `research/`. Leave links in place. | no |
| O2 | Re-derive the NIST values from CoolProp (MIT), NASA CEA (Apache-2.0) and IAPWS-95. Bump each changed record's revision. Measure how far each value moves, the restates, the affected gates and the column on its classical initializer. | yes |
| O3 | Four generated grades on the shared cuts: light, medium, heavy and oil sand. They replace the seven assays in the fluid presets, the column presets, the network defaults, the example sheet and the client libraries (`columnpreset/`, `mixturepreset/`), with en_us names. The test fixtures and inline values built from the old assays move to the grades. Tia Juana stays as a test-only fixture if tests need it (E5). Check whether saves store fluid preset ids; the inventory found that devices copy the composition. Add aliases only if they do. | yes |
| O4 | Re-source the liquid viscosity of the cuts and the mixture correction from a non-ExxonMobil source (research R1, then an owner choice). | yes |
| O5 | Remove unused data, from [UNUSED_DATA_REVIEW.md](UNUSED_DATA_REVIEW.md) section 7. Now: `tools/wp5-legacy-digest`, the two WTI world dumps in `tools/`, the Dalia numbers in `CRUDE_REGROUPING_PLAN_REVIEW.md` and `neural/regrouped-model-provenance.json`. Data that only tests read moves to `src/test`. After O3 and O4: the old assays, presets and tables. | no |
| O7 | First, the methane Cp refit of E16. Then the neural model's physics check compares values within a tolerance (E8, E9), not record ids or revisions; the trained physics travels with the model as values. Measure each compound's distance from the trained physics. Re-capture the wet-boundary fixture (O2 decision 7). | E16 only; the rest is binding |
| O8 | Once O3 is in, test the existing model on the four grades and on blends between them and the trained crudes. Force its seed past the composition gate in a probe, and compare counters with the classical initializer. The composition check is chosen from the result (E7). | none |
| O6 | One final lane for this branch's two batches, run on the merged code (E19): after `main` carries `claude/realistic-pump-compressor-6fdfd9` and `main` is merged here, at the merge preparation the owner starts. Until then, only targeted runs. | none |

## 4. Decisions

| # | Decision | By | Reversible |
|---|---|---|---|
| E1 | Replace the ExxonMobil assays and the Tia Juana assay with generated light, medium, heavy and oil-sand grades. Take the viscosity from another source (D-A). | owner, 2026-11-04 | |
| E2 | NIST data: D-B option 2, then see how it performs. | owner, 2026-11-04 | |
| E3 | Remove unused data. | owner, 2026-11-04 | |
| E4 | A batch of its own, on the same branch as the refinery-compounds batch. | orchestrator | yes |
| E5 | The shared cuts `crude_pc01` to `crude_pc12` stay. Their properties come from the project's DWSIM characterisation, and their records gain the missing citation. The grades are compositions on these cuts, as the ExxonMobil assays were. | orchestrator | yes |
| E6 | O2 leaves the neural sidecar alone, so the model drops out and the fallback is measured. The owner decides on the model after O8. Its training ring includes the five ExxonMobil crudes and the sidecar stores their compositions, so before a public release the model must be retrained on open data or removed. | orchestrator; the model is the owner's choice | yes |
| E7 | The owner did not pick a composition check yet: "Do some testing with the existing neuro model when the new assays are established and see how it works first" (O8). Earlier: "The neuro model should be principal-composition checked instead of relying on assay names". | owner, 2026-11-04 | |
| E8 | The model's physics check compares values within a tolerance, whatever the record names or revisions. | owner, 2026-11-04 | |
| E9 | The tolerance is 1 % relative on each compared value, the example in the owner's option. A function of temperature is compared on a grid over the model's trained range. A binary pair parameter is near zero, so it is compared absolutely, within 0.001 (1 % of a 0.1 pair scale). O2, O3 and O4 change no pair. | orchestrator | yes |
| E10 | Grades are built shape first on the existing cuts ([CRUDE_GRADES_REVIEW.md](CRUDE_GRADES_REVIEW.md) 3.5-1): TBP and residue meet each spec, and the API is what the cuts give (light 40, medium 31, heavy 21.6, dilbit 29). A dense cut family may come later. | owner, 2026-11-04 | |
| E11 | The oil-sand grade is a dilbit: 76 % generated bitumen and 24 % condensate. Known limit: on these cuts it reads API 29 against about 21.5, and its viscosity is about 20 times too low. | owner, 2026-11-04 | |
| E12 | The mixture correction becomes a one-term open refit, delta = G (x_cut^2 - sum x_i^2) over the cuts, fitted to ECCC whole-crude viscosities. It deletes the three other coefficients, the per-cut descriptors and both temperature references. | owner, 2026-11-04 | |
| E13 | The ECCC CSV and Analytical Methods PDF are downloaded to the main checkout's `research/2026-11-04-open-thermo-data/eccc-oil-database/` (`MANIFEST.md`: sources, SHA-256, OGL-Canada attribution). | owner, 2026-11-04 | |
| E14 | Twu 1985 with the ASTM D341 two-point form for all 12 cuts. pc11 and pc12 are used as Twu gives them, under the existing 1e12 Pa s cap. The light ends are split as in the review's section 3.1. G is refitted on the full ECCC crude set rather than the six points read. | orchestrator | yes |
| E15 | G = 1.0956 is taken from the 13 ECCC crudes the cuts represent (API on the cuts within 3 of measured; 23 points, rms factor 1.89), not from the full set (G = 1.346, 32 crudes, rms factor 3.31). On crudes the cuts cannot represent, G would absorb the basis error: their cuts are denser and more viscous, which is not mixing asymmetry. At 15 C / 40 C, in mPa s: light 4.1 / 2.3, medium 17.6 / 7.5, heavy 238 / 72, dilbit 19 / 8 (`tmp/crude-grades/out/validation.md`). | orchestrator | yes; the full-set value is in `g-fit.json` |
| E16 | The methane ideal-gas Cp fit (`tjl20_methane`) comes from the reference spine as it is: the Setzmann-Wagner alpha0 up to 425 K, then the GERG-2008 ideal-gas part up to 1200 K (decision D13, spine r2). The result is `tjl20-methane-spine-r3`, within 0.013 % of O2's fit and +1.177 % above the pre-O2 fit at 900 K. Corrected in O7: the orchestrator had assumed a CEA segment above 600 K, as the nitrogen spine has. CEA would be worse: 2.2 % from the pre-O2 fit at 900 K, a 0.38 % step at 600 K, and 1.2 % high at 1000 K against ExoMol. | orchestrator, corrected by O7 | yes |
| E17 | O2 decisions 2 to 6 of [NIST_REDERIVATION_REVIEW.md](NIST_REDERIVATION_REVIEW.md) are accepted as made: the nitrogen fit pinned at 273.16 K (owner rule D37/S4), the pilot's CoolProp viscosity tables, the steam standard entropy cited as CODATA, the spine uncertainty removed, and the open holdouts. Decision 7, the wet-boundary fixture, is re-captured in O7 once the model binds again. | orchestrator | yes |
| E18 | O7's design of the physics check is accepted, with sidecar schema 4 and `TrainedPhysics`:<br>- The grid is 298.15..765.77 K: the datasets' column floor up to the highest feed temperature the model saw (`globalMax[1]`). Up to 900 K, methane would sit 1.18 % off and the model would refuse.<br>- Compared: Tc, Pc, omega and Cp(T) of the fed compounds; the water's saturation pressure, latent heat, vapour Cp and enthalpy; pairs within 0.001.<br>- Excluded: molar mass, boiling point, standard density, volume translation and every transport property. The prediction reads none of them: a 5 % change gives a bit-identical raw output. So O4 cannot unbind the model.<br>- A pair with an unfed compound takes the trained value.<br>Distances on the default package: methane 0.847 %, water 0.030 %, the other 18 compounds 0. | orchestrator | yes |
| E19 | This branch merges after `claude/realistic-pump-compressor-6fdfd9`, the rotating-machinery batch.<br>- The 2026-11-05 trial merge (`git merge-tree`, both branches from `fb9607dad`) has three conflicts:<br>  - `documentation/INDEX.md`: keep both sides' rows;<br>  - `FluidCheckpointCodec.VERSION`: 31 here, 35 there; take 36, since state rebuilds;<br>  - `VelocityClampTest`: the pump branch's side wins (a pump no longer refuses gas, its D19 and D20).<br>- The lang files and the other 18 shared files merge cleanly.<br>- Semantic checks to expect: the pump branch's tests written on the 21-entry world list (widths and indices, as in this branch's lane fixes), and its pinned hydraulics on crude, which O4 moves through friction (its pump laws read no viscosity).<br>- The version is proposed after the pump branch's bump. | owner, 2026-11-05 | |
| E20 | O4's four choices are accepted at least change ([CUT_VISCOSITY_REVIEW.md](CUT_VISCOSITY_REVIEW.md) section 4):<br>- The mixture term's participants are the method's `liquid_mixture.components` list, not a flag on each cut.<br>- The record revisions stay: the cuts `crude-regrouped-r1` (citation and descriptor removal only), the method `crude-pr78-r2` (the term carries its own revision).<br>- The formulation label `log-liquid-pair-groups-wilke-v2` stays: equal numbers at equal parameters.<br>- The sizing pins are re-pinned to the run's values; no bore, size, lamp or deciding limit moved. | orchestrator | yes |
