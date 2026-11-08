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
        if (facts.panels().isEmpty() && publication.idle() && edits.isEmpty() && plans.isEmpty())
            return;
        long busRevision = hosts.busRevision();
        if (driveRevision != busRevision) {
            driveRevision = busRevision;
            world.shafts().dataChanged();
        }
        hosts.refreshColumnMembers();
        var sensors = hosts.inputs();
        applyEdits(sensors);
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

    /** The readings the enabled controllers and the open menus need, and nothing else. */
    private Map<UUID, Sensor.Reading> readings(Map<UUID, ControlInput> sensors) {
        var readings = new HashMap<UUID, Sensor.Reading>();
        var needed = new HashSet<Long>();
        for (var panel : facts.panels())
            if (!panel.dcs() && panel.settings().enabled()) {
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

    /**
     * Every local controller decides in a stable order. Two controllers on one output conflict; a
     * controller that cannot act keeps the output's last setpoint, unless the output is reserved.
     */
    private void runControllers(
            Map<UUID, ControlInput> sensors, Map<UUID, Sensor.Reading> readings) {
        var nextValves = new HashMap<Long, FlowControl.ControlValve>();
        var nextHeat = new HashMap<Long, Double>();
        var claims = new HashMap<Long, Integer>();
        for (var panel : facts.panels())
            if (!panel.dcs() && panel.settings().enabled())
                claims.merge(panel.settings().output(), 1, Integer::sum);
        for (var panel :
                facts.panels().stream()
                        .sorted(Comparator.comparing(p -> p.id().toString()))
                        .toList())
            if (!panel.dcs()) {
                var setting = panel.settings();
                String state = "OFF";
                if (setting.enabled()) {
                    ControlLaw.Decision decision;
                    if (claims.getOrDefault(setting.output(), 0) > 1)
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
                    if (!state.equals("OUTPUT RESERVED")
                            && !nextValves.containsKey(setting.output())
                            && !nextHeat.containsKey(setting.output())) {
                        var held = plans.valve(setting.output());
                        var thermal = plans.heat(setting.output());
                        if (held != null) {
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
        world.controlInputsChanged(plans.commit(nextValves, nextHeat));
    }
}
