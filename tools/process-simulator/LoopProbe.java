import com.wormzjl.icheme.science.column.v3.V3ColumnCalculator;
import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.column.v3.V3ColumnOutcome;
import com.wormzjl.icheme.science.column.v3.V3ColumnResult;
import com.wormzjl.icheme.science.column.v3.V3InitializationOptions;
import com.wormzjl.icheme.science.column.v3.V3NeuralRegistry;
import com.wormzjl.icheme.science.column.v3.V3NeuralSeed;
import com.wormzjl.icheme.science.column.v3.V3SolveStrategy;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleUnaryOperator;

/**
 * The owner's loops (batch 2026-11-02-process-simulator, message of 2026-11-02): the pumparound heats the crude through the
 * cooler inside the column block, the residue preheats it further, and a furnace sets the final temperature; then the same
 * train with the furnace on a fixed duty, so the feed moves and the column answers; then a material recycle. Each loop runs to
 * steady state with direct substitution and with bounded Wegstein, one warm column a pass. It is the plan's solver by hand, to
 * size a pass count and to read the sheets' numbers. Run as {@code ClientRunProbe}.
 */
public final class LoopProbe {
    static FluidThermodynamics thermo;
    static MaterialCatalog catalog;
    static String network;
    static List<String> axis;
    static V3ColumnInput base;
    static V3InitializationOptions options;
    static com.wormzjl.icheme.science.column.v3.V3NeuralInitializer model;
    static List<String> ids;
    static int columns;
    static long columnNanos;

    static FluidThermodynamics.State tp(double t, double p, double[] n) {
        return MaterialRuntime.with(catalog, network, () -> thermo.flashTP(t, p, n, () -> {}));
    }

    static FluidThermodynamics.State ph(double guess, double p, double h, double[] n) {
        var s = MaterialRuntime.with(catalog, network, () -> thermo.equilibriumPH(guess, p, h, n, () -> {}));
        if (s == null) throw new IllegalStateException("PH flash failed at " + p);
        return s;
    }

    static double[] amounts(FluidThermodynamics.State s) {
        int n = s.componentCount();
        double[] a = new double[n];
        for (int i = 0; i + 1 < n; i++) a[i] = s.liquidView()[i] + s.vaporView()[i];
        a[n - 1] = s.waterLiquid() + s.waterVapor();
        return a;
    }

    static double[] sum(double[] a, double[] b, double f) {
        double[] out = a.clone();
        for (int i = 0; i < a.length; i++) out[i] += f * b[i];
        return out;
    }

    /** The column on a feed on the network's axis; water and nitrogen, trace only here, stay out of V3's axis. */
    static V3ColumnResult column(double[] feed, double feedT, double[] duties, V3NeuralSeed[] warm) {
        double[] flows = new double[ids.size()];
        for (int i = 0; i < ids.size(); i++) flows[i] = feed[axis.indexOf(ids.get(i))];
        var pa = base.pumparounds();
        var specs = new java.util.ArrayList<com.wormzjl.icheme.science.column.v3.V3PumparoundSpec>();
        for (int i = 0; i < pa.size(); i++) specs.add(new com.wormzjl.icheme.science.column.v3.V3PumparoundSpec(pa.get(i).returnTray(), pa.get(i).drawTray(), duties[i], pa.get(i).split()));
        var in = new V3ColumnInput(base.schemaVersion(), base.packageId(), base.assayId(), base.componentBasis(), flows, feedT, base.stageCount(), base.feedStageNumber(), base.topPressurePascal(),
                base.stagePressureDropPascal(), base.specifications(), base.sideDraws(), base.steamFeeds(), specs, base.columnDiameterMetres());
        final var seed = warm[0];
        long t0 = System.nanoTime();
        V3ColumnOutcome outcome = MaterialRuntime.with(catalog, base.packageId(), () -> seed == null ? V3ColumnCalculator.calculate(in, () -> {}, 0, 0, options, model, V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO)
                : V3ColumnCalculator.calculateWithStrategy(in, () -> {}, V3SolveStrategy.AGGRESSIVE, seed, V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO));
        columnNanos += System.nanoTime() - t0;
        columns++;
        if (!(outcome instanceof V3ColumnOutcome.Success success)) throw new IllegalStateException(((V3ColumnOutcome.Failure) outcome).summary());
        warm[0] = success.result().warmSeed().orElse(seed);
        return success.result();
    }

