package com.wormzjl.icheme.client.gui.flowsheet;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetSolver;
import com.wormzjl.icheme.science.flowsheet.Params;
import com.wormzjl.icheme.science.flowsheet.sizing.SizingFacts;
import com.wormzjl.icheme.science.flowsheet.unit.EjectorUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.jetbrains.annotations.Nullable;

/**
 * Every edit of a sheet, its undo and redo, its tags and refusals (batch 2026-11-02-process-simulator, W6_SCREEN_PLAN.md section 5).
 * A sheet is immutable records, so undo holds whole sheets. {@code Params} wraps a mutable {@code JsonObject}, so a change deep-copies
 * first and each record is rebuilt with its {@code unknown}, which every edit keeps. Entries typed under one key, or one drag, coalesce
 * into one undo step. An edit that cannot stand is refused: the sheet stays, and {@link #refusal} says why for the status line.
 */
public final class SheetEditor {
    /** A refusal: its key under {@code gui.icheme.}, its arguments. */
    public record Refusal(String key, List<String> arguments) {
    }

    public static final String LOCKED = "flowsheet.refused.locked", RUNNING = "flowsheet.refused.running", NAME_USED = "flowsheet.refused.name_used", NAME_EMPTY = "flowsheet.refused.name_empty",
            PORT_HELD = "flowsheet.refused.port_held", PORT_SIDE = "flowsheet.refused.port_side";
    private static final int HISTORY = 256;

    private final Measure m;
    private final Supplier<Params> column;
    private final ArrayDeque<Flowsheet> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private Flowsheet sheet;
    private @Nullable String step;
    private @Nullable Refusal refusal;
    private boolean locked;
    private String lockedBy = LOCKED;
    private @Nullable SheetLayout layout;

    /** @param column a dropped column's settings, {@code ColumnDoor.from} on the default input */
    public SheetEditor(Flowsheet sheet, Measure m, Supplier<Params> column) {
        this.sheet = sheet;
        this.m = m;
        this.column = column;
    }

    public Flowsheet sheet() {
        return sheet;
    }

    /** The sheet's layout, which stays until the sheet changes. */
    public SheetLayout layout() {
        if (layout == null) layout = SheetLayout.of(sheet, m);
        return layout;
    }

    /** A read-only sheet takes no edit. */
    public void lock(boolean locked) {
        lock(locked, LOCKED);
    }

    /** A sheet takes no edit while {@code locked}, and {@code why} is what a refused gesture says. Lifting the lock lifts what it refused. */
    public void lock(boolean locked, String why) {
        if (this.locked && !locked && refusal != null && refusal.key().equals(lockedBy)) refusal = null;
        this.locked = locked;
        lockedBy = why;
    }

    public boolean locked() {
        return locked;
    }

    /** Whether an edit may begin; a gesture on a locked sheet says why on the status line. */
    public boolean editable() {
        return !locked || refuse(lockedBy);
    }

    /** Why the last edit was refused, or null if it stood. */
    public @Nullable Refusal refusal() {
        return refusal;
    }

    public boolean canUndo() {
        return !locked && !undo.isEmpty();
    }

    public boolean canRedo() {
        return !locked && !redo.isEmpty();
    }

    /** Ends a coalescing step: a field lost focus, a drag let go. */
    public void endStep() {
        step = null;
    }

    public boolean undo() {
        if (!canUndo()) return false;
        redo.push(sheet);
        replace(undo.pop());
        return true;
    }

    public boolean redo() {
        if (!canRedo()) return false;
        undo.push(sheet);
        replace(redo.pop());
        return true;
    }

    /** Starts over on {@code next}, as New and Open do: its history is its own. */
    public void load(Flowsheet next) {
        undo.clear();
        redo.clear();
        replace(next);
        refusal = null;
    }

    private void replace(Flowsheet next) {
        sheet = next;
        layout = null;
        step = null;
    }

    private boolean open(Refusal why) {
        refusal = why;
        return false;
    }

    private boolean refuse(String key, String... arguments) {
        return open(new Refusal(key, List.of(arguments)));
    }

