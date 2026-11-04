# crude-grades

**Purpose:** these scripts generate the open crude data of work packages O3 and O4:
- the four generated crude grades, as assay records and client-preset drafts;
- the Twu 1985 liquid-viscosity tables of the twelve shared cuts;
- the one-term liquid-mixture pair coefficient G, refitted to ECCC whole-crude viscosities.

**Batch:** [2026-11-04-open-thermo-data](../../documentation/2026-11-04-open-thermo-data/README.md); see the decisions E10 to E14 in `OPEN_DATA_PLAN.md` and section 6 of `CRUDE_GRADES_REVIEW.md`.

**Status:** generator only. The scripts write nothing under `src/`; the application thread copies the outputs into the material records.

## How to run

The scripts need Node 18 or later and no packages. Run them from the repository root, in this order:

```
node tools/crude-grades/cut-tables.mjs   # 1  cut-viscosity/crude_pcNN.viscosity.liquid.json, summary.json
node tools/crude-grades/fit-g.mjs [csv]  # 2  g-fit.json, g-fit.md
node tools/crude-grades/grades.mjs       # 3  assays/<id>.json, grades.json
node tools/crude-grades/method.mjs       # 4  crude_pr78.liquid_mixture.json (needs 2)
node tools/crude-grades/presets.mjs      # 5  presets/mixturepreset, presets/columnpreset, presets/README.json (needs 3)
node tools/crude-grades/validate.mjs     # 6  validation.md, validation.json (needs 2, 3)
```

**Inputs:**
- The bundled material records under `src/main/resources/data/icheme/materials/`, read only:
  - `properties/crude_pc*.json` and the seven light-end records;
  - `methods/crude_pr78.json`.
- The tia_juana client presets under `src/main/resources/assets/icheme/`.
- The ECCC CSV. The default path is the main checkout's `research/2026-11-04-open-thermo-data/eccc-oil-database/`. Override it with the variable `ECCC_CSV` or the first argument of `fit-g.mjs`.

**Output:** `tmp/crude-grades/out/`, or the folder in the variable `CRUDE_GRADES_OUT`. JSON files use 2-space indentation and CRLF line ends, the same as the working-tree copies of the bundled files.

**The CSV is untrusted data.** The script decodes it as ISO-8859-1 and splits it into fields; no field is ever evaluated. Its source and licence: Environment and Climate Change Canada, *Environment Canada Crude Oil and Petroleum Product Database*, 2021-01-22. Contains information licensed under the Open Government Licence - Canada.

## Entry points and library

| File | Role |
|---|---|
| `cut-tables.mjs` | Builds the Twu 1985 + ASTM D341 kinematic viscosity, times the Rackett density anchored at the standard density, capped at 1e12 Pa s. The table has 608 nodes from 293.15 to 900 K, on the template grid of `crude_pc05.json`. Revision `twu-1985-d341-r1`. |
| `fit-g.mjs` | Fits G by least squares in ln mu to the ECCC crude set, with exclusion rules R1 to R5. |
| `grades.mjs` | Builds the light, medium, heavy and oil-sand dilbit grades. Each is a Riazi 1989 TBP distribution on the shared cuts, shape first. Revision `generated-r1`. |
| `method.mjs` | Writes the proposed `liquid_mixture` block (`cut_pair_v1`, revision `eccc-cut-pair-r1`) and its exact equivalent for the current Java. |
| `presets.mjs` | Writes the mixture and column preset drafts. These need a solver check. |
| `validate.mjs` | Writes the per-grade validation against the ECCC references, and each cut table's ratio to the Dalia table. |
| `lib/materials.mjs` | Loads the records and evaluates the game's `log_table` and its liquid-source rule. |
| `lib/correlations.mjs` | API gravity, Twu 1985, ASTM D341 and the Rackett density. |
| `lib/grades.mjs` | The Riazi distribution, the cut bins, the light-end split, the yields and the solvers. |
| `lib/mixture.mjs` | The mixture rule: ln mu = sum x ln mu_i + G (x_cut^2 - sum x_i^2). |
| `lib/eccc.mjs` | The CSV reader. |
| `lib/out.mjs` | The JSON and text writers. |

## History

- 2026-11-04: Promoted from the research scripts in `tmp/crude-grades/` (CRUDE_GRADES_REVIEW sections 1 to 5), after the owner's decisions E10 to E14. In the same change:
  - the density rule for nu to mu moved from the API MPMS 11.1 expansion to the Rackett form, which holds up to 900 K;
  - G was refitted on the full ECCC crude set.
