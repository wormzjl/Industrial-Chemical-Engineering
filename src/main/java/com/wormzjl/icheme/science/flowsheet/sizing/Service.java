package com.wormzjl.icheme.science.flowsheet.sizing;

import com.wormzjl.icheme.science.flowsheet.FlowStream;
import com.wormzjl.icheme.science.flowsheet.FlowThermo;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.unit.ColumnUnit;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * What a line carries, which says which limits it answers to (batch 2026-11-02-process-simulator, {@code SIZING_PLAN.md} section
 * 3.3). The stream gives it, and the player may choose another. Saved by name.
 */
public enum Service {
    SUCTION_BOILING(Limit.SUCTION_BOILING_SPEED, Limit.SUCTION_BOILING_DROP, null),
    SUCTION_COLD(Limit.SUCTION_COLD_SPEED, Limit.SUCTION_COLD_DROP, null),
    DRAW(Limit.DRAW_SPEED, Limit.DRAW_DROP, null),
    LIQUID(Limit.LIQUID_SPEED, Limit.LIQUID_DROP, null),
    WATER(Limit.WATER_SPEED, Limit.WATER_DROP, null),
    GAS(Limit.GAS_SPEED, Limit.GAS_DROP, Limit.GAS_MOMENTUM),
    STEAM_SATURATED(Limit.STEAM_SATURATED_SPEED, Limit.STEAM_SATURATED_DROP, Limit.STEAM_SATURATED_MOMENTUM),
    STEAM_SUPERHEATED(Limit.STEAM_SUPERHEATED_SPEED, Limit.STEAM_SUPERHEATED_DROP, Limit.STEAM_SUPERHEATED_MOMENTUM),
    VACUUM_VAPOUR(Limit.VACUUM_SPEED, Limit.VACUUM_DROP, null),
    TWO_PHASE(Limit.TWO_PHASE_SPEED, null, Limit.TWO_PHASE_MOMENTUM);

    private final Limit speed;
    private final @Nullable Limit drop, momentum;

    Service(Limit speed, @Nullable Limit drop, @Nullable Limit momentum) {
        this.speed = speed;
        this.drop = drop;
        this.momentum = momentum;
    }

    /** Its limit on speed, m/s. */
    public Limit speed() {
        return speed;
    }

    /** Its limit on drop, null if it has none. */
    public @Nullable Limit drop() {
        return drop;
    }

    /** Its limit on ρv², null if it has none. */
    public @Nullable Limit momentum() {
        return momentum;
    }

    /** What a line's two ends say about it. */
    public record Ends(boolean leavesTray, boolean feedsPump) {
        /** A line whose ends say nothing. */
        public static final Ends OPEN = new Ends(false, false);

        public static Ends of(Flowsheet sheet, Flowsheet.Stream stream) {
            boolean tray = false, pump = false;
            for (var unit : sheet.units()) {
                var from = stream.from();
                var to = stream.to();
                if (from != null && unit.id().equals(from.unit()) && unit.type().equals("column") && ColumnUnit.leavesTray(from.port())) tray = true;
                if (to != null && unit.id().equals(to.unit()) && unit.type().equals("pump")) pump = true;
            }
            return new Ends(tray, pump);
        }
    }

    /**
     * The service of {@code stream}, or {@code chosen} if the player chose one. Null with no choice and no flow: it carries nothing
     * to read. In order: a vapour, then two phases, then what the line's ends ask of a liquid, then what the liquid is. A draw's limit
     * protects a liquid with only the column's head behind it and a suction's protects its pump, whatever the liquid: so a liquid
     * that leaves a tray is a draw even where it feeds a pump, and water into a pump is a suction.
     */
    public static @Nullable Service of(FlowStream stream, Ends ends, FlowThermo thermo, @Nullable Service chosen) {
        if (chosen != null) return chosen;
        var state = stream.state();
        if (state == null || stream.empty()) return null;
        double vapour = vapourShare(state, thermo.model());
        if (vapour > Limit.SERVICE_VAPOUR.value()) return vapour(state, thermo.model());
        if (vapour > Limit.SERVICE_TWO_PHASE.value()) return TWO_PHASE;
        if (ends.leavesTray()) return DRAW;
        if (ends.feedsPump()) {
            double spare = (stream.pressure() - thermo.bubblePressure(stream.temperature(), stream.amountsView())) / 1e5;
            return spare <= Limit.SERVICE_BOILING.value() ? SUCTION_BOILING : SUCTION_COLD;
        }
        return water(state) ? WATER : LIQUID;
    }

    /** Vapour's share of a state's fluid by mass, %. Solids do not move it. */
    static double vapourShare(FluidThermodynamics.State state, FluidThermodynamics model) {
        return 100 * model.vaporMass(state) / (state.mass() - state.solidMoments().mass());
    }

    /** Whether a state's liquid is water. */
    static boolean water(FluidThermodynamics.State state) {
        double liquid = Arrays.stream(state.liquidAmountsView()).sum() + Arrays.stream(state.aqueousAmountsView()).sum();
        return 100 * state.liquidWater() / liquid > Limit.SERVICE_WATER.value();
    }

    /** A vapour under ambient answers to its absolute pressure, whatever its gas: a limit in bar per 100 m means nothing there. */
    private static Service vapour(FluidThermodynamics.State state, FluidThermodynamics model) {
        if (state.pressure() / 1e5 < Limit.SERVICE_VACUUM.value()) return VACUUM_VAPOUR;
        double water = state.waterFraction(FluidThermodynamics.State.VAPOR);
        if (100 * water <= Limit.SERVICE_STEAM.value()) return GAS;
        return model.saturationPressure(state.temperature() - Limit.SERVICE_SUPERHEAT.value()) < water * state.pressure() ? STEAM_SATURATED : STEAM_SUPERHEATED;
    }
}
