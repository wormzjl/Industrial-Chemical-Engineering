package com.wormzjl.icheme.client.fx;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FxGlideTest {
    @Test
    void easesOverTwoSecondsFromArrival() {
        var glide = new FxGlide(0, 10);
        glide.arrive(new double[]{4, 30}, 100);
        assertArrayEquals(new double[]{0, 10}, glide.at(100), 1e-12);
        assertArrayEquals(new double[]{2, 20}, glide.at(101), 1e-12);
        assertArrayEquals(new double[]{4, 30}, glide.at(102), 1e-12);
        assertArrayEquals(new double[]{4, 30}, glide.at(500), 1e-12);
        assertFalse(glide.settled(101.9));
        assertTrue(glide.settled(102));
        assertEquals(.15625, FxGlide.ease(.25), 1e-12);
    }

    @Test
    void aNewSnapshotMidGlideStartsFromWhereItStands() {
        var glide = new FxGlide(0);
        glide.arrive(new double[]{10}, 0);
        double mid = glide.at(0, 1);
        glide.arrive(new double[]{0}, 1);
        assertEquals(mid, glide.at(0, 1), 1e-12);
        assertArrayEquals(new double[]{mid}, glide.from(), 1e-12);
        assertEquals(0, glide.at(0, 3), 1e-12);
    }

    @Test
    void restsBeforeTheFirstSnapshotAndSnapsOnANewLength() {
        var glide = new FxGlide(3);
        assertEquals(3, glide.at(0, 0), 1e-12);
        assertTrue(glide.settled(0));
        glide.arrive(new double[]{1, 2}, 5);
        assertArrayEquals(new double[]{1, 2}, glide.at(5), 1e-12);
    }

    @Test
    void secondsFollowGameTime() {
        assertEquals(50.5, FxGlide.seconds(1000, 10), 1e-12);
    }
}
