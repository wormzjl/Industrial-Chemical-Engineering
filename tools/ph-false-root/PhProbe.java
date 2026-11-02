package com.wormzjl.icheme.science.fluid.thermo;

import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult;
import com.wormzjl.icheme.science.thermo.phase.PhaseAmounts;
import com.wormzjl.icheme.science.thermo.phase.PhaseKind;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** PH and UV round trips of TP states on wet feeds, written to a TSV so two builds can be diffed. Gradle-free: see the README. */
public final class PhProbe {
    static final String PACKAGE = FluidMaterialCatalog.NETWORK_PACKAGE;
    static final MaterialCatalog CATALOG = MaterialCatalog.bundled();
    static FluidThermodynamics m;
    static NetworkPhaseEngine e;
    static int size, water;

    static double hcVapour(EquilibriumResult r) {
        double s = 0.0;
        for (PhaseAmounts ph : r.phases()) if (ph.kind() == PhaseKind.VAPOR) for (int i = 0; i < size; i++) if (i != water) s += ph.amount(i);
        return s;
    }
    static double t(EquilibriumResult r) { return r.phases().get(0).temperature(); }
    static double p(EquilibriumResult r) { return r.phases().get(0).pressure(); }
    static double steam(EquilibriumResult r) { return r.freeWater() == null ? 0.0 : r.freeWater().waterVapor(); }
    static double condensed(EquilibriumResult r) { return r.freeWater() == null ? 0.0 : r.freeWater().condensed(); }

    static double[] scaled(double[] feed) {
        double[] n = feed.clone();
        double sum = Arrays.stream(n).sum();
        for (int i = 0; i < n.length; i++) n[i] *= 100.0 / sum;
        return n;
    }

    static Map<String, double[]> feeds() {
        double[] crude = Arrays.copyOf(V3PengRobinsonThermo.fromRegisteredPackage(PACKAGE).crudeFeed("icheme:tia_juana_light_methane").moleFractions(), size);
        var feeds = new LinkedHashMap<String, double[]>();
        for (double ratio : new double[] {0.01, 0.2, 1.0, 5.0}) {
            double[] f = crude.clone();
            f[water] = ratio;
            feeds.put("crude+w" + ratio, f);
        }
        feeds.put("crude-dry", crude.clone());
        double[] light = new double[size]; light[0] = .5; light[1] = .3; light[2] = .2; light[water] = .2;
        feeds.put("light+w", light);
        // Boiling-range cuts: slices of the crude's pseudo-components.
        int top = size - 2;
        double[] mid = new double[size];
        for (int i = top / 3; i < 2 * top / 3; i++) mid[i] = crude[i];
        mid[water] = .3 * Arrays.stream(mid).sum();
        feeds.put("midcut+w", mid);
        double[] residue = new double[size];
        for (int i = size - 11; i < size - 1; i++) residue[i] = crude[i];
        residue[water] = .3 * Arrays.stream(residue).sum();
        feeds.put("residue+w", residue);
        double[] heavy = new double[size]; heavy[size - 3] = 1; heavy[water] = .3;
        feeds.put("heavy1+w", heavy);
        // One hydrocarbon beside water: the true three-phase step at P = p_sat(T) + P_v(T).
        double[] one = new double[size]; one[top / 2] = 1; one[water] = .3;
        feeds.put("pure-mid+w", one);
        double[] oneLight = new double[size]; oneLight[3] = 1; oneLight[water] = .3;
        feeds.put("pure-light+w", oneLight);
        double[] pure = new double[size]; pure[water] = 1;
        feeds.put("water", pure);
        // A clean heavy cut with no lights, then with a growing trace of methane: the volatility scale of the step.
        double[] clean = new double[size];
        for (int i = size - 7; i < size - 2; i++) clean[i] = crude[i];
        double sum = Arrays.stream(clean).sum();
        for (int i = 0; i < size; i++) clean[i] /= sum;
        double[] cut = clean.clone(); cut[water] = .3;
        feeds.put("heavycut+w", cut);
        for (double trace : new double[] {1e-9, 1e-7, 1e-5, 1e-3, 1e-2}) {
            double[] f = clean.clone(); f[0] = trace; f[water] = .3;
            feeds.put("heavycut+ch4" + trace, f);
        }
        return feeds;
    }

