package com.wormzjl.icheme.science.tutorial;

import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.tutorial.films.Films;
import com.wormzjl.icheme.science.tutorial.films.StillBuild;
import com.wormzjl.icheme.science.tutorial.films.StillInside;
import com.wormzjl.icheme.science.tutorial.scene.Frame;
import com.wormzjl.icheme.science.tutorial.scene.Track;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/** The film "building the still" (batch 2026-11-12-tutorial-system, P2b): the constructor, the parts rising, named, and the connections at their nozzles. */
class StillBuildFilmTest {
    @Test
    void itHasFourStopsAndEveryStopReads() {
        var scene = StillBuild.build();
        assertEquals(4, scene.stops().size());
        assertEquals(scene.stops(), Objects.requireNonNull(Films.build(StillBuild.NAME)).stops(), "the guide opens it by its name");
        FilmChecks.everyStopReads(scene);
    }

    @Test
    void theConstructorIsRightClickedThenTheStillRisesPartByPart() {
        var scene = StillBuild.build();
        var one = scene.at(scene.stops().get(0));
        assertEquals(List.of(StillBuild.CONSTRUCTOR), keys(one));
        assertEquals(1, one.pointers().size());
        assertEquals(Track.Sign.RIGHT_CLICK, one.pointers().getFirst().sign());
        assertEquals(List.of("ground", "constructor"), ids(one), "no part of the still yet");
        // Each part shows after the one below it.
        var order = List.of("setting", "pot", "neck", "condenser");
        double[] first = new double[order.size()];
        java.util.Arrays.fill(first, Double.NaN);
        for (double t = 0; t <= scene.stops().get(1); t += .05) {
            var shown = ids(scene.at(t));
            for (int i = 0; i < order.size(); i++) if (Double.isNaN(first[i]) && shown.contains(order.get(i))) first[i] = t;
        }
        for (int i = 0; i < order.size(); i++) {
            assertFalse(Double.isNaN(first[i]), order.get(i) + " shows by stop 2");
            if (i > 0) assertTrue(first[i] > first[i - 1], order.get(i) + " after " + order.get(i - 1));
        }
        for (var part : order) assertFalse(scene.actors().stream().filter(a -> a.id().equals(part)).findFirst().orElseThrow().polygons().isEmpty(), part);
    }

    @Test
    void thePartsAreNamedThenTheConnectionsAtTheirNozzles() {
        var scene = StillBuild.build();
        assertEquals(List.of(StillBuild.FIRE, StillBuild.POT_KEY, StillBuild.NECK_KEY, StillBuild.CONDENSER), keys(scene.at(scene.stops().get(2))));
        var four = scene.at(scene.stops().get(3));
        var expected = new ArrayList<String>();
        for (var role : List.of(EquipmentShape.Role.CHARGE, EquipmentShape.Role.DISTILLATE, EquipmentShape.Role.VENT, EquipmentShape.Role.DRAIN))
            expected.add(StillBuild.NOZZLES.get(role));
        expected.add(StillBuild.DOOR);
        assertEquals(expected, keys(four));
        // Each connection's label points at its nozzle on the still's shape.
        var shape = StillInside.shape();
        for (var role : StillBuild.NOZZLES.keySet()) {
            var at = StillBuild.end(shape, role);
            var label = four.labels().stream().filter(l -> l.key().equals(StillBuild.NOZZLES.get(role))).findFirst().orElseThrow();
            assertArrayEquals(at, new double[]{label.x(), label.y(), label.z()}, 1e-12, role.name());
            var nozzle = shape.nozzles().stream().filter(n -> n.role() == role).findFirst().orElseThrow();
            var base = shape.transform().point(nozzle.base());
            assertEquals(nozzle.length() / 2, Math.sqrt(sq(at[0] - base[0]) + sq(at[1] - base[1]) + sq(at[2] - base[2])), 1e-9, role + ": the nozzle's middle");
        }
        var door = four.labels().getLast();
        assertArrayEquals(StillInside.door(shape), new double[]{door.x(), door.y(), door.z()}, 1e-12);
    }

    private static double sq(double v) {
        return v * v;
    }

    private static List<String> keys(Frame frame) {
        return frame.labels().stream().map(Frame.LabelFrame::key).toList();
    }

    private static List<String> ids(Frame frame) {
        return frame.actors().stream().map(a -> a.id()).toList();
    }
}
