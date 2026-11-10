package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.math.DeterministicMath;

/**
 * A flare stack's combustion and rating (batch 2026-11-09-flare-stack, FLARE_STACK_PLAN.md): pure arithmetic on the vent
 * gas the flare receives, by component amounts, lower heating values (J/mol, the material records' {@code combustion}) and
 * formulas (atoms of C, H, N, O, S, their {@code thermochemistry}).
 *
 * <ul>
 * <li>The gas holds its flame while its stoichiometric adiabatic flame temperature with air at 298 K is at least
 * {@link #FLAME_LIMIT} (owner X1, X1b): its heat release Σ n·LHV heats the products the formulas give (CO2, H2O, SO2,
 * N2, the air's N2, any excess O2) over their mean heat capacities from 298.15 K to 1700 K. Methane at its nitrogen
 * inerting limit (Bulletin 627, about 6 N2 : 1 CH4) gives 1700 K. The feed's own sensible heat is left out.</li>
 * <li>Its flame is stable up to the exit velocity {@link #stableVelocity} of its net heating value per standard m³
 * (40 CFR 60.18: 18.3 m/s, up to log10 V = (H + 28.8) / 31.7, at most 122 m/s).</li>
 * <li>One size, the tip diameter (owner X3): its rated heat release is methane at its stable velocity through the tip, and
 * its stack is tall enough that the flame's radiation at the base stays at {@link #ALLOWED_FLUX} (API 521 simple method,
 * radiated fraction {@link #RADIATED}, flame length {@link #flameLength}).</li>
 * <li>Its flame's presentation (batch 2026-11-10-custom-shader-system, W3): a soot index from the gas's carbon-to-hydrogen
 * ratio and the load ({@link #soot}), and the exit velocity at the tip ({@link #exitVelocity}).</li>
 * </ul>
 */
public final class Flare {
    private Flare() {
    }

    /** The flame holds at or above this stoichiometric adiabatic flame temperature, K (owner X1b). */
    public static final double FLAME_LIMIT = 1700;
    /** The air's and products' reference temperature, K. */
    public static final double AMBIENT = 298.15;
    /** A standard cubic metre's moles: 20 °C and 1 atm, 0.02404 m³/mol (40 CFR 60.18). */
    public static final double STANDARD_VOLUME = .02404;
    /** Nitrogen per oxygen in air. */
    public static final double AIR_NITROGEN = 3.76;
    /**
     * The products' enthalpy rise from 298.15 to 1700 K, J/mol (JANAF tables, rounded): CO2, H2O (gas), N2, O2, SO2. Over
     * 1401.85 K they are the mean heat capacities the flame temperature takes.
     */
    static final double CO2 = 73.5e3, H2O = 57.8e3, N2 = 45.4e3, O2 = 48.0e3, SO2 = 72.5e3;
    /** Exit velocities of 40 CFR 60.18, m/s: always stable below, never above. */
    public static final double LOW_VELOCITY = 18.3, HIGH_VELOCITY = 122;
    /** The flame's radiated share of its heat release, and the flux allowed at the stack's base, W/m² (continuous exposure). */
    public static final double RADIATED = .2, ALLOWED_FLUX = 1580;
    /** Tip diameters, m: the one size. */
    public static final double MIN_TIP = .1, MAX_TIP = .6, TIP_STEP = .05;
    /** Methane at 298.15 K and 1 atm: density kg/m³, heating value J/kg and per standard m³ (the rating's reference gas). */
    static final double METHANE_DENSITY = 101325 * .016043 / (8.314462618 * AMBIENT), METHANE_PER_KG = 802618 / .016043, METHANE_NHV = 802618 / STANDARD_VOLUME;

    /** The vent gas's heat release, J per mole of gas, of {@code moles} (any amounts); 0 for no gas. */
    public static double releasePerMole(double[] moles, double[] heatingValues) {
        double heat = 0, total = 0;
        for (int c = 0; c < moles.length; c++) {
            double n = Math.max(0, moles[c]);
            heat += n * heatingValues[c];
            total += n;
        }
        return total > 0 ? heat / total : 0;
    }

