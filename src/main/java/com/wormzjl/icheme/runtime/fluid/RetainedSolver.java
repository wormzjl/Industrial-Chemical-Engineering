package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.fluid.SolverOwnership;
import com.wormzjl.icheme.science.fluid.network.PassiveIntervalSolver;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import java.util.Objects;

/**
 * One interval solver kept for one island across its jobs, so the sparsity pattern, the colouring and the
 * fill-reducing ordering survive between intervals instead of being rebuilt per job.
 *
 * <p>Nothing numeric crosses a job boundary but the committed interval: at the start of every job the solver is reset to
 * what a fresh solver would derive from the island's committed interval (its first step starts from the interval's end
 * flows, heads and modes) ({@link PassiveIntervalSolver#replayStart}; the coordinator hands the
 * committed interval over at dispatch, {@link #committed}), and an ordinary interval starts at
 * {@code min(initialStep, duration)}, a function of the interval alone. A certified island is woken with a new handle,
 * and its replay must reproduce the island that solved every interval bit for bit; a factorization or a step estimate
 * carried from an earlier job made them differ in the last digits (HANDOFF_REVIEW.md 8.9 of the mixed-gas junction
 * batch). The cost is one fresh Jacobian per job, +8 % wall time at 5 s slices.
 *
 * <p>This is ephemeral cost state, never material state: dropping it costs one Jacobian build and a
 * few substeps and changes nothing that is committed. The coordinator replaces the handle whenever
 * an island's revision changes (property suspension, topology repartition), and a new island gets a
 * new handle, so a stale model or topology can never reach a retained workspace.
 *
 * <p>Exclusive use is enforced by {@link SolverOwnership}: each call claims the latch and returns it
 * in a {@code finally}, including on cancellation and on the wall deadline, and a second
 * concurrent job on the same island fails fast instead of corrupting the cached factorization.
 * Workers therefore still touch only the immutable snapshot and this handle.
 */
public final class RetainedSolver {
    private final SolverOwnership ownership=SolverOwnership.released();
    private PassiveIntervalSolver solver;
    private FluidThermodynamics model;
    /** The island's committed interval (null for none), handed over at each dispatch. */
    private PassiveIntervalSolver.Result committed;
    /** Records the island's committed interval (null for none) for the next job's start. Called by the coordinator when
     * it dispatches, while no job holds this handle. */
    public void committed(PassiveIntervalSolver.Result result){this.committed=result;}

    /** One job's exclusive use of this island's solver: claims the latch and returns it in a {@code finally}, including
     * on cancellation and on the wall deadline. Every job starts from the committed interval
     * ({@link PassiveIntervalSolver#replayStart}). */
    public PassiveIntervalSolver.Result solve(FluidThermodynamics model,PassiveNetwork snapshot,double duration,
                                              PassiveIntervalSolver.Settings settings,Runnable checkpoint) {
        return solve(model,snapshot,duration,settings,checkpoint,java.util.Map.of());
    }
    /** {@code reads}: each coupled exchanger side's read of its other side, by connection id (plan section 5.2). */
    public PassiveIntervalSolver.Result solve(FluidThermodynamics model,PassiveNetwork snapshot,double duration,
                                              PassiveIntervalSolver.Settings settings,Runnable checkpoint,
                                              java.util.Map<Long,com.wormzjl.icheme.science.fluid.network.ExchangerRelay.Read> reads) {
        return solve(model,snapshot,duration,settings,checkpoint,reads,java.util.Map.of());
    }
    /** {@code ports}: each running column's port planned for the slice (batch 2026-10-28-column-operation). */
    public PassiveIntervalSolver.Result solve(FluidThermodynamics model,PassiveNetwork snapshot,double duration,
                                              PassiveIntervalSolver.Settings settings,Runnable checkpoint,
                                              java.util.Map<Long,com.wormzjl.icheme.science.fluid.network.ExchangerRelay.Read> reads,
                                              java.util.Map<Long,com.wormzjl.icheme.science.fluid.network.PortPlan> ports) {
        var solver=acquire(model);
        try {
            solver.replayStart(snapshot,committed);
            return solver.solve(snapshot,duration,settings,checkpoint,Math.min(settings.initialStep(),duration),reads,ports);
        }
        finally{ownership.release();}
    }
    private PassiveIntervalSolver acquire(FluidThermodynamics model) {
        Objects.requireNonNull(model);ownership.acquire();
        try {
            if(solver==null||this.model!=model){solver=new PassiveIntervalSolver(model,ownership);this.model=model;}
            return solver;
        }catch(RuntimeException|Error failure){ownership.release();throw failure;}
    }
}
