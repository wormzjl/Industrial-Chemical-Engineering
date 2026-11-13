package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.plant.column.ColumnCycle.Account;
import com.wormzjl.icheme.science.plant.still.BatchStill;
import com.wormzjl.icheme.science.fluid.network.Burners;
import com.wormzjl.icheme.science.fluid.network.ConservativeTransport;
import com.wormzjl.icheme.science.fluid.network.ExchangerRelay;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.network.PassiveIntervalSolver;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.network.Pool;
import com.wormzjl.icheme.science.fluid.network.PortPlan;
import com.wormzjl.icheme.science.plant.still.StillHead;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.plant.port.PortAccounts;
import java.util.*;
import java.util.function.LongFunction;

/**
 * The batch stills in the running world (batch 2026-11-09-batch-distiller, W3 to W5; BATCH_STILL_ENGINE.md): arithmetic on
 * the server thread on the island coordinator's port hooks; the column solves on the engine's worker.
 *
 * <ul>
 * <li><b>Pot.</b> A reservoir with a fired pool ({@link Pool#fired}): at each dispatch its heat, W, is the firing times the
 * rating times what the fuel or power delivers, 0 at OVERHEAT, at BOIL DRY and, with a column, held at the flood boil-up
 * (FLOODING). With the condenser built in the pot is open: what its surface releases goes up its head ({@link Pool.Head}).
 * Without it the pot is closed and its VAPOUR nozzle draws the boil-up.</li>
 * <li><b>Head.</b> The column's answer sets the head's recoveries; the liquid it returns falls back into the pot inside the
 * pot's own step. What the pot's vent and spill give the head (its VENT and SPILL boundaries) goes, at the pot's commit, into
 * the head's two accounts (facts): the distillate's (what the condenser condenses) and the vent's.</li>
 * <li><b>Outlets.</b> DISTILLATE and VENT are generators carrying the still's outlet valve ({@link FlowControl.ValveSource#STILL});
 * each slice gives what its account held as of {@link #FENCE_TICKS} before its start, over {@link #HOLD_CYCLES}, at the
 * temperature of the column's answer in force then. A gift's energy short of what its account carried is the condenser's
 * duty: owed to the water side, or to the air with none.</li>
 * <li><b>Condenser.</b> It condenses what its water takes. The water side reads as of its slice start less
 * {@link #FENCE_TICKS}, as the running column's does (codebase review R-ST1): the heat owed through then less what the water
 * took through its start, the pot's heat and the column's answer in force then; it waits for the pot's and the outlets'
 * islands through that tick, so its read does not depend on when their workers finished. While the heat due stays within
 * {@link #HOLD_CYCLES} of the pot's heat it condenses all; past that, the share the water took over the window before the
 * pot slice's own fence (COOLING LIMITED; the pot waits for its water through that tick), and the column solves at the
 * reflux that share leaves ({@link BatchStill#effectiveReflux}).</li>
 * <li><b>Column.</b> Solved every {@link #SOLVE_TICKS} of a boiling pot's commits, on the worker, warm from its last profile;
 * its answer applies at a fixed tick ({@link #APPLY_TICKS} after the commit that asked), so the pot's slices do not depend
 * on the worker's timing.</li>
 * <li><b>Fuel.</b> A solid still burns the heat left in its item (a fact) and takes the next item from its slot when that runs
 * low; a liquid one plans its burners' valve from its firing and books the fuel's heat; an electric one draws on its store.</li>
 * </ul>
 * Everything but the accounts, the fire's heat and the settings is state: a load starts each still at no column answer, and
 * a still that solves a column keeps its fire out until the first one arrives ({@link #waits}; code review finding 2b). A
 * data-network controller's setpoints ({@link Plans}, owner decision O1) override the settings while it runs; the settings
 * resume when it stops.
 */
public final class Stills implements IslandCoordinator.Ports {
    /** An outlet's slice reads its account as of this many ticks before its start: the pot's commits through then ({@link PortAccounts#FENCE_TICKS}). */
    public static final long FENCE_TICKS=PortAccounts.FENCE_TICKS;
    /** The column solves at most this often while the pot boils, ticks. */
    public static final long SOLVE_TICKS=100;
    /** A solve's answer applies this many ticks after the commit that asked for it. */
    public static final long APPLY_TICKS=100;
    /** The engine's cycle, ticks (5 s of play): the still's windows are counted in it ({@link PortAccounts#CYCLE_TICKS}). */
    public static final long CYCLE_TICKS=PortAccounts.CYCLE_TICKS;
    /**
     * An outlet gives its account over this many cycles, and the condenser keeps up while it owes at most this many cycles of
     * the pot's heat (10 s at factor 1); a solid still takes its next item while its fire holds less than {@link #REFILL_CYCLES}
     * cycles of its firing (30 s at 1). Counts of cycles (batch 2026-11-11-minecraft-time-delta, W3), so the still answers in
     * the same play time at any time factor.
     */
    public static final int HOLD_CYCLES=2,REFILL_CYCLES=6;
    /** The water's outlet stays this far under the condensate, K. */
    public static final double APPROACH_KELVIN=com.wormzjl.icheme.science.equipment.HeatSides.WATER_APPROACH;
    /** Below this, W, the pot is not heated. */
    static final double NO_HEAT=1;
    /** Water's latent heat at 1 atm, J/kg: the largest of any still's contents. */
    static final double LATENT_CEILING=2.257e6;
    /**
     * How long a new firing or reflux takes to show in the distillate's flow, ticks: the next column solve and its answer
     * ({@link #SOLVE_TICKS}, {@link #APPLY_TICKS}), the outlet's fence ({@link #FENCE_TICKS}) and its hold ({@link #HOLD_CYCLES}).
     */
    public static final long RESPONSE_TICKS=SOLVE_TICKS+APPLY_TICKS+FENCE_TICKS+HOLD_CYCLES*CYCLE_TICKS;
    /**
     * The fenced logs keep this many ticks beyond what a reader as of the fence needs, as the running column's do
     * ({@code ColumnOperation.KEEP_TICKS}): a reader that lags their writer by less than this reads exactly what it would
     * have read at once (the pot leads its water, and the water its pot, by at most a fence and a cadence; R-ST1).
     */
    static final long KEEP_TICKS=2400;
    /** {@link #HOLD_CYCLES} and {@link #REFILL_CYCLES} in process seconds under the coordinator's time factor. */
    private double holdSeconds(){return HOLD_CYCLES*coordinator.time().seconds(CYCLE_TICKS);}
    private double refillSeconds(){return REFILL_CYCLES*coordinator.time().seconds(CYCLE_TICKS);}

