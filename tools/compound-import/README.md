# compound-import

Purpose: copy the ChemSep v8.3 pure-component data and the PR interaction files out of the local DWSIM install, and turn a selection of compounds into IChemE material records (`components`, `properties` with the `freezing` sub-record, a PR78 pair library, liquid volume reference points), with a cross-check against the batch data document and a regression check against the bundled light ends.

Batch: [2026-11-04-refinery-compounds](../../documentation/2026-11-04-refinery-compounds/README.md), plan package W5 (PACKAGE_REWORK_PLAN.md sections 3.5 to 3.7, decisions D3, D6, Q4).

Status: In progress since 2026-11-04. Index: [../INDEX.md](../INDEX.md).

The DWSIM files are data: the scripts parse them as text and run nothing from them. ChemSep is Copyright Harry Kooijman and Ross Taylor, Artistic License 2.0; every record the import writes credits its ChemSep record.

## How to run

From the repository root (Node 22; Windows PowerShell 5.1 for the extract):

```
powershell -NoProfile -ExecutionPolicy Bypass -File tools/compound-import/Extract-DwsimData.ps1 -OutDir tmp/dwsim-data
node tools/compound-import/import.mjs tmp/dwsim-data tools/compound-import/selection-tier1.json tmp/compound-import/tier1
node tools/compound-import/import.mjs tmp/dwsim-data tools/compound-import/selection-regression.json tmp/compound-import/regression
node tools/compound-import/regression.mjs tmp/dwsim-data tools/compound-import/selection-regression.json tmp/compound-import/regression src/main/resources/data/icheme/materials
node tools/compound-import/import.mjs tmp/dwsim-data tools/compound-import/selection-freezing-existing.json tmp/compound-import/existing-freezing --materials src/main/resources/data/icheme/materials
```

