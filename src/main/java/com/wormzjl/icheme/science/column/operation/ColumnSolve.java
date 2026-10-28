package com.wormzjl.icheme.science.column.operation;

import com.wormzjl.icheme.science.column.v3.V3ColumnCalculator;
import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.column.v3.V3ColumnOutcome;
import com.wormzjl.icheme.science.column.v3.V3ColumnSpecification;
import com.wormzjl.icheme.science.column.v3.V3NeuralSeed;
import com.wormzjl.icheme.science.column.v3.V3PumparoundSpec;
import com.wormzjl.icheme.science.column.v3.V3SideDrawSpec;
import com.wormzjl.icheme.science.column.v3.V3SolveStrategy;
import com.wormzjl.icheme.science.column.v3.V3SteamFeedSpec;
import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A running column's solve (batch 2026-10-28-column-operation, plan sections 6 and 11): the design's trays and setpoints
 * at the state the column actually took, solved by V3 on the network's package, and the split table of the answer with
 * each product's specific enthalpy on the network's basis. Runs on a worker; reads no world.
 *
 * <p>The table's condenser duty per feed is the closure on the network's basis at the solved state, not V3's own duty:
 * the cycle closes its energy with the network's enthalpies, and V3's package differs from them by a few tenths of a
 * percent of the throughput's enthalpy (C1 review, limits), which is several percent of a small condenser. The closure
 * band then measures drift, not the basis. V3's duty is kept beside it for the status line.
 */
public final class ColumnSolve {
    /** V3's superheat rule for a steam feed, K above saturation at its stage. */
    static final double SUPERHEAT = 5;
    private static final int TEMPERATURE_ITERATIONS = 40;
    /** The step of a partial molar enthalpy's difference, a share of the product's moles. */
    static final double PARTIAL_STEP = 1e-5;

    private ColumnSolve() {
    }

    /**
     * A steam inlet's water taken, mol/s, and its specific enthalpy, J/mol on the network's basis; NaN at a start, which
     * takes {@code temperature} (K), otherwise the guess the enthalpy is inverted from.
     */
    public record Steam(double rate, double temperature, double enthalpy) {
    }

    /**
     * What the column took, as rates.
     *
     * @param feed         mol/s on the network's axis (water last); the steam ports' hydrocarbons are feed (plan 11)
     * @param feedEnthalpy J/mol on the network's basis; NaN at a start, which solves at the design's feed temperature
     * @param steam        one per steam inlet of the design
     */
    public record Taken(double[] feed, double feedEnthalpy, List<Steam> steam) {
        public Taken {
            feed = feed.clone();
            steam = List.copyOf(steam);
        }

        @Override
        public double[] feed() {
            return feed.clone();
        }

        public double feedRate() {
            return Arrays.stream(feed).sum();
        }
    }

    /** One solve: why, the design's calculator input, the setpoints, the state taken, and the last accepted seed (null: cold). */
    public record Job(DriftBands.Trigger trigger, V3ColumnInput design, Setpoints setpoints, Taken taken, V3NeuralSeed seed) {
        public Job {
            Objects.requireNonNull(trigger, "trigger");
            Objects.requireNonNull(design, "design");
            Objects.requireNonNull(setpoints, "setpoints");
            Objects.requireNonNull(taken, "taken");
        }
    }

    /** A solve's answer. */
    public sealed interface Outcome permits Solved, Failed {
    }

    /**
     * An accepted solve.
     *
     * @param enthalpy        each product's partial molar enthalpies on the axis, J/mol, network basis, at its solved temperature and
     *                        pressure: a product of moles n holds the energy sum n_i h_i, exact at its solved composition and
     *                        first order beside it (plan 5.3: its actual composition, not the solved one's specific enthalpy)
     * @param solvedCondenser V3's own condenser duty, W (column sign)
     * @param route           V3's solve path
     * @param note            what the input left out (feed water too cold to enter as steam), or empty
     */
    public record Solved(SplitTable table, double[][] enthalpy, V3NeuralSeed seed, double solvedCondenser, String route, String note) implements Outcome {
        public Solved {
            Objects.requireNonNull(table, "table");
            enthalpy = copy(enthalpy);
            if (enthalpy.length != table.products().size()) throw new IllegalArgumentException("One enthalpy row per product");
            for (double[] row : enthalpy) if (row.length != table.axis().size()) throw new IllegalArgumentException("A partial enthalpy per axis component");
        }

