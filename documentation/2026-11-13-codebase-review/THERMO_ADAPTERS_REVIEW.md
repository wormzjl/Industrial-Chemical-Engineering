# Thermo adapters, water, material: review (S2)

Base `5f61d3139`, 2026-11-13. Read closely: `science/fluid/thermo` (13 files, 3 503 lines), `science/column/v3/thermo` (18, 2 119), `science/material` (24, 5 599; `ModifiedUnifac`, `JaegerSpanGibbsFunction`, `GroupContributionInteractions`, `GroupInteractionMatrix`, `Eppr78Groups`, `SolidFuelCatalog`, `MaterialName`, `MaterialCoverage` by signatures and greps only), `science/math` (3, 677). Read outside scope for the water item: `thermo/phase/WaterShare` and `SteamVirial` whole; `PhaseContract` 120-130, `PengRobinsonKernel` 2011-2070, `V3ColumnProblemResolver` 7-28 and 163, `V3InputDigest` 55-94, `MaterialRuntime`, `water.json`, `cubic_water.json`. Not read: the kernel body, the engine, `V3MeshResidualEvaluator`, `V3ColumnCalculator`, `PassiveStepSolver`, `PhaseLayout` (callers cited by grep line only). No test or Gradle run.

## 1. Summary

The column adapter (`V3PengRobinsonThermo`/`V3PengRobinsonSession`) evaluates the derived PR78 Huron-Vidal kernel untranslated, adds the `shifted_polynomial_5` ideal gas and flashes with its own Wilson/successive-substitution flash; water is outside the kernel (free water and steam, `V3WaterProperties`) or, in a second mode, on the axis (`V3ColumnProblemResolver.waterOnAxis`). The network adapter (`FluidThermodynamics`/`HydrocarbonModel`/`NetworkPhaseEngine`) evaluates the same derived kernel with a Peneloux translation, water in the cubic plus `WaterShare`, and the engine decides phases. `science/material` parses, validates and identifies all data and derives the pairs at load.

Five largest problems:
1. **Water is written four times.** Wagner-Pruss in `V3WaterProperties` 59-98, `WaterShare` 67/357-376 (hard-coded constants), `TrainedPhysics.Water` 81-86, plus the cubic's own curve `WaterSaturation`; IF97 Region 1 in `WaterRegion1` 23-35/86-119 and again in `WaterShare` 48-61/244-296; Shomate and Watson twice. The column-fluid cycle (REVIEW_PLAN 1.2) is these copies plus five calls.
2. **The column's identity does not name the pairs it evaluates** (F1, DEFECT). Since W5 D1 the column kernel is `DerivedPairs` (`V3PengRobinsonSession` 37-43), but `datasetRevision` is `scientificRevision` alone (`V3CatalogPropertyPackage` 44), which leaves out `DerivedPairs.REVISION` and its record tokens (group counts, UNIFAC tables, Henry, homomorphs). The network's identity has them (`PhaseContract` 127). A derivation change moves column values and keeps its cached results and warm seeds current (`V3ColumnCalculator` 359, 428).
3. **`FluidThermodynamics` (1 556 lines) is seven modules**: the property port, the phase-trace and phase-check rules (835-1151, which call `fluid.solver.PhaseLayout` and `SparseNewton`: a thermo to solver cycle), stream properties (470-643), utility water (644-696), a facts reader (698-722), typed holds (1346-1422), the `State` record (1470-1556).
4. **Inversions**: material imports column (`MaterialPresets` 4, `MaterialCatalog` 778, `HenryConstant` 4) and `fluid.state` (`SolidMaterialCatalog` 4); `fluid/thermo` imports column five times (`FluidThermodynamics` 144, 660, 661, 674, 693) and the column imports `fluid/thermo` (`V3WaterProperties` 3). `SpinePackageData` 3 (`fluid.state.EnergyReference`) and `TranslatedPengRobinson` 5 (`fluid.diagnostics`) block their move into the thermo core that S1 needs.
5. **The column port is not flat and copies per call.** `fugacityDerivatives` copies the n x n `dLogPhiDn` twice (kernel 2060-2063, `V3FugacityDerivatives` 47) and checks n^2 entries; `fugacity` clones ln phi twice. The API is typed on `Root.LIQUID`/`Root.VAPOR` (`V3ThermoModel` 12-21).

