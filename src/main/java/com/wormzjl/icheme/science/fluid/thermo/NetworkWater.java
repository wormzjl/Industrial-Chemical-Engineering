package com.wormzjl.icheme.science.fluid.thermo;

import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics.WaterLiquid;

/**
 * The pure-water functions of a network model (finding F8 of batch 2026-11-13-codebase-review): utility water and steam
 * on the package's water model, and pure water's saturation curve. One per {@link FluidThermodynamics}, built in its
 * constructor; the model's public methods of the same names forward here. A view reads its whole model's curve.
 */
final class NetworkWater {
    private final FluidThermodynamics model;
    NetworkWater(FluidThermodynamics model){this.model=model;}
    /**
     * Utility water (cooling towers, steam services) at the state pressure: Region 1 and its continuation past its range
     * ({@link WaterRegion1#evaluateLiquid}), on the package's water model. Not a network phase since W3b.
     */
    public WaterLiquid waterLiquid(double t,double p) {
        var w=new WaterRegion1.Workspace();WaterRegion1.evaluateLiquid(model.water,t,p,w);
        return new WaterLiquid(w.specificVolume()*model.water.molarMass(),w.specificEnthalpy()*model.water.molarMass()+model.waterEnthalpyOffset);
    }
    /**
     * Utility steam's ideal-gas molar enthalpy at {@code t} (J/mol, the datum of {@link #waterLiquid}): the water record's
     * Shomate polynomial, held at its end heat capacity past its range (owner rule of 2026-10-16). A cooling tower's
     * humid air; not a network phase since W3b.
     */
    public double vaporWaterEnthalpy(double t) {
        if(!Double.isFinite(t))throw new IllegalArgumentException("Non-finite water temperature");
        return com.wormzjl.icheme.science.thermo.water.SteamIdealGas.enthalpy(model.waterConstants,t);
    }
    /**
     * Pure water's saturation pressure at {@code t} (Pa): steam services, ejectors, the cooling tower's air and the
     * condensate a kettle leaves. On a model with water it is the model's own curve ({@link WaterSaturation}, built once
     * for the whole model on the first read), so a state built at saturation is the phase the flash calls it (W3b, G2a:
     * Wagner-Pruss stood up to 0.43 K off the cubic's between 100 and 220 bar); on a dry model the package's water
     * record's Wagner-Pruss, continued past its ends. Infinite at and above the critical temperature.
     */
    public double saturationPressure(double t) {
        if(!Double.isFinite(t))throw new IllegalArgumentException("Non-finite water temperature");
        var curve=model.whole.pureWater.waterSaturation();
        if(curve!=null)return curve.pressure(t);
        return t>=model.water.criticalTemperature()?Double.POSITIVE_INFINITY:com.wormzjl.icheme.science.thermo.water.WagnerPruss.pressure(model.waterConstants,t);
    }
    /** The temperature whose {@link #saturationPressure} is {@code p} (Pa), K: the saturation a state at {@code p} is built at. */
    public double saturationTemperature(double p) {
        var curve=model.whole.pureWater.waterSaturation();
        if(curve!=null)return curve.temperature(p);
        if(!(p>0))throw new IllegalArgumentException("Positive pressure required");
        double low=1,high=model.water.criticalTemperature();
        while(high-low>1e-9){double middle=0.5*(low+high);if(saturationPressure(middle)>=p)high=middle;else low=middle;}
        return high;
    }
    /** The whole model's water saturation curve, built on first use; null on a dry model. */
    private volatile WaterSaturation waterSaturation;
    private WaterSaturation waterSaturation() {
        if(!model.hydrocarbon.wet())return null;
        var curve=waterSaturation;
        if(curve!=null)return curve;
        synchronized(model) {
            if(waterSaturation==null)waterSaturation=new WaterSaturation(model.hydrocarbon,model.hydrocarbon.componentCount(),model.water.triplePoint(),model.water.criticalTemperature(),
                    t->com.wormzjl.icheme.science.thermo.water.WagnerPruss.pressure(model.waterConstants,t));
            return waterSaturation;
        }
    }
}