    static FluidThermodynamics.State product(V3ColumnResult r, String name) {
        var s = r.streams().stream().filter(x -> x.displayName().startsWith(name)).findFirst().orElseThrow();
        double[] n = new double[axis.size()];
        for (var c : s.moleFractions()) n[axis.indexOf(c.componentId())] += c.moleFraction() * s.molarFlowMolPerSecond();
        return tp(s.temperatureKelvin(), s.pressurePascal(), n);
    }

    /** One pass's results. */
    record Pass(double feedT, double furnace, double[] coldOut, double residueDuty, V3ColumnResult column, FluidThermodynamics.State residue) {}

    /**
     * The train from the crude to the valve's outlet, given the column's residue. {@code duty} NaN holds the furnace at 365 C.
     * {@code recycle} is what comes back to the crude, cooled to 70 C and pumped to the crude's 8 bar; the mixer adds it.
     */
    static Pass train(double[] fresh, double[] recycle, double[] pas, V3ColumnResult result, double duty) {
        return train(fresh, recycle, pas, result, product(result, "Bottoms"), duty);
    }

    /** The train with the exchanger's hot side given: the bottoms the column made, or the tear's guess. */
    static Pass train(double[] fresh, double[] recycle, double[] pas, V3ColumnResult result, FluidThermodynamics.State bottoms, double duty) {
        var crudeIn = tp(298.15, 8e5, fresh);
        double[] crude = sum(fresh, recycle, 1);
        var cold = Arrays.stream(recycle).sum() == 0 ? crudeIn : ph(305, 8e5, crudeIn.enthalpy() + tp(343.15, 8e5, recycle).enthalpy(), crude);
        double pumped = 14e5, rho = cold.mass() / cold.volume();
        var s = ph(cold.temperature() + 0.4, pumped, cold.enthalpy() + cold.mass() * (pumped - 8e5) / rho, crude);
        double[] out = new double[pas.length];
        for (int i = 0; i < pas.length; i++) {
            s = ph(s.temperature() + 40, s.pressure() - 0.3e5, s.enthalpy() - pas[i], crude);
            out[i] = s.temperature();
        }
        var hotOut = tp(473.15, bottoms.pressure() - 0.3e5, amounts(bottoms));
        double q = bottoms.enthalpy() - hotOut.enthalpy();
        var preheated = ph(s.temperature() + 70, s.pressure() - 0.5e5, s.enthalpy() + q, crude);
        var coil = Double.isNaN(duty) ? tp(638.15, 5e5, crude) : ph(620, 5e5, preheated.enthalpy() + duty, crude);
        double fired = coil.enthalpy() - preheated.enthalpy();
        var feed = ph(coil.temperature() - 6, 2.72e5, coil.enthalpy(), crude);
        return new Pass(feed.temperature(), fired, new double[]{out[0], out[1], out[2], preheated.temperature()}, q, result, hotOut);
    }

    /** Scalar fixed point on {@code g} from {@code x}: direct, or Wegstein with its slope factor in [-5, 0]. */
    static int solve(String label, DoubleUnaryOperator g, double x, boolean wegstein, double tolerance) {
        double lastX = Double.NaN, lastG = Double.NaN;
        for (int pass = 1; pass <= 30; pass++) {
            double y = g.applyAsDouble(x), change = Math.abs(y - x);
            System.out.printf(Locale.ROOT, "  %s pass %d: in %.3f, out %.3f, change %.2e K%n", label, pass, x - 273.15, y - 273.15, change);
            if (change < tolerance) return pass;
            double next = y;
            if (wegstein && !Double.isNaN(lastX) && x != lastX) {
                double s = (y - lastG) / (x - lastX), q = Math.clamp(s / (s - 1), -5.0, 0.0);
                next = q * x + (1 - q) * y;
            }
            lastX = x;
            lastG = y;
            x = next;
        }
        return -1;
    }

