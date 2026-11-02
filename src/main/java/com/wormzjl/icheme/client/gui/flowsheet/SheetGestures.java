package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.client.gui.common.kit.Rect;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.jetbrains.annotations.Nullable;

/**
 * The mouse and key state machine of the sheet (batch 2026-11-02-process-simulator, plan section 3.5): what is selected, what a gesture
 * overlays. It reads canvas points and a time, so the gates drive it with no screen, and every change goes through the {@link SheetEditor}.
 *
 * <p>Left click selects, double click opens a door, double click on a name renames in place, drag moves a unit or a route's segment. A drag from a
 * free port draws a stream, which lands on release. Right click on a stream lifts the end nearer the click: it follows the cursor with no key held,
 * a left click lands it on a port or ends it in a new pill, and right click or Esc puts it back. A drag on empty canvas pans, a click there closes the panel.
 */
public final class SheetGestures {
    public enum Mode {
        IDLE, PAN, MOVE_UNIT, MOVE_SEGMENT, MOVE_PILL, DRAW, RECONNECT, DROP, NAME
    }

    /** What is selected: a unit, or a stream. */
    public record Selection(boolean unit, String id) {
    }

    /** A name being typed: its unit or stream, and where it stands in design units. */
    public record Naming(Selection target, String text, int x, int y) {
    }

    /**
     * What a gesture draws over the sheet.
     *
     * @param wire    design points of the line it carries, or null
     * @param hidden  the stream it lifted, which the sheet does not draw meanwhile, or null
     * @param freed   the port that stream left, shown free, or null
     * @param rings   the ports that can take the end it carries
     * @param tile    a tile it carries, at ({@code x}, {@code y}), or null
     * @param name    what the line it carries will be called
     * @param x       the cursor in design units
     */
    public record Overlay(int @Nullable [] wire, @Nullable String hidden, SheetLayout.@Nullable Place freed, List<SheetLayout.Place> rings, Tiles.@Nullable Tile tile, @Nullable String name, int x, int y) {
    }

    private static final long DOUBLE = 300;
    public static final int SNAP = 8;
    /** Canvas pixels a press may travel and stay a click. */
    private static final int STILL = 2;

    private final SheetEditor editor;
    private final SheetView view;
    private final Consumer<Selection> door;
    private Mode mode = Mode.IDLE;
    private @Nullable Selection selected;
    private @Nullable Naming naming;
    private double pressX, pressY, lastX, lastY;
    private boolean moved;
    private long lastClick = -DOUBLE * 2;
    private @Nullable SheetLayout.Hit lastHit;
    // The gesture in hand.
    private @Nullable String id;
    private int segment, unitX, unitY;
    private int @Nullable [] points;
    private boolean start;
    private SheetLayout.@Nullable Place port;
    private Tiles.@Nullable Tile tile;
    private String step = "";
    private int steps;

    /** @param door opens a door, on a double click */
    public SheetGestures(SheetEditor editor, SheetView view, Consumer<Selection> door) {
        this.editor = editor;
        this.view = view;
        this.door = door;
    }

    public Mode mode() {
        return mode;
    }

    public @Nullable Selection selected() {
        return selected;
    }

    /** Selects from outside, as a door's Open does; null closes the panel. */
    public void select(@Nullable Selection selection) {
        selected = selection;
    }

    public @Nullable Naming naming() {
        return naming;
    }

    private SheetLayout layout() {
        return editor.layout();
    }

    private static int snap(double value) {
        return (int) Math.round(value / SNAP) * SNAP;
    }

    private @Nullable SheetLayout.Hit hit(double x, double y) {
        return layout().at(view.designX(x), view.designY(y), view.zoom());
    }

    private boolean same(SheetLayout.Hit a, SheetLayout.@Nullable Hit b) {
        return b != null && a.kind() == b.kind() && java.util.Objects.equals(a.unit(), b.unit()) && java.util.Objects.equals(a.stream(), b.stream());
    }

    // ---- The mouse ----

    /** A press at a canvas point; {@code button} 0 left, 1 right; {@code time} in ms. Whether it took it. */
    public boolean press(double x, double y, int button, long time) {
        if (mode == Mode.NAME) commit(naming == null ? "" : naming.text());
        pressX = lastX = x;
        pressY = lastY = y;
        moved = false;
        if (mode == Mode.RECONNECT) return reconnecting(x, y, button);
        if (button == 1) return lift(x, y);
        if (button != 0) return false;
        var hit = hit(x, y);
        boolean twice = hit != null && time - lastClick <= DOUBLE && same(hit, lastHit);
        lastClick = twice ? -DOUBLE * 2 : time;
        lastHit = hit;
        if (hit == null) {
            mode = Mode.PAN;
            return true;
        }
        if (twice) {
            twice(hit);
            return true;
        }
        switch (hit.kind()) {
            case PORT -> {
                selected = new Selection(true, hit.unit());
                if (hit.place().held()) grab(hit.unit());
                else if (editor.editable()) {
                    port = hit.place();
                    mode = Mode.DRAW;
                }
            }
            case UNIT, NAME -> {
                if (hit.unit() != null) {
                    selected = new Selection(true, hit.unit());
                    grab(hit.unit());
                } else selected = new Selection(false, hit.stream());
            }
            case PILL -> {
                selected = new Selection(false, hit.stream());
                id = hit.stream();
                start = hit.start();
                step = "pill " + id + " " + ++steps;
                mode = Mode.MOVE_PILL;
            }
            case LINE -> {
                selected = new Selection(false, hit.stream());
                id = hit.stream();
                segment = hit.segment();
                points = layout().line(id).points().clone();
                step = "segment " + id + " " + ++steps;
                mode = Mode.MOVE_SEGMENT;
            }
        }
        return true;
    }

