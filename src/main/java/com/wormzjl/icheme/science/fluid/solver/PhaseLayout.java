package com.wormzjl.icheme.science.fluid.solver;

import com.wormzjl.icheme.science.math.DeterministicMath;

import com.wormzjl.icheme.science.fluid.diagnostics.SolverDiagnostics;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.thermo.PhaseSupport;
import com.wormzjl.icheme.science.thermo.TraceTruncationPolicy;
import java.util.*;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;

/** Fixed phase active set for one Newton pass. Evaluates properties directly; never runs a nested flash. */
public final class PhaseLayout {
    private final FluidThermodynamics model;
    private final int[] components,liquidIndex,vaporIndex;
    /** Where each present component's <em>total</em> amount lives: {@link #liquidIndex} when this
     * layout carries a liquid amount for it, {@link #vaporIndex} otherwise. For a two-phase component
     * the second slot is the phase split {@code ln(v/l)}; for a one-phase component there is no
     * second slot and no equilibrium row. */
    private final int[] amountIndex;
    private final PhaseSupport[] support;
    /** The conserved components a junction writes mass fractions for, water last: the graph fixes
     * it, so it is built with the layout instead of boxed into a list on every evaluation. */
    private final int[] junctionBasis;
    private final int waterLiquidIndex,waterVaporIndex,temperatureIndex,pressureIndex,partialPressureIndex,size;
    private final double amountScale,energyScale,steamSeed;
    private final double[] componentScales,differenceScales;
    private final boolean liquidActive,vaporActive;
    private final int solidIndex;
    private final SolidInventory solidBasis;
    private final double[] solidScale,solidReference;

