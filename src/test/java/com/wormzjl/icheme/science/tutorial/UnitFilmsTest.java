package com.wormzjl.icheme.science.tutorial;

import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.tutorial.cards.UnitMap;
import com.wormzjl.icheme.science.tutorial.films.CrudeUnit;
import com.wormzjl.icheme.science.tutorial.films.EquipmentFilms;
import com.wormzjl.icheme.science.tutorial.films.Films;
import com.wormzjl.icheme.science.tutorial.scene.Frame;
import com.wormzjl.icheme.science.tutorial.scene.Track;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Chapter 5's films (batch 2026-11-12-tutorial-system, P5): "the crude unit" (crude_unit), five stops in the sheet's
 * order, and the unit's equipment films, one for each kind with a body, two or three stops each on the kind's reference
 * shape: outside with its connections named, inside (cut open where the inside is the idea), and where it stands.
 */
class UnitFilmsTest {
    /** The films whose second stop cuts the body open: the inside is their idea. */
    static final Set<String> CUT = Set.of(EquipmentFilms.TANK, EquipmentFilms.EXCHANGER, EquipmentFilms.HEATER, EquipmentFilms.DRUM);

    @Test
    void everyEquipmentFilmHasItsStopsOnItsReferenceShape() {
        var glass = com.wormzjl.icheme.client.gui.tutorial.FilmLayout.DESIGN_GLASS;
        assertEquals(List.of(glass.w(), glass.h()), List.of(EquipmentFilms.GLASS_W, EquipmentFilms.GLASS_H), "the films fit the guide's own glass");
        for (var name : EquipmentFilms.KINDS.keySet()) {
            var scene = Objects.requireNonNull(EquipmentFilms.build(name), name);
            assertEquals(EquipmentFilms.stops(name), scene.stops().size(), name);
            assertTrue(scene.stops().size() >= 2 && scene.stops().size() <= 3, name + ": two or three stops");
            assertEquals(scene.stops(), Objects.requireNonNull(Films.build(name)).stops(), name + ": the page opens it by its name");
            FilmChecks.everyStopReads(scene);
            assertEquals(EquipmentFilms.KINDS.get(name), EquipmentFilms.shape(name).kind(), name + ": on its kind's shape");
            var body = scene.actors().stream().filter(a -> a.id().equals("body")).findFirst().orElseThrow();
            assertFalse(body.polygons().isEmpty(), name + ": its body drawn");
            assertFalse(keys(scene.at(scene.stops().getFirst())).isEmpty(), name + ": its connections named at stop 1");
            assertFalse(keys(scene.at(scene.stops().getLast())).isEmpty(), name + ": where it stands named at its last stop");
            boolean cut = scene.tracks().stream().anyMatch(t -> t instanceof Track.Cut c && c.actor().equals("body"));
            assertEquals(CUT.contains(name), cut, name + ": cut open where the inside is the idea");
            boolean dots = scene.tracks().stream().anyMatch(t -> t instanceof Track.Dots);
            assertTrue(dots, name + ": dots run through it");
            // P5 review: at every stop the body fills at least 60 % of the glass's width or height; at the inside stop
            // its dots read at 2 px or more, and a cut film shows its tubes or its liquid.
            for (int k = 0; k < scene.stops().size(); k++) {
                var frame = scene.at(scene.stops().get(k));
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
                double w = Math.min(x1, glass.right()) - Math.max(x0, glass.x()), h = Math.min(y1, glass.bottom()) - Math.max(y0, glass.y());
                assertTrue(w >= .6 * glass.w() || h >= .6 * glass.h(), name + " stop " + (k + 1) + ": the body fills " + Math.round(100 * w / glass.w()) + " % of the width, "
                        + Math.round(100 * h / glass.h()) + " % of the height");
                if (k == 1 && scene.stops().size() == 3) {
                    double ppm = frame.camera().scale() * com.wormzjl.icheme.client.gui.tutorial.FilmLayout.fit(glass);
                    for (var d : frame.dots()) assertTrue(d.size() * ppm >= 2, name + ": a dot of " + d.size() * ppm + " px at the inside stop");
                    if (CUT.contains(name))
                        assertTrue(frame.actors().stream().anyMatch(a -> a.id().equals("tubes") && a.alpha() > .9) || frame.actors().stream().anyMatch(a -> a.id().equals("roof"))
                                || !frame.liquids().isEmpty(), name + ": the inside its caption names, shown");
                }
            }
        }
        assertTrue(EquipmentFilms.shape(EquipmentFilms.DRUM).boot(), "the reflux drum with its boot");
    }

