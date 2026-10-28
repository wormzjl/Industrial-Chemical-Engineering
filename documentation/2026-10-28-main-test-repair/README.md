# 2026-10-28-main-test-repair

Purpose: make main's quick suite green again. On main `96806b8a` (0.16.1), 16 of 1654 tests failed, all from changes merged in 0.16.x (owner request 2026-10-28: "Fix main's 16 failing quick-suite tests", done in the session of batch [2026-10-28-old-mover-removal](../2026-10-28-old-mover-removal/README.md), on its branch).

Status: Implemented 2026-10-28, released as 0.16.2 (branch `claude/vigorous-bell-dab92b`, after the old mover removal's commits). Index: [../INDEX.md](../INDEX.md).

## Review

1. **Expected:** `./gradlew test` passes on main; a unit test may log through `IChemE.LOGGER` without Minecraft being bootstrapped.
2. **What the code did:**
   - 15 tests (`PipeLineCompatTest` 4, `FluidFactsCompatTest` 5, `FluidCheckpointStoreTest` 2, `AccessoryStoreCompatTest` 2, `AssemblyStoreTest` 1, `GaugeAccessoriesTest` 1) failed with `NoClassDefFoundError: IChemE`. Commit `f0b86f39` (2026-10-26) put the creative tab's `DeferredRegister` and its `register` call in `IChemE`'s static state. `DeferredHolder` binds its registry at creation, and with no bootstrap that throws (`Not bootstrapped`). The first repair log in a test run failed the class, and every later read failed with it.
   - `V3SimultaneousColumnSolverTest.theDampedCascadeCapIsHonouredAndTheRampsTwoDampingsAreEnough` counted workspaces created as its measure of solver work. Commit `0967a985` (2026-10-27) reuses one workspace per Newton attempt, so every solve counted 1 and the check "removing the cascade removes work" read 1 against 1. The solver was not wrong: its numerics are unchanged. The test's measure stopped measuring.
3. **Change:**
   - The tab moves to `registry/ModCreativeTabs` (`MAIN`), registered from the mod constructor like the other holders. Same id `icheme:main`, same contents.
   - The V3 test counts the manufactured thermo's fugacity calls per solve: each residual evaluation makes the same number, whatever the workspace allocation.

No save, format or physics change.

## Gates

On `3f7df293`'s tree, with no dev client running: `test` 1654/1654 and `slowGates` 188/188 in one lane; fluid GameTests 37/37 (fresh run id `movers2`; the server registers the tab from its new holder). No in-game check.

## Code areas

`IChemE`, `registry/ModCreativeTabs`; test `V3SimultaneousColumnSolverTest`. Code guide: `client-gui.md` (creative tab line). Tools: none.

## History

- 2026-10-28: both causes found (five of the failures re-run on a clean main checkout, same failures); fixed in `3f7df293`, then this README, the INDEX row and the CHANGELOG line. Gates above. Not merged, not pushed.

## Open items

- None.
