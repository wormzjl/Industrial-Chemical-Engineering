package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.equipment.HeatSides;
import com.wormzjl.icheme.science.fluid.network.Burners;
import com.wormzjl.icheme.science.fluid.network.ConservativeTransport;
import com.wormzjl.icheme.science.fluid.network.FiredCoil;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.network.PassiveIntervalSolver;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.network.ShaftPlan;
import java.util.*;

/**
 * The fired heaters' burners (batch 2026-11-09-furnace-fuel-power, FURNACE_FUEL_POWER_PLAN.md): the coupling between a
 * radiant coil's island and its fuel's, which never share a fluid. Arithmetic on the server thread only, as the shaft
 * trains' and the electric stores' hooks are:
 * <ul>
 * <li>at each commit or replay of a radiant coil, its <em>demand</em>: the heat it would take to hold its set point at its
 * design duty, W, read from the slice it solved (its heat where it reached the set point, its available heat over its
 * firing share where it fell short, its design duty where it had no fuel or air to try; 0 for no flow or an inlet at the set
 * point). It never reads the capped heat as the demand, so a short slice cannot ratchet the fuel down;</li>
 * <li>at each commit or replay of the fuel's island, the heat the burners fired, W: the mass flow into the firebox over the
 * span times the fuel's lower heating value per kilogram ({@link Burners#perKilogram}, the material records'
 * {@code combustion} sub-records), and the combustion-air fans' draw at that firing, booked on the burners' electric store;</li>
 * <li>at each dispatch, the slice inputs: the fuel valve's mass target, the demand's fuel over its heating value with
 * {@link Burners#MARGIN}, 0 with the burners out or unpowered ({@link ShaftPlan.Kind#FUEL}); the radiant coil's limit, the
 * share of its duty the fired heat allows ({@link Burners#limit}), 0 unpowered ({@link ShaftPlan.Kind#BURNERS});</li>
 * <li>a heater whose demand or fired heat moved wakes the other island ({@link IslandCoordinator#shaftsMoved});</li>
 * <li>its alarms ({@link #alarms}): {@code NO POWER}, {@code NO FUEL}, {@code LOW FUEL}, {@code FUEL NOT COMBUSTIBLE}.</li>
 * </ul>
 * All of it is state: a load starts every heater at no demand and no fuel, and the first slices light it again.
 */
public final class FiredHeaters {
    /** A moved demand or fired heat wakes the other island past this share (plus 1 W). */
    static final double WAKE=1e-2;
    /** A heater as its page shows it: its radiant demand, the fuel asked for and fired (W), the fuel's mass flow (kg/s) and heat (J/kg), the fans' draw (W), its alarms. */
    public record View(double demand,double asked,double fired,double fuelFlow,double heatingValue,double fanDraw,List<EquipmentAlarm> alarms) {}

    /**
     * A heater's flue for its presentation (batch 2026-11-10-custom-shader-system, W5): the heat fired (W), the fuel's mass flow
     * (kg/s), the flue gas's water (kg per kg of dry flue gas, {@link Burners#waterShare}) and the fuel's phase, as its last
     * fuel slice burnt them; methane's water and {@code GAS} before any fuel arrived.
     */
    public record Flue(double fired,double fuelFlow,double waterShare,Burners.Fuel fuel) {
        /** The flue gas, kg/s: the fuel and its combustion air ({@link Burners#flueFlow}). */
        public double flueFlow(){return Burners.flueFlow(fuelFlow,fired);}
    }

    private static final class Heater {
        final long burners;
        long radiant;
        double demand,fired,flow,perKilogram;
        double water=Burners.METHANE_WATER;
        Burners.Fuel fuel=Burners.Fuel.GAS;
        boolean demandSeen,firedSeen;
        Long radiantIsland,fuelIsland;
        Heater(long burners){this.burners=burners;}
    }

    private final WorldTopologyLedger topology;
    private final IslandCoordinator coordinator;
    private final ElectricStores electric;
    private final double[] heatingValues,molarMasses;
    /** The formulas, atoms of C, H, N, O, S per molecule, for the flue's water; null: methane's. */
    private final double[][] formulas;
    private final Map<Long,Heater> heaters=new TreeMap<>();
    /** The burners of each radiant coil: rebuilt on the cadence and when a lookup misses. */
    private final Map<Long,Long> byRadiant=new HashMap<>();