    /** A still's fuel: the heat (J) of one item taken from its slot, 0 when it is empty or its block is not loaded. */
    public interface Fuel {double take(long pot);}
    /** A still's column solve submitted to the worker: false when it was not accepted. */
    public interface Solves {boolean submit(long pot,long island,StillHead.Job job,java.util.function.Consumer<StillHead.Outcome> done);}
    /**
     * The data network's setpoints for a still's pot (owner decision O1): its firing (0 to 1) and its reflux ratio (+Infinity
     * closed), NaN where no controller sets one. State: the controllers' last cadence.
     */
    public interface Plans {double firing(long pot);double reflux(long pot);}
    private static final Plans NO_PLANS=new Plans(){public double firing(long pot){return Double.NaN;}public double reflux(long pot){return Double.NaN;}};

    /**
     * A still as its page and the data network show it (cached, no solve): the pot's temperature (K), its level (m) and its
     * liquid's share of its volume, the head vapour's temperature (K, NaN for none), the heat into the pot (W), the distillate
     * and vent flows (kg/s), the condenser's share, the reflux ratio the column runs at, its status and its alarms.
     */
    public record View(double potTemperature,double level,double filled,double headTemperature,double heat,double distillateFlow,double ventFlow,
                       double condensed,double reflux,BatchStill.Status status,List<EquipmentAlarm> alarms) {}

    private record Arrival(long tick,double[] distillate,double[] vent) {}
    /** A pot slice's heat, W, and the pot's temperature at its dispatch, K, booked at its commit: the slice from {@code start}. */
    private record Heat(long start,double watts,double temperature) {}
    /** No pot slice before the fence (a load, a new still): no heat, the temperature unknown. */
    private static final Heat UNKNOWN=new Heat(Long.MIN_VALUE,0,Double.NaN);
    /** A water side's read at its slice start {@code tick}: the heat due (J), its inlet's temperature (K, NaN for none) and the heat the water had taken by then (J). */
    private record Read(long tick,double due,double inlet,double taken) {}
    /** A column answer and the tick it applies from. */
    private record Answer(long tick,StillHead.Solved solved) {}
    /** The condenser's share from the pot's commit at {@code tick}. */
    private record Share(long tick,double value) {}
    /** Where an owe to the water comes from: its order within one tick, so its sum does not depend on the commits' order. */
    private static final int FROM_POT=0,FROM_DISTILLATE=1,FROM_VENT=2;

    /**
     * A still's engine state (memory only: a load starts a new one). The fenced logs (codebase review R-ST1) date what a
     * read as of a fence may need: the owes to the water by the end of the span that owed them, the pot's heat by its
     * slice's start, the water's reads by theirs, the column's answers by the tick they apply from, the condenser's share
     * by the commit that set it.
     */
    private static final class Run {
        StillHead.Solved solved,next;long nextTick=Long.MAX_VALUE,applyTick=Long.MAX_VALUE;boolean pending;long lastSubmit=Long.MIN_VALUE/2;
        double condensed=1,heat,boiling,fired,perKilogram,fPerWatt,spout;String failure;
        /** The fire is set and nothing cuts it (OVERHEAT, BOIL DRY, NO FUEL): the column is asked, lit or waiting. */
        boolean asked;
        long potIsland=-1;final ArrayDeque<Arrival> arrivals=new ArrayDeque<>();
        double potTemperature=Double.NaN,level,filled,distillateFlow,ventFlow;
        int alarms;BatchStill.Status status=BatchStill.Status.IDLE;
        /** By tick: the heat owed to the water (J) from the pot, the distillate and the vent, in that order. */
        final TreeMap<Long,double[]> owes=new TreeMap<>();
        final ArrayDeque<Heat> heats=new ArrayDeque<>();
        final ArrayDeque<Read> reads=new ArrayDeque<>();
        final ArrayDeque<Answer> answers=new ArrayDeque<>();
        final ArrayDeque<Share> shares=new ArrayDeque<>();
        /** The heat the water took, J: in all, and since its last read; the heat due at that read and its fence (none yet: MIN_VALUE). */
        double taken,since,settled;long settledFence=Long.MIN_VALUE;
    }

    private final WorldTopologyLedger topology;
    private final LongFunction<Long> owner;
    private final IslandCoordinator coordinator;
    /** By island: its own model (its compound list). */
    private final LongFunction<com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics> models;
    private final ElectricStores electric;
    private final List<String> axis;
    private final double[] weights,heatingValues;
    private Fuel fuel=pot->0;
    private Solves solves=(pot,island,job,done)->false;
    private Plans plans=NO_PLANS;
    private final Map<Long,Run> runs=new HashMap<>();
    /** By an outlet's, a water side's connection's and a burner's identity: the still's pot. */
    private final Map<Long,Long> outlets=new HashMap<>(),waters=new HashMap<>(),burners=new HashMap<>();
    /** By tick: the devices whose islands wake there (a certified island ends its certificate at the decision point). */
    private final TreeMap<Long,Set<Long>> wakes=new TreeMap<>();
    private long synced=-1;

    public Stills(WorldTopologyLedger topology,LongFunction<Long> owner,IslandCoordinator coordinator,LongFunction<com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics> models,ElectricStores electric,List<String> axis,double[] weights,double[] heatingValues) {
        this.topology=Objects.requireNonNull(topology);this.owner=Objects.requireNonNull(owner);this.coordinator=Objects.requireNonNull(coordinator);this.models=Objects.requireNonNull(models);
        this.electric=Objects.requireNonNull(electric);this.axis=List.copyOf(axis);this.weights=weights.clone();this.heatingValues=heatingValues.clone();
        if(weights.length!=axis.size()||heatingValues.length!=axis.size())throw new IllegalArgumentException("Still basis mismatch");
    }
    public void fuel(Fuel value){fuel=Objects.requireNonNull(value);}
    public void solves(Solves value){solves=Objects.requireNonNull(value);}
    public void plans(Plans value){plans=Objects.requireNonNull(value);}
    /** The firing still {@code u} runs at: its controller's, else its own setting. */
    private double firing(StillUnit u){double p=plans.firing(u.id());return Double.isNaN(p)?u.firing():p;}
    /** The reflux ratio still {@code u}'s valve is set to (+Infinity closed): its controller's, else its own setting. */
    /** The reflux ratio at the still's valve; 0 for a still without one ({@link BatchStill.Design#refluxes}). */
    private double reflux(StillUnit u){if(!u.design().refluxes())return 0;double p=plans.reflux(u.id());return Double.isNaN(p)?u.closedValve()?Double.POSITIVE_INFINITY:u.reflux():p;}

