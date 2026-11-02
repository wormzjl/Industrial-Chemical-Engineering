package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.client.gui.common.CalculatorColumn;
import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.ScrollView;
import com.wormzjl.icheme.client.gui.common.kit.Skin;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * A stream's door (batch 2026-11-02-process-simulator, sheets 07, 08, 10). A feed is the Fluid Generator's page: set point, flow, phases; its Composition tab
 * is the calculator's feed table, Amount a part of the whole, and the flow holds as a part changes. A stream a unit makes is a reader, every value a
 * meter. Copy and Paste wait for the clipboard of W8.
 */
public final class StreamDoors {
    private static final double KMOL_PER_HOUR = 1000 / 3600.0;
    private static final int TOP_SEARCH = 24;

    private StreamDoors() {
    }

    public static Door door(SheetContext x, String id, Door.View view) {
        var stream = x.editor().stream(id);
        return stream.feed() != null && stream.from() == null ? feed(x, stream, view) : product(x, stream, view);
    }

    private static List<String> phaseKeys(SheetContext x) {
        return List.of(x.text("phase.organic_short"), x.text("phase.aqueous_short"), x.text("phase.gas_short"), x.text("phase.solid_short"));
    }

    /** The phase keys, then the composition's table. */
    private static Door.Glass contents(SheetContext x, Flowsheet.Stream stream, Door.View view) {
        var keys = phaseKeys(x);
        return new Door.Glass() {
            @Override public void draw(Canvas c, Skin skin, Rect box) {
                var u = x.units();
                var solved = x.solved(stream);
                var table = new ArrayList<String[]>();
                table.add(new String[]{x.text("label.component"), x.text("label.mole_percent"), u.molesUnit()});
                for (var part : StreamProps.parts(solved, x.fluids(), view.phase)) table.add(new String[]{part.name(), SheetUnits.number(2, part.percent()), u.moles(part.moles())});
                Door.table(c, new Rect(box.x(), box.y() + 20, box.w(), box.h() - 20), new int[]{box.w() - 120, 60, 60}, table);
            }

            @Override public List<Door.Slot> slots(Rect box) {
                var slots = new ArrayList<Door.Slot>();
                int n = keys.size(), tw = (box.w() - 3 * (n - 1)) / n;
                for (int i = 0; i < n; i++) slots.add(Door.Slot.key("phase/" + i, new Rect(box.x() + i * (tw + 3), box.y(), tw, 16), keys.get(i), i == view.phase));
                return slots;
            }
        };
    }

    // ---- A feed ----

    private static double total(Flowsheet.Feed feed) {
        double total = 0;
        for (double amount : feed.amounts().values()) total += amount;
        return total;
    }

    private static double mass(SheetContext x, Flowsheet.Feed feed) {
        double mass = 0;
        var axis = x.fluids().axis();
        var properties = x.fluids().pack().properties();
        for (var e : feed.amounts().entrySet()) {
            int i = axis.indexOf(e.getKey());
            mass += e.getValue() * (i >= 0 && i < properties.size() ? properties.get(i).molecularWeight() : .018015);
        }
        return mass;
    }

