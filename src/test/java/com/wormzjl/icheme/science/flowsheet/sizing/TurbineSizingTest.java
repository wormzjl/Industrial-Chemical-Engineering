package com.wormzjl.icheme.science.flowsheet.sizing;

import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.MachineDuty;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The steam turbine's sizing (batch 2026-11-03-rotating-machinery, W8, owner decision D43), on loads by hand as {@link UnitSizingTest}
 * holds the pump's: Auto's size holds, one step down misses its spare, a Fixed size reads the same limits, and what the constructor
 * refuses is red and no exception.
 */
final class TurbineSizingTest {
    /** 850 kW at design flow from HP steam at 40 bar into the 3.5 bar header. */
    private static final TurbineSizing.Load LOAD = new TurbineSizing.Load(850, 4000, 350);

    @Test void aTurbineTakesTheSmallestRatedPowerThatHoldsItsSpare() {
        var choice = TurbineSizing.auto(LOAD);
        // 1.2 × 850 = 1020 kW: 1000 kW falls short, 1120 kW is the next of the list.
        assertEquals(new TurbineSizing.Size(1120, MachineDuty.Steam.HP, 350), choice.size());
        assertEquals(Lamp.GREEN, choice.rating().lamp());
        assertEquals(TurbineSizing.rate(LOAD, choice.size()), choice.rating(), "Fixed reads the same limits");
        var shape = choice.size().shape();
        assertNotNull(shape);
        assertEquals(EquipmentShape.Kind.STEAM_TURBINE, shape.kind());
        assertEquals(MachineDuty.turbine(1120, MachineDuty.Steam.HP, 350), shape.duty(), "Send to block stores the design point");
        var down = TurbineSizing.rate(LOAD, new TurbineSizing.Size(1000, MachineDuty.Steam.HP, 350));
        assertEquals(Lamp.YELLOW, down.lamp(), "one step down holds the need, not its spare");
        assertSame(Limit.TURBINE_SPARE, down.closest().limit());
    }

    @Test void theClassHoldsTheInletAndTheExhaustSnapsToTheConstructorsList() {
        assertEquals(Lamp.YELLOW, TurbineSizing.rate(LOAD, new TurbineSizing.Size(1120, MachineDuty.Steam.MP, 350)).lamp(), "40 bar on an MP casing");
        assertSame(Limit.TURBINE_INLET, TurbineSizing.rate(LOAD, new TurbineSizing.Size(1120, MachineDuty.Steam.MP, 350)).closest().limit());
        assertEquals(350, TurbineSizing.exhaust(300), 0, "nearer 350 than 250 by ratio");
        assertEquals(10, TurbineSizing.exhaust(8), 0);
        // 16 bar fits MP, but MP builds no exhaust over 8.5 bar: Auto goes up to HP for a 10 bar exhaust.
        var mp = TurbineSizing.auto(new TurbineSizing.Load(850, 1600, 1000));
        assertEquals(new TurbineSizing.Size(1120, MachineDuty.Steam.HP, 1000), mp.size());
        assertEquals(new TurbineSizing.Size(1120, MachineDuty.Steam.MP, 350), TurbineSizing.auto(new TurbineSizing.Load(850, 1600, 350)).size(), "the lowest class that holds");
    }

    @Test void whatTheConstructorRefusesIsRed() {
        assertEquals(Lamp.RED, TurbineSizing.rate(LOAD, new TurbineSizing.Size(1120, MachineDuty.Steam.MP, 1000)).lamp(), "an exhaust over half the class's inlet");
        assertSame(Limit.UNIT_SHAPE, TurbineSizing.rate(LOAD, new TurbineSizing.Size(1120, MachineDuty.Steam.MP, 1000)).closest().limit());
        assertNull(UnitSize.built(new TurbineSizing.Size(45, MachineDuty.Steam.HP, 350)), "under the turbine's 55 kW");
        assertEquals(Lamp.RED, TurbineSizing.rate(new TurbineSizing.Load(315000, 4000, 350), new TurbineSizing.Size(315000, MachineDuty.Steam.HP, 350)).lamp(), "over its 300 MW (owner P12)");
        // The world runs a turbine past its rating, so a need over the largest one is yellow: Auto offers the largest.
        var largest = TurbineSizing.auto(new TurbineSizing.Load(315000, 4000, 350));
        assertEquals(new TurbineSizing.Size(300000, MachineDuty.Steam.HP, 350), largest.size());
        assertEquals(Lamp.YELLOW, largest.rating().lamp());
    }

    @Test void noPowerNoSize() {
        assertNull(TurbineSizing.auto(TurbineSizing.Load.NONE).size());
        assertEquals(Lamp.NONE, TurbineSizing.rate(TurbineSizing.Load.NONE, new TurbineSizing.Size(1120, MachineDuty.Steam.HP, 350)).lamp());
    }
}
