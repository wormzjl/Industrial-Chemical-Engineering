package com.wormzjl.icheme.runtime;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.runtime.fluid.*;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.icheme.science.material.MaterialTestBasis;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The pumped fills and pump transfers the WP5 in-game rig placed, with the mod's placement defaults (reservoirs 1 m3 of
 * nitrogen at 298.15 K and 101,325 Pa, generators water at 298.15 K and 101,325 Pa, voids nitrogen at 101,325 Pa, pipes
 * 1 m of 0.05 m bore, pumps 0.01 m3/s with a 500 kPa limit; FluidWorldAuthority.place), compiled through
 * {@link PhysicalFluidTopology}. In game every one of them was held: the three-tank fill for minutes, the six-tank fill,
 * the vented chains and the transfers for good, at about 12 cores. See
 * {@code documentation/fluid-followups/FLUID_PUMPED_FILL_REVIEW.md}.
 *
 * <p>The long runs go through the real {@link IslandCoordinator} - its slices, holds, retries and certificates at the
 * world defaults - with one synchronous worker whose jobs run on a clock that charges one microsecond per solver
 * checkpoint (a warm JIT measured 1.03 us), so the 2 s wall budget cuts attempts at reproducible points (the 1.5 s soft
 * budget only reads the load for the adaptive cadence since owner decision D37, S2).
 */
class FluidPumpedFillLineTest {
    private static final PipeResistance.Geometry BLOCK=new PipeResistance.Geometry(1,.05,.000045,0);
    /** W: a 0.1 m pipe block, the blocks the gas transfer needs on both sides of the compressor since only pipes connect
     * (decision D20): the compressor holds its ratio between its own ends, so a tank misses it by the line's friction at
     * the closing flow. With a 0.05 m suction block the compressor closed on a suction junction 4.2 Pa below its tank,
     * outside the 3 Pa this test states the ratio limit to; since the compressor's halves take the line's bore (owner
     * decision OD4 2026-10-16, batch 2026-10-15-custom-equipment-rendering), a 0.05 m discharge block leaves the
     * discharge tank 7.2 Pa short. Wide blocks add no measurable drop. */
    private static final PipeResistance.Geometry WIDE=new PipeResistance.Geometry(1,.1,.000045,0);
    private static final long NANOS_PER_CHECKPOINT=1_000;
    private final FluidThermodynamics model=FluidTestSupport.networkModel();

