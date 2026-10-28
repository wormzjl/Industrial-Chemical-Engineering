# Material data packs

Production material values are JSON resources. Java implements equations and validation, not component tables.
The active crude family has 12 pseudocomponents (`crude_pc01`–`crude_pc12`): 19 hydrocarbons with methane, 18 without, and 20 non-water species in the nitrogen network extension. CDU17 is a test-only reference. Exact chemicals and water retain their data. PR78 is the only equilibrium model that runs; the bundled pilot package adds reference spines, temperature-dependent E-PPR78 interactions, volume-translation anchors and a crystal. NRTL parameter records can be loaded and validated; selecting an NRTL package for a column calculation reports `Thermodynamic solver unavailable: nrtl`, and the fluid network accepts PR78 packages only.

| Bundled package | Components | Used by |
|---|---|---|
| `icheme:tjl20_methane` | basis `icheme:crude_19`: Methane, Ethane, Propane, Isobutane, N-butane, Isopentane, N-pentane, `crude_pc01`–`crude_pc12` | default column preset `tia_juana`, Tia Juana fluid preset, the neural model |
| `icheme:tjl19_dwsim` | basis `icheme:crude_18`: the same without Methane | hidden literature column preset |
| `icheme:tjl20_methane_nitrogen` | basis `icheme:network`: `crude_19` plus Nitrogen | the fluid network, column preset `tia_juana_nitrogen` |
| `icheme:<crude>_tjl20` for `wti_light_export`, `upper_zakum`, `bonga`, `dalia`, `cold_lake_blend` | basis `icheme:crude_19` | one column preset each; WTI and Cold Lake also fluid presets |
| `icheme:pilot_cryogenic` | Nitrogen, Methane, Ethane, CarbonDioxide | the fluid network, when selected (see "Selecting the pilot package") |

## Installing and editing

Copy `examples/material-override` into a world's `datapacks` directory, enable it with `/datapack enable`, and run
`/reload`. The example replaces the property record `icheme:tjl20_methane` to give methane an illustrative
standard-liquid density (357.0 instead of 356.07 kg/m³); it is an editing example, not a measured reference dataset.
Its other fields are the bundled record's (a test keeps them so), except its `revision`
(`tjl20-methane-nist-r1-example-density`): an edit declares itself with a bump of the edited record's revision. Methane
belongs to the network package, so the bumped revision changes the network's fluid identity: on a running world the
reload holds the fluid network until the data is restored or the world is reopened (see "Reloads"); a new world starts
on the edited record.

Records live under `data/<namespace>/materials/<kind>/<filename>.json`. Minecraft chooses the highest-priority
resource at each path. A replacement is a **whole record**, not a field merge. Different resource paths declaring
the same ID within one kind are errors. Assay IDs are unique within a package, so `icheme:tjl20_methane` and
`icheme:tjl20_methane_nitrogen` both declare `icheme:tia_juana_light_methane`.

Every record requires `schema_version: 1`. File/resource identifiers follow Minecraft's lowercase
resource-location rules. Component IDs are separate case-sensitive identifiers, up to 64 characters matching
`[A-Za-z][A-Za-z0-9_.:-]*`. Exact chemicals use plain names, for example `Ethane`, `N-butane`, `CarbonDioxide` and `Water`. All other record IDs are lowercase (`[a-z][a-z0-9_.:-]*`, at most 128 characters) and normally namespaced. Text fields are nonblank and at most 2048 characters.

## Record schema and units

The bundled files are complete examples of all 14 record kinds: `components`, `properties`, `interactions`, `group_interactions`, `spine`, `crystals`, `packages`, `bases`, `assays`, `water`, `solids`, `transport`, `presets` and `networks`; any other kind directory fails loading. Numeric data is finite and in SI units, except the group-interaction matrix, which keeps the published MPa. There are no user-defined executable expressions. A new equation form requires a registered Java implementation and corresponding validation; arbitrary equation-type strings fail loading.

### `components`

Required: `id`, `kind`, `translation_key`, `fallback`, `appearance` (see "Fluid color and transparency"). Kinds are `chemical`, `petroleum_fraction`, and `lump`.
For a chemical, the English fallback must exactly equal its internal ID. Ordinary lumps have independent
descriptive labels. Component identity does not imply that every source dataset has identical property estimates.

Petroleum fractions additionally require `cut`:

```json
{
  "estimated": true,
  "derivation": "adjacent_nbp_midpoints",
  "series": "tjl19",
  "lower_kelvin": 602.2990231445796,
  "upper_kelvin": 651.0249814474507
}
```

At least one bound is required. Omit the lower bound for the first open-ended cut, or the upper bound for the last.
For PC01–PC07, internal boundaries retain the original adjacent-NBP midpoints. PC08–PC12 use the estimated 377.8749814474507/450/550/650/750°C grid with an open upper tail, derived by constrained mass-CDF reconstruction. Exact light-end chemicals are not used as neighboring cuts. These are estimated
labels, not recovered assay boundaries. Sourced boundaries should use `estimated: false` and identify their source
in `derivation`. Bounds affect presentation only; they never replace the model's representative NBP.

### `properties`

Required identity/provenance fields: `id`, `component`, `revision`, `source`, `estimated_heavy_residue`.
Physical fields:

| Field | Unit |
|---|---|
| `molecular_weight_kg_per_mol` | kg/mol |
| `normal_boiling_point_kelvin` | K |
| `standard_liquid_density_kg_per_m3` | kg/m³ |
| `standard_temperature_kelvin` | K, density reference |
| `standard_pressure_pascal` | Pa, density reference |
| `temperature_min_kelvin`, `temperature_max_kelvin` | K, the record's own range (the column's) |

`ideal_gas_cp` is optional: a record without it is usable only in a package with reference spines, which refuses records that have one (one ideal-gas source per species, decision D6). It requires `type: "shifted_polynomial_5"`, `reference_kelvin: 298.15`, and exactly six
`coefficients` `[A,B,C,D,E,F]`. With ΔT = T − 298.15 K:

