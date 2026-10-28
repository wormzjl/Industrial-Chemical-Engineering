package com.wormzjl.icheme.network;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.science.equipment.ColumnDesign;
import com.wormzjl.icheme.science.equipment.ColumnTrays;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.Paint;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
import com.wormzjl.icheme.science.equipment.VesselPlacement;
import com.wormzjl.icheme.world.level.block.EquipmentStructure;
import com.wormzjl.icheme.world.level.block.entity.EquipmentConstructorBlockEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.HandlerThread;

import java.util.Optional;

/**
 * Equipment protocol {@value #PROTOCOL}: the constructor screen sends its design with what to do with it: keep it (the
 * screen closes), build it (Build) or import the nearest column calculator's design onto it (Import). Placing a vessel is
 * a world edit, like placing blocks; it involves no process state, so the server answers at once in chat. An import reads
 * the calculator's saved design (nothing is solved) and always answers the requesting player ({@link ImportAnswer}).
 */
public final class EquipmentNetwork {
    public static final String PROTOCOL = "equipment-13";
    /** A player edits or builds only within this distance of the constructor, blocks. */
    private static final double REACH = 8;
    /** A column calculator is imported from within this many blocks of the constructor. */
    public static final int IMPORT_REACH = 16;

    private EquipmentNetwork() {
    }

    /** What the server does with the design the screen sends: keep it, build it, or import a column calculator's design onto it. */
    public enum Action {
        KEEP, BUILD, IMPORT
    }

