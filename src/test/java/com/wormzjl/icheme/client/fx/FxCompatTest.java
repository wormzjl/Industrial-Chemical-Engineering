package com.wormzjl.icheme.client.fx;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The loader of CUSTOM_SHADER_SYSTEM_PLAN.md section 4.8 with stub implementations: a missing class, a failing gate, a
 * throwing constructor or a stub API that has lost a method all leave the fallback, without an exception. Runs without
 * Iris on the classpath, which also proves the effects core loads without it.
 */
class FxCompatTest {
    private final List<String> logged = new ArrayList<>();

    public static final class Good implements FxCompat.Iris {
        public Good() {
        }

        @Override
        public boolean packInUse() {
            return true;
        }

        @Override
        public boolean shadowPass() {
            return false;
        }

        @Override
        public boolean known() {
            return true;
        }

        @Override
        public String status() {
            return "stub 1.0";
        }
    }

    /** Reports an API revision older than the one compiled against. */
    public static final class Old implements FxCompat.Iris {
        public Old() {
        }

        @Override
        public boolean compatible() {
            return false;
        }

        @Override
        public boolean packInUse() {
            return false;
        }

        @Override
        public boolean shadowPass() {
            return false;
        }

        @Override
        public boolean known() {
            return true;
        }

        @Override
        public String status() {
            return "stub 0.1, API v0 revision 0";
        }
    }

    /** Its version check calls a method the mod no longer has. */
    public static final class Removed implements FxCompat.Iris {
        public Removed() {
        }

        @Override
        public boolean compatible() {
            throw new NoSuchMethodError("getMinorApiRevision");
        }

        @Override
        public boolean packInUse() {
            return false;
        }

        @Override
        public boolean shadowPass() {
            return false;
        }

        @Override
        public boolean known() {
            return true;
        }

        @Override
        public String status() {
            return "stub";
        }
    }

    public static final class Throwing implements FxCompat.Iris {
        public Throwing() {
            throw new NoClassDefFoundError("net/irisshaders/iris/api/v0/IrisApi");
        }

        @Override
        public boolean packInUse() {
            return false;
        }

        @Override
        public boolean shadowPass() {
            return false;
        }

        @Override
        public boolean known() {
            return true;
        }

        @Override
        public String status() {
            return "never";
        }
    }

    private FxCompat.Iris load(boolean present, String type) {
        return FxCompat.load(id -> present, "stub", type, FxCompat.Iris.class, FxCompat.Iris.UNKNOWN, (m, e) -> logged.add(m));
    }

    private static String name(Class<?> type) {
        return type.getName();
    }

    @Test
    void aPresentCompatibleImplementationIsKept() {
        assertInstanceOf(Good.class, load(true, name(Good.class)));
        assertTrue(logged.isEmpty());
    }

    @Test
    void anAbsentModNeverLoadsTheClass() {
        assertSame(FxCompat.Iris.UNKNOWN, load(false, name(Throwing.class)));
        assertTrue(logged.isEmpty());
    }

    @Test
    void aMissingClassGivesTheFallback() {
        assertSame(FxCompat.Iris.UNKNOWN, load(true, "com.wormzjl.icheme.client.fx.compat.missing.Bridge"));
        assertEquals(1, logged.size());
    }

    @Test
    void aFailedVersionGateGivesTheFallback() {
        assertSame(FxCompat.Iris.UNKNOWN, load(true, name(Old.class)));
        assertSame(FxCompat.Iris.UNKNOWN, load(true, name(Removed.class)));
        assertSame(FxCompat.Iris.UNKNOWN, load(true, name(Throwing.class)));
        assertSame(FxCompat.Iris.UNKNOWN, load(true, name(String.class)));
        assertEquals(4, logged.size());
        assertTrue(logged.getFirst().contains("revision 0"), logged.getFirst());
    }

    @Test
    void aThrowingModListGivesTheFallback() {
        var result = FxCompat.load(id -> {
            throw new IllegalStateException("no mod list");
        }, "stub", name(Good.class), FxCompat.Iris.class, FxCompat.Iris.UNKNOWN, (m, e) -> logged.add(m));
        assertSame(FxCompat.Iris.UNKNOWN, result);
    }

    @Test
    void outsideAGameNoModIsLoaded() {
        assertFalse(FxCompat.loaded(FxCompat.IRIS));
        assertEquals("?", FxCompat.version(FxCompat.IRIS));
    }

    @Test
    void theFallbacksAnswerNoPack() {
        assertFalse(FxCompat.Iris.NONE.packInUse());
        assertFalse(FxCompat.Iris.NONE.shadowPass());
        assertTrue(FxCompat.Iris.NONE.known());
        assertFalse(FxCompat.Iris.UNKNOWN.known());
    }
}
