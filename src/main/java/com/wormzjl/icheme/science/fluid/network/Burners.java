package com.wormzjl.icheme.science.fluid.network;

/**
 * A fired heater's burners (batch 2026-11-09-furnace-fuel-power, FURNACE_FUEL_POWER_PLAN.md section 1): what fuel and
 * combustion air the two-coil model ({@link FiredCoil}, {@link ConvectionCoil}) burns. Pure arithmetic.
 *
 * <p>Fuel fired at Q_f (W, on its lower heating value) heats its flue gas from the combustion air at {@link #AIR} to the
 * flame ({@link ConvectionCoil#FLAME}), so the gas carries C_g = Q_f / (T_flame − T_air). The radiant coil takes it from the
 * flame to the bridgewall: Q_r = C_g (T_flame − T_bw), the same C_g the convection coil reads ({@link ConvectionCoil#flueOf}).
 * So Q_f = Q_r · {@link #FIRED_PER_RADIANT} (1.775): at the design split (70/30 on a 250 °C crude) the heater absorbs 80 % of
 * the fuel's heat and its stack leaves at about 370 °C, as API 560 heaters without an air preheater run (75 to 85 %).</p>
 *
 * <p>The combustion air is the internal forced-draft fans': 0.40 kg per MJ fired (0.34 at stoichiometry for hydrocarbon
 * fuels, 15 % excess as the flame temperature assumes), 0.34 m³/s per MW at 25 °C, over 1.5 kPa of burner registers and
 * ducts at 70 %: {@link #FAN_SHAFT_PER_FIRED} 0.72 W at the shaft per kW fired. The fans follow the firing.</p>
 */
public final class Burners {
    private Burners() {}
    /** The combustion air the fans draw, K: 25 °C, as an air cooler's air. */
    public static final double AIR=298.15;
    /** Fuel fired per watt the radiant coil takes. */
    public static final double FIRED_PER_RADIANT=(ConvectionCoil.FLAME-AIR)/(ConvectionCoil.FLAME-ConvectionCoil.BRIDGEWALL);
    /** The forced-draft fans' shaft power per watt fired. */
    public static final double FAN_SHAFT_PER_FIRED=.40e-6*(1/1.185)*1500/.70;
    /**
     * The firebox's back pressure on the burner tips, Pa absolute: the fuel's line must deliver above it. Gas burners take 0.3
     * to 2 bar(g) at the tips; 1 bar(g).
     */
    public static final double PRESSURE=201325;
    /** The share of the fuel asked for below which the fuel counts short (LOW FUEL). */
    public static final double SHORT=.98;
    /** Fuel asked for over the demand: the cap stands just above it, so the coil reaches its set point. */
    public static final double MARGIN=1.01;
    /** The fuel fired, W, behind a radiant coil taking {@code radiant} W. */
    public static double fired(double radiant){return Math.max(0,radiant)*FIRED_PER_RADIANT;}
    /** The radiant coil's most heat, W, from {@code fired} W of fuel. */
    public static double radiant(double fired){return Math.max(0,fired)/FIRED_PER_RADIANT;}
    /** The coil's limit ({@link FiredCoil#limit}) for {@code fired} W of fuel delivered to a coil of {@code duty} W. */
    public static double limit(double fired,double duty){return duty>0?Math.min(1,radiant(fired)/duty):0;}
    /** The fans' shaft power, W, at {@code fired} W of fuel. */
    public static double fanPower(double fired){return FAN_SHAFT_PER_FIRED*Math.max(0,fired);}
    /**
     * The fuel's heat, W per kg/s, of a stream of {@code moles} (any amounts) of components of {@code molarMasses} (kg/mol)
     * and {@code heatingValues} (J/mol): 0 for a stream that gives none.
     */
    public static double perKilogram(double[] moles,double[] molarMasses,double[] heatingValues) {
        double heat=0,mass=0;
        for(int c=0;c<moles.length;c++){double n=Math.max(0,moles[c]);heat+=n*heatingValues[c];mass+=n*molarMasses[c];}
        return mass>0?heat/mass:0;
    }
}
