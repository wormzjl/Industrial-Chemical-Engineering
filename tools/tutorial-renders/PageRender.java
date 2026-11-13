import com.wormzjl.icheme.client.gui.common.kit.PanelSkin;
import com.wormzjl.icheme.client.gui.common.kit.Skin;
import com.wormzjl.icheme.client.gui.tutorial.GuidePage;
import com.wormzjl.icheme.client.gui.tutorial.cards.Card;
import com.wormzjl.icheme.science.tutorial.guide.GuideFile;
import com.wormzjl.icheme.science.tutorial.guide.PageFile;
import com.wormzjl.icheme.science.tutorial.guide.Progress;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The P5 renders (batch 2026-11-12-tutorial-system, tools/tutorial-renders): every equipment page in the light theme at
 * 464 by 254 and in the 320 by 240 window (eight pages a sheet, the crude unit's first), the column's and the control
 * valve's pages dark too; the unit map card; chapter 5's guide page. Run by {@code renderTutorialGuide -PtutorialGuideOnly=p5}.
 */
final class PageRender {
    private PageRender() {
    }

    static void render(Path out) throws Exception {
        var pages = PageFile.load();
        var ids = new ArrayList<>(pages.unit());
        for (var id : pages.pages().keySet()) if (!ids.contains(id)) ids.add(id);
        int per = 8;
        for (int s = 0; s * per < ids.size(); s++) {
            var images = new ArrayList<BufferedImage>();
            var titles = new ArrayList<String>();
            for (var id : ids.subList(s * per, Math.min(ids.size(), (s + 1) * per))) {
                images.add(page(id, GuidePage.DESIGN_WIDTH, GuidePage.DESIGN_HEIGHT, PanelSkin.LIGHT, 0));
                titles.add(id + ", 464 by 254");
                images.add(page(id, 312, 232, PanelSkin.LIGHT, 0));
                titles.add(id + ", 320 by 240 window");
            }
            HeatDiagramRender.grid(out.resolve("pages-" + (s + 1) + ".png"), titles, images);
        }
        var dark = new ArrayList<BufferedImage>();
        var darkTitles = new ArrayList<String>();
        for (var id : List.of("equipment_column", "pressure_control_valve")) {
            dark.add(page(id, GuidePage.DESIGN_WIDTH, GuidePage.DESIGN_HEIGHT, PanelSkin.DARK, 0));
            darkTitles.add(id + ", dark, 464 by 254");
            dark.add(page(id, 312, 232, PanelSkin.DARK, Integer.MAX_VALUE));
            darkTitles.add(id + ", dark, 320 by 240 window, scrolled to its end");
        }
        HeatDiagramRender.grid(out.resolve("pages-dark.png"), darkTitles, dark);
        GuideRender.sheet(out, "unit/map", "unit_map", List.of(GuideRender.Frame.of("nothing pressed", 0, 0),
                new GuideRender.Frame("H-101 pressed (its page opens)", 0, 0, 3, Set.of(3))));
        GuideRender.chapter(out, "unit", "simulator", "Crude unit");
    }

    /** The shell sprites the pictures are drawn with, read once. */
    private static java.util.Map<com.wormzjl.icheme.science.equipment.Mesh.Material, TutorialRender.Sprite> sprites;

    /**
     * Page {@code id} open, chapter 5 behind it, scrolled by {@code offset} (clamped to the page's end), its picture drawn
     * in its frame as the game's {@code SceneView} draws it (TutorialRender's rasteriser).
     */
    static BufferedImage page(String id, int w, int h, Skin skin, int offset) {
        var placed = new GuidePage.Page[1];
        var image = GuideRender.image(w, h, c -> {
            var in = new GuidePage.Input(GuideFile.load(), Progress.NONE, "unit", null).withPage(PageFile.load(), id);
            var p = GuidePage.place(in, w, h, c.measure(), GuideRender.tr, GuideRender.tr.apply("gui.icheme.fluid.theme.light"));
            p.draw(c, skin, Math.min(offset, p.maxOffset()), null, Card.State.of(0, 0));
            placed[0] = p;
        });
        var e = placed[0].equipment();
        var frame = e == null ? null : com.wormzjl.icheme.science.tutorial.films.PagePicture.of(e.page());
        if (frame != null) {
            try {
                if (sprites == null) sprites = TutorialRender.sprites();
            } catch (Exception x) {
                throw new IllegalStateException(x);
            }
            int s = HeatDiagramRender.SCALE;
            var r = e.picture();
            double fit = com.wormzjl.icheme.client.gui.tutorial.FilmLayout.fit(r);
            var view = new com.wormzjl.icheme.client.gui.common.kit.Rect((r.x() + 4) * s, (r.y() + 4) * s, r.w() * s, r.h() * s);
            var projection = com.wormzjl.icheme.science.tutorial.scene.Projection.of(frame.camera(), 0, 0, 1, fit * s, view.x() + view.w() / 2.0, view.y() + view.h() / 2.0);
            TutorialRender.draw(image, view, frame, projection, sprites);
        }
        return image;
    }
}
