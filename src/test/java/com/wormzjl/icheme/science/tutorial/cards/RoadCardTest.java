package com.wormzjl.icheme.science.tutorial.cards;

import com.wormzjl.icheme.client.gui.common.ConstructorColumn;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.flowsheet.FlowsheetCatalog;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The card road (batch 2026-11-12-tutorial-system, P2a): every kind it names is a constructor equipment, its "modelled"
 * set is the process simulator's catalog's, every box has its lines, and the boxes are pressable and done when all were.
 */
class RoadCardTest {
    @Test
    void everyKindExistsAndTheMarksAreTheCatalogs() {
        var named = new HashSet<String>();
        for (var box : Road.BOXES) named.addAll(box.kinds());
        for (var kind : named) {
            var equipment = ConstructorColumn.Equipment.valueOf(kind);
            boolean modelled = FlowsheetCatalog.of(equipment).coverage() == FlowsheetCatalog.Coverage.MODELLED;
            assertEquals(modelled, Road.MODELLED.contains(kind), kind + ": the catalog's flag");
        }
        assertTrue(named.containsAll(Road.MODELLED));
        var expected = Map.of("well", Road.Mark.NOT_YET, "clean", Road.Mark.NOT_YET, "boil", Road.Mark.GUIDE, "vacuum", Road.Mark.RUNS, "break", Road.Mark.SHAPE,
                "reshape", Road.Mark.NOT_YET, "sulfur", Road.Mark.SHAPE, "blend", Road.Mark.RUNS, "products", Road.Mark.RUNS);
        assertEquals(9, Road.BOXES.size());
        for (var box : Road.BOXES) assertEquals(expected.get(box.id()), Road.mark(box), box.id());
    }

    @Test
    void everyBoxHasItsLinesAndEveryKindItsShortName() throws Exception {
        var english = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/assets/icheme/lang/en_us.json")))
                .getAsJsonObject();
        for (var key : new Road().textKeys()) assertTrue(english.has(key), key);
        for (var box : Road.BOXES) {
            assertFalse(english.get(box.sentenceKey()).getAsString().isBlank(), box.id());
            for (var kind : box.kinds()) assertTrue(english.has(Road.shortKey(kind)), kind);
        }
    }

    @Test
    void theBoxesArePressableAndTheStepIsDoneWhenAllWere() {
        var road = new Road();
        for (var area : new Rect[]{new Rect(100, 30, 310, 150), new Rect(70, 30, 190, 120)}) {
            var hits = road.hits(area, 0);
            assertEquals(9, hits.size());
            for (int i = 0; i < hits.size(); i++) {
                assertEquals(i, hits.get(i).index());
                assertTrue(hits.get(i).rect().inside(area), area + ": box " + i);
                for (int j = 0; j < i; j++) assertFalse(hits.get(i).rect().overlaps(hits.get(j).rect()), "boxes " + j + " and " + i + " overlap");
            }
            assertTrue(hits.get(8).rect().y() > hits.get(0).rect().y(), "the boxes wrap");
        }
        assertFalse(road.done(0, Set.of(0, 1, 2, 3, 4, 5, 6, 7)));
        assertTrue(road.done(0, Set.of(0, 1, 2, 3, 4, 5, 6, 7, 8)));
        assertEquals(Card.Control.NONE, road.control());
    }
}
