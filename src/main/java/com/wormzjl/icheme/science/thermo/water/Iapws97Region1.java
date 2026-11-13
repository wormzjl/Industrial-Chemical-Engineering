package com.wormzjl.icheme.science.thermo.water;

/**
 * IF97 Region 1 (IAPWS R7-97(2012)), liquid water, in SI units on its own datum: the one table and evaluator of the
 * repository (F2 and X39 of batch 2026-11-13-codebase-review; before, the fluid network's {@code WaterRegion1} and the
 * water share each had one). {@link #evaluate} forms the superset of both former evaluators (the Gibbs function and its
 * derivatives up to third order in pressure), each sum accumulated term by term in the table's order, so every
 * property keeps the bits either evaluator gave. {@link #liquid} is the network's liquid water: the region held at the
 * triple point and at its 623.15 K boundary, continued past them.
 */
public final class Iapws97Region1 {
    /** IF97's specific gas constant of water, J/(kg K). */
    public static final double GAS_CONSTANT = 461.526;
    private static final double P_STAR = 16.53e6, T_STAR = 1386;
    // IAPWS Region 1 table, expressed in (pi - 7.1): odd-I coefficients have reversed signs.
    private static final int[] I = {0,0,0,0,0,0,0,0,1,1,1,1,1,1,2,2,2,2,2,3,3,3,4,4,4,5,8,8,21,23,29,30,31,32};
    private static final int[] J = {-2,-1,0,1,2,3,4,5,-9,-7,-1,0,1,3,-3,0,1,3,17,-4,0,6,-5,-2,10,-8,-11,-6,-29,-31,-38,-39,-40,-41};
    private static final double[] N = {
            .14632971213167,-.84548187169114,-3.756360367204,3.3855169168385,-.95791963387872,
            .15772038513228,-.016616417199501,.00081214629983568,-.00028319080123804,
            .00060706301565874,.018990068218419,.032529748770505,.021841717175414,.00005283835796993,
            -.00047184321073267,-.00030001780793026,.000047661393906987,-4.4141845330846e-6,
            -7.2694996297594e-16,.000031679644845054,2.8270797985312e-6,8.5205128120103e-10,
            -.0000022425281908,-6.5171222895601e-7,-1.4341729937924e-13,4.0516996860117e-7,
            -1.2734301741641e-9,-1.7424871230634e-10,6.8762131295531e-19,-1.4478307828521e-20,
            -2.6335781662795e-23,-1.1947622640071e-23,-1.8228094581404e-24,-9.3537087292458e-26};
    // Exponent span the 34 terms and their derivatives reach: x^0..x^32 and y^-43..y^17.
    private static final int HIGHEST_X = 32, LOWEST_Y = -43, HIGHEST_Y = 17;
    /** IF97 Region 1's own upper temperature boundary, K, and its upper pressure, Pa. */
    public static final double MAXIMUM_TEMPERATURE = 623.15, MAXIMUM_PRESSURE = 100e6;

    private Iapws97Region1() {}

    /**
     * Caller-owned scratch and result of {@link #evaluate}: the two power tables (those of {@code tau - 1.222} kept for
     * the last temperature) and the specific properties. Mutable: one solving thread.
     */
    public static final class State {
        private final double[] xp = new double[HIGHEST_X + 1], yp = new double[HIGHEST_Y - LOWEST_Y + 1];
        private double powersTemperature = Double.NaN;
        private double volume, enthalpy, entropy, heatCapacity, volumeT, volumeP, volumeTT, volumeTP, volumePP;
        public State() {}
        /** {@code v}, m3/kg. */
        public double volume() { return volume; }
        /** {@code h}, J/kg. */
        public double enthalpy() { return enthalpy; }
        /** {@code s}, J/(kg K). */
        public double entropy() { return entropy; }
        /** {@code c_p}, J/(kg K). */
        public double heatCapacity() { return heatCapacity; }
        public double volumeTemperatureDerivative() { return volumeT; }
        public double volumePressureDerivative() { return volumeP; }
        public double volumeSecondTemperatureDerivative() { return volumeTT; }
        public double volumeTemperaturePressureDerivative() { return volumeTP; }
        public double volumeSecondPressureDerivative() { return volumePP; }
    }