    /** Rebuilds the lookups from the ledger's stills; cheap when nothing changed. */
    public void refresh() {
        var stills=topology.stills();
        outlets.clear();waters.clear();burners.clear();
        for(var u:stills.values()) {
            if(u.distillate()>0)outlets.put(u.distillate(),u.id());
            if(u.vent()>0)outlets.put(u.vent(),u.id());
            if(u.water()>0)waters.put(PhysicalFluidTopology.sideIdentity(u.water()),u.id());
            if(u.burners()>0)burners.put(u.burners(),u.id());
            runs.computeIfAbsent(u.id(),id->new Run());
        }
        runs.keySet().retainAll(stills.keySet());
        synced=stills.size();
    }
    private void sync(){if(synced!=topology.stills().size())refresh();}

    // ---------------- dispatch ----------------

    /** The pot's heat and head and the burners' fuel valve for the slice (reads only). */
    @Override public PassiveNetwork dispatch(long island,PassiveNetwork graph,IslandClock.Slice slice) {
        sync();if(runs.isEmpty())return graph;
        List<PassiveNetwork.Reservoir> nodes=null;List<PassiveNetwork.Pipe> pipes=null;
        for(int i=0;i<graph.reservoirs().size();i++) {
            var node=graph.reservoirs().get(i);var pool=node.inventory().pool();
            if(pool==null||!pool.fired())continue;
            var u=topology.still(node.id());var r=runs.get(node.id());if(u==null||r==null)continue;
            r.potIsland=island;
            promote(r,slice.startTick());
            double heat=heat(u,r,node,slice.seconds(coordinator.time()));
            var head=pool.head()==null?null:head(u,r,node,island,pool.head().components());
            var planned=pool.withFiring(heat).withHead(head);
            if(planned.equals(pool))continue;
            if(nodes==null)nodes=new ArrayList<>(graph.reservoirs());
            nodes.set(i,new PassiveNetwork.Reservoir(node.id(),node.elevation(),node.state(),node.kind(),node.inventory().withPool(planned)));
        }
        for(int e=0;e<graph.pipes().size();e++) {
            var pipe=graph.pipes().get(e);
            if(!(pipe.control() instanceof FlowControl.ControlValve valve)||valve.source()!=FlowControl.ValveSource.STILL)continue;
            Long pot=burners.get(graph.reservoirs().get(pipe.second()).id());if(pot==null)continue;
            var u=topology.still(pot);var r=runs.get(pot);if(u==null||r==null)continue;
            double target=fuelTarget(u,r);
            if(Math.abs(target-valve.massTarget())<=com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.VALVE_DEADBAND*Math.max(target,valve.massTarget())+1e-9&&(target>0)==(valve.massTarget()>0))continue;
            if(pipes==null)pipes=new ArrayList<>(graph.pipes());
            pipes.set(e,pipe.withControl(valve.withMassTarget(target)));
        }
        return nodes==null&&pipes==null?graph:new PassiveNetwork(nodes==null?graph.reservoirs():nodes,pipes==null?graph.pipes():pipes,graph.scheduledTransfers());
    }
    /**
     * Whether still {@code u}'s fire waits for its column's first answer (code review finding 2b): a still that solves a
     * column (a condenser or trays) and has none yet. Its head would send all to the vent, so nothing is boiled up until then.
     */
    private static boolean waits(StillUnit u,Run r){return (u.condenser()||u.stages()>0)&&r.solved==null;}
    /** The heat into the pot for the slice, W, and its alarms; 0 while the fire {@link #waits}. */
    private double heat(StillUnit u,Run r,PassiveNetwork.Reservoir node,double seconds) {
        var design=u.design();double asked=firing(u)*design.rating();
        var s=node.state();int alarms=0;
        double liquid=s.condensedVolume(),volume=node.inventory().volume();
        r.potTemperature=s.temperature();r.filled=volume>0?liquid/volume:0;r.level=r.filled*node.inventory().levelHeight();
        double available=switch(u.heater()) {
            case SOLID->asked>0?Math.min(1,u.burning()*u.heater().efficiency()/(asked*seconds)):0;
            case LIQUID->asked>0?Math.min(1,r.fired*u.heater().efficiency()/asked):0;
            case ELECTRIC->{if(!electric.powered(u.id()))electric.restart(u.id());yield electric.powered(u.id())?1:0;}
        };
        // A liquid still's burners light on the fuel's arrival: until they have fired once, the pot waits for it. A waiting
        // fire asks no fuel yet (fuelTarget), so its burners' fuel is not missing.
        boolean waits=waits(u,r);
        if(asked>0&&!(available>0)&&!(waits&&u.heater()==BatchStill.Heater.LIQUID))alarms|=u.heater()==BatchStill.Heater.ELECTRIC?0:BatchStill.Status.NO_FUEL.bit();
        if(s.temperature()>=BatchStill.OVERHEAT)alarms|=BatchStill.Status.OVERHEAT.bit();
        if(liquid<=BatchStill.DRY_SHARE*volume)alarms|=BatchStill.Status.BOIL_DRY.bit();
        int cut=BatchStill.Status.OVERHEAT.bit()|BatchStill.Status.BOIL_DRY.bit();
        r.asked=asked>=NO_HEAT&&(alarms&(cut|BatchStill.Status.NO_FUEL.bit()))==0;
        double heat=waits||(alarms&cut)!=0?0:asked*available;
        // A column floods past its F-factor: the firing is held at the flood boil-up.
        if(design.stages()>0&&r.fPerWatt>0&&heat*r.fPerWatt>BatchStill.FLOOD_F_FACTOR){heat=BatchStill.FLOOD_F_FACTOR/r.fPerWatt;alarms|=BatchStill.Status.FLOODING.bit();}
        if(u.condenser()&&r.condensed<1)alarms|=BatchStill.Status.COOLING_LIMITED.bit();
        r.alarms=alarms;r.heat=heat>=NO_HEAT?heat:0;
        return r.heat;
    }
    /**
     * The pot's head for the slice: the column's answer on the island's list, all up without a column. While the fire
     * {@link #waits} for the first answer, the condenser returns all the gas the pot's vent releases (its headspace: no heat
     * boils it up) at the gas's own molar enthalpy, so no mole reaches the head's accounts before the answer divides them.
     */
    private Pool.Head head(StillUnit u,Run r,PassiveNetwork.Reservoir node,long island,int components) {
        if(waits(u,r))return returned(node,island,components);
        if(u.stages()==0||r.solved==null||r.solved.head().components()!=components)return Pool.Head.plain(components);
        return r.solved.head();
    }
    /** A head that returns all the pot's released gas at the gas's molar enthalpy (cached phase values; 0 with no gas, which releases none). */
    private Pool.Head returned(PassiveNetwork.Reservoir node,long island,int components) {
        var s=node.state();double molar=0;
        if(FluidThermodynamics.holdsPhase(s,FluidThermodynamics.GAS)) {
            var model=models.apply(island);double moles=0;
            for(double v:FluidThermodynamics.phaseMoles(s,FluidThermodynamics.GAS))moles+=v;
            if(moles>0)molar=model.phaseMass(s,FluidThermodynamics.GAS)*model.phaseSpecificEnthalpy(s,FluidThermodynamics.GAS,null)/moles;
        }
        return new Pool.Head(new double[components],Double.isFinite(molar)?molar:0);
    }
    /** A liquid still's burners' fuel, kg/s: its firing's fuel over the fuel's heat with {@link Burners#MARGIN}, 0 when cut or while the fire {@link #waits}. */
    private double fuelTarget(StillUnit u,Run r) {
        if(u.heater()!=BatchStill.Heater.LIQUID||!(firing(u)>0)||waits(u,r))return 0;
        if((r.alarms&(BatchStill.Status.OVERHEAT.bit()|BatchStill.Status.BOIL_DRY.bit()))!=0)return 0;
        double fired=u.heater().drawn(firing(u)*u.design().rating());
        // Until fuel has arrived its heat is unknown: methane's, 50 MJ/kg, as the fired heaters ask.
        double perKilogram=r.perKilogram>0?r.perKilogram:5e7;
        return fired*Burners.MARGIN/perKilogram;
    }

