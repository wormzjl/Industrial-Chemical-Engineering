package com.wormzjl.icheme.science.fluid.network;

import java.util.Objects;

/**
 * Geometry and operating facts of an open water cooling tower; SI throughout. {@code fanLimit} is no fact: the most of
 * full speed its fans may turn at this slice (batch 2026-11-03-rotating-machinery W5: 0 when its electric store runs
 * empty, 1 otherwise), a dispatch input ({@link ShaftPlan}) that the facts and the checkpoint never carry.
 */
public record CoolingTower(Draft draft, double width, double fillDepth, double height, int columns, int rows,
                           Mode mode, double speed, double setPoint, double fanLimit) {
    public CoolingTower(Draft draft, double width, double fillDepth, double height, int columns, int rows, Mode mode, double speed, double setPoint) {
        this(draft, width, fillDepth, height, columns, rows, mode, speed, setPoint, 1);
    }
    public enum Draft { INDUCED, NATURAL }
    public enum Mode { FAN_SPEED, TARGET_TEMPERATURE }
    public static final double AIR_TEMPERATURE = 298.15, RELATIVE_HUMIDITY = .5, PRESSURE = 101325;
    public static final double BASIN_HEIGHT = 1.5, BASIN_DEPTH = 2 * BASIN_HEIGHT / 3;
    /** Full-speed fan pressure, Pa; declared air and water loading, kg/(s m²); the fan laws' efficiency and air density, kg/m³. */
    public static final double FAN_PRESSURE = 20, DESIGN_LOADING = 2, FAN_EFFICIENCY = .6, AIR_DENSITY = 1.18;
    /** A fan cell's fan diameter and stack height over its width, the bell mouth over the fan, and the spray zone above the fill, m. The stack follows the cell, not the fan: its height is the draft column of {@link TowerTransfer}. */
    public static final double FAN_RATIO = .6, STACK_RATIO = .28, BELL = 1.18, SPRAY_ZONE = 1;
    public CoolingTower {
        Objects.requireNonNull(draft); Objects.requireNonNull(mode);
        for (double v : new double[]{width, fillDepth, height, speed, setPoint})
            if (!Double.isFinite(v)) throw new IllegalArgumentException("Nonfinite cooling tower setting");
        if (columns < 1 || columns > 8 || rows < 1 || rows > 2 || speed < 0 || speed > 1 || setPoint <= 0 || !(fanLimit >= 0 && fanLimit <= 1))
            throw new IllegalArgumentException("Cooling tower setting outside its range");
        if (draft == Draft.INDUCED) {
            // 8 m is the narrowest cell whose fan leaves a railed walkway round its bell mouth.
            if (width < 8 || width > 14 || width % 2 != 0 || fillDepth < 1 || fillDepth > 3 || fillDepth != Math.rint(fillDepth) || height != fanHeight(width, fillDepth))
                throw new IllegalArgumentException("A fan cell is 8–14 m wide with 1–3 m fill");
        } else if (width < 40 || width > 80 || width % 10 != 0 || height != naturalHeight(width) || fillDepth != 2 || columns != 1 || rows != 1 || mode != Mode.FAN_SPEED || speed != 0)
            throw new IllegalArgumentException("A natural tower is 40–80 m across, with height 1.3 times its diameter");
    }
    public static CoolingTower induced(double width, double fill, int columns, int rows) {
        return new CoolingTower(Draft.INDUCED, width, fill, fanHeight(width, fill), columns, rows, Mode.FAN_SPEED, 1, 298.15);
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
    /** All fans at {@code speed}, W: flow follows speed and pressure its square at the declared loading. */
    public double fanPower(double speed) {
        return draft == Draft.INDUCED ? FAN_PRESSURE * speed * speed * DESIGN_LOADING * area() * speed / AIR_DENSITY / FAN_EFFICIENCY : 0;
    }
    /** The water flow of the declared calibration point, kg/s. */
    public double referenceWater() { return DESIGN_LOADING * area(); }
    /** The duty of that point at its 10 K range, W. */
    public double referenceDuty() { return referenceWater() * 4180 * 10; }
    private static double inlet(double width) { return Math.max(2, Math.ceil(3 * width / 10)); }
    private static double spray(double width, double fill) { return BASIN_HEIGHT + inlet(width) + fill + SPRAY_ZONE; }
    private static double deck(double width, double fill) { return spray(width, fill) + Math.max(1, Math.rint(width / 5)); }
    /** A fan cell from the ground to its stack's top, m: rim, louvers, fill, spray zone, plenum and stack. */
    public static double fanHeight(double width, double fill) { return deck(width, fill) + STACK_RATIO * width; }
    /** Clear air-inlet height above the basin rim. */
    public double airInletHeight() { return draft == Draft.NATURAL ? .14 * width : inlet(width); }
    /** Bottom of the natural shell or of an induced cell's fill, above grade. */
    public double shellBaseHeight() { return BASIN_HEIGHT + airInletHeight(); }
    public double distributorHeight() { return draft == Draft.INDUCED ? spray(width, fillDepth) : shellBaseHeight() + 3.5; }
    /** A fan cell's deck, above grade. */
    public double deckHeight() { return deck(width, fillDepth); }
    public double fanRadius() { return FAN_RATIO * width / 2; }
    public CoolingTower withControl(Mode mode, double speed, double setPoint) {
        return new CoolingTower(draft, width, fillDepth, height, columns, rows, mode, speed, setPoint, fanLimit);
    }
    /** This tower with its fans held at most at {@code value} of full speed (W5's dispatch input). */
    public CoolingTower withFanLimit(double value) {
        return value == fanLimit ? this : new CoolingTower(draft, width, fillDepth, height, columns, rows, mode, speed, setPoint, value);
    }
    /** The record's text as before the fan limit, which it names only below 1 (an island's certificate signature reads it). */
    @Override public String toString() {
        return "CoolingTower[draft=" + draft + ", width=" + width + ", fillDepth=" + fillDepth + ", height=" + height + ", columns=" + columns + ", rows=" + rows
                + ", mode=" + mode + ", speed=" + speed + ", setPoint=" + setPoint + (fanLimit < 1 ? ", fanLimit=" + fanLimit : "") + "]";
    }
    /** These facts without a dispatch's fan limit. */
    public CoolingTower facts() {
        return withFanLimit(1);
    }
    /** The fan speed it runs at for {@code wanted} of full speed: at most its {@link #fanLimit}. */
    public double fanSpeed(double wanted) {
        return Math.min(wanted, fanLimit);
    }
}
