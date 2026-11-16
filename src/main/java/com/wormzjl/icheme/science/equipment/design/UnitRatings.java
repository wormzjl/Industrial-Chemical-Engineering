package com.wormzjl.icheme.science.equipment.design;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Kind;
import com.wormzjl.icheme.science.equipment.MachineDuty;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
import com.wormzjl.icheme.science.equipment.ProcessUnit.Fans;
import com.wormzjl.icheme.science.equipment.UnitDesign;

/**
 * The datasheet and simulator ratings of the standalone units (finding E2, batch 2026-11-13-codebase-review): an
 * exchanger's and a kettle's tube surface and a kettle's shell, an air cooler bay's bundle face, fans and coverage, a
 * centrifugal pump's flow, power and discharge, a balanced-opposed compressor's stroke, speed, power and displacement, and
 * a fired heater's duty. The drawing kit ({@link ProcessUnit}), the designs' datasheets, the heat sides and the
 * simulator's sizing all read them here. Moved from {@link ProcessUnit} unchanged; the kit keeps the helpers its drawing
 * shares with them ({@link ProcessUnit#header}, {@link ProcessUnit#RING_OD}, {@link ProcessUnit#cabinCells},
 * {@link ProcessUnit#cabinWall}).
 */
public final class UnitRatings {
    private UnitRatings() {
    }

    /** A pump's design head, m of liquid, and its efficiency, for the datasheet's motor size. */
    public static final double PUMP_HEAD = 100, PUMP_EFFICIENCY = .7;
    /**
     * A balanced-opposed compressor's mean piston speed, m/s: process frames run at 3 to 4.5 m/s
     * ({@code MACHINES_SCALE_RESEARCH.md}: Siemens HHE-VL, Baker Hughes HE/HF).
     */
    static final double PISTON_SPEED = 4;
    /** A fired heater's mean radiant flux, kW/m² of tube, and the radiant section's share of its duty (API 560 practice). */
    public static final double RADIANT_FLUX = 35, RADIANT_SHARE = .7;

    /** Tube surface of an exchanger or a kettle's bundle of shell {@code shell} and tubes {@code tubes}, m². */
    public static double tubeSurface(double shell, double tubes) {
        return 75 * shell * shell * tubes;
    }

    /** A kettle's shell over its bundle: 1.6 times, to 0.25 m. */
    public static double kettleShell(double bundle) {
        return Math.round(1.6 * bundle * 4) / 4.0;
    }

    /** An air cooler bay's bundle face between its headers (centred 0.5 m in from each end), m². */
    public static double bundleFace(double width, double length) {
        return finWidth(width) * (length - 1 - ProcessUnit.header(length));
    }

    /** The widest bay one bundle fills, m: a wider bay holds two bundles side by side (a bundle over about 3.5 m does not travel by road). */
    public static final double ONE_BUNDLE = 3;

    /** An air cooler bay's bundles: one, or two above {@link #ONE_BUNDLE}. */
    public static int bundles(double width) {
        return width > ONE_BUNDLE + 1e-9 ? 2 : 1;
    }

    /**
     * The finned width of an air cooler bay, m (batch 2026-11-15-equipment-visual-design, AIR_COOLER_BAY_PLAN.md 4.2): the
     * bay less its side frames' 0.22 m, and less 0.12 m more for the two frames at its centre line where it holds two
     * bundles ({@link #bundles}).
     */
    public static double finWidth(double width) {
        return width - .22 - (bundles(width) > 1 ? .12 : 0);
    }

    /**
     * An air cooler bay's fans: one to three along its tubes, as many as cover its bundle face best, each the largest whose
     * ring fits its share of the plenum ({@link #fanDiameter}).
     */
    public static Fans fans(double width, double length) {
        Fans best = null;
        double covered = -1;
        for (int k = 1; k <= 3; k++) {
            double d = fanDiameter(width, length, k), c = d > 0 ? k * Math.PI * d * d / 4 : 0;
            if (c > covered + 1e-9) {
                covered = c;
                best = new Fans(k, d);
            }
        }
        return best;
    }

