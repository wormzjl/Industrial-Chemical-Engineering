package com.wormzjl.icheme.science.flowsheet;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
import com.wormzjl.icheme.science.flowsheet.sizing.AirCoolerSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.CompressorSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.DesignBasis;
import com.wormzjl.icheme.science.flowsheet.sizing.DrumSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.EjectorSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.ExchangerSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.HeaterSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.LineSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.PumpSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.Service;
import com.wormzjl.icheme.science.flowsheet.sizing.SizingFacts;
import com.wormzjl.icheme.science.flowsheet.sizing.TankSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.UnitSize;
import com.wormzjl.icheme.science.flowsheet.sizing.UnitType;
import com.wormzjl.icheme.science.fluid.state.ParticleSize;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The flowsheet file (batch 2026-11-02-process-simulator, plan section 3.9): a sheet's facts as one JSON document, for the player's
 * own library and the block's stored sheet alike. Facts survive, as in a world save. Fields are append-only, with a default equal
 * to the old behaviour, and a field a later version added is kept and written back. A damaged file or a newer format is refused
 * by a {@link FlowsheetFailure}, and reading never writes. What this version no longer has stays in the sheet for the compiler to
 * leave out: a removed type, a removed row's port, a removed component.
 */
public final class FlowsheetFile {
    /**
     * The format this version writes. A new field needs no new number; only what an older reader would get wrong does. Sizing's
     * facts (S4) need none: a reader before them keeps the section as a later version's and writes it back, and runs the sheet on
     * its doors' drops, which is all it ever did.
     */
    public static final int FORMAT = 1;
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();

    private FlowsheetFile() {
    }

    /** The sheet in {@code file}, which stays as it is. */
    public static Flowsheet open(Path file) throws IOException {
        return read(Files.readAllBytes(file));
    }

