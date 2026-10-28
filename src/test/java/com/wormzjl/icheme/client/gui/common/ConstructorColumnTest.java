package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.client.gui.common.ConstructorColumn.Category;
import com.wormzjl.icheme.client.gui.common.ConstructorColumn.Equipment;
import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.ScrollView;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
import com.wormzjl.icheme.science.equipment.UnitDesign;
import com.wormzjl.icheme.science.equipment.VesselPlacement;
import com.wormzjl.icheme.science.equipment.VesselPlacement.Form;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The equipment constructor's panel on the GUI kit (batch 2026-10-28-gui-toolkit, GUI_REVIEW.md order 3): every text
 * it names has its English line; every page of every kind fits the door, at full width and narrowed for its bar, with
 * no text cut, steppers of 14 px and other widgets of 40, plates in one line and boxes and units in line; each page
 * shows its keys, the current positions latched; the widgets keep their slots while the values and the labels that
 * follow them change; the footer and the key beside the title fit.
 */
class ConstructorColumnTest {
    private static final Measure M = Measure.fixed(6);
    /** The door's height in the renders (464 by 254 GUI px), and the room the view's bar takes where a column scrolls. */
    private static final int HEIGHT = 254, BAR = ScrollView.BAR + 4;
    private static final List<String> STANDING = List.of("front", "right", "back", "left"), LYING = List.of("front", "top", "back", "bottom");
    private static final String[] ROLES = {"reflux", "feed", "pumparound_draw", "return", "vapour_return", "draw", "steam", "bottoms"};

    private record Case(String name, ConstructorColumn.Page page) {
    }

    private static ConstructorColumn.Design design(Form form, double diameter, int length, int support, int forward, int right, int up) {
        var outlet = form == Form.TRICKLE_BED_REACTOR ? new ConstructorColumn.Outlet(0b1011, 3, STANDING) : new ConstructorColumn.Outlet(1, 0, STANDING);
        var column = form.column() ? new ConstructorColumn.Imported(diameter, length + 2, length / 2 + 1) : null;
        return new ConstructorColumn.Design(form, diameter, length, support, forward, right, up, new ProcessUnit.Bank(right == 0 ? 1 : ProcessUnit.Bank.MAX_PARALLEL,
                right == 0 ? 1 : ProcessUnit.Bank.MAX_SERIES), column, outlet, 16);
    }

    /** Every form at its least and its largest sizes and places, the right and up shifts at their least. */
    private static List<ConstructorColumn.Design> designs() {
        var out = new ArrayList<ConstructorColumn.Design>();
        for (var form : Form.values()) {
            if (form.designed()) {
                var d = UnitDesign.of(form.kind());
                out.add(design(form, d.minSize(), d.minLength(d.minSize()), 1, 1, 0, 0));
                out.add(design(form, d.maxSize(), d.maxLength(d.maxSize()), 16, VesselPlacement.MAX_FORWARD, -VesselPlacement.MAX_SHIFT, -VesselPlacement.MAX_SHIFT));
            } else {
                out.add(design(form, 0.5, 1, 1, 1, 0, 0));
                out.add(design(form, form.column() ? 15 : 10, 64, 16, VesselPlacement.MAX_FORWARD, -VesselPlacement.MAX_SHIFT, -VesselPlacement.MAX_SHIFT));
            }
        }
        return out;
    }

    private static ConstructorColumn.Connections connections(int count, int selected, boolean horizontal, int offset, String way, boolean room, boolean boot, int outlets) {
        return new ConstructorColumn.Connections(count, selected, 2, horizontal ? LYING : STANDING, "12.5", horizontal, offset, way, count < 6, room, boot,
                new ConstructorColumn.Outlet(outlets, 1, STANDING));
    }

    private static List<ConstructorColumn.Connections> connectionCases() {
        return List.of(connections(0, 0, false, 0, null, false, false, 1), connections(0, 0, true, 0, null, true, false, 1),
                connections(2, 1, false, 0, null, false, false, 0b1011), connections(6, 5, false, -3, "left", false, false, 1),
                connections(3, 0, true, 2, "bottom", true, true, 1), connections(3, 2, true, -2, "front", false, false, 1));
    }

