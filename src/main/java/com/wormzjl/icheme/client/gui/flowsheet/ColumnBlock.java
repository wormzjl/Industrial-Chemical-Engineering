package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.science.assembly.Blueprint.Slot;
import com.wormzjl.icheme.science.assembly.ColumnBlueprint;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * A column as the sheet draws it (batch 2026-11-02-process-simulator, decision D23): one block, one process model, its parts on a plate inside a solid frame. The frame is the box, its corner the unit's
 * x and y, and the ports stand on it. There is a part for each slot of {@link ColumnBlueprint}, chosen from the door the ports come from, so a drawn part is a part the constructor builds. A pure
 * function of the unit's settings, in design units. No Minecraft classes.
 *
 * <p>Each side keeps section 3.11's rule, in tray order: pairs and inlets left, products right, overhead on top, bottoms below. A line stands on its true tray where it can. Near trays push lines
 * apart, and a pumparound stays whole, because two loops on one wall that interleave cannot be drawn without a crossing: its two lines stay together and elbows in a channel beside the tower take them
 * to their trays. Lines keep their order at both ends, so the channel never crosses.
 */
public final class ColumnBlock {
    /** The frame's bands round the tower; the foot under a kettle; the frame of a column with no elbows. */
    public static final int LEFT = 72, RIGHT = 86, TOP = 62, FOOT = 20, KETTLE_FOOT = 56, WIDTH = LEFT + Symbol.COLUMN_TOP.w + RIGHT, HEIGHT = TOP + 30 + 16 * SheetLayout.SLICES + FOOT;
    private static final int W = Symbol.COLUMN_TOP.w, HEAD = 15, ATTACH = 5, TRACK = 3, CLEAR = 2, TRACKS = 2, RISER = 14, UNDER_REFLUX = 16, MARK = 11;

    public enum Shape {
        TOWER, COOLER, CONDENSER, AIR_CONDENSER, EJECTOR, DRUM, KETTLE, PUMP_DOWN, PUMP_ACROSS, INLINE_PUMP, TEE, VALVE, VALVE_DOWN
    }

    /** What a pipe carries, which sets its colour. */
    public enum Service {
        HOT, COLD, PRODUCT
    }

    /**
     * A part.
     *
     * @param x        the point on its line it stands on
     * @param rect     what a hover finds
     * @param argument its row's id, for a part of a row
     * @param boot     what else a hover finds, which no gate treats as its body: a receiver's boot under its line
     */
    public record Part(Slot slot, Shape shape, int x, int y, Rect rect, String argument, @Nullable Rect boot) {
        /** Whether a hover at ({@code px}, {@code py}) finds it, within {@code reach}. */
        public boolean at(double px, double py, double reach) {
            return near(rect, px, py, reach) || boot != null && near(boot, px, py, reach);
        }

        private static boolean near(Rect r, double px, double py, double reach) {
            return px >= r.x() - reach && px <= r.right() + reach && py >= r.y() - reach && py <= r.bottom() + reach;
        }
    }

    /** A pipe through {@code points}, with an arrow at its end. */
    public record Pipe(Service service, boolean arrow, int[] points) {
    }

    /**
     * A name beside a part, or in it.
     *
     * @param key   under {@code gui.icheme.flowsheet.}
     * @param width the part it fits in, or 0 for a free name
     */
    public record Note(String key, String argument, int x, int y, int width) {
    }

    /** A port's place on the frame. */
    public record Spot(int x, int y, SheetLayout.Side side) {
    }

    /** One line or a pumparound's two on the tower's left, kept together. */
    private static final class Run {
        final String id;
        final int index, rank;
        final int[] trays, attach;
        int middle;

        Run(String id, int index, int rank, int... trays) {
            this.id = id;
            this.index = index;
            this.rank = rank;
            this.trays = trays;
            attach = new int[trays.length];
        }

        boolean loop() {
            return trays.length == 2;
        }

        int up() {
            return loop() ? 15 : 0;
        }

        int down() {
            return loop() ? 14 : 0;
        }

        /** Where its cooler's return line and draw line reach its cooler. */
        int upper() {
            return middle - 5;
        }

