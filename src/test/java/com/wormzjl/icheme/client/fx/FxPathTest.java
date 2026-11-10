package com.wormzjl.icheme.client.fx;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The path matrix of CUSTOM_SHADER_SYSTEM_PLAN.md sections 4.1 and 4.8, with fake Iris answers. */
class FxPathTest {
    private static FxCompat.Iris iris(boolean known, boolean pack) {
        return new FxCompat.Iris() {
            @Override
            public boolean packInUse() {
                return pack;
            }

            @Override
            public boolean shadowPass() {
                return false;
            }

            @Override
            public boolean known() {
                return known;
            }

            @Override
            public String status() {
                return "fake";
            }
        };
    }

    @Test
    void offAndCompatibleAreAsSet() {
        for (var answer : new FxCompat.Iris[]{FxCompat.Iris.NONE, FxCompat.Iris.UNKNOWN, iris(true, true), iris(true, false)})
            for (boolean failed : new boolean[]{false, true}) {
                assertEquals(FxPath.OFF, FxPath.choose(FxMode.OFF, answer, failed));
                assertEquals(FxPath.COMPATIBLE, FxPath.choose(FxMode.COMPATIBLE, answer, failed));
            }
    }

    @Test
    void autoUsesOurShadersOnlyWithoutAPackAndWithWorkingShaders() {
        assertEquals(FxPath.SHADER, FxPath.choose(FxMode.AUTO, FxCompat.Iris.NONE, false));
        assertEquals(FxPath.SHADER, FxPath.choose(FxMode.AUTO, iris(true, false), false));
        assertEquals(FxPath.COMPATIBLE, FxPath.choose(FxMode.AUTO, iris(true, true), false));
        assertEquals(FxPath.COMPATIBLE, FxPath.choose(FxMode.AUTO, FxCompat.Iris.UNKNOWN, false));
        assertEquals(FxPath.COMPATIBLE, FxPath.choose(FxMode.AUTO, iris(false, false), false));
        assertEquals(FxPath.COMPATIBLE, FxPath.choose(FxMode.AUTO, FxCompat.Iris.NONE, true));
        assertEquals(FxPath.COMPATIBLE, FxPath.choose(FxMode.AUTO, null, false));
    }
}
