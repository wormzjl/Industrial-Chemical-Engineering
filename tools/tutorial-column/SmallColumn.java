import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wormzjl.icheme.science.column.v3.V3ColumnDisplayResult;
import com.wormzjl.icheme.science.column.v3.V3ColumnTemplate;
import com.wormzjl.icheme.science.column.v3.V3PumparoundSpec;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.science.flowsheet.FlowStream;
import com.wormzjl.icheme.science.flowsheet.FlowThermo;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetEngine;
import com.wormzjl.icheme.science.flowsheet.FlowsheetFile;
import com.wormzjl.icheme.science.flowsheet.FlowsheetRun;
import com.wormzjl.icheme.science.flowsheet.FlowsheetSolver;
import com.wormzjl.icheme.science.flowsheet.Params;
import com.wormzjl.icheme.science.flowsheet.Report;
import com.wormzjl.icheme.science.flowsheet.sizing.Lamp;
import com.wormzjl.icheme.science.flowsheet.sizing.Proposal;
import com.wormzjl.icheme.science.flowsheet.sizing.Rating;
import com.wormzjl.icheme.science.flowsheet.sizing.Reading;
import com.wormzjl.icheme.science.flowsheet.sizing.SheetSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.UnitSize;
import com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import com.wormzjl.icheme.science.tutorial.cards.BarrelData;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The small crude tower's candidates (batch 2026-11-12-tutorial-system, P4, decision D4): for each candidate it writes the
 * column template (schema 3) and a copy of the bundled crude unit with every flow scaled by 1/64 and T-101 replaced by the
 * candidate, runs that sheet once through the simulator's run and Size all (FlowsheetEngine.run, Proposal.sizeAll, the path
 * SimulatorRunTest and SizeAllSampleTest take), and writes what the datasheet draws to tmp/tutorial-p4/<id>-result.json.
 * Usage (worktree root): ./gradlew --offline --init-script tools/tutorial-column/column.init.gradle smallColumnSolve -Dcandidate=A
 */
public final class SmallColumn {
    static final double SCALE = 1.0 / 64;
    static final String OUT = "tmp/tutorial-p4";
    static final String SHEET = "assets/icheme/flowsheet_examples/crude_unit.json";
    static final String PRESET = "assets/icheme/columnpreset/medium_crude.json";
    static final String CANDIDATES = "tools/tutorial-column/candidates.json";

    private static MaterialCatalog catalog;
    private static String network;
    private static FluidThermodynamics model;
    private static Map<String, BarrelData.Segment> segments;

    public static void main(String[] args) throws Exception {
        var root = Path.of(args.length > 0 ? args[0] : ".");
        var ids = System.getProperty("candidate", "A").split(",");
        catalog = MaterialCatalog.bundled();
        network = catalog.presets().network().packageId();
        model = MaterialRuntime.with(catalog, network, () -> FluidThermodynamics.forNetwork(catalog, network));
        segments = new LinkedHashMap<>();
        for (var grade : BarrelData.compute(root.resolve("src/main/resources/data/icheme/materials")))
            if (grade.id().equals("medium_crude")) for (var s : grade.segments()) segments.put(s.component(), s);
        Files.createDirectories(root.resolve(OUT));
        var specs = Files.exists(root.resolve(CANDIDATES)) ? JsonParser.parseString(Files.readString(root.resolve(CANDIDATES))).getAsJsonObject() : new JsonObject();
        for (var id : ids) {
            id = id.trim();
            if (id.equals("REF8")) {
                reference(root);
                continue;
            }
            var spec = specs.has(id) ? specs.getAsJsonObject(id) : null;
            if (spec == null && !id.equals("A")) throw new IllegalArgumentException("no candidate " + id + " in " + CANDIDATES);
            solve(root, id, spec);
        }
    }