    // ---------------- the ports ----------------

    /**
     * The fence's waits (R-ST1 item 3), each on another island through this slice's start less {@link #FENCE_TICKS}, so what
     * a read as of the fence takes is booked on any schedule: a pot waits for its column answer due by its start and for its
     * water side's island (the reads its condenser's share and its job's outlet take); an outlet for its pot's island and the
     * answer due by the fence; a water side for its pot's and both outlets' islands and that answer. Each waits behind the
     * other by a fence, so no two of them block each other.
     */
    @Override public boolean ready(long island,PassiveNetwork graph,long start) {
        sync();if(runs.isEmpty())return true;
        long fence=start-FENCE_TICKS;
        for(var node:graph.reservoirs()) {
            var r=runs.get(node.id());
            if(r!=null) {
                // A pot waits for a column answer due at or before its slice's start.
                if(r.pending&&r.applyTick<=start)return false;
                var u=topology.still(node.id());
                if(u!=null&&u.condenser()&&u.water()>0&&!through(island,u.water(),fence))return false;
            }
            Long pot=outlets.get(node.id());
            if(pot!=null&&!fenced(island,pot,fence,false))return false;
        }
        if(!waters.isEmpty())for(var pipe:graph.pipes()){Long pot=waters.get(pipe.id());if(pot!=null&&!fenced(island,pot,fence,true))return false;}
        return true;
    }
    /** Whether device {@code device}'s island has booked through {@code tick}: true for {@code island} itself and for a device in no island. */
    private boolean through(long island,long device,long tick){Long other=owner.apply(device);return other==null||other==island||coordinator.through(other,tick);}
    /**
     * Whether still {@code pot}'s values as of {@code fence} are final for a slice of {@code island}: the column answer due by
     * then arrived and the pot's island booked through it; for its water side ({@code water}) its outlets' islands too.
     */
    private boolean fenced(long island,long pot,long fence,boolean water) {
        var p=runs.get(pot);if(p==null)return true;
        if(p.pending&&p.applyTick<=fence||!through(island,pot,fence))return false;
        var u=water?topology.still(pot):null;
        return u==null||(u.distillate()<=0||through(island,u.distillate(),fence))&&(u.vent()<=0||through(island,u.vent(),fence));
    }
    @Override public Map<Long,PortPlan> plans(long island,PassiveNetwork graph,IslandClock.Slice slice) {
        sync();if(outlets.isEmpty())return Map.of();
        Map<Long,PortPlan> plans=null;
        for(var node:graph.reservoirs()) {
            Long pot=outlets.get(node.id());if(pot==null)continue;
            var u=topology.still(pot);var r=runs.get(pot);if(u==null||r==null)continue;
            if(plans==null)plans=new HashMap<>();
            plans.put(node.id(),outletPlan(island,u,r,node,slice,node.id()==u.distillate()));
        }
        return plans==null?Map.of():plans;
    }
    /**
     * An outlet's generator and valve: its account as of the fence, at the condensate's or the head's temperature of the
     * column's answer and the condenser's share in force then (R-ST1 item 5), over {@link #HOLD_CYCLES}.
     */
    private PortPlan outletPlan(long island,StillUnit u,Run r,PassiveNetwork.Reservoir node,IslandClock.Slice slice,boolean distillate) {
        long fence=slice.startTick()-FENCE_TICKS;
        var available=(distillate?u.head():u.venting()).moles();
        for(var a:r.arrivals)if(a.tick()>fence)PortAccounts.less(available,distillate?a.distillate():a.vent());
        double total=0;for(double n:available)total+=n;
        var mine=total>0?onIsland(island,available):null;
        if(!(total>0)||mine==null)return PortAccounts.idle(node,node.id());
        // The distillate leaves at the condenser's outlet; the vent at the outlet too where the condenser took all (a partial
        // condenser's gas), else at the head (COOLING LIMITED: the vapour it could not take).
        var solved=solvedAt(r,fence);
        double temperature=solved==null?heatAt(r,fence).temperature():distillate||condensedAt(r,fence)>=1?solved.condensateTemperature():solved.headTemperature();
        if(!(temperature>0))temperature=node.state().temperature();
        double mass=mass(available),target=mass/Math.max(slice.seconds(coordinator.time()),holdSeconds());
        return new PortPlan(node.id(),node.id(),target,temperature,mine);
    }
    /** The compounds outlet {@code device} may deliver, for its island's list: those its account holds, and Water whatever it
     * holds now (W3b option A: a still's outlet can deliver water, so its island is wet from the start). */
    public Set<String> compounds(long device) {
        sync();Long pot=outlets.get(device);var u=pot==null?null:topology.still(pot);if(u==null)return Set.of();
        var n=(device==u.distillate()?u.head():u.venting()).moles();var out=new TreeSet<String>();
        for(int i=0;i<n.length;i++)if(n[i]>0||i==n.length-1)out.add(axis.get(i));
        return out;
    }