        int lower() {
            return upper() + Math.clamp(attach[1] - attach[0], 10, 11);
        }
    }

    private final Rect frame, tower;
    private final int slices;
    private final List<Part> parts = new ArrayList<>();
    private final List<Pipe> pipes = new ArrayList<>();
    private final List<Note> notes = new ArrayList<>();
    private final List<SheetLayout.Mark> marks = new ArrayList<>();
    private final Map<String, Spot> spots = new HashMap<>();

    /** The block of {@code unit}, whose settings {@code door} reads. */
    public static ColumnBlock of(Flowsheet.Unit unit, ColumnDoor door) {
        return new ColumnBlock(unit, door);
    }

    public Rect frame() {
        return frame;
    }

    public Rect tower() {
        return tower;
    }

    public int slices() {
        return slices;
    }

    public List<Part> parts() {
        return parts;
    }

    public List<Pipe> pipes() {
        return pipes;
    }

    public List<Note> notes() {
        return notes;
    }

    /** The names that stay on the tower: its inlets. */
    public List<SheetLayout.Mark> marks() {
        return marks;
    }

    /** Where port {@code id} stands, or null for a port this block does not draw. */
    public Spot spot(String id) {
        return spots.get(id);
    }

    private static List<Run> runs(ColumnDoor door) {
        var runs = new ArrayList<Run>();
        for (int i = 0; i < door.pumparounds().size(); i++) {
            var row = door.pumparounds().get(i);
            runs.add(new Run(row.id(), i + 1, 1, row.returnTray(), row.drawTray()));
        }
        runs.add(new Run("", 0, 2, door.feedStage()));
        for (int i = 0; i < door.steam().size(); i++) runs.add(new Run(door.steam().get(i).id(), i + 1, 3, door.steam().get(i).stage()));
        runs.sort(Comparator.<Run>comparingInt(r -> r.trays[0]).thenComparingInt(r -> r.rank));
        return runs;
    }

    /** The tower's y of stage {@code tray}, from its top: the reflux stands on the door's first tray, the sump on its last, and the door counts both. */
    private static int tray(ColumnDoor door, int tray, int h) {
        return HEAD + Math.clamp(tray, 0, door.stages() + 1) * (h - 2 * HEAD) / Math.max(1, door.stages() + 1);
    }

    /** {@code y} kept in order, {@code gap} apart, between {@code low} and {@code high}; whether it fits. */
    private static boolean spread(int[] y, int gap, int low, int high) {
        for (int i = 0; i < y.length; i++) y[i] = Math.max(y[i], i == 0 ? low : y[i - 1] + gap);
        for (int i = y.length - 1; i >= 0; i--) y[i] = Math.min(y[i], i == y.length - 1 ? high : y[i + 1] - gap);
        return y.length == 0 || y[0] >= low;
    }

    /** The runs' lines and bands, down to {@code high}, and a loop's band down to {@code floor}, for its name stands under it; on a tower {@code h} high; whether they fit. */
    private static boolean settle(List<Run> runs, ColumnDoor door, int h, int high, int floor) {
        var lines = new ArrayList<int[]>();
        for (var run : runs) for (int i = 0; i < run.trays.length; i++) lines.add(new int[]{tray(door, run.trays[i], h)});
        int[] y = new int[lines.size()];
        for (int i = 0; i < y.length; i++) y[i] = lines.get(i)[0];
        if (!spread(y, ATTACH, HEAD, high)) return false;
        int k = 0;
        for (var run : runs) for (int i = 0; i < run.trays.length; i++) run.attach[i] = y[k++];
        int low = -20, last = low - SheetLayout.PITCH;
        for (var run : runs) {
            run.middle = Math.max((run.attach[0] + run.attach[run.attach.length - 1]) / 2, last + SheetLayout.PITCH + run.up());
            last = run.middle + run.down();
        }
        int next = high + SheetLayout.PITCH;
        for (int i = runs.size() - 1; i >= 0; i--) {
            var run = runs.get(i);
            run.middle = Math.min(run.middle, Math.min(next - SheetLayout.PITCH, run.loop() ? floor : high) - run.down());
            next = run.middle - run.up();
        }
        return next >= low;
    }

