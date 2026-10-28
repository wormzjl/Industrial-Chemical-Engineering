# 2026-10-28-gui-toolkit

Purpose: owner choice 2026-10-28, option C of the GUI toolkit review: a small GUI toolkit in our own code, first used on the heat Control tab.

Status: In progress since 2026-10-28, on branch `claude/gui-toolkit` (stacked on `claude/heat-gui-redesign-abb1db`). The kit, the Control tab pilot and the shared flowsheet painter are built. Index: [../INDEX.md](../INDEX.md).

## Documents

- [GUI_TOOLKIT_PLAN.md](GUI_TOOLKIT_PLAN.md): the kit, the pilot, the shared flowsheet painter, the gates, the as-built notes (section 5), choices T1 to T3 (section 6).
- The evaluation that chose it: [../2026-10-28-gui-toolkit-evaluation/GUI_TOOLKIT_REVIEW.md](../2026-10-28-gui-toolkit-evaluation/GUI_TOOLKIT_REVIEW.md).

## Code areas

- `client/gui/common/kit/` (new): `Rect`, `Measure`, `Canvas`, `Palette`, `Format`, `Column`, `ScrollView`, `FlowsheetPainter`; the game adapters `GuiCanvas` and `KitWidgets`.
- `client/gui/common/HeatControlColumn` (new): the Control column as kit rows. `client/gui/common/HeatFlowsheet` (moved from `screens/inventory`): draws through the painter.
- `client/gui/screens/inventory/FluidDeviceScreen` (the Control tab on the kit), `AssemblyScreen` (its drawing through the painter); `client/gui/common/ProcessUi` (palette and `number()` alias the kit's).
- `tools/heat-gui-renders/` (whole Control pages through the shipped code).

## History

- 2026-10-28: plan written (owner: "Option C"), commit `b6659c07`.
- 2026-10-28: kit core and the Control tab pilot, commit `a779ce89`.
  - `FluidDeviceScreen` lost its walker, `ModeRow`, `clickMode`, the column's `ProcessScroll` and the rebuild on scroll.
  - Gates: `KitLayoutTest` 7, `HeatControlColumnTest` 4 (one wrong expectation fixed: the ratio mode's line names the feed valve in fixed words).
- 2026-10-28: the shared painter, in the painter commit (quick suite 1728, no failures).
  - `HeatFlowsheet` and `AssemblyScreen` draw through `FlowsheetPainter`. `AssemblyScreen` keeps its CRLF line endings.
  - The render tool renders whole Control pages. Gates: `FlowsheetPainterTest` 3; quick suite 1728, no failures.

## Open

- T1 to T3 (plan section 6) await the owner; built as (a).
- Seen in the renders (plan section 5): the Control column runs below the fold at 464 by 254; the ratio field's label is cut.
- Not moved yet (T2 (a)): the fluid screen's other pages and the other screens.
- At the merge to `main`: a `CHANGELOG.md` line, the version, and the code guide's GUI chapter.
- No in-game check (owner: enough client testing).
