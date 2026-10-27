package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.math.DeterministicMath;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;

/**
 * One heat-exchanging side's enthalpy-temperature-film curve at one composition and pressure, fixed for a step (batch
 * 2026-10-27-heat-exchanger-design, plan section 4). Knots carry the specific enthalpy {@code h} (J/kg, increasing), the
 * temperature (K, nondecreasing: equal temperatures are latent heat) and the side's film coefficient (W/m2K, positive),
 * all linear in {@code h} between knots. The kernel of the 2026-09-24 pipe thermal-loss study, ported and extended:
 *
 * <ul>
 *   <li>the film is a property of the curve, by vapour fraction ({@link #sample}), so a stream that starts to condense
 *   halfway through gets the condensing film there, with no mode and no lag;</li>
 *   <li>beyond its end knots the curve extends its end segment's temperature line and holds its end film, with value and
 *   slope continuous, and never throws inside a residual (AGENTS.md "Thermo validity"); {@link #extrapolated} reports
 *   it.</li>
 * </ul>
 */
public final class HeatCurve {
    /** A vapour mass fraction below this is all liquid film, above {@link #GAS_FROM} all gas film; between the two blends
     * and the plateau the boiling or condensing film holds. */
    public static final double LIQUID_UNTIL=.05,GAS_FROM=.95;
    private final double[] h,t,film;
    /** Each knot's vapour mass fraction where the curve was sampled ({@link #sample}), so {@link #withFilms} can give it
     * other films without a flash; null for a curve given its films directly. */
    private final double[] vapour;

    public HeatCurve(double[] h,double[] t,double[] film){this(h,t,film,null);}
    private HeatCurve(double[] h,double[] t,double[] film,double[] vapour) {
        if(h.length<2||h.length!=t.length||h.length!=film.length||vapour!=null&&vapour.length!=h.length)throw new IllegalArgumentException("At least two matching knots required");
        this.h=h.clone();this.t=t.clone();this.film=film.clone();this.vapour=vapour==null?null:vapour.clone();
        for(int i=0;i<h.length;i++)if(!Double.isFinite(this.h[i])||!Double.isFinite(this.t[i])||this.t[i]<=0||!Double.isFinite(this.film[i])||this.film[i]<=0
                ||i>0&&(this.h[i]<=this.h[i-1]||this.t[i]<this.t[i-1]))
            throw new IllegalArgumentException("A heat curve needs increasing enthalpy, nondecreasing temperature and positive films");
    }
    /** The same stream with {@code films} on its knots, by their vapour fractions (batch 2026-10-27-heat-exchanger-design,
     * W4: an air cooler's fans change its films from step to step). Only a sampled curve knows its vapour fractions. */
    public HeatCurve withFilms(Films films) {
        if(vapour==null)throw new IllegalStateException("A heat curve given its films directly has no vapour fractions");
        double[] value=new double[h.length];for(int i=0;i<h.length;i++)value[i]=films.at(vapour[i]);
        return new HeatCurve(h,t,value,vapour);
    }

    /** The films of the three phase regimes (W/m2K), each with its share of the wall and fouling (plan section 4). */
    public record Films(double liquid,double twoPhase,double gas) {
        public static final Films DEFAULT=new Films(2000,5000,100);
        public Films{if(!(liquid>0)||!(twoPhase>0)||!(gas>0)||!Double.isFinite(liquid+twoPhase+gas))throw new IllegalArgumentException("Invalid films");}
        /** The film at vapour mass fraction {@code x}: liquid, blended to the two-phase film by {@link #LIQUID_UNTIL}, held to
         * {@link #GAS_FROM}, blended to gas by 1. */
        public double at(double x) {
            if(x<=0)return liquid;
            if(x<LIQUID_UNTIL)return liquid+(twoPhase-liquid)*x/LIQUID_UNTIL;
            if(x<=GAS_FROM)return twoPhase;
            if(x<1)return twoPhase+(gas-twoPhase)*(x-GAS_FROM)/(1-GAS_FROM);
            return gas;
        }
    }

    public int knots(){return h.length;}
    public double enthalpy(int knot){return h[knot];}
    public double temperature(int knot){return t[knot];}
    public double film(int knot){return film[knot];}
    /** Whether {@code value} lies beyond the end knots, where the curve extrapolates. */
    public boolean extrapolated(double value){return value<h[0]||value>h[h.length-1];}

