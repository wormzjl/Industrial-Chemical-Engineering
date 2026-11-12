package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction.*;
import static org.junit.jupiter.api.Assertions.*;
import com.wormzjl.icheme.science.fluid.thermo.TestPhases;

/**
 * Row D23-1 of the owner's test world (documentation/2026-09-24-coolprop-low-temperature/TEST_WORLD_PLAN.md, row 8 and
 * F1 in section 5): liquid CO2 at 250 K and 2.5 MPa let down through a pressure valve set at 1.5 MPa and one block of
 * pipe into an empty 1 m3 tank vented at the top to a 1 atm void, at the row's 4 mm let-down pipe and at 5 cm. Built as
 * the world forms the island from placed blocks: compiled, its junctions minted ({@link PassiveNetwork#mintJunctions}),
 * then consecutive jobs on one {@link RetainedSolver}: the first one tick long, to the scheduler's next boundary, as the
 * row's world ran it, then 5 s each.
 *
 * <p>Physics. Through 4 mm the valve passes 0.283 kg/s, and at 1 atm the liquid flashes to dry ice and vapour (48 % and
 * 52 % by mass at 194.63 K). The tank stays at the vent's 101.3 to 101.5 kPa; until its refresh the frost's share rides
 * on the stand-in liquid at 184.8 K (Peng-Robinson's saturation at 1 atm, 9.8 K under the frost point, the known
 * approximation F3), and each refresh freezes it to dry ice. Through 5 cm the first tick is the same flash at 1 atm and
 * the refresh after it makes 1.7 kg of dry ice; then the valve passes about 90 kg/s, which the 5 cm vent cannot pass as
 * gas, so the tank floods with liquid CO2 at about 1.4 MPa and 242 K, above CO2's triple point (518 kPa), and the next
 * refresh melts the dry ice. Nothing in the tank approaches CO2's 90 K floor: without a stand-in for the frost's share
 * the arrival's latent heat could only cool the vapour left beside the dry ice, which reached 90 K in 12 ms of flow (F1).
 */
class LiquidCo2LetDownTest {
    static final String DIMENSION="minecraft:overworld",PILOT="icheme:pilot_cryogenic";
    static final long GENERATOR=1,VALVE=3,LET_DOWN=4,TANK=5;
    static final int CO2=3;
    final FluidThermodynamics pilot=FluidTestSupport.model(com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot(),PILOT);

    static PhysicalFluidTopology.Device device(long id,int x,int y,Kind kind,PhysicalFluidTopology.Direction facing,FlowControl control,double diameter) {
        return new PhysicalFluidTopology.Device(id,new PhysicalFluidTopology.Position(DIMENSION,x,y,0),kind,facing,
                new PipeResistance.Geometry(1,diameter,PipeResistance.DEFAULT_ROUGHNESS_METRES,0),control);
    }
    static PhysicalFluidTopology.Device device(long id,int x,int y,Kind kind){return device(id,x,y,kind,NORTH,new FlowControl.Passive(),.05);}
    static int node(PassiveNetwork graph,long id){for(int i=0;i<graph.reservoirs().size();i++)if(graph.reservoirs().get(i).id()==id)return i;throw new AssertionError(id);}
    static double dryIce(PassiveNetwork.Reservoir tank){double m=0;for(var p:tank.inventory().solids().crystals())m+=p.massKg();return m;}

    /** The row's seven blocks with the let-down pipe at {@code diameter}, compiled and minted, the tank placed empty. */
    PassiveNetwork island(double diameter) {
        var devices=List.of(device(GENERATOR,0,0,Kind.GENERATOR),device(2,1,0,Kind.PIPE),
                device(VALVE,2,0,Kind.VALVE,EAST,new FlowControl.PressureValve(1.5e6),.05),device(LET_DOWN,3,0,Kind.PIPE,NORTH,new FlowControl.Passive(),diameter),
                device(TANK,4,0,Kind.RESERVOIR),device(6,4,1,Kind.PIPE),device(7,4,2,Kind.VOID));
        double[] co2=new double[pilot.componentCount()],air=new double[pilot.componentCount()];co2[CO2]=1;air[0]=1;
        var boundaries=new LinkedHashMap<Long,PassiveNetwork.Reservoir>();
        boundaries.put(GENERATOR,new FluidDeviceSpec(1,250,2.5e6,co2).initialize(devices.get(0),pilot,()->{}));
        boundaries.put(TANK,new FluidDeviceSpec(1,298.15,101325,air).initialize(devices.get(4),pilot,()->{}));
        boundaries.put(7L,new FluidDeviceSpec(1,298.15,101325,air).initialize(devices.get(6),pilot,()->{}));
        var compiled=PhysicalFluidTopology.compile(devices,boundaries,Map.of(),NetworkReduction.ambient(pilot));
        var connected=compiled.islands().stream().filter(i->i.physicalIds().size()==devices.size()).toList();
        assertEquals(1,connected.size(),"one connected island: "+compiled.diagnostics());
        return PassiveNetwork.mintJunctions(connected.getFirst().graph(),pilot);
    }

