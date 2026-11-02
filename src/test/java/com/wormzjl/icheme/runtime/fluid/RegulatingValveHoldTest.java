package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.network.PassiveIntervalSolver;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.network.PassiveStepSolver;
import com.wormzjl.icheme.science.fluid.network.PortPlan;
import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.icheme.science.material.MaterialTestBasis;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A flow valve that regulates a line its open pass chokes (batch 2026-11-02-valve-hold). The line stands well under its
 * caps at the target and the valve takes the drop, whatever the ratio across it. Before the fix the regulated pass carried
 * the open pass's law flows, deep on the saturated law's plateau, and the Newton never left it: the island held at
 * residual (cap - target) at every step size, at 5 to 4 bar as at 5 to 2.5 bar.
 */
class RegulatingValveHoldTest {
    private static final UUID SENSOR=new UUID(1,1);
    private static double[] pure(int component){double[] n=new double[MaterialTestBasis.NETWORK+1];n[component]=1;return n;}
    private static double[] crude(){return Arrays.copyOf(V3PengRobinsonThermo.fromRegisteredPackage("icheme:tjl20_methane_nitrogen").crudeFeed("icheme:tia_juana_light_methane").moleFractions(),MaterialTestBasis.NETWORK+1);}
    private static FlowControl.ControlValve valve(double target){return new FlowControl.ControlValve(FlowControl.ValveSource.MASS_FLOW,target,SENSOR,1);}
    private static FluidDeviceSpec sink(double pressure,double temperature){return new FluidDeviceSpec(1,temperature,pressure,pure(MaterialTestBasis.NITROGEN));}

