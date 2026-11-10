# Wired controllers, data links and FE power

**Snapshot:** 971dd8346 (branch `claude/custom-shader-system-caff9e`), 2026-11-10. Batch `2026-11-10-chimney`, to merge as 0.25.0: a chimney's built-in inputs and alarms (`BuiltInInputs`, `EquipmentAlarm.CHIMNEY`) and its junction box (`EquipmentServices`); `ControlFacts` unchanged · earlier: 7be61a4b9 (branch `claude/furnace-fuel-power-requirements-f7a46e`, main 0.23.6 merged in), 2026-11-10. Batches `2026-11-09-furnace-fuel-power`, `2026-11-09-flare-stack` and `2026-11-09-batch-distiller`, merged as 0.24.0 on 2026-11-10: the fired heaters' fan power (`PowerPorts`, `TerminalEnergy`, electric store kind `BURNERS`), the electric still's terminal box, alarm channels (`DataPorts`, `SensorId`) · earlier: 2f86539b0 (branch `claude/save-corruption-prevention-2bd7ac`, main 0.23.4 merged in), 2026-11-09. Batch `2026-11-03-save-corruption`, merged as 0.23.5 on 2026-11-09: registry first for cables and controllers (W5c C2, C3): `ControlFacts` format 4 (a rack lane's frame), lost cable blocks, racks, controllers and consoles placed back, held cables cut out of their bus, held controllers off · earlier: e4749cdb6 (branch `claude/simulator-sizing-refactor-6bacab`, the consolidation review of `codex/simulator-sizing`), 2026-11-08. Batch `2026-11-06-simulator-sizing`, merged as 0.22.0 on 2026-11-08.

## The chimney's data channels (0.25.0)

- `BuiltInInputs`: a chimney's `flow_rate` (`CHIMNEY:FLOW`, kg/s), `solids_rate` (`CHIMNEY:SOLIDS`) and `venting` (`CHIMNEY:VENTING`, 1 from `Chimney.NO_FLOW` up), read from `Chimneys.view` at the online tick; its alarms `alarm_over_capacity` and `alarm_no_draft` (`EquipmentAlarm.CHIMNEY`).
- `EquipmentServices`: its junction box set into the foot's side (a steel stack's plinth, a brick chimney's shell), cover flush, gland on the bottom block's centre.

## Fan power, the still's terminal and alarm channels (0.24.0)

- A fired heater's terminal cell feeds its burners' electric store (`ElectricStores` kind `BURNERS`, `PowerPorts`, `TerminalEnergy`): without power the burners trip (NO POWER) and light again once the store refills.
- The electric still's terminal box feeds its heater: its FE socket faces the back, its data socket the front (`EquipmentServices`).
- `DataPorts` and `SensorId` carry the alarm channels and the diverter's actuator and data gland.

Local controller boxes own automatic loops. The DCS selects a linked box and edits its settings; it does not run a second controller. Data cable carries connectivity rather than fluid or a packet stream simulation. Power conduit transports ordinary NeoForge FE through capabilities. Both use the pipe laying tool's anchors, routes, validation and rack framework.

Controller, DCS and sealed sensors share neutral metal, dark bezels, blue displays and amber ID trim with the constructor/simulator; `tools/pump-sprites/ControlAssets.java` owns those resources.

Operator choices use `DataBus.hosts` for the selected controller, including when viewed through DCS. A replay cache with an obsolete topology revision is skipped until scheduled publication; enabled edits require both links. Dormant disconnected selections remain facts without displaying an unconnected instrument ID. Local loop outputs are control valves and controllable heat units; mover demand control remains the motor path.

The form calls the direction selector Valve location, shows it only for level/pressure, and explains inlet fill versus outlet drain. New level picks default to HEIGHT_PERCENT; old settings keep their unit/action. To hold 25%, select the linked LT and CV, choose %, enter 25, select the valve’s location, switch On and Apply. Percent refers to height span. Device/machine pages list static sensor IDs and type labels, plus the CV label on a valve page.

## Physical interfaces and accepted channel catalog (0.22.0)

`EquipmentServices` owns supported enclosure solids and physical socket datums; `SocketGeometry` (the data and FE socket datums and their one gland mesh), `DataPorts` (`at`, `fittingKey`) and `PowerPorts` adapt that plan. Inline movers and the pneumatic control-valve positioner expose integrated side entries. One physical C1 represents a multi-registration heat unit; secondary fluid registrations alias to its primary host. Columns reserve a control identity in the world's existing namespace and reuse it on reload. Saved wired face points remain facts.

