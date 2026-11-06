package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Orientation;
import com.wormzjl.icheme.science.equipment.Mesh.Frame;
import com.wormzjl.icheme.science.equipment.Mesh.Material;

import java.util.List;
import java.util.Locale;

/**
 * An electric motor, or its mirror the generator (batch 2026-11-03-rotating-machinery, W4; plan section 6): its design
 * point is its rated power from the IEC list (0.75 kW to 20 MW, the main size), its explicit VFD base speed (legacy poles retain their
 * old meaning). New motors all use a variable-speed drive. Up to {@link #LOW_VOLTAGE} a
 * low-voltage TEFC motor ({@link ProcessUnit#motor}: fins, cowl and turning fan, feet, terminal box), its body
 * {@link ProcessUnit#motorDiameter} across; above it a large motor ({@link ProcessUnit#largeMotor}: octagonal stator,
 * top-hat cooler, terminal box on its side), 1.15 of that across. Its drive end is at its back (every machine's shaft end
 * is), a half coupling under its guard; a shaft block joins it to the machine it drives (a generator: the machine that
 * drives it). Its terminal box is its RF port ({@link ProcessUnit#terminal}): a motor draws FE there, a generator gives
 * it (W5). It stands on a steel baseplate on a chamfered plinth at grade, or on a concrete table {@code support} metres
 * up so its shaft lines up with a machine on a deck. Process depth: the frame only sizes the drawing.
 */
final class MotorDesign extends UnitDesign {
    /** The largest low-voltage TEFC motor, kW; above it a large motor. */
    static final double LOW_VOLTAGE = 400;
    /** A new motor's poles: the common 4-pole, 1,500 rpm. */
    static final int DEFAULT_POLES = 4;
    /** The plinth at grade and the baseplate, m. */
    static final double PLINTH = .2, BASEPLATE = .08;

    /** Whether this is the generator: shaft power in, FE out. */
    final boolean generator;

    MotorDesign(boolean generator) {
        this.generator = generator;
    }

    /** The rated powers, kW: the IEC list to 20 MW. */
    static final List<Double> POWERS = MachineDuty.MOTOR_POWERS;

    @Override
    public double minSize() {
        return POWERS.getFirst();
    }

    @Override
    public double maxSize() {
        return POWERS.getLast();
    }

    /** A notional step; the sizes are the IEC list ({@link #sizes()}). */
    @Override
    public double sizeStep() {
        return .05;
    }

    @Override
    public List<Double> sizes() {
        return POWERS;
    }

    @Override
    public double snap(double size) {
        return Double.isFinite(size) ? MachineDuty.nearest(POWERS, size) : minSize();
    }

    @Override
    public double step(double size, int steps) {
        return MachineDuty.step(POWERS, size, steps);
    }

    @Override
    public String sizeUnit() {
        return "kW";
    }

    @Override
    public String sizeText(double size) {
        return size < 10 ? UnitDesign.size(size) : whole(size);
    }

    @Override
    public int minSupport(double size, int length) {
        return 0;
    }

    /** At grade, or on a concrete table up to 8 m. */
    @Override
    public int maxSupport() {
        return 8;
    }

    @Override
    public Orientation orientation() {
        return Orientation.HORIZONTAL;
    }

    /** The fan and the coupling's hub turn. */
    @Override
    public boolean moves() {
        return true;
    }

    @Override
    public boolean hasDuty() {
        return true;
    }

    @Override
    public MachineDuty duty(double size, int length) {
        return generator ? MachineDuty.generator(size, DEFAULT_POLES) : MachineDuty.motor(size,3000.0);
    }

    @Override
    public double size(MachineDuty duty) {
        return duty.power();
    }

    @Override
    void checkDuty(MachineDuty duty) {
        if (!POWERS.contains(duty.power())) throw new IllegalArgumentException("A " + name() + "'s rated power is one of the IEC list, 0.75 to 20,000 kW, not " + duty.power());
        if (!generator && duty.baseSpeed()!=0 && (duty.baseSpeed()<300||duty.baseSpeed()>12000))throw new IllegalArgumentException("Motor base speed is 300 to 12000 rpm");
        if (generator && duty.poles() == 0) throw new IllegalArgumentException("A " + name() + " has 2, 4, 6 or 8 poles");
        if (generator && duty.drive() != MachineDuty.Drive.FIXED) throw new IllegalArgumentException("A generator has no drive");
        if (duty.flow() != 0 || duty.head() != 0 || duty.polytropicHead() != 0 || duty.steam() != null || duty.exhaust() != 0)
            throw new IllegalArgumentException("A " + name() + "'s design point is its power, poles" + (generator ? "" : " and drive"));
    }

