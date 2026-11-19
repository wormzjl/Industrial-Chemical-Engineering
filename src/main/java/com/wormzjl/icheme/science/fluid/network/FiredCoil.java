package com.wormzjl.icheme.science.fluid.network;

/**
 * A fired heater's radiant coil (batch 2026-10-27-heat-exchanger-design, plan section 4, owner Q11): its burners heat the
 * stream towards the set temperature {@code setTemperature} (K), at most at its {@code duty} (W; 0 with the burners out)
 * times its {@code limit}. A signed flow takes the lesser of its actual demand
 * ({@code |ṁ| · max(0, h_set − h_in)}) and the available duty. At zero flow or above the set point it takes no heat.
 * The set enthalpy is the donor curve's enthalpy at the set temperature ({@link HeatCurve#enthalpyAt}, the vapour end of
 * a plateau). The planned {@code firing} share describes the start of the step: 1 when the duty covers its demand, the
 * available duty over demand when short, and 0 when no heat is needed or available. The flue gas crosses the heater's
 * convection coil ({@link ConvectionCoil}).
 *
 * <p>The heat comes from the burners' fuel (batch 2026-11-09-furnace-fuel-power, {@link Burners}): {@code limit}, a slice
 * input from 0 to 1, is the share of the design duty that the fuel delivered and the combustion-air fans allow: 0 with the
 * fans unpowered or no fuel, {@link Burners#radiant} of the fired heat over the duty when the fuel is short, 1 otherwise.
 * The fuel's heat is booked by the burners, the coil's own as its {@code HEAT} boundary.</p>
 *
 * @param partner the convection coil's connection id, 0 for none: the coil whose flue gas this one's heat sets
 */
public record FiredCoil(double setTemperature,double duty,double firing,long partner,double limit) implements HeatLaw {
    public FiredCoil {
        if(!(setTemperature>0)||!Double.isFinite(setTemperature)||!(duty>=0)||!Double.isFinite(duty)||!(firing>=0&&firing<=1)||!(limit>=0&&limit<=1))
            throw new IllegalArgumentException("Invalid fired coil");
    }
    /** A coil whose burners have all the fuel and air they ask for. */
    public FiredCoil(double setTemperature,double duty,double firing,long partner){this(setTemperature,duty,firing,partner,1);}
    /** The facts that shape the coil's equations, without its firing share and limit. */
    public record Shape(double setTemperature,double duty,long partner) {}
    @Override public Shape shape(){return new Shape(setTemperature,duty,partner);}
    @Override public double coldest(){return setTemperature;}
    @Override public double hottest(){return setTemperature;}
    @Override public HeatCurve.Films films(){return HeatCurve.Films.DEFAULT;}
    @Override public double heat(HeatCurve curve,double inlet,double massFlow) {
        return available()==0?0:Math.min(available(),requested(curve,inlet,massFlow));
    }
    /** The radiant heat the transported stream needs to reach the outlet set point, before the firing limit (W). */
    public double requested(HeatCurve curve,double inlet,double massFlow) {
        return massFlow==0?0:Math.abs(massFlow)*Math.max(0,curve.enthalpyAt(setTemperature)-inlet);
    }
    public FiredCoil withFiring(double value){return new FiredCoil(setTemperature,duty,value,partner,limit);}
    public FiredCoil withLimit(double value){return new FiredCoil(setTemperature,duty,firing,partner,value);}
    /** The most heat the coil may take now, W: its duty at its limit. */
    public double available(){return duty*limit;}
    /** This coil with the firing share for a step that starts drawing {@code massFlow} (kg/s) at {@code inlet} (J/kg) on {@code curve}. */
    public FiredCoil planned(HeatCurve curve,double inlet,double massFlow) {
        double rise=curve.enthalpyAt(setTemperature)-inlet,cap=available();
        if(!(rise>0)||cap==0)return withFiring(0);
        double demand=Math.abs(massFlow)*rise;
        return withFiring(demand<=cap?1:cap/demand);
    }
}
