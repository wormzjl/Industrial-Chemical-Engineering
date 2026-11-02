package com.wormzjl.icheme.science.column.operation;

import com.wormzjl.icheme.science.equipment.ColumnOperatingPoint;
import com.wormzjl.icheme.science.equipment.ColumnTrays;

/**
 * A port's pressure and temperature before the column's first solve (batch 2026-10-28-column-operation, plan 4.2): the
 * design's stage pressure at the port's stage, V3's {@code P_top + (j - 1) * drop} for tray j, the sump at the bottom
 * tray's, and the design's own temperature there (the feed's, a steam's, the condenser's overhead). A solve's table
 * replaces the temperature; until then nothing flows through a port (its valve's target is 0). Minecraft-free.
 */
public final class PortConditions {
    private PortConditions() {
    }

    /** A port's pressure, Pa, and temperature, K. */
    public record Conditions(double pressure, double temperature) {
    }

    /** The estimate for port {@code port} number {@code index} of a column built from {@code trays} and run at {@code point}. */
    public static Conditions estimate(ColumnOperatingPoint point, ColumnTrays trays, Port port, int index) {
        return switch (port) {
            case FEED -> new Conditions(stage(point, trays, trays.feed()), point.feedTemperature());
            case STEAM -> new Conditions(stage(point, trays, trays.steam().get(index)), point.steam().get(index).temperature());
            case OFF_GAS, SOUR_WATER, DISTILLATE -> new Conditions(point.topPressure(), point.condenserTemperature());
            case DRAW -> new Conditions(stage(point, trays, trays.draws().get(index)), point.feedTemperature());
            case BOTTOMS -> new Conditions(stage(point, trays, trays.trays()), point.feedTemperature());
        };
    }

    /** The pressure at stage {@code j}, Pa: stage {@code trays + 1} is the sump. */
    private static double stage(ColumnOperatingPoint point, ColumnTrays trays, int j) {
        return point.topPressure() + (Math.min(j, trays.trays()) - 1) * point.stageDrop();
    }
}
