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
 *   slope continuous, and never throws inside a residual (AGENTS.md "Thermo validity"). It raises no notice: its span is
 *   a sampling window that the next step samples again around the new state, and the property models' own data-range
 *   notices report every node state that leaves a data range (owner Q15, 2026-10-28).</li>
 * </ul>
 */
public final class HeatCurve {
    /** A vapour mass fraction below this is all liquid film, above {@link #GAS_FROM} all gas film; between the two blends
     * and the plateau the boiling or condensing film holds. */
    public static final double LIQUID_UNTIL=.05,GAS_FROM=.95;
    /**
     * The reduced densities {@code v_c / v} of one fluid between which its film goes from the gas film to the liquid film
     * (S2b of batch 2026-11-15-supercritical-fluids): the model's gas-like and liquid-like bounds
     * ({@link FluidThermodynamics#liquidLikeWeight}), which the machines read too (S4). The gas film at or below
     * {@link #FILM_GAS_UNTIL}, the liquid film from {@link #FILM_LIQUID_FROM}, a smoothstep between, never the two-phase
     * film. An ordinary liquid and an ordinary gas keep their film to the bit; a dense fluid's film follows its density,
     * with no line.
     */
    public static final double FILM_GAS_UNTIL=FluidThermodynamics.GAS_LIKE_UNTIL,FILM_LIQUID_FROM=FluidThermodynamics.LIQUID_LIKE_FROM;
    private final double[] h,t,film;
    /** Each knot's vapour mass fraction where the curve was sampled ({@link #sample}), so {@link #withFilms} can give it
     * other films without a flash; null for a curve given its films directly. */
    private final double[] vapour;
    /** Each knot's liquid-like weight ({@link #liquidLikeWeight}), NaN for a knot of more than one phase; null with
     * {@link #vapour}. */
    private final double[] liquidLike;
    /** Each knot's viscous resistance ({@link #viscous(double)}, m²K/W, 0 for a thin liquid or no liquid film); null with
     * {@link #vapour}. */
    private final double[] viscous;

    /** The reference viscosity of the liquid film, Pa·s: water at 20 °C ({@link #viscous(double)}). */
    public static final double VISCOUS_REFERENCE=1e-3;
    /** The film's exponent on the viscosity ratio at one mass flow and bore: Dittus-Boelter's Re^0.8 Pr^0.4 gives μ^-0.4. */
    public static final double VISCOUS_EXPONENT=.4;
    /** The most viscous resistance, m²K/W: the reference liquid film falls at most to the gas film. */
    public static final double VISCOUS_MOST=1/Films.DEFAULT.gas()-1/Films.DEFAULT.liquid();

