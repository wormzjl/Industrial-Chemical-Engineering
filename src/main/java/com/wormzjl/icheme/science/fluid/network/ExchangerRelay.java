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
    /** A controlled side's bypass and wall are solved in turn until the bypass moves by less than this (W4, plan section 6)... */
    public static final double BYPASS_TOLERANCE=1e-6;
    /** ...or for at most this many rounds; the next slice goes on from where they stop. */
    public static final int MAXIMUM_ROUNDS=8;

    /**
     * The other side as read at the slice start: the state of the node its stream is drawn from ({@code donor}) with the
     * port and connection volume it is drawn through, its mass flow (kg/s, its sign in that side's own forward direction) and
     * its heat side, whose surface, films and bypass it passes the cells with. A kettle's pool (W7) is read as its vessel's
     * contents alone ({@link #pool(PassiveNetwork.Reservoir)}): no stream, no heat side.
     */
    public record Partner(FluidThermodynamics.State donor,PassiveNetwork.PhasePort port,double volume,double massFlow,@Nullable HeatSide law,@Nullable Bath bath) {
        public Partner {Objects.requireNonNull(donor);Objects.requireNonNull(port);if(!Double.isFinite(massFlow)||!(volume>=0)||(law==null)==(bath==null)||bath!=null&&massFlow!=0)throw new IllegalArgumentException("Invalid partner read");}
        public Partner(FluidThermodynamics.State donor,PassiveNetwork.PhasePort port,double volume,double massFlow,HeatSide law){this(donor,port,volume,massFlow,law,null);}
        /** A kettle's pool as its tube side reads it: vessel {@code node}'s contents at its temperature and film ({@link Pool#film}),
         * or null where it holds no fluid (it then takes no heat). */
        public static @Nullable Partner pool(PassiveNetwork.Reservoir node) {
            if(PassiveStepSolver.holdsNoFluid(node))return null;
            var bath=new Bath(node.state().temperature(),node.inventory().pool().film(node.state(),node.inventory().geometry()));
            return new Partner(node.state(),PassiveNetwork.PhasePort.BULK,0,0,null,bath);
        }
        /** Whether this is a kettle's pool (W7). */
        public boolean pool(){return bath!=null;}
    }
    /**
     * A kettle's pool as the other side of its tube bundle (W7, plan section 8): isothermal at {@code temperature} K, its film
     * {@code film} W/m²K on the tubes ({@link Pool#film}), its capacity unbounded.
     */
    /**
     * The real valve a side's control works once its exchanger's lines are complete (batch 2026-10-27-heat-exchanger-design,
     * W8, plan section 7): the control valve whose node is {@code valve} (its registration), regulating the mass flow of the
     * connection it drives. A bypass valve ({@code feed} false) takes the share of the side's stream its controller plans
     * for the internal bypass, which then stays shut; a feed valve ({@code feed} true) holds the side's feed at {@code ratio}
     * times the other side's flow as read.
     */
    public record Operation(long valve,boolean feed,double ratio) {
        public Operation {if(feed&&!(ratio>0&&Double.isFinite(ratio))||!feed&&ratio!=0)throw new IllegalArgumentException("Invalid exchanger operation");}
        public static Operation bypass(long valve){return new Operation(valve,false,0);}
        public static Operation feed(long valve,double ratio){return new Operation(valve,true,ratio);}
    }
    public record Bath(double temperature,double film) {
        public Bath {if(!(temperature>0)||!(film>0)||!Double.isFinite(temperature+film))throw new IllegalArgumentException("Invalid bath");}
    }
    /**
     * One coupled side's slice inputs, read on the server at its dispatch: the other side as read ({@code partner}, null
     * where none is known), whether it counts as {@code stopped} (no island, held, phase-parked: it then moves no heat and
     * the read only bounds the give-back), the heat in transit due as of the slice start ({@code transitDue}, J, the energy
     * the exchanger holds: positive is owed to the streams), the other side's slice length ({@code partnerSeconds}, s, for
     * the share) and the share of this slice the other side spent counting this one stopped ({@code sitOut}, 0 to 1). With
     * the exchanger's lines complete (W8, plan section 7), {@code operation} names the valve the side's control works. A fired
     * heater's convection coil reads only {@code heatRate}: its radiant coil's heat (W) as of the slice start (F1).
     */
    public record Read(@Nullable Partner partner,boolean stopped,double transitDue,double partnerSeconds,double sitOut,@Nullable Operation operation,double heatRate) {
        public Read {if(!Double.isFinite(transitDue)||!(partnerSeconds>=0)||!Double.isFinite(partnerSeconds)||!(sitOut>=0&&sitOut<=1)||!Double.isFinite(heatRate))throw new IllegalArgumentException("Invalid side read");}
        public Read(@Nullable Partner partner,boolean stopped,double transitDue,double partnerSeconds,double sitOut,@Nullable Operation operation){this(partner,stopped,transitDue,partnerSeconds,sitOut,operation,0);}
        public Read(@Nullable Partner partner,boolean stopped,double transitDue,double partnerSeconds,double sitOut){this(partner,stopped,transitDue,partnerSeconds,sitOut,null);}
        /** A convection coil's read of its radiant coil in another island (the owner's test of 2026-10-28, F1): the radiant
         * coil's heat (W) as its island committed it as of the slice start; nothing else. */
        public static Read radiant(double heatRate){return new Read(null,false,0,0,0,null,heatRate);}
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
        /** The same stream through {@code side}'s cells (another bypass). */
        Stream through(HeatSide side){return new Stream(curve,inlet,massFlow,side,forward);}
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
        return root(s.curve.temperatureAt(hs),o.curve.temperatureAt(ho),guess,wall->balance(s,o,hs,ho,wall));
    }
    /**
     * The wall temperature between {@code a} and {@code b} (K) at which {@code balance}, rising with the wall, is zero, to a
     * tenth of {@link #TOLERANCE}: an Illinois root from {@code guess}, an end where the balance does not change sign.
     */
    private static double root(double a,double b,double guess,java.util.function.DoubleUnaryOperator balance) {
        double lo=Math.min(a,b),hi=Math.max(a,b),tolerance=TOLERANCE/10;
        if(hi-lo<=tolerance)return (lo+hi)/2;
        // The balance rises with the wall: below both sides both are cooled, above both both are heated.
        double flo=balance.applyAsDouble(lo),fhi=balance.applyAsDouble(hi);
        if(flo>=0)return lo;
        if(fhi<=0)return hi;
        if(guess>lo&&guess<hi) {
            double f=balance.applyAsDouble(guess);if(f==0)return guess;
            if(f<0){lo=guess;flo=f;}else{hi=guess;fhi=f;}
        }
        int side=0;
        for(int iteration=0;iteration<64&&hi-lo>tolerance;iteration++) {
            double x=(lo*fhi-hi*flo)/(fhi-flo);
            if(!(x>lo&&x<hi))x=(lo+hi)/2;
            double f=balance.applyAsDouble(x);
            if(f==0)return x;
            if(f<0){lo=x;flo=f;if(side==-1)fhi/=2;side=-1;}
            else{hi=x;fhi=f;if(side==1)flo/=2;side=1;}
        }
        return (lo*fhi-hi*flo)/(fhi-flo);
    }

    /**
     * A tube bundle's wall in a kettle's pool (W7): {@code s} marched through its cells in its own order, each cell's wall
     * where the heat the stream gives up is what the pool takes through its film, h_pool A_cell (T_wall - T_pool). One pass:
     * the pool is the same at every cell. In the wall's order, as {@link #wall} returns it.
     */
    public static double[] bathWall(Stream s,Bath bath) {
        int n=s.law.cells();double[] t=new double[n];double h=s.inlet,through=s.through(),cell=s.cell();
        for(int i=0;i<n;i++) {
            int k=s.ascending()?i:n-1-i;double entering=h;
            t[k]=root(s.curve.temperatureAt(entering),bath.temperature(),bath.temperature(),
                    wall->through*(s.curve.march(entering,through,cell,wall)-entering)+bath.film()*cell*(wall-bath.temperature()));
            h=s.curve.march(h,through,cell,t[k]);
        }
        return t;
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
     * side's own flow unknown or still the wall stands at the other's inlet. A side's controller (W4, plan section 6) sets its
     * bypass against the wall: the two are solved in turn, the wall at the bypass and the bypass on the wall
     * ({@link HeatControl#setting}, the give-back not counted), until the bypass settles, so the quasi-steady exchanger as
     * planned meets the target at the other side as read. With the other side not moving, the bypass stands. The give-back
     * is the side's share of {@code read}'s transit due, an enthalpy shift on its whole flow, scaled down so that its outlet
     * at the slice start stays between the two inlet temperatures (the rest stays in transit); none without flow or without
     * a read of the other side.
     */
    public static HeatSide plan(HeatSide own,Stream stream,@Nullable Stream other,Read read,double seconds) {
        boolean moving=other!=null&&!read.stopped()&&other.through()>0;
        var planned=controlled(own,stream,moving?(at,last)->wall(at,other,last).temperatures():null,moving?other.temperature():0,read);
        double[] wall=planned.wall();double bypass=planned.bypass(),mass=Math.abs(stream.massFlow());var limit=planned.limit();var at=stream.through(planned);
        if(other==null||mass==0||read.transitDue()==0)return planned;
        double share=share(capacityRate(at,wall),seconds,moving?capacityRate(other,wall):0,read.partnerSeconds());
        if(share==0)return planned;
        double shift=share*read.transitDue()/(mass*seconds);
        double mixed=stream.inlet+planned.heat(stream.curve,stream.inlet,stream.massFlow)/mass;
        double bound=stream.curve.enthalpyAt(other.temperature()),lo=Math.min(stream.inlet,bound),hi=Math.max(stream.inlet,bound);
        double allowed=Math.clamp(mixed+shift,lo,hi),scale=Math.clamp((allowed-mixed)/shift,0,1);
        return own.withInputs(wall,bypass,scale*shift,planned.active(),limit);
    }
    /**
     * A kettle's tube side planned for a slice (W7, plan section 8): as {@link #plan} against a pool at {@code bath} (null
     * where none is known: the pool holds nothing), the wall from {@link #bathWall}. It takes none of the heat in transit: the
     * pool's capacity is unbounded, so the pool takes it all ({@link #planPool}).
     */
    public static HeatSide plan(HeatSide own,Stream stream,@Nullable Bath bath,Read read) {
        boolean moving=bath!=null&&!read.stopped();
        return controlled(own,stream,moving?(at,last)->bathWall(at,bath):null,moving?bath.temperature():0,read);
    }
    /**
     * {@code own}'s wall, bypass and active share for a slice: with {@code wallAt} (null where the other side does not move,
     * so the cells move nothing and keep their last wall) the wall at a bypass, from the last wall, and the bypass on the wall
     * by the side's controller ({@link HeatControl#setting}, the give-back not counted), in turn until the bypass settles; a
     * flow that passes no cells gets a flat wall at {@code still} K. Its give-back is 0.
     */
    private static HeatSide controlled(HeatSide own,Stream stream,@Nullable java.util.function.BiFunction<Stream,double[],double[]> wallAt,double still,Read read) {
        int n=own.cells();double[] wall=own.wall();double active=1-read.sitOut(),bypass=own.bypass(),mass=Math.abs(stream.massFlow());
        var limit=HeatControl.Limit.NONE;
        if(wallAt==null)active=0;
        else for(int round=1;;round++) {
            var at=stream.through(own.withInputs(wall,bypass,0,1));
            if(at.through()==0){wall=new double[n];Arrays.fill(wall,still);}
            else wall=wallAt.apply(at,wall);
            if(own.control()==null||round>MAXIMUM_ROUNDS)break;
            double[] fixed=wall;
            var setting=own.control().setting(u->own.withInputs(fixed,1-u,0,1).heat(stream.curve,stream.inlet,stream.massFlow),stream.curve,stream.inlet,mass,1-bypass);
            double next=1-setting.effort();limit=setting.limit();
            boolean settled=Math.abs(next-bypass)<BYPASS_TOLERANCE;bypass=next;
            if(settled)break;
        }
        return own.withInputs(wall,bypass,0,active,limit);
    }
    /**
     * A kettle's pool planned for a slice of {@code seconds} s (W7, plan section 8): the wall its tube side's stream as read
     * ({@code tube}, null where none is known) makes against the pool at {@code bath} ({@link #bathWall}), active but for the
     * sit-out, and the give-back: all the heat in transit due, over the slice (plan section 5.2's share: the pool's capacity is
     * unbounded). With the tubes stopped or still the wall stands at the pool and moves nothing; the transit still returns.
     */
    public static Pool planPool(Pool own,Bath bath,@Nullable Stream tube,Read read,double seconds) {
        boolean moving=tube!=null&&!read.stopped()&&tube.through()>0;
        if(moving&&tube.law.cells()!=own.cells())throw new IllegalArgumentException("A pool and its tubes need the same cells");
        double[] wall;
        if(moving)wall=bathWall(tube,bath);
        else{wall=new double[own.cells()];Arrays.fill(wall,bath.temperature());}
        return own.withInputs(wall,seconds>0?read.transitDue()/seconds:0,moving?1-read.sitOut():0);
    }
}
