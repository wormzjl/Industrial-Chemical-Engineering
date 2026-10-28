# 2026-10-28-old-mover-removal

Purpose: remove the old one-block process fluid pump and compressor, blocks and engine form; the in-line movers on pipes replace them, and a saved world loads without its old ones (owner request 2026-10-28: "The old single block process fluid pump/compressor should be removed and use the in-line pump instead").

Status: In progress since 2026-10-28 (branch `claude/vigorous-bell-dab92b`, base `96806b8a`). Index: [../INDEX.md](../INDEX.md).

## Documents

- [OLD_MOVER_REMOVAL_PLAN.md](OLD_MOVER_REMOVAL_PLAN.md): expected behaviour, what the code has, the change, the owner's options (bore B, joins J, vertical V), the gates; section 6, the second step (remove entirely).
- [OLD_MOVER_REMOVAL_REVIEW.md](OLD_MOVER_REMOVAL_REVIEW.md): what changed, the physics check, the tests, the gates, the failures already on main.

## Code areas

`registry/ModBlocks`, `ModBlockEntities`, `world/level/block/FluidDeviceBlock`, `runtime/fluid/PhysicalFluidTopology.Device` (portless movers refused), `runtime/fluid/FluidFacts.world` (`World.removedMovers`), `runtime/fluid/FluidCheckpointCodec.topology`, `science/equipment/LineGeometry`, block assets and tags. Tests: the four fluid GameTests that placed the old blocks, `FluidFactsCompatTest` with the corpus image `src/test/resources/compat/removed-movers/`, `FluidTestSupport.mechanical` and the helpers that use it, the rebuilt upright-mover worlds, `LineGeometryTest`. Tool folder: [tools/old-mover-removal](../../tools/old-mover-removal/README.md).

## History

- 2026-10-28: plan written; defaults B1, J1 and V1 implemented, awaiting the owner's choice. The first `test` run showed that the ledger refused the change of kind; `Overlay.sameKind` now allows only this conversion. Commit `83ed8a58` (code and tests), then the documents and the CHANGELOG `[Unreleased]` line. Gates on `83ed8a58`: `slowGates` 188/188; `test` 1654 with 16 failures, all on main already (review section 7); fluid GameTests 37/37 (`movers1`). No in-game check. Not merged, not pushed.
- 2026-10-28: owner decision on the migration options: "Keep defaults" (B1 largest axial bore, J1 joins as an in-line mover, V1 an upright one keeps its registration).
- 2026-10-28: owner decision "Remove the old pump/compressor block entirely" (plan section 6): the engine refuses portless movers; no alias, no conversion; the facts reader drops saved one-block movers and the load rebuilds from facts (corpus image `compat/removed-movers`); test helpers lay in-line movers; upright movers rebuilt on their side; rule-off checks: seed 850 refuses 25 transitions, seed 743 holds at 13758. Gates: `test` 1654/1654, `slowGates` 188/188, fluid GameTests 35/35 (`movers2`). No in-game check. Not merged, not pushed.

## Open items

- Resolved 2026-10-28 on this branch: main's 16 quick-suite failures (review section 7), batch [2026-10-28-main-test-repair](../2026-10-28-main-test-repair/README.md) (`3f7df293`). With it, `test` 1654/1654, `slowGates` 188/188 and fluid GameTests 37/37 on `3f7df293`.
- The heat exchanger batch's review world places an old `FLUID_PUMP`; that batch moves it to an in-line pump after this lands.
- The in-line compressor's shutoff at rest depends on its bore by 5 Pa (review section 7); for the owner.