    /**
     * Single-layer river routing: lines that keep their order from {@code from} to {@code to}, each with one jog. Going down, a line that passes the next one's level jogs after it; going up, before it.
     * A higher track stands nearer {@code to}.
     */
    static int[] tracks(int[] from, int[] to) {
        int[] t = new int[from.length];
        for (int i = from.length - 1; i >= 0; i--)
            if (to[i] > from[i])
                for (int j = i + 1; j < from.length; j++) if (to[j] > from[j] && to[i] >= from[j] - CLEAR) t[i] = Math.max(t[i], t[j] + 1);
        for (int j = 0; j < from.length; j++)
            if (to[j] < from[j])
                for (int i = 0; i < j; i++) if (to[i] < from[i] && to[j] <= from[i] + CLEAR) t[j] = Math.max(t[j], t[i] + 1);
        return t;
    }

    private static int most(int[] t) {
        int most = 0;
        for (int value : t) most = Math.max(most, value);
        return most;
    }

    private ColumnBlock(Flowsheet.Unit unit, ColumnDoor door) {
        var choices = ColumnDoors.choices(unit);
        boolean kettle = ColumnComponents.kettleRequired(door.reboilerDuty()), air = door.condenser() == ColumnComponents.Condenser.AIR_COOLER, ejector = ColumnComponents.ejectorRequired(door.topPressure());
        var runs = runs(door);
        var draws = new ArrayList<>(door.draws());
        draws.sort(Comparator.comparingInt(ColumnDoor.Draw::tray));
        int[] onTower = new int[draws.size()], onFrame = new int[draws.size()];
        int count = SheetLayout.SLICES, h = 30 + 16 * count, last = h - HEAD - 2;
        while (!fits(runs, door, draws, onTower, onFrame, h, last, kettle)) {
            h = 30 + 16 * ++count;
            last = h - HEAD - 2;
        }
        slices = count;

        // Elbows: the channels first, for they set the frame's width.
        var from = new ArrayList<Integer>();
        var to = new ArrayList<Integer>();
        for (var run : runs) {
            from.add(run.loop() ? run.upper() : run.middle);
            to.add(run.attach[0]);
            if (run.loop()) {
                from.add(run.lower());
                to.add(run.attach[1]);
            }
        }
        int[] band = from.stream().mapToInt(Integer::intValue).toArray(), attach = to.stream().mapToInt(Integer::intValue).toArray(), inner = tracks(band, attach), outer = tracks(onFrame, onTower);
        int topTrack = most(inner), left = LEFT + TRACK * Math.max(0, topTrack - TRACKS), deepest = most(outer);

        int bx = unit.x(), by = unit.y(), tx = bx + left, wall = tx + W, br = wall + RIGHT, mid = tx + W / 2, ty = by + TOP, tf = ty + h, bb = tf + (kettle ? KETTLE_FOOT : FOOT);
        frame = new Rect(bx, by, br - bx, bb - by);
        tower = new Rect(tx, ty, W, h);
        part(ColumnBlueprint.COLUMN, Shape.TOWER, tx, ty, tower, "");

        overhead(door, choices, air, ejector, bx, by, tx, wall, br, mid, ty);

        int cooler = bx + 18, pump = cooler + 30, valve = bx + 14, line = 0;
        var inlets = new ArrayList<String>();
        var inletTrays = new ArrayList<Integer>();
        for (var run : runs) {
            int x0 = tx - 5 - TRACK * (topTrack - inner[line++]);
            if (run.loop()) {
                int x1 = tx - 5 - TRACK * (topTrack - inner[line++]), standard = choices.pumparoundPumps().get(run.index - 1) == ColumnComponents.Pump.STANDARD ? 1 : 0;
                loop(run, ty, bx, tx, cooler, pump, x0, x1, standard == 1, door.units().cooler(run.index - 1));
            } else {
                int y = ty + run.middle, a = ty + run.attach[0];
                boolean feed = run.id.isEmpty();
                valve(feed ? ColumnBlueprint.FEED_VALVE : ColumnBlueprint.steamValve(run.index), valve, y, run.id);
                pipe(Service.PRODUCT, false, bx, y, valve - 4, y);
                pipe(Service.PRODUCT, true, valve + 4, y, x0, y, x0, a, tx, a);
                spots.put(feed ? "feed" : "steam/" + run.id, new Spot(bx, y, SheetLayout.Side.LEFT));
                inlets.add(feed ? "port.feed" : "mark.steam");
                inletTrays.add(run.attach[0]);
            }
        }
        int[] named = inletTrays.stream().mapToInt(Integer::intValue).toArray();
        spread(named, MARK, MARK, h - MARK);
        for (int i = 0; i < named.length; i++) marks.add(new SheetLayout.Mark(inlets.get(i), "", mid, ty + named[i]));

        for (int i = 0; i < draws.size(); i++) {
            var row = draws.get(i);
            int y = ty + onFrame[i], a = ty + onTower[i], x = wall + 5 + TRACK * (deepest - outer[i]), vx = wall + 74;
            valve(ColumnBlueprint.drawValve(door.draws().indexOf(row) + 1), vx, y, row.id());
            pipe(Service.PRODUCT, false, wall, a, x, a, x, y, vx - 4, y);
            pipe(Service.PRODUCT, true, vx + 4, y, br, y);
            spots.put("draw/" + row.id(), new Spot(br, y, SheetLayout.Side.RIGHT));
        }

        if (kettle) reboiler(tx, mid, tf, bb, bx, ty + last, door.units().kettle());
        else {
            pipe(Service.PRODUCT, false, mid, tf, mid, tf + 6);
            pipe(Service.PRODUCT, true, mid, tf + 14, mid, bb);
            part(ColumnBlueprint.BOTTOMS_VALVE, Shape.VALVE_DOWN, mid, tf + 10, new Rect(mid - 8, tf + 6, 12, 8), "");
        }
        spots.put("bottoms", new Spot(mid, bb, SheetLayout.Side.BOTTOM));
    }

