package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import java.util.Objects;

/**
 * The batch still's head in the running world (batch 2026-11-09-batch-distiller, W3; BATCH_STILL_ENGINE.md): the column and
 * the condenser as one quasi-steady block between the pot's vapour and the still's outlets. Minecraft-free.
 *
 * <ul>
 * <li><b>The column's job</b> ({@link #run}), on the engine's worker: {@link StillColumn} on the pot's island's own model, warm
 * from its last profile, at the effective reflux the condenser allows. Its answer is the pot's {@link Pool.Head}: for each
 * component the share of the pot's vapour that leaves the head, d_i / v_i (the column's distillate over the vapour up from the
 * pot); the rest is the column's liquid, back to the pot at its own enthalpy (the network's, at the bottom tray's
 * temperature). Water is not in the column's basis: it leaves the head with the distillate (share 1), as do components the
 * column carries no vapour of. A closed valve returns every hydrocarbon (share 0).</li>
 * <li><b>The condenser's split</b> ({@link #divide}), arithmetic on the server: what reached the head goes to the
 * distillate (liquid) or the vent (vapour). A component whose critical temperature is at or below the condensate's cannot
 * condense: it vents whole. Of the rest, the share {@link #liquidShare} goes to the distillate. The energy goes with the
 * moles; the condenser's duty is what the outlets then give at their own temperatures less what the accounts carried.</li>
 * </ul>
 */
public final class StillHead {
    private StillHead() {}

    /**
     * A column solve: the pot's contents on its island's own list ({@code pot}, water last), its temperature (K) and pressure
     * (Pa), the trays, the reflux ratio the column runs at, the last profile (null for a cold start) and the column's
     * diameter (m; 0 for none).
     */
    public record Job(double[] pot,double temperature,double pressure,int stages,double reflux,StillColumn.Profile warm,double columnDiameter) {
        public Job {
            pot=pot.clone();
            if(!(temperature>0)||!(pressure>0)||stages<0||stages>StillColumn.MAX_STAGES||!(reflux>=0)||!(columnDiameter>=0))throw new IllegalArgumentException("Invalid still column job");
        }
        @Override public double[] pot(){return pot.clone();}
    }

    /** A column solve's answer. */
    public sealed interface Outcome permits Solved,Failed {}
    /**
     * The pot's head ({@link Pool.Head}, on the island's list), the column's profile for the next warm start, the head vapour's
     * and the condensate's temperatures (K), the critical temperature of each component (K, water last), the condenser's
     * need per mole that reached the head (J/mol: (R + 1)(H1 - h0) over the net top product), the reflux ratio solved and
     * the column's F-factor per watt of boil-up (Pa^0.5 / W: the top vapour's, {@link BatchStill#fFactor}; 0 with no column).
     */
    public record Solved(Pool.Head head,StillColumn.Profile profile,double headTemperature,double condensateTemperature,
                         double[] critical,double needPerMole,double reflux,double fPerWatt) implements Outcome {
        public Solved {Objects.requireNonNull(head);critical=critical.clone();}
        @Override public double[] critical(){return critical.clone();}
    }
    public record Failed(String reason) implements Outcome {}

    /** The critical temperature of water, K: it condenses at any condensate temperature the still reaches. */
    public static final double WATER_CRITICAL=647.096;

