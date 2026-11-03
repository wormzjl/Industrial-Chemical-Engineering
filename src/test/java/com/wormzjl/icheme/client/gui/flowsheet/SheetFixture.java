package com.wormzjl.icheme.client.gui.flowsheet;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wormzjl.icheme.client.gui.common.HeatControlColumn;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetSolver;
import com.wormzjl.icheme.science.flowsheet.Params;
import com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** The simulator gates' sheet, measure and texts: no Minecraft class, as the screen's own builders need none. */
final class SheetFixture {
    static final Measure M = Measure.fixed(6);
    /** What a Chinese line takes in the game's font: 9 px a glyph, a Latin character as {@link #M}. */
    static final Measure CHINESE = text -> text.codePoints().map(c -> c >= 0x2E80 ? 9 : 6).sum();
    static final MaterialCatalog CATALOG = MaterialCatalog.bundled();
    static final String NETWORK = CATALOG.presets().network().packageId();
    static final StreamProps.Fluids FLUIDS = new StreamProps.Fluids(MaterialRuntime.with(CATALOG, NETWORK, () -> FluidThermodynamics.forNetwork(CATALOG, NETWORK)), CATALOG.requirePackage(NETWORK),
            id -> CATALOG.name(id).english(), "Crude oil, methane, nitrogen");

    private SheetFixture() {
    }

    /** Every key asked, every key the language file lacks; a line reads as the game shows it. */
    static final class Texts implements HeatControlColumn.Texts {
        private static final JsonObject ENGLISH = language("en_us");
        final Set<String> asked = new TreeSet<>(), missing = new TreeSet<>(), inEnglish = new TreeSet<>();
        final Measure measure;
        private final JsonObject lines;

        Texts() {
            this(ENGLISH, M);
        }

        private Texts(JsonObject lines, Measure measure) {
            this.lines = lines;
            this.measure = measure;
        }

        static Texts chinese() {
            return new Texts(language("zh_cn"), CHINESE);
        }

