package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Orientation;
import com.wormzjl.icheme.science.equipment.design.UnitRatings;

import java.util.List;

/**
 * An API 560 cabin heater ({@link ProcessUnit#cabinHeater}): radiant section 4 to 12 m wide and 1.2 widths to 48 m
 * long; above {@link ProcessUnit#MAX_CABIN_CELL} a twin cell of two half-length cells.
 */
final class CabinHeaterDesign extends UnitDesign {
    @Override
    public double minSize() {
        return 4;
    }

    @Override
    public double maxSize() {
        return 12;
    }

    @Override
    public double sizeStep() {
        return 1;
    }

    @Override
    public int minLength(double size) {
        return Math.max(6, (int) Math.ceil(1.2 * size - 1e-9));
    }

    @Override
    public int maxLength(double size) {
        return 2 * ProcessUnit.MAX_CABIN_CELL;
    }

    /** The floor 3 m up: room to stand under the burners. */
    @Override
    public int minSupport(double size, int length) {
        return 3;
    }

    @Override
    public Orientation orientation() {
        return Orientation.HORIZONTAL;
    }

    @Override
    public String sizeRow() {
        return "radiant_width";
    }

    @Override
    public String lengthRow() {
        return "radiant_length";
    }

    @Override
    public String supportRow() {
        return "heater_floor";
    }

    @Override
    public Line held(EquipmentShape shape) {
        return new Line("heater_duty", number(UnitRatings.heaterDuty(EquipmentShape.Kind.CABIN_HEATER, shape.diameter(), shape.tangentLength())));
    }

    @Override
    public List<Line> datasheet(EquipmentShape shape) {
        double d = shape.diameter(), length = shape.tangentLength();
        int cells = ProcessUnit.cabinCells(length);
        return List.of(new Line("radiant.cabin_heater" + (cells > 1 ? ".twin" : ""), size(d), number(length / cells)),
                new Line("duty", number(UnitRatings.heaterDuty(EquipmentShape.Kind.CABIN_HEATER, d, length))));
    }

    @Override
    void build(ProcessUnit unit) {
        unit.cabinHeater();
    }
}
