package com.wormzjl.icheme.network;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.runtime.pollution.PollutionMaps;
import com.wormzjl.icheme.science.pollution.MapColumns;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;

import java.util.Objects;

/**
 * The pollution map's wire (batch 2026-11-15-pollution-system, WP9; plan section 12), on the mod's protocol
 * {@link NetworkProtocol#VERSION}. A client whose map is open asks with {@link Request}{@code (true)} and ends the ask with
 * {@link Request}{@code (false)}; while it asks, the server answers with {@link Answer}: at once (a read of the installed
 * field) and then once a field pass ({@code PollutionMaps}). No other client receives anything. The client handler stays
 * behind a lambda, so a dedicated server never loads a client class.
 */
public final class PollutionMapNetwork {
    /** The longest dimension id on the wire. */
    public static final int DIMENSION_MAX = 256;
    /** Bytes of one column on the wire: chunk x, chunk z, the four bytes. */
    static final int ENTRY_BYTES = 12;

    private PollutionMapNetwork() {
    }

    /** The map opened ({@code true}) or closed ({@code false}). */
    public record Request(boolean watching) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID, "pollution_map_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> STREAM_CODEC = new StreamCodec<>() {
            public Request decode(RegistryFriendlyByteBuf b) {
                return new Request(b.readBoolean());
            }

            public void encode(RegistryFriendlyByteBuf b, Request p) {
                b.writeBoolean(p.watching);
            }
        };

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * The polluted chunk columns around the player, the nearest first, at most {@link MapColumns#MAXIMUM_ENTRIES}: each
     * column ({@link MapColumns#column}) with its four bytes ({@link MapColumns#value}). {@code denied}: the player may not
     * see the overlay ({@code pollution.mapOverlay}); then there are no columns.
     */
    public record Answer(String dimension, boolean denied, long[] columns, int[] values) implements CustomPacketPayload {
        public static final Type<Answer> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID, "pollution_map_answer"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Answer> STREAM_CODEC = new StreamCodec<>() {
            public Answer decode(RegistryFriendlyByteBuf b) {
                String dimension = b.readUtf(DIMENSION_MAX);
                boolean denied = b.readBoolean();
                int count = Wire.count(b, MapColumns.MAXIMUM_ENTRIES, ENTRY_BYTES, "pollution map column");
                if (denied && count != 0) {
                    throw new DecoderException("A denied pollution map answer holds " + count + " columns");
                }
                long[] columns = new long[count];
                int[] values = new int[count];
                for (int i = 0; i < count; i++) {
                    int x = b.readInt();
                    int z = b.readInt();
                    columns[i] = MapColumns.column(x, z);
                    values[i] = b.readInt();
                }
                return new Answer(dimension, denied, columns, values);
            }

            public void encode(RegistryFriendlyByteBuf b, Answer p) {
                b.writeUtf(p.dimension, DIMENSION_MAX);
                b.writeBoolean(p.denied);
                b.writeVarInt(p.columns.length);
                for (int i = 0; i < p.columns.length; i++) {
                    b.writeInt(MapColumns.x(p.columns[i]));
                    b.writeInt(MapColumns.z(p.columns[i]));
                    b.writeInt(p.values[i]);
                }
            }
        };

        public Answer {
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(columns, "columns");
            Objects.requireNonNull(values, "values");
            if (columns.length != values.length || columns.length > MapColumns.MAXIMUM_ENTRIES || denied && columns.length != 0) {
                throw new IllegalArgumentException("Invalid pollution map answer: " + columns.length + " columns, " + values.length
                        + " values, denied " + denied);
            }
        }

        /** The answer to a player who may not see the overlay. */
        public static Answer refused(String dimension) {
            return new Answer(dimension, true, new long[0], new int[0]);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(NetworkProtocol.VERSION).executesOn(HandlerThread.MAIN);
        registrar.playToServer(Request.TYPE, Request.STREAM_CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                PollutionMaps.request(player, payload.watching());
            }
        });
        registrar.playToClient(Answer.TYPE, Answer.STREAM_CODEC,
                (payload, context) -> com.wormzjl.icheme.client.pollution.PollutionMapStore.accept(payload));
    }

    /** The client's ask: the map opened or closed. */
    public static void request(boolean watching) {
        PacketDistributor.sendToServer(new Request(watching));
    }

    public static void send(ServerPlayer player, Answer answer) {
        PacketDistributor.sendToPlayer(player, answer);
    }
}
