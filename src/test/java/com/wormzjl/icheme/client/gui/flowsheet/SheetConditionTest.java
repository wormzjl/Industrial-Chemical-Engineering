package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.science.tutorial.cards.SmallUnitData;
import com.wormzjl.icheme.science.tutorial.guide.SheetCondition;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The criterion {@code icheme:sheet_run}'s condition (batch 2026-11-12-tutorial-system, P4b): results that ran on the stored
 * sheet; with an outlet limit, a fired heater set to its outlet at or under it. Read on the bundled small crude unit.
 */
class SheetConditionTest {
    @Test
    void aRunCountsAndTheLoweredHeaterMeetsTheHeatStep() {
        var sheet = SheetFixture.example(SmallUnitRecorder.EXAMPLE);
        var any = new SheetCondition(Optional.empty());
        assertTrue(any.matches(sheet, true));
        assertFalse(any.matches(sheet, false), "no results on the stored sheet: not run");
        var heat = new SheetCondition(Optional.of(SmallUnitData.lowered().heaterKelvin()));
        assertFalse(heat.matches(sheet, true), "the bundled outlet is above the step's");
        assertTrue(heat.matches(SmallUnitRecorder.withHeater(sheet, SmallUnitData.lowered().heaterKelvin()), true), "lowered to the step's outlet");
        assertTrue(heat.matches(SmallUnitRecorder.withHeater(sheet, SmallUnitData.lowered().heaterKelvin() - 10), true), "or under it");
        assertFalse(heat.matches(SmallUnitRecorder.withHeater(sheet, SmallUnitData.lowered().heaterKelvin()), false), "but run");
        assertEquals(SmallUnitData.bundled().heaterKelvin(), sheet.units().stream().filter(u -> u.id().equals(SmallUnitRecorder.HEATER)).findFirst().orElseThrow().params().number("temperature", Double.NaN), 1e-9, "the recording's bundled outlet is the example's");
        assertThrows(IllegalArgumentException.class, () -> new SheetCondition(Optional.of(0.0)));
    }
}
