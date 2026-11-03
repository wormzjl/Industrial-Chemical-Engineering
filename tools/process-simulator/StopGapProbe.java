import com.google.gson.JsonParser;
import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.flowsheet.FlowStream;
import com.wormzjl.icheme.science.flowsheet.FlowThermo;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetEngine;
import com.wormzjl.icheme.science.flowsheet.FlowsheetRun;
import com.wormzjl.icheme.science.flowsheet.FlowsheetSolver;
import com.wormzjl.icheme.science.flowsheet.Params;
import com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * W7 of batch 2026-11-02-process-simulator: three numbers a gate must not hold, because they are clocks or one-off questions.
 *
 * 1. The longest gap between two checkpoints in a cold column on the game's path, which is how long Stop can take.
 * 2. What each pass of the sample did, and how the sample's run differs from the W3 gate's, which took one pass fewer.
 * 3. S-8's bubble point, in FlowThermo's own terms, against MoverUnit's test at 0.1 % under the stream's pressure.
 *
 * Run from PowerShell after `./gradlew compileJava processResources`; see the README.
 */
public final class StopGapProbe {
    static final MaterialCatalog CATALOG = MaterialCatalog.bundled();
    static final String NETWORK = CATALOG.presets().network().packageId();
    static final V3ColumnInput BASE = CATALOG.presets().column(CATALOG.presets().network().defaultColumnPreset()).input(CATALOG);
    static final List<String> AXIS = FluidThermodynamics.forNetwork(CATALOG, NETWORK).components();

    static Flowsheet.Unit unit(String id, String type, String settings) {
        return new Flowsheet.Unit(id, type, id, 0, 0, new Params(JsonParser.parseString(settings).getAsJsonObject()));
    }

    static Flowsheet.Stream stream(String id, String from, String fromPort, String to, String toPort, Flowsheet.Feed feed) {
        return new Flowsheet.Stream(id, id, from == null ? null : new Flowsheet.End(from, fromPort), to == null ? null : new Flowsheet.End(to, toPort), feed);
    }

    static Flowsheet.Feed water(double temperature, double pressure, double flow) {
        var amounts = new LinkedHashMap<String, Double>();
        amounts.put("Water", flow);
        return new Flowsheet.Feed(temperature, pressure, amounts, List.of());
    }

