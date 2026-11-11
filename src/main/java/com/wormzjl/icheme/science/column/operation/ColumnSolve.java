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
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.equipment.HeatSides;
import com.wormzjl.icheme.science.fluid.network.HeatCurve;
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
 * <p>The solve runs on its feed island's compound list (batch 2026-11-04-refinery-compounds, V4): the model is that island's,
 * a view of the network package on its list ({@link FluidThermodynamics#on}), and V3 reads the same view, so the column's
 * public basis is the island's compounds, at most 64 whatever the network package holds. What the column took and what it
 * gives stay on the world list (the accounts'), mapped by compound id. A compound the list does not carry (a steam inlet's
 * hydrocarbon from another island) has no feed in the solve and takes the row of the nearest boiling solved compound, as
 * every compound without feed does ({@link SplitTable#of}), and its product's specific enthalpy.
 *
 * <p>The table's condenser duty per feed is the closure on the network's basis at the solved state, not V3's own duty:
 * the cycle closes its energy with the network's enthalpies, and V3's package differs from them by a few tenths of a
 * percent of the throughput's enthalpy (C1 review, limits), which is several percent of a small condenser. The closure
 * band then measures drift, not the basis. V3's duty is kept beside it for the status line.
 */
public final class ColumnSolve {
    /** V3's superheat rule for a steam feed, K above saturation at its stage. */
    public static final double SUPERHEAT = 5;
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
     * @param feed         mol/s on the world list (the network package's compounds, water last); the steam ports' hydrocarbons are feed (plan 11)
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

    /**
     * One solve: why, the design's calculator input, the setpoints, the state taken, the last accepted seed (null: cold), and
     * the design's condenser, which says what share of its duty the cooling water takes.
     */
    public record Job(DriftBands.Trigger trigger, V3ColumnInput design, Setpoints setpoints, Taken taken, V3NeuralSeed seed, ColumnComponents.Condenser condenser) {
        public Job {
            Objects.requireNonNull(trigger, "trigger");
            Objects.requireNonNull(design, "design");
            Objects.requireNonNull(setpoints, "setpoints");
            Objects.requireNonNull(taken, "taken");
            Objects.requireNonNull(condenser, "condenser");
        }

        /** A solve of a water-cooled condenser's column. */
        public Job(DriftBands.Trigger trigger, V3ColumnInput design, Setpoints setpoints, Taken taken, V3NeuralSeed seed) {
            this(trigger, design, setpoints, taken, seed, ColumnComponents.Condenser.EXCHANGER);
        }
    }

    /** A solve's answer. */
    public sealed interface Outcome permits Solved, Failed {
    }

    /**
     * An accepted solve.
     *
     * @param enthalpy        each product's partial molar enthalpies on the world list, J/mol, network basis, at its solved temperature and
     *                        pressure: a product of moles n holds the energy sum n_i h_i, exact at its solved composition and
     *                        first order beside it (plan 5.3: its actual composition, not the solved one's specific enthalpy)
     * @param solvedCondenser V3's own condenser duty, W (column sign)
     * @param route           V3's solve path
     * @param note            what the input left out (feed water too cold to enter as steam), or empty
     * @param advisories      V3's bounded advisory texts of the accepted solve, at most {@link #MAX_ADVISORIES} (W3, step 5)
     */
    public record Solved(SplitTable table, double[][] enthalpy, V3NeuralSeed seed, double solvedCondenser, String route, String note, com.wormzjl.icheme.science.column.v3.V3ColumnInspection inspection,
                         List<String> advisories) implements Outcome {
        /** The most advisories a solve carries to the column's conditions. */
        public static final int MAX_ADVISORIES = 4;
        public Solved(SplitTable table,double[][] enthalpy,V3NeuralSeed seed,double duty,String route,String note){this(table,enthalpy,seed,duty,route,note,null);}
        public Solved(SplitTable table,double[][] enthalpy,V3NeuralSeed seed,double duty,String route,String note,com.wormzjl.icheme.science.column.v3.V3ColumnInspection inspection){this(table,enthalpy,seed,duty,route,note,inspection,List.of());}
        public Solved {
            Objects.requireNonNull(table, "table");
            advisories = List.copyOf(advisories.subList(0, Math.min(MAX_ADVISORIES, advisories.size())));
            enthalpy = copy(enthalpy);
            if (enthalpy.length != table.products().size()) throw new IllegalArgumentException("One enthalpy row per product");
            for (double[] row : enthalpy) if (row.length != table.axis().size()) throw new IllegalArgumentException("A partial enthalpy per axis component");
        }

        @Override
        public double[][] enthalpy() {
            return copy(enthalpy);
        }
    }

    /**
     * A refused or failed solve, and why; {@code transientFailure} where the worker did not finish it (a deadline, a full
     * queue, a worker failure, a shutdown): it says nothing of the state, which is asked once more (W2).
     */
    public record Failed(String reason, boolean transientFailure) implements Outcome {
        /** The solve refused the state. */
        public Failed(String reason) {
            this(reason, false);
        }
    }

    /**
     * Solves {@code job} on {@code model}, its feed island's model (a view of the network package, or the whole package),
     * taken on the engine thread; {@code screen} is V3's liquid-supply screen ratio. V3 reads the model's own catalog. The
     * solve runs on the island list's view with water (W3b option A): the steam and the products' water are states of the
     * column whether or not the feed island holds water; the axis is the same, water last.
     */
    public static Outcome run(Job job, FluidThermodynamics model, double screen, Runnable checkpoint) {
        String packageId = model.domain().packageId();
        try {
            var wet = model.on(model.compounds(), true);
            return MaterialRuntime.with(wet.catalog(), packageId, () -> solve(job, wet, packageId, screen, checkpoint));
        } catch (IllegalArgumentException refused) {
            return new Failed(refused.getMessage());
        }
    }

    private static Outcome solve(Job job, FluidThermodynamics model, String packageId, double screen, Runnable checkpoint) {
        var design = job.design();
        // The model's list (the feed island's compounds, water last) and the world list the accounts are on.
        var axis = model.components();
        var world = model.whole().components();
        int m = axis.size(), water = axis.indexOf(SplitTable.WATER);
        int[] at = new int[m];
        for (int i = 0; i < m; i++) at[i] = world.indexOf(axis.get(i));
        var taken = job.taken();
        double[] worldFeed = taken.feed();
        if (worldFeed.length != world.size()) throw new IllegalArgumentException("The feed taken is on the world list");
        double[] feed = new double[m];
        for (int i = 0; i < m; i++) feed[i] = worldFeed[at[i]];
        double rate = taken.feedRate();
        if (!(rate > 0)) return new Failed("no feed");
        double feedPressure = design.topPressurePascal() + design.feedStageNumber() * design.stagePressureDropPascal();
        // The feed's temperature: its taken enthalpy at the feed tray.
        double h = Double.isNaN(taken.feedEnthalpy()) ? specific(model, design.feedTemperatureKelvin(), feedPressure, feed, checkpoint) : taken.feedEnthalpy();
        double t = temperature(model, feed, feedPressure, h, design.feedTemperatureKelvin(), checkpoint);
        if (Double.isNaN(t)) return new Failed("the feed's enthalpy has no temperature");
        double feedSlope = specific(model, t + .5, feedPressure, feed, checkpoint) - specific(model, t - .5, feedPressure, feed, checkpoint);
        // The V3 input: the network's package on the model's list, the feed by component id, the design's trays and ratios at the taken rate.
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
        // (C1 review, section 3): such a table solves with no seed, the fast first solve. A seed on another compound list
        // (the feed island grew since) is dropped too: it is state, and the warm route needs the same basis.
        var seed = job.seed();
        if (seed != null && !seed.input().componentBasis().equals(basis)) seed = null;
        if (seed != null) for (boolean wet : seed.wetTrays()) if (wet) {
            seed = null;
            break;
        }
        var outcome = V3ColumnCalculator.calculateWithStrategy(input, checkpoint::run, V3SolveStrategy.AGGRESSIVE, seed, screen);
        if (outcome instanceof V3ColumnOutcome.Failure failure) return new Failed(failure.code().name() + ": " + failure.summary());
        var success = (V3ColumnOutcome.Success) outcome;
        // The table on the world list, its reference the state taken.
        int worldWater = world.indexOf(SplitTable.WATER);
        double[] z = new double[world.size()];
        for (int i = 0; i < z.length; i++) z[i] = worldFeed[i] / rate;
        var reference = new SplitTable.Reference(z, h, feedSlope, steamRatios, rate / setpoints.designFeedRate(), setpoints, t, steamT);
        var solved = SplitTable.of(world, boilingPoints(model.whole().catalog(), packageId, world), input, success.result(), reference);
        // Each product's partial molar enthalpies and the closure on the network's basis.
        double[] pooled = worldFeed.clone();
        double in = h * rate;
        for (int j = 0; j < designSteam.size(); j++) {
            var s = taken.steam().get(j);
            if (!(s.rate() > 0) || water < 0) continue;
            double[] steam = new double[m];
            steam[water] = s.rate();
            pooled[worldWater] += s.rate();
            double p = design.topPressurePascal() + designSteam.get(j).stageNumber() * design.stagePressureDropPascal();
            in += Double.isNaN(s.enthalpy()) ? model.flashTP(steamT[j], p, steam, checkpoint).enthalpy() : s.enthalpy() * s.rate();
        }
        double[][] split = solved.split(pooled);
        double[][] enthalpy = new double[split.length][];
        double out = 0;
        for (int k = 0; k < split.length; k++) {
            var product = solved.products().get(k);
            double n = Arrays.stream(split[k]).sum();
            double[] moles = n > 0 ? split[k] : pooled, own = new double[m];
            for (int i = 0; i < m; i++) own[i] = moles[at[i]];
            double[] hi = partial(model, product.temperature(), product.pressure(), own, checkpoint);
            // A compound off the list takes the product's specific enthalpy, as one whose difference does not flash.
            double held = 0, energy = 0;
            for (int i = 0; i < m; i++) {
                held += own[i];
                energy += hi[i] * own[i];
            }
            enthalpy[k] = new double[world.size()];
            Arrays.fill(enthalpy[k], energy / held);
            for (int i = 0; i < m; i++) enthalpy[k][at[i]] = hi[i];
            for (int i = 0; i < world.size(); i++) out += enthalpy[k][i] * split[k][i];
        }
        double[] pa = solved.pumparoundPerFeed();
        double paTotal = 0;
        for (double q : pa) paTotal += q * rate;
        double condenser = (out - in - solved.reboilerPerFeed() * rate - paTotal) / rate;
        double cooled = switch (job.condenser()) {
            case EXCHANGER -> condenser;
            case AIR_COOLER -> 0;
            case AIR_THEN_WATER -> trim(model, success.result().inspection().orElseThrow(), basis, axis, setpoints.condenserTemperature(), condenser * rate, checkpoint) / rate;
        };
        // An outlet's booking difference is the water's below this temperature, the air's at or above it (the owner's "divide by temperature").
        double waterBelow = switch (job.condenser()) {
            case EXCHANGER -> SplitTable.WATER_ALWAYS;
            case AIR_COOLER -> SplitTable.WATER_NEVER;
            case AIR_THEN_WATER -> HeatSides.AIR_SPLIT_TEMPERATURE;
        };
        // The air's surface per feed at each fan step (U2): an air cooler's from the top to the receiver, air then water's to the split.
        double[] air = job.condenser() == ColumnComponents.Condenser.EXCHANGER ? new double[0]
                : airArea(model, success.result().inspection().orElseThrow(), basis, axis, setpoints.condenserTemperature(), job.condenser() == ColumnComponents.Condenser.AIR_THEN_WATER, rate, checkpoint);
        // The worst tray's flood per mol/s of feed and the solved drop's split (W3.1, W3.3): none at a prescribed drop.
        var hydraulics = success.result().trayHydraulics().orElse(null);
        var flood = hydraulics == null ? SplitTable.Flood.NONE : new SplitTable.Flood(hydraulics.maximumFloodFraction() / rate, hydraulics.maximumFloodTray(),
                hydraulics.vaporLimited(), hydraulics.totalDryPascal(), hydraulics.totalLiquidPascal());
        // Each pumparound's draw tray as solved (W4): its cooler's utility leaves the approach below it. Node n is tray n.
        var inspection = success.result().inspection().orElse(null);
        double[] drawTemperatures = new double[inspection == null ? 0 : input.pumparounds().size()];
        for (int j = 0; j < drawTemperatures.length; j++) drawTemperatures[j] = inspection.nodes().get(input.pumparounds().get(j).drawTray()).temperatureKelvin();
        var table = new SplitTable(solved.axis(), solved.products(), solved.reboilerPerFeed(), pa, condenser, reference, solved.held(), cooled, waterBelow, air, flood, drawTemperatures);
        return new Solved(table, enthalpy, success.result().warmSeed().orElse(null), solved.condenserPerFeed() * rate,
                success.diagnostics().solvePath(), note, success.result().inspection().orElse(null), success.result().acceptanceAudit().advisoryEvidence());
    }

    /**
     * The duty an air-then-water condenser's water trim takes, W (column sign, removed: negative), of the whole
     * {@code condenser} duty ({@code COOLER_MODEL_PLAN.md} section 6): the overhead, the top tray's vapour with its water
     * vapour, cooled at the condenser's pressure from the split ({@link HeatSides#airSplit}) to the receiver. None where the
     * split is the receiver, the whole where it is the top vapour, never beyond the whole. The air takes the rest. The simulator's
     * column block reads its condenser's water share here too ({@code ColumnUnit}), so the sheet and the world split alike.
     */
    public static double trim(FluidThermodynamics model, com.wormzjl.icheme.science.column.v3.V3ColumnInspection inspection, com.wormzjl.icheme.science.column.v3.V3ComponentBasis basis,
                       List<String> axis, double receiver, double condenser, Runnable checkpoint) {
        double[] overhead = overhead(inspection, basis, axis);
        double p = inspection.nodes().getFirst().pressurePascal();
        return trim(inspection.nodes().get(1).temperatureKelvin(), receiver, condenser, t -> model.flashTP(t, p, overhead.clone(), checkpoint).enthalpy());
    }

    /** The overhead, mol/s on the axis: the top tray's vapour (V3's inspection node 1) with its water vapour. */
    private static double[] overhead(com.wormzjl.icheme.science.column.v3.V3ColumnInspection inspection, com.wormzjl.icheme.science.column.v3.V3ComponentBasis basis, List<String> axis) {
        var top = inspection.nodes().get(1);
        int water = axis.indexOf(SplitTable.WATER);
        double[] overhead = new double[axis.size()];
        var y = top.vaporFractions();
        for (int c = 0; c < basis.componentCount(); c++) {
            int i = axis.indexOf(basis.componentId(c));
            if (i >= 0 && i != water) overhead[i] += top.vaporMolPerSecond() * y.get(c);
        }
        if (water >= 0) overhead[water] += top.waterVaporMolPerSecond();
        return overhead;
    }

    /**
     * An air condenser's bare tube per mol/s of feed, m², at each fan step of {@link SplitTable#airArea} (U2, {@code COOLER_MODEL_PLAN.md}
     * section 8): the overhead marched at the condenser's pressure from the top tray down to the air's outlet against 25 °C air, with the
     * films of the world's air coolers at that speed ({@link HeatSides#airFilms}), every tube row meeting fresh air. The outlet is the
     * receiver, or the split of an air-then-water condenser ({@code trimmed}); none where the overhead arrives there already; the most
     * a double holds where the air cannot reach it.
     */
    static double[] airArea(FluidThermodynamics model, com.wormzjl.icheme.science.column.v3.V3ColumnInspection inspection, com.wormzjl.icheme.science.column.v3.V3ComponentBasis basis,
                            List<String> axis, double receiver, boolean trimmed, double rate, Runnable checkpoint) {
        double[] overhead = overhead(inspection, basis, axis), area = new double[SplitTable.FAN_STEPS + 1];
        double top = inspection.nodes().get(1).temperatureKelvin(), outlet = trimmed ? HeatSides.airSplit(top, receiver) : receiver;
        if (outlet >= top || !(rate > 0)) return area;
        if (outlet <= HeatSides.AIR_TEMPERATURE) {
            Arrays.fill(area, Double.MAX_VALUE);
            return area;
        }
        double[] weights = model.molecularWeights();
        double mass = 0;
        for (int i = 0; i < overhead.length; i++) mass += overhead[i] * weights[i];
        var curve = HeatCurve.sample(model, overhead, inspection.nodes().getFirst().pressurePascal(), outlet, top, HeatSides.airFilms(1), checkpoint);
        double in = curve.enthalpy(curve.knots() - 1), out = curve.enthalpy(0);
        for (int k = 0; k <= SplitTable.FAN_STEPS; k++)
            area[k] = exposure(curve.withFilms(HeatSides.airFilms(k / (double) SplitTable.FAN_STEPS)), in, out) * mass / rate;
        return area;
    }

    /** The bare tube per kg/s, m² s/kg, that brings a stream on {@code curve} from {@code in} down to {@code out} (J/kg) against 25 °C air: a bisection of the march. */
    static double exposure(HeatCurve curve, double in, double out) {
        double low = 0, high = 1;
        while (curve.march(in, 1, high, HeatSides.AIR_TEMPERATURE) > out) {
            low = high;
            high *= 2;
            if (high > 1e12) return Double.MAX_VALUE;
        }
        for (int i = 0; i < 60 && high - low > 1e-9 * high; i++) {
            double middle = (low + high) / 2;
            if (curve.march(in, 1, middle, HeatSides.AIR_TEMPERATURE) > out) low = middle;
            else high = middle;
        }
        return high;
    }

    /**
     * The trim's duty, W (column sign), from the top vapour's and the receiver's temperatures, the whole {@code condenser}
     * duty and the overhead's enthalpy at a temperature, W: the overhead from the split to the receiver, bounded by the whole.
     */
    static double trim(double top, double receiver, double condenser, java.util.function.DoubleUnaryOperator enthalpy) {
        if (!(condenser < 0)) return 0;
        double split = HeatSides.airSplit(top, receiver);
        if (split <= receiver) return 0;
        if (split >= top) return condenser;
        return Math.max(condenser, Math.min(0, enthalpy.applyAsDouble(receiver) - enthalpy.applyAsDouble(split)));
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
