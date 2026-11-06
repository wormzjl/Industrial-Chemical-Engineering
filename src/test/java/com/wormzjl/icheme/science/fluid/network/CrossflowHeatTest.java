package com.wormzjl.icheme.science.fluid.network;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CrossflowHeatTest {
    private static HeatCurve liquid(double cp, double film) {
        return new HeatCurve(new double[]{0, cp * 300}, new double[]{200, 500}, new double[]{film, film});
    }

    private static HeatSide side(double area, int n, HeatRouting routing) {
        var wall = new double[n * n];
        Arrays.fill(wall, 350);
        return new HeatSide(area, HeatCurve.Films.DEFAULT, wall, 0, 0, 1, 1, null, HeatControl.Limit.NONE, routing);
    }

    private static double[] rate(HeatCurve tc, double th, double tm, HeatCurve sc, double sh, double sm, double area, int n) {
        var tube = side(area, n, HeatRouting.TWO_PASS_TUBES);
        var shell = side(area, n, HeatRouting.MIXED_CROSSFLOW);
        var t = new ExchangerRelay.Stream(tc, th, tm, tube, true);
        var s = new ExchangerRelay.Stream(sc, sh, sm, shell, false);
        var wall = ExchangerRelay.wall(t, s, null);
        assertTrue(wall.converged(), "wall sweeps " + wall.sweeps());
        double qt = Math.abs(tm) * (ExchangerRelay.outlet(t, wall.temperatures()) - th);
        double qs = Math.abs(sm) * (ExchangerRelay.outlet(s, wall.temperatures()) - sh);
        assertEquals(0, qt + qs, Math.max(1e-3, Math.abs(qt) * 1e-4), "both streams book the same heat");
        assertEquals(qt, tube.withInputs(wall.temperatures(), 0, 0).heat(tc, th, tm), 1e-9 * Math.max(1, Math.abs(qt)));
        assertEquals(qs, shell.withInputs(wall.temperatures(), 0, 0).heat(sc, sh, sm), 1e-9 * Math.max(1, Math.abs(qs)));
        return new double[]{(qt - qs) / 2, wall.sweeps()};
    }

    @Test void liquidSweepConservesEnergyAndAgreesWithAFinerGrid() {
        var curve = liquid(4000, 2000);
        for (double cr : new double[]{.1, .5, 1}) for (double ntu : new double[]{.1, .5, 1, 2, 5}) {
            double area = ntu * 4000 / 1000;
            var coarse = rate(curve, 800_000, 1, curve, 400_000, 1 / cr, area, 8);
            var fine = rate(curve, 800_000, 1, curve, 400_000, 1 / cr, area, 16);
            assertEquals(fine[0], coarse[0], .02 * Math.abs(fine[0]), "Cr=" + cr + ", NTU=" + ntu);
            assertTrue(-coarse[0] > 0 && -coarse[0] <= 400_000);
        }
    }

    @Test void phaseChangeAndBothFlowDirectionsUseTheSameRouting() {
        var tube = liquid(4000, 2000);
        var shell = new HeatCurve(new double[]{-1e9, 0, 1e9}, new double[]{400, 400, 400}, new double[]{5000, 5000, 5000});
        double area = 5, u = 1 / (1 / 2000. + 1 / 5000.), exact = 4000 * 100 * (1 - Math.exp(-u * area / 4000));
        for (int ts : new int[]{-1, 1}) for (int ss : new int[]{-1, 1}) {
            var result = rate(tube, 400_000, ts, shell, 0, 10 * ss, area, 8);
            assertEquals(exact, result[0], .01 * exact);
        }
    }

    @Test void singlePhaseAtNegligibleCapacityRatioMatchesTheAshraeLimit() {
        var curve=liquid(4000,2000);
        for(double ntu:new double[]{.1,.5,1,2,5}) {
            var solved=rate(curve,800000,1,curve,400000,100000,4*ntu,8);
            assertEquals(1-Math.exp(-ntu),-solved[0]/400000,.002);
        }
    }

    @Test void supportedSurfacesAndPhaseDependentFilmsAgreeWithTheFinerGrid() {
        var water=liquid(4000,2000);
        var gas=liquid(2000,100);
        var phase=new HeatCurve(new double[]{0,200000,600000,1000000},new double[]{250,350,350,550},new double[]{2000,5000,5000,100});
        HeatCurve[][] pairs={{water,water},{gas,water},{water,phase},{phase,water},{phase,phase}};
        double worst=0;int sweeps=0,cases=0;
        for(double d=.5;d<=2.5;d+=.25) for(int length=(int)Math.ceil(3*d);length<=12;length++) for(var pair:pairs) {
            double area=com.wormzjl.icheme.science.equipment.ProcessUnit.tubeSurface(d,length);
            double hot=pair[0].enthalpyAt(420),cold=pair[1].enthalpyAt(290);
            var coarse=rate(pair[0],hot,100,pair[1],cold,150,area,8);
            var fine=rate(pair[0],hot,100,pair[1],cold,150,area,16);
            double error=Math.abs(coarse[0]-fine[0])/Math.max(1,Math.abs(fine[0]));
            worst=Math.max(worst,error);sweeps+=(int)coarse[1];cases++;
            assertTrue(error<.025,"D="+d+", L="+length+", error="+error);
        }
        System.out.printf("CROSSFLOW_SWEEP cases=%d worstRelativeError=%.6f wallSweeps=%d%n",cases,worst,sweeps);
    }

    @Test void zeroFlowBypassAndReturnHeaderRemainConservative() {
        var curve = liquid(4000, 2000);
        var tube = side(5, 8, HeatRouting.TWO_PASS_TUBES);
        assertEquals(0, tube.heat(curve, 400_000, 0));
        assertEquals(0, tube.withInputs(tube.wall(), 1, 0).heat(curve, 400_000, 1));
        var t = new ExchangerRelay.Stream(curve, 800_000, 1, tube, true);
        var s = new ExchangerRelay.Stream(curve, 400_000, 2, side(5, 8, HeatRouting.MIXED_CROSSFLOW), false);
        var read = new ExchangerRelay.Read(null, false, 100, 5, 0);
        var planned = ExchangerRelay.plan(tube, t, s, read, 5);
        assertEquals(HeatRouting.TWO_PASS_TUBES, planned.routing());
        assertEquals(64, planned.cells());
        assertNotEquals(tube.shape(), new HeatSide(5, HeatCurve.Films.DEFAULT, tube.wall(), 0, 0, 1, 1).shape());
    }

    @Test void finiteCapacityStreamsWorkInEitherPhysicalDirection() {
        var tube=liquid(4000,2000);var shell=liquid(2000,100);
        for(int tm:new int[]{-1,1})for(int sm:new int[]{-1,1}) {
            var coarse=rate(tube,800000,tm,shell,200000,2*sm,20,8);
            var fine=rate(tube,800000,tm,shell,200000,2*sm,20,16);
            assertTrue(coarse[0]<0);
            assertEquals(fine[0],coarse[0],.02*Math.abs(fine[0]));
        }
    }
}
