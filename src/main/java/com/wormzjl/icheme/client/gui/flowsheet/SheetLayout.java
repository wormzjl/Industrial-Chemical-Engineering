package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.client.PortColours;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetFailure;
import com.wormzjl.icheme.science.flowsheet.Port;
import com.wormzjl.icheme.science.flowsheet.UnitTypes;
import com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * Where a sheet lies (batch 2026-11-02-process-simulator, W6_SCREEN_PLAN.md section 4): each unit's box and port places, each
 * stream's line and pills, and the hit tests, a pure function of the sheet's facts, in design units. No Minecraft classes.
 *
 * <p><b>Pills and {@code Stream.route}: this reads the file's {@code route} field, so it counts as format.</b> {@code route} holds the
 * corners the player gave. An end with no {@code from} or {@code to} has no port, so its pill's place is the route's first or last
 * point, and the rest are corners. A stream with both ends open and one point has one pill, a feed's if it holds a feed. An end
 * that names a port no longer live (a removed row) keeps that end, so the file keeps it and Undo restores it: it draws as an open
 * pill near its unit, and every route point is a corner there. A change of this rule needs a line in the corpus image.
 */
public final class SheetLayout {
    /** A pill's half height, least width and text room; how far a line leaves a port; a port's square. */
    public static final int PILL_HALF = 9, PILL_LEAST = 38, PILL_PAD = 12, STUB = 10, LOOP = 30, PORT = 5;
    /** The least pitch of a column's ports; the room a column's ports leave at each end of its side; a column's fewest tray slices: the height the owner approved. */
    public static final int PITCH = 14, END = 17, SLICES = 14;
    /** The room a column's plate leaves round its name. */
    private static final int PLATE_ROOM = 5;
    /** How many canvas pixels off a thing a click still finds it. */
    public static final double REACH = 3;

    public enum Side {
        LEFT(-1, 0), RIGHT(1, 0), TOP(0, -1), BOTTOM(0, 1);

        public final int dx, dy;

        Side(int dx, int dy) {
            this.dx = dx;
            this.dy = dy;
        }

        boolean across() {
            return dy == 0;
        }
    }

    /** A port's place: its unit and stable port string, its direction, its point on the box, the side it leaves by, its class's colour, whether a stream holds it. */
    public record Place(String unit, String port, Port.Direction direction, boolean required, int x, int y, Side side, int rgb, boolean held) {
    }

    /** A name on a block, at a port's or a pair's middle: its key under {@code gui.icheme.}, its argument, its place. */
    public record Mark(String key, String argument, int x, int y) {
    }

    /**
     * A unit as placed: its box; its symbol, or null for a placeholder; a column's tray slices; its ports; where its tag stands; a column's parts, and its box is their frame.
     *
     * @param tagAnchor as {@code FlowsheetPainter.label}
     */
    public record Box(Flowsheet.Unit unit, @Nullable Symbol symbol, boolean column, Rect rect, int slices, List<Place> places, List<Mark> marks, int tagX, int tagY, float tagAnchor, @Nullable ColumnBlock block) {
        public Box {
            places = List.copyOf(places);
            marks = List.copyOf(marks);
        }

        public boolean placeholder() {
            return symbol == null;
        }
    }

    /**
     * A stream's end with no port.
     *
     * @param start  its start, not its end
     * @param feed   a feed's: outlined in the accent
     * @param lost   its end names a port no longer live, as a removed row leaves it
     * @param routed whether its place is a route point, so moving it moves that point
     */
    public record Pill(String stream, String text, boolean start, int x, int y, int w, boolean feed, boolean lost, boolean routed) {
        public Rect rect() {
            return new Rect(x - w / 2, y - PILL_HALF, w, 2 * PILL_HALF);
        }
    }

    /**
     * A stream as placed.
     *
     * @param points design points, every segment across or down; each end on a port or a pill's edge
     * @param nameX  where its name stands; {@code hasName} false where a pill says it already
     */
    public record Line(Flowsheet.Stream stream, int[] points, @Nullable Pill start, @Nullable Pill end, boolean hasName, int nameX, int nameY, int midX, int midY) {
        public int segments() {
            return points.length / 2 - 1;
        }
    }

