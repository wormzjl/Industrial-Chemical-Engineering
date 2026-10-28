package com.wormzjl.icheme.science.fluid.network;

import java.util.Objects;
import java.util.function.DoubleUnaryOperator;

/**
 * A heat side's built-in controller (batch 2026-10-27-heat-exchanger-design, plan section 6, owner Q6). It sets the side's
 * actuator between slices or steps, never inside a Newton, dead-beat on the side's own heat law: the actuator's effort
 * whose heat meets the target at the planned profile, fixed until the next plan. The actuator is the unit's: an exchanger
 * side's bypass ({@link HeatSide}), an air cooler's louvers and fans ({@link AirCooled}). Its effort runs from 0 (the bypass
 * fully open, the louvers shut) to 1 (no bypass, the fans at full speed), and the size of the side's heat rises with it. The controller
 * does not count the give-back of the heat in transit: that closes a loop through the transit and settles four to five
 * times slower (plan section 6).
 *
 * @param target   what it holds: nothing ({@link Target#MANUAL}: the manual effort holds), the outlet's temperature or the duty
 * @param value    the held value: K for an outlet temperature, W for a duty (the heat moved, whichever way); 0 in manual
 * @param lowLimit the cooled outlet's lowest temperature, K, or 0 for none: a limit in every mode, not a target, against
 *                 ice, crystals and wax downstream
 * @param manual   the effort the player set, 0 to 1, which holds in manual
 */
public record HeatControl(Target target,double value,double lowLimit,double manual) {
    public enum Target { MANUAL, OUTLET_TEMPERATURE, DUTY }
    /** Why the planned effort stands where it does: at the target or the manual setting ({@code NONE}), at its lowest or highest
     * end short of the target, or held back by the low limit. */
    public enum Limit { NONE, LOWEST, HIGHEST, LOW_LIMIT }
    /** A planned effort and why it stands there. */
    public record Setting(double effort,Limit limit) {}
    public HeatControl {
        Objects.requireNonNull(target);
        if(!(value>=0)||!Double.isFinite(value)||target==Target.OUTLET_TEMPERATURE&&!(value>0)||!(lowLimit>=0)||!Double.isFinite(lowLimit)||!(manual>=0&&manual<=1))
            throw new IllegalArgumentException("Invalid heat control");
    }

    /**
     * The effort for a step or slice of a stream drawn at {@code inlet} J/kg on {@code curve} at {@code mass} kg/s, whose heat
     * at effort u is {@code heat.applyAsDouble(u)} (W, positive when the stream is heated), with the actuator now at
     * {@code last}. A stream without flow or without heat to move keeps the actuator where it is (the manual setting in
     * manual). The side is cooled when its heat at full effort is negative. A target temperature names the plateau's liquid
     * end on a cooled side and its vapour end on a heated side (plan section 6). A target past what full effort moves takes
     * full effort; a target on the far side of the inlet, or below what the lowest effort moves, takes the lowest. On a cooled side the low limit then holds the effort back to where the outlet reaches it.
     */
    public Setting setting(DoubleUnaryOperator heat,HeatCurve curve,double inlet,double mass,double last) {
        double still=target==Target.MANUAL?manual:last;
        if(!(mass>0))return new Setting(still,Limit.NONE);
        double full=heat.applyAsDouble(1);
        if(full==0)return new Setting(still,Limit.NONE);
        double sign=full<0?-1:1;
        var setting=switch(target) {
            case MANUAL->new Setting(manual,Limit.NONE);
            case OUTLET_TEMPERATURE->reach(heat,sign,sign*((sign<0?curve.lowestEnthalpyAt(value):curve.enthalpyAt(value))-inlet)*mass,full);
            case DUTY->reach(heat,sign,value,full);
        };
        if(sign<0&&lowLimit>0) {
            // The most heat the stream may give before its outlet reaches the limit; none for a stream that arrives below it.
            double allowed=(inlet-curve.lowestEnthalpyAt(lowLimit))*mass;
            if(-heat.applyAsDouble(setting.effort())>allowed)setting=new Setting(reach(heat,sign,Math.max(allowed,0),full).effort(),Limit.LOW_LIMIT);
        }
        return setting;
    }
    /** The effort whose heat in the stream's direction ({@code sign}) is {@code wanted} W: an Illinois root, the heat rising with the effort. */
    private static Setting reach(DoubleUnaryOperator heat,double sign,double wanted,double full) {
        double least=sign*heat.applyAsDouble(0),most=sign*full;
        if(wanted<=least)return new Setting(0,wanted<least?Limit.LOWEST:Limit.NONE);
        if(wanted>=most)return new Setting(1,wanted>most?Limit.HIGHEST:Limit.NONE);
        double lo=0,hi=1,flo=least-wanted,fhi=most-wanted,tolerance=1e-10*Math.max(Math.abs(most),1);int side=0;
        for(int iteration=0;iteration<100&&hi-lo>1e-12;iteration++) {
            double u=(lo*fhi-hi*flo)/(fhi-flo);
            if(!(u>lo&&u<hi))u=(lo+hi)/2;
            double f=sign*heat.applyAsDouble(u)-wanted;
            if(Math.abs(f)<=tolerance)return new Setting(u,Limit.NONE);
            if(f<0){lo=u;flo=f;if(side==-1)fhi/=2;side=-1;}
            else{hi=u;fhi=f;if(side==1)flo/=2;side=1;}
        }
        return new Setting((lo*fhi-hi*flo)/(fhi-flo),Limit.NONE);
    }
}
