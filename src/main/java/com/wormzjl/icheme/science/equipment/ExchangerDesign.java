package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Orientation;

import java.util.List;

/** A TEMA AES shell-and-tube exchanger ({@link ProcessUnit#exchanger}): shell 0.5 to 2.5 m, tubes three shells to 12 m long. */
final class ExchangerDesign extends UnitDesign {
    @Override
    public double minSize() {
        return .5;
    }

    @Override
    public double maxSize() {
        return 2.5;
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
        return 12;
    }

    /** The shell's bottom 2 m up leaves a block under its bottom nozzles for their pipes. */
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
        return "shell";
    }

    @Override
    public String lengthRow() {
        return "tubes";
    }

    @Override
    public String supportRow() {
        return "shell_bottom";
    }

    @Override
    public Line held(EquipmentShape shape) {
        return new Line("surface", number(ProcessUnit.tubeSurface(shape.diameter(), shape.tangentLength())));
    }

    @Override
    public String variant(EquipmentShape shape) {
        return shape.exchangerType()==ExchangerType.STANDARD?"":"."+shape.exchangerType().key();
    }

    @Override
    public List<Line> datasheet(EquipmentShape shape) {
        double d = shape.diameter(), tubes = shape.tangentLength();
        return List.of(new Line("shell", size(d)), new Line("tubes", number(tubes), number(ProcessUnit.tubeSurface(d, tubes))));
    }

    @Override
    void build(ProcessUnit unit) {
        unit.exchanger();
    }
}