    public enum Kind {
        PORT, PILL, NAME, UNIT, LINE
    }

    /** What lies under a point. {@code unit} on a port, a unit's name or a unit; {@code stream} on a pill, a stream's name or a line. */
    public record Hit(Kind kind, @Nullable Place place, @Nullable String unit, @Nullable String stream, int segment, boolean start) {
    }

    private final Flowsheet sheet;
    private final Map<String, Box> boxes = new LinkedHashMap<>();
    private final Map<String, Line> lines = new LinkedHashMap<>();
    private final Measure m;

    private SheetLayout(Flowsheet sheet, Measure m) {
        this.sheet = sheet;
        this.m = m;
    }

    public static SheetLayout of(Flowsheet sheet, Measure m) {
        var layout = new SheetLayout(sheet, m);
        layout.place();
        return layout;
    }

    public Flowsheet sheet() {
        return sheet;
    }

    public List<Box> boxes() {
        return List.copyOf(boxes.values());
    }

    public List<Line> lines() {
        return List.copyOf(lines.values());
    }

    public @Nullable Box box(String unit) {
        return boxes.get(unit);
    }

    public @Nullable Line line(String stream) {
        return lines.get(stream);
    }

    public @Nullable Place place(String unit, String port) {
        var box = boxes.get(unit);
        if (box != null) for (var place : box.places()) if (place.port().equals(port)) return place;
        return null;
    }

    /** A port's colour by its class, the laying tool's. */
    public static int rgb(String port) {
        return switch (port) {
            case "gas", "overhead" -> PortColours.VAPOUR;
            case "oil", "water", "liquid", "distillate", "bottoms" -> PortColours.LIQUID;
            default -> PortColours.BULK;
        };
    }

    // ---- Units ----

    /** The live ports a stream may stand on, and whose ports these are. */
    private record Live(String unit, String port) {
    }

    private void place() {
        var held = new HashSet<Live>();
        var claimed = new HashSet<String>();
        var live = new HashMap<String, List<Port>>();
        for (var unit : sheet.units()) live.put(unit.id(), ports(unit));
        // A port holds one stream, as in the compiler: the first that names it.
        for (var stream : sheet.streams()) {
            if (claims(stream.from(), Port.Direction.OUT, live) && claimed.add("o " + stream.from().unit() + " " + stream.from().port())) held.add(new Live(stream.from().unit(), stream.from().port()));
            if (claims(stream.to(), Port.Direction.IN, live) && claimed.add("i " + stream.to().unit() + " " + stream.to().port())) held.add(new Live(stream.to().unit(), stream.to().port()));
        }
        for (var unit : sheet.units()) boxes.put(unit.id(), box(unit, live.get(unit.id()), held));
        int free = 0;
        for (var stream : sheet.streams()) lines.put(stream.id(), line(stream, held, free++));
    }

    private static boolean claims(@Nullable Flowsheet.End end, Port.Direction direction, Map<String, List<Port>> live) {
        if (end == null || !live.containsKey(end.unit())) return false;
        return live.get(end.unit()).stream().anyMatch(port -> port.id().equals(end.port()) && port.direction() == direction);
    }

    /** A unit's ports: its model's, or, for a unit that cannot say, what its streams name, as its placeholder keeps them. */
    private List<Port> ports(Flowsheet.Unit unit) {
        var model = UnitTypes.model(unit.type());
        if (model != null) try {
            return model.ports(unit.params());
        } catch (FlowsheetFailure noPorts) {
            // A placeholder keeps what its streams name.
        }
        var kept = new ArrayList<Port>();
        var seen = new HashSet<String>();
        for (var stream : sheet.streams()) {
            if (stream.to() != null && stream.to().unit().equals(unit.id()) && seen.add("i" + stream.to().port())) kept.add(Port.optionalIn(stream.to().port()));
            if (stream.from() != null && stream.from().unit().equals(unit.id()) && seen.add("o" + stream.from().port())) kept.add(Port.out(stream.from().port()));
        }
        return kept;
    }

