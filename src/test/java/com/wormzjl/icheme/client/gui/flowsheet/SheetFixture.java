package com.wormzjl.icheme.client.gui.flowsheet;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wormzjl.icheme.client.gui.common.HeatControlColumn;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
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
    static final MaterialCatalog CATALOG = MaterialCatalog.bundled();
    static final String NETWORK = CATALOG.presets().network().packageId();
    static final StreamProps.Fluids FLUIDS = new StreamProps.Fluids(MaterialRuntime.with(CATALOG, NETWORK, () -> FluidThermodynamics.forNetwork(CATALOG, NETWORK)), CATALOG.requirePackage(NETWORK),
            id -> CATALOG.name(id).english(), "Crude oil, methane, nitrogen");

    private SheetFixture() {
    }

    /** Every key asked, every key the language file lacks; a line reads as the game shows it. */
    static final class Texts implements HeatControlColumn.Texts {
        private static final JsonObject ENGLISH = english();
        final Set<String> asked = new TreeSet<>(), missing = new TreeSet<>();

        private static JsonObject english() {
            try (var in = SheetFixture.class.getResourceAsStream("/assets/icheme/lang/en_us.json")) {
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
            if (!ENGLISH.has(full)) {
                missing.add(full);
                return key;
            }
            String line = ENGLISH.get(full).getAsString();
            return args.length == 0 ? line.replace("%%", "%") : String.format(java.util.Locale.ROOT, line, args);
        }
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
        streams.add(new Flowsheet.Stream("s", "S-1", new Flowsheet.End("p", "out"), new Flowsheet.End("v", "in"), null, List.of(), new JsonObject()));
        streams.add(new Flowsheet.Stream("o", "PROD", new Flowsheet.End("v", "out"), null, null, List.of(new Flowsheet.Point(420, 97)), new JsonObject()));
        return new Flowsheet(basis(), units, streams, FlowsheetSolver.Settings.DEFAULT);
    }

    static Map<String, String> none() {
        return Map.of();
    }
}
