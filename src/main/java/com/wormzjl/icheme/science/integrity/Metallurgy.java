package com.wormzjl.icheme.science.integrity;

import com.wormzjl.icheme.science.math.DeterministicMath;

import java.util.Map;

/**
 * A structure's material of construction (batch 2026-11-16-fouling-corrosion, plan section 5, owner decision D2: one per
 * structure). Named "metallurgy" because "material" already means a compound ({@code MaterialCatalog}). Saved by name
 * ({@link #byName}): append-only; a renamed or removed constant gets an alias.
 * <table>
 * <caption>The four materials</caption>
 * <tr><th>Constant</th><th>Stands for</th><th>Allowance</th><th>Overheating limit</th><th>Cost index</th></tr>
 * <tr><td>{@link #CARBON_STEEL}</td><td>the default</td><td>3 mm</td><td>540 °C</td><td>1</td></tr>
 * <tr><td>{@link #CHROME_STEEL}</td><td>9Cr-1Mo</td><td>3 mm</td><td>650 °C</td><td>2</td></tr>
 * <tr><td>{@link #STAINLESS_STEEL}</td><td>316L</td><td>1.5 mm</td><td>815 °C</td><td>4</td></tr>
 * <tr><td>{@link #NICKEL_ALLOY}</td><td>Alloy 625</td><td>1.5 mm</td><td>980 °C</td><td>15</td></tr>
 * </table>
 * Two service limits, each a line in pressure and temperature (M10 and M11 of {@link CorrosionLaw}):
 * <ul>
 * <li><b>Hot hydrogen</b> (the Nelson curves of API RP 941 as a line), from {@code p_H2} = 7 bar:
 * {@code T_H = max(T_floor, T_7 − s log10(p_H2 / 7 bar))}; carbon steel 260 °C, 45 K per decade, floor 205 °C (232 °C at
 * 30 bar, 208 °C at 100 bar); chrome steel 600 °C, 120 K per decade, floor 450 °C (524 °C at 30 bar, 461 °C at 100 bar);
 * stainless steel and nickel alloy none.</li>
 * <li><b>Oxygen</b> (the exemption pressures of EIGA Doc 13 and CGA G-4.4): {@code p_150} up to 150 °C (2, 2, 14, 69 bar),
 * above it {@code max(0.3 bar, p_150 2^(−(T − 150 °C)/100 K))}: stainless steel 7 bar at 250 °C. The floor keeps air at
 * one atmosphere free at every temperature.</li>
 * </ul>
 * The factors on the carbon steel rate are the plan's table 6.3 ({@link #factor}). Pure; no Minecraft types.
 */
public enum Metallurgy {
    CARBON_STEEL(3, 540, 1, 260, 45, 205, 2),
    CHROME_STEEL(3, 650, 2, 600, 120, 450, 2),
    STAINLESS_STEEL(1.5, 815, 4, Double.NaN, Double.NaN, Double.NaN, 14),
    NICKEL_ALLOY(1.5, 980, 15, Double.NaN, Double.NaN, Double.NaN, 69);

    /** 0 °C in kelvin. */
    static final double ZERO_CELSIUS = 273.15;
    /** The hydrogen partial pressure, bar, below which no material sees hydrogen attack; also the {@code hydrogen} flag of {@link #factor}. */
    public static final double HYDROGEN_ONSET_BAR = 7;
    /** The temperature, K, up to which the oxygen limit is {@code p_150}, and the K per halving above it. */
    public static final double OXYGEN_KNEE = 150 + ZERO_CELSIUS, OXYGEN_HALVING = 100;
    /** The oxygen limit's floor, bar: air at one atmosphere never burns a wall. */
    public static final double OXYGEN_FLOOR_BAR = .3;

    /** Saved names that no longer name a constant, to the constant they stand for (none yet). */
    private static final Map<String, Metallurgy> ALIASES = Map.of();

    private final double allowance, limit, cost, hydrogen7, hydrogenSlope, hydrogenFloor, oxygen150;

