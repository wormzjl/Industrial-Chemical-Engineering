package com.wormzjl.icheme.runtime.control;

import com.wormzjl.icheme.runtime.instrument.InstrumentHosts;
import com.wormzjl.icheme.runtime.instrument.Sensor;
import com.wormzjl.icheme.science.equipment.*;
import com.wormzjl.icheme.world.level.block.ControlPanelBlock;
import com.wormzjl.icheme.world.level.block.FluidDeviceBlock;
import com.wormzjl.icheme.world.level.block.entity.ControlPanelBlockEntity;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;
import com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity;

import net.minecraft.core.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/** Physical connectors use the same signed 1/32 m face coordinates as off-centre pipe entries. */
public final class DataPorts {
    private DataPorts() {}

    private record Mount(Vec3 centre, Direction outward) {}

    private static Mount equipmentMount(InstrumentHosts.Host host) {
        if (host.entity() instanceof EquipmentShellBlockEntity shell && shell.shape() != null) {
            var socket = SocketGeometry.data(shell.shape());
            if (socket != null)
                return new Mount(at(socket, shell.origin()), Direction.values()[socket.face()]);
        }
        if (host.pipe()) {
            if (host.entity() instanceof FluidDeviceBlockEntity pipe) {
                var state = pipe.getBlockState();
                var key = fittingKey(pipe);
                var socket =
                        FluidDeviceBlock.hasPressureValve(state)
                                ? ValveGeometry.socket(key)
                                : FluidDeviceBlock.hasPump(state)
                                                || FluidDeviceBlock.hasCompressor(state)
                                        ? InlineMoverGeometry.socket(
                                                key, FluidDeviceBlock.hasCompressor(state), false)
                                        : null;
                if (socket != null)
                    return new Mount(
                            at(socket, pipe.getBlockPos()), Direction.values()[socket.face()]);
            }
            for (var sensor : host.metadata().sensors())
                for (var mount : Sensor.mounts(host, sensor.kind()))
                    if (mount.id().equals(sensor.key().mount()))
                        return new Mount(
                                mount.point(.14, 0, 0),
                                Direction.getNearest(
                                        mount.right().x, mount.right().y, mount.right().z));
        }
        // A diverter valve (owner decision O2): its socket on a face its pipes leave free, on top
        // for a level inlet.
        if (host.entity() instanceof FluidDeviceBlockEntity valve
                && valve.getBlockState().getBlock() instanceof FluidDeviceBlock block
                && block.kind()
                        == com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind
                                .DIVERTER_VALVE) {
            var inlet = valve.getBlockState().getValue(FluidDeviceBlock.FACING);
            var used = EnumSet.of(inlet);
            for (var outlet :
                    com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.diverterOutlets(
                            com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction.valueOf(
                                    inlet.name())))
                used.add(Direction.valueOf(outlet.name()));
            var face =
                    used.contains(Direction.UP)
                            ? Arrays.stream(Direction.values())
                                    .filter(d -> !used.contains(d))
                                    .findFirst()
                                    .orElseThrow()
                            : Direction.UP;
            return new Mount(
                    valve.getBlockPos()
                            .getCenter()
                            .add(Vec3.atLowerCornerOf(face.getNormal()).scale(.51)),
                    face);
        }
        var mount =
                host.mounts().stream()
                        .filter(m -> !m.top())
                        .findFirst()
                        .orElse(host.mounts().stream().findFirst().orElse(null));
        var outward = mount == null ? Direction.NORTH : mount.face();
        var centre =
                mount == null
                        ? host.entity().getBlockPos().getCenter().add(0, .2, -.51)
                        : mount.centre()
                                .add(
                                        outward.getAxis() == Direction.Axis.X ? 0 : .28,
                                        0,
                                        outward.getAxis() == Direction.Axis.X ? .28 : 0);
        return new Mount(centre, outward);
    }

    public static DataPort equipment(InstrumentHosts.Host host, boolean actuator) {
        var m = equipmentMount(host);
        return create(
                UUID.randomUUID(),
                actuator ? DataPort.Kind.ACTUATOR : DataPort.Kind.SENSOR,
                host.identity(),
                host.entity().getLevel(),
                host.entity().getBlockPos(),
                m.centre(),
                m.outward());
    }

    public static DataPort refresh(
            DataPort old, InstrumentHosts.Host host, java.util.function.Predicate<UUID> wired) {
        var m = equipmentMount(host);
        boolean connected = wired.test(old.id());
        // A wired drum terminal keeps the side it was built on while its socket stays on that side.
        if (connected
                && host.entity() instanceof EquipmentShellBlockEntity shell
                && shell.shape() != null
                && shell.shape().kind() == EquipmentShape.Kind.DRUM) {
            var socket = SocketGeometry.data(shell.shape());
            if (socket != null && socket.face() == old.outward().ordinal())
                m = new Mount(at(socket, shell.origin()), old.outward());
        }
        return remount(
                old,
                create(
                        old.id(),
                        old.kind(),
                        old.host(),
                        host.entity().getLevel(),
                        host.entity().getBlockPos(),
                        m.centre(),
                        m.outward()),
                connected);
    }

