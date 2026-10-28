package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.column.operation.ColumnCycle;
import com.wormzjl.icheme.science.column.operation.ColumnCycle.Account;
import com.wormzjl.icheme.science.column.operation.ColumnSolve;
import com.wormzjl.icheme.science.column.operation.DriftBands;
import com.wormzjl.icheme.science.column.operation.Port;
import com.wormzjl.icheme.science.column.operation.SplitTable;
import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.column.v3.V3NeuralSeed;
import com.wormzjl.icheme.science.fluid.network.ConservativeTransport;
import com.wormzjl.icheme.science.fluid.network.PassiveIntervalSolver;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.network.PortPlan;
import java.util.*;

/**
 * The running columns in the network (batch 2026-10-28-column-operation, C4, plan sections 4.3, 4.4, 5 and 9): the islands'
 * ports read their plans here, their bookings land here, and every running column takes a cycle each
 * {@link #CYCLE_TICKS} online ticks. Minecraft-free; the server thread owns it. Arithmetic only: the split table and each
 * product's specific enthalpy come from a solve on a worker (C5).
 *
 * <p>Conservation (plan 4.4). An inlet's void books what arrives; the account takes exactly that, as spans of online time,
 * and a cycle takes the spans' shares up to its tick. A cycle takes at most what an account holds and puts the same moles,
 * split, into the outlets' accounts; the energy closes on the condenser. An outlet's generator gives what its plan allows,
 * planned from the account's moles booked through the slice start less {@link #FENCE_TICKS}; the account is debited by
 * exactly what the island booked, component by component, and its energy by the same share; the difference between the
 * energy the island booked and that share is the condenser's (the column brings the product to the port's state).</p>
 */
public final class ColumnOperation implements IslandCoordinator.Ports {
    /** A cycle each 5 s of online time (plan section 5). */
    public static final int CYCLE_TICKS=100;
    /** The outlet fence L (owner Q4): a slice plans from the credits booked through its start less this. */
    public static final long FENCE_TICKS=440;
    /** An inlet's account aims at this much of its set rate, s; an outlet holds this much design production, s (plan 4.2). */
    public static final double INLET_AIM_SECONDS=30,BUFFER_SECONDS=120;
    /** The share of an outlet's available moles a slice may take: a margin over the valve's regulation tolerance. */
    static final double TAKE_MARGIN=1-1e-6;

    /** At most one solve per this many online ticks (30 s), except a setpoint edit (plan section 6). */
    static final long SOLVE_SPACING=600;
    /** A failed solve's retries back off: 30, 60, 120 s (plan section 6). */
    static final long[] RETRY_TICKS={600,1200,2400};
    /** A new table applies at the second cycle after its trigger (owner Q5). */
    static final long APPLY_TICKS=2*CYCLE_TICKS;
    /** The windows a save keeps beyond the fence: a port island this far behind still reads its plan. */
    static final long KEEP_TICKS=2400;

    /** Where a column's solves go (C5). */
    public interface Solves {
        /** The calculator input of unit {@code unit}'s design, or null where it has none. */
        V3ColumnInput design(ColumnUnit unit);
        /** Submits {@code job}; {@code done} gets its outcome on the server thread. False: not admitted, the next cycle tries again. */
        boolean submit(long unit,ColumnSolve.Job job,java.util.function.Consumer<ColumnSolve.Outcome> done);
        /** No solves: a table comes only from {@link #table}. */
        Solves NONE=new Solves(){public V3ColumnInput design(ColumnUnit unit){return null;}public boolean submit(long unit,ColumnSolve.Job job,java.util.function.Consumer<ColumnSolve.Outcome> done){return false;}};
    }
    /** A solve in flight: why, the cycle that asked and the cycle that applies it; its outcome once it is done. */
    static final class Pending {
        final DriftBands.Trigger trigger;final long tick;ColumnSolve.Outcome outcome;
        Pending(DriftBands.Trigger trigger,long tick){this.trigger=trigger;this.tick=tick;}
        long applyAt(){return tick+APPLY_TICKS;}
    }

