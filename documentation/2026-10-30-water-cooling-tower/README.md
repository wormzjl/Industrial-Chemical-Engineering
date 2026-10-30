# Scalable water cooling towers

In progress since 2026-10-30 on `codex/water-cooling-tower`, based on main `79b853e0d` (0.17.1). This batch has not merged or changed the release version.

The constructor adds induced-draft banks of bounded parallel cells and passive natural-draft towers. Both store water only in a real open basin and exchange heat and water with fixed ambient air. The induced-draft structure uses a metal basin and steel frame, without concrete support, following the owner's final revision.

- [Accepted design](COOLING_TOWER_PLAN.md): dimensions, physics, controls and exclusions.
- [Implementation and verification](COOLING_TOWER_REVIEW.md): owning code, conservation, formats, evidence and remaining qualification.
- [Cooler defect follow-up](COOLING_TOWER_FIX_REVIEW.md): fan power, bottom pressure, basin exchange and 80 °C natural-feed evidence.
- [Code guide](../reference/code-guide/cooling-towers.md).
- [Verification tools](../../tools/cooling-towers/README.md).

The owned worktree is `<main repo>/.claude/worktrees/water-cooling-tower/`. Scratch scripts and renders are indexed in its `tmp/tower-dev/`; final lane output and its fresh GameTest world are in `tmp/tower-verification/`. Do not remove these unmerged results as cleanup.

History: 2026-10-30 — implemented the accepted plan; removed concrete from the induced-draft bank at the owner's request. The revised two-nozzle model, target-temperature control, basin water and optimized natural chimney replace the initial proposal. Native checks use the repository’s local MCP bridge launcher in one client session; valve linking is deferred by owner choice.

History: 2026-10-30 — shortened both designs' nozzle welds to the walls, attached the natural inlet to its tapered shell, replaced the fan's closed backing with an open metal cowl and moved its animation pivot into the hub. Both towers share the 0.5 m bottom-outlet builder. No tests, builds or client checks were run for these repairs, as requested.

History: 2026-10-30 — extracted the tank gauge's translucent drawing into `LiquidRenderer` and reused it for both tower basin surfaces. Water now retains its material transparency instead of the basin's forced minimum opacity; tank gauge readability is preserved. Source/whitespace review only, with no tests, builds or client checks under the owner's standing instruction.

History: 2026-10-30 — `kimi/water-cooler-fixes` (`dd71de17`, based on `2433b1355`) made fan power speed-only for a fixed tower, added bottom pressure to both GUIs and removed basin evaporation. Natural 80 °C operation was already accepted on the base and is now pinned by direct, coupled and empty-startup regressions. Focused tests and slow gates pass; the quick suite's sole failure is the pre-existing stale equipment-layout golden. The owner will perform the native GUI check. No merge or version bump.
