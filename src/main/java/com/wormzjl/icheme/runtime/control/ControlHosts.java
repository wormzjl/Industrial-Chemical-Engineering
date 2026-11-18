package com.wormzjl.icheme.runtime.control;

import com.wormzjl.icheme.runtime.fluid.*;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Position;
import com.wormzjl.icheme.runtime.instrument.*;
import com.wormzjl.icheme.science.assembly.AssemblyReport;
import com.wormzjl.icheme.science.equipment.*;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.world.level.block.EquipmentStructure;
import com.wormzjl.icheme.world.level.block.entity.*;

import net.minecraft.core.BlockPos;

import java.util.*;
import java.util.function.LongFunction;

/**
 * The equipment on the data bus: each host's data port, the built-in bindings of equipment, a
 * column's member units and the aliases of a multi-registration unit. It answers the wiring
 * questions and lists every input a controller may read.
 */
final class ControlHosts {
    private final FluidWorldAuthority world;
    private final ControlFacts facts;
    private final Map<Long, DataPort> equipmentPorts = new HashMap<>();
    private final Map<Long, BuiltInInputs.Binding> bindings = new HashMap<>();
    private final Map<Long, List<ControlInput>> memberInputs = new HashMap<>();
    private final Map<Long, Set<Long>> columnMembers = new HashMap<>();
    private final Map<Long, Map<Long, BuiltInInputs.Binding>> memberBindings = new HashMap<>();
    private final Map<Long, Long> aliases = new HashMap<>();
    /** The fixed pollution monitors, by host (WP10): where each reads its cube. */
    private final Map<Long, Monitor> monitors = new HashMap<>();
    private DataBus bus = new DataBus(List.of(), Map.of());
    private long busRevision = -1;
    private boolean restored;

    ControlHosts(FluidWorldAuthority world, ControlFacts facts) {
        this.world = world;
        this.facts = facts;
        facts.ports()
                .values()
                .forEach(
                        p -> {
                            if (p.host() > 0 && !p.kind().power()) equipmentPorts.put(p.host(), p);
                        });
    }

    /** Reconstructs the engine bindings of saved data hosts before the first control cadence. */
    void restoreSavedBindings() {
        if (restored) return;
        restored = true;
        for (var port : equipmentPorts.values()) {
            long host = port.host();
            if (bindings.containsKey(host) || monitors.containsKey(host)) continue;
            var shaft = world.shafts().unit(host);
            if (shaft != null) {
                if (shaft.kind() == com.wormzjl.icheme.science.machine.ShaftNetwork.Kind.MOTOR) {
                    var shape = SavedEquipment.at(world, shaft.position());
                    if (shape != null && shape.kind() == EquipmentShape.Kind.MOTOR)
                        bindings.put(host, new BuiltInInputs.Binding(host, shape, shaft.position(), List.of()));
                }
                // Creative motors connect their drives through the saved port alone.
                continue;
            }
            var vessel = SavedEquipment.ownedBy(world, port.dimension(), port.owner());
            if (vessel != null) {
                var origin = position(port.dimension(), vessel.origin());
                if (vessel.shape().kind() == EquipmentShape.Kind.COLUMN)
                    bindings.put(host, new BuiltInInputs.Binding(host, vessel.shape(), origin, List.of()));
                else {
                    var binding = equipmentBinding(origin, vessel.shape());
                    if (binding.host() == host) remember(binding);
                }
                continue;
            }
            if (port.kind() == DataPort.Kind.SENSOR && !world.registrations().containsKey(host)) {
                var tag = world.savedBlockEntity(port.dimension(), port.owner());
                var level = world.serverLevel(port.dimension());
                if (level != null && tag != null && "icheme:pollution_monitor".equals(tag.getString("id")))
                    monitors.put(host, new Monitor(level.getServer(), level.dimension(), port.owner()));
            }
        }
    }

    private static Position position(String dimension, BlockPos pos) {
        return new Position(dimension, pos.getX(), pos.getY(), pos.getZ());
    }

    private BuiltInInputs.Binding equipmentBinding(Position origin, EquipmentShape shape) {
        var ids = EquipmentStructure.registrationsAtNodes(world, origin, shape);
        long primary = ids.isEmpty() ? 0 : ids.getFirst();
        return new BuiltInInputs.Binding(primary, shape, origin, ids);
    }

    private void remember(BuiltInInputs.Binding binding) {
        bindings.put(binding.host(), binding);
        for (long id : binding.nodes()) if (id > 0) aliases.put(id, binding.host());
    }

