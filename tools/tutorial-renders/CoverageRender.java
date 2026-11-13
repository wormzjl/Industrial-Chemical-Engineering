import com.wormzjl.icheme.client.gui.common.ConstructorColumn;
import com.wormzjl.icheme.client.gui.common.ControlColumns;
import com.wormzjl.icheme.client.gui.common.DeviceTour;
import com.wormzjl.icheme.client.gui.common.DeviceTourTest;
import com.wormzjl.icheme.client.gui.common.ScreenTours;
import com.wormzjl.icheme.client.gui.common.ScreenToursTest;
import com.wormzjl.icheme.client.gui.common.TitleStrip;
import com.wormzjl.icheme.client.gui.common.TourAnchors;
import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.PanelSkin;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.ScrollView;
import com.wormzjl.icheme.client.gui.common.kit.Skin;
import com.wormzjl.icheme.client.gui.screens.inventory.FluidDeviceScreen.FluidGuiLayout;
import com.wormzjl.icheme.client.gui.tutorial.TourLayout;
import com.wormzjl.icheme.science.tutorial.guide.Tour;
import com.wormzjl.icheme.science.tutorial.guide.TourFile;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The P6 tour renders (batch 2026-11-12-tutorial-system, tools/tutorial-renders): one sheet for each help tour P6 added,
 * stop by stop, on the variants the tour gates check ({@code DeviceTourTest.samples}, {@code ScreenToursTest}'s builders):
 * a stop stands on the first variant that has its control. The page is drawn from the anchor maps alone: the frame's
 * places (title plate, tabs, message display, glass, footer keys), the door columns through a view scrolled to the lit
 * row as the screen scrolls it ({@link TourAnchors#reveal}), the {@code ?} key (and the theme key where the screen has
 * one), and the tour's box over it ({@code TourLayout}). The device samples are placed with the gates' fixed measure (6 px
 * a letter), so their rows wrap a little earlier than the game's. Run by {@code renderTutorialGuide -PtutorialGuideOnly=p6}.
 */
final class CoverageRender {
    private CoverageRender() {
    }

    /** One variant of a page: the frame's places, its door's columns and their view's foot, the ? key, the theme key (or null). */
    record Page(String name, int w, int h, Map<String, Rect> frame, List<Column> door, int viewBottom, Rect help, Rect theme, Map<String, String> labels, String prefix) {
    }

    /** Dark sheets: two tours, one device page and one dialog page. */
    static final List<String> DARK = List.of("fluid_device.vessel", "control.loop");

    static void render(Path out) throws Exception {
        var m = measure();
        var tours = new LinkedHashMap<String, List<Page>>();
        int w = 464, h = 254;
        // The fluid device screen: every tab's tour and every Overview layout's, but the still's two (P1, P4b).
        for (var s : DeviceTourTest.samples()) {
            if (s.page().startsWith("fluid_device.still")) continue;
            var frame = new LinkedHashMap<>(DeviceTour.frame(w, h, s.tabs(), s.tab(), s.legend(), s.apply(), s.copy(), false));
            var keys = FluidGuiLayout.titleKeys(w, true);
            frame.put("title", TitleStrip.placeKeys(w, keys.size(), title(s.layout()), m).plate());
            var labels = new LinkedHashMap<String, String>();
            for (var tab : s.tabs()) labels.put("tab:" + tab, label(tab));
            labels.put("title", title(s.layout()));
            labels.put("current", "tab:" + s.tab());
            tours.computeIfAbsent(s.page(), k -> new ArrayList<>()).add(new Page(s.name(), w, h, frame, s.door().apply(w), h - FluidGuiLayout.DOOR_FOOT, keys.get(1), keys.get(0), labels, "gui.icheme.fluid."));
        }
        // The dialogs, the datasheet and the machine.
        add(tours, ScreenTours.GAUGE, ScreenToursTest.gauge(246 - 20, m), "Pressure gauge", false);
        add(tours, ScreenTours.PIPELINE_TOOL, ScreenToursTest.pipelineTool(230 - 20, m), "Pipe Laying Tool", false);
        add(tours, ScreenTours.VESSEL, ScreenToursTest.vessel(400, 250, m), "Vertical drum", false);
        add(tours, ScreenTours.MACHINE, ScreenToursTest.machine(w, h, m), "Motor", true);
        for (var page : ScreenTours.CONTROL_PAGES) add(tours, ScreenTours.control(page), ScreenToursTest.control(page, w, h, m), "Control station", false);
        for (var page : ScreenTours.Constructor.values()) {
            String title = page == ScreenTours.Constructor.CHOOSE ? GuideRender.tr.apply("gui.icheme.equipment_constructor.title_short")
                    : page == ScreenTours.Constructor.DESIGN_COLUMN || page == ScreenTours.Constructor.TEMPLATES ? "Distillation column" : "Vertical drum";
            add(tours, page.page(), ScreenToursTest.constructor(page, h, m), title, false);
        }
        var all = TourFile.load();
        int sheets = 0;
        for (var e : tours.entrySet()) {
            var tour = all.get(e.getKey());
            if (tour == null) throw new IllegalStateException("No tour " + e.getKey());
            sheet(out, e.getKey(), tour, e.getValue(), PanelSkin.LIGHT, "");
            if (DARK.contains(e.getKey())) sheet(out, e.getKey(), tour, e.getValue(), PanelSkin.DARK, "-dark");
            sheets++;
        }
        System.out.println("P6 TOURS: " + sheets + " sheets");
    }

    static Measure measure() {
        return new HeatDiagramRender.Java2dCanvas(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics()).measure();
    }

    static String title(DeviceTour.Layout layout) {
        return switch (layout) {
            case CONVERTER -> "Fluid converter";
            case TOWER -> "Cooling tower";
            case HEAT -> "Heat exchanger";
            case PUMP_CURVE, MOVER -> "Pump";
            case VALVE -> "Control valve";
            case PIPE -> "Pipe";
            case VESSEL -> "Vertical drum";
            default -> "Batch still";
        };
    }

    static void add(Map<String, List<Page>> tours, String tour, List<ScreenToursTest.Variant> variants, String title, boolean theme) {
        for (var v : variants) {
            boolean constructor = tour.startsWith("constructor.");
            Rect help = constructor ? ScreenTours.CONSTRUCTOR_HELP : theme ? TitleStrip.keys(v.width(), 2).get(1) : ScreenTours.help(v.width());
            var labels = new LinkedHashMap<String, String>();
            labels.put("title", title);
            // The DCS's list key reads Back on the list itself (ControlPanelScreen).
            // The constructor's title key reads Change on a design page and Back on the others.
            if (constructor && !tour.startsWith("constructor.design")) labels.put("title_key", GuideRender.tr.apply("gui.icheme.equipment_constructor.back"));
            if (tour.equals("control.controllers")) labels.put("controller_list", GuideRender.tr.apply("gui.icheme.control.back"));
            tours.computeIfAbsent(tour, k -> new ArrayList<>()).add(new Page(v.name(), v.width(), v.height(), v.frame(), v.door(), v.viewBottom(), help,
                    theme ? TitleStrip.keys(v.width(), 2).get(0) : null, labels, "gui.icheme."));
        }
    }

    static void sheet(Path out, String name, Tour tour, List<Page> variants, Skin skin, String suffix) throws Exception {
        var pages = new ArrayList<BufferedImage>();
        var titles = new ArrayList<String>();
        for (int i = 0; i < tour.stops().size(); i++) {
            String anchor = tour.stops().get(i).anchor();
            Page shown = null;
            for (var p : variants)
                if (at(p, anchor) != null) {
                    shown = p;
                    break;
                }
            int stop = i;
            var page = shown == null ? variants.getFirst() : shown;
            pages.add(GuideRender.image(page.w(), page.h(), c -> draw(c, skin, page, name, tour, stop)));
            titles.add("Stop " + (i + 1) + ": " + anchor + (shown == null ? "  NOT ON ANY VARIANT" : "  (" + page.name() + ", " + page.w() + " by " + page.h() + ")"));
        }
        HeatDiagramRender.grid(out.resolve("tour-" + name.replace('.', '-') + suffix + ".png"), titles, pages);
    }

    static Rect at(Page p, String anchor) {
        if (anchor.equals("page")) return ScreenTours.page(p.door());
        var r = p.frame().get(anchor);
        return r != null ? r : TourAnchors.find(anchor, p.door(), line(p));
    }

    static Function<String, String> line(Page p) {
        return k -> GuideRender.tr.apply(p.prefix() + k);
    }

    static void draw(Canvas c, Skin skin, Page p, String name, Tour tour, int stop) {
        String anchor = tour.stops().get(stop).anchor();
        boolean constructor = name.startsWith("constructor.");
        if (constructor) {
            c.fill(ConstructorColumn.PANEL, 0, p.w(), p.h(), 0xFF4A6B3A);
            c.text("(the world and the ghost)", ConstructorColumn.PANEL + 30, p.h() / 2, 0xFFE0E0E0);
            skin.page(c, new Rect(0, 0, ConstructorColumn.PANEL, p.h()));
        } else skin.page(c, new Rect(0, 0, p.w(), p.h()));
        Rect lit = null;
        // The frame's places.
        for (var e : p.frame().entrySet()) {
            String key = e.getKey();
            var r = e.getValue();
            if (key.equals(anchor)) lit = r;
            // Apply stands over Copy where a tank shows both (the screen adds Apply last).
            if (key.equals("copy") && p.frame().containsKey("apply")) continue;
            if (key.equals("title")) skin.title(c, r.x() + 4, r.y() + 4, c.measure().cut(p.labels().getOrDefault("title", "Title"), r.w() - 8));
            else if (key.equals("glass")) {
                var box = skin.well(c, r);
                String text = "(the drawing)";
                c.text(text, box.x() + (box.w() - c.measure().width(text)) / 2, box.y() + box.h() / 2 - 4, Palette.MUTED);
            } else if (key.equals("status")) skin.status(c, new Rect(r.x() + 2, r.y() + 3, r.w() - 4, 9), "RUNNING", Palette.ACCENT);
            else if (key.equals("units")) {
                skin.softKeys(c, r);
                skin.softKey(c, new Rect(r.x() + 4, r.y() + 4, Math.max(10, r.w() - 8), 18), "Units", false);
            } else if (key.equals("search") || key.equals("solidFraction")) skin.field(c, r, "", key.equals("search") ? "Search" : "0", false);
            else if (key.startsWith("tab:"))
                skin.button(c, r, p.labels().getOrDefault(key, label(key.substring(4))), key.equals(p.labels().get("current")) ? Skin.Press.CURRENT : Skin.Press.REST, false);
            else skin.button(c, r, p.labels().getOrDefault(key, label(key)), key.equals("apply") || key.equals("save") || key.equals("build") ? Skin.Press.PRIMARY : Skin.Press.REST, false);
        }
        // The door's columns, each through its view, the lit one scrolled to show its row.
        Rect page = anchor.equals("page") ? ScreenTours.page(p.door()) : null;
        for (var column : p.door()) {
            var b = column.bounds();
            int bottom = Math.max(b.y() + 18, p.viewBottom());
            var view = new ScrollView();
            view.layout(new Rect(b.x() - 2, b.y(), b.w() + 4, bottom - b.y()), b.x() + b.w() + 3, b.h());
            var r = page != null && column == p.door().getLast() ? page : lit == null ? TourAnchors.find(anchor, column, line(p)) : null;
            if (r != null) lit = TourAnchors.reveal(view, r, true);
            c.clip(view.viewport());
            column.draw(c, -view.offset(), skin);
            for (var placed : column.rows()) {
                if (placed.row() instanceof Column.Field f) {
                    var s = placed.slots().get(f.key());
                    if (view.visible(s)) skin.field(c, view.onScreen(s), "", "", false);
                } else if (placed.row() instanceof Column.Buttons buttons)
                    for (var spec : buttons.buttons()) {
                        var s = placed.slots().get(spec.key());
                        if (!view.visible(s)) continue;
                        if (spec.soft()) skin.softKey(c, view.onScreen(s), spec.text(), spec.latched());
                        else skin.button(c, view.onScreen(s), spec.text(), spec.latched() ? Skin.Press.CURRENT : !spec.active() ? Skin.Press.OFF : Skin.Press.REST, false);
                    }
                else if (placed.row() instanceof Column.Setting st && st.steps())
                    for (var spec : List.of(st.minus(), st.plus())) {
                        var s = placed.slots().get(spec.key());
                        if (view.visible(s)) skin.button(c, view.onScreen(s), spec.text(), Skin.Press.REST, false);
                    }
            }
            c.unclip();
            view.drawBar(c, skin);
        }
        if (p.theme() != null) skin.iconKey(c, p.theme(), Skin.Icon.THEME, false);
        skin.iconKey(c, p.help(), Skin.Icon.HELP, false);
        if (lit == null) {
            System.out.println("P6 TOUR " + name + ": no place for stop " + (stop + 1) + " (" + anchor + ")");
            return;
        }
        boolean last = stop == tour.stops().size() - 1;
        TourLayout.of(p.w(), p.h(), lit, GuideRender.tr.apply(tour.key(stop)), stop == 0, last, last && tour.shows(), GuideRender.words(), c.measure()).draw(c, skin, null);
    }

    /** A frame key's English word: the screens' own keys where one exists, else the key itself. */
    static String label(String key) {
        for (String prefix : List.of("gui.icheme.fluid.", "gui.icheme.", "gui.icheme.equipment_constructor.", "gui.icheme.control."))
            for (String k : List.of(key, key + "_button")) {
                String line = GuideRender.tr.apply(prefix + k);
                if (!line.startsWith("?")) return line;
            }
        return switch (key) {
            case "title_key" -> "Change";
            case "controller_list" -> GuideRender.tr.apply("gui.icheme.control.controllers");
            case "load_mixture" -> "Load";
            case "cancel" -> "Cancel";
            case "save" -> "Save";
            case "done" -> "Done";
            case "new_mixture" -> "New";
            default -> key;
        };
    }
}