    @Override public void booked(long island,PassiveNetwork graph,long start,long end,PassiveIntervalSolver.Result result) {
        sync();if(runs.isEmpty()||end<=start)return;
        double seconds=coordinator.time().seconds(end-start);
        Map<Long,double[]> arrived=null;Map<Long,Double> arrivedEnergy=null,potHeat=null,given=null,took=null;Map<Long,double[]> gifts=null;
        for(var b:result.boundaries()) {
            var kind=b.kind();
            if((kind==ConservativeTransport.BoundaryTransfer.Kind.VENT||kind==ConservativeTransport.BoundaryTransfer.Kind.SPILL)&&runs.containsKey(b.nodeId())) {
                if(arrived==null){arrived=new HashMap<>();arrivedEnergy=new HashMap<>();}
                var sum=arrived.computeIfAbsent(b.nodeId(),id->new double[axis.size()]);var n=onWorld(island,b.moles());
                for(int i=0;i<n.length;i++)sum[i]+=Math.max(0,-n[i]);
                arrivedEnergy.merge(b.nodeId(),-b.totalEnergyJoule(),Double::sum);
            } else if(kind==ConservativeTransport.BoundaryTransfer.Kind.HEAT&&b.nodeId()<0) {
                long pot=b.nodeId()-PhysicalFluidTopology.sideIdentity(0);
                if(runs.containsKey(pot)){if(potHeat==null)potHeat=new HashMap<>();potHeat.merge(pot,b.totalEnergyJoule(),Double::sum);}
                // A condenser's water side: the heat it took, which leaves the transit (the coordinator books the same sum).
                else{Long still=waters.get(b.nodeId());if(still!=null){if(took==null)took=new HashMap<>();took.merge(still,b.totalEnergyJoule(),Double::sum);}}
            } else if(kind==ConservativeTransport.BoundaryTransfer.Kind.EXCHANGE&&outlets.containsKey(b.nodeId())) {
                if(gifts==null){gifts=new HashMap<>();given=new HashMap<>();}
                var sum=gifts.computeIfAbsent(b.nodeId(),id->new double[axis.size()]);var n=onWorld(island,b.moles());
                for(int i=0;i<n.length;i++)sum[i]+=Math.max(0,n[i]);
                given.merge(b.nodeId(),b.totalEnergyJoule(),Double::sum);
            }
        }
        // The pots: their heat (the fire, the burners or the power) and what reached their heads.
        var flows=result.averageMassFlows();
        for(int k=0;k<result.graph().reservoirs().size();k++) {
            var node=result.graph().reservoirs().get(k);
            var r=runs.get(node.id());var u=topology.still(node.id());if(r==null||u==null)continue;var original=u;
            r.potIsland=island;
            double joule=potHeat==null?0:potHeat.getOrDefault(node.id(),0.);
            u=burn(u,joule,seconds,end);
            var n=arrived==null?null:arrived.get(node.id());double e=arrivedEnergy==null?0:arrivedEnergy.getOrDefault(node.id(),0.);
            r.spout=0;
            if(n!=null||e!=0)u=arrive(u,r,n==null?new double[axis.size()]:n,e,end);
            if(joule!=0)u=u.withTotals(u.heated()+joule,u.condensed());
            if(u!=original)topology.still(u);
            // A closed pot's vapour leaves by its VAPOUR nozzle: what its lines carry out, kg/s.
            if(!u.condenser()){double out=0;for(int pipe=0;pipe<result.graph().pipes().size();pipe++){var p=result.graph().pipes().get(pipe);if(p.first()==k)out+=flows[pipe];if(p.second()==k)out-=flows[pipe];}r.ventFlow=Math.max(0,out);}
            status(u,r,node,n,seconds);
            // The slice's heat as its dispatch set it, dated by its start: what a read as of a later fence takes (R-ST1 item 2).
            r.heats.addLast(new Heat(start,r.heat,r.potTemperature));
            keepAsOf(r.heats,Heat::start,start-2*FENCE_TICKS-KEEP_TICKS);
            submit(u,r,island,node,start,end);
        }
        // The water sides: what each took, for the next read's heat due and the condenser's share.
        if(took!=null)for(var t:took.entrySet()){var r=runs.get(t.getKey());if(r!=null){r.taken+=t.getValue();r.since+=t.getValue();}}
        // The outlets: what each gave, and the condenser's duty it leaves.
        if(gifts!=null)for(var g:gifts.entrySet())give(g.getKey(),g.getValue(),given.get(g.getKey()),seconds,end);
        // The burners: the fuel's heat fired.
        var average=flows;
        for(int e=0;e<result.graph().pipes().size();e++) {
            var pipe=result.graph().pipes().get(e);
            if(!(pipe.control() instanceof FlowControl.ControlValve valve)||valve.source()!=FlowControl.ValveSource.STILL)continue;
            Long pot=burners.get(result.graph().reservoirs().get(pipe.second()).id());var r=pot==null?null:runs.get(pot);if(r==null)continue;
            var donor=result.graph().reservoirs().get(pipe.first());
            double flow=Math.max(0,average[e]),perKilogram=Burners.perKilogram(onWorld(island,donor.inventory().moles()),weights,heatingValues);
            double before=r.fired;r.fired=flow*perKilogram;if(perKilogram>0)r.perKilogram=perKilogram;
            if((before>0)!=(r.fired>0)&&r.potIsland>=0)coordinator.controlsMoved(List.of(r.potIsland));
        }
    }
    /** A still's fire over a committed span: a solid one burns its item's heat and takes the next one; an electric one draws. */
    private StillUnit burn(StillUnit u,double joule,double seconds,long end) {
        double drawn=u.heater().drawn(joule);
        switch(u.heater()) {
            case SOLID->{
                double burning=Math.max(0,u.burning()-drawn);
                double want=u.heater().drawn(firing(u)*u.design().rating())*refillSeconds();
                for(int item=0;item<64&&burning<want;item++){double next=fuel.take(u.id());if(!(next>0))break;burning+=next;}
                if(burning!=u.burning())u=u.withBurning(burning);
            }
            case ELECTRIC->electric.draw(u.id(),seconds>0?drawn/seconds:0,end);
            case LIQUID->{}
        }
        return u;
    }
    /** What reached the head over a committed span: into the accounts, divided by the condenser; energy alone is the condenser's. */
    private StillUnit arrive(StillUnit u,Run r,double[] moles,double energy,long end) {
        double liquid=StillHead.liquidShare(reflux(u),r.condensed);
        // Each component's liquid share at the condenser's outlet, the column's answer; before its first one none condenses.
        var division=StillHead.divide(moles,energy,shares(r),liquid,u.condenser());
        for(double d:division.distillate())r.spout+=d;
        double total=0;for(double n:moles)total+=n;
        if(!(total>0)) {
            // Energy with no moles: a closed column's reflux, condensed and returned; owed now (the water reads it a fence later), or the air's.
            return owe(u,r,energy,end,FROM_POT);
        }
        r.arrivals.addLast(new Arrival(end,division.distillate(),division.vent()));
        // Kept for a fence and the keep window, as the column's (F6): an outlet's read lagging the pot keeps what it subtracts.
        PortAccounts.keep(r.arrivals,Arrival::tick,end-FENCE_TICKS-KEEP_TICKS);
        var head=new Account(plus(u.head().moles(),division.distillate()),u.head().energy()+division.distillateEnergy());
        var vent=new Account(plus(u.venting().moles(),division.vent()),u.venting().energy()+division.ventEnergy());
        return u.withAccounts(head,vent);
    }
    /** What outlet {@code device} gave over a span: debited from its account, the energy short of the account's share owed. */
    private void give(long device,double[] moles,double joule,double seconds,long end) {
        Long pot=outlets.get(device);var u=pot==null?null:topology.still(pot);var r=pot==null?null:runs.get(pot);if(u==null||r==null)return;
        boolean distillate=device==u.distillate();var account=distillate?u.head():u.venting();
        // The gifts were summed of non-negative entries (booked), so the debit's clamp at 0 leaves them as they are.
        var debit=PortAccounts.debit(account,moles,(i,held,given)->com.wormzjl.icheme.IChemE.LOGGER.error("still status=OVERDRAWN pot={} outlet={} component={} held={} given={}",pot,device,i,held,given));
        double debited=debit.energy();var next=debit.account();
        u=distillate?u.withAccounts(next,u.venting()):u.withAccounts(u.head(),next);
        if(distillate)r.distillateFlow=mass(moles)/seconds;else r.ventFlow=mass(moles)/seconds;
        topology.still(owe(u,r,debited-joule,end,distillate?FROM_DISTILLATE:FROM_VENT));
    }
    /**
     * {@code joule} of condenser duty from {@code source}: owed to the water side, or with none given to the air; the still's
     * total either way. The owe is dated by the end of the span that owed it (R-ST1 item 1): the water's slices from that
     * tick plus {@link #FENCE_TICKS} read it, so a certified water island wakes there, not when this commit landed.
     */
    private StillUnit owe(StillUnit u,Run r,double joule,long end,int source) {
        if(joule==0)return u;
        var water=u.water()>0?topology.active().get(u.water()):null;
        if(water!=null&&water.device().heat()!=null) {
            coordinator.owe(PhysicalFluidTopology.sideIdentity(u.water()),PhysicalFluidTopology.sideIdentity(u.id()),joule);
            r.owes.computeIfAbsent(end,tick->new double[3])[source]+=joule;
            // The water's reads drop what they no longer subtract (due); this bounds the log while the water stops reading.
            r.owes.headMap(end-FENCE_TICKS-KEEP_TICKS).clear();
            wakeAt(end+FENCE_TICKS,u.water());
        }
        return u.withTotals(u.heated(),u.condensed()+joule);
    }
    /**
     * The condenser's share for the next solve, at the pot's commit of the slice from {@code start} (R-ST1 item 4): from the
     * water's reads before the slice's fence ({@code start} less {@link #FENCE_TICKS}), which the pot waited for, and the
     * pot's heat as of that fence. All while the heat due at the last such read stays within {@link #HOLD_CYCLES} of that
     * heat (and with no such read: a load, a new still); past that, what the water took over the window of a fence before
     * that read (COOLING LIMITED); with one read alone in it, the share stands. Logged by {@code end} for the outlets' reads.
     */
    private void condensed(StillUnit u,Run r,long start,long end) {
        double share=r.condensed;
        if(!u.condenser()||u.water()==0)share=0;
        else {
            long fence=start-FENCE_TICKS;Read last=null;
            for(var x:r.reads){if(x.tick()>=fence)break;last=x;}
            double need=Math.max(heatAt(r,fence).watts(),NO_HEAT);
            if(last==null||last.due()<=holdSeconds()*need)share=1;
            else {
                Read first=last;for(var x:r.reads)if(x.tick()>=last.tick()-FENCE_TICKS){first=x;break;}
                double span=coordinator.time().seconds(last.tick()-first.tick());
                if(span>0)share=Math.max(0,Math.min(1,Math.max(0,last.taken()-first.taken())/span/need));
            }
        }
        r.condensed=share;
        double before=r.shares.isEmpty()?1:r.shares.peekLast().value();
        if(share!=before){r.shares.addLast(new Share(end,share));keepAsOf(r.shares,Share::tick,end-2*FENCE_TICKS-KEEP_TICKS);}
    }
    /**
     * The water side's read of its slice from {@code start} (R-ST1 items 1 and 2): as of its fence, {@code start} less
     * {@link #FENCE_TICKS}, through which it waited for the pot's and the outlets' islands and the column answer due. The
     * heat due, the pot's heat as of the fence with a backlog over the hold drained over six holds, and the outlet's ceiling
     * from the answer in force then. Before the pot's first slice on this load its temperature is unknown (NaN, which the
     * comparison passes on): the water reads 300 K. The condensing side stands at the mean of the head and the outlet, as the
     * sheet's condenser capacity takes it (a partial condenser's outlet is its water's inlet plus the approach).
     */
    @Override public ExchangerRelay.Read heatRead(long side,long partner,long start,double seconds,double transit) {
        sync();Long pot=waters.get(side);if(pot==null)return null;
        var u=topology.still(pot);var r=runs.get(pot);if(u==null||r==null)return null;
        long fence=start-FENCE_TICKS;
        double due=due(r,fence,transit);
        // A retried slice reads again from the same start: its read replaces the one it repeats.
        if(!r.reads.isEmpty()&&r.reads.peekLast().tick()==start)r.reads.removeLast();
        r.reads.addLast(new Read(start,due,coordinator.sideTemperature(side,start),r.taken));
        PortAccounts.keep(r.reads,Read::tick,start-2*FENCE_TICKS-KEEP_TICKS);
        var heat=heatAt(r,fence);var solved=solvedAt(r,fence);
        double condensate=solved!=null&&solved.condensateTemperature()>0?(solved.headTemperature()+solved.condensateTemperature())/2:heat.temperature()>300?heat.temperature():300;
        double rate=heat.watts()+Math.max(0,due-holdSeconds()*heat.watts())/(6*holdSeconds());
        return ExchangerRelay.Read.column(due,seconds,Math.max(1,condensate-APPROACH_KELVIN),Math.max(0,rate));
    }
    /**
     * The heat due to still {@code r}'s water as of {@code fence} (R-ST1 item 1), J: what was owed through the fence less what
     * the water took through its slice's start. The heat in transit now, {@code transit}, less every owe dated after the fence
     * (the strict edge: an owe dated at the fence is booked, its island being through it) is that value; it is carried here
     * from the last read in the owes' tick order and the water's own bookings, so its last bit does not depend on whether a
     * later owe landed in the transit first ((x + o) - o is not x). Where the two differ by more than rounding (a load, a
     * removed side, an owe the keep window dropped) the transit's value stands. The owes through the fence leave the log.
     */
    private static double due(Run r,long fence,double transit) {
        double live=transit;for(var owed:r.owes.tailMap(fence,false).values())for(double j:owed)live-=j;
        double due=live;
        if(r.settledFence!=Long.MIN_VALUE&&fence>=r.settledFence) {
            due=r.settled;for(var owed:r.owes.subMap(r.settledFence,false,fence,true).values())for(double j:owed)due+=j;
            due-=r.since;
            if(Math.abs(due-live)>1+1e-6*Math.abs(live))due=live;
        }
        r.settled=due;r.settledFence=fence;r.since=0;
        r.owes.headMap(fence,true).clear();
        return due;
    }
    /** The pot's slice in force just before {@code tick} (the latest booked from before it), or {@link #UNKNOWN}. */
    private static Heat heatAt(Run r,long tick){Heat h=UNKNOWN;for(var x:r.heats){if(x.start()>=tick)break;h=x;}return h;}
    /** The column answer in force at {@code tick} (the latest applying at or before it), or null. */
    private static StillHead.Solved solvedAt(Run r,long tick){StillHead.Solved s=null;for(var a:r.answers){if(a.tick()>tick)break;s=a.solved();}return s;}
    /** The condenser's share in force at {@code tick} (the latest set at or before it), 1 before any. */
    private static double condensedAt(Run r,long tick){double s=1;for(var x:r.shares){if(x.tick()>tick)break;s=x.value();}return s;}
    /** Drops the oldest entries of a dated log while the next is dated at or before {@code from}: a read as of {@code from} or later still finds the one in force. */
    private static <T> void keepAsOf(ArrayDeque<T> log,java.util.function.ToLongFunction<T> tick,long from) {
        while(log.size()>1){var it=log.iterator();it.next();if(tick.applyAsLong(it.next())>from)return;log.removeFirst();}
    }
    /** Device {@code device}'s island wakes at {@code tick}: a fixed tick of the world's, not the moment a commit lands (R-ST1 item 5). */
    private void wakeAt(long tick,long device){wakes.computeIfAbsent(tick,k->new TreeSet<>()).add(device);}
    /** The wakes due by now, at the engine's decision points (every tick's due deadlines among them): each device's island, certified, ends its certificate. */
    @Override public void decide() {
        if(wakes.isEmpty())return;long now=coordinator.now();
        while(!wakes.isEmpty()&&wakes.firstKey()<=now)for(long device:wakes.pollFirstEntry().getValue()){Long island=owner.apply(device);if(island!=null)coordinator.controlsMoved(List.of(island));}
    }

