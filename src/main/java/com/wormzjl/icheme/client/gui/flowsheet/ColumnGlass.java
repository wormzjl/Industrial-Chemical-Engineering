package com.wormzjl.icheme.client.gui.flowsheet;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wormzjl.icheme.client.gui.common.CalculatorColumn.Connection;
import com.wormzjl.icheme.client.gui.common.CalculatorColumn.Mark;
import com.wormzjl.icheme.client.gui.common.CalculatorColumn.Metric;
import com.wormzjl.icheme.client.gui.common.CalculatorColumn.Page;
import com.wormzjl.icheme.client.gui.common.CalculatorColumn;
import com.wormzjl.icheme.client.gui.common.DialogColumns;
import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.ScrollView;
import com.wormzjl.icheme.client.gui.common.kit.Skin;
import com.wormzjl.icheme.science.column.v3.V3ColumnDisplayResult;
import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.column.v3.V3ColumnInspection;
import com.wormzjl.icheme.science.column.v3.V3ColumnTemplate;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.Params;
import com.wormzjl.icheme.science.flowsheet.Port;
import com.wormzjl.icheme.science.flowsheet.UnitTypes;
import com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.Nullable;
import static com.wormzjl.icheme.client.gui.flowsheet.ColumnDoors.*;

/**
 * The column door's glass (batch 2026-11-02-process-simulator, split from {@link ColumnDoors}): the column drawing, the block's connection
 * points and the Results pages' sheets and plots. Minecraft-free.
 */
final class ColumnGlass {
    private ColumnGlass() {
    }

    // ---- The glass ----

    static Door.Glass drawing(SheetContext x, String id, ColumnDoor door, Door.View view) {
        return new Door.Glass() {
            private CalculatorColumn.Drawing placed(Rect box) {
                int n = door.stages();
                var draws = new ArrayList<Mark>();
                var pas = new ArrayList<Mark>();
                var steams = new ArrayList<Mark>();
                for (int i = 0; i < door.draws().size(); i++) draws.add(new Mark(i, door.draws().get(i).tray(), 0));
                for (int i = 0; i < door.pumparounds().size(); i++) pas.add(new Mark(i, door.pumparounds().get(i).drawTray(), door.pumparounds().get(i).returnTray()));
                for (int i = 0; i < door.steam().size(); i++) steams.add(new Mark(i, door.steam().get(i).stage(), 0));
                var display = display(x, id);
                var inspection = display == null ? null : display.inspection().orElse(null);
                double[] shares = null;
                if (inspection != null && inspection.nodes().size() == n + 2) {
                    double min = inspection.nodes().stream().mapToDouble(V3ColumnInspection.Node::temperatureKelvin).min().orElse(0), max = inspection.nodes().stream().mapToDouble(V3ColumnInspection.Node::temperatureKelvin).max().orElse(1);
                    shares = new double[n + 2];
                    for (int i = 0; i < n + 2; i++) shares[i] = (inspection.nodes().get(i).temperatureKelvin() - min) / Math.max(1e-9, max - min);
                }
                var in = new CalculatorColumn.DrawingInput(n, door.feedStage(), draws, pas, steams, Math.clamp(view.tray, 0, n + 1), view.connection, view.index, shares);
                return CalculatorColumn.drawing(in, box, 0, x.m(), x.t(), node -> "");
            }

            @Override public void draw(Canvas c, Skin skin, Rect box) {
                c.clip(box);
                CalculatorColumn.draw(c, placed(box), x.t());
                c.unclip();
            }

            @Override public @Nullable String click(Rect box, double px, double py) {
                var hit = placed(box).at(px, py);
                return hit == null ? null : hit.action();
            }
        };
    }

    /** A connection point's place in the list: what goes in, the pairs, what comes out. */
    private static int rank(String port) {
        return port.equals("feed") ? 0 : port.startsWith("steam/") || port.equals("motive") ? 1 : port.startsWith("condenser") ? 2 : port.startsWith("reboiler") ? 3 : port.startsWith("pa/") ? 4 : port.equals("overhead") ? 5 : port.equals("distillate") ? 6
                : port.equals("water") ? 7 : port.startsWith("draw/") ? 8 : 9;
    }

