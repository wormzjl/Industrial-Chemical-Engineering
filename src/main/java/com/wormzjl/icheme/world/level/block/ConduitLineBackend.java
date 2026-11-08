package com.wormzjl.icheme.world.level.block;

import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.runtime.control.*;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.instrument.Instrument;
import com.wormzjl.icheme.runtime.power.PowerPorts;
import com.wormzjl.icheme.science.equipment.*;
import com.wormzjl.icheme.world.level.block.entity.*;

import net.minecraft.core.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Data/FE facts, live socket capability and block copies. No command, route or inventory policy.
 */
public final class ConduitLineBackend implements LineBackend {
    static final ConduitLineBackend INSTANCE = new ConduitLineBackend();

    private ConduitLineBackend() {}

    public static List<DataBus.Cable> cables(Level level, BlockPos pos) {
        if (!level.hasChunkAt(pos)) return List.of();
        var be = level.getBlockEntity(pos);
        return be instanceof ConduitBlockEntity wire
                ? List.of(wire.cable())
                : be instanceof PipeRackBlockEntity rack ? rack.cables() : List.of();
    }

    public List<Host> hosts(Level level, BlockPos pos, LineProfile profile) {
        var out = new ArrayList<Host>();
        for (var c : cables(level, pos))
            if (c.geometry().medium() == profile.medium()) {
                var key = c.geometry();
                if (c.site().lane() > 0
                        && level.getBlockEntity(pos) instanceof PipeRackBlockEntity rack)
                    key = ConduitGeometry.laneKey(rack.rack(), rack.cableLane(c), key);
                out.add(
                        new Host(
                                pos,
                                c.site().lane(),
                                0,
                                key.medium().outside(),
                                c.geometry().joints(),
                                c.arms(),
                                key.offsets(),
                                5,
                                63,
                                true,
                                false,
                                true,
                                false));
            }
        return List.copyOf(out);
    }

    public int openFaces(Level level, Host host, LineProfile profile) {
        var c =
                cables(level, host.cell()).stream()
                        .filter(w -> w.site().lane() == host.lane())
                        .findFirst()
                        .orElse(null);
        if (c == null) return 0;
        return CableConnections.openFaces(c, p -> cables(level, p), id -> null);
    }

    public PortResult socket(Level level, LineTarget target, LineProfile.Settings settings) {
        var be = level.getBlockEntity(target.clicked());
        DataPort port = null;
        if (be instanceof ControlPanelBlockEntity panel) port = panel.dataPort();
        else if (be instanceof Instrument.Holder holder) {
            if (settings.profile() == LineProfile.POWER && PowerPorts.eligible(be))
                port =
                        holder.powerPorts().stream()
                                .filter(p -> target.port() == null || p.id().equals(target.port()))
                                .findFirst()
                                .orElse(null);
            else port = holder.instruments().dataPort();
        } else if (settings.profile() == LineProfile.POWER)
            port = PowerPorts.external(level, target.clicked(), target.face());
        boolean blocked =
                be instanceof EquipmentShellBlockEntity
                        || level.getBlockState(target.clicked()).getBlock()
                                instanceof EquipmentInteriorBlock
                        || port != null
                        || target.port() != null;
        if (port == null
                || port.kind().medium() != settings.profile().medium()
                || target.port() != null && !target.port().equals(port.id())
                || !port.owner().equals(target.clicked())
                || !port.dimension().equals(level.dimension().location().toString()))
            return new PortResult(blocked, null, PipeAnchor.Refusal.FITTING);
        if (target.port() == null
                && !(settings.profile() == LineProfile.POWER && !(be instanceof Instrument.Holder)))
            return new PortResult(true, null, PipeAnchor.Refusal.FITTING);
        if (!level.hasChunkAt(port.cell()))
            return new PortResult(true, null, PipeAnchor.Refusal.OUTSIDE);
        return new PortResult(true, socket(port), null);
    }

    static Socket socket(DataPort port) {
        var n = port.face().getOpposite();
        var delta = port.cablePoint().subtract(port.centre());
        var points =
                ConduitGeometry.leadPoints(
                        new double[] {n.getStepX(), n.getStepY(), n.getStepZ()},
                        new double[] {delta.x, delta.y, delta.z},
                        new double[] {n.getStepX(), n.getStepY(), n.getStepZ()});
        var lead =
                new LinePlacement.Lead(
                        port.owner(),
                        port.cell(),
                        port.centre(),
                        port.kind().medium().outside(),
                        points.stream().map(p -> port.centre().add(p[0], p[1], p[2])).toList());
        return new Socket(
                port.id(),
                port.owner(),
                port.centre(),
                port.cell(),
                port.face(),
                DataPorts.packed(port),
                lead);
    }