`Cp = A + B ΔT + C ΔT² + D ΔT³ + E ΔT⁴ + F ΔT⁵`, in J/(mol·K).

Coefficient n has units J/(mol·K^(n+1)). Ideal-gas enthalpy is its analytic integral from 298.15 K, in J/mol,
with zero enthalpy at that datum. Existing cubic fits retain their original evaluation order when E and F are zero.

An optional `temperature_min_kelvin` gives the fit's own lower end where it lies above the record's (it must lie above the record's `temperature_min_kelvin` and at most 298.15 K). On the fluid network the fit's data range is the component's `fluid_domain` temperature range (else the record's own), raised to this end; past it the heat capacity is held at the end value and integrated, so enthalpy and entropy stay continuous with their slopes. Nitrogen declares 273.16 K: its fit's value there, 29.111 J/(mol·K), is within 0.05 % of the NIST zero-pressure Cp down to its 63.151 K triple point (owner decision D37). The column evaluates the polynomial itself, inside its package's range.

`models` is required and may contain only `pr78`, with `critical_temperature_kelvin`, `critical_pressure_pascal`, and
dimensionless `acentric_factor`. A PR78 package requires those fields for each selected property dataset. NRTL is a mixture
activity model: its binary parameters belong in an interaction set, not in this pure-component record.

