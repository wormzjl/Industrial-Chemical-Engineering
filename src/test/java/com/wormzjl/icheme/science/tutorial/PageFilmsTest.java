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
 * set, and every generic film ({@link PageFilms}) names every nozzle of its reference shape at stop 1, cuts its body open
 * with dots from its inlet to its outlet at stop 2 where it has one, reads at every stop ({@link FilmChecks}), gives the
 * same frame twice and frames its body on the design glass.
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
            assertEquals(PageFilms.stops(name), scene.stops().size(), name);
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
            // Stop 2 where the inside is shown: the body cut open, dots from the inlet to the outlet, both named.
            boolean inside = PageFilms.INSIDE.containsKey(e.getValue());
            assertEquals(inside, scene.tracks().stream().anyMatch(t -> t instanceof Track.Cut c && c.actor().equals("body")), name + ": cut open only at an inside stop");
            if (inside) {
                var second = scene.at(scene.stops().get(1));
                assertFalse(second.dots().isEmpty(), name + ": dots run through it");
                var flow = PageFilms.INSIDE.get(e.getValue());
                assertEquals(Set.of(PageFilms.label(flow[0]), PageFilms.label(flow[1])), Set.copyOf(second.labels().stream().map(Frame.LabelFrame::key).toList()), name);
            }
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

    /** A frame's camera, captions, labels, dots and actors' ids and alphas, as text. */
    private static String summary(Frame f) {
        var actors = f.actors().stream().map(a -> a.id() + "@" + a.alpha() + "/" + a.polygons().size()).toList();
        return List.of(f.camera(), f.captions(), f.labels(), f.dots(), actors).toString();
    }
}