    /**
     * Existing cable face points are built facts. Do not swap a wired connector to another side of
     * its equipment.
     */
    public static DataPort remount(DataPort old, DataPort current, boolean wired) {
        if (wired
                && (old.face() != current.face()
                        || current.centre().distanceToSqr(old.cell().getCenter()) > 25)) return old;
        return new DataPort(
                old.id(),
                current.kind(),
                current.host(),
                current.dimension(),
                current.owner(),
                current.centre(),
                wired ? old.cell() : current.cell(),
                wired ? old.face() : current.face(),
                wired ? old.u() : current.u(),
                wired ? old.v() : current.v());
    }

    public static DataPort motor(
            EquipmentShellBlockEntity cell, com.wormzjl.icheme.runtime.fluid.ShaftUnit unit) {
        return equipment(new InstrumentHosts.Host(unit.id(), cell, List.of(), false), true);
    }

    public static DataPort panel(ControlPanelBlockEntity panel, UUID identity) {
        var outward = panel.getBlockState().getValue(ControlPanelBlock.FACING).getOpposite();
        var normal = Vec3.atLowerCornerOf(outward.getNormal());
        return create(
                identity,
                panel.dcs() ? DataPort.Kind.DCS : DataPort.Kind.CONTROLLER,
                0,
                panel.getLevel(),
                panel.getBlockPos(),
                panel.getBlockPos()
                        .getCenter()
                        .add(normal.scale((panel.dcs() ? .375 : .3125) + .005)),
                outward);
    }

    public static DataPort power(
            UUID id,
            DataPort.Kind kind,
            Level level,
            BlockPos owner,
            Vec3 centre,
            Direction outward) {
        if (!kind.power()) throw new IllegalArgumentException("Not a power port");
        return create(id, kind, 0, level, owner, centre, outward);
    }

    private static DataPort create(
            UUID id,
            DataPort.Kind kind,
            long host,
            Level level,
            BlockPos owner,
            Vec3 centre,
            Direction outward) {
        var normal = Vec3.atLowerCornerOf(outward.getNormal());
        // A gland may overlap its neighbouring pipe face, but an empty overhang cell must not put
        // the entry behind it.
        var ahead = centre.add(normal.scale(-.075));
        int axis = outward.getAxis().ordinal();
        int boundary =
                outward.getAxisDirection() == Direction.AxisDirection.POSITIVE
                        ? (int) Math.ceil(ahead.get(outward.getAxis()))
                        : (int) Math.floor(ahead.get(outward.getAxis())) - 1;
        var cell =
                new BlockPos(
                        axis == 0 ? boundary : (int) Math.floor(centre.x),
                        axis == 1 ? boundary : (int) Math.floor(centre.y),
                        axis == 2 ? boundary : (int) Math.floor(centre.z));
        var first = cell;
        // Only clear cells belonging to this casting. An unrelated wall must remain an obstacle,
        // never become a long invisible jump to the first empty cell beyond it. The complete lead
        // is checked by LinePlacement.
        var casting =
                level.getBlockEntity(owner) instanceof EquipmentShellBlockEntity shell
                        ? shell
                        : null;
        for (int step = 0;
                step < 8 && level.hasChunkAt(cell) && !level.getBlockState(cell).canBeReplaced();
                step++) {
            var be = level.getBlockEntity(cell);
            boolean own =
                    cell.equals(owner)
                            || casting != null
                                    && be instanceof EquipmentShellBlockEntity shell
                                    && casting.origin().equals(shell.origin())
                                    && Objects.equals(casting.shape(), shell.shape());
            if (!own) break;
            cell = cell.relative(outward);
        }
        if (centre.distanceToSqr(cell.getCenter()) > 16) cell = first;
        double[] local = {
            centre.x - cell.getX() - .5, centre.y - cell.getY() - .5, centre.z - cell.getZ() - .5
        };
        int u = (int) Math.clamp(Math.round(local[(axis + 1) % 3] * 32), -14, 14),
                v = (int) Math.clamp(Math.round(local[(axis + 2) % 3] * 32), -14, 14);
        return new DataPort(
                id,
                kind,
                host,
                level.dimension().location().toString(),
                owner,
                centre,
                cell,
                outward.getOpposite(),
                u,
                v);
    }

    /**
     * The line key an inline fitting's sockets are placed by: the pipe's run, joints and size
     * (the socket geometry reads only the run and the size).
     */
    public static LineGeometry.Key fittingKey(FluidDeviceBlockEntity pipe) {
        var state = pipe.getBlockState();
        return new LineGeometry.Key(
                pipe.kind(),
                state.getValue(FluidDeviceBlock.FACING).ordinal(),
                FluidDeviceBlock.connectionMask(state),
                PipeSizes.code(PipeSizes.outside(pipe.bore())),
                0,
                0,
                false);
    }

    /** A socket datum in world coordinates, for an owner whose local frame starts at {@code origin}. */
    public static Vec3 at(SocketGeometry.Socket socket, BlockPos origin) {
        return new Vec3(
                socket.x() + origin.getX(), socket.y() + origin.getY(), socket.z() + origin.getZ());
    }

    public static int packed(DataPort port) {
        return (port.u() & 31) | ((port.v() & 31) << 5);
    }
}