    private static Door feed(SheetContext x, Flowsheet.Stream stream, Door.View view) {
        var feed = stream.feed();
        var u = x.units();
        var values = new LinkedHashMap<String, String>();
        var rows = new ArrayList<Column.Row>();
        Door.Glass glass;
        if (view.tab == 0) {
            rows.add(Door.name(x.text("door.name")));
            values.put("name", stream.name());
            rows.add(Door.group(x.text("door.group.set_point"), Door.entry("pressure", x.text("door.pressure"), u.pressure().symbol()), Door.entry("temperature", x.text("door.temperature"), u.temperature().symbol())));
            values.put("pressure", u.p(feed.pressure()));
            values.put("temperature", u.t(feed.temperature()));
            boolean mass = view.basis.equals("mass");
            rows.add(Door.group(x.text("door.group.flow"), new Column.Buttons(List.of(Door.position("basis/mole", x.text("door.mole"), !mass), Door.position("basis/mass", x.text("door.mass"), mass))), Door.entry("flow", x.text("door.flow"), mass ? u.tonnesUnit() : u.molesUnit())));
            values.put("flow", mass ? u.tonnes(mass(x, feed)) : u.moles(total(feed)));
            var props = x.props(stream);
            rows.add(Door.phases(x.text("door.group.phases"), props == null ? StreamProps.Phases.NONE : props.phases(), x.t()));
            rows.add(Door.group(x.text("door.group.readings"), Door.meter(x.text("door.mass_flow"), props == null ? "" : u.tonnes(props.mass()), u.tonnesUnit()), Door.meter(x.text("door.density"), props == null ? "" : SheetUnits.number(1, props.density()), "kg/m³"),
                    Door.meter(x.text("door.mean_weight"), props == null ? "" : SheetUnits.number(1, props.weight()), "kg/kmol")));
            glass = contents(x, stream, view);
        } else if (view.tab == 1) {
            rows.add(Door.group(x.text("door.group.mixture"), new Column.Buttons(List.of(Door.key("templates", x.text("door.templates"), true, null), Door.key("save", x.text("file.save_as"), false, x.text("hint.clipboard"))))));
            rows.add(Door.group(x.text("door.group.clipboard"), new Column.Line(x.text("door.clipboard_empty"), Palette.MUTED), new Column.Buttons(List.of(Door.key("copy", x.text("door.copy"), false, x.text("hint.clipboard")), Door.key("paste", x.text("door.paste"), false, x.text("hint.clipboard")))),
                    new Column.Buttons(List.of(Door.key("paste_state", x.text("door.paste_state"), false, x.text("hint.clipboard"))))));
            rows.add(Door.group(x.text("door.group.total"), Door.meter(x.text("door.parts"), Integer.toString(feed.amounts().size()), ""), Door.meter(x.text("door.share"), feed.amounts().isEmpty() ? "" : "100.0", "%")));
            glass = table(x, stream);
        } else {
            rows.add(Door.group(x.text("door.group.solids"), new Column.Line(feed.solids().isEmpty() ? x.text("door.no_solids") : x.text("door.solids_count", feed.solids().size()), Palette.MUTED)));
            glass = solids(x, feed.solids());
        }
        var status = feedStatus(x, stream);
        return new Door(x.text("door.feed"), Door.tabs(view.tab, x.t().get("fluid.overview"), x.t().get("fluid.composition"), x.t().get("fluid.solids")), view.tab, status, Palette.ACCENT, false, List.of(rows), values, glass, List.of(), new Door.Actions() {
            @Override public void entry(String key, String text) {
                writeFeed(x, stream.id(), key, text, view);
            }

            @Override public void button(String key) {
                if (key.startsWith("tab/")) view.tab = Integer.parseInt(key.substring(4));
                else if (key.startsWith("phase/")) view.phase = Integer.parseInt(key.substring(6));
                else if (key.startsWith("basis/")) view.basis = key.substring(6);
                else if (key.startsWith("remove/")) removePart(x, stream.id(), key.substring(7));
            }
        });
    }

    private static String feedStatus(SheetContext x, Flowsheet.Stream stream) {
        var feed = stream.feed();
        var u = x.units();
        var props = x.props(stream);
        String flow = u.moles(total(feed)) + " " + u.molesUnit(), t = u.t(feed.temperature()) + " " + u.temperature().symbol(), p = u.p(feed.pressure()) + " " + u.pressure().symbol();
        if (total(feed) == 0) return x.text("door.status.no_flow");
        return x.text("door.status.feed", props == null ? x.text("door.status.not_solved_short") : phaseName(x, props), flow, t, p);
    }

