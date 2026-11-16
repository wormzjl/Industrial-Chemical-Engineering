# Client: device, heat, machine, dialog and controller screens

Up: [code guide](README.md). The kit these screens are built on: [client-kit.md](client-kit.md). The constructor, datasheet, assembly and column-door screens: [client-equipment-screens.md](client-equipment-screens.md). The server side of the fluid menu: [runtime-presentation.md](runtime-presentation.md); the heat control's physics: [heat-exchangers.md](heat-exchangers.md); machines: [shaft-trains.md](shaft-trains.md); controllers and the DCS: [wired-control-power.md](wired-control-power.md). GUI rules: the `gui-creation` skill ([SKILL.md](../../../.claude/skills/gui-creation/SKILL.md)).

## Read this when

- You change what a player sees or types on a fluid device's screen (pump, valve, pipe, tank, generator, filter, void, compressor, a constructed mover, a heat unit's side, a still's pot, a converter, a chimney, a flare, a drum's accessories), a machine's own page, a gauge's or the pipe tools' settings, or a controller's or the DCS's screen.
- You change a heat unit's Control tab (its drawing, switch, targets, set points), the Overview's stream switch or a pipe's line reading.
- A device screen shows a wrong number or status word, loses typed text, or does not refresh after Apply.
- A status line shows "outside data range", "HELD (phases)", `LOW LIMIT` or `DUTY INSUFFICIENT`, or the Solids tab lists a crystal such as "Carbon dioxide (solid)".

## What it does

