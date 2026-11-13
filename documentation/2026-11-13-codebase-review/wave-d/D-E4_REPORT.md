# D-E4 report: the equilibrium engine in files, second slice (S01): the dry core, the carried crystals, the onsets, the crystal solves

Branch `worktree-agent-ad8df42a0335c1c71`, fast-forwarded to `c931cd856`. Commits in order: `8d70fb734` stage 1
(`DryCore` with `CarriedCrystals`), `871eb969a` stage 2 (`CrystalOnsets`), `48cbeae3d` stage 3 (`CrystalEquilibrium`),
then this documents commit (this report, the engine chapter's class map, owner table and stage paragraph, the code guide
README's count). Nothing was compiled or run: every claim is from reading the code and from `node` scripts over the text.

## Summary

1. **Form: D-E3's, unchanged.** Each target is a package-private final class in `science/thermo/phase` whose final fields
   are the engine's final fields its methods read, plus the holders it calls; the engine holds one of each, built once at
   the end of its constructor. Constants only one stage reads move with it (private); shared ones stay in the facade,
   package-private, imported statically, so the moved lines keep their text. Shared static helpers of the facade
   (`diagnostics`, `crystalCeiling`, `fluidChemicalPotential`, `holdsLiquid`) are imported statically the same way.
   Engine call sites change by a qualifier only (`dryCore.solveDry(..)`, `crystalEquilibrium.solveWithCrystals(..)`,
   `crystalOnsets.onset(..)`); no call order changes, nothing is allocated per request (four more objects per engine).
2. **Stage 1, `DryCore` (330 lines) with `CarriedCrystals` (196)**: `solveDry`, `singlePhase`, `singleClassification`,
   `singleLabel`, `supercritical`, `merged`, `mergedDetail`, `twoPhaseResult`, `failed`, `hydrocarbon`, `feedStability`,
   `stabilityOf`, `threeSlotFeed`, `threePhaseResult`, and the field and construction of `patientStability` and `polar`
   (only the core reads them) into `DryCore`; `carriedTrial`, `crystalLean`, `supersaturated`, `crystalRich`,
   `carriedSplit`, `beyondStandIn` and `CARRIED_SHARE` into `CarriedCrystals`. The dry core builds the carried crystals
   and both flashes in its constructor; **the flashes now hold the dry core and the carried crystals instead of the
   engine** (every callback moved: `stabilityOf` to `DryCore`, `crystalRich` to `CarriedCrystals`); `TwoPhaseFlash` and
   `ThreeSlotFlash` reference the engine no more. Engine 4691 to 4286.
3. **Stage 2, `CrystalOnsets` (274 at `871eb969a`, 277 after stage 3)**: `onset`, `singlePhaseDrive`, `fluidAnswer`,
   `ONSET_EVALUATIONS`. The four public onset functions stay the engine's entries. Engine 4286 to 4059.
4. **Stage 3, `CrystalEquilibrium` (762)**: `solveWithCrystals`, `splitChemicalPotentialsUsable`, `depositFromVapour`,
   `depositFromFluid`, `fluidMuOf`, `twoCrystals`, `reduceFeed`, `singlePhaseSlope`, `secondCrystal`,
   `depositionResidual`, both `solidCoverage` overloads, `assessed`, `solidAnswer`, `crystalPhasesHold`, and
   `TWO_CRYSTAL_SWEEPS`, `TWO_CRYSTAL_CHANGE`, `DEPOSITION_ITERATIONS`, `CRYSTAL_ITERATIONS`. The onsets take
   `assessed` from it; `CrystalSet` calls the static `CrystalEquilibrium.solidCoverage`; the five balance-step answers
   call `crystalEquilibrium.solidCoverage`. Engine 4059 to 3363.
5. **Callbacks, each a plain call of a method that stays**: `DryCore` to the engine's `converged`, `expand`,
   `diagnostics` (answer builders, which call no stage); `CarriedCrystals.beyondStandIn` to `DryCore.hydrocarbon` (a
   cycle between two new classes, one call deep, as D-E3 foresaw); `CrystalOnsets` to `contractChecks`,
   `implementationChecks`, `unsupported`, `evaluateTp`, `graded`, `crystalStepTemperature`, `crystalStepPressure`,
   `crystalCeiling`, `fluidChemicalPotential`, `holdsLiquid` and `CrystalEquilibrium.assessed` (none of them calls an
   onset: only the four public entries do); `CrystalEquilibrium` to `DryCore.solveDry`, the engine's `single`,
   `diagnostics`, `fluidChemicalPotential`, `holdsLiquid` (none of them calls back into the crystal solves;
   `evaluateTp` calls `solveWithCrystals`, it is not called by it). No stage was left for a cycle.
6. **Lines**: `FluidTpEquilibrium` 4691 to 3363 (-1328); the four new files 1565; `TwoPhaseFlash` 675 to 677; the
   package 8937 to 9176 (+239: headers, holder fields and constructors, class documents). `Workspace`'s public surface
   is unchanged (the class, its private constructor, `newWorkspace()`; only field modifiers changed, to
   package-private); `pureBandBox` is unchanged and stays in the engine; `Feed` stays nested, its constructor private.
7. **Checks run here** (scripts in the worktree's `tmp/d-e4/`): per commit, `verify.mjs` (every line removed reappears
   among the added after dropping leading modifiers, the holder qualifiers and `{@link X#` prefixes, whitespace
   collapsed; braces and parentheses balance; every line CRLF; the unmatched lines are the table below); after each
   commit `access.mjs` over every new and edited stage file (every `w.x`, `material.x`, `engine.x` it reads is not private
   where declared; every unqualified call is declared in the file or imported statically; every ALL_CAPS name is
   declared, imported or an enum constant in a case label; every engine field name used bare is a field of the file or a
   local or parameter, each read by hand; every import used); `links.mjs` (no `{@link}` of the package names a member
   that moved away: the hits are enum constants and the two `{@link #solve3}` D-E3 noted); greps of every moved method
   and widened member over `src/main`, `src/test`, `src/fluidGameTest` and `tools/**/*.java` (no user outside the
   package; no reflection on the engine's members; the only name met is `fluidEquilibria()`, a public record accessor
   of `Deposition` and `DepositionOnset`).

## Construction order

`FluidTpEquilibrium`'s constructor, after `stability` and `convergedCoverage`:

1. `crystalSet = new CrystalSet(..)` (as before; its constructor now calls the static `CrystalEquilibrium.solidCoverage`).
2. `criticalBand = new CriticalBand(evaluator)` (moved up from after the flashes: its constructor only stores).
3. `dryCore = new DryCore(this, contract, kernel, evaluator, settings, stability, count, basis, inEquationOfState,
   cubicWater, crystalSet, criticalBand)`. Inside: the plain fields, then `patientStability` and `polar` (the statements
   moved from the engine's constructor), then `carried = new CarriedCrystals(this, ..)`, `twoPhaseFlash = new
   TwoPhaseFlash(this, carried, .., crystalSet.crystalSpecies)`, `threeSlotFlash = new ThreeSlotFlash(this,
   twoPhaseFlash, .., polar, cubicWater)`.
4. `crystalEquilibrium = new CrystalEquilibrium(this, dryCore, contract, evaluator, settings, count, basis,
   convergedCoverage, crystalSet)`.
5. `crystalOnsets = new CrystalOnsets(this, crystalEquilibrium, contract, evaluator)`.

Why this order: the flashes and the carried crystals call the core and the core calls them, so the core builds them and
hands them itself; the two flashes read `carried` and `twoPhaseFlash` at their construction, so those come first; the
crystal equilibrium reads the dry core, the onsets read the crystal equilibrium. Why javac accepts it: every holder field
is a blank final assigned exactly once in its constructor, and every read of one by simple name (`carried`,
`twoPhaseFlash`, `polar` in `DryCore`; `crystalSet`, `criticalBand`, `dryCore`, `crystalEquilibrium`,
`convergedCoverage` in the engine) comes after its assignment, which is what definite assignment (JLS 16) requires;
parameters that shadow fields (`count`, `kernel`, `evaluator`, `settings`, `stability` in `DryCore`; `contract`,
`evaluator`, `settings` in the engine) hold the same value or reference as the field. `this` escapes only into
constructors that store it; no stage runs before the engine's constructor returns (a `Workspace`, which reads
`owner.dryCore` and builds `Slots` from `owner.dryCore.threeSlotFlash`, exists only after it).

## Members widened (private to package-private), with who reads them

| Member | Read by |
|---|---|
| Engine methods `converged` (both), `expand`, static `diagnostics` | `DryCore` (`diagnostics` also `CrystalEquilibrium`) |
| Engine methods `contractChecks`, `implementationChecks`, `unsupported`, `evaluateTp`, `graded`, `crystalStepTemperature`, `crystalStepPressure`; static `crystalCeiling` | `CrystalOnsets` |
| Engine method `assessed` | widened at stage 2 for `CrystalOnsets`, moved at stage 3 (package-private in `CrystalEquilibrium`) |
| Engine static `fluidChemicalPotential`, `holdsLiquid` | `CrystalOnsets`, `CrystalEquilibrium` |
| Engine method `single` | `CrystalEquilibrium.twoCrystals` |
| Constants `PRESSURE_STEP_CAP`; `CONVERGED`, `FLUID_ONLY_NOTE` | `CrystalOnsets`; `CrystalEquilibrium` |
| `Feed.basisAmounts`; `Feed.takeAmounts` | `DryCore`, `CarriedCrystals`, `CrystalEquilibrium`; `CrystalEquilibrium` |
| `Workspace`: `stability`, `vaporScratch`, `liquidScratch`, `feedResult`, `beyondStandIn`, `carriedTemperature`, `carriedFilter`, `carriedState`, `standInFeed` | stage 1 (`DryCore`, `CarriedCrystals`; the scratch states also `CrystalOnsets.singlePhaseDrive`) |
| `Workspace`: `reducedFeed`, `pairFeed` | `CrystalEquilibrium`; widened at stage 1 with `standInFeed`, the three share one declaration |
| `Workspace`: `request`, `competition`, `active`, `activeCount`, `hydrocarbonTotal` (split from `private double total`, which stays private) | `CrystalOnsets`, `CrystalEquilibrium` (not `hydrocarbonTotal`) |
| `Workspace`: `solidState`, `reduced`, `fluidEquilibria`, `paired`, `secondForming`, `reducedSlope` | `CrystalEquilibrium` |

Moved methods called across files are package-private: `DryCore.solveDry`, `hydrocarbon`, `stabilityOf`;
`CarriedCrystals.carriedTrial`, `crystalRich`, `carriedSplit`, `beyondStandIn`; `CrystalOnsets.onset`;
`CrystalEquilibrium.solveWithCrystals`, `solidCoverage` (both), `assessed`. Everything else moved stays private.
Nothing became public.

## Edits that are not a pure move (the script's unmatched lines, each read by hand)

| Commit | Where (at `48cbeae3d`) | Edit |
|---|---|---|
| 1 | `FluidTpEquilibrium` constructor | `dryCore = new DryCore(..)` replaces the two flash constructions (now in `DryCore`'s constructor, `TwoPhaseFlash` with the extra `carried` argument) |
| 1 | `Workspace` constructor | `owner.carriedTrial(` to `owner.dryCore.carried.carriedTrial(`; `new Slots(owner.threeSlotFlash)` to `new Slots(owner.dryCore.threeSlotFlash)` |
| 1 | `TwoPhaseFlash` field, constructor, class document | `FluidTpEquilibrium engine` to `DryCore dryCore` and `CarriedCrystals carried` (the constructor's continuation line re-wrapped) |
| 1 | `ThreeSlotFlash` field, constructor, class document | `FluidTpEquilibrium engine` to `DryCore dryCore`; `{@link DryCore#threeSlotFeed}` |
| 1, 3 | documents | `{@link}`s to moved members now name their class (engine class document, `Feed`, `Workspace`, `CarriedCrystals.supersaturated`, `ThreeSlotFlash.slotOutcome`'s two links to the dry core's builders) |
| 2 | engine constant `DEPOSITION_ITERATIONS` | its shared document line loses the onset half; `ONSET_EVALUATIONS` gets its own line in `CrystalOnsets` |
| 2 | `Workspace` | `private double hydrocarbonTotal, total;` split into `double hydrocarbonTotal;` and `private double total;` |
| 2 | engine `converged` (both), `unsupported`; `CrystalOnsets.onset` | signature continuation lines re-indented after `private` went (whitespace) |
| 3 | engine section comment above the answer inspectors | says what stays and where the crystal solves and onsets went |
| 3 | `CrystalSet.java:69` | `FluidTpEquilibrium.solidCoverage(` to `CrystalEquilibrium.solidCoverage(` |
| 3 | `CrystalOnsets` field, constructor, class document | the `CrystalEquilibrium crystalEquilibrium` reference; `engine.assessed(` to `crystalEquilibrium.assessed(` |

## What stayed, and why

- **The answer inspectors** `fluidChemicalPotential`, `holdsLiquid`, `holdsKind`, `holdsSolid`, `hydrocarbonFluid`,
  `oneFluid`, `sameBesideFluid`, `samePhases`: `Search`, the PH/UV searches, the balance steps, the crystal solves and
  the onsets share them; they stay together in the facade (the two the stage files read are package-private, imported
  statically).
- **`pureCrystal`, `majorSpecies`, `traces`, `binaryCrystal`, `binaryPartner`, `foundOrHeld`**: the review's
  `CrystalEquilibrium` range held them, but only the searches and the balance steps call them; they go with
  `BalanceSteps` and `SpecificationSearch`.
- **Answer builders** `converged`, `expand`, `unsupported`, `diagnostics`, `single`: every stage builds answers with
  them; the facade keeps them (D-E3's rule). `shifts()`: the facade constants' initialiser. `pureBandBox`: public API.
- **The four onset entries**: the engine's public API; `crystalOnsets.onset` behind them.
- **Pre-existing, not touched**: the unused import `science.material.CrystalReference` and the unused private
  `species(int)` in the engine (both unused at `c931cd856`); the two dangling `{@link #solve3}`.
- `Workspace`, `Feed` and `Search` stay nested in the facade, as the brief says.

## What the last slice holds (`FluidTpEquilibrium`, 3363 lines)

The facade proper (constants, `Settings`, the fields and constructor, `Feed`, `Workspace`, `tp`/`ph`/`uv`, the checks,
`evaluateTp`, `graded`, `outsideData`, the onset entries, the builders, `own`, `pureBandBox`: about 900 lines with the
class document) and two stages: `SpecificationSearch` with `Search` (`searchWindow`, `outerTp`, the ideal-gas starts,
`solvePh`, `closedPh`, `solveUv`, `newtonUv` and its Jacobians, `nestedUv`, `innerUv`, `closedInner`, `found`, the
search answers and failures; about 1,150 lines) and `BalanceSteps` (`pureCoexistence`, `coexistenceTemperature`,
`coexistencePressure`, `pureCrystalStep`, `stepFluid`, `crystalStepHold`, `crystalStepTemperature`,
`crystalStepPressure`, `cold`, `pureTriplePoint` and its triangle, `binaryThreePhase`, `leverStep`,
`binaryFluidThreePhase` and their residuals, with the inspectors and `pureCrystal` .. `foundOrHeld`; about 1,300
lines), with D-E2's step 3 (the searches' values in a holder the search owns). D-E3's order for it stands: `BalanceSteps`
first (it reads the searches' `Search` only through its arguments and the workspace), then `SpecificationSearch`.

## Tests for the main thread

One run, `test` task (every class is in the everyday group; `science/thermo/phase` is in no gate group of
`build.gradle`). D-E3's list plus the two crystal-deposition pins of the owner table that load `CrystalEquilibrium`:

`./gradlew test --tests "*.EngineDigestTest" --tests "*.FluidTpEquilibriumTest" --tests "*.FluidTpEquilibriumP3Test" --tests "*.FluidTpEquilibriumPhUvTest" --tests "*.AqueousEquilibriumTest" --tests "*.GasSolidEquilibriumTest" --tests "*.TangentPlaneStabilityTest" --tests "*.NearPureCrystalStepTest" --tests "*.RefineryCarbonDioxideCrystalTest" --tests "*.SolidCarbonDioxideQualificationTest" --tests "*.CarriedCrystalsTest" --tests "*.NetworkPhaseEngineTest" --tests "*.CrystalDepositionIslandTest" --tests "*.CrystalFlushBudgetTest" --tests "*.WaterShareTest" --tests "*.LiquidFullBubblePointTest" --tests "*.SolidPhaseEvaluatorTest" --tests "*.SpinePackageEvaluatorTest" --tests "com.wormzjl.icheme.science.thermo.qualification.*" --tests "*.ThermoLayeringTest" --tests "*.LayeringTest"`

- The compile of `main` and `test`: the first proof (nothing was compiled here). No test source needed a change: no
  test names a moved nested type or a moved member (grep).
- `EngineDigestTest` (reference unchanged, `src/test/resources/thermo/engine-digest.txt`): the 429 answers' bits and
  stable counts on nine engines. Every stage moved here is on its path: the dry core and its builders on every line;
  the carried crystals on the carried lines (filter, restated split, `beyondStandIn`); the onsets on the four onset
  functions' lines; the crystal solves on every crystal line (one and two crystals, complete deposition, the
  three-phase-fluid verdict, the holds). Unchanged proves no operation and no kernel evaluation moved. If it fails,
  bisect by the three commits (each is meant to compile on its own).
- `FluidTpEquilibriumTest`, `FluidTpEquilibriumP3Test` (reused-workspace determinism, `pureBandBox`, the band),
  `FluidTpEquilibriumPhUvTest` (the balance steps' `solidCoverage` through `crystalEquilibrium`), `AqueousEquilibriumTest`
  (per-answer kernel table, `crystalAnswersDoNotDependOnWhatTheWorkspaceSolvedBefore`), `GasSolidEquilibriumTest`
  (onsets, deposition), `NearPureCrystalStepTest`, `RefineryCarbonDioxideCrystalTest`,
  `SolidCarbonDioxideQualificationTest`, `TangentPlaneStabilityTest`: the engine's own expectations.
- `CarriedCrystalsTest`, `NetworkPhaseEngineTest`, `CrystalDepositionIslandTest`, `CrystalFlushBudgetTest`,
  `WaterShareTest`, `LiquidFullBubblePointTest`, `SolidPhaseEvaluatorTest`, `SpinePackageEvaluatorTest`, the
  qualification package (`G4F*`, `G5F*` call the onsets): the carried path and crystal answers through the network and
  the other classes that build the engine.
- `ThermoLayeringTest`, `LayeringTest`: the four new files name only `science.thermo.phase`, `science.thermo`
  (`PengRobinsonKernel`, `PhaseIdentification`, `TangentPlaneStability`, `NumericalBox`) and `science.math`
  (`DeterministicMath`); none reads `MaterialCatalog`.
- Chain-100 exact (`FluidSolverRegressionTest`, task `fluidSolverRegression`) and the fluid GameTests load the engine:
  the batch's final lane.

## Uncertain points (not verified by a compiler)

- **Static imports of package-private static methods** of the engine (`diagnostics`: `DryCore.java:11`,
  `CrystalEquilibrium.java:8`; `crystalCeiling`, `fluidChemicalPotential`, `holdsLiquid`: `CrystalOnsets.java:6-8`,
  `CrystalEquilibrium.java:9-10`). Legal for accessible members of the same package; no importing class declares a
  member of the same name (a member would shadow the import). `fluid.diagnostics()` in the same files is a qualified
  call of `EquilibriumResult`'s accessor and does not meet the import.
- **`case SINGLE ->` with statically imported `int` constants** (`DryCore.java:98-103`, as `solveDry` was): the
  constants are constant variables (`static final int` with literal initialisers), so the labels are constant
  expressions.
- **`this` in constructors**: `FluidTpEquilibrium.java:349-353` hand `this` to `DryCore`, `CrystalEquilibrium`,
  `CrystalOnsets`; `DryCore.java:83-85` hand `this` to `CarriedCrystals`, `TwoPhaseFlash`, `ThreeSlotFlash`. Each only
  stores it. `DryCore.java:84` reads `crystalSet.crystalSpecies`, filled by `CrystalSet`'s constructor (engine line
  347) before.
- **Bits**: the moved text is the same, line by line (script), so the arithmetic and its order are the same text. What
  rests on reasoning: a holder's field is the same reference or the same `int` as the engine's (`kernel`, `evaluator`,
  `settings`, `stability`, `contract`, `crystalSet`, `criticalBand`, `convergedCoverage`; `count`, `basis`); the
  longer-budget test is built from the same arguments, after `crystalSet` instead of before it (its constructor reads
  only its arguments); `CrystalEquilibrium.solidCoverage`'s `fluid == convergedCoverage` compares with the engine's own
  object (`converged()` returns the engine's field, and the holder holds that reference), so the cached coverage is
  taken exactly where it was. The workspace's carried filter lambda now loads two more fields per filtered trial
  (`owner.dryCore.carried`): no floating-point operation, no allocation (the lambda is built once per workspace as
  before).
- **Class initialisation**: `CrystalSet`'s constructor now initialises `CrystalEquilibrium` (no static state but
  compile-time constants; `FLUID_ONLY_NOTE` is a constant `String`, inlined) where it called the engine's own static
  method before.
- **Doc links** to members of another class that are private (`{@link CrystalEquilibrium#depositFromVapour}` in the
  engine's class document, `{@link CarriedCrystals#beyondStandIn}` in `Workspace` and the like) matter only to
  `javadoc`, which the build does not run.
- **Long lines**: the moved lines keep their length; `TwoPhaseFlash.java:35` is 123 characters (no line-length check).
