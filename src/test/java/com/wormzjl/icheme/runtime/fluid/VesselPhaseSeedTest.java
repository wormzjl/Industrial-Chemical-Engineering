package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.diagnostics.SolverDiagnostics;
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
import com.wormzjl.icheme.science.fluid.thermo.TestPhases;

/**
 * Test world rows 2 and 9b, holds H1 and H3 (documentation/2026-09-24-coolprop-low-temperature/TEST_WORLD_RIG_HOLDS_TRACE.md;
 * owner decision D40 A): a vessel's phase seed comes from its own state, its committed layout or, at a crossing, its
 * liquid at its own bubble pressure and a vapour filling the volume it lost; a vessel closure that fails proposes nothing.
 * Each island is built as the world forms it from placed blocks: compiled, its junctions minted
 * ({@link PassiveNetwork#mintJunctions}), then consecutive jobs on one {@link RetainedSolver}: the first one tick long, as
 * the rig's slice after the edit, then 5 s each. The tanks are the rig's committed states, closed by their interval-start
 * refresh ({@link InventoryEquilibrium#refresh}).
 */
class VesselPhaseSeedTest {
    static final String DIMENSION="minecraft:overworld",PILOT="icheme:pilot_cryogenic";
    static final long VENT=1,TANK=3,GENERATOR=5;
    static final int METHANE=1,CO2=3;
    final FluidThermodynamics pilot=FluidTestSupport.model(com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot(),PILOT);

    static PhysicalFluidTopology.Device device(long id,int x,int y,int z,Kind kind,double diameter) {
        return new PhysicalFluidTopology.Device(id,new PhysicalFluidTopology.Position(DIMENSION,x,y,z),kind,NORTH,
                new PipeResistance.Geometry(1,diameter,PipeResistance.DEFAULT_ROUGHNESS_METRES,0),new FlowControl.Passive());
    }
    static int node(PassiveNetwork graph,long id){for(int i=0;i<graph.reservoirs().size();i++)if(graph.reservoirs().get(i).id()==id)return i;throw new AssertionError(id);}
    /** The vent: the connection from the tank's top face. */
    static int vent(PassiveNetwork graph){int t=node(graph,TANK);for(int e=0;e<graph.pipes().size();e++){var p=graph.pipes().get(e);if(p.first()==t&&p.firstPort()==PassiveNetwork.PhasePort.VAPOR||p.second()==t&&p.secondPort()==PassiveNetwork.PhasePort.VAPOR)return e;}throw new AssertionError("no vent");}

