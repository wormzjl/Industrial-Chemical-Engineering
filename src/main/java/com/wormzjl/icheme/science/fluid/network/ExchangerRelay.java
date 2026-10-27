package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import java.util.Arrays;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * The relay between an exchanger's two sides (batch 2026-10-27-heat-exchanger-design, plan section 5.2; owner Q10: the wall
 * holds no heat, Q16: form C, the coupling fence). Each side lives in its own island and plans its slice at the slice start
 * from the other side as read: it heads for the exchanger's quasi-steady wall, and it gives back its share of the heat in
 * transit, the energy the two sides booked differently while each still saw the other as it was.
 *
 * <p>Pure functions of curves and numbers: the island's worker builds both curves and plans each coupled side once per
 * slice ({@link PassiveStepSolver#couple}); the server reads and books only numbers.
 */
public final class ExchangerRelay {
    private ExchangerRelay(){}
    /** A cell's wall temperature is solved to this, K, and the sweeps stop once no cell moves by more (plan section 5.2). */
    public static final double TOLERANCE=.01;
    /** The most sweeps of the wall solve; a wall stopped short is still a valid wall (energy stays exact, only the transient changes). */
    public static final int MAXIMUM_SWEEPS=200;

    /**
     * The other side as read at the slice start: the state of the node its stream is drawn from ({@code donor}) with the
     * port and connection volume it is drawn through, its mass flow (kg/s, its sign in that side's own forward direction) and
     * its heat side, whose surface, films and bypass it passes the cells with.
     */
    public record Partner(FluidThermodynamics.State donor,PassiveNetwork.PhasePort port,double volume,double massFlow,HeatSide law) {
        public Partner {Objects.requireNonNull(donor);Objects.requireNonNull(port);Objects.requireNonNull(law);if(!Double.isFinite(massFlow)||!(volume>=0))throw new IllegalArgumentException("Invalid partner read");}
    }
    /**
     * One coupled side's slice inputs, read on the server at its dispatch: the other side as read ({@code partner}, null
     * where none is known), whether it counts as {@code stopped} (no island, held, phase-parked: it then moves no heat and
     * the read only bounds the give-back), the heat in transit due as of the slice start ({@code transitDue}, J, the energy
     * the exchanger holds: positive is owed to the streams), the other side's slice length ({@code partnerSeconds}, s, for
     * the share) and the share of this slice the other side spent counting this one stopped ({@code sitOut}, 0 to 1).
     */
    public record Read(@Nullable Partner partner,boolean stopped,double transitDue,double partnerSeconds,double sitOut) {
        public Read {if(!Double.isFinite(transitDue)||!(partnerSeconds>=0)||!Double.isFinite(partnerSeconds)||!(sitOut>=0&&sitOut<=1))throw new IllegalArgumentException("Invalid side read");}
        /** No partner known, nothing in transit: the side moves no heat. */
        public static final Read NONE=new Read(null,true,0,0,0);
    }
    /**
     * A stream as the plan sees it: its curve, the specific enthalpy it arrives at (J/kg), its mass flow (kg/s, the sign in
     * its own side's forward direction), its heat side and whether its forward direction meets the wall's cells in their
     * order ({@code forward}: the planning side's; the other side's runs the other way, counterflow).
     */
    public record Stream(HeatCurve curve,double inlet,double massFlow,HeatSide law,boolean forward) {
        /** The flow through the cells, kg/s: the bypass passes outside them. */
        double through(){return (1-law.bypass())*Math.abs(massFlow);}
        /** Whether it meets the cells in the wall's order. */
        boolean ascending(){return forward==(massFlow>=0);}
        double cell(){return law.area()/law.cells();}
        double temperature(){return curve.temperatureAt(inlet);}
    }
    /** A solved wall, in the planning side's forward order, the sweeps it took and whether it reached the tolerance. */
    public record Wall(double[] temperatures,int sweeps,boolean converged) {}

    /**
     * The exchanger's quasi-steady wall with both streams marched through its cells on their curves (plan section 5.2):
     * in each cell the heat one stream gives up is what the other takes. Each sweep passes the cells in one stream's
     * order and then in the other's; a cell's temperature is the root of its two heats, bracketed by the two streams'
     * temperatures as they enter it, solved to a tenth of {@link #TOLERANCE}. Starts from {@code start} where it fits (the
     * side's last planned wall), else from the mean of the inlets. A start the first sweep moves by less than the tolerance
     * is kept as it is, so a side at its steady state plans the same wall slice after slice and its island can certify.
     */
    public static Wall wall(Stream a,Stream b,@Nullable double[] start) {
        int n=a.law.cells();if(b.law.cells()!=n)throw new IllegalArgumentException("The two sides of a wall need the same cells");
        double[] t=new double[n],ha=new double[n],hb=new double[n];
        double ta=a.temperature(),tb=b.temperature();
        boolean warm=start!=null&&start.length==n;
        if(warm)for(int k=0;k<n;k++)t[k]=Math.clamp(start[k],Math.min(ta,tb),Math.max(ta,tb));
        else Arrays.fill(t,(ta+tb)/2);
        double[] kept=t.clone();
        enter(b,t,hb);
        for(int sweep=1;sweep<=MAXIMUM_SWEEPS;sweep++) {
            double change=Math.max(pass(a,b,t,ha,hb),pass(b,a,t,hb,ha));
            if(change<TOLERANCE)return new Wall(warm&&sweep==1?kept:t,sweep,true);
        }
        return new Wall(t,MAXIMUM_SWEEPS,false);
    }
    /** The enthalpy {@code s} enters each cell with against wall {@code t}. */
    private static void enter(Stream s,double[] t,double[] entering) {
        int n=t.length;double h=s.inlet,through=s.through();
        for(int i=0;i<n;i++){int k=s.ascending()?i:n-1-i;entering[k]=h;h=s.curve.march(h,through,s.cell(),t[k]);}
    }
    /** One pass in {@code s}'s order: each cell solved against {@code o} as it last entered; returns the largest move, K. */
    private static double pass(Stream s,Stream o,double[] t,double[] hs,double[] ho) {
        int n=t.length;double h=s.inlet,through=s.through(),change=0;
        for(int i=0;i<n;i++) {
            int k=s.ascending()?i:n-1-i;hs[k]=h;
            double next=cell(s,o,h,ho[k],t[k]);change=Math.max(change,Math.abs(next-t[k]));t[k]=next;
            h=s.curve.march(h,through,s.cell(),t[k]);
        }
        return change;
    }
    /** The heat (W) both streams take up in one cell at wall temperature {@code wall}: zero at the cell's steady wall. */
    private static double balance(Stream s,Stream o,double hs,double ho,double wall) {
        double ms=s.through(),mo=o.through();
        return ms*(s.curve.march(hs,ms,s.cell(),wall)-hs)+mo*(o.curve.march(ho,mo,o.cell(),wall)-ho);
    }
    /** One cell's steady wall: an Illinois root of {@link #balance}, bracketed by the two entering temperatures, from {@code guess}. */
    private static double cell(Stream s,Stream o,double hs,double ho,double guess) {
        double ts=s.curve.temperatureAt(hs),to=o.curve.temperatureAt(ho);
        double lo=Math.min(ts,to),hi=Math.max(ts,to),tolerance=TOLERANCE/10;
        if(hi-lo<=tolerance)return (lo+hi)/2;
        // The balance rises with the wall: below both streams both are cooled, above both both are heated.
        double flo=balance(s,o,hs,ho,lo),fhi=balance(s,o,hs,ho,hi);
        if(flo>=0)return lo;
        if(fhi<=0)return hi;
        if(guess>lo&&guess<hi) {
            double f=balance(s,o,hs,ho,guess);if(f==0)return guess;
            if(f<0){lo=guess;flo=f;}else{hi=guess;fhi=f;}
        }
        int side=0;
        for(int iteration=0;iteration<64&&hi-lo>tolerance;iteration++) {
            double x=(lo*fhi-hi*flo)/(fhi-flo);
            if(!(x>lo&&x<hi))x=(lo+hi)/2;
            double f=balance(s,o,hs,ho,x);
            if(f==0)return x;
            if(f<0){lo=x;flo=f;if(side==-1)fhi/=2;side=-1;}
            else{hi=x;fhi=f;if(side==1)flo/=2;side=1;}
        }
        return (lo*fhi-hi*flo)/(fhi-flo);
    }

    /** The specific enthalpy {@code s} leaves its cells with against {@code wall} (in the wall's order), J/kg. */
    public static double outlet(Stream s,double[] wall) {
        int n=wall.length;double h=s.inlet,through=s.through();
        for(int i=0;i<n;i++){int k=s.ascending()?i:n-1-i;h=s.curve.march(h,through,s.cell(),wall[k]);}
        return h;
    }
    /**
     * The heat capacity rate (W/K) {@code s} passes the cells with against {@code wall}: its heat over its temperature
     * change, or where it barely changes temperature its curve's slope at the inlet, unbounded on a plateau (a condensing or
     * boiling stream). Zero without flow through the cells.
     */
    public static double capacityRate(Stream s,double[] wall) {
        double through=s.through();if(through==0)return 0;
        double out=outlet(s,wall),change=s.curve.temperatureAt(out)-s.temperature();
        if(Math.abs(change)>1e-3)return through*(out-s.inlet)/change;
        double slope=s.curve.temperatureSlope(s.inlet);
        return slope>0?through/slope:Double.POSITIVE_INFINITY;
    }
    /**
     * The side's share of the heat in transit (plan section 5.2): its slice heat capacity against the two,
     * W_own Δt_own / (W_own Δt_own + W_other Δt_other). A side without flow takes none; a stopped or still other side
     * leaves it all; an unbounded (condensing or boiling) side takes all of it at its constant temperature, half each when
     * both are.
     */
    static double share(double own,double seconds,double other,double otherSeconds) {
        if(!(own>0))return 0;
        if(!(other>0))return 1;
        if(own==Double.POSITIVE_INFINITY)return other==Double.POSITIVE_INFINITY?.5:1;
        if(other==Double.POSITIVE_INFINITY)return 0;
        double a=own*seconds,b=other*otherSeconds;
        return a/(a+b);
    }

    /**
     * {@code own} planned for a slice of {@code seconds} s (plan section 5.2): its wall and give-back fixed for the slice and
     * its cells active but for the sit-out. {@code stream} is the side's own stream at the slice start (its flow 0 where none
     * is known), {@code other} the other side's as read (null where none is known). With both streams flowing the wall is
     * the quasi-steady wall. With the other stopped or still the wall, which holds no heat, stands at the side's own stream:
     * its cells move nothing for the slice, and it keeps its last wall to start from when the other moves again. With the
     * side's own flow unknown or still the wall stands at the other's inlet. The give-back is the side's share of
     * {@code read}'s transit due, an enthalpy shift on its whole flow, scaled down so that its outlet at the slice start
     * stays between the two inlet temperatures (the rest stays in transit); none without flow or without a read of the
     * other side.
     */
    public static HeatSide plan(HeatSide own,Stream stream,@Nullable Stream other,Read read,double seconds) {
        int n=own.cells();double[] wall=new double[n];double active=1-read.sitOut();
        boolean moving=other!=null&&!read.stopped()&&other.through()>0;
        if(!moving){wall=own.wall();active=0;}
        else if(stream.through()==0)Arrays.fill(wall,other.temperature());
        else {var solved=wall(stream,other,own.wall());wall=solved.temperatures();}
        var planned=own.withInputs(wall,own.bypass(),0,active);
        double mass=Math.abs(stream.massFlow());
        if(other==null||mass==0||read.transitDue()==0)return planned;
        double share=share(capacityRate(stream,wall),seconds,moving?capacityRate(other,wall):0,read.partnerSeconds());
        if(share==0)return planned;
        double shift=share*read.transitDue()/(mass*seconds);
        double mixed=stream.inlet+planned.heat(stream.curve,stream.inlet,stream.massFlow)/mass;
        double bound=stream.curve.enthalpyAt(other.temperature()),lo=Math.min(stream.inlet,bound),hi=Math.max(stream.inlet,bound);
        double allowed=Math.clamp(mixed+shift,lo,hi),scale=Math.clamp((allowed-mixed)/shift,0,1);
        return own.withInputs(wall,own.bypass(),scale*shift,active);
    }
}
