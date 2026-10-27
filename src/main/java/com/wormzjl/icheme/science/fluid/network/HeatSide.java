package com.wormzjl.icheme.science.fluid.network;

import java.util.Arrays;
import java.util.Objects;

/**
 * A connection that exchanges heat with a wall: one side of an exchanger, a kettle's tube bundle, an air cooler or a fired
 * heater (batch 2026-10-27-heat-exchanger-design, plan section 5.1). Its hydraulics are its connection's sections; its heat
 * is a pure function of the connection's flow and the donor's specific enthalpy for a step, on the donor's
 * {@link HeatCurve}: no unknown, no mode, exactly zero at no flow.
 *
 * <p>The facts are the side's wall surface ({@code area}, m2) and its {@link HeatCurve.Films}. The slice inputs, fixed for a
 * slice by whoever plans it, are the wall temperature of each cell in the order a forward flow (first end to second) meets
 * them ({@code wall}, K; a reversed flow meets them the other way, parallel to the profile for that slice), the bypass share
 * {@code bypass} of the flow that passes outside the cells and rejoins at the outlet, and the give-back {@code giveBack}
 * (J/kg), an enthalpy shift on the whole flow that returns heat in transit (plan section 5.2). Only the facts are the
 * connection's identity ({@link #shape}).
 */
public record HeatSide(double area,HeatCurve.Films films,double[] wall,double bypass,double giveBack) {
    public HeatSide {
        Objects.requireNonNull(films);
        if(!(area>0)||!Double.isFinite(area)||wall.length<1||!(bypass>=0&&bypass<=1)||!Double.isFinite(giveBack))throw new IllegalArgumentException("Invalid heat side");
        wall=wall.clone();
        for(double temperature:wall)if(!(temperature>0)||!Double.isFinite(temperature))throw new IllegalArgumentException("Invalid wall temperature");
    }
    /** The facts that shape the side's equations, without the slice inputs. */
    public record Shape(double area,HeatCurve.Films films,int cells) {}
    public Shape shape(){return new Shape(area,films,wall.length);}
    @Override public double[] wall(){return wall.clone();}
    public int cells(){return wall.length;}
    public double wallTemperature(int cell){return wall[cell];}
    public double coldestWall(){double t=wall[0];for(double w:wall)t=Math.min(t,w);return t;}
    public double hottestWall(){double t=wall[0];for(double w:wall)t=Math.max(t,w);return t;}
    /** This side with other slice inputs. */
    public HeatSide withInputs(double[] wall,double bypass,double giveBack){return new HeatSide(area,films,wall,bypass,giveBack);}

    /**
     * The heat (W) the stream takes up in passing, positive when it is heated: the share {@code 1 - bypass} of
     * {@code |massFlow|} marched through the cells against their wall temperatures on {@code curve}, from the donor's
     * specific enthalpy {@code inlet}, plus the give-back on the whole flow. {@code massFlow}'s sign picks the order the
     * cells are met in.
     */
    public double heat(HeatCurve curve,double inlet,double massFlow) {
        double mass=Math.abs(massFlow);
        if(mass==0)return 0;
        double through=(1-bypass)*mass,value=inlet,cell=area/wall.length;
        if(through>0)for(int k=0;k<wall.length;k++)value=curve.march(value,through,cell,wall[massFlow>0?k:wall.length-1-k]);
        return through*(value-inlet)+mass*giveBack;
    }
    @Override public boolean equals(Object other) {
        return other instanceof HeatSide side&&Double.compare(area,side.area)==0&&films.equals(side.films)&&Arrays.equals(wall,side.wall)
                &&Double.compare(bypass,side.bypass)==0&&Double.compare(giveBack,side.giveBack)==0;
    }
    @Override public int hashCode(){return 31*Objects.hash(area,films,bypass,giveBack)+Arrays.hashCode(wall);}
    @Override public String toString(){return "HeatSide[area="+area+", films="+films+", wall="+Arrays.toString(wall)+", bypass="+bypass+", giveBack="+giveBack+"]";}
}
