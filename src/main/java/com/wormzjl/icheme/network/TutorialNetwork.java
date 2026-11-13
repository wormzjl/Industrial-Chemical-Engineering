package com.wormzjl.icheme.network;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.runtime.tutorial.TutorialTriggers;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.HandlerThread;

import java.util.HashSet;
import java.util.Set;

/**
 * The field guide's wire (batch 2026-11-12-tutorial-system, P1), protocol {@value #PROTOCOL}. The client reports a CLIENT
 * step done ({@link StepDone}): the server fires {@code icheme:guide_step} with that step for that player, so only an
 * advancement that listens to that criterion with that step can complete; a SERVER step's advancement listens to another
 * criterion and a client cannot complete it. The client says {@link Hello} at login and the server answers with
 * {@link Progress}, the tutorial advancements that player has done; it sends {@link Progress} again whenever the player
 * earns one ({@link #earned}). No tick work and no polling.
 *
 * <p>An own packet, not the game's advancement sync: {@code ClientAdvancements} keeps its progress private and has one
 * listener, which the advancements screen owns. The client handler stays behind a lambda, so a dedicated server never
 * loads a client class.
 */
public final class TutorialNetwork {
    public static final String PROTOCOL = "tutorial-1";
    /** The longest step id the server reads; longer ones are ignored. */
    public static final int STEP_MAX = 64;
    /** The tutorial advancements' folder under the {@code icheme} namespace. */
    public static final String FOLDER = "tutorial/";

    private TutorialNetwork() {
    }

    /** A CLIENT step done, {@code <chapter>/<step>}. */
    public record StepDone(String step) implements CustomPacketPayload {
        public static final Type<StepDone> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID, "tutorial_step_done"));
        public static final StreamCodec<RegistryFriendlyByteBuf, StepDone> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.stringUtf8(256), StepDone::step, StepDone::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** The client's login: answered with {@link Progress}. */
    public record Hello() implements CustomPacketPayload {
        public static final Type<Hello> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID, "tutorial_hello"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Hello> STREAM_CODEC = StreamCodec.unit(new Hello());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** The tutorial steps the player has done, by their paths under {@code tutorial/} ({@code welcome/guide}, {@code still/build}). */
    public record Progress(Set<String> steps) implements CustomPacketPayload {
        public static final Type<Progress> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID, "tutorial_progress"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Progress> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.collection(HashSet::new, ByteBufCodecs.stringUtf8(256)), p -> new HashSet<>(p.steps()), Progress::new);

        public Progress {
            steps = Set.copyOf(steps);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(PROTOCOL).executesOn(HandlerThread.MAIN);
        registrar.playToServer(StepDone.TYPE, StepDone.STREAM_CODEC, TutorialNetwork::stepDone);
        registrar.playToServer(Hello.TYPE, Hello.STREAM_CODEC, TutorialNetwork::hello);
        registrar.playToClient(Progress.TYPE, Progress.STREAM_CODEC,
                (payload, context) -> com.wormzjl.icheme.client.gui.tutorial.GuideClient.progress(payload.steps()));
    }

    private static void stepDone(StepDone payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || payload.step().length() > STEP_MAX) return;
        TutorialTriggers.GUIDE_STEP.get().trigger(player, payload.step());
    }

    /** Each player's last answered Hello, server tick (code review finding 6); a player object is one login, so its first Hello always passes. */
    private static final java.util.Map<ServerPlayer, Integer> HEARD = new java.util.WeakHashMap<>();
    /** At most one Hello answered a player a second (20 ticks), as the fluid edits' admission. */
    static final int HELLO_TICKS = 20;

    /**
     * A client's Hello: its progress, at most once a second. The client sends one a login ({@code GuideClient.hello}), so a
     * dropped one follows an answered one; every advancement earned sends the progress again ({@link #earned}).
     */
    private static void hello(Hello payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player && admit(HEARD, player, player.server.getTickCount())) send(player);
    }

    /** Whether {@code player}'s Hello at {@code tick} is answered: none answered in the last {@link #HELLO_TICKS}. */
    static <P> boolean admit(java.util.Map<P, Integer> heard, P player, int tick) {
        Integer last = heard.get(player);
        if (last != null && tick - last < HELLO_TICKS) return false;
        heard.put(player, tick);
        return true;
    }

    /** A tutorial advancement earned: the player's client hears its progress again. */
    public static void earned(AdvancementEvent.AdvancementEarnEvent event) {
        var id = event.getAdvancement().id();
        if (event.getEntity() instanceof ServerPlayer player && id.getNamespace().equals(IChemE.MOD_ID) && id.getPath().startsWith(FOLDER)) send(player);
    }

    private static void send(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, new Progress(done(player)));
    }

    /** The tutorial advancements {@code player} has done, by their paths under {@code tutorial/}; the root is left out. */
    public static Set<String> done(ServerPlayer player) {
        var steps = new HashSet<String>();
        for (var holder : player.server.getAdvancements().getAllAdvancements()) {
            var id = holder.id();
            if (!id.getNamespace().equals(IChemE.MOD_ID) || !id.getPath().startsWith(FOLDER)) continue;
            String step = id.getPath().substring(FOLDER.length());
            if (!step.equals("root") && player.getAdvancements().getOrStartProgress(holder).isDone()) steps.add(step);
        }
        return steps;
    }
}
