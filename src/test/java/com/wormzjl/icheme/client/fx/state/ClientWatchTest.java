package com.wormzjl.icheme.client.fx.state;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The one client watch without Minecraft (batch 2026-11-13-codebase-review, C11): the nearest {@code maximum} in range,
 * the watch and release differences, a lost first answer asked again, forget, and the reset at a level change.
 */
class ClientWatchTest {
    /** A key at a distance from the player, with a tie-breaking id. */
    private record Key(double distance, long id) {}

    private static final Comparator<Key> NEAREST = Comparator.comparingDouble(Key::distance).thenComparingLong(Key::id);

    /** Every request the watch sent: the watched keys, then the released ones. */
    private record Sent(List<Key> watch, List<Key> unwatch) {}

    private final List<Sent> sent = new ArrayList<>();
    private final ClientWatch<Key, String> watch = new ClientWatch<>(3, (in, out) -> sent.add(new Sent(in, out)));

    private void request(Key... near) {
        watch.request(new ArrayList<>(List.of(near)), NEAREST);
    }

    @Test
    void theNearestMaximumAreWatchedInOrderTiesByTheSecondKey() {
        var a = new Key(9, 1), b = new Key(1, 7), c = new Key(4, 2), d = new Key(4, 1), e = new Key(16, 0);
        request(a, b, c, d, e);
        assertEquals(List.of(new Sent(List.of(b, d, c), List.of())), sent);
        assertTrue(watch.watches(b) && watch.watches(c) && watch.watches(d));
        assertFalse(watch.watches(a) || watch.watches(e), "beyond the maximum: not watched");
    }

    @Test
    void aSecondRequestWatchesTheNewAndReleasesTheGoneWithTheirStates() {
        var a = new Key(1, 1), b = new Key(2, 2), c = new Key(3, 3), d = new Key(.5, 4);
        request(a, b, c);
        for (var k : List.of(a, b, c)) watch.accept(k, "s" + k.id());
        sent.clear();
        request(a, b, c, d);
        assertEquals(List.of(new Sent(List.of(d), List.of(c))), sent, "d is nearer than c: c leaves, d joins");
        assertNull(watch.state(c), "a released key's state goes with it");
        assertEquals("s1", watch.state(a));
        assertEquals(2, watch.stored());
        sent.clear();
        watch.accept(d, "s4");
        request(d, a, b);
        assertEquals(List.of(new Sent(List.of(), List.of())), sent, "nothing changed: an empty request, which the wire drops");
    }

    @Test
    void aWatchedKeyWithNoStateIsAskedAgainUntilItsFirstStateArrives() {
        var a = new Key(1, 1), b = new Key(2, 2);
        request(a, b);
        watch.accept(a, "first");
        sent.clear();
        request(a, b);
        assertEquals(List.of(new Sent(List.of(b), List.of())), sent, "the lost first answer of b is asked for again");
        watch.accept(b, "first");
        sent.clear();
        request(a, b);
        assertEquals(List.of(new Sent(List.of(), List.of())), sent);
    }

    @Test
    void anUnwatchedKeysStateIsIgnored() {
        var a = new Key(1, 1);
        assertNull(watch.accept(a, "early"));
        assertNull(watch.state(a));
        request(a);
        assertNull(watch.accept(a, "one"));
        assertEquals("one", watch.accept(a, "two"), "accept returns the state it replaces");
        assertEquals("two", watch.state(a));
        assertEquals(Set.of(a), watch.states().keySet());
    }

    @Test
    void forgetDropsTheKeysItMatchesWithTheirStatesAndSendsNothing() {
        var a = new Key(1, 1), b = new Key(2, 2), c = new Key(3, 3);
        request(a, b, c);
        watch.accept(a, "a");
        watch.accept(b, "b");
        sent.clear();
        var gone = watch.forget(k -> k.id() >= 2);
        assertEquals(Set.of(b, c), Set.copyOf(gone));
        assertEquals(2, gone.size());
        assertTrue(sent.isEmpty(), "the caller releases what forget returns");
        assertFalse(watch.watches(b) || watch.watches(c));
        assertNull(watch.state(b));
        assertEquals("a", watch.state(a));
        assertTrue(watch.forget(k -> false).isEmpty());
    }

    @Test
    void aResetReleasesEveryWatchOnceWhenConnectedAndClearsTheStates() {
        var a = new Key(1, 1), b = new Key(2, 2);
        request(a, b);
        watch.accept(a, "a");
        sent.clear();
        watch.reset(true);
        assertEquals(1, sent.size());
        assertEquals(List.of(), sent.getFirst().watch());
        assertEquals(Set.of(a, b), Set.copyOf(sent.getFirst().unwatch()));
        assertFalse(watch.watches(a) || watch.watches(b));
        assertEquals(0, watch.stored());
        sent.clear();
        watch.reset(true);
        assertTrue(sent.isEmpty(), "nothing watched: nothing released");
        request(a);
        sent.clear();
        watch.reset(false);
        assertTrue(sent.isEmpty(), "not connected: no request");
        assertFalse(watch.watches(a));
        request(a);
        sent.clear();
        watch.clear();
        assertTrue(sent.isEmpty());
        assertFalse(watch.watches(a));
    }
}
