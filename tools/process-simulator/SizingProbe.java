import com.wormzjl.icheme.science.column.v3.V3ColumnCalculator;
import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.column.v3.V3ColumnOutcome;
import com.wormzjl.icheme.science.column.v3.V3ColumnResult;
import com.wormzjl.icheme.science.column.v3.V3SolveStrategy;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.equipment.ColumnDesign;
import com.wormzjl.icheme.science.equipment.ColumnTrays;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.HeatSides;
import com.wormzjl.icheme.science.equipment.NozzleBore;
import com.wormzjl.icheme.science.equipment.PipeSizes;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
import com.wormzjl.icheme.science.fluid.network.PipeResistance;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.transport.MixtureViscosity;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * The sizing stage on the design's sample flowsheet (batch 2026-11-02-process-simulator, {@code SIZING_PLAN.md}): runs
 * {@link SampleFlowsheetProbe}, then picks the smallest buildable size for each line and unit against the plan's criteria,
 * with the mod's own laws: {@code PipeResistance} for every drop, {@code ProcessUnit} and {@code HeatSides} for what a size
 * holds, the world's films for every area, the real column at each trial diameter. It prints what the sizing sheets show and
 * writes {@code sizing-sample.tsv}, which {@code tools/heat-gui-renders/SimulatorSizing.java} draws. Run as {@code ClientRunProbe},
 * from the worktree root. It stands in for the plan's sizing code: it is not it.
 */
public final class SizingProbe {
    /** The design flow over the steady flow (plan section 3.2). */
    static final double MARGIN = 1.10;
    /** A line's planned length with its fittings, m: only the pressure view reads it (plan section 3.2). */
    static final double LINE_LENGTH = 50;
    /** A pumparound's fall across its cooler, K (plan section 3.2): it gives the circulation the column model does not have. */
    static final double PA_FALL = 60;
    /** The bores the line list offers, m: the pipe tool takes any, these are the usual sizes, NPS 1 to 32 near enough. */
    static final double[] BORES = {.025, .05, .075, .1, .15, .2, .25, .3, .35, .4, .45, .5, .6, .7, .8};
    /** A regulating valve's least drop, Pa [HALL-V]. */
    static final double VALVE = .7e5;
    static final double PI = Math.PI, G = 9.80665;
    static final String WORLD = "World";

    /** A line's service (plan section 3.3). */
    enum Service {
        SUCTION_BOILING("Suction, boiling"), SUCTION_COLD("Suction, cold"), DRAW("Draw"), LIQUID("Liquid"), WATER("Water"), GAS("Gas"), STEAM_HOT("Steam, superheated"), TWO_PHASE("Two-phase");

        final String label;

        Service(String label) {
            this.label = label;
        }
    }

    /** The speed limit, m/s, at {@code bore} m and {@code bar} absolute. */
    static double speedLimit(Service s, double bore, double bar) {
        double gauge = bar - 1.01325;
        return switch (s) {
            case SUCTION_BOILING -> bore < .25 ? 1 : bore <= .5 ? 1.25 : 1.5; // [KARM]
            case SUCTION_COLD -> bore < .25 ? 1.5 : 1.8; // [KARM]
            case DRAW -> bore <= .05 ? .6 : .9; // [KARM]
            case LIQUID, WATER -> 3; // [KARM], [THAK]
            case GAS -> gauge <= .1 ? 60 : gauge <= 1 ? 40 : gauge <= 5 ? 30 : 20; // [KARM]
            case STEAM_HOT -> 60; // [SPX]
            case TWO_PHASE -> FluidThermodynamics.DEFAULT_MAXIMUM_VELOCITY; // Only the world's cap: rho v^2 rules it.
        };
    }

    /** The drop limit, bar per 100 m. */
    static double dropLimit(Service s) {
        return switch (s) {
            case SUCTION_BOILING, SUCTION_COLD, DRAW -> .10; // [LINEG]
            case LIQUID, WATER -> .45; // [ET]
            case GAS -> .11; // [CHER]
            case STEAM_HOT -> .2; // [SPX]
            case TWO_PHASE -> Double.POSITIVE_INFINITY;
        };
    }

    /** The rho v^2 limit, kg/m/s^2. */
    static double momentumLimit(Service s, double bar) {
        double gauge = bar - 1.01325;
        return switch (s) {
            case GAS -> gauge < 20 ? 6000 : gauge < 50 ? 7500 : gauge < 80 ? 10000 : 15000; // [KARM]
            case STEAM_HOT -> 15000; // [KARM]
            case TWO_PHASE -> 10000; // [KARM]
            default -> Double.POSITIVE_INFINITY;
        };
    }

    record Props(double mass, double volume, double density, double viscosity, double vapour) {}

    static final MixtureViscosity.Workspace SCRATCH = new MixtureViscosity.Workspace();

    static Props props(FluidThermodynamics.State s) {
        var t = SampleFlowsheetProbe.thermo;
        double oil = Arrays.stream(s.liquidView()).sum(), gas = Arrays.stream(s.vaporView()).sum() + s.waterVapor();
        double liquidMu = oil > 0 ? t.viscosity.liquid(s.temperature(), s.liquidView()).pascalSeconds() : s.waterLiquid() > 0 ? t.viscosity.waterLiquid(s.temperature()) : 0;
        double gasMu = gas > 0 ? t.vaporViscosity(s, SCRATCH, null) : 0;
        double gasMass = gas > 0 ? t.vaporMass(s) : 0, x = gasMass / s.mass();
        // McAdams: the homogeneous mixture's viscosity from the phases' by mass.
        double mu = x <= 0 ? liquidMu : x >= 1 ? gasMu : 1 / (x / gasMu + (1 - x) / liquidMu);
        return new Props(s.mass(), s.volume(), s.mass() / s.volume(), mu, x);
    }

    /** The pressure at which {@code n} at {@code t} starts to boil, Pa: a bisection on the world's flash. */
    static double bubble(double t, double[] n) {
        double low = 1e3, high = 5e6;
        for (int i = 0; i < 40 && high / low > 1.0005; i++) {
            double middle = Math.sqrt(low * high);
            if (props(SampleFlowsheetProbe.tp(t, middle, n)).vapour() > 1e-7) low = middle;
            else high = middle;
        }
        return high;
    }

    static double drop100(double bore, double mass, double density, double viscosity) {
        return PipeResistance.pressureDrop(new PipeResistance.Geometry(100, bore, PipeResistance.DEFAULT_ROUGHNESS_METRES, 0), mass, density, viscosity) / 1e5;
    }

    // ---- What the sheets read ----

