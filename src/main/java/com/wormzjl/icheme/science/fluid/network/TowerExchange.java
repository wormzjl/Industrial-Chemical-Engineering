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
    /** Water and energy leave through the fill's result and the basin surface; only the fill evaporates. */
    public record Rates(TowerTransfer.Result fill,double surfaceWatt,double inletTemperature,double inletFlow,String status) {
        public Rates {
            Objects.requireNonNull(fill);Objects.requireNonNull(status);
            for(double value:new double[]{surfaceWatt,inletTemperature,inletFlow})
                if(!Double.isFinite(value))throw new IllegalArgumentException("Nonfinite tower reading");
        }
        public double waterLoss(){return fill.evaporation();}
        public double energyWatt(){return fill.energyWatt()+surfaceWatt;}
        public double coolingDutyWatt(){return fill.dutyWatt()+surfaceWatt;}
    }
    /** Whether a stream can feed the fill: its liquid is water alone and stays liquid at the atmosphere. Its gas passes to the basin, whose vent takes it. */
    public static boolean liquidWater(FluidThermodynamics model,FluidThermodynamics.State state) {
        // W3b: the liquid is one slot that holds water and no other condensable compound, with no other liquid beside it. A gas
        // dissolved in it (a compound above its critical temperature: the air or nitrogen of a basin's water, about 1e-5 of
        // it) leaves with the spray as the gas of any stream; read as a second compound, it refused every basin's own water
        // and a pumped loop never primed (W3b stage A, CoolingTowerRuntimeTest).
        if(!state.solids().empty())return false;
        int slot=state.slot(FluidThermodynamics.AQUEOUS);
        if(slot<0||state.slot(FluidThermodynamics.OIL)>=0)return false;
        double[] liquid=state.amountsView(slot);
        if(!(liquid[liquid.length-1]>0))return false;
        for(int c=0;c<liquid.length-1;c++)if(liquid[c]!=0&&model.hydrocarbon.criticalTemperature(c)>state.temperature())return false;
        return model.saturationPressure(state.temperature())<CoolingTower.PRESSURE;
    }
    public static Rates at(PassiveNetwork graph,int node,List<FluidThermodynamics.State> states,double[] flows,FluidThermodynamics model) {
        var basin=graph.reservoirs().get(node);var tower=basin.inventory().tower();
        double inlet=0,heat=0;boolean unsupported=false;
        for(int edge=0;edge<flows.length;edge++){
            var pipe=graph.pipes().get(edge);boolean first=pipe.first()==node;
            if(!first&&pipe.second()!=node||pipe.portAt(node)!=PassiveNetwork.PhasePort.SPRAY)continue;
            double incoming=first?-flows[edge]:flows[edge];
            var donor=states.get(first?pipe.second():pipe.first());
            if(!liquidWater(model,donor)){unsupported=true;continue;}
            if(incoming>0){double water=incoming*donor.amountsView(donor.slot(FluidThermodynamics.AQUEOUS))[donor.componentCount()-1]*model.waterMolecularWeight/donor.mass();inlet+=water;heat+=water*donor.temperature();}
        }
        double basinTemperature=states.get(node).temperature();
        double temperature=inlet>0?heat/inlet:basinTemperature;
        boolean target=tower.mode()==CoolingTower.Mode.TARGET_TEMPERATURE;
        // Fans without power stand (W5, fanLimit 0): the tower runs on its stack draught alone.
        double speed=tower.fanSpeed(!target?tower.speed():inlet>0&&tower.fanLimit()>0?TowerTransfer.targetSpeed(model,tower,inlet,temperature):0);
        var fill=TowerTransfer.fill(model,tower,inlet,temperature,speed);
        String status=unsupported?"WATER_ONLY: pure liquid water required at atmospheric discharge"
            :target&&inlet>0&&Math.abs(fill.outletTemperature()-tower.setPoint())>.1?"TARGET_LIMIT: leaving-water temperature target unavailable"
            :inlet==0?"NO_SPRAY: basin surface exchange only":"RUNNING";
        return new Rates(fill,basin.empty()?0:TowerTransfer.surfaceWatt(tower,basinTemperature),temperature,inlet,status);
    }
}
