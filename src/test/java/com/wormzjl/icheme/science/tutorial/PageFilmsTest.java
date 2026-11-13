package com.wormzjl.icheme.science.tutorial;

import com.wormzjl.icheme.client.gui.tutorial.FilmLayout;
import com.wormzjl.icheme.science.tutorial.equipment.ReferenceShapes;
import com.wormzjl.icheme.science.tutorial.films.EquipmentFilms;
import com.wormzjl.icheme.science.tutorial.films.Films;
import com.wormzjl.icheme.science.tutorial.films.PageFilms;
import com.wormzjl.icheme.science.tutorial.guide.PageFile;
import com.wormzjl.icheme.science.tutorial.scene.Frame;
import com.wormzjl.icheme.science.tutorial.scene.Scene;
import com.wormzjl.icheme.science.tutorial.scene.Track;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The equipment pages' films (batch 2026-11-12-tutorial-system, P6): every page has a film or stands in the picture-only
 * set, and every generic film ({@link PageFilms}) is one stop that names every nozzle of its reference shape, reads
 * ({@link FilmChecks}), gives the same frame twice and frames its body on the design glass; any stop of any page film keeps
 * its dots on the body and its leaders on drawn nozzles.
 */
class PageFilmsTest {
    /**
     * The pages that keep their picture: the motor, the generator and the gearbox have no nozzle to name and no inside to
     * show (their picture says all a film would), and the control valve's item is no equipment shape at all.
     */
    static final Set<String> PICTURE_ONLY = Set.of("equipment_motor", "equipment_generator", "equipment_gearbox", "pressure_control_valve");

    @Test
    void everyPageHasAFilmOrIsPictureOnly() {
        var pages = PageFile.load();
        var films = new HashSet<String>();
        Films.names().forEach(films::add);
        for (var page : pages.pages().values()) {
            if (PICTURE_ONLY.contains(page.id())) {
                assertNull(page.film(), page.id() + ": picture only");
                continue;
            }
            assertNotNull(page.film(), page.id() + " has a film");
            assertTrue(films.contains(page.film()), page.id() + ": " + page.film() + " is a film");
            if (page.film().startsWith(PageFilms.PREFIX)) assertEquals(PageFilms.name(Objects.requireNonNull(page.kind())), page.film(), page.id() + ": its kind's film");
        }
        for (var name : PageFilms.KINDS.keySet())
            assertTrue(pages.pages().values().stream().anyMatch(p -> name.equals(p.film())), name + " is some page's film");
    }

    @Test
    void everyPageFilmNamesItsNozzlesAndShowsItsInside() {
        var glass = FilmLayout.DESIGN_GLASS;
        for (var e : PageFilms.KINDS.entrySet()) {
            String name = e.getKey();
            var shape = Objects.requireNonNull(ReferenceShapes.of(e.getValue()), name);
            Scene scene = Objects.requireNonNull(PageFilms.build(name), name);
            assertEquals(scene.stops(), Objects.requireNonNull(Films.build(name)).stops(), name + ": the page opens it by its name");
            assertEquals(1, scene.stops().size(), name + ": one stop, the outside");
            for (int k = 1; k < scene.stops().size(); k++) assertTrue(scene.stops().get(k) > scene.stops().get(k - 1), name + ": stops in order");
            FilmChecks.everyStopReads(scene);
            // Every track names an actor the scene has.
            var actors = new HashSet<String>();
            scene.actors().forEach(a -> actors.add(a.id()));
            assertFalse(scene.actors().getFirst().polygons().isEmpty(), name + ": its body drawn");
            for (var t : scene.tracks()) if (t instanceof Track.Cut c) assertTrue(actors.contains(c.actor()), name + ": cuts " + c.actor());
            // Stop 1: one label a nozzle, each its role's.
            var first = scene.at(scene.stops().getFirst());
            assertEquals(shape.nozzles().size(), first.labels().size(), name + ": every nozzle named");
            var roles = shape.nozzles().stream().map(n -> PageFilms.label(n.role())).sorted().toList();
            assertEquals(roles, first.labels().stream().map(Frame.LabelFrame::key).sorted().toList(), name + ": by role");
            assertTrue(scene.tracks().stream().noneMatch(t -> t instanceof Track.Cut || t instanceof Track.Dots), name + ": no cut, no dots");
            for (int k = 0; k < scene.stops().size(); k++) {
                double t = scene.stops().get(k);
                assertEquals(summary(scene.at(t)), summary(scene.at(t)), name + ": the same frame twice at stop " + (k + 1));
                // The body fits the glass's share (FILL_W, FILL_H) and fills at least 60 % of its width or height.
                var frame = scene.at(t);
                var projection = FilmChecks.projection(frame, glass);
                double x0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y0 = Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
                for (var a : frame.actors())
                    if (a.id().equals("body"))
                        for (var poly : a.polygons())
                            for (int i = 0; i < poly.size(); i++) {
                                var s = projection.screen(poly.positions()[3 * i], poly.positions()[3 * i + 1], poly.positions()[3 * i + 2]);
                                x0 = Math.min(x0, s[0]);
                                x1 = Math.max(x1, s[0]);
                                y0 = Math.min(y0, s[1]);
                                y1 = Math.max(y1, s[1]);
                            }
                double w = x1 - x0, h = y1 - y0, slack = 1.02;
                assertTrue(w <= slack * glass.w() && h <= slack * glass.h(), name + " stop " + (k + 1) + ": the body " + Math.round(w) + " by " + Math.round(h) + " fits the glass");
                assertTrue(x0 >= glass.x() - 1 && x1 <= glass.right() + 1 && y0 >= glass.y() - 1 && y1 <= glass.bottom() + 1, name + " stop " + (k + 1) + ": on the glass");
                assertTrue(w >= .6 * glass.w() || h >= .6 * glass.h(), name + " stop " + (k + 1) + ": the body fills " + Math.round(100 * w / glass.w()) + " % of the width, "
                        + Math.round(100 * h / glass.h()) + " % of the height");
            }
        }
        assertEquals(EquipmentFilms.GLASS_W, glass.w());
    }