`import.mjs` options: `--data <md>` (the cross-check document; default the batch's REFINERY_COMPOUNDS_DATA.md), `--materials <dir>` (bundled records, for the crude-fraction molar masses and the crystal anchors).

Parser check (plain `javac`, no Gradle; reads `src/`, writes only `tmp/`): compile `parse-check/ParseCheck.java` with `-sourcepath src/main/java` and the Gson 2.10.1, EJML 0.44.0 and JetBrains annotations jars from the Gradle cache, then `java ParseCheck src/main/resources tmp/compound-import/tier1`. It parses the bundled resources plus the import output through `MaterialCatalog.parse`, builds a package (the network package extended by the imported compounds, `crude_pr78` with the imported pair library after `tjl20`), checks every written pair resolves to its value, and builds `HydrocarbonModel`, which requires a PR78 liquid root at every volume anchor.

## Entry points

| File | Role |
|---|---|
| `Extract-DwsimData.ps1` | Finds `DWSIM.Thermodynamics.dll` (default `C:\Program Files\DWSIM`, else `%LOCALAPPDATA%\DWSIM`), loads it with `Assembly.ReflectionOnlyLoadFrom`, copies the manifest resources `DWSIM.Thermodynamics.<file>` byte for byte (a plain file beside the assembly if no resource), writes `manifest.txt` with SHA-256 |
| `import.mjs` | The import; writes the records, `lang-en_us.json` (suggested English lines), `report.json`, and prints fit errors, pair sources and the cross-check table |
| `chemsep.mjs` | ChemSep XML reader and the ChemSep equations 1-4, 10, 16, 100-102, 105, 107 |
| `pairs.mjs` | `kij_pr.dat` (Portuguese labels mapped to CAS), `pr_ip.dat` (ChemSep index pairs), `pr.ipd` (2009 corrections, cross-check only), the D3 choice |
| `regression.mjs` | Compares the regression import with `properties/tjl19_*.json`, `interactions/tjl20.json` and the NIST points of `transport/liquid_calibration.json` |
| `selection-tier1.json` | The 24 Tier 1 compounds of REFINERY_COMPOUNDS_REVIEW.md P1 with their D6 ids, and the import form |
| `selection-regression.json` | The six bundled light ends in the bundled form |
| `selection-freezing-existing.json` | Freezing sub-records for the existing datasets |
| `pair-exceptions.json` | Owner exceptions to D3: compounds whose pairs are all zero, single-pair overrides, each with its reason |
| `parse-check/ParseCheck.java` | The parser check above |

## Conversions

- Ideal-gas Cp (eq. 16, 100 or 107; J/kmol/K to J/mol/K): unweighted least squares, `shifted_polynomial_5`, on every 25 K multiple of the fit range. The bundled light ends sampled 300, 325, ..., 900 K (`include_low_end: false` reproduces their coefficients to 1e-11). Tier 1 fits from max(ChemSep Cp data start, triple point) to 900 K, with the low end as the first point; that range is also the record's `fluid_domain` temperature range. Below it the network holds Cp with a notice: a data-range end, never a validity floor.
- Viscosity (eq. 101 liquid, eq. 102 vapour), at 100 kPa as the bundled tables: a log table by recursive bisection, an interval kept when log interpolation is within 0.05 % at its quarter and mid points (at most 2^8 intervals). This reproduces the 12 bundled DWSIM-sampled light-end tables node for node. Liquid: bundled form 0.5..0.95 Tc; Tier 1 max(data start, triple point)..0.95 Tc, and past the equation's data end ln(mu) linear in 1/T at its slope there (Andrade), marked estimated. Vapour: the fluid-domain start (or data start) to 900 K (or data end); the parser continues it past the ends.
- Standard liquid density: eq. 105 at 288.7056 K and 101325 Pa, as the bundled light ends (reproduced to 5e-13). A compound with Tc below 288.7 K takes its saturated liquid at Tr = 0.7 instead, with that temperature and pressure (nitrogen's precedent: its own liquid point).
- Liquid volume reference points: the saturated liquid at Tr = 0.7, molar volume from eq. 105 and pressure from eq. 101, for every imported compound; no compound takes a hypothetical 60 F liquid. `report.json` gives the PR78 liquid root and the shift at each point.
- `freezing`: ChemSep melting point, triple point temperature and pressure, heat of fusion at the melting point, its own revision. CO2 takes the `crystal_carbon_dioxide_i` anchor; water has none (its water model holds 273.16 K and its ice); crude fractions take the Riazi and Al-Sahhaf 1995 n-paraffin bound, estimated (Q4).
- Pairs (D3): every pair with at least one imported compound, against the imported compounds and the existing pure compounds (Water excluded: the water model). `kij_pr.dat` first; else the `pr_ip.dat` entry whose stated temperature range is nearest 300..600 K (gap, then overlap, then file order), entries with no stated temperature only when none states one; else no pair (the method's zero).
- Owner exceptions to D3 (2026-11-04), listed with their reasons in `pair-exceptions.json` and applied before the D3 order:
  - Every Hydrogen pair is zero: no pair is written, whatever `kij_pr.dat` or DECHEMA give. The DECHEMA H2 values are narrow-range fits (H2/Toluene -0.51, H2/N-butane -0.397, H2/Propane -0.131) that would put H2 into the oil; zero is DWSIM's own default without data, and the treatment H2 gets with the crude fractions.
  - Methane/HydrogenSulfide = 0.08125, ChemSep's 2009 `pr.ipd` fit of Reamer et al. data (R. Taylor). `kij_pr.dat`'s 0.1356 equals the DECHEMA Methane/SulfurDioxide value (`pr_ip.dat` line 115) and is taken as a transcription error.
- Constant overrides: a selection entry may replace ChemSep's Tc, Pc and acentric factor (`constants`, with its source) and omit fields; every Tc-dependent value (0.95 Tc, Tr = 0.7) follows. Carbonyl sulfide takes CoolProp 8.0.0's Tc 378.77 K, Pc 6.3688 MPa, omega 0.0978 (Lemmon and Span 2006, as listed in REFINERY_COMPOUNDS_DATA.md section 2; owner decision 2026-11-04): ChemSep's record gives Tc 375.15 K while its own eq. 101 and 105 run to 378.8 K. Its triple-point pressure is omitted: ChemSep's 101.325 Pa is 1.8 times its own eq. 101 at the triple point, and no local source gives CoolProp's.

## Found in the bundled records (left to the data thread)

- `properties/tjl19_isobutane.json` and `tjl19_isopentane.json`: the liquid viscosity tables run to 0.95 Tc, past the ChemSep eq. 101 data end (373 K, 360 K), as DWSIM extrapolated them, but carry `estimated: false`.

## History

- 2026-11-04: built. Extract verified: a fresh extract into `tmp/compound-import/extract-check/` is byte-identical (SHA-256) to `tmp/dwsim-data/` for all ten files. Regression against the six bundled light ends: Cp coefficients within 1.1e-11, viscosity nodes identical and values within 7e-15, standard densities within 5e-13, every other scalar identical, the 11 `tjl20` pairs reproduced by D3 (10 from `kij_pr.dat`, Isobutane/N-butane from DECHEMA); the only non-rounding difference is the `estimated` flag of the Isobutane and Isopentane liquid tables, which DWSIM extrapolated past the eq. 101 data end. Tier 1 written to `tmp/compound-import/tier1/` and checked through `MaterialCatalog.parse` and `HydrocarbonModel`.
- 2026-11-04: owner decisions applied. `pair-exceptions.json` added (no Hydrogen pair; Methane/HydrogenSulfide 0.08125 from `pr.ipd`); carbonyl sulfide on CoolProp's Tc, Pc and omega with its triple-point pressure omitted; the cross-check row follows the overridden constants. Tier 1 regenerated (pairs: 21 `kij_pr.dat`, 35 DECHEMA, 1 owner exception, 32 hydrogen pairs zero by rule, 403 without data); the regression is unchanged. The parse check could not compile this time: the working-tree `MaterialCatalog.java` was mid-edit in another thread (7 errors inside it); the record formats are unchanged since the passing run.
