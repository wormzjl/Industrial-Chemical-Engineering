# D-E5 report: the equilibrium engine in files, last slice (S01): the balance steps and the specification searches

Branch `worktree-agent-ad2d12a5ad4c5af66`, fast-forwarded to `380015b56`. Commits in order: `118a08cb0` stage 1
(`BalanceSteps`), `360e84495` stage 2 (`SpecificationSearch` with `BracketSearch`), `4876f5008` (the engine's unused
import and unused private method, a commit of their own), then this documents commit (this report, the engine chapter
of the code guide, the code guide README's count). Nothing was compiled or run: every claim is from reading the code
and from `node` scripts over the text. Step 3 (the searches' values) is not done (below).

## Summary

1. **Form: D-E3's and D-E4's, unchanged.** Each target is a package-private final class in `science/thermo/phase`
   holding final references to what it reads, built once at the end of the engine's constructor; constants only one
   stage reads move with it (private), shared ones stay in the facade, imported statically; engine call sites change
   by a qualifier only (`balanceSteps.pureCoexistence(..)`, `specificationSearch.solvePh(..)`, statics by class:
   `BalanceSteps.straddlesThreePhase(s)`). Two more objects per engine; nothing per request or per iterate.
2. **Stage 1 (`118a08cb0`), `BalanceSteps` (1308 lines)**: the steps TP leaves undetermined, 45 methods and 9
   constants (table below). `CrystalOnsets` takes `crystalStepTemperature`/`Pressure` from it. Engine 3363 to 2129.
3. **Stage 2 (`360e84495`), `SpecificationSearch` (1098) and `BracketSearch` (185)**: the PH and UV searches, 35
   methods and 11 constants; the nested `Search` as the top-level `BracketSearch` (one indent out, body unchanged) with
   the inspectors only it and the searches read. `Workspace` keeps its fields `search` and `inner`
   (`final BracketSearch search = new BracketSearch();`): same names, same initialisers at the same place, so each is
   created with the workspace, as before. Engine 2129 to 927.
4. **Unused members (`4876f5008`)**: the import `science.material.CrystalReference` (its import line was its only
   occurrence) and the private `species(int)` (no call `species(` in the class; `request.species()` is
   `EquilibriumRequest`'s). Engine 927 to 924.
5. **One declaration changed more than its access**: `outerAnswer` became `static` (stage 1). It reads no instance
   member (`R`, its arguments and `w` only), so its body is unchanged; `BalanceSteps` (`pureTriplePoint`,
   `tripleHold`) calls it by a static import and holds no reference to the search, which would have been a cycle
   between two holders (the search calls the balance steps).
6. **Callbacks**, each a plain call of a method that stays, or a static: `BalanceSteps` to the engine's `converged`,
   `single`, static `diagnostics`, static `SpecificationSearch.outerAnswer`, `crystalEquilibrium.solidCoverage`,
   `criticalBand`, `crystalSet` (none calls a balance step); `SpecificationSearch` to the engine's `evaluateTp` and
   static `crystalCeiling` (`evaluateTp` reaches the dry core and the crystal solves, never a search); `CrystalOnsets`
   to `BalanceSteps.crystalStepTemperature`/`Pressure` (which call nothing outside it). No instance cycle.
7. **The facade**, `FluidTpEquilibrium`, 924 lines: the request entry points, the checks, `evaluateTp`, the grading,
   the onsets' public entries, the answer builders, two shared inspectors, `Workspace`, `Feed`, `Settings` (list below).
8. **Lines**: engine 3363 to 924; `CrystalOnsets` 277 to 280. The engine and the three new files 3515 lines, with the
   onsets 3795, against 3640 before: the +155 are headers, holder fields, constructors and class documents. The
   package 27 to 30 files.
9. **Checks run here** (scripts in the worktree's `tmp/d-e5/`, section "The checks, in detail"): the moved text line
   for line and method by method, access and resolution of every member, type and import, the doc links, greps of
   every moved and widened name. Nothing was compiled or run. **Step 3** (the searches' values) is not done: it
   changes bodies beyond qualifiers and buys no value semantics (section below).

## What moved

| Stage | Methods | Constants |
|---|---|---|
| 1, `BalanceSteps` | `pureCoexistence`, `coexistenceTemperature`, `coexistencePressure`; `holdsKind`; `pureCrystal`, `majorSpecies`, `traces`, `binaryCrystal`, `binaryPartner`; `pureCrystalStep`, `stepFluid`, `crystalStepHold`, `crystalStepTemperature`, `crystalStepPressure`, `cold`; `pureTriplePoint`, `insideTriangle`, `tripleHold`, `nearTriangle`, both `tripleBalances`, `tripleAmounts`, `crystalClassification`; `straddlesThreePhase`, `binaryThreePhase`, `leverStep`, `order`, `compositionDistance`, `scaled`, `pair`, `evaluateBinary`, `binaryFluid`, `straddlesFluidThree`, `binaryFluidThreePhase`, `logRatio`, `conserve`, `evaluateRatio`, `fluidThreeResidual`, `threePhaseResidual` | `COEXISTENCE_ITERATIONS`, `THREE_PHASE_ITERATIONS`, `THREE_PHASE_TOLERANCE`, `TRACE_FRACTION`, `NEAR_PURE_TRACES`, `STEP_FLUID_TRACES`, `TRIANGLE_SCREEN`, `NEAR_PURE_ITERATIONS`, `LEVER_MATCH` |
| 2, `SpecificationSearch` | from `searchWindow` to `outsideWindow` in the old order (the searches, starts, slopes, Newton and nested UV, the inner search, the answers and failures, `outerAnswer` static), plus `samePhases`, `foundOrHeld`, `threePhaseDue`, `fluidThreeDue` | the search constants (retreats, step caps, difference step, Newton UV budget, line-search halvings, the inner search's codes and looseness, the three-phase bracket) |
| 2, `BracketSearch` | the nested `Search` whole, with its fields; `holdsSolid` (package-private), `hydrocarbonFluid`, `oneFluid`, `sameBesideFluid` (private) | none |

## The facade's contents (`FluidTpEquilibrium`, 924 lines)

The class document; the constants (public: `SPECIFICATION_TOLERANCE`, `WARM_UV_EVALUATIONS`, `DEPOSITION_TOLERANCE`,
`DEPOSITION_TRACE`, `UNRESOLVED_STABILITY_BUDGET_FACTOR`, `SLOW_SUBSTITUTION_STEPS`, `SLOW_EIGENVALUE`,
`FUGACITY_TOLERANCE`, `BAND_TIE_LINE`, `BAND_STATIONARY_DISTANCE`; shared package-private: `CONVERGED`, `R`, `ARMIJO`,
`NEWTON_HALVINGS`, `SHIFTS_FROM_1E8`/`1E13`, `FRACTION_TO_BOUNDARY`, `GIBBS_ROUNDOFF`, `PRESSURE_STEP_CAP`,
`FLUID_ONLY_NOTE`, `SINGLE`..`THREE`); `Settings`; the fields and the constructor; `anchoredCrystal`, the accessors,
`newWorkspace`; `Feed`; `Workspace`; `tp`, `ph`, `uv` (both); `implementationChecks`, `outsideBox`, `evaluateTp`;
`graded`, `outsideData`, `crystalNote`, `note`, `crystalCeiling`; `fluidChemicalPotential`, `holdsLiquid`; `single`;
the four onset entries; `contractChecks`; `shifts`; `pureBandBox`; `expand`, `converged` (both), `unsupported`,
`diagnostics`, `own`, `requireSpecification`.

## The checks, in detail

- `verify.mjs`, per commit and over the slice from `380015b56` (2484 lines removed, 2639 added): every removed line
  reappears among the added after dropping leading modifiers, the holder and class qualifiers and `{@link X#`
  prefixes, renaming `BracketSearch` to `Search` and collapsing whitespace; braces, parentheses and brackets balance;
  every line ends in CRLF; the unmatched lines are the table "Edits that are not a pure move".
- `bodies.mjs`: each of the 130 methods of the base engine that still exist (`species(int)` was removed), found again
  by declaration, has its lines in the same order after the same normalisation: 129 identical; the one that differs
  is the engine's constructor, by its new holder lines.
- `access.mjs`: every `w.x`, `s.x`, `engine.x`, `balanceSteps.x` and `X.y` of a stage file names a member that is not
  private where declared; every unqualified call is declared in the file or statically imported; every ALL_CAPS name
  is declared or imported; every static import names an accessible member that no member of the file shadows; every
  import is used.
- `types.mjs`: every capitalised type name resolves (the package, an import, `java.lang`).
- `links.mjs`: no `{@link}` names a member that moved away; the hits are the enum constant
  `WaterParticipation#IN_EQUATION_OF_STATE` and the two `{@link #solve3}` that dangled before, now in `BalanceSteps`.
- Greps of every moved method, constant, type and widened member over `src/main`, `src/test`, `src/fluidGameTest` and
  `tools/**/*.java`: the hits are other classes' own members of the same name (`majorSpecies` in `VesselCrystals`,
  `logRatio`, `cubicPressure`, `samePhases`, `energyResidual` and the like); no test names `FluidTpEquilibrium.Search`
  or another moved type, so no test changed.
- `node tools/code-guide/map.mjs --readme`, then `--check`: passes (the package count 27 to 30).

## Construction order

`FluidTpEquilibrium`'s constructor, after `stability`, `convergedCoverage` (and `translations`, assigned before them):

1. `crystalSet`, 2. `criticalBand`, 3. `dryCore` (it builds `carried`, `twoPhaseFlash`, `threeSlotFlash`), 4.
   `crystalEquilibrium`, as in D-E4;
5. `balanceSteps = new BalanceSteps(this, crystalEquilibrium, contract, evaluator, kernel, criticalBand, crystalSet,
   count, basis, basisOfComponent, inEquationOfState, convergedCoverage)`;
6. `crystalOnsets = new CrystalOnsets(this, crystalEquilibrium, balanceSteps, contract, evaluator)`;
7. `specificationSearch = new SpecificationSearch(this, balanceSteps, contract, evaluator, kernel, settings, count,
   translations, crystalSet, criticalBand)`.

Why: the balance steps read the crystal equilibrium (`solidCoverage`); the onsets and the searches read the balance
steps; nothing reads the searches at construction. Why javac accepts it: every holder field of the engine is a blank
final assigned exactly once, and each read of one by simple name (`crystalEquilibrium`, `balanceSteps`, `criticalBand`,
`crystalSet`, `kernel`, `count`, `basis`, `basisOfComponent`, `inEquationOfState`, `convergedCoverage`, `translations`)
comes after its assignment (JLS 16); `contract`, `evaluator`, `settings` are the constructor's parameters, the same
references the fields hold. `this` reaches the three constructors that only store it; no stage runs before the
engine's constructor returns (a `Workspace` exists only after it).

## Members widened (private to package-private), with who reads them

| Member | Read by |
|---|---|
| Engine `outerAnswer` (also made static, stage 1; moved to `SpecificationSearch` at stage 2, package-private static there) | `BalanceSteps.pureTriplePoint`, `tripleHold` (static import) |
| `Search`: the class (stage 1); `lower`, `upper`, `lowerResidual`, `upperResidual` (one declaration), `lowerResult`, `upperResult` (stage 1); `minimum`, `maximum`, `threePhaseAttempts` (stage 2, as `BracketSearch`) | `BalanceSteps` (the bracket's ends and results); `SpecificationSearch` (all of them) |
| `Workspace`, stage 1: `search`, `inner`; `outerEvaluations`, `outerKernel`, `outerDerivative` (one declaration); `coexistenceX`, `coexistenceResidual`, `bestT`, `bestResidual`, `innerPressure` (one declaration); `pure`, `stepFluid`, `pureState`, `threeLiquid`, `threeVapour` | `BalanceSteps` (`outerKernel`, `coexistence*`, the scratch states and arrays, `search`/`inner` in `pureCrystalStep`); the rest of the two shared declarations by `SpecificationSearch` from stage 2 |
| `Workspace`, stage 2: `total`, `minimumT`, `maximumT`, `minimumP`, `maximumP`, `startMinimumT`, `startMaximumT`, `slope`, `jacobian`, `jacobianValid`, `innerResult`, `innerCoexistence`, `innerFailure`, `innerLooseness`, `innerSlope`, `innerLoose`, `innerSingle`, `innerT1`, `innerY1`, `innerT2`, `innerY2`, `solidSlope` | `SpecificationSearch` |

Moved methods called across files are package-private: `BalanceSteps.pureCoexistence`, `pureCrystalStep`,
`pureCrystal`, `binaryCrystal`, `binaryFluid`, `binaryThreePhase`, `binaryFluidThreePhase`, `leverStep`,
`pureTriplePoint`, static `nearTriangle`, `straddlesThreePhase`, `straddlesFluidThree`, `crystalStepTemperature`,
`crystalStepPressure` (the last two were already); `SpecificationSearch.searchWindow`, `solvePh`, `solveUv`, static
`outerAnswer`; static `BracketSearch.holdsSolid`. Everything else moved stays private. Nothing became public. Every
`Workspace` field but `owner` is now package-private; its public surface is unchanged (the public class, its private
constructor, `newWorkspace()`; it has no public member). `Feed` and `Settings` are untouched; `pureBandBox` and the
four onset functions stay public on the engine.

## Edits that are not a pure move (the script's unmatched lines, each read by hand)

| Commit | Where (at the tip) | Edit |
|---|---|---|
| 1 | `FluidTpEquilibrium.java:254-255, 304-306` | the `balanceSteps` field and its construction; `CrystalOnsets` built with it |
| 1 | `FluidTpEquilibrium.java:734-736` | the section comment above the inspectors (rewritten again at stage 2) |
| 1 | engine imports | `SmallDenseSolve`, `ArrayList`, `Arrays` removed (only the balance steps used them) |
| 1 | `CrystalOnsets.java:27-31, 38, 42-46, 106, 110` | class document, the `balanceSteps` field and constructor parameter, `engine.crystalStep*` to `balanceSteps.crystalStep*` |
| 1 | `BalanceSteps.java:1-106` | header, imports, class document, holder fields and constructor |
| 1, 2 | signatures' continuation lines (`BalanceSteps.java:121, 334, 745, 1087`; engine `outerAnswer`) | re-indented after `private` went or `static` came (whitespace) |
| 1 | `BalanceSteps.java:622`; engine `fluidThreeDue` document | `{@link FluidTpEquilibrium#SPECIFICATION_TOLERANCE}`, `{@link BalanceSteps#binaryFluid}` |
| 2 | `FluidTpEquilibrium.java:141` | `PRESSURE_STEP_CAP`'s document loses "; in {@code ln T}" (that half is `TEMPERATURE_STEP_CAP`'s, which moved) |
| 2 | `SpecificationSearch.java:1-82` | header, imports, class document, `TEMPERATURE_STEP_CAP`'s own document, holder fields and constructor |
| 2 | `BracketSearch.java:1-17, 145` | header, the class document's second paragraph, `final class BracketSearch`, the inspectors' section comment |
| 2 | `FluidTpEquilibrium.java:258-259, 307-308, 423, 561-562, 593-594` | the `specificationSearch` field and its construction; `Workspace`'s `{@link SpecificationSearch#searchWindow}`; `ph`, `uv` call through the holder |
| 2 | `BalanceSteps.java:7, 35-37` | `outerAnswer` imported from `SpecificationSearch`; `FluidTpEquilibrium.Search` import gone; the class document's callback sentence |
| 2 | every `Search s` of the moved code (7 in the searches, 6 in the balance steps) | `BracketSearch s` (the type token) |

## What stayed, and why

- **`single`** (an answer builder; `CrystalEquilibrium.twoCrystals` and three balance-step sites call it) and the other
  builders `converged`, `expand`, `unsupported`, `diagnostics`: the facade keeps the builders (D-E3's rule).
- **`fluidChemicalPotential`, `holdsLiquid`**: shared by `CrystalEquilibrium` and `CrystalOnsets`; the other
  inspectors went with their only users (`holdsKind` to `BalanceSteps`; `holdsSolid`, `hydrocarbonFluid`, `oneFluid`,
  `sameBesideFluid` to `BracketSearch`; `samePhases` to `SpecificationSearch`).
- **`crystalCeiling`**: the grading and onset document name it; the searches and the onsets import it statically.
- **`PRESSURE_STEP_CAP`**: `CrystalOnsets` and the searches share it. **`SPECIFICATION_TOLERANCE`**,
  **`WARM_UV_EVALUATIONS`**: public (a test reads the first; the `uv` document links the second).
- **`Workspace`, `Feed`, `Settings`** stay nested (the brief); `Workspace` keeps holding the two bracket searches.
- **`PhaseIdentification`'s import** in the engine: only the class document's two `{@link}`s use it (javac does not
  mind; javadoc would). The two dangling `{@link #solve3}` moved with `insideTriangle`'s and `tripleBalances`' documents
  into `BalanceSteps` (619, 669), as they were.

## Step 3 (the searches' values): not done

What it would be: `coexistenceX`, `coexistenceResidual` (the balance steps' side of a step for `closedPh`, `closedInner`,
`solvePh`, `innerUv`) and `innerResult`, `innerFailure` (the pressure search's outcome for `nestedUv`, `solveUv`) as a
value. The brief allows it only as a workspace-owned holder passed as a value with no allocation per iterate and no
change of what NaN means. A holder owned by the workspace and passed down is the same workspace state under another
name: every writer (`pureCoexistence`, `pureCrystalStep`, `binaryThreePhase`, `binaryFluidThreePhase`, `closedPh`,
`closedInner`, `innerUv`, `found`, `foundOrHeld`) and reader (about 30 sites in the two searches) would change from
`w.coexistenceX` to an argument or `w.side.x`, and every balance-step signature would take one more parameter. That
is a change of method bodies beyond qualifiers in an unbuilt slice whose proof is the digest, and it buys no value
semantics: the holder still carries NaN for "no side" between calls. A returned record per closed bracket would be a
value but allocates per PH and UV iterate, which the brief forbids. So it stays as D-E2 left it, now in two files:
the four fields are package-private `Workspace` fields that `BalanceSteps` writes and `SpecificationSearch` reads.

## Tests for the main thread

One run, `test` task (every class is in the everyday group; `science/thermo/phase` is in no gate group of
`build.gradle`). D-E4's list, unchanged (the classes that build the engine, by grep of the test sources, are all in
it):

`./gradlew test --tests "*.EngineDigestTest" --tests "*.FluidTpEquilibriumTest" --tests "*.FluidTpEquilibriumP3Test" --tests "*.FluidTpEquilibriumPhUvTest" --tests "*.AqueousEquilibriumTest" --tests "*.GasSolidEquilibriumTest" --tests "*.TangentPlaneStabilityTest" --tests "*.NearPureCrystalStepTest" --tests "*.RefineryCarbonDioxideCrystalTest" --tests "*.SolidCarbonDioxideQualificationTest" --tests "*.CarriedCrystalsTest" --tests "*.NetworkPhaseEngineTest" --tests "*.CrystalDepositionIslandTest" --tests "*.CrystalFlushBudgetTest" --tests "*.WaterShareTest" --tests "*.LiquidFullBubblePointTest" --tests "*.SolidPhaseEvaluatorTest" --tests "*.SpinePackageEvaluatorTest" --tests "com.wormzjl.icheme.science.thermo.qualification.*" --tests "*.ThermoLayeringTest" --tests "*.LayeringTest"`

- The compile of `main` and `test`: the first proof (nothing was compiled here). No test source changed.
- `EngineDigestTest` (reference unchanged): every stage moved here is on its path. The searches and the bracket on
  every PH and UV line (the sweeps, the round trips, the warm-started UV); the balance steps on the lines answered
  from a balance (`coexistenceFromBalance`: D-E0's pure N2 coexistence, D21 row A, PH across sublimation, the triple
  point's triangle, the steps from the balance on `pilot_aqueous`; which balance step each such line takes I did not
  trace), and their screens (`pureCrystal`, `binaryCrystal`, `binaryFluid`, the straddle tests) on every PH and UV
  line with crystals or water in the cubic; `crystalStepTemperature`/`Pressure` also on the onset lines (the
  partial-pressure approximation). Unchanged proves no operation and no kernel evaluation moved. If it fails, bisect
  by the two stage commits (each is meant to compile on its own); `4876f5008` removes only unused text.
- `FluidTpEquilibriumPhUvTest` (the searches, the balance steps, the extrapolated grade), `NearPureCrystalStepTest`
  (D21 steps and the triple point), `FluidTpEquilibriumP3Test` (reused-workspace determinism, the band),
  `AqueousEquilibriumTest`, `GasSolidEquilibriumTest` (onsets through
  `BalanceSteps`), the qualification package (`G3F8` PH/UV conservation, `G3F9` data-range grades, `G4`/`G5` onsets)
  and the rest: the engine's own expectations and its callers, as in D-E4.
- `ThermoLayeringTest`, `LayeringTest`: the three new files name only `science.thermo.phase`, `science.thermo`
  (`PengRobinsonKernel`, `PhaseIdentification`, `NumericalBox`, `IdealGasFunction`) and `science.math`
  (`DeterministicMath`, `SmallDenseSolve`); none reads `MaterialCatalog`; the engine no longer imports
  `science.material` at all.
- Chain-100 exact (`FluidSolverRegressionTest`, task `fluidSolverRegression`) and the fluid GameTests (vessel UV with
  the warm start) load the engine: the batch's final lane.

## Uncertain points (not verified by a compiler)

- **The static `outerAnswer`**: `SpecificationSearch.java:1002` declares `static EquilibriumResult outerAnswer(..)`;
  its body reads `R` (static import), its arguments and `w` only (read line by line). Its callers inside the class are
  instance methods (an unqualified static call is legal); `BalanceSteps.java:7` imports it statically and calls it at
  614 and 650. Bits: `invokestatic` in place of `invokevirtual`, no operand changes.
- **Static imports between the new classes**: `SpecificationSearch.java:3` imports the package-private static
  `BracketSearch.holdsSolid` (`BracketSearch.java:147`); `BalanceSteps.java:7` imports `SpecificationSearch.outerAnswer`
  while `SpecificationSearch` names `BalanceSteps`: cyclic imports between classes of one package are legal. No
  importing class declares a member of an imported name (checked by script).
- **A package-private class as the type of fields of a public nested class**: `FluidTpEquilibrium.java:425-426`
  (`final BracketSearch search = new BracketSearch();`); legal (the fields are package-private), and `BracketSearch`'s
  implicit constructor has the class's package access.
- **Class-qualified statics** (`BalanceSteps.straddlesThreePhase(s)` at `SpecificationSearch.java:381, 968, 1081`,
  `straddlesFluidThree` at 386, 973, 1091, `nearTriangle` at 408): static methods of a package-private class, called
  from the same package.
- **`this` in the constructor**: `FluidTpEquilibrium.java:304-308` hand `this` to `BalanceSteps`, `CrystalOnsets` and
  `SpecificationSearch`, which only store it.
- **Bits, by reasoning**: each holder field is the same reference or value as the engine's (`translations` is the
  array `evaluator.translations()` returned once, `basisOfComponent` the engine's array, `convergedCoverage` the
  engine's object, so `CrystalEquilibrium.solidCoverage`'s `fluid == convergedCoverage` still holds for the balance
  steps' answers); every `||` chain of the searches keeps its operands in order with one more field load each; the
  bracket searches are created by the same field initialisers at the same place in `Workspace`'s field order.
- **Shadowing**: `BracketSearch.reset(double minimum, double maximum)` assigns `this.minimum = minimum` as before;
  `record` keeps its local `best` beside the method `best()`; `BalanceSteps` keeps the local `traces` beside the method
  `traces(..)` and the local `cold` beside `cold(..)`; all as in the engine.
- **Doc links** to private members of another class (`{@link SpecificationSearch#searchWindow}` is package-private;
  `{@link CarriedCrystals#beyondStandIn}` and the like, as in D-E4) matter only to `javadoc`, which the build does
  not run.
