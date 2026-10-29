# Column GUI and pipe laying port colours

Date: 2026-10-29. Branch: `claude/column-operation-5c3005`. Code: `645e203d` (initial GUI change `9cfc3a08`).

## Expected behaviour

Every port square in the column assembly GUI has a different colour, including repeated
draws, pumparound ports and both ends of a connection. The pipe laying tool shows the same
colour for that nozzle. Connection changes keep it.

## Previous behaviour

`AssemblyFlowsheet` used the port's phase class: most squares were cyan, with vapour
white and liquid blue. Connected squares all became grey. The first GUI fix left the
tool using those class colours, so its world markers did not match the GUI.

## Implemented choice

Assign hues in the diagram's fixed port order, with brightness and saturation variation
for larger designs. A connected square keeps its colour and takes a grey outline;
UNUSED ports remain hidden. A changed component design can change the port order and
therefore the colours. `PortColours.assembly` is the shared lookup by slot, role and ordinal
(the receiver's wildcard SIDE square matches any side ordinal). `AssemblyToolClient`
resolves column nozzles directly from their loaded design, without needing validation;
other equipment uses the watched Check parts report to find its slot. Diagrams are cached
by immutable design and cleared with the watch or its next view. Only presentation is read.
`PipeLayingClient` keeps the matched colour even when connected, with a grey outer frame;
misplaced parts retain their yellow frame. Nozzles absent from the GUI use class colours.

## Verification

- `AssemblyPortColoursTest`, `AssemblyDiagramTest`, `AssemblyColumnTest`, `FlowsheetPainterTest`, `AssemblyToolWatchTest`: 20 tests, zero failures/errors; client adapters compile. The owner closed the dev client first.
- Regression: maximal designs under both condensers, both pumps, ejector and kettle choices; distinct actual GUI fills match nozzle lookups, connected fills stay identical with grey edges, wildcard/absent nozzles handled.
- Software renders: both themes, with/without a selected part, byte-identical to the checked initial GUI renders.
- Evidence: `tmp/port-colours/sync-gates.log`, `tmp/port-colours/synced/{assembly,assembly-selected}.png`; indexed in `tmp/INDEX.md`.
- No live world-marker check; mapping and GUI drawing checked offline. No solver campaign or new tools; existing `tools/heat-gui-renders/GuiPrototypes.java` used.
- Code guide: GUI, pipe laying and machine-assembly contracts updated. C8/main merge remains deferred.

## Existing templates and the dev client

The first follow-up launched main (`run/logs/latest.log`, 11:40:34 on 2026-10-29);
the later colour-sync report was from this branch's client. All existing component designs
use `AssemblyFlowsheet`; no port colour is saved in a template, so no migration is needed.

From the main checkout, launch this branch with:
```powershell
.\gradlew.bat -p .claude/worktrees/column-operation-5c3005 runClient
```

Restart the client after this change. Check parts associates the other equipment with its GUI slots.