    /** Generator, pipe, valve, pipe, void, as the column's boundary lines are laid, placed with the target set. */
    private static void assertRegulates(String name,double[] composition,double temperature,double supply,double sink,double bore,double target) {
        var w=new EventPathWorld();
        w.place(0,0,0,Kind.GENERATOR,EAST,new FlowControl.Passive(),new FluidDeviceSpec(1,temperature,supply,composition),bore);
        w.place(1,0,0,Kind.PIPE,EAST,bore);
        w.place(2,0,0,Kind.PIPE,EAST,valve(target),null,bore);
        w.place(3,0,0,Kind.PIPE,EAST,bore);
        w.place(4,0,0,Kind.VOID,EAST,sink(sink,temperature),bore);
        w.runTo(800);
        w.assertKeptUp(name);
        var result=w.coordinator.snapshots().getFirst().lastResult().orElseThrow();
        for(double flow:result.averageMassFlows())assertEquals(target,flow,1e-6*target,name);
        assertEquals(FlowControl.Mode.VALVE_REGULATING,result.endpointModes().getLast(),name);
    }
    @Test void theColumnsBoundaryLinesRegulateAtAnyRatio() {
        double[] steam=pure(MaterialTestBasis.NETWORK),oil=crude();
        for(double sink:new double[]{250000,400000}) {
            assertRegulates("steam to "+sink,steam,533,500000,sink,.15,.75);
            assertRegulates("crude to "+sink,oil,638.2,500000,sink,.25,19.9);
        }
        assertRegulates("a small flow of nitrogen",pure(MaterialTestBasis.NITROGEN),298.15,500000,400000,.05,.05);
    }
    /** Two supplies into one header above the valve: no closure of the series line tells either supply's share. */
    @Test void aHeaderOfTwoSuppliesRegulates() {
        var w=new EventPathWorld();double[] steam=pure(MaterialTestBasis.NETWORK);double bore=.15,target=.75;
        w.place(0,0,1,Kind.GENERATOR,SOUTH,new FlowControl.Passive(),new FluidDeviceSpec(1,533,500000,steam),bore);
        w.place(0,0,-1,Kind.GENERATOR,NORTH,new FlowControl.Passive(),new FluidDeviceSpec(1,533,500000,steam),bore);
        w.place(0,0,0,Kind.PIPE,EAST,bore);
        w.place(1,0,0,Kind.PIPE,EAST,valve(target),null,bore);
        w.place(2,0,0,Kind.PIPE,EAST,bore);
        w.place(3,0,0,Kind.VOID,EAST,sink(250000,533),bore);
        w.runTo(800);
        w.assertKeptUp("a header of two supplies");
        var result=w.coordinator.snapshots().getFirst().lastResult().orElseThrow();
        var graph=w.coordinator.snapshots().getFirst().graph();
        for(int e=0;e<graph.pipes().size();e++)if(graph.pipes().get(e).control() instanceof FlowControl.ControlValve)assertEquals(target,result.averageMassFlows()[e],1e-6*target);
    }
    /**
     * A running island whose closed valve gets its target, as a column's cycle sets it: the solve carries the closed point, so
     * no balance starts it; the valve goes closed, open, regulating within one step.
     */
    @Test void aClosedValveTakesItsTargetOnARunningIsland() {
        var model=FluidTestSupport.networkModel();var w=new EventPathWorld();double[] steam=pure(MaterialTestBasis.NETWORK);double bore=.15,target=.75;
        w.place(0,0,0,Kind.GENERATOR,EAST,new FlowControl.Passive(),new FluidDeviceSpec(1,533,500000,steam),bore);
        w.place(1,0,0,Kind.PIPE,EAST,bore);
        w.place(2,0,0,Kind.PIPE,EAST,valve(0),null,bore);
        w.place(3,0,0,Kind.PIPE,EAST,bore);
        w.place(4,0,0,Kind.VOID,EAST,sink(250000,533),bore);
        w.runTo(200);
        var graph=w.coordinator.snapshots().getFirst().graph();
        var solver=new PassiveIntervalSolver(model);
        var rest=solver.solve(graph,5,PassiveIntervalSolver.Settings.defaults(),()->{},1);
        var pipes=new ArrayList<PassiveNetwork.Pipe>();
        for(var pipe:rest.graph().pipes())pipes.add(pipe.control() instanceof FlowControl.ControlValve?pipe.withControl(valve(target)):pipe);
        var running=solver.solve(new PassiveNetwork(rest.graph().reservoirs(),List.copyOf(pipes),rest.graph().scheduledTransfers()),5,PassiveIntervalSolver.Settings.defaults(),()->{},1);
        assertEquals(FlowControl.Mode.VALVE_REGULATING,running.endpointModes().getLast());
        for(double flow:running.endpointFlows())assertEquals(target,flow,1e-6*target);
    }
    /**
     * An idle line's first flows of a liquid product, as a column's outlet starts: the junction still holds the gas it stood in,
     * and each slice sets a new target. Neither slice solves the open valve: open, the hot liquid falls under its bubble pressure
     * at the junction (546 K at 176 kPa) and the liquid layout goes singular. The valve stays where its target puts it.
     */
    @Test void aLiquidProductEntersALineHoldingItsGasAndTakesNewTargets() {
        assertTakesTargets(2,486,new double[]{.126,0,0,1.26,3.03,0,0,64.7,161.7,1294,2588,258.8});
        assertTakesTargets(2,546.3,new double[]{0,0,0,1.87,.83,0,1.66,53.1,0,0,531,0,2549,.104});
    }
    /** The line's old gas washes out of the junction a factor of 1e3 and more a job. A dry junction takes it past 1e-10 and on to nothing. */
    @Test void aDryLineKeepsRunningWhileItsOldGasWashesOut() {
        var last=assertTakesTargets(9,546.3,new double[]{0,0,0,1.87,.83,0,1.66,53.1,0,0,531,0,2549,.104});
        for(var n:last.graph().reservoirs())if(n.junction()){var held=PhaseLayout.totalAmounts(n.state());double total=Arrays.stream(held).sum();assertTrue(held[MaterialTestBasis.NITROGEN]/total<1e-12);}
    }
    /** A hand-set target arrives as a topology event; the island it replaces carries its committed interval, so a hot line does not start cold (merge review, finding 6). */
    @Test void aHandSetTargetKeepsTheCommittedInterval() {
        var w=new EventPathWorld();double[] steam=pure(MaterialTestBasis.NETWORK);double bore=.15;
        w.place(0,0,0,Kind.GENERATOR,EAST,new FlowControl.Passive(),new FluidDeviceSpec(1,533,500000,steam),bore);
        w.place(1,0,0,Kind.PIPE,EAST,bore);
        long id=w.place(2,0,0,Kind.PIPE,EAST,valve(.75),null,bore);
        w.place(3,0,0,Kind.PIPE,EAST,bore);
        w.place(4,0,0,Kind.VOID,EAST,sink(250000,533),bore);
        w.runTo(800);
        var registration=w.ledger.snapshot().active().get(id);
        w.registry.submit(List.of(new WorldTopologyLedger.Edit(id,new WorldTopologyLedger.Registration(registration.device().withControl(valve(.5)),registration.spec(),registration.revision()+1))),w.ledger.nextIdentity(),null);
        w.registry.applyPending();
        var island=w.coordinator.snapshots().getFirst();
        assertTrue(island.lastResult().isPresent(),"the replaced island carries its interval: "+island.status());
        w.runTo(1200);w.assertKeptUp("a hand-set target");
        for(double flow:w.coordinator.snapshots().getFirst().lastResult().orElseThrow().averageMassFlows())assertEquals(.5,flow,5e-7);
    }
    /** A column's plan of zero stands however small the target before it (merge review, finding 11). */
    @Test void aPlannedZeroAlwaysApplies() {
        var model=FluidTestSupport.networkModel();var w=new EventPathWorld();double bore=.05;
        w.place(0,0,0,Kind.GENERATOR,EAST,new FlowControl.Passive(),new FluidDeviceSpec(1,638.15,250000,pure(MaterialTestBasis.NITROGEN)),bore);
        w.place(1,0,0,Kind.PIPE,EAST,bore);
        w.place(2,0,0,Kind.PIPE,EAST,new FlowControl.ControlValve(FlowControl.ValveSource.MASS_FLOW,200000,5e-7,.01,PortPlan.COLUMN_SENSOR,0),null,bore);
        w.place(3,0,0,Kind.PIPE,EAST,bore);
        w.place(4,0,0,Kind.VOID,EAST,sink(101325,298.15),bore);
        w.runTo(200);
        var graph=w.coordinator.snapshots().getFirst().graph();
        long valve=-1,port=-1;
        for(var pipe:graph.pipes())if(pipe.control() instanceof FlowControl.ControlValve)valve=graph.reservoirs().get(pipe.first()).id();
        for(var node:graph.reservoirs())if(node.kind()==PassiveNetwork.NodeKind.VOID)port=node.id();
        var planned=new PassiveStepSolver(model).ports(graph,Map.of(port,new PortPlan(port,valve,0,0,null)),()->{});
        for(var pipe:planned.pipes())if(pipe.control() instanceof FlowControl.ControlValve c)assertEquals(0,c.massTarget());
    }
    private static PassiveIntervalSolver.Result assertTakesTargets(int slices,double temperature,double[] amounts) {
        var model=FluidTestSupport.networkModel();var w=new EventPathWorld();double bore=.05,first=.25,second=.32;
        w.place(0,0,0,Kind.GENERATOR,EAST,new FlowControl.Passive(),new FluidDeviceSpec(1,638.15,250000,pure(MaterialTestBasis.NITROGEN)),bore);
        w.place(1,0,0,Kind.PIPE,EAST,bore);
        w.place(2,0,0,Kind.PIPE,EAST,valve(0),null,bore);
        w.place(3,0,0,Kind.PIPE,EAST,bore);
        w.place(4,0,0,Kind.VOID,EAST,sink(101325,298.15),bore);
        w.runTo(200);
        // As production runs a column's line: one retained solver reset from the committed interval before each job, and each
        // slice with a new composition at the generator and a new target at the valve (a planned port's two changes).
        var solver=new RetainedSolver();var settings=PassiveIntervalSolver.Settings.defaults();
        var committed=solver.solve(model,w.coordinator.snapshots().getFirst().graph(),5,settings,()->{});
        for(int slice=0;slice<slices;slice++) {
            double target=slice%2==0?first:second,shift=slice%2==0?0:.02;double[] product=Arrays.copyOf(amounts,MaterialTestBasis.NETWORK+1);
            for(int c=0;c<product.length;c++)product[c]*=1+(c%2==0?shift:-shift);
            double volume=model.flashTP(temperature,250000,product,()->{}).volume();for(int c=0;c<product.length;c++)product[c]/=volume;
            var liquid=model.flashTP(temperature,250000,product,()->{});assertEquals(0,liquid.vaporVolume());
            var nodes=new ArrayList<PassiveNetwork.Reservoir>();
            for(var n:committed.graph().reservoirs())nodes.add(n.kind()==PassiveNetwork.NodeKind.GENERATOR?new PassiveNetwork.Reservoir(n.id(),n.elevation(),liquid,n.kind(),new PassiveNetwork.Inventory(n.inventory().volume(),PhaseLayout.totalAmounts(liquid),liquid.internalEnergy(),liquid.solids())):n);
            var pipes=new ArrayList<PassiveNetwork.Pipe>();
            for(var pipe:committed.graph().pipes())pipes.add(pipe.control() instanceof FlowControl.ControlValve?pipe.withControl(valve(target)):pipe);
            solver.committed(committed);
            var running=solver.solve(model,new PassiveNetwork(nodes,pipes,committed.graph().scheduledTransfers()),5,settings,()->{});
            assertEquals(FlowControl.Mode.VALVE_REGULATING,running.endpointModes().getLast());
            for(double flow:running.endpointFlows())assertEquals(target,flow,1e-6*target);
            committed=running;
        }
        return committed;
    }
}
