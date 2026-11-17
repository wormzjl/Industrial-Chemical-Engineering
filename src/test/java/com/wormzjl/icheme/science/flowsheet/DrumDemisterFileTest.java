package com.wormzjl.icheme.science.flowsheet;

import com.google.gson.JsonObject;
import com.wormzjl.icheme.science.flowsheet.sizing.DrumSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.SizingFacts;
import com.wormzjl.icheme.science.flowsheet.sizing.UnitType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Fixed drum size's demister in the sheet file (batch 2026-11-17-three-phase-separator): written only when the drum has
 * one, so a drum without keeps the file it had before the field; absent reads none.
 */
class DrumDemisterFileTest {
    private static SizingFacts fixed(DrumSizing.Size size) {
        return SizingFacts.NONE.with("d1", new SizingFacts.UnitFacts(SizingFacts.Mode.FIXED, size.type(), size, null, new JsonObject(), new JsonObject()));
    }

    @Test void aDemisterComesBackFromTheSheetFileAndIsWrittenOnlyWhenTheDrumHasOne() {
        var padded = new DrumSizing.Size(UnitType.STANDING_DRUM, 1.5, 5, false, true);
        var written = SizingFactsFile.write(fixed(padded));
        assertTrue(written.toString().contains("\"demister\":true"), written.toString());
        assertEquals(padded, SizingFactsFile.read(new JsonFields(written, "sizing")).unit("d1").size());
        var bare = new DrumSizing.Size(UnitType.LYING_DRUM, 2, 6, true);
        var plain = SizingFactsFile.write(fixed(bare));
        assertFalse(plain.toString().contains("demister"), plain.toString());
        var back = (DrumSizing.Size) SizingFactsFile.read(new JsonFields(plain, "sizing")).unit("d1").size();
        assertEquals(bare, back);
        assertFalse(back.demister());
    }
}
