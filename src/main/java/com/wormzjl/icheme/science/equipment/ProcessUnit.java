package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Kind;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Nozzle;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Orientation;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import com.wormzjl.icheme.science.math.DeterministicMath;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static com.wormzjl.icheme.science.equipment.Mesh.*;

/**
 * A standalone process unit (owner request and decisions 2026-10-16, batch 2026-10-15-custom-equipment-rendering,
 * {@code STANDALONE_UNITS_PLAN.md}): a shell-and-tube heat exchanger, a kettle reboiler, an air cooler bay or a steam
 * ejector, drawn only. The player places it with the constructor and runs the pipes; its nozzles take the pipe's size.
 *
 * <p>Sizes, from the physics of the plan. An exchanger (TEMA AES) of shell D and tubes L holds about 1,260 D² tubes of
 * 3/4" on a 1" pitch, about 75 D² L m² of surface; its tubes are at least 3 D long. A kettle's bundle of D_b lies in a
 * shell of 1.6 D_b with vapour space over the pool. An air cooler is a bank of single-pass bays in parallel and in series
 * ({@link Bank}), each 1.5 to 3 m wide and 2 to 6 m long where its one to three fans cover at least 40 % of its bundle
 * face (API 661; owner requests 2026-10-16: smaller bays, banks), its fans, rings and motors Blender parts ({@link Part}).
 * An ejector's proportions follow its suction s (8.3 s long).
 *
 * <p>Built in the unit's frame, which stands as a vertical vessel's ({@link EquipmentShape}): y up from the ground, the
 * front (frame -z) towards the constructor, a lying unit's axis along z with its channel (the air cooler's inlet header)
 * at the front. Every nozzle's centre line runs through cell centres and its flange face lies on a block face; the block
 * beyond each face is left free for a pipe. {@code diameter}, {@code tangentLength} and {@code support} of the shape are
 * the unit's own sizes: an exchanger's shell, tube length and the height of its shell's bottom; a kettle's bundle, tube
 * length and the height of its kettle's bottom; an air cooler's bay width, bay length and the height of its bundle; an
 * ejector's suction, 1 (unused) and the height of its discharge flange.
 */
public final class ProcessUnit {
    private static final int CACHE = 64;
    private static final Map<EquipmentShape, ProcessUnit> BUILT = new ConcurrentHashMap<>();

    /**
     * Steps of an exchanger's shell and a kettle's bundle, and of an ejector's suction, m; the largest suction of an
     * ejector on a single post (owner request 2026-10-16: "allow smaller ejectors").
     */
    public static final double SHELL_STEP = .25, SUCTION_STEP = .05, SMALL_EJECTOR = .2;
    /**
     * An air cooler bay's lengths, m (header to header, whole metres; owner request 2026-10-16: "allow smaller ejectors and
     * ACs"); its width runs from 1.5 to 3 m in 0.5 m steps. A bay is built only where its fans cover {@link #FAN_COVERAGE}
     * of its bundle face (API 661), which a bay under 1.5 m wide never does.
     */
    public static final int MIN_BAY_LENGTH = 2, MAX_BAY_LENGTH = 6;
    public static final double FAN_COVERAGE = .4;
    /**
     * Air cooler parts, m: the largest header depth along the tubes, a header's height, the bundle's depth, and the
     * deepest a fan ring and guard hang under the plenum (the Blender ring is 0.35 of its fan deep).
     */
    static final double HEADER = .6, HEADER_HEIGHT = .6, BUNDLE = .5, RING_DEPTH = .9;
    /**
     * A fan ring's widest parts, its top flange and its bellmouth rim, in rotor diameters (the Blender part
     * {@code ac_fan_ring}); fans are sized so that rings side by side keep apart (owner report 2026-10-16).
     */
    static final double RING_OD = 1.12;

    /**
     * An air cooler bank (owner request 2026-10-16: "AC should be able to be built in series & parallel", decided: one bank
     * unit with its manifolds and crossovers): {@code parallel} trains of bays side by side across the frame's x, each
     * {@code series} bays back to back along z. Single-pass bays: the process enters a train at its first bay's front
     * header and crosses from each back header to the next front header; the bank has one inlet and one outlet.
     */
    public record Bank(int parallel, int series) {
        public static final Bank ONE = new Bank(1, 1);
        public static final int MAX_PARALLEL = 6, MAX_SERIES = 4;

        public Bank {
            if (parallel < 1 || parallel > MAX_PARALLEL || series < 1 || series > MAX_SERIES)
                throw new IllegalArgumentException("A bank is 1 to " + MAX_PARALLEL + " bays in parallel by 1 to " + MAX_SERIES + " in series, not " + parallel + " × " + series);
        }

        public int bays() {
            return parallel * series;
        }

        public int[] encode() {
            return new int[]{parallel, series};
        }

        /** @throws IllegalArgumentException unless {@code values} holds the bays in parallel and in series, in range */
        public static Bank decode(int[] values) {
            if (values.length != 2) throw new IllegalArgumentException("A bank holds its bays in parallel and in series");
            return new Bank(values[0], values[1]);
        }
    }

    /** A bay's fans: how many, along its tubes, and their diameter, m. */
    public record Fans(int count, double diameter) {
    }

    /**
     * Heights under an air cooler's bundle for its fans, m above grade: the plenum's floor 0.2 + 0.4 D under the bundle,
     * the ring and guard hanging {@code up} (at most {@link #RING_DEPTH}, drawn at 0.35 of it) down to the ring's
     * {@code inlet}, the {@code rotor} turning a sixth of the way down, and the motor (scaled {@code motor}, smaller for
     * a small fan) under the guard on its shaft.
     */
    public record FanStack(double floor, double up, double rotor, double inlet, double motor, double motorTop, double motorBottom) {
        public static FanStack of(double bundle, double fan) {
            double up = Math.min(fan, RING_DEPTH), floor = bundle - (.2 + .4 * fan), motor = Math.max(.45, Math.min(1, fan / 1.9)), motorTop = floor - .35 * up - .03;
            return new FanStack(floor, up, floor - .15 * up, floor - .35 * up, motor, motorTop, motorTop - .58 * motor);
        }
    }

    /** Steelwork, m: a post's section, a beam's depth and flange. */
    static final double POST = .2, BEAM = .2, FLANGE_BEAM = .15;

    // ---- Sizes: each kind's design ({@link UnitDesign}) ---------------------------------------------------------------

    public static double minDiameter(Kind kind) {
        return UnitDesign.of(kind).minSize();
    }

    public static double maxDiameter(Kind kind) {
        return UnitDesign.of(kind).maxSize();
    }

    public static double diameterStep(Kind kind) {
        return UnitDesign.of(kind).sizeStep();
    }

    /** The least second size (tubes, a bay's length, throws, a radiant height or length; 1 where the kind has none). */
    public static int minLength(Kind kind, double diameter) {
        return UnitDesign.of(kind).minLength(diameter);
    }

    public static int maxLength(Kind kind, double diameter) {
        return UnitDesign.of(kind).maxLength(diameter);
    }

    public static int defaultLength(Kind kind, double diameter) {
        return UnitDesign.of(kind).defaultLength(diameter);
    }

    /** The lowest support, m. */
    public static int minSupport(Kind kind, double diameter, double length) {
        return UnitDesign.of(kind).minSupport(diameter, (int) length);
    }

    /** The highest support, m: 0 where it is fixed (a pump's plinth at grade). */
    public static int maxSupport(Kind kind) {
        return UnitDesign.of(kind).maxSupport();
    }

    public static int defaultSupport(Kind kind, double diameter, double length) {
        return UnitDesign.of(kind).defaultSupport(diameter, (int) length);
    }

    public static Orientation orientation(Kind kind) {
        return UnitDesign.of(kind).orientation();
    }

    /** A diameter on its kind's grid, cleared of rounding (0.30000000000000004 becomes 0.3). */
    public static double snap(Kind kind, double diameter) {
        double step = diameterStep(kind), min = minDiameter(kind);
        double k = Math.max(0, Math.min(Math.round((maxDiameter(kind) - min) / step), Math.round((diameter - min) / step)));
        return Math.round((min + k * step) * 1000) / 1000.0;
    }

    static void validate(Kind kind, double diameter, double length, Orientation orientation, int support, Bank bank) {
        var design = UnitDesign.of(kind);
        String name = kind.name().toLowerCase(Locale.ROOT);
        if (!design.banks() && !bank.equals(Bank.ONE)) throw new IllegalArgumentException("Only air coolers are built in banks");
        if (!(diameter >= minDiameter(kind) - 1e-9 && diameter <= maxDiameter(kind) + 1e-9) || Math.abs(snap(kind, diameter) - diameter) > 1e-9)
            throw new IllegalArgumentException("A " + name + "'s size must be " + minDiameter(kind) + " to " + maxDiameter(kind) + " m in " + diameterStep(kind)
                    + " m steps, not " + diameter);
        if (length != Math.rint(length) || length < minLength(kind, diameter) || length > maxLength(kind, diameter))
            throw new IllegalArgumentException("A " + name + "'s " + (design.lengthInMetres() ? "length" : "count") + " must be " + minLength(kind, diameter) + " to "
                    + maxLength(kind, diameter) + (design.lengthInMetres() ? " whole metres" : "") + ", not " + length);
        if (orientation != orientation(kind)) throw new IllegalArgumentException("A " + name + " " + (orientation(kind).horizontal() ? "lies" : "stands"));
        int lowest = minSupport(kind, diameter, length);
        if (support < lowest || support > maxSupport(kind))
            throw new IllegalArgumentException("A " + name + "'s support must be " + lowest + " to " + maxSupport(kind) + " m, not " + support);
        design.check(diameter, (int) length, support, bank);
    }

    /** Tube surface of an exchanger or a kettle's bundle of shell {@code shell} and tubes {@code tubes}, m². */
    public static double tubeSurface(double shell, double tubes) {
        return 75 * shell * shell * tubes;
    }

    /** A kettle's shell over its bundle: 1.6 times, to 0.25 m. */
    public static double kettleShell(double bundle) {
        return Math.round(1.6 * bundle * 4) / 4.0;
    }

    /** An air cooler bay's header depth along its tubes, m: 0.15 of its length, at most {@link #HEADER}. */
    static double header(double length) {
        return Math.min(HEADER, .15 * length);
    }

