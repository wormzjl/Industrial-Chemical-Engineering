package com.wormzjl.icheme.network;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.equipment.ColumnDesign;
import com.wormzjl.icheme.science.equipment.SentDesign;
import com.wormzjl.icheme.science.flowsheet.RevisionNotice;
import com.wormzjl.icheme.science.flowsheet.SheetBody;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import com.wormzjl.icheme.world.inventory.ProcessSimulatorMenu;
import com.wormzjl.icheme.world.level.block.entity.ProcessSimulatorBlockEntity;
import com.wormzjl.icheme.world.level.block.entity.SentDesignTags;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.DirectionalPayloadHandler;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.HandlerThread;
import org.jetbrains.annotations.Nullable;

/**
 * Between a process simulator's block and the player at it (batch 2026-11-02-process-simulator, plan section 3.4). Opening replays what is stored at once, a read
 * that solves nothing. The client sends a sheet body on Save, when a run ends and when the screen closes, in parts. A body is the player's own and an upload is untrusted:
 * it counts only from the holder, near the block, with the screen open, and {@link SheetBody} bounds it before it inflates. A store is no engine event, since nothing in a
 * sheet reaches the engine, so it lands with its last part: a close that follows it must find it stored. Send to block stores the sized designs on the block (S5), from
 * the holder only, and the server reads them whole before it keeps them; a constructor reads them when its Import opens. Wire format: no compatibility work.
 */
public final class ProcessSimulatorNetwork {
    private static final String PROTOCOL = "3";
    private static final int MAX_KEY = 64, MAX_DETAIL = 256;
    /** Translation key suffixes of an answer. */
    public static final String SAVED = "store.saved", DESIGN_SAVED = "store.design_saved", DESIGN_REFUSED = "store.design_refused", DESIGNS_SENT = "store.designs_sent", DESIGNS_REFUSED = "store.designs_refused";

