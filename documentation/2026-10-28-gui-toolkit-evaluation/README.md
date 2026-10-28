# 2026-10-28-gui-toolkit-evaluation

Purpose: owner question 2026-10-28: can LDLib make the mod's GUI better, or should similar tools be built into the code?

Status: Concluded 2026-10-28 (evaluation only, no code). The owner chose C (an own small toolkit) on 2026-10-28. Index: [../INDEX.md](../INDEX.md).

## Documents

- [GUI_TOOLKIT_REVIEW.md](GUI_TOOLKIT_REVIEW.md): today's GUI defects, LDLib2 as published, options A to D, the recommendation and the sources.

## Code areas

None changed. Read: `client/gui/common` (`ProcessUi`, `AssemblyDiagram`, `HeatDiagram`), `client/gui/screens` (7 screens), `FluidDeviceScreen.FluidGuiLayout`; the Ponder jar's Catnip GUI classes; `tools/minecraft-mcp-bridge/`.

## History

- 2026-10-28: evaluation in the heat GUI session (branch `claude/heat-gui-redesign-abb1db`), after the heat Control tab was built. Sources: the LDLib2 repository and docs, Modrinth's version list. No probe was run.

## Open

- Option C chosen by the owner 2026-10-28; built in batch [2026-10-28-gui-toolkit](../2026-10-28-gui-toolkit/README.md).
- If B: whether LDLib2 must be on a dedicated server, and whether it runs beside Create 6.0.10, are not verified.
