from pathlib import Path
p=Path('documentation/reference/code-guide/pipe-laying.md'); old=p.read_text(encoding='utf-8')
def section(title):
 s=old.index('<details><summary>'+title); e=old.index('</details>',s)+len('</details>');return old[s:e]
route=section('Route search and the server\'s check (PipeRoute)').replace('the anchors\' (and `PipeLaying`\'s)','`LineAnchors` and `LineLayout.mergeHosts`\'')
racks=section('Rack geometry: frame, slots, levels, tees, fits (RackGeometry)')+'\n\n'+section('Rack placement, steel, flanges, drawing and picking')
head='''# Fluid, data and power line placement

**Snapshot:** fd5603249 (branch `codex/simulator-sizing`), 2026-11-08. Batch `2026-11-06-simulator-sizing`, prepared for owner-approved 0.22.0; not merged.

Up: [code guide](README.md). Related: [equipment](equipment.md), [fluid runtime](fluid-runtime.md), [wired control/power](wired-control-power.md), [GUI](client-gui.md).

## Contract and ownership

The pipe laying tool selects Fluid, Data or Power. These modes share the interaction state machine, targeting, route search, spatial admission, immutable layout, server command, inventory/permission checks, collision/picking, open-end marks and structural refresh. `PipeLayingClient` and `CableLayingClient` are domain adapters, not independent placement engines. `PipeLaying` only supplies common refusal/message vocabulary; the old server `CableLaying` and `ConduitLaying` command engines are gone.

| Decision | Owner | Domain adapter |
|---|---|---|
| Use key, start/end, cancel, replan, refusal, one request in flight | `client/LineToolSession` | `PipeLayingClient` / `CableLayingClient` provide selection and presentation |
| Target face/lane/parent, socket hit, marks and HUD feedback | `LineSelection`, `LayingToolAim`, `LinePortTarget`, `LineToolMarkers`, `LineToolFeedback` | Fluid assembly colours; DATA/FE socket catalogues |
| Profile, material, diameter and finish | `LineProfile` | Fluid bore/paint; one 0.125 m diameter for each cable medium |
| Resolve clicks and retained hosts | `LineAnchors`, `PipeAnchor`, `LineBackend` | Nozzle/device faces vs UUID data/power sockets |
| Client search and server acceptance | `LineLaying`, `LinePlacement`, `PipeRoute` | Neither backend may search or bypass admission |
| Final cells, arms, face points, host edits and bindings | `LineLayout` | `LineBackend` supplies existing host/socket facts |
| Permissions, inventory and applying physical edits | `LinePlacement.commit/apply` | `FluidLineBackend` / `ConduitLineBackend` prepare registration/work |
| Socket leads crossing cells, collision, picking and derived refresh | `LinePieces`, `LineShapes`, `LinePieceBlock` | Same physical pieces for data and power |
| Process registration vs structural network | `FluidWorldAuthority` / `DataBus` and FE power grid | Necessary medium-specific facts, not separate routing rules |
| Packet bounds, request correlation and replies | `network/LineNetwork` | One `lines-1` protocol for all three modes |

## End-to-end flow

1. `LineToolSession` arbitrates the held hand/mode and one action per press. First click selects an anchor; a changed aim or periodic recheck builds a preview. Shift cancels a selection, otherwise dismantles one selected block/lane, or opens settings. Leaving the tool, world/mode changes or moving 96 blocks from the start clears the session. HUD cadence is 5 ticks; unchanged aims recheck every 20.
2. Both adapters call `LineLaying.search`. It resolves `LinePieces.target` to its authoritative parent, uses `LineAnchors.resolve`, checks endpoint fits and swept socket leads, then calls `LinePlacement.search` and `LineLayout.build`. The ghost uses that layout's actual face points, including off-centre rack lanes and sockets.
3. A second click sends `LineNetwork.Command`: UUID ticket, hand, profile, action, both targets and at most 128 path cells/lane options. `LineToolSession` correlates replies by ticket and generation, so an old response cannot clear another world/mode/start. A refused action retains its start; a successful one clears it.
4. `LineLaying.execute` validates the tool/profile, build rights, loaded endpoints, 64-block start range and end interaction reach. It resolves both targets again and checks the supplied path; **the server never searches**. `LineLayout.build` merges both ends' edits to the same host before checking joint constraints. `ATTACH` retains an existing entry block and checks the same socket lead and host-merge owners.
5. The selected backend validates existing registrations/bindings. `LinePlacement.commit` rechecks the whole spatial plan and every touched cell's permissions, including socket owner/entry and crossed lead cells, before taking inventory. It applies the prepared edits and refresh work, then flushes derived `LinePieces`. An ordinary validation refusal writes nothing; this is not an exception rollback mechanism.
6. Fluid registrations/host joint edits are one queued ledger event; process changes take effect on the engine schedule. Data/power record structural bindings for `DataBus`/standard FE. Placement, menus and packets never run a process solve. `handPlaced`, existing-stub attachment and `breakPiece` use the same layout/backend/physical owners.

## Spatial rules and cross-cell geometry

`PipeRoute` is the Minecraft-free A* and path checker. `PipeAnchor` supplies endpoint fit/face/lane constraints. `LinePlacement.space/reserve` reserves complete occupied equipment cells, including hollow skirts and supports. A conduit cannot treat a structure's empty-looking interior as replaceable space. Its own socket lead may overlap only the actual owner's geometry at the gland; unrelated occupied cells remain fully reserved.

`LinePlacement.checkLeads` checks swept envelopes against loaded chunks, height/world border, other leads and occupied blocks/rack lanes. Implicit socket leads do not skip rack registration. Every crossed cell joins the permission set. `LinePieces` indexes immutable cell slices of these leads for collision, targeting and model refresh. They are derived sidecar geometry, not new process devices or authoritative saved cable facts; removing a sensor/last pipe instrument retires its socket without deleting the remaining cable.

`LineShapes` is the common solid/picking path for pipe and conduit blocks/rack lanes. `LineToolMarkers` includes disconnected DATA/POWER ends as well as fluid ends. Marking, ghost geometry, collision and server validation must use actual retained face points, never substitute cell centres. A rack keeps its occupied lanes when a line is dismantled elsewhere; only the selected lane/block is removed/refunded.

## Rendering, racks and formats

`ConduitGeometry` uses the shared line sweep/finish path, steel housing, blue DATA and orange FE bands/symbols, opaque closed ends and `floorMod(x+y+z,4)==0` marking (about every four cells on a straight run). The cache includes finish and marking; neither is a saved layout change. Low-polygon clients simplify round meshes and flange fasteners independently of the server's physical geometry, route or connection keys.

`RackGeometry` owns slot positions, crossing levels, beam/steel clearance, lane face points and the drawn spool. `PipeRackBlockEntity` stores the fluid lane copy (`rack-3`) and refreshes model data; conduit lanes retain their structural control/power records. `LineLayout` uses `RackGeometry.facePoint`, including retained host offsets. Adjacent parallel lines connect only through explicit compatible joints; a rack lane is option + 1 in the fluid position, not a globally meaningful network number.

| Format / protocol | Current contract |
|---|---|
| `lines-1` | `line_command` / `line_answer`; bounded path/options, UUID correlation, LAY/ATTACH/DISMANTLE |
| `pipeline-tool-5` (`FluidNetwork`) | Tool open/settings, including mode; no separate fluid lay/dismantle packet engine |
| `control-3` (`ControlNetwork`) | Controller/DCS/menu commands and requested presentation |
| Pipe block fields | Append-only `Joints`, `Paint`, `Secondary`; absent values preserve original behaviour; drawn offsets are derived |
| Fluid rack lane copy | `rack-3`; identity, option, width, arms, joints, bore, paints; damaged drawing copies are logged/rebuilt from registrations |
| Fluid / control facts | Append-only device/joint/lane facts and data/power UUID bindings; see subsystem chapters for current owning schemas |

Wire protocols have no save migration. Corpus images under `compat/pipes`, `compat/racks`, `compat/fluid` and `compat/control` qualify saved facts. Use fresh dedicated-server worlds for protocol tests, never player worlds.

## Route kernel details

'''
tail='''

## Verification and change routing

- Interaction/correlation: `LineToolSessionTest`; common owners/packet contract: `LineContractTest`.
- A* costs, deterministic ties, each-cell-once, aimed ends and tampered paths: `PipeRouteTest`.
- Rack lanes, heights, steel/spool clearance and face points: `PipeRackGeometryTest`; pipe/conduit solids and transition rims: `LineGeometryTest`, `ConduitGeometryTest`.
- Saved pipe/rack facts: `PipeLineCompatTest`, `PhysicalFluidTopologyTest`, `FluidFactsCompatTest`; cable/port connectivity: `CableConnectionsTest`, `ControlSystemTest`.
- Fresh-world `ConduitLayingGameTests`: collision/picking, hand placement, parallel lines, stale rack targets, power/data and existing-stub attachment, structure/lead refusal, loaded cells, atomic ordinary refusal, endpoint families and live mesh invalidation.
- Existing `PipeLayingGameTests`: fluid tank/rack paths, inventory refusal, dismantling/refund, open-end reconnection, update packets and column nozzle size. Keep these bodies once; filtered registrar calls them without copying tests.
- `ProcessControlGameTests`: last-sensor socket retirement retains the cable. `CreativePowerGameTests`: six-sided source connections. DCS identity/upper/lower collision: `DcsConsoleGameTests`.

Change workflow in `LineToolSession`, command/admission in `LineLaying`/`LinePlacement`, retained geometry/host edits in `LineLayout`, physical lead lifecycle in `LinePieces`, and only medium facts in the backend. Calling a shared helper while a second class owns the same decision is not consolidation. Software meshes cannot prove live chunk sync; dedicated-server tests and owner acceptance cover that boundary.

Historical route/rack design decisions and prior captures: `documentation/2026-10-26-pipe-laying-system/`. Offline render/probe tools live under `tools/pipe-laying-renders`, `tools/pipe-laying-system` and `tools/simulator-sizing`; generated evidence remains local.
'''
p.write_text(head+route+'\n\n'+racks+tail,encoding='utf-8')
p=Path('documentation/reference/code-guide/client-gui.md');s=p.read_text(encoding='utf-8')
s=s.replace('`init` sets the scale for `OperationColumn.HEIGHT` 360 (`WindowScale.apply(…, this::fits)`) and the door takes `OperationColumn.rows`','the door lays out `OperationColumn.rows` in the current GUI viewport with scrolling')
lines=s.splitlines()
for i,l in enumerate(lines):
 if l.startswith('- Every kit screen sets its own whole GUI scale'):lines[i]='- Every kit screen retains the player\'s GUI scale. Measure the actual viewport and use compact layout, scrolling or paging. Never mutate global scale during `init` or `removed`.'
 if l.startswith('- `-PmcpClientWidth'):lines[i]='- `-PmcpClientWidth/-PmcpClientHeight` (3 or 4 digits, default 854×480) sizes `runMcpClient` without desktop input; the player\'s GUI scale remains in effect.'
 if l.startswith('- A kit screen that does not call `WindowScale.restore`'):lines[i]='- Do not change the player\'s GUI scale to fit a door. `KitLayoutTest` guards production screens against scale mutation; scrolling and viewport layout handle smaller windows.'
 if l.startswith('| A screen\'s own scale'):lines[i]='| GUI scale preservation and viewport fit | kit layout and each screen | `KitLayoutTest`, software renders at actual GUI sizes |'
 if l.startswith('| Constructor pages, rows, kinds offered;'):lines[i]=l.replace("a column's Nozzles page",'main-door nozzle groups')
