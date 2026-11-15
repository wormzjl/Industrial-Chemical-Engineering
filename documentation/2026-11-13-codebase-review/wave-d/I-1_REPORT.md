# I-1 report: room for supercritical fluids, first step: a phase's traits on every answer

Branch `worktree-agent-a3d8dbbf1b7ed5f61`, fast-forwarded to `743512ec1`. Commits in order: `72c1d9186` step 1
(`PhaseTraits`), `77a7e3e4b` step 2 (the label rules on traits), `0dcbe1291` step 3 (the traits on the answer),
`850d3f3a9` step 4 (`PhaseTraitsTest`), then this documents commit (this report, the engine chapter of the code guide).
Nothing was compiled or run: every claim is from reading the code and from `node` scripts over the text and the digest
reference (scratch in the worktree's `tmp/i1/`). Structure only: no operation of any answer changes.

## Summary

1. **The record**, `science/thermo/PhaseTraits` (kernel): `physicalRootCount`, `compressibility` (untranslated `Z` of
   the evaluated root), `volumeRatio` (`v/b`, untranslated `v = Z R T/P`, mixture co-volume `b`),
   `identificationParameter` (PIP, NaN where undefined). `PhaseTraits.of(Evaluation, T, P)` and
   `of(rootCount, Z, a, b, da/dT, T, P)`: `v = Z * R * T / P` is `PhaseIdentification.parameter(evaluation, ..)`'s
   expression, in its order; the parameter is `PhaseIdentification.parameter(T, v, a, b, da/dT)` on that `v`. No kernel
   call.
2. **Where each trait comes from.** `CubicPhaseEvaluator.fill`, after `out.set(..)`, stores the kernel evaluation's own
   `Z`, `a`, `b`, `da/dT` (`TranslatedCubic.Values.compressibility/attraction/coVolume/attractionTemperatureDerivative`,
   which are `evaluation.compressibility/aMix/bMix/daMixDt`) on the state: `PhaseState.setIdentification`, four stores
   per evaluation, nothing allocated, no arithmetic. `set` clears them (NaN), `copy` carries them. `PhaseState.traits()`
   forms the record (one allocation per call) at the state's own T and P, the T and P of the evaluation. Every fluid
   state in `src/main` is filled by `CubicPhaseEvaluator.fill` (grep of `.set(`: the only other filler is
   `CrystalPhaseEvaluator`, a crystal), so every fluid phase of every engine answer has traits.
3. **Same doubles as the label sites.** A label site evaluates the kernel on its own workspace (`w.mixture`) with the
   same T, P, amounts and root as the evaluator call that fills the phase's state (`DryCore.singleLabel`: the state's
   root and `material.amounts`; `TwoPhaseFlash.finishSplit`: `w.chosen` on the amounts and root of the product's state;
   `ThreeSlotFlash` 711-714: the same `s.amounts[k]`, `s.root[k]`). The kernel's `evaluate` is a function of
   (T, P, amounts, root, support) only (`evaluatePrepared`: no warm start; sums over the support in ascending order), and
   both workspaces carry the request's amounts (`FluidTpEquilibrium` 620-621). So `Z`, `a`, `b`, `da/dT` are the same
   bits, and so are the traits. `PhaseTraitsTest` checks the consequence on the 404 converged digest answers.