    /** One limit on one item: a ceiling if {@code max}, otherwise a floor; a ceiling with a {@code floor} is a band. */
    record Crit(String tag, String name, boolean must, boolean max, double value, double limit, double floor, String form, String unit, String source, boolean ranks) {
        boolean ok() {
            return (max ? value <= limit * (1 + 1e-9) : value >= limit * (1 - 1e-9)) && !(value < floor * (1 - 1e-9));
        }

        /** How near the limit: 1 is on it. */
        double load() {
            double ceiling = max ? value / limit : value <= 0 ? Double.POSITIVE_INFINITY : limit / value;
            return floor > 0 ? Math.max(ceiling, value <= 0 ? Double.POSITIVE_INFINITY : floor / value) : ceiling;
        }

        int lamp() {
            return ok() ? 0 : must ? 2 : 1;
        }

        String valueText() {
            return String.format(Locale.ROOT, form, value) + (unit.isEmpty() ? "" : " " + unit);
        }

        String limitText() {
            String top = String.format(Locale.ROOT, form, limit) + (unit.isEmpty() ? "" : " " + unit);
            return floor > 0 ? String.format(Locale.ROOT, form, floor) + " to " + top : (max ? "max " : "min ") + top;
        }

        Crit quiet() {
            return new Crit(tag, name, must, max, value, limit, floor, form, unit, source, false);
        }
    }

    static final List<Crit> CRITS = new ArrayList<>();

    static void most(String tag, String name, boolean must, double value, double limit, String form, String unit, String source) {
        CRITS.add(new Crit(tag, name, must, true, value, limit, 0, form, unit, source, true));
    }

    static void within(String tag, String name, boolean must, double value, double low, double high, String form, String unit, String source) {
        CRITS.add(new Crit(tag, name, must, true, value, high, low, form, unit, source, true));
    }

    static void least(String tag, String name, boolean must, double value, double limit, String form, String unit, String source) {
        CRITS.add(new Crit(tag, name, must, false, value, limit, 0, form, unit, source, true));
    }

    /** The last limit stays in the list and does not count as what is closest for its unit: no size of this unit moves it. */
    static void quiet() {
        CRITS.add(CRITS.removeLast().quiet());
    }

    /** A unit in the table: {@code depth} 1 is a member of the block above it; {@code item} is the constructor's name for it. */
    record Unit(int depth, String tag, String kind, String size, int count, String item) {}

    static final List<Unit> UNITS = new ArrayList<>();

    record Sized(String name, Service service, double tonnes, double cubic, double density, double bore, double speed, double drop, double momentum, int lines, String decides, int lamp) {}

    static final LinkedHashMap<String, Sized> LINES = new LinkedHashMap<>();

    static Sized line(String name, FluidThermodynamics.State s, Service service) {
        var p = props(s);
        double mass = p.mass() * MARGIN, q = p.volume() * MARGIN, bar = s.pressure() / 1e5;
        for (int lines = 1; lines <= 4; lines++)
            for (double bore : BORES) {
                double area = lines * PI * bore * bore / 4, v = q / area, drop = drop100(bore, mass / lines, p.density(), p.viscosity()), momentum = p.density() * v * v;
                double a = v / speedLimit(service, bore, bar), b = drop / dropLimit(service), c = momentum / momentumLimit(service, bar);
                if (Math.max(a, Math.max(b, c)) > 1) continue;
                var sized = new Sized(name, service, mass * 3.6, q * 3600, p.density(), bore, v, drop, momentum, lines, a >= b && a >= c ? "Speed" : b >= c ? "Drop" : "ρv²", lines > 1 ? 1 : 0);
                LINES.put(name, sized);
                return sized;
            }
        throw new IllegalStateException(name);
    }

    /** A line's drop over its planned length, bar. */
    static double run(String name) {
        return LINES.get(name).drop() * LINE_LENGTH / 100;
    }

    /** A stream of the column on the network axis: {@code fractions} on the column's basis, {@code flow} mol/s, plus {@code water}. */
    static FluidThermodynamics.State node(List<Double> fractions, double flow, double water, double t, double p) {
        var ids = SampleFlowsheetProbe.base.componentBasis().componentIds();
        double[] n = new double[SampleFlowsheetProbe.axis.size()];
        for (int i = 0; i < ids.size(); i++) n[SampleFlowsheetProbe.axis.indexOf(ids.get(i))] += fractions.get(i) * flow;
        n[SampleFlowsheetProbe.axis.indexOf("Water")] += water;
        return SampleFlowsheetProbe.tp(t, p, n);
    }

    static double lmtd(double hotIn, double hotOut, double coldIn, double coldOut) {
        double a = hotIn - coldOut, b = hotOut - coldIn;
        return Math.abs(a - b) < 1e-6 ? a : (a - b) / Math.log(a / b);
    }

    /** An exchanger's choice: shells in parallel, shell, tubes, surface, the sides' speeds and drops. */
    record Bundle(int shells, double shell, int tubes, double area, double tubeSpeed, double tubeDrop, double shellSpeed, double shellDrop) {
        String size() {
            return (shells > 1 ? shells + " × shell " : "Shell ") + String.format(Locale.ROOT, "%.2f m, tubes %d m", shell, tubes);
        }
    }

    /** A side's limits: least and most speed, m/s, most drop, bar. */
    record Side(double low, double high, double drop) {}

    static final Side FREE = new Side(0, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);

    /** What {@code n} shells of {@code d} by {@code l} m do at design flow. */
    static Bundle bundle(int n, double d, int l, Props tube, Props shell) {
        var tg = HeatSides.tubePassage(d, l);
        var sg = HeatSides.shellPassage(d, l);
        double tv = tube.volume() * MARGIN / n / tg.area(), sv = shell.volume() * MARGIN / n / sg.area();
        double td = PipeResistance.pressureDrop(tg, tube.mass() * MARGIN / n, tube.density(), tube.viscosity()) / 1e5;
        double sd = PipeResistance.pressureDrop(sg, shell.mass() * MARGIN / n, shell.density(), shell.viscosity()) / 1e5;
        return new Bundle(n, d, l, n * ProcessUnit.tubeSurface(d, l), tv, td, sv, sd);
    }

    /** The fewest shells, then the least surface, that hold {@code needed} m^2 under twice the need and within the sides' limits. */
    static Bundle exchanger(double needed, Props tube, Props shell, Side tubeSide, Side shellSide, double... caps) {
        for (double most : caps)
            for (int n = 1; n <= 6; n++) {
                Bundle best = null;
                for (double d = .5; d <= 2.5 + 1e-9; d += ProcessUnit.SHELL_STEP)
                    for (int l = Math.max(2, (int) Math.ceil(3 * d - 1e-9)); l <= 12; l++) {
                        var b = bundle(n, d, l, tube, shell);
                        if (b.area() < needed || b.area() > most * needed) continue;
                        if (b.tubeSpeed() < tubeSide.low() || b.tubeSpeed() > tubeSide.high() || b.tubeDrop() > tubeSide.drop() || b.shellSpeed() < shellSide.low() || b.shellSpeed() > shellSide.high() || b.shellDrop() > shellSide.drop()) continue;
                        if (best == null || b.area() < best.area()) best = b;
                    }
                if (best != null) return best;
            }
        return null;
    }

