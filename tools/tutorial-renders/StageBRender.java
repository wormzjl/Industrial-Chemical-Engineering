import com.wormzjl.icheme.client.gui.common.AssemblyColumn;
import com.wormzjl.icheme.client.gui.common.AssemblyTour;
import com.wormzjl.icheme.client.gui.common.OperationColumn;
import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.PanelSkin;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.ScrollView;
import com.wormzjl.icheme.client.gui.common.kit.Skin;
import com.wormzjl.icheme.client.gui.common.TitleStrip;
import com.wormzjl.icheme.client.gui.tutorial.GuidePage;
import com.wormzjl.icheme.client.gui.tutorial.TourLayout;
import com.wormzjl.icheme.science.assembly.ColumnBlueprint;
import com.wormzjl.icheme.science.tutorial.guide.TourFile;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The P5 stage B renders (batch 2026-11-12-tutorial-system, tools/tutorial-renders): the column assembly screen's two help
 * tours stop by stop (the pages as GuiPrototypes and OperationPrototypes draw them, the anchors from {@link AssemblyTour}),
 * the limit card's three cases, and the guide's build and start chapters. Run by
 * {@code renderTutorialGuide -PtutorialGuideOnly=p5b}.
 */
final class StageBRender {
    private StageBRender() {
    }

    static void render(Path out) throws Exception {
        tour(out, false);
        tour(out, true);
        GuideRender.sheet(out, "start/limit", "limit", List.of(GuideRender.Frame.of("feed short", 0, 0),
                GuideRender.Frame.of("steam short", 1, 0), GuideRender.Frame.of("product full", 2, 0)));
        GuideRender.chapter(out, "building", "unit", "Building");
        GuideRender.chapter(out, "start", "building", "Start-up");
    }

    static void tour(Path out, boolean operation) throws Exception {
        var tour = TourFile.load().get(AssemblyTour.page(operation));
        var pages = new ArrayList<BufferedImage>();
        var titles = new ArrayList<String>();
        for (int[] size : new int[][]{{GuidePage.DESIGN_WIDTH, GuidePage.DESIGN_HEIGHT}, {312, 232}})
            for (int i = 0; i < tour.stops().size(); i++) {
                int stop = i, w = size[0], h = size[1];
                pages.add(GuideRender.image(w, h, c -> stop(c, w, h, operation, stop, tour)));
                titles.add(w + " by " + h + ", stop " + (i + 1) + ": " + tour.stops().get(i).anchor());
            }
        HeatDiagramRender.grid(out.resolve("tour-assembly-" + (operation ? "operation" : "structure") + ".png"), titles, pages);
    }

    static void stop(Canvas c, int w, int h, boolean operation, int stop, com.wormzjl.icheme.science.tutorial.guide.Tour tour) {
        var skin = PanelSkin.LIGHT;
        skin.page(c, new Rect(0, 0, w, h));
        var d = new GuiPrototypes.Door(c, skin, w, h, false);
        var t = GuiPrototypes.DIALOG;
        var anchors = new HashMap<String, Rect>(AssemblyTour.frame(w, h, operation));
        if (operation) {
            var view = OperationPrototypes.crude(1, 2, 2);
            OperationPrototypes.door(d, view);
            anchors.putAll(AssemblyTour.door(placed(OperationColumn.rows(new OperationColumn.Input(view, false, OperationPrototypes.UNITS), t), w, h, d)));
        } else {
            var report = GuiPrototypes.assemblyReport();
            GuiPrototypes.assemblyDoor(d, GuiPrototypes.ASSEMBLY, report, ColumnBlueprint.REFLUX_PUMP, false);
            var slots = ColumnBlueprint.of(GuiPrototypes.ASSEMBLY).slots();
            anchors.putAll(AssemblyTour.door(placed(AssemblyColumn.rows(AssemblyColumn.input(slots.size(), report, false, "Reflux pump", true), t), w, h, d)));
        }
        // The help key left of the theme key, as AssemblyScreen places both (TitleStrip.keys(width, 2)).
        skin.iconKey(c, TitleStrip.keys(w, 2).get(1), Skin.Icon.HELP, false);
        String anchor = tour.stops().get(stop).anchor();
        var lit = anchors.get(anchor);
        if (lit == null) {
            System.out.println("ASSEMBLY TOUR: no place for stop " + (stop + 1) + " (" + anchor + ")");
            return;
        }
        boolean last = stop == tour.stops().size() - 1;
        TourLayout.of(w, h, lit, GuideRender.tr.apply(tour.key(stop)), stop == 0, last, last && tour.shows(), GuideRender.words(), c.measure()).draw(c, skin, null);
    }

    /** The door column as the prototypes place it (narrowed for its bar where it is taller than its area). */
    static Column placed(List<Column.Row> rows, int w, int h, GuiPrototypes.Door d) {
        var area = AssemblyColumn.area(w, h);
        int tall = Column.place(rows, area.x(), area.y(), area.w(), d.m, Column.Metrics.COMPACT).bounds().h();
        return Column.place(rows, area.x(), area.y(), tall > area.h() ? area.w() - ScrollView.BAR - 4 : area.w(), d.m, Column.Metrics.COMPACT);
    }

    static final Map<String, String> NONE = Map.of();
}