    /** Applies {@code next}: one undo step, or part of the last where {@code key} is the step in hand. */
    private boolean apply(Flowsheet next, @Nullable String key) {
        if (locked) return refuse(lockedBy);
        refusal = null;
        if (key == null || !key.equals(step)) {
            undo.push(sheet);
            while (undo.size() > HISTORY) undo.removeLast();
        }
        redo.clear();
        sheet = next;
        layout = null;
        step = key;
        return true;
    }

    private Flowsheet with(List<Flowsheet.Unit> units, List<Flowsheet.Stream> streams) {
        return new Flowsheet(sheet.basis(), units, streams, sheet.settings(), sheet.sizing(), sheet.unknown());
    }

    private Flowsheet withUnit(String id, UnaryOperator<Flowsheet.Unit> change) {
        var units = new ArrayList<Flowsheet.Unit>();
        for (var unit : sheet.units()) units.add(unit.id().equals(id) ? change.apply(unit) : unit);
        return with(units, sheet.streams());
    }

    private Flowsheet withStream(String id, UnaryOperator<Flowsheet.Stream> change) {
        var streams = new ArrayList<Flowsheet.Stream>();
        for (var stream : sheet.streams()) streams.add(stream.id().equals(id) ? change.apply(stream) : stream);
        return with(sheet.units(), streams);
    }

    public @Nullable Flowsheet.Unit unit(String id) {
        for (var unit : sheet.units()) if (unit.id().equals(id)) return unit;
        return null;
    }

    public @Nullable Flowsheet.Stream stream(String id) {
        for (var stream : sheet.streams()) if (stream.id().equals(id)) return stream;
        return null;
    }

    // ---- Units ----

    /** A dropped unit's settings, which run: section 5's table. */
    public static Params defaults(String type, Supplier<Params> column) {
        var p = new JsonObject();
        switch (type) {
            case "valve" -> {
                p.addProperty("target", "drop");
                p.addProperty("pressure", 1e5);
                p.addProperty("drop", 1e5);
            }
            case "mixer" -> p.addProperty("inlets", 2);
            case "splitter" -> {
                p.addProperty("mode", "shares");
                var rows = new JsonArray();
                var first = new JsonObject();
                first.addProperty("id", "1");
                first.addProperty("share", .5);
                var second = new JsonObject();
                second.addProperty("id", "2");
                rows.add(first);
                rows.add(second);
                p.add("outlets", rows);
            }
            case "drum" -> {
                p.addProperty("pressure_mode", "inlet");
                p.addProperty("pressure", 101325);
                p.addProperty("drop", 0);
                heat(p);
            }
            case "storage_tank" -> {
                p.addProperty("ambient", 101325);
                heat(p);
            }
            case "exchanger", "kettle" -> {
                p.addProperty("target", "outlet");
                p.addProperty("side", "tube");
                p.addProperty("temperature", 323.15);
                p.addProperty("duty", 1e6);
                p.addProperty("area", 100);
                p.addProperty("tube_drop", .3e5);
                p.addProperty("shell_drop", .3e5);
            }
            case "air_cooler" -> {
                p.addProperty("target", "outlet");
                p.addProperty("temperature", 323.15);
                p.addProperty("duty", 1e6);
                p.addProperty("drop", .2e5);
            }
            case "fired_heater" -> {
                p.addProperty("coils", "series");
                p.addProperty("target", "outlet");
                p.addProperty("temperature", 573.15);
                p.addProperty("duty", 1e7);
                p.addProperty("radiant_drop", .5e5);
                p.addProperty("convection_drop", .3e5);
                p.addProperty("convection_target", "outlet");
                p.addProperty("convection_temperature", 473.15);
                p.addProperty("convection_duty", 5e6);
            }
            case "pump", "compressor" -> {
                p.addProperty("target", "rise");
                p.addProperty("pressure", 5e5);
                p.addProperty("rise", 2e5);
            }
            case "ejector" -> {
                p.addProperty("discharge", EjectorUnit.DISCHARGE_PRESSURE);
                p.addProperty("motive", EjectorUnit.MOTIVE_PRESSURE);
            }
            case "column" -> {
                return column.get();
            }
            default -> {
            }
        }
        return new Params(p);
    }

    private static void heat(JsonObject p) {
        p.addProperty("heat", "none");
        p.addProperty("temperature", 298.15);
        p.addProperty("duty", 0);
    }