    /**
     * A stop shows its way where it has one (owner's rule for an inside stop, 2026-11-13): at every sampled time of a stop, at the
     * design glass and at 320 by 240, every dot stands on the body as drawn (inside a drawn polygon on the glass), and every
     * label's leader ends on its nozzle as drawn: its anchor on a drawn polygon and on the kept side of the cut. Stop 1's
     * labels the same, uncut. Whether the cut face is seen (a quarter of the body's drawn area, inside geometry) is judged
     * from the sheets, kind by kind (P6_COVERAGE.md).
     */
    @Test
    void anInsideStopKeepsItsDotsOnTheBodyAndItsLeadersOnDrawnNozzles() {
        var lang = TutorialText.english();
        var failures = new java.util.ArrayList<String>();
        int checked = 0;
        for (var e : PageFilms.KINDS.entrySet()) {
            String name = e.getKey();
            var scene = Objects.requireNonNull(PageFilms.build(name), name);
            var cut = scene.tracks().stream().filter(t -> t instanceof Track.Cut).map(t -> (Track.Cut) t).findFirst().orElse(null);
            var captions = new java.util.ArrayList<String>();
            for (var key : scene.captionKeys()) captions.add(lang.getOrDefault(key, key));
            for (var size : List.of(new int[]{FilmLayout.DESIGN_WIDTH, FilmLayout.DESIGN_HEIGHT}, new int[]{320, 240})) {
                var layout = FilmLayout.of(size[0], size[1], scene.stops().size(), FilmLayout.captionLines(captions, size[0], FilmChecks.M));
                var glass = FilmLayout.glass(com.wormzjl.icheme.client.gui.common.kit.PanelSkin.LIGHT, layout.scene());
                for (int k = 0; k < scene.stops().size(); k++) {
                    double stop = scene.stops().get(k), from = k == 0 ? stop : scene.stops().get(k - 1) + .8;
                    for (int i = 0; i <= 8; i++) {
                        double t = from + (stop - from) * i / 8;
                        var frame = scene.at(t);
                        var projection = FilmChecks.projection(frame, glass);
                        var drawn = drawn(frame, projection);
                        String where = name + " stop " + (k + 1) + " at " + size[0] + " by " + size[1] + ", t " + Math.round(t * 100) / 100.0;
                        for (var d : frame.dots()) {
                            checked++;
                            if (!on(drawn, projection.screen(d.x(), d.y(), d.z()), 0)) failures.add(where + ": a dot off the body");
                        }
                        if (i < 8) continue;
                        for (var l : frame.labels()) {
                            checked++;
                            if (!on(drawn, projection.screen(l.x(), l.y(), l.z()), 2)) failures.add(where + ": " + l.key() + "'s leader ends on nothing drawn");
                            if (k > 0 && cut != null) {
                                var q = cut.point();
                                var n = cut.normal();
                                if ((l.x() - q[0]) * n[0] + (l.y() - q[1]) * n[1] + (l.z() - q[2]) * n[2] > 1e-6) failures.add(where + ": " + l.key() + "'s nozzle is cut away");
                            }
                        }
                    }
                }
            }
        }
        failures.forEach(System.out::println);
        System.out.println("page films: " + PageFilms.KINDS.size() + ", " + checked + " dots and leaders checked, " + failures.size() + " off");
        assertEquals(List.of(), failures);
    }

    /** The body's drawn polygons of {@code frame} on the glass, each a ring of screen points. */
    private static List<double[][]> drawn(Frame frame, com.wormzjl.icheme.science.tutorial.scene.Projection projection) {
        var out = new java.util.ArrayList<double[][]>();
        for (var a : frame.actors())
            if (a.id().equals("body") && a.alpha() > 0)
                for (var poly : a.polygons()) {
                    var ring = new double[poly.size()][];
                    for (int i = 0; i < poly.size(); i++) ring[i] = projection.screen(poly.positions()[3 * i], poly.positions()[3 * i + 1], poly.positions()[3 * i + 2]);
                    out.add(ring);
                }
        return out;
    }

    /** Whether screen point {@code p} stands inside one of {@code rings}, or within {@code slack} px of one's edge. */
    private static boolean on(List<double[][]> rings, double[] p, double slack) {
        for (var ring : rings) {
            boolean in = false;
            for (int i = 0, j = ring.length - 1; i < ring.length; j = i++) {
                double[] a = ring[i], b = ring[j];
                if ((a[1] > p[1]) != (b[1] > p[1]) && p[0] < (b[0] - a[0]) * (p[1] - a[1]) / (b[1] - a[1]) + a[0]) in = !in;
                if (slack > 0) {
                    double dx = b[0] - a[0], dy = b[1] - a[1], l2 = dx * dx + dy * dy;
                    double u = l2 > 0 ? Math.clamp(((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / l2, 0, 1) : 0;
                    double ex = a[0] + u * dx - p[0], ey = a[1] + u * dy - p[1];
                    if (ex * ex + ey * ey <= slack * slack) return true;
                }
            }
            if (in) return true;
        }
        return false;
    }

    /** A frame's camera, captions, labels, dots and actors' ids and alphas, as text. */
    private static String summary(Frame f) {
        var actors = f.actors().stream().map(a -> a.id() + "@" + a.alpha() + "/" + a.polygons().size()).toList();
        return List.of(f.camera(), f.captions(), f.labels(), f.dots(), actors).toString();
    }
}