    /** The bundled 8 m sheet, run once as it is: the tray temperatures candidate A is held to. */
    static void reference(Path root) throws IOException {
        var sheet = FlowsheetFile.read(resource(SHEET));
        var probe = new Probe();
        var run = FlowsheetEngine.run(sheet, catalog, probe, null, probe);
        var out = new JsonObject();
        out.addProperty("status", run.status().name());
        var report = run.reports().get("T-101");
        out.add("trays", trays(report.display(), new FlowThermo(model, catalog, network, () -> {
        })));
        out.add("polls", probe.json());
        write(root.resolve(OUT).resolve("REF8-result.json"), out);
        System.out.println("REF8 " + run.status() + " start " + report.start() + " polls " + probe.json());
    }

    /** Candidate A: the bundled medium crude tower on 1/64 of its design feed at 1.0 m. */
    static V3ColumnTemplate candidateA() throws IOException {
        var preset = JsonParser.parseString(new String(resource(PRESET), StandardCharsets.UTF_8)).getAsJsonObject();
        var t = V3ColumnTemplate.read(preset).scaledTo(V3ColumnTemplate.read(preset).designFeed() * SCALE);
        return new V3ColumnTemplate(t.designFeed(), t.trays(), t.feedTray(), t.topTemperature(), t.reboilerDuty(), t.refluxRatio(), t.topPressure(), 1.0, t.draws(), t.steam(),
                t.pumparounds());
    }

    static V3ColumnTemplate template(JsonObject spec) throws IOException {
        var a = candidateA();
        var draws = new ArrayList<V3ColumnTemplate.Draw>();
        for (var e : spec.getAsJsonArray("draws")) draws.add(new V3ColumnTemplate.Draw(e.getAsJsonObject().get("tray").getAsInt(), e.getAsJsonObject().get("flow_kmol_h").getAsDouble()));
        var steam = new ArrayList<V3ColumnTemplate.Steam>();
        for (var e : spec.getAsJsonArray("steam"))
            steam.add(new V3ColumnTemplate.Steam(e.getAsJsonObject().get("tray").getAsInt(), e.getAsJsonObject().get("flow_kmol_h").getAsDouble(), e.getAsJsonObject().get("temperature_kelvin").getAsDouble()));
        var pas = new ArrayList<V3ColumnTemplate.Pumparound>();
        for (var e : spec.getAsJsonArray("pumparounds")) {
            var p = e.getAsJsonObject();
            pas.add(new V3ColumnTemplate.Pumparound(p.get("draw_tray").getAsInt(), p.get("return_tray").getAsInt(), p.get("cooling_mw").getAsDouble(), V3PumparoundSpec.Split.valueOf(p.get("split").getAsString())));
        }
        return new V3ColumnTemplate(a.designFeed(), spec.get("tray_count").getAsInt(), spec.get("feed_tray").getAsInt(), num(spec, "top_temperature_kelvin", a.topTemperature()), 0,
                num(spec, "reflux_ratio", a.refluxRatio()), num(spec, "top_pressure_bar", a.topPressure()), num(spec, "diameter_m", 1.0), draws, steam, pas);
    }

    private static double num(JsonObject o, String key, double fallback) {
        return o.has(key) ? o.get(key).getAsDouble() : fallback;
    }

