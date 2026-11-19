package com.wormzjl.icheme.science.fluid.network;

import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.GRAVITY;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.SOLID_CLAMP_FRACTION;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.TRACE_CLAMP_FRACTION;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.TRACE_RETENTION;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.FILTER_CAPACITY_MARGIN;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.CHORD_CONTRACTION;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.boreArea;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.boundaryAllowed;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.cakeResistance;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.chordHolds;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.closedEdge;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.columnFromFirst;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.donates;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.dry;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.hasSolids;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.holdsNoFluid;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.incidence;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.phaseCode;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.pockets;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.poolHeat;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.saturated;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.saturationSlope;
import com.wormzjl.icheme.science.fluid.diagnostics.SolverDiagnostics;
import com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.PassStart;
import com.wormzjl.icheme.science.fluid.solver.*;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.math.DeterministicMath;
import java.util.*;

/** The rows a block Jacobian sweep has to re-evaluate for one perturbed node or edge column. */
/**
 * The equations of one active-set pass of {@link PassiveStepSolver} (S-01, batch 2026-11-13-codebase-review): the layout
 * of the unknowns, what each port draws, the residual rows and the block Jacobian. Built once per pass by the solver's
 * pass loop on the solver and its model; it reads the solver's state and laws through {@code solver} and the solver's
 * statics by static import.
 */
