# B-S1 Fluid step: report

Branch `claude/review-wave-a6-fluid-18ecaf`, merged from the review branch at 7fb8e5aed. Status: code written, tests pending the lane. Every item is meant to keep results bitwise identical. No facts, state, protocol or shape change.

## Items
- **P-18** (c1d3fc0b4), a node's prepared terms refilled in place.
  - The new entry `FluidThermodynamics.prepare(T, reuse)` refills a bundle when its temperature differs. Its members are unformed, and its Peng-Robinson workspace is kept and prepared on its first use.
  - Premise checked (`prepareTemperature`, the stamps, `filter`, `carry`, `carryOnto`, `prepareSupport`):
    - a new workspace prepares and checks the whole list;
    - so a kept one is prepared on its last support and then carries the whole list, which prepares the rest at the same temperature;
    - every compound, pair and cell term is its own arithmetic at that temperature, so the refill gives the same bits and the same refusals.
  - A refusal drops the workspace. Otherwise the kernel's early return on `preparedTemperature` would keep half-overwritten terms.
  - In `Equations`:
    - a decode slot per node, whose state and transport are decoded again from it at once;
    - a probe slot per node, because the decode slot is the probe's base;
    - each pass takes over the previous pass's bundles; the previous pass drops its decode cache.
  - The `PhaseLayout` closures (640, 669) refill their bundle.
  - Left: `MixtureViscosity.Prepared` is still rebuilt at each new temperature (outside the lane).
  - Before (B1): `Workspace.<init>` was 34 % of a step's 60.7 MB, a new workspace for every trial temperature.
  - After: expected 0 kernel workspaces per step after an island's first step (counter below).
- **P-01** (ba17104ad): the block sweep no longer builds the `sparsity` it never read. `columnRows()` still builds it for a new pattern.
- **P-03** (8644a4b6e), one edge pre-pass in `assemble`:
  - it fills the flow, donor stream and suction stream per connection; the rows read them back; the block sweep computes its own per column;
  - per evaluation, `flow` goes from 3 calls to 1 per connection and `drawn` from up to 5 to at most 2;
  - a tower node no longer recomputes every flow.
- **P-05** (8847575d7). The structure number of the row cannot carry `Identity.sameLine`: the rule is not transitive (a column-worked valve), and an exact number would need an intern table that grows with every set point. Done in a bitwise form instead:
  - identities built once per graph (`identitiesOf`), the list hash once per solve (`WorkspaceKey.pipesHash`), lists compared by reference first, and `sameLines` returns at once for one list against itself. `workspaceBuilds` and `workspaceReuses` unchanged.
- **P-06**: not done. Its premise fails: `PassiveNetwork` is a record, so every constructor runs the canonical validation. I sent a decision to the main thread with three options; recommended: make it a final class that keeps its accessors, equals, hashCode and toString, with a trusted `withStates`.
- **P-12** (f44cc7d68): `Inventory.holdsNoAmounts()` reads the field, so `holdsNoFluid` (reached through `boundaryAllowed`) no longer clones the moles.
- **P-13** (296022e75), in-lane part only: the result's emptied vessels show the solver's one ambient state.
  - `NetworkReduction.ambient` and its viscosity fallback (347, 352) are outside the lane, so not done.
  - `emptyVessel` (PN:158) runs only when a vessel is built, so it is left.
  - States stay values: `sameStates` compares them by value after a reference check, and the heat-curve cache only gains hits.
- **P-14** (907c572cd): the scheduled transfers are indexed per node (`transfersAt`), in the graph's order, for `nodeAccumulate` and `runsDry`.
- **P-15**: stopped. Reinserting on use changes which workspace survives the 4-key eviction. A workspace seeds the Newton's Jacobian (`forkPreconditioner`, the kept factors), so a different survivor changes the iterates. That is not bitwise.

## Tests (pending "lane free")
`compileJava compileTestJava`, then each once with `--max-workers 4`:
- `PassiveStepSolverTest`, with the new `stepsAfterAnIslandsFirstBuildNoKernelWorkspace` and the counter probe `printsTheStepCountersOfAnIslandWithAndWithoutAHeatSide`.
- `PreparedRefillTest` (new): a refilled bundle against a new one, bit for bit, on a wider and on a narrower support.
- `BlockJacobianEquivalenceTest` (the brief's `BlockJacobianTest`: no class of that name exists) and `runtime.fluid.RetainedSolverTest`.

## Counters to read after the tests (from the probe, per step after one warm step)
- Bytes allocated per step (ThreadMXBean), on two water tanks and on a heat-side line.
- Kernel workspace constructions per step (`FluidThermodynamics.Prepared.workspacesBuilt`); expected 0.
- `luFactorizations` per step (`SolverDiagnostics`); expected unchanged by these items.
- Before: B1's tables only. No copy of the old code was built.

## For the main thread's lane
- Chain-100, the junction lines, the campaign: I expect no value to move. The counters that should fall are allocation, kernel workspace constructions, the `flow`/`drawn` call counts and the identity hashing.
