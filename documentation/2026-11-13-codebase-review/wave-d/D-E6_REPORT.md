# D-E6 report: the engine's failure kind and one refusal type (S06, S07's Java part)

Branch `worktree-agent-ad5bc398221d21f00`, fast-forwarded to `f87936d22`. Commits: `8dd5fdc3b` step 1 (`FailureKind`),
`27baff246` step 2 (`ThermoRefusal`), `c156670e4` step 3 (the engine's catch site, `EngineFailureKindTest`), then this
documents commit (this report, the engine chapter of the code guide). Nothing was compiled or run: every claim is from
reading the code and `node` scripts in the worktree's `tmp/de6/` (throw and catch tables, argument counts, braces, CRLF).

## Summary

1. **`EquilibriumResult.FailureKind`**: `NONE`, `UNSUPPORTED`, `REFUSED`, `INTERNAL`, `OUTSIDE_BOX`, `FLASH`,
   `UNSTABLE_PRODUCT`, `UNRESOLVED_PRODUCT`, `CRYSTAL_SOLVE`, `CRYSTAL_PHASES`, `CRYSTAL_STEP`, `SEARCH`. The review's
   list plus what table 1 showed missing: `UNSUPPORTED` (typed reasons), `FLASH` (the dry core's untyped failures),
   `CRYSTAL_SOLVE`, `INTERNAL`. The band is the flag `failedInCriticalBand()` (detail starts with `CRITICAL_BAND`); a
   banded failure keeps its own kind. No `detail()` text changed; the prefix constants stay for the readers outside.
2. **Construction.** The public 11- and 12-argument constructors keep their signatures (kind `NONE` or `UNSUPPORTED`
   by status; a `NOT_CONVERGED` through them is refused). `notConverged` now takes `(kind, failedInCriticalBand)`
   before the detail: its 12 callers are all in `science/thermo/phase` (grep of every source set and `tools/`). A
   package-private 14-argument constructor serves `outerAnswer`, `withCoverage`, `crystalPhasesHold`. New checks run
   after the existing ones: `NONE` exactly when converged; unsupported is `UNSUPPORTED` or `CRYSTAL_PHASES`; the band
   flag only when not converged. No outside constructor call changed (`SolidPhaseEvaluatorTest`'s three: converged).
3. **Kinds travel with the text.** `Workspace.failure` gains `failureKind`, written at each of its 13 writes (the
   Wilson retry keeps the first attempt's kind, as its text starts with it; `beyondStandIn` saves and restores both);
   `innerFailure` gains `innerFailureKind`. The one reader in `science/thermo/**`,
   `CrystalEquilibrium.splitChemicalPotentialsUsable` (the prefix test with the per-call `"): " + UNSTABLE_PRODUCT`
   pattern), reads `failureKind() == UNSTABLE_PRODUCT`. Same truth value: the only text with `"): UNSTABLE_PRODUCT"` is
   the band prefix before a failure that starts with it (checked against every `w.failure` text and band reason).
4. **`ThermoRefusal`** (`science/thermo`, kernel): `extends IllegalArgumentException`, `fillInStackTrace` returns
   `this`, message parts kept and joined on the first `getMessage()` with the same string conversions. 40 sites (table
   2). Sites that formatted per throw, now lazy: `NumericalBox` 42, 54; `PengRobinsonKernel` 525, 582;
   `CubicPhaseEvaluator` 211; `WaterShare` 249; `DiluteSteamViscosity` 26, `SteamVirial` 109, `WagnerPruss` 39.
5. **Kept JDK types (refusals):** `PengRobinsonKernel.NoPhysicalRoot` (1651, already a typed `IllegalStateException`)
   and the coalescence in `differentiatePrepared` (806, plain `IllegalStateException`). Reason: outside catchers that
   catch one of the two JDK types only, and differently: `V3PengRobinsonSession` 124/127 and 139/142 map IAE to
   `Code.DOMAIN` and ISE to `EOS_ROOT_FAILURE`; `HydrocarbonModel` 169 catches `NoPhysicalRoot`, 214 ISE only; inside,
   `TangentPlaneStability` 508/546/572 and `TwoPhaseFlash` 338 catch ISE only.
6. **The engine's catch site** (`FluidTpEquilibrium.evaluateTp` 657/661): `ThermoRefusal | NoPhysicalRoot` → `REFUSED`;
   every other IAE/ISE/ArithmeticException it caught → same text, `INTERNAL`. `NoPhysicalRoot` as `REFUSED` goes past
   the brief's letter (only `ThermoRefusal`): a missing physical root is a numerical guard, not a defect (AGENTS.md,
   thermo validity). The 17 trial catch sites reject a trial and build no answer: unchanged (table 3).
7. **Digest:** no reference line is a refused evaluation (the reference's typed field never reads `REFUSED`), so no
   request can end `INTERNAL`; `EngineFailureKindTest` pins the list (empty) and checks every answer's kind and band
   flag against the types its detail carries.
8. **Decision for the main thread (coalescence).** Reaching `evaluateTp` (an unguarded `evaluator.derivatives`, e.g.
   `CrystalEquilibrium` 698), the coalescence reads `INTERNAL`. Options: (a) a typed stackless ISE beside
   `NoPhysicalRoot` in the kernel, caught as `REFUSED` (no outside caller changes; a second refusal type); (b) leave it
   until the kernel returns a status (S07's Rust part). I recommend (a).
9. **Water module converted** (9 sites). Every outside catcher still catches them. The one visible difference is a
   class name in texts that print it: `V3LearnedRoute` 83, 117 ("neural seed rejected: " + simple name),
   `V3AnchorTransformerInitializer` 393, `SimulatorRunner` 273 (`String.valueOf`), `BoundedCpuSolveService` 669 (an
   escaped failure); the same holds for any converted refusal that escapes to them. No test resource holds such a
   text. To keep the water module's IAE: `git checkout 8dd5fdc3b -- src/main/java/com/wormzjl/icheme/science/thermo/water`.

## Table 1: failure texts (builders, kinds, readers)

| Text (prefix or opening) | Builder (tip lines) | Kind | Readers |
|---|---|---|---|
| converged details | `FluidTpEquilibrium.converged` 912; `BalanceSteps` 431, 615, 910, 997, 1233; `CrystalEquilibrium` 119, 599, 630, 738, 747 | `NONE` | none by prefix |
| typed reasons (contract, competition, no crystal evaluator) | `FluidTpEquilibrium.unsupported` 918 | `UNSUPPORTED` | none |
| `CRYSTAL_PHASES:` | `CrystalEquilibrium.crystalPhasesHold` 761 | `CRYSTAL_PHASES` | `FluidThermodynamics` 319 |
| `the state ... outside the numerical box` | `FluidTpEquilibrium.outsideBox` 636 | `OUTSIDE_BOX` | digest classifier (test) |
| `evaluation refused:` | `evaluateTp` 660 / 665 | `REFUSED` / `INTERNAL` | digest classifier (test) |
| `UNSTABLE_PRODUCT:` / `UNRESOLVED_PRODUCT:` | `TwoPhaseFlash` 262-266, `ThreeSlotFlash` 172-175 via `DryCore.failed` 221 | same names | `CrystalEquilibrium` 181 (kind now); `FluidThermodynamics` 1278-1279 |
| stability unresolved, substitution, Newton finish, three-slot | `DryCore` 256; `TwoPhaseFlash` 82, 148, 320, 340, 358, 381, 417; `ThreeSlotFlash` 260, 265 | `FLASH` | none |
| `CRITICAL_BAND (...): ` + failure | `DryCore.failed` 221; `SpecificationSearch.outerTp` 150, `searchFailure` 1047 | inner kind, flag set | `FluidThermodynamics` (via `diagnostics().criticalBand()`) |
| crystal solve not converged, fluid not carried, two crystals | `CrystalEquilibrium` 368, 379, 472, 574, 606, 623 | `CRYSTAL_SOLVE` | none |
| `CRYSTAL_STEP:` | `BalanceSteps.crystalStepHold` 450; `searchFailure` via `crystalStepKind` 1005 (398, 798, inner 990) | `CRYSTAL_STEP` | `FluidThermodynamics` 324 |
| search budget, no equal fugacity, step, window | `searchFailure` 268, 301, 398, 700, 725, 798, `outsideWindow` 1058; inner 860, 884 | `SEARCH` | none |
| TP answer + `; ` note | `SpecificationSearch.outerAnswer` 1031 | the answer's | none |

## Table 2: throw sites in `science/thermo/**` (190: 40 converted, 150 kept; tip lines, `s` = ISE)

| File | Lines | Decision |
|---|---|---|
| `NumericalBox` | 42, 54 | refusal: `ThermoRefusal` (the box) |
| `PengRobinsonKernel` | 424, 525, 582, 1580, 1725, 1732, 1739, 1747 | refusal: range, non-finite pair or cell, reduced parameters, trial composition, state |
| `PengRobinsonKernel` | 806s; `NoPhysicalRoot` 1651 | refusal, kept ISE (summary 5) |
| `PengRobinsonKernel` | 170, 173, 178, 198, 211, 279, 301, 311, 1719, 1728, 1752, 1988, 2018, 2069, 2184s | bug: construction, lengths, support, ownership, protocol |
| `TangentPlaneStability` | 182, 701, 704, 715 / 65, 151, 190, 698 | refusal (pressure, feed amounts) / bug (settings, workspace, length) |
| `TranslatedCubic` | 222, 294, 307, 357, 378, 401, 405 / 85, 101, 106, 116, 215s, 265s, 293, 359, 360, 426 | refusal (properties, temperature, root, derivative) / bug (construction, generation, ownership) |
| `phase/CubicPhaseEvaluator` | 199, 211, 232, 237, 240 / 97, 101, 171, 223, 234 | refusal / bug |
| `phase/TwoPhaseFlash` 517, `ThreeSlotFlash` 665 | | refusal: non-finite Gibbs energy of a trial split |
| `phase/WaterShare` | 249, 277, 289 / 117 | refusal / bug (ownership) |
| `water/*` | `DiluteSteamViscosity` 26, `Iapws97Region1` 116, 121, 131, `SteamIdealGas` 31, `SteamVirial` 109, `WagnerPruss` 39, 65, 69 / `WaterConstants` 32 | refusal (summary 9) / bug |
| `HuronVidalExcess`, `PairInteractions`, `ThermoComponent`, `TraceTruncationPolicy` | all (16, 8, 2, 3) | bug or configuration |
| `phase/EquilibriumResult` | 135, 341-382 (3 new: 376, 380, 382), 444, 498s, 499s | invariant, misuse |
| `phase/` request, contract, competition, domain, identity, binding, state, amounts, onsets, crystal set and evaluator, `BalanceSteps` 321s, facade 210, 280, 285, 841, 930, 937 | all | argument, invariant, configuration |

## Table 3: catch sites

Engine (`science/thermo/**`): `evaluateTp` 657 (refusal → `REFUSED`), 661 (rest → `INTERNAL`); trial sites, unchanged,
each rejecting the trial (no answer): `TangentPlaneStability` 508, 546, 572 (ISE); `TwoPhaseFlash` 338 (ISE), 406, 476
(IAE); `ThreeSlotFlash` 198 (halve, rethrow after the last), 541, 616; `SpecificationSearch` 218, 454; `BalanceSteps`
1282, 1308; `CrystalEquilibrium` 664; `CrystalOnsets` 265; `CrystalSet` 127, 173 (construction).

Outside (127 sites in files that import `science.thermo` or `science.fluid.thermo`, plus the still and `FlowThermo`;
`tmp/de6/reach.txt`): 44 catch IAE and ISE (or a supertype), 78 IAE only, 5 ISE only (`V3PengRobinsonSession` 127,
142; `HydrocarbonModel` 214; `ProcessSimulatorScreen` 423; `TopologyChange` 147). Every converted site was an IAE and
is one still, so all 122 that catch IAE catch it as before; no ISE site changed. The V3 classes without a thermo import
receive the water module's refusals through `V3WaterProperties`: all catch IAE where they catch them.

## For the main thread: readers of failure texts outside `science/thermo/**` (not edited)

`science/fluid/thermo/FluidThermodynamics.java` (lines at `f87936d22`; the file is being edited elsewhere):
- 319 `crystalAnswer`: `detail().startsWith(CRYSTAL_PHASES)` ≡ `failureKind() == CRYSTAL_PHASES`.
- 324 `crystalAnswer`: `startsWith(CRYSTAL_STEP)` ≡ `failureKind() == CRYSTAL_STEP && !failedInCriticalBand()` (it runs
  before the band check at 326).
- 1278-1279 `adopt`: `startsWith(UNSTABLE_PRODUCT)` / `UNRESOLVED_PRODUCT` ≡ the kind (after 1275's
  `diagnostics().criticalBand()` throw; `failedInCriticalBand()` implies that flag at every builder).
Tests reading prefixes (unchanged texts): `NetworkPhaseEngineTest` 152, 361, 369, 402; `CrystalDepositionIslandTest`
407-409; the engine's own tests in table 1's "Readers".

## Tests for the main thread

`test`: `./gradlew test --tests "*.EngineFailureKindTest"` plus the D-E5 list (`EngineDigestTest`, ..., `LayeringTest`)
and `"*.NetworkPhaseEngineTest" "*.FluidTpEquilibriumP3Test" "*.NearPureCrystalStepTest"
"*.PengRobinsonKernelRootPrecisionTest" "*.PengRobinsonKernelPairInteractionsTest" "*.G3F5CriticalRegionPathTest"
"*.PhaseTraitsTest" "*.SolidPhaseEvaluatorTest" "*.TangentPlaneStabilityTest" "*.WaterShareTest" "*.SteamVirialTest"
"*.WaterModuleTest" "*.CubicPhaseEvaluatorTest" "*.DataRangeContinuationTest" "*.RangeContinuationGateTest"
"*.FluidPropertyCoverageTest" "*.ThermoLayeringTest"`. Proofs: the compile of every source set (first proof);
`EngineDigestTest` unchanged (no operation, text or kernel call moved); `EngineFailureKindTest` (no `INTERNAL`, kinds
match texts); P3, near-pure, G3F5, network engine (band, product, crystal-step prefixes); the kernel, water and stability
tests (refusals still IAE with the same messages); `ThermoLayeringTest` (`ThermoRefusal` names only the kernel).
`columnGates`: `"*.V3PengRobinsonKernelTest" "*.V3PengRobinsonThermoTest" "*.V3PengRobinsonDerivativesTest"
"*.V3TruncatedFlashNumericsTest" "*.V3WaterPropertyDerivativesTest" "*.V3IdentityTest" "*.V3FlashCheckpointTest"` (they
catch kernel or water refusals). Chain-100 exact and the fluid GameTests: the final lane.

## Not verified without a compiler

- `ThermoRefusal` 30-62: four public constructors onto one private one (argument order checked by reading); `switch`
  on the `int` form with `static final int` labels; `fillInStackTrace` override without `synchronized` semantics lost.
- `FluidTpEquilibrium` 657: multi-catch `ThermoRefusal | PengRobinsonKernel.NoPhysicalRoot` (unrelated types), then
  the three JDK types (supertypes of the first: legal after it); the parameter `RuntimeException` of `refusedEvaluation`.
- `EquilibriumResult` 312-314: `this(...)` with a conditional on the parameter `status`; 376-384: the two final fields
  assigned after the checks. `EngineFailureKindTest` 66-77: an exhaustive `switch` expression on `Status` with `yield`
  in a block.
- Argument counts of every `notConverged` (7), `new EquilibriumResult` (11, 12, 14) and `searchFailure` (6) call: by
  script (`tmp/de6/args.mjs`).