`volume_translation` (optional, needs `pr78`) gives `type: "saturated_liquid_reduced_temperature"`, `reduced_temperature` in (0, 1), `temperature_kelvin` (that fraction of the record's PR78 critical temperature), `pressure_pascal` and `molar_volume_m3_per_mol` of the reference saturated liquid there, and the texts `reference`, `oracle` and `source`. It sets a constant shift c = v_ref − v_PR,L(T, P); a record whose PR78 has no separate liquid root there fails loading. Only spine packages read it, ahead of the global calibration point (`transport`); the four `icheme:pilot_*` records carry one at Tr = 0.8.

Separate datasets may reference the same component. The bundled crude packages share their selected property records; the methane-free package omits Methane, the network package adds Nitrogen (`icheme:fluid_nitrogen`), and the pilot has its own `icheme:pilot_*` records.

`fluid_domain` (fluid network only) gives the component's data range on the network: `temperature_min_kelvin`, `temperature_max_kelvin`, `pressure_min_pascal`, `pressure_max_pascal` and `evidence`. It is separate from the record's own range, which is the column's, and the column's physics comparison (`samePhysics`) leaves it out; like every field of the record, an edit of it bumps the record's `revision`. In a package without spines its temperature range sets the `ideal_gas_cp` fit's data range (above); in a spine package the ideal-gas data range comes from the spine segments instead. Leaving it never refuses a state. The bundled crude and light records declare 293.15..900 K (the ambient continuation of their 298.15 K fits), nitrogen 63.151..900 K and the pilot records their triple point..1200 K.

### `interactions`

Required: `id`, `model`, `source`, `pairs`. Each pair names `first` and `second`. Self-pairs and duplicate unordered
pairs are errors. A PR78 pair supplies dimensionless `kij`; the evaluator uses the same value in both directions.

A `pr78` record may add `rule: {"type": "eppr78", "group_interactions": <record id>}`: each pair without an explicit pair then gets the E-PPR78 kij(T) (Jaubert et al. 2022) from that matrix and the group counts of the package's spines; an explicit pair keeps its constant kij. The rule needs a package `spine`. A group scheme other than the matrix's, an unknown group, a needed group pair missing from the matrix, or a term with A = 0 and B ≠ 0 fails loading. The bundled record `icheme:pilot_cryogenic` lists no pairs and resolves all six pilot pairs this way.

An NRTL pair supplies all of:

```json
{
  "first": "Ethane", "second": "Propane",
  "a12": 1.0, "b12_kelvin": 100.0,
  "a21": 2.0, "b21_kelvin": 200.0,
  "alpha": 0.3,
  "temperature_min_kelvin": 298.15,
  "temperature_max_kelvin": 900.0
}
```

These numbers are a manufactured schema example, not fitted chemical data. The convention is dimensionless
τ12 = a12 + b12/T and τ21 = a21 + b21/T, with T and b in kelvin. `alpha` is constant, dimensionless, symmetric,
and must be in [0,1]. Direction is determined by the declared `first` and `second`, never lexical sorting.
Package temperature limits must fit within every selected pair's validity interval. Missing coefficients are errors.

### `group_interactions`

The symmetric A_kl/B_kl matrix of a group-contribution rule. Required: `id`, `revision`, `source`, `provenance` (an object, not interpreted), `scheme`, `units` (exactly `MPa`, as published), `groups` (1–64 `{number, name}` numbered 1..n in order) and `pairs` (`{first, second, a_mpa, b_mpa}`, two different groups, each unordered pair at most once). Scheme `eppr78-2022` must list the 40 E-PPR78 groups by number and name. The bundled `icheme:eppr78_2022` is Table S4 of the supporting information of Jaubert et al. (2022).

### `spine`

One species' ideal gas and formation data; in a package that lists spines they are the only ideal-gas source (decision D6). Required: `id`, `component`, `revision` (a revision token, see "Reloads, scientific identity, and verification"), `source`, `molar_mass_kg_per_mol`, `formation_enthalpy` (`value_j_per_mol`, `uncertainty_j_per_mol`, `temperature_kelvin` = 298.15, `source`), `standard_entropy` (`value_j_per_mol_kelvin`, `pressure_pascal` = 100000, `source`, optional `uncertainty_j_per_mol_kelvin`), `ideal_gas`, `groups` (`scheme: "eppr78-2022"` and `counts`, a positive integer per group name) and `coverage` (`grade` `qualified`, `estimated_declared_error`, `research_only` or `unavailable`, and `evidence`).

`ideal_gas.segments` holds 1–16 contiguous segments that contain 298.15 K, each with `type`, `temperature_min_kelvin`, `temperature_max_kelvin`, `source`, `revision` and an optional own `coverage`: `helmholtz_ideal_terms` (CoolProp's ideal-gas Helmholtz terms: `reducing_temperature_kelvin`, `gas_constant_j_per_mol_kelvin` and 1–16 `terms` of type `log_tau`, `power`, `planck_einstein`, `planck_einstein_function_t` or `planck_einstein_cosh`), or the NASA polynomials `nasa9`/`nasa7` (nine or seven `coefficients`; the gas constant defaults to CEA's 8.31451 J/(mol·K), which a `cea-` revision must use). Every gas constant is the molar gas constant within 1e-5 relative. Enthalpy and entropy are integrated in closed form and chained so that h(298.15 K) is the formation enthalpy and s(298.15 K, 1e5 Pa) the standard entropy; a Cp step at a join above `ideal_gas.maximum_cp_step_fraction` (default 0.005) fails loading. Past the segments Cp is held and integrated. The four bundled spines belong to the pilot.

### `crystals`

One pure crystal of a component, anchored at its triple point to the fluid model of the package that uses it (g_s = μ_liquid and h_s = h_liquid − ΔH_fus there). Required: `id`, `component`, `crystal` (a description), `revision`, `source`, `coverage`, `anchor` (`type: "triple_point"`, `temperature_kelvin`, `pressure_pascal`, `fusion_enthalpy_j_per_mol`, `source`, optional `estimated`) and one model. Either `heat_capacity.segments` (1–16 contiguous segments, each with its range and `source`: a `polynomial`, Cp = Σ c_k T^k with 1–8 `coefficients`, or a `table` of 2–256 `temperatures_kelvin` and `values_j_per_mol_kelvin` interpolated linearly), `molar_volume` (`value_m3_per_mol` at `reference_temperature_kelvin` and `reference_pressure_pascal`, `thermal_expansion_per_kelvin`, `isothermal_compressibility_per_pascal`, a temperature and pressure range, `source`) and `transitions` (`temperature_kelvin`, `enthalpy_j_per_mol`, `volume_change_m3_per_mol`, `to_crystal`); or a `gibbs_function` of `type: "jaeger_span_2012"` (CO2-I, Jaeger and Span 2012), whose integration constants are solved from the anchor. A record that lacks the expansion, compressibility or volume range, or whose data do not reach its anchor, loads only as `research_only` or `unavailable` and refuses evaluation. Past the declared ranges Cp and the volume slopes are held. Bundled: `icheme:crystal_carbon_dioxide_i` (fusion enthalpy 9019 J/mol, admitted by the pilot) and `icheme:crystal_ice_ih` (fitted to IAPWS R10-06 over 10–273.16 K; the water model's ice). On the fluid network a crystal is a solid population of a vessel, exchanging mass with its component only at the vessel's refresh; a crystal and a `solids` record never share an ID (owner decision D23: crystals are solids).

### `packages`

Required: `id`, `revision` (a revision token), `model`, ordered `components`, parallel ordered `properties` references (or a `basis`, see "Shared bases"), `interactions`, `missing_interactions`, `water_model`, `aliases`, `advisory_evidence` (at most 32 entries of 256 characters), `temperature_min_kelvin`, `temperature_max_kelvin`, `pressure_min_pascal`, and `pressure_max_pascal`. There must be 1–64 unique components. Each property record must describe the component at the same index. Package ranges must fit selected property ranges; they are the column's domain and bound column preset operating values. Optional: `liquid_mixture`, `fluid_domain`, `spine`, `crystals`.

A package the fluid network is built on declares a `fluid_domain` (same fields): its envelope, the data range of the package as a whole and the input box of device settings. A generator's or void's temperature and pressure and a valve's set pressure are refused outside it when the player sets them; a state the network reaches by itself may leave it. No range has to lie inside another. The bundled packages declare 273.16..900 K and 100 Pa..10 MPa (`tjl19_dwsim` 2 MPa), the network package 63.151..900 K and the pilot 63.151..1200 K.

`spine` lists one `spine` record per component, in order; each must name that component and match the property's molecular weight within 1e-4 relative. The fluid network then subtracts each spine's formation enthalpy, so its enthalpies stay sensible (zero at 298.15 K). `crystals` lists the crystal records the package admits; it needs `spine`, and each crystal's component must be in the package. Spines and crystals are hashed into a separate `spineFingerprint` and never move the physics fingerprint. The V3 column refuses a package with spines (`IdealGasFitUnavailable`) or crystals (`CrystalPhasesUnavailable`).

`missing_interactions` is explicitly `zero` or `error`. The migrated PR packages use `zero`, preserving their original
assumptions; the pilot uses `error`, and pairs its `rule` resolves count as present. NRTL requires `error` and a complete pair set; absent pair data never means ideal behavior.
`aliases` maps historical component IDs to current IDs within this package. Multiple old spellings can refer to one
identity; an alias must target a package component and cannot remap a different current ID. The bundled packages declare none.

### `assays`

Required: `id`, `package`, ordered `components`, `basis`, parallel nonnegative `amounts` with a finite positive
sum, and `appearance`. Assay order must exactly match its package. `basis` is `mole`, `mass`, or `standard_liquid_volume`.

- Mole amounts are normalized directly.
- Mass amounts are divided by selected molecular weights, then normalized.
- Standard-liquid-volume amounts are multiplied by selected densities and divided by molecular weights, then normalized.

Volume assays also require `standard_temperature_kelvin` and `standard_pressure_pascal`, matching the selected
density reference conditions. Optional positive `volume_scale` and `amount_total` default to 1; volume is evaluated
as `volume_scale * amount / amount_total`. The regrouped Tia Juana assay conserves the original mass, standard liquid volume and ideal caloric moments; its pseudomole total changes. The methane assay retains the synthetic 0.5 mol% convention.

### `water`

The registered type is `iapws_shomate_watson`. Required: `id`, `revision`, `source`, `type`, `molar_mass` (kg/mol),
`triple_point`, `critical_temperature`, `max_enthalpy_temperature`, `reference_boiling_temperature` (K),
`critical_pressure` (Pa), `reference_vaporization_enthalpy` (J/mol), positive dimensionless `watson_exponent`,
six dimensionless `saturation_coefficients`, and seven `shomate_coefficients`. A `Water` component record must exist.

Saturation coefficient order corresponds to reduced-temperature exponents [1, 1.5, 3, 3.5, 4, 7.5] in the
Wagner–Pruss auxiliary equation. Shomate order is [A,B,C,D,E,F,−H] using t = T/1000: vapor enthalpy is
1000 × (At + Bt²/2 + Ct³/3 + Dt⁴/4 − E/t + F − H) J/mol. The final array entry already contains −H.
The existing NIST fit's lower-temperature continuation and reference-consistent Watson liquid enthalpy are preserved.

Optional `ice` names a usable `crystal` of `Water` anchored at the record's `triple_point` and gives its `sublimation_pressure` (`type: "iapws_r14_08"`, three `coefficients`, three `exponents`, `source`; IAPWS R14-08 equation 6). On every package, free water below its melting line freezes as that crystal. The ice crystal record's revision is part of the fluid identity only, not of the column's identity: the column never reaches the water's melting line. On the network the record also supplies the saturation pressure and the ideal steam; liquid water is IAPWS-IF97 Region 1 in Java. The bundled `icheme:water` uses `icheme:crystal_ice_ih`. The water model of the network package must carry `ice`: loading, and so a `/reload`, refuses data without it, naming the water file and the field.

### `solids`

Inert particle materials: `id` (namespaced, lowercase), `revision`, positive `density_kg_per_m3` and `heat_capacity_j_per_kg_kelvin`; a population stores c (T − 298.15 K) per kg. The bundled `icheme:demo_particle` is fictional (2500 kg/m³); while it exists the fluid menu offers a 10 % water slurry. A world whose saved solids differ from the loaded records is refused at start; a reload that changes them holds the fluid network until restored.

## Data ranges on the fluid network

Leaving a data range never holds an island (owner rule of 2026-10-16), and a freezing point is never a lower bound. Every property continues past its data, continuous at the edge: ideal-gas and crystal heat capacities are held at the end value and integrated, viscosity continues as described under "Viscosity", and outside the water's triple point..623.15 K free liquid water's Region 1 properties are held at the nearer end, volume and enthalpy continuing with the held slopes. After each committed interval the island's status line carries one advisory suffix, `; outside data range: <what> extrapolated below|above <bound> at <node>` and a count of further notices, for the package envelope, each carried component's ideal-gas and viscosity data, free water's and steam's ranges, and each crystal's record. Only numerical guards refuse a state, as rejected trials inside the solvers: the numerical box (10 to 6000 K, 1 Pa to 1 GPa) and states the equations cannot evaluate; holds remain for capability limits only (a phase set the network cannot carry, or no convergence in a declared critical band). The pilot carries CO2 below its 216.592 K triple point in a gas or a methane-rich liquid beside its CO2-I crystal.

## Viscosity

Property datasets and water-model records may contain an optional `viscosity` object, keyed by `liquid` and/or
`vapor`. Each entry is a pure-phase dynamic-viscosity correlation. Missing entries mean unavailable, never zero;
the catalog applies no viscosity mixing rule or crude-fraction estimation.

Required fields per entry are `type`, `temperature_min_kelvin`, `temperature_max_kelvin`, `pressure_min_pascal`,
`pressure_max_pascal`, `reference_temperature_kelvin`, `coefficients`, `revision`, `source`, and `estimated`.
Temperature limits are inclusive and increasing. Pressure limits are inclusive and may be equal for an isobar.
No correlation has a pressure correction; the pressure limits are data only.
The caller chooses the phase; the correlation does not perform a flash or decide phase stability.

Supported equations, using T in kelvin and dynamic viscosity μ in Pa·s:

| `type` | Phase | Equation and coefficient units |
|---|---|---|
| `andrade` | liquid | μ = A exp(B/T); coefficients [A (Pa·s), B (K)] |
| `sutherland` | vapor | μ = μref (T/Tref)^1.5 (Tref + S)/(T + S); [μref (Pa·s), S (K)], S ≥ 0 |
| `power_sum` | either | μ = Σ ai (T/Tref)^bi; positive `coefficients` ai in Pa·s and matching dimensionless `exponents` bi |
| `log_table` | either | Linear interpolation of ln(μ) in T between `temperatures_kelvin`; parallel `coefficients` are μ in Pa·s |

Andrade/Sutherland/table entries omit `exponents`; a Sutherland reference temperature lies inside its range. Power sums allow 1–16 terms. Tables allow 2–2048 strictly
increasing temperature nodes, spanning exactly the declared temperature interval; node values are returned exactly.
`reference_temperature_kelvin` is metadata only for tables. Coefficients, domains, sources, and
estimation flags remain in JSON. Fits are checked for finite positive scales and endpoint values at load time,
and every evaluation returns a finite positive value.

Past its declared interval a correlation continues (owner rule of 2026-10-16). A formula continues itself. A `log_table` liquid continues below its first node with ln(μ) linear in 1/T at the end segment's slope (value and slope continuous) and holds its last value above it; a `log_table` vapor continues at both ends with ln(μ) linear in ln(T) at the end segment's slope. Values are capped at 10¹² Pa·s; only a temperature that is not positive and finite is refused.

The fluid network mixes pure values at the reference pressure: logarithmic liquid mixing with the package's `liquid_mixture` correction, and Wilke vapor mixing. Above a liquid table it reads the component's vapor data at or above its PR78 critical temperature (before any dissolved-solute curve starts), else its conditional dissolved-solute curve (`transport`) once that starts, else the table's end value.

The bundled water model includes the [IAPWS SR6-08(2011) liquid-water correlation](https://iapws.org/technical-guidance/release/LiquidWater.download),
equation 7/table 5, with coefficients converted from μPa·s to Pa·s. Its data range is the 0.1 MPa reference isobar
and 253.15–383.15 K. This interval includes metastable liquid below freezing and above boiling; it is not a vapor
correlation. The verification tests use independent table 8 values at 260, 298.15, and 375 K.
The light-hydrocarbon records (`tjl19_*`, `tjl20_methane`) retain the installed DWSIM API curves and independent check points; nitrogen's tables come from the NIST WebBook and the pilot records' from CoolProp 8.0.0. The 12 active petroleum cuts use the shared Dalia liquid family described below. The five regrouped heavy vapor curves were re-evaluated through `AUX_VAPVISCi` with their reconstructed properties; all are estimated. The retired CDU17 data is available only to independent tests.

DWSIM liquid curves sample `AUX_LIQVISCi(name,T,100000 Pa)`; vapor curves sample the dilute-gas auxiliary
`AUX_VAPVISCi(component,T)`. Both declare the 0.1 MPa isobar in the catalog. These are conditional phase
properties, not phase-stability predictions; a specified liquid or vapor can be metastable at the query state.
Exact-chemical liquid sampling starts at max(fusion temperature + 1 K, 0.5 Tc).
Liquid upper bounds are min(900 K, 0.95 Tc), avoiding unphysical API extrapolation beyond the critical point.
The original gas sampling intersects the available API correlation interval with 298.15–900 K (the latter interval
is also used for petroleum gas estimates without database bounds). These are sampling limits, not independently
qualified physical validity ranges. Heavy-residue estimates retain the source characterization's limitations.

An additional DWSIM API export prepends 293.15–298.15 K to the applicable liquid and vapor tables,
preserving every original knot at/above 298.15 K. The fluid dissolved-solute reference factors have the same
ambient extension. Run `Export-DwsimViscosity.ps1 -Ambient -Characterization <source-feed.dwxml>` and the
`Export-DissolvedViscosity.cs` tool with `--ambient`, then `extend_ambient_viscosity.py` with the two reports
(`--dissolved-report` selects the second). Independent ambient checkpoints are included in tests.

Fluid generators preserve temperature/pressure when selecting a composition. The bundled crude and light records' fluid data start at 293.15 K (20°C), a five-kelvin continuation of their existing caloric fits; this is a near-ambient approximation, not a new laboratory qualification or a wax/gel model.
Changed property records retain their declared limits. The column property domains remain unchanged; the regrouped PR/Cp values are versioned, while fluid-model and transport revisions invalidate incompatible fluid caches. Ambient generation
is tested at 293.15, 298, 298.15 and 300 K; colder states are continued and reported, not refused.
A world saved under another fluid identity restates its contents once when it loads.

Adaptive subdivision checks quarter, midpoint, and three-quarter temperatures until log interpolation agrees with
the API within 0.05% at those check points. This measures interpolation error only, not physical model accuracy.
The independent API values and characterization metadata are committed in `src/test/resources/materials/dwsim-viscosity-api.json`.
The following regeneration tools and source reports are local research under the Git-ignored `research/` directory;
they are not required to build or test a clean checkout.
Reproduce with `research/viscosity-tools/Export-DwsimViscosity.ps1 -Characterization <original-source-feed.dwxml>`, then
`python research/viscosity-tools/import_dwsim_viscosity.py research/viscosity-tools/raw/dwsim-viscosity-export.json`. The original characterization file
is an offline input; neither it nor the DWSIM runtime is required by Minecraft. The exporter uses isolated in-memory
flowsheets and never saves the supplied source simulation.

Use `catalog.viscosity(packageId, componentId, Phase.LIQUID)` to retrieve an `Optional<ViscosityCorrelation>`;
package aliases and the package's selected water model are respected. `MaterialRuntime.current().viscosity(...)` uses
the calling calculation's pinned catalog. Call `dynamicViscosityPascalSeconds(T, phase)` to evaluate μ (the phase selects the continuation); a kinematic
viscosity ν = μ/ρ needs the density at the same state, never the standard-liquid density. For display, 1 mPa·s = 10⁻³ Pa·s and 1 cSt = 10⁻⁶ m²/s.

Viscosity is a transport property and is not consumed by the equilibrium/enthalpy solvers. Each viscosity declares its
own `revision`, and an edit of it bumps that revision, not the property record's. The fluid identity lists every
viscosity revision and the column identity none, so a viscosity-only edit keeps column results and neural eligibility
and holds a running fluid world until restored; reloads still validate and atomically publish the complete catalog.
Source-text-only edits need no bump. Pressure corrections and GUI viscosity reporting are not provided.

## Fluid color and transparency

Component and assay records require an `appearance` object:

```json
"appearance": {
  "color": "#70501D",
  "transparency": 0.12,
  "estimated": true
}
```

`color` is a six-digit RGB hex string (`#RRGGBB`); alpha is specified separately. `transparency` is a finite
dimensionless value from 0 (fully opaque) to 1 (fully transparent). All three fields are required; there is no default tint.
Whole-assay appearance is independent of component appearance; no mixture-color rule is assumed.

These are liquid visual settings stored for future use. The bundled colors/transparencies are an artistic,
estimated palette, not sourced optical measurements or temperature/path-length-dependent transmittance.
They do not currently affect in-game rendering, calculator UI, or multiplayer payloads. Appearance overrides
reload atomically with the database but do not invalidate thermodynamic results, neural seeds, or viscosity caches.

Use `catalog.componentAppearance(packageId, componentId)` (supports package aliases and Water) or
`catalog.assayAppearance(packageId, assayId)` for immutable values. Use the calling calculation's captured
catalog when snapshot consistency matters. `FluidAppearance.argb()` supplies a packed AARRGGBB value for future
consumers. Put an appearance object in a whole-record override using the usual data-pack replacement rules.
The five crude conversion presets retain their visual settings in `research/crude-assays/appearances.json`,
separate from scientific source transcriptions, so rerunning the converter preserves them.

## Localization and saved data

Put translations in resource packs at `assets/<namespace>/lang/<locale>.json`. Exact chemicals fall back to their
plain IDs. Petroleum labels compose the component's translated base name with these translatable templates:

- `material.icheme.nbp_range`: `%s, NBP %s–%s°C`
- `material.icheme.nbp_below`: `%s, NBP below %s°C`
- `material.icheme.nbp_above`: `%s, NBP above %s°C`
- `material.icheme.estimated`: ` (estimated)`

The first argument is the base name; remaining arguments are Celsius bounds displayed to one decimal place.
The server sends bounded naming descriptors, so clients do not need the server's scientific data pack.
Composition cells show localized labels; hovering a clipped label shows it in full, and the component search matches labels and internal IDs. Missing translations use
English fallbacks. Package and assay IDs remain stable. Retired saved axes are rejected. Current-format saved results remain presentation-only and require recalculation.
The column protocol is version 11 (wire schema 16), and the fluid protocol is `fluid-10`; client and server must run compatible mod versions.

## Reloads, scientific identity, and verification

One reload listener resolves and validates the entire winning resource set before atomically publishing it.
Invalid initial catalogs fail world loading; invalid subsequent reloads leave the last valid material snapshot intact.
Background requests capture a snapshot at admission. A scoped, finally-restored calculation context carries it through
legacy static PR/water entry points, including continuation and reporting. No running solve reads a replacement catalog.

Data are identified by declared revisions; nothing is hashed (owner decisions of 2026-10-26). A record's `revision`
names the version of its values: an edit of a value bumps the revision of the record that holds it, and a viscosity
edit bumps the viscosity's own `revision`. A package's `revision` changes only when the package record changes. A
revision is a token of letters, digits, `_`, `.` and `-` that starts with a letter or digit, at most 64 characters;
loading refuses another, naming the record's file. An edit without a bump is not detected: results stay current and a
running world does not hold. Assays, names, cut-label metadata, translations and source texts are in no identity.

A package's column identity, `Package.scientificRevision`, lists the declared revisions of the records the column reads:
`<package>@<revision>;properties=<id>@<revision>,…;interactions=<id>@<revision>[;group-interactions=<id>@<revision>];water=<id>@<revision>`.
The fluid identity, `catalog.fluidRevision(packageId)`, adds what only the fluid network reads:
`;viscosity=<id>/<phase>@<revision>,…` (in component order, then the water), `;ice=<crystal>@<revision>` and
`;fluid-data=<type>@<revision>,…`. Spine and crystal records are identified record by record in
`catalog.spineRevision(packageId)`. The calculator shows an identity's first part, `<package>@<revision>`.

Results and neural seeds carry the column identity. Completed results preserve their original identity and become stale
when it differs from the live package. Open calculators refresh after publication; closed ones check when reopened.
The bundled neural registry pins its reference package's column identity. A changed identity disables that model; the
normal LNN_FIRST mode uses numerical fallback (explicit LNN_ONLY retains its existing failure semantics).
A running fluid world keeps the catalog it started with: a reload that changes its network package's fluid or spine identity holds the fluid network with `HELD: property data changed; save and reopen the world to restate the fluid network on the new data`. Restoring the data resumes it; a reopened world restates its contents on the new data.

`MaterialCatalog.bundled()` uses the same parser with `materials-index.json` for standalone tools/tests. Add new bundled
files to that index; Minecraft itself discovers data-pack resources dynamically and does not use the index. Keep
catalog snapshots immutable and keep all numeric arrays and model workspaces local to their owner.

Run `gradlew.bat test fluidScienceTest fluidRuntimeTest` for numerical parity, codec, concurrency, neural, and solver regressions.
Run `gradlew.bat runFluidGameTestServer -PfluidGameTestRunId=<fresh-id>` for actual Minecraft fluid and reload tests, and
`gradlew.bat runColumnGameTestServer -PcolumnGameTestRunId=<fresh-id>` separately for calculator service tests
(`ColumnRegroupingGameTests`). The fluid run's reload test (`MaterialReloadGameTests.materialReloadIsAtomic`) applies a
higher-priority override, rejects an invalid replacement without publishing it, and restores the original selection. The former hardcoded package tables remain test-only regression references: TJL19 and TJL20-methane as records in `src/test/resources/column/v3/tjl-reference-resources.json`, and the CDU17 package `V3Cdu17TiaJuanaPackage`.

## Five additional crude feeds

WTI Light - Export, Upper Zakum, Bonga, Dalia, and Cold Lake Blend have separate packages and mass-basis assays
on the existing TJL20 component/property basis. See the local research report `research/crude-regrouping/notes/CRUDE_ASSAY_CONVERSION.md`
for mole/mass tables, source links, the reproducible converter, and limitations. These are approximate feed mappings;
they do not fit new crude-specific physical or viscosity properties. Each unmeasured tail above 590°C is marked
estimated and constrained by that crude's published residue mean boiling point. Whole-crude nitrogen, sulfur,
and metals remain provenance metadata, without contaminant speciation. The regrouped TJL feeds preserve physical feed volume; their pseudomole amounts change with characterization.

Each crude's catalog column preset uses the Tia Juana column's 40 stages, feed stage, temperature, pressure, steam, and pumparound duties, with its captured physical feed volume and the qualified draw settings: starting points, not optimized refinery operations. The column calculator does not list catalog column presets (the server preset packet was removed with wire schema 16; `default_column_preset` gives a new calculator its input). Its preset browser loads client JSON instead: column templates (operating settings and connections) and mixtures (a `package` and component amounts), bundled under `assets/icheme/columnpreset/` and `assets/icheme/mixturepreset/` and read from the client's `IChemE/columnpreset/` and `IChemE/mixturepreset/` folders. They are not material data: a mixture must name a package the server offers, and the server checks each calculation's input against its own package. Fluid generator menus list the catalog's fluid presets and the client mixtures whose components the network carries.

Additional transport properties, fitted NRTL datasets/calculations, and an in-game editor are outside this release.


## Regrouped crude transport and development saves

PC01–PC12 use one Dalia source-cut liquid-viscosity family over 293.15–900 K. The three 550°C+ cuts share the same unresolved pure-liquid curve. This is an estimated transport surrogate for every crude, not a claim that their measured viscosity profiles agree. Light-cut priors are estimated. Liquid tables outside measured temperatures are continuations, not phase-stability predictions. No wax, gel, elemental or reaction state is introduced for the cuts.

A property may carry `liquid_mixture_descriptor` with `participation` and `residue_retention` in [0,1], and a finite nonnegative `activation_slope`; a nonparticipating component has zero retention and slope. Absent descriptors mean zero participation, retention and slope. `activation_slope` is dimensionless: Δln(μ)/(1000 K × Δ(1/T)) between 313.15 and 323.15 K. Retention describes an ideal 370°C+ split. Nonparticipating gases and exact chemicals still dilute participating mole fractions.

Packages may select `liquid_mixture` with `type: symmetric_pair_groups_v1`, four finite `coefficients`, positive `reference_kelvin` and `slope_cap_kelvin >= reference_kelvin`; without one the coefficients are zero. For normalized amounts x, p=participation and h=retention, let a=xp and b=ah. The correction to Σx ln(μ) is:

```
F(a) = (Σa)² − Σa²
E(a) = (Σa)(Σa activation_slope) − Σ(a² activation_slope)
q = 1000 K × (1/min(T, slope_cap_kelvin) − 1/reference_kelvin)
Δln(μ) = c0(F(a)−F(b)) + c1 F(b) + q[c2(E(a)−E(b)) + c3 E(b)]
```

The crude components' fluid data start at 293.15 K (20°C). Colder states are continued from those ranges (heat capacity held, liquid viscosity continued in 1/T) and reported on the island's status line; nothing is clamped or held. Reaching a data boundary is not a viscosity or solidification criterion.

All pair terms vanish for a pure component. The implementation evaluates factored sums in O(component count). These coefficients and descriptors belong to the package and property records: an edit bumps the record's `revision`. The bundled coefficients are a global fit to Dalia whole crude and its 370°C+ residue, holding out whole crude at 40°C and residue at 60°C for verification. Other crudes' discrepancies are accepted and reported as a global approximation.

Column operating values reside in `presets` records. `operating.feedStandardVolumeCubicMetresPerSecond` is the positive feed volume at the selected property density reference; molar throughput is derived from the current assay and molecular weights. `operating.sideDraws` contains explicit tray numbers and mol/s rates. Captured pre-regrouping physical throughputs are retained, and gameplay draws are recalibrated against accepted pre-regrouping volume targets. The hidden literature preset retains its published molar rates. Cold Lake has no accepted old yield targets and retains its authored rates.

This development change breaks the old component basis. Production packages have no old pseudocomponent aliases. Saved column data uses version 12; a calculator whose saved data has another version or component axis discards it and starts from a fresh input. A fluid world saved under another checkpoint format or property basis is refused. Use a fresh development world; no migration is provided. Independent old numerical oracles exist only in test scope. Obsolete learned weights are retired; an unavailable qualified model leaves the classical initializer usable.


## Shared bases, sparse assays, and catalog presets

`bases` records contain ordered `components` and selected `properties`. Optional `extends` names one base whose entries precede the new entries. Missing references, cycles, duplicates, invalid unused bases and more than 64 non-water components fail validation. A package selects `basis` or supplies both inline lists; mixing these forms is rejected. Expansion happens before validation and publication. A higher-priority pack still replaces the whole record; extension is explicit composition, not field merging.

An assay can use `amounts_by_component` instead of positional `components`/`amounts`. Missing IDs become zero on the selected package axis. Unknown IDs, including unknown zero entries, fail. Sparse and positional forms cannot coexist. Amounts keep the assay's declared mole, mass or standard-liquid-volume basis and reference conditions. `verifyMaterialIndex` checks bundled index completeness and uniqueness during resource processing; datapacks remain dynamically discovered.

`presets` have `schema_version`, stable `id`, `kind` (`column` or `fluid`), and `label`. Column presets additionally declare `translation_key`, integer `order`, boolean `visible`, `package`, `assay`, and an `operating` object. See the bundled `column_tia_juana.json` for the complete SI fields: standard-volume feed rate, feed temperature, stage/feed-stage numbers, top pressure/drop, condenser temperature, reflux ratio, reboiler watts, side draws (mol/s), steam feeds (mol/s, K), pumparounds (watts and explicit split rule), and `columnDiameterMetres` (above 0, at most 15 m; the tray hydraulics give the pressure profile, the drop is the first solve's guess). Numerical definitions and stage geometry (2–64 stages, values inside the package ranges) are validated before publication. A catalog holds at most 31 column and 32 fluid presets.

Fluid presets select either a pure `component` (a network component or `Water`) or a `package` and `assay`. At most one `networks` record selects the global `package`, `default_column_preset` (a visible column preset) and ordered `fluid_presets`; gameplay needs it, pure column-only standalone catalogs do not. The network package must be PR78 with Nitrogen and separate Water, nitrogen viscosity for both phases and a `fluid_domain`; its water model needs `ice` (refused at loading). Package-based fluid presets are projected by exact ID; their selected physical properties, interactions, water model, package ranges, component `fluid_domain` and viscosity/correction data must agree with the corresponding network sub-basis. The menu receives each as an ID, a label and mole fractions. Reordering or extending a network is a breaking development-world change, not a save migration. The fluid checkpoint (format 12, `FluidCheckpointCodec.VERSION`) stores the topology with the network's package, component IDs and qualified fluid fingerprint even with no active hydraulic islands; a restart rejects a changed contract. Running ledgers retain their captured axis through reload. The bundled fluid menu exposes Water, Nitrogen, Tia Juana, WTI and Cold Lake, plus a 10 % water slurry of `icheme:demo_particle`.

### Selecting the pilot package

`icheme:pilot_cryogenic` runs the fluid network on its reference spines, E-PPR78 kij(T) for all six pairs, Tr = 0.8 volume anchors and the CO2-I crystal, in a 63.151–1200 K, 100 Pa–10 MPa envelope. A datapack selects it by replacing `data/icheme/materials/networks/default.json` (a second network record fails loading). Crude presets cannot share its physics, so its menu lists component presets only, each a `presets` record of kind `fluid`. The tests' override (`src/test/resources/materials/pilot-cryogenic/`) is:

```json
{
  "schema_version": 1,
  "id": "icheme:default",
  "package": "icheme:pilot_cryogenic",
  "default_column_preset": "tia_juana",
  "fluid_presets": ["water", "nitrogen", "methane", "ethane", "carbon_dioxide"]
}
```

with presets such as `{"schema_version": 1, "id": "carbon_dioxide", "kind": "fluid", "label": "Carbon dioxide", "component": "CarbonDioxide"}`. It changes the world's fluid basis: use a fresh world. The column refuses the pilot; `default_column_preset` still names a column package's preset.

## Reloadable fluid reference records

`transport` records have identity, revision and source. `type: liquid_volume_reference_points` contains `points` with `component`, `temperatureKelvin`, `pressurePascal`, `molarVolumeCubicMetres` (m³/mol), and source. These are EOS volume-calibration references; a component with neither a point nor (in a spine package) a `volume_translation` is anchored at its standard density. `type: conditional_solute_log_tables` contains `reference_pressure_pascal` and component `curves` with increasing `temperatures_kelvin` and positive `viscosities_pascal_seconds`. Tables use the registered log-viscosity interpolation implementation. They describe the existing conditional dissolved-gas contribution, not a stable pure liquid above its critical temperature.

A fluid model captures these immutable records with its catalog. Volume references contribute to the fluid thermodynamic identity; conditional curves, molecular weights, pure curves and mixture corrections contribute to transport identity. Editing transport does not invalidate column PR/caloric seeds. No hardcoded classpath-only calibration or dissolved-viscosity loader remains.

## Bundled neural registry

`data/icheme/neural/registry.json` is a bounded classpath registry, not a datapack weight loader. Schema 1 permits up to 16 entries with `priority`, `modelId`, `payload`, `sidecar` and `pipelineSha256`. Highest priority wins; equal priorities are resolved by model ID. Duplicate IDs, unsafe resource paths, wrong hashes, malformed shapes or unsupported policies reject the registry. An empty or unavailable registry preserves classical fallback.

The trained payload uses schema 1 and contains immutable weights, normalization, component axis and encoding/anchor revisions. Sidecar schema 2 contains `propertyRevision` (the reference package's column identity), reference package, model ID, formulation/branch coverage, composition blend segments, numerical bounds, decoder/candidate/correction policy, and explicit `allowExtraZeroComponents` / `allowMissingZeroComponents` flags. The pipeline hash is SHA-256 of the ASCII string `payloadSha256:sidecarSha256`. Altering metadata does not rewrite weights, but requires whole-pipeline requalification.

Physics eligibility is the reference package's current column identity equal to the sidecar's `propertyRevision`, and the requested package's values on the model's axis equal to the reference's (`samePhysics`, a direct comparison: ordered component properties, EOS/interactions and water/caloric data; not assays, labels, provenance text, transport or declared revisions). Actual feed composition must still belong to the model's qualified composition domain. Every nonzero feed species must be represented. Reordering is by exact ID; discarding extra zero species and filling missing zero species require the respective explicit qualification flag. A missing species draws properties and cross-interactions only from the named reference package, and the complete projected physics must match (`samePhysics`). No missing interaction is inferred as zero. A package with crystals or rule interactions is never projected. The deployed regrouped model keeps both padding flags disabled; methane-free and nitrogen-extended requests use classical initialization.

Selection binds one model and one immutable catalog when a job is admitted. Native anchors run against that captured view, predictions scatter back to the request axis, and returned seeds retain the original request identity. Reloads cannot change running jobs. This projection is only an inference operation, never an old-save conversion. Result freshness remains tied to the original full scientific dataset.

## Adding a compound

1. Add a component ID with localized descriptor and appearance, sourced or explicitly estimated physical/PR data, an `ideal_gas_cp` fit or (in a spine package) a `spine` record, supported transport curves and reference conditions. New correlations require tested Java implementations; no executable expressions are accepted.
2. Add it to a shared basis and qualify all selected interaction and water treatment. Explicitly update sparse assays, operating presets, network selection and conditional/calibration records where scientifically applicable. Unknown sparse IDs and incompatible preset/network physics must fail validation.
3. Update the bundled index and run catalog/property/flash/derivative, current-format serialization, packet, column and fluid tests. Axes are bounded at 64 non-water species plus water, and packet validation uses the server-provided axis rather than the client's local catalog. A structural fixture adds a synthetic nitrogen-like component and moves nitrogen without array-width edits; it does not establish experimental properties for a new real substance.
4. Qualify native column/pipeline behavior and fresh-world reload behavior. Old worlds are intentionally unsupported after an axis/property change; a fresh world is required.
5. Existing models remain eligible only for their exact qualified physics and composition domain. Train additional specialists from qualified labels, generate independent parity fixtures, run native selection/holdout gates, then add a registry entry with explicit priority and hashes. Never expand a sidecar's eligibility merely to bypass missing scientific qualification.

Elemental/SARA/reactivity/wax append data, reaction models, crude-specific rheology, and NRTL calculations remain deferred.
