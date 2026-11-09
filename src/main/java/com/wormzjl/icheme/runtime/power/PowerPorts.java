package com.wormzjl.icheme.runtime.power;

import com.wormzjl.icheme.runtime.control.*;
import com.wormzjl.icheme.science.equipment.*;
import com.wormzjl.icheme.world.level.block.*;
import com.wormzjl.icheme.world.level.block.entity.*;

import net.minecraft.core.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Physical FE terminals, independent of process readings and sensor identities. */
public final class PowerPorts {
    private PowerPorts() {}

    public static boolean eligible(BlockEntity be) {
        if (be instanceof CreativePowerBlockEntity) return true;
        if (be instanceof FluidDeviceBlockEntity pipe)
            return FluidDeviceBlock.hasPump(pipe.getBlockState())
                    || FluidDeviceBlock.hasCompressor(pipe.getBlockState());
        if (be instanceof EquipmentShellBlockEntity shell && shell.shape() != null) {
            var terminal = ShapeRasterizer.terminal(shell.shape());
            return terminal != null
                    && terminal.equals(shell.cell())
                    && switch (shell.shape().kind()) {
                        case MOTOR, GENERATOR, AIR_COOLER, INDUCED_DRAFT_COOLING_TOWER, VERTICAL_HEATER, CABIN_HEATER -> true;
                        default -> false;
                    };
        }
        return false;
    }

    public static DataPort create(BlockEntity be) {
        var level = be.getLevel();
        if (level == null || !eligible(be)) return null;
        Vec3 centre;
        Direction outward;
        DataPort.Kind kind = DataPort.Kind.POWER_INPUT;
        if (be instanceof EquipmentShellBlockEntity shell) {
            var socket = SocketGeometry.power(shell.shape());
            if (socket == null) return null;
            centre = DataPorts.at(socket, shell.origin());
            outward = Direction.values()[socket.face()];
            if (shell.shape().kind() == EquipmentShape.Kind.GENERATOR)
                kind = DataPort.Kind.POWER_OUTPUT;
        } else if (be instanceof FluidDeviceBlockEntity pipe) {
            var socket =
                    InlineMoverGeometry.socket(
                            DataPorts.fittingKey(pipe),
                            FluidDeviceBlock.hasCompressor(pipe.getBlockState()),
                            true);
            centre = DataPorts.at(socket, pipe.getBlockPos());
            outward = Direction.values()[socket.face()];
        } else {
            centre = be.getBlockPos().getCenter().add(.52, 0, 0);
            outward = Direction.EAST;
            kind = DataPort.Kind.POWER_OUTPUT;
        }
        return DataPorts.power(UUID.randomUUID(), kind, level, be.getBlockPos(), centre, outward);
    }

    public static DataPort refresh(DataPort old, BlockEntity be, boolean wired) {
        var current = create(be);
        if (current == null) return old;
        return DataPorts.remount(old, current, wired);
    }

    /**
     * Six centred supply faces derived from the saved primary identity; its original socket and
     * wires are retained.
     */
    public static List<DataPort> omnidirectional(DataPort primary) {
        if (primary.kind() != DataPort.Kind.POWER_OUTPUT)
            throw new IllegalArgumentException("Not a supply socket");
        var result = new ArrayList<DataPort>(6);
        for (var face : Direction.values()) {
            if (face == primary.face().getOpposite()) {
                result.add(primary);
                continue;
            }
            var id =
                    UUID.nameUUIDFromBytes(
                            (primary.id() + ":power:" + face.getName())
                                    .getBytes(StandardCharsets.UTF_8));
            result.add(
                    new DataPort(
                            id,
                            DataPort.Kind.POWER_OUTPUT,
                            0,
                            primary.dimension(),
                            primary.owner(),
                            primary.owner()
                                    .getCenter()
                                    .add(Vec3.atLowerCornerOf(face.getNormal()).scale(.52)),
                            primary.owner().relative(face),
                            face.getOpposite(),
                            0,
                            0));
        }
        return List.copyOf(result);
    }

    /**
     * An ordinary FE-capable block face is also a legal endpoint; previews never create saved
     * ports.
     */
    public static DataPort external(Level level, BlockPos owner, Direction face) {
        if (!level.hasChunkAt(owner)) return null;
        var be = level.getBlockEntity(owner);
        if (be instanceof ConduitBlockEntity
                || be instanceof PipeRackBlockEntity
                || be instanceof EquipmentShellBlockEntity
                || be instanceof FluidDeviceBlockEntity
                || be instanceof ControlPanelBlockEntity
                || be instanceof CreativePowerBlockEntity) return null;
        if ((!level.isClientSide() || be == null)
                && level.getCapability(Capabilities.EnergyStorage.BLOCK, owner, face) == null)
            return null;
        var id =
                UUID.nameUUIDFromBytes(
                        ("icheme:fe:"
                                        + level.dimension().location()
                                        + ":"
                                        + owner.asLong()
                                        + ":"
                                        + face.getName())
                                .getBytes(StandardCharsets.UTF_8));
        return new DataPort(
                id,
                DataPort.Kind.POWER_BOTH,
                0,
                level.dimension().location().toString(),
                owner,
                owner.getCenter().add(Vec3.atLowerCornerOf(face.getNormal()).scale(.52)),
                owner.relative(face),
                face.getOpposite(),
                0,
                0);
    }
}
