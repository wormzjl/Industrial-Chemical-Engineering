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
 * Defect F7 of the phase-ports batch (2026-09-27): in the GUI-fixes check's first dev-client run a 1000 L nitrogen tank at
 * 1 atm, with 0.05 m pipes on its top, bottom, north and east faces to 101325 Pa voids and a 101325 Pa water generator on
 * its west face, logged 14 {@code status=HELD: Interval substep limit ... Backward-Euler state change #=511} lines
 * (tools/phase-ports-probes/gui-fixes/NOTES.md, client log {@code logs/06}). Built from blocks in the order and at the
 * online ticks the player placed them (the client log, 20 TPS from the world load), and run as the world runs an island:
 * each placement is a topology event that starts the island's slice ladder at one tick (every accepted full interval
 * doubles it back to the 100-tick cadence), a slice ends at the next event (its fence), and a hold halves the slice on a
 * fresh solver ({@code IslandCoordinator.topology}, {@code hold}). With the generator at tick 1007 the run is the in-game
 * one to the last digit (the held slices' last refused changes 0.08460994579887296, 0.08545046943000091, ... are the
 * client log's). Every slice must commit whole.
 */
class FedTankOpenOutletsTest {
    static final String DIMENSION="minecraft:overworld";
    static final int Y=-57;
    static final long TANK=1;
    static final PipeResistance.Geometry BLOCK=new PipeResistance.Geometry(1,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0);
    final FluidThermodynamics model=FluidTestSupport.networkModel();

    static PhysicalFluidTopology.Device device(long id,int x,int y,int z,Kind kind) {
        return PhysicalFluidTopology.Device.builder(id,new PhysicalFluidTopology.Position(DIMENSION,x,y,z),kind,PhysicalFluidTopology.Direction.NORTH,BLOCK,new FlowControl.Passive()).build();
    }
    static int node(PassiveNetwork graph,long id){for(int i=0;i<graph.reservoirs().size();i++)if(graph.reservoirs().get(i).id()==id)return i;throw new AssertionError(id);}

    record Placement(int tick,List<PhysicalFluidTopology.Device> devices) {}
    /** The client log's placements (tank, the five pipes, the four voids, then the generator at {@code generatorTick}), or,
     * {@code generatorFirst}, the generator and its pipe right after the tank and the outlets after them. */
    static List<Placement> placements(int generatorTick,boolean generatorFirst) {
        var list=new ArrayList<Placement>();
        list.add(new Placement(664,List.of(device(TANK,0,Y,0,Kind.RESERVOIR))));
        if(generatorFirst){list.add(new Placement(680,List.of(device(2,-1,Y,0,Kind.PIPE))));list.add(new Placement(690,List.of(device(11,-2,Y,0,Kind.GENERATOR))));}
        else list.add(new Placement(698,List.of(device(2,-1,Y,0,Kind.PIPE))));
        list.add(new Placement(732,List.of(device(3,1,Y,0,Kind.PIPE))));
        list.add(new Placement(767,List.of(device(4,0,Y,-1,Kind.PIPE))));
        list.add(new Placement(800,List.of(device(5,0,Y+1,0,Kind.PIPE))));
        list.add(new Placement(834,List.of(device(6,0,Y-1,0,Kind.PIPE))));
        list.add(new Placement(868,List.of(device(7,2,Y,0,Kind.VOID))));
        list.add(new Placement(903,List.of(device(8,0,Y,-2,Kind.VOID))));
        list.add(new Placement(936,List.of(device(9,0,Y+2,0,Kind.VOID))));
        list.add(new Placement(970,List.of(device(10,0,Y-2,0,Kind.VOID))));
        if(!generatorFirst)list.add(new Placement(generatorTick,List.of(device(11,-2,Y,0,Kind.GENERATOR))));
        return list;
    }
    PassiveNetwork compile(List<PhysicalFluidTopology.Device> devices,PassiveNetwork.Reservoir tank) {
        var boundaries=new LinkedHashMap<Long,PassiveNetwork.Reservoir>();
        for(var d:devices) {
            if(d.kind()!=Kind.RESERVOIR&&d.kind()!=Kind.VOID&&d.kind()!=Kind.GENERATOR)continue;
            var spec=d.kind()==Kind.GENERATOR?new FluidDeviceSpec(1,298.15,101325,water()):new FluidDeviceSpec(1,298.15,101325,nitrogen());
            boundaries.put(d.id(),d.id()==TANK&&tank!=null?tank:FluidTestSupport.chargedBoundary(spec,d,model));
        }
        var compiled=PhysicalFluidTopology.compile(devices,boundaries,Map.of(),model.initialNitrogenCharge(1,298.15,101325,()->{}));
        return compiled.islands().stream().filter(i->i.physicalIds().contains(TANK)).findFirst().orElseThrow().graph();
    }

    /** Runs the placements and {@code slicesAfter} slices past the last one; fails on the first hold. */
    void run(String label,List<Placement> placed,int slicesAfter) {
        var devices=new ArrayList<PhysicalFluidTopology.Device>();int next=0;long tick=placed.getFirst().tick();
        PassiveNetwork graph=null;PassiveNetwork.Reservoir tank=null;RetainedSolver retained=null;PassiveIntervalSolver.Result last=null;
        int span=1,slices=0,maxAttempts=0;double previousMass=Double.NaN;int steady=0;
        while(slices<slicesAfter) {
            if(next<placed.size()&&placed.get(next).tick()==tick) {
                devices.addAll(placed.get(next++).devices());
                graph=compile(devices,tank);retained=new RetainedSolver();last=null;span=1;
            }
            long fence=next<placed.size()?placed.get(next).tick():Long.MAX_VALUE;
            long end=Math.min(tick+Math.min(100,span),fence);int ticks=(int)(end-tick);int t=node(graph,TANK);
            retained.committed(last);PassiveIntervalSolver.Result result;
            try{result=retained.solve(model,graph,ticks/20.0,PassiveIntervalSolver.Settings.defaults(),()->{});}
            catch(RuntimeException held) {
                var s=graph.reservoirs().get(t).state();
                fail(label+": HELD at tick "+tick+" ("+ticks+" ticks), tank P="+s.pressure()+" mass="+s.mass()+" condensed="+model.liquidMass(s)+": "+held.getMessage());
                return;
            }
            graph=result.graph();last=result;tank=graph.reservoirs().get(t);tick=end;
            span=span>=50?Integer.MAX_VALUE:span*2;
            if(next>=placed.size()) {
                slices++;maxAttempts=Math.max(maxAttempts,result.acceptedSubsteps()+result.rejectedSubsteps());
                var s=tank.state();
                steady=Math.abs(s.mass()-previousMass)<1e-3?steady+1:0;previousMass=s.mass();
                System.out.println("F7_SLICE "+label+" end="+end+" ticks="+ticks+" P="+s.pressure()+" mass="+s.mass()+" condensed="+model.liquidMass(s)
                        +" flows="+Arrays.toString(result.averageMassFlows())+" modes="+result.endpointModes()+" accepted="+result.acceptedSubsteps()+" rejected="+result.rejectedSubsteps()+" reasons="+result.rejectionReasons());
            }
        }
        var s=tank.state();
        System.out.println("F7_END "+label+" tick="+tick+" maxAttempts="+maxAttempts+" steady="+steady+" P="+s.pressure()+" mass="+s.mass()+" condensed="+model.liquidMass(s));
        assertTrue(steady>=10,label+": the tank settles (ten slices without a change of its mass)");
        double[] flows=last.averageMassFlows();double in=0,out=0;int t=node(graph,TANK);
        for(int i=0;i<flows.length;i++){var pipe=graph.pipes().get(i);double f=pipe.first()==t?flows[i]:pipe.second()==t?-flows[i]:0;if(f>0)out+=f;else in-=f;}
        // Owner decision D54 (every vessel a bag with a stiff wall): the 1 atm charge vents through the bottom outlet into the
        // void below (its 2 m of gas column), the tank runs dry within a step and rests empty at the ambient, and a 1 atm
        // generator level with it has no head to feed it. Before, the rigid tank stood 23 Pa below the ambient and drew
        // the generator's water through it.
        assertTrue(graph.reservoirs().get(t).empty(),label+": the tank runs dry and rests empty");
        assertEquals(0,in,label+": nothing is fed at rest");assertEquals(0,out,label+": nothing drains at rest");
    }

    /** The in-game run: 14 holds on the tree the defect was found on. */
    @Test void theInGamePlacementOrderNeverHolds(){run("in-game",placements(1007,false),120);}
    /** The same blocks with the generator connected before the outlets: on the defect's tree every 5 s slice held. */
    @Test void theGeneratorBeforeTheOutletsNeverHolds(){run("generator-first",placements(1007,true),120);}
}
