package com.wormzjl.icheme.runtime.control;

import com.wormzjl.icheme.runtime.fluid.*;
import com.wormzjl.icheme.runtime.instrument.*;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.world.inventory.ControlPanelMenu;
import com.wormzjl.icheme.world.level.block.entity.*;

import java.util.*;

/**
 * Server-thread owner. Cable topology is structural; control and publication run only on engine
 * deadlines. The duties sit in package classes: {@link ControlHosts} (the equipment on the data
 * bus), {@link ControlLaw} (one controller's decision), {@link ControlPlans} (the setpoints the
 * engine reads) and {@link ControlPublication} (the operator's view). This class registers the
 * built wiring, queues operator edits and runs the cadence.
 */
public final class ControlService {
    public static final int CADENCE = 100;
    private final FluidWorldAuthority world;
    private final ControlFacts facts;
    private final ControlHosts hosts;
    private final ControlLaw law;
    private final ControlPlans plans;
    private final ControlPublication publication;
    private long driveRevision = -1;
    private final ArrayDeque<Edit> edits = new ArrayDeque<>();

    private record Edit(ControlPanelMenu menu, UUID id, long revision, ControlSettings settings) {}
    private final ArrayDeque<ColumnRequest> columnCommands = new ArrayDeque<>();

    private record ColumnRequest(ControlPanelMenu menu, ColumnCommands.Command command) {}
    private final ArrayDeque<ConverterRequest> converterCommands = new ArrayDeque<>();

    private record ConverterRequest(ControlPanelMenu menu, ConverterCommands.Command command) {}

    public ControlService(FluidWorldAuthority world, ControlFacts facts) {
        this.world = world;
        this.facts = facts;
        world.instruments().allSensors().stream()
                .sorted(Comparator.comparing(s -> s.instance().toString()))
                .forEach(facts::identify);
        hosts = new ControlHosts(world, facts);
        law = new ControlLaw(world, hosts);
        plans = new ControlPlans(world);
        publication = new ControlPublication(world, facts, hosts);
    }

    public ControlFacts facts() {
        return facts;
    }

    /** The firing the controllers set for still {@code pot} at the last cadence, NaN for none (owner decision O1). */
    /** The feed rate a control loop planned for column unit {@code unit} at the last cadence, mol/s, or NaN for none (W6.3). */
    public double columnFeed(long unit) {
        var v = plans.columnFeed(unit);
        return v == null ? Double.NaN : v;
    }

    public double stillFiring(long pot) {
        var v = plans.firing(pot);
        return v == null ? Double.NaN : v;
    }

    /** The reflux ratio the controllers set for still {@code pot} at the last cadence, NaN for none. */
    public double stillReflux(long pot) {
        var v = plans.reflux(pot);
        return v == null ? Double.NaN : v;
    }

    /** Cable sites whose chunk loaded, by dimension, each queued once until the re-alignment checks it (W5c C2). */
    private final Map<String, ArrayDeque<DataBus.Site>> cableQueue = new HashMap<>();
    private final Set<DataBus.Site> cableQueued = new HashSet<>();
    /** Controllers and consoles whose chunk loaded, by dimension, each queued once (W5c C3). */
    private final Map<String, ArrayDeque<UUID>> panelQueue = new HashMap<>();
    private final Set<UUID> panelQueued = new HashSet<>();

    /**
     * Queues the cables (W5c C2) and the controllers and consoles (C3) of chunk {@code chunk} of
     * {@code dimension} for the re-alignment (registry first).
     */
    public void loadedChunk(String dimension, long chunk) {
        for (var site : facts.cablesIn(dimension, chunk))
            if (cableQueued.add(site)) cableQueue.computeIfAbsent(dimension, k -> new ArrayDeque<>()).add(site);
        for (var id : facts.panelsIn(dimension, chunk))
            if (panelQueued.add(id)) panelQueue.computeIfAbsent(dimension, k -> new ArrayDeque<>()).add(id);
    }