    private static ConstructorColumn.Nozzles nozzles(String role, boolean placed, boolean turned, int count) {
        var shown = new ConstructorColumn.Nozzle(role, 9, placed, "41.5", new UnitDesign.Line("nozzle.sump_in", 53), turned, 3, 1);
        return new ConstructorColumn.Nozzles(count, count - 1, shown, STANDING, turned ? 3 : 0);
    }

    private static List<Case> cases(HeatControlColumn.Texts t) {
        var out = new ArrayList<Case>();
        for (var shown : Category.values())
            for (var current : new Equipment[]{Equipment.VERTICAL_DRUM, Equipment.FIXED_BED_GASIFIER})
                out.add(new Case("choose " + shown + " " + current, ConstructorColumn.choose(shown, current, t)));
        for (var d : designs()) out.add(new Case("design " + d.form() + " " + d.diameter(), ConstructorColumn.design(d, t)));
        for (var c : connectionCases()) out.add(new Case("connections " + c, ConstructorColumn.connections(c, t)));
        out.add(new Case("no nozzles", ConstructorColumn.nozzles(new ConstructorColumn.Nozzles(0, 0, null, STANDING, 0), t)));
        for (var role : ROLES)
            for (boolean turned : new boolean[]{false, true}) out.add(new Case("nozzle " + role + " " + turned, ConstructorColumn.nozzles(nozzles(role, !turned, turned, 14), t)));
        for (int channel : new int[]{0, 1}) out.add(new Case("paint " + channel, ConstructorColumn.paint(channel, t)));
        out.add(new Case("templates", ConstructorColumn.templates(TEMPLATES, 0, t)));
        out.add(new Case("no templates, files unreadable", ConstructorColumn.templates(List.of(), 3, t)));
        return out;
    }

    /** The Templates page as the screen fills it: the bundled crude templates and the pot (off), then one of the player's. */
    private static final List<ConstructorColumn.Template> TEMPLATES = List.of(new ConstructorColumn.Template("Bonga", true, "Bonga, bundled"),
            new ConstructorColumn.Template("Tia Juana Light (default)", true, "bundled"), new ConstructorColumn.Template("WTI Light - Export", true, "bundled"),
            new ConstructorColumn.Template("Two-tray pot", false, "builds no column"), new ConstructorColumn.Template("My column", true, "yours"));

    @Test
    void theTemplatesPageListsEveryTemplateAKeyARowTheUnusableOff() {
        var t = new HeatControlColumnTest.Recording("gui.icheme.");
        var page = ConstructorColumn.templates(TEMPLATES, 0, t);
        var keys = buttons(page);
        assertEquals(List.of("template:0", "template:1", "template:2", "template:3", "template:4"), List.copyOf(keys.keySet()));
        assertFalse(keys.get("template:3").active(), "a template that builds no column is off");
        assertTrue(keys.get("template:4").active() && !keys.get("template:4").latched());
        assertEquals("builds no column", keys.get("template:3").hint());
        assertTrue(page.footer().isEmpty() && !page.display(), "Back by the title, no footer, no message display");
        var empty = ConstructorColumn.templates(List.of(), 2, t);
        assertEquals(List.of(t.get(ConstructorColumn.KEY + "templates.none"), t.get(ConstructorColumn.KEY + "templates.unreadable", 2)),
                ((Column.Group) empty.rows().getFirst()).rows().stream().map(r -> ((Column.Line) r).text()).toList());
        var column = buttons(ConstructorColumn.design(design(Form.COLUMN, 2, 20, 3, 3, 0, 0), t));
        assertTrue(column.get("import").active() && column.get("templates").active(), "a column's Templates beside its Import");
        assertFalse(buttons(ConstructorColumn.design(design(Form.VERTICAL, 2, 6, 3, 3, 0, 0), t)).containsKey("templates"), "a drum has no templates");
    }

