package com.wormzjl.icheme.science.fluid.network;

import static org.junit.jupiter.api.Assertions.*;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;

class FlowControlTest {
    private final FluidThermodynamics model=com.wormzjl.icheme.fluid.support.FluidTestSupport.model(MaterialCatalog.bundled(),"icheme:tjl20_methane");
    /** Two 1 m3 vessels of one component at 350 K and {@code pa}, {@code pb}, joined by 10 m of 5 cm pipe with {@code control}. */
    private PassiveNetwork line(double pa,double pb,int component,FlowControl control) {
        var nodes=new ArrayList<PassiveNetwork.Reservoir>();
        for(double p:new double[]{pa,pb}){double[] n=new double[com.wormzjl.icheme.science.material.MaterialTestBasis.CRUDE+1];n[component]=1;var unit=model.flashTP(350,p,n,()->{});n[component]/=unit.volume();nodes.add(new PassiveNetwork.Reservoir(nodes.size(),0,model.flashTP(350,p,n,()->{})));}
        return new PassiveNetwork(nodes,List.of(new PassiveNetwork.Pipe(5,0,1,new PipeResistance.Geometry(10,.05,.000045,0),control)));
    }
    private PassiveStepSolver.Result run(double pa,double pb,int component,FlowControl control,double dt) {
        return new PassiveStepSolver(model).solve(line(pa,pb,component,control),dt,()->{});
    }
    /** Now a compressor on methane (decision D2; a liquid-only pump refuses gas, decision D1; test name kept): its setting is the suction volume
     * flow at inlet conditions, which it delivers on its corner law up to its relief rise (batch 2026-11-03-rotating-machinery,
     * form F2b: 99.99994 % at half the relief rise), and its gas power the polytropic work at η_p 0.78 on suction properties (batch
     * 2026-11-03-rotating-machinery, plan 3.2 and 4.4), {@code σ (P_s/ρ_s)(r^(1/σ) − 1)/η_p} per kilogram with
     * {@code σ = η_p k/(k − 1)} and k from the suction's start state. A ratio of 3 lifts 1 atm suction by 203 kPa, above
     * the 99 kPa the discharge vessel stands higher. */
    @Test void pumpMeetsItsSuctionFlowTargetAndAccountsForAddedEnergy() {
        var graph=line(101325,200000,0,new FlowControl.Compressor(.001,3));
        var result=new PassiveStepSolver(model).solve(graph,.1,()->{});var suction=result.states().getFirst();
        double corner=new com.wormzjl.icheme.science.machine.PositiveDisplacement(.001,2*suction.pressure()).flow(result.devicePressureChanges()[0],1);
        assertEquals(corner,result.massFlows()[0]/(suction.mass()/suction.volume()),1e-8*corner,"the corner's suction volume flow at the rise held");
        assertEquals(.001,corner,1e-5*.001,"its setting, to a hundred-thousandth at half its relief rise");
        assertEquals(FlowControl.Mode.MOVER_LAW,result.modes().getFirst());assertTrue(result.devicePressureChanges()[0]>0);assertTrue(result.pumpWorkJoule()>0);
        double q=result.massFlows()[0],head=result.devicePressureChanges()[0],p=suction.pressure(),rho=suction.mass()/suction.volume();
        double eta=.78,k=model.heatCapacityRatio(graph.reservoirs().getFirst().state()),sigma=eta*k/(k-1);
        assertTrue(k>1.25&&k<1.32,"methane's ideal-gas k at 350 K, about 1.28: "+k);
        double expected=.1*q*sigma*(p/rho)*(Math.pow(1+head/p,1/sigma)-1)/eta;
        assertEquals(expected,result.pumpWorkJoule(),1e-6*expected,"polytropic work on the suction's pressure and density over the step");
    }
    @Test void naturalFlowIsLimitedWithoutInventedPumpWorkAndOpposingHeadShutsThePump() {
        var natural=run(300000,101325,0,new FlowControl.Compressor(.001,3),.1);
        assertTrue(natural.devicePressureChanges()[0]<0);assertEquals(0,natural.pumpWorkJoule());assertTrue(natural.massFlows()[0]>0);
        // 8 bar against 1 atm suction is a ratio of 7.9: a 3:1 machine cannot open.
        var shutoff=run(101325,800000,0,new FlowControl.Compressor(.001,3),.1);
        assertEquals(FlowControl.Mode.CLOSED,shutoff.modes().getFirst());assertEquals(0,shutoff.massFlows()[0],1e-10);assertEquals(0,shutoff.pumpWorkJoule(),1e-6);
    }
    @Test void pressureValveClosesBelowTargetAndRegulatesAboveIt() {
        var closed=run(101325,80000,model.componentCount()-1,new FlowControl.PressureValve(175000),.1);
        assertEquals(FlowControl.Mode.CLOSED,closed.modes().getFirst());assertEquals(0,closed.massFlows()[0],1e-10);
        var regulated=run(200000,101325,model.componentCount()-1,new FlowControl.PressureValve(175000),.1);
        assertEquals(FlowControl.Mode.VALVE_REGULATING,regulated.modes().getFirst());assertEquals(175000,regulated.states().getFirst().pressure(),.01);
        assertTrue(regulated.massFlows()[0]>0);assertTrue(regulated.devicePressureChanges()[0]>0);
    }
    @Test void modularFlowValveMeetsMassAndActualVolumeTargetsWithoutWork(){
        for(var source:java.util.List.of(FlowControl.ValveSource.MASS_FLOW,FlowControl.ValveSource.VOLUME_FLOW)){
            double target=source==FlowControl.ValveSource.MASS_FLOW?.01:.001;
            var result=run(300000,101325,0,new FlowControl.ControlValve(source,target,new java.util.UUID(1,1),1),.1);
            assertEquals(FlowControl.Mode.VALVE_REGULATING,result.modes().getFirst());
            double rho=result.states().getFirst().mass()/result.states().getFirst().volume();
            assertEquals(target,source==FlowControl.ValveSource.MASS_FLOW?result.massFlows()[0]:result.massFlows()[0]/rho,1e-8*target);
            assertTrue(result.devicePressureChanges()[0]>=0);assertEquals(0,result.pumpWorkJoule(),1e-12);
        }
    }
    @Test void bareValveIsFullyOpenAndLargeFlowTargetSaturates(){
        var passive=run(300000,101325,0,new FlowControl.Passive(),.1);
        var bare=run(300000,101325,0,FlowControl.ControlValve.unconfigured(),.1);
        assertEquals(passive.massFlows()[0],bare.massFlows()[0],1e-8*Math.abs(passive.massFlows()[0]));
        assertEquals(FlowControl.Mode.VALVE_OPEN,bare.modes().getFirst());
        var saturated=run(300000,101325,0,new FlowControl.ControlValve(FlowControl.ValveSource.MASS_FLOW,100,new java.util.UUID(1,2),1),.1);
        assertEquals(FlowControl.Mode.VALVE_OPEN,saturated.modes().getFirst());
        assertTrue(saturated.massFlows()[0]<100);
    }
    @Test void removingASensorRetainsIndependentSiTargets(){
        var v=new FlowControl.ControlValve(FlowControl.ValveSource.PRESSURE,350000,2,.03,new java.util.UUID(1,1),1);
        var removed=v.withSource(v.source(),null,2);assertTrue(removed.bare());assertEquals(350000,removed.targetPressure());assertEquals(.03,removed.volumeTarget());assertEquals(2,removed.massTarget());
    }
    /** Owner decision 2026-11-02, "Lower target wins": valves in series carry one flow, the lower target's; a target of 0 closes the line. */
    @Test void flowValvesInSeriesCarryTheLowerTarget(){
        double[] n=new double[model.componentCount()];n[0]=1;
        var a=model.flashTP(350,300000,n,()->{});double[] n2=n.clone();for(int c=0;c<n.length;c++)n[c]/=a.volume();a=model.flashTP(350,300000,n,()->{});var b=model.flashTP(350,101325,n2,()->{});for(int c=0;c<n2.length;c++)n2[c]/=b.volume();b=model.flashTP(350,101325,n2,()->{});
        var nodes=java.util.List.of(new PassiveNetwork.Reservoir(1,0,a,PassiveNetwork.NodeKind.GENERATOR),new PassiveNetwork.Reservoir(2,0,b,PassiveNetwork.NodeKind.JUNCTION),new PassiveNetwork.Reservoir(3,0,b,PassiveNetwork.NodeKind.VOID));
        var line=new PipeResistance.Geometry(10,.05,.000045,0);
        for(double[] targets:new double[][]{{.01,.01},{.01,.02},{.02,.01},{.01,0},{0,.01}}){
            var graph=new PassiveNetwork(nodes,java.util.List.of(new PassiveNetwork.Pipe(5,0,1,line,new FlowControl.ControlValve(FlowControl.ValveSource.MASS_FLOW,targets[0],new java.util.UUID(1,1),1)),
                    new PassiveNetwork.Pipe(6,1,2,line,new FlowControl.ControlValve(FlowControl.ValveSource.MASS_FLOW,targets[1],new java.util.UUID(1,2),1))));
            int low=targets[1]<targets[0]?1:0;double lower=targets[low];
            assertEquals(java.util.BitSet.valueOf(new long[]{1L<<(1-low)}),ControlConstraints.standing(graph),"the higher target stands open");
            var result=new PassiveStepSolver(model).solveRate(graph,()->{});
            assertEquals(lower==0?FlowControl.Mode.CLOSED:FlowControl.Mode.VALVE_REGULATING,result.modes().get(low),java.util.Arrays.toString(targets));
            assertEquals(FlowControl.Mode.VALVE_OPEN,result.modes().get(1-low),java.util.Arrays.toString(targets));
            assertEquals(lower,result.massFlows()[0],1e-9);assertEquals(lower,result.massFlows()[1],1e-9);
            assertEquals(0,result.devicePressureChanges()[1-low],1e-5,"a valve standing open takes no drop");
            assertArrayEquals(result.massFlows(),new PassiveStepSolver(model).solveRate(graph,()->{}).massFlows());
        }
    }
    private PassiveNetwork.Pipe flowValve(long id,int first,int second,double target,java.util.UUID sensor){return new PassiveNetwork.Pipe(id,first,second,new PipeResistance.Geometry(10,.05,.000045,0),new FlowControl.ControlValve(FlowControl.ValveSource.MASS_FLOW,target,sensor,1));}
    /** In a branched group a column's port keeps its row: a generator's outlet valve, then two lines off a junction behind it. */
    @Test void aPortKeepsItsRowInABranchedGroup(){
        double[] n=new double[model.componentCount()];n[0]=1;var s=model.flashTP(350,200000,n,()->{});
        var nodes=List.of(new PassiveNetwork.Reservoir(1,0,s,PassiveNetwork.NodeKind.GENERATOR),new PassiveNetwork.Reservoir(2,0,s,PassiveNetwork.NodeKind.JUNCTION),new PassiveNetwork.Reservoir(3,0,s,PassiveNetwork.NodeKind.JUNCTION),
                new PassiveNetwork.Reservoir(4,0,s,PassiveNetwork.NodeKind.VOID),new PassiveNetwork.Reservoir(5,0,s,PassiveNetwork.NodeKind.VOID));
        var line=new PipeResistance.Geometry(10,.05,.000045,0);var feed=new PassiveNetwork.Pipe(9,0,1,line,new FlowControl.Passive());var player=new java.util.UUID(1,1);
        // The outlet's 5 keeps its row against a player's 2 and 1: the higher of those stands open.
        var players=new PassiveNetwork(nodes,List.of(feed,flowValve(10,1,2,5,PortPlan.COLUMN_SENSOR),flowValve(11,2,3,2,player),flowValve(12,2,4,1,player)));
        assertEquals(java.util.BitSet.valueOf(new long[]{1L<<2}),ControlConstraints.standing(players));
        // All three ports: the outlet's row goes in before the inlets it feeds, so what its account gives stays its plan.
        var ports=new PassiveNetwork(nodes,List.of(feed,flowValve(10,1,2,5,PortPlan.COLUMN_SENSOR),flowValve(11,2,3,3,PortPlan.COLUMN_SENSOR),flowValve(12,2,4,4,PortPlan.COLUMN_SENSOR)));
        assertEquals(java.util.BitSet.valueOf(new long[]{1L<<3}),ControlConstraints.standing(ports));
        // A player's valve in series with an outlet's, lower: it regulates, and the series keeps a port's rank.
        var series=new PassiveNetwork(List.of(nodes.get(0),nodes.get(1),nodes.get(2),nodes.get(3)),List.of(feed,flowValve(10,1,2,5,PortPlan.COLUMN_SENSOR),flowValve(11,2,3,2,player)));
        assertEquals(java.util.BitSet.valueOf(new long[]{1L<<1}),ControlConstraints.standing(series));
    }
    /** A single valve that bounds two columns takes the lower plan, whichever comes first. */
    @Test void aValveBoundingTwoColumnsTakesTheLowerPlan(){
        double[] n=new double[model.componentCount()];n[0]=1;var s=model.flashTP(350,200000,n,()->{});
        var nodes=List.of(new PassiveNetwork.Reservoir(1,0,s,PassiveNetwork.NodeKind.GENERATOR),new PassiveNetwork.Reservoir(2,0,s,PassiveNetwork.NodeKind.JUNCTION),new PassiveNetwork.Reservoir(3,0,s,PassiveNetwork.NodeKind.VOID));
        var graph=new PassiveNetwork(nodes,List.of(new PassiveNetwork.Pipe(9,0,1,new PipeResistance.Geometry(10,.05,.000045,0),new FlowControl.Passive()),flowValve(10,1,2,.5,PortPlan.COLUMN_SENSOR)));
        for(var plans:List.of(new java.util.TreeMap<>(java.util.Map.of(1L,new PortPlan(1,2,.01,0,null),3L,new PortPlan(3,2,.004,0,null))),new java.util.TreeMap<>(java.util.Map.of(1L,new PortPlan(1,2,.004,0,null),3L,new PortPlan(3,2,.01,0,null)))))
            assertEquals(.004,((FlowControl.ControlValve)new PassiveStepSolver(model).ports(graph,plans,()->{}).pipes().get(1).control()).massTarget());
    }
    /** Pressure valves on one node are outside "Lower target wins": they hold their accepted openings. */
    @Test void pressureValvesOnOneNodeHoldTheirAcceptedOpenings(){
        double[] n=new double[model.componentCount()];n[0]=1;
        var high=model.flashTP(350,300000,n,()->{});var low=model.flashTP(350,101325,n,()->{});
        var nodes=List.of(new PassiveNetwork.Reservoir(1,0,high,PassiveNetwork.NodeKind.GENERATOR),new PassiveNetwork.Reservoir(2,0,model.flashTP(350,200000,n,()->{}),PassiveNetwork.NodeKind.JUNCTION),new PassiveNetwork.Reservoir(3,0,low,PassiveNetwork.NodeKind.VOID),new PassiveNetwork.Reservoir(4,0,low,PassiveNetwork.NodeKind.VOID));
        var line=new PipeResistance.Geometry(10,.05,.000045,0);var feed=new PassiveNetwork.Pipe(9,0,1,line,new FlowControl.Passive());
        var valve=new PassiveNetwork.Pipe(5,1,2,line,new FlowControl.ControlValve(FlowControl.ValveSource.PRESSURE,280000,new UUID(1,1),1));
        var original=new PassiveNetwork(nodes,List.of(feed,valve));
        var solver=new PassiveStepSolver(model);var accepted=solver.solve(original,.1,()->{});solver.markBackwardEulerAccepted(original,accepted);
        var other=new PassiveNetwork.Pipe(6,1,3,line,new FlowControl.ControlValve(FlowControl.ValveSource.PRESSURE,280000,new UUID(1,2),1));
        var conflict=new PassiveNetwork(nodes,List.of(feed,valve,other));var result=solver.solve(conflict,.1,()->{});
        assertEquals(FlowControl.Mode.VALVE_CONFLICT,result.modes().get(1));assertEquals(FlowControl.Mode.VALVE_CONFLICT,result.modes().get(2));
        assertTrue(result.massFlows()[1]>0&&result.massFlows()[2]>result.massFlows()[1],"the valve that throttled holds its opening, the new one stands wide: "+result.massFlows()[1]+", "+result.massFlows()[2]);
    }
    /** A valve that stands open leaves every other regulation row: a third valve across the pair keeps its own target. */
    @Test void aValveStandingOpenLeavesIndependentLoopActive(){
        double[] n=new double[model.componentCount()];n[0]=1;
        var high=model.flashTP(350,300000,n,()->{});var low=model.flashTP(350,101325,n,()->{});
        var nodes=List.of(new PassiveNetwork.Reservoir(1,0,high,PassiveNetwork.NodeKind.GENERATOR),new PassiveNetwork.Reservoir(2,0,low,PassiveNetwork.NodeKind.JUNCTION),new PassiveNetwork.Reservoir(3,0,low,PassiveNetwork.NodeKind.VOID));
        var line=new PipeResistance.Geometry(10,.05,.000045,0);
        var valve=new PassiveNetwork.Pipe(5,0,1,line,new FlowControl.ControlValve(FlowControl.ValveSource.MASS_FLOW,.01,new UUID(1,1),1)).withActuatorArea(Math.PI*.08*.08/4);
        var original=new PassiveNetwork(nodes,List.of(valve,new PassiveNetwork.Pipe(6,1,2,line,new FlowControl.Passive())));
        var solver=new PassiveStepSolver(model);var accepted=solver.solve(original,.1,()->{});solver.markBackwardEulerAccepted(original,accepted);
        var other=new PassiveNetwork.Pipe(6,1,2,line,new FlowControl.ControlValve(FlowControl.ValveSource.MASS_FLOW,.02,new UUID(1,2),1));
        var independent=new PassiveNetwork.Pipe(7,0,2,line,new FlowControl.ControlValve(FlowControl.ValveSource.MASS_FLOW,.005,new UUID(1,3),1));
        var conflict=new PassiveNetwork(nodes,List.of(valve,other,independent));var result=solver.solve(conflict,.1,()->{});
        assertEquals(List.of(FlowControl.Mode.VALVE_REGULATING,FlowControl.Mode.VALVE_OPEN,FlowControl.Mode.VALVE_REGULATING),result.modes());
        assertEquals(.01,result.massFlows()[0],1e-8);
        assertEquals(.005,result.massFlows()[2],1e-10);assertEquals(0,result.pumpWorkJoule());
    }

