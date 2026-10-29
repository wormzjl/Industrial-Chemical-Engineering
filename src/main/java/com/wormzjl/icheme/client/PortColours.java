package com.wormzjl.icheme.client;

import com.wormzjl.icheme.client.gui.common.AssemblyDiagram;
import com.wormzjl.icheme.science.assembly.Blueprint.Slot;
import com.wormzjl.icheme.science.equipment.EquipmentShape.PortClass;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;

/**
 * Port colours shared by the assembly GUI and the laying tool. Assembly nozzles keep their individual design colour
 * after connection; other nozzles use their phase class, grey once connected. Status frames are grey for connected
 * assembly ports and yellow on misplaced parts. RGB, without alpha.
 */
public final class PortColours {
    public static final int VAPOUR = 0xF0F0F0, BULK = 0x55DDFF, LIQUID = 0x3377FF, OVERFLOW = 0xFF9933, CONNECTED = 0x808080, MISPLACED = 0xEF9F27;

    private PortColours() {
    }

    /** The RGB for this assembly nozzle, or -1 when the design has no square for it. A negative mark ordinal matches any nozzle of that role. */
    public static int assembly(AssemblyDiagram diagram, Slot slot, Role role, int ordinal) {
        for (int i = 0; i < diagram.ports().size(); i++) {
            var mark = diagram.ports().get(i);
            if (mark.slot().equals(slot) && mark.role() == role && (mark.ordinal() < 0 || mark.ordinal() == ordinal)) return assemblyColour(i);
        }
        return -1;
    }

    /** A distinct RGB per square in the design's fixed order, independent of its report or connection state. */
    private static int assemblyColour(int index) {
        // Spread neighbouring ports around the hue wheel; alternate brightness and saturation for larger designs.
        double hue = (index * 0.6180339887498949 % 1) * 6;
        int sector = (int) hue;
        int high = index / 6 % 2 == 0 ? 242 : 196;
        int low = index / 12 % 2 == 0 ? high / 3 : high / 6;
        int rising = low + (int) Math.round((high - low) * (hue - sector));
        int falling = high + low - rising;
        return switch (sector) {
            case 0 -> high << 16 | rising << 8 | low;
            case 1 -> falling << 16 | high << 8 | low;
            case 2 -> low << 16 | high << 8 | rising;
            case 3 -> low << 16 | falling << 8 | high;
            case 4 -> rising << 16 | low << 8 | high;
            default -> high << 16 | low << 8 | falling;
        };
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
