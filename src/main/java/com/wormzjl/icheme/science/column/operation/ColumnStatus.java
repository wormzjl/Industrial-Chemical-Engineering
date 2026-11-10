package com.wormzjl.icheme.science.column.operation;

import java.util.List;
import java.util.Locale;

/**
 * A running column's status line and phase from its conditions (batch 2026-11-10-column-failure-modes, W1): one pure
 * formatter in place of the strings the cycle joined. The words of each case that existed before stay as they were:
 * {@code RUNNING}, {@code LIMITED: <word>, N %}, {@code UPSET: <text>}, {@code RE-SOLVING: <trigger>; …},
 * {@code NOT READY: <reason>}, {@code STARTING: …}, the fans' suffix and rule A's.
 */
public final class ColumnStatus {
    private ColumnStatus() {
    }

    /**
     * The phase: no table yet, NOT READY where a solve or the design refused it, else STARTING; with a table, UPSET for a
     * TRIP, LIMITED for a LIMIT, else RUNNING (an OFF TABLE state or a NOTICE runs on).
     */
    public static OperationView.Phase phase(boolean table, List<Condition.Active> conditions) {
        if (!table) return Condition.find(conditions, Condition.SOLVE_REFUSED) != null ? OperationView.Phase.NOT_READY : OperationView.Phase.STARTING;
        var binding = Condition.binding(conditions);
        if (binding == null) return OperationView.Phase.RUNNING;
        return binding.condition().rank == Condition.Rank.TRIP ? OperationView.Phase.UPSET : OperationView.Phase.LIMITED;
    }

    /**
     * The status line.
     *
     * @param table    whether a table is in force
     * @param alpha    the last cycle's share of the set feed
     * @param fans     the air fans' share of full speed at the last cycle (NaN: no fans known)
     * @param fanPower their power, W
     */
    public static String line(boolean table, List<Condition.Active> conditions, double alpha, double fans, double fanPower) {
        var phase = phase(table, conditions);
        var pending = Condition.find(conditions, Condition.OFF_TABLE);
        switch (phase) {
            case NOT_READY:
                return "NOT READY: " + Condition.find(conditions, Condition.SOLVE_REFUSED).text();
            case STARTING:
                return pending != null ? "STARTING: the first solve" : "STARTING: waiting for the first solve";
            case UPSET:
                return "UPSET: " + Condition.binding(conditions).text();
            default:
                break;
        }
        var binding = Condition.binding(conditions);
        var out = new StringBuilder(binding == null ? "RUNNING" : String.format(Locale.ROOT, "LIMITED: %s, %.0f %%", binding.condition().word, 100 * alpha));
        if (!Double.isNaN(fans)) out.append(String.format(Locale.ROOT, "; air fans %.0f %%, %.1f kW", 100 * fans, fanPower / 1000));
        var steam = Condition.find(conditions, Condition.STEAM_SHORT);
        if (steam != null && !steam.text().isEmpty()) out.append(", ").append(steam.text());
        String line = pending == null ? out.toString() : "RE-SOLVING: " + pending.text() + "; " + out;
        var notes = new StringBuilder(line);
        // The notices and refusals, after the line, in the order they matter: a refusal, then what the solve or the plan said.
        for (var c : List.of(Condition.SOLVE_REFUSED, Condition.SETPOINT_REFUSED, Condition.HIGH_LOAD, Condition.FEED_WATER_ROUTED, Condition.ADVISORY, Condition.UNMETERED))
            for (var a : conditions)
                if (a.condition() == c && !a.text().isEmpty()) notes.append("; ").append(a.text());
        return notes.toString();
    }
}
