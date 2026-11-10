package com.wormzjl.icheme.science.machine;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * The prime mover law (batch 2026-11-10-prime-movers, PRIME_MOVERS_PLAN.md sections 4 to 9): the Willans line, the exhaust, an
 * engine's radiator and fans, a gas turbine's fuel supply, and the sizing with the 20 % spare.
 */
class PrimeMoverTest {
    private static PrimeMover of(String name) {
        return new PrimeMover(PrimeMover.MODELS.stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow());
    }

    /** IND-30: 30 MW at 36 %, 83.3 MW of fuel; at no load 28 % of it (c0), at half load η 0.36 x 0.5 / (0.28 + 0.72 x 0.5). */
    @Test void theWillansLineGivesTheFuel() {
        var gt = of("IND-30");
        assertEquals(30e6 / .36, gt.model().ratedFuel(), 1);
        assertEquals(.28 * gt.model().ratedFuel(), gt.fuel(0), 1);
        assertEquals(gt.model().ratedFuel(), gt.fuel(30e6), 1);
        assertEquals(.36 * .5 / (.28 + .72 * .5), gt.efficiency(15e6), 1e-12);
        for (double x : new double[]{0, .1, .5, .9, 1}) assertEquals(x * 30e6, gt.gross(gt.fuel(x * 30e6)), 1e-6 * 30e6, "the line both ways at " + x);
        assertEquals(0, gt.gross(.2 * gt.model().ratedFuel()), "below its idle fuel it gives no work");
        assertEquals(30e6, gt.gross(2 * gt.model().ratedFuel()), "never past its rating");
    }

    /** A gas turbine's exhaust takes all but its work and its 2 % losses: IND-30 at full load, 51.7 MW in 97 kg/s at 500 °C. */
    @Test void aGasTurbinesExhaustCarriesTheRest() {
        var gt = of("IND-30");
        double fuel = gt.model().ratedFuel(), heat = gt.exhaustHeat(fuel, 30e6);
        assertEquals(fuel * .98 - 30e6, heat, 1);
        assertEquals(97.1, gt.exhaustFlow(heat), .1);
        assertEquals(PrimeMover.EXHAUST_CP * gt.exhaustFlow(heat), gt.flue(heat), 1e-9);
        assertEquals(0, gt.cooling(fuel, 30e6), "no radiator");
        assertEquals(30e6, gt.ratedNet(), 1e-6, "its fans are none: net is gross");
        assertEquals(fuel, 30e6 + heat + PrimeMover.LOSS * fuel, 1e-6, "energy closes");
    }

    /**
     * An engine's exhaust is 28 % of its fuel, its radiator the rest less its work and losses; its fans take 1.75 % of the
     * radiator's heat from the shaft. HS-1000: 2.38 MW of fuel, 667 kW to each, 11.7 kW of fans, 988 kW net.
     */
    @Test void anEnginesRadiatorAndFansCloseItsBalance() {
        var e = of("HS-1000");
        double fuel = e.model().ratedFuel(), gross = 1e6;
        assertEquals(.28 * fuel, e.exhaustHeat(fuel, gross), 1e-6);
        assertEquals(fuel * .70 - gross, e.cooling(fuel, gross), 1e-6);
        assertEquals(666.7e3, e.cooling(fuel, gross), 100);
        assertEquals(11.66e3, e.fans(fuel, gross), 20);
        assertEquals(gross - e.fans(fuel, gross), e.ratedNet(), 1e-6);
        assertEquals(fuel, gross + e.exhaustHeat(fuel, gross) + e.cooling(fuel, gross) + PrimeMover.LOSS * fuel, 1e-6, "energy closes");
        // The gross that delivers a net demand: the closed form agrees with the line at every load.
        for (double x : new double[]{.05, .3, .7, 1}) {
            double f = e.fuel(x * 1e6);
            assertEquals(e.gross(f), e.grossFor(e.net(f)), 1e-6 * 1e6, "net and gross agree at " + x);
        }
    }

    /** A gas turbine's fuel needs 1.4 times its combustor's pressure; between them its most fuel falls as the root. */
    @Test void aLowSupplyDeratesAGasTurbine() {
        var gt = of("IND-30");
        double pc = 20 * PrimeMover.ATMOSPHERE, high = 1.4 * pc;
        assertEquals(high, gt.model().required(), 1e-9);
        assertEquals(1, gt.supplyShare(high));
        assertEquals(1, gt.supplyShare(2 * high));
        assertEquals(0, gt.supplyShare(pc));
        assertEquals(Math.sqrt(.5), gt.supplyShare((pc + high) / 2), 1e-12);
        var engine = of("HS-500");
        assertEquals(PrimeMover.ATMOSPHERE + 1e5, engine.model().required(), 1e-9, "an engine's fuel at 1 bar(g)");
        assertEquals(0, engine.supplyShare(PrimeMover.ATMOSPHERE));
    }

    /** The smallest model that holds a load with the 20 % spare; the largest above the range. */
    @Test void sizingHoldsTheSpare() {
        assertEquals("IND-30", PrimeMover.sized(PrimeMover.Family.GAS_TURBINE, 25e6).name(), "30 MW holds 25 MW x 1.2");
        assertEquals("AE-35", PrimeMover.sized(PrimeMover.Family.GAS_TURBINE, 25.1e6).name());
        assertEquals("HD-300", PrimeMover.sized(PrimeMover.Family.GAS_TURBINE, 1e9).name());
        assertEquals("MS-5000", PrimeMover.sized(PrimeMover.Family.ENGINE, 3e6).name());
        for (var family : PrimeMover.Family.values()) {
            var list = PrimeMover.models(family);
            for (int i = 1; i < list.size(); i++) assertTrue(list.get(i).power() > list.get(i - 1).power(), "rising power");
            for (var m : list) assertSame(m, PrimeMover.ofRating(family, new PrimeMover(m).ratedNet()), "a shaft unit's rating names its model");
        }
    }
}
