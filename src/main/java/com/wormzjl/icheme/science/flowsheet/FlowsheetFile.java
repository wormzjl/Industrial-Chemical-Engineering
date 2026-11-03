package com.wormzjl.icheme.science.flowsheet;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;

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
     * its doors' drops, which is all it ever did. Format 2 (PORT_SIDES_REVIEW.md option B): a passage's ends are {@code a} and
     * {@code b} (or {@code tube_a}, {@code pa/1/a}...), and the streams on them say which is the inlet. A sheet file needs no
     * compatibility (owner 2026-11-04), so an older format is refused, as a newer one is, and never misread.
     */
    public static final int FORMAT = 2;
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
        var root = new JsonFields(parse(bytes), "sheet");
        int format = root.whole("format");
        if (format > FORMAT) throw new FlowsheetFailure("file.newer", String.valueOf(format), String.valueOf(FORMAT));
        if (format < 1) throw JsonFields.damaged("sheet.format");
        if (format < FORMAT) throw new FlowsheetFailure("file.older", String.valueOf(format), String.valueOf(FORMAT));
        var basis = basis(root.object("basis"));
        var units = new ArrayList<Flowsheet.Unit>();
        var unitIds = new HashSet<String>();
        for (var row : root.rows("units")) {
            var unit = unit(row);
            if (!unitIds.add(unit.id())) throw JsonFields.damaged(row.path);
            units.add(unit);
        }
        var streams = new ArrayList<Flowsheet.Stream>();
        var ids = new HashSet<String>();
        for (var row : root.rows("streams")) {
            var stream = stream(row);
            if (!ids.add(stream.id())) throw JsonFields.damaged(row.path);
            streams.add(stream);
        }
        return new Flowsheet(basis, units, streams, settings(root.optionalObject("settings")), SizingFactsFile.read(root.optionalObject("sizing")), root.rest);
    }

    /** Text that may be any JSON: damage shows as bad bytes or a syntax error here, and as a missing or ill-kinded field after. */
    private static JsonElement parse(byte[] bytes) {
        try {
            return JsonParser.parseString(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString());
        } catch (CharacterCodingException | JsonParseException unreadable) {
            throw JsonFields.damaged("text");
        }
    }

    private static Flowsheet.Basis basis(JsonFields fields) {
        return new Flowsheet.Basis(fields.id("package"), fields.text("revision"), fields.texts("components"), fields.rest);
    }

    private static Flowsheet.Unit unit(JsonFields fields) {
        return new Flowsheet.Unit(fields.id("id"), fields.id("type"), fields.text("name"), fields.whole("x"), fields.whole("y"), new Params(fields.object("params").rest), fields.rest);
    }

    private static Flowsheet.Stream stream(JsonFields fields) {
        String id = fields.id("id"), name = fields.text("name");
        var from = end(fields.optionalObject("from"));
        var to = end(fields.optionalObject("to"));
        var spec = fields.optionalObject("feed");
        var route = new ArrayList<Flowsheet.Point>();
        for (var corner : fields.optionalRows("route")) route.add(new Flowsheet.Point(corner.whole("x"), corner.whole("y"), corner.rest));
        return new Flowsheet.Stream(id, name, from, to, spec == null ? null : feed(spec), route, fields.rest);
    }

    /** An end keeps what it names: a unit this sheet does not hold, or a port its unit has lost, leaves the end open in the compiler. */
    private static Flowsheet.@Nullable End end(@Nullable JsonFields fields) {
        if (fields == null) return null;
        return new Flowsheet.End(fields.id("unit"), fields.id("port"), fields.rest);
    }

    private static Flowsheet.Feed feed(JsonFields fields) {
        double temperature = fields.number("temperature"), pressure = fields.number("pressure");
        var amounts = new LinkedHashMap<String, Double>();
        var given = fields.object("amounts");
        for (var component : new ArrayList<>(given.rest.keySet())) amounts.put(component, given.amount(component));
        var solids = new ArrayList<Flowsheet.Solid>();
        for (var row : fields.optionalRows("solids")) solids.add(solid(row));
        return new Flowsheet.Feed(temperature, pressure, amounts, solids, fields.rest);
    }

    private static Flowsheet.Solid solid(JsonFields fields) {
        String material = fields.id("material"), micrometres = fields.text("micrometres");
        try {
            ParticleSize.micrometres(micrometres);
        } catch (IllegalArgumentException notASize) {
            throw JsonFields.damaged(fields.path + ".micrometres");
        }
        return new Flowsheet.Solid(material, micrometres, fields.amount("mass_flow"), fields.rest);
    }

    private static FlowsheetSolver.Settings settings(@Nullable JsonFields fields) {
        var standard = FlowsheetSolver.Settings.DEFAULT;
        if (fields == null) return standard;
        try {
            return new FlowsheetSolver.Settings(fields.number("flow_tolerance", standard.flowTolerance()), fields.number("temperature_tolerance", standard.temperatureTolerance()), fields.whole("max_passes", standard.maxPasses()),
                    fields.number("lowest_factor", standard.lowestFactor()), fields.number("highest_factor", standard.highestFactor()), fields.rest);
        } catch (IllegalArgumentException invalid) {
            throw JsonFields.damaged(fields.path);
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
        if (sheet.sizing().any()) root.add("sizing", SizingFactsFile.write(sheet.sizing()));
        root.add("settings", settings(sheet.settings()));
        return JSON.toJson(JsonFields.beside(root, sheet.unknown())).getBytes(StandardCharsets.UTF_8);
    }

    private static JsonObject basis(Flowsheet.Basis basis) {
        var json = new JsonObject();
        json.addProperty("package", basis.packageId());
        json.addProperty("revision", basis.revision());
        var components = new JsonArray();
        for (var component : basis.components()) components.add(component);
        json.add("components", components);
        return JsonFields.beside(json, basis.unknown());
    }

    private static JsonObject unit(Flowsheet.Unit unit) {
        var json = new JsonObject();
        json.addProperty("id", unit.id());
        json.addProperty("type", unit.type());
        json.addProperty("name", unit.name());
        json.addProperty("x", unit.x());
        json.addProperty("y", unit.y());
        json.add("params", unit.params().json());
        return JsonFields.beside(json, unit.unknown());
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
                route.add(JsonFields.beside(point, corner.unknown()));
            }
            json.add("route", route);
        }
        return JsonFields.beside(json, stream.unknown());
    }

    private static JsonObject end(Flowsheet.End end) {
        var json = new JsonObject();
        json.addProperty("unit", end.unit());
        json.addProperty("port", end.port());
        return JsonFields.beside(json, end.unknown());
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
                solids.add(JsonFields.beside(row, solid.unknown()));
            }
            json.add("solids", solids);
        }
        return JsonFields.beside(json, feed.unknown());
    }

    private static JsonObject settings(FlowsheetSolver.Settings settings) {
        var json = new JsonObject();
        json.addProperty("flow_tolerance", settings.flowTolerance());
        json.addProperty("temperature_tolerance", settings.temperatureTolerance());
        json.addProperty("max_passes", settings.maxPasses());
        json.addProperty("lowest_factor", settings.lowestFactor());
        json.addProperty("highest_factor", settings.highestFactor());
        return JsonFields.beside(json, settings.unknown());
    }
}
