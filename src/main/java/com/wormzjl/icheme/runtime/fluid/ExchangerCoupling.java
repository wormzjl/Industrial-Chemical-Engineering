package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.runtime.fluid.IslandCoordinator.Island;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import java.util.*;

/**
 * The coupling of exchanger sides across islands (batch 2026-10-27-heat-exchanger-design, plan section 5.2; owner Q16:
 * form C, the fence), moved from {@link IslandCoordinator} unchanged (codebase review R13): the coupling fence a slice
 * waits at, what each coupled side reads of its other side at a dispatch, the booking of a committed or replayed span,
 * the wake of a certified other side, the heat each exchanger holds in transit and the real valves its sides' controls
 * work. It reads the coordinator's islands, its side index and its ports and shafts, and runs on the coordinator's
 * thread only, from the coordinator's steps; the relay states and the side index stay the coordinator's, which keeps
 * them as islands are registered and replaced.
 */
final class ExchangerCoupling {
    private final IslandCoordinator coordinator;
    /** The heat each exchanger holds in transit by its pair key (the lower of its two sides' connection ids). */
    private final Map<Long,Double> transits=new HashMap<>();
    /** The real valve each coupled side's control works (W8), by the side's connection id: the exchangers whose lines are complete. */
    private Map<Long,ExchangerRelay.Operation> operations=Map.of();
    private boolean waking;
    /**
     * Decision D4's wakes, dated (codebase review R-ST4): by island, the ticks at which a partner's committed or replayed
     * span ended whose record moved what the island read while certified. Each is acted on as of its own tick ({@link
     * IslandCoordinator#movedAt}) once every partner of the island has booked through it ({@link IslandCoordinator#reached}),
     * so no partner can still date an earlier one, and in tick order; until then the island's partners ahead of the tick
     * wait at the fence ({@link #coupledThrough}). Memory only (state).
     */
    private final TreeMap<Long,TreeSet<Long>> dated=new TreeMap<>();
    ExchangerCoupling(IslandCoordinator coordinator){this.coordinator=coordinator;}

