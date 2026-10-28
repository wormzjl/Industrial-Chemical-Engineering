# 2026-10-28-gui-toolkit

Purpose: owner choice 2026-10-28, option C of the GUI toolkit review: a small GUI toolkit in our own code, first used on the heat Control tab.

Status: In progress since 2026-10-28, on branch `claude/gui-toolkit` (stacked on `claude/heat-gui-redesign-abb1db`). Index: [../INDEX.md](../INDEX.md).

## Documents

- [GUI_TOOLKIT_PLAN.md](GUI_TOOLKIT_PLAN.md): the kit, the pilot, the shared flowsheet painter, the gates, choices T1 to T3.
- The evaluation that chose it: [../2026-10-28-gui-toolkit-evaluation/GUI_TOOLKIT_REVIEW.md](../2026-10-28-gui-toolkit-evaluation/GUI_TOOLKIT_REVIEW.md).

## Code areas

- `client/gui/common/kit/` (new).
- `client/gui/screens/inventory/FluidDeviceScreen` (the Control tab), `HeatFlowsheet`, `AssemblyScreen` (its drawing).
- `tools/heat-gui-renders/`.

## History

- 2026-10-28: plan written (owner: "Option C").

## Open

- T1 to T3 (plan section 5) await the owner; built as (a).
