package com.wormzjl.icheme.science.tutorial;

import com.wormzjl.icheme.science.tutorial.scene.Series;
import com.wormzjl.icheme.science.tutorial.scene.Value;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** A recording's series (batch 2026-11-12-tutorial-system, P2b): linear between points, held at its ends, read from JSON. */
class SeriesTest {
    @Test
    void itInterpolatesAndHoldsItsEnds() {
        var s = new Series(new double[]{0, 10, 20}, new double[]{300, 320, 310});
        assertEquals(300, s.at(-5));
        assertEquals(310, s.at(25));
        assertEquals(310, s.at(5));
        assertEquals(315, s.at(15));
        assertEquals(1, s.index(5));
        assertEquals(1, s.index(10));
        assertEquals(2, s.index(10.5));
        assertEquals(5, s.reaches(310), 1e-12);
        assertTrue(Double.isNaN(s.reaches(400)));
        var gap = new Series(new double[]{0, 10}, new double[]{Double.NaN, 5});
        assertEquals(5, gap.at(3), "a step after a missing point takes its end's value");
        assertThrows(IllegalArgumentException.class, () -> new Series(new double[]{1, 0}, new double[]{0, 0}));
        assertThrows(IllegalArgumentException.class, () -> new Series(new double[]{0}, new double[]{0, 1}));
    }

    @Test
    void aTimelineReplaysARecordingOnTheFilmsTime() {
        var s = new Series(new double[]{0, 100, 1000}, new double[]{0, 1, 2});
        var line = new Value.Timeline(new double[]{0, 5, 10}, new double[]{0, 100, 1000});
        var v = line.replay(s);
        assertEquals(0, v.at(-1));
        assertEquals(.5, v.at(2.5), 1e-12);
        assertEquals(1.5, v.at(7.5), 1e-12);
        assertEquals(2, v.at(11));
        assertEquals(550, line.recording(7.5), 1e-12);
        assertEquals(3, Value.of(3).at(42));
        assertThrows(IllegalArgumentException.class, () -> new Value.Timeline(new double[]{0, 0}, new double[]{0, 1}));
    }

    @Test
    void itReadsARecording() {
        var json = "{\"setup\":{\"charge_kg\":2.5,\"finish\":\"DONE\"},\"series\":{\"time\":[0,1,2],\"pot\":[300,null,320],\"status\":[\"HEATING\",\"DISTILLING\",\"DISTILLING\"]},"
                + "\"lines\":{\"first\":{\"time\":1,\"pot_kelvin\":310}}}";
        var run = Series.read(() -> new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        assertEquals(2.5, run.setup().get("charge_kg"));
        assertEquals("DONE", run.setupWords().get("finish"));
        assertTrue(Double.isNaN(run.series("pot").at(.5)), "a step ending at a missing point has no value");
        assertEquals(320, run.series("pot").at(1.5), "a step after a missing point takes its end's value");
        assertEquals("DISTILLING", run.word("status", .5));
        assertEquals("HEATING", run.word("status", 0));
        assertEquals(310, run.line("first", "pot_kelvin"));
        assertTrue(Double.isNaN(run.line("kerosene", "time")));
        assertEquals(2, run.time().end());
        assertThrows(IllegalArgumentException.class, () -> run.series("none"));
    }
}
