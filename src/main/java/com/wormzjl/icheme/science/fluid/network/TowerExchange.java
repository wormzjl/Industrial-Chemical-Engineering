package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Common atmospheric boundary for Newton, conservative reconstruction and accepted presentation. */
public final class TowerExchange {
    private TowerExchange() {}
    /** Every tower's rates at {@code states} and {@code flows}, by node identity. */
    static Map<Long,Rates> readings(PassiveNetwork graph,List<FluidThermodynamics.State> states,double[] flows,FluidThermodynamics model){
        Map<Long,Rates> result=null;
        for(int node=0;node<graph.reservoirs().size();node++)if(graph.reservoirs().get(node).inventory().tower()!=null){
            if(result==null)result=new HashMap<>();
            result.put(graph.reservoirs().get(node).id(),at(graph,node,states,flows,model));
        }
        return result==null?Map.of():result;
    }
    static Map<Long,Rates> atRest(PassiveNetwork graph,FluidThermodynamics model){
        boolean tower=false;for(var node:graph.reservoirs())tower|=node.inventory().tower()!=null;
        if(!tower)return Map.of();
        return readings(graph,graph.reservoirs().stream().map(PassiveNetwork.Reservoir::state).toList(),new double[graph.pipes().size()],model);
    }
    /**
     * The components and energy that leave through the fill's result and the basin surface; only the fill exchanges mass.
     * {@code inletFlow}: the liquid the spray brings, kg/s; its gas and solids pass to the basin, whose vent takes the gas.
     */
    public record Rates(TowerTransfer.Result fill,double surfaceWatt,double inletTemperature,double inletFlow,String status) {
        public Rates {
            Objects.requireNonNull(fill);Objects.requireNonNull(status);
            for(double value:new double[]{surfaceWatt,inletTemperature,inletFlow})
                if(!Double.isFinite(value))throw new IllegalArgumentException("Nonfinite tower reading");
        }
        /** Component {@code c}'s loss to the air, kg/s (a gain negative). */
        public double loss(int c){return fill.loss(c);}
        /** The net mass the liquid loses to the air, kg/s. */
        public double massLoss(){return fill.evaporation();}
        public double energyWatt(){return fill.energyWatt()+surfaceWatt;}
        public double coolingDutyWatt(){return fill.dutyWatt()+surfaceWatt;}
    }
    public static Rates at(PassiveNetwork graph,int node,List<FluidThermodynamics.State> states,double[] flows,FluidThermodynamics model) {
        var basin=graph.reservoirs().get(node);var tower=basin.inventory().tower();
        int n=model.componentCount();double[] oil=new double[n],aqueous=new double[n];double inlet=0,heat=0;
        for(int edge=0;edge<flows.length;edge++){
            var pipe=graph.pipes().get(edge);boolean first=pipe.first()==node;
            if(!first&&pipe.second()!=node||pipe.portAt(node)!=PassiveNetwork.PhasePort.SPRAY)continue;
            double incoming=first?-flows[edge]:flows[edge];
            var donor=states.get(first?pipe.second():pipe.first());
            if(!(incoming>0)||!(donor.mass()>0))continue;
            // The spray's liquid feeds the fill (W3B_NETWORK_PLAN.md section 20); its gas and solids pass to the basin.
            double share=incoming/donor.mass(),liquid=share*(model.slotMass(donor,FluidThermodynamics.State.LIQUID)+model.slotMass(donor,FluidThermodynamics.State.AQUEOUS));
            if(!(liquid>0))continue;
            double[] l=donor.liquidAmountsView(),a=donor.aqueousAmountsView();
            for(int c=0;c<n;c++){oil[c]+=share*l[c];aqueous[c]+=share*a[c];}
            inlet+=liquid;heat+=liquid*donor.temperature();
        }
        double basinTemperature=states.get(node).temperature();
        double temperature=inlet>0?heat/inlet:basinTemperature;
        var liquid=inlet>0?TowerTransfer.of(model,oil,aqueous,temperature):null;
        boolean target=tower.mode()==CoolingTower.Mode.TARGET_TEMPERATURE,boiling=liquid!=null&&liquid.boiling;
        // Fans without power stand (W5, fanLimit 0): the tower runs on its stack draught alone.
        double speed=tower.fanSpeed(!target?tower.speed():liquid!=null&&!boiling&&tower.fanLimit()>0?TowerTransfer.targetSpeed(model,tower,liquid,inlet):0);
        // A liquid that boils at the atmosphere has no air contact; its vapour leaves by the basin's flash and vent.
        var fill=liquid==null?TowerTransfer.fill(model,tower,0,basinTemperature,speed):TowerTransfer.fill(model,tower,liquid,inlet,speed);
        String status=boiling?"BOILING: the liquid boils at the atmosphere; no air contact"
            :target&&inlet>0&&Math.abs(fill.outletTemperature()-tower.setPoint())>.1?"TARGET_LIMIT: leaving-liquid temperature target unavailable"
            :inlet==0?"NO_SPRAY: basin surface exchange only":"RUNNING";
        return new Rates(fill,basin.empty()?0:TowerTransfer.surfaceWatt(tower,basinTemperature),temperature,inlet,status);
    }
}
