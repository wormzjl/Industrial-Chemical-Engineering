package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.fluid.network.Flare;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.network.PassiveIntervalSolver;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.network.ShaftPlan;
import java.util.*;

/**
 * The flare stacks (batch 2026-11-09-flare-stack, FLARE_STACK_PLAN.md): arithmetic on the server thread on the shaft-train
 * hooks, as the burners'. A flare's tip is a void behind its gas inlet; the run into it carries its tip limit.
 * <ul>
 * <li>At each dispatch, the tip limit's mass target ({@link ShaftPlan.Kind#FLARE}): the most the flare passes of the gas
 * now at the run's other end ({@link Flare#limit}: its rated heat, its stable exit velocity), 0 while that gas carries solids
 * (owner X5: blocked while solids arrive).</li>
 * <li>At each commit or replay, what it received: the mean mass flow, the gas's net heating value and flame temperature,
 * the heat it released (none below {@link Flare#FLAME_LIMIT}: the gas leaves unburnt, owner X2), and its status.</li>
 * </ul>
 * All of it is state: a load starts every flare at no flow, and its first slice reads it again.
 */
public final class Flares {
    /** A flare's status, by name; its text is the status word. */
    public enum Status {
        NO_FLOW("NO FLOW"), FLARING("FLARING"), FLAME_OUT("FLAME OUT"), OVER_CAPACITY("OVER CAPACITY"), BLOCKED("BLOCKED");
        public final String text;
        Status(String text){this.text=text;}
    }
    /** A flow within this share of the limit is held back by it (OVER CAPACITY). */
    static final double HELD=.99;
    /** Below this mass flow, kg/s, nothing flows. */
    static final double NO_FLOW=1e-6;
    /**
     * A flare as its page and the data network show it: the mean mass flow (kg/s), the heat released (W), the gas's net
     * heating value (J/scm) and flame temperature (K), the most it passes now (kg/s), its rating (W) and its status.
     */
    public record View(double massFlow,double heat,double heatingValue,double flameTemperature,double limit,double rated,Status status) {
        public List<EquipmentAlarm> alarms() {
            return switch(status){case FLAME_OUT->List.of(EquipmentAlarm.FLAME_OUT);case OVER_CAPACITY->List.of(EquipmentAlarm.OVER_CAPACITY);case BLOCKED->List.of(EquipmentAlarm.BLOCKED);default->List.of();};
        }
    }

    private final WorldTopologyLedger topology;
    private final double[] heatingValues,molarMasses;
    private final double[][] formulas;
    private final Map<Long,View> views=new HashMap<>();
    private final Map<Long,Double> limits=new HashMap<>();

    /**
     * @param heatingValues the network's lower heating values, J/mol, in its conserved order
     * @param molarMasses   its molar masses, kg/mol
     * @param formulas      its formulas, atoms of C, H, N, O, S per molecule ({@link com.wormzjl.icheme.science.material.MaterialCatalog#formulas})
     */
    public Flares(WorldTopologyLedger topology,double[] heatingValues,double[] molarMasses,double[][] formulas) {
        this.topology=Objects.requireNonNull(topology);
        if(heatingValues.length!=molarMasses.length||formulas.length!=molarMasses.length)throw new IllegalArgumentException("Heating values, molar masses and formulas disagree");
        this.heatingValues=heatingValues.clone();this.molarMasses=molarMasses.clone();this.formulas=formulas.clone();
    }

    /** The tip diameter of flare {@code id}, m (its device's geometry), or 0 for none. */
    private double tip(long id) {
        var r=topology.active().get(id);
        return r==null||!r.device().flare()?0:r.device().geometry().diameter();
    }

    /** The gas at {@code donor}, at the tip of {@code tip}, and whether solids arrive with it. */
    private record Gas(Flare.Gas gas,boolean solids) {}
    private Gas gas(PassiveNetwork.Reservoir donor,double tip) {
        boolean solids=!donor.inventory().solids().empty()||!donor.state().solids().empty();
        return new Gas(Flare.gas(donor.inventory().moles(),molarMasses,heatingValues,formulas,donor.state().temperature(),tip),solids);
    }

    /** Each flare's tip limit at dispatch (reads only). */
    void plans(PassiveNetwork graph,Map<Long,ShaftPlan> out) {
        sync();
        for(var pipe:graph.pipes()) {
            if(!(pipe.control() instanceof FlowControl.ControlValve valve)||valve.source()!=FlowControl.ValveSource.FLARE)continue;
            long id=graph.reservoirs().get(pipe.second()).id();double tip=tip(id);if(tip<=0)continue;
            var gas=gas(graph.reservoirs().get(pipe.first()),tip);
            double limit=gas.solids()?0:gas.gas().limit();
            limits.put(id,limit);
            out.put(id,new ShaftPlan(ShaftPlan.Kind.FLARE,limit));
        }
    }

    /** A committed or replayed span of an island: each flare's flow, heat and status. */
    void booked(long start,long end,PassiveIntervalSolver.Result result) {
        if(end<=start)return;
        var solved=result.graph();var average=result.averageMassFlows();
        for(int e=0;e<solved.pipes().size();e++) {
            var pipe=solved.pipes().get(e);
            if(!(pipe.control() instanceof FlowControl.ControlValve valve)||valve.source()!=FlowControl.ValveSource.FLARE)continue;
            long id=solved.reservoirs().get(pipe.second()).id();double tip=tip(id);if(tip<=0)continue;
            var gas=gas(solved.reservoirs().get(pipe.first()),tip);
            double flow=Math.max(0,average[e]),limit=limits.getOrDefault(id,gas.solids()?0:gas.gas().limit());
            boolean burns=gas.gas().burns();
            Status status;
            if(gas.solids()&&flow<NO_FLOW)status=Status.BLOCKED;
            else if(flow<NO_FLOW)status=Status.NO_FLOW;
            else if(!burns)status=Status.FLAME_OUT;
            else if(flow>=HELD*limit)status=Status.OVER_CAPACITY;
            else status=Status.FLARING;
            var g=gas.gas();views.put(id,new View(flow,burns?flow*g.perKilogram():0,g.nhv(),g.flame(),limit,Flare.rated(tip),status));
        }
    }

    /** Flare {@code id} as its page shows it: its last booked span, else no flow. Null for no flare. */
    public View view(long id) {
        double tip=tip(id);if(tip<=0)return null;
        var v=views.get(id);
        return v!=null?v:new View(0,0,0,Flare.AMBIENT,limits.getOrDefault(id,0.),Flare.rated(tip),Status.NO_FLOW);
    }

    /** Flare {@code id}'s active alarms. */
    public List<EquipmentAlarm> alarms(long id) {
        var v=view(id);return v==null?List.of():v.alarms();
    }

    /** Forgets flares that left: at each dispatch. */
    void sync() {
        views.keySet().removeIf(id->tip(id)<=0);limits.keySet().removeIf(id->tip(id)<=0);
    }
}