    private void grab(String unit) {
        var at = editor.unit(unit);
        id = unit;
        unitX = at.x();
        unitY = at.y();
        step = "unit " + id + " " + ++steps;
        mode = Mode.MOVE_UNIT;
    }

    private void twice(SheetLayout.Hit hit) {
        mode = Mode.IDLE;
        if (hit.kind() == SheetLayout.Kind.NAME) {
            if (!editor.editable()) return;
            boolean unit = hit.unit() != null;
            var target = new Selection(unit, unit ? hit.unit() : hit.stream());
            selected = target;
            String text = unit ? editor.unit(target.id()).name() : editor.stream(target.id()).name();
            var box = unit ? layout().tag(layout().box(target.id())) : layout().name(layout().line(target.id()));
            naming = new Naming(target, text, box.x(), box.y());
            mode = Mode.NAME;
            return;
        }
        if (hit.kind() == SheetLayout.Kind.PORT) return;
        var target = hit.unit() != null ? new Selection(true, hit.unit()) : new Selection(false, hit.stream());
        selected = target;
        door.accept(target);
    }

    /** The cursor moved with a button held. */
    public void drag(double x, double y) {
        if (Math.abs(x - pressX) > STILL || Math.abs(y - pressY) > STILL) moved = true;
        double dx = (x - pressX) / view.zoom(), dy = (y - pressY) / view.zoom();
        switch (mode) {
            case PAN -> view.pan(x - lastX, y - lastY);
            case MOVE_UNIT -> {
                if (moved) editor.moveUnit(id, snap(unitX + dx), snap(unitY + dy), step);
            }
            case MOVE_PILL -> {
                if (moved) editor.movePill(id, start, snap(view.designX(x)), snap(view.designY(y)), step);
            }
            case MOVE_SEGMENT -> {
                if (!moved) break;
                boolean across = points[2 * segment + 1] == points[2 * segment + 3];
                editor.moveSegment(id, segment, across ? 0 : snap(dx), across ? snap(dy) : 0, points, step);
            }
            default -> {
            }
        }
        lastX = x;
        lastY = y;
    }

    /** The cursor moved with no button held. */
    public void move(double x, double y) {
        lastX = x;
        lastY = y;
    }

    /** The button came up. */
    public void release(double x, double y, int button) {
        lastX = x;
        lastY = y;
        switch (mode) {
            case PAN -> {
                if (!moved) selected = null;
                mode = Mode.IDLE;
            }
            case MOVE_UNIT, MOVE_PILL, MOVE_SEGMENT -> {
                editor.endStep();
                mode = Mode.IDLE;
            }
            case DRAW -> {
                var hit = hit(x, y);
                boolean near = !moved;
                if (near) {
                    mode = Mode.IDLE;
                    return;
                }
                if (hit != null && hit.kind() == SheetLayout.Kind.PORT) editor.addStream(port, hit.place(), 0, 0);
                else if (hit == null) editor.addStream(port, null, snap(view.designX(x)), snap(view.designY(y)));
                mode = Mode.IDLE;
            }
            case DROP -> {
                if (view.box().contains(x, y)) drop(view.designX(x), view.designY(y));
                mode = Mode.IDLE;
                tile = null;
            }
            default -> {
            }
        }
    }

    private void drop(double dx, double dy) {
        String made;
        if (tile.type().equals(Tiles.FEED)) made = editor.addFeed(snap(dx), snap(dy));
        else if (tile.type().equals(Tiles.PRODUCT)) made = editor.addProduct(snap(dx), snap(dy));
        else {
            var symbol = tile.symbol();
            made = editor.addUnit(tile.type(), snap(dx - symbol.w / 2.0), snap(dy - symbol.h / 2.0));
            if (made != null) {
                selected = new Selection(true, made);
                return;
            }
        }
        if (made != null) selected = new Selection(false, made);
    }

    /** A palette's tile came up off its place: {@code tile} follows the cursor until the button comes up. An unlit tile stays. */
    public boolean carry(Tiles.Tile tile) {
        if (!tile.lit() || !editor.editable()) return false;
        this.tile = tile;
        mode = Mode.DROP;
        return true;
    }

    // ---- Reconnecting ----

