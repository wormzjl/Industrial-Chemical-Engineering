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
 * Defect F2 of the phase-ports in-game check (2026-09-27): scenario 1's tank, fed by a 130 kPa water generator on its west
 * face with 0.01 m pipes open on its top, east and bottom faces to 101325 Pa voids, held its island once it ran
 * liquid-full ("Substep refinement exhausted: Newton iteration limit at residual 1.147e-9", 996 kg, the top outlet
 * overflowing water). Built from blocks as in game (tools/phase-ports-probes/in-game/NOTES.md, scenario 1) and run as the
 * world runs an island: consecutive 5 s jobs on one {@link RetainedSolver}. The tank must fill, run liquid-full and settle
 * to a steady overflow without a hold, through the whole lifetime of its nitrogen trace: the trace flush (owner decision
 * 2026-09-27, {@code PassiveIntervalSolver.TRACE_FLUSH_FRACTION}) removes the trace when it falls below 1e-24 of the tank, and
 * the vapour phase is then exactly gone. Without the flush the island held after about 27 minutes of steady overflow.
 */
class LiquidFullTankOutletsTest {
    static final String DIMENSION="minecraft:overworld";
    static final int Y=-57;
    static final PipeResistance.Geometry BLOCK=new PipeResistance.Geometry(1,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0);
    static final PipeResistance.Geometry OUTLET=new PipeResistance.Geometry(1,.01,PipeResistance.DEFAULT_ROUGHNESS_METRES,0);
    final FluidThermodynamics model=FluidTestSupport.networkModel();

    static PhysicalFluidTopology.Device device(long id,int x,int y,Kind kind,PipeResistance.Geometry geometry) {
        return PhysicalFluidTopology.Device.builder(id,new PhysicalFluidTopology.Position(DIMENSION,x,y,0),kind,PhysicalFluidTopology.Direction.NORTH,geometry,new FlowControl.Passive()).build();
    }
    static int node(PassiveNetwork graph,long id){for(int i=0;i<graph.reservoirs().size();i++)if(graph.reservoirs().get(i).id()==id)return i;throw new AssertionError(id);}

