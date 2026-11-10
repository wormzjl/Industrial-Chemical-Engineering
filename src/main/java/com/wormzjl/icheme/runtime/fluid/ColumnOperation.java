package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.column.operation.ColumnCycle;
import com.wormzjl.icheme.science.column.operation.ColumnCycle.Account;
import com.wormzjl.icheme.science.column.operation.ColumnSolve;
import com.wormzjl.icheme.science.column.operation.ColumnStatus;
import com.wormzjl.icheme.science.column.operation.Condition;
import com.wormzjl.icheme.science.column.operation.Refusal;
import com.wormzjl.icheme.science.column.operation.Setpoints;
import com.wormzjl.icheme.science.column.operation.DriftBands;
import com.wormzjl.icheme.science.column.operation.Port;
import com.wormzjl.icheme.science.column.operation.SplitTable;
import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.column.v3.V3NeuralSeed;
import com.wormzjl.icheme.science.fluid.network.ConservativeTransport;
import com.wormzjl.icheme.science.fluid.network.PassiveIntervalSolver;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.network.PortPlan;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import java.util.*;

/**
 * The running columns in the network (batch 2026-10-28-column-operation, C4, plan sections 4.3, 4.4, 5 and 9): the islands'
 * ports read their plans here, their bookings land here, and every running column takes a cycle each
 * {@link #CYCLE_TICKS} online ticks. Minecraft-free; the server thread owns it. Arithmetic only: the split table and each
 * product's partial molar enthalpies come from a solve on a worker (C5).
 *
 * <p>Conservation (plan 4.4). An inlet's void books what arrives; the account takes exactly that, as spans of online time,
 * and a cycle takes the spans' shares up to its tick. A cycle takes at most what an account holds and puts the same moles,
 * split, into the outlets' accounts; the energy closes on the condenser. An outlet's generator gives what its plan allows,
 * planned from the account's moles booked through the slice start less {@link #FENCE_TICKS}; the account is debited by
 * exactly what the island booked, component by component, and its energy by the same share; the difference between the
 * energy the island booked and that share is the condenser's (the column brings the product to the port's state).
 * Solids go round the split (plan 5.2 and 11): a crystal melts as it arrives, booked as its component with its true energy;
 * an inert solid waits at its inlet, a cycle moves what arrived by its tick to the bottoms, and the bottoms' generator gives it.</p>
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
    /** A solve the worker did not finish (a deadline, a worker failure, a shutdown) is asked once more after this many ticks (30 s, W2): not a refusal. */
    static final long TRANSIENT_WAIT=600;
    /** A new table applies at the second cycle after its trigger (owner Q5). */
    static final long APPLY_TICKS=2*CYCLE_TICKS;
    /** The windows a save keeps beyond the fence: a port island this far behind still reads its plan. */
    static final long KEEP_TICKS=2400;

    /** Where a column's solves go (C5). */
    public interface Solves {
        /** The calculator input of unit {@code unit}'s design, or null where it has none. */
        V3ColumnInput design(ColumnUnit unit);
        /** Unit {@code unit}'s condenser, which says what share of its duty the cooling water takes: water-cooled where it has no design. */
        default com.wormzjl.icheme.science.equipment.ColumnComponents.Condenser condenser(ColumnUnit unit){return com.wormzjl.icheme.science.equipment.ColumnComponents.Condenser.EXCHANGER;}
        /** Submits {@code job}; {@code done} gets its outcome on the server thread. False: not admitted, the next cycle tries again. */
        boolean submit(long unit,ColumnSolve.Job job,java.util.function.Consumer<ColumnSolve.Outcome> done);
        /** No solves: a table comes only from {@link #table}. */
        Solves NONE=new Solves(){public V3ColumnInput design(ColumnUnit unit){return null;}public boolean submit(long unit,ColumnSolve.Job job,java.util.function.Consumer<ColumnSolve.Outcome> done){return false;}};
    }
    /**
     * A solve in flight: why, the cycle that asked and the cycle that applies it, the set points it solves and the state it
     * was given (as a drift reference, with the components its table holds); its outcome once it is done.
     */
    static final class Pending {
        final DriftBands.Trigger trigger;final long tick;final com.wormzjl.icheme.science.column.operation.Setpoints setpoints;final SplitTable.Reference state;final boolean[] held;ColumnSolve.Outcome outcome;
        Pending(DriftBands.Trigger trigger,long tick,com.wormzjl.icheme.science.column.operation.Setpoints setpoints,SplitTable.Reference state,boolean[] held){this.trigger=trigger;this.tick=tick;this.setpoints=setpoints;this.state=state;this.held=held;}
        long applyAt(){return tick+APPLY_TICKS;}
    }
    /** A state whose solve was refused (W2): no solve is asked again while the state taken stays inside its bands. */
    record Refused(DriftBands.Trigger trigger,SplitTable.Reference state,boolean[] held,Condition.Active notice) {}

    /** Heat put in transit to each condenser shell's water at {@code tick}, J (one shell's share): by a cycle, or {@code booked} by an outlet's island, whose job decides when it lands. */
    record Owed(long tick,double joule,boolean booked) {}
    /** The condenser holds at most this many seconds of its design duty its water has not taken, and its water leaves at least this far below the condensate (C6b, plan section 10; U2: the simulator's approach). */
    static final double CONDENSER_HOLD_SECONDS=60,APPROACH_KELVIN=com.wormzjl.icheme.science.equipment.HeatSides.WATER_APPROACH;
    /** The heat each condenser shell's water was owed per second by the cycle at {@code tick}, W: what a feed valve the column sets carries (U2). */
    record Duty(long tick,double watts) {}
    /**
     * A duty a running column owes to utility sides through their heat in transit (C6b; W4 of batch
     * 2026-11-10-column-failure-modes): the condenser's cooling water, the reboiler's heating, pumparound {@code index}'s
     * cooler. A heating utility ({@link #gives}) gives the duty, its outlet no colder than the bottoms plus the approach; a
     * cooling one takes it, its outlet no warmer than the condensate's or the draw tray's less the approach.
     */
    record Utility(Kind kind,int index) {
        enum Kind { CONDENSER, REBOILER, PUMPAROUND }
        static final Utility CONDENSER=new Utility(Kind.CONDENSER,0),REBOILER=new Utility(Kind.REBOILER,0);
        static Utility pumparound(int j){return new Utility(Kind.PUMPAROUND,j);}
        /** Whether the utility gives the column heat (the reboiler's) rather than takes it. */
        boolean gives(){return kind==Kind.REBOILER;}
        /** +1 where the utility takes the heat owed (the transit is positive), −1 where it gives it. */
        double sign(){return gives()?-1:1;}
    }
    /** What a running unit owes one utility: the heat put in transit, dated, and each cycle's heat rate a side is owed (memory only). */
    static final class Debt {
        final ArrayDeque<Owed> owed=new ArrayDeque<>();
        final ArrayDeque<Duty> rates=new ArrayDeque<>();
    }
    /** Arrivals at an inlet over online ticks {@code start} to {@code end}, with their solids: already in the account, a cycle takes none of it before its time. */
    record Span(long start,long end,Account arrived,SolidInventory solids) {}
    /** What an outlet gave over online ticks {@code start} to {@code end}, mol: already off the account. */
    record Gift(long start,long end,double moles) {}
    /** A cycle's product credited to an outlet at {@code tick}, and the solids it moved there. */
    record Credit(long tick,Account product,SolidInventory solids) {}
    /** An inlet account's level after the cycle at {@code tick}, mol, and the rate the column demanded there, mol/s (0: nothing). */
    record Level(long tick,double held,double rate,double molarMass) {}
    /** A running column's state (plan section 12: the split table, the cycle clock, the windows). */
    static final class Run {
        final long unit;
        SplitTable table;
        ColumnUnit.Probes probes;
        boolean rebuildProbes;
        /** Each product's partial molar enthalpies at its solved temperature and pressure, J/mol, in the table's order (ColumnSolve.Solved). */
        double[][] enthalpy;
        long nextCycle;
        String status="STARTING: waiting for the first solve";
        /** The status as the Operation tab says it (C7), set with {@link #status}; the solve that gave the table, whether warm. */
        com.wormzjl.icheme.science.column.operation.OperationView.Phase phase=com.wormzjl.icheme.science.column.operation.OperationView.Phase.STARTING;DriftBands.Trigger solvedBy;boolean warm;
        /** What the last cycle and the solves found (W1): the step's conditions, then the solve's and the notices. */
        List<Condition.Active> conditions=List.of();
        ColumnCycle.Step last;
        /** The solve in flight, the tick of the last one asked, the warm seed. */
        Pending pending;long lastSolve=Long.MIN_VALUE/2;V3NeuralSeed seed;
        /** After a solve the worker did not finish, no solve before this tick; whether that solve was already the one retry (W2). */
        long waitUntil=Long.MIN_VALUE/2;boolean retried;
        /** The state whose solve was refused, or null; the requested set points whose solve was refused and why, or null (W2, memory only). */
        Refused refused;com.wormzjl.icheme.science.column.operation.Setpoints refusedSetpoints;Condition.Active setpointRefusal;
        /** The design had no operating point at the last request while no table stands: NOT READY, or null. */
        Condition.Active missing;
        /** Rule A in force (owner Q1, C1: rule B, with A where a solve fails): a solve of the short steam was refused, so every input is cut together until the steam is no longer short or its shortage leaves the refused state's bands. */
        boolean ruleA;
        /** The cycle that asked the table's solve and the cycle it applied at; V3's own condenser duty there, W; what the accepted solve said beyond its table (FEED_WATER_ROUTED), or null. */
        long tableTick,applied;double solvedCondenser;Condition.Active routed;
        /** What the accepted solve advised (V3's bounded texts, W3 step 5), as ADVISORY notices. Memory only: a reload starts without them. */
        List<Condition.Active> advisories=List.of();
        /**
         * Per utility, the heat booked into its sides' transit, dated (what a side reads through the fence, C6b), and each
         * cycle's heat rate owed to one side (U2, W4). State: a reload starts the rates from the table.
         */
        final Map<Utility,Debt> debts=new HashMap<>();
        Debt debt(Utility utility){return debts.computeIfAbsent(utility,k->new Debt());}
        /** The air condenser's fans at the last cycle, a share of full speed, and their power, W (U2; NaN: no fans known). */
        double fans=Double.NaN,fanPower=Double.NaN;
        /** The fan sides that had no power at the last cycle (W5): their fans stood. Memory only. */
        final Set<Long> unpoweredFans=new HashSet<>();
        final Map<Long,ArrayDeque<Span>> spans=new HashMap<>();
        final Map<Long,ArrayDeque<Gift>> gifts=new HashMap<>();
        final Map<Long,ArrayDeque<Credit>> credits=new HashMap<>();
        final Map<Long,ArrayDeque<Level>> levels=new HashMap<>();
        /** The table the last applied one replaced, for plans dated before it applied. */
        SplitTable before;
        Run(long unit,long nextCycle){this.unit=unit;this.nextCycle=nextCycle;}
        /** The table in force at {@code tick}. */
        SplitTable tableAt(long tick){return before!=null&&tick<applied?before:table;}
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
    /** A utility side's unit and the duty it serves. */
    private record Served(long unit,Utility utility) {}
    /**
     * Each running unit's utility sides, by their connection ({@link PhysicalFluidTopology#sideIdentity}): the sides whose heat
     * partner the column is, its condenser's water (C6b) and its reboiler's and pumparound coolers' utilities (W4).
     */
    private final Map<Long,Served> utilitySides=new HashMap<>();
    /** The port devices whose islands a cycle's new plan reaches at a later tick (the cycle's tick plus the fence), by that tick. */
    private final TreeMap<Long,Set<Long>> wakes=new TreeMap<>();
    private Solves solves=Solves.NONE;
    /** Where this world's column solves go. */
    public void solves(Solves solves){this.solves=Objects.requireNonNull(solves);}
    /**
     * A running column's members' power (W5 of batch 2026-11-10-column-failure-modes, {@code MEMBER_POWER_NOTE.md}): at each
     * cycle a member books what it drew over the span that just ran, then says whether it has power for the next.
     */
    public interface Power {
        /** Whether machines need power (config {@code energy.machinesNeedPower}): without, nothing books and every member has power. */
        boolean needed();
        /** Fan side {@code side}'s bare tube, m². */
        double surface(long side);
        /** Fan side {@code side} ran its fans at {@code fans} of full speed until {@code tick}: books it; whether its store has power. */
        boolean fans(long side,double fans,long tick);
        /**
         * Pump {@code mover} ran at {@code share} of its rated duty until {@code tick}: books its rated shaft power times the
         * share on its train (a casing) or its own store (an in-line pump); whether its driver gives power for the next cycle.
         */
        boolean pump(long mover,double share,long tick);
        /** No power to book: every member runs free, as before W5. */
        Power NONE=new Power(){public boolean needed(){return false;}public double surface(long side){return 0;}public boolean fans(long side,double fans,long tick){return true;}public boolean pump(long mover,double share,long tick){return true;}};
    }
    private Power power=Power.NONE;
    /** Where this world's column members draw their power. */
    public void power(Power power){this.power=Objects.requireNonNull(power);}

    public ColumnOperation(WorldTopologyLedger topology,PhysicalRegistry registry,IslandCoordinator coordinator,List<String> axis,double[] weights) {
        this.topology=Objects.requireNonNull(topology);this.registry=Objects.requireNonNull(registry);this.coordinator=Objects.requireNonNull(coordinator);this.weights=weights.clone();this.axis=List.copyOf(axis);if(axis.size()!=weights.length)throw new IllegalArgumentException("One weight per component");
        refresh();
    }

    /**
     * Stops unit {@code unit} (plan 4.1): one queued event ({@code submit}) releases what it holds and takes its ports out.
     * The unit runs until that event commits ({@link #committed}): its ports stand in their islands until then, and what
     * they take and give is booked. With {@code leaves} (the column was dismantled) its unit then goes, its accounts booked
     * as destroyed. A Start still queued goes first: the event queues behind it. A stop already queued has left nothing to
     * edit, and only takes {@code leaves} on. Every account stays with the unit otherwise.
     */
    public void stop(long unit,boolean leaves,java.util.function.Consumer<List<WorldTopologyLedger.Edit>> submit) {
        var u=topology.column(unit);if(u==null)return;
        if(leaves&&!u.leaving())topology.column(u=u.leave());
        if(!u.running()){if(leaves)topology.removeColumn(unit);return;}
        var edits=new ArrayList<WorldTopologyLedger.Edit>();
        for(var s:u.stations())if(s.device()!=0&&registry.registrations().containsKey(s.device()))edits.add(new WorldTopologyLedger.Edit(s.device(),null));
        for(var r:registry.registrations().values())if(r.device().suspendedBy()==unit)
            edits.add(new WorldTopologyLedger.Edit(r.device().id(),new WorldTopologyLedger.Registration(r.device().withSuspendedBy(0),r.spec(),Math.addExact(r.revision(),1))));
        if(!edits.isEmpty())submit.accept(edits);
        if(!standing(u))stopped(u);
    }
    /** An event committed: a running unit whose ports have all left stops. */
    public void committed() {
        for(var u:topology.columns().values())if(u.running()&&!standing(u))stopped(u);
    }
    /** Whether a port of {@code u} stands in its island, or a queued event still places or removes one. */
    private boolean standing(ColumnUnit u) {
        for(var s:u.stations())if(s.device()!=0&&(topology.active().containsKey(s.device())||topology.queued(s.device())))return true;
        return false;
    }
    private void stopped(ColumnUnit u) {
        topology.column(u.withStations(false,u.stations().stream().map(s->s.withDevice(0)).toList()));
        if(u.leaving())topology.removeColumn(u.id());
        refresh();
        com.wormzjl.icheme.IChemE.LOGGER.info("column status=STOPPED master={},{},{} unit={}",u.master().x(),u.master().y(),u.master().z(),u.id());
    }
    /** Reads the ledger's running units again: after a Start, a Stop, a load. A unit that stopped forgets its state. */
    public void refresh() {
        var units=topology.columns();portUnits.clear();utilitySides.clear();
        runs.keySet().removeIf(id->{var u=units.get(id);return u==null||!u.running();});
        for(var u:units.values()) {
            if(!u.running())continue;
            runs.computeIfAbsent(u.id(),id->restore(u));
            for(var s:u.stations())if(s.device()!=0)portUnits.put(s.device(),u.id());
            for(var utility:utilities(u))for(long side:sideList(u,utility))utilitySides.put(PhysicalFluidTopology.sideIdentity(side),new Served(u.id(),utility));
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
        r.table=s.table();r.enthalpy=s.enthalpy();r.tableTick=s.tableTick();r.applied=s.applied();r.solvedCondenser=s.solvedCondenser();publish(r,List.of(),null);
        r.lastSolve=s.tableTick();r.probes=s.probes();r.rebuildProbes=s.probes()==null;
        for(var l:s.levels())r.levels.computeIfAbsent(l.device(),d->new ArrayDeque<>()).addLast(new Level(l.tick(),l.held(),l.rate(),l.molarMass()));
        for(var c:s.credits())r.credits.computeIfAbsent(c.device(),d->new ArrayDeque<>()).addLast(new Credit(c.tick(),c.product(),SolidInventory.EMPTY));
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
        topology.column(u.withState(new ColumnUnit.State(r.table,r.enthalpy,r.tableTick,r.applied,r.solvedCondenser,levels,credits,r.probes)));
    }
    /** A unit's first cycle: the next multiple of the cycle after now, staggered by its identity. */
    private long firstCycle(long unit) {
        long now=coordinator.now();long offset=Math.floorMod(unit*37,CYCLE_TICKS);
        long base=now-Math.floorMod(now-offset,CYCLE_TICKS);return base<=now?base+CYCLE_TICKS:base;
    }
    /** The run of unit {@code unit}, or null. */
    Run run(long unit){return runs.get(unit);}
    /** The status line of unit {@code unit}: its run's, or STOPPED; with the cooling water its valves pass (U2). */
    public String status(long unit){var r=runs.get(unit);return r==null?"STOPPED":r.status+water(topology.column(unit));}
    /**
     * The cooling water a column's valves pass, for its status (U2): their flow targets as their islands last committed them,
     * kg/s, and the water's regulated outlet, the inlet as read there plus its range under the ceiling, °C; empty where the
     * column sets no valve. A presentation read: it moves no island.
     */
    private String water(ColumnUnit u) {
        if(u==null)return "";
        double flow=0,outlet=Double.NaN,ceiling=Math.max(1,condenserTemperature(u)-APPROACH_KELVIN);int set=0;
        for(long side:sides(u,Utility.CONDENSER)) {
            long id=PhysicalFluidTopology.sideIdentity(side);var operation=coordinator.operation(id);
            Long valveOwner=operation==null||!operation.column()?null:registry.owner(operation.valve()),sideOwner=registry.owner(side);
            if(valveOwner==null||sideOwner==null)continue;
            var graph=coordinator.presentation(valveOwner).graph();
            for(var pipe:graph.pipes())if(pipe.control() instanceof com.wormzjl.icheme.science.fluid.network.FlowControl.ControlValve law
                    &&law.source()==com.wormzjl.icheme.science.fluid.network.FlowControl.ValveSource.MASS_FLOW&&graph.reservoirs().get(pipe.first()).id()==operation.valve())flow+=law.massTarget();
            var sideGraph=coordinator.presentation(sideOwner).graph();
            for(var pipe:sideGraph.pipes())if(pipe.id()==id)outlet=Math.min(sideGraph.reservoirs().get(pipe.first()).state().temperature()+operation.rise(),ceiling);
            set++;
        }
        return set==0?"":String.format(Locale.ROOT,flow>=10?"; cooling water %.0f kg/s, out at %.1f °C":"; cooling water %.3g kg/s, out at %.1f °C",flow,outlet-273.15);
    }
    /** The last cycle of unit {@code unit}, or null. */
    public ColumnCycle.Step last(long unit){var r=runs.get(unit);return r==null?null:r.last;}
    /**
     * The Operation tab's view of unit {@code unit} at online tick {@code now} (C7, plan section 13): a read of its run's last
     * cycle and table, no solve. Each port's rate over that cycle, its temperature (the table's; the feed's moved along the
     * table's slope to the enthalpy taken), the pressure its port holds, its account against its aim (an inlet: 30 s of its
     * set rate; an outlet: its buffer of design production), its valve's opening as {@code opening} reads it (NaN unknown);
     * the duties booked over the cycle against the table's at the set feed. Null where the ledger has no such unit.
     */
    public com.wormzjl.icheme.science.column.operation.OperationView view(long unit,long now,java.util.function.LongToDoubleFunction opening) {
        var u=topology.column(unit);if(u==null)return null;
        var r=u.running()?runs.get(unit):null;var set=u.setpoints();
        if(r==null)return new com.wormzjl.icheme.science.column.operation.OperationView(com.wormzjl.icheme.science.column.operation.OperationView.Phase.STOPPED,List.of(),0,null,
                "STOPPED",false,true,set,null,List.of(),List.of(),null);
        var t=r.table;var step=r.last!=null&&!r.last.tripped()?r.last:null;double seconds=CYCLE_TICKS/20.0;
        // The cycle demands its inlets at the accepted set points (W2); the view shows the requested ones beside them where they differ.
        var demanded=running(u,t);var accepted=t!=null&&set.intensivelyDiffers(t.reference().setpoints())?demanded:null;
        var stations=new ArrayList<>(u.stations());stations.removeIf(s->s.device()==0);stations.sort(Comparator.comparing((ColumnUnit.Station s)->s.port()).thenComparingInt(ColumnUnit.Station::index));
        var streams=new ArrayList<com.wormzjl.icheme.science.column.operation.OperationView.Stream>();
        for(var s:stations) {
            var registration=s.device()==0?null:topology.active().get(s.device());
            double rate=0,temperature=Double.NaN,held=Double.NaN,pressure=registration==null?Double.NaN:registration.spec().pressure();
            if(s.port().inlet()) {
                var taken=step==null?null:s.port()==Port.FEED?step.feed():s.index()<step.steam().size()?step.steam().get(s.index()):null;
                if(taken!=null)rate=taken.total()/seconds;
                if(t!=null)temperature=s.port()==Port.FEED?t.reference().feedTemperature(taken!=null&&taken.total()>0?taken.energy()/taken.total():Double.NaN)
                        :s.index()<t.reference().steamTemperatures().length?t.reference().steamTemperatures()[s.index()]:Double.NaN;
                double aim=INLET_AIM_SECONDS*(s.port()==Port.FEED?demanded.feedRate():s.index()<demanded.steamPerFeed().length?demanded.feedRate()*demanded.steamPerFeed()[s.index()]:0);
                if(aim>0)held=s.account().total()/aim;
            } else if(t!=null) {
                int k=t.product(s.port(),s.index());
                if(k>=0) {
                    var p=t.products().get(k);temperature=p.temperature();
                    if(step!=null&&k<step.products().size())rate=step.products().get(k).total()/seconds;
                    double capacity=set.designFeedRate()*BUFFER_SECONDS*t.perFeed(k);
                    if(capacity>0)held=s.account().total()/capacity;
                }
            }
            streams.add(new com.wormzjl.icheme.science.column.operation.OperationView.Stream(s.port(),s.index(),rate,temperature,pressure,held,opening.applyAsDouble(s.valve())));
        }
        var duties=new ArrayList<com.wormzjl.icheme.science.column.operation.OperationView.Duty>();
        if(t!=null) {
            double f=set.feedRate();
            duties.add(new com.wormzjl.icheme.science.column.operation.OperationView.Duty(com.wormzjl.icheme.science.column.operation.OperationView.DutyKind.CONDENSER,0,step==null?0:step.condenser()/seconds,t.condenserPerFeed()*f));
            if(set.reboilerPerFeed()!=0)duties.add(new com.wormzjl.icheme.science.column.operation.OperationView.Duty(com.wormzjl.icheme.science.column.operation.OperationView.DutyKind.REBOILER,0,step==null?0:step.reboiler()/seconds,t.reboilerPerFeed()*f));
            var pa=t.pumparoundPerFeed();
            for(int j=0;j<pa.length;j++)duties.add(new com.wormzjl.icheme.science.column.operation.OperationView.Duty(com.wormzjl.icheme.science.column.operation.OperationView.DutyKind.PUMPAROUND,j,
                    step==null||j>=step.pumparounds().length?0:step.pumparounds()[j]/seconds,pa[j]*f));
            // An air condenser's fans (U2): their power at the last cycle, and at the set feed, for the electricity network to come.
            double atSet=t.fans(u.airSurface(),f);
            if(!Double.isNaN(atSet))duties.add(new com.wormzjl.icheme.science.column.operation.OperationView.Duty(com.wormzjl.icheme.science.column.operation.OperationView.DutyKind.FANS,0,
                    step==null||Double.isNaN(r.fanPower)?0:r.fanPower,com.wormzjl.icheme.science.equipment.HeatSides.fanPower(u.airSurface(),atSet)));
        }
        var solve=t==null?null:new com.wormzjl.icheme.science.column.operation.OperationView.Solve(r.solvedBy,r.warm,Math.max(0,now-r.tableTick)/20.0);
        // The trays at the feed taken (W7): the live drop as the channel reads it, the worst tray's flood fraction.
        var trays=com.wormzjl.icheme.science.column.operation.OperationView.Trays.NONE;
        if(t!=null&&t.flood().known()){double taken=step==null?0:step.feedMoles()/seconds;trays=new com.wormzjl.icheme.science.column.operation.OperationView.Trays(drop(t,taken),t.flood().fraction(taken),drop(t,set.feedRate()),t.flood().fraction(set.feedRate()),t.flood().tray()+1);}
        return new com.wormzjl.icheme.science.column.operation.OperationView(r.phase,r.conditions,step==null?0:step.alpha(),
                r.pending==null?null:r.pending.trigger,r.status+water(u),true,true,set,accepted,streams,duties,solve,trays);
    }
    /** The role of a column's pressure drop channel, {@code differential_pressure} (W3.3). */
    public static final String DIFFERENTIAL="COLUMN:differential";
    record InstrumentSample(double value,long tick) {}
    ColumnUnit.Probes probes(com.wormzjl.icheme.science.column.v3.V3ColumnInspection accepted,SplitTable table){
        if(accepted==null)return null;
        var top=accepted.nodes().get(1);var bottom=accepted.nodes().getLast();var reflux=accepted.nodes().getFirst();
        // The cycle meters total feed, including feed water; V3's component vector is hydrocarbon-only.
        double feed=table.reference().alpha()*table.reference().setpoints().designFeedRate(),refluxMass=0,vapourMass=0;
        if(!(feed>0))return null;
        for(int c=0;c<accepted.input().componentBasis().componentCount();c++){
            int k=axis.indexOf(accepted.input().componentBasis().componentId(c));
            if(k<0)return null;
            refluxMass+=reflux.liquidMolPerSecond()*reflux.liquidFractions().get(c)*weights[k];
            vapourMass+=top.vaporMolPerSecond()*top.vaporFractions().get(c)*weights[k];
        }
        int water=axis.indexOf(SplitTable.WATER);if(water>=0)vapourMass+=top.waterVaporMolPerSecond()*weights[water];
        return new ColumnUnit.Probes(top.pressurePascal(),top.temperatureKelvin(),bottom.pressurePascal(),bottom.temperatureKelvin(),reflux.pressurePascal(),reflux.temperatureKelvin(),refluxMass/feed,vapourMass/feed);
    }
    InstrumentSample instrument(long unit,String role,com.wormzjl.icheme.runtime.instrument.Sensor.Kind kind){
        var u=topology.column(unit);var r=runs.get(unit);if(u==null||r==null||r.table==null||r.last==null)return null;
        // On a trip the cycle took nothing: flows read 0, pressures and temperatures stay the table's (W1.7).
        double value=Double.NaN;long tick=r.applied;
        if(role.equals(DIFFERENTIAL)){if(kind!=com.wormzjl.icheme.runtime.instrument.Sensor.Kind.PRESSURE)return null;value=drop(unit);}
        else if(role.startsWith("COLUMN:")){
            if(r.probes==null)return null;boolean bottom=role.endsWith("bottom");
            value=kind==com.wormzjl.icheme.runtime.instrument.Sensor.Kind.PRESSURE?(bottom?r.probes.bottomPressure():r.probes.topPressure()):(bottom?r.probes.bottomTemperature():r.probes.topTemperature());
        }else if(role.equals("REFLUX")){
            if(r.probes==null)return null;
            if(kind==com.wormzjl.icheme.runtime.instrument.Sensor.Kind.PRESSURE)value=r.probes.refluxPressure();
            else if(kind==com.wormzjl.icheme.runtime.instrument.Sensor.Kind.TEMPERATURE)value=r.probes.refluxTemperature();
            else value=r.probes.refluxPerFeed()*r.last.feedMoles()/(CYCLE_TICKS/20.0);
        }else if(role.startsWith("COLUMN_PORT:")){
            var parts=role.split(":");int index=Integer.parseInt(parts[2]);
            if(parts[1].equals("VAPOUR_OUTLET")){
                if(r.probes==null)return null;
                if(kind==com.wormzjl.icheme.runtime.instrument.Sensor.Kind.PRESSURE)value=r.probes.topPressure();else if(kind==com.wormzjl.icheme.runtime.instrument.Sensor.Kind.TEMPERATURE)value=r.probes.topTemperature();
                else value=r.probes.vapourPerFeed()*r.last.feedMoles()/(CYCLE_TICKS/20.0);return Double.isFinite(value)?new InstrumentSample(value,tick):null;
            }
            Port port;try{port=Port.valueOf(parts[1]);}catch(IllegalArgumentException e){return null;}
            var stream=view(unit,topology.onlineTick(),id->Double.NaN).streams().stream().filter(p->p.port()==port&&p.index()==index).findFirst().orElse(null);if(stream==null)return null;
            if(kind==com.wormzjl.icheme.runtime.instrument.Sensor.Kind.PRESSURE)value=stream.pressure();
            else if(kind==com.wormzjl.icheme.runtime.instrument.Sensor.Kind.TEMPERATURE)value=stream.temperature();
            else {ColumnCycle.Account account=null;if(port==Port.FEED)account=r.last.feed();else if(port==Port.STEAM&&index<r.last.steam().size())account=r.last.steam().get(index);else {int k=r.table.product(port,index);if(k>=0&&k<r.last.products().size())account=r.last.products().get(k);}if(account!=null)value=mass(account.moles())/(CYCLE_TICKS/20.0);}
        }
        return Double.isFinite(value)?new InstrumentSample(value,tick):null;
    }
    /** Installs a solved split table and its products' partial molar enthalpies (J/mol) for unit {@code unit} (C5). */
    public void table(long unit,SplitTable table,double[][] enthalpy) {
        var r=runs.get(unit);if(r==null)return;
        if(enthalpy.length!=table.products().size())throw new IllegalArgumentException("One enthalpy row per product");
        var u=topology.column(unit);
        for(var p:table.products())if(u.station(p.port(),p.index())==null)throw new IllegalArgumentException("Product "+p.port()+" "+p.index()+" has no station");
        r.table=table;r.enthalpy=ColumnSolve.copy(enthalpy);publish(r,List.of(),null);
    }
    /**
     * The set points of unit {@code unit} were applied (W2): a refusal of the ones requested before is forgotten, so the next
     * cycle asks one solve of what now stands. Memory only; the cycle runs on its schedule.
     */
    public void edited(long unit){var r=runs.get(unit);if(r!=null){r.refusedSetpoints=null;r.setpointRefusal=null;}}
    /** What the last cycle of unit {@code unit} and its solves found (W1), or none where it does not run. */
    public List<Condition.Active> conditions(long unit){var r=runs.get(unit);return r==null?List.of():r.conditions;}
    /**
     * The status and phase of {@code r} from what the step found ({@code step}), the solve state and the notices (W1): one
     * list, one formatter (ColumnStatus).
     */
    private void publish(Run r,List<Condition.Active> step,Condition.Active unmetered) {
        var list=new ArrayList<>(step);
        if(r.pending!=null)list.add(new Condition.Active(Condition.OFF_TABLE,r.pending.trigger.name().toLowerCase(Locale.ROOT)));
        if(r.table==null&&r.missing!=null)list.add(r.missing);
        if(r.refused!=null)list.add(r.refused.notice());
        if(r.setpointRefusal!=null)list.add(r.setpointRefusal);
        if(r.routed!=null)list.add(r.routed);
        list.addAll(r.advisories);
        if(unmetered!=null)list.add(unmetered);
        r.conditions=List.copyOf(list);
        r.status=ColumnStatus.line(r.table!=null,r.conditions,r.last==null?0:r.last.alpha(),r.fans,r.fanPower);
        r.phase=ColumnStatus.phase(r.table!=null,r.conditions);
    }
    /** The set points the cycle runs on (W2): the table's accepted ones with the unit's feed rate; the requested ones before any table. */
    /** The set points the cycle runs on: the accepted ones (W2) at the feed rate it takes, the set one or a control loop's plan below it (W6.3). */
    private Setpoints running(ColumnUnit u,SplitTable table){double rate=feedRate(u);return table==null?u.setpoints().withFeedRate(rate):table.reference().setpoints().withFeedRate(rate);}
    /** A control loop's planned feed rate for a unit, mol/s, NaN for none (W6.3). */
    public interface FeedPlans{double feed(long unit);}
    private FeedPlans feedPlans=unit->Double.NaN;
    public void feedPlans(FeedPlans plans){feedPlans=Objects.requireNonNull(plans);}
    /** The feed rate unit {@code u}'s cycle takes, mol/s: its set rate, or a control loop's plan below it (W6.3). The saved set point does not change. */
    public double feedRate(ColumnUnit u){double set=u.setpoints().feedRate(),plan=feedPlans.feed(u.id());return plan>=0&&plan<set?plan:set;}
    /** The molar mass the feed inlet of unit {@code unit} meters by, kg/mol, or NaN: its last level's (W6.3: a flow loop's planned feed). */
    public double feedMolarMass(long unit){var r=runs.get(unit);var u=topology.column(unit);if(r==null||u==null)return Double.NaN;var s=u.station(Port.FEED,0);var log=s==null?null:r.levels.get(s.device());var last=log==null?null:log.peekLast();return last==null||!(last.molarMass()>0)?Double.NaN:last.molarMass();}
    /** The condenser temperature the column runs at: the accepted set point where a table stands, the requested one before (a finding of stage 1: the water's ceiling follows the table in force). */
    private double condenserTemperature(ColumnUnit u){var r=runs.get(u.id());return running(u,r==null?null:r.table).condenserTemperature();}
    /**
     * The column's pressure drop at {@code feedRate} mol/s of feed taken on {@code table}, Pa (W3.3): the solved dry drop by the
     * square of the feed over the table's own, its liquid head as solved; NaN where the table has no hydraulics. Port pressures
     * do not move with it.
     */
    static double drop(SplitTable table,double feedRate){
        var flood=table.flood();if(!flood.known())return Double.NaN;
        double solved=table.reference().alpha()*table.reference().setpoints().designFeedRate();
        return flood.drop(solved>0?feedRate/solved:0);
    }
    /** Unit {@code unit}'s live pressure drop over its trays at the last cycle, Pa, or NaN (W3.3: the tab and the {@code differential_pressure} channel). */
    public double drop(long unit){var r=runs.get(unit);return r==null||r.table==null||r.last==null?Double.NaN:drop(r.table,r.last.feedMoles()/(CYCLE_TICKS/20.0));}

    /** The next cycle deadline: the earliest cycle still ahead. A cycle already due waits for its ports' bookings, which run it
     * ({@link #booked}); one a cycle later looks again, since a port's island that stopped (a hold) books nothing. */
    private void schedule() {
        long now=coordinator.now(),next=wakes.isEmpty()?Long.MAX_VALUE:Math.max(now+1,wakes.firstKey());for(var r:runs.values())next=Math.min(next,r.nextCycle>now?r.nextCycle:now+CYCLE_TICKS);
        coordinator.schedule(IslandScheduler.Kind.COLUMN_CYCLE,next,this::due);
    }
    /** Runs every cycle that is due and whose inlets have booked through its tick, then schedules the next. */
    private void due() {
        // Materialising a certified inlet island books its replay here again: one pass at a time.
        if(cycling)return;cycling=true;
        try {
            long now=coordinator.now();
            for(var r:List.copyOf(runs.values()))while(r.nextCycle<=now&&portsThrough(r,r.nextCycle)&&applied(r,r.nextCycle)){cycle(r,r.nextCycle);r.nextCycle+=CYCLE_TICKS;save(r);}
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
     *
     * <p>A refused solve is not asked again on its own (W2): a refused set point stays refused while the unit's set points
     * equal it (rule A for a refused short steam), a refused state while the state taken stays inside its bands. A solve the
     * worker did not finish is asked once more after {@link #TRANSIENT_WAIT}; a second such failure is a refusal.</p>
     */
    private boolean applied(Run r,long tick) {
        var p=r.pending;if(p==null||p.applyAt()>tick)return true;
        if(p.outcome==null)return false;
        r.pending=null;
        var u=topology.column(r.unit);
        String refused=p.outcome instanceof ColumnSolve.Solved s?missingStation(u,s.table()):null;
        if(p.outcome instanceof ColumnSolve.Solved s&&refused==null) {
            r.before=r.table;r.table=s.table();r.enthalpy=s.enthalpy();r.seed=s.seed();r.probes=probes(s.inspection(),s.table());r.rebuildProbes=false;r.tableTick=p.tick;r.applied=tick;r.solvedCondenser=s.solvedCondenser();r.solvedBy=p.trigger;r.warm=s.route().contains("warm");
            r.routed=s.note().isEmpty()?null:new Condition.Active(Condition.FEED_WATER_ROUTED,s.note());
            r.advisories=s.advisories().stream().map(a->new Condition.Active(Condition.ADVISORY,a)).toList();
            r.refused=null;r.retried=false;
            // The requested set points are in force: no refusal of them stands.
            if(p.trigger==DriftBands.Trigger.SETPOINT||p.trigger==DriftBands.Trigger.START){r.refusedSetpoints=null;r.setpointRefusal=null;}
            // Only a solve of the short steam itself lifts rule A: one at A's own state says nothing of B's.
            if(p.trigger==DriftBands.Trigger.STEAM_RATIO)r.ruleA=false;
            return true;
        }
        var failed=(ColumnSolve.Failed)(refused==null?p.outcome:new ColumnSolve.Failed(refused));
        // The worker did not finish it (a deadline, a worker failure, a shutdown): once more after a wait, not a refusal.
        if(failed.transientFailure()&&!r.retried){r.retried=true;r.waitUntil=tick+TRANSIENT_WAIT;return true;}
        r.retried=false;
        String text=r.table==null?Refusal.FIRST_SOLVE.text(failed.reason())
                :String.format(Locale.ROOT,"the %s solve failed (%s), the table of %d s ago kept",p.trigger.name().toLowerCase(Locale.ROOT),failed.reason(),(tick-r.tableTick)/20);
        if(p.trigger==DriftBands.Trigger.SETPOINT){r.refusedSetpoints=p.setpoints;r.setpointRefusal=new Condition.Active(Condition.SETPOINT_REFUSED,text);return true;}
        r.refused=new Refused(p.trigger,p.state,p.held,new Condition.Active(Condition.SOLVE_REFUSED,text));
        // A solve of a changed steam ratio was refused (zero steam without a reboiler, the draw wall): rule A, the inputs cut together.
        if(p.trigger==DriftBands.Trigger.STEAM_RATIO)r.ruleA=true;
        return true;
    }
    /** A product of {@code table} the unit has no outlet for, as a refusal, or null. */
    private static String missingStation(ColumnUnit u,SplitTable table) {
        for(var p:table.products())if(u.station(p.port(),p.index())==null)return Refusal.NO_NOZZLE.text(p.port().name().toLowerCase(Locale.ROOT));
        return null;
    }
    /**
     * Asks a solve at the cycle at {@code tick} for {@code trigger}, of the state {@code state} (null: the design's at the set
     * rate): one in flight per column, at most one per {@link #SOLVE_SPACING} except a setpoint edit or a start, none before
     * an unfinished solve's wait, none of a state inside the bands of a refused one. A SETPOINT or START solve takes the
     * unit's requested set points, every other the accepted ones with the unit's feed rate (W2).
     */
    private void request(Run r,ColumnUnit u,long tick,DriftBands.Trigger trigger,ColumnCycle.Step state) {
        if(r.pending!=null||tick<r.waitUntil)return;
        boolean urgent=trigger==DriftBands.Trigger.SETPOINT||trigger==DriftBands.Trigger.START;
        if(!urgent&&tick<r.lastSolve+SOLVE_SPACING)return;
        var design=solves.design(u);
        if(design==null){if(r.table==null)r.missing=new Condition.Active(Condition.SOLVE_REFUSED,Refusal.NO_OPERATING_POINT.text());return;}
        r.missing=null;
        ColumnSolve.Taken taken;
        if(state==null){taken=designTaken(u,design);state=state(taken);}else taken=taken(state,design);
        var setpoints=urgent?u.setpoints():running(u,r.table);
        double designDemand=u.setpoints().designFeedRate()*CYCLE_TICKS/20.0;
        if(trigger!=DriftBands.Trigger.SETPOINT&&r.refused!=null&&inside(r.refused,setpoints,state,designDemand))return;
        boolean[] held;if(r.table!=null)held=r.table.held();else{held=new boolean[weights.length];Arrays.fill(held,true);}
        var pending=new Pending(trigger,tick,setpoints,DriftBands.reference(state,designDemand,setpoints,r.table==null?0:r.table.reference().enthalpySlope()),held);
        var job=new ColumnSolve.Job(trigger,design,setpoints,taken,trigger==DriftBands.Trigger.START?null:r.seed,solves.condenser(u));
        // In flight before the submission: a solver may answer at once.
        r.pending=pending;
        if(!solves.submit(r.unit,job,outcome->done(r,pending,outcome))){r.pending=null;return;}
        r.lastSolve=tick;
    }
    /** Whether {@code state}, at set points {@code setpoints}, stands inside the bands of the refused state {@code refused}. */
    private static boolean inside(Refused refused,Setpoints setpoints,ColumnCycle.Step state,double designDemand) {
        return !refused.state().setpoints().intensivelyDiffers(setpoints)&&DriftBands.check(refused.state(),refused.held(),state,designDemand).isEmpty();
    }
    /** A state given as rates (the design's at a start) as one cycle's take, for the bands. */
    private ColumnCycle.Step state(ColumnSolve.Taken taken) {
        double seconds=CYCLE_TICKS/20.0;double[] feed=taken.feed();double total=0;
        for(int i=0;i<feed.length;i++){feed[i]*=seconds;total+=feed[i];}
        int water=weights.length-1;var steam=new ArrayList<Account>();
        for(var s:taken.steam()){var n=new double[weights.length];n[water]=Math.max(0,s.rate()*seconds);steam.add(new Account(n,Double.isFinite(s.enthalpy())?s.enthalpy()*n[water]:0));}
        return new ColumnCycle.Step(1,new Account(feed,Double.isFinite(taken.feedEnthalpy())?taken.feedEnthalpy()*total:0),steam,List.of(),0,new double[0],0,0,List.of());
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
    /** Whether every inlet island of {@code r} has booked through {@code tick} and every outlet's through the fence the cycle reads its room at (plan section 9). */
    private boolean portsThrough(Run r,long tick) {
        var u=topology.column(r.unit);
        for(var s:u.stations()) {
            if(s.device()==0)continue;
            Long owner=registry.owner(s.device());
            if(owner!=null&&!coordinator.through(owner,s.port().inlet()?tick:tick-FENCE_TICKS))return false;
        }
        // The utility sides (the condenser's water, W4: the reboiler's and the pumparound coolers'), through the fence the cycle reads their transit at.
        for(var utility:utilities(u))for(long side:sideList(u,utility)){Long owner=registry.owner(side);if(owner!=null&&!coordinator.through(owner,tick-FENCE_TICKS))return false;}
        return true;
    }

    /**
     * {@code rooms} with the members' power for the cycle at {@code tick} (W5, {@code MEMBER_POWER_NOTE.md}): each fan side
     * books its fans over the span that just ran (its last speed, 0 where it had no power), then says whether its store has
     * power; banks without hold their surface at natural draught. Each pump books its rated shaft power at the last cycle's
     * share; one without power stops the cycle (NO_POWER, share 0). With machines that need no power, or no members, as it was.
     */
    private ColumnCycle.Rooms powered(Run r,ColumnUnit u,SplitTable t,long tick,ColumnCycle.Rooms rooms) {
        if(!power.needed()||u.members().none()){r.unpoweredFans.clear();return rooms;}
        double last=Double.isNaN(r.fans)?0:r.fans;
        var unpowered=new HashSet<Long>();
        for(long side:u.members().fans())if(!power.fans(side,r.unpoweredFans.contains(side)?0:last,tick))unpowered.add(side);
        r.unpoweredFans.clear();r.unpoweredFans.addAll(unpowered);
        // Each pump books its rated shaft power at the last cycle's share; one whose driver gives none stops the cycle.
        double share=r.last==null||r.last.tripped()?0:r.last.alpha();
        var stopped=new ArrayList<Integer>();
        if(!pumps(u.members().reflux(),share,tick))stopped.add(ColumnUnit.Members.REFLUX_PUMP);
        for(int j=0;j<u.members().pumparounds().size();j++)if(!pumps(u.members().pumparound(j),share,tick))stopped.add(ColumnUnit.Members.pumparoundPump(j));
        double natural=Math.min(u.airSurface(),surface(u,unpowered));
        if(!(natural>0)&&stopped.isEmpty())return rooms;
        return rooms.withPower(natural>0?t.airFeed(u.airSurface()-natural,natural):rooms.airFeed(),natural>0,stopped.stream().mapToInt(Integer::intValue).toArray());
    }
    /** Whether every one of a member's pumps gives power, each booked at {@code share} until {@code tick}. */
    private boolean pumps(List<Long> movers,double share,long tick) {
        boolean all=true;for(long p:movers)all&=power.pump(p,share,tick);
        return all;
    }
    /** The bare tube of {@code u}'s fan sides in {@code sides}, m². */
    private double surface(ColumnUnit u,Set<Long> sides) {
        double out=0;for(long side:u.members().fans())if(sides.contains(side))out+=power.surface(side);
        return out;
    }

    /** One cycle at online tick {@code tick} (plan section 5). */
    private void cycle(Run r,long tick) {
        var u=topology.column(r.unit);var stations=new ArrayList<>(u.stations());
        // What each inlet had by the tick: its account less what arrived after it, an interval across the tick shared by time.
        var available=new HashMap<Long,Account>();var waiting=new HashMap<Long,SolidInventory>();
        for(var s:u.stations()) {
            if(!s.port().inlet()||s.device()==0)continue;
            var later=Account.empty(weights.length);var laterSolids=SolidInventory.EMPTY;var queue=r.spans.get(s.device());
            if(queue!=null) {
                while(!queue.isEmpty()&&queue.peekFirst().end()<=tick)queue.removeFirst();
                for(var span:queue){double share=span.start()>=tick?1:(span.end()-tick)/(double)(span.end()-span.start());later=later.plus(span.arrived().part(share));laterSolids=laterSolids.plus(span.solids().scale(share));}
            }
            available.put(s.device(),s.account().less(later));waiting.put(s.device(),laterSolids);
        }
        var totals=u.totals();
        if(r.table==null) {
            request(r,u,tick,DriftBands.Trigger.START,null);
            publish(r,List.of(),levels(r,u,u.setpoints(),available,tick,false));return;
        }
        // Old rebuildable state has no accepted probe snapshot. Rebuild on the ordinary engine deadline, keeping its table meanwhile.
        if(r.rebuildProbes)request(r,u,tick,DriftBands.Trigger.START,null);
        var t=r.table;var feedStation=station(stations,Port.FEED,0);
        var feedAvailable=feedStation==null?Account.empty(weights.length):available.get(feedStation.device());
        var steam=new ArrayList<Account>();
        for(int j=0;j<u.setpoints().steamPerFeed().length;j++){var s=station(stations,Port.STEAM,j);steam.add(s==null?Account.empty(weights.length):available.get(s.device()));}
        double[] room=new double[t.products().size()];
        double design=u.setpoints().designFeedRate()*BUFFER_SECONDS;
        for(int k=0;k<room.length;k++) {
            var p=t.products().get(k);var s=station(stations,p.port(),p.index());
            // Gifts through the fence only: a slow island's booking lands after the cycle or before it, the room is the same.
            room[k]=Math.max(0,design*t.perFeed(k)-s.account().total()-givenSince(r,s.device(),tick-FENCE_TICKS));
        }
        var energy=r.enthalpy;
        // The cycle runs on the accepted set points with the unit's feed rate (W2): a requested one waits for its solve.
        var set=running(u,t);double designDemand=u.setpoints().designFeedRate()*CYCLE_TICKS/20.0;
        // Each duty passes no more than its utility has room for (C6b, W4): the heat owed as of the fence against its hold. An
        // air condenser holds at most what its built banks take with their fans at full speed (U2): past it, the column is limited.
        var pas=new double[t.pumparoundPerFeed().length];for(int j=0;j<pas.length;j++)pas[j]=room(r,u,t,Utility.pumparound(j),tick);
        var rooms=powered(r,u,t,tick,new ColumnCycle.Rooms(room(r,u,t,Utility.CONDENSER,tick),room(r,u,t,Utility.REBOILER,tick),pas,t.airFeed(u.airSurface())));
        ColumnCycle.ProductEnergy priced=(k,n)->{double e=0;var h=energy[k];for(int i=0;i<n.length;i++)e+=n[i]*h[i];return e;};
        var b=ColumnCycle.step(t,set,CYCLE_TICKS/20.0,feedAvailable,steam,room,rooms,priced,false);
        // Rule A stays while the steam is short and its shortage inside the bands of the refused state; then rule B tries once more.
        if(r.ruleA&&(Condition.find(b.conditions(),Condition.STEAM_SHORT)==null||r.refused==null||!inside(r.refused,set,b,designDemand)))r.ruleA=false;
        var step=r.ruleA?ColumnCycle.step(t,set,CYCLE_TICKS/20.0,feedAvailable,steam,room,rooms,priced,true):b;
        r.last=step;
        // The fans at the feed the cycle took, and their power by the cube of their speed (HeatSides.fanPower). Banks without
        // power (W5) hold what natural draught holds; the powered ones the rest.
        double natural=Math.min(u.airSurface(),surface(u,r.unpoweredFans)),driven=u.airSurface()-natural,airTaken=step.feedMoles()/(CYCLE_TICKS/20.0);
        if(natural>0)airTaken=Math.max(0,airTaken-t.airFeed(0,natural));
        r.fans=natural>0&&!(driven>0)?(Double.isNaN(t.fans(u.airSurface(),airTaken))?Double.NaN:0):t.fans(driven,airTaken);
        r.fanPower=Double.isNaN(r.fans)?Double.NaN:com.wormzjl.icheme.science.equipment.HeatSides.fanPower(driven,r.fans);
        if(step.tripped()) {
            // The model has no steady state for this input (a capability limit): the inlets close, a solve of the state the
            // column was given may lift it. Where rule B's steam is short the solve is the steam's, whose refusal brings rule A;
            // else of the first band the state left, else the closure's.
            if(feedAvailable.total()>0) {
                var given=attempted(set,feedAvailable,steam);
                var trigger=Condition.find(step.conditions(),Condition.STEAM_SHORT)!=null?DriftBands.Trigger.STEAM_RATIO
                        :DriftBands.check(t.reference(),t.held(),given,designDemand).map(DriftBands.Crossing::trigger).orElse(DriftBands.Trigger.CLOSURE);
                request(r,u,tick,trigger,given);
            }
            publish(r,step.conditions(),levels(r,u,set,available,tick,false));return;
        }
        replace(stations,Port.FEED,0,a->a.less(step.feed()));
        for(int j=0;j<steam.size();j++){var taken=step.steam().get(j);replace(stations,Port.STEAM,j,a->a.less(taken));}
        for(var e:available.entrySet()){for(var s:stations)if(s.device()==e.getKey())available.put(e.getKey(),s.port()==Port.FEED?e.getValue().less(step.feed()):s.port()==Port.STEAM?e.getValue().less(step.steam().get(s.index())):e.getValue());}
        // Every solid that arrived by the tick goes to the bottoms (plan 5.2); a table with no bottoms leaves them at the inlet.
        var solids=SolidInventory.EMPTY;
        if(t.product(Port.BOTTOMS,0)>=0)for(int i=0;i<stations.size();i++){var s=stations.get(i);var later=waiting.get(s.device());if(later!=null&&!s.solids().empty()){solids=solids.plus(s.solids().less(later));stations.set(i,s.withSolids(later));}}
        for(int k=0;k<t.products().size();k++) {
            var p=t.products().get(k);var product=step.products().get(k);var moved=p.port()==Port.BOTTOMS&&p.index()==0?solids:SolidInventory.EMPTY;
            var s=station(stations,p.port(),p.index());replace(stations,p.port(),p.index(),a->a.plus(product));
            if(!moved.empty())stations.set(stations.indexOf(station(stations,p.port(),p.index())),station(stations,p.port(),p.index()).withSolids(s.solids().plus(moved)));
            if(product.total()>0||!moved.empty())r.credits.computeIfAbsent(s.device(),d->new ArrayDeque<>()).addLast(new Credit(tick,product,moved));
        }
        var in=step.feed();for(var a:step.steam())in=in.plus(a);
        var out=Account.empty(weights.length);for(var a:step.products())out=out.plus(a);
        double pumparounds=0;for(double q:step.pumparounds())pumparounds+=q;
        totals=new ColumnUnit.Totals(totals.taken().plus(in),totals.produced().plus(out),totals.reboiler()+step.reboiler(),totals.condenser()+step.condenser(),totals.pumparounds()+pumparounds);
        topology.column(u.withStations(true,stations).withTotals(totals));
        // The totals book every duty whole; each utility is owed its duty (W4), the condenser's water its share, the air the rest
        // (COOLER_MODEL_PLAN.md section 6). A duty with no utility side is the column's own, in the totals alone.
        owed(r,u,tick,Utility.CONDENSER,-step.condenser()*t.waterShare());
        owed(r,u,tick,Utility.REBOILER,-step.reboiler());
        for(int j=0;j<step.pumparounds().length;j++)owed(r,u,tick,Utility.pumparound(j),-step.pumparounds()[j]);
        var unmetered=levels(r,u,set,available,tick,true);
        // A refusal of set points the player has since moved from is forgotten: what now stands is solved once.
        if(r.refusedSetpoints!=null&&u.setpoints().intensivelyDiffers(r.refusedSetpoints)){r.refusedSetpoints=null;r.setpointRefusal=null;}
        // A new solve where the state taken left the table's bands, or the requested set points moved from the accepted ones
        // and were not refused (plan section 6, W2).
        DriftBands.Trigger trigger=r.refusedSetpoints==null&&u.setpoints().intensivelyDiffers(t.reference().setpoints())?DriftBands.Trigger.SETPOINT
                :DriftBands.check(t,step,designDemand).map(DriftBands.Crossing::trigger).orElse(null);
        if(trigger!=null&&step.feedMoles()>0)request(r,u,tick,trigger,step);
        publish(r,step.conditions(),unmetered);
    }
    /** The state a tripped cycle was given: the feed it holds at the set rate, the steam at its ratios (both of {@code set}). */
    private ColumnCycle.Step attempted(Setpoints set,Account feed,List<Account> steam) {
        double seconds=CYCLE_TICKS/20.0,demand=set.feedRate()*seconds;
        var feedTaken=feed.part(Math.min(1,demand/feed.total()));
        var steamTaken=new ArrayList<Account>();double[] ratios=set.steamPerFeed();
        for(int j=0;j<steam.size();j++){var s=steam.get(j);double want=ratios[j]*feedTaken.total();steamTaken.add(s.total()>0?s.part(Math.min(1,want/s.total())):s);}
        return new ColumnCycle.Step(1,feedTaken,steamTaken,List.of(),0,new double[0],0,0,List.of());
    }
    /** Each inlet's level after the cycle at {@code tick} and the rate the column then demands of it (none: {@code demanding} false);
     * every port's island wakes where the plans this cycle dates take effect, the tick plus the fence. */
    private Condition.Active levels(Run r,ColumnUnit u,Setpoints set,Map<Long,Account> available,long tick,boolean demanding) {
        Condition.Active unmetered=null;
        for(var s:u.stations()) {
            if(s.device()==0)continue;
            wakes.computeIfAbsent(tick+FENCE_TICKS,t->new TreeSet<>()).add(s.device());
            if(!s.port().inlet())continue;
            double rate=!demanding?0:s.port()==Port.FEED?set.feedRate():set.feedRate()*set.steamPerFeed()[s.index()];
            // An empty account has no composition yet: its valve meters the design's feed, or water for the steam (not the
            // port's own contents, which before the first arrival are whatever filled the pipe).
            double molarMass=molar(available.get(s.device()).moles());
            if(!(molarMass>0)){int water=axis.indexOf(SplitTable.WATER);molarMass=s.port()==Port.FEED?(r.table==null?0:molar(r.table.reference().feedFractions())):water<0?0:weights[water];}
            // Nothing to meter by (steam on an axis without water): the inlet demands nothing, and the status line says why.
            if(rate>0&&!(molarMass>0)){rate=0;unmetered=new Condition.Active(Condition.UNMETERED,0,s.index(),String.format(Locale.ROOT,"the %s inlet takes nothing: no molar mass to meter it by",s.port().name().toLowerCase(Locale.ROOT)));}
            r.levels.computeIfAbsent(s.device(),d->new ArrayDeque<>()).addLast(new Level(tick,available.get(s.device()).total(),rate,molarMass));
        }
        return unmetered;
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
        if(portUnits.isEmpty()&&utilitySides.isEmpty())return true;
        for(var node:graph.reservoirs()) {
            Long unit=portUnits.get(node.id());if(unit==null)continue;
            var r=runs.get(unit);if(r!=null&&r.nextCycle<=start-FENCE_TICKS)return false;
        }
        for(var p:graph.pipes()){var served=utilitySides.get(p.id());var r=served==null?null:runs.get(served.unit());if(r!=null&&r.nextCycle<=start-FENCE_TICKS)return false;}
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
            plans.put(node.id(),s.port().inlet()?inletPlan(r,s,node,slice):outletPlan(island,r,s,node,slice));
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
        if(level!=null&&level.rate()>0&&level.molarMass()>0) {
            double aim=level.rate()*INLET_AIM_SECONDS,gain=Math.max(0,Math.min(2,1+(aim-level.held())/aim));
            target=level.rate()*gain*level.molarMass();
        }
        return new PortPlan(node.id(),s.valve(),target,0,null);
    }
    /**
     * An outlet's generator and valve (plan 4.3, LC): the account's moles booked through the slice start less the fence, at
     * the product's solved temperature; the valve takes the production of the last cycle through the fence corrected by the
     * account's fill, and never more than those moles over the slice.
     */
    private PortPlan outletPlan(long island,Run r,ColumnUnit.Station s,PassiveNetwork.Reservoir node,IslandClock.Slice slice) {
        long fence=slice.startTick()-FENCE_TICKS;
        var available=s.account().moles();var solids=s.solids();var log=r==null?null:r.credits.get(s.device());
        var made=r==null?null:asOf(log,Credit::tick,fence);
        if(log!=null)for(var c:log)if(c.tick()>fence){var n=c.product().moles();for(int i=0;i<available.length;i++)available[i]=Math.max(0,available[i]-n[i]);solids=solids.less(c.solids());}
        double total=0;for(double n:available)total+=n;
        double temperature=node.state().temperature();
        var table=r==null?null:r.tableAt(fence);
        if(table!=null)for(var p:table.products())if(p.port()==s.port()&&p.index()==s.index())temperature=p.temperature();
        // The port gives on its island's own compound list (V2): a product compound the island does not carry yet first grows
        // the island (a topology event, queued on the engine's tick, grow), and the account holds the moles until then.
        var mine=total>0?onIsland(island,available):null;
        if(total>0&&mine==null)growth.add(s.device());
        if(!(total>0)||mine==null)return new PortPlan(node.id(),s.valve(),0,node.state().temperature(),com.wormzjl.icheme.science.fluid.solver.PhaseLayout.totalAmounts(node.state()));
        double mass=mass(available),cap=mass/slice.seconds()*TAKE_MARGIN,rate=cap;
        if(made!=null) {
            double produced=mass(made.product().moles())/(CYCLE_TICKS/20.0),aim=produced*BUFFER_SECONDS/2;
            if(aim>0)rate=produced*Math.max(0,Math.min(2,mass/aim));
        }
        return new PortPlan(node.id(),s.valve(),Math.min(cap,rate),temperature,mine,solids);
    }
    /** The ports whose islands must grow before they give their accounts (V2): queued edits, applied on the engine's tick. */
    private final Set<Long> growth=new LinkedHashSet<>();
    /**
     * Queues, through {@code submit}, an edit of every port whose account holds a compound its island does not carry (its
     * registration at its next revision, which recompiles its island on the engine's schedule with the compounds the port
     * delivers, {@link #compounds}); a port with an event already queued waits for it. Nothing when none is waiting.
     */
    public void grow(java.util.function.Consumer<List<WorldTopologyLedger.Edit>> submit) {
        if(growth.isEmpty())return;
        var waiting=List.copyOf(growth);growth.clear();
        for(long device:waiting) {
            var r=topology.latest().get(device);if(r==null||topology.pending(device))continue;
            submit.accept(List.of(new WorldTopologyLedger.Edit(device,new WorldTopologyLedger.Registration(r.device(),r.spec(),Math.addExact(r.revision(),1)))));
        }
    }
    /** The compounds outlet port {@code device} may deliver: those its account holds (water not named), for its island's list (V2). */
    public Set<String> compounds(long device) {
        Long unit=portUnits.get(device);if(unit==null)return Set.of();var u=topology.column(unit);if(u==null)return Set.of();
        var found=new HashSet<String>();
        for(var st:u.stations())if(st.device()==device&&!st.port().inlet()){var n=st.account().moles();for(int i=0;i<n.length;i++)if(n[i]>0&&!axis.get(i).equals(SplitTable.WATER))found.add(axis.get(i));}
        return found;
    }
    /** {@code moles} on the world list on island {@code island}'s own list, or null where it holds a compound the island does not carry. */
    private double[] onIsland(long island,double[] moles) {
        var own=coordinator.model(island).components();if(own.equals(axis))return moles;
        var result=new double[own.size()];
        for(int i=0;i<moles.length;i++){if(moles[i]==0)continue;int j=own.indexOf(axis.get(i));if(j<0)return null;result[j]=moles[i];}
        return result;
    }
    /** {@code moles} on island {@code island}'s own list on the world list (the accounts'). */
    private double[] onWorld(long island,double[] moles) {
        var own=coordinator.model(island).components();if(own.equals(axis))return moles;
        var result=new double[axis.size()];
        for(int i=0;i<moles.length;i++){if(moles[i]==0)continue;int j=axis.indexOf(own.get(i));if(j<0)throw new IllegalStateException("Island "+island+" carries "+own.get(i)+", which the world list does not");result[j]=moles[i];}
        return result;
    }
    private double mass(double[] moles){double m=0;for(int i=0;i<moles.length;i++)m+=moles[i]*weights[i];return m;}
    private double molar(double[] moles){double t=0;for(double n:moles)t+=n;return t>0?mass(moles)/t:0;}
    /** What the island's ports gave and took over {@code start} to {@code end} (plan 4.4). */
    @Override public void booked(long island,PassiveNetwork graph,long start,long end,PassiveIntervalSolver.Result result) {
        if(portUnits.isEmpty()||end<=start)return;
        Map<Long,double[]> moles=null;Map<Long,Double> energy=null;Map<Long,SolidInventory> solids=null;
        for(var b:result.boundaries()) {
            if(b.kind()!=ConservativeTransport.BoundaryTransfer.Kind.EXCHANGE||!portUnits.containsKey(b.nodeId()))continue;
            if(moles==null){moles=new HashMap<>();energy=new HashMap<>();solids=new HashMap<>();}
            var sum=moles.computeIfAbsent(b.nodeId(),id->new double[weights.length]);var n=onWorld(island,b.moles());for(int i=0;i<n.length;i++)sum[i]+=n[i];
            energy.merge(b.nodeId(),b.totalEnergyJoule(),Double::sum);solids.merge(b.nodeId(),b.solids(),SolidInventory::plus);
        }
        if(moles!=null)credit(moles,energy,solids,start,end);
        // A cycle waiting on this island's bookings may run now, whether or not anything moved.
        boolean waiting=false;for(var r:runs.values())waiting|=r.nextCycle<=coordinator.now();
        if(waiting)due();
    }
    /** Books what the ports gave and took over {@code start} to {@code end}: an inlet's arrivals as a span, an outlet's gift as a debit. */
    private void credit(Map<Long,double[]> moles,Map<Long,Double> energy,Map<Long,SolidInventory> solids,long start,long end) {
        for(var e:moles.entrySet()) {
            long device=e.getKey();long unit=portUnits.get(device);var u=topology.column(unit);var r=runs.get(unit);
            ColumnUnit.Station s=null;for(var st:u.stations())if(st.device()==device)s=st;
            if(s==null)continue;
            double joule=energy.get(device);var n=e.getValue();var solid=solids.get(device);
            if(s.port().inlet()) {
                // A void takes: its booking is what left the island, negative.
                var arrived=new double[n.length];for(int i=0;i<n.length;i++)arrived[i]=Math.max(0,-n[i]);
                var account=melted(arrived,-joule,solid);var inert=solid.withoutCrystals();
                // In the account at once (a fact), and a span the cycles take it by (state).
                topology.column(withPort(u,s,s.account().plus(account),s.solids().plus(inert)));
                if(r!=null)r.spans.computeIfAbsent(device,d->new ArrayDeque<>()).addLast(new Span(start,end,account,inert));
            } else {
                // A generator gives: debit exactly that, component by component; the energy by the same share of the account.
                var held=s.account().moles();double before=s.account().total(),given=0;
                for(int i=0;i<n.length;i++){double g=Math.max(0,n[i]);if(g>held[i]*(1+1e-9)+1e-12)com.wormzjl.icheme.IChemE.LOGGER.error("column status=OVERDRAWN unit={} port={} component={} held={} given={}",unit,s.port(),i,held[i],g);held[i]=Math.max(0,held[i]-g);given+=g;}
                double share=before>0?Math.min(1,given/before):0,debited=share*s.account().energy();
                var account=new Account(held,s.account().energy()-debited);
                var t=u.totals();u=withPort(u,s,account,s.solids().less(solid)).withTotals(new ColumnUnit.Totals(t.taken(),t.produced(),t.reboiler(),t.condenser()+(joule-debited),t.pumparounds()));
                topology.column(u);
                if(r!=null)r.gifts.computeIfAbsent(device,d->new ArrayDeque<>()).addLast(new Gift(start,end,given));
                if(r!=null&&water(r,s,start))owe(r,u,end,Utility.CONDENSER,-(joule-debited),true);
            }
        }
    }
    /**
     * Whether the condenser's cooling water takes outlet {@code s}'s booking difference over the slice from {@code start}: where
     * the port offered its product, at the temperature its plan gave (the table's as of the fence), below the table's
     * {@link SplitTable#waterBelow}; the air (the totals alone) otherwise. Before any table, the water.
     */
    private static boolean water(Run r,ColumnUnit.Station s,long start) {
        var table=r.tableAt(start-FENCE_TICKS);if(table==null)return true;
        int k=table.product(s.port(),s.index());
        return table.waterTakes(k<0?Double.NaN:table.products().get(k).temperature());
    }
    /** What an inlet took, {@code moles} and {@code joule}, with the crystals of {@code solids} melted into it: each its component, with a population's stored sensible energy made true by its offset (plan section 11, D61). */
    Account melted(double[] moles,double joule,SolidInventory solids) {
        double[] arrived=moles.clone();double energy=joule;
        for(var p:solids.crystals()){var crystal=p.material().crystal();int c=axis.indexOf(crystal.component());arrived[c]+=p.massKg()/weights[c];energy+=p.massKg()*crystal.energyOffset();}
        return new Account(arrived,energy);
    }
    /** What outlet {@code device} gave after {@code fence}, mol: a span across the fence by time; what is older is dropped. State: a reload starts it empty. */
    private double givenSince(Run r,long device,long fence) {
        var queue=r.gifts.get(device);if(queue==null)return 0;
        while(!queue.isEmpty()&&queue.peekFirst().end()<=fence)queue.removeFirst();
        double later=0;for(var g:queue)later+=g.start()>=fence?g.moles():g.moles()*(g.end()-fence)/(double)(g.end()-g.start());
        return later;
    }
    /**
     * The heat one side of {@code utility} was owed per second by the last cycle at or before {@code through}, W: read as of the
     * fence, so a plan does not depend on when its job runs. Before any such cycle (a start, a reload) the table's duty at the
     * set feed, shared by the sides.
     */
    private double rate(Run r,ColumnUnit u,Utility utility,long through) {
        Duty last=null;var debt=r.debts.get(utility);if(debt!=null)for(var d:debt.rates)if(d.tick()<=through)last=d;
        if(last!=null)return last.watts();
        int units=Math.max(1,sides(u,utility).size());
        return r.table==null?0:Math.max(0,utility.sign()*-perFeed(r.table,utility)*u.setpoints().feedRate())/units;
    }
    /** {@code utility}'s duty per mole of feed in {@code t}, J (column sign: into the column); the condenser's its water's share. */
    private static double perFeed(SplitTable t,Utility utility) {
        return switch(utility.kind()) {
            case CONDENSER->t.condenserWaterPerFeed();
            case REBOILER->t.reboilerPerFeed();
            case PUMPAROUND->utility.index()<t.pumparoundPerFeed().length?t.pumparoundPerFeed()[utility.index()]:0;
        };
    }
    /**
     * The temperature {@code utility}'s outlet stops at, K (C6b, W4): a cooling side's ceiling, the condensate's (the accepted
     * condenser temperature) or the draw tray's (as {@code t} solved it) less the approach, at least 1 K; the reboiler's floor,
     * the bottoms' plus the approach.
     */
    private double bound(ColumnUnit u,SplitTable t,Utility utility) {
        return switch(utility.kind()) {
            case CONDENSER->Math.max(1,condenserTemperature(u)-APPROACH_KELVIN);
            case REBOILER->t.bottomsTemperature()+APPROACH_KELVIN;
            case PUMPAROUND->Math.max(1,t.drawTemperature(utility.index())-APPROACH_KELVIN);
        };
    }
    /** The heat partner of the registered side {@code side}, or 0. */
    private long partnerOf(long side) {
        if(side==0)return 0;var r=registry.registrations().get(side);
        return r==null||r.device().heat()==null?0:r.device().heat().partner();
    }
    /** {@code u}'s utilities that may have sides: the condenser, the reboiler, each pumparound of its set points. */
    private static List<Utility> utilities(ColumnUnit u) {
        var out=new ArrayList<Utility>(List.of(Utility.CONDENSER,Utility.REBOILER));
        for(int j=0;j<u.utilities().pumparounds().size();j++)out.add(Utility.pumparound(j));
        return out;
    }
    /** {@code u}'s sides of {@code utility} as its facts name them. */
    private static List<Long> sideList(ColumnUnit u,Utility utility) {
        return switch(utility.kind()) {
            case CONDENSER->u.condensers();
            case REBOILER->u.utilities().reboiler();
            case PUMPAROUND->u.utilities().pumparound(utility.index());
        };
    }
    /** {@code u}'s sides of {@code utility} that have a heat partner: those its duty goes to. */
    private List<Long> sides(ColumnUnit u,Utility utility) {
        var out=new ArrayList<Long>();
        for(long side:sideList(u,utility))if(partnerOf(side)!=0)out.add(side);
        return out;
    }
    /**
     * The heat {@code utility} can still take (or give) at the cycle at {@code tick}, J (C6b, W4): its hold,
     * {@link #CONDENSER_HOLD_SECONDS} of its duty per feed at the design rate, less its units times the fullest unit's transit
     * as of the fence (identical units in parallel share the duty equally, S3b, so the fullest bounds the whole), what outlets
     * booked after the fence left out. Unbounded where it has no side: the duty is the column's own.
     */
    private double room(Run r,ColumnUnit u,SplitTable t,Utility utility,long tick) {
        var sides=sides(u,utility);
        if(sides.isEmpty())return Double.POSITIVE_INFINITY;
        var debt=r.debts.get(utility);double owed=0;
        for(long side:sides) {
            double held=coordinator.transitAsOf(PhysicalFluidTopology.sideIdentity(side),PhysicalFluidTopology.sideIdentity(partnerOf(side)),tick-FENCE_TICKS);
            if(debt!=null)for(var o:debt.owed)if(o.booked()&&o.tick()>tick-FENCE_TICKS)held-=o.joule();
            owed=Math.max(owed,utility.sign()*held);
        }
        // The water's hold is of its own share: an air-then-water condenser's trim takes part of the duty (COOLER_MODEL_PLAN.md section 6).
        return CONDENSER_HOLD_SECONDS*Math.abs(perFeed(t,utility))*u.setpoints().designFeedRate()-sides.size()*owed;
    }
    /** A cycle's {@code joule} owed to {@code utility} at {@code tick} (column sign reversed), and each side's heat rate for a valve the column sets (U2). */
    private void owed(Run r,ColumnUnit u,long tick,Utility utility,double joule) {
        owe(r,u,tick,utility,joule,false);
        var sides=sides(u,utility);if(sides.isEmpty())return;
        var rates=r.debt(utility).rates;
        rates.addLast(new Duty(tick,Math.max(0,utility.sign()*joule)/(CYCLE_TICKS/20.0)/sides.size()));
        while(rates.size()>1&&rates.peekFirst().tick()<tick-FENCE_TICKS-KEEP_TICKS)rates.removeFirst();
    }
    /**
     * Books {@code joule} (J) into the transit of {@code utility}'s sides at {@code tick}, an equal share into each (S3b:
     * identical units in parallel take equal shares, so each passes its share of the duty), and wakes each side where it
     * reads it. The record keeps one side's share.
     */
    private void owe(Run r,ColumnUnit u,long tick,Utility utility,double joule,boolean booked) {
        var sides=sides(u,utility);
        if(joule==0||sides.isEmpty())return;
        double share=joule/sides.size();
        for(long side:sides) {
            coordinator.owe(PhysicalFluidTopology.sideIdentity(side),PhysicalFluidTopology.sideIdentity(partnerOf(side)),share);
            wakes.computeIfAbsent(tick+FENCE_TICKS,k->new TreeSet<>()).add(side);
        }
        var owed=r.debt(utility).owed;
        owed.addLast(new Owed(tick,share,booked));
        while(owed.size()>1&&owed.peekFirst().tick()<tick-FENCE_TICKS-KEEP_TICKS)owed.removeFirst();
    }
    /**
     * A coupled side whose partner a running column holds (C6b, W4): a utility side reads the heat the column owes it as of
     * its slice start less the fence (the transit now, less what the column booked after that), its outlet stopped at its
     * bound: a cooling side's (the condenser's water, a pumparound cooler's) below the condensate or the draw tray by the
     * approach, the reboiler's heating above the bottoms by the approach. A side of a member the column holds otherwise moves
     * no heat (a member whose Start found no utility side, or a save before W4: its duty is the column's own).
     */
    @Override public com.wormzjl.icheme.science.fluid.network.ExchangerRelay.Read heatRead(long side,long partner,long start,double seconds,double transit) {
        var served=utilitySides.get(side);var r=served==null?null:runs.get(served.unit());
        var table=r==null?null:r.tableAt(start-FENCE_TICKS);
        if(r!=null&&(table!=null||served.utility().kind()==Utility.Kind.CONDENSER)) {
            var utility=served.utility();var debt=r.debts.get(utility);
            double due=transit;if(debt!=null)for(var o:debt.owed)if(o.tick()>start-FENCE_TICKS)due-=o.joule();
            var u=topology.column(served.unit());
            // The heat a valve the column sets carries (U2): the side's share of the last cycle's duty through the fence, and a
            // backlog beyond a cycle's lump and the slice over the hold.
            double rate=rate(r,u,utility,start-FENCE_TICKS),excess=Math.max(0,utility.sign()*due-rate*(seconds+CYCLE_TICKS/20.0));
            double bound=utility.kind()==Utility.Kind.CONDENSER?Math.max(1,condenserTemperature(u)-APPROACH_KELVIN):bound(u,table,utility);
            return utility.gives()?com.wormzjl.icheme.science.fluid.network.ExchangerRelay.Read.heating(due,seconds,bound,rate+excess/CONDENSER_HOLD_SECONDS)
                    :com.wormzjl.icheme.science.fluid.network.ExchangerRelay.Read.column(due,seconds,bound,rate+excess/CONDENSER_HOLD_SECONDS);
        }
        var held=registry.registrations().get(partner-PhysicalFluidTopology.sideIdentity(0));
        return held!=null&&held.device().suspended()?com.wormzjl.icheme.science.fluid.network.ExchangerRelay.Read.NONE:null;
    }
    private static ColumnUnit withPort(ColumnUnit u,ColumnUnit.Station s,Account account,SolidInventory solids) {
        var next=new ArrayList<ColumnUnit.Station>();for(var st:u.stations())next.add(st==s||st.port()==s.port()&&st.index()==s.index()?st.withAccount(account).withSolids(solids):st);
        return u.withStations(u.running(),next);
    }
}
