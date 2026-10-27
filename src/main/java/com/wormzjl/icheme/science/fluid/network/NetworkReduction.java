package com.wormzjl.icheme.science.fluid.network;

import java.util.*;
import java.util.function.LongBinaryOperator;
import java.util.function.LongPredicate;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.icheme.science.fluid.transport.MixtureViscosity;

/**
 * The network reduction stage: what of a placed network is solved, and as what (owner decisions D17 to D19). It runs
 * where islands are formed ({@code TopologyCompiler.compile}, called by {@code PhysicalFluidTopology.compile} for every
 * topology event and every load), in this order: connectivity (placed blocks become nodes and links; only pipes
 * connect, owner decision D20); dead ends ({@link #pruneDeadEnds}, D18: a branch that leads to no tank, generator or
 * void carries no flow and is left out, its devices owned by no island); runs (maximal chains through blocks with two
 * connections become one connection); split at fixed boundaries ({@link #splitAtFixedBoundaries}, D17: parts of a
 * network that meet only at a generator or a void are separate islands, the boundary belonging to each); direction
 * feasibility ({@link #drives}); rest ({@link #atRest}, D19: an island nothing can drive is not solved, its interval is
 * the identity). A junction has no start state of its own (owner decision D54): its buffer is a relaxation row's state
 * (D46), minted from the island-wide fixed point's last round ({@link PassiveNetwork#mintJunctions}). Dead ends and the
 * boundary split read no state and commute, so every compile of the same blocks reduces them the same way; the drives
 * and the rest test read the island's states and are evaluated on the graph every interval job receives
 * ({@link PassiveIntervalSolver#solve}), so every path that solves an island decides the same way, from a worker.
 */
public final class NetworkReduction {
    private NetworkReduction() {}

    /**
     * Step 2 (owner decision D18): removes from {@code active} every link of a branch that ends at no destination, and
     * returns them. A node that is not a destination and keeps at most one active link is a dead end: its last link goes,
     * and its neighbour is examined again, so a whole tree of pipes, pumps, compressors, valves and filters hanging off the
     * network with nothing but open ends beyond it is removed leaf by leaf. A cycle is never removed (flow can circulate
     * in it), and a destination (a tank, a generator, a void) never is.
     *
     * @param adjacent    every node's links, each list in link order
     * @param other       the far end of a link from a node: {@code other.applyAsLong(link, node)}
     * @param destination whether a node is a destination (a tank or a fixed boundary)
     */
    public static Set<Long> pruneDeadEnds(Map<Long,List<Long>> adjacent,LongBinaryOperator other,LongPredicate destination,Set<Long> active) {
        var dead=new TreeSet<Long>();var queue=new ArrayDeque<Long>();
        for(var entry:new TreeMap<>(adjacent).entrySet())if(!destination.test(entry.getKey())&&entry.getValue().size()<=1)queue.add(entry.getKey());
        while(!queue.isEmpty()) {
            long id=queue.remove();if(destination.test(id))continue;
            var remaining=adjacent.get(id).stream().filter(active::contains).toList();if(remaining.size()!=1)continue;
            long link=remaining.getFirst();active.remove(link);dead.add(link);queue.add(other.applyAsLong(link,id));
        }
        return dead;
    }

