package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.fluid.network.Chimney;
import com.wormzjl.icheme.science.fluid.network.PassiveIntervalSolver;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import java.util.*;

/**
 * The chimneys (batch 2026-11-10-chimney, CHIMNEY_PLAN.md): read-only arithmetic on each committed or replayed span, on
 * the server thread on the shaft trains' booking hook, as the flares'. A chimney's tip is a passive void at its stack's
 * top; what flows into it leaves the island there, its gas and its solids booked as the void's exchange (owner C4 A:
 * released to the atmosphere). Each span gives its mean mass flow, the solids it carries (the donor's share), the gas's
 * temperature, molar mass and water, its exit velocity and the smoke's opacity ({@link Chimney#opacity}), and its status
 * ({@link Chimney#status}). All of it is state: a load starts every chimney at no flow.
 */
public final class Chimneys {
    /**
     * A chimney as its page, the data network and the effects show it: the mean mass flow and solids flow (kg/s), the gas's
     * temperature (K) and water per dry gas (kg/kg), its exit velocity (m/s), the smoke's opacity, its draft over the stack
     * (Pa) and its status.
     */
    public record View(double massFlow,double solids,double temperature,double waterShare,double exitVelocity,double opacity,double draft,Chimney.Status status) {
        public static final View NONE=new View(0,0,298.15,0,0,0,0,Chimney.Status.NO_FLOW);
        public List<EquipmentAlarm> alarms() {
            return switch(status){case ABOVE_RATING->List.of(EquipmentAlarm.OVER_CAPACITY);case NO_DRAFT->List.of(EquipmentAlarm.NO_DRAFT);default->List.of();};
        }
        /** The status line's words: the status, the flow in t/h and the solids released in kg/h (owner C4 A). */
        public String line() {
            if(status==Chimney.Status.NO_FLOW||status==Chimney.Status.NO_DRAFT)return status.text;
            return String.format(Locale.ROOT,"%s %.2f t/h, solids %.2f kg/h",status.text,massFlow*3.6,solids*3600);
        }
    }

    private final WorldTopologyLedger topology;
    private final double[] molarMasses;
    private final Map<Long,View> views=new HashMap<>();

    /** @param molarMasses the network's molar masses, kg/mol, in its conserved order (water last) */
    public Chimneys(WorldTopologyLedger topology,double[] molarMasses) {
        this.topology=Objects.requireNonNull(topology);this.molarMasses=molarMasses.clone();
    }

    /** Chimney {@code id}'s registration, or null for none. */
    private PhysicalFluidTopology.Device device(long id) {
        var r=topology.active().get(id);
        return r==null||!r.device().chimney()?null:r.device();
    }

    /** The form a chimney's facing names: UP a steel stack, DOWN a brick chimney ({@code FluidWorldAuthority.placeChimney}). */
    public static Chimney.Form form(PhysicalFluidTopology.Device device) {
        return device.facing()==PhysicalFluidTopology.Direction.DOWN?Chimney.Form.BRICK:Chimney.Form.STEEL;
    }

    /** A committed or replayed span of an island: each chimney's flow, gas and status. */
    void booked(long start,long end,PassiveIntervalSolver.Result result) {
        if(end<=start)return;
        var solved=result.graph();var average=result.averageMassFlows();
        for(int e=0;e<solved.pipes().size();e++) {
            var pipe=solved.pipes().get(e);
            for(int side=0;side<2;side++) {
                var tip=solved.reservoirs().get(side==0?pipe.second():pipe.first());
                if(tip.kind()!=PassiveNetwork.NodeKind.VOID)continue;
                var device=device(tip.id());if(device==null)continue;
                var donor=solved.reservoirs().get(side==0?pipe.first():pipe.second());
                double flow=Math.max(0,side==0?average[e]:-average[e]);
                views.put(tip.id(),view(device,donor,tip,flow));
            }
        }
    }

    private View view(PhysicalFluidTopology.Device device,PassiveNetwork.Reservoir donor,PassiveNetwork.Reservoir tip,double flow) {
        var state=donor.state();double fluid=state.mass(),solidMass=state.solids().massKg();
        double solids=fluid>0?flow*solidMass/fluid:0;
        double d=device.geometry().diameter(),height=device.geometry().length();
        var reading=Chimney.reading(donor.inventory().moles(),molarMasses,state.temperature(),flow,solids,d,tip.state().pressure());
        boolean gas=!donor.empty()&&fluid>0;
        var status=Chimney.status(form(device),reading,gas);
        return new View(reading.massFlow(),reading.solids(),reading.temperature(),reading.waterShare(),reading.exitVelocity(),reading.opacity(),Chimney.draft(reading.density(),height),status);
    }

    /** Chimney {@code id} as its page shows it: its last booked span, else no flow. Null for no chimney. */
    public View view(long id) {
        if(device(id)==null)return null;
        return views.getOrDefault(id,View.NONE);
    }

    /** Chimney {@code id}'s active alarms. */
    public List<EquipmentAlarm> alarms(long id) {
        var v=view(id);return v==null?List.of():v.alarms();
    }

    /** Forgets chimneys that left: at each booking hook's dispatch. */
    void sync() {
        views.keySet().removeIf(id->device(id)==null);
    }
}