    /** Sizes an exchanger and states its limits. {@code hot} and {@code cold} are the end temperatures, K; the tube side takes {@code tube}. */
    static Bundle exchanger(String tag, int depth, double duty, double u, double[] hot, double[] cold, Props tube, Props shell, boolean condenser) {
        double dt = lmtd(hot[0], hot[1], cold[0], cold[1]), needed = duty * MARGIN / (u * dt);
        var tubeSide = new Side(1, 3, .7);
        var shellSide = condenser ? new Side(0, 30, .2) : new Side(.3, 2, .7);
        var b = exchanger(needed, tube, shell, tubeSide, shellSide, 2, Double.POSITIVE_INFINITY);
        if (b == null) b = exchanger(needed, tube, shell, FREE, FREE, 2, Double.POSITIVE_INFINITY);
        // What the plan's D14 and D15 rest on: a liquid shell held to the source's 1 m/s, with the tube floor and without it; no cap on the surface.
        for (var other : new Bundle[]{condenser ? null : exchanger(needed, tube, shell, tubeSide, new Side(.3, 1, .7), Double.POSITIVE_INFINITY), condenser ? null : exchanger(needed, tube, shell, new Side(0, 3, .7), new Side(.3, 1, .7), Double.POSITIVE_INFINITY), exchanger(needed, tube, shell, tubeSide, shellSide, Double.POSITIVE_INFINITY)})
            if (other != null) System.out.printf(Locale.ROOT, "   other rule: %s = %.0f m2 (+%.0f %%), tube %.2f m/s, shell %.2f m/s%n", other.size(), other.area(), 100 * (other.area() / needed - 1), other.tubeSpeed(), other.shellSpeed());
        System.out.printf(Locale.ROOT, "%-12s %6.2f MW, LMTD %6.1f K, U %5.0f, needs %5.0f m2: %s = %5.0f m2, tube %.2f m/s %.2f bar, shell %.2f m/s %.2f bar%n", tag, duty / 1e6, dt, u, needed, b.size(), b.area(), b.tubeSpeed(), b.tubeDrop(), b.shellSpeed(), b.shellDrop());
        UNITS.add(new Unit(depth, tag, "Exchanger", b.size(), b.shells(), "Exchanger"));
        limits(tag, needed, b, hot, cold, tubeSide, shellSide, condenser);
        return b;
    }

    /** Every limit on an exchanger of {@code b}. */
    static void limits(String tag, double needed, Bundle b, double[] hot, double[] cold, Side tubeSide, Side shellSide, boolean condenser) {
        least(tag, "Surface", true, b.area(), needed, "%.0f", "m²", WORLD);
        double hotEnd = hot[0] - cold[1], coldEnd = hot[1] - cold[0];
        least(tag, "Temperature, hot end", true, hotEnd, 0, "%.1f", "K", WORLD);
        quiet();
        least(tag, "Temperature, cold end", true, coldEnd, 0, "%.1f", "K", WORLD);
        quiet();
        least(tag, "Least approach", false, Math.min(hotEnd, coldEnd), 10, "%.1f", "K", "[BLOG]");
        most(tag, "Surface over the need", false, 100 * (b.area() / needed - 1), 100, "%.0f", "%", "This plan");
        quiet();
        within(tag, "Tube speed", false, b.tubeSpeed(), tubeSide.low(), tubeSide.high(), "%.2f", "m/s", "[BLOG]");
        most(tag, "Tube drop", false, b.tubeDrop(), tubeSide.drop(), "%.2f", "bar", "[BLOG]");
        if (condenser) most(tag, "Shell speed", false, b.shellSpeed(), shellSide.high(), "%.2f", "m/s", "[SCR-HX]");
        else within(tag, "Shell speed", false, b.shellSpeed(), shellSide.low(), shellSide.high(), "%.2f", "m/s", "[SCR-HX] × 2");
        most(tag, "Shell drop", false, b.shellDrop(), shellSide.drop(), "%.2f", "bar", condenser ? "[SCR-HX]" : "[BLOG]");
    }

    /** A coil's drop from {@code in} to {@code out}: the homogeneous law along the enthalpy, in {@code slices}. */
    static double coil(FluidThermodynamics.State in, FluidThermodynamics.State out, PipeResistance.Geometry g, double flowFactor, int slices) {
        double[] n = SampleFlowsheetProbe.amounts(in);
        double p = in.pressure(), total = 0, t = in.temperature();
        var slice = new PipeResistance.Geometry(g.length() / slices, g.diameter(), g.roughness(), g.minorLoss() / slices, g.channels());
        for (int i = 0; i < slices; i++) {
            double h = in.enthalpy() + (i + .5) / slices * (out.enthalpy() - in.enthalpy());
            var s = SampleFlowsheetProbe.ph(t, p, h, n);
            t = s.temperature();
            var q = props(s);
            double drop = PipeResistance.pressureDrop(slice, q.mass() * flowFactor, q.density(), q.viscosity());
            total += drop;
            p = Math.max(1e4, p - drop);
        }
        return total;
    }

    static V3ColumnResult solved(V3ColumnInput in, com.wormzjl.icheme.science.column.v3.V3NeuralSeed seed) {
        V3ColumnOutcome outcome = MaterialRuntime.with(SampleFlowsheetProbe.catalog, in.packageId(), () -> V3ColumnCalculator.calculateWithStrategy(in, () -> {}, V3SolveStrategy.AGGRESSIVE, seed, V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO));
        return outcome instanceof V3ColumnOutcome.Success s ? s.result() : null;
    }

    /** A pump: where it draws from and what it must add. {@code head} m of the pumped liquid over its source, for NPSH. */
    record Mover(String tag, int depth, FluidThermodynamics.State liquid, String suctionLine, double source, double head, double rise) {}

    /** One step of a pressure path: its name, what it is, bar lost, the sheet's own drop for it or "". */
    record Step(String name, String what, double lost, String sheet) {}

    /** A path from a pressure to the next one the sheet sets. */
    record PressurePath(String name, double start, double end, double head, List<Step> steps) {
        double lost() {
            return steps.stream().mapToDouble(Step::lost).sum();
        }

        double available() {
            return start + head - end;
        }
    }

    static final List<PressurePath> PATHS = new ArrayList<>();

