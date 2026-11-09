package com.wormzjl.icheme.network;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.equipment.ColumnDesign;
import com.wormzjl.icheme.science.equipment.MachineDuty;
import com.wormzjl.icheme.science.equipment.ColumnTrays;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.Paint;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
import com.wormzjl.icheme.science.equipment.SentDesign;
import com.wormzjl.icheme.science.equipment.VesselPlacement;
import com.wormzjl.icheme.science.flowsheet.sizing.SheetSizing;
import com.wormzjl.icheme.world.level.block.EquipmentStructure;
import com.wormzjl.icheme.world.level.block.entity.EquipmentConstructorBlockEntity;
import com.wormzjl.icheme.world.level.block.entity.ProcessSimulatorBlockEntity;
import com.wormzjl.icheme.world.level.block.entity.SentDesignTags;
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

import java.util.List;
import java.util.Optional;

/**
 * Equipment protocol {@value #PROTOCOL}: the constructor screen sends its design with what to do with it: keep it (the
 * screen closes) or build it (Build). Placing a vessel is a world edit, like placing blocks; it involves no process state,
 * so the server answers at once in chat. Import (protocol 16, batch 2026-11-02-process-simulator S5) lists the designs the
 * nearest process simulator holds ({@link ImportList}, answered by {@link ImportListAnswer}), then lands the chosen one on
 * the draft ({@link ImportChoice}); both read what the block stores (nothing is solved) and always answer the requesting
 * player, the choice with {@link ImportAnswer}, as a column template does ({@link TemplatePayload}, protocol 14). A cooling
 * tower's cell bank rides on the form (protocol 15). A device's name (protocol 17, N1): {@link Rename} names a placed
 * structure or one-block device, and {@link ImportChoice} carries the name the Import page listed, which the built
 * structure takes. A machine's design point rides on the constructor's design too (protocol 18, batch
 * 2026-11-03-rotating-machinery), and a batch still's heater, condenser and stages (protocol 22, batch 2026-11-09-batch-distiller, W6).
 */
public final class EquipmentNetwork {
    public static final String PROTOCOL = "equipment-22";
    /** A player edits or builds only within this distance of the constructor, blocks. */
    private static final double REACH = 8;
    /** A process simulator's designs are imported from within this many blocks of the constructor. */
    public static final int IMPORT_REACH = 16;

    private EquipmentNetwork() {
    }

    /** What the server does with the design the screen sends: keep it, or build it. */
    public enum Action {
        KEEP, BUILD
    }

