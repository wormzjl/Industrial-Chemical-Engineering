package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.math.DeterministicMath;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
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
    /** Each knot's liquid film resistance ({@link #liquidResistance(double,double,double)}, m²K/W, 0 for a liquid at or above
     * the reference film or no liquid film); null with {@link #vapour}. */
    private final double[] resistance;

    /** The reference liquid of the liquid film, water at 20 °C, whose film is {@link Films#DEFAULT}'s liquid film
     * ({@link #liquidResistance(double,double,double)}): viscosity Pa·s, thermal conductivity W/mK, heat capacity J/kgK. */
    public static final double VISCOUS_REFERENCE=1e-3,CONDUCTIVITY_REFERENCE=.6,HEAT_CAPACITY_REFERENCE=4180;
    /** The film's exponents at one mass flow and bore: Dittus-Boelter's Nu = 0.023 Re^0.8 Pr^0.4 gives h ∝ k^0.6 cp^0.4 μ^-0.4. */
    public static final double VISCOUS_EXPONENT=.4,CONDUCTIVITY_EXPONENT=.6,HEAT_CAPACITY_EXPONENT=.4;
    /** The most liquid film resistance, m²K/W: the reference liquid film falls at most to the gas film. */
    public static final double LIQUID_RESISTANCE_MOST=1/Films.DEFAULT.gas()-1/Films.DEFAULT.liquid();
    /** The shortest chord a point's liquid heat capacity is read over, K ({@link #sample}): a bisected phase boundary leaves
     * points {@link #BOUNDARY_KELVIN} apart, whose enthalpy difference is mostly round-off. */
    static final double CHORD_KELVIN=.05;

    public HeatCurve(double[] h,double[] t,double[] film){this(h,t,film,null,null,null);}
    private HeatCurve(double[] h,double[] t,double[] film,double[] vapour,double[] liquidLike,double[] resistance) {
        if(h.length<2||h.length!=t.length||h.length!=film.length||vapour!=null&&vapour.length!=h.length
                ||(vapour==null)!=(liquidLike==null)||(vapour==null)!=(resistance==null)||liquidLike!=null&&liquidLike.length!=h.length
                ||resistance!=null&&resistance.length!=h.length)throw new IllegalArgumentException("At least two matching knots required");
        this.h=h.clone();this.t=t.clone();this.film=film.clone();this.vapour=vapour==null?null:vapour.clone();this.liquidLike=liquidLike==null?null:liquidLike.clone();
        this.resistance=resistance==null?null:resistance.clone();
        for(int i=0;i<h.length;i++)if(!Double.isFinite(this.h[i])||!Double.isFinite(this.t[i])||this.t[i]<=0||!Double.isFinite(this.film[i])||this.film[i]<=0
                ||i>0&&(this.h[i]<=this.h[i-1]||this.t[i]<this.t[i-1])||this.resistance!=null&&!(this.resistance[i]>=0&&this.resistance[i]<=LIQUID_RESISTANCE_MOST))
            throw new IllegalArgumentException("A heat curve needs increasing enthalpy, nondecreasing temperature and positive films");
    }
    /** The same stream with {@code films} on its knots, by their vapour fractions and liquid film resistances (batch
     * 2026-10-27-heat-exchanger-design, W4: an air cooler's fans change its films from step to step). Only a sampled curve
     * knows them; no flash. */
    public HeatCurve withFilms(Films films) {
        if(vapour==null)throw new IllegalStateException("A heat curve given its films directly has no vapour fractions");
        return new HeatCurve(h,t,filmed(films,vapour,liquidLike,resistance),vapour,liquidLike,resistance);
    }
    /**
     * The knots' films: {@link Films#at(double,double,double)} of each knot. An air cooler's {@link Films.Outer} is applied
     * once per regime for the knots of no liquid film resistance, and once for each knot whose liquid film has weight and a
     * resistance (most liquid knots: an air cooler's controller re-films a curve per trial share, {@link AirCooled#planned}).
     */
    private static double[] filmed(Films films,double[] xs,double[] ws,double[] rs) {
        double liquid=films.wallLiquid(),twoPhase=films.wallTwoPhase(),gas=films.wallGas();
        double[] value=new double[xs.length];
        for(int i=0;i<xs.length;i++) {
            double x=xs[i],w=ws[i],r=rs[i];
            value[i]=r>0&&liquidWeighted(x,w)?Films.blend(x,w,films.wallLiquid(r),twoPhase,gas):Films.blend(x,w,liquid,twoPhase,gas);
        }
        return value;
    }
    /** Whether the liquid film has weight at vapour fraction {@code x} and liquid-like weight {@code w} ({@link Films#at(double,double)}). */
    private static boolean liquidWeighted(double x,double w){return Double.isNaN(w)?x<LIQUID_UNTIL:w>0;}
    /**
     * The resistance of a liquid film, m²K/W, against the reference film of {@link Films#DEFAULT} (owner decisions D13 and
     * D15 of batch 2026-11-16-fouling-corrosion, 2026-11-16): in turbulent flow at one mass flow and bore Nu = 0.023 Re^0.8
     * Pr^0.4 gives φ = (k/k_ref)^0.6 (cp/cp_ref)^0.4 (μ_ref/μ)^0.4 of the reference film, water at 20 °C
     * ({@link #VISCOUS_REFERENCE} and its siblings), with {@code mu} Pa·s, {@code k} W/mK and {@code cp} J/kgK; density is
     * left out. φ is at most 1, so no liquid film exceeds the reference. As a resistance in series with the film, as a
     * deposit is: (1/φ − 1)/5000, at most {@link #LIQUID_RESISTANCE_MOST} (the film never falls below the gas film). A
     * light oil (1 mPa·s, 0.126 W/mK, 2000 J/kgK) gets φ 0.292, film 1459 W/m²K; a 50 mPa·s oil (0.110, 2300) 298. A
     * non-finite or non-positive input makes its own factor 1, the reference's.
     */
    public static double liquidResistance(double mu,double k,double cp) {
        double phi=DeterministicMath.pow(ratio(k,CONDUCTIVITY_REFERENCE),CONDUCTIVITY_EXPONENT)
                *DeterministicMath.pow(ratio(cp,HEAT_CAPACITY_REFERENCE),HEAT_CAPACITY_EXPONENT)
                *DeterministicMath.pow(1/ratio(mu,VISCOUS_REFERENCE),VISCOUS_EXPONENT);
        if(!(phi<1))return 0;
        return Math.min(LIQUID_RESISTANCE_MOST,(1/phi-1)/Films.DEFAULT.liquid());
    }
    /** {@code value / reference}, 1 for a non-finite or non-positive {@code value}. */
    private static double ratio(double value,double reference){return value>0&&Double.isFinite(value)?value/reference:1;}
    /** A knot's liquid film resistance ({@link #liquidResistance(double,double,double)}), m²K/W; 0 for a curve given its films directly. */
    public double liquidResistance(int knot){return resistance==null?0:resistance[knot];}
    /**
     * The thermal conductivity of liquid water at {@code temperature} K, W/mK (D15): one quadratic in t = (T − 273.15 K)/100 K
     * fitted to saturated liquid water (IAPWS 2011 as the NIST WebBook tabulates it, 0 to 300 °C every 25 K, within 2.1 %:
     * 0.556 at 0 °C, 0.606 at 25 °C, 0.677 at 100 °C, 0.660 at 200 °C, 0.553 at 300 °C), held at its end values outside, so
     * it stays continuous. No pressure term.
     */
    public static double waterConductivity(double temperature) {
        double x=Math.min(3,Math.max(0,(temperature-273.15)/100));
        return .56698+x*(.16157-.05619*x);
    }
    /** The floor of {@link #oilConductivity}, W/mK, which the rule reaches at 893 K: positive and continuous. */
    public static final double OIL_CONDUCTIVITY_FLOOR=.05;
    /**
     * The thermal conductivity of a petroleum liquid at {@code temperature} K, W/mK (D15): the API Technical Data Book's rule
     * for petroleum fractions with no data, k = 0.164 − 1.277e-4 T (Riazi, Characterization and Properties of Petroleum
     * Fractions, chapter 8; in field units 0.07577 − 4.1e-5 T[°F] Btu/h·ft·°F): 0.126 at 25 °C, 0.091 at 300 °C. At least
     * {@link #OIL_CONDUCTIVITY_FLOOR}.
     */
    public static double oilConductivity(double temperature){return Math.max(OIL_CONDUCTIVITY_FLOOR,.164-1.277e-4*temperature);}
    /**
     * The liquid-like weight of a state of one fluid, the model's ({@link FluidThermodynamics#liquidLikeWeight}): 0 (gas
     * film) at or below reduced density {@link #FILM_GAS_UNTIL}, 1 (liquid film) from {@link #FILM_LIQUID_FROM}, a
     * smoothstep between; NaN for any other state (its film is by vapour fraction).
     */
    public static double liquidLikeWeight(FluidThermodynamics.State state){return FluidThermodynamics.liquidLikeWeight(state);}

    /**
     * The films of the three phase regimes (W/m2K), each with its share of the wall and fouling (plan section 4). With an
     * {@code outer} (an air cooler's, {@link AirCooled.Air#films}) the three are the films inside the tubes and the wall
     * sees each through the outer side ({@link #wallLiquid}): a liquid film's resistance
     * ({@link HeatCurve#liquidResistance(double,double,double)}) then lies inside the tubes, before the air. Null for every
     * other side. {@code deposit} is the fouling deposit (m²K/W) the three already hold in series ({@link #fouled}), 0 for
     * clean films, so {@link #clean} gives them back without it (decision D16 of batch 2026-11-16-fouling-corrosion: the
     * unit's clean UA).
     */
    public record Films(double liquid,double twoPhase,double gas,@org.jetbrains.annotations.Nullable Outer outer,double deposit) {
        /** The reference films: the liquid film is the reference liquid's, water at 20 °C (D15; every other liquid's falls
         * from it by {@link HeatCurve#liquidResistance(double,double,double)}), the boiling or condensing film and the gas film. */
        public static final Films DEFAULT=new Films(5000,5000,100);
        public Films(double liquid,double twoPhase,double gas){this(liquid,twoPhase,gas,null);}
        public Films(double liquid,double twoPhase,double gas,@org.jetbrains.annotations.Nullable Outer outer){this(liquid,twoPhase,gas,outer,0);}
        public Films{if(!(liquid>0)||!(twoPhase>0)||!(gas>0)||!Double.isFinite(liquid+twoPhase+gas)||!(deposit>=0)||!Double.isFinite(deposit)
                ||!(liquid*deposit<1)||!(twoPhase*deposit<1)||!(gas*deposit<1))throw new IllegalArgumentException("Invalid films");}
        /** These films with {@code value} as their outer side, the deposit kept. */
        public Films withOuter(@org.jetbrains.annotations.Nullable Outer value){return new Films(liquid,twoPhase,gas,value,deposit);}
        /**
         * These films without their {@link #deposit}: each {@code h/(1 − h rf)}, the outer side kept. Itself, the same
         * instance, for clean films. No flash: a curve takes them with {@link HeatCurve#withFilms}.
         */
        public Films clean() {
            if(deposit==0)return this;
            return new Films(liquid/(1-liquid*deposit),twoPhase/(1-twoPhase*deposit),gas/(1-gas*deposit),outer,0);
        }
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
        /** The liquid film the wall sees behind a liquid film resistance {@code resistance} (m²K/W) in series with {@link #liquid}. */
        double wallLiquid(double resistance){double inner=1/(1/liquid+resistance);return outer==null?inner:outer.apply(inner);}
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
         * {@link #at(double,double)} with the liquid film behind a liquid film resistance {@code resistance} (m²K/W,
         * {@link HeatCurve#liquidResistance(double,double,double)}) in series: {@code 1/(1/liquid + resistance)}, inside an
         * {@link #outer} side. At {@code resistance} 0 exactly {@link #at(double,double)}.
         */
        public double at(double x,double w,double resistance) {
            if(resistance==0)return at(x,w);
            return blend(x,w,wallLiquid(resistance),wallTwoPhase(),wallGas());
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
         * 0, so a clean side stays bit for bit what it was. Series resistances add in any order: a liquid film behind a
         * deposit is {@code 1/(1/liquid + rf + resistance)}. The films record {@code rf} in their {@link #deposit}.
         */
        public Films fouled(double rf) {
            if(rf==0)return this;
            if(!(rf>0)||!Double.isFinite(rf))throw new IllegalArgumentException("Invalid fouling resistance "+rf);
            return new Films(liquid/(1+liquid*rf),twoPhase/(1+twoPhase*rf),gas/(1+gas*rf),outer,deposit+rf);
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
     * viscosity once and its conductivity from its temperature, and takes its heat capacity from the curve's own enthalpy
     * ({@link #resisted}), for its liquid film resistance ({@link #liquidResistance(double,double,double)}, decisions D13 and
     * D15 of batch 2026-11-16-fouling-corrosion): no flash beyond the curve's own.
     */
    public static HeatCurve sample(FluidThermodynamics model,double[] amounts,double pressure,double low,double high,Films films,Runnable checkpoint) {
        if(!(low>0)||!(high>low)||!Double.isFinite(high))throw new IllegalArgumentException("Invalid curve span");
        var points=new TreeMap<Double,Point>();
        for(int i=0;i<=DIVISIONS;i++){double temperature=low+(high-low)*i/DIVISIONS;points.put(temperature,point(model,amounts,pressure,temperature,checkpoint));}
        var initial=new ArrayList<>(points.values());
        for(int i=0;i<initial.size()-1;i++)refine(model,amounts,pressure,initial.get(i),initial.get(i+1),points,0,checkpoint);
        var knots=new ArrayList<Point>();
        Point previous=null;
        for(var point:resisted(new ArrayList<>(points.values()))) {
            if(previous!=null)for(double x:new double[]{LIQUID_UNTIL,GAS_FROM}) {
                double lo=Math.min(previous.vapour,point.vapour),hi=Math.max(previous.vapour,point.vapour);
                if(x>lo&&x<hi) {
                    double share=(x-previous.vapour)/(point.vapour-previous.vapour);
                    double value=previous.h+share*(point.h-previous.h);
                    // Between two points of one continuous fluid (the same slot, or both dense) the weight is interpolated as the
                    // vapour coordinate is (it is 1 - w); between a liquid and a vapour point a phase change lies (a pure
                    // component's plateau), so the knot has no weight and takes the two-phase film.
                    double weight=continuous(previous,point)?previous.w+share*(point.w-previous.w):Double.NaN;
                    double resistance=previous.resistance+share*(point.resistance-previous.resistance);
                    if(value>previous.h&&value<point.h)knots.add(new Point(previous.t+share*(point.t-previous.t),value,x,weight,-1,false,Double.NaN,Double.NaN,resistance));
                }
            }
            knots.add(point);previous=point;
        }
        knots.sort((a,c)->Double.compare(a.h,c.h));
        var kept=new ArrayList<Point>();
        for(var point:knots)if(kept.isEmpty()||point.h>kept.getLast().h)kept.add(point);
        double[] hs=new double[kept.size()],ts=new double[kept.size()],xs=new double[kept.size()],ws=new double[kept.size()],rs=new double[kept.size()];
        double floor=0;
        for(int i=0;i<kept.size();i++){var point=kept.get(i);hs[i]=point.h;ts[i]=floor=Math.max(floor,point.t);xs[i]=point.vapour;ws[i]=point.w;rs[i]=point.resistance;}
        return new HeatCurve(hs,ts,filmed(films,xs,ws,rs),xs,ws,rs);
    }

    /**
     * Completed samples owned by one island job. The thermodynamic knots and liquid resistance
     * depend on the exact model, amounts, pressure and span. The requesting side's films are
     * applied to those knots even when another side or substep sampled them first.
     */
    static final class Samples {
        private static final int CAPACITY = 64;
        private final LinkedHashMap<Input, Sample> kept = new LinkedHashMap<>(16, .75f, true);

        HeatCurve sample(FluidThermodynamics model,double[] amounts,double pressure,double low,double high,
                         Films films,Runnable checkpoint,Runnable sampled) {
            long p = Double.doubleToRawLongBits(pressure);
            long lower = Double.doubleToRawLongBits(low);
            long upper = Double.doubleToRawLongBits(high);
            var input = new Input(model, amounts, p, lower, upper);
            var old = kept.get(input);
            if (old != null) {
                checkpoint.run();
                return old.films.equals(films) ? old.curve : old.curve.withFilms(films);
            }
            double[] sampledAmounts = amounts.clone();
            if (sampled != null) sampled.run();
            var curve = HeatCurve.sample(model, sampledAmounts, pressure, low, high, films, checkpoint);
            kept.put(new Input(model, sampledAmounts, p, lower, upper), new Sample(films, curve));
            if (kept.size() > CAPACITY) kept.pollFirstEntry();
            return curve;
        }

        private record Sample(Films films, HeatCurve curve) {}

        private record Input(FluidThermodynamics model, double[] amounts, long pressure, long low, long high) {
            @Override public int hashCode() {
                int hash = System.identityHashCode(model);
                hash = 31 * hash + Long.hashCode(pressure);
                hash = 31 * hash + Long.hashCode(low);
                hash = 31 * hash + Long.hashCode(high);
                for (double amount : amounts) hash = 31 * hash + Long.hashCode(Double.doubleToRawLongBits(amount));
                return hash;
            }

            @Override public boolean equals(Object other) {
                if (!(other instanceof Input input) || model != input.model || pressure != input.pressure
                        || low != input.low || high != input.high || amounts.length != input.amounts.length) return false;
                for (int i = 0; i < amounts.length; i++)
                    if (Double.doubleToRawLongBits(amounts[i]) != Double.doubleToRawLongBits(input.amounts[i])) return false;
                return true;
            }
        }
    }
    /**
     * The sampled points, in temperature order, with their liquid film resistances (D15). A point whose liquid film has weight
     * takes its heat capacity as the slope Δh/ΔT of a chord that is all one liquid: both ends of several phases at vapour
     * fraction 0, or one fluid running {@link #continuous} between them, at least {@link #CHORD_KELVIN} long (a shorter
     * step walks one point further). The chord from below to above is preferred, else the one-sided one. A point with no
     * such chord (a vapour fraction between 0 and {@link #LIQUID_UNTIL}: its chords carry latent heat) takes the heat
     * capacity of the nearest point in temperature that has one; a curve with none, and a slope that is not positive and
     * finite, the reference's. A point with no liquid film weight keeps resistance 0.
     */
    private static List<Point> resisted(List<Point> points) {
        int n=points.size();
        double[] own=new double[n];
        for(int i=0;i<n;i++)own[i]=weighted(points.get(i))?chordCapacity(points,i):Double.NaN;
        var out=new ArrayList<Point>(n);
        for(int i=0;i<n;i++) {
            var p=points.get(i);
            if(!weighted(p)){out.add(p);continue;}
            double cp=own[i];
            if(Double.isNaN(cp)) {
                double nearest=Double.POSITIVE_INFINITY;
                for(int j=0;j<n;j++)if(!Double.isNaN(own[j])&&Math.abs(points.get(j).t-p.t)<nearest){nearest=Math.abs(points.get(j).t-p.t);cp=own[j];}
            }
            out.add(new Point(p.t,p.h,p.vapour,p.w,p.slot,p.dense,p.mu,p.k,liquidResistance(p.mu,p.k,cp)));
        }
        return out;
    }
    private static boolean weighted(Point p){return liquidWeighted(p.vapour,p.w);}
    /** The heat capacity, J/kgK, of point {@code i}'s liquid chord ({@link #resisted}): NaN where it has none, the reference
     * where its slope is not positive and finite. */
    private static double chordCapacity(List<Point> points,int i) {
        var p=points.get(i);
        int lo=i-1;
        while(lo>=0&&oneLiquid(points.get(lo),points.get(lo+1))&&p.t-points.get(lo).t<CHORD_KELVIN)lo--;
        int hi=i+1;
        while(hi<points.size()&&oneLiquid(points.get(hi-1),points.get(hi))&&points.get(hi).t-p.t<CHORD_KELVIN)hi++;
        var a=lo>=0&&oneLiquid(points.get(lo),points.get(lo+1))?points.get(lo):p;
        var b=hi<points.size()&&oneLiquid(points.get(hi-1),points.get(hi))?points.get(hi):p;
        if(a==b)return Double.NaN;
        double slope=(b.h-a.h)/(b.t-a.t);
        return slope>0&&Double.isFinite(slope)?slope:HEAT_CAPACITY_REFERENCE;
    }
    /** Whether the step from {@code a} to {@code b} is all one liquid, with no latent heat: both of several phases at vapour
     * fraction exactly 0, or one fluid running {@link #continuous} between them. */
    private static boolean oneLiquid(Point a,Point b) {
        return Double.isNaN(a.w)&&Double.isNaN(b.w)?a.vapour==0&&b.vapour==0:continuous(a,b);
    }
    private static final int DIVISIONS=16,MAXIMUM_KNOTS=512;
    /** How closely a phase boundary is bisected, K: the plateau's edge is then out by cp times this, about 4 J/kg against a
     * latent heat of megajoules per kilogram (L-F11 of the 2026-11-13 simplifications; 1e-5 K before, seven more flashes per boundary). */
    static final double BOUNDARY_KELVIN=1e-3;
    /** A sampled point: its vapour coordinate is the vapour mass fraction, or {@code 1 - w} for a point of one fluid of
     * liquid-like weight {@code w} (NaN for a point of more than one phase), so one fluid is never bisected as a phase change;
     * {@code slot} its one fluid slot (-1 for none or an inserted knot) and {@code dense} whether that fluid is
     * {@link FluidThermodynamics.State#dense()}; {@code mu} and {@code k} its liquid's viscosity (Pa·s) and conductivity (W/mK),
     * NaN where the liquid film has no weight (and for an inserted knot); {@code resistance} its liquid film resistance
     * ({@link #liquidResistance(double,double,double)}), 0 until {@link #resisted} gives it and where the liquid film has no
     * weight. */
    private record Point(double t,double h,double vapour,double w,int slot,boolean dense,double mu,double k,double resistance) {}
    /** Whether one fluid runs continuously from {@code a} to {@code b}: both of one fluid, in the same slot or both dense. */
    private static boolean continuous(Point a,Point b) {
        return !Double.isNaN(a.w)&&!Double.isNaN(b.w)&&(a.slot>=0&&a.slot==b.slot||a.dense&&b.dense);
    }
    private static Point point(FluidThermodynamics model,double[] amounts,double pressure,double temperature,Runnable checkpoint) {
        checkpoint.run();
        var state=model.flashTP(temperature,pressure,amounts,checkpoint);
        double w=liquidLikeWeight(state),vapour=Double.isNaN(w)?model.vaporMass(state)/state.mass():1-w;
        // A plateau or a gas point, where the liquid film has no weight, reads no liquid property.
        boolean weighted=liquidWeighted(vapour,w);
        double mu=weighted?liquidViscosity(model,state,w):Double.NaN,k=weighted?liquidConductivity(state,w):Double.NaN;
        return new Point(temperature,state.enthalpy()/state.mass(),vapour,w,state.oneFluidSlot(),state.dense(),mu,k,0);
    }
    /**
     * The thermal conductivity (W/mK) of the liquid a point's liquid film wets the wall with, from its temperature alone (D15),
     * over the slots {@link #liquidViscosity} reads, weighted the same way: water's ({@link #waterConductivity}) for the
     * aqueous liquid, the petroleum rule's ({@link #oilConductivity}) for any other. NaN, the reference, where no liquid is held.
     */
    private static double liquidConductivity(FluidThermodynamics.State state,double w) {
        double t=state.temperature();
        if(!Double.isNaN(w)){int slot=state.oneFluidSlot();return slot<0?Double.NaN:conductivity(state,slot,t);}
        double value=0,volume=0;
        for(int slot:new int[]{FluidThermodynamics.State.LIQUID,FluidThermodynamics.State.AQUEOUS}) {
            double v=state.slotVolume(slot);
            if(v>0){value+=v*conductivity(state,slot,t);volume+=v;}
        }
        return volume>0?value/volume:Double.NaN;
    }
    /**
     * A slot's conductivity at {@code t} K: water's where the slot holds the phase labelled aqueous
     * ({@link FluidThermodynamics.State#slot}: of two liquids the wetter, a single liquid at least half water by moles), and
     * where a slot with no liquid beside it (one dense fluid, in any slot) is at least half water by moles; the oil rule
     * otherwise.
     */
    private static double conductivity(FluidThermodynamics.State state,int slot,double t) {
        int aqueous=state.slot(FluidThermodynamics.AQUEOUS);
        boolean water=aqueous>=0?aqueous==slot:state.waterFraction(slot)>=.5;
        return water?waterConductivity(t):oilConductivity(t);
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
