package com.wormzjl.icheme.science.flowsheet.sizing;

import com.wormzjl.icheme.science.equipment.ColumnComponents.Condenser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The cooler model's rules (batch 2026-11-02-process-simulator, {@code COOLER_MODEL_PLAN.md} sections 3 and 6, C2): a free water side's
 * return, the Cooler's type, and the column condenser's three.
 */
final class CoolerSizingTest {
    private static final double KELVIN = 273.15;

    /** The full 10 K rise from a hot inlet down to 40 °C, then less, so the water never reaches the process inlet. */
    @Test void towerWaterReturnsByItsRule() {
        assertEquals(35 + KELVIN, CoolerSizing.waterReturn(120 + KELVIN), 1e-9);
        assertEquals(35 + KELVIN, CoolerSizing.waterReturn(45 + KELVIN), 1e-9);
        assertEquals(30 + KELVIN, CoolerSizing.waterReturn(40 + KELVIN), 1e-9);
        assertEquals(26.25 + KELVIN, CoolerSizing.waterReturn(30 + KELVIN), 1e-9);
    }

    @Test void theCoolerProposesAirFromFortyDegrees() {
        assertEquals(UnitType.AIR_COOLER, CoolerSizing.type(40 + KELVIN));
        assertEquals(UnitType.AIR_COOLER, CoolerSizing.type(70 + KELVIN));
        assertEquals(UnitType.WATER_COOLER, CoolerSizing.type(39.9 + KELVIN));
        assertEquals(UnitType.WATER_COOLER, CoolerSizing.type(30 + KELVIN));
    }

    /** Air while the receiver stands at the split; air then water while only the top vapour does; water where neither does. */
    @Test void theCondenserProposesOneOfThree() {
        assertEquals(Condenser.AIR_COOLER, CoolerSizing.condenser(110 + KELVIN, 45 + KELVIN));
        assertEquals(Condenser.AIR_COOLER, CoolerSizing.condenser(110 + KELVIN, 40 + KELVIN));
        assertEquals(Condenser.AIR_THEN_WATER, CoolerSizing.condenser(110 + KELVIN, 35 + KELVIN));
        assertEquals(Condenser.EXCHANGER, CoolerSizing.condenser(40 + KELVIN, 35 + KELVIN));
        assertEquals(Condenser.EXCHANGER, CoolerSizing.condenser(38 + KELVIN, 30 + KELVIN));
    }

    /**
     * The owner: "it's pointless to use twin cooler config if you don't need to cool below 40C". A twin condenser whose receiver
     * stands at the split leaves its trim no duty; one whose top vapour does not reach the split leaves its air member none. Either
     * reads yellow on the idle member, never red.
     */
    @Test void aTwinCondenserNamesItsIdleMember() {
        assertEquals(CoolerSizing.Idle.TRIM, CoolerSizing.twinIdle(110 + KELVIN, 40 + KELVIN));
        assertEquals(CoolerSizing.Idle.TRIM, CoolerSizing.twinIdle(110 + KELVIN, 59 + KELVIN));
        assertEquals(CoolerSizing.Idle.AIR, CoolerSizing.twinIdle(40 + KELVIN, 35 + KELVIN));
        assertEquals(CoolerSizing.Idle.AIR, CoolerSizing.twinIdle(38 + KELVIN, 30 + KELVIN));
        assertNull(CoolerSizing.twinIdle(110 + KELVIN, 35 + KELVIN));
        assertEquals(Lamp.YELLOW, Limit.TWIN_IDLE.read(0).lamp());
        assertEquals(Lamp.GREEN, Limit.TWIN_IDLE.read(1).lamp());
    }
}