    /**
     * Client to server: a constructor's design ({@code form} is the ordinal of its {@link VesselPlacement.Form},
     * {@code trays} its column's {@link ColumnTrays#encode()},
     * {@code connections} its drum's {@link EquipmentShape.Connection#encode}, {@code paint} its {@link Paint#encode},
     * {@code bank} an air cooler's {@link ProcessUnit.Bank#encode}, {@code boot} whether a lying drum has one,
     * {@code outlet} its bottom outlet's quarter turns from the front, {@code turns} its column's turned nozzles
     * ({@link ColumnTrays#encodeTurns}), a machine's design point {@code duty}), and what to do with it.
     */
    public record ConstructorPayload(BlockPos position, double diameter, int tangentLength, int form, int support, int forward, int right, int up,
                                     int[] trays, int[] connections, int[] paint, int[] bank, boolean boot, int outlet, int[] turns, Action action, int[] towerBank, MachineDuty duty,com.wormzjl.icheme.science.equipment.ExchangerType exchangerType,com.wormzjl.icheme.science.equipment.BatchStillOptions still) implements CustomPacketPayload {
        public ConstructorPayload(BlockPos position,double diameter,int tangentLength,int form,int support,int forward,int right,int up,int[] trays,int[] connections,int[] paint,int[] bank,boolean boot,int outlet,int[] turns,Action action,int[] towerBank,MachineDuty duty,com.wormzjl.icheme.science.equipment.ExchangerType exchangerType) {
            this(position,diameter,tangentLength,form,support,forward,right,up,trays,connections,paint,bank,boot,outlet,turns,action,towerBank,duty,exchangerType,com.wormzjl.icheme.science.equipment.BatchStillOptions.DEFAULT);
        }
        public ConstructorPayload(BlockPos position,double diameter,int tangentLength,int form,int support,int forward,int right,int up,int[] trays,int[] connections,int[] paint,int[] bank,boolean boot,int outlet,int[] turns,Action action,int[] towerBank,MachineDuty duty) {
            this(position,diameter,tangentLength,form,support,forward,right,up,trays,connections,paint,bank,boot,outlet,turns,action,towerBank,duty,com.wormzjl.icheme.science.equipment.ExchangerType.STANDARD);
        }
        public static final Type<ConstructorPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID, "equipment_constructor"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ConstructorPayload> STREAM_CODEC = new StreamCodec<>() {
            public ConstructorPayload decode(RegistryFriendlyByteBuf b) {
                return new ConstructorPayload(b.readBlockPos(), b.readDouble(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(),
                        b.readVarInt(), b.readVarIntArray(ColumnTrays.MAX_ENCODED), b.readVarIntArray(3 * EquipmentShape.MAX_CONNECTIONS), b.readVarIntArray(2), b.readVarIntArray(2), b.readBoolean(), b.readVarInt(),
                        b.readVarIntArray(3 * ColumnTrays.MAX_TURNS), b.readEnum(Action.class), b.readVarIntArray(2), readDuty(b),com.wormzjl.icheme.science.equipment.ExchangerType.valueOf(b.readUtf(64)),readStill(b));
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
                b.writeEnum(p.action);b.writeVarIntArray(p.towerBank);
                writeDuty(b, p.duty);
                b.writeUtf(p.exchangerType.name(),64);
                b.writeEnum(p.still.heater());b.writeEnum(p.still.condenser());b.writeVarInt(p.still.stages());
            }
        };

        public static ConstructorPayload of(BlockPos position, EquipmentConstructorBlockEntity.Design design, Action action) {
            var p = design.placement();
            return new ConstructorPayload(position, design.diameter(), design.tangentLength(), design.form().ordinal(), design.support(), p.forward(), p.right(), p.up(),
                    design.trays().encode(), EquipmentShape.Connection.encode(design.connections()), design.paint().encode(), design.bank().encode(), design.boot(), design.outlet(),
                    ColumnTrays.encodeTurns(design.turns()), action,design.towerBank().encode(), design.duty(),design.exchangerType(),design.still());
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
                    ColumnTrays.decodeTurns(turns),com.wormzjl.icheme.science.equipment.TowerBank.decode(towerBank), duty).withExchangerType(exchangerType).withStill(still));
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

    /** Client to server, sent after the screen's draft ({@link Action#KEEP}): the designs the nearest process simulator holds, please. */
    public record ImportList(BlockPos position) implements CustomPacketPayload {
        public static final Type<ImportList> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID, "equipment_import_list"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ImportList> STREAM_CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC, ImportList::position, ImportList::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Server to client, the answer to every {@link ImportList}: the designs as {@link SentDesignTags} writes them under {@code Designs}, the
     * calculator's saved design first; none, with why in {@code message}, when there is no simulator or it holds nothing.
     */
    public record ImportListAnswer(BlockPos position, Component message, CompoundTag designs) implements CustomPacketPayload {
        public static final Type<ImportListAnswer> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID, "equipment_import_list_answer"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ImportListAnswer> STREAM_CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC, ImportListAnswer::position,
                ComponentSerialization.TRUSTED_STREAM_CODEC, ImportListAnswer::message, ByteBufCodecs.COMPOUND_TAG, ImportListAnswer::designs, ImportListAnswer::new);

        /** The designs it carries; a design this build cannot read is left out. */
        public List<SentDesign> listed() {
            return SentDesignTags.read(SentDesignTags.unwrap(designs)).designs();
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Client to server, sent after the screen's draft ({@link Action#KEEP}): land design {@code index} of the list the screen showed on the
     * draft. Its tag and member must still name that design, else the list changed meanwhile and nothing lands. Answered by {@link ImportAnswer}.
     */
    public record ImportChoice(BlockPos position, int index, String tag, String member, String name) implements CustomPacketPayload {
        public static final Type<ImportChoice> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID, "equipment_import_choice"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ImportChoice> STREAM_CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC, ImportChoice::position,
                ByteBufCodecs.VAR_INT, ImportChoice::index, ByteBufCodecs.stringUtf8(SentDesign.MAX_TAG), ImportChoice::tag, ByteBufCodecs.stringUtf8(SentDesign.MAX_TAG),
                ImportChoice::member, ByteBufCodecs.stringUtf8(Rename.MAX_TEXT), ImportChoice::name, ImportChoice::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Deconstruct(BlockPos position, BlockPos origin, int shape, long request) implements CustomPacketPayload {
        public static final Type<Deconstruct> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID, "equipment_deconstruct"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Deconstruct> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Deconstruct::position, BlockPos.STREAM_CODEC, Deconstruct::origin,
                ByteBufCodecs.INT, Deconstruct::shape, ByteBufCodecs.VAR_LONG, Deconstruct::request, Deconstruct::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record DeconstructAnswer(BlockPos position, long request, boolean done, Component message) implements CustomPacketPayload {
        public static final Type<DeconstructAnswer> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID, "equipment_deconstruct_answer"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DeconstructAnswer> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, DeconstructAnswer::position, ByteBufCodecs.VAR_LONG, DeconstructAnswer::request,
                ByteBufCodecs.BOOL, DeconstructAnswer::done, ComponentSerialization.TRUSTED_STREAM_CODEC, DeconstructAnswer::message, DeconstructAnswer::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * Client to server: a column template for a constructor (owner request 2026-10-28), sent after the screen's draft
     * ({@link Action#KEEP}): its name as shown, and its input, the calculator's default input with the template's column
     * settings. The server makes the design from it ({@link ColumnDesign#of}, an air cooler and standard pumps), as a
     * calculator's Save design does, and answers as an import does ({@link ImportAnswer}).
     */
    public record TemplatePayload(BlockPos position, String name, V3ColumnInput input) implements CustomPacketPayload {
        public static final int MAX_NAME = 128;
        public static final Type<TemplatePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID, "equipment_template"));
        public static final StreamCodec<RegistryFriendlyByteBuf, TemplatePayload> STREAM_CODEC = new StreamCodec<>() {
            public TemplatePayload decode(RegistryFriendlyByteBuf b) {
                return new TemplatePayload(b.readBlockPos(), b.readUtf(MAX_NAME), ColumnWire.readInput(b));
            }

            public void encode(RegistryFriendlyByteBuf b, TemplatePayload p) {
                b.writeBlockPos(p.position);
                b.writeUtf(p.name, MAX_NAME);
                ColumnWire.writeInput(b, p.input);
            }
        };

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Client to server: name the device at {@code position} (N1), a placed structure's cell or a one-block device that
     * opens a screen; empty clears it. The server cleans the text ({@link com.wormzjl.icheme.science.equipment.DeviceName})
     * and writes it on the device's block entities, which send it to the clients as they send paint. No engine event: a
     * name is a drawing fact, not process state.
     */
    public record Rename(BlockPos position, String name) implements CustomPacketPayload {
        /** The longest text the wire takes; the name is cut to {@link com.wormzjl.icheme.science.equipment.DeviceName#MAX} after. */
        public static final int MAX_TEXT = 128;
        public static final Type<Rename> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID, "device_rename"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Rename> STREAM_CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC, Rename::position,
                ByteBufCodecs.stringUtf8(MAX_TEXT), Rename::name, Rename::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client: name the device at {@code position}. */
    public static void rename(BlockPos position, String name) {
        PacketDistributor.sendToServer(new Rename(position, name));
    }

    /**
     * Names the device the player may reach and use: a one-block device that opens a screen within its screen's reach, or
     * a structure within its screen's reach (the reach plus its largest size), every one of its cells loaded. Anything
     * else changes nothing; a structure with an unloaded cell answers in chat.
     */
    private static void rename(Rename rename, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        var level = player.serverLevel();
        var at = rename.position();
        if (!level.isLoaded(at) || !player.mayBuild() || player.isSpectator() || !level.mayInteract(player, at)) return;
        double reach = com.wormzjl.icheme.world.inventory.FluidDeviceMenu.REACH;
        if (level.getBlockEntity(at) instanceof com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity device) {
            if (device.nameable() && player.distanceToSqr(at.getCenter()) <= reach * reach) device.rename(rename.name());
            return;
        }
        var vessel = EquipmentStructure.find(level, at);
        if (vessel == null) return;
        var shape = vessel.shape();
        reach += Math.max(shape.sizeX(), Math.max(shape.sizeY(), shape.sizeZ()));
        if (player.distanceToSqr(at.getCenter()) > reach * reach) return;
        if (!EquipmentStructure.rename(level, vessel, rename.name()))
            player.displayClientMessage(Component.translatable("message.icheme.device.rename_unloaded"), true);
    }

    /** A design point on the wire: its numbers, its drive, its steam class (0 for none, else its ordinal plus one). */
    private static void writeDuty(RegistryFriendlyByteBuf b, MachineDuty d) {
        b.writeDouble(d.flow());
        b.writeDouble(d.head());
        b.writeDouble(d.polytropicHead());
        b.writeDouble(d.power());
        b.writeVarInt(d.poles());
        b.writeEnum(d.drive());
        b.writeVarInt(d.steam() == null ? 0 : d.steam().ordinal() + 1);
        b.writeDouble(d.exhaust());
        b.writeDouble(d.baseSpeed());
    }

    /** A batch still's options on the wire (protocol 22): its heater, condenser and stages, checked as the options check them. */
    private static com.wormzjl.icheme.science.equipment.BatchStillOptions readStill(RegistryFriendlyByteBuf b) {
        try {
            return new com.wormzjl.icheme.science.equipment.BatchStillOptions(b.readEnum(com.wormzjl.icheme.science.equipment.BatchStillOptions.Heater.class), b.readEnum(com.wormzjl.icheme.science.equipment.BatchStillOptions.Condenser.class), b.readVarInt());
        } catch (IllegalArgumentException invalid) {
            throw new io.netty.handler.codec.DecoderException(invalid.getMessage());
        }
    }

    private static MachineDuty readDuty(RegistryFriendlyByteBuf b) {
        double flow = b.readDouble(), head = b.readDouble(), polytropicHead = b.readDouble(), power = b.readDouble();
        int poles = b.readVarInt();
        var drive = b.readEnum(MachineDuty.Drive.class);
        int steam = b.readVarInt();
        if (steam < 0 || steam > MachineDuty.Steam.values().length) throw new IllegalArgumentException("Unknown steam class " + steam);
        return new MachineDuty(flow, head, polytropicHead, power, poles, drive, steam == 0 ? null : MachineDuty.Steam.values()[steam - 1], b.readDouble(),b.readDouble());
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(PROTOCOL).executesOn(HandlerThread.MAIN);
        registrar.playToServer(ConstructorPayload.TYPE, ConstructorPayload.STREAM_CODEC, EquipmentNetwork::constructor);
        registrar.playToServer(TemplatePayload.TYPE, TemplatePayload.STREAM_CODEC, EquipmentNetwork::template);
        registrar.playToServer(ImportList.TYPE, ImportList.STREAM_CODEC, EquipmentNetwork::importList);
        registrar.playToServer(ImportChoice.TYPE, ImportChoice.STREAM_CODEC, EquipmentNetwork::importChoice);
        registrar.playToServer(Rename.TYPE, Rename.STREAM_CODEC, EquipmentNetwork::rename);
        registrar.playToServer(Deconstruct.TYPE, Deconstruct.STREAM_CODEC, EquipmentNetwork::deconstruct);
        registrar.playToClient(DeconstructAnswer.TYPE, DeconstructAnswer.STREAM_CODEC, (reply, context) ->
                com.wormzjl.icheme.client.gui.screens.EquipmentConstructorScreen.deconstructed(reply));
        registrar.playToClient(ImportListAnswer.TYPE, ImportListAnswer.STREAM_CODEC, (answer, context) -> com.wormzjl.icheme.client.gui.screens.EquipmentConstructorScreen.listed(
                answer.position(), answer.message(), answer.listed()));
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

    private static void deconstruct(Deconstruct request, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        Component message;
        boolean done = false;
        var constructor = editable(player, request.position());
        if (constructor == null) message = Component.translatable("message.icheme.equipment.deconstruct.not_editable");
        else try {
            int cells = com.wormzjl.icheme.world.level.block.EquipmentDeconstruction.remove(player, constructor, request.origin(), request.shape());
            message = Component.translatable("message.icheme.equipment.deconstruct.done", request.origin().toShortString(), cells);
            done = true;
        } catch (IllegalStateException refused) {
            String reason = java.util.Set.of("not_editable", "changed", "unloaded", "protected").contains(refused.getMessage()) ? refused.getMessage() : "changed";
            message = Component.translatable("message.icheme.equipment.deconstruct." + reason);
        }
        PacketDistributor.sendToPlayer(player, new DeconstructAnswer(request.position(), request.request(), done, message));
    }

    public static void sendDeconstruct(Deconstruct request) { PacketDistributor.sendToServer(request); }

    /** Applies the screen's design, then keeps it or builds it. */
    private static void constructor(ConstructorPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        var constructor = editable(player, payload.position());
        if (constructor == null) return;
        EquipmentConstructorBlockEntity.Design design;
        try {
            design = payload.design(constructor.design());
        } catch (IllegalArgumentException invalid) {
            player.sendSystemMessage(Component.translatable("message.icheme.equipment.invalid", invalid.getMessage()).withStyle(ChatFormatting.RED));
            return;
        }
        constructor.apply(design);
        if (payload.action() == Action.BUILD) build(player, constructor, design);
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
            int placed = EquipmentStructure.place(player.serverLevel(), vessel.origin(), vessel.shape(), design.paint(), design.name(), Integer.MAX_VALUE);
            var at = vessel.origin().toShortString();
            var kind = Component.translatable("gui.icheme.vessel.title." + vessel.shape().kind().name().toLowerCase(java.util.Locale.ROOT));
            player.sendSystemMessage(!design.name().isEmpty() ? Component.translatable("message.icheme.equipment.built_named", design.name(), kind, placed, at)
                    : design.form().column()
                    ? Component.translatable("message.icheme.equipment.built_column", vessel.shape().diameter(), vessel.shape().trays().trays() + 2, placed, at)
                    : vessel.shape().designed() ? Component.translatable("message.icheme.equipment.built_unit",
                    kind, placed, at)
                    : Component.translatable("message.icheme.equipment.built", design.diameter(), design.tangentLength(), placed, at));
        } catch (IllegalStateException blocked) {
            player.sendSystemMessage(Component.translatable("message.icheme.equipment.blocked", blocked.getMessage()).withStyle(ChatFormatting.RED));
        }
    }

    /** Lists the designs of the process simulator nearest the constructor; always answered. */
    private static void importList(ImportList request, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        var at = request.position();
        var constructor = editable(player, at);
        if (constructor == null) {
            PacketDistributor.sendToPlayer(player, new ImportListAnswer(at, Component.translatable("message.icheme.equipment.not_editable"), new CompoundTag()));
            return;
        }
        var simulator = constructor.simulator(IMPORT_REACH);
        var designs = simulator.map(ProcessSimulatorBlockEntity::listed).orElse(List.of());
        var listed = SentDesignTags.wrap(designs);
        var message = simulator.isEmpty() ? Component.translatable("message.icheme.equipment.no_calculator", IMPORT_REACH)
                : designs.isEmpty() ? Component.translatable("message.icheme.equipment.no_design") : Component.empty();
        PacketDistributor.sendToPlayer(player, new ImportListAnswer(at, message, listed));
    }

    /** Lands the chosen design of the nearest process simulator on the constructor's design, which the screen just sent; always answered. */
    private static void importChoice(ImportChoice choice, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        var at = choice.position();
        var constructor = editable(player, at);
        if (constructor == null) {
            answer(player, at, Component.translatable("message.icheme.equipment.not_editable"), null);
            return;
        }
        var simulator = constructor.simulator(IMPORT_REACH);
        if (simulator.isEmpty()) {
            answer(player, at, Component.translatable("message.icheme.equipment.no_calculator", IMPORT_REACH), null);
            return;
        }
        var designs = simulator.get().listed();
        int i = choice.index();
        if (i < 0 || i >= designs.size() || !designs.get(i).tag().equals(choice.tag()) || !designs.get(i).member().equals(choice.member())) {
            answer(player, at, Component.translatable("message.icheme.equipment.import_changed"), null);
            return;
        }
        var sent = designs.get(i);
        EquipmentConstructorBlockEntity.Design design;
        try {
            design = constructor.design().landed(sent).withName(sent.tag().isEmpty() ? com.wormzjl.icheme.science.equipment.DeviceName.NONE : choice.name());
        } catch (IllegalArgumentException refused) {
            answer(player, at, Component.translatable("message.icheme.equipment.invalid", refused.getMessage()), null);
            return;
        }
        constructor.apply(design);
        answer(player, at, imported(sent), design);
    }

    /** What an import says: a column as the calculator counts it (its trays with the condenser and reboiler), a unit by its name, its kind and how many to build. */
    static Component imported(SentDesign sent) {
        var column = sent.column();
        if (column != null && sent.tag().isEmpty())
            return Component.translatable("message.icheme.equipment.imported", column.trays().trays() + 2, column.trays().feed() + 1, column.diameter());
        if (column != null)
            return Component.translatable("message.icheme.equipment.imported_column", name(sent), column.trays().trays() + 2, column.trays().feed() + 1, column.diameter());
        return Component.translatable("message.icheme.equipment.imported_unit", name(sent), kind(sent.shape()), sent.count());
    }

    /** A sent design's name, as the Import page's ({@code ConstructorColumn.listed}) in the reader's language: its tag, a member's own name after it; the calculator's untagged design by what it is. */
    static Component name(SentDesign sent) {
        if (sent.tag().isEmpty()) return Component.translatable("gui.icheme.equipment_constructor.import.saved");
        if (sent.member().isEmpty()) return Component.literal(sent.tag());
        String member = sent.member();
        var pa = SheetSizing.PumparoundMember.of(member);
        var own = pa != null ? Component.translatable("gui.icheme.flowsheet.sizing.member." + pa.key(), pa.pumparound())
                : Component.translatable("gui.icheme.flowsheet.sizing.member." + member.replace(' ', '_'));
        return Component.translatable("gui.icheme.equipment_constructor.import.member", sent.tag(), own);
    }

    /** What a shape builds, as the constructor names it: a drum standing or lying. */
    static Component kind(EquipmentShape shape) {
        if (shape.kind() == EquipmentShape.Kind.DRUM)
            return Component.translatable("gui.icheme.equipment_constructor.equipment." + (shape.orientation() == EquipmentShape.Orientation.VERTICAL ? "vertical_drum" : "horizontal_drum"));
        return Component.translatable("gui.icheme.vessel.title." + shape.kind().name().toLowerCase(java.util.Locale.ROOT));
    }

    /** Client: ask for the designs of the process simulator nearest the constructor at {@code position}, after its draft. */
    public static void sendImportList(BlockPos position) {
        PacketDistributor.sendToServer(new ImportList(position));
    }

    /** Client: land {@code sent}, design {@code index} of the list, on the constructor at {@code position}, after its draft. */
    public static void sendImportChoice(BlockPos position, int index, SentDesign sent, String name) {
        PacketDistributor.sendToServer(new ImportChoice(position, index, sent.tag(), sent.member(), name));
    }

    /** Client: send a column template for the constructor at {@code position}, after its draft. */
    public static void sendTemplate(BlockPos position, String name, V3ColumnInput input) {
        String shown = name.length() > TemplatePayload.MAX_NAME ? name.substring(0, TemplatePayload.MAX_NAME) : name;
        PacketDistributor.sendToServer(new TemplatePayload(position, shown, input));
    }

    /** Makes the template's column design and sets it on the constructor's design, as an import does; always answered. */
    private static void template(TemplatePayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        var at = payload.position();
        var constructor = editable(player, at);
        if (constructor == null) {
            answer(player, at, Component.translatable("message.icheme.equipment.not_editable"), null);
            return;
        }
        ColumnDesign column;
        try {
            column = ColumnDesign.of(payload.input(), ColumnComponents.Choices.DEFAULT.fit(payload.input().pumparounds().size()));
        } catch (IllegalArgumentException refused) {
            answer(player, at, Component.translatable("message.icheme.equipment.template_refused", payload.name(), refused.getMessage()), null);
            return;
        }
        var design = constructor.design().imported(column);
        constructor.apply(design);
        // The calculator's numbering: its trays and the condenser and reboiler.
        answer(player, at, Component.translatable("message.icheme.equipment.template", payload.name(), column.trays().trays() + 2, column.trays().feed() + 1, column.diameter()), design);
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