    public static void main(String[] args) {
        catalog = MaterialCatalog.bundled();
        base = catalog.presets().column(catalog.presets().network().defaultColumnPreset()).input(catalog);
        network = catalog.presets().network().packageId();
        thermo = MaterialRuntime.with(catalog, network, () -> FluidThermodynamics.forNetwork(catalog, network));
        axis = thermo.components();
        ids = base.componentBasis().componentIds();
        options = new V3InitializationOptions(V3InitializationOptions.Mode.LNN_FIRST, V3InitializationOptions.WetStart.AUTO, 16, 2_000, V3InitializationOptions.Correction.PROGRESS);
        model = V3NeuralRegistry.bundled().bind(catalog, base);
        double[] crude = new double[axis.size()];
        for (int i = 0; i < ids.size(); i++) crude[axis.indexOf(ids.get(i))] = base.feedComponentMolarFlowsMolPerSecond()[i];
        double[] pas = base.pumparounds().stream().mapToDouble(p -> p.dutyWatts()).toArray(), none = new double[crude.length];

        // A: the owner's example. The furnace holds its outlet, so the feed cannot move: a loop that closes on its second pass.
        System.out.println("A: PA and residue preheat, furnace holds 365 C");
        var warm = new V3NeuralSeed[1];
        Pass[] last = new Pass[1];
        long t0 = System.nanoTime();
        int passes = solve("A", t -> {
            var r = column(crude, t, pas, warm);
            last[0] = train(crude, none, pas, r, Double.NaN);
            return last[0].feedT();
        }, 632.0, false, 0.01);
        var a = last[0];
        System.out.printf(Locale.ROOT, "  %d passes, %.0f ms; crude after PA1 %.1f, PA2 %.1f, PA3 %.1f, residue %.1f C; residue duty %.2f MW; furnace %.2f MW (no preheat 145.56)%n", passes, (System.nanoTime() - t0) / 1e6,
                a.coldOut()[0] - 273.15, a.coldOut()[1] - 273.15, a.coldOut()[2] - 273.15, a.coldOut()[3] - 273.15, a.residueDuty() / 1e6, a.furnace() / 1e6);

        // The pumparounds' hot side and the cooling water, which feed nothing back: read once on the final column.
        var nodes = a.column().inspection().orElseThrow().nodes();
        double[] circulation = {3000, 2000, 1000};
        double[] coldIn = {25.1 + 273.15, a.coldOut()[0], a.coldOut()[1]};
        for (int i = 0; i < 3; i++) {
            var pa = base.pumparounds().get(i);
            var node = nodes.get(pa.drawTray());
            double[] n = new double[axis.size()];
            for (int k = 0; k < ids.size(); k++) n[axis.indexOf(ids.get(k))] = node.liquidFractions().get(k) * circulation[i] / 3.6;
            var hot = tp(node.temperatureKelvin(), node.pressurePascal() + 3e5, n);
            var back = ph(node.temperatureKelvin() - 60, node.pressurePascal() + 2.7e5, hot.enthalpy() + pas[i], n);
            System.out.printf(Locale.ROOT, "  PA%d: %.2f MW, %.0f kmol/h %.1f t/h, %.1f to %.1f C; crude %.1f to %.1f C; approach hot end %.1f K, cold end %.1f K%n", i + 1, -pas[i] / 1e6, circulation[i], hot.mass() * 3.6,
                    node.temperatureKelvin() - 273.15, back.temperature() - 273.15, coldIn[i] - 273.15, a.coldOut()[i] - 273.15, node.temperatureKelvin() - a.coldOut()[i], back.temperature() - coldIn[i]);
        }
        double condenser = a.column().dutyLedger().orElseThrow().condenserWatts();
        double[] water = new double[axis.size()];
        water[axis.indexOf("Water")] = 190000 / 3.6;
        var cw = tp(303.15, 4e5, water);
        var cwOut = ph(310, 3.7e5, cw.enthalpy() - condenser, water);
        System.out.printf(Locale.ROOT, "  condenser %.2f MW: cooling water 190000 kmol/h, %.0f t/h, 30.0 to %.1f C%n", condenser / 1e6, cw.mass() * 3.6, cwOut.temperature() - 273.15);
        var residue = tp(343.15, a.residue().pressure() - 0.2e5, amounts(a.residue()));
        System.out.printf(Locale.ROOT, "  bottoms %.1f C to E-101 outlet 200.0 C, air cooler %.2f MW to 70.0 C%n", product(a.column(), "Bottoms").temperature() - 273.15, (residue.enthalpy() - a.residue().enthalpy()) / 1e6);
        for (var s : a.column().streams())
            System.out.printf(Locale.ROOT, "  %-24s %8.1f kmol/h %8.2f t/h %6.1f C %.3f bar%n", s.displayName(), s.molarFlowMolPerSecond() * 3.6, s.massFlowKgPerSecond() * 3.6, s.temperatureKelvin() - 273.15, s.pressurePascal() / 1e5);

        // B: the furnace on a fixed duty, 8 MW short of A: the feed moves, the column answers, the residue's preheat changes.
        double fixed = a.furnace() - 8e6;
        for (boolean w : new boolean[]{false, true}) {
            System.out.println("B: furnace on a fixed duty " + String.format(Locale.ROOT, "%.2f MW", fixed / 1e6) + (w ? ", Wegstein" : ", direct"));
            columns = 0;
            columnNanos = 0;
            t0 = System.nanoTime();
            var seed = new V3NeuralSeed[]{warm[0]};
            passes = solve("B", t -> {
                var r = column(crude, t, pas, seed);
                last[0] = train(crude, none, pas, r, fixed);
                return last[0].feedT();
            }, a.feedT(), w, 0.01);
            System.out.printf(Locale.ROOT, "  %d passes, %d columns, %.0f ms, %.0f ms in columns; feed %.2f C, bottoms %.2f C, residue duty %.2f MW%n", passes, columns, (System.nanoTime() - t0) / 1e6, columnNanos / 1e6,
                    last[0].feedT() - 273.15, product(last[0].column(), "Bottoms").temperature() - 273.15, last[0].residueDuty() / 1e6);
        }

        // B again, torn where the sheets tear it.
        System.out.println("B, torn at S-9: furnace on a fixed duty, Wegstein on one factor");
        tearBottoms(crude, none, pas, fixed, a, warm[0]);

        // C: a material recycle: a share of the cooled residue back to the crude through a mixer ahead of P-101.
        for (double share : new double[]{0.15, 0.30}) {
            for (boolean w : new boolean[]{false, true}) {
                System.out.printf(Locale.ROOT, "C: %.0f %% of the residue back to the crude%s%n", share * 100, w ? ", Wegstein" : ", direct");
                recycle(crude, pas, share, w, warm[0]);
            }
        }

        // D: no way out: all of the residue comes back, so its heavy ends have nowhere to go and no steady state exists.
        for (boolean w : new boolean[]{false, true}) {
            System.out.println("D: all of the residue back to the crude" + (w ? ", Wegstein" : ", direct"));
            try {
                recycle(crude, pas, 1.0, w, warm[0]);
            } catch (IllegalStateException e) {
                System.out.println("  stopped: " + e.getMessage());
            }
        }
    }

