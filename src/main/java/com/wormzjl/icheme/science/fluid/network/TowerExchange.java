package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.HashMap;

/** Common atmospheric boundary for Newton, conservative reconstruction and accepted presentation. */
public final class TowerExchange {
    private TowerExchange() {}
    public static Map<Long,Rates> readings(PassiveNetwork graph,List<FluidThermodynamics.State> states,double[] flows,double dt,FluidThermodynamics model){
        Map<Long,Rates> result=null;
        for(int node=0;node<graph.reservoirs().size();node++)if(graph.reservoirs().get(node).inventory().tower()!=null){
            if(result==null)result=new HashMap<>();
            result.put(graph.reservoirs().get(node).id(),at(graph,node,states,flows,dt,model));
        }
        return result==null?Map.of():Map.copyOf(result);
    }
    static Map<Long,Rates> atRest(PassiveNetwork graph,double dt,FluidThermodynamics model){
        for(var node:graph.reservoirs())if(node.inventory().tower()!=null)
            return readings(graph,graph.reservoirs().stream().map(PassiveNetwork.Reservoir::state).toList(),new double[graph.pipes().size()],dt,model);
        return Map.of();
    }
    public record Rates(double waterLoss,double energyWatt,TowerTransfer.Result fill,TowerTransfer.Result surface,
                        double inletTemperature,double inletFlow,double outletFlow,String status) {
        public Rates {
            Objects.requireNonNull(fill);Objects.requireNonNull(surface);Objects.requireNonNull(status);
            for(double value:new double[]{waterLoss,energyWatt,inletTemperature,inletFlow,outletFlow})
                if(!Double.isFinite(value))throw new IllegalArgumentException("Nonfinite tower reading");
        }
        public double coolingDutyWatt(){return fill.sensibleWatt()+fill.latentWatt()+surface.sensibleWatt()+surface.latentWatt();}
    }
    public static boolean liquidWater(FluidThermodynamics model,FluidThermodynamics.State state) {
        if(!state.solids().empty()||state.waterLiquid()<=0||state.waterVapor()!=0)return false;
        for(double amount:state.liquid())if(amount!=0)return false;
        for(double amount:state.vapor())if(amount!=0)return false;
        double temperature=TowerTransfer.liquidTemperature(model,state.enthalpy()/state.mass(),state.temperature());
        return model.saturationPressure(temperature)<CoolingTower.PRESSURE;
    }
    public static Rates at(PassiveNetwork graph,int node,List<FluidThermodynamics.State> states,double[] flows,double dt,FluidThermodynamics model) {
        var basin=graph.reservoirs().get(node);var tower=basin.inventory().tower();
        if(tower==null)return null;
        double inlet=0,enthalpy=0,temperatureWeight=0,outlet=0;boolean unsupported=false;
        for(int edge=0;edge<flows.length;edge++){
            var pipe=graph.pipes().get(edge);boolean first=pipe.first()==node;
            if(!first&&pipe.second()!=node)continue;
            double incoming=first?-flows[edge]:flows[edge];
            var port=pipe.portAt(node);
            if(port==PassiveNetwork.PhasePort.LIQUID)outlet+=Math.max(0,-incoming);
            if(port!=PassiveNetwork.PhasePort.SPRAY)continue;
            var donor=states.get(first?pipe.second():pipe.first());
            if(!liquidWater(model,donor)){unsupported=true;continue;}
            if(incoming>0){inlet+=incoming;enthalpy+=incoming*donor.enthalpy()/donor.mass();temperatureWeight+=incoming*donor.temperature();}
        }
        double temperature=inlet>0?TowerTransfer.liquidTemperature(model,enthalpy/inlet,temperatureWeight/inlet):states.get(node).temperature();
        double speed=tower.speed();
        if(tower.mode()==CoolingTower.Mode.TARGET_TEMPERATURE&&inlet==0)speed=0;
        if(tower.mode()==CoolingTower.Mode.TARGET_TEMPERATURE&&inlet>0){
            var minimum=TowerTransfer.fill(model,tower,inlet,temperature,0);
            var maximum=TowerTransfer.fill(model,tower,inlet,temperature,1);
            double lowError=minimum.outletTemperature()-tower.setPoint(),highError=maximum.outletTemperature()-tower.setPoint();
            if(lowError*highError>=0)speed=Math.abs(lowError)<=Math.abs(highError)?0:1;
            else {
                double low=0,high=1;
                for(int iteration=0;iteration<16;iteration++){
                    double middle=(low+high)/2;
                    double error=TowerTransfer.fill(model,tower,inlet,temperature,middle).outletTemperature()-tower.setPoint();
                    if(error*lowError>0)low=middle;else high=middle;
                }
                speed=(low+high)/2;
            }
        }
        var fill=TowerTransfer.fill(model,tower,inlet,temperature,speed);
        var surface=TowerTransfer.surface(model,tower,states.get(node).temperature(),basin.empty()?0:basin.state().mass(),dt);
        String status=unsupported?"WATER_ONLY: pure liquid water required at atmospheric discharge"
            :tower.mode()==CoolingTower.Mode.TARGET_TEMPERATURE&&inlet>0&&Math.abs(fill.outletTemperature()-tower.setPoint())>.1
                ?"TARGET_LIMIT: leaving-water temperature target unavailable"
            :inlet==0?"NO_SPRAY: basin surface exchange only":"RUNNING";
        return new Rates(fill.waterLoss()+surface.waterLoss(),fill.energyWatt()+surface.energyWatt(),fill,surface,temperature,inlet,outlet,status);
    }
}
