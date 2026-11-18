package com.wormzjl.icheme.science.equipment;

/**
 * The creative motor's data socket (PUMP_SPEED_SHUT_VALVE_REVIEW.md, part C; owner: "creative motor should be controllable by
 * data as well"): the gland on its terminal box, in sixteenths of a block for the model facing north (its shaft out of the
 * north face), turned with the block's facing. The model is the main thread's; this class holds the one point both read.
 */
public final class CreativeMotorGeometry {
    private CreativeMotorGeometry() {}

    /** The socket's centre, x, y, z in sixteenths, facing north: the centre of the terminal box's top face. */
    public static final double[] SOCKET = {8, 16, 9.5};
    /** The socket's outward face, facing north (a direction's ordinal: 1, up). */
    public static final int SOCKET_OUTWARD = 1;

    /**
     * {@link #SOCKET} for a motor whose shaft leaves face {@code facing} (a horizontal direction's ordinal: 2 north, 3 south,
     * 4 west, 5 east), as the blockstate turns the model about the vertical: east is north turned a quarter clockwise seen
     * from above, {@code (x, z) -> (16 - z, x)}.
     */
    public static double[] socket(int facing) {
        double x = SOCKET[0], y = SOCKET[1], z = SOCKET[2];
        return switch (facing) {
            case 2 -> new double[] {x, y, z};
            case 5 -> new double[] {16 - z, y, x};
            case 3 -> new double[] {16 - x, y, 16 - z};
            case 4 -> new double[] {z, y, 16 - x};
            default -> throw new IllegalArgumentException("A creative motor faces a horizontal direction, not " + facing);
        };
    }

    /** {@link #SOCKET_OUTWARD} turned as {@link #socket}: up and down stay; a horizontal face turns with the motor. */
    public static int socketOutward(int facing) {
        if (SOCKET_OUTWARD < 2) return SOCKET_OUTWARD;
        int turns = switch (facing) {
            case 2 -> 0;
            case 5 -> 1;
            case 3 -> 2;
            case 4 -> 3;
            default -> throw new IllegalArgumentException("A creative motor faces a horizontal direction, not " + facing);
        };
        // North, east, south, west in clockwise order seen from above.
        int[] ring = {2, 5, 3, 4};
        int at = java.util.Arrays.stream(ring).boxed().toList().indexOf(SOCKET_OUTWARD);
        return ring[(at + turns) % 4];
    }
}
