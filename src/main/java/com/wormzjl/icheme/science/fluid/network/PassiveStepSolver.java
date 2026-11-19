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
    static final double SOLID_CLAMP_FRACTION=1e-6;
    /**
     * How far below its own difference floor a fluid amount unknown stops bounding the Newton step length, and what a
     * trial keeps of it instead: {@link StepEquations#project} holds such a trace at {@value #TRACE_RETENTION} of its value
     * rather than let the whole step shrink to the trace's size. The case: a connection the step reverses predicts that
     * the far vessel loses the near side's composition, drives a {@code 1e-12} entry trace negative by orders of magnitude,
     * and the {@code .99*x/-d} rule of {@link StepEquations#maximumStep} cut every step of the island to 2e-10 and then 2e-12
     * until the line search stalled (73 of the 76 Newton failures of a three-tank pumped fill). Safe because such a trace
     * moves every row it enters by less than 1e-10, and inventories are rebuilt from the converged flows by
     * {@link ConservativeTransport#reconstruct}.
     */
    static final double TRACE_CLAMP_FRACTION=1e-6,TRACE_RETENTION=.01;
    /**
     * How far below its stated capacity the saturated filter inlet aims: the committed cake is reconstructed by another
     * summation than the loading row solves, so a law aimed exactly at capacity lands one ULP over it on either side;
     * aiming a hair under makes the landing one-sided, and {@link SolidEventIntegrator} treats a cake this close as full.
     */
    static final double FILTER_CAPACITY_MARGIN=1e-12;
    /**
     * The inline filter's cake law, the one statement of it (S-08): the cake's resistance at {@code loading} (its retained
     * share of capacity) in a carrier of {@code viscosity} (Pa s), the clean resistance scaled from water's 1 mPa s and by
     * 1 + 99 L^2. Its readers finish it on their own operands: per density, the slope at zero flow (the balance's
     * {@link BalanceStart.BalanceNet#prepare}, {@link NetworkReduction#resolutionFlow}); per kilogram of the drawn stream's fluid volume
     * times the law's flow, the row's loss ({@link StepEquations#edgeRows}).
     */
    static double cakeResistance(InlineFilter filter,double viscosity,double loading){return filter.cleanResistance()*(viscosity/.001)*(1+99*loading*loading);}
    /**
     * The share of a row's gap a chord step may leave before it is not worth taking ({@link StepEquations#linearisationHolds}):
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
     * term ({@link StepObservations#checkConservation}). A numerical regularisation sized to the flow: its mixing lag at full flow is tau.
     */
    static final double HOLDUP_TAU=.05;
    /**
     * {@link #HOLDUP_TAU} under the time factor (batch 2026-11-11-minecraft-time-delta, W2; owner "Holdup only"): a time
     * constant of the numerics, so a step carries the same number of buffers through a junction at any factor (5 s over 0.05 s
     * at 1, 360 s over 3.6 s at 72) and its mixing lag stays 1 % of a slice. Exactly {@link #HOLDUP_TAU} at factor 1.
     */
    static double holdupTau(com.wormzjl.icheme.science.fluid.SimulationTime time){return HOLDUP_TAU*time.factor();}
    /** The Newton's relative tolerance on its scaled rows (a taut vessel's weighted by the pressure they imply,
     * {@link StepEquations#rowWeights}); the solid
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
        return InletPhase.decide(model,graph,carryModes?carry.modes():null,rateOnly?0:dt);
    }
    /** Connections the next step solve may not close at its start; set by {@link #reopenNext}, consumed by that solve. */
    private Set<PassiveNetwork.Pipe.Identity> keepOpen=Set.of();
    /** Exempts these connections from the start-of-solve closures ({@link OneWayClosures#closeDeadHeads}, {@link OneWayClosures#closeIllegalStarts})
     * of the next step solve only; the pass loop may still close them on a converged point. */
    void reopenNext(Set<PassiveNetwork.Pipe.Identity> identities){keepOpen=Set.copyOf(identities);}
    /** Each blanketed vessel's forecast condensed inflow over the step solves that follow, m3/s by vessel identity ({@link
     * GasBlanket#inflows}), what its vent reads ({@link GasBlanket#releases}); set by the interval solver's per-step plan. */
    Map<Long,Double> blanketInflows=Map.of();
    void blanketInflows(Map<Long,Double> rates){blanketInflows=Map.copyOf(rates);}
    /** The driving pressure (Pa) a closed run's end states must exceed in an allowed direction to be reopened. */
    static double reopenBand(double pa,double pb){return Math.max(1,1e-6*Math.max(Math.abs(pa),Math.abs(pb)));}
    /** The closed runs a converged step's own end states would reopen ({@link OneWayClosures#reopenable}), for the interval solver. */
    Set<PassiveNetwork.Pipe.Identity> reopenable(PassiveNetwork graph,Result result){return closures.reopenable(graph,result);}
    private final FluidThermodynamics model;
    private final SolverOwnership ownership;
    /** Each heat-exchanging connection's curve per end ({@link #sideCurve}), kept while its law's span and films, the end's
     * port and the donor's state are the ones it was built for: the start balance, the seeds and every pass and retry of a
     * step read one curve, and a slice input (a fired coil's firing share, a wall side's bypass) does not resample it
     * (batch 2026-10-27-heat-exchanger-design). */
    private record SideCurve(double coldest,double hottest,HeatCurve.Films films,PassiveNetwork.PhasePort port,double volume,FluidThermodynamics.State start,double pressure,HeatCurve curve) {}
    private final Map<Long,SideCurve[]> sideCurves=new HashMap<>();
    /**
     * The heat curves this solver has sampled ({@link #sideCurve}), and of them those sampled while a pass's residual or
     * Jacobian was evaluated, which is none: a missing curve is sampled between two evaluations ({@link StepEquations#sideHeat};
     * finding D-02, batch 2026-11-13-codebase-review). A test's counters.
     */
    private long heatCurveSamples,heatCurvesInResidual;boolean inResidual;
    long heatCurveSamples(){return heatCurveSamples;}
    long heatCurvesInResidual(){return heatCurvesInResidual;}
    private final Map<WorkspaceKey,SparseNewton.Workspace> workspaces=new LinkedHashMap<>();
    private final Map<WorkspaceKey,SparseNewton.Workspace> structures=new LinkedHashMap<>();
    /**
     * The point a solve carries to the next (S-04, batch 2026-11-13-codebase-review), set in one statement wherever a pass
     * hands its point on. {@code pipes} and {@code nodeIds}: the structure it was solved on. {@code flows} and {@code heads}:
     * its transported flows and device heads. {@code lawFlows}: the law flows ({@link #saturated}: the edge unknowns) of the
     * point {@code flows} are the transported flows of; a step's Newton starts from them; at a job start the committed
     * endpoint's transported flows stand in for them. {@code inputStates}: the input states {@code flows} were recorded at
     * (a rate-only solve warm-starts only from flows recorded at value-identical input states; see {@link StartSeeds#startPoint}).
     * {@code outputStates} and {@code rateOnly}: the last converged solve's states, and whether it was a rate solve (a rate
     * solve's point starts a step only where it was written back into the step's graph,
     * {@code PassiveIntervalSolver.coldRateSeed}; see {@link #carriedPoint}). {@code modes}: the device active set the last
     * pass of the last solve on this graph settled on; a mover on its suction limit, or closed where its rise at no flow
     * loses to the demand, is a property of the island's state, not of one step, so the next solve opens there; valid only
     * while {@code pipes} and {@code nodeIds} still describe this graph. The arrays and lists are held as the pass handed
     * them: {@code flows}, {@code heads} and {@code modes} are the pass's copies, the others its own references.
     */
    record CarriedPoint(List<PassiveNetwork.Pipe.Identity> pipes,long[] nodeIds,List<FluidThermodynamics.State> inputStates,
                                List<FluidThermodynamics.State> outputStates,boolean rateOnly,double[] flows,double[] lawFlows,double[] heads,
                                List<FlowControl.Mode> modes) {
        /** No carried point: a fresh solver's, and a job start's without a committed interval. */
        static CarriedPoint none(){return new CarriedPoint(List.of(),new long[0],List.of(),List.of(),false,new double[0],new double[0],new double[0],List.of());}
        /** This point without its structure, flows and heads (a solve that starts from the balance); its states, rate flag and modes kept. */
        CarriedPoint withoutFlows(){return new CarriedPoint(List.of(),new long[0],inputStates,outputStates,rateOnly,new double[0],new double[0],new double[0],modes);}
        /** This point with the device active set {@code next}. */
        CarriedPoint withModes(List<FlowControl.Mode> next){return new CarriedPoint(pipes,nodeIds,inputStates,outputStates,rateOnly,flows,lawFlows,heads,next);}
    }
    CarriedPoint carry=CarriedPoint.none();
    /**
     * What one pass of the pass loop hands the next pass's start point (S-03, batch 2026-11-13-codebase-review), passed to that
     * pass's equations and read once, as they are built ({@link StepEquations#buildInitial}, {@link StartSeeds#startPoint}).
     * {@code allowedRestart}: the start flows of the connections restarted in their allowed direction ({@link OneWayClosures#allowedRestart}),
     * NaN for the others; null for none. {@code lawFlowsFromBalance}: the next pass takes its law flows from the balance, not
     * from the carried point: a flow valve has just left its open pass; one pass only, so a solve that fails there is retried
     * from the point it was carried, not from the balance's mixture. {@code flashRestated}: the junctions {@link StartSeeds#flashSeeds}
     * restated for the next pass, whose connections start from the restated ends ({@link StartSeeds#restateFlashFlows}); null when none.
     */
    record PassStart(double[] allowedRestart,boolean lawFlowsFromBalance,boolean[] flashRestated) {
        /** Nothing handed on: every start point outside the pass loop's, and a pass's after its equations are built. */
        static final PassStart NONE=new PassStart(null,false,null);
    }
    /** Seeds and the junctions {@link StartSeeds#flashSeeds} restated in them ({@link PassStart#flashRestated}; null for none). */
    record Seeded(List<FluidThermodynamics.State> states,boolean[] flashRestated) {}
    /**
     * What a solve fixes at its start and its equations, mode tests and balance read while a row is evaluated (S-02, batch
     * 2026-11-13-codebase-review): set in one place per solve ({@link #solve}) and per mint ({@link #balanceStarts}), held
     * in {@link #constants}. The arrays are per connection.
     * <ul>
     * <li>{@code inputStates}: the input states of the solve in progress ({@link CarriedPoint#inputStates} are the carried
     * point's). The mint keeps the previous solve's.</li>
     * <li>{@code suctionFloors}: the pressure a pump's suction may not fall below in this solve
     * ({@link MoverSuction#suctionFloors(PassiveNetwork,List,SolveConstants)}).</li>
     * <li>{@code zoneEdges}: the clean zone's edge E of what its pump's suction line delivers in this solve
     * (W3B_NETWORK_PLAN.md section 17, {@link MoverSuction#zoneEdge}): NaN until read, positive infinity where none; worked out when
     * first read, in place, on {@code suctionCheckpoint}, the solve's checkpoint.</li>
     * <li>{@code drawnStreams}: what its liquid pump's suction end draws now ({@link InletPhase#drawn} with no slice: the
     * leading phase at a phase port, the bulk at a BULK end), the stream its clean zone's edge is read on; read with the
     * suction readings ({@link MoverSuction#readSuctions}); null where it reads none. The slice's later gas is the head share's only.</li>
     * <li>{@code vapourLocked}: whether its pump is vapour-locked in this solve ({@link MoverSuction#vapourLocks}): one fact per solve,
     * read on the step's start states with the floor.</li>
     * <li>{@code heldOpenings}: the accepted opening a pressure valve that shares its node holds ({@link ControlConstraints#dependent};
     * 1 where none was accepted), NaN for the others. The mint keeps the previous solve's, as {@code standing} and {@code series}.</li>
     * <li>{@code standing}: the flow valves of this solve that stand open behind a lower target ({@link ControlConstraints#standing}).</li>
     * <li>{@code series}: the series of a pressure valve and a flow regulator each connection of this solve stands in
     * ({@link ControlConstraints#series}), null for none.</li>
     * <li>{@code supplyGraph}, {@code supplyDuration}, {@code supplyDensities}: owner decision D11 (A2): this solve's input
     * graph and slice, and the density of each set-flow pump's supply on it ({@link MoverSuction#riseDensity}; -1 where none is
     * measured), NaN until read, then filled in place.</li>
     * <li>{@code readControls}, {@code suctionMovers}, {@code npshDesigns}, {@code liquidDensities}, {@code wetShares},
     * {@code vapourShares}, {@code condensedShares}, {@code bubbleShares}: request 11 (owner decisions D19 and D20): what this solve reads of its
     * mover's suction on the step's start states ({@link MoverSuction#readSuctions}): the control the reading belongs to, the mover as
     * its law runs this solve (a centrifugal pump's head scaled by its gas share), its NPSH design and the density of the
     * liquid it draws, and a centrifugal compressor's wet efficiency as a share of its dry one (one elsewhere); a liquid mover's
     * share of bubbles in its supply, which its gas ratio, churn and GAS word read: its vapour volume share, NaN for one dense
     * fluid, which holds none (S5 of batch 2026-11-15-supercritical-fluids; {@link InletPhase.Supply#bubbleVolumeShare}).</li>
     * </ul>
     * The suction readings and the vapour lock are written into a solve's arrays right after it is built
     * ({@link MoverSuction#suctionFloors(PassiveNetwork,List,SolveConstants)}, {@link MoverSuction#readSuctions}, {@link MoverSuction#vapourLocks}), the
     * zone edges and the supply densities when first read; no array is replaced within a solve.
     */
    static final class SolveConstants {
        final List<FluidThermodynamics.State> inputStates;
        final double[] suctionFloors,zoneEdges;final InletPhase.Drawn[] drawnStreams;final Runnable suctionCheckpoint;final boolean[] vapourLocked;
        final double[] heldOpenings;final BitSet standing;final int[] series;
        final PassiveNetwork supplyGraph;final double supplyDuration;final double[] supplyDensities;
        final FlowControl[] readControls;final FlowControl.Mover[] suctionMovers;final com.wormzjl.icheme.science.machine.Npsh[] npshDesigns;
        final double[] liquidDensities,wetShares,vapourShares,condensedShares,bubbleShares;
        private SolveConstants(List<FluidThermodynamics.State> inputStates,double[] suctionFloors,double[] zoneEdges,InletPhase.Drawn[] drawnStreams,Runnable suctionCheckpoint,
                               boolean[] vapourLocked,double[] heldOpenings,BitSet standing,int[] series,PassiveNetwork supplyGraph,double supplyDuration,double[] supplyDensities,
                               FlowControl[] readControls,FlowControl.Mover[] suctionMovers,com.wormzjl.icheme.science.machine.Npsh[] npshDesigns,
                               double[] liquidDensities,double[] wetShares,double[] vapourShares,double[] condensedShares,double[] bubbleShares) {
            this.inputStates=inputStates;this.suctionFloors=suctionFloors;this.zoneEdges=zoneEdges;this.drawnStreams=drawnStreams;this.suctionCheckpoint=suctionCheckpoint;
            this.vapourLocked=vapourLocked;this.heldOpenings=heldOpenings;this.standing=standing;this.series=series;
            this.supplyGraph=supplyGraph;this.supplyDuration=supplyDuration;this.supplyDensities=supplyDensities;
            this.readControls=readControls;this.suctionMovers=suctionMovers;this.npshDesigns=npshDesigns;
            this.liquidDensities=liquidDensities;this.wetShares=wetShares;this.vapourShares=vapourShares;this.condensedShares=condensedShares;this.bubbleShares=bubbleShares;
        }
        /** A fresh solver's, before any solve: every array empty, no series, no supply graph. */
        static SolveConstants none() {
            return new SolveConstants(List.of(),new double[0],new double[0],new InletPhase.Drawn[0],()->{},new boolean[0],new double[0],new BitSet(),null,null,0,new double[0],
                    new FlowControl[0],new FlowControl.Mover[0],new com.wormzjl.icheme.science.machine.Npsh[0],new double[0],new double[0],new double[0],new double[0],new double[0]);
        }
        /** A solve's (or the mint's) on {@code edges} connections, its suction readings and vapour lock not yet written: no
         * floor and no zone edge (positive infinity), no supply density read (NaN), no condensed share and no bubble share (NaN), the rest empty. */
        static SolveConstants of(List<FluidThermodynamics.State> inputStates,int edges,Runnable checkpoint,double[] heldOpenings,BitSet standing,int[] series,
                                 PassiveNetwork supplyGraph,double supplyDuration) {
            double[] floors=new double[edges];Arrays.fill(floors,Double.POSITIVE_INFINITY);double[] zoneEdges=new double[edges];Arrays.fill(zoneEdges,Double.POSITIVE_INFINITY);
            double[] supplyDensities=new double[edges];Arrays.fill(supplyDensities,Double.NaN);double[] condensedShares=new double[edges];Arrays.fill(condensedShares,Double.NaN);
            double[] bubbleShares=new double[edges];Arrays.fill(bubbleShares,Double.NaN);
            return new SolveConstants(inputStates,floors,zoneEdges,new InletPhase.Drawn[edges],checkpoint,new boolean[edges],heldOpenings,standing,series,supplyGraph,supplyDuration,supplyDensities,
                    new FlowControl[edges],new FlowControl.Mover[edges],new com.wormzjl.icheme.science.machine.Npsh[edges],new double[edges],new double[edges],new double[edges],condensedShares,bubbleShares);
        }
        /** These constants with the input states {@code next}, every other value the same object. */
        SolveConstants withInputStates(List<FluidThermodynamics.State> next) {
            return new SolveConstants(next,suctionFloors,zoneEdges,drawnStreams,suctionCheckpoint,vapourLocked,heldOpenings,standing,series,supplyGraph,supplyDuration,supplyDensities,
                    readControls,suctionMovers,npshDesigns,liquidDensities,wetShares,vapourShares,condensedShares,bubbleShares);
        }
    }
    /** The constants of the solve in progress, or of the last one ({@link SolveConstants}); a fresh solver's are empty. */
    SolveConstants constants=SolveConstants.none();
    private final Map<PassiveNetwork.Pipe.Identity,Double> acceptedOpenings=new HashMap<>();
    static double boreArea(PassiveNetwork.Pipe pipe){return pipe.controlArea();}
    private void rememberOpenings(PassiveNetwork graph,double[] flows,double[] heads,List<FlowControl.Mode> modes){
        for(int e=0;e<graph.pipes().size();e++){var p=graph.pipes().get(e);if(!(p.control() instanceof FlowControl.Valve))continue;
            double rho=PortStreams.endDensity(model,graph.reservoirs().get(p.first()).state(),p.firstPort(),p.volume());
            acceptedOpenings.put(p.identity(),modes.get(e)==FlowControl.Mode.CLOSED?0:FlowControl.opening(flows[e],Math.max(0,heads[e]),rho,boreArea(p)));
        }
    }

    /** The structure of the last solve this solver accepted; {@link OneWayClosures#closeIllegalStarts} acts only on it. */
    List<PassiveNetwork.Pipe.Identity> acceptedPipes=List.of();
    long[] acceptedNodeIds=new long[0];
    /** The structure of the last step the interval solver's controller accepted (a converged solve it refused does not
     * count): the cold start ({@link #backwardEulerCold}) lasts until then. */
    private List<PassiveNetwork.Pipe.Identity> beAcceptedPipes=List.of();
    private long[] beAcceptedNodeIds=new long[0];
    /** The interval solver accepted a step of this structure. */
    void markBackwardEulerAccepted(PassiveNetwork graph,Result result){beAcceptedPipes=identitiesOf(graph);beAcceptedNodeIds=nodeIds(graph);rememberOpenings(graph,result.massFlows(),result.devicePressureChanges(),result.modes());}
    /** Whether no step of this structure has been accepted yet: the cold start after compile, a topology change, or a job
     * start with no committed interval on this structure. */
    boolean backwardEulerCold(PassiveNetwork graph){return !(sameLines(beAcceptedPipes,identitiesOf(graph))&&Arrays.equals(beAcceptedNodeIds,nodeIds(graph)));}
    /**
     * Puts this solver, at the start of an island job, in the state a fresh solver would be in given only the island's
     * committed interval {@code committed} (null without one on this structure), so a job's solves depend on the committed
     * interval and the slice, not on earlier jobs ({@link PassiveIntervalSolver#replayStart}): every retained numeric
     * Jacobian and LU is dropped (the sparsity pattern, colouring and ordering stay, being functions of the structure);
     * the first step starts from the committed endpoint's flows, heads and modes ({@link StartSeeds#startPoint},
     * {@link CarriedPoint#modes}), or from the balance ({@link BalanceStart#balanceStart}) without a committed interval; "this structure has been
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
        carry=CarriedPoint.none();constants=constants.withInputStates(List.of());
        keepOpen=Set.of();lastSolve=null;acceptedOpenings.clear();
        if(committed!=null) {
            var ids=identitiesOf(graph);long[] nodes=nodeIds(graph);
            beAcceptedPipes=ids;beAcceptedNodeIds=nodes;acceptedPipes=ids;acceptedNodeIds=nodes;
            // The committed endpoint's flows twice (each read a copy), as the law flows' stand-in; no output states, not a rate solve.
            carry=new CarriedPoint(ids,nodes,committed.graph().reservoirs().stream().map(PassiveNetwork.Reservoir::state).toList(),List.of(),false,
                    committed.endpointFlows(),committed.endpointFlows(),committed.endpointHeads(),committed.endpointModes());
            rememberOpenings(graph,carry.flows(),carry.heads(),committed.endpointModes());
        } else {beAcceptedPipes=List.of();beAcceptedNodeIds=new long[0];acceptedPipes=List.of();acceptedNodeIds=new long[0];}
    }
    /** The same pipe identities and node ids. */
    static boolean sameStructure(PassiveNetwork a,PassiveNetwork b){return identities(a).equals(identities(b))&&Arrays.equals(nodeIds(a),nodeIds(b));}
    /** {@link #sameStructure} whatever a flow valve's set point: a job on a new target starts from the committed interval too. */
    static boolean sameLines(PassiveNetwork a,PassiveNetwork b){return sameLines(identities(a),identities(b))&&Arrays.equals(nodeIds(a),nodeIds(b));}
    /** The same connections, whatever a flow valve's set point (a column plans one each slice). */
    static boolean sameLines(List<PassiveNetwork.Pipe.Identity> a,List<PassiveNetwork.Pipe.Identity> b) {
        // P-05: one list is the same lines as itself (every identity is its own line), without the copies line() makes.
        if(a==b)return true;
        if(a.size()!=b.size())return false;
        for(int i=0;i<a.size();i++)if(!a.get(i).sameLine(b.get(i)))return false;
        return true;
    }
    /** The last successful solve, for the block Jacobian check; see {@link #acceptedEquations}. */
    record LastSolve(StepEquations equations,double[] variables) {}
    private LastSolve lastSolve;
    /** The pass whose node bundles ({@code StepEquations.cachedPrepared}) the next pass takes over (P-18). */
    StepEquations preparedPass;
    /** The node bundles the passes of this solver have built new (P-18): a step's first pass builds them and its later
     * passes refill them; the accepted pass keeps them, retired, so the solver's next step refills them and builds none
     * (batch 2026-11-15-fluid-step-cost, S2). A test's counter. */
    long preparedBuilt;
    long preparedBuilt(){return preparedBuilt;}
    boolean rateOnly;
    /** Owned by this solver, which the ownership latch confines to one worker at a time. */
    final com.wormzjl.icheme.science.fluid.transport.MixtureViscosity.Workspace viscosities=
            new com.wormzjl.icheme.science.fluid.transport.MixtureViscosity.Workspace();
    /** The island's retained transport linear algebra; an island holds exactly one. */
    private final ConservativeTransport.Workspace transport;
    /** The balance start ({@link BalanceStart}): built with the solver, on its own model. */
    final BalanceStart balance;
    /** What a converged step reports and its ledger ({@link StepObservations}): built with the solver, on its own model. */
    final StepObservations observations;
    /** The start of the passes: start points and seeds ({@link StartSeeds}): built with the solver, on its own model. */
    final StartSeeds seeding;
    /** What the solve reads of its movers' suctions ({@link MoverSuction}): built with the solver, on its own model. */
    final MoverSuction suctions;
    /** The one-way closures ({@link OneWayClosures}): built with the solver, on its own model. */
    final OneWayClosures closures;
    /** The device active set: start modes and transitions ({@link DeviceModes}): built with the solver, on its own model. */
    final DeviceModes devices;
    /** The phase active set: folds, phase checks, dry and emptying vessels ({@link PhaseActiveSet}): built with the solver, on its own model. */
    final PhaseActiveSet phaseSet;
    public PassiveStepSolver(FluidThermodynamics model){this(model,SolverOwnership.confinedToCurrentThread());}
    public PassiveStepSolver(FluidThermodynamics model,SolverOwnership ownership) {
        this.model=Objects.requireNonNull(model);this.ownership=Objects.requireNonNull(ownership);
        transport=new ConservativeTransport.Workspace(ownership);balance=new BalanceStart(this,this.model);observations=new StepObservations(this,this.model);seeding=new StartSeeds(this,this.model);suctions=new MoverSuction(this,this.model);closures=new OneWayClosures(this,this.model);devices=new DeviceModes(this,this.model);phaseSet=new PhaseActiveSet(this,this.model);
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
    /**
     * A solve that fails hands the next solve the point it started from (option S2 of START_HOLD_REVIEW.md, batch
     * 2026-11-16-review-plant-sizing): its passes write {@link #carry} as they converge, and a retry at a shorter interval
     * that read the failed attempt's would start from a mode set no accepted point holds (a pump left on its suction limit
     * by the failed attempt before a valve the committed interval holds closed: no root at any step size).
     */
    private Result solve(PassiveNetwork inputGraph,double dt,Runnable checkpoint,boolean rateOnly,List<FlowControl.Mode> acceptedEndpointModes) {
        var entry=carry;
        try {
            var mixing=JunctionMixing.begin(inputGraph);
            if(mixing==null)return solvePasses(inputGraph,dt,checkpoint,rateOnly,acceptedEndpointModes);
            var result=solvePasses(mixing.working,dt,checkpoint,rateOnly,acceptedEndpointModes);
            return new Result(result.states(),result.massFlows(),dt,result.numerical(),result.modes(),result.devicePressureChanges(),
                    result.pumpWorkJoule(),result.externalMoles(),result.externalEnergyJoule(),mixing.book(result.inventories()),
                    result.boundaries(),result.pipeTransfers(),result.filters(),result.observations(),result.towers());
        }
        catch(RuntimeException failed){carry=entry;throw failed;}
    }
    private Result solvePasses(PassiveNetwork inputGraph,double dt,Runnable checkpoint,boolean rateOnly,List<FlowControl.Mode> acceptedEndpointModes) {
        // A junction minted by its constructor and not yet sized to its connections is sized here (no-op otherwise).
        inputGraph=PassiveNetwork.sizeJunctionHoldups(inputGraph,model);
        this.rateOnly=rateOnly;
        // The solve's constants (S-02), set here once: its input states, the valves' held openings, standing set and series
        // (functions of the graph and the accepted openings alone), its supply graph and slice; then, written into them, the
        // suction readings before the lock: the zone edge the lock reads is read on the stream they find (InletPhase.drawn).
        var inputStates=inputGraph.reservoirs().stream().map(PassiveNetwork.Reservoir::state).toList();
        double[] heldOpenings=new double[inputGraph.pipes().size()];Arrays.fill(heldOpenings,Double.NaN);
        var conflicts=ControlConstraints.dependent(inputGraph);
        for(int e=conflicts.nextSetBit(0);e>=0;e=conflicts.nextSetBit(e+1))heldOpenings[e]=acceptedOpenings.getOrDefault(inputGraph.pipes().get(e).identity(),1.0);
        constants=SolveConstants.of(inputStates,inputGraph.pipes().size(),checkpoint,heldOpenings,ControlConstraints.standing(inputGraph),ControlConstraints.series(inputGraph),inputGraph,rateOnly?0:dt);
        suctions.suctionFloors(inputGraph,inputStates,constants);suctions.readSuctions(inputGraph,rateOnly?0:dt,constants);suctions.vapourLocks(inputGraph,inputStates,constants.vapourLocked);
        SolverDiagnostics.count(SolverDiagnostics.implicitSolves);
        ownership.check("Each executing island job needs its own step workspace");
        lastSolve=null;
        if(!Double.isFinite(dt)||dt<=0)throw new IllegalArgumentException("Positive finite substep required");
        var graph=inputGraph;
        // An island of unconnected nodes changes nothing in a step, unless an open vessel in it gives the atmosphere its gas
        // or its spill (OpenVessel).
        if(graph.pipes().isEmpty()&&graph.scheduledTransfers().isEmpty()&&(rateOnly||graph.reservoirs().stream().noneMatch(node->OpenVessel.releasing(node)||poolHeat(node)!=0||node.inventory().tower()!=null&&!node.empty())))return new Result(graph.reservoirs().stream().map(PassiveNetwork.Reservoir::state).toList(),new double[0],dt,
                new SparseNewton.Result(new double[0],0,0,0,0,0),List.of(),new double[0],0,new double[model.hydrocarbon.componentCount()+1],0,
                graph.reservoirs().stream().map(PassiveNetwork.Reservoir::inventory).toList(),List.of(),List.of());
        var modes=new ArrayList<FlowControl.Mode>();
        boolean carryModes=carry.modes().size()==graph.pipes().size()&&sameLines(carry.pipes(),identitiesOf(graph))&&Arrays.equals(carry.nodeIds(),nodeIds(graph));
        // A mover's start mode reads its own lines only (owner decision D40 A): its setting, and the sealed sides of it
        // (relieveSealedPockets). A connection blocked elsewhere in the island changes neither.
        modes.addAll(devices.initialModes(graph));
        // At exactly its setpoint a regulating valve may carry positive flow and throttling head. Re-closing it at
        // every substep creates a different branch that must converge before it can reopen, coupled to phase draws.
        // This seed is passed per attempt by the interval controller; the carried modes can include failed trials.
        if(acceptedEndpointModes!=null) {
            if(acceptedEndpointModes.size()!=graph.pipes().size())throw new IllegalArgumentException("Accepted endpoint mode count differs from graph");
            double[] bound=null;
            for(int edge=0;edge<modes.size();edge++)if(graph.pipes().get(edge).control() instanceof FlowControl.Valve valve&&!valve.bare()) {
                var accepted=acceptedEndpointModes.get(edge);
                if(accepted!=FlowControl.Mode.CLOSED&&accepted!=FlowControl.Mode.VALVE_OPEN&&accepted!=FlowControl.Mode.VALVE_REGULATING&&accepted!=FlowControl.Mode.VALVE_CONFLICT)
                    throw new IllegalArgumentException("Invalid accepted valve mode");
                if(constants.standing.get(edge)||accepted==FlowControl.Mode.VALVE_CONFLICT&&Double.isNaN(heldOpenings[edge])){modes.set(edge,accepted==FlowControl.Mode.CLOSED?accepted:FlowControl.Mode.VALVE_OPEN);continue;}
                if(!Double.isNaN(heldOpenings[edge]))continue;
                var start=accepted;
                // Zero demand closes the controlling valve before the first pass, including a previously wide-open valve.
                if(!valve.pressureControl()&&valve.targetMassFlow(1)==0)start=FlowControl.Mode.CLOSED;
                // Owner decision D11 (A1): one whose target lies above what a set-flow mover in its series moves starts open, the
                // mover setting the flow, as an open valve's first pass would find; the pass loop regulates again where the open line
                // passes more. Regulating there asked the mover for its target: 50 kg/s of a 10 kg/s pump stood a thousand relief
                // rises below its suction, where the thermo failed. Owner option 1 of FEED_STEP_HOLD_REVIEW.md: one whose supply holds
                // nothing ({@link InletPhase#starved}, a drained drum) starts open too, as {@link DeviceModes#openStarved} opens it in a pass.
                else if(accepted==FlowControl.Mode.VALVE_REGULATING&&!valve.pressureControl()) {
                    if(bound==null)bound=devices.moverBounds(graph);
                    var pipe=graph.pipes().get(edge);
                    if(valve.targetMassFlow(PortStreams.endDensity(model,graph.reservoirs().get(pipe.first()).state(),pipe.firstPort(),pipe.volume()))>bound[edge]
                            ||InletPhase.starved(graph,edge,node->false))start=FlowControl.Mode.VALVE_OPEN;
                }
                modes.set(edge,start);
            }
        }
        // A mover the previous point left on its suction limit opens there where this step's suction has a vapour pressure to
        // stand at ({@link MoverSuction#suctionFloors}, the water its first node holds); one it left closed stays closed while its rise at no
        // flow ({@link MoverSuction#riseDensity}) still loses to the demand; see {@link CarriedPoint#modes}.
        // Its law states the rest, and the velocity cap is part of every line's law ({@link #saturated}).
        // Owner decision D16 A: a vapour-locked pump ({@link MoverSuction#vapourLocks}) already starts closed ({@link DeviceModes#initialModes}) and
        // the carry leaves it so; the carry reads no suction head of its own. The rise reads this solve's supply densities
        // ({@link SolveConstants}: the input graph and slice, NaN until read).
        if(carryModes)for(int i=0;i<modes.size();i++) {
            var pipe=graph.pipes().get(i);
            if(!(pipe.control() instanceof FlowControl.Mover pump)||pump.targetVolumeFlow()==0||suctions.vapourLocked(i))continue;
            var carried=carry.modes().get(i);
            double band=DeviceModes.shutoffBand(Math.max(graph.reservoirs().get(pipe.first()).state().pressure(),graph.reservoirs().get(pipe.second()).state().pressure()),NEWTON_TOLERANCE);
            if(carried==FlowControl.Mode.PUMP_SUCTION_LIMIT&&suctions.suctionLimitPressure(graph,i,0)<Double.POSITIVE_INFINITY)
                modes.set(i,carried);
            else if(carried==FlowControl.Mode.CLOSED&&!(devices.shutoffMargin(graph,i)>band))
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
        if(!keepOpen.isEmpty()&&!rateOnly){var ids=identitiesOf(graph);keep=new boolean[ids.size()];for(int i=0;i<keep.length;i++)keep[i]=keepOpen.contains(ids.get(i));keepOpen=Set.of();}
        closures.closeDeadHeads(graph,boundaryClosed,keep);
        balanceLawFlows=null;
        if(needsBalance(graph)) {
            // Owner decision of 2026-10-19 (junction seeding): a solve without a carried point ({@link #carriedPoint}) starts from
            // the island's quasi-steady balance ({@link BalanceStart#balanceStart}), the pump modes it decides included: the cold rate seed's
            // rate solve, whose converged point then starts the step (the plan's fallback), and a step whose rate seed failed.
            // A point carried from a solve on other states is dropped; the pass loop carries within the solve.
            relieveSealedPockets(graph,modes,boundaryClosed);
            BalanceStart.Start start;
            if(balanceFor==inputGraph&&balanceCache!=null&&Arrays.equals(balanceClosedFor,boundaryClosed))start=balanceCache;
            else{start=balance.balanceStart(graph,modes,boundaryClosed,keep,checkpoint);balanceFor=inputGraph;balanceCache=start;balanceClosedFor=boundaryClosed.clone();}
            graph=start.graph();balanceLawFlows=start.lawFlows();
            for(int i=0;i<modes.size();i++)modes.set(i,start.modes().get(i));
            carry=carry.withoutFlows();
            for(int i=0;i<boundaryClosed.length;i++)if(start.closed()[i])boundaryClosed[i]=true;
        }
        closures.closeIllegalStarts(graph,modes,boundaryClosed,keep);
        // Owner decision D58: a running mover with a sealed side carries nothing and starts closed.
        relieveSealedPockets(graph,modes,boundaryClosed);
        // The species this island can carry, and the trial states built from them, are read off the connections this pass
        // leaves open, so they are stated after the closure above: a connection the closure has taken delivers nothing,
        // and an entry trace seeded behind it for a component only it could bring puts an unknown on a nonnegativity
        // boundary whose only root is zero, which refused the whole Newton step ({@link OneWayClosures#closeDeadHeads}).
        var reachable=reachableComponents(graph,boundaryClosed);
        var seeded=seeding.initialPhaseSeeds(graph,dt,checkpoint,reachable,modes,boundaryClosed);var seeds=seeded.states();
        var seen=new HashSet<PassKey>();
        // Constant for this solve: every pass keys on the same graph identity and component support.
        var pipeIdentities=identitiesOf(graph);int pipesHash=pipeIdentities.hashCode();
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
        // A vessel a converged pass has confirmed as emptying is not released again within the solve ({@link PhaseActiveSet#keptEmptying}).
        final var original=graph;boolean[] emptying=null,emptied=new boolean[graph.reservoirs().size()],flashedVessels=new boolean[graph.reservoirs().size()];
        // Owner decision of 2026-11-10 (option A, batch 2026-11-10-custom-shader-system): the connections at a void or generator
        // end this solve has restarted in their allowed direction ({@link OneWayClosures#allowedRestart}), and how many restarts it took.
        boolean[] restarted=new boolean[graph.pipes().size()];int restarts=0;
        // The flow valves a failed pass has opened in this solve ({@link DeviceModes#openFailedRegulators}).
        boolean[] openedOnFailure=new boolean[graph.pipes().size()];
        // What the next pass's start point is handed (S-03): the first pass the junctions the seeds restated; each pass consumes it.
        var passStart=new PassStart(null,false,seeded.flashRestated());
        for(int pass=0;pass<maximumPasses;pass++) {
            checkpoint.run();SolverDiagnostics.count(SolverDiagnostics.activeSetPasses);
            graph=emptying==null?original:PhaseActiveSet.emptyingGraph(original,emptying);emptyings=emptying==null?null:phaseSet.emptyings(original,emptying,dt);
            for(int i=0;i<kinds.length;i++)kinds[i]=(byte)graph.reservoirs().get(i).kind().ordinal();
            // Decision D34: the phases a pass is laid out for are the flash's, decided here once per pass and never inside a
            // residual or a Jacobian probe; a seed taken from a converged point of an earlier pass may still hold a phase its
            // Newton drove below the trace share.
            seeds=phaseSet.foldedSeeds(graph,seeds);
            // A junction owns stock like a vessel: it keeps the undirected composition basis
            // {@link #reachableComponents} gives every node and its own state as its seed.
            // A dry vessel (decision D54: empty, nothing laid out for it yet) has its own code: it carries no unknowns.
            int[] phases=new int[seeds.size()];for(int i=0;i<phases.length;i++)phases[i]=dry(graph.reservoirs().get(i),seeds.get(i))?DRY:phaseCode(seeds.get(i));
            byte[] modeCodes=new byte[modes.size()];for(int i=0;i<modeCodes.length;i++)modeCodes[i]=(byte)modes.get(i).ordinal();
            var supports=supports(graph,seeds,reachable,promoted);
            var equations=new StepEquations(this,model,graph,dt,modes,boundaryClosed,seeds,reachable,supports,pipeIdentities,checkpoint,passStart);passStart=PassStart.NONE;
            // The active-set state is what varies between passes, so the structure key is also the cycle key: repeating one
            // means the pass sequence cannot make progress. The frozen junction donors, the phase-port segments and the static
            // columns belong to it for the same reason the modes do: they decide which equations this pass states (see
            // {@link PassKey}).
            var structure=new WorkspaceKey(0,nodeIds,kinds,pipeIdentities,pipesHash,phases,componentMask,supportCodes(supports),modeCodes,boundaryClosed.clone(),equations.junctionDonorFirst);
            if(!seen.add(new PassKey(structure,equations.segmentCodes(),equations.columnCodes(),foldedPhases.clone(),proposal==null?-1:proposal.node(),proposal==null?0:proposal.phases(),restarts)))throw new SparseNewton.Nonconvergence("Phase/device active-set cycle");
            var key=new WorkspaceKey(Double.doubleToLongBits(dt),nodeIds,kinds,pipeIdentities,pipesHash,phases,componentMask,structure.supports,modeCodes,structure.boundaryClosed,structure.junctionDonors);
            var workspace=workspaces.get(key);
            SolverDiagnostics.count(workspace==null?SolverDiagnostics.workspaceBuilds:SolverDiagnostics.workspaceReuses);
            if(workspace==null){if(workspaces.size()>=4)workspaces.remove(workspaces.keySet().iterator().next());var previous=structures.get(structure);workspace=previous==null?new SparseNewton.Workspace(ownership):previous.forkPreconditioner();workspaces.put(key,workspace);}
            if(structures.size()>=4&&!structures.containsKey(structure))structures.remove(structures.keySet().iterator().next());structures.put(structure,workspace);
            SparseNewton.Result numerical;
            // One tolerance for every island: a taut vessel's rows carry the precision its pressure needs as weights in the
            // norm the Newton converges on (owner decision D12, {@link StepEquations#rowWeights}), in place of the tenth of
            // the tolerance a small headspace used to impose on the whole island.
            double tolerance=NEWTON_TOLERANCE;
            try{numerical=SparseNewton.solve(equations,equations.initial(),new SparseNewton.Settings(SparseNewton.ITERATIONS,tolerance,1e-6,24),checkpoint,workspace);}
            catch(SparseNewton.Nonconvergence failure) {
                if(failure.lastVariables()==null)throw failure;
                double[] trialFlows=Arrays.copyOfRange(failure.lastVariables(),equations.edgeOffset,equations.edgeOffset+graph.pipes().size());
                // Owner decisions D59 (4) and D14: a pump whose law pulled its suction below the vapour pressure of the liquid
                // it draws cavitates; the pass is solved again with it on its suction limit.
                if(devices.cavitates(equations,failure.lastVariables(),modes,boundaryClosed,tolerance))continue;
                // Batch 2026-11-10-rigid-reservoir: a rigid vessel holding no gas whose draw the iterate holds below its
                // vapour pressure flashes; the pass is solved again with it laid out at its fixed-volume equilibrium.
                var flashed=phaseSet.flashes(equations,failure.lastVariables(),seeds,flashedVessels,checkpoint);
                if(flashed!=null){seeds=flashed;continue;}
                // Decision D54: a vessel the failed iterate drained to nothing runs dry within the step. Its end state has no
                // contents, so the pass laid out with them has no root (every step size fails the same way); it is solved
                // again with the vessel as a junction holding its stock, which ends the step empty (emptyingGraph).
                // The junction is laid out on its stock, the vessel's own start state (stockSeeds), not on what the failed
                // passes folded out of it.
                var drained=equations.runsDry(failure.lastVariables(),emptying);
                if(drained!=null){seeds=PhaseActiveSet.stockSeeds(original,seeds,drained,emptying);emptying=drained;continue;}
                // Owner option 1 of FEED_STEP_HOLD_REVIEW.md: a regulating flow valve fed only by vessels that hold nothing or that
                // this pass runs dry cannot hold a target above what reaches it; its regulation row has no root at any step size
                // (a drained level-loop drum held its island at residual = the valve's flow). It is solved again open, passing
                // what reaches it; the converged open point regulates again where it passes more than the target.
                if(DeviceModes.openStarved(original,modes,emptying))continue;
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
                    int refuted=proposal.phases()&FluidThermodynamics.collapsing(proposal.seed(),iterate.get(proposal.node()),PhaseActiveSet.rigid(graph.reservoirs().get(proposal.node())));
                    if(refuted!=0){foldedPhases[proposal.node()]|=refuted;SolverDiagnostics.count(SolverDiagnostics.phaseRefusals);seeds=proposal.point();proposal=null;continue;}
                }
                var changedSeeds=phaseSet.foldedIterate(graph,seeds,iterate);
                // Decision D11: a phase a vessel's ports draw at their full capacity leaves the vessel within the step; a
                // pass whose seed still holds it may have no root (see drainedSeeds).
                if(changedSeeds==null)changedSeeds=equations.drainedSeeds(checkpoint);
                // Option B of VALVE_HOLD_TRACE.md (batch 2026-11-16-review-plant-sizing): where nothing else changed, a flow valve
                // regulating in this pass is solved open, once per solve; asked for more than its line passes it has no root.
                if(changedSeeds==null&&DeviceModes.openFailedRegulators(original,modes,openedOnFailure))continue;
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
            if(emptying!=null){var kept=PhaseActiveSet.keptEmptying(original,states,emptying,emptied);if(kept!=null){emptying=kept;continue;}}
            boolean changed=false;int illegalDirection=-1,deviceChange=-1,pressureEntry=-1;FlowControl.Mode deviceNext=null;
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
                next=devices.nextMode(equations,i,mode,flows[i],heads[i],states,up,suction,tolerance);
                // Owner decision of 2026-11-02 ("Lower target wins") in a series of a pressure valve and a flow regulator (batch
                // 2026-11-11-valve-followups, item 3): a flow regulator that would regulate goes before the pressure valve of its
                // series, and one that would regulate while the other regulates opens the other in its place, the change of this
                // pass ({@link DeviceModes#regulatesInSeries}).
                int[] series=constants.series;
                boolean flowEntry=next!=mode&&series!=null&&series.length==modes.size()&&series[i]>=0&&series[i]==pressureEntry&&!ControlConstraints.pressureRegulator(graph,i)&&DeviceModes.regulating(graph,i,next);
                if(next!=mode&&(deviceChange<0||flowEntry)) {
                    int other=devices.regulatesInSeries(graph,modes,i,next);pressureEntry=-1;
                    if(other<0){deviceChange=i;deviceNext=next;if(series!=null&&series.length==modes.size()&&series[i]>=0&&ControlConstraints.pressureRegulator(graph,i)&&DeviceModes.regulating(graph,i,next))pressureEntry=series[i];}
                    else if(other!=i){deviceChange=other;deviceNext=FlowControl.Mode.VALVE_OPEN;}
                    else {
                        // A pressure valve beside a running set-flow mover, which cannot stand open: it stands open in place of
                        // regulating, or reseats where its first node fell below its set pressure.
                        var stands=mode==FlowControl.Mode.CLOSED?FlowControl.Mode.VALVE_OPEN:FlowControl.Mode.CLOSED;
                        if(stands!=mode){deviceChange=i;deviceNext=stands;}
                    }
                }
            }
            // Decision A272: one active-set change per pass, a device's mode first, then a boundary closure. A boundary
            // closure is never taken back within a solve, and a forbidden direction can be the symptom of a wrong device
            // mode, so the reversible correction comes first.
            if(deviceChange>=0) {
                modes.set(deviceChange,deviceNext);changed=true;
                // Option E1 (START_HOLD_REVIEW.md): a flow valve regulating below a pump on its suction limit puts the pump on its law.
                if(deviceNext==FlowControl.Mode.VALVE_REGULATING){var dp=graph.pipes().get(deviceChange);int law=DeviceModes.lawBelowSuctionLimit(graph,modes,deviceChange,deviceNext,flows,equations.drawn(deviceChange,0,equations.cachedTransport[dp.first()],flows[deviceChange]).density());if(law>=0)modes.set(law,FlowControl.Mode.MOVER_LAW);}
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
            // direction is phase-checked first ({@link PhaseActiveSet#phaseCorrection}); where the check changes a node's phases the pass is
            // solved again from them. A rigid tank at the gas breakthrough of its bottom port (108.5 kPa, the void at 1 atm)
            // had its last water folded off a failed iterate; the pass after it held that water as steam, at 174 K and 67 kPa,
            // and drew from the void, and the closure stood the drain still for good, before the check that lays the water out
            // again. A bag hid it: its slack wall held the ambient there.
            if(!changed&&illegalDirection>=0) {
                var corrected=phaseSet.phaseCorrection(graph,states,checkpoint,equations.cachedPrepared,foldedPhases);
                // The correction is the solve's proposal here too (owner decision D39 B): the pass after it is the network's
                // closure of that layout and may refute it. Without the record, a junction given a trace of gas here, whose
                // pass then had no root, was folded off the failed iterate back to the active set of the converged pass, and
                // the check added the gas again: a cycle at every step size (cold seed 431, batch 2026-11-11-aqueous-solvents,
                // FINAL_LANE_2_REVIEW.md).
                if(corrected!=null){proposal=Proposal.of(states,corrected);seeds=corrected;continue;}
                // Owner decision of 2026-11-10 (option A): a converged point can flow the way a void or generator end forbids
                // where the network has a second root. A rigid kettle shell boiling at 101.6 kPa into a void at 1 atm converged
                // from rest on 29 kg/s drawn from the void at 101.320 kPa; the closure held its vapour line shut for the step,
                // and the next step started from rest again, so the line stayed shut for good while the shell rose to 231 kPa.
                // Before such a closure, the pass is solved once more from the same seeds with that connection started at its
                // own law's flow on the step-start states, where that flow goes the allowed way.
                var restart=restarted[illegalDirection]?null:closures.allowedRestart(graph,illegalDirection);
                if(restart!=null){restarted[illegalDirection]=true;restarts++;passStart=new PassStart(restart,false,null);continue;}
                OneWayClosures.closeBoundary(graph,illegalDirection,boundaryClosed,modes);changed=true;
            }
            // A confirmed emptying (keptEmptying) was decided on the network this active set states, so a mode or closure
            // change decides it again.
            if(changed)Arrays.fill(emptied,false);
            // A flow valve leaving its open pass for regulation leaves that pass's flows behind: a choked line's law flow stands on the
            // saturated law's plateau, where the full Newton step to the target crosses zero and lands further out (steam at 3.65 kg/s
            // through a valve set to 0.75: law flow 5.25, -75.9, then 48, slope 1e-11, at every step size). The regulated pass takes its
            // law flows from the balance on these modes ({@link BalanceStart#balanceStart}) and keeps its seeds: a junction still holding its old
            // charge is laid out on that, not on the mixture the balance sends it (a liquid reaching a gas junction went singular).
            // An unconverged balance states no point of the island (VALVE_HOLD_TRACE.md of batch 2026-11-16-review-plant-sizing,
            // option C3: one left at 1 kPa behind a one-way inlet started that inlet 8.5 times past its reverse cap and the pass
            // never took a step): the regulated pass then starts from this pass's own flows, as every other change does (below),
            // and where that fails too the failure goes to the failure branch like any other.
            if(deviceChange>=0&&deviceNext==FlowControl.Mode.VALVE_REGULATING&&graph.pipes().get(deviceChange).control() instanceof FlowControl.Valve valve&&!valve.pressureControl()) {
                var regulated=balance.balanceStart(graph,new ArrayList<>(modes),boundaryClosed,keep,checkpoint);
                if(regulated.converged()){seeds=states;balanceLawFlows=regulated.lawFlows();passStart=new PassStart(null,true,null);carry=carry.withModes(List.copyOf(modes));continue;}
            }
            if(changed){seeds=states;carry=new CarriedPoint(pipeIdentities,nodeIds,inputStates,states,rateOnly,flows.clone(),lawFlows,heads.clone(),List.copyOf(modes));continue;}
            // Decision D11: the phase-port segments this pass froze must be the ones its own converged flows land in
            // (which phases a port draws at its capacity and which one carries the rest); otherwise the pass is solved
            // again, starting from these flows, under the segments they decide (see PhaseDraw). Like an active-set change,
            // and the cycle key carries the segments, so the sequence stays finite. An island without a phase port has none.
            if(equations.segmentsMoved(flows)){seeds=states;carry=new CarriedPoint(pipeIdentities,nodeIds,inputStates,states,rateOnly,flows.clone(),lawFlows,heads.clone(),List.copyOf(modes));continue;}
            // What each phase-port outflow drew, phase by phase, at these flows on this candidate: the reconstruction books it
            // and the transfer samples it (decision D11).
            var draws=equations.draws(flows,states);
            // Decision D54: a dry vessel this pass's flows reach is laid out from what they deliver ({@link StartSeeds#arrivingPhases}), the
            // phases they draw included (what the booking books), and the pass is solved again, its end the step's implicit end
            // like every vessel's. The start flows may miss it: a rate
            // point carries none into a vessel it left dry, and the booking below states the delivered LNG at the tank's own
            // 298 K, as vapour, from which no closure reaches a liquid's enthalpy (cold seeds 181 and 397 stalled at 47 K at
            // every step size, down to 1e-21 s).
            if(!rateOnly&&PhaseActiveSet.anyDry(equations)) {
                var point=new ArrayList<>(states);for(int i=0;i<point.size();i++)if(equations.dry[i])point.set(i,seeds.get(i));
                var arrived=seeding.arrivingPhases(graph,point,lawFlows,draws,true,modes,boundaryClosed,dt,checkpoint);
                if(arrived.states()!=point){seeds=arrived.states();passStart=new PassStart(null,false,arrived.flashRestated());carry=new CarriedPoint(pipeIdentities,nodeIds,inputStates,states,rateOnly,flows.clone(),lawFlows,heads.clone(),List.copyOf(modes));continue;}
            }
            var projection=ConservativeTransport.reconstruct(graph,states,flows,heads,dt,model,checkpoint,transport,rateOnly,draws,equations.supports,emptyings,equations.releases,equations::bookedHeat,equations::moverPower);
            // Decision D54: what the booking fills past the bag's resolution though the flows delivered nothing at it (the
            // delivered amounts stated warmer) is laid out from that state.
            if(PhaseActiveSet.filled(equations,projection)){seeds=projection.states();continue;}
            // Hold class E3: a node this pass solved with no hydrocarbon phase, to which the booked flows deliver a
            // hydrocarbon its layout omitted, was restated by the reconstruction as its flash (ConservativeTransport
            // .repartition); the pass is solved again from it, as from any phase appearance. Before, that restatement
            // failed and every step size was refused.
            if(PhaseActiveSet.phaseAppeared(graph,states,projection.states())){seeds=projection.states();continue;}
            // Decision D34: the reconstruction folded a phase this pass solved (its booked share fell below the trace share),
            // so the pass is solved again from the folded states: the Newton, the reconstruction and the equation gate then
            // read the same phases. A vessel the pass laid out dry solved no phase: its state is its placeholder, and what
            // is booked into it is judged by `filled` alone.
            if(PhaseActiveSet.recordFolds(graph,states,projection.states(),foldedPhases,equations.dry)){SolverDiagnostics.count(SolverDiagnostics.traceFoldPasses);seeds=projection.states();continue;}
            var changedSeeds=phaseSet.phaseCorrection(graph,projection.states(),checkpoint,equations.cachedPrepared,foldedPhases);
            if(changedSeeds!=null){var flashed=seeding.flashSeeds(graph,modes,boundaryClosed,projection.states(),changedSeeds,checkpoint);changedSeeds=flashed.states();passStart=new PassStart(null,false,flashed.flashRestated());proposal=Proposal.of(projection.states(),changedSeeds);seeds=changedSeeds;continue;}
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
                    OneWayClosures.closeBoundary(graph,polished.closure(),boundaryClosed,modes);Arrays.fill(emptied,false);
                    seeds=projection.states();carry=new CarriedPoint(pipeIdentities,nodeIds,inputStates,projection.states(),rateOnly,flows.clone(),lawFlows,heads.clone(),List.copyOf(modes));continue;
                }
                if(polished!=null){numerical=polished.numerical();x=polished.variables();states=polished.states();System.arraycopy(polished.flows(),0,flows,0,flows.length);lawFlows=Arrays.copyOfRange(x,equations.edgeOffset,equations.edgeOffset+flows.length);
                    System.arraycopy(polished.heads(),0,heads,0,heads.length);draws=polished.draws();
                    projection=polished.projection();reconstructed=polished.reconstructed();maximumResidual=polished.residual();}
            }
            if(maximumResidual>gate){SolverDiagnostics.count(SolverDiagnostics.equationGateRejections);throw new SparseNewton.Nonconvergence("Conservative reconstruction fails equation gate: "+maximumResidual);}
            // The ledger reads the island as it is: an emptying vessel is a vessel that ends empty, not the junction it stood
            // as in this pass.
            observations.checkConservation(original,projection);
            carry=new CarriedPoint(pipeIdentities,nodeIds,inputStates,projection.states(),rateOnly,flows.clone(),lawFlows,heads.clone(),List.copyOf(modes));
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
                shown.set(i,ambient());
            }
            if(shown!=null)endStates=shown;
            var accepted=new Result(endStates,flows,dt,numerical,acceptedModes,heads,projection.pumpWork(),projection.externalMoles(),projection.externalEnergy(),projection.inventories(),projection.boundaries(),PipeTransfer.sample(model,graph,projection.states(),flows,dt,draws),projection.filters(),observations.observe(equations,states,flows,lawFlows,heads,acceptedModes,checkpoint),TowerExchange.readings(original,states,flows,draws,model));
            // The retained last solve keeps its equations and point, not their per-node decode caches (states, transport
            // rows): a later reader re-decodes (review 8.7 (c) E2). The node bundles stay, retired, with their kernel
            // workspaces, for the next step (batch 2026-11-15-fluid-step-cost, S2).
            equations.releaseDecodeCache();
            return accepted;
        }
        throw new SparseNewton.Nonconvergence("Device active-set limit");
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
     * (no trace reactivation due, no device mode change ({@link DeviceModes#nextMode}), no illegal direction, the segments its flows
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
    private Polished polishRefusedPoint(PassiveNetwork graph,StepEquations equations,double[] x,double dt,List<FlowControl.Mode> modes,
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
            if(devices.nextMode(equations,edge,modes.get(edge),flows[edge],heads[edge],states,states.get(pipe.first()),suction,tolerance)!=modes.get(edge))return null;
        }
        if(equations.segmentsMoved(flows))return null;
        var draws=equations.draws(flows,states);
        var projection=ConservativeTransport.reconstruct(graph,states,flows,heads,dt,model,checkpoint,transport,rateOnly,draws,equations.supports,emptyings,equations.releases,equations::bookedHeat,equations::moverPower);
        if(PhaseActiveSet.phaseAppeared(graph,states,projection.states())||PhaseActiveSet.phaseFolded(graph,states,projection.states(),equations.dry)
                ||phaseSet.phaseCorrection(graph,projection.states(),checkpoint,equations.cachedPrepared,foldedPhases.clone())!=null)return null;
        var reconstructed=variables.clone();
        for(int node=0;node<projection.states().size();node++)if(equations.layout[node]!=null){var encoded=equations.layout[node].encode(projection.states().get(node));System.arraycopy(encoded,0,reconstructed,equations.offsets[node],encoded.length);}
        SolverDiagnostics.count(SolverDiagnostics.verificationResiduals);
        double residual=0;for(double value:equations.residual(reconstructed))residual=Math.max(residual,Math.abs(value));
        if(residual>gate)return null;
        SolverDiagnostics.count(SolverDiagnostics.equationGatePolishesAccepted);
        return new Polished(numerical,variables,states,flows,heads,draws,projection,reconstructed,residual,-1);
    }
    /** The answers of {@link #liquidPH}: liquid alone, a vapour beside the liquid, or anything else (a refusal, a phase set
     * the network cannot carry, no liquid). */
    static final int PH_LIQUID=1,PH_VAPOUR=0,PH_OTHER=-1;
    /** The PH equilibrium of the liquid {@code x} of supplier {@code s} at its enthalpy {@code h} (J) and pressure
     * {@code p}, warm-started at the supplier's temperature, as {@link MoverSuction#releasePressure} reads it. W3b: any liquid counts,
     * water's among them (a single liquid of half water or more is labelled aqueous, so the oil label alone left a water
     * pump with no floor and a water valve with no cavitation). */
    int liquidPH(FluidThermodynamics.State s,double[] x,double h,double p,Runnable checkpoint) {
        FluidThermodynamics.State at;
        try{at=model.equilibriumPH(s.temperature(),p,h,x.clone(),checkpoint);}catch(IllegalArgumentException refused){return PH_OTHER;}
        if(at==null||!(FluidThermodynamics.holdsPhase(at,FluidThermodynamics.OIL)||FluidThermodynamics.holdsPhase(at,FluidThermodynamics.AQUEOUS)))return PH_OTHER;
        return FluidThermodynamics.holdsPhase(at,FluidThermodynamics.GAS)?PH_VAPOUR:PH_LIQUID;
    }
    /** Value key over compact codes instead of rendered strings and boxed lists; the arrays belong
     * to the key from construction on, so a caller must hand over a snapshot of anything it mutates.
     * {@code supports} is the per-node, per-component phase support flattened in node order: it
     * decides how many unknowns and rows each node block has, so the sparsity pattern, the colouring,
     * the ordering and every retained factorization belong to it as much as to the phase code. */
    private record WorkspaceKey(long stepBits,long[] nodeIds,byte[] kinds,List<PassiveNetwork.Pipe.Identity> pipes,int pipesHash,
                                int[] phases,boolean[] componentMask,byte[] supports,byte[] modes,boolean[] boundaryClosed,
                                boolean[] junctionDonors) {
        @Override public boolean equals(Object other) {
            return other instanceof WorkspaceKey key&&stepBits==key.stepBits&&Arrays.equals(nodeIds,key.nodeIds)
                    &&Arrays.equals(kinds,key.kinds)&&pipesHash==key.pipesHash&&(pipes==key.pipes||pipes.equals(key.pipes))&&Arrays.equals(phases,key.phases)
                    &&Arrays.equals(componentMask,key.componentMask)&&Arrays.equals(supports,key.supports)
                    &&Arrays.equals(modes,key.modes)&&Arrays.equals(boundaryClosed,key.boundaryClosed)
                    &&Arrays.equals(junctionDonors,key.junctionDonors);
        }
        @Override public int hashCode() {
            int hash=31*Long.hashCode(stepBits)+Arrays.hashCode(nodeIds);
            hash=31*(31*hash+Arrays.hashCode(kinds))+pipesHash;
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
     * <p>The static column of each connection ({@link StepEquations#columnEnds}) belongs to the key as well (batch
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
     * pass's point added to one node ({@link PhaseActiveSet#phaseCorrection}), the node's seed holding them and the point they were
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
    private int reactivate(StepEquations equations,List<FluidThermodynamics.State> states,boolean[][] promoted) {
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
    /** {@link #identities} of {@code graph}, built once per graph object (P-05): a solve and its passes ask for the same
     * graph's identities a dozen times. A graph is immutable, so the list is its own for as long as it is the one asked about;
     * an equal list is the same list. */
    List<PassiveNetwork.Pipe.Identity> identitiesOf(PassiveNetwork graph) {
        if(graph!=identityGraph) {
            // A graph of the same connections as the last one (every step's) keeps the last list object, so the workspace keys
            // stored from earlier solves compare by reference: one list comparison per new graph in place of one per lookup.
            var list=identities(graph);identityList=list.equals(identityList)?identityList:list;identityGraph=graph;
        }
        return identityList;
    }
    private PassiveNetwork identityGraph;private List<PassiveNetwork.Pipe.Identity> identityList;
    static long[] nodeIds(PassiveNetwork graph) {
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
    static boolean sameStates(List<FluidThermodynamics.State> a,List<FluidThermodynamics.State> b) {
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
    double molarMass(double[] amounts) {
        double mass=0;for(int c=0;c<amounts.length;c++)mass+=amounts[c]*model.molecularWeight(c);
        return mass;
    }
    /** Each connection's own law on its ends' states ({@link #initialMassFlow}), one per connection. */
    double[] lawFlows(PassiveNetwork graph) {
        double[] q=new double[graph.pipes().size()];
        for(int edge=0;edge<q.length;edge++)q[edge]=initialMassFlow(graph,graph.pipes().get(edge));
        return q;
    }
    /** The law's flow of one connection on its ends' states (the edge unknown's start, {@link #lawFlow}); a mover's its target. */
    double initialMassFlow(PassiveNetwork graph,PassiveNetwork.Pipe pipe) {
        return lawFlow(graph,pipe,PortStreams.endPressure(model,graph,pipe,pipe.first()),PortStreams.endPressure(model,graph,pipe,pipe.second()));
    }
    /** The law's flow of one connection at the end pressures given: a mover's suction target, else the loss inverted on the
     * static drive in the stream of the end the drive leaves (as the row's loss reads its flow's donor), with no cap. */
    double lawFlow(PassiveNetwork graph,PassiveNetwork.Pipe pipe,double firstPressure,double secondPressure) {
        var a=graph.reservoirs().get(pipe.first());var b=graph.reservoirs().get(pipe.second());
        if(pipe.control() instanceof FlowControl.Mover pump)
            return Math.min(PortStreams.suctionMassFlow(model,pump.targetVolumeFlow(),a.state(),pipe.firstPort(),pipe.volume()),massFlowLimit(pipe,a.state(),pipe.firstPort()));
        double driving=staticDrive(graph,pipe,firstPressure-secondPressure);
        var donor=(driving>=0?a:b).state();var donorPort=driving>=0?pipe.firstPort():pipe.secondPort();
        double rho=PortStreams.endDensity(model,donor,donorPort,pipe.volume()),mu=endViscosity(donor,donorPort,pipe.volume());
        double lo=0,hi=1;while(pipe.pressureDrop(hi,rho,mu)<Math.abs(driving)&&hi<1e6)hi*=2;
        for(int j=0;j<50;j++){double mid=(lo+hi)/2;if(pipe.pressureDrop(mid,rho,mu)>Math.abs(driving))hi=mid;else lo=mid;}
        return Math.copySign((lo+hi)/2,driving);
    }
    /**
     * The static driving pressure of one passive connection from end {@code a} to end {@code b}, given their end
     * pressures' {@code difference}, with the
     * column {@link #columnFromFirst} states and each end's {@link PortStreams#columnDensity}. Its start sign, where both columns are
     * admissible, is the sign of {@code difference}. An end that may not donate has no column of its own ({@link #donates}).
     */
    double staticDrive(PassiveNetwork graph,PassiveNetwork.Pipe pipe,double difference) {
        var a=graph.reservoirs().get(pipe.first());var b=graph.reservoirs().get(pipe.second());
        double first=PortStreams.columnDensity(model,a.state(),pipe.firstPort(),pipe.volume()),second=PortStreams.columnDensity(model,b.state(),pipe.secondPort(),pipe.volume()),dz=b.elevation()-a.elevation();
        if(!donates(graph,pipe,pipe.first()))first=second;else if(!donates(graph,pipe,pipe.second()))second=first;
        return difference-(columnFromFirst(first,second,difference,dz,difference>=0)?first:second)*GRAVITY*dz;
    }
    /**
     * Owner decision D59 (5): whether end {@code node} of {@code pipe} may fill the connection, that is, whether the
     * connection may carry flow out of that end ({@link #boundaryAllowed}). A void only receives, a generator's far end
     * never sends into it, a blocked way and a dry end (decision D54) send nothing: such an end's fluid never stands in
     * the connection, so the connection stands in what the other end sends. The one statement of that fact for every
     * static column (the row's {@link StepEquations#columnEnds}, {@link #staticDrive} and the balance's {@link BalanceStart.BalanceNet#flow});
     * before, a void line whose junction stood below the ambient was given the void's air column and closed for good.
     */
    static boolean donates(PassiveNetwork graph,PassiveNetwork.Pipe pipe,int node) {
        return boundaryAllowed(graph,pipe,node==pipe.first()?1:-1);
    }
    /**
     * The one column rule of a vertical connection: whether its static head is stated in the first end's column
     * ({@code first}, the density of what that end's port stands in the connection) rather than the second's, given the
     * end pressures' {@code difference} (actuator included) and {@code dz = z_second - z_first}. The flow row states it
     * once per pass ({@link StepEquations#columnEnds}) and the start estimate on the same question ({@link #staticDrive}).
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
     * The density is {@link MoverSuction#riseDensity}'s: a set-flow pump's supply, constant over the solve (owner decision D11, A2), any
     * other mover's trial suction stream ({@link PortStreams#endDensity}: the bulk at a BULK end, the drawn phase's at a phase port),
     * so every mode test reads the density its law row reads; the start balance reads the suction stream. It is the law's
     * rise at no flow: a set-flow mover's relief rise, a machine's shutoff rise (a centrifugal pump's times its gas share,
     * {@link MoverSuction#readSuctions}).
     */
    double riseLimit(PassiveNetwork graph,int edge,double suctionDensity,double suctionPressure) {
        return suctions.mover(graph,edge).riseLimit(suctionDensity,suctionPressure,model.pumpReferenceDensity(),kappa(graph,graph.pipes().get(edge)));
    }
    /** The rise (Pa) of the mover on connection {@code edge} at suction volume flow {@code flow} on its own law
     * ({@code MOVER_LAW}), as this solve reads it ({@link MoverSuction#mover}). */
    double lawRise(PassiveNetwork graph,int edge,double flow,double suctionDensity,double suctionPressure) {
        return suctions.mover(graph,edge).lawRise(flow,suctionDensity,suctionPressure,model.pumpReferenceDensity(),kappa(graph,graph.pipes().get(edge)));
    }
    double lawRiseSlope(PassiveNetwork graph,int edge,double flow,double suctionDensity,double suctionPressure) {
        return suctions.mover(graph,edge).lawRiseSlope(flow,suctionDensity,suctionPressure,model.pumpReferenceDensity(),kappa(graph,graph.pipes().get(edge)));
    }
    double lawResidual(PassiveNetwork graph,int edge,double flow,double head,double suctionDensity,double suctionPressure) {
        return suctions.mover(graph,edge).lawResidual(flow,head,suctionDensity,suctionPressure,model.pumpReferenceDensity(),kappa(graph,graph.pipes().get(edge)));
    }
    /** The power (W) the mover on connection {@code edge} gives what it moves ({@link FlowControl.Mover#power}): a centrifugal
     * compressor's over its wet efficiency's share of its dry one ({@link MoverSuction#readSuctions}), the one function the Newton's
     * energy rows and the transport's booking read ({@link ConservativeTransport.MoverWork}). A steam turbine's is negative,
     * its shaft power out of the stream ({@link FlowControl.Turbine#shaftPower}) from its inlet's density and pressure to
     * {@code dischargePressure}, its exhaust node's pressure, on {@link #kappa}; a mover's reads no discharge pressure. */
    double moverPower(PassiveNetwork graph,int edge,double massFlow,double head,double suctionDensity,double suctionPressure,double dischargePressure) {
        var pipe=graph.pipes().get(edge);
        if(pipe.control() instanceof FlowControl.Turbine turbine)return -turbine.shaftPower(massFlow,suctionDensity,suctionPressure,dischargePressure,kappa(graph,pipe));
        double power=suctions.mover(graph,edge).power(massFlow,head,suctionDensity,suctionPressure,kappa(graph,graph.pipes().get(edge)));
        return suctions.read(graph,edge)?power/constants.wetShares[edge]:power;
    }
    private final FluidThermodynamics.State[] kappaStates=new FluidThermodynamics.State[16];
    private final double[] kappaValues=new double[16];
    /**
     * The heat capacity ratio of the gas the mover on {@code pipe} draws, on its suction node's state at the step's start
     * ({@link FluidThermodynamics#heatCapacityRatio}; batch 2026-11-03-rotating-machinery, plan section 3.2): one value
     * for the whole step, so every row, every mode test and the transport's booking read the same polytropic exponent.
     * A liquid mover does not use it, except a plunger pump, which reads its suction's isentropic exponent instead
     * ({@link #suctionKappa}). A steam turbine reads its inlet's the same way (plan section 3.4). Cached by the
     * state's identity: a state is immutable.
     */
    private double kappa(PassiveNetwork graph,PassiveNetwork.Pipe pipe) {
        if(pipe.control() instanceof FlowControl.Mover mover&&mover.liquid()&&!plunger(mover))return 1.4;
        var state=graph.reservoirs().get(pipe.first()).state();int slot=System.identityHashCode(state)&15;
        if(kappaStates[slot]!=state){kappaValues[slot]=suctionKappa(model,pipe.control(),state);kappaStates[slot]=state;}
        return kappaValues[slot];
    }
    /**
     * The heat capacity ratio the solve reads for the mover or turbine {@code control} drawing from {@code suction}: a plunger
     * pump's suction's isentropic exponent ({@link FluidThermodynamics#isentropicExponent}: its clearance's re-expansion and
     * its isentropic work, batch 2026-11-16-supercritical-followups, P1); 1.4 for any other liquid mover (its law does not
     * use it); else the gas's on that state ({@link FluidThermodynamics#heatCapacityRatio}).
     * The one function the solve ({@link #kappa}), a driver's charge and its speed control read (code base review M-01, M-07).
     */
    public static double suctionKappa(FluidThermodynamics model,FlowControl control,FluidThermodynamics.State suction) {
        if(control instanceof FlowControl.Mover mover&&mover.liquid())return plunger(mover)?model.isentropicExponent(suction):1.4;
        return model.heatCapacityRatio(suction);
    }
    /** Whether {@code mover} is a plunger pump on its shaft, whose law reads the suction's isentropic exponent. */
    private static boolean plunger(FlowControl.Mover mover) {
        return mover instanceof FlowControl.Machine machine&&machine.law() instanceof com.wormzjl.icheme.science.machine.PlungerPump;
    }
    /**
     * The share of its dry efficiency a centrifugal compressor on connection {@code edge} of {@code graph} keeps on its wet
     * suction over a slice of {@code duration} s (zero for a rate solve), as {@link MoverSuction#readSuctions} reads it: the wet efficiency
     * of the supply's condensed mass share ({@link com.wormzjl.icheme.science.machine.CentrifugalCompressor#wetEfficiency}); 1 for
     * every other connection (a liquid machine, a vacuum pump, a stopped mover, a set-flow mover, no mover). A driver's charge
     * reads the same function (code base review M-01).
     */
    public static double wetShare(FluidThermodynamics model,PassiveNetwork graph,int edge,double duration) {
        if(!(graph.pipes().get(edge).control() instanceof FlowControl.Machine machine)||machine.liquid()||machine.targetVolumeFlow()==0
                ||machine.law() instanceof com.wormzjl.icheme.science.machine.VacuumPump)return 1;
        return wetShare(InletPhase.supply(model,graph,edge,machine.targetVolumeFlow(),duration));
    }
    /** {@link #wetShare} of the supply a compressor's suction line delivers. */
    static double wetShare(InletPhase.Supply supply){return com.wormzjl.icheme.science.machine.CentrifugalCompressor.wetEfficiency(1,supply.condensedMassShare());}
    /** The velocity cap's mass flow of what leaves {@code donor} through {@code port} at a vanishing flow (the stream
     * {@link PortStreams#endDensity} names): its density, area and velocity limit, multiplied in the order the bulk form
     * {@code mass/volume*area*velocityLimit} always was, so a BULK end is that form to the bit. The cap a pass imposes on
     * a priority mixture is {@link StepEquations#capLimit}. */
    double massFlowLimit(PassiveNetwork.Pipe pipe,FluidThermodynamics.State donor,PassiveNetwork.PhasePort port) {
        return PortStreams.endDensity(model,donor,port,pipe.volume())*pipe.minimumArea()*PortStreams.endVelocityLimit(model,donor,port);
    }
    /**
     * The velocity cap as part of every line's law (M7 and M8 option C, 2026-10-18; it replaces the cap active set of
     * decision A270): a choked line passes at most the cap flow {@code q_cap} of the end it leaves, and the flow its law
     * gives at a driving pressure, {@code q_law}, is reached only far under it,
     * {@code q = q_law (1 + (q_law/q_cap)^8)^(-1/8)}, odd in {@code q_law}: 8.3 % under the cap where the drive equals the
     * cap's own pressure drop, within 0.06 % of it at twice that drive and bitwise the law at a tenth of it. The edge
     * unknown is the law's flow {@code q_law}, unbounded, so the hydraulic row stays the plain law
     * {@code driving = loss(q_law)}; what the connection carries, {@code q}, is read from it wherever a flow is booked
     * ({@link StepEquations#flow}: the node rows, the actuator rows, the step's transfers). A flow deep in saturation moves
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
     * end ({@link PortStreams#portHead}: the level head at a vessel's LIQUID port, zero elsewhere). Every static driving pressure the
     * solver states (the hydraulic row, the pass's column choice, the start-of-solve closures, the start flows, the valve
     * start head, a mover's carry and shutoff margin ({@link DeviceModes#demand}) and the reopen test)
     * reads an end's pressure through here and nowhere else, because they must agree: a closure decided on one pressure
     * and reopened on another bottles a line for good. The offset is a pressure, so it applies in both directions.
     */
    /**
     * The height a one-block tank's level head is stated over (decision D9, option B): one metre, its own height at the
     * default 1 m3 volume; a prismatic tank of this height over the footprint {@code V/H}. A drum states its own height in
     * its inventory ({@link PassiveNetwork.Inventory#levelHeight}; batch 2026-10-15-custom-equipment-rendering): its
     * apex-to-apex length standing, its diameter lying.
     */
    public static final double LEVEL_HEAD_HEIGHT=1;
    /** The viscosity of what leaves a node through {@code port} at a vanishing flow; the bulk's at a BULK end and when the
     * state holds none of the phases. */
    double endViscosity(FluidThermodynamics.State s,PassiveNetwork.PhasePort port,double volume) {
        if(PhaseDraw.mixedLiquids(s,port))return PortStreams.mixedViscosity(model,s,volume,viscosities);
        int phase=PhaseDraw.leading(model,s,port);
        return phase<0?viscosity(s):model.phaseViscosity(s,phase,viscosities,null);
    }
    /** What a stream drawn at {@code specificEnthalpy} (J/kg) and {@code temperature} arrives as through {@code pipe} at
     * {@code flow}: its specific enthalpy and temperature after the side ({@link StepEquations#sideHeat}, the temperature off the same
     * curve); unchanged through a plain connection. A seed's reading: a junction a heater boils is seeded with its vapour. */
    double[] delivered(PassiveNetwork graph,PassiveNetwork.Pipe pipe,double specificEnthalpy,double temperature,double flow,Runnable checkpoint) {
        var curve=pipe.heat()==null||flow==0?null:sideCurve(graph,pipe,flow>0?pipe.first():pipe.second(),checkpoint);
        if(curve==null)return new double[]{specificEnthalpy,temperature};
        double out=specificEnthalpy+pipe.heat().heat(curve,specificEnthalpy,flow)/Math.abs(flow);
        return new double[]{out,curve.temperatureAt(out)};
    }
    /**
     * The heat curve (plan section 5.1) of the stream {@code pipe} draws from {@code node} in {@code graph}: the amounts its
     * end's port draws at the node's state ({@link PortStreams#endMoles}; a drum's liquid draw has its own bubble point, not the
     * drum's whole mixture's), at the node's pressure ({@link #curvePressure}: a fired coil's at its outlet's), over the node's temperature and the side's wall temperatures, 10 K
     * beyond each, inside the numerical box. Past that span the curve extends its end lines (AGENTS.md "Thermo validity").
     * Null where the end draws nothing. A curve kept for the same stream and span takes new films without a flash (an air
     * cooler's planned fans, W4).
     */
    HeatCurve sideCurve(PassiveNetwork graph,PassiveNetwork.Pipe pipe,int node,Runnable checkpoint) {
        var start=graph.reservoirs().get(node).state();int end=node==pipe.first()?0:1;var port=pipe.portAt(node);double volume=pipe.volume();
        var kept=sideCurves.computeIfAbsent(pipe.id(),id->new SideCurve[2]);var old=kept[end];var law=pipe.heat();double pressure=curvePressure(graph,pipe,node);
        if(keeps(old,start,pressure,port,volume,law)) {
            if(old.films().equals(law.films())||old.curve()==null)return old.curve();
            var curve=old.curve().withFilms(law.films());kept[end]=new SideCurve(old.coldest(),old.hottest(),law.films(),port,volume,start,pressure,curve);return curve;
        }
        double[] amounts=PortStreams.endMoles(model,start,port,volume);double total=0;for(double n:amounts)total+=n;
        HeatCurve curve=null;
        if(total>0&&PortStreams.endMass(model,start,port,volume)>0) {
            double low=Math.max(NumericalBox.MINIMUM_TEMPERATURE,Math.min(start.temperature(),law.coldest())-10);
            double high=Math.min(NumericalBox.MAXIMUM_TEMPERATURE,Math.max(start.temperature(),law.hottest())+10);
            heatCurveSamples++;if(inResidual)heatCurvesInResidual++;
            curve=HeatCurve.sample(model,amounts,pressure,low,high,law.films(),checkpoint);
        }
        kept[end]=new SideCurve(law.coldest(),law.hottest(),law.films(),port,volume,start,pressure,curve);
        return curve;
    }
    /**
     * The pressure {@code pipe}'s curve from {@code node} is sampled at: the node's, except a fired coil's, whose set point is
     * its outlet's temperature (owner, 2026-11-18: the controller reads the coil outlet). There the stream leaves at the
     * pressure of the end it delivers to, below its inlet's by the coil's drop, and the same enthalpy at that lower pressure
     * flashes colder: on the inlet's curve the review plant's coil read 620 K at full firing against its 638 K set point,
     * 300 kPa in and 262 kPa out. An outlet with no pressure yet takes the inlet's.
     */
    static double curvePressure(PassiveNetwork graph,PassiveNetwork.Pipe pipe,int node) {
        double inlet=graph.reservoirs().get(node).state().pressure();
        if(!(pipe.heat() instanceof FiredCoil))return inlet;
        double outlet=graph.reservoirs().get(node==pipe.first()?pipe.second():pipe.first()).state().pressure();
        return outlet>0&&Double.isFinite(outlet)?outlet:inlet;
    }
    private static boolean keeps(SideCurve old,FluidThermodynamics.State start,double pressure,PassiveNetwork.PhasePort port,double volume,HeatLaw law) {
        return old!=null&&old.start()==start&&old.pressure()==pressure&&old.port()==port&&old.volume()==volume&&old.coldest()==law.coldest()&&old.hottest()==law.hottest();
    }
    /** Whether {@link #sideCurve} gives {@code node}'s end of {@code pipe} without a flash march: a curve kept for its
     * stream and span (new films are applied without one), or an end that draws nothing. */
    boolean curveKept(PassiveNetwork graph,PassiveNetwork.Pipe pipe,int node) {
        var start=graph.reservoirs().get(node).state();int end=node==pipe.first()?0:1;var port=pipe.portAt(node);double volume=pipe.volume();
        var kept=sideCurves.get(pipe.id());
        if(keeps(kept==null?null:kept[end],start,curvePressure(graph,pipe,node),port,volume,pipe.heat()))return true;
        double[] amounts=PortStreams.endMoles(model,start,port,volume);double total=0;for(double n:amounts)total+=n;
        return !(total>0&&PortStreams.endMass(model,start,port,volume)>0);
    }
    /** A stream's curve and the specific enthalpy (J/kg) it arrives at. */
    record KeptCurve(HeatCurve curve,double inlet) {}
    /**
     * The curve of {@code node}'s end of {@code pipe} without a flash march (D16): {@link #sideCurve} where it keeps one for
     * the end's stream as it stands ({@link #curveKept}), else the curve it sampled last for that end, at the state it was
     * sampled from; null where it keeps none or the end draws nothing.
     */
    @org.jetbrains.annotations.Nullable KeptCurve keptCurve(PassiveNetwork graph,PassiveNetwork.Pipe pipe,int node,Runnable checkpoint) {
        if(curveKept(graph,pipe,node)) {
            var curve=sideCurve(graph,pipe,node,checkpoint);
            return curve==null?null:new KeptCurve(curve,PortStreams.endSpecificEnthalpy(model,graph.reservoirs().get(node).state(),pipe.portAt(node),pipe.volume()));
        }
        var kept=sideCurves.get(pipe.id());var old=kept==null?null:kept[node==pipe.first()?0:1];
        return old==null||old.curve()==null?null:new KeptCurve(old.curve(),PortStreams.endSpecificEnthalpy(model,old.start(),old.port(),old.volume()));
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
     *
     * <p>Every air cooler's bank also takes its UA for the heat page (decision D16 of batch 2026-11-16-fouling-corrosion,
     * {@link AirCooled#conductance(HeatCurve,double,double)}), on its planned air: a controlled one's on the curve it is
     * planned on, another's on a curve kept without a flash ({@link #keptCurve}); none where the step starts with no flow.
     * A reading within {@link ExchangerRelay.Conductance#DEADBAND} of the last stands.</p>
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
            if(!(pipe.heat() instanceof FiredCoil)&&!(pipe.heat() instanceof AirCooled))continue;
            boolean known=flows!=null&&flows.length==graph.pipes().size();double flow=known?flows[e]:0;
            if(pipe.heat() instanceof AirCooled cooled&&cooled.control()==null) {
                // An air cooler without a controller plans nothing: only its UA, where the step's start flow is known.
                if(!known)continue;
                var read=flow==0?null:keptCurve(graph,pipe,flow>=0?pipe.first():pipe.second(),checkpoint);
                var value=flow==0?null:read==null?cooled.conductance():cooled.conductance(read.curve(),read.inlet(),flow);
                if(ExchangerRelay.Conductance.same(value,cooled.conductance()))continue;
                if(pipes==null)pipes=new ArrayList<>(graph.pipes());
                pipes.set(e,pipe.withHeat(cooled.withConductance(value)));
                continue;
            }
            int donor=flow>=0?pipe.first():pipe.second();var curve=sideCurve(graph,pipe,donor,checkpoint);
            double inlet=curve==null?0:PortStreams.endSpecificEnthalpy(model,graph.reservoirs().get(donor).state(),pipe.portAt(donor),pipe.volume());
            HeatLaw planned=switch(pipe.heat()) {
                case FiredCoil coil->curve==null?coil.withFiring(1):coil.planned(curve,inlet,flow);
                case AirCooled cooled->curve==null?cooled.withConductance(null):cooled.planned(curve,inlet,flow).withConductance(null);
                default->pipe.heat();
            };
            if(planned instanceof AirCooled cooled&&curve!=null) {
                var value=cooled.conductance(curve,inlet,flow);var last=((AirCooled)pipe.heat()).conductance();
                planned=cooled.withConductance(ExchangerRelay.Conductance.same(value,last)?last:value);
            }
            if(planned instanceof FiredCoil){if(radiant==null)radiant=new HashMap<>();radiant.put(pipe.id(),curve==null?0:planned.heat(curve,inlet,flow));}
            if(planned.equals(pipe.heat())&&!(planned instanceof AirCooled cooled&&cooled.conductance()!=((AirCooled)pipe.heat()).conductance()))continue;
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
            var planned=side.withInputs(flat,side.bypass(),0,1).withConductance(null);
            double regulated=Double.NaN;
            if(PortStreams.endMass(model,start,port,pipe.volume())>0) {
                double own=PortStreams.endSpecificEnthalpy(model,start,port,pipe.volume()),low=start.temperature(),high=low;
                // A kettle's tube side reads its pool (W7): the vessel's contents, isothermal, no stream.
                boolean pool=partner!=null&&partner.pool();
                boolean drawn=partner!=null&&(pool||PortStreams.endMass(partnerModel(partner),partner.donor(),partner.port(),partner.volume())>0);
                if(drawn){low=Math.min(low,partner.donor().temperature());high=Math.max(high,partner.donor().temperature());}
                // A column's heating side (W4) gives its heat down to its floor, through its condensation: the curve spans it.
                if(read.gives())low=Math.min(low,read.ceiling());
                low=Math.max(NumericalBox.MINIMUM_TEMPERATURE,low-10);high=Math.min(NumericalBox.MAXIMUM_TEMPERATURE,high+10);
                var curve=PortStreams.spanned(model,start,port,pipe.volume(),low,high,side.films(),checkpoint);
                var other=!drawn||pool?null:PortStreams.spanned(partnerModel(partner),partner.donor(),partner.port(),partner.volume(),low,high,partner.law().films(),checkpoint);
                var stream=curve==null?null:new ExchangerRelay.Stream(curve,own,flow,side,true);
                if(stream!=null&&pool)planned=ExchangerRelay.plan(side,stream,partner.bath(),read);
                else if(stream!=null)planned=ExchangerRelay.plan(side,stream,
                        other==null?null:new ExchangerRelay.Stream(other,PortStreams.endSpecificEnthalpy(partnerModel(partner),partner.donor(),partner.port(),partner.volume()),partner.massFlow(),partner.law(),false),read,seconds);
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
            // The unit's UA (D16) is a reading: one within its deadband of the last stands, so a steady plant keeps its graph.
            if(planned.equals(law)&&ExchangerRelay.Conductance.same(planned.conductance(),law.conductance()))continue;
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
                boolean same=sameShares(PhaseLayout.totalAmounts(n.state()),x)&&plan.solids().empty()&&n.state().solids().empty();
                // An outlet at its account's enthalpy (L0 of batch 2026-11-17-column-cold-feed): already there within the deadband,
                // else the temperature whose flash holds it, the planned one where the PH equilibrium does not converge.
                if(plan.byEnthalpy()?!(same&&atEnthalpy(n.state(),plan.enthalpy(),t)):!(t==n.state().temperature()&&same)) {
                    if(plan.byEnthalpy())t=enthalpyTemperature(plan,p,x,checkpoint);
                    var unit=model.flashTP(t,p,x,checkpoint);double factor=n.inventory().volume()/unit.volume();
                    for(int c=0;c<x.length;c++)x[c]*=factor;
                    var state=model.flashTP(t,p,x,checkpoint);
                    if(!plan.solids().empty())state=state.withSolids(plan.solids().scale(factor));
                    if(nodes==null)nodes=new ArrayList<>(graph.reservoirs());
                    nodes.set(node,new PassiveNetwork.Reservoir(n.id(),n.elevation(),state,n.kind(),new PassiveNetwork.Inventory(n.inventory().volume(),PhaseLayout.totalAmounts(state),state.internalEnergy(),state.solids())));
                }
            }
            int valve=-1;
            // A port's valve is on the run out of its node; a void that is its own valve's port (a converter's drain) has it on the run into it.
            boolean into=plan.valve()==plan.node()&&n.kind()==PassiveNetwork.NodeKind.VOID;
            for(int e=0;e<graph.pipes().size();e++){var pipe=graph.pipes().get(e);if(pipe.control() instanceof FlowControl.Valve&&graph.reservoirs().get(into?pipe.second():pipe.first()).id()==plan.valve()){valve=e;break;}}
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
    /**
     * A generator at a planned enthalpy stands still while its own enthalpy per mole is within this many of the PH
     * equilibrium's tolerance ({@code FluidTpEquilibrium.SPECIFICATION_TOLERANCE}, per mole R T) of the plan's: ten, so a
     * generator set by a converged flash keeps its graph at the next slice. About 0.05 J/mol at 600 K, a millikelvin of oil.
     */
    public static final double ENTHALPY_DEADBAND=10*com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.SPECIFICATION_TOLERANCE;
    /** Whether {@code state}'s enthalpy per mole is the plan's {@code enthalpy} (J/mol) within {@link #ENTHALPY_DEADBAND} at {@code t}. */
    private static boolean atEnthalpy(FluidThermodynamics.State state,double enthalpy,double t) {
        double total=0;for(double n:PhaseLayout.totalAmounts(state))total+=n;
        return total>0&&Math.abs(state.enthalpy()/total-enthalpy)<=ENTHALPY_DEADBAND*com.wormzjl.icheme.science.thermo.PengRobinsonKernel.GAS_CONSTANT*Math.max(t,state.temperature());
    }
    /**
     * The temperature at which {@code x} holds the plan's enthalpy per mole at pressure {@code p}: the PH equilibrium's,
     * guessed at the plan's temperature; the plan's temperature where it does not converge (a rejected trial, never a hold: the
     * column books the residual).
     */
    private double enthalpyTemperature(PortPlan plan,double p,double[] x,Runnable checkpoint) {
        double total=0;for(double n:x)total+=n;
        FluidThermodynamics.State at;
        try{at=model.equilibriumPH(plan.temperature(),p,plan.enthalpy()*total,x.clone(),checkpoint);}catch(IllegalArgumentException refused){at=null;}
        return at!=null&&at.temperature()>0&&Double.isFinite(at.temperature())?at.temperature():plan.temperature();
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
                if(tube!=null&&!tube.pool()&&PortStreams.endMass(partnerModel(tube),tube.donor(),tube.port(),tube.volume())>0) {
                    double low=Math.max(NumericalBox.MINIMUM_TEMPERATURE,Math.min(contents.temperature(),tube.donor().temperature())-10);
                    double high=Math.min(NumericalBox.MAXIMUM_TEMPERATURE,Math.max(contents.temperature(),tube.donor().temperature())+10);
                    var curve=PortStreams.spanned(partnerModel(tube),tube.donor(),tube.port(),tube.volume(),low,high,tube.law().films(),checkpoint);
                    if(curve!=null)stream=new ExchangerRelay.Stream(curve,PortStreams.endSpecificEnthalpy(partnerModel(tube),tube.donor(),tube.port(),tube.volume()),tube.massFlow(),tube.law(),true);
                }
                planned=ExchangerRelay.planPool(pool,bath,stream,read,seconds);
            }
            if(planned.equals(pool))continue;
            if(nodes==null)nodes=new ArrayList<>(graph.reservoirs());
            nodes.set(i,new PassiveNetwork.Reservoir(node.id(),node.elevation(),contents,node.kind(),node.inventory().withPool(planned)));
        }
        return nodes==null?graph:new PassiveNetwork(nodes,graph.pipes(),graph.scheduledTransfers());
    }
    /** The model a coupled side's other stream is read on ({@link PortStreams#spanned}, {@link PortStreams#endMass}): this
     * solver's, or that of the island that stream is in (batch 2026-11-04-refinery-compounds, V2: each island carries its own
     * compound list). The stream functions read nothing else, so no second solver is built to read it (S-06). */
    private FluidThermodynamics partnerModel(ExchangerRelay.Partner partner) {
        var other=partner.model();
        return other==null?model:other;
    }
    /** The modes whose law the velocity cap saturates ({@link #saturated}): the throttling ones, and a mover on its own law,
     * whose rise reads the flow the cap lets through. */
    static boolean saturates(FlowControl.Mode mode) {
        return mode==FlowControl.Mode.PASSIVE||mode==FlowControl.Mode.PUMP_SUCTION_LIMIT||mode==FlowControl.Mode.VALVE_OPEN||mode==FlowControl.Mode.VALVE_CONFLICT
                ||mode==FlowControl.Mode.MOVER_LAW;
    }
    /** Which phases this state carries: the four fluid flags of {@link FluidThermodynamics#phaseBits} (hydrocarbon liquid 1,
     * hydrocarbon vapour 2, free water 4, steam 8) and 16 for solids. */
    static int phaseCode(FluidThermodynamics.State state) {
        return (state.solidMoments().mass()>0?16:0)|FluidThermodynamics.phaseBits(state);
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
    static boolean closedEdge(FlowControl.Mode mode,boolean closed){return closed||mode==FlowControl.Mode.CLOSED||mode==FlowControl.Mode.INLET_WRONG_PHASE;}
    private static int root(int[] parent,int node){while(parent[node]!=node){parent[node]=parent[parent[node]];node=parent[node];}return node;}
    /**
     * The pockets of a pass (hold F8): for every node, the pocket it belongs to (the index of a member), or -1. A pocket
     * is a set of junctions joined by pressure-coupling connections ({@link #couples}) with no pressure anchor: no coupling
     * connection to a node that is not a junction and no regulating valve drawing from it. A junction's pressure is
     * algebraic, and nothing places a pocket's level: shifting every member's pressure together changes no row but those
     * of the connections on its boundary, all prescribed-flow rows with a free head or none.
     */
    static int[] pockets(PassiveNetwork graph,List<FlowControl.Mode> modes,boolean[] boundaryClosed) {
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
     * and starts closed for this solve, the pocket isolated at its pressure ({@code StepEquations.retainedPressures}): on its
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
    /** The balance's law flows for the solve in progress; null where none was run (the static estimate stands in). */
    double[] balanceLawFlows;
    /** The last balance and the input graph it was computed for: every attempt of one step starts from the same graph. */
    private PassiveNetwork balanceFor;private BalanceStart.Start balanceCache;private boolean[] balanceClosedFor;
    /** Whether this solve starts from a carried point ({@link StartSeeds#startPoint}'s test) or needs the balance. */
    private boolean needsBalance(PassiveNetwork graph){return !graph.pipes().isEmpty()&&!carriedPoint(graph,identitiesOf(graph));}
    /**
     * Whether this solve starts from a carried point: the previous converged solve's structure is this one's (pipe
     * identities, node ids, a flow per connection) and its point is this solve's own. A rate solve warm-starts only on
     * value-identical input states (review 7.7); a rate solve's point starts a step only where it was written back into the
     * step's graph (the cold rate seed, {@code PassiveIntervalSolver.coldRateSeed}: owner decision of 2026-10-19, the plan's
     * fallback), so a step whose rate seed failed, or any solve on other states, starts from the balance instead.
     */
    boolean carriedPoint(PassiveNetwork graph,List<PassiveNetwork.Pipe.Identity> pipeIdentities) {
        if(!(sameLines(carry.pipes(),pipeIdentities)&&Arrays.equals(carry.nodeIds(),nodeIds(graph))&&carry.flows().length==graph.pipes().size()&&carry.lawFlows().length==graph.pipes().size()))return false;
        if(rateOnly&&!sameStates(carry.inputStates(),constants.inputStates))return false;
        if(carry.rateOnly()&&!rateOnly&&!sameFluidStates(carry.outputStates(),constants.inputStates))return false;
        return true;
    }
    /** The mint ({@link PassiveNetwork#mintJunctions}): every junction's state from the balance on the island's own states
     * under the start modes, its round 0 the supplier composition ({@link BalanceStart#supplierSeeds}); null for every other node; a
     * junction nothing reaches keeps the state it was built with. */
    List<FluidThermodynamics.State> balanceStarts(PassiveNetwork graph) {
        // Round 0 from what reaches each junction ({@link BalanceStart#supplierSeeds}); the balance solves the pressures from there.
        var seeds=balance.supplierSeeds(graph,()->{});var seeded=new ArrayList<PassiveNetwork.Reservoir>(graph.reservoirs().size());
        for(int i=0;i<graph.reservoirs().size();i++){var node=graph.reservoirs().get(i);seeded.add(node.junction()&&seeds.get(i)!=null&&seeds.get(i)!=node.state()?new PassiveNetwork.Reservoir(node.id(),node.elevation(),seeds.get(i),node.kind(),node.inventory()):node);}
        graph=new PassiveNetwork(seeded,graph.pipes(),graph.scheduledTransfers());
        var seededStates=graph.reservoirs().stream().map(PassiveNetwork.Reservoir::state).toList();
        // The mint's constants replace the solver's and stay (S-02): its suction readings, vapour lock and supply on the seeded
        // graph with no slice; the input states, held openings, standing set and series are the previous constants' objects.
        var held=constants;
        constants=SolveConstants.of(held.inputStates,graph.pipes().size(),()->{},held.heldOpenings,held.standing,held.series,graph,0);
        suctions.suctionFloors(graph,seededStates,constants);suctions.readSuctions(graph,0,constants);suctions.vapourLocks(graph,seededStates,constants.vapourLocked);
        var modes=devices.initialModes(graph);boolean[] closed=new boolean[graph.pipes().size()];
        for(int i=0;i<closed.length;i++)if(graph.pipes().get(i).effectiveBlockedDirections()==3){closed[i]=true;if(!(graph.pipes().get(i).control() instanceof FlowControl.Passive))modes.set(i,FlowControl.Mode.CLOSED);}
        if(graph.pipes().stream().anyMatch(pipe->pipe.control() instanceof FlowControl.Mover)){boolean[] refused=InletPhase.decide(model,graph,null,0);for(int i=0;i<refused.length;i++)if(refused[i]){closed[i]=true;modes.set(i,FlowControl.Mode.INLET_WRONG_PHASE);}}
        closures.closeDeadHeads(graph,closed,null);relieveSealedPockets(graph,modes,closed);
        var start=balance.balanceStart(graph,modes,closed,null,()->{},null);
        var states=new ArrayList<FluidThermodynamics.State>();
        for(int i=0;i<graph.reservoirs().size();i++)states.add(graph.reservoirs().get(i).junction()?start.graph().reservoirs().get(i).state():null);
        return states;
    }
    // ======== end of the t44 balance ========
    static int[][] incidence(PassiveNetwork graph) {
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
     * A closed connection seals in its current mode, a closed flow valve with a target too: the mover stands closed until a
     * pass opens the valve, and reopens on its rise ({@link DeviceModes#nextMode}); a pump on its suction limit before a shut
     * valve leaves its discharge's pressure in no row (START_HOLD_REVIEW.md, option S2).
     */
    static boolean sealedWithout(PassiveNetwork graph,int[][] nodeEdges,List<FlowControl.Mode> modes,boolean[] boundaryClosed,int node,int edge,int relief) {
        if(!graph.reservoirs().get(node).junction())return false;
        var members=new HashSet<Integer>();var queue=new ArrayDeque<Integer>();members.add(node);queue.add(node);
        while(!queue.isEmpty()) {
            int at=queue.remove();
            for(int e:nodeEdges[at]) {
                if(e==edge)continue;var mode=modes.get(e);var p=graph.pipes().get(e);
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
    /**
     * Whether a connection may carry {@code flow}'s direction: not blocked that way, not out of a void or into a generator,
     * not out of a dry end ({@link #dryEnd}). A phase port never refuses a direction for want of its first phase (decision
     * D11): a vessel that lacks it draws the next one of the port's priority order ({@link PhaseDraw}).
     */
    /** Whether a device's forward flow (first end to second) is one its ends forbid: into a generator, or out of a void. */
    static boolean forwardForbidden(PassiveNetwork graph,PassiveNetwork.Pipe pipe) {
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
        return PortStreams.spray(donor,flow>0?pipe.firstPort():pipe.secondPort())||receiver.inventory().tower()!=null&&!PortStreams.spray(receiver,flow>0?pipe.secondPort():pipe.firstPort());
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
        if(inventory.holdsNoAmounts())return true;
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
    /** This pass's emptying vessels ({@link PhaseActiveSet#emptyingGraph}), read by the junction continuity rows; null for none. */
    ConservativeTransport.Emptying[] emptyings;
    /** The phase code of a dry vessel in a pass's structure key: it carries no unknowns. */
    private static final int DRY=32;
    /**
     * Decision D54: whether a pass lays {@code node} out as dry, with no unknowns. A vessel that holds no fluid at the step
     * start ({@link #holdsNoFluidVessel}), whose seed is still its own state, is a receiver at the ambient with no fluid:
     * its state is its pressure and its solids, and it gives nothing. Once the start flows or a
     * converged pass's flows deliver into it ({@link StartSeeds#arrivingPhases}), or the booking fills it ({@link PhaseActiveSet#filled}), it is laid
     * out like every vessel.
     */
    static boolean dry(PassiveNetwork.Reservoir node,FluidThermodynamics.State seed){return holdsNoFluidVessel(node)&&seed==node.state();}
    /** The two liquid storage slots of a state. */
    private static final int[] LIQUID_SLOTS={FluidThermodynamics.State.LIQUID,FluidThermodynamics.State.AQUEOUS};
    /** The amounts of the state's liquids together, water last. */
    static double[] liquidMoles(FluidThermodynamics.State s) {
        double[] x=s.liquidAmounts(),a=s.aqueousAmountsView();for(int c=0;c<x.length;c++)x[c]+=a[c];return x;
    }
    /** The enthalpy of the state's liquids together, J. */
    static double liquidEnthalpy(FluidThermodynamics.State s) {
        double h=0;
        for(int slot:LIQUID_SLOTS){var phase=s.phase(slot);if(phase!=null)h+=PhaseLayout.sum(s.amountsView(slot))*phase.molarEnthalpy();}
        return h;
    }
    double carrierViscosity(FluidThermodynamics.State s) {
        if(!FluidThermodynamics.holdsFluid(s))return carrierViscosity(ambient());
        double value=0,volume=s.condensedVolume()+s.vaporVolume();
        for(int slot:LIQUID_SLOTS)if(s.slotVolume(slot)>0)value+=s.slotVolume(slot)*model.slotViscosity(s,slot,null,null);
        if(s.vaporVolume()>0)value+=s.vaporVolume()*model.slotViscosity(s,FluidThermodynamics.State.VAPOR,null,null);
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
    double viscosity(FluidThermodynamics.State s,FluidThermodynamics.Prepared prepared) {
        if(!FluidThermodynamics.holdsFluid(s))return viscosity(ambient(),null);
        var terms=prepared==null?null:prepared.viscosities();
        double value=0;
        for(int slot:LIQUID_SLOTS)if(s.slotVolume(slot)>0)value+=s.slotVolume(slot)*model.slotViscosity(s,slot,viscosities,terms);
        double liquidVolume=s.condensedVolume(),solidVolume=s.solidMoments().volume();
        if(liquidVolume>0&&solidVolume>0){double phi=solidVolume/(liquidVolume+solidVolume);value=SlurryTransport.effectiveViscosity(value/liquidVolume,Math.min(phi,0.62-1e-9))*(liquidVolume+solidVolume);}
        if(s.vaporVolume()>0)value+=s.vaporVolume()*model.slotViscosity(s,FluidThermodynamics.State.VAPOR,viscosities,terms);
        return value/(liquidVolume==0?s.vaporVolume():s.volume());
    }
    /** The equations of the last accepted solve, for the check that the block Jacobian sweep and an
     * independent column-by-column difference of {@link StepEquations#residual} produce the same entries;
     * {@code null} until a solve has been accepted. Nothing in production reads it. */
    /** Drops the retained last solve; the interval solver does after every accepted step. */
    void releaseLastSolve(){lastSolve=null;}
    StepEquations acceptedEquations(){return lastSolve==null?null:lastSolve.equations();}
    /** The point {@link #acceptedEquations} converged to. */
    double[] acceptedPoint(){return lastSolve==null?null:lastSolve.variables().clone();}

    /** Accepted hydraulic row, captured on the solver worker. Observational only; never fed back into the solve. */
    public record FlowSample(long host,double pressure,double massFlow,Double volumeFlow){public FlowSample{if(host<=0||!Double.isFinite(pressure)||!Double.isFinite(massFlow)||volumeFlow!=null&&!Double.isFinite(volumeFlow))throw new IllegalArgumentException("Invalid flow sample");}}
    public record PipeObservation(long pipe,double firstPressure,double secondPressure,double firstElevation,double secondElevation,
                                  double columnDensity,double temperature,boolean flowing,List<Double> lengths,List<Double> losses,double massFlow,double density,List<FlowSample> flowSamples,
                                  double npshRatio,boolean dry,double gasRatio,double npshRequired,double vapourShare,double valveDrop,Cavitation cavitation,
                                  double condensedShare) {
        /** A flowing liquid valve's state for its noise (owner 2026-11-10, option A; {@link StepObservations#cavitation}): NONE
         * on every other row and where the thermodynamics cannot say. */
        public enum Cavitation { NONE, PLAIN, CAVITATING, FLASHING }
        /** Without a centrifugal compressor's wet suction read (code base review N2): no condensed share (NaN). */
        public PipeObservation(long pipe,double firstPressure,double secondPressure,double firstElevation,double secondElevation,double columnDensity,double temperature,boolean flowing,List<Double> lengths,List<Double> losses,double massFlow,double density,List<FlowSample> flowSamples,double npshRatio,boolean dry,double gasRatio,double npshRequired,double vapourShare,double valveDrop,Cavitation cavitation){this(pipe,firstPressure,secondPressure,firstElevation,secondElevation,columnDensity,temperature,flowing,lengths,losses,massFlow,density,flowSamples,npshRatio,dry,gasRatio,npshRequired,vapourShare,valveDrop,cavitation,Double.NaN);}
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
        public PipeObservation withFlowSamples(List<FlowSample> samples){return new PipeObservation(pipe,firstPressure,secondPressure,firstElevation,secondElevation,columnDensity,temperature,flowing,lengths,losses,massFlow,density,samples,npshRatio,dry,gasRatio,npshRequired,vapourShare,valveDrop,cavitation,condensedShare);}
        /** This row with a liquid pump's suction read (W10b, W10c, W10d): its NPSHA over NPSH3 (NaN: none), whether it runs dry,
         * its suction's vapour share in Turpin onsets, {@code r = α/α_T} (NaN: none), its NPSH3 at the accepted flow (m; NaN:
         * none) and its suction's vapour volume share α (NaN: none). The pump page reads the last two. A liquid pump's ratio is of
         * bubbles: NaN for one dense fluid, which holds none (S5); its α is the head share's, which follows the density. */
        public PipeObservation withSuction(double ratio,boolean value,double gas,double required,double share){return new PipeObservation(pipe,firstPressure,secondPressure,firstElevation,secondElevation,columnDensity,temperature,flowing,lengths,losses,massFlow,density,flowSamples,ratio,value,gas,required,share,valveDrop,cavitation,condensedShare);}
        /** This row with a valve's read: its own pressure drop (Pa) and, for a flowing liquid, its cavitation. */
        public PipeObservation withValve(double drop,Cavitation c){return new PipeObservation(pipe,firstPressure,secondPressure,firstElevation,secondElevation,columnDensity,temperature,flowing,lengths,losses,massFlow,density,flowSamples,npshRatio,dry,gasRatio,npshRequired,vapourShare,drop,c,condensedShare);}
        /** This row with a wet read (code base review N2, N4): for a running centrifugal compressor the condensed mass share
         * of the supply its suction line delivers, the number its wet efficiency reads ({@link PassiveStepSolver#readSuctions});
         * for a steam turbine passing steam the condensed mass share of its own exhaust ({@link StepObservations#exhaustMoisture}). */
        public PipeObservation withCondensed(double share){return new PipeObservation(pipe,firstPressure,secondPressure,firstElevation,secondElevation,columnDensity,temperature,flowing,lengths,losses,massFlow,density,flowSamples,npshRatio,dry,gasRatio,npshRequired,vapourShare,valveDrop,cavitation,share);}
        public static List<PipeObservation> atRest(PassiveNetwork graph,com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics model){
            var result=new ArrayList<PipeObservation>();
            for(var pipe:graph.pipes()){
                var a=graph.reservoirs().get(pipe.first());var b=graph.reservoirs().get(pipe.second());
                double density=PortStreams.columnDensity(model,a.state(),pipe.firstPort(),pipe.volume());
                double pa=a.state().pressure()+PortStreams.portHead(model,a,pipe.firstPort(),a.state()),pb=b.state().pressure()+PortStreams.portHead(model,b,pipe.secondPort(),b.state());
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