final class StepEquations implements SparseNewton.Equations {
    /** The solver this pass belongs to (its fields and laws are read through it), and the solver's model, the same object. */
    private final PassiveStepSolver solver;private final FluidThermodynamics model;
    final double[][] solidTargets,solidIncoming;
    /** The junctions above a crystal's melting point (owner decision D61, {@link JunctionCrystals}), null where none is or
     * in a rate solve; per such junction, what its own stock melts: amounts {@code heldMelt} (mol), offsets
     * {@code heldMeltEnergy} (J), and the moments of the solids it keeps, {@code heldKept}. */
    final JunctionCrystals melting;
    final double[][] heldMelt,heldKept;final double[] heldMeltEnergy;
    final int[] filterOffsets;
    final int[][] weirOffsets;final double[][] weirValues;final boolean hasWeirs;
    /** The connections as the warm-flow check may compare them: never including the cake. They are the solve's own
     * connections, also in a pass that stands an emptying vessel as a junction (decision D54, {@link PhaseActiveSet#emptyingGraph}),
     * so every pass of the step starts from the same carried flows. */
    final List<PassiveNetwork.Pipe.Identity> pipeIdentities;
    final PassiveNetwork graph;final double dt;final PhaseLayout[] layout;final int[] offsets;
    /** Per node, the frozen phase support its layout states (null for a fixed node): the equilibrium the reconstruction
     * closes each vessel to ({@link InventoryEquilibrium#close}), so the equation gate checks it in its own model. */
    final byte[][] supports;
    /** Per node, whether this pass lays it out dry ({@link PassiveStepSolver#dry}): no unknowns, its state its own. */
    final boolean[] dry;
    /** Per vessel, the bag volume of the state the step starts from ({@link PhaseLayout#bagVolume}; zero for an empty
     * one): its energy row books the wall's work from it (decision D54). */
    final double[] bagStart;
    /** Per vessel, the wall's branches ({@link PhaseLayout#wall}) at the point the Jacobian in use was built at (decision
     * A423): {@code sweepWall} is read by the rows while a build is bracketed ({@link #linearising}), null otherwise;
     * {@code builtWall} by the chord test ({@link #linearisationHolds}). Null for a junction and for a dry vessel. */
    PhaseLayout.Wall[] sweepWall,builtWall;
    private final FurnacePairs furnacePairs;
    final int edgeOffset,size;final double[][] oldAmounts;int[][] sparsity;final List<FlowControl.Mode> modes;final int[] controlOffsets;final boolean[] boundaryClosed;final List<FluidThermodynamics.State> seeds;
    final double[][] cachedVariables;final FluidThermodynamics.State[] cachedStates;final Transport[] cachedTransport;
    /** Which nodes an end draws phases of ({@link PassiveNetwork.PhasePort} VAPOR or LIQUID) through a connection this
     * pass leaves open: only those nodes build their phase streams at each decode, and only their non-amount columns
     * keep their material-row entries ({@link #buildSparsity}). A closed passive connection carries exactly zero by its
     * own row, so its donor terms are zero whatever it would draw. */
    final boolean[] drawsPhase;
    /**
     * Each vessel's per-phase capacity (decision D11; {@link PhaseDraw}), kg/s by phase id, for a node with a phase port
     * whose connection may carry outflow from it; null for every other node: {@code c = m_start / (N dt)}, with
     * {@code m_start} the phase stream's mass on the accepted state the step starts from (its solid share included),
     * {@code N} the vessel's phase-port ends whose connection may carry outflow by the static rule
     * ({@link PassiveStepSolver#boundaryAllowed}, a constant of the solve), and {@code dt} this solve's step (the backward-Euler step the
     * interval solver attempts, never the slice). A rate solve has no step: every capacity is infinite, and a port
     * draws the first phase of its order its vessel holds.
     */
    final double[][] capacities;
    /**
     * Each open vessel's per-phase capacity at its OVERFLOW ports, kg/s by phase id ({@link OpenVessel#overflowBand}
     * over the step and over its OVERFLOW ends that may carry outflow); null for a node with none. Infinite in a rate
     * solve, like {@link #capacities}. The ends' total is also their flow's cap ({@link #cap}): the port draws what
     * stands above its nozzle and no more.
     */
    final double[][] overflowCapacities;
    /** What each open vessel gives the atmosphere over the step ({@link OpenVessel#releases}): constant terms of its
     * balance rows; null for a rate solve and for an island without one. */
    final OpenVessel.Releases[] releases;
    /** Which phases each node's seed of this pass holds ({@link PhaseDraw#held}). */
    final boolean[][] seedHeld;
    /** The phase streams of each node with a phase port on the accepted state the step starts from, by phase id (null
     * for a phase it does not hold): what a pinned phase is drawn at ({@link PhaseDraw}). */
    final Transport[][] startPhases;
    /**
     * The phase-port segment of each connection end for this pass ({@link PhaseDraw.Segment}; {@code [edge][0]} the
     * first end, {@code [edge][1]} the second), null for a BULK end and for a closed passive connection. Decided once
     * per pass from the start flow ({@link #initialPoint}) and the seeds, like {@link #junctionDonorFirst}: which
     * phases are pinned at their capacity and which one carries the rest is a kink of the stream's composition in the
     * flow, so freezing it keeps each residual smooth; the pass loop re-decides it from the converged flow.
     */
    final PhaseDraw.Segment[][] segments;
    final FluidThermodynamics.Prepared[] cachedPrepared;
    /** Per node, the bundle a Jacobian probe at another temperature is prepared in, refilled in place
     * ({@link FluidThermodynamics#prepare(double,FluidThermodynamics.Prepared)}, finding P-18): the decode's own
     * ({@link #cachedPrepared}) is the probe's base and stays as it is through the node's columns. */
    private final FluidThermodynamics.Prepared[] probePrepared;
    private FluidThermodynamics.Prepared probe(int node,double temperature) {
        if(probePrepared[node]==null)solver.preparedBuilt++;
        return probePrepared[node]=model.prepare(temperature,probePrepared[node]);
    }
    /**
     * Takes over the bundles of the solver's previous pass (P-18): a bundle holds one temperature's terms and no node's
     * state, so any slot serves any node, refilled at its first decode. The previous pass drops its decode cache and
     * its probe bundles, so it decodes anew with bundles of its own if it is evaluated again (the accepted point's
     * equations, read after the solve). Each solver runs one pass at a time on one thread. The accepted pass of the
     * solver's last step holds its bundles retired ({@link #releaseDecodeCache}), so the step's first pass refills each
     * at its first decode as a new one, and builds no workspace where a bundle is kept (batch 2026-11-15-fluid-step-cost, S2).
     */
    private void adoptPrepared() {
        var previous=solver.preparedPass;solver.preparedPass=this;
        if(previous==null||previous==this)return;
        System.arraycopy(previous.cachedPrepared,0,cachedPrepared,0,Math.min(cachedPrepared.length,previous.cachedPrepared.length));
        System.arraycopy(previous.probePrepared,0,probePrepared,0,Math.min(probePrepared.length,previous.probePrepared.length));
        Arrays.fill(previous.cachedStates,null);Arrays.fill(previous.cachedTransport,null);
        Arrays.fill(previous.cachedPrepared,null);Arrays.fill(previous.probePrepared,null);
    }
    /** Whether this island carries the three aggregate solid moment unknowns on every node;
     * the whole-island predicate {@link PhaseLayout} is built with, evaluated once. */
    final boolean solidSupport;
    final boolean[] amountVariables,solidVariables;
    final double[] differenceFloors;
    /** Difference scale of a normalized junction amount whose own size would give a rounded-away column. */
    private static final double JUNCTION_AMOUNT_DIFFERENCE_SCALE=1e-4;
    /** Each amount column's seed difference scale ({@link PhaseLayout#seedDifferenceScale}), -1 for every other column:
     * what {@link #differenceScale} differences a trace below {@link PhaseLayout#TRACE_DIFFERENCE_GUARD} against. */
    final double[] seedDifferenceScales;
    /** Below this value an amount unknown's nonnegativity is not a live constraint on the step
     * length: a solid moment at or below it is dust (see {@link PassiveStepSolver#SOLID_CLAMP_FRACTION}), and a
     * fluid amount at or below it is a trace that {@link #project} keeps positive instead (see
     * {@link PassiveStepSolver#TRACE_CLAMP_FRACTION}). */
    final double[] clampFloors;
    /** Edge indices incident to each node, ascending - which is the order the whole-island
     * residual accumulates that node's targets, energy and junction inflows in, so a per-node
     * re-assembly reproduces every accumulator bit for bit. */
    final int[][] nodeEdges;
    /** The pressure a junction no open connection reaches keeps, or 0 for every other node.
     * Depends on the pass's active set, so it is built with the equations and not per
     * evaluation; see {@link PhaseLayout#junctionRows}. */
    final double[] retainedPressures;
    /**
     * The pressure each edge's pressure-dimensioned rows are stated in, so that the single Newton tolerance means the
     * same relative closure on every island: {@code max(1e5, max(P_first, P_second))} from the endpoint states of this
     * pass. Those rows (the hydraulic balance, a mover's law, a valve's regulated inlet pressure and an open
     * valve's zero head) used to divide by a literal 1e5 Pa: at a tolerance of 1e-9 that demands 1e-4 Pa whatever the
     * island runs at, a relative 2.5e-10 at 400 kPa, below what the endpoint pressures can be resolved to through the
     * logarithmic pressure unknown, so a driven line stalled a fraction of a milli-pascal apart (an inline filter, about
     * 150x a plain pipe's resistance, first). Constant through the Newton solve, so out of the Jacobian; floored at
     * 1e5 Pa, so every island at or below atmospheric pressure is bit-identical; per edge, so an island spanning a 4 bar
     * header and an atmospheric vent loosens only the rows at high pressure. The live iterate's pressures were
     * rejected: the scale would enter every row's derivative with a kink.
     */
    final double[] pressureScales;
    /**
     * Owner decision D12 (E8, reading A11): the weight of each row in the norm this pass's Newton converges and
     * descends on ({@link #residualNorm}), null where every row's is 1; the rows themselves, and the equation gate that
     * reads them, are unchanged. A taut vessel's pressure is its contents' stiffness {@code B = V / sum(V_phase
     * kappa_phase)} times its volume residual ({@link FluidThermodynamics#isothermalAcousticBound}: {@code B = m c^2 / V}),
     * so its wall row at the tolerance leaves {@code B * 1e-9} in its pressure and a balance row stated over the
     * amount {@code s} leaves {@code B * 1e-9 * s / n}, where the hydraulic rows the gate checks are stated over
     * {@code max(1e5, P)} ({@link #pressureScales}). Water at 573.6 kPa ({@code B} 2.27 GPa) reads 2.3 Pa where the
     * gate allows 0.0057 Pa: a set-flow pump near its shutoff carried 3.1e-9 kg/s into a closed tank full of water
     * (6.4e-12 of its amount), the carried point met the tolerance at zero iterations, the booking moved the tank's
     * pressure 0.023 Pa and the gate refused 516 of 1024 attempts (bundled seed 896). So each of these rows is weighted
     * by the pressure it implies over {@code max(1e5, P)}, at least 1: the wall row by {@code B / max(1e5, P)}, a
     * balance row by that times {@code s / n}, so a trace stated over its own amount keeps its own resolution. A gas
     * vessel ({@code B} about {@code P}) reads 1; a slack vessel's wall row states its pressure itself and a junction
     * owns no wall, so neither is weighted (a filter's two junctions seeded liquid-full, held to a tank's tolerance,
     * demanded 1e-10 of a tank that floors its water saturation row near 1.03e-10, and the island refined its step to
     * nothing). The energy row of a vessel whose headspace is under 1 % keeps the tenth of the tolerance the whole
     * island used to be solved to: its pressure through the thermal pressure coefficient asks more, but 1e-11 stalls
     * on caloric cancellation in nearly liquid-full water with trace N2. Weights are the seed's, constant for the pass.
     */
    final double[] rowWeights;
    final double[] fixedOpenings;
    /** This pass's starting point, built once: {@link #junctionDonorFirst} is read off it. */
    final double[] initialPoint;
    /**
     * Which end of each connection donates into a zero-holdup junction for the whole of this pass, taken from the point
     * the pass starts at rather than from the live iterate. A junction's mass fractions and specific enthalpy are the
     * mixture of what arrives, a ratio that does not shrink with the flows that form it, so deciding the donor inside
     * the residual put a finite jump in those rows at exactly zero flow, and the one-sided differences the Jacobian is
     * built from sample only the positive side: on a settled island the derivative the step was computed from is not
     * the derivative along the step (on the valve block line at 400 kPa the linear model predicted a residual of 8e-25
     * and the full step produced 1.7e-3, every backtrack on the same plateau). Freezing it makes one pass's residual
     * smooth in its own unknowns, the rule every other switch here follows; a converged point whose flow disagrees
     * with the donor is one more active-set change, and the donor signs join the cycle key. Only the junction mixture
     * reads them; every edge row keeps the live upwind, so an accepted point is upwinded exactly as
     * {@link ConservativeTransport} reconstructs it.
     */
    final boolean[] junctionDonorFirst;
    /**
     * Which end's fluid stands in each connection for the whole of this pass (0 the first end's stream, 1 the second's),
     * read off the point the pass starts at rather than from the live iterate: the twin of {@link #junctionDonorFirst}
     * on the hydraulic row. The head {@code rho*g*dz} does not shrink with the flow, so a head read from the upwind end
     * of the current flow had a jump of {@code (rho_first - rho_second)*g*dz} in its row at exactly zero flow, and a
     * settled line landed on a plateau it could not leave (a water generator four blocks below a nitrogen tank at
     * 400 kPa: the row jumped by 2.754e-2, the predicted {@code (996.31-715.46)*9.80665*4/400000}, the linear model
     * predicted 1.08e-19 and the full step produced 2.76e-2). Stating it once per pass makes the column a property of
     * the connection for that pass and the rest point {@code P_b = P_a - rho*g*dz} an actual root.
     *
     * <p>Which column it is, is stated from the pass's own seed pressures and elevations ({@link PassiveStepSolver#columnFromFirst}),
     * never from a flow sign, and it is deliberately not revised by the outer active-set loop (unlike the junction
     * donor): near its own hydrostatic balance each column puts the flow on the other one, so a rule that turned the
     * head over on a converged disagreement would alternate forever. The pass keeps the column it solved under; the
     * friction term keeps the live donor, being odd in the flow with no jump to freeze. Nor is it part of
     * {@link WorkspaceKey}: adding it churned the workspace cache and cost a vertical line at rest its whole run (forty
     * intervals settled at 360918.3068 Pa went to a hold at residual 2.202e-7). What is frozen is the end, never its
     * density: the row reads that end's density on its own trial state, so a riser at rest balances its own fluid's
     * weight at the state solved for, and a dead-end loop of risers carries nothing but the column's discretisation
     * (frozen at the seeds, a dead-end pocket's columns did not cancel round its loops: 1.8e-5 kg/s on the seeds,
     * 2.4e-7 kg/s on the trial's densities).
     */
    final int[] columnEnds;
    /** Residual scratch. Every one of these is written before it is read within a single
     * evaluation and never escapes it, and the evaluations of one solve are sequential on the
     * worker holding the latch, so they are filled again rather than allocated again. The
     * returned residual is not among them: {@link SparseNewton} holds the current and the
     * candidate residual at the same time. */
    final double[][] targets,incoming;
    final double[] energy,incomingMass,incomingEnergy,netMass,fractions;
    /** Evaluation scratch under the same rule (finding P-07, batch 2026-11-13-codebase-review): the solid moments of the
     * stream a connection carries and of the one a receiver mixes ({@link #nodeAccumulate}), a junction's solid mixture
     * ({@link #nodeSolidRows}) and which phases a node transport holds ({@link #held(Transport)}, read at once by
     * {@link PhaseDraw.Segment#marginalAt}). */
    private final double[] carriedMoments=new double[3],mixedMoments=new double[3],solidMix=new double[3];
    private final boolean[] heldScratch=new boolean[3];
    /**
     * What a vessel's outlets carry beside their own phase for this slice ({@link Separation.Carried}, a slice input on
     * its inventory): per connection end, the mass share of its stream it draws as each other phase ({@code [edge][end]}
     * by phase id), on the densities and the volumes of the step's start state; {@code carriedOwn} the end's own phase.
     * Null where no vessel of the graph carries anything, which leaves every draw as it was, to the bit.
     */
    final double[][][] carriedShares;final int[][] carriedOwn;
    /** Per node, its solids' moments at the step's start ({@link #startMoments}), worked out at the first evaluation that
     * reads them and kept for the pass: a function of the graph's inventory, which a pass does not change. Read only. */
    private final double[][] startMoments;
    /** The weight of the mixing and enthalpy rows the last {@link #junctionInflow} call set: they are stated in
     * amount form over the junction's own mass; see there. */
    double junctionWeight=1;
    StepEquations(PassiveStepSolver solver,FluidThermodynamics model,PassiveNetwork graph,double dt,List<FlowControl.Mode> modes,boolean[] boundaryClosed,List<FluidThermodynamics.State> seeds,boolean[][] reachable,
              byte[][] supports,List<PassiveNetwork.Pipe.Identity> pipeIdentities,Runnable checkpoint,PassStart passStart) {
        this.solver=solver;this.model=model;fixedOpenings=solver.constants.heldOpenings.clone();
        this.modes=List.copyOf(modes);this.pipeIdentities=pipeIdentities;
        this.seeds=List.copyOf(seeds);this.supports=supports;
        this.boundaryClosed=boundaryClosed.clone();
        this.graph=graph;furnacePairs=FurnacePairs.in(graph);this.dt=dt;solidSupport=hasSolids(graph);int count=graph.reservoirs().size();layout=new PhaseLayout[count];offsets=new int[count];oldAmounts=new double[count][];
        this.checkpoint=checkpoint;passCurves=new HeatCurve[graph.pipes().size()][];passKnown=new boolean[graph.pipes().size()][];heldCurves();
        cachedVariables=new double[count][];cachedStates=new FluidThermodynamics.State[count];cachedTransport=new Transport[count];
        cachedPrepared=new FluidThermodynamics.Prepared[count];probePrepared=new FluidThermodynamics.Prepared[count];
        adoptPrepared();
        drawsPhase=new boolean[count];
        for(int edge=0;edge<graph.pipes().size();edge++)for(int end=0;end<2;end++) {
            var pipe=graph.pipes().get(edge);
            if(this.boundaryClosed[edge]&&pipe.control() instanceof FlowControl.Passive)continue;
            if((end==0?pipe.firstPort():pipe.secondPort())!=PassiveNetwork.PhasePort.BULK)drawsPhase[end==0?pipe.first():pipe.second()]=true;
        }
        releases=solver.rateOnly?null:releases(graph,dt);
        capacities=capacities(graph,dt);overflowCapacities=overflowCapacities(graph,dt);
        seedHeld=new boolean[count][];startPhases=new Transport[count][];
        for(int i=0;i<count;i++)if(drawsPhase[i]){seedHeld[i]=PhaseDraw.held(this.seeds.get(i));startPhases[i]=transport(i,graph.reservoirs().get(i).state(),null).phases;}
        double[][][] carrying=null;int[][] own=null;
        for(int edge=0;edge<graph.pipes().size();edge++)for(int end=0;end<2;end++) {
            var pipe=graph.pipes().get(edge);int node=end==0?pipe.first():pipe.second();
            var shares=carriedShares(node,end==0?pipe.firstPort():pipe.secondPort());if(shares==null)continue;
            if(carrying==null){carrying=new double[graph.pipes().size()][2][];own=new int[graph.pipes().size()][2];}
            carrying[edge][end]=shares;own[edge][end]=(int)shares[3];
        }
        carriedShares=carrying;carriedOwn=own;
        int cursor=0;dry=new boolean[count];bagStart=new double[count];
        for(int i=0;i<count;i++) {
            var node=graph.reservoirs().get(i);
            dry[i]=dry(node,this.seeds.get(i));
            // The bag volume the step starts from, whose wall work each vessel's energy row books (decision D54).
            if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR)bagStart[i]=node.empty()?node.inventory().emptyBag():PhaseLayout.bagVolume(node.state(),node.inventory().wallCapacity());
        }
        for(int i=0;i<count;i++) {
            var state=graph.reservoirs().get(i).state();offsets[i]=cursor;oldAmounts[i]=graph.reservoirs().get(i).inventory().moles();
            if(!graph.reservoirs().get(i).fixed()&&!dry[i]) {
                layout[i]=new PhaseLayout(model,seeds.get(i),reachable[i],kept(i),supports[i],solidSupport).atAmbient(graph.reservoirs().get(i).inventory().ambient());cursor+=layout[i].size();
                if(SolverDiagnostics.ENABLED) {
                    int omitted=layout[i].singlePhaseComponentCount();
                    if(omitted>0){SolverDiagnostics.traceOmittedUnknowns.add(omitted);SolverDiagnostics.truncatedNodePasses.increment();}
                }
            }
        }
        edgeOffset=cursor;cursor+=graph.pipes().size();controlOffsets=new int[graph.pipes().size()];Arrays.fill(controlOffsets,-1);
        for(int i=0;i<controlOffsets.length;i++)if(!(graph.pipes().get(i).control() instanceof FlowControl.Passive))controlOffsets[i]=cursor++;
        filterOffsets=new int[graph.pipes().size()];Arrays.fill(filterOffsets,-1);for(int i=0;i<filterOffsets.length;i++)if(graph.pipes().get(i).filter()!=null)filterOffsets[i]=cursor++;
        weirOffsets=new int[graph.pipes().size()][2];weirValues=new double[graph.pipes().size()][2];boolean anyWeir=false;
        for(int edge=0;edge<weirOffsets.length;edge++)for(int end=0;end<2;end++){
            var pipe=graph.pipes().get(edge);boolean weir=!solver.rateOnly&&(end==0?pipe.firstPort():pipe.secondPort())==PassiveNetwork.PhasePort.BOOT_HC;
            weirOffsets[edge][end]=weir?cursor++:-1;anyWeir|=weir;
        }
        hasWeirs=anyWeir;
        size=cursor;
        amountVariables=new boolean[size];solidVariables=new boolean[size];
        differenceFloors=new double[size];clampFloors=new double[size];Arrays.fill(differenceFloors,1);
        seedDifferenceScales=new double[size];Arrays.fill(seedDifferenceScales,-1);
        for(int node=0;node<count;node++)if(layout[node]!=null)for(int local=0;local<layout[node].size();local++) {
            int column=offsets[node]+local;
            amountVariables[column]=layout[node].totalAmountVariable(local);differenceFloors[column]=layout[node].differenceScale(local,0);
            if(amountVariables[column])seedDifferenceScales[column]=layout[node].seedDifferenceScale(local);
            solidVariables[column]=layout[node].solidVariable(local);
            // These rows are scaled by the junction's mixture, so a trace-relative perturbation can round to zero
            // in every row. Use the same bulk scale as PhaseLayout's gas-bearing amount columns, only for the stencil.
            if(graph.reservoirs().get(node).junction()&&amountVariables[column]&&!solidVariables[column])
                seedDifferenceScales[column]=Math.max(JUNCTION_AMOUNT_DIFFERENCE_SCALE,seedDifferenceScales[column]);
            if(solidVariables[column])clampFloors[column]=SOLID_CLAMP_FRACTION*differenceFloors[column];
            else if(amountVariables[column])clampFloors[column]=TRACE_CLAMP_FRACTION*differenceFloors[column];
        }
        int components=oldAmounts[0].length;
        targets=new double[count][components];incoming=new double[count][components];fractions=new double[components];
        solidTargets=new double[count][3];solidIncoming=new double[count][3];startMoments=new double[count][];
        melting=solver.rateOnly?null:JunctionCrystals.of(graph,model);heldMelt=new double[count][];heldKept=new double[count][];heldMeltEnergy=new double[count];
        if(melting!=null)for(int node=0;node<count;node++)if(melting.any(node)) {
            var stock=graph.reservoirs().get(node).inventory().solids();heldMelt[node]=new double[components];
            for(var p:stock.populations()){var crystal=melting.melts(node,p.material());if(crystal==null)continue;
                heldMelt[node][crystal.component()]+=p.massKg()/model.molecularWeight(crystal.component());heldMeltEnergy[node]+=p.massKg()*p.material().crystal().energyOffset();}
            heldKept[node]=melting.kept(node,stock).moments().values();
        }
        transfersAt=transfersAt(graph);edgeFlow=new double[graph.pipes().size()];edgeDonor=new Transport[edgeFlow.length];edgeSuction=new Transport[edgeFlow.length];
        donorMoles=new double[edgeFlow.length][];suctionMoles=new double[edgeFlow.length][];
        energy=new double[count];incomingMass=new double[count];incomingEnergy=new double[count];netMass=new double[count];
        for(int node=0;node<count;node++)if(layout[node]!=null)cachedVariables[node]=new double[layout[node].size()];
        nodeEdges=incidence(graph);
        pressureScales=new double[graph.pipes().size()];
        for(int edge=0;edge<pressureScales.length;edge++) {
            var pipe=graph.pipes().get(edge);
            pressureScales[edge]=Math.max(1e5,Math.max(seeds.get(pipe.first()).pressure(),seeds.get(pipe.second()).pressure()));
        }
        rowWeights=rowWeights();
        // A junction no open connection reaches keeps the pressure it had (PhaseLayout.junctionRows). The same holds
        // for a sealed pocket of junctions (hold F8; see pockets): every connection on its boundary is closed, so its
        // members' mass rows sum to zero identically and its pressure level has no row. The pocket's first member
        // keeps its pressure in place of its mass row; the others keep theirs.
        retainedPressures=new double[count];
        int[] pocket=pockets(graph,this.modes,this.boundaryClosed);boolean[] unsealed=new boolean[count],retained=new boolean[count];
        for(int edge=0;edge<graph.pipes().size();edge++) {
            var pipe=graph.pipes().get(edge);if(closedEdge(this.modes.get(edge),this.boundaryClosed[edge]))continue;
            int a=pipe.first(),b=pipe.second();
            if(pocket[a]>=0&&pocket[b]!=pocket[a])unsealed[pocket[a]]=true;
            if(pocket[b]>=0&&pocket[a]!=pocket[b])unsealed[pocket[b]]=true;
        }
        for(int node=0;node<count;node++) {
            if(layout[node]==null||!graph.reservoirs().get(node).junction()||pocket[node]<0||unsealed[pocket[node]]||retained[pocket[node]])continue;
            retained[pocket[node]]=true;retainedPressures[node]=graph.reservoirs().get(node).state().pressure();
        }
        initialPoint=buildInitial(passStart);
        junctionDonorFirst=new boolean[graph.pipes().size()];
        for(int edge=0;edge<junctionDonorFirst.length;edge++) {
            var pipe=graph.pipes().get(edge);
            // A connection with no junction at either end donates into no mixture, so it has no
            // frozen donor to state and none to key a workspace on. Leaving it constant is what
            // keeps an island without a single zero-holdup node bit for bit what it was.
            junctionDonorFirst[edge]=!graph.reservoirs().get(pipe.first()).junction()&&!graph.reservoirs().get(pipe.second()).junction()
                    ||initialPoint[edgeOffset+edge]>=0;
        }
        columnEnds=new int[graph.pipes().size()];
        for(int edge=0;edge<columnEnds.length;edge++) {
            var pipe=graph.pipes().get(edge);
            var start=seeds.get(pipe.first());var end=seeds.get(pipe.second());
            // Each end's column is what its own port stands in the connection (decision D34, {@link PortStreams#columnDensity}).
            double first=PortStreams.columnDensity(model,start,pipe.firstPort(),pipe.volume()),second=PortStreams.columnDensity(model,end,pipe.secondPort(),pipe.volume());
            // Decisions D54 and D59 (5): an end that may not fill the connection (a dry end, a void, a generator's far
            // end, a blocked way) has nothing to stand in it; it stands in what the other end sends ({@link PassiveStepSolver#donates}).
            int only=-1;
            if(!donates(graph,pipe,pipe.first())){first=second;only=1;}
            else if(!donates(graph,pipe,pipe.second())){second=first;only=0;}
            double dz=graph.reservoirs().get(pipe.second()).elevation()-graph.reservoirs().get(pipe.first()).elevation();
            // The same pressure difference the row drives on, actuator included: a pump's own
            // head is the larger part of what pushes its connection, so a column decided
            // without it is decided on the suction/discharge gap alone and always reads the
            // discharge as the donor.
            double actuator=controlOffsets[edge]<0?0:initialPoint[controlOffsets[edge]]*1e5;
            double difference=PortStreams.endPressure(model,graph.reservoirs().get(pipe.first()),pipe.firstPort(),start)-PortStreams.endPressure(model,graph.reservoirs().get(pipe.second()),pipe.secondPort(),end)
                    +(pipe.control() instanceof FlowControl.Mover?actuator:-actuator);
            // The column that is its own donor: a column is admissible exactly when the driving pressure it produces
            // points away from the end it was read off. Where both columns agree on the direction only one is
            // admissible (a line pouring downhill stands in what feeds it from above). Taking the start point's upwind
            // end instead reads a numerical zero as a direction: a water generator four blocks under a nitrogen tank at
            // the same pressure, read as the water column, asks for 39 kPa the generator has not got and stalls on the
            // friction kink at zero; a liquid line resting on its own two-metre column, read the other way, takes the
            // lighter density and the 0.18 Pa that leaves drives a permanent 8.3e-7 kg/s through a graph at rest.
            // Neither column admissible (the drive below the heavier head and above the lighter) is where a settled
            // line lives: the column nearest its own rest point, so rest is a fixed point of the rule as of the
            // equations. Both admissible is genuine bistability, and the start point's flow sign is the only evidence
            // of which fluid is in the pipe (deciding by rest point stopped a filling line at the lighter column's
            // balance, 429851 Pa against the 439082 Pa its water column demands). On a flat island dz is zero and the
            // choice multiplies nothing. The rule is {@link PassiveStepSolver#columnFromFirst}, which the start estimate states too.
            columnEnds[edge]=only>=0?only:columnFromFirst(first,second,difference,dz,initialPoint[edgeOffset+edge]>=0)?0:1;
        }
        segments=startSegments();
        solver.watchJunctionPrecision(this);
    }
    /**
     * The segments of this pass, decided from the flows its start point carries: the start point's edge unknowns are
     * the law's flows, and which phases a port draws at its capacity is a question of the transported ones
     * ({@link #flow}), which depend on the segments through a priority mixture's cap ({@link #capLimit}). Decided under
     * the bulk caps first, then under their own decision until it stands (at most three rounds); the pass loop decides
     * again from the converged flows (decision D11).
     */
    private PhaseDraw.Segment[][] startSegments() {
        int edges=graph.pipes().size();double[] q=new double[edges];PhaseDraw.Segment[][] decided=null;
        for(int round=0;round<3;round++) {
            for(int edge=0;edge<edges;edge++) {
                double y=initialPoint[edgeOffset+edge];int end=y>=0?0:1;var pipe=graph.pipes().get(edge);int node=end==0?pipe.first():pipe.second();
                q[edge]=saturates(edge)?saturated(y,cap(decided==null?null:decided[edge][end],edge,end,transportAt(node,seeds.get(node)),y)):y;
            }
            var next=decideSegments(q);
            if(decided!=null&&samePhases(decided,next))break;
            decided=next;
        }
        return decided;
    }
    private static boolean samePhases(PhaseDraw.Segment[][] a,PhaseDraw.Segment[][] b) {
        for(int edge=0;edge<a.length;edge++)for(int end=0;end<2;end++)
            if(a[edge][end]==null?b[edge][end]!=null:b[edge][end]==null||!a[edge][end].samePhases(b[edge][end]))return false;
        return true;
    }
    /** Whether connection {@code edge}'s law is saturated at its velocity cap in this pass ({@link PassiveStepSolver#saturates}): a
     * throttling mode and not closed. */
    boolean saturates(int edge){return PassiveStepSolver.saturates(modes.get(edge))&&!boundaryClosed[edge];}
    /** What node {@code i} holds past its releases: a gas its vent takes whole is, like one it never held, no trace for its rows to resolve. */
    private double[] kept(int i) {
        if(releases==null||releases[i]==null)return oldAmounts[i];
        var kept=oldAmounts[i].clone();
        for(var released:releases[i].each()){var n=released.moles();for(int c=0;c<n.length;c++)kept[c]-=n[c];}
        return kept;
    }
    /** {@link #releases} of {@code graph}: null when no node releases anything. */
    private OpenVessel.Releases[] releases(PassiveNetwork graph,double dt) {
        OpenVessel.Releases[] result=null;
        for(int node=0;node<graph.reservoirs().size();node++) {
            var vessel=graph.reservoirs().get(node);
            var released=OpenVessel.releases(model,vessel,dt,solver.blanketInflows.getOrDefault(vessel.id(),0.0)*dt);if(released==null)continue;
            if(result==null)result=new OpenVessel.Releases[graph.reservoirs().size()];
            result[node]=released;
        }
        return result;
    }
    /** The number of {@code port} ends (of the phase ports other than OVERFLOW when {@code port} is null) of each node
     * whose connection may carry outflow from it by the static rule. */
    private int[] drawingEnds(PassiveNetwork graph,PassiveNetwork.PhasePort port) {
        int[] ends=new int[graph.reservoirs().size()];
        for(var pipe:graph.pipes())for(int end=0;end<2;end++) {
            var at=end==0?pipe.firstPort():pipe.secondPort();
            if(port==null?at==PassiveNetwork.PhasePort.BULK||at==PassiveNetwork.PhasePort.OVERFLOW:at!=port)continue;
            if(!boundaryAllowed(graph,pipe,end==0?1:-1))continue;
            ends[end==0?pipe.first():pipe.second()]++;
        }
        return ends;
    }
    /** {@link #capacities} of {@code graph} (its states are the step's start states). An open vessel's leave out what it
     * releases over the step ({@link #releases}): its vented gas and its spilled liquid; a kettle's add its pool's
     * boil-up to its gas ({@link PassiveStepSolver#boilUp}). */
    private double[][] capacities(PassiveNetwork graph,double dt) {
        int nodes=graph.reservoirs().size();int[] ends=drawingEnds(graph,null);
        double[][] result=new double[nodes][];
        for(int node=0;node<nodes;node++) {
            if(ends[node]==0)continue;
            var start=graph.reservoirs().get(node).state();result[node]=new double[3];
            for(int phase=0;phase<3;phase++)result[node][phase]=solver.rateOnly?Double.POSITIVE_INFINITY
                    :FluidThermodynamics.holdsPhase(start,phase)?model.phaseMass(start,phase)/(ends[node]*dt):0;
            if(releases!=null&&releases[node]!=null)released(node,start,result[node],ends[node]*dt);
            // A kettle's pool boils vapour within the step (W7): its boil-up is gas the ports may draw.
            if(!solver.rateOnly)result[node][FluidThermodynamics.GAS]+=solver.boilUp(graph.reservoirs().get(node))/ends[node];
        }
        return result;
    }
    /** {@code capacity} less what open vessel {@code node} releases from its start state {@code start} over the step, each
     * phase's release shared over {@code span}, its drawing ends times the step. */
    private void released(int node,FluidThermodynamics.State start,double[] capacity,double span) {
        var released=releases[node];
        if(released.vent()!=null)capacity[FluidThermodynamics.GAS]=Math.max(0,capacity[FluidThermodynamics.GAS]-released.ventShare()*model.phaseMass(start,FluidThermodynamics.GAS)/span);
        if(released.spill()==null)return;
        var band=OpenVessel.band(model,start,OpenVessel.topVolume(graph.reservoirs().get(node).inventory()),OpenVessel.condensedVolume(start));
        for(int phase=FluidThermodynamics.OIL;phase<=FluidThermodynamics.AQUEOUS;phase++)if(band[phase]>0)
            capacity[phase]=Math.max(0,capacity[phase]-released.spillShare()*model.phaseMass(start,phase)*band[phase]/FluidThermodynamics.phaseVolume(start,phase)/span);
    }
    /** {@link #overflowCapacities} of {@code graph}. */
    private double[][] overflowCapacities(PassiveNetwork graph,double dt) {
        int nodes=graph.reservoirs().size();int[] ends=drawingEnds(graph,PassiveNetwork.PhasePort.OVERFLOW);
        double[][] result=null;
        for(int node=0;node<nodes;node++) {
            if(ends[node]==0)continue;
            if(result==null)result=new double[nodes][];
            var band=OpenVessel.overflowBand(model,graph.reservoirs().get(node));result[node]=new double[3];
            for(int phase=0;phase<3;phase++)result[node][phase]=band[phase]>0?solver.rateOnly?Double.POSITIVE_INFINITY:band[phase]/(ends[node]*dt):0;
        }
        return result;
    }
    /** The cap of an OVERFLOW end at {@code node}: its capacities' total, kg/s; infinite for a node without. */
    private double overflowCap(int node) {
        if(overflowCapacities==null||overflowCapacities[node]==null)return Double.POSITIVE_INFINITY;
        double total=0;for(double c:overflowCapacities[node])total+=c;
        return total;
    }
    record PinnedStartDraw(double[] transported,double[][] phaseRates) {}
    /** The transported rates and phase draws at a pass's start when a junction receives water, oil or gas at its supply limit. */
    PinnedStartDraw pinnedStartDraws() {
        for(int edge=0;edge<segments.length;edge++) {
            double law=initialPoint[edgeOffset+edge];
            var segment=segments[edge][law<0?1:0];
            var pipe=graph.pipes().get(edge);
            int receiver=law<0?pipe.first():pipe.second();
            if(law==0 || segment==null || segment.pinned().length==0 || !graph.reservoirs().get(receiver).junction())continue;
            var flows=new double[segments.length];
            for(int e=0;e<flows.length;e++) {
                double y=initialPoint[edgeOffset+e];
                var line=graph.pipes().get(e);
                int end=y<0?1:0;
                int donor=end==0?line.first():line.second();
                flows[e]=saturates(e)?saturated(y,cap(segments[e][end],e,end,transportAt(donor,seeds.get(donor)),y)):y;
            }
            return new PinnedStartDraw(flows,draws(flows,seeds));
        }
        return null;
    }
    /**
     * A finite draw can change the donor's inventory markedly before the end of this step, even when its marginal phase
     * remains. Seed it from the first pass's material and energy rows at the start point, including all other arrivals.
     * This is a trial inventory. The next pass retains the original start inventory and phase capacities.
     */
    List<FluidThermodynamics.State> projectedPhaseDonors(PinnedStartDraw start,Runnable checkpoint) {
        if(start.phaseRates()==null||PassiveStepSolver.hasSolids(graph))return seeds;
        boolean[] selected=new boolean[layout.length];
        int[] required=new int[layout.length];
        for(int edge=0;edge<start.transported().length;edge++) {
            double flow=start.transported()[edge];
            if(flow==0)continue;
            var pipe=graph.pipes().get(edge);int end=flow<0?1:0;
            int donor=end==0?pipe.first():pipe.second(),receiver=end==0?pipe.second():pipe.first();
            var segment=segments[edge][end];
            if(segment!=null&&segment.pinned().length>0&&graph.reservoirs().get(receiver).junction())selected[donor]=true;
            var rates=start.phaseRates()[edge];
            if(rates!=null)for(int phase=0;phase<3;phase++)if(rates[phase]>0)required[donor]|=1<<phase;
        }
        try{residual(initialPoint);}catch(IllegalArgumentException outside){return seeds;}
        List<FluidThermodynamics.State> projected=null;
        for(int i=0;i<selected.length;i++) {
            if(!selected[i]||layout[i]==null||dry[i])continue;
            var node=graph.reservoirs().get(i);
            if(node.junction()||node.fixed()||Arrays.stream(targets[i]).anyMatch(n->n<0))continue;
            try {
                var inventory=node.inventory().with(targets[i].clone(),energy[i],node.inventory().solids());
                var seed=seeds.get(i);
                var guess=model.folded(model.flashTP(seed.temperature(),seed.pressure(),targets[i],checkpoint),false,inventory.rigid());
                var candidate=InventoryEquilibrium.split(model,guess,inventory,bagStart[i],checkpoint);
                if(candidate==null||!FluidThermodynamics.holdsFluid(candidate))continue;
                // The receiver's seed still reads the start estimate's phase rates. Every phase it draws from the
                // donor's end state must exist on this projected donor.
                boolean fits=true;
                for(int phase=0;phase<3;phase++)if((required[i]&(1<<phase))!=0&&!FluidThermodynamics.holdsPhase(candidate,phase))fits=false;
                if(!fits)continue;
                if(projected==null)projected=new ArrayList<>(seeds);
                projected.set(i,candidate);
            }catch(IllegalArgumentException outside) {
                // An inadmissible forecast leaves the original donor seed in place.
            }
        }
        return projected==null?seeds:projected;
    }

    /** The segment of every open phase-port end at the connection flows {@code flows} and this pass's seeds; each end's
     * priority order is read off the node's state on the accepted state the step starts from, like its capacities. */
    private PhaseDraw.Segment[][] decideSegments(double[] flows) {
        var first=decideSegments(flows,null);
        // W3b stage A group 5 (W3B_NETWORK_PLAN.md section 23): a gas the seed holds is pinned at a port only where the
        // liquid its node gains within the step, at these flows and through every other end, fills its vapour space.
        boolean any=false;for(var ends:first)for(var segment:ends)any|=segment!=null&&segment.pinsGas();
        if(!any)return first;
        double[] gained=new double[graph.reservoirs().size()];double[][] leaving=new double[first.length][2];
        for(int edge=0;edge<first.length;edge++) {
            double q=flows[edge];if(q==0)continue;var pipe=graph.pipes().get(edge);
            int out=q>0?0:1,donor=out==0?pipe.first():pipe.second(),into=out==0?pipe.second():pipe.first();
            double liquid=liquidVolumeFlow(first[edge][out],donor,Math.abs(q));
            gained[into]+=liquid;gained[donor]-=liquid;leaving[edge][out]=liquid;
        }
        if(arrived==null)arrived=arrivals(graph);
        for(int node=0;node<gained.length;node++)if(arrived[node]!=null)gained[node]+=FluidThermodynamics.phaseVolume(arrived[node],FluidThermodynamics.OIL)+FluidThermodynamics.phaseVolume(arrived[node],FluidThermodynamics.AQUEOUS);
        boolean[][] displaced=new boolean[first.length][2];
        for(int edge=0;edge<first.length;edge++)for(int end=0;end<2;end++) {
            var pipe=graph.pipes().get(edge);int node=end==0?pipe.first():pipe.second();
            double vapour=graph.reservoirs().get(node).state().vaporVolume();
            displaced[edge][end]=(gained[node]+leaving[edge][end])*dt>=vapour;
        }
        return decideSegments(flows,displaced);
    }
    /** Per node, the flows (mol/s) its scheduled injections deliver, flashed at the step's start temperature and pressure;
     * null where none (for {@link #decideSegments}). */
    private FluidThermodynamics.State[] arrived;
    private FluidThermodynamics.State[] arrivals(PassiveNetwork graph) {
        var result=new FluidThermodynamics.State[graph.reservoirs().size()];double[][] rates=new double[result.length][];
        for(var transfer:graph.scheduledTransfers())if(transfer instanceof ScheduledTransfer.Injection injection) {
            double[] n=injection.molesPerSecond();var into=rates[injection.node()];
            if(into==null)rates[injection.node()]=n.clone();else for(int c=0;c<n.length;c++)into[c]+=n[c];
        }
        for(int node=0;node<result.length;node++) {
            if(rates[node]==null||!(PhaseLayout.sum(rates[node])>0))continue;var start=graph.reservoirs().get(node).state();
            try{result[node]=model.flashTP(start.temperature(),start.pressure(),rates[node],()->{});}catch(IllegalArgumentException refused){}
        }
        return result;
    }
    /** The liquid volume flow (m3/s) a donor node {@code donor} sends through an end drawing {@code q} (kg/s) under
     * {@code segment} (null: its bulk), each liquid at the donor's start state's density (before any flash on entry). */
    private double liquidVolumeFlow(PhaseDraw.Segment segment,int donor,double q) {
        var s=graph.reservoirs().get(donor).state();double volume=0;
        if(segment==null) {
            double mass=s.mass();if(!(mass>0))return 0;
            for(int phase:new int[]{FluidThermodynamics.OIL,FluidThermodynamics.AQUEOUS})if(FluidThermodynamics.holdsPhase(s,phase))volume+=q*model.phaseMass(s,phase)/mass/PortStreams.phaseDensity(model,s,phase);
            return volume;
        }
        var seed=seeds.get(donor);
        double oil=FluidThermodynamics.holdsPhase(seed,FluidThermodynamics.OIL)?PortStreams.phaseDensity(model,seed,FluidThermodynamics.OIL):0,water=FluidThermodynamics.holdsPhase(seed,FluidThermodynamics.AQUEOUS)?PortStreams.phaseDensity(model,seed,FluidThermodynamics.AQUEOUS):0;
        double[] rates=segment.rates(segment.marginalAt(PhaseDraw.held(seed)),q,oil,water);
        for(int phase:new int[]{FluidThermodynamics.OIL,FluidThermodynamics.AQUEOUS}) {
            double mass=rates[phase]+rates[3+phase];if(!(mass>0))continue;
            double rho=FluidThermodynamics.holdsPhase(s,phase)?PortStreams.phaseDensity(model,s,phase):phase==FluidThermodynamics.OIL?oil:water;
            if(rho>0)volume+=mass/rho;
        }
        return volume;
    }
    /** {@link #decideSegments(double[])} with {@code displaced} (null: every gas end as displaced, the outflow test). */
    private PhaseDraw.Segment[][] decideSegments(double[] flows,boolean[][] displaced) {
        var result=new PhaseDraw.Segment[graph.pipes().size()][2];
        for(int edge=0;edge<result.length;edge++)for(int end=0;end<2;end++) {
            var pipe=graph.pipes().get(edge);var port=end==0?pipe.firstPort():pipe.secondPort();int node=end==0?pipe.first():pipe.second();
            if(port==PassiveNetwork.PhasePort.BULK||boundaryClosed[edge]&&pipe.control() instanceof FlowControl.Passive)continue;
            var order=PhaseDraw.order(model,graph.reservoirs().get(node).state(),port);
            // An end no connection of which may carry outflow from its vessel (into a generator, blocked) draws nothing
            // over the step; its capacities are none, and the first phase its seed holds names its stream. An OVERFLOW
            // end draws the liquid above its nozzle (OpenVessel).
            var capacities=port==PassiveNetwork.PhasePort.OVERFLOW?overflowCapacities:this.capacities;
            var capacity=capacities==null||capacities[node]==null?new double[3]:capacities[node];
            double outflow=end==0?flows[edge]:-flows[edge];
            var start=graph.reservoirs().get(node).state();
            if(port==PassiveNetwork.PhasePort.BOOT_HC&&weirOffsets[edge][end]>=0){order=new int[]{FluidThermodynamics.OIL,FluidThermodynamics.GAS};}
            else if(port==PassiveNetwork.PhasePort.BOOT_HC) {
                capacity=capacity.clone();
                double waterVolume=FluidThermodynamics.phaseVolume(start,FluidThermodynamics.AQUEOUS);
                double available=Math.max(0,waterVolume-graph.reservoirs().get(node).inventory().geometry().weirVolume());
                capacity[FluidThermodynamics.AQUEOUS]=waterVolume>0?capacity[FluidThermodynamics.AQUEOUS]*available/waterVolume:0;
                if(available==0)order=new int[]{FluidThermodynamics.OIL,FluidThermodynamics.GAS};
            }
            boolean gasDisplaced=displaced==null||displaced[edge][end];
            result[edge][end]=port==PassiveNetwork.PhasePort.LIQUID?
                    PhaseDraw.Segment.mixed(capacity,seedHeld[node],PhaseDraw.held(start),outflow,FluidThermodynamics.holdsPhase(seeds.get(node),FluidThermodynamics.OIL)?PortStreams.phaseDensity(model,seeds.get(node),FluidThermodynamics.OIL):0,FluidThermodynamics.holdsPhase(seeds.get(node),FluidThermodynamics.AQUEOUS)?PortStreams.phaseDensity(model,seeds.get(node),FluidThermodynamics.AQUEOUS):0,gasDisplaced):
                    PhaseDraw.Segment.decide(order,capacity,seedHeld[node],PhaseDraw.held(start),outflow,gasDisplaced);
        }
        return result;
    }
    /**
     * The seeds of a pass whose Newton failed, with every vessel whose phase-port segments together draw a phase's whole
     * start content (each end that may draw from it pins the phase at its share of the capacity, {@code sum c dt =
     * m_start}) seeded without that content, flashed at the seed's temperature and pressure; null when no seed changes
     * its phase regime that way. A phase drawn to zero usually ends the step on its own phase boundary (a gas no liquid
     * dissolves cannot exist once it has all left), and a pass that keeps its unknowns has no root while halving the
     * step doubles the capacity and the pinned draw is still the whole phase. A starting point and a phase regime only:
     * a converged pass that holds the phase after all is corrected back by the converged-point phase correction.
     */
    List<FluidThermodynamics.State> drainedSeeds(Runnable checkpoint) {
        List<FluidThermodynamics.State> result=null;
        for(int node=0;node<layout.length;node++) {
            // An open vessel's vent draws its whole start gas (OpenVessel), as a phase pinned at its capacity does.
            boolean vents=releases!=null&&releases[node]!=null&&releases[node].vent()!=null;
            if(layout[node]==null||!vents&&(!drawsPhase[node]||capacities[node]==null))continue;
            var seed=seeds.get(node);var start=graph.reservoirs().get(node).state();
            double[] drawn=new double[3];
            if(vents)drawn[FluidThermodynamics.GAS]=releases[node].ventShare()*model.phaseMass(start,FluidThermodynamics.GAS);
            if(drawsPhase[node])for(int edge=0;edge<segments.length;edge++)for(int end=0;end<2;end++) {
                var segment=segments[edge][end];var pipe=graph.pipes().get(edge);
                if(segment==null||(end==0?pipe.first():pipe.second())!=node)continue;
                for(int phase:segment.pinned())drawn[phase]+=segment.capacity()[phase]*dt;
            }
            double[] amounts=PhaseLayout.totalAmounts(seed);boolean any=false;
            for(int phase=0;phase<3;phase++) {
                if(!(seedHeld[node]==null?FluidThermodynamics.holdsPhase(seed,phase):seedHeld[node][phase])||!FluidThermodynamics.holdsPhase(start,phase)||drawn[phase]<model.phaseMass(start,phase)*(1-1e-9))continue;
                var n=FluidThermodynamics.phaseMoles(start,phase);
                for(int c=0;c<amounts.length;c++)amounts[c]=Math.max(0,amounts[c]-n[c]);
                any=true;
            }
            if(!any||!(PhaseLayout.sum(amounts)>0))continue;
            FluidThermodynamics.State drained;
            try{drained=model.flashTP(seed.temperature(),seed.pressure(),amounts,checkpoint).withSolidState(seed.solids(),seed.solidMoments());}
            catch(com.wormzjl.icheme.science.fluid.thermo.ThermoHold outside){continue;}
            if(phaseCode(drained)==phaseCode(seed))continue;
            if(result==null)result=new ArrayList<>(seeds);
            result.set(node,drained);
        }
        return result;
    }
    /**
     * Decision D54: {@code emptying} with every vessel the failed iterate {@code x} drains to nothing added (what it holds
     * there is below 1e-6 of its stock, and the iterate's connections take at least half its stock out over the step),
     * or null when there is none. A vessel a scheduled transfer names is left to its transfer.
     */
    boolean[] runsDry(double[] x,boolean[] emptying) {
        boolean[] result=null;
        // The connections' flows are the transported ones ({@link #flow}) where the iterate decodes; its law flows where
        // it does not (a refused iterate).
        Transport[] tr;try{decode(x);tr=cachedTransport;}catch(IllegalArgumentException outsideDomain){tr=null;}
        for(int node=0;node<layout.length;node++) {
            var reservoir=graph.reservoirs().get(node);
            if(layout[node]==null||reservoir.kind()!=PassiveNetwork.NodeKind.RESERVOIR||dry[node])continue;
            if(transfersAt[node].length>0)continue;
            double held=0,left=0;for(double n:oldAmounts[node])held+=n;
            if(!(held>0))continue;
            for(int local=0;local<layout[node].size();local++)if(layout[node].totalAmountVariable(local)&&!layout[node].solidVariable(local))left+=x[offsets[node]+local];
            left*=layout[node].amountScale();
            if(!(left<1e-6*held))continue;
            double out=0;for(int edge:nodeEdges[node]){var pipe=graph.pipes().get(edge);double q=tr==null?x[edgeOffset+edge]:flow(edge,x[edgeOffset+edge],tr);out+=pipe.first()==node?q:-q;}
            double stock=solver.molarMass(oldAmounts[node])+reservoir.inventory().solids().massKg();
            if(!(out*dt>=.5*stock))continue;
            if(result==null)result=emptying==null?new boolean[layout.length]:emptying.clone();
            result[node]=true;
        }
        return result;
    }
    /** This pass's static column ends ({@link #columnEnds}) as codes for the cycle key ({@link PassKey}). */
    byte[] columnCodes(){byte[] codes=new byte[columnEnds.length];for(int edge=0;edge<codes.length;edge++)codes[edge]=(byte)columnEnds[edge];return codes;}
    /** This pass's segments as codes for the cycle key ({@link PassKey}); empty for a graph without an open phase port. */
    byte[] segmentCodes() {
        int count=0;for(var ends:segments)for(var segment:ends)if(segment!=null)count++;
        byte[] codes=new byte[count];int at=0;
        for(var ends:segments)for(var segment:ends)if(segment!=null)codes[at++]=segment.code();
        return codes;
    }
    /** Whether the converged connection flows {@code flows} land an open phase-port end in another segment than the one
     * this pass froze (the pass loop then solves the pass again with the segments re-decided from them). */
    boolean segmentsMoved(double[] flows) {
        boolean any=false;for(boolean draws:drawsPhase)any|=draws;
        if(!any)return false;
        var decided=decideSegments(flows);
        for(int edge=0;edge<segments.length;edge++)for(int end=0;end<2;end++)
            if(segments[edge][end]!=null&&!segments[edge][end].samePhases(decided[edge][end]))return true;
        return false;
    }
    /**
     * What connection {@code edge} carries out of its end {@code end} for the connection flow {@code q}, on that end's
     * node transport {@code node}: the node's bulk at a BULK end (or a closed connection's), the one phase stream the
     * segment draws where it pins none, and otherwise the priority stream's mixture ({@link PhaseDraw}): the pinned
     * phases at their capacities, at the step's start state ({@link #startPhases}), and the marginal phase at the end
     * state with the rest of {@code |q|}. The mixture is a {@link Transport} of rates ({@code mass} the mass flow, every
     * extensive field the matching rate), so every per-kilogram donor term reads it unchanged; its density is the
     * no-slip mixture's, its viscosity the phases' volume-flow average, its velocity limit the smallest of its phases'.
     */
    Transport drawn(int edge,int end,Transport node,double q){return drawn(segments[edge][end],edge,end,node,q);}
    /** {@link #drawn} under {@code segment} in place of this pass's own. */
    private double remainingAfterHc(PhaseDraw.Segment segment,Transport node,double q){
        var rates=segment.rates(segment.marginalAt(held(node)),q);
        return Math.max(0,q-rates[FluidThermodynamics.OIL]-rates[3+FluidThermodynamics.OIL]);
    }
    /**
     * The mass shares a {@code port} end of vessel {@code node} draws as the phases beside its own (entries by phase id,
     * then its own phase), from what the vessel carries this slice ({@link Separation.Carried}); null where it carries
     * nothing there. An oil outlet draws {@code waterInOil} m3 of water per m3 of oil and a water outlet {@code oilInWater}
     * of oil; a gas outlet draws the share {@code liquidInGas} of its volume as the vessel's bulk, each phase in the
     * volume the vessel holds of it. Densities and volumes are the start state's, so the shares are constants of the step.
     */
    private double[] carriedShares(int node,PassiveNetwork.PhasePort port) {
        if(port!=PassiveNetwork.PhasePort.BOOT_HC&&port!=PassiveNetwork.PhasePort.BOOT_WATER&&port!=PassiveNetwork.PhasePort.VAPOR)return null;
        var carried=graph.reservoirs().get(node).inventory().carried();var start=startPhases[node];
        if(carried.none()||start==null)return null;
        var state=graph.reservoirs().get(node).state();var oil=start[FluidThermodynamics.OIL];var water=start[FluidThermodynamics.AQUEOUS];var gas=start[FluidThermodynamics.GAS];
        double[] shares=new double[4];int own=PhaseDraw.order(model,state,port)[0];shares[3]=own;
        if(own==FluidThermodynamics.GAS) {
            if(gas==null||!(carried.liquidInGas()>0))return null;
            double vo=oil==null?0:FluidThermodynamics.phaseVolume(state,FluidThermodynamics.OIL),vw=water==null?0:FluidThermodynamics.phaseVolume(state,FluidThermodynamics.AQUEOUS);
            double total=state.vaporVolume()+vo+vw,bulk=carried.liquidInGas();
            double mo=oil==null?0:bulk*vo/total*oil.density,mw=water==null?0:bulk*vw/total*water.density;
            double sum=(1-bulk+bulk*state.vaporVolume()/total)*gas.density+mo+mw;
            shares[FluidThermodynamics.OIL]=mo/sum;shares[FluidThermodynamics.AQUEOUS]=mw/sum;
        } else {
            if(oil==null||water==null)return null;
            int other=own==FluidThermodynamics.OIL?FluidThermodynamics.AQUEOUS:FluidThermodynamics.OIL;
            double ratio=own==FluidThermodynamics.OIL?carried.waterInOil():carried.oilInWater();
            double mass=ratio*start[other].density;
            shares[other]=mass/(start[own].density+mass);
        }
        return shares[FluidThermodynamics.OIL]>0||shares[FluidThermodynamics.AQUEOUS]>0?shares:null;
    }
    /** The carried shares of end {@code end} of {@code edge} ({@link #carriedShares}) where its segment draws its own phase
     * alone, {@code marginal} at the end state; null otherwise: an end already drawing by priority (a phase run out) or
     * the two liquids together carries nothing more. */
    private double[] carriedAt(PhaseDraw.Segment segment,int edge,int end,int marginal) {
        var shares=carriedShares==null?null:carriedShares[edge][end];
        return shares!=null&&segment.pinned().length==0&&!segment.mixed()&&marginal==carriedOwn[edge][end]?shares:null;
    }
    private double[] phaseRates(PhaseDraw.Segment segment,int edge,int end,Transport node,double q){return phaseRates(segment,edge,end,node,q,true);}
    /**
     * {@link #ownRates} with what the end carries ({@link #carriedAt}): each carried phase takes its share of {@code q}
     * from the end's own phase, drawn at the step's start state as a pinned phase is and, where {@code limited}, up to
     * its capacity there (what the vessel held of it at the start, over its drawing ends), so the step never draws more
     * of a phase than the vessel held. {@code limited} is false for the stream of a vanishing flow, whose shares are the
     * limit's.
     */
    private double[] phaseRates(PhaseDraw.Segment segment,int edge,int end,Transport node,double q,boolean limited) {
        int marginal=segment.marginalAt(held(node));var rates=ownRates(segment,edge,end,node,q,marginal);
        var shares=carriedAt(segment,edge,end,marginal);if(shares==null)return rates;
        var pipe=graph.pipes().get(edge);var capacity=capacities==null?null:capacities[end==0?pipe.first():pipe.second()];
        for(int phase=FluidThermodynamics.OIL;phase<=FluidThermodynamics.AQUEOUS;phase++) {
            if(!(shares[phase]>0))continue;
            double take=shares[phase]*q;if(limited)take=Math.min(take,capacity==null?0:capacity[phase]);
            rates[3+phase]+=take;rates[marginal]-=take;
        }
        return rates;
    }
    private double[] ownRates(PhaseDraw.Segment segment,int edge,int end,Transport node,double q,int marginal){
        if(weirOffsets[edge][end]<0)return segment.rates(marginal,q,densityOf(node.phases,FluidThermodynamics.OIL),densityOf(node.phases,FluidThermodynamics.AQUEOUS));
        var rates=segment.rates(marginal,q);double remaining=remainingAfterHc(segment,node,q);
        double water=node.phases[FluidThermodynamics.GAS]==null?remaining:Math.min(remaining,Math.max(0,weirValues[edge][end]));
        rates[FluidThermodynamics.GAS]=0;rates[3+FluidThermodynamics.GAS]=0;
        int nodeId=end==0?graph.pipes().get(edge).first():graph.pipes().get(edge).second();
        if(node.phases[FluidThermodynamics.AQUEOUS]!=null)rates[FluidThermodynamics.AQUEOUS]=water;
        else if(startPhases[nodeId][FluidThermodynamics.AQUEOUS]!=null)rates[3+FluidThermodynamics.AQUEOUS]=water;
        else water=0;
        if(node.phases[FluidThermodynamics.GAS]!=null)rates[FluidThermodynamics.GAS]=remaining-water;
        else if(startPhases[nodeId][FluidThermodynamics.GAS]!=null)rates[3+FluidThermodynamics.GAS]=remaining-water;
        return rates;
    }
    private static double densityOf(Transport[] phases,int phase){return phases[phase]==null?0:phases[phase].density;}
    private Transport drawn(PhaseDraw.Segment segment,int edge,int end,Transport node,double q){return drawn(segment,edge,end,node,q,null,edge);}
    /** {@link #drawn} with a priority mixture's amounts written into {@code pool[slot]} (P-07), cleared to the zeros a new
     * array holds; a new array where {@code pool} is null. A pooled mixture lives until its slot is filled again, so only a
     * caller that is done with it by then passes a pool: {@link #assemble}'s edge pre-pass (one slot per connection and
     * role, read within the evaluation) and the caps, which read no amounts ({@link #capMoles}). */
    private Transport drawn(PhaseDraw.Segment segment,int edge,int end,Transport node,double q,double[][] pool,int slot) {
        if(segment==null||node.phases==null)return node;
        int marginal=segment.marginalAt(held(node));var pinned=segment.pinned();
        boolean carries=carriedAt(segment,edge,end,marginal)!=null;
        if(pinned.length==0&&!segment.mixed()&&weirOffsets[edge][end]<0&&!carries)return marginal<0?node:node.phases[marginal];
        var pipe=graph.pipes().get(edge);var start=startPhases[end==0?pipe.first():pipe.second()];
        if(pinned.length==1&&marginal<0&&weirOffsets[edge][end]<0)return start[pinned[0]];
        double total=segment.pinnedTotal(),basis=weirOffsets[edge][end]>=0||carries?(q==0?1:Math.abs(q)):segment.mixed()?Math.max(Math.abs(q),1):marginal<0?total:Math.max(Math.abs(q),total);
        var rates=phaseRates(segment,edge,end,node,basis,q!=0);
        double[] moles=pool==null?new double[node.moles.length]:pooled(pool,slot,node.moles.length);double volume=0,viscous=0,heat=0,limit=Double.POSITIVE_INFINITY,solidMass=0,solidVolume=0,solidCapacity=0;
        for(int k=0;k<6;k++) {
            if(!(rates[k]>0)&&!(k<3&&k==marginal))continue;
            var stream=k<3?node.phases[k]:start[k-3];double rate=rates[k],perMass=rate/stream.mass;
            for(int c=0;c<moles.length;c++)moles[c]+=perMass*stream.moles[c];
            double flowVolume=perMass*stream.volume;volume+=flowVolume;viscous+=flowVolume*stream.viscosity;heat+=rate*stream.specificEnthalpy;
            limit=Math.min(limit,stream.velocityLimit);
            solidMass+=perMass*stream.solids.mass();solidVolume+=perMass*stream.solids.volume();solidCapacity+=perMass*stream.solids.heatCapacity();
        }
        return new Transport(moles,basis,volume,basis/volume,viscous/volume,heat/basis,limit,new SolidInventory.Moments(solidMass,solidVolume,solidCapacity),null);
    }
    /** {@code pool[slot]}, of {@code length} doubles, cleared to zeros; allocated the first time (P-07). */
    private static double[] pooled(double[][] pool,int slot,int length) {
        var moles=pool[slot];
        if(moles==null||moles.length!=length)pool[slot]=moles=new double[length];else Arrays.fill(moles,0);
        return moles;
    }
    /**
     * The velocity cap (kg/s) of the direction leaving end {@code end} of {@code edge}, on that end's node transport: the
     * drawn stream's {@code density * area * velocityLimit} at a BULK end and where one phase is drawn (the form the cap
     * always had, to the bit); for a priority mixture, a volume flow of {@code area * v}, {@code v} the smallest
     * velocity limit of its phases: the pinned phases at their capacities and the marginal one filling the rest,
     * {@code C + rho_m (A v - sum c/rho)}, or, where the pinned phases alone would exceed it, the pinned mixture scaled
     * down to it, {@code C A v / sum c/rho} (continuous at {@code A v = sum c/rho}).
     */
    double capLimit(int edge,int end,Transport node){return capLimit(segments[edge][end],edge,end,node);}
    /**
     * The velocity cap (kg/s) of connection {@code edge} for the law's flow {@code y}, on the node transports {@code tr}:
     * the cap of the end it leaves ({@link #capLimit}) and a filter's cake room ({@link #filterCapFlow}, on the stream
     * drawn at the capped flow); none where the law is not saturated ({@link #saturates}).
     */
    double cap(int edge,double y,Transport[] tr) {
        if(!saturates(edge))return Double.POSITIVE_INFINITY;
        var pipe=graph.pipes().get(edge);int end=y>=0?0:1;
        return cap(segments[edge][end],edge,end,tr[end==0?pipe.first():pipe.second()],y);
    }
    /** {@link #cap} under {@code segment} at the end {@code end} the flow leaves, on that end's node transport {@code donor}. */
    private double cap(PhaseDraw.Segment segment,int edge,int end,Transport donor,double y) {
        double limit=baseCap(segment,edge,end,donor,y);
        if(weirOffsets[edge][end]>=0&&donor.phases!=null&&donor.phases[FluidThermodynamics.GAS]==null){
            boolean hc=donor.phases[FluidThermodynamics.OIL]!=null&&Arrays.stream(segment.pinned()).noneMatch(p->p==FluidThermodynamics.OIL);
            if(!hc){double oil=Arrays.stream(segment.pinned()).anyMatch(p->p==FluidThermodynamics.OIL)?segment.capacity()[FluidThermodynamics.OIL]:0;
                limit=Math.min(limit,oil+Math.max(0,weirValues[edge][end]));}
        }
        return limit;
    }
    private double baseCap(PhaseDraw.Segment segment,int edge,int end,Transport donor,double y) {
        var pipe=graph.pipes().get(edge);double limit=capLimit(segment,edge,end,donor);
        // An OVERFLOW end carries at most what stands above its nozzle over the step (OpenVessel).
        if((end==0?pipe.firstPort():pipe.secondPort())==PassiveNetwork.PhasePort.OVERFLOW)limit=Math.min(limit,overflowCap(end==0?pipe.first():pipe.second()));
        return pipe.filter()==null?limit:filterCapFlow(edge,limit,drawn(segment,edge,end,donor,saturated(y,limit),capMoles,0));
    }
    /** The transported flow (kg/s) of connection {@code edge} for its edge unknown {@code y}, the law's flow: its
     * saturation at its cap ({@link PassiveStepSolver#saturated}, {@link #cap}), the flow every booking and actuator row
     * reads; {@code y} itself where the law is not saturated. */
    double flow(int edge,double y,Transport[] tr){return saturated(y,cap(edge,y,tr));}
    /** {@link #capLimit} under {@code segment} in place of this pass's own. */
    private double capLimit(PhaseDraw.Segment segment,int edge,int end,Transport node) {
        var pipe=graph.pipes().get(edge);
        if(segment==null||node.phases==null)return node.density*pipe.minimumArea()*node.velocityLimit;
        int marginal=segment.marginalAt(held(node));var pinned=segment.pinned();
        if(segment.mixed()||weirOffsets[edge][end]>=0||carriedAt(segment,edge,end,marginal)!=null){var stream=drawn(segment,edge,end,node,1,capMoles,0);return stream.density*pipe.minimumArea()*stream.velocityLimit;}
        if(pinned.length==0){var stream=marginal<0?node:node.phases[marginal];return stream.density*pipe.minimumArea()*stream.velocityLimit;}
        var start=startPhases[end==0?pipe.first():pipe.second()];
        double velocity=marginal<0?Double.POSITIVE_INFINITY:node.phases[marginal].velocityLimit,total=segment.pinnedTotal(),pinnedVolume=0;
        for(int phase:pinned){velocity=Math.min(velocity,start[phase].velocityLimit);pinnedVolume+=segment.capacity()[phase]/start[phase].density;}
        double room=pipe.minimumArea()*velocity;
        return marginal>=0&&pinnedVolume<=room?total+node.phases[marginal].density*(room-pinnedVolume):total*(room/pinnedVolume);
    }
    /** The carrier viscosity of what {@code edge} draws out of end {@code end} (an inline filter's cake resistance scales
     * with it): the bulk's at a BULK end, the phase's own where one phase is drawn at the end state, and the phases'
     * volume-flow average for a priority mixture (the pinned ones' on the start state). */
    double drawnCarrierViscosity(int edge,int end,FluidThermodynamics.State state,Transport node,double q) {
        var segment=segments[edge][end];
        if(segment==null||node.phases==null)return solver.carrierViscosity(state);
        int marginal=segment.marginalAt(held(node));var pinned=segment.pinned();
        if(segment.mixed())return (model.phaseCarrierViscosity(state,FluidThermodynamics.OIL,solver.viscosities)+model.phaseCarrierViscosity(state,FluidThermodynamics.AQUEOUS,solver.viscosities))/2;
        if(pinned.length==0)return marginal<0?solver.carrierViscosity(state):model.phaseCarrierViscosity(state,marginal,solver.viscosities);
        var pipe=graph.pipes().get(edge);int n=end==0?pipe.first():pipe.second();var startState=graph.reservoirs().get(n).state();var start=startPhases[n];
        double total=segment.pinnedTotal();var rates=segment.rates(marginal,marginal<0?total:Math.max(Math.abs(q),total));double volume=0,value=0;
        for(int k=0;k<6;k++) {
            if(!(rates[k]>0))continue;
            var stream=k<3?node.phases[k]:start[k-3];double flowVolume=rates[k]/stream.density;volume+=flowVolume;
            value+=flowVolume*model.phaseCarrierViscosity(k<3?state:startState,k%3,solver.viscosities);
        }
        return volume>0?value/volume:model.phaseCarrierViscosity(startState,pinned[0],solver.viscosities);
    }
    /** The mass flow each phase supplies on every connection whose outflow end drew phases at the flows {@code flows} on
     * {@code states} (the converged candidate), as {@link PhaseDraw.Segment#rates}' six entries (end-state rates by phase
     * id, then start-state rates by phase id): what the reconstruction books and the transfer samples; the end state's
     * gas alone where a bulk end drew the gas of a donor holding solids but no liquid (owner decision D33, the stream
     * {@link #transport} gave it); null for an edge that drew the bulk or carried nothing, and null altogether when no
     * edge drew phases. */
    double[][] draws(double[] flows,List<FluidThermodynamics.State> states) {
        double[][] result=null;
        for(int edge=0;edge<flows.length;edge++) {
            double q=flows[edge];if(q==0)continue;
            var pipe=graph.pipes().get(edge);var donor=states.get(q>=0?pipe.first():pipe.second());
            var rates=drawRates(edge,q,donor,null);if(rates==null)continue;
            if(result==null)result=new double[flows.length][];
            result[edge]=rates;
        }
        return result;
    }
    /** {@link #draws}' entry for connection {@code edge} at flow {@code q} on its donor's {@code donor} (its transport
     * {@code transport}, or null to build it): null where the end draws the bulk. */
    double[] drawRates(int edge,double q,FluidThermodynamics.State donor,Transport transport) {
        int end=q>=0?0:1;var segment=segments[edge][end];
        if(segment==null) {
            if(!FluidThermodynamics.bulkDrawsGas(donor))return null;
            var rates=new double[6];rates[FluidThermodynamics.GAS]=Math.abs(q);return rates;
        }
        int marginal=segment.marginalAt(PhaseDraw.held(donor));
        if(marginal<0&&segment.pinned().length==0)return null;
        var pipe=graph.pipes().get(edge);
        return phaseRates(segment,edge,end,transport!=null?transport:transportAt(end==0?pipe.first():pipe.second(),donor),Math.abs(q));
    }
    /** This node's transport rows at {@code state} with no prepared temperature bundle (for a point other than the
     * pass's decoded one: the reconstruction's, the error probe's). */
    Transport transportAt(int node,FluidThermodynamics.State state){return transport(node,state,null);}
    /** An in-island furnace pair uses both trial streams. An external convection coil keeps its causal relay input. */
    private record FurnacePairs(int[] radiantAtConvection,int[] convectionAtRadiant) {
        static FurnacePairs in(PassiveNetwork graph) {
            Map<Long,Integer> radiantById=null;
            var pipes=graph.pipes();
            for(int edge=0;edge<pipes.size();edge++) {
                var pipe=pipes.get(edge);
                if(pipe.heat() instanceof FiredCoil coil&&coil.partner()!=0) {
                    if(radiantById==null)radiantById=new HashMap<>();
                    radiantById.put(pipe.id(),edge);
                }
            }
            if(radiantById==null)return null;
            int[] radiantAtConvection=new int[pipes.size()],convectionAtRadiant=new int[pipes.size()];
            Arrays.fill(radiantAtConvection,-1);Arrays.fill(convectionAtRadiant,-1);
            boolean linked=false;
            for(int edge=0;edge<pipes.size();edge++) {
                var pipe=pipes.get(edge);
                if(!(pipe.heat() instanceof ConvectionCoil coil))continue;
                var radiant=radiantById.get(coil.radiant());
                if(radiant==null||((FiredCoil)pipes.get(radiant).heat()).partner()!=pipe.id())continue;
                radiantAtConvection[edge]=radiant;convectionAtRadiant[radiant]=edge;linked=true;
            }
            return linked?new FurnacePairs(radiantAtConvection,convectionAtRadiant):null;
        }
    }
    /** The paired convection receiver's energy also reads the radiant rate, its donor state and its phase-port cap. */
    private void pairedColumns(BitSet[] columns) {
        for(int radiant=0;radiant<graph.pipes().size();radiant++) {
            int convection=furnacePairs.convectionAtRadiant()[radiant];
            if(convection<0)continue;
            var hot=graph.pipes().get(radiant);var warm=graph.pipes().get(convection);
            for(int recipient:new int[]{warm.first(),warm.second()})if(layout[recipient]!=null) {
                int from=offsets[recipient],to=from+nodeSize(recipient);
                columns[edgeOffset+radiant].set(from,to);
                for(int donor:new int[]{hot.first(),hot.second()})
                    for(int column=offsets[donor];column<offsets[donor]+nodeSize(donor);column++)
                        columns[column].set(from,to);
                for(int end=0;end<2;end++) {
                    int weir=weirOffsets[radiant][end];
                    if(weir>=0)columns[weir].set(from,to);
                }
            }
        }
    }
    /** Recompute the paired receiver for a radiant donor or flow column, including where both edges meet at that node. */
    private void pairedRows(int radiant,int perturbedNode,double[] base,double[] x,FluidThermodynamics.State[] st,Transport[] tr,double[] f) {
        int convection=furnacePairs.convectionAtRadiant()[radiant];
        if(convection<0)return;
        var pipe=graph.pipes().get(convection);
        for(int recipient:new int[]{pipe.first(),pipe.second()})if(layout[recipient]!=null&&recipient!=perturbedNode) {
            seed(recipient,f,base);nodeAccumulate(recipient,x,st,tr);nodeTargetRows(recipient,x,st,f);
        }
    }

    private void buildSparsity() {
        int count=layout.length;
        BitSet[] columns=new BitSet[size];for(int i=0;i<size;i++)columns[i]=new BitSet(size);
        for(int node=0;node<count;node++)for(int c=offsets[node];c<offsets[node]+nodeSize(node);c++)columns[c].set(offsets[node],offsets[node]+nodeSize(node));
        for(int edge=0;edge<graph.pipes().size();edge++) {
            var pipe=graph.pipes().get(edge);int er=edgeOffset+edge;
            for(int node:new int[]{pipe.first(),pipe.second()}) {
                columns[er].set(offsets[node],offsets[node]+nodeSize(node));
                for(int c=offsets[node];c<offsets[node]+nodeSize(node);c++) {
                    columns[c].set(er);
                    for(int receiver:new int[]{pipe.first(),pipe.second()})columns[c].set(offsets[receiver],offsets[receiver]+nodeSize(receiver));
                }
            }
            columns[er].set(er);
            int filter=filterOffsets[edge];if(filter>=0){columns[filter].set(er);columns[filter].set(filter);columns[er].set(filter);for(int node:new int[]{pipe.first(),pipe.second()})for(int c=offsets[node];c<offsets[node]+nodeSize(node);c++)columns[c].set(filter);}
            int control=controlOffsets[edge];
            if(control>=0) {
                columns[control].set(er);columns[control].set(control);columns[er].set(control);
                for(int node:new int[]{pipe.first(),pipe.second()}) {
                    columns[control].set(offsets[node],offsets[node]+nodeSize(node));
                    for(int c=offsets[node];c<offsets[node]+nodeSize(node);c++)columns[c].set(control);
                }
            }
        }
        for(int edge=0;edge<weirOffsets.length;edge++)for(int end=0;end<2;end++){
            int row=weirOffsets[edge][end];if(row<0)continue;var pipe=graph.pipes().get(edge);
            columns[row].set(row);columns[row].set(edgeOffset+edge);columns[edgeOffset+edge].set(row);
            for(int node:new int[]{pipe.first(),pipe.second()}){
                columns[row].set(offsets[node],offsets[node]+nodeSize(node));
                for(int c=offsets[node];c<offsets[node]+nodeSize(node);c++)columns[c].set(row);
            }
            int control=controlOffsets[edge],filter=filterOffsets[edge];
            if(control>=0){columns[row].set(control);columns[control].set(row);}
            if(filter>=0){columns[row].set(filter);columns[filter].set(row);}
        }
        if(furnacePairs!=null)pairedColumns(columns);
        // Exact structural zeros: phase allocation, T and P cannot change bulk component flow at fixed total amounts and
        // kg/s, so cancellation-noise derivatives are suppressed, not small physics. Only for a BULK end: what a phase
        // port carries is the phase split itself, so a node any end of which draws a phase keeps every entry.
        BitSet materialRows=new BitSet(size);
        for(int node=0;node<count;node++)if(layout[node]!=null) {
            int components=layout[node].componentBalanceCount();
            materialRows.set(offsets[node],offsets[node]+components-(graph.reservoirs().get(node).junction()?1:0));
            if(graph.reservoirs().get(node).junction())materialRows.set(offsets[node]+components+1);
        }
        for(int node=0;node<count;node++)if(layout[node]!=null&&!drawsPhase[node])for(int local=0;local<layout[node].size();local++)
            if(!layout[node].totalAmountVariable(local))columns[offsets[node]+local].andNot(materialRows);
        sparsity=new int[size][];for(int c=0;c<size;c++)sparsity[c]=columns[c].stream().toArray();
    }
    public int size(){return size;}
    /** {@link PhaseLayout#differenceStepScale} for a node's amount column (an amount below the trace guard at its value and
     * its seed is differenced relative to its own size; bitwise the old {@code max(floor, |value|)} otherwise), and
     * {@code max(floor, |value|)} for every other column. */
    public double differenceScale(int column,double value) {
        double seed=seedDifferenceScales[column];
        if(seed>=0)return PhaseLayout.traceDifferenceScale(Math.max(seed,Math.abs(value)));
        return Math.max(differenceFloors[column],Math.abs(value));
    }
    /**
     * Whether a chord step on the Jacobian built at {@code built} still closes at least half of every row's gap at
     * {@code now} ({@link PassiveStepSolver#chordHolds}): the slopes a connection's row states are its loss slope in its flow and its
     * slope in each end's pressure unknown. The loss slope (decision A371): a line's resistance follows its flow,
     * proportional to it on the turbulent branch, so a Newton walking a gas line from 2.0e-3 kg/s (Re 15 000) to a root
     * near rest (1.5e-5 kg/s, Re 109) halves its flow per fresh step, while a chord step keeping the resistance of a flow
     * twice as large closes only half the gap and the contraction test reads the halving as progress until the
     * iteration limit; near the velocity cap the saturated law's slope ({@link PassiveStepSolver#saturationSlope}) moves
     * faster still. The pressure slopes: a row's slope in an end's {@code ln(P / 1e5)} is that end's pressure, so a
     * node whose pressure has moved since the Jacobian was built has every row through it stated with the old pressure
     * (two junctions seeded 373 kPa apart stated a filter's pressure columns at 1/1.55 of their own and overshot its
     * gap by 0.55 per step). The rows and roots are unchanged; what moves is which Jacobian a step uses.
     */
    @Override public boolean linearisationHolds(double[] built,double[] now) {
        if(built==null||built.length!=now.length)return true;
        decode(now);
        for(int edge=0;edge<graph.pipes().size();edge++) {
            var pipe=graph.pipes().get(edge);
            if(pipe.filter()!=null||boundaryClosed[edge])continue;
            double from=built[edgeOffset+edge],to=now[edgeOffset+edge];
            if(from==to)continue;
            double qCap=cap(edge,to,cachedTransport);var donor=cachedTransport[to>=0?pipe.first():pipe.second()];var transport=drawn(edge,to>=0?0:1,donor,saturated(to,qCap));
            double then=pipe.loss(from,transport.density,transport.viscosity).massFlowDerivative();
            double slope=pipe.loss(to,transport.density,transport.viscosity).massFlowDerivative();
            if(!chordHolds(then,slope))return false;
            // The node rows' slope in this unknown is the saturation's ({@link PassiveStepSolver#saturationSlope}, 1 far
            // under the cap, 0 deep in saturation): a flow that crossed the knee of its cap since the build is on a
            // slope the Jacobian does not state. Absolute, as the slope is: two slopes both near zero differ in nothing
            // the row's other columns do not dominate.
            if(Math.abs(saturationSlope(to,qCap)-saturationSlope(from,qCap))>CHORD_CONTRACTION)return false;
        }
        for(int node=0;node<layout.length;node++)if(layout[node]!=null) {
            int at=offsets[node]+layout[node].pressureVariable();
            if(!chordHolds(1,DeterministicMath.exp(now[at]-built[at])))return false;
        }
        // Decision A423: a vessel whose wall changed branch since the build is on slopes the Jacobian does not state.
        if(builtWall!=null)for(int node=0;node<layout.length;node++)if(builtWall[node]!=null
                &&!builtWall[node].equals(PhaseLayout.wall(cachedStates[node],graph.reservoirs().get(node).inventory().wallCapacity())))return false;
        return true;
    }
    /** Decision A423: the wall's branches of every vessel at {@code base}, read by the sweep's rows and the chord test. */
    @Override public void linearising(double[] base) {
        decode(base);var walls=new PhaseLayout.Wall[layout.length];
        for(int node=0;node<layout.length;node++)if(layout[node]!=null&&!graph.reservoirs().get(node).junction())
            walls[node]=PhaseLayout.wall(cachedStates[node],graph.reservoirs().get(node).inventory().wallCapacity());
        builtWall=sweepWall=walls;
    }
    @Override public void linearised(){sweepWall=null;}
    public double maximumStep(double[] variables,double[] direction) {
        double alpha=1;
        // Approach a nonnegative amount boundary directly instead of repeatedly halving a step that misses it by
        // roundoff; this bounds a trial step and never clips accepted inventory. Below clampFloors the unknown is not a
        // boundary this step has to respect: a solid moment 1e-30 below its scale would bound alpha at 1e-12, where
        // x+alpha*d is bitwise x for every unknown of order one.
        for(int c=0;c<edgeOffset;c++)if(amountVariables[c]&&variables[c]>clampFloors[c]&&direction[c]<0)alpha=Math.min(alpha,.99*variables[c]/-direction[c]);
        return alpha;
    }
    /**
     * The other half of the trace rule in {@link #maximumStep}: a fluid amount at or below its floor does not bound the
     * step, so a trial that would take it below {@value #TRACE_RETENTION} of its value keeps that fraction instead, the
     * share the {@code .99} rule would have left it; the rest of the step is taken as the Newton direction says
     * ({@link PassiveStepSolver#TRACE_CLAMP_FRACTION}). Solid moments keep their own projection in {@link PhaseLayout#decode}.
     */
    @Override public void project(double[] variables,double[] candidate) {
        for(int c=0;c<edgeOffset;c++)
            if(amountVariables[c]&&!solidVariables[c]&&variables[c]<=clampFloors[c]&&candidate[c]<TRACE_RETENTION*variables[c])
                candidate[c]=TRACE_RETENTION*variables[c];
    }
    /** Solid unknowns this point leaves negative, i.e. where {@link PhaseLayout#decode}'s
     * nonnegativity projection would be active. Must be zero at every accepted point. */
    int negativeSolidUnknowns(double[] x) {
        int count=0;for(int c=0;c<edgeOffset;c++)if(solidVariables[c]&&x[c]<0)count++;
        return count;
    }
    int nodeSize(int node){return layout[node]==null?0:layout[node].size();}
    public int[][] columnRows(){if(sparsity==null)buildSparsity();return sparsity;}
    double[] initial(){return initialPoint.clone();}
    private double[] buildInitial(PassStart passStart) {
        double[] x=new double[size];for(int i=0;i<layout.length;i++)if(layout[i]!=null){var encoded=layout[i].encode(seeds.get(i));System.arraycopy(encoded,0,x,offsets[i],encoded.length);}
        int edges=graph.pipes().size();
        double[] q=new double[edges],head=new double[edges];boolean[] headSet=new boolean[edges];
        solver.seeding.startPoint(graph,modes,boundaryClosed,pipeIdentities,seeds,q,head,headSet,passStart);double[] allowedRestart=passStart.allowedRestart();
        if(allowedRestart!=null&&allowedRestart.length==edges)for(int i=0;i<edges;i++)if(!Double.isNaN(allowedRestart[i]))q[i]=allowedRestart[i];
        for(int i=0;i<edges;i++) {
            x[edgeOffset+i]=q[i];
            if(headSet[i]&&controlOffsets[i]>=0)x[controlOffsets[i]]=head[i];
        }
        for(int i=0;i<filterOffsets.length;i++)if(filterOffsets[i]>=0)x[filterOffsets[i]]=graph.pipes().get(i).filter().loading();
        return x;
    }
    List<FluidThermodynamics.State> states(double[] x) {
        decode(x);
        var states=new ArrayList<FluidThermodynamics.State>(layout.length);
        for(var state:cachedStates)states.add(state);
        return states;
    }
    /**
     * Drops the per-node decode cache; the next decode rebuilds every node exactly. The node bundles stay, retired
     * ({@link FluidThermodynamics.Prepared#retire}: each keeps its kernel workspace and nothing it derived), for the
     * solver's next pass to take over ({@link #adoptPrepared}) or for this pass's next decode: either refills each at its
     * first use as a new bundle, so no value and no preparation count changes, and no workspace is built again each step
     * (batch 2026-11-15-fluid-step-cost, S2; before, review 8.7 (c) E2 dropped them, about two kernel workspaces a laid-out
     * node a step). The retained last solve keeps those workspaces between steps.
     */
    void releaseDecodeCache() {
        Arrays.fill(cachedStates,null);Arrays.fill(cachedTransport,null);
        for(var bundle:cachedPrepared)if(bundle!=null)bundle.retire();
        for(var bundle:probePrepared)if(bundle!=null)bundle.retire();
    }
    /** Brings the per-node decode cache up to {@code x}; the residual reads the arrays directly. */
    private void decode(double[] x) {
        if(hasWeirs)for(int edge=0;edge<weirOffsets.length;edge++)for(int end=0;end<2;end++)if(weirOffsets[edge][end]>=0)weirValues[edge][end]=x[weirOffsets[edge][end]];
        for(int i=0;i<layout.length;i++) {
            boolean same=cachedStates[i]!=null&&(layout[i]==null||Arrays.mismatch(x,offsets[i],offsets[i]+layout[i].size(),cachedVariables[i],0,cachedVariables[i].length)<0);
            if(same)continue;
            if(layout[i]!=null) {
                double temperature=layout[i].temperature(x,offsets[i]);
                // Refilled in place (P-18): this node's state and transport are decoded again from it just below.
                if(cachedPrepared[i]==null)solver.preparedBuilt++;
                cachedPrepared[i]=model.prepare(temperature,cachedPrepared[i]);
            }
            FluidThermodynamics.State state;
            // A trial a typed thermodynamic hold refused names the node it was refused at; see ThermoHold.
            try{state=layout[i]==null?graph.reservoirs().get(i).state():layout[i].decode(x,offsets[i],cachedPrepared[i]);}
            catch(com.wormzjl.icheme.science.fluid.thermo.ThermoHold hold){throw hold.at(graph.reservoirs().get(i).id());}
            // Only a laid-out node prepared this slot for its current temperature. A fixed node can
            // inherit a live bundle from another active-set pass at a different temperature.
            var transport=transport(i,state,layout[i]==null?null:cachedPrepared[i]);
            cachedStates[i]=state;cachedTransport[i]=transport;
            if(layout[i]!=null)System.arraycopy(x,offsets[i],cachedVariables[i],0,layout[i].size());
        }
    }
    /**
     * What a connection carries out of one node, per kilogram and for its loss and cap: the conserved amounts and their
     * mass (the normaliser), the volume and density, the viscosity, the specific enthalpy, the velocity limit and the
     * solid moments. A node's cached entry is its BULK stream, the whole state as the donor terms have always read it
     * ({@code moles/mass} is the double {@code moles/state.mass()} was), and on a donor holding solids but no liquid its
     * gas stream (owner decision D33, {@link FluidThermodynamics#bulkDrawsGas}); {@code phases} are its phase streams by
     * phase id (null for a phase it does not hold), built only for a node an end of which is a phase port (decision D11).
     */
    record Transport(double[] moles,double mass,double volume,double density,double viscosity,double specificEnthalpy,double velocityLimit,
                             SolidInventory.Moments solids,Transport[] phases) {
    }
    /** Which phases node transport {@code node} holds (a phase stream is built exactly for a phase it holds), written into
     * one scratch (P-07): every caller hands it at once to {@link PhaseDraw.Segment#marginalAt}, which keeps nothing. */
    private boolean[] held(Transport node) {
        var held=heldScratch;held[0]=node.phases[0]!=null;held[1]=node.phases[1]!=null;held[2]=node.phases[2]!=null;
        return held;
    }
    /** This node's transport rows at {@code state}: the bulk stream in the arithmetic it always had (the gas stream where
     * a bulk end draws the gas, D33), plus its phase streams where an end of it is a phase port. The one builder for the
     * whole-island decode and the block Jacobian sweep. The gas stream's amounts per kilogram are the fluid's totals over
     * its mass, so the structural zeros of a BULK end's columns ({@link #buildSparsity}) hold for it as well. */
    private Transport transport(int node,FluidThermodynamics.State state,FluidThermodynamics.Prepared prepared) {
        var bulk=FluidThermodynamics.bulkDrawsGas(state)?phase(model.phaseStream(state,FluidThermodynamics.GAS,prepared,solver.viscosities))
                :new Transport(PhaseLayout.totalAmounts(state),state.mass(),state.volume(),state.mass()/state.volume(),solver.viscosity(state,prepared),
                state.enthalpy()/state.mass(),model.velocityLimit(state),state.solidMoments(),null);
        if(!drawsPhase[node])return bulk;
        var phases=new Transport[3];
        for(int phase=0;phase<3;phase++){var stream=model.phaseStream(state,phase,prepared,solver.viscosities);if(stream!=null)phases[phase]=phase(stream);}
        return new Transport(bulk.moles,bulk.mass,bulk.volume,bulk.density,bulk.viscosity,bulk.specificEnthalpy,bulk.velocityLimit,bulk.solids,phases);
    }
    private static Transport phase(FluidThermodynamics.PhaseStream stream) {
        return new Transport(stream.molesView(),stream.mass(),stream.volume(),stream.mass()/stream.volume(),stream.viscosity(),
                stream.specificEnthalpy(),stream.velocityLimit(),stream.solidMoments(),null);
    }
    public double[] residual(double[] x) {
        while(true) {
            double[] f=null;RuntimeException failed=null;
            evaluating();
            try{decode(x);f=new double[size];assemble(x,cachedStates,cachedTransport,cachedPrepared,f);}
            catch(RuntimeException failure){failed=failure;}
            if(evaluated(failed))return f;
        }
    }
    /** The largest row, each times its {@link #rowWeights} weight. */
    @Override public double residualNorm(double[] residual) {
        if(rowWeights==null)return SparseNewton.Equations.super.residualNorm(residual);
        double maximum=0;for(int row=0;row<residual.length;row++)maximum=Math.max(maximum,rowWeights[row]*Math.abs(residual[row]));
        return maximum;
    }
    /** {@link #rowWeights} from this pass's seeds and layouts. The amount {@code s} each balance row is stated over is read
     * off the row itself: its change when every target moves by one mole is {@code -1/s}. */
    private double[] rowWeights() {
        double[] weights=null;
        for(int node=0;node<layout.length;node++) {
            var reservoir=graph.reservoirs().get(node);var kind=reservoir.kind();var seed=seeds.get(node);
            if(layout[node]==null||kind!=PassiveNetwork.NodeKind.RESERVOIR&&kind!=PassiveNetwork.NodeKind.PORT||holdsNoFluid(reservoir))continue;
            double capacity=reservoir.inventory().wallCapacity();
            if(!PhaseLayout.taut(seed,capacity))continue;
            double stiffness;
            try{double speed=model.isothermalAcousticBound(seed);stiffness=seed.mass()*speed*speed/seed.volume()/Math.max(1e5,seed.pressure());}
            catch(IllegalArgumentException outsideDomain){continue;}
            if(!Double.isFinite(stiffness))continue;
            var rows=layout[node];int balances=rows.componentBalanceCount();double[] x=rows.encode(seed),targets=new double[oldAmounts[node].length];
            double[] at=new double[rows.size()],shifted=new double[rows.size()];
            rows.balanceRows(seed,targets,0,capacity,Double.NaN,at,0,x,null);
            Arrays.fill(targets,1);rows.balanceRows(seed,targets,0,capacity,Double.NaN,shifted,0,x,null);
            if(weights==null){weights=new double[size];Arrays.fill(weights,1);}
            int offset=offsets[node];
            for(int row=0;row<balances;row++){double perMole=at[row]-shifted[row];if(perMole>0&&Double.isFinite(perMole))weights[offset+row]=Math.max(1,stiffness/(rows.amountScale()*perMole));}
            if(seed.vaporVolume()/seed.volume()<.01)weights[offset+balances]=10;
            weights[offset+balances+1]=Math.max(1,stiffness);
        }
        return weights;
    }
    /**
     * One complete residual from already decoded node states. The three passes are the whole
     * island's version of the three assemblies a block Jacobian sweep performs for a single
     * perturbed column, and they are the only place each row's formula exists.
     */
    private void assemble(double[] x,FluidThermodynamics.State[] st,Transport[] tr,FluidThermodynamics.Prepared[] pr,double[] f) {
        // Finding P-03 (batch 2026-11-13-codebase-review): each connection's transported flow and the streams of its
        // ends are read once here, in the arithmetic every row read them in, and the rows below read them back.
        for(int edge=0;edge<edgeFlow.length;edge++) {
            var pipe=graph.pipes().get(edge);double q=flow(edge,x[edgeOffset+edge],tr);
            // P-07: a priority mixture's amounts go into this connection's own slots, read only within this evaluation.
            edgeFlow[edge]=q;int end=q>=0?0:1;edgeDonor[edge]=drawn(segments[edge][end],edge,end,tr[q>=0?pipe.first():pipe.second()],q,donorMoles,edge);
            edgeSuction[edge]=q>=0?edgeDonor[edge]:drawn(segments[edge][0],edge,0,tr[pipe.first()],q,suctionMoles,edge);
        }
        edgesRead=true;
        try {
            for(int node=0;node<layout.length;node++)if(layout[node]!=null)nodeAccumulate(node,x,st,tr);
            for(int edge=0;edge<graph.pipes().size();edge++)edgeRows(edge,x,st,tr,f);
            for(int node=0;node<layout.length;node++)if(layout[node]!=null)nodeRows(node,x,st,pr,f);
            if(hasWeirs)weirRows(x,st,tr,f);
        } finally {edgesRead=false;}
    }
    /**
     * P-03: per connection, what {@link #assemble}'s pre-pass read at its point: the transported flow ({@link #flow}), the
     * stream of the end the flow leaves and the first end's (suction) stream ({@link #drawn}). Read only while
     * {@code edgesRead}: the block sweep moves one node or one edge per column and reads its own.
     */
    private final double[] edgeFlow;private final Transport[] edgeDonor,edgeSuction;private boolean edgesRead;
    /** P-07: per connection, the arrays a priority mixture's amounts are written into by the pre-pass, the donor stream's
     * and the suction stream's ({@link #drawn(PhaseDraw.Segment,int,int,Transport,double,double[][],int)}); refilled at each
     * evaluation, read through {@code edgeDonor} and {@code edgeSuction} only while {@code edgesRead}. One slot,
     * {@code capMoles}, for the mixtures a cap reads its density, velocity limit and solids off (it reads no amounts). */
    private final double[][] donorMoles,suctionMoles,capMoles=new double[1][];
    /** Per node, the indices of the scheduled transfers that name it, in the graph's order (finding P-14): the node
     * accumulation reads them in place of a scan of every transfer per node and evaluation. */
    private final int[][] transfersAt;
    private static int[][] transfersAt(PassiveNetwork graph) {
        int nodes=graph.reservoirs().size();int[] count=new int[nodes];
        for(var transfer:graph.scheduledTransfers())count[transfer.node()]++;
        int[][] at=new int[nodes][];for(int i=0;i<nodes;i++)at[i]=new int[count[i]];Arrays.fill(count,0);
        for(int t=0;t<graph.scheduledTransfers().size();t++){int node=graph.scheduledTransfers().get(t).node();at[node][count[node]++]=t;}
        return at;
    }
    private double flowAt(int edge,double[] x,Transport[] tr){return edgesRead?edgeFlow[edge]:flow(edge,x[edgeOffset+edge],tr);}
    private Transport donorAt(int edge,Transport[] tr,double flow) {
        if(edgesRead)return edgeDonor[edge];
        var pipe=graph.pipes().get(edge);return drawn(edge,flow>=0?0:1,tr[flow>=0?pipe.first():pipe.second()],flow);
    }
    private Transport suctionAt(int edge,Transport[] tr,double flow){return edgesRead?edgeSuction[edge]:drawn(edge,0,tr[graph.pipes().get(edge).first()],flow);}
    /** Projection complementarity: retained water, all-water, or a water/gas split at the weir. */
    private void weirRows(double[] x,FluidThermodynamics.State[] st,Transport[] tr,double[] f){
        for(int edge=0;edge<weirOffsets.length;edge++)for(int end=0;end<2;end++){
            int row=weirOffsets[edge][end];if(row<0)continue;var pipe=graph.pipes().get(edge);int node=end==0?pipe.first():pipe.second();
            double y=x[edgeOffset+edge],outward=end==0?y:-y;var segment=segments[edge][end];var donor=tr[node];
            double free=outward>0?Math.max(0,saturated(outward,baseCap(segment,edge,end,donor,outward))):0;
            double remaining=segment==null||donor.phases==null?0:remainingAfterHc(segment,donor,free);
            double z=x[row],volume=FluidThermodynamics.phaseVolume(st[node],FluidThermodynamics.AQUEOUS);
            double density=volume>0?model.phaseMass(st[node],FluidThermodynamics.AQUEOUS)/volume:1;
            double excess=volume-graph.reservoirs().get(node).inventory().geometry().weirVolume();
            f[row]=(z-Math.clamp(z+density*excess/dt,0,remaining))/Math.max(.001,free);
        }
    }
    /**
     * This node's accumulated backward-Euler targets: the inventory it started from, the
     * scheduled transfers that name it, and every incident edge in edge order - which is the
     * order the whole-island loop reached them in, so the sums are the same doubles. A node
     * without a layout owns nothing and is only ever a donor or receiver, so its accumulators
     * are never read.
     */
    private void nodeAccumulate(int node,double[] x,FluidThermodynamics.State[] st,Transport[] tr) {
        double[] target=targets[node],in=incoming[node];
        System.arraycopy(startMoments(node),0,solidTargets[node],0,3);Arrays.fill(solidIncoming[node],0);
        System.arraycopy(oldAmounts[node],0,target,0,target.length);Arrays.fill(in,0);
        double stored=graph.reservoirs().get(node).inventory().internalEnergy(),mass=0,heat=0,net=0;
        for(int t:transfersAt[node]) {
            var transfer=graph.scheduledTransfers().get(t);var state=st[node];
            if(transfer instanceof ScheduledTransfer.Withdrawal withdrawal) {
                // A module's withdrawal takes the vessel's bulk, its solids at their share of the mass (D23 (6)), on the
                // state's own amounts: the node's transport is what a connection's bulk end draws (the gas, under D33).
                double withdrawn=dt*withdrawal.massKgPerSecond();var n=PhaseLayout.totalAmounts(state);
                for(int c=0;c<n.length;c++)target[c]-=withdrawn*n[c]/state.mass();
                stored-=withdrawn*state.enthalpy()/state.mass();
                var moments=state.solidMoments().values();for(int c=0;c<3;c++)solidTargets[node][c]-=withdrawn*moments[c]/state.mass();
            } else if(transfer instanceof ScheduledTransfer.Injection input) {
                var rates=input.molesPerSecond();
                for(int c=0;c<rates.length;c++)target[c]+=dt*rates[c];
                var moments=input.solidsPerSecond().moments().values();for(int c=0;c<3;c++)solidTargets[node][c]+=dt*moments[c];
                stored+=dt*input.totalEnergyPerSecond();
            }
        }
        // An open vessel's vent and spill (OpenVessel): fixed amounts read off the step's start state.
        if(releases!=null&&releases[node]!=null)for(var released:releases[node].each()) {
            var n=released.moles();for(int c=0;c<n.length;c++)target[c]-=n[c];
            var moments=released.moments().values();for(int c=0;c<3;c++)solidTargets[node][c]-=moments[c];
            stored-=released.energyJoule();
        }
        // A kettle's pool (W7): the bundle's heat into the vessel over the step, on its start state.
        double pooled=poolHeat(graph.reservoirs().get(node));stored+=dt*pooled;heat+=pooled;
        if(graph.reservoirs().get(node).inventory().tower()!=null) {
            double[] flows;if(edgesRead)flows=edgeFlow;else{flows=new double[graph.pipes().size()];for(int e=0;e<flows.length;e++)flows[e]=flow(e,x[edgeOffset+e],tr);}
            // The stream each spray edge delivers, as this point's transport draws it (COOLING_TOWER_PLAN.md 3.1).
            double[][] drawn=null;
            for(int edge:nodeEdges[node]){var pipe=graph.pipes().get(edge);boolean first=pipe.first()==node;
                if(pipe.portAt(node)!=PassiveNetwork.PhasePort.SPRAY||!((first?-flows[edge]:flows[edge])>0))continue;
                int donor=first?pipe.second():pipe.first();var rates=drawRates(edge,flows[edge],st[donor],tr[donor]);
                if(rates!=null){if(drawn==null)drawn=new double[flows.length][];drawn[edge]=rates;}}
            var exchange=TowerExchange.at(graph,node,Arrays.asList(st),flows,drawn,model);
            for(int c=0;c<target.length;c++)target[c]-=dt*exchange.loss(c)/model.molecularWeight(c);
            stored-=dt*exchange.energyWatt();heat-=exchange.energyWatt();net-=exchange.massLoss();
        }
        for(int edge:nodeEdges[node]) {
            var pipe=graph.pipes().get(edge);int a=pipe.first(),b=pipe.second();double flow=flowAt(edge,x,tr);
            // The donor terms read the stream of the end the flow leaves: the donor's bulk at a BULK end (the same
            // doubles as its whole state), its drawn phase at a phase port.
            boolean first=node==a;int donor=flow>=0?a:b;var upstream=st[donor];var transport=donorAt(edge,tr,flow);
            boolean receiver=node==(flow>=0?b:a);
            double captureFraction=pipe.filter()==null?0:transport.solids.mass()/transport.mass;
            double deliveredFlow=Math.abs(flow)*(1-captureFraction);
            net+=receiver?deliveredFlow:-Math.abs(flow);
            var amounts=transport.moles;double moving=dt*flow;
            for(int c=0;c<amounts.length;c++){double moved=moving*amounts[c]/transport.mass;target[c]+=first?-moved:moved;}
            var moments=momentsOf(transport.solids,carriedMoments);
            for(int c=0;c<3;c++)if(!receiver||pipe.filter()==null)solidTargets[node][c]+=(first?-1:1)*moving*moments[c]/transport.mass;
            stored+=(first?-1:1)*moving*transport.specificEnthalpy;
            // What arrives at this node, on the live upwind: read only by a junction's rows, which mix it into the
            // junction's owned stock like a vessel's inflow.
            int mixed=donor;
            if(receiver) {
                var source=st[mixed];var carried=transport;var sourceAmounts=carried.moles;
                double sourceCapture=pipe.filter()==null?0:carried.solids.mass()/carried.mass;
                double sourceEnthalpy=carried.specificEnthalpy;
                mass+=Math.abs(flow)*(1-sourceCapture);
                for(int c=0;c<sourceAmounts.length;c++)in[c]+=Math.abs(flow)*sourceAmounts[c]/carried.mass;
                heat+=Math.abs(flow)*sourceEnthalpy;
                var sourceMoments=momentsOf(carried.solids,mixedMoments);
                if(pipe.filter()==null&&melting!=null&&melting.any(node)&&carried.solids.mass()>0) {
                    // Owner decision D61: the crystal this junction is above the melting point of arrives as its
                    // component, with its offset (the crystal's enthalpy), and only the solids it keeps mix as solids.
                    var split=melting.split(node,source.solids(),sourceAmounts.length);double solidRate=Math.abs(flow)*carried.solids.mass()/carried.mass;
                    for(int c=0;c<sourceAmounts.length;c++)if(split.mass()[c]>0)in[c]+=solidRate*split.mass()[c]/model.molecularWeight(c);
                    heat+=solidRate*split.energy();
                    if(!split.all()){double[] removed={split.share(),split.volume(),split.capacity()};
                        for(int c=0;c<3;c++)solidIncoming[node][c]+=Math.max(0,Math.abs(flow)*sourceMoments[c]/carried.mass-solidRate*removed[c]);}
                }
                else if(pipe.filter()==null)for(int c=0;c<3;c++)solidIncoming[node][c]+=Math.abs(flow)*sourceMoments[c]/carried.mass;
                else heat-=Math.abs(flow)*(carried.solids.enthalpy(source.temperature(),source.pressure())/carried.mass);
            }
            if(receiver&&pipe.filter()!=null) {
                double capturedRate=Math.abs(flow)*(transport.solids.enthalpy(upstream.temperature(),upstream.pressure())/transport.mass);
                stored-=dt*capturedRate;
            }
            if(!first&&(pipe.control() instanceof FlowControl.Mover||pipe.control() instanceof FlowControl.Turbine)) {
                int control=controlOffsets[edge];double head=control<0?0:x[control]*1e5;
                // Metered on the suction stream's density (its bulk at a BULK end) and, for a compressor, the suction
                // node's pressure at this trial (decision D8: suction properties only). A turbine's work leaves the
                // stream (negative), its drop read from the trial inlet to the trial exhaust node's pressure; the
                // sparsity declares this row on both ends' columns and the flow's (buildSparsity).
                double power=moverPower(edge,flow,head,suctionAt(edge,tr,flow).density,st[a].pressure(),st[b].pressure());
                stored+=dt*power;heat+=power;
            }
            // A heat-exchanging side delivers its donor's stream with the heat it took up crossing the wall, booked like
            // a mover's work into the receiver's energy and its inflow (batch 2026-10-27-heat-exchanger-design).
            if(receiver&&pipe.heat()!=null){double taken=trialCoilHeat(edge,transport.specificEnthalpy,flow,x,tr);stored+=dt*taken;heat+=taken;}
        }
        // An emptying vessel standing as a junction (decision D54, emptyingGraph) passes its stock as well as what arrives.
        energy[node]=stored;incomingMass[node]=mass;incomingEnergy[node]=heat;netMass[node]=solver.emptyings!=null&&solver.emptyings[node]!=null?net+solver.emptyings[node].drain():net;
    }
    /** {@code moments.values()} written into {@code into} (P-07): the same three doubles in the same order (mass, volume,
     * heat capacity), every element written. */
    private static double[] momentsOf(SolidInventory.Moments moments,double[] into) {
        into[0]=moments.mass();into[1]=moments.volume();into[2]=moments.heatCapacity();
        return into;
    }
    /** Node {@code node}'s solids' moments at the step's start, {@code inventory().solids().moments().values()} of its graph
     * node, worked out once per pass (P-07); the caller only reads it. */
    private double[] startMoments(int node) {
        var moments=startMoments[node];
        if(moments==null)startMoments[node]=moments=graph.reservoirs().get(node).inventory().solids().moments().values();
        return moments;
    }
    /**
     * The heat (W, into the stream) heat-exchanging connection {@code edge} takes up when it carries {@code flow} drawn at
     * {@code specificEnthalpy} from the end the flow leaves: its side's march ({@link HeatSide#heat}) on that end's curve
     * ({@link #passCurves}). Zero for a plain connection, at no flow, and from an end that draws nothing. The residual, the
     * block Jacobian and, through {@link #bookedHeat}, the reconstruction ({@link ConservativeTransport.EdgeHeat}) all
     * read this one function (batch 2026-10-27-heat-exchanger-design). It never samples: an end whose curve the pass does
     * not hold yet is noted and reads zero, and its evaluation is discarded ({@link #evaluated}).
     */
    /** Read the other passage's radiant duty from this same Newton point. This closes the thermal loop through the
     * actual flow and fluid states, including a condenser or a junction held between the two passages. */
    private double trialCoilHeat(int edge,double inlet,double flow,double[] x,Transport[] tr) {
        int radiant=furnacePairs==null?-1:furnacePairs.radiantAtConvection()[edge];
        if(radiant<0)return sideHeat(edge,inlet,flow);
        double radiantFlow=flowAt(radiant,x,tr);
        if(radiantFlow==0)return 0;
        var radiantDonor=donorAt(radiant,tr,radiantFlow);
        double flue=ConvectionCoil.flueOf(sideHeat(radiant,radiantDonor.specificEnthalpy,radiantFlow));
        return sideHeat(edge,inlet,flow,flue);
    }

    double sideHeat(int edge,double specificEnthalpy,double flow) {
        return sideHeat(edge,specificEnthalpy,flow,Double.NaN);
    }
    private double sideHeat(int edge,double specificEnthalpy,double flow,double localFlue) {
        var pipe=graph.pipes().get(edge);
        if(pipe.heat()==null||flow==0)return 0;
        int end=flow>0?0:1;
        if(passRefusals!=null&&passRefusals[edge]!=null&&passRefusals[edge][end]!=null)throw passRefusals[edge][end];
        if(!passKnown[edge][end]){if(missingEdge<0){missingEdge=edge;missingEnd=end;}return 0;}
        var curve=passCurves[edge][end];
        if(curve==null)return 0;
        return Double.isNaN(localFlue)?pipe.heat().heat(curve,specificEnthalpy,flow)
                :((ConvectionCoil)pipe.heat()).heatAtFlue(curve,specificEnthalpy,flow,localFlue);
    }
    /** Pair the heat on the reconstructed donors and signed flows that the conserved booking actually uses. */
    ConservativeTransport.EdgeHeat thermalBooking() {
        if(furnacePairs==null)return this::bookedHeat;
        return new ConservativeTransport.PairedEdgeHeat() {
            private final double[] flues=new double[graph.pipes().size()];
            @Override public void prepare(double[] donor,double[] flows) {
                for(int convection=0;convection<flows.length;convection++) {
                    int radiant=furnacePairs.radiantAtConvection()[convection];
                    if(radiant>=0)flues[convection]=ConvectionCoil.flueOf(bookedHeat(radiant,donor[radiant],flows[radiant]));
                }
            }
            @Override public double heat(int edge,double enthalpy,double flow) {
                if(furnacePairs.radiantAtConvection()[edge]<0)return bookedHeat(edge,enthalpy,flow);
                if(flow!=0&&!passKnown[edge][flow>0?0:1])sample(edge,flow>0?0:1);
                return sideHeat(edge,enthalpy,flow,flues[edge]);
            }
        };
    }

    /** Uncapped radiant duty requested by the donor and signed flow of the decoded accepted point. */
    double radiantDemand(int edge,double flow) {
        if(flow==0||!(graph.pipes().get(edge).heat() instanceof FiredCoil coil))return 0;
        int end=flow>0?0:1;
        if(!passKnown[edge][end])sample(edge,end);
        var curve=passCurves[edge][end];
        return curve==null?0:coil.requested(curve,donorAt(edge,cachedTransport,flow).specificEnthalpy,flow);
    }

    /** {@link #sideHeat} for the reconstruction, outside every evaluation: an end not held yet is sampled first. */
    double bookedHeat(int edge,double specificEnthalpy,double flow) {
        var pipe=graph.pipes().get(edge);
        if(pipe.heat()!=null&&flow!=0&&!passKnown[edge][flow>0?0:1])sample(edge,flow>0?0:1);
        return sideHeat(edge,specificEnthalpy,flow);
    }
    /**
     * Finding D-02 (batch 2026-11-13-codebase-review): per heat-exchanging connection, the curve of each of its ends
     * ({@link PassiveStepSolver#sideCurve}) as far as the pass holds it ({@code passKnown}). A pass is built with the
     * curves the solver keeps already (the start balance's, the seeds', an earlier pass's), without a sample. An
     * evaluation that reaches an end without one (the start flow's donor, a flow a trial reverses or a probe moves off
     * zero) has that end sampled between two evaluations and is done again from the same point: no flash march runs
     * inside a residual or a Jacobian, and each curve is sampled where it was first needed, as before. A sample the
     * property model refuses is kept as that end's refusal and thrown where an evaluation draws from the end, as the
     * sample threw before; the checkpoint's own cancellation ends the pass.
     */
    private final HeatCurve[][] passCurves;private final boolean[][] passKnown;private RuntimeException[][] passRefusals;
    /** The first end an evaluation found without its curve, -1 for none. */
    private int missingEdge=-1,missingEnd;
    /** The solver's checkpoint for this pass: a deferred sample runs under it. */
    private final Runnable checkpoint;
    private void heldCurves() {
        for(int edge=0;edge<passCurves.length;edge++) {
            var pipe=graph.pipes().get(edge);
            if(pipe.heat()==null)continue;
            passCurves[edge]=new HeatCurve[2];passKnown[edge]=new boolean[2];
            for(int end=0;end<2;end++) {
                int node=end==0?pipe.first():pipe.second();
                if(solver.curveKept(graph,pipe,node)){passCurves[edge][end]=solver.sideCurve(graph,pipe,node,checkpoint);passKnown[edge][end]=true;}
            }
        }
    }
    /** Samples end {@code end} of heat side {@code edge} outside every evaluation, keeping a refusal as the end's. */
    private void sample(int edge,int end) {
        var pipe=graph.pipes().get(edge);
        RuntimeException[] cancelled={null};
        Runnable guarded=()->{try{checkpoint.run();}catch(RuntimeException cancel){cancelled[0]=cancel;throw cancel;}};
        try{passCurves[edge][end]=solver.sideCurve(graph,pipe,end==0?pipe.first():pipe.second(),guarded);}
        catch(RuntimeException refusal) {
            if(refusal==cancelled[0])throw refusal;
            if(passRefusals==null)passRefusals=new RuntimeException[passCurves.length][];
            if(passRefusals[edge]==null)passRefusals[edge]=new RuntimeException[2];
            passRefusals[edge][end]=refusal;
        }
        passKnown[edge][end]=true;
    }
    /** Opens an evaluation of the residual or the Jacobian. */
    private void evaluating(){missingEdge=-1;solver.inResidual=true;}
    /**
     * Closes an evaluation and says whether it stands. One that met an end without its curve does not, whatever it
     * returned or threw: that end is sampled here, between evaluations, and the caller evaluates again from the same
     * point (decode and assemble carry nothing from one evaluation to the next but the per-node decode cache, keyed by
     * the point). {@code failed}: what a standing evaluation threw, rethrown.
     */
    private boolean evaluated(RuntimeException failed) {
        solver.inResidual=false;
        if(missingEdge<0){if(failed!=null)throw failed;return true;}
        int edge=missingEdge,end=missingEnd;missingEdge=-1;
        sample(edge,end);
        return false;
    }
    /** The power (W) mover connection {@code edge} gives what it moves ({@link PassiveStepSolver#moverPower}, on this step's
     * graph): the energy rows and the reconstruction ({@link ConservativeTransport.MoverWork}) read this one function. */
    double moverPower(int edge,double massFlow,double head,double suctionDensity,double suctionPressure,double dischargePressure){return solver.moverPower(graph,edge,massFlow,head,suctionDensity,suctionPressure,dischargePressure);}
    /**
     * The cap flow (kg/s) of an inline filter's connection: the drawn stream's velocity cap {@code limit}
     * ({@link #capLimit}) and, in a step that may fill the cake, the room the cake has left over the step's own
     * retention per kilogram of {@code drawn}, the stream the flow carries.
     */
    private double filterCapFlow(int edge,double limit,Transport drawn) {
        var pipe=graph.pipes().get(edge);
        if(!solver.rateOnly) {
            // A filter has a second inlet limit: the cake it can still hold. The retained volume at the end of this step
            // is the expression the loading row states, so bounding the inlet by the room divided by the step's own
            // retention per unit mass makes the filter fill exactly at a step boundary. A rate solve carries no step and
            // no retention, so it is exempt, and a base already at capacity is left to the stage guard.
            double retainedPerMass=drawn.solids.volume()/drawn.mass;
            double room=pipe.filter().capacity()*(1-FILTER_CAPACITY_MARGIN)-pipe.filter().captured().volume();
            if(retainedPerMass>0&&room>0)limit=Math.min(limit,room/(dt*retainedPerMass));
        }
        return limit;
    }
    /** The hydraulic row of one edge and its actuator row, from the two endpoint states alone. */
    private void edgeRows(int edge,double[] x,FluidThermodynamics.State[] st,Transport[] tr,double[] f) {
        // The edge unknown is the law's flow; what the connection carries is its saturation at the velocity cap of the
        // end it leaves ({@link #flow}, PassiveStepSolver.saturated), the flow every booking and actuator row reads. The
        // loss is the law's at the law's flow, in the stream of the end the flow leaves.
        var pipe=graph.pipes().get(edge);int a=pipe.first(),b=pipe.second();double law=x[edgeOffset+edge],flow=flowAt(edge,x,tr);
        int donor=flow>=0?a:b;var transport=donorAt(edge,tr,flow);double rho=transport.density;
        var suction=suctionAt(edge,tr,flow);
        double dz=graph.reservoirs().get(b).elevation()-graph.reservoirs().get(a).elevation();
        double loss=pipe.pressureDrop(law,rho,transport.viscosity);
        if(pipe.filter()!=null){
            // The cake is loaded, and its resistance scaled, per kilogram of the drawn stream: its fluid volume, its
            // carrier's viscosity and its solids.
            var state=st[donor];double fluidVolume=transport.volume-transport.solids.volume();double carrier=drawnCarrierViscosity(edge,flow>=0?0:1,state,tr[donor],flow);
            double loading=x[filterOffsets[edge]];
            loss=cakeResistance(pipe.filter(),carrier,loading)*fluidVolume/transport.mass*law;
            double volume=pipe.filter().captured().volume()+(solver.rateOnly?0:dt)*Math.abs(flow)*transport.solids.volume()/transport.mass;
            f[filterOffsets[edge]]=loading-volume/pipe.filter().capacity();
        }
        int control=controlOffsets[edge];double head=control<0?0:x[control]*1e5;
        double signedHead=pipe.control() instanceof FlowControl.Mover?head:-head;
        // The static column is this pass's, never the live iterate's upwind end ({@link #columnEnds}); on a flat island
        // dz is zero and the term is bitwise the zero it was. A pump's column is the fluid it moves, the suction node's
        // transported density at this trial (the density its rise limit, target row, velocity clamp and shutoff margin
        // read), never the destination's (decision D6). The column end is the pass's; its density is the trial's, the
        // column that end's port stands in the connection ({@link PortStreams#columnDensity}, owner decision D34).
        double column=pipe.control() instanceof FlowControl.Mover?suction.density
                :columnEnds[edge]==0?PortStreams.columnDensity(model,st[a],pipe.firstPort(),pipe.volume()):PortStreams.columnDensity(model,st[b],pipe.secondPort(),pipe.volume());
        double driving=PortStreams.columnEndPressure(model,graph.reservoirs().get(a),pipe.firstPort(),st[a],column)-PortStreams.columnEndPressure(model,graph.reservoirs().get(b),pipe.secondPort(),st[b],column)-column*GRAVITY*dz+signedHead;
        // Every pressure-dimensioned row of this edge is stated in the island's own pressure,
        // never in a fixed 1e5 Pa unit; see {@link #pressureScales}. The throttled-inlet row
        // below and the closed-edge row are mass flows and keep their own flow scale.
        double pressureScale=pressureScales[edge];
        f[edgeOffset+edge]=(driving-loss)/pressureScale;
        if(boundaryClosed[edge]&&control<0)f[edgeOffset+edge]=flow;
        if(control>=0)f[control]=switch(modes.get(edge)) {
            // A mover on its own law (batch 2026-11-03-rotating-machinery): its head against the law at its suction volume
            // flow (a set-flow mover's in the flow form, FlowControl.Mover#lawResidual), the volume flow on the trial's suction
            // density, the rise limit on {@link MoverSuction#riseDensity} (a set-flow pump's supply, constant over the solve) and the
            // trial's suction pressure, so the Jacobian carries what varies: the block sweep re-evaluates
            // this edge's rows for every perturbed column of its first node, and the sparsity already declares the
            // actuator row on both endpoints' columns (buildSparsity).
            case MOVER_LAW->solver.lawResidual(graph,edge,flow/suction.density,head,solver.suctions.riseDensity(edge,suction.density),st[a].pressure())/pressureScale;
            // Cavitation (owner decisions D59 (4), D14, D20 Q11 A and option D of START_HOLD_REVIEW.md): a pump on its suction limit
            // delivers the flow at which its suction end stands at its limit ({@link MoverSuction#suctionLimit}: the vapour pressure
            // of the liquid it draws plus its breakdown NPSH at the trial's suction volume flow, or the clean zone's edge), or
            // nothing where its suction cannot reach the limit even at no flow: max(-flow, (p - limit(Q))/scale), the flow in the
            // closed-edge row's own unit. D17's delivery to zero as the supplier falls to its floor is the zero-flow branch, decided
            // on the trial; the row has a slope in the flow on both branches (the old lesser-of with the supplier's no-flow pressure
            // was flat above it, and its double root at no flow stalled a 150 kPa crude line's Newton). At a tie the pressure branch.
            // The head is free, and the hydraulic row with the suction line's sets the flow.
            case PUMP_SUCTION_LIMIT->{double pressure=(PortStreams.endPressure(model,graph.reservoirs().get(a),pipe.firstPort(),st[a])-solver.suctions.suctionLimit(this,edge,flow/suction.density))/pressureScale;yield -flow>pressure?-flow:pressure;}
            case VALVE_REGULATING->{var valve=(FlowControl.Valve)pipe.control();yield valve.pressureControl()?(st[a].pressure()-valve.targetPressure())/pressureScale:(flow-valve.targetMassFlow(suction.density))/Math.max(.001,valve.targetMassFlow(suction.density));}
            case VALVE_OPEN->head/pressureScale;
            case VALVE_CONFLICT->{double u=fixedOpenings[edge];yield u==0?flow:(head-.5*flow*flow/(suction.density*boreArea(pipe)*boreArea(pipe))*(1/(u*u)-1))/pressureScale;}
            // A mover refused for the slice (decision D6) is closed: its flow is exactly zero.
            case CLOSED,INLET_WRONG_PHASE->flow;
            case PASSIVE->throw new IllegalStateException("A passive mode has no actuator unknown");
        };
    }
    /**
     * The Jacobian from per-node block perturbations instead of a coloured whole-island sweep: one local column at a
     * time, the node decoded at the perturbed point and only the rows that can see it re-assembled (its own block, the
     * block of each neighbour across an incident edge, and the hydraulic, actuator and filter-loading rows of those
     * edges; the flow, actuator and filter columns sweep the same way over their own edge). Every such row is produced
     * by the same {@link #nodeAccumulate}, {@link #edgeRows} and {@link #nodeRows} the whole-island residual uses, in the
     * same accumulation order. The cost this removes is the assembly: the coloured sweep paid a complete island residual
     * for each of its 143 to 191 colour groups.
     *
     * <p>The phases of a perturbed node come from the node's phase tangents about its base point
     * ({@link FluidThermodynamics#tangents}, one derivative evaluation per phase and node), not from the equation of
     * state at each column (ANALYTIC_JACOBIAN_PLAN.md of batch 2026-11-11-aqueous-solvents): the rows are differenced
     * as before, over states that are first order in the column. An entry is therefore the row's derivative without
     * the phases' curvature inside the step, and differs from the coloured sweep's double near its sixth digit. A
     * phase whose composition the column moves by more than {@link FluidThermodynamics#TANGENT_COMPOSITION_STEP}, and
     * a phase without a tangent, is evaluated on the equation of state for that column.</p>
     */
    public int differentiateEntries(double[] x,double[] f,double differenceStep,int[] entryOffsets,int[] entryRows,
                                    double[] entries,Runnable checkpoint) {
        return differentiateEntries(x,f,differenceStep,entryOffsets,entryRows,entries,checkpoint,true);
    }
    /** {@link #differentiateEntries} with every perturbed phase from the equation of state where {@code tangents} is
     * false: the form before the tangents, which the equivalence test measures the entries against. */
    int differentiateEntries(double[] x,double[] f,double differenceStep,int[] entryOffsets,int[] entryRows,
                             double[] entries,Runnable checkpoint,boolean tangents) {
        // An evaluation like the residual (D-02, {@link #evaluated}): one that met a heat side's end without its curve is
        // done again, whole, once that curve is sampled.
        while(true) {
            int columns=-1;RuntimeException failed=null;
            evaluating();
            try{columns=differentiate(x,f,differenceStep,entryOffsets,entryRows,entries,checkpoint,tangents);}
            catch(RuntimeException failure){failed=failure;}
            if(evaluated(failed))return columns;
        }
    }
    /**
     * The phase tangents of the node {@link #differentiate} sweeps, one per storage slot, filled again for each node
     * (batch 2026-11-15-fluid-step-cost, S2; {@link FluidThermodynamics#tangents(FluidThermodynamics.State,
     * FluidThermodynamics.Prepared, com.wormzjl.icheme.science.fluid.thermo.HydrocarbonModel.Tangent[])}). A node's
     * tangents are read only by the decodes of its own columns, and no phase they give keeps a tangent's array, so the
     * next node may overwrite them; the pass's sweeps run one at a time on its thread.
     */
    private final com.wormzjl.icheme.science.fluid.thermo.HydrocarbonModel.Tangent[] tangentStorage=new com.wormzjl.icheme.science.fluid.thermo.HydrocarbonModel.Tangent[3];
    private int differentiate(double[] x,double[] f,double differenceStep,int[] entryOffsets,int[] entryRows,
                              double[] entries,Runnable checkpoint,boolean tangents) {
        if(hasWeirs)return -1;
        FluidThermodynamics.State[] st,baseStates;Transport[] tr,baseTransport;FluidThermodynamics.Prepared[] pr,basePrepared;
        double[] trial,perturbed;
        try {
            decode(x);
            st=cachedStates.clone();tr=cachedTransport.clone();pr=cachedPrepared.clone();
            baseStates=cachedStates.clone();baseTransport=cachedTransport.clone();basePrepared=cachedPrepared.clone();
            trial=x.clone();perturbed=new double[size];
        }catch(IllegalArgumentException outsideDomain) {
            SolverDiagnostics.count(SolverDiagnostics.jacobianBlockFallbacks);return -1;
        }
        int columns=0;
        try {
            for(int node=0;node<layout.length;node++) {
                if(layout[node]==null)continue;
                // One derivative evaluation per phase of the node; every local column decodes through them.
                // Filled into this pass's three slot tangents (S2): a node's tangents are read only through its own columns.
                var nodeTangents=tangents?model.tangents(baseStates[node],basePrepared[node],tangentStorage):null;
                for(int local=0;local<layout[node].size();local++) {
                    checkpoint.run();columns++;
                    int column=offsets[node]+local;
                    double step=differenceStep*differenceScale(column,x[column]);
                    trial[column]+=step;
                    double temperature=layout[node].temperature(trial,offsets[node]);
                    var prepared=basePrepared[node]!=null&&basePrepared[node].temperature()==temperature?basePrepared[node]
                            :probe(node,temperature);
                    long exactBefore=SolverDiagnostics.ENABLED&&tangents?SolverDiagnostics.jacobianExactPhases.sum():0;
                    var state=layout[node].decode(trial,offsets[node],prepared,nodeTangents);
                    if(SolverDiagnostics.ENABLED&&tangents&&SolverDiagnostics.jacobianExactPhases.sum()>exactBefore)SolverDiagnostics.jacobianExactColumns.increment();
                    st[node]=state;pr[node]=prepared;
                    tr[node]=transport(node,state,prepared);
                    // Seed every neighbour before recomputing any of them: parallel pipes reach
                    // the same node twice, and a seed must never overwrite a recomputed row.
                    for(int edge:nodeEdges[node])seed(other(edge,node),perturbed,f);
                    nodeAccumulate(node,trial,st,tr);nodeRows(node,trial,st,pr,perturbed);
                    for(int edge:nodeEdges[node]) {
                        edgeRows(edge,trial,st,tr,perturbed);
                        int other=other(edge,node);
                        if(layout[other]!=null&&carries(edge,node,trial)) {
                            nodeAccumulate(other,trial,st,tr);nodeTargetRows(other,trial,st,perturbed);
                        }
                    }
                    if(furnacePairs!=null)for(int edge:nodeEdges[node])pairedRows(edge,node,f,trial,st,tr,perturbed);
                    write(entryOffsets,entryRows,entries,column,perturbed,f,step);
                    st[node]=baseStates[node];tr[node]=baseTransport[node];pr[node]=basePrepared[node];
                    trial[column]=x[column];
                }
            }
            for(int edge=0;edge<graph.pipes().size();edge++) {
                checkpoint.run();
                var pipe=graph.pipes().get(edge);
                // The filter's retained-volume unknown is the third column of an edge. It enters
                // this edge's clogging resistance and its own loading row and nothing else - no
                // node block carries it, which is what buildSparsity declares - so its whole
                // stencil is what one edge assembly writes.
                for(int which=0;which<3;which++) {
                    int column=which==0?edgeOffset+edge:which==1?controlOffsets[edge]:filterOffsets[edge];
                    if(column<0)continue;
                    columns++;
                    double step=differenceStep*differenceScale(column,x[column]);
                    trial[column]+=step;
                    edgeRows(edge,trial,st,tr,perturbed);
                    // No node is decoded again, so only the two endpoints' inflow rows move.
                    if(which<2)for(int node:new int[]{pipe.first(),pipe.second()})if(layout[node]!=null) {
                        seed(node,perturbed,f);nodeAccumulate(node,trial,st,tr);nodeTargetRows(node,trial,st,perturbed);
                    }
                    if(furnacePairs!=null&&which==0)pairedRows(edge,-1,f,trial,st,tr,perturbed);
                    write(entryOffsets,entryRows,entries,column,perturbed,f,step);
                    trial[column]=x[column];
                }
            }
        }catch(IllegalArgumentException outsideDomain) {
            // A perturbed node left the property domain. The coloured sweep has a bounded
            // one-sided stencil for exactly that, so hand the whole build back to it.
            SolverDiagnostics.count(SolverDiagnostics.jacobianBlockFallbacks);return -1;
        }
        SolverDiagnostics.count(SolverDiagnostics.jacobianBlockColumns,columns);
        return columns;
    }
    private int other(int edge,int node) {
        var pipe=graph.pipes().get(edge);return pipe.first()==node?pipe.second():pipe.first();
    }
    /** A row this column cannot move keeps the value the base residual already produced for it. */
    private void seed(int node,double[] perturbed,double[] f) {
        if(layout[node]!=null)System.arraycopy(f,offsets[node],perturbed,offsets[node],layout[node].size());
    }
    private void write(int[] entryOffsets,int[] entryRows,double[] entries,int column,double[] perturbed,double[] f,double step) {
        for(int entry=entryOffsets[column];entry<entryOffsets[column+1];entry++) {
            int row=entryRows[entry];entries[entry]=(perturbed[row]-f[row])/step;
        }
    }
    /** The balance, energy, volume, equilibrium and closure rows this node owns. */
    private void nodeRows(int node,double[] x,FluidThermodynamics.State[] st,FluidThermodynamics.Prepared[] pr,double[] f) {
        if(!graph.reservoirs().get(node).junction())
            layout[node].residual(st[node],targets[node],energy[node],graph.reservoirs().get(node).inventory().wallCapacity(),bagStart[node],f,offsets[node],x,pr[node],sweepWall==null?null:sweepWall[node]);
        else layout[node].junctionResidual(st[node],fractions,junctionInflow(node),netMass[node],retainedPressures[node],f,offsets[node],x,pr[node],junctionWeight);
        nodeSolidRows(node,x,st,f);
    }
    /**
     * Only the rows a change of this node's inflow can move. A block sweep that perturbs one of
     * this node's <em>neighbours</em> leaves the decoded state here untouched, so the volume
     * closure, the equilibrium rows and the amount normalization are the base residual's own
     * doubles and the sweep seeds them from it rather than recomputing them.
     */
    private void nodeTargetRows(int node,double[] x,FluidThermodynamics.State[] st,double[] f) {
        if(!graph.reservoirs().get(node).junction())
            layout[node].balanceRows(st[node],targets[node],energy[node],graph.reservoirs().get(node).inventory().wallCapacity(),bagStart[node],f,offsets[node],x,sweepWall==null?null:sweepWall[node]);
        else layout[node].junctionRows(st[node],fractions,junctionInflow(node),netMass[node],retainedPressures[node],f,offsets[node],x,junctionWeight);
        nodeSolidRows(node,x,st,f);
    }
    /**
     * This node's three aggregate solid rows against the targets this evaluation accumulated, which is what makes them
     * rows a neighbour's column can move. {@link PhaseLayout#balanceRows} and {@link PhaseLayout#junctionRows} write
     * these rows against the layout's own seed reference, so both whole-island assembly and block sweep overwrite them
     * here with the same targets in the same order, or a neighbour's column would be differentiated against the seed
     * reference while the base residual holds the real one: a silently wrong derivative rather than a missing one.
     */
    private void nodeSolidRows(int node,double[] x,FluidThermodynamics.State[] st,double[] f) {
        if(!solidSupport)return;
        if(!graph.reservoirs().get(node).junction()) {
            layout[node].solidRows(st[node],x,offsets[node],solidTargets[node],false,f);return;
        }
        // A junction's solid moments are its owned stock's, mixed with what arrives like its fluid; a rate-only solve
        // holds them at the stock's own (see junctionInflow).
        var inventory=graph.reservoirs().get(node).inventory();
        double stock = solver.molarMass(oldAmounts[node]) + inventory.solids().massKg();
        double share = mixingShare(node, stock), retained = share * stock / dt, total = incomingMass[node] + retained;
        var solids=solidMix;System.arraycopy(solidIncoming[node],0,solids,0,3);var held=heldKept[node]!=null?heldKept[node]:startMoments(node);
        for(int c=0;c<3;c++)solids[c]=solver.rateOnly?held[c]/stock:(solids[c]+held[c]*share/dt)/total;
        layout[node].solidRows(st[node],x,offsets[node],solids,true,f);
    }
    /**
     * Fills {@link #fractions} with the mass fractions this junction's rows mix to and returns the specific enthalpy,
     * and sets {@link #junctionWeight}. A step solve is backward Euler over the step on the junction's owned inventory,
     * {@code (m/dt + Q) w = (m/dt) w_old + sum q w_e} and {@code (m/dt + Q) h = E_old/dt + sum q (h_e + g dz)}, with m,
     * w_old and E_old = m h_old read from the graph this step solves. The rows are stated in amount form over the
     * junction's own mass, {@code (dt/m)(m/dt + Q)(w - w_mix)}: a row at the Newton tolerance bounds the step's species
     * error at tol m_J, and the rounding floor {@code eps (1 + dt Q/m)} does not grow as the step shrinks (in rate form
     * it did, and every refinement chain ended on it). A rate-only solve holds the junction's composition and enthalpy
     * at its owned inventory's, its pressure algebraic, and the reconstruction books its accumulation as a
     * pseudo-boundary (frozen junctions).
     */
    private double mixingShare(int node, double stock) {
        if (solver.rateOnly || solver.emptyings != null && solver.emptyings[node] != null) return 1;
        return JunctionMixing.share(graph.reservoirs().get(node).inventory(),stock, incomingMass[node], dt, model.time());
    }

    /**
     * Finds the smallest supported active parcels for an otherwise converged point whose weighted mixing rows reach
     * their floating-point resolution. The step keeps its equations and acceptance tolerances on a retry.
     */
    List<JunctionMixing.Precision> precisionLoss(double[] point,double tolerance) {
        if(point==null||point.length!=size||!mightResolveJunctionRoundoff(tolerance))return List.of();
        double[] residual=residual(point);
        boolean[] rounded=new boolean[residual.length];
        var requests=new ArrayList<JunctionMixing.Precision>();
        for(int node=0;node<layout.length;node++) {
            if(!backedFluidJunction(node))continue;
            double delivery=dt*incomingMass[node];
            if(!(delivery>0))continue;
            double target=junctionInflow(node);
            if(!Double.isFinite(junctionWeight))continue;
            double ulp=layout[node].identifyMixingRoundoff(cachedStates[node],fractions,target,junctionWeight,
                    residual,offsets[node],tolerance,rounded);
            if(!(ulp>0))continue;
            // Allow 16 ulps for the residual of the new mixture, including its thermodynamic reconstruction.
            double turnover=tolerance/(16*ulp)-1;
            if(!(turnover>0)||!Double.isFinite(turnover))return List.of();
            requests.add(new JunctionMixing.Precision(node,delivery,delivery/turnover,cachedStates[node]));
        }
        for(int row=0;row<residual.length;row++) {
            double weight=rowWeights==null?1:rowWeights[row];
            if(!rounded[row]&&!(weight*Math.abs(residual[row])<=tolerance))return List.of();
        }
        return requests;
    }

    /** Most failed solves have ample resolution. Check the last assembled streams before re-evaluating a failed point. */
    private boolean mightResolveJunctionRoundoff(double tolerance) {
        for(int node=0;node<layout.length;node++) {
            if(!backedFluidJunction(node)||!(incomingMass[node]>0))continue;
            var state=cachedStates[node];
            double weight=1+dt*incomingMass[node]/solver.molarMass(oldAmounts[node]);
            double ulp=Math.ulp(state.enthalpy()/state.mass())/Math.max(1,layout[node].energyScale()/state.mass());
            if(8*weight*Math.max(Math.ulp(1.0),ulp)>=tolerance)return true;
        }
        return false;
    }

    private boolean backedFluidJunction(int node) {
        var reservoir=graph.reservoirs().get(node);
        return layout[node]!=null&&cachedStates[node]!=null&&reservoir.junction()&&reservoir.inventory().reserve()!=null
                &&reservoir.inventory().solids().empty()&&cachedStates[node].solidMoments().mass()==0;
    }

    private double junctionInflow(int node) {
        var inventory=graph.reservoirs().get(node).inventory();var held=oldAmounts[node];
        double heldMass=solver.molarMass(held)+inventory.solids().massKg();
        double share=mixingShare(node,heldMass),total=incomingMass[node]+share*heldMass/dt;
        if(solver.rateOnly) {
            junctionWeight=1;
            for(int c=0;c<fractions.length;c++)fractions[c]=held[c]*model.molecularWeight(c)/heldMass;
            return inventory.internalEnergy()/heldMass;
        }
        junctionWeight=share+incomingMass[node]*dt/heldMass;
        // Owner decision D61: what the junction's own stock melts joins its fluid, with the crystal's offset.
        var melt=heldMelt[node];
        for(int c=0;c<fractions.length;c++)
            fractions[c]=(incoming[node][c]+(melt==null?held[c]:held[c]+melt[c])*share/dt)*model.molecularWeight(c)/total;
        return (incomingEnergy[node]+(melt==null?inventory.internalEnergy():inventory.internalEnergy()+heldMeltEnergy[node])*share/dt)/total;
    }
    /** Whether this edge can carry a change of {@code node}'s decoded state into the other
     * endpoint's rows: the transported amounts and enthalpy are the donor's, a junction's
     * mixture is this pass's frozen donor's, and a pump's shaft power is metered on the first
     * endpoint's density. */
    private boolean carries(int edge,int node,double[] x) {
        var pipe=graph.pipes().get(edge);
        return node==(x[edgeOffset+edge]>=0?pipe.first():pipe.second())
                ||graph.reservoirs().get(other(edge,node)).junction()&&node==(junctionDonorFirst[edge]?pipe.first():pipe.second())
                ||node==pipe.first()&&pipe.control() instanceof FlowControl.Mover;
    }
}
