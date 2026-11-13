# D-E2 report: the split a verdict judges is the solve's own; the scratch states

Branch `worktree-agent-a023a8377196fde87`, fast-forwarded to `d33c0a18a`. Commits: `ce9a24856` commit 1 (defect fix and
its test), `bb553d136` commit 2 (scratch states, structure only), then this documents commit (this report, the engine
chapter of the code guide, `CHANGELOG.md`). Step 3 is not done (below). Nothing was compiled or run: every claim is from
reading the code. Line numbers are at `bb553d136` unless marked old (`d33c0a18a`).

## Summary

1. **The defect** (AGENTS.md order). Expected: `splitChemicalPotentialsUsable(fluid, material, w)` judges only the split
   that the dry solve which produced `fluid` left in the workspace. What the code did: it tested `liquidState` and
   `vaporState` as the last writer left them. The three-slot audit failure (`threeSlot`, line 5473, `UNSTABLE_PRODUCT`
   after its one reseed) returns FAILED before `slotOutcome` and writes neither state, so the verdict read the pair of an
   earlier writer: a two-slot `slotOutcome` split of an earlier re-flash in the same crystal solve, an earlier TP of a PH
   or UV search, an earlier request on the workspace, or a scratch use (a pure coexistence pair has equal chemical
   potentials by construction). If every present component had x > 0 in both states and equal mu to 1e-6 RT, the
   verdict was true and the crystal was tested and solved on that unrelated pair. No other path types
   `UNSTABLE_PRODUCT` without its own pair: `finishSplit` (line 4911) writes both states before its stability re-check.
2. **Commit 1** (`ce9a24856`). `Workspace.ownSplit` (private): cleared at `hydrocarbon`'s entry (line 4560) and, in
   that commit, at every other writer of the two states; set right after a finish has written both: `finishSplit`
   (4920), `slotOutcome` with two slots (6033), `beyondStandIn`'s restated split (4746); `beyondStandIn`'s failure
   clears it (4733: the failure text is the whole feed's split again, the states may be the rest's). `carriedSplit`
   restates the pair `finishSplit` wrote just before in the same `hydrocarbon` call, so the flag is already set there.
   The one branch condition: `if (!w.liquidState.filled() || !w.vaporState.filled()) return false;` became
   `if (!w.ownSplit) return false;` (2346). The flag implies both filled (it is set only after both `evaluate` calls
   returned, and `PhaseState.set` validates before it assigns). No floating-point operation and no evaluation changed.
3. **The callers** (2250, 2585, 2715) each pass the answer of the `solveDry` on the line before (2248, 2574, 2713), so no
   dry solve runs between `fluid` and the verdict and no stamp is needed. Each `solveDry` calls `hydrocarbon` once; the
   nested `hydrocarbon` in `beyondStandIn` needs `w.carrying`, and a carried competition has no crystals (the
   `PhaseCompetition` constructor refuses it), so no verdict runs while carrying. Between `hydrocarbon`'s return and
   the verdict only the builder runs; the only builder that returns NOT_CONVERGED is `failed()`, which writes no state.
4. **What may move**: only a request where the verdict was true on a pair not the solve's own. Such a request now
   takes the false verdict's path: the fluid answer's failure is returned (`solveWithCrystals` 2251, `depositFromFluid`
   2591, `twoCrystals` 2716). A request whose own two-slot split failed keeps its verdict: the flag survives from
   `finishSplit` through the second attempt of `twoPhase` (which writes no state before its own `finishSplit`) to the
   builder and the verdict. The digest requests that can reach the stale path are listed below; by reading I cannot
   tell whether any of them does (it needs a three-slot audit failure inside the crystal solve, a numerical outcome).
