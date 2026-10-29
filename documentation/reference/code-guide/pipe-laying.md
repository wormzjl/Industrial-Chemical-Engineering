# Pipe laying: the laying tool, routes and pipe racks

**Snapshot:** ce71b45c (main, mod 0.15.0), 2026-10-27 · **Updated:** 2026-10-27 (batch 2026-10-26-multi-machine-framework: holding the laying tool watches the column assemblies around the player (W7): a framed port, the port's line in its label, port colours from `PortColours`; the Pipe Sizing Tool resizes a whole line, rack lanes included, see [fluid-runtime.md](fluid-runtime.md); a column's nozzle takes the largest pipe its place clears: the anchor refuses a larger line (`NOZZLE_SIZE`) and the port label shows the nozzle's own largest, `67343274`) · earlier: 2026-10-27 (new chapter, batch 2026-10-26-pipe-laying-system: the laying tool, its route search and server check, explicit pipe joints, pipe racks and their lanes, transition pipes, line paints)
Up: [code guide](README.md)

## Read this when

- You change what the pipe laying tool does on a click: the start, the route, the ghost, the marks, the lay or the dismantle.
- A route is not found, goes the wrong way, or the server refuses what the client showed.
- You change a pipe rack: its shape at placement, its steel, the slots on its tier, how a lane is drawn, flanged, collided with, aimed at or picked.
- A laid line joins (or does not join) a pipe, a lane, a tank or a nozzle; a pipe beside a rack bends off centre (a transition pipe).
- You touch a line's paint, a pipe's `Joints`, `Paint` or `Secondary` NBT, the rack's lane copy (`rack-3`), facts schema 2's joint and lane fields, or the `pipeline-tool-2` packets.

The registry and island side of a laid pipe is in [fluid-runtime.md](fluid-runtime.md). The drawn pipe pieces (`LineGeometry`) are in [equipment.md](equipment.md). The tool's settings screen is in [client-gui.md](client-gui.md) and its item in [instrument-accessories.md](instrument-accessories.md).

## In one paragraph

The **pipe laying tool** (`icheme:pipe_laying_tool`) lays a line of pipe of its set bore between two clicks. The first click sets the start; the client then searches a route to whatever the player aims at (an A* over loaded blocks), draws it as a ghost, and shows its length and elbows on the action bar. The second click sends the route, cell by cell, to the server. The server never searches: it resolves both clicks again from its own world, checks the route it was sent against the same rules, and lays it whole or not at all, as **one ledger event**. A laid pipe has **explicit joints**: it connects only through the faces its route uses, so a line laid beside another never joins it. A **pipe rack** block carries up to four lines on one tier across its run, with crossing levels above and below; a line through a rack is a **lane** of that rack cell, registered in the fluid ledger at the rack's position with its lane number, and meets the next cell's lane where their points on the shared face coincide. A plain pipe beside a lane bends to the lane's off-centre point (a **transition pipe**). A rack's shape (straight, corner, tee) is decided once when it is placed, from the racks pointing into it and the player's look. Shift + use dismantles one pipe block or one lane at once. Everything the client shows is computed from blocks it already holds; nothing solves, and the fluid side takes every change on the engine schedule.

## Class map

Paths are relative to `src/main/java/com/wormzjl/icheme/`.

| Class | Role | Read it for |
|---|---|---|
| **Client** | | |
| [`client/PipeLayingClient`](../../../src/main/java/com/wormzjl/icheme/client/PipeLayingClient.java) | The tool on the client: turns each press of the use key into one command (`use`); keeps the start until `answered` says laid; one lay in flight (`sent`); the click aimed on a rack's tier (`aimed`); the plan (`plan`: anchor, `PipeRoute.search`, `Placement`); HUD; outlines (start, refused route, what a dismantle removes, the aimed slot's face square); marks (ports, open ends, free slots); the ghost (`render`); the assembly watch (`AssemblyToolClient.watch` each tick, [machine-assemblies.md](machine-assemblies.md)) | Everything the player sees before a lay |
| **Anchors and route** | | |
| [`world/level/block/PipeAnchor`](../../../src/main/java/com/wormzjl/icheme/world/level/block/PipeAnchor.java) | Where a line starts or ends, from a `Click` (block, face, block-local hit point): `Kind` FREE, PIPE, DEVICE, NOZZLE, RACK, LANE; `Refusal` (11, the last `NOZZLE_SIZE`); `Fit` (widths and height a line must have there); `resolve` (client and server alike); `aimed` (the slot on a rack's tier); `end()` → `PipeRoute.End`; `leaving()` (the start's heading); `space(level)` (the route's view of a level); `free` | What a click means |
| [`science/equipment/PipeRoute`](../../../src/main/java/com/wormzjl/icheme/science/equipment/PipeRoute.java) | Minecraft-free route: `Cell`, `Room` (FREE, BLOCKED, or a rack with its lanes and jointed faces), `Space`, `End`, `Limits`, `Refusal`, `Result`; `search` (client only: A* with an admissible `Estimate`, rack options as bits, `choose`); `check` and `blocked` (server); `Placement` (`laidJoints`, `facings`, `arms`), `elbows` | Route costs, rules the server holds the client to |
| **Server** | | |
| [`world/level/block/PipeLaying`](../../../src/main/java/com/wormzjl/icheme/world/level/block/PipeLaying.java) | `lay` (every check, then one ledger event, then the blocks and lanes), `dismantle` (one pipe block or one lane), `START_RANGE` 64; the player messages (`refusal`, `describe`, `message`, keys `message.icheme.pipe_laying.*`) | The order of the server's checks |
| [`network/FluidNetwork`](../../../src/main/java/com/wormzjl/icheme/network/FluidNetwork.java) | Registrar `pipeline-tool-2`: `PipelineToolSave` (bore and both paints), `PipeLayingOpen`, `PipeLay`, `PipeLayAnswer` (S→C), `PipeDismantle`; client senders `openPipeLaying`, `layPipe`, `dismantlePipe` | Wire fields |
| [`world/item/PipeLayingToolItem`](../../../src/main/java/com/wormzjl/icheme/world/item/PipeLayingToolItem.java) | The item: stack 1, default `pipe_laying_settings` 0.05 m; `paint`, `secondary`, `paintName`; its own `use` does nothing ([instrument-accessories.md](instrument-accessories.md)) | Components, tooltip |
| **Racks** | | |
| [`science/equipment/RackGeometry`](../../../src/main/java/com/wormzjl/icheme/science/equipment/RackGeometry.java) | Minecraft-free rack cell: `Rack` (facing, shape, steel bits, beam top), `Lane` (option, width, arms, joined, size code); sizes (`width`, `height`, `beam`, `largest`, `clears`); options (`option`, `crossing`, `tier`, `position`, `level`); `valid`, `takes`, `fits`, `open`, `runs`, `along`; tees (`legFaces`, `legRack`, `leg`, `landing`); `aim` (`Aim`); `placed` (the shape at placement, `Neighbours`); `steel`, `framed`; face points (`point`, `points`, `steps`, `facePoint`); drawing (`lane`, `spool`, `spoolAt`, `setback`, `polygons`, `steelwork`); collision and picking (`members`, `boxes`, `pick`, `Pick`) | Where a lane lies, what fits, how the rack looks |
| [`world/level/block/PipeRackBlock`](../../../src/main/java/com/wormzjl/icheme/world/level/block/PipeRackBlock.java) | Block `pipe_rack`: `FACING`, `SHAPE` (`Shape` STRAIGHT, BACK_LEFT, BACK_RIGHT, FRONT_LEFT, FRONT_RIGHT, TEE), `placed` at placement, `updateShape` → `redraw`, a rack with lanes refuses breaking (`Breaking.refuse`, `getDestroyProgress`, `onDestroyedByPlayer`, `onBlockExploded`), `onRemove` removes its lanes' registrations | Block state, breaking |
| [`world/level/block/entity/PipeRackBlockEntity`](../../../src/main/java/com/wormzjl/icheme/world/level/block/entity/PipeRackBlockEntity.java) | The lane copy (`Lane`: identity, option, width, arms, joints, bore, paint, secondary; format `FORMAT` `rack-3`, `write`/`read`), drawn state (`joined` per lane, `frame` steel bits), `bind` / `refresh` / `redraw`, `addLane`/`removeLane`, `room()` for the route, `pick`, `shape()`, `openFaces`, `laneTowards`, `points`, model data `Drawn` | The rack's saved and drawn data |
| [`client/model/PipeRackModel`](../../../src/main/java/com/wormzjl/icheme/client/model/PipeRackModel.java) | Chunk-meshed rack cell: key `Look(rack, lanes, painted, spools)`, cache 4096; frame untinted, each lane with its painted sprites and tint index 2 × option + channel; loader `icheme:pipe_rack` | Rack drawing |
| **Fluid side** ([fluid-runtime.md](fluid-runtime.md)) | | |
| [`runtime/fluid/PhysicalFluidTopology`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/PhysicalFluidTopology.java) | `Position.lane` (0, or option + 1 up to `RACK_PLACES` 14); `Device.joints` (`ALL_FACES` 63 for every device but a laid pipe), `slots` (a lane's width, ≤ `RACK_WIDTH` 4), `points` (a lane's face points); `jointAt`, `point`, `withJoints`, `transitionPipe`; `joins` with `lanesMeet`; `across` (what a device meets across a face, lanes included); `laneFits` | How a laid pipe or lane joins |
| [`runtime/fluid/FluidWorldAuthority`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/FluidWorldAuthority.java) | `lay(cells, diameter, hosts)` with `Laid` (position, facing, joints, slots, points): one ledger event; `placeLane` (a lane registered again from its rack's copy); `place(…, joints)` (a laid pipe registered again with its joints) | The ledger event of a lay |
| **Drawing** ([equipment.md](equipment.md), [client-gui.md](client-gui.md)) | `LineGeometry` (transition pipes, `Key.offsets`, `OFFSET`, `SPOOL`), `PipeSizes.drawn`, `Paint.BARE`/`line`, `Mesh` torus phase; `FluidDeviceBlockEntity` (joints, paints, offsets, `openEnds`, `transition`); `FluidDeviceModel`, `EquipmentShellModel.paintedLines/painted/lineTints`, `EquipmentClient` tint handlers | |

## Main flow

```mermaid
sequenceDiagram
    participant P as Player
    participant C as PipeLayingClient
    participant N as FluidNetwork (pipeline-tool-2)
    participant L as PipeLaying (server)
    participant A as FluidWorldAuthority
    P->>C: use (first click)
    C->>C: PipeAnchor.resolve on the client's blocks: start kept
    loop every client tick while the tool is held
        C->>C: aimed click -> plan: resolve, PipeRoute.search; ghost, HUD, marks
    end
    P->>C: use (second click)
    C->>N: PipeLay(start click, end click, cells, options); sent = start
    N->>L: lay(player, hand, clicks, cells, options)
    L->>L: resolve both anchors again; refuses; check; blocked; hosts; items
    L->>A: lay(cells, bore, hosts) [one queued ledger event]
    L->>L: place pipe blocks, add lanes, paint, refresh joints
    N-->>C: PipeLayAnswer(message, laid)
    C->>C: laid: start dropped; refused: start kept, reason shown until the aim moves
```

1. The use key fires `InputEvent.InteractionKeyMappingTriggered`; `PipeLayingClient.use` cancels it, so the item's own use never runs, and acts once per press (`armed`). Shift + use cancels a start; with no start it dismantles the pipe or lane under the crosshair (`PipeDismantle`), or opens the settings (`PipeLayingOpen`, the shared `PipelineToolMenu`).
2. The first click resolves an anchor on the client (`PipeAnchor.resolve`, then `refuses(outside)` for the tool's size). A refused click says why and sets nothing.
3. Each tick `tick` re-plans when the aimed anchor changes, and every 20 ticks (not while the server's refusal is shown). The plan resolves the end anchor, checks both ends' `Fit`, and runs `PipeRoute.search` from the start's `leaving()` face. The ghost draws the found route at the tool's size and paint; a refusal draws a red line and its reason.
4. The second click sends the clicks (not the anchors), the cells and each cell's lane option (−1 for a pipe block). While that lay is unanswered a further click sends nothing.
5. The server's `PipeLaying.lay` re-resolves, checks and lays (see "Lay and dismantle"). The answer carries the message and whether it laid. Only a laid answer drops the client's start.
6. The registry applies the lay's event on the next engine tick; the new pipes and lanes then join their island ([fluid-runtime.md](fluid-runtime.md), flow (a)). The blocks are placed at once, before the event applies.

<details><summary>The client: use key, aim on the tier, plan, marks and ghost (PipeLayingClient)</summary>

- **State.** `start` (the first click, its anchor and dimension), `sent` (the start of the lay sent last, until `answered`), `plan` (the route to the aimed click), `refused` (the plan the server refused, kept with its message until the aim moves), `slot` (the slot aimed on a rack before a click). Not holding the tool clears all of them; so does a dimension change, or the player moving more than `DROP_RANGE` 96 blocks from the start ("Laying cancelled: too far from the start").
- **Aim on the tier** (owner 2026-10-26, `aimed`). The client walks the view ray block by block up to the block it hits. The first rack cell whose tier plane (the beams' top, `frame().beam()` × 1/32 m) the ray crosses inside the cell is the target, even through the open frame; the click's local point is that crossing, sent 1/32 m under the beams' top so no lane resting on them is picked. If the hit block is that rack and the hit lands on a lane (`pick`), the lane is the click (to extend it). Looking level along the tier, the ray never crosses it, and the block hit decides.
- **Same anchor** (`same`): two clicks give one anchor when block, face and, on a rack, the picked lane and arm or the aimed slot agree; only then is the plan kept.
- **HUD** every 5 ticks: `hud.route` (from → to, cells, elbows, bore, pipes needed; "—" in creative) or the refusal; with no start, the aimed nozzle's role, whether a pipe is on it and the largest bore the nozzle takes, rounded down to the centimetre (`portLabel`, "fits up to … m", from `EquipmentShellBlockEntity.largestBore`: a pipe's largest, on a column what the neck's place clears, `NozzleBore`), then, for a nozzle of a watched column assembly, ` · ` and its line (`AssemblyToolClient.line`: the partner it should reach and its state, key `gui.icheme.assembly.tool.line`).
- **Outlines** (Catnip `Outliner`): the start cell green; a refused route red from start to end; while Shift is held with no start, what a dismantle would remove in red (a pipe block, or a lane's leg boxes, `RackGeometry.boxes`); the aimed slot's face square, green or red.
- **Marks** (re-shown each tick, at most `MAX_MARKS` 256 of each, nearest first, within `PORT_RANGE` 48): from `EquipmentShellBlockEntity.clientPortCells`. Colour snapshot `645e203d` (`claude/column-operation-5c3005`), 2026-10-29: assembly nozzle squares use the same `PortColours.assembly` lookup as the GUI, by equipment slot, role and ordinal; the column's own ports need no report, other equipment uses the watched Check parts report. Drawing models are cached by immutable design and cleared with the watch or its next view. Matched squares retain their colour after connection, with a grey frame 0.4 across; misplaced parts retain yellow frames. Needed frames use the same identity colour. Nozzles absent from the drawing keep phase class colours (vapour white, bulk cyan, liquid blue, overflow orange; grey with a pipe). Open line ends remain yellow (`FluidDeviceBlockEntity.clientOpenEnds`, `PipeRackBlockEntity.clientRacks`, `openFaces`, at the lane's point); an aimed rack has a small cube on each free tier position (`RackGeometry.taken`).
- **Ghost** (`render`, `AFTER_PARTICLES`, `entityTranslucentCull` on the block atlas, sprite `icheme:block/painted_shell`, alpha 140): the route at the tool's size, a lane as its rack will draw it, welded at every joint (`RackGeometry.lane`), a plain cell beside a lane as a transition pipe (`lanePoint` → `LineGeometry.Key.offsets`); before each click, the lane the aimed slot would take (red where it does not fit, or the route to it is refused). Its colour is the tool's primary paint (`Paint.tint`), light blue 0xB0D8FF when bare.
- Every read is of blocks the client holds; the search runs on the render thread within `PipeRoute.Limits.DEFAULT`.
</details>

<details><summary>Anchors: what a click means (PipeAnchor)</summary>

`resolve(level, click, outside)` gives an anchor or a `Refusal`. Client and server call it on their own world; the server takes from the client only where the click landed.

| Clicked | Anchor (`Kind`) | End cell, joint | Host edit (`joints`) | Refusals |
|---|---|---|---|---|
| A rack, on a lane (`pick`) | LANE | the cell beyond the lane's open end (the clicked arm where it is open, else its lowest open face), joint back to the lane | the lane's joints plus that face, or none where the joint exists but nothing meets it any more | LANE_CLOSED (no open end, D11: a rack carries no junctions); FACE_TAKEN (another lane leaves through that face towards a plain cell) |
| A rack, off its lanes; or a block face that looks into a rack cell | RACK | the rack cell itself, as a new lane in the slot aimed on its tier (`aimed` → `RackGeometry.aim`); heading = the aim's `through` face | none | LANE_SIZE (the cell takes no lane that wide), SLOT_TAKEN (the aimed slot does not fit) |
| A pipe, on a face it may connect through | PIPE | the next cell beyond the face | a laid pipe gains the joint (a tee or cross, or an extension); a hand-placed pipe (every face) needs none | FITTING (a fitting closes the face, or a laid pipe with a fitting), JUNCTION (four joints already), FACE_TAKEN, TRANSITION (a pipe that meets a lane, or will, keeps two joints, no fitting, no gauge) |
| Another fluid device, on a face it connects through | DEVICE | the next cell | none | DEVICE_FACE |
| An equipment cell with a nozzle on that face | NOZZLE | the cell in front of the nozzle | none | NOZZLE_SIZE (the line's drawn outside is over the nozzle's largest, `PipeSizes.outside(shell.largestBore(face))`; [equipment.md](equipment.md), `NozzleBore`) |
| Anything else | FREE | the clicked block if replaceable (grass), else the cell beside the face; open end (no joint) | none | |

- Then for every kind: the end cell must lie inside the build height and world border in a loaded chunk (OUTSIDE), and be free (`free`: replaceable) unless it is a rack cell (OCCUPIED). A device or nozzle never ends in a rack cell.
- **`Fit(minWidth, maxWidth, height)`**, in rack positions (`RackGeometry.width` of the standard drawn size): `ANY`; `RACK` (1 to 4); a lane continued into the next rack keeps its width and centre height (I9); a line out of a lane into a plain cell is no wider than the lane; a lane a pipe leads into is at least as wide as the pipe; an end cell in a rack is capped at four positions (RD10). A line of the wrong width or height there gives LANE_SIZE; `refuses(start, end, outside)` checks the start, then the end.
- **`end()`**: `PipeRoute.End(cell, joint, point)`; for an aimed slot `End(cell, -1, -1, option, through, open)`. `point` is the lane's point on the joint face when the line continues into the next rack (`PipeRackBlockEntity.point`).
- **`leaving()`**: the face a line starting here leaves along: the clicked face, or for an aimed slot the face opposite its open end.
- **`space(level)`**: rooms from loaded blocks only (a rack's `room()`, else FREE where `free`, else BLOCKED); its `racks(low, high)` lists the rack block entities of the loaded chunks over the search box.
</details>

<details><summary>Route search and the server's check (PipeRoute)</summary>

- **Faces** follow `LineGeometry.STEPS` (down, up, north, south, west, east); face f's opposite is f ^ 1. A route's heading in a cell is the face it travels towards.
- **`End(cell, joint, point, option, through, open)`**: `joint` the face joined to what the line meets (−1: open end); `point` the lane point it must meet on that face (−1: any); an aimed slot fixes `option`, the face it passes `through` to the route and the face it ends `open` at (all three or none; an aimed end has no joint).
- **Costs**: `STEP` 1 per free cell, `RACK_STEP` 0.4 for a step into a rack cell along its run (`RackGeometry.along`), `ELBOW` 1.5 per change of heading (and for an end entered other than straight onto its joint), `VERTICAL` 0.3 per vertical step, `TRANSITION` 0.5 between a lane and a plain cell. Routes therefore follow racks, as plant piping does.
- **`search(start, heading, end, outside, space, limits)`** (client only): exact A*. `Estimate` never overestimates: min(d, 0.4 d + 0.6 (max(0, r − 1) + r_goal)), with d the Manhattan distance and r the distance to the nearest rack run (runs are boxes of rack cells in a row along their facing's axis; racks with no room for the line are left out), plus 0.3 per block still to climb or drop and 1.5 per turn still needed. Ties go to the lower f, then the lower h, then the earlier push; pushes follow face order, so a search is deterministic. The box is the two ends widened by `Limits.margin` 8; a route holds at most `cells` 128 and the search expands at most `expansions` 32 768 states; a search that found no route gives TOO_LONG if it hit either cap, else BLOCKED.
- **Rack options as bits.** A state in a rack cell holds every lane option still possible there; a step to the next rack keeps those meeting one of them on the shared face (`entering`, `leaving`, `points`), so equal choices are one state (`anOpenRouteCostsTheSearchAboutOneNodePerBlock` pins the search cost). A rack face towards a plain cell carries at most one lane: a step between a lane and a plain cell is refused through a face another lane has a joint on (plan 5.2). A rack the line is too wide for is BLOCKED (`admitted`, RD10).
- **Each cell once** (`holds`, fix `43ad8063`): a route never steps into a cell it already holds. A rack's room lists only the lanes already laid, not the route's own, so without this a line aimed out of its start away from its end came round through its start cell, which the server then refused.
- **The approach cell**: an aimed end is entered only from the cell beyond its `through` face, and from that cell the route may step only into the end. An aimed start leaves only through its `through` face. A start aimed away from its end comes round outside its cell, or there is no route.
- **One cell** (fix `eeb0742f`): start and end in one cell is one lane (or one pipe block). With an aimed end its faces are the aimed lane's two faces, whichever half each click aimed at; both aimed ends must name one slot (ONE_SLOT); each end's joint must lie on one of those faces (BLOCKED); both joints on one face is SAME_FACE.
- **`choose`**: once the route is found, each rack portion's options are chosen from its last cell back. At the last cell the positions nearest the side the line leaves towards win (I7: the side its arm leaves through, else the first sideways step after the portion, else the last one before it), then the fewest level changes (tier, then above, then below); in a tee the run's lanes keep to the positions furthest from the branch and turning lanes to their own side (`rank`). Earlier cells take the option meeting the next on the shared face with the lowest level.
- **Refusals** (`Refusal`, messages `message.icheme.pipe_laying.route.*`): START_OCCUPIED, END_OCCUPIED, SAME_FACE, BLOCKED, TOO_LONG, BROKEN, JOINTS, ONE_SLOT.
- **Server: `check(cells, options, start, end, maxCells)`**: at most 128 cells; one option per cell, −1 or 0..13; first and last at the ends' cells; each cell a neighbour of the one before and none twice; no end joint on a face the line itself uses (JOINTS); an aimed end's option kept and the line passing through its `through` face.
- **Server: `blocked(cells, options, start, heading, end, outside, space)`**: the first cell the world does not admit as the search would, or −1: a plain cell free; a lane only in a rack cell, suiting and fitting it with the arms the route gives (`RackGeometry.valid`, `fits`); the ends' joints on their cells' faces; the end points met; consecutive lanes meeting on their shared face; no lane-to-plain step through a face another lane has a joint on. Whether a host takes its new joint is the anchors' (and `PipeLaying`'s) to check.
- **`placement(cells, start, heading, end)`** → `Placement(joints, facings, arms)`, one entry per cell, shared by the client's ghost and the server's lay: `laidJoints` (the faces to the cells before and after, and each end's joint), `facings` (the face towards the next cell; the last keeps the heading it was entered along), `arms` (the two faces each cell's pipe runs to; an open end runs straight on). `elbows` counts the bends for the HUD.
</details>

<details><summary>Lay and dismantle on the server (PipeLaying)</summary>

`lay(player, hand, startClick, endClick, path, options)` returns null when laid, else the player's message. Every check runs before anything changes, so a refused lay changes nothing:

1. The hand holds the laying tool with its settings; the player is no spectator and may build (`not_allowed`).
2. The end click is within interaction reach (+1 block) and the start click within `START_RANGE` 64 blocks (`out_of_reach`); both clicked chunks are loaded (`anchor.outside`).
3. Both anchors resolve on the server's world, and both take the tool's size (`PipeAnchor.refuses`).
4. `PipeRoute.check`, then `PipeRoute.blocked` against `PipeAnchor.space(level)` (`occupied`, naming the cell), then `level.mayInteract` on every cell.
5. Each host the line meets with a new joint is a registered pipe (`anchor.fitting`); its joints with both ends' edits stay at most two for a lane (`anchor.lane_closed`), four for a pipe (`anchor.junction`).
6. Outside creative, one `fluid_pipe` item per cell, lanes included, is taken (`items`), else nothing is.
7. `FluidWorldAuthority.lay`: one passive pipe registration per cell (bore = the tool's, nitrogen spec, identities in path order, a lane at `Position(…, option + 1)` with its width and `PipeRackBlockEntity.points`), and the hosts' new joints as edits (`Device.withJoints`, revision + 1), in **one** `submit`: one ledger event.
8. With `FluidDeviceBlock.laying` set (its `onPlace` then registers nothing), each plain cell becomes a `fluid_pipe` facing its `facings` entry (`UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE`), takes the tool's two paints and binds its identity; each lane cell's rack `addLane`s the lane with its identity and paints. Then racks take their lanes' joints (`refresh(false)`), host pipes and the new pipes redraw from their projected registrations (`refreshGeometry`, so every block's joints are set before a neighbour reads them), and racks redraw with their neighbours (`refresh(true)`).

`dismantle(player, hand, click)` (owner decisions D6, D7: one block, at once):
- On a rack: the picked lane (`pick`; off the lanes: `not_a_pipe`), after the block-action checks (`blockActionRestricted`, `mayInteract`; no break event, the rack stays): `world.remove(identity)`, `removeLane`, `refresh(true)`; outside creative one `fluid_pipe` item back.
- On a pipe block: `player.gameMode.destroyBlock` (the break event, fittings and gauges refunded through the accessory store, the drop in survival). Anything else: `not_a_pipe`.
- The neighbours keep their joints towards the gap and become open ends, which a new line laid to them joins.

The packet handlers (`FluidNetwork`, `HandlerThread.MAIN`) send `PipeLayAnswer(message, laid)` for a lay and an action-bar message for a dismantle. Neither runs a solve or reads process state.
</details>

<details><summary>Rack geometry: frame, slots, levels, tees, fits (RackGeometry)</summary>

- **Coordinates.** Canonical cell coordinates: u across the run from the left side (as seen along the facing), y up, w along the run from the back face; `Rack.world(u, y, w)` maps them to block coordinates. Heights are in 1/32 m steps (`LineGeometry.OFFSET`), so every face point lies on the transition pipes' grid.
- **`Rack(facing, corner, steel, beam)`**: facing a horizontal face (2..5); shape `STRAIGHT` 0, `BACK_LEFT` 1, `BACK_RIGHT` 2, `FRONT_LEFT` 3, `FRONT_RIGHT` 4, `TEE` 5 (the field is named `corner` but holds every shape); steel bits (`STEEL`, below); beam top 2..13. `Rack(facing, corner)` is the lane geometry alone. A tee's run passes from its left face to its right and its branch leaves through its front.
- **Tier.** `POSITIONS` 4 across the run at `PITCH` 3/16 m, the first centre at `FIRST` 7/32 m, `CLEARANCE` 1/64 m between lines. A line takes `width(od)` = ⌈(OD + 1/64) / (3/16)⌉ positions of its drawn size (`PipeSizes.drawn`, I12); over `MAX_WIDTH` 4 (OD over `largest(4)` = 0.734 m) it does not go on racks (RD10). It rests on the beam, top at `BEAM_TOP` 13/32 m: its centre `height(od)` stands its radius above, rounded up to the grid (support P2). A line that would then come within the clearance of the block's top (a four-position line, 24" to 28") sits at mid-height (16/32) on a beam lowered just beneath it (`beam(lanes)`; owner addition to RD11).
- **Options** (I1): a tier lane is `option(p, l)` = p + 4 l, p its first position and l the level its side arm leaves at: `TIER` 0 (flat), `ABOVE` 1, `BELOW` 2; a crossing lane runs side to side at mid-block, option 12 (`UPPER` 26/32 m) or 13 (`LOWER` 6/32 m). In a tee the level is the lane's leg: `RUN`, `TURN_LEFT`, `TURN_RIGHT`. `OPTIONS` 14. The ledger lane number is option + 1. Lanes meet across a face where their points coincide, so the number is only a label.
- **`valid(rack, option, width, arms)`**: a crossing runs side to side across a straight cell; a corner's lane runs from its end face to its side; a tee's along its leg (`legFaces`); any other tier lane has an end arm and runs on to the other end, up, down or out of a side. `takes`: in a straight cell a lane leaves above or below only one position wide; a corner's lanes stay on the tier; a crossing is one wide (I10).
- **`fits(rack, lanes, lane)`**: it suits the cell; it takes no position another lane takes or passes over flat (`shadow`), nor passes flat over one; it shares no level; every tier line clears a line at a crossing level (`clears`: lines up to about 10" pass under the upper level; `clearsUnder`: every line resting on the beam passes over the lower; a lowered beam closes it). A tee keeps its lanes to their legs (`room`, `teeApart`, `landing`: left-turning lanes stay left of right-turning ones; the run passes behind every landing). `open` is the same test before the arms are known (the route's `entering`).
- **Tees** (J3). A tee's lane is drawn and fitted on its leg (`leg`, `legRack`): the run as a straight cell facing the tee's left, a turning lane as the corner from the branch turning left or right, two corners back to back with the run behind them.
- **`runs(rack)`, `along(rack, face)`**: the faces lanes pass through along a run: a straight cell's ends, a corner's end and side, a tee's run faces and branch. Only these steps cost `RACK_STEP`.
- **Face points.** `point(rack, option, width, od, face)`: the lane's offsets from the face's centre along its in-plane axes in 1/32 m steps, packed in ten bits (two signed five-bit steps, as `LineGeometry.Key.offsets`), −1 where it has no arm there. `points(rack, lane)`: ten bits per arm face, what the registration carries. `steps` unpacks; `facePoint` is the block-local point.
- **`aim(rack, lanes, outside, point)`** → `Aim(option, through, open, fits)`: the run face nearest the point in plan is `through`; of the tier lanes from it to another run face (on along the run, round a corner, a tee's run or turn) the free one whose centre line passes nearest the point in plan wins; with none free the nearest that suits the cell, `fits` false. Exits over, under or out of the side are the route's, in later cells. Null where the cell takes no lane that wide.
</details>

<details><summary>Rack placement, steel, flanges, drawing and picking</summary>

- **Shape at placement** (`placed(around, look, sneaking)`, RACK_JUNCTION_PLAN J1 and J2; decided once, never changed after). The rack faces the look (owner 2026-10-26: "strictly based on player facing"). Its feeds are the neighbouring racks whose run passes through the face towards the cell; a rack beside a run, side on, never feeds it. Sneaking: straight along the look. No feed: straight. One: straight where it lies on the look's axis, else a corner from it towards the look. Two in line: straight where the look runs along them, else a tee whose branch opens towards the look. Two at a corner: that corner. Three: the tee joining them, which faces its branch (the feed with none across from it), the one exception to facing the look. Four: straight (X1: runs cross at two heights, one stacked on the other).
- **Steel** (`steel(around, rack, along, previous)`, bits `BENT_BACK` 1, `BENT_FRONT` 2, `STACKED` 4, `LEFT_JOINED` 8, `RIGHT_JOINED` 16, `GROUNDED` 32, `END_BACK` 64, `END_FRONT` 128, `RISING` 256): drawn state, from loaded neighbours; a neighbour not known (unloaded chunk) keeps its bit from `previous`. A straight cell stands a bent every third block face along its run (`along` in world coordinates), half in each cell's face plane; a whole bent at each end where no straight rack of its axis continues the run (nothing, a junction, or a rack across); its columns run on up under a rack stacked on it; a side with a straight rack of its axis beside it joins it (no column there, beams through). Every cell: pedestals where no rack stands under it (`GROUNDED`); side braces rising from the back on even blocks (`RISING`). A junction (corner or tee) has no bents of its own: H columns at its outer corners only, and the straight racks next to it stand a whole bent against it. `PipeRackBlockEntity.BARE` (bents and whole ends both sides, grounded) is the steel of the item and of a cell not yet redrawn.
- **Frame** (`steelwork`, RACK_FRAME_PLAN 3a, "sturdy", in 1/32 m): H columns 3.75 wide, I beams 4 deep under the lines, knee braces, concrete pedestals and base plates; a shared bent is 2 deep in each cell, a whole one 4; a light frame (posts and an angle) at a low face without a bent; side channels at the beam; diagonal and plan braces alternating by block, left out where a lane passes. Steel over the beam's top stays within 3.75/32 of the sides. `polygons(rack, lanes)` draws it; `members(rack)` gives its solid boxes (braces are drawn only).
- **A lane drawn** (`lane(rack, lane, spool)`): a straight pipe; a corner's bend about the inner corner, so a corner's bends nest and keep their order (I4); a turn up, down or out at mid-block as a transition pipe draws its bends, at most `BEND` 7/16 m radius so a bend starts clear of the bents' planes (I11). Welded where a line next door continues it (`joined`); else a blind flange set in off the steel at that face (`setback`: past every member its plate and nuts would reach), or capped at the face where that would pass the cell's middle (21"+ lines in a stacked rack); a corner's bend is capped at an open end, never flanged. `lane(rack, lane)` (no spool) is the ghost's.
- **Spool flanges** (owner: "staggered, off steel"): a lane joined straight through a straight cell carries a flange pair where its world coordinate along the run minus 3 × its position is a multiple of `LineGeometry.SPOOL` 12 (`spool`), at the nearest place to the face the side braces leave low where both plates clear the steel (`spoolAt`); where none does, the line runs on welded. Lanes side by side therefore flange 3 blocks apart.
- **Collision and picking.** `boxes(rack, lane)`: one box per leg of the lane's centre line. `pick(rack, lanes, x, y, z)` → `Pick(lane, face)`: the lane with a leg box nearest the point within `PICK_TOLERANCE` 1e-3; its first leg leads to its first arm, later legs to its second; `NONE` off every lane. `PipeRackBlockEntity.shape()` joins the frame's members (cached per frame in `FRAMES`) and every lane's boxes: collision, outline and picking use the same shape.
</details>

<details><summary>The rack block and its lane copy (PipeRackBlock, PipeRackBlockEntity, PipeRackModel)</summary>

- **Block** `icheme:pipe_rack`: metal, strength 3.5, `noOcclusion`, `dynamicShape`, push reaction BLOCK, tag `create:non_movable`, mineable with a pickaxe, drops itself. State `facing` (horizontal) and `shape` (`Shape` names, lower case, in `RackGeometry`'s shape order: a new shape goes last). The blockstate JSON has one variant; the model draws facing and shape from the state.
- **Breaking** (owner decision D9). A rack that carries lanes cannot be mined (`getDestroyProgress` 0), a player's break is cancelled (`BlockEvent.BreakEvent`, message `rack_carries`; the cancelled break re-sends the block and its lanes), an explosion leaves it, and `onDestroyedByPlayer` keeps it. Removed any other way (a command), `onRemove` removes its lanes' registrations.
- **Lane copy** (`Lane(identity, option, width, arms, joints, bore, paint, secondary)`): each lane's facts, kept in the rack for drawing, picking, the shape and the route, and to register a lane again when the ledger has none (after a facts loss). Arms are not in the ledger: a registration gives them back from its joints and facing (`Lane.arms(joints, facing)`; `Lane.facing()` is an arm without a joint, else the lowest joint). Paints exist only in the copy.
- **`bind(world)`** (server `onLoad`, and `FluidWorldAuthority.loadedChunk`): matches the copy to the ledger, which holds the fluid facts. A lane with a registration takes its identity and data and keeps the copy's paints; a lane without one registers again from the copy (`placeLane`); a registration with no copy is adopted, bare. A lane the cell's shape does not take, or with no room beside the lanes before it, is dropped from the copy. Then `refresh(true)`.
- **`refresh(neighbours)`** (server, after a lay, a dismantle, and `RegistryHost.drawn` for a lane): each lane takes its registered joints and bore where option, width and arms still match; the drawn state is recomputed (`refreshDrawn`) and sent if changed; with `neighbours`, pipes next door redraw (`refreshGeometry`) and racks next door redraw their drawn state.
- **`redraw()`** (from `PipeRackBlock.updateShape` through `server.execute`, and from `FluidDeviceBlockEntity.refreshGeometry`): recomputes the drawn state and sends it when it changed.
- **Drawn state, never saved**: `joined` per lane (which jointed faces meet a line next door: a lane of the next rack with the opposite joint at the same point, or a pipe connected towards the rack; a face in an unloaded chunk keeps what it showed) and `frame` (the steel bits). It travels in the update tag (`Joined`, `Frame`) only.
- **The server sends every change of the copy** (fix `9b5f1fcb`): `setLanes`, through which `addLane`, `removeLane`, `refresh` and `bind` all go, sends the block entity when the lane list changed. A one-block lane has no joint, so nothing `refresh` draws changes; before the fix it reached clients only after a reload. A send (`sync`) queues the block in its chunk's block updates (`sendBlockUpdated`), which reach the clients at the end of the tick.
- **Route view**: `room()` = the rack's lane geometry, its lanes as drawn with nothing joined (`unjoined`), and the faces its lanes have joints on.
- **Model** (`PipeRackModel`, `pipe_rack.json`: parent `fluid_device`, loader `icheme:pipe_rack`, plus the `concrete` texture): model data `PipeRackBlockEntity.Drawn(rack, lanes, paints, secondaries, spools)`; key `Look` with two painted bits per lane (not the colours) and a spool bit per lane; the frame baked untinted; each lane on `paintedLines` sprites with `lineTints(painted, 2 × option)`. Without model data, the bare frame of the block state; as an item, a straight rack facing north. `EquipmentClient`'s rack colour handler gives tint index t the paint of the lane whose option is t >> 1, channel t & 1.
</details>

<details><summary>The fluid side: explicit joints, lanes, transition pipes</summary>

- **Joints** (plan section 3; W1). `PhysicalFluidTopology.Device.joints`: the faces a pipe may connect through, a bit per `Direction` ordinal. A laid pipe has the faces its path uses; every other device, and a hand-placed pipe, has `ALL_FACES` 63 (owner decision D3: hand-placed pipes keep automatic joints). Only a laid pipe (a PIPE with no ports) may have explicit joints. `mechanicallyConnects` requires `jointAt`, so two blocks join across a face only if both have a joint there; a line laid beside another never joins it. On the world side `FluidDeviceBlock.connects` requires the joint on both block entities (`FluidDeviceBlock.joints`).
- **A lane** is a laid pipe at `Position(dimension, x, y, z, option + 1)`, `slots` its width (1..4, `laneFits`), `points` its face points (ten bits per face, under 2^60). It has at most two joints, no cutoff, filter or control: a rack carries no junction, fitting or gauge (plan 5.2).
- **`across(at, device, from, cell, direction)`**: what a device meets across a face. From a lane: the plain device in the next cell, else the lane there jointed back with the same point. From a pipe: the device in the next cell, else the lane of the next rack cell jointed back through this face (at most one). Every registry walk (`PhysicalRegistry.submit`'s fencing walk, the component walk, the lone-island walk) and `compile` use it; where no lane is near it is the plain lookup.
- **`joins` and `lanesMeet`**: two lanes meet where their points on the shared face are equal (I2), whatever their racks' facings; a lane meets a plain device only if that device is a **transition pipe** (`transitionPipe`: a laid plain pipe of at most two joints, no fitting, no actuator, no filter).
- **Transition pipes** (plan 6; W4). A plain cell beside a lane: `FluidDeviceBlockEntity.computeFaces` gives the face the lane's size code and the lane's point as `offsets` (`LineGeometry.Key.offsets`), and `LineGeometry` draws a run that bends to it: to the opposite face in two equal bends a quarter block in from each face; to an adjacent face in one turn, picking the path that leaves the largest bends, with ball corners where no bend fits. Such a pipe takes no fitting or sight glass (`PipelineFittingItem`), no gauge mount (`InstrumentHosts`), and no branch from the tool (anchor refusal TRANSITION). `FluidDeviceBlockEntity.transition()` is `offsets != 0`.
- **No view for a lane**: `FluidWorldAuthority`'s presentation host returns at once for a lane position (`p.lane() != 0`): a lane has no screen; its rack draws it from its own copy. A recompiled lane's `drawn` refreshes its rack.
- **Registering again** (a facts loss, D4 reset): `loadedChunk` registers a laid pipe from its block entity's saved `Joints` (`place(…, joints)`) and binds each rack (`PipeRackBlockEntity.bind` → `placeLane` from the copy).
- **Facts schema 2** (`FluidFacts.SCHEMA` 2): a position record appends its `lane` (written only when not 0, so a block's position keeps its schema-1 record); a registration record appends `joints` (default 63), `slots` (default 0, at most 4) and `points` (default 0). A schema-1 record reads as every face, no lane.
</details>

<details><summary>Line paints</summary>

- One line, two paints (owner requests 2026-10-26), each 0xRRGGBB or `Paint.BARE` (−1, `Paint.line` validates): the **primary** paints the pipe, its bends, transition pipes and rack lanes; the **secondary** paints the flanges (joint, spool and blind flanges). Bolts, valves, fittings and the rack frame keep their colours. Bare steel is the default and draws as before paint existed.
- The tool keeps them in `pipe_laying_paint` (absent: bare) and `pipe_laying_secondary` (absent: the primary); the settings screen edits them ([client-gui.md](client-gui.md)), `PipelineToolSave` carries them, `PipelineToolMenu.save` stores a secondary equal to the primary as unset.
- A lay paints every new pipe and lane; the pipes and lanes it starts or ends at keep theirs.
- Drawing: the block tint gives the colour, so a model bakes once per painted flag, not per colour. The painted parts swap to neutral sprites (`painted_pipe`, `painted_flange`, `EquipmentShellModel.paintedLines`; mask 0 keeps the bare sprites) and take tint index base + `Paint.BODY` (pipe) or base + `Paint.STRUCTURE` (flanges) (`EquipmentShellModel.lineTints`); a pipe's base is 0, a rack lane's 2 × option. The ghost shows the primary paint.
</details>

<details><summary>Formats, wire and ids</summary>

- **Pipe block entity** (facts, append-only; `FluidDeviceBlockEntity.writeLine`/`readJoints`/`readPaint`/`readSecondary`): `Joints` (byte, only when not every face; absent or past six faces: every face), `Paint` (int, only when painted; absent: bare; damaged: bare, logged `pipe_compat repair=paint`), `Secondary` (int, only when it differs from `Paint`; absent: the paint; damaged: the paint, logged `pipe_compat repair=secondary`). The update tag adds them and the drawn `Offsets` (long, only when not 0), which is never saved. The registration's joints are the fact; the block's copy is read only to register a block that has none.
- **Rack lane copy, format `rack-3`** (`PipeRackBlockEntity.FORMAT`, a constant, not written into the tag): list `Lanes` of `Identity` (long), `Option`, `Width`, `Arms`, `Joints` (int), `Bore` (double), `Paint`, `Secondary` (`writePaints`: as a pipe's). Read by name and validated (`read`): a damaged lane, or one on an option already read, is dropped and logged `pipe_rack status=DROPPED`, and the ledger's registration restores it at the next `bind`; a damaged paint repairs (`pipe_rack repair=paint`) and keeps the lane. `rack-3` added `Secondary`; a `rack-1` copy reads as it did. The update tag adds `Joined` (int[] per lane) and `Frame` (int).
- **Fluid facts schema 2**: above. The checkpoint envelope 1 and state format 20 are unchanged ([fluid-runtime.md](fluid-runtime.md)).
- **Corpus** (AGENTS.md): `src/test/resources/compat/fluid/schema-2/` (`core.dat`, `pack-1.bin`, `expected.properties`: 9 registrations, 2 vessels, 2 laid pipes, 1 lane); `compat/pipes/pipe-1.snbt` (a hand-placed pipe, laid ones, a painted one), `pipe-2.snbt` (two paints, bare flanges, bare pipe with painted flanges); `compat/racks/rack-1.snbt`, `rack-3.snbt`. Each line is `{Expected:"…", Saved:{…}}`.
- **Wire, registrar `pipeline-tool-2`** (`HandlerThread.MAIN`; was `pipeline-tool-1`):

| Payload id | Direction | Fields | Handler |
|---|---|---|---|
| `icheme:pipeline_tool_save` | C→S | varint menuId, double diameter, int paint, int secondary (each `Paint.line`) | `PipelineToolMenu.save` |
| `icheme:pipe_laying_open` | C→S | bool offHand | `PipelineToolMenu.open` (holding the laying tool) |
| `icheme:pipe_lay` | C→S | bool offHand, `Click` start, `Click` end, ≤ 128 BlockPos cells, ≤ 128 varint slots | `PipeLaying.lay` |
| `icheme:pipe_lay_answer` | S→C | Component message (trusted codec), bool laid | `PipeLayingClient.answered` |
| `icheme:pipe_dismantle` | C→S | bool offHand, `Click` | `PipeLaying.dismantle` |

  `PipeAnchor.Click.STREAM_CODEC`: BlockPos, Direction, three floats (the block-local hit point, clamped to 0..1; a non-finite value reads 0.5). The `PipelineToolMenu` opening buffer: hand, varint slot, `Settings`, int paint, int secondary.
- **Ids**: item `pipe_laying_tool`; block, block item and block entity `pipe_rack`; data components `pipe_laying_settings` (`PipelineToolItem.Settings`), `pipe_laying_paint`, `pipe_laying_secondary` (int, validated). Both items are in the creative tab, after `pipeline_tool`; neither has a recipe (owner: "no need for recipes"). Textures `block/painted_pipe`, `block/painted_flange`, `item/pipe_laying_tool`; `fluid_device.json` names the two painted textures.
</details>

<details><summary>Constants and limits</summary>

| Constant | Value | Where |
|---|---|---|
| Route cells, expansions, search margin | 128, 32 768, 8 blocks | `PipeRoute.Limits.DEFAULT` (also the packet's list caps; message `route.too_long`) |
| Costs | step 1, rack step 0.4, elbow 1.5, vertical 0.3, lane-plain transition 0.5 | `PipeRoute` |
| Start range (server), drop range (client) | 64, 96 blocks | `PipeLaying.START_RANGE`, `PipeLayingClient.DROP_RANGE` |
| Marks | 48 blocks, 256 of each kind; re-plan every 20 ticks, HUD every 5 | `PipeLayingClient` |
| Tier | 4 positions, pitch 3/16 m, first centre 7/32 m, clearance 1/64 m | `RackGeometry` |
| Widest line on a rack | 4 positions: OD ≤ 0.734 m (RD10) | `RackGeometry.MAX_WIDTH`, `largest`, `PhysicalFluidTopology.RACK_WIDTH` |
| Heights (1/32 m) | beam top 13, upper level 26, lower level 6; a four-position line at 16 on a lowered beam (2..13) | `RackGeometry` |
| Options | 14 (12 tier, 2 crossing); lane numbers 1..14 | `RackGeometry.OPTIONS`, `PhysicalFluidTopology.RACK_PLACES` |
| Lane bend radius | at most 7/16 m | `RackGeometry.BEND` |
| Face point grid | 1/32 m steps, two signed five-bit steps per face | `LineGeometry.OFFSET`, `Key.offsets` |
| Spool | every 12 blocks; rack lanes staggered 3 per position | `LineGeometry.SPOOL`, `RackGeometry.spool` |
| Pick tolerance | 1e-3 block | `RackGeometry.PICK_TOLERANCE` |
| Model and frame caches | 4096 keys per model; frame shapes per `Rack` | `PipeRackModel`, `PipeRackBlockEntity.FRAMES` |
</details>

<details><summary>Owner decisions that shape the code</summary>

- **D1**: the laying tool works in survival too: each laid cell, lane included, takes one `fluid_pipe` item, and a dismantle gives one back; free in creative. With no recipe, the tool comes from the creative tab only.
- **D2**: the old pipeline tool stays the creative-only sizing tool (`pipeline_tool`, now named "Pipe Sizing Tool"); the laying tool is a new item. Since 0.15.0 its Shift + use on a pipe resizes the whole line as one event, along rack lanes as the network joins them; a lane narrower than the new bore refuses the line (`PhysicalRegistry.LANE_TOO_NARROW`), and so does a nozzle beside a pipe of the line that takes a smaller pipe (`NOZZLE_TOO_SMALL`; [instrument-accessories.md](instrument-accessories.md), [fluid-runtime.md](fluid-runtime.md)).
- **D3**: hand-placed pipes keep automatic joints (every face).
- **D6, D7**: dismantling is one block (a pipe block or one lane), at once, with no confirmation. A lay, too, changes the blocks at once; the fluid network takes it as one queued event.
- **D9**: a rack that carries lanes is not broken; its pipes come out first, with the tool.
- **D11**: a click on a lane extends it from its open end; a rack carries no junctions.
- **D12** (owner option 1): no off-centre nozzle ports; the delayed coker's off-centre nozzles stay as they are.
- **D13** (open): the pipe spacing rule is unchanged; it can be reviewed now that laid lines never join by accident.
- **Layered rack** (RACK_LAYERS_PLAN option A, RD1-RD11): one tier of four positions per block; lines rest on the beam (RD11 P2); four-position lines on a lowered beam (owner addition); no line over four positions on racks (**RD10, the maximum rack width**); branch-only formats redefined in place (RD9).
- **Junctions** (D-J, X1): the shape is decided once at placement from the feeds and the look; four feeds make a straight rack; sneaking places a plain straight rack. **Facing**: strictly the look (a three-feed tee faces its branch).
- **Sturdy frame** (D-F, all seven parts at the sturdy sizes of RACK_FRAME_PLAN 3a).
- **Flanges** "staggered, off steel"; **residual 1 accepted as drawn** (2026-10-26): flanges of 5" and larger lines may reach the next position's pipe; the pitch and widths stay.
- **Slot choice**: "aim on the tier".
- **Paints**: a primary and a secondary, as a vessel has; bare steel by default.
</details>

<details><summary>Traps</summary>

- **The server never searches.** A change to the search that the server's `check` or `blocked` does not also allow makes the client show routes the server refuses (that was the second-click defect, `43ad8063`). `PipeRouteTest.everyAimedRouteAlongARunIsOneTheServerLays` lays every aimed pair of a run through both.
- **The start stays until the server says laid.** A refused lay keeps it and shows the reason; the client sends one lay at a time (`sent`). A lost answer leaves `sent` set until the tool is put away.
- **A rack's room does not hold the route's own lanes**: the search's `holds` is what stops a route coming back through a cell; do not drop it as redundant.
- **Lane arms are not facts.** The ledger holds a lane's joints and facing; `Lane.arms(joints, facing)` rebuilds the arms. A lane registered with a facing that is not its open arm (or its lowest joint) comes back with other arms.
- **Paints live only on blocks.** The ledger holds no paint: a rack lane adopted from a registration with no copy comes back bare.
- **Drawn state is not saved** (`Joined`, `Frame`, a pipe's `Offsets`): a loaded rack shows `BARE` steel and nothing joined until its first `refresh`/`redraw`; a face in an unloaded chunk keeps what it showed.
- `PipeRackBlock.updateShape` asks `server.execute` for the redraw; on the server thread outside a task that runs at once (see [fluid-runtime.md](fluid-runtime.md), traps).
- `refresh` sends a drawn-only change with `setChanged()`, `redraw` without it (consolidation review, kept: removing it changes when chunks save).
- **A lane has no menu and no view.** The presentation host skips lane positions; a GUI path that assumes one block, one device breaks on a rack (several devices at one block position, lane numbers 1..14).
- **A transition pipe is a plain pipe with an off-centre run**: it refuses fittings, sight glasses, gauges and new branches; a pipe that meets a lane keeps two joints at most. A hand-placed pipe (every face) never meets a lane.
- `FluidDeviceBlock.laying` is a static server-thread flag: blocks placed while it is set register nothing of their own; the lay registered them.
- `RackGeometry.Rack.corner` holds every shape, a tee included; `PipeRackBlock.Shape` is saved by name and mirrors its order, so a new shape goes last in both.
- The rack's frame is open: aiming through it picks the rack only through the tier ray (`aimed`); looking level along the tier, the block hit decides.
- Test worlds from before `8d693dd1` hold racks and facts in the branch's old formats (RD9): use a new world.
</details>

## Change the layer that owns it

| Change | Owning class | Tests that pin it |
|---|---|---|
| What a click anchors to, its refusals and fits | `PipeAnchor.resolve`, `Fit` | `PipeLayingGameTests` (aim resolved on a real rack, the transition refusal; `aColumnNozzleTakesNoLargerPipeThanItsPlaceClears`, the NOZZLE_SIZE refusal); route-side cases in `PipeRouteTest` |
| Route costs, the estimate, determinism, search cost | `PipeRoute.search`, `Estimate` | `PipeRouteTest` (`aClearRunIsStraightAndItsCellsJoinEachOther`, `theClickedFaceSteersTheFirstLegAndTiesAreDeterministic`, `aRouteGoesRoundAnObstacleAndStaysLevelWhenItCan`, `anOpenRouteCostsTheSearchAboutOneNodePerBlock`) |
| Routes on racks: run, side, crossing, rise, corner, tee, width | `PipeRoute` (`entering`, `leaving`, `choose`, `rank`, `towards`), `RackGeometry.fits/open` | `PipeRouteTest` (`aLongRouteFollowsARackOnTheSideItLeavesTowards`, `racksInLineJoinWhateverTheirFacingAndTheLineKeepsItsPlace`, `aCornerTurnsTheRunAndKeepsTheOrder`, `aRouteTurnsFromABranchThroughATeeOrPassesAlongItsRun`, `aLineLeavesOverTheTierWhereALaneLiesBetweenItAndItsSide`, `aLineCrossesARackBesideOnItsCrossingLevel`, `aLineRisesFromARackIntoTheRackStackedOnIt`, `aLineOverTheMaximumWidthIsNotLaidOnRacks`, `aFullRackIsPassedByAndALaneMayNotShareAFaceWithAnother`) |
| Aimed ends, each cell once, one-cell lines | `PipeRoute.End`, `holds`, the approach cell, `ends` | `PipeRouteTest` (`anAimedStartKeepsItsSlotAndLeavesThroughItsFace`, `anAimedEndIsEnteredThroughItsFaceOnItsSlot`, `anAimedStartLeavingAwayComesRoundOutsideItsCell`, `aOneBlockLineOnARackIsTheAimedLane`, `everyAimedRouteAlongARunIsOneTheServerLays`) |
| The server's check | `PipeRoute.check/blocked`, `PipeLaying.lay` | `PipeRouteTest` (`theServerRejectsATamperedRoute`, `theServerChecksEachCellAgainstTheWorldAsTheSearchDoes`, `refusalsNameTheirReason`); `PipeLayingGameTests.aLaidLineJoinsItsTanksOnlyAndComesOutOneBlockAtATime` (a refused lay changes nothing, a gap refused, one item per cell) |
| Lay, dismantle, hosts' joints, items | `PipeLaying`, `FluidWorldAuthority.lay` | `PipeLayingGameTests` (the three lay tests) |
| Slot sizes, heights, levels, clearances, fits | `RackGeometry` | `PipeRackGeometryTest` (`aLineTakesThePositionsItsSizeNeedsAndRestsOnTheBeam`, `crossingLevelsClearTheLinesTheyPass`, `aFlatExitClosesThePositionsBetweenItAndItsSide`, `everyPairOfLanesInACellKeepsItsClearance`) |
| Lanes and steel in the block, flanges off the steel | `RackGeometry.lane`, `steelwork`, `setback`, `spoolAt` | `PipeRackGeometryTest` (`everyLaneStaysInItsBlockAndClearOfTheSteel`, `everyFlangeStandsClearOfTheSteel`, `theSteelStaysInItsBlockAndClearOverTheTierOfTheLinesAtTheSides`, `anOpenLaneEndIsBlindFlangedAndAJoinedOneIsNot`, `theBentsStandWhereTheFrameSays`) |
| Face points, corners, tees | `RackGeometry.point`, `leg`, `legRack`, `room` | `PipeRackGeometryTest` (`lanesMeetWhereTheirPointsCoincideWhateverTheFacing`, `cornerLanesKeepTheirOrderAndMeetTheNextRun`, `aTeeTurnsItsBranchIntoTheRunAndPassesTheRunBehind`) |
| The aimed slot | `RackGeometry.aim`, `PipeAnchor.aimed`, `PipeLayingClient.aimed` | `PipeRackGeometryTest` (`theFaceAndSlotNearestThePointAreAimed`, `aTakenSlotGivesTheNearestFreeOneAndAFullCellTheNearestRefused`, `aCornersArmFaceNearestThePointIsAimed`, `aTeesBranchOrRunFaceIsAimedAndItsLaneSuitsTheLeg`); the client ray: none, dev client |
| A rack's shape at placement, groups, stacking | `RackGeometry.placed`, `steel` | `PipeRackGeometryTest` (one test per J2 row, `noFeedAndNoRackBesideGoesAlongTheLook` … `sneakingPlacesAPlainStraightRackAlongTheLook`; `aGroupPlacedLookingAlongItIsOneStraightRack`, `aTwoWideTurnBuiltRunByRunNestsItsCornersOnTheDiagonal`, `aJunctionsNeighboursStandAWholeBentAgainstIt`, `aStackedRackCarriesTheColumnsUpToTheRackOnIt`) |
| Lane copy format, paints in it, `bind` | `PipeRackBlockEntity` (`write`, `read`, `bind`, `Lane`) | `PipeLineCompatTest` (`aRackRoundTripsItsLanesExactly`, `aDamagedOrRepeatedLaneIsDroppedAndTheOthersKept`, `aLaneSavedBareOrWithADamagedPaintReadsBareAndIsKept`, `aSecondaryPaintAbsentOrDamagedReadsThePaint`, `aLaneFacesItsOpenArmElseItsLowestJoint`, `everyRackCorpusImageReadsToWhatItWasSavedWith`; corpus `compat/racks/`) |
| A rack change reaching the clients | `PipeRackBlockEntity.setLanes`, `refresh`, `redraw` | `PipeLayingGameTests.aOneBlockLaneOnARackIsSentToTheClients` |
| Breaking a rack with lanes | `PipeRackBlock` (`Breaking.refuse` and the overrides) | `PipeLayingGameTests.aLineThroughARackCornerJoinsItsTanksAndALaneComesOutAlone` |
| Pipe joints and paints in the block entity | `FluidDeviceBlockEntity` (`writeLine`, `readJoints`, `readPaint`, `readSecondary`) | `PipeLineCompatTest` (`everyPipeCorpusImageReadsToWhatItWasSavedWith`, `aDamagedValueReadsAsBeforeItExisted`; corpus `compat/pipes/`) |
| Joints, lanes and transition pipes in the topology | `PhysicalFluidTopology` (`Device.joints/slots/points`, `across`, `joins`, `transitionPipe`), `PhysicalRegistry` walks | `PhysicalFluidTopologyTest` (`twoLinesLaidSideBySideStayTwoLines`, `aTeeLaidBetweenTheLinesJoinsThemOnlyWhereBothHaveTheJoint`, `aLaidPipeBesideAHandPlacedOneDoesNotJoinIt`, `onlyALaidPipeHasExplicitJointsAndEveryCopyKeepsThem`, `aLaneJoinsTheSameLaneNextDoorAndAPlainPipeAcrossARackFace`, `parallelLanesInOneRackStayApart`, `aLaneMeetsOnlyATransitionPipe`, `aLaneIsAPlainPipeOfAtMostTwoJointsWithinTheRack`, `jointsRoundTripThroughTheFacts`, `theRegistryWalksFollowTheSameRule`) |
| Facts schema 2 fields | `FluidFacts` (`position`, `registration`) | `FluidFactsCompatTest.everyCorpusImageRebuildsFromItsFactsToItsRecordedTotals` (corpus `compat/fluid/schema-2/`, its `laid` and `lanes` counts), `PhysicalFluidTopologyTest.jointsRoundTripThroughTheFacts` |
| Transition pipe drawing | `LineGeometry` (`transition`, `Key.offsets`), `FluidDeviceBlockEntity.computeFaces` | `LineGeometryTest` (`transitionPipesStayInTheirBlockAndAreWatertight`, `transitionRimsAreTheRimsOfTheLinesTheyMeet`) |
| Ghost, HUD, marks, outlines, the use key | `PipeLayingClient` | none; dev client (software renders in the main checkout's `tools/pipe-laying-renders/`) |
| The assembly marks while the tool is held (the watch, framed ports, the line in the port label) | `PipeLayingClient`, `AssemblyToolClient`, `runtime/assembly/AssemblyWatch` ([machine-assemblies.md](machine-assemblies.md)) | `AssemblyToolWatchTest` (4) |
| Rack model, tints | `PipeRackModel`, `EquipmentClient`, `EquipmentShellModel.lineTints` | none; software renders |
| Paint selector | `PipelineToolScreen`, `PipelineToolMenu.save` | none; dev client ([client-gui.md](client-gui.md)) |

## Tests that pin it

All run under `test` (no task of their own, no slow gate class), except the GameTests, which run under `runFluidGameTestServer`.

- [`science/equipment/PipeRouteTest`](../../../src/test/java/com/wormzjl/icheme/science/equipment/PipeRouteTest.java) (24): straight and steered routes, obstacles, ends jointed to devices, one-cell lines, refusals, tampered routes refused, racks followed on the side the line leaves towards, width limits, full racks, aimed ends, each cell once, every aimed pair of a run laid by the server's check, facings, corners, tees, exits over the tier, crossings, risers into a stacked rack, lane extension, search cost, the server's per-cell check.
- [`science/equipment/PipeRackGeometryTest`](../../../src/test/java/com/wormzjl/icheme/science/equipment/PipeRackGeometryTest.java) (30): sizes and heights, levels and clearances, lanes and flanges clear of the steel and inside the block, face points across facings, corners, tees, blind flanges, bents, the aimed slot (4), the J2 placement table and groups (14).
- [`world/level/block/entity/PipeLineCompatTest`](../../../src/test/java/com/wormzjl/icheme/world/level/block/entity/PipeLineCompatTest.java) (8): the pipe line facts and the rack lane copy, every corpus image under `compat/pipes/` and `compat/racks/`.
- [`runtime/fluid/PhysicalFluidTopologyTest`](../../../src/test/java/com/wormzjl/icheme/runtime/fluid/PhysicalFluidTopologyTest.java) (29, 9 of them on joints and lanes) and `FluidFactsCompatTest` (the schema-2 image); the rigs `EventPathWorld.placeLaid` and `placeLane`.
- `science/equipment/LineGeometryTest` (10, 2 on transition pipes).
- GameTests [`PipeLayingGameTests`](../../../src/fluidGameTest/java/com/wormzjl/icheme/fluid/gametest/PipeLayingGameTests.java) (4, batch `pipe-laying`, survival player): a line between two tanks joins them and not the hand-placed pipe it runs beside, a lay short of pipe items is refused and changes nothing, a dismantled block leaves open ends that a one-block line joins again; a line through three racks placed as a player places them (J2) and turning in the corner one lays transition pipes at both ends and painted lanes in the racks, takes one item per cell, joins the tanks, refuses a tee beside a lane, takes the nearest free slot when the aimed one is taken, keeps the rack from breaking (D9), and one lane comes out alone, is refunded, and a one-cell lane joins its open ends again; a one-block lane on a rack is queued in its chunk's block updates (owner report 2026-10-27); on a 0.5 m column the reflux takes 0.25 m, the anchor refuses a line one step larger (NOZZLE_SIZE) and takes that one, and a pipe in front of it takes no more (`largestBoreBeside`; the column is removed whole with its registration). Each removes the blocks it placed.

Offline material (main checkout, pointers only): `tools/pipe-laying-renders/` (software renders, the `-Dclash` check, `FlangeProbe`), `tools/pipe-laying-ingame/` (the bridge rig, concluded), `tools/rack-slot-clearance/` (superseded), `tools/pipe-laying-system/` (route probe, consolidation digest probe, gate logs). Batch: `documentation/2026-10-26-pipe-laying-system/` (`PIPE_LAYING_PLAN.md`, `RACK_LAYERS_PLAN.md` with the choices I1-I12, `RACK_FRAME_PLAN.md`, `RACK_JUNCTION_PLAN.md`, `RACK_FLANGES_AIM_PLAN.md`, `CONSOLIDATION_REVIEW.md`, `MERGE_REVIEW.md`).

## Terms

- **Anchor**: where a line starts or ends: its end cell, the face joined to what it meets, and the host's edit.
- **Joints**: the faces a pipe connects through; every face for a hand-placed pipe and every other device.
- **Open end**: a pipe's or lane's face that a new line may join: a dead end's blind-flanged face, a joint nothing meets any more, a lane's arm without a joint.
- **Rack cell / lane**: a `pipe_rack` block / one line in it, a laid pipe registered at the rack's position with lane number option + 1.
- **Tier / position / level**: the four positions across the run on the beam / one 3/16 m slot of it / where a lane's side arm leaves (flat, above, below) or a crossing runs (upper, lower).
- **Option**: a lane's place in its cell, 0..13 (tier p + 4 l, crossings 12 and 13); in a tee the level is its leg.
- **Face point**: where a lane meets a face, in 1/32 m steps off the face's centre; two lanes join where theirs coincide.
- **Transition pipe**: a laid plain pipe beside a lane that bends to the lane's off-centre point.
- **Feed**: a neighbouring rack whose run points into a cell; the feeds and the look decide a placed rack's shape.
- **Aimed slot**: the lane a click on a rack's tier picks: its option, the face it passes through to the route and the face it ends open at.
- **Drawn state**: what a rack or pipe draws from its loaded neighbours (joins, steel, offsets): sent in the update tag, never saved.