    /** Arrivals at an inlet over online ticks {@code start} to {@code end}: already in the account, a cycle takes none of it before its time. */
    record Span(long start,long end,Account arrived) {}
    /** A cycle's product credited to an outlet at {@code tick}. */
    record Credit(long tick,Account product) {}
    /** An inlet account's level after the cycle at {@code tick}, mol, and the rate the column demanded there, mol/s (0: nothing). */
    record Level(long tick,double held,double rate,double molarMass) {}
    /** A running column's state (plan section 12: the split table, the cycle clock, the windows). */
    static final class Run {
        final long unit;
        SplitTable table;
        /** Each product's partial molar enthalpies at its solved temperature and pressure, J/mol, in the table's order (ColumnSolve.Solved). */
        double[][] enthalpy;
        long nextCycle;
        String status="STARTING: waiting for the first solve";
        ColumnCycle.Step last;
        /** The solve in flight, the tick of the last one asked, the failures in a row and the earliest retry, the warm seed. */
        Pending pending;long lastSolve=Long.MIN_VALUE/2,retryAt=Long.MIN_VALUE/2;int failures;V3NeuralSeed seed;
        /** Rule A in force (owner Q1, C1: rule B, with A where a solve fails): a solve of the short inputs failed, so every input is cut together until a solve succeeds or the retry tries B again. */
        boolean ruleA;
        /** The cycle that asked the table's solve and the cycle it applied at; V3's own condenser duty there, W; what the last solve said beyond its table. */
        long tableTick,applied;double solvedCondenser;String note="";
        final Map<Long,ArrayDeque<Span>> spans=new HashMap<>();
        final Map<Long,ArrayDeque<Credit>> credits=new HashMap<>();
        final Map<Long,ArrayDeque<Level>> levels=new HashMap<>();
        Run(long unit,long nextCycle){this.unit=unit;this.nextCycle=nextCycle;}
    }

    private final WorldTopologyLedger topology;
    private final PhysicalRegistry registry;
    private final IslandCoordinator coordinator;
    private final double[] weights;
    /** The network's component ids, water last. */
    private final List<String> axis;
    private final Map<Long,Run> runs=new TreeMap<>();
    /** Each port device's unit and station, from the ledger's running units. */
    private final Map<Long,Long> portUnits=new HashMap<>();
    /** The port devices whose islands a cycle's new plan reaches at a later tick (the cycle's tick plus the fence), by that tick. */
    private final TreeMap<Long,Set<Long>> wakes=new TreeMap<>();
    private Solves solves=Solves.NONE;
    /** Where this world's column solves go. */
    public void solves(Solves solves){this.solves=Objects.requireNonNull(solves);}

    public ColumnOperation(WorldTopologyLedger topology,PhysicalRegistry registry,IslandCoordinator coordinator,List<String> axis,double[] weights) {
        this.topology=Objects.requireNonNull(topology);this.registry=Objects.requireNonNull(registry);this.coordinator=Objects.requireNonNull(coordinator);this.weights=weights.clone();this.axis=List.copyOf(axis);if(axis.size()!=weights.length)throw new IllegalArgumentException("One weight per component");
        refresh();
    }

