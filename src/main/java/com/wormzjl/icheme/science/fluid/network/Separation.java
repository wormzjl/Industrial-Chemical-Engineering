package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.math.DeterministicMath;

/**
 * How well a vessel keeps its phases apart at its size and flows (batch 2026-11-17-three-phase-separator, plan sections 3
 * and 4; owner: "the drum should only outputs well separated water and oil when properly sized, otherwise you will find
 * some oil in water and some water in oil"). A liquid enters as drops in the other liquid. A drop settles at Stokes'
 * speed with Schiller and Naumann's drag correction, and a layer is an ideal settler: every drop leaves it where
 * {@code N = v_s A / Q} is 1 or more ({@code A} the layer's plan area, {@code Q} its own outflow), and the share
 * {@code 1 − N} stays under 1. The gas leaves clean while its speed through the vapour space stays under Souders and
 * Brown's. Pure arithmetic: what a vessel carries is a slice input, never an unknown of a step.
 */
public final class Separation {
    /** The drop a vessel without a coalescer leaves to settle, m (0.1 mm). A desalter's field makes its own larger. */
    public static final double DROP=1e-4;
    /** Souders and Brown's K of a standing vessel without a demister, m/s (GPSA's 0.107 m/s halved). */
    public static final double GAS_K=.107/2;
    /** A lying drum's K over a standing one's: its drops fall across the gas, not against it. */
    public static final double LYING=1.25;
    private static final int PASSES=5;

    private Separation(){}

    /**
     * What a vessel's outlets carry beside their own phase.
     *
     * @param waterInOil  water drawn with the oil, m³ per m³ of oil
     * @param oilInWater  oil drawn with the water, m³ per m³ of water
     * @param liquidInGas the share of the gas outlet's volume drawn as the vessel's bulk, 0 to 1
     */
    public record Carried(double waterInOil,double oilInWater,double liquidInGas) {
        /** A vessel that keeps its phases apart: today's draws. */
        public static final Carried NONE=new Carried(0,0,0);
        public Carried {
            if(!(waterInOil>=0&&waterInOil<=1)||!(oilInWater>=0&&oilInWater<=1)||!(liquidInGas>=0&&liquidInGas<=1))
                throw new IllegalArgumentException("Invalid carry-over");
        }
        public boolean none(){return waterInOil==0&&oilInWater==0&&liquidInGas==0;}
    }

    /**
     * A drop's settling speed, m/s: {@code g d² Δρ / (18 μ (1 + 0.15 Re^0.687))}, {@code Re = ρ v d / μ}, by five
     * fixed-point passes from Stokes' speed.
     *
     * @param drop       its diameter, m
     * @param difference the two liquids' density difference, kg/m³ (its sign does not matter: a drop falls or rises)
     * @param viscosity  of the liquid it crosses, Pa s
     * @param density    of the liquid it crosses, kg/m³
     */
    public static double settling(double drop,double difference,double viscosity,double density) {
        if(!(drop>0)||!(viscosity>0)||!(density>0)||!Double.isFinite(difference)||!Double.isFinite(drop+viscosity+density))
            throw new IllegalArgumentException("Invalid settling state");
        double stokes=PassiveStepSolver.GRAVITY*drop*drop*Math.abs(difference)/(18*viscosity),speed=stokes;
        if(stokes==0)return 0;
        for(int pass=0;pass<PASSES;pass++)speed=stokes/(1+.15*DeterministicMath.pow(density*speed*drop/viscosity,.687));
        return speed;
    }

    /** A layer's settling number {@code N = v_s A / Q}: positive infinity where nothing leaves it. */
    public static double number(double speed,double area,double outflow) {
        if(!(speed>=0)||!(area>=0)||!(outflow>=0))throw new IllegalArgumentException("Invalid settling layer");
        return outflow>0?speed*area/outflow:Double.POSITIVE_INFINITY;
    }

    /**
     * What a liquid outlet carries of the other liquid, m³ per m³ of its own: the share {@code 1 − N} of the drops that
     * did not settle, at most volume for volume, of the other liquid that entered per volume of its own ({@code other}
     * and {@code own}, m³ over the last slice). Nothing where nothing of the other liquid entered: a still vessel settles.
     */
    public static double carried(double number,double other,double own) {
        if(Double.isNaN(number)||!(other>=0)||!(own>=0))throw new IllegalArgumentException("Invalid carry-over state");
        if(number>=1||!(other>0))return 0;
        return (1-Math.max(0,number))*(own>0?Math.min(1,other/own):1);
    }

