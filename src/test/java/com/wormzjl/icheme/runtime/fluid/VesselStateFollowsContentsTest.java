package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialTestBasis;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Decision D36, G2 (documentation/2026-09-26-phase-ports-and-compressor/PHASE_PORTS_REVIEW.md, "Hold class E6, grid
 * overshoot (G2) and the one-way scale"): a vessel's committed state is the state of what it holds. Its temperature and
 * pressure follow from its inventory (volume, amounts, internal energy) through the equation of state, so the vessel's own
 * equations ({@link PhaseLayout#fixedInventory}) hold at the committed state within {@link 1e-11}
 * after every interval.
 *
 * <p>The world is the owner's extreme grid, case 1 of {@link ExtremeTopologyIslandTest}, uncapped, at 5 s slices: ten
 * nitrogen generators (110 to 200 kPa) feed ten tanks through a 2 x 10 mesh of junctions, and the tanks come to rest at
 * 200 kPa. Before G2 a tank kept the Newton candidate's temperature and pressure while its booked contents grew: at rest a
 * step's start point met the tolerance, the Newton returned it at zero iterations, and its carried flow was booked into the
 * tanks unseen. The lag reached 1.2e-9 at interval 3 (the contents then implied 3.1e-4 Pa above the generator while the
 * stated pressure was 1.4e-7 Pa below), and the tanks jumped 2.1e-4 Pa above the generator once the Newton saw it.
 */
class VesselStateFollowsContentsTest {
    private static final PipeResistance.Geometry BLOCK=new PipeResistance.Geometry(1,.05,.000045,0);

    @Test void everyTankOfTheExtremeGridIsStatedAtItsContentsAfterEveryInterval() {
        var model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"icheme:tjl20_methane_nitrogen",100000, FluidThermodynamics.DEFAULT_TRACE_CUTOFF_MOLE_FRACTION);
        var devices=new ArrayList<PhysicalFluidTopology.Device>();var pressures=new LinkedHashMap<Long,Double>();
        for(int x=0;x<10;x++){devices.add(device(1+x,x,0,Kind.GENERATOR));pressures.put(1L+x,ExtremeTopologyIslandTest.GENERATORS[x]);}
        for(int x=0;x<10;x++)devices.add(device(11+x,x,1,Kind.PIPE));
        for(int x=0;x<10;x++)devices.add(device(21+x,x,2,Kind.PIPE));
        for(int x=0;x<10;x++){devices.add(device(31+x,x,3,Kind.RESERVOIR));pressures.put(31L+x,ExtremeTopologyIslandTest.TANKS[x]);}
        var boundaries=new LinkedHashMap<Long,PassiveNetwork.Reservoir>();
        double[] n2=new double[model.components().size()];n2[MaterialTestBasis.NITROGEN]=1;
        for(var d:devices)if(pressures.containsKey(d.id()))boundaries.put(d.id(),com.wormzjl.icheme.fluid.support.FluidTestSupport.chargedBoundary(new FluidDeviceSpec(1,350,pressures.get(d.id()),n2),d,model));
        var graph=PassiveNetwork.sizeJunctionHoldups(PhysicalFluidTopology.compile(devices,boundaries).islands().getFirst().graph(),model);
        double pMax=Arrays.stream(ExtremeTopologyIslandTest.GENERATORS).max().orElseThrow();
        var solver=new PassiveIntervalSolver(model);PassiveIntervalSolver.Result committed=null;double worstLag=0,worstAbove=Double.NEGATIVE_INFINITY;
        for(int interval=1;interval<=12;interval++) {
            solver.replayStart(graph,committed);
            committed=solver.solve(graph,5,PassiveIntervalSolver.Settings.defaults(),()->{});graph=committed.graph();
            for(var node:graph.reservoirs()) {
                if(node.kind()!=PassiveNetwork.NodeKind.RESERVOIR)continue;
                var state=node.state();var inventory=node.inventory();var layout=new PhaseLayout(model,state);
                double[] residual=new double[layout.size()];
                layout.residual(state,inventory.moles(),inventory.internalEnergy(),inventory.volume(),Double.NaN,residual,0,layout.encode(state));
                double lag=Arrays.stream(residual).map(Math::abs).max().orElseThrow();worstLag=Math.max(worstLag,lag);
                worstAbove=Math.max(worstAbove,state.pressure()-pMax);
                assertTrue(lag<=InventoryEquilibrium.CLOSURE,"interval "+interval+": tank "+node.id()+" is stated "+lag+" away from its contents");
                assertTrue(state.pressure()<=pMax*(1+1e-9),"interval "+interval+": tank "+node.id()+" at "+state.pressure()+" Pa, above the highest generator");
            }
        }
        System.out.println("VESSEL_CLOSURE grid-10 uncapped 5 s x 12: worst lag "+worstLag+", highest tank "+worstAbove+" Pa from the highest generator");
    }
    private static PhysicalFluidTopology.Device device(long id,int x,int z,Kind kind){
        return PhysicalFluidTopology.Device.builder(id,new PhysicalFluidTopology.Position("minecraft:overworld",x,64,z),kind,PhysicalFluidTopology.Direction.EAST,BLOCK,new FlowControl.Passive()).build();
    }
}
