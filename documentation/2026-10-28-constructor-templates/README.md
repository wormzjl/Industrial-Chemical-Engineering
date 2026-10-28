# 2026-10-28-constructor-templates

Purpose: make the Equipment Constructor's current kind open its design, keep a column calculator's Save design pressed just before its screen closes, and let a column start from a column template (owner reports 2026-10-28: "if some equipment is already highlighted in green, I cannot click it again to enter its construction page"; "The constructor cannot load templates, also it should allow default templates"; "It says save a design from column calculator first, which I already did").

Status: Implemented 2026-10-28 (0.17.1; branch `claude/constructor-templates` from main 0.17.0 `ac572f12`, rebased onto main `631fbd17` and fast-forwarded into `main`); not pushed. Index: [../INDEX.md](../INDEX.md).

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
- 2026-10-28: fixes in `25dc375b`. `test` 1801/1801, `slowGates` 188/188 at `25dc375b`. Code guide (equipment, column-v3, client-gui, machine-assemblies, fluid-runtime, README); GUI skill rule 2; tool history in `tools/heat-gui-renders/README.md`.
- 2026-10-28: merged as 0.17.1 (owner: "0.17.1 (patch)", then "no test"). Rebased onto main `631fbd17` (the device pages on big screens, the compressor's trim and the drum outlet placement, all landed without a version bump): code `545c827a`, docs `aea5ce8f`; the doc conflicts kept both sides. Release prep: `mod_version` 0.17.1, CHANGELOG `[0.17.1]` (it also releases those batches' lines), INDEX rows, code guide snapshots at `545c827a`. No `test` or `slowGates` run on the release tree (owner); it compiles. Main fast-forwarded from `631fbd17`; not pushed.

## Open items

- Push (owner).
- No test run on the release tree: the rebase brought main's device-page and drum changes under this batch's code untested together.
- AGENTS.md "Closing the GUI ends the subscription": the owner may add that a queued input still applies.
- zh_cn lines for the new keys.
- Not checked in game.
