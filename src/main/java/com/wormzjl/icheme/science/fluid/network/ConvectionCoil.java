package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.math.DeterministicMath;

/**
 * A fired heater's convection coil (batch 2026-10-27-heat-exchanger-design, the owner's test of 2026-10-28, proposal F1): a
 * counterflow exchanger between the stream and the flue gas that leaves the radiant box. The burners' gas gives the radiant
 * coil ({@link FiredCoil}) its heat Q_r as it cools from the flame ({@link #FLAME}) to the bridgewall ({@link #BRIDGEWALL}),
 * so it carries the capacity rate C_g = Q_r / (T_flame − T_bridgewall) into this coil, the {@code flue} (W/K): about 1 kW/K
 * per MW of radiant heat. The coil's heat is ε C_min (T_bridgewall − T_in) over its {@code conductance} UA (W/K), with the
 * stream's mean capacity rate between its inlet and the bridgewall on its curve (a plateau's latent heat inside). So its
 * outlet never passes the bridgewall, it never takes more than the gas holds, and it moves nothing at no flow or without
 * flue gas (the burners out, the radiant coil still). A mean capacity rate misses a pinch inside a boiling plateau by a
 * few K (OWNER_TEST_REVIEW.md section 1).
 *
 * <p>{@code flue} is a slice input: planned at each step from the radiant coil's heat where both coils are in one island
 * ({@link PassiveStepSolver#plan}), else at each slice from the radiant coil's heat as its island committed it
 * ({@link PassiveStepSolver#couple}, the exchanger's relay). Fuel is another scope: the heat enters from outside, booked
 * as the coil's {@code HEAT} boundary.
 *
 * @param radiant the radiant coil's connection id, 0 for none (no flue gas)
 */
public record ConvectionCoil(double conductance,long radiant,double flue) implements HeatLaw {
    /** The flame and the bridgewall, K: 1800 °C (natural gas at about 15 % excess air) and 800 °C (API 560 heaters run 700 to 1000 °C). */
    public static final double FLAME=2073.15,BRIDGEWALL=1073.15;
    public ConvectionCoil {
        if(!(conductance>0)||!Double.isFinite(conductance)||!(flue>=0)||!Double.isFinite(flue))throw new IllegalArgumentException("Invalid convection coil");
    }
    /** The flue gas's capacity rate, W/K, behind a radiant coil taking {@code radiantHeat} W: what the gas gave between the flame and the bridgewall. */
    public static double flueOf(double radiantHeat){return Math.max(0,radiantHeat)/(FLAME-BRIDGEWALL);}
    /** The facts that shape the coil's equations, without its flue gas. */
    public record Shape(double conductance,long radiant) {}
    @Override public Shape shape(){return new Shape(conductance,radiant);}
    @Override public double coldest(){return BRIDGEWALL;}
    @Override public double hottest(){return BRIDGEWALL;}
    @Override public HeatCurve.Films films(){return HeatCurve.Films.DEFAULT;}
    @Override public double heat(HeatCurve curve,double inlet,double massFlow) {
        double mass=Math.abs(massFlow);
        if(mass==0||flue==0)return 0;
        double span=BRIDGEWALL-curve.temperatureAt(inlet);
        if(span==0)return 0;
        double stream=mass*(curve.enthalpyAt(BRIDGEWALL)-inlet)/span;
        if(!(stream>0)||!Double.isFinite(stream))return 0;
        // Counterflow, symmetric in the two capacity rates: Q = ΔT (1 − e^(−y)) / (1/C_min − e^(−y) / C_max), y = UA (1/C_min − 1/C_max) ≥ 0.
        double most=Math.max(1/stream,1/flue),least=Math.min(1/stream,1/flue),y=conductance*(most-least);
        double drop=DeterministicMath.expm1(-y);
        return y==0?span/(1/conductance+least):span*-drop/(y/conductance-least*drop);
    }
    public ConvectionCoil withFlue(double value){return new ConvectionCoil(conductance,radiant,value);}
}
