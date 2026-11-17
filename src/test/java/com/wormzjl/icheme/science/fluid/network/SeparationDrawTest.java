package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.thermo.TestPhases;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a vessel carries as a slice input of its phase draws (batch 2026-11-17-three-phase-separator, package 2): a lying
 * drum with a boot, pentane over water under nitrogen, an outlet for its oil, its water and its gas.
 */
class SeparationDrawTest extends PortFixture {
    private static final int HC=10,BOOT=11,TOP=12,FEED=13;

    private FluidThermodynamics.State charge(double oil,double waterFraction,double capacity,double p) {
        double[] n=new double[mw.length];double t=298.15,gas=1-oil-waterFraction;int pentane=index("N-pentane");
        n[pentane]=oil*626/mw[pentane];n[water]=waterFraction*997/mw[water];n[nitrogen]=p*gas/(FluidThermodynamics.R*t);
        var state=model.flashTP(t,p,n,NOOP);
        for(int i=0;i<10;i++) {
            n[pentane]*=oil*capacity/TestPhases.liquidVolume(state);n[water]*=waterFraction*capacity/TestPhases.waterVolume(state);
            n[nitrogen]*=gas*capacity/state.vaporVolume();
            state=model.flashTP(t,p,n,NOOP);
        }
        return state;
    }
    /** The drum at 2.5 bar with its three outlets to the atmosphere, carrying {@code carried}; fed at 4 bar where {@code fed}. */
    private PassiveNetwork drum(VesselGeometry g,double oil,double waterFraction,Separation.Carried carried,boolean fed) {return drum(g,oil,waterFraction,carried,fed,.02);}
    private PassiveNetwork drum(VesselGeometry g,double oil,double waterFraction,Separation.Carried carried,boolean fed,double bore) {
        var state=charge(oil,waterFraction,g.capacity(),250000);
        var inventory=new PassiveNetwork.Inventory(g.capacity(),PhaseLayout.totalAmounts(state),state.internalEnergy(),state.solids(),g.height(),false,g).withCarried(carried);
        var nodes=new ArrayList<PassiveNetwork.Reservoir>();var pipes=new ArrayList<PassiveNetwork.Pipe>();
        nodes.add(new PassiveNetwork.Reservoir(1,0,state,PassiveNetwork.NodeKind.RESERVOIR,inventory));
        for(int i=0;i<3;i++)nodes.add(new PassiveNetwork.Reservoir(2+i,0,nitrogenAt(101325),PassiveNetwork.NodeKind.VOID));
        var line=new PipeResistance.Geometry(10,bore,PipeResistance.DEFAULT_ROUGHNESS_METRES,0);
        pipes.add(new PassiveNetwork.Pipe(HC,0,1,line).withPorts(PassiveNetwork.PhasePort.BOOT_HC,PassiveNetwork.PhasePort.BULK));
        pipes.add(new PassiveNetwork.Pipe(BOOT,0,2,line).withPorts(PassiveNetwork.PhasePort.BOOT_WATER,PassiveNetwork.PhasePort.BULK));
        pipes.add(new PassiveNetwork.Pipe(TOP,0,3,line).withPorts(PassiveNetwork.PhasePort.VAPOR,PassiveNetwork.PhasePort.BULK));
        if(fed) {
            // Oil and water in equal volumes at 4 bar.
            double[] n=new double[mw.length];n[index("N-pentane")]=626/mw[index("N-pentane")];n[water]=997/mw[water];
            nodes.add(new PassiveNetwork.Reservoir(5,0,model.flashTP(298.15,400000,n,NOOP),PassiveNetwork.NodeKind.GENERATOR));
            pipes.add(new PassiveNetwork.Pipe(FEED,4,0,new PipeResistance.Geometry(10,.03,PipeResistance.DEFAULT_ROUGHNESS_METRES,0)));
        }
        return new PassiveNetwork(nodes,pipes);
    }
    private static VesselGeometry sized(){return VesselGeometry.round(VesselGeometry.Form.HORIZONTAL_DRUM,2,6,2.0/3,.7);}
    private static double[] volumes(PassiveStepSolver.Result result,long pipe) {
        return result.pipeTransfers().stream().filter(t->t.pipeId()==pipe).findFirst().orElseThrow().forward().phaseVolumes();
    }
    /** Every component: what the drum lost is what its three outlets moved. */
    private void assertLedger(PassiveNetwork graph,PassiveStepSolver.Result result) {
        double[] before=graph.reservoirs().getFirst().inventory().moles(),after=result.inventories().getFirst().moles(),moved=new double[before.length];
        for(var transfer:result.pipeTransfers()){var n=transfer.forward().componentMoles();for(int c=0;c<n.length;c++)moved[c]+=n[c];}
        for(int c=0;c<before.length;c++)assertEquals(before[c]-after[c],moved[c],1e-9*Math.max(before[c],1e-6),model.components().get(c));
    }

