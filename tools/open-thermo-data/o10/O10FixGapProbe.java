package com.wormzjl.icheme.science.flowsheet;

import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.TiaJuanaTestCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * E30 landing probe (batch 2026-11-04-open-thermo-data): O10GapProbe's sheet (the column alone, feed at its tray and let down from
 * 1 MPa) on the medium grade and Tia Juana, read off the block's own readings: the energy gap, the condenser duty and the
 * products. No hook. Prints "fix gap" lines; the label is read from tmp/o10-fix/label.txt.
 */
final class O10FixGapProbe {
    private static final MaterialCatalog CATALOG = MaterialCatalog.bundled();
    private static final String NETWORK = CATALOG.presets().network().packageId();
    private static final List<String> AXIS = FluidThermodynamics.forNetwork(CATALOG, NETWORK).components();
    static final String LABEL = label();
    static String label() {
        try { return Files.readString(Path.of("D:/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/cleanup-stale-branches-worktrees-9bc4aa/tmp/o10-fix/label.txt")).trim(); }
        catch (Exception e) { return "?"; }
    }

    static Flowsheet.Feed crude(V3ColumnInput base, double temperature, double pressure) {
        var ids = base.componentBasis().componentIds();
        var amounts = new LinkedHashMap<String, Double>();
        double[] flows = base.feedComponentMolarFlowsMolPerSecond();
        for (int i = 0; i < flows.length; i++) if (flows[i] > 0) amounts.put(ids.get(i), flows[i]);
        return new Flowsheet.Feed(temperature, pressure, amounts, List.of());
    }

    static FlowsheetRun alone(V3ColumnInput base, double feedPressure) {
        var params = ColumnDoor.from(base, ColumnComponents.Condenser.AIR_COOLER);
        var units = new ArrayList<Flowsheet.Unit>();
        units.add(new Flowsheet.Unit("T", "column", "T", 0, 0, params));
        var streams = new ArrayList<Flowsheet.Stream>();
        streams.add(new Flowsheet.Stream("feed", "feed", null, new Flowsheet.End("T", "feed"), crude(base, base.feedTemperatureKelvin(), feedPressure)));
        for (String p : new String[] {"overhead", "distillate", "water", "bottoms"}) streams.add(new Flowsheet.Stream(p, p, new Flowsheet.End("T", p), null, null));
        for (int row = 1; row <= base.sideDraws().size(); row++) streams.add(new Flowsheet.Stream("draw " + row, "draw " + row, new Flowsheet.End("T", "draw/" + row), null, null));
        return FlowsheetRuns.run(new Flowsheet(new Flowsheet.Basis(NETWORK, "test", AXIS), units, streams, FlowsheetSolver.Settings.DEFAULT), CATALOG, () -> {}, null);
    }

    static void report(String label, V3ColumnInput base, double feedPressure) {
        var run = alone(base, feedPressure);
        var readings = run.reports().get("T").readings();
        double gap = readings.getOrDefault("energy_gap", Double.NaN), condenser = readings.getOrDefault("condenser", Double.NaN);
        StringBuilder sb = new StringBuilder(String.format("fix gap %s %s: status=%s gap=%.2f kW condenser=%.4f MW ratio=%.2e", LABEL, label,
                run.status(), gap / 1e3, condenser / 1e6, Math.abs(gap) / condenser));
        for (var e : new java.util.TreeMap<>(run.streams()).entrySet()) {
            if (e.getKey().equals("feed")) continue;
            var s = e.getValue();
            sb.append(String.format("%n   %-10s m=%.4f kg/s T=%.3f K", e.getKey(), s.mass(), s.temperature()));
        }
        System.out.println(sb);
    }

    @Test void gaps() {
        var med = CATALOG.presets().column("medium_crude").input(CATALOG);
        var tjl = TiaJuanaTestCatalog.catalog().presets().column("tia_juana").input(TiaJuanaTestCatalog.catalog());
        for (var e : Map.of("med", med, "tjl", tjl).entrySet()) {
            var base = e.getValue();
            double tray = base.topPressurePascal() + (base.feedStageNumber() - 1) * base.stagePressureDropPascal();
            report(e.getKey() + " at-tray", base, tray);
            report(e.getKey() + " letdown-1MPa", base, 1e6);
        }
    }
}
