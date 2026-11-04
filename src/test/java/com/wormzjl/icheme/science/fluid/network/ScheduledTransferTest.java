package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;

class ScheduledTransferTest {
    private final FluidThermodynamics model=FluidTestSupport.networkModel();
    private PassiveNetwork.Reservoir gas(long id,double p,double z){return new PassiveNetwork.Reservoir(id,z,model.initialNitrogenCharge(1,298.15,p,()->{}));}
    private double energy(PassiveNetwork graph){double total=0;for(var node:graph.reservoirs())if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR)total+=node.inventory().internalEnergy();return total;}
    /** The withdrawal carries the tank's enthalpy alone: no stream carries potential energy (owner decision D37, S1). */
    @Test void bulkWithdrawalIsDistributedInTimeAndCarriesItsEnthalpy() {
        var initial=new PassiveNetwork(List.of(gas(1,150000,10),gas(2,149000,20)),List.of(new PassiveNetwork.Pipe(8,0,1,new PipeResistance.Geometry(100,.02,.000045,0))),List.of(new ScheduledTransfer.Withdrawal(90,1,.001)));
        var result=new PassiveIntervalSolver(model).solve(initial,5,PassiveIntervalSolver.Settings.defaults(),()->{});
        double removedMass=0,removedEnergy=0;
        for(var transfer:result.boundaries())if(transfer.nodeId()==90) {
            removedMass-=transfer.moles()[com.wormzjl.icheme.science.material.MaterialTestBasis.NITROGEN]*model.hydrocarbon.molecularWeight(com.wormzjl.icheme.science.material.MaterialTestBasis.NITROGEN);removedEnergy-=transfer.totalEnergyJoule();
        }
        assertEquals(.005,removedMass,1e-12);assertEquals(energy(initial),energy(result.graph())+removedEnergy,1e-5);
        assertTrue(result.graph().reservoirs().get(1).state().temperature()<298.15);
    }
    @Test void waterCanBeDeliveredOverFiveFifteenAndThirtySecondsWithoutAnInstantaneousInventoryJump() {
        for(double seconds:new double[]{5,15,30}) {
            var tank=gas(1,101325,12);double[] water=new double[com.wormzjl.icheme.science.material.MaterialTestBasis.NETWORK+1];water[com.wormzjl.icheme.science.material.MaterialTestBasis.NETWORK]=1;var stream=model.flashTP(298.15,101325,water,()->{});
            double[] rates=new double[com.wormzjl.icheme.science.material.MaterialTestBasis.NETWORK+1];rates[com.wormzjl.icheme.science.material.MaterialTestBasis.NETWORK]=100/model.waterMolecularWeight/seconds;
            double energyRate=100/seconds*(stream.enthalpy()/stream.mass());
            var graph=new PassiveNetwork(List.of(tank),List.of(),List.of(new ScheduledTransfer.Injection(90,0,rates,energyRate)));
            var result=new PassiveIntervalSolver(model).solve(graph,seconds,PassiveIntervalSolver.Settings.defaults(),()->{});
            var after=result.graph().reservoirs().getFirst();assertEquals(100,after.inventory().moles()[com.wormzjl.icheme.science.material.MaterialTestBasis.NETWORK]*model.waterMolecularWeight,1e-8);
            assertEquals(tank.inventory().moles()[com.wormzjl.icheme.science.material.MaterialTestBasis.NITROGEN],after.inventory().moles()[com.wormzjl.icheme.science.material.MaterialTestBasis.NITROGEN],1e-10);
            assertEquals(energy(graph)+seconds*energyRate,energy(result.graph()),1e-4);
            assertTrue(after.state().waterLiquid()>0);assertEquals(0,tank.inventory().moles()[com.wormzjl.icheme.science.material.MaterialTestBasis.NETWORK]);
        }
    }
    @Test void anUnsupportedWithdrawalPublishesNoPartialInterval() {
        var tank=gas(1,101325,0);var graph=new PassiveNetwork(List.of(tank),List.of(),List.of(new ScheduledTransfer.Withdrawal(90,0,1)));
        var before=tank.inventory();assertThrows(com.wormzjl.icheme.science.fluid.solver.SparseNewton.Nonconvergence.class,()->new PassiveIntervalSolver(model).solve(graph,5,PassiveIntervalSolver.Settings.defaults(),()->{}));
        assertEquals(before,graph.reservoirs().getFirst().inventory());
    }

    // ---------------- from ScheduledTransferEstimatorTest ----------------
    @Test void changingGasCompositionAndWithdrawnEnergyMatchTighterStepDoubling() {
        var gas=model.initialNitrogenCharge(1,320,300000,()->{});double[] methane=new double[com.wormzjl.icheme.science.material.MaterialTestBasis.NETWORK+1];methane[0]=1;
        var injected=model.flashTP(320,300000,methane,()->{});
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,gas)),List.of(),List.of(new ScheduledTransfer.Injection(-1,0,methane,injected.enthalpy()),new ScheduledTransfer.Withdrawal(-2,0,.03)));
        compare(graph);
    }
    @Test void wetCrudePipeAndSimultaneousModuleBoundariesMatchTighterStepDoubling() {
        var composition=Arrays.copyOf(V3PengRobinsonThermo.fromRegisteredPackage("icheme:tjl20_methane_nitrogen").crudeFeed("icheme:tia_juana_light_methane").moleFractions(),com.wormzjl.icheme.science.material.MaterialTestBasis.NETWORK+1);composition[com.wormzjl.icheme.science.material.MaterialTestBasis.NITROGEN]=.1;composition[com.wormzjl.icheme.science.material.MaterialTestBasis.NETWORK]=.2;
        var nodes=new ArrayList<PassiveNetwork.Reservoir>();
        for(int i=0;i<2;i++){var n=composition.clone();var unit=model.flashTP(350,150100-i*100,n,()->{});for(int c=0;c<com.wormzjl.icheme.science.material.MaterialTestBasis.NETWORK+1;c++)n[c]/=unit.volume();nodes.add(new PassiveNetwork.Reservoir(i+1,0,model.flashTP(350,150100-i*100,n,()->{})));}
        double[] water=new double[com.wormzjl.icheme.science.material.MaterialTestBasis.NETWORK+1];water[com.wormzjl.icheme.science.material.MaterialTestBasis.NETWORK]=.01/model.waterMolecularWeight;var feed=model.flashTP(350,150100,water,()->{});
        var graph=new PassiveNetwork(nodes,List.of(new PassiveNetwork.Pipe(9,0,1,new PipeResistance.Geometry(20,.05,.000045,0))),List.of(new ScheduledTransfer.Injection(-1,0,water,feed.enthalpy()),new ScheduledTransfer.Withdrawal(-2,1,.012)));
        compare(graph);
    }
    private void compare(PassiveNetwork graph) {
        var actual=new PassiveIntervalSolver(model).solve(graph,5,PassiveIntervalSolver.Settings.defaults(),()->{});
        var reference=new PassiveIntervalSolver(model).solve(graph,5,new PassiveIntervalSolver.Settings(.005,.05,4096),()->{});
        for(int i=0;i<graph.reservoirs().size();i++) {
            var a=actual.graph().reservoirs().get(i).state();var r=reference.graph().reservoirs().get(i).state();
            assertEquals(r.pressure(),a.pressure(),.001*r.pressure());assertEquals(r.temperature(),a.temperature(),.05);assertEquals(r.mass(),a.mass(),.001*r.mass());
        }
        var a=totals(actual.boundaries());var r=totals(reference.boundaries());assertEquals(r.keySet(),a.keySet());
        // A boundary's components within 1 % of its total amount (TR-BDF2: 0.1 % of each component). Backward Euler withdraws
        // at the step's end composition, and the state cap does not see a composition integral: on the single-tank fixture
        // one 5 s step withdraws 0.160 mol of the injected methane against 0.120 refined, 0.74 % of the 5.41 mol withdrawn
        // (WP1 of the mixed-gas junction batch). Energy stays at 0.1 %.
        for(long id:r.keySet()){double total=0;int width=model.componentCount();for(int c=0;c<width;c++)total+=Math.abs(r.get(id)[c]);
            for(int c=0;c<=width;c++)assertEquals(r.get(id)[c],a.get(id)[c],c==width?1e-4+.001*Math.abs(r.get(id)[c]):1e-10+.01*total,"Boundary "+id+" component/energy "+c);}
        for(int i=0;i<actual.averageMassFlows().length;i++)assertEquals(reference.averageMassFlows()[i],actual.averageMassFlows()[i],1e-9+.002*Math.abs(reference.averageMassFlows()[i]));
        assertTrue(actual.acceptedSubsteps()<reference.acceptedSubsteps(),"The controller did not reduce accepted work in this smooth fixture");
    }
    private Map<Long,double[]> totals(List<ConservativeTransport.BoundaryTransfer> transfers) {
        int width=model.componentCount();
        var result=new HashMap<Long,double[]>();for(var transfer:transfers){var sum=result.computeIfAbsent(transfer.nodeId(),id->new double[width+1]);var n=transfer.moles();for(int c=0;c<width;c++)sum[c]+=n[c];sum[width]+=transfer.totalEnergyJoule();}return result;
    }
}
