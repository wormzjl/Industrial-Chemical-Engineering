package com.wormzjl.icheme.science.fluid.solver;

import com.wormzjl.icheme.science.math.DeterministicMath;

import com.wormzjl.icheme.science.fluid.diagnostics.SolverDiagnostics;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.thermo.TraceTruncationPolicy;
import java.util.*;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;

/**
 * Fixed phase active set for one Newton pass. Evaluates properties directly; never runs a nested flash.
 *
 * <p>W3b of batch 2026-11-11-aqueous-solvents: a node has up to three fluid phases, the storage slots of its state (the
 * vapour, a liquid and a second liquid, {@link FluidThermodynamics.State}), and water is a component like any other. A
 * component's support is a mask of the slots it may occupy ({@link #LIQUID}, {@link #VAPOR}, {@link #AQUEOUS}, the bits
 * of {@link FluidThermodynamics#phaseBits}); 0 is absent. Per present component the unknowns are its total amount and, for
 * each further supported slot, {@code ln(n_ik/n_i,ref)}, the reference slot the first supported one in the order liquid,
 * aqueous, vapour; the equilibrium row of a further slot is
 * {@code ln(n_ik/n_k) + ln phi_ik = ln(n_i,ref/n_ref) + ln phi_i,ref}. A node of two slots keeps the unknowns and rows it
 * had before W3b; the free water's unknowns, the hydrocarbon partial pressure, the Dalton closure and the saturation row
 * are gone with the free water.</p>
 */
