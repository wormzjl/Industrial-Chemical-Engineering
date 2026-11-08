package com.wormzjl.icheme.network;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;

import java.util.HashSet;
import java.util.List;

/**
 * Explicit, bounded subscriptions to published shaft speeds. No inventories or solver work cross
 * this channel.
 */
public final class RotationNetwork {
    public static final int MAXIMUM_WATCHED = 256;
    public static final double RANGE = 96;

    private RotationNetwork() {}

    public record Watch(List<BlockPos> watch, List<BlockPos> unwatch)
            implements CustomPacketPayload {
        public static final Type<Watch> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID, "rotation_watch"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Watch> CODEC =
                new StreamCodec<>() {
                    public Watch decode(RegistryFriendlyByteBuf b) {
                        return new Watch(read(b), read(b));
                    }

                    public void encode(RegistryFriendlyByteBuf b, Watch p) {
                        write(b, p.watch);
                        write(b, p.unwatch);
                    }
                };

        public Watch {
            watch = watch.stream().map(BlockPos::immutable).toList();
            unwatch = unwatch.stream().map(BlockPos::immutable).toList();
            for (var list : List.of(watch, unwatch))
                if (list.size() > MAXIMUM_WATCHED || new HashSet<>(list).size() != list.size())
                    throw new IllegalArgumentException("Invalid rotation watch list");
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** A single shaft's rpm, or a gearbox's input and output rpm. */
    public record State(BlockPos position, float rpm, float outputRpm)
            implements CustomPacketPayload {
        public static final Type<State> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID, "rotation_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, State> CODEC =
                new StreamCodec<>() {
                    public State decode(RegistryFriendlyByteBuf b) {
                        return new State(b.readBlockPos(), b.readFloat(), b.readFloat());
                    }

                    public void encode(RegistryFriendlyByteBuf b, State p) {
                        b.writeBlockPos(p.position);
                        b.writeFloat(p.rpm);
                        b.writeFloat(p.outputRpm);
                    }
                };

        public State {
            position = position.immutable();
            if (!Float.isFinite(rpm) || rpm < 0 || !Float.isFinite(outputRpm) || outputRpm < 0)
                throw new IllegalArgumentException("Invalid rotation speed");
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static List<BlockPos> read(RegistryFriendlyByteBuf b) {
        int count = b.readVarInt();
        if (count < 0 || count > MAXIMUM_WATCHED)
            throw new IllegalArgumentException("Too many watched rotations");
        var positions = new java.util.ArrayList<BlockPos>(count);
        for (int i = 0; i < count; i++) positions.add(b.readBlockPos());
        return positions;
    }

    private static void write(RegistryFriendlyByteBuf b, List<BlockPos> positions) {
        b.writeVarInt(positions.size());
        positions.forEach(b::writeBlockPos);
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var channel = event.registrar("rotation-1").executesOn(HandlerThread.MAIN);
        channel.playToServer(
                Watch.TYPE,
                Watch.CODEC,
                (p, context) -> {
                    if (context.player() instanceof ServerPlayer player)
                        FluidWorldAuthority.find(player.server)
                                .ifPresent(
                                        world -> world.watchRotations(player, p.watch, p.unwatch));
                });
        channel.playToClient(
                State.TYPE,
                State.CODEC,
                (p, context) -> com.wormzjl.icheme.client.RotationAnimation.accept(p));
    }

    public static void request(List<BlockPos> watch, List<BlockPos> unwatch) {
        if (!watch.isEmpty() || !unwatch.isEmpty())
            PacketDistributor.sendToServer(new Watch(watch, unwatch));
    }

    public static void send(ServerPlayer player, State state) {
        PacketDistributor.sendToPlayer(player, state);
    }
}
