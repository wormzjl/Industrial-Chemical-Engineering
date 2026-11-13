package com.wormzjl.icheme.science.tutorial.cards;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The crude barrels the cards barrel and products draw (batch 2026-11-12-tutorial-system, P2a), recorded from the mod's
 * material data by {@code tools/tutorial-recordings/CrudeBarrel.java} into {@link #PATH} and read once when a card is
 * built. Per grade: its components in boiling order (mass share, colour, the range they boil over), the six bands'
 * shares, the share boiled off below every 5 K from 250 K to 900 K, and its sulfur. The rules: an assay's amounts are by
 * mass; a pure compound boils at its normal boiling point; a petroleum fraction boils evenly over its cut (one with no
 * upper bound boils above its lower one: in the curve it never boils below 900 K, in the bands it lies in the last).
 */
public final class BarrelData {
    public static final String PATH = "/assets/icheme/tutorial/recordings/crude_barrel.json";
    /** The grades, in the card's order: medium first. */
    public static final List<String> GRADES = List.of("medium_crude", "light_crude", "heavy_crude", "oil_sand_dilbit");
    /** The bands' names (the glossary's terms) and their edges, K: gas below 20 °C, naphtha to 180, kerosene to 240, diesel to 360, gas oil to 565, residue above. */
    public static final List<String> BANDS = List.of("gas", "naphtha", "kerosene", "diesel", "gas_oil", "residue");
    public static final double[] EDGES = {293.15, 453.15, 513.15, 633.15, 838.15};
    /** The curve's first point, its spacing and its last point, K. */
    public static final double CURVE_FROM = 250, CURVE_STEP = 5, CURVE_TO = 900;

    /** One component of a barrel: its mass share, its colour (ARGB), and the range it boils over (K; upper NaN: open above). */
    public record Segment(String component, double share, int colour, double lower, double upper) {
        /** The part of this segment's mass that boils below {@code kelvin}. */
        public double below(double kelvin) {
            if (Double.isNaN(upper)) return 0;
            if (upper <= lower) return kelvin > lower ? 1 : 0;
            return Math.clamp((kelvin - lower) / (upper - lower), 0, 1);
        }

        /** The part of this segment's mass that boils in band {@code band}. */
        public double in(int band) {
            if (Double.isNaN(upper)) {
                if (lower < EDGES[EDGES.length - 1]) throw new IllegalStateException(component + ": open above but starts below the last band");
                return band == BANDS.size() - 1 ? 1 : 0;
            }
            double lo = band == 0 ? Double.NEGATIVE_INFINITY : EDGES[band - 1], hi = band == EDGES.length ? Double.POSITIVE_INFINITY : EDGES[band];
            if (upper <= lower) return lower >= lo && lower < hi ? 1 : 0;
            double a = Math.max(lo, lower), b = Math.min(hi, upper);
            return b > a ? (b - a) / (upper - lower) : 0;
        }
    }

    /** A grade's barrel: its id, its sulfur (mass %), its segments in boiling order, its bands' shares and its curve. */
    public record Grade(String id, double sulfur, List<Segment> segments, double[] bands, double[] curve) {
        public Grade {
            segments = List.copyOf(segments);
            bands = bands.clone();
            curve = curve.clone();
        }

        /** The share boiled off below {@code kelvin}, between the curve's points. */
        public double boiled(double kelvin) {
            double at = (kelvin - CURVE_FROM) / CURVE_STEP;
            if (at <= 0) return curve[0];
            if (at >= curve.length - 1) return curve[curve.length - 1];
            int i = (int) Math.floor(at);
            return curve[i] + (curve[i + 1] - curve[i]) * (at - i);
        }

        @Override
        public double[] bands() {
            return bands.clone();
        }

        @Override
        public double[] curve() {
            return curve.clone();
        }
    }

    private BarrelData() {
    }

    /** The curve's points: 250 K to 900 K every 5 K. */
    public static int points() {
        return (int) Math.round((CURVE_TO - CURVE_FROM) / CURVE_STEP) + 1;
    }

    /**
     * The barrel of {@code assay}, its components' records by id from {@code component} and their property records by
     * reference ({@code icheme:<name>}) from {@code properties}.
     */
    public static Grade compute(String id, JsonObject assay, Function<String, JsonObject> component, Function<String, JsonObject> properties) {
        if (!"mass".equals(assay.get("basis").getAsString())) throw new IllegalArgumentException(id + ": not by mass");
        var amounts = assay.getAsJsonObject("amounts_by_component");
        double total = 0;
        for (var e : amounts.entrySet()) total += e.getValue().getAsDouble();
        var segments = new ArrayList<Segment>();
        for (var e : amounts.entrySet()) {
            var record = component.apply(e.getKey());
            if (record == null) throw new IllegalArgumentException(id + ": no component " + e.getKey());
            int colour = 0xFF000000 | Integer.parseInt(record.getAsJsonObject("appearance").get("color").getAsString().substring(1), 16);
            double lower, upper;
            if ("petroleum_fraction".equals(record.get("kind").getAsString())) {
                var cut = record.getAsJsonObject("cut");
                upper = cut.has("upper_kelvin") && !cut.get("upper_kelvin").isJsonNull() ? cut.get("upper_kelvin").getAsDouble() : Double.NaN;
                if (cut.has("lower_kelvin") && !cut.get("lower_kelvin").isJsonNull()) lower = cut.get("lower_kelvin").getAsDouble();
                else {
                    // The lightest cut names no lower edge: its edges are midpoints between neighbouring normal boiling
                    // points, so it is taken centred on its own one (crude_pc01: 305 K, between n-pentane and n-hexane).
                    var props = properties.apply(record.get("properties").getAsString());
                    if (props == null || Double.isNaN(upper)) throw new IllegalArgumentException(id + ": " + e.getKey() + " has no cut edges");
                    lower = 2 * props.get("normal_boiling_point_kelvin").getAsDouble() - upper;
                }
            } else {
                var props = properties.apply(record.get("properties").getAsString());
                if (props == null) throw new IllegalArgumentException(id + ": no properties for " + e.getKey());
                lower = upper = props.get("normal_boiling_point_kelvin").getAsDouble();
            }
            segments.add(new Segment(e.getKey(), e.getValue().getAsDouble() / total, colour, lower, upper));
        }
        segments.sort(Comparator.comparingDouble(Segment::lower).thenComparingDouble(s -> Double.isNaN(s.upper()) ? Double.MAX_VALUE : s.upper()));
        double[] bands = new double[BANDS.size()];
        for (var s : segments) for (int b = 0; b < bands.length; b++) bands[b] += s.share() * s.in(b);
        double[] curve = new double[points()];
        for (int i = 0; i < curve.length; i++) {
            double t = CURVE_FROM + i * CURVE_STEP;
            for (var s : segments) curve[i] += s.share() * s.below(t);
        }
        double sulfur = assay.getAsJsonObject("provenance").getAsJsonObject("specification").get("sulfur_mass_percent").getAsDouble();
        return new Grade(id, sulfur, segments, bands, curve);
    }

    /** Every grade of {@link #GRADES} computed from the material folder {@code materials} ({@code data/icheme/materials}): the recording tool's and the gate's source. */
    public static List<Grade> compute(java.nio.file.Path materials) throws IOException {
        var components = new java.util.HashMap<String, JsonObject>();
        try (var files = java.nio.file.Files.list(materials.resolve("components"))) {
            for (var file : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
                var o = JsonParser.parseString(java.nio.file.Files.readString(file)).getAsJsonObject();
                components.put(o.get("id").getAsString(), o);
            }
        }
        Function<String, JsonObject> properties = ref -> {
            var file = materials.resolve("properties").resolve(ref.substring(ref.indexOf(':') + 1) + ".json");
            try {
                return java.nio.file.Files.exists(file) ? JsonParser.parseString(java.nio.file.Files.readString(file)).getAsJsonObject() : null;
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        };
        var out = new ArrayList<Grade>();
        for (var id : GRADES) {
            var assay = JsonParser.parseString(java.nio.file.Files.readString(materials.resolve("assays").resolve(id + ".json"))).getAsJsonObject();
            out.add(compute(id, assay, components::get, properties));
        }
        return out;
    }

    /** {@code grades} as the recording's JSON. */
    public static String toJson(List<Grade> grades) {
        var root = new JsonObject();
        root.addProperty("schema_version", 1);
        root.addProperty("source", "tools/tutorial-recordings/CrudeBarrel.java from data/icheme/materials (assays, components, properties)");
        var list = new JsonArray();
        for (var g : grades) {
            var o = new JsonObject();
            o.addProperty("id", g.id());
            o.addProperty("sulfur_mass_percent", g.sulfur());
            var segments = new JsonArray();
            for (var s : g.segments()) {
                var so = new JsonObject();
                so.addProperty("component", s.component());
                so.addProperty("share", s.share());
                so.addProperty("color", String.format(java.util.Locale.ROOT, "#%06X", s.colour() & 0xFFFFFF));
                so.addProperty("lower_kelvin", s.lower());
                if (Double.isNaN(s.upper())) so.add("upper_kelvin", JsonNull.INSTANCE);
                else so.addProperty("upper_kelvin", s.upper());
                segments.add(so);
            }
            o.add("segments", segments);
            o.add("bands", array(g.bands()));
            o.add("curve", array(g.curve()));
            list.add(o);
        }
        root.add("grades", list);
        return new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(root) + "\n";
    }

    private static JsonArray array(double[] values) {
        var a = new JsonArray();
        for (double v : values) a.add(v);
        return a;
    }

    /** The recording read from {@code in}, in its own order. */
    public static List<Grade> read(Supplier<InputStream> in) {
        try (var stream = in.get()) {
            if (stream == null) throw new IllegalStateException("No " + PATH);
            var root = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            var out = new ArrayList<Grade>();
            for (var element : root.getAsJsonArray("grades")) {
                var o = element.getAsJsonObject();
                var segments = new ArrayList<Segment>();
                for (var se : o.getAsJsonArray("segments")) {
                    var so = se.getAsJsonObject();
                    JsonElement up = so.get("upper_kelvin");
                    segments.add(new Segment(so.get("component").getAsString(), so.get("share").getAsDouble(),
                            0xFF000000 | Integer.parseInt(so.get("color").getAsString().substring(1), 16), so.get("lower_kelvin").getAsDouble(),
                            up == null || up.isJsonNull() ? Double.NaN : up.getAsDouble()));
                }
                out.add(new Grade(o.get("id").getAsString(), o.get("sulfur_mass_percent").getAsDouble(), segments, doubles(o.getAsJsonArray("bands")),
                        doubles(o.getAsJsonArray("curve"))));
            }
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("Unreadable " + PATH, e);
        }
    }

    private static double[] doubles(JsonArray a) {
        double[] out = new double[a.size()];
        for (int i = 0; i < out.length; i++) out[i] = a.get(i).getAsDouble();
        return out;
    }

    private static List<Grade> loaded;

    /** The recording on the class path (the mod's jar, the tests' and the render tool's), read once. */
    public static synchronized List<Grade> load() {
        if (loaded == null) loaded = read(() -> BarrelData.class.getResourceAsStream(PATH));
        return loaded;
    }

    /** {@code shares} in whole percents that add to 100: each rounded down, the remainder to the largest fractions. */
    public static int[] percents(double[] shares) {
        double total = 0;
        for (double s : shares) total += s;
        int[] out = new int[shares.length];
        double[] rest = new double[shares.length];
        int sum = 0;
        for (int i = 0; i < shares.length; i++) {
            double p = total > 0 ? shares[i] / total * 100 : 0;
            out[i] = (int) Math.floor(p);
            rest[i] = p - out[i];
            sum += out[i];
        }
        for (int left = 100 - sum; left > 0; left--) {
            int best = 0;
            for (int i = 1; i < rest.length; i++) if (rest[i] > rest[best]) best = i;
            out[best]++;
            rest[best] = -1;
        }
        return out;
    }
}
