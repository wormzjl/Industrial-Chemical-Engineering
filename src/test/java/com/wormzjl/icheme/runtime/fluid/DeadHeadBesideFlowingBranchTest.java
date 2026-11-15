package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.icheme.science.material.MaterialTestBasis;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A dead-headed run beside a flowing branch in one island (group B stop point on {@link DeadHeadedLineIslandTest}, option
 * (a); PHASE_PORTS_REVIEW.md "Group C", section on the dead-head gate).
 *
 * <p>The world, all at one height: a 150 kPa nitrogen generator feeds a 1 m3 nitrogen tank through two pipe blocks; the
 * tank vents through three pipe blocks into a 1 atm void; a 1 atm water generator joins the tank from the other side
 * through a pipe block, an inline filter and a pipe block. Physics: nitrogen flows from the 150 kPa generator through the
 * tank into the void, and the tank settles between the two (143.8 kPa with 0.32 kg/s through it after 200 s: the feed run
 * is the shorter one). The water generator can never push into a tank that stands above its own pressure, and it takes
 * nothing back, so its run, filter included, carries exactly zero the whole time. The island flows, so the rest step
 * leaves it to the solver.
 *
 * <p>Measured on this tree (review): this world, three pressures of its flat and rising shapes, and the six worlds of
 * {@link DeadHeadedLineIslandTest} with the rest step bypassed all solve to exactly zero on the dead-headed run with or
 * without {@code PassiveStepSolver.closeDeadHeads}; the pass loop's own closure of an illegal direction now reaches the
 * same point. This test guards the behaviour (a dead-headed run beside flow is closed at its generator and carries
 * nothing), not that closure; whether the closure stays is an owner decision. The tank starts at 120 kPa so the flowing
 * branch starts driven: a tank venting down to a void's pressure holds near its zero-flow root on this tree with or
 * without the closure (hold class E5/E6, review), which is not what this world tests.
 */
class DeadHeadBesideFlowingBranchTest {
    private static final String DIMENSION="minecraft:overworld";
    private static final int BOTTOM=-59;
    private static final PipeResistance.Geometry BLOCK=new PipeResistance.Geometry(1,.05,.000045,0);
    private static final double ATMOSPHERE=101325,FEED=150000;
    private final FluidThermodynamics model=FluidTestSupport.networkModel();

    private PhysicalFluidTopology.Device device(long id,int x,int z,TopologyCompiler.Kind kind) {
        return PhysicalFluidTopology.Device.builder(id,new PhysicalFluidTopology.Position(DIMENSION,x,BOTTOM,z),kind,kind==TopologyCompiler.Kind.FILTER?PhysicalFluidTopology.Direction.EAST:PhysicalFluidTopology.Direction.NORTH,BLOCK,new FlowControl.Passive()).build();
    }
    private double[] only(int component){double[] n=new double[MaterialTestBasis.NETWORK+1];n[component]=1;return n;}

