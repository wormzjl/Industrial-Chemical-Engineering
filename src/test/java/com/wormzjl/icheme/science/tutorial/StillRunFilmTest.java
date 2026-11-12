package com.wormzjl.icheme.science.tutorial;

import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.science.tutorial.films.Films;
import com.wormzjl.icheme.science.tutorial.films.StillInside;
import com.wormzjl.icheme.science.tutorial.films.StillRun;
import com.wormzjl.icheme.science.tutorial.films.StillRunData;
import com.wormzjl.icheme.science.tutorial.scene.Frame;
import com.wormzjl.icheme.science.tutorial.scene.Track;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The film "a still run" (batch 2026-11-12-tutorial-system, P2b): the recorded run replayed in four stops, at the
 * recording's first drop, end of naphtha, end of kerosene and end, where the still cuts its own fire.
 */
class StillRunFilmTest {
    @Test
    void itHasFourStopsAtTheRecordingsLinesAndEveryStopReads() {
        var scene = StillRun.build();
        var run = StillRunData.load();
        assertEquals(4, scene.stops().size());
        assertEquals(scene.stops(), Objects.requireNonNull(Films.build(StillRun.NAME)).stops(), "the guide opens it by its name");
        FilmChecks.everyStopReads(scene);
        var lines = List.of("first", "naphtha", "kerosene", "end");
        for (int i = 0; i < lines.size(); i++) {
            var frame = scene.at(scene.stops().get(i));
            assertEquals(List.of(StillRun.POT, StillRun.DISTILLED), frame.readouts().stream().map(Frame.ReadoutFrame::key).toList(), "the pot and the distilled share at stop " + (i + 1));
            assertTrue(frame.legend());
            assertEquals(run.line(lines.get(i), "pot_kelvin"), frame.readouts().getFirst().value(), 1e-6, "stop " + (i + 1) + " shows the pot at " + lines.get(i));
        }
    }

    @Test
    void theFirstDropFallsAtTheSpoutAndTheReadoutsFollowTheRecording() {
        var scene = StillRun.build();
        var run = StillRunData.load();
        assertEquals(List.of(StillRun.FIRST, StillRun.SPOUT), scene.at(scene.stops().getFirst()).labels().stream().map(Frame.LabelFrame::key).toList());
        double lastShare = -1;
        for (double t = 0; t <= scene.length(); t += .25) {
            var share = scene.at(t).readouts().get(1).value();
            assertTrue(share >= lastShare - 1e-12, "the distilled share never falls: " + t);
            lastShare = share;
        }
        assertEquals(run.series("distilled").value(run.time().size() - 1), lastShare, 1e-9, "the end shows the run's end");
    }

    @Test
    void lightDotsLeaveBeforeMiddleOnesAndTheHeavyStay() {
        var scene = StillRun.build();
        var pot = ShapeRasterizer.stillParts(StillInside.shape()).pot();
        double light = Double.NaN, middle = Double.NaN;
        for (double t = 0; t <= scene.length(); t += .1) {
            var frame = scene.at(t);
            for (var d : frame.dots()) {
                if (inside(pot, d)) continue;
                if (d.kind() == Track.Kind.HEAVY) fail("a heavy dot left the pot at " + t);
                if (d.y() < -1.1 + 1.25) continue; // what a drum has collected
                if (d.kind() == Track.Kind.LIGHT && Double.isNaN(light)) light = t;
                if (d.kind() == Track.Kind.MIDDLE && Double.isNaN(middle)) middle = t;
            }
        }
        assertFalse(Double.isNaN(light) || Double.isNaN(middle), "both leave");
        assertTrue(light < scene.stops().getFirst(), "the light ones leave before the first drop: " + light);
        assertTrue(middle > scene.stops().get(1), "the middle ones after the cut: " + middle);
        var end = scene.at(scene.length());
        assertEquals(10, end.dots().stream().filter(d -> d.kind() == Track.Kind.HEAVY && inside(pot, d)).count(), "the heavy part stays in the pot");
        assertEquals(List.of(StillInside.POT), end.labels().stream().map(Frame.LabelFrame::key).toList(), "the last stop names the pot");
    }

    @Test
    void afterTheCutTheSecondDrumTakesTheStreamAndTheStillCutsItsOwnFire() {
        var scene = StillRun.build();
        var cut = scene.at(scene.stops().get(1));
        var later = scene.at(scene.stops().get(2));
        var first = drum(cut, StillRun.FIRST_LIQUID);
        var second = drum(cut, StillRun.SECOND_LIQUID);
        assertTrue(first.top() > first.bottom(), "the first drum holds the light part at the cut");
        assertEquals(second.bottom(), second.top(), 5e-3, "the second drum is (all but) empty at the cut");
        assertEquals(first.top(), drum(later, StillRun.FIRST_LIQUID).top(), 5e-3, "the first drum takes no more after the cut");
        assertTrue(drum(later, StillRun.SECOND_LIQUID).top() > second.top(), "the second drum fills after the cut");
        assertEquals(1, cut.pointers().size(), "the switch at the tee");
        assertEquals(1, later.pointers().size(), "the fire out at the door");
        assertFalse(later.flames().isEmpty(), "at stop 3 the fire is still lit: stop 4 shows it left on");
        assertFalse(scene.at(scene.length() - 1.5).flames().isEmpty(), "left on through stop 4");
        assertTrue(scene.at(scene.length()).flames().isEmpty(), "the still cuts its own fire at the run's end");
        assertTrue(scene.at(scene.length()).pointers().isEmpty(), "no hand puts it out");
    }

    private static Frame.LiquidFrame drum(Frame frame, int rgb) {
        return frame.liquids().stream().filter(l -> l.rgb() == rgb).findFirst().orElseThrow();
    }

    private static boolean inside(double[] box, Frame.DotFrame d) {
        return d.x() >= box[0] && d.x() <= box[3] && d.y() >= box[1] && d.y() <= box[4] && d.z() >= box[2] && d.z() <= box[5];
    }
}
