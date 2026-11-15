# B-S1 Fluid step: report

Branch `claude/review-wave-a6-fluid-18ecaf`, merged from the review branch at 7fb8e5aed. Status: done, the named tests pass (2026-11-13). Every item keeps results bitwise identical; no test showed a moved result. No facts, state, protocol or shape change.

## Items
- **P-18** (c1d3fc0b4), a node's prepared terms refilled in place.
  - The new entry `FluidThermodynamics.prepare(T, reuse)` refills a bundle when its temperature differs. Its members are unformed, and its Peng-Robinson workspace is kept and prepared on its first use.
  - Premise checked (`prepareTemperature`, the stamps, `filter`, `carry`, `carryOnto`, `prepareSupport`):
    - a new workspace prepares and checks the whole list;
    - so a kept one is prepared on its last support and then carries the whole list, which prepares the rest at the same temperature;
    - every compound, pair and cell term is its own arithmetic at that temperature, so the refill gives the same bits and the same refusals.
  - A refusal drops the workspace. Otherwise the kernel's early return on `preparedTemperature` would keep half-overwritten terms.
  - In `Equations`:
    - a decode slot per node (its state and transport are decoded again from it at once) and a probe slot (the decode slot is the probe's base);
    - each pass takes over the previous pass's bundles. The accepted pass drops them with its decode cache (775-777, review 8.7 (c) E2), probes included since 57f20fe34, so each step builds one set again. Keeping them across steps would hold about two kernel workspaces a node per island: a RAM choice for P-10.
  - The `PhaseLayout` closures (640, 669) refill their bundle.
  - Left: `MixtureViscosity.Prepared` is still rebuilt at each new temperature (outside the lane).
  - Before (B1): `Workspace.<init>` was 34 % of a step's 60.7 MB, one workspace per trial temperature. After: one per laid-out node per step (counters below). No edit in `science/thermo/**` or `TranslatedPengRobinson` (its public `prepare(T, workspace)` and `carryAll` are used).
- **P-01** (ba17104ad): the block sweep no longer builds the `sparsity` it never read. `columnRows()` still builds it for a new pattern.
- **P-03** (8644a4b6e), one edge pre-pass in `assemble`:
  - it fills the flow, donor stream and suction stream per connection; the rows read them back; the block sweep computes its own per column;
  - per evaluation, `flow` goes from 3 calls to 1 per connection and `drawn` from up to 5 to at most 2;
  - a tower node no longer recomputes every flow.
- **P-05** (8847575d7, 9ea492800), accepted in its bitwise form. A structure number cannot carry `Identity.sameLine`: the rule is not transitive (a column-worked valve is the same line as two plain valves that differ from each other), and a number exact on equality needs an intern table that grows with every set point.
  - Done: identities built once per graph (`identitiesOf`); a new graph equal to the last keeps the last list object; the list hash once per solve (`WorkspaceKey.pipesHash`); lists compared by reference first; `sameLines` returns at once for one list.
  - Cost per pass now: no identity list built, no list hashed; the key lookups (up to four) compare by reference. Per new graph: one list built and one deep comparison with the last. `workspaceBuilds` and `workspaceReuses` unchanged.
- **P-06** (d6de55f4e, counter removed in 57f20fe34), dropped (main thread, option C). The premise failed: `PassiveNetwork` is a record, which always runs its canonical constructor, so no trusted constructor can skip the validation. For F0, on HeatSideNetworkTest's boiling junction (2 s steps): 5 graphs in the first solve, 0 in each of the next three (a temporary counter in the record's constructor, read once, then removed with its probe).
- **P-12** (f44cc7d68): `Inventory.holdsNoAmounts()` reads the field, so `holdsNoFluid` (reached through `boundaryAllowed`) no longer clones the moles.
- **P-13** (296022e75), in-lane part only: the result's emptied vessels show the solver's one ambient state.
  - `NetworkReduction.ambient` and its viscosity fallback (347, 352) are outside the lane, so not done.
  - `emptyVessel` (PN:158) runs only when a vessel is built, so it is left.
  - States stay values: `sameStates` compares them by value after a reference check, and the heat-curve cache only gains hits.
- **P-14** (907c572cd): the scheduled transfers are indexed per node (`transfersAt`), in the graph's order, for `nodeAccumulate` and `runsDry`.
- **P-15**: stopped. Reinserting on use changes which workspace survives the 4-key eviction. A workspace seeds the Newton's Jacobian (`forkPreconditioner`, the kept factors), so a different survivor changes the iterates. That is not bitwise.

## Tests
`compileJava compileTestJava` once, then with `--max-workers 4`:
- First run: `PreparedRefillTest` 1/1, `BlockJacobianEquivalenceTest` 7/7, `runtime.fluid.RetainedSolverTest` 2/2 passed; `PassiveStepSolverTest` 10/11, with one failure: my own new case `stepsAfterAnIslandsFirstBuildNoKernelWorkspace`. Its premise was wrong, because the accepted pass drops the bundles (E2) and closures outside the passes build their own. No result moved.
- Fix (57f20fe34): probes released with the decode cache; the case became `aStepBuildsAtMostADecodeAndAProbeBundleANode` on the solver's own count; the P-06 counter removed. Then `PassiveStepSolverTest` 10/10, `BlockJacobianEquivalenceTest` 7/7 and `RetainedSolverTest` 2/2 (they load the changed solver). `PreparedRefillTest` loads nothing that changed, so its pass stands.

## Counters (PassiveStepSolverTest probe, per step of a retained solver after one warm step)
| Island | Bytes a step | Kernel workspaces a step | LU factorizations a step |
|---|---|---|---|
| two water tanks | 448,910 | 2.00 | 0.20 |
| water through a cooled heat side, junction, void | 365,132 | 1.00 | 0.00 |
- Kernel workspaces: one per laid-out node per step on these islands (the decode bundle, refilled at every trial temperature); before P-18, one per trial temperature per node. Before: B1's tables only (`Workspace.<init>` 34 % of a mixed100 step); no copy of the old code was built. The bench's mixed100 re-run gives the step's bytes before and after.
- Workspaces outside the passes: a vessel's wall closure (`InventoryEquilibrium.close` from `ConservativeTransport:662`) and a junction split (`FluidThermodynamics:1109`) each build a new `PhaseLayout` closure per call; passing them a kept bundle is a change outside lane S1.

## For the main thread's lane
- Chain-100, the junction lines, the campaign: I expect no value to move. The counters that should fall are allocation, kernel workspace constructions, the `flow`/`drawn` call counts and the identity hashing.