    /** The vent gas's net heating value, J per standard m³. */
    public static double netHeatingValue(double[] moles, double[] heatingValues) {
        return releasePerMole(moles, heatingValues) / STANDARD_VOLUME;
    }

    /**
     * The stoichiometric adiabatic flame temperature, K, of gas {@code moles} burnt with air at 298.15 K: {@link #AMBIENT}
     * for a gas that releases nothing.
     *
     * @param formulas atoms of C, H, N, O, S per molecule, per component (the material records' formulas)
     */
    public static double flameTemperature(double[] moles, double[] heatingValues, double[][] formulas) {
        double heat = 0, c = 0, h = 0, n = 0, o = 0, s = 0;
        for (int k = 0; k < moles.length; k++) {
            double m = Math.max(0, moles[k]);
            if (m == 0) continue;
            heat += m * heatingValues[k];
            var f = formulas[k];
            c += m * f[0];
            h += m * f[1];
            n += m * f[2];
            o += m * f[3];
            s += m * f[4];
        }
        if (!(heat > 0)) return AMBIENT;
        double oxygen = c + h / 4 + s - o / 2;
        double rise = c * CO2 + h / 2 * H2O + s * SO2 + (n / 2 + Math.max(0, oxygen) * AIR_NITROGEN) * N2 + Math.max(0, -oxygen) * O2;
        return rise > 0 ? AMBIENT + heat * (FLAME_LIMIT - AMBIENT) / rise : Double.POSITIVE_INFINITY;
    }

    /** Whether gas at flame temperature {@code temperature} holds its flame. */
    public static boolean burns(double temperature) {
        return temperature >= FLAME_LIMIT;
    }

    /** The highest exit velocity at which a flame of net heating value {@code nhv} (J/scm) stays on the tip, m/s. */
    public static double stableVelocity(double nhv) {
        double h = Math.max(0, nhv) / 1e6;
        return Math.max(LOW_VELOCITY, Math.min(HIGH_VELOCITY, DeterministicMath.pow(10, (h + 28.8) / 31.7)));
    }

    /** The tip's flow area, m². */
    public static double tipArea(double tip) {
        return Math.PI * tip * tip / 4;
    }

    /** Atomic masses of carbon and hydrogen, kg/mol. */
    static final double CARBON = .012011, HYDROGEN = .001008;
    /**
     * The soot index's rule (batch 2026-11-10-custom-shader-system, owner X7 A; a game-level rule, not a smoke-point model):
     * no soot up to a carbon-to-hydrogen mass ratio of {@link #SOOT_FROM} (methane 2.98), all of it at {@link #SOOT_FROM}
     * plus {@link #SOOT_SPAN} (ethylene 5.96, benzene 11.9), and the full index from {@link #SOOT_LOAD} of the rated heat.
     */
    public static final double SOOT_FROM = 3.2, SOOT_SPAN = 2.8, SOOT_LOAD = 1 / 1.25;

    /**
     * The flared gas's sooting carbon over its hydrogen, by mass, of {@code moles} (any amounts) of components of
     * {@code formulas}: a molecule's carbon less its oxygen atoms counts (carbon bound to oxygen, as in CO, CO2 or an
     * alcohol's, burns without soot). 0 for no sooting carbon; +Infinity for sooting carbon without hydrogen.
     */
    public static double carbonToHydrogen(double[] moles, double[][] formulas) {
        double carbon = 0, hydrogen = 0;
        for (int k = 0; k < moles.length; k++) {
            double m = Math.max(0, moles[k]);
            if (m == 0) continue;
            var f = formulas[k];
            carbon += m * Math.max(0, f[0] - f[3]);
            hydrogen += m * f[1];
        }
        if (!(carbon > 0)) return 0;
        return hydrogen > 0 ? carbon * CARBON / (hydrogen * HYDROGEN) : Double.POSITIVE_INFINITY;
    }

