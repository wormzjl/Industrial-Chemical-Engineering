# Kernel cost: an evaluation follows what the mixture carries

2026-11-12. Owner: "carrying every component with water is not good, this is not scalable, optimize". Plan for one
sub-thread (Opus high). Code: `science/thermo/PengRobinsonKernel.java` and its callers. Part A implemented 2026-11-12
(see Result); Part B not started.

## Measured (HEAD `6084652e4`, one run each, `tools/aqueous-solvents/kernel-cost/`)
The network list: 47 compounds, 304 pairs, 126 NRTL cells (92 of them with water). Time per call, evaluate / with derivatives:

| Phase | Listed | Present | Time |
|---|---|---|---|
| water + nitrogen | 47 | 2 | 3.8 µs / 17.4 µs |
| every compound | 47 | 47 | 6.2 µs / 35.8 µs |
| every compound, list without water (62 cells) | 46 | 46 | 4.8 µs / 24.6 µs |
| classical kernel, main (tools README, 2026-11-11) | 24 | 24 | 0.28 µs / not measured |

- The cost does not follow the phase: two compounds of 47 cost 60 % of all 47. Profile of the first row: `differentiateExcess`
  35 %, `correctExcess` 12 %, the dn loop of `evaluateDerivatives` 12 %, `prepareExcess` 10 % (126 exponentials per
  temperature), `evaluatePrepared` 8 %, `selectRoot` 5 %.
- Water is not the cost by itself: the list without water costs 70 to 80 % of the wet one. The excess path is: it forms
  every centre, every cell and the n x n blocks for the whole list.
- The game's islands carry their own list (`FluidThermodynamics.on`); a node pays for the island's list, not the
  package's. `PumpBottomPortDrainTest` runs water and nitrogen on the whole list: 82 s (main: 2.2 s, water outside the cubic).
- Tried and dropped (step 1): the rest sums over a list of the present compounds, and no pass over a pair of two
  absent ones. Bit-identical (26 kernel tests, chain-100 exact 0). No gain: 4.4 / 23 µs, pump test 82 s. Not committed.