    /** The next controller or console of {@code dimension} the re-alignment checks, taken off its queue, or null. */
    public UUID nextPanel(String dimension) {
        var queue = panelQueue.get(dimension);
        if (queue == null) return null;
        var id = queue.poll();
        if (queue.isEmpty()) panelQueue.remove(dimension);
        if (id != null) panelQueued.remove(id);
        return id;
    }

    /** The next cable of {@code dimension} the re-alignment checks, taken off its queue, or null when none is queued. */
    public DataBus.Site nextCable(String dimension) {
        var queue = cableQueue.get(dimension);
        if (queue == null) return null;
        var site = queue.poll();
        if (queue.isEmpty()) cableQueue.remove(dimension);
        if (site != null) cableQueued.remove(site);
        return site;
    }

    /**
     * Immutable slice inputs. The original device settings remain facts and resume when the
     * controller is off.
     */
    public PassiveNetwork apply(PassiveNetwork graph) {
        return plans.apply(graph);
    }

    public void cable(DataBus.Cable cable) {
        facts.cable(cable);
    }

    public void removeCable(DataBus.Site site) {
        facts.removeCable(site);
    }

    public void loaded(ConduitBlockEntity cable) {
        var existing = facts.cable(cable.cable().site());
        if (existing != null) cable.configure(existing);
        else facts.cable(cable.cable());
        if (cable.getLevel() instanceof net.minecraft.server.level.ServerLevel server)
            com.wormzjl.icheme.world.level.block.LinePieces.queue(
                    server.getServer(), cable.cable().site());
    }

    public void loaded(PipeRackBlockEntity rack) {
        var wires =
                facts.cables().stream()
                        .filter(
                                c ->
                                        c.site().lane() > 0
                                                && c.site()
                                                        .dimension()
                                                        .equals(
                                                                rack.getLevel()
                                                                        .dimension()
                                                                        .location()
                                                                        .toString())
                                                && c.site().cell().equals(rack.getBlockPos()))
                        .toList();
        // Registry first (format 4, W5c C2): a lane whose fact holds no frame learns this cell's (a world saved
        // before), unless another lane of the cell holds another: then this is not the cell the facts know.
        var frame = com.wormzjl.icheme.world.level.block.PipeRackBlock.frame(rack.getBlockState());
        if (wires.stream().anyMatch(c -> c.frame() == null)
                && wires.stream().allMatch(c -> c.frame() == null || c.frame().equals(frame))) {
            var framed = new ArrayList<DataBus.Cable>();
            for (var c : wires) {
                var next = c.frame() == null ? c.withFrame(frame) : c;
                if (next != c) facts.cable(next);
                framed.add(next);
            }
            wires = framed;
        }
        rack.cables(wires);
        if (rack.getLevel() instanceof net.minecraft.server.level.ServerLevel server)
            for (var cable : wires)
                com.wormzjl.icheme.world.level.block.LinePieces.queue(
                        server.getServer(), cable.site());
    }

    public void loaded(ControlPanelBlockEntity box) {
        var dim = box.getLevel().dimension().location().toString();
        var panel =
                facts.panels().stream()
                        .filter(
                                p ->
                                        p.dimension().equals(dim)
                                                && p.position().equals(box.getBlockPos()))
                        .findFirst()
                        .orElse(null);
        if (panel == null) {
            // A copied block must never take over another controller's persistent identity.
            var id = facts.panel(box.identity()) == null ? box.identity() : UUID.randomUUID();
            panel =
                    new ControlFacts.Panel(
                            id, dim, box.getBlockPos(), box.dcs(), 0, ControlSettings.EMPTY);
            facts.panel(panel);
        }
        if (panel.dcs() != box.dcs())
            throw new IllegalStateException("Control panel kind differs from registered facts");
        var port = facts.port(panel.id());
        var mount = DataPorts.panel(box, panel.id());
        if (port == null) {
            port = mount;
            facts.port(port);
        } else {
            var refreshed = DataPorts.remount(port, mount, facts.wired(port.id()));
            if (!refreshed.equals(port)) {
                port = refreshed;
                facts.port(port);
            }
        }
        if (!panel.id().equals(box.identity()) || !port.equals(box.dataPort()))
            box.bind(panel.id(), port);
    }

