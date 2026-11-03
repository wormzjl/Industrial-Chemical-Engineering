package com.wormzjl.icheme.client.gui.flowsheet;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wormzjl.icheme.science.assembly.Blueprint.Slot;
import com.wormzjl.icheme.science.assembly.ColumnBlueprint;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.equipment.ColumnTrays;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetSolver;
import com.wormzjl.icheme.science.flowsheet.Params;
import com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The column block (decision D23): over the door's settings, near trays and every row count included, no two lines cross, no line runs through a part and everything stays in the frame; every slot of
 * {@link ColumnBlueprint} has one drawn part, so a new slot fails here; a free pair shows its cooler; an air cooler shows with no ports.
 */
class ColumnBlockTest {
    private static Flowsheet one(Params params) {
        var units = List.of(new Flowsheet.Unit("u", "column", "T-101", 100, 60, params));
        return new Flowsheet(SheetFixture.basis(), units, List.of(), FlowsheetSolver.Settings.DEFAULT);
    }

    private static ColumnBlock block(Params params) {
        return SheetLayout.of(one(params), SheetFixture.M).box("u").block();
    }

    private static Params door(ColumnComponents.Condenser condenser, double reboiler, double top) {
        var door = SheetFixture.column().json().deepCopy();
        door.addProperty("condenser", condenser.name());
        door.addProperty("reboiler_duty", reboiler);
        door.addProperty("top_pressure", top);
        return new Params(door);
    }

    /** The tray of a row: on the sample's trays, all near the head, all near the foot, or anywhere. */
    private static int tray(Random r, int arrangement, int stages) {
        return Math.clamp(switch (arrangement) {
            case 0 -> 1 + r.nextInt(8);
            case 1 -> stages - r.nextInt(8);
            default -> 1 + r.nextInt(stages);
        }, 1, stages);
    }

    private static void sweep(Consumer<Params> check) {
        int count = 0;
        for (int stages : new int[]{6, 20, 40, 62})
            for (int arrangement = 0; arrangement < 3; arrangement++)
                for (var condenser : ColumnComponents.Condenser.values())
                    for (double reboiler : new double[]{0, 18e6})
                        for (double top : new double[]{1.4e5, .3e5})
                            for (int pa = 0; pa <= ColumnTrays.MAX_PUMPAROUNDS; pa++)
                                for (int steam = 0; steam <= ColumnTrays.MAX_STEAM; steam++)
                                    for (int draw = 0; draw <= ColumnTrays.MAX_DRAWS; draw++) {
                                        var r = new Random(count++);
                                        var json = door(condenser, reboiler, top).json();
                                        json.addProperty("stages", stages);
                                        json.addProperty("feed_stage", 1 + r.nextInt(stages + 1));
                                        json.addProperty("reflux_pump", (r.nextBoolean() ? ColumnComponents.Pump.STANDARD : ColumnComponents.Pump.IN_LINE).name());
                                        var pumparounds = new JsonArray();
                                        for (int i = 0; i < pa; i++) {
                                            var row = new JsonObject();
                                            int low = tray(r, arrangement, stages), high = Math.min(stages, low + r.nextInt(4));
                                            row.addProperty("id", Integer.toString(i + 1));
                                            row.addProperty("return_tray", low);
                                            row.addProperty("draw_tray", high);
                                            row.addProperty("duty", 1e7);
                                            row.addProperty("pump", (r.nextBoolean() ? ColumnComponents.Pump.STANDARD : ColumnComponents.Pump.IN_LINE).name());
                                            pumparounds.add(row);
                                        }
                                        json.add("pumparounds", pumparounds);
                                        var steams = new JsonArray();
                                        for (int i = 0; i < steam; i++) {
                                            var row = new JsonObject();
                                            row.addProperty("id", Integer.toString(i + 1));
                                            row.addProperty("stage", Math.min(stages + 1, tray(r, arrangement, stages) + r.nextInt(2)));
                                            row.addProperty("flow", 100);
                                            row.addProperty("temperature", 500);
                                            steams.add(row);
                                        }
                                        json.add("steam", steams);
                                        var draws = new JsonArray();
                                        for (int i = 0; i < draw; i++) {
                                            var row = new JsonObject();
                                            row.addProperty("id", Integer.toString(i + 1));
                                            row.addProperty("tray", tray(r, arrangement, stages));
                                            row.addProperty("flow", 100);
                                            draws.add(row);
                                        }
                                        json.add("draws", draws);
                                        // Member counts (S3b), drawn last so the rows above stand as before them.
                                        json.addProperty("condenser_units", 1 + r.nextInt(ColumnComponents.MAX_UNITS));
                                        json.addProperty("kettle_units", 1 + r.nextInt(ColumnComponents.MAX_UNITS));
                                        for (var row : pumparounds) row.getAsJsonObject().addProperty("units", 1 + r.nextInt(ColumnComponents.MAX_UNITS));
                                        check.accept(new Params(json));
                                    }
        assertTrue(count > 5000, "the sweep ran: " + count);
    }

