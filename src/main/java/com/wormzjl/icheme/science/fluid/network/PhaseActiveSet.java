package com.wormzjl.icheme.science.fluid.network;

import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.holdsNoFluid;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.phaseCode;
import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.icheme.science.fluid.solver.StepPhaseRules;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import java.util.*;

/**
 * The phase active set of a step solve's passes: the outer phase check of a converged point ({@link #phaseCorrection},
 * a vessel's own split {@link #vesselSplit}) and of a failed pass's iterate ({@link #foldedIterate}), the seeds' trace
 * folds ({@link #foldedSeeds}) and the reconstruction's ({@link #phaseFolded}, {@link #recordFolds},
 * {@link #phaseAppeared}), a rigid vessel flashed at its volume on a failed Newton ({@link #flashes}), the vessels a
 * pass runs dry ({@link #emptyingGraph}, {@link #emptyings}, {@link #stockSeeds}, {@link #keptEmptying}) and the
 * vessels it lays out dry ({@link #anyDry}, {@link #filled}). One per solver ({@code PassiveStepSolver.phaseSet}), built
 * with it; it reads the solve's input states through {@code solver} and holds no state of its own: the pass's emptying
 * vessels and folded phases are the solve's (S-01, batch 2026-11-13-codebase-review, D-S5: moved out of the solver with
 * every body unchanged but its qualifiers).
 */
