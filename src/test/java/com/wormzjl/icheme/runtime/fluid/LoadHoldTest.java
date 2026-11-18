package com.wormzjl.icheme.runtime.fluid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.diagnostics.SolverDiagnostics;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.network.PassiveIntervalSolver;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * A drive's speed step keeps the committed start (batch 2026-11-16-review-plant-sizing, LOAD_HOLD_REVIEW.md, option A). The
 * review world's crude island held while its flow-regulating drive stepped P-101's speed: the speed is part of the pump's
 * connection, and a job whose connections differ from the committed interval's started from the balance, at 100 to 500 times
 * the cost of a carried start. Here StartHoldTest's charge line runs with its valve regulating and the pump on its law; one
 * slice is solved from the committed interval at the pump's speed times 1.001 (the shaft deadband), 1.01 and 1.2, and from a
 * fresh solver at the same speed: the carried solve continues (few residual evaluations) and lands on the fresh solve's flows
 * and pressures.
 */
class LoadHoldTest {
    @Test void aDrivesSpeedStepStartsFromTheCommittedInterval() {
        var w=new EventPathWorld();long valve=StartHoldTest.line(w,150000,250000,StartHoldTest.STRONG);w.runTo(800);
        LevelLoopBlanketStartTest.edit(w,valve,d->d.withControl(StartHoldTest.inlet(1.05*StartHoldTest.DESIGN,0)),s->s);
        w.runTo(1600);
        assertTrue(w.held.isEmpty(),"holds "+w.held);
        var island=StartHoldTest.island(w);var last=island.lastResult().orElseThrow();var g=w.worldGraph(island);
        int p=StartHoldTest.edge(g,FlowControl.Machine.class),v=StartHoldTest.edge(g,FlowControl.ControlValve.class);
        assertEquals(FlowControl.Mode.VALVE_REGULATING,last.endpointModes().get(v));
        assertEquals(FlowControl.Mode.MOVER_LAW,last.endpointModes().get(p));
        var model=FluidTestSupport.networkModel();var settings=PassiveIntervalSolver.Settings.defaults();
        var text=new StringBuilder();
        for(double factor:new double[]{1.001,1.01,1.2}) {
            var pipes=new ArrayList<PassiveNetwork.Pipe>();
            for(var q:g.pipes())pipes.add(q.control() instanceof FlowControl.Machine m?q.withControl(m.withSpeed(m.speed()*factor)):q);
            var stepped=new PassiveNetwork(g.reservoirs(),List.copyOf(pipes),g.scheduledTransfers());
            long[] cost=new long[2];PassiveIntervalSolver.Result[] r=new PassiveIntervalSolver.Result[2];
            for(int k=0;k<2;k++) {
                var solver=new RetainedSolver();if(k==0)solver.committed(last);
                SolverDiagnostics.reset();SolverDiagnostics.ENABLED=true;
                try{r[k]=solver.solve(model,stepped,1,settings,()->{});}finally{SolverDiagnostics.ENABLED=false;}
                cost[k]=SolverDiagnostics.residualEvaluations.sum();
            }
            text.append(String.format(Locale.ROOT," x%.3f: carried %d, fresh %d residual evaluations, valve %.4f kg/s;",factor,cost[0],cost[1],r[0].averageMassFlows()[v]));
            if(factor==1.001)assertTrue(cost[0]<60,"x1.001 carried: "+cost[0]+" residual evaluations");
            assertTrue(cost[0]<cost[1],"x"+factor+": the carried start costs less than the fresh one ("+cost[0]+" against "+cost[1]+")");
            for(int e=0;e<g.pipes().size();e++)
                assertEquals(r[1].averageMassFlows()[e],r[0].averageMassFlows()[e],1e-6*Math.max(1,Math.abs(r[1].averageMassFlows()[e])),"x"+factor+": flow of connection "+e);
            assertEquals(r[1].endpointModes(),r[0].endpointModes(),"x"+factor+": modes");
            for(int n=0;n<g.reservoirs().size();n++)
                assertEquals(r[1].graph().reservoirs().get(n).state().pressure(),r[0].graph().reservoirs().get(n).state().pressure(),1,"x"+factor+": pressure of node "+n);
        }
        System.out.println("LoadHoldTest:"+text);
    }
}