    void bind(InstrumentHosts.Host host) {
        if (host.entity() instanceof EquipmentShellBlockEntity shell && shell.shape() != null) {
            var origin = position(shell.getLevel().dimension().location().toString(), shell.origin());
            var recorded = world.structure(origin);
            var binding = equipmentBinding(origin, recorded == null ? shell.shape() : recorded.shape());
            if (!binding.nodes().isEmpty()) {
                long primary = binding.host();
                if (host.identity() != primary) {
                    if (shell.instruments().dataPort() != null
                            && !facts.wired(shell.instruments().dataPort().id())) {
                        removeHost(host.identity());
                        shell.instruments().dataPort(null);
                        syncPort(host);
                    }
                    // Bind both node IDs in the engine. The primary cell refreshes its own socket on load.
                    if (primary > 0) remember(binding);
                    return;
                }
                remember(binding);
            }
        }
        var r = world.registrations().get(host.identity());
        // An equipment whose shape has no data socket takes no controller (a fired still: owner, 2026-11-09, only the
        // electric still has a data port), whatever its registration drives.
        boolean socketless =
                host.entity() instanceof EquipmentShellBlockEntity owner
                        && owner.shape() != null
                        && ShapeRasterizer.services(owner.shape()).data() == null;
        boolean actuator =
                !socketless
                        && r != null
                        && (r.device().control() instanceof FlowControl.ControlValve
                                || r.device().control() instanceof FlowControl.Mover
                                || r.device().heat() != null
                                // A diverter valve's outlet (owner decision O2).
                                || r.device().kind()
                                        == com.wormzjl.icheme.science.fluid.topology.TopologyCompiler
                                                .Kind.DIVERTER_VALVE);
        var ids = new HashMap<UUID, String>();
        for (var sensor : host.metadata().sensors())
            ids.put(sensor.instance(), facts.identify(sensor).label());
        boolean namesChanged = !ids.equals(host.metadata().sensorIds());
        host.metadata().sensorIds(ids);
        // A pipe sensor carries its connector. Removing the last sensor removes that connector too;
        // the cable's built geometry and endpoint ID remain as a disconnected terminal.
        // So does a socketless equipment's actuator port, one an earlier layout gave it.
        if ((host.pipe()
                        || socketless
                                && host.metadata().dataPort() != null
                                && host.metadata().dataPort().kind() == DataPort.Kind.ACTUATOR)
                && !actuator
                && host.metadata().sensors().isEmpty()) {
            removeHost(host.identity());
            if (host.metadata().dataPort() != null || namesChanged) {
                host.metadata().dataPort(null);
                syncPort(host);
            }
            return;
        }
        if (!actuator
                && host.metadata().sensors().isEmpty()
                && host.metadata().dataPort() == null
                && !(host.entity() instanceof EquipmentShellBlockEntity shell
                        && shell.shape() != null
                        && ShapeRasterizer.services(shell.shape()).data() != null)) return;
        var port = equipmentPorts.get(host.identity());
        if (port == null) {
            port = DataPorts.equipment(host, actuator);
            facts.port(port);
            equipmentPorts.put(host.identity(), port);
        } else {
            var refreshed = DataPorts.refresh(port, host, facts::wired);
            if (!refreshed.equals(port)) {
                port = refreshed;
                facts.port(port);
                equipmentPorts.put(host.identity(), port);
            }
        }
        if (namesChanged || !port.equals(host.metadata().dataPort())) {
            host.metadata().dataPort(port);
            syncPort(host);
        }
    }

    private static void syncPort(InstrumentHosts.Host host) {
        host.entity().setChanged();
        InstrumentHosts.loaded(host.entity());
        host.entity()
                .getLevel()
                .sendBlockUpdated(
                        host.entity().getBlockPos(),
                        host.entity().getBlockState(),
                        host.entity().getBlockState(),
                        2);
    }