    private Set<String> names() {
        var names = new HashSet<String>();
        for (var unit : sheet.units()) names.add(unit.name());
        for (var stream : sheet.streams()) names.add(stream.name());
        return names;
    }

    private static String free(Collection<String> taken, String prefix, int from) {
        int n = from;
        while (taken.contains(prefix + n)) n++;
        return prefix + n;
    }

    /** The tag a unit of {@code type} would take now; a stream's for a feed or product tile. */
    public String nextName(String type) {
        return type.equals(Tiles.FEED) || type.equals(Tiles.PRODUCT) ? nextStream() : free(names(), Tiles.prefix(type) + "-", 101);
    }

    /** The name a new stream would take now. */
    public String nextStream() {
        return free(names(), "S-", 1);
    }

    /** Adds a unit of {@code type} with its top left at the point, tagged by its kind; its id, or null if refused. */
    public @Nullable String addUnit(String type, int x, int y) {
        var ids = sheet.units().stream().map(Flowsheet.Unit::id).toList();
        String id = free(ids, "u", 1);
        var units = new ArrayList<>(sheet.units());
        units.add(new Flowsheet.Unit(id, type, free(names(), Tiles.prefix(type) + "-", 101), x, y, defaults(type, column)));
        return apply(with(units, sheet.streams()), null) ? id : null;
    }

    public boolean moveUnit(String id, int x, int y, @Nullable String key) {
        return apply(withUnit(id, u -> new Flowsheet.Unit(u.id(), u.type(), u.name(), x, y, u.params(), u.unknown())), key);
    }

    /** Takes a unit away; its streams stay, each end that stood on it an open pill at the place it held. */
    public boolean removeUnit(String id) {
        var units = new ArrayList<>(sheet.units());
        units.removeIf(u -> u.id().equals(id));
        var streams = new ArrayList<Flowsheet.Stream>();
        for (var stream : sheet.streams()) {
            boolean start = stream.from() != null && stream.from().unit().equals(id), end = stream.to() != null && stream.to().unit().equals(id);
            if (!start && !end) {
                streams.add(stream);
                continue;
            }
            var route = new ArrayList<>(routed(stream).route());
            if (start) route.addFirst(heldAt(stream.from()));
            if (end) route.addLast(heldAt(stream.to()));
            var from = start ? null : stream.from();
            var to = end ? null : stream.to();
            streams.add(new Flowsheet.Stream(stream.id(), stream.name(), from, to, fed(from, to, stream.feed()), route, stream.unknown()));
        }
        return apply(with(units, streams), null);
    }

    private Flowsheet.Point heldAt(Flowsheet.End end) {
        var place = layout().place(end.unit(), end.port());
        if (place != null) return new Flowsheet.Point(place.x(), place.y());
        var box = layout().box(end.unit());
        return box == null ? new Flowsheet.Point(0, 0) : new Flowsheet.Point(box.rect().x(), box.rect().y());
    }

    /** Sets a unit's settings to {@code params}, which the caller has copied. */
    public boolean setParams(String id, Params params, @Nullable String key) {
        return apply(withUnit(id, u -> new Flowsheet.Unit(u.id(), u.type(), u.name(), u.x(), u.y(), params, u.unknown())), key);
    }

    /** Sets one setting; the unit's other settings, known or not, stay. A row's or a pair's ports follow its settings, and an end that named a port now gone stays. */
    public boolean set(String id, String field, JsonElement value, @Nullable String key) {
        var unit = unit(id);
        if (unit == null) return false;
        var json = unit.params().json().deepCopy();
        json.add(field, value);
        return setParams(id, new Params(json), key);
    }

    /** Sets a text setting. */
    public boolean set(String id, String field, String value, @Nullable String key) {
        var unit = unit(id);
        if (unit == null) return false;
        var json = unit.params().json().deepCopy();
        json.addProperty(field, value);
        return setParams(id, new Params(json), key);
    }

    /** Sets a number setting. */
    public boolean set(String id, String field, double value, @Nullable String key) {
        var unit = unit(id);
        if (unit == null) return false;
        var json = unit.params().json().deepCopy();
        json.addProperty(field, value);
        return setParams(id, new Params(json), key);
    }