Ten highest-value changes, gain over cost:
1. F1: column identity = `scientificRevision` + derivation identity (3 lines; one restatement of column state).
2. F2/F6: one water module in the thermo core; the column-fluid cycle and about 110 duplicate lines go.
3. F4/F5/F7: material free of solver types (presets as plain records, preset validation in the plant layer, inventory validation in `fluid/state`).
4. F10/F11: column phase calls write into caller-owned flat arrays; the Rust JNI boundary is the same layout.
5. F8: split `FluidThermodynamics`; the phase rules move to `fluid/solver`, streams to `fluid/transport`.
6. F9: move `EnergyReference`'s formation datum and a diagnostics hook down, so S1 can move `CoefficientIdealGas`, `TranslatedPengRobinson`, `SpinePackageData`, `FluidDomain` into the thermo core.
7. F3: column steam and Wagner-Pruss continue past their data like the network's (rule fix).
8. F16/F17/F18/F25/F28: delete dead and duplicate code (about 130 lines).
9. F12: hoist the water constants out of the column residual path.
10. F14 (PHYSICS, owner): the column's feed flash on the engine's TP; `V3FeedFlash` and the reduced pass of `V3TruncatedFlash` go.

## 2. Module map

### 2.1 Modules I would draw

| Module | Files now | Lines | Goes to |
|---|---|---|---|
| math | `DeterministicMath`, `DeterministicPow`, `FdlibmPow` | 677 | stays; `strictPow` goes (F25) |
| water (new, thermo core) | `WaterRegion1`, `V3WaterProperties` (formulas), `SteamVirial`, `WaterShare` constants, `WaterSaturation` guess | ~650 moved | `science/thermo/water` |
| cubic adapter (thermo core) | `TranslatedPengRobinson`, `CoefficientIdealGas`, `SpinePackageData`, `FluidDomain`, `HydrocarbonModel` 381-435 | ~1 000 | `science/thermo` (S1) |
| network port | `HydrocarbonModel` 1-375, `NetworkPhaseEngine`, `NetworkCrystals`, `ThermoHold`, `DataRangeNotices`, `FluidThermodynamics` 1-433, 723-833, 1153-1344, 1425-1556 | ~1 600 | `fluid/thermo` |
| network phase rules | `FluidThermodynamics` 835-1151 | ~320 | `fluid/solver` |
| network streams | `FluidThermodynamics` 433-643 | ~210 | `fluid/transport` |
| facts reader | `FluidThermodynamics` 698-722 (`LegacySplit`) | 25 | `fluid/state` |
| column port | `V3PengRobinsonThermo`, `V3PengRobinsonSession`, `V3ThermoModel`, `V3ThermoDerivatives`, `V3ThermoWorkspace`, `V3Fugacity*`, `V3PropertyPackage`, `V3CatalogPropertyPackage`, `V3PropertyComponent`, `V3ThermoException` | ~1 120 | `column/v3/thermo` |
| column flash | `V3FeedFlash`, `V3TruncatedFlash`, `V3FlashResult`, `V3FlashTruncationEvidence`, `V3FeedPhase`, `V3CrudeFeed` | ~800 | shrinks under F14/F15 |
| material records | `MaterialCatalog`, `ReferenceSpine`, `CrystalReference`, `JaegerSpanGibbsFunction`, matrices, `ViscosityCorrelation`, `LiquidMixtureCorrection`, `VolumeTranslation`, `HenryConstant`, `JsonFields`, `SolidMaterialCatalog`, `SolidFuelCatalog`, names, coverage | ~4 000 | `science/material` |
| material derivation | `DerivedPairs`, `ModifiedUnifac` | ~1 160 | `science/material` |
| material runtime, lens, presets | `MaterialRuntime`, `MaterialAxis`, `TrainedPhysics`, `MaterialPresets` | ~430 | presets lose column types (F4) |

### 2.2 Imports that cross the wrong way

- material to column: `MaterialPresets` 4 (`column.v3.*`: `V3ColumnInput`, specs), `MaterialCatalog` 778 (`V3ColumnProblemResolver.validateInput`), `HenryConstant` 4/42 (`V3WaterProperties.wagnerPruss`). `MaterialCatalog` 178 names `V3WaterProperties` in a comment only.
- material to fluid: `SolidMaterialCatalog` 4/22-25 (`SolidInventory`).
- fluid/thermo to column: `FluidThermodynamics` 144, 660, 661, 674, 693.
- column to fluid/thermo and engine: `V3WaterProperties` 3 (`WaterRegion1`), 7 (`thermo.phase.SteamVirial`).
- fluid/thermo to fluid/solver: `FluidThermodynamics` 5-6 (`junctionSplit` 1104-1127 runs `PhaseLayout` and `SparseNewton`), `NetworkCrystals` 3 (`PhaseLayout.AMBIENT_PRESSURE`).
- would-be thermo core to fluid: `SpinePackageData` 3, `TranslatedPengRobinson` 5, `FluidThermodynamics` 7/56 (`MixtureViscosity`, `SlurryTransport`) and 20-21.