    private boolean runs(Flowsheet.Unit unit) {
        var model = UnitTypes.model(unit.type());
        if (model == null) return false;
        try {
            model.ports(unit.params());
            return true;
        } catch (FlowsheetFailure noPorts) {
            return false;
        }
    }

    private Box box(Flowsheet.Unit unit, List<Port> ports, Set<Live> held) {
        var symbol = runs(unit) ? Tiles.symbol(unit.type()) : null;
        if (symbol == null) return placeholder(unit, ports, held);
        if (unit.type().equals("column")) return column(unit, ports, held);
        int w = symbol.w, h = symbol.h;
        if (symbol == Symbol.MIXER) h = Math.max(h, (ports.size() - 2) * PITCH + 12);
        if (symbol == Symbol.SPLITTER) h = Math.max(h, (ports.size() - 2) * PITCH + 12);
        var at = new ArrayList<Place>();
        int inlets = 0, outlets = 0;
        for (var port : ports) {
            int[] spot = spot(unit, port, w, h, port.direction() == Port.Direction.IN ? inlets : outlets, ports);
            if (port.direction() == Port.Direction.IN) inlets++;
            else outlets++;
            at.add(place(unit, port, unit.x() + spot[0], unit.y() + spot[1], side(spot[2]), held));
        }
        // A lead leaves a top port upward, so the tag stands left of it.
        int lead = at.stream().filter(p -> p.side() == Side.TOP).mapToInt(Place::x).min().orElse(-1);
        boolean clear = lead < 0;
        return new Box(unit, symbol, false, new Rect(unit.x(), unit.y(), w, h), 0, at, List.of(), clear ? unit.x() + w / 2 : lead - 5, unit.y() - 7, clear ? .5f : 1f, null);
    }

    private Place place(Flowsheet.Unit unit, Port port, int x, int y, Side side, Set<Live> held) {
        return new Place(unit.id(), port.id(), port.direction(), port.required(), x, y, side, rgb(port.id()), held.contains(new Live(unit.id(), port.id())));
    }

    private static Side side(int index) {
        return Side.values()[index];
    }

    private static final int L = 0, R = 1, T = 2, B = 3;

    /** A port's spot in its symbol's box and the side it leaves by. */
    private static int[] spot(Flowsheet.Unit unit, Port port, int w, int h, int order, List<Port> ports) {
        String id = port.id();
        boolean in = port.direction() == Port.Direction.IN;
        return switch (unit.type()) {
            case "pump" -> new int[]{in ? 0 : w, 13, in ? L : R};
            case "compressor" -> new int[]{in ? 0 : w, 19, in ? L : R};
            case "mixer" -> in ? new int[]{0, stacked(order, ports.size() - 1, h), L} : new int[]{w, h / 2, R};
            case "splitter" -> in ? new int[]{0, h / 2, L} : new int[]{w, stacked(order, ports.size() - 1, h), R};
            case "drum", "storage_tank" -> switch (id) {
                case "in" -> new int[]{0, h / 2, L};
                case "gas" -> new int[]{w / 2, 0, T};
                case "oil" -> new int[]{w, h / 2, R};
                default -> new int[]{w / 2, h, B};
            };
            case "exchanger" -> switch (id) {
                case "tube_in" -> new int[]{0, 14, L};
                case "tube_out" -> new int[]{w, 14, R};
                case "shell_in" -> new int[]{48, h, B};
                default -> new int[]{16, h, B};
            };
            case "kettle" -> switch (id) {
                case "tube_in" -> new int[]{0, 11, L};
                case "tube_out" -> new int[]{0, 23, L};
                case "feed" -> new int[]{46, h, B};
                case "gas" -> new int[]{44, 0, T};
                default -> new int[]{w, 24, R};
            };
            // The owner's sheet: the cooler's inlet at its right end, the sample's residue running back to the left.
            case "air_cooler" -> new int[]{in ? w : 0, 8, in ? R : L};
            case "fired_heater" -> switch (id) {
                case "convection_in" -> new int[]{7, 20, L};
                case "convection_out" -> new int[]{51, 20, R};
                case "in", "radiant_in" -> new int[]{0, 66, L};
                default -> new int[]{58, 42, R};
            };
            default -> new int[]{in ? 0 : w, h / 2, in ? L : R};
        };
    }

