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
import net.neoforged.neoforge.energy.IEnergyStorage;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Physical FE terminals, independent of process readings and sensor identities. */
public final class PowerPorts {
    private PowerPorts() {}

    public static final IEnergyStorage CREATIVE = new IEnergyStorage() {
        @Override public int receiveEnergy(int amount, boolean simulate) { return 0; }
        @Override public int extractEnergy(int amount, boolean simulate) { return Math.max(0, amount); }
        @Override public int getEnergyStored() { return Integer.MAX_VALUE; }
        @Override public int getMaxEnergyStored() { return Integer.MAX_VALUE; }
        @Override public boolean canExtract() { return true; }
        @Override public boolean canReceive() { return false; }
    };

    public static boolean eligible(BlockEntity be) {
        if (be instanceof CreativePowerBlockEntity) return true;
        if (be instanceof FluidDeviceBlockEntity pipe)
            return FluidDeviceBlock.hasPump(pipe.getBlockState())
                    || FluidDeviceBlock.hasCompressor(pipe.getBlockState());
        if (be instanceof EquipmentShellBlockEntity shell && shell.shape() != null) {
            var terminal = ShapeRasterizer.terminal(shell.shape());
            return terminal != null
                    && terminal.equals(shell.cell())
                    && eligible(shell.shape());
        }
        return false;
    }

    static boolean eligible(EquipmentShape shape) {
        return switch (shape.kind()) {
            case MOTOR, GENERATOR, AIR_COOLER, INDUCED_DRAFT_COOLING_TOWER, VERTICAL_HEATER, CABIN_HEATER -> true;
            case BATCH_STILL -> EquipmentServices.electricStill(shape);
            default -> false;
        };
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
        } else return creative(level.dimension().location().toString(), be.getBlockPos());
        return DataPorts.power(UUID.randomUUID(), kind, level, be.getBlockPos(), centre, outward);
    }

    static DataPort creative(String dimension, BlockPos owner) {
        return new DataPort(UUID.randomUUID(), DataPort.Kind.POWER_OUTPUT, 0, dimension, owner,
                owner.getCenter().add(.52, 0, 0), owner.east(), Direction.WEST, 0, 0);
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
                || be instanceof com.wormzjl.icheme.runtime.control.DataPort.Owner
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
