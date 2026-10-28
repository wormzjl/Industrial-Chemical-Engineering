package com.wormzjl.icheme.network;

import com.wormzjl.icheme.runtime.PresentationMailbox;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.world.inventory.ColumnCalculatorV3Menu;
import com.wormzjl.icheme.world.level.block.entity.ColumnCalculatorV3BlockEntity;
import com.wormzjl.icheme.world.level.block.entity.ColumnCalculatorV3BlockEntity.V3State;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.util.IdentityHashMap;
import java.util.Map;

/** Session input mailbox flushed only by the simulation engine, never by packet or menu callbacks. */
final class ColumnPresentation {
    private static final Map<MinecraftServer, ColumnPresentation> SERVERS = new IdentityHashMap<>();
    private final PresentationMailbox<Session> mailbox = new PresentationMailbox<>();
    private final Map<ServerPlayer, Session> sessions = new IdentityHashMap<>();
    private record Rejection(BlockPos pos, long nonce, String reason) {}
    private static final class Session {
        final ServerPlayer player;
        final ColumnCalculatorV3Menu menu;
        final net.minecraft.server.level.ServerLevel level;
        final net.minecraft.world.level.block.entity.BlockEntity target;
        long revision = -1, nonce;
        boolean refresh = true;
        Rejection rejection;
        Session(ServerPlayer player, ColumnCalculatorV3Menu menu) {
            this.player=player;this.menu=menu;this.level=player.serverLevel();this.target=level.getBlockEntity(menu.blockPos());
        }
        boolean valid() {
            return !player.hasDisconnected() && player.containerMenu==menu && player.serverLevel()==level
                    && menu.stillValid(player) && level.getBlockEntity(menu.blockPos())==target;
        }
    }

    static void queue(IPayloadContext context, BlockPos pos, long nonce, Runnable action) {
        queue(context, pos, nonce, action, false);
    }

    static void queueImmediate(IPayloadContext context, BlockPos pos, long nonce, Runnable action) {
        queue(context, pos, nonce, action, true);
    }

    private static void queue(IPayloadContext context, BlockPos pos, long nonce, Runnable action, boolean immediate) {
        if (!(context.player() instanceof ServerPlayer player)
                || !(player.containerMenu instanceof ColumnCalculatorV3Menu menu) || !menu.blockPos().equals(pos)) return;
        var engine = FluidWorldAuthority.find(player.getServer());
        if (engine.isEmpty()) return; // No active simulation may accept a command.
        var host = SERVERS.computeIfAbsent(player.getServer(), ignored -> new ColumnPresentation());
        Session session = host.sessions.get(player);
        if (session == null || session.menu != menu) {
            if (session != null) host.mailbox.remove(session);
            session = new Session(player, menu); host.sessions.put(player, session);
        }
        host.mailbox.subscribe(session, engine.orElseThrow().onlineTick());
        if (action == null) {
            session.refresh = true;
            // An opening screen replays the block's last published state at once: a cached read that runs no solve,
            // applies no input and advances no time. Later states come with the session's buckets; with no published
            // state yet, the first bucket delivers it.
            if (player.serverLevel().getBlockEntity(pos) instanceof ColumnCalculatorV3BlockEntity calculator) {
                var published = calculator.published();
                if (published.isPresent()) {
                    ColumnV3Network.deliverState(player, pos, published.get());
                    session.revision = published.get().stateRevision(); session.refresh = false;
                }
            }
            return;
        }
        boolean accepted = immediate
                ? host.mailbox.submitImmediate(session, engine.orElseThrow().onlineTick(), action)
                : host.mailbox.submit(session, engine.orElseThrow().onlineTick(), action);
        if (!accepted)
            session.rejection = new Rejection(pos, nonce, "A request is already awaiting the engine");
    }

    static void acknowledged(IPayloadContext context, V3State state) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        var host = SERVERS.get(player.getServer());
        var session = host == null ? null : host.sessions.get(player);
        if (session != null) { session.nonce = state.clientNonce(); session.refresh = true; }
    }

    static void rejected(IPayloadContext context, BlockPos pos, long nonce, String reason) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        var host = SERVERS.get(player.getServer());
        var session = host == null ? null : host.sessions.get(player);
        if (session != null) session.rejection = new Rejection(pos, nonce, reason);
    }

    static void tick(MinecraftServer server, long now) {
        var host = SERVERS.get(server);
        if (host == null) return;
        host.mailbox.tick(now, session -> {
            if (session.valid()) return true;
            host.sessions.remove(session.player, session);
            return false;
        }, session -> {
            var level = session.player.serverLevel();
            if (level.getBlockEntity(session.menu.blockPos()) instanceof ColumnCalculatorV3BlockEntity calculator) {
                var state = calculator.state(session.nonce);
                calculator.markPublished(session.nonce == 0 ? state : calculator.state(0));
                if (session.refresh || session.revision != state.stateRevision()) {
                    ColumnV3Network.deliverState(session.player, session.menu.blockPos(), state);
                    session.revision = state.stateRevision(); session.refresh = false; session.nonce = 0;
                }
            }
            if (session.rejection != null) {
                var rejection = session.rejection; session.rejection = null;
                ColumnV3Network.deliverRejection(session.player, rejection.pos(), rejection.nonce(), rejection.reason());
            }
        });
    }

    static void refresh(MinecraftServer server) {
        var host = SERVERS.get(server);
        if (host != null) host.sessions.values().forEach(session -> session.refresh = true);
    }

    /** A terminal solve is already an engine event; publish it on the next engine tick instead of waiting for the
     * next five-second bucket. Sessions still have to be subscribed and valid, so unsolicited clients receive nothing. */
    static void refreshNow(MinecraftServer server, BlockPos pos) {
        var host = SERVERS.get(server);
        if (host == null) return;
        boolean matched = false;
        for (Session session : host.sessions.values()) {
            if (session.menu.blockPos().equals(pos)) {
                session.refresh = true;
                matched = true;
            }
        }
        if (matched) host.mailbox.requestImmediate();
    }

    static void forget(MinecraftServer server) { SERVERS.remove(server); }
}
