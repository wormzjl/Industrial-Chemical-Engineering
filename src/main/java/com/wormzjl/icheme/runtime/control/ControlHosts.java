package com.wormzjl.icheme.runtime.control;

import com.wormzjl.icheme.runtime.fluid.*;
import com.wormzjl.icheme.runtime.instrument.*;
import com.wormzjl.icheme.science.equipment.*;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.world.level.block.EquipmentStructure;
import com.wormzjl.icheme.world.level.block.entity.*;

import net.minecraft.core.BlockPos;

import java.util.*;

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
    private final Map<Long, java.lang.ref.WeakReference<EquipmentShellBlockEntity>> columnCells =
            new HashMap<>();
    private final Map<Long, List<ControlInput>> memberInputs = new HashMap<>();
    private final Map<Long, Set<Long>> columnMembers = new HashMap<>();
    private final Map<Long, Long> aliases = new HashMap<>();
    private DataBus bus = new DataBus(List.of(), Map.of());
    private long busRevision = -1;

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

    void bind(InstrumentHosts.Host host) {
        if (host.entity() instanceof EquipmentShellBlockEntity shell && shell.shape() != null) {
            var nodes = ShapeRasterizer.fluidCells(shell.shape());
            var ids = new ArrayList<Long>();
            for (var node : nodes) {
                var at = shell.origin().offset(node.x(), node.y(), node.z());
                var pos =
                        new PhysicalFluidTopology.Position(
                                shell.getLevel().dimension().location().toString(),
                                at.getX(),
                                at.getY(),
                                at.getZ());
                ids.add(world.at(pos).map(r -> r.device().id()).orElse(0L));
            }
            if (!ids.isEmpty() && ids.getFirst() > 0) {
                long primary = ids.getFirst();
                bindings.put(
                        primary,
                        new BuiltInInputs.Binding(
                                primary,
                                shell.shape(),
                                new PhysicalFluidTopology.Position(
                                        shell.getLevel().dimension().location().toString(),
                                        shell.origin().getX(),
                                        shell.origin().getY(),
                                        shell.origin().getZ()),
                                ids));
                for (long id : ids) if (id > 0) aliases.put(id, primary);
                if (host.identity() != primary) {
                    if (shell.instruments().dataPort() != null
                            && !facts.wired(shell.instruments().dataPort().id())) {
                        removeHost(host.identity());
                        shell.instruments().dataPort(null);
                        syncPort(host);
                    }
                    var cell = nodes.getFirst();
                    var pos = shell.origin().offset(cell.x(), cell.y(), cell.z());
                    if (shell.getLevel().getBlockEntity(pos)
                            instanceof EquipmentShellBlockEntity first)
                        bind(new InstrumentHosts.Host(primary, first, List.of(), false));
                    return;
                }
            }
        }
        var r = world.registrations().get(host.identity());
        boolean actuator =
                r != null
                        && (r.device().control() instanceof FlowControl.ControlValve
                                || r.device().control() instanceof FlowControl.Mover
                                || r.device().heat() != null);
        var ids = new HashMap<UUID, String>();
        for (var sensor : host.metadata().sensors())
            ids.put(sensor.instance(), facts.identify(sensor).label());
        boolean namesChanged = !ids.equals(host.metadata().sensorIds());
        host.metadata().sensorIds(ids);
        // A pipe sensor carries its connector. Removing the last sensor removes that connector too;
        // the cable's built geometry and endpoint ID remain as a disconnected terminal.
        if (host.pipe() && !actuator && host.metadata().sensors().isEmpty()) {
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
                                                    && port.dimension()
                                                            .equals(
                                                                    cell.getLevel()
                                                                            .dimension()
                                                                            .location()
                                                                            .toString())
                                                    && port.centre().distanceToSqr(centre) < 1)
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
        columnCells.put(id, new java.lang.ref.WeakReference<>(cell));
        bind(new InstrumentHosts.Host(id, cell, List.of(), false));
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
        for (var entry : columnCells.entrySet()) {
            var cell = entry.getValue().get();
            if (cell == null
                    || cell.isRemoved()
                    || !(cell.getLevel() instanceof net.minecraft.server.level.ServerLevel level))
                continue;
            var stored =
                    com.wormzjl.icheme.runtime.assembly.AssemblyService.entry(level, cell.origin());
            if (stored == null || stored.report() == null) {
                memberInputs.remove(entry.getKey());
                columnMembers.remove(entry.getKey());
                continue;
            }
            var inputs = new ArrayList<ControlInput>();
            var members = new HashSet<Long>();
            for (var slot : stored.report().slots())
                for (long placed : slot.units()) {
                    var pos = BlockPos.of(placed);
                    if (!level.hasChunkAt(pos)) continue;
                    var vessel = EquipmentStructure.find(level, pos);
                    if (vessel == null) continue;
                    var ids = new ArrayList<Long>();
                    for (var node : ShapeRasterizer.fluidCells(vessel.shape())) {
                        var at = vessel.origin().offset(node.x(), node.y(), node.z());
                        var where =
                                new PhysicalFluidTopology.Position(
                                        level.dimension().location().toString(),
                                        at.getX(),
                                        at.getY(),
                                        at.getZ());
                        ids.add(world.at(where).map(r -> r.device().id()).orElse(0L));
                    }
                    long primary = ids.stream().filter(i -> i > 0).findFirst().orElse(0L);
                    if (primary == 0) continue;
                    members.addAll(ids.stream().filter(i -> i > 0).toList());
                    var binding =
                            new BuiltInInputs.Binding(
                                    primary,
                                    vessel.shape(),
                                    new PhysicalFluidTopology.Position(
                                            level.dimension().location().toString(),
                                            vessel.origin().getX(),
                                            vessel.origin().getY(),
                                            vessel.origin().getZ()),
                                    ids);
                    bindings.put(primary, binding);
                    for (long node : ids) if (node > 0) aliases.put(node, primary);
                    String prefix =
                            slot.slot().part()
                                    + "_"
                                    + (slot.slot().index() + 1)
                                    + "_EQ"
                                    + primary
                                    + "_";
                    for (var input : BuiltInInputs.member(binding))
                        inputs.add(
                                ControlInput.builtIn(
                                        entry.getKey(),
                                        input.source(),
                                        prefix + input.channel(),
                                        input.kind(),
                                        input.role()));
                }
            memberInputs.put(entry.getKey(), List.copyOf(inputs));
            columnMembers.put(entry.getKey(), Set.copyOf(members));
        }
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
        columnCells.remove(host);
        memberInputs.remove(host);
        columnMembers.remove(host);
        aliases.entrySet().removeIf(a -> a.getKey() == host || a.getValue() == host);
        var port = equipmentPorts.remove(host);
        if (port != null) facts.removePort(port.id());
    }

    DataBus bus() {
        if (busRevision != facts.topologyRevision()) {
            bus = new DataBus(facts.cables(), facts.ports());
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

    /** The data port registered for an equipment host, or null. */
    DataPort port(long host) {
        return equipmentPorts.get(host);
    }
}