    public void removePanel(UUID id) {
        facts.removePanel(id);
        law.forget(id);
        publication.forget(id);
    }

    public void bind(InstrumentHosts.Host host) {
        hosts.bind(host);
    }

    public void bindColumn(EquipmentShellBlockEntity cell) {
        hosts.bindColumn(cell);
    }

    public void bindMotor(EquipmentShellBlockEntity cell, ShaftUnit unit) {
        hosts.bindMotor(cell, unit);
    }

    /** Connected physical host IDs, independent of chunk loading and menu subscriptions. */
    public Set<Long> connectedHosts(long host) {
        return hosts.connectedHosts(host);
    }

    public void removeHost(long host) {
        hosts.removeHost(host);
    }

    public void watch(ControlPanelMenu menu) {
        publication.watch(menu);
    }

    public void unwatch(ControlPanelMenu menu) {
        publication.unwatch(menu);
    }

    public void edit(ControlPanelMenu menu, UUID id, long revision, ControlSettings settings) {
        if (edits.size() < 4096) edits.addLast(new Edit(menu, id, revision, settings));
        else menu.answer(id, "BUSY: APPLY AGAIN");
    }

    /** A DCS's column command (W6.2): queued like a controller edit, run on the next cadence. */
    public void column(ControlPanelMenu menu, ColumnCommands.Command command) {
        if (columnCommands.size() < 256) columnCommands.addLast(new ColumnRequest(menu, command));
        else menu.answer(menu.panel(), "BUSY: APPLY AGAIN");
    }

    /** A DCS's converter command (batch 2026-11-12-fluid-conversion, W4): queued like a controller edit, run on the next cadence. */
    public void converter(ControlPanelMenu menu, ConverterCommands.Command command) {
        if (converterCommands.size() < 256) converterCommands.addLast(new ConverterRequest(menu, command));
        else menu.answer(menu.panel(), "BUSY: APPLY AGAIN");
    }

    /** A DCS menu's choice of column page (W6.2): the publication sends that column's Operation view. */
    public void selectColumn(ControlPanelMenu menu, long host) {
        menu.selectColumn(host);
    }

    private boolean accessible(ControlPanelMenu menu, UUID id) {
        var owner = facts.panel(menu.panel());
        return owner != null
                && (owner.id().equals(id) || owner.dcs() && hosts.bus().connected(owner.id(), id));
    }

    /**
     * One cadence, in order: the bus and the column members, the inputs, the queued edits, the
     * readings, every controller's decision, the setpoints the engine reads, and the publication
     * for open menus.
     */
    public void tick(long tick) {
        if (tick % CADENCE != 0) return;
        if (facts.panels().isEmpty() && publication.idle() && edits.isEmpty() && columnCommands.isEmpty() && converterCommands.isEmpty() && plans.isEmpty())
            return;
        long busRevision = hosts.busRevision();
        if (driveRevision != busRevision) {
            driveRevision = busRevision;
            world.shafts().dataChanged();
        }
        hosts.refreshColumnMembers();
        var sensors = hosts.inputs();
        applyEdits(sensors);
        applyColumnCommands();
        applyConverterCommands();
        var readings = readings(sensors);
        runControllers(sensors, readings);
        publication.publish(sensors, readings);
    }

    private void applyEdits(Map<UUID, ControlInput> sensors) {
        while (!edits.isEmpty()) {
            var edit = edits.removeFirst();
            var panel = facts.panel(edit.id());
            if (!edit.menu().stillValid(edit.menu().player())
                    || !edit.menu().player().mayBuild()
                    || !accessible(edit.menu(), edit.id())
                    || panel == null
                    || panel.dcs()
                    || panel.revision() != edit.revision()) {
                edit.menu().answer(edit.id(), "SETTINGS CHANGED: APPLY AGAIN");
                continue;
            }
            var before = panel.settings();
            var after = edit.settings();
            var sensor = sensors.get(after.sensor());
            if (after.enabled()
                    && (sensor == null
                            || !hosts.wired(panel.id(), sensor.key().host())
                            || !hosts.wired(panel.id(), after.output()))) {
                edit.menu().answer(edit.id(), "NO DATA LINK");
                continue;
            }
            if (!Objects.equals(before.sensor(), after.sensor())
                    || before.output() != after.output()
                    || before.action() != after.action()
                    || before.enabled() != after.enabled()) law.forget(panel.id());
            facts.panel(panel.edit(after));
            edit.menu().answer(edit.id(), "APPLIED");
        }
    }