    /** The sheet SimulatorRender draws; {@code gate} gives the W3 gate's sample in FlowsheetColumnTest where the two differ. */
    static Flowsheet sample(boolean gate) {
        var door = ColumnDoor.from(BASE, ColumnComponents.Condenser.EXCHANGER);
        door.json().addProperty("condenser_drop", .3e5);
        for (var row : door.rows("pumparounds")) row.json().addProperty("drop", .3e5);
        var ids = BASE.componentBasis().componentIds();
        var crude = new LinkedHashMap<String, Double>();
        double[] flows = BASE.feedComponentMolarFlowsMolPerSecond();
        for (int i = 0; i < flows.length; i++) if (flows[i] > 0) crude.put(ids.get(i), flows[i]);
        var steam = BASE.steamFeeds().getFirst();
        var units = gate ? List.of(unit("P-101", "pump", "{\"pressure\":14e5}"), new Flowsheet.Unit("T-101", "column", "T-101", 0, 0, door),
                unit("E-101", "exchanger", "{\"target\":\"outlet\",\"side\":\"shell\",\"temperature\":473.15,\"tube_drop\":0.5e5,\"shell_drop\":0.3e5}"),
                unit("H-101", "fired_heater", "{\"temperature\":638.15,\"radiant_drop\":5.1e5,\"convection_drop\":2.5e5}"),
                unit("V-101", "valve", "{\"pressure\":2.72e5}"), unit("E-102", "air_cooler", "{\"temperature\":343.15,\"drop\":0.2e5}"), unit("P-102", "pump", "{\"pressure\":6e5}"))
                : List.of(unit("P-101", "pump", "{\"target\":\"outlet\",\"pressure\":14e5,\"rise\":2e5}"), new Flowsheet.Unit("T-101", "column", "T-101", 0, 0, door),
                unit("E-101", "exchanger", "{\"target\":\"outlet\",\"side\":\"shell\",\"temperature\":473.15,\"duty\":1e6,\"area\":100,\"tube_drop\":0.5e5,\"shell_drop\":0.3e5}"),
                unit("H-101", "fired_heater", "{\"coils\":\"series\",\"target\":\"outlet\",\"temperature\":638.15,\"duty\":1e7,\"limit\":90e6,\"radiant_drop\":5.1e5,\"convection_drop\":2.5e5}"),
                unit("V-101", "valve", "{\"target\":\"outlet\",\"pressure\":2.72e5,\"drop\":1e5}"), unit("E-102", "air_cooler", "{\"target\":\"outlet\",\"temperature\":343.15,\"duty\":1e6,\"drop\":0.2e5}"),
                unit("P-102", "pump", "{\"target\":\"outlet\",\"pressure\":6e5,\"rise\":2e5}"));
        var streams = new ArrayList<>(List.of(stream("CRUDE", null, "", "P-101", "in", new Flowsheet.Feed(298.15, 8e5, crude, List.of())), stream("S-1", "P-101", "out", "T-101", "pa/1/a", null), stream("S-2", "T-101", "pa/1/b", "T-101", "pa/2/a", null),
                stream("S-3", "T-101", "pa/2/b", "T-101", "pa/3/a", null), stream("S-4", "T-101", "pa/3/b", "E-101", "tube_a", null), stream("S-5", "E-101", "tube_b", "H-101", "a", null), stream("S-6", "H-101", "b", "V-101", "a", null),
                stream("S-7", "V-101", "b", "T-101", "feed", null), stream("STEAM", null, "", "T-101", "steam/1", water(steam.temperatureKelvin(), 3.5e5, steam.molarFlowMolPerSecond())),
                stream("CWS", null, "", "T-101", "condenser_a", water(303.15, 4e5, 190000 / 3.6)), stream("CWR", "T-101", "condenser_b", null, "", null), stream("OFFGAS", "T-101", "overhead", null, "", null),
                stream("NAPHTHA", "T-101", "distillate", null, "", null), stream("WATER", "T-101", "water", null, "", null), stream("KERO", "T-101", "draw/1", null, "", null), stream("S-8", "T-101", "draw/2", "P-102", "in", null),
                stream("DIESEL", "P-102", "out", null, "", null), stream("AGO", "T-101", "draw/3", null, "", null), stream("S-9", "T-101", "bottoms", "E-101", "shell_a", null), stream("S-10", "E-101", "shell_b", "E-102", "a", null),
                stream("RESIDUE", "E-102", "b", null, "", null)));
        return new Flowsheet(new Flowsheet.Basis(NETWORK, "probe", AXIS), units, streams, FlowsheetSolver.Settings.DEFAULT);
    }

    /** The gaps between checkpoints of one run, in nanoseconds. */
    static final class Gaps implements Runnable {
        long last = System.nanoTime(), longest;
        String where = "";
        final ArrayList<Long> all = new ArrayList<>();

        @Override public void run() {
            long now = System.nanoTime(), gap = now - last;
            all.add(gap);
            if (gap > longest) {
                longest = gap;
                var frames = Thread.currentThread().getStackTrace();
                var at = new StringBuilder();
                for (int i = 2; i < Math.min(9, frames.length); i++) at.append(frames[i].getClassName().replaceAll(".*\\.", "")).append('.').append(frames[i].getMethodName()).append(' ');
                where = at.toString();
            }
            last = System.nanoTime();
        }

        void report(String name, double seconds) {
            long[] sorted = all.stream().mapToLong(Long::longValue).sorted().toArray();
            System.out.printf("%s: %.2f s, %d checkpoints, gap median %.1f us, 99.9 %% %.2f ms, longest %.2f ms%n  longest ended at: %s%n", name, seconds, sorted.length, sorted[sorted.length / 2] / 1e3, sorted[(int) (sorted.length * .999)] / 1e6, longest / 1e6, where);
        }
    }