    public static List<DataBus.Cable> wires(Level level, LineLayout layout, LineProfile profile) {
        var out = new ArrayList<DataBus.Cable>();
        String dim = level.dimension().location().toString();
        for (var cell : layout.cells())
            out.add(
                    wire(
                            new DataBus.Site(dim, cell.site().cell(), cell.site().lane()),
                            cell.joints(),
                            cell.arms(),
                            cell.points(),
                            Map.of(),
                            layout,
                            profile));
        for (var edit : layout.hosts()) {
            var old =
                    cables(level, edit.before().cell()).stream()
                            .filter(c -> c.site().lane() == edit.before().lane())
                            .findFirst()
                            .orElseThrow();
            out.add(
                    wire(
                            old.site(),
                            edit.joints(),
                            old.arms(),
                            edit.points(),
                            old.endpoints(),
                            layout,
                            profile));
        }
        return List.copyOf(out);
    }

    private static DataBus.Cable wire(
            DataBus.Site site,
            int joints,
            int arms,
            long points,
            Map<Direction, UUID> previous,
            LineLayout layout,
            LineProfile profile) {
        var endpoints = new EnumMap<Direction, UUID>(Direction.class);
        endpoints.putAll(previous);
        for (var end : layout.endpoints())
            if (end.site().cell().equals(site.cell()) && end.site().lane() == site.lane())
                endpoints.put(Direction.values()[end.face()], end.socket());
        return new DataBus.Cable(
                site, new ConduitGeometry.Key(joints, points, profile.medium()), arms, endpoints);
    }

    public LinePlacement.Work register(
            Level level,
            FluidWorldAuthority world,
            LineLayout layout,
            LineProfile.Settings settings) {
        var edits = new ArrayList<LinePlacement.Edit>();
        var fresh = new HashSet<LineLayout.Site>();
        layout.cells().forEach(c -> fresh.add(c.site()));
        for (var wire : wires(level, layout, settings.profile())) {
            var site = new LineLayout.Site(wire.site().cell(), wire.site().lane());
            var state =
                    fresh.contains(site) && site.lane() == 0
                            ? (settings.profile() == LineProfile.POWER
                                            ? ModBlocks.POWER_CABLE
                                            : ModBlocks.DATA_CABLE)
                                    .get()
                                    .defaultBlockState()
                            : null;
            edits.add(
                    new LinePlacement.Edit(
                            site.cell(),
                            state,
                            Block.UPDATE_CLIENTS,
                            entity -> {
                                if (entity instanceof ConduitBlockEntity block)
                                    block.configure(wire);
                                else if (entity instanceof PipeRackBlockEntity rack) {
                                    world.controls().cable(wire);
                                    var all = new ArrayList<>(rack.cables());
                                    all.removeIf(c -> c.site().equals(wire.site()));
                                    all.add(wire);
                                    rack.cables(all);
                                } else
                                    throw new IllegalStateException(
                                            "Validated line host disappeared");
                            }));
        }
        return new LinePlacement.Work(edits, () -> {});
    }

    public void forgetLane(Level level, FluidWorldAuthority world, Host host) {
        var rack = (PipeRackBlockEntity) level.getBlockEntity(host.cell());
        var old =
                rack.cables().stream()
                        .filter(c -> c.site().lane() == host.lane())
                        .findFirst()
                        .orElseThrow();
        world.controls().removeCable(old.site());
        rack.cables(rack.cables().stream().filter(c -> c.site().lane() != host.lane()).toList());
    }

    public void refresh(Level level, Collection<BlockPos> cells) {
        for (var at : cells)
            if (level.getBlockEntity(at) instanceof PipeRackBlockEntity rack) rack.redraw();
    }

    public UUID binding(Level level, Host host, int face) {
        return cables(level, host.cell()).stream()
                .filter(c -> c.site().lane() == host.lane())
                .map(c -> c.endpoints().get(Direction.values()[face]))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    public void registerSocket(Level level, FluidWorldAuthority world, Socket socket) {
        if (socket == null || socket.id() == null) return;
        DataPort port = null;
        var be = level.getBlockEntity(socket.owner());
        if (be instanceof ControlPanelBlockEntity panel) port = panel.dataPort();
        else if (be instanceof Instrument.Holder holder) {
            port = holder.instruments().dataPort();
            if (port == null || !port.id().equals(socket.id()))
                port =
                        holder.powerPorts().stream()
                                .filter(p -> p.id().equals(socket.id()))
                                .findFirst()
                                .orElse(null);
        } else port = PowerPorts.external(level, socket.owner(), socket.face().getOpposite());
        if (port == null || !port.id().equals(socket.id()))
            throw new IllegalStateException("Validated service socket disappeared");
        world.controls().facts().port(port);
    }

    public List<Socket> socketsAt(
            Level level, FluidWorldAuthority world, BlockPos cell, LineProfile.Settings settings) {
        return world.controls().facts().ports().values().stream()
                .filter(
                        p ->
                                p.dimension().equals(level.dimension().location().toString())
                                        && p.cell().equals(cell)
                                        && p.kind().medium() == settings.profile().medium())
                .sorted(Comparator.comparing(p -> p.id().toString()))
                .map(
                        p ->
                                socket(
                                                level,
                                                new LineTarget(
                                                        p.id(),
                                                        p.owner(),
                                                        p.face().getOpposite(),
                                                        new Vec3(.5, .5, .5),
                                                        0),
                                                settings)
                                        .socket())
                .filter(Objects::nonNull)
                .toList();
    }
}
