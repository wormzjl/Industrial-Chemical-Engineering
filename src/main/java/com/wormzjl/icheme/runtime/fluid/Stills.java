package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.column.operation.ColumnCycle.Account;
import com.wormzjl.icheme.science.fluid.network.BatchStill;
import com.wormzjl.icheme.science.fluid.network.Burners;
import com.wormzjl.icheme.science.fluid.network.ConservativeTransport;
import com.wormzjl.icheme.science.fluid.network.ExchangerRelay;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.network.PassiveIntervalSolver;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.network.Pool;
import com.wormzjl.icheme.science.fluid.network.PortPlan;
import com.wormzjl.icheme.science.fluid.network.StillColumn;
import com.wormzjl.icheme.science.fluid.network.StillHead;
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
 * each slice gives what its account held as of {@link #FENCE_TICKS} before its start, over {@link #HOLD_CYCLES}. A gift's
 * energy short of what its account carried is the condenser's duty: owed to the water side, or to the air with none.</li>
 * <li><b>Condenser.</b> It condenses what its water takes: while the heat owed stays within {@link #HOLD_CYCLES} of the
 * pot's heat it condenses all; past that, the share the water took over the last window (COOLING LIMITED), and the column
 * solves at the reflux that share leaves ({@link BatchStill#effectiveReflux}).</li>
 * <li><b>Column.</b> Solved every {@link #SOLVE_TICKS} of a boiling pot's commits, on the worker, warm from its last profile;
 * its answer applies at a fixed tick ({@link #APPLY_TICKS} after the commit that asked), so the pot's slices do not depend
 * on the worker's timing.</li>
 * <li><b>Fuel.</b> A solid still burns the heat left in its item (a fact) and takes the next item from its slot when that runs
 * low; a liquid one plans its burners' valve from its firing and books the fuel's heat; an electric one draws on its store.</li>
 * </ul>
 * Everything but the accounts, the fire's heat and the settings is state: a load starts each still at no column answer. A
 * data-network controller's setpoints ({@link Plans}, owner decision O1) override the settings while it runs; the settings
 * resume when it stops.
 */
public final class Stills implements IslandCoordinator.Ports {
    /** An outlet's slice reads its account as of this many ticks before its start: the pot's commits through then. */
    public static final long FENCE_TICKS=440;
    /** The column solves at most this often while the pot boils, ticks. */
    public static final long SOLVE_TICKS=100;
    /** A solve's answer applies this many ticks after the commit that asked for it. */
    public static final long APPLY_TICKS=100;
    /** The engine's cycle, ticks (5 s of play): the still's windows are counted in it. */
    public static final long CYCLE_TICKS=100;
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
    private record Paid(long tick,double owed,double transit) {}

    private static final class Run {
        StillHead.Solved solved,next;long nextTick=Long.MAX_VALUE,applyTick=Long.MAX_VALUE;boolean pending;long lastSubmit=Long.MIN_VALUE/2;
        double condensed=1,heat,boiling,fired,perKilogram,fPerWatt;String failure;
        long potIsland=-1;final ArrayDeque<Arrival> arrivals=new ArrayDeque<>();final ArrayDeque<Paid> paid=new ArrayDeque<>();
        double potTemperature=Double.NaN,level,filled,distillateFlow,ventFlow,transit;
        int alarms;BatchStill.Status status=BatchStill.Status.IDLE;
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
            var head=pool.head()==null?null:head(u,r,pool.head().components());
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
    /** The heat into the pot for the slice, W, and its alarms. */
    private double heat(StillUnit u,Run r,PassiveNetwork.Reservoir node,double seconds) {
        var design=u.design();double asked=firing(u)*design.rating();
        var s=node.state();int alarms=0;
        double liquid=s.liquidVolume()+s.waterVolume(),volume=node.inventory().volume();
        r.potTemperature=s.temperature();r.filled=volume>0?liquid/volume:0;r.level=r.filled*node.inventory().levelHeight();
        double available=switch(u.heater()) {
            case SOLID->asked>0?Math.min(1,u.burning()*u.heater().efficiency()/(asked*seconds)):0;
            case LIQUID->asked>0?Math.min(1,r.fired*u.heater().efficiency()/asked):0;
            case ELECTRIC->{if(!electric.powered(u.id()))electric.restart(u.id());yield electric.powered(u.id())?1:0;}
        };
        // A liquid still's burners light on the fuel's arrival: until they have fired once, the pot waits for it.
        if(asked>0&&!(available>0))alarms|=u.heater()==BatchStill.Heater.ELECTRIC?0:BatchStill.Status.NO_FUEL.bit();
        if(s.temperature()>=BatchStill.OVERHEAT)alarms|=BatchStill.Status.OVERHEAT.bit();
        if(liquid<=BatchStill.DRY_SHARE*volume)alarms|=BatchStill.Status.BOIL_DRY.bit();
        double heat=(alarms&(BatchStill.Status.OVERHEAT.bit()|BatchStill.Status.BOIL_DRY.bit()))!=0?0:asked*available;
        // A column floods past its F-factor: the firing is held at the flood boil-up.
        if(design.stages()>0&&r.fPerWatt>0&&heat*r.fPerWatt>BatchStill.FLOOD_F_FACTOR){heat=BatchStill.FLOOD_F_FACTOR/r.fPerWatt;alarms|=BatchStill.Status.FLOODING.bit();}
        if(u.condenser()&&r.condensed<1)alarms|=BatchStill.Status.COOLING_LIMITED.bit();
        r.alarms=alarms;r.heat=heat>=NO_HEAT?heat:0;
        return r.heat;
    }
    /** The pot's head for the slice: the column's answer on the island's list, all up before it or without a column. */
    private Pool.Head head(StillUnit u,Run r,int components) {
        if(u.stages()==0||r.solved==null||r.solved.head().components()!=components)return Pool.Head.plain(components);
        return r.solved.head();
    }
    /** A liquid still's burners' fuel, kg/s: its firing's fuel over the fuel's heat with {@link Burners#MARGIN}, 0 when cut. */
    private double fuelTarget(StillUnit u,Run r) {
        if(u.heater()!=BatchStill.Heater.LIQUID||!(firing(u)>0))return 0;
        if((r.alarms&(BatchStill.Status.OVERHEAT.bit()|BatchStill.Status.BOIL_DRY.bit()))!=0)return 0;
        double fired=u.heater().drawn(firing(u)*u.design().rating());
        // Until fuel has arrived its heat is unknown: methane's, 50 MJ/kg, as the fired heaters ask.
        double perKilogram=r.perKilogram>0?r.perKilogram:5e7;
        return fired*Burners.MARGIN/perKilogram;
    }

    // ---------------- the ports ----------------

    @Override public boolean ready(long island,PassiveNetwork graph,long start) {
        sync();if(runs.isEmpty())return true;
        for(var node:graph.reservoirs()) {
            var r=runs.get(node.id());
            // A pot waits for a column answer due at or before its slice's start.
            if(r!=null&&r.pending&&r.applyTick<=start)return false;
            Long pot=outlets.get(node.id());
            if(pot!=null){var p=runs.get(pot);Long potIsland=owner.apply(pot);if(p!=null&&potIsland!=null&&potIsland!=island&&!coordinator.through(potIsland,start-FENCE_TICKS))return false;}
        }
        return true;
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
    /** An outlet's generator and valve: its account as of the fence, at the condensate's or the head's temperature, over {@link #HOLD_CYCLES}. */
    private PortPlan outletPlan(long island,StillUnit u,Run r,PassiveNetwork.Reservoir node,IslandClock.Slice slice,boolean distillate) {
        long fence=slice.startTick()-FENCE_TICKS;
        var available=(distillate?u.head():u.venting()).moles();
        for(var a:r.arrivals)if(a.tick()>fence){var n=distillate?a.distillate():a.vent();for(int i=0;i<available.length;i++)available[i]=Math.max(0,available[i]-n[i]);}
        double total=0;for(double n:available)total+=n;
        var mine=total>0?onIsland(island,available):null;
        if(!(total>0)||mine==null)return new PortPlan(node.id(),node.id(),0,node.state().temperature(),com.wormzjl.icheme.science.fluid.solver.PhaseLayout.totalAmounts(node.state()));
        double temperature=r.solved==null?r.potTemperature:distillate?r.solved.condensateTemperature():r.solved.headTemperature();
        if(!(temperature>0))temperature=node.state().temperature();
        double mass=mass(available),target=mass/Math.max(slice.seconds(coordinator.time()),holdSeconds());
        return new PortPlan(node.id(),node.id(),target,temperature,mine);
    }
    /** The compounds outlet {@code device} may deliver: those its account holds (water not named), for its island's list. */
    public Set<String> compounds(long device) {
        sync();Long pot=outlets.get(device);var u=pot==null?null:topology.still(pot);if(u==null)return Set.of();
        var n=(device==u.distillate()?u.head():u.venting()).moles();var out=new TreeSet<String>();
        for(int i=0;i<n.length-1;i++)if(n[i]>0)out.add(axis.get(i));
        return out;
    }

    @Override public void booked(long island,PassiveNetwork graph,long start,long end,PassiveIntervalSolver.Result result) {
        sync();if(runs.isEmpty()||end<=start)return;
        double seconds=coordinator.time().seconds(end-start);
        Map<Long,double[]> arrived=null;Map<Long,Double> arrivedEnergy=null,potHeat=null,given=null;Map<Long,double[]> gifts=null;
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
            if(n!=null||e!=0)u=arrive(u,r,n==null?new double[axis.size()]:n,e,end);
            if(joule!=0)u=u.withTotals(u.heated()+joule,u.condensed());
            if(u!=original)topology.still(u);
            // A closed pot's vapour leaves by its VAPOUR nozzle: what its lines carry out, kg/s.
            if(!u.condenser()){double out=0;for(int pipe=0;pipe<result.graph().pipes().size();pipe++){var p=result.graph().pipes().get(pipe);if(p.first()==k)out+=flows[pipe];if(p.second()==k)out-=flows[pipe];}r.ventFlow=Math.max(0,out);}
            status(u,r,node,n,seconds);
            submit(u,r,island,node,end);
        }
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
        var world=critical(r);
        double condensate=r.solved==null?r.potTemperature:r.solved.condensateTemperature();
        double liquid=StillHead.liquidShare(reflux(u),r.condensed);
        // Before the column's first answer the condensate stands at the pot's temperature.
        var division=StillHead.divide(moles,energy,world,condensate,liquid,u.condenser());
        double total=0;for(double n:moles)total+=n;
        if(!(total>0)) {
            // Energy with no moles: a closed column's reflux, condensed and returned; the water (or the air) takes it now.
            return owe(u,r,energy,end);
        }
        r.arrivals.addLast(new Arrival(end,division.distillate(),division.vent()));
        while(r.arrivals.size()>1&&r.arrivals.peekFirst().tick()<end-2*FENCE_TICKS)r.arrivals.removeFirst();
        var head=new Account(plus(u.head().moles(),division.distillate()),u.head().energy()+division.distillateEnergy());
        var vent=new Account(plus(u.venting().moles(),division.vent()),u.venting().energy()+division.ventEnergy());
        return u.withAccounts(head,vent);
    }
    /** What outlet {@code device} gave over a span: debited from its account, the energy short of the account's share owed. */
    private void give(long device,double[] moles,double joule,double seconds,long end) {
        Long pot=outlets.get(device);var u=pot==null?null:topology.still(pot);var r=pot==null?null:runs.get(pot);if(u==null||r==null)return;
        boolean distillate=device==u.distillate();var account=distillate?u.head():u.venting();
        var held=account.moles();double before=account.total(),gave=0;
        for(int i=0;i<held.length;i++){double g=moles[i];if(g>held[i]*(1+1e-9)+1e-12)com.wormzjl.icheme.IChemE.LOGGER.error("still status=OVERDRAWN pot={} outlet={} component={} held={} given={}",pot,device,i,held[i],g);held[i]=Math.max(0,held[i]-g);gave+=g;}
        double share=before>0?Math.min(1,gave/before):0,debited=share*account.energy();
        var next=new Account(held,account.energy()-debited);
        u=distillate?u.withAccounts(next,u.venting()):u.withAccounts(u.head(),next);
        if(distillate)r.distillateFlow=mass(moles)/seconds;else r.ventFlow=mass(moles)/seconds;
        topology.still(owe(u,r,debited-joule,end));
    }
    /** {@code joule} of condenser duty: owed to the water side, or with none given to the air; the still's total either way. */
    private StillUnit owe(StillUnit u,Run r,double joule,long end) {
        if(joule==0)return u;
        var water=u.water()>0?topology.active().get(u.water()):null;
        if(water!=null&&water.device().heat()!=null) {
            coordinator.owe(PhysicalFluidTopology.sideIdentity(u.water()),PhysicalFluidTopology.sideIdentity(u.id()),joule);
            r.paid.addLast(new Paid(end,joule,Double.NaN));
            Long island=owner.apply(u.water());if(island!=null)coordinator.controlsMoved(List.of(island));
        }
        return u.withTotals(u.heated(),u.condensed()+joule);
    }
    /** The condenser's share for the next solve: all while what it owes stays within {@link #HOLD_CYCLES} of the pot's heat, else what the water took. */
    private void condensed(StillUnit u,Run r,long now) {
        if(!u.condenser()){r.condensed=0;return;}
        if(u.water()==0){r.condensed=0;return;}
        double need=Math.max(r.heat,NO_HEAT);
        if(r.transit<=holdSeconds()*need){r.condensed=1;return;}
        double owed=0,first=Double.NaN;long from=now;
        for(var p:r.paid){if(p.tick()<now-FENCE_TICKS)continue;owed+=p.owed();if(Double.isNaN(first)&&!Double.isNaN(p.transit())){first=p.transit();from=p.tick();}}
        double span=Math.max(1,coordinator.time().seconds(now-from)),took=Double.isNaN(first)?0:Math.max(0,owed-(r.transit-first));
        r.condensed=Math.max(0,Math.min(1,took/span/need));
        while(r.paid.size()>1&&r.paid.peekFirst().tick()<now-2*FENCE_TICKS)r.paid.removeFirst();
    }
    @Override public ExchangerRelay.Read heatRead(long side,long partner,long start,double seconds,double transit) {
        sync();Long pot=waters.get(side);if(pot==null)return null;
        var u=topology.still(pot);var r=runs.get(pot);if(u==null||r==null)return null;
        r.transit=transit;r.paid.addLast(new Paid(start,0,transit));
        while(r.paid.size()>1&&r.paid.peekFirst().tick()<start-2*FENCE_TICKS)r.paid.removeFirst();
        // Before the pot's first slice its temperature is unknown (NaN, which Math.max passes on): the water reads 300 K.
        double condensate=r.solved!=null&&r.solved.condensateTemperature()>0?r.solved.condensateTemperature():r.potTemperature>300?r.potTemperature:300;
        double rate=r.heat+Math.max(0,transit-holdSeconds()*r.heat)/(6*holdSeconds());
        return ExchangerRelay.Read.column(transit,seconds,Math.max(1,condensate-APPROACH_KELVIN),Math.max(0,rate));
    }

    // ---------------- the column's solves ----------------

    /** Asks for the column's next answer when the pot boils and its last answer is {@link #SOLVE_TICKS} old. */
    private void submit(StillUnit u,Run r,long island,PassiveNetwork.Reservoir node,long end) {
        // A still with neither column nor condenser has nothing to solve: its vapour leaves as the pot releases it.
        promote(r,end);
        if(!u.condenser()&&u.stages()==0||r.pending||end<r.lastSubmit+SOLVE_TICKS||!(r.heat>0))return;
        condensed(u,r,end);
        double reflux=BatchStill.effectiveReflux(reflux(u),r.condensed);
        var warm=r.solved==null?null:r.solved.profile();
        var job=new StillHead.Job(node.inventory().moles(),node.state().temperature(),BatchStill.ATMOSPHERIC,u.stages(),reflux,warm,u.design().columnDiameter());
        long pot=u.id(),apply=end+APPLY_TICKS;
        if(!solves.submit(pot,island,job,outcome->completed(pot,apply,outcome)))return;
        r.pending=true;r.applyTick=apply;r.lastSubmit=end;
    }
    /** The column's waiting answer, applied once its tick is reached. */
    private static void promote(Run r,long tick){if(r.next!=null&&r.nextTick<=tick){r.solved=r.next;r.fPerWatt=r.solved.fPerWatt();r.next=null;r.nextTick=Long.MAX_VALUE;}}
    private void completed(long pot,long apply,StillHead.Outcome outcome) {
        var r=runs.get(pot);if(r==null)return;
        r.pending=false;r.applyTick=Long.MAX_VALUE;
        switch(outcome) {
            case StillHead.Solved solved->{r.next=solved;r.nextTick=apply;r.failure=null;}
            case StillHead.Failed failed->r.failure=failed.reason();
        }
        if(r.potIsland>=0)coordinator.controlsMoved(List.of(r.potIsland));
    }

    // ---------------- statuses, views and settings ----------------

    private void status(StillUnit u,Run r,PassiveNetwork.Reservoir node,double[] arrived,double seconds) {
        int alarms=r.alarms;
        if(u.heater()==BatchStill.Heater.ELECTRIC&&firing(u)>0&&!electric.powered(u.id()))alarms|=BatchStill.Status.NO_FUEL.bit();
        double up=0;if(arrived!=null)for(double n:arrived)up+=n;
        // A closed pot distils while at least a tenth of its heat leaves as vapour (at water's latent heat, the largest a
        // still's contents have); a gas headspace pushed out by the heating alone carries far less.
        boolean distilled=u.condenser()?r.distillateFlow>0:r.heat>0&&r.ventFlow*LATENT_CEILING>.1*r.heat;
        BatchStill.Status primary=null;
        for(var s:BatchStill.Status.values())if(s.alarm()&&(alarms&s.bit())!=0){primary=s;break;}
        if(primary==null)primary=distilled||up>0&&u.condenser()?BatchStill.Status.DISTILLING:r.heat>0&&r.solved!=null?BatchStill.Status.BOILING:r.heat>0?BatchStill.Status.HEATING:BatchStill.Status.IDLE;
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

    private double[] onIsland(long island,double[] moles) {
        var own=models.apply(island).components();if(own.equals(axis))return moles;
        var result=new double[own.size()];
        for(int i=0;i<moles.length;i++){if(moles[i]==0)continue;int j=own.indexOf(axis.get(i));if(j<0)return null;result[j]=moles[i];}
        return result;
    }
    private double[] onWorld(long island,double[] moles) {
        var own=models.apply(island).components();if(own.equals(axis))return moles;
        var result=new double[axis.size()];
        for(int i=0;i<moles.length;i++){if(moles[i]==0)continue;int j=axis.indexOf(own.get(i));if(j<0)throw new IllegalStateException("Island "+island+" carries "+own.get(i)+", which the world list does not");result[j]=moles[i];}
        return result;
    }
    /**
     * Each world component's critical temperature, K, as the pot island's model gives it (the column's answer's, or its
     * model's own before one); a component the island does not carry condenses (water's).
     */
    private double[] critical(Run r) {
        var result=new double[axis.size()];Arrays.fill(result,StillHead.WATER_CRITICAL);
        if(r.potIsland<0)return result;
        var model=models.apply(r.potIsland);var own=model.components();
        double[] critical=r.solved!=null?r.solved.critical():criticals.computeIfAbsent(model,m->{
            var column=new StillColumn(m.hydrocarbon,BatchStill.ATMOSPHERIC);int n=m.hydrocarbon.componentCount();
            var c=new double[n+1];for(int i=0;i<n;i++)c[i]=column.criticalTemperature(i);c[n]=StillHead.WATER_CRITICAL;return c;});
        for(int i=0;i<critical.length&&i<own.size();i++){int j=axis.indexOf(own.get(i));if(j>=0)result[j]=critical[i];}
        return result;
    }
    private final Map<com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics,double[]> criticals=new java.util.WeakHashMap<>();
    private double mass(double[] moles){double m=0;for(int i=0;i<moles.length;i++)m+=moles[i]*weights[i];return m;}
    private static double[] plus(double[] a,double[] b){var c=a.clone();for(int i=0;i<c.length;i++)c[i]+=b[i];return c;}
}
