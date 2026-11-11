package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.runtime.BoundedCpuSolveService;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction.EAST;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A worker that fails a slice names its failure in the island's held status (batch 2026-11-11-drum-operation-holds,
 * defect 2): the gas-vent holds read "HELD: round deadline or worker failure" with no exception in the log.
 */
class WorkerFailureStatusTest {
    @Test void aWorkersFailureIsNamedInTheHeldStatus() {
        var w=new EventPathWorld();
        w.place(0,64,0,Kind.GENERATOR,EAST,FluidDeviceSpec.nitrogen(w.catalog),.05);
        w.place(1,64,0,Kind.PIPE,EAST,.05);long sink=w.place(2,64,0,Kind.VOID,EAST,FluidDeviceSpec.nitrogen(w.catalog),.05);
        w.runTo(40);
        Long island=w.registry.owner(sink);assertNotNull(island);
        w.failing.add(island);w.failure="worker failure: ArithmeticException: / by zero";
        w.runTo(400);
        var status=w.coordinator.presentation(island).status();
        assertEquals("HELD: worker failure: ArithmeticException: / by zero",status);
    }
    @Test void theRuntimeNamesTheExceptionAndOtherwiseTheTerminalStatus() {
        var stamp=new BoundedCpuSolveService.JobStamp<>(1,1,"island",0,"r1",0);
        var failed=new BoundedCpuSolveService.Completion<>(stamp,BoundedCpuSolveService.TerminalStatus.FAILED,Optional.empty(),
                Optional.of(new BoundedCpuSolveService.Failure("java.lang.ArithmeticException","/ by\n zero",List.of())),"",0,0,0);
        assertEquals("worker failure: ArithmeticException: / by zero",MinecraftFluidRuntime.failure(7,failed));
        var cancelled=new BoundedCpuSolveService.Completion<>(stamp,BoundedCpuSolveService.TerminalStatus.DEADLINE_EXCEEDED,Optional.empty(),Optional.empty(),"",0,0,0);
        assertEquals("worker deadline exceeded",MinecraftFluidRuntime.failure(7,cancelled));
    }
}