    static void passes(FlowsheetRun run) {
        for (var pass : run.passes()) {
            var said = new ArrayList<String>();
            for (var step : pass.steps()) if (!Double.isNaN(step.duty()) || step.start() != null) said.add(String.format("%s %s %.2f MW", step.calculation(), step.start() == null ? "" : step.start().name(), step.duty() / 1e6));
            System.out.printf("  pass %d, %.2f s: tear %s moved %.5f %%, %.2f K; %s%n", pass.pass(), pass.seconds(), pass.tear(), 100 * pass.moved(), pass.temperature(), said);
        }
        System.out.println("  " + run.status() + " " + run.loops() + " log " + run.log().stream().map(n -> n.key() + n.arguments()).toList());
    }

    public static void main(String[] args) throws Exception {
        var sheet = sample(false);
        // 1. The gap, on a thread of the run's priority and on a normal one, with the first (cold) run of the JVM thrown away. Pass "bubble" to skip it.
        if (args.length == 0) FlowsheetEngine.run(sheet, CATALOG, () -> {
        });
        for (int priority : args.length > 0 ? new int[0] : new int[]{Thread.NORM_PRIORITY, Thread.MIN_PRIORITY}) {
            var thread = new Thread(() -> {
                var gaps = new Gaps();
                long began = System.nanoTime();
                var run = FlowsheetEngine.run(sheet, CATALOG, gaps);
                gaps.report(Thread.currentThread().getPriority() == Thread.MIN_PRIORITY ? "low priority" : "normal priority", (System.nanoTime() - began) / 1e9);
                passes(run);
            });
            thread.setPriority(priority);
            thread.start();
            thread.join();
        }

        // 2. The W3 gate's sheet, for the pass count.
        if (args.length == 0) {
            System.out.println("W3 gate's sheet:");
            passes(FlowsheetEngine.run(sample(true), CATALOG, () -> {
            }));
        }

        // 3. S-8.
        var run = FlowsheetEngine.run(sheet, CATALOG, () -> {
        });
        var s8 = run.streams().get("S-8");
        MaterialRuntime.with(CATALOG, NETWORK, () -> {
            var thermo = new FlowThermo(FluidThermodynamics.forNetwork(CATALOG, NETWORK), CATALOG, NETWORK, () -> {
            });
            double p = s8.pressure(), t = s8.temperature();
            double bubble = thermo.bubblePressure(t, s8.amountsView());
            System.out.printf("S-8: %.2f K, %.0f Pa, vapour share %.3e, %.3f kg/s%n", t, p, thermo.vapourShare(s8), s8.mass());
            System.out.printf("world flash bubble pressure at that T: %.0f Pa, %.3f %% of the stream's%n", bubble, 100 * bubble / p);
            for (double under : new double[]{0, .001, .01, .05, .1, .2, .25, .27}) System.out.printf("  %.1f %% under: boils %s, vapour share %.3e%n", 100 * under, thermo.boils(t, p * (1 - under), s8.amountsView()), thermo.vapourShare(thermo.atTemperature(t, p * (1 - under), s8.amountsView(), s8.solids())));
            return null;
        });
        var report = run.reports().get("P-102");
        System.out.println("P-102 notes " + report.notes() + " readings " + report.readings());
        var trays = run.reports().get("T-101").display();
        var door = ColumnDoor.of(sheet.units().get(1).params());
        var draw = door.draws().get(1);
        var node = trays.inspection().orElseThrow().nodes().get(draw.tray());
        double steam = node.waterVaporMolPerSecond() / (node.vaporMolPerSecond() + node.waterVaporMolPerSecond());
        System.out.printf("draw tray %d: %.2f K, %.0f Pa; vapour %.2f mol/s organic, %.2f mol/s steam, steam share %.4f, oil's partial pressure %.0f Pa; free water %.3f mol/s; S-8 water %.5f of its moles%n", draw.tray(), node.temperatureKelvin(), node.pressurePascal(),
                node.vaporMolPerSecond(), node.waterVaporMolPerSecond(), steam, node.pressurePascal() * (1 - steam), node.freeWaterMolPerSecond(), s8.amountsView()[s8.amountsView().length - 1] / s8.total());
    }
}
