package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.machine.SteamEjector;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The steam ejector as fluid connections (batch 2026-11-10-steam-ejector-flow, EJECTOR_FLOW_PLAN.md "Correlation form"): its
 * motive valve ({@link FlowControl.ValveSource#EJECTOR}) from 10 bar steam into its discharge junction at a set mass target;
 * its mover on the set's curve from a column top held at its pressure (a nitrogen generator: a closed vessel cannot stand
 * below the ambient, its wall goes slack, decision D54) into the same junction; the junction's mix to a 1.05 bar void. The
 * slice inputs (the valve's target, the curve's motive) are fixed here, as the runtime would set them.
 */
class SteamEjectorConnectionTest extends PortFixture {
    private static final SteamEjector SET=new SteamEjector(.1);
    private static final long NOZZLE=11,EJECTOR=12,DISCHARGE=13;
    private static final PipeResistance.Geometry BODY=new PipeResistance.Geometry(1,SET.suction(),PipeResistance.DEFAULT_ROUGHNESS_METRES,0),
            LINE=new PipeResistance.Geometry(1,.2,PipeResistance.DEFAULT_ROUGHNESS_METRES,0),
            MOTIVE=new PipeResistance.Geometry(1,SET.motiveBore(),PipeResistance.DEFAULT_ROUGHNESS_METRES,0);
    private static final double EXIT=1.05e5,STEAM=784/3600.;

    private FluidThermodynamics.State steam(double t,double p){var n=new double[mw.length];n[water]=1;return model.flashTP(t,p,n,NOOP);}

    /** The four nodes (the steam generator, the top, the discharge junction, the void) and the three connections. */
    private PassiveNetwork network(double top,FlowControl.Ejector ejector,double steam,boolean shut) {
        var supply=steam(460,1e6);var exit=model.initialNitrogenCharge(1,330,EXIT,NOOP);
        var discharge=new PassiveNetwork.Pipe(DISCHARGE,2,3,LINE);
        return PassiveNetwork.sizeJunctionHoldups(new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,supply,PassiveNetwork.NodeKind.GENERATOR),
                        new PassiveNetwork.Reservoir(2,0,nitrogenAt(top),PassiveNetwork.NodeKind.GENERATOR),
                        new PassiveNetwork.Reservoir(3,0,exit,PassiveNetwork.NodeKind.JUNCTION),new PassiveNetwork.Reservoir(4,0,exit,PassiveNetwork.NodeKind.VOID)),
                List.of(new PassiveNetwork.Pipe(NOZZLE,0,2,MOTIVE,FlowControl.ControlValve.ejector().withMassTarget(steam)),new PassiveNetwork.Pipe(EJECTOR,1,2,BODY,ejector),shut?discharge.withBlockedDirections(3):discharge)),model);
    }
    /** {@code graph} after {@code dt}, the fixed nodes kept, and that step's result. */
    private record Step(PassiveNetwork graph,PassiveStepSolver.Result result){}
    private Step step(PassiveNetwork graph,double dt) {
        var result=new PassiveStepSolver(model).solve(graph,dt,NOOP);var nodes=new ArrayList<PassiveNetwork.Reservoir>();
        for(int i=0;i<graph.reservoirs().size();i++){var node=graph.reservoirs().get(i);
            nodes.add(node.fixed()?node:new PassiveNetwork.Reservoir(node.id(),node.elevation(),result.states().get(i),node.kind(),result.inventories().get(i)));}
        return new Step(new PassiveNetwork(nodes,graph.pipes(),graph.scheduledTransfers()),result);
    }
    private static double flow(PassiveStepSolver.Result result,PassiveNetwork graph,long pipe) {
        for(int e=0;e<graph.pipes().size();e++)if(graph.pipes().get(e).id()==pipe)return result.massFlows()[e];
        throw new AssertionError("no pipe "+pipe);
    }

    /**
     * On a 10 kPa top the motive valve passes its target (the law's 784 kg/h); the set takes its curve's volume, 0.47 m3/s at
     * 60 m/s, 190 kg/h of nitrogen; the junction passes both on to the void; the set does no work.
     */
    @Test void aSetOnItsSteamTakesItsCurvesVolumeFromATop() {
        var graph=network(1e4,new FlowControl.Ejector(SET,1e6,1),STEAM,false);
        PassiveStepSolver.Result r=null;
        for(int k=0;k<10;k++){var s=step(graph,1);r=s.result();graph=s.graph();}
        double load=flow(r,graph,EJECTOR),jet=flow(r,graph,NOZZLE),out=flow(r,graph,DISCHARGE);
        System.out.println("EJECTOR_RUN load="+load*3600+" kg/h jet="+jet*3600+" kg/h out="+out*3600+" kg/h");
        assertEquals(STEAM,jet,.02*STEAM,"the motive valve passes its target");
        var top=nitrogenAt(1e4);double curve=SET.capacity(1e4,1e6,1)*top.mass()/top.volume();
        assertEquals(curve,load,.05*curve,"the curve's volume at the top's state");
        assertEquals(190/3600.,load,.05*190/3600.,"the owner's 197 kg/h of air, as nitrogen");
        assertEquals(load+jet,out,1e-3*out,"the junction passes both on");
        assertEquals(0,r.pumpWorkJoule(),1e-9,"no work enters the stream");
    }

    /** No steam: the set stands closed and the top keeps its gas. */
    @Test void withoutSteamTheSetStandsClosed() {
        var graph=network(1e4,new FlowControl.Ejector(SET),0,false);
        var s=step(graph,1);
        assertEquals(FlowControl.Mode.CLOSED,s.result().modes().get(1));
        assertEquals(0,flow(s.result(),graph,EJECTOR),0);
    }

    /** A shut discharge: the junction fills toward the steam's pressure, past the set's maximum discharge, and nothing passes. */
    @Test void aShutDischargePassesNothing() {
        var graph=network(1e4,new FlowControl.Ejector(SET,1e6,1),STEAM,true);
        PassiveStepSolver.Result last=null;
        for(int k=0;k<60;k++){var s=step(graph,1);last=s.result();graph=s.graph();}
        assertEquals(0,flow(last,graph,NOZZLE),1e-3*STEAM,"the valve stops once the junction stands at the steam's pressure");
        assertTrue(flow(last,graph,EJECTOR)<=1e-9,"the set passes nothing against a shut discharge: "+flow(last,graph,EJECTOR));
    }
}
