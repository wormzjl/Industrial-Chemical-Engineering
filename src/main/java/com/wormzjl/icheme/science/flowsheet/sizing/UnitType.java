package com.wormzjl.icheme.science.flowsheet.sizing;

import com.wormzjl.icheme.science.equipment.EquipmentShape;

/**
 * What a tile with one function is built as (batch 2026-11-02-process-simulator, {@code SIZING_PLAN.md} section 3.10). The run needs
 * none, as the world's law is the same for each pair; Auto proposes one from the numbers, and a key keeps the player's. Saved by
 * name. The steam turbine (W8) is built one way, so it has no type here.
 */
public enum UnitType {
    VERTICAL_HEATER(EquipmentShape.Kind.VERTICAL_HEATER),
    CABIN_HEATER(EquipmentShape.Kind.CABIN_HEATER),
    FLOATING_ROOF(EquipmentShape.Kind.TANK),
    SPHERE(EquipmentShape.Kind.SPHERE),
    STANDING_DRUM(EquipmentShape.Kind.DRUM),
    LYING_DRUM(EquipmentShape.Kind.DRUM),
    PISTON(EquipmentShape.Kind.COMPRESSOR),
    TURBO(EquipmentShape.Kind.CENTRIFUGAL_COMPRESSOR),
    /** The Cooler tile on air (C2, {@code COOLER_MODEL_PLAN.md} section 3 item 4): an air cooler. */
    AIR_COOLER(EquipmentShape.Kind.AIR_COOLER),
    /** The Cooler tile on tower water: an exchanger with the process on its shell and the water on its tubes. */
    WATER_COOLER(EquipmentShape.Kind.EXCHANGER);

    private final EquipmentShape.Kind kind;

    UnitType(EquipmentShape.Kind kind) {
        this.kind = kind;
    }

    public EquipmentShape.Kind kind() {
        return kind;
    }
}
