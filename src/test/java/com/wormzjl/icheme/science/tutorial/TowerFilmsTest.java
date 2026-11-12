package com.wormzjl.icheme.science.tutorial;

import com.wormzjl.icheme.science.tutorial.films.Films;
import com.wormzjl.icheme.science.tutorial.films.SmallTower;
import com.wormzjl.icheme.science.tutorial.films.StillTray;
import com.wormzjl.icheme.science.tutorial.scene.Frame;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Chapter 3's films (batch 2026-11-12-tutorial-system, P4b): "a tray" (still_tray), three stops, the column of the recording's
 * trays on the still's pot; "the small tower" (small_tower), four stops on the small crude column preset, its products
 * named at the second stop and its pumparound and steam at the third.
 */
class TowerFilmsTest {
    @Test
    void theTrayFilmHasThreeStopsThatReadAndTheRecordingsTrays() {
        var scene = StillTray.build();
        assertEquals(3, scene.stops().size());
        assertEquals(scene.stops(), Objects.requireNonNull(Films.build(StillTray.NAME)).stops(), "the guide opens it by its name");
        FilmChecks.everyStopReads(scene);
        assertEquals(com.wormzjl.icheme.science.tutorial.cards.StillTraysData.trays(), StillTray.shape().still().stages(), "the still built with the recording's trays");
        assertFalse(scene.actors().stream().filter(a -> a.id().equals("trays")).findFirst().orElseThrow().polygons().isEmpty(), "its trays drawn");
        assertTrue(keys(scene.at(scene.stops().getFirst())).contains(StillTray.COLUMN), "the column named at stop 1");
        assertTrue(keys(scene.at(scene.stops().get(1))).contains(StillTray.TRAY), "a tray named at stop 2");
    }

    @Test
    void theTowerFilmHasFourStopsOnThePresetAndNamesItsProducts() {
        var scene = SmallTower.build();
        assertEquals(4, scene.stops().size());
        assertEquals(scene.stops(), Objects.requireNonNull(Films.build(SmallTower.NAME)).stops(), "the guide opens it by its name");
        FilmChecks.everyStopReads(scene);
        var preset = SmallTower.preset();
        assertTrue(preset.trays().trays() > com.wormzjl.icheme.science.tutorial.cards.StillTraysData.trays(), "a tower is many trays");
        // Stop 2, cut open: the feed and the middle draw named at their trays, the plates round and inside the shell.
        assertEquals(List.of(SmallTower.FEED, SmallTower.MIDDLE), keys(scene.at(scene.stops().get(1))), "the feed and the draw at stop 2");
        var shape = SmallTower.shape(preset);
        double[] axis = SmallTower.axis(shape);
        double outer = 0;
        var trays = scene.actors().stream().filter(a -> a.id().equals("trays")).findFirst().orElseThrow();
        for (var polygon : trays.polygons())
            for (int i = 0; i < polygon.size(); i++)
                outer = Math.max(outer, Math.hypot(polygon.positions()[3 * i] - axis[0], polygon.positions()[3 * i + 2] - axis[1]));
        assertTrue(outer < preset.diameter() / 2, "every plate and downcomer inside the shell: " + outer);
        assertTrue(keys(scene.at(scene.stops().get(2))).containsAll(List.of(SmallTower.TOP, SmallTower.MIDDLE, SmallTower.BOTTOM)), "the products at stop 3");
        assertTrue(keys(scene.at(scene.stops().get(3))).containsAll(List.of(SmallTower.LOOP, SmallTower.STEAM)), "the pumparound and the steam at stop 4");
    }

    private static List<String> keys(Frame frame) {
        return frame.labels().stream().map(Frame.LabelFrame::key).toList();
    }
}
