package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.state.ParticleSize;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.icheme.science.material.MaterialTestBasis;
import java.util.*;
import static com.wormzjl.icheme.fluid.support.FluidTestSupport.nitrogen;
import static com.wormzjl.icheme.fluid.support.FluidTestSupport.water;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Lines of blocks a player builds, compiled as the world compiles them and run as the island worker runs them:
 * consecutive five-second intervals on one retained solver. The block, filter and pump settings are the world's own
 * defaults (FluidWorldAuthority.place and SlurryTransport.Settings.defaults).
 */
abstract class BlockLineHarness {
    static final String DIMENSION="minecraft:overworld";
    /** The world floor the in-game confirmations build on. */
    static final int BOTTOM=-59;
    static final PipeResistance.Geometry BLOCK=new PipeResistance.Geometry(1,.05,.000045,0);
    static final double FILTER_CAPACITY=.01,FILTER_RESISTANCE=1e6;
    /** The pump every pumped fixture in the tree uses. */
    static final double PUMP_HEAD=500000;
    final FluidThermodynamics model=FluidTestSupport.networkModel();

    /* ---------------- blocks and settings ---------------- */

    /** A block at (x, y, 0) with its kind's default control. */
    static PhysicalFluidTopology.Device device(long id,int x,int y,TopologyCompiler.Kind kind,PhysicalFluidTopology.Direction facing) {
        var control=switch(kind) {
            case PUMP->new FlowControl.Pump(.01,PUMP_HEAD);
            case VALVE->new FlowControl.PressureValve(200000);
            default->new FlowControl.Passive();
        };
        return new PhysicalFluidTopology.Device(id,new PhysicalFluidTopology.Position(DIMENSION,x,y,0),FluidTestSupport.mechanical(kind),facing,BLOCK,control);
    }
    static PhysicalFluidTopology.Device device(long id,int x,int y,TopologyCompiler.Kind kind){return device(id,x,y,kind,PhysicalFluidTopology.Direction.NORTH);}
    /** A block on the floor row. */
    static PhysicalFluidTopology.Device device(long id,int x,TopologyCompiler.Kind kind,PhysicalFluidTopology.Direction facing){return device(id,x,BOTTOM,kind,facing);}
    static PhysicalFluidTopology.Device device(long id,int x,TopologyCompiler.Kind kind){return device(id,x,BOTTOM,kind,PhysicalFluidTopology.Direction.NORTH);}
    /** The generator panel's own settings: pressure, water, and a solids volume fraction. */
    static FluidDeviceSpec generatorSpec(double pressure,double solidFraction) {
        var feed=solidFraction<=0?SlurryFeed.NONE:new SlurryFeed(solidFraction,
                List.of(new SlurryFeed.Grade("icheme:demo_particle",ParticleSize.micrometres("100"),1)));
        return new FluidDeviceSpec(1,298.15,pressure,water(),feed);
    }
    static FluidDeviceSpec generatorSpec(double pressure){return generatorSpec(pressure,0);}
    /** A 1 m3 nitrogen vessel at 298.15 K. */
    static FluidDeviceSpec tankSpec(double pressure){return new FluidDeviceSpec(1,298.15,pressure,nitrogen());}
    /** An empty cake in every filter block of the line. */
    static Map<Long,InlineFilter> emptyFilters(List<PhysicalFluidTopology.Device> devices) {
        var cakes=new LinkedHashMap<Long,InlineFilter>();
        for(var d:devices)if(d.kind()==TopologyCompiler.Kind.FILTER)cakes.put(d.id(),new InlineFilter(FILTER_CAPACITY,FILTER_RESISTANCE,SolidInventory.EMPTY,0));
        return cakes;
    }

    /* ---------------- compiling ---------------- */

    /** The compiled island the world would hand a worker for this line of blocks, its boundaries freshly charged
     * from their specs and the first boundary's state as the idle seed. */
    PassiveNetwork island(List<PhysicalFluidTopology.Device> devices,Map<Long,FluidDeviceSpec> specs) {
        var boundaries=new LinkedHashMap<Long,PassiveNetwork.Reservoir>();
        for(var d:devices)if(specs.containsKey(d.id()))boundaries.put(d.id(),FluidTestSupport.chargedBoundary(specs.get(d.id()),d,model));
        var seed=boundaries.values().iterator().next().state();
        return connected(devices,PhysicalFluidTopology.compile(devices,boundaries,emptyFilters(devices),seed));
    }
    /**
     * One island built as {@code FluidWorldAuthority.applyPending} builds it: the live boundary stock, the live filter
     * cakes, and the world's own nitrogen idle seed.
     */
    PassiveNetwork compile(List<PhysicalFluidTopology.Device> devices,Map<Long,PassiveNetwork.Reservoir> stock,Map<Long,InlineFilter> cakes) {
        return connected(devices,PhysicalFluidTopology.compile(devices,stock,cakes,model.initialNitrogenCharge(1,298.15,101325,()->{})));
    }
    private static PassiveNetwork connected(List<PhysicalFluidTopology.Device> devices,PhysicalFluidTopology.Compiled compiled) {
        var connected=compiled.islands().stream().filter(i->i.physicalIds().size()==devices.size()).toList();
        assertEquals(1,connected.size(),"Expected exactly one connected island: "+describe(compiled));
        return connected.getFirst().graph();
    }
    static String describe(PhysicalFluidTopology.Compiled compiled) {
        var text=new StringBuilder();
        for(int i=0;i<compiled.islands().size();i++)text.append("\n island ").append(i).append(" ids=").append(compiled.islands().get(i).physicalIds()).append(shape(compiled.islands().get(i).graph()));
        text.append("\n diagnostics=").append(compiled.diagnostics());
        return text.toString();
    }