    /** A 1 m3 tank of {@code liquid} and {@code vapour} mol at (T, P) beside {@code solids}, as its refresh closes it. */
    PassiveNetwork.Reservoir tank(double t,double p,double[] liquid,double[] vapour,SolidInventory solids) {
        var guess=TestPhases.state(pilot,t,p,liquid,vapour,0,0).withSolids(solids);
        var vessel=new PassiveNetwork.Reservoir(TANK,1,guess,PassiveNetwork.NodeKind.RESERVOIR,new PassiveNetwork.Inventory(1,PhaseLayout.totalAmounts(guess),guess.internalEnergy(),solids));
        return InventoryEquilibrium.refresh(new PassiveNetwork(List.of(vessel),List.of()),pilot,()->{}).graph().reservoirs().getFirst();
    }
    /** The tank under its top vent (one block of 5 cm pipe to a 1 atm void), and {@code extra} devices, compiled and minted. */
    PassiveNetwork island(PassiveNetwork.Reservoir tank,Map<Long,PassiveNetwork.Reservoir> extraBoundaries,PhysicalFluidTopology.Device... extra) {
        var devices=new ArrayList<>(List.of(device(VENT,0,3,0,Kind.VOID,.05),device(2,0,2,0,Kind.PIPE,.05),device(TANK,0,1,0,Kind.RESERVOIR,.05)));
        devices.addAll(List.of(extra));
        double[] air=new double[pilot.componentCount()];air[0]=1;
        var boundaries=new LinkedHashMap<Long,PassiveNetwork.Reservoir>();
        boundaries.put(TANK,tank);
        boundaries.put(VENT,new FluidDeviceSpec(1,298.15,101325,air).initialize(devices.getFirst(),pilot,()->{}));
        boundaries.putAll(extraBoundaries);
        var compiled=PhysicalFluidTopology.compile(devices,boundaries,Map.of(),NetworkReduction.ambient(pilot));
        var connected=compiled.islands().stream().filter(i->i.physicalIds().size()==devices.size()).toList();
        assertEquals(1,connected.size(),"one connected island: "+compiled.diagnostics());
        return PassiveNetwork.mintJunctions(connected.getFirst().graph(),pilot);
    }
    /** Consecutive jobs of {@code seconds} each on one retained solver; the results. */
    List<PassiveIntervalSolver.Result> run(PassiveNetwork graph,String label,double... seconds) {
        var retained=new RetainedSolver();PassiveIntervalSolver.Result last=null;var results=new ArrayList<PassiveIntervalSolver.Result>();
        for(int slice=1;slice<=seconds.length;slice++) {
            retained.committed(last);
            try{last=retained.solve(pilot,graph,seconds[slice-1],PassiveIntervalSolver.Settings.defaults(),()->{});}
            catch(RuntimeException held){throw new AssertionError(label+", slice "+slice+": HELD: "+held.getMessage(),held);}
            graph=last.graph();results.add(last);
            var tank=graph.reservoirs().get(node(graph,TANK));var s=tank.state();int vent=vent(graph);
            System.out.printf(Locale.ROOT,"VESSEL_SEED %s slice %d (%.2f s): tank %.4f K %.1f Pa liquid %.5f m3 vapour %.5f m3 fluid %.3f kg solids %.3f kg; vent %s %.5f kg/s; substeps %d/%d%n",
                    label,slice,seconds[slice-1],s.temperature(),s.pressure(),TestPhases.liquidVolume(s),s.vaporVolume(),s.mass()-s.solidMoments().mass(),s.solidMoments().mass(),
                    last.endpointModes().get(vent),last.averageMassFlows()[vent],last.acceptedSubsteps(),last.rejectedSubsteps());
        }
        return results;
    }

    /** Row 2's pool at the hold (tick 1682): 22 071 mol of pure methane at its saturation, 112.30 K and 107.5 kPa, 21.36
     * mol of it vapour (0.179 m3), under its top vent; the CO2 line, one block of 1 cm pipe from a generator of CO2 gas at
     * 250 K and 250 kPa, enters the tank's side as its generator is set. */
    PassiveNetwork pool() {
        double[] liquid=new double[4],vapour=new double[4];liquid[METHANE]=22049.95;vapour[METHANE]=21.36;
        var pool=tank(112.3015,107503.48,liquid,vapour,new SolidInventory(List.of()));
        double[] co2=new double[pilot.componentCount()];co2[CO2]=1;
        var line=device(4,0,1,-1,Kind.PIPE,.01);var generator=device(GENERATOR,0,1,-2,Kind.GENERATOR,.05);
        return island(pool,Map.of(GENERATOR,new FluidDeviceSpec(1,250,250000,co2).initialize(generator,pilot,()->{})),line,generator);
    }