p.write_text('\n'.join(lines)+'\n',encoding='utf-8')
p=Path('documentation/reference/code-guide/fluid-runtime.md');s=p.read_text(encoding='utf-8').replace('`pipeline-tool-2`); `pipeline-tool-2` also carries the laying tool\'s open, lay, answer and dismantle payloads','`pipeline-tool-5`); `lines-1` carries shared line commands/answers')
s=s.replace('| `icheme:pipeline_tool_save` | `pipeline-tool-2` | C→S | varint menuId, double diameter, int paint, int secondary (each `Paint.line`) | `PipelineToolMenu.save` |','| `icheme:pipeline_tool_save` | `pipeline-tool-5` | C→S | Tool settings and selected medium; bounded by `PipelineToolMenu` | `PipelineToolMenu.save` |')
for l in s.splitlines():
 if l.startswith('| `icheme:pipe_laying_open`,'):s=s.replace(l,'| `icheme:line_command`, `icheme:line_answer` | `lines-1` | C→S, S→C | UUID, action/profile, targets and bounded cells/options; result | `LineLaying.execute`, `LineToolSession.answered` ([pipe-laying.md](pipe-laying.md)) |')
p.write_text(s,encoding='utf-8')
p=Path('documentation/reference/code-guide/verification.md');s=p.read_text(encoding='utf-8').replace('`ConduitLayingGameTests` (five)','`ConduitLayingGameTests` (twelve)').replace('Main merge pending.','Ported into the 0.22.0 branch; this batch is not merged.')
s=s.replace('`CoolingTowerTest`, `CoolingTowerGeometryTest`, `CoolingTowerRuntimeTest` and `LoadedEquipmentTest` run in `test`','`CoolingTowerTest`, `CoolingTowerRuntimeTest` and `LoadedEquipmentTest` run in `test`; `CoolingTowerGeometryTest` runs in `equipmentGates`')
s=s.replace('All tasks are defined in [build.gradle]', 'The historical per-class counts below describe their original batch; the current merge review records this branch\'s actual results. Source-set exclusions in `build.gradle` are authoritative.\n\nAll tasks are defined in [build.gradle]')
p.write_text(s,encoding='utf-8')
print('Updated shared line chapter and current GUI/runtime/verification contracts')