    /* ---------------- running ---------------- */

    /** How far a line got, why it stopped if it did, the graph it finished on, and its last interval's modes and flows. */
    record Run(int survived,String detail,PassiveNetwork finished,List<FlowControl.Mode> modes,double[] flows) {
        boolean ok(){return detail.startsWith("OK");}
        PassiveNetwork graph(){return finished;}
        /** The terminating vessel: the last node. */
        FluidThermodynamics.State last(){return finished.reservoirs().getLast().state();}
        /** The tank by kind rather than by position: a device mints its outlet junction after it, so on a filter or
         * valve island the last node is a junction and not the vessel. */
        PassiveNetwork.Reservoir tankNode(){return tankOf(finished);}
        FluidThermodynamics.State tank(){return tankNode().state();}
    }
    static PassiveNetwork.Reservoir tankOf(PassiveNetwork graph) {
        for(int i=graph.reservoirs().size()-1;i>=0;i--) {
            var node=graph.reservoirs().get(i);
            if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR)return node;
        }
        throw new AssertionError("No reservoir in"+shape(graph));
    }
    /** Consecutive five-second intervals on one retained solver, as the island worker runs them. */
    Run run(PassiveNetwork graph,int count){return run(graph,count,null);}
    /** The same, the line's name in the outcome. */
    Run run(PassiveNetwork graph,int count,String tag) {
        var solver=new PassiveIntervalSolver(model);double step=FluidTestSupport.COLD_START_SECONDS;
        List<FlowControl.Mode> modes=List.of();double[] flows=new double[0];String name=tag==null?"":" ("+tag+")";
        for(int i=0;i<count;i++) {
            try {
                var result=solver.solve(graph,5,PassiveIntervalSolver.Settings.defaults(),()->{},Math.min(step,5));
                graph=result.graph();step=solver.nextStepEstimate();modes=result.endpointModes();flows=result.averageMassFlows();
                if(i==count-1)return new Run(count,"OK intervals="+count+name+" advanced="+result.advancedSeconds()+" accepted="+result.acceptedSubsteps()
                        +" rejected="+result.rejectedSubsteps()+" reasons="+result.rejectionReasons(),graph,modes,flows);
            }catch(RuntimeException held) {
                return new Run(i,"HELD at interval "+(i+1)+"/"+count+name+" (t="+(5*i)+" s): "+held.getMessage()+shape(graph),graph,modes,flows);
            }
        }
        return new Run(count,"OK intervals=0"+name,graph,modes,flows);
    }
    static String shape(PassiveNetwork graph) {
        var text=new StringBuilder();
        for(int n=0;n<graph.reservoirs().size();n++) {
            var node=graph.reservoirs().get(n);
            text.append("\n  node[").append(n).append("] id=").append(node.id()).append(' ').append(node.kind())
                    .append(" y=").append(node.elevation()).append(" P=").append(node.state().pressure())
                    .append(" mass=").append(node.state().mass()).append(" rho=").append(node.state().mass()/node.state().volume())
                    .append(" liquid=").append(node.state().liquidVolume()+node.state().waterVolume())
                    .append(" vapour=").append(node.state().vaporVolume())
                    .append(" solidKg=").append(node.state().solidMoments().mass())
                    .append(" storedSolidKg=").append(node.inventory().solids().massKg());
        }
        for(var pipe:graph.pipes())text.append("\n  pipe id=").append(pipe.id()).append(' ').append(pipe.first()).append("->").append(pipe.second())
                .append(' ').append(pipe.control().getClass().getSimpleName()).append(" blocked=").append(pipe.blockedDirections())
                .append(pipe.filter()==null?" plain":" filter captured="+pipe.filter().captured().massKg());
        return text.toString();
    }
    /** The density of the generator's own charge: the column a line fed from below stands in. */
    double waterDensity(double pressure) {
        var state=model.flashTP(298.15,pressure,water(),()->{});
        return state.mass()/state.volume();
    }
}