    public PhaseLayout(FluidThermodynamics model,FluidThermodynamics.State seed) {
        this(model,seed,null);
    }
    /** A shared component mask allows a component absent in this vessel to arrive through connected pipes. */
    public PhaseLayout(FluidThermodynamics model,FluidThermodynamics.State seed,boolean[] componentMask) {
        this(model,seed,componentMask,null);
    }
    /** A trial seed may contain artificial entry traces; do not impose trace-relative accuracy
     * on a component that is actually absent before this step. Canonical traces remain resolved. */
    public PhaseLayout(FluidThermodynamics model,FluidThermodynamics.State seed,boolean[] componentMask,double[] conservedReference) {
        this(model,seed,componentMask,conservedReference,support(seed,componentMask,TraceTruncationPolicy.OFF,null));
    }
    /**
     * {@code support} must be the array {@link #support} derives from this seed, this mask and the
     * caller's policy - the caller derives it first because the solver keys its workspaces and its
     * active-set cycle guard on it, and it must be the same array the layout was built from.
     */
    public PhaseLayout(FluidThermodynamics model,FluidThermodynamics.State seed,boolean[] componentMask,double[] conservedReference,
                       PhaseSupport[] support) {
        this(model,seed,componentMask,conservedReference,support,seed.solidMoments().mass()>0);
    }
    public PhaseLayout(FluidThermodynamics model,FluidThermodynamics.State seed,boolean[] componentMask,double[] conservedReference,
                       PhaseSupport[] support,boolean solidSupport) {
        this.model=Objects.requireNonNull(model);
        double[] l=seed.liquidView(),v=seed.vaporView();var present=new ArrayList<Integer>();
        if(componentMask!=null&&componentMask.length!=l.length)throw new IllegalArgumentException("Component mask mismatch");
        if(support.length!=l.length)throw new IllegalArgumentException("Phase support basis mismatch");
        this.support=support.clone();
        // A masked component the support calls ABSENT is an omitted trace, not a member of this
        // layout: it carries no unknown and no row, and the mask keeps it available to the phase
        // appearance pass. That is how a node with no hydrocarbon phase at all - a zero-holdup
        // junction mixing pure water - can sit in an island whose mask names a hydrocarbon.
        for(int i=0;i<l.length;i++)if((l[i]+v[i]>0||componentMask!=null&&componentMask[i])&&support[i]!=PhaseSupport.ABSENT)present.add(i);
        components=present.stream().mapToInt(Integer::intValue).toArray();
        liquidActive=seed.liquidVolume()>0;vaporActive=sum(v)>0;
        if(components.length>0&&!liquidActive&&!vaporActive)throw new IllegalArgumentException("A hydrocarbon-phase appearance pass is required");
        for(int i:components)if(this.support[i]==PhaseSupport.ABSENT||this.support[i].liquid()&&!liquidActive||this.support[i].vapor()&&!vaporActive)
            throw new IllegalArgumentException("Phase support does not match the seed's active phases at component "+i);
        boolean water=seed.waterLiquid()>0||seed.waterVapor()>0;
        junctionBasis=new int[components.length+(water?1:0)];
        System.arraycopy(components,0,junctionBasis,0,components.length);
        if(water)junctionBasis[components.length]=l.length;
        // The last of this basis has no mixing row: the closure row alone states it. That row's sum stands near 1, where the
        // Newton's difference step of a share under 1.1e-10 rounds away and leaves its column empty, so the largest share takes
        // that place and every trace keeps a row of its own (a dry outlet line's old nitrogen, batch 2026-11-02-valve-hold).
        int bulk=junctionBasis.length-1;double most=-1;
        for(int index=0;index<junctionBasis.length;index++){int held=junctionBasis[index];double amount=held==l.length?seed.waterLiquid()+seed.waterVapor():l[held]+v[held];if(amount>=most){most=amount;bulk=index;}}
        if(bulk>=0){int held=junctionBasis[bulk];junctionBasis[bulk]=junctionBasis[junctionBasis.length-1];junctionBasis[junctionBasis.length-1]=held;}
        liquidIndex=new int[l.length];vaporIndex=new int[l.length];amountIndex=new int[l.length];
        Arrays.fill(liquidIndex,-1);Arrays.fill(vaporIndex,-1);Arrays.fill(amountIndex,-1);
        int offset=0;
        for(int i:components) {
            if(this.support[i].liquid())liquidIndex[i]=offset++;
            if(this.support[i].vapor())vaporIndex[i]=offset++;
            amountIndex[i]=liquidIndex[i]>=0?liquidIndex[i]:vaporIndex[i];
        }
        waterLiquidIndex=seed.waterLiquid()>0?offset++:-1;waterVaporIndex=seed.waterVapor()>0?offset++:-1;
        temperatureIndex=offset++;pressureIndex=offset++;
        partialPressureIndex=vaporActive&&waterVaporIndex>=0?offset++:-1;
        solidIndex=solidSupport?offset:-1;if(solidSupport)offset+=3;size=offset;
        solidBasis=seed.solids();solidReference=seed.solidMoments().values();
        solidScale=new double[]{Math.max(1e-12,seed.mass()),Math.max(1e-12,seed.volume()),Math.max(1e-9,seed.mass()*1000)};
        amountScale=Math.max(1e-12,sum(l)+sum(v)+seed.waterLiquid()+seed.waterVapor());
        energyScale=Math.max(1,Math.max(Math.abs(seed.internalEnergy()),amountScale*FluidThermodynamics.R*seed.temperature()));
        if(conservedReference!=null&&conservedReference.length!=l.length+1)throw new IllegalArgumentException("Balance reference basis mismatch");
        boolean resolveTraces=seed.vaporVolume()/seed.volume()<.01;
        componentScales=new double[l.length+1];for(int c=0;c<l.length;c++)componentScales[c]=!resolveTraces||conservedReference!=null&&conservedReference[c]==0?amountScale:Math.max(1e-30,l[c]+v[c]);
        componentScales[l.length]=!resolveTraces||conservedReference!=null&&conservedReference[l.length]==0?amountScale:Math.max(1e-30,seed.waterLiquid()+seed.waterVapor());
        differenceScales=encode(seed,l,v);
        if(solidIndex>=0)for(int i=0;i<3;i++)differenceScales[solidIndex+i]=Math.max(1e-6,differenceScales[solidIndex+i]);
        if(!resolveTraces)for(int local=0;local<size;local++)if(totalAmountVariable(local))differenceScales[local]=Math.max(1e-4,differenceScales[local]);
        if(conservedReference!=null) {
            for(int c:components)if(conservedReference[c]==0){int at=amountIndex[c];differenceScales[at]=Math.max(1e-4,differenceScales[at]);}
            if(conservedReference[l.length]==0){if(waterLiquidIndex>=0)differenceScales[waterLiquidIndex]=Math.max(1e-4,differenceScales[waterLiquidIndex]);if(waterVaporIndex>=0)differenceScales[waterVaporIndex]=Math.max(1e-4,differenceScales[waterVaporIndex]);}
        }
        // The steam beside its liquid water, which the saturation row reads through a logarithm: the floors above are capped
        // at LOG_READ_SPAN of its own seed amount.
        steamSeed=waterVaporIndex>=0?seed.waterVapor()/amountScale:0;
        if(waterLiquidIndex>=0&&waterVaporIndex>=0)differenceScales[waterVaporIndex]=logReadFloor(differenceScales[waterVaporIndex]);
    }
    /**
     * The span by which the bulk difference floor of an amount may exceed the amount where a row reads it through a
     * logarithm: 1e4, so its step (the Newton's 1e-6 of the scale) stays within 1 % of the amount and the logarithm's
     * secant within 0.5 % of its tangent. The bulk floor of a gas-rich vessel (1e-4 of its amount) is kept wherever such
     * an amount is at least 1e-8 of its vessel; the stand-in of ice in a cold gas holds steam of about 1e-5 Pa at 150 K,
     * 1e-11 of its vessel, where the floor's step was 8 times the steam and the Newton on the saturation row alternated.
     */
    public static final double LOG_READ_SPAN=1e4;
    private double logReadFloor(double floored){return Math.min(floored,Math.max(steamSeed,LOG_READ_SPAN*steamSeed));}
    /** The local column of the free water's steam, -1 where the layout carries none. */
    public int waterVaporVariable(){return waterVaporIndex;}
    /**
     * {@link #differenceStepScale} of the steam column where a row reads the steam through a logarithm, with the floor
     * capped at {@link #LOG_READ_SPAN} of the seed's steam: this layout's saturation row (the column carries the cap
     * already), and a vessel refresh's ice row (WP4) beside a layout without liquid water.
     */
    public double steamLogReadScale(double value){return traceDifferenceScale(Math.max(Math.abs(value),logReadFloor(differenceScales[waterVaporIndex])));}
    public int size(){return size;}
    /** The local column of the pressure unknown, {@code ln(P / 1e5)}; every layout has one. */
    public int pressureVariable(){return pressureIndex;}
    public int componentBalanceCount(){return components.length+(waterLiquidIndex>=0||waterVaporIndex>=0?1:0);}
    /** Whether this layout carries free liquid water, and water vapour, as unknowns (the free water's phases). */
    public boolean carriesWaterLiquid(){return waterLiquidIndex>=0;}
    public boolean carriesWaterVapor(){return waterVaporIndex>=0;}
    /** The water balance's row among {@link #balanceRows}' (from their offset), or -1 where the layout carries no water. */
    public int waterBalanceRow(){return waterLiquidIndex>=0||waterVaporIndex>=0?components.length:-1;}
    /** The amount a component's balance row is stated over ({@link #balanceRows}), mol; water's at the basis's last index. */
    public double componentScale(int component){return componentScales[component];}
    /** This layout's frozen per-component support, in the conserved hydrocarbon basis. */
    public PhaseSupport support(int component){return support[component];}
    /** Components whose omitted phase this layout holds at exactly zero, for the diagnostics counters. */
    public int singlePhaseComponentCount() {
        if(!liquidActive||!vaporActive)return 0;
        int count=0;for(int i:components)if(support[i].singlePhase())count++;
        return count;
    }
    /**
     * Whether this local column is one of the three aggregate solid moments (mass, volume, heat
     * capacity). They are nonnegative like every other amount unknown, but unlike a component
     * amount they are moments of a reconstructed population rather than a conserved species, and a
     * chain of nodes downstream of a solid source carries them at 1e-30 to 1e-70 of their own
     * scale - numerical dust the step limiter must not treat as a live boundary.
     */
    public boolean solidVariable(int local){return solidIndex>=0&&local>=solidIndex&&local<solidIndex+3;}
    /** Bulk component transport is independent of phase split, T and P at fixed total amounts and edge mass flow. */
    public boolean totalAmountVariable(int local) {
        if(solidVariable(local))return true;
        if(local==waterLiquidIndex||local==waterVaporIndex)return true;
        for(int component:components)if(local==amountIndex[component])return true;
        return false;
    }
    public double amountScale(){return amountScale;}
    public double energyScale(){return energyScale;}
    /** Resolve trace amounts relative to their own scale rather than the bulk water inventory. This is also the floor
     * {@code PassiveStepSolver} derives its trace clamp from; the finite-difference step itself is
     * {@link #differenceStepScale}. */
    public double differenceScale(int local,double value){return Math.max(Math.abs(value),totalAmountVariable(local)?Math.max(TRACE_DIFFERENCE_GUARD,Math.abs(differenceScales[local])):1);}
    /** The difference scale an amount takes when its value and its seed are both zero. */
    public static final double TRACE_DIFFERENCE_GUARD=1e-30;
    /**
     * The seed's own difference scale of an amount unknown (its encoded seed value, or the floor the layout put under it),
     * without the {@link #TRACE_DIFFERENCE_GUARD}; see {@link #differenceStepScale}.
     */
    public double seedDifferenceScale(int local){return Math.abs(differenceScales[local]);}
    /**
     * The scale of the finite-difference step of this column: {@link #differenceScale}, except that an amount whose value
     * and seed both lie below {@link #TRACE_DIFFERENCE_GUARD} is differenced relative to its own size, the guard applying
     * only where both are zero. Applied to every such amount, a trace of 1e-36 of its node's amount was differenced with
     * a step as large as itself, and the equilibrium rows, which read a trace phase's amounts through logarithms and
     * ratios, got a secant 20 to 35 % below the tangent: the Newton contracted by only 0.06 to 0.5 per iteration and
     * ended a little above the tolerance at every step size. Bitwise wherever the value or the seed is at least the
     * guard. Not reached: a trace near the bottom of the double range (about 1e-300 of its node), whose logarithmic rows
     * overflow the factorization; that end needs a decision on traces, not a difference step.
     */
    public double differenceStepScale(int local,double value) {
        if(!totalAmountVariable(local))return differenceScale(local,value);
        return traceDifferenceScale(Math.max(Math.abs(value),Math.abs(differenceScales[local])));
    }
    /** The difference scale of an amount whose own size (the larger of its value and its seed) is {@code own}: {@code own}
     * itself, and {@link #TRACE_DIFFERENCE_GUARD} at zero; for every {@code own} at or above the guard this is exactly
     * {@code max(guard, own)}, the scale it always was. */
    public static double traceDifferenceScale(double own){return own>0?own:TRACE_DIFFERENCE_GUARD;}
    public double temperature(double[] variables,int offset){return 350*DeterministicMath.exp(variables[offset+temperatureIndex]);}
    public double[] encode(FluidThermodynamics.State state) {
        return encode(state,state.liquidView(),state.vaporView());
    }
    /**
     * {@link java.util.stream.DoubleStream#sum()} without the stream: the same Kahan compensation,
     * accumulated in the same order, so replacing a stream sum with this cannot move a result.
     * Every summation here used to be a stream, and the residual evaluates several per node.
     */
    public static double sum(double[] values) {
        double high=0,low=0,simple=0;
        for(double value:values) {
            double adjusted=value-low,next=high+adjusted;
            low=(next-high)-adjusted;high=next;simple+=value;
        }
        double total=high-low;
        return Double.isNaN(total)&&Double.isInfinite(simple)?simple:total;
    }
    private double[] encode(FluidThermodynamics.State state,double[] l,double[] v) {
        double[] x=new double[size],fl=null,fv=null;double phaseAmountRatio=0;
        for(int i:components){
            if(support[i]==PhaseSupport.BOTH) {
                x[liquidIndex[i]]=(l[i]+v[i])/amountScale;
                if(l[i]>0&&v[i]>0)x[vaporIndex[i]]=DeterministicMath.log(v[i])-DeterministicMath.log(l[i]);
                else {
                    if(fl==null) {
                        phaseAmountRatio=DeterministicMath.log(sum(v)/sum(l));
                        fl=state.liquidProperties().logFugacityView();fv=state.vaporProperties().logFugacityView();
                    }
                    // The equilibrium split this component would reach at the seed's fugacity
                    // coefficients: ln(v_i/l_i) = ln(n_V/n_L) + ln phi_i^L - ln phi_i^V + ln(P/Pc).
                    // It is the seed for a phase that has just appeared, and for a trace component
                    // the active set has just reactivated, which is the same situation.
                    x[vaporIndex[i]]=phaseAmountRatio+fl[i]-fv[i]+DeterministicMath.log(state.pressure()/state.hydrocarbonPartialPressure());
                }
            }
            // One retained phase, whether because the node carries only that phase or because the
            // frozen support omitted the other one. The unknown is the component total either way,
            // so an omitted trace is folded into the phase that is kept and the material closes.
            else x[amountIndex[i]]=(l[i]+v[i])/amountScale;
        }
        if(waterLiquidIndex>=0)x[waterLiquidIndex]=state.waterLiquid()/amountScale;
        if(waterVaporIndex>=0)x[waterVaporIndex]=state.waterVapor()/amountScale;
        x[temperatureIndex]=DeterministicMath.log(state.temperature()/350);x[pressureIndex]=DeterministicMath.log(state.pressure()/1e5);
        if(partialPressureIndex>=0)x[partialPressureIndex]=DeterministicMath.log(state.hydrocarbonPartialPressure()/1e5);
        if(solidIndex>=0){var moments=state.solidMoments().values();for(int i=0;i<3;i++)x[solidIndex+i]=moments[i]/solidScale[i];}
        return x;
    }
    public FluidThermodynamics.State decode(double[] variables,int offset) {
        return decode(variables,offset,null);
    }
    /** {@code prepared} carries this node's temperature-only properties; it must be for the exact
     * temperature the variables decode to, and {@code null} evaluates them here as before. */
    public FluidThermodynamics.State decode(double[] variables,int offset,FluidThermodynamics.Prepared prepared) {
        double[] l=new double[liquidIndex.length],v=new double[l.length];
        for(int i:components){
            if(support[i]==PhaseSupport.BOTH) {
                double amount=amountScale*variables[offset+liquidIndex[i]],ratio=variables[offset+vaporIndex[i]];
                if(ratio>=0){l[i]=amount/(1+DeterministicMath.exp(ratio));v[i]=amount-l[i];}
                else{v[i]=amount/(1+DeterministicMath.exp(-ratio));l[i]=amount-v[i];}
            }
            // The omitted phase is written as exactly zero, never as a small number, so every
            // component total, every transport split and every conservation audit is exact.
            else if(liquidIndex[i]>=0)l[i]=amountScale*variables[offset+liquidIndex[i]];
            else v[i]=amountScale*variables[offset+vaporIndex[i]];
        }
        double wl=waterLiquidIndex<0?0:amountScale*variables[offset+waterLiquidIndex];
        double wv=waterVaporIndex<0?0:amountScale*variables[offset+waterVaporIndex];
        double t=350*DeterministicMath.exp(variables[offset+temperatureIndex]),p=1e5*DeterministicMath.exp(variables[offset+pressureIndex]);
        double pc=partialPressureIndex>=0?1e5*DeterministicMath.exp(variables[offset+partialPressureIndex]):vaporActive?p:0;
        var state=model.adoptingState(t,p,l,v,wl,wv,pc,prepared);
        if(solidIndex<0)return state;
        // The three moments are hard-nonnegative in SolidInventory.Moments. Enforce that by
        // projection rather than by refusing the trial: a Newton candidate that overshoots one of
        // them by dust is a point the line search must be allowed to evaluate and reject on its
        // residual, not a domain failure that kills every backtrack. Owned state keeps the
        // invariant, because an accepted point never has the projection active - the
        // solidMomentProjectionsAtAcceptedPoints counter is what verifies that.
        double mass=variables[offset+solidIndex]*solidScale[0],volume=variables[offset+solidIndex+1]*solidScale[1],
                capacity=variables[offset+solidIndex+2]*solidScale[2];
        if(mass<0||volume<0||capacity<0)SolverDiagnostics.count(SolverDiagnostics.solidMomentProjections);
        return state.withSolidState(solidBasis,new SolidInventory.Moments(Math.max(0,mass),Math.max(0,volume),Math.max(0,capacity)));
    }
    /**
     * The game's ambient pressure (Pa): the outside of every vessel's wall, and the pressure of the air the network
     * reduction's ambient state holds.
     */
    public static final double AMBIENT_PRESSURE=101325;
    /**
     * Batch 2026-11-10-chimney (owner decision C1 B): the atmosphere falls with height at every boundary open to it. The
     * density of the outside air, kg/m³ (dry air, 298.15 K, 101,325 Pa), and the height, block y, where it stands at
     * {@link #AMBIENT_PRESSURE} (the world's sea level).
     */
    public static final double AIR_DENSITY=1.185,SEA_LEVEL=63;
    /** The atmosphere's pressure at height {@code y}, Pa: {@code AMBIENT_PRESSURE - AIR_DENSITY g (y - SEA_LEVEL)}, 11.6 Pa a block. */
    public static double atmosphere(double y){return AMBIENT_PRESSURE-AIR_DENSITY*9.80665*(y-SEA_LEVEL);}
    /** The pressure outside this node's wall, Pa ({@link #atAmbient}): an open vessel's surface reads it. */
    private double ambient=AMBIENT_PRESSURE;
    /** This layout for a vessel whose wall stands under {@code value} (its inventory's ambient); returns this. */
    public PhaseLayout atAmbient(double value){ambient=value;return this;}
    /**
     * Owner decision D54: every vessel is a bag with a stiff wall. Its wall carries no load while the contents are
     * smaller than the capacity, so the pressure is the ambient and the volume the contents' own; once the contents
     * would exceed the capacity the wall holds them there and the pressure comes from the contents. The wall law
     * {@code P = P_ambient + k max(0, V - V_max)} in its stiff limit: {@code V <= V_max}, {@code P >= P_ambient}, one of
     * the two an equality, as one row {@code max(taut, slack) = 0} with {@code taut = (V - V_max)/V_max} (the fixed-volume
     * row it replaces, to the bit) and {@code slack = 2 (P_ambient - P)/P_ambient}, twice as steep in pressure as any
     * contents' own volume, so a vessel exactly at the kink reads the taut row. Continuous and smooth on each branch; a
     * Newton on it takes the slopes of the branch the iterate is on ({@code branch}, {@link Wall}).
     *
     * <p>An infinite {@code capacity} is an open vessel's (batch 2026-10-21-floating-roof-tanks,
     * {@code PassiveNetwork.Inventory#wallCapacity}): its wall is never taut, so the row is the slack branch alone, the
     * same row count, and its bag is the contents' own volume ({@link #bagVolume}).</p>
     *
     * <p>A negative {@code capacity} is a rigid vessel's, {@code -V_max} (batch 2026-11-10-rigid-reservoir,
     * {@code PassiveNetwork.Inventory#wallCapacity}, {@link #rigid}): its wall is always taut, so the row is the taut branch
     * alone, {@code V = V_max} at any pressure, the same row count; its bag is always {@code V_max}, so its wall does no work.</p>
     */
    public static double wallRow(FluidThermodynamics.State state,double capacity){return wallRow(state,capacity,null);}
    /** Whether {@code capacity} is a rigid vessel's ({@link #wallRow}). */
    public static boolean rigid(double capacity){return capacity<0;}
    /** The volume a wall of {@code capacity} holds at most, m³: infinite for an open vessel. */
    public static double capacityVolume(double capacity){return Math.abs(capacity);}
    /** {@link #wallRow} stated on {@code branch}'s branch ({@link Wall#taut}); on the state's own when it is null. */
    public static double wallRow(FluidThermodynamics.State state,double capacity,Wall branch){return wallRow(state,capacity,branch,AMBIENT_PRESSURE);}
    /** {@link #wallRow} of a vessel whose wall stands under {@code ambient}: an open vessel's surface (C1 B); a closed bag's slack branch keeps {@link #AMBIENT_PRESSURE}. */
    public static double wallRow(FluidThermodynamics.State state,double capacity,Wall branch,double ambient) {
        if(capacity==Double.POSITIVE_INFINITY)return 2*(ambient-state.pressure())/AMBIENT_PRESSURE;
        if(capacity<0)return (state.volume()+capacity)/-capacity;
        double taut=(state.volume()-capacity)/capacity,slack=2*(AMBIENT_PRESSURE-state.pressure())/AMBIENT_PRESSURE;
        return (branch==null?taut>=slack:branch.taut())?taut:slack;
    }
    /**
     * The branches of a vessel's wall at one state: {@code taut}, whether the wall carries the contents ({@link #wallRow}
     * on its taut branch); {@code full}, whether the bag is at the capacity ({@link #bagVolume}). Decision A423: the wall
     * row and the bag volume are continuous and piecewise smooth, and a one-sided difference across their kink reads the
     * far branch's slope for a row on the near one; a full tank of air at the ambient, the common state of a vessel in
     * the game, sits on that kink (five full nitrogen tanks seeded 7.5e-9 of their capacity over it stalled the line
     * search at 5.3e-8 at every step size, the chord steps keeping the taut slopes across the branch flips). The rule: a
     * Jacobian sweep states every wall row and bag volume on the branches of the point it is built at
     * ({@link SparseNewton.Equations#linearising}, a semismooth Newton), and a chord step is refused once a vessel's
     * branches differ from the Jacobian's ({@link SparseNewton.Equations#linearisationHolds}). The rows and roots are
     * unchanged.
     */
    public record Wall(boolean taut,boolean full) {}
    /** The wall's branches at {@code state}. */
    public static Wall wall(FluidThermodynamics.State state,double capacity) {
        if(capacity<0)return new Wall(true,true);
        boolean taut=taut(state,capacity);return new Wall(taut,taut||state.volume()>=capacity*(1-1e-12));
    }
    /** Whether the wall carries the contents ({@link #wallRow} on its taut branch). */
    public static boolean taut(FluidThermodynamics.State state,double capacity) {
        if(capacity==Double.POSITIVE_INFINITY)return false;
        if(capacity<0)return true;
        return (state.volume()-capacity)/capacity>=2*(AMBIENT_PRESSURE-state.pressure())/AMBIENT_PRESSURE;
    }
    /**
     * The volume of the bag holding {@code state}: the capacity exactly while the wall carries it, the contents' own volume
     * while it is slack. The wall's work on the ambient over a step is {@code P_ambient (V_bag,end - V_bag,start)}: the
     * volume moves only on the slack branch, where the inside pressure is the ambient, so the work is exact and is zero
     * between two taut states. Contents within 1e-12 of the capacity fill it (a vessel at the kink, the ambient pressure at
     * its capacity, reads the slack branch or the taut one by the roundoff of its volume; ten times below the closure's
     * resolution, so no row registers it).
     */
    public static double bagVolume(FluidThermodynamics.State state,double capacity){return bagVolume(state,capacity,null);}
    /** {@link #bagVolume} stated on {@code branch}'s branch ({@link Wall#full}); on the state's own when it is null. */
    public static double bagVolume(FluidThermodynamics.State state,double capacity,Wall branch) {
        if(capacity<0)return -capacity;
        double volume=state.volume();
        return (branch==null?taut(state,capacity)||volume>=capacity*(1-1e-12):branch.full())?capacity:volume;
    }
    /** Target amounts/U may include backward-Euler edge contributions computed in the same residual evaluation. {@code capacity}
     * is the vessel's {@code V_max} ({@link #wallRow}); {@code workFrom} is the bag volume the step starts from, whose wall work
     * the energy row books ({@code NaN}: none, the state is closed to the energy it holds). */
    public void residual(FluidThermodynamics.State state,double[] targetAmounts,double targetEnergy,double capacity,double workFrom,double[] result,int offset,double[] variables) {
        residual(state,targetAmounts,targetEnergy,capacity,workFrom,result,offset,variables,null,null);
    }
    /** {@code branch}: the wall's branches the rows are stated on in a Jacobian sweep ({@link Wall}, decision A423); null, the state's own. */
    public void residual(FluidThermodynamics.State state,double[] targetAmounts,double targetEnergy,double capacity,double workFrom,double[] result,int offset,
                         double[] variables,FluidThermodynamics.Prepared prepared,Wall branch) {
        double[] l=state.liquidView(),v=state.vaporView();
        int row=balanceRows(state,targetAmounts,targetEnergy,capacity,workFrom,result,offset,variables,branch);
        equilibriumResidual(state,l,v,result,row,offset,variables,prepared);
    }
    /**
     * The rows a change of this node's accumulated targets can move, and no others: the component
     * balances, the water balance, the energy balance and the wall row. The equilibrium and
     * closure rows below them read the decoded state alone, so a Jacobian sweep that perturbs a
     * <em>neighbour</em> leaves them at exactly the residual it already evaluated. Returns the
     * first row after this block.
     *
     * <p>The energy balance of a vessel whose wall moves is {@code dU = sum(h_in dm_in) - P_ambient dV_bag}
     * ({@link #bagVolume}): with {@code workFrom} the start's bag volume the row states
     * {@code U + P_ambient (V_bag - workFrom) = target}, which between two taut states is {@code U = target} to the bit.
     *
     * <p>{@code branch}: the wall's branches the energy row's bag volume and the wall row are stated on ({@link Wall}); null, the state's own.
     */
    public int balanceRows(FluidThermodynamics.State state,double[] targetAmounts,double targetEnergy,double capacity,double workFrom,
                           double[] result,int offset,double[] variables,Wall branch) {
        double[] l=state.liquidView(),v=state.vaporView();int row=offset;
        for(int i:components)result[row++]=(l[i]+v[i]-targetAmounts[i])/componentScales[i];
        if(waterLiquidIndex>=0||waterVaporIndex>=0)result[row++]=(state.waterLiquid()+state.waterVapor()-targetAmounts[l.length])/componentScales[l.length];
        double energy=state.internalEnergy();
        if(!Double.isNaN(workFrom)){double moved=bagVolume(state,capacity,branch)-workFrom;if(moved!=0)energy+=ambient*moved;}
        result[row++]=(energy-targetEnergy)/energyScale;
        result[row++]=wallRow(state,capacity,branch,ambient);
        if(solidIndex>=0){solidRows(state,variables,offset,solidReference,false,result);row+=3;}
        return row;
    }
    /**
     * A junction's rows: M-1 mass-fraction equations, continuity, enthalpy, and amount normalization, then its phase
     * equilibrium rows. {@code weight} multiplies the mixing and enthalpy rows: an owned junction states them in amount
     * form over its holdup, {@code (dt/m)(m/dt + Q)} times the difference (see
     * {@code PassiveStepSolver.Equations.junctionInflow}).
     */
    public void junctionResidual(FluidThermodynamics.State state,double[] incomingMassFractions,double incomingSpecificEnthalpy,
                                 double netMassFlow,double retainedPressure,double[] result,int offset,double[] variables,FluidThermodynamics.Prepared prepared,double weight) {
        int row=junctionRows(state,incomingMassFractions,incomingSpecificEnthalpy,netMassFlow,retainedPressure,result,offset,variables,weight);
        equilibriumResidual(state,state.liquidView(),state.vaporView(),result,row,offset,variables,prepared);
    }
    /**
     * The mixing block of {@link #junctionResidual}: everything the inflow and the net flow move, and nothing the
     * equilibrium rows below it read. Returns the first row after this block. {@code retainedPressure} is positive only
     * for a junction no open connection reaches, and is then the pressure it keeps: nothing but the hydraulics decides a
     * junction's pressure, and with every connection closed the net flow row is the sum of the closed rows, satisfied
     * identically, so the block would be one equation short of its own pressure and the factorization singular (a
     * junction between two stopped filters or two shut pumps). An isolated junction keeps the pressure it had, as it
     * keeps the composition it holds.
     */
    public int junctionRows(FluidThermodynamics.State state,double[] incomingMassFractions,double incomingSpecificEnthalpy,
                            double netMassFlow,double retainedPressure,double[] result,int offset,double[] variables,double weight) {
        var n=totalAmounts(state,state.liquidView(),state.vaporView());
        int row=offset;
        for(int index=0;index<junctionBasis.length-1;index++) {
            int i=junctionBasis[index];double mw=i==n.length-1?model.waterMolecularWeight:model.hydrocarbon.molecularWeight(i);
            result[row++]=weight*(n[i]*mw/state.mass()-incomingMassFractions[i]);
        }
        // 1 kg/s reference scale, or the retained pressure of an isolated junction, in the
        // logarithmic variable the pressure unknown already is.
        result[row++]=retainedPressure>0?variables[offset+pressureIndex]-DeterministicMath.log(retainedPressure/1e5):netMassFlow;
        result[row++]=weight*(state.enthalpy()/state.mass()-incomingSpecificEnthalpy)/Math.max(1,energyScale/state.mass());
        result[row++]=sum(n)/amountScale-1;
        if(solidIndex>=0){var target=solidReference.clone();for(int i=0;i<3;i++)target[i]/=state.mass();solidRows(state,variables,offset,target,true,result);row+=3;}
        return row;
    }
    /**
     * Three aggregate balances; population identities are reconstructed outside Newton. The moments are read from the
     * unknowns rather than from the decoded state, so that each row stays exactly linear in its own unknown everywhere,
     * including past the nonnegative boundary {@link #decode} projects onto: read from the projected state the row would
     * be constant for any trial with a negative moment, a column of zeros, and every node downstream of a filter sits on
     * that boundary. The two agree at every point the solver can accept.
     */
    public void solidRows(FluidThermodynamics.State state,double[] variables,int offset,double[] target,boolean junction,double[] result) {
        if(solidIndex<0)return;
        int row=offset+componentBalanceCount()+2;
        for(int i=0;i<3;i++) {
            double moment=variables[offset+solidIndex+i]*solidScale[i];
            result[row+i]=junction?(moment/state.mass()-target[i])/(solidScale[i]/solidScale[0]):(moment-target[i])/solidScale[i];
        }
    }
    private void equilibriumResidual(FluidThermodynamics.State state,double[] l,double[] v,double[] result,int row,int offset,double[] variables,
                                     FluidThermodynamics.Prepared prepared) {
        // A seed whose hydrocarbon is an omitted trace (FluidThermodynamics.omittedTrace) may hold it in two phases, but
        // the layout lays out neither: it decodes no hydrocarbon, and has no split to state.
        if(liquidActive&&vaporActive&&components.length>0) {
            if(state.liquidProperties()==null||state.vaporProperties()==null)throw new IllegalArgumentException("A phase vanished inside a fixed-regime Newton trial");
            double nl=sum(l),nv=sum(v);
            double[] fl=state.liquidProperties().logFugacityView(),fv=state.vaporProperties().logFugacityView();
            // The total amount cancels from the fugacity ratio, including at zero amount. A component
            // the frozen support reduced to one phase has no split unknown and therefore no row here:
            // its material balance is the whole of it, and this is the only equation it loses.
            // Hoisted, not changed: both logarithms are the same doubles for every component.
            double amountLog=DeterministicMath.log(nv/nl),pressureLog=DeterministicMath.log(state.pressure()/state.hydrocarbonPartialPressure());
            for(int i:components)if(support[i]==PhaseSupport.BOTH)
                result[row++]=-variables[offset+vaporIndex[i]]+amountLog+fl[i]-fv[i]+pressureLog;
        }
        if(waterLiquidIndex>=0&&waterVaporIndex>=0)result[row++]=DeterministicMath.log(state.waterPartialPressure()
                /(prepared==null?model.saturationPressure(state.temperature()):model.saturationPressure(prepared)));
        if(partialPressureIndex>=0)result[row++]=(state.hydrocarbonPartialPressure()+state.waterPartialPressure()-state.pressure())/state.pressure();
        if(row!=offset+size)throw new IllegalStateException("Phase equation/unknown count mismatch");
    }
    /**
     * Components whose omitted phase this converged state no longer supports omitting, marked in {@code promoted} for
     * the next active-set pass; returns how many were newly marked. The test is V3's reinsertion inequality on this
     * layout's own equilibrium relation: at a converged point the retained components satisfy
     * {@code ln y_i = ln x_i + ln phi_i^L - ln phi_i^V + ln(P/Pc)}, and the equation of state fills {@code ln phi_i} of
     * an absent component at infinite dilution, so the same expression says what mole fraction the omitted phase would
     * hold; when that reaches {@link TraceTruncationPolicy#REINSERTION_FACTOR} times the cutoff the component gets both
     * phases back (the factor is the hysteresis against alternation). {@link #encode} already seeds a component with an
     * empty phase at exactly this ratio.
     */
    public int reactivate(FluidThermodynamics.State state,TraceTruncationPolicy policy,boolean[] promoted) {
        if(!policy.enabled()||!liquidActive||!vaporActive)return 0;
        if(promoted.length!=liquidIndex.length)throw new IllegalArgumentException("Reactivation basis mismatch");
        var lp=state.liquidProperties();var vp=state.vaporProperties();
        if(lp==null||vp==null)return 0;
        double[] l=state.liquidView(),v=state.vaporView(),fl=lp.logFugacityView(),fv=vp.logFugacityView();
        double nl=sum(l),nv=sum(v);
        if(!(nl>0&&nv>0))return 0;
        double pressureRatio=DeterministicMath.log(state.pressure()/state.hydrocarbonPartialPressure());
        double threshold=DeterministicMath.log(TraceTruncationPolicy.REINSERTION_FACTOR*policy.cutoffMoleFraction());
        int restored=0;
        for(int i:components) {
            if(!support[i].singlePhase()||promoted[i])continue;
            double implied=support[i]==PhaseSupport.LIQUID_ONLY
                    ?DeterministicMath.log(l[i]/nl)+fl[i]-fv[i]+pressureRatio
                    :DeterministicMath.log(v[i]/nv)-fl[i]+fv[i]-pressureRatio;
            if(implied>=threshold){promoted[i]=true;restored++;}
        }
        return restored;
    }
    /**
     * The per-component phase support of one node, derived from a full-basis reference state and then frozen: V3's
     * rule, on the seed's own phase compositions. The reference is the seed the layout is built from and never a Newton
     * iterate, so the unknown set of a pass is fixed before the pass starts, which lets the sparsity pattern, the
     * colouring, the ordering and the retained factorization be keyed on it. A component is reduced to one phase when
     * its mole fraction in the other phase is below the cutoff while this one's is not; when both are below it both are
     * kept. {@code promoted} components are kept in both phases regardless (a reactivation may not be undone by a later
     * pass of the same solve). A node with one active hydrocarbon phase gets that phase for every present component; at
     * {@link TraceTruncationPolicy#OFF} the support is the unreduced layout's.
     */
    public static PhaseSupport[] support(FluidThermodynamics.State seed,boolean[] componentMask,TraceTruncationPolicy policy,boolean[] promoted) {
        return support(seed,componentMask,policy,promoted,false);
    }
    /**
     * {@link #support} for a node that is a pipe junction ({@code junction}) or a vessel. A node whose seed holds an
     * omitted hydrocarbon trace ({@link FluidThermodynamics#omittedTrace}, decision D41: a junction's carried dry gas, a
     * vessel's trace within its wall's volume resolution) has no phase of its own for it. Every hydrocarbon component is
     * an omitted trace with no unknown and no row, below what the node's rows resolve. An amount unknown there has a
     * difference step no row registers, or one that swamps the phase it is in (a tank's 1e-18 mol of crude liquid,
     * differenced by 5.4e-6 mol of methane, had no liquid root). With a gas's water vapour, the saturation row read the
     * ratio of two such amounts and never contracted. The transport reconstruction carries them exactly and books more
     * when more arrives; a share at or above the trace is a phase appearance.
     */
    public static PhaseSupport[] support(FluidThermodynamics.State seed,boolean[] componentMask,TraceTruncationPolicy policy,boolean[] promoted,boolean junction) {
        var support=supportOf(seed,componentMask,policy,promoted);
        if(FluidThermodynamics.omittedTrace(seed,junction))Arrays.fill(support,PhaseSupport.ABSENT);
        return support;
    }
    /**
     * {@link #support(FluidThermodynamics.State, boolean[], TraceTruncationPolicy, boolean[], boolean)} of a node of
     * {@code model}'s network, under its trace truncation policy, with the support read from the fluid phases of the
     * answer the seed was adopted from (owner decision D29 A). Where the seed's liquid is a crystal's condensed share alone
     * ({@link FluidThermodynamics#condensedShareOnly}), that liquid is no phase of the answer and its composition says
     * nothing of what a liquid there holds: the answer's one fluid phase is the vapour, and every component the vapour
     * holds is admitted to the liquid too ({@link PhaseSupport#BOTH} where the rule gives {@link PhaseSupport#VAPOR_ONLY}),
     * so the supersaturated liquid takes up the species that arrive with it (methane held in the vapour only cooled an
     * LNG-fed tank to methane's 90.69 K floor). Elsewhere the same support as without the model.
     */
    public static PhaseSupport[] support(FluidThermodynamics model,FluidThermodynamics.State seed,boolean[] componentMask,boolean[] promoted,boolean junction) {
        var support=support(seed,componentMask,model.traceTruncation(),promoted,junction);
        if(model.condensedShareOnly(seed))for(int i=0;i<support.length;i++)if(support[i]==PhaseSupport.VAPOR_ONLY)support[i]=PhaseSupport.BOTH;
        return support;
    }
    private static PhaseSupport[] supportOf(FluidThermodynamics.State seed,boolean[] componentMask,TraceTruncationPolicy policy,boolean[] promoted) {
        Objects.requireNonNull(seed);Objects.requireNonNull(policy);
        double[] l=seed.liquidView(),v=seed.vaporView();
        if(componentMask!=null&&componentMask.length!=l.length)throw new IllegalArgumentException("Component mask mismatch");
        if(promoted!=null&&promoted.length!=l.length)throw new IllegalArgumentException("Reactivation basis mismatch");
        var support=new PhaseSupport[l.length];
        boolean liquidActive=seed.liquidVolume()>0,vaporActive=sum(v)>0;
        double nl=liquidActive?sum(l):0,nv=vaporActive?sum(v):0;
        for(int i=0;i<l.length;i++) {
            if(!(l[i]+v[i]>0||componentMask!=null&&componentMask[i]))support[i]=PhaseSupport.ABSENT;
            // A masked component this reference holds none of, on a point with no hydrocarbon phase to hold it in, is an
            // omitted trace: inventing a phase for a species nothing delivers is the phantom-trace defect. It stays in the
            // mask, so a pass whose seed does carry the component states it again. A component this reference does hold
            // with no phase to hold it in is a real appearance problem and is left to the layout to refuse.
            else if(l[i]+v[i]==0&&!liquidActive&&!vaporActive)support[i]=PhaseSupport.ABSENT;
            else if(!vaporActive)support[i]=PhaseSupport.LIQUID_ONLY;
            else if(!liquidActive)support[i]=PhaseSupport.VAPOR_ONLY;
            else if(promoted!=null&&promoted[i])support[i]=PhaseSupport.BOTH;
            else support[i]=PhaseSupport.of(l[i]/nl,v[i]/nv,policy.cutoffMoleFraction());
        }
        return support;
    }
    public static double[] totalAmounts(FluidThermodynamics.State state) {
        return totalAmounts(state,state.liquidView(),state.vaporView());
    }
    private static double[] totalAmounts(FluidThermodynamics.State state,double[] l,double[] v) {
        double[] n=Arrays.copyOf(l,l.length+1);
        for(int i=0;i<l.length;i++)n[i]+=v[i];n[l.length]=state.waterLiquid()+state.waterVapor();return n;
    }
    /** Thrown by {@link #junctionClosure} at a point where a phase of its start is vanishing: the closure's verdict that its
     * rows empty that phase (owner decision D38 C), reached without crawling to the boundary. */
    public static final class Vanished extends RuntimeException {
        Vanished(){super(null,null,false,false);}
    }
    /**
     * A pipe junction's own rows isolated ({@code FluidThermodynamics.junctionSplit}): {@link #junctionResidual} with the
     * mass fractions {@code massFractions} and specific enthalpy {@code specificEnthalpy} it holds as its inflow, no net
     * flow and {@code pressure} retained, the pressure no connection decides here. Every point it evaluates is read by the
     * vanishing rule against {@code start} ({@code FluidThermodynamics.vanishing}); one that empties a phase of the start
     * ends the closure with {@link Vanished}.
     */
    public SparseNewton.Equations junctionClosure(double[] massFractions,double specificEnthalpy,double pressure,FluidThermodynamics.State start) {
        double[] w=massFractions.clone();
        return new SparseNewton.Equations(){
            private volatile FluidThermodynamics.Prepared prepared;
            public int size(){return PhaseLayout.this.size;}
            public double differenceScale(int column,double value){return PhaseLayout.this.differenceStepScale(column,value);}
            public double maximumStep(double[] x,double[] direction){double alpha=1;for(int c=0;c<x.length;c++)if(totalAmountVariable(c)&&x[c]>0&&direction[c]<0)alpha=Math.min(alpha,.99*x[c]/-direction[c]);return alpha;}
            public int[][] columnRows(){int[][] rows=new int[size()][size()];for(int[] column:rows)for(int i=0;i<column.length;i++)column[i]=i;return rows;}
            public double[] residual(double[] x){
                double temperature=PhaseLayout.this.temperature(x,0);var local=prepared;
                if(local==null||local.temperature()!=temperature)prepared=local=model.prepare(temperature);
                var state=decode(x,0,local);
                if(FluidThermodynamics.vanishing(start,state)!=0)throw new Vanished();
                double[] f=new double[size()];junctionResidual(state,w,specificEnthalpy,0,pressure,f,0,x,local,1);return f;
            }
        };
    }
    /** A vessel's closure at what it holds: the amounts, the energy and the wall ({@link #balanceRows}; {@code workFrom} as
     * there). */
    public SparseNewton.Equations wallClosure(double[] amounts,double energy,double capacity,double workFrom) {
        double[] target=amounts.clone();
        return new SparseNewton.Equations(){
            private volatile FluidThermodynamics.Prepared prepared;
            /** The wall's branches at the point the Jacobian in use was built at (decision A423): {@code sweeping} is read by
             * the sweep's trials while the build is bracketed, {@code built} by the chord test. */
            private Wall sweeping,built;
            public int size(){return PhaseLayout.this.size;}
            public double differenceScale(int column,double value){return PhaseLayout.this.differenceStepScale(column,value);}
            public double maximumStep(double[] x,double[] direction){double alpha=1;for(int c=0;c<x.length;c++)if(totalAmountVariable(c)&&x[c]>0&&direction[c]<0)alpha=Math.min(alpha,.99*x[c]/-direction[c]);return alpha;}
            public int[][] columnRows(){int[][] rows=new int[size()][size()];for(int[] column:rows)for(int i=0;i<column.length;i++)column[i]=i;return rows;}
            public void linearising(double[] x){built=sweeping=wall(decode(x,0,prepare(x)),capacity);}
            public void linearised(){sweeping=null;}
            public boolean linearisationHolds(double[] builtAt,double[] now){return built==null||built.equals(wall(decode(now,0,prepare(now)),capacity));}
            public double[] residual(double[] x){
                var local=prepare(x);
                double[] f=new double[size()];PhaseLayout.this.residual(decode(x,0,local),target,energy,capacity,workFrom,f,0,x,local,sweeping);return f;
            }
            private FluidThermodynamics.Prepared prepare(double[] x) {
                double temperature=PhaseLayout.this.temperature(x,0);var local=prepared;
                if(local==null||local.temperature()!=temperature)prepared=local=model.prepare(temperature);
                return local;
            }
        };
    }
}