    /** A connection point's name, with its tray where it has one. */
    private static String point(SheetContext x, ColumnDoor door, String port) {
        int tray = port.equals("feed") ? door.feedStage() : -1;
        for (var draw : door.draws()) if (port.equals("draw/" + draw.id())) tray = draw.tray();
        for (var steam : door.steam()) if (port.equals("steam/" + steam.id())) tray = steam.stage();
        String name = StreamDoors.portName(x, port);
        return tray < 0 ? name : x.text("door.port_tray", name, tray + 1);
    }

    /** The block's connection points: what each carries, read from the last run. */
    static Door.Glass connections(SheetContext x, Flowsheet.Unit unit, ColumnDoor door) {
        return (c, skin, box) -> {
            var u = x.units();
            int x0 = box.x() + 4, y = box.y() + 4;
            String[] head = {x.text("door.connection"), x.text("door.stream"), x.text("door.temperature"), x.text("door.heat")};
            int[] at = {0, 124, 200, 280};
            for (int i = 0; i < head.length; i++) c.text(head[i], x0 + at[i], y, Palette.MUTED);
            c.hLine(box.x() + 2, box.right() - 2, y + 10, Palette.LINE);
            y += 14;
            var results = x.state().shown(unit.id());
            int r = 0;
            var ports = new ArrayList<>(UnitTypes.model("column").ports(unit.params()));
            ports.sort(java.util.Comparator.comparingInt(port -> rank(port.id())));
            for (var port : ports) {
                var stream = port.direction() == Port.Direction.IN ? x.into(unit.id(), port.id()) : x.outOf(unit.id(), port.id());
                var props = x.props(stream);
                if (r % 2 == 1) c.fill(box.x(), y - 3, box.right(), y + 11, DialogColumns.BAND);
                c.text(point(x, door, port.id()), x0 + at[0], y, Palette.TEXT);
                c.text(stream == null ? x.text("door.none") : stream.name(), x0 + at[1], y, Palette.MUTED);
                if (props != null) c.text(u.t(props.temperature()) + " " + u.temperature().symbol(), x0 + at[2], y, Palette.ACCENT);
                double heat = results == null ? Double.NaN : heat(results, unit, door, port.id());
                if (!Double.isNaN(heat)) c.text(SheetUnits.megawatts(Math.abs(heat)) + " MW", x0 + at[3], y, Palette.ACCENT);
                r++;
                y += 14;
            }
        };
    }

    /** The heat of a pair, shown on its inlet: a part's reading, {@code T-101/pa 1}; air then water's, its trim's. */
    private static double heat(SheetResults results, Flowsheet.Unit unit, ColumnDoor door, String port) {
        // Air then water's pair is its trim's (C1): the water's share, not the whole condenser.
        if (port.equals("condenser_in")) return results.reading(unit.id(), door.condenser() == ColumnComponents.Condenser.AIR_THEN_WATER ? "condenser_water" : "condenser");
        if (port.equals("reboiler_in")) return results.reading(unit.id(), "reboiler");
        if (port.startsWith("pa/") && port.endsWith("/in")) return results.part(unit.id(), "pa " + port.split("/")[1], "duty");
        return Double.NaN;
    }

    private static double drop(V3ColumnInspection view, int tray) {
        return (view.nodes().get(tray).pressurePascal() - view.nodes().get(tray - 1).pressurePascal()) / 1000;
    }

    static double totalDrop(V3ColumnInspection view) {
        int n = view.input().stageCount();
        return n < 2 ? 0 : (view.nodes().get(n).pressurePascal() - view.nodes().get(1).pressurePascal()) / 1000;
    }

    private static String percent(double value) {
        return value != 0 && Math.abs(value) < .05 ? String.format(Locale.ROOT, "%.2g", value) : SheetUnits.number(1, value);
    }

    private static String fraction(double value) {
        return String.format(Locale.ROOT, "%.5g", value);
    }

