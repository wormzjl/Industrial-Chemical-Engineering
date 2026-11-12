package com.wormzjl.icheme.science.tutorial;

import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.science.tutorial.films.Films;
import com.wormzjl.icheme.science.tutorial.films.StillInside;
import com.wormzjl.icheme.science.tutorial.films.StillRun;
import com.wormzjl.icheme.science.tutorial.films.StillRunData;
import com.wormzjl.icheme.science.tutorial.scene.Frame;
import com.wormzjl.icheme.science.tutorial.scene.Track;
import org.junit.jupiter.api.Test;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/** The film "a still run" (batch 2026-11-12-tutorial-system, P2b): the recorded run replayed in five stops. */
class StillRunFilmTest {
    @Test
    void itHasFiveStopsAndEveryStopReads() {
        var scene = StillRun.build();
        assertEquals(5, scene.stops().size());
        assertEquals(scene.stops(), Objects.requireNonNull(Films.build(StillRun.NAME)).stops(), "the guide opens it by its name");
        FilmChecks.everyStopReads(scene);
        for (double stop : scene.stops()) {
            var frame = scene.at(stop);
            assertEquals(2, frame.readouts().size(), "the pot and the distilled share at " + stop);
            assertTrue(frame.legend());
        }
    }

    @Test
    void theFirstDropFallsAtTheRecordedTemperature() {
        var scene = StillRun.build();
        var run = StillRunData.load();
        var pot = scene.at(scene.stops().get(1)).readouts().getFirst();
        assertEquals(StillRun.POT, pot.key());
        assertEquals(run.line("first", "pot_kelvin"), pot.value(), 1e-6, "stop 2 shows the pot at the first drop");
        assertEquals(StillRun.SPOUT, scene.at(scene.stops().get(1)).labels().getFirst().key());
        // The readouts follow the recording: the pot never cools while the share distilled never falls.
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
        assertTrue(light < scene.stops().get(1), "the light ones leave before the first drop: " + light);
        assertTrue(middle > scene.stops().get(2), "the middle ones after the cut: " + middle);
    }

    @Test
    void afterTheCutTheSecondDrumTakesTheStreamAndTheFireIsOutBeforeTheEnd() {
        var scene = StillRun.build();
        var cut = scene.at(scene.stops().get(2));
        var later = scene.at(scene.stops().get(3));
        var first = drum(cut, StillRun.FIRST_LIQUID);
        var second = drum(cut, StillRun.SECOND_LIQUID);
        assertTrue(first.top() > first.bottom(), "the first drum holds the light part at the cut");
        assertEquals(second.bottom(), second.top(), 5e-3, "the second drum is (all but) empty at the cut");
        assertEquals(first.top(), drum(later, StillRun.FIRST_LIQUID).top(), 5e-3, "the first drum takes no more after the cut");
        assertTrue(drum(later, StillRun.SECOND_LIQUID).top() > second.top(), "the second drum fills after the cut");
        assertEquals(1, later.pointers().size(), "the switch at the tee");
        assertFalse(scene.at(scene.stops().get(3) + 1).flames().isEmpty(), "the fire burns in stop 5");
        assertTrue(scene.at(scene.length()).flames().isEmpty(), "the fire is out before the end");
    }

    private static Frame.LiquidFrame drum(Frame frame, int rgb) {
        return frame.liquids().stream().filter(l -> l.rgb() == rgb).findFirst().orElseThrow();
    }

    private static boolean inside(double[] box, Frame.DotFrame d) {
        return d.x() >= box[0] && d.x() <= box[3] && d.y() >= box[1] && d.y() <= box[4] && d.z() >= box[2] && d.z() <= box[5];
    }
}