    /** Power steps along the IEC list ({@link #POWERS}). */
    @Override
    public MachineDuty step(MachineDuty duty, String setting, int steps) {
        if (setting.equals("base_speed") && !generator) return duty.withBaseSpeed(Math.clamp(duty.speed()+steps*100,300,com.wormzjl.icheme.science.machine.Motor.MAXIMUM_RPM));
        if (!setting.equals("power")) return duty;
        double power = MachineDuty.step(POWERS, duty.power(), steps);
        return generator ? MachineDuty.generator(power, duty.poles()) : new MachineDuty(0,0,0,power,duty.poles(),duty.drive(),null,0,duty.baseSpeed());
    }

    private String name() {
        return generator ? "generator" : "motor";
    }

    @Override
    public String sizeRow() {
        return name() + ".power";
    }

    @Override
    public String supportRow() {
        return "machine_table";
    }

    @Override
    public Line held(EquipmentShape shape) {
        var duty = shape.duty();
        return new Line(name() + ".rating", sizeText(duty.power()), whole(duty.speed()), size(frame(duty.power())));
    }

    @Override
    public String variant(EquipmentShape shape) {
        return shape.duty().power() > LOW_VOLTAGE ? ".large" : "";
    }

    @Override
    public List<Line> datasheet(EquipmentShape shape) {
        var duty = shape.duty();
        var rating = new Line(name() + ".rating", sizeText(duty.power()), whole(duty.speed()), size(frame(duty.power())));
        if (generator) return List.of(rating);
        return List.of(rating, new Line("motor.drive." + "variable"));
    }

    /** The body's diameter, m: a TEFC motor's {@link ProcessUnit#motorDiameter}, a large motor's frame 1.15 of it across. */
    static double frame(double kw) {
        return (kw > LOW_VOLTAGE ? 1.15 : 1) * ProcessUnit.motorDiameter(kw);
    }

    /** The highest base under a shaft at {@code y} of radius {@code rs} that still leaves its coupling guard room ({@link ProcessUnit#coupling}). */
    static double guarded(double y, double rs) {
        return y - Math.max(rs + .04, 2.2 * rs) - .06;
    }

    @Override
    void build(ProcessUnit u) {
        var duty = u.shape.duty();
        double kw = duty.power(), rpm = duty.speed(), rs = ProcessUnit.shaftRadius(kw, rpm);
        int h = u.shape.support();
        double floor = h == 0 ? PLINTH : h, deck = floor + BASEPLATE, front = .2;
        var role = generator ? EquipmentShape.ShaftEnd.Role.DRIVEN_INPUT : EquipmentShape.ShaftEnd.Role.DRIVER_OUTPUT;
        double a, y, drive, half, plinth = PLINTH;
        if (kw <= LOW_VOLTAGE) {
            // A TEFC motor: its fan cowl to the front, its drive end at the back, its shaft on the lowest cell centre
            // whose feet stand on the baseplate. At grade the concrete block rises to bring the baseplate up under its
            // feet, as a pump's does (its shaft's height is the cell centre's).
            double r = ProcessUnit.motorDiameter(kw) / 2, fin = .025 + .06 * r, length = 3.2 * r + .1;
            half = r + fin + .08;
            a = ProcessUnit.centre(half + .12);
            y = Math.ceil(deck + r + .04 - .5) + .5;
            if (h == 0) {
                plinth = Math.max(PLINTH, Math.min(y - r - .02, guarded(y, rs)) - BASEPLATE);
                floor = plinth;
                deck = floor + BASEPLATE;
            }
            drive = front + length;
            if(generator)u.generator(Frame.of(new double[]{a,y,drive},2,-1),r,0,length,deck);
            else u.motor(Frame.of(new double[]{a, y, drive}, 2, -1), r, 0, length, 0, deck, ProcessUnit.MotorDetail.FULL);
        } else {
            double across = frame(kw), r = across / 2, length = 1.7 * across + .2, terminal = Math.min(1.2, .3 + .2 * across);
            half = r + .4 * terminal + .05;
            a = ProcessUnit.centre(half + .12);
            y = Math.ceil(deck + r + .1 - .5) + .5;
            if (h == 0) {
                // At grade the block rises to the base frame's beams under the feet, no posts.
                plinth = Math.max(PLINTH, Math.min(y - r - ProcessUnit.BEAM, guarded(y, rs)) - BASEPLATE);
                floor = plinth;
                deck = floor + BASEPLATE;
            }
            front = .35;
            drive = front + length;
            if(generator)u.largeGenerator(a,y,front,across,length,deck);else u.largeMotor(a, y, front, across, length, deck);
        }
        u.terminal = u.terminalBox;
        double face = Math.ceil(drive + .2 + rs - 1e-9);
        u.coupling(a, y, drive, face, 1, rs, deck, ProcessUnit.shown(rpm), role);
        // The baseplate and the plinth or table under it.
        double z0 = front - .2, z1 = face - .2;
        u.baseplate(a - half, a + half, floor, deck, z0, z1);
        u.driverBase(a - half - .1, a + half + .1, z0 - .05, z1 + .1, plinth, h);
    }
}
