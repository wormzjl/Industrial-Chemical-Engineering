# 2026-10-28-constructor-templates

Purpose: make the Equipment Constructor's current kind open its design, keep a column calculator's Save design pressed just before its screen closes, and let a column start from a column template (owner reports 2026-10-28: "if some equipment is already highlighted in green, I cannot click it again to enter its construction page"; "The constructor cannot load templates, also it should allow default templates"; "It says save a design from column calculator first, which I already did").

Status: In progress since 2026-10-28 (branch `claude/constructor-templates` from main 0.17.0 `ac572f12`); not merged. Index: [../INDEX.md](../INDEX.md).

## Documents

- [CONSTRUCTOR_TEMPLATES_REVIEW.md](CONSTRUCTOR_TEMPLATES_REVIEW.md): expected behaviour, what the code did, the fix and the owner's choices, formats, verification, open items.

## Code areas

- `client/gui/common/ConstructorColumn` (`choose`: the draft's kind acts; `design`: Templates beside Import; `templates`, `Template`) and `kit/SkinButton` (a latched key drawn latched whether it acts or not).
- `client/gui/screens/EquipmentConstructorScreen` (page `TEMPLATES`, `templateKeys`, `loadTemplate`) and `client/gui/screens/inventory/ColumnTemplates` (the calculator's library on its default input).
- `network/EquipmentNetwork` (`TemplatePayload`, `template`, `sendTemplate`; protocol `equipment-14`).
- `runtime/PresentationMailbox` (an accepted command outlives its session), `network/ColumnPresentation` (the screen and reach checked at receipt), `network/ColumnV3Network` (`applyCalculate`, `applySaveDesign`), `runtime/assembly/AssemblyPresentation`.
- Lang: `gui.icheme.equipment_constructor.templates*`, `template.*`, `group.templates*`, `message.icheme.equipment.template*`.
- Tests: `ColumnTemplatesTest`, `ConstructorColumnTest`, `EquipmentNetworkCodecTest`, `PresentationMailboxTest`, `AssemblyPresentationTest`.
- Tool: `tools/heat-gui-renders/GuiPrototypes.java` (prototypes `equipment-constructor-column-design` and `-templates`; latched keys drawn as `SkinButton` draws them, also in `HeatDiagramRender`).
- Code guide: equipment, column-v3, client-gui, machine-assemblies, fluid-runtime, README.

## History

- 2026-10-28: diagnosis (review section 2). The draft's kind was latched and inactive. The calculator's Save design waited for the 5 s bucket, and `PresentationMailbox` dropped it when the screen closed first. Not related to the tray dP removal. A column came only from Import.
- 2026-10-28: owner choices: "Column templates (Recommended)" (not templates for every kind); "Run it after close (Recommended)" (not storing Save at once, not the next tick).
- 2026-10-28: fixes in `25dc375b`. `test` 1801/1801, `slowGates` 188/188. Code guide at `25dc375b` (equipment, column-v3, client-gui, machine-assemblies, fluid-runtime, README); GUI skill rule 2; tool history in `tools/heat-gui-renders/README.md`.

## Open items

- The merge to main: version (patch or minor) for the owner.
- AGENTS.md "Closing the GUI ends the subscription": the owner may add that a queued input still applies.
- zh_cn lines for the new keys.
- Not checked in game.
