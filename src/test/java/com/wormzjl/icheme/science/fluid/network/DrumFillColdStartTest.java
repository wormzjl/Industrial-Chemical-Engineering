package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.SimulationTime;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The interval the level loop's blanketed drum held on in the fluid GameTests (batch 2026-11-11-aqueous-solvents,
 * FINAL_LANE_FIXES_REVIEW.md section 3), as the server dumped it: the island of
 * {@code aBlanketedDrumOfTwoCyclesHoldsItsLevelOnTheCadence} 0.4 s after its feed came on, recompiled by the level valve's
 * first law, so solved cold. The drum is a slack bag of nitrogen over 7.9 L of water; the feed valve regulates 19.8 kg/s,
 * the pad 6.4 kg/s, and the level valve is set to no flow. The 1 L junction between the drum's bottom outlet and that valve
 * holds froth: water with 0.15 % of nitrogen, 72 % gas by volume.
 *
 * <p>Two rules of a cold solve's start close it in one step. A flow valve set to no flow starts closed
 * ({@code PassiveStepSolver.initialModes}): started open, the outlet drew 790 kg/s out of 7.9 L. A junction that keeps its
 * stock over the step is seeded with that stock ({@code PassiveStepSolver.stocked}): seeded as the drum's liquid with 1e-7
 * of its volume as gas, its rows asked for the froth back and the Newton crossed to all vapour at every step size.
 */
class DrumFillColdStartTest {
    private static final double BORE=.25,FEED=19.810809642459418;
    private final FluidThermodynamics model=FluidTestSupport.networkModel().withTime(SimulationTime.of(4)).on(List.of("Nitrogen"),true);

    private static double[] v(double nitrogen,double water){return new double[]{nitrogen,water};}
    private PassiveNetwork.Reservoir node(long id,double elevation,PassiveNetwork.NodeKind kind,double t,double p,double[] vapor,double[] aqueous,double[] moles,
                                          double energy,VesselGeometry geometry,double blanket) {
        var inventory=new PassiveNetwork.Inventory(geometry.capacity(),moles,energy,SolidInventory.EMPTY,geometry.height(),false,geometry,null,null,false).withBlanket(blanket);
        return new PassiveNetwork.Reservoir(id,elevation,model.state(t,p,vapor,v(0,0),aqueous),kind,inventory);
    }
    private static PassiveNetwork.Pipe pipe(long id,int first,int second,double length,PassiveNetwork.PhasePort firstPort,PassiveNetwork.PhasePort secondPort,FlowControl control,double actuatorArea) {
        return new PassiveNetwork.Pipe(id,first,second,List.of(new PipeResistance.Geometry(length,BORE,PipeResistance.DEFAULT_ROUGHNESS_METRES,0)),control,0,null,firstPort,secondPort,0).withActuatorArea(actuatorArea);
    }
    /** The island as the server held it: nodes 0 the drum, 1 the water generator, 2 the feed junction, 3 the outlet junction, 4 the void, 5 the nitrogen generator. */
    private PassiveNetwork island() {
        var drum=VesselGeometry.round(VesselGeometry.Form.HORIZONTAL_DRUM,1.5,4,0,0);
        assertEquals(7.9521564043991635,drum.capacity());
        var bulk=PassiveNetwork.PhasePort.BULK;
        return new PassiveNetwork(List.of(
                node(2157,-48,PassiveNetwork.NodeKind.RESERVOIR,296.67725922417674,101325.0,v(68.50305847110286,2.0500653231688672),v(0.005108738589271158,437.10082454347923),
                        v(68.50816720969213,439.15088986664813),-1.9464583666897666E7,drum,121325.0),
                node(2158,-39,PassiveNetwork.NodeKind.GENERATOR,300.0,300000.0,v(0,0),v(0.0,55322.70143344549),v(0.0,55322.70143344549),-2.4272484568893824E9,VesselGeometry.prism(1,1),0),
                node(2162,-43,PassiveNetwork.NodeKind.JUNCTION,299.9913940353876,339074.3262113065,v(0,0),v(0.0,55.508435061791985),v(0.0,54309.38769583527),-2.3824954539073524E9,
                        VesselGeometry.prism(0.0010034228151024876,1),0),
                node(2166,-47,PassiveNetwork.NodeKind.JUNCTION,299.9607259843792,84874.3842858667,v(0.08394583455208271,0.0036864832003983706),v(5.157926121832809E-4,55.420286951436715),
                        v(82.56497211126025,54179.38269205035),-2.3769882626599965E9,VesselGeometry.prism(0.001003452712526662,1),0),
                node(2173,-53,PassiveNetwork.NodeKind.VOID,300.0,101325.0,v(40.63303111337175,0.0),v(0,0),v(40.63303111337175,0.0),-99431.38563383385,VesselGeometry.prism(1,1),0),
                node(2174,-45,PassiveNetwork.NodeKind.GENERATOR,300.0,300000.0,v(120.3667498723309,0.0),v(0,0),v(120.3667498723309,0.0),-296099.73345682357,VesselGeometry.prism(1,1),0)),
                List.of(
                pipe(6098361802743368302L,0,3,1.5,PassiveNetwork.PhasePort.LIQUID,bulk,new FlowControl.Passive(),0),
                pipe(232973820362946158L,2,0,2.5,bulk,bulk,new FlowControl.ControlValve(FlowControl.ValveSource.MASS_FLOW,200000,FEED,.01,UUID.fromString("8e44cc40-fcce-4f3b-9158-51c151d9f01c"),1),0.04908738521234052),
                pipe(9208667650061227080L,5,0,2.0,bulk,PassiveNetwork.PhasePort.VAPOR,new FlowControl.ControlValve(FlowControl.ValveSource.BLANKET,200000,6.762295954857196,.01,FlowControl.ControlValve.BLANKET_SENSOR,0),0.001963495408493621),
                pipe(5679322378823117499L,1,2,3.5,bulk,bulk,new FlowControl.Passive(),0),
                pipe(3745073015489887820L,3,4,6.5,bulk,bulk,new FlowControl.ControlValve(FlowControl.ValveSource.VOLUME_FLOW,200000,1,0,UUID.fromString("cbf8b1f8-0adb-4692-a5e8-d0def2f3803f"),1),0.04908738521234052)));
    }