        @Override
        public double[][] enthalpy() {
            return copy(enthalpy);
        }
    }

    /** A refused or failed solve, and why. */
    public record Failed(String reason) implements Outcome {
    }

    /** Solves {@code job} with the network's model; {@code screen} is V3's liquid-supply screen ratio. */
    public static Outcome run(Job job, FluidThermodynamics model, MaterialCatalog catalog, double screen, Runnable checkpoint) {
        String packageId = model.domain().packageId();
        try {
            return MaterialRuntime.with(catalog, packageId, () -> solve(job, model, catalog, packageId, screen, checkpoint));
        } catch (IllegalArgumentException refused) {
            return new Failed(refused.getMessage());
        }
    }

    private static Outcome solve(Job job, FluidThermodynamics model, MaterialCatalog catalog, String packageId, double screen, Runnable checkpoint) {
        var design = job.design();
        var axis = model.components();
        int m = axis.size(), water = axis.indexOf(SplitTable.WATER);
        var taken = job.taken();
        double[] feed = taken.feed();
        if (feed.length != m) throw new IllegalArgumentException("The feed taken is on the network's axis");
        double rate = taken.feedRate();
        if (!(rate > 0)) return new Failed("no feed");
        double feedPressure = design.topPressurePascal() + design.feedStageNumber() * design.stagePressureDropPascal();
        // The feed's temperature: its taken enthalpy at the feed tray.
        double h = Double.isNaN(taken.feedEnthalpy()) ? specific(model, design.feedTemperatureKelvin(), feedPressure, feed, checkpoint) : taken.feedEnthalpy();
        double t = temperature(model, feed, feedPressure, h, design.feedTemperatureKelvin(), checkpoint);
        if (Double.isNaN(t)) return new Failed("the feed's enthalpy has no temperature");
        double feedSlope = specific(model, t + .5, feedPressure, feed, checkpoint) - specific(model, t - .5, feedPressure, feed, checkpoint);
        // The V3 input: the network's package, the feed by component id, the design's trays and ratios at the taken rate.
        var basis = V3PengRobinsonThermo.fromRegisteredPackage(packageId).componentBasis();
        double[] flows = new double[basis.componentCount()];
        for (int c = 0; c < flows.length; c++) {
            int i = axis.indexOf(basis.componentId(c));
            if (i >= 0 && i != water) flows[c] = feed[i];
        }
        var steamFeeds = new ArrayList<V3SteamFeedSpec>();
        double[] steamRatios = new double[taken.steam().size()];
        var designSteam = design.steamFeeds();
        if (taken.steam().size() != designSteam.size()) throw new IllegalArgumentException("One steam taken per design steam");
        double[] steamT = new double[designSteam.size()];
        for (int j = 0; j < designSteam.size(); j++) {
            var s = taken.steam().get(j);
            steamRatios[j] = s.rate() / rate;
            if (!(s.rate() > 0) || water < 0) continue;
            double[] steam = new double[m];
            steam[water] = s.rate();
            double p = design.topPressurePascal() + designSteam.get(j).stageNumber() * design.stagePressureDropPascal();
            steamT[j] = Double.isNaN(s.enthalpy()) ? s.temperature() : temperature(model, steam, p, s.enthalpy(), s.temperature(), checkpoint);
            if (Double.isNaN(steamT[j])) return new Failed("the steam's enthalpy has no temperature");
            steamFeeds.add(new V3SteamFeedSpec(designSteam.get(j).stageNumber(), s.rate(), steamT[j]));
        }
        // Feed water enters as a steam feed at the feed tray (Q9) where it is superheated there; else it borrows its row.
        String note = "";
        if (water >= 0 && feed[water] > 0) {
            boolean superheated = model.saturationPressure(t - SUPERHEAT) < feedPressure;
            if (superheated && steamFeeds.size() < V3ColumnInput.MAX_STEAM_FEEDS)
                steamFeeds.add(new V3SteamFeedSpec(design.feedStageNumber(), feed[water], t));
            else note = superheated ? "feed water: no steam feed left, it follows the distillate" : "feed water below steam at the feed tray: it follows the distillate";
        }
        var setpoints = job.setpoints();
        var draws = new ArrayList<V3SideDrawSpec>();
        double[] drawRatios = setpoints.drawsPerFeed();
        for (int j = 0; j < design.sideDraws().size(); j++) draws.add(new V3SideDrawSpec(design.sideDraws().get(j).trayNumber(), drawRatios[j] * rate));
        var pumparounds = new ArrayList<V3PumparoundSpec>();
        double[] paRatios = setpoints.pumparoundsPerFeed();
        for (int j = 0; j < design.pumparounds().size(); j++) {
            var p = design.pumparounds().get(j);
            pumparounds.add(new V3PumparoundSpec(p.returnTray(), p.drawTray(), paRatios[j] * rate, p.split()));
        }
        var input = new V3ColumnInput(design.schemaVersion(), packageId, design.assayId(), basis, flows, t, design.stageCount(), design.feedStageNumber(),
                design.topPressurePascal(), design.stagePressureDropPascal(),
                List.of(new V3ColumnSpecification.CondenserOutletTemperature(setpoints.condenserTemperature()),
                        new V3ColumnSpecification.OrganicRefluxRatio(setpoints.refluxRatio()),
                        new V3ColumnSpecification.ReboilerDuty(setpoints.reboilerPerFeed() * rate)),
                draws, steamFeeds, pumparounds, design.columnDiameterMetres());
        // A seed with modelled free water is refused by the warm route and sends a seeded solve down the strict cold chain
        // (C1 review, section 3): such a table solves with no seed, the fast first solve.
        var seed = job.seed();
        if (seed != null) for (boolean wet : seed.wetTrays()) if (wet) {
            seed = null;
            break;
        }
        var outcome = V3ColumnCalculator.calculateWithStrategy(input, checkpoint::run, V3SolveStrategy.AGGRESSIVE, seed, screen);
        if (outcome instanceof V3ColumnOutcome.Failure failure) return new Failed(failure.code().name() + ": " + failure.summary());
        var success = (V3ColumnOutcome.Success) outcome;
        // The table on the network's axis, its reference the state taken.
        double[] z = new double[m];
        for (int i = 0; i < m; i++) z[i] = feed[i] / rate;
        var reference = new SplitTable.Reference(z, h, feedSlope, steamRatios, rate / setpoints.designFeedRate(), setpoints);
        var solved = SplitTable.of(axis, boilingPoints(catalog, packageId, axis), input, success.result(), reference);
        // Each product's partial molar enthalpies and the closure on the network's basis.
        double[] pooled = feed.clone();
        double in = h * rate;
        for (int j = 0; j < designSteam.size(); j++) {
            var s = taken.steam().get(j);
            if (!(s.rate() > 0) || water < 0) continue;
            double[] steam = new double[m];
            steam[water] = s.rate();
            pooled[water] += s.rate();
            double p = design.topPressurePascal() + designSteam.get(j).stageNumber() * design.stagePressureDropPascal();
            in += Double.isNaN(s.enthalpy()) ? model.flashTP(steamT[j], p, steam, checkpoint).enthalpy() : s.enthalpy() * s.rate();
        }
        double[][] split = solved.split(pooled);
        double[][] enthalpy = new double[split.length][];
        double out = 0;
        for (int k = 0; k < split.length; k++) {
            var product = solved.products().get(k);
            double n = Arrays.stream(split[k]).sum();
            enthalpy[k] = partial(model, product.temperature(), product.pressure(), n > 0 ? split[k] : pooled, checkpoint);
            for (int i = 0; i < m; i++) out += enthalpy[k][i] * split[k][i];
        }
        double[] pa = solved.pumparoundPerFeed();
        double paTotal = 0;
        for (double q : pa) paTotal += q * rate;
        double condenser = (out - in - solved.reboilerPerFeed() * rate - paTotal) / rate;
        var table = new SplitTable(solved.axis(), solved.products(), solved.reboilerPerFeed(), pa, condenser, reference, solved.held());
        return new Solved(table, enthalpy, success.result().warmSeed().orElse(null), solved.condenserPerFeed() * rate,
                success.diagnostics().solvePath(), note);
    }

