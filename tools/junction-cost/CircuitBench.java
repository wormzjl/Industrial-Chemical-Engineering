package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.network.*;
import java.lang.management.ManagementFactory;
import java.util.List;

public class CircuitBench {
    static final Runnable CHECK=()->{};
    static final com.sun.management.ThreadMXBean threads=ManagementFactory.getPlatformMXBean(com.sun.management.ThreadMXBean.class);
    static final com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics model=FluidTestSupport.networkModel();
    static final double[] methane=new double[model.molecularWeights().length];
    static double injectionEnergy;
    static int accepted, rejected, intervals;
    static void run(boolean gas) {
        for(double bore:new double[]{.02,.05})for(int ports=gas?4:0;ports<=(gas?6:0);ports++)for(boolean variation:new boolean[]{false,true}) {
            var graph=gas?MixedGasJunctionTransientTest.island(model,bore,ports,variation):LiquidJunctionTransientTest.island(model,variation,bore);
            var solver=new PassiveIntervalSolver(model);
            PassiveIntervalSolver.Result previous=null;
            int tank=0;
            if(gas)while(graph.reservoirs().get(tank).id()!=1)tank++;
            for(int i=0;i<4;i++) {
                double dt=gas&&i>=2?3:5;
                if(gas)graph=new PassiveNetwork(graph.reservoirs(),graph.pipes(), i==2?
                    List.of(new ScheduledTransfer.Injection(900,tank,methane,injectionEnergy)):List.of());
                solver.replayStart(graph,previous);
                previous=solver.solve(graph,dt,PassiveIntervalSolver.Settings.defaults(),CHECK);
                if(previous.advancedSeconds()!=dt)throw new IllegalStateException("Partial interval");
                graph=previous.graph();
                intervals++; accepted+=previous.acceptedSubsteps(); rejected+=previous.rejectedSubsteps();
            }
        }
    }
    public static void main(String[] args) {
        methane[0]=.5;
        injectionEnergy=model.flashTP(350,150000,methane,CHECK).enthalpy();
        for(int round=-6;round<12;round++)for(boolean gas:new boolean[]{false,true}) {
            accepted=0;rejected=0;intervals=0;
            long startCpu=threads.getCurrentThreadCpuTime(),startMem=threads.getCurrentThreadAllocatedBytes();
            run(gas);
            long bytes=threads.getCurrentThreadAllocatedBytes()-startMem,cpu=threads.getCurrentThreadCpuTime()-startCpu;
            if(round>=0)System.out.printf("CIRCUIT,%d,%s,%d,%d,%d,%d,%d%n",round,gas?"gas":"liquid",cpu,bytes,intervals,accepted,rejected);
        }
    }
}