### 2.3 The two adapters, property by property (brief 1)

| Property | Column | Network | Same? Difference from the formulas |
|---|---|---|---|
| ln phi, K (no water) | derived kernel, untranslated (`V3PengRobinsonSession` 150) | same kernel + `P c_i / RT` (`TranslatedPengRobinson` 478) | K equal: `c_i` cancels between phases; ln K differs at rounding. Exceptions: a lens package (column uses the authored matrix, `V3CatalogPropertyPackage` 40) and wet streams (below). |
| enthalpy | `sum x h_ig + h^R` (`V3PengRobinsonThermo` 245-250) | `+ P sum x c_i` (466) and `WaterShare` for water (`HydrocarbonModel` 164-168) | `P sum x c`: with `|c| <= 5e-5 m3/mol` (assumed bound for heavy cuts; to measure) at most 15 J/mol at 3 bar, 0.05 % of a 30 kJ/mol latent heat; 500 J/mol at 100 bar. |
| ideal gas | power form (`V3PropertyComponent` 52-59), Cp held outside the package admission range `[max dataset min, min dataset max]` (`V3PengRobinsonSession` 205-207, `MaterialCatalog` 1234) | Horner (`CoefficientIdealGas` 82-86), held outside each component's `fitRange` (49-54) | Inside both ranges last bits. Outside: `integral (Cp_fit - Cp_edge) dT ~ 0.5 (dCp/dT) dT^2`; 50 K past the edge with dCp/dT 0.8 J/(mol K^2) (a C20 cut, assumed) gives about 1 kJ/mol, 0.3 % of h. Spine packages: column refuses (`V3CatalogPropertyPackage` 30). |
| heat capacity | `cp_ig + dh^R/dT` (`V3PengRobinsonThermo` 159-160) | closed form (`TranslatedPengRobinson` 469, 304) | same quantity; rounding. |
| volume | none from the EOS; hydraulics use Rackett and Kay's rule (`V3PengRobinsonThermo` 61-67) | translated PR + water share | not comparable from formulas; to measure on the preset liquids. |
| derivatives | kernel `evaluateDerivatives` (142-162) | `HydrocarbonModel.tangent` 184-228 on the same kernel call | same kernel; different packaging and translation terms. |
| flash | `V3FeedFlash`: Wilson endpoint classification, damped SS (0.5), RR bisection, no stability test | engine TP with stability (`NetworkPhaseEngine` 78-80) | can differ near bubble and dew points (F14). |
| water | free water: Wagner-Pruss, Shomate steam + `SteamVirial` at `p_w`, IF97 Region 1 liquid; or water on axis (cubic) | cubic water with Mathias-Copeman alpha, `WaterShare` for h, s, v | see 2.4 |

### 2.4 Water (brief 2)

Values now, from the formulas:
- Saturation: column Wagner-Pruss from the record (`V3WaterProperties` 59-70); wet network the cubic's own curve, up to 0.43 K off Wagner-Pruss between 100 and 220 bar (`FluidThermodynamics` 666-667); dry network Wagner-Pruss continued (674). At column pressures the cubic-WP gap is not stated anywhere I read; to measure.
- Steam ideal gas: column Shomate; network the `cubic_water` polynomial, a fit of that Shomate within 0.016 % (`cubic_water.json` source): at most about 2 J/mol over 300 K.
- Pure steam residual: column `p_w (B - T B')` of IF97; network the cubic's `h^R` with its second virial replaced by IF97's (`WaterShare` 135-149). The cubic's higher terms remain: order 10 J/mol at 10 bar (from the 1 % volume gap at 10 bar, `SteamVirial` 14-15; estimate).
- Steam in hydrocarbon gas: column Dalton, no cross virial (`V3WaterProperties` 184-187); network the cubic's cross terms. `2 y_w y_h P D_wh` with `D_wh ~ -1e-3 m3/mol` (assumed), half steam at 2.5 bar: about -125 J/mol, 0.3 % of a latent heat.
- Liquid water: column `h_R1 + offset` (225-229, 247-254); network `h_R1 - h_R2,ig + h_fit + P c_w` through the liquid gap (`WaterShare` 213). Equal at 298.15 K by the datum; apart by `integral (cp_fit - cp_R2,ig) dT`, about 6 J/mol over 300 K (0.054 % + 0.016 % of 34 J/(mol K)). Negligible.