    /** An air cooler bay's bundle face between its headers (centred 0.5 m in from each end), m². */
    public static double bundleFace(double width, double length) {
        return (width - .22) * (length - 1 - header(length));
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
     * The largest of {@code k} fans whose rings fit a bay, m, on a 0.05 m grid: each ring ({@link #RING_OD} rotor
     * diameters) inside its share of the bundle along the tubes with 0.02 m to spare, and 0.1 m inside the plenum's
     * width (the side beams' 0.22 m off the bay width).
     */
    static double fanDiameter(double width, double length, int k) {
        double ring = Math.min(width - .32, (length - 1.1 - header(length)) / k - .02);
        return Math.floor(ring / RING_OD * 20 + 1e-9) / 20;
    }

    /** Every air cooler bay a player may build, {width, length}, the narrowest and shortest first. */
    public static List<double[]> bays() {
        var out = new ArrayList<double[]>();
        for (double w = minDiameter(Kind.AIR_COOLER); w <= maxDiameter(Kind.AIR_COOLER) + 1e-9; w += diameterStep(Kind.AIR_COOLER))
            for (int l = minLength(Kind.AIR_COOLER, w); l <= MAX_BAY_LENGTH; l++) if (coverage(w, l) >= FAN_COVERAGE) out.add(new double[]{snap(Kind.AIR_COOLER, w), l});
        return out;
    }

    /** The share of an air cooler bay's bundle face its fans cover (API 661 asks 0.4 at least). */
    public static double coverage(double width, double length) {
        var fans = fans(width, length);
        return fans.count() * Math.PI * fans.diameter() * fans.diameter() / 4 / bundleFace(width, length);
    }

    /** An ejector's length from its steam chest to its diffuser's exit, m: 8.3 suction diameters. */
    public static double ejectorLength(double suction) {
        return 8.3 * suction;
    }

    // ---- Machines and heaters (owner request 2026-10-17, MACHINES_HEATERS_PLAN.md) ----------------------------------

    /** A pump's design head, m of liquid, and its efficiency, for the datasheet's motor size. */
    static final double PUMP_HEAD = 100, PUMP_EFFICIENCY = .7;
    /**
     * The largest overhung (API 610 OH2) pump's discharge, m: larger pumps are axially split (BB1), up to the 0.75 m whose
     * 0.95 m suction is the largest nozzle the mod's pipes (1 m outside) connect ({@code MACHINES_SCALE_RESEARCH.md}: OH2
     * stops at 0.4 m, BB1 reaches 1.2 m).
     */
    public static final double OH2_MAX = .4;
    /**
     * A balanced-opposed compressor's most throws, and its mean piston speed, m/s: process frames reach 1.25 m bore and 10
     * throws and run at 3 to 4.5 m/s ({@code MACHINES_SCALE_RESEARCH.md}: Siemens HHE-VL, Baker Hughes HE/HF).
     */
    public static final int MAX_THROWS = 10;
    static final double PISTON_SPEED = 4;
    /** A vertical heater's tallest radiant section, m. */
    static final int MAX_RADIANT_HEIGHT = 20;
    /** A fired heater's mean radiant flux, kW/m² of tube, and the radiant section's share of its duty (API 560 practice). */
    static final double RADIANT_FLUX = 35, RADIANT_SHARE = .7;

    /** A centrifugal pump's suction for discharge {@code d}, m: one size up, about 1.25 d, on a 0.05 m grid. */
    public static double pumpSuction(double d) {
        return Math.round((1.25 * d + .025) * 20) / 20.0;
    }

    /** A centrifugal pump's flow, m³/h: liquid at 3 m/s in its discharge {@code d}. */
    public static double pumpFlow(double d) {
        return 3 * Math.PI / 4 * d * d * 3600;
    }

    /** The shaft power of a pump of discharge {@code d} at {@link #PUMP_HEAD} of water, kW. */
    public static double pumpPower(double d) {
        return 1000 * 9.81 * pumpFlow(d) / 3600 * PUMP_HEAD / PUMP_EFFICIENCY / 1000;
    }

    /** A TEFC motor's body across, m, for {@code kw}: about 0.19 P^0.25 (0.32 m at 8 kW, 0.91 m at 530 kW). */
    static double motorDiameter(double kw) {
        return .19 * Math.sqrt(Math.sqrt(kw));
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
     * each of its {@link #cabinCells} (1 m short each way) and a third as much again on its roof.
     */
    public static double heaterDuty(Kind kind, double diameter, double length) {
        int cells = kind == Kind.CABIN_HEATER ? cabinCells(length) : 1;
        double tubes = kind == Kind.VERTICAL_HEATER ? Math.PI * Math.PI / 2 * (diameter - .6) * (length - .5)
                : 2 * cells * Math.PI / 2 * (cabinWall(diameter) - 1) * (length / cells - 1) * 1.3;
        return RADIANT_FLUX * tubes / RADIANT_SHARE / 1000;
    }

    /** A cabin heater's wall height for width {@code w}, m. */
    static double cabinWall(double w) {
        return w + 1;
    }

    /**
     * A balanced-opposed compressor's sizes, m, for bore {@code b} and {@code throwCount} ({@code MACHINES_HEATERS_SURVEY.md}):
     * its stroke; a cylinder's outside across (water jacket and valve pockets, 1.45 bores and 0.15 m: 2.5 bores small, 1.6
     * large) and length (the stroke and 0.5 to 0.9 m); a crosshead guide's half section and its length (3.5 strokes, 0.7 m
     * at least), a distance piece's half section; the frame's half width, the crankshaft's height over the foundation and
     * the frame's top (the crankshaft about half way up); the crank pitch; the cylinders' centres out from the crankshaft
     * (whole metres: their bottles' nozzles run through cell centres; the distance piece takes the rest, 0.5 + 0.4 b at
     * least); the suction and discharge bottles across; the synchronous motor's frame across, 0.3 + 1.1 (kW / rpm)^⅓
     * (wider than long: 0.45 of that and 0.4 m), from {@link #compressorPower} ({@code MACHINES_SCALE_RESEARCH.md}).
     */
    record Compressor(double bore, int throwCount, double stroke, double cylinder, double cylinderLength, double guide, double guideLength, double piece, double half,
                      double crank, double frameTop, double pitch, double out, double suction, double discharge, double motor, double motorLength) {
        static Compressor of(double b, int throwCount) {
            double s = ProcessUnit.stroke(b), c = 1.45 * b + .15, lc = 2.2 * s + .3 + .3 * b, half = .45 + .3 * b, crank = .5 + .35 * b, guide = .25 + .2 * b;
            double lg = Math.max(.7, 3.5 * s), out = Math.ceil(half + lg + .5 + .4 * b + lc / 2 - 1e-9), ds = 1.1 * b + .3;
            double dm = .3 + 1.1 * DeterministicMath.cbrt(1000 * compressorPower(b, throwCount) / compressorSpeed(b));
            return new Compressor(b, throwCount, s, c, lc, guide, lg, .8 * guide, half, crank, crank + .45 + .5 * b, Math.max(.55, (c + .3) / 2), out, ds, .85 * ds, dm,
                    .45 * dm + .4);
        }

        /** The distance piece's length from the guide to the cylinder. */
        double pieceLength() {
            return out - half - guideLength - cylinderLength / 2;
        }

        /**
         * The suction header across: every cylinder's flow at about 12 m/s, 0.5 b √n; the discharge header 0.7 of it (a
         * pressure ratio of about 2); at most a pipe's largest ({@code MACHINES_SCALE_RESEARCH.md}).
         */
        double suctionHeader() {
            return Math.min(PipeSizes.MAX_OUTSIDE, .5 * bore * Math.sqrt(throwCount) + .05);
        }

        double dischargeHeader() {
            return Math.min(PipeSizes.MAX_OUTSIDE, .7 * suctionHeader() + .02);
        }

        /** The depth of the steel table's rails: about W24 (0.6 m) at 2 MW to W36 (0.9 m) at 20 MW, 0.4 m on the smallest. */
        double rail() {
            return Math.min(.9, .4 + .1 * Math.sqrt(compressorPower(bore, throwCount)));
        }

        /** The gap between a cylinder and its bottles (survey: 0.2 to 0.5 m). */
        double gap() {
            return .2 + .15 * bore;
        }

        /** The discharge bottles' axis for a foundation {@code h} high: the cell centre under the cylinders at least {@link #gap} below them. */
        double dischargeAxis(int h) {
            return Math.floor(h + crank - cylinder / 2 - gap() - discharge / 2 - .5) + .5;
        }

        /** Whether a foundation {@code h} high keeps the discharge bottles 0.3 m and the motor 0.1 m over grade. */
        boolean clears(int h) {
            return dischargeAxis(h) - discharge / 2 >= .3 && h + crank - motor / 2 >= .1;
        }
    }

    /**
     * A moving part (owner requests 2026-10-16, "add a working animation for AC", and 2026-10-18, "give compressor and
     * pump working animations"; {@code HEATER_JOINTS_MACHINE_MOTION_PLAN.md}): the facets drawn under its index
     * ({@link Mesh#part}) turn about {@code axis} through {@code pivot}, or slide along it, at {@code speed} revolutions a
     * second from {@code phase} (turns). A sliding part rides a slider-crank of crank radius {@code crank} and connecting
     * rod {@code rod}, drawn at mid stroke (the crank a quarter turn round). Real speeds alias at frame rates (a fan's 5
     * rev/s, a pump's 50, a compressor crank's 5 to 15), so these are slower; each makes whole turns in
     * {@link #MOTION_PERIOD} ticks. The client moves them only while its switch is on; the server never does.
     */
    public record Motion(double[] pivot, double[] axis, double speed, double phase, double crank, double rod) {
        static Motion turning(double[] pivot, double[] axis, double speed, double phase) {
            return new Motion(pivot, axis, speed, phase, 0, 0);
        }

        public boolean slides() {
            return crank > 0;
        }

        /** Where the part is {@code seconds} on: its angle about the axis, or its crank's, in turns. */
        public double turns(double seconds) {
            return speed * seconds + phase;
        }

        /** A sliding part's displacement along its axis from where it is drawn, m, its crank {@code turns} round. */
        public double offset(double turns) {
            double t = 2 * Math.PI * turns, s = DeterministicMath.sin(t);
            return crank * DeterministicMath.cos(t) + Math.sqrt(rod * rod - crank * crank * s * s) - Math.sqrt(rod * rod - crank * crank);
        }

        /** The same motion in the box: its pivot and axis turned by {@code transform}. */
        public Motion in(Transform transform) {
            return new Motion(transform.point(pivot), transform.direction(axis), speed, phase, crank, rod);
        }
    }

    /**
     * Ticks holding whole turns of every shown speed: two minutes (2026-10-18), so slow machines move at their real pace
     * (a coker's drill stem one stroke, an ash cooler's drum at 3 rpm, a ram feeder's stroke every 30 s) and every
     * speed that is a whole number of turns in it loops without a jump.
     */
    public static final int MOTION_PERIOD = 2400;
    /** Shown speeds, rev/s: an air cooler's fans, a pump's shaft and its motor's fan, a compressor's crank. */
    static final double FAN_SPEED = 1.5, PUMP_SPEED = 2, CRANK_SPEED = 1;

    /** The unit's moving parts in its frame, by index ({@link Mesh#part}): none for an exchanger, a kettle, an ejector or a heater. */
    List<Motion> motions() {
        return List.copyOf(motions);
    }

    /** Whether units of {@code kind} have moving parts: their datasheet carries the switch. */
    public static boolean moves(Kind kind) {
        return UnitDesign.of(kind).moves();
    }

    static ProcessUnit of(EquipmentShape shape) {
        var built = BUILT.get(shape);
        if (built != null) return built;
        if (BUILT.size() >= CACHE) BUILT.clear();
        return BUILT.computeIfAbsent(shape, s -> new ProcessUnit(s, UnitDesign.of(s.kind())::build, false));
    }

    // ---- Collision parts --------------------------------------------------------------------------------------------

    /**
     * A solid part in the frame: an axis-aligned box ({@code radius} 0), or a cylinder along frame axis {@code axis}
     * between {@code lo} and {@code hi} on it, its radius going from {@code radius} at {@code lo} to {@code radiusHi} at
     * {@code hi} round the line through {@code centre} (a cone where they differ), with a 2:1 head of depth {@code headLo}
     * beyond its low end and one of {@code headHi} beyond its high end (0: flat); a cylinder may be hollow inside
     * {@code inner} and cut to the angles [from, to] (counter-clockwise from +x towards +z about a vertical axis; from = to:
     * whole), a deck's ring; or a box turned any way ({@code axis} -2): its {@code centre} and three orthogonal half-extent
     * vectors, nine numbers in {@code extents} (a wound stair's tread or fence, {@link Access.WoundStair}).
     */
    record Solid(double[] min, double[] max, int axis, double[] centre, double radius, double radiusHi, double lo, double hi, double headLo, double headHi,
                         double inner, double from, double to, double[] extents) {
        static Solid box(double[] min, double[] max) {
            return new Solid(min, max, -1, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, null);
        }

        /** A box turned any way: its centre and three orthogonal half-extent vectors. */
        static Solid block(double[] centre, double[] ex, double[] ey, double[] ez) {
            double[] min = centre.clone(), max = centre.clone();
            for (int a = 0; a < 3; a++) {
                double h = Math.abs(ex[a]) + Math.abs(ey[a]) + Math.abs(ez[a]);
                min[a] -= h;
                max[a] += h;
            }
            return new Solid(min, max, -2, centre.clone(), 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    new double[]{ex[0], ex[1], ex[2], ey[0], ey[1], ey[2], ez[0], ez[1], ez[2]});
        }

        /** A ring about the vertical line through (x, z), from {@code inner} to {@code outer} out and {@code lo} to {@code hi} up, over the angles [from, to]. */
        static Solid ring(double x, double z, double lo, double hi, double inner, double outer, double from, double to) {
            var whole = cone(new double[]{x, 0, z}, 1, lo, hi, outer, outer, 0);
            // A full turn is whole: its two half-planes meet on one line, where both tests read rounding noise.
            if (to - from >= 2 * Math.PI - 1e-9) from = to = 0;
            return new Solid(whole.min, whole.max, 1, whole.centre, outer, outer, lo, hi, 0, 0, inner, from, to, null);
        }

        static Solid rod(double[] centre, int axis, double lo, double hi, double radius, double headHi) {
            return cone(centre, axis, lo, hi, radius, radius, 0, headHi);
        }

        /** A rod with a 2:1 head at either end: {@code headLo} deep under {@code lo}, {@code headHi} over {@code hi} (a drum, a bottle, a reactor's shell). */
        static Solid rod(double[] centre, int axis, double lo, double hi, double radius, double headLo, double headHi) {
            return cone(centre, axis, lo, hi, radius, radius, headLo, headHi);
        }

        static Solid cone(double[] centre, int axis, double lo, double hi, double radius, double radiusHi, double headHi) {
            return cone(centre, axis, lo, hi, radius, radiusHi, 0, headHi);
        }

        /** A cone with a 2:1 head of its end's radius at either end: {@code headLo} deep under {@code lo}, {@code headHi} over {@code hi}. */
        static Solid cone(double[] centre, int axis, double lo, double hi, double radius, double radiusHi, double headLo, double headHi) {
            double[] min = centre.clone(), max = centre.clone();
            double widest = Math.max(radius, radiusHi);
            for (int a = 0; a < 3; a++) {
                min[a] -= widest;
                max[a] += widest;
            }
            min[axis] = lo - headLo;
            max[axis] = hi + headHi;
            return new Solid(min, max, axis, centre, radius, radiusHi, lo, hi, headLo, headHi, 0, 0, 0, null);
        }

        /** This solid cut to the box from {@code low} to {@code high}. */
        Solid clip(double[] low, double[] high) {
            double[] a = min.clone(), b = max.clone();
            for (int k = 0; k < 3; k++) {
                a[k] = Math.max(a[k], low[k]);
                b[k] = Math.min(b[k], high[k]);
            }
            return new Solid(a, b, axis, centre, radius, radiusHi, lo, hi, headLo, headHi, inner, from, to, extents);
        }

        boolean contains(double[] p) {
            for (int a = 0; a < 3; a++) if (p[a] < min[a] || p[a] > max[a]) return false;
            if (axis == -2) {
                double dx = p[0] - centre[0], dy = p[1] - centre[1], dz = p[2] - centre[2];
                for (int e = 0; e < 9; e += 3) {
                    double ex = extents[e], ey = extents[e + 1], ez = extents[e + 2];
                    if (Math.abs(dx * ex + dy * ey + dz * ez) > ex * ex + ey * ey + ez * ez) return false;
                }
                return true;
            }
            if (axis < 0) return true;
            double rho2 = 0;
            for (int a = 0; a < 3; a++) if (a != axis) rho2 += (p[a] - centre[a]) * (p[a] - centre[a]);
            if (rho2 < inner * inner) return false;
            if (to > from) {
                double dx = p[0] - centre[0], dz = p[2] - centre[2];
                double c0 = DeterministicMath.cos(from), s0 = DeterministicMath.sin(from), c1 = DeterministicMath.cos(to), s1 = DeterministicMath.sin(to);
                boolean afterFrom = c0 * dz - s0 * dx >= 0, beforeTo = dx * s1 - dz * c1 >= 0;
                if (!(to - from <= Math.PI ? afterFrom && beforeTo : afterFrom || beforeTo)) return false;
            }
            double s = p[axis];
            if (s < lo) {
                double out = (lo - s) / headLo;
                return headLo > 0 && out * out + rho2 / (radius * radius) <= 1;
            }
            if (s <= hi) {
                double r = hi > lo ? radius + (radiusHi - radius) * (s - lo) / (hi - lo) : radius;
                return rho2 <= r * r;
            }
            double out = (s - hi) / headHi;
            return headHi > 0 && out * out + rho2 / (radiusHi * radiusHi) <= 1;
        }
    }

    // ---- The unit ---------------------------------------------------------------------------------------------------

    final EquipmentShape shape;
    /** The mesh being drawn: the cached unit drops it once built; {@link #mesh()} draws the facets again. */
    Mesh mesh = new Mesh();
    final List<Solid> solids = new ArrayList<>(), volumes = new ArrayList<>();
    /** The moving parts, by the index their facets carry ({@link Mesh#part}). */
    final List<Motion> motions = new ArrayList<>();
    /** A unit's ladders. */
    final List<Access.Climb> ladders = new ArrayList<>();

    /**
     * A unit's straight stair flight (2026-10-18, NEW_UNITS_PLAN.md: the big units' stair towers): its foot's centre (x, z),
     * rising along frame axis {@code axis} (0 or 2) in direction {@code sign}, {@code width} between its outer stringers,
     * from walking level {@code base} to {@code top}, in {@code steps} treads {@link #STAIR_GOING} deep.
     */
    record Stair(double x, double z, int axis, int sign, double width, double base, double top, int steps) {
        /** A point {@code s} along the flight from its foot, {@code off} across it (positive to its left going up), at height {@code y}. */
        double[] at(double s, double off, double y) {
            double along = sign * s, across = sign * off;
            return axis == 0 ? new double[]{x + along, y, z - across} : new double[]{x + across, y, z + along};
        }

        double rise() {
            return (top - base) / steps;
        }

        double length() {
            return steps * STAIR_GOING;
        }
    }

    /** A stair tread's going and its highest rise (an industrial stair of about 39°, OSHA 1910.25), m. */
    static final double STAIR_GOING = .25, STAIR_RISE = .2;
    final List<Stair> stairs = new ArrayList<>();
    /** Stairs wound round a body of revolution ({@link Access.WoundStair}): a floating-roof tank's and a sphere's (owner approval 2026-10-21). */
    final List<Access.WoundStair> woundStairs = new ArrayList<>();
    /** A storage vessel's fluid node: a frame point in the shell cell at its lowest inside, or null for a unit. */
    double[] fluidPoint;
    /**
     * Frame heights {low, high, phase} between which whole cell rows are body rows (a floating-roof tank's plain shell
     * courses, {@link ShapeRasterizer}): their cells repeat every {@link ShapeRasterizer#COURSE} pair from {@code phase},
     * but in the box columns the facets from {@link #irregularFrom} on reach (the stair, the sight glass). Null: none.
     */
    double[] bodyRows;
    int irregularFrom = Integer.MAX_VALUE;
    /**
     * A floating-roof tank's parts the client draws, not the chunk mesh (plan section 3.4): the roof, drawn landed on its
     * legs, and the rolling ladder, drawn level from its pivot; null for any other shape.
     */
    Floating floating;
    final Mesh roof = new Mesh(), ladder = new Mesh();
    /** A floating-roof tank's roof as the world collides with it, in the frame; null for any other shape. */
    RoofPlan roofPlan;

    /**
     * A floating roof's outline for collision (owner request 2026-10-21: a player on the roof rides it): its axis {@code
     * centre} (x, z in the frame), its outer {@code radius} and its deck's {@code inner} one (the pontoon ring between),
     * and the pontoon ring's top over the roof's underside; the deck's is {@link Floating#deck}.
     */
    record RoofPlan(double[] centre, double radius, double inner, double pontoon) {
    }

    /**
     * Where a floating-roof tank's client-drawn parts stand, in the frame (plan section 3.4). The roof is drawn with its
     * underside at {@code landed} (on its legs) and floats with its underside on the liquid, up to {@code top} (the fill
     * height); its deck's top stands {@code deck} over its underside. The rolling ladder, {@code length} long, is drawn
     * level from its {@code pivot} along {@code along} (towards the axis) and turns down about {@code axis} (horizontal,
     * along the gauger's platform) until its foot rests on the runway, which runs {@code runway} along the deck from the
     * rim. The sight glass stands at {@code glass} (x, z) from {@code glassLow} to {@code glassHigh}, facing {@code facing}.
     */
    record Floating(double landed, double top, double deck, double[] pivot, double[] along, double[] axis, double length, double runway, double[] glass,
                    double glassLow, double glassHigh, double[] facing) {
        /** The roof's underside over the floor for a liquid {@code level} m deep: on the liquid, between its legs' and the fill height. */
        double underside(double level) {
            return Math.max(landed, Math.min(top, level));
        }

        /** The ladder's turn down from level, radians, with its foot on the roof's deck at {@code level}: straight down where it cannot reach. */
        double pitch(double level) {
            double drop = pivot[1] - (underside(level) + deck);
            return drop >= length ? Math.PI / 2 : drop <= 0 ? 0 : DeterministicMath.asin(drop / length);
        }
    }

    /** A heater's deck: its run along its wall, the height under its walking surface, its width, whether a rail closes each end. */
    record Deck(Access.Run run, double level, double width, boolean closedStart, boolean closedEnd) {
    }

    final List<Deck> decks = new ArrayList<>();
    private final Map<Long, List<Solid>> solidCells = new HashMap<>(), volumeCells = new HashMap<>();
    /** Solids whose box spans more than {@link #LARGE} cells (a tank's shell, its rings), tested at every point instead of filed under each cell. */
    private final List<Solid> largeSolids = new ArrayList<>(), largeVolumes = new ArrayList<>();
    static final int LARGE = 256;
    final List<Nozzle> nozzles = new ArrayList<>();
    /** Fixed casting radii at nozzle roots, by nozzle index: the pipe-sized nozzle blends into each casting. */
    final Map<Integer, Double> nozzleRoots = new HashMap<>();
    /** The sleeves through a closed skirt, by nozzle index: drawn with the nozzle at its pipe's size ({@link Sleeve}). */
    final Map<Integer, Sleeve> sleeves = new HashMap<>();

    /**
     * A nozzle's sleeve through a closed skirt (owner decision 2026-10-21: "Skirt hole needs to adapt to the size of the
     * pipe to max 1m"): the skirt round the vertical axis, {@code fromAxis} from the nozzle's base back to that axis along
     * the nozzle, of circumradius {@code radius} and inscribed radius {@code apothem}. At every pipe size the hole is the
     * line's outside and {@link EquipmentShape#PORT_CLEARANCE}, dark inside, its sleeve {@link EquipmentShape#PORT_COLLAR}
     * thick standing proud of the skirt ({@link ShapeRasterizer}).
     */
    record Sleeve(double fromAxis, double radius, double apothem) {
    }

    /** Fixed outline edges, {x0, y0, z0, x1, y1, z1}; round bodies outlined against the eye, {x, y, z on the axis, axis, radius, lo, hi}. */
    final List<double[]> edges = new ArrayList<>(), rounds = new ArrayList<>();
    private final double[] box;
    /** What draws the unit: its kind's design ({@link UnitDesign#build}), or a test's kit pieces. */
    private final Consumer<ProcessUnit> design;

    private ProcessUnit(EquipmentShape s, Consumer<ProcessUnit> design, boolean drawing) {
        shape = s;
        this.design = design;
        design.accept(this);
        box = box(mesh.bounds());
        if (drawing) return;
        mesh = null;
        index(solids, solidCells, largeSolids);
        index(volumes, volumeCells, largeVolumes);
    }

    /** A unit of {@code shape} drawn by {@code design} instead of its kind's (uncached): the kit's pieces under test. */
    static ProcessUnit of(EquipmentShape shape, Consumer<ProcessUnit> design) {
        return new ProcessUnit(shape, design, false);
    }

    /** The parts' facets in the frame, drawn afresh: the layout is deterministic. */
    Mesh mesh() {
        return drawing().mesh;
    }

    /** The unit drawn afresh, its meshes kept: the chunk mesh's parts and a floating-roof tank's client-drawn ones. */
    ProcessUnit drawing() {
        return new ProcessUnit(shape, design, true);
    }

    List<Access.WoundStair> woundStairs() {
        return List.copyOf(woundStairs);
    }

    List<Nozzle> nozzles() {
        return List.copyOf(nozzles);
    }

    /** A heater's decks and ladders, for the access gates. */
    List<Deck> decks() {
        return List.copyOf(decks);
    }

    List<Access.Climb> ladders() {
        return List.copyOf(ladders);
    }

    List<Stair> stairs() {
        return List.copyOf(stairs);
    }

    /** The box in the frame, {x0, y0, z0, x1, y1, z1}: the parts and every nozzle's flange face, in whole cells. */
    double[] box() {
        return box.clone();
    }

    /** Length along the frame's z (a lying unit's axis), m. */
    double length() {
        return box[5] - box[2];
    }

    /** Height of the unit's top (its parts, not its nozzles) above the ground, m. */
    double height() {
        return top;
    }

    private double top;

    double[] box(double[] bounds) {
        double[] out = {Math.floor(bounds[0] + 1e-9), 0, Math.floor(bounds[2] + 1e-9), Math.ceil(bounds[3] - 1e-9), Math.ceil(bounds[4] - 1e-9), Math.ceil(bounds[5] - 1e-9)};
        top = bounds[4];
        for (var n : nozzles) {
            double face = n.face()[n.axis()];
            if (n.sign() > 0) out[n.axis() + 3] = Math.max(out[n.axis() + 3], face);
            else out[n.axis()] = Math.min(out[n.axis()], face);
        }
        return out;
    }

    /** Whether a frame point lies in a part a player collides with: the parts, and a ladder's rung plane (a player climbs by pressing against it). */
    boolean inside(double[] p) {
        if (within(solidCells, largeSolids, p)) return true;
        for (var climb : ladders) if (climb.rungs(p)) return true;
        return false;
    }

    /**
     * Whether whole frame rows [low, low + 1) are body rows ({@link #bodyRows}), and their texture class: the course pair's
     * phase, 0 to 3; -1 where they are not.
     */
    int bodyClass(double low) {
        if (bodyRows == null || low < bodyRows[0] - 1e-9 || low + 1 > bodyRows[1] + 1e-9) return -1;
        return Math.floorMod((int) Math.round(low - bodyRows[2]), (int) (2 * ShapeRasterizer.COURSE));
    }

    /** Whether a frame point lies where a ladder is climbed: in front of its rungs, inside its cage, from its foot to its top. */
    boolean climbable(double[] p) {
        for (var climb : ladders) if (climb.climbable(p)) return true;
        return false;
    }

    /** Whether a frame point lies inside a closed body (a shell, a plenum): no cell there is open. */
    boolean filled(double[] p) {
        return within(volumeCells, largeVolumes, p);
    }

    /** Outline edges in the frame: the round bodies' rims and the air cooler's bundle. */
    List<double[]> edges() {
        return edges;
    }

    /** The round bodies' outlines seen from frame point {@code eye}: the two lines along each body where it turns away. */
    void silhouette(double[] eye, List<double[]> out) {
        for (var r : rounds) {
            int ax = (int) r[3], u = (ax + 1) % 3, v = (ax + 2) % 3;
            double radius = r[4], du = eye[u] - r[u], dv = eye[v] - r[v], d = Math.sqrt(du * du + dv * dv);
            if (d <= radius) continue;
            double towards = DeterministicMath.atan2(dv, du), spread = DeterministicMath.acos(radius / d);
            for (double angle : new double[]{towards - spread, towards + spread}) {
                double[] p = {r[0], r[1], r[2]}, q = {r[0], r[1], r[2]};
                p[u] += radius * DeterministicMath.cos(angle);
                p[v] += radius * DeterministicMath.sin(angle);
                q[u] = p[u];
                q[v] = p[v];
                p[ax] = r[5];
                q[ax] = r[6];
                out.add(new double[]{p[0], p[1], p[2], q[0], q[1], q[2]});
            }
        }
    }

    static boolean within(Map<Long, List<Solid>> cells, List<Solid> large, double[] p) {
        for (var s : large) if (s.contains(p)) return true;
        var list = cells.get(key(Mesh.floor(p[0]), Mesh.floor(p[1]), Mesh.floor(p[2])));
        if (list == null) return false;
        for (var s : list) if (s.contains(p)) return true;
        return false;
    }

    static void index(List<Solid> parts, Map<Long, List<Solid>> cells, List<Solid> large) {
        for (var s : parts) {
            long span = (long) (Mesh.floor(s.max()[0]) - Mesh.floor(s.min()[0]) + 1) * (Mesh.floor(s.max()[1]) - Mesh.floor(s.min()[1]) + 1) * (Mesh.floor(s.max()[2]) - Mesh.floor(s.min()[2]) + 1);
            if (span > LARGE) {
                large.add(s);
                continue;
            }
            for (int x = Mesh.floor(s.min()[0]); x <= Mesh.floor(s.max()[0]); x++)
                for (int y = Mesh.floor(s.min()[1]); y <= Mesh.floor(s.max()[1]); y++)
                    for (int z = Mesh.floor(s.min()[2]); z <= Mesh.floor(s.max()[2]); z++) cells.computeIfAbsent(key(x, y, z), k -> new ArrayList<>()).add(s);
        }
    }

    static long key(int x, int y, int z) {
        return (long) (x + 1024) << 42 | (long) (y + 1024) << 21 | (z + 1024);
    }

    // ---- Shell-and-tube exchanger -----------------------------------------------------------------------------------

    /**
     * A TEMA AES exchanger lying along z on two saddles, its bottom {@code support} up: from the front, the channel's
     * cover, the channel (tube side in at its bottom, out at its top), the tubesheet's flange, the shell (shell side in
     * on top near the back, out at the bottom near the front), the floating head's flange and its cover.
     */
    void exchanger() {
        double d = shape.diameter(), r = d / 2, y = shape.support() + r, a = centre(1.1 * r);
        int n = sides(d);
        double channel = .5 * d + .2, zn = Math.ceil(.08 + channel / 2 - .5) + .5;
        double c0 = zn - channel / 2 - .08, ch1 = zn + channel / 2, s0 = ch1 + .1, s1 = s0 + .95 * shape.tangentLength(), end = s1 + .1;
        var f = new Frame(new double[]{a, y, 0}, new double[]{0, 0, 1}, new double[]{0, 1, 0});
        mesh.ring(f, 0, 1.1 * r, c0, c0 + .08, n, 3, Material.FLANGE);
        mesh.bolts(f, d, r, 1.1 * r, c0, -1, Material.BOLT);
        mesh.prism(f, r, c0 + .08, ch1, n, Material.SHELL);
        mesh.ring(f, r, 1.1 * r, ch1, s0, n, 3, Material.FLANGE);
        mesh.bolts(f, d, r, 1.1 * r, ch1, -1, Material.BOLT);
        mesh.bolts(f, d, r, 1.1 * r, s0, 1, Material.BOLT);
        mesh.prism(f, r, s0, s1, n, Material.SHELL);
        mesh.ring(f, r, 1.08 * r, s1, end, n, 3, Material.FLANGE);
        mesh.bolts(f, d, r, 1.08 * r, s1, -1, Material.BOLT);
        mesh.bolts(f, d, r, 1.08 * r, end, 1, Material.BOLT);
        mesh.head(f, r, end, d / 4, n, Material.HEAD);
        solids.add(Solid.rod(new double[]{a, y, 0}, 2, c0, end, 1.05 * r, d / 4));
        volumes.add(Solid.rod(new double[]{a, y, 0}, 2, c0 + .1, end, .94 * r, .9 * d / 4));
        round(a, y, c0, end, r);

        double tube = Math.min(PipeSizes.MAX_OUTSIDE, .4 * d), shellOd = Math.min(PipeSizes.MAX_OUTSIDE, d / 2), flange = PipeSizes.flange(shellOd);
        nozzles.add(radial(Role.TUBE_INLET, a, y, r, n, zn, -1, ColumnStructure.size(.25 * d, tube)));
        nozzles.add(radial(Role.TUBE_OUTLET, a, y, r, n, zn, 1, ColumnStructure.size(.25 * d, tube)));
        // The shell nozzles near the tubesheets, two rows at least behind the channel's (one free block between pipes).
        double zIn = Math.max(Math.floor(s1 - flange / 2 - .1 - .5) + .5, zn + 2), zOut = Math.max(Math.ceil(s0 + flange / 2 + .1 - .5) + .5, zn + 2);
        nozzles.add(radial(Role.SHELL_INLET, a, y, r, n, zIn, 1, ColumnStructure.size(.3 * d, shellOd)));
        nozzles.add(radial(Role.SHELL_OUTLET, a, y, r, n, zOut, -1, ColumnStructure.size(.3 * d, shellOd)));
        // The saddles a fifth of the shell in from its ends, off the pipe blocks under the tube inlet and the shell outlet.
        double w = saddleWidth(r), length = s1 - s0;
        saddle(saddleAt(s0 + .2 * length, w, s0, s1, zn, zOut), a, y, r, w, 0);
        saddle(saddleAt(s1 - .2 * length, w, s0, s1, zn, zOut), a, y, r, w, 0);
    }

    // ---- Kettle reboiler --------------------------------------------------------------------------------------------

    /**
     * A kettle reboiler (TEMA AKT) lying along z, its kettle's bottom {@code support} up: the channel at the front (steam
     * in at its top, condensate out at its bottom), a cone up to the kettle's shell (1.6 times the bundle), the shell
     * (liquid fed low at its front, vapour out on top) and its back head, where the product leaves low behind the weir.
     */
    void kettle() {
        double db = shape.diameter(), rb = db / 2, dk = kettleShell(db), rk = dk / 2, y = shape.support() + rk, a = centre(rk);
        int n = sides(dk), nb = sides(db);
        double channel = .5 * db + .2, zn = Math.ceil(.08 + channel / 2 - .5) + .5;
        double c0 = zn - channel / 2 - .08, ch1 = zn + channel / 2, cone = Math.max(.3, .6 * (dk - db)), s0 = ch1 + .1 + cone, s1 = s0 + .9 * shape.tangentLength() + .3;
        var f = new Frame(new double[]{a, y, 0}, new double[]{0, 0, 1}, new double[]{0, 1, 0});
        mesh.ring(f, 0, 1.1 * rb, c0, c0 + .08, nb, 3, Material.FLANGE);
        mesh.bolts(f, db, rb, 1.1 * rb, c0, -1, Material.BOLT);
        mesh.prism(f, rb, c0 + .08, ch1, nb, Material.SHELL);
        mesh.ring(f, rb, 1.12 * rb, ch1, ch1 + .1, nb, 3, Material.FLANGE);
        mesh.bolts(f, db, rb, 1.12 * rb, ch1, -1, Material.BOLT);
        mesh.frustum(f, 1.05 * rb, rk, ch1 + .1, s0, n, Material.SHELL);
        mesh.prism(f, rk, s0, s1, n, Material.SHELL);
        mesh.head(f, rk, s1, dk / 4, n, Material.HEAD);
        // Channel, cone and kettle collide as drawn and enclose their insides.
        var axis = new double[]{a, y, 0};
        solids.add(Solid.rod(axis, 2, c0, ch1 + .1, 1.12 * rb, 0));
        solids.add(Solid.cone(axis, 2, ch1 + .1, s0, 1.05 * rb, rk, 0));
        solids.add(Solid.rod(axis, 2, s0, s1, rk, dk / 4));
        volumes.add(Solid.rod(axis, 2, c0 + .1, ch1 + .1, .94 * rb, 0));
        volumes.add(Solid.cone(axis, 2, ch1 + .1, s0 + .02, .94 * 1.05 * rb, .94 * rk, 0));
        volumes.add(Solid.rod(axis, 2, s0 + .02, s1, .94 * rk, .9 * dk / 4));
        round(a, y, c0, ch1 + .1, rb);
        round(a, y, s0, s1, rk);
        // The shell's pool node (W7): its lowest inside cell, under the back of its cylinder, clear of the feed.
        fluidPoint = new double[]{a, y - rk + .01, s1 - .25};

        double tube = Math.min(PipeSizes.MAX_OUTSIDE, .4 * db), liquid = Math.min(PipeSizes.MAX_OUTSIDE, db / 2), vapour = Math.min(PipeSizes.MAX_OUTSIDE, dk / 2), flange = PipeSizes.flange(liquid);
        nozzles.add(radial(Role.TUBE_INLET, a, y, rb, nb, zn, 1, ColumnStructure.size(.25 * db, tube)));
        nozzles.add(radial(Role.TUBE_OUTLET, a, y, rb, nb, zn, -1, ColumnStructure.size(.15 * db, tube)));
        // The feed near the cone, two rows at least behind the channel's outlet (one free block between pipes).
        double zFeed = Math.max(Math.ceil(s0 + flange / 2 + .1 - .5) + .5, zn + 2);
        nozzles.add(radial(Role.FEED, a, y, rk, n, zFeed, -1, ColumnStructure.size(.15 * db, liquid)));
        nozzles.add(radial(Role.VAPOUR_OUTLET, a, y, rk, n, Math.floor((s0 + s1) / 2) + .5, 1, ColumnStructure.size(.3 * dk, vapour)));
        // The product: low on the back head, at the lowest cell centre 0.3 m over the shell's bottom, its default size
        // within 0.85 of the head's radius from the axis; its neck rooted under the head where a pipe's largest neck's
        // edge meets it (owner decision 2026-10-21: a 1 m pipe has a 1 m root).
        double yP = Math.ceil(shape.support() + .3 - .5) + .5, e = y - yP, depth = dk / 4;
        double od = Math.min(liquid, 2 * (.85 * rk - Math.abs(e)));
        double edge = (Math.abs(e) + PipeSizes.MAX_OUTSIDE / 2) / rk, under = depth * Math.sqrt(Math.max(0, 1 - edge * edge)), start = s1 + under - .03;
        nozzles.add(new Nozzle(Role.LIQUID_OUTLET, new double[]{a, yP, start}, 2, 1, Math.ceil(s1 + depth + NECK) - start,
                ColumnStructure.size(.15 * db, od), 0));
        double w = saddleWidth(rk), length = s1 - s0;
        saddle(saddleAt(s0 + .2 * length, w, s0, s1, zFeed), a, y, rk, w, 0);
        saddle(saddleAt(s1 - .2 * length, w, s0, s1, zFeed), a, y, rk, w, 0);
    }

    // ---- Air cooler -------------------------------------------------------------------------------------------------

    /**
     * An air cooler bank of forced-draft single-pass bays ({@link Bank}), their tubes along z. Per bay: the inlet header at
     * its front and the outlet header at its back (each centred half a metre in from its end), the finned bundle between
     * them {@code support} up, a plenum under it tapering to its fans' floor, each fan (a Blender rotor in its ring and
     * guard) with its motor under it on a beam, and legs under the headers' ends (one shared where trains meet). Bays in
     * series join back header to front header by crossover spools. Trains in parallel take the process from an inlet
     * manifold over their first front headers, the bank's inlet on its top, and give it to an outlet manifold behind their
     * last back headers, the outlet under it where the condensate drains; a single train's inlet is on its first header and
     * its outlet under a stub behind its last.
     */
    void airCooler() {
        var bank = shape.bank();
        int trains = bank.parallel(), rows = bank.series();
        double w = shape.diameter(), length = shape.tangentLength(), hb = shape.support(), h = header(length);
        double low = hb - .05, high = low + HEADER_HEIGHT, a = centre(trains * w / 2), x0 = a - trains * w / 2, x1 = x0 + trains * w, end = rows * length;
        var fans = fans(w, length);
        var stack = FanStack.of(hb, fans.diameter());
        double d = fans.diameter(), up = stack.up(), bottom = stack.floor(), rotor = stack.rotor();
        double motor = stack.motor(), motorTop = stack.motorTop(), motorBottom = stack.motorBottom();
        // A bay's nozzles and crossovers by its width; a manifold carries the trains' flow at the same speed.
        double stub = .1 + .04 * w, manifold = Math.min(HEADER_HEIGHT - .1, stub * Math.sqrt(trains));
        var fanZ = new ArrayList<Double>();
        for (int k = 0; k < rows; k++) {
            double front = k * length + .5, back = (k + 1) * length - .5, front1 = front + h / 2, back0 = back - h / 2;
            for (int i = 0; i < trains; i++) {
                double bx0 = x0 + i * w, bx1 = bx0 + w, mid = bx0 + w / 2, ix0 = bx0 + .11, ix1 = bx1 - .11;
                for (double zh : new double[]{front, back})
                    box(new double[]{bx0 + .05, low, zh - h / 2}, new double[]{bx1 - .05, high, zh + h / 2}, Material.AC_HEADER, true);
                box(new double[]{ix0, hb, front1}, new double[]{ix1, hb + BUNDLE, back0}, Material.AC_FINS, true);
                for (double x : new double[]{bx0 + .05, ix1})
                    box(new double[]{x, hb - .02, front1}, new double[]{x + .06, hb + BUNDLE + .02, back0}, Material.STEELWORK, false);
                double share = (back0 - front1 - .1) / fans.count();
                var centres = new double[fans.count()];
                for (int f = 0; f < centres.length; f++) centres[f] = front1 + .05 + (f + .5) * share;
                plenum(ix0, ix1, front1, back0, hb, bottom, centres, RING_OD / 2 * d);
                for (int f = 0; f < fans.count(); f++) {
                    double zc = front1 + .05 + (f + .5) * share;
                    if (i == 0 && k == 0) fanZ.add(zc);
                    Part.of("ac_fan_ring").place(mesh, new double[]{mid, bottom, zc}, d, up, 0, Material.AC_RING);
                    // The rotor turns clockwise seen from below, each from its own angle so a bank's blades are not in step.
                    mesh.part(motions.size());
                    Part.of("ac_fan").place(mesh, new double[]{mid, rotor, zc}, d, d, 0, Material.AC_FAN);
                    mesh.part(-1);
                    motions.add(Motion.turning(new double[]{mid, rotor, zc}, new double[]{0, -1, 0}, FAN_SPEED, .618 * motions.size()));
                    // Terminal boxes face out of the bank.
                    Part.of("ac_motor").place(mesh, new double[]{mid, motorTop, zc}, motor, motor, 2 * i < trains - 1 ? 2 : 0, Material.AC_MOTOR);
                    mesh.cylinder(Frame.of(new double[]{mid, 0, zc}, 1, 1), .025 * motor, motorTop, rotor - .06 * d, 8, Material.FLANGE);
                    solids.add(Solid.rod(new double[]{mid, 0, zc}, 1, bottom - .35 * up, bottom, .535 * d, 0));
                    solids.add(Solid.rod(new double[]{mid, 0, zc}, 1, motorBottom, motorTop, .2 * motor, 0));
                }
                if (k + 1 < rows) spool(mid, (low + high) / 2, back + h / 2, back + 1 - h / 2, stub);
            }
        }
        // Legs under the headers' ends; beams under the headers across the bank and along each leg line; at the motors'
        // level beams along the leg lines and across the bank under each row of motors.
        var legX = new ArrayList<Double>();
        legX.add(x0 + .15);
        for (int i = 1; i < trains; i++) legX.add(x0 + i * w);
        legX.add(x1 - .15);
        var legZ = new ArrayList<Double>();
        for (int k = 0; k < rows; k++) {
            legZ.add(k * length + .5);
            legZ.add((k + 1) * length - .5);
        }
        for (double x : legX)
            for (double z : legZ) box(new double[]{x - POST / 2, 0, z - POST / 2}, new double[]{x + POST / 2, low, z + POST / 2}, Material.STEELWORK, true);
        for (double z : legZ) box(new double[]{x0 + .05, low - BEAM, z - FLANGE_BEAM / 2}, new double[]{x1 - .05, low, z + FLANGE_BEAM / 2}, Material.STEELWORK, true);
        for (double x : legX)
            for (double y : new double[]{low, motorBottom})
                box(new double[]{x - FLANGE_BEAM / 2, y - BEAM, legZ.getFirst()}, new double[]{x + FLANGE_BEAM / 2, y, legZ.getLast()}, Material.STEELWORK, true);
        for (int k = 0; k < rows; k++)
            for (double zc : fanZ)
                box(new double[]{x0 + .15, motorBottom - BEAM, k * length + zc - FLANGE_BEAM / 2}, new double[]{x1 - .15, motorBottom, k * length + zc + FLANGE_BEAM / 2},
                        Material.STEELWORK, true);
        // The process in at the front, on top; out at the back, underneath.
        double zIn = .5, zOut = end + .5;
        if (trains == 1) nozzles.add(new Nozzle(Role.PROCESS_INLET, new double[]{a, high - .03, zIn}, 1, 1, Math.ceil(high + NECK) - high + .03, stub, 0));
        else {
            double ym = high + .1 + manifold / 2;
            manifold(x0 + w / 2, x1 - w / 2, ym, zIn, manifold);
            for (int i = 0; i < trains; i++) {
                var f = Frame.of(new double[]{x0 + (i + .5) * w, 0, zIn}, 1, 1);
                mesh.prism(f, stub / 2, high, ym, sides(stub), Material.PIPE);
                mesh.ring(f, stub / 2, PipeSizes.flange(stub) / 2, high, high + .03, sides(stub), 3, Material.FLANGE);
                mesh.bolts(f, stub, stub / 2, PipeSizes.flange(stub) / 2, high + .03, 1, Material.BOLT);
            }
            nozzles.add(new Nozzle(Role.PROCESS_INLET, new double[]{a, ym + manifold / 2 - .03, zIn}, 1, 1,
                    Math.ceil(ym + manifold / 2 + NECK) - ym - manifold / 2 + .03, manifold, 0));
        }
        double out = trains == 1 ? stub : manifold, yo = low + .05 + stub / 2;
        for (int i = 0; i < trains; i++) {
            double mid = x0 + (i + .5) * w, z0 = end - .5 + h / 2;
            var f = Frame.of(new double[]{mid, yo, 0}, 2, 1);
            mesh.prism(f, stub / 2, z0, trains == 1 ? zOut + stub / 2 : zOut, sides(stub), Material.PIPE);
            mesh.ring(f, stub / 2, PipeSizes.flange(stub) / 2, z0, z0 + .03, sides(stub), 3, Material.FLANGE);
            mesh.bolts(f, stub, stub / 2, PipeSizes.flange(stub) / 2, z0 + .03, 1, Material.BOLT);
            if (trains == 1) mesh.annulus(f, 0, stub / 2, zOut + stub / 2, sides(stub), 1, Material.PIPE);
            solids.add(Solid.rod(new double[]{mid, yo, 0}, 2, z0, zOut, stub / 2, 0));
        }
        if (trains > 1) manifold(x0 + w / 2, x1 - w / 2, yo, zOut, manifold);
        double outBase = yo - out / 2 + .03;
        nozzles.add(new Nozzle(Role.PROCESS_OUTLET, new double[]{a, outBase, zOut}, 1, -1, outBase - Math.floor(yo - out / 2 - NECK), out, 0));
        var outline = new double[][]{{x0 + .05, high, .5 - h / 2}, {x1 - .05, high, .5 - h / 2}, {x1 - .05, high, end - .5 + h / 2}, {x0 + .05, high, end - .5 + h / 2}};
        for (int k = 0; k < 4; k++) segment(outline[k], outline[(k + 1) % 4]);
    }

    /** Sides of the Blender fan ring's polygon ({@code ac_fan_ring}), a multiple of 4 for the hopper's quadrants. */
    static final int RING_SIDES = 24;

    /**
     * A bay's plenum (owner report 2026-10-16 with a photo: "the metal sheets lack a bottom casing"): a sheet-metal hopper
     * per fan, from the bundle's frame (x0 to x1, the fan's share of z0 to z1, at {@code top}) down to its ring's top
     * flange (radius {@code ring} round each of {@code centres}) at {@code bottom}. Each hopper is a square-to-round
     * transition: a triangle from each side of the frame to the circle's point facing it, a fan of triangles from each
     * corner to the quarter of the circle between, at the ring's {@link #RING_SIDES} corners. Sheets are drawn on both
     * faces, so the casing also shows from inside a ring; looking up through a ring one sees the bundle's fins.
     */
    void plenum(double x0, double x1, double z0, double z1, double top, double bottom, double[] centres, double ring) {
        for (int f = 0; f < centres.length; f++) {
            double za = f == 0 ? z0 : (centres[f - 1] + centres[f]) / 2, zb = f + 1 == centres.length ? z1 : (centres[f] + centres[f + 1]) / 2;
            // The ring part's top flange is a 24-gon at these angles (ac_parts.py, lathe n = 24): the hopper meets its corners.
            transition(1, x0, x1, za, zb, top, (x0 + x1) / 2, centres[f], ring, bottom, RING_SIDES, Material.AC_PLENUM, true);
        }
        // Collides as its frame's box; encloses its inside.
        solids.add(Solid.box(new double[]{x0, bottom, z0}, new double[]{x1, top, z1}));
        volumes.add(Solid.box(new double[]{x0 + .02, bottom + .02, z0 + .02}, new double[]{x1 - .02, top - .02, z1 - .02}));
    }

    /**
     * A square-to-round transition of sheet metal along frame axis {@code axis} (0 x, 1 y, 2 z): from the rectangle p0 to
     * p1 by q0 to q1 at station {@code at} on that axis to the circle of {@code radius} round (cp, cq) at station
     * {@code to}, where p and q are the other two axes in rising order (y and z along x, x and z along y, x and y along z);
     * the circle's angle 0 lies towards +p, turning towards +q (an air cooler's plenum hopper down to its fan ring, a
     * heater's breeching up to its stack, a cyclone's inlet duct, a pump's casing). A triangle runs from each side of the
     * rectangle to the circle's point facing it, a fan of triangles from each corner to the quarter of the circle between,
     * at the circle's {@code n} corners (a multiple of 4, whose corners a fan ring's or a stack's prism shares). Drawn
     * facing out, and also in where {@code inside} (a plenum seen up through its fan ring).
     */
    void transition(int axis, double p0, double p1, double q0, double q1, double at, double cp, double cq, double radius, double to, int n, Material material, boolean inside) {
        int quarter = n / 4;
        // The rectangle's corners in the circle's quadrants, counter-clockwise from +p +q.
        double[][] corners = {point(axis, at, p1, q1), point(axis, at, p0, q1), point(axis, at, p0, q0), point(axis, at, p1, q0)};
        var circle = new double[n + 1][];
        for (int i = 0; i <= n; i++) {
            double angle = 2 * Math.PI * (i % n) / n;
            circle[i] = point(axis, to, cp + radius * DeterministicMath.cos(angle), cq + radius * DeterministicMath.sin(angle));
        }
        var centre = point(axis, (at + to) / 2, cp, cq);
        for (int q = 0; q < 4; q++) {
            sheet(List.of(corners[(q + 3) % 4], corners[q], circle[q * quarter]), centre, material, inside);
            for (int i = q * quarter; i < (q + 1) * quarter; i++) sheet(List.of(corners[q], circle[i], circle[i + 1]), centre, material, inside);
        }
    }

    /** The frame point at station {@code at} on frame axis {@code axis} and (p, q) on the other two, in rising order. */
    static double[] point(int axis, double at, double p, double q) {
        var out = new double[3];
        out[axis] = at;
        out[axis == 0 ? 1 : 0] = p;
        out[axis == 2 ? 1 : 2] = q;
        return out;
    }

    /** One transition sheet, facing away from its {@code axis} point and, where {@code inside}, also towards it. */
    void sheet(List<double[]> triangle, double[] axis, Material material, boolean inside) {
        mesh.add(triangle, axis, FLAT, material, null, null);
        if (!inside) return;
        var centroid = new double[3];
        for (var p : triangle)
            for (int a = 0; a < 3; a++) centroid[a] += p[a] / 3;
        mesh.add(triangle, new double[]{2 * centroid[0] - axis[0], 2 * centroid[1] - axis[1], 2 * centroid[2] - axis[2]}, FLAT, material, null, null);
    }

    /** A crossover spool along z at (x, y) from a back header's face {@code z0} to the next front header's {@code z1}, flanged at both. */
    void spool(double x, double y, double z0, double z1, double od) {
        var f = Frame.of(new double[]{x, y, 0}, 2, 1);
        int n = sides(od);
        double r = PipeSizes.flange(od) / 2;
        mesh.prism(f, od / 2, z0, z1, n, Material.PIPE);
        mesh.ring(f, od / 2, r, z0, z0 + .03, n, 3, Material.FLANGE);
        mesh.bolts(f, od, od / 2, r, z0 + .03, 1, Material.BOLT);
        mesh.ring(f, od / 2, r, z1 - .03, z1, n, 3, Material.FLANGE);
        mesh.bolts(f, od, od / 2, r, z1 - .03, -1, Material.BOLT);
        solids.add(Solid.rod(new double[]{x, y, 0}, 2, z0, z1, od / 2, 0));
    }

    /** A manifold along x from {@code from} to {@code to} (capped half its size beyond each) at height {@code y}, station {@code z}. */
    void manifold(double from, double to, double y, double z, double od) {
        var f = Frame.of(new double[]{0, y, z}, 0, 1);
        mesh.cylinder(f, od / 2, from - od / 2, to + od / 2, sides(od), Material.PIPE);
        solids.add(Solid.rod(new double[]{0, y, z}, 0, from - od / 2, to + od / 2, od / 2, 0));
    }

    // ---- Steam ejector ----------------------------------------------------------------------------------------------

    /**
     * A steam ejector standing on its own frame, sized by its suction s: from the top, the steam chest (motive steam in on
     * top), the suction chamber (suction from the front), the inlet cone, the throat and the diffuser down to its discharge
     * flange, {@code support} up. Four posts round it from the ground to over the chest, tied by square frames every
     * 2.5 m, open at the front in the suction's row; a small one ({@link #SMALL_EJECTOR}), which a plant hangs on its
     * piping, on one post in the cell behind it. Lengths in suction diameters: chest 0.8, chamber 1.5 (1.2 wide), inlet
     * 2.2, throat 0.8 (0.35 wide), diffuser 3.0 (to 0.75 wide).
     */
    void ejector() {
        // Posts at ±e round the axis, their base plates outside the pipe blocks of the suction and the discharge (half a
        // block each side of the axis), and clear of the chamber; a small ejector's axis on its cell's centre.
        double s = shape.diameter(), e = Math.max(.5 + POST, .6 * s + .3), h = shape.support();
        boolean small = s <= SMALL_EJECTOR + 1e-9;
        double a = small ? .5 : centre(e + POST);
        // The suction's centre on a cell centre: the diffuser's exit at least 0.2 m over the discharge flange.
        int row = (int) Math.ceil(h + .2 + 6.75 * s - .5);
        double exit = row + .5 - 6.75 * s, chamber = exit + 6 * s, chest = exit + 7.5 * s, top = exit + 8.3 * s;
        var f = Frame.of(new double[]{a, exit, a}, 1, 1);
        int n = sides(1.2 * s);
        double rc = .6 * s, throat = .175 * s, outlet = .375 * s;
        mesh.ring(f, 0, outlet + .05 + .1 * s, -.03, 0, n, 1, Material.FLANGE);
        mesh.bolts(f, 2 * outlet, outlet, outlet + .05 + .1 * s, 0, 1, Material.BOLT);
        mesh.frustum(f, outlet, throat, 0, 3 * s, n, Material.MACHINE);
        mesh.prism(f, throat, 3 * s, 3.8 * s, n, Material.MACHINE);
        mesh.frustum(f, throat, rc, 3.8 * s, 6 * s, n, Material.MACHINE);
        mesh.prism(f, rc, 6 * s, 7.5 * s, n, Material.MACHINE);
        mesh.annulus(f, .225 * s, rc, 7.5 * s, n, 1, Material.MACHINE);
        mesh.ring(f, .225 * s, .3 * s, 7.5 * s, 7.58 * s, n, 3, Material.FLANGE);
        mesh.bolts(f, .45 * s, .225 * s, .3 * s, 7.58 * s, 1, Material.BOLT);
        mesh.cylinder(f, .225 * s, 7.5 * s, 8.3 * s, n, Material.MACHINE);
        solids.add(Solid.rod(new double[]{a, 0, a}, 1, exit, chest, rc, 0));
        solids.add(Solid.rod(new double[]{a, 0, a}, 1, chest, top, .225 * s, 0));
        round(a, a, chamber, chest, rc, 1);
        nozzles.add(radialSide(Role.SUCTION, a, row + .5, a, rc, n, -1, s));
        // The motive inlet's flange on the block face over the steam chest, and at least two rows over the suction's (one
        // free block between their pipes): a small ejector's stub runs up to it.
        double motive = Math.max(PipeSizes.MIN_OUTSIDE, .45 * s), motiveFace = Math.max(Math.ceil(top + NECK), row + 2);
        nozzles.add(new Nozzle(Role.MOTIVE, new double[]{a, top - .03, a}, 1, 1, motiveFace - top + .03,
                ColumnStructure.size(.3 * s, motive), 0));
        double discharge = Math.max(PipeSizes.MIN_OUTSIDE, 2 * outlet);
        nozzles.add(new Nozzle(Role.DISCHARGE, new double[]{a, exit + .03, a}, 1, -1, exit + .03 - h, discharge, 0));
        double postTop = top + .3, bar = .14, clamp = exit + 3.4 * s;
        if (small) {
            // One post in the cell behind (the suction's pipe leaves in front, the discharge's below, the motive's above),
            // braced to the throat and the chamber.
            double z = a + 1;
            box(new double[]{a - POST / 2, 0, z - POST / 2}, new double[]{a + POST / 2, postTop, z + POST / 2}, Material.STEELWORK, true);
            box(new double[]{a - POST, 0, z - POST}, new double[]{a + POST, .04, z + POST}, Material.SUPPORT, false);
            mesh.bar(new double[]{a, clamp, z}, new double[]{a, clamp, a + throat}, bar * .6, new double[]{0, 1, 0}, Material.STEELWORK);
            mesh.bar(new double[]{a, (chamber + chest) / 2, z}, new double[]{a, (chamber + chest) / 2, a + rc}, bar * .6, new double[]{0, 1, 0}, Material.STEELWORK);
            return;
        }
        // The frame: posts beside the suction's pipe block, square frames open at the front in its row, a clamp at the
        // throat.
        double[][] corners = {{a - e, a - e}, {a + e, a - e}, {a + e, a + e}, {a - e, a + e}};
        for (var c : corners) {
            box(new double[]{c[0] - POST / 2, 0, c[1] - POST / 2}, new double[]{c[0] + POST / 2, postTop, c[1] + POST / 2}, Material.STEELWORK, true);
            box(new double[]{c[0] - POST, 0, c[1] - POST}, new double[]{c[0] + POST, .04, c[1] + POST}, Material.SUPPORT, false);
        }
        var levels = new ArrayList<Double>();
        for (double y = 2.5; y < postTop - .3; y += 2.5) levels.add(y);
        levels.add(clamp);
        levels.add(postTop - .1);
        for (double y : levels) {
            // In the suction's row its pipe leaves between the front posts: the front bar and brackets give way there.
            boolean suction = y + bar / 2 > row - .05 && y - bar / 2 < row + 1.05;
            for (int k = suction ? 1 : 0; k < 4; k++) {
                var p = corners[k];
                var q = corners[(k + 1) % 4];
                mesh.bar(new double[]{p[0], y, p[1]}, new double[]{q[0], y, q[1]}, bar * .8, new double[]{0, 1, 0}, Material.STEELWORK);
            }
            // At the clamp, brackets from the corners to the throat.
            if (y == clamp)
                for (var c : corners)
                    if (!suction || c[1] > a)
                        mesh.bar(new double[]{c[0], y, c[1]}, new double[]{a + (c[0] - a) * throat / e, y, a + (c[1] - a) * throat / e}, bar * .6,
                                new double[]{0, 1, 0}, Material.STEELWORK);
        }
    }

    // ---- Centrifugal pump -------------------------------------------------------------------------------------------

    /**
     * An API 610 OH2 pump (owner decision M2 2026-10-17; {@code MACHINES_HEATERS_SURVEY.md}) on its baseplate, its shaft
     * along z through a cell centre at height y. From the front: the pump's own Class 300 suction flange on a short cast
     * bell; the volute, its scroll growing from 0.55 to 0.88 impeller diameters round a hub, its throat turning up into
     * the cast discharge neck and flange on the casing's centre line; the cover's joint ring of studs; the bearing bracket
     * with a window each side showing the seal chamber and gland; the bearing housing (oil sump in front with its
     * constant-level oiler, cooling fins behind, a vent on top); the coupling's half-round guard; the TEFC motor (fins,
     * feet, fan cowl and grille, terminal box, lifting eyes). The casing stands on its centreline feet on pedestals (API
     * 610 allows no foot under the bearing housing). From 0.1 m a seal pot (API 682 plans 52/53) stands on its post beside
     * the bracket, from 0.15 m a plan 11 flush line runs from the discharge neck to the gland. The baseplate's drain rim
     * stands on a grout line on a chamfered plinth; between the pump's flanges and the block faces the nozzles run as
     * spools of the connected pipe. Sizes follow the discharge d: suction {@link #pumpSuction}, impeller 0.18 + 1.2 d, the
     * motor {@link #motorDiameter} of {@link #pumpPower} across.
     */
    void pump() {
        if (shape.diameter() > OH2_MAX + 1e-9) {
            splitPump();
            return;
        }
        double d = shape.diameter(), s = pumpSuction(d), di = .18 + 1.2 * d;
        double rh = .45 * di, rv0 = .55 * di, rv1 = .88 * di, width = .4 * di + .06, joint = .82 * di, tj = .04 + .04 * di;
        double plate = .15 + .1 * di, rm = motorDiameter(pumpPower(d)) / 2, lm = 3.2 * rm + .1, fin = .025 + .06 * rm;
        double rb = .08 + .22 * di, lb = .3 + .6 * di, lk = .2 + .3 * di, guard = .12 + .2 * di, spacer = .15 + .25 * di, shaft = .03 + .05 * di;
        // Keep the established port clearances; the shared nozzle mesh draws the flange at the connection face.
        double ts = 1.3 * PipeSizes.flangeThickness(s), td = 1.3 * PipeSizes.flangeThickness(d);
        double bell = .08 + .7 * s, pot = d >= .1 ? .07 + .1 * d : 0;
        double half = Math.max(Math.max(rv1, joint) + .14, Math.max(Math.max(rm + fin, guard) + .08, pot > 0 ? .55 * di + 2 * pot + .17 : 0));
        double a = centre(half + .12);
        // Heights: a plinth of 0.15 to 0.45 m, a grout line, the baseplate; the shaft on the lowest cell centre whose casing
        // clears the baseplate by 0.06 m, the pedestals taking what the plinth does not.
        double y = Math.max(.5, Math.ceil(.15 + .04 + plate + .06 + rv1 - .5) + .5);
        double plinth = Math.max(.15, Math.min(.45, y - rv1 - .3 - plate - .04)), top = plinth + .04 + plate;
        // Along z: the line's spool, the suction neck and bell, the casing (its middle on a cell centre, the discharge's
        // line), the cover, the bracket, the bearing housing, the spacer under its guard, the motor.
        double zc = Math.ceil(.25 + ts + bell + .3 * width - .5) + .5, z0 = zc - width / 2, z1 = zc + width / 2, zh = zc - .3 * width, zf = zh - bell - ts;
        double zk = z1 + tj, zb0 = zk + lk, zb1 = zb0 + lb, zm0 = zb1 + .08 + spacer, zm1 = zm0 + lm, cowl = zm1 - .18 * lm, seal = zk + .45 * lk;
        var f = Frame.of(new double[]{a, y, 0}, 2, 1);
        int n = sides(2 * rv1), nm = sides(2 * rm), nb = sides(2 * rb);
        // The suction bell, the volute and its hub, the cover with its joint ring of studs.
        mesh.frustum(f, .5 * s + .04, .43 * s + .04, zf + ts, zh, n, Material.MACHINE);
        scroll(a, y, z0, z1, zh, rh, rv0, rv1);
        mesh.prism(f, rh, zh, z1, n, Material.MACHINE);
        mesh.annulus(f, .43 * s + .04, rh, zh, n, -1, Material.MACHINE);
        mesh.cylinder(f, joint, z1, zk, n, Material.MACHINE);
        mesh.bolts(f, Math.min(PipeSizes.MAX_OUTSIDE, 1.6 * joint), joint - .12, joint, zk, 1, Material.BOLT);
        // The bracket, windowed each side over the seal chamber, its gland and the shaft turning with its seal's collar.
        bracket(f, .62 * di, rb + .03, zk, zb0, n);
        mesh.cylinder(f, .27 * di, zk, seal, n, Material.MACHINE);
        mesh.cylinder(f, .2 * di, seal, seal + .05, n, Material.FLANGE);
        shaft(f, shaft, new double[][]{{seal + .05, zb0}}, seal + .05 + collar(shaft) / 2);
        // The bearing housing: its oil sump, the finned round body, the end cover; the oiler on the sump, the vent on top.
        double sump = zb0 + .45 * lb;
        box(new double[]{a - .9 * rb, y - 1.2 * rb, zb0}, new double[]{a + .9 * rb, y + .55 * rb, sump}, Material.MACHINE, false);
        mesh.prism(f, rb, zb0, zb1, nb, Material.MACHINE);
        mesh.annulus(f, rb, rb + .03, zb0, nb, 1, Material.MACHINE);
        mesh.annulus(f, 0, rb, zb1, nb, 1, Material.MACHINE);
        for (int k = 1; k <= 5; k++) {
            double z = sump + (zb1 - sump) * k / 6;
            mesh.ring(f, rb, rb + .025, z - .01, z + .01, nb, 3, Material.MACHINE);
        }
        mesh.cylinder(f, .55 * rb, zb1, zb1 + .03, nb, Material.MACHINE);
        double bulb = .035 + .02 * di, oiler = y - .8 * rb, xo = a + .9 * rb + .06 + bulb;
        box(new double[]{a + .9 * rb, oiler - .015, zb0 + .2 * lb - .015}, new double[]{xo, oiler + .015, zb0 + .2 * lb + .015}, Material.PIPE, false);
        mesh.cylinder(Frame.of(new double[]{xo, 0, zb0 + .2 * lb}, 1, 1), bulb, oiler, oiler + .13 + .05 * di, 8, Material.OPENING);
        mesh.cylinder(Frame.of(new double[]{a, 0, sump + .6 * (zb1 - sump)}, 1, 1), .025 + .01 * di, y + rb - .01, y + rb + .05, 8, Material.PIPE);
        // The coupling guard, a half-round hood over the spacer.
        var hood = new ArrayList<double[]>();
        hood.add(new double[]{a - guard, top});
        hood.add(new double[]{a + guard, top});
        for (int k = 0; k <= 6; k++)
            hood.add(new double[]{a + guard * DeterministicMath.cos(Math.PI * k / 6), y + guard * DeterministicMath.sin(Math.PI * k / 6)});
        extrude(hood, zb1 + .04, zm0 - .03, Material.GUARD);
        mesh.prism(f, shaft, zb1 + .03, zm0, 8, Material.PIPE);
        solids.add(Solid.box(new double[]{a - guard, top, zb1 + .04}, new double[]{a + guard, y + guard, zm0 - .03}));
        motor(f, rm, fin, zm0, cowl, zm1, nm, shaft, top, MotorDetail.FULL);
        // The casing's centreline feet near its cover on their pedestals.
        for (int side = -1; side <= 1; side += 2) {
            double inner = a + side * rh, outer = a + side * (rv1 + .1), px = a + side * (rv1 + .03);
            box(new double[]{Math.min(inner, outer), y - .06, zc}, new double[]{Math.max(inner, outer), y + .02, zk}, Material.MACHINE, false);
            box(new double[]{px - .07, top, zc + .02}, new double[]{px + .07, y - .06, zk - .02}, Material.STEELWORK, false);
        }
        // The cast discharge: a throat of about 1.2 DN square turning round into the flange, 0.12 + 0.2 d over the crown.
        double hx = .6 * d + .03, hz = Math.min(.6 * d + .03, .45 * width), neck = y + rv1 + .12 + .2 * d;
        box(new double[]{a - hx, y + rh, zc - hz}, new double[]{a + hx, y + .98 * rv0, zc + hz}, Material.MACHINE, false);
        transition(1, a - hx, a + hx, zc - hz, zc + hz, y + .98 * rv0, a, zc, .5 * d + .025, neck, 4 * ((sides(d) + 3) / 4), Material.MACHINE, false);
        // The baseplate on its grout line and chamfered plinth: its drain rim and drain.
        double zp0 = z0 - .1, zp1 = zm1 + .05;
        box(new double[]{a - half, plinth + .04, zp0}, new double[]{a + half, top, zp1}, Material.STEELWORK, true);
        for (int side = -1; side <= 1; side += 2) {
            box(new double[]{side < 0 ? a - half : a + half - .03, top, zp0}, new double[]{side < 0 ? a - half + .03 : a + half, top + .05, zp1}, Material.STEELWORK, false);
            double ze = side < 0 ? zp0 : zp1 - .03;
            box(new double[]{a - half + .03, top, ze}, new double[]{a + half - .03, top + .05, ze + .03}, Material.STEELWORK, false);
        }
        mesh.cylinder(Frame.of(new double[]{a + half - .15, plinth + .04 + plate / 2, 0}, 2, 1), .03, zp0 - .06, zp0, 8, Material.PIPE);
        box(new double[]{a - half + .02, plinth, zp0 + .02}, new double[]{a + half - .02, plinth + .04, zp1 - .02}, Material.CONCRETE, false);
        double base = half + .12, chamfer = .04;
        extrude(List.of(new double[]{a - base, 0}, new double[]{a + base, 0}, new double[]{a + base, plinth - chamfer}, new double[]{a + base - chamfer, plinth},
                new double[]{a - base + chamfer, plinth}, new double[]{a - base, plinth - chamfer}), zp0 - .12, zp1 + .12, Material.CONCRETE);
        solids.add(Solid.box(new double[]{a - base, 0, zp0 - .12}, new double[]{a + base, plinth + .04, zp1 + .12}));
        // The seal's pot on its post beside the bracket and its two lines into the gland; the flush line from the neck.
        if (pot > 0) {
            double xp = a - (.55 * di + pot + .12), bottom = y - .05, height = .55 + 1.2 * d;
            var p = Frame.of(new double[]{xp, 0, seal}, 1, 1);
            mesh.prism(p, pot, bottom, bottom + height, 12, Material.MACHINE);
            mesh.head(p, pot, bottom + height, pot / 2, 12, Material.MACHINE);
            mesh.annulus(p, 0, pot, bottom, 12, -1, Material.MACHINE);
            box(new double[]{xp - .04, top, seal - .04}, new double[]{xp + .04, bottom, seal + .04}, Material.STEELWORK, false);
            for (double yl : new double[]{y - .02, y + .1 * di + .04})
                mesh.cylinder(Frame.of(new double[]{0, yl, seal + .025}, 0, 1), .012, xp + pot - .01, a - .2 * di + .01, 6, Material.PIPE);
            solids.add(Solid.rod(new double[]{xp, 0, seal}, 1, bottom, bottom + height, pot, pot / 2));
        }
        if (d >= .15) {
            double x1 = a + Math.max(rv1, joint) + .06, tap = (y + rv0 + neck) / 2, low = y + .1 * di;
            line(.015, Material.PIPE, new double[]{a + .5 * d + .02, tap, zc}, new double[]{x1, tap, zc}, new double[]{x1, low, zc}, new double[]{x1, low, seal + .025},
                    new double[]{a + .2 * di - .01, low, seal + .025});
        }
        solids.add(Solid.rod(new double[]{a, y, 0}, 2, zf, zk, Math.max(rv1, joint), 0));
        solids.add(Solid.rod(new double[]{a, y, 0}, 2, zk, zb1 + .03, Math.max(rb + .03, .62 * di), 0));
        round(a, y, z0, z1, rv1);
        // One equipment neck behind each connection flange, blending into its casting at the connected pipe's size.
        double suctionBase = zf + ts + .01;
        nozzles.add(new Nozzle(Role.SUCTION, new double[]{a, y, suctionBase}, 2, -1, suctionBase, s, 0));
        nozzleRoots.put(nozzles.size() - 1, .5 * s + .04);
        double base0 = neck - .01, face = Math.ceil(neck + td + EquipmentShape.STAND_OUT);
        nozzles.add(new Nozzle(Role.DISCHARGE, new double[]{a, base0, zc}, 1, 1, face - base0, d, 0));
        nozzleRoots.put(nozzles.size() - 1, .5 * d + .025);
    }

    /**
     * An API 610 BB1 pump (owner decision 2026-10-17, "add an axially split pump as a second pump type": ok;
     * {@code MACHINES_SCALE_RESEARCH.md}): between bearings, single stage, double suction, its casing split along the
     * shaft's centre line. The lower half, deep enough for both nozzles, carries them side by side across the shaft (the
     * suction on the right seen from the constructor, the discharge on the left, both horizontal, half a metre under the
     * shaft) and stands on four feet; the upper half, the volute's round cover over the suction chambers either side of
     * it, is bolted on the split flange running the casing's length, with two lifting lugs. Seal chambers in windowed
     * brackets and finned bearing housings at both ends; the coupling guard and a large motor with its top cooler behind,
     * on one baseplate, grout line and plinth. Sizes follow the discharge d: suction {@link #pumpSuction}, impeller
     * 0.35 + 0.8 d, the motor about 1.15 {@link #motorDiameter} of {@link #pumpPower} across.
     */
    void splitPump() {
        double d = shape.diameter(), s = pumpSuction(d), di = .35 + .8 * d;
        double rv = .9 * di, rc = .72 * di, wv = .5 * di, lc = 1.5 * di, split = .03 + .02 * di;
        double fs = 1.15 * PipeSizes.flange(s) / 2, ts = 1.3 * PipeSizes.flangeThickness(s), fd = 1.15 * PipeSizes.flange(d) / 2, td = 1.3 * PipeSizes.flangeThickness(d);
        double rb = .1 + .2 * di, lb = .25 + .35 * di, lk = .2 + .25 * di, guard = .15 + .2 * di, spacer = .2 + .2 * di, shaft = .04 + .05 * di;
        double across = 1.15 * motorDiameter(pumpPower(d)), lm = 1.7 * across + .2, plate = .2 + .1 * di;
        // Across x: each nozzle's branch reaches a spool past the casing; the box holds both flanges and their block faces.
        double reachS = rv + .25 * s + .15, reachD = rv + .25 * d + .15, deck = Math.max(rv + .2, across / 2 + .3);
        double a = centre(Math.max(Math.max(reachS + ts, reachD + td) + EquipmentShape.STAND_OUT + .05, deck + .12));
        // Heights: a 0.3 m plinth, a grout line, the baseplate; the nozzles on the lowest cell centre whose larger flange
        // clears the baseplate by 0.06 m, the shaft half a metre over them.
        double plinth = .3, top = plinth + .04 + plate, yn = Math.ceil(top + Math.max(fs, fd) + .06 - .5) + .5, y = yn + .5, low = yn - .5 * s - .05;
        // Along z: the free end's housing and bracket, the casing (its middle, the nozzles' line, on a cell centre), the
        // drive end's bracket and housing, the coupling under its guard, the motor.
        double zc = Math.ceil(.15 + lb + lk + lc / 2 - .5) + .5, z0 = zc - lc / 2, z1 = zc + lc / 2;
        double zf1 = z0 - lk, zf0 = zf1 - lb, zb0 = z1 + lk, zb1 = zb0 + lb, zm0 = zb1 + .08 + spacer, zm1 = zm0 + lm;
        var f = Frame.of(new double[]{a, y, 0}, 2, 1);
        int n = sides(2 * rv), nb = sides(2 * rb);
        // The upper casing: the suction chambers, the volute between them, the end walls.
        mesh.annulus(f, 0, rc, z0, n, -1, Material.MACHINE);
        mesh.prism(f, rc, z0, zc - wv / 2, n, Material.MACHINE);
        mesh.annulus(f, rc, rv, zc - wv / 2, n, -1, Material.MACHINE);
        mesh.prism(f, rv, zc - wv / 2, zc + wv / 2, n, Material.MACHINE);
        mesh.annulus(f, rc, rv, zc + wv / 2, n, 1, Material.MACHINE);
        mesh.prism(f, rc, zc + wv / 2, z1, n, Material.MACHINE);
        mesh.annulus(f, 0, rc, z1, n, 1, Material.MACHINE);
        // The lower casing down to the nozzles, its four feet; the split flange along the casing with its studs; the lugs.
        double lx = .8 * rv;
        box(new double[]{a - lx, low, z0}, new double[]{a + lx, y, z1}, Material.MACHINE, true);
        for (int side = -1; side <= 1; side += 2)
            for (double z : new double[]{z0 + .05, z1 - .3}) {
                double outer = a + side * lx, inner = outer - side * .22;
                box(new double[]{Math.min(inner, outer), top, z}, new double[]{Math.max(inner, outer), low, z + .25}, Material.MACHINE, false);
            }
        box(new double[]{a - rv - .08, y - split, z0 - .04}, new double[]{a + rv + .08, y + split, z1 + .04}, Material.MACHINE, false);
        int studs = Math.max(3, (int) Math.round(lc / .22));
        for (int side = -1; side <= 1; side += 2)
            for (int k = 0; k < studs; k++) {
                double x = a + side * (rv + .03), z = z0 + lc * (k + .5) / studs;
                box(new double[]{x - .025, y + split, z - .025}, new double[]{x + .025, y + split + .06, z + .025}, Material.BOLT, false);
            }
        for (double z : new double[]{zc - .3 * lc, zc + .3 * lc}) box(new double[]{a - .03, y + rc - .05, z - .08}, new double[]{a + .03, y + rc + .12, z + .08}, Material.MACHINE, false);
        // Cast branches blend into the sized equipment necks; only the connection face carries a flange.
        double root=rv+.05;
        for (int side = -1; side <= 1; side += 2) {
            double bore = side < 0 ? s : d;
            var branch = Frame.of(new double[]{a, yn, zc}, 0, side);
            int nn = sides(bore + .1);
            mesh.frustum(branch, .6 * bore + .06, .5 * bore + .04, .5 * lx, root+.01, nn, Material.MACHINE);
            mesh.annulus(branch,0,.5*bore+.04,root+.01,nn,1,Material.MACHINE);
            double inner = a + side * .5 * lx, outer = a + side * (root+.01);
            solids.add(Solid.rod(new double[]{0, yn, zc}, 0, Math.min(inner, outer), Math.max(inner, outer), .6 * bore + .06, 0));
        }
        // The brackets, seal chambers and finned bearing housings at both ends; the shaft through them.
        bracket(f, rb + .03, .45 * di, zf1, z0, n);
        bracket(f, .45 * di, rb + .03, z1, zb0, n);
        mesh.cylinder(f, .22 * di, z0 - .55 * lk, z0, n, Material.MACHINE);
        mesh.cylinder(f, .22 * di, z1, z1 + .55 * lk, n, Material.MACHINE);
        shaft(f, shaft, new double[][]{{zf1, z0 - .55 * lk}, {z1 + .55 * lk, zb0}}, z0 - .55 * lk - collar(shaft) / 2, z1 + .55 * lk + collar(shaft) / 2);
        for (int e = 0; e < 2; e++) {
            double h0 = e == 0 ? zf0 : zb0, h1 = e == 0 ? zf1 : zb1;
            mesh.prism(f, rb, h0, h1, nb, Material.MACHINE);
            mesh.annulus(f, 0, rb, e == 0 ? zf0 : zb1, nb, e == 0 ? -1 : 1, Material.MACHINE);
            for (int k = 1; k <= 3; k++) {
                double z = h0 + (h1 - h0) * k / 4;
                mesh.ring(f, rb, rb + .025, z - .01, z + .01, nb, 3, Material.MACHINE);
            }
            mesh.cylinder(Frame.of(new double[]{a, 0, (h0 + h1) / 2}, 1, 1), .025 + .01 * di, y + rb - .01, y + rb + .05, 8, Material.PIPE);
        }
        // The coupling guard, a half-round hood over the spacer, and the motor.
        var hood = new ArrayList<double[]>();
        hood.add(new double[]{a - guard, top});
        hood.add(new double[]{a + guard, top});
        for (int k = 0; k <= 6; k++)
            hood.add(new double[]{a + guard * DeterministicMath.cos(Math.PI * k / 6), y + guard * DeterministicMath.sin(Math.PI * k / 6)});
        extrude(hood, zb1 + .04, zm0 - .03, Material.GUARD);
        mesh.prism(f, shaft, zb1 + .03, zm0, 8, Material.PIPE);
        solids.add(Solid.box(new double[]{a - guard, top, zb1 + .04}, new double[]{a + guard, y + guard, zm0 - .03}));
        largeMotor(a, y, zm0, across, lm, top, false);
        // The baseplate on its grout line and chamfered plinth.
        double zp0 = zf0 - .15, zp1 = zm1 + .1, base = deck + .12, chamfer = .04;
        box(new double[]{a - deck, plinth + .04, zp0}, new double[]{a + deck, top, zp1}, Material.STEELWORK, true);
        box(new double[]{a - deck + .02, plinth, zp0 + .02}, new double[]{a + deck - .02, plinth + .04, zp1 - .02}, Material.CONCRETE, false);
        extrude(List.of(new double[]{a - base, 0}, new double[]{a + base, 0}, new double[]{a + base, plinth - chamfer}, new double[]{a + base - chamfer, plinth},
                new double[]{a - base + chamfer, plinth}, new double[]{a - base, plinth - chamfer}), zp0 - .12, zp1 + .12, Material.CONCRETE);
        solids.add(Solid.box(new double[]{a - base, 0, zp0 - .12}, new double[]{a + base, plinth + .04, zp1 + .12}));
        solids.add(Solid.rod(new double[]{a, y, 0}, 2, z0, z1, rv, 0));
        solids.add(Solid.rod(new double[]{a, y, 0}, 2, zf0, zb1, Math.max(rb + .03, .45 * di), 0));
        volumes.add(Solid.rod(new double[]{a, y, 0}, 2, z0 + .05, z1 - .05, rc - .05, 0));
        volumes.add(Solid.box(new double[]{a - lx + .05, low + .05, z0 + .05}, new double[]{a + lx - .05, y, z1 - .05}));
        round(a, y, zc - wv / 2, zc + wv / 2, rv);
        // Preserve both connection planes while giving their necks clear space behind the flange.
        double outerS=a-reachS-ts+.02,outerD=a+reachD+td-.02;
        double faceS=Math.floor(outerS-.03-EquipmentShape.STAND_OUT),faceD=Math.ceil(outerD+.03+EquipmentShape.STAND_OUT);
        double bs=a-root,bd=a+root;
        nozzles.add(new Nozzle(Role.SUCTION, new double[]{bs, yn, zc}, 0, -1, bs-faceS, s, 0));
        nozzles.add(new Nozzle(Role.DISCHARGE, new double[]{bd, yn, zc}, 0, 1, faceD-bd, d, 0));
    }

    /**
     * A pump's volute round its shaft (along z at (x, y)) from {@code z0} to {@code z1}: a scroll from just past the top
     * (the cutwater) round to the throat at the top, its outer wall growing from {@code r0} to {@code r1} over a hub of
     * radius {@code rh} whose front is recessed to {@code zh}; its section a rectangle with chamfered outer corners. The
     * outer wall is lit as round, the rest flat; a chamfer, twisted between stations, is drawn as two triangles.
     */
    void scroll(double x, double y, double z0, double z1, double zh, double rh, double r0, double r1) {
        int steps = 20;
        double t0 = .45, t1 = 2 * Math.PI - .1, c = .3 * (r0 - rh), zm = (z0 + z1) / 2;
        var round = new Radial(new double[]{x, y, 0}, new double[]{0, 0, 1});
        double[][][] sections = new double[steps + 1][][];
        for (int i = 0; i <= steps; i++) {
            double t = t0 + (t1 - t0) * i / steps, r = r0 + (r1 - r0) * i / steps, sx = DeterministicMath.sin(t), cy = DeterministicMath.cos(t);
            double[][] rz = {{rh, z0}, {r - c, z0}, {r, z0 + c}, {r, z1 - c}, {r - c, z1}, {rh, z1}, {rh, zh}};
            sections[i] = new double[rz.length][];
            for (int k = 0; k < rz.length; k++) sections[i][k] = new double[]{x + rz[k][0] * sx, y + rz[k][0] * cy, rz[k][1]};
        }
        for (int i = 0; i < steps; i++) {
            double[][] p = sections[i], q = sections[i + 1];
            double t = t0 + (t1 - t0) * (i + .5) / steps, r = r0 + (r1 - r0) * (i + .5) / steps, mid = (rh + r) / 2;
            var inside = new double[]{x + mid * DeterministicMath.sin(t), y + mid * DeterministicMath.cos(t), zm};
            mesh.flat(List.of(p[0], p[1], q[1], q[0]), new double[]{0, 0, -1}, Material.MACHINE);
            mesh.flat(List.of(p[4], p[5], q[5], q[4]), new double[]{0, 0, 1}, Material.MACHINE);
            mesh.add(List.of(p[1], p[2], q[2]), inside, FLAT, Material.MACHINE, null, null);
            mesh.add(List.of(p[1], q[2], q[1]), inside, FLAT, Material.MACHINE, null, null);
            mesh.add(List.of(p[2], p[3], q[3], q[2]), inside, round, Material.MACHINE, null, null);
            mesh.add(List.of(p[3], p[4], q[4]), inside, FLAT, Material.MACHINE, null, null);
            mesh.add(List.of(p[3], q[4], q[3]), inside, FLAT, Material.MACHINE, null, null);
            // The inner wall shows only in front of the hub.
            mesh.add(List.of(p[0], p[6], q[6], q[0]), new double[]{x + (rh + .1) * DeterministicMath.sin(t), y + (rh + .1) * DeterministicMath.cos(t), (z0 + zh) / 2}, FLAT,
                    Material.MACHINE, null, null);
        }
        for (int end = 0; end < 2; end++) {
            double t = end == 0 ? t0 : t1;
            var cap = new ArrayList<double[]>();
            for (int k = 0; k < 6; k++) cap.add(sections[end == 0 ? 0 : steps][k]);
            double sign = end == 0 ? -1 : 1;
            mesh.flat(cap, new double[]{sign * DeterministicMath.cos(t), -sign * DeterministicMath.sin(t), 0}, Material.MACHINE);
        }
    }

    /**
     * A pump's bearing bracket on frame {@code f} (its shaft) from {@code z0} to {@code z1}, narrowing from {@code r0} to
     * {@code r1}: a cast shell 0.025 m thick, open in a window each side (frame ±x) over the middle 60 % of its length and
     * 100° of its round, drawn inside and out so the seal shows through.
     */
    void bracket(Frame f, double r0, double r1, double z0, double z1, int n) {
        double t = .025, w0 = z0 + .2 * (z1 - z0), w1 = z0 + .8 * (z1 - z0), window = Math.toRadians(50);
        double[] stations = {z0, w0, w1, z1};
        for (int band = 0; band < 3; band++) {
            double za = stations[band], zb = stations[band + 1], ra = r0 + (r1 - r0) * (za - z0) / (z1 - z0), rb = r0 + (r1 - r0) * (zb - z0) / (z1 - z0);
            for (int i = 0; i < n; i++) {
                double a0 = 2 * Math.PI * i / n, a1 = 2 * Math.PI * (i + 1) / n, mid = (a0 + a1) / 2;
                boolean open = band == 1 && (apart(mid, 0) < window || apart(mid, Math.PI) < window);
                if (open) continue;
                var axis = f.along((za + zb) / 2);
                mesh.add(List.of(f.at(a0, ra, za), f.at(a1, ra, za), f.at(a1, rb, zb), f.at(a0, rb, zb)), axis, FLAT, Material.MACHINE, null, null);
                mesh.add(List.of(f.at(a0, ra - t, za), f.at(a1, ra - t, za), f.at(a1, rb - t, zb), f.at(a0, rb - t, zb)), f.at(mid, 2 * ra, (za + zb) / 2), FLAT, Material.MACHINE,
                        null, null);
                if (band == 1) {
                    // A window's jambs at its edges.
                    for (double edge : new double[]{a0, a1}) {
                        boolean beside = apart(edge == a0 ? a0 - Math.PI / n : a1 + Math.PI / n, 0) < window || apart(edge == a0 ? a0 - Math.PI / n : a1 + Math.PI / n, Math.PI) < window;
                        if (beside)
                            mesh.add(List.of(f.at(edge, ra - t, za), f.at(edge, ra, za), f.at(edge, rb, zb), f.at(edge, rb - t, zb)), f.at(mid, (ra + rb) / 2 - t / 2, (za + zb) / 2),
                                    FLAT, Material.MACHINE, null, null);
                    }
                }
            }
            if (band == 1)
                for (int i = 0; i < n; i++) {
                    double a0 = 2 * Math.PI * i / n, a1 = 2 * Math.PI * (i + 1) / n, mid = (a0 + a1) / 2;
                    if (!(apart(mid, 0) < window || apart(mid, Math.PI) < window)) continue;
                    // The window's sills: the ends of the bands either side.
                    mesh.flat(List.of(f.at(a0, ra - t, za), f.at(a1, ra - t, za), f.at(a1, ra, za), f.at(a0, ra, za)), f.axis(), Material.MACHINE);
                    mesh.flat(List.of(f.at(a0, rb - t, zb), f.at(a1, rb - t, zb), f.at(a1, rb, zb), f.at(a0, rb, zb)), neg(f.axis()), Material.MACHINE);
                }
        }
        mesh.annulus(f, r0 - t, r0, z0, n, -1, Material.MACHINE);
    }

    /** The smaller angle between two directions, radians. */
    static double apart(double a, double b) {
        double d = Math.abs(a - b) % (2 * Math.PI);
        return Math.min(d, 2 * Math.PI - d);
    }

    /** How much of a TEFC motor ({@link #motor(Frame, double, double, double, double, double, MotorDetail)}) is drawn. */
    enum MotorDetail {
        /** 16 to 32 cooling fins, a cowl over a turning fan behind its grille, the bearing cap, the terminal box's conduit, lifting eyes and feet: a machine seen close. */
        FULL,
        /** Twelve fins and a plain cowl closed by its guard: one of a boiler's fans (3 000 quads fewer on three). */
        FINNED,
        /** Its body, end shield and cowl only: a small drive among large parts. */
        PLAIN
    }

    /**
     * A totally enclosed fan-cooled motor (NEMA MG 1 / IEC 60034 frames; its diameter from its power, {@link #motorDiameter})
     * on frame {@code f}, its shaft along the frame's axis through its origin, of body radius {@code r} from {@code z0} (the
     * drive end) to {@code z1}, its fan cowl over the last 18 %; drawn to {@code detail}. Lying (the frame's axis
     * horizontal, along a box axis or not) it carries its terminal box on top and stands on its feet and a pedestal down to
     * {@code floor}, its shaft's end {@code shaft} round at the drive end; standing (the axis vertical: an agitator's or a
     * fan's drive on its gearbox) it sits on its mounting flange at {@code z0}, its fins all round and its terminal box on
     * the front (frame -z).
     */
    void motor(Frame f, double r, double z0, double z1, double shaft, double floor, MotorDetail detail) {
        double fin = detail == MotorDetail.PLAIN ? .03 : .025 + .06 * r;
        motor(f, r, fin, z0, z1 - .18 * (z1 - z0), z1, sides(2 * r), shaft, floor, detail);
    }

    /**
     * A {@link #motor(Frame, double, double, double, double, double, MotorDetail)} with its axial fins {@code fin} high,
     * its fan cowl from {@code cowl} to {@code z1} and {@code n} sides (a pump's motor, proportioned to its pump).
     */
    private void motor(Frame f, double r, double fin, double z0, double cowl, double z1, int n, double shaft, double floor, MotorDetail detail) {
        double[] o = f.origin().clone(), ax = f.axis(), ref = f.ref(), side = f.side();
        boolean standing = Math.abs(ax[1]) > .5, full = detail == MotorDetail.FULL;
        // The motor's top (up, or the front when standing) and its right across it; the fins' angles start at the top.
        double[] top = standing ? new double[]{0, 0, -1} : new double[]{0, 1, 0}, across = cross(top, ax);
        double up = DeterministicMath.atan2(dot(top, side), dot(top, ref)), rc = r + fin, body = standing ? z0 + .04 : z0;
        if (standing) mesh.cylinder(f, rc + .03, z0, body, n, Material.MOTOR);
        mesh.prism(f, r, body, cowl, n, Material.MOTOR);
        if (!standing) {
            mesh.annulus(f, 0, r, z0, n, -1, Material.MOTOR);
            if (full) mesh.cylinder(f, .35 * r, z0 - .04, z0, n, Material.MOTOR);
            if (shaft > 0) mesh.prism(f, shaft, z0 - .1, full ? z0 - .04 : z0, 8, Material.PIPE);
        }
        if (detail != MotorDetail.PLAIN) {
            // Axial fins round the body, none on a lying motor's underside (its feet stand there).
            int fins = full ? Math.max(16, Math.min(32, (int) Math.round(2 * Math.PI * r / .08))) : 12;
            double skip = standing ? -1 : full ? Math.toRadians(50) : .9;
            for (int k = 0; k < fins; k++) {
                double angle = up + 2 * Math.PI * k / fins;
                if (apart(angle, up - Math.PI) < skip) continue;
                double c = DeterministicMath.cos(angle), s = DeterministicMath.sin(angle);
                double[] radial = {c * ref[0] + s * side[0], c * ref[1] + s * side[1], c * ref[2] + s * side[2]};
                mesh.block(f.at(angle, r + fin / 2 - .01, (body + .03 + cowl - .02) / 2), scale(radial, fin / 2), scale(ax, (cowl - .05 - body) / 2), scale(cross(ax, radial), .01),
                        Material.MOTOR);
            }
        }
        mesh.annulus(f, r, rc, cowl, n, -1, Material.MOTOR);
        if (full) {
            mesh.frustum(f, rc, .92 * rc, cowl, z1, n, Material.MOTOR);
            mesh.annulus(f, .8 * rc, .92 * rc, z1, n, 1, Material.MOTOR);
            grilledFan(f, .8 * rc, z1, Math.max(.06, Math.min(.15, .6 * (z1 - cowl))), n, PUMP_SPEED);
        } else if (detail == MotorDetail.FINNED) {
            mesh.prism(f, rc, cowl, z1, n, Material.MOTOR);
            mesh.annulus(f, 0, rc, z1, n, 1, Material.GUARD);
        } else {
            mesh.frustum(f, rc, .9 * r, cowl, z1, n, Material.MOTOR);
            mesh.annulus(f, 0, .9 * r, z1, n, 1, Material.MOTOR);
        }
        double terminal = z0 + .3 * (z1 - z0), lo = Math.min(o[1] + ax[1] * z0, o[1] + ax[1] * z1), hi = Math.max(o[1] + ax[1] * z0, o[1] + ax[1] * z1);
        if (standing) {
            // The terminal box on the front, the solid round the body and its flange.
            double t0 = o[1] + ax[1] * terminal, t1 = o[1] + ax[1] * (terminal + .55 * r);
            box(new double[]{o[0] - .35 * r, Math.min(t0, t1), o[2] - r - .45 * r}, new double[]{o[0] + .35 * r, Math.max(t0, t1), o[2] - r + .02}, Material.MOTOR, false);
            solids.add(Solid.rod(o, 1, lo, hi, rc + .03, 0));
            round(o, 1, lo, hi, rc);
            return;
        }
        double y = o[1];
        motorBox(f, across, terminal, terminal + .55 * r, y + r - .02, y + 1.45 * r, -.35 * r, .35 * r, Material.MOTOR, false);
        if (full) {
            double[] conduit = {o[0] + (terminal + .27 * r) * ax[0], y + 1.2 * r, o[2] + (terminal + .27 * r) * ax[2]};
            mesh.cylinder(new Frame(conduit, across, top), .06 * r + .01, .35 * r, .35 * r + .05, 8, Material.MOTOR);
            for (double z : new double[]{z0 + .12 * (z1 - z0), cowl - .1 * (z1 - z0)})
                mesh.ring(new Frame(new double[]{o[0], y + rc + .02 + .02 * r, o[2]}, ax, ref), .02 + .02 * r, .04 + .03 * r, z - .01, z + .01, 8, 3, Material.MOTOR);
            for (int s = -1; s <= 1; s += 2)
                for (double z : new double[]{z0 + .08, cowl - .25})
                    motorBox(f, across, z, z + .17, y - r - .02, y - .55 * r, Math.min(s * .5 * r, s * .9 * r), Math.max(s * .5 * r, s * .9 * r), Material.MOTOR, false);
        }
        double feet = full ? y - r - .02 : y - .6 * r, half = full ? .95 * r : .85 * r, inset = full ? .02 : .05;
        if (feet > floor + .01) motorBox(f, across, z0 + inset, cowl - inset, floor, feet, -half, half, Material.STEELWORK, true);
        int i = Math.abs(ax[0]) == 1 ? 0 : Math.abs(ax[2]) == 1 ? 2 : -1;
        if (i < 0) {
            // A motor lying off the box axes collides as the box round its body, cowl and terminal box.
            double[] min = {Double.MAX_VALUE, y - rc, Double.MAX_VALUE}, max = {-Double.MAX_VALUE, y + 1.45 * r, -Double.MAX_VALUE};
            for (double z : new double[]{z0, z1})
                for (double w : new double[]{-rc, rc})
                    for (int k = 0; k < 3; k += 2) {
                        double c = o[k] + z * ax[k] + w * across[k];
                        min[k] = Math.min(min[k], c);
                        max[k] = Math.max(max[k], c);
                    }
            solids.add(Solid.box(min, max));
            return;
        }
        double a0 = o[i] + ax[i] * z0, a1 = o[i] + ax[i] * z1;
        solids.add(Solid.rod(o, i, Math.min(a0, a1), Math.max(a0, a1), rc, 0));
        round(o, i, Math.min(a0, a1), Math.max(a0, a1), r);
    }

    /**
     * A box on a lying motor's frame {@code f}: {@code a0} to {@code a1} along its axis, heights {@code y0} to {@code y1},
     * {@code w0} to {@code w1} along {@code across}; drawn as an axis-aligned box where the frame lies along a box axis,
     * else turned with it; {@code solid}: the box round it collides.
     */
    private void motorBox(Frame f, double[] across, double a0, double a1, double y0, double y1, double w0, double w1, Material material, boolean solid) {
        double[] o = f.origin(), ax = f.axis();
        double[] p = {o[0] + a0 * ax[0] + w0 * across[0], y0, o[2] + a0 * ax[2] + w0 * across[2]}, q = {o[0] + a1 * ax[0] + w1 * across[0], y1, o[2] + a1 * ax[2] + w1 * across[2]};
        if (Math.abs(ax[0]) == 1 || Math.abs(ax[2]) == 1) {
            box(new double[]{Math.min(p[0], q[0]), y0, Math.min(p[2], q[2])}, new double[]{Math.max(p[0], q[0]), y1, Math.max(p[2], q[2])}, material, solid);
            return;
        }
        double am = (a0 + a1) / 2, wm = (w0 + w1) / 2;
        mesh.block(new double[]{o[0] + am * ax[0] + wm * across[0], (y0 + y1) / 2, o[2] + am * ax[2] + wm * across[2]}, scale(ax, (a1 - a0) / 2), new double[]{0, (y1 - y0) / 2, 0},
                scale(across, (w1 - w0) / 2), material);
        if (!solid) return;
        double[] min = {Double.MAX_VALUE, y0, Double.MAX_VALUE}, max = {-Double.MAX_VALUE, y1, -Double.MAX_VALUE};
        for (double a : new double[]{a0, a1})
            for (double w : new double[]{w0, w1})
                for (int k = 0; k < 3; k += 2) {
                    double c = o[k] + a * ax[k] + w * across[k];
                    min[k] = Math.min(min[k], c);
                    max[k] = Math.max(max[k], c);
                }
        solids.add(Solid.box(min, max));
    }

    /** A line of radius {@code r} through {@code points}, a ball at each bend (a seal's flush tube, a heater's insulated crossover); legs of no length are skipped. */
    void line(double r, Material material, double[]... points) {
        line(r, r < .05 ? 6 : 12, material, points);
    }

    /** A {@link #line(double, Material, double[]...)} of {@code sides} sides: a drawn line in any direction that does not collide. */
    void line(double r, int sides, Material material, double[]... points) {
        for (int k = 0; k + 1 < points.length; k++) {
            var along = sub(points[k + 1], points[k]);
            double length = Math.sqrt(dot(along, along));
            if (length < 1e-6) continue;
            var dir = unit(along);
            var ref = Math.abs(dir[1]) < .9 ? new double[]{0, 1, 0} : new double[]{1, 0, 0};
            mesh.prism(new Frame(points[k], dir, unit(cross(dir, ref))), r, 0, length, sides, material);
            if (k > 0) mesh.sphere(points[k], r * 1.05, sides, material);
        }
    }

    /** A {@link #pipe(double, int, Material, boolean, double[]...)} with a pipe's facets ({@link PipeSizes#sides}). */
    void pipe(double r, Material material, boolean solid, double[]... points) {
        pipe(r, PipeSizes.sides(2 * r), material, solid, points);
    }

    /**
     * A pipe of radius {@code r} and {@code sides} facets through {@code points}, its legs along the frame's axes with
     * square turns: its legs ({@link #prism(Frame, double, double, double, int, Material)}) and at each turn a long-radius
     * elbow (ASME B16.9: a bend radius of 1.5 nominal sizes, here one outside diameter and 0.15 m at least, half the
     * shorter leg at most), mitred ({@link Mesh#bend}: three turns over 0.2 m, two under), each leg running on to the
     * elbow's first mitre. A leg under 1 cm has no room for an elbow beside it and is left out, as is a point the pipe
     * runs straight through ({@link #legs}). Its legs collide as drawn where {@code solid}, each overlapping the turns at
     * its ends by r (a hose inside a derrick, a moving part, does not collide).
     */
    void pipe(double r, int sides, Material material, boolean solid, double[]... points) {
        var p = legs(points);
        double od = 2 * r, bend = Math.max(.15, od);
        int m = p.length, segments = od > .2 ? 3 : 2;
        double tan = DeterministicMath.sin(Math.PI / 4 / segments) / DeterministicMath.cos(Math.PI / 4 / segments);
        double[] cut = new double[m], reach = new double[m];
        for (int k = 1; k + 1 < m; k++) {
            cut[k] = Math.min(bend, .5 * Math.min(Math.sqrt(dot(sub(p[k], p[k - 1]), sub(p[k], p[k - 1]))), Math.sqrt(dot(sub(p[k + 1], p[k]), sub(p[k + 1], p[k])))));
            reach[k] = (cut[k] + r) * tan;
        }
        for (int k = 0; k + 1 < m; k++) {
            double[] a = p[k], b = p[k + 1], along = sub(b, a);
            double length = Math.sqrt(dot(along, along));
            var dir = scale(along, 1 / length);
            int axis = Math.abs(dir[0]) > .5 ? 0 : Math.abs(dir[1]) > .5 ? 1 : 2;
            var ref = new double[3];
            ref[(axis + 1) % 3] = 1;
            prism(new Frame(a, dir, ref), r, cut[k] - reach[k], length - cut[k + 1] + reach[k + 1], sides, material);
            if (!solid) continue;
            var origin = a.clone();
            origin[axis] = 0;
            double sign = Math.signum(dir[axis]), s0 = a[axis] - sign * (k == 0 ? 0 : r), s1 = b[axis] + sign * (k + 2 == m ? 0 : r);
            solids.add(Solid.rod(origin, axis, Math.min(s0, s1), Math.max(s0, s1), r, 0));
        }
        for (int k = 1; k + 1 < m; k++) {
            var d0 = unit(sub(p[k], p[k - 1]));
            var d1 = unit(sub(p[k + 1], p[k]));
            mesh.bend(new Frame(plus(p[k], plus(scale(d0, -cut[k]), scale(d1, cut[k]))), cross(d0, d1), neg(d1)), cut[k], r, 0, Math.PI / 2, segments, sides, false, material);
        }
    }

    /**
     * A pipe's points less those 1 cm or less from the last one kept (the run's end stays where it is) and those it runs
     * straight through: every leg left has room for an elbow at each of its ends.
     */
    private static double[][] legs(double[]... points) {
        var out = new ArrayList<double[]>();
        for (int k = 0; k < points.length; k++) {
            var q = points[k];
            if (!out.isEmpty() && Math.sqrt(dot(sub(q, out.getLast()), sub(q, out.getLast()))) <= .01) {
                if (k + 1 == points.length && out.size() > 1) out.set(out.size() - 1, q);
                continue;
            }
            if (out.size() > 1 && dot(unit(sub(out.getLast(), out.get(out.size() - 2))), unit(sub(q, out.getLast()))) > 1 - 1e-6) out.removeLast();
            out.add(q);
        }
        return out.toArray(double[][]::new);
    }

    /**
     * A 90° elbow of bend radius {@code bend} and radius {@code r} from {@code start}, heading {@code d0} there and
     * {@code d1} at its end (unit vectors at right angles), turning {@code segments} times ({@link Mesh#bend}), its ends
     * square to d0 and d1: returns its end.
     */
    double[] elbow(double[] start, double[] d0, double[] d1, double bend, double r, int n, int segments, Material material) {
        var centre = plus(start, scale(d1, bend));
        mesh.bend(new Frame(centre, cross(d0, d1), neg(d1)), bend, r, 0, Math.PI / 2, segments, n, true, material);
        return plus(centre, scale(d0, bend));
    }

    /**
     * A prism along a frame axis parallel to a box axis ({@link Mesh#prism}), cut once where its texture's tiles along it
     * meet a whole multiple of the material's period: a prism's tiles start at its own end, so a long body starting off a
     * cell face is cut once more in every cell it crosses; from the cut on its tiles fall on cell faces.
     */
    void prism(Frame f, double r, double z0, double z1, int n, Material material) {
        double[] axis = f.axis(), o = f.origin();
        int a = Math.abs(axis[0]) > .5 ? 0 : Math.abs(axis[1]) > .5 ? 1 : 2;
        double s = axis[a], period = material.period(), lo = Math.min(z0, z1), hi = Math.max(z0, z1), w = o[a] + lo * s;
        double aligned = s > 0 ? Math.ceil(w / period - 1e-9) * period : Math.floor(w / period + 1e-9) * period, cut = (aligned - o[a]) * s;
        if (hi - lo > period && cut > lo + 1e-6 && cut < hi - 1e-6) {
            mesh.prism(f, r, lo, cut, n, material);
            mesh.prism(f, r, cut, hi, n, material);
        } else mesh.prism(f, r, lo, hi, n, material);
    }

    /** A prism along z from {@code z0} to {@code z1} whose section is the convex polygon {@code outline} of (x, y) points, both ends drawn. */
    void extrude(List<double[]> outline, double z0, double z1, Material material) {
        int m = outline.size();
        double cx = 0, cy = 0;
        for (var p : outline) {
            cx += p[0] / m;
            cy += p[1] / m;
        }
        var front = new ArrayList<double[]>();
        var back = new ArrayList<double[]>();
        for (int i = 0; i < m; i++) {
            double[] p = outline.get(i), q = outline.get((i + 1) % m);
            mesh.add(List.of(new double[]{p[0], p[1], z0}, new double[]{q[0], q[1], z0}, new double[]{q[0], q[1], z1}, new double[]{p[0], p[1], z1}),
                    new double[]{cx, cy, (z0 + z1) / 2}, FLAT, material, null, null);
            front.add(new double[]{p[0], p[1], z0});
            back.add(new double[]{p[0], p[1], z1});
        }
        mesh.flat(front, new double[]{0, 0, -1}, material);
        mesh.flat(back, new double[]{0, 0, 1}, material);
    }

    // ---- Reciprocating compressor -----------------------------------------------------------------------------------

    /**
     * An API 618 balanced-opposed compressor (owner decision M4 2026-10-17; {@code MACHINES_HEATERS_SURVEY.md}), its
     * crankshaft along z on a steel table {@code support} high (owner request 2026-10-17: steel beams like the other
     * units). The frame, ribbed, a bolted cover over each throw, a breather, the oil pump's cover at its free end and the
     * lubricator on its side; on alternate sides of it, each throw's crosshead guide (a bolted door on top), its distance
     * piece (flanged at both ends, a window cover each side of each compartment, two compartments when long) and its
     * double-acting cylinder: a heavy flange at its crank end, valve caps at both ends on top (suction) and underneath
     * (discharge), one at each end of a small cylinder and two of a large (0.45 m bore up) beside its nozzles, a bolted head
     * and from 0.3 m bore a clearance pocket with its handwheel, an outboard support under the head. On each side a
     * suction bottle lies over the cylinders and a discharge bottle under them on saddled posts, both as long as the longer
     * side's, each joined to every cylinder by a flanged neck. One suction header crosses the machine over its outboard
     * (front) end and drops into both suction bottles, the inlet on top of its middle; one discharge header joins both discharge bottles'
     * front heads in front of the frame, the outlet at the front of its middle (owner request 2026-10-17: one inlet, one
     * outlet). The synchronous motor drives the crankshaft's back end through a guarded coupling ({@link #syncMotor}).
     * Sizes from {@link Compressor}.
     */
    void compressor() {
        var c = Compressor.of(shape.diameter(), (int) shape.tangentLength());
        int throwCount = c.throwCount(), h = shape.support();
        double b = c.bore(), yc = h + c.crank(), ds = c.suction(), dd = c.discharge(), cyl = c.cylinder(), lc = c.cylinderLength(), gh = c.guide(), piece = c.piece();
        double ysb = yc + cyl / 2 + c.gap() + ds / 2, ydb = c.dischargeAxis(h), pocket = b >= .3 ? .3 + .3 * b : 0;
        double hs = c.suctionHeader(), hd = c.dischargeHeader();
        double a = centre(c.out() + Math.max(lc / 2 + .12 + pocket + (pocket > 0 ? .12 : 0), Math.max(ds, dd) / 2) + .05);
        // Along z from the frame's front: an auxiliary end of 0.6 m, the throws at the crank pitch, 0.6 m to the drive end.
        // The bottles span the cylinders of either side and two diameters at least.
        var zs = new double[throwCount];
        for (int k = 0; k < throwCount; k++) zs[k] = .6 + (k + .5) * c.pitch();
        double end = throwCount * c.pitch() + 1.2;
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (int side = 0; side < 2; side++) {
            lo = Math.min(lo, zs[side] - cyl / 2 - .1);
            hi = Math.max(hi, zs[throwCount - 2 + side] + cyl / 2 + .1);
        }
        double grow = Math.max(0, 2 * ds - (hi - lo)) / 2;
        lo -= grow;
        hi += grow;
        // The discharge header on a cell centre in front of the bottles' heads and the table; everything shifted by whole
        // metres (cell centres stay cell centres) so the outlet's flange starts at z 0.
        double depth = dd / 4, zd = Math.floor(Math.min(lo - depth - .1, -.25) - hd / 2 - .5) + .5;
        double shift = -Math.floor(zd - hd / 2 - NECK);
        for (int k = 0; k < throwCount; k++) zs[k] += shift;
        lo += shift;
        hi += shift;
        zd += shift;
        double front = shift;
        end += shift;
        // The steel table: posts at the frame's corners and between the throws, beams along its sides and across at each
        // post line; the frame on it, its ribs between the throws, a cover over each throw, the breather, the oil pump's
        // cover and the pump at the free end, the lubricator on its side.
        double half = c.half(), top = h + c.frameTop();
        var lines = new ArrayList<Double>();
        lines.add(front + .1);
        for (int k = 1; k < throwCount; k += 2) lines.add((zs[k] + (k + 1 < throwCount ? zs[k + 1] : end - .1)) / 2);
        if (lines.getLast() < end - .4) lines.add(end - .1);
        steelTable(a, half, front - .1, end + .1, h, lines, c.rail());
        box(new double[]{a - half, h, front}, new double[]{a + half, top, end}, Material.MACHINE, true);
        volumes.add(Solid.box(new double[]{a - half + .05, h + .05, front + .05}, new double[]{a + half - .05, top - .05, end - .05}));
        for (int k = 0; k < throwCount; k++) {
            box(new double[]{a - half + .08, top, zs[k] - .38 * c.pitch()}, new double[]{a + half - .08, top + .03, zs[k] + .38 * c.pitch()}, Material.MACHINE, false);
            mesh.bar(new double[]{a - .15, top + .05, zs[k]}, new double[]{a + .15, top + .05, zs[k]}, .03, new double[]{0, 1, 0}, Material.PIPE);
            // A rib on the side where this throw has no guide.
            double rx = a + (k % 2 == 0 ? 1 : -1) * half;
            box(new double[]{rx - .04, h + .05, zs[k] - .05}, new double[]{rx + .04, top - .05, zs[k] + .05}, Material.MACHINE, false);
        }
        mesh.cylinder(Frame.of(new double[]{a + .5 * half, 0, front + .3}, 1, 1), .06 + .06 * b, top, top + .15 + .1 * b, 10, Material.MACHINE);
        mesh.cylinder(Frame.of(new double[]{a, yc, 0}, 2, 1), .25 + .15 * b, front - .06, front, sides(.5 + .3 * b), Material.MACHINE);
        box(new double[]{a - .2, h + .15, front - .25}, new double[]{a + .2, h + .45, front}, Material.MACHINE, false);
        box(new double[]{a + half, yc - .1, front + .05}, new double[]{a + half + .3, yc + .3, front + .45}, Material.MACHINE, false);
        int n = sides(cyl), nb = sides(ds), nd = sides(dd), caps = b >= .45 ? 2 : 1;
        double neck = .25 * b + .04, cap = Math.min(.225, .1 + .2 * b), capHeight = .08 + .08 * b;
        for (int k = 0; k < throwCount; k++) {
            int sx = k % 2 == 0 ? -1 : 1;
            double z = zs[k], xb = a + sx * c.out(), g0 = a + sx * half, g1 = a + sx * (half + c.guideLength()), p1 = xb - sx * (lc / 2 + .06);
            // The crosshead guide and its door; the open distance piece between its flanges with the piston rod sliding in it.
            box(new double[]{Math.min(g0, g1), yc - gh, z - gh}, new double[]{Math.max(g0, g1), yc + gh, z + gh}, Material.MACHINE, true);
            double d0 = g0 + sx * .15, d1 = g1 - sx * .15;
            box(new double[]{Math.min(d0, d1), yc + gh, z - .7 * gh}, new double[]{Math.max(d0, d1), yc + gh + .025, z + .7 * gh}, Material.MACHINE, false);
            mesh.bar(new double[]{(d0 + d1) / 2 - .1, yc + gh + .06, z}, new double[]{(d0 + d1) / 2 + .1, yc + gh + .06, z}, .03, new double[]{0, 1, 0}, Material.PIPE);
            plate(g1 - sx * .05, g1, yc, z, piece + .06);
            // Balanced opposed: a pair's pistons move out together, the pairs' cranks spread over half a turn.
            distancePiece(g1, p1, sx, yc, z, piece, c.pieceLength() > 1.1 ? 2 : 1, b, c.stroke(), (k / 2) / (double) throwCount);
            // The cylinder: crank-end flange, body, head flange with its studs, head cover; the clearance pocket and its wheel.
            var fx = Frame.of(new double[]{xb, yc, z}, 0, sx);
            mesh.cylinder(fx, cyl / 2 + .05, -lc / 2 - .06, -lc / 2 + .02, n, Material.FLANGE);
            mesh.prism(fx, cyl / 2, -lc / 2 + .02, lc / 2, n, Material.MACHINE);
            mesh.cylinder(fx, cyl / 2 + .03, lc / 2, lc / 2 + .07, n, Material.FLANGE);
            mesh.bolts(fx, Math.min(cyl, PipeSizes.MAX_OUTSIDE), .35 * cyl, cyl / 2 + .03, lc / 2 + .07, 1, Material.BOLT);
            mesh.cylinder(fx, .42 * cyl, lc / 2 + .07, lc / 2 + .12, n, Material.MACHINE);
            if (pocket > 0) {
                double wheel = .12 + .1 * b, at = lc / 2 + .12 + pocket;
                mesh.cylinder(fx, .25 * cyl, lc / 2 + .12, at, n, Material.MACHINE);
                mesh.prism(fx, .02, at, at + .1, 6, Material.PIPE);
                mesh.ring(fx, wheel - .025, wheel, at + .07, at + .1, 12, 3, Material.ACTUATOR);
                mesh.bar(fx.at(0, wheel - .02, at + .085), fx.at(Math.PI, wheel - .02, at + .085), .02, new double[]{0, 0, 1}, Material.ACTUATOR);
            }
            solids.add(Solid.rod(new double[]{0, yc, z}, 0, Math.min(xb - sx * (lc / 2 + .06), xb + sx * (lc / 2 + .12 + pocket)),
                    Math.max(xb - sx * (lc / 2 + .06), xb + sx * (lc / 2 + .12 + pocket)), cyl / 2 + .05, 0));
            round(new double[]{0, yc, z}, 0, xb - lc / 2, xb + lc / 2, cyl / 2);
            volumes.add(Solid.rod(new double[]{0, yc, z}, 0, xb - lc / 2 + .05, xb + lc / 2 - .05, cyl / 2 - .05, 0));
            // Valve caps at both ends, on top (suction) and underneath (discharge); their nuts are too small to show.
            for (double t : new double[]{-(lc / 2 - cap - .08), lc / 2 - cap - .08})
                for (int below = 0; below < 2; below++)
                    for (int j = 0; j < caps; j++) {
                        double phi = (below == 0 ? 0 : Math.PI) + (caps == 1 ? 0 : (j == 0 ? -1 : 1) * Math.toRadians(35));
                        double[] radial = {0, DeterministicMath.cos(phi), DeterministicMath.sin(phi)};
                        var cf = new Frame(new double[]{xb + sx * t, yc, z}, radial, new double[]{1, 0, 0});
                        mesh.cylinder(cf, cap, cyl / 2 - .04, cyl / 2 + capHeight, 12, Material.FLANGE);
                    }
            // The necks to the bottles; the outboard support under the head.
            var up = Frame.of(new double[]{xb, 0, z}, 1, 1);
            mesh.prism(up, neck, yc + cyl / 2 - .05, ysb - .45 * ds, 12, Material.PIPE);
            mesh.ring(up, neck, 1.6 * neck, yc + cyl / 2 + .05, yc + cyl / 2 + .09, 12, 3, Material.FLANGE);
            mesh.prism(up, neck, ydb + .45 * dd, yc - cyl / 2 + .05, 12, Material.PIPE);
            mesh.ring(up, neck, 1.6 * neck, yc - cyl / 2 - .09, yc - cyl / 2 - .05, 12, 3, Material.FLANGE);
            double xs = xb + sx * (lc / 2 + .04);
            box(new double[]{xs - .04, .03, z - .2 * cyl}, new double[]{xs + .04, yc - .4 * cyl, z + .2 * cyl}, Material.STEELWORK, true);
            box(new double[]{xs - .12, 0, z - .2 * cyl - .06}, new double[]{xs + .12, .03, z + .2 * cyl + .06}, Material.STEELWORK, false);
            // A beam under the guide from the table's rail out to a post at the guide's end, a saddle up to the guide.
            double rail = Math.min(c.rail(), h), gx = g1 - sx * .2;
            box(new double[]{Math.min(g0, gx) - .05, h - rail, z - FLANGE_BEAM}, new double[]{Math.max(g0, gx) + .05, h, z + FLANGE_BEAM}, Material.STEELWORK, true);
            if (h - rail > .05) box(new double[]{gx - POST / 2, .03, z - POST / 2}, new double[]{gx + POST / 2, h - rail, z + POST / 2}, Material.STEELWORK, true);
            box(new double[]{Math.min(g0, gx) + .1, h, z - .6 * gh}, new double[]{Math.max(g0, gx) - .1, yc - gh, z + .6 * gh}, Material.STEELWORK, true);
        }
        for (int side = 0; side < 2; side++) {
            int sx = side == 0 ? -1 : 1;
            double xb = a + sx * c.out();
            bottle(xb, ysb, lo, hi, ds, nb);
            bottle(xb, ydb, lo, hi, dd, nd);
            // Posts with saddles under the discharge bottle, a quarter in from each end.
            for (double zp : new double[]{lo + .25 * (hi - lo), hi - .25 * (hi - lo)}) {
                box(new double[]{xb - .1, 0, zp - .1}, new double[]{xb + .1, ydb - .45 * dd, zp + .1}, Material.STEELWORK, true);
                box(new double[]{xb - .4 * dd, ydb - .45 * dd - .04, zp - .13}, new double[]{xb + .4 * dd, ydb - .3 * dd, zp + .13}, Material.STEELWORK, false);
            }
        }
        // The suction header across the outboard (front) end on a cell centre, over the frame, its branches down into both
        // suction bottles, the inlet on top of its middle; the discharge header from both discharge bottles' front heads,
        // the outlet at the front of its middle.
        double xl = a - c.out(), xr = a + c.out(), zh = Math.ceil(lo + .1 + hs / 2 - .5) + .5, yh = Math.ceil(ysb + ds / 2 + .2 + hs / 2 - .5) + .5, into = ysb + .3 * ds;
        header(hs, new double[]{xl, into, zh}, new double[]{xl, yh, zh}, new double[]{xr, yh, zh}, new double[]{xr, into, zh});
        nozzles.add(radial(Role.SUCTION, a, yh, hs / 2, sides(hs), zh, 1, ColumnStructure.size(hs, hs)));
        double head = lo - .7 * depth;
        header(hd, new double[]{xl, ydb, head}, new double[]{xl, ydb, zd}, new double[]{xr, ydb, zd}, new double[]{xr, ydb, head});
        double start = zd + .03, face = Math.floor(zd - hd / 2 - NECK);
        nozzles.add(new Nozzle(Role.DISCHARGE, new double[]{a, ydb, start}, 2, -1, start - face, ColumnStructure.size(hd, hd), 0));
        // The guarded coupling and the synchronous motor.
        double mz0 = end + .35, g = .15 + .15 * b;
        mesh.cylinder(Frame.of(new double[]{a, yc, 0}, 2, 1), .1 + .1 * b, end, mz0, 12, Material.MACHINE);
        box(new double[]{a - g, Math.max(h, yc - g), end + .03}, new double[]{a + g, yc + 1.2 * g, mz0 - .03}, Material.GUARD, true);
        largeMotor(a, yc, mz0, c.motor(), c.motorLength(), 0, true);
    }

    /**
     * A throw's open distance piece from the crosshead guide's flange at {@code g} to the cylinder's at {@code p} (outward
     * {@code sx} along x; its axis at (y, z), its half section {@code piece}) in {@code compartments} (owner request
     * 2026-10-18, "add Piston model to compressor", answered: the rods in open windows, since the pistons stay in the
     * cylinders): top and bottom plates, sides open in a window per compartment, a partition between compartments, the
     * rod's packing case on the cylinder's flange and its wiper case on the guide's. The piston rod, 0.05 + 0.12 bores
     * across, slides through it as a moving part ({@link Motion}) on a crank of half the {@code stroke} and a connecting rod
     * of five cranks from {@code phase}, its oil deflector collar in the compartment by the guide; it reaches half a stroke
     * and 0.1 m into the guide and the cylinder, so its ends never show.
     */
    void distancePiece(double g, double p, int sx, double y, double z, double piece, int compartments, double bore, double stroke, double phase) {
        double x0 = Math.min(g, p), x1 = Math.max(g, p), t = .03 + .02 * bore, length = (x1 - x0) / compartments, win = .55 * piece;
        solids.add(Solid.box(new double[]{x0, y - piece, z - piece}, new double[]{x1, y + piece, z + piece}));
        box(new double[]{x0, y + piece - t, z - piece}, new double[]{x1, y + piece, z + piece}, Material.MACHINE, false);
        box(new double[]{x0, y - piece, z - piece}, new double[]{x1, y - piece + t, z + piece}, Material.MACHINE, false);
        for (int face = -1; face <= 1; face += 2) {
            double za = face < 0 ? z - piece : z + piece - t, zb = za + t, from = x0;
            box(new double[]{x0, y - piece + t, za}, new double[]{x1, y - win, zb}, Material.MACHINE, false);
            box(new double[]{x0, y + win, za}, new double[]{x1, y + piece - t, zb}, Material.MACHINE, false);
            for (int j = 0; j < compartments; j++) {
                double mid = x0 + length * (j + .5);
                box(new double[]{from, y - win, za}, new double[]{mid - .3 * length, y + win, zb}, Material.MACHINE, false);
                from = mid + .3 * length;
            }
            box(new double[]{from, y - win, za}, new double[]{x1, y + win, zb}, Material.MACHINE, false);
        }
        for (int j = 1; j < compartments; j++) plate(x0 + length * j - .025, x0 + length * j + .025, y, z, piece - t);
        var f = Frame.of(new double[]{0, y, z}, 0, 1);
        double rod = .025 + .06 * bore, cased = p - sx * (.1 + .05 * bore), wiper = g + sx * .06;
        mesh.cylinder(f, 2.2 * rod, Math.min(p, cased), Math.max(p, cased), 12, Material.FLANGE);
        mesh.cylinder(f, 1.8 * rod, Math.min(g, wiper), Math.max(g, wiper), 12, Material.FLANGE);
        double reach = stroke / 2 + .1, collar = g + sx * length / 2, half = .02 + .02 * bore;
        mesh.part(motions.size());
        mesh.cylinder(f, rod, Math.min(g - sx * reach, p + sx * reach), Math.max(g - sx * reach, p + sx * reach), 10, Material.PIPE);
        mesh.cylinder(f, 1.9 * rod, collar - half, collar + half, 12, Material.FLANGE);
        mesh.part(-1);
        double crank = stroke / 2;
        motions.add(new Motion(new double[]{(g + p) / 2, y, z}, new double[]{sx, 0, 0}, CRANK_SPEED, phase, crank, 5 * crank));
    }

    /**
     * A shaft of radius {@code shaft} along frame {@code f} (its axis along z) over {@code spans} ({z0, z1} each), seen in
     * a pump's bracket windows, with a seal's drive collar and its four set screws centred at each of {@code collars}: a
     * moving part turning at {@link #PUMP_SPEED} (owner answer 2026-10-18: the shaft and the motor's fan turn). A round
     * shaft turning shows nothing; its screws show it.
     */
    void shaft(Frame f, double shaft, double[][] spans, double... collars) {
        mesh.part(motions.size());
        for (var s : spans) mesh.prism(f, shaft, s[0], s[1], 8, Material.PIPE);
        double rc = 1.7 * shaft + .01, w = .02 + shaft / 2;
        for (double zc : collars) {
            mesh.cylinder(f, rc, zc - w / 2, zc + w / 2, 12, Material.FLANGE);
            for (int k = 0; k < 4; k++) {
                var at = f.at(k * Math.PI / 2, rc + .008, zc);
                box(new double[]{at[0] - .012, at[1] - .012, at[2] - .012}, new double[]{at[0] + .012, at[1] + .012, at[2] + .012}, Material.BOLT, false);
            }
        }
        mesh.part(-1);
        motions.add(Motion.turning(f.along((spans[0][0] + spans[spans.length - 1][1]) / 2), f.axis(), PUMP_SPEED, 0));
    }

    /** The width of a {@link #shaft}'s seal collar, m. */
    static double collar(double shaft) {
        return .02 + shaft / 2;
    }

    /**
     * A cooling fan behind a grille in a cowl's end along frame {@code f} at {@code face} (facing along the frame's axis,
     * radius {@code r}): a dark bore {@code depth} deep and its back, six pitched blades on a hub turning in it at
     * {@code speed} (a moving part; the motor's shaft drives it), and over them a grille of eight spokes, two rings and a
     * hub cap.
     */
    void grilledFan(Frame f, double r, double face, double depth, int n, double speed) {
        mesh.bore(f, r, face - depth, face, n, Material.OPENING);
        mesh.annulus(f, 0, r, face - depth, n, 1, Material.OPENING);
        double hub = .2 * r, t = .01 + .01 * r, deep = face - depth + .01, shallow = face - 2 * t - .005, width = Math.min(.4 * r, 1.6 * (shallow - deep));
        mesh.part(motions.size());
        mesh.cylinder(f, hub, deep, shallow, 10, Material.MOTOR);
        double pitch = Math.PI / 6;
        for (int k = 0; k < 6; k++) {
            double angle = k * Math.PI / 3;
            var radial = sub(f.at(angle, 1, 0), f.at(angle, 0, 0));
            var across = plus(scale(cross(f.axis(), radial), DeterministicMath.cos(pitch)), scale(f.axis(), DeterministicMath.sin(pitch)));
            mesh.block(f.at(angle, (hub + .92 * r) / 2, (deep + shallow) / 2), scale(radial, (.92 * r - hub) / 2), scale(across, width / 2), scale(cross(radial, across), .006),
                    Material.MOTOR);
        }
        mesh.part(-1);
        motions.add(Motion.turning(f.along(face), f.axis(), speed, 0));
        for (int k = 0; k < 8; k++) mesh.bar(f.at(k * Math.PI / 4, .15 * r, face - t / 2), f.at(k * Math.PI / 4, r, face - t / 2), t, f.axis(), Material.MOTOR);
        for (double ring : new double[]{.45 * r, .75 * r}) mesh.ring(f, ring - t / 2, ring + t / 2, face - t, face, n, 3, Material.MOTOR);
        mesh.cylinder(f, .15 * r, face - t, face, 10, Material.MOTOR);
    }

    /**
     * A process header of {@code od} through {@code points} (a bend at each), with a flange pair where it leaves the first
     * and enters the last; it collides as drawn.
     */
    void header(double od, double[]... points) {
        double r = od / 2;
        line(r, Material.PIPE, points);
        for (int k = 0; k + 1 < points.length; k++) {
            double[] p = points[k], q = points[k + 1];
            int axis = p[0] != q[0] ? 0 : p[1] != q[1] ? 1 : 2;
            double[] origin = p.clone();
            origin[axis] = 0;
            solids.add(Solid.rod(origin, axis, Math.min(p[axis], q[axis]) - r, Math.max(p[axis], q[axis]) + r, r, 0));
        }
        for (int end = 0; end < 2; end++) {
            double[] p = end == 0 ? points[0] : points[points.length - 1], q = end == 0 ? points[1] : points[points.length - 2];
            var dir = unit(sub(q, p));
            var ref = Math.abs(dir[1]) < .9 ? new double[]{0, 1, 0} : new double[]{1, 0, 0};
            double at = Math.min(.35 + r, .45 * Math.sqrt(dot(sub(q, p), sub(q, p))));
            var f = new Frame(p, dir, unit(cross(dir, ref)));
            mesh.cylinder(f, r + .04 + .1 * r, at - .03, at + .03, sides(od), Material.FLANGE);
        }
    }

    /**
     * A compressor's steel table (owner request 2026-10-17; {@code MACHINES_SCALE_RESEARCH.md}): two rails {@code rail} deep
     * under the frame's sides (half width {@code half} about x {@code a}) from {@code z0} to {@code z1}, cross beams as deep
     * at each of the {@code lines} along z, posts under them on base plates, their tops at {@code top}. A slow-speed machine
     * of the largest frames stands on a concrete block in real plants; steel here keeps to the other units' look.
     */
    void steelTable(double a, double half, double z0, double z1, double top, List<Double> lines, double rail) {
        double beam = Math.min(rail, top), post = POST + .1, x0 = a - half + post / 2, x1 = a + half - post / 2;
        for (double x : new double[]{x0, x1}) {
            box(new double[]{x - FLANGE_BEAM, top - beam, z0}, new double[]{x + FLANGE_BEAM, top, z1}, Material.STEELWORK, true);
            for (double z : lines) post(x, z, 0, top - beam, post, true);
        }
        for (double z : lines)
            box(new double[]{x0 + FLANGE_BEAM, top - beam, z - FLANGE_BEAM / 2 - .025}, new double[]{x1 - FLANGE_BEAM, top, z + FLANGE_BEAM / 2 + .025}, Material.STEELWORK, true);
    }

    /**
     * A large motor from {@code z0} along z, its shaft at (x, y) ({@code MACHINES_HEATERS_SURVEY.md}): a fabricated
     * octagonal stator frame {@code across} wide and {@code length} long with banded ends, round end shields and the
     * drive-end bearing; louvres on its sides; feet on a steel base frame on posts down to {@code floor}; a top-hat
     * air-to-air cooler with its fan cowl; the terminal box on its side with its conduit down. A compressor's
     * {@code synchronous} motor adds the outboard pedestal bearing with its sight glass and the brushless exciter beyond
     * it, its rotor the flywheel (owner request 2026-10-17: a better motor than a box); a large pump's induction motor
     * ends at its fan cowl.
     */
    void largeMotor(double x, double y, double z0, double across, double length, double floor, boolean synchronous) {
        double r = across / 2, z1 = z0 + length, corner = r * (Math.sqrt(2) - 1); // tan(pi/8): half a side of the octagon of apothem r
        var octagon = new ArrayList<double[]>();
        for (int k = 0; k < 8; k++) {
            double t = Math.PI / 4 * k, c = DeterministicMath.cos(t), s = DeterministicMath.sin(t), u = -s, v = c;
            octagon.add(new double[]{x + r * c - corner * u, y + r * s - corner * v});
        }
        extrude(octagon, z0 + .1, z1 - .1, Material.MOTOR);
        for (double zb : new double[]{z0 + .1, z1 - .22}) {
            var band = new ArrayList<double[]>();
            for (var p : octagon) band.add(new double[]{x + (p[0] - x) * (r + .04) / r, y + (p[1] - y) * (r + .04) / r});
            extrude(band, zb, zb + .12, Material.MOTOR);
        }
        var axis = Frame.of(new double[]{x, y, 0}, 2, 1);
        int n = sides(across);
        mesh.cylinder(axis, .82 * r, z0 + .02, z0 + .1, n, Material.MOTOR);
        mesh.cylinder(axis, .82 * r, z1 - .1, z1 - .02, n, Material.MOTOR);
        mesh.cylinder(axis, .22 * r + .06, z0 - .02, z0 + .02, 12, Material.MOTOR);
        solids.add(Solid.rod(new double[]{x, y, 0}, 2, z0, z1, 1.04 * r, 0));
        // Enclosed: the octagon's inscribed cylinder (a point of the octagon outside it lies within 0.09 r of a face, so a
        // cell whose centre is there holds part of that face).
        volumes.add(Solid.rod(new double[]{x, y, 0}, 2, z0 + .15, z1 - .15, r - .02, 0));
        for (int side = -1; side <= 1; side += 2) {
            double xf = x + side * r, xo = xf + side * .01;
            for (double zl : new double[]{z0 + .3 * length, z1 - .3 * length})
                box(new double[]{Math.min(xf, xo), y - .25 * r, zl - .12 * length}, new double[]{Math.max(xf, xo), y + .3 * r, zl + .12 * length}, Material.OPENING, false);
        }
        // Feet on the base frame; the base frame's beams along z under the feet, on posts when the motor stands high.
        double base = y - r, beam = Math.min(BEAM, base - floor), pedestal = z1 + .05, exciter = pedestal + .4, outer = Math.min(.9, .25 + .12 * across);
        double zEnd = synchronous ? exciter + .2 + .4 * outer : z1 + .05;
        double stand = Math.min(1, .3 + .15 * across) / 2, terminal = Math.min(1.2, .3 + .2 * across);
        for (int side = -1; side <= 1; side += 2) {
            double xf = x + side * .78 * r;
            box(new double[]{xf - .16 * r, base, z0 + .15}, new double[]{xf + .16 * r, y - .55 * r, z1 - .15}, Material.MOTOR, false);
            if (beam > .02) box(new double[]{xf - FLANGE_BEAM, base - beam, z0 - .05}, new double[]{xf + FLANGE_BEAM, base, zEnd + .05}, Material.STEELWORK, true);
            if (base - beam - floor > .05)
                for (double zp : new double[]{z0 + .1, (z0 + zEnd) / 2, zEnd - .1})
                    box(new double[]{xf - POST / 2, floor, zp - POST / 2}, new double[]{xf + POST / 2, base - beam, zp + POST / 2}, Material.STEELWORK, true);
        }
        // The top-hat cooler: its box with louvred sides and tube-plate ends, the fan cowl at its back end.
        double cool = .6 * r, c0 = z0 + .12 * length, c1 = z1 - .12 * length;
        box(new double[]{x - .75 * r, y + r - .02, c0}, new double[]{x + .75 * r, y + r + cool, c1}, Material.MOTOR, true);
        volumes.add(Solid.box(new double[]{x - .75 * r + .05, y + r, c0 + .05}, new double[]{x + .75 * r - .05, y + r + cool - .05, c1 - .05}));
        for (int side = -1; side <= 1; side += 2) {
            double xf = x + side * .75 * r, xo = xf + side * .01;
            box(new double[]{Math.min(xf, xo), y + r + .2 * cool, c0 + .1}, new double[]{Math.max(xf, xo), y + r + .8 * cool, c1 - .1}, Material.OPENING, false);
        }
        var cowl = Frame.of(new double[]{x, y + r + .55 * cool, 0}, 2, 1);
        double rf = .35 * cool + .05, end = Math.min(z1 + .1, c1 + .3);
        mesh.prism(cowl, rf, c1, end, 12, Material.MOTOR);
        mesh.annulus(cowl, .85 * rf, rf, end, 12, 1, Material.MOTOR);
        grilledFan(cowl, .85 * rf, end, Math.min(.15, .6 * (end - c1)), 12, synchronous ? CRANK_SPEED : PUMP_SPEED);
        // The terminal box on the right side, its conduit to the ground.
        double zt = (z0 + z1) / 2;
        box(new double[]{x + r, y - .35 * r, zt - terminal / 2}, new double[]{x + r + .4 * terminal, y - .35 * r + .7 * terminal, zt + terminal / 2}, Material.MOTOR, false);
        mesh.bar(new double[]{x + r + .2 * terminal, floor + .02, zt}, new double[]{x + r + .2 * terminal, y - .35 * r, zt}, .08, new double[]{1, 0, 0}, Material.PIPE);
        if (!synchronous) return;
        // The outboard pedestal bearing and its sight glass; the exciter.
        if (beam > .02) box(new double[]{x - .78 * r + FLANGE_BEAM, base - beam, pedestal}, new double[]{x + .78 * r - FLANGE_BEAM, base, exciter}, Material.STEELWORK, true);
        box(new double[]{x - stand, beam > .02 ? base : floor, pedestal}, new double[]{x + stand, y - .1, exciter}, Material.MOTOR, true);
        mesh.cylinder(axis, .12 + .06 * across, pedestal - .02, exciter + .02, 12, Material.MOTOR);
        box(new double[]{x + stand, y - .3, (pedestal + exciter) / 2 - .05}, new double[]{x + stand + .02, y - .2, (pedestal + exciter) / 2 + .05}, Material.OPENING, false);
        mesh.cylinder(axis, .05 + .02 * across, z1 - .02, pedestal, 8, Material.MACHINE);
        mesh.cylinder(axis, outer / 2, exciter + .02, zEnd - .05, sides(outer), Material.MOTOR);
        mesh.cylinder(axis, .4 * outer, zEnd - .05, zEnd, sides(.8 * outer), Material.MOTOR);
    }

    /** A square flange plate across a throw's line from {@code x0} to {@code x1}, {@code half} out from its axis at (y, z). */
    void plate(double x0, double x1, double y, double z, double half) {
        box(new double[]{Math.min(x0, x1), y - half, z - half}, new double[]{Math.max(x0, x1), y + half, z + half}, Material.MACHINE, false);
    }

    /** A pulsation bottle lying along z at (x, y), its tangent lines at {@code lo} and {@code hi}, with 2:1 heads; it collides and encloses as drawn. */
    void bottle(double x, double y, double lo, double hi, double od, int n) {
        double r = od / 2, depth = od / 4;
        mesh.prism(Frame.of(new double[]{x, y, 0}, 2, 1), r, lo, hi, n, Material.SHELL);
        mesh.head(Frame.of(new double[]{x, y, 0}, 2, 1), r, hi, depth, n, Material.HEAD);
        mesh.head(Frame.of(new double[]{x, y, 0}, 2, -1), r, -lo, depth, n, Material.HEAD);
        var axis = new double[]{x, y, 0};
        solids.add(Solid.rod(axis, 2, lo - depth, hi, r, depth));
        volumes.add(Solid.rod(axis, 2, lo, hi, .94 * r, .9 * depth));
        volumes.add(Solid.rod(axis, 2, lo - .6 * depth, lo, .65 * r, 0));
        round(x, y, lo, hi, r);
    }

    // ---- Fired heaters ----------------------------------------------------------------------------------------------

    /**
     * A vertical cylindrical heater (owner decision M3 2026-10-17; API 560; {@code MACHINES_HEATERS_SURVEY.md}): a round
     * radiant section of diameter D and height {@code tangentLength} on 6 to 14 H-section legs about 2.3 m apart on concrete
     * pedestals, a ring girder under its floor from leg to leg, X-braced in alternate bays and knee-braced in the others
     * (the front's left open), its floor {@code support} up with the burners (muffler drums under their registers) firing
     * up through it from a circle of 0.4 D; the casing's full-height panels bolted on flanged seams over the legs
     * ({@link #seam}), girth bands every 3 m; on its roof the convection section in modules with its panelled header boxes
     * ({@link #convection}), the breeching and the stack with its damper. Platforms (API 560 12.4.1): the
     * hearth platform round the casing at the floor, a 270° one for each further row of peep doors when the radiant
     * section is over 8 m, peep doors 1.4 m over each and an access door over the hearth at the back, rest platforms over
     * the back where a climb would be longer than {@link Access#MAX_CLIMB} ({@link #ringAccess}); a handrail round the
     * roof's edge; caged ladders at the side legs ({@link Access}), alternating, the last walked through onto the roof.
     * Five nozzles at the front (owner request 2026-10-17): the convection coil in near the top of its front header box and
     * out near its bottom (it flows down against the flue gas), the radiant coil in just under the roof and out just over
     * the floor (the crossover between them is the player's pipe), fuel gas to the burners' ring header.
     */
    void verticalHeater() {
        double dia = shape.diameter(), r = dia / 2, height = shape.tangentLength(), y0 = shape.support() - .3, y1 = shape.support() + height;
        int floor = shape.support();
        double hx = Math.min(2.25, Math.max(.75, .225 * dia)), hz = Math.min(.425 * dia, Math.sqrt(r * r - hx * hx) - .1), process = Math.min(.6, hx);
        // The convection section high enough for its outlet's row and its inlet's two rows over it (one free block between
        // their pipes, owner rule 2026-10-17).
        double yConvection = Math.ceil(y1 + .2 + process / 2 - .5) + .5, hc = Math.max(Math.min(6, Math.max(2, .4 * dia + 1)), yConvection + 2.2 - y1);
        double wall = r + .12, width = ColumnStructure.MIN_DECK, edge = wall + width;
        double a = centre(Math.max(edge + .05, hz + HEADER_BOX + .2));
        int n = 4 * (int) Math.ceil(.75 * Math.PI * dia), legs = Math.max(6, 2 * (int) Math.round(Math.PI * dia / 4.6));
        double step = 2 * Math.PI / legs;
        var up = Frame.of(new double[]{a, 0, a}, 1, 1);
        mesh.prism(up, r, y0, y1, n, Material.HEATER);
        mesh.annulus(up, 0, r, y0, n, -1, Material.HEATER);
        mesh.annulus(up, 0, r, y1, n, 1, Material.HEATER);
        solids.add(Solid.rod(new double[]{a, 0, a}, 1, y0, y1, r, 0));
        volumes.add(Solid.rod(new double[]{a, 0, a}, 1, y0 + .1, y1 - .1, .94 * r, 0));
        round(a, a, y0, y1, r, 1);
        for (double y = floor + 3; y < y1 - 1; y += 3) band(up, r, y, n, Material.HEATER);
        // Legs under the casing columns, half a pitch off the front, their webs radial; a ring girder under the floor from
        // leg to leg; X-bracing in alternate bays from the front's right, knee braces in the others but the front's.
        double cased = casing(legDepth(dia)), girder = Math.min(.6, .3 + .025 * dia), low = y0 - girder;
        var legAt = new double[legs][];
        for (int k = 0; k < legs; k++) {
            double t = -Math.PI / 2 + (k + .5) * step;
            double[] radial = {DeterministicMath.cos(t), 0, DeterministicMath.sin(t)};
            legAt[k] = new double[]{a + (r - .12) * radial[0], a + (r - .12) * radial[2]};
            leg(legAt[k][0], legAt[k][1], y0, cased, radial);
            seam(new double[]{a + r * radial[0], y0, a + r * radial[2]}, new double[]{a + r * radial[0], y1, a + r * radial[2]}, radial, true);
        }
        for (int k = 0; k < legs; k++) girder(legAt[k], legAt[(k + 1) % legs], low, y0, cased);
        solids.add(Solid.ring(a, a, low, y0, r - .12 - GIRDER_HALF, r - .12 + GIRDER_HALF, 0, 2 * Math.PI));
        for (int k = 0; k + 1 < legs; k++)
            if (k % 2 == 1 && k + 1 < legs - 1) brace(legAt[k], legAt[k + 1], .6, low, .15, BRACE_TIER);
            else knees(legAt[k], legAt[k + 1], low, cased);
        // The platforms and their ladders, alternating between the side legs (ringAccess): the hearth platform at
        // the floor, a 270° one at each further row of peep doors, rest platforms over the back where a climb would be
        // longer than Access.MAX_CLIMB (owner decision C1 2026-10-18: a tall support's), then the roof.
        int extra = height > 8 ? (int) Math.ceil(height / 8) - 1 : 0;
        var wanted = new ArrayList<Integer>();
        wanted.add(floor);
        for (int k = 1; k <= extra; k++) wanted.add(floor + (int) Math.round(height * k / (extra + 1)));
        var levels = Access.climbs(0, wanted, (int) Math.ceil(y1 - Access.DECK - 1e-9));
        levels.removeLast();
        int side = (int) Math.ceil(legs / 4.0 - .5);
        double right = -Math.PI / 2 + (side + .5) * step, left = Math.PI - right, gap = DeterministicMath.asin(Math.min(.95, .6 / (wall + Access.GAP)));
        double clear = Access.clearance(wall);
        var rings = new ArrayList<Ring>();
        for (int k = 0; k < levels.size(); k++) {
            int level = levels.get(k);
            double in = k % 2 == 0 ? right : left;
            if (level == floor) rings.add(new Ring(level, in, -Math.PI / 2 + gap, 1.5 * Math.PI - gap));
            else if (wanted.contains(level)) rings.add(in == right ? new Ring(level, in, right, 1.5 * Math.PI - gap) : new Ring(level, in, -Math.PI / 2 + gap, left));
            else rings.add(in == right ? new Ring(level, in, right, left + clear) : new Ring(level, in, right - clear, left));
        }
        var arcs = ringAccess(a, a, wall, width, 0, rings);
        for (int k = 0; k < levels.size(); k++) {
            int level = levels.get(k);
            if (!wanted.contains(level)) continue;
            // Peep doors between the columns over the decks, 1.4 m up; the access door over the hearth at the back.
            for (int j = 0; j + 1 < legs; j++) {
                double t = -Math.PI / 2 + (j + 1) * step;
                boolean decked = false;
                for (var arc : arcs.get(k)) decked |= t > arc.from() + .05 && t < arc.to() - .05;
                if (decked) door(a, a, r, t, level + Access.DECK + 1.4, .125, .15);
            }
            if (level == floor) door(a, a, r, Math.PI / 2, level + Access.DECK + .4, .3, .3);
        }
        double roofIn = levels.size() % 2 == 0 ? right : left;
        verticalLadder(a, a, wall, roofIn, levels.getLast() + Access.DECK, y1 + Access.LANDING, true);
        // The roof's handrail on its edge, open at the ladder, the front's nozzle and header boxes that overhang it.
        double rail = r - .03, front = DeterministicMath.asin(Math.min(.95, (Math.max(.6, hx) + .1) / rail)), ladderGap = DeterministicMath.asin(Math.min(.95, (Access.LADDER_HALF + .2) / rail));
        var gaps = new ArrayList<double[]>();
        gaps.add(new double[]{roofIn - ladderGap, roofIn + ladderGap});
        if (hz + HEADER_BOX > Math.sqrt(Math.max(0, rail * rail - hx * hx)) - .1) {
            double back = DeterministicMath.asin(Math.min(.95, (hx + .05) / rail));
            gaps.add(new double[]{Math.PI / 2 - back, Math.PI / 2 + back});
        }
        gaps.sort((p, q) -> Double.compare(p[0], q[0]));
        double from = -Math.PI / 2 + front;
        for (var g : gaps) {
            if (g[0] > from + .05) railing(new Access.Arc(a, a, r - 1, from, g[0], r, Access.Arc.step(r)), y1 - Access.DECK, 1);
            from = Math.max(from, g[1]);
        }
        if (1.5 * Math.PI - front > from + .05) railing(new Access.Arc(a, a, r - 1, from, 1.5 * Math.PI - front, r, Access.Arc.step(r)), y1 - Access.DECK, 1);
        // Burners on a circle under the floor, their ring header and its risers.
        double duty = heaterDuty(Kind.VERTICAL_HEATER, dia, height), circle = .2 * dia, register = .25 + .02 * dia;
        int burners = Math.max(3, Math.min(16, Math.min((int) Math.round(duty / 2.5), (int) Math.floor(2 * Math.PI * circle / (2.6 * register + .1)))));
        var at = new double[burners][];
        for (int i = 0; i < burners; i++) {
            double t = 2 * Math.PI * (i + .5) / burners;
            at[i] = new double[]{a + circle * DeterministicMath.sin(t), a + circle * DeterministicMath.cos(t)};
        }
        // The ring header low enough that the fuel's line at a pipe's largest passes under the burners' mufflers (owner
        // decision 2026-10-21: a 1 m pipe has a 1 m root, other components give way).
        double yFuel = Math.floor(Math.min(y0 - 1.2, y0 - BURNER_DROP - .05 - PipeSizes.MAX_OUTSIDE / 2) - .5) + .5, pipe = .04 + .004 * dia;
        burners(at, register, y0, yFuel, pipe);
        for (int i = 0; i < burners; i++) {
            double[] p = {at[i][0], yFuel, at[i][1]}, q = {at[(i + 1) % burners][0], yFuel, at[(i + 1) % burners][1]};
            mesh.cylinder(new Frame(p, unit(sub(q, p)), new double[]{0, 1, 0}), pipe, 0, Math.sqrt(dot(sub(q, p), sub(q, p))), 8, Material.PIPE);
        }
        // The convection section with its header boxes, the stack.
        double y2 = y1 + hc;
        convection(a - hx, a + hx, a - hz, a + hz, y1, y2);
        var breeching = new Breeching(a - hx, a + hx, a - hz, a + hz);
        double stack = .25 * dia + .3, neck = breeching.neck(y2, stack);
        stack(a, a, stack, sides(stack), y2, Math.min(neck + MAX_STACK, stackTop(neck, y1)), Material.HEATER, breeching, true);
        // The convection coil in near the top of its front header box and out near its bottom; the radiant coil in at the
        // front under the roof, two rows under the convection outlet, and out just over the floor; fuel gas to the ring
        // header's front from the front. Every pipe block keeps one free block from the next.
        double feed = ColumnStructure.size(.1 + .02 * dia, process), zIn = a - hz - HEADER_BOX + .03, yConvIn = Math.floor(y2 - .7) + .5;
        double yRadiantIn = Math.min(Math.floor(y1 - .7 - process / 2) + .5, yConvection - 2);
        nozzles.add(front(Role.CONVECTION_INLET, a, yConvIn, zIn, feed));
        nozzles.add(front(Role.CONVECTION_OUTLET, a, yConvection, zIn, feed));
        nozzles.add(radialSide(Role.RADIANT_INLET, a, yRadiantIn, a, r, n, -1, feed));
        nozzles.add(radialSide(Role.RADIANT_OUTLET, a, floor + .5, a, r, n, -1, feed));
        // The ring's front: a burner's riser where there is an odd number, else the chord between two.
        double zFuel = a - circle * (burners % 2 == 1 ? 1 : DeterministicMath.cos(Math.PI / burners)) + .03, fuel = .3;
        nozzles.add(new Nozzle(Role.FUEL, new double[]{a, yFuel, zFuel}, 2, -1, zFuel - Math.floor(a - r - NECK),
                ColumnStructure.size(.05 + .003 * duty, fuel), 0));
    }

    /**
     * A caged ladder ({@link #ladder}) at angle {@code t} (from +x towards +z) round a round body whose axis stands at
     * (x, z), its rung plane {@link Access#STANDOFF} beyond the wall {@code wall} from the axis and bracketed to it (a
     * vertical heater's: the casing columns' face, the leg and column behind it), from {@code base} to {@code top}; one
     * {@code walkThrough} is left between its rails over its landing.
     */
    void verticalLadder(double x, double z, double wall, double t, double base, double top, boolean walkThrough) {
        double rung = wall + Access.STANDOFF;
        ladder(x + rung * DeterministicMath.cos(t), z + rung * DeterministicMath.sin(t), new double[]{DeterministicMath.cos(t), 0, DeterministicMath.sin(t)}, Access.STANDOFF + .12,
                base, top, walkThrough);
    }

    /**
     * A cabin heater (owner decision M3 2026-10-17; API 560; {@code MACHINES_HEATERS_SURVEY.md}): a box radiant section
     * of width W and length {@code tangentLength} along z on H-section legs every 3 m or so on concrete pedestals, a girder
     * under the floor round its edge, X-braced along both sides in the end bays and every third and knee-braced in the
     * others (the burner aisles open), its walls W + 1 high, a hipped roof rising to the convection section
     * along its whole length, its floor {@code support} up with one or two rows of burners (muffler drums under their
     * registers) firing up through it. The wall panels bolted on flanged seams over the legs ({@link #seam}) with an eave
     * beam, peep doors 1.4 m over each walkway in every bay and an access door over the hearth walkway; panelled header
     * boxes on both end walls for the side walls' tube bends and for the convection section's (in modules,
     * {@link #convection}), an insulated crossover between the
     * back radiant header boxes (the coil runs back along the right wall and returns along the left); one stack per 15 m
     * with its damper. Walkways on both sides (API 560
     * 12.4.1, {@link Access}): the hearth walkway the full length, a second one under the eaves when the walls are 6 m or
     * more and on a taller wall one at each row of peep doors between (at most 8 m apart), the convection walkway over the
     * roof's slope; a ladder walked through from grade onto the hearth walkway (the
     * left's at the back, the right's at the front), side-step ladders against the wall between the side walkways and one
     * walked through onto the convection walkway. Five nozzles at the front (owner request 2026-10-17): the convection
     * coil in near the top of its front header box and out near its bottom, the radiant coil into the left front radiant
     * header box (+x, seen from the constructor) near its top and out of the right one just over the floor (the crossover between the coils is the
     * player's pipe), fuel gas to the burner rows' headers.
     */
    void cabinHeater() {
        double w = shape.diameter(), total = shape.tangentLength(), y0 = shape.support() - .3, y1 = shape.support() + cabinWall(w);
        int floor = shape.support(), cells = cabinCells(total);
        double length = total / cells, wc = Math.min(4, Math.max(1.5, .4 * w)), yr = y1 + .6 * (w - wc) / 2, walk = 1.2, process = Math.min(.6, wc / 2);
        // The convection section high enough for its outlet's row and its inlet's two rows over it (one free block between
        // their pipes, owner rule 2026-10-17).
        double yConvection = Math.ceil(yr + .2 + process / 2 - .5) + .5, hc = Math.max(Math.min(5, Math.max(2, .25 * w + 1)), yConvection + 2.2 - yr);
        // One cell in the middle, or two either side of an aisle; the convection section over the middle, over a twin's
        // aisle and half its throat into each cell. A wall outside the convection section is an eave, its roof slope rising
        // to the section's side; a twin's inner wall stands under the section, up to it.
        double gap = cells == 2 ? AISLE : 0, half = (cells * w + gap) / 2;
        double a = centre(half + .12 + walk + .05 + 2 * Access.CAGE_HALF + .1), zs = 1, ze = zs + length, c0 = a - (wc + gap) / 2, c1 = a + (wc + gap) / 2;
        double[] cellX = cells == 1 ? new double[]{a} : new double[]{a - (gap + w) / 2, a + (gap + w) / 2};
        int bays = Math.max(2, (int) Math.round(length / 3)), out = (int) Math.floor(w / 2 - .5);
        double cased = casing(legDepth(w)), low = y0 - legDepth(w), rise = yr - y1;
        for (double ac : cellX) {
            double x0 = ac - w / 2, x1 = ac + w / 2;
            boolean eave0 = x0 < c0 - 1e-9, eave1 = x1 > c1 + 1e-9;
            double top0 = eave0 ? y1 : yr, top1 = eave1 ? y1 : yr, run0 = c0 - x0, run1 = x1 - c1;
            // The walls, the end walls up to the roof, the floor's underside and the roof's slopes.
            mesh.flat(List.of(new double[]{x0, y0, zs}, new double[]{x0, top0, zs}, new double[]{x0, top0, ze}, new double[]{x0, y0, ze}), new double[]{-1, 0, 0}, Material.HEATER);
            mesh.flat(List.of(new double[]{x1, y0, zs}, new double[]{x1, y0, ze}, new double[]{x1, top1, ze}, new double[]{x1, top1, zs}), new double[]{1, 0, 0}, Material.HEATER);
            mesh.flat(List.of(new double[]{x0, y0, zs}, new double[]{x0, y0, ze}, new double[]{x1, y0, ze}, new double[]{x1, y0, zs}), new double[]{0, -1, 0}, Material.HEATER);
            for (double z : new double[]{zs, ze}) {
                var outline = new ArrayList<double[]>(List.of(new double[]{x0, y0, z}, new double[]{x1, y0, z}, new double[]{x1, top1, z}));
                if (eave1) outline.add(new double[]{c1, yr, z});
                if (eave0) outline.add(new double[]{c0, yr, z});
                outline.add(new double[]{x0, top0, z});
                mesh.flat(outline, new double[]{0, 0, z == zs ? -1 : 1}, Material.HEATER);
            }
            if (eave0) mesh.flat(List.of(new double[]{x0, y1, zs}, new double[]{c0, yr, zs}, new double[]{c0, yr, ze}, new double[]{x0, y1, ze}), new double[]{-rise, run0, 0}, Material.HEATER);
            if (eave1) mesh.flat(List.of(new double[]{x1, y1, zs}, new double[]{c1, yr, zs}, new double[]{c1, yr, ze}, new double[]{x1, y1, ze}), new double[]{rise, run1, 0}, Material.HEATER);
            solids.add(Solid.box(new double[]{x0, y0, zs}, new double[]{x1, y1, ze}));
            volumes.add(Solid.box(new double[]{x0 + .1, y0 + .1, zs + .1}, new double[]{x1 - .1, y1, ze - .1}));
            // Under the roof in three steps, each inside the slopes at its top.
            for (int k = 0; k < 3; k++) {
                double b0 = y1 + k * rise / 3, b1 = b0 + rise / 3, lo = eave0 ? x0 + (k + 1) * run0 / 3 + .05 : x0, hi = eave1 ? x1 - (k + 1) * run1 / 3 - .05 : x1;
                solids.add(Solid.box(new double[]{lo, b0, zs}, new double[]{hi, b1, ze}));
                volumes.add(Solid.box(new double[]{lo, b0, zs + .1}, new double[]{hi, b1, ze - .1}));
            }
            for (double[] p : new double[][]{{x0, zs, x1, zs}, {x1, zs, x1, ze}, {x1, ze, x0, ze}, {x0, ze, x0, zs}}) segment(new double[]{p[0], y1, p[1]}, new double[]{p[2], y1, p[3]});
            // Fireproofed legs and casing columns every 3 m or so; a girder under the floor round its edge; X-bracing in the
            // end bays and every third, knee braces in the others and across both ends; an eave beam along each eave wall.
            for (int k = 0; k <= bays; k++) {
                double z = zs + length * k / bays;
                for (int side = -1; side <= 1; side += 2) {
                    double wall = ac + side * w / 2, leg = ac + side * (w / 2 - .15);
                    leg(leg, z, y0, cased, new double[]{1, 0, 0});
                    seam(new double[]{wall, y0, z}, new double[]{wall, side < 0 ? top0 : top1, z}, new double[]{side, 0, 0}, true);
                    if (k == bays) continue;
                    double[] p = {leg, z}, q = {leg, zs + length * (k + 1) / bays};
                    if (k == 0 || k == bays - 1 || k % 3 == 1) brace(p, q, .6, low, .15, BRACE_TIER);
                    else knees(p, q, low, cased);
                }
            }
            for (int side = -1; side <= 1; side += 2) {
                double leg = ac + side * (w / 2 - .15);
                box(new double[]{leg - GIRDER_HALF, low, zs - cased / 2}, new double[]{leg + GIRDER_HALF, y0, ze + cased / 2}, Material.STEELWORK, true);
            }
            for (double z : new double[]{zs, ze}) {
                double left = ac - (w / 2 - .15), right = ac + (w / 2 - .15);
                box(new double[]{left + cased / 2, low, z - GIRDER_HALF}, new double[]{right - cased / 2, y0, z + GIRDER_HALF}, Material.STEELWORK, true);
                knees(new double[]{left, z}, new double[]{right, z}, low, cased);
            }
            for (int side = -1; side <= 1; side += 2) {
                if (!(side < 0 ? eave0 : eave1)) continue;
                double wall = ac + side * w / 2, outer = wall + side * .15;
                box(new double[]{Math.min(wall, outer), y1 - .25, zs - .1}, new double[]{Math.max(wall, outer), y1, ze + .1}, Material.HEATER, false);
            }
            // Header boxes on both end walls for the side walls' tube bends, panelled; the crossover between the back ones.
            for (int side = -1; side <= 1; side += 2) {
                double x = ac + side * out;
                for (int end = 0; end < 2; end++) {
                    double[] lo = {x - .4, floor, end == 0 ? zs - HEADER_BOX : ze}, hi = {x + .4, y1 - .4, end == 0 ? zs : ze + HEADER_BOX};
                    mesh.box(lo, hi, ALL_FACES & ~(end == 0 ? 32 : 16), Material.HEATER);
                    solids.add(Solid.box(lo, hi));
                    panels(lo, hi, 2, end == 0 ? -1 : 1, 1, Math.max(2, (int) Math.round((hi[1] - lo[1]) / 1.3)));
                }
            }
            double cross = .06 + .008 * w, yCross = Math.floor(y1 - 1.2) + .5, zCross = ze + HEADER_BOX + .35;
            line(cross, Material.CLADDING, new double[]{ac + out, yCross, ze + HEADER_BOX - .05}, new double[]{ac + out, yCross, zCross}, new double[]{ac - out, yCross, zCross},
                    new double[]{ac - out, yCross, ze + HEADER_BOX - .05});
        }
        if (cells == 2) mesh.flat(List.of(new double[]{a - gap / 2, yr, zs}, new double[]{a + gap / 2, yr, zs}, new double[]{a + gap / 2, yr, ze}, new double[]{a - gap / 2, yr, ze}),
                new double[]{0, -1, 0}, Material.HEATER);
        // Burners in one row of each cell (two from 7 m wide) under the floor, a header along each row and one across the front.
        double duty = heaterDuty(Kind.CABIN_HEATER, w, total), register = .25 + .015 * w, yFuel = Math.floor(y0 - 1.2 - .5) + .5, pipe = .05 + .004 * w * Math.sqrt(cells);
        var rows = new ArrayList<Double>();
        for (double ac : cellX) {
            if (w <= 6) rows.add(ac);
            else {
                rows.add(ac - w / 4);
                rows.add(ac + w / 4);
            }
        }
        int each = Math.max(2, Math.min((int) Math.round(duty / 4 / rows.size()), (int) Math.floor((length - 1) / (2.6 * register + .2))));
        var at = new double[rows.size() * each][];
        for (int row = 0; row < rows.size(); row++)
            for (int i = 0; i < each; i++) at[row * each + i] = new double[]{rows.get(row), zs + length * (i + .5) / each};
        burners(at, register, y0, yFuel, pipe);
        double zHeader = zs + .3;
        for (double x : rows) mesh.cylinder(Frame.of(new double[]{x, yFuel, 0}, 2, 1), pipe, zHeader, zs + length * (each - .5) / each, 8, Material.PIPE);
        if (rows.size() > 1) mesh.cylinder(Frame.of(new double[]{0, yFuel, zHeader}, 0, 1), pipe, rows.getFirst(), rows.getLast(), 8, Material.PIPE);
        // The convection section along the whole roof, a stack over each of its parts (a twin's flue gas through stacks
        // √2 as wide).
        double y2 = yr + hc, stack = Math.min(3.5, (.25 * w + .3) * Math.sqrt(cells));
        int convection = (int) Math.ceil(yr + .05);
        convection(c0, c1, zs, ze, yr, y2);
        int stacks = Math.max(1, (int) Math.round(length / 15));
        for (int j = 0; j < stacks; j++) {
            double zj = zs + length * (j + .5) / stacks, room = Math.min(length / stacks / 2 - .2, Math.max((c1 - c0) / 2, stack / 2 + .3));
            var breeching = new Breeching(c0, c1, zj - room, zj + room);
            double neck = breeching.neck(y2, stack);
            stack(a, zj, stack, sides(stack), y2, Math.min(neck + MAX_STACK, stackTop(neck, y1)), Material.HEATER, breeching, true);
        }
        // The walkways and ladders of each eave wall: the hearth walkway; on walls 6 m or more the side walkways at the rows
        // of peep doors up the wall at most 8 m apart (as the vertical heater's platforms, so no caged climb passes
        // Access.MAX_CLIMB: owner decision C1 2026-10-18), the highest under the eaves, each reached from the one under it
        // by a side-step ladder at alternate ends of the wall; the convection walkway from the highest. Peep doors over
        // each walkway in every bay but where a ladder stands, an access door over the hearth walkway in the middle bay.
        var walkways = new ArrayList<Integer>();
        if (y1 - 2.4 - floor >= 3) {
            int eaves = (int) (y1 - 2.4), between = eaves - floor > 8 ? (int) Math.ceil((eaves - floor) / 8.0) - 1 : 0;
            for (int k = 1; k <= between; k++) walkways.add(floor + (int) Math.round((eaves - floor) * k / (between + 1.0)));
            walkways.add(eaves);
        }
        int count = walkways.size();
        for (int side = -1; side <= 1; side += 2) {
            double wall = side < 0 ? cellX[0] - w / 2 : cellX[cellX.length - 1] + w / 2, wallX = wall + side * .12, edge = side < 0 ? c0 : c1;
            double[] outward = {side, 0, 0}, along = {0, 0, 1};
            // The wall's end the first side-step ladder stands at (the back's on the left, the front's on the right), the
            // grade ladder's at the other, and the way along z from the first to the other.
            double first = side < 0 ? zs : ze, last = side < 0 ? ze : zs, in = side < 0 ? 1 : -1;
            double zGrade = last - in * .6, zMid = first + in * .5, start = zs - .3;
            platform(new Access.Line(new double[]{wallX, 0, start}, along, outward, length + .6), floor, walk, true, true, zGrade - Access.WALK_THROUGH - start, zGrade + Access.WALK_THROUGH - start);
            // The climb from grade up the walkway's outer edge, resting on posts beyond it where the floor stands over 9 m.
            double zRest = Math.min(zGrade, zGrade - in * 1.2) - .6;
            climb(new Access.Line(new double[]{wallX + side * walk, 0, zRest}, along, outward, 2.4), 0, floor + Access.DECK, zGrade - in * 1.2 - zRest, zGrade - zRest, .15);
            int top = floor;
            var ladderAt = new double[count];
            for (int i = 0; i < count; i++) {
                boolean atFirst = i % 2 == 0;
                double zl = ladderAt[i] = atFirst ? zMid : last - in * .5;
                // The walkway from beside its ladder (open there: the climber steps off sideways) to the wall's other end.
                double open = zl + (atFirst ? in : -in) * .55, closed = atFirst ? last + in * .3 : first - in * .3, from = Math.min(open, closed), to = Math.max(open, closed);
                platform(new Access.Line(new double[]{wallX, 0, from}, along, outward, to - from), walkways.get(i), walk, from == closed, to == closed);
                ladder(wallX + side * Access.STANDOFF, zl, outward, Access.STANDOFF, top + Access.DECK, walkways.get(i) + Access.DECK + Access.LANDING, false);
                top = walkways.get(i);
            }
            // The convection walkway's ladder on the highest walkway, away from the ladder that reached it.
            double zConv = count > 0 && count % 2 == 0 ? first + in * 1.6 : last - in * 1.6;
            platform(new Access.Line(new double[]{edge, 0, zs}, along, outward, length), convection, Math.abs(wall - edge) + .12, true, true, zConv - Access.WALK_THROUGH - zs, zConv + Access.WALK_THROUGH - zs);
            ladder(wallX + side * .05, zConv, outward, .05, top + Access.DECK, convection + Access.DECK + Access.LANDING, true);
            for (int k = 0; k < bays; k++) {
                double z = zs + length * (k + .5) / bays;
                for (int j = 0; j <= count; j++) {
                    boolean ladderThere = j == count && Math.abs(z - zConv) < .7 || j < count && Math.abs(z - ladderAt[j]) < .7 || j > 0 && Math.abs(z - ladderAt[j - 1]) < .7;
                    if (!ladderThere) door(wall, z, side, (j == 0 ? floor : walkways.get(j - 1)) + Access.DECK + 1.4, .125, .15);
                }
                if (k == bays / 2 && Math.abs(z - zConv) > 1 && Math.abs(z - zMid) > 1) door(wall, z + .7, side, floor + Access.DECK + .4, .3, .3);
            }
        }
        // Nozzles at the front: the convection coil's at its header box; one cell's radiant coil in at the left header box
        // (+x, seen from the constructor) and out of the right one; a twin's coils fed from an inlet manifold between their inner header boxes and joined in
        // an outlet manifold between their outer ones, both across the front of the aisle.
        double feed = ColumnStructure.size(.1 + .02 * w * Math.sqrt(cells), process), zIn = zs - HEADER_BOX + .03, yIn = Math.floor(y1 - 1.2) + .5, yOut = floor + .5;
        double yConvIn = Math.floor(y2 - .7) + .5;
        nozzles.add(front(Role.CONVECTION_INLET, a, yConvIn, zIn, feed));
        nozzles.add(front(Role.CONVECTION_OUTLET, a, yConvection, zIn, feed));
        if (cells == 1) {
            nozzles.add(front(Role.RADIANT_INLET, a + out, yIn, zIn, feed));
            nozzles.add(front(Role.RADIANT_OUTLET, a - out, yOut, zIn, feed));
        } else {
            double manifold = feed / 2 + .05, zm = zs - HEADER_BOX - manifold - .1, face = zs - HEADER_BOX;
            for (double[] ends : new double[][]{{cellX[0] + out, cellX[1] - out, yIn}, {cellX[0] - out, cellX[1] + out, yOut}})
                line(manifold, Material.PIPE, new double[]{ends[0], ends[2], face}, new double[]{ends[0], ends[2], zm}, new double[]{ends[1], ends[2], zm}, new double[]{ends[1], ends[2], face});
            nozzles.add(front(Role.RADIANT_INLET, a, yIn, zm, feed));
            nozzles.add(front(Role.RADIANT_OUTLET, a, yOut, zm, feed));
        }
        double zFuel = zHeader + .03, fuel = .3;
        nozzles.add(new Nozzle(Role.FUEL, new double[]{a, yFuel, zFuel}, 2, -1, zFuel - Math.floor(zs - NECK),
                ColumnStructure.size(.05 + .003 * duty, fuel), 0));
    }

    /** A twin cabin's aisle between its cells' inner walls, m: room for a player between the legs' pedestals. */
    static final double AISLE = 1.5;

    /** A cabin's longest radiant cell, m: horizontal radiant tubes reach about 24 m ({@code MACHINES_SCALE_RESEARCH.md}). */
    static final int MAX_CABIN_CELL = 24;

    /** A cabin's radiant cells: a longer cabin is a twin cell, two cells of half its length side by side (owner decision 2026-10-17). */
    public static int cabinCells(double length) {
        return length > MAX_CABIN_CELL ? 2 : 1;
    }

    /** A heater's header box depth beyond the casing it stands on, m (its tube bends and their covers). */
    static final double HEADER_BOX = .6;

    /**
     * A girth band round a vertical heater's casing or its stack (radius {@code r}, {@code n} sides about frame {@code up})
     * at height {@code y}, 0.05 m proud and 0.12 m high in {@code paint}: a stack's joint, a stiffening ring.
     */
    void band(Frame up, double r, double y, int n, Material paint) {
        mesh.hoop(up, r, r + .05, y, y + .12, n, paint);
    }

    /** X-bracing between two legs at (x, z) from {@code low} to {@code high}: angles 0.15 m across, their ends drawn. */
    void brace(double[] p, double[] q, double low, double high) {
        brace(p, q, low, high, .15, Double.POSITIVE_INFINITY, BOTH_ENDS);
    }

    /** The tallest tier of a heater's X-bracing, m: diagonals at about 50° over its 2.3 to 3 m bays. */
    static final double BRACE_TIER = 3;

    /**
     * X-bracing between two columns at p and q ({x, z} each) from {@code low} to {@code high}: angles {@code width} across
     * crossing in equal tiers of at most {@code tier} (a tall bay braced at a steep angle carries little; AISC practice keeps
     * diagonals near 45°), their ends in the columns.
     */
    void brace(double[] p, double[] q, double low, double high, double width, double tier) {
        brace(p, q, low, high, width, tier, NO_ENDS);
    }

    private void brace(double[] p, double[] q, double low, double high, double width, double tier, int ends) {
        int tiers = Math.max(1, (int) Math.ceil((high - low) / tier - 1e-9));
        double[] up = {0, 1, 0};
        for (int k = 0; k < tiers; k++) {
            double y0 = low + (high - low) * k / tiers, y1 = k + 1 == tiers ? high : low + (high - low) * (k + 1) / tiers;
            mesh.bar(new double[]{p[0], y0, p[1]}, new double[]{q[0], y1, q[1]}, width, up, ends, Material.STEELWORK);
            mesh.bar(new double[]{p[0], y1, p[1]}, new double[]{q[0], y0, q[1]}, width, up, ends, Material.STEELWORK);
        }
    }

    /** A girder's half width, m. */
    private static final double GIRDER_HALF = .125;

    /**
     * A heater leg's H-section (inside its concrete {@link #casing}), its depth and flange width, and the depth of the cabin's
     * girder under the floor, m: about W12 on
     * the smallest heater to W18 on the largest (owner request 2026-10-17, "make furnace support looks more rigid"; the
     * girder at most 0.45 m, over the burners' muffler drums).
     */
    static double legDepth(double size) {
        return Math.min(.45, .28 + .015 * size);
    }

    /**
     * A fireproofed leg's concrete casing across, m: the H-section with 50 mm of cover, 0.45 m at least (refinery practice
     * over the fire zone, {@code MACHINES_SCALE_RESEARCH.md}; owner decision 2026-10-17, "add concrete fireproofing up to
     * the floor": yes).
     */
    static double casing(double depth) {
        return Math.max(.45, depth + .1);
    }

    /**
     * A heater's leg at (x, z): an H-section in a square concrete casing {@code cased} across (its faces square to
     * {@code web}, horizontal) from its pedestal 0.3 m over grade up to the floor at {@code top}.
     */
    void leg(double x, double z, double top, double cased, double[] web) {
        double[] flange = {-web[2], 0, web[0]};
        double half = cased / 2, pad = half + .1;
        mesh.block(new double[]{x, .15, z}, scale(web, pad), new double[]{0, .15, 0}, scale(flange, pad), Material.CONCRETE);
        mesh.block(new double[]{x, (.3 + top) / 2, z}, scale(web, half), new double[]{0, (top - .3) / 2, 0}, scale(flange, half), Material.CONCRETE);
        solids.add(Solid.box(new double[]{x - pad, 0, z - pad}, new double[]{x + pad, .3, z + pad}));
        solids.add(Solid.box(new double[]{x - half, .3, z - half}, new double[]{x + half, top, z + half}));
    }

    /** A girder from leg p to leg q, {x, z} each, from {@code low} to {@code high}, between the legs' casings ({@code depth} across). */
    void girder(double[] p, double[] q, double low, double high, double depth) {
        double dx = q[0] - p[0], dz = q[1] - p[1], length = Math.sqrt(dx * dx + dz * dz);
        double[] along = {dx / length, 0, dz / length}, across = {-along[2], 0, along[0]};
        mesh.block(new double[]{(p[0] + q[0]) / 2, (low + high) / 2, (p[1] + q[1]) / 2}, scale(along, length / 2 - depth / 2), new double[]{0, (high - low) / 2, 0},
                scale(across, GIRDER_HALF), Material.STEELWORK);
    }

    /** Knee braces from the casings ({@code depth} across) of legs p and q ({x, z}) up to the girder's underside at {@code low}, a third of the bay or 0.8 m along it. */
    void knees(double[] p, double[] q, double low, double depth) {
        double dx = q[0] - p[0], dz = q[1] - p[1], length = Math.sqrt(dx * dx + dz * dz), reach = Math.min(.8, length / 3) + depth / 2;
        double ux = dx / length, uz = dz / length;
        for (int end = 0; end < 2; end++) {
            double[] from = end == 0 ? p : q;
            double s = end == 0 ? 1 : -1, x = from[0] + s * ux * depth / 2, z = from[1] + s * uz * depth / 2;
            mesh.bar(new double[]{x, low - reach + depth / 2, z}, new double[]{from[0] + s * ux * reach, low, from[1] + s * uz * reach}, .15, new double[]{0, 1, 0}, Material.STEELWORK);
        }
    }

    /** A nozzle out of a box's front face (frame -z) at (x, y), its neck starting at z: its flange face on the next block face {@link #NECK} and 0.03 m beyond. */
    static Nozzle front(Role role, double x, double y, double z, double defaultOd) {
        return end(role, new double[]{x, y, z}, 2, -1, defaultOd);
    }

    /**
     * A nozzle at a line's end: its neck from {@code base} along frame axis {@code axis} towards {@code sign}, its flange
     * face on the next block face {@link #NECK} and 0.03 m beyond (a base 0.03 m inside a casing; the block past it free
     * for the player's pipe).
     */
    static Nozzle end(Role role, double[] base, int axis, int sign, double defaultOd) {
        double at = base[axis], plane = sign > 0 ? Math.ceil(at + .03 + NECK) : Math.floor(at - .03 - NECK);
        return new Nozzle(role, base, axis, sign, Math.abs(plane - at), defaultOd, 0);
    }

    /** A door on a round casing (axis at (x, z), radius {@code r}) at angle {@code t}, centred at height {@code y}: {@code across} and {@code up} its half sizes. */
    void door(double x, double z, double r, double t, double y, double across, double up) {
        double[] radial = {DeterministicMath.cos(t), 0, DeterministicMath.sin(t)}, tangent = {-radial[2], 0, radial[0]};
        mesh.block(new double[]{x + (r + .04) * radial[0], y, z + (r + .04) * radial[2]}, scale(radial, .04), new double[]{0, up, 0}, scale(tangent, across), Material.MACHINE);
    }

    /** A door on a flat side wall at x {@code wall} facing {@code side} (±1 along x), centred at z and height {@code y}. */
    void door(double wall, double z, int side, double y, double across, double up) {
        double outer = wall + side * .08;
        box(new double[]{Math.min(wall, outer), y - up, z - across}, new double[]{Math.max(wall, outer), y + up, z + across}, Material.MACHINE, false);
    }

    /** How far a burner's muffler hangs under the floor, m. */
    static final double BURNER_DROP = 1.05;

    /**
     * A heater's burners under its floor ({@code y0}) at {@code at}, {x, z} each: the air register drum ({@code register}
     * across, its louvre band and lever) under the floor, the muffler drum (1.3 times as wide, open underneath) under it,
     * and the gas riser from the fuel header at {@code yFuel} up through the muffler.
     */
    void burners(double[][] at, double register, double y0, double yFuel, double pipe) {
        double muffler = 1.3 * register, low = y0 - BURNER_DROP;
        for (var p : at) {
            var up = Frame.of(new double[]{p[0], 0, p[1]}, 1, 1);
            mesh.cylinder(up, register, y0 - .45, y0, 12, Material.MACHINE);
            mesh.ring(up, register, register + .03, y0 - .32, y0 - .2, 12, 3, Material.FLANGE);
            mesh.bar(up.at(0, register + .02, y0 - .26), up.at(0, register + .2, y0 - .16), .03, new double[]{0, 0, 1}, Material.ACTUATOR);
            mesh.prism(up, muffler, low, y0 - .45, 12, Material.MACHINE);
            mesh.annulus(up, register, muffler, y0 - .45, 12, 1, Material.MACHINE);
            mesh.annulus(up, .55 * muffler, muffler, low, 12, -1, Material.MACHINE);
            mesh.annulus(up, 0, .55 * muffler, low + .05, 12, -1, Material.OPENING);
            mesh.prism(up, .7 * pipe, yFuel, low + .05, 8, Material.PIPE);
            solids.add(Solid.rod(new double[]{p[0], 0, p[1]}, 1, low, y0, muffler, 0));
        }
    }

    /**
     * A heater's convection section from x0 to x1 by z0 to z1, from {@code bottom} (the radiant roof) to {@code top}, its
     * top drawn (the breeching opens from it), a panelled header box on each end; it encloses its inside. It is built of
     * equal shop modules of {@link #MODULE} at most, stacked on bolted flanged joints ({@link #joint}), standing on the roof
     * on one and carrying the breeching on another, with corner columns (owner request 2026-10-18, photo survey). Its tubes
     * stay clean on fuel gas: no soot blowers (owner decision 2026-10-18; they were red gearboxes every metre up its side).
     */
    void convection(double x0, double x1, double z0, double z1, double bottom, double top) {
        mesh.box(new double[]{x0, bottom, z0}, new double[]{x1, top, z1}, ALL_FACES & ~4, Material.HEATER);
        solids.add(Solid.box(new double[]{x0, bottom, z0}, new double[]{x1, top, z1}));
        volumes.add(Solid.box(new double[]{x0 + .05, bottom, z0 + .05}, new double[]{x1 - .05, top - .05, z1 - .05}));
        int count = Math.max(2, (int) Math.round((x1 - x0 - .2) / .9)), modules = (int) Math.ceil((top - bottom) / MODULE - 1e-9);
        double h = (top - bottom) / modules, hx0 = x0 + .1, hx1 = x1 - .1;
        for (int end = 0; end < 2; end++) {
            double[] lo = {hx0, bottom + .2, end == 0 ? z0 - HEADER_BOX : z1}, hi = {hx1, top - .2, end == 0 ? z0 : z1 + HEADER_BOX};
            mesh.box(lo, hi, ALL_FACES & ~(end == 0 ? 32 : 16), Material.HEATER);
            solids.add(Solid.box(lo, hi));
            // Each module's own header panels.
            for (int m = 0; m < modules; m++) {
                double[] plo = lo.clone(), phi = hi.clone();
                plo[1] = Math.max(lo[1], bottom + m * h + .12);
                phi[1] = Math.min(hi[1], bottom + (m + 1) * h - .12);
                if (phi[1] - plo[1] > .3) panels(plo, phi, 2, end == 0 ? -1 : 1, 0, count);
            }
        }
        // The joints: on the roof and under the breeching round the casing, between modules round the header boxes too.
        double[][] casing = {{x0, z0}, {x1, z0}, {x1, z1}, {x0, z1}};
        double[][] whole = {{x0, z0}, {hx0, z0}, {hx0, z0 - HEADER_BOX}, {hx1, z0 - HEADER_BOX}, {hx1, z0}, {x1, z0}, {x1, z1}, {hx1, z1}, {hx1, z1 + HEADER_BOX},
                {hx0, z1 + HEADER_BOX}, {hx0, z1}, {x0, z1}};
        joint(casing, bottom, false);
        for (int m = 1; m < modules; m++) joint(whole, bottom + m * h, true);
        joint(casing, top, true);
        for (double[] c : casing) {
            double cx = c[0] == x0 ? x0 - .08 : x1, cz = c[1] == z0 ? z0 - .08 : z1;
            box(new double[]{cx, bottom, cz}, new double[]{cx + .08, top, cz + .08}, Material.HEATER, false);
        }
        for (double[] p : new double[][]{{x0, z0, x1, z0}, {x1, z0, x1, z1}, {x1, z1, x0, z1}, {x0, z1, x0, z0}}) segment(new double[]{p[0], top, p[1]}, new double[]{p[2], top, p[3]});
    }

    /** A heater casing module's largest height, m: about what goes by road (the photos' convection modules are 2 to 3 m). */
    static final double MODULE = 3;
    /**
     * Spacing of the nuts drawn on a heater's field joints, m: real bolts stand 0.15 to 0.3 m apart; a row at 0.75 m reads
     * as bolted at a block's scale for half the quads of one at 0.5 m (each nut is five).
     */
    static final double BOLT_PITCH = .75;

    /**
     * A bolted field joint at height {@code y} round a casing's outline (x, z corners of a rectilinear polygon, either
     * way round): a flange frame 0.1 m out over the joint and, where two modules meet ({@code pair}), one under it 0.02 m
     * apart, each 0.07 m deep; nuts on the upper frame every {@link #BOLT_PITCH}. An edge's bar reaches past an outer
     * corner and stops short of an inner one, so the bars meet without overlapping.
     */
    void joint(double[][] outline, double y, boolean pair) {
        int n = outline.length;
        double area = 0;
        for (int i = 0; i < n; i++) area += outline[i][0] * outline[(i + 1) % n][1] - outline[(i + 1) % n][0] * outline[i][1];
        double w = .1, turn = Math.signum(area);
        for (int i = 0; i < n; i++) {
            double[] p = outline[i], q = outline[(i + 1) % n], r = outline[(i + 2) % n];
            double dx = Math.signum(q[0] - p[0]), dz = Math.signum(q[1] - p[1]), ex = Math.signum(r[0] - q[0]), ez = Math.signum(r[1] - q[1]);
            // Outward of the edge (x, z): to its right going round with positive area; an outer corner turns left.
            double ox = turn * dz, oz = -turn * dx;
            boolean outer = turn * (dx * ez - dz * ex) > 0;
            double length = Math.abs(q[0] - p[0]) + Math.abs(q[1] - p[1]), to = length + (outer ? w : -w);
            if (to < .02) continue;
            for (int k = 0; k < (pair ? 2 : 1); k++) {
                double lo = k == 0 ? y + (pair ? .01 : 0) : y - .08, hi = lo + .07;
                double[] a = {p[0], p[1]}, b = {p[0] + dx * to + ox * w, p[1] + dz * to + oz * w};
                box(new double[]{Math.min(a[0], b[0]), lo, Math.min(a[1], b[1])}, new double[]{Math.max(a[0], b[0]), hi, Math.max(a[1], b[1])}, Material.HEATER, false);
            }
            int bolts = (int) Math.floor(to / BOLT_PITCH);
            for (int k = 0; k < bolts; k++) {
                double t = to * (k + .5) / bolts, x = p[0] + dx * t + ox * w / 2, z = p[1] + dz * t + oz * w / 2, top = y + (pair ? .08 : .07);
                mesh.box(new double[]{x - .025, top, z - .025}, new double[]{x + .025, top + .03, z + .025}, ALL_FACES & ~4, Material.BOLT);
            }
        }
    }

    /**
     * A bolted field joint between two casing panels from {@code p} to {@code q} on a wall facing {@code out} (owner
     * request 2026-10-18; photo survey: shop-built panels bolted on site on flanged seams at the casing columns): two
     * flange bars 0.12 m proud and 0.09 m wide either side of a 0.02 m split, nuts every {@link #BOLT_PITCH} on the outer
     * face of each ({@code bolts}).
     */
    void seam(double[] p, double[] q, double[] out, boolean bolts) {
        var along = sub(q, p);
        double length = Math.sqrt(dot(along, along));
        var u = scale(along, 1 / length);
        var across = unit(cross(out, u));
        var mid = scale(plus(p, q), .5);
        int count = bolts ? (int) Math.floor(length / BOLT_PITCH) : 0;
        for (int side = -1; side <= 1; side += 2) {
            var bar = plus(plus(mid, scale(out, .06)), scale(across, side * .055));
            mesh.block(bar, scale(out, .06), scale(u, length / 2), scale(across, .045), ALL_FACES & ~1, Material.HEATER);
            for (int k = 0; k < count; k++) {
                var nut = plus(plus(bar, scale(across, side * .06)), scale(u, ((k + .5) / count - .5) * length));
                mesh.block(nut, scale(out, .025), scale(u, .025), scale(across, .015), ALL_FACES & ~(side > 0 ? 16 : 32), Material.BOLT);
            }
        }
    }

    /**
     * Bolted panels on the face of the box (lo, hi) across frame axis {@code axis} on its {@code sign} side: {@code count}
     * of them side by side along frame axis {@code split} (the other in-face axis is their height), 0.02 m proud, 0.04 m
     * apart and 0.06 m in from the box's edges, each with a lifting handle.
     */
    void panels(double[] lo, double[] hi, int axis, int sign, int split, int count) {
        int other = 3 - axis - split;
        double face = sign > 0 ? hi[axis] : lo[axis], from = lo[split] + .06, to = hi[split] - .06, size = (to - from - .04 * (count - 1)) / count;
        for (int k = 0; k < count; k++) {
            double[] p = new double[3], q = new double[3];
            p[axis] = Math.min(face, face + sign * .02);
            q[axis] = Math.max(face, face + sign * .02);
            p[split] = from + k * (size + .04);
            q[split] = p[split] + size;
            p[other] = lo[other] + .06;
            q[other] = hi[other] - .06;
            box(p, q, Material.HEATER, false);
            double[] h0 = new double[3], h1 = new double[3];
            h0[axis] = h1[axis] = face + sign * .06;
            h0[split] = h1[split] = (p[split] + q[split]) / 2;
            h0[other] = (p[other] + q[other]) / 2 - .1;
            h1[other] = h0[other] + .2;
            mesh.bar(h0, h1, .03, axis == 1 ? new double[]{1, 0, 0} : new double[]{0, 1, 0}, Material.PIPE);
        }
    }

    /**
     * A stack's breeching: the duct's rectangle x0 to x1 by z0 to z1 at the stack's foot (a heater's convection section's
     * top, a boiler's outlet duct), drawn as a square-to-round transition up to the stack's neck.
     */
    record Breeching(double x0, double x1, double z0, double z1) {
        /** The stack's neck over a breeching whose top is at {@code base}, for a stack of {@code od}: 0.8 m up, more where the duct is much wider than the stack (a transition of about 50°). */
        double neck(double base, double od) {
            return base + Math.max(.8, .8 * (Math.max(x1 - x0, z1 - z0) / 2 - od / 2));
        }
    }

    /** A heater's stack's most height over its neck, m. */
    static final double MAX_STACK = 30;

    /** A stack's top over a neck at {@code neck}: twice the height of the roof {@code roof} it rises from over grade (the plume clears the building's wake), and at least 6 m over the neck. */
    static double stackTop(double neck, double roof) {
        return Math.max(neck + 6, 2 * roof);
    }

    /**
     * A steel stack of {@code od} and {@code n} sides round the vertical line through (x, z) from {@code base} to
     * {@code top}: from its {@code breeching}'s rectangle up to its neck ({@link Breeching#neck}; null: none, the stack
     * starts at {@code base} on its foundation), its shell, where {@code damper} the damper's band half a metre over the
     * neck with its actuator and lever, a band at each joint between its 8 m shop sections and one under its top, open at
     * the top, a lightning rod on its rim (NFPA 780). Its bands and breeching are in {@code paint}; it collides as drawn.
     */
    void stack(double x, double z, double od, int n, double base, double top, Material paint, Breeching breeching, boolean damper) {
        double r = od / 2, neck = breeching == null ? base : breeching.neck(base, od);
        if (breeching != null) transition(1, breeching.x0(), breeching.x1(), breeching.z0(), breeching.z1(), base, x, z, r, neck, n, paint, false);
        var up = Frame.of(new double[]{x, 0, z}, 1, 1);
        mesh.prism(up, r, neck, top, n, Material.SHELL);
        for (double y = neck + 8; y < top - 2; y += 8) band(up, r, y, n, paint);
        band(up, r, top - .6, n, paint);
        if (damper) {
            band(up, r, neck + .5, n, paint);
            box(new double[]{x + r + .05, neck + .38, z - .15}, new double[]{x + r + .35, neck + .68, z + .15}, Material.ACTUATOR, false);
            mesh.bar(new double[]{x + r + .2, neck + .68, z}, new double[]{x + r + .45, neck + 1.05, z}, .04, new double[]{0, 0, 1}, Material.ACTUATOR);
        }
        mesh.annulus(up, .92 * r, r, top, n, 1, Material.SHELL);
        mesh.bore(up, .92 * r, top - .3, top, n, Material.SHELL);
        mesh.annulus(up, 0, .92 * r, top - .3, n, 1, Material.OPENING);
        mesh.bar(new double[]{x + .96 * r, top, z}, new double[]{x + .96 * r, top + 1.2, z}, .03, new double[]{1, 0, 0}, Material.PIPE);
        if (breeching != null) solids.add(Solid.box(new double[]{breeching.x0(), base, breeching.z0()}, new double[]{breeching.x1(), neck, breeching.z1()}));
        solids.add(Solid.rod(new double[]{x, 0, z}, 1, neck, top, r, 0));
        round(x, z, neck, top, r, 1);
    }

    // ---- Parts ------------------------------------------------------------------------------------------------------

    /** The middle of a box whose width is the least odd whole number of cells holding {@code half} each side: the centre line runs through cell centres. */
    static double centre(double half) {
        int width = (int) Math.ceil(2 * half - 1e-9);
        if (width % 2 == 0) width++;
        return width / 2.0;
    }

    /**
     * A nozzle on top ({@code sign} +1) or under (-1) a body lying along z (its axis at (x, y), radius {@code r} to its
     * {@code n} vertices) at station {@code z}: its neck starts under the surface where a pipe's largest neck's edge meets
     * it (owner decision 2026-10-21: a 1 m pipe has a 1 m root), its flange face on the next block face
     * {@link #NECK} beyond.
     */
    static Nozzle radial(Role role, double x, double y, double r, int n, double z, int sign, double defaultOd) {
        double start = rootUnder(r, n);
        double surface = y + sign * r, plane = sign > 0 ? Math.ceil(surface + NECK) : Math.floor(surface - NECK);
        return new Nozzle(role, new double[]{x, y + sign * start, z}, 1, sign, Math.abs(plane - (y + sign * start)), defaultOd, 0);
    }

    /**
     * A nozzle on the front ({@code sign} -1, frame -z) or the back (+1) of a standing body (its axis at (x, z), radius
     * {@code r} to its {@code n} vertices) at height {@code y}: its neck starts under the surface where a pipe's largest
     * neck's edge meets it (owner decision 2026-10-21: a 1 m pipe has a 1 m root), its flange face on the next block face
     * {@link #NECK} beyond.
     */
    static Nozzle radialSide(Role role, double x, double y, double z, double r, int n, int sign, double defaultOd) {
        double start = rootUnder(r, n);
        double plane = sign > 0 ? Math.ceil(z + r + NECK) : Math.floor(z - r - NECK), base = z + sign * start;
        return new Nozzle(role, new double[]{x, y, base}, 2, sign, Math.abs(plane - base), defaultOd, 0);
    }

    /**
     * How far from the axis of a round body (radius {@code r} to its {@code n} vertices) a radial nozzle's neck starts:
     * where a pipe's largest neck's edge meets the flats, less 0.03 m (owner decision 2026-10-21: a 1 m pipe has a 1 m
     * root); on a body narrower than that, just across its axis.
     */
    static double rootUnder(double r, int n) {
        double apothem = r * DeterministicMath.cos(Math.PI / n), half = PipeSizes.MAX_OUTSIDE / 2;
        return Math.sqrt(Math.max(0, apothem * apothem - half * half)) - .03;
    }

    /**
     * The least a unit nozzle's flange face stands off where its neck at a pipe's largest leaves its body or its line, on
     * the next block face: that neck's hub and flange plate (0.21 m) and 0.03 m of neck, within the photo survey's 150 to
     * 300 mm (owner decisions 2026-10-21 and 2026-10-25: a 0.8 m pipe has a 0.8 m root, other components give way). It
     * replaces {@link EquipmentShape#STAND_OUT} on the units (under it a pipe's largest hub sat in the body, 2026-10-25);
     * a pump's casing nozzles keep that, blending into its castings ({@link #nozzleRoots}).
     */
    static final double NECK = PipeSizes.hub(PipeSizes.MAX_OUTSIDE) + PipeSizes.flangeThickness(PipeSizes.MAX_OUTSIDE) + .03;

    /** {@code v} held between {@code lo} and {@code hi}. */
    static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /**
     * Where a saddle {@code w} wide stands nearest {@code want} on a shell from {@code lo} to {@code hi} (along z) without
     * standing in the pipe block under a downward nozzle at any of {@code under}: half a block each side of the nozzle's
     * centre line and 0.05 m. {@code want} when no such place is on the shell.
     */
    static double saddleAt(double want, double w, double lo, double hi, double... under) {
        double best = want, gap = Double.MAX_VALUE, keep = .55 + w / 2;
        var candidates = new ArrayList<Double>(List.of(want));
        for (double z : under) {
            candidates.add(z - keep);
            candidates.add(z + keep);
        }
        for (double c : candidates) {
            if (c < lo + w / 2 - 1e-9 || c > hi - w / 2 + 1e-9 || Math.abs(c - want) >= gap) continue;
            boolean clear = true;
            for (double z : under) if (Math.abs(c - z) < keep - 1e-9) clear = false;
            if (clear) {
                best = c;
                gap = Math.abs(c - want);
            }
        }
        return best;
    }

    static double saddleWidth(double r) {
        return Math.max(.15, Math.min(.3, .12 * r + .1));
    }

    /**
     * A saddle {@code w} wide at station {@code z} under a body lying along z at (x, y) of radius {@code r}: a plate from
     * {@code floor} (the ground, a table, a skid) and its base plate.
     */
    void saddle(double z, double x, double y, double r, double w, double floor) {
        double across = .8 * r;
        box(new double[]{x - across, floor, z - w / 2}, new double[]{x + across, y - .6 * r, z + w / 2}, Material.SUPPORT, true);
        box(new double[]{x - across - .05, floor, z - w / 2 - .05}, new double[]{x + across + .05, floor + .04, z + w / 2 + .05}, Material.SUPPORT, false);
    }

    /** A round body lying along z at (x, y) from {@code lo} to {@code hi}: its rims are fixed edges, its sides outlined against the eye. */
    void round(double x, double y, double lo, double hi, double r) {
        round(x, y, lo, hi, r, 2);
    }

    /** A round body along frame axis {@code axis} through the point (x, y, z) less that axis, from {@code lo} to {@code hi}. */
    void round(double x, double y, double lo, double hi, double r, int axis) {
        round(axis == 2 ? new double[]{x, y, 0} : new double[]{x, 0, y}, axis, lo, hi, r);
    }

    /** A round body along frame axis {@code axis} through {@code centre} (its {@code axis} coordinate unused), from {@code lo} to {@code hi}. */
    void round(double[] centre, int axis, double lo, double hi, double r) {
        rounds.add(new double[]{centre[0], centre[1], centre[2], axis, r, lo, hi});
        int u = (axis + 1) % 3, v = (axis + 2) % 3, steps = 24;
        for (double at : new double[]{lo, hi})
            for (int k = 0; k < steps; k++) {
                double a0 = 2 * Math.PI * k / steps, a1 = 2 * Math.PI * (k + 1) / steps;
                double[] p = centre.clone(), q = centre.clone();
                p[axis] = at;
                q[axis] = at;
                p[u] += r * DeterministicMath.cos(a0);
                p[v] += r * DeterministicMath.sin(a0);
                q[u] += r * DeterministicMath.cos(a1);
                q[v] += r * DeterministicMath.sin(a1);
                segment(p, q);
            }
    }

    /**
     * A platform ({@link Access}): its deck {@code width} out from the wall along {@code run}, its walking surface
     * {@link Access#DECK} over {@code level}, and its handrail ({@link #rails}), open over the {@code gaps} where a ladder
     * is walked through. A player walks the deck; its outer edge is outlined.
     */
    void platform(Access.Run run, double level, double width, boolean closedStart, boolean closedEnd, double... gaps) {
        platform(run, level, width, Access.ALL_PARTS, closedStart, closedEnd, gaps);
    }

    /**
     * A {@link #platform(Access.Run, double, double, boolean, boolean, double...)} of {@code parts} ({@link Access#BRACKETS},
     * {@link Access#RAILED}, {@link Access#WALL_EDGE}, {@link Access#NARROW}): a deck on beams or posts has no brackets; one
     * not railed carries only the rails across its closed ends (a landing piece whose outer edge meets another deck); a
     * narrow one's closed ends are fenced {@link Access#NARROW_BAND} deep, so a player walks between them.
     */
    void platform(Access.Run run, double level, double width, int parts, boolean closedStart, boolean closedEnd, double... gaps) {
        Access.deck(mesh, run, level, width, parts, gaps);
        decks.add(new Deck(run, level, width, closedStart, closedEnd));
        double deck = level + Access.DECK, length = run.length();
        if (run instanceof Access.Arc arc) solids.add(Solid.ring(arc.cx(), arc.cz(), level, deck, arc.wall() + Access.GAP, arc.wall() + width, arc.from(), arc.to()));
        else solids.add(span(run.at(0, Access.GAP, level), run.at(length, width, deck)));
        double band = (parts & Access.NARROW) != 0 ? Access.NARROW_BAND : Access.RAIL_BAND;
        if ((parts & Access.RAILED) != 0) rails(run, level, width, band, closedStart, closedEnd, gaps);
        else rails(run, level, width, band, closedStart, closedEnd, 0, length);
        int count = Math.max(2, (int) Math.ceil(length / .5));
        for (int j = 0; j < count; j++) segment(run.at(length * j / count, width, deck), run.at(length * (j + 1) / count, width, deck));
    }

    /** A handrail alone {@code width} out from the wall along {@code run}, standing {@link Access#DECK} over {@code level} (a roof's edge): open at both ends. */
    void railing(Access.Run run, double level, double width) {
        rails(run, level, width, false, false);
    }

    /**
     * A deck's handrail along {@code run} but over the {@code gaps} (pairs of stations along it), each piece's posts at its
     * ends, a rail across each closed end of the run, also where a gap begins at that end (a stair landing beside a
     * walkway's end: in-game check 2026-10-18, the condensing turbine's walkway end was open over a 6 m drop); a player
     * meets the rails and closed ends as fences {@link Access#FENCE} high and {@link Access#RAIL_BAND} thick.
     */
    void rails(Access.Run run, double level, double width, boolean closedStart, boolean closedEnd, double... gaps) {
        rails(run, level, width, Access.RAIL_BAND, closedStart, closedEnd, gaps);
    }

    /** {@link #rails(Access.Run, double, double, boolean, boolean, double...)} with its closed ends fenced {@code band} deep along the run. */
    private void rails(Access.Run run, double level, double width, double band, boolean closedStart, boolean closedEnd, double... gaps) {
        double deck = level + Access.DECK, fence = deck + Access.FENCE, length = run.length();
        var pieces = Access.pieces(length, gaps);
        boolean startDrawn = false, endDrawn = false;
        for (int i = 0; i < pieces.size(); i++) {
            var piece = Access.part(run, pieces.get(i)[0], pieces.get(i)[1]);
            boolean start = closedStart && pieces.get(i)[0] == 0, end = closedEnd && pieces.get(i)[1] == length;
            startDrawn |= start;
            endDrawn |= end;
            Access.handrail(mesh, piece, level, width, start, end);
            if (piece instanceof Access.Arc arc) solids.add(Solid.ring(arc.cx(), arc.cz(), deck, fence, arc.wall() + width - Access.RAIL_BAND, arc.wall() + width, arc.from(), arc.to()));
            else solids.add(span(piece.at(0, width - Access.RAIL_BAND, deck), piece.at(piece.length(), width, fence)));
        }
        if (closedStart && !startDrawn) Access.endRail(mesh, run, 0, level, width);
        if (closedEnd && !endDrawn) Access.endRail(mesh, run, length, level, width);
        if (run instanceof Access.Arc arc) {
            // Inside the deck's last band, as on a straight run: the band is its depth long at its inner edge.
            double wall = arc.wall(), angle = band / (wall + Access.GAP);
            if (closedStart) solids.add(Solid.ring(arc.cx(), arc.cz(), deck, fence, wall + Access.GAP, wall + width, arc.from(), arc.from() + angle));
            if (closedEnd) solids.add(Solid.ring(arc.cx(), arc.cz(), deck, fence, wall + Access.GAP, wall + width, arc.to() - angle, arc.to()));
        } else {
            if (closedStart) solids.add(span(run.at(0, Access.GAP, deck), run.at(band, width, fence)));
            if (closedEnd) solids.add(span(run.at(length - band, Access.GAP, deck), run.at(length, width, fence)));
        }
    }

    /**
     * A caged ladder ({@link Access#ladder}) whose rung plane's middle stands at (x, z), facing {@code out} from a wall
     * {@code back} behind it, from {@code base} to {@code top}; one {@code walkThrough} is climbed facing the deck it
     * lands on and left between its rails, so its rung plane stops at the landing.
     */
    void ladder(double x, double z, double[] out, double back, double base, double top, boolean walkThrough) {
        var climb = new Access.Climb(x, z, out[0], out[2], base, top, walkThrough ? top - Access.LANDING : top);
        Access.ladder(mesh, climb, back);
        ladders.add(climb);
    }

    /**
     * A caged climb up a flat face from walking level {@code base} to the deck whose walking level is {@code top}, walked
     * through onto it: one ladder at station {@code upper} of {@code run} (its start on the face, along it, out from it)
     * {@link Access#GAP} out where the climb is {@link Access#MAX_CLIMB} or less (with that deck's grating). A longer one
     * is split ({@link Access#climbs}; owner decision C1 2026-10-18, the old OSHA 1910.27(d)(2): a landing every 30 ft of a
     * caged ladder, the sections offset) by a rest platform {@link Access#MIN_DECK} out along the run (on past its ends
     * where a section stands nearer than its opening and a fence band), railed on every
     * side (the face is open steelwork) but at each section: the lower one from base just outside its outer edge at
     * station {@code lower}, walked through onto it; the upper one from its deck at the face at {@code upper}, so a fall
     * from the upper section ends on the rest. The rest stands on four posts {@code post} square on base plates with
     * beams under its ends, or (post 0) on brackets off the face, the member there the caller's. Returns the rests' deck
     * levels: one at most (a climb under 18 m).
     */
    List<Double> climb(Access.Line run, double base, double top, double lower, double upper, double post) {
        double[] out = run.out();
        var stops = Access.climbs(0, List.of(), (int) Math.ceil(top - base - Access.DECK - 1e-9));
        if (stops.size() > 2) throw new IllegalStateException("a climb of " + (top - base) + " m needs " + (stops.size() - 1) + " rests");
        var rests = new ArrayList<Double>();
        double from = base, w = Access.MIN_DECK, length = run.length();
        if (stops.size() == 2) {
            // The rest reaches past each section by its opening and a closed end's band, so a climber passes between its ends
            // (2026-10-25: a cyclone's rest ended 0.22 m beside its ladder's middle).
            double need = Access.WALK_THROUGH + Access.RAIL_BAND, s0 = Math.min(0, Math.min(lower, upper) - need);
            var deck = (Access.Line) Access.part(run, s0, Math.max(length, Math.max(lower, upper) + need));
            double rest = base + stops.getFirst() - Access.DECK, beam = .2, low = lower - s0, high = upper - s0, span = deck.length();
            platform(deck, rest, w, post > 0 ? Access.RAILED | Access.WALL_EDGE : Access.ALL_PARTS, true, true, low - Access.WALK_THROUGH, low + Access.WALK_THROUGH);
            rails(new Access.Line(deck.at(0, w, 0), deck.direction(), neg(out), span), rest, w - Access.GAP, false, false, high - Access.WALK_THROUGH, high + Access.WALK_THROUGH);
            if (post > 0)
                for (double s : new double[]{0, span - post}) {
                    for (double off : new double[]{Access.GAP + post / 2, w - post / 2}) {
                        var p = deck.at(s + post / 2, off, 0);
                        post(p[0], p[2], 0, rest - beam, post, true);
                    }
                    double[] p = deck.at(s, Access.GAP, rest - beam), q = deck.at(s + post, w, rest);
                    beam(new double[]{Math.min(p[0], q[0]), p[1], Math.min(p[2], q[2])}, new double[]{Math.max(p[0], q[0]), q[1], Math.max(p[2], q[2])});
                }
            var foot = run.at(lower, w + Access.GAP, 0);
            ladder(foot[0], foot[2], out, Access.GAP, base, rest + Access.DECK + Access.LANDING, true);
            rests.add(rest);
            from = rest + Access.DECK;
        }
        var foot = run.at(upper, Access.GAP, 0);
        ladder(foot[0], foot[2], out, Access.GAP, from, top + Access.LANDING, true);
        return rests;
    }

    /**
     * A straight stair flight ({@link Stair}) from walking level {@code base} at its foot's centre (x, z) up to {@code top},
     * rising along frame axis {@code axis} (0 or 2) in direction {@code sign}, {@code width} across its stringers: grating
     * treads {@link #STAIR_GOING} deep, rises of at most {@link #STAIR_RISE}, on two channel stringers, a handrail and mid
     * rail each side 0.9 m over the nosings (OSHA 1910.29). Its last tread is level with {@code top}, where a deck or
     * landing goes on beyond the flight. A player steps up the treads between the side fences, each
     * {@link Access#NARROW_BAND} deep (a 0.9 m flight keeps room for a player). Returns the flight's length.
     */
    double stair(double x, double z, int axis, int sign, double width, double base, double top) {
        int steps = (int) Math.ceil((top - base) / STAIR_RISE - 1e-9);
        var f = new Stair(x, z, axis, sign, width, base, top, steps);
        stairs.add(f);
        double rise = f.rise(), half = width / 2, length = f.length();
        for (int k = 1; k <= steps; k++) {
            double s0 = (k - 1) * STAIR_GOING, s1 = k * STAIR_GOING, y = base + k * rise;
            double[] p = f.at(s0, -half + .06, y - .04), q = f.at(s1, half - .06, y);
            mesh.box(new double[]{Math.min(p[0], q[0]), p[1], Math.min(p[2], q[2])}, new double[]{Math.max(p[0], q[0]), q[1], Math.max(p[2], q[2])}, ALL_FACES, Material.GRATING);
            // The tread's step is filled down to the one below, so the game's collision (1/8-block voxels) steps a player up it.
            solids.add(span(f.at(s0, -half, y - rise), f.at(s1, half, y)));
            for (int side = -1; side <= 1; side += 2)
                solids.add(span(f.at(s0, side * half, y), f.at(s1, side * (half - Access.NARROW_BAND), y + Access.FENCE)));
        }
        double[] up = {0, 1, 0}, rising = axis == 0 ? new double[]{sign, 0, 0} : new double[]{0, 0, sign};
        for (int side = -1; side <= 1; side += 2) {
            // The stringer, its ends cut short of the foot's level and of the landing's deck.
            double[] a = f.at(0, side * (half - .03), base + .1), b = f.at(length, side * (half - .03), top - .1);
            double dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2], l = Math.sqrt(dx * dx + dy * dy + dz * dz), h = Math.sqrt(dx * dx + dz * dz);
            double[] centre = {(a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2}, along = {dx / 2, dy / 2, dz / 2};
            double[] depth = {-dx / h * dy / l * .1, h / l * .1, -dz / h * dy / l * .1}, across = f.at(0, side * .03, 0);
            mesh.block(centre, along, depth, new double[]{across[0] - x, 0, across[2] - z}, Material.STEELWORK);
            // Handrail and mid rail from over the first nosing to over the landing's edge, posts at both ends and 1.8 m apart.
            double r = side * (half - .03);
            for (double over : new double[]{.9, .45})
                mesh.bar(f.at(0, r, base + rise + over), f.at(length, r, top + over), Access.BAR, up, NO_ENDS, Material.HANDRAIL);
            for (double s : Access.posts(length)) {
                double nosing = base + rise + (top - base - rise) * s / length;
                mesh.bar(f.at(s, r, nosing - .04), f.at(s, r, nosing + .9 + Access.BAR / 2), Access.BAR, rising, NO_ENDS, Material.HANDRAIL);
            }
            segment(f.at(0, side * half, base + rise), f.at(length, side * half, top));
        }
        return length;
    }

    /**
     * A stair wound round a body of revolution ({@link Access.WoundStair}, planned by {@link Access.WoundStair#wind}): drawn,
     * collided with, walked and outlined, as a straight flight is ({@link #stair}).
     */
    Access.WoundStair woundStair(Access.WoundStair stair) {
        stair.draw(mesh);
        solids.addAll(stair.solids());
        woundStairs.add(stair);
        edges.addAll(stair.edges());
        return stair;
    }

    // ---- Access parts -----------------------------------------------------------------------------------------------

    /** A ring platform round a round body ({@link #ringAccess}): its deck level, the angle of the ladder up to it, and the angles its deck spans. */
    record Ring(double level, double ladder, double from, double to) {
    }

    /**
     * Ring platforms round a round body whose axis stands at (x, z), its wall (cladding, casing columns) {@code wall} from
     * the axis, each deck {@code width} wide ({@link Access#MIN_DECK} where a caged ladder starts on it): for each of
     * {@code rings}, rising, its caged ladder at its {@code ladder} angle from the deck below (the first from {@code base},
     * the ground or a deck) up to it, and its deck from {@code from} to {@code to} (from +x towards +z) but where that
     * ladder's cage comes up through it ({@link Access#clearance}). The deck stays open beside the ladder (the climber
     * steps off sideways) and is railed across its own ends; a whole ring (2π) is open at the ladder only; a piece under
     * 0.8 m along the wall is left out unless a ladder lands on it or starts from it. The next ring's ladder stands on
     * this one's deck (API 560 12.4.1 and OSHA 1910.28:
     * a heater's, a column's, a reactor's access; the column's pattern, {@link ColumnStructure#plan}). Returns each ring's
     * deck arcs.
     */
    List<List<Access.Arc>> ringAccess(double x, double z, double wall, double width, double base, List<Ring> rings) {
        double clear = Access.clearance(wall), edge = wall + width, step = Access.Arc.step(edge);
        var out = new ArrayList<List<Access.Arc>>();
        for (int k = 0; k < rings.size(); k++) {
            var ring = rings.get(k);
            verticalLadder(x, z, wall, ring.ladder(), k == 0 ? base : rings.get(k - 1).level() + Access.DECK, ring.level() + Access.DECK + Access.LANDING, false);
            // The deck's pieces {from, to, closed at from, closed at to} once the ladder's sector is taken out.
            var pieces = new ArrayList<double[]>();
            if (ring.to() - ring.from() >= 2 * Math.PI - 1e-9) pieces.add(new double[]{ring.ladder() + clear, ring.ladder() + 2 * Math.PI - clear, 0, 0});
            else {
                pieces.add(new double[]{ring.from(), ring.to(), 1, 1});
                for (int j = -1; j <= 1; j++) {
                    double c = ring.ladder() + 2 * Math.PI * j, s0 = c - clear, s1 = c + clear;
                    var left = new ArrayList<double[]>();
                    for (var p : pieces) {
                        if (s1 <= p[0] || s0 >= p[1]) {
                            left.add(p);
                            continue;
                        }
                        if (s0 > p[0]) left.add(new double[]{p[0], s0, p[2], 0});
                        if (s1 < p[1]) left.add(new double[]{s1, p[1], 0, p[3]});
                    }
                    pieces = left;
                }
            }
            var arcs = new ArrayList<Access.Arc>();
            for (var p : pieces) {
                // A piece too short to stand on beside its end rail is left out, but not one this ring's ladder lands on
                // (an end open beside it) or the next ring's starts from.
                boolean ladder = p[2] == 0 || p[3] == 0;
                for (int j = -1; j <= 1 && k + 1 < rings.size(); j++) {
                    double t = rings.get(k + 1).ladder() + 2 * Math.PI * j;
                    ladder |= t >= p[0] && t <= p[1];
                }
                if (!ladder && (p[1] - p[0]) * (wall + Access.GAP) < .8) continue;
                var arc = new Access.Arc(x, z, wall, p[0], p[1], edge, step);
                platform(arc, ring.level(), width, p[2] > 0, p[3] > 0);
                arcs.add(arc);
            }
            out.add(arcs);
        }
        return out;
    }

    /**
     * A rectangular grating deck from x0 to x1 by z0 to z1 at {@code level} on beams (an operating deck, a structure's
     * floor) with square openings of half side {@code half} centred at {@code centres} (rising, inside the deck) along a
     * row on frame axis {@code axis} (0 x, 2 z) at {@code row} on the other: two long strips either side of the row, closed
     * at their ends, pieces between the openings and at the row's ends, all {@code parts} ({@link Access#BRACKETS} where
     * they hang from a wall, {@link Access#RAILED} on the deck's outer edges, the pieces between the openings never;
     * {@link Access#WALL_EDGE} the faces round the openings, for openings left open). Where {@code sides} is over 0 each
     * opening is filled round a round body of radius {@code body} ({@link #fill}). The handrail is open over {@code gaps}
     * (pairs of frame coordinates along each edge: x on the -z (0) and +z (2) edges, z on the +x (1) and -x (3) ones; null:
     * none); an opening on a row's end edge that reaches past the pieces there opens the strip's end too. A caged ladder
     * comes up through the {@code hole} ({side, from, to}: the strip on the row's low (-1) or high (+1) side, from and to
     * along the row; null: none), where that strip is laid either side, open towards it, its handrail run on over it.
     */
    void squareDeck(double x0, double x1, double z0, double z1, double level, int axis, double row, double half, double[] centres, double body, int sides, int parts,
                    double[][] gaps, double[] hole) {
        boolean alongX = axis == 0;
        double u0 = alongX ? x0 : z0, u1 = alongX ? x1 : z1, v0 = alongX ? z0 : x0, v1 = alongX ? z1 : x1, g = Access.GAP;
        double[] du = alongX ? new double[]{1, 0, 0} : new double[]{0, 0, 1}, dv = alongX ? new double[]{0, 0, 1} : new double[]{1, 0, 0};
        // The edges' gap lists: the strips' sides (at v0 and v1) and the row's ends (at u0 and u1).
        double[] lowV = edgeGaps(gaps, alongX ? 0 : 3, -u0), highV = edgeGaps(gaps, alongX ? 2 : 1, -u0);
        double[] lowU = edgeGaps(gaps, alongX ? 3 : 0, -(row - half)), highU = edgeGaps(gaps, alongX ? 1 : 2, -(row - half));
        int edges = parts & ~Access.WALL_EDGE, inner = parts & ~Access.RAILED;
        double first = centres[0] - half, last = centres[centres.length - 1] + half;
        double lowWidth = row - half + g - v0, highWidth = v1 - (row + half - g);
        for (int s = -1; s <= 1; s += 2) {
            double width = s < 0 ? lowWidth : highWidth;
            if (width <= g + .1) continue;
            var run = new Access.Line(deckPoint(alongX, u0, row + s * (half - g)), du, s < 0 ? neg(dv) : dv, u1 - u0);
            boolean closedStart = !reaches(lowU, s < 0 ? -(row - half) + v0 : 2 * half, s < 0 ? 0 : v1 - (row - half));
            boolean closedEnd = !reaches(highU, s < 0 ? -(row - half) + v0 : 2 * half, s < 0 ? 0 : v1 - (row - half));
            var strip = s < 0 ? lowV : highV;
            if (hole == null || hole[0] != s) {
                platform(run, level, width, edges, closedStart, closedEnd, strip);
                continue;
            }
            double h0 = hole[1] - u0, h1 = hole[2] - u0, length = u1 - u0;
            var beyond = strip.clone();
            for (int k = 0; k < beyond.length; k++) beyond[k] -= h1;
            platform(Access.part(run, 0, h0), level, width, edges, closedStart, false, strip);
            platform(Access.part(run, h1, length), level, width, edges, false, closedEnd, beyond);
            if ((parts & Access.RAILED) != 0) railing(Access.part(run, h0, h1), level, width);
        }
        if (first - u0 > .1) platform(new Access.Line(deckPoint(alongX, first + g, row - half), dv, neg(du), 2 * half), level, first + g - u0, parts, false, false, lowU);
        if (u1 - last > .1) platform(new Access.Line(deckPoint(alongX, last - g, row - half), dv, du, 2 * half), level, u1 - last + g, parts, false, false, highU);
        for (int j = 0; j + 1 < centres.length; j++) {
            double from = centres[j] + half, to = centres[j + 1] - half;
            if (to - from > .1) platform(new Access.Line(deckPoint(alongX, from - g, row - half), dv, du, 2 * half), level, to - from + g, inner, false, false);
        }
        for (double c : centres) {
            if ((parts & Access.WALL_EDGE) != 0)
                for (int s = -1; s <= 1; s += 2) {
                    double v = row + s * half;
                    mesh.flat(List.of(deckPoint(alongX, c - half, v, level), deckPoint(alongX, c + half, v, level), deckPoint(alongX, c + half, v, level + Access.DECK),
                            deckPoint(alongX, c - half, v, level + Access.DECK)), scale(dv, -s), Material.GRATING);
                }
            if (sides > 0) fill(alongX ? c : row, alongX ? row : c, half, body, sides, level);
        }
    }

    /** The gaps of {@code gaps}' edge {@code edge} as stations from its run's start: frame coordinates moved by {@code shift}. */
    private static double[] edgeGaps(double[][] gaps, int edge, double shift) {
        if (gaps == null || gaps[edge] == null) return new double[0];
        var out = gaps[edge].clone();
        for (int k = 0; k < out.length; k++) out[k] += shift;
        return out;
    }

    /** Whether any of the stations {@code gaps} (pairs) opens over the stretch {@code from} to {@code to} of the same run (a strip's end beyond a row's end piece). */
    private static boolean reaches(double[] gaps, double from, double to) {
        for (int k = 0; k + 1 < gaps.length; k += 2) if (gaps[k] < Math.max(from, to) && gaps[k + 1] > Math.min(from, to)) return true;
        return false;
    }

    /** A square deck's frame point at u along its row and v across it, at height {@code y}. */
    private static double[] deckPoint(boolean alongX, double u, double v, double y) {
        return alongX ? new double[]{u, y, v} : new double[]{v, y, u};
    }

    private static double[] deckPoint(boolean alongX, double u, double v) {
        return deckPoint(alongX, u, v, 0);
    }

    /**
     * Grating between a round body (radius {@code body} about the vertical line through (x, z)) and the square opening of
     * half side {@code half} round it in a deck at {@code level}: quads from the body's polygon of {@code sides} (a
     * multiple of 8, so the square's corners are its rays' ends; angle 0 towards +x, as a vertical prism's of a multiple of
     * 4) out to the square along its radii, drawn on top and underneath (its edge along the body is inside the body and
     * never seen); it collides as a ring out to the square's corners, cut at its sides (the strips round the opening
     * collide beyond them).
     */
    void fill(double x, double z, double half, double body, int sides, double level) {
        double top = level + Access.DECK;
        for (int i = 0; i < sides; i++) {
            double t0 = 2 * Math.PI * i / sides, t1 = 2 * Math.PI * (i + 1) / sides;
            double c0 = DeterministicMath.cos(t0), s0 = DeterministicMath.sin(t0), c1 = DeterministicMath.cos(t1), s1 = DeterministicMath.sin(t1);
            double q0 = half / Math.max(Math.abs(c0), Math.abs(s0)), q1 = half / Math.max(Math.abs(c1), Math.abs(s1));
            for (double y : new double[]{level, top})
                mesh.flat(List.of(new double[]{x + body * c0, y, z + body * s0}, new double[]{x + body * c1, y, z + body * s1}, new double[]{x + q1 * c1, y, z + q1 * s1},
                        new double[]{x + q0 * c0, y, z + q0 * s0}), new double[]{0, y == top ? 1 : -1, 0}, Material.GRATING);
        }
        // Out to the square's corners and no further: beyond its sides the strips collide, or a ladder comes up through
        // a hole in one (a climber's head met the ring there, 2026-10-25).
        solids.add(Solid.ring(x, z, level, top, body, half * Math.sqrt(2), 0, 0).clip(new double[]{x - half, level, z - half}, new double[]{x + half, top, z + half}));
    }

    /** A concrete table's column pads: their height, m (a pedestal's grout and base, ACI 351.3 practice). */
    static final double PAD = .15;

    /**
     * A reinforced-concrete table (a turbine's or compressor's foundation deck, a coker's drum table; ACI 351.3): its slab
     * from x0 to x1 by z0 to z1, {@code slab} thick under its top {@code top}, cut round its {@code holes} ({x0, z0, x1, z1}
     * each, inside the slab and apart), in bands between the holes' edges along z and pieces between them; each piece's top
     * and underside, the slab's four outer faces and the holes' walls are drawn, no face between two pieces. Square columns
     * {@code column} across at each of {@code posts} ({x, z}) stand on pads {@link #PAD} high and 0.2 m wider up to its
     * underside. Slab, columns and pads collide; slab and columns enclose their inside.
     */
    void concreteTable(double x0, double x1, double z0, double z1, double top, double slab, List<double[]> holes, double column, List<double[]> posts) {
        double y1 = top, y0 = top - slab, h = column / 2, pad = h + .1;
        for (var p : posts) {
            box(new double[]{p[0] - pad, 0, p[1] - pad}, new double[]{p[0] + pad, PAD, p[1] + pad}, ALL_FACES & ~4, Material.CONCRETE, true);
            box(new double[]{p[0] - h, PAD, p[1] - h}, new double[]{p[0] + h, y0, p[1] + h}, ALL_FACES & ~(4 | 8), Material.CONCRETE, true);
            if (column > .2) volumes.add(Solid.box(new double[]{p[0] - h + .05, PAD, p[1] - h + .05}, new double[]{p[0] + h - .05, y0, p[1] + h - .05}));
        }
        var cuts = new ArrayList<Double>(List.of(z0, z1));
        for (var hole : holes)
            for (double z : new double[]{hole[1], hole[3]}) if (z > z0 && z < z1) cuts.add(z);
        cuts.sort(Double::compare);
        for (int b = 0; b + 1 < cuts.size(); b++) {
            double za = cuts.get(b), zb = cuts.get(b + 1);
            if (zb - za < 1e-6) continue;
            var spans = new ArrayList<double[]>();
            spans.add(new double[]{x0, x1});
            for (var hole : holes) {
                if (hole[1] >= zb - 1e-9 || hole[3] <= za + 1e-9) continue;
                var cut = new ArrayList<double[]>();
                for (var s : spans) {
                    if (hole[2] <= s[0] || hole[0] >= s[1]) cut.add(s);
                    else {
                        if (hole[0] > s[0]) cut.add(new double[]{s[0], hole[0]});
                        if (hole[2] < s[1]) cut.add(new double[]{hole[2], s[1]});
                    }
                }
                spans = cut;
            }
            for (var s : spans) {
                for (double y : new double[]{y0, y1})
                    mesh.flat(List.of(new double[]{s[0], y, za}, new double[]{s[1], y, za}, new double[]{s[1], y, zb}, new double[]{s[0], y, zb}), new double[]{0, y == y1 ? 1 : -1, 0},
                            Material.CONCRETE);
                solids.add(Solid.box(new double[]{s[0], y0, za}, new double[]{s[1], y1, zb}));
                if (s[1] - s[0] > .2 && zb - za > .2 && slab > .2)
                    volumes.add(Solid.box(new double[]{s[0] + .05, y0 + .05, za + .05}, new double[]{s[1] - .05, y1 - .05, zb - .05}));
            }
        }
        for (double z : new double[]{z0, z1})
            mesh.flat(List.of(new double[]{x0, y0, z}, new double[]{x1, y0, z}, new double[]{x1, y1, z}, new double[]{x0, y1, z}), new double[]{0, 0, z == z0 ? -1 : 1}, Material.CONCRETE);
        for (double x : new double[]{x0, x1})
            mesh.flat(List.of(new double[]{x, y0, z0}, new double[]{x, y0, z1}, new double[]{x, y1, z1}, new double[]{x, y1, z0}), new double[]{x == x0 ? -1 : 1, 0, 0}, Material.CONCRETE);
        // The holes' walls, facing into them.
        for (var hole : holes) {
            for (int e = 0; e < 2; e++) {
                double z = hole[1 + 2 * e], x = hole[2 * e];
                mesh.flat(List.of(new double[]{hole[0], y0, z}, new double[]{hole[2], y0, z}, new double[]{hole[2], y1, z}, new double[]{hole[0], y1, z}), new double[]{0, 0, e == 0 ? 1 : -1},
                        Material.CONCRETE);
                mesh.flat(List.of(new double[]{x, y0, hole[1]}, new double[]{x, y0, hole[3]}, new double[]{x, y1, hole[3]}, new double[]{x, y1, hole[1]}), new double[]{e == 0 ? 1 : -1, 0, 0},
                        Material.CONCRETE);
            }
        }
        double[][] corners = {{x0, z0}, {x1, z0}, {x1, z1}, {x0, z1}};
        for (int k = 0; k < 4; k++) segment(new double[]{corners[k][0], y1, corners[k][1]}, new double[]{corners[(k + 1) % 4][0], y1, corners[(k + 1) % 4][1]});
    }

    /**
     * A stair tower's openings onto the structure it serves ({@link #stairTower}): per stop, {lo, hi} along the tower's
     * axis (frame coordinates) where its landing meets the structure's deck, whose rail the caller opens there; the frame
     * coordinate across of the tower's side facing the structure (where that deck's edge meets it); and the tower's ends
     * along its axis, its corner posts included, from the lower coordinate to the higher.
     */
    record Tower(List<double[]> openings, double edge, double from, double to) {
    }

    /** A stair tower's flight width, lane pitch, landing depth and highest rise between landings, m (OSHA 1910.25; IBC 1011.8: 12 ft between landings). */
    static final double TOWER_FLIGHT = .9, TOWER_LANE = 1, TOWER_LANDING = 1.2, TOWER_RISE = 3.6;

    /**
     * A stair tower's plan ({@link #towerPlan}), worked out before it is drawn so a design can place it: per flight its
     * top's deck level, its length and the stop it reaches (-1: a landing between stops), alternating lanes from lane
     * A; the longest flight, where lane B's flights start. Stations run along lane A's rise from its first foot.
     */
    record TowerPlan(List<Double> levels, List<Double> lengths, List<Integer> reached, double longest) {
        /** How far a landing's corner posts reach past its outer edge along the tower, m. */
        static final double POSTS = .175;

        /** The station of the near end's landing's outer edge (a tower of one flight has none there: its first foot's). */
        double near() {
            return levels.size() > 1 ? -TOWER_LANDING : 0;
        }

        /** The station of the far end's landing's outer edge. */
        double far() {
            return longest + TOWER_LANDING;
        }

        /** The stations of the tower's ends, its corner posts included. */
        double from() {
            return levels.size() > 1 ? near() - POSTS : 0;
        }

        double to() {
            return far() + POSTS;
        }

        /** Where stop {@code j}'s landing is: +1 at the far end (a flight of lane A reaches it), -1 at the near end (one of lane B). */
        int end(int j) {
            return reached.indexOf(j) % 2 == 0 ? 1 : -1;
        }

        /** Stop {@code j}'s opening onto the structure, {lo, hi} stations: where its landing meets the structure's deck. */
        double[] opening(int j) {
            int f = reached.indexOf(j);
            return f % 2 == 0 ? new double[]{lengths.get(f), far()} : new double[]{near(), 0};
        }

        /** Lane A's first foot (its frame coordinate along the tower) for a tower rising towards {@code sign} whose {@code station} stands at {@code at}. */
        static double foot(double at, int sign, double station) {
            return at - sign * station;
        }
    }

    /**
     * The plan of a stair tower from the ground to each of {@code stops} (deck levels, rising: the structure's decks it
     * serves): each rise split into equal flights of at most {@link #TOWER_RISE}, each flight as long as its treads.
     */
    static TowerPlan towerPlan(double... stops) {
        var levels = new ArrayList<Double>();
        var lengths = new ArrayList<Double>();
        var reached = new ArrayList<Integer>();
        double walk = 0, longest = 0;
        for (int j = 0; j < stops.length; j++) {
            double to = stops[j] + Access.DECK, rise = to - walk;
            int n = (int) Math.ceil(rise / TOWER_RISE - 1e-9);
            for (int k = 1; k <= n; k++) {
                double level = k == n ? stops[j] : walk + rise * k / n - Access.DECK, base = levels.isEmpty() ? 0 : levels.getLast() + Access.DECK;
                levels.add(level);
                lengths.add(Math.ceil((level + Access.DECK - base) / STAIR_RISE - 1e-9) * STAIR_GOING);
                reached.add(k == n ? j : -1);
                longest = Math.max(longest, lengths.getLast());
            }
            walk = to;
        }
        return new TowerPlan(List.copyOf(levels), List.copyOf(lengths), List.copyOf(reached), longest);
    }

    /**
     * A switchback stair tower beside a structure (owner decision 2026-10-18; OSHA 1910.25 and 1910.29) as {@code plan}
     * lays it out: flights {@link #TOWER_FLIGHT} wide on two lanes {@link #TOWER_LANE} apart, their centres on cell
     * centres, lane A's first foot at (x, z) (its across coordinate moved to the cell centre), its flights rising along
     * frame axis {@code axis} (0 or 2) towards {@code sign}, lane B's at {@code side} (±1) across from it rising back from
     * the longest flight's top, the structure beside lane A on the other side. At each turn a landing
     * {@link #TOWER_LANDING} deep across both lanes, a lane's piece from a shorter flight's top on to it (fenced
     * {@link Access#NARROW_BAND} deep, so a player walks between its ends), railed on its outer edges and on the
     * structure's side but at a stop; the top landing over lane A alone. No brackets: posts at the tower's corners (their
     * faces in line with its sides, so the structure's deck runs up to them) and, where {@code beams}, beams under each
     * landing between them carry it. Returns the openings onto the structure (cut into its decks' rails with
     * {@link Access.Line#gap}).
     */
    Tower stairTower(TowerPlan plan, double x, double z, int axis, int sign, int side, boolean beams) {
        boolean alongX = axis == 0;
        double start = alongX ? x : z, cA = Math.floor(alongX ? z : x) + .5, cB = cA + side * TOWER_LANE, h = TOWER_FLIGHT / 2, d = TOWER_LANDING, g = Access.GAP;
        double far = plan.longest(), post = h - .075;
        var levels = plan.levels();
        double highNear = -1, highFar = -1;
        int flights = levels.size();
        for (int f = 0; f < flights; f++) {
            boolean laneA = f % 2 == 0, last = f + 1 == flights, joins = plan.reached().get(f) >= 0;
            double level = levels.get(f), base = f == 0 ? 0 : levels.get(f - 1) + Access.DECK, length = plan.lengths().get(f);
            if (laneA) {
                stair(towerX(alongX, start, sign, 0, cA), towerZ(alongX, start, sign, 0, cA), axis, sign, TOWER_FLIGHT, base, level + Access.DECK);
                if (!last) {
                    platform(landing(alongX, start, sign, side, far, cA - side * h, cB + side * h, 1), level, d + g, Access.RAILED | Access.WALL_EDGE, !joins, true);
                    if (far - length > 1e-6)
                        platform(landing(alongX, start, sign, side, length, cA - side * h, cA + side * h, 1), level, far - length + g, Access.WALL_EDGE | Access.NARROW, !joins, true);
                } else
                    platform(landing(alongX, start, sign, side, length, cA - side * h, cA + side * h, 1), level, far + d - length + g, Access.RAILED | Access.WALL_EDGE | Access.NARROW,
                            !joins, true);
                if (beams) towerBeams(alongX, start, sign, far, far + d + .1, cA - side * post, cB + side * post, level);
                highFar = level;
            } else {
                stair(towerX(alongX, start, sign, far, cB), towerZ(alongX, start, sign, far, cB), axis, -sign, TOWER_FLIGHT, base, level + Access.DECK);
                platform(landing(alongX, start, sign, side, 0, cA - side * h, cB + side * h, -1), level, d + g, Access.RAILED | Access.WALL_EDGE, !joins, true);
                if (far - length > 1e-6)
                    platform(landing(alongX, start, sign, side, far - length, cB - side * h, cB + side * h, -1), level, far - length + g, Access.WALL_EDGE | Access.NARROW, true, true);
                // At the top the landing's edge over lane A has no flight going on up from it: a handrail closes it.
                if (last) rails(landing(alongX, start, sign, side, -1 + g, cA - side * h, cA + side * h, 1), level, 1, false, false);
                if (beams) towerBeams(alongX, start, sign, 0, -d - .1, cA - side * post, cB + side * post, level);
                highNear = level;
            }
        }
        // The corner posts up past the handrails of each end's highest landing.
        for (int e = 0; e < 2; e++) {
            double high = e == 0 ? highNear : highFar, u = e == 0 ? -d - .1 : far + d + .1;
            if (high < 0) continue;
            for (double c : new double[]{cA - side * post, cB + side * post})
                post(towerX(alongX, start, sign, u, c), towerZ(alongX, start, sign, u, c), 0, high + Access.DECK + Access.RAIL + .1, .15, false);
        }
        var openings = new ArrayList<double[]>();
        for (int j = 0; plan.reached().contains(j); j++) openings.add(towerSpan(start, sign, plan.opening(j)[0], plan.opening(j)[1]));
        double[] ends = towerSpan(start, sign, plan.from(), plan.to());
        return new Tower(List.copyOf(openings), cA - side * h, ends[0], ends[1]);
    }

    /** A stair tower's frame x at {@code u} along its axis from lane A's first foot (at {@code start}) and {@code c} across. */
    private static double towerX(boolean alongX, double start, int sign, double u, double c) {
        return alongX ? start + sign * u : c;
    }

    private static double towerZ(boolean alongX, double start, int sign, double u, double c) {
        return alongX ? c : start + sign * u;
    }

    /** The frame coordinates {lo, hi} along a stair tower's axis of its stations u0 and u1. */
    private static double[] towerSpan(double start, int sign, double u0, double u1) {
        double a = start + sign * u0, b = start + sign * u1;
        return new double[]{Math.min(a, b), Math.max(a, b)};
    }

    /**
     * A stair tower's landing piece: a run across its lanes from {@code c0} to {@code c1} (from lane A's side towards
     * lane B's), its deck starting at station {@code u} along the tower and reaching towards {@code out} (±1 along the
     * tower's rise of lane A), its wall line {@link Access#GAP} short of u.
     */
    private static Access.Line landing(boolean alongX, double start, int sign, int side, double u, double c0, double c1, int out) {
        double[] across = alongX ? new double[]{0, 0, side} : new double[]{side, 0, 0}, along = alongX ? new double[]{sign * out, 0, 0} : new double[]{0, 0, sign * out};
        double wall = u - out * Access.GAP;
        return new Access.Line(new double[]{towerX(alongX, start, sign, wall, c0), 0, towerZ(alongX, start, sign, wall, c0)}, across, along, Math.abs(c1 - c0));
    }

    /** Beams under a stair tower's landing at {@code level}: across its outer end between the posts at {@code post}, and along its sides at c0 and c1 from its inner edge {@code u}. */
    private void towerBeams(boolean alongX, double start, int sign, double u, double post, double c0, double c1, double level) {
        double y0 = level - .2;
        double[][] boxes = {{post - .075, post + .075, c0 - .075 * Math.signum(c1 - c0), c1 + .075 * Math.signum(c1 - c0)}, {u, post, c0 - .075, c0 + .075}, {u, post, c1 - .075, c1 + .075}};
        for (var b : boxes) {
            double xa = towerX(alongX, start, sign, b[0], b[2]), xb = towerX(alongX, start, sign, b[1], b[3]);
            double za = towerZ(alongX, start, sign, b[0], b[2]), zb = towerZ(alongX, start, sign, b[1], b[3]);
            beam(new double[]{Math.min(xa, xb), y0, Math.min(za, zb)}, new double[]{Math.max(xa, xb), level, Math.max(za, zb)});
        }
    }

    /**
     * A pipe davit on a round top deck ({@link Access#davit}), its mast {@code r} from the vertical axis through (x, z) at
     * angle {@code t}, from the deck's walking level {@code base} to {@code top}, its jib to {@code reach} from the axis;
     * the mast collides.
     */
    void davit(double x, double z, double t, double r, double base, double top, double reach) {
        Access.davit(mesh, x, z, t, r, base, top, reach);
        solids.add(Solid.rod(Access.around(x, z, r, t, base), 1, base, top, .1, 0));
    }

    // ---- Heavy vessel parts -----------------------------------------------------------------------------------------

    /** The horizontal unit vector at angle {@code t} (from +x towards +z). */
    static double[] outward(double t) {
        return new double[]{DeterministicMath.cos(t), 0, DeterministicMath.sin(t)};
    }

    /** The horizontal unit vector a quarter turn on from angle {@code t} (towards increasing angle). */
    static double[] tangential(double t) {
        return new double[]{-DeterministicMath.sin(t), 0, DeterministicMath.cos(t)};
    }

    /**
     * A skirt's foot on grade as a drum's or a column's (ShapeRasterizer's skirt base; owner report 2026-10-25: the
     * reactors' skirts in line with theirs): the base plate {@link EquipmentShape#PLATE} thick under the skirt of outer
     * radius {@code r} round the vertical axis through (x, z), {@code n} sides, its ring {@link EquipmentShape#BASE_RING}
     * proud, and the anchor chairs on it ({@link EquipmentShape#CHAIR} square, a drum's count for {@code diameter}), none
     * in an opening reaching down to them; an open skirt's floor plate inside. Openings as {@link #skirt}'s.
     */
    void baseRing(double x, double z, double r, double diameter, int n, double[]... openings) {
        double plate = EquipmentShape.PLATE, ring = EquipmentShape.BASE_RING, chair = EquipmentShape.CHAIR;
        mesh.ring(Frame.of(new double[]{x, 0, z}, 1, 1), openings.length > 0 ? 0 : r - EquipmentShape.SKIRT_WALL, r + ring, 0, plate, n, 2, Material.SUPPORT);
        int chairs = EquipmentShape.anchorChairs(diameter);
        for (int k = 0; k < chairs; k++) {
            double t = 2 * Math.PI * (k + .5) / chairs;
            boolean open = false;
            for (var o : openings) open |= o[2] < plate + chair && apart(t, o[0]) < DeterministicMath.asin(Math.min(1, o[1] / r)) + chair / r;
            if (!open)
                mesh.block(Access.around(x, z, r + ring / 2, t, plate + chair / 2), scale(outward(t), ring / 2), new double[]{0, chair / 2, 0}, scale(tangential(t), chair / 2),
                        Material.SUPPORT);
        }
    }

    /**
     * A skirt as a drum's or a column's (owner report 2026-10-25: the reactors' skirts in line with theirs): a steel
     * plate {@link EquipmentShape#SKIRT_WALL} through, of outer radius {@code r} round the vertical axis through (x, z),
     * from grade to {@code y1}, {@code n} sides, in the structure's paint. Cut through over each of {@code openings}
     * ({centre angle, half width (m), sill, head}): a slot or a bay, the skirt's inside face and the cut's sill, head and
     * jambs drawn; closed, its inside is never seen and is not drawn. It collides solid, as a drum's, unless an opening
     * reaches grade (a bay one walks into), then as drawn.
     */
    void skirt(double x, double z, double r, double y1, int n, double[]... openings) {
        double[] axis = {x, 0, z}, up = {0, 1, 0}, down = {0, -1, 0};
        var outside = new Radial(axis, up);
        var inside = new Inward(axis, up);
        double ri = r - EquipmentShape.SKIRT_WALL;
        boolean open = openings.length > 0, bay = false;
        for (var o : openings) bay |= o[2] <= 0;
        var cut = new double[n][];
        for (int i = 0; i < n; i++) {
            double mid = 2 * Math.PI * (i + .5) / n;
            for (var o : openings) if (apart(mid, o[0]) < DeterministicMath.asin(Math.min(1, o[1] / r))) cut[i] = o;
        }
        for (int i = 0; i < n; i++) {
            double a0 = 2 * Math.PI * i / n, a1 = 2 * Math.PI * (i + 1) / n;
            var o = cut[i];
            for (var span : o == null ? List.of(new double[]{0, y1}) : List.of(new double[]{0, o[2]}, new double[]{o[3], y1})) {
                if (span[1] - span[0] < 1e-9) continue;
                mesh.add(List.of(Access.around(x, z, r, a0, span[0]), Access.around(x, z, r, a1, span[0]), Access.around(x, z, r, a1, span[1]), Access.around(x, z, r, a0, span[1])),
                        new double[]{x, (span[0] + span[1]) / 2, z}, outside, Material.SUPPORT, null, null);
                if (open)
                    mesh.add(List.of(Access.around(x, z, ri, a0, span[0]), Access.around(x, z, ri, a1, span[0]), Access.around(x, z, ri, a1, span[1]), Access.around(x, z, ri, a0, span[1])),
                            Access.around(x, z, 2 * r, (a0 + a1) / 2, (span[0] + span[1]) / 2), inside, Material.SUPPORT, null, null);
            }
            if (o != null) {
                // The cut's head, and its sill where it stands off the ground.
                mesh.flat(List.of(Access.around(x, z, ri, a0, o[3]), Access.around(x, z, ri, a1, o[3]), Access.around(x, z, r, a1, o[3]), Access.around(x, z, r, a0, o[3])), down,
                        Material.SUPPORT);
                if (o[2] > 0)
                    mesh.flat(List.of(Access.around(x, z, r, a0, o[2]), Access.around(x, z, r, a1, o[2]), Access.around(x, z, ri, a1, o[2]), Access.around(x, z, ri, a0, o[2])), up,
                            Material.SUPPORT);
            }
            var before = cut[(i + n - 1) % n];
            if (o == before) continue;
            // A jamb at this facet's edge facing into each opening it ends.
            for (var j : new double[][]{o, before}) {
                if (j == null) continue;
                double[] facing = j == o ? tangential(a0) : neg(tangential(a0));
                mesh.flat(List.of(Access.around(x, z, ri, a0, j[2]), Access.around(x, z, r, a0, j[2]), Access.around(x, z, r, a0, j[3]), Access.around(x, z, ri, a0, j[3])), facing,
                        Material.SUPPORT);
            }
        }
        if (!bay) {
            solids.add(Solid.rod(axis, 1, 0, y1, r, 0));
            return;
        }
        // Each run of facets alike (one opening, or none): whole where uncut, under the sill and over the head where cut.
        int first = 0;
        while (first < n && cut[first] == cut[(first + n - 1) % n]) first++;
        for (int done = 0, i = first % n; done < n; ) {
            int run = 1;
            while (done + run < n && cut[(i + run) % n] == cut[i]) run++;
            double from = 2 * Math.PI * i / n, to = 2 * Math.PI * (i + run) / n;
            var o = cut[i];
            if (o == null) solids.add(Solid.ring(x, z, 0, y1, ri, r, from, to));
            else {
                if (o[2] > 0) solids.add(Solid.ring(x, z, 0, o[2], ri, r, from, to));
                solids.add(Solid.ring(x, z, o[3], y1, ri, r, from, to));
            }
            done += run;
            i = (i + run) % n;
        }
    }

    /**
     * A heavy flange pair's studs round the vertical axis through (x, z) on the bolt circle {@code circle}: nuts under the
     * lower flange's face {@code low} and over the upper's {@code high}, with the studs' ends past them; 12 to 24 of them
     * by the line {@code p} (ASME B16.5 Class 2500).
     */
    void studs(double x, double z, double circle, double low, double high, double p) {
        studs(x, z, circle, low, high, p < .25 ? 12 : p < .45 ? 16 : p < .65 ? 20 : 24, Math.max(.02, .16 * p));
    }

    /** {@code count} studs {@code d} across on a heavy flange pair's bolt circle, as {@link #studs(double, double, double, double, double, double)}. */
    void studs(double x, double z, double circle, double low, double high, int count, double d) {
        double nut = Math.max(Mesh.MIN_NUT, 1.6 * d) / Math.sqrt(3);
        for (int k = 0; k < count; k++) {
            double t = 2 * Math.PI * (k + .5) / count;
            for (int s = -1; s <= 1; s += 2) {
                var f = new Frame(Access.around(x, z, circle, t, s < 0 ? low : high), new double[]{0, s, 0}, new double[]{0, 0, 1});
                mesh.cylinder(f, nut, 0, d, 6, Material.BOLT);
                if (d >= .04) mesh.cylinder(f, d / 2, d, 1.5 * d, 6, Material.BOLT);
            }
        }
    }

    /**
     * A heavy flange pair across a straight line at {@code station} along frame {@code f} (its axis along the line): two
     * flanges 1.9 times the line {@code od} across, each 0.3 od thick with a 0.01 m joint, and the nuts of 8 to 16 studs
     * on their outer faces (a hydroprocessing reactor's Class 2500 ring-joint flanges).
     */
    void heavyJoint(Frame f, double od, double station) {
        double rf = .95 * od, tf = Math.max(.03, .3 * od), gap = .01;
        int n = sides(2 * rf), count = od < .3 ? 8 : od < .6 ? 12 : 16;
        mesh.cylinder(f, rf, station - gap / 2 - tf, station - gap / 2, n, Material.FLANGE);
        mesh.cylinder(f, rf, station + gap / 2, station + gap / 2 + tf, n, Material.FLANGE);
        double d = Math.max(.02, .12 * od), nut = Math.max(Mesh.MIN_NUT, 1.6 * d) / Math.sqrt(3), circle = (od / 2 + .05 + rf) / 2;
        if (rf - od / 2 - .05 < 1.6 * d) return;
        var axis = f.axis();
        for (int k = 0; k < count; k++) {
            double t = 2 * Math.PI * (k + .5) / count;
            for (int s = -1; s <= 1; s += 2)
                mesh.cylinder(new Frame(f.at(t, circle, station + s * (gap / 2 + tf)), s < 0 ? neg(axis) : axis, f.ref()), nut, 0, d, 6, Material.BOLT);
        }
    }

    // ---- Line fittings ----------------------------------------------------------------------------------------------

    /**
     * A control valve on a vertical line of radius {@code r} at (x, y, z): a cast body between two flanges, its yoke out
     * along the horizontal unit vector {@code out} to the diaphragm actuator (a quench valve station, an actuated ball
     * valve); body and actuator collide.
     */
    void valve(double x, double y, double z, double r, double[] out) {
        var up = Frame.of(new double[]{x, 0, z}, 1, 1);
        double body = 1.6 * r + .03, fr = Math.max(body + .02, PipeSizes.flange(2 * r) / 2), cap = .1 + r;
        mesh.cylinder(up, body, y - body, y + body, 12, Material.MACHINE);
        mesh.cylinder(up, fr, y - body - .04, y - body, 12, Material.FLANGE);
        mesh.cylinder(up, fr, y + body, y + body + .04, 12, Material.FLANGE);
        double[] c0 = {x + out[0] * body, y, z + out[2] * body}, c1 = {x + out[0] * (body + .25), y, z + out[2] * (body + .25)};
        mesh.bar(c0, c1, .04, new double[]{0, 1, 0}, Material.ACTUATOR);
        var f = new Frame(c1, out, new double[]{0, 1, 0});
        mesh.cylinder(f, cap, 0, .1, 12, Material.ACTUATOR);
        mesh.cylinder(f, .04, .1, .16, 8, Material.ACTUATOR);
        solids.add(span(new double[]{x - body, y - body, z - body}, new double[]{x + body, y + body, z + body}));
        double[] far = {c1[0] + out[0] * .16, y, c1[2] + out[2] * .16};
        solids.add(span(new double[]{Math.min(c0[0], far[0]) - cap * Math.abs(out[2]), y - cap, Math.min(c0[2], far[2]) - cap * Math.abs(out[0])},
                new double[]{Math.max(c0[0], far[0]) + cap * Math.abs(out[2]), y + cap, Math.max(c0[2], far[2]) + cap * Math.abs(out[0])}));
    }

    /**
     * A thermowell on a round body (radius {@code r} about the vertical axis through (x, z)) at angle {@code t} and height
     * {@code y}: a short stub and its junction head; it collides.
     */
    void thermowell(double x, double z, double r, double t, double y) {
        mesh.cylinder(new Frame(Access.around(x, z, r - .02, t, y), outward(t), new double[]{0, 1, 0}), .03, 0, .14, 8, Material.FLANGE);
        mesh.block(Access.around(x, z, r + .19, t, y), scale(outward(t), .07), new double[]{0, .08, 0}, scale(tangential(t), .07), Material.MOTOR);
        solids.add(span(Access.around(x, z, r, t, y - .08), Access.around(x, z, r + .26, t, y + .08)));
    }

    /**
     * A concentric reducer from radius {@code r0} at {@code start} along the unit vector {@code dir} to {@code r1},
     * {@code length} on, {@code sides} facets; when {@code solid} (dir along a frame axis) it collides as a rod of the
     * larger radius. Returns its far end.
     */
    double[] reducer(double[] start, double[] dir, double r0, double r1, double length, int sides, Material material, boolean solid) {
        double[] ref = Math.abs(dir[1]) < .9 ? new double[]{0, 1, 0} : new double[]{1, 0, 0}, end = plus(start, scale(dir, length));
        mesh.frustum(new Frame(start, dir, unit(cross(dir, ref))), r0, r1, 0, length, sides, material);
        if (solid) {
            int axis = Math.abs(dir[0]) > .9 ? 0 : Math.abs(dir[1]) > .9 ? 1 : 2;
            double[] centre = start.clone();
            centre[axis] = 0;
            solids.add(Solid.rod(centre, axis, Math.min(start[axis], end[axis]), Math.max(start[axis], end[axis]), Math.max(r0, r1), 0));
        }
        return end;
    }

    /** The axis-aligned box spanned by two corners in any order, a solid. */
    static Solid span(double[] p, double[] q) {
        return Solid.box(new double[]{Math.min(p[0], q[0]), Math.min(p[1], q[1]), Math.min(p[2], q[2])}, new double[]{Math.max(p[0], q[0]), Math.max(p[1], q[1]), Math.max(p[2], q[2])});
    }

    /** An axis-aligned box, every face drawn; {@code solid} for one a player collides with. */
    void box(double[] lo, double[] hi, Material material, boolean solid) {
        box(lo, hi, ALL_FACES, material, solid);
    }

    /** An axis-aligned box drawn with {@code faces} (bits -x, +x, -y, +y, -z, +z: one standing on a slab hides its underside); {@code solid} for one a player collides with. */
    void box(double[] lo, double[] hi, int faces, Material material, boolean solid) {
        mesh.box(lo, hi, faces, material);
        if (solid) solids.add(Solid.box(lo.clone(), hi.clone()));
    }

    /**
     * A steel column of square {@code section} at (x, z) from {@code y0} (the ground, a slab: its foot hidden) to {@code y1};
     * it collides. Where {@code plate}, it stands on a base plate {@link #BASE_PLATE} thick and 0.06 m wider each side
     * (its anchor bolts' room) at y0.
     */
    void post(double x, double z, double y0, double y1, double section, boolean plate) {
        double h = section / 2, foot = plate ? y0 + BASE_PLATE : y0;
        if (plate) box(new double[]{x - h - .06, y0, z - h - .06}, new double[]{x + h + .06, foot, z + h + .06}, ALL_FACES & ~4, Material.STEELWORK, false);
        if (y1 > foot + 1e-6) box(new double[]{x - h, foot, z - h}, new double[]{x + h, y1, z + h}, ALL_FACES & ~4, Material.STEELWORK, true);
    }

    /** A column's base plate's thickness, m. */
    static final double BASE_PLATE = .03;

    /** A steel beam filling the box (lo, hi), its top hidden under the deck or slab it carries; it collides. */
    void beam(double[] lo, double[] hi) {
        box(lo, hi, ALL_FACES & ~8, Material.STEELWORK, true);
    }

    void segment(double[] p, double[] q) {
        edges.add(new double[]{p[0], p[1], p[2], q[0], q[1], q[2]});
    }

    static int sides(double od) {
        return Math.max(12, PipeSizes.sides(od));
    }
}
