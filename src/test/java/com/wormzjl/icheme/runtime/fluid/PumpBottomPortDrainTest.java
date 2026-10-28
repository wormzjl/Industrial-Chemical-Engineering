package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.icheme.science.material.MaterialTestBasis;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.wormzjl.icheme.fluid.support.FluidTestSupport.nitrogen;
import static com.wormzjl.icheme.fluid.support.FluidTestSupport.water;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Defect F1 of the phase-ports in-game check (2026-09-27): a pump drawing water from a tank's bottom face held its island
 * with water left in the tank. Built as in game (scenario 2 / 2b of tools/phase-ports-probes/in-game/NOTES.md): a 1000 L
 * nitrogen tank at 1 atm filled from a 130 kPa water generator through one block of 0.05 m pipe until it rests (about
 * 200 kg of water at 130 kPa); then the generator and its pipe removed and a pump placed under the tank facing down, one
 * block of pipe and a 101325 Pa void below it. Run the way the world runs an island: consecutive 5 s jobs on one
 * {@link RetainedSolver}, the committed interval handed over at each dispatch. The pump must drain the tank without a
 * hold and end refused on its inlet phase once the water is gone.
 */
class PumpBottomPortDrainTest {
    static final String DIMENSION="minecraft:overworld";
    static final int Y=-57;
    static final PipeResistance.Geometry BLOCK=new PipeResistance.Geometry(1,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0);
    final FluidThermodynamics model=FluidTestSupport.networkModel();

    static PhysicalFluidTopology.Device device(long id,int x,int y,Kind kind,PhysicalFluidTopology.Direction facing,FlowControl control) {
        return new PhysicalFluidTopology.Device(id,new PhysicalFluidTopology.Position(DIMENSION,x,y,0),com.wormzjl.icheme.fluid.support.FluidTestSupport.mechanical(kind),facing,BLOCK,control);
    }
    static PhysicalFluidTopology.Device device(long id,int x,int y,Kind kind) {
        return device(id,x,y,kind,PhysicalFluidTopology.Direction.NORTH,new FlowControl.Passive());
    }

    /** The world's compile of a set of blocks; {@code carried} are boundary states kept from an earlier island. */
    PassiveNetwork island(List<PhysicalFluidTopology.Device> devices,Map<Long,FluidDeviceSpec> specs,Map<Long,PassiveNetwork.Reservoir> carried) {
        var boundaries=new LinkedHashMap<Long,PassiveNetwork.Reservoir>(carried);
        for(var d:devices)if(specs.containsKey(d.id()))boundaries.put(d.id(),FluidTestSupport.chargedBoundary(specs.get(d.id()),d,model));
        var compiled=PhysicalFluidTopology.compile(devices,boundaries,Map.of(),model.initialNitrogenCharge(1,298.15,101325,()->{}));
        var connected=compiled.islands().stream().filter(i->i.physicalIds().size()==devices.size()).toList();
        assertEquals(1,connected.size(),"one connected island: "+compiled.diagnostics());
        return connected.getFirst().graph();
    }
    static int node(PassiveNetwork graph,long id){for(int i=0;i<graph.reservoirs().size();i++)if(graph.reservoirs().get(i).id()==id)return i;throw new AssertionError(id);}

    /** The fill of scenario 2: generator - pipe - tank until the tank rests. Returns the tank's committed node. */
    PassiveNetwork.Reservoir filledTank(long tankId) {
        var devices=List.of(device(1,-2,Y,Kind.GENERATOR),device(2,-1,Y,Kind.PIPE),device(tankId,0,Y,Kind.RESERVOIR));
        var graph=island(devices,Map.of(1L,new FluidDeviceSpec(1,298.15,130000,water()),tankId,new FluidDeviceSpec(1,298.15,101325,nitrogen())),Map.of());
        var retained=new RetainedSolver();PassiveIntervalSolver.Result last=null;
        for(int slice=0;slice<60;slice++) {
            retained.committed(last);
            var result=retained.solve(model,graph,5,PassiveIntervalSolver.Settings.defaults(),()->{});
            double before=graph.reservoirs().get(node(graph,tankId)).state().mass();
            graph=result.graph();last=result;
            if(Math.abs(graph.reservoirs().get(node(graph,tankId)).state().mass()-before)<1e-6)break;
        }
        var tank=graph.reservoirs().get(node(graph,tankId));
        System.out.println("F1_FILL tank P="+tank.state().pressure()+" mass="+tank.state().mass()+" condensed="+model.liquidMass(tank.state()));
        assertEquals(130000,tank.state().pressure(),50,"the filled tank rests on the generator (a side port: no level head)");
        return tank;
    }