    /**
     * Step 4 (owner decision D17): the islands, as the connected parts of the carrying links with every fixed boundary (a
     * generator or a void) cut out. A fixed boundary belongs to every island that reaches it, every other node to exactly
     * one. A walk never goes on through a fixed boundary; one started from a fixed boundary starts once from each of its
     * carrying links, in link order, so a component no fixed boundary cuts is found at the same place in the island order
     * as when fixed boundaries joined islands.
     *
     * @param starts   the nodes islands are started from, in order (every node that ends a run)
     * @param carrying the links that carry flow (after step 2, on a run)
     * @param fixed    whether a node is a fixed boundary
     */
    public static List<Set<Long>> splitAtFixedBoundaries(Collection<Long> starts,Map<Long,List<Long>> adjacent,LongBinaryOperator other,LongPredicate fixed,Set<Long> carrying) {
        var islands=new ArrayList<Set<Long>>();var seen=new HashSet<Long>();var queue=new ArrayDeque<Long>();
        for(long start:starts) {
            var from=new ArrayList<Long>();
            if(fixed.test(start)){for(long link:adjacent.get(start))if(carrying.contains(link))from.add(other.applyAsLong(link,start));}
            else if(adjacent.get(start).stream().anyMatch(carrying::contains))from.add(start);
            for(long first:from) {
                if(fixed.test(first)||!seen.add(first))continue;
                var members=new TreeSet<Long>();queue.add(first);
                while(!queue.isEmpty()) {
                    long id=queue.remove();members.add(id);
                    for(long link:adjacent.get(id))if(carrying.contains(link)) {
                        long next=other.applyAsLong(link,id);
                        if(fixed.test(next))members.add(next);else if(seen.add(next))queue.add(next);
                    }
                }
                islands.add(members);
            }
        }
        return islands;
    }

    /**
     * The static drive of every connection, in Pa, as {forward, reverse}: the end pressures (a tank's bottom port with its
     * level head) less the column between the ends that the donor end's port stands in the connection
     * ({@link PassiveStepSolver#columnDensity}, owner decision D34), positive when it pushes that way;
     * {@link Double#NEGATIVE_INFINITY} for a direction the one-way rules forbid. The rest step ({@link #atRest}) reads its
     * size against {@link #REST_RESOLUTION}.
     */
    static double[][] drives(PassiveNetwork graph,FluidThermodynamics model) {
        var drive=new double[graph.pipes().size()][2];
        for(int i=0;i<drive.length;i++) {
            var pipe=graph.pipes().get(i);var a=graph.reservoirs().get(pipe.first());var b=graph.reservoirs().get(pipe.second());
            double pa=a.state().pressure()+PassiveStepSolver.portHead(model,a,pipe.firstPort(),a.state());
            double pb=b.state().pressure()+PassiveStepSolver.portHead(model,b,pipe.secondPort(),b.state());
            double dz=b.elevation()-a.elevation(),difference=pa-pb;
            drive[i][0]=PassiveStepSolver.boundaryAllowed(graph,pipe,1)
                    ?difference-PassiveStepSolver.columnDensity(model,a.state(),pipe.firstPort(),pipe.volume())*PassiveStepSolver.GRAVITY*dz:Double.NEGATIVE_INFINITY;
            drive[i][1]=PassiveStepSolver.boundaryAllowed(graph,pipe,-1)
                    ?-(difference-PassiveStepSolver.columnDensity(model,b.state(),pipe.secondPort(),pipe.volume())*PassiveStepSolver.GRAVITY*dz):Double.NEGATIVE_INFINITY;
        }
        return drive;
    }