    /** One straight run of a pipe: x0, y0, x1, y1. */
    private static List<int[]> runs(ColumnBlock.Pipe pipe) {
        var runs = new ArrayList<int[]>();
        int[] p = pipe.points();
        for (int i = 0; i + 3 < p.length; i += 2) runs.add(new int[]{p[i], p[i + 1], p[i + 2], p[i + 3]});
        return runs;
    }

    /** Whether two straight runs meet anywhere but at an end of both, which is a tee. */
    private static boolean meet(int[] a, int[] b) {
        int x0 = Math.max(Math.min(a[0], a[2]), Math.min(b[0], b[2])), x1 = Math.min(Math.max(a[0], a[2]), Math.max(b[0], b[2]));
        int y0 = Math.max(Math.min(a[1], a[3]), Math.min(b[1], b[3])), y1 = Math.min(Math.max(a[1], a[3]), Math.max(b[1], b[3]));
        if (x0 > x1 || y0 > y1) return false;
        if (x0 < x1 || y0 < y1) return true;
        return !(end(a, x0, y0) && end(b, x0, y0));
    }

    private static boolean end(int[] run, int x, int y) {
        return run[0] == x && run[1] == y || run[2] == x && run[3] == y;
    }

    /** Whether a run passes through a part's body, which a line that ends on the part does not reach. */
    private static boolean through(int[] run, ColumnBlock.Part part) {
        var r = part.rect();
        int x0 = r.x() + 2, x1 = r.right() - 2, y0 = r.y() + 2, y1 = r.bottom() - 2;
        return Math.max(run[0], run[2]) > x0 && Math.min(run[0], run[2]) < x1 && Math.max(run[1], run[3]) > y0 && Math.min(run[1], run[3]) < y1;
    }

    private static String label(Params door) {
        var d = ColumnDoor.of(door);
        return "stages " + d.stages() + " feed " + d.feedStage() + " top " + d.topPressure() + " duty " + d.reboilerDuty() + " " + d.condenser() + " " + d.pumparounds() + " " + d.steam() + " " + d.draws();
    }

    @Test
    void noLineCrossesAnotherAtAnySettingOfTheDoor() {
        sweep(door -> {
            var pipes = block(door).pipes();
            for (int i = 0; i < pipes.size(); i++)
                for (int j = i + 1; j < pipes.size(); j++)
                    for (var a : runs(pipes.get(i)))
                        for (var b : runs(pipes.get(j))) if (meet(a, b)) fail(java.util.Arrays.toString(a) + " meets " + java.util.Arrays.toString(b) + ": " + label(door));
        });
    }

    @Test
    void noLineRunsThroughAPartAtAnySettingOfTheDoor() {
        sweep(door -> {
            var block = block(door);
            for (var pipe : block.pipes())
                for (var run : runs(pipe))
                    for (var part : block.parts()) if (through(run, part)) fail(java.util.Arrays.toString(run) + " through " + part.slot() + " " + part.rect() + ": " + label(door));
        });
    }

    /** A note as it stands: 8 high, as wide as its text at the gates' width. */
    private static int[] note(ColumnBlock.Note note, SheetFixture.Texts texts) {
        String text = texts.get("flowsheet." + note.key(), note.argument());
        int w = texts.measure.width(text);
        return new int[]{note.x() - w / 2, note.y() - 4, w, 8};
    }

