package com.wormzjl.icheme.science.tutorial.cards;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The recorded runs of chapter 3 (batch 2026-11-12-tutorial-system, P4b), {@code still_trays.json}: chapter 2's still as a
 * pot still and with a column of a few trays at two reflux ratios, written offline from the still law by
 * {@code tools/tutorial-recordings/StillTrays}, held to the law by {@code StillTraysRecordingTest}. Each form's two cuts
 * (the first to the charge's gas and naphtha taken overhead, the second on to its kerosene): where they end and their
 * liquid's six parts by mass. Read once; no Minecraft type.
 */
public final class StillTraysData {
    public static final String PATH = "/assets/icheme/tutorial/recordings/still_trays.json";

    /** A cut: the overhead share it ends at, process s, pot K, minutes of play, its liquid (kg) and that liquid's parts' mass shares. */
    public record Cut(double overhead, double time, double pot, double playMinutes, double liquidKg, double[] parts) {
        public Cut {
            parts = parts.clone();
        }

        @Override
        public double[] parts() {
            return parts.clone();
        }

        public double part(int band) {
            return parts[band];
        }
    }

    /** A form: its name ({@code pot}, {@code trays_r1}, ...), the column's trays (0 for none), the reflux ratio, how the run ended, its cuts. */
    public record Form(String name, int stages, double reflux, String finish, List<Cut> cuts) {
        public Cut first() {
            return cuts.getFirst();
        }

        public Cut second() {
            return cuts.get(1);
        }
    }

    private static List<Form> loaded;

    private StillTraysData() {
    }

    /** The forms in the file's order: the pot still first, then the column at rising reflux. */
    public static synchronized List<Form> load() {
        if (loaded != null) return loaded;
        try (var in = StillTraysData.class.getResourceAsStream(PATH)) {
            var root = JsonParser.parseReader(new InputStreamReader(Objects.requireNonNull(in, PATH), StandardCharsets.UTF_8)).getAsJsonObject();
            var forms = new ArrayList<Form>();
            for (var e : root.getAsJsonArray("forms")) {
                var o = e.getAsJsonObject();
                var cuts = new ArrayList<Cut>();
                for (var c : o.getAsJsonArray("cuts")) cuts.add(cut(c.getAsJsonObject()));
                forms.add(new Form(o.get("name").getAsString(), o.get("stages").getAsInt(), o.get("reflux").getAsDouble(), o.get("finish").getAsString(), List.copyOf(cuts)));
            }
            loaded = List.copyOf(forms);
            return loaded;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The form named {@code name}. */
    public static Form form(String name) {
        return load().stream().filter(f -> f.name().equals(name)).findFirst().orElseThrow(() -> new IllegalArgumentException("No still form " + name));
    }

    /** The pot still (no column). */
    public static Form pot() {
        return form("pot");
    }

    /** The column forms, at rising reflux. */
    public static List<Form> columns() {
        return load().stream().filter(f -> f.stages() > 0).toList();
    }

    /** The trays of the column forms (one count for all of them). */
    public static int trays() {
        return columns().getFirst().stages();
    }

    private static Cut cut(JsonObject o) {
        var a = o.getAsJsonArray("parts");
        double[] parts = new double[a.size()];
        for (int i = 0; i < parts.length; i++) parts[i] = a.get(i).getAsDouble();
        return new Cut(o.get("overhead").getAsDouble(), o.get("time").getAsDouble(), o.get("pot_kelvin").getAsDouble(), o.get("play_minutes").getAsDouble(),
                o.get("liquid_kg").getAsDouble(), parts);
    }
}