The module, `science/thermo/water`, built from a `WaterConstants` value (doubles from `MaterialCatalog.Water`, so it imports no material type): `WagnerPruss` (p_sat, d ln p_sat/dT, T_sat, continued below the triple point), `Iapws97Region1` (one table and the superset evaluator of `WaterShare.region1`; the held continuation of `WaterRegion1` as one function), `SteamVirial` (moved), `SteamIdealGas` (Shomate, Cp held past its range as `FluidThermodynamics` 657-662), `WaterDatum.liquidEnthalpyOffset`, `DiluteSteamViscosity` (`WaterRegion1` 143-169). Callers: the column port (steam rows, wet trays, the duty ledger), the network port (`WaterShare`, utility water, `WaterSaturation`'s seed), material (`HenryConstant`, `TrainedPhysics`). Moves: Region 1 bitwise identical (same accumulation order, `WaterRegion1` 101-109 against `WaterShare` 261-273); Wagner-Pruss has two associations (`V3WaterProperties` 66 `get(1)*(theta*root)` against `WaterShare` 374 `WAGNER[1]*theta*root`), so the solver whose form is dropped moves by about 1e-16 relative in p_sat; the column's steam changes only where it threw (F3).

### 2.5 Ports after the split (brief 3)

```java
/** Column port: one per solve, immutable; a Workspace per thread. Flat arrays, the Rust JNI layout. */
interface ColumnThermoPort {
    int components();
    String identity();                                  // F1
    Workspace newWorkspace();
    /** x normalised; slot 0 liquid-like, 1 vapour-like. out = {h, cp, z, rootCount, rootSeparation}. 0 ok, 1 domain, 2 no root. */
    int phase(double t, double p, double[] x, int slot, Workspace w, double[] lnPhi, double[] out);
    /** As phase, plus dLnPhiDt[n], dLnPhiDn[n*n] row-major (one-mole basis), hBar[n]; 3 = coalescence (caller differences). */
    int phaseDerivatives(double t, double p, double[] x, int slot, Workspace w,
                         double[] lnPhi, double[] dLnPhiDt, double[] dLnPhiDn, double[] hBar, double[] out);
    void wilsonK(double t, double p, double[] k);
    void idealGas(double t, double[] hPure, double[] cpPure);
    /** out = {pSat, dlnPsat/dT, hSteam(pw), dhSteam/dT, dhSteam/dpw, hLiquid(p), dhLiquid/dT}. */
    void water(double t, double p, double pw, double[] out);
}

/** Network port: the model of one compound list. */
interface NetworkThermoPort {
    int species(); boolean wet(); String identity();
    Prepared prepare(double t);                         // T-only terms (FluidThermodynamics.Prepared)
    /** out = {v, h, u, dvdp, kind}; kind 0 liquid-like, 1 vapour-like, 2 one dense root. 0 ok, 1 box, 2 no root. */
    int phase(Prepared w, double p, double[] amounts, int slot, boolean vaporCoexists, double[] lnPhi, double[] out);
    int tangent(Prepared w, double p, double[] amounts, int slot, boolean vaporCoexists,
                double[] base, double[] dT, double[] dP, double[] dN);
    EquilibriumResult tp(double t, double p, double[] n, PhaseCompetition c);
    EquilibriumResult ph(double p, double h, double[] n, PhaseCompetition c);
    EquilibriumResult uv(double u, double v, double[] n, PhaseCompetition c, double hintT, double hintP);
    void utilityWater(double t, double p, double[] out);
}
```
A Rust stage-(d) column call is `phaseDerivatives` over all stages: `t[S]`, `p[S]`, `x[S*n]`, outputs `[S*n]`, `[S*n*n]`. The `slot` int replaces `Root` and becomes the phase descriptor's request (REVIEW_PLAN 2).

### 2.6 Inversions (brief 4)

- `MaterialPresets`: `Column` keeps numbers only (feed volume, temperature, stages, pressures, `(tray, rate)` draws, `(stage, rate, T)` steam, pumparound tuples, three specification values, diameter). A column-side `ColumnPresets.input(MaterialPresets.Column, MaterialCatalog)` builds `V3ColumnInput` (now `Column.input` 24-32) and checks `MAX_COLUMN_DIAMETER_METRES` (87). `Descriptor.matches` takes `(packageId, assayId)`.
- `MaterialCatalog` 777-778: the preset check runs after `parse` in the plant layer: `MaterialReloadListener.prepare` (before `publish`, so a bad preset still refuses the reload as a transaction) and the bundled loader used by tests through one `Catalogs.load` above both.
- `HenryConstant` 42: `WagnerPruss.pressure(waterConstants, t)` from the water module.
- `SolidMaterialCatalog.validate` (22-25) moves to `fluid/state` as `SolidInventory.validate(SolidMaterialCatalog)`; callers `FluidPropertyReloadGuard` 39, `PhysicalRegistry` 307.

### 2.7 Revisions and identities (brief 5)

Tokens that decide what restates: `Package.scientificRevision` (method physics fields, dataset, pair-library, group-matrix and water-model revisions; `MaterialCatalog` 1314-1320), `fluidRevision` (+ viscosities, ice, fluid data; 365-374), the network's `HydrocarbonModel.revision` (`PhaseContract` identity incl. `DerivedPairs` identity and `WaterShare.REVISION`, + `FORMULATION` 38), `FluidThermodynamics.thermodynamicRevision` (`fluid-trbdf2-r1` + model + viscosity, 154) and `revision` (+ cap law, trace, solids, time; 160-165), `V3InputDigest` (`datasetRevision`, water revision with steam, 76).
- A code move changes none of them: identities are declared strings, not hashes. A STRUCTURE package therefore restates nothing; a package that moves values must bump the token that names the moved code (`WaterShare.REVISION`, `FORMULATION`, `DerivedPairs.REVISION`, or a new column token).
- A merged adapter needs one identity per port, each naming only what that port's values read: column = `scientificRevision;` derivation identity (F1) `;` a column water token when steam is on; network = today's. Then a viscosity or `WaterShare` edit moves the network only, a water-record edit moves both (it is in both), a derivation edit moves both (today the column misses it).

### 2.8 Deterministic math (brief 6)

Calls in production: log 200, cos 172, sin 171, exp 102, pow 67, log10 16, cbrt 16, atan2 16, expm1 14, asin 14, atan 8, acos 7, log1p 6, tanh 2. By solver area: column log 30, exp 19, pow 10, expm1 5, log1p 3, log10 1, cbrt 1; thermo log 87, exp 45, cbrt 5, pow 4, expm1 3, cos 3, acos 1; fluid log 31, exp 23, pow 9, cos 4, cbrt 4, expm1 3, sin/log1p/log10/asin/acos 1 each; material log 30, pow 18, exp 13, atan 8, expm1 3, tanh 2, log1p 1. Most sin/cos are geometry and client. `strictPow` has no production caller (`DeterministicMath` 93-95). No direct `Math.log/exp/pow` in my scope.

A Rust column must reproduce, bit for bit: fdlibm 5.3 `log`, `exp`, `expm1`, `log1p`, `log10`, `cbrt`, `cos` (with `__ieee754_rem_pio2`), `acos`; `DeterministicPow` and its `FdlibmPow` fallback (`DeterministicPow` 208-209, 238-239). Port the JDK's `FdLibm` sources, not the platform libm and not a crate assumed equal to fdlibm. Also: `Math.rint` is `round_ties_even` (`DeterministicPow` 245); Java `Math.max/min` return NaN and order signed zeros, Rust `f64::max/min` return the other operand for NaN (used at `V3FeedFlash` 58, `V3TruncatedFlash` 100; `Math.clamp` at `V3PengRobinsonSession` 206); no FMA contraction, no fast-math, the same summation order (Java sums left to right in every loop read here).

### 2.9 Places that assume a subcritical state (in scope)

`V3WaterProperties` 60-62 (Wagner-Pruss refuses T > Tc), 106 (T_sat refuses p > Pc), 159 (latent heat 0 at and above Tc), 273-276; `FluidThermodynamics` 674 (p_sat infinite at Tc); `WaterSaturation` 32, 84-91 (table to Tc - 0.05 K, extrapolated, then infinite at Tc: a jump); `TrainedPhysics` 89, 170; `DerivedPairs` 507 (liquid test needs T < Tc; fit-time only); `WaterShare` 160 and 41 (liquid gap faded to zero at Tc; S1 file). Root requests: `V3ThermoModel` 12-21, `V3FeedFlash` 28, 34, 52-53, `V3TruncatedFlash` 52-53, 92-93, 328-329, `V3PengRobinsonSession` 209-226 (exactly two evaluations), `FluidThermodynamics` 755, 818-820, `HydrocarbonModel` 159 (a liquid slot with one vapour-like root beside a vapour is refused) and 173 (`vaporLike` only on the vapour root). `V3FeedFlash` 46-48: a feed above its mixture critical point converges toward the trivial solution and is refused as "lost its two-phase root" instead of answered as one dense phase. `FluidThermodynamics.placed` 1308-1312 already places a one-root phase by its identification parameter: the right pattern.

## 3. Findings

| id | file:line and symbol | what it is | proposed change | class | lines | risk | proof |
|---|---|---|---|---|---|---|---|
| F1 | `V3CatalogPropertyPackage` 44 `datasetRevision`; `V3PengRobinsonSession` 37-43 | column identity omits the derivation it evaluates; `V3ColumnCalculator` 359 accepts a seed, 428 digests results on it | `scientificRevision + ";" + pairs.identity()` when `pairs != null` | DEFECT | +3 | every column identity moves once: cached column state and warm seeds rebuild (state, AGENTS saves rule) | add `V3IdentityTest`: bump a UNIFAC table revision in a test catalog, `datasetRevision` changes |
| F2 | `V3WaterProperties` 59-98, 128-163; `WaterShare` 39, 48-67, 244-296, 357-376; `WaterRegion1` 23-35, 86-119; `TrainedPhysics` 81-102 | water formulas written up to four times; `WaterShare` hard-codes the record's numbers | one `science/thermo/water` module on `WaterConstants` (2.4) | STRUCTURE (WP: one association, the other solver moves ~1e-16) | ~110 removed, ~650 moved | low | `WaterRegion1Test`, `WaterShareTest`, `SteamVirialTest`, `V3WaterPropertyDerivativesTest`, `V3FreeWaterTrayTest`, `AqueousNetworkGatesTest` |
| F3 | `V3WaterProperties` 128-129, 280-285 (`requireEnthalpyTemperature`), 49 and 273-276 | column steam enthalpy refuses outside 273.16-900 K, column p_sat below the triple point; the network continues the same functions (`FluidThermodynamics` 657-662, `V3WaterProperties` 54-59) | the module's continued forms for both | DEFECT (AGENTS thermo validity) | -10 | none inside the data range | `RangeContinuationGateTest` pattern: a column steam state at 950 K evaluates |
| F4 | `MaterialPresets` 4, 17, 20-32, 76-88 | material builds `V3ColumnInput` and specs | plain preset records; builder in the column package (2.6) | STRUCTURE | ~25 moved | low | `MaterialPresetsTest` |
| F5 | `MaterialCatalog` 777-778 | the parser runs the column's input validation | run it in the plant layer before publish (2.6) | STRUCTURE | 2 moved | a test that parses without the plant loader loses the check: keep it in `MaterialPresetsTest` | `MaterialPresetsTest`, `MaterialCatalogTest` |
| F6 | `HenryConstant` 4, 42; javadoc 18-20 stale (DerivedPairs 582 reads it, its token is in the derivation identity) | material imports the column for Wagner-Pruss | water module call; fix the comment | STRUCTURE | 2 | none | `DerivedPairsTest` |
| F7 | `SolidMaterialCatalog` 4, 22-25 | material imports `SolidInventory` | `SolidInventory.validate(catalog)` in `fluid/state` | STRUCTURE | 5 moved | none | `MaterialCatalogTest` + the reload guard's tests |
| F8 | `FluidThermodynamics` 5-6, 835-1151, 433-643, 644-696, 698-722 | seven roles; thermo calls solver | split per 2.1; `junctionSplit` and `phaseCheck` to `fluid/solver`, streams to `fluid/transport`, water to the module, `LegacySplit` to `fluid/state` | STRUCTURE | ~900 moved | wide caller churn (`PassiveStepSolver`, `ConservativeTransport`) | `FluidThermodynamicsTest`, `AqueousNetworkGatesTest`, `WetGasGatesTest`, chain-100 in the final lane |
| F9 | `SpinePackageData` 3, 70-71; `TranslatedPengRobinson` 5, 375 | block S1's move of the cubic adapter into the thermo core | formation datum as offsets `double[]` + revision in the core, `EnergyReference` built in `fluid/state`; a counter interface the network installs | STRUCTURE | ~20 | low | `SpineNetworkPathTest`, `TranslatedPengRobinsonTest` |
| F10 | `V3PengRobinsonThermo` 150-161; `V3FugacityDerivatives` 45-71; kernel 2060-2068 | per call: n x n copied twice, n^2 finiteness scan, five more array copies (n = 64: about 64 KiB) | write into caller-owned `double[]` (2.5); finiteness checked once on the flat output | PERF (identical) | ~60 | callers in `V3MeshResidualEvaluator` 294-300 adapt | `V3PengRobinsonDerivativesTest`, column digests |
| F11 | `V3PengRobinsonThermo` 125-128; `V3PengRobinsonSession` 246-248; `V3FugacityResult` 19 | ln phi cloned twice per `fugacity` | views into the workspace, as F10 | PERF (identical) | ~10 | low | `V3PengRobinsonThermoTest` |
| F12 | `V3WaterProperties` 31-40, 169-215; `MaterialRuntime` 17-21 | context forms in the residual (`V3MeshResidualEvaluator` 357-394, 533-566): two ThreadLocal reads, two map probes, boxed `List<Double>` per call | constants resolved once per solve (`double[]`) | PERF (identical) | ~40 | none | `V3WaterPropertyDerivativesTest`; counters in B1 |
| F13 | `V3PropertyComponent` 15-20, 41-59; `V3PengRobinsonSession` 176-207 | second ideal-gas implementation with another continuation edge | column reads `CoefficientIdealGas` per component | PHYSICS: inside range ~1e-16, outside 2.3 example 1 kJ/mol | ~45 | moves column refs only past the package range | column digests; `RangeContinuationGateTest` |
| F14 | `V3FeedFlash` 21-38, 44-81 | single phase decided by Wilson K alone; damped SS + 100-step bisection; no stability test; above the mixture critical point refused | column flash = engine TP on the column's kernel (one TPD), keep `V3FlashResult` | PHYSICS (owner): moves feeds whose Wilson and PR bubble or dew points straddle T; size to measure | ~160 | column references near boundaries; cost of TPD per feed | add a gate: preset feeds swept in T, `V3FeedFlash` phase against the engine's |
| F15 | `V3TruncatedFlash` 31, 44-60, 75-119, 219-282 | full reference always computed, then a reduced flash accepted within budgets of it | return the reference with the frozen `Support` | PHYSICS (feed split moves within the policy budgets) | ~250 | S5 to confirm what the column needs from the reduced split | `V3FlashTruncationTest`, `V3FlashTruncationColumnTest` |
| F16 | `V3TruncatedFlash` 338 vs `thermo/PhaseSupport` 12-13 | duplicate enum | use `PhaseSupport` | STRUCTURE | 1 | none | `V3TruncatedFlashNumericsTest` |
| F17 | `V3CatalogPropertyPackage` 53-68 vs `MaterialPresets` 49-65 | assay to moles twice | one function in material | STRUCTURE | ~15 | none (normalisation differs: keep the unnormalised result) | `MaterialPresetsTest`, `V3Cdu17TiaJuanaPackageTest` |
| F18 | `TranslatedPengRobinson` 47-60, 131-145 (constructors), 32, 45, 162-172, 385-391 (coefficients, fit ranges, Horner path) | the coefficient and spine-variant public constructors have no production caller (`HydrocarbonModel` 70 uses the kernel form; `CubicPhaseEvaluator` 73, 80, 196 use `residualOnly`); in production the Horner path runs on zero coefficients only | delete them; keep the private constructor for `residualOnly` (S1 to say whether its matrix forms are still used); tests build through the kernel form | STRUCTURE (zero coefficients give +0.0 either way) | ~70 | tests to port | `TranslatedPengRobinsonTest`, `CubicPhaseEvaluatorTest` |
| F19 | `HydrocarbonModel` 381-435 `pureLiquidCompressibility`, `realCubicRoots` | a third cubic solver, used for translations (`SpinePackageData` 122) | one-component kernel as `VolumeTranslation.shift` 33-43 | PERF (translations move a few ulp; every network value at rounding) | ~45 | network references at last bits | `PilotVolumeAnchorTest`, `HydrocarbonModelTest` |
| F20 | `MaterialRuntime` 19 | context-free `water()` falls back to package `icheme:tjl20_methane` by name | remove the context-free form with F12 | DEFECT (latent: a datapack without that package fails) | -3 | none | `MaterialCatalogTest` |
| F21 | `WaterSaturation` 84-91; `FluidThermodynamics` 674 | p_sat jumps from the extrapolated value to infinity at Tc | above Tc report "no saturation" in the descriptor; never a jump | DEFECT (small) | 5 | callers that branch on infinity (steam services, ejectors) | `AqueousNetworkGatesTest` + a continuity check at Tc |
| F22 | `WaterSaturation` 20 | `R = 8.314462618`, not `GAS_CONSTANT` | use the kernel's constant | STRUCTURE (Newton step only; last bits of the table) | 1 | none | `AqueousNetworkGatesTest` |
| F23 | `FluidThermodynamics` 424-429, 686-696; `WaterSaturation` 37-57 | 4 097 Newton solves per whole model, rebuilt for every `withTime` copy, each node allocating a workspace | key the curve by the model's thermodynamic revision; one workspace | PERF (identical) | ~10 | none | `AqueousNetworkGatesTest` |
| F24 | `HydrocarbonModel` 166, 170, 195-206 | per phase evaluation two arrays; per tangent n x n plus eight n arrays | workspace buffers for the share and tangent | PERF (identical) | ~15 | the `Phase` record keeps its own ln phi | `HydrocarbonTangentTest`, `DirectLiquidDeterminismTest` |
| F25 | `DeterministicMath` 92-95 | `strictPow` unused in production | delete (keep `FdlibmPow` for `DeterministicPow`) | STRUCTURE | 4 | test use only | `DeterministicMathTest` |
| F26 | `ViscosityCorrelation` 106-118 | constant logs and boxed binary search per call | `double[]` and precomputed logs at construction | PERF (identical) | ~10 | none | `ViscosityTest` |
| F27 | `CrystalReference` 248-251 | the CO2-I Gibbs function evaluated once per property | one evaluation per state | PERF (identical) | ~10 | callers in `CrystalPhaseEvaluator` (S1) | `JaegerSpanGibbsFunctionTest`, `CarriedCrystalsTest` |
| F28 | `FluidThermodynamics` 343 `engineIndex`; 1377-1379 `LIQUID_LIQUID` | identity function; a kind never raised | delete; keep the key string as an alias if a stored reason reads it | STRUCTURE | ~6 | none | `FluidThermodynamicsTest` |
| F29 | `V3ColumnProblemResolver` 15-28, 163; `V3WaterProperties` | the column has two water models (on axis = cubic, steam = free water) | owner option: one (on axis) for both solvers, the free-water rows deleted; needs V-L-L stages in the column (a condenser with free water) | PHYSICS: 2.4 sizes (steam cross term ~0.3 % of latent, p_sat cubic vs WP to measure, mutual solubility) | large (free-water rows in V3, S5) | high: V3 is a two-phase algorithm | `V3WaterPhaseQualificationTest`, `V3SteamFeedContractTest` |
| F30 | `DerivedPairs` 587-594 | Henry bisection evaluates `miss(low)` twice per step and runs 200 steps (~55 change the bracket) | cache `lowMiss`, stop when `middle` equals an end | PERF (identical, load time) | 3 | none | `DerivedPairsTest` |

## 4. Open questions for the main thread

1. F1: does the owner accept one restatement of every column identity, or should the derivation token enter only packages whose pairs come from a source other than `LIBRARY` and `NONPOLAR`?
2. F14/F15: may the column's feed flash become the engine's TP (owner decision on references near phase boundaries)? S5 must confirm what the column needs from the truncated split.
3. F29: one water model is the largest de-correlation gain but needs three-phase stages in V3; keep both column water modes until the Rust port, or plan it?
4. `TrainedPhysics.compare` 161-164 checks the authored matrix and no Mathias-Copeman alpha, while the column evaluates derived pairs (`V3CatalogPropertyPackage` 40): should a neural model's binding compare the derived pairs and alphas?
5. Is water's translation `c_w` zero in the derived evaluator? `WaterShare` replaces the cubic volume with Region 1 but `HydrocarbonModel` 163-168 keeps `+c` (the "7e-6 off Region 1" of `FluidThermodynamics` 145 suggests it is near zero). S1 to confirm.
6. REVIEW_PLAN 2 places material below the thermo core; the engine imports `MaterialCatalog` (`PhaseContract`, `CubicPhaseEvaluator`, `CrystalPhaseEvaluator`, `FluidTpEquilibrium`, `ThermoIdentity`) while material imports the kernel (`DerivedPairs` 4-6, `VolumeTranslation` 4, `ReferenceSpine` 8). Order the layers math, kernel, material, engine, solvers; or make the engine take resolved data?
7. F13: should the column's ideal gas hold at each component's own range (network) or keep the package range?
8. Should `datum_vaporization_enthalpy` become required so `liquidEnthalpyOffset`'s Watson branch (`V3WaterProperties` 248-250) can go? It refuses datapacks without the field.