public final class PhaseLayout {
    /** The support bits of the three slots ({@link FluidThermodynamics#phaseBits}). */
    public static final byte LIQUID=1,VAPOR=2,AQUEOUS=4;
    /** The slots in reference order (liquid, aqueous, vapour), with their storage slot and bit. */
    private static final int[] ORDER_SLOT={FluidThermodynamics.State.LIQUID,FluidThermodynamics.State.AQUEOUS,FluidThermodynamics.State.VAPOR};
    private static final byte[] ORDER_BIT={LIQUID,AQUEOUS,VAPOR};
    private final FluidThermodynamics model;
    private final int[] components;
    /** Per conserved component and storage slot, the local column of its unknown: the total for the reference slot, the
     * log ratio for a further one; -1 where it has none. */
    private final int[][] slotIndex;
    /** Where each present component's total amount lives; -1 for an absent one. */
    private final int[] amountIndex;
    /** Per component, its reference storage slot; -1 for an absent one. */
    private final int[] referenceSlot;
    private final byte[] support;
    /**
     * The compounds this layout's evaluations carry in the kernel ({@link FluidThermodynamics.Prepared#carry}): what the
     * seed holds and what its mask says can arrive, whose {@code ln phi} the layout reads at zero amount too
     * ({@link #encode}, {@link #reactivate}). Named on the prepared bundle at every decode.
     */
    private final boolean[] carried;
    /** The bits of the slots the seed holds. */
    private final int active;
    /** The conserved components a junction writes mass fractions for: the graph fixes it, so it is built with the layout
     * instead of boxed into a list on every evaluation. */
    private final int[] junctionBasis;
    private final int temperatureIndex,pressureIndex,size;
    private final double amountScale,energyScale;
    private final double[] componentScales,differenceScales,seedValues;
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
                       byte[] support) {
        this(model,seed,componentMask,conservedReference,support,seed.solidMoments().mass()>0);
    }
    public PhaseLayout(FluidThermodynamics model,FluidThermodynamics.State seed,boolean[] componentMask,double[] conservedReference,
                       byte[] support,boolean solidSupport) {
        this.model=Objects.requireNonNull(model);
        int n=seed.componentCount();double[] totals=seed.totals();var present=new ArrayList<Integer>();
        if(componentMask!=null&&componentMask.length!=n)throw new IllegalArgumentException("Component mask mismatch");
        if(support.length!=n)throw new IllegalArgumentException("Phase support basis mismatch");
        this.support=support.clone();
        carried=new boolean[n];
        for(int i=0;i<n;i++)carried[i]=totals[i]>0||componentMask!=null&&componentMask[i];
        // A masked component the support calls absent is an omitted trace, not a member of this layout: it carries no
        // unknown and no row, and the mask keeps it available to the phase appearance pass.
        for(int i=0;i<n;i++)if((totals[i]>0||componentMask!=null&&componentMask[i])&&support[i]!=0)present.add(i);
        components=present.stream().mapToInt(Integer::intValue).toArray();
        active=activeBits(seed);
        if(components.length>0&&active==0)throw new IllegalArgumentException("A phase appearance pass is required");
        for(int i:components)if((this.support[i]&~active)!=0)
            throw new IllegalArgumentException("Phase support does not match the seed's active phases at component "+i);
        junctionBasis=components.clone();
        // The last of this basis has no mixing row: the closure row alone states it. That row's sum stands near 1, where the
        // Newton's difference step of a share under 1.1e-10 rounds away and leaves its column empty, so the largest share takes
        // that place and every trace keeps a row of its own (a dry outlet line's old nitrogen, batch 2026-11-02-valve-hold).
        int bulk=junctionBasis.length-1;double most=-1;
        for(int index=0;index<junctionBasis.length;index++){double amount=totals[junctionBasis[index]];if(amount>=most){most=amount;bulk=index;}}
        if(bulk>=0){int held=junctionBasis[bulk];junctionBasis[bulk]=junctionBasis[junctionBasis.length-1];junctionBasis[junctionBasis.length-1]=held;}
        slotIndex=new int[n][3];amountIndex=new int[n];referenceSlot=new int[n];
        for(int[] row:slotIndex)Arrays.fill(row,-1);
        Arrays.fill(amountIndex,-1);Arrays.fill(referenceSlot,-1);
        int offset=0;
        for(int i:components)for(int k=0;k<3;k++)if((this.support[i]&ORDER_BIT[k])!=0) {
            int slot=ORDER_SLOT[k];slotIndex[i][slot]=offset++;
            if(referenceSlot[i]<0){referenceSlot[i]=slot;amountIndex[i]=slotIndex[i][slot];}
        }
        temperatureIndex=offset++;pressureIndex=offset++;
        solidIndex=solidSupport?offset:-1;if(solidSupport)offset+=3;size=offset;
        solidBasis=seed.solids();solidReference=seed.solidMoments().values();
        solidScale=new double[]{Math.max(1e-12,seed.mass()),Math.max(1e-12,seed.volume()),Math.max(1e-9,seed.mass()*1000)};
        amountScale=Math.max(1e-12,sum(totals));
        energyScale=Math.max(1,Math.max(Math.abs(seed.internalEnergy()),amountScale*FluidThermodynamics.R*seed.temperature()));
        if(conservedReference!=null&&conservedReference.length!=n)throw new IllegalArgumentException("Balance reference basis mismatch");
        boolean resolveTraces=seed.vaporVolume()/seed.volume()<.01;
        componentScales=new double[n];
        for(int c=0;c<n;c++)componentScales[c]=!resolveTraces||conservedReference!=null&&conservedReference[c]==0?amountScale:Math.max(1e-30,totals[c]);
        differenceScales=encode(seed);seedValues=differenceScales.clone();
        if(solidIndex>=0)for(int i=0;i<3;i++)differenceScales[solidIndex+i]=Math.max(1e-6,differenceScales[solidIndex+i]);
        if(!resolveTraces)for(int local=0;local<size;local++)if(totalAmountVariable(local))differenceScales[local]=Math.max(1e-4,differenceScales[local]);
        if(conservedReference!=null)for(int c:components)if(conservedReference[c]==0){int at=amountIndex[c];differenceScales[at]=Math.max(1e-4,differenceScales[at]);}
    }
    /** The slot bits ({@link #LIQUID}, {@link #VAPOR}, {@link #AQUEOUS}) of the phases {@code state} holds. */
    public static int activeBits(FluidThermodynamics.State state) {
        int bits=0;
        if(state.slotVolume(FluidThermodynamics.State.LIQUID)>0)bits|=LIQUID;
        if(sum(state.vaporAmountsView())>0)bits|=VAPOR;
        if(state.slotVolume(FluidThermodynamics.State.AQUEOUS)>0)bits|=AQUEOUS;
        return bits;
    }
    private static byte bit(int slot){return slot==FluidThermodynamics.State.VAPOR?VAPOR:slot==FluidThermodynamics.State.LIQUID?LIQUID:AQUEOUS;}
    public int size(){return size;}
    /** The local column of a present component's total amount, -1 for one the layout does not carry. */
    public int amountVariable(int component){return amountIndex[component];}
    /**
     * The span by which the bulk difference floor of an amount may exceed the amount where a row reads it through a
     * logarithm: 1e4, so its step (the Newton's 1e-6 of the scale) stays within 1 % of the amount and the logarithm's
     * secant within 0.5 % of its tangent. The bulk floor of a gas-rich vessel (1e-4 of its amount) is kept wherever such
     * an amount is at least 1e-8 of its vessel; the fluid's water beside ice in a cold gas is steam of about 1e-5 Pa at
     * 150 K, 1e-11 of its vessel, where the floor's step was 8 times the steam and the Newton on the ice's row alternated.
     * W3b: the free water's saturation row is gone; a crystal's row of a vessel refresh reads its component's fluid total
     * through a logarithm ({@code VesselCrystals}), every crystal alike.
     */
    public static final double LOG_READ_SPAN=1e4;
    /** {@link #differenceStepScale} of amount column {@code local} where a row reads it through a logarithm: the floor
     * capped at {@link #LOG_READ_SPAN} of the seed's own amount. */
    public double logReadScale(int local,double value) {
        double seed=Math.abs(seedValues[local]);
        return traceDifferenceScale(Math.max(Math.abs(value),Math.min(Math.abs(differenceScales[local]),Math.max(seed,LOG_READ_SPAN*seed))));
    }
    /** The local column of the pressure unknown, {@code ln(P / 1e5)}; every layout has one. */
    public int pressureVariable(){return pressureIndex;}
    /** The number of component balance rows: one per present component, water among them. */
    public int componentBalanceCount(){return components.length;}
    /** The balance row of conserved component {@code component} among {@link #balanceRows}' (from their offset), or -1
     * where the layout does not carry it. */
    public int balanceRow(int component) {
        for(int index=0;index<components.length;index++)if(components[index]==component)return index;
        return -1;
    }
    /** The amount a component's balance row is stated over ({@link #balanceRows}), mol. */
    public double componentScale(int component){return componentScales[component];}
    /** This layout's frozen support of a conserved component: its slot bits, 0 for an absent one. */
    public byte support(int component){return support[component];}
    /** Components that hold fewer of the seed's slots than it has, the rest at exactly zero, for the diagnostics counters
     * and the reactivation test. */
    public int singlePhaseComponentCount() {
        if(Integer.bitCount(active)<2)return 0;
        int count=0;for(int i:components)if(support[i]!=active)count++;
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
        for(int component:components)if(local==amountIndex[component])return true;
        return false;
    }
    public double amountScale(){return amountScale;}
    public double energyScale(){return energyScale;}
    /** Resolve trace amounts relative to their own scale rather than the bulk inventory. This is also the floor
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
    /**
     * {@code state} where each of its phases carried every component of this layout in the kernel, else the same amounts
     * evaluated again on the whole list: a seed from an earlier pass or step was evaluated on that layout's support, and
     * this one reads {@code ln phi} at zero amount of what it can now receive. The phases it carried keep their bits.
     */
    private FluidThermodynamics.State carriedState(FluidThermodynamics.State state) {
        for(int slot=0;slot<3;slot++) {
            var phase=state.phase(slot);if(phase==null)continue;
            for(int i:components)if(!phase.carries(i))
                return model.adoptingState(state.temperature(),state.pressure(),state.vaporAmounts(),state.liquidAmounts(),state.aqueousAmounts(),null)
                        .withSolidState(state.solids(),state.solidMoments());
        }
        return state;
    }
    public double[] encode(FluidThermodynamics.State state) {
        state=carriedState(state);
        double[] x=new double[size];double[] slotTotal=null;
        for(int i:components) {
            int ref=referenceSlot[i];double total=0;
            for(int slot=0;slot<3;slot++)if(slotIndex[i][slot]>=0)total+=state.amountsView(slot)[i];
            // Any slot the frozen support omits holds the component at exactly zero, so the total of its supported slots is
            // the component's total and an omitted trace is folded into the slots that are kept.
            for(int slot=0;slot<3;slot++)if(slotIndex[i][slot]<0)total+=state.amountsView(slot)[i];
            x[amountIndex[i]]=total/amountScale;
            for(int slot=0;slot<3;slot++) {
                if(slot==ref||slotIndex[i][slot]<0)continue;
                double a=state.amountsView(slot)[i],r=state.amountsView(ref)[i];
                if(a>0&&r>0)x[slotIndex[i][slot]]=DeterministicMath.log(a)-DeterministicMath.log(r);
                else {
                    // The equilibrium split this component would reach at the seed's fugacity coefficients:
                    // ln(n_ik/n_i,ref) = ln(n_k/n_ref) + ln phi_i,ref - ln phi_ik. It is the seed for a phase that has just
                    // appeared, and for a trace component the active set has just reactivated, which is the same situation.
                    if(slotTotal==null)slotTotal=new double[]{sum(state.amountsView(0)),sum(state.amountsView(1)),sum(state.amountsView(2))};
                    x[slotIndex[i][slot]]=DeterministicMath.log(slotTotal[slot]/slotTotal[ref])
                            +state.phase(ref).logFugacityView()[i]-state.phase(slot).logFugacityView()[i];
                }
            }
        }
        x[temperatureIndex]=DeterministicMath.log(state.temperature()/350);x[pressureIndex]=DeterministicMath.log(state.pressure()/1e5);
        if(solidIndex>=0){var moments=state.solidMoments().values();for(int i=0;i<3;i++)x[solidIndex+i]=moments[i]/solidScale[i];}
        return x;
    }
    public FluidThermodynamics.State decode(double[] variables,int offset) {
        return decode(variables,offset,null);
    }
    /** {@code prepared} carries this node's temperature-only properties; it must be for the exact
     * temperature the variables decode to, and {@code null} evaluates them here as before. */
    public FluidThermodynamics.State decode(double[] variables,int offset,FluidThermodynamics.Prepared prepared) {
        return decode(variables,offset,prepared,null);
    }
    /**
     * The node at {@code variables} beside its base state, whose phase {@code tangents} these are
     * ({@link FluidThermodynamics#tangents}): the split of the amounts is exact, each phase is its tangent's first-order
     * phase where the step is small for it ({@link FluidThermodynamics#adoptingState(double, double, double[], double[],
     * double[], FluidThermodynamics.Prepared, com.wormzjl.icheme.science.fluid.thermo.HydrocarbonModel.Tangent[])}). For
     * the Jacobian's perturbed columns; {@code null} tangents decode on the equation of state.
     */
    public FluidThermodynamics.State decode(double[] variables,int offset,FluidThermodynamics.Prepared prepared,
                                            com.wormzjl.icheme.science.fluid.thermo.HydrocarbonModel.Tangent[] tangents) {
        int n=amountIndex.length;double[][] out=new double[3][n];
        for(int i:components) {
            double amount=amountScale*variables[offset+amountIndex[i]];
            int ref=referenceSlot[i],last=ref;double largest=0;
            for(int slot=0;slot<3;slot++){int at=slotIndex[i][slot];if(at<0||slot==ref)continue;double r=variables[offset+at];if(r>=largest){largest=r;last=slot;}}
            if(last==ref&&!hasOther(i)){out[ref][i]=amount;continue;}
            // Each slot but the largest is amount / sum_j exp(r_j - r_k), the largest takes the remainder: two slots give
            // the split l = n/(1 + e^r), v = n - l (r >= 0) and v = n/(1 + e^-r), l = n - v of the layout before W3b, to the bit.
            double rest=amount;
            for(int slot=0;slot<3;slot++) {
                if(slotIndex[i][slot]<0||slot==last)continue;
                double rk=ratio(variables,offset,i,slot),denominator=0;
                for(int j=0;j<3;j++)if(slotIndex[i][j]>=0)denominator+=j==slot?1:DeterministicMath.exp(ratio(variables,offset,i,j)-rk);
                out[slot][i]=amount/denominator;rest-=out[slot][i];
            }
            out[last][i]=rest;
        }
        double t=350*DeterministicMath.exp(variables[offset+temperatureIndex]),p=1e5*DeterministicMath.exp(variables[offset+pressureIndex]);
        if(prepared!=null)prepared.carry(carried);
        var state=tangents==null?model.adoptingState(t,p,out[0],out[1],out[2],prepared):model.adoptingState(t,p,out[0],out[1],out[2],prepared,tangents);
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
    private boolean hasOther(int i){for(int slot=0;slot<3;slot++)if(slot!=referenceSlot[i]&&slotIndex[i][slot]>=0)return true;return false;}
    /** {@code ln(n_ik/n_i,ref)} of a supported slot: 0 for the reference, its unknown otherwise. */
    private double ratio(double[] variables,int offset,int i,int slot){return slot==referenceSlot[i]?0:variables[offset+slotIndex[i][slot]];}
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
        int row=balanceRows(state,targetAmounts,targetEnergy,capacity,workFrom,result,offset,variables,branch);
        equilibriumResidual(state,result,row,offset,variables);
    }
    /**
     * The rows a change of this node's accumulated targets can move, and no others: the component
     * balances, the energy balance and the wall row. The equilibrium rows below them read the decoded state alone, so a
     * Jacobian sweep that perturbs a <em>neighbour</em> leaves them at exactly the residual it already evaluated. Returns
     * the first row after this block.
     *
     * <p>The energy balance of a vessel whose wall moves is {@code dU = sum(h_in dm_in) - P_ambient dV_bag}
     * ({@link #bagVolume}): with {@code workFrom} the start's bag volume the row states
     * {@code U + P_ambient (V_bag - workFrom) = target}, which between two taut states is {@code U = target} to the bit.
     *
     * <p>{@code branch}: the wall's branches the energy row's bag volume and the wall row are stated on ({@link Wall}); null, the state's own.
     */
    public int balanceRows(FluidThermodynamics.State state,double[] targetAmounts,double targetEnergy,double capacity,double workFrom,
                           double[] result,int offset,double[] variables,Wall branch) {
        double[] v=state.vaporAmountsView(),l=state.liquidAmountsView(),a=state.aqueousAmountsView();int row=offset;
        for(int i:components)result[row++]=(l[i]+v[i]+a[i]-targetAmounts[i])/componentScales[i];
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
        equilibriumResidual(state,result,row,offset,variables);
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
        var n=state.totals();
        int row=offset;
        for(int index=0;index<junctionBasis.length-1;index++) {
            int i=junctionBasis[index];
            result[row++]=weight*(n[i]*model.molecularWeight(i)/state.mass()-incomingMassFractions[i]);
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
    /**
     * The equilibrium rows: for each present component and each supported slot {@code k} past its reference,
     * {@code -ln(n_ik/n_i,ref) + ln(n_k/n_ref) + ln phi_i,ref - ln phi_ik}. The total amount cancels from the fugacity
     * ratio, including at zero amount; a component the frozen support reduced to one slot has no ratio unknown and no row.
     */
    private void equilibriumResidual(FluidThermodynamics.State state,double[] result,int row,int offset,double[] variables) {
        if(Integer.bitCount(active)>=2&&components.length>0) {
            double[] slotTotal=new double[3];double[][] f=new double[3][];
            for(int slot=0;slot<3;slot++) {
                if((active&bit(slot))==0)continue;
                var phase=state.phase(slot);
                if(phase==null)throw new IllegalArgumentException("A phase vanished inside a fixed-regime Newton trial");
                slotTotal[slot]=sum(state.amountsView(slot));f[slot]=phase.logFugacityView();
            }
            // Hoisted, not changed: the slot logarithm is the same double for every component.
            double[] amountLog=new double[3];int[] seen=new int[3];Arrays.fill(seen,-1);
            for(int i:components) {
                int ref=referenceSlot[i];
                for(int slot=0;slot<3;slot++) {
                    int at=slotIndex[i][slot];if(at<0||slot==ref)continue;
                    if(seen[slot]!=ref){amountLog[slot]=DeterministicMath.log(slotTotal[slot]/slotTotal[ref]);seen[slot]=ref;}
                    result[row++]=-variables[offset+at]+amountLog[slot]+f[ref][i]-f[slot][i];
                }
            }
        }
        if(row!=offset+size)throw new IllegalStateException("Phase equation/unknown count mismatch");
    }
    /**
     * Components whose omitted slot this converged state no longer supports omitting, marked in {@code promoted} for
     * the next active-set pass; returns how many were newly marked. The test is V3's reinsertion inequality on this
     * layout's own equilibrium relation: at a converged point the retained slots satisfy
     * {@code ln x_ik = ln x_i,ref + ln phi_i,ref - ln phi_ik}, and the equation of state fills {@code ln phi_ik} of an
     * absent component at infinite dilution, so the same expression says what mole fraction the omitted slot would hold;
     * when that reaches {@link TraceTruncationPolicy#REINSERTION_FACTOR} times the cutoff the component gets every slot
     * back (the factor is the hysteresis against alternation). {@link #encode} already seeds a component with an empty
     * slot at exactly this ratio.
     */
    public int reactivate(FluidThermodynamics.State state,TraceTruncationPolicy policy,boolean[] promoted) {
        if(!policy.enabled()||Integer.bitCount(active)<2)return 0;
        if(promoted.length!=amountIndex.length)throw new IllegalArgumentException("Reactivation basis mismatch");
        state=carriedState(state);
        double[] slotTotal=new double[3];double[][] f=new double[3][];
        for(int slot=0;slot<3;slot++) {
            if((active&bit(slot))==0)continue;
            var phase=state.phase(slot);if(phase==null)return 0;
            slotTotal[slot]=sum(state.amountsView(slot));f[slot]=phase.logFugacityView();
            if(!(slotTotal[slot]>0))return 0;
        }
        double threshold=DeterministicMath.log(TraceTruncationPolicy.REINSERTION_FACTOR*policy.cutoffMoleFraction());
        int restored=0;
        for(int i:components) {
            if(support[i]==active||promoted[i])continue;
            int ref=referenceSlot[i];
            double held=DeterministicMath.log(state.amountsView(ref)[i]/slotTotal[ref])+f[ref][i];
            for(int slot=0;slot<3;slot++) {
                if((active&bit(slot))==0||(support[i]&bit(slot))!=0)continue;
                if(held-f[slot][i]>=threshold){promoted[i]=true;restored++;break;}
            }
        }
        return restored;
    }
    /**
     * The per-component slot support of one node, derived from a full-basis reference state and then frozen: V3's rule, on
     * the seed's own phase compositions, over every slot the seed holds. The reference is the seed the layout is built
     * from and never a Newton iterate, so the unknown set of a pass is fixed before the pass starts, which lets the
     * sparsity pattern, the colouring, the ordering and the retained factorization be keyed on it. A component is dropped
     * from a slot where its mole fraction is below the cutoff while some other slot's is not; when every slot's is below
     * it every slot is kept. {@code promoted} components keep every slot (a reactivation may not be undone by a later pass
     * of the same solve). A node with one slot gets that slot for every present component; at
     * {@link TraceTruncationPolicy#OFF} the support is the unreduced layout's.
     *
     * <p>W3b deleted D41's omitted trace (a junction's carried dry gas beside free water, a vessel's hydrocarbon trace
     * within its wall's volume resolution, every hydrocarbon component absent): its cause was the free water's
     * saturation row reading the ratio of two such amounts, and every component now sits in an existing phase.</p>
     */
    public static byte[] support(FluidThermodynamics.State seed,boolean[] componentMask,TraceTruncationPolicy policy,boolean[] promoted) {
        Objects.requireNonNull(seed);Objects.requireNonNull(policy);
        int n=seed.componentCount();double[] totals=seed.totals();
        if(componentMask!=null&&componentMask.length!=n)throw new IllegalArgumentException("Component mask mismatch");
        if(promoted!=null&&promoted.length!=n)throw new IllegalArgumentException("Reactivation basis mismatch");
        var support=new byte[n];
        int active=activeBits(seed);double cutoff=policy.cutoffMoleFraction();
        double[] slotTotal={sum(seed.amountsView(0)),sum(seed.amountsView(1)),sum(seed.amountsView(2))};
        for(int i=0;i<n;i++) {
            // A masked component this reference holds none of, on a point with no phase to hold it in, is an omitted trace:
            // inventing a phase for a species nothing delivers is the phantom-trace defect. It stays in the mask, so a pass
            // whose seed does carry the component states it again.
            if(!(totals[i]>0||componentMask!=null&&componentMask[i])||active==0)continue;
            if(Integer.bitCount(active)==1||promoted!=null&&promoted[i]||!(cutoff>0)){support[i]=(byte)active;continue;}
            int kept=0;boolean any=false;
            for(int slot=0;slot<3;slot++) {
                if((active&bit(slot))==0)continue;
                boolean trace=seed.amountsView(slot)[i]/slotTotal[slot]<cutoff;
                if(!trace){kept|=bit(slot);any=true;}
            }
            support[i]=(byte)(any?kept:active);
        }
        return support;
    }
    /**
     * {@link #support(FluidThermodynamics.State, boolean[], TraceTruncationPolicy, boolean[])} of a node of {@code model}'s
     * network, under its trace truncation policy, with the support read from the fluid phases of the answer the seed was
     * adopted from (owner decision D29 A). A stand-in slot ({@link FluidThermodynamics#standIn}) is a crystal's condensed
     * share alone, no phase of the answer, and its composition says nothing of what a liquid there holds: every component
     * the node holds is admitted to it, so the supersaturated liquid takes up the species that arrive with it (methane held
     * in the vapour only cooled an LNG-fed tank to methane's 90.69 K floor). Elsewhere the same support as without the model.
     */
    public static byte[] support(FluidThermodynamics model,FluidThermodynamics.State seed,boolean[] componentMask,boolean[] promoted) {
        var support=support(seed,componentMask,model.traceTruncation(),promoted);
        int standIn=model.standIn(seed);
        if(standIn!=0)for(int i=0;i<support.length;i++)if(support[i]!=0)support[i]|=(byte)standIn;
        return support;
    }
    /** Each component's amount over the state's slots, water last ({@link FluidThermodynamics.State#totals}). */
    public static double[] totalAmounts(FluidThermodynamics.State state){return state.totals();}
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