    /** One word for a stream's phases. */
    private static String phaseName(SheetContext x, StreamProps props) {
        var p = props.phases();
        return p.gas() >= 100 ? x.text("phase.gas_word") : p.gas() > 0 ? x.text("phase.two_word") : x.text("phase.liquid_word");
    }

    private static void writeFeed(SheetContext x, String id, String key, String text, Door.View view) {
        var stream = x.editor().stream(id);
        var feed = stream.feed();
        String step = "entry " + id + " " + key;
        if (key.equals("name")) {
            x.editor().rename(id, text);
            return;
        }
        if (key.equals("add")) {
            add(x, id, text);
            return;
        }
        var value = SheetContext.number(text);
        if (value == null) return;
        var u = x.units();
        Flowsheet.Feed next;
        switch (key) {
            case "pressure" -> next = new Flowsheet.Feed(feed.temperature(), u.pressure().si(value), feed.amounts(), feed.solids(), feed.unknown());
            case "temperature" -> next = new Flowsheet.Feed(u.temperature().si(value), feed.pressure(), feed.amounts(), feed.solids(), feed.unknown());
            case "flow" -> {
                boolean mass = view.basis.equals("mass");
                double now = mass ? mass(x, feed) : total(feed), wanted = u.time().perSecond(value) * 1000;
                if (now == 0) return;
                double scale = wanted / now;
                var amounts = new LinkedHashMap<String, Double>();
                for (var e : feed.amounts().entrySet()) amounts.put(e.getKey(), e.getValue() * scale);
                next = new Flowsheet.Feed(feed.temperature(), feed.pressure(), amounts, feed.solids(), feed.unknown());
            }
            default -> {
                if (!key.startsWith("part/") || value < 0) return;
                next = part(feed, key.substring(5), value);
            }
        }
        x.editor().setFeed(id, next, step);
    }

    /** A component's part of the whole, the flow held. With no flow yet, a part is that many kmol/h, for the mixture must start somewhere. */
    private static Flowsheet.Feed part(Flowsheet.Feed feed, String component, double percent) {
        double total = total(feed);
        var amounts = new LinkedHashMap<>(feed.amounts());
        if (total == 0) amounts.put(component, percent * KMOL_PER_HOUR);
        else {
            double others = total - amounts.getOrDefault(component, 0.0), share = Math.min(percent, 100) / 100;
            double scale = others == 0 ? 1 : (1 - share) / (others / total);
            for (var e : feed.amounts().entrySet()) amounts.put(e.getKey(), e.getValue() * scale);
            amounts.put(component, share * total);
        }
        return new Flowsheet.Feed(feed.temperature(), feed.pressure(), amounts, feed.solids(), feed.unknown());
    }

    /** Adds the one component the text names, as the player types it. */
    private static void add(SheetContext x, String id, String text) {
        var feed = x.editor().stream(id).feed();
        String want = text.strip().toLowerCase(Locale.ROOT);
        if (want.isEmpty()) return;
        String found = null;
        for (var component : x.fluids().axis()) {
            if (feed.amounts().containsKey(component) || !x.fluids().names().apply(component).toLowerCase(Locale.ROOT).startsWith(want)) continue;
            if (found != null) return;
            found = component;
        }
        if (found == null) return;
        var amounts = new LinkedHashMap<>(feed.amounts());
        amounts.put(found, 0.0);
        x.editor().setFeed(id, new Flowsheet.Feed(feed.temperature(), feed.pressure(), amounts, feed.solids(), feed.unknown()), null);
    }

    private static void removePart(SheetContext x, String id, String component) {
        var feed = x.editor().stream(id).feed();
        var amounts = new LinkedHashMap<>(feed.amounts());
        amounts.remove(component);
        x.editor().setFeed(id, new Flowsheet.Feed(feed.temperature(), feed.pressure(), amounts, feed.solids(), feed.unknown()), null);
    }

