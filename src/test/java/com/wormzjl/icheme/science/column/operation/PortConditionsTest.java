package com.wormzjl.icheme.science.column.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.wormzjl.icheme.science.equipment.ColumnOperatingPoint;
import com.wormzjl.icheme.science.equipment.ColumnTrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A port stands at V3's stage pressure: tray 1 at the top pressure, the sump at the bottom tray's. */
class PortConditionsTest {
    private static final double TOP = 200_000, DROP = 1_000;
    private static final ColumnOperatingPoint POINT = new ColumnOperatingPoint("icheme:a", "icheme:b", List.of("Methane"), List.of(1.0), 600, TOP, DROP, 330, 2, 0,
            List.of(new ColumnOperatingPoint.Steam(1, 500), new ColumnOperatingPoint.Steam(1, 510)), List.of(.1), List.of());
    private static final ColumnTrays TRAYS = new ColumnTrays(20, 12, List.of(6), List.of(15, 21), List.of());

    private static double pressure(Port port, int index) {
        return PortConditions.estimate(POINT, TRAYS, port, index).pressure();
    }

    @Test void aPortStandsAtItsStagesPressure() {
        assertEquals(TOP + 11 * DROP, pressure(Port.FEED, 0), "feed on tray 12");
        assertEquals(TOP + 5 * DROP, pressure(Port.DRAW, 0), "draw on tray 6");
        assertEquals(TOP + 14 * DROP, pressure(Port.STEAM, 0), "steam on tray 15");
    }

    @Test void theSumpStandsAtTheBottomTraysPressure() {
        assertEquals(TOP + 19 * DROP, pressure(Port.BOTTOMS, 0), "bottoms");
        assertEquals(TOP + 19 * DROP, pressure(Port.STEAM, 1), "steam into the sump, stage 21");
    }

    @Test void theCondensersPortsStandAtTheTopPressure() {
        for (var port : List.of(Port.OFF_GAS, Port.SOUR_WATER, Port.DISTILLATE)) assertEquals(TOP, pressure(port, 0), port.name());
    }
}