    public static void main(String[] args) throws IOException {
        SampleFlowsheetProbe.main(args);
        var S = SampleFlowsheetProbe.STREAMS;
        var r = SampleFlowsheetProbe.solved;
        var base = SampleFlowsheetProbe.base;
        var nodes = r.inspection().orElseThrow().nodes();
        int trays = base.stageCount();
        System.out.println("=== SIZING, design flow " + MARGIN + " x steady ===");

        // ---- Lines on the sheet ----
        line("CRUDE", S.get("CRUDE"), Service.SUCTION_COLD);
        for (var name : List.of("S-1", "S-2", "S-3", "S-4", "S-5")) line(name, S.get(name), Service.LIQUID);
        for (var name : List.of("S-6", "S-7")) line(name, S.get(name), Service.TWO_PHASE);
        line("STEAM", S.get("STEAM"), Service.STEAM_HOT);
        for (var name : List.of("CWS", "CWR")) line(name, S.get(name), Service.WATER);
        line("OFFGAS", S.get("OFFGAS"), Service.GAS);
        for (var name : List.of("NAPHTHA", "WATER", "DIESEL", "S-10", "RESIDUE")) line(name, S.get(name), Service.LIQUID);
        for (var name : List.of("KERO", "AGO", "S-8", "S-9")) line(name, S.get(name), Service.DRAW);
        // Lines inside T-101's block: the overhead, the reflux, each pumparound.
        var top = nodes.get(1);
        var overhead = node(top.vaporFractions(), top.vaporMolPerSecond(), top.waterVaporMolPerSecond(), top.temperatureKelvin(), top.pressurePascal());
        line("Overhead", overhead, Service.GAS);
        var condenserNode = nodes.get(0);
        var condensate = node(condenserNode.liquidFractions(), condenserNode.liquidMolPerSecond(), 0, condenserNode.temperatureKelvin(), condenserNode.pressurePascal() + 3e5);
        double distillate = Arrays.stream(SampleFlowsheetProbe.amounts(S.get("NAPHTHA"))).sum();
        var refluxLiquid = node(condenserNode.liquidFractions(), condenserNode.liquidMolPerSecond() - distillate, 0, condenserNode.temperatureKelvin(), condenserNode.pressurePascal() + 3e5);
        line("Receiver out", condensate, Service.SUCTION_BOILING);
        line("Pump out", condensate, Service.LIQUID);
        line("Reflux", refluxLiquid, Service.LIQUID);

        // ---- T-101: the real column at each trial diameter, from the largest down ----
        System.out.println("--- column ---");
        double worstLiquid = 0;
        int worstLiquidTray = 0;
        for (int k = 1; k <= trays; k++) {
            var n = nodes.get(k);
            if (n.liquidMolPerSecond() <= 0) continue;
            double q = props(node(n.liquidFractions(), n.liquidMolPerSecond(), 0, n.temperatureKelvin(), n.pressurePascal() + 3e5)).volume() * MARGIN;
            if (q > worstLiquid) {
                worstLiquid = q;
                worstLiquidTray = k;
            }
        }
        var bottom = props(S.get("S-9"));
        double chosen = 0, chosenFlood = 0, chosenTray = 0;
        V3ColumnResult result = null;
        for (double d = 6.5; d <= 9 + 1e-9; d += .5) {
            var in = new V3ColumnInput(base.schemaVersion(), base.packageId(), base.assayId(), base.componentBasis(), base.feedComponentMolarFlowsMolPerSecond(), SampleFlowsheetProbe.feedKelvin, trays,
                    base.feedStageNumber(), base.topPressurePascal(), base.stagePressureDropPascal(), base.specifications(), base.sideDraws(), base.steamFeeds(), base.pumparounds(), d);
            var out = solved(in, SampleFlowsheetProbe.warm);
            if (out == null) {
                System.out.printf(Locale.ROOT, "D %.1f m: no solve%n", d);
                continue;
            }
            var h = out.trayHydraulics().orElseThrow();
            double area = PI * d * d / 4, flood = h.maximumFloodFraction() * MARGIN, downcomer = worstLiquid / (.1 * area), weir = worstLiquid * 3600 / (.726 * d);
            System.out.printf(Locale.ROOT, "D %.1f m: flood %.1f %% at tray %d, design %.1f %%, column drop %.1f kPa, downcomer %.3f m/s, weir %.1f m3/h/m%n", d, 100 * h.maximumFloodFraction(), h.maximumFloodTray() + 1, 100 * flood, h.totalPressureDropPascal() / 1e3, downcomer, weir);
            if (chosen == 0 && flood <= .80 && downcomer <= .18) {
                chosen = d;
                chosenFlood = flood;
                chosenTray = h.maximumFloodTray() + 1;
                result = out;
            }
        }
        var hydraulics = result.trayHydraulics().orElseThrow();
        double diameter = chosen, area = PI * diameter * diameter / 4, skirt = EquipmentShape.minimumSkirt(diameter), bottomSpace = ColumnTrays.bottomSpace(diameter);
        double trayRun = ColumnTrays.SPACING * (trays + 1), height = trayRun + ColumnTrays.TOP_SPACE + bottomSpace;
        System.out.printf(Locale.ROOT, "column: D %.1f m, height %.1f m on %.1f m of trays, bottom space %.1f m, skirt %.1f m, worst liquid %.1f m3/h at tray %d%n", diameter, height, trayRun, bottomSpace, skirt, worstLiquid * 3600, worstLiquidTray);
        UNITS.add(new Unit(0, "T-101", "Column", String.format(Locale.ROOT, "D %.1f m, %d trays, %.1f m tall", diameter, trays, height), 1, "Column"));
        most("T-101", "Flood", true, 100 * chosenFlood, 100, "%.1f", "%", WORLD);
        quiet();
        most("T-101", "Flood", false, 100 * chosenFlood, 80, "%.1f", "%", "[NPTEL], [KIST]");
        most("T-101", "Downcomer speed", false, worstLiquid / (.1 * area), .18, "%.3f", "m/s", "[KIST]");
        most("T-101", "Tray drop", false, (hydraulics.worstTrayDryPascal() * MARGIN * MARGIN + hydraulics.worstTrayLiquidPascal()) / 1e3, 1, "%.2f", "kPa", "[TRAY-X]");
        least("T-101", "Sump hold to half", false, area * bottomSpace / 2 / (bottom.volume() * MARGIN) / 60, 5, "%.1f", "min", "[ET]");
        most("T-101", "Height to diameter", false, height / diameter, 30, "%.1f", "", "[ROT]");
        quiet();
        most("T-101", "Diameter", true, diameter, 15, "%.1f", "m", WORLD);
        quiet();
        most("T-101", "Trays", true, trays, 64, "%.0f", "", WORLD);
        quiet();
        var design = ColumnDesign.of(base, new ColumnComponents.Choices(ColumnComponents.Condenser.EXCHANGER, ColumnComponents.Pump.STANDARD, List.of()).fit(base.pumparounds().size()));
        var shape = EquipmentShape.column(diameter, design.trays(), EquipmentShape.Heading.NORTH, EquipmentShape.minimumSkirt(diameter));
        double nozzle = Double.POSITIVE_INFINITY, widest = 0;
        for (int k = 0; k < shape.nozzles().size(); k++) nozzle = Math.min(nozzle, NozzleBore.largest(shape, k));
        for (var name : List.of("S-7", "Overhead", "S-9", "STEAM")) widest = Math.max(widest, LINES.get(name).bore());
        most("T-101", "Largest line on a nozzle", true, widest, nozzle, "%.2f", "m", WORLD);
        quiet();
        System.out.printf(Locale.ROOT, "nozzles: narrowest limit %.2f m, widest line %.2f m%n", nozzle, widest);

        // ---- The block's coolers and their pumparounds ----
        var cold = List.of(new FluidThermodynamics.State[]{S.get("S-1"), S.get("S-2")}, new FluidThermodynamics.State[]{S.get("S-2"), S.get("S-3")}, new FluidThermodynamics.State[]{S.get("S-3"), S.get("S-4")});
        var pumparounds = base.pumparounds();
        System.out.println("--- exchangers ---");
        double[] tubeDrops = new double[pumparounds.size()];
        var movers = new ArrayList<Mover>();
        for (int i = 0; i < pumparounds.size(); i++) {
            var pa = pumparounds.get(i);
            var draw = nodes.get(pa.drawTray());
            double q = -pa.dutyWatts();
            var unit = node(draw.liquidFractions(), 1000, 0, draw.temperatureKelvin(), draw.pressurePascal() + 3e5);
            var cooled = SampleFlowsheetProbe.tp(draw.temperatureKelvin() - PA_FALL, draw.pressurePascal() + 3e5, SampleFlowsheetProbe.amounts(unit));
            double mol = 1000 * q / (unit.enthalpy() - cooled.enthalpy());
            var hot = node(draw.liquidFractions(), mol, 0, draw.temperatureKelvin(), draw.pressurePascal() + 3e5);
            System.out.printf(Locale.ROOT, "PA %d: draw tray %d at %.1f C, fall %.0f K, circulation %.0f kmol/h, %.1f t/h, %.1f m3/h%n", i + 1, pa.drawTray() + 1, draw.temperatureKelvin() - 273.15, PA_FALL, mol * 3.6, hot.mass() * 3.6, hot.volume() * 3600);
            line("PA " + (i + 1) + " draw", hot, Service.DRAW);
            line("PA " + (i + 1) + " return", hot, Service.LIQUID);
            var b = exchanger("PA " + (i + 1) + " cooler", 1, q, 1000, new double[]{draw.temperatureKelvin(), draw.temperatureKelvin() - PA_FALL}, new double[]{cold.get(i)[0].temperature(), cold.get(i)[1].temperature()}, props(cold.get(i)[0]), props(hot), false);
            tubeDrops[i] = b.tubeDrop();
            // The pump at grade: the lift from the draw tray to the return tray, the loop's friction, a regulating valve.
            double lift = (pa.drawTray() - pa.returnTray()) * ColumnTrays.SPACING, density = props(hot).density();
            double rise = (run("PA " + (i + 1) + " draw") + run("PA " + (i + 1) + " return") + b.shellDrop()) * 1e5 + VALVE + density * G * lift;
            double level = skirt + bottomSpace + (trays - pa.drawTray()) * ColumnTrays.SPACING;
            movers.add(new Mover("PA " + (i + 1) + " pump", 1, hot, "PA " + (i + 1) + " draw", draw.pressurePascal(), level, rise));
        }
        // E-101: both sides liquid; the crude in the tubes, the bottoms on the shell.
        double q101 = S.get("S-9").enthalpy() - S.get("S-10").enthalpy();
        var e101 = exchanger("E-101", 0, q101, 1000, new double[]{S.get("S-9").temperature(), S.get("S-10").temperature()}, new double[]{S.get("S-4").temperature(), S.get("S-5").temperature()}, props(S.get("S-4")), props(S.get("S-9")), false);
        // The same unit with a size the player keeps: one step down, as a Fixed size that fails.
        double[] e101Hot = {S.get("S-9").temperature(), S.get("S-10").temperature()}, e101Cold = {S.get("S-4").temperature(), S.get("S-5").temperature()};
        var kept = bundle(1, .75, 6, props(S.get("S-4")), props(S.get("S-9")));
        limits("E-101 kept", q101 * MARGIN / (1000 * lmtd(e101Hot[0], e101Hot[1], e101Cold[0], e101Cold[1])), kept, e101Hot, e101Cold, new Side(1, 3, .7), new Side(.3, 2, .7), false);
        // The condenser: vapour on the shell side, water in the tubes; film 5000 against 2000.
        double condenser = Math.abs(r.dutyLedger().orElseThrow().condenserWatts()), cu = 1 / (1 / 5000.0 + 1 / 2000.0);
        var e100 = exchanger("Condenser", 1, condenser, cu, new double[]{top.temperatureKelvin(), condenserNode.temperatureKelvin()}, new double[]{S.get("CWS").temperature(), S.get("CWR").temperature()}, props(S.get("CWS")), props(overhead), true);
        most("Condenser", "Water out", false, S.get("CWR").temperature() - 273.15, 45, "%.1f", "°C", "[BLOG]");
        quiet();

        // ---- The receiver: the least drum, half full for 5 min, with the water in its boot for 5 min ----
        System.out.println("--- receiver ---");
        var liquid = props(condensate);
        var off = props(S.get("OFFGAS"));
        double oil = liquid.volume() * MARGIN, gas = off.volume() * MARGIN, water = S.get("WATER").volume() * MARGIN;
        double[] drum = null;
        for (double d = 1; d <= 10; d += .5)
            for (int tt = (int) Math.ceil(2.5 * d); tt <= 5 * d; tt++) {
                double volume = PI * d * d / 4 * tt;
                var boot = EquipmentShape.bootFor(d, tt);
                if (boot == null || volume / 2 / oil < 300 || PI * boot.diameter() * boot.diameter() / 4 * boot.length() / water < 300) continue;
                if (drum == null || volume < drum[2]) drum = new double[]{d, tt, volume};
            }
        double dd = drum[0];
        int tt = (int) drum[1];
        var boot = EquipmentShape.bootFor(dd, tt);
        double saddles = EquipmentShape.minimumBootSupport(dd, tt);
        // No pad in the world's drum: half of the table's K, by 1.25 for a lying drum.
        double load = gas / (PI * dd * dd / 8), allowed = 1.25 * .107 / 2 * Math.sqrt((liquid.density() - off.density()) / off.density());
        System.out.printf(Locale.ROOT, "liquid %.1f m3/h, gas %.0f m3/h at %.1f kg/m3, water %.1f m3/h; drum D %.1f x T/T %d m, boot D %.2f x %.2f m, saddles %.0f m%n", oil * 3600, gas * 3600, off.density(), water * 3600, dd, tt, boot.diameter(), boot.length(), saddles);
        UNITS.add(new Unit(1, "Receiver", "Lying drum", String.format(Locale.ROOT, "D %.1f × T/T %d m, boot", dd, tt), 1, "Drum with boot"));
        least("Receiver", "Hold to half", false, drum[2] / 2 / oil / 60, 5, "%.1f", "min", "[BLOG], [COST]");
        within("Receiver", "Length to diameter", false, tt / dd, 2.5, 5, "%.1f", "", "[BLOG]");
        quiet();
        most("Receiver", "Gas speed over the liquid", false, load, allowed, "%.2f", "m/s", "[GPSA], [FLU]");
        least("Receiver", "Boot hold", false, PI * boot.diameter() * boot.diameter() / 4 * boot.length() / water / 60, 5, "%.1f", "min", "[REDBAG]");
        most("Receiver", "Diameter", true, dd, 10, "%.1f", "m", WORLD);
        quiet();

        // ---- E-102: the residue from 200 to 70 C ----
        System.out.println("--- air cooler ---");
        var s10 = S.get("S-10");
        var residue = S.get("RESIDUE");
        double qAir = (s10.enthalpy() - residue.enthalpy()) * MARGIN, capacity = qAir / (s10.temperature() - residue.temperature());
        double uaNeeded = capacity * Math.log((s10.temperature() - HeatSides.AIR_TEMPERATURE) / (residue.temperature() - HeatSides.AIR_TEMPERATURE));
        double film = HeatSides.airFilms(1).liquid();
        var mean = props(SampleFlowsheetProbe.tp(.5 * (s10.temperature() + residue.temperature()), s10.pressure(), SampleFlowsheetProbe.amounts(s10)));
        double bestFace = 1e9, bestSpeed = 0, bestDrop = 0, bestBare = 0;
        String bestBank = null;
        int bestBays = 0;
        for (double floor : new double[]{.6, 0}) {
            if (bestBank != null) break;
            for (var bay : ProcessUnit.bays())
            for (int parallel = 1; parallel <= ProcessUnit.Bank.MAX_PARALLEL; parallel++)
                for (int series = 1; series <= ProcessUnit.Bank.MAX_SERIES; series++) {
                    double w = bay[0], l = bay[1];
                    var bank = new ProcessUnit.Bank(parallel, series);
                    double face = parallel * series * ProcessUnit.bundleFace(w, l), bare = 5 * face;
                    if (bare * film < uaNeeded) continue;
                    var g = HeatSides.airCoolerPassage(w, l, bank);
                    double speed = mean.volume() * MARGIN / g.area(), drop = PipeResistance.pressureDrop(g, mean.mass() * MARGIN, mean.density(), mean.viscosity()) / 1e5;
                    if (speed < floor || speed > 2.4 || drop > .7) continue;
                    if (floor > 0 ? face < bestFace : speed > bestSpeed) {
                        bestFace = face;
                        bestBare = bare;
                        bestSpeed = speed;
                        bestDrop = drop;
                        bestBays = parallel * series;
                        bestBank = String.format(Locale.ROOT, "%d × %d bays of %.1f × %.0f m", parallel, series, w, l);
                    }
                }
        }
        System.out.printf(Locale.ROOT, "E-102: %.2f MW design, C %.1f kW/K, UA needed %.1f kW/K, bare needed %.0f m2: %s, face %.0f m2, bare %.0f m2, tube %.2f m/s, %.2f bar, fans %.0f kW%n", qAir / 1e6, capacity / 1e3, uaNeeded / 1e3, uaNeeded / film, bestBank, bestFace, bestBare, bestSpeed, bestDrop, HeatSides.fanPower(bestBare, 1) / 1e3);
        UNITS.add(new Unit(0, "E-102", "Air cooler", bestBank, 1, "Air cooler"));
        least("E-102", "Bare surface", true, bestBare, uaNeeded / film, "%.0f", "m²", WORLD);
        least("E-102", "Outlet above air", false, residue.temperature() - HeatSides.AIR_TEMPERATURE, 15, "%.1f", "K", "[HALL-R]");
        within("E-102", "Tube speed", false, bestSpeed, .6, 2.4, "%.2f", "m/s", "[CHECALC]");
        most("E-102", "Tube drop", false, bestDrop, .7, "%.2f", "bar", "[WEAK]");

        // ---- H-101: the smallest heater that holds the duty ----
        System.out.println("--- fired heater ---");
        double heater = (S.get("S-6").enthalpy() - S.get("S-5").enthalpy()) * MARGIN, picked = 1e9;
        String pick = null, item = null;
        for (var kind : List.of(EquipmentShape.Kind.VERTICAL_HEATER, EquipmentShape.Kind.CABIN_HEATER)) {
            double[] sizes = kind == EquipmentShape.Kind.VERTICAL_HEATER ? new double[]{2.5, 3, 3.5, 4, 4.5, 5, 5.5, 6, 6.5, 7, 7.5, 8, 8.5, 9, 9.5, 10, 10.5, 11, 11.5, 12} : new double[]{4, 5, 6, 7, 8, 9, 10, 11, 12};
            for (double d : sizes) {
                int low = kind == EquipmentShape.Kind.VERTICAL_HEATER ? Math.max(4, (int) Math.ceil(1.5 * d)) : Math.max(6, (int) Math.ceil(1.2 * d));
                int high = kind == EquipmentShape.Kind.VERTICAL_HEATER ? Math.min(20, (int) Math.floor(2.75 * d)) : 48;
                for (int l = low; l <= high; l++) {
                    double duty = ProcessUnit.heaterDuty(kind, d, l);
                    if (duty * 1e6 < heater || duty >= picked) continue;
                    picked = duty;
                    pick = String.format(Locale.ROOT, "%s %.1f × %d m", kind == EquipmentShape.Kind.VERTICAL_HEATER ? "Vertical" : "Cabin", d, l);
                    item = kind == EquipmentShape.Kind.VERTICAL_HEATER ? "Vertical heater" : "Cabin heater";
                }
            }
        }
        double duty = heater / 1e6, radiant = .7 * duty * 1000 / 35, convection = .3 * duty * 1000 / 15;
        var rg = HeatSides.heaterPassage(radiant, duty);
        var cg = HeatSides.heaterPassage(convection, duty);
        var coolIn = S.get("S-5");
        double split = coolIn.enthalpy() + .3 * (S.get("S-6").enthalpy() - coolIn.enthalpy());
        var middle = SampleFlowsheetProbe.ph(coolIn.temperature() + 50, coolIn.pressure(), split, SampleFlowsheetProbe.amounts(coolIn));
        double dc = coil(coolIn, middle, cg, MARGIN, 12), dr = coil(middle, S.get("S-6"), rg, MARGIN, 24);
        double massFlux = coolIn.mass() * MARGIN / cg.area();
        System.out.printf(Locale.ROOT, "H-101: design %.2f MW, largest vertical %.1f, largest cabin %.1f; %s holds %.1f MW; coils %d passes, %.2f + %.2f = %.2f bar; cold oil %.2f m/s, %.0f kg/s/m2%n", duty, ProcessUnit.heaterDuty(EquipmentShape.Kind.VERTICAL_HEATER, 12, 20), ProcessUnit.heaterDuty(EquipmentShape.Kind.CABIN_HEATER, 12, 48), pick, picked, cg.channels(), dc / 1e5, dr / 1e5, (dc + dr) / 1e5, props(coolIn).volume() * MARGIN / cg.area(), massFlux);
        UNITS.add(new Unit(0, "H-101", "Fired heater", pick, 1, item));
        least("H-101", "Duty held", true, picked, duty, "%.1f", "MW", WORLD);

        // ---- Pumps ----
        System.out.println("--- pumps ---");
        movers.add(0, new Mover("P-101", 0, S.get("CRUDE"), "CRUDE", S.get("CRUDE").pressure(), 0, S.get("S-1").pressure() - S.get("CRUDE").pressure()));
        double draw19 = skirt + bottomSpace + (trays - 18) * ColumnTrays.SPACING;
        movers.add(1, new Mover("P-102", 0, S.get("S-8"), "S-8", S.get("S-8").pressure(), draw19, S.get("DIESEL").pressure() - S.get("S-8").pressure()));
        // The reflux pump lifts from the receiver to the top tray, against the overhead's own losses and a regulating valve.
        double overheadLoss = (run("Overhead") + e100.shellDrop()) * 1e5, drumLevel = saddles + dd / 2, density = liquid.density();
        double refluxRise = density * G * (height + skirt - drumLevel) + (run("Receiver out") + run("Pump out") + run("Reflux")) * 1e5 + overheadLoss + VALVE;
        movers.add(new Mover("Reflux pump", 1, condensate, "Receiver out", condenserNode.pressurePascal(), drumLevel, refluxRise));
        for (var m : movers) {
            var p = props(m.liquid());
            double q = p.volume() * MARGIN * 3600, head = m.rise() / (p.density() * G), power = p.volume() * MARGIN * m.rise() / 1e3;
            double discharge = 0;
            for (double d = ProcessUnit.SUCTION_STEP; d <= .6 + 1e-9; d += ProcessUnit.SUCTION_STEP) if (1.2 * ProcessUnit.pumpFlow(d) >= q) {
                discharge = d;
                break;
            }
            double boil = bubble(m.liquid().temperature(), SampleFlowsheetProbe.amounts(m.liquid()));
            double npsh = (m.source() + p.density() * G * m.head() - run(m.suctionLine()) * 1e5 - boil) / (p.density() * G);
            System.out.printf(Locale.ROOT, "%-12s %7.1f m3/h, head %6.1f m, ideal %7.1f kW, shaft at 70 %% %7.1f kW, discharge %.2f m, bubble %.3f bar, NPSH %.1f m%n", m.tag(), q, head, power, power / .7, discharge, boil / 1e5, npsh);
            UNITS.add(new Unit(m.depth(), m.tag(), "Pump", String.format(Locale.ROOT, "Discharge %.2f m, %.0f m", discharge, head), 1, "Pump"));
            most(m.tag(), "Flow", true, q, 36000, "%.0f", "m³/h", WORLD);
            quiet();
            most(m.tag(), "Head", true, head, 2e6 / (998.2 * G), "%.0f", "m", WORLD);
            quiet();
            least(m.tag(), "NPSH available", true, npsh, 0, "%.1f", "m", WORLD);
            quiet();
            least(m.tag(), "NPSH available", false, npsh, 1, "%.1f", "m", "[HI-961]");
            quiet();
            within(m.tag(), "Flow to rated", false, 100 * q / ProcessUnit.pumpFlow(discharge), 70, 120, "%.0f", "%", "[API610]");
        }

        // ---- Pressure paths ----
        var one = new ArrayList<Step>();
        one.add(new Step("S-1", "Line, 50 m", run("S-1"), ""));
        one.add(new Step("PA 1 cooler", "Tube side", tubeDrops[0], "0.30"));
        one.add(new Step("S-2", "Line, 50 m", run("S-2"), ""));
        one.add(new Step("PA 2 cooler", "Tube side", tubeDrops[1], "0.30"));
        one.add(new Step("S-3", "Line, 50 m", run("S-3"), ""));
        one.add(new Step("PA 3 cooler", "Tube side", tubeDrops[2], "0.30"));
        one.add(new Step("S-4", "Line, 50 m", run("S-4"), ""));
        one.add(new Step("E-101", "Tube side", e101.tubeDrop(), "0.50"));
        one.add(new Step("S-5", "Line, 50 m", run("S-5"), ""));
        one.add(new Step("H-101", "Coils: convection, then radiant", (dc + dr) / 1e5, "7.60"));
        PATHS.add(new PressurePath("P-101 to H-101's outlet", S.get("S-1").pressure() / 1e5, S.get("S-6").pressure() / 1e5, 0, one));
        var two = new ArrayList<Step>();
        two.add(new Step("S-9", "Line, 50 m", run("S-9"), ""));
        two.add(new Step("E-101", "Shell side", e101.shellDrop(), "0.30"));
        two.add(new Step("S-10", "Line, 50 m", run("S-10"), ""));
        var airLoss = new Step("E-102", "Tubes", bestDrop, "0.20");
        two.add(airLoss);
        two.add(new Step("RESIDUE", "Line, 50 m", run("RESIDUE"), ""));
        double sumpHead = props(S.get("S-9")).density() * G * (skirt + bottomSpace / 2) / 1e5;
        PATHS.add(new PressurePath("T-101's sump to RESIDUE", S.get("S-9").pressure() / 1e5, S.get("RESIDUE").pressure() / 1e5, sumpHead, two));
        for (var path : PATHS) System.out.printf(Locale.ROOT, "path %s: from %.2f + head %.2f to %.2f bar, available %.2f, lost %.2f, spare %.2f%n", path.name(), path.start(), path.head(), path.end(), path.available(), path.lost(), path.available() - path.lost());
        least("H-101", "Pressure spare on its path", true, PATHS.get(0).available() - PATHS.get(0).lost(), 0, "%.2f", "bar", WORLD);
        quiet();
        double friction = PATHS.get(0).lost() + S.get("S-6").pressure() / 1e5 - S.get("S-7").pressure() / 1e5, valveDrop = (S.get("S-6").pressure() - S.get("S-7").pressure()) / 1e5;
        UNITS.add(new Unit(0, "V-101", "Valve", String.format(Locale.ROOT, "Bore %.2f m", LINES.get("S-6").bore()), 1, "Valve"));
        least("V-101", "Drop", false, valveDrop, Math.max(.2 * friction, VALVE / 1e5), "%.2f", "bar", "[HALL-V]");
        least("E-102", "Pressure spare on its path", true, PATHS.get(1).available() - PATHS.get(1).lost(), 0, "%.2f", "bar", WORLD);
        quiet();

        write(diameter, chosenFlood, chosenTray);
    }