    /** A one-tick slice, then four of 5 s; the tank after each. */
    List<PassiveNetwork.Reservoir> run(double diameter) {
        var graph=island(diameter);var retained=new RetainedSolver();PassiveIntervalSolver.Result last=null;var tanks=new ArrayList<PassiveNetwork.Reservoir>();
        int valve=-1;for(int e=0;e<graph.pipes().size();e++)if(graph.pipes().get(e).control() instanceof FlowControl.PressureValve)valve=e;
        double[] seconds={.05,5,5,5,5};
        for(int slice=1;slice<=seconds.length;slice++) {
            retained.committed(last);
            try{last=retained.solve(pilot,graph,seconds[slice-1],PassiveIntervalSolver.Settings.defaults(),()->{});}
            catch(RuntimeException held){throw new AssertionError(diameter+" m let-down, slice "+slice+": HELD: "+held.getMessage(),held);}
            graph=last.graph();var tank=graph.reservoirs().get(node(graph,TANK));var s=tank.state();tanks.add(tank);
            System.out.printf(Locale.ROOT,"LET_DOWN %.3f m slice %d: valve %s %.5f kg/s; tank %.3f K %.1f Pa liquid %.4e m3 vapour %.4e m3 fluid %.4f kg dry ice %.4f kg; substeps %d/%d%n",
                    diameter,slice,last.endpointModes().get(valve),last.averageMassFlows()[valve],s.temperature(),s.pressure(),TestPhases.liquidVolume(s),s.vaporVolume(),
                    tank.empty()?0:s.mass()-s.solidMoments().mass(),dryIce(tank),last.acceptedSubsteps(),last.rejectedSubsteps());
        }
        return tanks;
    }

    /** At 4 mm: the tank stays at about 184.8 K and 101.5 kPa, and its refreshes make dry ice. */
    @Test void liquidCo2LetDownThroughFourMillimetresMakesDryIceAtOneBar() {
        var tanks=run(.004);
        for(var tank:tanks) {
            assertEquals(184.8,tank.state().temperature(),.3,"the stand-in's boiling point at the vent's pressure");
            assertEquals(101.5e3,tank.state().pressure(),.5e3,"the vent holds the tank at 1 atm");
        }
        assertTrue(dryIce(tanks.getLast())>1,"dry ice at the refreshes: "+dryIce(tanks.getLast())+" kg");
    }

    /** At 5 cm: the first tick's flash at 1 atm makes dry ice at the refresh; the let-down then floods the tank with
     * liquid CO2 above its triple point, and the dry ice melts. */
    @Test void liquidCo2LetDownThroughFiveCentimetresFloodsTheTankAboveTheTriplePoint() {
        var tanks=run(.05);var first=tanks.getFirst().state();
        assertEquals(184.8,first.temperature(),.3,"the first tick flashes at 1 atm");
        assertEquals(101.5e3,first.pressure(),.5e3,"the first tick flashes at 1 atm");
        assertTrue(dryIce(tanks.get(1))>1,"dry ice at the refresh after it: "+dryIce(tanks.get(1))+" kg");
        var tank=tanks.getLast();var s=tank.state();
        assertTrue(s.pressure()>518e3,"above CO2's triple point: "+s.pressure());
        assertTrue(TestPhases.liquidVolume(s)>.5,"liquid CO2 fills most of the tank: "+TestPhases.liquidVolume(s));
        assertEquals(240,s.temperature(),5,"near the feed's 250 K, flashed to the tank's pressure");
        assertEquals(0,dryIce(tank),"the dry ice melted above the triple point");
    }
}
