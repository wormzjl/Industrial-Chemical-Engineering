package com.wormzjl.icheme.science.tutorial.films;

import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.LineGeometry;
import com.wormzjl.icheme.science.equipment.Mesh;
import com.wormzjl.icheme.science.equipment.ValveGeometry;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.icheme.science.tutorial.equipment.ReferenceShapes;
import com.wormzjl.icheme.science.tutorial.guide.PageFile;
import com.wormzjl.icheme.science.tutorial.guide.Pages;
import com.wormzjl.icheme.science.tutorial.scene.Actor;
import com.wormzjl.icheme.science.tutorial.scene.Actors;
import com.wormzjl.icheme.science.tutorial.scene.Frame;
import com.wormzjl.icheme.science.tutorial.scene.Projection;
import com.wormzjl.icheme.science.tutorial.scene.Scene;
import com.wormzjl.icheme.science.tutorial.scene.Track;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * An equipment page's picture (batch 2026-11-12-tutorial-system, P5): picture first, the page's lines its caption. A page
 * with a film shows its film's first stop, from which Watch plays on; a kind without one its reference body as the item's
 * icon sees it (from the south-east, 30 degrees above the horizon), drawn large; the control valve its item's own valve. The
 * picture is a still {@link Frame}, framed on the films' design glass ({@link EquipmentFilms#GLASS_W} by
 * {@link EquipmentFilms#GLASS_H}) and drawn as a film's frame is (the game's {@code SceneView}, the render tool). Built when
 * the page opens, never before.
 */
public final class PagePicture {
    /** The icon's view: from the south-east, 30 degrees above the horizon. */
    static final double YAW = -.75 * Math.PI, PITCH = Math.PI / 6;

    private PagePicture() {
    }

    /** The picture of {@code page}: its film's first stop, else its body's still; null where it has neither. */
    public static @Nullable Frame of(Pages.Page page) {
        if (page.film() != null) {
            var film = Films.build(page.film());
            if (film != null) return film.at(film.stops().getFirst());
        }
        var still = still(page);
        return still == null ? null : still.at(1);
    }

    /** A page's still: its kind's reference body, or the control valve's item geometry; null for neither. */
    public static @Nullable Scene still(Pages.Page page) {
        List<Mesh.Polygon> polygons;
        if (page.kind() != null) {
            EquipmentShape shape = ReferenceShapes.of(page.kind());
            if (shape == null) return null;
            polygons = Actors.equipment("body", shape).polygons();
        } else if (PageFile.ITEMS.contains(page.id())) {
            polygons = ValveGeometry.pressure(LineGeometry.Key.item(TopologyCompiler.Kind.PIPE), true);
        } else return null;
        if (polygons.isEmpty()) return null;
        var body = new Actor("body", polygons);
        var b = EquipmentFilms.bounds(body);
        double[] c = {(b[0][0] + b[1][0]) / 2, (b[0][1] + b[1][1]) / 2, (b[0][2] + b[1][2]) / 2};
        // The body's projected extent, centred on the glass (the target moved to its middle), at the scale that fits it.
        var unit = new Projection(YAW, PITCH, 1, c, 0, 0);
        double x0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y0 = Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
        for (var p : polygons)
            for (int i = 0; i < p.size(); i++) {
                var s = unit.screen(p.positions()[3 * i], p.positions()[3 * i + 1], p.positions()[3 * i + 2]);
                x0 = Math.min(x0, s[0]);
                x1 = Math.max(x1, s[0]);
                y0 = Math.min(y0, s[1]);
                y1 = Math.max(y1, s[1]);
            }
        double mx = (x0 + x1) / 2, my = -(y0 + y1) / 2;
        var r = unit.right();
        var u = unit.up();
        for (int k = 0; k < 3; k++) c[k] += mx * r[k] + my * u[k];
        double scale = Math.min(EquipmentFilms.FILL_W * EquipmentFilms.GLASS_W / Math.max(1e-6, x1 - x0), EquipmentFilms.FILL_H * EquipmentFilms.GLASS_H / Math.max(1e-6, y1 - y0));
        return Scene.builder(page.nameKey()).actor(body).track(new Track.Camera(List.of(new Track.Key(0, YAW, PITCH, scale, c[0], c[1], c[2])))).stop(1).build();
    }

    /**
     * The game's draw calls for {@code frame} on a screen: one a drawn actor, one for its dots, one for its flames, one for
     * its liquids, and the depth clear before them.
     */
    public static int draws(Frame frame) {
        int n = 1;
        for (var a : frame.actors()) if (!a.polygons().isEmpty() && a.alpha() > 0) n++;
        if (!frame.dots().isEmpty()) n++;
        if (!frame.flames().isEmpty()) n++;
        if (!frame.liquids().isEmpty()) n++;
        return n;
    }
}
