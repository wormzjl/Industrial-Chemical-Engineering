package com.wormzjl.icheme.science.material;

import com.wormzjl.icheme.science.math.DeterministicMath;
import com.wormzjl.icheme.science.thermo.HuronVidalExcess;
import com.wormzjl.icheme.science.thermo.PairInteractions;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleUnaryOperator;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;

/**
 * The PR78 kernel of a compound list with every pair derived from the compounds' own data, no pair record per compound
 * (batch 2026-11-11-aqueous-solvents, plan M4, work package W2b): the {@link HuronVidalExcess}, the Mathias-Copeman
 * coefficients and the critical constants for {@link PengRobinsonKernel#huronVidal}. Ported from
 * {@code tools/aqueous-solvents/group-derived-pairs.mjs} ({@code deriveShape}, {@code gasEnergy}) and
 * {@code gas-in-organics.mjs} (the homomorph rule). Each unordered pair takes exactly one source, the first that applies:
 * <ol>
 *   <li>{@link Source#LIBRARY}: a pair the package's interaction libraries give (a constant {@code k_ij}, or a pair its
 *   group-contribution rule resolves, {@code k_ij(T)} exact), as a classical pair;</li>
 *   <li>{@link Source#HYDROGEN}: hydrogen with anything, {@code k_ij = 0} (owner rule of 2026-11-04);</li>
 *   <li>{@link Source#HENRY}: water with a gas that carries {@code henry_in_water}: one energy for both directions at
 *   {@code alpha = 0.3}, at 298.15 K and at 373.15 K (linear in T between), such that the gas's fugacity coefficient at
 *   infinite dilution in liquid water at 101.325 kPa is {@code k_H/P};</li>
 *   <li>{@link Source#ACTIVITY}: two compounds with modified UNIFAC (Dortmund) counts, both liquids at 298.15 K and
 *   2 MPa (below Tc, root denser than PR's critical volume), at least one polar (a main group other than 1, 2, 3, 4 and
 *   42; water is polar), whose main-group pairs the table holds: the group model's ln gamma of both compounds on 15
 *   compositions at 298.15 K fitted by least squares, through the equation of state at 2 MPa
 *   ({@code ln gamma_i = ln phi_i - ln phi_i} of the pure liquid), into alpha and the two energies; then the two energies
 *   alone at 373.15 K with that alpha (constant energies when a compound is not a liquid at 373.15 K); energies linear
 *   in T. The water-hydrocarbon block of the table applies where its rule holds. Where the group model has one liquid
 *   the fit is held to one (a falling {@code ln x_1 + ln gamma_1} is penalised) and refused if it keeps two; one liquid
 *   where the group model has two is kept with a notice ({@link Result#notices()}). A compound that is not a liquid at
 *   298.15 K, or a pair the table lacks, takes the next source;</li>
 *   <li>{@link Source#HOMOMORPH}: a compound without Dortmund counts against a polar compound other than water that names
 *   a {@link MaterialCatalog.Homomorph}: E-PPR78's {@code k_ij(T)} of the compound with the homomorph's default dataset,
 *   on the homomorph's own Tc, Pc and acentric factor, used as a classical pair of the real compounds, at 298.15 K and
 *   373.15 K and linear in T between;</li>
 *   <li>{@link Source#NONPOLAR}: two compounds that are neither a gas (a compound with {@code henry_in_water}) nor
 *   polar, {@code k_ij = 0} (owner, 2026-11-11, option B of W3B_NETWORK_PLAN.md section 8);</li>
 *   <li>{@link Source#EPPR78}: a gas or a polar compound with a compound, both with E-PPR78 counts: E-PPR78's
 *   {@code k_ij} on the two compounds' own Tc, Pc, acentric factors and alphas at 298.15 K, constant in temperature (its
 *   power law in 298.15/T leaves its fitted range inside the network's: |k| above 1 at 150 K and at 700 K);</li>
 *   <li>{@link Source#MISSING}: anything else, {@code k_ij = 0}, listed by name with its reason in
 *   {@link Result#missing()}.</li>
 * </ol>
 * <p>A crude cut (a {@code petroleum_fraction} without counts in a scheme) takes paraffinic counts from its molar mass M
 * (g/mol): {@code n = round((M - 2.01588)/14.02658)} carbons of a normal paraffin, rounded half up, as 2 CH3 and
 * {@code n - 2} CH2, in both schemes. A fit that does not converge (no root, a penalty left at the optimum, two liquids
 * where the group model has one, a Henry energy outside its bracket) is refused with both compounds' names ({@link Refused}). Each fitted pair is kept for the life of
 * the catalog in its {@link Cache}, keyed by the source and the records it read with their revisions, so lists sharing a
 * pair fit it once. The fits are deterministic: fixed starting points, fixed iteration counts, every transcendental
 * function through {@link DeterministicMath} or the kernel.</p>
 */
