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
 * <ul>
 * <li><b>Pad.</b> The run into the vessel's blanket gas port carries the valve ({@link FlowControl.ValveSource#BLANKET}).
 * Each step of the interval the valve regulates the mass flow that brings the gas the vessel lacks at its set pressure
 * ({@link #plan}, from the step's start state, in the interval solver's planning stage, as a fired coil's firing is): the
 * gas that fills the room its condensed phases leave, at the set pressure, less the gas it holds, closed over the valve's
 * response time ({@link OpenVessel#RELEASE_TIME}, the vent's); and the gas for the room its liquid vacates over the step
 * at the rate of the step before ({@link #condensedInflow}), so a draining vessel is padded as it drains, not a step
 * later. A vessel at or above its set pressure whose liquid is not leaving asks for nothing. The gas is the supply
 * line's: a valve nothing feeds passes nothing, and the vessel stands where it is.</li>
 * <li><b>Vent.</b> A vessel above {@link #ventPressure} gives the atmosphere the gas it holds in excess of that pressure, as
 * an open vessel vents ({@link OpenVessel}): the share of its start gas over {@link OpenVessel#RELEASE_TIME}, a constant
 * term of its balance rows, booked as a boundary transfer of kind {@code VENT}. It vents whether or not a supply line is
 * connected.</li>
 * </ul>
 * The band between the two keeps the valve from padding and venting in one step: both laws are stated from start states.
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
    /** The share of its gas a fitted vessel vents: what it holds above its vent pressure, over {@link #GAMMA}; 0 for a bag (it stands at the ambient) and at or below the vent pressure. */
    private static double excess(PassiveNetwork.Reservoir node) {
        if(!fitted(node)||!node.inventory().rigid()||!gas(node))return 0;
        double vent=ventPressure(node.inventory().blanket()),pressure=node.state().pressure();
        return pressure>vent?(1-vent/pressure)/GAMMA:0;
    }
    /** Whether a fitted vessel has gas to give the atmosphere: such a vessel is never at rest ({@link NetworkReduction#atRest}). */
    static boolean venting(PassiveNetwork.Reservoir node){return excess(node)>0;}

    /**
     * What fitted vessel {@code node} vents over a step of {@code dt} s from its start state: {@link #excess} of its gas,
     * released as an open vessel's ({@link OpenVessel#ventShare}). Null when it vents nothing.
     */
    static OpenVessel.Releases releases(FluidThermodynamics model,PassiveNetwork.Reservoir node,double dt) {
        double excess=excess(node);if(!(excess>0))return null;
        var state=node.state();var held=node.inventory().moles();
        double share=excess*OpenVessel.ventShare(node.inventory(),state.vaporVolume(),dt);
        if(!(share>0))return null;
        var n=FluidThermodynamics.phaseMoles(state,GAS);
        for(int c=0;c<n.length;c++)n[c]=Math.min(share*n[c],held[c]);
        double energy=share*model.phaseMass(state,GAS)*model.phaseSpecificEnthalpy(state,GAS,null);
        return new OpenVessel.Releases(new OpenVessel.Release(n,energy,SolidInventory.EMPTY,SolidInventory.Moments.ZERO),null,share,0);
    }

    /**
     * The gas the pad brings fitted vessel {@code node} over a step, mol, when its condensed phases gain {@code inflow} m3
     * over that step (negative when they leave): the share {@code response} of the gas it lacks now at its set pressure,
     * over {@link #GAMMA}, and the gas that fills the room its liquid vacates over the step (less what the liquid entering
     * it displaces), in full: gas that follows a leaving liquid compresses nothing. The room is what its condensed phases
     * leave, at least {@link #ROOM} of its volume. The gas it holds is scaled to the set pressure as an ideal gas at its
     * temperature; a vessel without gas is filled as an ideal gas at its temperature. 0 for a rigid vessel at or above its
     * set pressure whose room does not grow.
     */
    static double deficit(PassiveNetwork.Reservoir node,double inflow,double response) {
        if(!fitted(node))return 0;
        var inventory=node.inventory();var state=node.state();double set=inventory.blanket();
        boolean fluid=!node.empty()&&!PassiveStepSolver.holdsNoFluid(node),gas=gas(node);
        if(inventory.rigid()&&fluid&&state.pressure()>=set&&!(inflow<0))return 0;
        double free=inventory.volume()-(fluid?OpenVessel.condensedVolume(state):0),least=ROOM*inventory.volume();
        double room=Math.max(free,least),after=Math.min(inventory.volume(),Math.max(free-inflow,least));
        double held=0;if(gas)for(double n:FluidThermodynamics.phaseMoles(state,GAS))held+=n;
        double density=gas?held/state.vaporVolume()*set/state.pressure():set/(FluidThermodynamics.R*state.temperature());
        double lack=density*room-held,moles=(lack>0?response*lack/GAMMA:lack)+density*(after-room);
        // Below the resolution of what it lacks in all, it lacks nothing: a short step's share of a real lack still counts.
        return lack+density*(after-room)>RESOLUTION*density*room&&moles>0?moles:0;
    }
    /** The gas a fitted vessel whose liquid stands lacks at its set pressure, over {@link #GAMMA}, mol: what its valve asks for, so it is not at rest while its supply can bring it ({@link NetworkReduction#atRest}). */
    static double deficit(PassiveNetwork.Reservoir node){return deficit(node,0,1);}
    /**
     * The condensed volume (both liquids) that entered node {@code node} of {@code graph} through its pipes over the step
     * whose transfers are {@code last}, {@code seconds} s long, less what left, m3/s; 0 without one (an interval's first
     * step).
     */
    static double condensedInflow(PassiveNetwork graph,int node,List<PipeTransfer> last,double seconds) {
        if(last==null||!(seconds>0))return 0;
        double volume=0;
        for(var transfer:last)for(var pipe:graph.pipes()) {
            if(pipe.id()!=transfer.pipeId())continue;
            if(pipe.first()!=node&&pipe.second()!=node)break;
            var in=pipe.second()==node?transfer.forward():transfer.reverse();var out=pipe.second()==node?transfer.reverse():transfer.forward();
            volume+=in.phaseVolumes()[0]+in.phaseVolumes()[1]-out.phaseVolumes()[0]-out.phaseVolumes()[1];
            break;
        }
        return volume/seconds;
    }
    /** The mean molar mass of what {@code donor} gives a gas line, kg/mol: its gas's, or its whole contents' when it holds none. */
    private static double molarMass(FluidThermodynamics model,PassiveNetwork.Reservoir donor) {
        var state=donor.state();
        var n=FluidThermodynamics.holdsPhase(state,GAS)?FluidThermodynamics.phaseMoles(state,GAS):PhaseLayout.totalAmounts(state);
        double moles=0,mass=0;for(int c=0;c<n.length;c++){moles+=n[c];mass+=n[c]*model.molecularWeight(c);}
        return moles>0?mass/moles:.028;
    }

    /** {@link #plan(PassiveNetwork, FluidThermodynamics, double, List, double)} of an interval's first step: every vessel's liquid stands. */
    public static PassiveNetwork plan(PassiveNetwork graph,FluidThermodynamics model,double seconds){return plan(graph,model,seconds,null,0);}
    /**
     * {@code graph} with each gas blanketing valve at its plan for a step of {@code seconds} s: the mass flow that brings
     * its vessel's {@link #deficit} over the step, in the supply's gas, its liquid moving at the rate of the step before
     * (its transfers {@code last} over {@code lastSeconds} s; null at an interval's first step). A target inside {@link
     * PassiveStepSolver#VALVE_DEADBAND} of the valve's own keeps the valve's, and 0 is exact, as for every planned valve.
     * Pure: no solve, no state.
     */
    public static PassiveNetwork plan(PassiveNetwork graph,FluidThermodynamics model,double seconds,List<PipeTransfer> last,double lastSeconds) {
        List<PassiveNetwork.Pipe> pipes=null;
        for(int e=0;e<graph.pipes().size();e++) {
            var pipe=graph.pipes().get(e);
            if(!(pipe.control() instanceof FlowControl.ControlValve valve)||valve.source()!=FlowControl.ValveSource.BLANKET)continue;
            double moles=seconds>0?deficit(graph.reservoirs().get(pipe.second()),condensedInflow(graph,pipe.second(),last,lastSeconds)*seconds,OpenVessel.releaseShare(seconds)):0;
            double target=moles>0&&seconds>0?moles*molarMass(model,graph.reservoirs().get(pipe.first()))/seconds:0;
            if((target>0)==(valve.massTarget()>0)&&Math.abs(target-valve.massTarget())<=PassiveStepSolver.VALVE_DEADBAND*Math.max(target,valve.massTarget())+1e-9)continue;
            if(pipes==null)pipes=new ArrayList<>(graph.pipes());
            pipes.set(e,pipe.withControl(valve.withMassTarget(target)));
        }
        return pipes==null?graph:new PassiveNetwork(graph.reservoirs(),pipes,graph.scheduledTransfers());
    }
}
