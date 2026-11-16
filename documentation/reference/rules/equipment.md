# Rules: New equipment

The full rules of the `AGENTS.md` section "New equipment", with the same force. `AGENTS.md` keeps the core and says when to read this file.

- Before you build, change or review an equipment model or a sound, read the `models-and-sfx` skill.
- Every new `EquipmentShape.Kind`, `ConstructorColumn.Equipment` and `TopologyCompiler.Kind` constant gets a case in `FlowsheetCatalog`: MODELLED, or DRAWING_ONLY while no model exists. `FlowsheetPaletteCoverageTest` checks that each has a tile, a port rule and a lang line; one tile may hold several kinds.
- Update drawings by `tools/equipment-drawings/README.md`: `./tools/equipment-drawings/update.ps1 -Check`, then `-Only <affected IDs or kinds>` (or `update.py`). Drawing updates never depend on ignored scratch files, session-specific class paths or another worktree: the exporter, renderer, dependency list and run instructions stay in `tools/`.
