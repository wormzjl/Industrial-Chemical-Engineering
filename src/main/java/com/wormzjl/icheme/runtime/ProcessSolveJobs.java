package com.wormzjl.icheme.runtime;

import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.network.PassiveIntervalSolver;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The worker side of the shared pool's protocol: the command each job family runs and the result it returns (a fluid
 * island's slice, a running column unit's solve, a batch still's column solve, a dimension's pollution pass). No Minecraft type: the targets,
 * requests and completions that bind a job to a world stay in the server-thread adapter {@link ProcessSolveServices}.
 * Moved from {@code ProcessSolveServices} unchanged (codebase review R13); in memory only, never saved or sent.
 */
public final class ProcessSolveJobs {
    private ProcessSolveJobs() {}

    /** Immutable worker envelope. Adding a job family extends this sealed protocol, never the executor count. */
    public sealed interface ProcessSolveCommand permits FluidIslandCommand,ColumnUnitCommand,StillCommand,PollutionCommand {
        ProcessSolveResult solve(BoundedCpuSolveService.CancellationToken cancellationToken);
    }

    /** Immutable worker result envelope routed only after main-thread completion draining. */
    public sealed interface ProcessSolveResult permits FluidIslandSolveResult,ColumnUnitSolveResult,StillSolveResult,PollutionSolveResult {}

