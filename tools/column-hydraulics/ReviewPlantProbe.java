import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.column.v3.V3ColumnPresets;
import com.wormzjl.icheme.science.column.v3.V3ColumnSpecification;
import com.wormzjl.icheme.science.column.v3.V3PumparoundSpec;
import com.wormzjl.icheme.science.column.v3.V3SideDrawSpec;
import com.wormzjl.icheme.science.column.v3.V3SteamFeedSpec;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.equipment.design.UnitRatings;
import com.wormzjl.icheme.science.flowsheet.FlowThermo;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetEngine;
import com.wormzjl.icheme.science.flowsheet.FlowsheetRun;
import com.wormzjl.icheme.science.flowsheet.FlowsheetSolver;
import com.wormzjl.icheme.science.flowsheet.sizing.DesignBasis;
import com.wormzjl.icheme.science.flowsheet.sizing.LineSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.Proposal;
import com.wormzjl.icheme.science.flowsheet.sizing.PumpSizing;
import com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor;
import com.wormzjl.icheme.science.flowsheet.unit.ColumnUnit;
import com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The review plant's internal streams in numbers (batch 2026-11-17-column-internal-hydraulics, plan work package 1): the
 * network's default column template at 1/16 on a one-column sheet, as {@code ColumnOperationReviewWorld.sizing} builds it,
 * run and sized by Size all. Prints each stream of the run, each line's steady flow, density, viscosity and speed limit,
 * its speed and its drop over 20 and 30 m at each bore from 0.025 to 0.25 m, and each member pump's load against the
 * casings' rated flows. A line's numbers are the sizer's at design flow (1.10); the probe divides the flows by it.
 * Run from the worktree root after {@code ./gradlew compileJava processResources}, in PowerShell:
 * <pre>java -Dfile.encoding=UTF-8 -cp "build\classes\java\main;build\resources\main;&lt;gson jar&gt;" tools\column-hydraulics\ReviewPlantProbe.java</pre>
 */
public final class ReviewPlantProbe {
    private static final String TOWER = "T-1";
    private static final double SCALE = 1 / 16.0, SUPPLY = 500_000;
    private static final double[] BORES = {.025, .05, .075, .1, .15, .2, .25};

    public static void main(String[] args) {
        var catalog = MaterialCatalog.bundled();
        String network = FluidMaterialCatalog.networkPackage(catalog);
        MaterialRuntime.with(catalog, network, () -> {
            run(catalog, network);
            return null;
        });
    }