    /**
     * Step 6, rest (owner decisions D19 and D22): whether nothing in an island can drive a flow, so its interval is the
     * identity and it is not solved ({@link PassiveIntervalSolver#solve} returns {@link #restInterval}; the island then
     * certifies as at rest). Decided from the structure and the present states only, before any Newton:
     * <ul>
     * <li>Only islands holding no trace an accepted step would flush (decision D16), no module transfer and no open vessel
     * that vents or overflows ({@link OpenVessel#releasing}) are asked.
     * Solids do not keep an island awake (owner decision D23 (6)): a particle moves only with the flow that carries it
     * and grows or melts only in a vessel's refresh, which runs before this test. At an interval start every transport
     * closure is read as open (owner decision D33), since that interval re-decides them ({@link #reopened}); a filter
     * stopped at its capacity stays closed. A pressure valve drives forward by the smaller of its static drive and its
     * excess over the set point, never back; a flow valve only throttles, so it drives forward by its static drive (nothing
     * at a zero target), never back; the rest interval reports either CLOSED. An empty vessel stands at the ambient
     * and gives nothing (decision D54).</li>
     * <li>A vessel whose wall moved is not at rest: a layout refresh rebuilt it to another capacity (batch
     * 2026-10-26-backward-compat), and its contents still stand at the old one, its state missing its wall row
     * ({@link PhaseLayout#wallRow}) by more than the gauge reads ({@link #WALL_RESOLUTION}). The interval's refresh closes
     * it to the new wall, and the step moves what that drives.</li>
     * <li>An island that holds no stock (no generator, no vessel holding anything) is at rest whatever its devices are
     * set to: a junction's buffer is never material (D46), and one no source reaches holds a placeholder (D55).</li>
     * <li>A pump or compressor that cannot move anything this slice (set to no flow, or refused for the slice,
     * {@code refused}) is a closed connection; any other at its target moves its set flow whatever the pressures, and
     * the island is solved.</li>
     * <li>Otherwise it is at rest when the gauge reads no drive and no flow (owner decisions D42, D43, D58). A gauge stands
     * where stock is; a junction holds no stock and no gauge. So: no connection between two stock nodes has a static drive
     * ({@link #drives}) above {@link #REST_RESOLUTION} in an allowed direction, no path of junctions between two such
     * nodes drives above it ({@link #pathDriven}), no loop of connections has a net drive above it ({@link #loopDriven}),
     * and, when the state is a step's end, no connection carries more than the flow the resolution drives through it
     * ({@link #resolutionFlow}). The state itself is never rounded; it is only no longer stepped.</li>
     * </ul>
     *
     * @param flows   the mass flows of the step that ended in this state, or null at an interval start (flows are not state)
     * @param refused the movers refused for the slice ({@link InletPhase#decide}), one flag per connection; null for none
     */
    public static boolean atRest(PassiveNetwork graph,FluidThermodynamics model,double[] flows,boolean[] refused) {
        // Material a module delivers or withdraws in the interval moves stock whatever the hydraulics do.
        if(!graph.scheduledTransfers().isEmpty())return false;
        boolean stock=false;
        for(var node:graph.reservoirs()) {
            if(node.kind()==PassiveNetwork.NodeKind.PORT)return false;
            stock|=node.kind()==PassiveNetwork.NodeKind.GENERATOR||node.kind()==PassiveNetwork.NodeKind.RESERVOIR&&!PassiveStepSolver.holdsNoFluid(node);
            if(!node.fixed()&&flushable(node))return false;
            // An open vessel holding gas or liquid above its fill height gives it to the atmosphere (OpenVessel).
            if(OpenVessel.releasing(node))return false;
            // A kettle's pool heated by its bundle (W7) moves heat whatever the hydraulics do.
            if(PassiveStepSolver.poolHeat(node)!=0)return false;
            if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR&&!PassiveStepSolver.holdsNoFluid(node)&&Math.abs(PhaseLayout.wallRow(node.state(),node.inventory().wallCapacity()))>WALL_RESOLUTION)return false;
        }
        // Nothing to move: no generator, no vessel holding fluid (solids alone do not move, D33: they ride the liquid; voids
        // only receive, and a junction's buffer is its relaxation row's state, never material, D46; a minted one no source
        // reaches holds a placeholder, D55).
        if(!stock)return true;
        boolean[] closed=closedMovers(graph,refused);
        for(int i=0;i<closed.length;i++)if(!closed[i]&&graph.pipes().get(i).control() instanceof FlowControl.Mover)return false;
        // The closures are read as the caller hands them: at an interval start every one but a stopped filter's is open
        // (owner decision D33, {@link #reopened}), at a step's end those its interval decided stand.
        var drives=drives(graph,model);
        for(int i=0;i<drives.length;i++) {
            // A closed mover carries nothing either way.
            if(closed[i]){drives[i][0]=drives[i][1]=Double.NEGATIVE_INFINITY;continue;}
            // A configured valve passes only from its first end (A211). A pressure valve passes only while that end stands
            // above its set point; a flow valve only throttles, so it passes the static drive, and nothing at a zero target.
            if(graph.pipes().get(i).control() instanceof FlowControl.Valve valve&&!valve.bare()) {
                if(valve.pressureControl())drives[i][0]=Math.min(drives[i][0],graph.reservoirs().get(graph.pipes().get(i).first()).state().pressure()-valve.targetPressure());
                else if(!(valve.targetMassFlow(1)>0))drives[i][0]=Double.NEGATIVE_INFINITY;
                drives[i][1]=Double.NEGATIVE_INFINITY;
            }
        }
        // Connections between two gauges; a connection with a junction end is read along its paths below.
        for(int i=0;i<drives.length;i++) {
            var pipe=graph.pipes().get(i);
            if(graph.reservoirs().get(pipe.first()).junction()||graph.reservoirs().get(pipe.second()).junction())continue;
            if(drives[i][0]>REST_RESOLUTION||drives[i][1]>REST_RESOLUTION)return false;
        }
        if(pathDriven(graph,drives))return false;
        if(loopDriven(graph,drives))return false;
        if(flows!=null) {
            var scratch=new MixtureViscosity.Workspace();
            for(int i=0;i<flows.length;i++)if(Math.abs(flows[i])>resolutionFlow(graph,model,i,scratch))return false;
        }
        return true;
    }
    /** {@code graph} with every transport closure lifted ({@link PassiveNetwork.Pipe#blockedDirections} zero) except a
     * filter's stopped at its capacity ({@link InlineFilter#stoppedAtCapacity}), which stands until the cake is cleared:
     * the closures as an interval start reads them (owner decision D33); the solid events then decide them again on the
     * committed ones, and a closure nothing moves keeps (D62, {@code SolidEventIntegrator.carried}).
     * {@link PassiveIntervalSolver#solve} starts every interval from it, so the rest test, the slice's inlet decision and
     * both integrations read it; so do the device view
     * of a mover's supply and {@link #atRest(PassiveNetwork, FluidThermodynamics)}. */
    public static PassiveNetwork reopened(PassiveNetwork graph) {
        if(graph.pipes().stream().allMatch(p->p.blockedDirections()==0||stopped(p)))return graph;
        return new PassiveNetwork(graph.reservoirs(),graph.pipes().stream().map(p->stopped(p)?p:p.withBlockedDirections(0)).toList(),graph.scheduledTransfers());
    }
    private static boolean stopped(PassiveNetwork.Pipe pipe){return pipe.filter()!=null&&pipe.filter().stoppedAtCapacity();}
    /** {@link #atRest} with no mover refused. */
    public static boolean atRest(PassiveNetwork graph,FluidThermodynamics model,double[] flows){return atRest(graph,model,flows,null);}
    /** {@link #atRest} at an interval start: the committed state, its closures read open ({@link #reopened}), no flows. */
    public static boolean atRest(PassiveNetwork graph,FluidThermodynamics model){return atRest(reopened(graph),model,null,null);}
    /** The movers that move nothing this slice: set to no flow, or refused ({@code refused}, null for none). */
    private static boolean[] closedMovers(PassiveNetwork graph,boolean[] refused) {
        boolean[] closed=new boolean[graph.pipes().size()];
        for(int i=0;i<closed.length;i++)if(graph.pipes().get(i).control() instanceof FlowControl.Mover mover)
            closed[i]=mover.targetVolumeFlow()==0||refused!=null&&refused.length==closed.length&&refused[i];
        return closed;
    }
    /**
     * The gauge's pressure resolution, Pa (owner decision D42: "Further precision is pointless"). A static drive at or
     * below it is no drive, and a flow at or below what it drives through a connection ({@link #resolutionFlow}) is no
     * flow. It belongs to the rest test only: the solver keeps full precision, the flow law has no dead band, and no state
     * is ever rounded.
     */
    public static final double REST_RESOLUTION=.1;
    /**
     * The gauge resolution on a vessel's wall row ({@link PhaseLayout#wallRow}): {@link #REST_RESOLUTION} on its slack
     * branch, about 2e-6. A closed state meets the row to {@code InventoryEquilibrium.CLOSURE} (1e-11) and a step's end to
     * the Newton's 1e-9; a vessel rebuilt to another capacity misses it by the fraction its capacity moved.
     */
    static final double WALL_RESOLUTION=2*REST_RESOLUTION/PhaseLayout.AMBIENT_PRESSURE;
    /**
     * The flow (kg/s) that {@link #REST_RESOLUTION} drives through connection {@code edge}: the connection's own law on its
     * laminar branch, the resolution over the law's slope at zero flow (a run's Poiseuille slope; an inline filter's clean
     * cake law at its present loading, as {@link #loss} states it), in the fluid each end's port draws first, the smaller of
     * the two ends. A water run of 1 m and 0.05 m: about 0.017 kg/s; the same run in 1 atm nitrogen about 0.001 kg/s.
     */
    static double resolutionFlow(PassiveNetwork graph,FluidThermodynamics model,int edge,MixtureViscosity.Workspace scratch) {
        var pipe=graph.pipes().get(edge);double flow=Double.POSITIVE_INFINITY;
        for(int node:new int[]{pipe.first(),pipe.second()}) {
            var s=graph.reservoirs().get(node).state();var port=pipe.portAt(node);
            double density=PassiveStepSolver.endDensity(model,s,port,pipe.volume());int phase=PhaseDraw.leading(model,s,port);
            double viscosity=PhaseDraw.mixedLiquids(s,port)?PassiveStepSolver.mixedViscosity(model,s,pipe.volume(),scratch):phase<0?viscosity(model,s,scratch):model.phaseViscosity(s,phase,scratch,null);
            double slope;
            if(pipe.filter()==null)slope=pipe.loss(0,density,viscosity).massFlowDerivative();
            else{double loading=pipe.filter().loading();slope=pipe.filter().cleanResistance()*(viscosity/.001)*(1+99*loading*loading)/density;}
            flow=Math.min(flow,REST_RESOLUTION/slope);
        }
        return flow;
    }
    /** Whether an accepted step would flush a trace of this node (decision D16): its state is then not the rest state. */
    private static boolean flushable(PassiveNetwork.Reservoir node) {
        var held=node.inventory().moles();double total=0;for(double amount:held)total+=amount;
        for(double amount:held)if(amount>0&&amount<PassiveIntervalSolver.TRACE_FLUSH_FRACTION*total)return true;
        return false;
    }
    /**
     * Owner decision D43: whether a loop of connections has a net drive above {@link #REST_RESOLUTION} in a direction every
     * connection of it allows. Only nodes that are not fixed boundaries close a loop: a generator only supplies and a void
     * only receives, so nothing circulates through either. The loops are the fundamental cycles of a spanning forest of
     * those nodes (breadth first, in node and connection order), each closed by one connection outside the forest. A loop's
     * net drive one way is the sum of its connections' static drives that way ({@link #drives}): the end pressures cancel
     * around the loop, so what is left is its columns (and a tank's level heads), the drive of a thermosiphon. Each
     * connection may stand below the resolution while the loop as a whole circulates; a loop with no net drive does not.
     */
    static boolean loopDriven(PassiveNetwork graph,double[][] drives) {
        int nodes=graph.reservoirs().size(),edges=graph.pipes().size();
        var at=new ArrayList<List<Integer>>(nodes);for(int i=0;i<nodes;i++)at.add(new ArrayList<>());
        boolean[] inner=new boolean[edges];
        for(int e=0;e<edges;e++){var pipe=graph.pipes().get(e);
            if(graph.reservoirs().get(pipe.first()).fixed()||graph.reservoirs().get(pipe.second()).fixed())continue;
            inner[e]=true;at.get(pipe.first()).add(e);if(pipe.second()!=pipe.first())at.get(pipe.second()).add(e);}
        int[] parent=new int[nodes],via=new int[nodes],depth=new int[nodes];Arrays.fill(parent,-2);boolean[] tree=new boolean[edges];
        var queue=new ArrayDeque<Integer>();
        for(int root=0;root<nodes;root++) {
            if(parent[root]!=-2||graph.reservoirs().get(root).fixed())continue;
            parent[root]=-1;queue.add(root);
            while(!queue.isEmpty()){int u=queue.remove();
                for(int e:at.get(u)){var pipe=graph.pipes().get(e);int v=pipe.first()==u?pipe.second():pipe.first();
                    if(parent[v]!=-2)continue;parent[v]=u;via[v]=e;depth[v]=depth[u]+1;tree[e]=true;queue.add(v);}}
        }
        for(int e=0;e<edges;e++) {
            if(!inner[e]||tree[e])continue;
            var pipe=graph.pipes().get(e);
            // The loop one way: the closing connection from its first end to its second, then the forest path back.
            double forward=drives[e][0],reverse=drives[e][1];int a=pipe.second(),b=pipe.first();
            while(a!=b) {
                if(depth[a]>=depth[b]){int f=via[a],p=parent[a];forward+=along(graph,drives,f,a);reverse+=along(graph,drives,f,p);a=p;}
                else{int f=via[b],p=parent[b];forward+=along(graph,drives,f,p);reverse+=along(graph,drives,f,b);b=p;}
            }
            if(forward>REST_RESOLUTION||reverse>REST_RESOLUTION)return true;
        }
        return false;
    }
    /**
     * Owner decision D42, read where a gauge stands: a junction holds no stock and no gauge, so the drive that counts is
     * the one between two nodes that do, summed along a path of junctions between them (the junctions' pressures cancel,
     * so a path's drive is its end pressures less its columns: a chain of five tees each 0.09 Pa apart is 0.45 Pa between
     * its tank and its generator, and the tank still fills). Whether some path between two such nodes drives above
     * {@link #REST_RESOLUTION}: the largest path drive arriving at each junction from a stock node, relaxed through the
     * junctions (at most one round per junction), then closed at a stock node. A loop of junctions with a net drive below
     * the resolution can only raise a path by that much per round: the test may read a drive that is not there, never
     * miss one.
     */
    static boolean pathDriven(PassiveNetwork graph,double[][] drives) {
        int nodes=graph.reservoirs().size(),junctions=0;double[] best=new double[nodes];Arrays.fill(best,Double.NEGATIVE_INFINITY);
        for(var node:graph.reservoirs())if(node.junction())junctions++;
        for(int e=0;e<drives.length;e++) {
            var pipe=graph.pipes().get(e);boolean a=graph.reservoirs().get(pipe.first()).junction(),b=graph.reservoirs().get(pipe.second()).junction();
            if(!a&&b)best[pipe.second()]=Math.max(best[pipe.second()],drives[e][0]);
            if(a&&!b)best[pipe.first()]=Math.max(best[pipe.first()],drives[e][1]);
        }
        for(int round=0;round<junctions;round++) {
            boolean changed=false;
            for(int e=0;e<drives.length;e++) {
                var pipe=graph.pipes().get(e);int a=pipe.first(),b=pipe.second();
                if(!graph.reservoirs().get(a).junction()||!graph.reservoirs().get(b).junction())continue;
                if(best[a]+drives[e][0]>best[b]){best[b]=best[a]+drives[e][0];changed=true;}
                if(best[b]+drives[e][1]>best[a]){best[a]=best[b]+drives[e][1];changed=true;}
            }
            if(!changed)break;
        }
        for(int e=0;e<drives.length;e++) {
            var pipe=graph.pipes().get(e);boolean a=graph.reservoirs().get(pipe.first()).junction(),b=graph.reservoirs().get(pipe.second()).junction();
            if(a&&!b&&best[pipe.first()]+drives[e][0]>REST_RESOLUTION)return true;
            if(!a&&b&&best[pipe.second()]+drives[e][1]>REST_RESOLUTION)return true;
        }
        return false;
    }
    /** The static drive of connection {@code e} leaving node {@code from}. */
    private static double along(PassiveNetwork graph,double[][] drives,int e,int from){return graph.pipes().get(e).first()==from?drives[e][0]:drives[e][1];}
    /**
     * The interval of an island at rest ({@link #atRest}): the graph unchanged, every flow, head, transfer and pump work
     * zero, one accepted step, FULL acceptance. A connection with a one-way end (a generator or a void, or a blocked
     * direction) is reported CLOSED, as the solver's start closure closes such a run when nothing drives it; a connection
     * both ways open reports PASSIVE; a mover refused for the slice ({@code refused}, null for none) reports
     * INLET_WRONG_PHASE, the endpoint mode the next slice's hysteresis reads (decision D6). Nothing is evaluated, so
     * conservation is exact and the interval is the same on every run.
     */
    public static PassiveIntervalSolver.Result restInterval(PassiveNetwork graph,double duration){return restInterval(graph,duration,null);}
    /** {@link #restInterval} with the movers refused for the slice. */
    public static PassiveIntervalSolver.Result restInterval(PassiveNetwork graph,double duration,boolean[] refused) {
        int pipes=graph.pipes().size();var modes=new ArrayList<FlowControl.Mode>(pipes);
        for(int i=0;i<pipes;i++){var pipe=graph.pipes().get(i);
            modes.add(refused!=null&&refused.length==pipes&&refused[i]?FlowControl.Mode.INLET_WRONG_PHASE
                    :pipe.control() instanceof FlowControl.Passive&&PassiveStepSolver.boundaryAllowed(graph,pipe,1)&&PassiveStepSolver.boundaryAllowed(graph,pipe,-1)?FlowControl.Mode.PASSIVE:FlowControl.Mode.CLOSED);}
        var states=graph.reservoirs().stream().map(PassiveNetwork.Reservoir::state).toList();
        double[] zero=new double[pipes];
        return new PassiveIntervalSolver.Result(graph,duration,zero,1,0,0,List.of(),Map.of(),modes,new double[pipes],zero,
                PipeTransfer.sample(graph,states,zero,duration));
    }
    static PassiveIntervalSolver.Result observedRestInterval(PassiveNetwork graph,double duration,boolean[] refused,FluidThermodynamics model){var r=restInterval(graph,duration,refused);return new PassiveIntervalSolver.Result(r.graph(),r.advancedSeconds(),r.averageMassFlows(),r.acceptedSubsteps(),r.rejectedSubsteps(),r.pumpWorkJoule(),r.boundaries(),r.rejectionReasons(),r.endpointModes(),r.endpointHeads(),r.endpointFlows(),r.pipeTransfers(),PassiveStepSolver.PipeObservation.atRest(graph,model));}
    /** The game's ambient: air (the network's nitrogen) at 298.15 K and {@link PhaseLayout#AMBIENT_PRESSURE} (A280). The
     * state an empty vessel shows (decision D54: its pressure, nothing it holds) and the ports of a disconnected filter. */
    public static FluidThermodynamics.State ambient(FluidThermodynamics model){return model.initialNitrogenCharge(1,298.15,PhaseLayout.AMBIENT_PRESSURE,()->{});}
    /** A stream's viscosity: its phases' own, volume-weighted (the solver's bulk average, without solids). A state holding
     * no fluid has no stream (its ends are dry); its end reads an empty vessel's, the ambient's ({@code PassiveStepSolver}'s
     * bulk viscosity). */
    private static double viscosity(FluidThermodynamics model,FluidThermodynamics.State s,MixtureViscosity.Workspace scratch) {
        if(!FluidThermodynamics.holdsFluid(s))return viscosity(model,ambient(model),scratch);
        double value=0,volume=0;
        for(int phase:new int[]{FluidThermodynamics.GAS,FluidThermodynamics.OIL,FluidThermodynamics.WATER})
            if(FluidThermodynamics.holdsPhase(s,phase)){double v=FluidThermodynamics.phaseVolume(s,phase);value+=v*model.phaseViscosity(s,phase,scratch,null);volume+=v;}
        return value/volume;
    }
}
