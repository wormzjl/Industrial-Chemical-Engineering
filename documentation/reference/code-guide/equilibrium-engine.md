# Multiphase equilibrium engine: requests, contracts, the dry core and the flashes

Up: [code guide](README.md)

## Read this when

- A network node, junction or vessel gets the wrong phases, phase labels or phase amounts (gas, oil, aqueous liquid).
- An island holds with `HELD (phases)` and the detail names `CRITICAL_BAND`, `UNSTABLE_PRODUCT` or `UNRESOLVED_PRODUCT`.
- An answer is graded `RESEARCH_ONLY` or `EXTRAPOLATED` ("extrapolated past ..." in its evidence), or a request is
  `UNSUPPORTED`.
- You change water's share, the stability settings, the flashes, the critical band or the cache identity.
- A step's answer states a CO2-rich liquid, a stand-in or one liquid where you expected frost (carried crystals).
- A wet gas condenses its oil late or all at once (the liquid trial's water trace).

Crystals at TP, onsets, the PH and UV searches and the steps answered from a balance (`CRYSTAL_PHASES`, `CRYSTAL_STEP`,
`coexistenceFromBalance`) are in [equilibrium-crystals-searches.md](equilibrium-crystals-searches.md). The kernel, the
stability test, the phase identification and the water module are in [thermo-kernel.md](thermo-kernel.md); the records
the engine reads in [materials.md](materials.md). The network side (when it calls the engine, how a failure becomes a
hold, `NetworkPhaseEngine`) is in [fluid-science.md](fluid-science.md). The column does not use the engine.

## What it does

A mixture at a given temperature and pressure splits into the phases that minimise its Gibbs energy: a gas, up to two
liquids (an oil and an aqueous liquid), and pure crystals such as ice or dry ice. Every compound, water included, stands
in every fluid phase. At fixed enthalpy and pressure (a junction) or fixed energy and volume (a closed vessel) the
temperature and pressure follow from the same minimum. In code, an `EquilibriumRequest` has a specification (TP, PH or
UV), amounts over the package contract's basis, and a phase competition (fluid only, the fluid phases plus the
package's crystals, or the fluid phases carrying them: a network step's request). `FluidTpEquilibrium` is the façade:
it checks the request against the `PhaseContract`, then hands it to its stage objects. The dry core tests the feed's
stability on the PR78 kernel and splits an unstable feed with the two-phase flash, or with the three-slot system when a
polar compound is present, each with a Newton finish on the Gibbs energy. The `EquilibriumResult` gives the phases on
the shared basis, a classification, a coverage grade, a typed failure kind and diagnostics. Data ranges never refuse: an
answer outside them is graded `EXTRAPOLATED`; only the numerical box bounds a trial. The engine's one production caller
is the fluid network (`NetworkPhaseEngine`).

## Class map

**`science/thermo/phase/`** ([source](../../../src/main/java/com/wormzjl/icheme/science/thermo/phase/)): the classes of
this chapter. Lines at the snapshot.