    /**
     * The tank fills, runs liquid-full and overflows steadily for sixty slices (five minutes; in game the island held 14
     * slices after it ran full), and keeps overflowing without a hold through the whole lifetime of its nitrogen trace. The
     * nitrogen left in the full tank does not dissolve in free water: it stays a vapour phase that the top port halves at
     * every step (decision D11: its capacity is an equal share of the tank's two phase-port ends), about 0.9 decades per
     * slice, until it underflows to exactly zero (it used to hold at 1e-31 of the tank, tools/phase-ports-probes/holds/).
     * Each check runs on the state it was taken at: the overflow sixty slices after the tank ran full (within 150 slices),
     * and the first steady overflow after the trace was gone (within 500 slices).
     */
    @Test void aTankFedLiquidFullWithItsOutletsOpenOverflowsSteadilyThroughTheWholeLifetimeOfItsGasTrace() {
        long tankId=3;
        var devices=List.of(device(1,-2,Y,Kind.GENERATOR,BLOCK),device(2,-1,Y,Kind.PIPE,BLOCK),device(tankId,0,Y,Kind.RESERVOIR,BLOCK),
                device(4,0,Y+1,Kind.PIPE,OUTLET),device(5,0,Y+2,Kind.VOID,BLOCK),
                device(6,1,Y,Kind.PIPE,OUTLET),device(7,2,Y,Kind.VOID,BLOCK),
                device(8,0,Y-1,Kind.PIPE,OUTLET),device(9,0,Y-2,Kind.VOID,BLOCK));
        var specs=new HashMap<Long,FluidDeviceSpec>();
        specs.put(1L,new FluidDeviceSpec(1,298.15,130000,water()));specs.put(tankId,new FluidDeviceSpec(1,298.15,101325,nitrogen()));
        for(long v:new long[]{5,7,9})specs.put(v,new FluidDeviceSpec(1,298.15,101325,nitrogen()));
        var boundaries=new LinkedHashMap<Long,PassiveNetwork.Reservoir>();
        for(var d:devices)if(specs.containsKey(d.id()))boundaries.put(d.id(),FluidTestSupport.chargedBoundary(specs.get(d.id()),d,model));
        var compiled=PhysicalFluidTopology.compile(devices,boundaries,Map.of(),model.initialNitrogenCharge(1,298.15,101325,()->{}));
        var connected=compiled.islands().stream().filter(i->i.physicalIds().size()==devices.size()).toList();
        assertEquals(1,connected.size(),"one connected island: "+compiled.diagnostics());
        var graph=connected.getFirst().graph();
        int tank=node(graph,tankId);var faces=compiled.tankFaces().get(tankId);
        var retained=new RetainedSolver();PassiveIntervalSolver.Result last=null;int fullAt=-1,goneAt=-1,steady=0;double previousMass=Double.NaN;
        int overflowSlices=-1,overflowSteady=-1,traceSteady=-1;List<FluidView.Outlet> overflow=null,afterTrace=null;
        for(int slice=0;slice<500&&(overflow==null||afterTrace==null);slice++) {
            retained.committed(last);PassiveIntervalSolver.Result result;
            try{result=retained.solve(model,graph,5,PassiveIntervalSolver.Settings.defaults(),()->{});}
            catch(RuntimeException held) {
                var t=graph.reservoirs().get(tank).state();
                fail("HELD at slice "+slice+" (t="+5*slice+" s), tank P="+t.pressure()+" mass="+t.mass()+" vapour share="+t.vaporVolume()/t.volume()+": "+held.getMessage());
                return;
            }
            graph=result.graph();last=result;
            var t=graph.reservoirs().get(tank).state();double share=t.vaporVolume()/t.volume();
            System.out.println("F2_SLICE slice="+slice+" tankP="+t.pressure()+" mass="+t.mass()+" vapourShare="+share+" flows="+Arrays.toString(result.averageMassFlows())
                    +" modes="+result.endpointModes()+" accepted="+result.acceptedSubsteps()+" rejected="+result.rejectedSubsteps()+" reasons="+result.rejectionReasons());
            if(share<1e-6&&fullAt<0)fullAt=slice;
            if(t.vaporVolume()==0&&goneAt<0)goneAt=slice;
            steady=fullAt>=0&&Math.abs(t.mass()-previousMass)<1e-3?steady+1:0;previousMass=t.mass();
            if(overflow==null&&slice<150&&fullAt>=0&&slice>=fullAt+60){overflow=TankOutlets.of(model,graph,tank,last,faces);overflowSlices=slice+1;overflowSteady=steady;}
            if(afterTrace==null&&goneAt>=0&&steady>=10){afterTrace=TankOutlets.of(model,graph,tank,last,faces);traceSteady=steady;}
        }
        System.out.println("F2_END overflowSlices="+overflowSlices+" fullAt="+fullAt+" goneAt="+goneAt+" overflowSteady="+overflowSteady+" traceSteady="+traceSteady);
        assertTrue(fullAt>=0,"the tank runs liquid-full");
        assertEquals(fullAt+61,overflowSlices,"sixty slices of overflow after the tank ran full");
        assertTrue(overflowSteady>=10,"a steady overflow: ten slices without a change of the tank's mass");
        assertTrue(goneAt>=0,"the last of the nitrogen leaves the full tank");
        assertTrue(traceSteady>=10,"ten slices of steady overflow after the tank ran full");
        // At rest the three outlets carry the feed and the top outlet overflows water, at both checks.
        var top=overflow.stream().filter(o->o.port()==PassiveNetwork.PhasePort.VAPOR).findFirst().orElseThrow();
        assertTrue(top.massFlow()>0,"the top outlet overflows");
        assertEquals(1,top.drawn(),"the top outlet draws water (overflow)");
        assertFeedEqualsOverflow(overflow);
        assertTrue(afterTrace.stream().filter(o->o.port()==PassiveNetwork.PhasePort.VAPOR).findFirst().orElseThrow().massFlow()>0,"the top outlet overflows");
        assertFeedEqualsOverflow(afterTrace);
    }
    private static void assertFeedEqualsOverflow(List<FluidView.Outlet> outlets) {
        double in=0,out=0;
        for(var o:outlets){if(o.massFlow()>0)out+=o.massFlow();else in-=o.massFlow();}
        assertEquals(in,out,1e-3*in,"feed = overflow at rest");
    }
}
