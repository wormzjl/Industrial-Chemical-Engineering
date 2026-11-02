import java.util.Locale;

/**
 * The ejector law of {@code SIZING_PLAN.md} section 3.12 (batch 2026-11-02-process-simulator): steam per kg of load from
 * El-Dessouky's correlation, as WaterTAP documents it (water on both sides, fitted for Ra below 4, compression above 1.89,
 * motive 100 to 3500 kPa), with the square root of the molar mass that vendors use to turn a gas into a dry-air load. A jet
 * law in the world compressor's isothermal heads was tried first: mean error 68 % over the correlation's range, and it needs
 * 3.6 kg/kg where the correlation needs 5.5 at a ratio of 7.7, so it was dropped. This uses no game classes:
 * {@code java -Duser.language=en tools/process-simulator/EjectorProbe.java}.
 */
public final class EjectorProbe {
    static final double WATER = 18.015;

    /** Steam per kg of load: pressures kPa; {@code t} the load's temperature, C; {@code mass} its molar mass. */
    static double steam(double motive, double suction, double discharge, double t, double mass) {
        // The polynomial's least: above 1500 kPa it turns up, which no ejector does, so it stays flat, with the same slope (zero) at the edge.
        double edge = Math.min(motive, 1500);
        double pcf = 3e-7 * edge * edge - .0009 * edge + 1.6101;
        double tcf = 2e-8 * t * t - .0006 * t + 1.0047;
        return .296 * Math.pow(discharge, 1.19) / Math.pow(suction, 1.04) * Math.pow(motive / suction, .015) * pcf / tcf * Math.sqrt(WATER / mass);
    }

    public static void main(String[] args) {
        System.out.println("motive 1000 kPa, discharge 120 kPa, load at 50 C: suction kPa, ratio, kg of steam per kg of load, by the load's molar mass");
        System.out.printf(Locale.ROOT, "%8s %6s %8s %8s %8s %8s%n", "suction", "ratio", "M 2", "M 18", "M 29", "M 100");
        for (double suction : new double[]{50, 30, 20, 12, 7, 4})
            System.out.printf(Locale.ROOT, "%8.0f %6.1f %8.2f %8.2f %8.2f %8.2f%n", suction, 120 / suction, steam(1000, suction, 120, 50, 2), steam(1000, suction, 120, 50, WATER), steam(1000, suction, 120, 50, 29), steam(1000, suction, 120, 50, 100));
        System.out.println("the same load at 12 kPa, by motive pressure, M 100");
        for (double motive : new double[]{300, 500, 1000, 2000, 3500, 5000}) System.out.printf(Locale.ROOT, "  %4.0f kPa: %.2f%n", motive, steam(motive, 12, 120, 50, 100));
        System.out.println("load factors against air, as the vendor's: H2 0.26, CH4 0.74, CO2 1.23");
        for (double mass : new double[]{2, 16, 44}) System.out.printf(Locale.ROOT, "  M %2.0f: %.2f%n", mass, steam(1000, 12, 120, 50, 29) / steam(1000, 12, 120, 50, mass));
        double load = 5000, need = steam(1000, 12, 120, 50, 100) * load;
        System.out.printf(Locale.ROOT, "a vacuum top: 5000 kg/h of M 100 at 12 kPa to 120 kPa on 10 bar steam: ratio 10, %.0f kg/h of steam, %.1f MW more on the condenser%n", need, need * 2.2e6 / 3600 / 1e6);
    }
}
