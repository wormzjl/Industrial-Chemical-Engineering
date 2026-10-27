package com.wormzjl.icheme.science.fluid.network;

/**
 * A fired heater's coil (batch 2026-10-27-heat-exchanger-design, plan section 4, owner Q11): its burners heat the stream
 * towards the set temperature {@code setTemperature} (K), the share {@code reach} of the way (the radiant coil all of it,
 * the convection coil its section's share of the heater's absorbed duty), at most at its {@code duty} (W; 0 with the
 * burners out). The heat is β · reach · |ṁ| · (h_set − h_in), h_set the donor curve's enthalpy at the set temperature
 * ({@link HeatCurve#enthalpyAt}, the vapour end of a plateau): linear in the flow, zero at no flow, and while β is 1 it
 * never carries the outlet past the set point. β, the {@code firing} share, is a slice input that {@link #planned} sets
 * for a step from its start: 1 while the duty covers the demand, the duty over the demand when it does not (the coil
 * fires at its maximum and the outlet stays below the set point), 0 for a stream that arrives at or above the set point.
 * Fuel is another scope: the heat enters from outside, booked as the coil's {@code HEAT} boundary.
 */
public record FiredCoil(double setTemperature,double reach,double duty,double firing) implements HeatLaw {
    public FiredCoil {
        if(!(setTemperature>0)||!Double.isFinite(setTemperature)||!(reach>0&&reach<=1)||!(duty>=0)||!Double.isFinite(duty)||!(firing>=0&&firing<=1))
            throw new IllegalArgumentException("Invalid fired coil");
    }
    /** The facts that shape the coil's equations, without its firing share. */
    public record Shape(double setTemperature,double reach,double duty) {}
    @Override public Shape shape(){return new Shape(setTemperature,reach,duty);}
    @Override public double coldest(){return setTemperature;}
    @Override public double hottest(){return setTemperature;}
    @Override public HeatCurve.Films films(){return HeatCurve.Films.DEFAULT;}
    @Override public double heat(HeatCurve curve,double inlet,double massFlow) {
        double mass=Math.abs(massFlow);
        if(mass==0||firing==0)return 0;
        return firing*reach*mass*(curve.enthalpyAt(setTemperature)-inlet);
    }
    public FiredCoil withFiring(double value){return new FiredCoil(setTemperature,reach,duty,value);}
    /** This coil with the firing share for a step that starts drawing {@code massFlow} (kg/s) at {@code inlet} (J/kg) on {@code curve}. */
    public FiredCoil planned(HeatCurve curve,double inlet,double massFlow) {
        double rise=curve.enthalpyAt(setTemperature)-inlet;
        if(!(rise>0)||duty==0)return withFiring(0);
        double demand=reach*Math.abs(massFlow)*rise;
        return withFiring(demand<=duty?1:duty/demand);
    }
}