    public HeatCurve(double[] h,double[] t,double[] film){this(h,t,film,null,null,null);}
    private HeatCurve(double[] h,double[] t,double[] film,double[] vapour,double[] liquidLike,double[] viscous) {
        if(h.length<2||h.length!=t.length||h.length!=film.length||vapour!=null&&vapour.length!=h.length
                ||(vapour==null)!=(liquidLike==null)||(vapour==null)!=(viscous==null)||liquidLike!=null&&liquidLike.length!=h.length
                ||viscous!=null&&viscous.length!=h.length)throw new IllegalArgumentException("At least two matching knots required");
        this.h=h.clone();this.t=t.clone();this.film=film.clone();this.vapour=vapour==null?null:vapour.clone();this.liquidLike=liquidLike==null?null:liquidLike.clone();
        this.viscous=viscous==null?null:viscous.clone();
        for(int i=0;i<h.length;i++)if(!Double.isFinite(this.h[i])||!Double.isFinite(this.t[i])||this.t[i]<=0||!Double.isFinite(this.film[i])||this.film[i]<=0
                ||i>0&&(this.h[i]<=this.h[i-1]||this.t[i]<this.t[i-1])||this.viscous!=null&&!(this.viscous[i]>=0&&this.viscous[i]<=VISCOUS_MOST))
            throw new IllegalArgumentException("A heat curve needs increasing enthalpy, nondecreasing temperature and positive films");
    }
    /** The same stream with {@code films} on its knots, by their vapour fractions and viscous resistances (batch
     * 2026-10-27-heat-exchanger-design, W4: an air cooler's fans change its films from step to step). Only a sampled curve
     * knows them; no flash. */
    public HeatCurve withFilms(Films films) {
        if(vapour==null)throw new IllegalStateException("A heat curve given its films directly has no vapour fractions");
        return new HeatCurve(h,t,filmed(films,vapour,liquidLike,viscous),vapour,liquidLike,viscous);
    }
    /**
     * The knots' films: {@link Films#at(double,double,double)} of each knot, with an air cooler's {@link Films.Outer}
     * applied once per regime for every knot of no viscous resistance, and once more for a knot whose viscous liquid film
     * has weight (an air cooler's controller re-films a curve per trial share, {@link AirCooled#planned}).
     */
    private static double[] filmed(Films films,double[] xs,double[] ws,double[] vs) {
        double liquid=films.wallLiquid(),twoPhase=films.wallTwoPhase(),gas=films.wallGas();
        double[] value=new double[xs.length];
        for(int i=0;i<xs.length;i++) {
            double x=xs[i],w=ws[i],v=vs[i];
            value[i]=v>0&&liquidWeighted(x,w)?Films.blend(x,w,films.wallViscous(v),twoPhase,gas):Films.blend(x,w,liquid,twoPhase,gas);
        }
        return value;
    }
    /** Whether the liquid film has weight at vapour fraction {@code x} and liquid-like weight {@code w} ({@link Films#at(double,double)}). */
    private static boolean liquidWeighted(double x,double w){return Double.isNaN(w)?x<LIQUID_UNTIL:w>0;}
    /**
     * The viscous resistance of a liquid film, m²K/W (owner decision D13 of batch 2026-11-16-fouling-corrosion, 2026-11-16):
     * in turbulent flow at one mass flow and bore the film falls with the liquid's viscosity {@code mu} (Pa·s) as
     * Nu = 0.023 Re^0.8 Pr^0.4 gives, φ = (μ_ref/μ)^0.4 of the reference film at {@link #VISCOUS_REFERENCE} (water at 20 °C:
     * it and every thinner liquid keep the reference film). As a resistance in series with the film, as a deposit is:
     * (1/φ − 1)/2000. μ 3 mPa·s gives φ 0.644 (film 1289 W/m²K), 10 mPa·s 0.398 (796), 30 mPa·s 0.257 (513); the film never
     * falls below the gas film, {@link #VISCOUS_MOST}, which μ 1.79 Pa·s reaches. 0 for a thin, non-finite or non-positive
     * {@code mu}.
     */
    public static double viscous(double mu) {
        if(!(mu>VISCOUS_REFERENCE)||!Double.isFinite(mu))return 0;
        double phi=DeterministicMath.pow(VISCOUS_REFERENCE/mu,VISCOUS_EXPONENT);
        return Math.min(VISCOUS_MOST,(1/phi-1)/Films.DEFAULT.liquid());
    }
    /** A knot's viscous resistance ({@link #viscous(double)}), m²K/W; 0 for a curve given its films directly. */
    public double viscous(int knot){return viscous==null?0:viscous[knot];}
    /**
     * The liquid-like weight of a state of one fluid, the model's ({@link FluidThermodynamics#liquidLikeWeight}): 0 (gas
     * film) at or below reduced density {@link #FILM_GAS_UNTIL}, 1 (liquid film) from {@link #FILM_LIQUID_FROM}, a
     * smoothstep between; NaN for any other state (its film is by vapour fraction).
     */
    public static double liquidLikeWeight(FluidThermodynamics.State state){return FluidThermodynamics.liquidLikeWeight(state);}