    /** The column for {@code job} on {@code model} (the pot island's own model). */
    public static Outcome run(Job job,FluidThermodynamics model,Runnable checkpoint) {
        int n=model.hydrocarbon.componentCount();
        var pot=job.pot();
        if(pot.length!=n+1)return new Failed("basis mismatch");
        var column=new StillColumn(model,job.pressure());
        var hydrocarbons=java.util.Arrays.copyOf(pot,column.componentCount());if(column.water()>=0)hydrocarbons[column.water()]=0;
        double total=0;for(double v:hydrocarbons)total+=v;
        var critical=new double[n+1];for(int i=0;i<n;i++)critical[i]=column.criticalTemperature(i);critical[n]=WATER_CRITICAL;
        if(!(total>0))return new Solved(Pool.Head.plain(n+1),null,job.temperature(),job.temperature(),critical,0,0,0);
        checkpoint.run();
        var split=column.solve(hydrocarbons,job.stages(),job.reflux(),1,job.warm());
        checkpoint.run();
        if(!split.converged())return new Failed(split.failure()==null?"no convergence":split.failure());
        var up=split.vapourFlows();var out=split.distillateFlows();var back=split.liquidFlows();
        var recovery=new double[n+1];
        for(int i=0;i<n;i++)recovery[i]=up[i]>0?Math.max(0,Math.min(1,out[i]/up[i])):1;
        recovery[n]=1;
        // The liquid back to the pot leaves the bottom tray at its temperature, on the network's own energy basis.
        double returned=0;for(double b:back)returned+=b;
        double returnEnthalpy=0;
        if(returned>0) {
            var x=new double[n+1];for(int i=0;i<n;i++)x[i]=Math.max(0,back[i]);
            var temperatures=split.profile()==null?null:split.profile().temperatures();
            double t=temperatures!=null&&temperatures.length>split.stages()&&split.stages()>0?temperatures[split.stages()]:split.potTemperature();
            var liquid=model.flashTP(t,job.pressure(),x,checkpoint);
            returnEnthalpy=liquid.enthalpy()/returned;
        }
        double net=split.distillateRate();
        double need=net>0?split.condenserDuty()/net:0;
        // The top vapour at 1 W of boil-up (every rate of the split is per watt), at the head's temperature and the distillate's composition.
        double fPerWatt=0;
        if(job.columnDiameter()>0&&split.topVapourRate()>0) {
            var y=split.distillate();double molar=column.molarMass(y),volume=column.vapourVolume(split.headTemperature(),y);
            if(molar>0&&volume>0)fPerWatt=BatchStill.fFactor(split.topVapourRate()*molar,molar/volume,job.columnDiameter());
        }
        return new Solved(new Pool.Head(recovery,returnEnthalpy),split.profile(),split.headTemperature(),split.condensateTemperature(),critical,need,split.refluxRatio(),fPerWatt);
    }

    /**
     * The share of the net top product that leaves as distillate, for the valve's reflux ratio {@code reflux} (infinite:
     * closed) and the condenser's share {@code condensed} of the top vapour: φ (R_eff + 1) / (R + 1) ({@link BatchStill}); a
     * closed valve sends no condensate out, so all of its top product is vented vapour.
     */
    public static double liquidShare(double reflux,double condensed) {
        if(!(condensed>0))return 0;
        if(reflux==Double.POSITIVE_INFINITY)return condensed<1?0:1;
        double effective=BatchStill.effectiveReflux(reflux,condensed);
        return Math.max(0,Math.min(1,condensed*(effective+1)/(reflux+1)));
    }

    /** What reached the head, divided: to the distillate and to the vent, each moles per component and energy (J). */
    public record Division(double[] distillate,double distillateEnergy,double[] vent,double ventEnergy) {
        public Division {distillate=distillate.clone();vent=vent.clone();}
        @Override public double[] distillate(){return distillate.clone();}
        @Override public double[] vent(){return vent.clone();}
    }

    /**
     * Divides {@code arrived} moles with {@code energy} J (what the pot's vent gave its head) between the distillate and the
     * vent: a component with {@code critical} (K) at or below {@code condensate} (K) vents whole; of each other the share
     * {@code liquid} is condensed; with no condenser everything vents. The energy goes by the moles' share; energy with no
     * moles (the latent heat of a closed column's reflux) stays at the head as the condenser's, in neither account.
     */
    public static Division divide(double[] arrived,double energy,double[] critical,double condensate,double liquid,boolean condenser) {
        int n=arrived.length;var d=new double[n];var v=new double[n];double total=0,condensed=0;
        for(int i=0;i<n;i++) {
            double a=Math.max(0,arrived[i]);total+=a;
            boolean condensable=condenser&&i<critical.length&&critical[i]>condensate;
            d[i]=condensable?a*liquid:0;v[i]=a-d[i];condensed+=d[i];
        }
        if(!(total>0))return new Division(d,0,v,0);
        double share=condensed/total;
        return new Division(d,energy*share,v,energy*(1-share));
    }
}
