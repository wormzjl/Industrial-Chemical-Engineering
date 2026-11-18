import com.wormzjl.icheme.client.gui.common.kit.PanelSkin;
import com.wormzjl.icheme.client.gui.common.kit.Skin;
import com.wormzjl.icheme.client.gui.tutorial.GuidePage;
import com.wormzjl.icheme.client.gui.tutorial.cards.Card;
import com.wormzjl.icheme.science.tutorial.guide.GuideFile;
import com.wormzjl.icheme.science.tutorial.guide.PhotoFile;
import com.wormzjl.icheme.science.tutorial.guide.Progress;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The guide's photographs as the shipped {@link GuidePage} places and draws them (batch 2026-11-18-tutorial-photos, option
 * B): for every chapter its photo row over the steps at 464 by 254 light and dark and at 312 by 232; the viewer for every
 * photo dark at 464 by 254, and the crude chapter's last and the start-up chapter's at 312 by 232. The PNGs are read from
 * {@code src/main/resources} by the render canvas. {@code GuideRender <out> photos}.
 */
final class PhotoRender {
    private PhotoRender() {
    }

    static GuidePage.Page page(com.wormzjl.icheme.client.gui.common.kit.Canvas c, int w, int h, String open, String photo) {
        var in = new GuidePage.Input(GuideFile.load(), Progress.NONE, open, null).withPhotos(PhotoFile.load(), photo);
        return GuidePage.place(in, w, h, c.measure(), GuideRender.tr, GuideRender.tr.apply("gui.icheme.fluid.theme.light"));
    }

    static BufferedImage draw(int w, int h, String open, String photo, Skin skin) {
        return GuideRender.image(w, h, c -> page(c, w, h, open, photo).draw(c, skin, 0, null, Card.State.of(0, 0)));
    }

    static void render(Path out) throws Exception {
        var guide = GuideFile.load();
        for (var chapter : guide.chapters()) {
            String id = chapter.id();
            HeatDiagramRender.grid(out.resolve("list-" + id + ".png"), List.of("Light, 464 by 254: " + id, "Dark, 464 by 254", "Light, 320 by 240 window (312 by 232)"),
                    List.of(draw(464, 254, id, null, PanelSkin.LIGHT), draw(464, 254, id, null, PanelSkin.DARK), draw(312, 232, id, null, PanelSkin.LIGHT)));
            var pages = new ArrayList<BufferedImage>();
            var titles = new ArrayList<String>();
            for (var photo : chapter.photos()) {
                pages.add(draw(464, 254, id, photo, PanelSkin.DARK));
                titles.add("Viewer, dark, 464 by 254: " + photo);
            }
            HeatDiagramRender.grid(out.resolve("viewer-" + id + ".png"), titles, pages);
        }
        HeatDiagramRender.grid(out.resolve("viewer-small.png"), List.of("Viewer, dark, 312 by 232: crude_residue", "Viewer, dark, 312 by 232: start (a tall photo)",
                        "Viewer, light, 312 by 232: building (a tall photo)"),
                List.of(draw(312, 232, "crude", "crude_residue", PanelSkin.DARK), draw(312, 232, "start", "start", PanelSkin.DARK),
                        draw(312, 232, "building", "building", PanelSkin.LIGHT)));
    }
}
