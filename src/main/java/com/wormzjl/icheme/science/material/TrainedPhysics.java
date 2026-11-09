package com.wormzjl.icheme.science.material;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wormzjl.icheme.science.math.DeterministicMath;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The phase physics a neural model was trained on, carried with the model as values (sidecar schema 4; batch
 * 2026-11-04-open-thermo-data, O7, owner decision E8): no record id or revision takes part. A request binds the model when
 * its own values on its active compounds are the trained ones within the tolerances of decision E9
 * ({@link #compare}): each scalar within 1 % relative, each function of temperature within 1 % on a grid over the model's
 * trained temperature range, each binary pair within 0.001 absolute.
 *
 * <p>What is compared is what the model's prediction computes with (the native anchor's PR78 flashes and enthalpies, the
 * features and the decoder): each compound's PR78 constants (Tc, Pc, omega) and its ideal-gas heat capacity as the column
 * evaluates its {@code shifted_polynomial_5} about 298.15 K; the constant pairs among the active compounds; the water's
 * saturation pressure (Wagner-Pruss), latent heat (Watson) below its critical point, its vapour heat capacity and vapour
 * enthalpy (the Shomate form, whose datum enters the steam features). Left out, because no step of the prediction reads
 * them: molar mass, normal boiling point, standard liquid density and its reference state (read only by the assay
 * conversion before the input exists, and by the tray hydraulics and product streams after the solve), the
 * {@code estimated} flag, the datasets' and the water's range bounds (the lens keeps the request's own column range and
 * water), the volume translation (fluid network only), and every transport property (the column reads none).</p>
 */
public record TrainedPhysics(String model, double minimumTemperature, double maximumTemperature, List<Compound> compounds,
        List<Pair> pairs, Water water) {
    /** Decision E9: a scalar or a function value within 1 % relative. */
    public static final double RELATIVE_TOLERANCE = 0.01;
    /** Decision E9: a binary pair parameter within 0.001 absolute (1 % of a 0.1 pair scale). */
    public static final double PAIR_TOLERANCE = 0.001;
    /** Intervals of the temperature grid a function is compared on (201 points over the trained range). */
    public static final int GRID_INTERVALS = 200;

    /** A compound's trained PR78 constants and ideal-gas heat capacity ({@code shifted_polynomial_5} about 298.15 K, J/(mol K)). */
    public record Compound(String id, double criticalTemperature, double criticalPressure, double acentricFactor, List<Double> idealGasCp) {
        public Compound {
            Objects.requireNonNull(id); idealGasCp = List.copyOf(idealGasCp);
            if (!id.matches("[A-Za-z][A-Za-z0-9_.:-]{0,127}")) throw new IllegalArgumentException("physics: invalid compound id " + id);
            if (!(criticalTemperature > 0) || !(criticalPressure > 0) || !Double.isFinite(criticalTemperature) || !Double.isFinite(criticalPressure)
                    || !Double.isFinite(acentricFactor) || idealGasCp.size() != 6 || idealGasCp.stream().anyMatch(c -> !Double.isFinite(c)))
                throw new IllegalArgumentException("physics: invalid values of " + id);
        }
        /** The heat capacity as the column evaluates the record ({@code V3PropertyComponent}), J/(mol K). */
        public double heatCapacity(double temperature) { return polynomial(idealGasCp, temperature); }
    }
    /** A constant pair; every pair not listed is zero. */
    public record Pair(String first, String second, double kij) {
        public Pair {
            Objects.requireNonNull(first); Objects.requireNonNull(second);
            if (first.equals(second) || !Double.isFinite(kij)) throw new IllegalArgumentException("physics: invalid pair " + first + " / " + second);
        }
    }
    /**
     * The water model's values the column computes with ({@code V3WaterProperties}): the Wagner-Pruss saturation
     * coefficients with the critical point, the Watson latent heat and the vapour's Shomate coefficients (A..E, then the two
     * constants of its enthalpy datum).
     */
    public record Water(double criticalTemperature, double criticalPressure, List<Double> saturation, double boilingTemperature,
            double vaporizationEnthalpy, double watsonExponent, List<Double> shomate) {
        public Water {
            saturation = List.copyOf(saturation); shomate = List.copyOf(shomate);
            if (!(criticalTemperature > 0) || !(criticalPressure > 0) || !(boilingTemperature > 0) || !(boilingTemperature < criticalTemperature)
                    || !(vaporizationEnthalpy > 0) || !(watsonExponent > 0) || !Double.isFinite(criticalTemperature) || !Double.isFinite(criticalPressure)
                    || !Double.isFinite(vaporizationEnthalpy) || !Double.isFinite(watsonExponent)
                    || saturation.size() != 6 || shomate.size() != 7 || saturation.stream().anyMatch(c -> !Double.isFinite(c))
                    || shomate.stream().anyMatch(c -> !Double.isFinite(c)))
                throw new IllegalArgumentException("physics: invalid water values");
        }
        /** The values of a catalog water model. */
        public static Water of(MaterialCatalog.Water w) {
            return new Water(w.criticalTemperature(), w.criticalPressure(), w.saturation(), w.boilingTemperature(), w.vaporizationEnthalpy(),
                    w.watsonExponent(), w.shomate());
        }
        /** Wagner-Pruss saturation pressure, Pa, below the critical point. */
        public double saturationPressure(double t) {
            double theta = 1 - t / criticalTemperature, root = Math.sqrt(theta), cube = theta * theta * theta;
            double sum = saturation.get(0) * theta + saturation.get(1) * theta * root + saturation.get(2) * cube + saturation.get(3) * cube * root
                    + saturation.get(4) * cube * theta + saturation.get(5) * cube * cube * theta * root;
            return criticalPressure * DeterministicMath.exp(criticalTemperature / t * sum);
        }
        /** Watson latent heat, J/mol, zero at and above the critical point. */
        public double vaporizationEnthalpy(double t) {
            if (t >= criticalTemperature) return 0;
            return vaporizationEnthalpy * DeterministicMath.pow((1 - t / criticalTemperature) / (1 - boilingTemperature / criticalTemperature), watsonExponent);
        }
        /** Vapour heat capacity, J/(mol K). */
        public double vaporHeatCapacity(double t) {
            double x = t / 1000;
            return shomate.get(0) + shomate.get(1) * x + shomate.get(2) * x * x + shomate.get(3) * x * x * x + shomate.get(4) / (x * x);
        }
        /** Vapour enthalpy on the record's datum, J/mol. */
        public double vaporEnthalpy(double t) {
            double x = t / 1000;
            return 1000 * (shomate.get(0) * x + shomate.get(1) * x * x / 2 + shomate.get(2) * x * x * x / 3 + shomate.get(3) * x * x * x * x / 4
                    - shomate.get(4) / x + shomate.get(5) + shomate.get(6));
        }
    }
    /**
     * One compared quantity: its subject (a compound, a pair or {@code Water}), what was compared, the largest deviation on
     * it (relative, or absolute for a pair) and the limit it is held to.
     */
    public record Deviation(String subject, String quantity, double value, double limit) {
        public boolean within() { return value <= limit; }
    }

    public TrainedPhysics {
        Objects.requireNonNull(model); Objects.requireNonNull(water); compounds = List.copyOf(compounds); pairs = List.copyOf(pairs);
        if (!model.equals("pr78")) throw new IllegalArgumentException("physics: unsupported model " + model);
        if (!(minimumTemperature > 0) || !(maximumTemperature > minimumTemperature) || !Double.isFinite(maximumTemperature))
            throw new IllegalArgumentException("physics: invalid trained temperature range");
        if (compounds.isEmpty()) throw new IllegalArgumentException("physics: no compounds");
        var ids = compounds.stream().map(Compound::id).toList();
        if (Set.copyOf(ids).size() != ids.size()) throw new IllegalArgumentException("physics: duplicate compound");
        var seen = new HashSet<String>();
        for (var pair : pairs) if (!ids.contains(pair.first()) || !ids.contains(pair.second()) || !seen.add(key(pair.first(), pair.second())))
            throw new IllegalArgumentException("physics: pair " + pair.first() + " / " + pair.second() + " is outside the compounds or repeated");
    }

    /** The compound ids, in the model's order. */
    public List<String> components() { return compounds.stream().map(Compound::id).toList(); }

    /** The trained constant pair of two compounds: the listed value, else zero. */
    public double kij(String a, String b) {
        for (var pair : pairs) if (pair.first().equals(a) && pair.second().equals(b) || pair.first().equals(b) && pair.second().equals(a)) return pair.kij();
        return 0;
    }

    /** The trained temperature grid, {@link #GRID_INTERVALS} equal steps from the minimum to the maximum. */
    public double[] grid() {
        double[] t = new double[GRID_INTERVALS + 1];
        for (int i = 0; i <= GRID_INTERVALS; i++) t[i] = minimumTemperature + (maximumTemperature - minimumTemperature) * i / GRID_INTERVALS;
        return t;
    }

    /**
     * Every compared quantity of package {@code request} on the compounds {@code active} (each of which this physics and the
     * package hold), against the trained values. A compound without PR78 constants or an ideal-gas fit in the package
     * deviates infinitely.
     */
    public List<Deviation> compare(MaterialCatalog.Package request, List<String> active) {
        var out = new ArrayList<Deviation>(); double[] grid = grid();
        var trained = new HashMap<String, Compound>(); for (var c : compounds) trained.put(c.id(), c);
        for (String id : active) {
            var t = Objects.requireNonNull(trained.get(id), id);
            int index = request.components().indexOf(id);
            if (index < 0) throw new IllegalArgumentException("Compound outside package: " + id);
            var p = request.properties().get(index);
            if (p.pr() == null || p.cp().size() != 6) { out.add(new Deviation(id, "pr78 constants and ideal-gas fit", Double.POSITIVE_INFINITY, RELATIVE_TOLERANCE)); continue; }
            out.add(new Deviation(id, "critical temperature", relative(p.pr().criticalTemperature(), t.criticalTemperature()), RELATIVE_TOLERANCE));
            out.add(new Deviation(id, "critical pressure", relative(p.pr().criticalPressure(), t.criticalPressure()), RELATIVE_TOLERANCE));
            out.add(new Deviation(id, "acentric factor", relative(p.pr().acentricFactor(), t.acentricFactor()), RELATIVE_TOLERANCE));
            double cp = 0; for (double temperature : grid) cp = worst(cp, relative(polynomial(p.cp(), temperature), t.heatCapacity(temperature)));
            out.add(new Deviation(id, "ideal-gas heat capacity", cp, RELATIVE_TOLERANCE));
        }
        for (int a = 0; a < active.size(); a++) for (int b = a + 1; b < active.size(); b++) {
            int i = request.components().indexOf(active.get(a)), j = request.components().indexOf(active.get(b));
            out.add(new Deviation(active.get(a) + " / " + active.get(b), "kij",
                    Math.abs(request.interactions().get(i).get(j) - kij(active.get(a), active.get(b))), PAIR_TOLERANCE));
        }
        var w = Water.of(request.water());
        double saturation = 0, latent = 0, vaporCp = 0, enthalpy = 0, scale = 0;
        for (double temperature : grid) scale = Math.max(scale, Math.abs(water.vaporEnthalpy(temperature)));
        for (double temperature : grid) {
            if (temperature < Math.min(water.criticalTemperature(), w.criticalTemperature())) {
                saturation = worst(saturation, relative(w.saturationPressure(temperature), water.saturationPressure(temperature)));
                latent = worst(latent, relative(w.vaporizationEnthalpy(temperature), water.vaporizationEnthalpy(temperature)));
            }
            vaporCp = worst(vaporCp, relative(w.vaporHeatCapacity(temperature), water.vaporHeatCapacity(temperature)));
            enthalpy = worst(enthalpy, Math.abs(w.vaporEnthalpy(temperature) - water.vaporEnthalpy(temperature)) / scale);
        }
        out.add(new Deviation("Water", "saturation pressure", saturation, RELATIVE_TOLERANCE));
        out.add(new Deviation("Water", "latent heat", latent, RELATIVE_TOLERANCE));
        out.add(new Deviation("Water", "vapour heat capacity", vaporCp, RELATIVE_TOLERANCE));
        // The enthalpy carries a datum and crosses zero near 298.15 K: its deviation is relative to its largest magnitude on the grid.
        out.add(new Deviation("Water", "vapour enthalpy", enthalpy, RELATIVE_TOLERANCE));
        return out;
    }

    /** Whether every compared quantity of {@code request} on {@code active} is within its limit ({@link #compare}). */
    public boolean matches(MaterialCatalog.Package request, List<String> active) {
        return compare(request, active).stream().allMatch(Deviation::within);
    }

    /**
     * Reads the sidecar's {@code physics} object (schema 4): {@code model}, {@code source} (text, not compared),
     * {@code temperatureMinimumKelvin}, {@code temperatureMaximumKelvin}, {@code compounds} (each {@code id},
     * {@code criticalTemperatureKelvin}, {@code criticalPressurePascal}, {@code acentricFactor}, {@code idealGasCp}),
     * {@code pairs} (each {@code first}, {@code second}, {@code kij}) and {@code water}. Every field is required and no
     * other is accepted.
     */
    public static TrainedPhysics read(JsonObject o) {
        fields(o, "physics", "model", "source", "temperatureMinimumKelvin", "temperatureMaximumKelvin", "compounds", "pairs", "water");
        text(o, "source");
        var compounds = new ArrayList<Compound>();
        for (var e : array(o, "compounds")) {
            var c = object(e, "compound");
            fields(c, "physics compound", "id", "criticalTemperatureKelvin", "criticalPressurePascal", "acentricFactor", "idealGasCp");
            compounds.add(new Compound(text(c, "id"), number(c, "criticalTemperatureKelvin"), number(c, "criticalPressurePascal"),
                    number(c, "acentricFactor"), numbers(c, "idealGasCp")));
        }
        var pairs = new ArrayList<Pair>();
        for (var e : array(o, "pairs")) {
            var p = object(e, "pair"); fields(p, "physics pair", "first", "second", "kij");
            pairs.add(new Pair(text(p, "first"), text(p, "second"), number(p, "kij")));
        }
        var w = object(o.get("water"), "water");
        fields(w, "physics water", "criticalTemperatureKelvin", "criticalPressurePascal", "saturationCoefficients", "boilingTemperatureKelvin",
                "vaporizationEnthalpyJoulesPerMol", "watsonExponent", "shomateCoefficients");
        var water = new Water(number(w, "criticalTemperatureKelvin"), number(w, "criticalPressurePascal"), numbers(w, "saturationCoefficients"),
                number(w, "boilingTemperatureKelvin"), number(w, "vaporizationEnthalpyJoulesPerMol"), number(w, "watsonExponent"), numbers(w, "shomateCoefficients"));
        return new TrainedPhysics(text(o, "model"), number(o, "temperatureMinimumKelvin"), number(o, "temperatureMaximumKelvin"),
                compounds, pairs, water);
    }

    static double polynomial(List<Double> c, double temperature) {
        double d = temperature - 298.15;
        return c.get(0) + c.get(1) * d + c.get(2) * d * d + c.get(3) * d * d * d + d * d * d * d * (c.get(4) + c.get(5) * d);
    }
    /** |a / b - 1|; zero for two zeros, infinite against a zero. */
    private static double relative(double a, double b) {
        if (a == b) return 0;
        return b == 0 ? Double.POSITIVE_INFINITY : Math.abs(a / b - 1);
    }
    /** The larger, a NaN counting as infinite. */
    private static double worst(double a, double b) { return Double.isNaN(b) ? Double.POSITIVE_INFINITY : Math.max(a, b); }
    private static String key(String a, String b) { return a.compareTo(b) < 0 ? a + "\u0000" + b : b + "\u0000" + a; }
    private static void fields(JsonObject o, String what, String... names) {
        if (o == null || !o.keySet().equals(Set.of(names))) throw new IllegalArgumentException("Invalid " + what + " fields: expected " + List.of(names));
    }
    private static String text(JsonObject o, String name) {
        JsonElement e = o.get(name);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("physics: " + name + " must be a string");
        return e.getAsString();
    }
    private static JsonObject object(JsonElement e, String what) {
        if (e == null || !e.isJsonObject()) throw new IllegalArgumentException("physics: " + what + " must be an object");
        return e.getAsJsonObject();
    }
    private static JsonArray array(JsonObject o, String name) {
        var e = o.get(name); if (e == null || !e.isJsonArray()) throw new IllegalArgumentException("physics: " + name + " must be an array");
        return e.getAsJsonArray();
    }
    private static double number(JsonObject o, String name) {
        JsonElement e = o.get(name);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("physics: " + name + " must be a number");
        return e.getAsDouble();
    }
    private static List<Double> numbers(JsonObject o, String name) {
        var out = new ArrayList<Double>();
        for (var e : array(o, name)) {
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("physics: " + name + " must hold numbers");
            out.add(e.getAsDouble());
        }
        return out;
    }
}