    /** Reads the ledger's running units again: after a Start, a Stop, a load. A unit that stopped forgets its state. */
    public void refresh() {
        var units=topology.columns();portUnits.clear();
        runs.keySet().removeIf(id->{var u=units.get(id);return u==null||!u.running();});
        for(var u:units.values()) {
            if(!u.running())continue;
            runs.computeIfAbsent(u.id(),id->restore(u));
            for(var s:u.stations())if(s.device()!=0)portUnits.put(s.device(),u.id());
        }
        schedule();
    }
    /**
     * A running unit's run: its saved engine state where it holds one this world can use (the network's axis, an outlet for
     * every product), so a reload takes no solve; otherwise a start. The cycles keep their grid, staggered by the identity.
     */
    private Run restore(ColumnUnit u) {
        var r=new Run(u.id(),firstCycle(u.id()));var s=u.state();
        if(s==null||!s.table().axis().equals(axis)||missingStation(u,s.table())!=null)return r;
        r.table=s.table();r.enthalpy=s.enthalpy();r.tableTick=s.tableTick();r.applied=s.applied();r.solvedCondenser=s.solvedCondenser();r.status="RUNNING";
        r.lastSolve=s.tableTick();
        for(var l:s.levels())r.levels.computeIfAbsent(l.device(),d->new ArrayDeque<>()).addLast(new Level(l.tick(),l.held(),l.rate(),l.molarMass()));
        for(var c:s.credits())r.credits.computeIfAbsent(c.device(),d->new ArrayDeque<>()).addLast(new Credit(c.tick(),c.product()));
        return r;
    }
    /** A unit's engine state into the ledger after each cycle, so a save carries it: the windows older than the plans can still read are dropped. */
    private void save(Run r) {
        var u=topology.column(r.unit);if(u==null||!u.running())return;
        if(r.table==null){if(u.state()!=null)topology.column(u.withState(null));return;}
        long through=r.nextCycle-FENCE_TICKS-KEEP_TICKS;
        var levels=new ArrayList<ColumnUnit.State.Level>();
        for(var e:new TreeMap<>(r.levels).entrySet()){asOf(e.getValue(),Level::tick,through);for(var l:e.getValue())levels.add(new ColumnUnit.State.Level(e.getKey(),l.tick(),l.held(),l.rate(),l.molarMass()));}
        var credits=new ArrayList<ColumnUnit.State.Credit>();
        for(var e:new TreeMap<>(r.credits).entrySet()){asOf(e.getValue(),Credit::tick,through);for(var c:e.getValue())credits.add(new ColumnUnit.State.Credit(e.getKey(),c.tick(),c.product()));}
        topology.column(u.withState(new ColumnUnit.State(r.table,r.enthalpy,r.tableTick,r.applied,r.solvedCondenser,levels,credits)));
    }
    /** A unit's first cycle: the next multiple of the cycle after now, staggered by its identity. */
    private long firstCycle(long unit) {
        long now=coordinator.now();long offset=Math.floorMod(unit*37,CYCLE_TICKS);
        long base=now-Math.floorMod(now-offset,CYCLE_TICKS);return base<=now?base+CYCLE_TICKS:base;
    }
    /** The run of unit {@code unit}, or null. */
    Run run(long unit){return runs.get(unit);}
    /** The status line of unit {@code unit}: its run's, or STOPPED. */
    public String status(long unit){var r=runs.get(unit);return r==null?"STOPPED":r.status;}
    /** The last cycle of unit {@code unit}, or null. */
    public ColumnCycle.Step last(long unit){var r=runs.get(unit);return r==null?null:r.last;}
    /** Installs a solved split table and its products' partial molar enthalpies (J/mol) for unit {@code unit} (C5). */
    public void table(long unit,SplitTable table,double[][] enthalpy) {
        var r=runs.get(unit);if(r==null)return;
        if(enthalpy.length!=table.products().size())throw new IllegalArgumentException("One enthalpy row per product");
        var u=topology.column(unit);
        for(var p:table.products())if(u.station(p.port(),p.index())==null)throw new IllegalArgumentException("Product "+p.port()+" "+p.index()+" has no station");
        r.table=table;r.enthalpy=ColumnSolve.copy(enthalpy);r.status="RUNNING";
    }

    /** The next cycle deadline: the earliest cycle still ahead. A cycle already due waits for its inlets' bookings, which
     * run it ({@link #booked}), never a deadline that fires again at once. */
    private void schedule() {
        long now=coordinator.now(),next=wakes.isEmpty()?Long.MAX_VALUE:Math.max(now+1,wakes.firstKey());for(var r:runs.values())if(r.nextCycle>now)next=Math.min(next,r.nextCycle);
        coordinator.schedule(IslandScheduler.Kind.COLUMN_CYCLE,next,this::due);
    }
    /** Runs every cycle that is due and whose inlets have booked through its tick, then schedules the next. */
    private void due() {
        // Materialising a certified inlet island books its replay here again: one pass at a time.
        if(cycling)return;cycling=true;
        try {
            long now=coordinator.now();
            for(var r:List.copyOf(runs.values()))while(r.nextCycle<=now&&inletsThrough(r,r.nextCycle)&&applied(r,r.nextCycle)){cycle(r,r.nextCycle);r.nextCycle+=CYCLE_TICKS;save(r);}
            // A certified port island rests on its old plan: wake it where the new one starts.
            while(!wakes.isEmpty()&&wakes.firstKey()<=now)for(long device:wakes.pollFirstEntry().getValue()){Long owner=registry.owner(device);if(owner!=null)coordinator.portsMoved(owner);}
        } finally{cycling=false;}
        schedule();
    }
    private boolean cycling;

    // ---------------- solves (plan section 6) ----------------