    /**
     * H1, row 2: a vented pool of pure methane at its saturation takes its CO2 line with no hold. Physics: the pool boils
     * down its saturation line toward the vent's 1 atm, 111.67 K, its vapour about the 0.18 m3 it holds; the CO2, a few
     * grams a second through 1 cm, dissolves in it, and what the cold liquid cannot hold freezes out as dry ice (0.17 kg
     * after 15 s). Its first pass starts from its committed layout, at a wall residual of
     * about 0, and converges in a few Newton iterations. Before D40 A it started from the TP flash of its moles with the
     * arriving CO2: pure methane at its saturation pressure has no TP split, and that flash put 99 % of the moles in the
     * vapour, 183 m3 in the 1 m3 tank (wall residual 182); the Newton stopped at its iteration limit at every step size
     * down to 2e-5 s, and the rig held from tick 1682 until row 7's edit.
     */
    @Test void aVentedMethanePoolAtSaturationTakesItsCo2LineWithNoHold() {
        var graph=pool();var start=graph.reservoirs().get(node(graph,TANK)).state();
        SolverDiagnostics.reset();SolverDiagnostics.ENABLED=true;PassiveStepSolver.Result step;
        try{step=new PassiveStepSolver(pilot).solve(graph,.05,()->{});}
        catch(RuntimeException held){throw new AssertionError("the first 0.05 s step: "+held.getMessage(),held);}
        finally{SolverDiagnostics.ENABLED=false;}
        var first=step.states().get(node(graph,TANK));
        System.out.printf(Locale.ROOT,"VESSEL_SEED pool: %.4f K %.2f Pa vapour %.5f m3; first step %.4f K %.2f Pa vapour %.5f m3, %d pass(es), %d Newton iterations, |r| %.3e%n",
                start.temperature(),start.pressure(),start.vaporVolume(),first.temperature(),first.pressure(),first.vaporVolume(),
                SolverDiagnostics.activeSetPasses.sum(),SolverDiagnostics.newtonIterations.sum(),step.numerical().residualNorm());
        assertEquals(1,SolverDiagnostics.activeSetPasses.sum(),"one pass: the pool keeps its liquid and vapour");
        assertTrue(SolverDiagnostics.newtonIterations.sum()<=8,"from its own layout the pass converges in a few iterations: "+SolverDiagnostics.newtonIterations.sum());
        var results=run(graph,"H1 pool",.05,5,5,5);
        var end=results.getLast().graph().reservoirs().get(node(results.getLast().graph(),TANK)).state();
        assertTrue(end.temperature()<start.temperature()&&end.temperature()>111.5,"the pool boils down its saturation line: "+end.temperature());
        assertTrue(end.pressure()<start.pressure()&&end.pressure()>101325,"toward the vent's 1 atm: "+end.pressure());
        assertEquals(.18,end.vaporVolume(),.02,"its vapour about the 0.18 m3 it holds");
    }

    /** Row 9b's slush tank at tick 3911, after the cut: 254 kg of liquid methane with 4.2 % CO2 dissolved beside 344 kg of
     * dry ice at 0.25 mm, liquid-full at 172.76 K and 2.576 MPa, its bubble point, under its top vent. */
    PassiveNetwork slush() {
        var dryIce=new SolidInventory(List.of(new SolidInventory.Population(pilot.crystalSolids().all().getFirst().material(),new ParticleSize("0.00025"),343.733)));
        double[] liquid=new double[4];liquid[METHANE]=14144.8;liquid[CO2]=618.4;
        return island(tank(172.7562,2576056.87,liquid,new double[4],dryIce),Map.of());
    }

    /**
     * H3, row 9b: a liquid-full slush tank vented at its top vents toward 1 atm, its vent passing gas. Physics: drawn at the
     * top, the liquid falls below its bubble pressure and boils, a bubble of under a litre at first (at the rig's failing
     * point, 2.2 mol in 0.84 L at its bubble line, at the tank's own energy and volume); the tank then flashes down its
     * bubble line toward 1 atm and about 112 K, cooling its liquid and its dry ice, whose heat boils most of the methane,
     * and the vent passes the gas (the rig at {@code eec02758}: 139.9 K and 637 kPa 30 s after the cut). Before D40 A the
     * phase check offered the vapour of the TP answer at the tank's (T, P), 13 to 29 m3 for its 0.78 m3 of fluid space; the
     * tank's closure from there failed, and its last iterate, all 14 420 mol in the vapour slot as one dense root, stood as
     * the split, the liquid relabelled; every later pass failed, and the rig held at every slice from tick 3911.
     */
    @Test void aLiquidFullSlushTankVentsTowardOneAtmosphereWhileItsVentPassesGas() {
        var graph=slush();var start=graph.reservoirs().get(node(graph,TANK)).state();
        System.out.printf(Locale.ROOT,"VESSEL_SEED slush: %.4f K %.1f Pa liquid %.5f m3 vapour %.5f m3 solids %.3f kg%n",
                start.temperature(),start.pressure(),TestPhases.liquidVolume(start),start.vaporVolume(),start.solidMoments().mass());
        var results=run(graph,"H3 slush",.05,.1,5,5,5,5,5,5);
        var g=results.getLast().graph();var end=g.reservoirs().get(node(g,TANK)).state();int vent=vent(g);
        assertTrue(end.pressure()<1e6,"the tank vents toward 1 atm: "+end.pressure());
        assertTrue(end.temperature()<160,"down its bubble line: "+end.temperature());
        assertTrue(end.vaporVolume()>0,"it holds the vapour it boils");
        double flow=Math.abs(results.getLast().averageMassFlows()[vent]);
        assertTrue(flow>0&&flow<10,"the vent passes gas, not the liquid at its cap (114 to 118 kg/s): "+flow);
    }
}