    /** The gas speed a vessel's vapour space takes, m/s: {@code K sqrt((ρ_l − ρ_g) / ρ_g)}, {@link #LYING} times that in a
     * lying drum; positive infinity where the gas is no lighter than the liquid (nothing to carry). */
    public static double gasLimit(boolean lying,double liquidDensity,double gasDensity) {
        if(!(liquidDensity>0)||!(gasDensity>0))throw new IllegalArgumentException("Invalid densities");
        if(liquidDensity<=gasDensity)return Double.POSITIVE_INFINITY;
        return (lying?LYING:1)*GAS_K*Math.sqrt((liquidDensity-gasDensity)/gasDensity);
    }

    /** The share of a gas outlet's volume it draws as the vessel's bulk: {@code 1 − limit / speed} above the limit. */
    public static double entrained(double speed,double limit) {
        if(!(speed>=0)||!(limit>0))throw new IllegalArgumentException("Invalid gas speed");
        return speed>limit?1-limit/speed:0;
    }

    /**
     * A vessel's liquid layer for one slice.
     *
     * @param volume    what the vessel holds of it, m³
     * @param density   kg/m³
     * @param viscosity Pa s
     * @param outflow   what left through its own outlet, m³/s
     * @param inflow    what entered the vessel of it, m³/s
     */
    public record Layer(double volume,double density,double viscosity,double outflow,double inflow) {
        public Layer {
            if(!(volume>=0)||!(density>=0)||!(viscosity>=0)||!(outflow>=0)||!(inflow>=0)||!Double.isFinite(volume+density+viscosity+outflow+inflow))
                throw new IllegalArgumentException("Invalid layer");
        }
        boolean held(){return volume>0&&density>0&&viscosity>0;}
    }

    /**
     * What a vessel of {@code geometry} carries for a slice.
     *
     * @param drop       the drop its liquids settle as, m ({@link #DROP}; a desalter's own)
     * @param oil        its hydrocarbon layer
     * @param water      its aqueous layer
     * @param twoOutlets whether each liquid has its own outlet (a boot drum, a desalter): with one liquid outlet there is
     *                   nothing to keep apart
     * @param gasOutflow what left through its gas outlet, m³/s
     * @param gasDensity kg/m³; 0 with no gas
     */
    public static Carried of(VesselGeometry geometry,double drop,Layer oil,Layer water,boolean twoOutlets,double gasOutflow,double gasDensity) {
        boolean heavyWater=water.density()>=oil.density();
        Layer lower=heavyWater?water:oil,upper=heavyWater?oil:water;
        double split=geometry.heightAt(lower.volume()),top=geometry.heightAt(lower.volume()+upper.volume());
        double waterInOil=0,oilInWater=0;
        if(twoOutlets&&oil.held()&&water.held()) {
            // Each layer's plan area at its mid-height; the upper one's inside the body, above a boot.
            double lowerArea=geometry.planArea((-geometry.bootDepth()+split)/2),upperArea=geometry.planArea((Math.max(split,0)+Math.max(top,0))/2);
            double difference=water.density()-oil.density();
            double inOil=number(settling(drop,difference,oil.viscosity(),oil.density()),heavyWater?upperArea:lowerArea,oil.outflow());
            double inWater=number(settling(drop,difference,water.viscosity(),water.density()),heavyWater?lowerArea:upperArea,water.outflow());
            waterInOil=carried(inOil,water.inflow(),oil.inflow());
            oilInWater=carried(inWater,oil.inflow(),water.inflow());
        }
        double liquidInGas=0;
        if(gasOutflow>0&&gasDensity>0&&(oil.held()||water.held())) {
            // The liquid the gas would lift: the lighter one the vessel holds, which lies on top.
            double liquid=upper.held()?upper.density():lower.density(),section=geometry.vapourSection(top);
            liquidInGas=section>0?entrained(gasOutflow/section,gasLimit(geometry.lying(),liquid,gasDensity)):1;
        }
        return new Carried(waterInOil,oilInWater,liquidInGas);
    }
}
