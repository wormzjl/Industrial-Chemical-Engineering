package com.wormzjl.icheme.science.fluid.network;

import java.util.Arrays;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * A connection that exchanges heat with a wall: one side of an exchanger or a kettle's tube bundle (batch
 * 2026-10-27-heat-exchanger-design, plan section 5.1). Its hydraulics are its connection's sections; its heat
 * is a pure function of the connection's flow and the donor's specific enthalpy for a step, on the donor's
 * {@link HeatCurve}: no unknown, no mode, exactly zero at no flow.
 *
 * <p>The facts are the side's wall surface ({@code area}, m2), its {@link HeatCurve.Films} and its {@code partner}: the
 * connection id of the exchanger's other side, whose stream makes the wall (plan section 5.2), or 0 for a side whose wall
 * is fixed (an air cooler's air). The slice inputs, fixed for a slice by whoever plans it, are the wall temperature of each
 * cell in the order a forward flow (first end to second) meets them ({@code wall}, K; a reversed flow meets them the other
 * way, parallel to the profile for that slice), the bypass share {@code bypass} of the flow that passes outside the cells
 * and rejoins at the outlet, the give-back {@code giveBack} (J/kg), an enthalpy shift on the whole flow that returns heat
 * in transit, and the {@code active} share of the slice in which the cells move heat (1 but where the partner counted this
 * side stopped and so moved none: the sit-out of plan section 5.2). Its built-in {@code control} (W4, plan section 6; null
 * for none) is a fact; it plans the bypass with the wall at each slice start ({@link ExchangerRelay#plan}), and {@code limit}
 * records why the planned bypass stands where it does. Only the facts are the connection's identity ({@link #shape}).
 */
public record HeatSide(double area,HeatCurve.Films films,double[] wall,double bypass,double giveBack,long partner,double active,@Nullable HeatControl control,HeatControl.Limit limit) implements HeatLaw {
    public HeatSide {
        Objects.requireNonNull(films);Objects.requireNonNull(limit);
        if(!(area>0)||!Double.isFinite(area)||wall.length<1||!(bypass>=0&&bypass<=1)||!Double.isFinite(giveBack)||!(active>=0&&active<=1))throw new IllegalArgumentException("Invalid heat side");
        wall=wall.clone();
        for(double temperature:wall)if(!(temperature>0)||!Double.isFinite(temperature))throw new IllegalArgumentException("Invalid wall temperature");
    }
    /** A side without a partner, its cells active the whole slice. */
    public HeatSide(double area,HeatCurve.Films films,double[] wall,double bypass,double giveBack){this(area,films,wall,bypass,giveBack,0,1);}
    /** A side without a controller. */
    public HeatSide(double area,HeatCurve.Films films,double[] wall,double bypass,double giveBack,long partner,double active){this(area,films,wall,bypass,giveBack,partner,active,null,HeatControl.Limit.NONE);}
    /** The facts that shape the side's equations, without the slice inputs. */
    public record Shape(double area,HeatCurve.Films films,int cells,long partner,HeatControl control) {}
    @Override public Shape shape(){return new Shape(area,films,wall.length,partner,control);}
    @Override public double[] wall(){return wall.clone();}
    public int cells(){return wall.length;}
    public double wallTemperature(int cell){return wall[cell];}
    public double coldestWall(){double t=wall[0];for(double w:wall)t=Math.min(t,w);return t;}
    public double hottestWall(){double t=wall[0];for(double w:wall)t=Math.max(t,w);return t;}
    @Override public double coldest(){return coldestWall();}
    @Override public double hottest(){return hottestWall();}
    /** Whether the side's wall is made by an exchanger's other side (plan section 5.2). */
    public boolean coupled(){return partner!=0;}
    /** This side with other slice inputs, its cells active the whole slice. */
    public HeatSide withInputs(double[] wall,double bypass,double giveBack){return withInputs(wall,bypass,giveBack,1);}
    /** This side with other slice inputs, its controller's limit as it was. */
    public HeatSide withInputs(double[] wall,double bypass,double giveBack,double active){return withInputs(wall,bypass,giveBack,active,limit);}
    /** This side with other slice inputs. */
    public HeatSide withInputs(double[] wall,double bypass,double giveBack,double active,HeatControl.Limit why){return new HeatSide(area,films,wall,bypass,giveBack,partner,active,control,why);}

    /**
     * The heat (W) the stream takes up in passing, positive when it is heated: the share {@code 1 - bypass} of
     * {@code |massFlow|} marched through the cells against their wall temperatures on {@code curve}, from the donor's
     * specific enthalpy {@code inlet}, for the active share of the slice; plus the give-back on the whole flow.
     * {@code massFlow}'s sign picks the order the cells are met in.
     */
    @Override public double heat(HeatCurve curve,double inlet,double massFlow) {
        double mass=Math.abs(massFlow);
        if(mass==0)return 0;
        double through=(1-bypass)*mass,value=inlet,cell=area/wall.length;
        if(through>0&&active>0)for(int k=0;k<wall.length;k++)value=curve.march(value,through,cell,wall[massFlow>0?k:wall.length-1-k]);
        return active*through*(value-inlet)+mass*giveBack;
    }
    @Override public boolean equals(Object other) {
        return other instanceof HeatSide side&&Double.compare(area,side.area)==0&&films.equals(side.films)&&Arrays.equals(wall,side.wall)
                &&Double.compare(bypass,side.bypass)==0&&Double.compare(giveBack,side.giveBack)==0&&partner==side.partner&&Double.compare(active,side.active)==0
                &&Objects.equals(control,side.control)&&limit==side.limit;
    }
    @Override public int hashCode(){return 31*Objects.hash(area,films,bypass,giveBack,partner,active,control,limit)+Arrays.hashCode(wall);}
    @Override public String toString(){return "HeatSide[area="+area+", films="+films+", wall="+Arrays.toString(wall)+", bypass="+bypass+", giveBack="+giveBack+", partner="+partner+", active="+active+", control="+control+", limit="+limit+"]";}
}
