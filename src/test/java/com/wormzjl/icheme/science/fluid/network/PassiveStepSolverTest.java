package com.wormzjl.icheme.science.fluid.network;

import static org.junit.jupiter.api.Assertions.*;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.wormzjl.icheme.science.fluid.thermo.TestPhases;

class PassiveStepSolverTest {
    // W3b: on the refinery network, whose cubic holds water (the tjl20 packages hold none); its first compounds are tjl20's.
    private final FluidThermodynamics model=com.wormzjl.icheme.fluid.support.FluidTestSupport.model(MaterialCatalog.bundled(),"icheme:refinery");
    private FluidThermodynamics.State fill(double pressure,int component) {
        double[] n=new double[model.componentCount()];n[component]=1;var unit=model.flashTP(350,pressure,n,()->{});n[component]/=unit.volume();
        return model.flashTP(350,pressure,n,()->{});
    }
    private PassiveNetwork graph(int component,boolean reverse) {
        var a=new PassiveNetwork.Reservoir(1,0,fill(200000,component));var b=new PassiveNetwork.Reservoir(2,0,fill(101325,component));
        return new PassiveNetwork(reverse?List.of(b,a):List.of(a,b),List.of(new PassiveNetwork.Pipe(3,0,1,new PipeResistance.Geometry(10,.05,.000045,0))));
    }
    @Test void gasAndLiquidFullReservoirsEqualizeWithConservedInventoriesAndEnergy() {
        for(int component:new int[]{0,model.componentCount()-1}) {
            var graph=graph(component,false);var result=new PassiveStepSolver(model).solve(graph,.1,()->{});
            assertTrue(result.massFlows()[0]>0);assertTrue(result.states().get(0).pressure()<200000);
            assertTrue(result.states().get(1).pressure()>101325);assertTrue(result.states().get(0).pressure()>=result.states().get(1).pressure());
            double beforeN=0,afterN=0,beforeU=0,afterU=0;
            for(int i=0;i<2;i++){var before=graph.reservoirs().get(i).state();var after=result.states().get(i);beforeN+=PhaseLayout.totalAmounts(before)[component];afterN+=PhaseLayout.totalAmounts(after)[component];beforeU+=before.internalEnergy();afterU+=after.internalEnergy();assertEquals(before.volume(),after.volume(),1e-8);}
            assertEquals(beforeN,afterN,1e-8*beforeN);assertEquals(beforeU,afterU,1e-6*Math.abs(beforeU));
        }
    }
    /**
     * Finding P-18 (batch 2026-11-13-codebase-review): a node's prepared bundle is refilled in place at a new temperature
     * and handed from pass to pass, so an island's steps after its first build no Peng-Robinson workspace.
     */
    @Test void stepsAfterAnIslandsFirstBuildNoKernelWorkspace() {
        for(int component:new int[]{0,model.componentCount()-1}) {
            var graph=graph(component,false);var solver=new PassiveStepSolver(model);
            graph=PassiveIntervalSolver.replace(graph,solver.solve(graph,.1,()->{}));
            long built=FluidThermodynamics.Prepared.workspacesBuilt();
            for(int step=0;step<3;step++)graph=PassiveIntervalSolver.replace(graph,solver.solve(graph,.1,()->{}));
            assertEquals(built,FluidThermodynamics.Prepared.workspacesBuilt(),"component "+component);
        }
    }
    @Test void endpointOrderOnlyChangesTheFlowSign() {
        var solver=new PassiveStepSolver(model);var forward=solver.solve(graph(0,false),.1,()->{});var backward=solver.solve(graph(0,true),.1,()->{});
        assertEquals(forward.massFlows()[0],-backward.massFlows()[0],1e-8);
        assertEquals(forward.states().get(0).pressure(),backward.states().get(1).pressure(),.001);
    }
    @Test void aJunctionNoOpenConnectionReachesKeepsItsPressureInsteadOfGoingSingular() {
        // A junction owns no volume, so only the hydraulics decide its pressure. Close every
        // connection - two stopped filters, two shut pumps, two connections a transport closure has
        // taken in both directions - and its net mass flow row is the sum of rows that already say
        // each of those flows is zero: satisfied identically, with no dependence on anything the
        // junction owns, so the block is one equation short of its own pressure and the
        // factorization is singular. It keeps the pressure it had, as it already keeps the
        // composition it had.
        // The island has to be doing something elsewhere, or Newton meets its tolerance at
        // iteration zero and never factorizes the matrix the isolated junction makes singular.
        var geometry=new PipeResistance.Geometry(10,.05,.000045,0);
        var graph=new PassiveNetwork(List.of(
                new PassiveNetwork.Reservoir(1,0,fill(300000,0),PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(2,0,fill(170000,0),PassiveNetwork.NodeKind.JUNCTION),
                // Above the ambient: a vessel is a bag (decision D54), and one charged below the ambient goes slack at it.
                new PassiveNetwork.Reservoir(3,0,fill(110000,0)),
                new PassiveNetwork.Reservoir(4,0,fill(110000,0))),List.of(
                new PassiveNetwork.Pipe(5,0,1,List.of(geometry),new FlowControl.Passive(),3),
                new PassiveNetwork.Pipe(6,1,2,List.of(geometry),new FlowControl.Passive(),3),
                new PassiveNetwork.Pipe(7,0,3,new PipeResistance.Geometry(10,.05,.000045,0))));
        var result=new PassiveStepSolver(model).solve(graph,.1,()->{});
        assertTrue(result.massFlows()[2]>0,"The open branch must still carry the step");
        assertEquals(0,result.massFlows()[0]);assertEquals(0,result.massFlows()[1]);
        assertEquals(170000,result.states().get(1).pressure(),1e-6*170000);
        // Nothing crossed either closure, so the reservoir behind them is exactly where it was.
        assertEquals(fill(110000,0).pressure(),result.states().get(2).pressure(),1e-6*110000);
        assertEquals(graph.reservoirs().get(2).inventory().moles()[0],result.inventories().get(2).moles()[0],1e-12);
    }
    @Test void branchJunctionHasNoInventoryAndBalancesEveryConnectedFlow() {
        var geometry=new PipeResistance.Geometry(10,.05,.000045,0);
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,fill(300000,0)),
                new PassiveNetwork.Reservoir(2,0,fill(100000,0)),new PassiveNetwork.Reservoir(3,0,fill(100000,0)),
                new PassiveNetwork.Reservoir(4,0,fill(180000,0),PassiveNetwork.NodeKind.JUNCTION)),List.of(
                new PassiveNetwork.Pipe(5,0,3,geometry),new PassiveNetwork.Pipe(6,3,1,geometry),new PassiveNetwork.Pipe(7,3,2,geometry)));
        var result=new PassiveStepSolver(model).solve(graph,.1,()->{});var flows=result.massFlows();
        assertTrue(flows[0]>0);assertEquals(flows[0],flows[1]+flows[2],1e-9);assertEquals(flows[1],flows[2],1e-9);
        assertEquals(result.states().get(1).pressure(),result.states().get(2).pressure(),1e-4);
    }
    @Test void bulkThreePhaseCrudeTransfersAllComponentsUpAnElevation() {
        String id="icheme:tjl20_methane";double[] composition=Arrays.copyOf(com.wormzjl.icheme.science.material.TiaJuanaTestCatalog.thermo(id).crudeFeed(com.wormzjl.icheme.science.material.TiaJuanaTestCatalog.ASSAY_ID).moleFractions(),model.componentCount());composition[model.componentCount()-1]=.2;
        var reservoirs=new ArrayList<PassiveNetwork.Reservoir>();
        for(double pressure:new double[]{200000,150000}) {
            double[] n=composition.clone();var unit=model.flashTP(350,pressure,n,()->{});for(int i=0;i<n.length;i++)n[i]/=unit.volume();
            reservoirs.add(new PassiveNetwork.Reservoir(reservoirs.size(),reservoirs.size()*2,model.flashTP(350,pressure,n,()->{})));
        }
        var graph=new PassiveNetwork(reservoirs,List.of(new PassiveNetwork.Pipe(4,0,1,new PipeResistance.Geometry(10,.05,.000045,0))));
        var result=new PassiveStepSolver(model).solve(graph,.01,()->{});assertTrue(result.massFlows()[0]>0);
        var oldReceiver=PhaseLayout.totalAmounts(reservoirs.get(1).state());var newReceiver=PhaseLayout.totalAmounts(result.states().get(1));
        // Every component of the feed (the refinery basis holds 26 the feed does not name; they stay zero).
        for(int i=0;i<oldReceiver.length;i++)if(composition[i]>0)assertTrue(newReceiver[i]>oldReceiver[i],"component "+i);else assertEquals(0,newReceiver[i],0.0,"component "+i);
        for(var state:result.states()){assertTrue(TestPhases.liquidVolume(state)>0);assertTrue(TestPhases.waterVolume(state)>0);assertTrue(state.vaporVolume()>0);}
    }
    @Test void aPreviouslyAbsentGasComponentCanEnterAReceivingReservoir() {
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,fill(200000,0)),new PassiveNetwork.Reservoir(2,0,fill(101325,1))),
                List.of(new PassiveNetwork.Pipe(3,0,1,new PipeResistance.Geometry(10,.05,.000045,0))));
        var result=new PassiveStepSolver(model).solve(graph,.01,()->{});
        assertTrue(TestPhases.vapor(result.states().get(1))[0]>0);assertTrue(result.massFlows()[0]>0);
        assertEquals(0,TestPhases.vapor(result.states().get(0))[1],1e-10);
    }
    /**
     * Finding D-02 (batch 2026-11-13-codebase-review): a heat side between two water tanks at 350 K, cooled by a 300 K wall.
     * The first step runs forward (3 bar to 1.5 bar); the second starts from that flow on swapped pressures, so its Newton
     * reverses the flow through zero onto the other end's stream. The first step samples its donor's curve alone; in the
     * second, the evaluation that first draws from the other end has that end sampled between two evaluations and is done
     * again: the step converges backward, its side books the cooling of the other end's water, and no curve is sampled
     * inside a residual or a Jacobian.
     */
    @Test void aHeatSideWhoseFlowReversesReadsTheCurvesItsPassSampled() {
        int water=model.componentCount()-1;
        var side=new HeatSide(2,HeatCurve.Films.DEFAULT,new double[]{300},0,0);
        var pipe=new PassiveNetwork.Pipe(3,0,1,new PipeResistance.Geometry(10,.05,.000045,0)).withHeat(side);
        var solver=new PassiveStepSolver(model);
        var forward=solver.solve(new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,fill(300000,water)),new PassiveNetwork.Reservoir(2,0,fill(150000,water))),List.of(pipe)),.1,()->{});
        assertTrue(forward.massFlows()[0]>0);
        long first=solver.heatCurveSamples();
        assertEquals(1,first,"the forward step samples its donor's end alone");
        var backward=solver.solve(new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,fill(150000,water)),new PassiveNetwork.Reservoir(2,0,fill(300000,water))),List.of(pipe)),.1,()->{});
        assertTrue(backward.massFlows()[0]<0,"the flow reversed: "+backward.massFlows()[0]);
        assertTrue(solver.heatCurveSamples()>first,"the second tank's end, on its new start state");
        assertTrue(solver.heatCurveSamples()<=first+2,"at most both ends once on the new start states: "+solver.heatCurveSamples());
        double heat=0;for(var b:backward.boundaries())if(b.kind()==ConservativeTransport.BoundaryTransfer.Kind.HEAT)heat+=b.totalEnergyJoule();
        assertTrue(heat<0,"the wall cools the water arriving from the second tank: "+heat);
        assertEquals(0,solver.heatCurvesInResidual(),"no curve sampled inside an evaluation");
    }
    /**
     * Finding D-02: a heat side whose flow does not reverse costs what it cost before the fix. Water from a 3 bar generator
     * through a cooled side into a junction, then a plain run to a 1 atm void: over the step one curve is sampled, the
     * generator's end's, and none for the junction's end it never draws from.
     */
    @Test void aHeatSideThatKeepsItsDirectionSamplesItsDonorsCurveAlone() {
        int water=model.componentCount()-1;var source=fill(300000,water);
        var run=new PipeResistance.Geometry(10,.05,.000045,0);
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,source,PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(2,0,source,PassiveNetwork.NodeKind.JUNCTION),new PassiveNetwork.Reservoir(3,0,fill(101325,water),PassiveNetwork.NodeKind.VOID)),
            List.of(new PassiveNetwork.Pipe(5,0,1,run).withHeat(new HeatSide(2,HeatCurve.Films.DEFAULT,new double[]{300},0,0)),new PassiveNetwork.Pipe(6,1,2,run)));
        var solver=new PassiveStepSolver(model);
        var result=solver.solve(graph,.1,()->{});
        assertTrue(result.massFlows()[0]>0);
        assertEquals(1,solver.heatCurveSamples(),"the donor's end alone");
        assertEquals(0,solver.heatCurvesInResidual());
    }
}