    private static void run(MaterialCatalog catalog, String network) {
        var input = scaled(V3ColumnPresets.input(catalog.presets().column(catalog.presets().network().defaultColumnPreset()), catalog), SCALE);
        var model = FluidThermodynamics.forNetwork(catalog, network);
        var params = ColumnDoor.from(input, ColumnComponents.Condenser.EXCHANGER);
        var door = ColumnDoor.of(params);
        var amounts = new LinkedHashMap<String, Double>();
        double[] flows = input.feedComponentMolarFlowsMolPerSecond();
        double feed = 0;
        for (int c = 0; c < flows.length; c++) {
            amounts.merge(input.componentBasis().componentId(c), flows[c], Double::sum);
            feed += flows[c];
        }
        var streams = new ArrayList<Flowsheet.Stream>();
        streams.add(new Flowsheet.Stream("feed", "feed", null, new Flowsheet.End(TOWER, ColumnUnit.FEED), new Flowsheet.Feed(input.feedTemperatureKelvin(), SUPPLY, amounts, List.of())));
        for (int s = 0; s < door.steam().size(); s++) {
            var row = door.steam().get(s);
            streams.add(new Flowsheet.Stream("steam" + (s + 1), "steam" + (s + 1), null, new Flowsheet.End(TOWER, ColumnUnit.steamPort(row)),
                    new Flowsheet.Feed(row.temperature(), SUPPLY, Map.of("Water", row.flow()), List.of())));
        }
        streams.add(product("offgas", ColumnUnit.OVERHEAD));
        streams.add(product("distillate", ColumnUnit.DISTILLATE));
        streams.add(product("water", ColumnUnit.WATER));
        for (int j = 0; j < door.draws().size(); j++) streams.add(product("draw" + (j + 1), ColumnUnit.drawPort(door.draws().get(j))));
        streams.add(product("bottoms", ColumnUnit.BOTTOMS));
        var sheet = new Flowsheet(new Flowsheet.Basis(network, catalog.requirePackage(network).revision(), model.components()),
                List.of(new Flowsheet.Unit(TOWER, "column", TOWER, 0, 0, params)), streams, FlowsheetSolver.Settings.DEFAULT);
        var run = FlowsheetEngine.run(sheet, catalog, () -> {
        }, null, FlowsheetSolver.Progress.NONE);
        System.out.printf(Locale.ROOT, "run %s; feed %.2f mol/s (%.1f kmol/h); top %.3f bar, stage drop %.1f Pa, %d stages%n", run.status(), feed, feed * 3.6,
                input.topPressurePascal() / 1e5, input.stagePressureDropPascal(), input.stageCount());
        if (run.status() != FlowsheetRun.Status.STEADY) {
            System.out.println(run.log());
            return;
        }
        for (var s : run.streams().entrySet())
            if (!s.getValue().empty())
                System.out.printf(Locale.ROOT, "stream %-12s %9.3f mol/s %7.1f C %7.3f bar%n", s.getKey(), s.getValue().total(), s.getValue().temperature() - 273.15, s.getValue().pressure() / 1e5);
        System.out.println("report " + run.reports().get(TOWER).readings());
        for (var pa : door.pumparounds())
            System.out.printf(Locale.ROOT, "pumparound %s draw tray %d return tray %d duty %.1f kW%n", pa.id(), pa.drawTray(), pa.returnTray(), pa.duty() / 1e3);
        var thermo = new FlowThermo(model, catalog, network, () -> {
        });
        var sized = Proposal.sizeAll(sheet, run, thermo, FlowsheetSolver.Progress.NONE).sized();
        double design = DesignBasis.DEFAULT.designFlow();
        System.out.println("line: steady kg/s, m3/s, m3/h, kg/m3, mPa s, bar, speed limit m/s, sized bore; then per bore: speed m/s, drop over 20 m and 30 m, bar (steady flow)");
        for (var e : sized.lines().entrySet()) {
            var line = e.getValue().line();
            if (!line.flows()) continue;
            var steady = new LineSizing.Line(line.service(), line.mass() / design, line.volume() / design, line.density(), line.viscosity(), line.pressure(), line.length(), line.nozzle(),
                    line.speedLimit(), line.solids());
            System.out.printf(Locale.ROOT, "line %-16s %8.4f kg/s %9.6f m3/s %8.2f m3/h %8.2f kg/m3 %7.4f mPa s %6.3f bar limit %6.1f m/s sized %s%n", e.getKey(), steady.mass(), steady.volume(),
                    steady.volume() * 3600, steady.density(), steady.viscosity() * 1e3, steady.pressure() / 1e5, steady.speedLimit(), e.getValue().size() == null ? "none" : e.getValue().size().bore());
            for (double bore : BORES) {
                var size = new LineSizing.Size(bore, 1);
                System.out.printf(Locale.ROOT, "    bore %.3f m: %8.2f m/s, %10.4f bar / 20 m, %10.4f bar / 30 m%n", bore, steady.volume() / (Math.PI * bore * bore / 4),
                        LineSizing.drop(steady, size, 20), LineSizing.drop(steady, size, 30));
            }
        }
        for (var e : sized.units().entrySet())
            if (e.getValue().size() instanceof PumpSizing.Size pump)
                System.out.printf(Locale.ROOT, "pump %-16s discharge %.2f m, rated %.1f m3/h; rating %s%n", e.getKey(), pump.discharge(), UnitRatings.pumpFlow(pump.discharge()), e.getValue().rating().readings());
        for (double d : new double[]{.05, .1, .15}) System.out.printf(Locale.ROOT, "casing %.2f m: rated %.1f m3/h, head %.0f m%n", d, UnitRatings.pumpFlow(d), UnitRatings.PUMP_HEAD);
    }

    private static Flowsheet.Stream product(String id, String port) {
        return new Flowsheet.Stream(id, id, new Flowsheet.End(TOWER, port), null, null);
    }

    /** {@code ColumnOperationReviewWorld.scaled}: the design at {@code scale} of its feed, draws, steam and duties, its area in step. */
    private static V3ColumnInput scaled(V3ColumnInput in, double scale) {
        double[] feed = in.feedComponentMolarFlowsMolPerSecond().clone();
        for (int c = 0; c < feed.length; c++) feed[c] *= scale;
        var draws = in.sideDraws().stream().map(d -> new V3SideDrawSpec(d.trayNumber(), d.molarFlowMolPerSecond() * scale)).toList();
        var steam = in.steamFeeds().stream().map(s -> new V3SteamFeedSpec(s.stageNumber(), s.molarFlowMolPerSecond() * scale, s.temperatureKelvin())).toList();
        var specifications = in.specifications().stream().map(sp -> sp instanceof V3ColumnSpecification.ReboilerDuty r ? (V3ColumnSpecification) new V3ColumnSpecification.ReboilerDuty(r.watts() * scale) : sp).toList();
        var pumparounds = in.pumparounds().stream().map(p -> new V3PumparoundSpec(p.returnTray(), p.drawTray(), p.dutyWatts() * scale, p.split())).toList();
        return new V3ColumnInput(in.schemaVersion(), in.packageId(), in.assayId(), in.componentBasis(), feed, in.feedTemperatureKelvin(), in.stageCount(), in.feedStageNumber(),
                in.topPressurePascal(), in.stagePressureDropPascal(), specifications, draws, steam, pumparounds, in.columnDiameterMetres() * Math.sqrt(scale));
    }
}
