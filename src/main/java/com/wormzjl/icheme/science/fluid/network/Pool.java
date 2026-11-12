package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import java.util.Arrays;

/**
 * A vessel's heated pool, carried by its inventory ({@link PassiveNetwork.Inventory#pool}) so the heat enters the vessel's own
 * energy row in the vessel's island. Two forms:
 * <ul>
 * <li><b>A kettle's pool</b> (batch 2026-10-27-heat-exchanger-design, W7, plan section 8): the vessel's contents around a
 * submerged tube bundle, whose tubes are connection {@code partner} (the kettle's tube side, a {@link HeatSide} that names
 * {@code id} as its partner).</li>
 * <li><b>A fired pot</b> (batch 2026-11-09-batch-distiller, W3): a batch still's pot, heated from below by its firebox or its
 * electric heater; {@code partner} is 0 (no tubes). Its heat is its {@code giveBack} alone, the heat its heater puts into the
 * pot, W, a slice input the still plans at dispatch ({@link #withFiring}). A pot that condenses its own vapour carries its
 * {@link Head}: what its column passes of the gas its vent releases (its release, {@link OpenVessel#releases}).</li>
 * </ul>
 *
 * <p>The facts are its coupling key {@code id}, its {@code partner}, the heated surface {@code area} (m²) and where a bundle
 * lies: its lowest tubes {@code bottom} m over the vessel's bottom, across {@code bundle} m. The slice inputs, planned at each
 * slice start from the tube side as read ({@link ExchangerRelay#planPool}), are the tube wall's temperature in each cell
 * ({@code wall}, K), the give-back ({@code giveBack}, W), which returns the heat in transit (the pool takes all of it: a
 * boiling pool's capacity is unbounded), and the {@code active} share of the slice. Its heat into the vessel is explicit on a
 * step's start state, as a side's is on its donor's ({@link #heat}): no unknown, no mode.
 */
public record Pool(long id,long partner,double area,double bottom,double bundle,double[] wall,double giveBack,double active,@org.jetbrains.annotations.Nullable Head head) {
    /**
     * A still's column on its pot's vent (batch 2026-11-09-batch-distiller, W3), a slice input: of the gas the pot releases in a
     * step, the share {@code recovery[c]} of each component (the island's list) rises past the column to its head; the rest
     * the column condenses and returns, so it stays in the pot as liquid at {@code returnEnthalpy} J/mol, and only the
     * difference between its gas's energy and that leaves the pot. A head of all ones and no return is a plain pot still.
     */
    public record Head(double[] recovery,double returnEnthalpy) {
        public Head {
            recovery=recovery.clone();
            if(recovery.length==0||!Double.isFinite(returnEnthalpy))throw new IllegalArgumentException("Invalid still head");
            for(double r:recovery)if(!(r>=0&&r<=1))throw new IllegalArgumentException("Invalid still recovery "+r);
        }
        /** A plain pot still on {@code components} components: everything its vent releases leaves. */
        public static Head plain(int components){double[] r=new double[components];Arrays.fill(r,1);return new Head(r,0);}
        @Override public double[] recovery(){return recovery.clone();}
        public double recovery(int component){return recovery[component];}
        public int components(){return recovery.length;}
        @Override public boolean equals(Object other){return other instanceof Head h&&Arrays.equals(recovery,h.recovery)&&Double.compare(returnEnthalpy,h.returnEnthalpy)==0;}
        @Override public int hashCode(){return 31*Arrays.hashCode(recovery)+Double.hashCode(returnEnthalpy);}
        @Override public String toString(){return "Head[recovery="+Arrays.toString(recovery)+", returnEnthalpy="+returnEnthalpy+"]";}
    }
    public Pool {
        boolean fired=partner==0;
        if(id==0||!(area>0)||!Double.isFinite(area)||!(bottom>=0)||!(bundle>0)||!Double.isFinite(bottom+bundle)||wall.length<1
                ||!Double.isFinite(giveBack)||!(active>=0&&active<=1))throw new IllegalArgumentException("Invalid pool");
        if(fired&&(active!=0||giveBack<0))throw new IllegalArgumentException("A fired pot takes only its firing");
        if(!fired&&head!=null)throw new IllegalArgumentException("Only a still's pot has a head");
        wall=wall.clone();
        for(double temperature:wall)if(!(temperature>0)||!Double.isFinite(temperature))throw new IllegalArgumentException("Invalid wall temperature");
    }
    /** A kettle's pool, without a head. */
    public Pool(long id,long partner,double area,double bottom,double bundle,double[] wall,double giveBack,double active){this(id,partner,area,bottom,bundle,wall,giveBack,active,null);}
    /** A pool as compiled: {@code cells} cells at {@code temperature} K, moving no heat until its first slice plans it. */
    public static Pool idle(long id,long partner,double area,double bottom,double bundle,int cells,double temperature) {
        double[] wall=new double[cells];Arrays.fill(wall,temperature);
        return new Pool(id,partner,area,bottom,bundle,wall,0,0);
    }
    /**
     * A still's pot as compiled (batch 2026-11-09-batch-distiller, W3): key {@code id}, heated surface {@code area} m², at
     * {@code temperature} K, firing nothing until its first slice plans it; {@code head} null for a pot whose vapour leaves
     * by its own nozzle.
     */
    public static Pool fired(long id,double area,double temperature,@org.jetbrains.annotations.Nullable Head head) {
        return new Pool(id,0,area,0,1,new double[]{temperature},0,0,head);
    }
    /** Whether this is a still's fired pot: no tubes, its heat its firing. */
    public boolean fired(){return partner==0;}
    /** The facts that shape the vessel's equations, without the slice inputs. */
    public record Shape(long id,long partner,double area,double bottom,double bundle,int cells,boolean headed) {}
    public Shape shape(){return new Shape(id,partner,area,bottom,bundle,wall.length,head!=null);}
    @Override public double[] wall(){return wall.clone();}
    public int cells(){return wall.length;}
    public Pool withInputs(double[] wall,double giveBack,double active){return new Pool(id,partner,area,bottom,bundle,wall,giveBack,active,head);}
    /** A fired pot at {@code heat} W into the pot (its slice input). */
    public Pool withFiring(double heat){return new Pool(id,partner,area,bottom,bundle,wall,heat,active,head);}
    /** A fired pot with its column's slice input. */
    public Pool withHead(@org.jetbrains.annotations.Nullable Head value){return new Pool(id,partner,area,bottom,bundle,wall,giveBack,active,value);}