    /** The recycle's amounts as the tear, with the furnace on hold; Wegstein on the total flow's slope, one factor for every component. */
    static void recycle(double[] crude, double[] pas, double share, boolean wegstein, V3NeuralSeed start) {
        var seed = new V3NeuralSeed[]{start};
        double[] x = new double[crude.length], lastX = null, lastG = null;
        columns = 0;
        columnNanos = 0;
        long t0 = System.nanoTime();
        double feedT = 632.46;
        for (int pass = 1; pass <= 30; pass++) {
            double[] feed = sum(crude, x, 1);
            var r = column(feed, feedT, pas, seed);
            var p = train(crude, x, pas, r, Double.NaN);
            feedT = p.feedT();
            double[] y = amounts(p.residue());
            for (int i = 0; i < y.length; i++) y[i] *= share;
            double change = 0, total = 0;
            int most = 0;
            for (int i = 0; i < y.length; i++) {
                if (Math.abs(y[i] - x[i]) > change) most = i;
                change = Math.max(change, Math.abs(y[i] - x[i]));
                total += y[i];
            }
            double sumX = Arrays.stream(x).sum();
            System.out.printf(Locale.ROOT, "  C pass %d: recycle in %.2f, out %.2f kmol/h, largest component change %.3f kmol/h (%s), column feed %.1f kmol/h%n", pass, sumX * 3.6, total * 3.6, change * 3.6, axis.get(most), Arrays.stream(feed).sum() * 3.6);
            if (change < 1e-4 * Math.max(total, 1e-9)) {
                var fresh = tp(298.15, 8e5, crude);
                var net = tp(343.15, p.residue().pressure(), sum(amounts(p.residue()), y, -1));
                double products = 0;
                for (var s : r.streams()) if (!s.displayName().startsWith("Bottoms")) products += s.massFlowKgPerSecond();
                double steam = base.steamFeeds().getFirst().molarFlowMolPerSecond() * 0.018015;
                System.out.printf(Locale.ROOT, "  %d passes, %d columns, %.0f ms, %.0f ms in columns; mass in %.3f, out %.3f t/h; furnace %.2f MW%n", pass, columns, (System.nanoTime() - t0) / 1e6, columnNanos / 1e6,
                        (fresh.mass() + steam) * 3.6, (products + net.mass()) * 3.6, p.furnace() / 1e6);
                return;
            }
            double[] next = y;
            if (wegstein && lastX != null) {
                double s = (Arrays.stream(y).sum() - Arrays.stream(lastG).sum()) / (Arrays.stream(x).sum() - Arrays.stream(lastX).sum()), q = Math.clamp(s / (s - 1), -5.0, 0.0);
                next = new double[y.length];
                for (int i = 0; i < y.length; i++) next[i] = Math.max(0, q * x[i] + (1 - q) * y[i]);
            }
            lastX = x;
            lastG = y;
            x = next;
        }
        System.out.printf(Locale.ROOT, "  no steady state in 30 passes, %d columns, %.0f ms%n", columns, (System.nanoTime() - t0) / 1e6);
    }

