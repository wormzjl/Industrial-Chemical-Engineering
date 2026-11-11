package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.fluid.SolverOwnership;
import com.wormzjl.icheme.science.fluid.diagnostics.SolverDiagnostics;
import com.wormzjl.icheme.science.fluid.linalg.SparseLuSolver;
import com.wormzjl.icheme.science.fluid.solver.*;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.math.DeterministicMath;
import com.wormzjl.icheme.science.thermo.NumericalBox;
import java.util.*;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import com.wormzjl.icheme.science.fluid.transport.SlurryTransport;

/** One simultaneous backward-Euler step with a fixed phase regime; no nested TP/UV/PH flash. */
public final class PassiveStepSolver {
    /**
     * Standard gravity, m/s2: the static heads (a connection's column, a vessel's level head) and a solid's settling
     * ({@code SlurryTransport}). No stream or ledger carries potential energy (owner decision D37, simplification S1).
     */
    public static final double GRAVITY=9.80665;
    /**
     * How far below its own difference floor a solid moment unknown stops bounding the Newton step length (the floors are
     * {@code max(1e-6, encoded value)}). Swept at 0, 1e-6, 1e-3 and 1 on the ten- and thirty-reservoir water chains: every
     * nonzero value gives the same accepted and rejected counts, 0 fails the thirty-reservoir chain; the smallest kept.
     */
    private static final double SOLID_CLAMP_FRACTION=1e-6;
    /**
     * How far below its own difference floor a fluid amount unknown stops bounding the Newton step length, and what a
     * trial keeps of it instead: {@link Equations#project} holds such a trace at {@value #TRACE_RETENTION} of its value
     * rather than let the whole step shrink to the trace's size. The case: a connection the step reverses predicts that
     * the far vessel loses the near side's composition, drives a {@code 1e-12} entry trace negative by orders of magnitude,
     * and the {@code .99*x/-d} rule of {@link Equations#maximumStep} cut every step of the island to 2e-10 and then 2e-12
     * until the line search stalled (73 of the 76 Newton failures of a three-tank pumped fill). Safe because such a trace
     * moves every row it enters by less than 1e-10, and inventories are rebuilt from the converged flows by
     * {@link ConservativeTransport#reconstruct}.
     */
    private static final double TRACE_CLAMP_FRACTION=1e-6,TRACE_RETENTION=.01;
    /**
     * How far below its stated capacity the saturated filter inlet aims: the committed cake is reconstructed by another
     * summation than the loading row solves, so a law aimed exactly at capacity lands one ULP over it on either side;
     * aiming a hair under makes the landing one-sided, and {@link SolidEventIntegrator} treats a cake this close as full.
     */
    private static final double FILTER_CAPACITY_MARGIN=1e-12;
    /**
     * The share of a row's gap a chord step may leave before it is not worth taking ({@link Equations#linearisationHolds}):
     * a reused Jacobian stating slope s where the row's own is t leaves |1 - t/s| of the gap; past one half either way the
     * chord step does no better than the halving a fresh step makes.
     */
    static final double CHORD_CONTRACTION=.5;
    /** Whether a chord step still closes at least half of a row's gap where the Jacobian states the slope {@code stated} and
     * the row's own is {@code own} ({@link #CHORD_CONTRACTION}). */
    static boolean chordHolds(double stated,double own){return Math.abs(1-own/stated)<=CHORD_CONTRACTION;}
    /**
     * The holdup of a junction, in seconds of its largest connection's velocity-cap flow: its buffer holds
     * {@code m_J = HOLDUP_TAU * max over its connections of rho_seed * minimumArea * velocityLimit(seed)}, minted from its
     * seed when first solved ({@link PassiveNetwork#sizeJunctionHoldups}). Without a buffer a junction mixes by a ratio
     * closure that is singular at zero through-flow; with one its mixing and enthalpy rows are the backward-Euler vessel
     * rows {@code (m_J/dt + Q) w = (m_J/dt) w_old + sum q w_e}, regular at Q = 0. The buffer is the row's state, not
     * inventory (owner decision D46): nothing is booked at a mint, and the ledger reports the buffers' change as its own
     * term ({@link #checkConservation}). A numerical regularisation sized to the flow: its mixing lag at full flow is tau.
     */
    static final double HOLDUP_TAU=.05;
    /** The Newton's relative tolerance on its scaled rows (a taut vessel's weighted by the pressure they imply,
     * {@link Equations#rowWeights}); the solid
     * events read a flow within it of the island's largest as roundoff ({@code SolidEventIntegrator.still}). */
    static final double NEWTON_TOLERANCE=1e-9;
    /**
     * The movers (pipe ids) refused for the slice in progress ({@link FlowControl.Mode#INLET_WRONG_PHASE}, decision D6),
     * set by the interval solver at each slice start ({@link #refuseInlets}) and read by every solve until the next;
     * null for a step solver no interval drives, which decides at each solve ({@link #refusedInlets}).
     */
    private Set<Long> sliceRefusals;
    /** The movers (pipe ids) refused for the slice that starts now; see {@link PassiveIntervalSolver} and {@link InletPhase}. */
    void refuseInlets(Set<Long> pipeIds){sliceRefusals=Set.copyOf(pipeIds);}
    /** The movers refused for the slice in progress ({@link #refuseInlets}), one flag per connection of {@code graph}; null
     * when no interval solver set them. */
    boolean[] sliceRefused(PassiveNetwork graph) {
        if(sliceRefusals==null)return null;
        boolean[] refused=new boolean[graph.pipes().size()];
        for(int i=0;i<refused.length;i++)refused[i]=graph.pipes().get(i).control() instanceof FlowControl.Mover&&sliceRefusals.contains(graph.pipes().get(i).id());
        return refused;
    }
    /**
     * Which connections of {@code graph} this solve holds in {@link FlowControl.Mode#INLET_WRONG_PHASE}: the slice's
     * refusals when an interval solver set them ({@link #refuseInlets}, decided once per slice on the committed state so
     * every step and replay of the slice agrees); otherwise {@link InletPhase#decide} on this solve's input states.
     */
    private boolean[] refusedInlets(PassiveNetwork graph,boolean carryModes,double dt) {
        boolean[] refused=new boolean[graph.pipes().size()];
        if(sliceRefusals!=null) {
            for(int i=0;i<refused.length;i++)refused[i]=graph.pipes().get(i).control() instanceof FlowControl.Mover&&sliceRefusals.contains(graph.pipes().get(i).id());
            return refused;
        }
        if(graph.pipes().stream().noneMatch(pipe->pipe.control() instanceof FlowControl.Mover))return refused;
        return InletPhase.decide(model,graph,carryModes?previousModes:null,rateOnly?0:dt);
    }
    /** Connections the next step solve may not close at its start; set by {@link #reopenNext}, consumed by that solve. */
    private Set<PassiveNetwork.Pipe.Identity> keepOpen=Set.of();
    /** Exempts these connections from the start-of-solve closures ({@link #closeDeadHeads}, {@link #closeIllegalStarts})
     * of the next step solve only; the pass loop may still close them on a converged point. */
    void reopenNext(Set<PassiveNetwork.Pipe.Identity> identities){keepOpen=Set.copyOf(identities);}
    /** The driving pressure (Pa) a closed run's end states must exceed in an allowed direction to be reopened. */
    static double reopenBand(double pa,double pb){return Math.max(1,1e-6*Math.max(Math.abs(pa),Math.abs(pb)));}
    /** The runs of a graph: a run is a maximal chain of connections through degree-two junctions no actuator touches.
     * {@link #reopenable} and {@link #closeDeadHeads} walk them, each over the connections
     * its own rule admits. */
    private record Runs(boolean[] interior,int[][] nodeEdges) {
        static Runs of(PassiveNetwork graph) {
            int edges=graph.pipes().size(),nodes=graph.reservoirs().size();
            int[] degree=new int[nodes];boolean[] actuated=new boolean[nodes];
            for(var pipe:graph.pipes()) {
                degree[pipe.first()]++;degree[pipe.second()]++;
                if(!(pipe.control() instanceof FlowControl.Passive)){actuated[pipe.first()]=true;actuated[pipe.second()]=true;}
            }
            boolean[] interior=new boolean[nodes];
            for(int i=0;i<nodes;i++)interior[i]=graph.reservoirs().get(i).junction()&&degree[i]==2&&!actuated[i];
            int[][] nodeEdges=new int[nodes][];
            for(int i=0;i<nodes;i++)nodeEdges[i]=new int[degree[i]];
            Arrays.fill(degree,0);
            for(int edge=0;edge<edges;edge++){var pipe=graph.pipes().get(edge);nodeEdges[pipe.first()][degree[pipe.first()]++]=edge;nodeEdges[pipe.second()][degree[pipe.second()]++]=edge;}
            return new Runs(interior,nodeEdges);
        }
        /** Walks the run through {@code edge} both ways over the connections {@code walkable} admits, marking them
         * taken: {@code chain} its links and {@code order} its nodes, first to last. */
        void walk(PassiveNetwork graph,int edge,boolean[] taken,IntPredicate walkable,List<Integer> chain,List<Integer> order) {
            var pipe=graph.pipes().get(edge);
            order.add(pipe.first());chain.add(edge);order.add(pipe.second());taken[edge]=true;
            for(int end=0;end<2;end++) {
                int at=end==0?pipe.first():pipe.second(),from=edge;
                while(interior[at]) {
                    int next=nodeEdges[at][0]==from?nodeEdges[at][1]:nodeEdges[at][0];
                    if(taken[next]||!walkable.test(next))break;
                    taken[next]=true;var step=graph.pipes().get(next);
                    int beyond=step.first()==at?step.second():step.first();
                    if(end==0){chain.addFirst(next);order.addFirst(beyond);}else{chain.add(next);order.add(beyond);}
                    at=beyond;from=next;
                }
            }
        }
    }
    /**
     * The passive connections a converged step holds closed ({@code boundaryClosed}) whose run (the chain of closed passive
     * connections through degree-two junctions no actuator touches, as {@link #closeDeadHeads} decides it) is driven by the
     * step's own end states in a direction every link allows, by more than {@link #reopenBand}; a forward run stands in
     * the first end's fluid, a reverse run in the second's, and a junction end counts only while one of its connections
     * is open. Closures are decided on the solve's start point and never reopened within a solve, so a step that should
     * open part-way (an injection into tanks at rest) would be solved shut throughout: the interval solver refuses such a
     * step and solves it again with the run exempt.
     */
    Set<PassiveNetwork.Pipe.Identity> reopenable(PassiveNetwork graph,Result result) {
        int edges=graph.pipes().size(),nodes=graph.reservoirs().size();
        var states=result.states();var modes=result.modes();
        if(modes.size()!=edges||states.size()!=nodes)return Set.of();
        boolean[] closed=new boolean[edges];boolean[] open=new boolean[nodes];boolean any=false;
        for(int e=0;e<edges;e++) {
            var pipe=graph.pipes().get(e);
            closed[e]=pipe.control() instanceof FlowControl.Passive&&pipe.effectiveBlockedDirections()!=3&&modes.get(e)==FlowControl.Mode.CLOSED;any|=closed[e];
            if(modes.get(e)!=FlowControl.Mode.CLOSED&&modes.get(e)!=FlowControl.Mode.INLET_WRONG_PHASE){open[pipe.first()]=true;open[pipe.second()]=true;}
        }
        if(!any)return Set.of();
        var runs=Runs.of(graph);
        boolean[] taken=new boolean[edges];var reopen=new LinkedHashSet<PassiveNetwork.Pipe.Identity>();
        // A phase port never refuses a direction (decision D11: it draws its next phase instead), so only the static
        // boundary rule decides which way a run may be reopened.
        for(int edge=0;edge<edges;edge++) {
            if(taken[edge]||!closed[edge])continue;
            var chain=new ArrayList<Integer>();var order=new ArrayList<Integer>();
            runs.walk(graph,edge,taken,next->closed[next],chain,order);
            int start=order.getFirst(),finish=order.getLast();
            if(start==finish)continue;
            if(graph.reservoirs().get(start).junction()&&!open[start]||graph.reservoirs().get(finish).junction()&&!open[finish])continue;
            boolean forwardAllowed=true,reverseAllowed=true;
            for(int position=0;position<chain.size();position++) {
                var link=graph.pipes().get(chain.get(position));boolean aligned=link.first()==order.get(position);
                forwardAllowed&=boundaryAllowed(graph,link,aligned?1:-1);
                reverseAllowed&=boundaryAllowed(graph,link,aligned?-1:1);
            }
            var a=states.get(start);var b=states.get(finish);
            // Each end of the run stands in its own end port's column over the run ({@link #columnDensity}), at its own end's
            // driving pressure.
            var startPort=graph.pipes().get(chain.getFirst()).portAt(start);var finishPort=graph.pipes().get(chain.getLast()).portAt(finish);
            double pa=endPressure(graph.reservoirs().get(start),startPort,a),pb=endPressure(graph.reservoirs().get(finish),finishPort,b);
            double dz=graph.reservoirs().get(finish).elevation()-graph.reservoirs().get(start).elevation(),difference=pa-pb;
            double band=reopenBand(pa,pb);
            double forwardDrive=difference-runColumnDensity(graph,chain,a,startPort)*GRAVITY*dz,reverseDrive=difference-runColumnDensity(graph,chain,b,finishPort)*GRAVITY*dz;
            if(forwardAllowed&&forwardDrive>band||reverseAllowed&&reverseDrive<-band)for(int link:chain)reopen.add(graph.pipes().get(link).identity());
        }
        return reopen;
    }
    private final FluidThermodynamics model;
    private final SolverOwnership ownership;
    /** Each heat-exchanging connection's curve per end ({@link #sideCurve}), kept while its law's span and films, the end's
     * port and the donor's state are the ones it was built for: the start balance, the seeds and every pass and retry of a
     * step read one curve, and a slice input (a fired coil's firing share, a wall side's bypass) does not resample it
     * (batch 2026-10-27-heat-exchanger-design). */
    private record SideCurve(double coldest,double hottest,HeatCurve.Films films,PassiveNetwork.PhasePort port,double volume,FluidThermodynamics.State start,HeatCurve curve) {}
    private final Map<Long,SideCurve[]> sideCurves=new HashMap<>();
    private final Map<WorkspaceKey,SparseNewton.Workspace> workspaces=new LinkedHashMap<>();
    private final Map<WorkspaceKey,SparseNewton.Workspace> structures=new LinkedHashMap<>();
    private List<PassiveNetwork.Pipe.Identity> previousPipes=List.of();
    private long[] previousNodeIds=new long[0];
    private double[] previousFlows=new double[0],previousHeads=new double[0];
    /** The next pass's start flows of the connections restarted in their allowed direction ({@link #allowedRestart}), NaN for
     * the others; null for none. The pass's equations read it once, as they are built. */
    private double[] allowedRestart;
    /** The law flows ({@link #saturated}: the edge unknowns) of the point {@link #previousFlows} are the transported flows of;
     * a step's Newton starts from them. At a job start the committed endpoint's transported flows stand in for them. */
    private double[] previousLawFlows=new double[0];
    /** The input states {@link #previousFlows} were recorded at, and the input states of the solve in progress: a
     * rate-only solve warm-starts only from flows recorded at value-identical input states; see {@link #startPoint}. */
    private List<FluidThermodynamics.State> previousInputStates=List.of(),inputStates=List.of();
    /** The last converged solve's states, and whether it was a rate solve: a rate solve's point starts a step only where
     * it was written back into the step's graph ({@code PassiveIntervalSolver.coldRateSeed}); see {@link #carriedPoint}. */
    private List<FluidThermodynamics.State> previousOutputStates=List.of();private boolean previousRateOnly;
    /** Per connection, the pressure a pump's suction may not fall below in this solve ({@link #suctionFloors(PassiveNetwork,List,Runnable)}). */
    private double[] suctionFloors=new double[0];
    /** Per connection, the clean zone's edge E of what its pump's suction line delivers in this solve
     * (W3B_NETWORK_PLAN.md section 17, {@link #zoneEdge}): NaN until read, positive infinity where none. */
    private double[] zoneEdges=new double[0];
    /** Per connection, what its liquid pump's suction end draws now ({@link InletPhase#drawn} with no slice: the leading
     * phase at a phase port, the bulk at a BULK end), the stream its clean zone's edge is read on; read with the suction
     * readings ({@link #readSuctions}); null where it reads none. The slice's later gas is the head share's only. */
    private InletPhase.Drawn[] drawnStreams=new InletPhase.Drawn[0];
    /** The solve's checkpoint, for the zone edges worked out when first read. */
    private Runnable suctionCheckpoint=()->{};
    /** Per connection, whether its pump is vapour-locked in this solve ({@link #vapourLocks}): one fact per solve, read on the
     * step's start states with the floor. */
    private boolean[] vapourLocked=new boolean[0];
    /**
     * The device active set the last pass of the last solve on this graph settled on. A mover on its suction limit, or closed
     * where its rise at no flow loses to the demand, is a property of the island's state, not of one step, so the next solve
     * opens there. Valid only while {@link #previousPipes} and {@link #previousNodeIds} still describe this graph.
     */
    private List<FlowControl.Mode> previousModes=List.of();
    private final Map<PassiveNetwork.Pipe.Identity,Double> acceptedOpenings=new HashMap<>();
    private double[] heldOpenings=new double[0];
    /** The flow valves of this solve that stand open behind a lower target ({@link ControlConstraints#standing}). */
    private BitSet standing=new BitSet();
    /** Owner decision D11 (A2): this solve's input graph and slice, and the density of each set-flow pump's supply on it
     * ({@link #riseDensity}; -1 where none is measured), NaN until read. */
    private PassiveNetwork supplyGraph;private double supplyDuration;private double[] supplyDensities=new double[0];
    /** Request 11 (owner decisions D19 and D20): per connection, what this solve reads of its mover's suction on the step's
     * start states ({@link #readSuctions}): the control the reading belongs to, the mover as its law runs this solve (a
     * centrifugal pump's head scaled by its gas share), its NPSH design and the density of the liquid it draws, and a
     * centrifugal compressor's wet efficiency as a share of its dry one (one elsewhere). */
    private FlowControl[] readControls=new FlowControl[0];private FlowControl.Mover[] suctionMovers=new FlowControl.Mover[0];
    private com.wormzjl.icheme.science.machine.Npsh[] npshDesigns=new com.wormzjl.icheme.science.machine.Npsh[0];
    private double[] liquidDensities=new double[0],wetShares=new double[0],vapourShares=new double[0];
    private static double boreArea(PassiveNetwork.Pipe pipe){return pipe.controlArea();}
    private void rememberOpenings(PassiveNetwork graph,double[] flows,double[] heads,List<FlowControl.Mode> modes){
        for(int e=0;e<graph.pipes().size();e++){var p=graph.pipes().get(e);if(!(p.control() instanceof FlowControl.Valve))continue;
            double rho=endDensity(graph.reservoirs().get(p.first()).state(),p.firstPort(),p.volume());
            acceptedOpenings.put(p.identity(),modes.get(e)==FlowControl.Mode.CLOSED?0:FlowControl.opening(flows[e],Math.max(0,heads[e]),rho,boreArea(p)));
        }
    }

    /** The structure of the last solve this solver accepted; {@link #closeIllegalStarts} acts only on it. */
    private List<PassiveNetwork.Pipe.Identity> acceptedPipes=List.of();
    private long[] acceptedNodeIds=new long[0];
    /** The structure of the last step the interval solver's controller accepted (a converged solve it refused does not
     * count): the cold start ({@link #backwardEulerCold}) lasts until then. */
    private List<PassiveNetwork.Pipe.Identity> beAcceptedPipes=List.of();
    private long[] beAcceptedNodeIds=new long[0];
    /** The interval solver accepted a step of this structure. */
    void markBackwardEulerAccepted(PassiveNetwork graph,Result result){beAcceptedPipes=identities(graph);beAcceptedNodeIds=nodeIds(graph);rememberOpenings(graph,result.massFlows(),result.devicePressureChanges(),result.modes());}
    /** Whether no step of this structure has been accepted yet: the cold start after compile, a topology change, or a job
     * start with no committed interval on this structure. */
    boolean backwardEulerCold(PassiveNetwork graph){return !(sameLines(beAcceptedPipes,identities(graph))&&Arrays.equals(beAcceptedNodeIds,nodeIds(graph)));}
    /**
     * Puts this solver, at the start of an island job, in the state a fresh solver would be in given only the island's
     * committed interval {@code committed} (null without one on this structure), so a job's solves depend on the committed
     * interval and the slice, not on earlier jobs ({@link PassiveIntervalSolver#replayStart}): every retained numeric
     * Jacobian and LU is dropped (the sparsity pattern, colouring and ordering stay, being functions of the structure);
     * the first step starts from the committed endpoint's flows, heads and modes ({@link #startPoint},
     * {@link #previousModes}), or from the balance ({@link #balanceStart}) without a committed interval; "this structure has been
     * accepted" is true exactly when the island has a committed interval on it; the reopen exemption and the retained
     * last solve are dropped.
     */
    void replayStart(PassiveNetwork graph,PassiveIntervalSolver.Result committed) {
        ownership.check("Each executing island job needs its own step workspace");
        // Exactly a fresh solver's numerics: no dt-keyed workspace, and every structure's latest workspace replaced by a
        // fork that keeps its pattern, colouring and ordering (functions of the structure) and no Jacobian or LU. An
        // invalidated workspace left under its dt key would not inherit the chord this job builds on its first step, so a
        // carried job refreshed its Jacobian where a fresh one reused it (RetainedSolverTest).
        workspaces.clear();
        for(var entry:structures.entrySet())entry.setValue(entry.getValue().forkStructure());
        previousFlows=new double[0];previousLawFlows=new double[0];previousHeads=new double[0];previousInputStates=List.of();inputStates=List.of();previousOutputStates=List.of();previousRateOnly=false;
        previousPipes=List.of();previousNodeIds=new long[0];previousModes=List.of();
        keepOpen=Set.of();lastSolve=null;acceptedOpenings.clear();
        if(committed!=null) {
            var ids=identities(graph);long[] nodes=nodeIds(graph);
            beAcceptedPipes=ids;beAcceptedNodeIds=nodes;acceptedPipes=ids;acceptedNodeIds=nodes;
            previousPipes=ids;previousNodeIds=nodes;previousModes=committed.endpointModes();
            previousFlows=committed.endpointFlows();previousLawFlows=committed.endpointFlows();previousHeads=committed.endpointHeads();
            rememberOpenings(graph,previousFlows,previousHeads,committed.endpointModes());
            previousInputStates=committed.graph().reservoirs().stream().map(PassiveNetwork.Reservoir::state).toList();
        } else {beAcceptedPipes=List.of();beAcceptedNodeIds=new long[0];acceptedPipes=List.of();acceptedNodeIds=new long[0];}
    }
    /** The same pipe identities and node ids. */
    static boolean sameStructure(PassiveNetwork a,PassiveNetwork b){return identities(a).equals(identities(b))&&Arrays.equals(nodeIds(a),nodeIds(b));}
    /** {@link #sameStructure} whatever a flow valve's set point: a job on a new target starts from the committed interval too. */
    static boolean sameLines(PassiveNetwork a,PassiveNetwork b){return sameLines(identities(a),identities(b))&&Arrays.equals(nodeIds(a),nodeIds(b));}
    /** The same connections, whatever a flow valve's set point (a column plans one each slice). */
    private static boolean sameLines(List<PassiveNetwork.Pipe.Identity> a,List<PassiveNetwork.Pipe.Identity> b) {
        if(a.size()!=b.size())return false;
        for(int i=0;i<a.size();i++)if(!a.get(i).sameLine(b.get(i)))return false;
        return true;
    }
    /** The last successful solve, for the block Jacobian check; see {@link #acceptedEquations}. */
    record LastSolve(Equations equations,double[] variables) {}
    private LastSolve lastSolve;
    private boolean rateOnly;
    /** Owned by this solver, which the ownership latch confines to one worker at a time. */
    private final com.wormzjl.icheme.science.fluid.transport.MixtureViscosity.Workspace viscosities=
            new com.wormzjl.icheme.science.fluid.transport.MixtureViscosity.Workspace();
    /** The island's retained transport linear algebra; an island holds exactly one. */
    private final ConservativeTransport.Workspace transport;
    public PassiveStepSolver(FluidThermodynamics model){this(model,SolverOwnership.confinedToCurrentThread());}
    public PassiveStepSolver(FluidThermodynamics model,SolverOwnership ownership) {
        this.model=Objects.requireNonNull(model);this.ownership=Objects.requireNonNull(ownership);
        transport=new ConservativeTransport.Workspace(ownership);
    }
    public record Result(List<FluidThermodynamics.State> states,double[] massFlows,double deltaTime,SparseNewton.Result numerical,
                         List<FlowControl.Mode> modes,double[] devicePressureChanges,double pumpWorkJoule,double[] externalMoles,double externalEnergyJoule,
                         List<PassiveNetwork.Inventory> inventories,List<ConservativeTransport.BoundaryTransfer> boundaries,List<PipeTransfer> pipeTransfers,Map<Long,InlineFilter> filters,List<PipeObservation> observations,Map<Long,TowerExchange.Rates> towers) {
        public Result(List<FluidThermodynamics.State> states,double[] massFlows,double deltaTime,SparseNewton.Result numerical,List<FlowControl.Mode> modes,double[] devicePressureChanges,double pumpWorkJoule,double[] externalMoles,double externalEnergyJoule,List<PassiveNetwork.Inventory> inventories,List<ConservativeTransport.BoundaryTransfer> boundaries,List<PipeTransfer> pipeTransfers,Map<Long,InlineFilter> filters){this(states,massFlows,deltaTime,numerical,modes,devicePressureChanges,pumpWorkJoule,externalMoles,externalEnergyJoule,inventories,boundaries,pipeTransfers,filters,List.of(),Map.of());}
        public Result(List<FluidThermodynamics.State> states,double[] massFlows,double deltaTime,SparseNewton.Result numerical,List<FlowControl.Mode> modes,double[] devicePressureChanges,double pumpWorkJoule,double[] externalMoles,double externalEnergyJoule,List<PassiveNetwork.Inventory> inventories,List<ConservativeTransport.BoundaryTransfer> boundaries,List<PipeTransfer> pipeTransfers){this(states,massFlows,deltaTime,numerical,modes,devicePressureChanges,pumpWorkJoule,externalMoles,externalEnergyJoule,inventories,boundaries,pipeTransfers,Map.of());}
        public Result {towers=Map.copyOf(towers);observations=List.copyOf(observations);filters=Map.copyOf(filters);states=List.copyOf(states);massFlows=massFlows.clone();modes=List.copyOf(modes);devicePressureChanges=devicePressureChanges.clone();externalMoles=externalMoles.clone();inventories=List.copyOf(inventories);boundaries=List.copyOf(boundaries);pipeTransfers=List.copyOf(pipeTransfers);}
        @Override public double[] massFlows(){return massFlows.clone();}
        @Override public double[] devicePressureChanges(){return devicePressureChanges.clone();}
        @Override public double[] externalMoles(){return externalMoles.clone();}
    }
    public Result solve(PassiveNetwork graph,double dt,Runnable checkpoint){return solve(graph,dt,checkpoint,false,null);}
    /** The interval controller supplies only modes of its matching accepted endpoint, never a rejected attempt. */
    Result solve(PassiveNetwork graph,double dt,Runnable checkpoint,List<FlowControl.Mode> acceptedEndpointModes){return solve(graph,dt,checkpoint,false,acceptedEndpointModes);}
    public Result solveRate(PassiveNetwork graph,Runnable checkpoint){return solve(graph,1,checkpoint,true,null);}
    /**
     * One rate solve of {@code graph} with its vessels held as ports ({@link PassiveNetwork.NodeKind#PORT}, dt = 1), or
     * null when it has no connection or does not converge: the interval solver's guard and the solid closure check
     * ({@link SolidEventIntegrator}) read the island through it. It only pre-empts a closure the steps would find, so a
     * failed rate solve declares nothing and the steps decide (hold class E1).
     */
    Result portRate(PassiveNetwork graph,Runnable checkpoint) {
        if(graph.pipes().isEmpty())return null;
        var ports=new ArrayList<PassiveNetwork.Reservoir>();
        for(var node:graph.reservoirs())ports.add(new PassiveNetwork.Reservoir(node.id(),node.elevation(),node.state(),
                node.kind()==PassiveNetwork.NodeKind.RESERVOIR?PassiveNetwork.NodeKind.PORT:node.kind(),node.inventory()));
        try{return solveRate(new PassiveNetwork(ports,graph.pipes()),checkpoint);}
        catch(SparseNewton.Nonconvergence|IllegalArgumentException failed){return null;}
    }
    private Result solve(PassiveNetwork inputGraph,double dt,Runnable checkpoint,boolean rateOnly,List<FlowControl.Mode> acceptedEndpointModes) {
        // A junction minted by its constructor and not yet sized to its connections is sized here (no-op otherwise).
        inputGraph=PassiveNetwork.sizeJunctionHoldups(inputGraph,model);
        this.rateOnly=rateOnly;
        inputStates=inputGraph.reservoirs().stream().map(PassiveNetwork.Reservoir::state).toList();
        // The suction readings before the lock: the zone edge the lock reads is read on the stream they find (InletPhase.drawn).
        suctionFloors=suctionFloors(inputGraph,inputStates,checkpoint);readSuctions(inputGraph,rateOnly?0:dt);vapourLocked=vapourLocks(inputGraph,inputStates);
        SolverDiagnostics.count(SolverDiagnostics.implicitSolves);
        ownership.check("Each executing island job needs its own step workspace");
        lastSolve=null;lawFlowsFromBalance=false;
        if(!Double.isFinite(dt)||dt<=0)throw new IllegalArgumentException("Positive finite substep required");
        var graph=inputGraph;
        heldOpenings=new double[graph.pipes().size()];Arrays.fill(heldOpenings,Double.NaN);
        var conflicts=ControlConstraints.dependent(graph);
        for(int e=conflicts.nextSetBit(0);e>=0;e=conflicts.nextSetBit(e+1))heldOpenings[e]=acceptedOpenings.getOrDefault(graph.pipes().get(e).identity(),1.0);
        standing=ControlConstraints.standing(graph);
        // An island of unconnected nodes changes nothing in a step, unless an open vessel in it gives the atmosphere its gas
        // or its spill (OpenVessel).
        if(graph.pipes().isEmpty()&&graph.scheduledTransfers().isEmpty()&&(rateOnly||graph.reservoirs().stream().noneMatch(node->OpenVessel.releasing(node)||poolHeat(node)!=0||node.inventory().tower()!=null&&!node.empty())))return new Result(graph.reservoirs().stream().map(PassiveNetwork.Reservoir::state).toList(),new double[0],dt,
                new SparseNewton.Result(new double[0],0,0,0,0,0),List.of(),new double[0],0,new double[model.hydrocarbon.componentCount()+1],0,
                graph.reservoirs().stream().map(PassiveNetwork.Reservoir::inventory).toList(),List.of(),List.of());
        var modes=new ArrayList<FlowControl.Mode>();
        boolean carryModes=previousModes.size()==graph.pipes().size()&&sameLines(previousPipes,identities(graph))&&Arrays.equals(previousNodeIds,nodeIds(graph));
        // A mover's start mode reads its own lines only (owner decision D40 A): its setting, and the sealed sides of it
        // (relieveSealedPockets). A connection blocked elsewhere in the island changes neither.
        modes.addAll(initialModes(graph));
        // At exactly its setpoint a regulating valve may carry positive flow and throttling head. Re-closing it at
        // every substep creates a different branch that must converge before it can reopen, coupled to phase draws.
        // This seed is passed per attempt by the interval controller; mutable previousModes can include failed trials.
        if(acceptedEndpointModes!=null) {
            if(acceptedEndpointModes.size()!=graph.pipes().size())throw new IllegalArgumentException("Accepted endpoint mode count differs from graph");
            double[] bound=null;
            for(int edge=0;edge<modes.size();edge++)if(graph.pipes().get(edge).control() instanceof FlowControl.Valve valve&&!valve.bare()) {
                var accepted=acceptedEndpointModes.get(edge);
                if(accepted!=FlowControl.Mode.CLOSED&&accepted!=FlowControl.Mode.VALVE_OPEN&&accepted!=FlowControl.Mode.VALVE_REGULATING&&accepted!=FlowControl.Mode.VALVE_CONFLICT)
                    throw new IllegalArgumentException("Invalid accepted valve mode");
                if(standing.get(edge)||accepted==FlowControl.Mode.VALVE_CONFLICT&&Double.isNaN(heldOpenings[edge])){modes.set(edge,accepted==FlowControl.Mode.CLOSED?accepted:FlowControl.Mode.VALVE_OPEN);continue;}
                if(!Double.isNaN(heldOpenings[edge]))continue;
                var start=accepted;
                // A regulating flow valve whose target went to zero is closed: its regulation row would state a zero flow through its open line.
                if(accepted==FlowControl.Mode.VALVE_REGULATING&&!valve.pressureControl()&&valve.targetMassFlow(1)==0)start=FlowControl.Mode.CLOSED;
                // Owner decision D11 (A1): one whose target lies above what a set-flow mover in its series moves starts open, the
                // mover setting the flow, as an open valve's first pass would find; the pass loop regulates again where the open line
                // passes more. Regulating there asked the mover for its target: 50 kg/s of a 10 kg/s pump stood a thousand relief
                // rises below its suction, where the thermo failed.
                else if(accepted==FlowControl.Mode.VALVE_REGULATING&&!valve.pressureControl()) {
                    if(bound==null)bound=moverBounds(graph);
                    var pipe=graph.pipes().get(edge);
                    if(valve.targetMassFlow(endDensity(graph.reservoirs().get(pipe.first()).state(),pipe.firstPort(),pipe.volume()))>bound[edge])start=FlowControl.Mode.VALVE_OPEN;
                }
                modes.set(edge,start);
            }
        }
        // A mover the previous point left on its suction limit opens there where this step's suction has a vapour pressure to
        // stand at ({@link #suctionFloors}, the water its first node holds); one it left closed stays closed while its rise at no
        // flow ({@link #riseDensity}) still loses to the demand; see {@link #previousModes}.
        // Its law states the rest, and the velocity cap is part of every line's law ({@link #saturated}).
        // Owner decision D16 A: a vapour-locked pump ({@link #vapourLocks}) already starts closed ({@link #initialModes}) and
        // the carry leaves it so; the carry reads no suction head of its own.
        supplyGraph=inputGraph;supplyDuration=rateOnly?0:dt;supplyDensities=new double[graph.pipes().size()];Arrays.fill(supplyDensities,Double.NaN);
        if(carryModes)for(int i=0;i<modes.size();i++) {
            var pipe=graph.pipes().get(i);
            if(!(pipe.control() instanceof FlowControl.Mover pump)||pump.targetVolumeFlow()==0||vapourLocked(i))continue;
            var carried=previousModes.get(i);
            double band=shutoffBand(Math.max(graph.reservoirs().get(pipe.first()).state().pressure(),graph.reservoirs().get(pipe.second()).state().pressure()),NEWTON_TOLERANCE);
            if(carried==FlowControl.Mode.PUMP_SUCTION_LIMIT&&suctionLimitPressure(graph,i,0)<Double.POSITIVE_INFINITY)
                modes.set(i,carried);
            else if(carried==FlowControl.Mode.CLOSED&&!(shutoffMargin(graph,i)>band))
                modes.set(i,carried);
        }
        // A connection closed to all transport before the solve starts is in the active-set state the pass-by-pass closure
        // produces, actuator included: an actuator left on its setpoint across a closed edge is a second equation for a
        // flow the closure has decided, and on a junction whose other edges are closed the factorization is singular.
        boolean[] boundaryClosed=new boolean[graph.pipes().size()];
        for(int i=0;i<boundaryClosed.length;i++)if(graph.pipes().get(i).effectiveBlockedDirections()==3) {
            boundaryClosed[i]=true;
            if(!(graph.pipes().get(i).control() instanceof FlowControl.Passive))modes.set(i,FlowControl.Mode.CLOSED);
        }
        // Decision D6: a pump or compressor refused for this slice because its supply is the wrong phase is closed for the
        // whole solve like a connection blocked both ways (its flow is exactly zero, no pass reopens it, nothing reaches
        // its discharge through it), and holds the mode INLET_WRONG_PHASE, which the carry never restates; see
        // {@link #refusedInlets}.
        boolean[] refused=refusedInlets(inputGraph,carryModes,dt);
        for(int i=0;i<refused.length;i++)if(refused[i]){boundaryClosed[i]=true;modes.set(i,FlowControl.Mode.INLET_WRONG_PHASE);}
        // A run the interval solver reopened is exempt from the start-of-solve closures of this one solve; the pass loop
        // may still close it on a converged point. See {@link #reopenable}.
        boolean[] keep=null;
        if(!keepOpen.isEmpty()&&!rateOnly){var ids=identities(graph);keep=new boolean[ids.size()];for(int i=0;i<keep.length;i++)keep[i]=keepOpen.contains(ids.get(i));keepOpen=Set.of();}
        closeDeadHeads(graph,boundaryClosed,keep);
        balanceLawFlows=null;
        if(needsBalance(graph)) {
            // Owner decision of 2026-10-19 (junction seeding): a solve without a carried point ({@link #carriedPoint}) starts from
            // the island's quasi-steady balance ({@link #balanceStart}), the pump modes it decides included: the cold rate seed's
            // rate solve, whose converged point then starts the step (the plan's fallback), and a step whose rate seed failed.
            // A point carried from a solve on other states is dropped; the pass loop carries within the solve.
            relieveSealedPockets(graph,modes,boundaryClosed);
            Start start;
            if(balanceFor==inputGraph&&balanceCache!=null&&Arrays.equals(balanceClosedFor,boundaryClosed))start=balanceCache;
            else{start=balanceStart(graph,modes,boundaryClosed,keep,checkpoint);balanceFor=inputGraph;balanceCache=start;balanceClosedFor=boundaryClosed.clone();}
            graph=start.graph();balanceLawFlows=start.lawFlows();
            for(int i=0;i<modes.size();i++)modes.set(i,start.modes().get(i));
            previousPipes=List.of();previousNodeIds=new long[0];previousFlows=new double[0];previousLawFlows=new double[0];previousHeads=new double[0];
            for(int i=0;i<boundaryClosed.length;i++)if(start.closed()[i])boundaryClosed[i]=true;
        }
        closeIllegalStarts(graph,modes,boundaryClosed,keep);
        // Owner decision D58: a running mover with a sealed side carries nothing and starts closed.
        relieveSealedPockets(graph,modes,boundaryClosed);
        // The species this island can carry, and the trial states built from them, are read off the connections this pass
        // leaves open, so they are stated after the closure above: a connection the closure has taken delivers nothing,
        // and an entry trace seeded behind it for a component only it could bring puts an unknown on a nonnegativity
        // boundary whose only root is zero, which refused the whole Newton step ({@link #closeDeadHeads}).
        var reachable=reachableComponents(graph,boundaryClosed);
        flashRestated=null;
        var seeds=initialPhaseSeeds(graph,dt,checkpoint,reachable,modes,boundaryClosed);
        var seen=new HashSet<PassKey>();
        // Constant for this solve: every pass keys on the same graph identity and component support.
        var pipeIdentities=identities(graph);
        long[] nodeIds=nodeIds(graph);byte[] kinds=new byte[nodeIds.length];
        for(int i=0;i<kinds.length;i++)kinds[i]=(byte)graph.reservoirs().get(i).kind().ordinal();
        boolean[] componentMask=componentMask(graph);
        // Components this solve has reactivated, per node. Reactivation is monotone within one solve
        // - a later pass's seed may not undo it - which is what keeps the support half of the cycle
        // key monotone and the pass sequence finite; demotion happens at the next solve's seed.
        boolean[][] promoted=new boolean[seeds.size()][];
        // Decision D34 with A85: the phases (phaseCode bits) the trace rule removed at each node in this solve, those a
        // node's own split refused at a converged point (the D26 amendment) and those the pass after a proposal refuted
        // (owner decision D39 B, {@link Proposal}). Monotone within the solve: the phase correction does not give such a
        // phase back, else a condensate just below the trace share is folded, the gas left behind is cooler, its flash
        // condenses more than the share again, and the pass sequence repeats (1.0e-9 folded, 2.4e-9 given back).
        int[] foldedPhases=new int[seeds.size()];
        // Owner decision D39 B: the phase the last converged point's check added to one node, until a pass converges again.
        Proposal proposal=null;
        int maximumPasses=Math.min(512,16+2*graph.reservoirs().size()+2*graph.pipes().size());
        // Decision D54: the vessels this solve has found running dry within the step (a failed Newton drove what they hold to
        // nothing); each pass solves them as junctions holding their stock (emptyingGraph), null while there is none.
        // A vessel a converged pass has confirmed as emptying is not released again within the solve ({@link #keptEmptying}).
        final var original=graph;boolean[] emptying=null,emptied=new boolean[graph.reservoirs().size()],flashedVessels=new boolean[graph.reservoirs().size()];
        // Owner decision of 2026-11-10 (option A, batch 2026-11-10-custom-shader-system): the connections at a void or generator
        // end this solve has restarted in their allowed direction ({@link #allowedRestart}), and how many restarts it took.
        boolean[] restarted=new boolean[graph.pipes().size()];int restarts=0;allowedRestart=null;
        for(int pass=0;pass<maximumPasses;pass++) {
            checkpoint.run();SolverDiagnostics.count(SolverDiagnostics.activeSetPasses);
            graph=emptying==null?original:emptyingGraph(original,emptying);emptyings=emptying==null?null:emptyings(original,emptying,dt);
            for(int i=0;i<kinds.length;i++)kinds[i]=(byte)graph.reservoirs().get(i).kind().ordinal();
            // Decision D34: the phases a pass is laid out for are the flash's, decided here once per pass and never inside a
            // residual or a Jacobian probe; a seed taken from a converged point of an earlier pass may still hold a phase its
            // Newton drove below the trace share.
            seeds=foldedSeeds(graph,seeds);
            // A junction owns stock like a vessel: it keeps the undirected composition basis
            // {@link #reachableComponents} gives every node and its own state as its seed.
            // A dry vessel (decision D54: empty, nothing laid out for it yet) has its own code: it carries no unknowns.
            int[] phases=new int[seeds.size()];for(int i=0;i<phases.length;i++)phases[i]=dry(graph.reservoirs().get(i),seeds.get(i))?DRY:phaseCode(seeds.get(i));
            byte[] modeCodes=new byte[modes.size()];for(int i=0;i<modeCodes.length;i++)modeCodes[i]=(byte)modes.get(i).ordinal();
            var supports=supports(graph,seeds,reachable,promoted);
            var equations=new Equations(graph,dt,modes,boundaryClosed,seeds,reachable,supports,pipeIdentities);allowedRestart=null;
            flashRestated=null;
            // The active-set state is what varies between passes, so the structure key is also the cycle key: repeating one
            // means the pass sequence cannot make progress. The frozen junction donors, the phase-port segments and the static
            // columns belong to it for the same reason the modes do: they decide which equations this pass states (see
            // {@link PassKey}).
            var structure=new WorkspaceKey(0,nodeIds,kinds,pipeIdentities,phases,componentMask,supportCodes(supports),modeCodes,boundaryClosed.clone(),equations.junctionDonorFirst);
            if(!seen.add(new PassKey(structure,equations.segmentCodes(),equations.columnCodes(),foldedPhases.clone(),proposal==null?-1:proposal.node(),proposal==null?0:proposal.phases(),restarts)))throw new SparseNewton.Nonconvergence("Phase/device active-set cycle");
            var key=new WorkspaceKey(Double.doubleToLongBits(dt),nodeIds,kinds,pipeIdentities,phases,componentMask,structure.supports,modeCodes,structure.boundaryClosed,structure.junctionDonors);
            var workspace=workspaces.get(key);
            SolverDiagnostics.count(workspace==null?SolverDiagnostics.workspaceBuilds:SolverDiagnostics.workspaceReuses);
            if(workspace==null){if(workspaces.size()>=4)workspaces.remove(workspaces.keySet().iterator().next());var previous=structures.get(structure);workspace=previous==null?new SparseNewton.Workspace(ownership):previous.forkPreconditioner();workspaces.put(key,workspace);}
            if(structures.size()>=4&&!structures.containsKey(structure))structures.remove(structures.keySet().iterator().next());structures.put(structure,workspace);
            SparseNewton.Result numerical;
            // One tolerance for every island: a taut vessel's rows carry the precision its pressure needs as weights in the
            // norm the Newton converges on (owner decision D12, {@link Equations#rowWeights}), in place of the tenth of
            // the tolerance a small headspace used to impose on the whole island.
            double tolerance=NEWTON_TOLERANCE;
            try{numerical=SparseNewton.solve(equations,equations.initial(),new SparseNewton.Settings(SparseNewton.ITERATIONS,tolerance,1e-6,24),checkpoint,workspace);}
            catch(SparseNewton.Nonconvergence failure) {
                if(failure.lastVariables()==null)throw failure;
                double[] trialFlows=Arrays.copyOfRange(failure.lastVariables(),equations.edgeOffset,equations.edgeOffset+graph.pipes().size());
                // Owner decisions D59 (4) and D14: a pump whose law pulled its suction below the vapour pressure of the liquid
                // it draws cavitates; the pass is solved again with it on its suction limit.
                if(cavitates(equations,failure.lastVariables(),modes,boundaryClosed,tolerance))continue;
                // Batch 2026-11-10-rigid-reservoir: a rigid vessel holding no gas whose draw the iterate holds below its
                // vapour pressure flashes; the pass is solved again with it laid out at its fixed-volume equilibrium.
                var flashed=flashes(equations,failure.lastVariables(),seeds,flashedVessels,checkpoint);
                if(flashed!=null){seeds=flashed;continue;}
                // Decision D54: a vessel the failed iterate drained to nothing runs dry within the step. Its end state has no
                // contents, so the pass laid out with them has no root (every step size fails the same way); it is solved
                // again with the vessel as a junction holding its stock, which ends the step empty (emptyingGraph).
                var drained=equations.runsDry(failure.lastVariables(),emptying);
                if(drained!=null){emptying=drained;continue;}
                // A point the Newton never reached is not read as evidence about this pass's frozen junction donors or the
                // junction basis they state (unlike the converged tests below): a diverged trial's flow sign handed a
                // junction the tank's nitrogen as a seeded trace nothing delivers, and every swept pressure lost an interval
                // by it (a tank settled at 399999.99971 Pa went to 399998.24 Pa). Owner decisions D28 A' and D39: the same
                // holds for its phases; the iterate's check folds what the iterate empties and proposes nothing.
                var iterate=equations.states(failure.lastVariables());
                // Owner decision D39 B: the iterate is read for one thing more, the solve's own proposal. This pass is the
                // network's closure of the layout the last converged point's check proposed, and a proposed phase it drives
                // to nothing (vanishing from the proposal's seed, FluidThermodynamics.vanishing) is refuted: the pass is
                // solved again from the point it was proposed at, the node laid out without it, and the refusal recorded
                // (a tank on its bubble point proposed 3.4e-6 of its moles as vapour and stalled at every step size).
                if(proposal!=null) {
                    int refuted=proposal.phases()&FluidThermodynamics.collapsing(proposal.seed(),iterate.get(proposal.node()),rigid(graph.reservoirs().get(proposal.node())));
                    if(refuted!=0){foldedPhases[proposal.node()]|=refuted;SolverDiagnostics.count(SolverDiagnostics.phaseRefusals);seeds=proposal.point();proposal=null;continue;}
                }
                var changedSeeds=foldedIterate(graph,iterate);
                // Decision D11: a phase a vessel's ports draw at their full capacity leaves the vessel within the step; a
                // pass whose seed still holds it may have no root (see drainedSeeds).
                if(changedSeeds==null)changedSeeds=equations.drainedSeeds(checkpoint);
                if(changedSeeds==null)throw new SparseNewton.Nonconvergence(failure.getMessage()+"; active-set pass="+pass,failure.lastVariables(),failure.phaseHold());
                seeds=changedSeeds;continue;
            }
            // A pass that converges ends the proposal it was laid out with (D39 B): the network's closure kept it.
            proposal=null;
            double[] x=numerical.variables();var states=equations.states(x);double[] flows=new double[graph.pipes().size()],heads=new double[flows.length];
            // The edge unknowns are the law flows; the transported flows are their saturation at the caps (saturated).
            double[] lawFlows=Arrays.copyOfRange(x,equations.edgeOffset,equations.edgeOffset+flows.length);
            // The converged Newton point is not phase-checked here: the reconstructed point below, which an accepted step
            // commits, is, and the two agree to the Newton tolerance (checking both cost 43 % of a flowing island's wall
            // time). The reactivation asks the same stability question per component of the frozen trace support.
            if(reactivate(equations,states,promoted)>0){seeds=states;continue;}
            // Decision D54: an emptying vessel whose outlet pressure the converged point puts above the ambient does not run
            // dry (what reaches it is more than its outlets take at the ambient): it holds contents at the step's end and is
            // solved again as the vessel it is, unless an earlier converged pass of this solve has confirmed it emptying.
            if(emptying!=null){var kept=keptEmptying(original,states,emptying,emptied);if(kept!=null){emptying=kept;continue;}}
            boolean changed=false;int illegalDirection=-1,deviceChange=-1;FlowControl.Mode deviceNext=null;
            for(int i=0;i<flows.length;i++) {
                flows[i]=equations.flow(i,x[equations.edgeOffset+i],equations.cachedTransport);heads[i]=equations.controlOffsets[i]<0?0:x[equations.controlOffsets[i]]*1e5;
                if(boundaryClosed[i]||modes.get(i)==FlowControl.Mode.CLOSED){flows[i]=0;x[equations.edgeOffset+i]=0;lawFlows[i]=0;}
                // Decision D54: a dry end has nothing to give; an outflow from it within the forbidden-direction tolerance
                // below is no flow ({@link #outOfDryEnd}).
                var pipe=graph.pipes().get(i);
                if(flows[i]!=0&&Math.abs(flows[i])<=1e-10&&outOfDryEnd(graph,pipe,flows[i])){flows[i]=0;x[equations.edgeOffset+i]=0;lawFlows[i]=0;}
                // What the first (suction) end draws at this flow: its bulk at a BULK end, the priority stream at a phase port.
                var up=states.get(pipe.first());var suction=equations.drawn(i,0,equations.cachedTransport[pipe.first()],flows[i]);
                var mode=modes.get(i);var next=mode;
                if(!boundaryAllowed(graph,pipe,flows[i])&&Math.abs(flows[i])>1e-10){if(illegalDirection<0)illegalDirection=i;continue;}
                if(boundaryClosed[i])continue;
                next=nextMode(equations,i,mode,flows[i],heads[i],states,up,suction,tolerance);
                if(next!=mode&&deviceChange<0){deviceChange=i;deviceNext=next;}
            }
            // Decision A272: one active-set change per pass, a device's mode first, then a boundary closure. A boundary
            // closure is never taken back within a solve, and a forbidden direction can be the symptom of a wrong device
            // mode, so the reversible correction comes first.
            if(deviceChange>=0) {
                modes.set(deviceChange,deviceNext);changed=true;
                // A flow valve that closes can seal a mover's side (owner decision D58).
                if(flowValve(graph.pipes().get(deviceChange)))relieveSealedPockets(graph,modes,boundaryClosed);
            }
            // A passive connection showing a direction its own boundary forbids, next to a device that has just decided it
            // cannot run in its current mode, is that device's symptom: a pump held above its shutoff head pushes its whole
            // suction line backwards. {@code boundaryClosed} is never cleared within a solve, so taking the symptom first
            // pins the wrong active set for good (the closed suction edge forces the pump's flow to zero, the reverse-flow
            // test never fires again, and the pump is left running where the Newton cannot converge). The device
            // is decided first; the boundary closure waits for a pass where every device holds a mode it can.
            // Owner decision of 2026-11-10 (batch 2026-11-10-rigid-reservoir, amending A272): a boundary closure is never taken
            // back within the solve, so it is taken only on a pass whose phases stand. A converged point that shows a forbidden
            // direction is phase-checked first ({@link #phaseCorrection}); where the check changes a node's phases the pass is
            // solved again from them. A rigid tank at the gas breakthrough of its bottom port (108.5 kPa, the void at 1 atm)
            // had its last water folded off a failed iterate; the pass after it held that water as steam, at 174 K and 67 kPa,
            // and drew from the void, and the closure stood the drain still for good, before the check that lays the water out
            // again. A bag hid it: its slack wall held the ambient there.
            if(!changed&&illegalDirection>=0) {
                var corrected=phaseCorrection(graph,states,checkpoint,equations.cachedPrepared,foldedPhases);
                if(corrected!=null){seeds=corrected;continue;}
                // Owner decision of 2026-11-10 (option A): a converged point can flow the way a void or generator end forbids
                // where the network has a second root. A rigid kettle shell boiling at 101.6 kPa into a void at 1 atm converged
                // from rest on 29 kg/s drawn from the void at 101.320 kPa; the closure held its vapour line shut for the step,
                // and the next step started from rest again, so the line stayed shut for good while the shell rose to 231 kPa.
                // Before such a closure, the pass is solved once more from the same seeds with that connection started at its
                // own law's flow on the step-start states, where that flow goes the allowed way.
                var restart=restarted[illegalDirection]?null:allowedRestart(graph,illegalDirection);
                if(restart!=null){restarted[illegalDirection]=true;restarts++;allowedRestart=restart;continue;}
                closeBoundary(graph,illegalDirection,boundaryClosed,modes);changed=true;
            }
            // A confirmed emptying (keptEmptying) was decided on the network this active set states, so a mode or closure
            // change decides it again.
            if(changed)Arrays.fill(emptied,false);
            // A flow valve leaving its open pass for regulation leaves that pass's flows behind: a choked line's law flow stands on the
            // saturated law's plateau, where the full Newton step to the target crosses zero and lands further out (steam at 3.65 kg/s
            // through a valve set to 0.75: law flow 5.25, -75.9, then 48, slope 1e-11, at every step size). The regulated pass takes its
            // law flows from the balance on these modes ({@link #balanceStart}) and keeps its seeds: a junction still holding its old
            // charge is laid out on that, not on the mixture the balance sends it (a liquid reaching a gas junction went singular).
            if(deviceChange>=0&&deviceNext==FlowControl.Mode.VALVE_REGULATING&&graph.pipes().get(deviceChange).control() instanceof FlowControl.Valve valve&&!valve.pressureControl()) {
                seeds=states;balanceLawFlows=balanceStart(graph,new ArrayList<>(modes),boundaryClosed,keep,checkpoint).lawFlows();lawFlowsFromBalance=true;previousModes=List.copyOf(modes);continue;
            }
            if(changed){seeds=states;previousPipes=pipeIdentities;previousNodeIds=nodeIds;previousInputStates=inputStates;previousOutputStates=states;previousRateOnly=rateOnly;previousFlows=flows.clone();previousLawFlows=lawFlows;previousHeads=heads.clone();previousModes=List.copyOf(modes);continue;}
            // Decision D11: the phase-port segments this pass froze must be the ones its own converged flows land in
            // (which phases a port draws at its capacity and which one carries the rest); otherwise the pass is solved
            // again, starting from these flows, under the segments they decide (see PhaseDraw). Like an active-set change,
            // and the cycle key carries the segments, so the sequence stays finite. An island without a phase port has none.
            if(equations.segmentsMoved(flows)){seeds=states;previousPipes=pipeIdentities;previousNodeIds=nodeIds;previousInputStates=inputStates;previousOutputStates=states;previousRateOnly=rateOnly;previousFlows=flows.clone();previousLawFlows=lawFlows;previousHeads=heads.clone();previousModes=List.copyOf(modes);continue;}
            // What each phase-port outflow drew, phase by phase, at these flows on this candidate: the reconstruction books it
            // and the transfer samples it (decision D11).
            var draws=equations.draws(flows,states);
            // Decision D54: a dry vessel this pass's flows reach is laid out from what they deliver ({@link #arrivingPhases}), the
            // phases they draw included (what the booking books), and the pass is solved again, its end the step's implicit end
            // like every vessel's. The start flows may miss it: a rate
            // point carries none into a vessel it left dry, and the booking below states the delivered LNG at the tank's own
            // 298 K, as vapour, from which no closure reaches a liquid's enthalpy (cold seeds 181 and 397 stalled at 47 K at
            // every step size, down to 1e-21 s).
            if(!rateOnly&&anyDry(equations)) {
                var point=new ArrayList<>(states);for(int i=0;i<point.size();i++)if(equations.dry[i])point.set(i,seeds.get(i));
                var arrived=arrivingPhases(graph,point,lawFlows,draws,true,modes,boundaryClosed,dt,checkpoint);
                if(arrived!=point){seeds=arrived;previousPipes=pipeIdentities;previousNodeIds=nodeIds;previousInputStates=inputStates;previousOutputStates=states;previousRateOnly=rateOnly;previousFlows=flows.clone();previousLawFlows=lawFlows;previousHeads=heads.clone();previousModes=List.copyOf(modes);continue;}
            }
            var projection=ConservativeTransport.reconstruct(graph,states,flows,heads,dt,model,checkpoint,transport,rateOnly,draws,equations.supports,emptyings,equations.releases,equations::sideHeat,equations::moverPower);
            // Decision D54: what the booking fills past the bag's resolution though the flows delivered nothing at it (the
            // delivered amounts stated warmer) is laid out from that state.
            if(filled(equations,projection)){seeds=projection.states();continue;}
            // Hold class E3: a node this pass solved with no hydrocarbon phase, to which the booked flows deliver a
            // hydrocarbon its layout omitted, was restated by the reconstruction as its flash (ConservativeTransport
            // .repartition); the pass is solved again from it, as from any phase appearance. Before, that restatement
            // failed and every step size was refused.
            if(phaseAppeared(graph,states,projection.states())){seeds=projection.states();continue;}
            // Decision D34: the reconstruction folded a phase this pass solved (its booked share fell below the trace share),
            // so the pass is solved again from the folded states: the Newton, the reconstruction and the equation gate then
            // read the same phases.
            if(recordFolds(graph,states,projection.states(),foldedPhases)){SolverDiagnostics.count(SolverDiagnostics.traceFoldPasses);seeds=projection.states();continue;}
            var changedSeeds=phaseCorrection(graph,projection.states(),checkpoint,equations.cachedPrepared,foldedPhases);
            if(changedSeeds!=null){changedSeeds=flashSeeds(graph,modes,boundaryClosed,projection.states(),changedSeeds,checkpoint);proposal=Proposal.of(projection.states(),changedSeeds);seeds=changedSeeds;continue;}
            var reconstructed=x.clone();
            for(int node=0;node<projection.states().size();node++)if(equations.layout[node]!=null){var encoded=equations.layout[node].encode(projection.states().get(node));System.arraycopy(encoded,0,reconstructed,equations.offsets[node],encoded.length);}
            SolverDiagnostics.count(SolverDiagnostics.verificationResiduals);
            double maximumResidual=0;for(double residual:equations.residual(reconstructed))maximumResidual=Math.max(maximumResidual,Math.abs(residual));
            double gate=1e-8;
            if(maximumResidual>gate) {
                // Decision D13: a refused point gets one polish on a fresh Jacobian before the step is refused; see
                // {@link #polishRefusedPoint}. Reached only after the gate has refused, so every point it accepts is untouched.
                var polished=polishRefusedPoint(graph,equations,reconstructed,dt,modes,boundaryClosed,promoted,foldedPhases,tolerance,gate,checkpoint);
                if(polished!=null&&polished.closure()>=0) {
                    // The booked states show a drive the converged point did not state: a one-way end that would receive
                    // (a tank full of water 0.01 Pa above its generator, pushed back into it by the polish). The boundary
                    // is closed as at a converged point, and the pass is solved again from the reconstruction.
                    closeBoundary(graph,polished.closure(),boundaryClosed,modes);Arrays.fill(emptied,false);
                    seeds=projection.states();previousPipes=pipeIdentities;previousNodeIds=nodeIds;previousInputStates=inputStates;previousOutputStates=projection.states();previousRateOnly=rateOnly;previousFlows=flows.clone();previousLawFlows=lawFlows;previousHeads=heads.clone();previousModes=List.copyOf(modes);continue;
                }
                if(polished!=null){numerical=polished.numerical();x=polished.variables();states=polished.states();System.arraycopy(polished.flows(),0,flows,0,flows.length);lawFlows=Arrays.copyOfRange(x,equations.edgeOffset,equations.edgeOffset+flows.length);
                    System.arraycopy(polished.heads(),0,heads,0,heads.length);draws=polished.draws();
                    projection=polished.projection();reconstructed=polished.reconstructed();maximumResidual=polished.residual();}
            }
            if(maximumResidual>gate){SolverDiagnostics.count(SolverDiagnostics.equationGateRejections);throw new SparseNewton.Nonconvergence("Conservative reconstruction fails equation gate: "+maximumResidual);}
            // The ledger reads the island as it is: an emptying vessel is a vessel that ends empty, not the junction it stood
            // as in this pass.
            checkConservation(original,projection);
            previousPipes=pipeIdentities;previousNodeIds=nodeIds;previousInputStates=inputStates;previousOutputStates=projection.states();previousRateOnly=rateOnly;previousFlows=flows.clone();previousLawFlows=lawFlows;previousHeads=heads.clone();previousModes=List.copyOf(modes);
            acceptedPipes=pipeIdentities;acceptedNodeIds=nodeIds;
            var acceptedModes=new ArrayList<>(modes);
            for(int edge=0;edge<flows.length;edge++)if(boundaryClosed[edge]&&graph.pipes().get(edge).control() instanceof FlowControl.Passive)acceptedModes.set(edge,FlowControl.Mode.CLOSED);
            if(SolverDiagnostics.ENABLED)SolverDiagnostics.count(SolverDiagnostics.solidMomentProjectionsAtAcceptedPoints,
                    equations.negativeSolidUnknowns(x)+equations.negativeSolidUnknowns(reconstructed));
            lastSolve=new LastSolve(equations,x);
            // A vessel that ran dry within the step, or an open one that vented all it held (owner rule of 2026-10-21), holds
            // nothing at its end and shows the ambient (decision D54).
            var endStates=projection.states();List<FluidThermodynamics.State> shown=null;
            for(int i=0;i<endStates.size();i++) {
                var node=original.reservoirs().get(i);var end=projection.inventories().get(i);
                boolean dry=emptying!=null&&emptying[i]||node.kind()==PassiveNetwork.NodeKind.RESERVOIR&&!end.equals(node.inventory())
                        &&new PassiveNetwork.Reservoir(node.id(),node.elevation(),endStates.get(i),node.kind(),end).empty();
                if(!dry)continue;
                if(shown==null)shown=new ArrayList<>(endStates);
                shown.set(i,NetworkReduction.ambient(model));
            }
            if(shown!=null)endStates=shown;
            var accepted=new Result(endStates,flows,dt,numerical,acceptedModes,heads,projection.pumpWork(),projection.externalMoles(),projection.externalEnergy(),projection.inventories(),projection.boundaries(),PipeTransfer.sample(model,graph,projection.states(),flows,dt,draws),projection.filters(),observe(equations,states,flows,lawFlows,heads,acceptedModes,checkpoint),TowerExchange.readings(original,states,flows,draws,model));
            // The retained last solve keeps its equations and point, not their per-node decode caches (states, transport
            // rows, prepared temperature workspaces): a later reader re-decodes (review 8.7 (c) E2).
            equations.releaseDecodeCache();
            return accepted;
        }
        throw new SparseNewton.Nonconvergence("Device active-set limit");
    }
    /**
     * Closes connection {@code edge}'s boundary for the rest of the solve: a direction its one-way end forbids (a void
     * supplying, a generator receiving, a dry end giving).
     */
    /**
     * Owner decision of 2026-11-10 (option A): the start flows that restart connection {@code edge}, which a converged point
     * showed flowing the way its void or generator end forbids, at its own law's flow on the step-start states; null where
     * the connection has no such end or that flow is not the allowed way (then the boundary closes as before).
     */
    private double[] allowedRestart(PassiveNetwork graph,int edge) {
        var pipe=graph.pipes().get(edge);var a=graph.reservoirs().get(pipe.first()).kind();var b=graph.reservoirs().get(pipe.second()).kind();
        boolean end=a==PassiveNetwork.NodeKind.VOID||a==PassiveNetwork.NodeKind.GENERATOR||b==PassiveNetwork.NodeKind.VOID||b==PassiveNetwork.NodeKind.GENERATOR;
        if(!end)return null;
        double flow=initialMassFlow(graph,pipe);
        if(!(Math.abs(flow)>1e-10)||!boundaryAllowed(graph,pipe,flow))return null;
        double[] start=new double[graph.pipes().size()];Arrays.fill(start,Double.NaN);start[edge]=flow;
        return start;
    }
    private static void closeBoundary(PassiveNetwork graph,int edge,boolean[] boundaryClosed,List<FlowControl.Mode> modes) {
        boundaryClosed[edge]=true;
        if(!(graph.pipes().get(edge).control() instanceof FlowControl.Passive))modes.set(edge,FlowControl.Mode.CLOSED);
    }
    /** A point the equation gate refused, re-solved once and passing the gate, or the boundary its point shows must close
     * ({@code closure}, -1 for none); see {@link #polishRefusedPoint}. */
    private record Polished(SparseNewton.Result numerical,double[] variables,List<FluidThermodynamics.State> states,double[] flows,double[] heads,
                            double[][] draws,ConservativeTransport.Projection projection,double[] reconstructed,double residual,int closure) {
        /** A polished point that flows the way connection {@code edge}'s boundary forbids: no point to accept, a closure to take. */
        static Polished closing(int edge){return new Polished(null,null,null,null,null,null,null,null,Double.NaN,edge);}
    }
    /**
     * Decision D13: one re-solve of a point the reconstruction's equation gate refused, from its reconstruction ({@code x}:
     * the converged point with every node at its reconstructed state, the flows the Newton's), on a fresh Jacobian of this
     * step's own dt at 1e-2 times the pass's Newton tolerance; checked exactly as the pass loop checks a converged point
     * (no trace reactivation due, no device mode change ({@link #nextMode}), no illegal direction, the segments its flows
     * land in the ones the pass froze, the reconstruction with its own draws, the phase correction) and gated again.
     * Returns it only if all of that holds; a point that flows the way a boundary forbids
     * returns that boundary ({@link Polished#closing}); otherwise null and the step is refused as before.
     *
     * <p>Why: the Newton's last iterates run on a chord Jacobian inherited from another step, so a balance row closes only
     * to the tolerance, stated over the node's total amount; a vessel whose gas is a small share of its amounts (a nitrogen
     * cushion over water, 0.1 to 0.2 %) sees that residual magnified by n_total/n_gas (about 500) in its cushion's pressure.
     * A fresh Jacobian closes such a row in one iteration (1.6e-14 to 1.5e-12). It starts from the reconstruction because
     * the converged point itself may already meet the tolerance (9.2e-12) and a re-solve from there sees nothing. Only a
     * refused point reaches this method, and the fresh workspace is never retained, so every accepted step is bitwise what
     * it was.
     */
    private Polished polishRefusedPoint(PassiveNetwork graph,Equations equations,double[] x,double dt,List<FlowControl.Mode> modes,
                                        boolean[] boundaryClosed,boolean[][] promoted,int[] foldedPhases,double tolerance,double gate,Runnable checkpoint) {
        SolverDiagnostics.count(SolverDiagnostics.equationGatePolishes);
        SparseNewton.Result numerical;
        try{numerical=SparseNewton.solve(equations,x,new SparseNewton.Settings(SparseNewton.ITERATIONS,tolerance*1e-2,1e-6,24),checkpoint,new SparseNewton.Workspace(ownership));}
        catch(SparseNewton.Nonconvergence failure){return null;}
        double[] variables=numerical.variables();var states=equations.states(variables);
        // A trace the polished point would reactivate is an active-set change the pass loop would take (reactivate), not an
        // accepted point: asked on copies, so nothing of the pass's own state moves.
        if(model.traceTruncation().enabled())for(int node=0;node<states.size();node++) {
            var layout=equations.layout[node];
            if(layout==null||layout.singlePhaseComponentCount()==0)continue;
            boolean[] flags=promoted[node]==null?new boolean[model.componentCount()]:promoted[node].clone();
            if(layout.reactivate(states.get(node),model.traceTruncation(),flags)>0)return null;
        }
        double[] flows=new double[graph.pipes().size()],heads=new double[flows.length];
        for(int edge=0;edge<flows.length;edge++) {
            flows[edge]=equations.flow(edge,variables[equations.edgeOffset+edge],equations.cachedTransport);heads[edge]=equations.controlOffsets[edge]<0?0:variables[equations.controlOffsets[edge]]*1e5;
            if(boundaryClosed[edge]||modes.get(edge)==FlowControl.Mode.CLOSED){flows[edge]=0;variables[equations.edgeOffset+edge]=0;}
            var pipe=graph.pipes().get(edge);
            if(flows[edge]!=0&&Math.abs(flows[edge])<=1e-10&&outOfDryEnd(graph,pipe,flows[edge])){flows[edge]=0;variables[equations.edgeOffset+edge]=0;}
            if(!boundaryAllowed(graph,pipe,flows[edge])&&Math.abs(flows[edge])>1e-10)return Polished.closing(edge);
            if(boundaryClosed[edge])continue;
            var suction=equations.drawn(edge,0,equations.cachedTransport[pipe.first()],flows[edge]);
            if(nextMode(equations,edge,modes.get(edge),flows[edge],heads[edge],states,states.get(pipe.first()),suction,tolerance)!=modes.get(edge))return null;
        }
        if(equations.segmentsMoved(flows))return null;
        var draws=equations.draws(flows,states);
        var projection=ConservativeTransport.reconstruct(graph,states,flows,heads,dt,model,checkpoint,transport,rateOnly,draws,equations.supports,emptyings,equations.releases,equations::sideHeat,equations::moverPower);
        if(phaseAppeared(graph,states,projection.states())||phaseFolded(graph,states,projection.states())
                ||phaseCorrection(graph,projection.states(),checkpoint,equations.cachedPrepared,foldedPhases.clone())!=null)return null;
        var reconstructed=variables.clone();
        for(int node=0;node<projection.states().size();node++)if(equations.layout[node]!=null){var encoded=equations.layout[node].encode(projection.states().get(node));System.arraycopy(encoded,0,reconstructed,equations.offsets[node],encoded.length);}
        SolverDiagnostics.count(SolverDiagnostics.verificationResiduals);
        double residual=0;for(double value:equations.residual(reconstructed))residual=Math.max(residual,Math.abs(value));
        if(residual>gate)return null;
        SolverDiagnostics.count(SolverDiagnostics.equationGatePolishesAccepted);
        return new Polished(numerical,variables,states,flows,heads,draws,projection,reconstructed,residual,-1);
    }
    /**
     * The device mode the pass loop moves connection {@code i} to from {@code mode} at a converged point ({@code flow},
     * {@code head}, the node {@code states}, the first node's state {@code up} and what its first end draws, {@code suction});
     * {@code mode} itself when the point holds it. The one statement of the pump and valve transitions, read by the pass
     * loop and by {@link #polishRefusedPoint}, which must accept a point only where the pass loop would.
     */
    private FlowControl.Mode nextMode(Equations equations,int i,FlowControl.Mode mode,double flow,double head,List<FluidThermodynamics.State> states,
                                      FluidThermodynamics.State up,Equations.Transport suction,double tolerance) {
        var graph=equations.graph;var pipe=graph.pipes().get(i);double rho=suction.density;var next=mode;
        // Owner decision of 2026-11-10 (option A, batch 2026-11-10-custom-shader-system): a closed device never reopens toward
        // a flow its own end forbids, a discharge into a generator or a suction from a void: it can never deliver there, so it
        // stands dead-headed. Bundled random 575's pump into a 254 kPa gas generator reopened on its rise every step, stalled
        // its Newton on the law (its suction junction pulled to 75 kPa) and closed again, 4 to 8 passes a step, and the step
        // controller cut the steps to 6 ms until the island held on its wall deadline.
        if(mode==FlowControl.Mode.CLOSED&&forwardForbidden(graph,pipe))return FlowControl.Mode.CLOSED;
        if(pipe.control() instanceof FlowControl.Mover pump) {
            double band=shutoffBand(equations.pressureScales[i],tolerance);
            // A mover on its own law (batch 2026-11-03-rotating-machinery): it closes on a reverse drive (its check valve; the
            // curve itself reaches zero flow at its shutoff, owner decision D12), opens where its rise at
            // no flow beats the demand (a set-flow pump's rise on its supply, {@link #riseDensity}), goes on its suction limit
            // where its law holds its suction below its floor at its flow, the vapour pressure of the liquid it draws and its
            // breakdown NPSH (owner decisions D59 (4), D14 and D20 Q11 A, {@link #suctionLimit}), and
            // leaves it where its law cannot hold the point the suction line delivers.
            // Owner decision D16 A: a vapour-locked pump ({@link #vapourLocks}, one fact per solve on the start states) is
            // closed, whatever its mode and its rise, and never reopens in the solve; any other pump the converged point holds
            // below its floor goes on its suction limit, never closed for vapour lock. Read on the pass's point, D15's test
            // disagreed with itself: bundled random 575 and cold random 181 reopened a pump on its closed converged point
            // (+117 kPa and +400 Pa of suction head) and closed it again on the failed iterate its own law drained (the
            // supplier pulled to 7 to 73 kPa and 14 to 28 kPa), an active-set cycle (D15_HOLDS_REVIEW.md).
            if(pump.targetVolumeFlow()==0||vapourLocked(i))next=FlowControl.Mode.CLOSED;
            else if(mode==FlowControl.Mode.MOVER_LAW&&flow<-1e-10)next=FlowControl.Mode.CLOSED;
            else if(mode==FlowControl.Mode.CLOSED&&riseLimit(graph,i,riseDensity(i,rho),states.get(pipe.first()).pressure())-demand(graph,pipe,states,rho)>band)next=FlowControl.Mode.MOVER_LAW;
            else if(mode==FlowControl.Mode.MOVER_LAW&&pump.liquid()&&cavitationMargin(equations,i,states,flow/rho)<-band)next=FlowControl.Mode.PUMP_SUCTION_LIMIT;
            else if(mode==FlowControl.Mode.PUMP_SUCTION_LIMIT&&flow<-1e-10)next=FlowControl.Mode.CLOSED;
            else if(mode==FlowControl.Mode.PUMP_SUCTION_LIMIT&&lawResidual(graph,i,flow/rho,head,riseDensity(i,rho),states.get(pipe.first()).pressure())>band)next=FlowControl.Mode.MOVER_LAW;
        }else if(pipe.control() instanceof FlowControl.Valve valve) {
            if(!Double.isNaN(equations.fixedOpenings[i]))return equations.fixedOpenings[i]==0?FlowControl.Mode.CLOSED:FlowControl.Mode.VALVE_CONFLICT;
            if(valve.bare())return FlowControl.Mode.VALVE_OPEN;
            if(!valve.pressureControl()) {
                double target=valve.targetMassFlow(rho),band=Math.max(1e-10,1e-8*target);boolean stands=standing.get(i);
                if(target==0&&!stands)return FlowControl.Mode.CLOSED;
                if(mode==FlowControl.Mode.VALVE_REGULATING) {
                    if(flow<-band)return FlowControl.Mode.CLOSED;
                    if(head<-.01||target>equations.capLimit(i,0,equations.cachedTransport[pipe.first()])*(1+1e-8))return FlowControl.Mode.VALVE_OPEN;
                } else if(mode==FlowControl.Mode.VALVE_OPEN) {
                    if(flow<-band)return FlowControl.Mode.CLOSED;
                    if(!stands&&flow>target+band)return FlowControl.Mode.VALVE_REGULATING;
                } else if(mode==FlowControl.Mode.CLOSED&&head>.01)return FlowControl.Mode.VALVE_OPEN;
                return next;
            }
            if(mode==FlowControl.Mode.VALVE_REGULATING&&flow<-1e-10)next=FlowControl.Mode.CLOSED;
            else if(mode==FlowControl.Mode.VALVE_REGULATING&&flow>equations.capLimit(i,0,equations.cachedTransport[pipe.first()])*(1+1e-8))next=FlowControl.Mode.VALVE_OPEN;
            else if(mode==FlowControl.Mode.VALVE_REGULATING&&head<-.01)next=FlowControl.Mode.VALVE_OPEN;
            else if(mode==FlowControl.Mode.VALVE_OPEN&&flow<-1e-10)next=FlowControl.Mode.CLOSED;
            else if(mode==FlowControl.Mode.VALVE_OPEN&&!graph.reservoirs().get(pipe.first()).fixed()&&up.pressure()<valve.targetPressure()-.01&&flow>1e-10)next=FlowControl.Mode.VALVE_REGULATING;
            else if(mode==FlowControl.Mode.CLOSED&&up.pressure()>valve.targetPressure()+.01&&head>.01)next=FlowControl.Mode.VALVE_REGULATING;
        }
        return next;
    }
    /**
     * Owner decisions D59 (4), D14 and D17: how far the suction end of connection {@code edge} stands above the pressure its
     * suction limit holds it at ({@link #suctionLimit}), read on {@code states} at suction volume flow {@code volumeFlow}
     * (m3/s) (Pa), so the mode test and the limit's row read one value; positive infinity where the connection is not a
     * liquid pump or its suction has no vapour pressure to stand at.
     */
    private double cavitationMargin(Equations equations,int edge,List<FluidThermodynamics.State> states,double volumeFlow) {
        var pipe=equations.graph.pipes().get(edge);int a=pipe.first();
        double limit=suctionLimit(equations,edge,states::get,volumeFlow);
        return limit<Double.POSITIVE_INFINITY?endPressure(equations.graph.reservoirs().get(a),pipe.firstPort(),states.get(a))-limit:Double.POSITIVE_INFINITY;
    }
    /**
     * Owner decision D17 (bundled random 896): the pressure the suction end of pump connection {@code edge} stands at on its
     * suction limit, on the node states {@code states} and its suction volume flow {@code volumeFlow} (m3/s; the trial point
     * in the Newton's row): the lesser of its floor and what its supplier gives at no flow ({@link #suctionSupply}, the supplier
     * its suction walk ends at, {@link Equations#suctionSuppliers}). A suction limit never stands above its supplier: where
     * the supplier falls to its bubble point within the step (896's tee, flashing to pass its main stream), the suction
     * stands at the supplier less its static column, the suction line carries nothing and the pump delivers nothing,
     * continuously and with no mode change. Held at the floor alone, the suction stood above a tee that had to flash below
     * it, the line had to reverse and no point balanced the tee (D14_HOLDS_REVIEW.md). The floor where the pump has no
     * suction walk (it draws straight from its first node) or no floor.
     *
     * <p>The floor (owner decision D20 Q11 A, plan section 3.6) is the floor of the liquid it draws ({@link #vapourPressure})
     * plus its breakdown NPSH at its flow, {@code 0.85 ρ_l g NPSH3(Q, s)} ({@link #breakdown}): the suction pressure at which
     * its head breaks down, so it delivers what its suction can feed there; the floor rises with the flow while the suction
     * line's pressure falls with it, so they cross once, and the bubble floor is its {@code NPSH3 = 0} end. The row reads it on
     * its own flow column: the block sweep re-assembles the actuator row for the edge's flow, which the sparsity declares
     * ({@link Equations#buildSparsity}). Where the pump has no floor (infinite) the NPSH adds none.
     */
    private double suctionLimit(Equations equations,int edge,IntFunction<FluidThermodynamics.State> states,double volumeFlow) {
        var pipe=equations.graph.pipes().get(edge);
        double floor=suctionLimitPressure(equations.graph,edge,breakdown(equations.graph,edge,volumeFlow));int supplier=equations.suctionSuppliers[edge];
        if(!(floor<Double.POSITIVE_INFINITY)||supplier<0)return floor;
        var s=states.apply(supplier);int link=equations.suctionLinks[edge];
        return Math.min(floor,suctionSupply(equations.graph,edge,supplier,link,endPressure(equations.graph.reservoirs().get(supplier),equations.graph.pipes().get(link).portAt(supplier),s),s));
    }
    /**
     * Owner decisions D15 and D17: the pressure the supplier {@code supplier} of pump connection {@code edge} gives at the
     * pump's first node with nothing flowing, its suction walk's end ({@link InletPhase#walk}, reached through its link
     * {@code link}): its end pressure {@code supplierEnd} less the static column of its stream {@code s} up to that node.
     */
    private double suctionSupply(PassiveNetwork graph,int edge,int supplier,int link,double supplierEnd,FluidThermodynamics.State s) {
        var line=graph.pipes().get(link);var node=graph.reservoirs().get(supplier);
        double lift=graph.reservoirs().get(graph.pipes().get(edge).first()).elevation()-node.elevation();
        return supplierEnd-columnDensity(s,line.portAt(supplier),line.volume())*GRAVITY*lift;
    }
    /**
     * The vapour pressure of the liquid the pump on connection {@code edge} draws, for the whole solve
     * ({@link #suctionFloors}): its p_v
     * ({@link #release}). Its wear reads σ against it ({@link #npshRatio}); its suction limit and vapour lock read it with
     * the zone edge ({@link #suctionLimitPressure}). Positive infinity where the suction has none (no pump, a VAPOR port, a supplier with no
     * liquid, a liquid that stays one liquid down to the numerical box's floor). The water saturation curve of owner
     * decision D59 (4) was lifted by the owner on 2026-11-11 (W3B_NETWORK_PLAN.md section 17): water is one more liquid.
     * {@code suction} is the trial state of the pump's first node, which the floor does not read.
     */
    double vapourPressure(Equations equations,int edge,FluidThermodynamics.State suction) {
        return vapourPressure(equations.graph,edge);
    }
    /** {@link #vapourPressure} on {@code graph}. */
    private double vapourPressure(PassiveNetwork graph,int edge) {
        var pipe=graph.pipes().get(edge);
        if(!(pipe.control() instanceof FlowControl.Mover liquid&&liquid.liquid())||pipe.firstPort()==PassiveNetwork.PhasePort.VAPOR)return Double.POSITIVE_INFINITY;
        return edge<suctionFloors.length?suctionFloors[edge]:Double.POSITIVE_INFINITY;
    }
    /**
     * W3B_NETWORK_PLAN.md section 17: the pressure the suction of the pump on connection {@code edge} of {@code graph} is
     * held at with {@code breakdown} (Pa) of breakdown NPSH: {@link #limit} of its liquid's p_v ({@link #vapourPressure})
     * and the clean zone's edge E of what its line delivers ({@link #zoneEdge}). The suction limit reads it with the
     * breakdown at the pump's flow; the vapour lock, and every test of whether a pump has a limit, with none.
     */
    private double suctionLimitPressure(PassiveNetwork graph,int edge,double breakdown) {
        return limit(vapourPressure(graph,edge),zoneEdge(edge),breakdown);
    }
    /**
     * The suction limit of section 17 from its two edges: the larger of those that exist, p_v with the breakdown NPSH added
     * and E without it; positive infinity (no limit) only where neither exists. A heavy dead oil under a blanket has no
     * p_v (its condensables stay liquid down to the box's floor) and an E; a liquid whose top trial the engine refused has
     * a p_v and no E.
     */
    static double limit(double vapourPressure,double zoneEdge,double breakdown) {
        boolean vapour=vapourPressure<Double.POSITIVE_INFINITY,zone=zoneEdge<Double.POSITIVE_INFINITY;
        if(vapour&&zone)return Math.max(zoneEdge,vapourPressure+breakdown);
        return vapour?vapourPressure+breakdown:zone?zoneEdge:Double.POSITIVE_INFINITY;
    }
    /**
     * The clean zone's edge E of what the suction line of the pump on connection {@code edge} delivers in this solve
     * ({@link #drawnStreams}), worked out when first read ({@link #zoneEdge(InletPhase.Drawn,Runnable)}). Positive
     * infinity where the pump draws no liquid ({@link #suctionFloors}: its supplier holds none, or a VAPOR port).
     */
    private double zoneEdge(int edge) {
        if(edge>=zoneEdges.length||edge>=drawnStreams.length||drawnStreams[edge]==null)return Double.POSITIVE_INFINITY;
        if(Double.isNaN(zoneEdges[edge]))zoneEdges[edge]=zoneEdge(drawnStreams[edge],suctionCheckpoint);
        return zoneEdges[edge];
    }
    /**
     * Owner decisions D15 and D16 A (batch 2026-11-03-rotating-machinery, D14_HOLDS_REVIEW.md, D15_HOLDS_REVIEW.md): per
     * connection, whether its pump is vapour-locked in this solve, worked out once on the step's start {@code states}, the
     * states its floor is read on ({@link #suctionFloors}). A liquid pump is vapour-locked where its suction head at no flow
     * ({@link #suctionHead}, the available NPSH) is at or below the band of its mode tests ({@link #shutoffBand} at the
     * larger end pressure, the Newton's tolerance): its line delivers nothing with the suction at the floor, so it passes
     * nothing and stands closed, not on its suction limit with no flow. Whether it is depends on its supplier at no flow,
     * never on a point its own law drained: one fact for the start modes ({@link #initialModes}), the carry, the balance
     * and the pass loop ({@link #nextMode}, {@link #cavitates}), so a locked pump stays closed for the whole solve and any
     * other pump the solve finds starved goes on its suction limit. Bundled random 896: a crude drawn through a 1 cm line
     * from a tee at its 214.78 kPa bubble point, anchored at the floor, had no root at any step size. Bundled random 575 (a
     * crude tee 99 to 117 kPa above its 124.18 kPa floor) and cold random 181 (LNG 400 Pa above its 101.32 kPa floor with its
     * drain flowing, none with it stopped) cycled while the test was read on each pass's point: the closed converged point
     * reopened the pump, its law pulled the supplier itself to 7 to 73 kPa and 14 to 28 kPa, and the failed iterate closed
     * it again. A pump whose supplier recovers stays closed for that one solve (181: 0.03 kg/s for one step). The lock (D15)
     * was lifted by the owner on 2026-11-11 subject to measurement; measured on form G and kept (W3B_NETWORK_PLAN.md section
     * 17: removed, cold random 181 stalled in a line search at tick 1086 and bundled iterations rose 5.1 %). The lock reads
     * the suction limit ({@link #suctionLimitPressure})
     * without the breakdown NPSH (plan section 3.6: it is the NPSHA ≤ 0 end): a supplier whose head at no flow lies between
     * zero and the breakdown NPSH at shutoff leaves the pump on its suction limit, where D17's lesser-of takes its delivery
     * to zero continuously ({@link #suctionLimit}).
     */
    private boolean[] vapourLocks(PassiveNetwork graph,List<FluidThermodynamics.State> states) {
        boolean[] locked=new boolean[graph.pipes().size()];
        for(int edge=0;edge<locked.length;edge++) {
            var pipe=graph.pipes().get(edge);
            if(!(pipe.control() instanceof FlowControl.Mover pump&&pump.liquid())||pump.targetVolumeFlow()==0)continue;
            var a=states.get(pipe.first());double head;
            try{head=suctionHead(graph,edge,suctionLimitPressure(graph,edge,0),states);}catch(IllegalArgumentException outsideData){continue;}
            locked[edge]=!(head>shutoffBand(Math.max(a.pressure(),states.get(pipe.second()).pressure()),NEWTON_TOLERANCE));
        }
        return locked;
    }
    /** Whether the pump on connection {@code edge} is vapour-locked in this solve ({@link #vapourLocks}). */
    private boolean vapourLocked(int edge){return edge<vapourLocked.length&&vapourLocked[edge];}
    /**
     * Owner decision D15: the suction head the line of liquid pump connection {@code edge} has at no flow over the floor
     * {@code floor} of the liquid it draws, on the node {@code states} (Pa), the step's start states ({@link #vapourLocks}):
     * the end pressure of the supplier its suction walk ends at ({@link InletPhase#walk}), less the floor and the static
     * column of the supplier's stream up to the pump's first node. Positive infinity where the pump has no floor
     * ({@code floor} infinite) or no suction walk (it draws straight from its first node: its line has no length over which
     * to lose its head at no flow).
     */
    private double suctionHead(PassiveNetwork graph,int edge,double floor,List<FluidThermodynamics.State> states) {
        if(!(floor<Double.POSITIVE_INFINITY))return Double.POSITIVE_INFINITY;
        var walk=InletPhase.walk(graph,edge);if(walk.via()<0)return Double.POSITIVE_INFINITY;
        var s=states.get(walk.node());var link=graph.pipes().get(walk.via());var supplier=graph.reservoirs().get(walk.node());
        return suctionSupply(graph,edge,walk.node(),walk.via(),endPressure(supplier,link.portAt(walk.node()),s),s)-floor;
    }
    /**
     * Owner decision D14 (reading A13; K6 of the t38 plan widened), as W3B_NETWORK_PLAN.md section 17 restates it: a pump's
     * suction limit reads the liquid it draws, at that liquid's own enthalpy ({@link #release}), whatever the liquid. Per
     * pump connection, for the whole solve, on the step's start states: the floor of the liquid of the supplier its suction
     * walk ends at ({@link InletPhase#walk}), or of its own first node where it draws straight from it; the clean zone's edge
     * is read when first needed ({@link #zoneEdge}), and only for a pump that draws a liquid here. Positive infinity where there is none, and where the
     * thermodynamics refuses the evaluation (no floor, never a hold). An LNG tank at 112 K and 222 kPa feeding a pump
     * through its line drew the junction below the bubble point, where the liquid flashed and the step stalled; with water at
     * that temperature the limit was nothing. A subcooled light crude at 310 K and 390 kPa, drawn by a 0.01 m3/s pump
     * through a 1 cm line that passes 3.04 kg/s, was pulled to the 1 Pa floor still laid out as liquid, and the line search
     * stalled (bundled random seeds 952 and 575).
     */
    private double[] suctionFloors(PassiveNetwork graph,List<FluidThermodynamics.State> states,Runnable checkpoint) {
        double[] floors=new double[graph.pipes().size()];Arrays.fill(floors,Double.POSITIVE_INFINITY);
        // NaN: worked out when first read; infinite: the pump draws no liquid, so no edge.
        zoneEdges=new double[floors.length];Arrays.fill(zoneEdges,Double.POSITIVE_INFINITY);suctionCheckpoint=checkpoint;
        for(int edge=0;edge<floors.length;edge++) {
            var pipe=graph.pipes().get(edge);
            if(!(pipe.control() instanceof FlowControl.Mover liquid&&liquid.liquid())||pipe.firstPort()==PassiveNetwork.PhasePort.VAPOR)continue;
            // Section 17 (owner, 2026-11-11): a pump drawing straight from its tank reads its own first node, as one drawing
            // through a suction walk reads the walk's end. The old rule ("a tank's own closure holds its bubble pressure")
            // left it with no NPSH, no cavitation and no wear.
            var walk=InletPhase.walk(graph,edge);boolean direct=walk.via()<0;
            int supplier=direct?pipe.first():walk.node();var s=states.get(supplier);
            int label=drawnLiquid(s,direct?pipe.firstPort():graph.pipes().get(walk.via()).portAt(supplier));
            if(label<0)continue;
            floors[edge]=release(s,checkpoint);zoneEdges[edge]=Double.NaN;
        }
        return floors;
    }
    /** The liquid label (OIL or AQUEOUS) a pump draws from supplier state {@code s} through its end {@code port}: the port's
     * leading liquid; the hydrocarbon liquid where it draws both or the bulk and the supplier holds one; -1 for none. */
    private int drawnLiquid(FluidThermodynamics.State s,PassiveNetwork.PhasePort port) {
        int leading=PhaseDraw.leading(model,s,port);
        if(leading==FluidThermodynamics.OIL||leading==FluidThermodynamics.AQUEOUS)return leading;
        if(FluidThermodynamics.holdsPhase(s,FluidThermodynamics.OIL))return FluidThermodynamics.OIL;
        return FluidThermodynamics.holdsPhase(s,FluidThermodynamics.AQUEOUS)?FluidThermodynamics.AQUEOUS:-1;
    }
    /** {@link #zoneEdge(InletPhase.Drawn,Runnable)} worked out, per supplier state and the stream drawn from it (a
     * generator feeding two pumps through different ends has one E per end). */
    private static final Map<FluidThermodynamics.State,Map<DrawKey,Double>> ZONE_EDGE_CACHE=Collections.synchronizedMap(new WeakHashMap<>());
    /** A drawn stream as the cache tells streams of one state apart: its amounts and enthalpy. */
    private record DrawKey(double[] amounts,double enthalpy) {
        @Override public boolean equals(Object o){return o instanceof DrawKey k&&Double.compare(enthalpy,k.enthalpy)==0&&Arrays.equals(amounts,k.amounts);}
        @Override public int hashCode(){return 31*Arrays.hashCode(amounts)+Double.hashCode(enthalpy);}
    }
    /**
     * W3B_NETWORK_PLAN.md section 17 (owner, 2026-11-11), form G: the clean zone's edge E of the stream {@code drawn} a
     * suction end draws now ({@link #drawnStreams}); none where it holds no liquid; the supplier's pressure where the
     * stream's vapour volume share is at or past {@link com.wormzjl.icheme.science.machine.GasEntrainment#cleanShare}
     * there; else the lowest pressure at which the stream, at its own fluid enthalpy (solids apart), holds a vapour volume
     * share at most the clean share there ({@link #releasePressure}); positive infinity where there is none. Cold random 181
     * and bundled random 6: a pump on a boiling two-phase junction, E read on its liquid alone 1.1 kPa under the junction's
     * pressure, was left open there and the junction's rows stalled; read on the junction's mixture, E is its pressure. A
     * liquid drawn through a vessel's liquid port from under its blanket keeps an E below the vessel's pressure. The limit
     * is max(E, p_v + 0.85 ρ g NPSH3(Q)) ({@link #limit}); wear reads σ against p_v.
     */
    double zoneEdge(InletPhase.Drawn drawn,Runnable checkpoint) {
        // A phase port drawing gas now draws no liquid: no edge (the dry-suction refusal owns that case).
        if(!drawn.condensed())return Double.POSITIVE_INFINITY;
        var s=drawn.state();double p=s.pressure();
        if(drawn.supply().vapourVolumeShare()>=com.wormzjl.icheme.science.machine.GasEntrainment.cleanShare(p))return p;
        var key=new DrawKey(drawn.amounts(),drawn.enthalpy());
        var known=ZONE_EDGE_CACHE.computeIfAbsent(s,k->Collections.synchronizedMap(new HashMap<>()));
        var e=known.get(key);if(e!=null)return e;
        double edge=PhaseLayout.sum(drawn.amounts())>0?releasePressure(s,drawn.amounts(),drawn.enthalpy(),com.wormzjl.icheme.science.machine.GasEntrainment::cleanShare,checkpoint):Double.POSITIVE_INFINITY;
        known.put(key,edge);
        return edge;
    }
    /** {@link #release} worked out, keyed by the supplier state (a generator keeps its state across solves). */
    private static final Map<FluidThermodynamics.State,Double> RELEASE_CACHE=Collections.synchronizedMap(new WeakHashMap<>());
    /**
     * p_v of supplier state {@code s}'s liquids together (Pa, {@link #releasePressure}), once per state: the bubble
     * pressure of its condensable compounds, those above their critical temperature at the supplier's temperature left
     * out, at their own enthalpy at the supplier's pressure, every liquid (water alone at 298.15 K and 1 atm: about
     * 3175 Pa; nitrogen or air dissolved in it: none of it; CO2 dissolved in water at 298 K counts, its Tc is 304.1 K).
     * Positive infinity where there is none. The clean zone's edge is the drawn stream's ({@link #zoneEdge}).
     */
    double release(FluidThermodynamics.State s,Runnable checkpoint) {
        var known=RELEASE_CACHE.get(s);if(known!=null)return known;
        double r=Double.POSITIVE_INFINITY;
        if(FluidThermodynamics.holdsLiquid(s)) {
            // W3b: the supplier's liquids together, water among their components: two liquids at equilibrium share one bubble
            // pressure, and a pump drawing either draws the same floor.
            double[] x=liquidMoles(s);double h=liquidEnthalpy(s),t=s.temperature();
            double[] c=x.clone();boolean removed=false,left=false;
            for(int k=0;k<c.length;k++){if(c[k]>0&&model.hydrocarbon.criticalTemperature(k)<=t){c[k]=0;removed=true;}left|=c[k]>0;}
            if(left) {
                double hc=h;
                if(removed)try{hc=model.flashTP(t,s.pressure(),c.clone(),checkpoint).enthalpy();}catch(IllegalArgumentException refused){hc=Double.NaN;}
                if(Double.isFinite(hc))r=releasePressure(s,c,hc,p->0,checkpoint);
            }
        }
        RELEASE_CACHE.put(s,r);
        return r;
    }
    /** The relative width in pressure to which {@link #releasePressure} brackets its pressure. The floor is read from one
     * evaluation for a whole solve, so its rows and mode tests all read the same value; the width only places it against
     * the true pressure (0.1 Pa at 100 kPa), and it costs about 24 PH equilibria from the box's floor, once per supplier
     * state ({@link #RELEASE_CACHE}). */
    private static final double BUBBLE_WIDTH=1e-6;
    /**
     * W3B_NETWORK_PLAN.md section 17, after owner decisions D14 (reading A13) and D16 C's first half: the lowest pressure,
     * between the numerical box's floor and supplier {@code s}'s pressure, at which the liquid {@code x} drawn from it, at
     * its own enthalpy {@code h} (J), holds a vapour volume share at most {@code share} at that pressure. The liquid reaches
     * the pump through its line as through a throttle, which keeps its enthalpy. A bisection in ln P to
     * {@link #BUBBLE_WIDTH} on the engine's PH equilibrium ({@link FluidThermodynamics#equilibriumPH}, unfolded, so a trace
     * of vapour counts) that returns the high end, so a suction held there holds no more vapour than {@code share}; with
     * {@code share} 0 it is the bubble pressure. Midpoints are geometric means ({@link Math#sqrt}, correctly rounded, the
     * same on every runtime). A trial the engine cannot state as a network phase set (no convergence, a product proved
     * unstable, the cold end of a deep flash outside the box: {@link #releaseShare}) does not pass; the pressure stands
     * only where some trial below the end stated a vapour. The supplier's pressure where the liquid holds more there;
     * positive infinity where it passes at the box's floor, where the equilibrium at the supplier's own pressure is refused
     * and where no trial stated a vapour: no floor, never a hold (AGENTS.md, thermo validity).
     *
     * <p>At the liquid's temperature (D14's TP form) the floor stood below the pressure at which the throttled liquid,
     * warmed by its Joule-Thomson effect, boils: cold random 181's LNG has its floor at 101 324.09 Pa at its temperature and
     * 101 324.998 Pa at its enthalpy, and held at the lower one its suction flashed a trace and stalled at tick 1078; at its
     * enthalpy that tick passes (D15_HOLDS_REVIEW.md, probe C). The same for water: PumpCavitationTest's starved suction,
     * water at 298.15 K and 1 atm drawn down, is 91 J/kg over the saturated liquid at its stated temperature; held at that
     * temperature's saturation pressure 3.7e-5 of what arrived flashed, the pure-water junction held steam beside its water
     * and the suction limit's row was its own phase rule: the step's Jacobian was singular. At its own enthalpy it stands
     * liquid at about 298.17 K. That is why water's own curve (BalanceNet.waterFloor, D59 (4)) read its enthalpy; this
     * reads every liquid's.
     */
    private double releasePressure(FluidThermodynamics.State s,double[] x,double h,java.util.function.DoubleUnaryOperator share,Runnable checkpoint) {
        double hi=s.pressure(),lo=NumericalBox.MINIMUM_PRESSURE;
        if(!(hi>lo))return Double.POSITIVE_INFINITY;
        double top=releaseShare(s,x,h,hi,checkpoint);
        if(Double.isNaN(top))return Double.POSITIVE_INFINITY;
        if(!(top<=share.applyAsDouble(hi)))return hi;
        double bottom=releaseShare(s,x,h,lo,checkpoint);
        if(bottom<=share.applyAsDouble(lo))return Double.POSITIVE_INFINITY;
        boolean vapour=bottom>0;
        for(int k=0;k<200&&hi>lo*(1+BUBBLE_WIDTH);k++){double mid=Math.sqrt(lo*hi),at=releaseShare(s,x,h,mid,checkpoint);if(at<=share.applyAsDouble(mid))hi=mid;else{lo=mid;vapour|=at>0;}}
        return vapour?hi:Double.POSITIVE_INFINITY;
    }
    /** The vapour volume share of the PH equilibrium of liquid {@code x} at enthalpy {@code h} (J) and pressure {@code p},
     * warm-started at supplier {@code s}'s temperature ({@link #releasePressure}): 0 for liquid alone, the smallest positive
     * double for a vapour too small to have a volume, NaN for anything the network cannot carry or no liquid. */
    private double releaseShare(FluidThermodynamics.State s,double[] x,double h,double p,Runnable checkpoint) {
        FluidThermodynamics.State at;
        try{at=model.equilibriumPH(s.temperature(),p,h,x.clone(),checkpoint);}catch(IllegalArgumentException refused){return Double.NaN;}
        if(at==null||!(FluidThermodynamics.holdsPhase(at,FluidThermodynamics.OIL)||FluidThermodynamics.holdsPhase(at,FluidThermodynamics.AQUEOUS)))return Double.NaN;
        if(!FluidThermodynamics.holdsPhase(at,FluidThermodynamics.GAS))return 0;
        double v=at.vaporVolume(),total=v+at.condensedVolume();
        return total>0&&v>0?v/total:Double.MIN_VALUE;
    }
    /** The answers of {@link #liquidPH}: liquid alone, a vapour beside the liquid, or anything else (a refusal, a phase set
     * the network cannot carry, no liquid). */
    private static final int PH_LIQUID=1,PH_VAPOUR=0,PH_OTHER=-1;
    /** The PH equilibrium of the liquid {@code x} of supplier {@code s} at its enthalpy {@code h} (J) and pressure
     * {@code p}, warm-started at the supplier's temperature, as {@link #releasePressure} reads it. W3b: any liquid counts,
     * water's among them (a single liquid of half water or more is labelled aqueous, so the oil label alone left a water
     * pump with no floor and a water valve with no cavitation). */
    private int liquidPH(FluidThermodynamics.State s,double[] x,double h,double p,Runnable checkpoint) {
        FluidThermodynamics.State at;
        try{at=model.equilibriumPH(s.temperature(),p,h,x.clone(),checkpoint);}catch(IllegalArgumentException refused){return PH_OTHER;}
        if(at==null||!(FluidThermodynamics.holdsPhase(at,FluidThermodynamics.OIL)||FluidThermodynamics.holdsPhase(at,FluidThermodynamics.AQUEOUS)))return PH_OTHER;
        return FluidThermodynamics.holdsPhase(at,FluidThermodynamics.GAS)?PH_VAPOUR:PH_LIQUID;
    }
    /**
     * Owner decision D59 (4) on a failed Newton: the first pump on its law whose suction the last iterate
     * holds below the vapour pressure ({@link #cavitationMargin}, by more than {@link #shutoffBand}) is put on its suction
     * limit; true if one was. The mode is part of the cycle key, so the sequence stays finite. Owner decision D16 A: the
     * iterate is never read for vapour lock, a point the pump's own law drained (575's and 181's failed iterates pulled the
     * supplier below its floor and closed a pump the closed point then reopened); a vapour-locked pump ({@link #vapourLocks})
     * is closed for the whole solve and never on its law here.
     */
    /**
     * Batch 2026-11-10-rigid-reservoir (RIGID_RESERVOIR_PLAN.md 4.2) on a failed Newton: a rigid vessel whose seed holds no gas
     * cannot give up volume as a liquid alone below its vapour pressure (a liquid-full tank drained below its bubble point has
     * no liquid-only root). Each such vessel whose iterate holds less than its seed, or none the network can state, is seeded
     * at the engine's fixed-volume equilibrium of the iterate's amounts and energy, once per solve ({@code flashed}); null
     * where none is. The seed list is otherwise the pass's own.
     */
    private List<FluidThermodynamics.State> flashes(Equations equations,double[] last,List<FluidThermodynamics.State> seeds,boolean[] flashed,Runnable checkpoint) {
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
    private boolean cavitates(Equations equations,double[] last,List<FlowControl.Mode> modes,boolean[] boundaryClosed,double tolerance) {
        List<FluidThermodynamics.State> states=null;
        for(int i=0;i<modes.size();i++) {
            var mode=modes.get(i);
            if(boundaryClosed[i]||mode!=FlowControl.Mode.MOVER_LAW||!(equations.graph.pipes().get(i).control() instanceof FlowControl.Mover liquid&&liquid.liquid()))continue;
            if(states==null)try{states=equations.states(last);}catch(IllegalArgumentException outsideDomain){return false;}
            // The iterate's own suction volume flow, as the row reads it (owner decision D20 Q11 A, {@link #suctionLimit}).
            int a=equations.graph.pipes().get(i).first();double flow=equations.flow(i,last[equations.edgeOffset+i],equations.cachedTransport);
            if(cavitationMargin(equations,i,states,flow/equations.drawn(i,0,equations.cachedTransport[a],flow).density)<-shutoffBand(equations.pressureScales[i],tolerance))
                {modes.set(i,FlowControl.Mode.PUMP_SUCTION_LIMIT);return true;}
        }
        return false;
    }
    /** Value key over compact codes instead of rendered strings and boxed lists; the arrays belong
     * to the key from construction on, so a caller must hand over a snapshot of anything it mutates.
     * {@code supports} is the per-node, per-component phase support flattened in node order: it
     * decides how many unknowns and rows each node block has, so the sparsity pattern, the colouring,
     * the ordering and every retained factorization belong to it as much as to the phase code. */
    private record WorkspaceKey(long stepBits,long[] nodeIds,byte[] kinds,List<PassiveNetwork.Pipe.Identity> pipes,
                                int[] phases,boolean[] componentMask,byte[] supports,byte[] modes,boolean[] boundaryClosed,
                                boolean[] junctionDonors) {
        @Override public boolean equals(Object other) {
            return other instanceof WorkspaceKey key&&stepBits==key.stepBits&&Arrays.equals(nodeIds,key.nodeIds)
                    &&Arrays.equals(kinds,key.kinds)&&pipes.equals(key.pipes)&&Arrays.equals(phases,key.phases)
                    &&Arrays.equals(componentMask,key.componentMask)&&Arrays.equals(supports,key.supports)
                    &&Arrays.equals(modes,key.modes)&&Arrays.equals(boundaryClosed,key.boundaryClosed)
                    &&Arrays.equals(junctionDonors,key.junctionDonors);
        }
        @Override public int hashCode() {
            int hash=31*Long.hashCode(stepBits)+Arrays.hashCode(nodeIds);
            hash=31*(31*hash+Arrays.hashCode(kinds))+pipes.hashCode();
            hash=31*(31*hash+Arrays.hashCode(phases))+Arrays.hashCode(componentMask);
            hash=31*hash+Arrays.hashCode(supports);
            hash=31*(31*hash+Arrays.hashCode(modes))+Arrays.hashCode(boundaryClosed);
            return 31*hash+Arrays.hashCode(junctionDonors);
        }
    }
    /**
     * The cycle key of one active-set pass: its structure and the phase-port segments it froze ({@link PhaseDraw.Segment},
     * which change a port stream's values but no structural entry, so they are not part of the workspace key), plus the
     * phases the trace rule has removed and the own splits and refuted proposals have refused so far in the solve (D34,
     * A85, D26 amendment, D39 B): that record only grows, so a pass that returns to an earlier structure after a fold or
     * a refusal is a pass under a different phase-correction rule, not a cycle. The proposal the pass is laid out under
     * ({@link Proposal}: its node and phases, none as -1 and 0) belongs to the key too (t38 form B, 2026-10-18): a pass
     * whose seed a failed iterate's fold trimmed, re-proposed by the converged point's check, is the pass that refutes or
     * keeps the proposal (D39 B), not a cycle with the pass that failed before the fold; the record of refusals still
     * grows at every refutation, so the sequence stays finite (CO2 gas mixed into LNG near its critical region: the check
     * and the Newton disagreed, the fold and the proposal alternated, and the guard fired before the refutation could).
     *
     * <p>The static column of each connection ({@link Equations#columnEnds}) belongs to the key as well (batch
     * 2026-10-15-custom-equipment-rendering, OD4 holds): like a segment it changes a row's value and no structural entry,
     * and each pass states it from its own seed, so two passes with the same modes can state different equations. A
     * junction holding gas under a liquid-full tank stands at the tank's bottom pressure under its gas column and one
     * metre of water higher under the water column: a 150 kPa pressure valve on it was closed at 152.5 kPa (water column),
     * regulated to 150 kPa with -0.0027 kg/s (gas column), and the closed pass under the gas column, which converges at
     * 143.0 kPa, was refused as a repeat of the first (bundled fuzz seed 145, hold E7). The columns are a finite record, so
     * the sequence stays finite; a pair of passes that alternates its columns repeats a key one pass later.
     */
    private record PassKey(WorkspaceKey structure,byte[] segments,byte[] columns,int[] folded,int proposalNode,int proposalPhases,int restarts) {
        @Override public boolean equals(Object other){return other instanceof PassKey key&&structure.equals(key.structure)&&Arrays.equals(segments,key.segments)&&Arrays.equals(columns,key.columns)&&Arrays.equals(folded,key.folded)&&proposalNode==key.proposalNode&&proposalPhases==key.proposalPhases&&restarts==key.restarts;}
        @Override public int hashCode(){return 31*(31*(31*(31*(31*(31*structure.hashCode()+Arrays.hashCode(segments))+Arrays.hashCode(columns))+Arrays.hashCode(folded))+proposalNode)+proposalPhases)+restarts;}
    }
    /**
     * A proposal (owner decision D39 B): the phases ({@link FluidThermodynamics#phaseBits} bits) the check of a converged
     * pass's point added to one node ({@link #phaseCorrection}), the node's seed holding them and the point they were
     * added at. The passes after it are the network's own closure of that layout; one that fails with a proposed phase
     * vanishing from the seed refutes it.
     */
    private record Proposal(int node,int phases,FluidThermodynamics.State seed,List<FluidThermodynamics.State> point) {
        /** The proposal of the phase correction from {@code point} to {@code corrected}, which changes one node; null where
         * it adds no phase (a fold). */
        static Proposal of(List<FluidThermodynamics.State> point,List<FluidThermodynamics.State> corrected) {
            for(int i=0;i<point.size();i++) {
                if(corrected.get(i)==point.get(i))continue;
                int added=FluidThermodynamics.phaseBits(corrected.get(i))&~FluidThermodynamics.phaseBits(point.get(i));
                return added==0?null:new Proposal(i,added,corrected.get(i),point);
            }
            return null;
        }
    }
    /** Every node's frozen per-component phase support for one pass, from that pass's seeds and never from a Newton iterate,
     * with the components this solve has already reactivated kept in both phases. A fixed node owns no unknowns. */
    private byte[][] supports(PassiveNetwork graph,List<FluidThermodynamics.State> seeds,boolean[][] reachable,boolean[][] promoted) {
        var supports=new byte[seeds.size()][];
        for(int node=0;node<supports.length;node++)
            if(!graph.reservoirs().get(node).fixed()&&!dry(graph.reservoirs().get(node),seeds.get(node)))
                supports[node]=PhaseLayout.support(model,seeds.get(node),reachable[node],promoted[node]);
        return supports;
    }
    /**
     * Components whose omitted phase the converged point no longer supports omitting, marked for the next pass; returns
     * how many. The test runs on the Newton solution of the reduced system, where both phases' fugacity coefficients are
     * converged and {@code ln phi_i} of the omitted phase is filled at infinite dilution. Every node is swept before any
     * pass is repeated, so one reactivation pass restores everything the state asks for.
     */
    private int reactivate(Equations equations,List<FluidThermodynamics.State> states,boolean[][] promoted) {
        if(!model.traceTruncation().enabled())return 0;
        int components=model.componentCount(),restored=0;
        for(int node=0;node<states.size();node++) {
            var layout=equations.layout[node];
            if(layout==null||layout.singlePhaseComponentCount()==0)continue;
            boolean[] flags=promoted[node]==null?new boolean[components]:promoted[node];
            int added=layout.reactivate(states.get(node),model.traceTruncation(),flags);
            if(added>0){promoted[node]=flags;restored+=added;}
        }
        if(restored>0)SolverDiagnostics.count(SolverDiagnostics.traceReactivations,restored);
        return restored;
    }
    /** The support arrays as one flat byte array for the workspace and cycle keys. */
    private byte[] supportCodes(byte[][] supports) {
        int components=model.componentCount();
        byte[] codes=new byte[supports.length*components];
        for(int node=0;node<supports.length;node++) {
            if(supports[node]==null){Arrays.fill(codes,node*components,(node+1)*components,(byte)-1);continue;}
            System.arraycopy(supports[node],0,codes,node*components,components);
        }
        return codes;
    }
    /** This graph's connections as the part of themselves the retained solver state may key on. */
    private static List<PassiveNetwork.Pipe.Identity> identities(PassiveNetwork graph) {
        var identities=new ArrayList<PassiveNetwork.Pipe.Identity>(graph.pipes().size());
        for(var pipe:graph.pipes())identities.add(pipe.identity());
        return List.copyOf(identities);
    }
    private static long[] nodeIds(PassiveNetwork graph) {
        long[] ids=new long[graph.reservoirs().size()];
        for(int i=0;i<ids.length;i++)ids[i]=graph.reservoirs().get(i).id();
        return ids;
    }
    /** Components any vessel or scheduled injection can supply; a Newton pass carries unknowns for
     * all of them, so an absent component can still arrive through a pipe. Depends on the graph
     * only, so it is evaluated once per solve rather than once per active-set pass. */
    private boolean[] componentMask(PassiveNetwork graph) {
        boolean[] mask=new boolean[model.componentCount()];
        for(var node:graph.reservoirs())if(node.kind()!=PassiveNetwork.NodeKind.VOID){var amounts=node.inventory().moles();for(int c=0;c<mask.length;c++)mask[c]|=amounts[c]>0;}
        for(var transfer:graph.scheduledTransfers())if(transfer instanceof ScheduledTransfer.Injection input){var amounts=input.molesPerSecond();for(int c=0;c<mask.length;c++)mask[c]|=amounts[c]>0;}
        // A tower's fill takes up the air's own gases and water (W3B_NETWORK_PLAN.md section 20).
        for(var node:graph.reservoirs())if(node.inventory().tower()!=null)for(int c:TowerTransfer.airComponents(model))mask[c]=true;
        return mask;
    }
    /** Physical species can traverse passive pipes in either direction, actuators only downstream.
     * Junction property guesses own no inventory and therefore cannot introduce a species. {@code closed} names the connections this pass has already pinned to a flow of exactly zero;
     * they carry no species in either direction. {@code null} is no closure at all. */
    private boolean[][] reachableComponents(PassiveNetwork graph,boolean[] closed) {
        int count=model.hydrocarbon.componentCount()+1,nodes=graph.reservoirs().size();
        var possible=new BitSet[nodes];var outgoing=new ArrayList<List<Integer>>(nodes);
        for(int i=0;i<nodes;i++) {
            possible[i]=new BitSet(count);outgoing.add(new ArrayList<>());
            var node=graph.reservoirs().get(i);
            if(node.kind()!=PassiveNetwork.NodeKind.VOID) {
                var amounts=node.inventory().moles();for(int c=0;c<count;c++)if(amounts[c]>0)possible[i].set(c);
            }
        }
        for(var transfer:graph.scheduledTransfers())if(transfer instanceof ScheduledTransfer.Injection injection) {
            var amounts=injection.molesPerSecond();for(int c=0;c<count;c++)if(amounts[c]>0)possible[injection.node()].set(c);
        }
        // A tower's fill takes up the air's own gases and water (W3B_NETWORK_PLAN.md section 20).
        for(int i=0;i<nodes;i++)if(graph.reservoirs().get(i).inventory().tower()!=null)for(int c:TowerTransfer.airComponents(model))possible[i].set(c);
        // Owner decision D61: where a junction melts crystals, a crystal can bring its component.
        if(!rateOnly&&JunctionCrystals.of(graph,model)!=null)for(int i=0;i<nodes;i++)
            for(var p:graph.reservoirs().get(i).inventory().solids().crystals())possible[i].set(model.crystalSolids().of(p.material()).component());
        for(int edge=0;edge<graph.pipes().size();edge++) {var pipe=graph.pipes().get(edge);
            if(closed!=null&&closed[edge])continue;
            if(!(pipe.control() instanceof FlowControl.Mover pump)||pump.targetVolumeFlow()>0) {
                if(boundaryAllowed(graph,pipe,1))outgoing.get(pipe.first()).add(pipe.second());
                if(pipe.control() instanceof FlowControl.Passive&&boundaryAllowed(graph,pipe,-1))outgoing.get(pipe.second()).add(pipe.first());
            }
        }
        var queue=new ArrayDeque<Integer>();var queued=new boolean[nodes];
        for(int i=0;i<nodes;i++){queue.add(i);queued[i]=true;}
        while(!queue.isEmpty()) {
            int from=queue.remove();queued[from]=false;
            for(int to:outgoing.get(from)) {
                int before=possible[to].cardinality();possible[to].or(possible[from]);
                if(before!=possible[to].cardinality()&&!queued[to]){queued[to]=true;queue.add(to);}
            }
        }
        var result=new boolean[nodes][count];
        for(int i=0;i<nodes;i++) {
            // A hydraulically isolated junction retains only its arbitrary property guess.
            if(possible[i].isEmpty()&&graph.reservoirs().get(i).junction()) {
                var amounts=PhaseLayout.totalAmounts(graph.reservoirs().get(i).state());for(int c=0;c<count;c++)if(amounts[c]>0)possible[i].set(c);
            }
            for(int c=possible[i].nextSetBit(0);c>=0;c=possible[i].nextSetBit(c+1))result[i][c]=true;
        }
        return result;
    }
    /**
     * A trial point for one pass, per node. A vessel is seeded with its own state, its committed layout (owner decision
     * D40 A): a reachable component it lacks ({@link #reachableComponents}) enters as an entry trace of {@code 1e-12} of
     * its amount, restated in the phases it holds ({@link ConservativeTransport#repartition}) and placed by the layout's
     * own split; a phase what is about to arrive carries and the layout lacks enters with what arrives of it
     * ({@link #arrivingInVessel}). The seed was the TP flash of the inventory with up to a quarter of its mass of what
     * arrives: for a pool of pure methane at saturation fed CO2 that put 99 % of the moles in the vapour, 183 m3 in a 1 m3
     * tank. A junction is seeded with its own state, its owned holdup a small stock the backward-Euler rows move.
     *
     * <p>W3b deleted decision D10's water trace in a junction and D41's omitted arrival in a vessel: water is a component
     * of the reachable mask like any other, so a junction's layout carries it wherever it can arrive, and a hydrocarbon
     * trace in a water-full vessel is a component of its liquid, not a phase of its own. A junction's first pass admits
     * the phase that arrives ({@link #arrivingPhases}).
     */
    private List<FluidThermodynamics.State> initialPhaseSeeds(PassiveNetwork graph,double dt,Runnable checkpoint,boolean[][] reachable,
                                                              List<FlowControl.Mode> modes,boolean[] boundaryClosed) {
        int count=model.hydrocarbon.componentCount()+1,nodes=graph.reservoirs().size();
        // The flows every pass reads its statements off ({@link #startPoint}: the balance's or the carried point's).
        double[] flows=new double[graph.pipes().size()];startPoint(graph,modes,boundaryClosed,identities(graph),null,flows,new double[flows.length],new boolean[flows.length]);
        var seeds=new ArrayList<FluidThermodynamics.State>(nodes);
        for(int nodeIndex=0;nodeIndex<nodes;nodeIndex++) {
            var node=graph.reservoirs().get(nodeIndex);var available=reachable[nodeIndex];
            var state=node.state();
            // A vessel that holds no fluid keeps its own state, which lays it out dry (decision D54, see dry); what arrives
            // in it is laid out by arrivingPhases.
            if(node.fixed()||node.junction()||holdsNoFluidVessel(node)){seeds.add(state);continue;}
            // This is only a trial guess and a basis. The accumulation equations still start from the exact original inventory.
            var n=PhaseLayout.totalAmounts(state);double total=Arrays.stream(n).sum();boolean changed=false;
            for(int i=0;i<count;i++)if(available[i]&&n[i]==0){n[i]=total*1e-12;changed=true;}
            try{seeds.add(changed?arrivingInVessel(graph,nodeIndex,ConservativeTransport.repartition(model,state,n,node.inventory().rigid()),flows,dt):state);}
            catch(com.wormzjl.icheme.science.fluid.thermo.ThermoHold hold){throw hold.at(graph.reservoirs().get(nodeIndex).id());}
        }
        return arrivingPhases(graph,seeds,modes,boundaryClosed,dt,checkpoint);
    }
    /**
     * Owner decision D40 A at a vessel's step start: its committed layout {@code seed}, and a phase that what is about to
     * arrive carries and the layout lacks laid out with what arrives of it, in its own phase: per inflow the stream its
     * donor end draws ({@link #endStream}), at most a quarter of the vessel's mass over {@code dt}, at the vessel's
     * temperature and pressure: its gas into the vapour slot where the seed holds no gas, each of its liquids into a free
     * liquid slot where the seed holds no liquid of its label. The phases the vessel holds keep their committed amounts.
     * The seed itself where nothing arrives in a phase it lacks, or where its state refuses that phase (LNG let into a warm
     * gas boils). W3b deleted the steam the gas took beside arriving free water (Dalton at the free water's vapour
     * pressure): water is a component of the gas, and the layout seeds an empty slot's share at the equilibrium ratio
     * ({@link PhaseLayout#encode}).
     */
    private FluidThermodynamics.State arrivingInVessel(PassiveNetwork graph,int nodeIndex,FluidThermodynamics.State seed,double[] flows,double dt) {
        double[][] slots={seed.vaporAmounts(),seed.liquidAmounts(),seed.aqueousAmounts()};int n=slots[0].length;boolean added=false;
        for(int edge=0;edge<flows.length;edge++) {
            var pipe=graph.pipes().get(edge);double flow=flows[edge];
            if(!(Math.abs(flow)>0)||(flow>=0?pipe.second():pipe.first())!=nodeIndex||!boundaryAllowed(graph,pipe,flow))continue;
            var stream=endStream(graph.reservoirs().get(flow>=0?pipe.first():pipe.second()).state(),pipe.drawPort(flow),pipe.volume());
            if(stream==null)continue;
            // The stream is per kilogram of fluid; the start flows are the law's, delivered at their saturation ({@link #transported}).
            double mass=Math.min(seed.mass()*.25,dt*Math.abs(transported(graph,pipe,flow)));
            for(int label:new int[]{FluidThermodynamics.GAS,FluidThermodynamics.OIL,FluidThermodynamics.AQUEOUS}) {
                int from=stream.slot(label);if(from<0||FluidThermodynamics.holdsPhase(seed,label))continue;
                int to=label==FluidThermodynamics.GAS?FluidThermodynamics.State.VAPOR:freeLiquidSlot(slots);
                if(to<0||label!=FluidThermodynamics.GAS&&seed.slotVolume(to)>0)continue;
                double[] arriving=stream.amountsView(from);
                for(int c=0;c<n;c++)if(arriving[c]>0){slots[to][c]+=mass*arriving[c];added=true;}
            }
        }
        if(!added)return seed;
        try{return model.adoptingState(seed.temperature(),seed.pressure(),slots[0],slots[1],slots[2],null).withSolidState(seed.solids(),seed.solidMoments());}
        catch(IllegalArgumentException refused){return seed;}
    }
    /** A liquid slot of {@code slots} (vapour, liquid, aqueous) that holds nothing, -1 where both hold some. */
    private static int freeLiquidSlot(double[][] slots) {
        for(int slot:new int[]{FluidThermodynamics.State.LIQUID,FluidThermodynamics.State.AQUEOUS})if(!(PhaseLayout.sum(slots[slot])>0))return slot;
        return -1;
    }
    /**
     * Group L (A300-A303): {@code seeds} with every junction laid out for the phases the step's inflow leaves in it. A
     * fitting full of air that water reaches is full of water a few milliseconds later, and a pass is laid out for its
     * seeds' phases ({@link PhaseLayout}), so a junction seeded as its held gas has no unknown for the water its mixing
     * rows must hold: the residual sits in those rows, the trial temperatures leave the data and the step halves to its
     * floor (an 11 g air buffer receiving about 7 kg/s of water). Each junction's mixture over the step is its stock plus
     * what its connections deliver at the pass's start flows ({@link #startPoint}) over {@code dt}, each the stream its
     * donor end draws ({@link #endMoles}), at the seed's pressure and the enthalpy of stock and deliveries, split as the
     * junction's own split is (D26, {@link FluidThermodynamics#junctionSplit}: its rows closed from the engine's PH answer
     * at the mass-weighted temperature, fluid only, a starting basis booking no crystal, the seed keeping the junction's
     * own solids; from the TP flash there where the engine answers nothing, as for an empty bag; formed only where the
     * engine's answer holds a phase the seed lacks, and nothing taken where the closure fails or empties that phase) and
     * folded as the pass folds a junction (D34, D41). The stock is what
     * the junction's own rows mix (D46): its buffer's amounts and energy field, which is in enthalpy form, its solids'
     * enthalpy left out. Its property state can be another mixture (the balance states a junction as what is delivered
     * to it, {@code BalanceNet.seeded}), and that state's specific enthalpy over the buffer's amounts made a subcooled LNG buffer (134.35 K, 656 kPa, 27 kJ/kg apart) a
     * two-phase mixture at 139.97 K, 45 % vapour by volume, and no step converged (cold fuzz seed 583, E5). A junction
     * nothing reaches at the start flows is its stock alone: an island come to rest at its gas source's pressure had its
     * junctions stated as the 257 K CO2-rich gas the balance delivers to them, while each held 1.1 to 3.5 kg of the cold
     * two-phase mixture it had, and the pass laid out as a gas stalled on their enthalpy rows (cold fuzz seed 729). With the crystals
     * competing, the cold quick check's seeds 77 and 151 held E5 and six more ran out of wall time. The enthalpy carries
     * the latent heat a temperature mix leaves out: liquid water at 326.648 K reaching a pump suction held at its vapour
     * pressure (14.63 kPa, its saturation at 326.606 K) is liquid beside 6.6e-6 of its mass as steam, where the TP flash
     * at the mixed temperature made all of it steam, and the suction limit's row then had no vapour pressure to stand at
     * (bundled fuzz seed 889, hold E9). Where the seed is not laid out for that mixture ({@link #laidOutFor}), the seed
     * becomes the mixture at the seed's mass; a junction donor delivers its own updated seed, so a phase reaches a run of
     * junctions in one stage. A seed laid out for its mixture is kept to the bit; a rate-only solve holds a junction's
     * composition at its stock and this does nothing there.
     *
     * <p>A vessel that holds no fluid (decision D54) is laid out from what its connections deliver over {@code dt}, beside
     * its solids: the delivered amounts at the bag's own split (decision D26, {@link #emptyBagSplit}), at the ambient while
     * the bag is slack and at the capacity once what arrives overfills it, folded as a vessel; where the engine answers
     * nothing the network carries, the TP flash of the delivered amounts at their mass-weighted temperature and the
     * ambient (an isothermal let-down: LNG at 139 K and 1 atm is a vapour of several bag volumes beside CO2 frost, and a
     * pass laid out without the liquid cooled the vapour to its 90.69 K floor). One nothing reaches stays dry.
     */
    private List<FluidThermodynamics.State> arrivingPhases(PassiveNetwork graph,List<FluidThermodynamics.State> seeds,List<FlowControl.Mode> modes,
                                                           boolean[] boundaryClosed,double dt,Runnable checkpoint) {
        if(rateOnly||graph.reservoirs().stream().noneMatch(node->node.junction()&&!node.fixed()||holdsNoFluidVessel(node)))return seeds;
        int edges=graph.pipes().size();double[] flows=new double[edges];
        startPoint(graph,modes,boundaryClosed,identities(graph),null,flows,new double[edges],new boolean[edges]);
        return arrivingPhases(graph,seeds,flows,null,false,modes,boundaryClosed,dt,checkpoint);
    }
    /** {@link #arrivingPhases} at the law flows {@code flows}; {@code vesselsOnly} leaves the junctions, for a converged pass
     * whose junctions are solved; {@code seeds} itself where nothing is laid out. A connection the pass drew phases on
     * ({@code draws}, {@link Equations#draws}) delivers its drawn stream ({@link #drawnStream}), what the booking books;
     * any other the stream of its end at a vanishing flow. */
    private List<FluidThermodynamics.State> arrivingPhases(PassiveNetwork graph,List<FluidThermodynamics.State> seeds,double[] flows,double[][] draws,boolean vesselsOnly,List<FlowControl.Mode> modes,
                                                           boolean[] boundaryClosed,double dt,Runnable checkpoint) {
        int nodes=graph.reservoirs().size();
        List<FluidThermodynamics.State> result=null;
        for(int round=0;round<=nodes;round++) {
            boolean changed=false;
            for(int j=0;j<nodes;j++) {
                var node=graph.reservoirs().get(j);boolean empty=holdsNoFluidVessel(node);if(!(node.junction()&&!node.fixed()&&!vesselsOnly||empty))continue;
                var current=result==null?seeds:result;var seed=current.get(j);
                if(empty&&!dry(node,seed))continue;
                double[] n=node.inventory().moles().clone();double heldMass=molarMass(n),mass=heldMass,weighted=heldMass*node.state().temperature(),heat=0;boolean arrives=false;
                for(int edge=0;edge<flows.length;edge++) {
                    var pipe=graph.pipes().get(edge);double flow=flows[edge];
                    if(!(Math.abs(flow)>0)||(flow>=0?pipe.second():pipe.first())!=j||!boundaryAllowed(graph,pipe,flow))continue;
                    int donor=flow>=0?pipe.first():pipe.second();var stream=current.get(donor);var port=pipe.drawPort(flow);
                    var rates=draws==null?null:draws[edge];var drawn=rates==null?null:drawnStream(rates,stream,graph.reservoirs().get(donor).state());
                    double rate=Math.abs(transported(graph,pipe,flow)),delivered=dt*rate,streamMass=drawn!=null?1:endMass(stream,port,pipe.volume());double[] moles=drawn!=null?drawn:endMoles(stream,port,pipe.volume());
                    for(int c=0;c<n.length;c++)n[c]+=delivered*moles[c]/streamMass;
                    var through=delivered(graph,pipe,drawn!=null?drawn[n.length]:endSpecificEnthalpy(stream,port,pipe.volume()),stream.temperature(),Math.copySign(rate,flow),checkpoint);
                    mass+=delivered;weighted+=delivered*through[1];arrives=true;
                    heat+=delivered*through[0];
                }
                if(empty) {
                    if(!arrives||!(mass>0))continue;
                    FluidThermodynamics.State delivered;
                    try{delivered=model.folded(model.flashTP(weighted/mass,PhaseLayout.AMBIENT_PRESSURE,n,checkpoint),false);}
                    catch(com.wormzjl.icheme.science.fluid.thermo.ThermoHold outside){continue;}
                    // What arrives may itself be nothing at the bag's resolution: the vessel then stays dry.
                    if(PhaseLayout.bagVolume(delivered,node.inventory().wallCapacity())<=HOLDS_NOTHING*node.inventory().volume())continue;
                    var own=emptyBagSplit(node,weighted/mass,n,heat,checkpoint);
                    if(result==null)result=new ArrayList<>(seeds);
                    var arrived=own==null?delivered:own;var held=node.inventory().solids();
                    result.set(j,held.empty()?arrived:arrived.withSolids(held));changed=true;continue;
                }
                // Where nothing arrives the mixture is the stock alone, which the seed must hold as well.
                if(!(heldMass>0))continue;
                // The stock as the junction's rows mix it (D46): the buffer's amounts and its energy field (enthalpy form), fluid only.
                var buffer=node.inventory();var at=node.state();
                double stock=buffer.internalEnergy()-buffer.solids().moments().enthalpy(at.temperature(),at.pressure());
                double scale=seed.mass()/mass,enthalpy=(stock+heat)*scale;
                for(int c=0;c<n.length;c++)n[c]*=scale;
                FluidThermodynamics.State engine;
                try{var split=model.equilibriumPH(weighted/mass,seed.pressure(),enthalpy,n,checkpoint);engine=split!=null?split:model.flashTP(weighted/mass,seed.pressure(),n,checkpoint);}
                catch(com.wormzjl.icheme.science.fluid.thermo.ThermoHold outside){continue;}
                if(laidOutFor(phaseCode(model.folded(engine,true)),phaseCode(seed)))continue;
                // The engine's answer is the start of the junction's own split, not its verdict (D26): its rows decide the phase.
                var own=model.junctionSplit(n,enthalpy,molarMass(n),seed.pressure(),engine,checkpoint);
                if(own==null)continue;
                var mixture=model.folded(own,true);
                if(laidOutFor(phaseCode(mixture),phaseCode(seed)))continue;
                if(result==null)result=new ArrayList<>(seeds);
                result.set(j,mixture.withSolidState(seed.solids(),seed.solidMoments()));changed=true;
            }
            if(!changed)break;
        }
        return result==null?seeds:flashSeeds(graph,modes,boundaryClosed,seeds,result,checkpoint);
    }
    /**
     * Decisions D54 and D26: an empty bag's own split of what arrives over a step, the amounts {@code n} with the enthalpy
     * {@code h} they bring (J, the network's datum). The engine's PH answer at the ambient pressure holds the phases; the
     * vessel's closure from it ({@link InventoryEquilibrium#split}, the wall's work booked from a zero volume) is the
     * equilibrium of that enthalpy at the ambient while the bag is slack and of its energy at the capacity once what
     * arrives overfills it; the PH answer itself where the closure leaves nothing. {@code null} where the engine answers
     * nothing the network carries.
     */
    private FluidThermodynamics.State emptyBagSplit(PassiveNetwork.Reservoir node,double t,double[] n,double h,Runnable checkpoint) {
        try {
            var answer=model.equilibriumPH(t,PhaseLayout.AMBIENT_PRESSURE,h,n,checkpoint);
            if(answer==null)return null;
            var start=model.folded(answer,false);
            var own=InventoryEquilibrium.split(model,start,node.inventory().with(n.clone(),h,SolidInventory.EMPTY),node.inventory().emptyBag(),checkpoint);
            return own==null?start:model.folded(own,false);
        }
        catch(IllegalArgumentException refused){return null;}
    }
    /** Value identity of the fluid of two node-state lists (T, P, amounts, water): a rate solve's point written back
     * carries the junction's own solids, so mass, volume and enthalpy (the moments' share) are not compared. */
    private static boolean sameFluidStates(List<FluidThermodynamics.State> a,List<FluidThermodynamics.State> b) {
        if(a.size()!=b.size())return false;
        for(int i=0;i<a.size();i++) {
            var x=a.get(i);var y=b.get(i);if(x==y)continue;
            if(Double.doubleToLongBits(x.temperature())!=Double.doubleToLongBits(y.temperature())||Double.doubleToLongBits(x.pressure())!=Double.doubleToLongBits(y.pressure())
                    ||!sameSlots(x,y))return false;
        }
        return true;
    }
    /** Whether two states hold the same amounts in each slot, to the bit. */
    private static boolean sameSlots(FluidThermodynamics.State x,FluidThermodynamics.State y) {
        return Arrays.equals(x.vaporAmountsView(),y.vaporAmountsView())&&Arrays.equals(x.liquidAmountsView(),y.liquidAmountsView())
                &&Arrays.equals(x.aqueousAmountsView(),y.aqueousAmountsView());
    }
    /** {@code state} with its extensive quantities (amounts, volumes, mass, energies, solid moments) scaled by {@code f}: a
     * per-kilogram mixture restated at a junction's own scale. */
    static FluidThermodynamics.State scaled(FluidThermodynamics.State s,double f) {
        double[] v=s.vaporAmounts(),l=s.liquidAmounts(),a=s.aqueousAmounts();for(int c=0;c<l.length;c++){v[c]*=f;l[c]*=f;a[c]*=f;}
        var m=s.solidMoments();
        return new FluidThermodynamics.State(s.temperature(),s.pressure(),v,l,a,
                s.volume()*f,s.enthalpy()*f,s.internalEnergy()*f,s.mass()*f,s.vaporVolume()*f,s.liquidSlotVolume()*f,s.aqueousSlotVolume()*f,
                s.vaporPhase(),s.liquidPhase(),s.aqueousPhase(),s.solids(),new SolidInventory.Moments(m.mass()*f,m.volume()*f,m.heatCapacity()*f));
    }
    /** Value identity of two node-state lists (T, P, amounts, mass, volume, enthalpy). */
    private static boolean sameStates(List<FluidThermodynamics.State> a,List<FluidThermodynamics.State> b) {
        if(a.size()!=b.size())return false;
        for(int i=0;i<a.size();i++) {
            var x=a.get(i);var y=b.get(i);if(x==y)continue;
            if(Double.doubleToLongBits(x.temperature())!=Double.doubleToLongBits(y.temperature())||Double.doubleToLongBits(x.pressure())!=Double.doubleToLongBits(y.pressure())
                    ||Double.doubleToLongBits(x.mass())!=Double.doubleToLongBits(y.mass())||Double.doubleToLongBits(x.volume())!=Double.doubleToLongBits(y.volume())
                    ||Double.doubleToLongBits(x.enthalpy())!=Double.doubleToLongBits(y.enthalpy())
                    ||!sameSlots(x,y))return false;
        }
        return true;
    }
    /** The mass of a conserved amount vector, water last. */
    private double molarMass(double[] amounts) {
        double mass=0;for(int c=0;c<amounts.length;c++)mass+=amounts[c]*model.molecularWeight(c);
        return mass;
    }
    /** Each connection's own law on its ends' states ({@link #initialMassFlow}), one per connection. */
    private double[] lawFlows(PassiveNetwork graph) {
        double[] q=new double[graph.pipes().size()];
        for(int edge=0;edge<q.length;edge++)q[edge]=initialMassFlow(graph,graph.pipes().get(edge));
        return q;
    }
    /**
     * The connection flows and actuator heads a pass with this active set starts from, the whole of
     * {@link Equations#buildInitial}'s edge half; it lives here because {@link #closeIllegalStarts} reads it before the
     * equations exist. {@code seeds} may be {@code null} before this solve has any, which only a regulating valve reads.
     */
    private void startPoint(PassiveNetwork graph,List<FlowControl.Mode> modes,boolean[] boundaryClosed,
                            List<PassiveNetwork.Pipe.Identity> pipeIdentities,List<FluidThermodynamics.State> seeds,
                            double[] flows,double[] heads,boolean[] headSet) {
        boolean warmFlow=graph.reservoirs().stream().anyMatch(node->node.junction()||node.fixed())
                ||graph.pipes().stream().anyMatch(pipe->phaseCode(graph.reservoirs().get(pipe.first()).state())!=phaseCode(graph.reservoirs().get(pipe.second()).state()));
        // At a job start the previous point is the committed interval's endpoint; see {@link #replayStart}.
        boolean previousAvailable=!lawFlowsFromBalance&&carriedPoint(graph,pipeIdentities);lawFlowsFromBalance=false;
        // The balance's law flows ({@link #balanceStart}) where this point has no history to start from (a solve without a
        // carried point balances first); each connection's own law on its ends' states on an island the balance leaves alone.
        double[] estimate=warmFlow&&!previousAvailable?(balanceLawFlows!=null&&balanceLawFlows.length==graph.pipes().size()?balanceLawFlows:lawFlows(graph)):null;
        for(int i=0;i<graph.pipes().size();i++) {
            var pipe=graph.pipes().get(i);var a=graph.reservoirs().get(pipe.first());var b=graph.reservoirs().get(pipe.second());
            if(boundaryClosed[i]||modes.get(i)==FlowControl.Mode.CLOSED)continue;
            if(previousAvailable){
                // A flow the connection's boundary forbids (into a generator, out of a void, a blocked way) is no state to
                // start from, however small: the pass loop tolerates one below 1e-10 kg/s at a converged point, and carried
                // into the next step a Newton that converges without an iteration kept it there for good. It starts at rest.
                flows[i]=boundaryAllowed(graph,pipe,previousLawFlows[i])?previousLawFlows[i]:0;heads[i]=previousHeads[i]/1e5;headSet[i]=true;
                if(modes.get(i)==FlowControl.Mode.VALVE_REGULATING&&pipe.control() instanceof FlowControl.Valve valve&&valve.pressureControl()) {
                    double predictedDrop=a.state().pressure()-(seeds==null?a.state():seeds.get(pipe.first())).pressure();
                    if(predictedDrop>0)flows[i]*=Math.clamp((a.state().pressure()-valve.targetPressure())/predictedDrop,0,1);
                    double rho=endDensity(a.state(),pipe.firstPort(),pipe.volume());
                    heads[i]=(endPressure(a,pipe.firstPort(),a.state())-endPressure(b,pipe.secondPort(),b.state())
                            -columnDensity(a.state(),pipe.firstPort(),pipe.volume())*GRAVITY*(b.elevation()-a.elevation())
                            -pipe.pressureDrop(flows[i],rho,endViscosity(a.state(),pipe.firstPort(),pipe.volume())))/1e5;
                }
                continue;
            }
            if(!warmFlow)continue;
            // Decision D54: a dry end gives nothing, so an estimate out of one starts at rest (A190's rule for a carried flow).
            flows[i]=outOfDryEnd(graph,pipe,estimate[i])?0:estimate[i];
        }
        if(flashRestated!=null&&seeds!=null)restateFlashFlows(graph,modes,boundaryClosed,seeds,flows);
        if(!hasSolids(graph)&&graph.pipes().stream().noneMatch(pipe->pipe.filter()!=null))return;
        int nodes=graph.reservoirs().size();int[] degree=new int[nodes];
        for(var pipe:graph.pipes()){degree[pipe.first()]++;degree[pipe.second()]++;}
        int[][] nodeEdges=new int[nodes][];
        for(int i=0;i<nodes;i++)nodeEdges[i]=new int[degree[i]];
        Arrays.fill(degree,0);
        for(int edge=0;edge<graph.pipes().size();edge++) {
            var pipe=graph.pipes().get(edge);
            nodeEdges[pipe.first()][degree[pipe.first()]++]=edge;nodeEdges[pipe.second()][degree[pipe.second()]++]=edge;
        }
        boolean[] known=new boolean[graph.pipes().size()];
        for(int i=0;i<known.length;i++)known[i]=boundaryClosed[i]||modes.get(i)==FlowControl.Mode.CLOSED;
        for(int pass=0;pass<known.length;pass++) {
            boolean changed=false;
            for(int node=0;node<nodes;node++)if(graph.reservoirs().get(node).junction()) {
                int missing=-1;double net=0;boolean usable=true;
                for(int edge:nodeEdges[node]) {
                    if(!known[edge]){if(missing>=0){usable=false;break;}missing=edge;continue;}
                    var pipe=graph.pipes().get(edge);double q=flows[edge];boolean receiving=node==(q>=0?pipe.second():pipe.first());
                    double factor=receiving&&pipe.filter()!=null?1-solidShare(graph.reservoirs().get(q>=0?pipe.first():pipe.second()).state(),pipe.drawPort(q),pipe.volume()):1;
                    net+=receiving?Math.abs(q)*factor:-Math.abs(q);
                }
                if(!usable||missing<0)continue;
                var pipe=graph.pipes().get(missing);double q=pipe.first()==node?net:-net;
                if(node==(q>=0?pipe.second():pipe.first())&&pipe.filter()!=null){var donor=graph.reservoirs().get(q>=0?pipe.first():pipe.second()).state();q/=Math.max(1e-12,1-solidShare(donor,pipe.drawPort(q),pipe.volume()));}
                if(boundaryAllowed(graph,pipe,q)){flows[missing]=q;known[missing]=true;changed=true;}
            }
            if(!changed)break;
        }
    }
    /** The law's flow of one connection on its ends' states (the edge unknown's start, {@link #lawFlow}); a mover's its target. */
    private double initialMassFlow(PassiveNetwork graph,PassiveNetwork.Pipe pipe) {
        return lawFlow(graph,pipe,endPressure(graph,pipe,pipe.first()),endPressure(graph,pipe,pipe.second()));
    }
    /** The transported flow of connection {@code pipe} for the law's flow {@code lawFlow}: its saturation at the velocity
     * cap of the end it leaves, on that end's stream ({@link #massFlowLimit}). */
    private double transported(PassiveNetwork graph,PassiveNetwork.Pipe pipe,double lawFlow) {
        if(pipe.control() instanceof FlowControl.Mover)return lawFlow;
        var donor=graph.reservoirs().get(lawFlow>=0?pipe.first():pipe.second()).state();
        return saturated(lawFlow,massFlowLimit(pipe,donor,pipe.drawPort(lawFlow)));
    }
    /** The law's flow of one connection at the end pressures given: a mover's suction target, else the loss inverted on the
     * static drive in the stream of the end the drive leaves (as the row's loss reads its flow's donor), with no cap. */
    private double lawFlow(PassiveNetwork graph,PassiveNetwork.Pipe pipe,double firstPressure,double secondPressure) {
        var a=graph.reservoirs().get(pipe.first());var b=graph.reservoirs().get(pipe.second());
        if(pipe.control() instanceof FlowControl.Mover pump)
            return Math.min(suctionMassFlow(pump.targetVolumeFlow(),a.state(),pipe.firstPort(),pipe.volume()),massFlowLimit(pipe,a.state(),pipe.firstPort()));
        double driving=staticDrive(graph,pipe,firstPressure-secondPressure);
        var donor=(driving>=0?a:b).state();var donorPort=driving>=0?pipe.firstPort():pipe.secondPort();
        double rho=endDensity(donor,donorPort,pipe.volume()),mu=endViscosity(donor,donorPort,pipe.volume());
        double lo=0,hi=1;while(pipe.pressureDrop(hi,rho,mu)<Math.abs(driving)&&hi<1e6)hi*=2;
        for(int j=0;j<50;j++){double mid=(lo+hi)/2;if(pipe.pressureDrop(mid,rho,mu)>Math.abs(driving))hi=mid;else lo=mid;}
        return Math.copySign((lo+hi)/2,driving);
    }
    /**
     * The static driving pressure of one passive connection from end {@code a} to end {@code b}, given their end
     * pressures' {@code difference}, with the
     * column {@link #columnFromFirst} states and each end's {@link #columnDensity}. Its start sign, where both columns are
     * admissible, is the sign of {@code difference}. An end that may not donate has no column of its own ({@link #donates}).
     */
    private double staticDrive(PassiveNetwork graph,PassiveNetwork.Pipe pipe,double difference) {
        var a=graph.reservoirs().get(pipe.first());var b=graph.reservoirs().get(pipe.second());
        double first=columnDensity(a.state(),pipe.firstPort(),pipe.volume()),second=columnDensity(b.state(),pipe.secondPort(),pipe.volume()),dz=b.elevation()-a.elevation();
        if(!donates(graph,pipe,pipe.first()))first=second;else if(!donates(graph,pipe,pipe.second()))second=first;
        return difference-(columnFromFirst(first,second,difference,dz,difference>=0)?first:second)*GRAVITY*dz;
    }
    /**
     * Owner decision D59 (5): whether end {@code node} of {@code pipe} may fill the connection, that is, whether the
     * connection may carry flow out of that end ({@link #boundaryAllowed}). A void only receives, a generator's far end
     * never sends into it, a blocked way and a dry end (decision D54) send nothing: such an end's fluid never stands in
     * the connection, so the connection stands in what the other end sends. The one statement of that fact for every
     * static column (the row's {@link Equations#columnEnds}, {@link #staticDrive} and the balance's {@link BalanceNet#flow});
     * before, a void line whose junction stood below the ambient was given the void's air column and closed for good.
     */
    static boolean donates(PassiveNetwork graph,PassiveNetwork.Pipe pipe,int node) {
        return boundaryAllowed(graph,pipe,node==pipe.first()?1:-1);
    }
    /**
     * The one column rule of a vertical connection: whether its static head is stated in the first end's column
     * ({@code first}, the density of what that end's port stands in the connection) rather than the second's, given the
     * end pressures' {@code difference} (actuator included) and {@code dz = z_second - z_first}. The flow row states it
     * once per pass ({@link Equations#columnEnds}) and the start estimate on the same question ({@link #staticDrive}).
     * <ul>
     * <li>Both columns drive one way: the column of the end that drive leaves (the only admissible one).</li>
     * <li>The two drives point towards each other (neither column admissible, where a settled line lives): the column
     * nearest its own rest.</li>
     * <li>Both admissible (the pipe can stand full of either fluid): the start's direction {@code forwardStart}, the
     * history of which fluid is in the pipe.</li>
     * </ul>
     */
    static boolean columnFromFirst(double first,double second,double difference,double dz,boolean forwardStart) {
        double drivingFirst=difference-first*GRAVITY*dz,drivingSecond=difference-second*GRAVITY*dz;
        if(drivingFirst>=0&&drivingSecond>=0)return true;
        if(drivingFirst<=0&&drivingSecond<=0)return false;
        if(drivingFirst<0)return Math.abs(drivingFirst)<=Math.abs(drivingSecond);
        return forwardStart;
    }
    /**
     * A mover's pressure-rise limit ({@link FlowControl.Mover#riseLimit}); a compressor's is {@code (r_max - 1) P_s} on its
     * suction pressure. A pump's is the setting, the rise for water at 298.15 K and 1 atm
     * ({@link FluidThermodynamics#pumpReferenceDensity()}), scaled by the suction's density over water's: a head, the one
     * quantity a real pump fixes. On nitrogen at 1 atm the limit is about 1/870 of the setting, so a pump moving gas
     * between closed vessels reaches its limit and its shutoff within a second instead of evacuating the suction vessel.
     * The density is {@link #riseDensity}'s: a set-flow pump's supply, constant over the solve (owner decision D11, A2), any
     * other mover's trial suction stream ({@link #endDensity}: the bulk at a BULK end, the drawn phase's at a phase port),
     * so every mode test reads the density its law row reads; the start balance reads the suction stream. It is the law's
     * rise at no flow: a set-flow mover's relief rise, a machine's shutoff rise (a centrifugal pump's times its gas share,
     * {@link #readSuctions}).
     */
    private double riseLimit(PassiveNetwork graph,int edge,double suctionDensity,double suctionPressure) {
        return mover(graph,edge).riseLimit(suctionDensity,suctionPressure,model.pumpReferenceDensity(),kappa(graph,graph.pipes().get(edge)));
    }
    /** How far the closed mover on connection {@code edge} out-rises, at no flow, what its ends ask on the step's start
     * states: its rise limit ({@link #riseDensity}) less the discharge end's pressure over the suction end's and the static
     * column between them (Pa). */
    private double shutoffMargin(PassiveNetwork graph,int edge) {
        var pipe=graph.pipes().get(edge);var a=graph.reservoirs().get(pipe.first());var b=graph.reservoirs().get(pipe.second());
        double suction=endDensity(a.state(),pipe.firstPort(),pipe.volume());
        return riseLimit(graph,edge,riseDensity(edge,suction),a.state().pressure())-(endPressure(b,pipe.secondPort(),b.state())-endPressure(a,pipe.firstPort(),a.state())
                +suction*GRAVITY*(b.elevation()-a.elevation()));
    }
    /**
     * The density the rise limit of the mover on connection {@code edge} reads ({@link #riseLimit}). A set-flow pump's is its
     * supply's, the stream its supply walk ends at ({@link InletPhase#density} on this solve's input states; owner decision D11,
     * A2): one value for the whole solve, so its law row and every mode test read one relief rise, and a closed pump reopens
     * exactly where its law runs (read on the suction stream for the reopening alone, a dead-headed pump behind a junction
     * 1e-5 lighter than its supply reopened 5 Pa early at 5 bar and cycled). A pump whose suction line still holds the gas it
     * was built with pushes it out at its relief rise on the liquid behind it, as a positive-displacement pump primes
     * itself. Every other mover reads its suction stream {@code suction}: a compressor's ratio limit reads no density, and a
     * centrifugal machine does not prime. The start balance reads every mover on its suction stream ({@link #balanceStart}):
     * there a pump whose suction flashes yields as the tabled density falls, so a starved pump starts where it cavitates (an
     * LNG pump fed through a 1 cm line, balanced on its supply's relief, started liquid at its bubble point and every step
     * size stalled).
     */
    private double riseDensity(int edge,double suction) {
        if(!(supplyGraph.pipes().get(edge).control() instanceof FlowControl.Pump pump))return suction;
        if(Double.isNaN(supplyDensities[edge])){double d=InletPhase.density(model,supplyGraph,edge,pump.targetVolumeFlow(),supplyDuration);supplyDensities[edge]=d>0&&d<Double.POSITIVE_INFINITY?d:-1;}
        return supplyDensities[edge]>0?supplyDensities[edge]:suction;
    }
    /** What each connection's series passes at most on its set-flow movers' laws ({@link ControlConstraints#moverBounds}):
     * a mover's set flow at its suction stream's density on {@code graph}'s states (kg/s). */
    private double[] moverBounds(PassiveNetwork graph) {
        return ControlConstraints.moverBounds(graph,e->{var pipe=graph.pipes().get(e);
            return ((FlowControl.Mover)pipe.control()).targetVolumeFlow()*endDensity(graph.reservoirs().get(pipe.first()).state(),pipe.firstPort(),pipe.volume());});
    }
    /** The rise (Pa) of the mover on connection {@code edge} at suction volume flow {@code flow} on its own law
     * ({@code MOVER_LAW}), as this solve reads it ({@link #mover}). */
    private double lawRise(PassiveNetwork graph,int edge,double flow,double suctionDensity,double suctionPressure) {
        return mover(graph,edge).lawRise(flow,suctionDensity,suctionPressure,model.pumpReferenceDensity(),kappa(graph,graph.pipes().get(edge)));
    }
    private double lawRiseSlope(PassiveNetwork graph,int edge,double flow,double suctionDensity,double suctionPressure) {
        return mover(graph,edge).lawRiseSlope(flow,suctionDensity,suctionPressure,model.pumpReferenceDensity(),kappa(graph,graph.pipes().get(edge)));
    }
    private double lawResidual(PassiveNetwork graph,int edge,double flow,double head,double suctionDensity,double suctionPressure) {
        return mover(graph,edge).lawResidual(flow,head,suctionDensity,suctionPressure,model.pumpReferenceDensity(),kappa(graph,graph.pipes().get(edge)));
    }
    /** The power (W) the mover on connection {@code edge} gives what it moves ({@link FlowControl.Mover#power}): a centrifugal
     * compressor's over its wet efficiency's share of its dry one ({@link #readSuctions}), the one function the Newton's
     * energy rows and the transport's booking read ({@link ConservativeTransport.MoverWork}). A steam turbine's is negative,
     * its shaft power out of the stream ({@link FlowControl.Turbine#shaftPower}) from its inlet's density and pressure to
     * {@code dischargePressure}, its exhaust node's pressure, on {@link #kappa}; a mover's reads no discharge pressure. */
    double moverPower(PassiveNetwork graph,int edge,double massFlow,double head,double suctionDensity,double suctionPressure,double dischargePressure) {
        var pipe=graph.pipes().get(edge);
        if(pipe.control() instanceof FlowControl.Turbine turbine)return -turbine.shaftPower(massFlow,suctionDensity,suctionPressure,dischargePressure,kappa(graph,pipe));
        double power=mover(graph,edge).power(massFlow,head,suctionDensity,suctionPressure,kappa(graph,graph.pipes().get(edge)));
        return read(graph,edge)?power/wetShares[edge]:power;
    }
    /** Whether this solve's suction readings ({@link #readSuctions}) hold connection {@code edge} of {@code graph}: they were
     * read for the control it carries. */
    private boolean read(PassiveNetwork graph,int edge) {
        return edge<readControls.length&&readControls[edge]!=null&&readControls[edge]==graph.pipes().get(edge).control();
    }
    /** The mover on connection {@code edge} as its law runs this solve ({@link #readSuctions}): a centrifugal pump with its
     * head scaled by its gas share; else the connection's own control. */
    private FlowControl.Mover mover(PassiveNetwork graph,int edge) {
        return read(graph,edge)?suctionMovers[edge]:(FlowControl.Mover)graph.pipes().get(edge).control();
    }
    /**
     * Owner decision D20 Q11 A: how far above the floor of the liquid it draws the suction of liquid pump connection
     * {@code edge} stands where its head breaks down at suction volume flow {@code volumeFlow} (m3/s),
     * {@code 0.85 ρ_l g NPSH3(Q, s)} ({@link com.wormzjl.icheme.science.machine.Npsh#breakdownPressure}) on this solve's NPSH
     * design and liquid density; zero where it has none (a gas mover, a stopped pump, a supply holding no liquid).
     */
    private double breakdown(PassiveNetwork graph,int edge,double volumeFlow) {
        if(!read(graph,edge)||npshDesigns[edge]==null||!(liquidDensities[edge]>0))return 0;
        return npshDesigns[edge].breakdownPressure(volumeFlow,liquidDensities[edge]);
    }
    /**
     * Request 11 (owner decisions D19 and D20, plan section 3.6): what each mover of {@code graph} reads of its suction for
     * the whole solve, on the step's start states and the slice {@code duration} (s; zero for a rate solve and the mint),
     * the supply its suction line delivers ({@link InletPhase#supply}), as the floor ({@link #suctionFloors}) and the vapour
     * lock ({@link #vapourLocks}) are read: numbers fixed for the solve, so every row and mode test reads one law. A
     * centrifugal pump's head is scaled by its gas share ({@link com.wormzjl.icheme.science.machine.GasEntrainment#headShare}
     * of the supply's vapour volume share at the suction node's pressure); its rise keeps the mixture density. A centrifugal
     * compressor's power is over its wet efficiency ({@link com.wormzjl.icheme.science.machine.CentrifugalCompressor#wetEfficiency}
     * of the supply's condensed mass share): its curve and ratio read the suction mixture already. A set-flow mover needs
     * neither: its law counts the suction mixture's volume, so it delivers {@code (1 − α)} of its displacement as liquid, or
     * its displacement less the liquid's volume as gas. Every liquid mover keeps its NPSH design ({@link FlowControl.Mover#npsh})
     * and the density of the liquid it draws ({@link InletPhase#liquidDensity}) for its floor ({@link #breakdown}).
     */
    private void readSuctions(PassiveNetwork graph,double duration) {
        int edges=graph.pipes().size();
        readControls=new FlowControl[edges];suctionMovers=new FlowControl.Mover[edges];npshDesigns=new com.wormzjl.icheme.science.machine.Npsh[edges];
        liquidDensities=new double[edges];wetShares=new double[edges];vapourShares=new double[edges];drawnStreams=new InletPhase.Drawn[edges];
        for(int edge=0;edge<edges;edge++) {
            var pipe=graph.pipes().get(edge);
            if(!(pipe.control() instanceof FlowControl.Mover mover))continue;
            readControls[edge]=mover;suctionMovers[edge]=mover;wetShares[edge]=1;
            if(mover.targetVolumeFlow()==0)continue;
            // E reads the liquid at the suction now (no slice); the head share reads the slice's stream, its later gas included.
            // Pulled for a whole slice to the edge of a stream that mixed the last water with the gas after it, a bottom-port
            // drain stood on its limit with the water still flowing (InletPhaseTest, fixture 5: 22 rejected steps).
            if(mover.liquid())drawnStreams[edge]=InletPhase.drawn(model,graph,edge,mover.targetVolumeFlow(),0);
            if(mover instanceof FlowControl.Machine machine) {
                var supply=mover.liquid()&&!(duration>0)?drawnStreams[edge].supply():InletPhase.supply(model,graph,edge,mover.targetVolumeFlow(),duration);
                if(machine.liquid())vapourShares[edge]=supply.vapourVolumeShare();
                if(machine.liquid())suctionMovers[edge]=machine.withHeadShare(com.wormzjl.icheme.science.machine.GasEntrainment.headShare(supply.vapourVolumeShare(),graph.reservoirs().get(pipe.first()).state().pressure()));
                // A vacuum pump (batch 2026-11-10-vacuum-pump) counts the suction mixture's volume as a set-flow mover does; a dry
                // screw keeps its vapour share for its wear and liquid noise (owner V6 B).
                else if(machine.law() instanceof com.wormzjl.icheme.science.machine.VacuumPump){if(com.wormzjl.icheme.science.machine.VacuumPump.dryScrew(machine.law()))vapourShares[edge]=supply.vapourVolumeShare();}
                else wetShares[edge]=com.wormzjl.icheme.science.machine.CentrifugalCompressor.wetEfficiency(1,supply.condensedMassShare());
            }
            // W10d: a set-flow pump's suction vapour share, for its page's gas reading only (its law counts the mixture already).
            else if(mover.liquid())vapourShares[edge]=(duration>0?InletPhase.supply(model,graph,edge,mover.targetVolumeFlow(),duration):drawnStreams[edge].supply()).vapourVolumeShare();
            if(mover.liquid()){npshDesigns[edge]=mover.npsh();liquidDensities[edge]=InletPhase.liquidDensity(model,graph,edge,mover.targetVolumeFlow(),duration);}
        }
    }
    private final FluidThermodynamics.State[] kappaStates=new FluidThermodynamics.State[16];
    private final double[] kappaValues=new double[16];
    /**
     * The heat capacity ratio of the gas the mover on {@code pipe} draws, on its suction node's state at the step's start
     * ({@link FluidThermodynamics#heatCapacityRatio}; batch 2026-11-03-rotating-machinery, plan section 3.2): one value
     * for the whole step, so every row, every mode test and the transport's booking read the same polytropic exponent.
     * A liquid mover does not use it. A steam turbine reads its inlet's the same way (plan section 3.4). Cached by the
     * state's identity: a state is immutable.
     */
    private double kappa(PassiveNetwork graph,PassiveNetwork.Pipe pipe) {
        if(pipe.control() instanceof FlowControl.Mover mover&&mover.liquid())return 1.4;
        var state=graph.reservoirs().get(pipe.first()).state();int slot=System.identityHashCode(state)&15;
        if(kappaStates[slot]!=state){kappaValues[slot]=model.heatCapacityRatio(state);kappaStates[slot]=state;}
        return kappaValues[slot];
    }
    /** The velocity cap's mass flow of what leaves {@code donor} through {@code port} at a vanishing flow (the stream
     * {@link #endDensity} names): its density, area and velocity limit, multiplied in the order the bulk form
     * {@code mass/volume*area*velocityLimit} always was, so a BULK end is that form to the bit. The cap a pass imposes on
     * a priority mixture is {@link Equations#capLimit}. */
    private double massFlowLimit(PassiveNetwork.Pipe pipe,FluidThermodynamics.State donor,PassiveNetwork.PhasePort port) {
        return endDensity(donor,port,pipe.volume())*pipe.minimumArea()*endVelocityLimit(donor,port);
    }
    /**
     * The velocity cap as part of every line's law (M7 and M8 option C, 2026-10-18; it replaces the cap active set of
     * decision A270): a choked line passes at most the cap flow {@code q_cap} of the end it leaves, and the flow its law
     * gives at a driving pressure, {@code q_law}, is reached only far under it,
     * {@code q = q_law (1 + (q_law/q_cap)^8)^(-1/8)}, odd in {@code q_law}: 8.3 % under the cap where the drive equals the
     * cap's own pressure drop, within 0.06 % of it at twice that drive and bitwise the law at a tenth of it. The edge
     * unknown is the law's flow {@code q_law}, unbounded, so the hydraulic row stays the plain law
     * {@code driving = loss(q_law)}; what the connection carries, {@code q}, is read from it wherever a flow is booked
     * ({@link Equations#flow}: the node rows, the actuator rows, the step's transfers). A flow deep in saturation moves
     * nothing by its own drive, and a junction between choked lines balances by its pressure, as it does in a pipe. One
     * law for every mode that throttles ({@link #saturates}: passive, a mover on its law or its suction limit, an open
     * valve); a regulating valve states its own flow or pressure and is not saturated.
     */
    static final double CAP_EXPONENT=8;
    /** The transported flow of the law's flow {@code qLaw} under the cap {@code qCap}; {@code qLaw} itself without a cap. */
    static double saturated(double qLaw,double qCap) {
        if(!(qCap>0)||!(qCap<Double.POSITIVE_INFINITY))return qLaw;
        double r=Math.abs(qLaw)/qCap,r2=r*r,r4=r2*r2,r8=r4*r4;
        return qLaw*DeterministicMath.pow(1+r8,-1/CAP_EXPONENT);
    }
    /** {@code dq / dq_law} of {@link #saturated} at {@code qLaw}: {@code (1 + (q_law/q_cap)^8)^(-9/8)}, 1 without a cap. */
    static double saturationSlope(double qLaw,double qCap) {
        if(!(qCap>0)||!(qCap<Double.POSITIVE_INFINITY))return 1;
        double r=Math.abs(qLaw)/qCap,r2=r*r,r4=r2*r2,r8=r4*r4;
        return DeterministicMath.pow(1+r8,-(CAP_EXPONENT+1)/CAP_EXPONENT);
    }
    /**
     * The driving pressure of one end of a connection: the node's pressure plus the static offset of the port at that
     * end ({@link #portHead}: the level head at a vessel's LIQUID port, zero elsewhere). Every static driving pressure the
     * solver states (the hydraulic row, the pass's column choice, the start-of-solve closures, the start flows, the valve
     * start head, a mover's carry and shutoff margin ({@link #demand}) and the reopen test)
     * reads an end's pressure through here and nowhere else, because they must agree: a closure decided on one pressure
     * and reopened on another bottles a line for good. The offset is a pressure, so it applies in both directions.
     */
    private List<PipeObservation> observe(Equations e,List<FluidThermodynamics.State> states,double[] flows,double[] lawFlows,double[] heads,List<FlowControl.Mode> modes,Runnable checkpoint) {
        var samples=new ArrayList<PipeObservation>();
        for(int edge=0;edge<e.graph.pipes().size();edge++) {
            var pipe=e.graph.pipes().get(edge);int a=pipe.first(),b=pipe.second(),donor=flows[edge]>=0?a:b;
            var carried=e.drawn(edge,flows[edge]>=0?0:1,e.cachedTransport[donor],flows[edge]);
            var suction=e.drawn(edge,0,e.cachedTransport[a],flows[edge]);
            double column=pipe.control() instanceof FlowControl.Mover?suction.density:e.columnEnds[edge]==0?columnDensity(states.get(a),pipe.firstPort(),pipe.volume()):columnDensity(states.get(b),pipe.secondPort(),pipe.volume());
            double head=pipe.control() instanceof FlowControl.Mover?heads[edge]:-heads[edge];
            var lengths=new ArrayList<Double>();var losses=new ArrayList<Double>();
            for(var section:pipe.sections()){lengths.add(section.length());losses.add(PipeResistance.pressureDrop(section,lawFlows[edge],carried.density,carried.viscosity));}
            var observed=new PipeObservation(pipe.id(),endPressure(e.graph.reservoirs().get(a),pipe.firstPort(),states.get(a))+head,endPressure(e.graph.reservoirs().get(b),pipe.secondPort(),states.get(b)),e.graph.reservoirs().get(a).elevation(),e.graph.reservoirs().get(b).elevation(),column,states.get(donor).temperature(),Math.abs(flows[edge])>1e-10,lengths,losses,flows[edge],e.drawn(edge,flows[edge]>=0?0:1,e.cachedTransport[donor],flows[edge]).density);
            if(!pipe.observationPoints().isEmpty()&&!rateOnly){
                var meters=new ArrayList<FlowSample>();
                for(var point:pipe.observationPoints()){
                    boolean inlet=pipe.control() instanceof FlowControl.Valve&&point.fraction()==0;
                    double pressure=inlet?states.get(a).pressure():observed.pressure(point.fraction(),point.elevation(),point.reversed());
                    double q=flows[edge]*(point.reversed()?-1:1);Double volume=null;
                    if(q==0)volume=0.0;
                    else if(inlet)volume=q/carried.density;
                    else try{
                        checkpoint.run();var local=model.flashTP(states.get(donor).temperature(),pressure,carried.moles,checkpoint);
                        volume=q/carried.mass*(local.volume()+carried.solids.volume());
                    }catch(IllegalArgumentException unavailable){ /* The measurement is unavailable; the accepted process remains valid. */ }
                    meters.add(new FlowSample(point.host(),pressure,q,volume));
                }
                observed=observed.withFlowSamples(meters);
            }
            if(pipe.control() instanceof FlowControl.Mover mover&&mover.liquid()&&mover.targetVolumeFlow()>0&&read(e.graph,edge))
                observed=observed.withSuction(npshRatio(e,edge,states,flows[edge]),dry(edge,mover,modes.get(edge),flows[edge],states.get(a).pressure()),gasRatio(edge,mover,states.get(a).pressure()),
                        npshRequired(edge,flows[edge]),vapourShares[edge]);
            // A running dry screw's suction vapour share (owner V6 B): its wear and liquid noise read its liquid share.
            else if(pipe.control() instanceof FlowControl.Machine screw&&com.wormzjl.icheme.science.machine.VacuumPump.dryScrew(screw.law())&&screw.targetVolumeFlow()>0&&read(e.graph,edge))
                observed=observed.withSuction(Double.NaN,false,Double.NaN,Double.NaN,vapourShares[edge]);
            if(pipe.control() instanceof FlowControl.PressureValve||pipe.control() instanceof FlowControl.ControlValve) {
                // The valve stands at the line's first end: its node side is that end's pressure, its line side the row's
                // first pressure, which includes the valve's drop (the control head).
                double node=endPressure(e.graph.reservoirs().get(a),pipe.firstPort(),states.get(a)),line=observed.firstPressure();
                var read=Math.abs(flows[edge])<=1e-10?PipeObservation.Cavitation.NONE:flows[edge]>=0?cavitation(states.get(donor),node,line,checkpoint):cavitation(states.get(donor),line,node,checkpoint);
                observed=observed.withValve(Math.abs(node-line),read);
            }
            samples.add(observed);
        }
        return List.copyOf(samples);
    }
    /** A control valve's incipient cavitation pressure ratio x_Fz (IEC 60534-8-4): it cavitates from ΔP = x_Fz (P1 − Pv). */
    static final double VALVE_INCIPIENT=.3;
    /**
     * A flowing valve's cavitation (owner 2026-11-10, option A: the liquid valve's loop by σ = (P1 − Pv) / (P1 − P2), Pv the
     * vapour pressure of the liquid it passes), from its donor node's state {@code s}, its inlet pressure {@code p1} and its
     * outlet pressure {@code p2}: FLASHING where p2 lies below Pv (the vapour stays), CAVITATING where σ ≤ 1 / x_Fz
     * ({@link #VALVE_INCIPIENT}: the vena contracta falls below Pv and the bubbles collapse downstream), else PLAIN. Water
     * reads its saturation pressure at the donor's temperature. A hydrocarbon liquid is throttled at its own enthalpy, as
     * {@link #releasePressure} reads it, but tested at two pressures instead of bisected: it flashes if it holds vapour at
     * p2, and it cavitates if it holds vapour at p1 − (p1 − p2) / x_Fz, where σ = 1 / x_Fz (any Pv cavitates where that
     * pressure is below the box's floor); a liquid beside vapour stands at its bubble point, its node's pressure. The worse
     * of the two liquids counts. NONE with no liquid or where the thermodynamics refuses. Observational only.
     */
    private PipeObservation.Cavitation cavitation(FluidThermodynamics.State s,double p1,double p2,Runnable checkpoint) {
        var none=PipeObservation.Cavitation.NONE;
        if(!(p1>p2)||!Double.isFinite(p1+p2))return FluidThermodynamics.holdsPhase(s,FluidThermodynamics.OIL)||FluidThermodynamics.holdsPhase(s,FluidThermodynamics.AQUEOUS)?PipeObservation.Cavitation.PLAIN:none;
        double incipient=p1-(p1-p2)/VALVE_INCIPIENT;var worst=none;
        if(FluidThermodynamics.holdsLiquid(s)) {
            PipeObservation.Cavitation oil;
            if(FluidThermodynamics.holdsPhase(s,FluidThermodynamics.GAS))oil=regime(s.pressure(),p2,incipient);
            else {
                double[] x=liquidMoles(s);
                double h=liquidEnthalpy(s);int out=liquidPH(s,x,h,Math.max(p2,NumericalBox.MINIMUM_PRESSURE),checkpoint);
                if(out==PH_VAPOUR)oil=PipeObservation.Cavitation.FLASHING;
                else if(out!=PH_LIQUID)oil=none;
                else if(incipient<=NumericalBox.MINIMUM_PRESSURE)oil=PipeObservation.Cavitation.CAVITATING;
                else oil=liquidPH(s,x,h,incipient,checkpoint)==PH_VAPOUR?PipeObservation.Cavitation.CAVITATING:PipeObservation.Cavitation.PLAIN;
            }
            if(oil.ordinal()>worst.ordinal())worst=oil;
        }
        return worst;
    }
    /** The regime of a liquid of vapour pressure {@code pv} throttled to {@code p2}, cavitating from {@code incipient} (Pa). */
    static PipeObservation.Cavitation regime(double pv,double p2,double incipient) {
        if(!Double.isFinite(pv))return PipeObservation.Cavitation.NONE;
        return pv>p2?PipeObservation.Cavitation.FLASHING:pv>=incipient?PipeObservation.Cavitation.CAVITATING:PipeObservation.Cavitation.PLAIN;
    }
    /**
     * W10b (owner decision D34): a running liquid pump's σ = NPSHA / NPSH3 at the accepted point, which its wear reads
     * ({@link com.wormzjl.icheme.science.machine.Wear}): its suction end's pressure over the floor of the liquid it draws
     * ({@link #vapourPressure}), as a head of that liquid ({@code liquidDensities}), over this solve's NPSH3 at its suction
     * flow ({@link com.wormzjl.icheme.science.machine.Npsh#required}). NaN where it has no NPSH design (a set-flow
     * constructed pump), no floor or no liquid. Observational: never fed back into the solve.
     */
    private double npshRatio(Equations e,int edge,List<FluidThermodynamics.State> states,double massFlow) {
        double rho=liquidDensities[edge],required=npshRequired(edge,massFlow);
        if(!(required>0))return Double.NaN;
        var pipe=e.graph.pipes().get(edge);int a=pipe.first();double floor;
        try{floor=vapourPressure(e,edge,states.get(a));}catch(IllegalArgumentException outsideData){return Double.NaN;}
        if(!(floor<Double.POSITIVE_INFINITY))return Double.NaN;
        return (endPressure(e.graph.reservoirs().get(a),pipe.firstPort(),states.get(a))-floor)/(rho*GRAVITY)/required;
    }
    /** W10d: a running liquid pump's NPSH3 (m) at the accepted mass flow, on this solve's NPSH design and liquid density; NaN where it has none. */
    private double npshRequired(int edge,double massFlow) {
        var design=npshDesigns[edge];double rho=liquidDensities[edge];
        return design==null||!(rho>0)?Double.NaN:design.required(Math.max(0,massFlow)/rho);
    }
    /**
     * W10c (owner decision D35): a running centrifugal pump's suction vapour share in Turpin onsets, {@code r = α/α_T} at its
     * suction node's pressure ({@link com.wormzjl.icheme.science.machine.GasEntrainment#onset}), the share this solve read
     * ({@link #readSuctions}); NaN for a set-flow pump, which has no head factor, or no suction pressure. Observational.
     */
    private double gasRatio(int edge,FlowControl.Mover mover,double suctionPressure) {
        if(!(mover instanceof FlowControl.Machine)||!(suctionPressure>0))return Double.NaN;
        return vapourShares[edge]/com.wormzjl.icheme.science.machine.GasEntrainment.onset(suctionPressure);
    }
    /**
     * W10b (owner decision D34): whether a liquid pump whose driver is on runs dry at the accepted point: vapour-locked
     * ({@link #vapourLocks}), refused for a dry suction ({@code INLET_WRONG_PHASE}, decision D58), or gas-locked (a
     * centrifugal pump passing nothing with its suction's vapour share past the onset, {@link com.wormzjl.icheme.science.machine.GasEntrainment#onset}).
     */
    private boolean dry(int edge,FlowControl.Mover mover,FlowControl.Mode mode,double massFlow,double suctionPressure) {
        if(vapourLocked(edge)||mode==FlowControl.Mode.INLET_WRONG_PHASE)return true;
        return mover instanceof FlowControl.Machine&&Math.abs(massFlow)<=1e-10&&suctionPressure>0&&vapourShares[edge]>com.wormzjl.icheme.science.machine.GasEntrainment.onset(suctionPressure);
    }
    double endPressure(PassiveNetwork.Reservoir node,PassiveNetwork.PhasePort port,FluidThermodynamics.State state) {
        if(spray(node,port))return sprayPressure(node,model.waterMolecularWeight/model.waterLiquid(state.temperature(),CoolingTower.PRESSURE).molarVolume());
        return state.pressure()+portHead(node,port,state);
    }
    /** A spray inlet discharges to the atmosphere at its nozzle; a tower's own end is no other kind of port. */
    private static boolean spray(PassiveNetwork.Reservoir node,PassiveNetwork.PhasePort port){return port==PassiveNetwork.PhasePort.SPRAY&&node.inventory().tower()!=null;}
    private static double sprayPressure(PassiveNetwork.Reservoir node,double column){return CoolingTower.PRESSURE+column*GRAVITY*node.inventory().tower().distributorHeight();}
    /** {@link #endPressure} with a spray end at the incoming column's density, so a basin temperature cannot create an inlet head. */
    private double columnEndPressure(PassiveNetwork.Reservoir node,PassiveNetwork.PhasePort port,FluidThermodynamics.State state,double column) {
        return spray(node,port)?sprayPressure(node,column):endPressure(node,port,state);
    }
    /** {@link #endPressure} of the end of {@code pipe} at {@code node}, on that node's own state in {@code graph}. */
    double endPressure(PassiveNetwork graph,PassiveNetwork.Pipe pipe,int node) {
        var reservoir=graph.reservoirs().get(node);
        return endPressure(reservoir,pipe.portAt(node),reservoir.state());
    }
    /**
     * The height a one-block tank's level head is stated over (decision D9, option B): one metre, its own height at the
     * default 1 m3 volume; a prismatic tank of this height over the footprint {@code V/H}. A drum states its own height in
     * its inventory ({@link PassiveNetwork.Inventory#levelHeight}; batch 2026-10-15-custom-equipment-rendering): its
     * apex-to-apex length standing, its diameter lying.
     */
    public static final double LEVEL_HEAD_HEIGHT=1;
    /**
     * The static head of a port over its node's pressure: the level head of a bottom outlet (decision D9, option B), zero
     * everywhere else. At a LIQUID port of a vessel (RESERVOIR, or its rate-solve copy PORT) the node's pressure is the
     * headspace's, and the bottom stands below the weight of the condensed phases over the footprint {@code A = V/H}:
     * {@code h = g * m_c * H / V}, {@code m_c} the condensed mass of {@code state} ({@link FluidThermodynamics#liquidMass}),
     * {@code H} the vessel's level height and {@code V} its inventory volume (both its identity,
     * {@link PassiveNetwork.Inventory}, not the trial state's EOS volume). Linear in the decoded condensed
     * amounts, evaluated by every caller on the state it is stating a pressure for (so the block Jacobian carries its
     * derivative with no new structural entry); a pressure, so inflow through a bottom port works against it; not in the
     * port's capacities nor the step controller. A VAPOR or BULK port, any other node kind and a state with no condensed
     * mass get exactly {@code 0.0}, so gas-only and all-BULK islands are the doubles they were. An OVERFLOW port
     * ({@link OpenVessel}) states the LIQUID port's head: its nozzle stands at the fill height, and the line below it full of
     * the same liquid adds back the head the nozzle stands above the bottom.
     */
    double portHead(PassiveNetwork.Reservoir node,PassiveNetwork.PhasePort port,FluidThermodynamics.State state){return portHead(model,node,port,state);}
    /** {@link #portHead} for a caller outside a solve (the network reduction stage): the same arithmetic on {@code model}. */
    public static double portHead(FluidThermodynamics model,PassiveNetwork.Reservoir node,PassiveNetwork.PhasePort port,FluidThermodynamics.State state) {
        if(port!=PassiveNetwork.PhasePort.LIQUID&&port!=PassiveNetwork.PhasePort.OVERFLOW&&port!=PassiveNetwork.PhasePort.BOOT_WATER&&port!=PassiveNetwork.PhasePort.BOOT_HC||node.kind()!=PassiveNetwork.NodeKind.RESERVOIR&&node.kind()!=PassiveNetwork.NodeKind.PORT)return 0;
        double condensed=model.liquidMass(state);
        if(condensed<=0)return 0;
        var geometry=node.inventory().geometry();
        if(geometry.form()==VesselGeometry.Form.PRISM)return GRAVITY*condensed*node.inventory().levelHeight()/node.inventory().volume();
        int lower=model.heavierLiquid(state),upper=lower==FluidThermodynamics.AQUEOUS?FluidThermodynamics.OIL:FluidThermodynamics.AQUEOUS;
        // Each layer weighs its liquid and the solids it carries (its volume share, as phaseVolume counts them), as the
        // prismatic law's liquidMass does; a vessel holding solids and no liquid stands them as one bed.
        var solids=state.solidMoments();double lowerVolume=FluidThermodynamics.phaseVolume(state,lower),upperVolume=FluidThermodynamics.phaseVolume(state,upper);
        if(lowerVolume+upperVolume==0)return geometry.head(solids.volume(),solids.mass(),0,0);
        return geometry.head(lowerVolume,model.phaseMass(state,lower)+FluidThermodynamics.phaseSolidShare(state,lower)*solids.mass(),
                upperVolume,model.phaseMass(state,upper)+FluidThermodynamics.phaseSolidShare(state,upper)*solids.mass());
    }
    /**
     * The density of what leaves a node through {@code port} at a vanishing flow: the bulk {@code mass/volume} at a BULK
     * end, and at a phase port the stream of the first phase of its priority order the state holds
     * ({@link PhaseDraw#leading}, decision D11); the bulk's when the state holds none of the three. The draw reads an
     * end's density here (a start flow's loss and cap, a mover's suction, rise limit and column); what stands in the
     * connection is {@link #columnDensity}. {@code volume} is the connection's (m3). It is read only at a LIQUID end
     * holding both liquids, whose stream is {@link PhaseDraw#mixedShares} of it; every end function takes it for that.
     */
    private double endDensity(FluidThermodynamics.State s,PassiveNetwork.PhasePort port,double volume){return endDensity(model,s,port,volume);}
    /** {@link #endDensity} for a caller outside a solve (the network reduction stage): the same arithmetic on {@code model}. */
    static double endDensity(FluidThermodynamics model,FluidThermodynamics.State s,PassiveNetwork.PhasePort port,double volume) {
        if(PhaseDraw.mixedLiquids(s,port)){var shares=PhaseDraw.mixedShares(s,volume);
            return shares[FluidThermodynamics.OIL]*phaseDensity(model,s,FluidThermodynamics.OIL)+shares[FluidThermodynamics.AQUEOUS]*phaseDensity(model,s,FluidThermodynamics.AQUEOUS);}
        int phase=PhaseDraw.leading(model,s,port);
        return phase<0?s.mass()/s.volume():phaseDensity(model,s,phase);
    }
    /** The density of one phase stream of {@code s}: the vapour's ({@link FluidThermodynamics#vaporDensity}) or a liquid's
     * with its solid share ({@link FluidThermodynamics#phaseMass} over {@link FluidThermodynamics#phaseVolume}). */
    static double phaseDensity(FluidThermodynamics model,FluidThermodynamics.State s,int phase) {
        return phase==FluidThermodynamics.GAS?model.vaporDensity(s):model.phaseMass(s,phase)/FluidThermodynamics.phaseVolume(s,phase);
    }
    /**
     * Owner decision D34: the density of the static column a node's end {@code port} stands in a connection of its own
     * {@code volume} (m3; a contracted run's summed): the connection filled with the port's phases in priority order by
     * volume ({@link PhaseDraw#fill}), each up to what the state holds of it, the last phase it holds taking the rest.
     * Every static column reads an end's density here (the hydraulic row and the pass's column choice, the start drives,
     * the sign tests and the rest gauge's drives); a mover's column is its suction stream instead (decision D6). A phase
     * that fills the connection alone gives its own density to the bit; a BULK end, and a state holding none of the
     * port's phases, the bulk's; in between the column is continuous in the phase volumes. Read from the leading phase
     * instead, 1.83e-8 m3 of condensate (880 kg/m3) at a bottom port over a 2 m drop of 2 L line stood as a 17.3 kPa
     * column no step could drain; as the line's gas plus the condensate's share it is 44.5 Pa. Read on the trial state,
     * so the Jacobian differentiates it.
     */
    private double columnDensity(FluidThermodynamics.State s,PassiveNetwork.PhasePort port,double volume){return columnDensity(model,s,port,volume);}
    /** {@link #columnDensity} for a caller outside a solve (the network reduction stage): the same arithmetic on {@code model}. */
    static double columnDensity(FluidThermodynamics model,FluidThermodynamics.State s,PassiveNetwork.PhasePort port,double volume) {
        if(port==PassiveNetwork.PhasePort.BULK)return endDensity(model,s,port,volume);
        if(PhaseDraw.mixedLiquids(s,port)){var filled=PhaseDraw.fillMixed(s,volume,1);double mass=0;for(int phase=0;phase<3;phase++)if(filled[phase]>0)mass+=phaseDensity(model,s,phase)*filled[phase];return mass/volume;}
        int leading=PhaseDraw.leading(model,s,port);
        if(leading<0)return s.mass()/s.volume();
        if(!(FluidThermodynamics.phaseVolume(s,leading)<volume))return phaseDensity(model,s,leading);
        int[] order=PhaseDraw.order(model,s,port);var filled=PhaseDraw.fill(s,order,volume,1);
        double mass=0;
        for(int phase:order) {
            if(!(filled[phase]>0))continue;
            if(filled[phase]==volume)return phaseDensity(model,s,phase);
            mass+=phaseDensity(model,s,phase)*filled[phase];
        }
        return mass/volume;
    }
    /** {@link #columnDensity} of a contracted run's end: the run {@code chain}'s links' volumes summed. */
    private double runColumnDensity(PassiveNetwork graph,List<Integer> chain,FluidThermodynamics.State s,PassiveNetwork.PhasePort port) {
        double volume=0;for(int link:chain)volume+=graph.pipes().get(link).volume();
        return columnDensity(s,port,volume);
    }
    /** The velocity limit of what leaves a node through {@code port} at a vanishing flow ({@link #endDensity}'s stream);
     * the bulk's at a BULK end and when the state holds none of the phases. */
    private double endVelocityLimit(FluidThermodynamics.State s,PassiveNetwork.PhasePort port) {
        if(PhaseDraw.mixedLiquids(s,port))return Math.min(model.phaseVelocityLimit(s,FluidThermodynamics.OIL),model.phaseVelocityLimit(s,FluidThermodynamics.AQUEOUS));
        int phase=PhaseDraw.leading(model,s,port);
        return phase<0?model.velocityLimit(s):model.phaseVelocityLimit(s,phase);
    }
    /** The viscosity of what leaves a node through {@code port} at a vanishing flow; the bulk's at a BULK end and when the
     * state holds none of the phases. */
    private double endViscosity(FluidThermodynamics.State s,PassiveNetwork.PhasePort port,double volume) {
        if(PhaseDraw.mixedLiquids(s,port))return mixedViscosity(model,s,volume,viscosities);
        int phase=PhaseDraw.leading(model,s,port);
        return phase<0?viscosity(s):model.phaseViscosity(s,phase,viscosities,null);
    }
    /** The viscosity of a {@link PhaseDraw#mixedLiquids} end's stream: its liquids' own, weighted by their
     * {@link PhaseDraw#mixedShares} of a connection of {@code volume}. */
    static double mixedViscosity(FluidThermodynamics model,FluidThermodynamics.State s,double volume,com.wormzjl.icheme.science.fluid.transport.MixtureViscosity.Workspace scratch) {
        var shares=PhaseDraw.mixedShares(s,volume);
        return shares[FluidThermodynamics.OIL]*model.phaseViscosity(s,FluidThermodynamics.OIL,scratch,null)+shares[FluidThermodynamics.AQUEOUS]*model.phaseViscosity(s,FluidThermodynamics.AQUEOUS,scratch,null);
    }
    /** The solid mass share of what leaves a node through {@code port} at a vanishing flow: none in the gas, a liquid's share
     * of the solids over its stream's mass, the bulk share at a BULK end and when the state holds none of the phases. A
     * LIQUID end holding both liquids carries each one's solids in its {@link PhaseDraw#mixedShares} of the stream. */
    private double solidShare(FluidThermodynamics.State s,PassiveNetwork.PhasePort port,double volume) {
        if(PhaseDraw.mixedLiquids(s,port)){
            var shares=PhaseDraw.mixedShares(s,volume);double solids=0,fluid=0;
            for(int phase:new int[]{FluidThermodynamics.OIL,FluidThermodynamics.AQUEOUS}){double held=FluidThermodynamics.phaseVolume(s,phase);
                solids+=shares[phase]*FluidThermodynamics.phaseSolidShare(s,phase)*s.solidMoments().mass()/held;fluid+=shares[phase]*model.phaseMass(s,phase)/held;}
            return solids/fluid;
        }
        int phase=PhaseDraw.leading(model,s,port);
        if(phase<0)return s.solidMoments().mass()/s.mass();
        return phase==FluidThermodynamics.GAS?0:FluidThermodynamics.phaseSolidShare(s,phase)*s.solidMoments().mass()/model.phaseMass(s,phase);
    }
    /** The mass flow of a suction volume flow of what leaves through {@code port}, {@code Q m / V}, multiplied and divided
     * in the order the bulk start flows always were ({@code Q*mass/volume}, not {@code Q*(mass/volume)}). */
    private double suctionMassFlow(double volumeFlow,FluidThermodynamics.State s,PassiveNetwork.PhasePort port,double volume) {
        if(PhaseDraw.mixedLiquids(s,port))return volumeFlow*endDensity(s,port,volume);
        int phase=PhaseDraw.leading(model,s,port);
        if(phase<0)return volumeFlow*s.mass()/s.volume();
        return volumeFlow*model.phaseMass(s,phase)/FluidThermodynamics.phaseVolume(s,phase);
    }
    /** The conserved amounts (water last) of what leaves through {@code port} at a vanishing flow; the node's total amounts
     * at a BULK end and when the state holds none of the phases. Over {@link #endMass} they are the moles per kilogram of
     * the fluid the connection carries: the solids a liquid or bulk end also draws are left behind, as {@link #endStream}
     * leaves them (LNG drawn with 1.3 % of dry ice at its stored energy flashed 1.3 % of its methane to vapour). */
    private double[] endMoles(FluidThermodynamics.State s,PassiveNetwork.PhasePort port,double volume) {
        if(PhaseDraw.mixedLiquids(s,port))return PhaseLayout.totalAmounts(mixedLiquidStream(s,volume));
        return fluidMoles(s,PhaseDraw.leading(model,s,port));
    }
    /** The fluid mass of {@link #endMoles}' stream, kg: the phase's (or the node's) mass without its solids; a dry end, which
     * draws no fluid ({@link #endStream} is null there), keeps its whole mass. */
    private double endMass(FluidThermodynamics.State s,PassiveNetwork.PhasePort port,double volume) {
        if(PhaseDraw.mixedLiquids(s,port))return mixedLiquidStream(s,volume).mass();
        return fluidMass(s,PhaseDraw.leading(model,s,port));
    }
    /** The fluid's specific enthalpy of {@link #endMoles}' stream, J/kg, its solids left behind (see {@link #endMoles}). */
    private double endSpecificEnthalpy(FluidThermodynamics.State s,PassiveNetwork.PhasePort port,double volume) {
        if(PhaseDraw.mixedLiquids(s,port)){var mixed=mixedLiquidStream(s,volume);return mixed.enthalpy()/mixed.mass();}
        return fluidSpecificEnthalpy(s,PhaseDraw.leading(model,s,port));
    }
    /**
     * What a phase-port outflow the pass drew carries per kilogram of fluid ({@code rates}, the six entries of
     * {@link Equations#draws}): each drawn phase's {@link #fluidMoles} over its {@link #fluidMass}, and its
     * {@link #fluidSpecificEnthalpy}, weighted by its mass flow and read where the booking reads it, on {@code end} or, for a
     * pinned entry, on the step's start state {@code start}; the specific enthalpy (J/kg) is the last entry. A full tank
     * holding a 1.2e-22 m3 nitrogen bubble draws water through its top port, though the bubble leads the port at a vanishing
     * flow: a dry tank above it laid out from the leading phase held 77 g of overflowing water as 0.081 m3 of vapour, and
     * the next pass ran its temperature to the 10 K floor (bundled fuzz seed 301).
     */
    private double[] drawnStream(double[] rates,FluidThermodynamics.State end,FluidThermodynamics.State start) {
        double[] stream=null;double total=0;
        for(double rate:rates)if(rate>0)total+=rate;
        for(int k=0;k<rates.length;k++) {
            if(!(rates[k]>0))continue;
            var at=k<3?end:start;int phase=k%3;double weight=rates[k]/total,fluid=fluidMass(at,phase);double[] moles=fluidMoles(at,phase);
            if(stream==null)stream=new double[moles.length+1];
            for(int c=0;c<moles.length;c++)stream[c]+=weight*moles[c]/fluid;
            stream[moles.length]+=weight*fluidSpecificEnthalpy(at,phase);
        }
        return stream;
    }
    /** The conserved amounts of phase {@code phase} of {@code s} (water last); the node's total amounts for {@code -1}. */
    private static double[] fluidMoles(FluidThermodynamics.State s,int phase){return phase<0?PhaseLayout.totalAmounts(s):FluidThermodynamics.phaseMoles(s,phase);}
    /** The mass of phase {@code phase} of {@code s} (the node's for {@code -1}) without its solids, kg; its whole mass where
     * the solids would leave none. */
    private double fluidMass(FluidThermodynamics.State s,int phase) {
        double mass=phase<0?s.mass():model.phaseMass(s,phase),solids=s.solidMoments().mass();
        if(solids==0)return mass;
        double fluid=mass-solidShare(s,phase)*solids;
        return fluid>0?fluid:mass;
    }
    /** The specific enthalpy of phase {@code phase} of {@code s} (the node's for {@code -1}) without its solids, J/kg. */
    private double fluidSpecificEnthalpy(FluidThermodynamics.State s,int phase) {
        double whole=phase<0?s.enthalpy()/s.mass():model.phaseSpecificEnthalpy(s,phase,null),solids=s.solidMoments().mass();
        if(solids==0)return whole;
        double mass=phase<0?s.mass():model.phaseMass(s,phase),fluid=mass-solidShare(s,phase)*solids;
        if(!(fluid>0))return whole;
        return (whole*mass-solidShare(s,phase)*s.solidMoments().enthalpy(s.temperature(),s.pressure()))/fluid;
    }
    /**
     * The heat (W, into the stream) heat-exchanging connection {@code pipe} takes up when it carries {@code flow} drawn at
     * {@code specificEnthalpy} from {@code graph}'s end the flow leaves: its side's march ({@link HeatSide#heat}) on that
     * end's curve ({@link #sideCurve}). Zero for a plain connection, at no flow, and from an end that draws nothing. The
     * step's rows and reconstruction, the start balance and the junction seeds all read it (batch
     * 2026-10-27-heat-exchanger-design).
     */
    private double sideHeat(PassiveNetwork graph,PassiveNetwork.Pipe pipe,double specificEnthalpy,double flow,Runnable checkpoint) {
        if(pipe.heat()==null||flow==0)return 0;
        var curve=sideCurve(graph,pipe,flow>0?pipe.first():pipe.second(),checkpoint);
        return curve==null?0:pipe.heat().heat(curve,specificEnthalpy,flow);
    }
    /** What a stream drawn at {@code specificEnthalpy} (J/kg) and {@code temperature} arrives as through {@code pipe} at
     * {@code flow}: its specific enthalpy and temperature after the side ({@link #sideHeat}, the temperature off the same
     * curve); unchanged through a plain connection. A seed's reading: a junction a heater boils is seeded with its vapour. */
    private double[] delivered(PassiveNetwork graph,PassiveNetwork.Pipe pipe,double specificEnthalpy,double temperature,double flow,Runnable checkpoint) {
        var curve=pipe.heat()==null||flow==0?null:sideCurve(graph,pipe,flow>0?pipe.first():pipe.second(),checkpoint);
        if(curve==null)return new double[]{specificEnthalpy,temperature};
        double out=specificEnthalpy+pipe.heat().heat(curve,specificEnthalpy,flow)/Math.abs(flow);
        return new double[]{out,curve.temperatureAt(out)};
    }
    /**
     * The heat curve (plan section 5.1) of the stream {@code pipe} draws from {@code node} in {@code graph}: the amounts its
     * end's port draws at the node's state ({@link #endMoles}; a drum's liquid draw has its own bubble point, not the
     * drum's whole mixture's), at the node's pressure, over the node's temperature and the side's wall temperatures, 10 K
     * beyond each, inside the numerical box. Past that span the curve extends its end lines (AGENTS.md "Thermo validity").
     * Null where the end draws nothing. A curve kept for the same stream and span takes new films without a flash (an air
     * cooler's planned fans, W4).
     */
    private HeatCurve sideCurve(PassiveNetwork graph,PassiveNetwork.Pipe pipe,int node,Runnable checkpoint) {
        var start=graph.reservoirs().get(node).state();int end=node==pipe.first()?0:1;var port=pipe.portAt(node);double volume=pipe.volume();
        var kept=sideCurves.computeIfAbsent(pipe.id(),id->new SideCurve[2]);var old=kept[end];var law=pipe.heat();
        if(old!=null&&old.start()==start&&old.port()==port&&old.volume()==volume&&old.coldest()==law.coldest()&&old.hottest()==law.hottest()) {
            if(old.films().equals(law.films())||old.curve()==null)return old.curve();
            var curve=old.curve().withFilms(law.films());kept[end]=new SideCurve(old.coldest(),old.hottest(),law.films(),port,volume,start,curve);return curve;
        }
        double[] amounts=endMoles(start,port,volume);double total=0;for(double n:amounts)total+=n;
        HeatCurve curve=null;
        if(total>0&&endMass(start,port,volume)>0) {
            double low=Math.max(NumericalBox.MINIMUM_TEMPERATURE,Math.min(start.temperature(),law.coldest())-10);
            double high=Math.min(NumericalBox.MAXIMUM_TEMPERATURE,Math.max(start.temperature(),law.hottest())+10);
            curve=HeatCurve.sample(model,amounts,start.pressure(),low,high,law.films(),checkpoint);
        }
        kept[end]=new SideCurve(law.coldest(),law.hottest(),law.films(),port,volume,start,curve);
        return curve;
    }
    /**
     * {@code graph} with each local heat actuator planned for a step that starts with {@code flows} (kg/s per connection, in
     * {@code graph}'s order; null where none is known) on its donor's curve (batch 2026-10-27-heat-exchanger-design): a fired
     * coil's firing share ({@link FiredCoil#planned}, W6; it fires fully where its end draws nothing), a controlled air
     * cooler's louvers and fans ({@link AirCooled#planned}, W4), and the flue gas of a convection coil whose radiant coil is
     * in this island: the radiant coil's planned heat at the step's start ({@link ConvectionCoil#flueOf}). Each stays fixed
     * inside the step's Newton; the next step plans it again from where this one ended. An exchanger side's bypass needs its
     * partner and is planned per slice ({@link #couple}), as is a convection coil's flue gas from another island's radiant
     * coil. {@code graph} itself where nothing changes.
     */
    /** Whether {@code graph} has a heat actuator {@link #plan} sets from the step's start flows: a fired coil or a controlled air cooler. */
    static boolean plansHeat(PassiveNetwork graph) {
        for(var pipe:graph.pipes())if(pipe.heat() instanceof FiredCoil||pipe.heat() instanceof AirCooled cooled&&cooled.control()!=null)return true;
        return false;
    }
    public PassiveNetwork plan(PassiveNetwork graph,double[] flows,Runnable checkpoint) {
        List<PassiveNetwork.Pipe> pipes=null;
        Map<Long,Double> radiant=null;
        for(int e=0;e<graph.pipes().size();e++) {
            var pipe=graph.pipes().get(e);
            if(!(pipe.heat() instanceof FiredCoil)&&!(pipe.heat() instanceof AirCooled cooled&&cooled.control()!=null))continue;
            double flow=flows!=null&&flows.length==graph.pipes().size()?flows[e]:0;
            int donor=flow>=0?pipe.first():pipe.second();var curve=sideCurve(graph,pipe,donor,checkpoint);
            double inlet=curve==null?0:endSpecificEnthalpy(graph.reservoirs().get(donor).state(),pipe.portAt(donor),pipe.volume());
            HeatLaw planned=switch(pipe.heat()) {
                case FiredCoil coil->curve==null?coil.withFiring(1):coil.planned(curve,inlet,flow);
                case AirCooled cooled->curve==null?cooled:cooled.planned(curve,inlet,flow);
                default->pipe.heat();
            };
            if(planned instanceof FiredCoil){if(radiant==null)radiant=new HashMap<>();radiant.put(pipe.id(),curve==null?0:planned.heat(curve,inlet,flow));}
            if(planned.equals(pipe.heat()))continue;
            if(pipes==null)pipes=new ArrayList<>(graph.pipes());
            pipes.set(e,pipe.withHeat(planned));
        }
        if(radiant!=null)for(int e=0;e<graph.pipes().size();e++) {
            var pipe=pipes==null?graph.pipes().get(e):pipes.get(e);
            if(!(pipe.heat() instanceof ConvectionCoil coil)||!radiant.containsKey(coil.radiant()))continue;
            var planned=coil.withFlue(ConvectionCoil.flueOf(radiant.get(coil.radiant())));
            if(planned.equals(coil))continue;
            if(pipes==null)pipes=new ArrayList<>(graph.pipes());
            pipes.set(e,pipe.withHeat(planned));
        }
        return pipes==null?graph:new PassiveNetwork(graph.reservoirs(),pipes,graph.scheduledTransfers());
    }
    /**
     * {@code graph} with the flue gas of each convection coil whose radiant coil is in another island (the owner's test of
     * 2026-10-28, proposal F1): the radiant coil's heat as its island committed it as of the slice start, from the coil's
     * read ({@link ExchangerRelay.Read#heatRate}); none without a read. A radiant coil in this island plans it at each step
     * instead ({@link #plan}). {@code graph} itself where nothing changes.
     */
    private static PassiveNetwork flueGas(PassiveNetwork graph,Map<Long,ExchangerRelay.Read> reads) {
        List<PassiveNetwork.Pipe> pipes=null;
        for(int e=0;e<graph.pipes().size();e++) {
            var pipe=graph.pipes().get(e);if(!(pipe.heat() instanceof ConvectionCoil coil))continue;
            boolean local=false;for(var other:graph.pipes())if(other.id()==coil.radiant()&&other.heat() instanceof FiredCoil)local=true;
            if(local)continue;
            var read=reads.get(pipe.id());
            var planned=coil.withFlue(ConvectionCoil.flueOf(read==null?0:read.heatRate()));
            if(planned.equals(coil))continue;
            if(pipes==null)pipes=new ArrayList<>(graph.pipes());
            pipes.set(e,pipe.withHeat(planned));
        }
        return pipes==null?graph:new PassiveNetwork(graph.reservoirs(),pipes,graph.scheduledTransfers());
    }
    /**
     * {@code graph} with each coupled exchanger side planned for a slice of {@code seconds} s (batch
     * 2026-10-27-heat-exchanger-design, plan section 5.2, W3; {@link ExchangerRelay#plan}): its wall, give-back and sit-out,
     * from its read in {@code reads} (by connection id) and its own stream at the slice start, drawn at {@code flows} (kg/s
     * per connection in {@code graph}'s order; null where none is known, which plans with no flow). Both streams' curves
     * span both inlets, 10 K beyond, at each stream's own pressure. A side without a read, or whose end draws nothing,
     * gets a flat wall at its own temperature and moves no heat. {@code graph} itself where no side is coupled.
     *
     * <p>A side whose read names an operation (W8, plan section 7) works a real valve. A bypass valve: the side is planned on
     * its stream and the bypass's together, its controller's bypass share goes to the valve as a mass flow target, and the
     * side's own bypass stays shut (its give-back on the flow that crosses it). A feed valve: its target is the operation's
     * ratio times the other side's flow as read. A target within {@link #VALVE_DEADBAND} of the valve's stands, so a steady
     * plant keeps its graph.
     */
    public PassiveNetwork couple(PassiveNetwork graph,Map<Long,ExchangerRelay.Read> reads,double[] flows,double seconds,Runnable checkpoint) {
        graph=pools(graph,reads,seconds,checkpoint);
        graph=flueGas(graph,reads);
        List<PassiveNetwork.Pipe> pipes=null;
        boolean known=flows!=null&&flows.length==graph.pipes().size();
        for(int e=0;e<graph.pipes().size();e++) {
            var pipe=graph.pipes().get(e);if(!(pipe.heat() instanceof HeatSide law)||!law.coupled())continue;
            var side=law;
            double flow=known?flows[e]:0;
            int donor=flow>=0?pipe.first():pipe.second();var start=graph.reservoirs().get(donor).state();var port=pipe.portAt(donor);
            var read=reads.getOrDefault(pipe.id(),ExchangerRelay.Read.NONE);var partner=read.partner();
            var operation=read.operation();int valve=operation==null?-1:valvePipe(graph,operation.valve());
            boolean bypassed=valve>=0&&!operation.feed()&&flow>=0;
            if(bypassed) {
                // The side's stream as its controller sees it: what crosses it and what the bypass valve passes.
                double around=known?Math.max(0,flows[valve]):0;flow+=around;
                side=side.withInputs(side.wall(),flow>0?around/flow:0,side.giveBack(),side.active(),side.limit());
            }
            double[] flat=new double[side.cells()];Arrays.fill(flat,start.temperature());
            var planned=side.withInputs(flat,side.bypass(),0,1);
            double regulated=Double.NaN;
            if(endMass(start,port,pipe.volume())>0) {
                double own=endSpecificEnthalpy(start,port,pipe.volume()),low=start.temperature(),high=low;
                // A kettle's tube side reads its pool (W7): the vessel's contents, isothermal, no stream.
                boolean pool=partner!=null&&partner.pool();
                boolean drawn=partner!=null&&(pool||reading(partner).endMass(partner.donor(),partner.port(),partner.volume())>0);
                if(drawn){low=Math.min(low,partner.donor().temperature());high=Math.max(high,partner.donor().temperature());}
                // A column's heating side (W4) gives its heat down to its floor, through its condensation: the curve spans it.
                if(read.gives())low=Math.min(low,read.ceiling());
                low=Math.max(NumericalBox.MINIMUM_TEMPERATURE,low-10);high=Math.min(NumericalBox.MAXIMUM_TEMPERATURE,high+10);
                var curve=spanned(start,port,pipe.volume(),low,high,side.films(),checkpoint);
                var other=!drawn||pool?null:reading(partner).spanned(partner.donor(),partner.port(),partner.volume(),low,high,partner.law().films(),checkpoint);
                var stream=curve==null?null:new ExchangerRelay.Stream(curve,own,flow,side,true);
                if(stream!=null&&pool)planned=ExchangerRelay.plan(side,stream,partner.bath(),read);
                else if(stream!=null)planned=ExchangerRelay.plan(side,stream,
                        other==null?null:new ExchangerRelay.Stream(other,reading(partner).endSpecificEnthalpy(partner.donor(),partner.port(),partner.volume()),partner.massFlow(),partner.law(),false),read,seconds);
                // A column's water side (U2): the flow that carries the heat the column asks for, the water rising its range but
                // staying under the ceiling; water at or over the ceiling can take nothing, and its valve holds. A column's heating
                // side (W4, a reboiler's tubes) gives its heat down to the floor: the flow is the heat rate over h(inlet) − h(floor);
                // a stream at or under the floor can give nothing, and its valve holds.
                if(curve!=null&&valve>=0&&operation.column()&&read.ceiling()>0) {
                    if(read.gives()) {
                        double fall=own-curve.enthalpyAt(read.ceiling());
                        if(fall>0)regulated=-read.heatRate()/fall;
                    } else {
                        double outlet=Math.min(curve.temperatureAt(own)+operation.rise(),read.ceiling()),rise=curve.enthalpyAt(outlet)-own;
                        if(rise>0)regulated=read.heatRate()/rise;
                    }
                }
            }
            double target=Double.NaN;
            if(bypassed) {
                double share=planned.bypass();target=share*flow;
                planned=planned.withInputs(planned.wall(),0,share<.99?planned.giveBack()/(1-share):planned.giveBack(),planned.active(),planned.limit());
            } else if(valve>=0&&operation.column())target=regulated;
            else if(valve>=0&&operation.feed()&&partner!=null)target=operation.ratio()*Math.abs(partner.massFlow());
            if(!Double.isNaN(target)) {
                var driven=pipes==null?graph.pipes().get(valve):pipes.get(valve);var valveLaw=(FlowControl.ControlValve)driven.control();
                if(valveLaw.source()!=FlowControl.ValveSource.MASS_FLOW||Math.abs(target-valveLaw.massTarget())>VALVE_DEADBAND*Math.max(valveLaw.massTarget(),target)+1e-6) {
                    if(pipes==null)pipes=new ArrayList<>(graph.pipes());
                    pipes.set(valve,driven.withControl(new FlowControl.ControlValve(FlowControl.ValveSource.MASS_FLOW,valveLaw.targetPressure(),Math.max(0,target),valveLaw.volumeTarget(),valveLaw.sensor(),valveLaw.revision())));
                }
            }
            if(planned.equals(law))continue;
            if(pipes==null)pipes=new ArrayList<>(graph.pipes());
            pipes.set(e,pipe.withHeat(planned));
        }
        return pipes==null?graph:new PassiveNetwork(graph.reservoirs(),pipes,graph.scheduledTransfers());
    }
    /** A planned valve target within this share of the valve's stands (W8): a steady plant keeps its graph, so it certifies. */
    public static final double VALVE_DEADBAND=1e-3;
    /**
     * Batch 2026-10-28-column-operation (plan 4.2 and 4.3): {@code graph} with each running column's port planned for the
     * slice ({@link PortPlan}). An outlet's generator stands at its account's composition and the planned temperature, at
     * the port's pressure and its own volume, holding the account's solids in their ratio to the moles; each port's boundary valve regulates the mass flow planned for it, measured
     * by the column ({@link PortPlan#COLUMN_SENSOR}; a valve the column works is never bare). A generator already at the
     * planned state and a target within {@link #VALVE_DEADBAND} of the valve's stand, so a steady plant keeps its graph. A
     * valve that bounds two columns takes the lower plan.
     */
    public PassiveNetwork ports(PassiveNetwork graph,Map<Long,PortPlan> plans,Runnable checkpoint) {
        if(plans.isEmpty())return graph;
        List<PassiveNetwork.Reservoir> nodes=null;List<PassiveNetwork.Pipe> pipes=null;
        var lowest=new HashMap<Long,Double>();for(var plan:plans.values())lowest.merge(plan.valve(),plan.massTarget(),Math::min);
        for(var plan:plans.values()) {
            int node=-1;for(int i=0;i<graph.reservoirs().size();i++)if(graph.reservoirs().get(i).id()==plan.node()){node=i;break;}
            if(node<0)continue;
            var n=graph.reservoirs().get(node);
            if(plan.moles()!=null) {
                if(n.kind()!=PassiveNetwork.NodeKind.GENERATOR)throw new IllegalArgumentException("An outlet port is a generator");
                double t=plan.temperature(),p=n.state().pressure();var x=plan.moles();
                if(!(t==n.state().temperature()&&sameShares(PhaseLayout.totalAmounts(n.state()),x)&&plan.solids().empty()&&n.state().solids().empty())) {
                    var unit=model.flashTP(t,p,x,checkpoint);double factor=n.inventory().volume()/unit.volume();
                    for(int c=0;c<x.length;c++)x[c]*=factor;
                    var state=model.flashTP(t,p,x,checkpoint);
                    if(!plan.solids().empty())state=state.withSolids(plan.solids().scale(factor));
                    if(nodes==null)nodes=new ArrayList<>(graph.reservoirs());
                    nodes.set(node,new PassiveNetwork.Reservoir(n.id(),n.elevation(),state,n.kind(),new PassiveNetwork.Inventory(n.inventory().volume(),PhaseLayout.totalAmounts(state),state.internalEnergy(),state.solids())));
                }
            }
            int valve=-1;
            for(int e=0;e<graph.pipes().size();e++){var pipe=graph.pipes().get(e);if(pipe.control() instanceof FlowControl.Valve&&graph.reservoirs().get(pipe.first()).id()==plan.valve()){valve=e;break;}}
            if(valve<0)continue;
            var driven=pipes==null?graph.pipes().get(valve):pipes.get(valve);var law=(FlowControl.Valve)driven.control();
            double target=lowest.get(plan.valve());
            if(law instanceof FlowControl.ControlValve c&&c.source()==FlowControl.ValveSource.MASS_FLOW&&PortPlan.COLUMN_SENSOR.equals(c.sensor())
                    &&(target>0||c.massTarget()==0)&&Math.abs(target-c.massTarget())<=VALVE_DEADBAND*Math.max(c.massTarget(),target)+1e-6)continue;
            var volume=law instanceof FlowControl.ControlValve c?c.volumeTarget():.01;long revision=law instanceof FlowControl.ControlValve c?c.revision():0;
            if(pipes==null)pipes=new ArrayList<>(graph.pipes());
            pipes.set(valve,driven.withControl(new FlowControl.ControlValve(FlowControl.ValveSource.MASS_FLOW,law.targetPressure(),target,volume,PortPlan.COLUMN_SENSOR,revision)));
        }
        return nodes==null&&pipes==null?graph:new PassiveNetwork(nodes==null?graph.reservoirs():nodes,pipes==null?graph.pipes():pipes,graph.scheduledTransfers());
    }
    /** Whether {@code a} and {@code b} hold the same shares of their totals, to 1e-12. */
    private static boolean sameShares(double[] a,double[] b) {
        double ta=0,tb=0;for(double v:a)ta+=v;for(double v:b)tb+=v;
        if(!(ta>0)||!(tb>0)||a.length!=b.length)return false;
        for(int c=0;c<a.length;c++)if(Math.abs(a[c]/ta-b[c]/tb)>1e-12)return false;
        return true;
    }
    /** The index of the connection the control valve at node {@code valve} drives, or -1 where the island has none. */
    private static int valvePipe(PassiveNetwork graph,long valve) {
        for(int e=0;e<graph.pipes().size();e++) {
            var pipe=graph.pipes().get(e);
            if(pipe.control() instanceof FlowControl.ControlValve&&graph.reservoirs().get(pipe.first()).id()==valve)return e;
        }
        return -1;
    }
    /**
     * {@code graph} with each kettle's pool planned for a slice of {@code seconds} s (W7, plan section 8;
     * {@link ExchangerRelay#planPool}) from its read in {@code reads} (by its key): the wall the tube side's stream as read
     * makes against the pool as it stands, the give-back and the sit-out. A pool that holds no fluid, or whose tubes draw
     * nothing, moves no heat. {@code graph} itself where it has no pool.
     */
    private PassiveNetwork pools(PassiveNetwork graph,Map<Long,ExchangerRelay.Read> reads,double seconds,Runnable checkpoint) {
        List<PassiveNetwork.Reservoir> nodes=null;
        for(int i=0;i<graph.reservoirs().size();i++) {
            // A still's fired pot (batch 2026-11-09-batch-distiller, W3) has no tubes: the still plans its firing and head at dispatch.
            var node=graph.reservoirs().get(i);var pool=node.inventory().pool();if(pool==null||pool.fired())continue;
            var read=reads.getOrDefault(pool.id(),ExchangerRelay.Read.NONE);var tube=read.partner();
            var contents=node.state();
            Pool planned;
            if(node.kind()!=PassiveNetwork.NodeKind.RESERVOIR||holdsNoFluid(node))planned=Pool.idle(pool.id(),pool.partner(),pool.area(),pool.bottom(),pool.bundle(),pool.cells(),contents.temperature());
            else {
                var bath=new ExchangerRelay.Bath(contents.temperature(),pool.film(contents,node.inventory().geometry()));
                ExchangerRelay.Stream stream=null;
                if(tube!=null&&!tube.pool()&&reading(tube).endMass(tube.donor(),tube.port(),tube.volume())>0) {
                    double low=Math.max(NumericalBox.MINIMUM_TEMPERATURE,Math.min(contents.temperature(),tube.donor().temperature())-10);
                    double high=Math.min(NumericalBox.MAXIMUM_TEMPERATURE,Math.max(contents.temperature(),tube.donor().temperature())+10);
                    var curve=reading(tube).spanned(tube.donor(),tube.port(),tube.volume(),low,high,tube.law().films(),checkpoint);
                    if(curve!=null)stream=new ExchangerRelay.Stream(curve,reading(tube).endSpecificEnthalpy(tube.donor(),tube.port(),tube.volume()),tube.massFlow(),tube.law(),true);
                }
                planned=ExchangerRelay.planPool(pool,bath,stream,read,seconds);
            }
            if(planned.equals(pool))continue;
            if(nodes==null)nodes=new ArrayList<>(graph.reservoirs());
            nodes.set(i,new PassiveNetwork.Reservoir(node.id(),node.elevation(),contents,node.kind(),node.inventory().withPool(planned)));
        }
        return nodes==null?graph:new PassiveNetwork(nodes,graph.pipes(),graph.scheduledTransfers());
    }
    /** The solver a coupled side's other stream is read on: this one, or one on the model of the island that stream is in (batch
     * 2026-11-04-refinery-compounds, V2: each island carries its own compound list). */
    private PassiveStepSolver reading(ExchangerRelay.Partner partner) {
        var other=partner.model();
        if(other==null||other==model)return this;
        if(partnerReader==null||partnerReader.model!=other)partnerReader=new PassiveStepSolver(other,ownership);
        return partnerReader;
    }
    private PassiveStepSolver partnerReader;
    /** The heat curve of the stream drawn through {@code port} from state {@code s} over {@code [low, high]} K, or null where it draws nothing. */
    private HeatCurve spanned(FluidThermodynamics.State s,PassiveNetwork.PhasePort port,double volume,double low,double high,HeatCurve.Films films,Runnable checkpoint) {
        double[] amounts=endMoles(s,port,volume);double total=0;for(double n:amounts)total+=n;
        return total>0&&endMass(s,port,volume)>0?HeatCurve.sample(model,amounts,s.pressure(),low,high,films,checkpoint):null;
    }
    /** The share of the node's solids a stream of {@code phase} draws: all of them at a BULK end ({@code phase < 0}). */
    private static double solidShare(FluidThermodynamics.State s,int phase){return phase<0?1:FluidThermodynamics.phaseSolidShare(s,phase);}
    /**
     * The fluid of {@link #endMoles}' stream as a state: the phase amounts {@code port} draws from {@code s} at a vanishing
     * flow (all of them at a BULK end and when the state holds none of the phases), per kilogram of fluid, at the node's
     * own temperature and pressure, its solids left behind. Built from the node's own amounts ({@link FluidThermodynamics#state}):
     * no flash, so nothing a flash's window or answer could refuse. Null when the stream carries no fluid (dry solids).
     */
    private FluidThermodynamics.State endStream(FluidThermodynamics.State s,PassiveNetwork.PhasePort port,double volume) {
        if(PhaseDraw.mixedLiquids(s,port))return mixedLiquidStream(s,volume);
        int phase=PhaseDraw.leading(model,s,port),n=s.componentCount();
        double[][] out=new double[3][n];double mass=0;
        for(int slot=0;slot<3;slot++) {
            // A BULK end draws every slot; a phase end the slot holding its phase.
            if(phase>=0&&s.slot(phase)!=slot)continue;
            double[] held=s.amountsView(slot);
            for(int c=0;c<n;c++){out[slot][c]=held[c];mass+=held[c]*model.molecularWeight(c);}
        }
        if(!(mass>0))return null;
        for(double[] row:out)for(int c=0;c<n;c++)row[c]/=mass;
        return model.adoptingState(s.temperature(),s.pressure(),out[0],out[1],out[2],null);
    }
    /** {@link #endStream} of a {@link PhaseDraw#mixedLiquids} end: each liquid in its {@link PhaseDraw#mixedShares} of a
     * connection of {@code volume}, per kilogram of fluid, its solids left behind. */
    private FluidThermodynamics.State mixedLiquidStream(FluidThermodynamics.State s,double volume){
        var shares=PhaseDraw.mixedShares(s,volume);
        double oil=shares[FluidThermodynamics.OIL]/FluidThermodynamics.phaseVolume(s,FluidThermodynamics.OIL),water=shares[FluidThermodynamics.AQUEOUS]/FluidThermodynamics.phaseVolume(s,FluidThermodynamics.AQUEOUS);
        int n=s.componentCount();double[][] out=new double[3][n];double mass=0;
        for(int label:new int[]{FluidThermodynamics.OIL,FluidThermodynamics.AQUEOUS}) {
            int slot=s.slot(label);if(slot<0)continue;double share=label==FluidThermodynamics.OIL?oil:water;
            double[] held=s.amountsView(slot);
            for(int c=0;c<n;c++){out[slot][c]=held[c]*share;mass+=out[slot][c]*model.molecularWeight(c);}
        }
        for(double[] row:out)for(int c=0;c<n;c++)row[c]/=mass;
        return model.adoptingState(s.temperature(),s.pressure(),out[0],out[1],out[2],null);
    }
    /**
     * The head this connection demands of its device at the point one active-set pass converged to: the discharge
     * pressure less the suction pressure, plus the static column between them. A closed mover reopens where its rise at no
     * flow exceeds it by more than {@link #shutoffBand}: in {@code CLOSED} the actuator row pins the flow to zero, so the
     * free head unknown is this demand and the margin is how much head the mover has in hand.
     */
    private double demand(PassiveNetwork graph,PassiveNetwork.Pipe pipe,List<FluidThermodynamics.State> states,double density) {
        return endPressure(graph.reservoirs().get(pipe.second()),pipe.secondPort(),states.get(pipe.second()))
                -endPressure(graph.reservoirs().get(pipe.first()),pipe.firstPort(),states.get(pipe.first()))
                +density*GRAVITY*(graph.reservoirs().get(pipe.second()).elevation()-graph.reservoirs().get(pipe.first()).elevation());
    }
    /**
     * How much head margin this edge's own rows can resolve, and so the band around the shutoff corner inside which a
     * device's mode may not be decided by the sign of a flow. The pressure-dimensioned rows of an edge are stated in
     * {@link Equations#pressureScales}, so a solve converged to {@code tolerance} has decided them to
     * {@code tolerance*scale} Pa (1e-4 Pa on an atmospheric island, 6e-4 Pa at 6 bar), a flow resolution of order
     * 1e-5 kg/s. The floor of 0.01 Pa keeps the reopening test bit for bit what it was at or below 1e5 Pa (every island
     * of the regression reference). A mover's suction limit reads the same band, against its law's residual and its
     * cavitation margin.
     */
    private static double shutoffBand(double pressureScale,double tolerance) {
        return Math.max(.01,tolerance*Math.max(1e5,pressureScale));
    }
    /** The modes whose law the velocity cap saturates ({@link #saturated}): the throttling ones, and a mover on its own law,
     * whose rise reads the flow the cap lets through. */
    private static boolean saturates(FlowControl.Mode mode) {
        return mode==FlowControl.Mode.PASSIVE||mode==FlowControl.Mode.PUMP_SUCTION_LIMIT||mode==FlowControl.Mode.VALVE_OPEN||mode==FlowControl.Mode.VALVE_CONFLICT
                ||mode==FlowControl.Mode.MOVER_LAW;
    }
    /**
     * The outer phase check of a converged pass's point, node by node ({@link FluidThermodynamics#phaseCheck}, owner
     * decisions D25 and D26): a phase leaves a node only when the solve empties it (the trace fold, which the
     * reconstruction has applied, and the vanishing rule of D38 C: under {@link FluidThermodynamics#VANISHING_SHARE} of the
     * node's fluid and at a smaller share than at the step's start, {@link #inputStates}), a phase the node's TP answer
     * holds and the node does not is added at the node's own split (a junction's PH answer, a vessel's closure,
     * {@link #vesselSplit}), and no populated phase set is swapped for another on a TP verdict. Never inside a residual or
     * Jacobian evaluation. One node per call, as one active-set change per pass; null when none changes.
     *
     * <p>{@code prepared}, when given, is the pass's per-node prepared temperature state (a flash at exactly that
     * temperature reuses its workspace). {@code foldedPhases}, when given, holds per node the phases ({@link #phaseCode}
     * bits) the trace rule removed in this solve and those an own split refused (A85, the D26 amendment): the check does
     * not add them back and records there what its fold removes and its own split refuses, so the pass sequence stays
     * finite. A failed pass's last iterate is not checked here: it is only folded ({@link #foldedIterate}) and read for
     * whether it refutes a phase this check added ({@link Proposal}).
     *
     * <p>The nodes are checked from the lowest pressure up, so a run of junctions that must flash flashes from its
     * downstream end. A liquid stream reaching a junction that already holds vapour has a root there; a two-phase stream
     * reaching a junction laid out as a liquid below its bubble point has none (cold fuzz seed 757: vapour proposed at a
     * junction at 563 kPa, 24 % of its moles at 138 K, fed a liquid-only junction at 484 kPa and 162 K, and the pass
     * stalled on that junction's rows).
     */
    private List<FluidThermodynamics.State> phaseCorrection(PassiveNetwork graph,List<FluidThermodynamics.State> states,Runnable checkpoint,FluidThermodynamics.Prepared[] prepared,int[] foldedPhases) {
        Integer[] order=new Integer[states.size()];for(int i=0;i<order.length;i++)order[i]=i;
        Arrays.sort(order,(a,b)->{int c=Double.compare(states.get(a).pressure(),states.get(b).pressure());return c!=0?c:Integer.compare(a,b);});
        for(int i:order) {
            var state=states.get(i);var node=graph.reservoirs().get(i);if(node.fixed())continue;
            FluidThermodynamics.State checked;
            try{checked=model.phaseCheck(state,node.junction(),inputStates.get(i),foldedPhases,i,prepared==null?null:prepared[i],
                    node.junction()?null:start->vesselSplit(node,state,start,checkpoint),!node.junction()&&node.inventory().rigid(),checkpoint);}
            catch(com.wormzjl.icheme.science.fluid.thermo.ThermoHold hold){throw hold.at(node.id());}
            if(checked==state)continue;
            var corrected=new ArrayList<>(states);corrected.set(i,checked);return corrected;
        }
        return null;
    }
    /** The vapour bit of {@link FluidThermodynamics#phaseBits}. */
    private static final int VAPOUR_BITS=2;
    /**
     * The seeds {@code corrected} with every junction where a vapour first appears ({@link #flashes}) seeded just under
     * the pressure its connections settle it at ({@link #settledFlash}), its connections restarted there
     * ({@link #restateFlashFlows}); {@code point} is what the seeds were formed from: a converged pass's point for the
     * phase check's proposal ({@link #phaseCorrection}), the step's seeds for what arrives ({@link #arrivingPhases}).
     *
     * <p>A junction holds no pressure of its own; its connections set it. The pressure a vapour first appears at is the
     * answer of a layout without it. A liquid-only layout carries its outflow at a liquid's density and velocity cap, so a
     * junction that must flash is left far below its bubble pressure. With the vapour there, the outflow is the mixture's,
     * much lighter, and the junction backs up until what leaves passes what arrives. Cold fuzz seed 509: LNG at 4.34 MPa
     * and 160 K enters through 0.1 m at its cap, 311.9 kg/s. As a liquid (377 kg/m3) the junction drains to 170 kPa, a
     * tenth of its bubble pressure. As the mixture its two 0.1 m outlets pass 311.9 kg/s only at about 206 kg/m3, which it
     * is at 1.24 MPa. Seeded at 170 kPa every step stalled; seed 170 is the same at 449 kPa against 0.92 MPa.
     *
     * <p>Only a vapour appearing in a junction that held none is restated. A liquid condensing in a gas junction makes the
     * outflow heavier, and the point's own pressure is the better start (cold fuzz seed 118: 98 such proposals).
     */
    private List<FluidThermodynamics.State> flashSeeds(PassiveNetwork graph,List<FlowControl.Mode> modes,boolean[] boundaryClosed,
                                                       List<FluidThermodynamics.State> point,List<FluidThermodynamics.State> corrected,Runnable checkpoint) {
        List<FluidThermodynamics.State> result=null;
        for(int j=0;j<corrected.size();j++) {
            if(!flashes(graph,point,corrected,j))continue;
            var settled=settledFlash(graph,modes,boundaryClosed,result==null?corrected:result,j,point.get(j),checkpoint);
            if(settled==null)continue;
            if(result==null)result=new ArrayList<>(corrected);
            result.set(j,settled);
            if(flashRestated==null)flashRestated=new boolean[corrected.size()];
            flashRestated[j]=true;
        }
        return result==null?corrected:result;
    }
    /** The junctions {@link #flashSeeds} restated for the next pass, whose connections start from the restated ends
     * ({@link #restateFlashFlows}); null when none. The pass loop clears it once that pass's equations are built. */
    private boolean[] flashRestated;
    /**
     * The start flows of the connections of every junction {@link #flashSeeds} restated: each one's own law at the
     * seeded end pressures ({@link #lawFlow}), nothing in a direction its boundary forbids. A carried flow belongs to the
     * pressure the junction had: cold fuzz seed 170's junction went from 450 to 674 kPa, and the pass after it stalled
     * on the hydraulic rows of its three connections.
     */
    private void restateFlashFlows(PassiveNetwork graph,List<FlowControl.Mode> modes,boolean[] boundaryClosed,List<FluidThermodynamics.State> seeds,double[] flows) {
        var reservoirs=new ArrayList<PassiveNetwork.Reservoir>(graph.reservoirs().size());
        for(int i=0;i<graph.reservoirs().size();i++){var r=graph.reservoirs().get(i);reservoirs.add(r.fixed()?r:new PassiveNetwork.Reservoir(r.id(),r.elevation(),seeds.get(i),r.kind(),r.inventory()));}
        var g=new PassiveNetwork(reservoirs,graph.pipes(),graph.scheduledTransfers());
        for(int e=0;e<flows.length;e++) {
            var pipe=g.pipes().get(e);
            if(!(flashRestated[pipe.first()]||flashRestated[pipe.second()])||pipe.control() instanceof FlowControl.Mover)continue;
            if(boundaryClosed[e]||modes.get(e)==FlowControl.Mode.CLOSED)continue;
            double law=lawFlow(g,pipe,endPressure(g,pipe,pipe.first()),endPressure(g,pipe,pipe.second()));
            flows[e]=boundaryAllowed(g,pipe,law)?law:0;
        }
    }
    /** Whether {@code corrected} gives junction {@code j} a vapour ({@link #VAPOUR_BITS}) that {@code point} holds none of. */
    private static boolean flashes(PassiveNetwork graph,List<FluidThermodynamics.State> point,List<FluidThermodynamics.State> corrected,int j) {
        var node=graph.reservoirs().get(j);
        if(!node.junction()||node.fixed()||corrected.get(j)==point.get(j))return false;
        int before=FluidThermodynamics.phaseBits(point.get(j));
        return (before&VAPOUR_BITS)==0&&(FluidThermodynamics.phaseBits(corrected.get(j))&~before&VAPOUR_BITS)!=0;
    }
    /** The pressure ratio of the march up from the pressure a vapour appears at, and its most steps. */
    private static final double FLASH_STEP=1.5;private static final int FLASH_STEPS=12;
    /**
     * Junction {@code j}'s flashed seed under the pressure its own balance settles it at, every other node held at
     * {@code states}. The balance is where the net inflow of its connections ({@link #netInflow}) vanishes: each at its
     * own law, saturated at the cap of the end it leaves, the junction the donor of what leaves at its own split there
     * ({@link FluidThermodynamics#junctionSplit}: its amounts and enthalpy, from the trial below it). The net inflow falls
     * as the pressure rises: less arrives at a smaller drive, and what leaves is denser. So the root lies above the seed's
     * pressure where the flashed junction takes in more than it passes, and below the pressure its vapour vanishes at.
     * The march goes up by {@link #FLASH_STEP} (never past the highest pressure of a neighbour) and keeps the last trial
     * that still takes in more than it passes: within one step under the root, on the side where the vapour holds.
     * At the root itself a junction that barely flashes sits just under its bubble point, and the two-phase layout ends
     * there. Cold fuzz seed 592: LNG must pass 298 kg/s through 9.8e-3 m2 of outlets at about 304 kg/m3, against 356 as a
     * liquid; the root is 1.78 MPa, the bubble point about 1.86 MPa. Every step from the root walked off to an all-vapour
     * iterate. Seeded at 1.24 MPa, the march's last trial, the island is clean in 4 266 Newton iterations (5 153 on
     * 0.10.0, whose junction stayed a liquid at 552 kPa). Null where the seed passes what arrives already, or where no
     * trial keeps the vapour.
     */
    private FluidThermodynamics.State settledFlash(PassiveNetwork graph,List<FlowControl.Mode> modes,boolean[] boundaryClosed,
                                                   List<FluidThermodynamics.State> states,int j,FluidThermodynamics.State before,Runnable checkpoint) {
        var seed=states.get(j);
        if(!(netInflow(graph,modes,boundaryClosed,states,j,seed)>0))return null;
        double[] n=PhaseLayout.totalAmounts(seed);int added=FluidThermodynamics.phaseBits(seed)&~FluidThermodynamics.phaseBits(before);
        double ceiling=0;
        for(var pipe:graph.pipes())if(pipe.first()==j||pipe.second()==j){int other=pipe.first()==j?pipe.second():pipe.first();ceiling=Math.max(ceiling,states.get(other).pressure());}
        var low=seed;
        for(int k=0;k<FLASH_STEPS;k++) {
            double p=Math.min(low.pressure()*FLASH_STEP,ceiling);if(!(p>low.pressure()))break;
            var trial=flashAt(n,seed,p,low,added,checkpoint);
            if(trial==null||!(netInflow(graph,modes,boundaryClosed,states,j,trial)>0))break;
            low=trial;
        }
        return low==seed?null:low;
    }
    /** The junction's own split of {@code seed}'s amounts {@code n} and enthalpy at pressure {@code p}, from {@code start},
     * folded as the pass folds a junction; null where it fails or no longer holds the {@code added} phases. */
    private FluidThermodynamics.State flashAt(double[] n,FluidThermodynamics.State seed,double p,FluidThermodynamics.State start,int added,Runnable checkpoint) {
        FluidThermodynamics.State own;
        try{own=model.junctionSplit(n,seed.enthalpy(),seed.mass(),p,start,checkpoint);}
        catch(com.wormzjl.icheme.science.fluid.thermo.ThermoHold outside){return null;}
        if(own==null)return null;
        var folded=model.folded(own.withSolidState(seed.solids(),seed.solidMoments()),true);
        return (FluidThermodynamics.phaseBits(folded)&added)==added?folded:null;
    }
    /** The net mass inflow (kg/s) of junction {@code j} stated as {@code trial}, every other node as {@code states}: each open
     * connection at its law's flow on those end pressures ({@link #lawFlow}), saturated at its donor's cap
     * ({@link #transported}), nothing in a direction its boundary forbids. */
    private double netInflow(PassiveNetwork graph,List<FlowControl.Mode> modes,boolean[] boundaryClosed,List<FluidThermodynamics.State> states,int j,FluidThermodynamics.State trial) {
        var reservoirs=new ArrayList<PassiveNetwork.Reservoir>(graph.reservoirs().size());
        for(int i=0;i<graph.reservoirs().size();i++){var r=graph.reservoirs().get(i);reservoirs.add(r.fixed()?r:new PassiveNetwork.Reservoir(r.id(),r.elevation(),i==j?trial:states.get(i),r.kind(),r.inventory()));}
        var g=new PassiveNetwork(reservoirs,graph.pipes(),graph.scheduledTransfers());
        double net=0;
        for(int e=0;e<g.pipes().size();e++) {
            var pipe=g.pipes().get(e);if(pipe.first()!=j&&pipe.second()!=j)continue;
            var mode=modes.get(e);
            if(boundaryClosed[e]||mode==FlowControl.Mode.CLOSED||mode==FlowControl.Mode.INLET_WRONG_PHASE)continue;
            double law=lawFlow(g,pipe,endPressure(g,pipe,pipe.first()),endPressure(g,pipe,pipe.second()));
            if(!boundaryAllowed(g,pipe,law))continue;
            double q=transported(g,pipe,law);
            net+=pipe.second()==j?q:-q;
        }
        return net;
    }
    /**
     * The check of a failed pass's last iterate, node by node (owner decisions D28 A' and D39): the iterate with the
     * phases it empties folded, by the trace fold and the vanishing rule against the step's start ({@link #inputStates}),
     * one node per call; null when it empties none. A failed iterate is not a state: this trial layout neither reads nor
     * writes the A85 record and adds no phase, so no own split is formed and no hold is raised at a point the network
     * never reaches.
     */
    /** Whether {@code node} is a vessel whose wall is rigid (batch 2026-11-10-rigid-reservoir): its gas is judged by volume. */
    private static boolean rigid(PassiveNetwork.Reservoir node){return node.kind()==PassiveNetwork.NodeKind.RESERVOIR&&!node.junction()&&node.inventory()!=null&&node.inventory().rigid();}
    private List<FluidThermodynamics.State> foldedIterate(PassiveNetwork graph,List<FluidThermodynamics.State> states) {
        for(int i=0;i<states.size();i++) {
            var state=states.get(i);var node=graph.reservoirs().get(i);if(node.fixed())continue;
            var folded=model.folded(state,node.junction(),rigid(node),inputStates.get(i));
            if(folded==state)continue;
            var corrected=new ArrayList<>(states);corrected.set(i,folded);return corrected;
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
    private List<FluidThermodynamics.State> foldedSeeds(PassiveNetwork graph,List<FluidThermodynamics.State> seeds) {
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
    /** {@link #phaseFolded}, recording the folded phases ({@link #phaseCode} bits) of each node in {@code foldedPhases}. */
    private static boolean recordFolds(PassiveNetwork graph,List<FluidThermodynamics.State> solved,List<FluidThermodynamics.State> reconstructed,int[] foldedPhases) {
        if(!phaseFolded(graph,solved,reconstructed))return false;
        for(int i=0;i<solved.size();i++)if(!graph.reservoirs().get(i).fixed())foldedPhases[i]|=phaseCode(solved.get(i))&~phaseCode(reconstructed.get(i))&7;
        return true;
    }
    /**
     * Whether the reconstruction folded away a phase the pass solved (decision D34): a node's restated state lacks a
     * hydrocarbon liquid, free water or gas phase its Newton state holds, or its gas lost its water vapour (a carried gas,
     * decision D41). The restatement ({@code ConservativeTransport.repartition}) keeps every phase whose booked amounts are
     * not all zero, so a phase it drops is a phase it folded.
     */
    private static boolean phaseFolded(PassiveNetwork graph,List<FluidThermodynamics.State> solved,List<FluidThermodynamics.State> reconstructed) {
        for(int i=0;i<solved.size();i++) {
            if(graph.reservoirs().get(i).fixed())continue;
            var before=solved.get(i);var after=reconstructed.get(i);
            if((PhaseLayout.activeBits(before)&~PhaseLayout.activeBits(after))!=0)return true;
        }
        return false;
    }
    /**
     * Whether the reconstruction gave a hydrocarbon phase to a node the Newton state holds none in (hold class E3). A
     * node the booked amounts give an omitted trace has none to give (decision D41 for vessels,
     * {@link FluidThermodynamics#omittedTrace}): the next pass would omit it again ({@code PhaseLayout.support}), so it
     * is the node's state, not an appearance.
     */
    private static boolean phaseAppeared(PassiveNetwork graph,List<FluidThermodynamics.State> solved,List<FluidThermodynamics.State> reconstructed) {
        for(int i=0;i<solved.size();i++) {
            var before=solved.get(i);var after=reconstructed.get(i);
            if(FluidThermodynamics.holdsFluid(before)||!FluidThermodynamics.holdsFluid(after))continue;
            double[] held=PhaseLayout.totalAmounts(before),booked=PhaseLayout.totalAmounts(after);double total=PhaseLayout.sum(booked);
            for(int c=0;c<held.length;c++)if(held[c]==0&&booked[c]>=PassiveIntervalSolver.TRACE_FLUSH_FRACTION*total)return true;
        }
        return false;
    }
    /** Which phases this state carries: the four fluid flags of {@link FluidThermodynamics#phaseBits} (hydrocarbon liquid 1,
     * hydrocarbon vapour 2, free water 4, steam 8) and 16 for solids. */
    /**
     * Whether a junction seed of phase code {@code seed} is laid out for its step's mixture of code {@code mixture}
     * ({@link #arrivingPhases}): the same hydrocarbon phases, liquid and gas, and every water phase of the mixture. A pass
     * cannot empty a phase, so a hydrocarbon phase the mixture lacks counts as much as one it holds and the seed does not:
     * the balance stated four junctions as the 215.5 K CO2-rich gas and liquid delivered to them while each held 3 to 14 g
     * of warm gas (stock enthalpy -10 to +44 kJ/kg against -131 to -136), and every step down to 1.1e-6 s started in a
     * layout its answer was not in (cold fuzz seed 994, E6). Water keeps the superset: a seed's water may be the entry
     * trace of decision D10, which a mixture nothing wet reaches lacks, and dropping it left the rows without the water the
     * reconstruction then booked (bundled fuzz seed 157, E4).
     */
    private static boolean laidOutFor(int mixture,int seed){return (mixture&2)==(seed&2)&&Integer.bitCount(mixture&5)==Integer.bitCount(seed&5);}
    private static int phaseCode(FluidThermodynamics.State state) {
        return (state.solidMoments().mass()>0?16:0)|FluidThermodynamics.phaseBits(state);
    }
    /**
     * Closes, before the first pass, every passive connection whose own starting point leaves it no direction it is
     * allowed to carry. The hydraulic row is {@code driving - loss(q)} with {@code loss} zero at zero flow and increasing
     * away from it, so a root with {@code q > 0} exists only when the driving pressure a forward flow stands in is
     * positive, and with {@code q < 0} only when the reverse drive is negative: a connection one of whose ends forbids a
     * direction has no admissible root when the only sign with a root is the forbidden one, and its one answer is a flow
     * of exactly zero, the answer {@code boundaryClosed} produces. On a dead-headed line the pass cannot converge to that
     * point (the reverse root lies across zero flow, where the friction slope changes by three orders between a liquid and
     * a gas), so the closure is taken from the starting point instead.
     *
     * <p>Three conditions keep it a statement that cannot be wrong for the solve: both columns are asked (a forward flow
     * stands in the first end's fluid and a reverse flow in the second's, the pairing {@link Equations#columnEnds} states);
     * neither end of the run is a zero-holdup junction (a junction's pressure is an output of the previous solve, while a
     * boundary's is fixed and a vessel's moves monotonically against the flow that delivers it, so a drive that refuses a
     * direction at the start refuses it everywhere the solve can go; the run is a chain of
     * {@link Runs}); only passive connections (an actuator decides its own mode, and a boundary direction is never closed on
     * a device's symptom). It cannot cycle: monotone within a solve, taken once before any pass, never cleared, and per
     * solve, so a line whose generator is raised afterwards opens again.
     */
    private void closeDeadHeads(PassiveNetwork graph,boolean[] boundaryClosed,boolean[] keep) {
        int edges=graph.pipes().size();
        var runs=Runs.of(graph);
        boolean[] taken=new boolean[edges];
        for(int edge=0;edge<edges;edge++) {
            if(taken[edge]||boundaryClosed[edge]||!(graph.pipes().get(edge).control() instanceof FlowControl.Passive))continue;
            // The maximal passive run through degree-two junctions containing this connection, as
            // a node order and the links between consecutive nodes ({@link Runs#walk}).
            var chain=new ArrayList<Integer>();var order=new ArrayList<Integer>();
            runs.walk(graph,edge,taken,next->graph.pipes().get(next).control() instanceof FlowControl.Passive,chain,order);
            int start=order.getFirst(),finish=order.getLast();
            if(start==finish||graph.reservoirs().get(start).junction()||graph.reservoirs().get(finish).junction())continue;
            // Which way the run as a whole may carry: every link must allow its own share of it.
            boolean forwardAllowed=true,reverseAllowed=true;
            for(int position=0;position<chain.size();position++) {
                var link=graph.pipes().get(chain.get(position));boolean aligned=link.first()==order.get(position);
                forwardAllowed&=boundaryAllowed(graph,link,aligned?1:-1);
                reverseAllowed&=boundaryAllowed(graph,link,aligned?-1:1);
            }
            if(forwardAllowed&&reverseAllowed)continue;
            var a=graph.reservoirs().get(start);var b=graph.reservoirs().get(finish);
            // Each direction in its donor end port's column over the run ({@link #columnDensity}), at the ends' driving pressures.
            var startPort=graph.pipes().get(chain.getFirst()).portAt(start);var finishPort=graph.pipes().get(chain.getLast()).portAt(finish);
            double dz=b.elevation()-a.elevation(),difference=endPressure(a,startPort,a.state())-endPressure(b,finishPort,b.state());
            boolean forward=forwardAllowed&&difference-runColumnDensity(graph,chain,a.state(),startPort)*GRAVITY*dz>0;
            boolean reverse=reverseAllowed&&difference-runColumnDensity(graph,chain,b.state(),finishPort)*GRAVITY*dz<0;
            if(keep!=null){boolean kept=false;for(int link:chain)kept|=keep[link];if(kept)continue;}
            if(!forward&&!reverse)for(int link:chain)boundaryClosed[link]=true;
        }
    }
    /**
     * The {@code illegalDirection} closure of the pass loop, taken once at the start of the solve on the point pass 0
     * starts from: a passive connection whose start-point flow runs in a direction its own boundary forbids
     * ({@link #boundaryAllowed}) is closed for this solve exactly as that rule closes it after a converged pass. The
     * direction is the one {@link #startPoint} gives it; where that gives none ({@code |q| <= 1e-10}, every connection the
     * previous point had closed), the direction of {@link #initialMassFlow} on the current states. Derived afresh from
     * this solve's start point, so a connection whose pressures turn legal opens again at the next solve. Passive
     * connections only (devices are decided first, see the pass loop). Why: an island coming to rest next to a void or a
     * generator leaves the one-way connections with flows that point the forbidden way at the next solve's start, and the
     * pass loop would close them only after a converged pass, which at rest is a zero-flow stall. Only once this solver
     * has accepted a solve on the same structure (a first solve's junction pressures are the compiler's guess, not a
     * state), and only where the static driving pressure forbids the direction with either end's density
     * ({@link #illegalWithEitherDensity}). {@code keep} names the connections the interval solver reopened.
     */
    private void closeIllegalStarts(PassiveNetwork graph,List<FlowControl.Mode> modes,boolean[] boundaryClosed,boolean[] keep) {
        // Decision D54: a passive run whose static drive points out of a dry end (a vessel holding no fluid, the top port of a
        // slack vessel holding no gas) carries nothing: the end gives nothing and the run stands in what its other end would
        // send (the column of a dry end is the other end's), so the drive is no guess. Closed for this solve, on every solve.
        for(int i=0;i<graph.pipes().size();i++) {
            var pipe=graph.pipes().get(i);var a=graph.reservoirs().get(pipe.first());var b=graph.reservoirs().get(pipe.second());
            if(boundaryClosed[i]||!(pipe.control() instanceof FlowControl.Passive)||keep!=null&&keep[i])continue;
            boolean dryA=dryEnd(a,pipe.firstPort()),dryB=dryEnd(b,pipe.secondPort());if(!dryA&&!dryB)continue;
            double drive=staticDrive(graph,pipe,endPressure(a,pipe.firstPort(),a.state())-endPressure(b,pipe.secondPort(),b.state()));
            if(dryA&&drive>0||dryB&&drive<0||dryA&&dryB)boundaryClosed[i]=true;
        }
        if(!(sameLines(acceptedPipes,identities(graph))&&Arrays.equals(acceptedNodeIds,nodeIds(graph))))return;
        int edges=graph.pipes().size();
        double[] start=new double[edges];
        startPoint(graph,modes,boundaryClosed,identities(graph),null,start,new double[edges],new boolean[edges]);
        for(int i=0;i<edges;i++) {
            var pipe=graph.pipes().get(i);
            if(boundaryClosed[i]||!(pipe.control() instanceof FlowControl.Passive))continue;
            if(keep!=null&&keep[i])continue;
            double q=start[i];
            if(Math.abs(q)<=1e-10) {
                // No start flow (a settled line, one the previous point had closed): the connection's own law on the current
                // states decides, read only beyond the reopen band, the one-way rule's tolerance ({@link #balanceStart}): a
                // settled line's drive is the junction pressures' roundoff (a line read -7.2e-7 kg/s and was closed for good).
                var a=graph.reservoirs().get(pipe.first());var b=graph.reservoirs().get(pipe.second());
                double pa=endPressure(a,pipe.firstPort(),a.state()),pb=endPressure(b,pipe.secondPort(),b.state());
                if(Math.abs(staticDrive(graph,pipe,pa-pb))<=reopenBand(pa,pb))continue;
                q=initialMassFlow(graph,pipe);
            }
            if(Math.abs(q)>1e-10&&!boundaryAllowed(graph,pipe,q)) {
                if(illegalWithEitherDensity(graph,pipe,q))boundaryClosed[i]=true;
            }
        }
    }
    /**
     * Whether a connection in {@code mode} relates the pressures of its two ends: every open connection except one whose
     * flow is prescribed (closed, refused) or whose row fixes its first end's pressure outright
     * (a regulating valve, or a pump on its suction limit, which anchors that end instead; see {@link #pockets}).
     */
    private static boolean couples(FlowControl.Mode mode,boolean closed) {
        return !closed&&switch(mode){case CLOSED,INLET_WRONG_PHASE,VALVE_REGULATING,PUMP_SUCTION_LIMIT->false;default->true;};
    }
    /** Whether a connection in {@code mode} fixes its first end's pressure by its own row: a regulating pressure valve (its
     * set point) and a pump on its suction limit (the vapour pressure, owner decision D59 (4)). A regulating flow valve's
     * row prescribes a flow. */
    private static boolean anchors(PassiveNetwork.Pipe pipe,FlowControl.Mode mode){return mode==FlowControl.Mode.VALVE_REGULATING&&!flowValve(pipe)||mode==FlowControl.Mode.PUMP_SUCTION_LIMIT;}
    /** Whether {@code pipe} is a configured valve on a flow target. */
    private static boolean flowValve(PassiveNetwork.Pipe pipe){return pipe.control() instanceof FlowControl.Valve valve&&!valve.bare()&&!valve.pressureControl();}
    private static boolean closedEdge(FlowControl.Mode mode,boolean closed){return closed||mode==FlowControl.Mode.CLOSED||mode==FlowControl.Mode.INLET_WRONG_PHASE;}
    private static int root(int[] parent,int node){while(parent[node]!=node){parent[node]=parent[parent[node]];node=parent[node];}return node;}
    /**
     * The pockets of a pass (hold F8): for every node, the pocket it belongs to (the index of a member), or -1. A pocket
     * is a set of junctions joined by pressure-coupling connections ({@link #couples}) with no pressure anchor: no coupling
     * connection to a node that is not a junction and no regulating valve drawing from it. A junction's pressure is
     * algebraic, and nothing places a pocket's level: shifting every member's pressure together changes no row but those
     * of the connections on its boundary, all prescribed-flow rows with a free head or none.
     */
    private static int[] pockets(PassiveNetwork graph,List<FlowControl.Mode> modes,boolean[] boundaryClosed) {
        int nodes=graph.reservoirs().size();int[] parent=new int[nodes];for(int i=0;i<nodes;i++)parent[i]=i;
        boolean[] anchored=new boolean[nodes];
        for(int i=0;i<nodes;i++)anchored[i]=!graph.reservoirs().get(i).junction();
        for(int e=0;e<graph.pipes().size();e++) {
            var pipe=graph.pipes().get(e);var mode=modes.get(e);
            if(!boundaryClosed[e]&&anchors(pipe,mode))anchored[pipe.first()]=true;
            if(couples(mode,boundaryClosed[e])){int a=root(parent,pipe.first()),b=root(parent,pipe.second());if(a!=b)parent[Math.max(a,b)]=Math.min(a,b);}
        }
        boolean[] rootAnchored=new boolean[nodes];for(int i=0;i<nodes;i++)if(anchored[i])rootAnchored[root(parent,i)]=true;
        int[] pocket=new int[nodes];for(int i=0;i<nodes;i++){int r=root(parent,i);pocket[i]=rootAnchored[r]?-1:r;}
        return pocket;
    }
    /**
     * Owner decision D58 (A4): what feeds a mover's suction must reach it, and what relieves its discharge must carry flow
     * away from it; a connection whose one-way rules forbid that direction relieves nothing. The mover's own ends first
     * (D59 (4), world defaults 826): a discharge that is a generator, or a suction that is a void, is a pocket of one node
     * that only supplies or only receives. A running mover with a sealed side ({@link #sealedWithout}) can carry nothing
     * and starts closed for this solve, the pocket isolated at its pressure ({@code Equations.retainedPressures}): on its
     * law it would stand at its rise at no flow, which places a sealed discharge at the mover's shutoff pressure and a
     * sealed suction that far below its discharge, outside the package's range for a compressor or a strong pump. Repeated
     * until no running mover has a sealed side, since one closing can seal another; an island without one keeps its modes
     * exactly. Decided at the start of the solve and when a flow valve changes. A mover on its law prescribes no flow, so
     * every pocket's level keeps a row (hold F8 was a mover at its target flow into a sealed pocket).
     */
    private static void relieveSealedPockets(PassiveNetwork graph,List<FlowControl.Mode> modes,boolean[] boundaryClosed) {
        int edges=graph.pipes().size();int[][] nodeEdges=incidence(graph);
        for(int round=0;round<=edges;round++) {
            boolean changed=false;
            for(int e=0;e<edges;e++) {
                var pipe=graph.pipes().get(e);var mode=modes.get(e);
                if(boundaryClosed[e]||mode!=FlowControl.Mode.MOVER_LAW&&mode!=FlowControl.Mode.PUMP_SUCTION_LIMIT)continue;
                if(!boundaryAllowed(graph,pipe,1)||sealedWithout(graph,nodeEdges,modes,boundaryClosed,pipe.first(),e,-1)||sealedWithout(graph,nodeEdges,modes,boundaryClosed,pipe.second(),e,1))
                    {modes.set(e,FlowControl.Mode.CLOSED);changed=true;}
            }
            if(!changed)return;
        }
    }
    // ======== The balanced start: owner decision of 2026-10-19 (batch 2026-10-19-junction-seeding, JUNCTION_SEEDING_PLAN.md) ========
    /**
     * Composition rounds of {@link #balanceStart}, at most: the junctions' upwind mixtures at the balanced flows move their
     * densities and caps, so the balance is solved once more on them; a round that moves no junction pressure by more than
     * 1e-6 of itself ends the rounds early.
     */
    static final int BALANCE_ROUNDS=2;
    /** The balance's law flows for the solve in progress; null where none was run (the static estimate stands in). */
    private double[] balanceLawFlows;
    /** The next pass takes its law flows from the balance, not from the carried point: a flow valve has just left its open pass.
     * One pass only, so a solve that fails there is retried from the point it was carried, not from the balance's mixture. */
    private boolean lawFlowsFromBalance;
    /** The last balance and the input graph it was computed for: every attempt of one step starts from the same graph. */
    private PassiveNetwork balanceFor;private Start balanceCache;private boolean[] balanceClosedFor;
    /** The balance's result for one solve: the graph with its junctions at the balanced states, the law flows of every
     * open connection at the balanced ends, the passive connections the one-way law closes, the device modes it decided, and whether it converged. */
    record Start(PassiveNetwork graph,double[] lawFlows,boolean[] closed,List<FlowControl.Mode> modes,boolean converged) {}
    /** Whether this solve starts from a carried point ({@link #startPoint}'s test) or needs the balance. */
    private boolean needsBalance(PassiveNetwork graph){return !graph.pipes().isEmpty()&&!carriedPoint(graph,identities(graph));}
    /**
     * Whether this solve starts from a carried point: the previous converged solve's structure is this one's (pipe
     * identities, node ids, a flow per connection) and its point is this solve's own. A rate solve warm-starts only on
     * value-identical input states (review 7.7); a rate solve's point starts a step only where it was written back into the
     * step's graph (the cold rate seed, {@code PassiveIntervalSolver.coldRateSeed}: owner decision of 2026-10-19, the plan's
     * fallback), so a step whose rate seed failed, or any solve on other states, starts from the balance instead.
     */
    private boolean carriedPoint(PassiveNetwork graph,List<PassiveNetwork.Pipe.Identity> pipeIdentities) {
        if(!(sameLines(previousPipes,pipeIdentities)&&Arrays.equals(previousNodeIds,nodeIds(graph))&&previousFlows.length==graph.pipes().size()&&previousLawFlows.length==graph.pipes().size()))return false;
        if(rateOnly&&!sameStates(previousInputStates,inputStates))return false;
        if(previousRateOnly&&!rateOnly&&!sameFluidStates(previousOutputStates,inputStates))return false;
        return true;
    }
    /** The initial device modes of a solve on {@code graph}'s states (the mint reads them too): the switch of {@link #solve}.
     * A vapour-locked pump ({@link #vapourLocks}, owner decision D16 A) starts closed, as one set to no flow does. */
    private List<FlowControl.Mode> initialModes(PassiveNetwork graph) {
        var modes=new ArrayList<FlowControl.Mode>();
        for(var pipe:graph.pipes())modes.add(switch(pipe.control()) {
            case FlowControl.Passive ignored->FlowControl.Mode.PASSIVE;
            case FlowControl.Mover pump->pump.targetVolumeFlow()==0||vapourLocked(modes.size())?FlowControl.Mode.CLOSED:FlowControl.Mode.MOVER_LAW;
            case FlowControl.Valve valve->heldOpenings.length==graph.pipes().size()&&!Double.isNaN(heldOpenings[modes.size()])?(heldOpenings[modes.size()]==0?FlowControl.Mode.CLOSED:FlowControl.Mode.VALVE_CONFLICT):valve.bare()||!valve.pressureControl()||graph.reservoirs().get(pipe.first()).state().pressure()>valve.targetPressure()+.01
                    ?FlowControl.Mode.VALVE_OPEN:FlowControl.Mode.CLOSED;
        });
        return modes;
    }
    /**
     * The mint's round 0 (owner decision of 2026-10-19 on t45's trace, {@code BRANCH_SELECTION_TRACE.md}): a minted
     * junction's first state is what reaches it, decision D55's supplier composition, not the placeholder (the island's
     * lowest-id boundary state, which put crude in a nitrogen header and water in a nitrogen loop, sized buffers 38 to 570
     * times too dense, and left the balance's two rounds on an iterate the cold rate seed then froze). A supplier is a node
     * that is not a junction and holds something; a void and a dry end send nothing ({@link #boundaryAllowed}). Each
     * supplier joined to a junction sends the stream its end draws ({@link #endMoles}) at the flow its static drive gives
     * against the ambient ({@link #supplyFlow}); the junction is the flow-weighted mixture of what reaches it, flashed at the
     * flow-weighted pressure of its suppliers' ends ({@link #seedAt}; the ambient put a liquid CO2 let-down below its triple point): the balance solves the pressures and restates every mixture at its balanced pressure
     * ({@link BalanceNet#seeded}), so D55's pressure bisection is not needed. A junction no supplier drives into takes it hop
     * by hop from the junctions that have it (a breadth-first sweep weighted by the flow that seeded them); one neither
     * reaches takes the streams of the suppliers joined to it, one share each, and the sweep runs again. A junction no
     * source reaches keeps the state it was built with ({@link NetworkReduction#atRest}).
     *
     * <p>A mover's suction line is seeded first, from the stream it draws (owner's fix A of F2): the junctions its supply
     * walk crosses ({@link InletPhase#walk}) take the stream of the supplier the walk ends at, as that end draws it
     * ({@link #endStream}), neither flashed nor moved. A mover's target row converts its volume flow at its suction
     * junction's density, so that junction must hold what the mover draws (a pump under a vented LNG tank kept the 298 K
     * nitrogen placeholder, 1.1 kg/m3 for 447 kg/m3, and moved 0.006 kg/s for 2.24 kg/s).
     */
    private List<FluidThermodynamics.State> supplierSeeds(PassiveNetwork graph,Runnable checkpoint) {
        int nodes=graph.reservoirs().size(),count=model.hydrocarbon.componentCount()+1;
        var states=new ArrayList<FluidThermodynamics.State>(nodes);
        for(var node:graph.reservoirs())states.add(node.junction()?node.state():null);
        double[][] amounts=new double[nodes][count];double[] mass=new double[nodes],heat=new double[nodes],tLow=new double[nodes],tHigh=new double[nodes],pressureSum=new double[nodes];
        boolean[] seeded=new boolean[nodes],drawn=new boolean[nodes];double[] weight=new double[nodes];
        for(int e=0;e<graph.pipes().size();e++) {
            if(!(graph.pipes().get(e).control() instanceof FlowControl.Mover))continue;
            var walk=InletPhase.walk(graph,e);
            if(walk.via()<0||graph.reservoirs().get(walk.node()).junction())continue;
            boolean supplies=true;
            for(int k=0;k<walk.links().length&&supplies;k++){var link=graph.pipes().get(walk.links()[k]);supplies=boundaryAllowed(graph,link,link.first()==walk.donors()[k]?1:-1);}
            var supplier=graph.reservoirs().get(walk.node());var last=graph.pipes().get(walk.via());
            var seed=supplies?endStream(supplier.state(),last.portAt(walk.node()),last.volume()):null;
            if(seed==null)continue;
            double q=supplyFlow(graph,last,walk.node());if(!(q>0))q=1;
            for(int k=-1;k<walk.links().length-1;k++) {
                int j=k<0?graph.pipes().get(e).first():walk.donors()[k];
                if(seeded[j])continue;
                states.set(j,seed);seeded[j]=drawn[j]=true;weight[j]=q;
            }
        }
        for(boolean driven:new boolean[]{true,false}) {
            Arrays.fill(mass,0);Arrays.fill(heat,0);Arrays.fill(pressureSum,0);Arrays.fill(tLow,Double.POSITIVE_INFINITY);Arrays.fill(tHigh,Double.NEGATIVE_INFINITY);
            for(var row:amounts)Arrays.fill(row,0);
            for(var pipe:graph.pipes())for(int end=0;end<2;end++) {
                int supplier=end==0?pipe.first():pipe.second(),junction=end==0?pipe.second():pipe.first();
                var s=graph.reservoirs().get(supplier);
                // A supplier whose end is dry (one holding no fluid, dryEnd) sends nothing: both flows below are zero.
                if(!graph.reservoirs().get(junction).junction()||seeded[junction]||s.junction())continue;
                double q=driven?supplyFlow(graph,pipe,supplier):boundaryAllowed(graph,pipe,end==0?1:-1)?1:0;
                if(!(q>0))continue;
                var port=pipe.portAt(supplier);double[] n=endMoles(s.state(),port,pipe.volume());double m=endMass(s.state(),port,pipe.volume());
                for(int c=0;c<count;c++)amounts[junction][c]+=q*n[c]/m;
                var through=delivered(graph,pipe,endSpecificEnthalpy(s.state(),port,pipe.volume()),s.state().temperature(),end==0?q:-q,checkpoint);
                heat[junction]+=q*through[0];pressureSum[junction]+=q*endPressure(s,port,s.state());
                mass[junction]+=q;tLow[junction]=Math.min(tLow[junction],through[1]);tHigh[junction]=Math.max(tHigh[junction],through[1]);
            }
            // Flashed at the flow-weighted pressure of what reaches it (a liquid CO2 let-down's junction stands above the triple
            // point, not at the ambient); the balance then solves the pressure and restates the mixture there.
            for(int j=0;j<nodes;j++)if(mass[j]>0&&seedAt(states,j,amounts[j],mass[j],heat[j],tLow[j],tHigh[j],pressureSum[j]/mass[j],checkpoint)){seeded[j]=true;weight[j]=mass[j];}
            // The sweep: hop by hop from the junctions seeded so far; a vessel that holds no fluid passes it through.
            for(boolean grew=true;grew;) {
                grew=false;var next=new boolean[nodes];
                Arrays.fill(mass,0);Arrays.fill(heat,0);Arrays.fill(pressureSum,0);Arrays.fill(tLow,Double.POSITIVE_INFINITY);Arrays.fill(tHigh,Double.NEGATIVE_INFINITY);
                for(var row:amounts)Arrays.fill(row,0);
                for(int j=0;j<nodes;j++) {
                    if(!graph.reservoirs().get(j).junction()||seeded[j])continue;
                    for(int k:sweepNeighbours(graph,j)) {
                        if(!seeded[k])continue;var s=states.get(k);double w=weight[k];
                        double[] n=PhaseLayout.totalAmounts(s);for(int c=0;c<count;c++)amounts[j][c]+=w*n[c]/s.mass();
                        heat[j]+=w*(s.enthalpy()/s.mass());pressureSum[j]+=w*s.pressure();mass[j]+=w;
                        tLow[j]=Math.min(tLow[j],s.temperature());tHigh[j]=Math.max(tHigh[j],s.temperature());
                    }
                }
                for(int j=0;j<nodes;j++)if(mass[j]>0&&seedAt(states,j,amounts[j],mass[j],heat[j],tLow[j],tHigh[j],pressureSum[j]/mass[j],checkpoint)){next[j]=grew=true;weight[j]=mass[j];}
                for(int j=0;j<nodes;j++)seeded[j]|=next[j];
            }
        }
        return states;
    }
    /** The junctions one hop from junction {@code j} for {@link #supplierSeeds}' sweep: joined to it by a connection, or
     * through one or more vessels that hold no fluid ({@link #holdsNoFluid}: none of their own to send). */
    private static List<Integer> sweepNeighbours(PassiveNetwork graph,int j) {
        var found=new ArrayList<Integer>();var seen=new HashSet<Integer>();var open=new ArrayDeque<Integer>();open.add(j);seen.add(j);
        while(!open.isEmpty()) {
            int at=open.poll();
            for(var pipe:graph.pipes()) {
                int other=pipe.first()==at?pipe.second():pipe.second()==at?pipe.first():-1;
                if(other<0||!seen.add(other))continue;
                var node=graph.reservoirs().get(other);
                if(node.junction())found.add(other);else if(holdsNoFluid(node))open.add(other);
            }
        }
        Collections.sort(found);return found;
    }
    /** {@link #supplierSeeds}' flash of junction {@code j}'s mixture (amounts and enthalpy summed over {@code mass}) at
     * {@code pressure}, per kilogram: the temperature at which the mixture has the mixed enthalpy, bisected within 20 K of
     * its streams' temperatures inside the numerical box (no data range narrows the window, owner rule of 2026-10-16).
     * False, the junction left as it was, where a flash is refused. */
    private boolean seedAt(List<FluidThermodynamics.State> states,int j,double[] amounts,double mass,double heat,double tLow,double tHigh,double pressure,Runnable checkpoint) {
        double[] y=new double[amounts.length];for(int c=0;c<y.length;c++)y[c]=amounts[c]/mass;
        double lower=Math.max(tLow-20,NumericalBox.MINIMUM_TEMPERATURE),upper=Math.min(tHigh+20,NumericalBox.MAXIMUM_TEMPERATURE);
        try {
            for(int k=0;k<60&&upper-lower>1e-9;k++) {
                double t=.5*(lower+upper);var point=model.flashTP(t,pressure,y.clone(),checkpoint);
                if(point.enthalpy()/point.mass()<heat/mass)lower=t;else upper=t;
            }
            states.set(j,model.flashTP(.5*(lower+upper),pressure,y.clone(),checkpoint));
        } catch(IllegalArgumentException refused){return false;} // a rejected state: the numerical box, a phase hold
        return true;
    }
    /**
     * The flow (kg/s, at least zero) supplier {@code supplier} sends into the junction at the other end of {@code pipe}
     * before anything is solved, as {@link #initialMassFlow} reads a connection: a mover's suction flow out of its suction
     * end; otherwise the supplier's end pressure over the ambient, less its own end's column up to the junction, through
     * the connection's loss in its end stream, capped by the velocity limit. Zero where that direction is refused.
     */
    private double supplyFlow(PassiveNetwork graph,PassiveNetwork.Pipe pipe,int supplier) {
        boolean first=pipe.first()==supplier;
        if(!boundaryAllowed(graph,pipe,first?1:-1))return 0;
        var s=graph.reservoirs().get(supplier);var port=pipe.portAt(supplier);
        if(pipe.control() instanceof FlowControl.Mover pump)
            return first?Math.min(suctionMassFlow(pump.targetVolumeFlow(),s.state(),port,pipe.volume()),massFlowLimit(pipe,s.state(),port)):0;
        double rho=endDensity(s.state(),port,pipe.volume()),mu=endViscosity(s.state(),port,pipe.volume());
        double drive=endPressure(s,port,s.state())-PhaseLayout.AMBIENT_PRESSURE
                -columnDensity(s.state(),port,pipe.volume())*GRAVITY*(graph.reservoirs().get(first?pipe.second():pipe.first()).elevation()-s.elevation());
        if(!(drive>0))return 0;
        double lo=0,hi=1;while(pipe.pressureDrop(hi,rho,mu)<drive&&hi<1e6)hi*=2;
        for(int j=0;j<50;j++){double mid=(lo+hi)/2;if(pipe.pressureDrop(mid,rho,mu)>drive)hi=mid;else lo=mid;}
        return saturated((lo+hi)/2,massFlowLimit(pipe,s.state(),port));
    }
    /** The mint ({@link PassiveNetwork#mintJunctions}): every junction's state from the balance on the island's own states
     * under the start modes, its round 0 the supplier composition ({@link #supplierSeeds}); null for every other node; a
     * junction nothing reaches keeps the state it was built with. */
    List<FluidThermodynamics.State> balanceStarts(PassiveNetwork graph) {
        // Round 0 from what reaches each junction ({@link #supplierSeeds}); the balance solves the pressures from there.
        var seeds=supplierSeeds(graph,()->{});var seeded=new ArrayList<PassiveNetwork.Reservoir>(graph.reservoirs().size());
        for(int i=0;i<graph.reservoirs().size();i++){var node=graph.reservoirs().get(i);seeded.add(node.junction()&&seeds.get(i)!=null&&seeds.get(i)!=node.state()?new PassiveNetwork.Reservoir(node.id(),node.elevation(),seeds.get(i),node.kind(),node.inventory()):node);}
        graph=new PassiveNetwork(seeded,graph.pipes(),graph.scheduledTransfers());
        var seededStates=graph.reservoirs().stream().map(PassiveNetwork.Reservoir::state).toList();
        suctionFloors=suctionFloors(graph,seededStates,()->{});readSuctions(graph,0);vapourLocked=vapourLocks(graph,seededStates);
        supplyGraph=graph;supplyDuration=0;supplyDensities=new double[graph.pipes().size()];Arrays.fill(supplyDensities,Double.NaN);
        var modes=initialModes(graph);boolean[] closed=new boolean[graph.pipes().size()];
        for(int i=0;i<closed.length;i++)if(graph.pipes().get(i).effectiveBlockedDirections()==3){closed[i]=true;if(!(graph.pipes().get(i).control() instanceof FlowControl.Passive))modes.set(i,FlowControl.Mode.CLOSED);}
        if(graph.pipes().stream().anyMatch(pipe->pipe.control() instanceof FlowControl.Mover)){boolean[] refused=InletPhase.decide(model,graph,null,0);for(int i=0;i<refused.length;i++)if(refused[i]){closed[i]=true;modes.set(i,FlowControl.Mode.INLET_WRONG_PHASE);}}
        closeDeadHeads(graph,closed,null);relieveSealedPockets(graph,modes,closed);
        var start=balanceStart(graph,modes,closed,null,()->{},null);
        var states=new ArrayList<FluidThermodynamics.State>();
        for(int i=0;i<graph.reservoirs().size();i++)states.add(graph.reservoirs().get(i).junction()?start.graph().reservoirs().get(i).state():null);
        return states;
    }
    /**
     * The balance start of one solve: rounds of (1) the quasi-steady balance of the island on its start states, the
     * minimum of the co-content (every junction's net inflow zero under every connection's own law: the loss inverted on
     * the drive in the donor's column, saturated at the donor's cap, nothing in a refused direction, a mover's rise or
     * prescribed flow, a filter's cake law) and (2) each junction's state as the upwind mixture of what the balanced flows
     * deliver, flashed at its balanced pressure (PH); then the law flow of every open connection at the balanced ends and,
     * where the balance converged, the passive connections the one-way law leaves nothing to carry. A starting point only:
     * no inventory moves.
     *
     * <p>A closure is read only off ends that balance. Cold fuzz seed 654 (B): a void placed at 101.3 kPa became the only
     * outlet of a run of three junctions at 99.0 kPa that two choked lines fed 0.10 kg/s, so the run must stand just over
     * the void and discharge into it. The balance ended unconverged with those 0.10 kg/s left in the run and its last
     * iterate still at 99.0 kPa, and the one-way law read off that iterate closed the void line for the solve. The run
     * then had no outlet, only inflows deep in saturation (slope about 1e-3) set its pressure, and every pass went to 13
     * to 15 MPa.
     */
    private Start balanceStart(PassiveNetwork graph,List<FlowControl.Mode> modes,boolean[] boundaryClosed,boolean[] keep,Runnable checkpoint) {return balanceStart(graph,modes,boundaryClosed,keep,checkpoint,graph);}
    /** {@link #balanceStart} with the junctions' buffers {@code buffers}: the solve's input graph; null for the mint, which has none yet. */
    private Start balanceStart(PassiveNetwork graph,List<FlowControl.Mode> modes,boolean[] boundaryClosed,boolean[] keep,Runnable checkpoint,PassiveNetwork buffers) {
        int edges=graph.pipes().size();
        var current=graph;boolean converged=true;BalanceNet net=null;double[] p=null;
        for(int round=0;round<Math.max(1,BALANCE_ROUNDS);round++) {
            net=new BalanceNet(current,buffers,modes,boundaryClosed,checkpoint,heldOpenings);
            p=net.solve(checkpoint);converged=net.converged;
            if(net.count==0&&net.anchored==0)break;
            var next=net.seeded(p,checkpoint);
            if(next==null)break;
            boolean moved=false;
            for(int i=0;i<next.reservoirs().size()&&!moved;i++)if(next.reservoirs().get(i).junction())moved=Math.abs(next.reservoirs().get(i).state().pressure()-current.reservoirs().get(i).state().pressure())>1e-6*current.reservoirs().get(i).state().pressure();
            current=next;
            if(!moved)break;
        }
        double[] law=new double[edges];boolean[] closed=new boolean[edges];
        for(int e=0;e<edges;e++) {
            var pipe=current.pipes().get(e);
            if(boundaryClosed[e]||modes.get(e)==FlowControl.Mode.CLOSED||modes.get(e)==FlowControl.Mode.INLET_WRONG_PHASE)continue;
            double pa=endPressure(current,pipe,pipe.first()),pb=endPressure(current,pipe,pipe.second());
            // A valve whose flow or opening the balance states starts at what the balance gave it, not at its pipe's unthrottled law.
            boolean stated=pipe.control() instanceof FlowControl.Valve&&(!Double.isNaN(net.prescribed[e])||!Double.isNaN(net.fixedOpenings[e]));
            law[e]=net.suctionLimited[e]?net.prescribed[e]:stated?net.flow(e,pa,pb):lawFlow(current,pipe,pa,pb);
            if(!(pipe.control() instanceof FlowControl.Passive)||keep!=null&&keep[e]||!converged)continue;
            // A passive connection the balanced ends can only drive the way its boundary forbids carries nothing this solve
            // (the closure the one-way estimate and the start closures stated by hand). Only ends that balance state it: an
            // unconverged balance's last iterate is no state of the island, and the closure holds for the whole solve.
            var a=current.reservoirs().get(pipe.first());var b=current.reservoirs().get(pipe.second());
            double dz=b.elevation()-a.elevation(),difference=pa-pb,band=reopenBand(pa,pb);
            double forward=difference-columnDensity(a.state(),pipe.firstPort(),pipe.volume())*GRAVITY*dz,reverse=difference-columnDensity(b.state(),pipe.secondPort(),pipe.volume())*GRAVITY*dz;
            boolean fwd=boundaryAllowed(current,pipe,1),rev=boundaryAllowed(current,pipe,-1);
            if(forward>band&&!fwd&&!(reverse<-band&&rev)||reverse<-band&&!rev&&!(forward>band&&fwd)){closed[e]=true;law[e]=0;}
        }
        return new Start(current,law,closed,List.copyOf(modes),converged);
    }
    /** The balance of one island on its start states (decision D12's one-way estimate promoted, with every connection's mode): the junction pressures
     * at which every junction's net inflow vanishes, and the junctions' upwind mixtures at those pressures. */
    final class BalanceNet {
        final PassiveNetwork graph;final List<FlowControl.Mode> modes;final boolean[] closed;final int edges,nodes;final double[] fixedOpenings;
        /** The junctions' buffers, the solve's input graph ({@link #seeded}); null for the mint, which has none yet. */
        final PassiveNetwork buffers;
        final int[] unknown;final double[] anchor;int count,anchored,iterations,sweeps;boolean converged;double finalResidual;
        final double[][] rho,mu,cap,column,endP,filterR;final boolean[][] allowed;final boolean[] gas,mover;final double[] reference,prescribed;
        /** Per connection, its pump's suction floor once worked out ({@link #suctionFloor}); NaN before. */
        final double[] floors;
        /** Per unknown junction, its bulk density and density times velocity limit as a function of its own pressure: the
         * PH equilibrium of what it holds at TABLE points spaced in ln P over the hull (a flashing LNG junction is vapour at
         * 1 atm and liquid at its bubble pressure, and its caps follow); null where the engine answers nothing. A junction
         * stated as one hydrocarbon liquid has a table only where it flashes inside the hull, from the hull's low end up to
         * its stated pressure, and reads it only below {@link #flashFrom}. */
        final double[][] tableP,tableRho,tableRhoV;static final int TABLE=5;
        /** Per unknown junction, the pressure from which it reads its stated data and not its table: the first table point
         * of a liquid junction that holds no vapour; infinite for a table of a junction stated two-phase or still flashing at
         * its stated pressure. A junction that stays there balances bit for bit as it would with no table. */
        final double[] flashFrom;
        /** The tables keyed by the junction state they were built for (a mint's state is the first step's), over one hull
         * ({low, top, flashFrom} in the fourth row); a liquid junction that does not flash keeps an entry with no table. */
        static final Map<FluidThermodynamics.State,double[][]> TABLE_CACHE=Collections.synchronizedMap(new WeakHashMap<>());
        /** Per pump connection, whether the balance put it on its suction limit: its suction junction is anchored at the
         * liquid's bubble pressure ({@link #suctionFloors}) and the pump passes what the suction line delivers there. */
        final boolean[] suctionLimited,onLaw;final int[][] nodeEdges;final double[][] lastQ;
        /** Closed for this solve by a start closure, a blocked way or a refused inlet, never to reopen; {@code closed} also holds
         * the device modes the balance may revise (a closed valve). */
        final boolean[] hardClosed;double rhoMax,rises,zLow=Double.POSITIVE_INFINITY,zHigh=Double.NEGATIVE_INFINITY;
        double low,high,scale;int band;
        BalanceNet(PassiveNetwork graph,PassiveNetwork buffers,List<FlowControl.Mode> modes,boolean[] boundaryClosed,Runnable checkpoint,double[] fixedOpenings) {
            this.fixedOpenings=fixedOpenings.length==graph.pipes().size()?fixedOpenings.clone():new double[graph.pipes().size()];if(fixedOpenings.length!=graph.pipes().size())Arrays.fill(this.fixedOpenings,Double.NaN);
            this.buffers=buffers;
            tableP=new double[graph.reservoirs().size()][];tableRho=new double[graph.reservoirs().size()][];tableRhoV=new double[graph.reservoirs().size()][];
            flashFrom=new double[graph.reservoirs().size()];Arrays.fill(flashFrom,Double.POSITIVE_INFINITY);
            suctionLimited=new boolean[graph.pipes().size()];onLaw=new boolean[graph.pipes().size()];nodeEdges=incidence(graph);lastQ=new double[graph.pipes().size()][2];
            this.graph=graph;this.modes=modes;edges=graph.pipes().size();nodes=graph.reservoirs().size();
            closed=new boolean[edges];hardClosed=new boolean[edges];unknown=new int[nodes];Arrays.fill(unknown,-1);anchor=new double[nodes];Arrays.fill(anchor,Double.NaN);
            rho=new double[edges][2];mu=new double[edges][2];cap=new double[edges][2];column=new double[edges][2];endP=new double[edges][2];filterR=new double[edges][2];
            allowed=new boolean[edges][2];gas=new boolean[nodes];mover=new boolean[edges];reference=new double[nodes];prescribed=new double[edges];Arrays.fill(prescribed,Double.NaN);floors=new double[edges];Arrays.fill(floors,Double.NaN);
            for(int i=0;i<nodes;i++){var node=graph.reservoirs().get(i);var s=node.state();reference[i]=s.pressure();gas[i]=node.junction()&&s.condensedVolume()==0&&s.pressure()>0;}
            low=Double.POSITIVE_INFINITY;high=Double.NEGATIVE_INFINITY;scale=0;
            for(int i=0;i<nodes;i++){zLow=Math.min(zLow,graph.reservoirs().get(i).elevation());zHigh=Math.max(zHigh,graph.reservoirs().get(i).elevation());}
            for(int e=0;e<edges;e++) {
                var pipe=graph.pipes().get(e);var mode=modes.get(e);
                hardClosed[e]=boundaryClosed[e]||pipe.effectiveBlockedDirections()==3||mode==FlowControl.Mode.INLET_WRONG_PHASE;
                closed[e]=hardClosed[e]||mode==FlowControl.Mode.CLOSED;
                if(!closed[e])prepare(e,checkpoint);
            }
            // The unknowns: junctions a node that holds its pressure reaches through pressure-coupling connections (a
            // prescribed flow couples nothing), numbered breadth first so the Jacobian is banded; an anchored junction is a
            // held node; a junction nothing reaches (a sealed pocket) keeps its pressure.
            number();
            for(int i=0;i<nodes;i++)if(!Double.isNaN(anchor[i])){low=Math.min(low,anchor[i]);high=Math.max(high,anchor[i]);}
            if(!(high>=low)){low=Double.NaN;high=Double.NaN;return;}
            double margin=rhoMax*GRAVITY*(zHigh-zLow)+1;low=Math.max(low-margin-rises,1e-3*high);high+=margin+rises;
            for(int j=0;j<nodes;j++) {
                if(unknown[j]<0)continue;var s=graph.reservoirs().get(j).state();if(!FluidThermodynamics.holdsFluid(s))continue;
                // W3b: any liquid, water's among them (a single liquid of half water or more is labelled aqueous).
                boolean vapour=FluidThermodynamics.holdsPhase(s,FluidThermodynamics.GAS),anyLiquid=FluidThermodynamics.holdsPhase(s,FluidThermodynamics.OIL)||FluidThermodynamics.holdsPhase(s,FluidThermodynamics.AQUEOUS),liquid=!vapour&&anyLiquid;
                if(!liquid&&!(vapour&&anyLiquid))continue;
                // A liquid is a liquid from its stated pressure up. Below its bubble pressure it flashes and its density falls
                // sixty times, which the balance must see: read as a liquid, the junctions under a 1.35 MPa LNG line with a
                // 1.25 MPa bubble point fell to 1 atm still liquid and 40 K superheated, and flashing them one by one stalled
                // Newton (cold seed 907, whose start the crystal competition's 2 K of heat had made two-phase).
                double top=liquid?Math.min(high,s.pressure()):high;if(!(top>low))continue;
                var cached=TABLE_CACHE.get(s);
                if(cached!=null&&cached[3][0]==low&&cached[3][1]==top){if(cached[1]!=null){tableP[j]=cached[0];tableRho[j]=cached[1];tableRhoV[j]=cached[2];flashFrom[j]=cached[3][2];}continue;}
                double[] held=PhaseLayout.totalAmounts(s);double h=s.enthalpy();double[] tp=new double[TABLE],tr=new double[TABLE],tv=new double[TABLE];boolean ok=true;double flash=Double.POSITIVE_INFINITY;
                for(int k=0;k<TABLE;k++)tp[k]=low*DeterministicMath.exp(DeterministicMath.log(top/low)*k/(TABLE-1));
                for(int k=0;k<TABLE&&ok&&flash==Double.POSITIVE_INFINITY;k++) {
                    // A two-phase junction reads the fluid phases first: with the crystals carried at every point cold seed 244
                    // held 20 times with a 12 s job, on the rows of junctions holding vapour and near-pure carbon dioxide at 1 atm
                    // (0 holds, 422 ms fluid-only). A point they refuse, the two-liquid window every LNG flash ends in, is carried:
                    // with no table a junction read its stated density at every pressure, and under a line opened to a void it
                    // fell from 980 to 225 kPa where its density carries the flow at about 1 MPa (cold seed 651's job 88 stalled).
                    try{var at=liquid?null:model.fluidPH(s.temperature(),tp[k],h,held.clone(),checkpoint);
                        if(at==null)at=model.equilibriumPH(s.temperature(),tp[k],h,held.clone(),checkpoint);
                        if(at==null||!FluidThermodynamics.holdsFluid(at)){ok=false;break;}
                        tr[k]=at.mass()/at.volume();tv[k]=tr[k]*model.velocityLimit(at);
                        if(liquid&&!FluidThermodynamics.holdsPhase(at,FluidThermodynamics.GAS)){flash=tp[k];for(int i=k+1;i<TABLE;i++){tr[i]=tr[k];tv[i]=tv[k];}}}
                    catch(IllegalArgumentException refused){ok=false;}
                }
                if(flash==tp[0]){TABLE_CACHE.put(s,new double[][]{tp,null,null,{low,top,flash}});continue;}
                if(ok){tableP[j]=tp;tableRho[j]=tr;tableRhoV[j]=tv;flashFrom[j]=flash;TABLE_CACHE.put(s,new double[][]{tp,tr,tv,{low,top,flash}});}
            }
        }
        /** The held data of open connection {@code e}: each end's density, viscosity, cap, column and end pressure, its one-way
         * rules and filter law, a mover's prescribed flow or rise, a regulating valve's anchor; the hull and scale grow with it. */
        private void prepare(int e,Runnable checkpoint) {
            var pipe=graph.pipes().get(e);var mode=modes.get(e);
            for(int dir=0;dir<2;dir++) {
                int donor=dir==0?pipe.first():pipe.second();var node=graph.reservoirs().get(donor);var port=pipe.portAt(donor);var s=node.state();
                rho[e][dir]=endDensity(s,port,pipe.volume());mu[e][dir]=endViscosity(s,port,pipe.volume());cap[e][dir]=massFlowLimit(pipe,s,port);
                column[e][dir]=columnDensity(s,port,pipe.volume());allowed[e][dir]=boundaryAllowed(graph,pipe,dir==0?1:-1);
                endP[e][dir]=endPressure(graph,pipe,donor);
                if(pipe.filter()!=null){double loading=pipe.filter().loading();filterR[e][dir]=pipe.filter().cleanResistance()*(carrierViscosity(s)/.001)*(1+99*loading*loading)/rho[e][dir];}
                rhoMax=Math.max(rhoMax,column[e][dir]);scale=Math.max(scale,cap[e][dir]);
                if(!node.junction()){low=Math.min(low,endP[e][dir]);high=Math.max(high,endP[e][dir]);}
            }
            if(pipe.control() instanceof FlowControl.Mover pump) {
                allowed[e][1]=false;var a=graph.reservoirs().get(pipe.first());
                // A pump that arrives on its suction limit is stated as the balance puts one there: its suction junction anchored at
                // the floor, the pump passing what arrives ({@link #solve} sets the flow once the island balances around it). Anchored
                // on the hydrocarbon floor alone, a water pump's suction was free again in the next round, the pump drew 72.6 kg/s
                // on its rise law and the junction fell to the hull's 101 Pa, stated as steam (PumpCavitationTest, starved suction).
                if(mode==FlowControl.Mode.PUMP_SUCTION_LIMIT&&a.junction()&&suctionFloor(e,checkpoint)<Double.POSITIVE_INFINITY){anchor[pipe.first()]=cavitationFloor(e,0);suctionLimited[e]=true;prescribed[e]=0;}
                else{mover[e]=true;onLaw[e]=mode==FlowControl.Mode.MOVER_LAW;}
                rises+=riseLimit(graph,e,rho[e][0],a.state().pressure());
            } else if(pipe.control() instanceof FlowControl.Valve valve) {
                if(!valve.bare())allowed[e][1]=false;
                if(mode==FlowControl.Mode.VALVE_REGULATING&&valve.pressureControl()&&graph.reservoirs().get(pipe.first()).junction())anchor[pipe.first()]=valve.targetPressure();
                else if(mode==FlowControl.Mode.VALVE_REGULATING&&!valve.pressureControl())prescribed[e]=valve.targetMassFlow(rho[e][0]);
            }
        }
        /** The suction limit of the pump on connection {@code e} with no flow ({@link PassiveStepSolver#suctionLimitPressure},
         * owner decision D14 as W3B_NETWORK_PLAN.md section 17 restates it: p_v and the clean zone's edge); infinite where it
         * has none. One statement for a pump the balance puts on its suction limit and for one that arrives there, worked out
         * once per connection. The NPSH the pump's flow adds is {@link #cavitationFloor}'s. The water curve D59 (4) put beside it where
         * the junction held free water (BalanceNet.waterFloor) was lifted by the owner on 2026-11-11: every liquid's floor
         * is at its own enthalpy ({@link PassiveStepSolver#releasePressure}), so water's is no longer a second one. */
        private double suctionFloor(int e,Runnable checkpoint) {
            if(!Double.isNaN(floors[e]))return floors[e];
            return floors[e]=suctionLimitPressure(graph,e,0);
        }
        /** Owner decision D20 Q11 A in the balance: the suction limit of pump connection {@code e}
         * ({@link PassiveStepSolver#suctionLimitPressure}) with its breakdown NPSH ({@link PassiveStepSolver#breakdown}) at mass flow {@code massFlow}
         * (kg/s) on its suction end's density: the flow the balance gives it, its law's at the balanced pressures where it
         * decides the mode, its prescribed flow on its suction limit, none where it arrives there. The Newton's row reads the
         * same floor at its trial flow ({@link PassiveStepSolver#suctionLimit}). */
        private double cavitationFloor(int e,double massFlow) {
            return suctionLimitPressure(graph,e,breakdown(graph,e,Math.max(0,massFlow)/rho[e][0]));
        }
        /** Owner decision D17 in the balance: the pressure the suction junction of pump connection {@code e}, on its suction
         * limit, is anchored at on the balance's pressures {@code p}: the lesser of its floor at its prescribed flow
         * ({@link #cavitationFloor}) and what
         * the supplier its suction walk ends at gives at no flow ({@link PassiveStepSolver#suctionSupply}, its static column
         * from the start state, as the balance holds every column). The Newton's row states the same on its trial
         * ({@link PassiveStepSolver#suctionLimit}). */
        private double suctionAnchor(int e,double[] p,Runnable checkpoint) {
            double floor=cavitationFloor(e,Double.isNaN(prescribed[e])?0:prescribed[e]);var walk=InletPhase.walk(graph,e);
            if(!(floor<Double.POSITIVE_INFINITY)||walk.via()<0)return floor;
            int s=walk.node();var link=graph.pipes().get(walk.via());
            double end=unknown[s]>=0?p[s]:!Double.isNaN(anchor[s])?anchor[s]:endPressure(graph,link,s);
            return Math.min(floor,suctionSupply(graph,e,s,walk.via(),end,graph.reservoirs().get(s).state()));
        }
        /** Whether junction {@code node} reads its table at {@code pressure}, not its stated data. */
        private boolean tabled(int node,double pressure){return tableP[node]!=null&&pressure<flashFrom[node];}
        /** Linear interpolation of {@code table} in ln P at {@code pressure}, clamped to the table's range. */
        private double at(int node,double[] table,double pressure) {
            double[] tp=tableP[node];if(pressure<=tp[0])return table[0];if(pressure>=tp[TABLE-1])return table[TABLE-1];
            int k=1;while(k<TABLE-1&&tp[k]<pressure)k++;
            double w=DeterministicMath.log(pressure/tp[k-1])/DeterministicMath.log(tp[k]/tp[k-1]);return table[k-1]+w*(table[k]-table[k-1]);
        }
        /** The unknowns: junctions a node that holds its pressure reaches through pressure-coupling connections, numbered breadth
         * first (a banded Jacobian); an anchored junction is a held node; a junction nothing reaches keeps its pressure. */
        private void number() {
            Arrays.fill(unknown,-1);anchored=0;band=0;
            var queue=new ArrayDeque<Integer>();boolean[] seen=new boolean[nodes];int n=0;
            for(int i=0;i<nodes;i++)if(!graph.reservoirs().get(i).junction()||!Double.isNaN(anchor[i])){seen[i]=true;queue.add(i);if(!Double.isNaN(anchor[i]))anchored++;}
            while(!queue.isEmpty()){int at=queue.remove();
                for(int e:nodeEdges[at]){if(closed[e]||!Double.isNaN(prescribed[e]))continue;var pipe=graph.pipes().get(e);int other=pipe.first()==at?pipe.second():pipe.first();
                    if(!seen[other]){seen[other]=true;unknown[other]=n++;queue.add(other);}}}
            count=n;
            for(int e=0;e<edges;e++){var pipe=graph.pipes().get(e);if(!closed[e]&&unknown[pipe.first()]>=0&&unknown[pipe.second()]>=0)band=Math.max(band,Math.abs(unknown[pipe.first()]-unknown[pipe.second()]));}
        }
        /** The net inflow of junction {@code node} through every open connection but {@code except}, at {@code p}. */
        private double inflowExcept(int node,int except,double[] p) {
            double net=0;
            for(int e:nodeEdges[node]){if(e==except||closed[e])continue;var pipe=graph.pipes().get(e);double q=flow(e,endAt(e,0,p),endAt(e,1,p));net+=pipe.second()==node?q:-q;}
            return net;
        }
        /** Whether no open connection of junction {@code node} carries anything at {@code p}: each is refused the way its drive
         * points, or has none (a sealed fitting, whose balance holds at its own pressure). */
        private boolean sealed(int node,double[] p) {
            for(int e:nodeEdges[node]){if(closed[e])continue;if(flow(e,endAt(e,0,p),endAt(e,1,p))!=0)return false;}
            return true;
        }
        private double scale(int node,double pressure){return unknown[node]>=0&&gas[node]?pressure/reference[node]:1;}
        /** The connection's flow at end pressures {@code pa}, {@code pb} (positive first to second): its law in the donor's
         * column and stream, saturated at the donor's cap, nothing in a refused direction; a mover's prescribed flow, or its
         * law with its rise one way. */
        double flow(int e,double pa,double pb) {
            if(closed[e]||!Double.isNaN(fixedOpenings[e])&&fixedOpenings[e]==0)return 0;
            if(!Double.isNaN(prescribed[e]))return prescribed[e];
            var pipe=graph.pipes().get(e);
            double dz=graph.reservoirs().get(pipe.second()).elevation()-graph.reservoirs().get(pipe.first()).elevation();
            int a=pipe.first(),b=pipe.second();double area=pipe.minimumArea();
            boolean tableA=tabled(a,pa);
            double rhoA=tableA?at(a,tableRho[a],pa):rho[e][0]*scale(a,pa),capA=tableA?at(a,tableRhoV[a],pa)*area:cap[e][0]*scale(a,pa),colA=tableA?rhoA:column[e][0]*scale(a,pa);
            double rise=mover[e]?riseLimit(graph,e,rhoA,pa):0;
            double forward=pa-pb-colA*GRAVITY*dz+rise;
            if(forward>0&&mover[e]&&onLaw[e])return allowed[e][0]?curveFlow(e,forward-rise,rhoA,pa,mu[e][0],capA):0;
            if(forward>0)return allowed[e][0]?runFlow(e,0,forward,rhoA,mu[e][0],capA):0;
            if(mover[e])return 0;
            boolean tableB=tabled(b,pb);
            double rhoB=tableB?at(b,tableRho[b],pb):rho[e][1]*scale(b,pb),capB=tableB?at(b,tableRhoV[b],pb)*area:cap[e][1]*scale(b,pb),colB=tableB?rhoB:column[e][1]*scale(b,pb);
            double reverse=pa-pb-colB*GRAVITY*dz;
            if(reverse<0)return allowed[e][1]?-runFlow(e,1,-reverse,rhoB,mu[e][1],capB):0;
            return 0;
        }
        private double runFlow(int e,int dir,double driving,double rho,double mu,double cap) {
            if(!(cap>0))return 0;
            if(filterR[e][dir]>0)return saturated(driving/filterR[e][dir],cap);
            var pipe=graph.pipes().get(e);
            // Newton on the law from the last flow found on this end (a bracket grows from it), bisection where a step leaves the bracket.
            // The loss rises strictly with the flow, so the root is unique and the start only saves iterations. The bracket ends
            // the search only once it is closed: while it is open above, 1e-14 of an infinite bracket is no width, and the test
            // ended every search started under its root after one laminar step (a 1 cm line under 2.57 MPa read 1 484 kg/s where
            // its law gives 1.536, so the balance's residual at fixed pressures moved with the history of starts, cold seed 743).
            double lo=0,hi=Double.POSITIVE_INFINITY,q=lastQ[e][dir]>0?lastQ[e][dir]:cap;
            for(int k=0;k<60;k++) {
                var loss=pipe.loss(q,rho,mu);double restriction=Double.isNaN(fixedOpenings[e])?0:.5/(rho*boreArea(pipe)*boreArea(pipe))*(1/(fixedOpenings[e]*fixedOpenings[e])-1);double f=loss.pressureDrop()+restriction*q*q-driving;
                if(Math.abs(f)<=1e-12*driving)break;
                if(f>0)hi=q;else lo=q;
                double next=q-f/(loss.massFlowDerivative()+2*restriction*q);
                if(hi==Double.POSITIVE_INFINITY)q=next>lo?next:2*q;else q=next>lo&&next<hi?next:.5*(lo+hi);
                if(hi<Double.POSITIVE_INFINITY&&hi-lo<=1e-14*hi)break;
            }
            lastQ[e][dir]=q;
            return saturated(q,cap);
        }
        /**
         * The forward flow of mover connection {@code e} on its own law ({@code MOVER_LAW}, batch 2026-11-03-rotating-machinery)
         * at static drive {@code drive} (Pa: the end pressures less the column), with the suction at {@code rho} and
         * {@code pressure}: the law flow q of {@code loss(q) - drive - rise(saturated(q)) = 0}, the Newton's row, whose rise
         * reads the flow the velocity cap lets through. Unique because the loss rises with the law flow and the rise falls.
         * Newton from the last flow found, bisection where a step leaves the bracket, as {@link #runFlow}.
         */
        private double curveFlow(int e,double drive,double rho,double pressure,double mu,double cap) {
            if(!(cap>0))return 0;
            var pipe=graph.pipes().get(e);
            double nominal=rho*((FlowControl.Mover)pipe.control()).targetVolumeFlow();
            double lo=0,hi=Double.POSITIVE_INFINITY,q=lastQ[e][0]>0?lastQ[e][0]:nominal>0?Math.min(cap,nominal):cap;
            for(int k=0;k<80;k++) {
                double moved=saturated(q,cap),rise=lawRise(graph,e,moved/rho,rho,pressure);
                var loss=pipe.loss(q,rho,mu);double f=loss.pressureDrop()-drive-rise;
                if(Math.abs(f)<=1e-12*Math.max(Math.abs(drive),Math.abs(rise)))break;
                if(f>0)hi=q;else lo=q;
                double next=q-f/(loss.massFlowDerivative()-lawRiseSlope(graph,e,moved/rho,rho,pressure)*saturationSlope(q,cap)/rho);
                if(hi==Double.POSITIVE_INFINITY)q=next>lo?next:2*q;else q=next>lo&&next<hi?next:.5*(lo+hi);
                if(hi<Double.POSITIVE_INFINITY&&hi-lo<=1e-14*hi)break;
            }
            lastQ[e][0]=q;
            return saturated(q,cap);
        }
        private double endAt(int e,int end,double[] p){var pipe=graph.pipes().get(e);int node=end==0?pipe.first():pipe.second();return unknown[node]>=0?p[node]:!Double.isNaN(anchor[node])?anchor[node]:endP[e][end];}
        double residual(double[] p,double[] residual,double[][] jacobian) {
            Arrays.fill(residual,0);
            for(int e=0;e<edges;e++) {
                if(closed[e])continue;var pipe=graph.pipes().get(e);int a=pipe.first(),b=pipe.second();
                double pa=endAt(e,0,p),pb=endAt(e,1,p),q=flow(e,pa,pb);
                if(unknown[a]>=0)residual[unknown[a]]-=q;
                if(unknown[b]>=0)residual[unknown[b]]+=q;
                if(jacobian==null)continue;
                for(int end=0;end<2;end++) {
                    int node=end==0?a:b,col=unknown[node];if(col<0)continue;
                    // The slope over 1e-9 of the end pressure: over 1e-7 (0.32 Pa at 3.16 MPa) a line between neighbouring junctions carrying
                    // 2 g/s across 6 mPa was crossed through its zero flow, so its slope was a secant across the column's dead band and no
                    // Newton step descended (cold seed 729: 124 balances ended unconverged, 117 on the sweep budget; 7 over 1e-9). The
                    // inversion resolves 1e-12 of a drive, so 1e-9 of the pressure is still a thousand times its noise.
                    double at=end==0?pa:pb,delta=1e-9*Math.max(1,Math.abs(at));
                    double dq=((end==0?flow(e,pa+delta,pb):flow(e,pa,pb+delta))-q)/delta;
                    if(unknown[a]>=0)jacobian[unknown[a]][col-unknown[a]+band]-=dq;
                    if(unknown[b]>=0)jacobian[unknown[b]][col-unknown[b]+band]+=dq;
                }
            }
            double merit=0;for(double r:residual)merit+=r*r;
            return .5*merit;
        }
        /**
         * One Gauss-Seidel sweep: each junction is bisected on the sign of its net inflow inside the hull, except a sealed one
         * ({@link #sealed}), which keeps its pressure (t56's trace of cold seed 743): a junction between closed lines and a
         * one-way supply it stands above has no inflow at any pressure above that supply's, and the bisection put it on the
         * edge of that flat range, a liquid-full fitting at 4.96 MPa seeded as vapour at the supply's 485 kPa, where no step
         * converged. A sealed fitting holds its fluid and its pressure.
         */
        void sweep(double[] p) {
            int[][] nodeEdges=incidence(graph);
            for(int node=0;node<nodes;node++) {
                if(unknown[node]<0||sealed(node,p))continue;
                double lo=low,hi=high;
                for(int k=0;k<30&&hi-lo>1e-9*high;k++) {
                    double mid=.5*(lo+hi),net=0;p[node]=mid;
                    for(int e:nodeEdges[node]){if(closed[e])continue;var pipe=graph.pipes().get(e);double q=flow(e,endAt(e,0,p),endAt(e,1,p));net+=pipe.second()==node?q:-q;}
                    if(net>0)lo=mid;else hi=mid;
                }
                p[node]=.5*(lo+hi);
            }
        }
        /** The junction pressures: a damped Newton on the banded Jacobian inside the hull, a Gauss-Seidel sweep where a
         * step does not descend; the last iterate when the budget runs out ({@code converged} false). */
        double[] solve(Runnable checkpoint) {
            double[] p=solveOnce(checkpoint);
            // The pump modes the balance starts the step in: a pump on its law whose suction junction stands below the bubble
            // pressure of the liquid it draws goes to its suction limit, the junction anchored there and the pump passing
            // what arrives. A vapour-locked pump (owner decision D16 A, {@link PassiveStepSolver#vapourLocks}) arrives closed
            // and the balance never reads one on its own pressures, which its law drained (D15's reading there started
            // cold random 181's pump closed with its supplier pulled 20 kPa below the floor).
            for(int round=0;round<4;round++) {
                boolean changed=false;
                for(int e=0;e<edges;e++) {
                    var pipe=graph.pipes().get(e);int a=pipe.first(),b=pipe.second();var mode=modes.get(e);
                    // Owner decision of 2026-10-19 (t46's trace of cold 271): the balance decides a pressure valve's mode on its balanced
                    // pressures as it decides a pump's, the pass loop's own transitions taken at the start. A closed back-pressure valve
                    // whose balanced inlet stands above its target with a forward drive regulates, its inlet anchored at the target (271:
                    // minted at 100.9 kPa the valve started closed, the closed network balanced at 1 125 kPa and pass 1 was singular);
                    // one that nothing reaches closes; one the open valve cannot relieve at its target opens; an open one whose inlet
                    // falls under the target regulates again.
                    if(pipe.control() instanceof FlowControl.Valve valve&&valve.pressureControl()&&graph.reservoirs().get(a).junction()&&!hardClosed[e]) {
                        double target=valve.targetPressure();
                        if(mode==FlowControl.Mode.CLOSED) {
                            double pa=endAt(e,0,p),pb=endAt(e,1,p),dz=graph.reservoirs().get(b).elevation()-graph.reservoirs().get(a).elevation();
                            double drive=pa-pb-columnDensity(graph.reservoirs().get(a).state(),pipe.firstPort(),pipe.volume())*GRAVITY*dz;
                            if(pa>target+.01&&drive>.01){closed[e]=false;modes.set(e,FlowControl.Mode.VALVE_REGULATING);prepare(e,checkpoint);anchor[a]=target;changed=true;}
                        } else if(mode==FlowControl.Mode.VALVE_REGULATING) {
                            double inflow=inflowExcept(a,e,p),pass=flow(e,target,endAt(e,1,p));
                            if(inflow<=0){modes.set(e,FlowControl.Mode.CLOSED);closed[e]=true;anchor[a]=Double.NaN;changed=true;}
                            else if(inflow>pass*(1+1e-9)){modes.set(e,FlowControl.Mode.VALVE_OPEN);anchor[a]=Double.NaN;changed=true;}
                        } else if(mode==FlowControl.Mode.VALVE_OPEN) {
                            double pa=endAt(e,0,p);
                            if(pa<target-.01&&flow(e,pa,endAt(e,1,p))>1e-10){modes.set(e,FlowControl.Mode.VALVE_REGULATING);anchor[a]=target;changed=true;}
                        }
                        continue;
                    }
                    if(closed[e]||!(pipe.control() instanceof FlowControl.Mover))continue;
                    // The floor at the flow its law gives at the balanced pressures (owner decision D20 Q11 A).
                    double pa=endAt(e,0,p),floor=suctionFloor(e,checkpoint);
                    boolean onLawWithFloor=mode==FlowControl.Mode.MOVER_LAW&&graph.reservoirs().get(a).junction()&&floor<Double.POSITIVE_INFINITY;
                    if(onLawWithFloor)floor=cavitationFloor(e,flow(e,pa,endAt(e,1,p)));
                    boolean below=onLawWithFloor&&pa<floor*(1-1e-9);
                    if(below) {
                        modes.set(e,FlowControl.Mode.PUMP_SUCTION_LIMIT);anchor[a]=floor;p[a]=floor;mover[e]=false;suctionLimited[e]=true;prescribed[e]=Math.max(0,inflowExcept(a,e,p));changed=true;
                    } else if(suctionLimited[e]) {
                        // Owner decision D17: the anchor never stands above the supplier, read on this balance's pressures,
                        // where the pump is on its limit and its law drains nothing; the mode above reads the floor alone.
                        double at=suctionAnchor(e,p,checkpoint);
                        if(Math.abs(at-anchor[a])>1e-9*Math.abs(at)){anchor[a]=at;p[a]=at;changed=true;}
                        double q=Math.max(0,inflowExcept(a,e,p));if(Math.abs(q-prescribed[e])>1e-9*Math.max(1,q)){prescribed[e]=q;changed=true;}
                    }
                }
                if(!changed)break;
                number();p=solveOnce(checkpoint);
            }
            return p;
        }
        private double[] solveOnce(Runnable checkpoint) {
            double[] p=new double[nodes];
            for(int i=0;i<nodes;i++)p[i]=!Double.isNaN(anchor[i])?anchor[i]:graph.reservoirs().get(i).state().pressure();
            if(count==0||Double.isNaN(low)){converged=true;return p;}
            for(int i=0;i<nodes;i++)if(unknown[i]>=0)p[i]=Math.clamp(p[i],low,high);
            double[] residual=new double[count];double merit=residual(p,residual,null);
            // 1e-6 of the island's largest cap flow: a start, above the floor of a Newton on finite-difference slopes (1e-9 relative).
            double tolerance=1e-6*Math.max(1,scale);
            for(int iteration=0;iteration<30;iteration++) {
                checkpoint.run();finalResidual=maximum(residual);
                if(finalResidual<=tolerance){converged=true;return p;}
                iterations++;
                double[][] jacobian=new double[count][2*band+1];residual(p,new double[count],jacobian);
                double floor=0;for(int i=0;i<count;i++)floor=Math.max(floor,Math.abs(jacobian[i][band]));floor=Math.max(floor*1e-12,Double.MIN_NORMAL);
                for(int i=0;i<count;i++)if(Math.abs(jacobian[i][band])<floor)jacobian[i][band]=-floor;
                double[] step=solveBanded(jacobian,band,residual);boolean accepted=false;double largest=0,took=0;
                if(step!=null) {
                    for(double s:step)largest=Math.max(largest,Math.abs(s));
                    double limit=Math.min(1,.5*(high-low)/Math.max(largest,Double.MIN_NORMAL));
                    double[] trial=p.clone(),trialResidual=new double[count];
                    for(double fraction=limit;fraction>1e-3*limit;fraction*=.5) {
                        for(int i=0;i<nodes;i++)if(unknown[i]>=0)trial[i]=Math.clamp(p[i]-fraction*step[unknown[i]],low,high);
                        double trialMerit=residual(trial,trialResidual,null);
                        if(trialMerit<(1-1e-4*fraction)*merit){System.arraycopy(trial,0,p,0,nodes);System.arraycopy(trialResidual,0,residual,0,count);merit=trialMerit;accepted=true;took=fraction;break;}
                    }
                }
                // Where the Newton step does not descend (a cap or a one-way kink), one Gauss-Seidel sweep, kept only where it
                // improves on the Newton's point: a sweep across a choked junction's flat range can walk away from a root the
                // Newton has all but reached, and then neither method improves on the other.
                if(!accepted){if(++sweeps>12)break;double[] was=p.clone(),wasResidual=residual.clone();double wasMerit=merit;sweep(p);merit=residual(p,residual,null);
                    if(!(merit<wasMerit)){System.arraycopy(was,0,p,0,nodes);System.arraycopy(wasResidual,0,residual,0,count);merit=wasMerit;break;}
                    double moved=0;for(int i=0;i<nodes;i++)if(unknown[i]>=0)moved=Math.max(moved,Math.abs(p[i]-was[i])/Math.max(1,Math.abs(was[i])));
                    if(moved<1e-12)break;}
            }
            finalResidual=maximum(residual);converged=finalResidual<=tolerance;
            return p;
        }
        /**
         * The graph with every balanced junction at its pressure and the upwind mixture of what the balanced flows deliver (PH
         * at that pressure); null when nothing moved. A junction nothing reaches mixes only its own buffer in its row, so it is
         * stated as that buffer let down at its own enthalpy (PH; {@link #held}); at the mint, which has no buffer yet, at its
         * temperature. t56b's trace of the whole cold seed 743 world: a sealed junction holding 2.25 kg of LNG and CO2 at
         * 4.96 MPa and 200.4 K, put on its 485 kPa supply's pressure, was restated at its old temperature, as vapour holding
         * about 1 MJ more than its buffer (0.34 m3), so its energy row had no root and every step stalled; at its own enthalpy
         * it is a two-phase mixture at 165 K, as the real liquid flashes.
         */
        PassiveNetwork seeded(double[] p,Runnable checkpoint) {
            double[] q=new double[edges];
            for(int e=0;e<edges;e++)q[e]=closed[e]?0:flow(e,endAt(e,0,p),endAt(e,1,p));
            var states=new ArrayList<FluidThermodynamics.State>(nodes);for(var node:graph.reservoirs())states.add(node.state());
            int count=model.hydrocarbon.componentCount()+1;boolean any=false;
            for(int sweep=0;sweep<=nodes;sweep++) {
                boolean changed=false;
                for(int j=0;j<nodes;j++) {
                    var node=graph.reservoirs().get(j);if(!node.junction()||unknown[j]<0&&Double.isNaN(anchor[j]))continue;
                    double pressure=!Double.isNaN(anchor[j])?anchor[j]:p[j];
                    double[] amounts=new double[count];double mass=0,heat=0,weighted=0,tLow=Double.POSITIVE_INFINITY,tHigh=Double.NEGATIVE_INFINITY;
                    for(int e=0;e<edges;e++) {
                        var pipe=graph.pipes().get(e);double flow=q[e];
                        if(!(Math.abs(flow)>0)||(flow>=0?pipe.second():pipe.first())!=j)continue;
                        int donor=flow>=0?pipe.first():pipe.second();var stream=states.get(donor);var port=pipe.drawPort(flow);
                        double rate=Math.abs(flow),streamMass=endMass(stream,port,pipe.volume());double[] moles=endMoles(stream,port,pipe.volume());
                        if(!(streamMass>0))continue;
                        for(int c=0;c<count;c++)amounts[c]+=rate*moles[c]/streamMass;
                        var through=delivered(graph,pipe,endSpecificEnthalpy(stream,port,pipe.volume()),stream.temperature(),flow,checkpoint);
                        mass+=rate;heat+=rate*through[0];weighted+=rate*through[1];
                        tLow=Math.min(tLow,through[1]);tHigh=Math.max(tHigh,through[1]);
                    }
                    var old=states.get(j);FluidThermodynamics.State next;
                    try {
                        // A junction that already holds the delivered mixture at that pressure is not flashed again (the second
                        // round's unchanged junctions, a structure balanced once more): the flashes were the balance's cost.
                        if(mass>0) {
                            next=holdsMixture(old,amounts,mass,heat,pressure)?null:mixed(amounts,mass,heat,weighted/mass,tLow,tHigh,pressure,checkpoint);
                            // At the junction's own scale (the mixture is stated per kilogram): the solid event integrator reads a junction's
                            // populations against its state's volume, and the rate seed writes the state back into the step's graph.
                            if(next!=null){double oldFluid=old.mass()-old.solidMoments().mass(),newFluid=next.mass()-next.solidMoments().mass();if(oldFluid>0&&newFluid>0)next=scaled(next,oldFluid/newFluid);}
                        }
                        else if(!(Math.abs(pressure-old.pressure())>1e-9*old.pressure()))next=null;
                        else if(buffers==null)next=model.flashTP(old.temperature(),pressure,PhaseLayout.totalAmounts(old),checkpoint);
                        else{next=held(buffers.reservoirs().get(j).state(),pressure,checkpoint);
                            if(next!=null){double oldFluid=old.mass()-old.solidMoments().mass(),newFluid=next.mass()-next.solidMoments().mass();if(oldFluid>0&&newFluid>0)next=scaled(next,oldFluid/newFluid);}}
                    } catch(IllegalArgumentException refused){next=null;}
                    if(next==null)continue;
                    // The junction's solids ride its fluid: the moments scale with the fluid mass the mixture is stated for.
                    double oldFluid=old.mass()-old.solidMoments().mass(),ratio=oldFluid>0?next.mass()/oldFluid:1;var m=old.solidMoments();
                    next=next.withSolidState(old.solids(),new SolidInventory.Moments(m.mass()*ratio,m.volume()*ratio,m.heatCapacity()*ratio));
                    if(!sameStates(List.of(old),List.of(next))){states.set(j,next);changed=true;any=true;}
                }
                if(!changed)break;
            }
            if(!any)return null;
            var reservoirs=new ArrayList<PassiveNetwork.Reservoir>(nodes);
            for(int i=0;i<nodes;i++){var node=graph.reservoirs().get(i);reservoirs.add(states.get(i)==node.state()?node:new PassiveNetwork.Reservoir(node.id(),node.elevation(),states.get(i),node.kind(),node.inventory()));}
            return new PassiveNetwork(reservoirs,graph.pipes(),graph.scheduledTransfers());
        }
        /** {@code buffer}'s fluid at {@code pressure} at its own enthalpy (PH), per kilogram; null where it holds no fluid. */
        private FluidThermodynamics.State held(FluidThermodynamics.State buffer,double pressure,Runnable checkpoint) {
            double mass=buffer.mass()-buffer.solidMoments().mass();if(!(mass>0))return null;
            double heat=buffer.enthalpy()-buffer.solidMoments().enthalpy(buffer.temperature(),buffer.pressure());
            return mixed(PhaseLayout.totalAmounts(buffer),mass,heat,buffer.temperature(),buffer.temperature(),buffer.temperature(),pressure,checkpoint);
        }
        /** Whether {@code old} already is the delivered mixture ({@code amounts} and {@code heat} over {@code mass}) at {@code pressure},
         * within 1e-6 of each per-kilogram amount, the specific enthalpy and the pressure: a start guess needs no closer a flash. */
        private boolean holdsMixture(FluidThermodynamics.State old,double[] amounts,double mass,double heat,double pressure) {
            double oldMass=old.mass()-old.solidMoments().mass();if(!(oldMass>0))return false;
            if(Math.abs(old.pressure()-pressure)>1e-6*pressure||Math.abs(old.enthalpy()/oldMass-heat/mass)>1e-6*Math.max(1,Math.abs(heat/mass)))return false;
            double[] n=PhaseLayout.totalAmounts(old);double total=0;for(int c=0;c<amounts.length;c++)total+=amounts[c]/mass;
            for(int c=0;c<amounts.length;c++)if(Math.abs(n[c]/oldMass-amounts[c]/mass)>1e-6*total)return false;
            return true;
        }
        /** The mixture per kilogram at {@code pressure} with the delivered specific enthalpy: the engine's PH answer, else a
         * temperature bisection inside the streams' window (20 K either side, inside the numerical box: no data range narrows a seed, owner rule of 2026-10-16).
         * The bisection ends on the bracket side whose state is nearer the delivered enthalpy. Water alone (no engine answer) has
         * no two-phase TP state at its own vapour pressure: its enthalpy jumps by the latent heat across the saturation, and the
         * midpoint's side is round-off. A pump suction the balance holds at its own liquid's vapour pressure ({@link PassiveStepSolver#releasePressure})
         * is delivered the saturated liquid's enthalpy to round-off: the liquid side is that round-off away, the steam side the
         * latent heat. Bundled fuzz seed 889, while the floor read the junction's stated temperature: water arriving 0.06 K
         * warmer from the friction of its 309 kPa descent (1e-4 of it flashes there) was stated all steam, and the step's
         * junction held a steam stock no pass could empty. */
        private FluidThermodynamics.State mixed(double[] amounts,double mass,double heat,double tMix,double tLow,double tHigh,double pressure,Runnable checkpoint) {
            double[] y=new double[amounts.length];for(int c=0;c<y.length;c++)y[c]=amounts[c]/mass;
            double hydrocarbon=0;for(int c=0;c<y.length-1;c++)hydrocarbon+=y[c];
            var answer=hydrocarbon>0?model.equilibriumPH(tMix,pressure,heat/mass,y,checkpoint):null;
            if(answer!=null&&FluidThermodynamics.holdsFluid(answer))return model.folded(answer,true);
            double lower=Math.max(tLow-20,NumericalBox.MINIMUM_TEMPERATURE),upper=Math.min(tHigh+20,NumericalBox.MAXIMUM_TEMPERATURE);
            for(int k=0;k<60&&upper-lower>1e-9;k++) {
                double t=.5*(lower+upper);var point=model.flashTP(t,pressure,y.clone(),checkpoint);
                if(point.enthalpy()/point.mass()<heat/mass)lower=t;else upper=t;
            }
            var below=model.flashTP(lower,pressure,y.clone(),checkpoint);var above=model.flashTP(upper,pressure,y.clone(),checkpoint);
            double target=heat/mass;
            return model.folded(Math.abs(below.enthalpy()/below.mass()-target)<=Math.abs(above.enthalpy()/above.mass()-target)?below:above,true);
        }
    }
    // ======== end of the t44 balance ========
    private static int[][] incidence(PassiveNetwork graph) {
        int nodes=graph.reservoirs().size();int[] degree=new int[nodes];
        for(var pipe:graph.pipes()){degree[pipe.first()]++;degree[pipe.second()]++;}
        int[][] nodeEdges=new int[nodes][];for(int i=0;i<nodes;i++)nodeEdges[i]=new int[degree[i]];Arrays.fill(degree,0);
        for(int e=0;e<graph.pipes().size();e++){var pipe=graph.pipes().get(e);nodeEdges[pipe.first()][degree[pipe.first()]++]=e;nodeEdges[pipe.second()][degree[pipe.second()]++]=e;}
        return nodeEdges;
    }
    /**
     * Whether, with connection {@code edge} left out, junction {@code node} lies in a sealed pocket: the junctions it reaches
     * through pressure-coupling connections ({@link #couples}) touch no node that is not a junction, no regulating valve
     * draws from them, and every other connection leaving them is closed. Whatever {@code edge} carries into such a pocket
     * its mass rows send back out through {@code edge} alone, so {@code edge} carries exactly zero at every root.
     */
    static boolean sealedWithout(PassiveNetwork graph,int[][] nodeEdges,List<FlowControl.Mode> modes,boolean[] boundaryClosed,int node,int edge) {
        return sealedWithout(graph,nodeEdges,modes,boundaryClosed,node,edge,0);
    }
    /**
     * {@link #sealedWithout} for a mover's side (owner decision D58, A4): with {@code relief} 1 the pocket must let flow
     * out (a mover's discharge), with -1 it must let flow in (its suction), and a connection whose one-way rules
     * ({@link #boundaryAllowed}) forbid that direction counts as closed. With 0 every open connection counts, either way.
     */
    static boolean sealedWithout(PassiveNetwork graph,int[][] nodeEdges,List<FlowControl.Mode> modes,boolean[] boundaryClosed,int node,int edge,int relief) {
        if(!graph.reservoirs().get(node).junction())return false;
        var members=new HashSet<Integer>();var queue=new ArrayDeque<Integer>();members.add(node);queue.add(node);
        while(!queue.isEmpty()) {
            int at=queue.remove();
            for(int e:nodeEdges[at]) {
                if(e==edge)continue;var mode=modes.get(e);var p=graph.pipes().get(e);
                // A closed flow valve with a target opens to the head on it (nextMode): it seals no discharge.
                if(relief>0&&mode==FlowControl.Mode.CLOSED&&!boundaryClosed[e]&&p.first()==at&&flowValve(p)&&p.control() instanceof FlowControl.Valve valve&&valve.targetMassFlow(1)>0)return false;
                if(closedEdge(mode,boundaryClosed[e]))continue;
                int other=p.first()==at?p.second():p.first();
                if(relief!=0&&other!=at) {
                    double flow=(p.first()==at?1:-1)*relief;
                    if((p.control() instanceof FlowControl.Mover||p.control() instanceof FlowControl.Valve v&&!v.bare())&&flow<0||!boundaryAllowed(graph,p,flow))continue;
                }
                if(!couples(mode,boundaryClosed[e])){if(members.contains(other)&&!anchors(p,mode))continue;return false;}
                if(!graph.reservoirs().get(other).junction())return false;
                if(members.add(other))queue.add(other);
            }
        }
        return true;
    }
    private static double maximum(double[] values){double m=0;for(double v:values)m=Math.max(m,Math.abs(v));return m;}
    /** Banded Gaussian elimination without pivoting ({@code a} in band storage of half-width {@code band}, overwritten):
     * the solution of {@code a x = b}, or null at a pivot that vanishes against its row. The balance's Jacobian is a
     * weighted graph Laplacian (diagonally dominant up to the gas scaling), which needs no pivoting. */
    private static double[] solveBanded(double[][] a,int band,double[] rightHandSide) {
        int n=rightHandSide.length;double[] b=rightHandSide.clone();
        for(int column=0;column<n;column++) {
            double pivot=a[column][band],size=0;
            for(int k=0;k<=2*band;k++)size=Math.max(size,Math.abs(a[column][k]));
            if(!(Math.abs(pivot)>1e-14*size))return null;
            for(int row=column+1;row<=Math.min(n-1,column+band);row++) {
                double factor=a[row][column-row+band]/pivot;if(factor==0)continue;
                for(int k=column;k<=Math.min(n-1,column+band);k++)a[row][k-row+band]-=factor*a[column][k-column+band];
                b[row]-=factor*b[column];
            }
        }
        double[] x=new double[n];
        for(int row=n-1;row>=0;row--){double sum=b[row];for(int k=row+1;k<=Math.min(n-1,row+band);k++)sum-=a[row][k-row+band]*x[k];x[row]=sum/a[row][band];}
        return x;
    }
    /**
     * Whether the static driving pressure {@code P_a - P_b - rho g (z_b - z_a)} has the sign of {@code flow} with rho =
     * either endpoint's density. {@link #initialMassFlow} states the row's column ({@link #columnFromFirst}), and where
     * the pipe could stand full of either fluid that is the column of the end with the higher pressure: on a falling water
     * line into a nitrogen tank that is the tank's gas once the tank passes the generator's pressure, so the estimate read
     * a 4 m water column as nitrogen and reported a 4.8 kg/s backflow while the column still drove 4.2 kPa forward, and
     * the connection stayed closed for good. Between two gas endpoints both densities give the same sign.
     */
    private boolean illegalWithEitherDensity(PassiveNetwork graph,PassiveNetwork.Pipe pipe,double flow) {
        var a=graph.reservoirs().get(pipe.first());var b=graph.reservoirs().get(pipe.second());
        double dp=endPressure(a,pipe.firstPort(),a.state())-endPressure(b,pipe.secondPort(),b.state()),dz=b.elevation()-a.elevation();
        // Each end's density is what its own port stands in the connection (decision D34, {@link #columnDensity}).
        double drivingFirst=dp-columnDensity(a.state(),pipe.firstPort(),pipe.volume())*GRAVITY*dz;if(!(Math.signum(drivingFirst)==Math.signum(flow)))return false;
        double drivingSecond=dp-columnDensity(b.state(),pipe.secondPort(),pipe.volume())*GRAVITY*dz;if(!(Math.signum(drivingSecond)==Math.signum(flow)))return false;
        return true;
    }
    /**
     * Whether a connection may carry {@code flow}'s direction: not blocked that way, not out of a void or into a generator,
     * not out of a dry end ({@link #dryEnd}). A phase port never refuses a direction for want of its first phase (decision
     * D11): a vessel that lacks it draws the next one of the port's priority order ({@link PhaseDraw}).
     */
    /** Whether a device's forward flow (first end to second) is one its ends forbid: into a generator, or out of a void. */
    private static boolean forwardForbidden(PassiveNetwork graph,PassiveNetwork.Pipe pipe) {
        return graph.reservoirs().get(pipe.second()).kind()==PassiveNetwork.NodeKind.GENERATOR||graph.reservoirs().get(pipe.first()).kind()==PassiveNetwork.NodeKind.VOID;
    }
    static boolean boundaryAllowed(PassiveNetwork graph,PassiveNetwork.Pipe pipe,double flow) {
        var a=graph.reservoirs().get(pipe.first()).kind();var b=graph.reservoirs().get(pipe.second()).kind();
        return !pipe.blocked(flow)&&!(flow<0&&(a==PassiveNetwork.NodeKind.GENERATOR||b==PassiveNetwork.NodeKind.VOID)
                ||flow>0&&(b==PassiveNetwork.NodeKind.GENERATOR||a==PassiveNetwork.NodeKind.VOID))
                &&!outOfDryEnd(graph,pipe,flow)&&!againstTower(graph,pipe,flow);
    }
    /** A tower's ends are air breaks: water falls in through its spray inlet alone, and never leaves by it. */
    private static boolean againstTower(PassiveNetwork graph,PassiveNetwork.Pipe pipe,double flow) {
        if(flow==0)return false;
        var donor=graph.reservoirs().get(flow>0?pipe.first():pipe.second());var receiver=graph.reservoirs().get(flow>0?pipe.second():pipe.first());
        return spray(donor,flow>0?pipe.firstPort():pipe.secondPort())||receiver.inventory().tower()!=null&&!spray(receiver,flow>0?pipe.secondPort():pipe.firstPort());
    }
    /**
     * Whether {@code flow} leaves its connection through a dry end ({@link #dryEnd}). Such an end gives nothing in the step:
     * a larger flow is a forbidden direction ({@link #boundaryAllowed}), and one within that rule's tolerance, 1e-10 kg/s,
     * is no flow, at a converged point and at a polished one alike (an empty tank looped twice to one junction converged
     * with 1.6e-22 kg/s round the loop, and the booking ended it at zero at every step size).
     */
    static boolean outOfDryEnd(PassiveNetwork graph,PassiveNetwork.Pipe pipe,double flow) {
        return flow>0&&dryEnd(graph.reservoirs().get(pipe.first()),pipe.firstPort())||flow<0&&dryEnd(graph.reservoirs().get(pipe.second()),pipe.secondPort());
    }
    /**
     * Decision D54 (every vessel a bag with a stiff wall): an end with nothing behind it to draw. A vessel that holds no
     * fluid ({@link #holdsNoFluid}: placed empty, or holding only solids, which ride the liquid, decision D33) has nothing
     * to carry anything out, so all its ports are dry; a top (VAPOR) port of a slack vessel that holds no gas is dry too:
     * what is above the liquid is the atmosphere the model does not carry. An OVERFLOW port is dry while the vessel holds
     * no liquid above its fill height at the step start ({@link OpenVessel#overflows}): its nozzle stands above the level.
     */
    static boolean dryEnd(PassiveNetwork.Reservoir node,PassiveNetwork.PhasePort port) {
        if(node.kind()!=PassiveNetwork.NodeKind.RESERVOIR&&node.kind()!=PassiveNetwork.NodeKind.PORT)return false;
        if(holdsNoFluid(node))return true;
        if(port==PassiveNetwork.PhasePort.OVERFLOW)return !OpenVessel.overflows(node);
        if(port==PassiveNetwork.PhasePort.BOOT_HC&&!FluidThermodynamics.holdsPhase(node.state(),FluidThermodynamics.OIL)&&!FluidThermodynamics.holdsPhase(node.state(),FluidThermodynamics.GAS))
            return FluidThermodynamics.phaseVolume(node.state(),FluidThermodynamics.AQUEOUS)<=node.inventory().geometry().weirVolume();
        return port==PassiveNetwork.PhasePort.VAPOR&&!FluidThermodynamics.holdsPhase(node.state(),FluidThermodynamics.GAS)
                &&!PhaseLayout.taut(node.state(),node.inventory().wallCapacity());
    }
    /**
     * A vessel, or its rate-solve copy, that holds no fluid (decision D54): its fluid, the bag less its solids, at most
     * the bag's resolution, {@link #HOLDS_NOTHING} of its capacity (owner decision D59 (2)); below it a vessel's fluid
     * has no pressure of its own, typically a residue a junction's buffer leaves in an emptied tank (1.3e-71 kg,
     * 2.2e-17 kg) or the first drops a trickle delivers (7e-12 kg). Solids ride
     * the liquid and have no fluid to exchange with, so whatever solids it holds such a vessel is neither feed nor stock:
     * an end of it gives nothing ({@link #dryEnd}), its column is the other end's, a junction seed's sweep passes through
     * it, the rest test does not count it, a step lays it out dry ({@link #dry}), and the booking states it at its flash
     * with no closure ({@link InventoryEquilibrium#solve}). The residue stays booked in its inventory (the ledger is exact,
     * D46). A mover's dry suction keeps its port band ({@link InletPhase}).
     */
    /**
     * The heat (W) a kettle's pool takes into vessel {@code node} over a step, on the step's start state ({@link Pool#heat},
     * batch 2026-10-27-heat-exchanger-design, W7): 0 for every node without a pool, for one standing as a junction (an
     * emptying vessel, decision D54) and for a vessel that holds no fluid. The step's energy rows, the reconstruction's
     * booking ({@code ConservativeTransport}) and the rest test all read it.
     */
    public static double poolHeat(PassiveNetwork.Reservoir node) {
        var pool=node.inventory().pool();
        return pool==null||node.kind()!=PassiveNetwork.NodeKind.RESERVOIR||holdsNoFluid(node)?0:pool.heat(node.state(),node.inventory().geometry());
    }
    /**
     * The vapour (kg/s) a kettle's pool boils in vessel {@code node} over a step (W7): its heat ({@link #poolHeat}) over the
     * latent heat of the start state's liquid into its gas; 0 unless the vessel holds gas over its liquid and the pool heats
     * it. The phase draw counts it as gas the vessel holds within the step ({@code capacities}), as it counts an open vessel's
     * release out: a vapour port draws the pool's boil-up, not its liquid.
     */
    double boilUp(PassiveNetwork.Reservoir node) {
        double heat=poolHeat(node);var s=node.state();
        if(!(heat>0)||!FluidThermodynamics.holdsPhase(s,FluidThermodynamics.GAS))return 0;
        double liquid=0,enthalpy=0;
        for(int phase:new int[]{FluidThermodynamics.OIL,FluidThermodynamics.AQUEOUS})if(FluidThermodynamics.holdsPhase(s,phase)){
            double mass=model.phaseMass(s,phase);liquid+=mass;enthalpy+=mass*model.phaseSpecificEnthalpy(s,phase,null);}
        if(!(liquid>0))return 0;
        double latent=model.phaseSpecificEnthalpy(s,FluidThermodynamics.GAS,null)-enthalpy/liquid;
        return latent>0?heat/latent:0;
    }
    static boolean holdsNoFluid(PassiveNetwork.Reservoir node) {
        if(node.kind()!=PassiveNetwork.NodeKind.RESERVOIR&&node.kind()!=PassiveNetwork.NodeKind.PORT)return false;
        return holdsNoFluid(node.inventory(),node.state());
    }
    /** {@link #holdsNoFluid(PassiveNetwork.Reservoir)} for a vessel's {@code inventory} at {@code state}. */
    static boolean holdsNoFluid(PassiveNetwork.Inventory inventory,FluidThermodynamics.State state) {
        boolean none=true;for(double n:inventory.moles())if(n!=0){none=false;break;}
        if(none)return true;
        // A pure-water basin exchanges at its surface until its actual inventory is empty.
        if(inventory.tower()!=null)return false;
        double capacity=inventory.volume(),bag=PhaseLayout.bagVolume(state,inventory.wallCapacity());
        return (inventory.solids().empty()?bag:bag-inventory.solids().volume())<=HOLDS_NOTHING*capacity;
    }
    /**
     * A vessel a step lays out dry when nothing arrives in it (decision D54) and books nothing for when nothing reaches
     * it: one that holds no fluid at the bag's resolution (owner decision D59 (2)), whatever solids it holds. A residue
     * below it has no phases worth stating (a 4e-33 kg crude residue laid out as two phases stalled the line search at
     * every step size), and solids without a fluid neither move nor change in a step: a bed of them has no unknowns until
     * a fluid arrives.
     */
    static boolean holdsNoFluidVessel(PassiveNetwork.Reservoir node) {
        return node.kind()==PassiveNetwork.NodeKind.RESERVOIR&&holdsNoFluid(node);
    }
    /**
     * The bag's resolution as a share of its capacity: the share below which a vessel holds no fluid (D59 (2), A311).
     * A millilitre in a cubic metre (t38 form C, 2026-10-18; 1e-12 before, the roundoff at which {@link PhaseLayout#bagVolume}
     * snaps): a first arrival of 7e-12 kg in an empty tank was laid out with two phases and stalled the Newton at every
     * step size (cold seed 1); below this resolution a bag's fluid has no pressure of its own, so the vessel stays dry,
     * booked exactly and laid out once it holds a millilitre.
     */
    static final double HOLDS_NOTHING=1e-6;
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
    private static PassiveNetwork emptyingGraph(PassiveNetwork graph,boolean[] emptying) {
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
    /** Per node of {@code graph}, the stock an emptying vessel passes over the step and the ambient's work on its bag. */
    private ConservativeTransport.Emptying[] emptyings(PassiveNetwork graph,boolean[] emptying,double dt) {
        var result=new ConservativeTransport.Emptying[emptying.length];
        for(int i=0;i<emptying.length;i++)if(emptying[i]) {
            var node=graph.reservoirs().get(i);var stock=node.inventory();
            result[i]=new ConservativeTransport.Emptying((molarMass(stock.moles())+stock.solids().massKg())/dt,
                    stock.wallWorkPressure()*PhaseLayout.bagVolume(node.state(),stock.wallCapacity()));
        }
        return result;
    }
    /** This pass's emptying vessels ({@link #emptyingGraph}), read by the junction continuity rows; null for none. */
    private ConservativeTransport.Emptying[] emptyings;
    /**
     * {@code emptying} with every vessel released that a converged pass puts above the ambient (it holds contents at the
     * step's end after all), or null when none is. A vessel a converged pass of this solve has held at or below the ambient
     * is confirmed in {@code emptied} and never released again within the solve: whether a bag runs dry within the step is
     * decided once (a release that restored the vessel form after the phase correction laid out its last 3e-17 kg as two
     * phases cycled the pass sequence at every step size).
     */
    private static boolean[] keptEmptying(PassiveNetwork graph,List<FluidThermodynamics.State> states,boolean[] emptying,boolean[] emptied) {
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
    /** The phase code of a dry vessel in a pass's structure key: it carries no unknowns. */
    private static final int DRY=32;
    /**
     * Decision D54: whether a pass lays {@code node} out as dry, with no unknowns. A vessel that holds no fluid at the step
     * start ({@link #holdsNoFluidVessel}), whose seed is still its own state, is a receiver at the ambient with no fluid:
     * its state is its pressure and its solids, and it gives nothing. Once the start flows or a
     * converged pass's flows deliver into it ({@link #arrivingPhases}), or the booking fills it ({@link #filled}), it is laid
     * out like every vessel.
     */
    static boolean dry(PassiveNetwork.Reservoir node,FluidThermodynamics.State seed){return holdsNoFluidVessel(node)&&seed==node.state();}
    /** Whether this pass laid out a vessel dry. */
    private static boolean anyDry(Equations equations){for(boolean dry:equations.dry)if(dry)return true;return false;}
    /** Whether a converged pass's reconstruction books a fluid into a vessel the pass laid out dry: more than none at the
     * bag's resolution ({@link #holdsNoFluid}); a roundoff it receives stays booked and the vessel stays dry. */
    private static boolean filled(Equations equations,ConservativeTransport.Projection projection) {
        for(int node=0;node<equations.dry.length;node++)
            if(equations.dry[node]&&!holdsNoFluid(projection.inventories().get(node),projection.states().get(node)))return true;
        return false;
    }
    /**
     * The step's ledger (owner decision D46): the change of the vessels and filter cakes equals the boundary transfers plus
     * the junction term, per component, per solid population and for energy (pump work included). The junction term is
     * the change of the junctions' buffers (a buffer is the state of its relaxation row, not inventory) plus what a rate
     * evaluation's frozen junctions held back ({@link ConservativeTransport.HeldJunctions}). Each tolerance is scaled by
     * the boundaries' turnover and the stocks held. The energy is {@code U + sum h dm}: no stream carries potential energy
     * (owner decision D37, simplification S1), so a pump's lift work stays in the fluid as heat (0.023 K per 10 m of water).
     */
    void checkConservation(PassiveNetwork graph,ConservativeTransport.Projection projection) {
        int count=model.hydrocarbon.componentCount()+1;double[] before=new double[count],after=new double[count],turnover=new double[count];double eb=0,ea=0,energyScale=0;
        double[] junction=projection.held().moles(),bufferedBefore=new double[count],bufferedAfter=new double[count];double junctionEnergy=projection.held().energyJoule();
        for(var boundary:projection.boundaries()){var n=boundary.moles();for(int c=0;c<count;c++)turnover[c]+=Math.abs(n[c]);energyScale+=Math.abs(boundary.totalEnergyJoule());}
        // A rate evaluation's held accumulation was a pseudo-boundary at each junction before D46 and counts in the turnover
        // as it did, junction by junction.
        var gross=projection.held().grossMoles();for(int c=0;c<count;c++)turnover[c]+=gross[c];energyScale+=projection.held().grossEnergyJoule();
        for(int i=0;i<graph.reservoirs().size();i++) {
            var reservoir=graph.reservoirs().get(i);var old=reservoir.inventory();var next=projection.inventories().get(i);
            if(reservoir.fixed())continue;
            var a=old.moles();var b=next.moles();double ebNode=old.internalEnergy(),eaNode=next.internalEnergy();
            if(reservoir.junction()){for(int j=0;j<count;j++){junction[j]+=b[j]-a[j];bufferedBefore[j]+=a[j];bufferedAfter[j]+=b[j];}junctionEnergy+=eaNode-ebNode;}
            else{for(int j=0;j<count;j++){before[j]+=a[j];after[j]+=b[j];}eb+=ebNode;ea+=eaNode;}
            energyScale+=Math.abs(old.internalEnergy());
        }
        // Per population: vessels and cakes before [0] and after [1], boundaries [2], buffers before [3] and after [4], and a
        // rate evaluation's held accumulation [5] (net, signed as the junction term).
        var solidBalance=new TreeMap<SolidInventory.Key,double[]>();
        for(int i=0;i<graph.reservoirs().size();i++)if(!graph.reservoirs().get(i).fixed()){
            int buffer=graph.reservoirs().get(i).junction()?3:0;
            for(var p:graph.reservoirs().get(i).inventory().solids().populations())solidBalance.computeIfAbsent(p.key(),k->new double[6])[buffer]+=p.massKg();
            for(var p:projection.inventories().get(i).solids().populations())solidBalance.computeIfAbsent(p.key(),k->new double[6])[buffer+1]+=p.massKg();
        }
        for(var p:projection.held().gained().populations())solidBalance.computeIfAbsent(p.key(),k->new double[6])[5]+=p.massKg();
        for(var p:projection.held().lost().populations())solidBalance.computeIfAbsent(p.key(),k->new double[6])[5]-=p.massKg();
        for(var pipe:graph.pipes())if(pipe.filter()!=null){
            var old=pipe.filter();var next=projection.filters().getOrDefault(pipe.id(),old);eb+=old.energyJoule();ea+=next.energyJoule();energyScale+=Math.abs(old.energyJoule())+Math.abs(next.energyJoule());
            for(var p:old.captured().populations())solidBalance.computeIfAbsent(p.key(),k->new double[6])[0]+=p.massKg();
            for(var p:next.captured().populations())solidBalance.computeIfAbsent(p.key(),k->new double[6])[1]+=p.massKg();
        }
        for(var boundary:projection.boundaries())for(var p:boundary.solids().populations())solidBalance.computeIfAbsent(p.key(),k->new double[6])[2]+=boundary.solidDirection()*p.massKg();
        // What the junctions melted (owner decision D61) leaves the solid ledger and enters the component and energy ones.
        var melted=projection.melted();
        for(var p:melted.solids().populations())solidBalance.computeIfAbsent(p.key(),k->new double[6])[2]-=p.massKg();
        for(var b:solidBalance.values())if(Math.abs(b[0]+b[2]-b[1]-(b[4]-b[3]+b[5]))>1e-10+1e-8*Math.max(Math.max(b[0]+b[3],b[1]+b[4]),Math.abs(b[2]-b[5])))throw new SparseNewton.Nonconvergence("Solid population balance failed");
        var external=projection.externalMoles();var meltedMoles=melted.moles();
        for(int i=0;i<count;i++)if(Math.abs(before[i]+external[i]+meltedMoles[i]-after[i]-junction[i])>1e-10+1e-8*Math.max(turnover[i],Math.max(before[i]+bufferedBefore[i],after[i]+bufferedAfter[i])))throw new SparseNewton.Nonconvergence("Component balance failed: "+i+" before="+before[i]+" after="+after[i]+" external="+external[i]+" junction="+junction[i]);
        if(Math.abs(ea-eb+junctionEnergy-projection.pumpWork()-projection.externalEnergy()-melted.energyJoule())>1e-4+1e-6*(energyScale+Math.abs(projection.pumpWork())+Math.abs(melted.energyJoule())))throw new SparseNewton.Nonconvergence("Total energy balance failed");
    }
    /** The two liquid storage slots of a state. */
    private static final int[] LIQUID_SLOTS={FluidThermodynamics.State.LIQUID,FluidThermodynamics.State.AQUEOUS};
    /** The amounts of the state's liquids together, water last. */
    private static double[] liquidMoles(FluidThermodynamics.State s) {
        double[] x=s.liquidAmounts(),a=s.aqueousAmountsView();for(int c=0;c<x.length;c++)x[c]+=a[c];return x;
    }
    /** The enthalpy of the state's liquids together, J. */
    private static double liquidEnthalpy(FluidThermodynamics.State s) {
        double h=0;
        for(int slot:LIQUID_SLOTS){var phase=s.phase(slot);if(phase!=null)h+=PhaseLayout.sum(s.amountsView(slot))*phase.molarEnthalpy();}
        return h;
    }
    private double carrierViscosity(FluidThermodynamics.State s) {
        if(!FluidThermodynamics.holdsFluid(s))return carrierViscosity(ambient());
        double value=0,volume=s.condensedVolume()+s.vaporVolume();
        for(int slot:LIQUID_SLOTS)if(s.slotVolume(slot)>0)value+=s.slotVolume(slot)*model.viscosity.liquid(s.temperature(),s.amountsView(slot)).pascalSeconds();
        if(s.vaporVolume()>0)value+=s.vaporVolume()*model.viscosity.vapor(s.temperature(),s.vaporAmountsView());
        return value/volume;
    }
    static boolean hasSolids(PassiveNetwork graph) {
        return graph.reservoirs().stream().anyMatch(n->n.state().solidMoments().mass()>0||!n.inventory().solids().empty())
                ||graph.scheduledTransfers().stream().anyMatch(t->t instanceof ScheduledTransfer.Injection in&&!in.solidsPerSecond().empty());
    }
    private double viscosity(FluidThermodynamics.State s) {
        return viscosity(s,null);
    }
    /** The ambient's state ({@link NetworkReduction#ambient}), read for an end that holds no fluid ({@link #viscosity}). */
    private FluidThermodynamics.State ambient;
    private FluidThermodynamics.State ambient(){if(ambient==null)ambient=NetworkReduction.ambient(model);return ambient;}
    /** {@code prepared} must be this state's own temperature bundle, or {@code null} to evaluate the pure-component terms
     * here. A state holding no fluid (a vessel holding only solids, decision D33) has no stream: its ends are dry. The
     * solver still reads a stream at every end where no flow passes (a decode, a closed row, a start estimate, a row's
     * scale), and there it reads an empty vessel's, the ambient's (decision D54), not the 0/0 of no fluid
     * ({@link #carrierViscosity} too). */
    private double viscosity(FluidThermodynamics.State s,FluidThermodynamics.Prepared prepared) {
        if(!FluidThermodynamics.holdsFluid(s))return viscosity(ambient(),null);
        var terms=prepared==null?null:prepared.viscosities();
        double value=0;
        for(int slot:LIQUID_SLOTS)if(s.slotVolume(slot)>0)value+=s.slotVolume(slot)*model.viscosity.liquid(s.temperature(),s.amountsView(slot),terms).pascalSeconds();
        double liquidVolume=s.condensedVolume(),solidVolume=s.solidMoments().volume();
        if(liquidVolume>0&&solidVolume>0){double phi=solidVolume/(liquidVolume+solidVolume);value=SlurryTransport.effectiveViscosity(value/liquidVolume,Math.min(phi,0.62-1e-9))*(liquidVolume+solidVolume);}
        if(s.vaporVolume()>0)value+=s.vaporVolume()*model.viscosity.vapor(s.temperature(),s.vaporAmountsView(),viscosities,terms);
        return value/(liquidVolume==0?s.vaporVolume():s.volume());
    }
    /** The equations of the last accepted solve, for the check that the block Jacobian sweep and an
     * independent column-by-column difference of {@link Equations#residual} produce the same entries;
     * {@code null} until a solve has been accepted. Nothing in production reads it. */
    /** Drops the retained last solve; the interval solver does after every accepted step. */
    void releaseLastSolve(){lastSolve=null;}
    Equations acceptedEquations(){return lastSolve==null?null:lastSolve.equations();}
    /** The point {@link #acceptedEquations} converged to. */
    double[] acceptedPoint(){return lastSolve==null?null:lastSolve.variables().clone();}
    /** The rows a block Jacobian sweep has to re-evaluate for one perturbed node or edge column. */
    final class Equations implements SparseNewton.Equations {
        final double[][] solidTargets,solidIncoming;
        /** The junctions above a crystal's melting point (owner decision D61, {@link JunctionCrystals}), null where none is or
         * in a rate solve; per such junction, what its own stock melts: amounts {@code heldMelt} (mol), offsets
         * {@code heldMeltEnergy} (J), and the moments of the solids it keeps, {@code heldKept}. */
        final JunctionCrystals melting;
        final double[][] heldMelt,heldKept;final double[] heldMeltEnergy;
        final int[] filterOffsets;
        final int[][] weirOffsets;final double[][] weirValues;final boolean hasWeirs;
        /** The connections as the warm-flow check may compare them: never including the cake. They are the solve's own
         * connections, also in a pass that stands an emptying vessel as a junction (decision D54, {@link #emptyingGraph}),
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
         * ({@link #boundaryAllowed}, a constant of the solve), and {@code dt} this solve's step (the backward-Euler step the
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
        /** Whether this island carries the three aggregate solid moment unknowns on every node;
         * the whole-island predicate {@link PhaseLayout} is built with, evaluated once. */
        final boolean solidSupport;
        final boolean[] amountVariables,solidVariables;
        final double[] differenceFloors;
        /** Each amount column's seed difference scale ({@link PhaseLayout#seedDifferenceScale}), -1 for every other column:
         * what {@link #differenceScale} differences a trace below {@link PhaseLayout#TRACE_DIFFERENCE_GUARD} against. */
        final double[] seedDifferenceScales;
        /** Below this value an amount unknown's nonnegativity is not a live constraint on the step
         * length: a solid moment at or below it is dust (see {@link #SOLID_CLAMP_FRACTION}), and a
         * fluid amount at or below it is a trace that {@link #project} keeps positive instead (see
         * {@link #TRACE_CLAMP_FRACTION}). */
        final double[] clampFloors;
        /** Edge indices incident to each node, ascending - which is the order the whole-island
         * residual accumulates that node's targets, energy and junction inflows in, so a per-node
         * re-assembly reproduces every accumulator bit for bit. */
        final int[][] nodeEdges;
        /** Per liquid pump connection, the supplier its suction walk ends at ({@link InletPhase#walk}) and the walk's link into
         * it; -1 where the pump draws straight from its first node (or the connection is no liquid pump). What the suction
         * limit's row and mode test read the supplier's pressure on (owner decision D17, {@link PassiveStepSolver#suctionLimit}). */
        final int[] suctionSuppliers,suctionLinks;
        /** Per node, the connections on their suction limit whose supplier it is and which it is no end of: their actuator
         * row reads its state (owner decision D17), which {@link #buildSparsity} declares and the block sweep re-assembles. */
        final int[][] remoteSuctions;
        /** Whether the actuator row of connection {@code edge} is a suction limit that reads a supplier the connection does not
         * end at. */
        private boolean remoteSuction(int edge) {
            int supplier=suctionSuppliers[edge];var pipe=graph.pipes().get(edge);
            return supplier>=0&&controlOffsets[edge]>=0&&modes.get(edge)==FlowControl.Mode.PUMP_SUCTION_LIMIT&&supplier!=pipe.first()&&supplier!=pipe.second();
        }
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
        final double[] fixedOpenings=heldOpenings.clone();
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
         * <p>Which column it is, is stated from the pass's own seed pressures and elevations ({@link #columnFromFirst}),
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
        /** The weight of the mixing and enthalpy rows the last {@link #junctionInflow} call set: they are stated in
         * amount form over the junction's own mass; see there. */
        double junctionWeight=1;
        Equations(PassiveNetwork graph,double dt,List<FlowControl.Mode> modes,boolean[] boundaryClosed,List<FluidThermodynamics.State> seeds,boolean[][] reachable,
                  byte[][] supports,List<PassiveNetwork.Pipe.Identity> pipeIdentities) {
            this.modes=List.copyOf(modes);this.pipeIdentities=pipeIdentities;
            this.seeds=List.copyOf(seeds);this.supports=supports;
            this.boundaryClosed=boundaryClosed.clone();
            this.graph=graph;this.dt=dt;solidSupport=hasSolids(graph);int count=graph.reservoirs().size();layout=new PhaseLayout[count];offsets=new int[count];oldAmounts=new double[count][];
            cachedVariables=new double[count][];cachedStates=new FluidThermodynamics.State[count];cachedTransport=new Transport[count];
            cachedPrepared=new FluidThermodynamics.Prepared[count];
            drawsPhase=new boolean[count];
            for(int edge=0;edge<graph.pipes().size();edge++)for(int end=0;end<2;end++) {
                var pipe=graph.pipes().get(edge);
                if(this.boundaryClosed[edge]&&pipe.control() instanceof FlowControl.Passive)continue;
                if((end==0?pipe.firstPort():pipe.secondPort())!=PassiveNetwork.PhasePort.BULK)drawsPhase[end==0?pipe.first():pipe.second()]=true;
            }
            releases=rateOnly?null:releases(graph,dt);
            capacities=capacities(graph,dt);overflowCapacities=overflowCapacities(graph,dt);
            seedHeld=new boolean[count][];startPhases=new Transport[count][];
            for(int i=0;i<count;i++)if(drawsPhase[i]){seedHeld[i]=PhaseDraw.held(this.seeds.get(i));startPhases[i]=transport(i,graph.reservoirs().get(i).state(),null).phases;}
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
                var pipe=graph.pipes().get(edge);boolean weir=!rateOnly&&(end==0?pipe.firstPort():pipe.secondPort())==PassiveNetwork.PhasePort.BOOT_HC;
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
                if(solidVariables[column])clampFloors[column]=SOLID_CLAMP_FRACTION*differenceFloors[column];
                else if(amountVariables[column])clampFloors[column]=TRACE_CLAMP_FRACTION*differenceFloors[column];
            }
            int components=oldAmounts[0].length;
            targets=new double[count][components];incoming=new double[count][components];fractions=new double[components];
            solidTargets=new double[count][3];solidIncoming=new double[count][3];
            melting=rateOnly?null:JunctionCrystals.of(graph,model);heldMelt=new double[count][];heldKept=new double[count][];heldMeltEnergy=new double[count];
            if(melting!=null)for(int node=0;node<count;node++)if(melting.any(node)) {
                var stock=graph.reservoirs().get(node).inventory().solids();heldMelt[node]=new double[components];
                for(var p:stock.populations()){var crystal=melting.melts(node,p.material());if(crystal==null)continue;
                    heldMelt[node][crystal.component()]+=p.massKg()/model.molecularWeight(crystal.component());heldMeltEnergy[node]+=p.massKg()*p.material().crystal().energyOffset();}
                heldKept[node]=melting.kept(node,stock).moments().values();
            }
            energy=new double[count];incomingMass=new double[count];incomingEnergy=new double[count];netMass=new double[count];
            for(int node=0;node<count;node++)if(layout[node]!=null)cachedVariables[node]=new double[layout[node].size()];
            int[] degree=new int[count];
            for(var pipe:graph.pipes()){degree[pipe.first()]++;degree[pipe.second()]++;}
            nodeEdges=new int[count][];for(int node=0;node<count;node++)nodeEdges[node]=new int[degree[node]];
            Arrays.fill(degree,0);
            for(int edge=0;edge<graph.pipes().size();edge++) {
                var pipe=graph.pipes().get(edge);
                nodeEdges[pipe.first()][degree[pipe.first()]++]=edge;nodeEdges[pipe.second()][degree[pipe.second()]++]=edge;
            }
            suctionSuppliers=new int[graph.pipes().size()];suctionLinks=new int[graph.pipes().size()];Arrays.fill(suctionSuppliers,-1);Arrays.fill(suctionLinks,-1);
            int[] remote=new int[count];
            for(int edge=0;edge<graph.pipes().size();edge++) {
                var pipe=graph.pipes().get(edge);
                if(!(pipe.control() instanceof FlowControl.Mover liquid&&liquid.liquid()))continue;
                var walk=InletPhase.walk(graph,edge);if(walk.via()<0)continue;
                suctionSuppliers[edge]=walk.node();suctionLinks[edge]=walk.via();
                if(remoteSuction(edge))remote[walk.node()]++;
            }
            remoteSuctions=new int[count][];for(int node=0;node<count;node++)remoteSuctions[node]=new int[remote[node]];
            Arrays.fill(remote,0);
            for(int edge=0;edge<graph.pipes().size();edge++)if(remoteSuction(edge))remoteSuctions[suctionSuppliers[edge]][remote[suctionSuppliers[edge]]++]=edge;
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
            initialPoint=buildInitial();
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
                // Each end's column is what its own port stands in the connection (decision D34, {@link #columnDensity}).
                double first=columnDensity(start,pipe.firstPort(),pipe.volume()),second=columnDensity(end,pipe.secondPort(),pipe.volume());
                // Decisions D54 and D59 (5): an end that may not fill the connection (a dry end, a void, a generator's far
                // end, a blocked way) has nothing to stand in it; it stands in what the other end sends ({@link #donates}).
                int only=-1;
                if(!donates(graph,pipe,pipe.first())){first=second;only=1;}
                else if(!donates(graph,pipe,pipe.second())){second=first;only=0;}
                double dz=graph.reservoirs().get(pipe.second()).elevation()-graph.reservoirs().get(pipe.first()).elevation();
                // The same pressure difference the row drives on, actuator included: a pump's own
                // head is the larger part of what pushes its connection, so a column decided
                // without it is decided on the suction/discharge gap alone and always reads the
                // discharge as the donor.
                double actuator=controlOffsets[edge]<0?0:initialPoint[controlOffsets[edge]]*1e5;
                double difference=endPressure(graph.reservoirs().get(pipe.first()),pipe.firstPort(),start)-endPressure(graph.reservoirs().get(pipe.second()),pipe.secondPort(),end)
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
                // choice multiplies nothing. The rule is {@link #columnFromFirst}, which the start estimate states too.
                columnEnds[edge]=only>=0?only:columnFromFirst(first,second,difference,dz,initialPoint[edgeOffset+edge]>=0)?0:1;
            }
            segments=startSegments();
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
                var released=OpenVessel.releases(model,graph.reservoirs().get(node),dt);if(released==null)continue;
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
         * boil-up to its gas ({@link #boilUp}). */
        private double[][] capacities(PassiveNetwork graph,double dt) {
            int nodes=graph.reservoirs().size();int[] ends=drawingEnds(graph,null);
            double[][] result=new double[nodes][];
            for(int node=0;node<nodes;node++) {
                if(ends[node]==0)continue;
                var start=graph.reservoirs().get(node).state();result[node]=new double[3];
                for(int phase=0;phase<3;phase++)result[node][phase]=rateOnly?Double.POSITIVE_INFINITY
                        :FluidThermodynamics.holdsPhase(start,phase)?model.phaseMass(start,phase)/(ends[node]*dt):0;
                if(releases!=null&&releases[node]!=null)released(node,start,result[node],ends[node]*dt);
                // A kettle's pool boils vapour within the step (W7): its boil-up is gas the ports may draw.
                if(!rateOnly)result[node][FluidThermodynamics.GAS]+=boilUp(graph.reservoirs().get(node))/ends[node];
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
                for(int phase=0;phase<3;phase++)result[node][phase]=band[phase]>0?rateOnly?Double.POSITIVE_INFINITY:band[phase]/(ends[node]*dt):0;
            }
            return result;
        }
        /** The cap of an OVERFLOW end at {@code node}: its capacities' total, kg/s; infinite for a node without. */
        private double overflowCap(int node) {
            if(overflowCapacities==null||overflowCapacities[node]==null)return Double.POSITIVE_INFINITY;
            double total=0;for(double c:overflowCapacities[node])total+=c;
            return total;
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
                for(int phase:new int[]{FluidThermodynamics.OIL,FluidThermodynamics.AQUEOUS})if(FluidThermodynamics.holdsPhase(s,phase))volume+=q*model.phaseMass(s,phase)/mass/phaseDensity(model,s,phase);
                return volume;
            }
            var seed=seeds.get(donor);
            double oil=FluidThermodynamics.holdsPhase(seed,FluidThermodynamics.OIL)?phaseDensity(model,seed,FluidThermodynamics.OIL):0,water=FluidThermodynamics.holdsPhase(seed,FluidThermodynamics.AQUEOUS)?phaseDensity(model,seed,FluidThermodynamics.AQUEOUS):0;
            double[] rates=segment.rates(segment.marginalAt(PhaseDraw.held(seed)),q,oil,water);
            for(int phase:new int[]{FluidThermodynamics.OIL,FluidThermodynamics.AQUEOUS}) {
                double mass=rates[phase]+rates[3+phase];if(!(mass>0))continue;
                double rho=FluidThermodynamics.holdsPhase(s,phase)?phaseDensity(model,s,phase):phase==FluidThermodynamics.OIL?oil:water;
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
                        PhaseDraw.Segment.mixed(capacity,seedHeld[node],PhaseDraw.held(start),outflow,FluidThermodynamics.holdsPhase(seeds.get(node),FluidThermodynamics.OIL)?phaseDensity(model,seeds.get(node),FluidThermodynamics.OIL):0,FluidThermodynamics.holdsPhase(seeds.get(node),FluidThermodynamics.AQUEOUS)?phaseDensity(model,seeds.get(node),FluidThermodynamics.AQUEOUS):0,gasDisplaced):
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
                final int at=node;if(graph.scheduledTransfers().stream().anyMatch(t->t.node()==at))continue;
                double held=0,left=0;for(double n:oldAmounts[node])held+=n;
                if(!(held>0))continue;
                for(int local=0;local<layout[node].size();local++)if(layout[node].totalAmountVariable(local)&&!layout[node].solidVariable(local))left+=x[offsets[node]+local];
                left*=layout[node].amountScale();
                if(!(left<1e-6*held))continue;
                double out=0;for(int edge:nodeEdges[node]){var pipe=graph.pipes().get(edge);double q=tr==null?x[edgeOffset+edge]:flow(edge,x[edgeOffset+edge],tr);out+=pipe.first()==node?q:-q;}
                double stock=molarMass(oldAmounts[node])+reservoir.inventory().solids().massKg();
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
            var rates=segment.rates(segment.marginalAt(node.held()),q);
            return Math.max(0,q-rates[FluidThermodynamics.OIL]-rates[3+FluidThermodynamics.OIL]);
        }
        private double[] phaseRates(PhaseDraw.Segment segment,int edge,int end,Transport node,double q){
            int marginal=segment.marginalAt(node.held());
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
        private Transport drawn(PhaseDraw.Segment segment,int edge,int end,Transport node,double q) {
            if(segment==null||node.phases==null)return node;
            int marginal=segment.marginalAt(node.held());var pinned=segment.pinned();
            if(pinned.length==0&&!segment.mixed()&&weirOffsets[edge][end]<0)return marginal<0?node:node.phases[marginal];
            var pipe=graph.pipes().get(edge);var start=startPhases[end==0?pipe.first():pipe.second()];
            if(pinned.length==1&&marginal<0&&weirOffsets[edge][end]<0)return start[pinned[0]];
            double total=segment.pinnedTotal(),basis=weirOffsets[edge][end]>=0?(q==0?1:Math.abs(q)):segment.mixed()?Math.max(Math.abs(q),1):marginal<0?total:Math.max(Math.abs(q),total);
            var rates=phaseRates(segment,edge,end,node,basis);
            double[] moles=new double[node.moles.length];double volume=0,viscous=0,heat=0,limit=Double.POSITIVE_INFINITY,solidMass=0,solidVolume=0,solidCapacity=0;
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
            return pipe.filter()==null?limit:filterCapFlow(edge,limit,drawn(segment,edge,end,donor,saturated(y,limit)));
        }
        /** The transported flow (kg/s) of connection {@code edge} for its edge unknown {@code y}, the law's flow: its
         * saturation at its cap ({@link PassiveStepSolver#saturated}, {@link #cap}), the flow every booking and actuator row
         * reads; {@code y} itself where the law is not saturated. */
        double flow(int edge,double y,Transport[] tr){return saturated(y,cap(edge,y,tr));}
        /** {@link #capLimit} under {@code segment} in place of this pass's own. */
        private double capLimit(PhaseDraw.Segment segment,int edge,int end,Transport node) {
            var pipe=graph.pipes().get(edge);
            if(segment==null||node.phases==null)return node.density*pipe.minimumArea()*node.velocityLimit;
            int marginal=segment.marginalAt(node.held());var pinned=segment.pinned();
            if(segment.mixed()||weirOffsets[edge][end]>=0){var stream=drawn(segment,edge,end,node,1);return stream.density*pipe.minimumArea()*stream.velocityLimit;}
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
            if(segment==null||node.phases==null)return carrierViscosity(state);
            int marginal=segment.marginalAt(node.held());var pinned=segment.pinned();
            if(segment.mixed())return (model.phaseCarrierViscosity(state,FluidThermodynamics.OIL,viscosities)+model.phaseCarrierViscosity(state,FluidThermodynamics.AQUEOUS,viscosities))/2;
            if(pinned.length==0)return marginal<0?carrierViscosity(state):model.phaseCarrierViscosity(state,marginal,viscosities);
            var pipe=graph.pipes().get(edge);int n=end==0?pipe.first():pipe.second();var startState=graph.reservoirs().get(n).state();var start=startPhases[n];
            double total=segment.pinnedTotal();var rates=segment.rates(marginal,marginal<0?total:Math.max(Math.abs(q),total));double volume=0,value=0;
            for(int k=0;k<6;k++) {
                if(!(rates[k]>0))continue;
                var stream=k<3?node.phases[k]:start[k-3];double flowVolume=rates[k]/stream.density;volume+=flowVolume;
                value+=flowVolume*model.phaseCarrierViscosity(k<3?state:startState,k%3,viscosities);
            }
            return volume>0?value/volume:model.phaseCarrierViscosity(startState,pinned[0],viscosities);
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
            // Owner decision D17: a suction limit's row reads its supplier's state, which may stand beyond its own ends.
            for(int node=0;node<count;node++)for(int edge:remoteSuctions[node])
                for(int c=offsets[node];c<offsets[node]+nodeSize(node);c++)columns[c].set(controlOffsets[edge]);
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
         * {@code now} ({@link #chordHolds}): the slopes a connection's row states are its loss slope in its flow and its
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
         * ({@link #TRACE_CLAMP_FRACTION}). Solid moments keep their own projection in {@link PhaseLayout#decode}.
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
        private double[] buildInitial() {
            double[] x=new double[size];for(int i=0;i<layout.length;i++)if(layout[i]!=null){var encoded=layout[i].encode(seeds.get(i));System.arraycopy(encoded,0,x,offsets[i],encoded.length);}
            int edges=graph.pipes().size();
            double[] q=new double[edges],head=new double[edges];boolean[] headSet=new boolean[edges];
            startPoint(graph,modes,boundaryClosed,pipeIdentities,seeds,q,head,headSet);
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
        /** Drops the per-node decode cache; the next decode rebuilds every node exactly. */
        void releaseDecodeCache(){Arrays.fill(cachedStates,null);Arrays.fill(cachedTransport,null);Arrays.fill(cachedPrepared,null);}
        /** Brings the per-node decode cache up to {@code x}; the residual reads the arrays directly. */
        private void decode(double[] x) {
            if(hasWeirs)for(int edge=0;edge<weirOffsets.length;edge++)for(int end=0;end<2;end++)if(weirOffsets[edge][end]>=0)weirValues[edge][end]=x[weirOffsets[edge][end]];
            for(int i=0;i<layout.length;i++) {
                boolean same=cachedStates[i]!=null&&(layout[i]==null||Arrays.mismatch(x,offsets[i],offsets[i]+layout[i].size(),cachedVariables[i],0,cachedVariables[i].length)<0);
                if(same)continue;
                if(layout[i]!=null) {
                    double temperature=layout[i].temperature(x,offsets[i]);
                    if(cachedPrepared[i]==null||cachedPrepared[i].temperature()!=temperature)cachedPrepared[i]=model.prepare(temperature);
                }
                FluidThermodynamics.State state;
                // A trial a typed thermodynamic hold refused names the node it was refused at; see ThermoHold.
                try{state=layout[i]==null?graph.reservoirs().get(i).state():layout[i].decode(x,offsets[i],cachedPrepared[i]);}
                catch(com.wormzjl.icheme.science.fluid.thermo.ThermoHold hold){throw hold.at(graph.reservoirs().get(i).id());}
                var transport=transport(i,state,cachedPrepared[i]);
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
        private record Transport(double[] moles,double mass,double volume,double density,double viscosity,double specificEnthalpy,double velocityLimit,
                                 SolidInventory.Moments solids,Transport[] phases) {
            /** Which phases this node's state holds (a phase stream is built exactly for a phase it holds). */
            boolean[] held(){return new boolean[]{phases[0]!=null,phases[1]!=null,phases[2]!=null};}
        }
        /** This node's transport rows at {@code state}: the bulk stream in the arithmetic it always had (the gas stream where
         * a bulk end draws the gas, D33), plus its phase streams where an end of it is a phase port. The one builder for the
         * whole-island decode and the block Jacobian sweep. The gas stream's amounts per kilogram are the fluid's totals over
         * its mass, so the structural zeros of a BULK end's columns ({@link #buildSparsity}) hold for it as well. */
        private Transport transport(int node,FluidThermodynamics.State state,FluidThermodynamics.Prepared prepared) {
            var bulk=FluidThermodynamics.bulkDrawsGas(state)?phase(model.phaseStream(state,FluidThermodynamics.GAS,prepared,viscosities))
                    :new Transport(PhaseLayout.totalAmounts(state),state.mass(),state.volume(),state.mass()/state.volume(),viscosity(state,prepared),
                    state.enthalpy()/state.mass(),model.velocityLimit(state),state.solidMoments(),null);
            if(!drawsPhase[node])return bulk;
            var phases=new Transport[3];
            for(int phase=0;phase<3;phase++){var stream=model.phaseStream(state,phase,prepared,viscosities);if(stream!=null)phases[phase]=phase(stream);}
            return new Transport(bulk.moles,bulk.mass,bulk.volume,bulk.density,bulk.viscosity,bulk.specificEnthalpy,bulk.velocityLimit,bulk.solids,phases);
        }
        private static Transport phase(FluidThermodynamics.PhaseStream stream) {
            return new Transport(stream.molesView(),stream.mass(),stream.volume(),stream.mass()/stream.volume(),stream.viscosity(),
                    stream.specificEnthalpy(),stream.velocityLimit(),stream.solidMoments(),null);
        }
        public double[] residual(double[] x) {
            decode(x);double[] f=new double[size];
            assemble(x,cachedStates,cachedTransport,cachedPrepared,f);
            return f;
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
            for(int node=0;node<layout.length;node++)if(layout[node]!=null)nodeAccumulate(node,x,st,tr);
            for(int edge=0;edge<graph.pipes().size();edge++)edgeRows(edge,x,st,tr,f);
            for(int node=0;node<layout.length;node++)if(layout[node]!=null)nodeRows(node,x,st,pr,f);
            if(hasWeirs)weirRows(x,st,tr,f);
        }
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
            System.arraycopy(graph.reservoirs().get(node).inventory().solids().moments().values(),0,solidTargets[node],0,3);Arrays.fill(solidIncoming[node],0);
            System.arraycopy(oldAmounts[node],0,target,0,target.length);Arrays.fill(in,0);
            double stored=graph.reservoirs().get(node).inventory().internalEnergy(),mass=0,heat=0,net=0;
            for(var transfer:graph.scheduledTransfers()) {
                if(transfer.node()!=node)continue;
                var state=st[node];
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
                var flows=new double[graph.pipes().size()];for(int e=0;e<flows.length;e++)flows[e]=flow(e,x[edgeOffset+e],tr);
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
                var pipe=graph.pipes().get(edge);int a=pipe.first(),b=pipe.second();double flow=flow(edge,x[edgeOffset+edge],tr);
                // The donor terms read the stream of the end the flow leaves: the donor's bulk at a BULK end (the same
                // doubles as its whole state), its drawn phase at a phase port.
                boolean first=node==a;int donor=flow>=0?a:b;var upstream=st[donor];var transport=drawn(edge,flow>=0?0:1,tr[donor],flow);
                boolean receiver=node==(flow>=0?b:a);
                double captureFraction=pipe.filter()==null?0:transport.solids.mass()/transport.mass;
                double deliveredFlow=Math.abs(flow)*(1-captureFraction);
                net+=receiver?deliveredFlow:-Math.abs(flow);
                var amounts=transport.moles;double moving=dt*flow;
                for(int c=0;c<amounts.length;c++){double moved=moving*amounts[c]/transport.mass;target[c]+=first?-moved:moved;}
                var moments=transport.solids.values();
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
                    var sourceMoments=carried.solids.values();
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
                    double power=moverPower(edge,flow,head,drawn(edge,0,tr[a],flow).density,st[a].pressure(),st[b].pressure());
                    stored+=dt*power;heat+=power;
                }
                // A heat-exchanging side delivers its donor's stream with the heat it took up crossing the wall, booked like
                // a mover's work into the receiver's energy and its inflow (batch 2026-10-27-heat-exchanger-design).
                if(receiver&&pipe.heat()!=null){double taken=sideHeat(edge,transport.specificEnthalpy,flow);stored+=dt*taken;heat+=taken;}
            }
            // An emptying vessel standing as a junction (decision D54, emptyingGraph) passes its stock as well as what arrives.
            energy[node]=stored;incomingMass[node]=mass;incomingEnergy[node]=heat;netMass[node]=emptyings!=null&&emptyings[node]!=null?net+emptyings[node].drain():net;
        }
        /**
         * The heat (W, into the stream) heat-exchanging connection {@code edge} takes up when it carries {@code flow} drawn at
         * {@code specificEnthalpy} from the end the flow leaves ({@link PassiveStepSolver#sideHeat}, on this step's graph).
         * The residual, the block Jacobian and the reconstruction ({@link ConservativeTransport.EdgeHeat}) all read this one
         * function.
         */
        double sideHeat(int edge,double specificEnthalpy,double flow){return PassiveStepSolver.this.sideHeat(graph,graph.pipes().get(edge),specificEnthalpy,flow,()->{});}
        /** The power (W) mover connection {@code edge} gives what it moves ({@link PassiveStepSolver#moverPower}, on this step's
         * graph): the energy rows and the reconstruction ({@link ConservativeTransport.MoverWork}) read this one function. */
        double moverPower(int edge,double massFlow,double head,double suctionDensity,double suctionPressure,double dischargePressure){return PassiveStepSolver.this.moverPower(graph,edge,massFlow,head,suctionDensity,suctionPressure,dischargePressure);}
        /**
         * The cap flow (kg/s) of an inline filter's connection: the drawn stream's velocity cap {@code limit}
         * ({@link #capLimit}) and, in a step that may fill the cake, the room the cake has left over the step's own
         * retention per kilogram of {@code drawn}, the stream the flow carries.
         */
        private double filterCapFlow(int edge,double limit,Transport drawn) {
            var pipe=graph.pipes().get(edge);
            if(!rateOnly) {
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
            var pipe=graph.pipes().get(edge);int a=pipe.first(),b=pipe.second();double law=x[edgeOffset+edge],flow=flow(edge,law,tr);
            int donor=flow>=0?a:b;var transport=drawn(edge,flow>=0?0:1,tr[donor],flow);double rho=transport.density;
            var suction=drawn(edge,0,tr[a],flow);
            double dz=graph.reservoirs().get(b).elevation()-graph.reservoirs().get(a).elevation();
            double loss=pipe.pressureDrop(law,rho,transport.viscosity);
            if(pipe.filter()!=null){
                // The cake is loaded, and its resistance scaled, per kilogram of the drawn stream: its fluid volume, its
                // carrier's viscosity and its solids.
                var state=st[donor];double fluidVolume=transport.volume-transport.solids.volume();double carrier=drawnCarrierViscosity(edge,flow>=0?0:1,state,tr[donor],flow);
                double loading=x[filterOffsets[edge]];
                loss=pipe.filter().cleanResistance()*(carrier/.001)*(1+99*loading*loading)*fluidVolume/transport.mass*law;
                double volume=pipe.filter().captured().volume()+(rateOnly?0:dt)*Math.abs(flow)*transport.solids.volume()/transport.mass;
                f[filterOffsets[edge]]=loading-volume/pipe.filter().capacity();
            }
            int control=controlOffsets[edge];double head=control<0?0:x[control]*1e5;
            double signedHead=pipe.control() instanceof FlowControl.Mover?head:-head;
            // The static column is this pass's, never the live iterate's upwind end ({@link #columnEnds}); on a flat island
            // dz is zero and the term is bitwise the zero it was. A pump's column is the fluid it moves, the suction node's
            // transported density at this trial (the density its rise limit, target row, velocity clamp and shutoff margin
            // read), never the destination's (decision D6). The column end is the pass's; its density is the trial's, the
            // column that end's port stands in the connection ({@link #columnDensity}, owner decision D34).
            double column=pipe.control() instanceof FlowControl.Mover?suction.density
                    :columnEnds[edge]==0?columnDensity(st[a],pipe.firstPort(),pipe.volume()):columnDensity(st[b],pipe.secondPort(),pipe.volume());
            double driving=columnEndPressure(graph.reservoirs().get(a),pipe.firstPort(),st[a],column)-columnEndPressure(graph.reservoirs().get(b),pipe.secondPort(),st[b],column)-column*GRAVITY*dz+signedHead;
            // Every pressure-dimensioned row of this edge is stated in the island's own pressure,
            // never in a fixed 1e5 Pa unit; see {@link #pressureScales}. The throttled-inlet row
            // below and the closed-edge row are mass flows and keep their own flow scale.
            double pressureScale=pressureScales[edge];
            f[edgeOffset+edge]=(driving-loss)/pressureScale;
            if(boundaryClosed[edge]&&control<0)f[edgeOffset+edge]=flow;
            if(control>=0)f[control]=switch(modes.get(edge)) {
                // A mover on its own law (batch 2026-11-03-rotating-machinery): its head against the law at its suction volume
                // flow (a set-flow mover's in the flow form, FlowControl.Mover#lawResidual), the volume flow on the trial's suction
                // density, the rise limit on {@link #riseDensity} (a set-flow pump's supply, constant over the solve) and the
                // trial's suction pressure, so the Jacobian carries what varies: the block sweep re-evaluates
                // this edge's rows for every perturbed column of its first node, and the sparsity already declares the
                // actuator row on both endpoints' columns (buildSparsity).
                case MOVER_LAW->lawResidual(graph,edge,flow/suction.density,head,riseDensity(edge,suction.density),st[a].pressure())/pressureScale;
                // Cavitation (owner decisions D59 (4), D14, D17 and D20 Q11 A): the suction end stands at the vapour pressure of
                // the liquid the pump draws plus its breakdown NPSH at the trial's suction volume flow, or at what its supplier
                // gives at no flow where that is less, on the trial ({@link PassiveStepSolver#suctionLimit}); the head is free, and the hydraulic row with the suction line's sets
                // the flow. The supplier may be no end of this connection: the sparsity declares its columns on this row and
                // the block sweep re-assembles it ({@link #remoteSuctions}).
                case PUMP_SUCTION_LIMIT->(endPressure(graph.reservoirs().get(a),pipe.firstPort(),st[a])-suctionLimit(this,edge,n->st[n],flow/suction.density))/pressureScale;
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
         * same accumulation order, so each entry is the double the coloured sweep computed. The cost this removes is the
         * assembly: the coloured sweep paid a complete island residual for each of its 143 to 191 colour groups.
         */
        public int differentiateEntries(double[] x,double[] f,double differenceStep,int[] entryOffsets,int[] entryRows,
                                        double[] entries,Runnable checkpoint) {
            if(hasWeirs)return -1;
            if(sparsity==null)buildSparsity();
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
                    for(int local=0;local<layout[node].size();local++) {
                        checkpoint.run();columns++;
                        int column=offsets[node]+local;
                        double step=differenceStep*differenceScale(column,x[column]);
                        trial[column]+=step;
                        double temperature=layout[node].temperature(trial,offsets[node]);
                        var prepared=basePrepared[node]!=null&&basePrepared[node].temperature()==temperature?basePrepared[node]:model.prepare(temperature);
                        var state=layout[node].decode(trial,offsets[node],prepared);
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
                        // The suction limits this node supplies from beyond their ends (owner decision D17): only their
                        // actuator row reads this node, and it is the only one of their rows this column declares.
                        for(int edge:remoteSuctions[node])edgeRows(edge,trial,st,tr,perturbed);
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
            double retained=(molarMass(oldAmounts[node])+inventory.solids().massKg())/dt,total=incomingMass[node]+retained;
            var solids=solidIncoming[node].clone();var held=heldKept[node]!=null?heldKept[node]:inventory.solids().moments().values();
            for(int c=0;c<3;c++)solids[c]=rateOnly?held[c]/(retained*dt):(solids[c]+held[c]/dt)/total;
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
        private double junctionInflow(int node) {
            var inventory=graph.reservoirs().get(node).inventory();var held=oldAmounts[node];
            double heldMass=molarMass(held)+inventory.solids().massKg(),total=incomingMass[node]+heldMass/dt;
            if(rateOnly) {
                junctionWeight=1;
                for(int c=0;c<fractions.length;c++)fractions[c]=held[c]*model.molecularWeight(c)/heldMass;
                return inventory.internalEnergy()/heldMass;
            }
            junctionWeight=total*dt/Math.max(heldMass,1e-300);
            // Owner decision D61: what the junction's own stock melts joins its fluid, with the crystal's offset.
            var melt=heldMelt[node];
            for(int c=0;c<fractions.length;c++)
                fractions[c]=(incoming[node][c]+(melt==null?held[c]:held[c]+melt[c])/dt)*model.molecularWeight(c)/total;
            return (incomingEnergy[node]+(melt==null?inventory.internalEnergy():inventory.internalEnergy()+heldMeltEnergy[node])/dt)/total;
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

    /** Accepted hydraulic row, captured on the solver worker. Observational only; never fed back into the solve. */
    public record FlowSample(long host,double pressure,double massFlow,Double volumeFlow){public FlowSample{if(host<=0||!Double.isFinite(pressure)||!Double.isFinite(massFlow)||volumeFlow!=null&&!Double.isFinite(volumeFlow))throw new IllegalArgumentException("Invalid flow sample");}}
    public record PipeObservation(long pipe,double firstPressure,double secondPressure,double firstElevation,double secondElevation,
                                  double columnDensity,double temperature,boolean flowing,List<Double> lengths,List<Double> losses,double massFlow,double density,List<FlowSample> flowSamples,
                                  double npshRatio,boolean dry,double gasRatio,double npshRequired,double vapourShare,double valveDrop,Cavitation cavitation) {
        /** A flowing liquid valve's state for its noise (owner 2026-11-10, option A; {@link PassiveStepSolver#cavitation}): NONE
         * on every other row and where the thermodynamics cannot say. */
        public enum Cavitation { NONE, PLAIN, CAVITATING, FLASHING }
        /** Without a valve's read: no valve drop (NaN), no cavitation. The pressures of a valve's row include its drop (the
         * control head): {@code firstPressure} stands after the valve, so the drop is its own component. */
        public PipeObservation(long pipe,double firstPressure,double secondPressure,double firstElevation,double secondElevation,double columnDensity,double temperature,boolean flowing,List<Double> lengths,List<Double> losses,double massFlow,double density,List<FlowSample> flowSamples,double npshRatio,boolean dry,double gasRatio,double npshRequired,double vapourShare){this(pipe,firstPressure,secondPressure,firstElevation,secondElevation,columnDensity,temperature,flowing,lengths,losses,massFlow,density,flowSamples,npshRatio,dry,gasRatio,npshRequired,vapourShare,Double.NaN,Cavitation.NONE);}
        /** Without the pump readings (W10d): no NPSH3 in metres, no vapour share. */
        public PipeObservation(long pipe,double firstPressure,double secondPressure,double firstElevation,double secondElevation,double columnDensity,double temperature,boolean flowing,List<Double> lengths,List<Double> losses,double massFlow,double density,List<FlowSample> flowSamples,double npshRatio,boolean dry,double gasRatio){this(pipe,firstPressure,secondPressure,firstElevation,secondElevation,columnDensity,temperature,flowing,lengths,losses,massFlow,density,flowSamples,npshRatio,dry,gasRatio,Double.NaN,Double.NaN);}
        /** Without a gas read (W10c): no vapour share in onsets. */
        public PipeObservation(long pipe,double firstPressure,double secondPressure,double firstElevation,double secondElevation,double columnDensity,double temperature,boolean flowing,List<Double> lengths,List<Double> losses,double massFlow,double density,List<FlowSample> flowSamples,double npshRatio,boolean dry){this(pipe,firstPressure,secondPressure,firstElevation,secondElevation,columnDensity,temperature,flowing,lengths,losses,massFlow,density,flowSamples,npshRatio,dry,Double.NaN);}
        /** Without a pump's suction read (W10b): no NPSH ratio, not dry. */
        public PipeObservation(long pipe,double firstPressure,double secondPressure,double firstElevation,double secondElevation,double columnDensity,double temperature,boolean flowing,List<Double> lengths,List<Double> losses,double massFlow,double density,List<FlowSample> flowSamples){this(pipe,firstPressure,secondPressure,firstElevation,secondElevation,columnDensity,temperature,flowing,lengths,losses,massFlow,density,flowSamples,Double.NaN,false);}
        public PipeObservation(long pipe,double firstPressure,double secondPressure,double firstElevation,double secondElevation,double columnDensity,double temperature,boolean flowing,List<Double> lengths,List<Double> losses,double massFlow,double density){this(pipe,firstPressure,secondPressure,firstElevation,secondElevation,columnDensity,temperature,flowing,lengths,losses,massFlow,density,List.of());}
        public PipeObservation(long pipe,double firstPressure,double secondPressure,double firstElevation,double secondElevation,double columnDensity,double temperature,boolean flowing,List<Double> lengths,List<Double> losses){this(pipe,firstPressure,secondPressure,firstElevation,secondElevation,columnDensity,temperature,flowing,lengths,losses,0,0);}
        public PipeObservation{flowSamples=List.copyOf(flowSamples);lengths=List.copyOf(lengths);losses=List.copyOf(losses);if(lengths.isEmpty()||lengths.size()!=losses.size())throw new IllegalArgumentException("Missing observed sections");}
        public PipeObservation withFlowSamples(List<FlowSample> samples){return new PipeObservation(pipe,firstPressure,secondPressure,firstElevation,secondElevation,columnDensity,temperature,flowing,lengths,losses,massFlow,density,samples,npshRatio,dry,gasRatio,npshRequired,vapourShare,valveDrop,cavitation);}
        /** This row with a liquid pump's suction read (W10b, W10c, W10d): its NPSHA over NPSH3 (NaN: none), whether it runs dry,
         * its suction's vapour share in Turpin onsets, {@code r = α/α_T} (NaN: none), its NPSH3 at the accepted flow (m; NaN:
         * none) and its suction's vapour volume share α (NaN: none). The pump page reads the last two. */
        public PipeObservation withSuction(double ratio,boolean value,double gas,double required,double share){return new PipeObservation(pipe,firstPressure,secondPressure,firstElevation,secondElevation,columnDensity,temperature,flowing,lengths,losses,massFlow,density,flowSamples,ratio,value,gas,required,share,valveDrop,cavitation);}
        /** This row with a valve's read: its own pressure drop (Pa) and, for a flowing liquid, its cavitation. */
        public PipeObservation withValve(double drop,Cavitation c){return new PipeObservation(pipe,firstPressure,secondPressure,firstElevation,secondElevation,columnDensity,temperature,flowing,lengths,losses,massFlow,density,flowSamples,npshRatio,dry,gasRatio,npshRequired,vapourShare,drop,c);}
        public static List<PipeObservation> atRest(PassiveNetwork graph,com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics model){
            var result=new ArrayList<PipeObservation>();
            for(var pipe:graph.pipes()){
                var a=graph.reservoirs().get(pipe.first());var b=graph.reservoirs().get(pipe.second());
                double density=PassiveStepSolver.columnDensity(model,a.state(),pipe.firstPort(),pipe.volume());
                double pa=a.state().pressure()+PassiveStepSolver.portHead(model,a,pipe.firstPort(),a.state()),pb=b.state().pressure()+PassiveStepSolver.portHead(model,b,pipe.secondPort(),b.state());
                if(!(pipe.control() instanceof FlowControl.Passive))pa=pb+density*PassiveStepSolver.GRAVITY*(b.elevation()-a.elevation());
                var row=new PipeObservation(pipe.id(),pa,pb,a.elevation(),b.elevation(),density,a.state().temperature(),false,pipe.sections().stream().map(PipeResistance.Geometry::length).toList(),pipe.sections().stream().map(g->0d).toList());
                var points=new ArrayList<FlowSample>();for(var point:pipe.observationPoints())points.add(new FlowSample(point.host(),row.pressure(point.fraction(),point.elevation(),point.reversed()),0,0.0));
                result.add(row.withFlowSamples(points));
            }
            return List.copyOf(result);
        }

        /** Pressure at a tap's hydraulic datum, including the accepted control/port heads and section loss law. */
        public double pressure(double fraction,double elevation){return pressure(fraction,elevation,false);}
        public double pressure(double fraction,double elevation,boolean reversed){double total=lengths.stream().mapToDouble(Double::doubleValue).sum();double distance=Math.clamp(fraction,0,1)*total,passed=0,loss=0,totalLoss=0;for(int i=0;i<lengths.size();i++){int section=reversed?lengths.size()-1-i:i;double length=lengths.get(section),drop=Math.abs(losses.get(section));loss+=drop*Math.clamp((distance-passed)/length,0,1);totalLoss+=drop;passed+=length;}double share=totalLoss>0?loss/totalLoss:Math.clamp(fraction,0,1);double driving=firstPressure-secondPressure-columnDensity*9.80665*(secondElevation-firstElevation);return firstPressure-columnDensity*9.80665*(elevation-firstElevation)-share*driving;}
    }
}
