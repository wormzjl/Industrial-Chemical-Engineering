# Constructor templates review (2026-10-28)

Owner reports, 2026-10-28:
- "if some equipment is already highlighted in green, I cannot click it again to enter its construction page"
- "The constructor cannot load templates, also it should allow default templates"
- "It says save a design from column calculator first, which I already did"

## 1. Expected behaviour

1. On the choice page the draft's kind stands latched (green). Pressing it opens its design, as pressing any other kind opens that kind's design.
2. A design saved in a column calculator stays saved. The constructor's Import then finds it, whenever the calculator's screen was closed.
3. A column can also start from a template: the bundled crude templates (Bonga, Cold Lake Blend, Dalia, Tia Juana, Upper Zakum, WTI) and the player's own column templates, without a calculator nearby.

## 2. What the code did

1. `ConstructorColumn.choose` built the draft's kind as a selector position: latched and inactive, so a press did nothing. Only Back, beside the title, went to the design.
2. The calculator queues Save design on the engine's 5 s presentation bucket (`ColumnPresentation.queue`). `PresentationMailbox` dropped the queued command of a session that closed before its bucket: "Closing a session expires its unsent command". `applySaveDesign` also refused when the screen was no longer open (`REJECTED_CONTEXT`), and that refusal reached no one. A player who pressed Save and closed the screen within 5 s saved nothing. "Saved: the constructor imports this design" appears only on the bucket's answer. The constructor's Import then said "Save a design in the column calculator first".
3. Not related to the tray dP removal: neither the save nor the import reads the tray drop. A `ColumnDesign` holds only the trays, the diameter and the components.
4. A column came only from Import. The calculator's template library was not reachable from the constructor.

## 3. Fix (owner choices 2026-10-28)

1. A latched key may still act. `SkinButton` draws `latched` as latched whether or not the key is active. No key was latched and active before, so no other screen changes. The current kind is latched and active, and its press returns to its design.
2. Owner choice "Run it after close": a command the server accepted runs on its bucket even when its session closed first. Closing ends the subscription, not the input.
   - `PresentationMailbox.tick` runs every pending command, in arrival order. Then it publishes to the open sessions only.
   - `remove` keeps a pending command; reopening a screen also calls it.
   - The "screen open and within reach" check moves to receipt (`ColumnPresentation.queue`, now with `stillValid`). `applySaveDesign` and `applyCalculate` drop their own check.
   - The same now holds for Calculate and for the assembly screen's Validate: a press just before closing still runs, and its result waits on the block.
3. Owner choice "Column templates":
   - A Templates key beside Import on a column's design page opens a Templates page. It lists the calculator's library (`ColumnTemplates`): the bundled templates first, then the player's from `IChemE/columnpreset`.
   - Each template is the calculator's default input with the template's column settings, as the calculator's Templates load them. Its design takes an air cooler and standard pumps (`ColumnComponents.Choices.DEFAULT`).
   - A template that builds no column (the two-tray pot) is off, and its hover says why.
   - The screen sends the draft (`KEEP`), then `TemplatePayload(position, name, input)`. The server makes the design with `ColumnDesign.of`, as the calculator's Save design does, and answers with `ImportAnswer`.

## 4. Formats

- Equipment protocol `equipment-13` → `equipment-14` (new `TemplatePayload`; wire, no compatibility work).
- No save format changes: the constructor's design and the calculator's saved design are unchanged.

## 5. Verification

- New and changed tests:
  - `ColumnTemplatesTest`: every bundled crude template builds a 40-tray, 8 m column; the pot is off; a player's own template comes after the bundled ones; an unreadable file is counted.
  - `ConstructorColumnTest`: the templates page, the current kind active, every text has its English line, the width sweep.
  - `EquipmentNetworkCodecTest`: the template payload round trip.
  - `PresentationMailboxTest`: a closed or removed session's command runs on its bucket.
  - `AssemblyPresentationTest`: a press queued before close still validates.
- Renders: `equipment-constructor-choose`, `-column-design`, `-templates` (`tools/heat-gui-renders/GuiPrototypes.java`).
- Gates at `25dc375b`: `test` 1801/1801, `slowGates` 188/188. No in-game check.
- Tool folders: `tools/heat-gui-renders/` (two new prototypes). Code guide: equipment, column-v3, client-gui, machine-assemblies, fluid-runtime, README.

## 6. Open items

- AGENTS.md says "Closing the GUI ends the subscription". The owner may want it to add "a queued input still applies".
- zh_cn lines for the new keys (the zh_cn follow-up).
