package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import java.util.Arrays;

import static com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics.GAS;
import static com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics.OIL;
import static com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics.AQUEOUS;

/**
 * What a vessel's phase port draws (decision D11: ports carry mixed phases by priority). Priority: a bottom
 * ({@link PassiveNetwork.PhasePort#LIQUID}) port draws the heavier of the vessel's two liquids
 * ({@link FluidThermodynamics#heavierLiquid}), then the lighter, then the gas; a top ({@link PassiveNetwork.PhasePort#VAPOR})
 * port the gas, then the lighter liquid, then the heavier; each liquid carries its volume share of the solids
 * ({@link FluidThermodynamics#phaseSolidShare}). A BULK end draws the bulk, except on a donor holding solids but no
 * liquid, whose bulk end draws its gas (owner decision D33, {@link FluidThermodynamics#bulkDrawsGas}).
 *
 * <p>Capacity and the priority stream: a phase can be drawn through a port over one step only up to what the vessel
 * held of it at the step start, {@code c = m_start / (N dt)} (kg/s), {@code N} the vessel's phase-port ends whose
 * connection may carry outflow by the static rule, {@code dt} the step (infinite in a rate solve). A port carrying the
 * outflow {@code q} draws the first phase of its order up to its capacity, the second with what is left, and so on; the
 * last phase the vessel holds takes whatever the earlier ones do not (a port's total flow is limited by the hydraulics
 * and the velocity cap alone). A phase drawn at its capacity (pinned) is drawn at the composition, enthalpy and density
 * of the step's start state, which exists whatever the end state holds (a phase drawn at its capacity usually ends the
 * step at zero, on its own phase boundary); the phase that carries the rest (marginal) is drawn at the step's end state,
 * and must be held by the pass's seed. Which phases are pinned and which one is marginal is a kink in the stream's
 * composition as a function of {@code q}, so it is frozen for one active-set pass (a {@link Segment}, decided from the
 * pass's start flow and seeds) and re-decided from the converged flow by the pass loop.</p>
 */
final class PhaseDraw {
    private PhaseDraw() {}

