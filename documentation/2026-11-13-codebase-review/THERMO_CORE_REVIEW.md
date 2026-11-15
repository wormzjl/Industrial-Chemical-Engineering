# Thermo core and equilibrium engine: review (S1)

Scope read line by line: `science/thermo/**` (29 files, 12 883 lines: 9 top-level files 3 844, `phase/` 20 files 9 039). Base `5f61d3139`. Read outside the scope, for brief item 5 only: `fluid/thermo/HydrocarbonModel.java` 322-435, `fluid/thermo/TranslatedPengRobinson.java` 270-504. Everything else outside the scope by `Grep` (imports, signatures, call sites). Not read: test bodies (test classes are named from their file names), `DerivedPairs`, `FluidThermodynamics`, `NetworkPhaseEngine`, `WaterRegion1`, `WaterSaturation`, the V3 thermo classes, the code guide chapters.

Abbreviations: PRK `PengRobinsonKernel.java`, TPS `TangentPlaneStability.java`, PI `PairInteractions.java`, HV `HuronVidalExcess.java`, FTE `phase/FluidTpEquilibrium.java`, CPE `phase/CubicPhaseEvaluator.java`, CrPE `phase/CrystalPhaseEvaluator.java`, WS `phase/WaterShare.java`, PID `phase/PhaseIdentification.java`, ER `phase/EquilibriumResult.java`, EQ `phase/EquilibriumRequest.java`, PC `phase/PhaseContract.java`; outside: TPR `fluid/thermo/TranslatedPengRobinson.java`, HM `fluid/thermo/HydrocarbonModel.java`, FT `fluid/thermo/FluidThermodynamics.java`.

## 1. Summary