    private double[] pure(int component){double[] n=new double[MaterialTestBasis.NETWORK+1];n[component]=1;return n;}
    /** The compressor of the gas transfer (decision D2): the pump's 0.01 m3/s, ratio 1.01 (the lowest the plan's GUI bounds
     * allow; a 1013 Pa rise on 1 atm suction, near the pump's 575 Pa density-scaled limit this test was written for). */
    private static final double COMPRESSOR_RATIO=1.01;
    /** A line of blocks along +x, every block facing east, in the WP5 rig's letters: G generator, U pump, P pipe, R tank, V void;
     * a pipe block stands between the generator and the pump and between the suction tank and the compressor, since only pipes
     * connect (decision D20; they touched until 2026-09-28);
     * and C, a compressor (phase-ports batch, decision D2). A pump or compressor is in-line, a pipe carrying its control
     * (batch 2026-10-28-old-mover-removal), and has the bore of the line it is installed on. */
    private PassiveNetwork line(String layout) {
        var devices=new ArrayList<PhysicalFluidTopology.Device>();var boundaries=new LinkedHashMap<Long,PassiveNetwork.Reservoir>();
        for(int i=0;i<layout.length();i++) {
            var kind=switch(layout.charAt(i)){case 'G'->TopologyCompiler.Kind.GENERATOR;case 'U'->TopologyCompiler.Kind.PUMP;case 'C'->TopologyCompiler.Kind.COMPRESSOR;case 'P','W'->TopologyCompiler.Kind.PIPE;
                case 'R'->TopologyCompiler.Kind.RESERVOIR;case 'V'->TopologyCompiler.Kind.VOID;default->throw new IllegalArgumentException(layout);};
            var device=new PhysicalFluidTopology.Device(i+1,new PhysicalFluidTopology.Position("minecraft:overworld",i,64,0),com.wormzjl.icheme.fluid.support.FluidTestSupport.mechanical(kind),PhysicalFluidTopology.Direction.EAST,layout.charAt(i)=='W'||"UC".indexOf(layout.charAt(i))>=0&&i>0&&layout.charAt(i-1)=='W'?WIDE:BLOCK,
                    kind==TopologyCompiler.Kind.PUMP?new FlowControl.Pump(.01,500000):kind==TopologyCompiler.Kind.COMPRESSOR?new FlowControl.Compressor(.01,COMPRESSOR_RATIO):new FlowControl.Passive());
            devices.add(device);
            if(kind==TopologyCompiler.Kind.GENERATOR)boundaries.put(device.id(),new FluidDeviceSpec(1,298.15,101325,pure(MaterialTestBasis.NETWORK)).initialize(device,model,()->{}));
            else if(kind==TopologyCompiler.Kind.RESERVOIR||kind==TopologyCompiler.Kind.VOID)boundaries.put(device.id(),com.wormzjl.icheme.fluid.support.FluidTestSupport.chargedBoundary(new FluidDeviceSpec(1,298.15,101325,pure(MaterialTestBasis.NITROGEN)),device,model));
        }
        var compiled=PhysicalFluidTopology.compile(devices,boundaries);
        var connected=compiled.islands().stream().filter(i->i.physicalIds().size()==devices.size()).toList();
        assertEquals(1,connected.size(),"one island expected for "+layout+": "+compiled.diagnostics());
        return connected.getFirst().graph();
    }
    private static List<PassiveNetwork.Reservoir> tanks(PassiveNetwork graph){return graph.reservoirs().stream().filter(n->n.kind()==PassiveNetwork.NodeKind.RESERVOIR).toList();}
    private static double liquidFraction(FluidThermodynamics.State s){return (s.liquidVolume()+s.waterVolume())/s.volume();}

    /** One placed line under the real coordinator; see the class comment. */
    private final class Rig {
        final Map<Long,IslandCoordinator.Attempt> attempts=new LinkedHashMap<>();
        final Map<Long,ProcessSolveServices.FluidIslandCommand> commands=new HashMap<>();
        final IslandCoordinator coordinator;
        long requests,jobs,work,online;
        Rig(String layout) {
            coordinator=new IslandCoordinator(new IslandCoordinator.Dispatcher() {
                public int availableWorkers(){return 1-attempts.size();}
                public long nextRequestId(){return ++requests;}
                public boolean submit(IslandCoordinator.Attempt attempt,ProcessSolveServices.FluidIslandCommand command){attempts.put(attempt.slice().requestId(),attempt);commands.put(attempt.slice().requestId(),command);return true;}
                public void cancel(long request){}
            },changed->{},()->0L,new IslandCoordinator.Settings(2_000_000_000L,1_500_000_000L,64,false,100,CertificatePolicy.defaults()));
            coordinator.register(new IslandCoordinator.Snapshot(1,0,line(layout),new IslandClock.Snapshot(0,0,0,100),Optional.empty(),"READY"),model);
        }
        /** Online ticks, each: advance, run what was admitted, deliver, pump. Stops early when {@code done} holds. */
        void run(long ticks,java.util.function.Predicate<IslandCoordinator.Snapshot> done) {
            for(long tick=0;tick<ticks;tick++) {
                coordinator.tick();online++;
                for(long request:List.copyOf(attempts.keySet())) {
                    var attempt=attempts.remove(request);var command=commands.remove(request);long[] clock={0};
                    var result=(ProcessSolveServices.FluidIslandSolveResult)command.solve(FluidTestSupport.NEVER,()->clock[0]+=NANOS_PER_CHECKPOINT);
                    jobs++;work+=clock[0];coordinator.completed(attempt,Optional.of(result));
                }
                coordinator.pump();
                if(done.test(coordinator.observe(1)))return;
            }
        }
        IslandCoordinator.Snapshot island(){return coordinator.snapshot(1);}
        String describe() {
            var s=coordinator.observe(1);var text=new StringBuilder(String.format("online %d, committed %d, %d jobs, %.1f s of work, %s, %s",
                    online,s.clock().committedTick(),jobs,work/1e9,s.status(),s.certificate()));
            for(var tank:tanks(s.graph()))text.append(String.format(" [%.1f Pa, %.2f K, liquid %.4f]",tank.state().pressure(),tank.state().temperature(),liquidFraction(tank.state())));
            return text.toString();
        }
    }

