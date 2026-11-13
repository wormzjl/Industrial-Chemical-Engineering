package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.icheme.science.material.MaterialTestBasis;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Owner decision D54 (nothing at the mint), the empty riser: a 150 kPa water generator spills through a junction into a
 * level 1 atm void, and from the junction a dead-end run rises 8 m to an empty tank. Built and minted as placed.
 *
 * <p>Physics: the spill sets the junction between the generator's pressure and the void's; water can stand above it only
 * to its head over the ambient, (P_J - P_ambient)/(rho g), about 2.5 m, so the riser holds a water column to that height
 * and nothing above it: nothing reaches the tank 8 m up, which stays empty, while the spill flows on.
 */
class EmptyRiserTest {
    static final String DIMENSION="minecraft:overworld";
    static final long GENERATOR=1,JUNCTION=3,VOID=5,TANK=13;
    static final PipeResistance.Geometry BLOCK=new PipeResistance.Geometry(1,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0);
    final FluidThermodynamics model=FluidTestSupport.networkModel();

    static PhysicalFluidTopology.Device device(long id,int x,int y,int z,Kind kind) {
        return PhysicalFluidTopology.Device.builder(id,new PhysicalFluidTopology.Position(DIMENSION,x,y,z),kind,PhysicalFluidTopology.Direction.NORTH,BLOCK,new FlowControl.Passive()).build();
    }
    static double[] only(int component){double[] n=new double[MaterialTestBasis.NETWORK+1];n[component]=1;return n;}
    static int node(PassiveNetwork graph,long id){for(int i=0;i<graph.reservoirs().size();i++)if(graph.reservoirs().get(i).id()==id)return i;return -1;}

    PassiveNetwork world() {
        var devices=new ArrayList<>(List.of(device(GENERATOR,0,0,0,Kind.GENERATOR),device(2,1,0,0,Kind.PIPE),device(JUNCTION,2,0,0,Kind.PIPE),
                device(4,3,0,0,Kind.PIPE),device(VOID,4,0,0,Kind.VOID)));
        for(int y=1;y<=7;y++)devices.add(device(5+y,2,y,0,Kind.PIPE));
        devices.add(device(TANK,2,8,0,Kind.RESERVOIR));
        var boundaries=new LinkedHashMap<Long,PassiveNetwork.Reservoir>();
        boundaries.put(GENERATOR,new FluidDeviceSpec(1,298.15,150000,only(MaterialTestBasis.NETWORK)).initialize(devices.getFirst(),model,()->{}));
        boundaries.put(VOID,new FluidDeviceSpec(1,298.15,101325,only(MaterialTestBasis.NITROGEN)).initialize(devices.get(4),model,()->{}));
        boundaries.put(TANK,new FluidDeviceSpec(1,298.15,101325,only(MaterialTestBasis.NITROGEN)).initialize(devices.getLast(),model,()->{}));
        var compiled=PhysicalFluidTopology.compile(devices,boundaries,Map.of(),NetworkReduction.ambient(model));
        return PassiveNetwork.mintJunctions(compiled.islands().stream().filter(i->i.physicalIds().contains(TANK)).findFirst().orElseThrow().graph(),model);
    }

    @Test void theRiserHoldsAColumnToItsHeadAndNothingReachesTheTankAbove() {
        var graph=world();int t=node(graph,TANK);
        var retained=new RetainedSolver();PassiveIntervalSolver.Result last=null;int accepted=0,rejected=0;
        for(int slice=1;slice<=24;slice++) {
            retained.committed(last);
            try{last=retained.solve(model,graph,5,PassiveIntervalSolver.Settings.defaults(),()->{});}
            catch(RuntimeException held){fail("HELD at slice "+slice+": "+held.getMessage());}
            graph=last.graph();accepted+=last.acceptedSubsteps();rejected+=last.rejectedSubsteps();
            var tank=graph.reservoirs().get(t);
            System.out.println("EMPTY_RISER slice="+slice+" tankEmpty="+tank.empty()+" tankMass="+(tank.empty()?0:tank.state().mass())+" flows="+Arrays.toString(last.averageMassFlows())
                    +" accepted="+last.acceptedSubsteps()+" rejected="+last.rejectedSubsteps()+" reasons="+last.rejectionReasons());
        }
        System.out.println("EMPTY_RISER_END accepted="+accepted+" rejected="+rejected);
        assertTrue(graph.reservoirs().get(t).empty(),"nothing reaches the tank 8 m above the junction");
        double spill=0,riser=0;
        for(int i=0;i<graph.pipes().size();i++){var pipe=graph.pipes().get(i);double q=Math.abs(last.averageMassFlows()[i]);
            if(pipe.first()==t||pipe.second()==t)riser=q;else spill=Math.max(spill,q);}
        assertTrue(spill>1,"the spill flows on: "+spill);assertEquals(0,riser,"nothing moves in the riser");
    }
}