4. **The label rules' trait forms** (`PhaseIdentification`): `liquidLike(PhaseTraits)` is
   `liquidLike(parameter, Z)`, the same comparison on the same two doubles as `liquidLike(Evaluation, T, P)`;
   `denseLiquidLike(PhaseTraits)` is `liquidLike(traits) && volumeRatio < VAPOUR_BRANCH_VOLUME_RATIO`, where the
   evaluation's form tests `v < VAPOUR_BRANCH_VOLUME_RATIO * b`: **not the same operations** (a division against a
   product; they can part only where `v/b` is within an ulp or two of 3.95137), so both stay; `singleLabel(traits, root,
   pureSupercritical)` is `DryCore.singleLabel`'s rule (three roots: the requested root's label; one root: FLUID for a
   pure supercritical component, the critical-constant test an argument; else PIP, FLUID where NaN). It returns the
   kernel enum `PhaseIdentification.Label {VAPOR, LIQUID, FLUID}`: `ThermoLayeringTest` forbids the kernel to name
   `science.thermo.phase`, so it cannot return `PhaseKind` (the constants have `PhaseKind`'s names).
5. **Which existing rules call the trait forms: none.** Every engine label site calls the evaluation overloads per
   iterate (`CarriedCrystals.carriedTrial`, `BalanceSteps.coexistenceTemperature/Pressure`) or holds the evaluation in
   hand (`DryCore.singleLabel`, `TwoPhaseFlash.finishSplit`, `ThreeSlotFlash`); routing them through a record would
   allocate where they allocate nothing today, and `denserThanCriticalVolume` is not the trait form's operation. The two
   forms share the primitive `liquidLike(double, double)`. No label site was edited.
6. **Builders changed** (each passes its state's `traits()`, a crystal `null`): `DryCore.singlePhase`, `merged`,
   `twoPhaseResult` (two locals `lighterState`, `denserState` replace the inline conditionals), `threePhaseResult`;
   `BalanceSteps.pureCoexistence` (both branches; local `only`), `pureCrystalStep` (three), `pureTriplePoint`,
   `binaryThreePhase`, `binaryFluidThreePhase`, and `scaled` (the lever step: carries the copied phase's traits);
   `CrystalEquilibrium` (five crystal phases: `null`). `ThreeSlotFlash`, `TwoPhaseFlash`, `CarriedCrystals` and the
   facade build no `PhaseAmounts`; the crystal solves and `outerAnswer` reuse the dry answers' `PhaseAmounts` objects,
   so their fluid phases carry the dry answer's traits. A pure coexistence's two `coexisting()` states have traits too
   (`state.traits()`); they are not `PhaseAmounts`.
7. **The component**: `PhaseAmounts(kind, crystal, temperature, pressure, amounts, state, traits)`, traits last;
   `null` for a crystal (the compact constructor refuses traits on a crystal; nothing else is validated, so no new
   exception can reach the engine's catch in `evaluateTp`). **Sites left without traits: none in `src/main`.**
8. **Allocation**: one `PhaseTraits` per fluid `PhaseAmounts`, at the builder that allocates the `PhaseAmounts`; per
   evaluation only the four stores. Nothing per iterate.
9. **Order.** The engine lists fluid phases by `PhaseState.molarVolume()` (the physical, translated volume), largest
   first: `DryCore.twoPhaseResult` (`swapped`), `threePhaseResult` (sort), `BalanceSteps` (vapour, liquids lighter first,
   then crystals; the lever step by `order(kind)`). By molar volume, not mass density. Over the digest reference
   (`tmp/i1/order.mjs`, reading each printed `v`): 404 converged answers (onset fluids included), **none out of order**,
   no crystal before a fluid. Nothing reordered.

## Outside `science/thermo/**`

- Constructor sites edited: `src/test/.../phase/SolidPhaseEvaluatorTest.java` 111, 112, 138-141 (the vapour takes
  `vapourState.traits()`, the crystal and the refused constructions `null`). No call in `science/fluid/**`,
  `runtime/**`, `src/fluidGameTest`, `src/mcpCompat` or `tools/**` (script `tmp/i1/args.mjs`: 32 calls, all 7
  arguments). No record pattern, `::new` or `equals` on `PhaseAmounts` anywhere.
- `EngineDigestTest`: the eight request groups move into `requests(Digest)` (the digest test calls it as before);
  `Digest` keeps each answer in `answers` (`record Answer(id, engine, result)`; an onset's fluid answer as
  `"<id> [onset fluid]"`); `static List<Answer> answers()`. The lines, the tally and the reference are untouched.
- Wire and saves: no codec serialises `PhaseAmounts` (its only reader outside the engine is
  `FluidThermodynamics`, accessors only). `Workspace`'s public surface unchanged.

## `PhaseTraitsTest` (new, `test` group)

Over `EngineDigestTest.answers()`, every converged answer: each fluid phase has traits equal to its state's, root count
equal; a crystal none; fluids before crystals, molar volume non-increasing. Labels: answers with a balance flag or a
balance note in their detail (pure coexistence, crystal step, triple and three-phase points, lever step), or a merged
split, are counted, their labels being set by construction. All other answers, with a crystal beside them or not, are
the dry core's: one fluid phase by `singleLabel` (critical constants read in the test from the phase's composition);
two by the products rule (denser LIQUID; lighter LIQUID iff not in band and both `denseLiquidLike(traits)`); three by
position. Under `FLUID_CARRYING_CRYSTALS` a lighter VAPOR where the rule says LIQUID is counted as a restated split
(`CarriedCrystals.carriedSplit`, `beyondStandIn` force vapour-liquid), not a fault. From the digest reference
(`tmp/i1/dump.txt`): 42 answers carry the balance flag, 1 is merged; without a crystal, 135 are single-phase, 144
two-phase and 21 three-phase answers of the dry core, besides the crystal answers' fluid phases; every carried
two-phase vapour has `v/b` above about 15, so I expect no restated count.

## Tests for the main thread (all `test` task; `science/thermo/**` is in no gate group)

`./gradlew test --tests "*.PhaseTraitsTest" --tests "*.EngineDigestTest" --tests "*.FluidTpEquilibriumTest" --tests "*.FluidTpEquilibriumP3Test" --tests "*.FluidTpEquilibriumPhUvTest" --tests "*.AqueousEquilibriumTest" --tests "*.GasSolidEquilibriumTest" --tests "*.TangentPlaneStabilityTest" --tests "*.NearPureCrystalStepTest" --tests "*.RefineryCarbonDioxideCrystalTest" --tests "*.SolidCarbonDioxideQualificationTest" --tests "*.CarriedCrystalsTest" --tests "*.NetworkPhaseEngineTest" --tests "*.CrystalDepositionIslandTest" --tests "*.CrystalFlushBudgetTest" --tests "*.WaterShareTest" --tests "*.LiquidFullBubblePointTest" --tests "*.SolidPhaseEvaluatorTest" --tests "*.SpinePackageEvaluatorTest" --tests "com.wormzjl.icheme.science.thermo.qualification.*" --tests "*.ThermoLayeringTest" --tests "*.LayeringTest" --tests "*.CubicPhaseEvaluatorTest" --tests "*.CrystalPhaseEvaluatorTest" --tests "*.ThermoIdentityTest" --tests "*.HydrocarbonTangentTest" --tests "*.SpineNetworkPathTest" --tests "*.WetGasGatesTest" --tests "*.HelmholtzReferenceParityTest" --tests "*.FluidGuiConsistencyTest"`

- Compile of `main`, `test`, `fluidGameTest`: the first proof. `EngineDigestTest` unchanged: no operation and no
  kernel evaluation moved (the four stores and the record touch no printed number). `PhaseTraitsTest`: the brief's
  item 4. `SolidPhaseEvaluatorTest`: the edited constructor calls. `ThermoLayeringTest`, `LayeringTest`: `PhaseTraits`
  names only the kernel. The D-E5 list: the engine's callers. The last eight load a changed class
  (`CubicPhaseEvaluator`, `PhaseState`, `PhaseAmounts` or `PhaseIdentification`, by grep of the test sources).
- Chain-100 exact (`FluidSolverRegressionTest`, task `fluidSolverRegression`): the final lane.

## Not verified without a compiler

- `PhaseIdentification.java` 134-169: the nested `enum Label` after the static methods; the overload
  `liquidLike(PhaseTraits)` beside `liquidLike(double)` (no call passes `null`).
- `PhaseState.java` 57-58, 146-157, 180-185: field initialisers, the parameters shadowing the fields (`this.` used).
- `EngineDigestTest.java` 119-130: `record Answer` nested in the test class and used by the private `Digest`; `answers()` reads
  `digest.answers` of the private nested class (legal inside the top-level class).
- `PhaseTraitsTest.java` 89: `EngineDigestTest.Answer` and `answers()` package-private, same package; `detail::contains`
  as a `Predicate<String>`.
- The test's outcome: the products rule's `v/b` comparison may differ from the engine's `v < 3.95 b` at a boundary
  (not expected on the digest); a balance answer without the flag or a listed note would be checked by a dry rule.

## Left, and what the next steps of wave I need

- Label sites still decide on their evaluations (item 5); `DryCore.singleLabel` could call `singleLabel(PhaseTraits)`
  with `PhaseTraits.of(w.first, t, p)` (same operations), left as is.
- Next: `Root` selectors (P22); `Classification` reduced (its five mapping sites; `FluidThermodynamics` switches on it);
  the network's slot mapping by `traits().volumeRatio()` or density with its own hysteresis (`FluidThermodynamics.placed`
  re-evaluates `liquidLike` on its own model today, 1306-1313); F24 films and heads by density; F21 a "no saturation"
  predicate. A consumer reading `PhaseAmounts.traits()` gets the engine's numbers, not the network model's.