    /**
     * Whether the cycle at {@code tick} may run: no solve applies there, or its outcome is in and applied now. A solve
     * applies at the second cycle after its trigger whenever it finishes, so a table never depends on the worker's timing;
     * the solve deadline bounds the wait (a deadline that expires is a failed solve).
     */
    private boolean applied(Run r,long tick) {
        var p=r.pending;if(p==null||p.applyAt()>tick)return true;
        if(p.outcome==null)return false;
        r.pending=null;
        var u=topology.column(r.unit);
        String refused=p.outcome instanceof ColumnSolve.Solved s?missingStation(u,s.table()):null;
        if(p.outcome instanceof ColumnSolve.Solved s&&refused==null) {
            r.table=s.table();r.enthalpy=s.enthalpy();r.seed=s.seed();r.tableTick=p.tick;r.applied=tick;r.solvedCondenser=s.solvedCondenser();r.failures=0;r.note=s.note();
            // Only a solve of the short steam itself lifts rule A: one at A's own state says nothing of B's.
            if(p.trigger==DriftBands.Trigger.STEAM_RATIO)r.ruleA=false;
            return true;
        }
        String reason=refused!=null?refused:((ColumnSolve.Failed)p.outcome).reason();
        r.failures++;r.retryAt=tick+RETRY_TICKS[Math.min(r.failures,RETRY_TICKS.length)-1];
        // A solve of a changed steam ratio failed (zero steam without a reboiler, the draw wall): rule A, the inputs cut together.
        if(p.trigger==DriftBands.Trigger.STEAM_RATIO)r.ruleA=true;
        r.note=r.table==null?"":String.format(Locale.ROOT,"the %s solve failed (%s), the table of %d s ago kept",p.trigger.name().toLowerCase(Locale.ROOT),reason,(tick-r.tableTick)/20);
        if(r.table==null)r.status="NOT READY: "+reason;
        return true;
    }
    /** A product of {@code table} the unit has no outlet for, as a refusal, or null. */
    private static String missingStation(ColumnUnit u,SplitTable table) {
        for(var p:table.products())if(u.station(p.port(),p.index())==null)return "the solve gives a "+p.port().name().toLowerCase(Locale.ROOT)+" the column has no nozzle for";
        return null;
    }
    /**
     * Asks a solve at the cycle at {@code tick} for {@code trigger}, of the state {@code taken}: one in flight per column, at
     * most one per {@link #SOLVE_SPACING} except a setpoint edit or a start, none before a failure's retry time.
     */
    private void request(Run r,ColumnUnit u,long tick,DriftBands.Trigger trigger,ColumnSolve.Taken taken) {
        if(r.pending!=null||tick<r.retryAt)return;
        boolean urgent=trigger==DriftBands.Trigger.SETPOINT||trigger==DriftBands.Trigger.START;
        if(!urgent&&tick<r.lastSolve+SOLVE_SPACING)return;
        var design=solves.design(u);
        if(design==null){if(r.table==null)r.status="NOT READY: the design has no operating point";return;}
        if(taken==null)taken=designTaken(u,design);
        var pending=new Pending(trigger,tick);
        var job=new ColumnSolve.Job(trigger,design,u.setpoints(),taken,trigger==DriftBands.Trigger.START?null:r.seed);
        // In flight before the submission: a solver may answer at once.
        r.pending=pending;
        if(!solves.submit(r.unit,job,outcome->done(r,pending,outcome))){r.pending=null;return;}
        r.lastSolve=tick;
        if(r.table==null)r.status="STARTING: the first solve";
    }
    /** A solve's outcome, on the server thread: a cycle waiting for it runs now. */
    private void done(Run r,Pending pending,ColumnSolve.Outcome outcome) {
        if(runs.get(r.unit)!=r||r.pending!=pending)return;
        pending.outcome=outcome;
        if(r.nextCycle<=coordinator.now()&&r.nextCycle>=pending.applyAt())due();
    }
    /** The design's own feed and steam at the set rate, on the network's axis (a start, before anything was taken). */
    private ColumnSolve.Taken designTaken(ColumnUnit u,V3ColumnInput design) {
        double[] feed=new double[weights.length];
        double[] flows=design.feedComponentMolarFlowsMolPerSecond();double total=0;for(double f:flows)total+=f;
        double scale=total>0&&u.setpoints().feedRate()>0?u.setpoints().feedRate()/total:1;
        for(int c=0;c<flows.length;c++){int i=axis.indexOf(design.componentBasis().componentId(c));if(i>=0)feed[i]+=flows[c]*scale;}
        var steam=new ArrayList<ColumnSolve.Steam>();
        double rate=total*scale;double[] ratios=u.setpoints().steamPerFeed();
        for(int j=0;j<design.steamFeeds().size();j++)steam.add(new ColumnSolve.Steam(j<ratios.length?ratios[j]*rate:0,design.steamFeeds().get(j).temperatureKelvin(),Double.NaN));
        return new ColumnSolve.Taken(feed,Double.NaN,steam);
    }
    /** What the cycle {@code step} took, as rates over the cycle. */
    private ColumnSolve.Taken taken(ColumnCycle.Step step,V3ColumnInput design) {
        double seconds=CYCLE_TICKS/20.0;var feed=step.feed().moles();
        int water=weights.length-1;var steam=new ArrayList<ColumnSolve.Steam>();
        for(int j=0;j<step.steam().size();j++) {
            var a=step.steam().get(j);var n=a.moles();double w=n[water];
            // A steam port's hydrocarbons are split as feed (plan section 11).
            for(int i=0;i<n.length;i++)if(i!=water)feed[i]+=n[i];
            double hint=j<design.steamFeeds().size()?design.steamFeeds().get(j).temperatureKelvin():400;
            steam.add(new ColumnSolve.Steam(w/seconds,hint,w>0?a.energy()/a.total():Double.NaN));
        }
        double total=0;for(double n:feed)total+=n;
        double energy=step.feed().energy();
        for(int i=0;i<feed.length;i++)feed[i]/=seconds;
        return new ColumnSolve.Taken(feed,total>0?energy/step.feed().total():Double.NaN,steam);
    }
    /** Whether every inlet island of {@code r} has booked through {@code tick} (plan section 9). */
    private boolean inletsThrough(Run r,long tick) {
        var u=topology.column(r.unit);
        for(var s:u.stations()) {
            if(!s.port().inlet()||s.device()==0)continue;
            Long owner=registry.owner(s.device());
            if(owner!=null&&!coordinator.through(owner,tick))return false;
        }
        return true;
    }