    void bindColumn(EquipmentShellBlockEntity cell) {
        if (cell.shape() == null || cell.shape().kind() != EquipmentShape.Kind.COLUMN) return;
        var existing = cell.instruments().dataPort();
        if (existing == null) {
            var p = ShapeRasterizer.services(cell.shape()).data();
            var origin = cell.origin();
            var centre =
                    new net.minecraft.world.phys.Vec3(
                            origin.getX() + p.x(), origin.getY() + p.y(), origin.getZ() + p.z());
            existing =
                    facts.ports().values().stream()
                            .filter(
                                    port ->
                                            port.host() > 0
                                                    && !port.kind().power()
                                                    && !world.registrations()
                                                            .containsKey(port.host())
                                                    && world.shafts().unit(port.host()) == null
                                                    // A pollution monitor's socket beside the column's is not the column's (WP10).
                                                    && !monitors.containsKey(port.host())
                                                    && port.dimension()
                                                            .equals(
                                                                    cell.getLevel()
                                                                            .dimension()
                                                                            .location()
                                                                            .toString())
                                                    && port.centre().distanceToSqr(centre) < 1
                                                    && !(cell.getLevel().hasChunkAt(port.owner())
                                                            && cell.getLevel().getBlockState(port.owner()).getBlock()
                                                                    instanceof com.wormzjl.icheme.world.level.block.PollutionMonitorBlock))
                            .findFirst()
                            .orElse(null);
        }
        long id = existing == null ? world.reserveControlIdentity() : existing.host();
        var origin = cell.origin();
        bindings.put(
                id,
                new BuiltInInputs.Binding(
                        id,
                        cell.shape(),
                        new PhysicalFluidTopology.Position(
                                cell.getLevel().dimension().location().toString(),
                                origin.getX(),
                                origin.getY(),
                                origin.getZ()),
                        List.of()));
        bind(new InstrumentHosts.Host(id, cell, List.of(), false));
    }

    /** Where a pollution monitor reads: its server, dimension and block. */
    record Monitor(
            net.minecraft.server.MinecraftServer server,
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
            BlockPos position) {}

    /**
     * A fixed pollution monitor's host (WP10), as a column's: the socket fact whose owner is its block keeps its host and
     * identity (registry first: a monitor placed again where a lost one stood takes its socket back); else a reserved
     * control identity and a new socket. A wired socket keeps its built entry ({@link DataPorts#remount}).
     */
    long bindMonitor(PollutionMonitorBlockEntity monitor) {
        var level = monitor.getLevel();
        var existing =
                monitorPort(
                        facts.ports().values(),
                        level.dimension().location().toString(),
                        monitor.getBlockPos(),
                        host -> world.registrations().containsKey(host) || world.shafts().unit(host) != null);
        long host = existing == null ? world.reserveControlIdentity() : existing.host();
        var mount = DataPorts.monitor(monitor, host, existing == null ? UUID.randomUUID() : existing.id());
        var port = existing == null ? mount : DataPorts.remount(existing, mount, facts.wired(existing.id()));
        if (!port.equals(existing)) facts.port(port);
        equipmentPorts.put(host, port);
        monitors.put(host, new Monitor(level.getServer(), level.dimension(), monitor.getBlockPos().immutable()));
        if (host != monitor.host() || !port.equals(monitor.dataPort())) monitor.bind(host, port);
        return host;
    }

    /**
     * The sensor socket fact a pollution monitor at {@code owner} owns, or null: a socket of a host that no fluid
     * registration or shaft unit holds ({@code taken}), owned by that block.
     */
    static DataPort monitorPort(
            Collection<DataPort> ports, String dimension, BlockPos owner, java.util.function.LongPredicate taken) {
        return ports.stream()
                .filter(
                        p ->
                                p.kind() == DataPort.Kind.SENSOR
                                        && p.host() > 0
                                        && p.dimension().equals(dimension)
                                        && p.owner().equals(owner)
                                        && !taken.test(p.host()))
                .min(Comparator.comparing(p -> p.id().toString()))
                .orElse(null);
    }

    /** Whether {@code host} is a pollution monitor bound this session. */
    boolean monitor(long host) {
        return monitors.containsKey(host);
    }

    /** A monitor channel's reading: its cube's indices from the installed pollution field (no step), 0 while it is off. */
    Sensor.Reading readMonitor(ControlInput input, long tick) {
        var m = monitors.get(input.host());
        var service =
                m == null
                        ? null
                        : com.wormzjl.icheme.runtime.pollution.PollutionService.find(m.server()).orElse(null);
        double[] indices =
                m == null
                        ? new double[com.wormzjl.icheme.science.pollution.Channel.values().length]
                        : com.wormzjl.icheme.runtime.pollution.MonitorSignals.indices(service, m.dimension(), m.position());
        return BuiltInInputs.monitorReading(
                input, indices, com.wormzjl.icheme.runtime.pollution.MonitorSignals.enabled(service), tick);
    }