    /** The segment {@code value} lies on: -1 below the first knot, {@code knots()-1} above the last, else the segment
     * from that knot to the next. */
    private int segment(double value) {
        if(value<h[0])return -1;
        if(value>=h[h.length-1])return h.length-1;
        int at=Arrays.binarySearch(h,value);
        return at>=0?at:-at-2;
    }
    /** The knot a segment's line is anchored at and its temperature slope (K per J/kg); the ends extend their end segment. */
    private int anchor(int segment){return segment<0?0:Math.min(segment,h.length-2);}
    private double slope(int segment){int i=anchor(segment);return (t[i+1]-t[i])/(h[i+1]-h[i]);}
    private double filmSlope(int segment){if(segment<0||segment>=h.length-1)return 0;return (film[segment+1]-film[segment])/(h[segment+1]-h[segment]);}
    private double temperatureOn(int segment,double value){int i=anchor(segment);return t[i]+(value-h[i])*slope(segment);}
    private double filmOn(int segment,double value) {
        if(segment<0)return film[0];
        if(segment>=h.length-1)return film[h.length-1];
        return film[segment]+(value-h[segment])*filmSlope(segment);
    }
    public double temperatureAt(double value){return temperatureOn(segment(value),value);}
    /** The temperature's slope at {@code value}, K per J/kg: 0 on a plateau (latent heat), the end lines' beyond the end knots. */
    public double temperatureSlope(double value){return slope(segment(value));}
    public double filmAt(double value){return filmOn(segment(value),value);}
    /**
     * The enthalpy (J/kg) at {@code temperature} K: the largest on the curve, so a plateau's temperature names its vapour
     * end (a fired coil set to a pure stream's saturation temperature boils it). Beyond the end knots the end lines extend,
     * as {@link #temperatureAt} does; an end segment without slope holds its end knot.
     */
    public double enthalpyAt(double temperature) {
        int last=h.length-1;
        if(temperature>=t[last]){double s=slope(last);return s>0?h[last]+(temperature-t[last])/s:h[last];}
        if(temperature<t[0]){double s=slope(-1);return s>0?h[0]+(temperature-t[0])/s:h[0];}
        int i=last;while(t[i]>temperature)i--;
        return t[i]==temperature?h[i]:h[i]+(temperature-t[i])*(h[i+1]-h[i])/(t[i+1]-t[i]);
    }
    /**
     * The smallest enthalpy (J/kg) at {@code temperature} K: a plateau's temperature names its liquid end (a stream cooled to
     * its saturation temperature is all condensed there; W4, plan section 6). Elsewhere as {@link #enthalpyAt}.
     */
    public double lowestEnthalpyAt(double temperature) {
        if(temperature>t[h.length-1]||temperature<t[0])return enthalpyAt(temperature);
        if(temperature==t[0])return h[0];
        int i=0;while(t[i+1]<temperature)i++;
        return t[i+1]==temperature?h[i+1]:h[i]+(temperature-t[i])*(h[i+1]-h[i])/(t[i+1]-t[i]);
    }

    /**
     * The outlet enthalpy after {@code area} m2 of wall at {@code wall} K: dh/dA = -film(h) (T(h) - wall) / |massFlow|,
     * exact on each segment (both factors are linear in h there, a partial-fraction integral). The stream approaches the
     * wall's temperature and never crosses it. Zero flow or zero area returns the inlet.
     */
    public double march(double inlet,double massFlow,double area,double wall) {
        if(!Double.isFinite(inlet)||!Double.isFinite(massFlow)||!Double.isFinite(wall)||!(area>=0))throw new IllegalArgumentException("Invalid march");
        double mass=Math.abs(massFlow);
        if(mass==0||area==0)return inlet;
        double value=inlet,remaining=area;
        for(int guard=0;guard<=h.length+2;guard++) {
            int segment=segment(value);
            double difference=temperatureOn(segment,value)-wall;
            if(difference==0)return value;
            boolean cooling=difference>0;
            // At a knot a cooling stream leaves on the segment below it.
            if(cooling&&segment>=0&&segment<h.length&&value==h[segment])segment--;
            double d=slope(segment),b=filmSlope(segment),f0=filmOn(segment,value),d0=temperatureOn(segment,value)-wall;
            double boundary=cooling?(segment<0?Double.NEGATIVE_INFINITY:h[segment]):(segment>=h.length-1?Double.POSITIVE_INFINITY:h[segment+1]);
            if(Double.isFinite(boundary)) {
                double delta=boundary-value,de=d0+d*delta;
                if(de!=0&&(de>0)==cooling) {
                    // The area to reach the boundary: -mass * int ds / ((f0 + b s)(d0 + d s)) = -mass delta / (f0 de) L(r).
                    double p=b*delta/f0,q=d*delta/d0,r=(p-q)/(1+q);
                    double crossing=-mass*delta/(f0*de)*logRatio(r);
                    if(remaining>=crossing){value=boundary;remaining-=crossing;continue;}
                }
            }
            return value+within(f0,d0,d,b,remaining/mass);
        }
        throw new IllegalStateException("Heat curve march did not end");
    }
    /** {@code log1p(r)/r}, 1 at 0. */
    private static double logRatio(double r){return r==0?1:DeterministicMath.log1p(r)/r;}
    /**
     * The enthalpy change inside one segment after {@code exposure} = area / mass (m2 s/kg), with the film {@code f0 + b s}
     * and the temperature difference {@code d0 + d s} at the step {@code s}: s = g f0 d0 / (1 - g d f0) with
     * g = -exposure expm1(z)/z and z = -(b d0 - d f0) exposure, whose limits are the constant-film exponential and the
     * common-root case without a division by their difference.
     */
    private static double within(double f0,double d0,double d,double b,double exposure) {
        double z=-(b*d0-d*f0)*exposure;
        if(z>600)return d!=0?-d0/d:Double.isFinite(f0/b)?-f0/b:0;
        double ratio=z==0?1:DeterministicMath.expm1(z)/z,g=-exposure*ratio;
        return g*f0*d0/(1-g*d*f0);
    }