`ControlInput` represents either an installed sensor UUID or a deterministic equipment/channel UUID. `BuiltInInputs.Binding` maps the physical host, shape/origin and fluid registrations. Movers expose inlet/outlet P/T, flow and speed; motors/generators expose speed. Heat units expose accepted process/utility port P/T and exchanger flow channels. Cooling towers expose inlet/outlet P/T and liquid-plus-solid basin level. Columns expose top/bottom P/T, reflux P/T/flow and native/external stream channels; cached assembly members add their accepted port/utility channels through C1. Unsupported air properties and V3's unmodelled circulating pumparound streams remain unavailable.

`ControlService` refreshes the cached assembly-member catalog before validating edits. It reads only accepted `FluidView`/pipe observations and column publications, for requested or enabled-loop hosts, on the 100-tick cadence. The column stores a bounded `ColumnUnit.Probes` state snapshot alongside its table; internal mass flows scale with accepted total feed, including feed water. Reload preserves these readings without a GUI-triggered solve; older rebuildable state gets its normal scheduled refresh.

`DcsGeometry.localParts` supplies the local cabinet model-authoring/collision bounds; its recessed rear entry is not blocked by a full-cube collision. `HorizontalShapes` turns north-facing boxes into the four facings' collision shapes of the local controller, both DCS halves, the simulator console and the constructor bench; `DcsGeometry.turned` is the same quarter turn in closed form. Both cabinets union derived lead geometry into their actual shape. DATA/POWER item IDs now register BlockItems and direct placement enters `LineLaying.handPlaced`.

The DCS is a two-block console (`DcsBlock`, `DcsGeometry`), one lower-half panel identity/menu/socket. Placement and removal treat both halves together; a legacy one-block console waits for room without moving saved identity or endpoints. Protocols: controller `control-3`, shared commands `lines-1`, tool settings `pipeline-tool-5`. Screens preserve the player's GUI scale and scroll/page within their viewport. Hover shows sensor block coordinates and attached EQ identity, with no dimension identifier.


## Class map

