package com.wormzjl.icheme.runtime.pollution;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.registry.ModItems;
import com.wormzjl.icheme.science.pollution.Channel;
import com.wormzjl.icheme.science.pollution.Environment;
import com.wormzjl.icheme.science.pollution.Exposure;
import com.wormzjl.icheme.science.pollution.Reading;
import com.wormzjl.icheme.world.item.RespiratorItem;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The handheld pollution meter's server side (batch 2026-11-15-pollution-system, WP8; plan section 11). Holding the meter
 * in either hand is the request (AGENTS.md, engine schedule): every {@link #METER_TICKS} ticks each holder, and no other
 * player, gets the four indices of the cube it stands in on its action bar and a warning click by the worst of them. Using
 * the meter prints the full reading in chat ({@link #report}). A read of the installed field and the cached cells: nothing
 * steps, nothing loads.
 *
 * <p>Crash-proof as the world pass ({@link PollutionEffects}): each player in its own guard; after {@link #FAILURE_LIMIT}
 * failed passes in a row the meters turn off for the session ({@code pollution status=METERS_OFF}). The meters run while
 * pollution is disabled or absent too: they then show {@code OFF}.</p>
 */
public final class PollutionMeters {
    /** Server ticks between two readings: one second of play. */
    public static final int METER_TICKS = 20;
    /** Failed passes in a row that turn the meters off for the session. */
    public static final int FAILURE_LIMIT = 3;
    /** The warning click's volume, to the holder only. */
    static final float TICK_VOLUME = 0.6f;

    private static final Channel[] CHANNELS = Channel.values();
    private static final Map<MinecraftServer, PollutionMeters> SERVERS = new IdentityHashMap<>();

    private int next;
    private int failures;
    private boolean off;
    private boolean playerFailureLogged;

    private PollutionMeters() {
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The pass.

    /** The meters' deadline; {@code service} may be null (pollution never started). Never throws. */
    static void tick(MinecraftServer server, PollutionService service) {
        try {
            var meters = SERVERS.computeIfAbsent(server, s -> new PollutionMeters());
            if (meters.off) {
                return;
            }
            int now = server.getTickCount();
            if (now - meters.next < 0) {
                return;
            }
            meters.next = now + METER_TICKS;
            meters.run(server, service);
        } catch (RuntimeException failure) {
            IChemE.LOGGER.error("pollution status=METERS_FAILED detail={}", String.valueOf(failure));
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

    private void run(MinecraftServer server, PollutionService service) {
        try {
            playerFailureLogged = false;
            for (ServerLevel level : server.getAllLevels()) {
                var players = level.players();
                for (int p = 0; p < players.size(); p++) {
                    var player = players.get(p);
                    if (!holds(player)) {
                        continue;
                    }
                    try {
                        show(player, service);
                    } catch (RuntimeException failure) {
                        if (!playerFailureLogged) {
                            playerFailureLogged = true;
                            IChemE.LOGGER.warn("pollution status=METER_FAILED player={} detail={}", player.getGameProfile().getName(), String.valueOf(failure));
                        }
                    }
                }
            }
            failures = 0;
        } catch (RuntimeException failure) {
            failures++;
            IChemE.LOGGER.warn("pollution status=METERS_FAILED failures={} detail={}", failures, String.valueOf(failure));
            if (failures >= FAILURE_LIMIT) {
                off = true;
                IChemE.LOGGER.error("pollution status=METERS_OFF detail={} failed meter passes in a row, the last: {}", FAILURE_LIMIT, String.valueOf(failure));
            }
        }
    }

    /** Whether the player holds a pollution meter in either hand. */
    public static boolean holds(Player player) {
        if (player == null) {
            return false;
        }
        var meter = ModItems.POLLUTION_METER.get();
        return player.getMainHandItem().is(meter) || player.getOffhandItem().is(meter);
    }

    private static void show(ServerPlayer player, PollutionService service) {
        var mask = respirator(RespiratorGear.worn(player));
        if (!on(service)) {
            player.displayClientMessage(withMask(off(), mask), true);
            return;
        }
        double[] indices = indices(service, player);
        player.displayClientMessage(withMask(actionBar(indices[0], indices[1], indices[2], indices[3]), mask), true);
        var sound = tickSound(Reading.worst(indices[0], indices[1], indices[2], indices[3]));
        if (sound != null) {
            player.playNotifySound(sound, SoundSource.PLAYERS, TICK_VOLUME, 1f);
        }
    }

    /** The full reading in chat, to that player only (the meter's use). Never throws. */
    public static void report(ServerPlayer player) {
        try {
            var server = player.getServer();
            var service = server == null ? null : PollutionService.find(server).orElse(null);
            var pos = player.blockPosition();
            if (!on(service)) {
                player.displayClientMessage(header(pos), false);
                player.displayClientMessage(off(), false);
                return;
            }
            double[] indices = indices(service, player);
            var cell = service.cell(player.level().dimension(), PollutionService.cube(pos));
            // The ground of the column underfoot (batch 2026-11-18-ground-pollution, G3), from the published snapshot.
            var snapshot = service.groundSnapshot(player.level().dimension());
            long column = com.wormzjl.icheme.science.pollution.MapColumns.column(pos.getX() >> 4, pos.getZ() >> 4);
            double ground = snapshot.index(column);
            for (var line : fullReading(pos, indices[0], indices[1], indices[2], indices[3], cell, ground)) {
                player.displayClientMessage(line, false);
            }
            var mask = respirator(RespiratorGear.worn(player));
            if (mask != null) {
                player.displayClientMessage(mask, false);
            }
        } catch (RuntimeException failure) {
            IChemE.LOGGER.warn("pollution status=METER_REPORT_FAILED detail={}", String.valueOf(failure));
        }
    }

    private static boolean on(PollutionService service) {
        return service != null && service.enabled();
    }

    /** The installed indices of the cube the player stands in, in {@link Channel} order. */
    private static double[] indices(PollutionService service, ServerPlayer player) {
        var dimension = player.level().dimension();
        var pos = player.blockPosition();
        double[] out = new double[CHANNELS.length];
        for (var channel : CHANNELS) {
            out[channel.ordinal()] = service.index(dimension, pos, channel);
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The text: translatable components only, so each client shows its own language.

    /** The action bar line: the four channels' segments, each coloured by its word, apart by bars. */
    public static Component actionBar(double toxic, double combustible, double corrosive, double particulate) {
        double[] indices = {toxic, combustible, corrosive, particulate};
        MutableComponent out = Component.empty();
        for (var channel : CHANNELS) {
            if (channel.ordinal() > 0) {
                out.append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY));
            }
            double index = indices[channel.ordinal()];
            var word = Reading.word(channel, index);
            out.append(Component.translatable("pollution.icheme.meter.segment", Component.translatable(shortKey(channel)),
                    Reading.number(index), Component.translatable(wordKey(word))).withStyle(colour(word)));
        }
        return out;
    }

    /**
     * The worn respirator's segment (batch 2026-11-18-respirator): "Mask 73% protected", "Mask 0% filter spent" or
     * "Mask 0% no filter"; null when none is worn.
     */
    public static Component respirator(net.minecraft.world.item.ItemStack worn) {
        if (worn == null || worn.isEmpty()) {
            return null;
        }
        boolean fitted = RespiratorItem.fitted(worn);
        double remaining = RespiratorItem.filter(worn);
        boolean working = com.wormzjl.icheme.science.pollution.Respirator.protects(remaining);
        String word = !fitted ? "pollution.icheme.meter.respirator.none"
                : working ? "pollution.icheme.meter.respirator.protected" : "pollution.icheme.meter.respirator.spent";
        return Component.translatable("pollution.icheme.meter.respirator", RespiratorItem.percent(remaining), Component.translatable(word))
                .withStyle(working ? ChatFormatting.AQUA : ChatFormatting.RED);
    }

    private static Component withMask(Component line, Component mask) {
        if (mask == null) {
            return line;
        }
        return Component.empty().append(line).append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY)).append(mask);
    }

    /** The one segment shown while pollution is disabled or absent. */
    public static Component off() {
        return Component.translatable("pollution.icheme.meter.off").withStyle(ChatFormatting.GRAY);
    }

    /** The chat lines of a full reading: the cube, the four channels, and the cube's air. */
    public static List<Component> fullReading(BlockPos pos, double toxic, double combustible, double corrosive, double particulate,
            Environment.Cell cell) {
        return fullReading(pos, toxic, combustible, corrosive, particulate, cell, 0.0);
    }

    /**
     * {@link #fullReading(BlockPos, double, double, double, double, Environment.Cell)} and then, when {@code ground} is above
     * 0, the ground of the chunk column underfoot in one line, its word and colour on the toxic scale
     * ({@link Reading#groundWord}; batch 2026-11-18-ground-pollution, one amount).
     */
    public static List<Component> fullReading(BlockPos pos, double toxic, double combustible, double corrosive, double particulate,
            Environment.Cell cell, double ground) {
        var out = airReading(pos, toxic, combustible, corrosive, particulate, cell);
        if (!(ground > 0)) {
            return out;
        }
        var word = Reading.groundWord(ground);
        out.add(Component.translatable("pollution.icheme.meter.ground", Integer.toString(pos.getX() >> 4), Integer.toString(pos.getZ() >> 4),
                Reading.number(ground), Component.translatable(wordKey(word))).withStyle(colour(word)));
        return out;
    }

    private static List<Component> airReading(BlockPos pos, double toxic, double combustible, double corrosive, double particulate,
            Environment.Cell cell) {
        double[] indices = {toxic, combustible, corrosive, particulate};
        var out = new ArrayList<Component>(11);
        out.add(header(pos));
        for (var channel : CHANNELS) {
            double index = indices[channel.ordinal()];
            var word = Reading.word(channel, index);
            out.add(Component.translatable("pollution.icheme.meter.line", Component.translatable(nameKey(channel)),
                    Reading.number(index), Component.translatable(wordKey(word))).withStyle(colour(word)));
        }
        if (cell == null || !cell.loaded()) {
            out.add(Component.translatable("pollution.icheme.air.unread").withStyle(ChatFormatting.GRAY));
        } else {
            out.add(Component.translatable("pollution.icheme.meter.air", Long.toString(Math.round(cell.airShare() * 100)),
                    Component.translatable(cell.open() ? "pollution.icheme.air.open" : "pollution.icheme.air.enclosed"))
                    .withStyle(ChatFormatting.GRAY));
        }
        return out;
    }

    private static Component header(BlockPos pos) {
        return Component.translatable("pollution.icheme.meter.header", Integer.toString(pos.getX() >> 4),
                Integer.toString(pos.getY() >> 4), Integer.toString(pos.getZ() >> 4)).withStyle(ChatFormatting.AQUA);
    }

    public static String wordKey(Reading.Word word) {
        return "pollution.icheme.word." + word.name().toLowerCase(Locale.ROOT);
    }

    public static String shortKey(Channel channel) {
        return "pollution.icheme.short." + channel.name().toLowerCase(Locale.ROOT);
    }

    public static String nameKey(Channel channel) {
        return "pollution.icheme.name." + channel.name().toLowerCase(Locale.ROOT);
    }

    /** The colour of a word on the meter's lines and the map's tooltip (WP9). */
    public static ChatFormatting colour(Reading.Word word) {
        return switch (word) {
            case SAFE -> ChatFormatting.GREEN;
            case WARNING -> ChatFormatting.YELLOW;
            case DANGER -> ChatFormatting.GOLD;
            case LETHAL, FLAMMABLE -> ChatFormatting.RED;
        };
    }

    /** The warning click by the worst level: none, slow (1 click a second), medium (3) or fast (8). */
    static SoundEvent tickSound(Exposure.Level worst) {
        if (worst == null) {
            return null;
        }
        return switch (worst) {
            case NONE -> null;
            case LOW -> ModBlocks.METER_TICK_SLOW.get();
            case MEDIUM -> ModBlocks.METER_TICK_MEDIUM.get();
            case HIGH, EXTREME -> ModBlocks.METER_TICK_FAST.get();
        };
    }
}