    /**
     * The curve of a fluid of total amounts {@code amounts} (mol, the order {@link FluidThermodynamics#flashTP} reads) at
     * {@code pressure} Pa, from {@code low} to {@code high} K. Knots start evenly in temperature (16 divisions), a phase
     * change is bisected to 1e-5 K, other segments are refined while the midpoint's temperature lies more than 0.2 K off
     * the chord, and knots at the vapour fractions {@link #LIQUID_UNTIL} and {@link #GAS_FROM} are placed along h, where
     * the vapour fraction is linear: a pure component's plateau, which has no other inner knot, so takes the two-phase
     * film between them. Solids, if any, are not on the curve.
     */
    public static HeatCurve sample(FluidThermodynamics model,double[] amounts,double pressure,double low,double high,Films films,Runnable checkpoint) {
        if(!(low>0)||!(high>low)||!Double.isFinite(high))throw new IllegalArgumentException("Invalid curve span");
        var points=new TreeMap<Double,Point>();
        for(int i=0;i<=DIVISIONS;i++){double temperature=low+(high-low)*i/DIVISIONS;points.put(temperature,point(model,amounts,pressure,temperature,checkpoint));}
        var initial=new ArrayList<>(points.values());
        for(int i=0;i<initial.size()-1;i++)refine(model,amounts,pressure,initial.get(i),initial.get(i+1),points,0,checkpoint);
        var knots=new ArrayList<Point>();
        Point previous=null;
        for(var point:points.values()) {
            if(previous!=null)for(double x:new double[]{LIQUID_UNTIL,GAS_FROM}) {
                double lo=Math.min(previous.vapour,point.vapour),hi=Math.max(previous.vapour,point.vapour);
                if(x>lo&&x<hi) {
                    double share=(x-previous.vapour)/(point.vapour-previous.vapour);
                    double value=previous.h+share*(point.h-previous.h);
                    if(value>previous.h&&value<point.h)knots.add(new Point(previous.t+share*(point.t-previous.t),value,x));
                }
            }
            knots.add(point);previous=point;
        }
        knots.sort((a,c)->Double.compare(a.h,c.h));
        var kept=new ArrayList<Point>();
        for(var point:knots)if(kept.isEmpty()||point.h>kept.getLast().h)kept.add(point);
        double[] hs=new double[kept.size()],ts=new double[kept.size()],fs=new double[kept.size()],xs=new double[kept.size()];
        double floor=0;
        for(int i=0;i<kept.size();i++){var point=kept.get(i);hs[i]=point.h;ts[i]=floor=Math.max(floor,point.t);xs[i]=point.vapour;fs[i]=films.at(point.vapour);}
        return new HeatCurve(hs,ts,fs,xs);
    }
    private static final int DIVISIONS=16,MAXIMUM_KNOTS=512;
    private record Point(double t,double h,double vapour) {}
    private static Point point(FluidThermodynamics model,double[] amounts,double pressure,double temperature,Runnable checkpoint) {
        checkpoint.run();
        var state=model.flashTP(temperature,pressure,amounts,checkpoint);
        return new Point(temperature,state.enthalpy()/state.mass(),model.vaporMass(state)/state.mass());
    }
    private static void refine(FluidThermodynamics model,double[] amounts,double pressure,Point a,Point b,TreeMap<Double,Point> points,int depth,Runnable checkpoint) {
        if(depth>=24||b.t-a.t<1e-5||points.size()>=MAXIMUM_KNOTS)return;
        boolean phase=Math.abs(a.vapour-b.vapour)>.1;
        var mid=point(model,amounts,pressure,(a.t+b.t)/2,checkpoint);
        if(!phase) {
            double chord=a.t+(b.t-a.t)*(mid.h-a.h)/(b.h-a.h);
            if(Math.abs(chord-mid.t)<=.2)return;
        }
        points.put(mid.t,mid);
        refine(model,amounts,pressure,a,mid,points,depth+1,checkpoint);
        refine(model,amounts,pressure,mid,b,points,depth+1,checkpoint);
    }
    /** Knots for a test or a caller with its own data. */
    public static HeatCurve of(List<double[]> knots) {
        double[] hs=new double[knots.size()],ts=new double[knots.size()],fs=new double[knots.size()];
        for(int i=0;i<knots.size();i++){hs[i]=knots.get(i)[0];ts[i]=knots.get(i)[1];fs[i]=knots.get(i)[2];}
        return new HeatCurve(hs,ts,fs);
    }
}