    /** One cycle at online tick {@code tick} (plan section 5). */
    private void cycle(Run r,long tick) {
        var u=topology.column(r.unit);var stations=new ArrayList<>(u.stations());
        // What each inlet had by the tick: its account less what arrived after it, an interval across the tick shared by time.
        var available=new HashMap<Long,Account>();
        for(var s:u.stations()) {
            if(!s.port().inlet())continue;
            var later=Account.empty(weights.length);var queue=r.spans.get(s.device());
            if(queue!=null) {
                while(!queue.isEmpty()&&queue.peekFirst().end()<=tick)queue.removeFirst();
                for(var span:queue)later=later.plus(span.start()>=tick?span.arrived():span.arrived().part((span.end()-tick)/(double)(span.end()-span.start())));
            }
            available.put(s.device(),s.account().less(later));
        }
        var totals=u.totals();
        if(r.table==null) {
            request(r,u,tick,DriftBands.Trigger.START,null);
            levels(r,u,available,tick,false);return;
        }
        var t=r.table;var feedStation=station(stations,Port.FEED,0);
        var feedAvailable=feedStation==null?Account.empty(weights.length):available.get(feedStation.device());
        var steam=new ArrayList<Account>();
        for(int j=0;j<u.setpoints().steamPerFeed().length;j++){var s=station(stations,Port.STEAM,j);steam.add(s==null?Account.empty(weights.length):available.get(s.device()));}
        double[] room=new double[t.products().size()];
        double design=u.setpoints().designFeedRate()*BUFFER_SECONDS;
        for(int k=0;k<room.length;k++) {
            var p=t.products().get(k);var s=station(stations,p.port(),p.index());
            double share=0;var z=t.reference().feedFractions();for(int c=0;c<z.length;c++)share+=p.recovery()[c]*z[c];
            room[k]=Math.max(0,design*share-s.account().total());
        }
        var energy=r.enthalpy;
        // Rule A stays until a solve succeeds; at its retry time the cycle tries B again, and a still short steam asks the solve again.
        if(r.ruleA&&r.pending==null&&tick>=r.retryAt)r.ruleA=false;
        var step=ColumnCycle.step(t,u.setpoints(),CYCLE_TICKS/20.0,feedAvailable,steam,room,(k,n)->{double e=0;var h=energy[k];for(int i=0;i<n.length;i++)e+=n[i]*h[i];return e;},r.ruleA);
        r.last=step;
        if(step.trip()!=null) {
            // The model has no steady state for this input (a capability limit): the inlets close, a solve of the state the
            // column was given may lift it.
            r.status="UPSET: "+step.trip();
            if(feedAvailable.total()>0){var source=solves.design(u);if(source!=null)request(r,u,tick,DriftBands.Trigger.CLOSURE,attempted(u,feedAvailable,steam,source));}
            levels(r,u,available,tick,false);return;
        }
        replace(stations,Port.FEED,0,a->a.less(step.feed()));
        for(int j=0;j<steam.size();j++){var taken=step.steam().get(j);replace(stations,Port.STEAM,j,a->a.less(taken));}
        for(var e:available.entrySet()){for(var s:stations)if(s.device()==e.getKey())available.put(e.getKey(),s.port()==Port.FEED?e.getValue().less(step.feed()):s.port()==Port.STEAM?e.getValue().less(step.steam().get(s.index())):e.getValue());}
        for(int k=0;k<t.products().size();k++) {
            var p=t.products().get(k);var product=step.products().get(k);
            var s=station(stations,p.port(),p.index());replace(stations,p.port(),p.index(),a->a.plus(product));
            if(product.total()>0)r.credits.computeIfAbsent(s.device(),d->new ArrayDeque<>()).addLast(new Credit(tick,product));
        }
        var in=step.feed();for(var a:step.steam())in=in.plus(a);
        var out=Account.empty(weights.length);for(var a:step.products())out=out.plus(a);
        double pumparounds=0;for(double q:step.pumparounds())pumparounds+=q;
        totals=new ColumnUnit.Totals(totals.taken().plus(in),totals.produced().plus(out),totals.reboiler()+step.reboiler(),totals.condenser()+step.condenser(),totals.pumparounds()+pumparounds);
        topology.column(u.withStations(true,stations).withTotals(totals));
        levels(r,u,available,tick,true);
        r.status=step.limit()==ColumnCycle.Limit.NONE?"RUNNING":String.format(Locale.ROOT,"LIMITED: %s, %.0f %%",step.limit().name().toLowerCase(Locale.ROOT),100*step.alpha());
        if(r.ruleA)r.status+=", inputs cut together (rule A)";
        // A new solve where the state taken left the table's bands, or the setpoints moved (plan section 6).
        DriftBands.Trigger trigger=u.setpoints().intensivelyDiffers(t.reference().setpoints())?DriftBands.Trigger.SETPOINT
                :DriftBands.check(t,step,u.setpoints().designFeedRate()*CYCLE_TICKS/20.0).map(DriftBands.Crossing::trigger).orElse(null);
        if(trigger!=null&&step.feedMoles()>0){var source=solves.design(u);if(source!=null)request(r,u,tick,trigger,taken(step,source));}
        if(r.pending!=null)r.status="RE-SOLVING: "+r.pending.trigger.name().toLowerCase(Locale.ROOT)+"; "+r.status;
        if(!r.note.isEmpty())r.status+="; "+r.note;
    }
    /** The state a tripped cycle was given: the feed it holds at the set rate, the steam at its ratios. */
    private ColumnSolve.Taken attempted(ColumnUnit u,Account feed,List<Account> steam,V3ColumnInput design) {
        double seconds=CYCLE_TICKS/20.0,demand=u.setpoints().feedRate()*seconds;
        var feedTaken=feed.part(Math.min(1,demand/feed.total()));
        var steamTaken=new ArrayList<Account>();double[] ratios=u.setpoints().steamPerFeed();
        for(int j=0;j<steam.size();j++){var s=steam.get(j);double want=ratios[j]*feedTaken.total();steamTaken.add(s.total()>0?s.part(Math.min(1,want/s.total())):s);}
        var none=new double[0];
        return taken(new ColumnCycle.Step(1,ColumnCycle.Limit.NONE,feedTaken,steamTaken,List.of(),0,none,0,0,null),design);
    }
    /** Each inlet's level after the cycle at {@code tick} and the rate the column then demands of it (none: {@code demanding} false);
     * every port's island wakes where the plans this cycle dates take effect, the tick plus the fence. */
    private void levels(Run r,ColumnUnit u,Map<Long,Account> available,long tick,boolean demanding) {
        for(var s:u.stations()) {
            if(s.device()!=0)wakes.computeIfAbsent(tick+FENCE_TICKS,t->new TreeSet<>()).add(s.device());
            if(!s.port().inlet())continue;
            double rate=!demanding?0:s.port()==Port.FEED?u.setpoints().feedRate():u.setpoints().feedRate()*u.setpoints().steamPerFeed()[s.index()];
            r.levels.computeIfAbsent(s.device(),d->new ArrayDeque<>()).addLast(new Level(tick,available.get(s.device()).total(),rate,molar(available.get(s.device()).moles())));
        }
    }
    /** The latest entry of {@code log} at or before {@code through}, after dropping every older one, or null. */
    private static <T> T asOf(ArrayDeque<T> log,java.util.function.ToLongFunction<T> tick,long through) {
        if(log==null)return null;
        while(log.size()>1){var it=log.iterator();it.next();if(tick.applyAsLong(it.next())<=through)log.removeFirst();else break;}
        var first=log.peekFirst();return first!=null&&tick.applyAsLong(first)<=through?first:null;
    }
    private static ColumnUnit.Station station(List<ColumnUnit.Station> stations,Port port,int index) {
        for(var s:stations)if(s.port()==port&&s.index()==index)return s;
        return null;
    }
    private static void replace(List<ColumnUnit.Station> stations,Port port,int index,java.util.function.UnaryOperator<Account> change) {
        for(int i=0;i<stations.size();i++){var s=stations.get(i);if(s.port()==port&&s.index()==index){stations.set(i,s.withAccount(change.apply(s.account())));return;}}
    }

