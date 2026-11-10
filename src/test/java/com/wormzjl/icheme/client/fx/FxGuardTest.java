package com.wormzjl.icheme.client.fx;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CUSTOM_SHADER_SYSTEM_PLAN.md section 4.8: a changed API turns the integration off, without an exception, logging once. */
class FxGuardTest {
    private final List<String> logged = new ArrayList<>();
    private final FxGuard guard = new FxGuard("Stub", () -> "9.9.9", (message, error) -> logged.add(message));

    @Test
    void aMissingMethodTurnsTheIntegrationOffAndLogsOnce() {
        int[] calls = {0};
        boolean first = guard.test(() -> {
            calls[0]++;
            throw new NoSuchMethodError("isShaderPackInUse");
        }, false);
        assertFalse(first);
        assertTrue(guard.disabled());
        assertEquals(1, logged.size());
        assertTrue(logged.getFirst().contains("Stub") && logged.getFirst().contains("9.9.9"), logged.getFirst());
        // Later calls return the fallback without running or logging.
        assertTrue(guard.test(() -> {
            calls[0]++;
            return false;
        }, true));
        assertEquals("x", guard.call(() -> "y", "x"));
        assertFalse(guard.run(() -> calls[0]++));
        assertEquals(1, calls[0]);
        assertEquals(1, logged.size());
    }

    @Test
    void otherLinkageErrorsAndRuntimeExceptionsAreCaught() {
        for (var error : List.<Runnable>of(() -> {
            throw new NoClassDefFoundError("net/irisshaders/iris/api/v0/IrisApi");
        }, () -> {
            throw new AbstractMethodError();
        }, () -> {
            throw new IncompatibleClassChangeError();
        }, () -> {
            throw new IllegalStateException();
        })) {
            var g = new FxGuard("Stub", () -> "1", (m, e) -> logged.add(m));
            assertFalse(g.run(error));
            assertTrue(g.disabled());
        }
        assertEquals(4, logged.size());
    }

    @Test
    void anErrorThatIsNotAnApiChangeStillPropagates() {
        assertThrows(StackOverflowError.class, () -> guard.run(() -> {
            throw new StackOverflowError();
        }));
        assertFalse(guard.disabled());
    }

    @Test
    void aBrokenVersionLookupStillLogs() {
        var g = new FxGuard("Stub", () -> {
            throw new NoSuchMethodError("getVersion");
        }, (m, e) -> logged.add(m));
        g.disable("API v0 revision 1 < 2");
        assertTrue(g.disabled());
        assertEquals(1, logged.size());
        assertTrue(logged.getFirst().contains("unknown version"));
    }

    @Test
    void workingCallsPassThrough() {
        int three = guard.call(() -> 3, -1);
        assertEquals(3, three);
        assertTrue(guard.test(() -> true, false));
        assertFalse(guard.disabled());
        assertTrue(logged.isEmpty());
    }

    @Test
    void effectBreakersAreOnePerType() {
        assertSame(FxGuard.effect("test_flame"), FxGuard.effect("test_flame"));
        FxGuard.effect("test_broken").run(() -> {
            throw new IllegalArgumentException("bad quad");
        });
        assertTrue(FxGuard.effect("test_broken").disabled());
        assertFalse(FxGuard.effect("test_flame").disabled());
    }
}
