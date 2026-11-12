import com.wormzjl.icheme.client.gui.common.DeviceColumn;
import com.wormzjl.icheme.client.gui.common.StillDoor;
import com.wormzjl.icheme.client.gui.common.TitleStrip;
import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.PanelSkin;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.ScrollView;
import com.wormzjl.icheme.client.gui.common.kit.Skin;
import com.wormzjl.icheme.client.gui.screens.inventory.FluidDeviceScreen.FluidGuiLayout;
import com.wormzjl.icheme.client.gui.tutorial.GuidePage;
import com.wormzjl.icheme.client.gui.tutorial.TourLayout;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.icheme.science.tutorial.cards.Card;
import com.wormzjl.icheme.science.tutorial.guide.GuideFile;
import com.wormzjl.icheme.science.tutorial.guide.Progress;
import com.wormzjl.icheme.science.tutorial.guide.Tour;
import com.wormzjl.icheme.science.tutorial.guide.TourFile;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The field guide's renders (batch 2026-11-12-tutorial-system, P1), in the game's bitmap font, drawn by the shipped
 * builders ({@link GuidePage}, {@link TourLayout}) on the kit's canvas as {@code tools/heat-gui-renders/GuiPrototypes}
 * draws its pages: the guide at three states in both themes at the design size and at the smallest window's panel; the
 * guide's tour stop by stop; the still page's tour stop by stop (the page built as GuiPrototypes builds its still page);
 * every card at the positions its gate names (P2a: chain, barrel, products, road, three_parts), each with one 320 by 240
 * dark frame; the guide with chapter 1 open. Run by {@code renderTutorial} (render.init.gradle) after the film sheets.
 */
public final class GuideRender {
    static final int SCALE = HeatDiagramRender.SCALE;
    static Function<String, String> tr;

    public static void main(String[] args) throws Exception {
        HeatDiagramRender.lang = HeatDiagramRender.lang(Path.of("src/main/resources/assets/icheme/lang/en_us.json"));
        var jar = Path.of("build/moddev/artifacts/neoforge-21.1.219-client-extra-aka-minecraft-resources.jar");
        HeatDiagramRender.font = Files.exists(jar) ? HeatDiagramRender.GameFont.load(jar) : null;
        tr = key -> HeatDiagramRender.tr(key);
        var out = Path.of(args[0]);
        Files.createDirectories(out);
        guide(out);
        guideTour(out);
        stillTour(out);
        card(out);
    }

