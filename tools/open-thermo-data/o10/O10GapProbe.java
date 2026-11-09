package com.wormzjl.icheme.science.flowsheet;

import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.column.v3.V3ColumnResult;
import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.icheme.science.column.v3.thermo.V3WaterProperties;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.flowsheet.unit.ColumnBlockSolve;
import com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import com.wormzjl.icheme.science.material.TiaJuanaTestCatalog;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * O10 probe (batch 2026-11-04-open-thermo-data): the block energy gap between the simulator and V3, by stream and term, on the
 * medium grade and on Tia Juana. Needs the scratch hook ColumnBlockSolve.O10. Prints "o10 gap" lines.
 */
final class O10GapProbe {
    private static final MaterialCatalog CATALOG = MaterialCatalog.bundled();
    private static final String NETWORK = CATALOG.presets().network().packageId();
    private static final List<String> AXIS = FluidThermodynamics.forNetwork(CATALOG, NETWORK).components();

    static Flowsheet.Feed crude(V3ColumnInput base, double temperature, double pressure) {
        var ids = base.componentBasis().componentIds();
        var amounts = new LinkedHashMap<String, Double>();
        double[] flows = base.feedComponentMolarFlowsMolPerSecond();
        for (int i = 0; i < flows.length; i++) if (flows[i] > 0) amounts.put(ids.get(i), flows[i]);
        return new Flowsheet.Feed(temperature, pressure, amounts, List.of());
    }

    static FlowsheetRun alone(V3ColumnInput base, double feedPressure, double steamDeltaT) {
        var params = ColumnDoor.from(base, ColumnComponents.Condenser.AIR_COOLER);
        var units = new ArrayList<Flowsheet.Unit>();
        units.add(new Flowsheet.Unit("T", "column", "T", 0, 0, params));
        var streams = new ArrayList<Flowsheet.Stream>();
        streams.add(new Flowsheet.Stream("feed", "feed", null, new Flowsheet.End("T", "feed"), crude(base, base.feedTemperatureKelvin(), feedPressure)));
        for (String p : new String[] {"overhead", "distillate", "water", "bottoms"}) streams.add(new Flowsheet.Stream(p, p, new Flowsheet.End("T", p), null, null));
        for (int row = 1; row <= base.sideDraws().size(); row++) streams.add(new Flowsheet.Stream("draw " + row, "draw " + row, new Flowsheet.End("T", "draw/" + row), null, null));
        return FlowsheetRuns.run(new Flowsheet(new Flowsheet.Basis(NETWORK, "test", AXIS), units, streams, FlowsheetSolver.Settings.DEFAULT), CATALOG, () -> {}, null);
    }