final class PhaseActiveSet {
    /** The solver whose passes these are: its solve constants (the input states) and its molar masses. */
    private final PassiveStepSolver solver;
    /** The solver's thermodynamics, the same object. */
    private final FluidThermodynamics model;
    PhaseActiveSet(PassiveStepSolver solver,FluidThermodynamics model){this.solver=solver;this.model=model;}
    /**
     * Batch 2026-11-10-rigid-reservoir (RIGID_RESERVOIR_PLAN.md 4.2) on a failed Newton: a rigid vessel whose seed holds no gas
     * cannot give up volume as a liquid alone below its vapour pressure (a liquid-full tank drained below its bubble point has
     * no liquid-only root). Each such vessel whose iterate holds less than its seed, or none the network can state, is seeded
     * at the engine's fixed-volume equilibrium of the iterate's amounts and energy, once per solve ({@code flashed}); null
     * where none is. The seed list is otherwise the pass's own.
     */
    List<FluidThermodynamics.State> flashes(StepEquations equations,double[] last,List<FluidThermodynamics.State> seeds,boolean[] flashed,Runnable checkpoint) {
        List<FluidThermodynamics.State> states;
        try{states=equations.states(last);}catch(IllegalArgumentException outsideDomain){return null;}
        List<FluidThermodynamics.State> result=null;
        for(int i=0;i<seeds.size();i++) {
            var node=equations.graph.reservoirs().get(i);var seed=seeds.get(i);
            if(flashed[i]||node.kind()!=PassiveNetwork.NodeKind.RESERVOIR||!node.inventory().rigid()||holdsNoFluid(node)||FluidThermodynamics.holdsPhase(seed,FluidThermodynamics.GAS))continue;
            var at=states.get(i);if(!(at.pressure()<seed.pressure()))continue;
            FluidThermodynamics.State own;
            try{own=model.equilibriumAtVolume(at.temperature(),at.pressure(),at.internalEnergy()-at.solidMoments().internalEnergy(at.temperature()),node.inventory().volume()-at.solidMoments().volume(),PhaseLayout.totalAmounts(at),checkpoint);}
            catch(IllegalArgumentException refused){continue;}
            if(own==null||!FluidThermodynamics.holdsPhase(own,FluidThermodynamics.GAS))continue;
            flashed[i]=true;if(result==null)result=new ArrayList<>(seeds);
            result.set(i,model.folded(own.withSolidState(seed.solids(),seed.solidMoments()),false,true));
        }
        return result;
    }
    /**
     * The outer phase check of a converged pass's point, node by node ({@link StepPhaseRules#phaseCheck}, owner
     * decisions D25 and D26): a phase leaves a node only when the solve empties it (the trace fold, which the
     * reconstruction has applied, and the vanishing rule of D38 C: under {@link FluidThermodynamics#VANISHING_SHARE} of the
     * node's fluid and at a smaller share than at the step's start, the solve's input states, {@link PassiveStepSolver.SolveConstants}), a phase the node's TP answer
     * holds and the node does not is added at the node's own split (a junction's PH answer, a vessel's closure,
     * {@link #vesselSplit}), and no populated phase set is swapped for another on a TP verdict. Never inside a residual or
     * Jacobian evaluation. One node per call, as one active-set change per pass; null when none changes.
     *
     * <p>{@code prepared}, when given, is the pass's per-node prepared temperature state (a flash at exactly that
     * temperature reuses its workspace). {@code foldedPhases}, when given, holds per node the phases ({@link PassiveStepSolver#phaseCode}
     * bits) the trace rule removed in this solve and those an own split refused (A85, the D26 amendment): the check does
     * not add them back and records there what its fold removes and its own split refuses, so the pass sequence stays
     * finite. A failed pass's last iterate is not checked here: it is only folded ({@link #foldedIterate}) and read for
     * whether it refutes a phase this check added ({@link PassiveStepSolver.Proposal}).
     *
     * <p>The nodes are checked from the lowest pressure up, so a run of junctions that must flash flashes from its
     * downstream end. A liquid stream reaching a junction that already holds vapour has a root there; a two-phase stream
     * reaching a junction laid out as a liquid below its bubble point has none (cold fuzz seed 757: vapour proposed at a
     * junction at 563 kPa, 24 % of its moles at 138 K, fed a liquid-only junction at 484 kPa and 162 K, and the pass
     * stalled on that junction's rows).
     */
    List<FluidThermodynamics.State> phaseCorrection(PassiveNetwork graph,List<FluidThermodynamics.State> states,Runnable checkpoint,FluidThermodynamics.Prepared[] prepared,int[] foldedPhases) {
        Integer[] order=new Integer[states.size()];for(int i=0;i<order.length;i++)order[i]=i;
        Arrays.sort(order,(a,b)->{int c=Double.compare(states.get(a).pressure(),states.get(b).pressure());return c!=0?c:Integer.compare(a,b);});
        for(int i:order) {
            var state=states.get(i);var node=graph.reservoirs().get(i);if(node.fixed())continue;
            FluidThermodynamics.State checked;
            try{checked=StepPhaseRules.phaseCheck(model,state,node.junction(),solver.constants.inputStates.get(i),foldedPhases,i,prepared==null?null:prepared[i],
                    node.junction()?null:start->vesselSplit(node,state,start,checkpoint),!node.junction()&&node.inventory().rigid(),!node.junction()&&node.inventory().open(),checkpoint);}
            catch(com.wormzjl.icheme.science.fluid.thermo.ThermoHold hold){throw hold.at(node.id());}
            if(checked==state)continue;
            var corrected=new ArrayList<>(states);corrected.set(i,checked);return corrected;
        }
        return null;
    }
    /** Whether {@code node} is a vessel whose wall is rigid (batch 2026-11-10-rigid-reservoir): its gas is judged by volume. */
    static boolean rigid(PassiveNetwork.Reservoir node){return node.kind()==PassiveNetwork.NodeKind.RESERVOIR&&!node.junction()&&node.inventory()!=null&&node.inventory().rigid();}
    /**
     * A failed iterate can identify a vanishing phase (owner decisions D28 A' and D39). Fold that phase on the previous
     * seed so the next pass keeps its pressure, temperature and conserved amounts. Change one node per call.
     */
    List<FluidThermodynamics.State> foldedIterate(PassiveNetwork graph,List<FluidThermodynamics.State> seeds,
                                                  List<FluidThermodynamics.State> iterate) {
        for(int i=0;i<iterate.size();i++) {
            var node=graph.reservoirs().get(i);
            if(node.fixed())continue;
            var trial=iterate.get(i);
            var folded=model.folded(trial,node.junction(),rigid(node),solver.constants.inputStates.get(i));
            int removed=FluidThermodynamics.phaseBits(trial)&~FluidThermodynamics.phaseBits(folded);
            if(removed==0)continue;
            var seed=seeds.get(i);
            var next=model.folded(seed,rigid(node),removed);
            if(next==seed)continue;
            var corrected=new ArrayList<>(seeds);
            corrected.set(i,next);
            return corrected;
        }
        return null;
    }
    /**
     * Owner decision D26: a vessel's own split at a pass's point {@code state} ({@link InventoryEquilibrium#split}), from
     * {@code start}: the closure of what the point holds, its amounts and internal energy, the wall's work booked from its
     * bag volume, so the equilibrium of its energy and volume while the wall is taut and of its enthalpy at the ambient
     * pressure while slack; {@code null} where that closure fails.
     */
    private FluidThermodynamics.State vesselSplit(PassiveNetwork.Reservoir node,FluidThermodynamics.State state,FluidThermodynamics.State start,Runnable checkpoint) {
        double capacity=node.inventory().wallCapacity();
        return InventoryEquilibrium.split(model,start,node.inventory().with(PhaseLayout.totalAmounts(state),state.internalEnergy(),state.solids()),
                PhaseLayout.bagVolume(state,capacity),checkpoint);
    }
    /**
     * The pass's seeds with their trace phases folded ({@code FluidThermodynamics.folded}, decision D34); the list itself
     * when no seed holds one. Fixed nodes carry no unknowns and keep their boundary state.
     */
    List<FluidThermodynamics.State> foldedSeeds(PassiveNetwork graph,List<FluidThermodynamics.State> seeds) {
        List<FluidThermodynamics.State> result=null;
        for(int i=0;i<seeds.size();i++) {
            if(graph.reservoirs().get(i).fixed())continue;
            var seed=seeds.get(i);FluidThermodynamics.State folded;
            try{folded=model.folded(seed,graph.reservoirs().get(i).junction(),rigid(graph.reservoirs().get(i)));}
            catch(com.wormzjl.icheme.science.fluid.thermo.ThermoHold hold){throw hold.at(graph.reservoirs().get(i).id());}
            if(folded==seed)continue;
            if(result==null)result=new ArrayList<>(seeds);
            result.set(i,folded);
        }
        return result==null?seeds:result;
    }
    /** {@link #phaseFolded}, recording the folded phases ({@link PassiveStepSolver#phaseCode} bits) of each node in {@code foldedPhases}. */
    static boolean recordFolds(PassiveNetwork graph,List<FluidThermodynamics.State> solved,List<FluidThermodynamics.State> reconstructed,int[] foldedPhases,boolean[] dry) {
        if(!phaseFolded(graph,solved,reconstructed,dry))return false;
        for(int i=0;i<solved.size();i++)if(!graph.reservoirs().get(i).fixed()&&!dry[i])foldedPhases[i]|=phaseCode(solved.get(i))&~phaseCode(reconstructed.get(i))&7;
        return true;
    }
    /**
     * Whether the reconstruction folded away a phase the pass solved (decision D34): a node's restated state lacks a
     * hydrocarbon liquid, free water or gas phase its Newton state holds, or its gas lost its water vapour (a carried gas,
     * decision D41). The restatement ({@code ConservativeTransport.repartition}) keeps every phase whose booked amounts are
     * not all zero, so a phase it drops is a phase it folded.
     *
     * <p>A vessel the pass laid out dry ({@code dry}, decision D54) solved no phase: its entry in {@code solved} is its
     * placeholder, the ambient nitrogen charge of an empty vessel, not a Newton state. Liquid water booked into it gives a
     * restated state with no gas, which is no fold of the placeholder's: {@link #filled} alone lays a dry vessel out, at
     * the bag's resolution. Before, any such booking solved the pass again with the vessel laid out on it, 1.4e-30 mol
     * in defaults fuzz seed 358, whose temperature column was zero and its Jacobian singular.</p>
     */
    static boolean phaseFolded(PassiveNetwork graph,List<FluidThermodynamics.State> solved,List<FluidThermodynamics.State> reconstructed,boolean[] dry) {
        for(int i=0;i<solved.size();i++) {
            if(graph.reservoirs().get(i).fixed()||dry[i])continue;
            var before=solved.get(i);var after=reconstructed.get(i);
            if((PhaseLayout.activeBits(before)&~PhaseLayout.activeBits(after))!=0)return true;
        }
        return false;
    }
    /**
     * Whether the reconstruction gave a fluid to a node whose solved state holds none (hold class E3): a compound the
     * solved state has none of, booked as at least the trace share of the node's booked total. A smaller share is a
     * trace the next pass would omit again ({@code PhaseLayout.support}), so it is the node's state, not an appearance.
     * Only a node whose state holds solids alone reaches the share test. A junction always holds fluid, and so does a
     * dry vessel: its solved state is its placeholder, the ambient nitrogen charge, so what is booked into a dry vessel
     * is judged by {@link #filled} alone.
     */
    static boolean phaseAppeared(PassiveNetwork graph,List<FluidThermodynamics.State> solved,List<FluidThermodynamics.State> reconstructed) {
        for(int i=0;i<solved.size();i++) {
            var before=solved.get(i);var after=reconstructed.get(i);
            if(FluidThermodynamics.holdsFluid(before)||!FluidThermodynamics.holdsFluid(after))continue;
            double[] held=PhaseLayout.totalAmounts(before),booked=PhaseLayout.totalAmounts(after);double total=PhaseLayout.sum(booked);
            for(int c=0;c<held.length;c++)if(held[c]==0&&booked[c]>=PassiveIntervalSolver.TRACE_FLUSH_FRACTION*total)return true;
        }
        return false;
    }
    /**
     * Decision D54 (a bag can be emptied): {@code graph} with every vessel that runs dry within the step ({@code emptying})
     * standing as a junction that holds its stock. A vessel drained to nothing has no state of its own to solve for at the
     * step's end; what leaves it is what it held and what reached it, mixed (the junction's backward-Euler rows with the
     * stock as their held term), at the pressure its outlets drain it at, at most the ambient. The junction's buffer is
     * the vessel's inventory, its energy field the vessel's energy plus the ambient's work on its collapsing bag,
     * {@code U + P_ambient V_bag}; its continuity row gains the stock's mass over the step ({@link #emptyings}), and its
     * ports draw the bulk. The vessel's own record is untouched: the reconstruction books it to nothing
     * ({@link ConservativeTransport.Emptying}).
     */
    static PassiveNetwork emptyingGraph(PassiveNetwork graph,boolean[] emptying) {
        var nodes=new ArrayList<>(graph.reservoirs());
        for(int i=0;i<emptying.length;i++)if(emptying[i]) {
            var node=graph.reservoirs().get(i);var stock=node.inventory();
            double work=stock.wallWorkPressure()*PhaseLayout.bagVolume(node.state(),stock.wallCapacity());
            nodes.set(i,new PassiveNetwork.Reservoir(node.id(),node.elevation(),node.state(),PassiveNetwork.NodeKind.JUNCTION,
                    stock.with(stock.moles(),stock.internalEnergy()+work,stock.solids())));
        }
        var pipes=new ArrayList<PassiveNetwork.Pipe>(graph.pipes().size());
        for(var pipe:graph.pipes())pipes.add(emptying[pipe.first()]||emptying[pipe.second()]
                ?pipe.withPorts(emptying[pipe.first()]&&pipe.firstPort()!=PassiveNetwork.PhasePort.SPRAY?PassiveNetwork.PhasePort.BULK:pipe.firstPort(),emptying[pipe.second()]&&pipe.secondPort()!=PassiveNetwork.PhasePort.SPRAY?PassiveNetwork.PhasePort.BULK:pipe.secondPort()):pipe);
        return new PassiveNetwork(nodes,pipes,graph.scheduledTransfers());
    }
    /**
     * Decision D54: {@code seeds} with every vessel that {@code drained} adds to {@code emptying} seeded with its own start
     * state. A junction's seed is its own state, the phases of the stock it holds; the passes that failed before the
     * vessel was found running dry read diverged iterates and may have folded a phase out of its seed. A blanketed drum
     * of 2.9 m3 of nitrogen over 20 L of water, its gas folded by a failed 2 s pass, stood as a junction of water alone
     * at 83.8 kPa and was confirmed emptying; with its gas it stands at 104.7 kPa, above the ambient, and keeps its
     * contents (batch 2026-11-11-aqueous-solvents, FINAL_LANE_FIXES_REVIEW.md section 3).
     */
    static List<FluidThermodynamics.State> stockSeeds(PassiveNetwork graph,List<FluidThermodynamics.State> seeds,boolean[] drained,boolean[] emptying) {
        List<FluidThermodynamics.State> result=null;
        for(int i=0;i<drained.length;i++) {
            if(!drained[i]||emptying!=null&&emptying[i])continue;
            var own=graph.reservoirs().get(i).state();if(seeds.get(i)==own)continue;
            if(result==null)result=new ArrayList<>(seeds);
            result.set(i,own);
        }
        return result==null?seeds:result;
    }
    /** Per node of {@code graph}, the stock an emptying vessel passes over the step and the ambient's work on its bag. */
    ConservativeTransport.Emptying[] emptyings(PassiveNetwork graph,boolean[] emptying,double dt) {
        var result=new ConservativeTransport.Emptying[emptying.length];
        for(int i=0;i<emptying.length;i++)if(emptying[i]) {
            var node=graph.reservoirs().get(i);var stock=node.inventory();
            result[i]=new ConservativeTransport.Emptying((solver.molarMass(stock.moles())+stock.solids().massKg())/dt,
                    stock.wallWorkPressure()*PhaseLayout.bagVolume(node.state(),stock.wallCapacity()));
        }
        return result;
    }
    /**
     * {@code emptying} with every vessel released that a converged pass puts above the ambient (it holds contents at the
     * step's end after all), or null when none is. A vessel a converged pass of this solve has held at or below the ambient
     * is confirmed in {@code emptied} and never released again within the solve: whether a bag runs dry within the step is
     * decided once (a release that restored the vessel form after the phase correction laid out its last 3e-17 kg as two
     * phases cycled the pass sequence at every step size).
     */
    static boolean[] keptEmptying(PassiveNetwork graph,List<FluidThermodynamics.State> states,boolean[] emptying,boolean[] emptied) {
        boolean[] result=null;
        for(int i=0;i<emptying.length;i++) {
            if(!emptying[i]||emptied[i])continue;
            // A rigid vessel (batch 2026-11-10-rigid-reservoir) holds contents at any pressure above none.
            if(!(states.get(i).pressure()>graph.reservoirs().get(i).inventory().wallWorkPressure()*(1+1e-9))){emptied[i]=true;continue;}
            if(result==null)result=emptying.clone();
            result[i]=false;
        }
        return result;
    }
    /** Whether this pass laid out a vessel dry. */
    static boolean anyDry(StepEquations equations){for(boolean dry:equations.dry)if(dry)return true;return false;}
    /** Whether a converged pass's reconstruction books a fluid into a vessel the pass laid out dry: more than none at the
     * bag's resolution ({@link PassiveStepSolver#holdsNoFluid}); a roundoff it receives stays booked and the vessel stays dry. */
    static boolean filled(StepEquations equations,ConservativeTransport.Projection projection) {
        for(int node=0;node<equations.dry.length;node++)
            if(equations.dry[node]&&!holdsNoFluid(projection.inventories().get(node),projection.states().get(node)))return true;
        return false;
    }
}
