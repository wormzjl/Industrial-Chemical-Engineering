package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Orientation;
import com.wormzjl.icheme.science.equipment.design.UnitRatings;

import java.util.List;

/** A kettle reboiler ({@link ProcessUnit#kettle}): bundle 0.5 to 2 m in a shell of 1.6 bundles, tubes three bundles to 10 m long. */
final class KettleDesign extends UnitDesign {
    @Override
    public double minSize() {
        return .5;
    }

    @Override
    public double maxSize() {
        return 2;
    }

    @Override
    public double sizeStep() {
        return ProcessUnit.SHELL_STEP;
    }

    @Override
    public int minLength(double size) {
        return Math.max(2, (int) Math.ceil(3 * size - 1e-9));
    }

    @Override
    public int maxLength(double size) {
        return 10;
    }

    /** The kettle's bottom 2 m up leaves a block under its bottom nozzles for their pipes. */
    @Override
    public int minSupport(double size, int length) {
        return 2;
    }

    @Override
    public Orientation orientation() {
        return Orientation.HORIZONTAL;
    }

    @Override
    public String sizeRow() {
        return "bundle";
    }

    @Override
    public String lengthRow() {
        return "tubes";
    }

    @Override
    public String supportRow() {
        return "kettle_bottom";
    }

    @Override
    public Line held(EquipmentShape shape) {
        double d = shape.diameter();
        return new Line("kettle_surface", size(UnitRatings.kettleShell(d)), number(UnitRatings.tubeSurface(d, shape.tangentLength())));
    }

    @Override
    public List<Line> datasheet(EquipmentShape shape) {
        double d = shape.diameter(), tubes = shape.tangentLength();
        return List.of(new Line("bundle", size(d), size(UnitRatings.kettleShell(d))), new Line("tubes", number(tubes), number(UnitRatings.tubeSurface(d, tubes))));
    }

    @Override
    void build(ProcessUnit unit) {
        unit.kettle();
    }
}