    @Test void theDrumFillsOnItsFirstStepWithItsOutletShutAndTheJunctionKeepingItsFroth() {
        var graph=island();
        var solver=new PassiveIntervalSolver(model);
        solver.replayStart(graph,null);
        var result=solver.solve(graph,.8,new PassiveIntervalSolver.Settings(4,80,1024),()->{},.8,Map.of(),Map.of());
        assertEquals(1,result.acceptedSubsteps(),"one step");
        assertEquals(0,result.rejectedSubsteps(),"no step refused: the level valve set to no flow starts closed");
        double[] flows=result.averageMassFlows();
        assertEquals(0,flows[0],1e-6,"nothing leaves the drum");
        assertEquals(0,flows[4],1e-6,"the level valve passes nothing");
        assertEquals(FEED,flows[1],1e-9,"the feed valve regulates");
        var drum=result.graph().reservoirs().get(0);
        assertEquals(.8*FEED,(drum.inventory().moles()[1]-graph.reservoirs().get(0).inventory().moles()[1])*.01801528,1e-3,"the feed stays in the drum (kg of water)");
        assertEquals(.8*FEED,model.liquidMass(drum.state())-model.liquidMass(graph.reservoirs().get(0).state()),.2,"as liquid, less what the pad's dry gas takes up (kg)");
        var junction=result.graph().reservoirs().get(3).state();
        double[] held=junction.totals();
        assertEquals(82.56497211126025/(82.56497211126025+54179.38269205035),held[0]/(held[0]+held[1]),1e-5,"the outlet junction keeps its froth");
        assertTrue(junction.vaporVolume()>junction.condensedVolume(),"mostly gas by volume");
    }
}
