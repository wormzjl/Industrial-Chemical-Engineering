package com.wormzjl.icheme.science.material;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wormzjl.icheme.science.math.DeterministicMath;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The modified UNIFAC (Dortmund) group model (batch 2026-11-11-aqueous-solvents, plan M3/M4, work package W2a): its table,
 * a {@code group_interactions} record of scheme {@value #SCHEME}, and the activity coefficients it gives a small mixture
 * from the compounds' group counts ({@code group_counts} of a dataset, {@link MaterialCatalog.GroupCounts}). The pairs
 * derived from it (W2b) read it once per data revision at load; nothing here runs per tick.
 *
 * <p>Record fields (each refusal names the field): {@code id}, {@code revision} (a revision token), {@code scheme}
 * ({@value #SCHEME}), {@code source}, {@code provenance} (an object of texts, not interpreted); {@code main_groups}
 * {@code {number, name}}; {@code subgroups} {@code {number, name, [label], main, r, q}} in number order, names unique
 * record keys ({@link #NAME}), r above 0, q not below 0; {@code pairs} {@code {first, second, a, b, c, a_reverse,
 * b_reverse, c_reverse, [reference]}} of two different main groups, each unordered pair at most once: a, b, c are those
 * of {@code Psi_nm = exp(-(a_nm + b_nm T + c_nm T^2)/T)} for n = first, m = second, the reverse ones for n = second,
 * m = first (a in K, b dimensionless, c in 1/K). A main-group pair absent from the list has no data: a mixture that
 * needs it is refused. {@code water_hydrocarbon_block} (optional): {@code water_main_group}, {@code hydrocarbon_main_groups},
 * {@code pairs} of the water main group with hydrocarbon main groups, {@code estimated}, {@code source}, {@code revision}.</p>
 *
 * <p>The block rule (plan M4): the table's water parameters serve water with polar compounds (alcohols); for a mixture of
 * water (a compound of the water main group alone) and compounds whose groups all lie in the hydrocarbon main groups (no
 * polar group), the block's pairs replace the table's for those main groups with water. The bundled block was fitted to
 * the mutual solubility of n-hexane and of benzene with water at 25 C ({@code tools/aqueous-solvents/group-derived-pairs.mjs}).</p>
 *
 * <p>The model, for compounds i with subgroup counts nu_ik (R_k, Q_k), r_i = sum nu_ik R_k, q_i = sum nu_ik Q_k:</p>
 * <pre>
 *   ln gamma_i = 1 - V'_i + ln V'_i - 5 q_i (1 - V_i/F_i + ln(V_i/F_i)) + sum_k nu_ik (ln Gamma_k - ln Gamma_k^(i))
 *   V_i = r_i / sum x_j r_j,  V'_i = r_i^(3/4) / sum x_j r_j^(3/4),  F_i = q_i / sum x_j q_j
 *   ln Gamma_k = Q_k (1 - ln sum_m theta_m Psi_mk - sum_m theta_m Psi_km / sum_n theta_n Psi_nm)
 * </pre>
 * <p>theta_m the area fraction of subgroup m, Psi = 1 within one main group, ln Gamma_k^(i) the same in pure i. Ported
 * from {@code dortmund} of {@code tools/aqueous-solvents/group-derived-pairs.mjs}; exp, log and pow through
 * {@link DeterministicMath}. Immutable.</p>
 */
public final class ModifiedUnifac {
    /** The value the record's {@code scheme} carries and the key of a dataset's counts in {@code group_counts}. */
    public static final String SCHEME = "modified-unifac-dortmund";
    /** A subgroup name: a record key of letters, digits and {@code ()[]{}=/._+-}, at most 32 characters. */
    static final String NAME = "[A-Za-z0-9(][A-Za-z0-9()\\[\\]{}=/._+-]{0,31}";
    private static final int MAXIMUM_GROUPS = 512;

    /** A subgroup: its number, its record name, its main group, volume R and area Q. */
    public record Subgroup(int number, String name, int mainGroup, double r, double q) {}

    /** a, b, c of {@code Psi_nm = exp(-(a + b T + c T^2)/T)} for one direction n to m. */
    public record Parameters(double a, double b, double c) {
        /** Psi at {@code temperature} K. */
        public double psi(double temperature) { return DeterministicMath.exp(-(a + temperature * (b + c * temperature)) / temperature); }
    }

    /** The water-hydrocarbon block: the water main group, the main groups of a compound without a polar group, the pairs. */
    public record Block(int waterMainGroup, Set<Integer> hydrocarbonMainGroups, Map<Long, Parameters> pairs, boolean estimated,
            String source, String revision) {
        public Block { hydrocarbonMainGroups = Set.copyOf(hydrocarbonMainGroups); pairs = Map.copyOf(pairs); }
    }

    private final String id;
    private final String revision;
    private final List<Subgroup> subgroups;
    private final Map<String, Subgroup> byName;
    private final Map<Integer, String> mainGroups;
    private final Map<Long, Parameters> pairs;
    private final Block block;

    private ModifiedUnifac(String id, String revision, List<Subgroup> subgroups, Map<Integer, String> mainGroups, Map<Long, Parameters> pairs, Block block) {
        this.id = id; this.revision = revision; this.subgroups = List.copyOf(subgroups); this.mainGroups = Collections.unmodifiableMap(new TreeMap<>(mainGroups));
        this.pairs = Map.copyOf(pairs); this.block = block;
        var names = new LinkedHashMap<String, Subgroup>();
        for (Subgroup s : this.subgroups) names.put(s.name(), s);
        byName = Collections.unmodifiableMap(names);
    }

    public String id() { return id; }
    public String revision() { return revision; }
    /** The subgroups in number order. */
    public List<Subgroup> subgroups() { return subgroups; }
    /** Main-group names by number. */
    public Map<Integer, String> mainGroups() { return mainGroups; }
    /** The number of main-group pairs with data. */
    public int pairCount() { return pairs.size() / 2; }
    /** The water-hydrocarbon block, empty when the record has none. */
    public Optional<Block> block() { return Optional.ofNullable(block); }
    /** The subgroup a name gives, empty when the table has none of that name. */
    public Optional<Subgroup> subgroup(String name) { return Optional.ofNullable(byName.get(name)); }
    /** The table's parameters for the direction n to m of two main groups, empty when the pair has no data. */
    public Optional<Parameters> parameters(int n, int m) { return Optional.ofNullable(pairs.get(key(n, m))); }

    private static long key(int n, int m) { return (long) n << 32 | (m & 0xffffffffL); }

    /**
     * The mixture of the compounds {@code counts} (subgroup name to count, one map per compound) with the block rule when
     * {@code waterHydrocarbonBlock} is true. Refused when a name is not a subgroup of the table, a count is not positive,
     * a compound has no area, or a main-group pair the mixture needs has no data.
     */
    public Mixture mixture(List<Map<String, Integer>> counts, boolean waterHydrocarbonBlock) {
        return new Mixture(counts, waterHydrocarbonBlock);
    }

    /**
     * A mixture of fixed compounds: ln gamma at any temperature and composition. Its arrays are made once; each
     * evaluation allocates its work arrays (it runs at load).
     */
    public final class Mixture {
        private final int compounds;
        private final Subgroup[] groups;
        private final double[][] nu;
        private final double[] r, q, r34;
        private final Parameters[][] interaction;
        private final boolean blockApplied;

        private Mixture(List<Map<String, Integer>> counts, boolean waterHydrocarbonBlock) {
            if (counts.isEmpty()) throw new IllegalArgumentException("modified UNIFAC: at least one compound required");
            compounds = counts.size();
            // Subgroups in the table's number order, so every sum runs in one order whatever map the counts come in.
            var present = new TreeMap<Integer, Subgroup>();
            for (int i = 0; i < compounds; i++) {
                if (counts.get(i).isEmpty()) throw new IllegalArgumentException("modified UNIFAC: compound " + i + " has no groups");
                for (var entry : counts.get(i).entrySet()) {
                    Subgroup s = byName.get(entry.getKey());
                    if (s == null) throw new IllegalArgumentException("modified UNIFAC: unknown subgroup " + entry.getKey() + " in " + id);
                    if (entry.getValue() == null || entry.getValue() < 1) throw new IllegalArgumentException("modified UNIFAC: count of " + entry.getKey() + " must be a positive integer");
                    present.putIfAbsent(s.number(), s);
                }
            }
            groups = present.values().toArray(Subgroup[]::new);
            nu = new double[compounds][groups.length]; r = new double[compounds]; q = new double[compounds]; r34 = new double[compounds];
            for (int i = 0; i < compounds; i++) {
                for (int k = 0; k < groups.length; k++) nu[i][k] = counts.get(i).getOrDefault(groups[k].name(), 0);
                for (int k = 0; k < groups.length; k++) { r[i] += nu[i][k] * groups[k].r(); q[i] += nu[i][k] * groups[k].q(); }
                if (!(q[i] > 0)) throw new IllegalArgumentException("modified UNIFAC: compound " + i + " has no surface area (q = " + q[i] + ")");
                r34[i] = DeterministicMath.pow(r[i], 0.75);
            }
            blockApplied = waterHydrocarbonBlock && block != null && blockHolds(counts);
            interaction = new Parameters[groups.length][groups.length];
            for (int n = 0; n < groups.length; n++) for (int m = 0; m < groups.length; m++) {
                int a = groups[n].mainGroup(), b = groups[m].mainGroup();
                if (a == b) continue;
                Parameters p = blockApplied ? block.pairs().get(key(a, b)) : null;
                if (p == null) p = pairs.get(key(a, b));
                if (p == null) throw new IllegalArgumentException("modified UNIFAC: no group pair " + mainGroups.get(a) + " (" + a + ") / "
                        + mainGroups.get(b) + " (" + b + ") in " + id);
                interaction[n][m] = p;
            }
        }

        /** Water and compounds of hydrocarbon main groups only, at least one of each. */
        private boolean blockHolds(List<Map<String, Integer>> counts) {
            boolean water = false, hydrocarbon = false;
            for (var compound : counts) {
                boolean allWater = true, allHydrocarbon = true;
                for (String name : compound.keySet()) {
                    int main = byName.get(name).mainGroup();
                    allWater &= main == block.waterMainGroup();
                    allHydrocarbon &= block.hydrocarbonMainGroups().contains(main);
                }
                if (allWater) water = true;
                else if (allHydrocarbon) hydrocarbon = true;
                else return false;
            }
            return water && hydrocarbon;
        }

        /** Whether the block's pairs replace the table's in this mixture. */
        public boolean blockApplied() { return blockApplied; }
        public int size() { return compounds; }

        /** ln gamma of each compound at {@code temperature} K and mole fractions {@code x} (zeros allowed: infinite dilution). */
        public double[] lnGamma(double temperature, double[] x) { return lnGamma(temperature, x, false); }

        /** As {@link #lnGamma(double, double[])}; {@code residualOnly} leaves out the combinatorial part. */
        public double[] lnGamma(double temperature, double[] x, boolean residualOnly) {
            if (!(temperature > 0) || !Double.isFinite(temperature)) throw new IllegalArgumentException("modified UNIFAC: temperature must be finite and positive, got " + temperature);
            if (x.length != compounds) throw new IllegalArgumentException("modified UNIFAC: expected " + compounds + " mole fractions, got " + x.length);
            double total = 0;
            for (double v : x) { if (!(v >= 0) || !Double.isFinite(v)) throw new IllegalArgumentException("modified UNIFAC: mole fractions must be finite and not negative"); total += v; }
            if (!(total > 0)) throw new IllegalArgumentException("modified UNIFAC: mole fractions sum to zero");
            int g = groups.length;
            double[][] psi = new double[g][g];
            for (int n = 0; n < g; n++) for (int m = 0; m < g; m++) psi[n][m] = interaction[n][m] == null ? 1.0 : interaction[n][m].psi(temperature);
            double[] mixture = groupResidual(x, psi);
            double sr = 0, sq = 0, s34 = 0;
            for (int i = 0; i < compounds; i++) { sr += x[i] * r[i]; sq += x[i] * q[i]; s34 += x[i] * r34[i]; }
            double[] out = new double[compounds], unit = new double[compounds];
            for (int i = 0; i < compounds; i++) {
                java.util.Arrays.fill(unit, 0.0); unit[i] = 1.0;
                double[] pure = groupResidual(unit, psi);
                double v = r[i] / sr, f = q[i] / sq, vp = r34[i] / s34;
                double combinatorial = residualOnly ? 0.0
                        : 1.0 - vp + DeterministicMath.log(vp) - 5.0 * q[i] * (1.0 - v / f + DeterministicMath.log(v / f));
                double residual = 0;
                for (int k = 0; k < g; k++) residual += nu[i][k] * (mixture[k] - pure[k]);
                out[i] = combinatorial + residual;
            }
            return out;
        }

        /** ln Gamma_k of every subgroup for compound amounts {@code amounts}. */
        private double[] groupResidual(double[] amounts, double[][] psi) {
            int g = groups.length;
            double[] theta = new double[g], denominator = new double[g], out = new double[g];
            double area = 0;
            for (int k = 0; k < g; k++) {
                double moles = 0;
                for (int i = 0; i < compounds; i++) moles += amounts[i] * nu[i][k];
                theta[k] = groups[k].q() * moles; area += theta[k];
            }
            for (int k = 0; k < g; k++) theta[k] /= area;
            for (int m = 0; m < g; m++) { double s = 0; for (int n = 0; n < g; n++) s += theta[n] * psi[n][m]; denominator[m] = s; }
            for (int k = 0; k < g; k++) {
                double s = 0;
                for (int m = 0; m < g; m++) s += theta[m] * psi[k][m] / denominator[m];
                out[k] = groups[k].q() * (1.0 - DeterministicMath.log(denominator[k]) - s);
            }
            return out;
        }
    }

    /** Reads a {@code group_interactions} record of scheme {@value #SCHEME}; a refusal names the field. */
    static ModifiedUnifac read(JsonObject o) {
        String id = JsonFields.text(o, "id");
        String revision = JsonFields.revision(o, "revision");
        JsonFields.text(o, "source");
        JsonFields.object(o, "provenance");
        String scheme = JsonFields.text(o, "scheme");
        if (!scheme.equals(SCHEME)) throw new IllegalArgumentException("scheme: expected " + SCHEME + ", got " + scheme);
        var mains = new HashMap<Integer, String>();
        var mainArray = JsonFields.array(o, "main_groups");
        for (int i = 0; i < mainArray.size(); i++) {
            JsonObject main = element(mainArray.get(i), "main_groups[" + i + "]");
            int number = positiveInteger(main, "number", "main_groups[" + i + "]");
            String name = JsonFields.text(main, "name");
            if (mains.put(number, name) != null) throw new IllegalArgumentException("main_groups[" + i + "].number: duplicate main group " + number);
        }
        var subgroupArray = JsonFields.array(o, "subgroups");
        if (subgroupArray.isEmpty() || subgroupArray.size() > MAXIMUM_GROUPS)
            throw new IllegalArgumentException("subgroups: expected 1 to " + MAXIMUM_GROUPS + " subgroups, got " + subgroupArray.size());
        var subgroups = new ArrayList<Subgroup>();
        var names = new HashSet<String>();
        for (int i = 0; i < subgroupArray.size(); i++) {
            String label = "subgroups[" + i + "]";
            JsonObject s = element(subgroupArray.get(i), label);
            int number = positiveInteger(s, "number", label);
            if (!subgroups.isEmpty() && number <= subgroups.getLast().number())
                throw new IllegalArgumentException(label + ".number: subgroups in number order, " + number + " after " + subgroups.getLast().number());
            String name = JsonFields.text(s, "name");
            if (!name.matches(NAME)) throw new IllegalArgumentException(label + ".name: expected a record key, got " + name);
            if (!names.add(name)) throw new IllegalArgumentException(label + ".name: duplicate subgroup " + name);
            if (s.has("label")) JsonFields.text(s, "label");
            int main = positiveInteger(s, "main", label);
            if (!mains.containsKey(main)) throw new IllegalArgumentException(label + ".main: unknown main group " + main);
            double r = JsonFields.number(s, "r"), q = JsonFields.number(s, "q");
            if (!(r > 0)) throw new IllegalArgumentException(label + ".r: must be positive");
            if (!(q >= 0)) throw new IllegalArgumentException(label + ".q: must not be negative");
            subgroups.add(new Subgroup(number, name, main, r, q));
        }
        var pairs = readPairs(JsonFields.array(o, "pairs"), "pairs", mains, null);
        Block block = null;
        if (o.has("water_hydrocarbon_block")) {
            JsonObject b = JsonFields.object(o, "water_hydrocarbon_block");
            try {
                int water = positiveInteger(b, "water_main_group", "water_main_group");
                if (!mains.containsKey(water)) throw new IllegalArgumentException("water_main_group: unknown main group " + water);
                var hydrocarbons = new HashSet<Integer>();
                for (var e : JsonFields.array(b, "hydrocarbon_main_groups")) {
                    if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber() || e.getAsDouble() != Math.rint(e.getAsDouble()))
                        throw new IllegalArgumentException("hydrocarbon_main_groups: expected main-group numbers");
                    int main = e.getAsInt();
                    if (!mains.containsKey(main) || main == water) throw new IllegalArgumentException("hydrocarbon_main_groups: invalid main group " + main);
                    if (!hydrocarbons.add(main)) throw new IllegalArgumentException("hydrocarbon_main_groups: duplicate " + main);
                }
                if (hydrocarbons.isEmpty()) throw new IllegalArgumentException("hydrocarbon_main_groups: at least one main group required");
                var blockPairs = readPairs(JsonFields.array(b, "pairs"), "pairs", mains, (first, second) -> {
                    int other = first == water ? second : second == water ? first : -1;
                    if (!hydrocarbons.contains(other))
                        throw new IllegalArgumentException("pairs: " + first + " / " + second + " is not water with a hydrocarbon main group");
                });
                block = new Block(water, hydrocarbons, blockPairs, JsonFields.bool(b, "estimated"), JsonFields.text(b, "source"), JsonFields.revision(b, "revision"));
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("water_hydrocarbon_block." + invalid.getMessage(), invalid);
            }
        }
        return new ModifiedUnifac(id, revision, subgroups, mains, pairs, block);
    }

    private interface PairCheck { void check(int first, int second); }

    private static Map<Long, Parameters> readPairs(com.google.gson.JsonArray array, String field, Map<Integer, String> mains, PairCheck check) {
        var pairs = new HashMap<Long, Parameters>();
        for (int i = 0; i < array.size(); i++) {
            String label = field + "[" + i + "]";
            JsonObject p = element(array.get(i), label);
            int first = positiveInteger(p, "first", label), second = positiveInteger(p, "second", label);
            if (!mains.containsKey(first)) throw new IllegalArgumentException(label + ".first: unknown main group " + first);
            if (!mains.containsKey(second)) throw new IllegalArgumentException(label + ".second: unknown main group " + second);
            if (first == second) throw new IllegalArgumentException(label + ": self pair " + first + " (Psi = 1 within a main group)");
            if (check != null) check.check(first, second);
            if (p.has("reference")) positiveInteger(p, "reference", label);
            var forward = new Parameters(number(p, "a", label), number(p, "b", label), number(p, "c", label));
            var reverse = new Parameters(number(p, "a_reverse", label), number(p, "b_reverse", label), number(p, "c_reverse", label));
            if (pairs.put(key(first, second), forward) != null || pairs.put(key(second, first), reverse) != null)
                throw new IllegalArgumentException(label + ": duplicate pair " + first + " / " + second);
        }
        return pairs;
    }

    private static JsonObject element(JsonElement e, String label) {
        if (!e.isJsonObject()) throw new IllegalArgumentException(label + ": expected object");
        return e.getAsJsonObject();
    }

    private static double number(JsonObject o, String key, String label) {
        try { return JsonFields.number(o, key); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException(label + "." + invalid.getMessage(), invalid); }
    }

    private static int positiveInteger(JsonObject o, String key, String label) {
        double value = number(o, key, label);
        if (value != Math.rint(value) || value < 1 || value > 100000) throw new IllegalArgumentException(label + "." + key + ": expected a positive integer");
        return (int) value;
    }

    @Override public String toString() { return "ModifiedUnifac[" + id + "@" + revision + ", " + subgroups.size() + " subgroups, " + pairCount() + " pairs]"; }
}