public final class DerivedPairs {
    /** The declared revision of the derivation: bumped when a rule, a constant or a fit setting here changes. */
    public static final String REVISION = "derived-pairs-r4";
    public static final double FIRST_KELVIN = 298.15;
    public static final double SECOND_KELVIN = 373.15;
    /** The pressure of the activity fit: both compounds liquid at both temperatures for the usual solvents. */
    static final double FIT_PRESSURE = 2.0e6;
    /** The pressure a gas's Henry constant is matched at. */
    static final double HENRY_PRESSURE = 101325.0;
    static final double HENRY_ALPHA = 0.3;
    /** The bracket of the Henry energy, J/mol, and the bisection's step count. */
    static final double HENRY_LOW = -30000.0, HENRY_HIGH = 80000.0;
    static final int HENRY_STEPS = 200;
    /** Mole fractions of the first compound the activity fit compares on. */
    static final double[] GRID = {0.0, 0.02, 0.05, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 0.95, 0.98, 1.0};
    /** The liquid-count scan: x = 0.01 to 0.99 in steps of 0.01. */
    static final int SCAN_POINTS = 99;
    static final int SIMPLEX_ITERATIONS = 300;
    static final double[] ALPHA_STARTS = {0.2, 0.3, 0.45};
    static final double ALPHA_LOW = 0.05, ALPHA_HIGH = 0.7;
    static final double ENERGY_STEP = 800.0, ALPHA_STEP = 0.05;
    /** The objective of a trial without a root or outside the alpha bounds; an optimum at or above it is not converged. */
    static final double PENALTY = 1.0e9;
    /**
     * PR's critical volume over its co-volume, {@code Z_c/Omega_b = 0.30740/0.07780}: a pure compound is a liquid at a fit
     * temperature when it is below its critical temperature and its smallest root at the fit pressure is denser than this.
     */
    static final double CRITICAL_VOLUME_RATIO = 0.30740 / 0.07780;
    /** Dortmund main groups of a compound without a polar group: alkane, alkene, aromatic carbon, alkyl aromatic, cycloalkane. */
    static final Set<Integer> NONPOLAR_MAIN_GROUPS = Set.of(1, 2, 3, 4, 42);
    static final String HYDROGEN = "Hydrogen";
    static final String WATER = "Water";
    /** g/mol of CH2 and of the paraffin's end hydrogens: C_nH_(2n+2) = n CH2 + H2. */
    static final double CH2_GRAMS = 14.02658, END_GRAMS = 2.01588;
    /** The token a paraffinic count of a cut carries in place of a record revision (it reads only the molar mass). */
    static final String PARAFFINIC = "paraffinic-by-molar-mass";
    private static final double R = PengRobinsonKernel.GAS_CONSTANT;

    private DerivedPairs() {}

    /** Where a pair's interaction comes from, in the order of precedence. */
    public enum Source { LIBRARY, HYDROGEN, HENRY, ACTIVITY, HOMOMORPH, NONPOLAR, EPPR78, MISSING }

    /** One unordered pair of the list: its compounds (list order), its source and what it holds. */
    public record Pair(String first, String second, Source source, String detail) {}

    /** A pair whose derivation does not converge: refused by both compounds' names, never set to zero. */
    public static final class Refused extends IllegalArgumentException {
        Refused(String first, String second, String reason) {
            super("Derived pair " + first + " / " + second + " refused: " + reason);
        }
    }

    /** The kernel's inputs for one list, in the list's order. */
    public static final class Result {
        private final List<String> components;
        private final double[] criticalTemperatures, criticalPressures, acentricFactors;
        private final double[][] mathiasCopeman;
        private final HuronVidalExcess excess;
        private final List<Pair> pairs;
        private final List<String> missing;
        private final List<String> notices;
        private final String identity;
        private final int fitsRun;
        private final boolean[] polar;

        private Result(List<String> components, double[] tc, double[] pc, double[] omega, double[][] alpha, HuronVidalExcess excess,
                       List<Pair> pairs, List<String> missing, List<String> notices, String identity, int fitsRun, boolean[] polar) {
            this.polar = polar;
            this.components = List.copyOf(components);
            this.criticalTemperatures = tc; this.criticalPressures = pc; this.acentricFactors = omega; this.mathiasCopeman = alpha;
            this.excess = excess; this.pairs = List.copyOf(pairs); this.missing = List.copyOf(missing); this.notices = List.copyOf(notices);
            this.identity = identity; this.fitsRun = fitsRun;
        }

        public List<String> components() { return components; }
        public double[] criticalTemperatures() { return criticalTemperatures.clone(); }
        public double[] criticalPressures() { return criticalPressures.clone(); }
        public double[] acentricFactors() { return acentricFactors.clone(); }
        /** Per compound null (PR78's kappa) or {@code {c1, c2, c3}}; a copy. */
        public double[][] mathiasCopeman() {
            double[][] copy = new double[mathiasCopeman.length][];
            for (int i = 0; i < copy.length; i++) copy[i] = mathiasCopeman[i] == null ? null : mathiasCopeman[i].clone();
            return copy;
        }
        public HuronVidalExcess excess() { return excess; }
        /** Every unordered pair, {@code (0,1), (0,2), ..., (1,2), ...}, with its source. */
        public List<Pair> pairs() { return pairs; }
        /** The pairs of {@link Source#MISSING}, {@code "A / B: reason"}: zero in the kernel. */
        public List<String> missing() { return missing; }
        /**
         * Pairs kept with a remark, {@code "A / B: remark"}: an activity pair whose derived liquid count is one where the
         * group model has two (the fit holds a pair to one liquid only), and a pair whose group model lacks a main-group
         * pair, which took the next source.
         */
        public List<String> notices() { return notices; }
        /**
         * {@code derived-pairs=<REVISION>;records=<token>,...}: the sorted tokens of every record the derivation read, each
         * with its declared revision (datasets, group counts, the group tables and the block, Henry constants, homomorphs,
         * the water model, pair libraries). Nothing is hashed.
         */
        public String identity() { return identity; }
        /** Pairs this call fitted; zero when every fit came from the catalog's cache. */
        public int fitsRun() { return fitsRun; }
        /**
         * Whether compound {@code i} is polar by its Dortmund counts (a main group other than 1, 2, 3, 4 and 42; water is
         * polar): the equilibrium engine's three-slot system runs for a feed holding one (W3a D3).
         */
        public boolean polar(int i) { return polar[i]; }

        /** The kernel on these inputs over the given temperature and pressure box. */
        public PengRobinsonKernel kernel(double minimumKelvin, double maximumKelvin, double minimumPascal, double maximumPascal) {
            return PengRobinsonKernel.huronVidal(criticalTemperatures, criticalPressures, acentricFactors, excess, mathiasCopeman,
                    minimumKelvin, maximumKelvin, minimumPascal, maximumPascal);
        }
    }