    /**
     * The films of the three phase regimes (W/m2K), each with its share of the wall and fouling (plan section 4). With an
     * {@code outer} (an air cooler's, {@link AirCooled.Air#films}) the three are the films inside the tubes and the wall
     * sees each through the outer side ({@link #wallLiquid}): a liquid's viscous resistance ({@link HeatCurve#viscous(double)})
     * then lies inside the tubes, before the air. Null for every other side.
     */
    public record Films(double liquid,double twoPhase,double gas,@org.jetbrains.annotations.Nullable Outer outer) {
        public static final Films DEFAULT=new Films(2000,5000,100);
        public Films(double liquid,double twoPhase,double gas){this(liquid,twoPhase,gas,null);}
        public Films{if(!(liquid>0)||!(twoPhase>0)||!(gas>0)||!Double.isFinite(liquid+twoPhase+gas))throw new IllegalArgumentException("Invalid films");}
        /**
         * An air cooler's outer side per m² of bare tube (W4): its bare tube per m² of face {@code barePerFace}, the air's
         * film on the fins {@code air} (W/K per m² of face) and its capacity rate {@code rate} (W/K per m² of face). A data
         * record, so films with one compare by value ({@code PassiveStepSolver}'s curve cache).
         */
        public record Outer(double barePerFace,double air,double rate) {
            /** The effective film on the bare tube of a process film {@code process}: UA_eff = C (1 − e^(−UA/C)) per m² of
             * face, UA the process film on the bare tube in series with the air's film ({@link AirCooled.Air#films}). */
            public double apply(double process) {
                double conductance=1/(1/(barePerFace*process)+1/air);
                return -rate*DeterministicMath.expm1(-conductance/rate)/barePerFace;
            }
        }
        /** The liquid film the wall sees: {@link #liquid}, through the {@link #outer} side with one. */
        public double wallLiquid(){return outer==null?liquid:outer.apply(liquid);}
        /** The two-phase film the wall sees ({@link #wallLiquid}). */
        public double wallTwoPhase(){return outer==null?twoPhase:outer.apply(twoPhase);}
        /** The gas film the wall sees ({@link #wallLiquid}). */
        public double wallGas(){return outer==null?gas:outer.apply(gas);}
        /** The liquid film the wall sees behind a viscous resistance {@code viscous} (m²K/W) in series with {@link #liquid}. */
        double wallViscous(double viscous){double inner=1/(1/liquid+viscous);return outer==null?inner:outer.apply(inner);}
        /** The film at vapour mass fraction {@code x}: liquid, blended to the two-phase film by {@link #LIQUID_UNTIL}, held to
         * {@link #GAS_FROM}, blended to gas by 1. */
        public double at(double x){return blend(x,Double.NaN,wallLiquid(),wallTwoPhase(),wallGas());}
        /**
         * The film of a point of vapour fraction {@code x} and liquid-like weight {@code w} ({@link #liquidLikeWeight}): with
         * no weight (NaN, a point of two phases) {@link #at(double)}; else the gas film at {@code w <= 0}, the liquid film at
         * {@code w >= 1}, {@code gas + w (liquid - gas)} between: one fluid never takes the two-phase film.
         */
        public double at(double x,double w){return blend(x,w,wallLiquid(),wallTwoPhase(),wallGas());}
        /**
         * {@link #at(double,double)} with the liquid film behind a viscous resistance {@code viscous} (m²K/W,
         * {@link HeatCurve#viscous(double)}) in series: {@code 1/(1/liquid + viscous)}, inside an {@link #outer} side. At
         * {@code viscous} 0 exactly {@link #at(double,double)}.
         */
        public double at(double x,double w,double viscous) {
            if(viscous==0)return at(x,w);
            return blend(x,w,wallViscous(viscous),wallTwoPhase(),wallGas());
        }
        /** The blend of {@link #at(double,double)} over the wall's three films. */
        static double blend(double x,double w,double liquid,double twoPhase,double gas) {
            if(Double.isNaN(w)) {
                if(x<=0)return liquid;
                if(x<LIQUID_UNTIL)return liquid+(twoPhase-liquid)*x/LIQUID_UNTIL;
                if(x<=GAS_FROM)return twoPhase;
                if(x<1)return twoPhase+(gas-twoPhase)*(x-GAS_FROM)/(1-GAS_FROM);
                return gas;
            }
            if(w<=0)return gas;
            if(w>=1)return liquid;
            return gas+w*(liquid-gas);
        }
        /**
         * These films behind a fouling deposit of resistance {@code rf} (m²K/W) in series (batch 2026-11-16-fouling-corrosion,
         * plan section 3.2): each {@code h/(1 + h rf)}, inside the {@link #outer} side. Itself, the same instance, at {@code rf}
         * 0, so a clean side stays bit for bit what it was. Series resistances add in any order: a viscous liquid behind a
         * deposit is {@code 1/(1/liquid + rf + viscous)}.
         */
        public Films fouled(double rf) {
            if(rf==0)return this;
            if(!(rf>0)||!Double.isFinite(rf))throw new IllegalArgumentException("Invalid fouling resistance "+rf);
            return new Films(liquid/(1+liquid*rf),twoPhase/(1+twoPhase*rf),gas/(1+gas*rf),outer);
        }
    }