    @Test
    void everyTextTheConstructorNamesHasItsEnglishLine() {
        var texts = new HeatControlColumnTest.Recording("gui.icheme.");
        cases(texts);
        // The keys the screen reads itself: the title and its key, the message display and its hover, the schematic, the swatches.
        var keys = new ArrayList<>(List.of("change", "change.hint", "importing", "import_needed", "standing", "blocked", "extent", "cells", "volume", "volume.hint",
                "status.hint", "clash.hint", "nozzle.status.hint", "nozzle.unplaced", "nozzle.sump_out", "nozzle.over", "nozzle.under_in", "nozzle.under_out",
                "schematic.feed", "schematic.draws", "schematic.extras", "paint.swatch", "clash.connection", "clash.vapour", "clash.liquid", "clash.saddle",
                "clash.ground", "clash.manway", "clash.boot", "template.bundled", "template.own", "template.unusable"));
        for (var e : Equipment.values()) keys.add("equipment." + e.key() + ".hint");
        for (var key : keys) texts.get(ConstructorColumn.KEY + key, "1", "2");
        assertEquals(Set.of(), texts.missing);
    }

    /** Every button text fits its slot ({@code Skin.centred} cuts at the slot less 6 px). */
    private static void buttonsFit(Column column, String at) {
        for (var p : column.rows())
            if (p.row() instanceof Column.Buttons b)
                for (var spec : b.buttons()) assertTrue(M.width(spec.text()) <= p.slots().get(spec.key()).w() - 6, at + ": a button's text cut: " + spec.text());
    }

    /**
     * No text cut: every line inside its row, every button's text inside its button, every value inside its box; every
     * widget inside its row, a stepper 14 px and any other widget at least 40; plates in one line; units whole; boxes and
     * units in line; one widget per key. At full width and narrowed for the view's bar.
     */
    @Test
    void eachPageFitsTheDoorWithItsBoxesInLine() {
        var texts = new HeatControlColumnTest.Recording("gui.icheme.");
        for (var c : cases(texts))
            for (int width : new int[]{ConstructorColumn.WIDTH, ConstructorColumn.WIDTH - BAR}) {
                var column = Column.place(c.page().rows(), ConstructorColumn.LEFT, DialogColumns.TOP, width, M, Column.Metrics.COMPACT);
                var bounds = column.bounds();
                String at = c.name() + " at " + width;
                for (var p : column.rows()) {
                    assertTrue(p.rect().x() >= bounds.x() && p.rect().right() <= bounds.right(), at);
                    for (var t : p.texts()) assertTrue(M.width(t) <= p.rect().w(), at + ": a text cut: " + t);
                    for (var e : p.slots().entrySet()) {
                        var s = e.getValue();
                        assertTrue(s.inside(p.rect()), at + ": a widget outside its row " + e.getKey());
                        boolean stepper = p.row() instanceof Column.Setting setting && !e.getKey().equals(setting.key());
                        assertTrue(stepper ? s.w() == Column.STEP : s.w() >= 40, at + ": a widget too narrow " + e.getKey() + " " + s);
                    }
                    if (p.row() instanceof Column.Group g) assertEquals(1, p.texts().size(), at + ": a plate in one line: " + g.title());
                    if (p.row() instanceof Column.Setting s) {
                        assertTrue(p.texts().contains(s.unit()), at + ": a unit whole: " + s.unit());
                        assertTrue(M.width(s.value()) <= Column.BOX - 8, at + ": a value wider than its box: " + s.value());
                    }
                    if (p.row() instanceof Column.Reading r) assertTrue(M.width(r.value()) <= Column.BOX - 8, at + ": a value wider than its box: " + r.value());
                    if (p.row() instanceof Column.Field f) assertTrue(p.texts().contains(f.unit()), at + ": a unit whole: " + f.unit());
                }
                buttonsFit(column, at);
                column.slots();
                DeviceColumnTest.assertInLine(column, at);
            }
    }

