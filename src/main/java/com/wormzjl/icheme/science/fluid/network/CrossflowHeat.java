package com.wormzjl.icheme.science.fluid.network;

import org.jetbrains.annotations.Nullable;

/** A two-pass tube bundle against a shell mixed between crossflow rows, on the running exchanger's wall solver. */
public final class CrossflowHeat {
    public static final int CELLS = 64;

    private CrossflowHeat() {}

    public record Result(double intoTube, double approach, int sweeps, boolean converged, double[] wall) {
        public Result { wall = wall.clone(); }
        @Override public double[] wall() { return wall.clone(); }
    }

    public static Result rate(HeatCurve tube, double tubeInlet, double tubeFlow, HeatCurve shell, double shellInlet,
                              double shellFlow, double area, double @Nullable [] start) {
        if (!(area > 0) || tubeFlow == 0 || shellFlow == 0)
            return new Result(0, Math.abs(tube.temperatureAt(tubeInlet) - shell.temperatureAt(shellInlet)), 0, true, new double[0]);
        double[] wall = new double[CELLS];
        java.util.Arrays.fill(wall, (tube.temperatureAt(tubeInlet) + shell.temperatureAt(shellInlet)) / 2);
        var t = new HeatSide(area, HeatCurve.Films.DEFAULT, wall, 0, 0, 0, 1, null, HeatControl.Limit.NONE, HeatRouting.TWO_PASS_TUBES);
        var s = new HeatSide(area, HeatCurve.Films.DEFAULT, wall, 0, 0, 0, 1, null, HeatControl.Limit.NONE, HeatRouting.MIXED_CROSSFLOW);
        var ts = new ExchangerRelay.Stream(tube, tubeInlet, tubeFlow, t, true);
        var ss = new ExchangerRelay.Stream(shell, shellInlet, shellFlow, s, false);
        var solved = ExchangerRelay.wall(ts, ss, start);
        double[] temperatures = solved.temperatures();
        double qt = Math.abs(tubeFlow) * (ExchangerRelay.outlet(ts, temperatures) - tubeInlet);
        double qs = Math.abs(shellFlow) * (ExchangerRelay.outlet(ss, temperatures) - shellInlet);
        double[] ht = new double[CELLS], hs = new double[CELLS];
        t.routing().visit(tube, tubeInlet, Math.abs(tubeFlow), area, temperatures, tubeFlow > 0, ht, null);
        s.routing().visit(shell, shellInlet, Math.abs(shellFlow), area, temperatures, shellFlow > 0, hs, null);
        double sign = tube.temperatureAt(tubeInlet) >= shell.temperatureAt(shellInlet) ? 1 : -1, approach = Double.POSITIVE_INFINITY;
        for (int k = 0; k < CELLS; k++) {
            double ti=tube.temperatureAt(ht[k]), si=shell.temperatureAt(hs[k]);
            double to=tube.temperatureAt(tube.march(ht[k],t.routing().laneFlow(Math.abs(tubeFlow),CELLS),area/CELLS,temperatures[k]));
            double so=shell.temperatureAt(shell.march(hs[k],s.routing().laneFlow(Math.abs(shellFlow),CELLS),area/CELLS,temperatures[k]));
            double local=sign>0?Math.min(ti,to)-Math.max(si,so):Math.min(si,so)-Math.max(ti,to);
            approach=Math.min(approach,local);
        }
        return new Result((qt - qs) / 2, approach, solved.sweeps(), solved.converged(), temperatures);
    }
}