    /**
     * @param heatingValues the network's lower heating values, J/mol, in its conserved order ({@link com.wormzjl.icheme.science.material.MaterialCatalog#heatingValues})
     * @param molarMasses   its molar masses, kg/mol, in the same order
     */
    public FiredHeaters(WorldTopologyLedger topology,IslandCoordinator coordinator,ElectricStores electric,double[] heatingValues,double[] molarMasses) {
        this(topology,coordinator,electric,heatingValues,molarMasses,null);
    }
    /** @param formulas the formulas in the same order ({@link com.wormzjl.icheme.science.material.MaterialCatalog#formulas}), or null: every flue takes methane's water */
    public FiredHeaters(WorldTopologyLedger topology,IslandCoordinator coordinator,ElectricStores electric,double[] heatingValues,double[] molarMasses,double[][] formulas) {
        this.topology=Objects.requireNonNull(topology);this.coordinator=Objects.requireNonNull(coordinator);this.electric=Objects.requireNonNull(electric);
        if(heatingValues.length!=molarMasses.length||formulas!=null&&formulas.length!=molarMasses.length)throw new IllegalArgumentException("Heating values, molar masses and formulas disagree");
        this.heatingValues=heatingValues.clone();this.molarMasses=molarMasses.clone();this.formulas=formulas==null?null:formulas.clone();
    }

    /** Follows the registrations: a heater for every burners registration, keyed by it; a gone one leaves. On the cadence and on a missed lookup. */
    void sync() {
        var present=new HashSet<Long>();byRadiant.clear();
        for(var r:topology.active().values()){var d=r.device();if(!d.burner())continue;present.add(d.id());byRadiant.put(d.heat().partner(),d.id());
            heaters.computeIfAbsent(d.id(),Heater::new).radiant=d.heat().partner();}
        heaters.keySet().retainAll(present);
    }
    private Heater ofBurners(long id) {
        var h=heaters.get(id);if(h!=null)return h;
        var r=topology.active().get(id);if(r==null||!r.device().burner())return null;
        sync();return heaters.get(id);
    }
    private Heater ofRadiant(long id) {
        Long burners=byRadiant.get(id);
        if(burners==null){sync();burners=byRadiant.get(id);}
        return burners==null?null:heaters.get(burners);
    }

    /** Whether heater {@code burners}' burners are lit: its radiant coil's burners switched on. */
    private boolean lit(Heater h) {
        var coil=topology.active().get(h.radiant);
        return coil!=null&&coil.device().heat()!=null&&coil.device().heat().control().share()>0;
    }

    /** The fuel the heater asks for, kg/s: its demand's fuel over the fuel's heat, 0 with its burners out or unpowered. */
    private double target(Heater h) {
        if(!lit(h)||!electric.powered(h.burners)||!(h.demand>0))return 0;
        // Until fuel has arrived, its heat is unknown: the valve asks for the demand's fuel on methane's heat (50 MJ/kg).
        double perKilogram=h.perKilogram>0?h.perKilogram:5e7;
        return Burners.fired(h.demand)*Burners.MARGIN/perKilogram;
    }

    /** Each heater's slice inputs at dispatch (reads only): its fuel valve's target, its radiant coil's limit. */
    void plans(PassiveNetwork graph,Map<Long,ShaftPlan> out) {
        for(var pipe:graph.pipes()) {
            if(pipe.control() instanceof FlowControl.ControlValve valve&&valve.source()==FlowControl.ValveSource.BURNER) {
                var h=ofBurners(graph.reservoirs().get(pipe.second()).id());
                if(h!=null)out.put(h.burners,new ShaftPlan(ShaftPlan.Kind.FUEL,target(h)));
            } else if(pipe.heat() instanceof FiredCoil coil) {
                long id=graph.reservoirs().get(pipe.first()).id();var h=ofRadiant(id);
                if(h!=null)out.put(id,new ShaftPlan(ShaftPlan.Kind.BURNERS,electric.powered(h.burners)?Burners.limit(h.fired,coil.duty()):0));
            }
        }
    }

    /** A committed or replayed span of {@code island}: each radiant coil's demand and each burners' fired heat in it, the fans' draw. */
    void booked(long island,long start,long end,PassiveIntervalSolver.Result result) {
        if(end<=start)return;
        double seconds=(end-start)/20.0;var solved=result.graph();var average=result.averageMassFlows();
        Map<Long,Double> heat=null;
        var wake=new TreeSet<Long>();
        for(int e=0;e<solved.pipes().size();e++) {
            var pipe=solved.pipes().get(e);
            if(pipe.control() instanceof FlowControl.ControlValve valve&&valve.source()==FlowControl.ValveSource.BURNER) {
                var h=ofBurners(solved.reservoirs().get(pipe.second()).id());if(h==null)continue;
                var donor=solved.reservoirs().get(pipe.first());
                double flow=Math.max(0,average[e]),perKilogram=Burners.perKilogram(donor.inventory().moles(),molarMasses,heatingValues);
                double fired=flow*perKilogram,before=h.fired;
                h.flow=flow;h.perKilogram=perKilogram>0||flow>0?perKilogram:h.perKilogram;h.fired=fired;h.fuelIsland=island;h.firedSeen=true;
                // The flue's water and the fuel's phase, for its presentation (W5): what the fuel now at the valve burns to.
                if(flow>0){var moles=donor.inventory().moles();h.water=formulas==null?Burners.METHANE_WATER:Burners.waterShare(moles,formulas);h.fuel=phase(donor);}
                electric.bookedBurners(h.burners,ElectricStores.burnerDraw(topology.active().get(h.burners),fired),start,end);
                if(moved(before,fired)&&h.radiantIsland!=null)wake.add(h.radiantIsland);
            } else if(pipe.heat() instanceof FiredCoil coil) {
                long id=solved.reservoirs().get(pipe.first()).id();var h=ofRadiant(id);if(h==null)continue;
                if(heat==null){heat=new HashMap<>();for(var b:result.boundaries())if(b.kind()==ConservativeTransport.BoundaryTransfer.Kind.HEAT)heat.merge(b.nodeId(),b.totalEnergyJoule(),Double::sum);}
                double rate=heat.getOrDefault(pipe.id(),0.)/seconds,flow=Math.abs(average[e]);
                var inlet=solved.reservoirs().get(average[e]>=0?pipe.first():pipe.second()).state();
                double demand;
                if(coil.firing()>0&&coil.firing()<1)demand=coil.available()/coil.firing();
                else if(coil.firing()==1)demand=Math.max(0,rate);
                else demand=flow>0&&inlet.temperature()<coil.setTemperature()-.05&&coil.duty()>0?coil.duty():0;
                demand=Math.min(coil.duty(),demand);
                double before=h.demand;h.demand=demand;h.radiantIsland=island;h.demandSeen=true;
                if(moved(Burners.fired(before),Burners.fired(demand))&&h.fuelIsland!=null)wake.add(h.fuelIsland);
            }
        }
        wake.remove(island);
        if(!wake.isEmpty())coordinator.shaftsMoved(wake);
    }

