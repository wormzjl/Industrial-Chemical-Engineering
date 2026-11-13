package com.wormzjl.icheme.runtime.fluid;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.diagnostics.SolverDiagnostics;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Finding P-17b (batch 2026-11-13-codebase-review): an island keeps its sparse-LU storages, and with them each column's
 * reach, across its steps and jobs. On one island of unchanged structure solved in one-step jobs by one retained handle,
 * the jobs after the warm-up construct no storage and search no reach while the pivots repeat; and every job's result is
 * bitwise the one a fresh handle gives, which rebuilds every storage and searches every column (nothing numeric crosses
 * from one factorization to the next).
 */
class KeptLuStorageTest {
    /** Nitrogen from a generator through two tanks: two vessels whose closures share one declared pattern. */
    private static PassiveNetwork graph(FluidThermodynamics model) {
        var run=new PipeResistance.Geometry(100,.01,.000045,0);
        return new PassiveNetwork(List.of(
                new PassiveNetwork.Reservoir(1,0,model.initialNitrogenCharge(1,350,150000,()->{}),PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(2,0,model.initialNitrogenCharge(1,350,149500,()->{})),
                new PassiveNetwork.Reservoir(3,0,model.initialNitrogenCharge(1,350,149000,()->{}))),
                List.of(new PassiveNetwork.Pipe(1,0,1,run),new PassiveNetwork.Pipe(2,1,2,run)));
    }
    private static final String[] COUNTERS={"luStorages","luColumnsFactored","luReachSearches","luFactorizations","transportFactorizations","jacobianBuilds","newtonSolves"};
    private static long[] add(long[] sum,SolverDiagnostics.Sample sample){for(int i=0;i<COUNTERS.length;i++)sum[i]+=sample.value(COUNTERS[i]);return sum;}
    private static SolverDiagnostics.Sample measure(Runnable work) {
        boolean enabled=SolverDiagnostics.ENABLED;SolverDiagnostics.reset();SolverDiagnostics.ENABLED=true;
        try{work.run();}finally{SolverDiagnostics.ENABLED=enabled;}
        return SolverDiagnostics.sample();
    }
    private static String line(long[] counts) {
        var text=new StringBuilder();
        for(int i=0;i<COUNTERS.length;i++)text.append(i==0?"":", ").append(COUNTERS[i]).append(' ').append(counts[i]);
        return text.toString();
    }
    private static void assertSameBits(PassiveIntervalSolver.Result expected,PassiveIntervalSolver.Result actual,String job) {
        assertArrayEquals(expected.averageMassFlows(),actual.averageMassFlows(),job+": mass flows");
        assertArrayEquals(expected.endpointFlows(),actual.endpointFlows(),job+": end flows");
        assertEquals(expected.acceptedSubsteps(),actual.acceptedSubsteps(),job+": accepted steps");
        for(int i=0;i<expected.graph().reservoirs().size();i++) {
            var e=expected.graph().reservoirs().get(i);var a=actual.graph().reservoirs().get(i);
            assertEquals(e.inventory(),a.inventory(),job+": node "+e.id()+" inventory");
            assertEquals(Double.doubleToRawLongBits(e.state().pressure()),Double.doubleToRawLongBits(a.state().pressure()),job+": node "+e.id()+" pressure");
            assertEquals(Double.doubleToRawLongBits(e.state().temperature()),Double.doubleToRawLongBits(a.state().temperature()),job+": node "+e.id()+" temperature");
        }
    }

    @Test void aRetainedIslandKeepsItsLuStoragesAndReachesAcrossSteps() {
        var model=FluidTestSupport.networkModel();var settings=PassiveIntervalSolver.Settings.defaults();
        // One-second jobs at a first step of one second: one step a job.
        double duration=settings.initialStep();
        var retained=new RetainedSolver();var next=graph(model);
        // Warm-up: the first jobs build every storage and search every column.
        for(int job=0;job<2;job++)next=retained.solve(model,next,duration,settings,()->{}).graph();
        long[] carried=new long[COUNTERS.length],fresh=new long[COUNTERS.length];
        for(int job=0;job<3;job++) {
            var input=next;var results=new PassiveIntervalSolver.Result[2];
            add(carried,measure(()->results[0]=retained.solve(model,input,duration,settings,()->{})));
            add(fresh,measure(()->results[1]=new RetainedSolver().solve(model,input,duration,settings,()->{})));
            assertSameBits(results[1],results[0],"job "+(job+3));
            next=results[0].graph();
        }
        System.out.printf(Locale.ROOT,"P-17b counters, three one-step jobs, kept handle: %s%nP-17b counters, three one-step jobs, fresh handles: %s%n",line(carried),line(fresh));
        assertTrue(fresh[1]>0,"the jobs factor");
        assertTrue(fresh[0]>0&&fresh[2]>0,"a fresh handle builds its storages and searches their reaches");
        // The same work: the kept storages change which arrays hold a factorization, not which factorizations run.
        for(int i=1;i<COUNTERS.length;i++)if(i!=2)assertEquals(fresh[i],carried[i],COUNTERS[i]+", kept against fresh");
        assertEquals(0,carried[0],"no storage is built after the warm-up on an unchanged structure");
        assertEquals(0,carried[2],"no reach is searched while the pivots repeat ("+carried[1]+" columns factored)");
    }
}