    /**
     * Binds creative motor {@code unit} standing at {@code pos} with its shaft out of {@code facing} as a data host (part C of
     * PUMP_SPEED_SHUT_VALVE_REVIEW.md): its socket ({@link DataPorts#creativeMotor}) is a control fact keyed by the unit, kept
     * where a cable is wired to it ({@link DataPorts#remount}). Its block entity keeps only a copy of the socket fact; the unit and its settings are the engine's.
     */
    void bindCreativeMotor(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos, net.minecraft.core.Direction facing, ShaftUnit unit) {
        var port = equipmentPorts.get(unit.id());
        var mount = DataPorts.creativeMotor(level, pos, facing, unit.id(), port == null ? UUID.randomUUID() : port.id());
        var next = port == null ? mount : DataPorts.remount(port, mount, facts.wired(port.id()));
        if (!next.equals(port)) {
            facts.port(next);
            equipmentPorts.put(unit.id(), next);
        }
        if (level.getBlockEntity(pos) instanceof CreativeMotorBlockEntity motor) motor.bind(next);
    }

    void bindMotor(EquipmentShellBlockEntity cell, ShaftUnit unit) {
        var origin = cell.origin();
        bindings.put(
                unit.id(),
                new BuiltInInputs.Binding(
                        unit.id(),
                        cell.shape(),
                        new PhysicalFluidTopology.Position(
                                cell.getLevel().dimension().location().toString(),
                                origin.getX(),
                                origin.getY(),
                                origin.getZ()),
                        List.of()));
        var port = equipmentPorts.get(unit.id());
        if (port == null) {
            port = DataPorts.motor(cell, unit);
            facts.port(port);
            equipmentPorts.put(unit.id(), port);
        } else {
            var host = new InstrumentHosts.Host(unit.id(), cell, List.of(), false);
            var refreshed = DataPorts.refresh(port, host, facts::wired);
            if (!refreshed.equals(port)) {
                port = refreshed;
                facts.port(port);
                equipmentPorts.put(unit.id(), port);
            }
        }
        if (!port.equals(cell.instruments().dataPort())) {
            cell.instruments().dataPort(port);
            cell.setChanged();
            InstrumentHosts.loaded(cell);
            cell.getLevel()
                    .sendBlockUpdated(
                            cell.getBlockPos(), cell.getBlockState(), cell.getBlockState(), 2);
        }
    }

    /**
     * Uses the cached assembly report and registration positions; never traverses pipes or loads a
     * chunk.
     */
    void refreshColumnMembers() {
        var columns = bindings.values().stream().filter(b -> b.shape().kind() == EquipmentShape.Kind.COLUMN).toList();
        for (var column : columns) {
            var origin = column.origin();
            var level = world.serverLevel(origin.dimension());
            if (level == null) continue;
            var stored =
                    com.wormzjl.icheme.runtime.assembly.AssemblyService.entry(level, new BlockPos(origin.x(), origin.y(), origin.z()));
            refreshColumnMembers(column.host(), stored == null ? null : stored.report(), placed -> {
                var member = position(origin.dimension(), BlockPos.of(placed));
                var shape = SavedEquipment.at(world, member);
                return shape == null ? null : equipmentBinding(member, shape);
            });
        }
    }

    void refreshColumnMembers(long host, AssemblyReport report, LongFunction<BuiltInInputs.Binding> read) {
        if (report == null || report.members().isEmpty()) {
            memberInputs.remove(host);
            columnMembers.remove(host);
            memberBindings.remove(host);
            return;
        }
        var inputs = new ArrayList<ControlInput>();
        var members = new HashSet<Long>();
        var origins = new HashSet<>(report.members());
        var previous = memberBindings.getOrDefault(host, Map.of());
        var present = new HashMap<Long, BuiltInInputs.Binding>();
        for (var slot : report.slots())
            for (long placed : slot.units()) {
                if (!origins.contains(placed)) continue;
                var binding = read.apply(placed);
                if (binding == null || binding.host() == 0) binding = previous.get(placed);
                if (binding == null) continue;
                present.put(placed, binding);
                for (long node : binding.nodes()) if (node > 0) members.add(node);
                remember(binding);
                String prefix = slot.slot().part() + "_" + (slot.slot().index() + 1) + "_EQ" + binding.host() + "_";
                for (var input : BuiltInInputs.member(binding))
                    inputs.add(ControlInput.builtIn(host, input.source(), prefix + input.channel(), input.kind(), input.role()));
            }
        memberInputs.put(host, List.copyOf(inputs));
        columnMembers.put(host, Set.copyOf(members));
        memberBindings.put(host, present);
    }

    /** Connected physical host IDs, independent of chunk loading and menu subscriptions. */
    Set<Long> connectedHosts(long host) {
        var port = equipmentPorts.get(aliases.getOrDefault(host, host));
        if (port == null) return Set.of();
        var result = new HashSet<Long>();
        for (var id : bus().connected(port.id())) {
            var p = facts.port(id);
            if (p != null && p.host() > 0 && p.host() != host) result.add(p.host());
        }
        for (var a : aliases.entrySet()) if (result.contains(a.getValue())) result.add(a.getKey());
        return Set.copyOf(result);
    }