    @Test
    void noNoteSitsOnALineOrAPartAtAnySettingOfTheDoor() {
        var languages = List.of(new SheetFixture.Texts(), SheetFixture.Texts.chinese());
        sweep(door -> {
            var block = block(door);
            for (var texts : languages)
                for (var named : block.notes()) {
                    if (named.width() > 0) continue;
                    int[] n = note(named, texts);
                    for (var pipe : block.pipes())
                        for (var run : runs(pipe))
                            if (Math.max(run[0], run[2]) >= n[0] && Math.min(run[0], run[2]) <= n[0] + n[2] && Math.max(run[1], run[3]) >= n[1] && Math.min(run[1], run[3]) <= n[1] + n[3])
                                fail(named.key() + " " + named.argument() + " " + java.util.Arrays.toString(n) + " on " + java.util.Arrays.toString(run) + ": " + label(door));
                    for (var part : block.parts()) {
                        var r = part.rect();
                        if (r.right() > n[0] && r.x() < n[0] + n[2] && r.bottom() > n[1] && r.y() < n[1] + n[3]) fail(named.key() + " " + named.argument() + " on " + part.slot() + ": " + label(door));
                    }
                }
        });
    }

    @Test
    void everyInletKeepsItsNameOnTheTower() {
        sweep(door -> {
            var block = block(door);
            var tower = block.tower();
            var marks = block.marks();
            assertEquals(1 + ColumnDoor.of(door).steam().size(), marks.size(), label(door));
            for (int i = 0; i < marks.size(); i++) {
                int y = marks.get(i).y();
                assertTrue(y - 5 >= tower.y() && y + 5 <= tower.bottom(), "on the tower: " + label(door));
                if (i > 0) assertTrue(y - marks.get(i - 1).y() >= 11, "two names clear of each other: " + label(door));
            }
        });
    }

    @Test
    void everythingStaysInTheFrameAtAnySettingOfTheDoor() {
        sweep(door -> {
            var block = block(door);
            var f = block.frame();
            for (var part : block.parts()) {
                var r = part.rect();
                assertTrue(r.x() >= f.x() && r.right() <= f.right() && r.y() >= f.y() && r.bottom() <= f.bottom(), part.slot() + " " + r + " in " + f + ": " + label(door));
            }
            for (var pipe : block.pipes())
                for (var run : runs(pipe)) {
                    assertTrue(run[0] == run[2] || run[1] == run[3], "a pipe goes across or down: " + label(door));
                    for (int i = 0; i < 4; i += 2) assertTrue(run[i] >= f.x() && run[i] <= f.right() && run[i + 1] >= f.y() && run[i + 1] <= f.bottom(), "a pipe in its frame: " + label(door));
                }
        });
    }

    private static ColumnTrays trays(Params params) {
        var door = ColumnDoor.of(params);
        var unit = one(params).units().getFirst();
        var pumparounds = door.pumparounds().stream().map(p -> new ColumnTrays.Pumparound(p.drawTray(), p.returnTray())).toList();
        var components = ColumnComponents.resolve(ColumnDoors.choices(unit), door.topPressure(), door.reboilerDuty(), pumparounds.size());
        return new ColumnTrays(door.stages(), door.feedStage(), door.draws().stream().map(ColumnDoor.Draw::tray).toList(), door.steam().stream().map(ColumnDoor.Steam::stage).toList(), pumparounds, components);
    }

    @Test
    void everySlotTheConstructorBuildsHasOneDrawnPart() {
        var seen = new HashSet<String>();
        sweep(door -> {
            var drawn = block(door).parts().stream().map(ColumnBlock.Part::slot).toList();
            var slots = ColumnBlueprint.of(trays(door)).slots();
            assertEquals(new HashSet<Slot>(slots), new HashSet<>(drawn), label(door));
            assertEquals(slots.size(), drawn.size(), "one part each: " + label(door));
            for (var slot : slots) seen.add(slot.part());
        });
        assertTrue(seen.containsAll(List.of("ejector", "reboiler", "pa_cooler", "pa_pump", "steam_valve", "draw_valve")), "the sweep reached every optional slot: " + seen);
    }

