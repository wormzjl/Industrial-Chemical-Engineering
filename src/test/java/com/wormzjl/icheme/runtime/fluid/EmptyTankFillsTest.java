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
 * Owner decision D54 (every vessel a bag with a stiff wall; plan
 * documentation/2026-10-14-empty-start-progressive-volume/EMPTY_START_PLAN.md), on the E5 card world of the phase-ports
 * batch (REMAINING_HOLDS.md, "E5: the step solve stalls", world defaults seed 12, 7 blocks): a 1 atm water generator three
 * blocks above a 1 m3 tank, through a junction on the tank's top face and a pipe round into its north side. The tank is
 * built as a placed tank is ({@link FluidDeviceSpec#initialize}).
 *
 * <p>Physics: water falls 3 m into a bag that holds nothing. While the bag is slack it is at the ambient and fills at
 * the 29 kPa head, about 7 kg/s; once its contents reach the capacity the wall carries them and the tank takes the head's
 * pressure, 101325 + 997 x 9.80665 x 3 = 130.7 kPa, full of water with no gas. With the old 1 atm nitrogen charge the
 * same world rests on a gas cushion squeezed to about 0.78 m3 under some 220 kg of water.
 */
class EmptyTankFillsTest {
    static final String DIMENSION="minecraft:overworld";
    static final long GENERATOR=1,TANK=7;
    static final PipeResistance.Geometry BLOCK=new PipeResistance.Geometry(1,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0);
    final FluidThermodynamics model=FluidTestSupport.networkModel();

    static PhysicalFluidTopology.Device device(long id,int x,int y,int z,Kind kind) {
        return PhysicalFluidTopology.Device.builder(id,new PhysicalFluidTopology.Position(DIMENSION,x,y,z),kind,PhysicalFluidTopology.Direction.NORTH,BLOCK,new FlowControl.Passive()).build();
    }
    static double[] only(int component){double[] n=new double[MaterialTestBasis.NETWORK+1];n[component]=1;return n;}
    static int node(PassiveNetwork graph,long id){for(int i=0;i<graph.reservoirs().size();i++)if(graph.reservoirs().get(i).id()==id)return i;throw new AssertionError(id);}

    /** The card's seven blocks, compiled and minted as the registry forms an island from placed blocks. */
    PassiveNetwork world() {
        var devices=List.of(device(GENERATOR,0,3,1,Kind.GENERATOR),device(2,0,2,1,Kind.PIPE),device(3,0,1,1,Kind.PIPE),device(4,0,1,0,Kind.PIPE),
                device(5,0,1,-1,Kind.PIPE),device(6,0,0,-1,Kind.PIPE),device(TANK,0,0,0,Kind.RESERVOIR));
        var boundaries=new LinkedHashMap<Long,PassiveNetwork.Reservoir>();
        boundaries.put(GENERATOR,new FluidDeviceSpec(1,298.15,101325,only(MaterialTestBasis.NETWORK)).initialize(devices.getFirst(),model,()->{}));
        boundaries.put(TANK,new FluidDeviceSpec(1,298.15,101325,only(MaterialTestBasis.NITROGEN)).initialize(devices.getLast(),model,()->{}));
        var compiled=PhysicalFluidTopology.compile(devices,boundaries,Map.of(),NetworkReduction.ambient(model));
        return PassiveNetwork.mintJunctions(compiled.islands().stream().filter(i->i.physicalIds().contains(TANK)).findFirst().orElseThrow().graph(),model);
    }

    @Test void theEmptyTankFillsCompletelyAndRestsAtTheHeadsPressure() {
        var graph=world();int t=node(graph,TANK);double capacity=graph.reservoirs().get(t).inventory().volume();
        var retained=new RetainedSolver();PassiveIntervalSolver.Result last=null;double previous=Double.NaN;int steady=0;
        for(int slice=1;slice<=72;slice++) {
            retained.committed(last);
            try{last=retained.solve(model,graph,5,PassiveIntervalSolver.Settings.defaults(),()->{});}
            catch(RuntimeException held){fail("HELD at slice "+slice+": "+held.getMessage());}
            graph=last.graph();var tank=graph.reservoirs().get(t);var s=tank.state();
            double mass=tank.empty()?0:s.mass();
            System.out.println("EMPTY_TANK slice="+slice+" P="+s.pressure()+" mass="+mass+" liquid="+(tank.empty()?0:model.liquidMass(s))
                    +" gasVolume="+(tank.empty()?0:s.vaporVolume())+" fill="+(tank.empty()?0:s.volume()/capacity)+" flows="+Arrays.toString(last.averageMassFlows()));
            steady=Math.abs(mass-previous)<1e-6?steady+1:0;previous=mass;
        }
        var s=graph.reservoirs().get(t).state();
        System.out.println("EMPTY_TANK_END P="+s.pressure()+" mass="+s.mass()+" gasVolume="+s.vaporVolume()+" steady="+steady);
        double head=PassiveStepSolver.GRAVITY*3;
        assertTrue(steady>=5,"the tank comes to rest");
        assertEquals(0,s.vaporVolume(),"no gas cushion: the tank holds water only");
        assertEquals(1,s.volume()/capacity,1e-4,"the water fills the capacity");
        assertTrue(s.mass()>990,"about a cubic metre of water: "+s.mass());
        assertEquals(101325+s.mass()/s.volume()*head,s.pressure(),300,"the tank rests at the head's pressure");
    }
}