    static void solve(Path root, String id, JsonObject spec) throws IOException {
        var template = spec == null ? candidateA() : template(spec);
        // The template as a columnpreset draft (schema 3), never under assets/.
        var preset = new JsonObject();
        preset.addProperty("schema_version", 3);
        preset.addProperty("kind", "column");
        preset.addProperty("name", "Small crude tower (Medium crude) candidate " + id);
        template.write(preset);
        write(root.resolve(OUT).resolve(id + ".json"), preset);

        // The crude unit at 1/64 with T-101 replaced.
        var sheetJson = JsonParser.parseString(new String(resource(SHEET), StandardCharsets.UTF_8)).getAsJsonObject();
        int drawCount = template.draws().size(), paCount = template.pumparounds().size();
        for (var e : sheetJson.getAsJsonArray("units")) {
            var u = e.getAsJsonObject();
            var params = u.getAsJsonObject("params");
            switch (u.get("id").getAsString()) {
                case "H-101" -> {
                    scale(params, "duty");
                    scale(params, "limit");
                }
                case "E-101" -> {
                    scale(params, "duty");
                    scale(params, "area");
                }
                case "E-102" -> scale(params, "duty");
                case "T-101" -> u.add("params", ColumnDoor.template(new Params(params), template).json());
                default -> {
                }
            }
        }
        var streams = new JsonArray();
        for (var e : sheetJson.getAsJsonArray("streams")) {
            var s = e.getAsJsonObject();
            String sid = s.get("id").getAsString();
            if (s.has("feed")) {
                var amounts = s.getAsJsonObject("feed").getAsJsonObject("amounts");
                if (sid.equals("STEAM")) {
                    double moles = template.steam().stream().mapToDouble(V3ColumnTemplate.Steam::flow).sum() / 3.6;
                    amounts.addProperty("Water", moles);
                    s.getAsJsonObject("feed").addProperty("temperature", template.steam().getFirst().temperature());
                } else for (var k : amounts.keySet()) amounts.addProperty(k, amounts.get(k).getAsDouble() * SCALE);
            }
            // The crude's preheat train through the pumparound coolers in order, then E-101.
            if (sid.equals("S-2") && paCount < 2 || sid.equals("S-3") && paCount < 3) continue;
            if (sid.equals("S-4")) s.getAsJsonObject("from").addProperty("port", "pa/" + paCount + "/b");
            if (sid.equals("AGO") && drawCount < 3 || sid.equals("KERO") && drawCount < 2) continue;
            if (sid.equals("S-8") && drawCount < 2) s.getAsJsonObject("from").addProperty("port", "draw/1");
            if (sid.equals("DIESEL") && drawCount < 2) {
                s.addProperty("id", "MIDDLE");
                s.addProperty("name", "MIDDLE");
            }
            streams.add(s);
        }
        sheetJson.add("streams", streams);
        var sheetBytes = new GsonBuilder().setPrettyPrinting().create().toJson(sheetJson).getBytes(StandardCharsets.UTF_8);
        Files.write(root.resolve(OUT).resolve(id + "-sheet.json"), sheetBytes);
        var sheet = FlowsheetFile.read(sheetBytes);
        if (Boolean.getBoolean("draft")) {
            System.out.println(id + ": template and sheet written, not solved (-Ddraft=true)");
            return;
        }

        var probe = new Probe();
        var thermo = new FlowThermo(model, catalog, network, probe);
        var out = new JsonObject();
        out.addProperty("id", id);
        out.addProperty("note", spec != null && spec.has("note") ? spec.get("note").getAsString() : "the 42-tray medium crude tower, every flow and duty / 64, 1.0 m");
        out.add("template", preset);
        var run = FlowsheetEngine.run(sheet, catalog, probe, null, probe);
        out.addProperty("status", run.status().name());
        out.add("log", notes(run));
        out.add("loops", loops(run));
        var column = run.reports().get("T-101");
        out.addProperty("start", column == null || column.start() == null ? "none" : column.start().name());
        out.add("polls_run", probe.json());
        if (column == null || column.display() == null) {
            out.addProperty("converged", false);
            out.addProperty("reason", run.outcomes().containsKey("T-101") ? String.valueOf(run.outcomes().get("T-101")) : "no outcome");
            write(root.resolve(OUT).resolve(id + "-result.json"), out);
            System.out.println(id + " " + run.status() + " column not solved: " + out.get("reason"));
            return;
        }
        out.addProperty("converged", true);
        column(out, column, template, thermo);
        out.add("products", products(run, thermo, drawCount));
        out.add("units", unitReadings(run));
        if (run.status() == FlowsheetRun.Status.STEADY) {
            probe.phase("rate");
            var rated = MaterialRuntime.with(catalog, network, () -> SheetSizing.rate(sheet, run, thermo));
            out.add("rated", sized(rated));
            probe.phase("size");
            var all = MaterialRuntime.with(catalog, network, () -> Proposal.sizeAll(sheet, run, thermo, probe));
            out.add("polls_size", probe.json());
            out.addProperty("sized_status", all.run().status().name());
            out.add("sized", sized(all.sized()));
            out.add("sized_units", unitReadings(all.run()));
            var door = ColumnDoor.of(all.sheet().units().stream().filter(u -> u.id().equals("T-101")).findFirst().orElseThrow().params());
            out.addProperty("sized_diameter", door.diameter());
            Files.write(root.resolve(OUT).resolve(id + "-sized-sheet.json"), FlowsheetFile.write(all.sheet()));
        }
        write(root.resolve(OUT).resolve(id + "-result.json"), out);
        summary(id, out);
    }

