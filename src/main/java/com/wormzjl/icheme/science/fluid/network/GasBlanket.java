package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;

import java.util.ArrayList;
import java.util.List;

import static com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics.GAS;

/**
 * A gas blanketing valve on a closed vessel (batch 2026-11-11-gas-blanketing; owner, 2026-11-11: "Keep the drum pressure to
 * set value, pressure too high -> release fluid (gas), pressure too low -> introduce gas"; "need to be connected to a gas
 * source through piping, vent to atmosphere"). The vessel's set pressure is {@link PassiveNetwork.Inventory#blanket}.
 * Both ends of the band follow one law ({@link #gap}; batch 2026-11-11-valve-followups, item 2): from the step's start
 * state, the gas the vessel lacks (pad) or holds in excess (vent) at the band's pressure, closed over the valve's response
 * time ({@link OpenVessel#RELEASE_TIME}), and the gas for the room its liquid vacates (pad) or takes (vent) over the step at
 * the rate of the step before ({@link #inflows}), in full: a draining vessel is padded and a filling one vented as its
 * liquid moves, not a step later.
 * <ul>
 * <li><b>Pad.</b> The run into the vessel's blanket gas port carries the valve ({@link FlowControl.ValveSource#BLANKET}).
 * Each step of the interval the valve regulates the mass flow of the gas the law asks for at the set pressure ({@link
 * #plan}, in the interval solver's planning stage, as a fired coil's firing is). The gas is the supply line's: a valve
 * nothing feeds passes nothing, and the vessel stands where it is.</li>
 * <li><b>Vent.</b> A vessel gives the atmosphere the gas the law finds in excess at {@link #ventPressure}, as an open vessel
 * vents ({@link OpenVessel}): a share of its start gas, no faster than {@link OpenVessel#VENT_SPEED}, a constant term of its
 * balance rows, booked as a boundary transfer of kind {@code VENT}. It vents whether or not a supply line is connected.</li>
 * </ul>
 * The band between the two keeps the valve from padding and venting in one step: both are stated from start states.
 */
public final class GasBlanket {
    private GasBlanket() {}
    /** The share of the set pressure above it at which the valve vents. */
    public static final double BAND=.05;
    /**
     * The least gas room the pad fills, as a share of the vessel's volume: a vessel full of liquid has no room, and without
     * this floor its valve would never open, so the liquid could never leave it.
     */
    public static final double ROOM=.01;
    /**
     * The ratio of heat capacities both laws allow for (a diatomic gas's). Gas that enters a rigid vessel warms what it
     * compresses, and gas that leaves cools what stays, so a pad or a vent sized at constant temperature overshoots its
     * pressure by up to this ratio and the other law would answer it in the next slice. Each law moves its share over this
     * ratio: it never passes its pressure, and what is left closes in the following slices.
     */
    public static final double GAMMA=1.4;

    /** The share of the gas a vessel holds at its set pressure below which it lacks none: the valve then asks for nothing, and an island its vessel stands in can rest. */
    public static final double RESOLUTION=1e-6;