    /** The end nearer the point along the line. */
    private static boolean nearerStart(int[] p, int segment, double x, double y) {
        double before = 0, after = 0;
        for (int i = 0; i + 3 < p.length; i += 2) {
            double length = Math.abs(p[i + 2] - p[i]) + Math.abs(p[i + 3] - p[i + 1]);
            if (i / 2 < segment) before += length;
            else if (i / 2 > segment) after += length;
            else {
                double to = Math.abs(x - p[i]) + Math.abs(y - p[i + 1]);
                before += Math.min(to, length);
                after += length - Math.min(to, length);
            }
        }
        return before <= after;
    }

    private boolean lift(double x, double y) {
        var hit = hit(x, y);
        if (hit == null || hit.stream() == null) return false;
        if (!editor.editable()) return true;
        var line = layout().line(hit.stream());
        if (line == null) return false;
        id = hit.stream();
        if (hit.kind() == SheetLayout.Kind.PILL) start = hit.start();
        else if (hit.kind() == SheetLayout.Kind.NAME) start = nearerStart(line.points(), segmentAt(line), view.designX(x), view.designY(y));
        else start = nearerStart(line.points(), hit.segment(), view.designX(x), view.designY(y));
        selected = new Selection(false, id);
        mode = Mode.RECONNECT;
        return true;
    }

    private static int segmentAt(SheetLayout.Line line) {
        return line.segments() / 2;
    }

    private boolean reconnecting(double x, double y, int button) {
        if (button == 1) {
            mode = Mode.IDLE;
            return true;
        }
        if (button != 0) return false;
        var hit = hit(x, y);
        if (hit != null && hit.kind() == SheetLayout.Kind.PORT) {
            if (editor.reconnect(id, start, hit.place(), 0, 0)) mode = Mode.IDLE;
        } else if (hit == null) {
            editor.reconnect(id, start, null, snap(view.designX(x)), snap(view.designY(y)));
            mode = Mode.IDLE;
        }
        return true;
    }

    /** The prompt's stream while an end is lifted, or null. */
    public @Nullable String lifted() {
        return mode == Mode.RECONNECT ? id : null;
    }

    // ---- Keys and names ----

    /** Esc: what is in hand goes back; else the panel closes. Whether it took it, for a screen closes on a free Esc. */
    public boolean escape() {
        switch (mode) {
            case RECONNECT, DRAW, DROP -> {
                mode = Mode.IDLE;
                tile = null;
                return true;
            }
            case NAME -> {
                naming = null;
                mode = Mode.IDLE;
                return true;
            }
            default -> {
                if (selected == null) return false;
                selected = null;
                return true;
            }
        }
    }

    /** Delete: takes what is selected off the sheet. */
    public boolean delete() {
        if (selected == null || mode != Mode.IDLE) return false;
        boolean done = selected.unit() ? editor.removeUnit(selected.id()) : editor.removeStream(selected.id());
        if (done) selected = null;
        return done;
    }

    /** The typed text changes as the player types. */
    public void typed(String text) {
        if (naming != null) naming = new Naming(naming.target(), text, naming.x(), naming.y());
    }

    /** Enter, or a click away: the name lands, or the editor says why not and the name stays open. */
    public boolean commit(String text) {
        if (naming == null) return false;
        boolean done = editor.rename(naming.target().id(), text);
        if (done) {
            naming = null;
            mode = Mode.IDLE;
        }
        return done;
    }

    // ---- What a gesture draws ----

    private List<SheetLayout.Place> rings(boolean startEnd, @Nullable String stream) {
        var rings = new ArrayList<SheetLayout.Place>();
        for (var box : layout().boxes()) for (var place : box.places()) if (editor.takes(place, startEnd, stream)) rings.add(place);
        return rings;
    }

    private static int[] elbow(int x0, int y0, int x1, int y1) {
        int mid = (x0 + x1) / 2;
        return new int[]{x0, y0, mid, y0, mid, y1, x1, y1};
    }

    /** What the gesture in hand draws, or null. */
    public @Nullable Overlay overlay() {
        int cx = (int) Math.round(view.designX(lastX)), cy = (int) Math.round(view.designY(lastY));
        switch (mode) {
            case DROP -> {
                return new Overlay(null, null, null, List.of(), tile, null, snap(cx), snap(cy));
            }
            case DRAW -> {
                boolean from = port.direction() == com.wormzjl.icheme.science.flowsheet.Port.Direction.OUT;
                return new Overlay(elbow(port.x(), port.y(), cx, cy), null, null, rings(!from, null), null, null, cx, cy);
            }
            case RECONNECT -> {
                var line = layout().line(id);
                if (line == null) return null;
                int[] p = line.points();
                int fx = start ? p[p.length - 2] : p[0], fy = start ? p[p.length - 1] : p[1];
                var end = start ? line.stream().from() : line.stream().to();
                var freed = end == null ? null : layout().place(end.unit(), end.port());
                return new Overlay(elbow(fx, fy, cx, cy), id, freed, rings(start, id), null, line.stream().name(), cx, cy);
            }
            default -> {
                return null;
            }
        }
    }

    /** The name's box on the canvas, in design units, for the screen's field. */
    public static Rect nameBox(Naming naming, int width) {
        return new Rect(naming.x(), naming.y() - 2, width, 15);
    }
}
