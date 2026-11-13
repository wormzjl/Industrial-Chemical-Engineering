package com.wormzjl.icheme.science.fluid.network;

import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.GRAVITY;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.boundaryAllowed;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.dryEnd;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.incidence;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.nodeIds;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.reopenBand;
import static com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.sameLines;
import com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.PassStart;
import com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.Result;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import java.util.*;
import java.util.function.IntPredicate;

/**
 * The one-way closures of a step solve: the passive runs closed before the first pass because their start point leaves
 * them no direction they may carry ({@link #closeDeadHeads}, {@link #closeIllegalStarts}), a boundary a converged pass
 * found crossed the forbidden way, closed for the rest of the solve ({@link #closeBoundary}) or restarted the allowed way
 * ({@link #allowedRestart}), and the closed runs a converged step's own end states would reopen ({@link #reopenable},
 * which the interval solver reads through the solver). The runs these walk are {@link Runs}. One per solver
 * ({@code PassiveStepSolver.closures}), built with it; it reads the solver's accepted structure, its laws and its start
 * points through {@code solver} and holds no state of its own. The exemptions the interval solver asks for
 * ({@code PassiveStepSolver.reopenNext}, {@code keepOpen}) stay the solver's: the solve hands them in as {@code keep}
 * (S-01, batch 2026-11-13-codebase-review, D-S5: moved out of the solver with every body unchanged but its qualifiers).
 */