    /** The {@code order}th of {@code n} ports at {@link #PITCH}, about the middle of a box {@code h} high. */
    private static int stacked(int order, int n, int h) {
        return h / 2 + (2 * order - (n - 1)) * PITCH / 2;
    }

    private Box placeholder(Flowsheet.Unit unit, List<Port> ports, Set<Live> held) {
        int w = Symbol.HEATER.w, in = (int) ports.stream().filter(p -> p.direction() == Port.Direction.IN).count(), out = ports.size() - in;
        int h = Math.max(Symbol.HEATER.h, 2 * END + (Math.max(in, out) - 1) * PITCH);
        var at = new ArrayList<Place>();
        int i = 0, o = 0;
        for (var port : ports) {
            boolean inlet = port.direction() == Port.Direction.IN;
            int y = spaced(inlet ? i++ : o++, inlet ? in : out, h);
            at.add(place(unit, port, unit.x() + (inlet ? 0 : w), unit.y() + y, inlet ? Side.LEFT : Side.RIGHT, held));
        }
        return new Box(unit, null, false, new Rect(unit.x(), unit.y(), w, h), 0, at, List.of(), unit.x() + w / 2, unit.y() - 7, .5f, null);
    }

    /** The {@code order}th of {@code n} ports spaced to fill a side {@code h} high. */
    private static int spaced(int order, int n, int h) {
        return n == 1 ? h / 2 : END + order * (h - 2 * END) / (n - 1);
    }

    /** Section 3.11's rule, drawn by {@link ColumnBlock}: its frame is the box, and its ports stand on the frame. */
    private Box column(Flowsheet.Unit unit, List<Port> ports, Set<Live> held) {
        var block = ColumnBlock.of(unit, ColumnDoor.of(unit.params()));
        var at = new ArrayList<Place>();
        for (var port : ports) {
            var spot = block.spot(port.id());
            at.add(place(unit, port, spot.x(), spot.y(), spot.side(), held));
        }
        var frame = block.frame();
        return new Box(unit, Symbol.COLUMN_TOP, true, frame, block.slices(), at, block.marks(), frame.x() + PLATE_ROOM + 6, frame.y(), 0f, block);
    }

    // ---- Lines ----

    /** Where a line ends: a point, the side it leaves by. */
    private record Anchor(int x, int y, Side side) {
    }

    private @Nullable Place live(@Nullable Flowsheet.End end, Port.Direction direction) {
        if (end == null) return null;
        var place = place(end.unit(), end.port());
        return place != null && place.direction() == direction ? place : null;
    }

    private int pillWidth(String text) {
        return Math.max(PILL_LEAST, m.width(text) + PILL_PAD);
    }

