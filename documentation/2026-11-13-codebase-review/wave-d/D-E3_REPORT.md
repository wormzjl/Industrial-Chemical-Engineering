# D-E3 report: the equilibrium engine in files, first slice (S01): the flashes, the crystal set, the critical band

Branch `worktree-agent-aefd41df1dfdfd888`, fast-forwarded to `a91f6f9fa`. Commits in order: `e476fc87f` stage 1
(`ThreeSlotFlash`), `74ba28c80` stage 2 (`TwoPhaseFlash`), `b2c977fcb` stage 3 (`CrystalSet`), `6e968c8fc` stage 4
(`CriticalBand`), then this documents commit (this report, the engine chapter's class map, the code guide README's count).
Nothing was compiled or run: every claim is from reading the code and from `node` scripts over the text.

## Summary

1. **Form: a holder per stage with final references given at construction.** Each target is a package-private final
   class in `science/thermo/phase` whose final fields are the engine's final fields its methods read (kernel, evaluator,
   settings, stability test, count, and per stage the polar list, cubic water, crystal species), plus the engine itself
   where a moved method calls back into it, plus the other stage it calls; the engine holds one of each, built once in
   its constructor. Why: moved bodies keep their simple names (`kernel`, `count` are the holder's fields) and an engine
   call site changes by a qualifier only (`twoPhaseFlash.twoPhase(..)`), where static functions would add arguments at
   every call; the holders take the engine's fields by simple name at the end of the constructor, so javac's
   definite-assignment rule checks each was assigned first; nothing is allocated per request (four objects per
   engine); no call order changes (the same calls through one more field load).
2. **Constants**: those only one moved stage reads move with it (private); those shared stay in the facade, widened to
   package-private, and the stage files `import static` them, so the moved lines keep their text. `shifts()` stays with
   the facade's `SHIFTS_FROM_1E8`/`1E13`, whose initialisers call it.
3. **Stage 1, `ThreeSlotFlash`** (791 lines at `e476fc87f`, 793 after stage 2): `threeSlot`, `evaluateSlot`,
   `slotSubstitution`, `slotsPresent`, `dropDuplicates`, `slotFractions`, `slotObjective`, `slotMoles`, `slotNewton`,
   `slotCurvature`, `slotGibbs`, `slotOutcome`, nested `Slots` (its constructor takes its holder instead of the engine:
   it reads `count`, `kernel`, `evaluator`, now the holder's, so those three engine fields stay private), and the eight
   three-slot constants. Engine 6214 to 5490.
4. **Stage 2, `TwoPhaseFlash`** (675): `twoPhase`, `attempt`, `finishSplit`, `newtonFinish`, `startFromStationaryPhase`,
   `gibbs`, `shiftedSolve`, `phaseLogPhi`, `differentiatePhase`, `split`, `twoPhaseSplit`, `splitDenominator`,
   `lowerGibbsRoot`, `residualGibbs`, and `LOG_K_BOUND`, `MAXIMUM_RACHFORD_RICE_STEPS`, `START_HALVINGS`. `ThreeSlotFlash`
   now calls `twoPhase.lowerGibbsRoot` and `twoPhase.differentiatePhase`. Engine 5490 to 4876.
5. **Stage 3, `CrystalSet`** (207): what the constructor built for the crystals and the solves only read: the crystal
   loop (duplicate check, anchoring) and the coverage map, moved as statements into `CrystalSet`'s constructor in the same
   order; `crystals`, `crystalSpecies`, `crystalCoverages` (package-private final fields); `crystal`, `liquidShare`,
   `tripleTriangle`, `familyTriplePoint`, `crystalById`, `gradeOf`; nested record `Crystal` (the engine imports it, so
   its uses of the type are unchanged). The engine builds it where the loop stood, before the flashes. Engine 4876 to 4709.
6. **Stage 4, `CriticalBand`** (41): `inBand`, `bandReason`. `pureBandBox` stays in the engine: it is public API
   (`FluidTpEquilibriumP3Test` calls `FluidTpEquilibrium.pureBandBox`), and `inBand` calls it there. Engine 4709 to 4691.
7. **The small linear algebra**: nothing left to move. The dense solves are `science/math/SmallDenseSolve` already
   (`factorShifted`, `solveCholesky`, `solve3`, `solvePivoted`); `shiftedSolve` went with the two-phase flash; the 2x2
   solves of `familyTriplePoint` and `newtonUv` are inline in method bodies and cannot leave without a body change.
8. **Callbacks into the engine** (each a plain call of a method that stays, one level deep): `stabilityOf` (both flashes),
   `crystalRich` (`finishSplit`), `solidCoverage` (static, `CrystalSet`'s constructor), `pureBandBox` (static,
   `inBand`). No cycle deeper than that.
9. **Lines**: `FluidTpEquilibrium` 6214 to 4691; the four files 1716; together 6407 (+193: headers, holder fields and
   constructors, class documents). `Workspace`'s public surface is unchanged: the class, its private constructor and
   `newWorkspace()`; only field modifiers changed (to package-private). `Feed` stays nested, now package-private.
10. **Checks run here** (scripts in the worktree's `tmp/d-e3/`): per commit, `verify.mjs` checks that every line
   removed from the engine reappears among the added lines after dropping leading modifiers, the holder qualifiers and
   `{@link X#` prefixes (exceptions: the table "Edits that are not a pure move"), that braces balance and that every line
   is CRLF. After the moves: every `w.x`, `material.x` of a new file is declared without `private`, no widened
   `Workspace` field is unread by them, every unqualified call resolves in its file, every import is used, and the moved
   names have no user outside the package (`src/main`, `src/test`, `src/fluidGameTest`, `tools/**/*.java`).

## Tests for the main thread

One run, `test` task (every class is in the everyday group; `science/thermo/phase` is in no gate group of `build.gradle`):

`./gradlew test --tests "*.EngineDigestTest" --tests "*.FluidTpEquilibriumTest" --tests "*.FluidTpEquilibriumP3Test" --tests "*.FluidTpEquilibriumPhUvTest" --tests "*.AqueousEquilibriumTest" --tests "*.GasSolidEquilibriumTest" --tests "*.TangentPlaneStabilityTest" --tests "*.NearPureCrystalStepTest" --tests "*.CarriedCrystalsTest" --tests "*.NetworkPhaseEngineTest" --tests "*.CrystalDepositionIslandTest" --tests "*.CrystalFlushBudgetTest" --tests "*.WaterShareTest" --tests "*.LiquidFullBubblePointTest" --tests "*.SolidPhaseEvaluatorTest" --tests "*.SpinePackageEvaluatorTest" --tests "com.wormzjl.icheme.science.thermo.qualification.*" --tests "*.ThermoLayeringTest" --tests "*.LayeringTest"`

- The compile of `main` and `test` itself: the first proof (nothing was compiled here; see the uncertain points).
- `EngineDigestTest` (reference unchanged, `src/test/resources/thermo/engine-digest.txt`): the 429 answers' bits and
  stable counts on nine engines; every stage moved here is on its path (the two-phase flash and Newton finish on every
  engine, the three-slot system on `pilot_aqueous`, `tjl20...+water` and `refinery+water`, the crystal set on every
  crystal engine, the band's lines with `CRITICAL_BAND`). Unchanged proves no operation and no kernel evaluation moved.
  If it fails, bisect by the four commits (each is meant to compile on its own).
- `FluidTpEquilibriumTest`, `FluidTpEquilibriumP3Test` (reused-workspace determinism, `pureBandBox`),
  `FluidTpEquilibriumPhUvTest`, `AqueousEquilibriumTest` (per-answer kernel table, history independence),
  `GasSolidEquilibriumTest`, `NearPureCrystalStepTest`, `CarriedCrystalsTest`, `NetworkPhaseEngineTest`,
  `TangentPlaneStabilityTest`: the engine's own expectations and its stability neighbour.
- `CrystalDepositionIslandTest`, `CrystalFlushBudgetTest`, `WaterShareTest`, `LiquidFullBubblePointTest`,
  `SolidPhaseEvaluatorTest`, `SpinePackageEvaluatorTest`, the qualification package: crystal and wet answers through the
  network and the other classes that build the engine (D-E1's list).
- `ThermoLayeringTest`, `LayeringTest`: the new files name only `science.thermo.phase`, `science.thermo`,
  `science.math` and (`CrystalSet`) `science.material.MaterialCoverage`, which the rules allow.
- Chain-100 exact (`FluidSolverRegressionTest`, task `fluidSolverRegression`) and the fluid GameTests load the engine:
  the batch's final lane.

## Uncertain points (not verified by a compiler)

- **Imports of nested types in the same package**: the four files import `FluidTpEquilibrium.Feed` (package-private
  nested class, `FluidTpEquilibrium.java:402`), `FluidTpEquilibrium.Workspace` and `FluidTpEquilibrium.Settings`, and
  `import static` package-private constants of the engine (`ThreeSlotFlash.java:3-16`, `TwoPhaseFlash.java:3-13`,
  `CrystalSet.java:3`, `CriticalBand.java:3`); the engine imports `CrystalSet.Crystal` and `ThreeSlotFlash.Slots`
  (`FluidTpEquilibrium.java:16-17`). Legal by the JLS (accessible from the same package; the import cycle is allowed).
- **`this` in the constructor**: `FluidTpEquilibrium.java:370-371` hand `this` to `TwoPhaseFlash` and `ThreeSlotFlash`,
  which only store it; nothing in the constructor runs a flash, and a `Workspace` (whose constructor reads
  `owner.threeSlotFlash`, line 585, and `owner.crystalSet`, line 575) exists only after the constructor returned.
- **Shadowing in `CrystalSet`'s constructor** (`CrystalSet.java:41-72`): the parameters `contract` and `count` shadow
  the fields of the same name; the moved statements read the parameters (`contract.qualifiedCompetitions()`,
  `new boolean[count]`), the same objects and value the fields hold; the methods it calls read the fields, assigned on
  lines 43-46 before the first call.
- **Bits**: the arithmetic is the same text, verified line by line by the script (above). What rests on reasoning: a
  holder's field is the same reference or the same `int` as the engine's (`kernel = evaluator.kernel()` once, the same
  `polar` and `crystalSpecies` arrays, the latter filled in `CrystalSet`'s constructor before `TwoPhaseFlash` is built at
  line 370); the facade's static constants are initialised with the engine class, before any flash can run.
- **Doc links** to private members of another class (`{@link FluidTpEquilibrium#threeSlotFeed}` and the like) matter
  only to `javadoc`; the build runs no `javadoc` and no `-Xdoclint`. The engine's two `{@link #solve3}` (lines 3187,
  3237) dangled before this batch (`solve3` is `SmallDenseSolve`'s) and are left.
- **Long line**: `CriticalBand.java:33` is 134 characters (no line-length check in the build).

## Edits that are not a pure move (the script's unmatched lines, each read by hand)

| Commit | Where (line at the tip, `6e968c8fc`) | Edit |
|---|---|---|
| 1 | `FluidTpEquilibrium.java:585` (`Workspace` constructor) | `new Slots(owner)` to `new Slots(owner.threeSlotFlash)` |
| 1 | `ThreeSlotFlash.java` `Slots` constructor | `Slots(FluidTpEquilibrium owner)` to `Slots(ThreeSlotFlash owner)` (its body unchanged) |
| 2 | `FluidTpEquilibrium.java` Workspace | `private boolean carrying, carriedProduct, standInHeld, beyondStandIn;` split: `beyondStandIn` stays private |
| 2 | `ThreeSlotFlash.java` constructor, class document | the `TwoPhaseFlash twoPhase` parameter and field |
| 2 | `FluidTpEquilibrium.java` constructor | `threeSlotFlash = new ThreeSlotFlash(this, twoPhaseFlash, ...)` |
| 2 | `TwoPhaseFlash.java` `differentiatePhase` | the signature's continuation line re-indented (whitespace) |
| 3 | `FluidTpEquilibrium.java` `solidCoverage`, `CrystalSet.java` record `Crystal` | continuation lines re-indented (whitespace) |

## Members widened (private to package-private), with who reads them

| Member | Read by |
|---|---|
| Constants `SINGLE`, `SPLIT`, `MERGED`, `FAILED`, `THREE` | both flashes (`THREE`, `SINGLE`: the three-slot system) |
| Constants `ARMIJO`, `NEWTON_HALVINGS`, `SHIFTS_FROM_1E8`, `FRACTION_TO_BOUNDARY`, `GIBBS_ROUNDOFF`; `SHIFTS_FROM_1E13` | both flashes; `SHIFTS_FROM_1E13` the three-slot system only (kept beside `1E8` and `shifts()`) |
| Constant `R` | `CrystalSet.familyTriplePoint` |
| Methods `stabilityOf`, `crystalRich`, static `solidCoverage(Coverage, PhaseCompetition, List<Crystal>)` | the flashes; `TwoPhaseFlash.finishSplit`; `CrystalSet`'s constructor |
| Methods `lowerGibbsRoot`, `differentiatePhase` | widened at stage 1, moved at stage 2 (package-private in `TwoPhaseFlash`, called by `ThreeSlotFlash`) |
| Class `Feed`; its `amounts`, `z`, `present`, `presentCount` | the flashes, `CriticalBand.inBand` (`basisAmounts`, the constructor and `takeAmounts` stay private) |
| `Workspace`: `logK`, `k`, `x`, `y`, `newLogK`, `delta`, `previousDelta`, `liquidLogPhi`, `vaporLogPhi`, `liquidAmounts`, `vaporAmounts`, `mixture`, `first`, `second`, `phases`, `vaporState`, `liquidState`, `ownSplit`, `kernelEvaluations`, `derivativeEvaluations`, `rachfordRiceSteps`, `iterations`, `extrapolations`, `newtonIterations`, `lastChange`, `pressure`, `vaporMoles`, `liquidMoles`, `trialVapor`, `trialLiquid`, `step`, `gradient`, `scale`, `reference`, `trial`, `hessian`, `factor`, `direction`, `vaporDerivatives`, `liquidDerivatives`, `lambda`, `tieLine`, `seedDistance`, `gibbsScale`, `trialIsVapour`, `vapourFraction`, `liquidFraction`, `liquidMinor`, `chosen`, `liquidProductDenseLiquidLike`, `vapourProductDenseLiquidLike`, `singleRoot`, `failure`, `residual`, `productDistance`, `carrying`, `carriedProduct`, `standInHeld`, `standIn`, `slots` | the flashes (`tieLine` also `CriticalBand.bandReason`); each is read by a new file (checked by script) |

Not widened: the engine's `count`, `kernel`, `evaluator`, `settings`, `stability`, `polar`, `cubicWater`, `contract`
(the holders receive them); `Search`; every other `Workspace` field.

## What stayed, and why

- `threeSlotFeed` (the core's dispatch, also read by `stabilityOf`) and `threePhaseResult` (a result builder; the
  review puts builders in the facade): in the engine. `pureBandBox`: public API. `shifts()`: the facade constants'
  initialiser. `solidCoverage` (both overloads): the crystal solves' coverage, the next slice's `CrystalEquilibrium`.
- The anchoring's inline 2x2 Newton in `familyTriplePoint` moved inside its method; no function of `science/math`
  is identical to it.
- The engine reference in the two flashes exists only for `stabilityOf` and `crystalRich`; it goes when the dry core
  and the carried crystals move (next slice, item 1).

## The next slice's order (proposal)

1. `DryCore` with `CarriedCrystals` in one slice: `hydrocarbon`, `feedStability`, `stabilityOf`, `threeSlotFeed`;
   `carriedTrial`, `crystalLean`, `supersaturated`, `crystalRich`, `carriedSplit`, `beyondStandIn` (it calls
   `hydrocarbon` back: a two-class cycle, one call deep). The flashes then hold `dryCore` and `carried` instead of the
   engine; the `Workspace` lambda calls `owner.carried.carriedTrial`.
2. `CrystalOnsets` (`onset`, `singlePhaseDrive`; the four public onset methods stay as the facade's entries).
3. `CrystalEquilibrium` (`solveWithCrystals` to `crystalPhasesHold`, with both `solidCoverage` overloads).
4. `BalanceSteps` (pure coexistence, the crystal steps, the triple point, the binary three-phase and lever steps), with
   D-E2's step 3 (the searches' values in a holder the search owns).
5. `SpecificationSearch` (PH, UV) with `Search` as `BracketSearch`; `Workspace` stays `FluidTpEquilibrium.Workspace`
   (public type used by the network and the tests) unless the owner accepts a renamed public type.
