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
 * temperature). On a model with water the column carries the whole pot, water among its components (batch
 * 2026-11-11-aqueous-solvents, W4 D1): water's share is the column's like every other. On a list without water, and for a
 * component the column carries no vapour of, the share is 1. A closed valve returns every component (share 0). A tray
 * that would hold two liquids is reported ({@link Solved#settlingTrays}), never a failure.</li>
 * <li><b>The condenser's split</b> ({@link #divide}), arithmetic on the server: what reached the head goes to the
 * distillate (liquid) or the vent (vapour). Each component's liquid share at the condenser's outlet is the column's answer
 * ({@link StillColumn.Split#liquidShares}: 1 where the head condenses whole, less where its outlet, the water's inlet plus
 * {@link BatchStill#OUTLET_APPROACH}, leaves gas); of that, the share {@link #liquidShare} goes to the distillate. Before the
 * column's first answer nothing is known to condense: all vents. The energy goes with the moles; the condenser's duty is what
 * the outlets then give at their own temperatures less what the accounts carried.</li>
 * </ul>
 */
public final class StillHead {
    private StillHead() {}

    /**
     * A column solve: the pot's contents on its island's own list ({@code pot}, water last), its temperature (K) and pressure
     * (Pa), the trays, the reflux ratio the column runs at, the last profile (null for a cold start), the column's diameter (m;
     * 0 for none) and the condenser's outlet floor (K: its water's inlet plus {@link BatchStill#OUTLET_APPROACH} as of the
     * commit that queued the job; {@link StillColumn#TOTAL_CONDENSER} with no water read).
     */
    public record Job(double[] pot,double temperature,double pressure,int stages,double reflux,StillColumn.Profile warm,double columnDiameter,double outlet) {
        public Job {
            pot=pot.clone();
            if(!(temperature>0)||!(pressure>0)||stages<0||stages>StillColumn.MAX_STAGES||!(reflux>=0)||!(columnDiameter>=0)||!(outlet>0))throw new IllegalArgumentException("Invalid still column job");
        }
        @Override public double[] pot(){return pot.clone();}
    }

    /** A column solve's answer. */
    public sealed interface Outcome permits Solved,Failed {}
    /**
     * The pot's head ({@link Pool.Head}, on the island's list), the column's profile for the next warm start, the head vapour's
     * and the condenser's outlet's temperatures (K), each component's liquid share of the net top product at that outlet (on
     * the island's list, water last; {@link StillColumn.Split#liquidShares}), the condenser's
     * need per mole that reached the head (J/mol: (R + 1)(H1 - h0) over the net top product), the reflux ratio solved and
     * the column's F-factor per watt that goes up as vapour (Pa^0.5 / W: the top vapour's, {@link BatchStill#fFactor}, over the
     * heat it gives the condenser, (R + 1)(H1 - hC) a mole of distillate; 0 with no column),
     * the trays whose liquid would settle into two liquids (the status line's notice) and the engine splits the solve made.
     */
    public record Solved(Pool.Head head,StillColumn.Profile profile,double headTemperature,double condensateTemperature,
                         double[] liquidShares,double needPerMole,double reflux,double fPerWatt,int[] settlingTrays,int flashes,String notice) implements Outcome {
        public Solved {Objects.requireNonNull(head);liquidShares=liquidShares.clone();settlingTrays=settlingTrays.clone();}
        @Override public double[] liquidShares(){return liquidShares.clone();}
        @Override public int[] settlingTrays(){return settlingTrays.clone();}
        /** Whether a tray would hold free water: the still's notice "water on the trays". */
        public boolean waterOnTrays(){return settlingTrays.length>0;}
        /** Whether the column fell back to the pot's single stage ({@link StillColumn#POT_STILL}): two liquids on its trays. */
        public boolean potStill(){return notice!=null;}
    }
    public record Failed(String reason) implements Outcome {}

    /** The column for {@code job} on {@code model} (the pot island's own model). */
    public static Outcome run(Job job,FluidThermodynamics model,Runnable checkpoint) {
        int n=model.hydrocarbon.componentCount();
        var pot=job.pot();
        if(pot.length!=n+1)return new Failed("basis mismatch");
        var column=new StillColumn(model,job.pressure());
        // The column's basis: the whole pot where the model carries water, else its compounds (water's share then 1).
        int carried=column.componentCount();
        var inPot=java.util.Arrays.copyOf(pot,carried);
        double total=0;for(double v:inPot)total+=v;
        if(!(total>0))return new Solved(Pool.Head.plain(n+1),null,job.temperature(),job.temperature(),new double[n+1],0,0,0,new int[0],0,null);
        checkpoint.run();
        var split=column.solve(inPot,job.stages(),job.reflux(),job.outlet(),job.warm());
        checkpoint.run();
        if(!split.converged())return new Failed(split.failure()==null?"no convergence":split.failure());
        var up=split.vapourFlows();var out=split.distillateFlows();var back=split.liquidFlows();
        var recovery=new double[n+1];java.util.Arrays.fill(recovery,1);
        // Each component's liquid share at the outlet; water on a list without it is in no pot, its share 1.
        var liquidShares=new double[n+1];java.util.Arrays.fill(liquidShares,1);var own=split.liquidShares();
        for(int i=0;i<carried;i++)liquidShares[i]=own[i];
        for(int i=0;i<carried;i++)recovery[i]=up[i]>0?Math.max(0,Math.min(1,out[i]/up[i])):1;
        // The liquid back to the pot leaves the bottom tray at its temperature, on the network's own energy basis.
        double returned=0;for(double b:back)returned+=b;
        double returnEnthalpy=0;
        if(returned>0) {
            var x=new double[n+1];for(int i=0;i<carried;i++)x[i]=Math.max(0,back[i]);
            var temperatures=split.profile()==null?null:split.profile().temperatures();
            double t=temperatures!=null&&temperatures.length>split.stages()&&split.stages()>0?temperatures[split.stages()]:split.potTemperature();
            var liquid=model.flashTP(t,job.pressure(),x,checkpoint);
            returnEnthalpy=liquid.enthalpy()/returned;
        }
        double net=split.distillateRate();
        double need=net>0?split.condenserDuty()/net:0;
        // The top vapour that 1 W carries up to the condenser (the split's rates are per mole of its basis, its condenser duty the
        // heat of that basis's top vapour), at the head's temperature and the distillate's composition.
        double fPerWatt=0;
        if(job.columnDiameter()>0&&split.topVapourRate()>0&&split.condenserDuty()>0) {
            var y=split.distillate();double molar=column.molarMass(y),volume=column.vapourVolume(split.headTemperature(),y);
            if(molar>0&&volume>0)fPerWatt=BatchStill.fFactor(split.topVapourRate()*molar,molar/volume,job.columnDiameter())/split.condenserDuty();
        }
        return new Solved(new Pool.Head(recovery,returnEnthalpy),split.profile(),split.headTemperature(),split.condensateTemperature(),liquidShares,need,split.refluxRatio(),fPerWatt,
                split.settlingTrays(),split.flashes(),split.notice());
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
     * vent: of each component its liquid share at the condenser's outlet ({@code shares}, on the world's list; null before the
     * column's first answer: none) times the condenser's share {@code liquid} goes to the distillate, the rest to the vent;
     * with no condenser everything vents. The energy goes by the moles' share; energy with no moles (the latent heat of a
     * closed column's reflux) stays at the head as the condenser's, in neither account.
     */
    public static Division divide(double[] arrived,double energy,double[] shares,double liquid,boolean condenser) {
        int n=arrived.length;var d=new double[n];var v=new double[n];double total=0,condensed=0;
        for(int i=0;i<n;i++) {
            double a=Math.max(0,arrived[i]);total+=a;
            double share=condenser&&shares!=null&&i<shares.length?Math.max(0,Math.min(1,shares[i])):0;
            d[i]=a*share*liquid;v[i]=a-d[i];condensed+=d[i];
        }
        if(!(total>0))return new Division(d,0,v,0);
        double share=condensed/total;
        return new Division(d,energy*share,v,energy*(1-share));
    }
}