    /** The table {@code SimulatorSizing} draws. */
    static void write(double diameter, double flood, double floodTray) throws IOException {
        var out = new ArrayList<String>();
        int must = 0, should = 0;
        var counted = CRITS.stream().filter(c -> !c.tag().endsWith(" kept")).toList();
        for (var c : counted) {
            if (c.lamp() == 2) must++;
            if (c.lamp() == 1) should++;
        }
        for (var l : LINES.values()) if (l.lamp() == 1) should++;
        var warned = new ArrayList<String>();
        for (var c : counted) if (c.lamp() > 0) warned.add(c.tag() + " " + c.name().toLowerCase(Locale.ROOT));
        String tail = must > 0 ? must + " will not build" : should == 0 ? "every limit met" : should + (should == 1 ? " limit" : " limits") + " outside practice: " + String.join(", ", warned);
        out.add("STATUS\tSized at " + Math.round(100 * MARGIN) + " % of the steady flow: " + UNITS.size() + " units, " + LINES.size() + " lines; " + tail + "\t" + (must + should));
        out.add("BASIS\t" + Math.round(100 * MARGIN) + "\t" + Math.round(LINE_LENGTH) + "\t" + Math.round(PA_FALL));
        var order = new ArrayList<>(UNITS);
        order.sort(Comparator.comparingInt(u -> rank(u.tag())));
        for (var u : order) {
            var mine = CRITS.stream().filter(c -> c.tag().equals(u.tag())).toList();
            var head = mine.stream().filter(c -> !c.ok()).max(Comparator.comparingInt(Crit::lamp).thenComparingDouble(Crit::load)).or(() -> mine.stream().filter(Crit::ranks).max(Comparator.comparingDouble(Crit::load))).orElseThrow();
            int lamp = mine.stream().mapToInt(Crit::lamp).max().orElse(0);
            out.add(String.join("\t", "UNIT", "" + u.depth(), u.tag(), u.kind(), u.size(), head.name(), head.valueText(), head.limitText(), "" + lamp));
        }
        var kept = CRITS.stream().filter(c -> c.tag().equals("E-101 kept")).toList();
        var worst = kept.stream().filter(c -> !c.ok()).max(Comparator.comparingInt(Crit::lamp).thenComparingDouble(Crit::load)).orElseThrow();
        out.add(String.join("\t", "RATED", "E-101", "Shell 0.75 m, tubes 6 m", worst.name(), worst.valueText(), worst.limitText(), "" + worst.lamp()));
        for (var c : CRITS) out.add(String.join("\t", "CRIT", c.tag(), c.name(), c.must() ? "Must" : "Should", c.valueText(), c.limitText(), "" + c.lamp(), c.source()));
        for (var l : LINES.values())
            out.add(String.join("\t", "LINE", l.name(), l.service().label, String.format(Locale.ROOT, "%.0f", l.cubic()), (l.lines() > 1 ? l.lines() + " × " : "") + String.format(Locale.ROOT, "%.3f", l.bore()),
                    String.format(Locale.ROOT, "%.1f", l.speed()), String.format(Locale.ROOT, "%.3f", l.drop()), String.format(Locale.ROOT, "%.0f", l.momentum()), "" + l.lamp(), l.decides()));
        for (int k = 0; k < PATHS.size(); k++) {
            var path = PATHS.get(k);
            out.add(String.join("\t", "PATHSUM", path.name(), bar(path.available()), bar(path.lost()), bar(path.available() - path.lost()), bar(path.start()), bar(path.head()), bar(path.end())));
            double left = path.start() + path.head();
            for (var s : path.steps()) {
                left -= s.lost();
                out.add(String.join("\t", "PATH", "" + k, s.name(), s.what(), bar(s.lost()), bar(left), s.sheet(), left < path.end() ? "2" : "0"));
            }
        }
        for (var u : order) {
            var mine = CRITS.stream().filter(c -> c.tag().equals(u.tag())).toList();
            out.add(String.join("\t", "BUILD", u.tag(), "" + u.count(), u.item(), u.count() > 1 ? u.size().replaceFirst("^\\d+ × s", "S") : u.size(), "" + mine.stream().mapToInt(Crit::lamp).max().orElse(0)));
        }
        double metres = 0, largest = 0;
        int count = 0;
        for (var l : LINES.values()) {
            count += l.lines();
            metres += l.lines() * LINE_LENGTH;
            largest = Math.max(largest, l.bore());
        }
        out.add("BUILDLINES\tLines\t" + count + "\t");
        out.add("BUILDLINES\tPipe\t" + Math.round(metres) + "\tm");
        out.add("BUILDLINES\tLargest bore\t" + String.format(Locale.ROOT, "%.2f", largest) + "\tm");
        out.forEach(System.out::println);
        Files.write(Path.of("tools/process-simulator/sizing-sample.tsv"), out, StandardCharsets.UTF_8);
        System.out.printf(Locale.ROOT, "column D %.1f, design flood %.1f %% at tray %.0f; pipe limit %.2f m%n", diameter, 100 * flood, floodTray, PipeSizes.MAX_BORE);
    }

    static String bar(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    /** The table's order: down the sheet, the block's members under the block. */
    static int rank(String tag) {
        var order = List.of("P-101", "E-101", "H-101", "V-101", "T-101", "Condenser", "Receiver", "Reflux pump", "PA 1 cooler", "PA 1 pump", "PA 2 cooler", "PA 2 pump", "PA 3 cooler", "PA 3 pump", "P-102", "E-102");
        return order.indexOf(tag);
    }
}
