package com.wormzjl.icheme.science.fluid.network;

import java.util.Objects;

/** Geometry and operating facts of an open water cooling tower; SI throughout. */
public record CoolingTower(Draft draft, double width, double fillDepth, double height, int columns, int rows,
                           Mode mode, double speed, double setPoint) {
    public enum Draft { INDUCED, NATURAL }
    public enum Mode { FAN_SPEED, TARGET_TEMPERATURE }
    public static final double AIR_TEMPERATURE = 298.15, RELATIVE_HUMIDITY = .5, PRESSURE = 101325;
    public static final double BASIN_HEIGHT = 2, BASIN_DEPTH = 2 * BASIN_HEIGHT / 3;
    public CoolingTower {
        Objects.requireNonNull(draft); Objects.requireNonNull(mode);
        for (double v : new double[]{width, fillDepth, height, speed, setPoint})
            if (!Double.isFinite(v)) throw new IllegalArgumentException("Nonfinite cooling tower setting");
        if (columns < 1 || columns > 6 || rows < 1 || rows > 4 || speed < 0 || speed > 1 || setPoint <= 0)
            throw new IllegalArgumentException("Cooling tower setting outside its range");
        if (draft == Draft.INDUCED) {
            if (width < 4 || width > 8 || width % 2 != 0 || fillDepth < 2 || fillDepth > 6 || fillDepth != Math.rint(fillDepth) || height != fillDepth + 8)
                throw new IllegalArgumentException("A fan cell is 4–8 m wide with 2–6 m fill");
        } else if (width < 40 || width > 80 || width % 10 != 0 || height != naturalHeight(width) || fillDepth != 2 || columns != 1 || rows != 1 || mode != Mode.FAN_SPEED || speed != 0)
            throw new IllegalArgumentException("A natural tower is 40–80 m across, with height 1.3 times its diameter");
    }
    public static CoolingTower induced(double width, double fill, int columns, int rows) {
        return new CoolingTower(Draft.INDUCED, width, fill, fill + 8, columns, rows, Mode.FAN_SPEED, 1, 298.15);
    }
    public static CoolingTower natural(double diameter, double height) {
        return new CoolingTower(Draft.NATURAL, diameter, 2, height, 1, 1, Mode.FAN_SPEED, 0, 298.15);
    }
    /** Typical hyperbolic towers are much stockier than a 2:1 cylinder; 1.3 lies within the published 1.2–1.55 H/D range. */
    public static int naturalHeight(double diameter) { return (int)Math.rint(1.3*diameter); }
    public static CoolingTower natural(double diameter) { return natural(diameter,naturalHeight(diameter)); }
    public int cells() { return columns * rows; }
    public double cellArea() { return draft == Draft.INDUCED ? width * width : Math.PI * width * width / 4; }
    public double area() { return cells() * cellArea(); }
    public double basinVolume() { return BASIN_DEPTH * area(); }
    /** Clear air-inlet height above the basin rim; the natural support band is 70% of its former 0.2 × diameter. */
    public double airInletHeight() { return draft == Draft.NATURAL ? .14 * width : 2; }
    /** Bottom of the natural shell or induced casing, above grade. */
    public double shellBaseHeight() { return BASIN_HEIGHT + airInletHeight(); }
    public double distributorHeight() { return draft == Draft.INDUCED ? 5.5 + fillDepth : shellBaseHeight() + 3.5; }
    public CoolingTower withControl(Mode mode, double speed, double setPoint) {
        return new CoolingTower(draft, width, fillDepth, height, columns, rows, mode, speed, setPoint);
    }
}