| Class | Role | Lines |
|---|---|---|
| [`FluidTpEquilibrium`](../../../src/main/java/com/wormzjl/icheme/science/thermo/phase/FluidTpEquilibrium.java) | The façade: `tp`, `ph`, `uv` (also the warm start `uv(request, workspace, hintT, hintP)`), the checks (`contractChecks`, `implementationChecks`, `outsideBox`), `evaluateTp`, the grading (`graded`, `outsideData`, `crystalCeiling`), the onsets' entries, `anchoredCrystal`, `pureBandBox`, the answer inspectors and builders the stages share (`fluidChemicalPotential`, `holdsLiquid`, `converged`, `expand`, `diagnostics`, `unsupported`, `single`), the constants; nested `Settings` (`DEFAULT`), `Workspace` (holds the two `BracketSearch`es `search` and `inner`) and `Feed`; one instance each of `CrystalSet`, `CriticalBand`, `DryCore`, `CrystalEquilibrium`, `BalanceSteps`, `CrystalOnsets`, `SpecificationSearch`, built at the end of the constructor in that order | 940 |
| [`DryCore`](../../../src/main/java/com/wormzjl/icheme/science/thermo/phase/DryCore.java) | The TP answer of one feed without crystals, on the `Feed` it is handed: `solveDry`, `hydrocarbon` (composition, stability test and its longer-budget repeat, dispatch to `ThreeSlotFlash` or `TwoPhaseFlash`, the carried restatements), `feedStability`, `stabilityOf`, `threeSlotFeed`; answers `singlePhase` (`singleClassification`, `singleLabel`), `merged`, `twoPhaseResult`, `threePhaseResult`, `failed`. Builds the longer-budget test, the polar list, `CarriedCrystals` and both flashes | 335 |
| [`TwoPhaseFlash`](../../../src/main/java/com/wormzjl/icheme/science/thermo/phase/TwoPhaseFlash.java) | The two-slot split: `twoPhase` (two attempts, the second from Wilson's K), substitution with Rachford-Rice in the minor phase's fraction (`split`), the Newton finish (`newtonFinish`, `shiftedSolve`), `finishSplit` (products, merge, product re-check, `ownSplit`); `lowerGibbsRoot`, `differentiatePhase` (shared with the three-slot system) | 691 |
| [`ThreeSlotFlash`](../../../src/main/java/com/wormzjl/icheme/science/thermo/phase/ThreeSlotFlash.java) | Three fluid phases as one system: `threeSlot` (seeds, the audit and its one reseed), `slotSubstitution`, `slotFractions` (Michelsen's multiphase Rachford-Rice), `slotNewton`, `slotOutcome`; nested `Slots` (the workspace's scratch) | 798 |
| [`CarriedCrystals`](../../../src/main/java/com/wormzjl/icheme/science/thermo/phase/CarriedCrystals.java) | Carried crystals under `FLUID_CARRYING_CRYSTALS`: the trial filter `carriedTrial` with `crystalLean`, `supersaturated`, `crystalRich`; the restated split `carriedSplit`; the stand-in held out `beyondStandIn`; `CARRIED_SHARE` | 199 |
| [`CriticalBand`](../../../src/main/java/com/wormzjl/icheme/science/thermo/phase/CriticalBand.java) | The declared critical band: `inBand`, `bandReason` | 41 |
| [`EquilibriumRequest`](../../../src/main/java/com/wormzjl/icheme/science/thermo/phase/EquilibriumRequest.java) | Record: `Specification` (TP, PH, UV), two values (K and Pa; Pa and J; J and m³), species (the contract basis), amounts, competition | 71 |
| [`EquilibriumResult`](../../../src/main/java/com/wormzjl/icheme/science/thermo/phase/EquilibriumResult.java) | The answer: `Status`, `UnsupportedReason`, `Classification`, `FailureKind`, `PhaseAmounts`, `coexisting`, `Coverage`, `CoverageGrade`, `Deposition`, `Diagnostics`; aggregates `enthalpy`, `internalEnergy`, `volume`, `conservationDefect` | 507 |
| [`PhaseContract`](../../../src/main/java/com/wormzjl/icheme/science/thermo/phase/PhaseContract.java) | What a package promises: basis, `WaterParticipation`, qualified competitions, water-chemistry species, `PhaseDomain`, fluid coverage, `ThermoIdentity`; `resolve`, `qualifies`, `crystalCompetition`, `waterIndex` | 93 |
| [`PhaseCompetition`](../../../src/main/java/com/wormzjl/icheme/science/thermo/phase/PhaseCompetition.java) | `FLUID_ONLY`, `FLUID_AND_CRYSTALS` (resolved by the contract to the package's set), `FLUID_CARRYING_CRYSTALS`; `withCrystals`, `carried()` | 74 |
| [`PhaseDomain`](../../../src/main/java/com/wormzjl/icheme/science/thermo/phase/PhaseDomain.java) | The package envelope used for grading (`of`, four numbers the caller reads from the catalog; `open`); `covers` | 59 |
| [`PackageBinding`](../../../src/main/java/com/wormzjl/icheme/science/thermo/phase/PackageBinding.java) | The catalog factories, the only class of the package that reads `MaterialCatalog`: `equilibrium`, `derivedEquilibrium`, `contract`, `derivedContract`, `derivedBasis`, `admittedCrystals`, `evaluator`, `fromPackage`, `derivedEvaluator`, `crystal`, `identity` | 305 |
| [`CubicPhaseEvaluator`](../../../src/main/java/com/wormzjl/icheme/science/thermo/phase/CubicPhaseEvaluator.java) | Fluid family `pr78_translated_v1` (`FAMILY`): the translated PR78 at the state pressure for every root (`TranslatedCubic.residualOnly`), one `IdealGasFunction` per component; the derived family `DERIVED_FAMILY` (pairs from `DerivedPairs`, water untranslated, `WaterShare` on the balance book); `fill`, `waterComponent()`, `derivation()`, `Workspace.carry` | 293 |
| [`WaterShare`](../../../src/main/java/com/wormzjl/icheme/science/thermo/phase/WaterShare.java) | Water's own share of h, s and v in a phase of the cubic, never in g or ln phi: `x_w W L + x_w² (1 − W) G`, the liquid gap L to IF97 Region 1, the gas gap G to `SteamVirial`, the weight W a C2 quintic of v/b; its fades and continuation below the triple point; `REVISION` | 338 |
| [`PhaseState`](../../../src/main/java/com/wormzjl/icheme/science/thermo/phase/PhaseState.java), [`PhaseDerivatives`](../../../src/main/java/com/wormzjl/icheme/science/thermo/phase/PhaseDerivatives.java), [`PhaseAmounts`](../../../src/main/java/com/wormzjl/icheme/science/thermo/phase/PhaseAmounts.java) | Caller-owned phase state: the cubic's book (g, ln phi) and the balance book (h, s, v with water's share: `shareEnthalpy`, `shareEntropy`, `shareVolume`, `balanceInternalEnergy`, `balanceVolume`), the root's identification inputs (`setIdentification`, `traits`); first derivatives at fixed composition; one phase of a result on the shared basis, with its `traits` | 263, 99, 62 |
| [`ThermoIdentity`](../../../src/main/java/com/wormzjl/icheme/science/thermo/phase/ThermoIdentity.java) | The identity of the numbers: package revision, evaluator family, spine revision, water part; `revision()`; `NO_SPINE`, `NO_WATER`, `WATER_IN_EOS_FORMULATION` | 80 |
| [`PhaseKind`](../../../src/main/java/com/wormzjl/icheme/science/thermo/phase/PhaseKind.java), [`WaterParticipation`](../../../src/main/java/com/wormzjl/icheme/science/thermo/phase/WaterParticipation.java) | `VAPOR`, `LIQUID`, `FLUID`, `SOLID`; water `NONE` or `IN_EQUATION_OF_STATE` | 24, 24 |

The crystal and search classes of the same package (`CrystalSet`, `CrystalEquilibrium`, `CrystalOnsets`,
`CrystalPhaseEvaluator`, `DepositionOnset`, `SolidPhaseEvaluator`, `CrystalFugacity`, `SpecificationSearch`,
`BracketSearch`, `BalanceSteps`) are mapped in [equilibrium-crystals-searches.md](equilibrium-crystals-searches.md).

## Main flow

1. **Construction.** `PackageBinding.equilibrium(catalog, packageId)`: a package with water in its cubic on its derived
   contract (`derivedEquilibrium`), any other on `PackageBinding.contract` and `PackageBinding.evaluator`; one
   `CrystalPhaseEvaluator` per admitted crystal. The network builds its own through `NetworkPhaseEngine.of` on
   `PackageBinding.derivedContract` of its model's evaluator. The constructor requires the evaluator's basis to be the
   contract's, anchors each crystal once (`CrystalSet`) and builds the stage objects.
2. **`tp(request, workspace)`, `ph`, `uv`.**
   - `contractChecks`: a species list other than the basis throws `IllegalArgumentException`; water beside a
     water-chemistry species is `UNSUPPORTED` (`WATER_CHEMISTRY_NOT_MODELLED`); the competition is resolved
     (`PhaseContract.resolve`), one the contract does not qualify is `PHASE_COMPETITION_NOT_QUALIFIED`.
   - `implementationChecks`: a competing crystal without its evaluator is `NOT_IMPLEMENTED`; the request's material
     goes into the workspace's `request` feed, and the engine names the feed as the support of its kernel workspaces.
3. **TP.** `outsideBox` answers `NOT_CONVERGED` (`OUTSIDE_BOX`) outside the box. `evaluateTp` dispatches to
   `DryCore.solveDry` (fluid only, or the carried request) or `CrystalEquilibrium.solveWithCrystals`.
   - `DryCore.hydrocarbon`: `feedStability` (the `TangentPlaneStability` test; an `UNRESOLVED` verdict is repeated once
     with `UNRESOLVED_STABILITY_BUDGET_FACTOR` times the budget); stable: `singlePhase`; unstable: `ThreeSlotFlash.threeSlot`
     when a polar compound is present, else `TwoPhaseFlash.twoPhase`; then `merged`, `twoPhaseResult` or
     `threePhaseResult`.
   - Under `FLUID_CARRYING_CRYSTALS`, the stability tests are filtered and the splits restated (`CarriedCrystals`).
4. **PH, UV.** `SpecificationSearch` over `evaluateTp` ([equilibrium-crystals-searches.md](equilibrium-crystals-searches.md)).
5. **`graded`.** A converged answer outside a data range of the models it used (`outsideData`) is regraded
   `EXTRAPOLATED`, the range named in its evidence.
6. **Refusals.** A `ThermoRefusal` or the kernel's `NoPhysicalRoot` inside the solve becomes `NOT_CONVERGED`
   "evaluation refused: ..." of kind `REFUSED`; any other `IllegalArgumentException`, `IllegalStateException` or
   `ArithmeticException` gets the same text and kind `INTERNAL` (a defect, still answered).

## Contracts

**Request and result.**

- Units are SI: K, Pa, J, m³, mol. Amounts are per species of the contract basis, in order: the package's compounds,
  water last where the package carries it (`PackageBinding.derivedBasis`). The record refuses non-finite values, a
  non-positive T or P (TP), P (PH) or V (UV), negative amounts, no material and duplicate species.
- Status rules (the `EquilibriumResult` constructor): `CONVERGED` has a classification other than `NONE`, at least one
  phase and an answer grade (`PURE_COEXISTENCE_UNDERDETERMINED`: no phases and exactly two `coexisting` states);
  `UNSUPPORTED` has a typed `UnsupportedReason`, no phases, grade `UNAVAILABLE`; `NOT_CONVERGED` has no phases, grade
  `UNAVAILABLE` and the attempt's diagnostics.
- Classifications (15): `SINGLE_VAPOR`, `SINGLE_LIQUID`, `SINGLE_FLUID`, `SUPERCRITICAL_FLUID`, `VAPOR_LIQUID`,
  `LIQUID_LIQUID`, `VAPOR_LIQUID_LIQUID`, `PURE_COEXISTENCE_UNDERDETERMINED`, `SOLID_PRESENT`, `VAPOR_SOLID`,
  `LIQUID_SOLID`, `VAPOR_LIQUID_SOLID`, `LIQUID_LIQUID_SOLID`, `VAPOR_LIQUID_LIQUID_SOLID`, `NONE`; `holdsCrystal()` is
  true for the six crystal classes.
- `UnsupportedReason`: `PHASE_COMPETITION_NOT_QUALIFIED`, `WATER_CHEMISTRY_NOT_MODELLED`, `NOT_IMPLEMENTED`.
- Detail prefixes: `CRITICAL_BAND` (a failure inside the band), `UNSTABLE_PRODUCT` and `UNRESOLVED_PRODUCT` (a
  converged split whose product the re-check proved unstable or left unresolved), `CRYSTAL_PHASES`, `CRYSTAL_STEP`.
- `FailureKind` is set beside each detail; read it, not the text, inside the engine: `NONE`, `UNSUPPORTED`, `REFUSED`,
  `INTERNAL`, `OUTSIDE_BOX`, `FLASH`, `UNSTABLE_PRODUCT`, `UNRESOLVED_PRODUCT`, `CRYSTAL_SOLVE`, `CRYSTAL_PHASES`,
  `CRYSTAL_STEP`, `SEARCH`. A failure inside the band keeps its own kind; `failedInCriticalBand()` is true exactly when
  the detail starts with `CRITICAL_BAND`. The workspace writes `failure` and `failureKind` together.
- `CoverageGrade`: `QUALIFIED`, `ESTIMATED_DECLARED_ERROR`, `RESEARCH_ONLY`, `EXTRAPOLATED`, `UNAVAILABLE`; `answer()`
  for the first four. A fluid answer carries the contract's fluid coverage with "; fluid-only: solid phases not
  assessed"; under a crystal competition the worst of it and each crystal's; `RESEARCH_ONLY` inside the band.
- `solidsAssessed()` is false for a `FLUID_ONLY` or a carried result: a constrained fluid answer.

**Contracts of a package** (`PackageBinding`):

- `derivedContract`: the compounds other than water in package order, then water, `IN_EQUATION_OF_STATE` (`NONE`
  without water), fluid-only plus the admitted crystals as one set (ice among them), the package envelope,
  `ESTIMATED_DECLARED_ERROR`, the derived family's identity.
- `contract`: a package with water in its cubic takes `derivedContract`; any other its PR78 compounds without water,
  `NONE`, fluid-only plus the admitted crystals, the spine in the identity.
- `admittedCrystals`: the usable crystal records graded `qualified` or `estimated_declared_error`.
- `FLUID_CARRYING_CRYSTALS` qualifies wherever the contract has a crystal competition (`qualifies`); it is in no
  `qualifiedCompetitions` list.

**Water in the cubic.** Water dissolves and is dissolved; a feed can split into a gas and two liquids. Its pairs come
from `DerivedPairs`, its alpha is a Mathias-Copeman fit. Two books per phase state: the cubic's book holds g and ln
phi, and every equilibrium condition reads it; the balance book holds h, s and v with water's share (`WaterShare`), so
pure liquid water's enthalpy and volume are IF97 Region 1's while its fugacity is the cubic's. Energy and volume
balances, levels and walls read the balance book. The datum: saturated liquid water at 298.15 K lies the water
record's `datum_vaporization_enthalpy` below the saturated steam.

**Carried crystals** (a network step's request, where crystals would compete: a crystal package, a crystal species
present, at or below the crystal ceiling; `FluidThermodynamics.stepCompetition`). No crystal competes: the request runs
`solveDry`. The phase a crystal would take is no fluid phase of its own: a dense fluid stays one liquid; a vapour keeps
a stand-in of crystal species only in the liquid slot; a vapour beside a methane-rich liquid leaves the CO2 in that
liquid, supersaturated. Ice's stand-in is supercooled water in the aqueous liquid.

- The filter (`carriedTrial`, a `TangentPlaneStability.TrialFilter` in every stability test of the feed and of both
  products, `stabilityOf`) excludes a stationary dense trial when a crystal species is over `CARRIED_SHARE` of the trial
  or of the tested phase, that phase is dense or a split's product, and it is supersaturated by the deposit test's own
  sign (`supersaturated`); and the dense supersaturated phase with the crystal species taken out (`crystalLean`). A feed
  on a vapour root is never filtered; above the triple point nothing is supersaturated. A feed of the three-slot system
  is not filtered: a crystal's stand-in is a liquid of its own there.
- An excluded trial proves and seeds nothing; with no surviving trial the result reports distance 0 and the feed.
- `carriedSplit`: with no crystal-rich product the split is the answer; both products dense outside the band is a
  dense fluid's stand-in split, and the feed is one liquid; otherwise a crystal-rich denser product keeps only its
  crystal species and the lighter product takes the rest.
- The three-fluid window: below the triple point PR78 continues vapour, liquid and frost as three fluid phases (3 % CO2
  at 101 kPa between 113 and 129 K). A split whose vapour product is unstable beside a stable crystal-rich liquid
  records that liquid's crystal species (`standIn`); when the split then fails, `beyondStandIn` holds the stand-in out,
  splits the rest alone and returns it to the liquid.

**Threads and determinism.** Fixed arithmetic and trial order; elementary functions through `DeterministicMath`. The
engine and its stage objects are immutable after construction (final references, built once; no request allocates
one). A `Workspace` is per thread and belongs to one engine (`own` refuses another engine's); the network keeps one per
thread in a `ThreadLocal` of `NetworkPhaseEngine`. The dry core solves the `Feed` it is handed (the cubic's amounts, the
basis amounts, the composition and present list `hydrocarbon` sets) and reads no feed of the workspace. The workspace
holds four preallocated feeds: `request` (never overwritten), `reducedFeed`, `pairFeed`, `standInFeed`; nothing is saved
or restored. Iterations allocate nothing; results hold frozen `PhaseState` copies. `WaterShare` keeps the liquid gap of
the last (T, P) in its per-thread scratch.

**Identity and caching.** `ThermoIdentity.revision()` is `thermo-identity-v2` and four length-prefixed parts: the
package revision, the evaluator family, the spine revision (`NO_SPINE`), the water part (`NO_WATER`). The derived
contract's: the package revision `MaterialCatalog.fluidRevision` with `DerivedPairs.Result.identity`, the family
`DERIVED_FAMILY`, and `water-in-eos-v1:<water dataset revision>:<WaterShare.REVISION>`. Nothing is hashed. Anything that
stores a number computed from a phase state keys on `revision()`: the network's thermodynamic revision, regression
fixtures, warm starts and factorizations.

## Laws and constants

- **Flash** (`TwoPhaseFlash`): Rachford-Rice substitution on ln K_i = ln phi_i^L − ln phi_i^V from the stability test's
  stationary composition, each phase on its own lower-Gibbs root, an extrapolation every `accelerationCycle` steps,
  solved in the minor phase's fraction. Newton finish when substitution is slow (`SLOW_SUBSTITUTION_STEPS` with the
  eigenvalue above `SLOW_EIGENVALUE`), past `substitutionLimit`, collapsing onto the feed or losing the split: it
  minimises G over the vapour mole numbers, the Hessian scaled by √(v_i l_i/z_i) and shifted by μI until Cholesky
  succeeds (shifts from `SHIFTS_FROM_1E8`, `SHIFTS_FROM_1E13`), the step cut so no amount shrinks below a tenth of
  itself, an Armijo line search on ΔG/RT; converged at max |g_i| < `FUGACITY_TOLERANCE`. Amounts are split per
  component (the smaller share directly, the larger by difference), so each component conserves to rounding.
- **Three slots** (`ThreeSlotFlash`): for fixed ln phi the phase fractions minimise Michelsen's convex
  Q(β) = Σ β_k − Σ z_i ln(Σ_k β_k/phi_ik) (`slotFractions`), then substitution on the stacked ln phi and the Newton
  finish (`slotNewton`). Seeds: Wilson's vapour, Wilson's liquid of the non-polar compounds (water at the trace), the
  water-rich liquid. An absent slot is watched until it stays absent; two coinciding slots are one. A failed audit
  reseeds an absent slot once; a second failure is `UNSTABLE_PRODUCT`. Every other feed keeps the two-slot path.
- **Merge and labels**: a split with Σ z_i (ln K_i)² < `mergeTieLine` and molar volumes within `mergeVolumeGap` is one
  fluid (`SINGLE_FLUID`); otherwise both products are re-tested. The denser phase is `LIQUID`, the lighter `VAPOR`; two
  liquid-like products denser than the cubic's critical volume, outside the band, are `LIQUID_LIQUID`. A one-root
  single phase is labelled by PIP; a pure component above its critical point is `SUPERCRITICAL_FLUID`. Phases are
  listed lightest first, then the crystals. Every fluid `PhaseAmounts` carries the `PhaseTraits` of its root
  (`CubicPhaseEvaluator.fill` stores Z, a, b, da/dT through `PhaseState.setIdentification`); the label sites still call
  the evaluation forms of `PhaseIdentification`.
- **Critical band** (`CriticalBand`): a pure fluid (one component at least 0.95 of the amount) inside one of the four
  reduced boxes of `pureBandBox`; a split with a tie line below `BAND_TIE_LINE`; a single mixture phase whose test met
  a stationary point within `BAND_STATIONARY_DISTANCE` of the feed; a failed split whose last iterate or seed was that
  short. No split is fabricated, and no single phase is reported stable when the test said unstable.
- **`Settings.DEFAULT`**: lnKTolerance 1e-10, maximumIterations 1000, accelerationCycle 5, coexistenceTolerance 1e-9,
  trivialDistance 1e-8, stability `TangentPlaneStability.Settings.DEFAULT`, substitutionLimit 50,
  maximumNewtonIterations 50, mergeTieLine 1e-8, mergeVolumeGap 1e-2, maximumOuterIterations 100, outerTolerance 1e-11.
- Public constants of `FluidTpEquilibrium`: `UNRESOLVED_STABILITY_BUDGET_FACTOR`, `SPECIFICATION_TOLERANCE`,
  `WARM_UV_EVALUATIONS`, `DEPOSITION_TOLERANCE`, `DEPOSITION_TRACE`, `SLOW_SUBSTITUTION_STEPS`, `SLOW_EIGENVALUE`,
  `FUGACITY_TOLERANCE`, `BAND_TIE_LINE`, `BAND_STATIONARY_DISTANCE`.
- **Grading** (`outsideData`): the package envelope (`PhaseDomain.covers`), the ideal-gas range of each carried
  species, each crystal record among the phases and an onset's crystal. Continuation past a range is the models' job
  (`ReferenceSpine`, `CrystalReference`, `ViscosityCorrelation`, `WaterShare`).

## Formats

The engine saves nothing. Its identity (`ThermoIdentity`) keys the network's saved state; numbers:
[FORMATS.md](../FORMATS.md).

## Tests and gates

| Change | Owner | Tests (`--tests` filter) |
|---|---|---|
| Any engine change | the package | `EngineDigestTest` (about 430 TP, PH, UV and onset requests on nine engines, one line of raw bits each, against `src/test/resources/thermo/engine-digest.txt`; record with `ICHEME_ENGINE_DIGEST_RECORD=true`, which writes `build/engine-digest.txt`) |
| Flash, Newton finish, merge, band, two liquids | `TwoPhaseFlash`, `CriticalBand`, `DryCore`, `pureBandBox`, `Settings` | `FluidTpEquilibriumTest`, `FluidTpEquilibriumP3Test` |
| Three fluid phases | `ThreeSlotFlash`, `DryCore` (`threeSlotFeed`, `threePhaseResult`) | `AqueousEquilibriumTest` (with `kernelEvaluationsRecorded`), `AqueousNetworkGatesTest`, `WetGasGatesTest` |
| Stability as the engine uses it | `DryCore.feedStability`, `TangentPlaneStability` | `TangentPlaneStabilityTest`, `WetGasGatesTest.naphthaBesideSteamCondensesAtItsOwnPartialPressure` |
| Water's share | `WaterShare`, `PackageBinding.derivedEvaluator`, `PhaseState` | `WaterShareTest`, `AqueousEquilibriumTest`, `SteamVirialTest` |
| Labels and traits | `PhaseIdentification`, `PhaseState.traits`, `CubicPhaseEvaluator.fill` | `PhaseTraitsTest`, `FluidTpEquilibriumP3Test` (`singlePhasesAreLabelledByThePhaseIdentificationParameter`, `hotGasesAreVapourLikeOverTheGrid`) |
| Carried crystals | `CarriedCrystals`, `DryCore.stabilityOf`, `PhaseContract.qualifies` | `CarriedCrystalsTest`, `NetworkPhaseEngineTest`, `CrystalDepositionIslandTest` |
| Failure kinds, refusals | `EquilibriumResult.FailureKind`, `FluidTpEquilibrium` | `EngineFailureKindTest` (no digest request ends `INTERNAL`) |
| A workspace's history | `Workspace.ownSplit`, the scratch pair | `AqueousEquilibriumTest.crystalAnswersDoNotDependOnWhatTheWorkspaceSolvedBefore` |
| Contracts, competitions | `PhaseContract`, `PackageBinding` | `FluidTpEquilibriumTest.waterChemistryAndQualifiedCrystalsFollowTheContract`, `SpinePackageEvaluatorTest` |
| Grades | `graded`, `outsideData`, `PhaseDomain` | `G3F9DataRangeBoundaryTest`, `FluidTpEquilibriumPhUvTest.aClosedVesselBeyondThePressureEnvelopeIsAnsweredExtrapolated` |
| Evaluator, identity | `CubicPhaseEvaluator`, `ThermoIdentity` | `CubicPhaseEvaluatorTest`, `ThermoIdentityTest` |
| Layers | `PackageBinding` the only reader of the catalog | `ThermoLayeringTest` (`onlyThePackageBindingOfTheEngineReadsTheCatalog`) |
| The network's calls | `NetworkPhaseEngine`, `FluidThermodynamics` | `NetworkPhaseEngineTest`, `SpineNetworkPathTest`, `CrystalFlushBudgetTest`, `LiquidFullBubblePointTest` |

Helpers: `PhaseTestSupport`, `If97Region2` (the full IF97 Region 2 residual, Region 4's saturation line, the B23
boundary). Qualification families `science/thermo/qualification` (G3F1..G3F10, G4F1, G4F2, G5F1..G5F6) are described in
[verification.md](verification.md). Only `test` runs these; `fluidScienceTest` matches `science.fluid.*` only.

## Traps

- `FluidTpEquilibrium` also answers PH, UV, crystals and onsets: it hands them to its stage objects.
- `hydrocarbonTotal` in the workspace is the whole feed's amount, water included; it stays the request's while a crystal
  solve flashes a reduced feed.
- `liquidState` and `vaporState` in the workspace are the split's products only (written by `finishSplit`,
  `slotOutcome`, `carriedSplit`, `beyondStandIn`); `ownSplit` says they are the current dry solve's own. Stages that
  evaluate a phase only to copy it use the scratch pair `vaporScratch`, `liquidScratch`.
- `FLUID_CARRYING_CRYSTALS` is neither `fluidOnly()` nor `crystalsCompete()`: test `carried()`.
- `PhaseKind` on a `PhaseState` is the root situation the evaluator met (one root gives `FLUID`); on a `PhaseAmounts`
  it is the equilibrium's label.
- `EXTRAPOLATED` is an answer, not a failure.
- `PackageBinding.contract` of a package without water qualifies the admitted crystals as well as fluid-only; the
  network uses `derivedContract` on its own evaluator.
- `IdealGasFunction` states a formation datum, but a spine-less package's `CoefficientIdealGas` sits on the network's
  sensible datum; a crystal anchored to such a family (CO2-I and ice in `icheme:refinery`) is on that datum too.

**Snapshot:** `d6f721b94`, 2026-11-15.