    /** The footer's push buttons share the door's width, Build the last; their texts and the title's key fit; the column's area lies over the message display. */
    @Test
    void theFooterTheTitleKeyAndTheDisplayFit() {
        var t = new HeatControlColumnTest.Recording("gui.icheme.");
        for (var c : cases(t)) {
            var footer = c.page().footer();
            if (footer.isEmpty()) {
                assertFalse(c.page().display(), c.name() + ": the choice page shows no message display");
                assertEquals(List.of(), ConstructorColumn.footer(HEIGHT, 0), "and no push buttons");
                continue;
            }
            assertTrue(c.page().display(), c.name());
            assertTrue(footer.size() >= 2 && footer.size() <= 3, c.name() + ": two or three push buttons share the width");
            assertEquals("build", footer.getLast().key(), c.name() + ": Build last");
            var slots = ConstructorColumn.footer(HEIGHT, footer.size());
            for (int i = 0; i < footer.size(); i++) {
                var slot = slots.get(i);
                assertTrue(slot.x() >= 10 && slot.right() <= ConstructorColumn.PANEL - 10 && slot.bottom() <= HEIGHT - 8, c.name() + ": " + slot);
                assertTrue(M.width(footer.get(i).text()) <= slot.w() - 6, c.name() + ": a push button's text cut: " + footer.get(i).text());
            }
        }
        for (var key : List.of("change", "back")) assertTrue(M.width(t.get(ConstructorColumn.KEY + key)) <= ConstructorColumn.TITLE_KEY.w() - 6, key);
        assertTrue(ConstructorColumn.TITLE_KEY.right() <= ConstructorColumn.PANEL - 10 && ConstructorColumn.TITLE_KEY.x() >= 6 + ConstructorColumn.TITLE + 8 + 4,
                "the key clears the widest title plate");
        for (int height : new int[]{240, HEIGHT, 360}) {
            var area = ConstructorColumn.area(height, true);
            var display = ConstructorColumn.display(height);
            assertTrue(area.bottom() <= display.y() - 5 - 4, "the column ends 4 px over the display's window");
            assertTrue(display.y() + 10 <= ConstructorColumn.footer(height, 3).getFirst().y() - 4, "the display over the footer");
            assertEquals(height - 8, ConstructorColumn.area(height, false).bottom());
        }
    }

    private static Map<String, Column.Button> buttons(ConstructorColumn.Page page) {
        var out = new LinkedHashMap<String, Column.Button>();
        for (var b : ConstructorColumn.buttons(page.rows())) out.put(b.key(), b);
        for (var b : page.footer()) out.put(b.key(), b);
        return out;
    }

    private static List<String> slots(ConstructorColumn.Page page) {
        return List.copyOf(Column.place(page.rows(), ConstructorColumn.LEFT, DialogColumns.TOP, ConstructorColumn.WIDTH, M, Column.Metrics.COMPACT).slots().keySet());
    }