    static final double[] TS = {120, 200, 260, 280, 300, 320, 340, 350, 360, 370, 373.15, 376, 379, 382, 385, 390, 393, 395, 400, 410, 420, 435, 450, 465, 480, 485, 490, 500, 507, 520, 550, 600, 650, 700};
    static final double[] PS = {5e3, 1e5, 2e5, 5e5, 10e5, 20e5, 30e5, 60e5, 100e5};

    static void trips(Path out) throws Exception {
        var feeds = feeds();
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out))) {
            w.println("feed\tT\tP\tH\thcV\tsteam\tcond\tphOk\tphT\tphHcV\tphSteam\tphBal\tphCalls\tuvOk\tuvT\tuvP\tuvHcV\tuvBal\tuvCalls");
            for (var entry : feeds.entrySet()) {
                double[] n = scaled(entry.getValue());
                int tried = 0, refused = 0, phFail = 0, phOff = 0, uvFail = 0, uvOff = 0, calls = 0, callsMax = 0, uvCalls = 0, uvMax = 0, balance = 0;
                long start = System.nanoTime();
                for (double t : TS) for (double p : PS) {
                    boolean crystals = m.crystalsCompete(t, n);
                    var tp = e.tp(t, p, n, crystals);
                    if (!tp.converged()) { refused++; w.printf(Locale.ROOT, "%s\t%.2f\t%.0f\tREFUSED%n", entry.getKey(), t, p); continue; }
                    tried++;
                    var ph = e.ph(p, tp.enthalpy(), n, crystals);
                    var uv = e.crystalUv(tp.internalEnergy(), tp.volume(), n, crystals, Double.NaN, Double.NaN);
                    boolean phOk = ph.converged() && !ph.phases().isEmpty();
                    boolean uvOk = uv.converged() && !uv.phases().isEmpty();
                    int c = ph.diagnostics().outerIterations(), d = uv.diagnostics().outerIterations();
                    calls += c; callsMax = Math.max(callsMax, c); uvCalls += d; uvMax = Math.max(uvMax, d);
                    if (!phOk) phFail++; else if (Math.abs(t(ph) - t) > 1e-5) phOff++;
                    if (!uvOk) uvFail++; else if (Math.abs(t(uv) - t) > 1e-5 || Math.abs(p(uv) / p - 1) > 1e-5) uvOff++;
                    if (phOk && ph.diagnostics().coexistenceFromBalance()) balance++;
                    w.printf(Locale.ROOT, "%s\t%.2f\t%.0f\t%.6f\t%.6g\t%.6g\t%.6g\t%b\t%.7f\t%.6g\t%.6g\t%b\t%d\t%b\t%.7f\t%.3f\t%.6g\t%b\t%d%n", entry.getKey(), t, p, tp.enthalpy(), hcVapour(tp), steam(tp), condensed(tp),
                            phOk, phOk ? t(ph) : Double.NaN, phOk ? hcVapour(ph) : Double.NaN, phOk ? steam(ph) : Double.NaN, phOk && ph.diagnostics().coexistenceFromBalance(), c,
                            uvOk, uvOk ? t(uv) : Double.NaN, uvOk ? p(uv) : Double.NaN, uvOk ? hcVapour(uv) : Double.NaN, uvOk && uv.diagnostics().coexistenceFromBalance(), d);
                }
                System.out.printf(Locale.ROOT, "%-13s states %3d refusedTP %3d | PH fail %3d off %3d balance %3d calls mean %5.1f max %3d | UV fail %3d off %3d calls mean %6.1f max %4d | %.1f s%n",
                        entry.getKey(), tried, refused, phFail, phOff, balance, (double) calls / Math.max(1, tried), callsMax, uvFail, uvOff, (double) uvCalls / Math.max(1, tried), uvMax, (System.nanoTime() - start) * 1e-9);
            }
        }
    }

    /** T_sat(p) of the engine's free water. */
    static double saturation(double p) {
        var water = e.engine().freeWater();
        double a = 274, b = 640;
        for (int k = 0; k < 200 && Math.nextUp(a) < b; k++) {
            double c = 0.5 * (a + b);
            if (water.saturationPressure(c) < p) a = c; else b = c;
        }
        return 0.5 * (a + b);
    }

    /** Targets spread between H(T_sat - dT) and H(T_sat + dT): what lies at the water's boiling, step or no step. */
    static void gap(Path out) throws Exception {
        var feeds = feeds();
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out))) {
            w.println("feed\tP\tTsat\tfraction\ttarget\tok\tT\thcV\tsteam\tcond\tbal\tcalls\tdetail");
            for (var entry : feeds.entrySet()) {
                double[] n = scaled(entry.getValue());
                int tried = 0, fail = 0, bal = 0, calls = 0, max = 0;
                for (double p : new double[] {1e5, 2e5, 5e5, 10e5, 30e5, 60e5}) {
                    double ts = saturation(p);
                    double low = ts - 12, high = ts + 12;
                    var a = e.tp(low, p, n, false);
                    var b = e.tp(high, p, n, false);
                    int climb = 0;
                    while (!b.converged() && climb++ < 8) { high += 25; b = e.tp(high, p, n, false); }
                    if (!a.converged() || !b.converged()) { System.out.printf(Locale.ROOT, "  gap %s P %.0f: no ends (%b %b)%n", entry.getKey(), p, a.converged(), b.converged()); continue; }
                    for (double f = 0.05; f < 1.0; f += 0.1) {
                        double target = a.enthalpy() + f * (b.enthalpy() - a.enthalpy());
                        var ph = e.ph(p, target, n, false);
                        boolean ok = ph.converged() && !ph.phases().isEmpty();
                        int c = ph.diagnostics().outerIterations();
                        tried++; if (!ok) fail++; calls += c; max = Math.max(max, c);
                        if (ok && ph.diagnostics().coexistenceFromBalance()) bal++;
                        w.printf(Locale.ROOT, "%s\t%.0f\t%.5f\t%.2f\t%.3f\t%b\t%.7f\t%.6g\t%.6g\t%.6g\t%b\t%d\t%s%n", entry.getKey(), p, ts, f, target, ok, ok ? t(ph) : Double.NaN, ok ? hcVapour(ph) : Double.NaN, ok ? steam(ph) : Double.NaN, ok ? condensed(ph) : Double.NaN,
                                ok && ph.diagnostics().coexistenceFromBalance(), c, ok ? "" : String.valueOf(ph.detail()).replace('\t', ' ').substring(0, Math.min(150, String.valueOf(ph.detail()).length())));
                    }
                }
                System.out.printf(Locale.ROOT, "gap %-13s targets %3d fail %3d balance %3d calls mean %5.1f max %3d%n", entry.getKey(), tried, fail, bal, (double) calls / Math.max(1, tried), max);
            }
        }
    }

    static boolean traceFeeds;
    /** Water holding a trace of hydrocarbons: the gas can hold no more hydrocarbon than there is. */
    static Map<String, double[]> traceFeeds() {
        var feeds = new LinkedHashMap<String, double[]>();
        double[] crude = Arrays.copyOf(V3PengRobinsonThermo.fromRegisteredPackage(PACKAGE).crudeFeed("icheme:tia_juana_light_methane").moleFractions(), size);
        int top = size - 2;
        double[] mid = new double[size];
        for (int i = top / 3; i < 2 * top / 3; i++) mid[i] = crude[i];
        double[] pentane = new double[size]; pentane[6] = 1;
        double[] heavy = new double[size];
        for (int i = size - 7; i < size - 2; i++) heavy[i] = crude[i];
        var names = new LinkedHashMap<String, double[]>();
        names.put("crude", crude); names.put("midcut", mid); names.put("nC5", pentane); names.put("heavy", heavy);
        for (double ratio : new double[] {30, 100, 300, 1e3, 1e4, 1e6}) for (var n : names.entrySet()) {
            double[] f = n.getValue().clone();
            double sum = Arrays.stream(f).sum();
            for (int i = 0; i < size; i++) f[i] /= sum;
            f[water] = ratio;
            feeds.put(n.getKey() + " r" + ratio, f);
        }
        return feeds;
    }

    /** The feeds of the step stress: low water ratios over the volatility scale. */
    static Map<String, double[]> stepFeeds() {
        var feeds = new LinkedHashMap<String, double[]>();
        double[] crude = Arrays.copyOf(V3PengRobinsonThermo.fromRegisteredPackage(PACKAGE).crudeFeed("icheme:tia_juana_light_methane").moleFractions(), size);
        double[] clean = new double[size];
        for (int i = size - 7; i < size - 2; i++) clean[i] = crude[i];
        double sum = Arrays.stream(clean).sum();
        for (int i = 0; i < size; i++) clean[i] /= sum;
        for (double ratio : new double[] {1e-4, 1e-3, 1e-2, .3}) {
            for (double trace : new double[] {0, 1e-7, 1e-5, 1e-3}) {
                double[] f = clean.clone(); f[0] = trace; f[water] = ratio;
                feeds.put("cut r" + ratio + " ch4 " + trace, f);
            }
            double[] c = crude.clone(); c[water] = ratio;
            feeds.put("crude r" + ratio, c);
        }
        return feeds;
    }

    /**
     * Finds where the TP answer goes from free water present to none (the water's dry-out) and from no steam to steam (the
     * wet bubble point), by bisection on the rule's own answers, and aims PH targets between the two.
     */
    static void step(Path out) throws Exception {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out))) {
            w.println("feed\tP\tTsat\tT3\tTd\tshare\tfraction\ttarget\tok\tT\thcV\tsteam\tcond\tbal\tcalls\tdetail");
            for (var entry : (traceFeeds ? traceFeeds() : stepFeeds()).entrySet()) {
                double[] n = scaled(entry.getValue());
                int tried = 0, fail = 0, bal = 0, calls = 0, max = 0;
                for (double p : new double[] {1e5, 2e5, 5e5, 10e5, 30e5, 60e5}) {
                    double ts = saturation(p);
                    // T3: the highest temperature with no gas at all, from below.
                    double a = 280, b = ts + 40;
                    var lowEnd = e.tp(a, p, n, false);
                    if (!lowEnd.converged() || steam(lowEnd) > 0) { System.out.printf(Locale.ROOT, "  step %s P %.0f: gas at the cold end%n", entry.getKey(), p); continue; }
                    EquilibriumResult below = lowEnd;
                    for (int k = 0; k < 60 && b - a > 1e-9 * ts; k++) {
                        double c = 0.5 * (a + b);
                        var r = e.tp(c, p, n, false);
                        if (r.converged() && steam(r) == 0) { a = c; below = r; } else b = c;
                    }
                    double t3 = a;
                    // Td: the lowest temperature with no condensed water, from above.
                    double d = t3, f2 = ts + 40;
                    EquilibriumResult above = e.tp(f2, p, n, false);
                    int climb = 0;
                    while (!above.converged() && climb++ < 12) { f2 += 15; above = e.tp(f2, p, n, false); }
                    if (!above.converged()) { System.out.printf(Locale.ROOT, "  step %s P %.0f: no hot end%n", entry.getKey(), p); continue; }
                    for (int k = 0; k < 60 && f2 - d > 1e-9 * ts; k++) {
                        double c = 0.5 * (d + f2);
                        var r = e.tp(c, p, n, false);
                        if (r.converged() && condensed(r) == 0) { f2 = c; above = r; } else d = c;
                    }
                    double td = f2;
                    double share = hcVapour(above) / Math.max(1e-300, hcVapour(above) + steam(above));
                    for (double f = 0.1; f < 1.0; f += 0.2) {
                        double target = below.enthalpy() + f * (above.enthalpy() - below.enthalpy());
                        var ph = e.ph(p, target, n, false);
                        boolean ok = ph.converged() && !ph.phases().isEmpty();
                        int c = ph.diagnostics().outerIterations();
                        tried++; if (!ok) fail++; calls += c; max = Math.max(max, c);
                        if (ok && ph.diagnostics().coexistenceFromBalance()) bal++;
                        String detail = String.valueOf(ph.detail()).replace('\t', ' ');
                        w.printf(Locale.ROOT, "%s\t%.0f\t%.6f\t%.7f\t%.7f\t%.4g\t%.1f\t%.4f\t%b\t%.7f\t%.6g\t%.6g\t%.6g\t%b\t%d\t%s%n", entry.getKey(), p, ts, t3, td, share, f, target, ok, ok ? t(ph) : Double.NaN, ok ? hcVapour(ph) : Double.NaN, ok ? steam(ph) : Double.NaN, ok ? condensed(ph) : Double.NaN,
                                ok && ph.diagnostics().coexistenceFromBalance(), c, ok ? "" : detail.substring(0, Math.min(120, detail.length())));
                    }
                }
                System.out.printf(Locale.ROOT, "step %-22s targets %3d fail %3d balance %3d calls mean %5.1f max %3d%n", entry.getKey(), tried, fail, bal, (double) calls / Math.max(1, tried), max);
            }
        }
    }

    /** The zone around T_sat at one pressure: the rule's own answers, to read where gas appears and the water dries out. */
    static void scan(String name, double p, double from, double to, double step) {
        double[] n = scaled(feeds().get(name));
        System.out.printf(Locale.ROOT, "== %s P %.0f Tsat %.5f%n", name, p, saturation(p));
        for (double t = from; t <= to + 1e-9; t += step) {
            var tp = e.tp(t, p, n, false);
            if (!tp.converged()) { System.out.printf(Locale.ROOT, "  T %.4f refused: %s%n", t, String.valueOf(tp.detail()).substring(0, Math.min(120, String.valueOf(tp.detail()).length()))); continue; }
            var ph = e.ph(p, tp.enthalpy(), n, false);
            System.out.printf(Locale.ROOT, "  T %.4f H %.1f hcV %.5f steam %.5f cond %.5f | PH T %s hcV %s bal %s calls %d%n", t, tp.enthalpy(), hcVapour(tp), steam(tp), condensed(tp),
                    ph.converged() ? String.format(Locale.ROOT, "%.5f", t(ph)) : "FAIL", ph.converged() ? String.format(Locale.ROOT, "%.5f", hcVapour(ph)) : "-", ph.converged() && ph.diagnostics().coexistenceFromBalance(), ph.diagnostics().outerIterations());
        }
    }

    public static void main(String[] args) {
        MaterialRuntime.with(CATALOG, PACKAGE, () -> {
            m = FluidThermodynamics.forNetwork(CATALOG, PACKAGE);
            e = m.phaseEngine();
            size = m.components().size();
            water = size - 1;
            try {
                switch (args[0]) {
                    case "names" -> { System.out.println(m.components()); System.out.println("contract " + e.engine().contract().components().equals(m.components()) + " water " + e.engine().contract().waterIndex()); feeds().forEach((k, v) -> System.out.println(k + " present " + Arrays.stream(v).filter(x -> x > 0).count())); }
                    case "trips" -> trips(Path.of(args[1]));
                    case "gap" -> gap(Path.of(args[1]));
                    case "step" -> step(Path.of(args[1]));
                    case "traces" -> { traceFeeds = true; step(Path.of(args[1])); }
                    case "scan" -> scan(args[1], Double.parseDouble(args[2]), Double.parseDouble(args[3]), Double.parseDouble(args[4]), Double.parseDouble(args[5]));
                    default -> throw new IllegalArgumentException(args[0]);
                }
            } catch (Exception x) { throw new RuntimeException(x); }
            return null;
        });
    }
}
