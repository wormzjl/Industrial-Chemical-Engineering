package com.wormzjl.icheme.science.fluid.network;

/**
 * How a connection exchanges heat (batch 2026-10-27-heat-exchanger-design): through a wall an exchanger's other side makes
 * ({@link HeatSide}), against an air cooler's air ({@link AirCooled}) or from a fired heater's burners ({@link FiredCoil}). Its heat is a
 * pure function of the connection's flow and the donor's specific enthalpy for a step, on the donor's {@link HeatCurve}:
 * no unknown, no mode, exactly zero at no flow. Facts shape its equations ({@link #shape}); slice inputs are fixed for a
 * step by whoever plans it.
 */
public sealed interface HeatLaw permits HeatSide, AirCooled, FiredCoil {
    /**
     * The heat (W) the stream takes up in passing, positive when it is heated, drawn at specific enthalpy {@code inlet}
     * (J/kg) on {@code curve} at {@code massFlow} (kg/s; its sign picks the direction).
     */
    double heat(HeatCurve curve,double inlet,double massFlow);
    /** The coldest and hottest temperatures the stream may approach, K: the donor's curve spans them, 10 K beyond. */
    double coldest();
    double hottest();
    /** The films the donor's curve carries. */
    HeatCurve.Films films();
    /** The facts that shape the law's equations, without its slice inputs: part of the connection's identity. */
    Record shape();
}