    /** What each page shows, by its widgets' keys; the current positions latched, the others keys that move them there. */
    @Test
    void eachPageShowsItsKeysWithTheCurrentPositionsLatched() {
        var t = new HeatControlColumnTest.Recording("gui.icheme.");
        var choose = buttons(ConstructorColumn.choose(Category.VESSELS, Equipment.COLUMN, t));
        assertEquals(List.of("category:vessels", "category:heat", "category:machines", "category:reactors", "category:solid_fuel", "kind:vertical_drum", "kind:horizontal_drum",
                "kind:column", "kind:floating_roof_tank", "kind:sphere", "kind:adsorber", "kind:cyclone"), List.copyOf(choose.keySet()));
        assertTrue(choose.get("category:vessels").latched() && !choose.get("category:vessels").active() && choose.get("category:heat").active());
        assertTrue(choose.get("kind:column").latched() && choose.get("kind:column").active() && choose.get("kind:sphere").active() && !choose.get("kind:sphere").latched(),
                "the draft's kind latched, and still pressed to return to its design");
        var elsewhere = buttons(ConstructorColumn.choose(Category.HEAT, Equipment.COLUMN, t));
        assertTrue(elsewhere.values().stream().filter(b -> b.key().startsWith("kind:")).noneMatch(Column.Button::latched), "the draft's kind latched only in its group");
        for (var category : Category.values())
            assertEquals(java.util.Arrays.stream(Equipment.values()).filter(e -> e.category == category).count(),
                    buttons(ConstructorColumn.choose(category, Equipment.PUMP, t)).keySet().stream().filter(k -> k.startsWith("kind:")).count(), category + "'s kinds");

        var drum = ConstructorColumn.design(design(Form.VERTICAL, 2, 6, 3, 3, 0, 0), t);
        assertEquals(List.of("size", "size-", "size+", "length", "length-", "length+", "support", "support-", "support+", "forward", "forward-", "forward+", "right", "right-",
                "right+", "up", "up-", "up+"), slots(drum));
        assertEquals(List.of("nozzles", "paint", "build"), drum.footer().stream().map(Column.Button::key).toList());
        var lying = ConstructorColumn.design(design(Form.CROSSWISE, 2, 6, 3, 3, 0, 0), t);
        assertTrue(buttons(lying).get("axis:crosswise").latched() && buttons(lying).get("axis:lengthwise").active());
        var column = ConstructorColumn.design(design(Form.COLUMN, 2, 20, 3, 3, 0, 0), t);
        assertEquals(List.of("size", "trays", "feed", "support", "support-", "support+", "import"), slots(column).subList(0, 7), "the imported design shown, not stepped");
        var cooler = slots(ConstructorColumn.design(design(Form.AIR_COOLER, 3, 6, 4, 3, 0, 0), t));
        assertTrue(cooler.containsAll(List.of("parallel", "series", "support")));
        var pump = ConstructorColumn.design(design(Form.PUMP, 0.1, 1, 0, 3, 0, 0), t);
        assertFalse(slots(pump).contains("support"), "a pump stands at grade");
        assertEquals(List.of("paint", "build"), pump.footer().stream().map(Column.Button::key).toList(), "a unit has no Nozzles page");
        var reactor = buttons(ConstructorColumn.design(design(Form.TRICKLE_BED_REACTOR, 2, 2, 3, 3, 0, 0), t));
        assertTrue(reactor.get("outlet:3").latched() && reactor.get("outlet:0").active() && !reactor.get("outlet:2").active() && !reactor.get("outlet:2").latched(),
                "the outlet latched where it faces; a side its design does not allow switched off");

        assertEquals(List.of("connection", "connection-", "connection+", "add", "remove"), slots(ConstructorColumn.connections(connections(0, 0, false, 0, null, false, false, 1), t)));
        var none = buttons(ConstructorColumn.connections(connections(0, 0, false, 0, null, false, false, 1), t));
        assertTrue(none.get("add").active() && !none.get("remove").active());
        var standing = ConstructorColumn.connections(connections(2, 1, false, 0, null, false, false, 0b1011), t);
        assertTrue(slots(standing).containsAll(List.of("station", "offset", "side:0", "outlet:1")));
        assertTrue(buttons(standing).get("side:2").latched() && buttons(standing).get("outlet:1").latched());
        assertEquals(List.of("side:0", "side:2", "side:3", "side:1"), buttons(standing).keySet().stream().filter(k -> k.startsWith("side:")).toList(),
                "front, back, left, right");
        var short_ = buttons(ConstructorColumn.connections(connections(3, 2, true, -2, "front", false, false, 1), t));
        assertTrue(short_.get("boot:off").latched() && !short_.get("boot:on").active() && !short_.get("boot:on").latched(), "too short for a boot");
        assertEquals(t.get(ConstructorColumn.KEY + "boot.none"), short_.get("boot:on").text());
        var booted = buttons(ConstructorColumn.connections(connections(3, 0, true, 2, "bottom", true, true, 1), t));
        assertTrue(booted.get("boot:on").latched() && booted.get("boot:off").active());

        var unturned = buttons(ConstructorColumn.nozzles(nozzles("draw", true, false, 14), t));
        assertTrue(unturned.get("face:auto").latched() && unturned.get("face:3").active() && !unturned.get("reset").active());
        assertEquals(t.get(ConstructorColumn.KEY + "nozzle.auto", t.get(ConstructorColumn.KEY + "side.left")), unturned.get("face:auto").text(), "Auto and the side it took");
        var turned = buttons(ConstructorColumn.nozzles(nozzles("draw", true, true, 14), t));
        assertTrue(turned.get("face:3").latched() && turned.get("face:auto").active() && turned.get("reset").active());
        assertEquals(List.of("nozzle", "nozzle-", "nozzle+"), slots(ConstructorColumn.nozzles(new ConstructorColumn.Nozzles(0, 0, null, STANDING, 0), t)));

        var paint = ConstructorColumn.paint(1, t);
        assertEquals(List.of("channel:0", "channel:1", "custom", "default"), slots(paint));
        assertTrue(buttons(paint).get("channel:1").latched() && buttons(paint).get("channel:0").active());
        var palette = DialogColumns.palette(Column.place(paint.rows(), ConstructorColumn.LEFT, DialogColumns.TOP, ConstructorColumn.WIDTH, M, Column.Metrics.COMPACT), 16);
        assertEquals(16, palette.size(), "the palette in the Paint group's gap");
        assertTrue(palette.stream().allMatch(r -> r.w() >= 16));
    }