    @Test void aVesselThatCarriesNothingDrawsItsPhasesClean() {
        var graph=drum(sized(),.4,.02,Separation.Carried.NONE,false);
        assertSame(graph,Separation.plan(model,graph,null));
        var result=new PassiveStepSolver(model).solve(graph,.1,NOOP);
        double[] hc=volumes(result,HC),boot=volumes(result,BOOT),top=volumes(result,TOP);
        assertTrue(hc[0]>0&&boot[1]>0&&top[2]>0);
        assertEquals(0,hc[1]+hc[2]+boot[0]+boot[2]+top[0]+top[1]);
        // The same graph with the plan's zeros written on it is the same record: nothing else reads it.
        assertEquals(graph.reservoirs().getFirst().inventory(),graph.reservoirs().getFirst().inventory().withCarried(new Separation.Carried(0,0,0)));
    }

    @Test void theOilOutletCarriesWaterAndTheWaterOutletOil() {
        var graph=drum(sized(),.4,.02,new Separation.Carried(.05,.2,0),false);
        var result=new PassiveStepSolver(model).solve(graph,.1,NOOP);
        double[] hc=volumes(result,HC),boot=volumes(result,BOOT),top=volumes(result,TOP);
        assertEquals(.05,hc[1]/hc[0],.05*2e-3);assertEquals(0,hc[2]);
        assertEquals(.2,boot[0]/boot[1],.2*2e-3);assertEquals(0,boot[2]);
        assertEquals(0,top[0]+top[1]);
        assertLedger(graph,result);
    }

    @Test void theGasOutletCarriesTheVesselsBulkAboveItsLimit() {
        var graph=drum(sized(),.4,.02,new Separation.Carried(0,0,.3),false);
        var start=graph.reservoirs().getFirst().state();
        double oil=FluidThermodynamics.phaseVolume(start,FluidThermodynamics.OIL),aqueous=FluidThermodynamics.phaseVolume(start,FluidThermodynamics.AQUEOUS),total=oil+aqueous+start.vaporVolume();
        var result=new PassiveStepSolver(model).solve(graph,.1,NOOP);
        double[] top=volumes(result,TOP),hc=volumes(result,HC);double all=top[0]+top[1]+top[2];
        assertEquals(.3*oil/total,top[0]/all,.3*oil/total*5e-3);
        assertEquals(.3*aqueous/total,top[1]/all,.3*aqueous/total*5e-3);
        assertEquals(0,hc[1]+hc[2]);
        assertLedger(graph,result);
    }

    @Test void aCarriedPhaseIsDrawnUpToWhatTheVesselHeld() {
        // 21 L of water, and an oil outlet on a 0.1 m line that would carry its own volume of it.
        var graph=drum(sized(),.4,1e-3,new Separation.Carried(1,0,0),false,.1);
        double held=FluidThermodynamics.phaseVolume(graph.reservoirs().getFirst().state(),FluidThermodynamics.AQUEOUS);
        var result=new PassiveStepSolver(model).solve(graph,1,NOOP);
        double[] hc=volumes(result,HC);
        assertTrue(hc[0]>held,"the oil drawn, "+hc[0]+" m3 against "+held+" m3 of water");
        assertTrue(hc[1]>0&&hc[1]<=held/3*(1+1e-9),"the water carried, "+hc[1]+" of "+held+" m3");
        assertLedger(graph,result);
    }

    @Test void thePlanReadsTheLastSliceAndASizedDrumStaysClean() {
        // A 0.5 m drum with a 0.25 m boot, fed oil and water in equal volumes: its boot's water leaves faster than oil rises.
        var small=VesselGeometry.round(VesselGeometry.Form.HORIZONTAL_DRUM,.5,4,.25,.4);
        var graph=drum(small,.4,.08,Separation.Carried.NONE,true);
        var solver=new PassiveIntervalSolver(model);
        var first=solver.solve(graph,2,PassiveIntervalSolver.Settings.defaults(),NOOP);
        var planned=Separation.plan(model,first.graph(),first);
        var carried=planned.reservoirs().getFirst().inventory().carried();
        assertTrue(carried.oilInWater()>.5,"oil in water "+carried.oilInWater());
        assertEquals(0,carried.waterInOil());
        var second=solver.solve(planned,2,PassiveIntervalSolver.Settings.defaults(),NOOP);
        double[] boot=second.pipeTransfers().stream().filter(t->t.pipeId()==BOOT).findFirst().orElseThrow().forward().phaseVolumes();
        assertEquals(carried.oilInWater(),boot[0]/boot[1],carried.oilInWater()*.02);
        // The slice input lasts the slice: the next plan replaces it, and with no slice behind it the drum is clean again.
        assertEquals(carried,second.graph().reservoirs().getFirst().inventory().carried());
        assertTrue(Separation.plan(model,second.graph(),null).reservoirs().getFirst().inventory().carried().none());
        // The sized drum on the same feed, its outlets on 5 mm lines: it keeps its phases apart. (On 20 mm lines it does
        // not: its 0.35 m2 boot settles under a litre of water a second.)
        var large=drum(sized(),.4,.02,Separation.Carried.NONE,true,.005);
        var slice=solver.solve(large,2,PassiveIntervalSolver.Settings.defaults(),NOOP);
        assertSame(slice.graph(),Separation.plan(model,slice.graph(),slice));
    }
}