    /**
     * The first slice of every pumped chain, from a fresh solver and the cold step: it used to be held at every
     * slice length by the step limiter on a steam trace in the second tank (six tanks: {@code Newton iteration limit
     * at residual 7.923408755940031E-7} in game and here, bit for bit).
     */
    @Test void everyPumpedChainCommitsItsFirstTickFromAColdSolver() {
        for(String layout:List.of("GPUPRPRPR","GPUPRPRPRPRPRPR","GPUPRPRPRPRPRPRPV")) {
            var result=new PassiveIntervalSolver(model).solve(line(layout),.05,PassiveIntervalSolver.Settings.defaults(),()->{},FluidTestSupport.COLD_START_SECONDS);
            System.out.println(layout+": first tick accepted="+result.acceptedSubsteps()+" rejected="+result.rejectedSubsteps()+" reasons="+result.rejectionReasons());
            var first=tanks(result.graph()).getFirst().state();
            assertTrue(liquidFraction(first)>0&&first.temperature()<298.15-5,layout+": water entering dry nitrogen evaporates and cools the first tank to its wet bulb: "+first.temperature());
        }
    }

    /**
     * The three-tank fill's twelve-tick slice from a cold solver: at 0.128 s the second tank has cooled and the third
     * pushes nitrogen back into it, and a rate solve that starts from a numerically positive flow on that connection
     * used to meet the velocity clamp's donor switch at zero flow and hold at {@code Newton line search stalled at
     * residual 0.10479353009252752} (in game after the first committed tick: {@code 0.10479996312241754}).
     */
    @Test void aPumpedFillPassesTheSecondTanksBackflowWithoutAHold() {
        var result=new PassiveIntervalSolver(model).solve(line("GPUPRPRPR"),.6,PassiveIntervalSolver.Settings.defaults(),()->{},FluidTestSupport.COLD_START_SECONDS);
        System.out.println("three-tank fill, 0.6 s: accepted="+result.acceptedSubsteps()+" rejected="+result.rejectedSubsteps()+" reasons="+result.rejectionReasons());
        assertEquals(.6,result.advancedSeconds(),1e-12);
    }

    /**
     * {@code fill100}'s line: water pumped into a closed chain of three nitrogen tanks. It fills until the pump stands
     * at its 500 kPa limit over the generator (601,325 Pa), where it closes, and the island certifies STEADY.
     */
    @Test void aPumpedThreeTankChainFillsToThePumpsShutoffAndCertifies() {
        var rig=new Rig("GPUPRPRPR");rig.run(20*420,s->s.certificate().isPresent());
        System.out.println("three-tank fill: "+rig.describe());
        var island=rig.island();
        assertTrue(island.certificate().isPresent(),"the filled chain certifies: "+rig.describe());
        for(var tank:tanks(island.graph()))assertEquals(601325,tank.state().pressure(),1,"every tank at the pump's shutoff: "+rig.describe());
        assertEquals(FlowControl.Mode.CLOSED,island.lastResult().orElseThrow().endpointModes().get(1),"the pump is closed at its shutoff corner");
        assertTrue(liquidFraction(tanks(island.graph()).getFirst().state())>.95,rig.describe());
        assertTrue(rig.work<20_000_000_000L,"the whole fill costs less than 20 s of worker time: "+rig.describe());
    }