    /** Writes the sheet whole beside {@code file} and renames it into place, so a stopped game leaves the old file or the new one. */
    public static void save(Path file, Flowsheet sheet) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        var whole = file.resolveSibling(file.getFileName() + ".part");
        try {
            Files.write(whole, write(sheet));
            try {
                Files.move(whole, file, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException plain) {
                Files.move(whole, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(whole);
        }
    }

    public static Flowsheet read(byte[] bytes) {
        return read(bytes, UnitTypes.ALIASES);
    }

    static Flowsheet read(byte[] bytes, UnitTypes.Aliases aliases) {
        var root = new Fields(parse(bytes), "sheet");
        int format = root.whole("format");
        if (format > FORMAT) throw new FlowsheetFailure("file.newer", String.valueOf(format), String.valueOf(FORMAT));
        if (format < 1) throw damaged("sheet.format");
        var basis = basis(root.object("basis"));
        var units = new ArrayList<Flowsheet.Unit>();
        var types = new HashMap<String, String>();
        var held = new LinkedHashMap<String, UnitType>();
        for (var row : root.rows("units")) {
            var unit = unit(row, aliases, held);
            if (types.putIfAbsent(unit.id(), unit.type()) != null) throw damaged(row.path);
            units.add(unit);
        }
        var streams = new ArrayList<Flowsheet.Stream>();
        var ids = new HashSet<String>();
        for (var row : root.rows("streams")) {
            var stream = stream(row, types, aliases);
            if (!ids.add(stream.id())) throw damaged(row.path);
            streams.add(stream);
        }
        var sizing = sizing(root.optionalObject("sizing"));
        // A unit read under an old type's name keeps the type it was, where its sizing holds no key of its own (C2: an air cooler).
        for (var entry : held.entrySet()) if (sizing.unit(entry.getKey()).type() == null) sizing = sizing.with(entry.getKey(), sizing.unit(entry.getKey()).typed(entry.getValue()));
        return new Flowsheet(basis, units, streams, settings(root.optionalObject("settings")), sizing, root.rest);
    }

    // ---- Sizing's facts (sizing plan section 3.9): absent on a sheet never sized, which reads as every item on Auto, no size ----

    private static SizingFacts sizing(@Nullable Fields fields) {
        if (fields == null) return SizingFacts.NONE;
        var basis = DesignBasis.DEFAULT;
        var basisRest = new JsonObject();
        var given = fields.optionalObject("basis");
        if (given != null) {
            try {
                basis = new DesignBasis(given.number("design_flow", basis.designFlow()), given.number("line_length", basis.lineLength()), given.number("pa_fall", basis.paFall()));
            } catch (IllegalArgumentException invalid) {
                throw damaged(given.path);
            }
            basisRest = given.rest;
        }
        var units = new LinkedHashMap<String, SizingFacts.UnitFacts>();
        var unitRows = fields.optionalObject("units");
        if (unitRows != null) for (var id : new ArrayList<>(unitRows.rest.keySet())) units.put(id, unitFacts(unitRows.object(id)));
        var lines = new LinkedHashMap<String, SizingFacts.LineFacts>();
        var lineRows = fields.optionalObject("lines");
        if (lineRows != null) for (var id : new ArrayList<>(lineRows.rest.keySet())) lines.put(id, lineFacts(lineRows.object(id)));
        return new SizingFacts(basis, basisRest, units, lines, fields.rest);
    }

    private static SizingFacts.UnitFacts unitFacts(Fields fields) {
        var mode = fields.name("mode", SizingFacts.Mode.class, SizingFacts.Mode.AUTO);
        var type = fields.name("type", UnitType.class, null);
        var hold = fields.optionalAmount("hold");
        var size = fields.optionalObject("size");
        return new SizingFacts.UnitFacts(mode, type, size == null ? null : unitSize(size), hold, size == null ? new JsonObject() : size.rest, fields.rest);
    }

    private static SizingFacts.LineFacts lineFacts(Fields fields) {
        var mode = fields.name("mode", SizingFacts.Mode.class, SizingFacts.Mode.AUTO);
        var service = fields.name("service", Service.class, null);
        var length = fields.optionalAmount("length");
        var size = fields.optionalObject("size");
        LineSizing.Size line = null;
        if (size != null) {
            try {
                line = new LineSizing.Size(size.number("bore"), size.whole("runs"));
            } catch (IllegalArgumentException invalid) {
                throw damaged(size.path);
            }
        }
        return new SizingFacts.LineFacts(mode, service, length, line, size == null ? new JsonObject() : size.rest, fields.rest);
    }

    /** A size by its kind's name; one its record refuses is damage. One the constructor cannot build is a red lamp, not damage. */
    private static UnitSize unitSize(Fields fields) {
        String kind = fields.id("kind");
        try {
            return switch (kind) {
                case "PUMP" -> new PumpSizing.Size(fields.number("discharge"));
                case "EXCHANGER" -> new ExchangerSizing.Size(fields.bool("kettle"), fields.whole("shells"), fields.number("shell"), fields.whole("tubes"));
                case "AIR_COOLER" -> new AirCoolerSizing.Size(fields.number("width"), fields.whole("length"), new ProcessUnit.Bank(fields.whole("parallel"), fields.whole("series")), fields.whole("banks", 1));
                case "HEATER" -> new HeaterSizing.Size(fields.name("type", UnitType.class), fields.whole("count"), fields.number("diameter"), fields.whole("length"));
                case "COMPRESSOR" -> new CompressorSizing.Size(fields.name("type", UnitType.class), fields.number("size"), fields.whole("length"));
                case "DRUM" -> new DrumSizing.Size(fields.name("type", UnitType.class), fields.number("diameter"), fields.whole("length"), fields.bool("boot"));
                case "TANK" -> new TankSizing.Size(fields.name("type", UnitType.class), fields.whole("count"), fields.number("diameter"), fields.whole("shell"));
                case "EJECTOR" -> new EjectorSizing.Size(fields.number("suction"));
                default -> throw damaged(fields.path + ".kind");
            };
        } catch (IllegalArgumentException invalid) {
            throw damaged(fields.path);
        }
    }

    private static JsonObject sizing(SizingFacts facts) {
        var json = new JsonObject();
        var basis = new JsonObject();
        basis.addProperty("design_flow", facts.basis().designFlow());
        basis.addProperty("line_length", facts.basis().lineLength());
        basis.addProperty("pa_fall", facts.basis().paFall());
        json.add("basis", beside(basis, facts.basisUnknown()));
        var units = new JsonObject();
        facts.units().forEach((id, unit) -> {
            var row = new JsonObject();
            row.addProperty("mode", unit.mode().name());
            if (unit.type() != null) row.addProperty("type", unit.type().name());
            if (unit.hold() != null) row.addProperty("hold", unit.hold());
            if (unit.size() != null) row.add("size", beside(unitSize(unit.size()), unit.sizeUnknown()));
            units.add(id, beside(row, unit.unknown()));
        });
        json.add("units", units);
        var lines = new JsonObject();
        facts.lines().forEach((id, line) -> {
            var row = new JsonObject();
            row.addProperty("mode", line.mode().name());
            if (line.service() != null) row.addProperty("service", line.service().name());
            if (line.length() != null) row.addProperty("length", line.length());
            if (line.size() != null) {
                var size = new JsonObject();
                size.addProperty("bore", line.size().bore());
                size.addProperty("runs", line.size().runs());
                row.add("size", beside(size, line.sizeUnknown()));
            }
            lines.add(id, beside(row, line.unknown()));
        });
        json.add("lines", lines);
        return beside(json, facts.unknown());
    }

    private static JsonObject unitSize(UnitSize size) {
        var json = new JsonObject();
        switch (size) {
            case PumpSizing.Size pump -> {
                json.addProperty("kind", "PUMP");
                json.addProperty("discharge", pump.discharge());
            }
            case ExchangerSizing.Size exchanger -> {
                json.addProperty("kind", "EXCHANGER");
                json.addProperty("kettle", exchanger.kettle());
                json.addProperty("shells", exchanger.shells());
                json.addProperty("shell", exchanger.shell());
                json.addProperty("tubes", exchanger.tubes());
            }
            case AirCoolerSizing.Size cooler -> {
                json.addProperty("kind", "AIR_COOLER");
                json.addProperty("width", cooler.width());
                json.addProperty("length", cooler.length());
                json.addProperty("parallel", cooler.bank().parallel());
                json.addProperty("series", cooler.bank().series());
                // Identical banks in parallel (S3b): one where absent.
                if (cooler.banks() > 1) json.addProperty("banks", cooler.banks());
            }
            case HeaterSizing.Size heater -> {
                json.addProperty("kind", "HEATER");
                json.addProperty("type", heater.type().name());
                json.addProperty("count", heater.count());
                json.addProperty("diameter", heater.diameter());
                json.addProperty("length", heater.length());
            }
            case CompressorSizing.Size compressor -> {
                json.addProperty("kind", "COMPRESSOR");
                json.addProperty("type", compressor.type().name());
                json.addProperty("size", compressor.size());
                json.addProperty("length", compressor.length());
            }
            case DrumSizing.Size drum -> {
                json.addProperty("kind", "DRUM");
                json.addProperty("type", drum.type().name());
                json.addProperty("diameter", drum.diameter());
                json.addProperty("length", drum.length());
                json.addProperty("boot", drum.boot());
            }
            case TankSizing.Size tank -> {
                json.addProperty("kind", "TANK");
                json.addProperty("type", tank.type().name());
                json.addProperty("count", tank.count());
                json.addProperty("diameter", tank.diameter());
                json.addProperty("shell", tank.shell());
            }
            case EjectorSizing.Size ejector -> {
                json.addProperty("kind", "EJECTOR");
                json.addProperty("suction", ejector.suction());
            }
            default -> throw new IllegalArgumentException("A " + size.getClass().getSimpleName() + " is no fact: it follows from the sheet");
        }
        return json;
    }

    /** Text that may be any JSON: damage shows as bad bytes or a syntax error here, and as a missing or ill-kinded field after. */
    private static JsonElement parse(byte[] bytes) {
        try {
            return JsonParser.parseString(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString());
        } catch (CharacterCodingException | JsonParseException unreadable) {
            throw damaged("text");
        }
    }

    private static FlowsheetFailure damaged(String where) {
        return new FlowsheetFailure("file.damaged", where);
    }

    private static Flowsheet.Basis basis(Fields fields) {
        return new Flowsheet.Basis(fields.id("package"), fields.text("revision"), fields.texts("components"), fields.rest);
    }

    private static Flowsheet.Unit unit(Fields fields, UnitTypes.Aliases aliases, Map<String, UnitType> held) {
        String id = fields.id("id"), type = fields.id("type");
        var key = aliases.held(type);
        if (key != null) held.put(id, key);
        return new Flowsheet.Unit(id, aliases.type(type), fields.text("name"), fields.whole("x"), fields.whole("y"), new Params(fields.object("params").rest), fields.rest);
    }

    private static Flowsheet.Stream stream(Fields fields, Map<String, String> types, UnitTypes.Aliases aliases) {
        String id = fields.id("id"), name = fields.text("name");
        var from = end(fields.optionalObject("from"), types, aliases);
        var to = end(fields.optionalObject("to"), types, aliases);
        var spec = fields.optionalObject("feed");
        var route = new ArrayList<Flowsheet.Point>();
        for (var corner : fields.optionalRows("route")) route.add(new Flowsheet.Point(corner.whole("x"), corner.whole("y"), corner.rest));
        return new Flowsheet.Stream(id, name, from, to, spec == null ? null : feed(spec), route, fields.rest);
    }

    /** An end keeps what it names: a unit this sheet does not hold, or a port its unit has lost, leaves the end open in the compiler. */
    private static Flowsheet.@Nullable End end(@Nullable Fields fields, Map<String, String> types, UnitTypes.Aliases aliases) {
        if (fields == null) return null;
        String unit = fields.id("unit"), port = fields.id("port"), type = types.get(unit);
        return new Flowsheet.End(unit, type == null ? port : aliases.port(type, port), fields.rest);
    }

    private static Flowsheet.Feed feed(Fields fields) {
        double temperature = fields.number("temperature"), pressure = fields.number("pressure");
        var amounts = new LinkedHashMap<String, Double>();
        var given = fields.object("amounts");
        for (var component : new ArrayList<>(given.rest.keySet())) amounts.put(component, given.amount(component));
        var solids = new ArrayList<Flowsheet.Solid>();
        for (var row : fields.optionalRows("solids")) solids.add(solid(row));
        return new Flowsheet.Feed(temperature, pressure, amounts, solids, fields.rest);
    }

    private static Flowsheet.Solid solid(Fields fields) {
        String material = fields.id("material"), micrometres = fields.text("micrometres");
        try {
            ParticleSize.micrometres(micrometres);
        } catch (IllegalArgumentException notASize) {
            throw damaged(fields.path + ".micrometres");
        }
        return new Flowsheet.Solid(material, micrometres, fields.amount("mass_flow"), fields.rest);
    }

    private static FlowsheetSolver.Settings settings(@Nullable Fields fields) {
        var standard = FlowsheetSolver.Settings.DEFAULT;
        if (fields == null) return standard;
        try {
            return new FlowsheetSolver.Settings(fields.number("flow_tolerance", standard.flowTolerance()), fields.number("temperature_tolerance", standard.temperatureTolerance()), fields.whole("max_passes", standard.maxPasses()),
                    fields.number("lowest_factor", standard.lowestFactor()), fields.number("highest_factor", standard.highestFactor()), fields.rest);
        } catch (IllegalArgumentException invalid) {
            throw damaged(fields.path);
        }
    }

    public static byte[] write(Flowsheet sheet) {
        var root = new JsonObject();
        root.addProperty("format", FORMAT);
        root.add("basis", basis(sheet.basis()));
        var units = new JsonArray();
        for (var unit : sheet.units()) units.add(unit(unit));
        root.add("units", units);
        var streams = new JsonArray();
        for (var stream : sheet.streams()) streams.add(stream(stream));
        root.add("streams", streams);
        if (sheet.sizing().any()) root.add("sizing", sizing(sheet.sizing()));
        root.add("settings", settings(sheet.settings()));
        return JSON.toJson(beside(root, sheet.unknown())).getBytes(StandardCharsets.UTF_8);
    }

    /** {@code json} with what a later version put beside its fields. */
    private static JsonObject beside(JsonObject json, JsonObject unknown) {
        for (var field : unknown.entrySet()) json.add(field.getKey(), field.getValue());
        return json;
    }

    private static JsonObject basis(Flowsheet.Basis basis) {
        var json = new JsonObject();
        json.addProperty("package", basis.packageId());
        json.addProperty("revision", basis.revision());
        var components = new JsonArray();
        for (var component : basis.components()) components.add(component);
        json.add("components", components);
        return beside(json, basis.unknown());
    }

    private static JsonObject unit(Flowsheet.Unit unit) {
        var json = new JsonObject();
        json.addProperty("id", unit.id());
        json.addProperty("type", unit.type());
        json.addProperty("name", unit.name());
        json.addProperty("x", unit.x());
        json.addProperty("y", unit.y());
        json.add("params", unit.params().json());
        return beside(json, unit.unknown());
    }

    private static JsonObject stream(Flowsheet.Stream stream) {
        var json = new JsonObject();
        json.addProperty("id", stream.id());
        json.addProperty("name", stream.name());
        if (stream.from() != null) json.add("from", end(stream.from()));
        if (stream.to() != null) json.add("to", end(stream.to()));
        if (stream.feed() != null) json.add("feed", feed(stream.feed()));
        if (!stream.route().isEmpty()) {
            var route = new JsonArray();
            for (var corner : stream.route()) {
                var point = new JsonObject();
                point.addProperty("x", corner.x());
                point.addProperty("y", corner.y());
                route.add(beside(point, corner.unknown()));
            }
            json.add("route", route);
        }
        return beside(json, stream.unknown());
    }

    private static JsonObject end(Flowsheet.End end) {
        var json = new JsonObject();
        json.addProperty("unit", end.unit());
        json.addProperty("port", end.port());
        return beside(json, end.unknown());
    }

    private static JsonObject feed(Flowsheet.Feed feed) {
        var json = new JsonObject();
        json.addProperty("temperature", feed.temperature());
        json.addProperty("pressure", feed.pressure());
        var amounts = new JsonObject();
        feed.amounts().forEach(amounts::addProperty);
        json.add("amounts", amounts);
        if (!feed.solids().isEmpty()) {
            var solids = new JsonArray();
            for (var solid : feed.solids()) {
                var row = new JsonObject();
                row.addProperty("material", solid.material());
                row.addProperty("micrometres", solid.micrometres());
                row.addProperty("mass_flow", solid.massFlow());
                solids.add(beside(row, solid.unknown()));
            }
            json.add("solids", solids);
        }
        return beside(json, feed.unknown());
    }

    private static JsonObject settings(FlowsheetSolver.Settings settings) {
        var json = new JsonObject();
        json.addProperty("flow_tolerance", settings.flowTolerance());
        json.addProperty("temperature_tolerance", settings.temperatureTolerance());
        json.addProperty("max_passes", settings.maxPasses());
        json.addProperty("lowest_factor", settings.lowestFactor());
        json.addProperty("highest_factor", settings.highestFactor());
        return beside(json, settings.unknown());
    }

    /** One object of the file as it is read. A reader takes its fields out, each checked, so what stays is what a later version added. */
    private static final class Fields {
        final JsonObject rest;
        final String path;

        Fields(JsonElement element, String path) {
            if (!element.isJsonObject()) throw damaged(path);
            rest = element.getAsJsonObject();
            this.path = path;
        }

        private @Nullable JsonElement take(String key) {
            var value = rest.remove(key);
            return value == null || value.isJsonNull() ? null : value;
        }

        private JsonElement need(String key) {
            var value = take(key);
            if (value == null) throw damaged(at(key));
            return value;
        }

        private String at(String key) {
            return path + "." + key;
        }

        private static boolean isText(JsonElement value) {
            return value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
        }

        String text(String key) {
            var value = need(key);
            if (!isText(value)) throw damaged(at(key));
            return value.getAsString();
        }

        /** A text that names something, so it cannot be empty. */
        String id(String key) {
            String id = text(key);
            if (id.isEmpty()) throw damaged(at(key));
            return id;
        }

        /** Text rows that name something once each. */
        List<String> texts(String key) {
            var value = need(key);
            if (!value.isJsonArray()) throw damaged(at(key));
            var texts = new ArrayList<String>();
            for (var row : value.getAsJsonArray()) {
                if (!isText(row) || texts.contains(row.getAsString())) throw damaged(at(key));
                texts.add(row.getAsString());
            }
            return texts;
        }

        private double finite(String key, JsonElement value) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() || !Double.isFinite(value.getAsDouble())) throw damaged(at(key));
            return value.getAsDouble();
        }

        double number(String key) {
            return finite(key, need(key));
        }

        double number(String key, double fallback) {
            var value = take(key);
            return value == null ? fallback : finite(key, value);
        }

        /** A flow: a number that cannot be under zero. */
        double amount(String key) {
            double amount = number(key);
            if (amount < 0) throw damaged(at(key));
            return amount;
        }

        private int whole(String key, JsonElement value) {
            double number = finite(key, value);
            if (number != Math.rint(number) || Math.abs(number) > Integer.MAX_VALUE) throw damaged(at(key));
            return (int) number;
        }

        int whole(String key) {
            return whole(key, need(key));
        }

        int whole(String key, int fallback) {
            var value = take(key);
            return value == null ? fallback : whole(key, value);
        }

        boolean bool(String key) {
            var value = need(key);
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw damaged(at(key));
            return value.getAsBoolean();
        }

        /** A positive number, null if absent. */
        @Nullable Double optionalAmount(String key) {
            var value = take(key);
            if (value == null) return null;
            double amount = finite(key, value);
            if (!(amount > 0)) throw damaged(at(key));
            return amount;
        }

        /** An enum constant by its name; a name this version does not know is damage. */
        <E extends Enum<E>> E name(String key, Class<E> type) {
            String name = text(key);
            try {
                return Enum.valueOf(type, name);
            } catch (IllegalArgumentException unknown) {
                throw damaged(at(key));
            }
        }

        <E extends Enum<E>> @Nullable E name(String key, Class<E> type, @Nullable E fallback) {
            var value = rest.get(key);
            if (value != null && !value.isJsonNull()) return name(key, type);
            rest.remove(key);
            return fallback;
        }

        Fields object(String key) {
            return new Fields(need(key), at(key));
        }

        @Nullable Fields optionalObject(String key) {
            var value = take(key);
            return value == null ? null : new Fields(value, at(key));
        }

        private List<Fields> rows(String key, JsonElement value) {
            if (!value.isJsonArray()) throw damaged(at(key));
            var rows = new ArrayList<Fields>();
            for (var row : value.getAsJsonArray()) rows.add(new Fields(row, at(key) + "[" + rows.size() + "]"));
            return rows;
        }

        List<Fields> rows(String key) {
            return rows(key, need(key));
        }

        List<Fields> optionalRows(String key) {
            var value = take(key);
            return value == null ? List.of() : rows(key, value);
        }
    }
}