    /**
     * The share of the bundle's tubes under the liquid of {@code contents} in {@code vessel}: the bundle's cross-section
     * below the level its liquids stand at ({@link VesselGeometry#heightAt}), 0 with the level under its lowest tubes, 1 over
     * its highest. A kettle's duty falls as its level uncovers its bundle (its weir stands just over it).
     */
    public double wetted(FluidThermodynamics.State contents,VesselGeometry vessel) {
        double liquid=contents.condensedVolume();if(!(liquid>0))return 0;
        double r=bundle/2,depth=vessel.heightAt(liquid)-bottom;
        if(depth<=0)return 0;
        if(depth>=bundle)return 1;
        return VesselGeometry.segmentArea(r,depth)/(Math.PI*r*r);
    }
    /**
     * The film between the tube wall and {@code contents} in {@code vessel}, W/m²K (plan section 4's films per phase): on the
     * {@link #wetted} tubes nucleate boiling where the vessel holds vapour over its liquid, liquid where it holds liquid
     * alone; on the others the gas's.
     */
    public double film(FluidThermodynamics.State contents,VesselGeometry vessel) {
        var films=HeatCurve.Films.DEFAULT;double wet=wetted(contents,vessel);
        double wetFilm=FluidThermodynamics.holdsVapor(contents)?films.twoPhase():films.liquid();
        return wet*wetFilm+(1-wet)*films.gas();
    }
    /**
     * The heat into the vessel, W, from its contents {@code contents} at a step's start in {@code vessel}: each cell's
     * surface at the pool's {@link #film} against its wall temperature, for the active share of the slice, plus the give-back.
     * A fired pot's is its firing.
     */
    public double heat(FluidThermodynamics.State contents,VesselGeometry vessel) {
        if(fired())return giveBack;
        double cell=area/wall.length,film=active>0?film(contents,vessel):0,t=contents.temperature(),sum=0;
        if(active>0)for(double w:wall)sum+=film*cell*(w-t);
        return active*sum+giveBack;
    }
    @Override public boolean equals(Object other) {
        return other instanceof Pool pool&&id==pool.id&&partner==pool.partner&&Double.compare(area,pool.area)==0&&Double.compare(bottom,pool.bottom)==0&&Double.compare(bundle,pool.bundle)==0
                &&Arrays.equals(wall,pool.wall)&&Double.compare(giveBack,pool.giveBack)==0&&Double.compare(active,pool.active)==0&&java.util.Objects.equals(head,pool.head);
    }
    @Override public int hashCode(){return 31*java.util.Objects.hash(id,partner,area,bottom,bundle,giveBack,active,head)+Arrays.hashCode(wall);}
    @Override public String toString(){return "Pool[id="+id+", partner="+partner+", area="+area+", bottom="+bottom+", bundle="+bundle+", wall="+Arrays.toString(wall)+", giveBack="+giveBack+", active="+active+", head="+head+"]";}
}
