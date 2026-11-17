package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.math.DeterministicMath;
import java.util.Objects;

/**
 * Smooth internal vessel geometry. Heights use the main body's bottom, including negative boot heights. {@code demister}:
 * a drum's mesh pad under its gas outlet ({@link Separation#DEMISTER}); a drum only.
 */
public record VesselGeometry(Form form,double capacity,double height,double diameter,double length,
                             double bootDiameter,double bootLength,boolean demister) {
    public enum Form { PRISM, CYLINDER, SPHERE, VERTICAL_DRUM, HORIZONTAL_DRUM }
    /** A vessel without a demister. */
    public VesselGeometry(Form form,double capacity,double height,double diameter,double length,double bootDiameter,double bootLength){
        this(form,capacity,height,diameter,length,bootDiameter,bootLength,false);
    }
    public VesselGeometry {
        Objects.requireNonNull(form);
        if(demister&&form!=Form.VERTICAL_DRUM&&form!=Form.HORIZONTAL_DRUM)throw new IllegalArgumentException("Only a drum has a demister");
        for(double v:new double[]{capacity,height,diameter,length,bootDiameter,bootLength})
            if(!Double.isFinite(v)||v<0)throw new IllegalArgumentException("Invalid vessel geometry");
        if(capacity<=0||height<=0||form!=Form.PRISM&&diameter<=0||
                (bootDiameter==0)!=(bootLength==0)||bootDiameter>0&&form!=Form.HORIZONTAL_DRUM)
            throw new IllegalArgumentException("Invalid vessel dimensions");
        double physicalHeight=switch(form){case PRISM->height;case CYLINDER->length;case SPHERE,HORIZONTAL_DRUM->diameter;case VERTICAL_DRUM->length+diameter/2;};
        if(Math.abs(height-physicalHeight)>height*1e-8||form==Form.PRISM&&(diameter!=0||length!=0||bootDiameter!=0||bootLength!=0)||form==Form.SPHERE&&length!=0)
            throw new IllegalArgumentException("Inconsistent vessel dimensions");
        if(form!=Form.PRISM&&Math.abs(capacity-physicalVolume(form,diameter,length,bootDiameter,bootLength))>1e-8*capacity)
            throw new IllegalArgumentException("Vessel volume differs from geometry");
    }
    public static VesselGeometry prism(double capacity,double height){return new VesselGeometry(Form.PRISM,capacity,height,0,0,0,0);}
    public static VesselGeometry round(Form form,double diameter,double length,double bootDiameter,double bootLength){
        double height=switch(form){case CYLINDER->length;case SPHERE,HORIZONTAL_DRUM->diameter;case VERTICAL_DRUM->length+diameter/2;default->throw new IllegalArgumentException("Not round");};
        return new VesselGeometry(form,physicalVolume(form,diameter,length,bootDiameter,bootLength),height,diameter,length,bootDiameter,bootLength);
    }
    /** This vessel with a demister or without one. */
    public VesselGeometry withDemister(boolean value){
        return value==demister?this:new VesselGeometry(form,capacity,height,diameter,length,bootDiameter,bootLength,value);
    }
    private static double physicalVolume(Form form,double d,double length,double bd,double bl){
        double body=switch(form){case CYLINDER->Math.PI*d*d*length/4;case SPHERE->Math.PI*d*d*d/6;
            case VERTICAL_DRUM,HORIZONTAL_DRUM->Math.PI*d*d*length/4+Math.PI*d*d*d/12;
            default->throw new IllegalArgumentException("Not round");};
        return body+Math.PI*bd*bd*bl/4+Math.PI*bd*bd*bd/24;
    }
    public boolean boot(){return bootDiameter>0;}
    public double bootDepth(){return boot()?bootLength+bootDiameter/4:0;}
    public double bootCapacity(){return Math.PI*bootDiameter*bootDiameter*bootLength/4+Math.PI*bootDiameter*bootDiameter*bootDiameter/24;}
    public double weirHeight(){return diameter/3;}
    public double weirVolume(){return boot()?volumeAt(weirHeight()):0;}
    /** Cumulative condensed volume below a body-relative elevation, m³. */
    public double volumeAt(double h){
        if(!Double.isFinite(h))throw new IllegalArgumentException("Nonfinite height");
        if(h<=-bootDepth())return 0;
        if(h>height)return capacity+(h-height)*capacity/height;
        if(form==Form.PRISM)return capacity*Math.max(0,h)/height;
        if(h<0){double z=h+bootDepth(),c=bootDiameter/4,r=bootDiameter/2;
            return z<c?headCap(r,c,z):Math.PI*r*r*(z-c)+headCap(r,c,c);}
        double r=diameter/2,body=switch(form){
            case CYLINDER->Math.PI*r*r*h;
            case SPHERE->Math.PI*h*h*(r-h/3);
            case HORIZONTAL_DRUM->{double x=Math.clamp(h,0,diameter),t=x/diameter;
                double area=segmentArea(r,x);
                yield length*area+Math.PI*diameter*diameter*diameter/12*(3*t*t-2*t*t*t);}
            case VERTICAL_DRUM->{double c=diameter/4,head=headCap(r,c,c);
                yield h<c?headCap(r,c,h):h<c+length?head+Math.PI*r*r*(h-c):
                        2*head+Math.PI*r*r*length-headCap(r,c,height-h);}
            default->throw new IllegalStateException();};
        return bootCapacity()+body;
    }
    /**
     * The horizontal section at a body-relative elevation, m²: the slope of {@link #volumeAt}, the area a drop settles
     * across there ({@link Separation}); a boot's own section below the body. The nearest section inside the vessel
     * beyond its ends.
     */
    public double planArea(double h){
        if(!Double.isFinite(h))throw new IllegalArgumentException("Nonfinite height");
        if(form==Form.PRISM)return capacity/height;
        h=Math.clamp(h,-bootDepth(),height);
        if(h<0){double z=h+bootDepth(),c=bootDiameter/4,r=bootDiameter/2;return z<c?headSection(r,c,z):Math.PI*r*r;}
        double r=diameter/2;
        return switch(form){
            case CYLINDER->Math.PI*r*r;
            case SPHERE->Math.PI*h*(diameter-h);
            case HORIZONTAL_DRUM->{double t=h/diameter;yield length*2*Math.sqrt(h*(diameter-h))+Math.PI*diameter*diameter/2*t*(1-t);}
            case VERTICAL_DRUM->{double c=diameter/4;yield h<c?headSection(r,c,h):h<c+length?Math.PI*r*r:headSection(r,c,height-h);}
            default->throw new IllegalStateException();};
    }
    /**
     * The section the gas crosses above a liquid level at body-relative elevation {@code level}, m² ({@link Separation}'s
     * gas law): a standing vessel's whole section, whatever its level; a lying drum's segment above the level, along its
     * axis.
     */
    public double vapourSection(double level){
        if(!Double.isFinite(level))throw new IllegalArgumentException("Nonfinite height");
        if(form==Form.PRISM)return capacity/height;
        double r=diameter/2;
        return form==Form.HORIZONTAL_DRUM?Math.PI*r*r-segmentArea(r,Math.clamp(level,0,diameter)):Math.PI*r*r;
    }
    /** Whether the gas crosses the vessel along a lying axis ({@link Separation#gasLimit}). */
    public boolean lying(){return form==Form.HORIZONTAL_DRUM;}
    private static double headSection(double r,double c,double h){return Math.PI*r*r*(2*h/c-h*h/(c*c));}
    static double segmentArea(double r,double h){
        if(h>r)return Math.PI*r*r-segmentArea(r,2*r-h);
        double angle=2*DeterministicMath.asin(Math.sqrt(Math.max(0,h/(2*r))));
        if(angle<.01){double a2=angle*angle;return r*r*angle*a2*(2.0/3-a2*(2.0/15-a2*(4.0/315-a2*2.0/2835)));}
        return r*r*(angle-DeterministicMath.sin(angle)*DeterministicMath.cos(angle));
    }
    private static double headCap(double r,double c,double h){return Math.PI*r*r*(h*h/c-h*h*h/(3*c*c));}
    /** Monotone bracketed inversion; no Newton derivative or thermodynamic flash at the endpoints. */
    public double heightAt(double volume){
        if(!Double.isFinite(volume)||volume<0)throw new IllegalArgumentException("Invalid condensed volume");
        if(volume==0)return -bootDepth();
        if(boot()&&volume==bootCapacity())return 0;
        if(volume>=capacity)return height+(volume-capacity)*height/capacity;
        if(form==Form.PRISM||form==Form.CYLINDER)return volume*height/capacity;
        double low=-bootDepth(),high=height;
        for(int i=0;i<56;i++){double mid=(low+high)/2;if(volumeAt(mid)<volume)low=mid;else high=mid;}
        return (low+high)/2;
    }
    /**
     * Pressure head over the body bottom (z = 0, the vessel node's elevation) from two stacked liquid/slurry layers, Pa.
     * A boot's column below is the line below its nozzle: the connection's own column from the node's elevation carries it,
     * so it is not counted here again (a 1 m boot full of water would add 9.8 kPa twice).
     */
    public double head(double lowerVolume,double lowerMass,double upperVolume,double upperMass){
        if(lowerVolume+upperVolume==0)return 0;
        double split=Math.max(0,heightAt(lowerVolume)),top=Math.max(0,heightAt(lowerVolume+upperVolume));
        return PassiveStepSolver.GRAVITY*((lowerVolume>0?lowerMass/lowerVolume*split:0)+
                (upperVolume>0?upperMass/upperVolume*(top-split):0));
    }
}
