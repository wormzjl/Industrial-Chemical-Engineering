package com.wormzjl.icheme.science.column.operation;

import com.wormzjl.icheme.science.equipment.ColumnOperatingPoint;
import com.wormzjl.icheme.science.equipment.ColumnTrays;

/**
 * A port's pressure and temperature before the column's first solve (batch 2026-10-28-column-operation, plan 4.2): the
 * design's stage pressure at the port's stage, {@code P_top + j * drop} for stage j, and the design's own temperature
 * there (the feed's, a steam's, the condenser's overhead). The first solve's table replaces them; until then nothing
 * flows through a port (its valve's target is 0), so the estimate only places the port. Minecraft-free.
 */
public final class PortConditions {
    private PortConditions() {
    }

    /** A port's pressure, Pa, and temperature, K. */
    public record Conditions(double pressure, double temperature) {
    }

    /** The estimate for port {@code port} number {@code index} of a column built from {@code trays} and run at {@code point}. */
    public static Conditions estimate(ColumnOperatingPoint point, ColumnTrays trays, Port port, int index) {
        double top = point.topPressure(), drop = point.stageDrop();
        return switch (port) {
            case FEED -> new Conditions(top + trays.feed() * drop, point.feedTemperature());
            case STEAM -> new Conditions(top + trays.steam().get(index) * drop, point.steam().get(index).temperature());
            case OFF_GAS, SOUR_WATER, DISTILLATE -> new Conditions(top, point.condenserTemperature());
            case DRAW -> new Conditions(top + trays.draws().get(index) * drop, point.feedTemperature());
            case BOTTOMS -> new Conditions(top + trays.trays() * drop, point.feedTemperature());
        };
    }
}