    private ProcessSimulatorNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(PROTOCOL).executesOn(HandlerThread.MAIN);
        registrar.playBidirectional(SheetPart.TYPE, SheetPart.STREAM_CODEC, new DirectionalPayloadHandler<>(ProcessSimulatorNetwork::replayPart, ProcessSimulatorNetwork::upload));
        registrar.playToServer(SaveDesign.TYPE, SaveDesign.STREAM_CODEC, ProcessSimulatorNetwork::saveDesign);
        registrar.playToServer(SendDesigns.TYPE, SendDesigns.STREAM_CODEC, ProcessSimulatorNetwork::sendDesigns);
        registrar.playToClient(Answer.TYPE, Answer.STREAM_CODEC, ProcessSimulatorNetwork::answer);
    }

    /** A part of a sheet body: an upload going to the server, a replay coming from it. */
    public record SheetPart(int container, int index, int count, byte[] data) implements CustomPacketPayload {
        public static final Type<SheetPart> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID, "simulator_sheet_part"));
        public static final StreamCodec<FriendlyByteBuf, SheetPart> STREAM_CODEC = StreamCodec.of(
                (b, p) -> {
                    b.writeVarInt(p.container);
                    b.writeVarInt(p.index);
                    b.writeVarInt(p.count);
                    b.writeByteArray(p.data);
                },
                b -> new SheetPart(b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readByteArray(SheetBody.PART)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** A column's design, for the block a constructor imports from. The server checks it again. */
    public record SaveDesign(int container, V3ColumnInput input, ColumnComponents.Choices choices) implements CustomPacketPayload {
        public static final Type<SaveDesign> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID, "simulator_save_design"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SaveDesign> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public SaveDesign decode(RegistryFriendlyByteBuf buffer) {
                return new SaveDesign(buffer.readVarInt(), ColumnWire.readInput(buffer), ColumnWire.readChoices(buffer));
            }

            @Override
            public void encode(RegistryFriendlyByteBuf buffer, SaveDesign payload) {
                buffer.writeVarInt(payload.container);
                ColumnWire.writeInput(buffer, payload.input);
                ColumnWire.writeChoices(buffer, payload.choices);
            }
        };

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Send to block's designs, as {@link SentDesignTags} writes them under {@code Designs}. The server reads them again. */
    public record SendDesigns(int container, CompoundTag designs) implements CustomPacketPayload {
        public static final Type<SendDesigns> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID, "simulator_send_designs"));
        public static final StreamCodec<FriendlyByteBuf, SendDesigns> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, SendDesigns::container,
                ByteBufCodecs.COMPOUND_TAG, SendDesigns::designs, SendDesigns::new);

        public static SendDesigns of(int container, List<SentDesign> designs) {
            return new SendDesigns(container, SentDesignTags.wrap(designs));
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** What became of a body or a design: a translation key suffix, and the refusal's own words where it has them. */
    public record Answer(int container, String key, String detail) implements CustomPacketPayload {
        public static final Type<Answer> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID, "simulator_answer"));
        public static final StreamCodec<FriendlyByteBuf, Answer> STREAM_CODEC = StreamCodec.of(
                (b, a) -> {
                    b.writeVarInt(a.container);
                    b.writeUtf(a.key, MAX_KEY);
                    b.writeUtf(a.detail, MAX_DETAIL);
                },
                b -> new Answer(b.readVarInt(), b.readUtf(MAX_KEY), b.readUtf(MAX_DETAIL)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---- Server ----

    /** Opens the block for {@code player} and replays what it holds: bytes it has, parsed once to see they read, and no solve. */
    public static void open(ServerPlayer player, ProcessSimulatorBlockEntity block) {
        boolean edits = block.opens(player);
        var store = block.store();
        byte[] body = store.replay();
        String holder = "";
        if (!edits && block.getLevel() != null && block.getLevel().getServer() != null) {
            var other = block.getLevel().getServer().getPlayerList().getPlayer(store.holder());
            if (other != null) holder = other.getGameProfile().getName();
        }
        var opening = new ProcessSimulatorMenu.Opening(block.getBlockPos(), block.sheetId(), !edits, holder, body.length > 0, store.damaged(), RevisionNotice.tokens(MaterialRuntime.active()));
        player.openMenu(block, opening::write);
        if (!(player.containerMenu instanceof ProcessSimulatorMenu menu)) {
            store.release(player.getUUID());
            return;
        }
        if (body.length == 0) return;
        var parts = SheetBody.parts(body);
        for (int i = 0; i < parts.size(); i++) PacketDistributor.sendToPlayer(player, new SheetPart(menu.containerId, i, parts.size(), parts.get(i)));
    }

    /** {@code player}'s menu on {@code container}, or null unless it is that screen, near its block. */
    private static @Nullable ProcessSimulatorMenu menu(ServerPlayer player, int container) {
        return player.containerMenu instanceof ProcessSimulatorMenu menu && menu.containerId == container && menu.stillValid(player) ? menu : null;
    }

    /** The block of {@code player}'s menu on {@code container} while {@code player} holds its sheet; else null, the read-only answer sent. */
    private static @Nullable ProcessSimulatorBlockEntity held(ServerPlayer player, int container) {
        var menu = menu(player, container);
        var block = menu == null ? null : menu.block();
        if (block != null && player.getUUID().equals(block.store().holder())) return block;
        PacketDistributor.sendToPlayer(player, new Answer(container, ProcessSimulatorMenu.READ_ONLY, ""));
        return null;
    }

    /** An answer whose detail is {@code why}, clipped to what the wire carries. */
    private static Answer clipped(int container, String key, String why) {
        return new Answer(container, key, why.substring(0, Math.min(why.length(), MAX_DETAIL)));
    }

    private static void upload(SheetPart part, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) receive(player, part);
    }

    /** A part from {@code player}. */
    public static void receive(ServerPlayer player, SheetPart part) {
        var menu = menu(player, part.container);
        var block = menu == null ? null : menu.block();
        try {
            if (block == null) throw new SheetBody.Refused(ProcessSimulatorMenu.READ_ONLY);
            var whole = menu.parts().add(part.index, part.count, part.data);
            if (whole == null) return;
            block.accept(player.getUUID(), whole);
            PacketDistributor.sendToPlayer(player, new Answer(part.container, SAVED, ""));
        } catch (SheetBody.Refused refused) {
            PacketDistributor.sendToPlayer(player, new Answer(part.container, refused.key(), ""));
        }
    }

    private static void saveDesign(SaveDesign design, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) receive(player, design);
    }

    /** A design from {@code player}. */
    public static void receive(ServerPlayer player, SaveDesign design) {
        var block = held(player, design.container);
        if (block == null) return;
        try {
            block.saveDesign(ColumnDesign.of(design.input, design.choices));
            PacketDistributor.sendToPlayer(player, new Answer(design.container, DESIGN_SAVED, ""));
        } catch (IllegalArgumentException refused) {
            String why = String.valueOf(refused.getMessage());
            PacketDistributor.sendToPlayer(player, clipped(design.container, DESIGN_REFUSED, why));
        }
    }

    private static void sendDesigns(SendDesigns designs, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) receive(player, designs);
    }

    /** Send to block's designs from {@code player}: only the holder's, read whole, at most {@link SentDesign#MAX}; they replace what the block held. */
    public static void receive(ServerPlayer player, SendDesigns designs) {
        var block = held(player, designs.container);
        if (block == null) return;
        var list = SentDesignTags.unwrap(designs.designs);
        var read = SentDesignTags.read(list);
        if (!read.whole() || read.designs().isEmpty() || list.size() > SentDesign.MAX) {
            String why = read.repairs().isEmpty() ? "count " + list.size() : read.repairs().getFirst();
            PacketDistributor.sendToPlayer(player, clipped(designs.container, DESIGNS_REFUSED, why));
            return;
        }
        block.send(read.designs());
        PacketDistributor.sendToPlayer(player, new Answer(designs.container, DESIGNS_SENT, Integer.toString(read.designs().size())));
    }

    // ---- Client: none of this names a client class, so a dedicated server loads it ----

    private static void replayPart(SheetPart part, IPayloadContext context) {
        if (!(context.player().containerMenu instanceof ProcessSimulatorMenu menu) || menu.containerId != part.container) return;
        try {
            var whole = menu.parts().add(part.index, part.count, part.data);
            if (whole != null) menu.replayed(whole);
        } catch (SheetBody.Refused refused) {
            menu.answered(refused.key(), "");
        }
    }

    /** An answer to a screen that closed meanwhile, a refused close above all, goes to the chat: the player must hear it. */
    private static void answer(Answer answer, IPayloadContext context) {
        if (context.player().containerMenu instanceof ProcessSimulatorMenu menu && menu.containerId == answer.container) menu.answered(answer.key, answer.detail);
        else if (!answer.key.equals(SAVED)) context.player().displayClientMessage(Component.translatable("gui.icheme.flowsheet." + answer.key, answer.detail), false);
    }

    /** Sends a body from this client, in parts. */
    public static void sendBody(int container, byte[] packed) {
        var parts = SheetBody.parts(packed);
        for (int i = 0; i < parts.size(); i++) PacketDistributor.sendToServer(new SheetPart(container, i, parts.size(), parts.get(i)));
    }

    public static void sendDesign(int container, V3ColumnInput input, ColumnComponents.Choices choices) {
        PacketDistributor.sendToServer(new SaveDesign(container, input, choices));
    }

    public static void sendDesigns(int container, List<SentDesign> designs) {
        PacketDistributor.sendToServer(SendDesigns.of(container, designs));
    }
}
