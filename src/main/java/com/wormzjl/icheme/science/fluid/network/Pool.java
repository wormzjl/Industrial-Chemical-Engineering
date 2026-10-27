package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import java.util.Arrays;

/**
 * A kettle's pool (batch 2026-10-27-heat-exchanger-design, W7, plan section 8): the vessel's contents around a submerged tube
 * bundle, whose tubes are connection {@code partner} (the kettle's tube side, a {@link HeatSide} that names {@code id} as its
 * partner). The vessel's inventory carries it ({@link PassiveNetwork.Inventory#pool}), so the bundle's heat enters the
 * vessel's own energy row in the vessel's island.
 *
 * <p>The facts are its coupling key {@code id}, its {@code partner}, the bundle's surface {@code area} (m²) and where the
 * bundle lies: its lowest tubes {@code bottom} m over the vessel's bottom, across {@code bundle} m. The slice inputs, planned
 * at each slice start from the tube side as read ({@link ExchangerRelay#planPool}), are the tube wall's temperature in each
 * cell ({@code wall}, K), the give-back ({@code giveBack}, W), which returns the heat in transit (the pool takes all of it: a
 * boiling pool's capacity is unbounded), and the {@code active} share of the slice. Its heat into the vessel is explicit on a
 * step's start state, as a side's is on its donor's ({@link #heat}): no unknown, no mode.
 */
public record Pool(long id,long partner,double area,double bottom,double bundle,double[] wall,double giveBack,double active) {
    public Pool {
        if(id==0||partner==0||!(area>0)||!Double.isFinite(area)||!(bottom>=0)||!(bundle>0)||!Double.isFinite(bottom+bundle)||wall.length<1
                ||!Double.isFinite(giveBack)||!(active>=0&&active<=1))throw new IllegalArgumentException("Invalid pool");
        wall=wall.clone();
        for(double temperature:wall)if(!(temperature>0)||!Double.isFinite(temperature))throw new IllegalArgumentException("Invalid wall temperature");
    }
    /** A pool as compiled: {@code cells} cells at {@code temperature} K, moving no heat until its first slice plans it. */
    public static Pool idle(long id,long partner,double area,double bottom,double bundle,int cells,double temperature) {
        double[] wall=new double[cells];Arrays.fill(wall,temperature);
        return new Pool(id,partner,area,bottom,bundle,wall,0,0);
    }
    /** The facts that shape the vessel's equations, without the slice inputs. */
    public record Shape(long id,long partner,double area,double bottom,double bundle,int cells) {}
    public Shape shape(){return new Shape(id,partner,area,bottom,bundle,wall.length);}
    @Override public double[] wall(){return wall.clone();}
    public int cells(){return wall.length;}
    public Pool withInputs(double[] wall,double giveBack,double active){return new Pool(id,partner,area,bottom,bundle,wall,giveBack,active);}

    /**
     * The share of the bundle's tubes under the liquid of {@code contents} in {@code vessel}: the bundle's cross-section
     * below the level its liquids stand at ({@link VesselGeometry#heightAt}), 0 with the level under its lowest tubes, 1 over
     * its highest. A kettle's duty falls as its level uncovers its bundle (its weir stands just over it).
     */
    public double wetted(FluidThermodynamics.State contents,VesselGeometry vessel) {
        double liquid=contents.liquidVolume()+contents.waterVolume();if(!(liquid>0))return 0;
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
     */
    public double heat(FluidThermodynamics.State contents,VesselGeometry vessel) {
        double cell=area/wall.length,film=active>0?film(contents,vessel):0,t=contents.temperature(),sum=0;
        if(active>0)for(double w:wall)sum+=film*cell*(w-t);
        return active*sum+giveBack;
    }
    @Override public boolean equals(Object other) {
        return other instanceof Pool pool&&id==pool.id&&partner==pool.partner&&Double.compare(area,pool.area)==0&&Double.compare(bottom,pool.bottom)==0&&Double.compare(bundle,pool.bundle)==0
                &&Arrays.equals(wall,pool.wall)&&Double.compare(giveBack,pool.giveBack)==0&&Double.compare(active,pool.active)==0;
    }
    @Override public int hashCode(){return 31*java.util.Objects.hash(id,partner,area,bottom,bundle,giveBack,active)+Arrays.hashCode(wall);}
    @Override public String toString(){return "Pool[id="+id+", partner="+partner+", area="+area+", bottom="+bottom+", bundle="+bundle+", wall="+Arrays.toString(wall)+", giveBack="+giveBack+", active="+active+"]";}
}
