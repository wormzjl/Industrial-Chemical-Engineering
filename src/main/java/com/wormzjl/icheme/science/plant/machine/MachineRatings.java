package com.wormzjl.icheme.science.plant.machine;

import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.UnitDesign;

/**
 * A placed machine's law from its shape (finding E9, batch 2026-11-13-codebase-review): the shape's ratings
 * ({@link UnitDesign}) to the control the network registers. Moved unchanged from {@code EquipmentStructure}, which keeps
 * the compatibility repair of a casing saved before W3 ({@code restoredControl}) and reads these.
 */
public final class MachineRatings {
    private MachineRatings() {
    }

    /** A steam ejector's control (batch 2026-11-10-steam-ejector-flow): its curve from its suction bore, no motive until its first slice. */
    public static com.wormzjl.icheme.science.fluid.network.FlowControl.Ejector ejectorControl(EquipmentShape shape) {
        return new com.wormzjl.icheme.science.fluid.network.FlowControl.Ejector(new com.wormzjl.icheme.science.machine.SteamEjector(shape.diameter()));
    }

    /**
     * A shaft-driven machine's control from its shape's design point (W3, readings A3 and A32): a pump on its curve at its
     * duty, with Corripio's efficiency; a centrifugal compressor on its curve at its rating, efficiency 0.78; a reciprocating
     * frame at its displacement; a steam turbine at its rated power and its size's efficiency. A machine's speed is its train's.
     */
    public static com.wormzjl.icheme.science.fluid.network.FlowControl machineControl(EquipmentShape shape) {
        return switch (shape.kind()) {
            case PUMP -> {
                var r = UnitDesign.pumpRating(shape);
                yield new com.wormzjl.icheme.science.fluid.network.FlowControl.Machine(new com.wormzjl.icheme.science.machine.CentrifugalPump(r[0], r[1],
                        com.wormzjl.icheme.science.machine.CentrifugalPump.bestEfficiency(r[0]), UnitDesign.designSpeed(shape)), 0, true);
            }
            case CENTRIFUGAL_COMPRESSOR -> {
                var r = UnitDesign.compressorRating(shape);
                yield new com.wormzjl.icheme.science.fluid.network.FlowControl.Machine(new com.wormzjl.icheme.science.machine.CentrifugalCompressor(r[0], r[1], .78), 0, true);
            }
            case COMPRESSOR -> new com.wormzjl.icheme.science.fluid.network.FlowControl.Compressor(frameDisplacement(shape), 3, true);
            // A vacuum pump casing (batch 2026-11-10-vacuum-pump): its model's curve, on the machine row at its train's speed.
            case LIQUID_RING_PUMP, DRY_SCREW_PUMP -> new com.wormzjl.icheme.science.fluid.network.FlowControl.Machine(UnitDesign.vacuumPump(shape), 0, true);
            case STEAM_TURBINE -> new com.wormzjl.icheme.science.fluid.network.FlowControl.Turbine(com.wormzjl.icheme.science.machine.SteamTurbine.rated(Math.max(1e3, UnitDesign.ratedPower(shape))));
            default -> throw new IllegalArgumentException("Not a shaft-driven machine: " + shape.kind());
        };
    }

    /** A reciprocating frame's registered volume flow, m³/s: its displacement, within the mover's range. */
    public static double frameDisplacement(EquipmentShape shape) {
        return Math.max(1e-4, Math.min(com.wormzjl.icheme.science.fluid.network.FlowControl.Mover.MAXIMUM_VOLUME_FLOW, UnitDesign.displacement(shape)));
    }
}
