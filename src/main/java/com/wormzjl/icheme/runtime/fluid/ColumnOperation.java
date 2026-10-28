package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.column.operation.ColumnCycle;
import com.wormzjl.icheme.science.column.operation.ColumnCycle.Account;
import com.wormzjl.icheme.science.column.operation.Port;
import com.wormzjl.icheme.science.column.operation.SplitTable;
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

    /** Arrivals at an inlet over online ticks {@code start} to {@code end}. */
    record Span(long start,long end,Account arrived) {}
    /** A cycle's product credited to an outlet at {@code tick}. */
    record Credit(long tick,Account product) {}
    /** An inlet account's level after the cycle at {@code tick}, mol, and the rate the column demanded there, mol/s (0: nothing). */
    record Level(long tick,double held,double rate,double molarMass) {}
    /** A running column's state (plan section 12: the split table, the cycle clock, the windows). */
    static final class Run {
        final long unit;
        SplitTable table;
        /** Each product's specific enthalpy at its solved temperature and port pressure, J/mol, in the table's order. */
        double[] enthalpy;
        long nextCycle;
        String status="STARTING: waiting for the first solve";
        ColumnCycle.Step last;
        final Map<Long,ArrayDeque<Span>> spans=new HashMap<>();
        final Map<Long,ArrayDeque<Credit>> credits=new HashMap<>();
        final Map<Long,ArrayDeque<Level>> levels=new HashMap<>();
        Run(long unit,long nextCycle){this.unit=unit;this.nextCycle=nextCycle;}
    }

    private final WorldTopologyLedger topology;
    private final PhysicalRegistry registry;
    private final IslandCoordinator coordinator;
    private final double[] weights;
    private final Map<Long,Run> runs=new TreeMap<>();
    /** Each port device's unit and station, from the ledger's running units. */
    private final Map<Long,Long> portUnits=new HashMap<>();
    /** The port devices whose islands a cycle's new plan reaches at a later tick (the cycle's tick plus the fence), by that tick. */
    private final TreeMap<Long,Set<Long>> wakes=new TreeMap<>();

    public ColumnOperation(WorldTopologyLedger topology,PhysicalRegistry registry,IslandCoordinator coordinator,double[] weights) {
        this.topology=Objects.requireNonNull(topology);this.registry=Objects.requireNonNull(registry);this.coordinator=Objects.requireNonNull(coordinator);this.weights=weights.clone();
        refresh();
    }

    /** Reads the ledger's running units again: after a Start, a Stop, a load. A unit that stopped forgets its state. */
    public void refresh() {
        var units=topology.columns();portUnits.clear();
        runs.keySet().removeIf(id->{var u=units.get(id);return u==null||!u.running();});
        for(var u:units.values()) {
            if(!u.running())continue;
            runs.computeIfAbsent(u.id(),id->new Run(id,firstCycle(id)));
            for(var s:u.stations())if(s.device()!=0)portUnits.put(s.device(),u.id());
        }
        schedule();
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
    /** Installs a solved split table and its products' specific enthalpies (J/mol) for unit {@code unit} (C5). */
    public void table(long unit,SplitTable table,double[] enthalpy) {
        var r=runs.get(unit);if(r==null)return;
        if(enthalpy.length!=table.products().size())throw new IllegalArgumentException("One specific enthalpy per product");
        var u=topology.column(unit);
        for(var p:table.products())if(u.station(p.port(),p.index())==null)throw new IllegalArgumentException("Product "+p.port()+" "+p.index()+" has no station");
        r.table=table;r.enthalpy=enthalpy.clone();r.status="RUNNING";
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
            for(var r:List.copyOf(runs.values()))while(r.nextCycle<=now&&inletsThrough(r,r.nextCycle)){cycle(r,r.nextCycle);r.nextCycle+=CYCLE_TICKS;}
            // A certified port island rests on its old plan: wake it where the new one starts.
            while(!wakes.isEmpty()&&wakes.firstKey()<=now)for(long device:wakes.pollFirstEntry().getValue()){Long owner=registry.owner(device);if(owner!=null)coordinator.portsMoved(owner);}
        } finally{cycling=false;}
        schedule();
    }
    private boolean cycling;
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
        // Take each inlet's spans up to the tick into its account, an interval across the tick shared by time.
        for(int i=0;i<stations.size();i++) {
            var s=stations.get(i);if(!s.port().inlet())continue;
            var queue=r.spans.get(s.device());if(queue==null||queue.isEmpty())continue;
            var account=s.account();
            while(!queue.isEmpty()&&queue.peekFirst().start()<tick) {
                var span=queue.removeFirst();
                if(span.end()<=tick){account=account.plus(span.arrived());continue;}
                double share=(tick-span.start())/(double)(span.end()-span.start());var now=span.arrived().part(share);
                account=account.plus(now);queue.addFirst(new Span(tick,span.end(),span.arrived().less(now)));break;
            }
            stations.set(i,s.withAccount(account));
        }
        var totals=u.totals();
        if(r.table==null){levels(r,u,stations,tick,false);topology.column(u.withStations(true,stations));return;}
        var t=r.table;var feed=station(stations,Port.FEED,0);
        var steam=new ArrayList<Account>();
        for(int j=0;j<u.setpoints().steamPerFeed().length;j++){var s=station(stations,Port.STEAM,j);steam.add(s==null?Account.empty(weights.length):s.account());}
        double[] room=new double[t.products().size()];
        double design=u.setpoints().designFeedRate()*BUFFER_SECONDS;
        for(int k=0;k<room.length;k++) {
            var p=t.products().get(k);var s=station(stations,p.port(),p.index());
            double share=0;var z=t.reference().feedFractions();for(int c=0;c<z.length;c++)share+=p.recovery()[c]*z[c];
            room[k]=Math.max(0,design*share-s.account().total());
        }
        var energy=r.enthalpy;
        var step=ColumnCycle.step(t,u.setpoints(),CYCLE_TICKS/20.0,feed==null?Account.empty(weights.length):feed.account(),steam,room,(k,n)->{double sum=0;for(double v:n)sum+=v;return sum*energy[k];},false);
        r.last=step;
        if(step.trip()!=null){r.status="UPSET: "+step.trip();levels(r,u,stations,tick,false);topology.column(u.withStations(true,stations));return;}
        replace(stations,Port.FEED,0,a->a.less(step.feed()));
        for(int j=0;j<steam.size();j++){var taken=step.steam().get(j);replace(stations,Port.STEAM,j,a->a.less(taken));}
        for(int k=0;k<t.products().size();k++) {
            var p=t.products().get(k);var product=step.products().get(k);
            var s=station(stations,p.port(),p.index());replace(stations,p.port(),p.index(),a->a.plus(product));
            if(product.total()>0)r.credits.computeIfAbsent(s.device(),d->new ArrayDeque<>()).addLast(new Credit(tick,product));
        }
        var in=step.feed();for(var a:step.steam())in=in.plus(a);
        var out=Account.empty(weights.length);for(var a:step.products())out=out.plus(a);
        double pumparounds=0;for(double q:step.pumparounds())pumparounds+=q;
        totals=new ColumnUnit.Totals(totals.taken().plus(in),totals.produced().plus(out),totals.reboiler()+step.reboiler(),totals.condenser()+step.condenser(),totals.pumparounds()+pumparounds);
        levels(r,u,stations,tick,true);
        topology.column(u.withStations(true,stations).withTotals(totals));
        r.status=step.limit()==ColumnCycle.Limit.NONE?"RUNNING":String.format(Locale.ROOT,"LIMITED: %s, %.0f %%",step.limit().name().toLowerCase(Locale.ROOT),100*step.alpha());
    }
    /** Each inlet's level after the cycle at {@code tick} and the rate the column then demands of it (none: {@code demanding} false);
     * every port's island wakes where the plans this cycle dates take effect, the tick plus the fence. */
    private void levels(Run r,ColumnUnit u,List<ColumnUnit.Station> stations,long tick,boolean demanding) {
        for(var s:stations) {
            if(s.device()!=0)wakes.computeIfAbsent(tick+FENCE_TICKS,t->new TreeSet<>()).add(s.device());
            if(!s.port().inlet())continue;
            double rate=!demanding?0:s.port()==Port.FEED?u.setpoints().feedRate():u.setpoints().feedRate()*u.setpoints().steamPerFeed()[s.index()];
            r.levels.computeIfAbsent(s.device(),d->new ArrayDeque<>()).addLast(new Level(tick,s.account().total(),rate,molar(s.account().moles())));
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

    /** The outlet fence (plan section 9): every cycle through {@code start - L} has run for each outlet port the island holds. */
    @Override public boolean ready(long island,PassiveNetwork graph,long start) {
        if(portUnits.isEmpty())return true;
        for(var node:graph.reservoirs()) {
            if(node.kind()!=PassiveNetwork.NodeKind.GENERATOR)continue;
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
                if(r!=null)r.spans.computeIfAbsent(device,d->new ArrayDeque<>()).addLast(new Span(start,end,account));
                else topology.column(withAccount(u,s,s.account().plus(account)));
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
