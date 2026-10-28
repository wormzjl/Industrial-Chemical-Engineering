package com.wormzjl.icheme.science.column.operation;

/**
 * A running column's port at a boundary valve (plan section 4.2): two inlets, which are sinks at the required pressure,
 * and five product outlets, which are constant-pressure buffers. Saved by name.
 */
public enum Port {
    FEED, STEAM, OFF_GAS, SOUR_WATER, DISTILLATE, DRAW, BOTTOMS;

    /** Whether the port takes from the network (an inlet) rather than gives to it. */
    public boolean inlet() {
        return this == FEED || this == STEAM;
    }

    /** Whether the port's buffer holds a gas product. */
    public boolean gas() {
        return this == OFF_GAS;
    }
}