    /** The fitted pairs of one catalog and its views, by source and the tokens of the records read. */
    static final class Cache {
        private final ConcurrentHashMap<String, Object> fits = new ConcurrentHashMap<>();
        private final AtomicInteger runs = new AtomicInteger();

        /** Fits run since the catalog was made. */
        int fitsRun() { return runs.get(); }

        /** The kept fit of {@code key}, else {@code fit}'s value, kept; {@code counter} counts a fit run here. */
        @SuppressWarnings("unchecked")
        <T> T get(String key, Supplier<T> fit, AtomicInteger counter) {
            Object kept = fits.get(key);
            if (kept != null) return (T) kept;
            T value = fit.get();
            runs.incrementAndGet();
            counter.incrementAndGet();
            Object prior = fits.putIfAbsent(key, value);
            return prior != null ? (T) prior : value;
        }
    }

    /** Every compound of package {@code packageId}, in its order. */
    public static Result derive(MaterialCatalog catalog, String packageId) {
        return derive(catalog, packageId, catalog.requirePackage(packageId).components());
    }

    /** The compounds {@code componentIds} of package {@code packageId}, in the order given. */
    public static Result derive(MaterialCatalog catalog, String packageId, List<String> componentIds) {
        Objects.requireNonNull(catalog, "catalog");
        var p = catalog.requirePackage(packageId);
        if (!p.model().equals("pr78")) throw new IllegalArgumentException("Derived pairs need a pr78 package, " + packageId + " is " + p.model());
        var ids = new ArrayList<String>();
        for (String id : componentIds) ids.add(p.canonicalId(id));
        if (ids.isEmpty() || new HashSet<>(ids).size() != ids.size()) throw new IllegalArgumentException("Derived pairs: expected distinct compounds, got " + componentIds);
        int n = ids.size();
        var context = new Context(catalog, packageId, p);
        var compounds = new ArrayList<Compound>();
        for (String id : ids) compounds.add(context.compound(id));
        var authored = catalog.authoredPairs(packageId);
        var tokens = new TreeSet<String>();
        for (Compound c : compounds) tokens.add(c.token);
        var excess = HuronVidalExcess.builder(n);
        // Only an authored group-contribution rule carries k_ij(T) here; every derived pair is a constant or a line in T.
        var model = PairInteractions.builder(n);
        boolean modelPairs = false;
        var pairs = new ArrayList<Pair>();
        var missing = new ArrayList<String>();
        var notices = new ArrayList<String>();
        var counter = new AtomicInteger();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                Compound a = compounds.get(i), b = compounds.get(j);
                int pi = p.components().indexOf(a.id), pj = p.components().indexOf(b.id);
                var library = authored.get(Math.min(pi, pj) * p.components().size() + Math.max(pi, pj));
                Pair pair;
                if (library != null) {
                    tokens.addAll(library.tokens());
                    if (library.rule() != null) {
                        model.groupTerms(i, j, library.rule().weights(), library.rule().aPascal(), library.rule().exponents());
                        modelPairs = true;
                        pair = new Pair(a.id, b.id, Source.LIBRARY, "rule k_ij(T) of " + library.tokens());
                    } else {
                        excess.classical(i, j, library.kij());
                        pair = new Pair(a.id, b.id, Source.LIBRARY, "k_ij " + library.kij() + " of " + library.tokens());
                    }
                } else if (a.id.equals(HYDROGEN) || b.id.equals(HYDROGEN)) {
                    excess.classical(i, j, 0.0);
                    pair = new Pair(a.id, b.id, Source.HYDROGEN, "k_ij 0");
                } else if (a.id.equals(WATER) && b.henry != null || b.id.equals(WATER) && a.henry != null) {
                    Compound gas = a.id.equals(WATER) ? b : a, water = gas == a ? b : a;
                    var read = new TreeSet<>(List.of(gas.token, water.token, gas.henryToken, context.waterToken()));
                    tokens.addAll(read);
                    double[] e = context.cache.get("henry|" + String.join(",", read), () -> context.henryEnergies(gas, water), counter);
                    int g = gas == a ? i : j, w = gas == a ? j : i;
                    excess.nrtl(g, w, HENRY_ALPHA, FIRST_KELVIN, e[0], e[0], SECOND_KELVIN, e[1], e[1]);
                    pair = new Pair(a.id, b.id, Source.HENRY, String.format(java.util.Locale.ROOT,
                            "alpha %.2f, energy %.1f J/mol at %.2f K, %.1f at %.2f K", HENRY_ALPHA, e[0], FIRST_KELVIN, e[1], SECOND_KELVIN));
                } else if (a.activity() && b.activity() && (a.polar || b.polar) && context.groupModel(a, b, notices)) {
                    // Fitted with the compounds in record-key order, so one fit serves either list order.
                    boolean ordered = a.recordKey.compareTo(b.recordKey) <= 0;
                    Compound first = ordered ? a : b, second = ordered ? b : a;
                    var read = new TreeSet<>(List.of(first.token, second.token, first.dortmundToken, second.dortmundToken, context.tableToken()));
                    boolean block = context.blockApplies(first, second);
                    if (block) read.add(context.blockToken());
                    tokens.addAll(read);
                    ActivityFit fit = context.cache.get("activity|" + first.recordKey + "|" + second.recordKey + "|" + String.join(",", read),
                            () -> context.activity(first, second), counter);
                    int f = ordered ? i : j, s = ordered ? j : i;
                    excess.nrtl(f, s, fit.alpha, FIRST_KELVIN, fit.first[1], fit.first[0], SECOND_KELVIN, fit.second[1], fit.second[0]);
                    pair = new Pair(a.id, b.id, Source.ACTIVITY, fit.describe(first.id, second.id) + (block ? ", water-hydrocarbon block" : ""));
                    if (fit.notice != null) notices.add(first.id + " / " + second.id + ": " + fit.notice);
                } else {
                    Compound polar = a.polar && !a.id.equals(WATER) ? a : b.polar && !b.id.equals(WATER) ? b : null;
                    Compound other = polar == a ? b : a;
                    // The homomorph rule where it applies, else E-PPR78; the reasons of each that does not.
                    var reasons = new ArrayList<String>();
                    if (polar != null && !other.activity()) {
                        if (polar.homomorph == null) reasons.add(polar.id + " names no homomorph");
                        else if (other.eppr78 == null) reasons.add(other.id + " has no E-PPR78 counts");
                        else {
                            var homomorph = context.homomorph(polar);
                            if (homomorph == null) reasons.add("homomorph " + polar.homomorph.component() + " of " + polar.id + " has no dataset with E-PPR78 counts");
                            else {
                                var read = new TreeSet<>(List.of(other.token, other.eppr78Token, polar.token, polar.homomorphToken,
                                        homomorph.token, homomorph.eppr78Token, context.matrixToken()));
                                try {
                                    double[] k = context.cache.get("homomorph|" + String.join(",", read),
                                            () -> context.eppr78Interaction(other, homomorph, FIRST_KELVIN, SECOND_KELVIN), counter);
                                    tokens.addAll(read);
                                    excess.classicalLinear(i, j, FIRST_KELVIN, k[0], SECOND_KELVIN, k[1]);
                                    pairs.add(new Pair(a.id, b.id, Source.HOMOMORPH, String.format(java.util.Locale.ROOT,
                                            "k_ij %.5f at %.2f K, %.5f at %.2f K (E-PPR78 with %s)", k[0], FIRST_KELVIN, k[1], SECOND_KELVIN, homomorph.id)));
                                    continue;
                                } catch (Unresolved unresolved) {
                                    reasons.add(unresolved.getMessage());
                                }
                            }
                        }
                    }
                    if (a.henry == null && b.henry == null && !a.polar && !b.polar) {
                        excess.classical(i, j, 0.0);
                        pairs.add(new Pair(a.id, b.id, Source.NONPOLAR, "k_ij 0"));
                        continue;
                    }
                    if (a.eppr78 != null && b.eppr78 != null) {
                        // Fitted with the compounds in record-key order, so one value serves either list order.
                        boolean ordered = a.recordKey.compareTo(b.recordKey) <= 0;
                        Compound first = ordered ? a : b, second = ordered ? b : a;
                        var read = new TreeSet<>(List.of(first.token, second.token, first.eppr78Token, second.eppr78Token, context.matrixToken()));
                        try {
                            double[] k = context.cache.get("eppr78|" + String.join(",", read),
                                    () -> context.eppr78Interaction(first, second, FIRST_KELVIN), counter);
                            tokens.addAll(read);
                            excess.classical(i, j, k[0]);
                            pairs.add(new Pair(a.id, b.id, Source.EPPR78, String.format(java.util.Locale.ROOT, "k_ij %.5f (E-PPR78 at %.2f K)", k[0], FIRST_KELVIN)));
                            continue;
                        } catch (Unresolved unresolved) {
                            reasons.add(unresolved.getMessage());
                        }
                    } else {
                        reasons.add((a.eppr78 == null ? a.id : b.id) + " has no E-PPR78 counts");
                    }
                    String reason = String.join("; ", reasons);
                    excess.classical(i, j, 0.0);
                    missing.add(a.id + " / " + b.id + ": " + reason);
                    pair = new Pair(a.id, b.id, Source.MISSING, "k_ij 0: " + reason);
                }
                pairs.add(pair);
            }
        }
        if (modelPairs) excess.interactions(model.build());
        double[] tc = new double[n], pc = new double[n], omega = new double[n];
        double[][] alpha = new double[n][];
        boolean[] polar = new boolean[n];
        for (int i = 0; i < n; i++) {
            Compound c = compounds.get(i);
            tc[i] = c.tc; pc[i] = c.pc; omega[i] = c.omega; alpha[i] = c.alpha == null ? null : c.alpha.clone();
            polar[i] = c.polar || c.id.equals(WATER);
        }
        String identity = "derived-pairs=" + REVISION + ";records=" + String.join(",", tokens);
        return new Result(ids, tc, pc, omega, alpha, excess.build(), pairs, missing, notices, identity, counter.get(), polar);
    }

    /** A group-contribution pair the tables cannot resolve: the pair falls to {@link Source#MISSING} with this reason. */
    private static final class Unresolved extends RuntimeException {
        Unresolved(String message, Throwable cause) { super(message, cause, false, false); }
    }

    /** One compound of the list with the data the derivation reads, and the token of each record. */
    private record Compound(String id, String recordKey, String token, double tc, double pc, double omega, double[] alpha,
                            Map<String, Integer> dortmund, String dortmundToken, Map<String, Integer> eppr78, String eppr78Token,
                            HenryConstant henry, String henryToken, MaterialCatalog.Homomorph homomorph, String homomorphToken,
                            boolean polar, boolean liquidAtFirst, boolean liquidAtSecond) {
        /** Takes part in an activity pair: Dortmund counts, and a liquid at 298.15 K and the fit pressure. */
        boolean activity() { return dortmund != null && liquidAtFirst; }
    }

    /** A fitted activity pair: alpha and the energies {@code {dg(second around first), dg(first around second)}} at both temperatures. */
    private record ActivityFit(double alpha, double[] first, double[] second, double firstRms, double secondRms, String notice) {
        String describe(String a, String b) {
            return String.format(java.util.Locale.ROOT, "alpha %.4f; dg(%s around %s) %.1f, dg(%s around %s) %.1f J/mol at %.2f K (rms ln gamma %.4f); "
                    + "%.1f, %.1f at %.2f K (rms %.4f)", alpha, b, a, first[0], a, b, first[1], FIRST_KELVIN, firstRms,
                    second[0], second[1], SECOND_KELVIN, secondRms);
        }
    }

    /** The records of one derivation: the package, its tables and the cache. */
    private static final class Context {
        final MaterialCatalog catalog;
        final String packageId;
        final MaterialCatalog.Package pack;
        final Cache cache;
        final ModifiedUnifac table;
        final GroupInteractionMatrix matrix;

        Context(MaterialCatalog catalog, String packageId, MaterialCatalog.Package pack) {
            this.catalog = catalog; this.packageId = packageId; this.pack = pack; this.cache = catalog.derivedPairCache();
            var tables = new TreeMap<>(catalog.modifiedUnifacTables());
            this.table = tables.isEmpty() ? null : tables.firstEntry().getValue();
            GroupInteractionMatrix found = null;
            for (var m : new TreeMap<>(catalog.groupInteractionMatrices()).values()) if (m.scheme().equals(Eppr78Groups.SCHEME)) { found = m; break; }
            this.matrix = found;
        }

        String tableToken() { return table.id() + "@" + table.revision(); }
        String blockToken() { return table.id() + "/water_hydrocarbon_block@" + table.block().orElseThrow().revision(); }
        String matrixToken() { return matrix.id() + "@" + matrix.revision(); }
        String waterToken() { return "water-model@" + pack.water().revision(); }

        Compound compound(String id) {
            int index = pack.components().indexOf(id);
            if (index < 0) throw new IllegalArgumentException("Derived pairs: " + id + " is outside package " + packageId);
            var property = pack.properties().get(index);
            return compound(id, property, catalog.groupCounts(packageId, id), catalog.henryInWater(packageId, id).orElse(null),
                    catalog.homomorph(packageId, id).orElse(null));
        }

        Compound compound(String id, MaterialCatalog.Property property, Map<String, MaterialCatalog.GroupCounts> counts,
                          HenryConstant henry, MaterialCatalog.Homomorph homomorph) {
            var pr = property.pr();
            if (pr == null) throw new IllegalArgumentException("Derived pairs: " + property.id() + " has no PR78 constants");
            String key = "properties/" + property.id();
            String token = key + "@" + catalog.propertyRevision(property.id());
            double[] alpha = null;
            if (pr.alpha() != null) {
                alpha = new double[3];
                for (int k = 0; k < pr.alpha().coefficients().size(); k++) alpha[k] = pr.alpha().coefficients().get(k);
            }
            boolean cut = "petroleum_fraction".equals(catalog.name(property.component()).kind());
            Map<String, Integer> dortmund = null, eppr78 = null;
            String dortmundToken = null, eppr78Token = null;
            var d = counts.get(ModifiedUnifac.SCHEME);
            var e = counts.get(Eppr78Groups.SCHEME);
            if (d != null) { dortmund = d.counts(); dortmundToken = key + "/" + d.scheme() + "@" + d.revision(); }
            else if (cut) { dortmund = paraffinic(property); dortmundToken = key + "/" + ModifiedUnifac.SCHEME + "@" + PARAFFINIC; }
            if (e != null) { eppr78 = e.counts(); eppr78Token = key + "/" + e.scheme() + "@" + e.revision(); }
            else if (cut) { eppr78 = paraffinic(property); eppr78Token = key + "/" + Eppr78Groups.SCHEME + "@" + PARAFFINIC; }
            if (dortmund != null && table == null) throw new IllegalArgumentException("Derived pairs: no modified UNIFAC table for " + id);
            boolean polar = false;
            if (dortmund != null) for (String name : dortmund.keySet()) {
                var subgroup = table.subgroup(name).orElseThrow(() -> new IllegalArgumentException("Derived pairs: unknown subgroup " + name + " of " + id));
                polar |= !NONPOLAR_MAIN_GROUPS.contains(subgroup.mainGroup());
            }
            return new Compound(id, key, token, pr.criticalTemperature(), pr.criticalPressure(), pr.acentricFactor(), alpha,
                    dortmund, dortmundToken, eppr78, eppr78Token,
                    henry, henry == null ? null : key + "/henry_in_water@" + henry.revision(),
                    homomorph, homomorph == null ? null : key + "/homomorph@" + homomorph.revision(), polar,
                    liquid(pr, alpha, FIRST_KELVIN), liquid(pr, alpha, SECOND_KELVIN));
        }

        /** Whether the pure compound is a liquid at {@code temperature} and {@link #FIT_PRESSURE} (see {@link #CRITICAL_VOLUME_RATIO}). */
        static boolean liquid(MaterialCatalog.Pr78 pr, double[] alpha, double temperature) {
            if (!(temperature < pr.criticalTemperature())) return false;
            var kernel = PengRobinsonKernel.huronVidal(new double[] {pr.criticalTemperature()}, new double[] {pr.criticalPressure()},
                    new double[] {pr.acentricFactor()}, HuronVidalExcess.builder(1).build(), new double[][] {alpha}, 1.0, 3000.0, 1.0, 1.0e9);
            var evaluation = kernel.newEvaluation();
            kernel.evaluate(temperature, FIT_PRESSURE, new double[] {1.0}, PengRobinsonKernel.Root.LIQUID, kernel.newWorkspace(), evaluation);
            double volumeRatio = evaluation.compressibility() * R * temperature / (FIT_PRESSURE * evaluation.bMix());
            return volumeRatio < CRITICAL_VOLUME_RATIO;
        }

        /** The homomorph of a polar compound as a compound: its default dataset with its E-PPR78 counts; null without either. */
        Compound homomorph(Compound polar) {
            var property = catalog.defaultProperty(polar.homomorph.component()).orElse(null);
            if (property == null || property.pr() == null) return null;
            var counts = catalog.propertyGroupCounts(property.id());
            var c = compound(polar.homomorph.component(), property, counts, null, null);
            return c.eppr78 == null ? null : c;
        }

        /** Two compounds of the block's rule: water (main group of water only) and one without a polar group. */
        boolean blockApplies(Compound a, Compound b) {
            return table.mixture(List.of(a.dortmund, b.dortmund), true).blockApplied();
        }

        /** E-PPR78 terms {weights, A Pa, exponents} of two compounds' counts; refused as {@link Unresolved}. */
        double[][] eppr78Terms(Compound a, Compound b) {
            if (matrix == null) throw new Unresolved("no E-PPR78 group matrix in the catalog", null);
            try {
                var terms = GroupContributionInteractions.terms(matrix, GroupContributionInteractions.fractions(matrix, a.eppr78, a.id),
                        GroupContributionInteractions.fractions(matrix, b.eppr78, b.id), a.id + " / " + b.id);
                double[][] out = new double[3][terms.size()];
                for (int t = 0; t < terms.size(); t++) {
                    out[0][t] = terms.get(t).weight(); out[1][t] = terms.get(t).aPascal(); out[2][t] = terms.get(t).exponent();
                }
                return out;
            } catch (IllegalArgumentException unresolved) {
                throw new Unresolved(unresolved.getMessage(), unresolved);
            }
        }

        /**
         * E-PPR78's k_ij of {@code gas} with {@code other} at each of {@code temperatures}, on the two compounds' own Tc,
         * Pc, acentric factors and alphas ({@code gasEnergy}'s sibling in {@code gas-in-organics.mjs}): a homomorph pair
         * reads it at 298.15 K and 373.15 K, a gas pair at 298.15 K.
         */
        double[] eppr78Interaction(Compound gas, Compound other, double... temperatures) {
            double[][] terms = eppr78Terms(gas, other);
            var interactions = PairInteractions.builder(2).groupTerms(0, 1, terms[0], terms[1], terms[2]).build();
            var kernel = PengRobinsonKernel.huronVidal(new double[] {gas.tc, other.tc}, new double[] {gas.pc, other.pc},
                    new double[] {gas.omega, other.omega}, HuronVidalExcess.builder(2).build(),
                    new double[][] {gas.alpha, other.alpha}, 1.0, 3000.0, 1.0, 1.0e9);
            double[] k = new double[temperatures.length];
            for (int t = 0; t < temperatures.length; t++) {
                var workspace = kernel.newWorkspace();
                kernel.prepareTemperature(temperatures[t], workspace);
                double[] rootDt = new double[2], rootDt2 = new double[2], value = new double[1], d1 = new double[1], d2 = new double[1];
                kernel.prepareRootDerivatives(temperatures[t], workspace, rootDt, rootDt2);
                interactions.evaluate(temperatures[t], workspace.attractionRootsView(), rootDt, rootDt2,
                        new double[] {kernel.coVolume(0), kernel.coVolume(1)}, value, d1, d2);
                if (!Double.isFinite(value[0])) throw new Refused(gas.id, other.id, "E-PPR78 k_ij is not finite at " + temperatures[t] + " K");
                k[t] = value[0];
            }
            return k;
        }

        /**
         * The Henry pair's energy at 298.15 K and 373.15 K ({@code gasEnergy}): both directions {@code e}, alpha 0.3, and
         * {@code ln phi_gas} at infinite dilution in liquid water at 101.325 kPa equal to {@code ln(k_H/P)}, by bisection
         * on {@code [-30000, 80000]} J/mol.
         */
        double[] henryEnergies(Compound gas, Compound water) {
            var binary = new Binary(gas, water);
            double[] out = new double[2];
            double[] temperatures = {FIRST_KELVIN, SECOND_KELVIN};
            for (int t = 0; t < 2; t++) {
                double temperature = temperatures[t];
                double want = DeterministicMath.log(gas.henry.pascal(pack.water(), temperature) / HENRY_PRESSURE);
                DoubleUnaryOperator miss = e -> {
                    double[] phi = binary.logPhi(HENRY_ALPHA, e, e, temperature, HENRY_PRESSURE, 0.0);
                    return phi == null ? Double.NaN : phi[0] - want;
                };
                double low = HENRY_LOW, high = HENRY_HIGH;
                double lowMiss = miss.applyAsDouble(low), highMiss = miss.applyAsDouble(high);
                if (!(lowMiss * highMiss <= 0.0)) throw new Refused(gas.id, water.id, "no Henry energy in [" + low + ", " + high + "] J/mol at " + temperature + " K");
                for (int step = 0; step < HENRY_STEPS; step++) {
                    double middle = 0.5 * (low + high);
                    if (miss.applyAsDouble(low) * miss.applyAsDouble(middle) <= 0.0) high = middle; else low = middle;
                }
                out[t] = 0.5 * (low + high);
                if (!(Math.abs(miss.applyAsDouble(out[t])) < 1.0e-6)) throw new Refused(gas.id, water.id, "the Henry energy does not converge at " + temperature + " K");
            }
            return out;
        }

        /** The activity pair of {@code first} and {@code second} ({@code deriveShape} at both temperatures). */
        ActivityFit activity(Compound first, Compound second) {
            var binary = new Binary(first, second);
            var mixture = table.mixture(List.of(first.dortmund, second.dortmund), true);
            Shape low = shape(binary, mixture, FIRST_KELVIN, Double.NaN, first, second);
            // A compound above its critical temperature (or gas-like) at 373.15 K has no liquid to fit: constant energies.
            Shape high = first.liquidAtSecond && second.liquidAtSecond ? shape(binary, mixture, SECOND_KELVIN, low.alpha, first, second)
                    : new Shape(low.alpha, low.energies.clone(), Double.NaN, null);
            String notice = low.notice != null ? low.notice : high.notice;
            return new ActivityFit(low.alpha, new double[] {low.energies[0], low.energies[1]}, new double[] {high.energies[0], high.energies[1]},
                    low.rms, high.rms, notice);
        }

        private record Shape(double alpha, double[] energies, double rms, String notice) {}

        /**
         * Whether the group model can describe the pair: false, with a notice, when the table lacks a main-group pair the
         * two compounds need (the pair then takes the next source).
         */
        boolean groupModel(Compound a, Compound b, List<String> notices) {
            try {
                table.mixture(List.of(a.dortmund, b.dortmund), true);
                return true;
            } catch (IllegalArgumentException absent) {
                notices.add(a.id + " / " + b.id + ": " + absent.getMessage() + "; the pair takes the next source");
                return false;
            }
        }

        /**
         * {@code deriveShape}: alpha (unless {@code fixedAlpha} is given) and the energies {@code {dg(second around first),
         * dg(first around second)}} that minimise the squared misses of both ln gamma on {@link #GRID} at 2 MPa, by
         * Nelder-Mead from fixed starts; with the one-liquid penalty where the group model has one liquid. Refused when the
         * optimum carries a penalty or the derived pair keeps two liquids where the group model has one.
         */
        private Shape shape(Binary binary, ModifiedUnifac.Mixture mixture, double temperature, double fixedAlpha, Compound first, Compound second) {
            boolean fixed = !Double.isNaN(fixedAlpha);
            double[] pure = {binary.pureLogPhi(0, temperature, FIT_PRESSURE), binary.pureLogPhi(1, temperature, FIT_PRESSURE)};
            if (!Double.isFinite(pure[0]) || !Double.isFinite(pure[1])) throw new Refused(first.id, second.id, "no pure liquid root at " + temperature + " K");
            double[][] target = new double[GRID.length][];
            for (int k = 0; k < GRID.length; k++) target[k] = mixture.lnGamma(temperature, new double[] {GRID[k], 1.0 - GRID[k]});
            boolean oneLiquid = falls(x -> DeterministicMath.log(x) + mixture.lnGamma(temperature, new double[] {x, 1.0 - x})[0]) == 0.0;
            ToDoubleFunction<double[]> miss = v -> {
                double alpha = fixed ? fixedAlpha : v[2];
                if (!(alpha > ALPHA_LOW && alpha < ALPHA_HIGH)) return PENALTY;
                var model = binary.model(alpha, v[1], v[0]);
                double sum = 0.0;
                for (int k = 0; k < GRID.length; k++) {
                    double[] phi = model.logPhi(temperature, FIT_PRESSURE, GRID[k]);
                    if (phi == null) return PENALTY;
                    double d0 = phi[0] - pure[0] - target[k][0], d1 = phi[1] - pure[1] - target[k][1];
                    sum += d0 * d0 + d1 * d1;
                }
                if (oneLiquid) {
                    double drop = falls(x -> {
                        double[] phi = model.logPhi(temperature, FIT_PRESSURE, x);
                        return phi == null ? Double.NaN : DeterministicMath.log(x) + phi[0];
                    });
                    if (drop > 0.0) sum += 1.0 + 1000.0 * drop;
                }
                return Double.isFinite(sum) ? sum : PENALTY;
            };
            double[] start = {R * temperature * target[0][0], R * temperature * target[GRID.length - 1][1]};
            Point best = null;
            for (double alphaStart : fixed ? new double[] {fixedAlpha} : ALPHA_STARTS) {
                Point point = fixed ? nelderMead(miss, start, new double[] {ENERGY_STEP, ENERGY_STEP}, SIMPLEX_ITERATIONS)
                        : nelderMead(miss, new double[] {start[0], start[1], alphaStart}, new double[] {ENERGY_STEP, ENERGY_STEP, ALPHA_STEP}, SIMPLEX_ITERATIONS);
                if (best == null || point.f < best.f) best = point;
            }
            if (!(best.f < PENALTY) || !Double.isFinite(best.p[0]) || !Double.isFinite(best.p[1]))
                throw new Refused(first.id, second.id, "the activity fit at " + temperature + " K finds no root or no admissible alpha");
            double alpha = fixed ? fixedAlpha : best.p[2];
            var fitted = binary.model(alpha, best.p[1], best.p[0]);
            double drop = falls(x -> {
                double[] phi = fitted.logPhi(temperature, FIT_PRESSURE, x);
                return phi == null ? Double.NaN : DeterministicMath.log(x) + phi[0];
            });
            if (oneLiquid && drop > 0.0)
                throw new Refused(first.id, second.id, "the derived pair has two liquids at " + temperature + " K where the group model has one");
            String notice = !oneLiquid && drop == 0.0 ? "one liquid at " + temperature + " K where the group model has two" : null;
            return new Shape(alpha, new double[] {best.p[0], best.p[1]}, Math.sqrt(best.f / (2.0 * GRID.length)), notice);
        }
    }

    /**
     * How far {@code ln a_1 = act(x)} falls on the scan x = 0.01 to 0.99: the sum of each drop below the running maximum
     * (zero for one liquid). A point without a value counts as {@link #PENALTY}.
     */
    static double falls(DoubleUnaryOperator act) {
        double drop = 0.0, last = Double.NEGATIVE_INFINITY;
        for (int k = 1; k <= SCAN_POINTS; k++) {
            double value = act.applyAsDouble(k / 100.0);
            if (!Double.isFinite(value)) return PENALTY;
            if (value < last) drop += last - value;
            last = Math.max(last, value);
        }
        return drop;
    }

    /** A Nelder-Mead vertex. */
    private record Point(double[] p, double f) {}

    /**
     * Nelder-Mead as {@code nelderMead} of the reference script: reflection -1, expansion -2, contraction 0.5 towards the
     * worst vertex, shrink by half towards the best; a fixed number of iterations; ties keep their order (stable sort).
     */
    static Point nelderMead(ToDoubleFunction<double[]> fn, double[] start, double[] step, int iterations) {
        int n = start.length;
        var points = new ArrayList<Point>();
        points.add(new Point(start.clone(), fn.applyAsDouble(start.clone())));
        for (int i = 0; i < n; i++) {
            double[] p = start.clone();
            p[i] += step[i];
            points.add(new Point(p, fn.applyAsDouble(p.clone())));
        }
        Comparator<Point> order = Comparator.comparingDouble(Point::f);
        for (int iteration = 0; iteration < iterations; iteration++) {
            points.sort(order);
            double[] centre = new double[n];
            for (int j = 0; j < n; j++) {
                double sum = 0.0;
                for (int q = 0; q < n; q++) sum += points.get(q).p[j];
                centre[j] = sum / n;
            }
            Point worst = points.get(n);
            Point reflected = trial(fn, centre, worst, -1.0);
            if (reflected.f < points.get(0).f) {
                Point expanded = trial(fn, centre, worst, -2.0);
                points.set(n, expanded.f < reflected.f ? expanded : reflected);
            } else if (reflected.f < points.get(n - 1).f) {
                points.set(n, reflected);
            } else {
                Point contracted = trial(fn, centre, worst, 0.5);
                if (contracted.f < worst.f) points.set(n, contracted);
                else {
                    double[] best = points.get(0).p;
                    for (int k = 1; k <= n; k++) {
                        double[] p = new double[n];
                        for (int j = 0; j < n; j++) p[j] = 0.5 * (points.get(k).p[j] + best[j]);
                        points.set(k, new Point(p, fn.applyAsDouble(p.clone())));
                    }
                }
            }
        }
        points.sort(order);
        return points.get(0);
    }

    private static Point trial(ToDoubleFunction<double[]> fn, double[] centre, Point worst, double t) {
        double[] p = new double[centre.length];
        for (int j = 0; j < centre.length; j++) p[j] = centre[j] + t * (worst.p[j] - centre[j]);
        return new Point(p, fn.applyAsDouble(p.clone()));
    }

    /** Paraffinic counts of a cut: 2 CH3 and n - 2 CH2, n the carbons of the normal paraffin of its molar mass. */
    static Map<String, Integer> paraffinic(MaterialCatalog.Property property) {
        double grams = property.molecularWeight() * 1000.0;
        long carbons = Math.round((grams - END_GRAMS) / CH2_GRAMS);
        if (carbons < 2) throw new IllegalArgumentException("Derived pairs: cut " + property.id() + " of " + grams + " g/mol is lighter than ethane");
        var counts = new LinkedHashMap<String, Integer>();
        counts.put("CH3", 2);
        if (carbons > 2) counts.put("CH2", (int) (carbons - 2));
        return Map.copyOf(counts);
    }

    /** Two compounds on the kernel, for the fits: ln phi of both on the liquid root. */
    private static final class Binary {
        final double[] tc, pc, omega;
        final double[][] alpha;

        Binary(Compound a, Compound b) {
            tc = new double[] {a.tc, b.tc}; pc = new double[] {a.pc, b.pc}; omega = new double[] {a.omega, b.omega};
            alpha = new double[][] {a.alpha, b.alpha};
        }

        /** The pair with {@code alpha}, {@code dg01} (0 around 1) and {@code dg10} constant in temperature. */
        Model model(double nrtlAlpha, double dg01, double dg10) {
            var excess = HuronVidalExcess.builder(2).nrtl(0, 1, nrtlAlpha, FIRST_KELVIN, dg01, dg10, SECOND_KELVIN, dg01, dg10).build();
            return new Model(PengRobinsonKernel.huronVidal(tc, pc, omega, excess, alpha, 1.0, 3000.0, 1.0, 1.0e9));
        }

        /** ln phi of both compounds at the first's mole fraction {@code x}, or null without a root. */
        double[] logPhi(double nrtlAlpha, double dg01, double dg10, double temperature, double pressure, double x) {
            return model(nrtlAlpha, dg01, dg10).logPhi(temperature, pressure, x);
        }

        /** ln phi of pure compound {@code i} on its liquid root. */
        double pureLogPhi(int i, double temperature, double pressure) {
            double[] phi = model(0.0, 0.0, 0.0).logPhi(temperature, pressure, i == 0 ? 1.0 : 0.0);
            return phi == null ? Double.NaN : phi[i];
        }
    }

    /** One binary kernel with its own workspace. */
    private static final class Model {
        final PengRobinsonKernel kernel;
        final PengRobinsonKernel.Workspace workspace;
        final PengRobinsonKernel.Evaluation evaluation;
        final double[] composition = new double[2];

        Model(PengRobinsonKernel kernel) {
            this.kernel = kernel; this.workspace = kernel.newWorkspace(); this.evaluation = kernel.newEvaluation();
        }

        double[] logPhi(double temperature, double pressure, double x) {
            composition[0] = x;
            composition[1] = 1.0 - x;
            try {
                kernel.evaluate(temperature, pressure, composition, PengRobinsonKernel.Root.LIQUID, workspace, evaluation);
            } catch (IllegalStateException | IllegalArgumentException noRoot) {
                return null;
            }
            double[] out = evaluation.logFugacityCoefficients();
            return Double.isFinite(out[0]) && Double.isFinite(out[1]) ? out : null;
        }
    }
}
