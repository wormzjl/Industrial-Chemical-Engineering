package com.wormzjl.icheme.science.equipment;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The creative motor's data socket turns with the block (part C of PUMP_SPEED_SHUT_VALVE_REVIEW.md). */
class CreativeMotorGeometryTest {
    /** A quarter turn clockwise seen from above about the block's centre, sixteenths: north's model to east's. */
    private static double[] turn(double[] p) {
        return new double[] {16 - p[2], p[1], p[0]};
    }

    @Test void theSocketTurnsWithTheFourFacings() {
        assertArrayEquals(CreativeMotorGeometry.SOCKET, CreativeMotorGeometry.socket(2), 0, "north is the model's own");
        // North, east, south, west: each a quarter turn of the one before.
        int[] ring = {2, 5, 3, 4};
        for (int i = 1; i < 4; i++)
            assertArrayEquals(turn(CreativeMotorGeometry.socket(ring[i - 1])), CreativeMotorGeometry.socket(ring[i]), 1e-12, "facing " + ring[i]);
        assertArrayEquals(turn(CreativeMotorGeometry.socket(4)), CreativeMotorGeometry.socket(2), 1e-12, "four turns come back");
        for (int f : ring) {
            for (double v : CreativeMotorGeometry.socket(f)) assertTrue(v >= 0 && v <= 16, "inside the block: " + f);
            assertEquals(CreativeMotorGeometry.SOCKET_OUTWARD < 2 ? CreativeMotorGeometry.SOCKET_OUTWARD : -1,
                    CreativeMotorGeometry.SOCKET_OUTWARD < 2 ? CreativeMotorGeometry.socketOutward(f) : -1, "up and down stay: " + f);
        }
        assertThrows(IllegalArgumentException.class, () -> CreativeMotorGeometry.socket(1));
    }

    /** The shaft end stays where placed trains have it: the socket is not on the shaft's face, the north one for the model. */
    @Test void theSocketLeavesTheShaftsFaceFree() {
        assertTrue(CreativeMotorGeometry.SOCKET_OUTWARD != 2, "not out of the shaft's face");
        assertTrue(CreativeMotorGeometry.SOCKET_OUTWARD != 0, "not under the base");
    }
}