    /** Loads a template's mixture: its fractions on the axis, the flow held, 100 kmol/h with none yet. */
    public static void template(SheetContext x, String id, double[] fractions) {
        var feed = x.editor().stream(id).feed();
        double total = total(feed);
        if (total == 0) total = 100 * KMOL_PER_HOUR;
        var axis = x.fluids().axis();
        var amounts = new LinkedHashMap<String, Double>();
        for (int i = 0; i < fractions.length && i < axis.size(); i++) if (fractions[i] > 0) amounts.put(axis.get(i), fractions[i] * total);
        x.editor().setFeed(id, new Flowsheet.Feed(feed.temperature(), feed.pressure(), amounts, feed.solids(), feed.unknown()), null);
    }

    private record Table(CalculatorColumn.FeedTable table, Map<Integer, String> components, Rect area) {
    }

    private static Table placed(SheetContext x, Flowsheet.Stream stream, Rect box) {
        var feed = stream.feed();
        var u = x.units();
        double total = total(feed);
        var rows = new ArrayList<CalculatorColumn.FeedRow>();
        var components = new LinkedHashMap<Integer, String>();
        var axis = x.fluids().axis();
        var properties = x.fluids().pack().properties();
        for (var e : feed.amounts().entrySet()) {
            int row = rows.size(), i = axis.indexOf(e.getKey());
            double share = total == 0 ? 0 : 100 * e.getValue() / total, weight = i >= 0 && i < properties.size() ? properties.get(i).molecularWeight() : .018015;
            rows.add(new CalculatorColumn.FeedRow(row, x.fluids().names().apply(e.getKey()), SheetUnits.number(1, share), u.moles(e.getValue()), u.tonnes(e.getValue() * weight)));
            components.put(row, e.getKey());
        }
        var area = new Rect(box.x() + 2, box.y() + TOP_SEARCH, box.w() - 8 - ScrollView.BAR, box.h() - TOP_SEARCH - 2);
        var header = List.of(x.text("door.component"), x.text("door.amount"), x.text("label.mole_percent"), u.molesUnit(), u.tonnesUnit());
        return new Table(CalculatorColumn.feedTable(header, rows, area, x.m()), components, area);
    }

    /** The calculator's feed table, under a search box that adds a component. */
    private static Door.Glass table(SheetContext x, Flowsheet.Stream stream) {
        return new Door.Glass() {
            @Override public void draw(Canvas c, Skin skin, Rect box) {
                var t = placed(x, stream, box);
                c.clip(t.area());
                CalculatorColumn.draw(c, t.table(), t.area(), 0);
                c.unclip();
                var view = new ScrollView();
                view.layout(new Rect(box.x(), t.area().y(), box.w(), t.area().h()), box.right() - ScrollView.BAR, t.table().height());
                view.drawBar(c, skin);
            }

            @Override public List<Door.Slot> slots(Rect box) {
                var t = placed(x, stream, box);
                var slots = new ArrayList<Door.Slot>();
                slots.add(Door.Slot.entry("add", new Rect(box.x() + 3, box.y() + 3, box.w() - 6, 16), "", x.text("door.add_component")));
                double total = total(stream.feed());
                for (var e : t.table().entries()) {
                    if (e.amount().bottom() > t.area().bottom()) break;
                    String component = t.components().get(e.row().id());
                    double share = total == 0 ? 0 : 100 * stream.feed().amounts().get(component) / total;
                    slots.add(Door.Slot.entry("part/" + component, e.amount(), SheetUnits.number(2, share), null));
                    slots.add(Door.Slot.key("remove/" + component, e.remove(), "x", false));
                }
                return slots;
            }
        };
    }

    private static Door.Glass solids(SheetContext x, List<Flowsheet.Solid> solids) {
        return (c, skin, box) -> {
            var rows = new ArrayList<String[]>();
            rows.add(new String[]{x.text("door.material"), x.text("door.size"), "kg/s"});
            for (var solid : solids) rows.add(new String[]{solid.material(), solid.micrometres() + " µm", SheetUnits.number(2, solid.massFlow())});
            Door.table(c, box, new int[]{box.w() - 120, 60, 60}, rows);
        };
    }