    /** An exchanger's side's stream at a commit or a replay's end, and the heat rate (W) of the span that ended there: what
     * its other side reads as of a later slice start (a fired heater's convection coil reads its radiant coil's rate, F1). */
    record SideRecord(long tick,ExchangerRelay.Partner stream,double rate) {}
    /** What a coupled side is: an exchanger's side or a kettle's pool, which read each other and share a heat in transit; or a
     * fired heater's radiant coil, whose heat sets the flue gas its convection coil reads (the owner's test of 2026-10-28,
     * F1). The coils share no transit: their heat comes from fuel. */
    enum SideKind { EXCHANGER, RADIANT, CONVECTION }
    /** A committed or replayed span of a side and the heat its stream took up over it, J. */
    private record Booked(long start,long end,double joule) {}
    /** A side keeps at most this many booked spans; older ones merge (the transit stays exact, only an as-of split moves). */
    private static final int MAXIMUM_BOOKED=64;
    /** Decision D4: a side's commit wakes its certified other side when its stream's temperature moved by more than this
     * (K), its flow by more than this share, or the settled transit grew past this share of that side's slice heat (plus 1 J). */
    static final double WAKE_TEMPERATURE=.05,WAKE_FLOW=1e-3,WAKE_TRANSIT=1e-3;
    /**
     * One coupled exchanger side's relay state, kept by its connection id across the replacements of its island: its last
     * two records (behind the fence its other side is at most one slice past a read's start, decision D1), its booked spans
     * that end past its other side's committed tick, the spans in which it counted its other side stopped, what its last
     * solved slice read of its other side, and that slice's heat rate (W).
     */
    static final class Coupled {
        final long pipe,partner;final SideKind kind;
        SideRecord current,previous;
        final ArrayDeque<Booked> booked=new ArrayDeque<>();
        final ArrayDeque<long[]> sawStopped=new ArrayDeque<>();
        // What its last solved slice read of its other side; until a slice of this run read it (a loaded island's), the
        // first record its other side commits is taken as what it read.
        ExchangerRelay.Partner readUsed;
        boolean read;
        double heatRate;
        // A convection coil: the radiant coil's heat rate (W) its last solved slice read.
        double rateUsed;
        Coupled(long pipe,long partner,SideKind kind){this.pipe=pipe;this.partner=partner;this.kind=kind;}
        long pair(){return Math.min(pipe,partner);}
        void record(SideRecord next){if(current!=null&&current.tick()<next.tick())previous=current;current=next;}
        /** The stream as of {@code tick}: the latest record at or before it, else the current one (after a load, decision D1). */
        ExchangerRelay.Partner asOf(long tick) {
            if(current==null)return null;
            return current.tick()<=tick||previous==null||previous.tick()>tick?current.stream():previous.stream();
        }
        /** The heat rate (W) as of {@code tick}, picked as {@link #asOf} picks the stream; 0 before any record. */
        double rateAsOf(long tick) {
            if(current==null)return 0;
            return current.tick()<=tick||previous==null||previous.tick()>tick?current.rate():previous.rate();
        }
        /** The heat booked past {@code tick}, J: each span's share of its own beyond it. */
        double bookedPast(long tick) {
            double sum=0;for(var b:booked)if(b.end()>tick)sum+=b.joule()*(b.end()-Math.max(b.start(),tick))/(double)(b.end()-b.start());
            return sum;
        }
        /** The share of {@code [start, end]} in which this side counted its other side stopped. */
        double stoppedWithin(long start,long end) {
            long sum=0;for(var s:sawStopped)sum+=Math.max(0,Math.min(end,s[1])-Math.max(start,s[0]));
            return Math.min(1,sum/(double)(end-start));
        }
        void book(long start,long end,double joule) {
            booked.addLast(new Booked(start,end,joule));
            if(booked.size()>MAXIMUM_BOOKED){var a=booked.removeFirst();var b=booked.removeFirst();booked.addFirst(new Booked(a.start(),b.end(),a.joule()+b.joule()));}
        }
        void stopped(long start,long end){var last=sawStopped.peekLast();if(last!=null&&last[1]==start)last[1]=end;else sawStopped.addLast(new long[]{start,end});}
        /** Forgets what its other side, committed through {@code tick}, never reads again. */
        void prune(long tick) {
            while(!booked.isEmpty()&&booked.peekFirst().end()<=tick)booked.removeFirst();
            while(!sawStopped.isEmpty()&&sawStopped.peekFirst()[1]<=tick)sawStopped.removeFirst();
        }
    }
    /** The island's coupled sides: each coupled heat side's connection id, each kettle's pool's key (W7), and each fired
     * heater coil with its partner (F1). */
    static long[] coupledSides(PassiveNetwork graph) {
        var sides=graph.pipes().stream().filter(p->p.heat() instanceof HeatSide side&&side.coupled()||p.heat() instanceof FiredCoil coil&&coil.partner()!=0
                ||p.heat() instanceof ConvectionCoil coil&&coil.radiant()!=0).mapToLong(PassiveNetwork.Pipe::id);
        // A still's fired pot (batch 2026-11-09-batch-distiller, W3) has no other side: its heat is its firing.
        var pools=graph.reservoirs().stream().filter(n->n.inventory().pool()!=null&&!n.inventory().pool().fired()).mapToLong(n->n.inventory().pool().id());
        return java.util.stream.LongStream.concat(sides,pools).toArray();
    }
    static SideKind kindOf(PassiveNetwork graph,long pipe) {
        for(var p:graph.pipes())if(p.id()==pipe)return p.heat() instanceof FiredCoil?SideKind.RADIANT:p.heat() instanceof ConvectionCoil?SideKind.CONVECTION:SideKind.EXCHANGER;
        return SideKind.EXCHANGER;
    }
    static long partnerOf(PassiveNetwork graph,long pipe) {
        for(var p:graph.pipes())if(p.id()==pipe&&p.heat() instanceof HeatSide side)return side.partner();
        for(var p:graph.pipes())if(p.id()==pipe&&p.heat() instanceof FiredCoil coil)return coil.partner();
        for(var p:graph.pipes())if(p.id()==pipe&&p.heat() instanceof ConvectionCoil coil)return coil.radiant();
        for(var n:graph.reservoirs())if(n.inventory().pool()!=null&&n.inventory().pool().id()==pipe)return n.inventory().pool().partner();
        throw new IllegalArgumentException("No coupled side "+pipe);
    }
    /** {@code pipe}'s stream in {@code graph}, drawn at {@code flows} (kg/s per connection; null where none is known: no flow);
     * a kettle's pool's contents for a pool's key (W7), null where it holds no fluid. */
    static ExchangerRelay.Partner stream(PassiveNetwork graph,double[] flows,long pipe,FluidThermodynamics model) {
        for(var n:graph.reservoirs())if(n.inventory().pool()!=null&&n.inventory().pool().id()==pipe){var pool=ExchangerRelay.Partner.pool(n);return pool==null?null:pool.withModel(model);}
        var pipes=graph.pipes();
        for(int e=0;e<pipes.size();e++) {
            var p=pipes.get(e);if(p.id()!=pipe||!(p.heat() instanceof HeatSide law))continue;
            double flow=flows!=null&&flows.length==pipes.size()?flows[e]:0;int donor=flow>=0?p.first():p.second();
            return new ExchangerRelay.Partner(graph.reservoirs().get(donor).state(),p.portAt(donor),p.volume(),flow,law).withModel(model);
        }
        return null;
    }
    /** The island holding {@code side}'s other side, or null where none does. */
    private Island other(Coupled side){Long owner=coordinator.sideOwners.get(side.partner);return owner==null?null:coordinator.islands.get(owner);}
    /** The other islands holding the other sides of {@code island}'s exchangers. */
    List<Island> partners(Island island) {
        var found=new ArrayList<Island>();
        for(long pipe:island.sides){var side=coordinator.coupled.get(pipe);if(side==null)continue;var other=other(side);if(other!=null&&other!=island&&!found.contains(other))found.add(other);}
        return found;
    }
    /** Plan section 5.2: an explicit stopped state (held, phase-parked, suspended), never lag. */
    private static boolean stoppedSide(Island island){return island.held||island.phaseParked||island.suspended;}
    /**
     * The coupling fence (owner Q16, form C): each of the island's exchangers' other sides has committed through the
     * island's committed tick, or can be materialised there (certified, with no dated wake before it: that side solves
     * again from its wake, R-ST4), or is stopped, or has no island, or is this one. A pure read: nothing is materialised here.
     */
    boolean coupledThrough(Island island) {
        long start=island.clock.committedTick();
        for(long pipe:island.sides) {
            // A radiant coil reads nothing of its convection coil.
            var side=coordinator.coupled.get(pipe);if(side==null||side.kind==SideKind.RADIANT)continue;
            var other=other(side);if(other==null||other==island||stoppedSide(other)||other.clock.committedTick()>=start)continue;
            var certificate=other.certificate;
            if(certificate!=null&&Math.min(Math.min(other.clock.onlineTick(),other.fence()),Math.min(other.holdTick,certificate.horizonTick()))>=start&&!wokenBefore(other.id,start))continue;
            return false;
        }
        return true;
    }
    /** Whether island {@code id} holds a dated wake before {@code tick}. */
    private boolean wokenBefore(long id,long tick){var ticks=dated.get(id);return ticks!=null&&!ticks.isEmpty()&&ticks.first()<tick;}
    /**
     * What each of the island's coupled sides reads for {@code slice} (plan section 5.2): its other side's stream as of the
     * slice start, whether that side is stopped, the heat in transit as of the start (the other side's heat booked past it
     * taken out again), that side's slice length and the share of this slice it spent counting this one stopped. A certified
     * other side behind the start is materialised first, to the start (the fence let it through as far: {@link
     * #coupledThrough}), not to the moment of the dispatch, which follows when workers finished (codebase review R-ST4).
     * Arithmetic only.
     */
    Map<Long,ExchangerRelay.Read> reads(Island island,IslandClock.Slice slice) {
        if(island.sides.length==0)return Map.of();
        var reads=new HashMap<Long,ExchangerRelay.Read>();long start=slice.startTick(),end=slice.endTick();
        for(long pipe:island.sides) {
            var side=coordinator.coupled.get(pipe);if(side==null||side.kind==SideKind.RADIANT)continue;
            var held=coordinator.ports.heatRead(pipe,side.partner,start,slice.seconds(coordinator.settings.time()),transits.getOrDefault(side.pair(),0.));
            // A column's condenser read by its water side carries the valve the column sets, where its lines are complete (U2).
            if(held!=null){reads.put(pipe,held.ceiling()>0&&held.operation()==null?held.withOperation(operations.get(pipe)):held);continue;}
            var other=other(side);var otherSide=coordinator.coupled.get(side.partner);
            if(other!=null&&other!=island&&other.certificate!=null&&other.clock.committedTick()<start)coordinator.certificates.materialise(other,start);
            // A convection coil reads its radiant coil's heat as of the slice start (F1); one in this island plans it per step.
            if(side.kind==SideKind.CONVECTION){reads.put(pipe,ExchangerRelay.Read.radiant(otherSide==null||other==null?0:otherSide.rateAsOf(start)));continue;}
            boolean stoppedOther=other==null||other!=island&&stoppedSide(other);
            double due=transits.getOrDefault(side.pair(),0.)+(otherSide==null?0:otherSide.bookedPast(start));
            double seconds=other==null?0:other==island?slice.seconds(coordinator.settings.time()):coordinator.settings.time().seconds(Math.min(other.clock.snapshot().cadenceTicks(),other.maximumSliceTicks));
            reads.put(pipe,new ExchangerRelay.Read(otherSide==null?null:otherSide.asOf(start),stoppedOther,due,seconds,otherSide==null?0:otherSide.stoppedWithin(start,end),operations.get(pipe)));
        }
        return reads;
    }
    /**
     * Books {@code island}'s coupled sides over a committed or replayed span from {@code start} to {@code end} (plan section
     * 5.2): each side's heat leaves the heat in transit, the span is kept for its other side's as-of reads, a span in which
     * it read its other side stopped is kept for that side to sit out, and its record moves to the span's end. {@code reads}
     * is what a solved slice read (null for a replay). A certified other side whose read of this one moved is then woken as
     * of {@code end}, the tick from which it reads the moved record (R-ST4).
     */
    void booked(Island island,long start,long end,PassiveIntervalSolver.Result result,Map<Long,ExchangerRelay.Read> reads) {
        coordinator.ports.booked(island.id,island.graph,start,end,result);
        coordinator.shafts.booked(island.id,island.graph,start,end,result);
        for(long pipe:island.sides) {
            var side=coordinator.coupled.get(pipe);if(side==null)continue;
            double joule=heat(result,pipe);
            if(joule!=0&&side.kind==SideKind.EXCHANGER)transits.merge(side.pair(),-joule,Double::sum);
            var other=other(side);side.prune(other==null?end-KEEP_UNREAD_TICKS:other.clock.committedTick());side.book(start,end,joule);
            var read=reads==null?null:reads.get(pipe);
            if(read!=null){if(read.stopped())side.stopped(start,end);side.read=true;side.readUsed=read.partner();side.rateUsed=read.heatRate();side.heatRate=joule/coordinator.settings.time().seconds(end-start);}
            side.record(new SideRecord(end,stream(island.graph,result.endpointFlows(),pipe,island.model),joule/coordinator.settings.time().seconds(end-start)));
            if(other!=null&&other!=island&&other.certificate!=null&&moved(side,coordinator.coupled.get(side.partner),other))wake(other,end);
        }
    }
    /** Decision D4's wake of certified island {@code other} as of {@code tick} (R-ST4); its partners' fence re-derives it. */
    private void wake(Island other,long tick){if(dated.computeIfAbsent(other.id,k->new TreeSet<>()).add(tick))coordinator.reconsider(other);}
    /** The heat (J) {@code pipe}'s stream took up over {@code result}'s span. */
    static double heat(PassiveIntervalSolver.Result result,long pipe) {
        double joule=0;for(var b:result.boundaries())if(b.kind()==ConservativeTransport.BoundaryTransfer.Kind.HEAT&&b.nodeId()==pipe)joule+=b.totalEnergyJoule();
        return joule;
    }
    /** Decision D4: whether {@code otherSide}'s certified island must solve again after {@code side}'s commit. */
    private boolean moved(Coupled side,Coupled otherSide,Island other) {
        // A radiant coil's commit wakes its convection coil when its heat moved (F1); a convection coil's wakes nothing.
        if(side.kind!=SideKind.EXCHANGER) {
            if(side.kind!=SideKind.RADIANT||otherSide==null||side.current==null)return false;
            double rate=side.current.rate();
            if(!otherSide.read){otherSide.read=true;otherSide.rateUsed=rate;return false;}
            return Math.abs(rate-otherSide.rateUsed)>WAKE_FLOW*Math.abs(otherSide.rateUsed)+1;
        }
        if(otherSide==null||side.current==null||side.current.stream()==null)return false;
        var now=side.current.stream();
        if(!otherSide.read){otherSide.read=true;otherSide.readUsed=now;return false;}
        var used=otherSide.readUsed;if(used==null)return true;
        double slice=coordinator.settings.time().seconds(Math.min(other.clock.snapshot().cadenceTicks(),other.maximumSliceTicks));
        return Math.abs(now.donor().temperature()-used.donor().temperature())>WAKE_TEMPERATURE
                ||Math.abs(now.massFlow()-used.massFlow())>WAKE_FLOW*Math.abs(used.massFlow())
                // A controller's bypass moves the flow through the cells as a flow change does (W4).
                ||now.law()!=null&&used.law()!=null&&Math.abs(now.law().bypass()-used.law().bypass())>WAKE_FLOW
                // A kettle's pool that boils dry or floods changes its film (W7).
                ||now.pool()!=used.pool()||now.pool()&&Math.abs(now.bath().film()-used.bath().film())>WAKE_FLOW*used.bath().film()
                // The settled transit: the heat the side ahead booked past the other's commit is a clock offset, not a change.
                ||Math.abs(settledTransit(side.pair()))>WAKE_TRANSIT*Math.abs(otherSide.heatRate)*slice+1;
    }
    /** The certified islands woken by an exchanger partner's commit (decision D4, dated: {@link #dated}) and by a control, shaft
     * or port change ({@code toWake}) end their certificates here, at the engine's decision points (a round's close, the due
     * deadlines, a pump), never inside a presentation read. */
    void flushWakes() {
        if(waking||coordinator.toWake.isEmpty()&&dated.isEmpty())return;waking=true;
        try {
            flushDated();
            while(!coordinator.toWake.isEmpty()) {
                var it=coordinator.toWake.iterator();long id=it.next();it.remove();
                var island=coordinator.islands.get(id);if(island==null||island.certificate==null||coordinator.stopped||coordinator.suspension!=null)continue;
                // Its replay up to now first (which books its heat); that may reach its horizon, which ends it anyway.
                coordinator.certificates.materialise(island);if(island.certificate==null)continue;
                coordinator.certificates.end(island);
            }
        } finally{waking=false;}
    }
    /**
     * The dated wakes (R-ST4) whose islands' partners have all booked through their ticks, each island's in tick order: a
     * certified island is materialised to the tick, not to the moment this runs, and ends its certificate there; one awake
     * behind the tick keeps it ({@link IslandCoordinator#movedAt}). A materialisation may date wakes of other islands, so
     * the pass repeats until it settles nothing more. Before R-ST4 a partner's commit woke the island to the moment its job's
     * completion was drained, and the island solved on a grid from there.
     */
    private void flushDated() {
        for(boolean settled=true;settled&&!dated.isEmpty();) {
            settled=false;
            for(long id:List.copyOf(dated.keySet())) {
                var ticks=dated.get(id);if(ticks==null)continue;
                var island=coordinator.islands.get(id);
                if(island==null){dated.remove(id);continue;}
                boolean any=false;
                while(!ticks.isEmpty()&&partnersThrough(island,ticks.first())&&coordinator.movedAt(id,ticks.first())){ticks.pollFirst();any=true;}
                if(ticks.isEmpty())dated.remove(id);
                // Its partners' fence reads the wakes it still holds.
                if(any){settled=true;var now=coordinator.islands.get(id);if(now!=null)coordinator.reconsider(now);}
            }
        }
    }
    /** Whether every other island holding the other side of one of {@code island}'s exchangers has booked through {@code tick}. */
    private boolean partnersThrough(Island island,long tick) {
        for(var other:partners(island))if(!coordinator.reached(other.id,tick))return false;
        return true;
    }