A fluid device screen shows what the engine last published for one device: switches, set points and meters on the door column, phases and tables on the glass, and its status said once. It sends inputs as one bounded `FluidNetwork.Controls` record with Apply (or a unit's own payload), and the answer comes back on the engine's schedule, about every 5 s of online time; the screen never computes process state (AGENTS.md, "Engine schedule"). Typed text lives in drafts outside the widgets, which parse to SI and build the exact record Apply sends, so tests pin it without Minecraft. Minecraft-free builders lay out each door: `DeviceColumn` (every page but Control), `HeatControlColumn` (the Control tab), `MachineColumn` (a machine's page), `DialogColumns` (gauge, pipe tools, datasheet), `ConverterDoor` and `StillDoor` (rows a device column leads with), `ControlColumns` (controllers and the DCS). `DeviceMeters` builds the meters' values; `HeatDiagram` and `HeatFlowsheet` draw a heat unit's flowsheet.

## Class map

Paths under [`src/main/java/com/wormzjl/icheme/client/gui/`](../../../src/main/java/com/wormzjl/icheme/client/gui/). Lines at the snapshot.

| Class | Role | Lines |
|---|---|---|
| **Screens (`screens/inventory`)** | | |
| `FluidDeviceScreen` | A fluid device's screen on its menu: pages (`Page`: Overview, Composition, Solids, Connections, Outlets, Datasheet, Control, Accessory), one `KitDoor`, the status words (`statusText`, `clauseText`, `controlStatus`), drafts reloaded on a new input revision, Apply (`deviceInput`, `controlAction`, `heatControl`), the generator's mixtures (`loadMixture`), the Solids rows (`shownSolids`, `solidName`), the heat drawing's values (`heatValue`, `partState`, `streams`), the accessory glass (`renderAccessory`), a converter's (`converter`, `converterInput`, `converterApply`) | 1251 |
| `ConverterDrafts` | A fluid converter's drafts: the Mode as drafted, rate, deadband, alarm levels, refreshed from the published view | 48 |
| `StillDrafts` | A batch still's drafts: the Firing switch and the Reflux valve, firing and ratio | 39 |
| `MachineScreen` | A machine's own page on `MachineMenu` in the device frame (`FluidGuiLayout`): title strip with its own name entry, one tab, message display, `MachineColumn` door, the glass (with `MotorCurve` for a motor); shows the session's last `MachineView`, Apply and Reset send `MachineNetwork.EditPayload` | 400 |
| `GaugeScreen` | A gauge item's settings (`DialogColumns.gauge`): range endpoints through `NumericDraft`, unit selector (`GaugeSettings.Unit`), a pressure gauge's gauge or absolute reading; Save sends `FluidNetwork.saveGauge` | 225 |
| `PipelineToolScreen` | Both pipe tools' settings on `PipelineToolMenu` (`DialogColumns.pipelineTool`): the bore; for the laying tool the two paints (channel selector, palette of `PaintSwatch`, custom RRGGBB, Bare steel); Save sends `PipelineToolSave`; data from the opening menu only | 256 |
| `ControlPanelScreen` | A local controller's or the DCS's screen: loops, sensors and valves by id, the Converters and Columns groups and a column's page (`columnDrafts`), the Alarms group; drafts survive publications; only Apply (or a mode key) queues a server input (`ControlNetwork`) | 839 |
| **Builders and drawings (`common`, no Minecraft classes)** | | |
| `DeviceColumn` | The door column on every page but Control: first the groups with widgets (side selector, Control, Set point, Limit, a pipe's Size, a filter's Solids, Phases with its basis key), then the groups that follow the plant (Level, Valve, Readings, Drive, Power, Suction, Health); `accessories`, `blanketValve`, `reliefValve`; `instrumentIds`; `WATER_LABEL_SHARE`; `phaseKey` (the aqueous liquid's "water", one dense fluid's "dense") | 352 |
| `DeviceMeters` | What the door column reads, from the view (`Data`), the stream shown, the phase basis (`PhaseRatioBasis`) and the player's units: `meters`, `phases`, `level`, `suction`, `drive`, `power`; `denseRow` and `withDense` (the dense label of the shown contents) | 335 |
| `DenseLabel` | One dense fluid above reduced density 0.5 reads "Dense fluid" (S5 of batch 2026-11-15-supercritical-fluids): `dense` and `row` on a model's state; `row` on a view (one cubic root on the client's catalog, today's label where it cannot say); `model`, the client's network model, built once per catalog | 113 |
| `ConverterDoor` | A converter's groups: Mode (Off, Balance, To IChemE, To Forge), Rate, Alarm levels, Tanks, Rule, Converting; also on the DCS's converter page; `plant` alone (Forge tank, rule, booked rate) is a liquid hopper's page | 132 |
| `StillDoor` | A still's groups on its pot's door: Firing, the Reflux valve, Fuel, a controller's hold, the readings | 90 |
| `HeatControlColumn` | The Control tab's column from `FluidView.Heat`, the applied `HeatUnit.Control` and the drafts: side selector, Control, Target (`targets`), Set point or By hand, low limit, the actuator group named by the server, Check lines; `columns` (layout C) or `rows`; `columnTexts`; its `Texts` interface | 258 |
| `HeatDiagram` | A heat unit's flowsheet on a `WIDTH` by `HEIGHT` design grid: body glyph, nozzle squares, stream pills, an exchanger's line parts, a column condenser's flow station; each line a `Value` read from the view; `roles`; lang keys under `KEY` | 380 |
| `HeatFlowsheet` | Draws a `HeatDiagram` through `FlowsheetPainter` scaled to its area; `part` and `pill` hit tests | 358 |
| `MachineColumn` | A machine page's door: a motor, a steam turbine's drive, a generator, a gearbox, a creative motor, a prime mover or a Create coupling (read only); widgets first (Off/On, drive, regulation and set point, limit or ratio, Trip reset), then the plant's readings from `MachineView` | 283 |
| `MotorCurve` | The motor's finite VFD envelope on the machine page's glass | 72 |
| `PumpCurve` | A constructed centrifugal pump's curve on its Overview glass: H(Q) at the present speed, NPSH3(Q), the inception curve (dashed), NPSH available, the operating point; from the casing's `FluidView.Curve` and the view | 201 |
| `DialogColumns` | The small dialogs' doors: `gauge`, `pipelineTool` (with `palette`), `vessel` (the datasheet's Size group and motion switch); `footer`; the datasheet's `Sheet` and its `glass` (warning, notes, the nozzle table, `BAND`), `material` (its material line), `TOP` | 279 |
| `ControlColumns` | The controller and DCS operator form, shared with the offline renderer: viewport groups, selection (`choices`), hover texts, `Layout` | 252 |

Client-side packet entry points live in server-owned classes: `FluidNetwork` (`sendEdit`, `recoverSolids`, `openPartner`, `checkLines`, `remember`, `lastDelivered`, `forgetDelivered`, `saveGauge`, `savePipelineTool`, `requestTanks`, `requestInstruments`, `ConverterEditPayload`, `MenuData`), `FluidDeviceMenu` (`acceptStatic`, `acceptLive`), `MachineNetwork`, `ControlNetwork`.

## Main flow

Open, update, Apply and close of a fluid device screen:

1. Server: the `FluidDeviceMenu` constructor subscribes the menu to its device's presentation bucket; the block entity calls `replayOnOpen`, which replays the cached snapshot. No solver runs.
2. Client: the menu starts from `FluidNetwork.lastDelivered(identity)` (the view this session last received, LRU of 32); with none and no snapshot yet, the screen says it waits for the next simulation update.
3. `acceptStatic` stores the per-revision part (kind, component axis, presets, names, weights); `acceptLive` joins the per-bucket part (view, controls, reply) into a `MenuData` and validates it (`requireCrystalAxis`). A live payload before any static one is ignored.
4. Each frame `refresh` reads the menu's data; drafts reload from the controls only when `view.inputRevision()` changes; a new reply is detected by its message revision. The door is placed again each frame from the view, so meters follow the plant while widgets keep their slots.
5. Apply builds `FluidNetwork.Controls` with the revision and sends it (`sendEdit`); a heat side's Apply carries `heatControl` and shows only on its Control tab; Recover solids sends a recover payload, Check lines a lines payload, the side selector a partner payload (the server opens the other side's menu). The message display shows waiting until the engine's reply ("Queued…", "Applied", "Not applied: …") arrives with a later bucket.
6. Closing calls `removed`, which unsubscribes. Logging out clears the delivered-view cache.