    /** A pumped chain vented to a void: the tanks fill with water, the nitrogen leaves through the void, and the pump
     * holds its 0.01 m3/s through the chain. */
    @Test void aVentedChainReachesThroughFlowAtThePumpsTarget() {
        var rig=new Rig("GPUPRPRPRPV");rig.run(20*600,s->false);
        System.out.println("vented three-tank chain: "+rig.describe());
        var island=rig.island();var result=island.lastResult().orElseThrow();
        assertTrue(island.status().startsWith("FULL"),rig.describe());
        assertTrue(island.clock().committedTick()>=20*590,"the island keeps up with online time: "+rig.describe());
        assertEquals(FlowControl.Mode.PUMP_TARGET,result.endpointModes().get(1));
        double[] flows=result.averageMassFlows();
        assertTrue(flows[flows.length-1]>.8*flows[1],"through-flow reaches the void: "+Arrays.toString(flows));
        assertTrue(liquidFraction(tanks(island.graph()).getFirst().state())>.95,rig.describe());
    }

    /**
     * The six-tank chain never committed an interval in game (493 held retries in six minutes). It now passes the
     * start, where every downstream connection turns round while the first tank cools, and fills on at the pump's
     * target: the first two simulated seconds are the whole of its difficulty.
     */
    @Test void aSixTankChainPassesTheStartThatHeldItForever() {
        var rig=new Rig("GPUPRPRPRPRPRPR");rig.run(20*120,s->s.clock().committedTick()>=40);
        System.out.println("six-tank fill: "+rig.describe());
        assertTrue(rig.island().clock().committedTick()>=40,"past its first two seconds: "+rig.describe());
        assertEquals(FlowControl.Mode.PUMP_TARGET,rig.island().lastResult().orElseThrow().endpointModes().get(1));
    }

    /**
     * A compressor moving nitrogen from one closed tank to another (decision D2: a liquid-only pump refuses the gas, D1). Its
     * limit is a pressure ratio (decision D7): it reaches the discharge at {@code r_max} times the suction and then its
     * shutoff within a few seconds, and the island certifies. The suction tank is a bag (decision D54): it gives its gas
     * at the ambient and at its own temperature, shrinking as the gas leaves, so it stays at 298.15 K and 101325 Pa; the
     * discharge tank, a full bag, rises to {@code r_max} times that. (Before D54 the suction tank was rigid and cooled a
     * fraction of a kelvin by its expansion. Test name kept from the pump it was: the pump held its density-scaled limit,
     * 575 Pa on this gas, with the suction a quarter of a kelvin cooler. Before that, option P1 of the F1 review, the pump
     * evacuated the suction tank until it reached the model's 273.16 K floor after 21.9 s.)
     */
    @Test void aGasTransferClosesAtThePumpsScaledLimitAndCertifies() {
        var rig=new Rig("RWCWR");
        rig.run(20*120,s->s.certificate().isPresent());
        System.out.println("gas transfer (compressor): "+rig.describe());
        var island=rig.island();var suction=tanks(island.graph()).getFirst().state();var discharge=tanks(island.graph()).getLast().state();
        assertTrue(island.certificate().isPresent(),"the closed transfer certifies: "+rig.describe());
        assertEquals(FlowControl.Mode.CLOSED,island.lastResult().orElseThrow().endpointModes().get(1),"the compressor is closed at its shutoff");
        assertEquals(298.15,suction.temperature(),1e-6,"the suction bag gives its gas at its own temperature (D54): "+rig.describe());
        assertEquals(101325,suction.pressure(),1e-3,"the suction bag stays at the ambient (D54): "+rig.describe());
        assertEquals(COMPRESSOR_RATIO*suction.pressure(),discharge.pressure(),3,"the compressor holds its ratio limit: "+rig.describe());
        assertTrue(rig.jobs<=20,"a handful of solves: "+rig.describe());
    }
}