## Part A: a support (results keep their bits)
The caller names the compounds its mixture carries (its feed's nonzero amounts). The kernel prepares, sums and
differentiates over those only. A sum that leaves out exact zeros, in the same order, keeps its bits.

1. `PengRobinsonKernel.Workspace`: `support[]` (ascending indices), `supportCount`, `inSupport[]`. `carry(double[] feed)`
   sets it from `feed[i] != 0`; `carryAll()` restores the whole list (the default of a new workspace). On a change it
   filters, once: the pairs with both ends in the support (pair order kept) and, per centre, its cells with the partner
   in the support (cell order kept).
2. Temperature preparation per compound: `prepareTemperature`, `preparePairs`, `prepareExcess`,
   `prepareRootDerivatives` fill only the support's compounds, pairs and cells. A stamp per compound, pair and cell
   holds the temperature it was prepared at, so a support that grows at the same temperature prepares only what is new.
3. Every loop `0 .. count` of `evaluatePrepared`, `correctExcess`, `excessSecondTemperatureDerivative`,
   `differentiateExcess`, `mixtureSecondTemperatureDerivative`, `addInteractionTemperatureTerms` and the two loops of
   `evaluateDerivatives` runs over `support[]`; every pair pass over the filtered pairs; `centreShift` and
   `classicalTau` unchanged. `excessCurvature`, `excessCross` and `dLogPhiDn` keep their full-list positions (`i * count + j`).
4. Outside the support the outputs are 0.0 (`x_i ln phi_i` sums stay as they are). `evaluate` throws on an amount
   outside the support. A test switch fills NaN there instead, to find a reader of an uncarried compound.
5. Callers set the support at their entry, from the feed, never from a phase (a compound of the feed with no amount in
   one phase still needs its `ln phi` there):
   - `TangentPlaneStability.run`: its `present` list is the support.
   - `FluidTpEquilibrium`: at each public entry, from the feed, on every kernel workspace it owns (two-phase and
     three-phase paths, `kernel.evaluateDerivatives` at 5292, 5300, the slot derivatives at 6181).
   - `CubicPhaseEvaluator`: a workspace's support comes from the flash that owns it; a direct call carries all.
   - `HydrocarbonModel` (state, tangent at 189): from the node's amounts. `V3PengRobinsonSession`: all (no change).
   - Changed 2026-11-12 (owner, option A): the node's amounts are too narrow. A layout carries reachable compounds
     with zero amount and reads their `ln phi` at infinite dilution (`PhaseLayout.encode`, `reactivate`). The network's
     support is the node's amounts plus the layout's build mask, named on `FluidThermodynamics.Prepared` at every
     decode. Rule: every compound whose `ln phi` or derivative is read is in the support.
6. Gate, in `PengRobinsonKernelHuronVidalTest`: on the refinery list, for five supports (water + nitrogen; water + one
   cut; three miscible compounds; half the list; all), every output on the support equals the whole-list call bit for
   bit (`ln phi`, `d ln phi/dT`, the dn block, `secondTemperatureDerivative`, enthalpy), at a liquid and a vapour root.

Pitfalls: a stale support on a reused or thread-local workspace (set it at every entry, never rely on the last call);
a trace fold or a dry phase changes the feed between calls; `-0.0` against `0.0` in a skipped term (compare with
`Double.compare` only where a sign can reach a result); the rank-one and no-excess paths must take the same lists;
`activePresent` and `workspace.active[]` are read by later calls on the same workspace; `HydrocarbonModel` copies
whole rows (zeros outside the support are right there); allocation: filter into arrays the workspace owns.

Ruled out: a support the kernel derives from the phase alone (it loses `ln phi` of a feed compound absent from the
phase); a cached sub-kernel per support (tables and preparation per support: memory, and a second arithmetic path).

Expected: the first row near a two-compound kernel, under 0.5 µs / 1.5 µs; the pump test under 10 s; rows 2 and 3 unchanged.

## Result (Part A, 2026-11-12)
Commits: `2b43a4427` (kernel), `6b9fa2ef5` (gate), `ce6d31ffc` (engine callers), `d009783aa` (network callers).

- Kernel: `Workspace.carry(feed)` / `carryAll()` / `carrySame`; pairs and cells filtered once per change; a temperature
  stamp per compound, pair and cell; every loop over the support. Outside it the outputs are zero
  (`outsideSupportFill`), and they are refilled only when the support changes. An amount outside the support is
  refused. `PairInteractions.evaluatePair` lets the model pairs follow the support.
- Engine: `FluidTpEquilibrium` sets the support from the request's feed at every entry (`implementationChecks`), on its
  kernel workspace and on both workspaces of its phase evaluator. `TangentPlaneStability` carries its present compounds.
- Network (option A): `PhaseLayout` names its build mask (seed amounts plus what can arrive) on `Prepared` at every
  decode. `state()`, `tangents()` and the tangent's exact fallback carry that mask plus the node's amounts. Paths with no
  named mask carry the whole list. Each `HydrocarbonModel.Phase` records its support (`carried`), and `encode` /
  `reactivate` evaluate a seed again on the whole list when its phases miss a compound the layout reads.
- Gate (`aCarriedSupportKeepsEveryOutputOnItBitForBit`): 98,880 outputs bit-equal on 5 supports, liquid and vapour root.
- Audit: the NaN switch (system property `icheme.kernel.supportNaN`, `tools/aqueous-solvents/kernel-cost/support-nan.init.gradle`;
  under it an amount outside the support is an `AssertionError`). Engine callers: 60 tests. Network classes,
  `DrumFillColdStartTest`, `PumpBottomPortDrainTest`, `MixedSetMergeTest` and the engine: 79 tests. No reader outside
  the support.
- Runs without the switch: 121 tests in 18 classes, 0 failed; chain-100 exact deviation 0.

| Probe row (one run each, alone) | Before | After |
|---|---|---|
| water + nitrogen of 47 (carried: 2) | 3.8 / 17.4 µs | 0.18 / 0.29 µs |
| every compound (47) | 6.2 / 35.8 µs | 6.8 / 34.8 µs |
| every compound, list without water (46) | 4.8 / 24.6 µs | 5.7 / 28.2 µs |

- The sparse row is below the 0.5 / 1.5 µs target. The two dense rows were expected unchanged. The list without water
  is 15 to 19 % higher in one run each: noise or the support indirection, not separated.
- `PumpBottomPortDrainTest`: 2.23 s for the class (3 tests), run once alone. The test was shortened in `e8a25bd2e`
  before this run, and `src/main` had already changed, so there is no before on the same test. The plan's 82 s was the
  old 400-slice form.

## Part B: the dense excess path (owner, 2026-11-12: "Part B first", before the third final lane)
Profile of the dense row on `45adfee06` (all 47 present, 903 samples): `differentiateExcess` 40 % (its cross loop with
`centreShift` and `interaction` 27 %), `correctExcess` 21 %, `excessSecondTemperatureDerivative` 7.5 %, the dn loop of
`evaluateDerivatives` 7 %, `prepareExcess` 7 %, `evaluatePrepared` 6 %. The rest sums are 2 to 4 %: the class-sum form
this section first proposed is dropped (it moves digits for nothing).

The cross loop is bound by its divisions: 1128 pairs (k <= l), about ten each (`share / LAMBDA` twice, `a_k / b_k`
twice, `s_k s_l / b_l` twice, `h / D_k` twice, `/ b` twice), near 10 µs of the 35 µs.

### B1: the same arithmetic, less of it (every output keeps its bits)
In `differentiateExcess` and `centreShift` only; each value is formed by the expression it has today, once.
1. Cross loop (the last loop): per `k`, hoist `cellIndex[k]`, `pairIndex[k]`, `a[k] / coVolumes[k]`, `theta[k]`,
   `thetaBase[k]`, `denominator[k]`, `active[k]`; the scalar `HuronVidalExcess.LAMBDA * (share / HuronVidalExcess.LAMBDA)`
   once per call (`classicalTau` forms `LAMBDA * inverseRt * (...)` left to right: keep that product, do not replace
   it by `share`); one `pairIndex` lookup per pair for the two shifts and the quadratic term. `centreShift` and
   `classicalTau` stay as methods for their other callers, or are inlined: the expressions do not change.
2. `excessCurvature` on its upper triangle (`l >= k` in support order): the fill, the cell loop over `l` and the cell
   pair loop write only there. Today the cell loop adds `term` to `(k, l)` and to `(l, k)`; on the triangle the element
   `(min, max)` takes the same additions in the same order (centre ascending, then its cells, then `l`), and the
   diagonal takes `term` twice as today. The cross loop is its only reader and reads `l >= k`. Check there is no other
   reader (`excessCurvature` in the kernel, the gate, tests).
3. The cell loop's `classical` (centre `i`, compound `l`) is the same for every cell of the centre: form the vector once
   per centre into a workspace scratch row (water with 46 cells forms it 46 times today).
4. Pair passes (`correctExcess` y, `differentiateExcess` yDt and y2): measure one; only if a pass is above 5 % of a
   dense call, keep the two ends of each support pair in two workspace arrays filled in `filter()`.
Proof: a dump of every output in hexadecimal (`ln phi`, `d ln phi/dT`, the dn block, `secondTemperatureDerivative`,
enthalpy) for the gate's five supports and two roots, written before the first edit and after B1; the two files are
equal. Add the dump to `tools/aqueous-solvents/kernel-cost/` (as `probes/PairDump.java` does for the pairs).

### B2: reciprocals in the composition derivatives (moves the dn block's last digits only)
After B1, the cross loop keeps six divisions per pair. Form once: `s_l / b_l` and `a_i / b_i` per prepared temperature
(with the stamps of Part A), `1 / D_i` per composition in `correctExcess` (stored, not used there), `1 / b` per call.
The cross loop and the cell loop multiply. `centreShift` keeps its direct form, `h * (1/D_k) - (tau - theta_k^b) * (1/b)`:
no regrouping into `1/D - 1/b` (the code comment's cancellation). `correctExcess`, the temperature derivatives and
`ln phi` are not touched, so they keep their bits; only `dLogPhiDn` moves, by rounding.
Proof: the dump again: everything but the dn block equal; the dn block's largest relative difference reported (expect
1e-15 to 1e-13; stop and report above 1e-12). Chain-100 exact stays 0 if its list has no cells (classical path); if it
moves, stop and report: its reference is not captured again without the owner's word.

Not in Part B: the dn loop of `evaluateDerivatives` (its `/ (a * a)` is shared with the classical path and would move
chain-100); `prepareExcess` (126 exponentials per temperature are the cells' own); the rest sums.

Pitfalls: `omegaDt[]` is reused as `c_i - c_i^b` in the second half of `differentiateExcess`; `rowY` is reused as `y2`;
a support smaller than the list (positions stay `k * count + l`, loops stay in support order); `-0.0`; an inactive
centre's branch of `centreShift` reads `theta[k]` and `denominator[k]` set by `correctExcess` for inactive centres
(`bMix`, `base`); the NaN switch must still find nothing.

Runs: the kernel classes and the callers as in Part A (`--tests`, 4 workers); chain-100 exact; the dump before, after B1,
after B2. Timing, alone: the dense probe row with `-Dkprobe.seconds=60` (add it: 60 s warm-up, then 60 s measured) on
`45adfee06` first, then after B1 and after B2; the other two rows once at the end with the short form.
Expected (from the division counts, not measured): dense derivatives 35 µs to about 28 µs after B1 and about 20 µs
after B2; evaluate unchanged near 6 µs.
Done: both proofs hold, the three timings are in a "Result (Part B)" section here and in the two READMEs, one commit
per step (B1, B2), no probe or dump left in `src`.

## Runs (the thread's; no final lane, no run with a change switched off)
- `./gradlew test --tests "*PengRobinsonKernel*" --tests "*TangentPlaneStability*" --tests "*FluidTpEquilibrium*" --tests "*CubicPhaseEvaluator*" --tests "*HydrocarbonModel*" --offline --max-workers 4`: the callers and the gate.
- `./gradlew --offline --no-configuration-cache fluidSolverRegression -PfluidRegressionMode=exact`: deviation 0 (the bits are kept through the network).
- The probe, alone: `node tools/aqueous-solvents/kernel-cost/kernel-probe.js on`, the three rows, `off`. Then `PumpBottomPortDrainTest`, once, alone.

Done: the gate passes, chain-100 exact 0, the three probe rows and the pump test recorded here and in the batch README, one commit per step.
