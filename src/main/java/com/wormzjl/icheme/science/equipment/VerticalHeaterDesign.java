package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Orientation;
import com.wormzjl.icheme.science.equipment.design.UnitRatings;

import java.util.List;

/**
 * An API 560 vertical cylindrical heater ({@link ProcessUnit#verticalHeater}): radiant section 2.5 to 12 m across, 1.5
 * to 2.75 diameters high (API 560's slenderness) and at most {@link ProcessUnit#MAX_RADIANT_HEIGHT} m.
 */
final class VerticalHeaterDesign extends UnitDesign {
    @Override
    public double minSize() {
        return 2.5;
    }

    @Override
    public double maxSize() {
        return 12;
    }

    @Override
    public double sizeStep() {
        return .5;
    }

    @Override
    public int minLength(double size) {
        return Math.max(4, (int) Math.ceil(1.5 * size - 1e-9));
    }

    @Override
    public int maxLength(double size) {
        return Math.min(ProcessUnit.MAX_RADIANT_HEIGHT, (int) Math.floor(2.75 * size + 1e-9));
    }

    /** The floor 3 m up: room to stand under the burners. */
    @Override
    public int minSupport(double size, int length) {
        return 3;
    }

    @Override
    public Orientation orientation() {
        return Orientation.VERTICAL;
    }

    @Override
    public String sizeRow() {
        return "radiant_diameter";
    }

    @Override
    public String lengthRow() {
        return "radiant_height";
    }

    @Override
    public String supportRow() {
        return "heater_floor";
    }

    @Override
    public Line held(EquipmentShape shape) {
        return new Line("heater_duty", number(UnitRatings.heaterDuty(EquipmentShape.Kind.VERTICAL_HEATER, shape.diameter(), shape.tangentLength())));
    }

    @Override
    public List<Line> datasheet(EquipmentShape shape) {
        double d = shape.diameter(), height = shape.tangentLength();
        return List.of(new Line("radiant.vertical_heater", size(d), number(height)),
                new Line("duty", number(UnitRatings.heaterDuty(EquipmentShape.Kind.VERTICAL_HEATER, d, height))));
    }

    @Override
    void build(ProcessUnit unit) {
        unit.verticalHeater();
    }
}
