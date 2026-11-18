package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import static org.junit.jupiter.api.Assertions.*;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.icheme.science.fluid.solver.SparseNewton;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;

class ConservativeTransportTest {
    @Test void aClosedConnectionCannotBookARoundoffDrawOfAnAbsentPhase() {
        var model = FluidTestSupport.networkModel();
        var gas = model.initialNitrogenCharge(1,298.15,101325,() -> {});
        var nodes = List.of(new PassiveNetwork.Reservoir(1,0,gas,PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(2,0,gas));
        var graph = new PassiveNetwork(nodes,List.of(new PassiveNetwork.Pipe(3,0,1,new PipeResistance.Geometry(1,.05,.000045,0))));
        var states = List.of(gas,gas);
        double[][] draws = {new double[6]};
        draws[0][FluidThermodynamics.AQUEOUS] = 1.55e-112;
        var zero = new double[]{0};
        var balance = ConservativeTransport.reconstruct(graph,states,zero,zero,1,model,() -> {},null,false,draws,null,null,null);
        assertEquals(nodes.get(1).inventory().internalEnergy(),balance.inventories().get(1).internalEnergy(),0);
        var history = PipeTransfer.sample(model,graph,states,zero,1,draws).getFirst();
        assertEquals(0,history.forward().massKg());
        assertEquals(0,history.reverse().massKg());
        assertArrayEquals(new double[model.componentCount()],history.forward().componentMoles(),0);
    }

    @Test void simultaneousBranchTransfersRemainPositiveAndCannotInventAnAbsentComponent() {
        var model=com.wormzjl.icheme.fluid.support.FluidTestSupport.model(MaterialCatalog.bundled(),"icheme:tjl20_methane");
        var nodes=new ArrayList<PassiveNetwork.Reservoir>();
        for(int component:new int[]{0,1,1}) {
            double[] n=new double[model.componentCount()];n[component]=1/model.hydrocarbon.molecularWeight(component);
            nodes.add(new PassiveNetwork.Reservoir(nodes.size()+1,0,model.flashTP(350,101325,n,()->{})));
        }
        var geometry=new PipeResistance.Geometry(10,.05,.000045,0);
        var graph=new PassiveNetwork(nodes,List.of(new PassiveNetwork.Pipe(4,0,1,geometry),new PassiveNetwork.Pipe(5,0,2,geometry)));
        var states=nodes.stream().map(PassiveNetwork.Reservoir::state).toList();
        var projection=ConservativeTransport.reconstruct(graph,states,new double[]{.1,.2},new double[]{0,0},1,model,()->{});
        assertEquals(.7,projection.inventories().get(0).moles()[0]*model.hydrocarbon.molecularWeight(0),1e-12);
        assertEquals(.1,projection.inventories().get(1).moles()[0]*model.hydrocarbon.molecularWeight(0),1e-12);
        assertEquals(.2,projection.inventories().get(2).moles()[0]*model.hydrocarbon.molecularWeight(0),1e-12);
        for(var inventory:projection.inventories()){assertEquals(0,inventory.moles()[2]);for(double n:inventory.moles())assertTrue(n>=0);}
        assertThrows(SparseNewton.Nonconvergence.class,()->ConservativeTransport.reconstruct(graph,states,new double[]{.6,.6},new double[]{0,0},1,model,()->{}));
    }
    @Test void repeatedStepsKeepTheDeclaredVolumeBitForBitWhileCheckingTheDerivedVolume() {
        var model=FluidTestSupport.networkModel();
        var a=model.initialNitrogenCharge(1,320,110000,()->{});var b=model.initialNitrogenCharge(1,320,101325,()->{});
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,a,PassiveNetwork.NodeKind.RESERVOIR,new PassiveNetwork.Inventory(1,PhaseLayout.totalAmounts(a),a.internalEnergy())),
                new PassiveNetwork.Reservoir(2,0,b,PassiveNetwork.NodeKind.RESERVOIR,new PassiveNetwork.Inventory(1,PhaseLayout.totalAmounts(b),b.internalEnergy()))),
                List.of(new PassiveNetwork.Pipe(3,0,1,new PipeResistance.Geometry(100,.05,.000045,0))));
        var stepper=new PassiveStepSolver(model);
        for(int step=0;step<20;step++) {
            var result=stepper.solve(graph,.01,()->{});graph=PassiveIntervalSolver.replace(graph,result);
            for(var node:graph.reservoirs()){assertEquals(1,node.inventory().volume());assertEquals(1,node.state().volume(),1e-8);assertArrayEquals(node.inventory().moles(),PhaseLayout.totalAmounts(node.state()),1e-12);}
        }
    }
    /**
     * Decision D54 with D59 (2): a vessel that holds nothing and that nothing reaches in the step keeps what it holds, its
     * solids included. A pass that laid such a vessel out from an arrival the pinned flows then booked as nothing leaves the
     * Newton's candidate a roundoff of that arrival: in cold fuzz seed 85, 1.6e-45 mol of LNG vapour and 3.4e-93 kg of CO2-I
     * in an empty tank. The booking kept the tank's amounts and energy (none) but took the candidate's solids, so the tank
     * held 3.4e-93 kg of crystal from nowhere and no fluid: a dry-solids vessel at 298.15 K whose bulk end had nothing to
     * carry (a viscosity of 0/0), and the next interval's start flows were refused at every step size.
     */
    @Test void aVesselNothingReachesKeepsWhatItHoldsItsSolidsIncluded() {
        var model=FluidTestSupport.model(com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot(),"icheme:pilot_cryogenic");
        var crystal=model.crystalSolids().all().getFirst();
        var lng=model.flashTP(120,3e5,new double[]{0,.995,0,.005,0},()->{});
        var tank=PassiveNetwork.emptyVessel(2,0,1,model);
        var roundoff=new com.wormzjl.icheme.science.fluid.state.SolidInventory(List.of(new com.wormzjl.icheme.science.fluid.state.SolidInventory.Population(
                crystal.material(),new com.wormzjl.icheme.science.fluid.state.ParticleSize("0.00025"),3.4255257817720115e-93)));
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,lng),tank),
                List.of(new PassiveNetwork.Pipe(3,0,1,new PipeResistance.Geometry(10,.05,.000045,0))));
        // The Newton's candidate of the tank: laid out from what was to arrive, it holds a roundoff of it.
        var projection=ConservativeTransport.reconstruct(graph,List.of(lng,tank.state().withSolids(roundoff)),new double[]{0},new double[]{0},1,model,()->{});
        var kept=projection.inventories().get(1);
        assertTrue(kept.solids().empty(),"no crystal from nowhere: "+kept.solids());
        assertTrue(projection.states().get(1).solids().empty(),"the tank is stated with what it holds");
        assertArrayEquals(tank.inventory().moles(),kept.moles());
        assertEquals(tank.inventory().internalEnergy(),kept.internalEnergy());
        // Its next interval finds the tank empty, a receiver at the ambient: not a dry-solids vessel.
        assertTrue(new PassiveNetwork.Reservoir(2,0,projection.states().get(1),PassiveNetwork.NodeKind.RESERVOIR,kept).empty());
    }
    /**
     * F20 (batch 2026-11-13-codebase-review): an emptying cooling tower basin is solved as a junction that keeps its tower
     * ({@code PassiveStepSolver.emptyingGraph}). Its sprays are fed through a pipe junction whose Newton row closed only to a
     * residual (1.0 kg/s in, 1.001 out), so the pinning books the junction's outflow down to 1.0. The basin passes what its
     * sprays deliver less what they lose to the air, that loss read on the booked spray flow, as the booking reads it.
     */
    @Test void aTowerBasinSolvedAsAJunctionPassesItsBookedSprayLessItsLossOnThatSpray() {
        var model=FluidTestSupport.networkModel();int water=model.componentCount()-1;
        var amounts=new double[model.componentCount()];amounts[water]=1000/model.waterMolecularWeight;
        var hot=model.flashTP(308.15,101325,amounts,()->{});
        var tower=CoolingTower.induced(8,2,1,1);
        var basin=new PassiveNetwork.Inventory(tower.basinVolume(),PhaseLayout.totalAmounts(hot),hot.internalEnergy(),hot.solids(),CoolingTower.BASIN_DEPTH,true).withTower(tower);
        var line=new PipeResistance.Geometry(10,.05,.000045,0);
        var graph=new PassiveNetwork(List.of(
                new PassiveNetwork.Reservoir(1,0,hot,PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(2,0,hot,PassiveNetwork.NodeKind.JUNCTION),
                new PassiveNetwork.Reservoir(3,0,hot,PassiveNetwork.NodeKind.JUNCTION,basin),
                new PassiveNetwork.Reservoir(4,0,hot,PassiveNetwork.NodeKind.VOID)),
            List.of(new PassiveNetwork.Pipe(5,0,1,line),
                new PassiveNetwork.Pipe(6,1,2,line).withPorts(PassiveNetwork.PhasePort.BULK,PassiveNetwork.PhasePort.SPRAY),
                new PassiveNetwork.Pipe(7,2,3,line)));
        var states=graph.reservoirs().stream().map(PassiveNetwork.Reservoir::state).toList();
        var booked=ConservativeTransport.pinnedFlows(graph,states,new double[]{1,1.001,.98},model,null,null);
        assertEquals(1,booked[1],1e-15,"the pipe junction passes what it receives");
        double loss=TowerExchange.at(graph,2,states,booked,null,model).massLoss();
        assertTrue(loss>0,"the sprays lose water to the air: "+loss);
        assertEquals(booked[1]-loss,booked[2],1e-14,"the basin passes its booked spray less the loss on that spray");
    }
}