    /** The priority order of {@code port} on {@code state} (phase ids of {@link FluidThermodynamics}): null for BULK, which
     * draws the bulk, but the gas alone where the bulk end of a donor holding solids but no liquid draws its gas (owner
     * decision D33, {@link FluidThermodynamics#bulkDrawsGas}). A BULK end has no segment either way: what it carries in a
     * step is its node's transport ({@code PassiveStepSolver.Equations}). An OVERFLOW port skims the surface: the lighter
     * liquid, then the heavier, then the gas ({@link OpenVessel}). */
    static int[] order(FluidThermodynamics model,FluidThermodynamics.State state,PassiveNetwork.PhasePort port) {
        if(port==PassiveNetwork.PhasePort.BULK)return FluidThermodynamics.bulkDrawsGas(state)?new int[]{GAS}:null;
        int heavy=model.heavierLiquid(state),light=heavy==OIL?AQUEOUS:OIL;
        return switch(port) {
            case LIQUID,BOOT_HC->new int[]{OIL,AQUEOUS,GAS};
            // W3b: the boot holds the denser liquid, whichever it is.
            case BOOT_WATER->new int[]{heavy,light,GAS};
            case OVERFLOW->new int[]{light,heavy,GAS};
            default->new int[]{GAS,light,heavy};
        };
    }
    /** The phase a port draws at a vanishing flow: the first of its order {@code state} holds; -1 for a BULK end drawing the
     * bulk and for a state holding none of the three (then the port draws the bulk). The stream every reader of what a port
     * sends reads (a start flow's loss and velocity cap, a mover's suction, the mobility check): the priority stream's limit
     * as the flow goes to zero. What stands in the port's connection is its {@link #fill} (owner decision D34). A LIQUID
     * port holding both liquids has no one leading phase (-1 too): every reader asks {@link #mixedLiquids} first and reads
     * the {@link #mixedShares} stream there, never the bulk. */
    static int leading(FluidThermodynamics model,FluidThermodynamics.State state,PassiveNetwork.PhasePort port) {
        if(port==PassiveNetwork.PhasePort.LIQUID&&FluidThermodynamics.holdsPhase(state,OIL)&&FluidThermodynamics.holdsPhase(state,AQUEOUS))return -1;
        var order=order(model,state,port);if(order==null)return -1;
        for(int phase:order)if(FluidThermodynamics.holdsPhase(state,phase))return phase;
        return -1;
    }
    static boolean mixedLiquids(FluidThermodynamics.State state,PassiveNetwork.PhasePort port){
        return port==PassiveNetwork.PhasePort.LIQUID&&FluidThermodynamics.holdsPhase(state,OIL)&&FluidThermodynamics.holdsPhase(state,AQUEOUS);
    }
    /**
     * The volume shares (indexed by phase; the gas's 0) of the liquid a {@link #mixedLiquids} port draws at a vanishing
     * flow: what first fills {@code volume} of its connection (decision D34's reference) under the equal-volume draw. The
     * two liquids go in equal volumes until the smaller is spent and the larger takes the rest. When together they fill
     * less than the connection, both go in whole. So the stream is half and half while each liquid fills half the
     * connection, and it is continuous in either amount. Read as the rate's limit (always half and half), a 8.7e-24 m3 film
     * over 1 m3 of water drew a stream half oil, and the junction it fed seeded far from what any step delivers (seed 236).
     * Half and half when the connection has no volume.
     */
    static double[] mixedShares(FluidThermodynamics.State state,double volume){
        double[] shares=new double[3];
        if(!(volume>0)){shares[OIL]=shares[AQUEOUS]=.5;return shares;}
        double oil=FluidThermodynamics.phaseVolume(state,OIL),water=FluidThermodynamics.phaseVolume(state,AQUEOUS);
        double shared=Math.min(volume/2,Math.min(oil,water)),rest=volume-2*shared;
        double o=shared+Math.min(oil-shared,rest),w=shared+Math.min(water-shared,rest-(o-shared)),total=o+w;
        shares[OIL]=o/total;shares[AQUEOUS]=w/total;return shares;
    }
    /** Equal-volume column/sample, retaining the finite-inventory dilution of the existing column law. */
    static double[] fillMixed(FluidThermodynamics.State state,double volume,int ends){
        double[] result=new double[3];double oil=FluidThermodynamics.phaseVolume(state,OIL)/ends,water=FluidThermodynamics.phaseVolume(state,AQUEOUS)/ends;
        double shared=Math.min(volume/2,Math.min(oil,water));result[OIL]=shared;result[AQUEOUS]=shared;double remaining=volume-2*shared;
        for(int phase:new int[]{OIL,AQUEOUS,GAS}){
            if(!FluidThermodynamics.holdsPhase(state,phase))continue;
            double available=Math.max(0,FluidThermodynamics.phaseVolume(state,phase)/ends-result[phase]);
            double take=Math.min(remaining,available);result[phase]+=take;remaining-=take;
        }
        if(remaining>0){int last=FluidThermodynamics.holdsPhase(state,GAS)?GAS:oil>water?OIL:AQUEOUS;result[last]+=remaining;}
        return result;
    }
    /** Which of the three phases {@code state} holds. */
    static boolean[] held(FluidThermodynamics.State state) {
        return new boolean[]{FluidThermodynamics.holdsPhase(state,GAS),FluidThermodynamics.holdsPhase(state,OIL),FluidThermodynamics.holdsPhase(state,AQUEOUS)};
    }
    /**
     * A port's priority fill of {@code volume} (m3) from {@code state}: the volume of each phase stream that fills it, by
     * phase id ({@link FluidThermodynamics#phaseVolume}, a liquid with its share of the solids), in the port's
     * {@code order}: each phase the state holds up to its held volume over {@code ends}, the last one it holds taking the
     * rest, as the priority stream's last phase takes the rest of a flow. Null when the state holds none of the order's
     * phases. The one fill of both readers: the step's suction intake of a mover ({@link InletPhase}, its target volume
     * shared among the vessel's {@code ends} phase-port outlets) and the static column of a connection
     * ({@code PassiveStepSolver.columnDensity}, the connection's own volume, one end).
     */
    static double[] fill(FluidThermodynamics.State state,int[] order,double volume,int ends) {
        int last=-1;for(int phase:order)if(FluidThermodynamics.holdsPhase(state,phase))last=phase;
        if(last<0)return null;
        double[] filled=new double[3];double remaining=volume;
        for(int phase:order) {
            if(!FluidThermodynamics.holdsPhase(state,phase))continue;
            if(phase==last){filled[phase]=remaining;break;}
            double take=Math.min(remaining,FluidThermodynamics.phaseVolume(state,phase)/ends);
            filled[phase]=take;remaining-=take;
            if(!(remaining>0))break;
        }
        return filled;
    }