    private static boolean fits(List<Run> runs, ColumnDoor door, List<ColumnDoor.Draw> draws, int[] onTower, int[] onFrame, int h, int last, boolean kettle) {
        for (int i = 0; i < onTower.length; i++) onTower[i] = onFrame[i] = tray(door, draws.get(i).tray(), h);
        return settle(runs, door, h, kettle ? last - ATTACH : last, kettle ? last - RISER : last) && spread(onTower, ATTACH, HEAD + UNDER_REFLUX, last) && spread(onFrame, SheetLayout.PITCH, HEAD + UNDER_REFLUX, last);
    }

    /** Overhead train at the top right, as the assembly drawing has it: head, ejector, condenser, receiver and its boot, the pump, the tee, a valve on each leg. Cooling water passes over the head to its pair. */
    private void overhead(ColumnDoor door, ColumnComponents.Choices choices, boolean air, boolean ejector, int bx, int by, int tx, int wall, int br, int mid, int ty) {
        int vapour = ty - 20, water = ty - 6, reflux = ty + HEAD, condenser = wall + 18, d0 = condenser + 20, d1 = d0 + 32, oil = d0 + 8, boot = d1 - 9, vx = wall + 74, wash = wall + 23;
        if (ejector) {
            int e0 = wall - 16, e1 = wall + 4;
            pipe(Service.HOT, true, mid, ty, mid, vapour, e0, vapour);
            pipe(Service.HOT, true, e1, vapour, condenser - 8, vapour);
            part(ColumnBlueprint.EJECTOR, Shape.EJECTOR, (e0 + e1) / 2, vapour, new Rect(e0, vapour - 4, e1 - e0, 8), "");
            // Its motive steam (W10) from the frame's left side, under the cooling water's pair, which stands one pitch higher: a line
            // from above would cross the water's supply.
            int motive = ty - 28;
            pipe(Service.HOT, true, bx, motive, e0 + 4, motive, e0 + 4, vapour - 4);
            spots.put("motive", new Spot(bx, motive, SheetLayout.Side.LEFT));
        } else pipe(Service.HOT, true, mid, ty, mid, vapour, condenser - 8, vapour);
        pipe(Service.HOT, true, condenser + 8, vapour, d0, vapour);
        if (air) part(ColumnBlueprint.CONDENSER, Shape.AIR_CONDENSER, condenser, vapour, new Rect(condenser - 8, vapour - 7, 16, 14), "");
        else {
            int cold = ejector ? ty - 56 : ty - 54, back = ejector ? ty - 42 : ty - 34;
            pipe(Service.COLD, true, bx, cold, condenser + 4, cold, condenser + 4, vapour - 7);
            pipe(Service.COLD, true, condenser - 4, vapour - 7, condenser - 4, back, bx, back);
            part(ColumnBlueprint.CONDENSER, Shape.CONDENSER, condenser, vapour, new Rect(condenser - 8, vapour - 8, 16, 16), "");
            spots.put("condenser_in", new Spot(bx, cold, SheetLayout.Side.LEFT));
            spots.put("condenser_out", new Spot(bx, back, SheetLayout.Side.LEFT));
        }
        notes.add(new Note("mark.condenser", "", condenser, vapour + 16, 0));
        // Its units in parallel (S3b): the count over the line into it, under the cooling water's return and clear of an ejector.
        int units = door.units().condenser();
        if (units > 1) notes.add(new Note("mark.units", Integer.toString(units), condenser - 17, vapour - 8, 0));
        parts.add(new Part(ColumnBlueprint.RECEIVER, Shape.DRUM, boot, water, new Rect(d0, vapour - 7, d1 - d0, 14), "", new Rect(boot - 4, vapour + 7, 8, water + 4 - vapour - 7)));
        notes.add(new Note("mark.drum", "", (d0 + d1) / 2, vapour, d1 - d0));

        int valveAt = by + 22;
        pipe(Service.PRODUCT, false, boot, vapour - 7, boot, valveAt + 4);
        pipe(Service.PRODUCT, true, boot, valveAt - 4, boot, by);
        part(ColumnBlueprint.OFFGAS_VALVE, Shape.VALVE_DOWN, boot, valveAt, new Rect(boot - 8, valveAt - 4, 12, 8), "");
        spots.put("overhead", new Spot(boot, by, SheetLayout.Side.TOP));

        pipe(Service.PRODUCT, false, boot + 4, water, vx - 4, water);
        pipe(Service.PRODUCT, true, vx + 4, water, br, water);
        valve(ColumnBlueprint.WATER_VALVE, vx, water, "");
        spots.put("water", new Spot(br, water, SheetLayout.Side.RIGHT));

        int pump = ty - 1;
        boolean standard = choices.refluxPump() == ColumnComponents.Pump.STANDARD;
        pipe(Service.HOT, false, oil, vapour + 7, oil, pump - 6);
        pipe(Service.HOT, true, oil, pump + 6, oil, reflux - 2);
        part(ColumnBlueprint.REFLUX_PUMP, standard ? Shape.PUMP_DOWN : Shape.INLINE_PUMP, oil, pump, standard ? new Rect(oil - 5, pump - 5, 14, 10) : new Rect(oil - 4, pump - 4, 8, 8), "");
        part(ColumnBlueprint.REFLUX_TEE, Shape.TEE, oil, reflux, new Rect(oil - 2, reflux - 2, 4, 4), "");
        pipe(Service.HOT, false, oil, reflux, wash + 4, reflux);
        pipe(Service.HOT, true, wash - 4, reflux, wall, reflux);
        valve(ColumnBlueprint.REFLUX_VALVE, wash, reflux, "");
        notes.add(new Note("mark.reflux", "", wash, reflux + 9, 0));
        pipe(Service.PRODUCT, false, oil, reflux, vx - 4, reflux);
        pipe(Service.PRODUCT, true, vx + 4, reflux, br, reflux);
        valve(ColumnBlueprint.DISTILLATE_VALVE, vx, reflux, "");
        spots.put("distillate", new Spot(br, reflux, SheetLayout.Side.RIGHT));
    }

