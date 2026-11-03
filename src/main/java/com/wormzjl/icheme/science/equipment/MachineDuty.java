package com.wormzjl.icheme.science.equipment;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * A rotating machine's design point, a fact of its shape (shape fields 6; batch 2026-11-03-rotating-machinery, W4):
 * what the player asked of it in the constructor. The machine's drawn sizes follow from it ({@link UnitDesign#size},
 * {@link UnitDesign#length}); the engine (W2, W3) reads its rated values. Each field has one meaning and unit for every
 * kind, and is 0 (or null) where the kind has none:
 * <ul>
 *     <li>{@code flow}: rated flow, m³/h (a pump's liquid; a centrifugal compressor's at its inlet);</li>
 *     <li>{@code head}: a pump's rated head, m of the pumped liquid;</li>
 *     <li>{@code polytropicHead}: a centrifugal compressor's rated polytropic head, kJ/kg;</li>
 *     <li>{@code power}: rated shaft power, kW (a motor, a turbine, a gearbox, a generator);</li>
 *     <li>{@code poles}: an electric machine's poles, 2, 4, 6 or 8 (a pump's speed class, 2 or 4): its synchronous speed
 *     at 50 Hz, {@link #speed()};</li>
 *     <li>{@code drive}: a motor's drive, fixed speed or a variable-speed drive;</li>
 *     <li>{@code steam}: a steam turbine's inlet class;</li>
 *     <li>{@code exhaust}: a steam turbine's exhaust pressure, kPa absolute (back pressure, or vacuum to a condenser).</li>
 * </ul>
 * Enums are saved by name. Mechanical numbers (a shaft's diameter, a frame's size) only size the drawing.
 */
public record MachineDuty(double flow, double head, double polytropicHead, double power, int poles, Drive drive, @Nullable Steam steam, double exhaust) {
    /** A shape whose kind has no design point. */
    public static final MachineDuty NONE = new MachineDuty(0, 0, 0, 0, 0, Drive.FIXED, null, 0);

    /** A motor's drive. */
    public enum Drive {
        FIXED, VARIABLE
    }

    /**
     * A steam turbine's inlet class: a refinery's steam headers, pressure kPa absolute and temperature K (MP 17 bar
     * 300 °C, HP 42 bar 400 °C, VHP 100 bar 510 °C).
     */
    public enum Steam {
        MP(1700, 573.15), HP(4200, 673.15), VHP(10000, 783.15);

        public final double pressure, temperature;

        Steam(double pressure, double temperature) {
            this.pressure = pressure;
            this.temperature = temperature;
        }
    }

    /** An electric machine's poles. */
    public static final List<Integer> POLES = List.of(2, 4, 6, 8);
    /**
     * Rated powers, kW: the IEC 60072 motor list to 1 MW, then the R20 series to 50 MW. Motors, generators and
     * gearboxes take a power from it; turbines a power from 55 kW.
     */
    public static final List<Double> POWERS = List.of(.75, 1.1, 1.5, 2.2, 3.0, 4.0, 5.5, 7.5, 11.0, 15.0, 18.5, 22.0, 30.0, 37.0, 45.0, 55.0, 75.0, 90.0, 110.0, 132.0,
            160.0, 200.0, 250.0, 315.0, 355.0, 400.0, 450.0, 500.0, 560.0, 630.0, 710.0, 800.0, 900.0, 1000.0, 1120.0, 1250.0, 1400.0, 1600.0, 1800.0, 2000.0, 2240.0,
            2500.0, 2800.0, 3150.0, 3550.0, 4000.0, 4500.0, 5000.0, 5600.0, 6300.0, 7100.0, 8000.0, 9000.0, 10000.0, 11200.0, 12500.0, 14000.0, 16000.0, 18000.0, 20000.0,
            22400.0, 25000.0, 28000.0, 31500.0, 35500.0, 40000.0, 45000.0, 50000.0);
    /** The largest motor, generator and gearbox, kW. */
    public static final double MAX_MOTOR = 20000;
    /** The rated powers of motors, generators and gearboxes, kW: the list to {@link #MAX_MOTOR}. */
    public static final List<Double> MOTOR_POWERS = POWERS.stream().filter(p -> p <= MAX_MOTOR).toList();
    /** Exhaust pressures a turbine steps through, kPa absolute: vacuum to a condenser, then back pressures (the LP, MP and HP headers). */
    public static final List<Double> EXHAUSTS = List.of(10.0, 15.0, 20.0, 150.0, 250.0, 350.0, 450.0, 650.0, 1000.0, 1700.0, 2500.0, 4200.0);
    /** The highest exhaust that is vacuum, kPa: below it the turbine exhausts to a condenser. */
    public static final double VACUUM = 50;

    public MachineDuty {
        Objects.requireNonNull(drive, "drive");
        for (double v : new double[]{flow, head, polytropicHead, power, exhaust})
            if (!Double.isFinite(v) || v < 0) throw new IllegalArgumentException("A design point's values are finite and not negative, not " + v);
        if (poles != 0 && !POLES.contains(poles)) throw new IllegalArgumentException("An electric machine has 2, 4, 6 or 8 poles, not " + poles);
    }

    /** A pump's design point. */
    public static MachineDuty pump(double flow, double head, int poles) {
        return new MachineDuty(flow, head, 0, 0, poles, Drive.FIXED, null, 0);
    }

    /** A centrifugal compressor's design point. */
    public static MachineDuty compressor(double flow, double polytropicHead) {
        return new MachineDuty(flow, 0, polytropicHead, 0, 0, Drive.FIXED, null, 0);
    }

    /** A motor's design point. */
    public static MachineDuty motor(double power, int poles, Drive drive) {
        return new MachineDuty(0, 0, 0, power, poles, drive, null, 0);
    }

    /** A generator's design point. */
    public static MachineDuty generator(double power, int poles) {
        return new MachineDuty(0, 0, 0, power, poles, Drive.FIXED, null, 0);
    }

    /** A gearbox's design point. */
    public static MachineDuty gearbox(double power) {
        return new MachineDuty(0, 0, 0, power, 0, Drive.FIXED, null, 0);
    }

    /** A steam turbine's design point. */
    public static MachineDuty turbine(double power, Steam steam, double exhaust) {
        return new MachineDuty(0, 0, 0, power, 0, Drive.FIXED, Objects.requireNonNull(steam, "steam"), exhaust);
    }

    /** An electric machine's synchronous speed at 50 Hz, rpm (3000, 1500, 1000, 750); 0 without poles. */
    public double speed() {
        return poles == 0 ? 0 : 6000.0 / poles;
    }

    /** Whether a turbine exhausts to a condenser. */
    public boolean vacuum() {
        return exhaust > 0 && exhaust < VACUUM;
    }

    /** This design point with {@code value} poles. */
    public MachineDuty withPoles(int value) {
        return new MachineDuty(flow, head, polytropicHead, power, value, drive, steam, exhaust);
    }

    /** This design point with drive {@code value}. */
    public MachineDuty withDrive(Drive value) {
        return new MachineDuty(flow, head, polytropicHead, power, poles, value, steam, exhaust);
    }

    /** This turbine's design point at inlet class {@code value}, its exhaust lowered to the highest of {@link #EXHAUSTS} at most half the inlet where it stood above. */
    public MachineDuty withSteam(Steam value) {
        double e = exhaust;
        if (e > value.pressure / 2) for (double x : EXHAUSTS) if (x <= value.pressure / 2) e = x;
        return new MachineDuty(flow, head, polytropicHead, power, poles, drive, value, e);
    }

    /** The R10 preferred numbers (ISO 3: 1, 1.25, 1.6, 2, 2.5, 3.15, 4, 5, 6.3, 8 in each decade) between {@code min} and {@code max}, both ends included. */
    public static List<Double> series(double min, double max) {
        double[] r10 = {1, 1.25, 1.6, 2, 2.5, 3.15, 4, 5, 6.3, 8};
        var out = new java.util.ArrayList<Double>();
        out.add(min);
        for (int k = (int) Math.floor(Math.log10(min)); k <= (int) Math.ceil(Math.log10(max)); k++)
            for (double m : r10) {
                double v = k >= 0 ? m * Math.pow(10, k) : m / Math.pow(10, -k);
                v = Math.round(v * 1e6) / 1e6;
                if (v > min + 1e-9 && v < max - 1e-9) out.add(v);
            }
        if (max > min) out.add(max);
        return List.copyOf(out);
    }

    /** {@code value} stepped {@code steps} places along {@code list} (ascending) from where it stands, held at its ends; off the list, the first step lands on its neighbour. */
    public static double step(List<Double> list, double value, int steps) {
        int at = 0;
        while (at < list.size() - 1 && list.get(at) < value - 1e-9) at++;
        boolean on = Math.abs(list.get(at) - value) < 1e-9;
        if (!on && steps < 0) {
            at--;
            steps++;
        }
        if (!on && steps > 0 && list.get(at) > value) steps--;
        return list.get(Math.clamp(at + steps, 0, list.size() - 1));
    }

    /** The value of {@code list} nearest {@code value}. */
    public static double nearest(List<Double> list, double value) {
        double best = list.getFirst();
        for (double v : list) if (Math.abs(v - value) < Math.abs(best - value)) best = v;
        return best;
    }
}