    static void decompose(String label, V3ColumnInput base, double feedPressure) {
        Object[][] last = new Object[1][];
        ColumnBlockSolve.O10 = o -> last[0] = o;
        FlowsheetRun run;
        try { run = alone(base, feedPressure, 0); } finally { ColumnBlockSolve.O10 = null; }
        if (last[0] == null) { System.out.println("o10 gap " + label + " no capture, status " + run.status() + " " + run.log()); return; }
        var result = (V3ColumnResult) last[0][0];
        var flashed = (FlowStream) last[0][1];
        @SuppressWarnings("unchecked") var valved = (List<FlowStream>) last[0][2];
        @SuppressWarnings("unchecked") var supplies = (List<FlowStream>) last[0][3];
        @SuppressWarnings("unchecked") var products = (Map<String, FlowStream>) last[0][4];
        double gap = (double) last[0][5];
        var ledger = result.dutyLedger().orElseThrow();
        var input = result.problem().input();
        var ids = input.componentBasis().componentIds();
        StringBuilder sb = new StringBuilder("o10 gap " + label + String.format(": gap=%.1f kW Qc=%.3f MW", gap / 1e3, ledger.condenserWatts() / 1e6));
        double simIn = flashed.enthalpy();
        for (var s : supplies) simIn += s.enthalpy();
        double feedDelta = flashed.enthalpy() - ledger.feedEnthalpyWatts();
        double steamSim = 0; for (var s : supplies) steamSim += s.enthalpy();
        double steamDelta = steamSim - ledger.steamEnthalpyWatts();
        sb.append(String.format("%n   IN feed T=%.2f P=%.0f simH=%.4f MW v3H=%.4f MW d=%.1f kW", flashed.temperature(), flashed.pressure(), flashed.enthalpy() / 1e6, ledger.feedEnthalpyWatts() / 1e6, feedDelta / 1e3));
        for (int i = 0; i < supplies.size(); i++) sb.append(String.format("%n   IN steam supply T=%.2f P=%.0f n=%.2f simH=%.4f MW; valved T=%.2f P=%.0f simH=%.4f", supplies.get(i).temperature(), supplies.get(i).pressure(), supplies.get(i).total(), supplies.get(i).enthalpy() / 1e6, valved.get(i).temperature(), valved.get(i).pressure(), valved.get(i).enthalpy() / 1e6));
        sb.append(String.format("%n   IN steam v3H=%.4f MW d=%.1f kW", ledger.steamEnthalpyWatts() / 1e6, steamDelta / 1e3));
        double outDelta = 0, v3Out = 0;
        double[] closure = {0};
        String pkg = input.packageId();
        for (var st : result.streams()) {
            FlowStream sim = products.get(st.streamId());
            java.util.Map<String, Double> hcMap = new java.util.HashMap<>();
            double water = 0, hcTotal = 0;
            for (var f : st.moleFractions()) {
                double n = f.moleFraction() * st.molarFlowMolPerSecond();
                if (f.componentId().equals("Water")) water += n; else { hcMap.merge(f.componentId(), n, Double::sum); hcTotal += n; }
            }
            final double hcT = hcTotal;
            double v3hc = hcT <= 0 ? 0 : MaterialRuntime.with(CATALOG, pkg, () -> {
                var th = V3PengRobinsonThermo.fromRegisteredPackage(pkg); var tid = th.componentBasis().componentIds(); double[] hc = new double[tid.size()]; for (var en : hcMap.entrySet()) { int ix = tid.indexOf(en.getKey()); if (ix < 0) throw new IllegalStateException("no " + en.getKey() + " in " + tid); hc[ix] += en.getValue(); }
                return hcT * th.molarEnthalpy(st.temperatureKelvin(), st.pressurePascal(), hc,
                        st.phase().equals("VAPOR") ? PengRobinsonKernel.Root.VAPOR : PengRobinsonKernel.Root.LIQUID, th.newWorkspace());
            });
            final double wq = water; double v3w = MaterialRuntime.with(CATALOG, pkg, () -> wq * (st.phase().equals("VAPOR") ? V3WaterProperties.vaporMolarEnthalpy(st.temperatureKelvin()) : V3WaterProperties.liquidMolarEnthalpy(st.temperatureKelvin())));
            double v3 = v3hc + v3w;
            v3Out += v3;
            double d = sim.enthalpy() - v3;
            outDelta += d;
            sb.append(String.format("%n   OUT %-22s %s T=%.2f n=%.2f (water %.2f) simH=%.4f v3H=%.4f MW d=%.1f kW", st.streamId(), st.phase(), st.temperatureKelvin(), st.molarFlowMolPerSecond(), water, sim.enthalpy() / 1e6, v3 / 1e6, d / 1e3));
        }
        double v3Closure = ledger.feedEnthalpyWatts() + ledger.steamEnthalpyWatts() + ledger.condenserWatts() + ledger.reboilerWatts() + ledger.stageHeatTotalWatts() - v3Out;
        sb.append(String.format("%n   SUM in-delta=%.1f kW (feed %.1f, steam %.1f) out-delta=%.1f kW v3-closure=%.1f kW -> predicted gap %.1f kW",
                (feedDelta + steamDelta) / 1e3, feedDelta / 1e3, steamDelta / 1e3, outDelta / 1e3, v3Closure / 1e3, (feedDelta + steamDelta - outDelta + v3Closure) / 1e3));
        System.out.println(sb);
    }

    @Test void gaps() {
        var med = CATALOG.presets().column("medium_crude").input(CATALOG);
        var tjl = TiaJuanaTestCatalog.catalog().presets().column("tia_juana").input(TiaJuanaTestCatalog.catalog());
        for (var e : Map.of("med", med, "tjl", tjl).entrySet()) {
            var base = e.getValue();
            double tray = base.topPressurePascal() + (base.feedStageNumber() - 1) * base.stagePressureDropPascal();
            decompose(e.getKey() + " at-tray", base, tray);
            decompose(e.getKey() + " letdown-1MPa", base, 1e6);
        }
    }
}