    @Test
    void aFreePairStillShowsItsCooler() {
        var block = block(SheetFixture.column());
        var rows = ColumnDoor.of(SheetFixture.column()).pumparounds();
        assertFalse(rows.isEmpty());
        for (int i = 1; i <= rows.size(); i++) {
            var slot = ColumnBlueprint.pumparoundCooler(i);
            assertEquals(1, block.parts().stream().filter(p -> p.slot().equals(slot) && p.shape() == ColumnBlock.Shape.COOLER).count(), "no stream on PA " + i + ", a utility takes its heat");
        }
        assertEquals(ColumnBlock.Shape.CONDENSER, block.parts().stream().filter(p -> p.slot().equals(ColumnBlueprint.CONDENSER)).findFirst().orElseThrow().shape());
    }

    @Test
    void anAirCooledCondenserShowsWithNoPorts() {
        var params = door(ColumnComponents.Condenser.AIR_COOLER, 0, 1.4e5);
        var block = block(params);
        assertEquals(ColumnBlock.Shape.AIR_CONDENSER, block.parts().stream().filter(p -> p.slot().equals(ColumnBlueprint.CONDENSER)).findFirst().orElseThrow().shape());
        assertNull(block.spot("condenser_in"));
        assertNull(block.spot("condenser_out"));
        assertEquals(0, block.pipes().stream().filter(p -> p.service() == ColumnBlock.Service.COLD).filter(p -> p.points()[1] < block.tower().y() - 20).count(), "no cold line over the head");
        for (var place : SheetLayout.of(one(params), SheetFixture.M).box("u").places()) assertFalse(place.port().startsWith("condenser"), place.port());
    }

    @Test
    void eachPortStandsOnItsSide() {
        var box = SheetLayout.of(one(door(ColumnComponents.Condenser.EXCHANGER, 18e6, 1.4e5)), SheetFixture.M).box("u");
        for (var place : box.places()) {
            String port = place.port();
            var side = port.equals("overhead") ? SheetLayout.Side.TOP : port.equals("bottoms") ? SheetLayout.Side.BOTTOM : port.equals("water") || port.equals("distillate") || port.startsWith("draw/") ? SheetLayout.Side.RIGHT : SheetLayout.Side.LEFT;
            assertEquals(side, place.side(), port);
        }
        var block = box.block();
        assertTrue(block.spot("water").y() < block.spot("distillate").y(), "the boot's water stands above the distillate");
    }

    @Test
    void theSamplesLinesStandOnTheirTrueTrays() {
        var params = SheetFixture.column();
        var door = ColumnDoor.of(params);
        var block = block(params);
        var tower = block.tower();
        for (var row : door.draws()) {
            int y = tower.y() + 15 + row.tray() * (tower.h() - 30) / (door.stages() + 1);
            assertEquals(y, block.spot("draw/" + row.id()).y(), "draw " + row.id());
        }
        for (var pipe : block.pipes()) {
            int[] p = pipe.points();
            if (pipe.service() == ColumnBlock.Service.HOT && (p[0] == tower.x() || p[p.length - 2] == tower.x())) assertEquals(4, p.length, "a pumparound's hot line runs straight: " + java.util.Arrays.toString(p));
        }
    }