    private static boolean moved(double before,double after){return (before>0)!=(after>0)||Math.abs(after-before)>WAKE*Math.max(before,after)+1;}

    /** A powered unit changed: its heater's islands read the new plans (the store's cadence wakes the fuel island only). */
    void powerMoved(long burners) {
        var h=heaters.get(burners);if(h==null)return;
        var wake=new ArrayList<Long>();if(h.radiantIsland!=null)wake.add(h.radiantIsland);if(h.fuelIsland!=null)wake.add(h.fuelIsland);
        if(!wake.isEmpty())coordinator.shaftsMoved(wake);
    }

    /** Heater {@code burners}' active alarms, in their order. */
    public List<EquipmentAlarm> alarms(long burners) {
        var h=ofBurners(burners);if(h==null)return List.of();
        var out=new ArrayList<EquipmentAlarm>();
        if(!lit(h))return out;
        if(!electric.powered(h.burners)){out.add(EquipmentAlarm.NO_POWER);return out;}
        double asked=Burners.fired(h.demand);
        if(!(asked>0)||!h.demandSeen)return out;
        if(h.flow>0&&h.perKilogram==0)out.add(EquipmentAlarm.NOT_COMBUSTIBLE);
        else if(!(h.fired>0))out.add(EquipmentAlarm.NO_FUEL);
        else if(h.fired<Burners.SHORT*asked)out.add(EquipmentAlarm.LOW_FUEL);
        return out;
    }

    /** Every heater with an active alarm, by its burners' identity. */
    public Map<Long,List<EquipmentAlarm>> alarms() {
        var out=new TreeMap<Long,List<EquipmentAlarm>>();
        for(long id:heaters.keySet()){var a=alarms(id);if(!a.isEmpty())out.put(id,a);}
        return out;
    }

    /** The burners of radiant coil {@code radiant}, or 0. */
    public long burnersOf(long radiant){var h=ofRadiant(radiant);return h==null?0:h.burners;}

    /** The phase a burner's fuel arrives in: solids carried with it, else liquid where its liquid moles outweigh its vapour's. */
    static Burners.Fuel phase(PassiveNetwork.Reservoir donor) {
        if(!donor.inventory().solids().empty()||!donor.state().solids().empty())return Burners.Fuel.SOLID;
        var s=donor.state();double liquid=s.waterLiquid(),vapor=s.waterVapor();
        for(double n:s.liquid())liquid+=Math.max(0,n);
        for(double n:s.vapor())vapor+=Math.max(0,n);
        return liquid>vapor?Burners.Fuel.LIQUID:Burners.Fuel.GAS;
    }

    /** Heater {@code burners}' flue as its last fuel slice burnt (a read), or null for none. */
    public Flue flue(long burners) {
        var h=ofBurners(burners);if(h==null)return null;
        return new Flue(h.fired,h.flow,h.water,h.fuel);
    }

    /** Heater {@code burners} as its page shows it, or null for none. */
    public View view(long burners) {
        var h=ofBurners(burners);if(h==null)return null;
        return new View(h.demand,Burners.fired(h.demand),h.fired,h.flow,h.perKilogram,ElectricStores.burnerDraw(topology.active().get(h.burners),h.fired),alarms(burners));
    }

    /** The design fuel of burners registration {@code r}, W: its radiant coil's duty at full firing. */
    static double designFired(WorldTopologyLedger.Registration r) {
        var heat=r==null?null:r.device().heat();
        return heat==null?0:Burners.fired(HeatSides.coilDuty(HeatSides.Side.RADIANT,heat.area(),1));
    }
}
