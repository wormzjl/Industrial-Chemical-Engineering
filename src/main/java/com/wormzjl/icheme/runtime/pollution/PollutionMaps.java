package com.wormzjl.icheme.runtime.pollution;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.network.PollutionMapNetwork;
import com.wormzjl.icheme.registry.ModItems;
import com.wormzjl.icheme.science.pollution.MapColumns;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The pollution map overlay's server side (batch 2026-11-15-pollution-system, WP9; plan section 12). A client whose map is
 * open asks ({@link #request}); the server keeps that player's subscription, for the dimension the player is in, and
 * answers it at once and then every {@link #MAP_TICKS} ticks (one field pass) with the polluted chunk columns within
 * {@link MapColumns#RADIUS_CHUNKS} chunks of the player ({@link MapColumns}). An answer is a read of the installed field and
 * the cells the passes cached: nothing steps, nothing loads. A subscription ends when the client closes its map, the player
 * logs out ({@link #forget(ServerPlayer)}) or leaves the dimension, or the server stops. No subscriber: the tick is one map
 * check.
 *
 * <p>Who may see it: {@code pollution.mapOverlay} ({@link Access}). Crash-proof as the meters ({@link PollutionMeters}): every
 * entry point catches at its edge and logs {@code pollution status=MAP_FAILED}; after {@link #FAILURE_LIMIT} failed passes in
 * a row the answers stop for the session ({@code pollution status=MAP_OFF}).</p>
 */
public final class PollutionMaps {
    /** Server ticks between two answers: one field pass. */
    public static final int MAP_TICKS = PollutionService.PASS_TICKS;
    /** Failed passes in a row that turn the map answers off for the session. */
    public static final int FAILURE_LIMIT = 3;
    /** At most one immediate answer a player in this many ticks; a subscription asked again sooner waits for the pass. */
    static final int REPLAY_TICKS = 20;

    /** Who may see the overlay ({@code pollution.mapOverlay}). */
    public enum Access {
        /** Only a player who carries a pollution meter anywhere in the inventory (the default). */
        METER,
        /** Every player. */
        ALL,
        /** No player. */
        OFF;

        /** The config's text, any case; {@link #METER} for anything else. */
        public static Access parse(String text) {
            if (text != null) {
                for (var access : values()) {
                    if (access.name().equals(text.trim().toUpperCase(Locale.ROOT))) {
                        return access;
                    }
                }
            }
            return METER;
        }
    }

    private static final Map<MinecraftServer, PollutionMaps> SERVERS = new IdentityHashMap<>();

    /** Each subscriber and the dimension it asked in. */
    private final Map<UUID, ResourceKey<Level>> subscribers = new LinkedHashMap<>();
    /** The tick of each player's last immediate answer; kept when the map closes, so opening it again and again is bounded. */
    private final Map<UUID, Integer> replayed = new HashMap<>();
    private int next;
    private int failures;
    private boolean off;

    private PollutionMaps() {
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The requests.

    /** A client's ask: its map opened ({@code true}: subscribe and answer at once) or closed. Server thread; never throws. */
    public static void request(ServerPlayer player, boolean watching) {
        try {
            var server = player.getServer();
            if (server == null) {
                return;
            }
            var maps = SERVERS.computeIfAbsent(server, s -> new PollutionMaps());
            UUID id = player.getUUID();
            if (!watching) {
                maps.subscribers.remove(id);
                return;
            }
            if (maps.off) {
                return;
            }
            var access = access();
            var dimension = player.level().dimension();
            if (access == Access.OFF) {
                maps.subscribers.remove(id);
                PollutionMapNetwork.send(player, PollutionMapNetwork.Answer.refused(dimension.location().toString()));
                return;
            }
            int now = server.getTickCount();
            if (maps.subscribers.isEmpty()) {
                // The first subscriber: the pass follows its replay one pass later, not on the next tick.
                maps.next = now + MAP_TICKS;
            }
            maps.subscribers.put(id, dimension);
            // The replay of the installed field, at most one a player in REPLAY_TICKS; one asked sooner waits for the pass.
            Integer last = maps.replayed.get(id);
            if (last == null || now - last >= REPLAY_TICKS) {
                maps.replayed.put(id, now);
                var service = PollutionService.find(server).orElse(null);
                PollutionMapNetwork.send(player, answer(player, service, access, new HashMap<>()));
            }
        } catch (RuntimeException failure) {
            IChemE.LOGGER.warn("pollution status=MAP_FAILED stage=request detail={}", String.valueOf(failure));
        }
    }

    /** A player who logs out asks no more. Never throws. */
    public static void forget(ServerPlayer player) {
        try {
            var server = player.getServer();
            var maps = server == null ? null : SERVERS.get(server);
            if (maps != null) {
                maps.subscribers.remove(player.getUUID());
                maps.replayed.remove(player.getUUID());
            }
        } catch (RuntimeException failure) {
            IChemE.LOGGER.warn("pollution status=MAP_FAILED stage=forget detail={}", String.valueOf(failure));
        }
    }

    /** Forgets a stopped server. Never throws. */
    static void forget(MinecraftServer server) {
        try {
            SERVERS.remove(server);
        } catch (RuntimeException failure) {
            IChemE.LOGGER.error("pollution status=FORGET_FAILED detail={}", String.valueOf(failure));
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The pass.

    /** The answers' deadline; {@code service} may be null (pollution never started). Never throws. */
    static void tick(MinecraftServer server, PollutionService service) {
        try {
            var maps = SERVERS.get(server);
            if (maps == null || maps.off || maps.subscribers.isEmpty()) {
                return;
            }
            int now = server.getTickCount();
            if (now - maps.next < 0) {
                return;
            }
            maps.next = now + MAP_TICKS;
            maps.run(server, service);
        } catch (RuntimeException failure) {
            IChemE.LOGGER.error("pollution status=MAP_FAILED stage=tick detail={}", String.valueOf(failure));
        }
    }

    private void run(MinecraftServer server, PollutionService service) {
        try {
            var access = access();
            // One summary a dimension and pass, shared by its subscribers.
            var summaries = new HashMap<ResourceKey<Level>, MapColumns.Columns>();
            for (var it = subscribers.entrySet().iterator(); it.hasNext(); ) {
                var entry = it.next();
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                // Logged out, or moved to another dimension: its client asks again for the new one.
                if (player == null || !player.level().dimension().equals(entry.getValue())) {
                    it.remove();
                    continue;
                }
                PollutionMapNetwork.send(player, answer(player, service, access, summaries));
                if (access == Access.OFF) {
                    it.remove();
                }
            }
            failures = 0;
        } catch (RuntimeException failure) {
            failures++;
            IChemE.LOGGER.warn("pollution status=MAP_FAILED stage=pass failures={} detail={}", failures, String.valueOf(failure));
            if (failures >= FAILURE_LIMIT) {
                off = true;
                subscribers.clear();
                IChemE.LOGGER.error("pollution status=MAP_OFF detail={} failed map passes in a row, the last: {}", FAILURE_LIMIT, String.valueOf(failure));
            }
        }
    }

    /**
     * The answer to {@code player}: refused when {@code access} does not let the player see it; empty when pollution is off
     * or absent; else the columns around the player from the dimension's summary in {@code summaries} (made on first use).
     */
    private static PollutionMapNetwork.Answer answer(ServerPlayer player, PollutionService service, Access access,
            Map<ResourceKey<Level>, MapColumns.Columns> summaries) {
        var dimension = player.level().dimension();
        String id = dimension.location().toString();
        if (!allowed(access, player)) {
            return PollutionMapNetwork.Answer.refused(id);
        }
        if (service == null || !service.enabled()) {
            return new PollutionMapNetwork.Answer(id, false, new long[0], new int[0]);
        }
        var all = summaries.computeIfAbsent(dimension, key -> MapColumns.columns(service.field(key), cube -> {
            var cell = service.cell(key, cube);
            // The field's own rule: the cube's last read air share, 1 when none is kept.
            return cell.loaded() ? cell.airShare() : 1.0;
        }, service.settings()));
        var near = MapColumns.around(all, player.getBlockX() >> 4, player.getBlockZ() >> 4, MapColumns.RADIUS_CHUNKS,
                MapColumns.MAXIMUM_ENTRIES);
        return new PollutionMapNetwork.Answer(id, false, near.columns(), near.values());
    }

    /** Whether {@code access} lets {@code player} see the overlay. */
    static boolean allowed(Access access, Player player) {
        return switch (access) {
            case ALL -> true;
            case OFF -> false;
            case METER -> carries(player);
        };
    }

    /** Whether the player carries a pollution meter anywhere in the inventory (main slots, armour, off hand). */
    public static boolean carries(Player player) {
        if (player == null) {
            return false;
        }
        var meter = ModItems.POLLUTION_METER.get();
        return player.getInventory().contains(stack -> stack.is(meter));
    }

    private static Access access() {
        return Access.parse(IChemE.pollutionMapOverlay());
    }
}