    private Line line(Flowsheet.Stream stream, Set<Live> held, int order) {
        var route = new ArrayList<int[]>();
        for (var point : stream.route()) route.add(new int[]{point.x(), point.y()});
        var from = live(stream.from(), Port.Direction.OUT);
        var to = live(stream.to(), Port.Direction.IN);
        // A pill's place is a route point only where its end is open; a lost end's stands near its unit.
        boolean openStart = stream.from() == null, openEnd = stream.to() == null;
        int w = pillWidth(stream.name());
        int[] startAt = openStart && !route.isEmpty() ? route.removeFirst() : null;
        int[] endAt = openEnd && !route.isEmpty() ? route.removeLast() : null;
        if (openStart && openEnd && endAt == null && startAt != null) {
            // Both ends open on one point: one pill, a feed's if it holds a feed.
            boolean feed = stream.feed() != null;
            var pill = new Pill(stream.id(), stream.name(), feed, startAt[0], startAt[1], w, feed, false, true);
            return new Line(stream, new int[0], feed ? pill : null, feed ? null : pill, false, 0, 0, startAt[0], startAt[1]);
        }
        var first = route.isEmpty() ? null : route.getFirst();
        var last = route.isEmpty() ? null : route.getLast();
        if (startAt == null && from == null) startAt = free(stream.from(), to, first, order, w, true);
        if (endAt == null && to == null) endAt = free(stream.to(), from, last, order, w, false);
        Pill start = null, end = null;
        Anchor a, b;
        int[] towardsB = first != null ? first : to != null ? new int[]{to.x(), to.y()} : endAt;
        int[] towardsA = last != null ? last : from != null ? new int[]{from.x(), from.y()} : startAt;
        if (from != null) a = new Anchor(from.x(), from.y(), from.side());
        else {
            boolean routed = openStart && !stream.route().isEmpty();
            start = new Pill(stream.id(), stream.name(), true, startAt[0], startAt[1], w, stream.feed() != null, !openStart, routed);
            a = edge(start, towardsB);
        }
        if (to != null) b = new Anchor(to.x(), to.y(), to.side());
        else {
            boolean routed = openEnd && !stream.route().isEmpty();
            end = new Pill(stream.id(), stream.name(), false, endAt[0], endAt[1], w, false, !openEnd, routed);
            b = edge(end, towardsA);
        }
        int[] points = points(a, b, route);
        int[] name = name(points, stream.name());
        return new Line(stream, points, start, end, start == null && end == null, name[0], name[1], name[2], name[3]);
    }

    /** Where a pill stands with no route point for it: out from the port at the line's other end, or near the unit its lost end names. */
    private int[] free(@Nullable Flowsheet.End lost, @Nullable Place other, int @Nullable [] corner, int order, int w, boolean start) {
        if (lost != null) {
            var box = boxes.get(lost.unit());
            if (box != null) {
                int count = 0;
                for (var stream : sheet.streams()) {
                    var end = start ? stream.to() : stream.from();
                    if (end != null && end.unit().equals(lost.unit())) count++;
                }
                int y = box.rect().y() + 12 + Math.floorMod(order, Math.max(1, count + 1)) * (2 * PILL_HALF + 4);
                return new int[]{start ? box.rect().x() - w / 2 - 20 : box.rect().right() + w / 2 + 20, y};
            }
        }
        if (corner != null) return new int[]{corner[0] + (start ? -1 : 1) * (w / 2 + 12), corner[1]};
        if (other != null) return new int[]{other.x() + other.side().dx * (w / 2 + 2 * STUB + 2) + (other.side().across() ? 0 : w / 2 + 2 * STUB), other.y() + other.side().dy * (PILL_HALF + 2 * STUB)};
        return new int[]{40, 20 + 2 * PILL_HALF * order};
    }

    /** A pill's line joins the edge nearer the line's other end. */
    private static Anchor edge(Pill pill, int @Nullable [] towards) {
        boolean left = towards != null && towards[0] < pill.x();
        return new Anchor(pill.x() + (left ? -1 : 1) * pill.w() / 2, pill.y(), left ? Side.LEFT : Side.RIGHT);
    }