    @Test
    void aPartsHoverNamesItAndAClickSelectsTheBlock() {
        var params = door(ColumnComponents.Condenser.EXCHANGER, 18e6, .3e5);
        var editor = SheetFixture.editor(one(params));
        var layout = editor.layout();
        var x = new SheetContext(editor, new SheetState(editor.sheet(), null, null, 0, null), SheetUnits.DEFAULT, new SheetFixture.Texts(), SheetFixture.M, SheetFixture.FLUIDS);
        for (var part : layout.box("u").block().parts()) {
            var r = part.rect();
            double cx = r.x() + r.w() / 2.0, cy = r.y() + r.h() / 2.0;
            assertEquals(part.slot(), layout.part(cx, cy, 1).slot(), "the hover finds what the painter drew");
            var hit = layout.at(cx, cy, 1);
            assertEquals(SheetLayout.Kind.UNIT, hit.kind(), part.slot().toString());
            assertEquals("u", hit.unit());
            String hover = SimulatorPage.partHover(x, part);
            assertFalse(hover.startsWith("?") || hover.contains("assembly.part") || hover.contains("%s"), part.slot() + ": " + hover);
        }
        var receiver = layout.box("u").block().parts().stream().filter(p -> p.slot().equals(ColumnBlueprint.RECEIVER)).findFirst().orElseThrow();
        var boot = receiver.boot();
        assertEquals(ColumnBlueprint.RECEIVER, layout.part(boot.x() + boot.w() / 2.0, boot.y() + boot.h() / 2.0, 1).slot(), "a hover on the boot finds the receiver");
        assertEquals("PA 1 exchanger", SimulatorPage.partHover(x, layout.box("u").block().parts().stream().filter(p -> p.slot().equals(ColumnBlueprint.pumparoundCooler(1))).findFirst().orElseThrow()));
        assertNull(layout.part(10, 10, 1), "off the block");
    }

    /**
     * C3: the door's third condenser key sets air then water, which the block draws as the air cooler, then the water trim on the
     * cooling water's ports, the frame wider by the trim; the trim's stepper counts its shells within 1 to the most.
     */
    @Test
    void theDoorsAirThenWaterKeyDrawsTheTrimAndItsStepperCountsItsShells() {
        var editor = SheetFixture.editor(one(door(ColumnComponents.Condenser.EXCHANGER, 18e6, 1.4e5)));
        var x = new SheetContext(editor, new SheetState(editor.sheet(), null, null, 0, null), SheetUnits.DEFAULT, new SheetFixture.Texts(), SheetFixture.M, SheetFixture.FLUIDS);
        var view = new Door.View();
        int width = editor.layout().box("u").block().frame().w();
        ColumnDoors.door(x, "u", view).actions().button("condenser:air_then_water");
        assertEquals(ColumnComponents.Condenser.AIR_THEN_WATER, ColumnDoor.of(editor.unit("u").params()).condenser());
        var block = editor.layout().box("u").block();
        assertEquals(width + ColumnBlock.TRIM, block.frame().w(), "the frame takes the trim");
        var air = block.parts().stream().filter(p -> p.slot().equals(ColumnBlueprint.CONDENSER)).findFirst().orElseThrow();
        var trim = block.parts().stream().filter(p -> p.slot().equals(ColumnBlueprint.TRIM_CONDENSER)).findFirst().orElseThrow();
        assertEquals(ColumnBlock.Shape.AIR_CONDENSER, air.shape());
        assertEquals(ColumnBlock.Shape.CONDENSER, trim.shape());
        assertTrue(air.x() < trim.x(), "the air cooler, then the trim");
        ColumnDoors.door(x, "u", view).actions().button("units/trim+");
        assertEquals(2, ColumnDoor.of(editor.unit("u").params()).units().trim());
        for (int i = 0; i < 3; i++) ColumnDoors.door(x, "u", view).actions().button("units/trim-");
        assertEquals(1, ColumnDoor.of(editor.unit("u").params()).units().trim(), "never under one shell");
        ColumnDoors.door(x, "u", view).actions().button("condenser:air_cooler");
        assertEquals(ColumnComponents.Condenser.AIR_COOLER, ColumnDoor.of(editor.unit("u").params()).condenser());
    }

    @Test
    void tracksKeepLinesInOrder() {
        assertArrayEquals(new int[]{0, 0}, ColumnBlock.tracks(new int[]{10, 40}, new int[]{10, 40}), "straight lines need no jog");
        assertArrayEquals(new int[]{1, 0}, ColumnBlock.tracks(new int[]{10, 30}, new int[]{40, 60}), "going down the upper line turns after the lower");
        assertArrayEquals(new int[]{0, 1}, ColumnBlock.tracks(new int[]{30, 50}, new int[]{10, 25}), "going up the lower line turns after the upper");
    }
}