    /**
     * The largest of {@code k} fans whose rings fit a bay, m, on a 0.05 m grid: each ring ({@link ProcessUnit#RING_OD} rotor
     * diameters) inside its share of the bundle along the tubes with 0.02 m to spare, and 0.1 m inside the plenum's
     * width (the side beams' 0.22 m off the bay width).
     */
    static double fanDiameter(double width, double length, int k) {
        double ring = Math.min(width - .32, (length - 1.1 - ProcessUnit.header(length)) / k - .02);
        return Math.floor(ring / ProcessUnit.RING_OD * 20 + 1e-9) / 20;
    }

    /** The share of an air cooler bay's bundle face its fans cover (API 661 asks 0.4 at least). */
    public static double coverage(double width, double length) {
        var fans = fans(width, length);
        return fans.count() * Math.PI * fans.diameter() * fans.diameter() / 4 / bundleFace(width, length);
    }

    /** A centrifugal pump's flow, m³/h: liquid at 3 m/s in its discharge {@code d}. */
    public static double pumpFlow(double d) {
        return 3 * Math.PI / 4 * d * d * 3600;
    }

    /** The shaft power of a pump of discharge {@code d} at {@link #PUMP_HEAD} of water, kW. */
    public static double pumpPower(double d) {
        return 1000 * 9.81 * pumpFlow(d) / 3600 * PUMP_HEAD / PUMP_EFFICIENCY / 1000;
    }

    /**
     * A centrifugal pump's discharge, m, for rated flow {@code flow}, m³/h: liquid at 3 m/s ({@link #pumpFlow}), up onto the
     * 0.05 m grid, 0.05 to 0.6 m.
     */
    public static double pumpDischarge(double flow) {
        return UnitDesign.upTo(Math.sqrt(4 * flow / 3600 / (3 * Math.PI)), ProcessUnit.SUCTION_STEP, ProcessUnit.SUCTION_STEP, .6);
    }

    /** A pump's shaft power at its design point, kW: water at its rated flow and head, at {@link #PUMP_EFFICIENCY}. */
    public static double pumpPower(MachineDuty duty) {
        return 9.81 * duty.flow() / 3600 * duty.head() / PUMP_EFFICIENCY;
    }

    /** A compressor's stroke for bore {@code b}, m. */
    public static double stroke(double b) {
        return .1 + .25 * b;
    }

    /** A compressor's speed, rev/min: its mean piston speed over twice its stroke (larger frames, longer strokes, run slower). */
    public static double compressorSpeed(double b) {
        return 60 * PISTON_SPEED / (2 * stroke(b));
    }

    /** A compressor's shaft power, MW: about 0.05 + 1.7 b² per throw (a fit to Siemens HHE-VL and the Thai Oil machines, {@code MACHINES_SCALE_RESEARCH.md}). */
    public static double compressorPower(double b, int throwCount) {
        return throwCount * (.05 + 1.7 * b * b);
    }

    /** A compressor's displacement, m³/h: double-acting cylinders of bore {@code b}, {@code throwCount} of them. */
    public static double displacement(double b, int throwCount) {
        return throwCount * 2 * Math.PI / 4 * b * b * stroke(b) * compressorSpeed(b) * 60;
    }

    /**
     * A fired heater's duty, MW, from its radiant tubes: 6" tubes at two diameters' pitch (π²/2 m² of tube per metre of
     * wall run) at {@link #RADIANT_FLUX}, the radiant section taking {@link #RADIANT_SHARE}. A vertical heater's tubes stand
     * round a circle 0.6 m inside its casing, 0.5 m shorter than its radiant height; a cabin's lie along both side walls of
     * each of its {@link ProcessUnit#cabinCells} (1 m short each way) and a third as much again on its roof.
     */
    public static double heaterDuty(Kind kind, double diameter, double length) {
        int cells = kind == Kind.CABIN_HEATER ? ProcessUnit.cabinCells(length) : 1;
        double tubes = kind == Kind.VERTICAL_HEATER ? Math.PI * Math.PI / 2 * (diameter - .6) * (length - .5)
                : 2 * cells * Math.PI / 2 * (ProcessUnit.cabinWall(diameter) - 1) * (length / cells - 1) * 1.3;
        return RADIANT_FLUX * tubes / RADIANT_SHARE / 1000;
    }
}