    /** An orthogonal line: with no corners, out of each end by {@link #STUB}, then one or two bends, a line between two ports on one face standing {@link #LOOP} off it so it reads as a line and not as the box's edge; with corners, through them, an elbow where two points share neither x nor y. */
    private static int[] points(Anchor a, Anchor b, List<int[]> corners) {
        var out = new ArrayList<int[]>();
        out.add(new int[]{a.x(), a.y()});
        if (corners.isEmpty()) {
            int ax = a.x() + a.side().dx * STUB, ay = a.y() + a.side().dy * STUB, bx = b.x() + b.side().dx * STUB, by = b.y() + b.side().dy * STUB;
            if (a.y() == b.y() && a.side().across() && b.side().across() && a.side().dx > 0 == a.x() < b.x() && a.side() != b.side()) {
                out.add(new int[]{b.x(), b.y()});
                return flat(out);
            }
            if (a.x() == b.x() && !a.side().across() && !b.side().across() && a.side() != b.side() && a.side().dy > 0 == a.y() < b.y()) {
                out.add(new int[]{b.x(), b.y()});
                return flat(out);
            }
            out.add(new int[]{ax, ay});
            if (a.side().across() && b.side().across()) {
                int mid = a.side() != b.side() ? (ax + bx) / 2 : a.x() == b.x() ? a.x() + a.side().dx * LOOP : a.side().dx < 0 ? Math.min(ax, bx) : Math.max(ax, bx);
                out.add(new int[]{mid, ay});
                out.add(new int[]{mid, by});
            } else if (!a.side().across() && !b.side().across()) {
                int mid = a.side() != b.side() ? (ay + by) / 2 : a.y() == b.y() ? a.y() + a.side().dy * LOOP : a.side().dy < 0 ? Math.min(ay, by) : Math.max(ay, by);
                out.add(new int[]{ax, mid});
                out.add(new int[]{bx, mid});
            } else if (a.side().across()) out.add(new int[]{bx, ay});
            else out.add(new int[]{ax, by});
            out.add(new int[]{bx, by});
            out.add(new int[]{b.x(), b.y()});
            return flat(out);
        }
        boolean across = a.side().across();
        var through = new ArrayList<>(corners);
        through.add(new int[]{b.x(), b.y()});
        for (int i = 0; i < through.size(); i++) {
            int[] p = out.getLast(), q = through.get(i);
            if (p[0] != q[0] && p[1] != q[1]) {
                boolean last = i == through.size() - 1;
                // Into an end along the side it leaves by.
                out.add((last ? !b.side().across() : across) ? new int[]{q[0], p[1]} : new int[]{p[0], q[1]});
            }
            int[] r = out.getLast();
            across = r[1] != q[1];
            out.add(q);
        }
        return flat(out);
    }

    /** {@code points} with repeats and points on a straight run left out. */
    static int[] flat(List<int[]> points) {
        var kept = new ArrayList<int[]>();
        for (var p : points) {
            if (!kept.isEmpty() && kept.getLast()[0] == p[0] && kept.getLast()[1] == p[1]) continue;
            if (kept.size() >= 2) {
                int[] a = kept.get(kept.size() - 2), b = kept.getLast();
                boolean straight = a[0] == b[0] && b[0] == p[0] && (b[1] - a[1]) * (p[1] - b[1]) >= 0 || a[1] == b[1] && b[1] == p[1] && (b[0] - a[0]) * (p[0] - b[0]) >= 0;
                if (straight) kept.removeLast();
            }
            kept.add(p);
        }
        int[] flat = new int[kept.size() * 2];
        for (int i = 0; i < kept.size(); i++) {
            flat[2 * i] = kept.get(i)[0];
            flat[2 * i + 1] = kept.get(i)[1];
        }
        return flat;
    }

    /** A stream's name: over its longest across segment, on its longest down one where the name does not fit that; then a place on that segment beside the name, for its tear. */
    private int[] name(int[] p, String text) {
        int across = -1, down = -1, wide = 0, tall = 0;
        for (int i = 0; i + 3 < p.length; i += 2) {
            int dx = Math.abs(p[i + 2] - p[i]), dy = Math.abs(p[i + 3] - p[i + 1]);
            if (dy == 0 && dx > wide) {
                wide = dx;
                across = i;
            }
            if (dx == 0 && dy > tall) {
                tall = dy;
                down = i;
            }
        }
        if (across >= 0 && (wide >= m.width(text) + 4 || down < 0)) {
            int mx = (p[across] + p[across + 2]) / 2, my = p[across + 1];
            return new int[]{mx, my - 8, mx + Math.max(0, Math.min(m.width(text) / 2 + 11, wide / 2 - 8)), my};
        }
        if (down >= 0) {
            int mx = p[down], my = (p[down + 1] + p[down + 3]) / 2;
            return new int[]{mx, my, mx, my + Math.max(0, Math.min(16, tall / 2 - 8))};
        }
        return new int[]{p.length > 0 ? p[0] : 0, p.length > 1 ? p[1] : 0, 0, 0};
    }

    // ---- Hit tests ----