    /** A side whose other side has no island keeps its booked spans this long, for an as-of read of its transit (C6b). */
    static final long KEEP_UNREAD_TICKS=4000;

    // The coordinator's public coupling methods, which forward here with their documentation; each checks the coordinator's thread.
    void operations(Map<Long,ExchangerRelay.Operation> next) {
        coordinator.owned();
        var changed=new HashSet<Long>(operations.keySet());changed.addAll(next.keySet());
        changed.removeIf(id->java.util.Objects.equals(operations.get(id),next.get(id)));
        operations=Map.copyOf(next);
        for(long id:changed){Long owner=coordinator.sideOwners.get(id);if(owner!=null)coordinator.toWake.add(owner);}
    }
    @org.jetbrains.annotations.Nullable ExchangerRelay.Operation operation(long side){coordinator.owned();return operations.get(side);}
    double sideTemperature(long side,long tick){coordinator.owned();var c=coordinator.coupled.get(side);var stream=c==null?null:c.asOf(tick);return stream==null?Double.NaN:stream.donor().temperature();}
    Map<Long,Double> transits(){coordinator.owned();return Map.copyOf(transits);}
    double settledTransit(long pair) {
        coordinator.owned();double joule=transits.getOrDefault(pair,0.);
        var sides=coordinator.pairSides.get(pair);if(sides==null||sides.size()<2)return joule;
        Coupled a=sides.get(0),b=sides.get(1);
        var ia=other(b);var ib=other(a);if(ia==null||ib==null)return joule;
        long behind=Math.min(ia.clock.committedTick(),ib.clock.committedTick());
        // A canonical order (code base review R23): the side of the lower island first, then the lower side identity. The scan
        // this replaces added them in the order of a hash map's iteration, which a map's history can change (a loaded world
        // against one that ran on), so the last bit of this sum, read only by the wake test (D4), depended on it.
        if(ia.id>ib.id||ia.id==ib.id&&a.pipe>b.pipe){var t=a;a=b;b=t;}
        return joule+a.bookedPast(behind)+b.bookedPast(behind);
    }
    void owe(long side,long partner,double joule) {
        coordinator.owned();if(!Double.isFinite(joule))throw new IllegalArgumentException("Invalid heat in transit");
        long pair=Math.min(side,partner);double next=transits.getOrDefault(pair,0.)+joule;if(next==0)transits.remove(pair);else transits.put(pair,next);
    }
    double transitAsOf(long side,long partner,long tick) {
        coordinator.owned();var s=coordinator.coupled.get(side);return transits.getOrDefault(Math.min(side,partner),0.)+(s==null?0:s.bookedPast(tick));
    }
    void transit(long pair,double joule) {
        coordinator.owned();if(!Double.isFinite(joule))throw new IllegalArgumentException("Invalid heat in transit");
        if(joule==0)transits.remove(pair);else transits.put(pair,joule);
    }
    double removeTransit(long pair){coordinator.owned();Double joule=transits.remove(pair);return joule==null?0:joule;}
}