    Metallurgy(double allowanceMm, double limitCelsius, double cost, double hydrogen7Celsius, double hydrogenSlope,
               double hydrogenFloorCelsius, double oxygen150Bar) {
        this.allowance = allowanceMm;
        this.limit = limitCelsius + ZERO_CELSIUS;
        this.cost = cost;
        this.hydrogen7 = hydrogen7Celsius + ZERO_CELSIUS;
        this.hydrogenSlope = hydrogenSlope;
        this.hydrogenFloor = hydrogenFloorCelsius + ZERO_CELSIUS;
        this.oxygen150 = oxygen150Bar;
    }

    /** The corrosion allowance, mm: the wall a design adds for loss. */
    public double allowance() { return allowance; }

    /** The overheating limit, K (API Std 530 tube metal temperatures): M8 acts above it. */
    public double limit() { return limit; }

    /** The cost index against carbon steel (owner O2: shown, not charged, in this batch). */
    public double cost() { return cost; }

    /**
     * The hot-hydrogen limit, K, at hydrogen partial pressure {@code pH2Bar}: positive infinity below 7 bar (and for NaN)
     * and for a material without one, else {@code max(T_floor, T_7 − s log10(p/7))}.
     */
    public double hydrogenLimit(double pH2Bar) {
        if (!(pH2Bar >= HYDROGEN_ONSET_BAR) || Double.isNaN(hydrogen7)) return Double.POSITIVE_INFINITY;
        return Math.max(hydrogenFloor, hydrogen7 - hydrogenSlope * DeterministicMath.log10(pH2Bar / HYDROGEN_ONSET_BAR));
    }

    /**
     * The oxygen limit, bar, at {@code temperatureK}: {@code p_150} up to 150 °C (and for NaN), above it
     * {@code max(0.3, p_150 2^(−(T − 423.15)/100))}.
     */
    public double oxygenLimit(double temperatureK) {
        if (!(temperatureK > OXYGEN_KNEE)) return oxygen150;
        return Math.max(OXYGEN_FLOOR_BAR, oxygen150 * DeterministicMath.pow(2, -(temperatureK - OXYGEN_KNEE) / OXYGEN_HALVING));
    }

    /**
     * The factor on the carbon steel rate of {@code mechanism} (plan table 6.3). {@code hydrogen}, a hydrogen partial
     * pressure of at least 7 bar, changes only sulfidation on chrome steel (H2/H2S, Couper–Gorman): 0.12 to 0.6.
     * {@link Mechanism#OVERHEATING}, {@link Mechanism#HYDROGEN_ATTACK} and {@link Mechanism#OXYGEN_FIRE} are the
     * material's own laws: factor 1.
     */
    public double factor(Mechanism mechanism, boolean hydrogen) {
        return switch (mechanism) {
            case SULFIDATION -> this == CHROME_STEEL && hydrogen ? .6 : pick(1, .12, .01, .01);
            case NAPHTHENIC_ACID -> pick(1, .6, .02, .01);
            case SOUR_WATER -> pick(1, 1, .01, .005);
            case WET_CO2 -> pick(1, .3, .005, .002);
            case AMMONIUM_BISULFIDE -> pick(1, 1, .2, .02);
            case ACID_CONDENSATE -> pick(1, 1, .1, .02);
            case AERATED_WATER -> pick(1, .6, .005, .002);
            case CORROSIVE_AIR -> pick(1, .6, .01, .005);
            case EROSION -> pick(1, .8, .5, .4);
            case OVERHEATING, HYDROGEN_ATTACK, OXYGEN_FIRE -> 1;
        };
    }

    /** This material's entry of a table row (exhaustive: a new constant must give its own). */
    private double pick(double carbon, double chrome, double stainless, double nickel) {
        return switch (this) {
            case CARBON_STEEL -> carbon;
            case CHROME_STEEL -> chrome;
            case STAINLESS_STEEL -> stainless;
            case NICKEL_ALLOY -> nickel;
        };
    }

    /** The constant saved as {@code name}, by its name or an alias; {@link #CARBON_STEEL} for null or an unknown name. */
    public static Metallurgy byName(String name) {
        if (name == null) return CARBON_STEEL;
        for (var m : values()) if (m.name().equals(name)) return m;
        return ALIASES.getOrDefault(name, CARBON_STEEL);
    }
}
