package com.wormzjl.icheme.client;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A hand valve turning (owner 2026-11-10: "add turning sound and animation for hand valve"): pure state, no Minecraft
 * class. The lever turns at the click, as a hand turns a real valve; the network answers on its own schedule, and the
 * block state ({@code FluidDeviceBlock.SHUTOFF}) shows the committed position seconds later.
 *
 * <p>Per valve, by block position: the angle the turn starts from and goes to, its start, and its target position. A
 * valve with a turn is drawn apart from the chunk mesh ({@link #apart}) and turned per frame; one without costs nothing.
 * The turn eases over {@link #SECONDS}; the lever then waits at its target until the block state shows it, and turns
 * back to the block state's position if that has not come {@link #PATIENCE} after the click. Once the block state agrees,
 * the valve returns to the mesh, and is drawn {@link #LINGER} longer so the section's rebuild shows no gap.
 *
 * <p>Positions: a shutoff valve 0 open (the lever along the pipe), 1 closed (across it, a quarter turn about the stem);
 * a diverter valve its outlet, 0 straight through, 1 and 2 beside it ({@link #angle}). Client thread, except
 * {@link #apart}, which a section build may read.
 */
public final class ValveTurns {
    /** The turn's length, s: a hand swings a quarter-turn lever in about a second. */
    public static final double SECONDS = 1.0;
    /** How long a turned lever waits for the block state to show its target before it turns back, s from the click. */
    public static final double PATIENCE = 10.0;
    /** How long a settled valve is still drawn apart while its section rebuilds with the lever in the mesh, s. */
    public static final double LINGER = .25;

    public enum Kind { SHUTOFF, DIVERTER }

    /** The turn of one valve; {@code settled}: the time the block state agreed (NaN while it turns or waits). */
    record Turn(Kind kind, double from, double to, double start, int target, boolean back, double settled) {
        boolean lingering() { return !Double.isNaN(settled); }
    }

    /** The client's turns. Touched only on the client side of a level ({@code level.isClientSide()}). */
    public static final ValveTurns CLIENT = new ValveTurns();

    private final Map<Long, Turn> turns = new ConcurrentHashMap<>();

    /** The drawn angle of position {@code position}, radians about the valve's turning axis; 0 is the mesh's open, outlet 0. */
    public static double angle(Kind kind, int position) {
        return switch (kind) {
            case SHUTOFF -> position == 1 ? Math.PI / 2 : 0;
            case DIVERTER -> position == 1 ? Math.PI / 2 : position == 2 ? -Math.PI / 2 : 0;
        };
    }

    /** The share of a turn done {@code s} of the way through its time: a hand's start and stop, eased both ends. */
    public static double ease(double s) {
        double c = Math.clamp(s, 0, 1);
        return .5 - .5 * Math.cos(Math.PI * c);
    }

    /**
     * Turns valve {@code at} of {@code kind} to {@code target}, from {@code shown}, the position it shows now (the block
     * state's), or from its current angle if it is turning already. A repeat of the same target is ignored (the clicking
     * client's own start, then the server's block event), a new target re-aims from where the lever is. Whether the valve
     * has just left the mesh, so its section must rebuild.
     */
    public boolean start(long at, Kind kind, int shown, int target, double now) {
        var turn = turns.get(at);
        if (turn != null && !turn.back() && !turn.lingering() && turn.target() == target && turn.kind() == kind) return false;
        if ((turn == null || turn.lingering()) && shown == target) return false;
        double from = turn != null && turn.kind() == kind ? angleOf(turn, now) : angle(kind, shown);
        boolean left = turn == null || turn.lingering();
        turns.put(at, new Turn(kind, from, angle(kind, target), now, target, false, Double.NaN));
        return left;
    }

    /**
     * Advances valve {@code at} at time {@code now} against {@code committed}, the position its block state shows (-1:
     * none to compare, the turn ends with its motion). A turned lever that the block state does not follow within
     * {@link #PATIENCE} turns back to the block state's position. Whether the valve has just returned to the mesh, so its
     * section must rebuild.
     */
    public boolean settle(long at, int committed, double now) {
        var turn = turns.get(at);
        if (turn == null) return false;
        if (turn.lingering()) {
            if (now >= turn.settled() + LINGER) turns.remove(at);
            return false;
        }
        if (now < turn.start() + SECONDS) return false;
        if (committed < 0 || committed == turn.target()) {
            turns.put(at, new Turn(turn.kind(), turn.to(), turn.to(), turn.start(), turn.target(), turn.back(), now));
            return true;
        }
        if (turn.back() || now >= turn.start() + PATIENCE)
            turns.put(at, new Turn(turn.kind(), turn.to(), angle(turn.kind(), committed), now, committed, true, Double.NaN));
        return false;
    }

    /** The lever's angle now, radians; NaN for a valve not drawn apart. */
    public double angle(long at, double now) {
        var turn = turns.get(at);
        return turn == null ? Double.NaN : angleOf(turn, now);
    }

    private static double angleOf(Turn turn, double now) {
        return turn.from() + (turn.to() - turn.from()) * ease((now - turn.start()) / SECONDS);
    }

    /** Whether valve {@code at}'s turning part is left out of the chunk mesh: from the click until the block state agrees. */
    public boolean apart(long at) {
        var turn = turns.get(at);
        return turn != null && !turn.lingering();
    }

    /** The position valve {@code at} turns to or waits at, or -1: a second click toggles from it, as the server composes clicks. */
    public int target(long at) {
        var turn = turns.get(at);
        return turn == null || turn.lingering() ? -1 : turn.target();
    }

    /** The kind of valve {@code at}'s turn, or null for none. */
    public Kind kind(long at) {
        var turn = turns.get(at);
        return turn == null ? null : turn.kind();
    }

    /** Forgets valve {@code at} (its block is gone or no longer a valve). Whether it was drawn apart. */
    public boolean forget(long at) {
        var turn = turns.remove(at);
        return turn != null && !turn.lingering();
    }

    /** The valves drawn per frame now; empty when nothing turns. */
    public List<Long> positions() {
        return turns.isEmpty() ? List.of() : List.copyOf(turns.keySet());
    }

    /** Forgets every turn (the client left its level). */
    public void clear() {
        turns.clear();
    }
}