    @Test void aBareValvePassesReverseFlowLikeItsPipe(){
        var plain=run(101325,300000,0,new FlowControl.Passive(),.1);var bare=run(101325,300000,0,FlowControl.ControlValve.unconfigured(),.1);
        assertTrue(bare.massFlows()[0]<0);assertEquals(plain.massFlows()[0],bare.massFlows()[0],1e-10);assertEquals(0,bare.devicePressureChanges()[0],1e-8);
    }
    /** A configured flow valve only throttles: its island rests when nothing drives through it, as the solve finds. */
    @Test void aFlowValveRestsWhenNothingDrivesThroughIt(){
        for(var source:List.of(FlowControl.ValveSource.MASS_FLOW,FlowControl.ValveSource.VOLUME_FLOW)){
            var valve=new FlowControl.ControlValve(source,source==FlowControl.ValveSource.MASS_FLOW?.01:.001,new UUID(1,1),1);
            assertTrue(NetworkReduction.atRest(line(200000,200000,0,valve),model),source+": no drive");
            assertTrue(NetworkReduction.atRest(line(101325,300000,0,valve),model),source+": a drive against it");
            assertEquals(0,run(101325,300000,0,valve,.1).massFlows()[0],1e-12,source+": it passes nothing back");
            assertFalse(NetworkReduction.atRest(line(300000,101325,0,valve),model),source+": a drive through it");
            assertTrue(NetworkReduction.atRest(line(300000,101325,0,valve.withTarget(0)),model),source+": a zero target holds it shut");
            assertEquals(0,run(300000,101325,0,valve.withTarget(0),.1).massFlows()[0],1e-12,source+": the solve passes nothing at a zero target");
        }
    }

}