    // ---------------- the islands' ports (IslandCoordinator.Ports) ----------------

    /** The fence (plan section 9): every cycle through {@code start - L} has run for each port the island holds, whose plans read it. */
    @Override public boolean ready(long island,PassiveNetwork graph,long start) {
        if(portUnits.isEmpty())return true;
        for(var node:graph.reservoirs()) {
            Long unit=portUnits.get(node.id());if(unit==null)continue;
            var r=runs.get(unit);if(r!=null&&r.nextCycle<=start-FENCE_TICKS)return false;
        }
        return true;
    }
    @Override public Map<Long,PortPlan> plans(long island,PassiveNetwork graph,IslandClock.Slice slice) {
        if(portUnits.isEmpty())return Map.of();
        Map<Long,PortPlan> plans=null;
        for(var node:graph.reservoirs()) {
            Long unit=portUnits.get(node.id());if(unit==null)continue;
            var u=topology.column(unit);var r=runs.get(unit);ColumnUnit.Station s=null;
            for(var st:u.stations())if(st.device()==node.id())s=st;
            if(s==null)continue;
            if(plans==null)plans=new HashMap<>();
            plans.put(node.id(),s.port().inlet()?inletPlan(r,s,node,slice):outletPlan(r,s,node,slice));
        }
        return plans==null?Map.of():plans;
    }
    /**
     * An inlet's valve (plan 4.3, FC): the rate the column demanded at its last cycle through the slice start less the fence,
     * corrected by the account's level after that cycle against {@link #INLET_AIM_SECONDS} of that rate. Read as of the
     * fence, so the plan does not depend on when the island's job is dispatched (plan section 9).
     */
    private PortPlan inletPlan(Run r,ColumnUnit.Station s,PassiveNetwork.Reservoir node,IslandClock.Slice slice) {
        double target=0;
        var level=r==null?null:asOf(r.levels.get(s.device()),Level::tick,slice.startTick()-FENCE_TICKS);
        if(level!=null&&level.rate()>0) {
            double aim=level.rate()*INLET_AIM_SECONDS,gain=Math.max(0,Math.min(2,1+(aim-level.held())/aim));
            target=level.rate()*gain*(level.molarMass()>0?level.molarMass():molarMass(new double[0],node));
        }
        return new PortPlan(node.id(),s.valve(),target,0,null);
    }
    /**
     * An outlet's generator and valve (plan 4.3, LC): the account's moles booked through the slice start less the fence, at
     * the product's solved temperature; the valve takes the production of the last cycle through the fence corrected by the
     * account's fill, and never more than those moles over the slice.
     */
    private PortPlan outletPlan(Run r,ColumnUnit.Station s,PassiveNetwork.Reservoir node,IslandClock.Slice slice) {
        long fence=slice.startTick()-FENCE_TICKS;
        var available=s.account().moles();var log=r==null?null:r.credits.get(s.device());
        var made=r==null?null:asOf(log,Credit::tick,fence);
        if(log!=null)for(var c:log)if(c.tick()>fence){var n=c.product().moles();for(int i=0;i<available.length;i++)available[i]=Math.max(0,available[i]-n[i]);}
        double total=0;for(double n:available)total+=n;
        double temperature=node.state().temperature();
        if(r!=null&&r.table!=null)for(var p:r.table.products())if(p.port()==s.port()&&p.index()==s.index())temperature=p.temperature();
        if(!(total>0))return new PortPlan(node.id(),s.valve(),0,node.state().temperature(),com.wormzjl.icheme.science.fluid.solver.PhaseLayout.totalAmounts(node.state()));
        double mass=mass(available),cap=mass/slice.seconds()*TAKE_MARGIN,rate=cap;
        if(made!=null) {
            double produced=mass(made.product().moles())/(CYCLE_TICKS/20.0),aim=produced*BUFFER_SECONDS/2;
            if(aim>0)rate=produced*Math.max(0,Math.min(2,mass/aim));
        }
        return new PortPlan(node.id(),s.valve(),Math.min(cap,rate),temperature,available);
    }
    private double mass(double[] moles){double m=0;for(int i=0;i<moles.length;i++)m+=moles[i]*weights[i];return m;}
    private double molar(double[] moles){double t=0;for(double n:moles)t+=n;return t>0?mass(moles)/t:0;}
    private double molarMass(double[] moles,PassiveNetwork.Reservoir node) {
        double total=0,mass=0;for(int i=0;i<moles.length;i++){total+=moles[i];mass+=moles[i]*weights[i];}
        if(total>0)return mass/total;
        var n=com.wormzjl.icheme.science.fluid.solver.PhaseLayout.totalAmounts(node.state());total=0;mass=0;for(int i=0;i<n.length;i++){total+=n[i];mass+=n[i]*weights[i];}
        return total>0?mass/total:.1;
    }
    /** What the island's ports gave and took over {@code start} to {@code end} (plan 4.4). */
    @Override public void booked(long island,PassiveNetwork graph,long start,long end,PassiveIntervalSolver.Result result) {
        if(portUnits.isEmpty()||end<=start)return;
        Map<Long,double[]> moles=null;Map<Long,Double> energy=null;
        for(var b:result.boundaries()) {
            if(b.kind()!=ConservativeTransport.BoundaryTransfer.Kind.EXCHANGE||!portUnits.containsKey(b.nodeId()))continue;
            if(moles==null){moles=new HashMap<>();energy=new HashMap<>();}
            var sum=moles.computeIfAbsent(b.nodeId(),id->new double[weights.length]);var n=b.moles();for(int i=0;i<n.length;i++)sum[i]+=n[i];
            energy.merge(b.nodeId(),b.totalEnergyJoule(),Double::sum);
        }
        if(moles!=null)credit(moles,energy,start,end);
        // A cycle waiting on this island's bookings may run now, whether or not anything moved.
        boolean waiting=false;for(var r:runs.values())waiting|=r.nextCycle<=coordinator.now();
        if(waiting)due();
    }
    /** Books what the ports gave and took over {@code start} to {@code end}: an inlet's arrivals as a span, an outlet's gift as a debit. */
    private void credit(Map<Long,double[]> moles,Map<Long,Double> energy,long start,long end) {
        for(var e:moles.entrySet()) {
            long device=e.getKey();long unit=portUnits.get(device);var u=topology.column(unit);var r=runs.get(unit);
            ColumnUnit.Station s=null;for(var st:u.stations())if(st.device()==device)s=st;
            if(s==null)continue;
            double joule=energy.get(device);var n=e.getValue();
            if(s.port().inlet()) {
                // A void takes: its booking is what left the island, negative.
                var arrived=new double[n.length];for(int i=0;i<n.length;i++)arrived[i]=Math.max(0,-n[i]);
                var account=new Account(arrived,-joule);
                // In the account at once (a fact), and a span the cycles take it by (state).
                topology.column(withAccount(u,s,s.account().plus(account)));
                if(r!=null)r.spans.computeIfAbsent(device,d->new ArrayDeque<>()).addLast(new Span(start,end,account));
            } else {
                // A generator gives: debit exactly that, component by component; the energy by the same share of the account.
                var held=s.account().moles();double before=s.account().total(),given=0;
                for(int i=0;i<n.length;i++){double g=Math.max(0,n[i]);if(g>held[i]*(1+1e-9)+1e-12)com.wormzjl.icheme.IChemE.LOGGER.error("column status=OVERDRAWN unit={} port={} component={} held={} given={}",unit,s.port(),i,held[i],g);held[i]=Math.max(0,held[i]-g);given+=g;}
                double share=before>0?Math.min(1,given/before):0,debited=share*s.account().energy();
                var account=new Account(held,s.account().energy()-debited);
                var t=u.totals();u=withAccount(u,s,account).withTotals(new ColumnUnit.Totals(t.taken(),t.produced(),t.reboiler(),t.condenser()+(joule-debited),t.pumparounds()));
                topology.column(u);
            }
        }
    }
    private static ColumnUnit withAccount(ColumnUnit u,ColumnUnit.Station s,Account account) {
        var next=new ArrayList<ColumnUnit.Station>();for(var st:u.stations())next.add(st==s||st.port()==s.port()&&st.index()==s.index()?st.withAccount(account):st);
        return u.withStations(u.running(),next);
    }
}