| Owner | Responsibility |
|---|---|
| `runtime/control/ControlFacts` | Saved cables, sockets, panels/settings and stable sensor IDs; format 4 (a rack lane's frame); the cable chunk index (`cablesIn`), held cables and controllers (`hold`, `holdPanel`: engine state, never saved), the standing cables the buses join (`standing`) |
| `DataPort`, `DataPorts`, `SensorId` | Socket identities, eligible host registration/remounting and human sensor labels |
| `DataBus` | Immutable compiled connectivity from face/lane cable facts, filtered by medium; `Cable.frame`, a rack lane's rack cell frame as laid |
| `ControlService`, `ControlSettings`, `ControlInput`, `BuiltInInputs` | Server-owned edits, cadence and access/revision checks; `ControlService` is the facade the world, block entities and menus call |
| `ControlHosts`, `ControlLaw`, `ControlPlans`, `ControlPublication` | Package-private parts of `ControlService`: host bindings, aliases and wiring queries; one loop's decision; the valve/heat plans a slice takes; watched menus, replay cache and snapshots |
| `InventoryControl` | Volume-balance inversion for level control; attainable flow still belongs to hydraulics |
| `runtime/power/PowerNetwork`, `PowerPorts` | Server-thread-owned topology refresh, pending hosts, capability resolution and live-source checks |
| `PowerGrid` | Compiled circuits grouped by owner, FE transfer/extraction and read-only source availability |
| `CableMedium`, `ConduitGeometry` | Shared 0.125 m steel housings, blue data/orange power identity, sparse symbols and closed ends |
| `ConduitLineBackend`, `ConduitBlockEntity`, rack lanes | Structural placement adapter, saved joint/socket endpoints and world/model updates |
| `ControlNetwork`, controller/DCS menus/screens | Protocol `control-3`, subscriptions, paged ID choices and acknowledgements |

## Control path

1. Immediate accessory installation registers the host/sensor. `ControlFacts` assigns a stable human ID backed by the sensor UUID. A fitting install does not force a process step.
2. Shared laying creates cable joint/face/lane facts and explicit endpoint bindings. Topology revision rebuilds the data bus; it is not searched afresh for every reading.
3. At each 100-online-tick cadence, `ControlService` validates controller settings and data reachability, reads accepted observations and creates immutable valve/heat plans (`ControlLaw` decides each loop, `ControlPlans` keeps the plans). The saved equipment settings remain underneath them.
4. Flow control scales the native flow target; pressure control compensates the measured intervening drop for the native upstream pressure valve and requires outlet action; level derives the balancing flow from volume change and requests correction over one 5 s cadence; temperature uses the same heat device's native outlet control.
5. Missing links/signals and conflicting outputs report explicit status. A controller cannot reserve an output already owned by another loop or a column. Limits and conservation remain enforced by native process laws.
6. DCS/controller screens replay cached requested state and subscribe to scheduled publication. Closing ends the subscription. Sensor selection is ID-based, with paged choices; location is explanatory only.

This is ideal game-level regulation with feed-forward, not a tuned PID or a promise to remove hydraulic/thermal response time. Sensor applicability is listed in [instrument-accessories.md](instrument-accessories.md).

## FE path

`PowerNetwork` confines mutable hosts, topology and capabilities to the server thread. Immutable wiring compiles through `DataBus` in POWER mode; `PowerGrid` groups sockets by equipment owner to avoid self-transfer and repeated scans. Each operation resolves the current capability so chunk unloads and changed sources are visible. Circuits with no available source skip sink probes. A recursion guard prevents reentrant transfer.

The creative RF source exposes extract-only standard FE on all six faces. Its original saved socket identity survives; additional face identities are deterministic. Motors, generators, inline movers and fan equipment expose their relevant capabilities on physical service mounts. Data and FE ports use separate motor sides and casing/cabinet locations, centred when clearance permits. An off-centre route endpoint is preserved precisely by the common laying path.

Motors require usable stored energy and live FE supply. Their buffer is 10 s of rated energy with a 5 s restart threshold; older saved joules survive even above the reduced capacity. A source disconnect stops drive at the next scheduled process decision. Read-only simulated source probes and a one-tick FE-offer tolerance handle capability ordering without creating energy. Ordinary FE transfers may happen per tick; process calculation does not.

## Persistence, checks and change boundaries

`ControlFacts` formats 1–4 and accessory facts schema 4 have corpus images (format 4: `compat/control/control-4.snbt`); missing old conduit medium means the original data medium. Sockets/endpoint IDs, settings and cable positions are facts; compiled graphs, loop samples, pending edits and caches rebuild. Damaged facts refuse load. Existing wired endpoints are retained across visual/layout changes rather than silently reattached.

Registry first (batch 2026-11-03-save-corruption, W5c C2 and C3; [fluid-runtime.md](fluid-runtime.md)). Format 4 appends a rack lane's frame (`DataBus.Cable.frame`, default none): `ConduitLineBackend.register` records it at the lay, a standing rack teaches it to lanes saved before (`ControlService.loaded(rack)`), and a frame in an older format refuses the facts. A chunk load queues its cables and controllers (`ControlService.loadedChunk`). The re-alignment (`RegistryRealignment.cable`, `panel`) places a lost cable block of the fact's medium, whose copy takes the fact with its endpoints and line pieces at its load; replaces one of the other medium; places a lost rack with the frame its lanes hold, or one derived from them; and places a lost controller or DCS console facing its socket's `face` (the cable's face toward it; the socket leaves the block's back, `DataPorts.panel`), a console with its top where the cell above is free. A cell another block holds holds the part (option Q1): the data and FE buses join only `ControlFacts.standing()` (`ControlHosts.bus`, `PowerNetwork`), and a held controller neither reads nor acts, its status HELD, so its output resumes the device's own settings. Holds are not saved: a load checks them again when their chunk loads. `ConduitBlock.onRemove` and `ControlPanelBlock.onRemove` remove no fact while `FluidDeviceBlock.quiet()`.

`ControlSystemTest` (the corpus images; a frame before format 4 refused; held cables and controllers never saved), `PowerGridTest`, `ConduitGeometryTest`, layout/compatibility tests and the standard-namespace `ConduitLayingGameTests`/`CreativePowerGameTests` cover the core paths. `RegistryFirstGameTests` l and m set back a lost data cable and a lost controller and console, and hold a blocked one. `ControlLoopGameTests` drives one local flow loop end to end: an edit through the controller's menu, the cadence's decision, the valve plan on the slice (the saved valve law unchanged) and the publication to the open menu. Historical CPU/RAM and draw probes live in `tools/simulator-sizing/`; the batch merge review records final verification. Change routing in the common line classes, process laws in science, capability transfer in power, and scheduling/ID policy in control. Do not add another parallel placement engine.
