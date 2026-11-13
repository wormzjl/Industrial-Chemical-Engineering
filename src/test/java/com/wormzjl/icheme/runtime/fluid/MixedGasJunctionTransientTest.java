package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.diagnostics.SolverDiagnostics;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.icheme.science.material.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The twelve finite-tank mixed-gas junction transients (documentation/2026-09-24-mixed-gas-junction): two 1 m3 tanks
 * (methane at 150 kPa; nitrogen at 150 kPa, or 90 kPa and 400 K) feed a compiled junction with 2 to 4 void outlets, 50 or
 * 20 mm bore. 10 s through the approach to rest, 3 s of 0.5 mol/s methane injected into the first tank (a restart), 3 s of
 * settling. Every case must converge at the probe's 0.1 s intervals and at the product's 5 s cycle, and every committed
 * interval must close the ledger (owner decision D46: the tanks' change equals the boundary transfers plus the junction
 * term, the change of the junction's buffer) at roundoff, the term within its bound, m_J ({@link JunctionLedger}). Each
 * interval is a job as the island runtime runs it: {@link PassiveIntervalSolver#replayStart} from the committed interval,
 * then the settings' initial step. Formerly JunctionHoldupTransientProbe (tools/junction-holdup-prototype); the TR-BDF2
 * chain failed the 50 mm six-port restart.
 */
class MixedGasJunctionTransientTest {
    private final FluidThermodynamics model=FluidTestSupport.networkModel();
    private final double[] mw=model.molecularWeights();
    /** Newton solves per case at the 5 s cycle in HANDOFF_REVIEW.md 8.9 run 115 (281 in all), keyed bore:ports:unequal. */
    private static final Map<String,Integer> RUN_115_NEWTON_SOLVES=Map.ofEntries(
            Map.entry("0.05:4:false",26),Map.entry("0.05:4:true",27),Map.entry("0.05:5:false",30),Map.entry("0.05:5:true",29),
            Map.entry("0.05:6:false",32),Map.entry("0.05:6:true",31),Map.entry("0.02:4:false",16),Map.entry("0.02:4:true",16),
            Map.entry("0.02:5:false",18),Map.entry("0.02:5:true",17),Map.entry("0.02:6:false",20),Map.entry("0.02:6:true",19));
    /** The ledger closes by construction (exact reconstruction); these bounds are roundoff, relative to max(1, initial)
     * (measured at most 5.4e-15 over both cadences). */
    private static final double COMPONENT_LEDGER=1e-12,ENERGY_LEDGER=1e-12;
    /** {@code junctionShare}: the junction term's largest component mass over m_J (D46's bound is 1). */
    private record Outcome(String key,double worstMoles,double worstEnergy,double junctionShare,long newtonSolves,long ms,long bytes) {}

    @Test void everyCaseConvergesAndClosesItsLedgerAtTenthSecondIntervals() {
        var failures=new ArrayList<String>();
        for(var outcome:runAll(.1,false,failures))System.out.println("MIXED_GAS_TRANSIENT interval=0.1 "+outcome);
        assertTrue(failures.isEmpty(),failures.size()+" of 12 cases failed at 0.1 s:\n"+String.join("\n",failures));
    }
    @Test void everyCaseConvergesAtTheFiveSecondCycleWithinTwiceTheMeasuredCost() {
        var failures=new ArrayList<String>();long ms=0,solves=0,bytes=0;
        for(var outcome:runAll(5,true,failures)) {
            System.out.println("MIXED_GAS_TRANSIENT interval=5 "+outcome);ms+=outcome.ms();solves+=outcome.newtonSolves();bytes+=outcome.bytes();
            int bound=2*RUN_115_NEWTON_SOLVES.get(outcome.key());
            if(outcome.newtonSolves()>=bound)failures.add(outcome.key()+": "+outcome.newtonSolves()+" Newton solves, bound "+bound);
        }
        System.out.println("MIXED_GAS_COST interval=5 cases=12 ms="+ms+" newtonSolves="+solves+" allocatedMB="+String.format(Locale.ROOT,"%.1f",bytes/1048576.0));
        assertTrue(failures.isEmpty(),failures.size()+" failures at 5 s:\n"+String.join("\n",failures));
    }

    private List<Outcome> runAll(double interval,boolean cost,List<String> failures) {
        var outcomes=new ArrayList<Outcome>();
        for(double bore:new double[]{.05,.02})for(int ports=4;ports<=6;ports++)for(boolean unequal:new boolean[]{false,true}) {
            String key=bore+":"+ports+":"+unequal;
            boolean enabled=SolverDiagnostics.ENABLED;
            if(cost){SolverDiagnostics.reset();SolverDiagnostics.ENABLED=true;}
            long bytes=threadAllocated(),start=System.nanoTime();
            try{
                var ledger=runCase(bore,ports,unequal,interval);
                long elapsed=(System.nanoTime()-start)/1000000;bytes=threadAllocated()-bytes;
                outcomes.add(new Outcome(key,ledger[0],ledger[1],ledger[2],cost?SolverDiagnostics.sample().value("newtonSolves"):0,elapsed,bytes));
            }catch(Exception|AssertionError failure){failures.add(key+": "+failure);}
            finally{if(cost){SolverDiagnostics.ENABLED=enabled;SolverDiagnostics.reset();}}
        }
        return outcomes;
    }
    private static long threadAllocated() {
        return java.lang.management.ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean sun?sun.getCurrentThreadAllocatedBytes():0;
    }
    /** Runs one case and returns its worst relative component and energy ledger errors and its junction share. */
    private double[] runCase(double bore,int ports,boolean unequal,double interval){
        var graph=island(model,bore,ports,unequal);
        var ledger=new JunctionLedger(graph,mw);
        int tank=-1;for(int i=0;i<graph.reservoirs().size();i++)if(graph.reservoirs().get(i).id()==1)tank=i;
        // 0.5 mol/s methane at 350 K and 150 kPa, carrying its enthalpy.
        double[] feed=new double[mw.length];feed[0]=.5;
        var feedState=model.flashTP(350,150000,feed,()->{});
        double feedEnergy=feedState.enthalpy();
        var solver=new PassiveIntervalSolver(model);
        PassiveIntervalSolver.Result committed=null;
        var schedule=schedule(interval);
        for(int step=0;step<schedule.size();step++){
            List<ScheduledTransfer> transfers=schedule.get(step)[1]>0?List.of(new ScheduledTransfer.Injection(900,tank,feed,feedEnergy)):List.of();
            graph=new PassiveNetwork(graph.reservoirs(),graph.pipes(),transfers);
            solver.replayStart(graph,committed);
            var result=solver.solve(graph,schedule.get(step)[0],PassiveIntervalSolver.Settings.defaults(),()->{});committed=result;
            graph=result.graph();ledger.add(result);
            assertTrue(ledger.worstMoles<COMPONENT_LEDGER,"interval "+step+": component balance error "+ledger.worstMoles);
            assertTrue(ledger.worstEnergy<ENERGY_LEDGER,"interval "+step+": energy balance "+ledger.worstEnergy);
            assertTrue(ledger.worstShare<=1,"interval "+step+": the junction term exceeds m_J: "+ledger.worstShare);
        }
        return new double[]{ledger.worstMoles,ledger.worstEnergy,ledger.worstShare};
    }
    /**
     * The schedule (10 s rest, 3 s injection, 3 s settle) cut into intervals of the given length, a segment's last
     * interval shortened to its end (5 s: 5, 5, 3, 3): per interval its length and 1 while methane is injected.
     */
    static List<double[]> schedule(double interval) {
        var schedule=new ArrayList<double[]>();
        for(double[] segment:new double[][]{{10,0},{3,1},{3,0}}){
            double length=segment[0];long whole=Math.round(length/interval);
            if(Math.abs(whole*interval-length)<=1e-9*length){for(long k=0;k<whole;k++)schedule.add(new double[]{interval,segment[1]});}
            else{long full=(long)Math.floor(length/interval);for(long k=0;k<full;k++)schedule.add(new double[]{interval,segment[1]});schedule.add(new double[]{length-full*interval,segment[1]});}
        }
        return schedule;
    }
    /** One case's island: the two tanks (ids 1 and 2) and the voids around the compiled junction, sized as the solver would
     * size it at its first solve. */
    static PassiveNetwork island(FluidThermodynamics model,double bore,int ports,boolean unequal) {
        var mw=model.molecularWeights();
        int[][] positions={{-1,0,0},{1,0,0},{0,0,-1},{0,0,1},{0,1,0},{0,-1,0}};
        var geometry=new PipeResistance.Geometry(1,bore,PipeResistance.DEFAULT_ROUGHNESS_METRES,0);
        var devices=new ArrayList<PhysicalFluidTopology.Device>();
        devices.add(PhysicalFluidTopology.Device.builder(100,new PhysicalFluidTopology.Position("minecraft:overworld",0,0,0),Kind.PIPE,PhysicalFluidTopology.Direction.EAST,geometry,new FlowControl.Passive()).build());
        var boundaries=new HashMap<Long,PassiveNetwork.Reservoir>();
        for(int i=0;i<ports;i++){
            int[] at=positions[i];var kind=i<2?Kind.RESERVOIR:Kind.VOID;
            var d=PhysicalFluidTopology.Device.builder(i+1,new PhysicalFluidTopology.Position("minecraft:overworld",at[0],at[1],at[2]),kind,PhysicalFluidTopology.Direction.EAST,geometry,new FlowControl.Passive()).build();devices.add(d);
            double pressure=i==0?150000:i==1?(unequal?90000:150000):101325;
            double temperature=i==1&&unequal?400:350;
            double[] n=new double[mw.length];n[i==0?0:MaterialTestBasis.NITROGEN]=1;
            var unit=model.flashTP(temperature,pressure,n,()->{});for(int c=0;c<n.length;c++)n[c]/=unit.volume();
            var state=model.flashTP(temperature,pressure,n,()->{});
            boundaries.put(d.id(),new PassiveNetwork.Reservoir(d.id(),at[1],state,i<2?PassiveNetwork.NodeKind.RESERVOIR:PassiveNetwork.NodeKind.VOID));
        }
        // Sized here, as the solver would at its first solve, so the ledger starts from the junction's buffer.
        return PassiveNetwork.sizeJunctionHoldups(PhysicalFluidTopology.compile(devices,boundaries).islands().getFirst().graph(),model);
    }
}
