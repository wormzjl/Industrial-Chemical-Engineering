# Column GUI port colours

Date: 2026-10-29. Branch: `claude/column-operation-5c3005`. Code: `9cfc3a08`.

## Expected behaviour

Every port square in the column assembly GUI has a different colour, including repeated
draws, pumparound ports and both ends of a connection. Connection changes keep that colour.

## Previous behaviour

`AssemblyFlowsheet` used the port's phase class: most squares were cyan, with vapour
white and liquid blue. Connected squares all became grey.

## Implemented choice

Assign hues in the diagram's fixed port order, with brightness and saturation variation
for larger designs. A connected square keeps its colour and takes a grey outline;
UNUSED ports remain hidden. A changed component design can change the port order and
therefore the colours. The pipeline tool continues using its existing class colours.

## Verification

- Existing `AssemblyDiagramTest`, `AssemblyColumnTest`, `FlowsheetPainterTest`: 14 tests, zero failures/errors.
- Software renders through the shipped `AssemblyFlowsheet`: light and dark panels, with and without a selected part; visually checked both themes.
- Evidence: `tmp/port-colours/gates.log`, `tmp/port-colours/after/assembly.png`, `tmp/port-colours/after/assembly-selected.png`; indexed in `tmp/INDEX.md`.
- Drawing-only change: no dev client or solver campaign. No new tools; existing `tools/heat-gui-renders/GuiPrototypes.java` used.
- Code guide: updated GUI and machine-assembly port-colour contracts. C8/main merge remains deferred.

## Existing templates and the dev client

The owner's follow-up found the old colours under `gradlew runClient`. The main checkout's
`run/logs/latest.log` records a client starting at 11:40:34 on 2026-10-29, with Minecraft
artifacts under the main checkout's `build/moddev/artifacts/`. This worktree's `run/`
contains only `review-world/`: the updated client had not been launched here.
`AssemblyScreen` builds the same `AssemblyFlowsheet` for every existing column with a
component design; no port colour is stored in the template. No template migration is needed.

From the main checkout, launch this branch with:

```powershell
.\gradlew.bat -p .claude/worktrees/column-operation-5c3005 runClient
```

This task changes GUI squares. World port markers still use the pipeline tool's class colours.
