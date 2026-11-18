package com.wormzjl.icheme.client.pollution;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.network.PollutionMapNetwork;
import net.minecraft.client.Minecraft;

import java.util.Arrays;

/**
 * The client's pollution map data (batch 2026-11-15-pollution-system, WP9; plan section 12): the server's last answer, kept
 * only while the map asks for it. {@link #watch} sends the ask, and only on a change; closing the map drops the data, so
 * nothing here outlives the request (AGENTS.md, engine schedule). A level change or a logout drops it too. Client thread only.
 *
 * <p>No map mod's type is named here: the map integration ({@code client/pollution/ftbchunks/}) reads {@link #columns},
 * {@link #value} and {@link #shows} at draw time and listens to {@link #onChange} for a new set of columns.</p>
 */
public final class PollutionMapStore {
    private static final long[] NONE = new long[0];
    private static final int[] NO_VALUES = new int[0];

    private static boolean watching;
    private static String dimension = "";
    private static boolean denied;
    /** The columns of the last answer, ascending (for {@link Arrays#binarySearch}), and their four bytes. */
    private static long[] columns = NONE;
    private static int[] values = NO_VALUES;
    /** The ground's four bytes of each column (batch 2026-11-18-ground-pollution, G3), in the order of {@link #columns}. */
    private static int[] groundValues = NO_VALUES;

    /** Which layer a square shows (G3): both (the worse of air and ground per channel), the air's, or the ground's. */
    public enum Layer {
        BOTH,
        AIR,
        GROUND
    }

    private static Layer layer = Layer.BOTH;
    private static Runnable changed = () -> {
    };

    private PollutionMapStore() {
    }

    /** The map is open ({@code true}) or closed: asks the server, only when this changes. Closing drops the data. */
    public static void watch(boolean open) {
        if (open == watching) {
            return;
        }
        watching = open;
        if (!open) {
            drop();
        }
        try {
            if (Minecraft.getInstance().getConnection() != null) {
                PollutionMapNetwork.request(open);
            }
        } catch (RuntimeException failure) {
            IChemE.LOGGER.warn("pollution status=MAP_FAILED stage=client_request detail={}", String.valueOf(failure));
        }
    }

    /** The server's answer; ignored when the map no longer asks (an answer already on its way when it closed). */
    public static void accept(PollutionMapNetwork.Answer answer) {
        if (!watching || answer == null) {
            return;
        }
        // The answer is nearest first; the store keeps the columns ascending.
        long[] given = answer.columns();
        int[] givenValues = answer.values();
        int[] givenGround = answer.ground();
        Integer[] order = new Integer[given.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Long.compare(given[a], given[b]));
        long[] sortedColumns = new long[order.length];
        int[] sortedValues = new int[order.length];
        int[] sortedGround = new int[order.length];
        for (int k = 0; k < order.length; k++) {
            sortedColumns[k] = given[order[k]];
            sortedValues[k] = givenValues[order[k]];
            sortedGround[k] = givenGround[order[k]];
        }
        boolean newSet = answer.denied() != denied || !answer.dimension().equals(dimension) || !Arrays.equals(sortedColumns, columns);
        dimension = answer.dimension();
        denied = answer.denied();
        columns = sortedColumns;
        values = sortedValues;
        groundValues = sortedGround;
        if (newSet) {
            try {
                changed.run();
            } catch (RuntimeException failure) {
                IChemE.LOGGER.warn("pollution status=MAP_FAILED stage=client_refresh detail={}", String.valueOf(failure));
            }
        }
    }

    /** Whether the store holds a drawable answer for {@code dimensionId} (the map asks, and the player may see it). */
    public static boolean shows(String dimensionId) {
        return watching && !denied && dimension.equals(dimensionId);
    }

    /** The columns of the last answer, ascending; read only. Empty when the map does not ask. */
    public static long[] columns() {
        return columns;
    }

    /** The four bytes of {@code column} in {@code dimensionId}; 0 when the store has none (no allocation). */
    public static int value(String dimensionId, long column) {
        if (!shows(dimensionId)) {
            return 0;
        }
        int i = Arrays.binarySearch(columns, column);
        return i < 0 ? 0 : values[i];
    }

    /** The ground's four bytes of {@code column} in {@code dimensionId}; 0 when the store has none (no allocation). */
    public static int ground(String dimensionId, long column) {
        if (!shows(dimensionId)) {
            return 0;
        }
        int i = Arrays.binarySearch(columns, column);
        return i < 0 ? 0 : groundValues[i];
    }

    /** The four bytes a square shows in the current {@link #layer}. */
    public static int shown(String dimensionId, long column) {
        return switch (layer) {
            case AIR -> value(dimensionId, column);
            case GROUND -> ground(dimensionId, column);
            case BOTH -> com.wormzjl.icheme.science.pollution.MapColumns.worse(value(dimensionId, column), ground(dimensionId, column));
        };
    }

    public static Layer layer() {
        return layer;
    }

    /** Shows {@code next} on the squares from the next draw. */
    public static void layer(Layer next) {
        layer = next == null ? Layer.BOTH : next;
    }

    /** Run when an answer brings another set of columns (the map gathers its squares again); one listener. */
    public static void onChange(Runnable listener) {
        changed = listener == null ? () -> {
        } : listener;
    }

    /** The level changed: the server dropped the ask with it, so the data goes and the next open check asks again. */
    public static void levelChanged() {
        watching = false;
        drop();
    }

    /** The client left: everything goes, with no request. */
    public static void clear() {
        watching = false;
        drop();
    }

    private static void drop() {
        dimension = "";
        denied = false;
        columns = NONE;
        values = NO_VALUES;
        groundValues = NO_VALUES;
    }
}