    /** The column's numbers: verdict, counters, trays, duties. */
    static void column(JsonObject out, Report report, V3ColumnTemplate template, FlowThermo thermo) {
        V3ColumnDisplayResult d = report.display();
        var inspection = d.inspection().orElseThrow();
        out.addProperty("audit_accepted", inspection.audit().accepted());
        var checks = new JsonArray();
        for (var c : inspection.audit().checks()) {
            var o = new JsonObject();
            o.addProperty("family", c.family());
            o.addProperty("value", c.value());
            o.addProperty("limit", c.limit());
            o.addProperty("passed", c.passed());
            checks.add(o);
        }
        out.add("audit", checks);
        var advisories = new JsonArray();
        inspection.audit().advisoryEvidence().forEach(advisories::add);
        out.add("advisories", advisories);
        out.addProperty("newton", d.newtonIterations());
        out.addProperty("residual", d.maximumScaledResidual());
        out.add("trays", trays(d, thermo));
        var readings = new JsonObject();
        report.readings().forEach(readings::addProperty);
        out.add("column_readings", readings);
        out.addProperty("reflux_ratio", template.refluxRatio());
        var pas = new JsonArray();
        for (var p : template.pumparounds()) {
            var o = new JsonObject();
            o.addProperty("draw_tray", p.drawTray());
            o.addProperty("return_tray", p.returnTray());
            o.addProperty("mw", p.cooling());
            pas.add(o);
        }
        out.add("pumparounds", pas);
        d.trayHydraulics().ifPresent(h -> out.addProperty("hydraulics", h.toString()));
    }

    /** Every node: condenser 0, trays, sump; T, P, L, V, the liquid's 5/50/95 % points. Numbered as the player reads them (condenser 1). */
    static JsonArray trays(V3ColumnDisplayResult d, FlowThermo thermo) {
        var inspection = d.inspection().orElseThrow();
        var ids = inspection.input().componentBasis().componentIds();
        var out = new JsonArray();
        for (int n = 0; n < inspection.nodes().size(); n++) {
            var node = inspection.nodes().get(n);
            var o = new JsonObject();
            o.addProperty("tray", n + 1);
            o.addProperty("T", node.temperatureKelvin());
            o.addProperty("P", node.pressurePascal());
            o.addProperty("L", node.liquidMolPerSecond() * 3.6);
            o.addProperty("V", node.vaporMolPerSecond() * 3.6);
            o.addProperty("freeWater", node.freeWaterMolPerSecond() * 3.6);
            var parts = new ArrayList<Boiling.Part>();
            for (int i = 0; i < ids.size(); i++) {
                int at = thermo.place(ids.get(i));
                if (at < 0 || at == thermo.water()) continue;
                var part = part(ids.get(i), node.liquidFractions().get(i) * node.liquidMolPerSecond() * model.molecularWeight(at));
                if (part != null) parts.add(part);
            }
            points(o, parts);
            out.add(o);
        }
        return out;
    }

    private static Boiling.Part part(String id, double mass) {
        var s = segments.get(id);
        if (s == null) {
            for (var e : segments.entrySet())
                if (catalog.requirePackage(network).canonicalId(e.getKey()).equals(catalog.requirePackage(network).canonicalId(id))) {
                    s = e.getValue();
                    break;
                }
        }
        if (s == null) return null;
        return new Boiling.Part(mass, s.lower(), s.upper());
    }

    private static void points(JsonObject o, List<Boiling.Part> parts) {
        for (double share : new double[]{.05, .5, .95}) {
            var p = Boiling.at(parts, share);
            o.addProperty("t" + Math.round(share * 100), p.kelvin());
            if (p.above()) o.addProperty("t" + Math.round(share * 100) + "_above", true);
        }
    }