    /**
     * One port end's draw for one pass: its priority {@code order}, the per-phase {@code capacity} (kg/s, by phase id), the
     * phases {@code pinned} at their capacity (in order, drawn at the step's start state) and the {@code marginal} phase
     * (drawn at the end state) that carries the rest of the outflow; {@code marginal} is -1 when the seed holds no phase to
     * draw it from.
     */
    record Segment(int[] order,double[] capacity,int[] pinned,int marginal,boolean mixed) {
        Segment(int[] order,double[] capacity,int[] pinned,int marginal){this(order,capacity,pinned,marginal,false);}
        /** Equal volume-rate draw; one depleted liquid becomes pinned and the other takes the remainder. */
        static Segment mixed(double[] capacity,boolean[] held,boolean[] startHeld,double outflow,double oilDensity,double waterDensity) {
            int[] order={OIL,AQUEOUS,GAS};
            if(!held[OIL]||!held[AQUEOUS])return decide(order,capacity,held,startHeld,outflow);
            double sum=oilDensity+waterDensity,oilShare=oilDensity/sum,waterShare=waterDensity/sum;
            if(outflow*oilShare<=capacity[OIL]&&outflow*waterShare<=capacity[AQUEOUS])
                return new Segment(order,capacity,new int[0],OIL,true);
            int first=capacity[OIL]/oilShare<=capacity[AQUEOUS]/waterShare?OIL:AQUEOUS,second=first==OIL?AQUEOUS:OIL;
            return decide(new int[]{first,second,GAS},capacity,held,startHeld,outflow);
        }
        double[] rates(int marginal,double outflow,double oilDensity,double waterDensity) {
            if(!mixed||oilDensity<=0||waterDensity<=0)return rates(marginal,outflow);
            double[] result=new double[6];double total=oilDensity+waterDensity;
            result[OIL]=outflow*oilDensity/total;result[AQUEOUS]=outflow*waterDensity/total;return result;
        }
        /**
         * The segment an outflow {@code outflow} (kg/s; zero or negative is no outflow) lands in. Walk the order over the
         * phases with a positive capacity: a phase whose capacity the remaining flow exceeds is pinned, provided a later
         * phase of the order the seed holds can take the rest; the first phase the seed holds that covers the remainder
         * (or that nothing after it could relieve) is marginal. A phase the step's start holds ({@code startHeld}) and the
         * seed no longer holds is pinned whatever the remainder, provided a later phase can take the rest: the port draws it
         * first, so it is gone from the seed because the port drew it whole (W3b stage A: a bottom port's last 6.8 g of
         * water, skipped while its pump ran on the gas, stood as vapour supersaturated at 282 K). A phase with capacity the
         * start does not hold (a rate solve's, a kettle's boil-up) and the seed does not hold, which the remainder does not
         * exceed, is not drawn. With no such phase, the first phase after the pinned ones the seed holds is marginal (a
         * capacity of zero: a phase that appeared within the step), then any the seed holds; none, -1.
         */
        static Segment decide(int[] order,double[] capacity,boolean[] seedHeld,boolean[] startHeld,double outflow) {
            double remaining=Math.max(outflow,0);int[] pinned=new int[3];int count=0,marginal=-1,after=0;
            for(int i=0;i<order.length;i++) {
                int phase=order[i];if(!(capacity[phase]>0))continue;
                boolean relieved=false;for(int j=i+1;j<order.length;j++)relieved|=seedHeld[order[j]];
                if((remaining>capacity[phase]||!seedHeld[phase]&&startHeld[phase]&&Double.isFinite(capacity[phase]))&&relieved){pinned[count++]=phase;remaining=Math.max(0,remaining-capacity[phase]);after=i+1;continue;}
                if(seedHeld[phase]){marginal=phase;break;}
            }
            if(marginal<0)for(int i=after;i<order.length;i++)if(seedHeld[order[i]]){marginal=order[i];break;}
            if(marginal<0&&count==0)for(int phase:order)if(seedHeld[phase]){marginal=phase;break;}
            return new Segment(order,capacity,Arrays.copyOf(pinned,count),marginal);
        }
        /** A compact code of the pinned set and the marginal phase, for the pass loop's cycle key. */
        byte code() {
            int mask=0;for(int phase:pinned)mask|=1<<phase;
            return (byte)(marginal+1|mask<<2|(mixed?64:0));
        }
        boolean samePhases(Segment other){return mixed==other.mixed&&marginal==other.marginal&&Arrays.equals(pinned,other.pinned);}
        /** The pinned total, {@code C = sum c}. */
        double pinnedTotal() {
            double total=0;for(int phase:pinned)total+=capacity[phase];
            return total;
        }
        /**
         * The phase drawn at the end state from a state holding {@code held}: the marginal phase, or, if the state no
         * longer holds it (an iterate at which it vanished), the next phase of the order the state holds, else the
         * nearest earlier one that is not pinned; -1 when there is none (with pinned phases the pinned mixture then
         * carries the whole flow; without, the port draws the bulk). The same resolution serves the Newton's stream, the
         * reconstruction's booking and the transfer sample.
         */
        int marginalAt(boolean[] held) {
            if(marginal<0)return -1;
            int at=0;while(order[at]!=marginal)at++;
            for(int i=at;i<order.length;i++)if(held[order[i]])return order[i];
            for(int i=at-1;i>=0;i--){int phase=order[i];if(held[phase]&&Arrays.stream(pinned).noneMatch(p->p==phase))return phase;}
            return -1;
        }
        /**
         * The mass flow (kg/s) each phase supplies to an outflow {@code outflow}, as {@code [end-state rates by phase id,
         * start-state rates by phase id]} (six entries): the pinned phases their capacity at the start state and the
         * marginal phase {@code marginal} (from {@link #marginalAt}) the rest at the end state; below the pinned total (an
         * iterate or a point the segment does not describe), or with no marginal phase, the pinned phases in proportion to
         * their capacities and the marginal one nothing, which is continuous with the above at the pinned total and at zero
         * flow. They add up to {@code outflow}.
         */
        double[] rates(int marginal,double outflow) {
            double[] rates=new double[6];double total=pinnedTotal();
            if(pinned.length==0){if(marginal>=0)rates[marginal]=outflow;return rates;}
            if(marginal>=0&&outflow>=total){for(int phase:pinned)rates[3+phase]=capacity[phase];rates[marginal]=outflow-total;}
            else for(int phase:pinned)rates[3+phase]=capacity[phase]*(outflow/total);
            return rates;
        }
    }
}