    /**
     * The flame's soot index, 0 to 1 (owner X7 A): clamp((C/H − 3.2) / 2.8, 0, 1) × clamp(1.25 × load, 0, 1), C/H the gas's
     * {@link #carbonToHydrogen} and {@code load} its heat released over its rating. Methane gives none; ethane slight (0.28
     * at full load), propane 0.45, olefins and aromatics all of it.
     */
    public static double soot(double[] moles, double[][] formulas, double load) {
        double ratio = carbonToHydrogen(moles, formulas);
        double rich = ratio == Double.POSITIVE_INFINITY ? 1 : Math.clamp((ratio - SOOT_FROM) / SOOT_SPAN, 0, 1);
        return rich * Math.clamp(load / SOOT_LOAD, 0, 1);
    }

    /** The gas's exit velocity at a tip {@code tip} m across, m/s: {@code massFlow} kg/s at {@code density} kg/m³ over the tip's area. */
    public static double exitVelocity(double massFlow, double density, double tip) {
        return density > 0 && tip > 0 ? Math.max(0, massFlow) / density / tipArea(tip) : 0;
    }

    /** The rated heat release of a tip {@code tip} m across, W: methane at its stable velocity. */
    public static double rated(double tip) {
        return METHANE_DENSITY * stableVelocity(METHANE_NHV) * tipArea(tip) * METHANE_PER_KG;
    }

    /** The flame length of heat release {@code heat} W, m (the API 521 figure's worksheet form). */
    public static double flameLength(double heat) {
        return heat > 1 ? DeterministicMath.exp(.4562 * DeterministicMath.log(heat) - 5.3603) : 0;
    }

    /** The stack height for tip {@code tip}, whole metres: its rated flame's centre far enough above the base. */
    public static int stackHeight(double tip) {
        double q = rated(tip), distance = Math.sqrt(RADIATED * q / (4 * Math.PI * ALLOWED_FLUX));
        return (int) Math.ceil(distance - flameLength(q) / 2 - 1e-9);
    }

    /**
     * The most the flare passes, kg/s, of gas whose heating value is {@code perKilogram} J/kg and {@code nhv} J/scm, at
     * density {@code density} kg/m³ through tip {@code tip} (owner X4): its rated heat release, and its stable exit velocity.
     */
    public static double limit(double tip, double perKilogram, double nhv, double density) {
        double byVelocity = density * stableVelocity(nhv) * tipArea(tip);
        return perKilogram > 0 ? Math.min(byVelocity, rated(tip) / perKilogram) : byVelocity;
    }
    /**
     * Gas at a flare's tip: its heat, J/kg, its net heating value, J/scm, its flame temperature, K, its density at the tip,
     * kg/m³, and the most the tip passes of it, kg/s.
     */
    public record Gas(double perKilogram, double nhv, double flame, double density, double limit) {
        public boolean burns() {
            return Flare.burns(flame);
        }
    }

    /**
     * Gas {@code moles} (any amounts) of components of {@code molarMasses} (kg/mol), {@code heatingValues} (J/mol) and
     * {@code formulas}, leaving tip {@code tip} at {@code temperature} K and atmospheric pressure.
     */
    public static Gas gas(double[] moles, double[] molarMasses, double[] heatingValues, double[][] formulas, double temperature, double tip) {
        double heat = 0, total = 0, mass = 0;
        for (int c = 0; c < moles.length; c++) {
            double n = Math.max(0, moles[c]);
            heat += n * heatingValues[c];
            total += n;
            mass += n * molarMasses[c];
        }
        double perKilogram = mass > 0 ? heat / mass : 0, nhv = netHeatingValue(moles, heatingValues);
        double density = total > 0 && temperature > 0 ? 101325 * (mass / total) / (8.314462618 * temperature) : METHANE_DENSITY;
        return new Gas(perKilogram, nhv, flameTemperature(moles, heatingValues, formulas), density, limit(tip, perKilogram, nhv, density));
    }
}