    @Test
    void theUnitFilmFollowsTheSheetsStreams() {
        var scene = CrudeUnit.build();
        assertEquals(5, scene.stops().size());
        assertEquals(scene.stops(), Objects.requireNonNull(Films.build(CrudeUnit.NAME)).stops(), "the guide opens it by its name");
        FilmChecks.everyStopReads(scene);
        var stops = scene.stops();
        assertEquals(List.of(CrudeUnit.P101, CrudeUnit.CRUDE_TANK, CrudeUnit.CONVERTER).stream().sorted().toList(), keys(scene.at(stops.get(0))).stream().sorted().toList());
        assertEquals(Set.of(CrudeUnit.LOOP, CrudeUnit.E101), Set.copyOf(keys(scene.at(stops.get(1)))), "stop 2: the preheat");
        assertEquals(Set.of(CrudeUnit.H101, CrudeUnit.V101), Set.copyOf(keys(scene.at(stops.get(2)))), "stop 3: the heater and the valve");
        assertEquals(Set.of(CrudeUnit.T101, CrudeUnit.DRUM, CrudeUnit.P102), Set.copyOf(keys(scene.at(stops.get(3)))), "stop 4: the tower and its products");
        assertEquals(Set.of(CrudeUnit.E102, CrudeUnit.TANKS), Set.copyOf(keys(scene.at(stops.get(4)))), "stop 5: the products' way to their tanks");
        // The order the film tells, as the sheet's streams run: the crude from P-101 to the tower's pumparound, back to E-101's
        // tubes, H-101, V-101 and the tower's feed; E-101's heat from the tower's bottoms on its shell, then E-102.
        var sheet = UnitMap.sheet();
        var way = new ArrayList<String>();
        var at = stream(sheet, "CRUDE").to();
        while (at != null) {
            way.add(at.unit() + ":" + at.port());
            var next = out(sheet, at.unit(), at.port());
            at = next == null ? null : next.to();
        }
        assertEquals(List.of("P-101:in", "T-101:pa/1/a", "E-101:tube_a", "H-101:a", "V-101:a", "T-101:feed"), way);
        assertEquals("T-101:bottoms", end(stream(sheet, "S-9").from()));
        assertEquals("E-101:shell_a", end(stream(sheet, "S-9").to()), "E-101 takes its heat from the residue");
        assertEquals("E-102:a", end(stream(sheet, "S-10").to()), "then the residue cools in E-102");
    }

    /** The stream that leaves {@code unit} after it entered at {@code port}: the pumparound's way back, or the unit's outlet. */
    private static Flowsheet.Stream out(Flowsheet sheet, String unit, String port) {
        String back = port.startsWith("pa/") ? port.substring(0, port.length() - 1) + "b" : null;
        for (var s : sheet.streams())
            if (s.from() != null && s.from().unit().equals(unit)) {
                if (back != null && s.from().port().equals(back)) return s;
                if (back == null && !unit.equals("T-101") && (s.from().port().equals("out") || s.from().port().equals("b") || s.from().port().equals("tube_b"))) return s;
            }
        return null;
    }

    private static Flowsheet.Stream stream(Flowsheet sheet, String id) {
        return sheet.streams().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    private static String end(Flowsheet.End end) {
        return end.unit() + ":" + end.port();
    }

    private static List<String> keys(Frame frame) {
        return frame.labels().stream().map(Frame.LabelFrame::key).toList();
    }
}