A machine page: the server opens it (`MachineNetwork.hasPage`; a casing's Drive group opens its driver's), replays the last `MachineView`, and answers Apply or Reset on the next bucket. A controller or DCS screen reads the console's publication; a mode key, Start, Stop and Apply queue `ControlNetwork` inputs, answered on its schedule.

## Contracts

- **The door column, in order** (`DeviceColumn`): a heat side's side selector; Control (Off, On) for a generator or a constructed mover; Set point (a flow valve's target, mass or volume flow, above its entry), Limit, a pipe's Size; a filter's Solids (Recover solids); Phases (a basis key over a split bar of organic, aqueous, vapour and solid shares); a tank's Level; a valve's Valve (opening and the controller's words); Readings, one LED meter a value (`DeviceMeters.meters`), a junction's line first, then temperature and pressure (a tank's top and bottom pressure and mass; a mover's suction, a valve's or heat side's inlet), throughput, a pipe's velocity and gradient, a filter's load, the Δp (a compressor with a ratio reads the ratio), a heat side's outlet, duty, bypass, fans, set temperature and available duty, the sensors; a casing's Drive, a fan unit's Power, a pump's Suction and Health.
- **One dense fluid** (`DenseLabel`): the view carries no phase traits (no wire field), so the screen evaluates the shown contents' one root on the client's own catalog once per published view and stream (`FluidDeviceScreen.denseRow`, kept until the next). Above reduced density 0.5 the tab and legend of its row read "Dense" (`DeviceColumn.phaseKey`), a tank's level line "No interface", a pump's gas meter its head share `GasEntrainment.headShare(α, p)` on the server's α and the suction pressure. A compound the client lacks keeps today's words. A dedicated server's datapack override is not on the client: the label follows the client's data.
- **Pages** (`Page`): Overview (all; a heat unit's stream switch first, then the phase tabs and the component table in the shown basis and flow time; a pump's curve), Composition and Solids (an editable generator), Connections (a pipe with more than one), Outlets (a tank), Datasheet (a structure with a fluid device: `VesselScreen.sheet`'s glass, [client-equipment-screens.md](client-equipment-screens.md)), Control (a heat side; the page last chosen on a heat unit opens first this session), Accessory (a drum with a blanketing or safety valve; a pipe's safety valve is the pipe's only page). Page numbers never meet a wire, menu or saved value.
- **Footer**: Close, Apply and four display keys (composition basis, temperature, pressure, flow time) on their tray; tanks and filters have no Apply; a probe menu (`menu.debug()`) only reads. The keys save the choice in `ClientConfig` and send nothing.
- **Status words** (`statusText`): a data-range notice is cut from the end (marker `"; outside data range: "`, `DataRangeNotices`), the rest worded clause by clause, the notice appended unchanged. `HELD`, `ERROR`, `WAITING`, `closed` and `UNBOUND` pass through in the warning colour; "HELD (phases): …" passes through as `HELD`; "STEADY: no flow" reads `no_flow`, any other `STEADY:` reads `flowing`, `FULL` or `READY` reads `ready`. A column member's clause `COLUMN: part of the column …` reads `status.column`.
- **Heat Control tab**: Control (Off, On); while On a Target (outlet temperature, duty; an exchanger's feed ratio, an air cooler's fan speed; a kettle's or exchanger's `column` mode, where the running column sets the flow and nothing is set here); Set point or, while Off, By hand (an exchanger's bypass); the low limit (blank for none; none on an air cooler); the actuator group with its effort bar and the lines' note; Check lines on an exchanger side with a target drafted.
- **Machine page**: a Create coupling's page only reads. A prime mover has Off/On, a set speed up to its rated speed, and the fuel group from `MachineView.Fuel`.
- **Converters and stills** lead the device column with their groups (`DeviceColumn.Input.lead`); a converter's Apply sends `ConverterEditPayload`, admitted as a queued edit.
- **Accessories**: `Controls` carries `blanket` and `relief` (Pa absolute, 0 none); drafts start at the fitted value or `DEFAULT_BLANKET`, `DEFAULT_RELIEF`; the server keeps each in its valve's range.
- **Controllers**: the screen keeps drafts through publications and refuses to apply an enabled loop with a disconnected input or output; sensor hovers show coordinates and the attached id. The DCS column page has no glass.

## Laws and constants

- The client checks only the compressor ratio before sending (`FluidNetwork.pressureRatioAllowed`); every other range is the server's, whose reason arrives as the reply ("Not applied: …").
- The Phases group's bar counts only crystals in its fourth share on a mole basis; a custom solid is reported as excluded. The aqueous liquid reads "Water" where water is at least `DeviceColumn.WATER_LABEL_SHARE` of it by moles.
- Molecular weights are kg/mol, so mol × weight is kg. A pump's life reads minutes of play at its present wear (`DeviceColumn.Suction`), "—" while it does not wear.

## Formats

The menu, edit and display payloads are on `NetworkProtocol.VERSION`; their rows are in [FORMATS.md](../FORMATS.md) ("Wire protocols"). Nothing here is saved.

## Tests and gates

In `test`, `src/test/java/com/wormzjl/icheme/client/gui/common/` unless noted; each builder's gate runs at every width its screen takes and checks every text's English line, rows that fit, widgets in line, latched positions and widgets that keep their slots:

| Test | Proves |
|---|---|
| `DeviceColumnTest` | The door column's groups for each kind, `assertInLine`, a widget keeps its slot while readings change, the accessory groups |
| `DeviceMetersTest` | The meters' values, units and order |
| `HeatControlColumnTest` | Layout C and its split, each side's switch, targets and settings, a ratio's reading, the lines' note, the `column` mode |
| `HeatDiagramTest` | Every unit's and line design's parts inside the grid and apart, a square and a pill per nozzle, readings that name only the unit's parts, no lang key defined twice |
| `DialogColumnsTest` | The gauge, pipe tool and datasheet doors, the palette's gap, the glass and bands, the footer |
| `MachineColumnTest`, `PumpCurveTest` | The machine page's rows (samples in the test class `MachineColumnSamples`); the pump curve |
| `ConverterDoorTest`, `StillDoorTest` | The converter's and the still's groups at every width (the converter's in a DCS column too) |
| `FluidGuiConsistencyTest` | Units, phase bases and the frame ([client-kit.md](client-kit.md)) |
| `runtime/fluid/FluidPacketCodecTest` | The menu payloads, the static and live split, the delivered-view cache, the heat payloads, a pipe's line reading |
| `FluidPresentationGameTests` (GameTest) | Open replays the cached snapshot, then follows the bucket; acknowledgements on the next bucket |

The status words, the overview table, the Solids rows and the Control tab's status words have no test: judge them on a render or in a check the owner asks for. Renders: `tools/heat-gui-renders/` (`GuiPrototypes` for the pages and dialogs, `HeatDiagramRender`).

## Traps

- The screen reloads its drafts when `inputRevision` changes: text the player typed and did not apply is lost when someone else's edit is applied.
- A live payload is ignored until a static payload has arrived for this menu.
- The Control tab re-reads two server clauses by format, `LOW LIMIT: the outlet is held at %.1f K` and `DUTY INSUFFICIENT: outlet %.1f K below the set point` (`FluidDeviceScreen.LOW_LIMIT`, `DUTY_SHORT`), and a status's `since N s`. Changing those server strings needs the patterns changed with them.
- A tank's Accessory tab must not size its scroll bar from pipe information: a tank's view carries none.
- The GUI says "Organic phase" and "Aqueous phase"; code identifiers and stream ids still say free water.
- `MachineScreen` keeps its own name entry (no follow, no Enter), unlike the screens on `NameEntry`.
- `EquipmentConstructorScreen` and `PipelineToolScreen` do not draw their frame in one batch: their `PaintSwatch` widgets fill straight on `GuiGraphics`. `GaugeScreen`, `MachineScreen`, `ControlPanelScreen` (and the assembly and datasheet screens) do.

**Snapshot:** `9195b5161`, 2026-11-16.
