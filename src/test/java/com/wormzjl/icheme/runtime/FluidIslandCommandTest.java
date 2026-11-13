package com.wormzjl.icheme.runtime;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import static org.junit.jupiter.api.Assertions.*;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import com.wormzjl.icheme.runtime.fluid.*;

class FluidIslandCommandTest {
    /**
     * A job that runs past what was the soft budget is the full solve, on the whole wall budget: the clock advances 1 ns
     * per read, so the old 1 ns soft budget cut this job at its first checkpoint and started the approximate fallback.
     * Owner decision D37 (S2) removed that mode, so the job is the same computation as a solve with no clock at all.
     */
    @Test void aJobPastTheOldSoftBudgetIsTheFullSolveOnTheWholeWallBudget() {
        var model=FluidTestSupport.networkModel();
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,model.initialNitrogenCharge(1,350,150000,()->{}),PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(2,0,model.initialNitrogenCharge(1,350,149000,()->{}))),List.of(new PassiveNetwork.Pipe(1,0,1,new PipeResistance.Geometry(100,.01,.000045,0))));
        graph=new PassiveIntervalSolver(model).solve(graph,.05,PassiveIntervalSolver.Settings.defaults(),()->{}).graph();
        var command=new ProcessSolveJobs.FluidIslandCommand(model,graph,5,PassiveIntervalSolver.Settings.defaults(),1_000_000);
        var clock=new AtomicLong();var result=assertInstanceOf(ProcessSolveJobs.FluidIslandSolveResult.class,command.solve(token(false),clock::getAndIncrement));
        assertEquals("FULL",result.detail());assertTrue(clock.get()>1,"the job read the clock past the old soft budget");
        var reference=new PassiveIntervalSolver(model).solve(graph,5,PassiveIntervalSolver.Settings.defaults(),()->{});
        assertTrue(reference.averageMassFlows()[0]>0);
        assertArrayEquals(reference.averageMassFlows(),result.candidate().orElseThrow().averageMassFlows());
        assertEquals(reference.graph().reservoirs().get(1).inventory(),result.candidate().orElseThrow().graph().reservoirs().get(1).inventory());
    }
    private ProcessSolveJobs.FluidIslandCommand command() {
        var model=FluidTestSupport.networkModel();
        var state=model.initialNitrogenCharge(1,298.15,101325,()->{});
        return new ProcessSolveJobs.FluidIslandCommand(model,new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,state)),List.of()),5,PassiveIntervalSolver.Settings.defaults(),100);
    }
    private BoundedCpuSolveService.CancellationToken token(boolean cancelled) {
        return new BoundedCpuSolveService.CancellationToken(){
            public void throwIfCancellationRequested(){if(cancelled)throw new CancellationException("test cancellation");}
        };
    }
    @Test void localWorkerDeadlineReturnsAHeldCandidateWithoutAdvancingTheSnapshot() {
        var command=command();var time=new AtomicLong();
        var result=assertInstanceOf(ProcessSolveJobs.FluidIslandSolveResult.class,command.solve(token(false),()->time.getAndAdd(100)));
        assertTrue(result.candidate().isEmpty());assertTrue(result.detail().contains("wall deadline"));assertEquals(1,command.snapshot().reservoirs().getFirst().state().volume(),1e-12);
    }
    @Test void cancellationPropagatesAndSuccessfulIdleIntervalsRemainImmutable() {
        var command=command();assertThrows(CancellationException.class,()->command.solve(token(true),()->0));
        var result=assertInstanceOf(ProcessSolveJobs.FluidIslandSolveResult.class,command.solve(token(false),()->0));
        assertEquals(5,result.candidate().orElseThrow().advancedSeconds());assertEquals(command.snapshot(),result.candidate().orElseThrow().graph());
    }
}