What the area does:
- `science/thermo` (top level): the PR78 kernel with one mixing rule (quadratic pairs, E-PPR78 `k_ij(T)`, Huron-Vidal NRTL cells), its analytic T and composition derivatives, the cubic's root selection, and Michelsen's tangent-plane stability test.
- `science/thermo/phase`: phase evaluators (translated cubic, anchored crystals, water's IF97 share) and `FluidTpEquilibrium`: TP equilibrium of up to three fluid phases and up to two crystals, with PH and UV as outer searches over TP answers.
- Users: the kernel is used by both solvers; the engine (`phase/`) is used only by the fluid network (`FluidThermodynamics`, `NetworkPhaseEngine`, `NetworkCrystals`). The column takes from `phase/` only `SteamVirial`.

The five largest problems:
1. `FluidTpEquilibrium` is one class of 6 290 lines whose stages talk through about 110 mutable workspace fields (FTE 608-697): the feed is mutated and restored, scratch phase states are read as results after a failed solve (FTE 2338-2343), and balance solvers answer through NaN-coded fields (`coexistenceX`).
2. Layering. `phase/` imports the network's package for its own phase evaluator (CPE 5-8, CrPE 3, PC 3, `PhaseDomain` 3), and `science/thermo` and `science/material` import each other (`phase/` reads the catalog in 5 files; `DerivedPairs`, `VolumeTranslation`, `ReferenceSpine` read the kernel).
3. The same numerics are written two to ten times: PR volumetric block 4, cubic roots 2, lower-Gibbs root choice 3, substitution with extrapolation 3, Newton on the Gibbs energy 2, Rachford-Rice 2, bracketed one-variable solves 10, IF97 Region 1 twice.
4. Cost structure, by counts: one engine workspace holds four kernel workspaces that each prepare the temperature terms; every two-root state is evaluated twice in full; E-PPR78 calls `pow` once per group term per pair per preparation; every PH/UV iterate is a cold TP solve (feed stability, flash, two product stability tests) that allocates a result graph and formats text.
5. Failure typing by exception and message text: the kernel refuses a trial by `IllegalArgumentException`/`IllegalStateException` (19 catch sites in scope), the engine's failure kind is a prefix of `detail()` that `FluidThermodynamics` parses (FT 315, 320, 1276-1277). Both block a flat Rust boundary without callbacks.

The ten highest-value changes, by gain over cost:
1. P20: one shared set of temperature terms for the four kernel workspaces (bitwise identical).
2. P21: a per-temperature table of the distinct E-PPR78 group-pair powers (bitwise identical).
3. P22: the lower-Gibbs root chosen inside the kernel from a closed scalar form (identical except at exact ties).
4. P23: `CubicPhaseEvaluator.derivatives` evaluates the same state twice (bitwise identical to fix).
5. S03: delete the per-branch-pressure paths, dead since the free-water rule went (about 170 lines, bitwise identical).
6. S04/S05: move the residual cubic, the ideal-gas fits and the spine data below the engine; one binding class holds every catalog factory.
7. S06/S07: a typed failure kind and a typed refusal (status code in the kernel): prerequisite of the Rust boundary.
8. S01/S02: split `FluidTpEquilibrium` into ten files with the dry core as a function of an explicit feed.
9. P24/P25: PH and UV searches on an internal outcome (no result graph, no text) and warm-started; values inside the search's own tolerance.
10. X36: label-free phases with a traits record, the API that lets a caller receive one dense phase.

Contradictions to REVIEW_PLAN sections 1 and 2: see section 4, questions 1 to 3.

## 2. Module map

### 2.1 Sub-modules as they should be drawn

| Module | Files (lines) | Imports today that cross the wrong way |
|---|---|---|
| A. Kernel (math only) | PRK 2 077, HV 366, PI 277, `NumericalBox` 53, `ThermoComponent` 29, `IdealGasFunction` 31; to move in: PID 134, `SteamVirial` 110, the residual half of TPR (about 330 of 532) | TPS 5 imports `phase.PhaseIdentification` (its only use is `admissible`, TPS 705-708, dead: S03) |
| B. Phase-split numerics (kernel only) | TPS 898; carved from FTE: two-phase flash 4789-5438 (650), three-slot flash 5440-6189 (750), band 6191-6227 | none after the carve |
| C. Solver support vocabulary | `PhaseSupport` 34, `TraceTruncationPolicy` 79 | none; shared by V3's truncated flash and `fluid/solver/PhaseLayout` (by grep), worded for V3 (`TraceTruncationPolicy` 19-26, 73) |
| D. Engine types | EQ 71, ER 427, `PhaseAmounts` 54, `PhaseCompetition` 74, `PhaseKind` 22, `PhaseState` 223, `PhaseDerivatives` 99, `DepositionOnset` 63, `WaterParticipation` 24, `ThermoIdentity` 90 | `ThermoIdentity` 3 imports `MaterialCatalog` (one factory, line 65) |
| E. Evaluators | CPE 389, CrPE 261, `SolidPhaseEvaluator` 52, `CrystalFugacity` 12, WS 395 | CPE 5-8 (`CoefficientIdealGas`, `HydrocarbonModel`, `SpinePackageData`, `TranslatedPengRobinson`), CrPE 3 (`CoefficientIdealGas`); CPE 9-10, CrPE 4-6 import `science/material` |
| F. Engine | FTE 6 290 (rest after the carve: about 4 800), PC 193, `PhaseDomain` 56 | PC 3, `PhaseDomain` 3 import `fluid.thermo.FluidDomain`; PC 4-6, FTE 5-7 import `science/material` |

Dependency facts: `science/material` imports module A (`DerivedPairs` 4-6, `VolumeTranslation` 4, `ReferenceSpine` 8); modules D to F import `science/material` (57 uses in 5 files). So the thermo core is two layers with material between them: A, B, C below material; D, E, F above it. Only the catalog factories force D to F above material: `FluidTpEquilibrium.forPackage` 356, `forDerivedPackage` 373, `PhaseContract.forPackage` 73, `forDerived` 115, `admittedCrystals` 148, `CubicPhaseEvaluator.forPackage` 114, `fromPackage` 129, `derived` 158, `CrystalPhaseEvaluator.forPackage` 59, `ThermoIdentity.of` 65, and `CrystalReference`/`JaegerSpanGibbsFunction` as the crystal model (CrPE 34, 153).

### 2.2 `FluidTpEquilibrium`: stages, split, shared state

| Stage | Lines | Target file (package-private, `phase/`) |
|---|---|---|
| Constants, `Settings` | 113-314 | `FluidTpEquilibrium` (facade) |
| Construction, crystal anchoring, `Crystal`, family triple point | 316-592 | `CrystalSet` |
| `Workspace`, `Search`, `Slots` | 594-893, 6144-6189 | `EquilibriumWorkspace`, `BracketSearch` |
| Request: `tp`/`ph`/`uv`, checks, box, `evaluateTp`, grading | 895-1101, 4412-4440 | facade |
| PH and UV outer searches (window, starts, `solvePh`, `solveUv`, `newtonUv`, `nestedUv`, `innerUv`) | 1103-2015 | `SpecificationSearch` |
| Steps TP leaves undetermined: pure coexistence | 2017-2163 | `BalanceSteps` |
| Search answers and failures | 2165-2218 | `SpecificationSearch` |
| Crystals at TP (`solveWithCrystals`, vapour and general solves, two crystals, feed reduction, coverage, holds) | 2220-3060 | `CrystalEquilibrium` |
| Crystal steps of PH and UV (pure step, triple point, binary three-phase, lever rule, binary vapour-liquid-liquid) | 3062-4120 | `BalanceSteps` |
| Onsets | 4122-4410 | `CrystalOnsets` |
| Dry TP: dispatch, single-phase answer and label, merged, two-phase result, failure | 4442-4581 | facade |
| Core: stability and dispatch | 4583-4645 | `DryCore` |
| Carried crystals (filter, restated split, stand-in) | 4647-4787 | `CarriedCrystals` |
| Two-phase: substitution, finish, Newton, Rachford-Rice, root choice | 4789-5438 | `TwoPhaseFlash` |
| Three-slot system | 5440-6142 | `ThreeSlotFlash` |
| Critical band, result builders, small linear algebra | 6191-6290 | `CriticalBand`, facade, `science/math` |

State shared between stages, and why:
- `feed`, `basisAmounts`, `z`, `present`, `presentCount`: the request's material. The crystal solves overwrite them with a reduced feed and restore them (`savedFeed`, `savedBasis`; FTE 2302-2322, 2427-2428, 2556-2557, 2784-2858), and `beyondStandIn` does the same (4756-4767). Reason: `hydrocarbon()` reads its feed from the workspace instead of an argument.
- `liquidState`, `vaporState`, `liquidAmounts`, `vaporAmounts`: the split's products. Written by `finishSplit`, `slotOutcome`, `carriedSplit`; read by `twoPhaseResult`, and by `splitChemicalPotentialsUsable` after a failed solve (2338-2343). The same two states are scratch for `pureCoexistence` (2035-2036), `pureCrystalStep` (3090), `stepFluid` (3182) and `singlePhaseDrive` (4391). The comment at 677 records one defect class of this sharing.
- `first`, `second` (kernel evaluations): scratch of almost every stage; `mixtureParameters` leaves a result in `first` for its callers (1470, 1506, 1545).
- `search`, `inner`, `coexistenceX`, `coexistenceResidual`, `innerResult`, `innerFailure`: out-parameters between the searches and the balance solvers (2031, 2049-2050, 3118-3119, 3602-3603), NaN meaning none.
- Counters (`kernelEvaluations` and five more), `feedResult`, `failure`, `tieLine`, `seedDistance`, `residual`, `productDistance`: the core's outcome, read by the result builders.
- `competition`, `active`, `carrying`, `carriedProduct`, `standInHeld`, `beyondStandIn`, `paired`, `secondForming`: the request's mode and side channels of the crystal solves (2304, 2706, 2822, 4997).

Target: the dry core returns a `DryOutcome` it owns (kind, root, amounts, states, tie line, failure kind) for an explicit `(t, p, feed[])`; scratch states are separate from outcome states; the searches receive `(answer | side, x, residual)` as a value.

### 2.3 The engine's contract

- `EquilibriumRequest` (EQ 15-38): a specification pair (TP, PH or UV), the species list equal to the contract's basis in order (else `IllegalArgumentException`, FTE 4419-4421), amounts finite, nonnegative, some positive, and a `PhaseCompetition`.
- `EquilibriumResult` (ER 23-315): status `CONVERGED`, `UNSUPPORTED` (typed reason) or `NOT_CONVERGED`; for a converged answer the phases over the shared basis with frozen states, a classification, a coverage grade, diagnostics, and the deposition numbers when a crystal is present. Each component conserves to rounding (smaller share direct, larger by difference: FTE 4932-4943, 5164-5175, 6053-6059). Aggregates (`enthalpy`, `internalEnergy`, `volume`, `entropy`) read the balance book (ER 384-415). No derivative crosses: a caller that needs `c_p` evaluates the phase again.
- What the contract takes from `science/fluid/thermo`, and what must move down:

| Type | Used at | What the engine needs | Move |
|---|---|---|---|
| `FluidDomain` | PC 79, 129; `PhaseDomain` 35-39 | `packageId()` and four envelope numbers | `PhaseDomain.of(packageId, Validity, components)`; `FluidDomain` and its `requireInput*` stay in the network |
| `CoefficientIdealGas` (106 lines) | CPE 118, 176; CrPE 63 | an `IdealGasFunction` from a catalog property | to `science/material`, beside `ReferenceSpine` (the other `IdealGasFunction`) |
| `SpinePackageData` (149 lines) | CPE 121-122, 172-181 | components, spines, pair model, anchors, translations | to `science/material`; its `formationReference()` (imports `fluid.state.EnergyReference`, line 3) stays with the network |
| `TranslatedPengRobinson` | CPE 57, 73, 80, 196, 226-261 | `residualOnly`, `prepare`, `evaluateValues`, `Values` (the residual cubic with translation and the volumetric block) | the residual half to module A; the ideal-gas half (TPR 381-391) stays as the network's adapter; cut `SolverDiagnostics.count` (TPR 375) |
| `HydrocarbonModel.coefficientTranslations` | CPE 119 | translations of a spine-less package | deleted with HM 395-433 (finding D09): the kernel anchoring of CPE 185-194 serves both |

After these moves and a binding class for the factories listed in 2.1, `phase/` imports the kernel, `science/math`, and `science/material` for `CrystalReference` only.

### 2.4 Supercritical readiness

The kernel assumes nothing subcritical. `Root.LIQUID` and `Root.VAPOR` select the smallest and the largest physical root (PRK 1576); with one real root both return it (PRK 1534, 1571-1574; TPS 84-86). The Soave alpha continues above `Tc` unchanged (PRK 439-446).

Every place in scope that branches on a critical constant, a saturation curve or a latent heat, or that turns a root into a liquid or vapour claim:

| Site | What it assumes | Effect on a supercritical state |
|---|---|---|
| PRK 470-471 `prepareMathiasCopeman` | `c2`, `c3` dropped above `Tc` | value and slope of `a(T)` continuous, curvature steps by `a_c c2 / Tc^2` (finding X34) |
| PRK 401-411 `wilsonK` | Wilson's correlation on `Tc`, `Pc` | seeds only (TPS 293, FTE 4828, 5471) |
| HV 86-107 `held` with fade, `FADE_END` 1.25 | no local composition above the polar compound's `Tc` | an NRTL pair is classical with `k = 0` from 1.25 `Tc` (809 K for water) |
| WS 160 `t < TC`, 41 and 161-186 fade from 623.15 K, 164-169 and 357-376 Wagner saturation line | the liquid gap exists only below water's `Tc` | at `T >= 647.096 K` a dense phase (`v/b < 2.6`, WS 45) has no share: water is the cubic's alone |
| WS 135-149 gas gap, `SteamVirial` 43-71 | second virial only | no density correction for supercritical steam between dilute and dense |
| CPE 371-373 | three roots: label from the requested root; one root: `FLUID` | already a descriptor |
| CPE 191-194 `derived` | a translation needs a liquid root at the volume anchor | a compound supercritical at its anchor gets no translation |
| CrPE 89-93 `anchor` | a liquid root at the triple point | none (triple points are subcritical) |
| FTE 4532-4535 `supercritical`, 4505-4506 | `SUPERCRITICAL_FLUID` only for a pure component above its own `Tc` and `Pc` | a mixture is never classified supercritical |
| FTE 4516-4529 `singleLabel` | every other one-root phase is labelled `LIQUID` or `VAPOR` by the identification parameter | the label flips on a line through a continuous region; a consumer with slots moves the inventory |
| FTE 4561-4572 `twoPhaseResult`, 4965-4968, 6082-6084 | `VAPOR_LIQUID` or `LIQUID_LIQUID` by `denseLiquidLike` of both products | a dense supercritical solvent beside a liquid changes classification at `PIP = max(1, Z)` or `v = 3.9514 b` |
| FTE 6197-6221 `inBand`, `pureBandBox` | boxes in reduced T and P; boxes 3 and 4 are above `Tc` and `Pc` | every pure-fluid answer at `Tr` 1.0-1.2, `Pr` 1.5-3 is graded research-only, a failure there is typed `CRITICAL_BAND` (finding X35) |
| FTE 827-833 `straddlesPureCoexistence`, used at 1347, 1927, 1984 | a `LIQUID`/`VAPOR` label change between bracket ends is a first-order jump | safe today only because a pure fluid above `Tc` and `Pc` is labelled `FLUID` (4520) |
| FTE 2090-2163 `coexistenceTemperature`, `coexistencePressure` | an equal-fugacity point of a pure fluid; one-root ends sorted by `liquidLike` (2099-2104, 2140-2145) | above `Tc` the bisection converges to the label line, then `pureCoexistence` finds a zero gap and returns a side (2046-2051): wasted evaluations, no wrong answer |
| FTE 2928-2936 `samePhases`, used at 1336, 1916 | a secant is valid only between answers with the same labels | a relabel discards the secant: cost only |
| FTE 3084-3085, 3135 `pureCrystalStep` | the step's fluid is the liquid root or the vapour root, by the triple point | a crystal melting into a fluid above `Pc` is labelled `LIQUID` |
| FTE 1857-1863 `innerUv` | Trouton's slope `-10 T` in `(1/T, ln P)` | a start only; a one-phase answer starts from `cubicPressure` |
| FTE 3246-3249, 3282-3287, 4250-4251, 4322-4323, 591 | Clausius-Clapeyron with a sublimation or fusion enthalpy | starts and first slopes only |
| FTE 483, 498-499, 531-532 | pure liquid and vapour at a crystal's triple point | none |
| TPS 260-283, 372, 650-667; FTE 5412-5430, 5293-5310, 5559, 6022 | "preferred root, then the lower Gibbs energy" | none: with one root the request is moot |

Outside the scope, by signature only (S2 owns them): HM 98 `criticalTemperature`, 149-155 `phase(..., vaporCoexists)`, 329-335 `liquidLike`, 349-352 `RootAbsent.liquidBesideVapour`; a grep of `fluid/thermo` for `criticalTemperature(`, `saturationPressure`, `bubblePressure`, `latent`, `vaporCoexists`, `WaterSaturation`, `WaterRegion1` matches 15 lines of `FluidThermodynamics` and 10 of `HydrocarbonModel`.

`PhaseKind` and `PhaseIdentification` as they stand: `PhaseKind` already has `FLUID` (22 lines); `PhaseState.kind()` states the root situation, `PhaseAmounts.kind()` the engine's label. `PhaseIdentification` (PID 50-133) gives the parameter, the rule `PIP > max(1, Z)` and the volume guard `v < 3.9514 b`; the engine computes these per answer (FTE 4523-4527, 4965-4968, 6084) and keeps only the resulting label.

The API change (finding X36):
1. Kernel: `Root` becomes a selector with three values, `SMALLEST`, `LARGEST`, `LOWER_GIBBS` (finding P22). No caller names a phase to the kernel.
2. `PhaseState` carries `PhaseTraits(physicalRootCount, compressibility, volumeRatio = v_untranslated / b, identificationParameter)`, filled from numbers the evaluation already holds (`Z`, `a`, `b`, `da/dT`; no kernel call).
3. `EquilibriumResult.phases()` lists the fluid phases lightest first (the order of FTE 4567-4570 and 6124-6138 already) and then the crystals. `PhaseAmounts.kind()` becomes `PhaseIdentification.label(traits)`, one function a caller may ignore.
4. `Classification` (13 constants, ER 35-105) reduces to what the phases do not say: `PURE_COEXISTENCE_UNDERDETERMINED`. The five mapping sites go (FTE 2601-2608, 3438-3447, 3748-3752, 4002-4004, 4501-4508).
5. A consumer with a fixed slot model (the network: one vapour, up to two liquids) maps phases to slots in one place by a continuous trait (density or `volumeRatio`), with its own hysteresis. One dense phase arrives as one fluid phase with `physicalRootCount = 1`; no special case.

### 2.5 A flat-array kernel boundary

Model data (immutable, built once per list): `n`; `Tc[n]`, `Pc[n]`, `omega[n]`; derived `a_c[n]`, `b[n]`, `kappa[n]`; Mathias-Copeman flag and `c1..c3[n]`; classical pairs `first[p]`, `second[p]`, `constant[p]`, `slope[p]`, `low[p]`, `high[p]`, `model[p]`; E-PPR78 terms `termStart[p+1]`, `coefficient[t]`, `exponent[t]`; NRTL cells in row form `cellStart[n+1]`, `partner[c]`, `alpha[c]`, `intercept[c]`, `slope[c]`, `low[c]`, `high[c]`, `fade[c]` (PRK 41-97; PI 40-52). Jagged today: `pairIndex[n][n]` (PRK 68), `cellIndex[n][n]` (PRK 88): flatten to `n*n`.

Calls today and their hidden contracts:
- `carry(feed)` (PRK 1886), `prepareTemperature(T, ws)` (420), `evaluate(T, P, x, root, ws, out)` (413), `evaluateDerivatives(...)` (774), `wilsonK` (401), static `selectRoot(A, B, root)` (1503).
- A three-call protocol on "the mixture the preceding evaluation left in the workspace": `prepareRootDerivatives`, `mixtureSecondTemperatureDerivative`, `secondTemperatureDerivative` (PRK 887-959), used by TPR 300, 406 and WS 302, 340.
- Views into the workspace (`compositionView`, `attractionRootsView`, `attractionRowsView`, PRK 1962-1970) that TPR 447-482 and WS 308, 343 read to form partial molar volumes and the pure cubic.
- No pressure derivative: `d ln phi/dP` is formed in CPE 296 from the partial volumes of TPR 479-482.

Proposed boundary (the kernel, the volumetric block of TPR `fill` and PID `parameter` as one call):

```
status = eos_eval(model, temps, work, T, P, n[N], root, want, out)
root : 0 smallest, 1 largest, 2 lower Gibbs
want : LNPHI | DT | DN | CP | VOLUMETRIC | PARTIAL_VOLUMES
out scalars : Z, v, a, b, da/dT, d2a/dT2, hR, cpR, dP/dv, dP/dT, d2P/dv2, d2P/dTdv, PIP, rootCount, rootSeparation
out arrays  : lnphi[N], dlnphi_dT[N], dlnphi_dP[N], vbar[N], hbarR[N], dlnphi_dn[N*N] (row-major)
status : OK, OUT_OF_BOX, BAD_COMPOSITION, NO_ROOT, COALESCENCE, NONFINITE_PAIR, UNSTABLE_ROOT
```

`temps` is the temperature-level block (`a`, `da/dT`, `d2a/dT2`, `sqrt a` and its two derivatives per compound; `k`, `k'`, `k''` per pair; ten values per cell), filled by `eos_prepare(model, T, support, temps)` and shared by every user at that temperature (finding P20). `work` is per thread.

Workspaces today:
- `PengRobinsonKernel.Workspace` (PRK 1698-1978): 10 `double[n]`, 3 per pair and 3 per model pair, 10 per cell, and on a kernel with NRTL pairs 25 `double[n]` and 2 `double[n*n]`; support lists and three stamp arrays.
- `Evaluation`: `lnphi[n]` and 7 scalars. `Derivatives`: an `Evaluation`, 7 `double[n]`, and `dLogPhiDn` as `double[n][n]` (PRK 2035, 2049): flatten.
- `TangentPlaneStability.Workspace`: a kernel workspace, 2 points with 2 evaluations and 1 derivative block each, a Hessian `n*n`.
- `FluidTpEquilibrium.Workspace`: one kernel workspace, one stability workspace, one evaluator workspace (two kernel workspaces inside, CPE 238-239), 30 `double[n]`, 2 `n*n`, about 10 phase states, `Slots` with 8 `double[3][n]` and 2 `double[(2n)^2]`.

What allocates per call:
- Kernel: a `RootSelection` per evaluation (PRK 1578; WS 311); `inSupport.clone()` at each support change (PRK 1951); an exception object with its stack trace and a formatted message at each refused trial (PRK 424, 523, 580, 791, 1506, 1577, 1647-1654, 1669).
- Stability: one `Result` and a clone of the trial composition per test (TPS 106, 347); the accessor clones again (TPS 109; callers FTE 4810, 5530).
- Crystal: two `double[1]` and a state record per evaluation (CrPE 185, 194, 244-245); `chemicalPotential` allocates the function's record (CrPE 201).
- Engine, per TP solve: the request (`List.copyOf`, a stream, a clone: EQ 28-30); the result, its `Diagnostics`, the lists; per phase one `PhaseAmounts` (clone at `PhaseAmounts` 35 of an array `expand` just made, FTE 6232-6236) and one `PhaseState.copy()` (3 arrays, `PhaseState` 134-145); with a crystal competition a `StringBuilder` with one formatted double per crystal (FTE 2248-2255) and a second result with a concatenated detail (`assessed`, FTE 3035-3039), on every solve including every iterate of a search.
- Engine, per balance step: `new double[3]` and `new double[9]` scratch (FTE 3534-3537), a `double[3]` per `solve3` inside the Newton loop (3463, 3553), an `EquilibriumResult[]` per `straddlesThreePhase` call (3489, called from 1349 and 1929 at every iterate of a binary with a crystal), `double[9]` per `tripleBalances` (3410, 3416), `double[4]` per `envelope()` (1138).

### 2.6 Duplicated numerics

| What | Sites |
|---|---|
| PR constants `0.45724`, `0.07780`, `kappa(omega)` | PRK 288-293, 1677-1681; HM 398-401 |
| Cubic coefficients and Cardano | PRK 1508-1575 (non-cancelling term, backward-accuracy test, bounded Newton refinement); HM 416-433 (none of these; at coalescence it returns two roots where the kernel returns one, HM 425-427 against PRK 1535-1540); PRK 786-787 forms `c2`, `c1` again |
| `A`, `B` | PRK 730-731, 784-785; WS 310; HM 405-406 |
| `ln phi` of a pure compound | PRK 734-744; WS 312-313 |
| Residual enthalpy or energy, closed form | PRK 753-754; WS 327; FTE 1509-1510 |
| `P(v)`, `dP/dv`, `dP/dT`, `d2P/dv2`, `d2P/dTdv` | PID 50-59; TPR 353-356, 456-463; WS 315-320; FTE 1548-1551 |
| Lower-Gibbs root of one composition | TPS 650-667; FTE 5412-5430; FTE 5293-5310 |
| Substitution with dominant-eigenvalue extrapolation | TPS 446-489; FTE 4870-4903; FTE 5634-5670 |
| Newton on the Gibbs energy, shifted Cholesky, Armijo | FTE 5061-5160 (two phases); FTE 5902-5994 (two or three; with one non-reference slot it is the former) |
| Rachford-Rice | FTE 5326-5394 (minor-phase form); FTE 5734-5849 (Michelsen's Q, any slot count) |
| Bracketed Newton, secant or bisection in one variable | `Search` FTE 766-893; 1188-1206; 1504-1533; 2090-2122; 2129-2163; 2381-2405; 2513-2584; 3198-3229; 3237-3279; 4268-4351 |
| Small dense solves | TPS 715-742; FTE 541-543, 1585-1587 (2x2); 3456-3464; 4064-4094; 5261-5275; 6284-6289 |
| IF97 Region 1, Region 2 ideal part, Wagner saturation | WS 48-67, 205-212, 244-296, 357-376; `fluid/thermo/WaterRegion1`, `WaterSaturation` (named by WS 48; not read) |

## 3. Findings

Class: STRUCTURE (bitwise identical), PERF, PHYSICS, DEFECT. "Lines" is removed (−) or moved (→). Proofs name existing test classes by file name; "digest (to add)" is a gate that records `doubleToLongBits` of phase amounts and state values for a fixed set of TP, PH and UV requests per bundled package, read before and after.

| id | file:line and symbol | what it is | proposed change | class | lines | risk | proof |
|---|---|---|---|---|---|---|---|
| S01 | FTE 113-6290, whole class | one class holds ten stages (2.2) | split into the files of 2.2; no arithmetic changes | STRUCTURE | → 6 290 | low; a large diff on a file the network depends on | digest (to add); `FluidTpEquilibriumTest`, `FluidTpEquilibriumP3Test`, `FluidTpEquilibriumPhUvTest`, `AqueousEquilibriumTest`, `GasSolidEquilibriumTest`; chain-100 exact |
| S02 | FTE 608-697 `Workspace`; 2302-2322, 2784-2858, 4756-4767; 2338-2343; 2035-2036, 3090, 3182, 4391 | feed mutated and restored; scratch states read as results | `DryCore.solve(t, p, feed[])` returns an outcome it owns; scratch states separate; searches receive values | STRUCTURE | − 80 | medium: evaluation order must stay | as S01 |
| S03 | FTE 2333, 4456-4465, 4640, 4796, 4815, 4955-4960, 4974-4981, 5005, 5013, 5188, 5281-5290, 5299, 5313, 5451; TPS 192-234, 265-275, 289, 360-362, 626-649, 705-708, 846-863 | per-branch pressures of the removed free-water rule; `setBranches` has one call, with equal pressures (FTE 4445) | delete `branched`, `vaporShifts`, `pressureOf`, `shift`, `admissible`, the branched overloads | STRUCTURE | − 170 and the branch tests (5 call lines) | low: the class comment states equal pressures are the one-pressure test bit for bit (TPS 34-35, 244) | `compileTestJava`; `TangentPlaneStabilityTest` (branch tests removed, proven by name); digest |
| S04 | CPE 5-8, 57, 73, 80, 118-122, 172-196; CrPE 3, 63; PC 3, 79, 129; `PhaseDomain` 3, 35-39 | the engine imports `science/fluid/thermo` | the moves of 2.3 | STRUCTURE | → about 600 | medium: `thermodynamicRevision` and `fluidRevision` strings must not change (S2) | `SpinePackageEvaluatorTest`, `CubicPhaseEvaluatorTest`, `ThermoIdentityTest`; digest |
| S05 | FTE 356, 373; PC 73-157; CPE 114-200; CrPE 59-66; `ThermoIdentity` 65 | catalog factories inside the engine classes put `phase/` above `science/material` while material imports the kernel | one binding class above material holds them; engine classes take arrays and interfaces | STRUCTURE | → about 300 | low | as S04 |
| S06 | ER 203-236; FTE 2334-2336, 3055, 3188, 4578, 5000, 5541; FT 315, 320, 1276-1277 | the failure kind is a prefix of `detail()`; the engine itself parses it and concatenates a pattern per call (2336) | `enum FailureKind` on `EquilibriumResult` (`NONE`, `REFUSED`, `OUTSIDE_BOX`, `CRITICAL_BAND` as a flag, `UNSTABLE_PRODUCT`, `UNRESOLVED_PRODUCT`, `CRYSTAL_PHASES`, `CRYSTAL_STEP`, `SEARCH`) | STRUCTURE | − 10 | low | `NetworkPhaseEngineTest`, `FluidTpEquilibriumP3Test`, `NearPureCrystalStepTest` |
| S07 | PRK 424, 523, 580, 791, 1506, 1577, 1647-1654, 1669; TPS 575, 613, 645, 663; FTE 1022 and 14 more catch sites | a refused trial is an exception with a stack trace and a formatted message; `evaluateTp` catches every `IllegalArgumentException`, `IllegalStateException`, `ArithmeticException` of the whole solve (FTE 1020-1026), so an invariant failure (`PhaseAmounts` 41, FTE 2994, ER 280-313) reads as "evaluation refused" | kernel evaluation returns a status (2.5); the Java facade throws one `ThermoRefusal` without a stack trace; the engine catches only it | STRUCTURE, DEFECT (latent) | about 60 touched | medium: every caller of the kernel (V3, network, still) catches the two JDK types today | `PengRobinsonKernelRootPrecisionTest`, `G3F5CriticalRegionPathTest`; column digests; chain-100 exact |
| S08 | PID 50-59; TPR 353-356, 456-463; WS 315-320; FTE 1548-1551 | the cubic's pressure derivatives four times | one static function in the kernel, the operations in the order of TPR 456-463 | STRUCTURE | − 40 | low: PID uses `free * free`, TPR `(v-b)*(v-b)`: the same doubles | `WaterShareTest`, `CubicPhaseEvaluatorTest`; digest |
| D09 | HM 395-433 `pureLiquidCompressibility`, `realCubicRoots`; caller HM 390 | a second cubic solver with no root refinement and its own constants; `Math.sqrt(a*a)` at 404 | delete; `coefficientTranslations` evaluates the kernel's pure liquid as CPE 185-194 | PHYSICS | − 40 | translations of spine-less, water-less packages move by the root's rounding (at most about 1e-12 relative in `Z`); HM 377-378 states that path is outside the network | `CubicPhaseEvaluatorTest`; test-side `PengRobinson78` |
| S10 | PRK 94-97, 105-147, 265-276, 422-425, 1665-1671 | four range parameters; every production caller passes the open range, the numerical box or an ad hoc wide range (TPR 202-205; `V3PengRobinsonSession` 41-57; CPE 163; WS 79; `DerivedPairs` 183, 509, 556, 778, 789; `VolumeTranslation` 34-36) | remove; the kernel checks finite positive T and P | STRUCTURE | − 40 | low: V3 kernels lose the box check inside the kernel; V3 must keep it at its port (S5) | column digests; `PengRobinsonKernelHuronVidalTest` |
| S11 | PRK 387-390, 746-750, 862-884, 1986-1993 | a process-wide mutable test switch read on every evaluation | a workspace-level debug flag | STRUCTURE | 10 | low | the tests that set it |
| S12 | TPS 715-742; FTE 3456-3464, 4064-4094, 5261-5275, 6284-6289 | Cholesky, shifted factor, 3x3 Cramer, pivoted solve inside the thermo classes | to `science/math` | STRUCTURE | → 110 | low | digest |
| S13 | FTE 5264, 5795, 5948 | `DeterministicMath.pow(10.0, k)` for a fixed ladder of shifts, per attempt | three static tables filled by the same calls | STRUCTURE | 6 | none | digest |
| D14 | CrPE 97-109, 137-143; `SolidPhaseEvaluator` 45-50 | the by-anchor path builds the crystal without the liquid's share (`anchored(anchor)`, 140), so for ice it is another crystal than the engine's (CrPE 132-135; FTE 466-467); production uses only `anchoredCrystal` (`NetworkCrystals` 87) | delete the by-anchor methods, the `last` cache and the interface; keep `TriplePointAnchor` | DEFECT (latent) | − 70 | low: tests use the path | `SolidPhaseEvaluatorTest`, `CrystalPhaseEvaluatorTest` rewritten on `Anchored` |
| S15 | FTE 4491, 4522, 4539 | `x == Root.VAPOR ? Root.VAPOR : Root.LIQUID`, an identity | delete | STRUCTURE | 3 | none | compile |
| S16 | TPS 446-489; FTE 4870-4903, 5634-5670 | the extrapolation written three times | one helper, same operation order | STRUCTURE | − 40 | low | digest |
| P20 | FTE 717, 720, 721; TPS 818; CPE 238-239; PRK 420-457 | four kernel workspaces per engine workspace; each prepares alpha terms, pairs and cells at a new temperature (three always, the fourth when a derivative block is asked, CPE 274) | split the kernel workspace into shared temperature terms and per-user mixture scratch | PERF, identical | about 150 touched | medium: stamps and support growth (PRK 429-456) | digest; `PengRobinsonKernelPairInteractionsTest` |
| P21 | PI 115-126 `evaluatePair`; PRK 497-508 | one `pow` per group term per pair per preparation; pairs that touch the same group pair repeat the same call | a per-temperature table of `pow(ratio, exponent)` over the distinct exponents of the support's pairs; terms index into it | PERF, identical | + 40 | low | `PairInteractionsTest`, `PengRobinsonKernelPairInteractionsTest` |
| P22 | FTE 5412-5430, 5293-5310; TPS 607-667; PRK 611-732, 1576 | the lower-Gibbs root is found by a second full evaluation (mixing sums, excess correction, `n` logarithm terms), all root-independent up to PRK 732; `selectRoot` already has both roots | `Root.LOWER_GIBBS`: compare `(Z-1) - ln(Z-B) - A/(2 sqrt2 B) ln[(Z+(1+sqrt2)B)/(Z+(1-sqrt2)B)]` of the two roots (equal to `sum x_i ln phi_i`, since `sum x_i S_i = a`), then one fugacity loop | PERF | − 60 | the choice can differ from today's only where the two sums agree to rounding (about 1e-14); a tie keeps the preferred root as now | `TangentPlaneStabilityTest`, `G3F1PureFluidOracleTest`, `G3F5CriticalRegionPathTest`; chain-100 exact |
| P23 | CPE 266-275 `derivatives` | value evaluation on `w.terms`, then `evaluateDerivatives` on `w.mixture`, which evaluates the same state again (PRK 779) | fill the translated block from the derivative call's own evaluation | PERF, identical | 10 | low | `G3F8ConservationAndDerivativeTest`, `CubicPhaseEvaluatorTest` |
| P24 | FTE 1159-1175 `outerTp`; 2248-2255, 3035-3039; 4496-4497, 4567-4572, 6232-6256; `PhaseAmounts` 35; `PhaseState` 134-145; TPS 106, 109; EQ 28-30; FTE 4419 | every iterate of a PH or UV search builds a full result (2.5), with formatted text under a crystal competition; every request re-validates the species list with a stream | searches run on the internal outcome of S02; one result at the end; diagnostics hold numbers, text is formatted on read; a request bound to its contract validates the basis once | PERF, identical values | about 200 touched | low | `FluidTpEquilibriumPhUvTest`; digest |
| P25 | FTE 4599 (feed stability), 4984-4985 (two product tests), reached from 1288, 1608, 1684-1688, 1881 | every iterate of a search is a cold TP solve; "results do not depend on what the workspace computed before" (598-599) | inside one search: start the flash from the previous iterate's `ln K` when it was a split, test products only at the accepted iterate, then one cold solve at the answer | PERF | + 80 | the answer's T and P move inside `outerTolerance` (1e-11 relative) and the energy inside `SPECIFICATION_TOLERANCE` (1e-6 `N R T`), the search's own acceptance; near a phase boundary a warm flash can hold a split one iterate longer | `FluidTpEquilibriumPhUvTest`, `NearPureCrystalStepTest`; campaign; needs B1's share of PH and UV first |
| P26 | PRK 826-852; consumers TPS 519, 569; FTE 5108-5109, 6008 | `d ln phi_i/dn_j` filled for all `n*n` entries with a division each; it is symmetric, and every consumer averages `[i][j]` and `[j][i]` | fill `j >= i`, mirror, one reciprocal of `a*a` | PERF | 20 | Hessian entries move by a few units in the last place (1e-15 relative): column digests and exact lines move in the last digits | `G3F8ConservationAndDerivativeTest`; column digests re-recorded with old and new values |
| P27 | PRK 1503-1579 `selectRoot` returns `new RootSelection` (1578); WS 311 | a record per evaluation in a kernel documented as allocation-free | write `Z`, the count and the separation into the output | PERF, identical | 10 | none | `PengRobinsonKernelRootPrecisionTest` |
| X30 | FTE 4789-4949, 5020-5275, 5316-5409 against 5440-6142; 5450-5454 `threeSlotFeed` | two flash engines; the three-slot system with one slot dropped is a two-phase flash | one N-slot flash for every list (two slots without a polar compound) | PHYSICS, owner option | − 510 | usual states: amounts within the flash tolerance (1e-10 in `ln K`, about 1e-9 relative); extremes: a phase below 1e-10 of the feed loses the minor-fraction precision of 5316-5324 (slot tolerance 1e-13, FTE 252), and merged or band outcomes near a critical point can change; every network reference re-recorded | campaign; chain-100 and the 33 junction lines with old and new values |
| X31 | FTE 3466-3679, 3795-4120 (540 lines) against `leverStep` 3691-3766 | two Newton solvers for a binary's three-phase steps; the lever rule is documented as their generalisation (3682-3689) | keep `pureCoexistence`, `pureCrystalStep`, `pureTriplePoint`, `leverStep`; delete the two binary solvers | PHYSICS, owner option | − 540 | same state within 1e-11 relative in T or `ln P` and 1e-6 `N R T`; cost: the bracket closes by bisection, about 30 more TP solves per request on such a step (from 1e-2 to 1e-11); water with one hydrocarbon at its boiling step is a usual state, so not recommended unless B1 shows the step is rare | `FluidTpEquilibriumPhUvTest`, `AqueousEquilibriumTest`, `G5F3ThreePhaseLineTest` |
| X32 | FTE 161, 667-668, 1713-1715, 1727-1728, 1769-1773, 1800-1812, 1923-1925 | inexact inner volume solves of the nested UV search, with a re-solve state machine; two recorded wrong-side defects (comments 1722-1726, 1801-1803); already off under a crystal competition | exact inner solves always | PHYSICS, owner option | − 40, five flags | answers inside the same tolerances; cost: a few more TP solves per temperature far from the answer | `FluidTpEquilibriumPhUvTest` |
| X33 | FTE 136, 320-321, 417-420, 4600-4604 | a second stability instance with ten times the budget, run after an unresolved first test | one test with the long budget | PHYSICS, small | − 15 | verdicts equal; the seeding trial can differ when a later trial would have proven first; answers inside the flash tolerance | `FluidTpEquilibriumP3Test` |
| X34 | PRK 464-481 `prepareMathiasCopeman` | above `Tc` only `c1`: `d2a/dT2` steps by `a_c c2 / Tc^2` at `T = Tc`, so `c_p` (the slope of the enthalpy) steps | owner option: keep (the standard form), or fade `c2`, `c3` over a width above `Tc` as `HuronVidalExcess.held` does | PHYSICS, owner option | + 15 | size in the dilute-gas limit: `0.457 R c2 P/Pc`, that is 3.8 `c2` J/(mol K) at `Pc` and 0.05 `c2` at 0.0136 `Pc` (CO2 at 1 bar); a fade moves every derived-list reference above each `Tc` | `PengRobinsonKernelHuronVidalTest`; a continuity test to add |
| X35 | FTE 6197-6221 `pureBandBox` boxes 3 and 4; 1164-1173; 4469, 4565, 4577 | supercritical boxes graded research-only by position | with supercritical work: the grade from the validated region, not a box; until then no change | PHYSICS, owner option | 0 | none now | `G3F10QualifiedRegionTest` |
| X36 | CPE 371-373; FTE 4501-4529, 4561-4572, 4965-4968, 6082-6084; ER 35-112; PID 81-133 | the engine computes the identification numbers and returns only a label and one of 13 classifications | the API of 2.4: `PhaseTraits` on the state, phases ordered by volume, labels derived, three root selectors | STRUCTURE in the engine; the consumer's slot rule is S2's | − 80 | medium: `FluidThermodynamics` switches on `Classification` | digest; `NetworkPhaseEngineTest` |
| X37 | PRK 887-959, 1962-1970, 2035, 68, 88 | a three-call protocol on leftover workspace state, internal views, jagged arrays | the single call of 2.5 with a `want` mask; flat arrays | STRUCTURE | about 120 touched | medium: TPR and WS read the views | column digests; `WaterShareTest` |
| X38 | PRK 788, 1590, 1610, 1625 `Math.fma` | the root refinement, its backward-accuracy test and the coalescence guard use a fused multiply-add | the Rust kernel calls an exact software `fma` at these four sites and forbids contraction elsewhere; or the Java kernel is re-recorded without `fma` first | STRUCTURE (port rule) | 0 | a port with "no FMA" changes which roots are refined | `PengRobinsonKernelRootPrecisionTest` on both paths |
| X39 | WS 48-67, 205-212, 244-296, 357-376 | a second IF97 Region 1 (with third pressure derivatives, 267), Region 2 ideal part and Wagner line inside the share | S2's one water module provides them; the share keeps the gap, weight and fades | STRUCTURE if the sums keep their order | − 110 | low | `WaterShareTest` |

## 4. Open questions for the main thread

1. REVIEW_PLAN section 2 draws material under the thermo core. The code needs two layers: kernel, stability and flash numerics below material (material's `DerivedPairs`, `VolumeTranslation`, `ReferenceSpine` import them), the engine above it or freed from the catalog by a binding class (S05). Which one does the decoupling plan take?
2. REVIEW_PLAN section 2 says the Rust path uses no FMA. The Java kernel calls `Math.fma` at four sites (X38); `EnergyReference` and `SparseLuSolver` hold the other three of the code base. Explicit fused operations in Rust, or a Java re-record without them?
3. REVIEW_PLAN section 1 item 3 is right, with one precision: the equilibrium engine is the network's alone. The column's port needs the kernel, `HuronVidalExcess`, `PairInteractions`, `SteamVirial`, `NumericalBox`, `TraceTruncationPolicy` and `PhaseSupport`, and nothing else from `phase/`. Does the engine go to Rust with the fluid step, or stay in Java behind the network port?
4. `TraceTruncationPolicy.REINSERTION_FACTOR` is kept equal "so the two engines agree" (lines 19-26). Does each solver's port own its trace policy after the split?
5. P20, P21 and P25 are ranked from counts. B1 should report, for the fluid scenario: the share of time in `prepareTemperature` and `PairInteractions.evaluatePair`, the number of TP solves per PH and per UV request, and the share of stability tests in a TP solve.
6. X30 (one flash for every list) deletes about 510 lines and moves every network reference. Owner option now, or after the Rust boundary is fixed?
7. Supercritical target: which fluids and states first? It decides X34 (alpha curvature at `Tc`), X35 (band boxes 3 and 4), and the region where water has no share (`T >= 647.096 K`, dense).
8. Is there a bitwise gate of the engine alone? chain-100 exact covers the network's path through it. S01 to S16 want the digest gate of section 3 before the first move.
9. The carried-crystal rule (D23, FTE 4647-4787) and the near-pure rule (D21, FTE 199-223, 3094-3127, 3317-3335) are about 450 lines of owner decisions. They are left out of the simplifications on purpose; confirm.
10. S07 changes what every kernel caller catches (V3, `StillColumn`, `TowerTransfer`, the network). Should S5 and S3 plan their ports on status codes from the start?
