package com.wormzjl.icheme.science.tutorial.cards;

import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.flowsheet.Tiles;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.flowsheet.sizing.CoolerSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.HeaterSizing;
import com.wormzjl.icheme.science.tutorial.equipment.EquipmentInfo;
import com.wormzjl.icheme.science.tutorial.guide.PageFile;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The crude unit's map card (batch 2026-11-12-tutorial-system, P5): its symbols held to the bundled sheet's units. Every unit
 * of {@code small_crude_unit.json} is one place to press, on the card, apart from the others, drawn with its tile's symbol;
 * each opens the page of the kind it is built as: the heater's and the cooler's the types Size all proposes for the
 * recording's duty and the sheet's outlet. Its step is done once one page was opened.
 */
class UnitMapCardTest {
    @Test
    void everySymbolIsASheetUnitAndOpensItsKindsPage() {
        var card = (UnitMap) Objects.requireNonNull(Cards.build(UnitMap.NAME));
        var pages = PageFile.load();
        var units = card.flowsheet().units();
        for (var area : new Rect[]{new Rect(20, 40, 300, 150), new Rect(10, 30, 180, 120)}) {
            var hits = card.hits(area, 0);
            assertEquals(units.size(), hits.size(), "one place for each unit");
            var seen = new HashSet<Integer>();
            for (var h : hits) {
                assertTrue(seen.add(h.index()), "each unit once");
                var unit = units.get(h.index());
                assertTrue(h.rect().inside(area), unit.id() + ": on the card");
                assertNotNull(Tiles.symbol(unit.type()), unit.id() + ": drawn with the simulator's own symbol");
                for (var other : hits) if (other != h) assertFalse(h.rect().overlaps(other.rect()), unit.id() + " apart from " + units.get(other.index()).id());
                var page = card.link(h.index());
                assertNotNull(page, unit.id() + ": opens a page");
                assertNotNull(pages.page(page), unit.id() + ": its page " + page);
                assertTrue(pages.inUnit(page), unit.id() + ": a page of the unit's chain");
            }
            // Each unit's tag on the card, whole, apart from every other tag (a wider font than the game's).
            var tags = card.tags(hits, area, com.wormzjl.icheme.client.gui.common.kit.Measure.fixed(6));
            for (int i = 0; i < tags.size(); i++) {
                assertTrue(tags.get(i).inside(area), units.get(hits.get(i).index()).id() + ": its tag on the card");
                for (int j = i + 1; j < tags.size(); j++)
                    assertFalse(tags.get(i).overlaps(tags.get(j)), units.get(hits.get(i).index()).id() + "'s tag apart from " + units.get(hits.get(j).index()).id() + "'s");
            }
        }
        // The kinds the sheet's typed units are built as: H-101 at the recording's duty, E-102 at its outlet.
        double duty = SmallUnitData.bundled().duty("H-101") / 1e6;
        assertEquals(EquipmentShape.Kind.VERTICAL_HEATER, HeaterSizing.type(new HeaterSizing.Load(duty, null)).kind(), "H-101 is built as a vertical heater");
        assertEquals(EquipmentInfo.itemId(EquipmentShape.Kind.VERTICAL_HEATER), UnitMap.PAGES.get("fired_heater"));
        var cooler = units.stream().filter(u -> u.type().equals("cooler")).findFirst().orElseThrow();
        assertEquals(EquipmentShape.Kind.AIR_COOLER, CoolerSizing.type(cooler.params().number("temperature", 0)).kind(), "E-102 is built as an air cooler");
        assertEquals(EquipmentInfo.itemId(EquipmentShape.Kind.AIR_COOLER), UnitMap.PAGES.get("cooler"));
        assertFalse(card.done(0, Set.of()));
        assertTrue(card.done(0, Set.of(3)), "done once a page was opened from it");
        assertNull(card.link(-1));
    }
}