    /** The pressure above which a vessel blanketed at {@code set} vents, Pa. */
    public static double ventPressure(double set){return set*(1+BAND);}
    /** Whether {@code node} is a vessel with a gas blanketing valve. */
    static boolean fitted(PassiveNetwork.Reservoir node){return node.kind()==PassiveNetwork.NodeKind.RESERVOIR&&node.inventory().blanket()>0;}
    /** Whether {@code state} of a fitted vessel holds gas the laws can read. */
    private static boolean gas(PassiveNetwork.Reservoir node) {
        return !node.empty()&&!PassiveStepSolver.holdsNoFluid(node)&&FluidThermodynamics.holdsPhase(node.state(),GAS)&&node.state().vaporVolume()>0;
    }
    /**
     * The law of both ends of the band: the gas, mol, fitted vessel {@code node} lacks at pressure {@code reference} ({@code
     * sign} -1, the pad) or holds in excess of it ({@code sign} +1, the vent) over a step in which its condensed phases gain
     * {@code inflow} m3 (negative when they leave): the share {@code response} of what it lacks or holds in excess now, over
     * {@link #GAMMA}, and the gas at {@code reference} of the room its liquid vacates (pad) or takes (vent), in full: gas that
     * follows a leaving liquid, or leaves ahead of an entering one, compresses nothing. A gap on the other side of the
     * reference counts in full against that room: only what the step's end would pass the reference by is moved. The room is
     * what its condensed phases leave, at least {@link #ROOM} of its volume. The gas it holds ({@code gas}; else none) is
     * scaled to {@code reference} as an ideal gas at its temperature; a vessel without gas is filled as an ideal gas at its
     * temperature. 0 below the {@link #RESOLUTION} of the whole gap.
     */
    private static double gap(PassiveNetwork.Reservoir node,double inflow,double response,double reference,int sign,boolean gas) {
        var inventory=node.inventory();var state=node.state();
        boolean fluid=!node.empty()&&!PassiveStepSolver.holdsNoFluid(node);
        double free=inventory.volume()-(fluid?OpenVessel.condensedVolume(state):0),least=ROOM*inventory.volume();
        double room=Math.max(free,least),after=Math.min(inventory.volume(),Math.max(free-inflow,least));
        double held=0;if(gas)for(double n:FluidThermodynamics.phaseMoles(state,GAS))held+=n;
        double density=gas?held/state.vaporVolume()*reference/state.pressure():reference/(FluidThermodynamics.R*state.temperature());
        double now=sign*(held-density*room),taken=sign*density*(room-after),moles=(now>0?response*now/GAMMA:now)+taken;
        // Below the resolution of the whole gap there is none: a short step's share of a real gap still counts.
        return now+taken>RESOLUTION*density*room&&moles>0?moles:0;
    }
    /** The gas fitted vessel {@code node} vents over a step whose liquid gains {@code inflow} m3, at response share {@code response}, mol: {@link #gap} at its vent pressure; 0 for a bag (it stands at the ambient) and a vessel without gas. */
    private static double excess(PassiveNetwork.Reservoir node,double inflow,double response) {
        if(!fitted(node)||!node.inventory().rigid()||!gas(node))return 0;
        return gap(node,inflow,response,ventPressure(node.inventory().blanket()),1,true);
    }
    /** Whether a fitted vessel whose liquid stands has gas to give the atmosphere: such a vessel is never at rest ({@link NetworkReduction#atRest}). */
    static boolean venting(PassiveNetwork.Reservoir node){return excess(node,0,1)>0;}

    /**
     * What fitted vessel {@code node} vents over a step of {@code dt} s from its start state, its condensed phases gaining
     * {@code inflow} m3 over the step: its {@link #excess} with the response share {@link OpenVessel#releaseShare}, released
     * as an open vessel's vent, no faster than {@link OpenVessel#VENT_SPEED} through its footprint. Null when it vents nothing.
     */
    static OpenVessel.Releases releases(FluidThermodynamics model,PassiveNetwork.Reservoir node,double dt,double inflow) {
        double excess=excess(node,inflow,OpenVessel.releaseShare(dt));if(!(excess>0))return null;
        var state=node.state();var held=node.inventory().moles();
        double gas=0;for(double n:FluidThermodynamics.phaseMoles(state,GAS))gas+=n;
        double share=Math.min(Math.min(1,excess/gas),OpenVessel.VENT_SPEED*OpenVessel.footprint(node.inventory())*dt/state.vaporVolume());
        if(!(share>0))return null;
        var n=FluidThermodynamics.phaseMoles(state,GAS);
        for(int c=0;c<n.length;c++)n[c]=Math.min(share*n[c],held[c]);
        double energy=share*model.phaseMass(state,GAS)*model.phaseSpecificEnthalpy(state,GAS,null);
        return new OpenVessel.Releases(new OpenVessel.Release(n,energy,SolidInventory.EMPTY,SolidInventory.Moments.ZERO),null,share,0);
    }