    /** Scenario 2b: the filled tank drained at {@code target} m3/s by a pump one pipe block below its bottom face (only pipes
     * connect, decision D20; the pump stood on the face until 2026-09-28). The pump is in-line and lies on its side, two
     * blocks below the face as before, between two elbows (batch 2026-10-28-old-mover-removal: an in-line mover is
     * horizontal); the void is two blocks below the pump, as before. */
    void drain(double target,int slices) {
        long tankId=3;var tank=filledTank(tankId);
        var devices=List.of(device(tankId,0,Y,Kind.RESERVOIR),
                device(7,0,Y-1,Kind.PIPE),device(8,0,Y-2,Kind.PIPE),device(4,1,Y-2,Kind.PUMP,PhysicalFluidTopology.Direction.EAST,new FlowControl.Pump(target,500000)),
                device(5,2,Y-2,Kind.PIPE),device(9,2,Y-3,Kind.PIPE),device(6,2,Y-4,Kind.VOID));
        var graph=island(devices,Map.of(6L,new FluidDeviceSpec(1,298.15,101325,nitrogen())),Map.of(tankId,tank));
        int pump=-1;for(int i=0;i<graph.pipes().size();i++)if(graph.pipes().get(i).control() instanceof FlowControl.Pump)pump=i;
        assertTrue(pump>=0);
        var retained=new RetainedSolver();PassiveIntervalSolver.Result last=null;int refusedAt=-1;
        for(int slice=0;slice<slices;slice++) {
            retained.committed(last);PassiveIntervalSolver.Result result;
            try{result=retained.solve(model,graph,5,PassiveIntervalSolver.Settings.defaults(),()->{});}
            catch(RuntimeException held) {
                var t=graph.reservoirs().get(node(graph,tankId)).state();
                fail("HELD at slice "+slice+" (t="+5*slice+" s), tank P="+t.pressure()+" condensed="+model.liquidMass(t)+" kg vapour share="+t.vaporVolume()/t.volume()+": "+held.getMessage());
                return;
            }
            graph=result.graph();last=result;
            var t=graph.reservoirs().get(node(graph,tankId)).state();var mode=result.endpointModes().get(pump);
            System.out.println("F1_SLICE target="+target+" slice="+slice+" mode="+mode+" flow="+result.averageMassFlows()[pump]+" tankP="+t.pressure()
                    +" condensed="+model.liquidMass(t)+" vapourShare="+t.vaporVolume()/t.volume()+" accepted="+result.acceptedSubsteps()+" rejected="+result.rejectedSubsteps()+" reasons="+result.rejectionReasons());
            if(mode==FlowControl.Mode.INLET_WRONG_PHASE&&refusedAt<0)refusedAt=slice;
            if(refusedAt>=0&&slice>=refusedAt+2)break;
        }
        assertTrue(refusedAt>=0,"the pump ends refused on its inlet phase");
        var t=graph.reservoirs().get(node(graph,tankId)).state();
        assertEquals(FlowControl.Mode.INLET_WRONG_PHASE,last.endpointModes().get(pump));
        assertEquals(0,last.averageMassFlows()[pump],0,"a refused pump carries nothing");
        // A37: the pump is refused once the port's draw over one slice is more than 2 % gas, so at most about one slice
        // of the target volume of water is left behind.
        assertTrue(model.liquidMass(t)<target*5*1000,"the tank is drained to less than one slice of the target: "+model.liquidMass(t)+" kg");
    }

    @Test void aPumpOnTheBottomFaceDrainsTheTankAtTheDefaultTarget(){drain(.01,20);}
    @Test void aPumpOnTheBottomFaceDrainsTheTankAtATenthOfTheDefaultTarget(){drain(.001,60);}

    // ---------------- from TraceFlushFedPumpTest ----------------
    @Test void aPumpedWaterLineRunsThroughTheWholeLifetimeOfItsJunctionsGasTrace() {
        var base=new PumpBottomPortDrainTest();var model=base.model;long tankId=3;var tank=base.filledTank(tankId);
        int y=PumpBottomPortDrainTest.Y;
        var devices=List.of(PumpBottomPortDrainTest.device(7,-2,y,Kind.GENERATOR),PumpBottomPortDrainTest.device(8,-1,y,Kind.PIPE),
                PumpBottomPortDrainTest.device(tankId,0,y,Kind.RESERVOIR),
                PumpBottomPortDrainTest.device(9,0,y-1,Kind.PIPE),PumpBottomPortDrainTest.device(10,0,y-2,Kind.PIPE),
                PumpBottomPortDrainTest.device(4,1,y-2,Kind.PUMP,PhysicalFluidTopology.Direction.EAST,new FlowControl.Pump(.001,500000)),
                PumpBottomPortDrainTest.device(5,2,y-2,Kind.PIPE),PumpBottomPortDrainTest.device(11,2,y-3,Kind.PIPE),PumpBottomPortDrainTest.device(6,2,y-4,Kind.VOID));
        var graph=base.island(devices,Map.of(7L,new com.wormzjl.icheme.runtime.fluid.FluidDeviceSpec(1,298.15,130000,com.wormzjl.icheme.fluid.support.FluidTestSupport.water()),
                6L,new com.wormzjl.icheme.runtime.fluid.FluidDeviceSpec(1,298.15,101325,com.wormzjl.icheme.fluid.support.FluidTestSupport.nitrogen())),Map.of(tankId,tank));
        var retained=new RetainedSolver();PassiveIntervalSolver.Result last=null;
        for(int slice=0;slice<400;slice++) {
            retained.committed(last);PassiveIntervalSolver.Result result;
            try{result=retained.solve(model,graph,5,PassiveIntervalSolver.Settings.defaults(),()->{});}
            catch(RuntimeException held){System.out.println("FED_PUMP HELD at slice "+slice+": "+held.getMessage());fail(held.getMessage());return;}
            graph=result.graph();last=result;
            var line=new StringBuilder("FED_PUMP slice="+slice+" accepted="+result.acceptedSubsteps()+" rejected="+result.rejectedSubsteps()+" modes="+result.endpointModes());
            for(var node:graph.reservoirs())if(node.junction())line.append(" junction ").append(node.id()).append(" vapour=").append(Arrays.stream(node.state().vaporView()).sum()).append(" wV=").append(node.state().waterVapor());
            var t=graph.reservoirs().get(PumpBottomPortDrainTest.node(graph,tankId)).state();line.append(" tank P=").append(t.pressure()).append(" water=").append(model.liquidMass(t));
            System.out.println(line);
        }
    }
}