5. **The test**: `AqueousEquilibriumTest.crystalAnswersDoNotDependOnWhatTheWorkspaceSolvedBefore`: the class's eight
   crystal requests on `pilot_aqueous` (ice out of 10 % ethanol, pure water at 272 K, ice with dry ice, CO2/water, ice
   beside a vapour and a liquid, beside two liquids and a vapour, beside two liquids, the ternary with nitrogen), each
   on a fresh workspace and after each of five primers on one workspace (the same compounds fluid-only at 300, 340 and
   370 K and the request's pressure; ethanol/water at 353.15 K; water/n-hexane at 298.15 K): 40 pairs, compared as
   `EngineDigestTest.describe` lines (every bit and count). **The failing case is not pinned**: no existing test or
   digest request is known, by reading, to end the whole-feed solve or a re-flash in a three-slot audit failure, so I
   cannot say that the test fails on `d33c0a18a`. It pins history independence for these answers; a failure there
   after commit 1 would be another history dependence of the workspace (none is known to me).
6. **Commit 2** (`bb553d136`, structure only). `pureCoexistence`, `pureCrystalStep`, `stepFluid`, `singlePhaseDrive`,
   `singlePhase` and `merged` evaluate into one new scratch pair (`vaporScratch`, `liquidScratch`, two `PhaseState`s
   allocated in the `Workspace` constructor, none per request on a reused workspace) and no longer clear the flag.
   Vapour writes go to `vaporScratch`, liquid writes to `liquidScratch`, so the aliasing among these six uses is the
   one they had (they all wrote the same two objects). Proof below. After it, `liquidState` and `vaporState` are written
   only by `finishSplit`, `slotOutcome`, `carriedSplit` and `beyondStandIn`.
7. **Step 3 not done** (optional): see "The searches' values" below.
8. **Lines**: `FluidTpEquilibrium` 6188 to 6214 (commit 1 +24 net, commit 2 +2 net); `AqueousEquilibriumTest` +46.
   `Workspace`'s public surface is unchanged (one private boolean, two private final states); grepped `src/main`,
   `src/test`, `src/fluidGameTest` and `tools/**/*.java` for `ownSplit`, `vaporScratch`, `liquidScratch`,
   `liquidState`, `vaporState`: no use outside `FluidTpEquilibrium.java` (the `state.*` keys in the client are unrelated).

## Tests for the main thread

One run, `test` task (every class below is in the everyday group):

`./gradlew test --tests "*.EngineDigestTest" --tests "*.AqueousEquilibriumTest" --tests "*.FluidTpEquilibriumTest" --tests "*.FluidTpEquilibriumP3Test" --tests "*.FluidTpEquilibriumPhUvTest" --tests "*.GasSolidEquilibriumTest" --tests "*.TangentPlaneStabilityTest" --tests "*.NearPureCrystalStepTest" --tests "*.CarriedCrystalsTest" --tests "*.NetworkPhaseEngineTest" --tests "*.CrystalDepositionIslandTest" --tests "*.CrystalFlushBudgetTest" --tests "*.WaterShareTest" --tests "*.LiquidFullBubblePointTest" --tests "com.wormzjl.icheme.science.thermo.qualification.*" --tests "*.ThermoLayeringTest" --tests "*.LayeringTest"`

- `EngineDigestTest`: at `ce9a24856`, the reference unchanged or the moved lines (below); at `bb553d136`, the same as at
  `ce9a24856` (commit 2 moves nothing). Run once at the tip if it passes; if it fails, run it at `ce9a24856` to tell the
  fix's moves from a commit 2 fault (a move at the tip that is not one at `ce9a24856` is a commit 2 fault).
- `AqueousEquilibriumTest`: the new case (history independence of the eight crystal answers) and the class's own
  expectations of those answers.
- The other engine and crystal classes as D-E1 listed them: the engine's expectations, crystal answers through the
  network, the qualification gates (`G5F6ConsistencyTest` reads fluid-only answers whose detail holds `UNSTABLE_PRODUCT`).
- Chain-100 exact (`FluidSolverRegressionTest`, task `fluidSolverRegression`) and the fluid GameTests load the engine:
  the batch's final lane. Chain-100 can move only if one of its islands solves a crystal competition (not carried) on a
  feed with a polar compound and meets the stale path; I did not trace which competitions the network asks.

### Digest requests that can move at commit 1

A move needs a crystal competition, a feed with a polar compound in the cubic (the three-slot system), and a three-slot
audit failure in a solve inside the crystal solve, with a stale pair that passes. A moved line becomes the fluid's
typed failure, `TP NOT_CONVERGED UNSTABLE_PRODUCT` (or `CRITICAL_BAND+UNSTABLE_PRODUCT`; for PH or UV, the search's
failure built on that TP, or a different converged answer if the search goes around the state). Candidates (ids from
the reference): `pilot_aqueous` lines 220 to 236 (ice out of 10 % ethanol, pure water 272 and 274 K, N2/CO2/H2O with
its PH and UV, CO2/H2O, H2O/C6/N2, the ethanol/water/hexane cases with their PH and UV) and 279 to 284 (the water
melting sweep); `tjl20_methane_nitrogen+water` 353 to 355 (H2O/N2 and its PH, UV); `refinery+water` 392 and 393
(N2/H2O, N2/CO2/H2O), and 374 (refinery gas) only if its derivation marks one of its species polar. Every other line
cannot move: no crystal competition, no polar compound in the cubic, or a carried competition. My expectation, not a
proof: none moves, because each candidate's answer is converged and a three-slot audit failure needs a failed reseed;
the in-request stale path (an earlier re-flash's pair, or an earlier TP of the PH or UV search) is the likelier one if
any line moves.

## Uncertain points

- **Compilation** (not built). Commit 1: one private field, assignments, one condition. Commit 2: two private final
  fields assigned once in the constructor (javac's definite assignment: both assigned, no other assignment), seven
  argument replacements. The test: `EngineDigestTest.describe(EquilibriumResult, double[])` is package-private static
  in the same package (`science.thermo.phase`) and test source set; `EquilibriumRequest.temperature()`, `pressure()`,
  `amounts()` are public accessors of a TP request; `ENGINE.tp(request, workspace)` and `newWorkspace()` are public;
  new imports `java.util.ArrayList`, `java.util.Arrays`; boxed `(Double)` casts of `Object[]` literals as in
  `phAndUvRoundTrips`. The Javadoc `{@link #liquidState}` inside `Workspace` and `{@link #pureCrystalStep}` on
  `stepFluid` matter only to `javadoc`.
- **Bits of commit 2.** Rests on three facts read in the code: `CubicPhaseEvaluator.evaluate` returns its `out` and
  never reads it before `PhaseState.set` (`fill`, lines 244 to 287 of the evaluator); `set` overwrites every scalar
  and copies every array in full; no outcome reader runs after a scratch write without an outcome write between (table
  below). If an evaluation throws inside `set`'s validation the state keeps its old content; every scratch use but
  `singlePhaseDrive` lets the exception out (to `evaluateTp`'s catch, no state read), and `singlePhaseDrive` returns NaN
  without reading.
- **The test's cost**: 48 crystal TP solves and 40 fluid TP solves on `pilot_aqueous`; the CO2/water request took 86
  fluid equilibria in the digest. My estimate is a few seconds of CPU; not measured.
- **The test's primers**: whether each primer splits is not asserted (a primer is only history); for pure water and
  some feeds the warm primers are one phase.
- **Line endings**: both edited source files keep CRLF on every line (6214 and 491 lines, no LF-only line; checked with
  a `node` count); `git diff --stat` per commit shows only the intended files.

## The flag: set and clear sites, and the order of calls on each path

| Path (in `solveDry`) | Calls in order | `ownSplit` at the verdict |
|---|---|---|
| Feed stable or unresolved | `hydrocarbon` clears; SINGLE (`singlePhase`, converged) or FAILED (`failed`) | false; a FAILED unresolved feed has no `UNSTABLE_PRODUCT` detail anyway |
| Two-slot split, products stable | clears; `twoPhase` → `attempt` → `finishSplit` sets (4920), SPLIT | true, but the answer is converged: verdict false at its first line |
| Two-slot, `finishSplit` merges | clears; `finishSplit` sets, MERGED → `merged` (scratch since commit 2) | converged: not reached |
| Two-slot, a product unstable | clears; attempt 1 `finishSplit` sets, FAILED; attempt 2 either fails before its `finishSplit` (flag stays: attempt 1's pair, this solve's) or reaches it (sets again, its own pair) → `failed` | true: the solve's own split, as before |
| Three-slot, audit stable, two slots | clears; `threeSlot` → `slotOutcome` sets (6033), SPLIT or MERGED | converged: not reached |
| Three-slot, audit stable, one or three slots | clears; `slotOutcome` returns SINGLE or THREE before writing | converged: not reached |
| Three-slot, audit failure (5473) | clears; FAILED → `failed` | **false** (was: whatever the last writer left): the fix |
| Three-slot, substitution failure (`slotSubstitution` false) | clears; FAILED, detail not `UNSTABLE_PRODUCT` | not reached past the detail test |
| Carried (no crystals, so no verdict) | clears; `finishSplit` sets; SPLIT → `carriedSplit` (keeps); FAILED with a stand-in → `beyondStandIn` → inner `hydrocarbon` clears, may set; restated split sets (4746), failure clears (4733) | no verdict while carrying |

Callers of `splitChemicalPotentialsUsable` and the dry solves since the answer they pass:

| Caller (line) | Answer passed | Dry solves between |
|---|---|---|
| `solveWithCrystals` (2250) | `solveDry(.., w.request, w)` (2248) | none |
| `depositFromFluid` (2585) | `solveDry(.., w.reducedFeed, w)` (2574) | none |
| `twoCrystals` (2715) | `solveDry(.., w.pairFeed, w)` (2713) | none |

After a true verdict the reads of `w.liquidState` (`solveWithCrystals` 2262, `fluidMuOf` 2674 from 2306 and 2717,
`depositFromFluid` 2587) follow with only crystal evaluations into `solidState` in between.

## Commit 2: what reads each scratch use's states next

| Use (line) | Writes (now) | Read back by | Between the write and the read | Read next as an outcome by |
|---|---|---|---|---|
| `pureCoexistence` (2044, 2045) | `liquidScratch`, `vaporScratch` | itself, values and `copy()` | `inBand`, `bandReason`, `converged`, `diagnostics`: no state write | nothing: the outcome readers read `liquidState`/`vaporState`, written by a finish in the same `hydrocarbon` call before every read |
| `pureCrystalStep` (3099) | `vaporScratch` (`fluid`) | itself | `traces`, `stepFluid` (writes the same object; `fluid` is reassigned to its return), `crystalStepHold`; the crystal into `solidState` | nothing |
| `stepFluid` (3192) | `vaporScratch` | `pureCrystalStep` | none | nothing |
| `singlePhaseDrive` (4357) | `liquidScratch` | itself, one chemical potential | the crystal's `chemicalPotential` (no state) | nothing |
| `singlePhase` (4442, 4443, 4451) | both, or `vaporScratch` | itself, `copy()`; `singleClassification`, `singleLabel` (kernel into `w.first`) | none | nothing |
| `merged` (4499) | `vaporScratch` | itself, `copy()` | none | nothing |

Before commit 2 the outcome states could be read after a scratch write only by `splitChemicalPotentialsUsable`
(D-E1's table); commit 1's flag made that read false after any scratch write. At a verdict that passes the detail test
the flag has the same value at `ce9a24856` and at `bb553d136`: such a verdict follows a `solveDry` that returned through
`failed()`, so no scratch writer (`singlePhase`, `merged`) ran after its `hydrocarbon` cleared the flag; the PH, UV and
onset scratch uses run between requests' TP solves, and each verdict's own `hydrocarbon` clears the flag first.

## The searches' values (step 3): not done

`coexistenceX`, `coexistenceResidual`, `innerResult` and `innerFailure` are out-parameters handed over two levels:
`pureCoexistence`, `pureCrystalStep`, `binaryThreePhase` and `binaryFluidThreePhase` leave the step's side for
`closedPh` (1396) or `closedInner` (1982), which return null and leave it for `solvePh` (1366) or `innerUv` (1944) to
record; `innerUv` and `closedInner` leave `innerResult` and `innerFailure` for `nestedUv` (1751, 1772) and `solveUv`
(1467). A value the callers receive without an allocation is a workspace-owned holder passed down and read back at the
same places, still with NaN for "no side": the same state under another name, about 40 edited lines in the PH and UV
searches, which S01 moves into `SpecificationSearch` and `BalanceSteps`. A record returned per closed bracket would be
a value but allocates on each PH and UV iterate. Neither meets "a pure renaming that turns them into values" well
enough to be worth an unbuilt change here; I propose to fold it into S01, where the holder becomes the search's own
field.

## What was left

- Step 3, for the reason above.
- The second attempt of `twoPhase` can leave a detail that starts with attempt 1's `UNSTABLE_PRODUCT` while the states
  hold attempt 2's split (its `finishSplit` failed `UNRESOLVED_PRODUCT`). Both are the same dry solve's own splits, so
  this is no history dependence and the flag keeps today's verdict; noted only.
- `PhaseState` holds old content after a refused `set`; no reader depends on it (above).
- The batch README and `documentation/INDEX.md` are the main thread's (history line at merge).