    /** The Products view's flow, mass flow, temperature and pressure on the display keys: flows stay molar, on the time key. */
    static List<List<String>> streamPairs(SheetContext x, double moles, double kilograms, double kelvin, double pascals) {
        var u = x.units();
        return List.of(List.of(c(x, "field.flow"), u.moles(moles) + " " + u.molesUnit()), List.of(c(x, "reading.mass_flow"), SheetUnits.number(1, u.time().display(kilograms)) + " " + u.time().unit("kg")),
                List.of(c(x, "field.temperature"), u.t(kelvin) + " " + u.temperature().symbol()), List.of(c(x, "reading.pressure"), u.p(pascals) + " " + u.pressure().symbol()));
    }

    /** The Products view's table heads: its rates per the time key. */
    static List<String> productHeader(SheetContext x) {
        var u = x.units();
        return List.of(c(x, "component"), c(x, "mole_percent"), u.molesUnit(), c(x, "mass_percent"), u.time().unit("kg"));
    }

    /** The Tray view's readings on the display keys; {@code dropPascals} NaN where the stage has no tray (the condenser, the reboiler). */
    static List<List<String>> nodePairs(SheetContext x, V3ColumnInspection.Node node, double dropPascals) {
        var u = x.units();
        String p = " " + u.pressure().symbol(), flow = " " + u.molesUnit();
        return List.of(List.of(c(x, "field.temperature"), u.t(node.temperatureKelvin()) + " " + u.temperature().symbol()), List.of(c(x, "reading.pressure"), u.p(node.pressurePascal()) + p),
                List.of(c(x, "tray_dp"), Double.isNaN(dropPascals) ? "—" : u.drop(dropPascals) + p), List.of(c(x, "hc_liquid"), u.moles(node.liquidMolPerSecond()) + flow),
                List.of(c(x, "hc_vapor"), u.moles(node.vaporMolPerSecond()) + flow), List.of(c(x, "water_vapor"), u.moles(node.waterVaporMolPerSecond()) + flow),
                List.of(c(x, "free_water"), u.moles(node.freeWaterMolPerSecond()) + flow));
    }

    /** The Results page's glass: the calculator's profiles, products, heat, tray and solver sheets. */
    record ResultGlass(SheetContext x, String id, ColumnDoor door, Door.View view) implements Door.Glass {
        @Override public void draw(Canvas c, Skin skin, Rect box) {
            var m = c.measure();
            int width = box.w() - ScrollView.BAR - 2;
            var display = display(x, id);
            var inspection = display == null ? null : display.inspection().orElse(null);
            c.clip(box);
            if (view.results == CalculatorColumn.View.PROFILES) {
                if (inspection == null) sheet(c, box, new CalculatorColumn.SheetBuilder(width, m).note(c(x, "solve_profiles"), Palette.MUTED));
                else profile(c, box, inspection);
            } else sheet(c, box, switch (view.results) {
                case PRODUCTS -> products(display, width, c);
                case HEAT -> heat(display, width, c);
                case TRAY -> tray(inspection, width, c);
                default -> solver(display, inspection, width, c);
            });
            c.unclip();
        }

        private void sheet(Canvas c, Rect box, CalculatorColumn.SheetBuilder builder) {
            CalculatorColumn.draw(c, builder.build(), new Rect(box.x(), box.y(), box.w() - ScrollView.BAR - 2, box.h()), 0);
        }

        private double value(V3ColumnInspection.Node node, int series) {
            return switch (view.metric) {
                case TEMPERATURE -> x.units().temperature().display(node.temperatureKelvin());
                case PRESSURE -> node.pressurePascal() / 1000;
                case TRAFFIC -> switch (series) {
                    case 0 -> node.liquidMolPerSecond() / SheetUnits.KMOL_PER_HOUR;
                    case 1 -> node.vaporMolPerSecond() / SheetUnits.KMOL_PER_HOUR;
                    case 2 -> node.waterVaporMolPerSecond() / SheetUnits.KMOL_PER_HOUR;
                    default -> node.freeWaterMolPerSecond() / SheetUnits.KMOL_PER_HOUR;
                };
                case DROP -> throw new IllegalStateException("A drop is a bar, not a profile");
            };
        }

