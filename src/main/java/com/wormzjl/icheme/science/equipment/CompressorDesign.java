package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Orientation;
import com.wormzjl.icheme.science.equipment.design.UnitRatings;

import java.util.List;

/**
 * An API 618 balanced-opposed compressor ({@link ProcessUnit#compressor}): bore 0.15 to 1.2 m, 2 to
 * {@link ProcessUnit#MAX_THROWS} throws in opposed pairs, on a foundation high enough that its discharge bottles clear
 * grade by 0.3 m and its motor by 0.1 m.
 */
final class CompressorDesign extends UnitDesign {
    @Override
    public double minSize() {
        return .15;
    }

    @Override
    public double maxSize() {
        return 1.2;
    }

    @Override
    public double sizeStep() {
        return ProcessUnit.SUCTION_STEP;
    }

    @Override
    public int minLength(double size) {
        return 2;
    }

    @Override
    public int maxLength(double size) {
        return ProcessUnit.MAX_THROWS;
    }

    @Override
    public int lengthStep() {
        return 2;
    }

    @Override
    public int minSupport(double size, int length) {
        var c = ProcessUnit.Compressor.of(size, length);
        int h = 1;
        while (h < EquipmentShape.MAX_SUPPORT && !c.clears(h)) h++;
        return h;
    }

    @Override
    public Orientation orientation() {
        return Orientation.HORIZONTAL;
    }

    @Override
    public boolean moves() {
        return true;
    }

    @Override
    void check(double size, int length, int support, ProcessUnit.Bank bank) {
        if (length % 2 != 0) throw new IllegalArgumentException("A balanced-opposed compressor has an even number of throws, not " + length);
    }

    @Override
    public String sizeRow() {
        return "bore";
    }

    @Override
    public String lengthRow() {
        return "throws";
    }

    @Override
    public boolean lengthInMetres() {
        return false;
    }

    @Override
    public String supportRow() {
        return "foundation";
    }

    @Override
    public Line held(EquipmentShape shape) {
        double d = shape.diameter();
        return new Line("displacement", whole(UnitRatings.displacement(d, (int) shape.tangentLength())), whole(UnitRatings.compressorSpeed(d)));
    }

    @Override
    public List<Line> datasheet(EquipmentShape shape) {
        double d = shape.diameter();
        int throwCount = (int) shape.tangentLength();
        return List.of(new Line("compressor", throwCount, size(d), size(UnitRatings.stroke(d))),
                new Line("displacement", whole(UnitRatings.displacement(d, throwCount)), whole(UnitRatings.compressorSpeed(d))));
    }

    @Override
    void build(ProcessUnit unit) {
        unit.compressor();
    }
}