    /**
     * The region's sums at {@code (T, p)} into {@code state}, with no range or validity check (each caller applies its
     * own). The arithmetic and its order are fixed, so a reused state and a fresh one give the same bits.
     */
    public static void evaluate(double temperatureKelvin, double pressurePascal, State state) {
        double t = temperatureKelvin;
        double tau = T_STAR / t;
        if (t != state.powersTemperature) {
            double y = tau - 1.222;
            double[] yp = state.yp;
            yp[-LOWEST_Y] = 1.0;
            for (int k = -LOWEST_Y + 1; k < yp.length; k++) yp[k] = yp[k - 1] * y;
            double inverse = 1.0 / y;
            for (int k = -LOWEST_Y - 1; k >= 0; k--) yp[k] = yp[k + 1] * inverse;
            state.powersTemperature = t;
        }
        double x = pressurePascal / P_STAR - 7.1;
        // The integer powers, built once by multiplication instead of six Math.pow calls per term.
        double[] xp = state.xp, yp = state.yp;
        xp[0] = 1.0;
        for (int k = 1; k < xp.length; k++) xp[k] = xp[k - 1] * x;
        double g = 0, gp = 0, gpp = 0, gppp = 0, gt = 0, gtt = 0, gpt = 0, gptt = 0, gppt = 0;
        for (int k = 0; k < N.length; k++) {
            int i = I[k], jj = J[k], j = jj - LOWEST_Y;
            double n = N[k];
            g += n * xp[i] * yp[j];
            if (i != 0) gp += n * i * xp[i - 1] * yp[j];
            if (i > 1) gpp += n * i * (i - 1) * xp[i - 2] * yp[j];
            if (i > 2) gppp += n * i * (i - 1) * (i - 2) * xp[i - 3] * yp[j];
            if (jj != 0) gt += n * jj * xp[i] * yp[j - 1];
            if (jj != 0 && jj != 1) gtt += n * jj * (jj - 1) * xp[i] * yp[j - 2];
            if (i != 0 && jj != 0) gpt += n * i * jj * xp[i - 1] * yp[j - 1];
            if (i != 0 && jj != 0 && jj != 1) gptt += n * i * jj * (jj - 1) * xp[i - 1] * yp[j - 2];
            if (i > 1 && jj != 0) gppt += n * i * (i - 1) * jj * xp[i - 2] * yp[j - 1];
        }
        double r = GAS_CONSTANT;
        state.volume = r * t * gp / P_STAR;
        state.enthalpy = r * t * tau * gt;
        state.entropy = r * (tau * gt - g);
        state.heatCapacity = -r * tau * tau * gtt;
        state.volumeT = r / P_STAR * (gp - tau * gpt);
        state.volumeP = r * t * gpp / (P_STAR * P_STAR);
        state.volumeTT = r * tau * tau * gptt / (P_STAR * t);
        state.volumeTP = r / (P_STAR * P_STAR) * (gpp - tau * gppt);
        state.volumePP = r * t * gppp / (P_STAR * P_STAR * P_STAR);
    }

    /**
     * The network's liquid water at {@code (T, p)}: the region at the temperature held inside
     * {@code [triplePoint, 623.15 K]}, the volume and enthalpy continued past it with the held {@code dv/dT} and
     * {@code c_p} (value and slope continuous); every other property held. Refuses a state that is not finite and
     * positive, and a held evaluation that fails the region's own check ({@code v > 0}, {@code c_p > 0},
     * {@code dv/dp < 0}, finite {@code h}) or a continued volume that is not positive.
     */
    public static void liquid(double triplePoint, double temperatureKelvin, double pressurePascal, Liquid out) {
        if (!(temperatureKelvin > 0) || !Double.isFinite(temperatureKelvin) || !Double.isFinite(pressurePascal) || pressurePascal <= 0)
            throw new IllegalArgumentException("Water state is not finite and positive");
        double held = temperatureKelvin < triplePoint ? triplePoint : Math.min(temperatureKelvin, MAXIMUM_TEMPERATURE);
        evaluate(held, pressurePascal, out.region);
        State s = out.region;
        double v = s.volume, h = s.enthalpy, cp = s.heatCapacity, vt = s.volumeT, vp = s.volumeP;
        if (!(v > 0) || !(cp > 0) || !(vp < 0) || !Double.isFinite(h)) throw new IllegalArgumentException("Invalid Region 1 result");
        out.specificVolume = v; out.specificEnthalpy = h; out.specificInternalEnergy = h - pressurePascal * v; out.specificHeatCapacity = cp;
        out.volumeTemperatureDerivative = vt; out.volumePressureDerivative = vp; out.enthalpyPressureDerivative = v - held * vt;
        out.volumeSecondTemperatureDerivative = s.volumeTT;
        if (held != temperatureKelvin) {
            double step = temperatureKelvin - held;
            out.specificVolume += out.volumeTemperatureDerivative * step;
            out.specificEnthalpy += out.specificHeatCapacity * step;
            out.specificInternalEnergy = out.specificEnthalpy - pressurePascal * out.specificVolume;
        }
        if (!(out.specificVolume > 0)) throw new IllegalArgumentException("Invalid Region 1 result");
    }

    /** Caller-owned result of {@link #liquid}, specific (per kg), with its region scratch. */
    public static final class Liquid {
        private final State region = new State();
        private double specificVolume, specificEnthalpy, specificInternalEnergy, specificHeatCapacity,
                volumeTemperatureDerivative, volumePressureDerivative, enthalpyPressureDerivative, volumeSecondTemperatureDerivative;
        public Liquid() {}
        public double specificVolume() { return specificVolume; }
        public double specificEnthalpy() { return specificEnthalpy; }
        public double specificInternalEnergy() { return specificInternalEnergy; }
        /** c_p, J/(kg K): the region's at the evaluated temperature, the held end value past its range. */
        public double specificHeatCapacity() { return specificHeatCapacity; }
        public double volumeTemperatureDerivative() { return volumeTemperatureDerivative; }
        public double volumePressureDerivative() { return volumePressureDerivative; }
        public double enthalpyPressureDerivative() { return enthalpyPressureDerivative; }
        public double volumeSecondTemperatureDerivative() { return volumeSecondTemperatureDerivative; }
    }
}
