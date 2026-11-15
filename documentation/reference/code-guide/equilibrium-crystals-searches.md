# Equilibrium engine: crystals, onsets, PH and UV searches, balance steps

Up: [code guide](README.md) · the engine's request, contract and dry core: [equilibrium-engine.md](equilibrium-engine.md)

## Read this when

- A vessel's ice or dry ice (CO2-I) forms at the wrong temperature or amount, or a frost, freezing or deposition onset
  is wrong.
- An island holds with `HELD (phases)` and the detail names `CRYSTAL_PHASES` or `CRYSTAL_STEP`.
- A PH or UV search does not converge, closes at a box bound, or its answer comes from a balance
  (`coexistenceFromBalance`).
- You add a crystal to a package's competition or change the deposition, onset or search constants.

## What it does

A pure crystal stands beside the fluid wherever a species' chemical potential in the fluid exceeds the crystal's. The
engine answers a crystal competition at TP by first answering the fluid, then each crystal by its driving force
D = (mu_i − mu_s)/(RT), solving the solid amount so that the fluid the crystal leaves has the crystal's chemical
potential. Onsets (frost point, liquidus, three-phase point) are bracketed searches on D. A PH request (a junction) is a
search in T at fixed P; a UV request (a closed vessel) a search in (T, P). A step that TP leaves undetermined (pure
coexistence, a pure crystal step, a triple point, a binary's three-phase step) is split from the energy and volume
balance. All of these are stage objects of `FluidTpEquilibrium`, built once by its constructor; they call back its
`evaluateTp`, its answer builders and its grading ([equilibrium-engine.md](equilibrium-engine.md)).

## Class map

**`science/thermo/phase/`** ([source](../../../src/main/java/com/wormzjl/icheme/science/thermo/phase/)): the classes of
this chapter. Lines at the snapshot.

| Class | Role | Lines |
|---|---|---|
| [`CrystalSet`](../../../src/main/java/com/wormzjl/icheme/science/thermo/phase/CrystalSet.java) | The engine's crystals: each evaluator checked and anchored to this family (`crystal`, `liquidShare`), the family's own triple point and its UV triangle (`familyTriplePoint`, `tripleTriangle`); `crystals`, `crystalSpecies`, `crystalCoverages`, `crystalById`; record `Crystal` | 207 |
| [`CrystalEquilibrium`](../../../src/main/java/com/wormzjl/icheme/science/thermo/phase/CrystalEquilibrium.java) | Crystals at TP: `solveWithCrystals` (the fluid answer on the dry core, each crystal by fugacity, a pure feed's complete deposition), `splitChemicalPotentialsUsable`, the vapour-solid solve `depositFromVapour` (`depositionResidual`), the general solve `depositFromFluid` (`singlePhaseSlope`), `secondCrystal`, `twoCrystals` on `reduceFeed`'s pair feed; coverage (`solidCoverage`), `assessed`, `solidAnswer`, the hold `crystalPhasesHold` | 763 |
| [`CrystalOnsets`](../../../src/main/java/com/wormzjl/icheme/science/thermo/phase/CrystalOnsets.java) | The onset searches: `onset` (bracketed on D in 1/T or ln P, the partial-pressure approximation, the ideal-solubility start), `singlePhaseDrive` (freezing and one-phase frost), `fluidAnswer`; `ONSET_EVALUATIONS` | 280 |
| [`CrystalPhaseEvaluator`](../../../src/main/java/com/wormzjl/icheme/science/thermo/phase/CrystalPhaseEvaluator.java) | `SolidPhaseEvaluator` of a crystal record: `of(record, idealGas)`, `anchor(CubicPhaseEvaluator)` on the family's pure liquid at the record's triple point, `anchored(...)` giving the immutable `Anchored` view (a `CrystalFugacity`; ice carries the liquid's water share); caches a `volatile Anchored last` | 243 |
| [`SolidPhaseEvaluator`](../../../src/main/java/com/wormzjl/icheme/science/thermo/phase/SolidPhaseEvaluator.java), [`CrystalFugacity`](../../../src/main/java/com/wormzjl/icheme/science/thermo/phase/CrystalFugacity.java) | The solid contract with its `TriplePointAnchor`; a crystal's ln(f_s/P) | 52, 12 |
| [`DepositionOnset`](../../../src/main/java/com/wormzjl/icheme/science/thermo/phase/DepositionOnset.java) | An onset answer (frost point, liquidus or three-phase point); `approximation` for diagnostics only | 63 |
| [`SpecificationSearch`](../../../src/main/java/com/wormzjl/icheme/science/thermo/phase/SpecificationSearch.java) | The PH and UV searches: `searchWindow`, `outerTp`, the starts (`idealTemperature`, `singlePhaseTemperature`, `cubicPressure`) and slopes (`dryDerivatives`), `solvePh`, `closedPh`, `solveUv`, `newtonUv` (`analyticJacobian`, `differenceJacobian`), `nestedUv`, `innerUv`, `closedInner` (`found`, `foundOrHeld`), `threePhaseDue`, `fluidThreeDue`, `samePhases`; answers and failures (`outerAnswer`, `searchFailure`, `outsideWindow`) | 1111 |
| [`BracketSearch`](../../../src/main/java/com/wormzjl/icheme/science/thermo/phase/BracketSearch.java) | The bracket of one search: the latest point on each side and their answers, `record`, `recordSide`, `next` (the step inside the bracket, else its midpoint; bisection after two stalls), `best`, the straddle tests (`straddlesPureCoexistence`, `straddlesCrystalStep`, `endHoldsSolid`, `threePhaseAttempts`) and their inspectors (`holdsSolid`, `oneFluid`, `sameBesideFluid`). Two per workspace (`search`, `inner`), reset at a search's start | 185 |
| [`BalanceSteps`](../../../src/main/java/com/wormzjl/icheme/science/thermo/phase/BalanceSteps.java) | Steps answered from the balances: `pureCoexistence` (`coexistenceTemperature`, `coexistencePressure`); a pure or near-pure feed's crystal steps (`pureCrystal`, `majorSpecies`, `traces`, `pureCrystalStep`, `stepFluid`, `crystalStepHold`, `crystalStepTemperature`, `crystalStepPressure`); the triple point in UV (`pureTriplePoint`, `insideTriangle`, `nearTriangle`, `tripleHold`); a binary's vapour-liquid-crystal step (`binaryCrystal`, `binaryPartner`, `straddlesThreePhase`, `binaryThreePhase`, `threePhaseResidual`); the lever rule (`leverStep`); a binary's vapour-liquid-liquid step (`binaryFluid`, `straddlesFluidThree`, `binaryFluidThreePhase`, `fluidThreeResidual`); `holdsKind`. Each returns the answer, or null with the step's end in the workspace's `coexistenceX` and `coexistenceResidual` | 1312 |

## Main flow

**Crystals at TP** (`evaluateTp` with a crystal competition):

1. `CrystalEquilibrium.solveWithCrystals`: the fluid answer of the feed (`DryCore.solveDry`).
2. For each crystal, D = (mu_i − mu_s)/(RT) against the fluid answer (`fluidChemicalPotential`). Above
   `DEPOSITION_TOLERANCE`: `depositFromVapour` (Newton on the vapour-solid residual), else `depositFromFluid` (every
   iterate a re-flash of the reduced feed with the stability test).
3. A second crystal: `secondCrystal`, then `twoCrystals` on the pair feed (`reduceFeed`).
4. `solidAnswer` with `solidCoverage`; `assessed` grades it; `crystalPhasesHold` where the engine cannot represent the
   state.

**Onsets** (`depositionTemperature`, `frostTemperature`, `freezingTemperature`, `depositionPressure` on the façade):
the façade's checks, then `CrystalOnsets.onset` or `singlePhaseDrive`, each fluid answer from `fluidAnswer`
(`evaluateTp` with the crystals left out), then `graded`.

**PH** (`ph`): the pressure against the box, `searchWindow`, `solvePh` over `outerTp`; a bracket that straddles or
closes on a step goes to `BalanceSteps`; `graded`.

**UV** (`uv`): `searchWindow`, `solveUv`: `newtonUv` first, else `nestedUv` with `innerUv` per temperature;
`BalanceSteps` for a step; `graded`. The warm start `uv(request, workspace, hintT, hintP)` allows `WARM_UV_EVALUATIONS`
TP answers before the cold search; the network's vessel crystal refresh uses it.

## Contracts

- **Anchoring** (once, at construction): g_s(T_t, P_t) = mu_L and s_s = (h_L − mu_L)/T_t − dH_fus/T_t on this
  evaluator's pure liquid; for a Gibbs-function record this fixes g0 and g1. The crystal's ln phi = (g_s − g_ig(T, P))/(RT)
  against the species' ideal gas, so its chemical potential compares directly with a fluid phase's. Ice's anchor carries
  the liquid's water share (`liquidShare`), so ice and the cubic's liquid meet at the triple point on the model's own
  terms. A crystal anchored to a spine-less family takes its formation offset 0 (`PackageBinding.crystal`).
- With water in the cubic a crystal stands beside two liquids or three fluid phases too (`LIQUID_LIQUID_SOLID`,
  `VAPOR_LIQUID_LIQUID_SOLID`); ice forms from the water-rich liquid at the temperature its composition sets, beside
  another crystal too (`twoCrystals`).
- A fluid answer or re-flash that failed with a product proved unstable (`UNSTABLE_PRODUCT`, also inside the band)
  steers the crystal by its split's chemical potentials (`splitChemicalPotentialsUsable`) only when the two products in
  the workspace are that same dry solve's own split (`Workspace.ownSplit`) and agree to 1e-6 RT. A three-slot audit
  failure writes no split, so it ends as the fluid's failure.
- A pure feed, or a species below `DEPOSITION_TRACE` in the fluid, deposits or freezes completely (`SOLID_PRESENT`).
- **Near-pure feeds**: a feed whose other species total at most 1e-5 of its largest. PH and UV answer its major
  species' crystal steps as a pure feed's, the traces carried in the fluid up to 1e-4 of it; a step this rule does not
  answer is `CRYSTAL_STEP`.
- **`CRYSTAL_PHASES`** (`UNSUPPORTED`, `NOT_IMPLEMENTED`): three crystals, two crystals whose fluid is not answered, a
  fluid with a product proved unstable beside a crystal, and, on a contract without water in the cubic, two liquids or
  three fluid phases beside a crystal. Only under a crystal competition: the network meets it at a vessel's refresh,
  never in a step.
- **Crystal ceiling** (`crystalCeiling`): the lowest record maximum temperature of the competition. It is not a data
  range: crystals cease to exist above it, onset searches stay below it, and a PH or UV start that fails restarts there.
- **Searches**: the window is the numerical box. A search starts inside the overlap of the data of every model the
  material uses (the envelope and the carried species' ideal gases) and moves out only as far as the specification
  asks. A target inside a step no balance answers is `NOT_CONVERGED`; a bracket that closes at the box's pressure bound
  with the energy still short is `NOT_CONVERGED` naming that bound. A search's answer copies `failure` and
  `failureKind` from its TP answer (`outerAnswer`).
- **Balance answers** (`Diagnostics.coexistenceFromBalance()`): target = (1 − β) x_1 + β x_2 once a bracket straddles the
  jump. They meet their target by construction: a balance that does not hold leaves no residual; only a round trip shows
  it.
- The network asks for the competition only at a vessel's refresh, where crystal mass moves; its steps carry the
  crystals instead ([equilibrium-engine.md](equilibrium-engine.md), "Carried crystals").

## Laws and constants

- **PH** (`solvePh`): a bracketed search in T at fixed P from the ideal-gas temperature of the enthalpy; Newton with the
  phase's c_p for one phase, a secant between answers with the same phases otherwise, bisection when two steps fail to
  halve the bracket's smaller residual.
- **UV** (`solveUv`): Newton on (ln T, ln P) for ((U − U_spec)/(NRT), ln(V/V_spec)) with the analytic Jacobian of one
  phase, else differences and Broyden's update, and a halving line search. It starts where the feed as one cubic phase
  has U_spec at V_spec and stops after 30 TP answers unless its last step cut the residual fourfold. Then the nested
  search in T at the specified volume, each temperature solving V(P) = V_spec in ln P (`innerUv`).
- **Convergence**: a step or bracket within `outerTolerance`, the specification within `SPECIFICATION_TOLERANCE` of NRT
  and in ln V, at most `maximumOuterIterations` TP answers per search.
- **Onsets**: at most `ONSET_EVALUATIONS` fluid answers each. `DepositionOnset.approximation` (the partial-pressure
  estimate) is never the answer.
- Constants: `DEPOSITION_TOLERANCE`, `DEPOSITION_TRACE`, `WARM_UV_EVALUATIONS`, `SPECIFICATION_TOLERANCE` on
  `FluidTpEquilibrium`; the near-pure, trace, three-phase and lever constants on `BalanceSteps`; the deposition and
  two-crystal constants on `CrystalEquilibrium`; the search constants on `SpecificationSearch`.

## Formats

None. Crystal identities in the engine's `ThermoIdentity` (family ids `crystal_segments_triple_point_anchored_v1`,
`crystal_jaeger_span_2012_triple_point_anchored_v1`): [equilibrium-engine.md](equilibrium-engine.md).

## Tests and gates

| Change | Owner | Tests (`--tests` filter) |
|---|---|---|
| Crystal competition, deposition, ice | `CrystalEquilibrium`, `CrystalSet`, `CrystalPhaseEvaluator`; data `crystals/ice_ih.json` | `GasSolidEquilibriumTest` (with `theCrystalCompetitionIsQualifiedAsAWholeSet`), `CrystalPhaseEvaluatorTest`, `SolidCarbonDioxideQualificationTest`, `RefineryCarbonDioxideCrystalTest`, `AqueousEquilibriumTest`, qualification `G4*`, `G5*` |
| Pure and near-pure crystal steps, the triple point | `BalanceSteps` (`pureCrystalStep`, `pureTriplePoint`, `binaryThreePhase`) | `NearPureCrystalStepTest`, `GasSolidEquilibriumTest` |
| Onsets | `CrystalOnsets`, `DepositionOnset`, the façade's entries | `GasSolidEquilibriumTest`, `G4F1`, `G4F2`, `G5F2` |
| PH and UV searches, balance steps | `SpecificationSearch`, `BracketSearch`, `BalanceSteps` (`pureCoexistence`, `binaryFluidThreePhase`, `leverStep`) | `FluidTpEquilibriumPhUvTest`, `NetworkPhaseEngineTest`, `G3F8ConservationAndDerivativeTest` |
| Solid contract | `SolidPhaseEvaluator`, `CrystalFugacity` | `SolidPhaseEvaluatorTest`, `CrystalPhaseEvaluatorTest` |
| Any of them | the package | `EngineDigestTest` (the digest of about 430 requests) |
| The network's crystal refresh | `NetworkPhaseEngine`, `VesselCrystals` | `CrystalDepositionIslandTest`, `CrystalFlushBudgetTest` |

Helper: `SolidCarbonDioxideReferences`; fixtures `src/test/resources/science/thermo/coolprop/`,
`gerg2008/pilot-binaries.json`, `solid-co2-holdouts/*.tsv`.

## Traps

- `CRYSTAL_PHASES` is `UNSUPPORTED`, not `NOT_CONVERGED`; `CRYSTAL_STEP` is `NOT_CONVERGED`.
- A crystal leaves the composition block of `PhaseDerivatives` unfilled, and nothing flags it.
- The searches' out-parameters are workspace fields (`coexistenceX`, `coexistenceResidual`), not return values.
- `BracketSearch.holdsSolid` is read by the searches too: a change to it moves both.
- The crystal ceiling is the competition's lowest record maximum, not a data range: an answer above it has no crystal,
  not an extrapolated one.

**Snapshot:** `d6f721b94`, 2026-11-15.