    // ---------------- the column's solves ----------------

    /** Asks for the column's next answer when the fire is asked (lit, or waiting for the first answer) and its last answer is {@link #SOLVE_TICKS} old. */
    private void submit(StillUnit u,Run r,long island,PassiveNetwork.Reservoir node,long start,long end) {
        // A still with neither column nor condenser has nothing to solve: its vapour leaves as the pot releases it.
        promote(r,end);
        if(!u.condenser()&&u.stages()==0||r.pending||end<r.lastSubmit+SOLVE_TICKS||!r.asked)return;
        condensed(u,r,start,end);
        double reflux=BatchStill.effectiveReflux(reflux(u),r.condensed);
        var warm=r.solved==null?null:r.solved.profile();
        var job=new StillHead.Job(node.inventory().moles(),node.state().temperature(),BatchStill.ATMOSPHERIC,u.stages(),reflux,warm,u.design().columnDiameter(),outlet(r,start-FENCE_TICKS));
        long pot=u.id(),apply=end+APPLY_TICKS;
        if(!solves.submit(pot,island,job,outcome->completed(pot,apply,outcome)))return;
        r.pending=true;r.applyTick=apply;r.lastSubmit=end;
    }
    /**
     * The condenser's outlet floor for a job queued by the pot slice whose fence is {@code fence}, K (R-ST1 item 5): the water
     * side's inlet as its last read before that fence recorded it (the pot waited for its water through it) plus
     * {@link BatchStill#OUTLET_APPROACH}; a still with no water, or none recorded yet, the total condenser's (it then
     * condenses nothing with no water, {@link #condensed}). A change of the water reaches a job queued a fence later.
     */
    private static double outlet(Run r,long fence) {
        double water=Double.NaN;for(var x:r.reads){if(x.tick()>=fence)break;if(x.inlet()>0)water=x.inlet();}
        return water>0?water+BatchStill.OUTLET_APPROACH:com.wormzjl.icheme.science.column.still.StillColumn.TOTAL_CONDENSER;
    }
    /** The column's waiting answer, applied once its tick is reached. */
    private static void promote(Run r,long tick){if(r.next!=null&&r.nextTick<=tick){r.solved=r.next;r.fPerWatt=r.solved.fPerWatt();r.next=null;r.nextTick=Long.MAX_VALUE;}}
    /** A column job's outcome: the answer applies at {@code apply}, where the pot's island wakes (and the fenced reads find it there). */
    private void completed(long pot,long apply,StillHead.Outcome outcome) {
        var r=runs.get(pot);if(r==null)return;
        r.pending=false;r.applyTick=Long.MAX_VALUE;
        switch(outcome) {
            case StillHead.Solved solved->{
                r.next=solved;r.nextTick=apply;r.failure=null;
                r.answers.addLast(new Answer(apply,solved));keepAsOf(r.answers,Answer::tick,apply-2*FENCE_TICKS-KEEP_TICKS);
            }
            case StillHead.Failed failed->r.failure=failed.reason();
        }
        wakeAt(apply,pot);
    }

