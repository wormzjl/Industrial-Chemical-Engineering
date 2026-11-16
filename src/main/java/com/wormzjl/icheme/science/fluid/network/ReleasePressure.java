package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import java.util.function.DoubleUnaryOperator;

/**
 * The pressures at which a liquid releases vapour (W3B_NETWORK_PLAN.md sections 17 and 44): one rule, read by the world's
 * pump suction limit ({@link PassiveStepSolver}, on its constant-enthalpy equilibrium) and by the process simulator's
 * sizing ({@code FlowThermo}, on its TP flash). Each reader brings its own trial, a pressure's vapour volume share; the
 * search, the reading of a trial state, the compounds that count as condensable and the limit are here.
 *
 * <ul>
 * <li>p_v: the lowest pressure at which the liquid's condensable compounds ({@link #leaveOutGases}) hold no vapour.</li>
 * <li>E, the clean zone's edge: the lowest pressure at which the whole stream holds at most the clean share of vapour
 * ({@link com.wormzjl.icheme.science.machine.GasEntrainment#cleanShare}).</li>
 * <li>The suction limit: the greater of E and p_v plus the breakdown NPSH ({@link #limit}).</li>
 * </ul>
 */
public final class ReleasePressure {
    /** {@link #leaveOutGases}: a compound was left out; a compound is left. */
    public static final int REMOVED=1,LEFT=2;

    private ReleasePressure() {}

    /**
     * The lowest pressure between {@code lo} and {@code hi} at which {@code trial}'s vapour volume share is at most
     * {@code share} at that pressure: a bisection in ln P to the relative width {@code width} that returns the high end, so a
     * liquid held there holds no more vapour than {@code share}; with {@code share} 0 it is the bubble pressure. Midpoints
     * are geometric means ({@link Math#sqrt}, correctly rounded, the same on every runtime). {@code trial} gives 0 for
     * liquid alone, the smallest positive double for a vapour too small to have a volume ({@link #vapourVolumeShare}),
     * and NaN where it cannot say; a NaN trial does not pass, and the pressure stands only where some trial below the end
     * stated a vapour. {@code hi} where the liquid holds more than {@code share} there; positive infinity where the top
     * trial is NaN, where the liquid passes at {@code lo}, and where no trial stated a vapour: no such pressure, never a
     * failure (AGENTS.md, thermo validity).
     */
    public static double lowest(DoubleUnaryOperator trial,DoubleUnaryOperator share,double lo,double hi,double width) {
        return search(trial,share,lo,hi,width).pressure();
    }
    /**
     * {@link #lowest} where the stream holds a vapour at the pressure it returns: its trial there above 0 (a vapour beside a
     * liquid; the smallest positive double counts); positive infinity where it reads exactly 0 there. What the clean zone's
     * edge of one dense fluid reads (S4b of batch 2026-11-15-supercritical-fluids): such a fluid has no second phase, so an
     * edge stands only where its isenthalp really flashes. From the bubble side the share rises continuously from 0 at the
     * bubble pressure to the clean share at E, and the search's last passing pressure lies between them, above 0; where the
     * isenthalp crosses the critical temperature on the gas side, the last passing trial is still one fluid, exactly 0: a
     * change of label, not a flash. The same bisection and the same pressure as {@link #lowest}.
     */
    public static double lowestHeld(DoubleUnaryOperator trial,DoubleUnaryOperator share,double lo,double hi,double width) {
        var found=search(trial,share,lo,hi,width);
        return found.share()>0?found.pressure():Double.POSITIVE_INFINITY;
    }
    /** {@link #lowest}'s pressure and its trial's value there (NaN with no pressure). */
    private record Found(double pressure,double share) {
        static final Found NONE=new Found(Double.POSITIVE_INFINITY,Double.NaN);
    }
    private static Found search(DoubleUnaryOperator trial,DoubleUnaryOperator share,double lo,double hi,double width) {
        if(!(hi>lo))return Found.NONE;
        double top=trial.applyAsDouble(hi);
        if(Double.isNaN(top))return Found.NONE;
        if(!(top<=share.applyAsDouble(hi)))return new Found(hi,top);
        double bottom=trial.applyAsDouble(lo);
        if(bottom<=share.applyAsDouble(lo))return Found.NONE;
        boolean vapour=bottom>0;double held=top;
        for(int k=0;k<200&&hi>lo*(1+width);k++){double mid=Math.sqrt(lo*hi),at=trial.applyAsDouble(mid);if(at<=share.applyAsDouble(mid)){hi=mid;held=at;}else{lo=mid;vapour|=at>0;}}
        return vapour?new Found(hi,held):Found.NONE;
    }
    /**
     * The vapour volume share of trial state {@code at}: 0 for liquid alone, the smallest positive double for a vapour
     * too small to have a volume, NaN for no state and for one that holds no liquid. One dense fluid
     * ({@link FluidThermodynamics.State#dense()}), in either slot, reads 0: it has no second phase, so it releases no vapour
     * (S4b of batch 2026-11-15-supercritical-fluids; before S4 the liquid slot read 0 and the vapour slot NaN).
     */
    public static double vapourVolumeShare(FluidThermodynamics.State at) {
        if(at!=null&&at.dense())return 0;
        if(at==null||!(FluidThermodynamics.holdsPhase(at,FluidThermodynamics.OIL)||FluidThermodynamics.holdsPhase(at,FluidThermodynamics.AQUEOUS)))return Double.NaN;
        if(!FluidThermodynamics.holdsPhase(at,FluidThermodynamics.GAS))return 0;
        double v=at.vaporVolume(),total=v+at.condensedVolume();
        return total>0&&v>0?v/total:Double.MIN_VALUE;
    }
    /**
     * Leaves out of {@code amounts}, in place, the compounds above their critical temperature at {@code temperature}: a
     * gas dissolved in the liquid is no part of its vapour pressure (nitrogen or air in water: none of it; CO2 in water at
     * 298 K counts, its Tc is 304.1 K). {@link #REMOVED} and {@link #LEFT} say whether one was left out and whether one is left.
     */
    public static int leaveOutGases(FluidThermodynamics model,double temperature,double[] amounts) {
        boolean removed=false,left=false;
        for(int k=0;k<amounts.length;k++){if(amounts[k]>0&&model.hydrocarbon.criticalTemperature(k)<=temperature){amounts[k]=0;removed=true;}left|=amounts[k]>0;}
        return (removed?REMOVED:0)|(left?LEFT:0);
    }
    /**
     * The suction limit of section 17 from its two edges: the larger of those that exist, p_v with the breakdown NPSH added
     * and E without it; positive infinity (no limit) only where neither exists. A heavy dead oil under a blanket has no
     * p_v (its condensables stay liquid down to the box's floor) and an E; a liquid whose top trial the engine refused has
     * a p_v and no E.
     */
    public static double limit(double vapourPressure,double zoneEdge,double breakdown) {
        boolean vapour=vapourPressure<Double.POSITIVE_INFINITY,zone=zoneEdge<Double.POSITIVE_INFINITY;
        if(vapour&&zone)return Math.max(zoneEdge,vapourPressure+breakdown);
        return vapour?vapourPressure+breakdown:zone?zoneEdge:Double.POSITIVE_INFINITY;
    }
}
