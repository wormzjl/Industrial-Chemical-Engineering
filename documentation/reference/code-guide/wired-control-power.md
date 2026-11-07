# Wired controllers, data links and FE power

**Snapshot:** 3515b1ff (branch `codex/simulator-sizing`), 2026-11-07. Batch `2026-11-06-simulator-sizing`, prepared for 0.22.0; not merged.

Local controller boxes own automatic loops. The DCS selects a linked box and edits its settings; it does not run a second controller. Data cable carries connectivity rather than fluid or a packet stream simulation. Power conduit transports ordinary NeoForge FE through capabilities. Both use the pipe laying tool's anchors, routes, validation and rack framework.

## Class map

| Owner | Responsibility |
|---|---|
| `runtime/control/ControlFacts` | Saved cables, sockets, panels/settings and stable sensor IDs; format 3 |
| `DataPort`, `DataPorts`, `SensorId` | Socket identities, eligible host registration/remounting and human sensor labels |
| `DataBus` | Immutable compiled connectivity from face/lane cable facts, filtered by medium |
| `ControlService`, `ControlSettings` | Server-owned edits, cadence, loop plans, access/revision checks and requested views |
| `InventoryControl` | Volume-balance inversion for level control; attainable flow still belongs to hydraulics |
| `runtime/power/PowerNetwork`, `PowerPorts` | Server-thread-owned topology refresh, pending hosts, capability resolution and live-source checks |
| `PowerGrid` | Compiled circuits grouped by owner, FE transfer/extraction and read-only source availability |
| `CableMedium`, `ConduitGeometry` | Shared 0.125 m steel housings, blue data/orange power identity, sparse symbols and closed ends |
| `ConduitLaying`, `ConduitBlockEntity`, rack lanes | Structural placement adapter, saved joint/socket endpoints and world/model updates |
| `ControlNetwork`, controller/DCS menus/screens | Protocol `control-2`, subscriptions, paged ID choices and acknowledgements |

## Control path

1. Immediate accessory installation registers the host/sensor. `ControlFacts` assigns a stable human ID backed by the sensor UUID. A fitting install does not force a process step.
2. Shared laying creates cable joint/face/lane facts and explicit endpoint bindings. Topology revision rebuilds the data bus; it is not searched afresh for every reading.
3. At each 100-online-tick cadence, `ControlService` validates controller settings and data reachability, reads accepted observations and creates immutable valve/heat plans. The saved equipment settings remain underneath them.
4. Flow control scales the native flow target; pressure control compensates the measured intervening drop for the native upstream pressure valve and requires outlet action; level derives the balancing flow from volume change and requests correction over one 5 s cadence; temperature uses the same heat device's native outlet control.
5. Missing links/signals and conflicting outputs report explicit status. A controller cannot reserve an output already owned by another loop or a column. Limits and conservation remain enforced by native process laws.
6. DCS/controller screens replay cached requested state and subscribe to scheduled publication. Closing ends the subscription. Sensor selection is ID-based, with paged choices; location is explanatory only.

This is ideal game-level regulation with feed-forward, not a tuned PID or a promise to remove hydraulic/thermal response time. Sensor applicability is listed in [instrument-accessories.md](instrument-accessories.md).

## FE path

`PowerNetwork` confines mutable hosts, topology and capabilities to the server thread. Immutable wiring compiles through `DataBus` in POWER mode; `PowerGrid` groups sockets by equipment owner to avoid self-transfer and repeated scans. Each operation resolves the current capability so chunk unloads and changed sources are visible. Circuits with no available source skip sink probes. A recursion guard prevents reentrant transfer.

The creative RF source exposes extract-only standard FE on all six faces. Its original saved socket identity survives; additional face identities are deterministic. Motors, generators, inline movers and fan equipment expose their relevant capabilities on physical service mounts. Data and FE ports use separate motor sides and casing/cabinet locations, centred when clearance permits. An off-centre route endpoint is preserved precisely by the common laying path.

Motors require usable stored energy and live FE supply. Their buffer is 10 s of rated energy with a 5 s restart threshold; older saved joules survive even above the reduced capacity. A source disconnect stops drive at the next scheduled process decision. Read-only simulated source probes and a one-tick FE-offer tolerance handle capability ordering without creating energy. Ordinary FE transfers may happen per tick; process calculation does not.

## Persistence, checks and change boundaries

`ControlFacts` formats 1–3 and accessory facts schema 4 have corpus images; missing old conduit medium means the original data medium. Sockets/endpoint IDs, settings and cable positions are facts; compiled graphs, loop samples, pending edits and caches rebuild. Damaged facts refuse load. Existing wired endpoints are retained across visual/layout changes rather than silently reattached.

`ControlSystemTest`, `PowerGridTest`, `ConduitGeometryTest`, layout/compatibility tests and the standard-namespace `ConduitLayingGameTests`/`CreativePowerGameTests` cover the core paths. Historical CPU/RAM and draw probes live in `tools/simulator-sizing/`; the batch merge review records final verification. Change routing in the common line classes, process laws in science, capability transfer in power, and scheduling/ID policy in control. Do not add another parallel placement engine.