        private static JsonObject language(String name) {
            try (var in = SheetFixture.class.getResourceAsStream("/assets/icheme/lang/" + name + ".json")) {
                return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        static boolean has(String key) {
            return ENGLISH.has(key);
        }

        @Override public String get(String key, Object... args) {
            String full = "gui.icheme." + key;
            asked.add(full);
            var source = lines.has(full) ? lines : ENGLISH;
            if (!source.has(full)) {
                missing.add(full);
                return key;
            }
            if (source != lines) inEnglish.add(full);
            String line = source.get(full).getAsString();
            return args.length == 0 ? line.replace("%%", "%") : String.format(java.util.Locale.ROOT, line, args);
        }
    }

    private static final V3ColumnInput BASE = CATALOG.presets().column(CATALOG.presets().network().defaultColumnPreset()).input(CATALOG);

    private static Flowsheet.Unit unit(String id, String type, String settings) {
        return new Flowsheet.Unit(id, type, id, 0, 0, new Params(JsonParser.parseString(settings).getAsJsonObject()));
    }

    private static Flowsheet.Stream stream(String id, @org.jetbrains.annotations.Nullable String from, String fromPort, @org.jetbrains.annotations.Nullable String to, String toPort, @org.jetbrains.annotations.Nullable Flowsheet.Feed feed) {
        return new Flowsheet.Stream(id, id, from == null ? null : new Flowsheet.End(from, fromPort), to == null ? null : new Flowsheet.End(to, toPort), feed);
    }

    private static Flowsheet.Feed fluid(String component, double temperature, double pressure, double flow) {
        var amounts = new LinkedHashMap<String, Double>();
        amounts.put(component, flow);
        return new Flowsheet.Feed(temperature, pressure, amounts, List.of());
    }

    /** The design sheets' crude unit, as `SimulatorRender` draws it. V-101 sets 2.80 bar, not the design sheets' 2.72, so its path keeps a spare (S4c). */
    static Flowsheet sample() {
        var door = ColumnDoor.from(BASE, ColumnComponents.Condenser.EXCHANGER);
        door.json().addProperty("condenser_drop", .3e5);
        for (var row : door.rows("pumparounds")) row.json().addProperty("drop", .3e5);
        var ids = BASE.componentBasis().componentIds();
        var crude = new LinkedHashMap<String, Double>();
        double[] flows = BASE.feedComponentMolarFlowsMolPerSecond();
        for (int i = 0; i < flows.length; i++) if (flows[i] > 0) crude.put(ids.get(i), flows[i]);
        var steam = BASE.steamFeeds().getFirst();
        var units = List.of(unit("P-101", "pump", "{\"target\":\"outlet\",\"pressure\":14e5,\"rise\":2e5}"), new Flowsheet.Unit("T-101", "column", "T-101", 0, 0, door),
                unit("E-101", "exchanger", "{\"target\":\"outlet\",\"side\":\"shell\",\"temperature\":473.15,\"duty\":1e6,\"area\":100,\"tube_drop\":0.5e5,\"shell_drop\":0.3e5}"),
                unit("H-101", "fired_heater", "{\"coils\":\"series\",\"target\":\"outlet\",\"temperature\":638.15,\"duty\":1e7,\"limit\":90e6,\"radiant_drop\":5.1e5,\"convection_drop\":2.5e5}"),
                unit("V-101", "valve", "{\"target\":\"outlet\",\"pressure\":2.80e5,\"drop\":1e5}"), unit("E-102", "cooler", "{\"target\":\"outlet\",\"temperature\":343.15,\"duty\":1e6,\"drop\":0.2e5}"),
                unit("P-102", "pump", "{\"target\":\"outlet\",\"pressure\":6e5,\"rise\":2e5}"));
        var streams = List.of(stream("CRUDE", null, "", "P-101", "in", new Flowsheet.Feed(298.15, 8e5, crude, List.of())), stream("S-1", "P-101", "out", "T-101", "pa/1/a", null), stream("S-2", "T-101", "pa/1/b", "T-101", "pa/2/a", null),
                stream("S-3", "T-101", "pa/2/b", "T-101", "pa/3/a", null), stream("S-4", "T-101", "pa/3/b", "E-101", "tube_a", null), stream("S-5", "E-101", "tube_b", "H-101", "a", null), stream("S-6", "H-101", "b", "V-101", "a", null),
                stream("S-7", "V-101", "b", "T-101", "feed", null), stream("STEAM", null, "", "T-101", "steam/1", fluid("Water", steam.temperatureKelvin(), 3.5e5, steam.molarFlowMolPerSecond())),
                stream("CWS", null, "", "T-101", "condenser_a", fluid("Water", 303.15, 4e5, 190000 / 3.6)), stream("CWR", "T-101", "condenser_b", null, "", null), stream("OFFGAS", "T-101", "overhead", null, "", null),
                stream("NAPHTHA", "T-101", "distillate", null, "", null), stream("WATER", "T-101", "water", null, "", null), stream("KERO", "T-101", "draw/1", null, "", null), stream("S-8", "T-101", "draw/2", "P-102", "in", null),
                stream("DIESEL", "P-102", "out", null, "", null), stream("AGO", "T-101", "draw/3", null, "", null), stream("S-9", "T-101", "bottoms", "E-101", "shell_a", null), stream("S-10", "E-101", "shell_b", "E-102", "a", null),
                stream("RESIDUE", "E-102", "b", null, "", null));
        return new Flowsheet(basis(), units, streams, FlowsheetSolver.Settings.DEFAULT);
    }

    /** Water through three units in a line, which runs in no time: FEED, P-1, A, V-1, B, P-2, OUT. */
    static Flowsheet water() {
        var units = List.of(unit("P-1", "pump", "{\"target\":\"rise\",\"rise\":2e5}"), unit("V-1", "valve", "{\"target\":\"drop\",\"drop\":1e5}"), unit("P-2", "pump", "{\"target\":\"rise\",\"rise\":2e5}"));
        var streams = List.of(stream("FEED", null, "", "P-1", "in", water(100)), stream("A", "P-1", "out", "V-1", "a", null), stream("B", "V-1", "b", "P-2", "in", null), stream("OUT", "P-2", "out", null, "", null));
        return new Flowsheet(basis(), units, streams, FlowsheetSolver.Settings.DEFAULT);
    }

    static Params column() {
        return ColumnDoor.from(CATALOG.presets().column(CATALOG.presets().network().defaultColumnPreset()).input(CATALOG), ColumnComponents.Condenser.EXCHANGER);
    }

    static SheetEditor editor(Flowsheet sheet) {
        return new SheetEditor(sheet, M, SheetFixture::column);
    }

    static Flowsheet.Basis basis() {
        return new Flowsheet.Basis(NETWORK, CATALOG.requirePackage(NETWORK).revision(), FLUIDS.axis());
    }

    static Flowsheet.Feed water(double flow) {
        var amounts = new LinkedHashMap<String, Double>();
        amounts.put("Water", flow);
        return new Flowsheet.Feed(303.15, 8e5, amounts, List.of());
    }

    /** A line of units: FEED into a pump, S-1 to a valve, PROD out. */
    static Flowsheet line() {
        var units = new ArrayList<Flowsheet.Unit>();
        units.add(new Flowsheet.Unit("p", "pump", "P-101", 100, 80, SheetEditor.defaults("pump", SheetFixture::column)));
        units.add(new Flowsheet.Unit("v", "valve", "V-101", 300, 85, SheetEditor.defaults("valve", SheetFixture::column)));
        units.add(new Flowsheet.Unit("q", "pump", "P-102", 300, 200, SheetEditor.defaults("pump", SheetFixture::column)));
        var streams = new ArrayList<Flowsheet.Stream>();
        streams.add(new Flowsheet.Stream("f", "FEED", null, new Flowsheet.End("p", "in"), water(100), List.of(new Flowsheet.Point(40, 97)), new JsonObject()));
        streams.add(new Flowsheet.Stream("s", "S-1", new Flowsheet.End("p", "out"), new Flowsheet.End("v", "a"), null, List.of(), new JsonObject()));
        streams.add(new Flowsheet.Stream("o", "PROD", new Flowsheet.End("v", "b"), null, null, List.of(new Flowsheet.Point(420, 97)), new JsonObject()));
        return new Flowsheet(basis(), units, streams, FlowsheetSolver.Settings.DEFAULT);
    }

    static Map<String, String> none() {
        return Map.of();
    }
}
