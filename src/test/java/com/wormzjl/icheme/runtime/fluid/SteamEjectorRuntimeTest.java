package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.network.PassiveStepSolver;
import com.wormzjl.icheme.science.fluid.network.PipeResistance;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.icheme.science.machine.SteamEjector;
import java.util.List;
import org.junit.jupiter.api.Test;
import static com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A placed steam ejector (batch 2026-11-10-steam-ejector-flow, EJECTOR_FLOW_PLAN.md "Correlation form" and "In line with the
 * column ejector") on the real topology compiler and coordinator, its slice inputs from {@link Ejectors}: 10 bar steam from a
 * generator to its motive port, a column top held at a fixed pressure (a nitrogen generator) on its suction, a 1.05 bar void
 * on its discharge, each on its own laid line. Its curve takes the sheet's volume at the top's pressure and its motive valve
 * passes the steam the sheet's law asks for that load.
 */
class SteamEjectorRuntimeTest {
    private static final SteamEjector SET=new SteamEjector(.1);
    private static final int ALONG=1<<WEST.ordinal()|1<<EAST.ordinal();

    private record Plant(EventPathWorld world,long ejector){}
    /** The plant on a top at {@code top} Pa; {@code steam} false leaves the motive line without its generator. */
    private static Plant plant(double top,boolean steam) {
        var w=new EventPathWorld();w.coordinator.ports(new Ejectors(w.coordinator::model));
        var water=FluidDeviceSpec.water(EventPathWorld.CATALOG).composition();
        var nitrogen=FluidDeviceSpec.nitrogen(EventPathWorld.CATALOG).composition();
        if(steam)w.place(0,64,0,Kind.GENERATOR,EAST,new FlowControl.Passive(),new FluidDeviceSpec(1,460,1e6,water),.05);
        for(int x=1;x<=2;x++)w.placeLaid(x,64,0,EAST,.05,ALONG);
        w.place(0,64,2,Kind.GENERATOR,EAST,new FlowControl.Passive(),new FluidDeviceSpec(1,298.15,top,nitrogen),.1);
        for(int x=1;x<=2;x++)w.placeLaid(x,64,2,EAST,.1,ALONG);
        w.place(0,64,4,Kind.VOID,EAST,new FlowControl.Passive(),new FluidDeviceSpec(1,330,1.05e5,nitrogen),.2);
        for(int x=1;x<=2;x++)w.placeLaid(x,64,4,EAST,.2,ALONG);
        long id=w.ledger.nextIdentity();
        var suction=new PhysicalFluidTopology.Position(EventPathWorld.DIM,3,64,2);
        var ports=List.of(new PhysicalFluidTopology.Port(suction,WEST,PassiveNetwork.PhasePort.BULK,PhysicalFluidTopology.ConnectionRole.SUCTION),
                new PhysicalFluidTopology.Port(new PhysicalFluidTopology.Position(EventPathWorld.DIM,3,64,0),WEST,PassiveNetwork.PhasePort.BULK,PhysicalFluidTopology.ConnectionRole.INLET),
                new PhysicalFluidTopology.Port(new PhysicalFluidTopology.Position(EventPathWorld.DIM,3,64,4),WEST,PassiveNetwork.PhasePort.BULK,PhysicalFluidTopology.ConnectionRole.DISCHARGE));
        var device=new PhysicalFluidTopology.Device(id,suction,Kind.EJECTOR,NORTH,new PipeResistance.Geometry(1,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0),
                new FlowControl.Ejector(SET),ports,true,PhysicalFluidTopology.Cutoff.NONE,false,PhysicalFluidTopology.ALL_FACES);
        w.registry.submit(List.of(new WorldTopologyLedger.Edit(id,new WorldTopologyLedger.Registration(device,FluidDeviceSpec.nitrogen(EventPathWorld.CATALOG),0))),id+1,null);
        return new Plant(w,id);
    }
    private static IslandCoordinator.Snapshot island(EventPathWorld w,long ejector) {
        long pipe=PhysicalFluidTopology.ejectorIdentity(ejector);
        for(var s:w.coordinator.snapshots())if(s.graph().pipes().stream().anyMatch(p->p.id()==pipe))return s;
        throw new AssertionError("no island holds ejector "+ejector);
    }
    private static PassiveNetwork.Pipe pipe(PassiveNetwork graph,long id){return graph.pipes().stream().filter(p->p.id()==id).findFirst().orElseThrow();}
    private static PassiveStepSolver.PipeObservation row(IslandCoordinator.Snapshot s,long id) {
        return s.lastResult().orElseThrow().observations().stream().filter(o->o.pipe()==id).findFirst().orElseThrow();
    }

    /**
     * At 10 kPa and 3 kPa the curve takes the suction's area at the sheet's speed (60 and 68 m/s), 190 and 64 kg/h of
     * nitrogen (the owner's 197 and 67 kg/h of air, by density), and the valve passes the law's steam for it, all of it.
     */
    @Test void aPlacedEjectorTakesTheSheetsVolumeAndItsSteamAtTwoTopPressures() {
        for(double top:new double[]{1e4,3e3}) {
            var p=plant(top,true);var w=p.world();
            w.runTo(1200);w.assertKeptUp("a steam ejector on a "+top+" Pa top");
            var s=island(w,p.ejector());var graph=s.graph();
            var ejector=(FlowControl.Ejector)pipe(graph,PhysicalFluidTopology.ejectorIdentity(p.ejector())).control();
            var valve=(FlowControl.ControlValve)pipe(graph,PhysicalFluidTopology.motiveIdentity(p.ejector())).control();
            var load=row(s,PhysicalFluidTopology.ejectorIdentity(p.ejector()));var jet=row(s,PhysicalFluidTopology.motiveIdentity(p.ejector()));
            var mover=pipe(graph,PhysicalFluidTopology.ejectorIdentity(p.ejector()));var at=graph.reservoirs().get(mover.first()).state();double ps=at.pressure(),pd=graph.reservoirs().get(mover.second()).state().pressure();
            System.out.println("EJECTOR_WORLD top="+top+" load="+load.massFlow()*3600+" kg/h at "+ps+" Pa to "+pd+" Pa, steam asked="+valve.massTarget()*3600+" got="+jet.massFlow()*3600+" kg/h, share="+ejector.delivered());
            assertEquals(FlowControl.ValveSource.EJECTOR,valve.source());
            assertTrue(ejector.motivePressure()>9e5,"steam at the nozzle near the header's 10 bar: "+ejector.motivePressure());
            assertTrue(ejector.delivered()>.98,"the nozzle gets the steam asked: "+ejector.delivered());
            assertEquals(valve.massTarget(),jet.massFlow(),.02*valve.massTarget(),"the valve passes its target");
            double curve=SET.capacity(ps,ejector.motivePressure(),ejector.delivered())*at.mass()/at.volume();
            assertEquals(curve,load.massFlow(),.05*curve,"the load is the curve's volume at the suction's state");
            double expected=(top>5e3?190:64)/3600.;
            assertEquals(expected,load.massFlow(),.08*expected,"the owner's table, as nitrogen");
            double law=com.wormzjl.icheme.science.flowsheet.EjectorLaw.steam(load.massFlow()*Math.sqrt(29/28.013),0,ejector.motivePressure(),ps,pd,1);
            assertEquals(law,valve.massTarget(),.05*law,"the steam is the sheet's law for that load");
        }
    }

    /** Without its steam the set has no capacity: nothing leaves the top. */
    @Test void withoutSteamTheSetTakesNothing() {
        var p=plant(1e4,false);var w=p.world();
        w.runTo(1200);w.assertKeptUp("an ejector without steam");
        var s=island(w,p.ejector());
        var valve=(FlowControl.ControlValve)pipe(s.graph(),PhysicalFluidTopology.motiveIdentity(p.ejector())).control();
        assertEquals(0,valve.massTarget(),0);
        assertEquals(0,((FlowControl.Ejector)pipe(s.graph(),PhysicalFluidTopology.ejectorIdentity(p.ejector())).control()).delivered(),0);
        assertEquals(0,row(s,PhysicalFluidTopology.ejectorIdentity(p.ejector())).massFlow(),1e-9);
    }
}