    /**
     * The queued column commands (W6.2), each for its console: refused with NO DATA LINK where its bus no longer reaches the
     * column; else the column's own Start, Stop or set points at its origin, answered on the menu.
     */
    private void applyColumnCommands() {
        while (!columnCommands.isEmpty()) {
            var request = columnCommands.removeFirst();
            var menu = request.menu();
            var console = facts.panel(menu.panel());
            var cell = hosts.column(request.command().host());
            boolean allowed = menu.stillValid(menu.player()) && menu.player().mayBuild();
            boolean wired = console != null && cell != null && hosts.wired(console.id(), request.command().host());
            menu.answer(menu.panel(), ColumnCommands.run(console != null && console.dcs(), allowed, wired, request.command(), door(cell)));
        }
    }

    /**
     * The queued converter commands (W4), each for its console: refused with NO DATA LINK where its bus no longer reaches the
     * converter; else its next settings, changed in the command's field, queued for the engine's next tick.
     */
    private void applyConverterCommands() {
        while (!converterCommands.isEmpty()) {
            var request = converterCommands.removeFirst();
            var menu = request.menu();
            var console = facts.panel(menu.panel());
            long host = request.command().host();
            boolean allowed = menu.stillValid(menu.player()) && menu.player().mayBuild();
            boolean wired = console != null && hosts.converter(host) && hosts.wired(console.id(), host);
            menu.answer(menu.panel(), ConverterCommands.run(console != null && console.dcs(), allowed, wired, request.command(), new ConverterCommands.Door() {
                @Override
                public Converters.Settings settings(long id) {
                    return world.converters().next(id);
                }

                @Override
                public String apply(long id, Converters.Settings settings) {
                    return world.converterSettings(id, settings);
                }
            }));
        }
    }

    /** The Operation actions of the column whose loaded cell is {@code cell}, at its origin. */
    private static ColumnCommands.Door door(EquipmentShellBlockEntity cell) {
        return new ColumnCommands.Door() {
            @Override
            public String start(long host) {
                return com.wormzjl.icheme.runtime.assembly.AssemblyService.start(level(), cell.origin());
            }

            @Override
            public void stop(long host) {
                com.wormzjl.icheme.runtime.assembly.AssemblyService.stop(level(), cell.origin());
            }

            @Override
            public String setpoints(long host, List<Double> values) {
                return com.wormzjl.icheme.runtime.assembly.AssemblyService.setpoints(level(), cell.origin(), values);
            }

            private net.minecraft.server.level.ServerLevel level() {
                return (net.minecraft.server.level.ServerLevel) cell.getLevel();
            }
        };
    }

    /** The readings the enabled controllers and the open menus need, and nothing else. */
    private Map<UUID, Sensor.Reading> readings(Map<UUID, ControlInput> sensors) {
        var readings = new HashMap<UUID, Sensor.Reading>();
        var needed = new HashSet<Long>();
        for (var panel : facts.panels())
            if (!panel.dcs() && panel.settings().enabled() && !facts.heldPanel(panel.id())) {
                var s = sensors.get(panel.settings().sensor());
                if (s != null && hosts.wired(panel.id(), s.key().host()))
                    needed.add(s.key().host());
            }
        publication.watchedHosts(needed);
        // A pure read of accepted engine presentation; no replay invokes a solve or advances time.
        var views = new HashMap<Long, FluidView>();
        for (long host : needed)
            for (var r :
                    world.instruments()
                            .sensorReadings(host, views.computeIfAbsent(host, world::view)))
                readings.put(r.instance(), r);
        for (var input : sensors.values())
            if (input.physical() == null && needed.contains(input.host()))
                readings.put(
                        input.instance(),
                        BuiltInInputs.read(input, world, hosts.binding(input), views));
        return readings;
    }

