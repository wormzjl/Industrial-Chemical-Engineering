package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Orientation;

import java.util.List;

/**
 * An air cooler bank of bays ({@link ProcessUnit#airCooler}, {@link ProcessUnit.Bank}): bays 1.5 to 3 m wide and 2 to 6
 * m long where their fans cover {@link ProcessUnit#FAN_COVERAGE} of the bundle face (API 661). The constructor sets the
 * bay and the bank on rows of their own.
 */
final class AirCoolerDesign extends UnitDesign {
    @Override
    public double minSize() {
        return 1.5;
    }

    @Override
    public double maxSize() {
        return 3;
    }

    @Override
    public double sizeStep() {
        return .5;
    }

    /** The shortest bay of width {@code size} whose fans cover {@link ProcessUnit#FAN_COVERAGE} of its bundle face. */
    @Override
    public int minLength(double size) {
        int length = ProcessUnit.MIN_BAY_LENGTH;
        while (length < ProcessUnit.MAX_BAY_LENGTH && ProcessUnit.coverage(size, length) < ProcessUnit.FAN_COVERAGE) length++;
        return length;
    }

    @Override
    public int maxLength(double size) {
        return ProcessUnit.MAX_BAY_LENGTH;
    }

    /**
     * The bundle 3 m up (owner request 2026-10-16) keeps the fans' ring inlets at least 0.6 fan diameters above grade,
     * more than the half diameter of free air a forced-draft fan usually takes.
     */
    @Override
    public int minSupport(double size, int length) {
        return 3;
    }

    /** A new bank's bundle 4 m up (owner request 2026-10-16), 1.68 m or more under its motor beams. */
    @Override
    public int defaultSupport(double size, int length) {
        return 4;
    }

    @Override
    public Orientation orientation() {
        return Orientation.HORIZONTAL;
    }

    @Override
    public boolean banks() {
        return true;
    }

    @Override
    public boolean moves() {
        return true;
    }

    @Override
    void check(double size, int length, int support, ProcessUnit.Bank bank) {
        if (ProcessUnit.coverage(size, length) < ProcessUnit.FAN_COVERAGE)
            throw new IllegalArgumentException("An air cooler bay of " + size + " × " + length + " m has no room for fans covering " + ProcessUnit.FAN_COVERAGE + " of its bundle");
    }

    @Override
    public String sizeRow() {
        return "bay";
    }

    @Override
    public String supportRow() {
        return "bundle_height";
    }

    @Override
    public Line held(EquipmentShape shape) {
        double d = shape.diameter(), length = shape.tangentLength();
        var fans = ProcessUnit.fans(d, length);
        int bays = shape.bank().bays();
        return new Line("bay_face", number(bays * ProcessUnit.bundleFace(d, length)), bays * fans.count(), size(fans.diameter()));
    }

    @Override
    public List<Line> datasheet(EquipmentShape shape) {
        double d = shape.diameter(), length = shape.tangentLength();
        var fans = ProcessUnit.fans(d, length);
        // The process side's bare tube surface and the fans' power at full speed (batch 2026-10-27-heat-exchanger-design).
        double surface = HeatSides.of(shape).getFirst().area();
        return List.of(new Line("bank", shape.bank().parallel(), shape.bank().series()), new Line("bay", size(d), number(length), number(ProcessUnit.bundleFace(d, length))),
                new Line("fans", fans.count(), size(fans.diameter()), Math.round(100 * ProcessUnit.coverage(d, length))),
                new Line("air_side", number(surface), number(HeatSides.fanPower(surface, 1) / 1000)));
    }

    @Override
    void build(ProcessUnit unit) {
        unit.airCooler();
    }
}