    /** What lies at design point ({@code x}, {@code y}), or null: port, pill, name, unit, line, within {@link #REACH} canvas pixels at {@code zoom}. */
    public @Nullable Hit at(double x, double y, double zoom) {
        double reach = REACH / zoom;
        for (var box : boxes.values())
            for (var place : box.places())
                if (Math.abs(x - place.x()) <= PORT / 2.0 + reach && Math.abs(y - place.y()) <= PORT / 2.0 + reach) return new Hit(Kind.PORT, place, place.unit(), null, 0, false);
        for (var line : lines.values())
            for (var pill : new Pill[]{line.start(), line.end()})
                if (pill != null && pill.rect().contains(x, y)) return new Hit(Kind.PILL, null, null, pill.stream(), 0, pill.start());
        for (var box : boxes.values())
            if (tag(box).contains(x, y)) return new Hit(Kind.NAME, null, box.unit().id(), null, 0, false);
        for (var line : lines.values())
            if (line.hasName() && name(line).contains(x, y)) return new Hit(Kind.NAME, null, null, line.stream().id(), 0, false);
        for (var box : boxes.values())
            if (box.rect().contains(x, y)) return new Hit(Kind.UNIT, null, box.unit().id(), null, 0, false);
        for (var line : lines.values()) {
            int[] p = line.points();
            for (int i = 0; i + 3 < p.length; i += 2) {
                double dx = Math.max(Math.min(p[i], p[i + 2]) - x, x - Math.max(p[i], p[i + 2])), dy = Math.max(Math.min(p[i + 1], p[i + 3]) - y, y - Math.max(p[i + 1], p[i + 3]));
                if (dx <= reach && dy <= reach) return new Hit(Kind.LINE, null, null, line.stream().id(), i / 2, false);
            }
        }
        return null;
    }

    /** The box a unit's tag stands in, as the painter places it at full size: a column's is the plate on its frame's top edge. */
    public Rect tag(Box box) {
        int w = m.width(box.unit().name()) + 2;
        if (box.column()) return new Rect(box.tagX() - PLATE_ROOM, box.tagY() - 6, w + 2 * PLATE_ROOM - 2, 12);
        return new Rect(box.tagX() - (int) (w * box.tagAnchor()), box.tagY() - 6, w, 11);
    }

    /** The part of a column at design point ({@code x}, {@code y}), within {@link #REACH} canvas pixels at {@code zoom}, or null. A valve stands before the tower it may touch. */
    public ColumnBlock.@Nullable Part part(double x, double y, double zoom) {
        double reach = REACH / zoom;
        for (var box : boxes.values()) {
            if (box.block() == null) continue;
            var parts = box.block().parts();
            for (int i = parts.size() - 1; i >= 0; i--) {
                var r = parts.get(i).rect();
                if (x >= r.x() - reach && x <= r.right() + reach && y >= r.y() - reach && y <= r.bottom() + reach) return parts.get(i);
            }
        }
        return null;
    }

    /** The box a stream's name stands in, as the painter places it at full size. */
    public Rect name(Line line) {
        int w = m.width(line.stream().name()) + 2;
        return new Rect(line.nameX() - w / 2, line.nameY() - 6, w, 11);
    }

    /** The sheet's extent, ports and pills too; a sheet with nothing on it has none. */
    public @Nullable Rect bounds() {
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
        var rects = new ArrayList<Rect>();
        for (var box : boxes.values()) {
            rects.add(box.rect());
            rects.add(tag(box));
        }
        for (var line : lines.values()) {
            if (line.start() != null) rects.add(line.start().rect());
            if (line.end() != null) rects.add(line.end().rect());
            int[] p = line.points();
            for (int i = 0; i + 1 < p.length; i += 2) rects.add(new Rect(p[i], p[i + 1], 0, 0));
        }
        for (var r : rects) {
            x0 = Math.min(x0, r.x());
            y0 = Math.min(y0, r.y());
            x1 = Math.max(x1, r.right());
            y1 = Math.max(y1, r.bottom());
        }
        return rects.isEmpty() ? null : new Rect(x0, y0, x1 - x0, y1 - y0);
    }
}