    /** A pumparound: draw, pump, cooler, return; its cold stream through the cooler from the frame and back. */
    private void loop(Run run, int ty, int bx, int tx, int cooler, int pump, int x0, int x1, boolean standard, int units) {
        int c = ty + run.middle, upper = ty + run.upper(), lower = ty + run.lower(), r = standard ? 5 : 4, top = c - run.up(), foot = c + run.down();
        pipe(Service.COLD, true, bx, top, cooler, top, cooler, c - 8);
        pipe(Service.COLD, true, cooler, c + 8, cooler, foot, bx, foot);
        pipe(Service.HOT, true, tx, ty + run.attach[1], x1, ty + run.attach[1], x1, lower, pump + r + 1, lower);
        pipe(Service.HOT, true, pump - r - 1, lower, cooler + 6, lower);
        pipe(Service.HOT, true, cooler + 6, upper, x0, upper, x0, ty + run.attach[0], tx, ty + run.attach[0]);
        part(ColumnBlueprint.pumparoundCooler(run.index), Shape.COOLER, cooler, c, new Rect(cooler - 8, c - 8, 16, 16), run.id);
        part(ColumnBlueprint.pumparoundPump(run.index), standard ? Shape.PUMP_ACROSS : Shape.INLINE_PUMP, pump, lower, standard ? new Rect(pump - 5, lower - 5, 10, 14) : new Rect(pump - 4, lower - 4, 8, 8), run.id);
        notes.add(new Note("mark.pumparound", run.id, pump, lower + 16, 0));
        // Its coolers in parallel (S3b): the count above its return line, clear of the cold line's elbow.
        if (units > 1) notes.add(new Note("mark.units", Integer.toString(units), cooler + 16, c - 10, 0));
        spots.put("pa/" + run.id + "/in", new Spot(bx, top, SheetLayout.Side.LEFT));
        spots.put("pa/" + run.id + "/out", new Spot(bx, foot, SheetLayout.Side.LEFT));
    }

