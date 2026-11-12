package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.science.fluid.SimulationTime;
import com.wormzjl.icheme.science.fluid.conversion.ConversionLaws;
import com.wormzjl.icheme.science.fluid.conversion.ConversionRule;
import com.wormzjl.icheme.science.fluid.conversion.ConversionRules;
import com.wormzjl.icheme.science.fluid.network.ConservativeTransport;
import com.wormzjl.icheme.science.fluid.network.PassiveIntervalSolver;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.network.PortPlan;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import java.util.*;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The fluid converters (batch 2026-11-12-fluid-conversion, plan 3.2): a boundary of the network between an IChemE vessel
 * and a Forge tank, on the column ports' mechanism ({@link PortPlan}, {@link ColumnOperation}): plan at dispatch, book on
 * accept. Arithmetic on the server thread; a forward rule's litre is a flash once per rule revision and island list.
 *
 * <ul>
 * <li>Reverse (IChemE to Forge): the drain port, a void behind its valve, takes the phase at its nozzle while a rule holds
 * for it. Its target is {@code min(rate rho, room rho / dt)} kg/s, room the Forge tank's free litres less the remainder,
 * reserved for the slice. A booking adds {@code m / rho} litres to the remainder and moves the whole mB into the tank.</li>
 * <li>Forward (Forge to IChemE): the fill port, a generator behind its valve, gives the rule's stream. A plan moves up to
 * {@code rate dt} litres out of the Forge tank into the account; the generator gives at most the account's mass; a booking
 * debits {@code m / rho_rule} litres and returns the rest of the account, in whole mB, to the tank.</li>
 * </ul>
 * A new plan supersedes the last one, so a rejected slice's reservation goes with the next plan; a reservation also lapses
 * {@link #RESERVATION_TICKS} after its slice's end, and {@link #release} returns an account (an island restated or held).
 * A replayed span books as a solved one; what a full Forge tank cannot take stays in the remainder, never lost.
 * Nothing here runs in a Forge handler call but the move of whole mB ({@link Tank}).
 */
public final class Converters implements IslandCoordinator.Ports {
    /** A converter's state as its page shows it, by name. */
    public enum Status {OFF,IDLE,BALANCED,CONVERTING,NO_RULE,OFF_SPEC,SOURCE_EMPTY,DESTINATION_FULL}
    /** The direction a plan took. */
    public enum Direction {NONE,TO_ICHEME,TO_FORGE}
    /** A reservation lapses this many online ticks after its slice's end (a slice never booked: its island held or fenced). */
    public static final long RESERVATION_TICKS=200;
    /** Below this many litres a direction moves nothing (no room, an empty source). */
    static final double LEAST_LITRES=1;
    /**
     * The heel the drain leaves in its vessel, a share of the vessel's volume (at least {@link #LEAST_LITRES}): the regulated
     * draw never asks for more than the drawn phase holds, which leaves the valve no state to regulate to (the island holds).
     */
    static final double HEEL=.01;

    /** What crossed a converter's ports, since the engine started (state): kg, J and Forge litres each way. */
    public record Totals(double massOut,double energyOut,double litresOut,double massIn,double energyIn,double litresIn) {
        public static final Totals NONE=new Totals(0,0,0,0,0,0);
        Totals plus(Totals o){return new Totals(massOut+o.massOut,energyOut+o.energyOut,litresOut+o.litresOut,massIn+o.massIn,energyIn+o.energyIn,litresIn+o.litresIn);}
    }
    /** The Forge-facing tank of a converter (W3 exposes it as the fluid handler): whole mB only, no process calculation. */
    public interface Tank {
        long capacity();
        String fluid();
        long stored();
        /** Puts up to {@code mB} of {@code fluid} in, inside {@code capacity - stored - reserved}; returns what it took. */
        long fill(String fluid,long mB,boolean simulate);
        /** Takes up to {@code mB} out, inside {@code stored}; returns what it gave. */
        long drain(long mB,boolean simulate);
        /** Whether it takes {@code fluid}: the fluid it holds, or, empty, a fluid some rule in force names for it. */
        boolean valid(String fluid);
    }

    private record Reserved(double litres,long until) {}
    /** The last plan of a converter (state): its direction and rule, the drawn density (kg/m3) and the forward stream per litre. */
    private record Planned(Direction direction,String rule,String fluid,double density,ConversionLaws.PerLitre perLitre,long end) {}

    private final WorldTopologyLedger topology;
    private final LongFunction<FluidThermodynamics> models;
    private final SimulationTime time;
    private final LongSupplier now;
    private final LongConsumer moved;
    private Supplier<ConversionRules> rules;
    private Function<String,Set<String>> tags=fluid->Set.of();
    private final Map<Long,Long> byPort=new HashMap<>();
    private final Map<Long,Reserved> reserved=new HashMap<>();
    private final Map<Long,Planned> planned=new HashMap<>();
    private final Map<Long,String> lastRule=new HashMap<>();
    private final Map<Long,Status> status=new HashMap<>();
    private final Map<Long,Totals> totals=new HashMap<>();
    private final Map<String,ConversionLaws.PerLitre> perLitre=new HashMap<>();

    /**
     * @param models by island, its model (its own compound list)
     * @param time   the world's time factor (a slice's process seconds)
     * @param now    the online tick
     * @param moved  told a converter's identity when its plan moved (a booking with flow, a Forge fill or drain): the
     *               world wakes its island ({@link IslandCoordinator#portsMoved}) at the next decision point
     * @param rules  the rules in force ({@code ConversionRuntime::active})
     */
    public Converters(WorldTopologyLedger topology,LongFunction<FluidThermodynamics> models,SimulationTime time,LongSupplier now,LongConsumer moved,Supplier<ConversionRules> rules) {
        this.topology=Objects.requireNonNull(topology);this.models=Objects.requireNonNull(models);this.time=Objects.requireNonNull(time);
        this.now=Objects.requireNonNull(now);this.moved=Objects.requireNonNull(moved);this.rules=Objects.requireNonNull(rules);
        // A load starts with no slice in flight: every saved account goes back to its Forge tank, whole mB.
        for(var u:topology.converters().values()){index(u);release(u.id());}
    }
    /** The Forge fluids' tags, without '#' (W3: the fluid registry's), for tag rules. */
    public void tags(Function<String,Set<String>> value){tags=Objects.requireNonNull(value);}
    public void rules(Supplier<ConversionRules> value){rules=Objects.requireNonNull(value);perLitre.clear();}
    private void index(ConverterUnit u){if(u.drain()!=0)byPort.put(u.drain(),u.id());if(u.fill()!=0)byPort.put(u.fill(),u.id());}

    // ---------------- registration and settings (W3 calls these from the equipment structure) ----------------

    /** Registers {@code unit}, its vessel and ports already registered devices. */
    public void register(ConverterUnit unit){topology.converter(unit);index(unit);moved.accept(unit.id());}
    /** Removes converter {@code id}; its Forge tank leaves with the block (nothing of the fluid world is in it). */
    public ConverterUnit remove(long id) {
        var u=topology.removeConverter(id);if(u==null)return null;
        byPort.values().removeIf(v->v==id);reserved.remove(id);planned.remove(id);lastRule.remove(id);status.remove(id);totals.remove(id);return u;
    }
    /** Sets converter {@code id}'s mode, rate (L per process second) and deadband (a queued engine event's apply). */
    public void settings(long id,ConverterUnit.Mode mode,double rate,double deadband) {
        var u=topology.converter(id);if(u==null)return;topology.converter(u.withSettings(mode,rate,deadband));moved.accept(id);
    }
    public ConverterUnit unit(long id){return topology.converter(id);}
    public Status status(long id){var u=topology.converter(id);if(u==null)return null;return u.mode()==ConverterUnit.Mode.OFF?Status.OFF:status.getOrDefault(id,Status.IDLE);}
    public Totals totals(long id){return totals.getOrDefault(id,Totals.NONE);}
    /** The Forge litres reserved for converter {@code id}'s slice in flight (0 once lapsed). */
    public double reserved(long id){var r=reserved.get(id);return r==null||r.until()<now.getAsLong()?0:r.litres();}
    /** Returns converter {@code id}'s account to its Forge tank in whole mB and drops its reservation: its island restated or held. */
    public void release(long id){reserved.remove(id);planned.remove(id);giveBack(id);}
    /**
     * At the engine's decision points: an account whose slice ended {@link #RESERVATION_TICKS} ago and never booked (its island
     * held, restated or fenced) goes back to its Forge tank, whole mB. Its last plan stays: a replayed span still books on it.
     */
    @Override public void decide() {
        if(byPort.isEmpty())return;long tick=now.getAsLong();
        for(var u:topology.converters().values()) {
            if(u.account()<1)continue;var p=planned.get(u.id());
            if(p==null||p.end()+RESERVATION_TICKS<tick){reserved.remove(u.id());giveBack(u.id());}
        }
    }
    private void giveBack(long id) {
        var u=topology.converter(id);if(u==null||u.account()<1)return;
        long whole=(long)Math.floor(u.account());
        if(!u.fluid().isEmpty()&&!u.fluid().equals(u.accountFluid()))return;
        long room=u.capacity()-u.stored();long back=Math.min(whole,room);if(back<=0)return;
        topology.converter(u.withTank(u.accountFluid(),u.stored()+back,u.remainder()).withAccount(u.accountFluid(),u.account()-back));
    }
    /** Whether a rule in force names Forge fluid {@code fluid} for a converter, closed or open: forward, or reverse to it. */
    private boolean named(String fluid,boolean closed) {
        var set=rules.get();if(set.forward(fluid,tags.apply(fluid),closed)!=null)return true;
        for(var r:set.rules())if(r.toForge()!=null&&r.fluid().equals(fluid)&&(closed||r.phase()==ConversionRule.Phase.LIQUID))return true;
        return false;
    }
    /** Converter {@code id}'s Forge tank. */
    public Tank tank(long id) {
        return new Tank() {
            public long capacity(){var u=topology.converter(id);return u==null?0:u.capacity();}
            public String fluid(){var u=topology.converter(id);return u==null?"":u.fluid();}
            public long stored(){var u=topology.converter(id);return u==null?0:u.stored();}
            public long fill(String fluid,long mB,boolean simulate) {
                var u=topology.converter(id);if(u==null||mB<=0||fluid.isEmpty())return 0;
                if(!u.fluid().isEmpty()&&!u.fluid().equals(fluid)||u.fluid().isEmpty()&&!named(fluid,u.closed()))return 0;
                long free=(long)Math.floor(u.capacity()-u.stored()-reserved(id)-u.remainder());
                long taken=Math.max(0,Math.min(mB,free));
                if(taken>0&&!simulate){topology.converter(u.withTank(fluid,u.stored()+taken,u.remainder()));moved.accept(id);}
                return taken;
            }
            public long drain(long mB,boolean simulate) {
                var u=topology.converter(id);if(u==null||mB<=0)return 0;
                long given=Math.min(mB,u.stored());
                if(given>0&&!simulate){topology.converter(u.withTank(u.fluid(),u.stored()-given,u.remainder()));moved.accept(id);}
                return given;
            }
            public boolean valid(String fluid) {
                var u=topology.converter(id);if(u==null||fluid.isEmpty())return false;
                return u.fluid().isEmpty()?named(fluid,u.closed()):u.fluid().equals(fluid);
            }
        };
    }

    // ---------------- the islands' ports (IslandCoordinator.Ports) ----------------

    @Override public boolean ready(long island,PassiveNetwork graph,long start){return true;}

    /** The fill ports whose islands must grow before they give their rule's stream (W3): queued edits, applied on the engine's tick. */
    private final Set<Long> growth=new LinkedHashSet<>();
    /**
     * Queues, through {@code submit}, an edit of every fill port whose forward rule names a compound its island does not carry
     * (its registration at its next revision, which recompiles its island with {@link #compounds}), as
     * {@link ColumnOperation#grow} does; a port with an event already queued waits for it.
     */
    public void grow(java.util.function.Consumer<List<WorldTopologyLedger.Edit>> submit) {
        if(growth.isEmpty())return;
        var waiting=List.copyOf(growth);growth.clear();
        for(long device:waiting) {
            var r=topology.latest().get(device);if(r==null||topology.pending(device))continue;
            submit.accept(List.of(new WorldTopologyLedger.Edit(device,new WorldTopologyLedger.Registration(r.device(),r.spec(),Math.addExact(r.revision(),1)))));
        }
    }
    /** The compounds fill port {@code device} may give: its forward rule's for the Forge tank's fluid, for its island's list. */
    public Set<String> compounds(long device) {
        Long id=byPort.get(device);var u=id==null?null:topology.converter(id);
        if(u==null||u.fill()!=device||u.mode()==ConverterUnit.Mode.OFF||u.mode()==ConverterUnit.Mode.TO_FORGE)return Set.of();
        var fluid=u.account()>0?u.accountFluid():u.fluid();if(fluid.isEmpty())return Set.of();
        var rule=rules.get().forward(fluid,tags.apply(fluid),u.closed());
        return rule==null?Set.of():new TreeSet<>(rule.toIChemE().composition().keySet());
    }

    @Override public Map<Long,PortPlan> plans(long island,PassiveNetwork graph,IslandClock.Slice slice) {
        if(byPort.isEmpty())return Map.of();
        Map<Long,PortPlan> plans=null;Set<Long> seen=null;
        for(var node:graph.reservoirs()) {
            Long id=byPort.get(node.id());if(id==null)continue;
            if(seen==null){seen=new HashSet<>();plans=new HashMap<>();}
            if(!seen.add(id))continue;
            var u=topology.converter(id);if(u==null)continue;
            plan(island,graph,slice,u,plans);
        }
        return plans==null?Map.of():plans;
    }
    /** Converter {@code u}'s two ports for the slice (plan 3.2, steps 1 to 4); the port it does not use is shut. */
    private void plan(long island,PassiveNetwork graph,IslandClock.Slice slice,ConverterUnit u,Map<Long,PortPlan> plans) {
        // A new plan supersedes the last: a rejected slice's reservation goes with it (plan section 5).
        reserved.remove(u.id());planned.remove(u.id());
        var model=models.apply(island);double dt=slice.seconds(time);
        int vessel=index(graph,u.id());
        Direction direction=Direction.NONE;Status s=Status.IDLE;PortPlan drain=null,fill=null;
        if(vessel<0||!(dt>0))s=Status.IDLE;
        else {
            var tank=graph.reservoirs().get(vessel);
            switch(u.mode()) {
                case OFF->s=Status.OFF;
                case TO_FORGE->direction=Direction.TO_FORGE;
                case TO_ICHEME->direction=Direction.TO_ICHEME;
                case BALANCE->{
                    double gap=fill(tank)-u.forgeFill();
                    if(Math.abs(gap)<=u.deadband())s=Status.BALANCED;else direction=gap>0?Direction.TO_FORGE:Direction.TO_ICHEME;
                }
            }
            if(direction==Direction.TO_FORGE) {var r=reverse(island,graph,vessel,u,model,dt,slice);drain=r.plan;s=r.status;}
            if(direction==Direction.TO_ICHEME) {var r=forward(graph,vessel,u,model,dt,slice);fill=r.plan;s=r.status;}
        }
        if(u.drain()!=0)plans.put(u.drain(),drain!=null?drain:new PortPlan(u.drain(),u.drainValve(),0,0,null));
        if(u.fill()!=0)plans.put(u.fill(),fill!=null?fill:new PortPlan(u.fill(),u.fillValve(),0,0,null));
        status.put(u.id(),s);
    }
    private record Outcome(PortPlan plan,Status status) {}
    /** Step 2: the drain port's void, on the rule that holds for the phase at its nozzle. */
    private Outcome reverse(long island,PassiveNetwork graph,int vessel,ConverterUnit u,FluidThermodynamics model,double dt,IslandClock.Slice slice) {
        var tank=graph.reservoirs().get(vessel);var state=tank.state();
        if(u.drain()==0)return new Outcome(null,Status.NO_RULE);
        int phase=drawnPhase(graph,vessel,u.drainValve(),state,model);
        var moles=FluidThermodynamics.phaseMoles(state,phase);double mass=model.phaseMass(state,phase),volume=FluidThermodynamics.phaseVolume(state,phase);
        if(tank.empty()||!(mass>0)||!(volume>0))return new Outcome(null,Status.SOURCE_EMPTY);
        var drawn=new ConversionRules.Drawn(model.components(),moles,model.molecularWeights(),state.temperature(),state.pressure(),phase==FluidThermodynamics.GAS);
        var set=rules.get();var rule=set.reverse(drawn,lastRule.get(u.id()),u.closed());
        if(rule==null){lastRule.remove(u.id());return new Outcome(null,offSpec(set,u)?Status.OFF_SPEC:Status.NO_RULE);}
        lastRule.put(u.id(),rule.id());
        if(!u.fluid().isEmpty()&&!u.fluid().equals(rule.fluid()))return new Outcome(null,Status.DESTINATION_FULL);
        double density=mass/volume,room=u.capacity()-u.stored()-u.remainder();
        if(room<LEAST_LITRES)return new Outcome(null,Status.DESTINATION_FULL);
        double heel=Math.max(LEAST_LITRES,HEEL*tank.inventory().volume()*ConversionLaws.LITRES_PER_M3),held=volume*ConversionLaws.LITRES_PER_M3-heel;
        if(held<LEAST_LITRES)return new Outcome(null,Status.SOURCE_EMPTY);
        double litres=Math.min(Math.min(u.maxRate()*dt,room),held);
        if(!(litres>0))return new Outcome(null,Status.IDLE);
        double target=ConversionLaws.kilograms(litres,density)/dt;
        reserved.put(u.id(),new Reserved(ConversionLaws.litres(target*dt,density),slice.endTick()+RESERVATION_TICKS));
        planned.put(u.id(),new Planned(Direction.TO_FORGE,rule.id(),rule.fluid(),density,null,slice.endTick()));
        return new Outcome(new PortPlan(u.drain(),u.drainValve(),target,0,null),Status.CONVERTING);
    }
    /** Whether a reverse rule exists for the Forge tank's fluid (or any, for an empty tank): its ranges then do not hold (OFF_SPEC). */
    private static boolean offSpec(ConversionRules set,ConverterUnit u) {
        for(var r:set.rules())if(r.toForge()!=null&&(u.fluid().isEmpty()||r.fluid().equals(u.fluid()))&&(u.closed()||r.phase()==ConversionRule.Phase.LIQUID))return true;
        return false;
    }
    /** Step 3: the fill port's generator, on the rule for the Forge tank's fluid, bounded by the account. */
    private Outcome forward(PassiveNetwork graph,int vessel,ConverterUnit u,FluidThermodynamics model,double dt,IslandClock.Slice slice) {
        if(u.fill()==0)return new Outcome(null,Status.NO_RULE);
        var fluid=u.account()>0?u.accountFluid():u.fluid();
        if(fluid.isEmpty())return new Outcome(null,Status.SOURCE_EMPTY);
        var rule=rules.get().forward(fluid,tags.apply(fluid),u.closed());
        if(rule==null)return new Outcome(null,Status.NO_RULE);
        var per=perLitre(rule,model);
        // A rule naming a compound the island does not carry waits for its island to grow (W3), as a column outlet's does.
        if(per==null){growth.add(u.fill());return new Outcome(null,Status.NO_RULE);}
        var tank=graph.reservoirs().get(vessel);
        double free=(tank.inventory().volume()-FluidThermodynamics.liquidStreamVolume(tank.state()))*ConversionLaws.LITRES_PER_M3;
        double want=Math.min(u.maxRate()*dt,free);
        if(free<LEAST_LITRES)return new Outcome(null,Status.DESTINATION_FULL);
        // Whole mB out of the Forge tank into the account, enough for the slice.
        long take=Math.max(0,Math.min(u.stored(),(long)Math.ceil(want-u.account())));
        if(take>0&&!u.fluid().equals(fluid))take=0;
        if(take>0){u=u.withTank(u.fluid(),u.stored()-take,u.remainder()).withAccount(fluid,u.account()+take);topology.converter(u);}
        double litres=Math.min(want,u.account());
        if(litres<LEAST_LITRES&&u.stored()==0)return new Outcome(null,Status.SOURCE_EMPTY);
        if(!(litres>0))return new Outcome(null,Status.IDLE);
        planned.put(u.id(),new Planned(Direction.TO_ICHEME,rule.id(),fluid,per.density(),per,slice.endTick()));
        double target=ConversionLaws.kilograms(litres,per.density())/dt;
        return new Outcome(new PortPlan(u.fill(),u.fillValve(),target,rule.toIChemE().temperature(),per.moles(litres)),Status.CONVERTING);
    }
    /** A forward rule's stream per litre on {@code model}'s list: a flash once per rule revision and list. */
    private ConversionLaws.PerLitre perLitre(ConversionRule rule,FluidThermodynamics model) {
        var key=rule.id()+"@"+rule.revision()+"#"+model.components();
        if(perLitre.containsKey(key))return perLitre.get(key);
        var per=ConversionLaws.perLitre(rule.toIChemE(),model);perLitre.put(key,per);return per;
    }
    /** The phase a port behind {@code valve} draws from vessel node {@code vessel}: the gas at a vapour nozzle, else the heavier liquid (plan 5: the drawn phase, not the tank). */
    private static int drawnPhase(PassiveNetwork graph,int vessel,long valve,FluidThermodynamics.State state,FluidThermodynamics model) {
        var port=PassiveNetwork.PhasePort.LIQUID;
        for(var p:graph.pipes()) {
            if(p.first()==vessel&&graph.reservoirs().get(p.second()).id()==valve){port=p.firstPort();break;}
            if(p.second()==vessel&&graph.reservoirs().get(p.first()).id()==valve){port=p.secondPort();break;}
        }
        if(port==PassiveNetwork.PhasePort.VAPOR||!FluidThermodynamics.holdsLiquid(state))return FluidThermodynamics.GAS;
        return model.heavierLiquid(state);
    }
    /** A vessel's fill fraction: its condensed volume over its volume. */
    private static double fill(PassiveNetwork.Reservoir tank){return tank.inventory().volume()>0?FluidThermodynamics.liquidStreamVolume(tank.state())/tank.inventory().volume():0;}
    private static int index(PassiveNetwork graph,long id){for(int i=0;i<graph.reservoirs().size();i++)if(graph.reservoirs().get(i).id()==id)return i;return -1;}

    /** What the island's converter ports gave and took over {@code start} to {@code end} (a solved or replayed span). */
    @Override public void booked(long island,PassiveNetwork graph,long start,long end,PassiveIntervalSolver.Result result) {
        if(byPort.isEmpty()||end<=start)return;
        double[] weights=null;
        for(var b:result.boundaries()) {
            if(b.kind()!=ConservativeTransport.BoundaryTransfer.Kind.EXCHANGE)continue;
            Long id=byPort.get(b.nodeId());if(id==null)continue;
            var u=topology.converter(id);if(u==null)continue;
            if(weights==null)weights=models.apply(island).molecularWeights();
            var n=b.moles();double mass=0;for(int i=0;i<n.length;i++)mass+=n[i]*weights[i];
            if(b.nodeId()==u.drain())drained(u,-mass,-b.totalEnergyJoule());
            else givenIn(u,mass,b.totalEnergyJoule());
        }
    }
    /** Reverse: {@code mass} kg left through the drain port; its litres join the remainder, whole mB go into the tank. */
    private void drained(ConverterUnit u,double mass,double energy) {
        reserved.remove(u.id());
        if(!(mass>0))return;
        var p=planned.get(u.id());
        if(p==null||p.direction()!=Direction.TO_FORGE){IChemE.LOGGER.error("converter status=UNPLANNED unit={} drained_kg={}",u.id(),mass);return;}
        double litres=ConversionLaws.litres(mass,p.density()),remainder=u.remainder()+litres;
        var fluid=u.fluid().isEmpty()?p.fluid():u.fluid();long stored=u.stored();
        if(fluid.equals(p.fluid())){long whole=Math.min((long)Math.floor(remainder),u.capacity()-stored);if(whole>0){stored+=whole;remainder-=whole;}}
        topology.converter(u.withTank(fluid,stored,remainder));
        totals.merge(u.id(),new Totals(mass,energy,litres,0,0,0),Totals::plus);
        moved.accept(u.id());
    }
    /** Forward: {@code mass} kg came in through the fill port; its litres leave the account, the rest goes back in whole mB. */
    private void givenIn(ConverterUnit u,double mass,double energy) {
        var p=planned.get(u.id());
        double litres=0;
        if(mass>0) {
            if(p==null||p.direction()!=Direction.TO_ICHEME){IChemE.LOGGER.error("converter status=UNPLANNED unit={} given_kg={}",u.id(),mass);return;}
            litres=ConversionLaws.litres(mass,p.density());
        }
        double account=u.account()-litres;
        if(account<0) {
            // More than the account (a replayed span): the rest comes out of the Forge tank, whole mB up.
            long owed=Math.min(u.stored(),(long)Math.ceil(-account));
            u=u.withTank(u.fluid(),u.stored()-owed,u.remainder());account+=owed;
            if(account<-1e-9)IChemE.LOGGER.error("converter status=OVERDRAWN unit={} litres={}",u.id(),-account);
            account=Math.max(0,account);
        }
        var fluid=!u.accountFluid().isEmpty()?u.accountFluid():p!=null?p.fluid():u.fluid();u=u.withAccount(fluid,account);
        // The unconverted part goes back to the Forge tank, whole mB; the fraction waits in the account.
        long back=Math.min((long)Math.floor(u.account()),u.capacity()-u.stored());
        if(back>0&&(u.fluid().isEmpty()||u.fluid().equals(fluid)))u=u.withTank(fluid,u.stored()+back,u.remainder()).withAccount(fluid,u.account()-back);
        topology.converter(u);
        if(litres>0){totals.merge(u.id(),new Totals(0,0,0,mass,energy,litres),Totals::plus);moved.accept(u.id());}
    }
}