    private static Map<String, Rect> widgets(ConstructorColumn.Page page, int width) {
        var out = new LinkedHashMap<String, Rect>();
        for (var p : Column.place(page.rows(), ConstructorColumn.LEFT, DialogColumns.TOP, width, M, Column.Metrics.COMPACT).rows())
            if (p.row() instanceof Column.Buttons || p.row() instanceof Column.Setting || p.row() instanceof Column.Field) out.putAll(p.slots());
        return out;
    }

    /**
     * The screen places its widgets at a rebuild and the values and labels again each frame: a step moves no widget
     * (sizes and places to their ends, an offset's label naming its way, a nozzle's height), so a click lands where the
     * player aimed.
     */
    @Test
    void aWidgetKeepsItsSlotWhileTheValuesChange() {
        var t = new HeatControlColumnTest.Recording("gui.icheme.");
        for (int width : new int[]{ConstructorColumn.WIDTH, ConstructorColumn.WIDTH - BAR}) {
            var designs = designs();
            for (int i = 0; i < designs.size(); i += 2) {
                var small = designs.get(i);
                var large = designs.get(i + 1);
                var moved = new ConstructorColumn.Design(small.form(), large.diameter(), large.length(), large.support(), large.forward(), large.right(), large.up(), large.bank(),
                        large.column(), small.outlet(), small.reach());
                assertEquals(widgets(ConstructorColumn.design(small, t), width), widgets(ConstructorColumn.design(moved, t), width), small.form() + " at " + width);
            }
            for (boolean horizontal : new boolean[]{false, true}) {
                var centred = widgets(ConstructorColumn.connections(connections(3, 1, horizontal, 0, null, true, false, 1), t), width);
                for (var way : List.of("front", "back", "left", "right", "top", "bottom"))
                    assertEquals(centred, widgets(ConstructorColumn.connections(connections(3, 1, horizontal, -4, way, true, false, 1), t), width), "offset " + way + " at " + width);
            }
            var placed = new ConstructorColumn.Nozzles(14, 3, new ConstructorColumn.Nozzle("feed", 1, true, "41.5", new UnitDesign.Line("nozzle.over", 9), false, 1, 1), STANDING, 0);
            var unplaced = new ConstructorColumn.Nozzles(14, 3, new ConstructorColumn.Nozzle("feed", 1, false, "", new UnitDesign.Line("nozzle.over", 9), false, 1, 1), STANDING, 0);
            assertEquals(widgets(ConstructorColumn.nozzles(placed, t), width), widgets(ConstructorColumn.nozzles(unplaced, t), width));
        }
    }
}