    /** The kettle at the bottom left, its pair left, its vapour back to the tower's foot, its liquid under it to the bottoms. */
    private void reboiler(int tx, int mid, int tf, int bb, int bx, int back, int units) {
        int k0 = tx - 50, k1 = tx - 6, top = tf + 14, foot = top + 18, level = top + 9, in = tf + 16, out = tf + 30, riser = tx - 28, under = tf + 44, valve = tx - 2;
        pipe(Service.HOT, true, mid, tf, mid, level, k1, level);
        pipe(Service.HOT, true, riser, top, riser, back, tx, back);
        pipe(Service.HOT, true, bx, in, k0, in);
        pipe(Service.HOT, true, k0, out, bx, out);
        pipe(Service.PRODUCT, false, riser, foot, riser, under, valve - 4, under);
        pipe(Service.PRODUCT, true, valve + 4, under, mid, under, mid, bb);
        part(ColumnBlueprint.REBOILER, Shape.KETTLE, (k0 + k1) / 2, level, new Rect(k0, top, k1 - k0, foot - top), "");
        valve(ColumnBlueprint.BOTTOMS_VALVE, valve, under, "");
        notes.add(counted("mark.reboiler", units, (k0 + k1) / 2, level, k1 - k0));
        spots.put("reboiler_in", new Spot(bx, in, SheetLayout.Side.LEFT));
        spots.put("reboiler_out", new Spot(bx, out, SheetLayout.Side.LEFT));
    }

    /** A member's name, with its count of identical units in parallel past one (S3b). */
    private static Note counted(String key, int units, int x, int y, int width) {
        return units == 1 ? new Note(key, "", x, y, width) : new Note(key + "_units", Integer.toString(units), x, y, width);
    }

    private void part(Slot slot, Shape shape, int x, int y, Rect rect, String argument) {
        parts.add(new Part(slot, shape, x, y, rect, argument, null));
    }

    private void valve(Slot slot, int x, int y, String argument) {
        part(slot, Shape.VALVE, x, y, new Rect(x - 4, y - 8, 8, 12), argument);
    }

    private void pipe(Service service, boolean arrow, int... points) {
        var through = new ArrayList<int[]>();
        for (int i = 0; i + 1 < points.length; i += 2) through.add(new int[]{points[i], points[i + 1]});
        pipes.add(new Pipe(service, arrow, SheetLayout.flat(through)));
    }
}