    /** Contains no live level, block entity, callback, or mutable server-owned collection. {@code reads}: each coupled
     * exchanger side's read of its other side, by connection id, made on the server at the dispatch (plan section 5.2). */
    public record FluidIslandCommand(FluidThermodynamics model,PassiveNetwork snapshot,double durationSeconds,
                                     PassiveIntervalSolver.Settings settings,long wallBudgetNanos,
                                     com.wormzjl.icheme.runtime.fluid.RetainedSolver retained,
                                     Map<Long,com.wormzjl.icheme.science.fluid.network.ExchangerRelay.Read> reads,
                                     Map<Long,com.wormzjl.icheme.science.fluid.network.PortPlan> ports) implements ProcessSolveCommand {
        /** No running column's port in the island. */
        public FluidIslandCommand(FluidThermodynamics model,PassiveNetwork snapshot,double durationSeconds,PassiveIntervalSolver.Settings settings,long wallBudgetNanos,
                                  com.wormzjl.icheme.runtime.fluid.RetainedSolver retained,Map<Long,com.wormzjl.icheme.science.fluid.network.ExchangerRelay.Read> reads) {
            this(model,snapshot,durationSeconds,settings,wallBudgetNanos,retained,reads,Map.of());
        }
        /** Without a retained handle the job gets its own, which is the per-job solver of before. */
        public FluidIslandCommand(FluidThermodynamics model,PassiveNetwork snapshot,double durationSeconds,PassiveIntervalSolver.Settings settings,long wallBudgetNanos) {
            this(model,snapshot,durationSeconds,settings,wallBudgetNanos,new com.wormzjl.icheme.runtime.fluid.RetainedSolver());
        }
        /** No coupled exchanger side read. */
        public FluidIslandCommand(FluidThermodynamics model,PassiveNetwork snapshot,double durationSeconds,PassiveIntervalSolver.Settings settings,long wallBudgetNanos,com.wormzjl.icheme.runtime.fluid.RetainedSolver retained) {
            this(model,snapshot,durationSeconds,settings,wallBudgetNanos,retained,Map.of());
        }
        public FluidIslandCommand {
            Objects.requireNonNull(model);Objects.requireNonNull(snapshot);Objects.requireNonNull(settings);Objects.requireNonNull(retained);reads=Map.copyOf(reads);ports=Map.copyOf(ports);
            if(!Double.isFinite(durationSeconds)||durationSeconds<=0||wallBudgetNanos<=0)throw new IllegalArgumentException("Invalid island solve budget");
        }
        @Override public ProcessSolveResult solve(BoundedCpuSolveService.CancellationToken cancellationToken) {
            long cpuStart=WorkerAllocation.CpuTime.sample();
            var result=(FluidIslandSolveResult)solve(cancellationToken,System::nanoTime);
            long cpu=WorkerAllocation.CpuTime.elapsed(cpuStart,WorkerAllocation.CpuTime.sample());
            return new FluidIslandSolveResult(result.candidate(),result.detail(),result.workerNanos(),cpu,result.phaseHold(),result.notices());
        }
        /**
         * The full solve, on the whole wall budget. Owner decision D37 (S2) removed the approximate fallback a soft budget
         * used to start: it refused every interval it was tried on (M5), and the island was held and retried with the
         * full solve alone on the whole budget, which is now every attempt.
         */
        ProcessSolveResult solve(BoundedCpuSolveService.CancellationToken cancellationToken,java.util.function.LongSupplier nanoClock) {
            Objects.requireNonNull(cancellationToken);Objects.requireNonNull(nanoClock);long start=nanoClock.getAsLong();
            long[] elapsed={0};
            Runnable hard=()->{cancellationToken.throwIfCancellationRequested();elapsed[0]=nanoClock.getAsLong()-start;if(elapsed[0]>=wallBudgetNanos)throw new FluidWallDeadline();};
            try {
                var candidate=retained.solve(model,snapshot,durationSeconds,settings,hard,reads,ports);hard.run();
                return new FluidIslandSolveResult(Optional.of(candidate),"FULL",elapsed[0],-1,Optional.empty(),notices(model,candidate));
            }catch(FluidWallDeadline deadline){return new FluidIslandSolveResult(Optional.empty(),WALL_DEADLINE,elapsed[0]);}
            catch(com.wormzjl.icheme.science.fluid.solver.SparseNewton.Nonconvergence|IllegalArgumentException failed) {
                cancellationToken.throwIfCancellationRequested();
                // A failure a phase hold decided (a phase set the network cannot carry, or the critical band) carries the
                // hold itself, so the coordinator can hold the island with the dedicated status and tell a reproduced hold
                // from a numerical failure (IslandCoordinator.hold).
                var hold=Optional.ofNullable(com.wormzjl.icheme.science.fluid.solver.SparseNewton.phaseHold(failed));
                return new FluidIslandSolveResult(Optional.empty(),hold.map(v->PHASE_HOLD+v.getMessage()).orElse("HELD: "+failed.getMessage()),elapsed[0],-1,hold);
            }
        }
    }
    /**
     * The data ranges an accepted interval's end state lies outside, node by node in the network's order (owner rule of
     * 2026-10-16, RANGE_EXTRAPOLATION_PLAN section 5): read once per committed interval, here in the worker, and published
     * with the interval's status line. A vessel that holds nothing keeps the ambient as its state and is skipped.
     */
    static List<com.wormzjl.icheme.science.fluid.thermo.DataRangeNotices.Notice> notices(FluidThermodynamics model,PassiveIntervalSolver.Result candidate) {
        var notices=new com.wormzjl.icheme.science.fluid.thermo.DataRangeNotices();
        for(var reservoir:candidate.graph().reservoirs())if(!reservoir.empty())model.dataRanges(reservoir.state(),reservoir.id(),notices);
        return notices.notices();
    }
    /** The detail of an island interval the hard wall budget cut. */
    public static final String WALL_DEADLINE="HELD: wall deadline";
    /** The detail prefix of an island interval a phase hold refused; the result carries the hold. */
    public static final String PHASE_HOLD="HELD (phases): ";
    private static final class FluidWallDeadline extends RuntimeException {}
    public record FluidIslandSolveResult(Optional<PassiveIntervalSolver.Result> candidate,String detail,long workerNanos,long workerCpuNanos,
                                        Optional<com.wormzjl.icheme.science.fluid.thermo.ThermoHold> phaseHold,
                                        List<com.wormzjl.icheme.science.fluid.thermo.DataRangeNotices.Notice> notices) implements ProcessSolveResult {
        public FluidIslandSolveResult(Optional<PassiveIntervalSolver.Result> candidate,String detail,long workerNanos,long workerCpuNanos,
                                      Optional<com.wormzjl.icheme.science.fluid.thermo.ThermoHold> phaseHold) {
            this(candidate,detail,workerNanos,workerCpuNanos,phaseHold,List.of());
        }
        public FluidIslandSolveResult(Optional<PassiveIntervalSolver.Result> candidate,String detail,long workerNanos,long workerCpuNanos) {
            this(candidate,detail,workerNanos,workerCpuNanos,Optional.empty());
        }
        public FluidIslandSolveResult(Optional<PassiveIntervalSolver.Result> candidate,String detail,long workerNanos) {
            this(candidate,detail,workerNanos,-1);
        }
        /** {@code notices}: the data ranges the accepted interval's end state lies outside, empty for a held one. */
        public FluidIslandSolveResult {Objects.requireNonNull(phaseHold);notices=List.copyOf(notices);if(!notices.isEmpty()&&candidate.isEmpty())throw new IllegalArgumentException("Only an accepted interval carries data-range notices");if(phaseHold.isPresent()&&candidate.isPresent())throw new IllegalArgumentException("An accepted interval has no phase hold");Objects.requireNonNull(candidate);Objects.requireNonNull(detail);if(workerNanos<0||workerCpuNanos < -1)throw new IllegalArgumentException("Invalid worker elapsed time");}
    }
    /** A column unit's solve: the job and its feed island's model (its compound list's view, which V3 reads too), read on the server at admission. */
    public record ColumnUnitCommand(com.wormzjl.icheme.science.plant.column.ColumnSolve.Job job,FluidThermodynamics model,double liquidSupplyScreenRatio) implements ProcessSolveCommand {
        public ColumnUnitCommand {Objects.requireNonNull(job);Objects.requireNonNull(model);}
        @Override public ProcessSolveResult solve(BoundedCpuSolveService.CancellationToken cancellationToken) {
            return new ColumnUnitSolveResult(com.wormzjl.icheme.science.plant.column.ColumnSolve.run(job,model,liquidSupplyScreenRatio,cancellationToken::throwIfCancellationRequested));
        }
    }
    public record ColumnUnitSolveResult(com.wormzjl.icheme.science.plant.column.ColumnSolve.Outcome outcome) implements ProcessSolveResult {
        public ColumnUnitSolveResult {Objects.requireNonNull(outcome);}
    }
    /** A still's column solve: the job and its pot island's model, read on the server at admission. */
    public record StillCommand(com.wormzjl.icheme.science.plant.still.StillHead.Job job,FluidThermodynamics model) implements ProcessSolveCommand {
        public StillCommand {Objects.requireNonNull(job);Objects.requireNonNull(model);}
        @Override public ProcessSolveResult solve(BoundedCpuSolveService.CancellationToken cancellationToken) {
            return new StillSolveResult(com.wormzjl.icheme.science.plant.still.StillHead.run(job,model,cancellationToken::throwIfCancellationRequested));
        }
    }
    public record StillSolveResult(com.wormzjl.icheme.science.plant.still.StillHead.Outcome outcome) implements ProcessSolveResult {
        public StillSolveResult {Objects.requireNonNull(outcome);}
    }
    /**
     * One pass of a dimension's pollution field (batch 2026-11-15-pollution-system, WP3): the step over an immutable
     * snapshot taken on the server. A step that throws (a non-finite amount, a bad snapshot) is a failed result, so the
     * service keeps its last field; a cancellation still ends the job as cancelled.
     */
    public record PollutionCommand(com.wormzjl.icheme.runtime.pollution.PollutionEngine.Pass pass) implements ProcessSolveCommand {
        public PollutionCommand {Objects.requireNonNull(pass);}
        @Override public ProcessSolveResult solve(BoundedCpuSolveService.CancellationToken cancellationToken) {
            cancellationToken.throwIfCancellationRequested();
            try{return new PollutionSolveResult(Optional.of(pass.run()),"");}
            catch(RuntimeException failed){
                cancellationToken.throwIfCancellationRequested();
                return new PollutionSolveResult(Optional.empty(),failed.getClass().getSimpleName()+": "+failed.getMessage());
            }
        }
    }
    /** {@code result}: the next field and ledger, empty when the step failed; {@code detail} names the failure. */
    public record PollutionSolveResult(Optional<com.wormzjl.icheme.runtime.pollution.PollutionEngine.Outcome> result,String detail) implements ProcessSolveResult {
        public PollutionSolveResult {Objects.requireNonNull(result);Objects.requireNonNull(detail);}
    }
}