        private void profile(Canvas c, Rect box, V3ColumnInspection inspection) {
            var m = c.measure();
            int nodes = inspection.nodes().size();
            if (view.metric == Metric.DROP) {
                int head = CalculatorColumn.heading(c, box, c(x, "column_dp", SheetUnits.number(1, totalDrop(inspection))), List.of(), m);
                var values = new double[nodes];
                for (int i = 2; i <= inspection.input().stageCount(); i++) values[i] = drop(inspection, i);
                CalculatorColumn.drops(c, CalculatorColumn.plot(box, head), values);
                return;
            }
            boolean traffic = view.metric == Metric.TRAFFIC;
            var legend = !traffic ? List.<CalculatorColumn.Legend>of() : List.of(new CalculatorColumn.Legend(c(x, "traffic.organic"), CalculatorColumn.TRAFFIC[0]), new CalculatorColumn.Legend(c(x, "traffic.vapor"), CalculatorColumn.TRAFFIC[1]),
                    new CalculatorColumn.Legend(c(x, "traffic.water"), CalculatorColumn.TRAFFIC[2]), new CalculatorColumn.Legend(c(x, "traffic.aqueous"), CalculatorColumn.TRAFFIC[3]));
            String title = switch (view.metric) {
                case TEMPERATURE -> c(x, "temperature", x.units().temperature().symbol());
                case PRESSURE -> c(x, "pressure");
                default -> c(x, "traffic");
            };
            int head = CalculatorColumn.heading(c, box, title, legend, m);
            var plot = CalculatorColumn.plot(box, head);
            if (plot.h() < 12) return;
            int series = traffic ? 4 : 1;
            var values = new double[series][nodes];
            for (int s = 0; s < series; s++) for (int i = 0; i < nodes; i++) values[s][i] = value(inspection.nodes().get(i), s);
            if (!CalculatorColumn.profile(c, plot, values, traffic ? CalculatorColumn.TRAFFIC : new int[]{Palette.ACCENT}))
                CalculatorColumn.draw(c, new CalculatorColumn.SheetBuilder(box.w(), m).note(c(x, "absent_phase"), Palette.WARN).build(), new Rect(box.x(), box.y() + head, box.w(), box.h() - head), 0);
        }

        private String material(String id) {
            return x.fluids().names().apply(id);
        }

        private CalculatorColumn.SheetBuilder products(@Nullable V3ColumnDisplayResult display, int width, Canvas c) {
            var m = c.measure();
            var out = new CalculatorColumn.SheetBuilder(width, m);
            if (display == null || display.streams().isEmpty()) return out.note(c(x, "solve_products"), Palette.MUTED);
            var stream = display.streams().get(Math.clamp(view.stream, 0, display.streams().size() - 1));
            var time = x.units().time();
            for (var pair : streamPairs(x, stream.molarFlowMolPerSecond(), stream.massFlowKgPerSecond(), stream.temperatureKelvin(), stream.pressurePascal())) out.pair(pair.get(0), pair.get(1));
            out.blank();
            var header = productHeader(x);
            var rows = new ArrayList<List<String>>();
            for (var part : stream.moleFractions())
                rows.add(List.of(material(part.componentId()), percent(part.moleFraction() * 100), percent(time.display(stream.molarFlowMolPerSecond() * part.moleFraction()) / 1000), percent(part.massFraction() * 100),
                        percent(time.display(stream.massFlowKgPerSecond() * part.massFraction()))));
            if (CalculatorColumn.SheetBuilder.fits(header, rows, width - 80 + m.width(header.getFirst()), m)) return out.table(header, rows);
            return out.table(header.subList(0, 3), rows.stream().map(r -> r.subList(0, 3)).toList());
        }