    /** Water generator at x=0, pipes at x=1-3, tank at x=4, pipes at x=5-7, void at x=8; nitrogen generator at z=3 over two
     * pipes at z=1-2 beside the tank: one island (the tank is not a fixed boundary). */
    private PassiveNetwork island(double tankPressure) {
        var devices=new ArrayList<PhysicalFluidTopology.Device>();
        devices.add(device(1,0,0,TopologyCompiler.Kind.GENERATOR));
        devices.add(device(2,1,0,TopologyCompiler.Kind.PIPE));devices.add(device(3,2,0,TopologyCompiler.Kind.FILTER));devices.add(device(4,3,0,TopologyCompiler.Kind.PIPE));
        devices.add(device(5,4,0,TopologyCompiler.Kind.RESERVOIR));
        for(int x=5;x<=7;x++)devices.add(device(1+x,x,0,TopologyCompiler.Kind.PIPE));
        devices.add(device(9,8,0,TopologyCompiler.Kind.VOID));
        devices.add(device(10,4,1,TopologyCompiler.Kind.PIPE));devices.add(device(11,4,2,TopologyCompiler.Kind.PIPE));
        devices.add(device(12,4,3,TopologyCompiler.Kind.GENERATOR));
        var stock=new LinkedHashMap<Long,PassiveNetwork.Reservoir>();
        stock.put(1L,new FluidDeviceSpec(1,298.15,ATMOSPHERE,only(MaterialTestBasis.NETWORK)).initialize(devices.get(0),model,()->{}));
        stock.put(5L,FluidTestSupport.chargedBoundary(new FluidDeviceSpec(1,298.15,tankPressure,only(MaterialTestBasis.NITROGEN)),devices.get(4),model));
        stock.put(9L,FluidTestSupport.chargedBoundary(new FluidDeviceSpec(1,298.15,ATMOSPHERE,only(MaterialTestBasis.NITROGEN)),devices.get(8),model));
        stock.put(12L,FluidTestSupport.chargedBoundary(new FluidDeviceSpec(1,298.15,FEED,only(MaterialTestBasis.NITROGEN)),devices.get(11),model));
        var cakes=new LinkedHashMap<Long,InlineFilter>();cakes.put(3L,new InlineFilter(.01,1e6,com.wormzjl.icheme.science.fluid.state.SolidInventory.EMPTY,0));
        var compiled=PhysicalFluidTopology.compile(devices,stock,cakes,model.initialNitrogenCharge(1,298.15,ATMOSPHERE,()->{}));
        var connected=compiled.islands().stream().filter(i->i.physicalIds().size()==devices.size()).toList();
        assertEquals(1,connected.size(),"one island holds both generators, the tank and the void: "+compiled.diagnostics());
        return connected.getFirst().graph();
    }
    private static int indexOf(PassiveNetwork graph,long id) {
        for(int i=0;i<graph.reservoirs().size();i++)if(graph.reservoirs().get(i).id()==id)return i;
        throw new AssertionError("no node "+id);
    }

    @Test void aDeadHeadedFilterRunBesideAFlowingBranchCarriesExactlyZero() {
        var graph=island(120000);
        int water=indexOf(graph,1),tank=indexOf(graph,5),feed=indexOf(graph,12),drain=indexOf(graph,9);
        var solver=new PassiveIntervalSolver(model);double step=FluidTestSupport.COLD_START_SECONDS;
        int intervals=40;double[] flows=null;
        for(int i=0;i<intervals;i++) {
            PassiveIntervalSolver.Result result;
            try{result=solver.solve(graph,5,PassiveIntervalSolver.Settings.defaults(),()->{},Math.min(step,5));}
            catch(RuntimeException held){throw new AssertionError("held at interval "+(i+1)+": "+held.getMessage(),held);}
            graph=result.graph();step=solver.nextStepEstimate();flows=result.averageMassFlows();
            for(int edge=0;edge<flows.length;edge++) {
                var pipe=graph.pipes().get(edge);
                if(pipe.first()==feed||pipe.second()==feed||pipe.first()==drain||pipe.second()==drain)continue;
                assertEquals(0,flows[edge],0,"interval "+(i+1)+": the dead-headed run carries exactly zero through the filter");
                if(pipe.first()!=water&&pipe.second()!=water)continue;
                assertEquals(FlowControl.Mode.CLOSED,result.endpointModes().get(edge),"interval "+(i+1)+": the water generator's run is closed");
                assertEquals(0,flows[edge],0,"interval "+(i+1)+": the water generator's run carries exactly zero");
            }
        }
        var held=graph.reservoirs().get(tank).inventory().moles();
        assertEquals(0,held[held.length-1],0,"no water reaches the tank");
        double p=graph.reservoirs().get(tank).state().pressure(),through=0;
        for(int edge=0;edge<flows.length;edge++){var pipe=graph.pipes().get(edge);if(pipe.first()!=water&&pipe.second()!=water)through=Math.max(through,Math.abs(flows[edge]));}
        System.out.println("DEAD_HEAD_BESIDE_FLOW tank P after "+intervals+" intervals="+p+" through-flow="+through+" kg/s");
        assertTrue(p>ATMOSPHERE&&p<FEED,"the tank stands between the void and the feed: "+p);
        assertTrue(through>0,"nitrogen flows through the tank");
    }
}