    /**
     * The gas the pad brings fitted vessel {@code node} over a step, mol, when its condensed phases gain {@code inflow} m3
     * over that step (negative when they leave): {@link #gap} at its set pressure. 0 for a rigid vessel at or above its set
     * pressure whose liquid does not leave (one full of liquid included).
     */
    static double deficit(PassiveNetwork.Reservoir node,double inflow,double response) {
        if(!fitted(node))return 0;
        double set=node.inventory().blanket();
        boolean fluid=!node.empty()&&!PassiveStepSolver.holdsNoFluid(node);
        if(node.inventory().rigid()&&fluid&&node.state().pressure()>=set&&!(inflow<0))return 0;
        return gap(node,inflow,response,set,-1,gas(node));
    }
    /** The gas a fitted vessel whose liquid stands lacks at its set pressure, over {@link #GAMMA}, mol: what its valve asks for, so it is not at rest while its supply can bring it ({@link NetworkReduction#atRest}). */
    static double deficit(PassiveNetwork.Reservoir node){return deficit(node,0,1);}
    /**
     * The condensed volume (both liquids) that entered each fitted vessel of {@code graph} through its pipes over the step
     * whose transfers are {@code last}, {@code seconds} s long, less what left, m3/s by vessel identity: the rate both ends
     * of the band forecast the next step at. Empty without a step before (an interval's first step) or a fitted vessel.
     */
    public static java.util.Map<Long,Double> inflows(PassiveNetwork graph,List<PipeTransfer> last,double seconds) {
        if(last==null||!(seconds>0))return java.util.Map.of();
        java.util.Map<Long,Double> rates=null;
        for(int node=0;node<graph.reservoirs().size();node++) {
            if(!fitted(graph.reservoirs().get(node)))continue;
            double volume=0;
            for(var transfer:last)for(var pipe:graph.pipes()) {
                if(pipe.id()!=transfer.pipeId())continue;
                if(pipe.first()!=node&&pipe.second()!=node)break;
                var in=pipe.second()==node?transfer.forward():transfer.reverse();var out=pipe.second()==node?transfer.reverse():transfer.forward();
                volume+=in.phaseVolumes()[0]+in.phaseVolumes()[1]-out.phaseVolumes()[0]-out.phaseVolumes()[1];
                break;
            }
            if(volume!=0){if(rates==null)rates=new java.util.HashMap<>();rates.put(graph.reservoirs().get(node).id(),volume/seconds);}
        }
        return rates==null?java.util.Map.of():rates;
    }
    /** The mean molar mass of what {@code donor} gives a gas line, kg/mol: its gas's, or its whole contents' when it holds none. */
    private static double molarMass(FluidThermodynamics model,PassiveNetwork.Reservoir donor) {
        var state=donor.state();
        var n=FluidThermodynamics.holdsPhase(state,GAS)?FluidThermodynamics.phaseMoles(state,GAS):PhaseLayout.totalAmounts(state);
        double moles=0,mass=0;for(int c=0;c<n.length;c++){moles+=n[c];mass+=n[c]*model.molecularWeight(c);}
        return moles>0?mass/moles:.028;
    }

    /** {@link #plan(PassiveNetwork, FluidThermodynamics, double, java.util.Map)} of an interval's first step: every vessel's liquid stands. */
    public static PassiveNetwork plan(PassiveNetwork graph,FluidThermodynamics model,double seconds){return plan(graph,model,seconds,java.util.Map.of());}
    /**
     * {@code graph} with each gas blanketing valve at its plan for a step of {@code seconds} s: the mass flow that brings
     * its vessel's {@link #deficit} over the step, in the supply's gas, its liquid moving at {@code inflows} (m3/s by vessel
     * identity, {@link #inflows}; absent: it stands). A target inside {@link PassiveStepSolver#VALVE_DEADBAND} of the
     * valve's own keeps the valve's, and 0 is exact, as for every planned valve. Pure: no solve, no state.
     */
    public static PassiveNetwork plan(PassiveNetwork graph,FluidThermodynamics model,double seconds,java.util.Map<Long,Double> inflows) {
        List<PassiveNetwork.Pipe> pipes=null;
        for(int e=0;e<graph.pipes().size();e++) {
            var pipe=graph.pipes().get(e);
            if(!(pipe.control() instanceof FlowControl.ControlValve valve)||valve.source()!=FlowControl.ValveSource.BLANKET)continue;
            var vessel=graph.reservoirs().get(pipe.second());
            double moles=seconds>0?deficit(vessel,inflows.getOrDefault(vessel.id(),0.0)*seconds,OpenVessel.releaseShare(seconds)):0;
            double target=moles>0&&seconds>0?moles*molarMass(model,graph.reservoirs().get(pipe.first()))/seconds:0;
            if((target>0)==(valve.massTarget()>0)&&Math.abs(target-valve.massTarget())<=PassiveStepSolver.VALVE_DEADBAND*Math.max(target,valve.massTarget())+1e-9)continue;
            if(pipes==null)pipes=new ArrayList<>(graph.pipes());
            pipes.set(e,pipe.withControl(valve.withMassTarget(target)));
        }
        return pipes==null?graph:new PassiveNetwork(graph.reservoirs(),pipes,graph.scheduledTransfers());
    }
}
