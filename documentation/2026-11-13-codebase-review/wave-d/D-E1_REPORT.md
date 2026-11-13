# D-E1 report: the engine's dry core on an explicit feed (S02)

Branch `worktree-agent-a2090118279f60103`, fast-forwarded to `7b36c23ff`. Commits: `5334ad72b` step 1 (production code),
then this documents commit (this report and the code guide's engine chapter). Steps 2 and 3 are not done (below).
Nothing was compiled or run: every claim below is from reading the code.

## Summary

1. **Step 1, done.** `FluidTpEquilibrium` gets a private `Feed` holder (the cubic's amounts, the basis amounts, the
   composition `z`, the present list and its count; `takeAmounts` copies both amount arrays). The workspace's five
   request fields become one `Feed request`; `savedFeed`, `savedBasis` and `restoreFeed` are deleted; three
   preallocated scratch feeds are added: `reducedFeed` (the fluid a crystal leaves), `pairFeed` (`twoCrystals`: the
   request less the partner's solid) and `standInFeed` (`beyondStandIn`). `solveDry`, `hydrocarbon` and the 33
   functions under them take `Feed material` (before `Workspace w`; `inBand` and `slotCurvature` take it instead of
   the workspace, `shiftedSolve(material, w, m)`), and none reads a workspace feed: a grep of the core's range
   (`solveDry` to `bandReason`) finds no `w.request`, `w.reducedFeed` or `w.pairFeed`, and no `w.feed`, `w.z`,
   `w.present`, `w.presentCount`, `w.basisAmounts`, `w.savedFeed` or `w.savedBasis` is left in the file.
2. **The crystal solves** build their reduced feeds in the scratch feeds (the same arithmetic: element `i` set to
   `z e^s`, `vapour`, or 0; the partner to `saved - amount` or 0) and pass them; every read of `savedFeed` reads
   `w.request.amounts`, never overwritten now. The read-by-read table below gives, for each read in or near the two
   old save/restore windows, which feed it saw before and which it gets now: the same values, element by element.
3. **Removed work.** Every `restoreFeed` call (five) and both save copies. A restore's only effect was to put back
   values that the new code never overwrote, so no read sees a different value. In `reduceFeed` the restore's first
   composition loop is gone: its outputs were overwritten in full by the second loop (`z` for every component, the
   present list up to its count; no read goes past the count, checked for every `present[...]` index in the file).
   In `twoCrystals` the second `reduceFeed` after a null vapour solve is gone: `pairFeed` was not written by that
   solve (it writes `reducedFeed`), and the call rebuilt the same values from the same inputs.
4. **Step 2, not done: every scratch use is read later.** The four named uses (`pureCoexistence`, `pureCrystalStep`,
   `stepFluid`, `singlePhaseDrive`) and two more inside the core's result builders (`singlePhase`, `merged`) write
   `liquidState` or `vaporState`. A three-slot audit failure typed `UNSTABLE_PRODUCT` (`threeSlot`, about line 5447)
   writes neither state, and `splitChemicalPotentialsUsable` then judges the two states as the last writer left them,
   in the same request or an earlier one on the same workspace. Giving any scratch use its own state changes what
   that read can see (proof below), so by the brief each use stays. This is a defect (results depend on the
   workspace's history, against the `Workspace` comment "results do not depend on what the workspace computed
   before"); the proposal is below, for the owner.
5. **Step 3, skipped:** the brief makes it conditional on steps 1 and 2.
6. **Lines.** `FluidTpEquilibrium` 6156 to 6188 (+32 net: 480 lines in, 448 out, most of them the same line with
   `w.feed` renamed; added are the `Feed` class, the four feeds and their comments, removed `restoreFeed`, the save
   copies and the restore calls). The review's -80 for S02 assumed all three steps and a `DryOutcome`; step 1 alone
   adds the holder.
7. **Allocation.** No allocation per evaluation is added. A `Workspace` now allocates 16 arrays of feeds instead of 6
   (four feeds of `count`, `basis`, `count` and `count` ints, against `feed`, `z`, `present`, `basisAmounts`,
   `savedFeed`, `savedBasis`). A caller that builds a workspace per request (the convenience `tp(request)`,
   `ph(request)`, `uv(request)` overloads) allocates those 10 arrays more per request; the network reuses its
   workspace per thread.
8. **Public surface.** Unchanged: `Workspace` keeps only its private fields and constructor; `Feed` is private.
   Grepped `src/main`, `src/test`, `src/fluidGameTest` and `tools/**/*.java`: no reference to a changed private
   member (reflection on `FluidTpEquilibrium` members: none; the tool probes call `tp`, `ph`, `uv`, `newWorkspace`).

## Tests for the main thread

One run, `test` task (all classes are in the everyday group; none in `columnGates`, `equipmentGates` or `holdGates`):

`./gradlew test --tests "*.EngineDigestTest" --tests "*.FluidTpEquilibriumTest" --tests "*.FluidTpEquilibriumP3Test" --tests "*.FluidTpEquilibriumPhUvTest" --tests "*.AqueousEquilibriumTest" --tests "*.GasSolidEquilibriumTest" --tests "*.TangentPlaneStabilityTest" --tests "*.ThermoLayeringTest" --tests "*.LayeringTest" --tests "*.NearPureCrystalStepTest" --tests "*.CarriedCrystalsTest" --tests "*.NetworkPhaseEngineTest" --tests "*.CrystalDepositionIslandTest" --tests "*.CrystalFlushBudgetTest" --tests "*.WaterShareTest" --tests "*.LiquidFullBubblePointTest" --tests "*.SolidPhaseEvaluatorTest" --tests "*.SpinePackageEvaluatorTest" --tests "com.wormzjl.icheme.science.thermo.qualification.*"`

- `EngineDigestTest` (reference unchanged): the 429 answers' bits and stable counts on nine engines; it proves step 1
  moved no operation and no kernel evaluation, including the crystal solves (one and two crystals, complete
  deposition), the carried path with `beyondStandIn`, and the PH/UV searches that read the request's composition.
- `FluidTpEquilibriumTest`, `FluidTpEquilibriumP3Test` (reused-workspace determinism), `FluidTpEquilibriumPhUvTest`,
  `AqueousEquilibriumTest` (per-answer kernel table), `GasSolidEquilibriumTest`, `NearPureCrystalStepTest`,
  `CarriedCrystalsTest`, `NetworkPhaseEngineTest`: the engine's own expectations.
- `CrystalDepositionIslandTest`, `CrystalFlushBudgetTest`: crystal answers through the network.
- `WaterShareTest`, `LiquidFullBubblePointTest`, `SolidPhaseEvaluatorTest`, `SpinePackageEvaluatorTest`, the
  qualification classes (`G3*`, `G4*`, `G5*`, in `science/thermo/qualification`): the other test classes the grep
  finds that name `FluidTpEquilibrium` or a crystal competition.
- `TangentPlaneStabilityTest`, `ThermoLayeringTest`, `LayeringTest`: unchanged neighbours and the layer rules.
- Chain-100 exact (`FluidSolverRegressionTest`, task `fluidSolverRegression`) and the fluid GameTests load the
  engine; they belong to the batch's final lane.

## Uncertain points

- Compilation (not verified by a build). Every call of a changed method was checked by a script that matches each
  call's argument count and the position of its `Feed` argument against the declarations (`tmp/d-e1/arity.mjs`: no
  mismatch). Points a compiler could still refuse: `Feed` is a private static nested class used as the type of
  private fields of the public nested `Workspace` (legal in Java); `Feed`'s constructor parameters `count` and
  `basis` shadow nothing in a static class; the Javadoc `{@link #twoCrystals}` and `{@link #hydrocarbon}` inside
  nested classes (as the existing `{@link #searchWindow}` there) only matter to `javadoc`, not `javac`.
- Bits. Step 1 changes no floating-point operation and none is reordered: the subtraction in `beyondStandIn`
  (`rest.amounts[i] = material.amounts[i] - held[i]`, `FluidTpEquilibrium.java` about line 4699) is the same IEEE
  operation as the old `w.feed[i] -= held[i]`. The one argument that rests on reasoning, not on a one-to-one
  replacement: after a crystal solve the request's `z` and present list are now those of the first `solveDry` on the
  request (`solveWithCrystals`, about line 2231) instead of `restoreFeed`'s recomputation; both run the same loop
  (sum from 0.0 in index order, `z_i = n_i / total`, present by `n_i > 0`) on the same amounts, so they are equal.
- The scripted part of the edit (`tmp/d-e1/edit.mjs`, 389 token edits on 341 lines) kept every line ending (6188 CRLF
  lines, none LF); `git diff --stat` shows only `FluidTpEquilibrium.java` in the code commit.

## Step 1: the read-by-read table

Old line numbers are at `7b36c23ff`; "base" is the feed a single crystal solve starts from: the request's from
`solveWithCrystals`, the pair feed from `twoCrystals`.

| # | Function (old line) | Read or write | Held before | Gets now |
|---|---|---|---|---|
| 1 | `solveWithCrystals` (2204) | `solveDry` on `w.feed` | request | `solveDry(.., w.request, w)` |
| 2 | `solveWithCrystals` (2206) | `splitChemicalPotentialsUsable`: present list | request's (the core's, line 1) | `w.request` |
| 3 | `solveWithCrystals` (2216, 2248, 2261) | `w.feed[c]` | request | `w.request.amounts` |
| 4 | `solveWithCrystals` (2229) | `w.presentCount` | request's | `w.request.presentCount` |
| 5 | `solveWithCrystals` (2245) | `w.basisAmounts` | request | `w.request.basisAmounts` |
| 6 | `solveWithCrystals` (2267-2268) | save to `savedFeed`, `savedBasis` | | deleted |
| 7 | `depositFromVapour` (2331, 2333) | `z = feed[i]`, `others` | base (nothing written yet) | `material` (= base) |
| 8 | `depositFromVapour` (new) | | | `w.reducedFeed.takeAmounts(material)`: base copied |
| 9 | `depositionResidual` (2802-2803) | writes `feed[i] = r`, derivatives on `feed` | base with `i = r`, in place | `w.reducedFeed` with `i = r`; every `j != i` the base's in both |
| 10 | `depositFromVapour` (2392-2394) | writes `feed[i]`, `basis[slot]` = vapour, `solveDry` | base with `i` = vapour | `w.reducedFeed`, same values |
| 11 | `secondCrystal` (2406 to 2783) | `feed[other]`, `other != i` | reduced (the base's value) | `w.reducedFeed` (the same value) |
| 12 | `solveWithCrystals` (2275) | `restoreFeed` after a null vapour solve | request back | deleted: the request was never written |
| 13 | `depositFromFluid` (2459, 2461) | `z`, `others` | base (request restored, or `reduceFeed`'s pair) | `material` (= base) |
| 14 | `depositFromFluid` (new) | | | `w.reducedFeed.takeAmounts(material)` before any read of it |
| 15 | `singlePhaseSlope` (2467 to 2767) | derivatives on `feed` | base | `material` |
| 16 | `depositFromFluid` (2521-2523) | writes `feed[i]`, `basis[slot]` = left, `solveDry` | base with `i` = left | `w.reducedFeed`, same values |
| 17 | `singlePhaseSlope` (2533) | derivatives on `feed` | reduced | `w.reducedFeed` |
| 18 | `splitChemicalPotentialsUsable` (2534) | present list | reduced (the core's) | `w.reducedFeed` |
| 19 | `depositFromFluid` (2553-2555) | writes 0 at `i`, `slot`; `solveDry` | base with `i = 0` | `w.reducedFeed`, same values |
| 20 | `secondCrystal` (2591) | `feed[other]` | reduced (last iterate, or `i = 0`) | `w.reducedFeed` |
| 21 | `solveWithCrystals` (2280, 2286) | `restoreFeed` | request back | deleted |
| 22 | `twoCrystals` / `reduceFeed` (2650, 2749-2761) | restore, then partner at `saved - amount` or 0, composition | request less partner, in `w.feed` | `w.pairFeed` built from `w.request`, same values and composition |
| 23 | `twoCrystals` (2651) | `feed[c]` | pair (the request's value: `c` is not the partner) | `w.pairFeed.amounts` |
| 24 | `twoCrystals` (2654) | `presentCount` | pair's (`reduceFeed`'s) | `w.pairFeed.presentCount` |
| 25 | `twoCrystals` (2656, 2662) | `solveDry` | pair | `solveDry(.., w.pairFeed, w)` |
| 26 | `twoCrystals` (2664) | `splitChemicalPotentialsUsable`: present list | pair's (the core's) | `w.pairFeed` |
| 27 | `twoCrystals` (2674, 2677) | base of the single solves | pair | `w.pairFeed` |
| 28 | `twoCrystals` (2675) | `reduceFeed` again after a null vapour solve | pair rebuilt | deleted: `w.pairFeed` untouched by that solve |
| 29 | `twoCrystals` (2684, 2697, 2702, 2703, 2705, 2735) | `savedFeed[...]` | request | `w.request.amounts` |
| 30 | `beyondStandIn` (4664-4665) | save to `savedFeed`, `savedBasis` | | deleted; basis copied into `rest.basisAmounts` (never read: `hydrocarbon` calls no result builder) |
| 31 | `beyondStandIn` (4667) | `feed[i] -= held[i]` | feed less stand-in, in place | `rest.amounts[i] = material.amounts[i] - held[i]` |
| 32 | `beyondStandIn` (4671) | `hydrocarbon` on `w.feed` | feed less stand-in | `hydrocarbon(t, rest, w)`; it writes `rest`'s composition |
| 33 | `beyondStandIn` (4674) | `restoreFeed` | feed and its composition back | deleted: `material` and its composition never written |
| 34 | `beyondStandIn` (4684) | `feed[i] - held[i]` | restored feed | `material.amounts` |
| 35 | `singlePhase`, `failed`, `twoPhaseResult` after it | present list, `z`, `feed`, basis | restored feed's | `material`'s (set by the outer `hydrocarbon`, the same loop) |
| 36 | the core (every function under `solveDry`) | `feed`, `z`, `present`, `presentCount`, `basisAmounts` | whatever `w.feed` held: request, reduced, pair or less the stand-in | `material`: the same feed, passed |
| 37 | every function outside the core and the crystal solves | the same fields | request (both windows closed) | `w.request` |

The two windows never nest: a carried competition has no crystals and no package crystals (`PhaseCompetition`
constructor) and `PhaseContract.resolve` returns it unchanged, so `activeCount` is 0 and `solveWithCrystals` never
runs while carrying, and `beyondStandIn` (which needs `w.carrying`) never runs inside a crystal solve. Its old copy into
`savedFeed` therefore never overwrote a crystal solve's saved request, and row 29 always read the request's feed.

## Step 2: what reads the two states after each scratch use

Writers of `liquidState` and `vaporState` as the split's outcome: `finishSplit`, `slotOutcome`, `carriedSplit`,
`beyondStandIn`. Readers: `twoPhaseResult` and `carriedSplit` (always after an outcome writer of the same solve: a
SPLIT outcome is returned only after one of them wrote both), and `splitChemicalPotentialsUsable` after a failed solve,
whose verdict decides the reads of `w.liquidState` in `solveWithCrystals` (`fluidMu`), `fluidMuOf` and
`depositFromFluid`. That verdict needs a detail starting with `UNSTABLE_PRODUCT` (or `CRITICAL_BAND ...): UNSTABLE_PRODUCT`).
Two places produce it: `finishSplit` (after writing both states, so it reads its own solve's split) and the three-slot
audit (`threeSlot`, about line 5447), which returns FAILED before `slotOutcome` and writes neither state.

| Scratch use | Writes | Read next as a result by | Kept? |
|---|---|---|---|
| `pureCoexistence` (about 2027-2028) | both, the pure feed's liquid and vapour at its equal-fugacity point | the next three-slot `UNSTABLE_PRODUCT` failure before an outcome writer, in this search or a later request on the workspace: the pair has equal chemical potentials to the coexistence tolerance, so the test can pass | kept |
| `pureCrystalStep` (about 3080) | `vaporState`, the step's fluid | the same read, paired with the last `liquidState` | kept |
| `stepFluid` (about 3172) | `vaporState`, the near-pure step's fluid | the same read | kept |
| `singlePhaseDrive` (about 4337) | `liquidState`, the onset's liquid | the same read, also in the next request (the onset's own fluid answers run without crystals) | kept |
| `singlePhase` (about 4422, 4423, 4431), `merged` (about 4479), in the core | both, or `vaporState` | the same read (a crystal solve's first answer SINGLE, then a reduced feed's three-slot failure) | not in the brief; noted |

With its own scratch state a use would leave the older outcome states in place, and an older split's pair (equal
chemical potentials by construction) can pass the 1e-6 test where today the scratch state fails it (or the reverse):
the verdict, and with it the crystal solve's path and bits, can change. So none of the uses can move under the rule
"if and only if no later code reads what they left there".

**The defect, for the owner** (AGENTS.md "Physics and defects"):

1. Expected: the split's chemical potentials steer a crystal only when this solve's split produced them: a product
   proved unstable in a two-phase split. A three-slot failure has no such split; its crystal decision should not
   depend on any earlier computation.
2. What the code does: after a three-slot `UNSTABLE_PRODUCT` failure `splitChemicalPotentialsUsable` reads the states
   the last writer left (a scratch use, an earlier solve of this search, or an earlier request on the workspace). When
   they happen to pass, the crystal is tested and solved against an unrelated state.
3. Proposal (results may move, only on that path): mark the outcome states as this solve's split. `hydrocarbon`
   clears a workspace flag at its entry and `finishSplit` sets it once both states are written;
   `splitChemicalPotentialsUsable` returns false without it. The three-slot failure then ends as the fluid answer's
   failure, as any other unusable failure does. After that, step 2 is pure structure (no read of a scratch state as a
   result remains) and can follow with step 3. To be measured by the digest (which lines move) and the crystal
   classes above.

## What was left, and why

- Steps 2 and 3, for the reason above.
- The core's result builders (`singlePhase`, `merged`) also use the outcome states as scratch; they stay with step 2.
- `Workspace.hydrocarbonTotal` and `total` stay in the workspace: they are the request's totals and stay the request's
  while a reduced feed is flashed (no read under the core needs the reduced total: the core sums its own).
- The carried-crystal flags (`carrying`, `carriedProduct`, `standInHeld`, `beyondStandIn`) stay: a mode of the
  request, not material.
- The batch README and `documentation/INDEX.md` are the main thread's (history line at merge).
