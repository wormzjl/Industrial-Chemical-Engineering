package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.icheme.science.fluid.state.ParticleSize;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * F2 of the owner's test world (row R9; documentation/2026-09-24-coolprop-low-temperature/TEST_WORLD_PLAN.md, section 5),
 * the owner's fix A (2026-10-16): a pump under a vented LNG slurry tank starts. The tank is R9's after its CO2 feed: 1 m3
 * of liquid methane boiling at 1 atm (111.6 K) with the CO2 it dissolves (0.05 mol %), 2.9 kg of dry ice at 0.25 mm and
 * the methane vapour above them, vented at the top to a void. Three blocks of 0.05 m pipe run from its bottom face to a
 * pump, which discharges through one block into a void. Built as the world forms an island from placed blocks: compiled,
 * its junctions minted ({@link PassiveNetwork#mintJunctions}), then consecutive 5 s jobs on one {@link RetainedSolver}.
 * The vent was placed first, as in the row, so the compile builds the junctions on its 298 K nitrogen as their
 * placeholder (the island's first boundary), which the mint replaces.
 *
 * <p>Physics: at 5 L/s the pump draws 5 L/s of the slurry, about 2.2 kg/s at 2.55 m/s in the bore, above the 1.02 m/s
 * that keeps 0.25 mm dry ice moving in the line: the crystals ride the liquid and the pump runs. At 0.5 L/s (0.25 m/s)
 * they settle and the suction line closes; raised again to 5 L/s, the pump's own drive suspends them, the line opens and
 * the pump runs.
 */
class SlurryTankPumpTest {
    static final String DIMENSION="minecraft:overworld",PILOT="icheme:pilot_cryogenic";
    static final long VENT=1,TANK=3,PUMP=8,SIDE_VOID=12;
    static final int METHANE=1,CO2=3;
    static final PipeResistance.Geometry BLOCK=new PipeResistance.Geometry(1,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0);
    final FluidThermodynamics pilot=FluidTestSupport.model(MaterialCatalog.bundled(),PILOT);

    static PhysicalFluidTopology.Device device(long id,int x,int y,int z,Kind kind,PhysicalFluidTopology.Direction facing,FlowControl control) {
        return new PhysicalFluidTopology.Device(id,new PhysicalFluidTopology.Position(DIMENSION,x,y,z),com.wormzjl.icheme.fluid.support.FluidTestSupport.mechanical(kind),facing,BLOCK,control);
    }
    static PhysicalFluidTopology.Device device(long id,int x,int y,int z,Kind kind){return device(id,x,y,z,kind,NORTH,new FlowControl.Passive());}
    static int node(PassiveNetwork graph,long id){for(int i=0;i<graph.reservoirs().size();i++)if(graph.reservoirs().get(i).id()==id)return i;throw new AssertionError(id);}
    static int edge(PassiveNetwork graph,Class<?> control){for(int e=0;e<graph.pipes().size();e++)if(control.isInstance(graph.pipes().get(e).control()))return e;throw new AssertionError(control);}
    /** The suction line: the connection from the tank's bottom face. */
    static int suction(PassiveNetwork graph){int t=node(graph,TANK);for(int e=0;e<graph.pipes().size();e++){var p=graph.pipes().get(e);if(p.first()==t&&p.firstPort()==PassiveNetwork.PhasePort.LIQUID||p.second()==t&&p.secondPort()==PassiveNetwork.PhasePort.LIQUID)return e;}throw new AssertionError("no suction line");}

    /** R9's tank after its CO2 feed (the rig's read-back at row 7): its amounts at 111.59 K and 101.37 kPa beside the dry ice,
     * at that energy in 1 m3, as its interval-start refresh closes it. */
    PassiveNetwork.Reservoir slurryTank() {
        var dryIce=new SolidInventory(List.of(new SolidInventory.Population(pilot.crystalSolids().all().getFirst().material(),new ParticleSize("0.00025"),2.919)));
        double t=111.59,p=101370.5;
        double[] liquid=new double[4],vapour=new double[4];liquid[METHANE]=15130;liquid[CO2]=7.548;vapour[METHANE]=49.23;vapour[CO2]=5.6e-4;
        var guess=pilot.state(t,p,liquid,vapour,0,0,p).withSolids(dryIce);
        var vessel=new PassiveNetwork.Reservoir(TANK,1,guess,PassiveNetwork.NodeKind.RESERVOIR,new PassiveNetwork.Inventory(1,PhaseLayout.totalAmounts(guess),guess.internalEnergy(),dryIce));
        return InventoryEquilibrium.refresh(new PassiveNetwork(List.of(vessel),List.of()),pilot,()->{}).graph().reservoirs().getFirst();
    }

    /** The world's compile of the tank, its vent and the pump line, the pump at {@code target} m3/s, and {@code extra}
     * devices, minted. */
    PassiveNetwork island(double target,PhysicalFluidTopology.Device... extra) {
        var devices=new ArrayList<>(List.of(device(VENT,0,3,0,Kind.VOID),device(2,0,2,0,Kind.PIPE),device(TANK,0,1,0,Kind.RESERVOIR),
                device(4,0,0,0,Kind.PIPE),device(5,0,0,1,Kind.PIPE),device(6,0,0,2,Kind.PIPE),
                device(PUMP,0,0,3,Kind.PUMP,SOUTH,new FlowControl.Pump(target,500000)),device(9,0,0,4,Kind.PIPE),device(10,0,0,5,Kind.VOID)));
        devices.addAll(List.of(extra));
        double[] air=new double[pilot.componentCount()];air[0]=1;
        var boundaries=new LinkedHashMap<Long,PassiveNetwork.Reservoir>();
        boundaries.put(TANK,slurryTank());
        for(var d:devices)if(d.kind()==Kind.VOID)boundaries.put(d.id(),new FluidDeviceSpec(1,298.15,101325,air).initialize(d,pilot,()->{}));
        var compiled=PhysicalFluidTopology.compile(devices,boundaries,Map.of(),NetworkReduction.ambient(pilot));
        var connected=compiled.islands().stream().filter(i->i.physicalIds().size()==devices.size()).toList();
        assertEquals(1,connected.size(),"one connected island: "+compiled.diagnostics());
        return PassiveNetwork.mintJunctions(connected.getFirst().graph(),pilot);
    }
    /** {@link #island(double, PhysicalFluidTopology.Device...)} with R7's side outlet, one block of pipe from the tank's
     * east face to a void, closed by deposition: both directions, as the solid event closes a connection. */
    PassiveNetwork islandWithClosedSideOutlet(double target) {
        var graph=island(target,device(11,1,1,0,Kind.PIPE),device(SIDE_VOID,2,1,0,Kind.VOID));
        int tank=node(graph,TANK),side=node(graph,SIDE_VOID);var pipes=new ArrayList<>(graph.pipes());int closed=0;
        for(int e=0;e<pipes.size();e++){var p=pipes.get(e);if(p.first()==tank&&p.second()==side||p.first()==side&&p.second()==tank){pipes.set(e,p.withBlockedDirections(3));closed++;}}
        assertEquals(1,closed,"one side outlet from the tank to its void");
        return new PassiveNetwork(graph.reservoirs(),pipes,graph.scheduledTransfers());
    }
    /** The pump's suction junction: the pump connection's first node. */
    static FluidThermodynamics.State pumpJunction(PassiveNetwork graph){return graph.reservoirs().get(graph.pipes().get(edge(graph,FlowControl.Pump.class)).first()).state();}

    PassiveIntervalSolver.Result slice(RetainedSolver retained,PassiveIntervalSolver.Result last,PassiveNetwork graph,String label) {
        retained.committed(last);PassiveIntervalSolver.Result result;
        try{result=retained.solve(pilot,graph,5,PassiveIntervalSolver.Settings.defaults(),()->{});}
        catch(RuntimeException held){throw new AssertionError(label+": HELD: "+held.getMessage(),held);}
        var g=result.graph();int pump=edge(g,FlowControl.Pump.class),line=suction(g);var j=pumpJunction(g);var tank=g.reservoirs().get(node(g,TANK)).state();
        System.out.printf(Locale.ROOT,"SLURRY_PUMP %s: pump %s %.5f kg/s, suction %.5f kg/s closed=%d; junction %.2f K %.1f Pa %.1f kg/m3; tank %.2f K %.1f Pa liquid %.4f m3 solids %.3f kg; substeps %d/%d %s%n",
                label,result.endpointModes().get(pump),result.averageMassFlows()[pump],result.averageMassFlows()[line],g.pipes().get(line).blockedDirections(),
                j.temperature(),j.pressure(),j.mass()/j.volume(),tank.temperature(),tank.pressure(),tank.liquidVolume(),tank.solidMoments().mass(),
                result.acceptedSubsteps(),result.rejectedSubsteps(),result.rejectionReasons());
        return result;
    }

    /** At 5 L/s from the mint: the pump draws about 2.2 kg/s through an open line, its suction junction minted from the
     * slurry it draws (the tank's liquid at the tank's temperature), not from the 298 K nitrogen placeholder. */
    @Test void aPumpUnderAVentedLngSlurryTankStartsAndDrawsTheSlurry() {
        var minted=island(.005);var j=pumpJunction(minted);double tankTemperature=minted.reservoirs().get(node(minted,TANK)).state().temperature();
        System.out.printf(Locale.ROOT,"SLURRY_PUMP minted junction: %.2f K %.1f Pa %.1f kg/m3 liquid %.3e m3 vapour %.3e m3%n",j.temperature(),j.pressure(),j.mass()/j.volume(),j.liquidVolume(),j.vaporVolume());
        var retained=new RetainedSolver();PassiveIntervalSolver.Result last=null;var graph=minted;
        for(int k=1;k<=2;k++){last=slice(retained,last,graph,"fast "+k);graph=last.graph();}
        int pump=edge(graph,FlowControl.Pump.class);
        assertEquals(2.2,last.averageMassFlows()[pump],.15,"the pump draws about 2.2 kg/s of the slurry");
        assertEquals(0,graph.pipes().get(suction(graph)).blockedDirections(),"the suction line stays open");
        assertTrue(j.mass()/j.volume()>400&&Math.abs(j.temperature()-tankTemperature)<.01,"the suction junction is minted as the tank's liquid");
    }

    /** At 0.5 L/s the suction line closes on deposition; the same island's pump raised to 5 L/s runs at the next slice. */
    @Test void aSuctionLineTheSlowTargetClosedOpensUnderTheRaisedTarget() {
        var graph=island(.0005);var retained=new RetainedSolver();
        var last=slice(retained,null,graph,"slow");graph=last.graph();
        int pump=edge(graph,FlowControl.Pump.class),line=suction(graph);
        assertNotEquals(0,graph.pipes().get(line).blockedDirections(),"0.25 m/s settles the crystals: the suction line closes");
        assertTrue(last.rejectionReasons().keySet().stream().anyMatch(r->r.contains("DEPOSITION")),"on deposition: "+last.rejectionReasons());
        var pipes=new ArrayList<>(graph.pipes());var p=pipes.get(pump);
        pipes.set(pump,new PassiveNetwork.Pipe(p.id(),p.first(),p.second(),p.sections(),new FlowControl.Pump(.005,500000),p.blockedDirections(),p.filter(),p.firstPort(),p.secondPort()));
        graph=new PassiveNetwork(graph.reservoirs(),pipes,graph.scheduledTransfers());
        last=slice(retained,last,graph,"raised");graph=last.graph();
        assertNotEquals(FlowControl.Mode.INLET_WRONG_PHASE,last.endpointModes().get(pump),"the closure the slow target left does not leave the suction dry");
        assertEquals(0,graph.pipes().get(line).blockedDirections(),"the pump's drive suspends the crystals: the line opens");
        assertEquals(2.2,last.averageMassFlows()[pump],.15,"the pump draws about 2.2 kg/s");
    }

    /**
     * Test world row 9, the fast pump (H2; owner decision D40 A): a pump's start mode reads its own lines only. The pump
     * draws 5 L/s of the slurry from the tank's bottom while R7's side outlet stands closed by deposition, as the rig's
     * side outlet closed at its 1.02 m/s threshold. Physics: nothing in the pump's own lines changed, so it goes on at its
     * target, about 2.2 kg/s, its suction junction a few kPa above the tank. Before the rule, any blocked connection in the
     * island started every pump on its head limit: there the pump drew about 20 kg/s, its liquid-only suction junction fell
     * to the 1 Pa box floor, and the step stalled at every size (the rig held for good at tick 5075, |r| 0.4575).
     */
    @Test void aSideOutletClosedByDepositionLeavesThePumpOnItsTarget() {
        var graph=islandWithClosedSideOutlet(.005);var retained=new RetainedSolver();PassiveIntervalSolver.Result last=null;
        for(int k=1;k<=2;k++){last=slice(retained,last,graph,"side outlet closed "+k);graph=last.graph();}
        int pump=edge(graph,FlowControl.Pump.class);
        assertEquals(FlowControl.Mode.PUMP_TARGET,last.endpointModes().get(pump),"the pump stays on its target");
        assertEquals(2.2,last.averageMassFlows()[pump],.15,"the pump draws about 2.2 kg/s of the slurry");
    }
}