    // ---------------- statuses, views and settings ----------------

    private void status(StillUnit u,Run r,PassiveNetwork.Reservoir node,double[] arrived,double seconds) {
        int alarms=r.alarms;
        if(u.heater()==BatchStill.Heater.ELECTRIC&&firing(u)>0&&!electric.powered(u.id()))alarms|=BatchStill.Status.NO_FUEL.bit();
        // A closed pot distils while at least a tenth of its heat leaves as vapour (at water's latent heat, the largest a
        // still's contents have); a gas headspace pushed out by the heating alone carries far less.
        boolean distilled=u.condenser()?r.distillateFlow>0:r.heat>0&&r.ventFlow*LATENT_CEILING>.1*r.heat;
        BatchStill.Status primary=null;
        for(var s:BatchStill.Status.values())if(s.alarm()&&(alarms&s.bit())!=0){primary=s;break;}
        // With a condenser the still distils where liquid reached the spout's account; its vented gas alone is boiling.
        // A fire waiting for its column's first answer reads HEATING: it is set and lights at the answer (the line gives a failure).
        if(primary==null)primary=distilled||r.spout>0&&u.condenser()?BatchStill.Status.DISTILLING:r.heat>0&&r.solved!=null?BatchStill.Status.BOILING:r.heat>0||r.asked&&waits(u,r)?BatchStill.Status.HEATING:BatchStill.Status.IDLE;
        r.status=primary;r.alarms=alarms;
        if(u.condenser()&&r.condensed<1)r.alarms|=BatchStill.Status.COOLING_LIMITED.bit();
    }
    /** Still {@code pot}'s alarms, in their order: NO POWER for an electric still out of power, NO FUEL for a fired one. */
    public List<EquipmentAlarm> alarms(long pot) {
        var u=topology.still(pot);var r=runs.get(pot);if(u==null||r==null)return List.of();
        var out=new ArrayList<EquipmentAlarm>();
        if((r.alarms&BatchStill.Status.NO_FUEL.bit())!=0)out.add(u.heater()==BatchStill.Heater.ELECTRIC?EquipmentAlarm.NO_POWER:EquipmentAlarm.NO_FUEL);
        if((r.alarms&BatchStill.Status.OVERHEAT.bit())!=0)out.add(EquipmentAlarm.OVERHEAT);
        if((r.alarms&BatchStill.Status.BOIL_DRY.bit())!=0)out.add(EquipmentAlarm.BOIL_DRY);
        if((r.alarms&BatchStill.Status.FLOODING.bit())!=0)out.add(EquipmentAlarm.FLOODING);
        if((r.alarms&BatchStill.Status.COOLING_LIMITED.bit())!=0)out.add(EquipmentAlarm.COOLING_LIMITED);
        return out;
    }
    /** Still {@code pot} as its page shows it (cached), or null for none. */
    public View view(long pot) {
        var u=topology.still(pot);var r=runs.get(pot);if(u==null||r==null)return null;
        double reflux=BatchStill.effectiveReflux(reflux(u),r.condensed);
        return new View(r.potTemperature,r.level,r.filled,r.solved==null?Double.NaN:r.solved.headTemperature(),r.heat,r.distillateFlow,r.ventFlow,r.condensed,reflux,r.status,alarms(pot));
    }
    /** The still's status line: its status word, its alarms, and a failed column solve's reason. */
    public String statusLine(long pot) {
        var v=view(pot);if(v==null)return "";
        var r=runs.get(pot);var line=new StringBuilder(v.status().name().replace('_',' '));
        for(var a:v.alarms())if(!line.toString().contains(a.text))line.append(" · ").append(a.text);
        if(r!=null&&r.failure!=null)line.append(" · COLUMN: ").append(r.failure);
        // A tray that would hold free water (W4 D4): a notice, the column's answer stands.
        if(r!=null&&r.solved!=null&&r.solved.waterOnTrays())line.append(" · WATER ON THE TRAYS");
        // Two liquids on the trays (W4 plan section 12): the column's answer is the pot's single stage.
        if(r!=null&&r.solved!=null&&r.solved.potStill())line.append(" · COLUMN: ").append(r.solved.notice());
        return line.toString();
    }
    /**
     * The player's settings for still {@code pot}: its firing (0 to 1: the damper, the burners or the power) and its reflux
     * ratio (+Infinity closed). A fact; the pot's island reads them at its next slice. Null, or why not.
     */
    public String settings(long pot,double firing,double reflux) {
        var u=topology.still(pot);if(u==null)return "no batch still";
        if(!(firing>=0&&firing<=1)||!(reflux>=0)||Double.isNaN(reflux))return "invalid still settings";
        topology.still(u.withFiring(firing).withReflux(reflux));
        replanned(pot);
        return null;
    }
    /**
     * Still {@code pot}'s firing or reflux changed (its settings, or its controller's setpoints): its pot's and its burners'
     * islands read them at their next slice, and its column solves again at its next boiling commit.
     */
    public void replanned(long pot) {
        var u=topology.still(pot);if(u==null)return;
        Long island=owner.apply(pot);if(island!=null)coordinator.controlsMoved(List.of(island));
        if(u.burners()>0){Long fuelIsland=owner.apply(u.burners());if(fuelIsland!=null)coordinator.controlsMoved(List.of(fuelIsland));}
        var r=runs.get(pot);if(r!=null)r.lastSubmit=Long.MIN_VALUE/2;
    }
    /** Whether still {@code pot} has a reflux valve: a column over its condenser. */
    public boolean refluxes(long pot){var u=topology.still(pot);return u!=null&&u.design().refluxes();}
    /** The firing still {@code pot} runs at (its controller's, else its setting), NaN for no still. */
    public double firing(long pot){var u=topology.still(pot);return u==null?Double.NaN:firing(u);}
    /** The reflux ratio still {@code pot}'s valve is set to (+Infinity closed), NaN for no still; 0 with no condenser to return any. */
    public double reflux(long pot){var u=topology.still(pot);return u==null?Double.NaN:reflux(u);}
    /** Forgets still {@code pot}'s engine state (its structure was removed). */
    public void removed(long pot){runs.remove(pot);refresh();}

    // ---------------- the basis ----------------

    private double[] onIsland(long island,double[] moles){return PortAccounts.onIsland(models.apply(island).components(),axis,moles);}
    private double[] onWorld(long island,double[] moles){return PortAccounts.onWorld(island,models.apply(island).components(),axis,moles);}
    /**
     * Each world component's liquid share at the condenser's outlet, from the column's answer on the pot island's own list; a
     * component that list does not carry, none; null (all vents) for a still that solves no column (the fire of one that
     * does waits for its first answer, {@link #waits}).
     */
    private double[] shares(Run r) {
        if(r.solved==null||r.potIsland<0)return null;
        var own=models.apply(r.potIsland).components();var shares=r.solved.liquidShares();
        if(shares.length!=own.size())return null;
        var result=new double[axis.size()];
        for(int i=0;i<shares.length;i++){int j=axis.indexOf(own.get(i));if(j>=0)result[j]=shares[i];}
        return result;
    }
    private double mass(double[] moles){return PortAccounts.mass(weights,moles);}
    private static double[] plus(double[] a,double[] b){var c=a.clone();for(int i=0;i<c.length;i++)c[i]+=b[i];return c;}
}