    /** What a controller claims: its output, and for a batch still's pot the handle its action names (firing or reflux). */
    private record Claim(long output, ControlSettings.Action handle) {}

    private Claim claim(ControlSettings s) {
        var r = world.registrations().get(s.output());
        return new Claim(s.output(), r != null && r.device().stillPot() ? s.action() : null);
    }

    /**
     * Every local controller decides in a stable order. Two controllers on one output conflict (on a
     * still's pot, two on one handle); a controller that cannot act keeps the output's last
     * setpoint, unless the output is reserved. A diverter valve's outlet is an engine edit of its
     * joints: it stays where the controller last set it.
     */
    private void runControllers(
            Map<UUID, ControlInput> sensors, Map<UUID, Sensor.Reading> readings) {
        var nextValves = new HashMap<Long, FlowControl.ControlValve>();
        var nextHeat = new HashMap<Long, Double>();
        var nextFiring = new HashMap<Long, Double>();
        var nextReflux = new HashMap<Long, Double>();
        var nextColumnFeed = new HashMap<Long, Double>();
        var claims = new HashMap<Claim, Integer>();
        for (var panel : facts.panels())
            if (!panel.dcs() && panel.settings().enabled() && !facts.heldPanel(panel.id()))
                claims.merge(claim(panel.settings()), 1, Integer::sum);
        for (var panel :
                facts.panels().stream()
                        .sorted(Comparator.comparing(p -> p.id().toString()))
                        .toList())
            if (!panel.dcs()) {
                var setting = panel.settings();
                // A held controller (W5c C3): no block stands for it, so it is off and its output resumes.
                boolean blocked = facts.heldPanel(panel.id());
                String state = blocked ? "HELD" : "OFF";
                if (setting.enabled() && !blocked) {
                    ControlLaw.Decision decision;
                    if (claims.getOrDefault(claim(setting), 0) > 1)
                        decision = ControlLaw.Decision.noSetpoint("OUTPUT CONFLICT");
                    else
                        try {
                            decision =
                                    law.decide(
                                            panel,
                                            sensors.get(setting.sensor()),
                                            readings.get(setting.sensor()));
                        } catch (IllegalArgumentException invalid) {
                            decision = ControlLaw.Decision.noSetpoint("INVALID SETTINGS");
                        }
                    state = decision.status();
                    if (decision.valve() != null)
                        nextValves.put(setting.output(), decision.valve());
                    if (decision.heat() != null) nextHeat.put(setting.output(), decision.heat());
                    if (decision.firing() != null)
                        nextFiring.put(setting.output(), decision.firing());
                    if (decision.reflux() != null)
                        nextReflux.put(setting.output(), decision.reflux());
                    if (decision.feed() != null) nextColumnFeed.merge(decision.unit(), decision.feed(), Math::min);
                    if (decision.outlet() != null) {
                        var refused = world.divert(setting.output(), decision.outlet());
                        if (refused != null) state += " · " + refused.toUpperCase(Locale.ROOT);
                    }
                    if (!state.equals("OUTPUT RESERVED") && !decision.sets()) {
                        var held = plans.valve(setting.output());
                        var thermal = plans.heat(setting.output());
                        boolean firing = setting.action() == ControlSettings.Action.INLET;
                        var still = firing ? plans.firing(setting.output()) : plans.reflux(setting.output());
                        if (still != null) {
                            (firing ? nextFiring : nextReflux).put(setting.output(), still);
                            state += " · LAST SETPOINT";
                        } else if (held != null) {
                            nextValves.put(setting.output(), held);
                            state += " · LAST SETPOINT";
                        } else if (thermal != null) {
                            nextHeat.put(setting.output(), thermal);
                            state += " · LAST SETPOINT";
                        }
                    }
                }
                publication.status(panel.id(), state);
            }
        world.controlInputsChanged(plans.commit(nextValves, nextHeat, nextFiring, nextReflux));
        plans.commitColumnFeeds(nextColumnFeed);
    }
}
