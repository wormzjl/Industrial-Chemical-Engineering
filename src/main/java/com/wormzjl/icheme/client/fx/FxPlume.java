package com.wormzjl.icheme.client.fx;

/**
 * Whether an exhaust shows a white plume, and how far it reaches (CUSTOM_SHADER_SYSTEM_PLAN.md section 2). A plume is
 * condensed water: it shows where the mixing line between the exhaust and the ambient air crosses the saturation curve.
 * Along the line, the exhaust's share of the dry air is f (1 at the exit, 0 far downwind); humidity ratio and
 * temperature mix linearly in f, as the plan's table assumes. Saturation pressure over liquid water by the Buck (1996)
 * Magnus-type formula, humidity ratio 0.621945 p / (P − p) as {@code TowerTransfer} uses. Pure; names no Minecraft class.
 */
public final class FxPlume {
    /** Atmospheric pressure, Pa. */
    public static final double PRESSURE = 101_325;
    /** Molar mass ratio of water to dry air. */
    public static final double EPSILON = .621945;
    /**
     * A round jet's centre-line dilution: the exhaust share falls as f = 5 D / x a few diameters past the exit
     * (Fischer et al., Mixing in Inland and Coastal Waters, 1979: c/c₀ ≈ 5 D/x). A game-level estimate; it ignores
     * buoyancy and the cross-wind.
     */
    public static final double JET_DILUTION = 5;
    private static final int GOLDEN_STEPS = 80, BISECTION_STEPS = 60;

    private FxPlume() {
    }

    /** Saturation vapour pressure over liquid water at {@code celsius}, Pa (Buck 1996). Water below 0 °C is supercooled. */
    public static double saturationPressure(double celsius) {
        return 611.21 * Math.exp((18.678 - celsius / 234.5) * (celsius / (257.14 + celsius)));
    }

    /**
     * Humidity ratio at a vapour pressure {@code p} and total pressure {@code total}, kg water per kg dry air. Where
     * p ≥ P (saturated air above the boiling point) the air holds any amount of water: positive infinity.
     */
    public static double humidityRatio(double p, double total) {
        if (!(p < total)) return Double.POSITIVE_INFINITY;
        return EPSILON * Math.max(p, 0) / (total - p);
    }

    /** Saturation humidity ratio at {@code celsius} and atmospheric pressure, kg/kg. */
    public static double saturationRatio(double celsius) {
        return humidityRatio(saturationPressure(celsius), PRESSURE);
    }

    /** Humidity ratio of air at {@code celsius} and relative humidity {@code rh} (0 to 1), kg/kg. */
    public static double ambientRatio(double celsius, double rh) {
        return humidityRatio(Math.max(0, rh) * saturationPressure(celsius), PRESSURE);
    }

    /** Liquid water at exhaust share {@code f} of the mixing line, kg/kg dry air (negative: none, by that margin). */
    public static double excessAt(double f, double exhaustCelsius, double exhaustRatio, double ambientCelsius, double ambientRatio) {
        double w = f * exhaustRatio + (1 - f) * ambientRatio;
        double t = f * exhaustCelsius + (1 - f) * ambientCelsius;
        return w - saturationRatio(t);
    }

    /**
     * The most liquid water anywhere on the mixing line, kg/kg dry air; zero or below means no white plume. The saturation
     * ratio is convex in temperature, so the excess is concave in f and a golden-section search over [0, 1] finds its peak.
     *
     * @param exhaustCelsius exhaust temperature, °C
     * @param exhaustRatio   exhaust humidity ratio, kg water per kg dry air (or dry flue gas)
     * @param ambientCelsius ambient temperature, °C
     * @param ambientRh      ambient relative humidity, 0 to 1
     */
    public static double excess(double exhaustCelsius, double exhaustRatio, double ambientCelsius, double ambientRh) {
        return excessAt(peak(exhaustCelsius, exhaustRatio, ambientCelsius, ambientRh), exhaustCelsius, exhaustRatio, ambientCelsius,
                ambientRatio(ambientCelsius, ambientRh));
    }

    /** The exhaust share where the liquid water peaks. */
    public static double peak(double exhaustCelsius, double exhaustRatio, double ambientCelsius, double ambientRh) {
        double wa = ambientRatio(ambientCelsius, ambientRh);
        double lo = 0, hi = 1, g = (Math.sqrt(5) - 1) / 2;
        double a = hi - g * (hi - lo), b = lo + g * (hi - lo);
        double fa = excessAt(a, exhaustCelsius, exhaustRatio, ambientCelsius, wa), fb = excessAt(b, exhaustCelsius, exhaustRatio, ambientCelsius, wa);
        for (int i = 0; i < GOLDEN_STEPS; i++) {
            if (fa < fb) {
                lo = a;
                a = b;
                fa = fb;
                b = lo + g * (hi - lo);
                fb = excessAt(b, exhaustCelsius, exhaustRatio, ambientCelsius, wa);
            } else {
                hi = b;
                b = a;
                fb = fa;
                a = hi - g * (hi - lo);
                fa = excessAt(a, exhaustCelsius, exhaustRatio, ambientCelsius, wa);
            }
        }
        double f = (lo + hi) / 2;
        // The peak may sit at an end of the line: the exit itself (a saturated exhaust) or the ambient (fog).
        double best = excessAt(f, exhaustCelsius, exhaustRatio, ambientCelsius, wa);
        if (excessAt(1, exhaustCelsius, exhaustRatio, ambientCelsius, wa) >= best) return 1;
        if (excessAt(0, exhaustCelsius, exhaustRatio, ambientCelsius, wa) > best) return 0;
        return f;
    }

    /**
     * The smallest exhaust share that still holds liquid water: where the visible plume ends. {@code NaN} when there is no
     * plume; 0 when the ambient air itself is saturated (fog: the plume never clears).
     */
    public static double visibleEnd(double exhaustCelsius, double exhaustRatio, double ambientCelsius, double ambientRh) {
        double wa = ambientRatio(ambientCelsius, ambientRh);
        double top = peak(exhaustCelsius, exhaustRatio, ambientCelsius, ambientRh);
        if (!(excessAt(top, exhaustCelsius, exhaustRatio, ambientCelsius, wa) > 0)) return Double.NaN;
        if (excessAt(0, exhaustCelsius, exhaustRatio, ambientCelsius, wa) >= 0) return 0;
        double lo = 0, hi = top;
        for (int i = 0; i < BISECTION_STEPS; i++) {
            double mid = (lo + hi) / 2;
            if (excessAt(mid, exhaustCelsius, exhaustRatio, ambientCelsius, wa) > 0) hi = mid;
            else lo = mid;
        }
        return hi;
    }

    /**
     * The visible length of a plume from an exit {@code diameter} metres across that ends at exhaust share {@code end}
     * ({@link #visibleEnd}), m, along its axis: {@link #JET_DILUTION} D / f. Zero without a plume; {@code cap} when it never
     * clears.
     */
    public static double visibleLength(double diameter, double end, double cap) {
        if (Double.isNaN(end) || !(diameter > 0)) return 0;
        if (!(end > 0)) return cap;
        return Math.min(cap, JET_DILUTION * diameter / Math.min(end, 1));
    }
}
