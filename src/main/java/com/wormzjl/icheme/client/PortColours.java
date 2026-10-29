package com.wormzjl.icheme.client;

import com.wormzjl.icheme.science.equipment.EquipmentShape.PortClass;

/**
 * The colour of each nozzle port class for the pipeline tool's port squares (batch
 * 2026-10-26-multi-machine-framework, plan section 9): a port keeps its class colour while it still needs a pipe and
 * turns grey once connected. A misplaced part's ports take the column GUI's yellow. RGB, without alpha.
 */
public final class PortColours {
    public static final int VAPOUR = 0xF0F0F0, BULK = 0x55DDFF, LIQUID = 0x3377FF, OVERFLOW = 0xFF9933, CONNECTED = 0x808080, MISPLACED = 0xEF9F27;

    private PortColours() {
    }

    public static int of(PortClass port) {
        return switch (port) {
            case VAPOUR -> VAPOUR;
            case BULK -> BULK;
            case LIQUID -> LIQUID;
            case OVERFLOW -> OVERFLOW;
        };
    }
}