    /** Swaps a unit's type, as a parked or removed unit's Replace does: its id, tag, place and streams stay, and an end whose port has gone waits as an open pill. */
    public boolean replaceUnit(String id, String type) {
        return apply(withUnit(id, u -> new Flowsheet.Unit(u.id(), type, u.name(), u.x(), u.y(), defaults(type, column), u.unknown())), null);
    }

    /**
     * A new row's id: one above every id the unit's rows hold and every id its streams' ends name, so a stream left open by a
     * removed row never finds a new one.
     */
    public String rowId(String unit, String... keys) {
        int top = 0;
        var at = unit(unit);
        if (at != null) for (var key : keys) for (var row : at.params().rows(key)) top = Math.max(top, number(row.text("id", "")));
        for (var stream : sheet.streams()) for (var end : new Flowsheet.End[]{stream.from(), stream.to()}) {
            if (end == null || !end.unit().equals(unit)) continue;
            var parts = end.port().split("/");
            if (parts.length > 1) top = Math.max(top, number(parts[1]));
        }
        return Integer.toString(top + 1);
    }

    private static int number(String id) {
        try {
            return Integer.parseInt(id);
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    // ---- Names ----

    /** Renames a unit or a stream. A name in use, by either, is refused. */
    public boolean rename(String id, String name) {
        String text = name.strip();
        if (text.isEmpty()) return refuse(NAME_EMPTY);
        var unit = unit(id);
        var stream = stream(id);
        if (unit != null && unit.name().equals(text) || stream != null && stream.name().equals(text)) {
            refusal = null;
            return true;
        }
        if (names().contains(text)) return refuse(NAME_USED, text);
        if (unit != null) return apply(withUnit(id, u -> new Flowsheet.Unit(u.id(), u.type(), text, u.x(), u.y(), u.params(), u.unknown())), null);
        if (stream == null) return false;
        return apply(withStream(id, s -> new Flowsheet.Stream(s.id(), text, s.from(), s.to(), s.feed(), s.route(), s.unknown())), null);
    }

    // ---- Streams ----

    /** A new stream's id, the first free; its name, likewise. */
    private Flowsheet.Stream fresh(@Nullable Flowsheet.End from, @Nullable Flowsheet.End to, @Nullable Flowsheet.Feed feed, List<Flowsheet.Point> route) {
        var ids = sheet.streams().stream().map(Flowsheet.Stream::id).toList();
        return new Flowsheet.Stream(free(ids, "s", 1), free(names(), "S-", 1), from, to, fed(from, to, feed), route, new JsonObject());
    }

    private static Flowsheet.Feed noFlow() {
        return new Flowsheet.Feed(298.15, 101325, new java.util.LinkedHashMap<>(), List.of());
    }

    /** What enters a unit from an open start is a feed, with no flow until its door sets one: the solver refuses an open start without one, and only a feed's door can give it one. */
    private static Flowsheet.@Nullable Feed fed(@Nullable Flowsheet.End from, @Nullable Flowsheet.End to, @Nullable Flowsheet.Feed feed) {
        return from == null && to != null && feed == null ? noFlow() : feed;
    }

    private @Nullable String added(Flowsheet.Stream stream) {
        var streams = new ArrayList<>(sheet.streams());
        streams.add(stream);
        return apply(with(sheet.units(), streams), null) ? stream.id() : null;
    }

    /** Drops a feed's pill, with no flow until its door sets one; its id, or null if refused. */
    public @Nullable String addFeed(int x, int y) {
        return added(fresh(null, null, noFlow(), List.of(new Flowsheet.Point(x, y))));
    }

    /** Drops a product's pill. */
    public @Nullable String addProduct(int x, int y) {
        return added(fresh(null, null, null, List.of(new Flowsheet.Point(x, y))));
    }

    /** Whether a stream holds a port, or a stream other than {@code but}. */
    private boolean held(SheetLayout.Place place, @Nullable String but) {
        for (var stream : sheet.streams()) {
            if (stream.id().equals(but)) continue;
            var end = place.direction() == com.wormzjl.icheme.science.flowsheet.Port.Direction.OUT ? stream.from() : stream.to();
            if (end != null && end.unit().equals(place.unit()) && end.port().equals(place.port())) return true;
        }
        return false;
    }

    /** Whether {@code place} can take a stream's start, or its end. */
    public boolean takes(SheetLayout.Place place, boolean start, @Nullable String stream) {
        boolean out = place.direction() == com.wormzjl.icheme.science.flowsheet.Port.Direction.OUT;
        return out == start && !held(place, stream);
    }

    private static Flowsheet.End end(SheetLayout.Place place) {
        return new Flowsheet.End(place.unit(), place.port());
    }

    /** Draws a stream between two ports, or from a port to a pill at the point. Either order. Its id, or null if refused. */
    public @Nullable String addStream(SheetLayout.Place from, @Nullable SheetLayout.Place to, int x, int y) {
        boolean start = from.direction() == com.wormzjl.icheme.science.flowsheet.Port.Direction.OUT;
        if (held(from, null)) {
            refuse(PORT_HELD, from.port());
            return null;
        }
        if (to == null) {
            var point = List.of(new Flowsheet.Point(x, y));
            return added(start ? fresh(end(from), null, null, point) : fresh(null, end(from), null, point));
        }
        if (to.direction() == from.direction()) {
            refuse(PORT_SIDE);
            return null;
        }
        if (held(to, null)) {
            refuse(PORT_HELD, to.port());
            return null;
        }
        return added(start ? fresh(end(from), end(to), null, List.of()) : fresh(end(to), end(from), null, List.of()));
    }

    /** Takes a stream off the sheet. */
    public boolean removeStream(String id) {
        var streams = new ArrayList<>(sheet.streams());
        streams.removeIf(s -> s.id().equals(id));
        return apply(with(sheet.units(), streams), null);
    }

    public boolean setFeed(String id, @Nullable Flowsheet.Feed feed, @Nullable String key) {
        return apply(withStream(id, s -> new Flowsheet.Stream(s.id(), s.name(), s.from(), s.to(), feed, s.route(), s.unknown())), key);
    }

    /** {@code stream} with a route point for each pill, as the layout places it, so a later corner cannot pass for one. A lost end's pill has none. */
    private Flowsheet.Stream routed(Flowsheet.Stream stream) {
        var line = layout().line(stream.id());
        if (line == null) return stream;
        var route = new ArrayList<>(stream.route());
        boolean bothOnOne = stream.from() == null && stream.to() == null && route.size() == 1;
        if (stream.from() == null && route.isEmpty() && line.start() != null) route.add(new Flowsheet.Point(line.start().x(), line.start().y()));
        if (stream.to() == null && (route.isEmpty() || stream.from() == null && route.size() == 1 && !bothOnOne) && line.end() != null) route.add(new Flowsheet.Point(line.end().x(), line.end().y()));
        return new Flowsheet.Stream(stream.id(), stream.name(), stream.from(), stream.to(), stream.feed(), route, stream.unknown());
    }

    /**
     * Lands a lifted end: on a port, or, with {@code port} null, in a new pill at the point. The stream keeps its name and every setting,
     * and a pill's point leaves the route when its end lands on a port, for it would pass for a corner.
     */
    public boolean reconnect(String id, boolean start, @Nullable SheetLayout.Place port, int x, int y) {
        var stream = stream(id);
        if (stream == null) return false;
        if (port != null && !takes(port, start, id)) return held(port, id) ? refuse(PORT_HELD, port.port()) : refuse(PORT_SIDE);
        var was = routed(stream);
        var route = new ArrayList<>(was.route());
        boolean open = (start ? stream.from() : stream.to()) == null, oneOfTwo = stream.from() == null && stream.to() == null && route.size() == 1;
        if (open && !route.isEmpty() && !oneOfTwo) {
            if (start) route.removeFirst();
            else route.removeLast();
        }
        if (port == null) {
            if (oneOfTwo) route.clear();
            var point = new Flowsheet.Point(x, y);
            if (oneOfTwo) route.add(was.route().getFirst());
            if (start) route.addFirst(point);
            else route.addLast(point);
        }
        var end = port == null ? null : end(port);
        var from = start ? end : stream.from();
        var to = start ? stream.to() : end;
        return apply(withStream(id, s -> new Flowsheet.Stream(s.id(), s.name(), from, to, fed(from, to, s.feed()), route, s.unknown())), null);
    }

    /** Moves a stream's pill to the point. */
    public boolean movePill(String id, boolean start, int x, int y, @Nullable String key) {
        var stream = stream(id);
        if (stream == null) return false;
        var line = layout().line(id);
        var pill = line == null ? null : start ? line.start() : line.end();
        // A lost end's pill stands by its unit: nothing in the file says where.
        if (pill == null || pill.lost()) return false;
        var route = new ArrayList<>(routed(stream).route());
        int at = start || route.size() == 1 ? 0 : route.size() - 1;
        route.set(at, new Flowsheet.Point(x, y, route.get(at).unknown()));
        return apply(withStream(id, s -> new Flowsheet.Stream(s.id(), s.name(), s.from(), s.to(), s.feed(), route, s.unknown())), key);
    }

    /**
     * Moves a line's segment across itself by the steps: the line's inner corners go into the route, then the segment's two ends move. A segment
     * on an end gets a new corner there, so the port or pill stays.
     */
    public boolean moveSegment(String id, int segment, int dx, int dy, int @Nullable [] from, @Nullable String key) {
        var stream = stream(id);
        var line = layout().line(id);
        if (stream == null || line == null) return false;
        int[] p = from != null ? from : line.points();
        int n = p.length / 2;
        if (segment < 0 || segment >= n - 1) return false;
        var corners = new ArrayList<int[]>();
        for (int i = 0; i < n; i++) {
            boolean moved = i == segment || i == segment + 1;
            if (moved && i == 0) corners.add(new int[]{p[0], p[1]});
            corners.add(moved ? new int[]{p[2 * i] + dx, p[2 * i + 1] + dy} : new int[]{p[2 * i], p[2 * i + 1]});
            if (moved && i == n - 1) corners.add(new int[]{p[2 * i], p[2 * i + 1]});
        }
        var old = routed(stream).route();
        var route = new ArrayList<Flowsheet.Point>();
        if (stream.from() == null && !old.isEmpty()) route.add(old.getFirst());
        for (int i = 1; i < corners.size() - 1; i++) {
            int at = route.size();
            var kept = at < old.size() ? old.get(at).unknown() : new JsonObject();
            route.add(new Flowsheet.Point(corners.get(i)[0], corners.get(i)[1], kept));
        }
        if (stream.to() == null && !old.isEmpty()) route.add(old.getLast());
        return apply(withStream(id, s -> new Flowsheet.Stream(s.id(), s.name(), s.from(), s.to(), s.feed(), route, s.unknown())), key);
    }

    // ---- The sheet ----

    public boolean settings(FlowsheetSolver.Settings settings, @Nullable String key) {
        return apply(new Flowsheet(sheet.basis(), sheet.units(), sheet.streams(), settings, sheet.sizing(), sheet.unknown()), key);
    }

    public boolean basis(Flowsheet.Basis basis, @Nullable String key) {
        return apply(new Flowsheet(basis, sheet.units(), sheet.streams(), sheet.settings(), sheet.sizing(), sheet.unknown()), key);
    }

    // ---- Sizing ----

    /** Takes Size all's sheet, which the caller has copied, as one step: Undo takes it back whole. A sheet holding the same facts adds no step. */
    public boolean sized(Flowsheet next) {
        if (SheetState.same(sheet, next)) return false;
        return apply(next, null);
    }

    /** Sets the sheet's sizing facts. */
    public boolean sizing(SizingFacts facts, @Nullable String key) {
        return apply(new Flowsheet(sheet.basis(), sheet.units(), sheet.streams(), sheet.settings(), facts, sheet.unknown()), key);
    }

    /** Sets the sizing facts and {@code unit}'s settings, which the caller has copied, in one step: a member's count is its column door's (S3b). */
    public boolean sizing(SizingFacts facts, String unit, Params params, @Nullable String key) {
        var units = new ArrayList<Flowsheet.Unit>();
        for (var u : sheet.units()) units.add(u.id().equals(unit) ? new Flowsheet.Unit(u.id(), u.type(), u.name(), u.x(), u.y(), params, u.unknown()) : u);
        return apply(new Flowsheet(sheet.basis(), units, sheet.streams(), sheet.settings(), facts, sheet.unknown()), key);
    }
}