    /** The products leaving the sheet, light to heavy, each with its rates, share and boiling points. */
    static JsonArray products(FlowsheetRun run, FlowThermo thermo, int draws) {
        var names = new ArrayList<>(List.of("OFFGAS", "NAPHTHA"));
        if (draws >= 2) names.addAll(List.of("KERO", "DIESEL"));
        else names.add("MIDDLE");
        if (draws >= 3) names.add("AGO");
        names.add("RESIDUE");
        double feedMass = hydrocarbonMass(run.streams().get("CRUDE"), thermo);
        var out = new JsonArray();
        for (var name : names) {
            var s = run.streams().get(name);
            var o = new JsonObject();
            o.addProperty("name", name);
            if (s == null) {
                o.addProperty("missing", true);
                out.add(o);
                continue;
            }
            double[] a = s.amountsView();
            double moles = 0, mass = 0;
            var parts = new ArrayList<Boiling.Part>();
            for (int i = 0; i < a.length; i++) {
                if (i == thermo.water()) continue;
                moles += a[i];
                double m = a[i] * model.molecularWeight(i);
                mass += m;
                var part = part(thermo.axis().get(i), m);
                if (part != null) parts.add(part);
            }
            o.addProperty("kmol_h", moles * 3.6);
            o.addProperty("kg_h", mass * 3600);
            o.addProperty("water_kg_h", a[thermo.water()] * model.molecularWeight(thermo.water()) * 3600);
            o.addProperty("share", mass / feedMass);
            o.addProperty("T", s.temperature());
            o.addProperty("P", s.pressure());
            double[] hc = a.clone();
            hc[thermo.water()] = 0;
            try {
                var std = thermo.atTemperature(288.15, Math.max(s.pressure(), 2e5), hc, SolidInventory.EMPTY);
                double vapour = std.state() == null ? Double.NaN : 1 - (FluidThermodynamics.holdsLiquid(std.state()) ? model.liquidMass(std.state()) / std.mass() : 0);
                o.addProperty("vapour_at_15C", vapour);
                if (std.state() != null && FluidThermodynamics.holdsLiquid(std.state())) o.addProperty("m3_h", mass * 3600 / model.liquidDensity(std.state()));
            } catch (RuntimeException refused) {
                o.addProperty("m3_h_refused", String.valueOf(refused.getMessage()));
            }
            points(o, parts);
            out.add(o);
        }
        return out;
    }

    static double hydrocarbonMass(FlowStream s, FlowThermo thermo) {
        double[] a = s.amountsView();
        double mass = 0;
        for (int i = 0; i < a.length; i++) if (i != thermo.water()) mass += a[i] * model.molecularWeight(i);
        return mass;
    }

    static JsonObject unitReadings(FlowsheetRun run) {
        var out = new JsonObject();
        run.reports().forEach((id, report) -> {
            var o = new JsonObject();
            report.readings().forEach(o::addProperty);
            o.addProperty("duty_W", report.duty());
            out.add(id, o);
        });
        var streams = new JsonObject();
        run.streams().forEach((id, s) -> {
            var o = new JsonObject();
            o.addProperty("T", s.temperature());
            o.addProperty("P", s.pressure());
            o.addProperty("mol_s", s.total());
            o.addProperty("kg_s", s.mass());
            streams.add(id, o);
        });
        out.add("streams", streams);
        return out;
    }

    static JsonArray notes(FlowsheetRun run) {
        var out = new JsonArray();
        for (var n : run.log()) out.add(n.severity() + " " + n.subject() + " " + n.key() + " " + n.arguments());
        for (var e : run.reports().entrySet()) for (var n : e.getValue().notes()) out.add(n.severity() + " " + n.subject() + " " + n.key() + " " + n.arguments());
        return out;
    }

    static JsonArray loops(FlowsheetRun run) {
        var out = new JsonArray();
        for (var l : run.loops()) out.add("tears " + l.tears() + " passes " + l.passes() + " closed " + l.closed());
        return out;
    }

