package com.wormzjl.icheme.science.fluid.network;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A fired heater's burners (batch 2026-11-09-furnace-fuel-power, FURNACE_FUEL_POWER_PLAN.md section 1): the fuel the radiant
 * heat needs at the flame's and the bridgewall's temperatures, the combustion-air fans' power, and the fuel's heat per kg.
 */
class BurnersTest {
    @Test void theRadiantHeatNeedsItsFlameOverTheBridgewall() {
        // (2073.15 - 298.15) / (2073.15 - 1073.15) = 1.775: the gas leaves the firebox at the bridgewall.
        assertEquals(1.775,Burners.FIRED_PER_RADIANT,1e-12);
        assertEquals(1.775e6,Burners.fired(1e6),1e-6);assertEquals(1e6,Burners.radiant(Burners.fired(1e6)),1e-6);
        assertEquals(0,Burners.fired(-1));assertEquals(0,Burners.radiant(-1));
        // At the design 70 / 30 split the heater gives about 80 % of its fuel: 0.7 + 0.3 over 1.775 / 0.7.
        double efficiency=1/(.7*Burners.FIRED_PER_RADIANT);
        assertEquals(.805,efficiency,.005);
    }
    @Test void theLimitIsTheRadiantShareTheFuelAllows() {
        assertEquals(1,Burners.limit(Burners.fired(2e6),1e6),"more fuel than the duty: the duty");
        assertEquals(.5,Burners.limit(Burners.fired(5e5),1e6),1e-12);
        assertEquals(0,Burners.limit(0,1e6));
        assertEquals(0,Burners.limit(1e6,0),"no duty, no limit to give");
    }
    @Test void theFansDrawAboutAKilowattPerMegawattFired() {
        // 0.40 kg/MJ of air at 1.185 kg/m3 against 1500 Pa at 70 %: 0.72 kW of shaft per MW fired.
        assertEquals(7.23e-4,Burners.fanPower(1e6)/1e6,1e-6);
        assertEquals(0,Burners.fanPower(-1));
        assertTrue(Burners.PRESSURE>101325,"the tips' back pressure");
        assertTrue(Burners.SHORT<1&&Burners.MARGIN>1);
    }
    @Test void theFuelsHeatIsPerKilogramOfWhatFlows() {
        // Methane 802.6 kJ/mol at 16.04 g/mol: 50.0 MJ/kg; half nitrogen by moles takes the mass and gives nothing.
        double[] lhv={802618,0},mass={.016043,.0280134};
        assertEquals(5.003e7,Burners.perKilogram(new double[]{1,0},mass,lhv),1e5);
        assertEquals(802618/(.016043+.0280134),Burners.perKilogram(new double[]{1,1},mass,lhv),1e-6);
        assertEquals(0,Burners.perKilogram(new double[]{0,1},mass,lhv),"an inert gives none");
        assertEquals(0,Burners.perKilogram(new double[]{0,0},mass,lhv),"nothing flows");
        assertEquals(5.003e7,Burners.perKilogram(new double[]{2,-1},mass,lhv),1e5,"a negative trace is no amount");
    }
    /** The flue's water (batch 2026-11-10-custom-shader-system, W5): methane 12 % of the dry flue gas, an oil less, hydrogen-free fuel none. */
    @Test void theFluesWaterFollowsTheFuelsHydrogen() {
        double[][] formulas={{1,4,0,0,0},{12,26,0,0,0},{1,0,0,1,0},{0,2,0,1,0}};
        assertEquals(.122,Burners.waterShare(new double[]{1,0,0,0},formulas),.002,"methane at 15 % excess air");
        assertEquals(.122,Burners.METHANE_WATER,.002);
        double oil=Burners.waterShare(new double[]{0,1,0,0},formulas);
        assertTrue(oil>.06&&oil<.09,"dodecane: about 7 %: "+oil);
        assertEquals(0,Burners.waterShare(new double[]{0,0,1,0},formulas),1e-12,"carbon monoxide forms no water");
        assertEquals(Burners.METHANE_WATER,Burners.waterShare(new double[]{0,0,0,0},formulas),"no fuel: methane's");
        assertTrue(Burners.waterShare(new double[]{1,0,0,1},formulas)>Burners.waterShare(new double[]{1,0,0,0},formulas),"the fuel's own water joins the flue");
        // The flue: the fuel and 0.40 kg of air per MJ fired, the air the fans' power is drawn for.
        assertEquals(.02+.40e-6*1e6,Burners.flueFlow(.02,1e6),1e-12);
        assertEquals(Burners.AIR_PER_FIRED*(1/1.185)*1500/.70,Burners.FAN_SHAFT_PER_FIRED,0);
    }
}
