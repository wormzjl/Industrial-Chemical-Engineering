package com.wormzjl.icheme.science.fluid.network;
import com.wormzjl.icheme.science.math.DeterministicMath;

import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;

/**
 * Steady water/air contact as direct correlations, fitted to a marched counterflow fill (the table and the fit are in
 * {@code tools/cooling-towers/}). The ambient air is fixed, so its wet bulb is a constant. Nothing here iterates: a fill runs
 * inside every Newton residual of its island.
 */
public final class TowerTransfer {
    private TowerTransfer() {}
    private static final double CP_WATER = 4186, LATENT = 2.43e6, GRAVITY = 9.80665;
    /** Sensible exchange at the basin surface, W/(m² K). */
    private static final double SURFACE_COEFFICIENT = 5;

    /** Positive mass and energy leave the water. {@code dutyWatt} is the heat the water gave up; {@code energyWatt}, what the evaporated water carries out with it. */
    public record Result(double outletTemperature, double evaporation, double dutyWatt, double energyWatt, double dryAirFlow, double fanPowerWatt, double speed) {
        public Result {
            for(double value:new double[]{outletTemperature,evaporation,dutyWatt,energyWatt,dryAirFlow,fanPowerWatt,speed})
                if(!Double.isFinite(value))throw new IllegalArgumentException("Nonfinite tower transfer");
            if(outletTemperature<=0||fanPowerWatt<0||speed<0||speed>1)throw new IllegalArgumentException("Invalid tower transfer");
        }
    }
    private static double liquid(FluidThermodynamics model, double t) {
        return model.waterLiquid(t, CoolingTower.PRESSURE).molarEnthalpy() / model.waterMolecularWeight;
    }
    /** The draft of warm water in the stack, Pa; negative below 297 K, where the air falls. */
    private static double buoyancy(CoolingTower tower,double loading,double inlet) {
        double span=inlet-297,lift=Math.max(1,tower.height()-tower.distributorHeight());
        return span==0?0:GRAVITY*lift*.00819*Math.signum(span)*DeterministicMath.pow(Math.abs(span),.838)*loading/(loading+1.66);
    }
    /** The air flux a pressure moves against the fill and the stack's resistance, kg/(s m²). */
    private static double flux(CoolingTower tower,double pressure) {
        return Math.signum(pressure)*Math.sqrt(2*CoolingTower.AIR_DENSITY*Math.abs(pressure)/(3+3*tower.fillDepth()));
    }
    /** The transfer units at unit air flux: the approach to the wet bulb is {@code 1-exp(-units flux^.59)}. */
    private static double units(CoolingTower tower,double loading,double inlet) {
        return .799*DeterministicMath.pow(tower.fillDepth()/2,.322)*DeterministicMath.pow(.5,.59)*DeterministicMath.pow(loading/2,-.874)*DeterministicMath.exp(.0186*(inlet-308.15));
    }
    /** {@code water} and {@code inlet} over the whole tower; extensive results are the tower's. */
    public static Result fill(FluidThermodynamics model, CoolingTower tower, double water, double inlet, double speed) {
        double loading=water/tower.area(),fan=tower.draft()==CoolingTower.Draft.INDUCED?CoolingTower.FAN_PRESSURE*speed*speed:0;
        double flux=flux(tower,fan+buoyancy(tower,loading,inlet)),air=flux*tower.area();
        if(water<=0||flux==0)return new Result(inlet,0,0,0,air,tower.fanPower(speed),speed);
        double fall=(inlet-CoolingTower.WET_BULB)*-DeterministicMath.expm1(-units(tower,loading,inlet)*DeterministicMath.pow(Math.abs(flux),.59));
        // The water's heat that goes as vapor, and the air humidified on its way past water at the wet bulb.
        double evaporation=(.725+.00391*(inlet-308.15))*water*CP_WATER*fall/LATENT+.00257*Math.abs(air)*loading/(loading+.02);
        double outlet=inlet-fall,in=liquid(model,inlet),out=liquid(model,outlet);
        return new Result(outlet,evaporation,water*(in-out),water*in-(water-evaporation)*out,air,tower.fanPower(speed),speed);
    }
    /** The fan speed that brings leaving water to the tower's set point, the correlation read backwards; the end stop when no speed does. */
    static double targetSpeed(CoolingTower tower,double water,double inlet) {
        double wanted=(inlet-tower.setPoint())/(inlet-CoolingTower.WET_BULB),loading=water/tower.area();
        if(!(wanted>0))return 0;
        if(wanted>=1)return 1;
        double flux=DeterministicMath.pow(-DeterministicMath.log(1-wanted)/units(tower,loading,inlet),1/.59);
        return Math.sqrt(Math.clamp(((3+3*tower.fillDepth())*flux*flux/(2*CoolingTower.AIR_DENSITY)-buoyancy(tower,loading,inlet))/CoolingTower.FAN_PRESSURE,0,1));
    }
    /** Sensible exchange at a wet basin surface, including at zero spray; the basin itself does not evaporate. */
    static double surfaceWatt(CoolingTower tower,double t) {
        return SURFACE_COEFFICIENT*tower.area()*(t-CoolingTower.AIR_TEMPERATURE);
    }
}