    public int knots(){return h.length;}
    public double enthalpy(int knot){return h[knot];}
    public double temperature(int knot){return t[knot];}
    public double film(int knot){return film[knot];}

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
     * change is bisected to {@link #BOUNDARY_KELVIN}, other segments are refined while the midpoint's temperature lies more than 0.2 K off
     * the chord, and knots at the vapour fractions {@link #LIQUID_UNTIL} and {@link #GAS_FROM} are placed along h, where
     * the vapour fraction is linear: a pure component's plateau, which has no other inner knot, so takes the two-phase
     * film between them. Solids, if any, are not on the curve. Each point where the liquid film has weight reads its liquid's
     * viscosity once, for its viscous resistance ({@link #viscous(double)}, decision D13 of batch 2026-11-16-fouling-corrosion).
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
                    // Between two points of one continuous fluid (the same slot, or both dense) the weight is interpolated as the
                    // vapour coordinate is (it is 1 - w); between a liquid and a vapour point a phase change lies (a pure
                    // component's plateau), so the knot has no weight and takes the two-phase film.
                    double weight=continuous(previous,point)?previous.w+share*(point.w-previous.w):Double.NaN;
                    double viscous=previous.viscous+share*(point.viscous-previous.viscous);
                    if(value>previous.h&&value<point.h)knots.add(new Point(previous.t+share*(point.t-previous.t),value,x,weight,-1,false,viscous));
                }
            }
            knots.add(point);previous=point;
        }
        knots.sort((a,c)->Double.compare(a.h,c.h));
        var kept=new ArrayList<Point>();
        for(var point:knots)if(kept.isEmpty()||point.h>kept.getLast().h)kept.add(point);
        double[] hs=new double[kept.size()],ts=new double[kept.size()],xs=new double[kept.size()],ws=new double[kept.size()],vs=new double[kept.size()];
        double floor=0;
        for(int i=0;i<kept.size();i++){var point=kept.get(i);hs[i]=point.h;ts[i]=floor=Math.max(floor,point.t);xs[i]=point.vapour;ws[i]=point.w;vs[i]=point.viscous;}
        return new HeatCurve(hs,ts,filmed(films,xs,ws,vs),xs,ws,vs);
    }
    private static final int DIVISIONS=16,MAXIMUM_KNOTS=512;
    /** How closely a phase boundary is bisected, K: the plateau's edge is then out by cp times this, about 4 J/kg against a
     * latent heat of megajoules per kilogram (L-F11 of the 2026-11-13 simplifications; 1e-5 K before, seven more flashes per boundary). */
    static final double BOUNDARY_KELVIN=1e-3;
    /** A sampled point: its vapour coordinate is the vapour mass fraction, or {@code 1 - w} for a point of one fluid of
     * liquid-like weight {@code w} (NaN for a point of more than one phase), so one fluid is never bisected as a phase change;
     * {@code slot} its one fluid slot (-1 for none or an inserted knot) and {@code dense} whether that fluid is
     * {@link FluidThermodynamics.State#dense()}; {@code viscous} its liquid film's viscous resistance ({@link #viscous(double)}),
     * 0 where the liquid film has no weight. */
    private record Point(double t,double h,double vapour,double w,int slot,boolean dense,double viscous) {}
    /** Whether one fluid runs continuously from {@code a} to {@code b}: both of one fluid, in the same slot or both dense. */
    private static boolean continuous(Point a,Point b) {
        return !Double.isNaN(a.w)&&!Double.isNaN(b.w)&&(a.slot>=0&&a.slot==b.slot||a.dense&&b.dense);
    }
    private static Point point(FluidThermodynamics model,double[] amounts,double pressure,double temperature,Runnable checkpoint) {
        checkpoint.run();
        var state=model.flashTP(temperature,pressure,amounts,checkpoint);
        double w=liquidLikeWeight(state),vapour=Double.isNaN(w)?model.vaporMass(state)/state.mass():1-w;
        // A plateau or a gas point, where the liquid film has no weight, reads no viscosity.
        double viscous=liquidWeighted(vapour,w)?viscous(liquidViscosity(model,state,w)):0;
        return new Point(temperature,state.enthalpy()/state.mass(),vapour,w,state.oneFluidSlot(),state.dense(),viscous);
    }
    /**
     * The viscosity (Pa·s) of the liquid a point's liquid film wets the wall with: the one fluid's ({@code w} not NaN), else
     * its held liquid slots' (oil and aqueous), weighted by slot volume, the network's rule for a stream of several phases
     * ({@code NetworkReduction.viscosity}). Solids are not on the curve: no slurry term. NaN, the reference film, where no
     * liquid is held or the viscosity model refuses the liquid.
     */
    private static double liquidViscosity(FluidThermodynamics model,FluidThermodynamics.State state,double w) {
        try {
            if(!Double.isNaN(w)){int slot=state.oneFluidSlot();return slot<0?Double.NaN:model.slotViscosity(state,slot,null,null);}
            double value=0,volume=0;
            for(int slot:new int[]{FluidThermodynamics.State.LIQUID,FluidThermodynamics.State.AQUEOUS}) {
                double v=state.slotVolume(slot);
                if(v>0){value+=v*model.slotViscosity(state,slot,null,null);volume+=v;}
            }
            return volume>0?value/volume:Double.NaN;
        } catch(IllegalArgumentException refused) {
            // MixtureViscosity.liquid refuses a liquid with no supported carrier (only conditional solutes) and a component
            // without liquid data: such a liquid keeps the reference film, and the curve samples as it did before D13.
            return Double.NaN;
        }
    }
    private static void refine(FluidThermodynamics model,double[] amounts,double pressure,Point a,Point b,TreeMap<Double,Point> points,int depth,Runnable checkpoint) {
        if(depth>=24||b.t-a.t<BOUNDARY_KELVIN||points.size()>=MAXIMUM_KNOTS)return;
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