        private CalculatorColumn.SheetBuilder heat(@Nullable V3ColumnDisplayResult display, int width, Canvas c) {
            var out = new CalculatorColumn.SheetBuilder(width, c.measure()).note(c(x, "signed_heat"), Palette.MUTED);
            if (display != null) display.dutyLedger().ifPresent(d -> {
                out.note(c(x, "tray_duty", 1, SheetUnits.number(1, d.condenserWatts() / 1e6)), Palette.TEXT).note(c(x, "tray_duty", door.stages() + 2, SheetUnits.number(1, d.reboilerWatts() / 1e6)), Palette.TEXT)
                        .note(c(x, "stage_heat", SheetUnits.number(1, d.stageHeatTotalWatts() / 1e6)), Palette.TEXT).note(c(x, "feed_enthalpy", SheetUnits.number(1, d.feedEnthalpyWatts() / 1e6)), Palette.TEXT).note(c(x, "steam_enthalpy", SheetUnits.number(1, d.steamEnthalpyWatts() / 1e6)), Palette.TEXT);
                for (var stage : d.stageDuties()) out.note(c(x, "tray_duty", stage.trayNumber() + 1, SheetUnits.number(1, stage.dutyWatts() / 1e6)), Palette.TEXT);
            });
            return out;
        }

        private CalculatorColumn.SheetBuilder tray(@Nullable V3ColumnInspection inspection, int width, Canvas c) {
            var out = new CalculatorColumn.SheetBuilder(width, c.measure());
            int n = door.stages(), selected = Math.clamp(view.tray, 0, n + 1);
            out.note(c(x, selected == 0 ? "top_tray" : selected == n + 1 ? "bottom_tray" : "tray", selected + 1), Palette.TEXT);
            if (inspection == null || selected >= inspection.nodes().size()) return out.note(c(x, "solve_to_inspect"), Palette.MUTED);
            var node = inspection.nodes().get(selected);
            for (var pair : nodePairs(x, node, selected > 0 && selected <= n ? drop(inspection, selected) * 1000 : Double.NaN)) out.pair(pair.get(0), pair.get(1));
            out.blank();
            var rows = new ArrayList<List<String>>();
            var basis = inspection.input().componentBasis();
            for (int i = 0; i < basis.componentCount(); i++) rows.add(List.of(material(basis.componentId(i)), node.liquidMolPerSecond() == 0 ? "—" : percent(node.liquidFractions().get(i) * 100), node.vaporMolPerSecond() == 0 ? "—" : percent(node.vaporFractions().get(i) * 100)));
            return out.table(List.of(c(x, "component"), c(x, "liquid_percent"), c(x, "vapor_percent")), rows);
        }

        private CalculatorColumn.SheetBuilder solver(@Nullable V3ColumnDisplayResult display, @Nullable V3ColumnInspection inspection, int width, Canvas c) {
            var out = new CalculatorColumn.SheetBuilder(width, c.measure());
            if (display == null) return out.note(c(x, "solve_profiles"), Palette.MUTED);
            if (inspection != null) {
                var wet = new ArrayList<String>();
                for (int i = 1; i <= inspection.input().stageCount(); i++) if (inspection.nodes().get(i).freeWaterMolPerSecond() > 0) wet.add(Integer.toString(i + 1));
                out.note(c(x, "wet_trays", wet.isEmpty() ? c(x, "none") : String.join(", ", wet)), Palette.TEXT).note(c(x, "column_dp", SheetUnits.number(1, totalDrop(inspection))), Palette.TEXT);
                display.trayHydraulics().ifPresent(h -> out.note(c(x, h.floods() ? "flooding" : "below_flood", h.maximumFloodTray() + 1), h.floods() ? Palette.WARN : Palette.TEXT).note(c(x, "worst_flood", SheetUnits.number(1, h.maximumFloodFraction() * 100)), Palette.TEXT));
            }
            out.note(c(x, "iterations", display.newtonIterations()), Palette.TEXT).note(c(x, "residual", fraction(display.maximumScaledResidual())), Palette.TEXT).note(c(x, "closure", fraction(display.closureTolerance())), Palette.TEXT);
            display.inspection().ifPresent(v -> {
                for (var check : v.audit().checks()) out.note(c(x, "audit_value", check.family(), fraction(check.value()), fraction(check.limit())), Palette.TEXT);
            });
            return out;
        }
    }
}