    /** The temperature at which {@code moles} at {@code p} has the specific enthalpy {@code h}, from {@code guess}: a secant on the model; NaN where none. */
    static double temperature(FluidThermodynamics model, double[] moles, double p, double h, double guess, Runnable checkpoint) {
        double t = guess, h0 = specific(model, t, p, moles, checkpoint);
        double slope = specific(model, t + 1, p, moles, checkpoint) - h0;
        for (int i = 0; i < TEMPERATURE_ITERATIONS && Math.abs(h - h0) > 1e-9 * Math.max(1, Math.abs(h)); i++) {
            if (!(slope > 0) || !Double.isFinite(slope)) return Double.NaN;
            double next = Math.max(t * .5, Math.min(t * 1.5, t + (h - h0) / slope));
            double hn = specific(model, next, p, moles, checkpoint);
            if (next != t) slope = (hn - h0) / (next - t);
            t = next;
            h0 = hn;
        }
        return Math.abs(h - h0) > 1e-6 * Math.max(1, Math.abs(h)) ? Double.NaN : t;
    }

    /**
     * The partial molar enthalpies of {@code moles} at {@code t}, {@code p}, J/mol per axis component: forward differences of
     * the model's enthalpy, shifted together so that they sum, weighted by {@code moles}, to its enthalpy exactly (Euler: the
     * enthalpy at fixed temperature and pressure is homogeneous of degree one in the moles). A component whose difference
     * does not flash takes the specific enthalpy.
     */
    static double[] partial(FluidThermodynamics model, double t, double p, double[] moles, Runnable checkpoint) {
        double total = Arrays.stream(moles).sum(), h = model.flashTP(t, p, moles.clone(), checkpoint).enthalpy();
        double delta = PARTIAL_STEP * total;
        double[] partial = new double[moles.length];
        double sum = 0;
        for (int i = 0; i < moles.length; i++) {
            double[] plus = moles.clone();
            plus[i] += delta;
            double hi;
            try {
                hi = (model.flashTP(t, p, plus, checkpoint).enthalpy() - h) / delta;
            } catch (IllegalArgumentException | ArithmeticException noFlash) {
                hi = Double.NaN;
            }
            partial[i] = Double.isFinite(hi) ? hi : h / total;
            sum += partial[i] * moles[i];
        }
        double shift = (h - sum) / total;
        for (int i = 0; i < partial.length; i++) partial[i] += shift;
        return partial;
    }

    public static double[][] copy(double[][] rows) {
        double[][] out = new double[rows.length][];
        for (int k = 0; k < rows.length; k++) out[k] = rows[k].clone();
        return out;
    }

    /** The model's specific enthalpy of {@code moles} at {@code t}, {@code p}, J/mol. */
    private static double specific(FluidThermodynamics model, double t, double p, double[] moles, Runnable checkpoint) {
        return model.flashTP(t, p, moles.clone(), checkpoint).enthalpy() / Arrays.stream(moles).sum();
    }

    /** The axis components' normal boiling points, K: the package's records, water at 373.15 K. */
    static double[] boilingPoints(MaterialCatalog catalog, String packageId, List<String> axis) {
        double[] tb = new double[axis.size()];
        Arrays.fill(tb, Double.NaN);
        for (var p : catalog.requirePackage(packageId).properties()) {
            int i = axis.indexOf(p.component());
            if (i >= 0) tb[i] = p.normalBoilingPoint();
        }
        int water = axis.indexOf(SplitTable.WATER);
        if (water >= 0) tb[water] = 373.15;
        for (int i = 0; i < tb.length; i++) if (Double.isNaN(tb[i])) throw new IllegalArgumentException("No boiling point for " + axis.get(i));
        return tb;
    }
}