    void removeHost(long host) {
        bindings.remove(host);
        memberInputs.remove(host);
        columnMembers.remove(host);
        memberBindings.remove(host);
        monitors.remove(host);
        aliases.entrySet().removeIf(a -> a.getKey() == host || a.getValue() == host);
        var port = equipmentPorts.remove(host);
        if (port != null) facts.removePort(port.id());
    }

    DataBus bus() {
        if (busRevision != facts.topologyRevision()) {
            bus = new DataBus(facts.standing(), facts.ports());
            busRevision = facts.topologyRevision();
        }
        return bus;
    }

    /** The topology revision the bus was built at, after bringing it up to date. */
    long busRevision() {
        bus();
        return busRevision;
    }

    boolean wired(UUID controller, long host) {
        for (var entry : columnMembers.entrySet())
            if (entry.getValue().contains(host)) {
                var primary = equipmentPorts.get(entry.getKey());
                if (primary != null && bus().connected(controller, primary.id())) return true;
            }
        var port = equipmentPorts.get(aliases.getOrDefault(host, host));
        return port != null && bus().connected(controller, port.id());
    }

    /**
     * Every input a controller may read this cadence, by instance: installed sensors (each given
     * its stable ID), the built-in channels of bound equipment, inline movers' channels and column
     * members' channels.
     */
    Map<UUID, ControlInput> inputs() {
        var sensors = new HashMap<UUID, ControlInput>();
        world.instruments()
                .allSensors()
                .forEach(
                        s -> {
                            facts.identify(s);
                            sensors.put(s.instance(), ControlInput.installed(s));
                        });
        for (var binding : bindings.values())
            for (var input : BuiltInInputs.channels(binding)) sensors.put(input.instance(), input);
        for (var entry : equipmentPorts.entrySet())
            if (!bindings.containsKey(entry.getKey())) {
                var r = world.registrations().get(entry.getKey());
                if (r != null && r.device().control() instanceof FlowControl.Mover)
                    for (var input : BuiltInInputs.inline(entry.getKey()))
                        sensors.put(input.instance(), input);
            }
        for (var channels : memberInputs.values())
            for (var input : channels) sensors.put(input.instance(), input);
        for (long host : monitors.keySet())
            for (var input : BuiltInInputs.monitor(host)) sensors.put(input.instance(), input);
        return sensors;
    }

    /** The equipment binding a built-in input reads: its source's unit, else its host's. */
    BuiltInInputs.Binding binding(ControlInput input) {
        return bindings.getOrDefault(
                aliases.getOrDefault(input.source(), input.source()), bindings.get(input.host()));
    }

    /**
     * The hosts a controller's bus reaches, with the aliases of each unit and a column's members.
     */
    Set<Long> visibleHosts(UUID controller) {
        var hostIds = new HashSet<>(bus().hosts(controller, facts::port));
        for (var a : aliases.entrySet())
            if (hostIds.contains(a.getValue())) hostIds.add(a.getKey());
        for (var entry : columnMembers.entrySet())
            if (hostIds.contains(entry.getKey())) hostIds.addAll(entry.getValue());
        return hostIds;
    }

    /** The column hosts whose data socket {@code controller}'s bus reaches, in host order (W6.2: the DCS's column page). */
    List<Long> columns(UUID controller) {
        var out = new ArrayList<Long>();
        for (long host : bindings.keySet()) if (columnMaster(host) != null && wired(controller, host)) out.add(host);
        out.sort(null);
        return out;
    }

    /** Whether {@code host} is a fluid converter's (batch 2026-11-12-fluid-conversion, W4): its vessel's binding. */
    boolean converter(long host) {
        var b = bindings.get(host);
        return b != null && b.shape().kind().converter() && b.node(0) == host;
    }

    /** The converter hosts whose data socket {@code controller}'s bus reaches, in host order (W4: the DCS's converter page). */
    List<Long> converters(UUID controller) {
        var out = new ArrayList<Long>();
        for (long host : bindings.keySet()) if (converter(host) && wired(controller, host)) out.add(host);
        out.sort(null);
        return out;
    }

    /** The master position of column host {@code host}, or null where {@code host} is no column's (W6.3: a column loop's output). */
    PhysicalFluidTopology.Position columnMaster(long host) {
        var binding = bindings.get(host);
        return binding != null && binding.shape().kind() == EquipmentShape.Kind.COLUMN ? binding.origin() : null;
    }

    /** The data port registered for an equipment host, or null. */
    DataPort port(long host) {
        return equipmentPorts.get(host);
    }
}