    static BufferedImage image(int w, int h, Consumer<Canvas> body) {
        var image = new BufferedImage((w + 8) * SCALE, (h + 8) * SCALE, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.scale(SCALE, SCALE);
        g.translate(4, 4);
        var canvas = new HeatDiagramRender.Java2dCanvas(g);
        canvas.fill(-4, -4, w + 4, h + 4, 0xFF101418);
        body.accept(canvas);
        g.dispose();
        return image;
    }

    static final List<String> STATES = List.of("new", "step-3-current", "chapter-done");

    static Progress state(String name) {
        return switch (name) {
            case "new" -> Progress.NONE;
            case "step-3-current" -> new Progress(Set.of("welcome/guide", "welcome/units"));
            default -> new Progress(Set.copyOf(GuideFile.load().stepIds()));
        };
    }

    static GuidePage.Page page(Canvas c, Progress progress, int w, int h, String card) {
        return page(c, progress, w, h, card == null ? "welcome" : card.substring(0, card.indexOf('/')), card);
    }

    static GuidePage.Page page(Canvas c, Progress progress, int w, int h, String open, String card) {
        return GuidePage.place(new GuidePage.Input(GuideFile.load(), progress, open, card), w, h, c.measure(), tr, tr.apply("gui.icheme.fluid.theme.light"));
    }

    /** The guide's first view: the current step scrolled into the glass, as the screen opens it. */
    static int opening(GuidePage.Page p) {
        var step = p.anchors().get("step");
        return step == null ? 0 : p.reveal(step, 0);
    }

    static void guide(Path out) throws Exception {
        for (var state : STATES) {
            var pages = new ArrayList<BufferedImage>();
            for (var skin : List.of(PanelSkin.LIGHT, PanelSkin.DARK))
                pages.add(image(GuidePage.DESIGN_WIDTH, GuidePage.DESIGN_HEIGHT, c -> {
                    var p = page(c, state(state), GuidePage.DESIGN_WIDTH, GuidePage.DESIGN_HEIGHT, null);
                    p.draw(c, skin, opening(p), null, Card.State.of(0, 0));
                }));
            HeatDiagramRender.grid(out.resolve("guide-" + state + ".png"), List.of("Light, 464 by 254: " + state, "Dark, 464 by 254: " + state), pages);
        }
        var small = new ArrayList<BufferedImage>();
        var titles = new ArrayList<String>();
        for (var state : List.of("new", "step-3-current")) {
            small.add(image(312, 232, c -> {
                var p = page(c, state(state), 312, 232, null);
                p.draw(c, PanelSkin.LIGHT, opening(p), null, Card.State.of(0, 0));
            }));
            titles.add("Light, 320 by 240 window: " + state);
        }
        small.add(image(312, 232, c -> {
            var p = page(c, state("step-3-current"), 312, 232, null);
            p.draw(c, PanelSkin.DARK, p.maxOffset(), null, Card.State.of(0, 0));
        }));
        titles.add("Dark, 320 by 240 window: scrolled to the end");
        HeatDiagramRender.grid(out.resolve("guide-small.png"), titles, small);
        // Chapter 1 open, new.
        var crude = new ArrayList<BufferedImage>();
        for (var skin : List.of(PanelSkin.LIGHT, PanelSkin.DARK))
            crude.add(image(GuidePage.DESIGN_WIDTH, GuidePage.DESIGN_HEIGHT, c -> {
                var p = page(c, Progress.NONE, GuidePage.DESIGN_WIDTH, GuidePage.DESIGN_HEIGHT, "crude", null);
                p.draw(c, skin, 0, null, Card.State.of(0, 0));
            }));
        crude.add(image(312, 232, c -> {
            var p = page(c, Progress.NONE, 312, 232, "crude", null);
            p.draw(c, PanelSkin.DARK, 0, null, Card.State.of(0, 0));
        }));
        crude.add(image(312, 232, c -> {
            var p = page(c, Progress.NONE, 312, 232, "crude", null);
            p.draw(c, PanelSkin.DARK, p.maxOffset(), null, Card.State.of(0, 0));
        }));
        HeatDiagramRender.grid(out.resolve("guide-crude.png"), List.of("Light, 464 by 254: chapter Crude oil, new", "Dark, 464 by 254", "Dark, 320 by 240 window",
                "Dark, 320 by 240 window: scrolled to the end"), crude);
    }

    static TourLayout.Words words() {
        return new TourLayout.Words(tr.apply(Tour.KEY + "back"), tr.apply(Tour.KEY + "next"), tr.apply(Tour.KEY + "done"), tr.apply(Tour.KEY + "close"), tr.apply(Tour.KEY + "show_me"));
    }

    static void guideTour(Path out) throws Exception {
        var tour = TourFile.load().get("guide");
        var pages = new ArrayList<BufferedImage>();
        var titles = new ArrayList<String>();
        for (var size : new int[][]{{GuidePage.DESIGN_WIDTH, GuidePage.DESIGN_HEIGHT}, {312, 232}})
            for (int i = 0; i < tour.stops().size(); i++) {
                int stop = i;
                pages.add(image(size[0], size[1], c -> {
                    var p = page(c, state("step-3-current"), size[0], size[1], null);
                    String anchor = tour.stops().get(stop).anchor();
                    var r = p.anchors().get(anchor);
                    int offset = p.scrolls(anchor) ? p.reveal(r, opening(p)) : opening(p);
                    p.draw(c, PanelSkin.LIGHT, offset, null, Card.State.of(0, 0));
                    var lit = p.scrolls(anchor) ? p.onPanel(r, offset) : r;
                    TourLayout.of(size[0], size[1], lit, tr.apply(tour.key(stop)), stop == 0, stop == tour.stops().size() - 1, false, words(), c.measure()).draw(c, PanelSkin.LIGHT, null);
                }));
                titles.add(size[0] + " by " + size[1] + ", stop " + (i + 1) + ": " + tour.stops().get(i).anchor());
            }
        HeatDiagramRender.grid(out.resolve("tour-guide.png"), titles, pages);
    }

    /** The still's page as GuiPrototypes' batch-still-solid-column builds it, editable, with no controller holding it. */
    static DeviceColumn.Input stillInput() {
        var texts = GuiPrototypes.TEXTS;
        var door = StillDoor.rows(new StillDoor.Input(StillDoor.Heater.SOLID, true, true, true, true, null, null, new StillDoor.Fuel("Coal", "12", "1630", "27.7")), texts);
        String flow = "kg/h";
        var meters = List.of(GuiPrototypes.meter("still_pot_temperature", "118.4", "°C"),
                new DeviceColumn.Meter("still_head_temperature", "86.2", "°C", texts.get("still_head_temperature.hint")),
                new DeviceColumn.Meter("still_level", "0.52", "m", texts.get("still_level.hint", "61.0")),
                new DeviceColumn.Meter("still_heat", "20.2", "kW", texts.get("still_heat.hint", "32.0", "63.0")),
                new DeviceColumn.Meter("still_distillate", "61.4", flow, texts.get("still_distillate.hint")),
                new DeviceColumn.Meter("still_vent", "0.0", flow, texts.get("still_vent.hint")),
                new DeviceColumn.Meter("still_condensed", "100.0", "%", texts.get("still_condensed.hint")),
                new DeviceColumn.Meter("still_reflux_run", "2.0", "", texts.get("still_reflux_run.hint")));
        return new DeviceColumn.Input(Kind.RESERVOIR, true, false, true, "bar", "°C", "/h", DeviceColumn.Valve.NONE, null, false,
                new DeviceColumn.Level(0.61, "305.0", "500.0"), null, door, GuiPrototypes.phases("volume", 61, 0, 39, 0), meters, null, null);
    }

    /** The slot a still tour's anchor names in the door column, or null ({@code reading:<key>} by the meter's index). */
    static String slot(String anchor, DeviceColumn.Input in) {
        if (!anchor.startsWith("reading:")) return anchor;
        for (int i = 0; i < in.readings().size(); i++) if (in.readings().get(i).key().equals(anchor.substring(8))) return "meter:" + i;
        return null;
    }

    static void stillTour(Path out) throws Exception {
        var tour = TourFile.load().get("fluid_device.still");
        var pages = new ArrayList<BufferedImage>();
        var titles = new ArrayList<String>();
        int w = GuidePage.DESIGN_WIDTH, h = GuidePage.DESIGN_HEIGHT;
        pages.add(image(w, h, c -> stillPage(c, PanelSkin.LIGHT, w, h, null, -1, tour)));
        titles.add("The still's page with its ? key, 464 by 254, no tour");
        for (int i = 0; i < tour.stops().size(); i++) {
            int stop = i;
            pages.add(image(w, h, c -> stillPage(c, PanelSkin.LIGHT, w, h, tour.stops().get(stop).anchor(), stop, tour)));
            titles.add("Stop " + (i + 1) + ": " + tour.stops().get(i).anchor());
        }
        HeatDiagramRender.grid(out.resolve("tour-fluid_device-still.png"), titles, pages);
    }

    /** The still's page (FluidDeviceScreen's frame: title strip with ? and theme keys, tabs, status, door, glass, footer), its door scrolled to show {@code anchor}, and the tour's stop over it. */
    static void stillPage(Canvas c, Skin skin, int w, int h, String anchor, int stop, Tour tour) {
        var d = new GuiPrototypes.Door(c, skin, w, h, false);
        skin.page(c, new Rect(0, 0, w, h));
        var strip = TitleStrip.placeKeys(w, 2, "Batch still", c.measure());
        d.strip = strip;
        skin.title(c, TitleStrip.TITLE_X, TitleStrip.TITLE_Y, strip.kind());
        var keys = FluidGuiLayout.titleKeys(w, true);
        skin.iconKey(c, keys.get(0), Skin.Icon.THEME, false);
        skin.iconKey(c, keys.get(1), Skin.Icon.HELP, false);
        d.name("ST-1", true);
        d.tabs(0, "Overview", "Outlets", "Datasheet");
        d.status("DISTILLING", Palette.ACCENT);
        var in = stillInput();
        var rows = DeviceColumn.rows(in, GuiPrototypes.TEXTS);
        int side = FluidGuiLayout.side(w), top = FluidGuiLayout.DOOR_TOP, bottom = h - FluidGuiLayout.DOOR_FOOT;
        var column = Column.place(rows, 10, top, side - 10, d.m, Column.Metrics.COMPACT);
        var view = new ScrollView();
        view.layout(new Rect(8, top, side - 6, bottom - top), side + 3, column.bounds().h());
        Rect lit = null;
        if (anchor != null && anchor.equals("status")) lit = new Rect(8, 56, w - 16, 14);
        else if (anchor != null) {
            var slot = slot(anchor, in);
            var r = slot == null ? null : column.slots().get(slot);
            for (int i = 0; r != null && i < 64 && !view.visible(r); i++) {
                int before = view.offset();
                view.wheel(view.viewport().x() + 2, view.viewport().y() + 2, view.onScreen(r).y() < view.viewport().y() ? 1 : -1);
                if (view.offset() == before) break;
            }
            if (r != null) lit = view.onScreen(r);
        }
        c.clip(view.viewport());
        column.draw(c, -view.offset(), skin);
        var drafts = Map.of("stillFiring", "100", "stillReflux", "2");
        for (var p : column.rows()) {
            if (p.row() instanceof Column.Field f) {
                var r = p.slots().get(f.key());
                if (view.visible(r)) skin.field(c, view.onScreen(r), drafts.getOrDefault(f.key(), ""), "", false);
            } else if (p.row() instanceof Column.Buttons b)
                for (var spec : b.buttons()) {
                    var r = p.slots().get(spec.key());
                    if (!view.visible(r)) continue;
                    if (spec.soft()) skin.softKey(c, view.onScreen(r), spec.text(), false);
                    else skin.button(c, view.onScreen(r), spec.text(), spec.latched() ? Skin.Press.CURRENT : !spec.active() ? Skin.Press.OFF : Skin.Press.REST, false);
                }
        }
        c.unclip();
        view.drawBar(c, skin);
        skin.vDivider(c, side + 13, top, bottom);
        var box = d.well(new Rect(side + 15, 72, w - side - 20, h - 50 - 72));
        GuiPrototypes.contents(d, box, 1, List.of(new String[]{"Component", "Mole %", "kmol"}, new String[]{"n-Hexane", "38.2", "0.71"}, new String[]{"Toluene", "61.8", "1.15"}));
        d.footer(2, "Close", "Apply", "Mole", "°C", "bar", "/h");
        if (stop < 0) return;
        if (lit == null) {
            System.out.println("STILL TOUR: no control for stop " + (stop + 1) + " (" + anchor + ")");
            return;
        }
        boolean last = stop == tour.stops().size() - 1;
        TourLayout.of(w, h, lit, tr.apply(tour.key(stop)), stop == 0, last, last && tour.film() != null, words(), c.measure()).draw(c, skin, null);
    }

    /** One frame of a card sheet: its title, option, control, last press and the presses so far. */
    record Frame(String title, int option, double control, int hit, Set<Integer> visited) {
        static Frame of(String title, int option, double control) {
            return new Frame(title, option, control, -1, Set.of());
        }
    }

    /** A card's sheet: each frame at 464 by 254 in the light theme, and the first frame again dark in the 320 by 240 window. */
    static void sheet(Path out, String step, String name, List<Frame> frames) throws Exception {
        var pages = new ArrayList<BufferedImage>();
        var titles = new ArrayList<String>();
        for (var f : frames) {
            pages.add(image(GuidePage.DESIGN_WIDTH, GuidePage.DESIGN_HEIGHT, c -> {
                var p = page(c, state("step-3-current"), GuidePage.DESIGN_WIDTH, GuidePage.DESIGN_HEIGHT, step);
                p.draw(c, PanelSkin.LIGHT, 0, null, new Card.State(1.3, f.control(), f.option(), f.hit(), f.visited(), Card.CELSIUS));
            }));
            titles.add("Card " + name + ": " + f.title());
        }
        var last = frames.getLast();
        pages.add(image(312, 232, c -> {
            var p = page(c, state("step-3-current"), 312, 232, step);
            p.draw(c, PanelSkin.DARK, 0, null, new Card.State(1.3, last.control(), last.option(), last.hit(), last.visited(), Card.CELSIUS));
        }));
        titles.add("Dark, 320 by 240 window: " + last.title());
        HeatDiagramRender.grid(out.resolve("card-" + name + ".png"), titles, pages);
    }

    static void card(Path out) throws Exception {
        var chain = new ArrayList<Frame>();
        for (int carbons : new int[]{1, 5, 16, 40}) {
            int at = 0;
            while (com.wormzjl.icheme.science.tutorial.cards.Chain.CARBONS[at] != carbons) at++;
            chain.add(Frame.of(carbons + " carbons", 0, at / 12.0));
        }
        sheet(out, "crude/what", "chain", chain);
        sheet(out, "crude/barrel", "barrel", List.of(Frame.of("Medium, 0 °C", 0, 0), Frame.of("Medium, 200 °C", 0, 200 / 600.0), Frame.of("Medium, 400 °C", 0, 400 / 600.0),
                Frame.of("Light, 200 °C", 1, 200 / 600.0), Frame.of("Heavy, 200 °C", 2, 200 / 600.0)));
        var products = new ArrayList<Frame>();
        var bands = com.wormzjl.icheme.science.tutorial.cards.BarrelData.load().getFirst().bands();
        double at = 0;
        for (int i = 0; i < bands.length; i++) {
            products.add(Frame.of(com.wormzjl.icheme.science.tutorial.cards.BarrelData.BANDS.get(i), 0, at + bands[i] / 2));
            at += bands[i];
        }
        sheet(out, "crude/why", "products", products);
        // The road: nothing pressed, then one box of each mark (well: not yet; boil: this guide; vacuum: runs; break: shape only).
        sheet(out, "crude/road", "road", List.of(Frame.of("no box pressed", 0, 0), new Frame("Well pressed", 0, 0, 0, Set.of(0)),
                new Frame("Boil pressed", 0, 0, 2, Set.of(0, 2)), new Frame("Vacuum pressed", 0, 0, 3, Set.of(0, 2, 3)),
                new Frame("Break pressed", 0, 0, 4, Set.of(0, 2, 3, 4))));
        sheet(out, "crude/parts", "three_parts", List.of(Frame.of("heat 0", 0, 0), Frame.of("heat 0.5", 0, .5), Frame.of("heat 1", 0, 1)));
    }
}
