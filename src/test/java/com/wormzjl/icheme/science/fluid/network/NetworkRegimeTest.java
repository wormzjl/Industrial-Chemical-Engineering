package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import static org.junit.jupiter.api.Assertions.*;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import com.wormzjl.icheme.science.fluid.thermo.TestPhases;

class NetworkRegimeTest {
    @Test void serialSectionsPreserveIndependentLaminarAndFittingLosses() {
        var sections=List.of(new PipeResistance.Geometry(4,.02,.00001,2),new PipeResistance.Geometry(6,.01,.00002,3));
        var pipe=new PassiveNetwork.Pipe(1,0,1,sections,new FlowControl.Passive());
        double q=.0001,rho=1000,mu=.001,expected=0;
        for(var section:sections)expected+=128*mu*section.length()*q/(Math.PI*rho*Math.pow(section.diameter(),4))
                +section.minorLoss()*q*q/(2*rho*Math.pow(Math.PI*section.diameter()*section.diameter()/4,2));
        assertEquals(expected,pipe.loss(q,rho,mu).pressureDrop(),1e-12);
        assertEquals(-expected,pipe.loss(-q,rho,mu).pressureDrop(),1e-12);
    }
    private final FluidThermodynamics model=FluidTestSupport.networkModel();
    private final PipeResistance.Geometry geometry=new PipeResistance.Geometry(100,.02,.000045,0);
    private PassiveNetwork.Reservoir gas(long id,double t,double p,int component,PassiveNetwork.NodeKind kind) {
        var n=new double[com.wormzjl.icheme.science.material.MaterialTestBasis.NETWORK+1];n[component]=1;var unit=model.flashTP(t,p,n,()->{});n[component]/=unit.volume();
        return new PassiveNetwork.Reservoir(id,0,model.flashTP(t,p,n,()->{}),kind);
    }
    /** Total D46 stock, including the fluid set aside from the flowing mixture. */
    private double ownedMass(PassiveNetwork.Inventory inventory) {
        var n=inventory.moles();double m=inventory.solids().massKg();for(int c=0;c<n.length;c++)m+=n[c]*model.molecularWeight(c);return m;
    }
    @Test void twoDifferentFeedsMixAtAZeroHoldupJunction() {
        var graph=new PassiveNetwork(List.of(gas(1,350,220000,0,PassiveNetwork.NodeKind.GENERATOR),gas(2,400,210000,com.wormzjl.icheme.science.material.MaterialTestBasis.NITROGEN,PassiveNetwork.NodeKind.GENERATOR),
                gas(3,350,195000,com.wormzjl.icheme.science.material.MaterialTestBasis.NITROGEN,PassiveNetwork.NodeKind.JUNCTION),gas(4,350,180000,com.wormzjl.icheme.science.material.MaterialTestBasis.NITROGEN,PassiveNetwork.NodeKind.VOID)),List.of(
                new PassiveNetwork.Pipe(1,0,2,geometry),new PassiveNetwork.Pipe(2,1,2,geometry),new PassiveNetwork.Pipe(3,2,3,geometry)));
        var result=new PassiveStepSolver(model).solve(graph,1,()->{});var q=result.massFlows();assertTrue(q[0]>0&&q[1]>0);
        assertEquals(q[0]+q[1],q[2],1e-9);var mixture=result.states().get(2);var n=PhaseLayout.totalAmounts(mixture);
        var before=PassiveNetwork.sizeJunctionHoldups(graph,model).reservoirs().get(2).inventory();
        var after=result.inventories().get(2);
        assertEquals(ownedMass(before),ownedMass(after),1e-12);
        double methane=n[0]*model.molecularWeight(0)/mixture.mass();
        assertEquals(q[0]/q[2],methane,.001,"both inlet streams reach the outlet in the first step");
        double retainedMethane=(after.moles()[0]-before.moles()[0])*model.molecularWeight(0);
        assertEquals(q[0]-q[2]*methane,retainedMethane,1e-8,"methane retained in the junction over one second");
        double sent=0;
        for(int i=0;i<2;i++){var feed=graph.reservoirs().get(i).state();sent+=q[i]*feed.enthalpy()/feed.mass();}
        assertEquals(sent-q[2]*mixture.enthalpy()/mixture.mass(),after.internalEnergy()-before.internalEnergy(),.001);

    }
    @Test void reversingOneFeedSwitchesJunctionUpwindingAndSerialValvesRemainSolvable() {
        var reversed=new PassiveNetwork(List.of(gas(1,350,220000,0,PassiveNetwork.NodeKind.GENERATOR),gas(2,400,170000,com.wormzjl.icheme.science.material.MaterialTestBasis.NITROGEN,PassiveNetwork.NodeKind.RESERVOIR),
                gas(3,350,195000,com.wormzjl.icheme.science.material.MaterialTestBasis.NITROGEN,PassiveNetwork.NodeKind.JUNCTION),gas(4,350,180000,com.wormzjl.icheme.science.material.MaterialTestBasis.NITROGEN,PassiveNetwork.NodeKind.VOID)),List.of(
                new PassiveNetwork.Pipe(1,0,2,geometry),new PassiveNetwork.Pipe(2,1,2,geometry),new PassiveNetwork.Pipe(3,2,3,geometry)));
        var back=new PassiveStepSolver(model).solve(reversed,.1,()->{});assertTrue(back.massFlows()[1]<0);
        // Only methane now enters the junction, so its nitrogen holdup is flushed with the lag of its stock: over dt = 0.1 s
        // the nitrogen fraction falls from 1 to (m_J/dt)/(m_J/dt + Q_in).
        var junction=back.states().get(2);int nitrogen=com.wormzjl.icheme.science.material.MaterialTestBasis.NITROGEN;
        var previous=PassiveNetwork.sizeJunctionHoldups(reversed,model).reservoirs().get(2).inventory();
        double inflow=back.massFlows()[0];
        double fraction=PhaseLayout.totalAmounts(junction)[nitrogen]*model.hydrocarbon.molecularWeight(nitrogen)/junction.mass();
        assertEquals(0,fraction,.001,"the methane inlet displaces the initial nitrogen in the first step");
        double nitrogenLost=(previous.moles()[nitrogen]-back.inventories().get(2).moles()[nitrogen])*model.molecularWeight(nitrogen);
        assertEquals(.1*inflow*fraction,nitrogenLost,1e-8,"the D46 stock supplies exactly the discharged nitrogen");
        assertTrue(back.inventories().get(1).moles()[0]>0);
        var serial=new PassiveNetwork(List.of(gas(1,350,250000,com.wormzjl.icheme.science.material.MaterialTestBasis.NITROGEN,PassiveNetwork.NodeKind.GENERATOR),gas(2,350,200000,com.wormzjl.icheme.science.material.MaterialTestBasis.NITROGEN,PassiveNetwork.NodeKind.JUNCTION),gas(3,350,101325,com.wormzjl.icheme.science.material.MaterialTestBasis.NITROGEN,PassiveNetwork.NodeKind.VOID)),List.of(
                new PassiveNetwork.Pipe(1,0,1,geometry,new FlowControl.PressureValve(200000)),new PassiveNetwork.Pipe(2,1,2,geometry,new FlowControl.PressureValve(180000))));
        var result=new PassiveStepSolver(model).solve(serial,1,()->{});assertTrue(result.massFlows()[0]>0);assertEquals(result.massFlows()[0],result.massFlows()[1],1e-9);
        assertEquals(FlowControl.Mode.VALVE_OPEN,result.modes().getFirst());
    }
    @Test void aRejectedTrialRestartsFromTheSameConservedStateAtTheSmallerStep() {
        var graph=new PassiveNetwork(List.of(gas(1,350,200000,com.wormzjl.icheme.science.material.MaterialTestBasis.NITROGEN,PassiveNetwork.NodeKind.RESERVOIR),gas(2,350,101325,com.wormzjl.icheme.science.material.MaterialTestBasis.NITROGEN,PassiveNetwork.NodeKind.RESERVOIR)),List.of(new PassiveNetwork.Pipe(1,0,1,geometry)));
        var calls=new AtomicInteger();var before=graph.reservoirs().stream().map(PassiveNetwork.Reservoir::inventory).toList();
        var rejected=new PassiveIntervalSolver(model).solve(graph,1,PassiveIntervalSolver.Settings.defaults(),()->{if(calls.incrementAndGet()==30)throw new com.wormzjl.icheme.science.fluid.solver.SparseNewton.Nonconvergence("Forced rejection");});
        var fresh=new PassiveIntervalSolver(model).solve(graph,1,new PassiveIntervalSolver.Settings(.5,20,1024),()->{});
        assertTrue(rejected.rejectedSubsteps()>0);assertArrayEquals(fresh.averageMassFlows(),rejected.averageMassFlows(),1e-8);
        assertEquals(before,graph.reservoirs().stream().map(PassiveNetwork.Reservoir::inventory).toList());
    }
    @Test void pumpShutoffUsesAddedPressureAndTankRecycleIsAllowed() {
        var source=gas(1,350,1e6,com.wormzjl.icheme.science.material.MaterialTestBasis.NITROGEN,PassiveNetwork.NodeKind.GENERATOR);
        // Nitrogen is moved by a compressor (decision D2; a liquid-only pump refuses gas, decision D1). Its limit is a
        // pressure ratio (decision D7): r_max = 1.5 on the 1 MPa generator adds 500 kPa, the bracket this test always had.
        for(double p:new double[]{1.49e6,1.51e6}) {
            var graph=new PassiveNetwork(List.of(source,gas(2,350,p,com.wormzjl.icheme.science.material.MaterialTestBasis.NITROGEN,PassiveNetwork.NodeKind.VOID)),List.of(new PassiveNetwork.Pipe(1,0,1,geometry,new FlowControl.Compressor(.001,1.5))));
            var result=new PassiveStepSolver(model).solve(graph,1,()->{});
            if(p<1.5e6)assertTrue(result.massFlows()[0]>0);else assertEquals(0,result.massFlows()[0],1e-9);
        }
        var recycle=new PassiveNetwork(List.of(gas(1,350,150000,com.wormzjl.icheme.science.material.MaterialTestBasis.NITROGEN,PassiveNetwork.NodeKind.RESERVOIR),gas(2,350,150000,com.wormzjl.icheme.science.material.MaterialTestBasis.NITROGEN,PassiveNetwork.NodeKind.RESERVOIR)),List.of(
                new PassiveNetwork.Pipe(1,0,1,geometry,new FlowControl.Compressor(.001,1.5)),new PassiveNetwork.Pipe(2,1,0,geometry)));
        var result=new PassiveIntervalSolver(model).solve(recycle,1,PassiveIntervalSolver.Settings.defaults(),()->{});
        assertTrue(result.averageMassFlows()[0]>0&&result.averageMassFlows()[1]>0);assertTrue(result.pumpWorkJoule()>0);
    }
    @Test void bypassCanPreventAValveFromMaintainingItsUpstreamSetpoint() {
        var graph=new PassiveNetwork(List.of(gas(1,350,180000,com.wormzjl.icheme.science.material.MaterialTestBasis.NITROGEN,PassiveNetwork.NodeKind.RESERVOIR),gas(2,350,101325,com.wormzjl.icheme.science.material.MaterialTestBasis.NITROGEN,PassiveNetwork.NodeKind.RESERVOIR)),List.of(
                new PassiveNetwork.Pipe(1,0,1,new PipeResistance.Geometry(10,.05,.000045,0),new FlowControl.PressureValve(175000)),
                new PassiveNetwork.Pipe(2,0,1,new PipeResistance.Geometry(10,.05,.000045,0))));
        var result=new PassiveIntervalSolver(model).solve(graph,1,PassiveIntervalSolver.Settings.defaults(),()->{});
        assertTrue(result.graph().reservoirs().getFirst().state().pressure()<175000);assertTrue(result.averageMassFlows()[1]>0);
    }
    @Test void hotNitrogenCanEvaporateTheLastFreeWaterWithoutDiscardingIt() {
        double[] n=new double[com.wormzjl.icheme.science.material.MaterialTestBasis.NETWORK+1];n[com.wormzjl.icheme.science.material.MaterialTestBasis.NITROGEN]=40;n[com.wormzjl.icheme.science.material.MaterialTestBasis.NETWORK]=5;var initial=model.flashTP(298.15,150000,n,()->{});double scale=1/initial.volume();for(int i=0;i<n.length;i++)n[i]*=scale;
        var tank=new PassiveNetwork.Reservoir(2,0,model.flashTP(298.15,150000,n,()->{}));assertTrue(TestPhases.waterLiquid(tank.state())>0);
        var graph=new PassiveNetwork(List.of(gas(1,500,500000,com.wormzjl.icheme.science.material.MaterialTestBasis.NITROGEN,PassiveNetwork.NodeKind.GENERATOR),tank),List.of(new PassiveNetwork.Pipe(1,0,1,geometry)));
        for(int i=0;i<3;i++)graph=new PassiveIntervalSolver(model).solve(graph,20,PassiveIntervalSolver.Settings.defaults(),()->{}).graph();
        var end=graph.reservoirs().get(1);assertEquals(0,TestPhases.waterLiquid(end.state()),1e-9,"T="+end.state().temperature()+" P="+end.state().pressure());assertEquals(n[com.wormzjl.icheme.science.material.MaterialTestBasis.NETWORK],end.inventory().moles()[com.wormzjl.icheme.science.material.MaterialTestBasis.NETWORK],1e-8);
    }
    @Test void hydrocarbonLiquidCanDisappearIntoTheSharedGasPhase() {
        int pentane=model.hydrocarbon.components().indexOf("N-pentane");assertTrue(pentane>=0);
        double[] n=new double[com.wormzjl.icheme.science.material.MaterialTestBasis.NETWORK+1];n[com.wormzjl.icheme.science.material.MaterialTestBasis.NITROGEN]=30;n[pentane]=50;var unit=model.flashTP(298.15,150000,n,()->{});for(int i=0;i<n.length;i++)n[i]/=unit.volume();
        var tank=new PassiveNetwork.Reservoir(2,0,model.flashTP(298.15,150000,n,()->{}));assertTrue(TestPhases.liquidVolume(tank.state())>0);
        var graph=new PassiveNetwork(List.of(gas(1,500,500000,com.wormzjl.icheme.science.material.MaterialTestBasis.NITROGEN,PassiveNetwork.NodeKind.GENERATOR),tank,gas(3,350,101325,com.wormzjl.icheme.science.material.MaterialTestBasis.NITROGEN,PassiveNetwork.NodeKind.VOID)),
                List.of(new PassiveNetwork.Pipe(1,0,1,geometry),new PassiveNetwork.Pipe(2,1,2,geometry)));
        double removed=0;
        for(int i=0;i<6;i++) {var result=new PassiveIntervalSolver(model).solve(graph,20,PassiveIntervalSolver.Settings.defaults(),()->{});graph=result.graph();for(var entry:result.boundaries())if(entry.nodeId()==3)removed-=entry.moles()[pentane];}
        var end=graph.reservoirs().get(1);assertEquals(0,TestPhases.liquidVolume(end.state()),1e-12);assertEquals(n[pentane],end.inventory().moles()[pentane]+removed,1e-8);
    }
    @Test void cancellationCannotLeavePartialTransfersAndExcessVelocityIsConservativelyLimited() {
        var graph=new PassiveNetwork(List.of(gas(1,350,200000,com.wormzjl.icheme.science.material.MaterialTestBasis.NITROGEN,PassiveNetwork.NodeKind.RESERVOIR),gas(2,350,101325,com.wormzjl.icheme.science.material.MaterialTestBasis.NITROGEN,PassiveNetwork.NodeKind.RESERVOIR)),List.of(new PassiveNetwork.Pipe(1,0,1,geometry)));
        var solver=new PassiveIntervalSolver(model);var count=new AtomicInteger();
        assertThrows(CancellationException.class,()->solver.solve(graph,1,PassiveIntervalSolver.Settings.defaults(),()->{if(count.incrementAndGet()==30)throw new CancellationException();}));
        var retry=solver.solve(graph,1,new PassiveIntervalSolver.Settings(.1,.1,1024),()->{});
        var fresh=new PassiveIntervalSolver(model).solve(graph,1,new PassiveIntervalSolver.Settings(.1,.1,1024),()->{});
        assertArrayEquals(fresh.averageMassFlows(),retry.averageMassFlows(),1e-8);
        var sonic=new PassiveNetwork(List.of(gas(1,350,101325,com.wormzjl.icheme.science.material.MaterialTestBasis.NITROGEN,PassiveNetwork.NodeKind.GENERATOR),gas(2,350,101325,com.wormzjl.icheme.science.material.MaterialTestBasis.NITROGEN,PassiveNetwork.NodeKind.VOID)),List.of(
                // A compressor (decision D2) whose ratio adds about 500 kPa on the 1 atm generator, the rise the pump had.
                new PassiveNetwork.Pipe(1,0,1,new PipeResistance.Geometry(.1,.01,0,0),new FlowControl.Compressor(1,6))));
        var capped=new PassiveStepSolver(model).solve(sonic,1,()->{});var upstream=sonic.reservoirs().getFirst().state();
        double expected=upstream.mass()/upstream.volume()*sonic.pipes().getFirst().minimumArea()*model.velocityLimit(upstream);
        assertEquals(expected,capped.massFlows()[0],1e-10+expected*1e-8);assertEquals(FlowControl.Mode.MOVER_LAW,capped.modes().getFirst());assertTrue(Math.abs(capped.massFlows()[0])<expected,"the saturated law stays under the cap");
    }
}
