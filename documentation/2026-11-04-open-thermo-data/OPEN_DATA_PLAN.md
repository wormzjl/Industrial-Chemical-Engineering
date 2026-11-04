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
- **The column's neural model.** It binds only to feeds on its training blend segments, which are all Tia Juana. It also needs the trained records' exact revisions: methane `tjl20-methane-nist-r1` and water `water-iapws-shomate-r2`. After O2 or O3 it fits no game feed, and the column falls back to its classical initializer.

## 3. Work packages

| WP | Content | Physics change |
|---|---|---|
| O1 | Move the raw NIST copies in `tools/` to the main checkout's `research/`. Leave links in place. | no |
| O2 | Re-derive the NIST values from CoolProp (MIT), NASA CEA (Apache-2.0) and IAPWS-95. Bump each changed record's revision. Measure how far each value moves, the restates, the affected gates and the column on its classical initializer. | yes |
| O3 | Four generated grades on the shared cuts: light, medium, heavy and oil sand. They replace the seven assays in the fluid presets, the column presets, the network defaults and the example sheet, with en_us names. Each removed fluid preset id gets an alias to its replacement (saved device settings are facts). | yes |
| O4 | Re-source the liquid viscosity of the cuts and the mixture correction from a non-ExxonMobil source (research R1, then an owner choice). | yes |
| O5 | Remove unused data after an inventory (research R2): the ExxonMobil records and every copy of them, data that only tests read (to `src/test`), and duplicate copies such as `tools/wp5-legacy-digest`. | no, unless the inventory finds otherwise |
| O6 | One final lane for the batch, then the report. No merge preparation until the owner asks. | none |

## 4. Decisions

| # | Decision | By | Reversible |
|---|---|---|---|
| E1 | Replace the ExxonMobil assays and the Tia Juana assay with generated light, medium, heavy and oil-sand grades. Take the viscosity from another source (D-A). | owner, 2026-11-04 | |
| E2 | NIST data: D-B option 2, then see how it performs. | owner, 2026-11-04 | |
| E3 | Remove unused data. | owner, 2026-11-04 | |
| E4 | A batch of its own, on the same branch as the refinery-compounds batch. | orchestrator | yes |
| E5 | The shared cuts `crude_pc01` to `crude_pc12` stay. Their properties come from the project's DWSIM characterisation, and their records gain the missing citation. The grades are compositions on these cuts, as the ExxonMobil assays were. | orchestrator | yes |
| E6 | O2 leaves the neural sidecar alone (the strict physics contract). The model then drops out and its fallback is measured. The owner decides on the model after O3: remove it, keep it for retraining, or retrain it. | orchestrator; the model is the owner's choice | yes |