final class OneWayClosures {
    /** The solver whose closures these are: its accepted structure, its laws and its start points. */
    private final PassiveStepSolver solver;
    /** The solver's thermodynamics, the same object. */
    private final FluidThermodynamics model;
    OneWayClosures(PassiveStepSolver solver,FluidThermodynamics model){this.solver=solver;this.model=model;}
    /** The runs of a graph: a run is a maximal chain of connections through degree-two junctions no actuator touches.
     * {@link #reopenable} and {@link #closeDeadHeads} walk them, each over the connections
     * its own rule admits. */
    private record Runs(boolean[] interior,int[][] nodeEdges) {
        static Runs of(PassiveNetwork graph) {
            int nodes=graph.reservoirs().size();int[][] nodeEdges=incidence(graph);boolean[] actuated=new boolean[nodes];
            for(var pipe:graph.pipes())if(!(pipe.control() instanceof FlowControl.Passive)){actuated[pipe.first()]=true;actuated[pipe.second()]=true;}
            boolean[] interior=new boolean[nodes];
            for(int i=0;i<nodes;i++)interior[i]=graph.reservoirs().get(i).junction()&&nodeEdges[i].length==2&&!actuated[i];
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
     * step's own end states in a direction every link allows, by more than {@link PassiveStepSolver#reopenBand}; a forward run stands in
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
            // Each end of the run stands in its own end port's column over the run ({@link PortStreams#columnDensity}), at its own end's
            // driving pressure.
            var startPort=graph.pipes().get(chain.getFirst()).portAt(start);var finishPort=graph.pipes().get(chain.getLast()).portAt(finish);
            double pa=PortStreams.endPressure(model,graph.reservoirs().get(start),startPort,a,()->PortStreams.runColumnDensity(model,graph,chain,b,finishPort)),
                    pb=PortStreams.endPressure(model,graph.reservoirs().get(finish),finishPort,b,()->PortStreams.runColumnDensity(model,graph,chain,a,startPort));
            double dz=graph.reservoirs().get(finish).elevation()-graph.reservoirs().get(start).elevation(),difference=pa-pb;
            double band=reopenBand(pa,pb);
            double forwardDrive=difference-PortStreams.runColumnDensity(model,graph,chain,a,startPort)*GRAVITY*dz,reverseDrive=difference-PortStreams.runColumnDensity(model,graph,chain,b,finishPort)*GRAVITY*dz;
            if(forwardAllowed&&forwardDrive>band||reverseAllowed&&reverseDrive<-band)for(int link:chain)reopen.add(graph.pipes().get(link).identity());
        }
        return reopen;
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
    double[] allowedRestart(PassiveNetwork graph,int edge) {
        var pipe=graph.pipes().get(edge);var a=graph.reservoirs().get(pipe.first()).kind();var b=graph.reservoirs().get(pipe.second()).kind();
        boolean end=a==PassiveNetwork.NodeKind.VOID||a==PassiveNetwork.NodeKind.GENERATOR||b==PassiveNetwork.NodeKind.VOID||b==PassiveNetwork.NodeKind.GENERATOR;
        if(!end)return null;
        double flow=solver.initialMassFlow(graph,pipe);
        if(!(Math.abs(flow)>1e-10)||!boundaryAllowed(graph,pipe,flow))return null;
        double[] start=new double[graph.pipes().size()];Arrays.fill(start,Double.NaN);start[edge]=flow;
        return start;
    }
    static void closeBoundary(PassiveNetwork graph,int edge,boolean[] boundaryClosed,List<FlowControl.Mode> modes) {
        boundaryClosed[edge]=true;
        if(!(graph.pipes().get(edge).control() instanceof FlowControl.Passive))modes.set(edge,FlowControl.Mode.CLOSED);
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
     * stands in the first end's fluid and a reverse flow in the second's, the pairing {@link StepEquations#columnEnds} states);
     * neither end of the run is a zero-holdup junction (a junction's pressure is an output of the previous solve, while a
     * boundary's is fixed and a vessel's moves monotonically against the flow that delivers it, so a drive that refuses a
     * direction at the start refuses it everywhere the solve can go; the run is a chain of
     * {@link Runs}); only passive connections (an actuator decides its own mode, and a boundary direction is never closed on
     * a device's symptom). It cannot cycle: monotone within a solve, taken once before any pass, never cleared, and per
     * solve, so a line whose generator is raised afterwards opens again.
     */
    void closeDeadHeads(PassiveNetwork graph,boolean[] boundaryClosed,boolean[] keep) {
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
            // Each direction in its donor end port's column over the run ({@link PortStreams#columnDensity}), at the ends' driving pressures.
            var startPort=graph.pipes().get(chain.getFirst()).portAt(start);var finishPort=graph.pipes().get(chain.getLast()).portAt(finish);
            double dz=b.elevation()-a.elevation(),difference=PortStreams.endPressure(model,a,startPort,a.state(),()->PortStreams.runColumnDensity(model,graph,chain,b.state(),finishPort))
                    -PortStreams.endPressure(model,b,finishPort,b.state(),()->PortStreams.runColumnDensity(model,graph,chain,a.state(),startPort));
            boolean forward=forwardAllowed&&difference-PortStreams.runColumnDensity(model,graph,chain,a.state(),startPort)*GRAVITY*dz>0;
            boolean reverse=reverseAllowed&&difference-PortStreams.runColumnDensity(model,graph,chain,b.state(),finishPort)*GRAVITY*dz<0;
            if(keep!=null){boolean kept=false;for(int link:chain)kept|=keep[link];if(kept)continue;}
            if(!forward&&!reverse)for(int link:chain)boundaryClosed[link]=true;
        }
    }
    /**
     * The {@code illegalDirection} closure of the pass loop, taken once at the start of the solve on the point pass 0
     * starts from: a passive connection whose start-point flow runs in a direction its own boundary forbids
     * ({@link PassiveStepSolver#boundaryAllowed}) is closed for this solve exactly as that rule closes it after a converged pass. The
     * direction is the one {@link StartSeeds#startPoint} gives it; where that gives none ({@code |q| <= 1e-10}, every connection the
     * previous point had closed), the direction of {@link PassiveStepSolver#initialMassFlow} on the current states. Derived afresh from
     * this solve's start point, so a connection whose pressures turn legal opens again at the next solve. Passive
     * connections only (devices are decided first, see the pass loop). Why: an island coming to rest next to a void or a
     * generator leaves the one-way connections with flows that point the forbidden way at the next solve's start, and the
     * pass loop would close them only after a converged pass, which at rest is a zero-flow stall. Only once this solver
     * has accepted a solve on the same structure (a first solve's junction pressures are the compiler's guess, not a
     * state), and only where the static driving pressure forbids the direction with either end's density
     * ({@link #illegalWithEitherDensity}). {@code keep} names the connections the interval solver reopened.
     */
    void closeIllegalStarts(PassiveNetwork graph,List<FlowControl.Mode> modes,boolean[] boundaryClosed,boolean[] keep) {
        // Decision D54: a passive run whose static drive points out of a dry end (a vessel holding no fluid, the top port of a
        // slack vessel holding no gas) carries nothing: the end gives nothing and the run stands in what its other end would
        // send (the column of a dry end is the other end's), so the drive is no guess. Closed for this solve, on every solve.
        for(int i=0;i<graph.pipes().size();i++) {
            var pipe=graph.pipes().get(i);var a=graph.reservoirs().get(pipe.first());var b=graph.reservoirs().get(pipe.second());
            if(boundaryClosed[i]||!(pipe.control() instanceof FlowControl.Passive)||keep!=null&&keep[i])continue;
            boolean dryA=dryEnd(a,pipe.firstPort()),dryB=dryEnd(b,pipe.secondPort());if(!dryA&&!dryB)continue;
            double drive=solver.staticDrive(graph,pipe,PortStreams.endPressure(model,graph,pipe,pipe.first())-PortStreams.endPressure(model,graph,pipe,pipe.second()));
            if(dryA&&drive>0||dryB&&drive<0||dryA&&dryB)boundaryClosed[i]=true;
        }
        if(!(sameLines(solver.acceptedPipes,solver.identitiesOf(graph))&&Arrays.equals(solver.acceptedNodeIds,nodeIds(graph))))return;
        int edges=graph.pipes().size();
        double[] start=new double[edges];
        solver.seeding.startPoint(graph,modes,boundaryClosed,solver.identitiesOf(graph),null,start,new double[edges],new boolean[edges],PassStart.NONE);
        for(int i=0;i<edges;i++) {
            var pipe=graph.pipes().get(i);
            if(boundaryClosed[i]||!(pipe.control() instanceof FlowControl.Passive))continue;
            if(keep!=null&&keep[i])continue;
            double q=start[i];
            if(Math.abs(q)<=1e-10) {
                // No start flow (a settled line, one the previous point had closed): the connection's own law on the current
                // states decides, read only beyond the reopen band, the one-way rule's tolerance ({@link BalanceStart#balanceStart}): a
                // settled line's drive is the junction pressures' roundoff (a line read -7.2e-7 kg/s and was closed for good).
                var a=graph.reservoirs().get(pipe.first());var b=graph.reservoirs().get(pipe.second());
                double pa=PortStreams.endPressure(model,graph,pipe,pipe.first()),pb=PortStreams.endPressure(model,graph,pipe,pipe.second());
                if(Math.abs(solver.staticDrive(graph,pipe,pa-pb))<=reopenBand(pa,pb))continue;
                q=solver.initialMassFlow(graph,pipe);
            }
            if(Math.abs(q)>1e-10&&!boundaryAllowed(graph,pipe,q)) {
                if(illegalWithEitherDensity(graph,pipe,q))boundaryClosed[i]=true;
            }
        }
    }
    /**
     * Whether the static driving pressure {@code P_a - P_b - rho g (z_b - z_a)} has the sign of {@code flow} with rho =
     * either endpoint's density. {@link PassiveStepSolver#initialMassFlow} states the row's column ({@link PassiveStepSolver#columnFromFirst}), and where
     * the pipe could stand full of either fluid that is the column of the end with the higher pressure: on a falling water
     * line into a nitrogen tank that is the tank's gas once the tank passes the generator's pressure, so the estimate read
     * a 4 m water column as nitrogen and reported a 4.8 kg/s backflow while the column still drove 4.2 kPa forward, and
     * the connection stayed closed for good. Between two gas endpoints both densities give the same sign.
     */
    private boolean illegalWithEitherDensity(PassiveNetwork graph,PassiveNetwork.Pipe pipe,double flow) {
        var a=graph.reservoirs().get(pipe.first());var b=graph.reservoirs().get(pipe.second());
        double dp=PortStreams.endPressure(model,a,pipe.firstPort(),a.state())-PortStreams.endPressure(model,b,pipe.secondPort(),b.state()),dz=b.elevation()-a.elevation();
        // Each end's density is what its own port stands in the connection (decision D34, {@link PortStreams#columnDensity}).
        double drivingFirst=dp-PortStreams.columnDensity(model,a.state(),pipe.firstPort(),pipe.volume())*GRAVITY*dz;if(!(Math.signum(drivingFirst)==Math.signum(flow)))return false;
        double drivingSecond=dp-PortStreams.columnDensity(model,b.state(),pipe.secondPort(),pipe.volume())*GRAVITY*dz;if(!(Math.signum(drivingSecond)==Math.signum(flow)))return false;
        return true;
    }
}
