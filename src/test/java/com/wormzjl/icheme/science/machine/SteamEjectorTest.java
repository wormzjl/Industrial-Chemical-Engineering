package com.wormzjl.icheme.science.machine;

import com.wormzjl.icheme.science.flowsheet.EjectorLaw;
import com.wormzjl.icheme.science.flowsheet.sizing.EjectorSizing;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The steam ejector set's correlation (batch 2026-11-10-steam-ejector-flow, EJECTOR_FLOW_PLAN.md "Correlation form" and "In
 * line with the column ejector"): its suction volume at the sheet's vacuum suction speed, the sheet's law's steam for the load
 * that volume holds, the motive's share and the maximum discharge; a sheet-sized set holds its sheet duty.
 */
class SteamEjectorTest {
    private static final SteamEjector SMALL = new SteamEjector(.1);

    /** Air at 300 K, kg/m³. */
    private static double air(double pressure) {
        return pressure * EjectorLaw.AIR * 1e-3 / (8.314462618 * 300);
    }

    /** The owner's table for a 0.1 m set on 10 bar to 1.2 bar: air and steam, kg/h, at 10, 3 and 1 kPa. */
    @Test void aTenthOfAMetreOnTenBarHoldsTheOwnersTable() {
        double[] pressures = {1e4, 3e3, 1e3}, airs = {197, 67, 30}, steams = {784, 396, 226};
        for (int i = 0; i < pressures.length; i++) {
            double p = pressures[i], load = SMALL.capacity(p, 1e6, 1) * air(p);
            assertEquals(airs[i], load * 3600, 1, p + " Pa: air");
            assertEquals(steams[i], EjectorLaw.steam(load, 0, 1e6, p, 1.2e5, 1) * 3600, 2, p + " Pa: steam");
        }
        assertEquals(60, SteamEjector.speed(5e3), 1e-12, "60 m/s from 5 kPa up");
        assertEquals(60, SteamEjector.speed(2e4), 1e-12);
        assertEquals(68.2, SteamEjector.speed(3e3), .05, "60 (5/3)^¼ at 3 kPa");
        assertEquals(89.7, SteamEjector.speed(1e3), .05, "60 5^¼ at 1 kPa");
        assertEquals(.03, SMALL.motiveBore(), 1e-12);
    }

    /** No steam, no capacity; the motive's share; the maximum discharge with the motive. */
    @Test void theMotiveSetsTheCapacityAndTheBreak() {
        assertEquals(0, SMALL.capacity(1e4, 1e6, 0));
        assertEquals(0, SMALL.capacity(1e4, 0, 1));
        assertEquals(0, SMALL.capacity(0, 1e6, 1));
        assertEquals(1, SteamEjector.motiveShare(1e6), 1e-3, "rated at 10 bar");
        assertEquals(.409, SteamEjector.motiveShare(5e5), .002, "0.5 / motiveFactor(5 bar)");
        assertEquals(.5 * SMALL.capacity(1e4, 1e6, 1), SMALL.capacity(1e4, 1e6, .5), 1e-15, "half the steam delivered, half the volume");
        assertEquals(SMALL.capacity(1e4, 1e6, 1), SMALL.capacity(1e4, 1e6, 2), 1e-15, "never past the full share");
        assertEquals(6e4, SteamEjector.maximumDischarge(5e5), 1e-9);
        assertEquals(1.2e5, SteamEjector.maximumDischarge(1e6), 1e-9);
    }

    /**
     * A set the sheet sizes for a column top (100 kg/h of air at 10 kPa, then 30 kg/h at 3 kPa) on the column door's 10 bar
     * and 1.2 bar takes its sheet duty in the world: its curve holds the load's volume at the top's pressure, and the steam
     * the law asks there covers the sheet's need.
     */
    @Test void aSheetSizedSetMeetsItsSheetDutyAtTwoTopPressures() {
        double[][] tops = {{1e4, 100}, {3e3, 30}};
        for (var top : tops) {
            double p = top[0], mass = top[1] / 3600, flow = mass / air(p) * 3600;
            double need = EjectorLaw.steam(mass, 0, 1e6, p, 1.2e5, 1);
            var size = EjectorSizing.auto(new EjectorSizing.Load(flow, p / 1e5, need * 3600, null)).size();
            assertNotNull(size, p + " Pa: the sheet sizes it");
            var set = new SteamEjector(size.suction());
            double holds = set.capacity(p, SteamEjector.RATED_MOTIVE, 1) * 3600;
            assertTrue(holds >= flow, p + " Pa: the curve holds the sheet's " + flow + " m3/h: " + holds);
            double steam = EjectorLaw.steam(holds / 3600 * air(p), 0, 1e6, p, 1.2e5, 1);
            assertTrue(steam >= need, p + " Pa: the law's steam covers the sheet's need");
            assertEquals(steam / need, holds / flow, 1e-9, "the steam scales with the load the curve holds");
        }
    }
}
