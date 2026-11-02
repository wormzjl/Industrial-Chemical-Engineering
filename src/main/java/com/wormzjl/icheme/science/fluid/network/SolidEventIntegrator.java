package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import com.wormzjl.icheme.science.fluid.transport.SolidMobility;
import com.wormzjl.icheme.science.fluid.solver.SparseNewton;
import java.util.*;

/** Worker-local strict transport events. Trial integrations never commit ownership. */
final class SolidEventIntegrator {
    /**
     * Every transport closure names both directions: a filter that has met its capacity carries nothing either way, and
     * a bed that has settled out of a carrier too slow to suspend it is in the connection, not in one end of it. It is
     * also what the step solver can work with: {@link PassiveStepSolver} presets {@code boundaryClosed} only for a fully
     * closed connection, and a one-directional closure left the first Newton pass free to drive flow through the
     * forbidden direction (a ten-reservoir chain 70 to 51 ms and a thirty-reservoir chain 215 to 103 ms over eleven warm
     * repeats once both were closed). The next interval's rate pass reconsiders every closure, and one stands only while
     * nothing moves its pipe and an end holds what closed it ({@link #carried}), so none is a permanent physical deposit.
     */
    private static final int BOTH_DIRECTIONS=3;
    private final FluidThermodynamics model;
    private final PassiveIntervalSolver integrator;
    private final PassiveStepSolver rates;
    SolidEventIntegrator(FluidThermodynamics model,PassiveIntervalSolver integrator) {
        this.model=model;this.integrator=integrator;rates=new PassiveStepSolver(model);
    }
    /**
     * A stage guard's report that the connection it names may no longer carry what the trajectory
     * asks it to. It is a control-flow signal out of a step solve, thrown once per rejected
     * substep while the interval solver refines onto the event, so it carries no stack trace.
     */
    static final class Transition extends RuntimeException {
        /** Every transport closure applies the mask {@link #BOTH_DIRECTIONS}; see there. */
        final int edge;
        /** The connection's own identity, which is what a player sees on a device and what the
         * only durable record of a closure - the reason text - is looked up by. The edge index is
         * a position in one graph and means nothing outside it. */
        final long pipeId;
        final SolidMobility.Check check;
        /** Seen on the state at the start of a step: the event is at the accepted state
         * itself, so it needs no refinement and is declared where the integration stands. */
        final boolean atStart;
        Transition(int edge,long pipeId,SolidMobility.Check check,boolean atStart) {
            super("blocked with solid: "+check.reason()+"; pipe="+pipeId+"; velocity="+check.velocity()+"; deposition="+check.minimumVelocity());
            this.edge=edge;this.pipeId=pipeId;this.check=check;this.atStart=atStart;
        }
        SolidMobility.Reason reason(){return check.reason();}
        Transition atStart(){return atStart?this:new Transition(edge,pipeId,check,true);}
        @Override public synchronized Throwable fillInStackTrace(){return this;}
    }
    private PassiveNetwork masks(PassiveNetwork graph,int[] masks) {
        var pipes=new ArrayList<PassiveNetwork.Pipe>();
        for(int i=0;i<masks.length;i++)pipes.add(graph.pipes().get(i).withBlockedDirections(masks[i]));
        return new PassiveNetwork(graph.reservoirs(),pipes,graph.scheduledTransfers());
    }
    private PassiveNetwork stopFilter(PassiveNetwork graph,int edge){var pipes=new ArrayList<>(graph.pipes());var p=pipes.get(edge);pipes.set(edge,p.withFilter(p.filter().stopped()));return new PassiveNetwork(graph.reservoirs(),pipes,graph.scheduledTransfers());}
    /**
     * The pipes still at an interval's start (owner decision of 2026-10-19 on t53's final test): a pipe at rest carries no
     * solids, so it deposits, refuses and delivers nothing, and the sign of a roundoff flow names no donor (t48's trace of
     * cold fuzz seed 850: 1.3e-23 m/s in a still pipe from a tank holding crystals read DEPOSITION, and its rejections
     * stalled the island). Still is roundoff: a flow of the interval-start rate solve (where it fails, of the last accepted
     * step) within the Newton's own tolerance ({@link PassiveStepSolver#NEWTON_TOLERANCE}) of its largest flow, at least
     * 1 kg/s. It is decided once, where the
     * closures are decided, and such a pipe keeps its state for the interval: no threshold on a real flow fires inside a
     * step (D42's resolution flow, read as still before, is about 1 kg/s in 1 m of 100 mm LNG line, and cold fuzz seed
     * 829's draining tank, 0.38 m/s, met it inside its step).
     */
    private static boolean[] still(double[] flows) {
        double largest=1;for(double q:flows)largest=Math.max(largest,Math.abs(q));
        boolean[] still=new boolean[flows.length];
        for(int i=0;i<flows.length;i++)still[i]=Math.abs(flows[i])<=PassiveStepSolver.NEWTON_TOLERANCE*largest;
        return still;
    }
    /** The first transition among the connections not {@code still} (null for none), at the flows given. */
    private Transition failed(PassiveNetwork graph,List<FluidThermodynamics.State> states,double[] flows,boolean[] still) {
        Transition result=null;double ratio=Double.POSITIVE_INFINITY;long identity=Long.MAX_VALUE;
        var reach=populationReach(graph,states,flows);
        // One mobility preparation per donor node, not per connection leaving it: the carrier
        // viscosities, which every wet island pays whether or not it carries a particle, depend on
        // the donor alone. A node that is never a donor this pass is never prepared at all.
        var prepared=new SolidMobility.Donor[states.size()];
        Map<Integer,SolidMobility.Check> limited=null;
        for(int i=0;i<flows.length;i++) {
            var pipe=graph.pipes().get(i);double flow=flows[i];int direction=flow>=0?1:2;
            if(pipe.filter()!=null&&atCapacity(pipe.filter())&&pipe.blockedDirections()!=BOTH_DIRECTIONS)return clogged(i,pipe.id());
            if((pipe.effectiveBlockedDirections()&direction)!=0||still!=null&&still[i])continue;
            var check=mobility(graph,states,prepared,i,flow);
            boolean overPopulated=check.allowed()&&reach!=null&&flow!=0&&overPopulated(graph,pipe,flow,reach);
            if(check.allowed()&&!overPopulated)continue;
            if(overPopulated) {
                check=new SolidMobility.Check(SolidMobility.Reason.POPULATION_LIMIT,check.velocity(),check.minimumVelocity());
                // A node receiver can be fed by several connections at once, and every one of them
                // is flagged although at most one of them has to stop; which one is decided over
                // the whole set, once it is known. A filter's cake has the one donor that fills it.
                if(pipe.filter()==null){(limited==null?limited=new LinkedHashMap<>():limited).put(i,check);continue;}
            }
            double candidate=closureRatio(check);
            if(candidate<ratio||candidate==ratio&&pipe.id()<identity){result=new Transition(i,pipe.id(),check,false);ratio=candidate;identity=pipe.id();}
        }
        if(limited!=null)for(var entry:narrowLimits(graph,states,flows,reach,limited).entrySet()) {
            var pipe=graph.pipes().get(entry.getKey());double candidate=closureRatio(entry.getValue());
            if(candidate<ratio||candidate==ratio&&pipe.id()<identity){result=new Transition(entry.getKey(),pipe.id(),entry.getValue(),false);ratio=candidate;identity=pipe.id();}
        }
        return result;
    }
    /** Whether connection {@code i} keeps its solids moving when it draws {@code flow} (its sign names the donor end). */
    private SolidMobility.Check mobility(PassiveNetwork graph,List<FluidThermodynamics.State> states,SolidMobility.Donor[] prepared,int i,double flow) {
        var pipe=graph.pipes().get(i);int upstream=flow>=0?pipe.first():pipe.second();var donor=states.get(upstream);
        if(prepared[upstream]==null)prepared[upstream]=SolidMobility.donor(model,donor);
        // What the end draws decides the outlet (plan 3.8; decision D11): a phase port is checked on the phase it draws
        // first (PhaseDraw.leading, the priority stream at a vanishing flow; this check sees no step and so no capacity):
        // the gas carries no solid and is always mobile; the hydrocarbon liquid or the free water carries its volume share
        // of the solids, checked for its own carrier (ORGANIC_LIQUID, WATER) over its stream's mass per the whole liquid
        // it carries the solids in; a bulk end, and a phase port on a vessel holding none of the phases, the donor's mix,
        // except a bulk end on a donor holding solids but no liquid: nothing carries them, so it draws the gas and the
        // solids stay (owner decision D33, the GAS outlet through PhaseDraw.leading). A LIQUID end holding both liquids draws
        // both carriers (MIXED), each in its PhaseDraw.mixedShares of the stream: its stream's mass per the whole liquid is
        // their shares of each one's.
        var port=pipe.drawPort(flow);
        if(PhaseDraw.mixedLiquids(donor,port)) {
            var shares=PhaseDraw.mixedShares(donor,pipe.volume());double drawnMass=0;
            for(int liquid:new int[]{FluidThermodynamics.OIL,FluidThermodynamics.WATER})drawnMass+=shares[liquid]*model.phaseMass(donor,liquid)/FluidThermodynamics.phaseSolidShare(donor,liquid);
            return SolidMobility.check(prepared[upstream],donor,pipe,flow,SolidMobility.Outlet.MIXED,drawnMass);
        }
        int phase=PhaseDraw.leading(model,donor,port);
        SolidMobility.Outlet outlet;double drawnMass;
        if(phase<0){outlet=SolidMobility.Outlet.MIXED;drawnMass=donor.mass();}
        else if(phase==FluidThermodynamics.GAS){outlet=SolidMobility.Outlet.GAS;drawnMass=donor.mass();}
        else{outlet=phase==FluidThermodynamics.OIL?SolidMobility.Outlet.ORGANIC_LIQUID:SolidMobility.Outlet.WATER;drawnMass=model.phaseMass(donor,phase)/FluidThermodynamics.phaseSolidShare(donor,phase);}
        return SolidMobility.check(prepared[upstream],donor,pipe,flow,outlet,drawnMass);
    }
    /**
     * What a closure carries across an interval start (owner decision D62 of 2026-10-19, batch
     * {@code 2026-10-20-crystal-junction-holds}, amending D33): a pipe the last interval left closed that nothing moves at
     * this interval's start keeps its closure, because what closed it (the bed its slow carrier let settle, the particle
     * too wide for its bore, the slush) is still in it. Nothing moves it: the rate solve with it reopened leaves it
     * roundoff-still ({@code still}, the reading of {@link #still}), or, without a rate solve, no mover draws through it
     * ({@link #carriedWithoutRate}). It keeps its closure while an end still holds what closed it: the mobility check fails
     * with that end as the donor, read both ways at a vanishing flow, so no roundoff sign picks the end. A pipe the drive
     * moves is decided by its flow in {@link #failed}, as any other. Returns the first such closure, declared at t0.
     */
    private Transition carried(PassiveNetwork graph,PassiveNetwork committed,int[] blocked,List<FluidThermodynamics.State> states,boolean[] still) {
        var prepared=new SolidMobility.Donor[states.size()];
        for(int i=0;i<blocked.length;i++) {
            if(committed.pipes().get(i).blockedDirections()!=BOTH_DIRECTIONS||blocked[i]!=0||still!=null&&!still[i])continue;
            for(double draw:new double[]{Double.MIN_VALUE,-Double.MIN_VALUE}){var check=mobility(graph,states,prepared,i,draw);if(!check.allowed())return new Transition(i,graph.pipes().get(i).id(),check,true);}
        }
        return null;
    }
    /**
     * {@link #carried} where the port graph has no rate solve with the closures reopened, so nothing shows a flow through
     * them (cold fuzz seed 15). The owner's assumption, form C of t56's comparison: a mover clears what it draws through.
     * A closure on a line a mover draws through ({@link #drawnThrough}) reads open, and the steps decide it by its flow
     * (PumpCavitationTest's pump pulls 2.5 m/s through its 10 mm suction line); every other closure an end still holds
     * keeps. On the 200-seed quick check form A (keep every closure) closed that suction line, and form B (the drive read
     * from a rate solve with the closures closed) cleared neither cold 183 nor 118.
     */
    private Transition carriedWithoutRate(PassiveNetwork graph,PassiveNetwork committed,int[] blocked) {
        var drawn=drawnThrough(graph);int[] kept=blocked.clone();
        for(int i=0;i<kept.length;i++)if(drawn[i])kept[i]=BOTH_DIRECTIONS;
        return carried(graph,committed,kept,graph.reservoirs().stream().map(PassiveNetwork.Reservoir::state).toList(),null);
    }
    /** The passive pipes a mover draws through: those on its suction side, reached from its first end through junctions. */
    private static boolean[] drawnThrough(PassiveNetwork graph) {
        int edges=graph.pipes().size();boolean[] drawn=new boolean[edges];
        for(int m=0;m<edges;m++) {
            if(!(graph.pipes().get(m).control() instanceof FlowControl.Mover mover)||mover.targetVolumeFlow()<=0)continue;
            var queue=new ArrayDeque<Integer>();var seen=new HashSet<Integer>();queue.add(graph.pipes().get(m).first());seen.add(graph.pipes().get(m).first());
            while(!queue.isEmpty()){int at=queue.poll();
                for(int e=0;e<edges;e++){var pipe=graph.pipes().get(e);if(e==m||drawn[e]||!(pipe.control() instanceof FlowControl.Passive)||pipe.first()!=at&&pipe.second()!=at)continue;
                    drawn[e]=true;int other=pipe.first()==at?pipe.second():pipe.first();
                    if(graph.reservoirs().get(other).junction()&&seen.add(other))queue.add(other);}}
        }
        return drawn;
    }
    /** How far a connection is from carrying what it is asked to, as the shared closure rule orders
     * it: the lowest ratio closes first, and a failure with no velocity of its own sorts ahead of
     * every one that has one. */
    private static double closureRatio(SolidMobility.Check check) {
        return check.minimumVelocity()>0?check.velocity()/check.minimumVelocity():-1;
    }
    /**
     * Which of an over-populated receiver's flagged connections actually has to stop: one per receiver, and never one
     * whose delivery the limit did not need to refuse. Every open inbound connection of a receiver whose union does not
     * fit is flagged, so the shared rule (lowest velocity ratio, then lowest identity) could name a feed whose removal
     * leaves the union over the limit anyway (a receiver holding sixty grades closed a four-grade feed first and a
     * ten-grade feed on the next pass). So the feed that closes is the one contributing the most keys no other open
     * inbound donor supplies; the rest stay open and are reconsidered next pass. Ties fall back to the shared rule.
     */
    private static Map<Integer,SolidMobility.Check> narrowLimits(PassiveNetwork graph,List<FluidThermodynamics.State> states,double[] flows,
                                                                 List<SortedSet<SolidInventory.Key>> reach,Map<Integer,SolidMobility.Check> flagged) {
        var byReceiver=new LinkedHashMap<Integer,List<Integer>>();
        for(var edge:flagged.keySet()){var pipe=graph.pipes().get(edge);byReceiver.computeIfAbsent(flows[edge]>=0?pipe.second():pipe.first(),k->new ArrayList<Integer>()).add(edge);}
        var kept=new LinkedHashMap<Integer,SolidMobility.Check>();
        for(var entry:byReceiver.entrySet()) {
            var group=entry.getValue();
            if(group.size()==1){kept.put(group.getFirst(),flagged.get(group.getFirst()));continue;}
            int best=-1,exclusive=-1;double ratio=Double.POSITIVE_INFINITY;long identity=Long.MAX_VALUE;
            for(int edge:group) {
                var pipe=graph.pipes().get(edge);var elsewhere=suppliedWithout(graph,states,flows,reach,entry.getKey(),edge);
                int own=0;for(var key:reach.get(flows[edge]>=0?pipe.first():pipe.second()))if(!elsewhere.contains(key))own++;
                double candidate=closureRatio(flagged.get(edge));
                if(own>exclusive||own==exclusive&&(candidate<ratio||candidate==ratio&&pipe.id()<identity)){best=edge;exclusive=own;ratio=candidate;identity=pipe.id();}
            }
            kept.put(best,flagged.get(best));
        }
        return kept;
    }
    /** The keys this receiver would still be given without the named connection: its own stock and
     * injections, and the reach of every other open inbound donor, flagged or not. */
    private static SortedSet<SolidInventory.Key> suppliedWithout(PassiveNetwork graph,List<FluidThermodynamics.State> states,double[] flows,
                                                                 List<SortedSet<SolidInventory.Key>> reach,int receiver,int except) {
        var keys=new TreeSet<SolidInventory.Key>();
        for(var p:states.get(receiver).solids().populations())keys.add(p.key());
        for(var transfer:graph.scheduledTransfers())if(transfer instanceof ScheduledTransfer.Injection in&&in.node()==receiver)
            for(var p:in.solidsPerSecond().populations())keys.add(p.key());
        for(int edge=0;edge<flows.length;edge++) {
            if(edge==except||flows[edge]==0)continue;
            var pipe=graph.pipes().get(edge);
            if(pipe.filter()!=null||pipe.blocked(flows[edge])||(flows[edge]>=0?pipe.second():pipe.first())!=receiver)continue;
            keys.addAll(reach.get(flows[edge]>=0?pipe.first():pipe.second()));
        }
        return keys;
    }
    /**
     * The conserved population keys that can reach each node over one step, or {@code null} when this island cannot put
     * more than {@link SolidInventory#MAXIMUM_POPULATIONS} of them anywhere. The reconstruction's population system has
     * only positive coefficients, so a stock reaches every node connected to it by open connections a filter does not
     * empty, and the union that decides whether an inventory can be built is the transitive one; a fixed node neither
     * receives nor forwards. If the whole island holds 64 or fewer populations with duplicates, no union can exceed 64,
     * one field read per node.
     */
    private static List<SortedSet<SolidInventory.Key>> populationReach(PassiveNetwork graph,List<FluidThermodynamics.State> states,double[] flows) {
        int held=0;
        for(var state:states)held+=state.solids().populations().size();
        for(var transfer:graph.scheduledTransfers())if(transfer instanceof ScheduledTransfer.Injection in)held+=in.solidsPerSecond().populations().size();
        if(held<=SolidInventory.MAXIMUM_POPULATIONS)return null;
        var reach=new ArrayList<SortedSet<SolidInventory.Key>>(states.size());
        for(var state:states){var keys=new TreeSet<SolidInventory.Key>();for(var p:state.solids().populations())keys.add(p.key());reach.add(keys);}
        for(var transfer:graph.scheduledTransfers())if(transfer instanceof ScheduledTransfer.Injection in)
            for(var p:in.solidsPerSecond().populations())reach.get(in.node()).add(p.key());
        for(int pass=0;pass<states.size();pass++) {
            boolean changed=false;
            for(int edge=0;edge<flows.length;edge++) {
                var pipe=graph.pipes().get(edge);
                if(pipe.filter()!=null||flows[edge]==0||pipe.blocked(flows[edge]))continue;
                int donor=flows[edge]>=0?pipe.first():pipe.second(),receiver=flows[edge]>=0?pipe.second():pipe.first();
                if(graph.reservoirs().get(receiver).fixed())continue;
                changed|=reach.get(receiver).addAll(reach.get(donor));
            }
            if(!changed)break;
        }
        return reach;
    }
    /** Whether delivering this connection's flow puts more distinct populations than a conserved
     * stock can hold into what receives it: the far reservoir, or an inline filter's own cake. */
    private static boolean overPopulated(PassiveNetwork graph,PassiveNetwork.Pipe pipe,double flow,List<SortedSet<SolidInventory.Key>> reach) {
        int donor=flow>=0?pipe.first():pipe.second(),receiver=flow>=0?pipe.second():pipe.first();
        if(pipe.filter()!=null) {
            var union=new TreeSet<>(reach.get(donor));
            for(var p:pipe.filter().captured().populations())union.add(p.key());
            return union.size()>SolidInventory.MAXIMUM_POPULATIONS;
        }
        return !graph.reservoirs().get(receiver).fixed()&&reach.get(receiver).size()>SolidInventory.MAXIMUM_POPULATIONS;
    }
    private static Transition clogged(int edge,long pipeId) {
        return new Transition(edge,pipeId,new SolidMobility.Check(SolidMobility.Reason.FILTER_CLOGGED,0,0),false);
    }
    /**
     * Whether this cake has met its capacity and the connection must stop. The saturated inlet law
     * aims a hair below capacity so that an owned cake never exceeds it, so "met" is a relative
     * test rather than {@link InlineFilter#clogged()}'s exact one; a filter restored from a save
     * at or past capacity, and one already stopped there, answer the same way either way.
     */
    private static boolean atCapacity(InlineFilter state) {
        return state.clogged()||state.captured().volume()>=state.capacity()*(1-1e-9);
    }
    private PassiveIntervalSolver.StageGuard guard(PassiveNetwork graph,boolean[] still) {
        return new PassiveIntervalSolver.StageGuard() {
            public void check(List<FluidThermodynamics.State> states,List<FlowControl.Mode> modes) {}
            @Override public void checkRate(Map<Long,InlineFilter> filters,List<FluidThermodynamics.State> states,List<FlowControl.Mode> modes,double[] flows) {
                // The graph this guard closes over is the one the segment started from, so its own
                // cakes are stale by the steps already accepted; the live ones arrive here.
                var full=cloggedFilter(filters);if(full!=null)throw full.atStart();
                var failure=failed(graph,states,flows,still);if(failure!=null)throw failure.atStart();
            }
            @Override public void checkFilters(Map<Long,InlineFilter> filters,List<FluidThermodynamics.State> states,List<FlowControl.Mode> modes,double[] flows) {
                // A step may not reach capacity: the exact test, so the saturated inlet law's own
                // landing a relative hair below it is an accepted step whose closure the next
                // step's t0 guard declares, while a step that overshot the remaining room - by
                // more than rounding absorbs, or on an island where the law is
                // not applied at all - is refused and the controller halves onto the fill.
                var over=overfilledFilter(filters);if(over!=null)throw over;
                checkFlow(states,modes,flows);
            }
            @Override public void checkFlow(List<FluidThermodynamics.State> states,List<FlowControl.Mode> modes,double[] flows) {
                var failure=failed(graph,states,flows,still);
                if(failure!=null)throw failure;
            }
            private Transition cloggedFilter(Map<Long,InlineFilter> filters) {
                return firstFilter(filters,SolidEventIntegrator::atCapacity);
            }
            private Transition overfilledFilter(Map<Long,InlineFilter> filters) {
                return firstFilter(filters,InlineFilter::clogged);
            }
            private Transition firstFilter(Map<Long,InlineFilter> filters,java.util.function.Predicate<InlineFilter> refused) {
                for(int i=0;i<graph.pipes().size();i++) {
                    var pipe=graph.pipes().get(i);
                    if(pipe.filter()==null||pipe.blockedDirections()==BOTH_DIRECTIONS)continue;
                    var state=filters.get(pipe.id());
                    if(state!=null&&refused.test(state))return clogged(i,pipe.id());
                }
                return null;
            }
        };
    }
    /** The interval from {@code start}, the island's refresh at the interval's start ({@link PassiveIntervalSolver#solve}), whose
     * closures D33 read open; {@code committed} is the committed graph, whose closures {@link #carried} reads. */
    PassiveIntervalSolver.Result solve(InventoryEquilibrium.Refreshed start,PassiveNetwork committed,double duration,PassiveIntervalSolver.Settings settings,Runnable checkpoint,double startingStep) {
        if(!Double.isFinite(duration)||duration<=0)throw new IllegalArgumentException("Positive finite interval required");
        var graph=start.graph();
        int[] blocked=new int[graph.pipes().size()];
        for(int i=0;i<blocked.length;i++){var p=graph.pipes().get(i);var a=graph.reservoirs().get(p.first());var b=graph.reservoirs().get(p.second());if(p.filter()!=null&&a.id()<0&&b.id()<0&&a.kind()==PassiveNetwork.NodeKind.GENERATOR&&b.kind()==PassiveNetwork.NodeKind.VOID)blocked[i]=BOTH_DIRECTIONS;}
        double elapsed=0,work=0,step=startingStep;int accepted=0,rejected=0;
        var reasons=new LinkedHashMap<String,Integer>();
        // Reconsider every closure on a new requested interval (D33, amended by D62): a pipe the drive moves is decided by its
        // flow, and a closure nothing moves keeps while an end holds what closed it (carried). A closure the interval starts
        // with is reported like any other, at the interval's own t0. The check reads one rate solve of the port graph
        // (PassiveStepSolver.portRate); it only pre-empts a closure the steps would find, so a rate solve that fails declares
        // no new closure and the steps decide (hold class E1), but a closure carried without it (carriedWithoutRate) is
        // declared, and the rate solve tried again with it closed. The same rate solve decides which pipes are still (still):
        // they keep their state for the whole interval, its steps included, a closed one its closure. Where it fails, the
        // flows the last accepted step left on this structure decide (the t0 flows the interval solver's own start-of-step
        // check reads, PassiveIntervalSolver.carriedFlows): a pipe at rest is still whether or not the port graph converges.
        // Cold fuzz seed 850 at rest: the rate solve, its taut tank held as a port 1.36 kPa under the junction beside it,
        // failed on eight intervals in a row; with no pipe still, the 2e-12 kg/s the step left in the pipe to the tank read
        // DEPOSITION against its 1.275 m/s suspension velocity, 25 refusals in one interval.
        boolean[] still=null;
        for(int attempt=0;SolidMobility.closable(model,graph)&&attempt<=2*blocked.length;attempt++) {
            checkpoint.run();graph=masks(graph,blocked);
            var candidate=rates.portRate(graph,checkpoint);
            Transition failure;
            if(candidate!=null) {
                still=still(candidate.massFlows());
                failure=failed(graph,candidate.states(),candidate.massFlows(),still);
                if(failure==null)failure=carried(graph,committed,blocked,candidate.states(),still);
            } else {
                var carriedFlows=integrator.carriedFlows(graph);still=carriedFlows==null?null:still(carriedFlows);
                failure=carriedWithoutRate(graph,committed,blocked);
            }
            if(failure==null)break;
            blocked[failure.edge]|=BOTH_DIRECTIONS;
            // A cake the last interval's final step left at capacity is declared here, at the next interval's t0, and
            // not by an in-interval guard: under backward Euler a 5 s interval is often a single step, so the saturated
            // inlet's landing is the interval's last step. Stop the filter exactly as a declared FILTER_CLOGGED
            // transition does below, so the stopped state (status, checkpoint) is the same either way (review 8.8 (a)).
            if(failure.reason()==SolidMobility.Reason.FILTER_CLOGGED&&graph.pipes().get(failure.edge).filter()!=null)graph=stopFilter(graph,failure.edge);
            reasons.merge(failure.getMessage()+"; t="+elapsed,1,Integer::sum);
            if(attempt==2*blocked.length)throw new SparseNewton.Nonconvergence("Solid closure active set did not settle");
        }
        double[] transported=new double[graph.pipes().size()];
        var histories=new PipeTransfer.Accumulator();var boundaries=new ArrayList<ConservativeTransport.BoundaryTransfer>(start.boundaries());
        PassiveIntervalSolver.Result last=null;
        // One segment per closure. The interval solver stops its own step grid on the transition
        // and hands back the prefix it accepted, so the event is located by the same adaptive
        // controller that integrates the rest of the interval, at the same tolerance, and the
        // prefix is never re-integrated.
        for(int event=0;elapsed<duration&&event<=2*blocked.length+1;event++) {
            checkpoint.run();
            var masked=masks(graph,blocked);double remaining=duration-elapsed;
            // The first segment starts from the interval's refresh; every later one from its own refresh of the state the
            // transition left (owner decision D23: crystal mass moves only in a vessel's refresh).
            var segment=event==0?InventoryEquilibrium.Refreshed.as(masked):InventoryEquilibrium.refresh(masked,model,checkpoint);
            var prefix=integrator.integrateToTransition(segment,remaining,settings,checkpoint,guard(masked,still),step);
            var transition=prefix.transition();int stoppedFilter=-1;
            if(transition!=null) {
                blocked[transition.edge]|=BOTH_DIRECTIONS;
                if(transition.reason()==SolidMobility.Reason.FILTER_CLOGGED)stoppedFilter=transition.edge;
            }
            var result=prefix.result();
            if(result!=null) {
                double advanced=transition==null?remaining:result.advancedSeconds();
                elapsed+=advanced;last=result;graph=result.graph();accepted+=result.acceptedSubsteps();rejected+=result.rejectedSubsteps();
                work+=result.pumpWorkJoule();boundaries.addAll(result.boundaries());histories.add(result.pipeTransfers(),1);
                result.rejectionReasons().forEach((k,v)->reasons.merge(k,v,Integer::sum));
                var flows=result.averageMassFlows();for(int i=0;i<flows.length;i++)transported[i]+=advanced*flows[i];
                step=integrator.nextStepEstimate();
            }
            if(transition!=null)reasons.merge(transition.getMessage()+"; t="+elapsed,1,Integer::sum);
            if(stoppedFilter>=0)graph=stopFilter(graph,stoppedFilter);
            graph=masks(graph,blocked);
        }
        if(elapsed<duration||last==null)throw new SparseNewton.Nonconvergence("Solid event limit exhausted; no partial interval may commit");
        for(int i=0;i<transported.length;i++)transported[i]/=duration;
        return new PassiveIntervalSolver.Result(graph,duration,transported,accepted,rejected,work,boundaries,reasons,
                last.endpointModes(),last.endpointHeads(),last.endpointFlows(),histories.snapshot(),last.observations(),last.towers());
    }
}