    /** Every unit and line rated: size, lamp, each reading with its load, the shape's footprint and height in blocks. */
    static JsonObject sized(SheetSizing.Sized sized) {
        var out = new JsonObject();
        var units = new JsonObject();
        sized.units().forEach((id, u) -> {
            var o = new JsonObject();
            o.addProperty("size", String.valueOf(u.size()));
            o.addProperty("count", u.size() == null ? 0 : u.size().count());
            o.addProperty("lamp", u.rating().lamp().name());
            o.add("readings", readings(u.rating()));
            if (u.size() != null) {
                var shape = UnitSize.built(u.size());
                if (shape != null) {
                    o.addProperty("kind", shape.kind().name());
                    try {
                        var layout = ShapeRasterizer.layout(shape);
                        int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y0 = Integer.MAX_VALUE, y1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
                        var cells = new ArrayList<>(layout.shell());
                        cells.addAll(layout.interior());
                        for (var c : cells) {
                            x0 = Math.min(x0, c.x());
                            x1 = Math.max(x1, c.x());
                            y0 = Math.min(y0, c.y());
                            y1 = Math.max(y1, c.y());
                            z0 = Math.min(z0, c.z());
                            z1 = Math.max(z1, c.z());
                        }
                        o.addProperty("blocks_x", x1 - x0 + 1);
                        o.addProperty("blocks_z", z1 - z0 + 1);
                        o.addProperty("blocks_y", y1 - y0 + 1);
                        var ports = new JsonArray();
                        for (var p : ShapeRasterizer.ports(shape)) ports.add(p.cell().x() + "," + p.cell().y() + "," + p.cell().z() + " d" + p.dx() + "," + p.dy() + "," + p.dz() + " " + p.port());
                        o.add("ports", ports);
                    } catch (RuntimeException e) {
                        o.addProperty("layout_refused", String.valueOf(e.getMessage()));
                    }
                }
            }
            units.add(id, o);
        });
        out.add("units", units);
        var lines = new JsonObject();
        sized.lines().forEach((id, l) -> {
            var o = new JsonObject();
            o.addProperty("size", String.valueOf(l.size()));
            o.addProperty("lamp", l.rating().lamp().name());
            o.add("readings", readings(l.rating()));
            lines.add(id, o);
        });
        out.add("lines", lines);
        var paths = new JsonArray();
        for (var p : sized.paths()) {
            var o = new JsonObject();
            o.addProperty("path", p.start() + " -> " + p.end());
            o.addProperty("spare", p.spare());
            o.addProperty("lamp", p.rating().lamp().name());
            paths.add(o);
        }
        out.add("paths", paths);
        return out;
    }

    static JsonArray readings(Rating rating) {
        var out = new JsonArray();
        for (Reading r : rating.readings()) {
            var o = new JsonObject();
            o.addProperty("limit", r.limit().name());
            o.addProperty("kind", r.limit().kind().name());
            o.addProperty("value", r.value());
            o.addProperty("low", r.low());
            o.addProperty("high", r.high());
            o.addProperty("lamp", r.lamp().name());
            o.addProperty("load", r.load());
            out.add(o);
        }
        return out;
    }