    /** B with S-9's temperature and amounts as the guess, as the plan's solver tears it; one Wegstein factor, from the temperature's slope. */
    static void tearBottoms(double[] crude, double[] none, double[] pas, double fixed, Pass from, V3NeuralSeed start) {
        var seed = new V3NeuralSeed[]{start};
        var bottoms = product(from.column(), "Bottoms");
        double t = bottoms.temperature(), lastT = Double.NaN, lastOut = Double.NaN;
        double[] n = amounts(bottoms);
        columns = 0;
        columnNanos = 0;
        long t0 = System.nanoTime();
        for (int pass = 1; pass <= 30; pass++) {
            var guess = tp(t, bottoms.pressure(), n);
            var r = column(crude, train(crude, none, pas, from.column(), guess, fixed).feedT(), pas, seed);
            var y = product(r, "Bottoms");
            double change = Math.abs(y.temperature() - t);
            System.out.printf(Locale.ROOT, "  B pass %d: S-9 in %.2f, out %.2f C, change %.2e K%n", pass, t - 273.15, y.temperature() - 273.15, change);
            if (change < 0.01) {
                System.out.printf(Locale.ROOT, "  %d passes, %d columns, %.0f ms%n", pass, columns, (System.nanoTime() - t0) / 1e6);
                return;
            }
            double nextT = y.temperature();
            double[] next = amounts(y);
            if (pass > 1) {
                double s = (y.temperature() - lastOut) / (t - lastT), q = Math.clamp(s / (s - 1), -5.0, 0.0);
                nextT = q * t + (1 - q) * y.temperature();
                for (int i = 0; i < next.length; i++) next[i] = Math.max(0, q * n[i] + (1 - q) * next[i]);
            }
            lastT = t;
            lastOut = y.temperature();
            t = nextT;
            n = next;
        }
        System.out.println("  no steady state in 30 passes");
    }
}