    // ---- A stream a unit makes ----

    private static @Nullable String from(SheetContext x, Flowsheet.Stream stream) {
        if (stream.from() == null) return null;
        var unit = x.editor().unit(stream.from().unit());
        return unit == null ? null : x.text("door.status.from", unit.name(), portName(x, stream.from().port()));
    }

    /** A port's name for the player. */
    public static String portName(SheetContext x, String port) {
        var parts = port.split("/");
        return switch (parts[0]) {
            case "pa" -> x.text("port.pa", parts[1], x.text("port." + parts[2]));
            case "in", "out" -> parts.length > 1 ? x.text("port." + parts[0] + "_row", parts[1]) : x.text("port." + port);
            case "steam", "draw" -> x.text("port." + parts[0] + "_row", parts[1]);
            default -> x.text("port." + port);
        };
    }

    private static Door product(SheetContext x, Flowsheet.Stream stream, Door.View view) {
        var u = x.units();
        var p = x.props(stream);
        var rows = new ArrayList<Column.Row>();
        rows.add(Door.name(x.text("door.name")));
        rows.add(Door.group(x.text("door.group.conditions"), Door.meter(x.text("door.pressure"), p == null ? "" : u.p(p.pressure()), u.pressure().symbol()), Door.meter(x.text("door.temperature"), p == null ? "" : u.t(p.temperature()), u.temperature().symbol())));
        rows.add(Door.group(x.text("door.group.flow"), Door.meter(x.text("door.mole_flow"), p == null ? "" : u.moles(p.moles()), u.molesUnit()), Door.meter(x.text("door.mass_flow"), p == null ? "" : u.tonnes(p.mass()), u.tonnesUnit()),
                Door.meter(x.text("door.liquid_volume"), p == null ? "" : SheetUnits.number(1, u.time().display(p.liquidVolume())), u.time().unit("m³"))));
        rows.add(Door.phases(x.text("door.group.phases"), p == null ? StreamProps.Phases.NONE : p.phases(), x.t()));
        rows.add(Door.group(x.text("door.group.readings"), Door.meter(x.text("door.density"), p == null ? "" : SheetUnits.number(1, p.density()), "kg/m³"), Door.meter(x.text("door.viscosity"), p == null ? "" : SheetUnits.number(2, p.liquidMu() * 1e3), "mPa s"),
                Door.meter(x.text("door.mean_weight"), p == null ? "" : SheetUnits.number(1, p.weight()), "kg/kmol"), Door.meter(x.text("door.enthalpy"), p == null ? "" : SheetUnits.number(1, p.enthalpy() / 1e3), "kJ/kg")));
        String origin = from(x, stream);
        String status = p == null ? x.text("door.status.not_solved") : origin == null ? x.text("door.status.open") : origin;
        boolean wired = stream.to() != null;
        return new Door(x.text(wired ? "door.stream" : "door.product"), Door.tabs(Math.min(view.tab, 1), x.t().get("fluid.overview"), x.t().get("fluid.solids")), Math.min(view.tab, 1), status, p == null ? Palette.MUTED : Palette.ACCENT, false, List.of(rows), Map.of("name", stream.name()),
                view.tab == 0 ? contents(x, stream, view) : solids(x, List.of()), List.of(Door.key("copy", x.text("door.copy"), false, x.text("hint.clipboard"))), new Door.Actions() {
            @Override public void entry(String key, String text) {
                if (key.equals("name")) x.editor().rename(stream.id(), text);
            }

            @Override public void button(String key) {
                if (key.startsWith("tab/")) view.tab = Integer.parseInt(key.substring(4));
                else if (key.startsWith("phase/")) view.phase = Integer.parseInt(key.substring(6));
            }
        });
    }
}