    static void summary(String id, JsonObject out) {
        System.out.printf(Locale.ROOT, "%s: %s, column start %s, newton %d, audit %s%n", id, out.get("status").getAsString(), out.get("start").getAsString(), out.get("newton").getAsInt(),
                out.get("audit_accepted").getAsBoolean());
        System.out.println("  polls run " + out.get("polls_run") + (out.has("polls_size") ? " size " + out.get("polls_size") : ""));
        for (var e : out.getAsJsonArray("products")) {
            var p = e.getAsJsonObject();
            if (p.has("missing")) continue;
            System.out.printf(Locale.ROOT, "  %-8s %8.2f kmol/h %9.1f kg/h %6.2f %%  5/50/95 %6.1f %6.1f %6.1f K%n", p.get("name").getAsString(), p.get("kmol_h").getAsDouble(),
                    p.get("kg_h").getAsDouble(), 100 * p.get("share").getAsDouble(), p.get("t5").getAsDouble(), p.get("t50").getAsDouble(), p.get("t95").getAsDouble());
        }
        if (out.has("sized")) {
            var bad = new ArrayList<String>();
            out.getAsJsonObject("sized").getAsJsonObject("units").entrySet().forEach(e -> {
                var lamp = e.getValue().getAsJsonObject().get("lamp").getAsString();
                if (!lamp.equals(Lamp.GREEN.name()) && !lamp.equals(Lamp.NONE.name())) bad.add(e.getKey() + " " + lamp);
            });
            out.getAsJsonObject("sized").getAsJsonObject("lines").entrySet().forEach(e -> {
                var lamp = e.getValue().getAsJsonObject().get("lamp").getAsString();
                if (!lamp.equals(Lamp.GREEN.name()) && !lamp.equals(Lamp.NONE.name())) bad.add("line " + e.getKey() + " " + lamp);
            });
            System.out.println("  sized " + out.get("sized_status").getAsString() + " diameter " + out.get("sized_diameter").getAsDouble() + " m; not green: " + bad);
        }
    }

    private static void scale(JsonObject params, String key) {
        if (params.has(key)) params.addProperty(key, params.get(key).getAsDouble() * SCALE);
    }

    static byte[] resource(String name) throws IOException {
        try (var in = SmallColumn.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) throw new IOException("no resource " + name);
            return in.readAllBytes();
        }
    }

    static void write(Path file, JsonObject o) throws IOException {
        Files.writeString(file, new GsonBuilder().setPrettyPrinting().serializeSpecialFloatingPointValues().create().toJson(o), StandardCharsets.UTF_8);
    }

    /**
     * The run's checkpoint and progress: counts the column's control polls by route, from the stack (the learned correction,
     * the classical continuation, the warm start), for each column calculation of each pass, and for the sizing stage's trial
     * solves. A counter, as the calculator's own budget is: no clock.
     */
    static final class Probe implements Runnable, FlowsheetSolver.Progress {
        private String phase = "run";
        private final Map<String, Long> counts = new LinkedHashMap<>();
        private final List<String> solves = new ArrayList<>();
        private String current = null;
        private final Map<String, Long> solve = new LinkedHashMap<>();

        void phase(String phase) {
            close();
            this.phase = phase;
            counts.clear();
            solves.clear();
        }

        @Override public void run() {
            String route = StackWalker.getInstance().walk(frames -> {
                boolean calculator = false, learned = false, warm = false, sizing = false;
                for (var f : (Iterable<StackWalker.StackFrame>) frames::iterator) {
                    String c = f.getClassName(), m = f.getMethodName();
                    if (c.endsWith("V3ColumnCalculator")) calculator = true;
                    if (m.equals("correctNeuralSeed") || c.contains("Neural")) learned = true;
                    if (m.equals("calculateWithStrategy")) warm = true;
                    if (c.endsWith("ColumnSizing") || c.endsWith("BlockSizing")) sizing = true;
                }
                if (!calculator) return null;
                String r = warm ? "warm" : learned ? "learned" : "classical";
                return sizing ? "trial-" + r : r;
            });
            if (route == null) return;
            counts.merge(route, 1L, Long::sum);
            solve.merge(route, 1L, Long::sum);
        }

        @Override public void calculation(int pass, int passes, String unit) {
            close();
            if (unit.equals("T-101")) current = phase + " pass " + pass;
        }

        @Override public void start(Report.Start start) {
            if (current != null) current += " start " + start;
        }

        private void close() {
            if (current != null || !solve.isEmpty()) solves.add((current == null ? phase + " other" : current) + " " + solve);
            current = null;
            solve.clear();
        }

        JsonObject json() {
            close();
            var o = new JsonObject();
            counts.forEach(o::addProperty);
            var s = new JsonArray();
            for (var line : solves) if (!line.endsWith("{}")) s.add(line);
            o.add("solves", s);
            return o;
        }
    }
}