    /**
     * Client to server: a constructor's design ({@code form} is the ordinal of its {@link VesselPlacement.Form},
     * {@code trays} its column's {@link ColumnTrays#encode()},
     * {@code connections} its drum's {@link EquipmentShape.Connection#encode}, {@code paint} its {@link Paint#encode},
     * {@code bank} an air cooler's {@link ProcessUnit.Bank#encode}, {@code boot} whether a lying drum has one,
     * {@code outlet} its bottom outlet's quarter turns from the front, {@code turns} its column's turned nozzles
     * ({@link ColumnTrays#encodeTurns})), and what to do with it.
     */
    public record ConstructorPayload(BlockPos position, double diameter, int tangentLength, int form, int support, int forward, int right, int up,
                                     int[] trays, int[] connections, int[] paint, int[] bank, boolean boot, int outlet, int[] turns, Action action) implements CustomPacketPayload {
        public static final Type<ConstructorPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID, "equipment_constructor"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ConstructorPayload> STREAM_CODEC = new StreamCodec<>() {
            public ConstructorPayload decode(RegistryFriendlyByteBuf b) {
                return new ConstructorPayload(b.readBlockPos(), b.readDouble(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(),
                        b.readVarInt(), b.readVarIntArray(ColumnTrays.MAX_ENCODED), b.readVarIntArray(3 * EquipmentShape.MAX_CONNECTIONS), b.readVarIntArray(2), b.readVarIntArray(2), b.readBoolean(), b.readVarInt(),
                        b.readVarIntArray(3 * ColumnTrays.MAX_TURNS), b.readEnum(Action.class));
            }

            public void encode(RegistryFriendlyByteBuf b, ConstructorPayload p) {
                b.writeBlockPos(p.position);
                b.writeDouble(p.diameter);
                b.writeVarInt(p.tangentLength);
                b.writeVarInt(p.form);
                b.writeVarInt(p.support);
                b.writeVarInt(p.forward);
                b.writeVarInt(p.right);
                b.writeVarInt(p.up);
                b.writeVarIntArray(p.trays);
                b.writeVarIntArray(p.connections);
                b.writeVarIntArray(p.paint);
                b.writeVarIntArray(p.bank);
                b.writeBoolean(p.boot);
                b.writeVarInt(p.outlet);
                b.writeVarIntArray(p.turns);
                b.writeEnum(p.action);
            }
        };

        public static ConstructorPayload of(BlockPos position, EquipmentConstructorBlockEntity.Design design, Action action) {
            var p = design.placement();
            return new ConstructorPayload(position, design.diameter(), design.tangentLength(), design.form().ordinal(), design.support(), p.forward(), p.right(), p.up(),
                    design.trays().encode(), EquipmentShape.Connection.encode(design.connections()), design.paint().encode(), design.bank().encode(), design.boot(), design.outlet(),
                    ColumnTrays.encodeTurns(design.turns()), action);
        }

        /**
         * The design this payload sends, with {@code stored}'s imported column: only an import sets it (plan section 4.5).
         *
         * @throws IllegalArgumentException for a field out of range
         */
        public EquipmentConstructorBlockEntity.Design design(EquipmentConstructorBlockEntity.Design stored) {
            var forms = VesselPlacement.Form.values();
            if (form < 0 || form >= forms.length) throw new IllegalArgumentException("Unknown form " + form);
            return stored.keepingColumn(new EquipmentConstructorBlockEntity.Design(diameter, tangentLength, forms[form], support, new VesselPlacement(forward, right, up),
                    ColumnTrays.decode(trays), EquipmentShape.Connection.decode(connections), Paint.decode(paint), ProcessUnit.Bank.decode(bank), boot, outlet, null,
                    ColumnTrays.decodeTurns(turns)));
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Server to client, the answer to every import: what happened, and the constructor's design after it (the
     * {@link EquipmentConstructorBlockEntity#writeDesign} tags), absent when nothing was imported.
     */
    public record ImportAnswer(BlockPos position, Component message, Optional<CompoundTag> design) implements CustomPacketPayload {
        public static final Type<ImportAnswer> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID, "equipment_import_answer"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ImportAnswer> STREAM_CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC, ImportAnswer::position,
                ComponentSerialization.TRUSTED_STREAM_CODEC, ImportAnswer::message, ByteBufCodecs.OPTIONAL_COMPOUND_TAG, ImportAnswer::design, ImportAnswer::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(PROTOCOL).executesOn(HandlerThread.MAIN);
        registrar.playToServer(ConstructorPayload.TYPE, ConstructorPayload.STREAM_CODEC, EquipmentNetwork::constructor);
        registrar.playToClient(ImportAnswer.TYPE, ImportAnswer.STREAM_CODEC, (answer, context) -> com.wormzjl.icheme.client.gui.screens.EquipmentConstructorScreen.answered(
                answer.position(), answer.message(), answer.design().map(tag -> EquipmentConstructorBlockEntity.readDesign(tag, EquipmentConstructorBlockEntity.Design.fresh(), repair -> {
                }))));
    }

    /** Client: send a constructor's design, and what to do with it. */
    public static void send(BlockPos position, EquipmentConstructorBlockEntity.Design design, Action action) {
        PacketDistributor.sendToServer(ConstructorPayload.of(position, design, action));
    }

    /** The constructor a player may edit at {@code position}, or null. */
    private static EquipmentConstructorBlockEntity editable(ServerPlayer player, BlockPos position) {
        var level = player.serverLevel();
        if (!level.isLoaded(position) || !(level.getBlockEntity(position) instanceof EquipmentConstructorBlockEntity constructor)
                || player.distanceToSqr(position.getCenter()) > REACH * REACH || !player.mayBuild() || player.isSpectator() || !level.mayInteract(player, position))
            return null;
        return constructor;
    }

    /**
     * Applies the screen's design, then keeps it, builds it or imports onto it. An import is always answered, as the
     * screen waits for the answer; a design the server refuses or cannot reach answers it too.
     */
    private static void constructor(ConstructorPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        boolean importing = payload.action() == Action.IMPORT;
        var constructor = editable(player, payload.position());
        if (constructor == null) {
            if (importing) answer(player, payload.position(), Component.translatable("message.icheme.equipment.not_editable"), null);
            return;
        }
        EquipmentConstructorBlockEntity.Design design;
        try {
            design = payload.design(constructor.design());
        } catch (IllegalArgumentException invalid) {
            var message = Component.translatable("message.icheme.equipment.invalid", invalid.getMessage());
            if (importing) answer(player, payload.position(), message, null);
            else player.sendSystemMessage(message.withStyle(ChatFormatting.RED));
            return;
        }
        constructor.apply(design);
        switch (payload.action()) {
            case KEEP -> {
            }
            case BUILD -> build(player, constructor, design);
            case IMPORT -> importColumn(player, constructor);
        }
    }

    private static void build(ServerPlayer player, EquipmentConstructorBlockEntity constructor, EquipmentConstructorBlockEntity.Design design) {
        if (!design.buildable()) {
            player.sendSystemMessage(Component.translatable("message.icheme.equipment.import_first", IMPORT_REACH).withStyle(ChatFormatting.RED));
            return;
        }
        var vessel = constructor.vessel(design);
        // Overlapping side connections are a design the screen shows but the server does not build.
        if (!vessel.shape().clashes().isEmpty()) {
            player.sendSystemMessage(Component.translatable("message.icheme.equipment.clash").withStyle(ChatFormatting.RED));
            return;
        }
        // The calculator checked the nozzles on the least skirt; this column stands on the constructor's.
        var misfit = design.form().column() ? ColumnDesign.misfit(vessel.shape()) : java.util.Optional.<String>empty();
        if (misfit.isPresent()) {
            player.sendSystemMessage(Component.translatable("message.icheme.equipment.misfit", misfit.get()).withStyle(ChatFormatting.RED));
            return;
        }
        try {
            int placed = EquipmentStructure.place(player.serverLevel(), vessel.origin(), vessel.shape(), design.paint(), Integer.MAX_VALUE);
            var at = vessel.origin().toShortString();
            player.sendSystemMessage(design.form().column()
                    ? Component.translatable("message.icheme.equipment.built_column", vessel.shape().diameter(), vessel.shape().trays().trays() + 2, placed, at)
                    : vessel.shape().designed() ? Component.translatable("message.icheme.equipment.built_unit",
                    Component.translatable("gui.icheme.vessel.title." + vessel.shape().kind().name().toLowerCase(java.util.Locale.ROOT)), placed, at)
                    : Component.translatable("message.icheme.equipment.built", design.diameter(), design.tangentLength(), placed, at));
        } catch (IllegalStateException blocked) {
            player.sendSystemMessage(Component.translatable("message.icheme.equipment.blocked", blocked.getMessage()).withStyle(ChatFormatting.RED));
        }
    }

    /** Imports the nearest column calculator's saved design onto the constructor's design, which the screen just sent. */
    private static void importColumn(ServerPlayer player, EquipmentConstructorBlockEntity constructor) {
        var at = constructor.getBlockPos();
        var calculator = constructor.importColumn(IMPORT_REACH);
        if (calculator.isEmpty()) {
            answer(player, at, Component.translatable("message.icheme.equipment.no_calculator", IMPORT_REACH), null);
            return;
        }
        if (calculator.get().isEmpty()) {
            answer(player, at, Component.translatable("message.icheme.equipment.no_design"), null);
            return;
        }
        var design = calculator.get().get();
        constructor.apply(design);
        var column = design.column();
        // The calculator's numbering: its trays and the condenser and reboiler.
        answer(player, at, Component.translatable("message.icheme.equipment.imported", column.trays().trays() + 2, column.trays().feed() + 1, column.diameter()), design);
    }

    /** Answers an import: the message, and the constructor's design when one was imported (null: nothing was). */
    private static void answer(ServerPlayer player, BlockPos at, Component message, EquipmentConstructorBlockEntity.@org.jetbrains.annotations.Nullable Design design) {
        Optional<CompoundTag> tags = Optional.empty();
        if (design != null) {
            var tag = new CompoundTag();
            EquipmentConstructorBlockEntity.writeDesign(tag, design);
            tags = Optional.of(tag);
        }
        PacketDistributor.sendToPlayer(player, new ImportAnswer(at, message, tags));
    }
}
