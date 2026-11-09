# W5c: registry first for the modules main added (plan, 2026-11-09)

Owner, 2026-11-09: "merge main to here and resolve conflict first, then add support for new modules. Final merge will be when everything's done". The merge is done (`ddfd9cbd3`). This plan applies W5's rule to main 0.20.0-0.23.4: the saved facts decide what exists, and the world is set back to them after a chunk loads. Status: built, C1 to C5 (2026-11-09; owner: "Yes, build W5c", the recommended Q1 to Q4); the history is in [README.md](README.md).

## 1. What each new module saves, and what W5 does for it today

| Module | Facts (where) | Block | Today, block lost |
|---|---|---|---|
| Shaft, creative motor, Create coupling | shaft units, fluid facts schema 10 (`ShaftUnit`: kind, cell, axis, ends) | `ShaftBlock` (axis; size is drawn from the train), `CreativeMotorBlock` (facing = its end), `CreateCouplingBlock` | Unit stays, train turns through a ghost |
| Motor, generator, gearbox, turbine, casing | W5 structure record + shaft unit | shell cells | W5 rebuilds it; `restoreFluidBinding` re-queues the unit (done) |
| Data and FE cable | `ControlFacts` (`icheme_control`): `DataBus.Cable` by site (cell, lane), geometry with medium, endpoints | `ConduitBlock` (DATA or POWER) or a rack lane | Fact stays, bus joins a ghost (drawn connections, finding 2) |
| Local controller, DCS | `ControlFacts.Panel` (id, cell, dcs, settings) and its socket `DataPort` (face) | `ControlPanelBlock` (facing), `DcsBlock` (two cells) | Fact stays, controller acts with no block |
| Line pieces | derived from cable and port facts | `LinePieceBlock` | Already registry first (`LinePieces.flush`) |
| Device names (N1) | block NBT only (`FluidDeviceBlockEntity`, every shell cell) | - | Name lost with the block |
| Process simulator, creative power | block NBT / none | - | Out of scope (below) |

## 2. Expected behaviour

The registry is the fact, and the block draws it, as W5 does for fluid devices:

- A part whose cell is free gets its block back, oriented from its facts.
- A part whose cell another block holds is held, and the status line and `/icheme devices held` name it. Its block is never overwritten.
- A held part is absent to its network, because nothing physical stands there to carry it. A train splits at a held shaft. A bus is cut at a held cable. A held controller does not act.

## 3. Work packages

- **C1. Shaft parts.** Index the shaft units by chunk, as `structureChunks` indexes structures, and queue them at `loadedChunk`. Check them with the same budget as W5 (32 checks and 1 ms a tick).
  - SHAFT: a `ShaftBlock` on the unit's axis.
  - CREATIVE_MOTOR: a `CreativeMotorBlock` facing its end.
  - CREATE_COUPLING: a coupling block when Create is loaded; otherwise held.
  - Structure kinds: left to W5's structure path.
  - Held: `ShaftTrains` leaves the part out of its train (option Q1).
- **C2. Cables.** A cable index by chunk in `ControlService`.
  - Lane 0: a `ConduitBlock` of the fact's medium, configured from the fact (`configure`). Its line pieces are queued.
  - A conduit block of the other medium is replaced quietly. `ConduitBlock.onRemove` follows `FluidDeviceBlock.quietly`.
  - Rack lanes: W5's lane path also places the rack for a cell that holds only cable lanes, with the frame the facts hold (option Q2). `controls().loaded(rack)` then restores the lanes.
  - Held: the bus leaves the cable out.
- **C3. Controllers.** A panel index by chunk.
  - A `ControlPanelBlock`, or both cells of a `DcsBlock`, facing its socket's `face` (the socket leaves the block's back, `DataPorts.panel`, and `DataPort.face` is the cable's face toward it; corrected at the build, 2026-11-09). No new field is needed.
  - `ControlPanelBlock.onRemove` follows the quiet flag.
  - Held: the controller is off until its block stands.
- **C4. Device names.** Schema 15 is not on main yet, so names join W5's build facts there and need no new schema:
  - a name per one-block device identity (`Builds.names`), and the structure record's name;
  - a named block whose registration holds no name teaches it at bind, as paint does;
  - a re-placed pipe or a rebuilt structure takes its name.
  - The schema-15 corpus image is written again.
- **C5. Notices.** `HeldDevicesCommand` and the operator's join message count held shafts, cables and controllers with the fluid devices.

## 4. Owner decisions

- **Q1. A held part is absent to its network** (recommended: one rule for all four networks, as W5's S11).
  - Alternative: notice only, the network unchanged.
- **Q2. Rack frames for cable lanes:** an append-only frame field on `ControlFacts` cables (format 3 to 4, corpus image `compat/control/control-4.snbt`). Old facts derive the frame from the lane points, as W5's `derivedFrame` does (recommended).
  - Alternative: always derive it; a rack whose points leave the shape open may come back straight.
- **Q3. Names in schema 15** (recommended), or a later schema.
- **Q4. Out of scope:**
  - The process simulator block: the owner gave simulator sheets no save compatibility (2026-11-04, PORT_SIDES_REVIEW: "no need"). They follow the column calculator block's rule.
  - The creative power block: creative only, no fact.
  - Learning a standalone shaft block at load: today it registers when placed. A44 already adopts the shafts placed before W3, and a load scan of block states costs a section scan per chunk.

## 5. Tests

- Unit tests: the ledger and the facts round trip with names (`WorldTopologyLedgerTest`, `FluidFactsCompatTest`, image 15 again). With Q2, the `ControlFacts` compat test reads `control-1` to `control-4`.
- GameTests, batch `registry-first-modules`:
  - k: a lost shaft line comes back, and the train turns whole again.
  - l: a lost data cable comes back with its endpoints and line pieces. A stone in a cable cell holds it and cuts the bus.
  - m: a lost controller and DCS come back facing their sockets.
  - n: a stone in a shaft cell holds the shaft, the train splits, and clearing the stone releases it.
  - o: a re-placed named pipe and a rebuilt named drum keep their names.
- Then the fluid GameTests whole (W5 runs at every chunk load), on a fresh run id.

## 6. Order

C4 first (one more schema-15 change, while the image is new), then C1, C2, C3, C5. Each package is one commit with its tests. The batch's merge to main follows once all of them pass.
