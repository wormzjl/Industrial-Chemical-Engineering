# Scalable water cooling towers

In progress since 2026-10-30 on `codex/water-cooling-tower`, based on main `79b853e0d` (0.17.1). This batch has not merged or changed the release version.

The constructor adds induced-draft banks of bounded parallel cells and passive natural-draft towers. Both store water only in a real open basin and exchange heat and water with fixed ambient air. The induced-draft structure uses a metal basin and steel frame, without concrete support, following the owner's final revision.

- [Accepted design](COOLING_TOWER_PLAN.md): dimensions, physics, controls and exclusions.
- [Implementation and verification](COOLING_TOWER_REVIEW.md): owning code, conservation, formats, evidence and remaining qualification.
- [Cooler defect follow-up](COOLING_TOWER_FIX_REVIEW.md): fan power, bottom pressure, basin exchange and 80 °C natural-feed evidence.
- [Tower geometry follow-up](INDUCED_TOWER_GEOMETRY_REVIEW.md): basin-floor legs, fan collision, smaller basins, enclosed single-layer fill and natural-support evidence.
- [Natural support and mesh review](NATURAL_TOWER_SUPPORT_REVIEW.md): reference-matched scale, concrete V-struts, internal grillage and geometry-cost evidence.
- [Code guide](../reference/code-guide/cooling-towers.md).
- [Verification tools](../../tools/cooling-towers/README.md).

The original worktree is `<main repo>/.claude/worktrees/water-cooling-tower/`; this follow-up branch uses `<main repo>/.claude/worktrees/water-cooler-fixes-kimi/`. Scratch scripts and renders are indexed in the owning worktree's `tmp/tower-dev/`; final lane output and its fresh GameTest world are in `tmp/tower-verification/`. Do not remove these unmerged results as cleanup.

History: 2026-10-30 — implemented the accepted plan; removed concrete from the induced-draft bank at the owner's request. The revised two-nozzle model, target-temperature control, basin water and optimized natural chimney replace the initial proposal. Native checks use the repository’s local MCP bridge launcher in one client session; valve linking is deferred by owner choice.

History: 2026-10-30 — shortened both designs' nozzle welds to the walls, attached the natural inlet to its tapered shell, replaced the fan's closed backing with an open metal cowl and moved its animation pivot into the hub. Both towers share the 0.5 m bottom-outlet builder. No tests, builds or client checks were run for these repairs, as requested.

History: 2026-10-30 — extracted the tank gauge's translucent drawing into `LiquidRenderer` and reused it for both tower basin surfaces. Water now retains its material transparency instead of the basin's forced minimum opacity; tank gauge readability is preserved. Source/whitespace review only, with no tests, builds or client checks under the owner's standing instruction.

History: 2026-10-30 — `kimi/water-cooler-fixes` (`dd71de17`, based on `2433b1355`) made fan power speed-only for a fixed tower, added bottom pressure to both GUIs and removed basin evaporation. Natural 80 °C operation was already accepted on the base and is now pinned by direct, coupled and empty-startup regressions. Focused tests and slow gates pass; the quick suite's sole failure is the pre-existing stale equipment-layout golden. The owner will perform the native GUI check. No merge or version bump.

History: 2026-10-30 — `98789eaa` extends induced-tower legs to the basin floor and adds square fan-deck collision without closing the fan opening. Focused geometry checks and slow gates pass; software `iso`/`top` renders were inspected. The quick suite retains only the known older tower layout-golden mismatch. No merge, client check or version bump.

History: 2026-10-30 — after the owner's fan screenshot, `44044ff2` makes the rotor/grille volume solid throughout the cowl. Main and test compilation pass and occupied-cell layouts are unchanged; test execution is deferred while the owner's dev client is running. No merge or version bump.

History: 2026-10-30 — `aa8381cb` reduces both basins to 2 m walls and 4/3 m working depth, scales default induced fill to half cell width, encloses one packing layer and replaces natural posts with 32 X-braced perimeter bays. No compatibility code was added by owner choice; the current schema-6 fixture was regenerated. Forty-four focused checks and independent slow gates pass; 1,838 quick tests have only the older outlet-golden failure. Software renders were inspected, with no client launched, merge or version bump.

History: 2026-10-30 — `0491f75d` rebuilds the natural support with concrete V-struts, a ring and internal grillage. The clear inlet now scales to 12–24 m above the basin rim; packing, distributor and inlet follow it. An 80 m tower uses 7,004 triangles instead of 23,016 (69.6% fewer), without a measured-FPS claim. Thirty-five focused checks pass and two software views were inspected. Only natural shell/stamp/inlet golden fields changed; the older outlet mismatch remains. No client check, merge or version bump.

History: 2026-10-30 — after the owner rejected the 15% support height against the latest reference photo, `0fd07699` reduces it to 10% of total height (`0.2 × diameter`, 8–16 m above the basin rim). The new V-struts, grillage and coarse shell remain. Standalone compilation, layout/mesh reports and two offline renders verify the correction while the owner's dev client prevents Gradle tests. The final 80 m model has 7,256 triangles (68.5% fewer than the original). No in-game check, merge or version bump.

History: 2026-10-30 — `d97ca7db` extends the perimeter V-struts from the basin rim to the floor at `y = 0`, preserving their continuous incline rather than adding vertical lower members. Standalone checks confirm floor contact and diagonal collision at 40 m and 80 m; two offline renders were inspected. Natural layout shell counts/stamps changed, with inlet and outlet pipe entries preserved. No Gradle tests, in-game check, merge or version bump.

History: 2026-10-30 — the owner selected the 32 × 32 pixel-art concrete texture after 16/32/64 mockup comparisons. The restrained warm-grey clusters replace the coarse speckling without changing geometry. Only the texture asset and this review changed; no build, test or client check was run. No merge or version bump.
