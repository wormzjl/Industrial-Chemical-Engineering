# Kernel cost: an evaluation follows what the mixture carries

2026-11-12. Owner: "carrying every component with water is not good, this is not scalable, optimize". Plan for one
sub-thread (Opus high). Code: `science/thermo/PengRobinsonKernel.java` and its callers. Not started.

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

## Part B: the dense excess path (moves last digits; the owner's choice after Part A's numbers)
- Rest sums of a centre whose only partner is water: one sum over the non-water compounds less its pairs' k terms
  (no partner is ever subtracted, so the cancellation the code comment records does not arise). O(n + pairs) for O(n²).
- `excessCurvature` is symmetric and read on its upper triangle only: one rank-two update per centre with cells.
- Reciprocals of `D_i` and `b` formed once per centre; `centreShift` keeps its direct form.
It changes results in the last digits: the printed references are read again in a final lane.

## Runs (the thread's; no final lane, no run with a change switched off)
- `./gradlew test --tests "*PengRobinsonKernel*" --tests "*TangentPlaneStability*" --tests "*FluidTpEquilibrium*" --tests "*CubicPhaseEvaluator*" --tests "*HydrocarbonModel*" --offline --max-workers 4`: the callers and the gate.
- `./gradlew --offline --no-configuration-cache fluidSolverRegression -PfluidRegressionMode=exact`: deviation 0 (the bits are kept through the network).
- The probe, alone: `node tools/aqueous-solvents/kernel-cost/kernel-probe.js on`, the three rows, `off`. Then `PumpBottomPortDrainTest`, once, alone.

Done: the gate passes, chain-100 exact 0, the three probe rows and the pump test recorded here and in the batch README, one commit per step.
