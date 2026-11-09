package com.wormzjl.icheme.runtime;

import com.wormzjl.icheme.IChemE;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.world.level.Level;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.network.PassiveIntervalSolver;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;

import java.time.Duration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * NeoForge lifecycle adapter for the pure, bounded CPU solve service.
 *
 * <p>Every method is server-thread confined. The worker receives only a deeply immutable
 * {@link ProcessSolveCommand}; Minecraft objects and request-delivery metadata stay in the server-thread context
 * map. V3 jobs enter the one owned bounded service through this sealed envelope.</p>
 */
public final class ProcessSolveServices {
    public static final int MAXIMUM_COMPLETIONS_PER_TICK = 64;

    private static final AtomicLong SERVER_EPOCH_SEQUENCE = new AtomicLong();
    private static final AtomicLong PROCESS_REQUEST_SEQUENCE = new AtomicLong();
    private static final Map<MinecraftServer, ServerState> STATES = new IdentityHashMap<>();
    private ProcessSolveServices() {}

    /**
     * Returns the only process-wide request identity used by calculator requests.
     *
     * <p>The value is deliberately not reset with a logical server: this keeps stale packet/log identities
     * unambiguous across an integrated-server restart in the same JVM. It is allocated only on the logical server
     * thread by the network admission boundary.</p>
     */
    public static long nextRequestId() {
        return PROCESS_REQUEST_SEQUENCE.updateAndGet(previous -> {
            if (previous == Long.MAX_VALUE) {
                throw new IllegalStateException("Process-solve request sequence exhausted");
            }
            return previous + 1L;
        });
    }

    /** Creates exactly one owned solve service for a starting logical server. */
    public static ServerStarted startServer(MinecraftServer server, Config config) {
        requireServerThread(server);
        Objects.requireNonNull(config, "config");
        if (STATES.containsKey(server)) {
            throw new IllegalStateException("Process solve service already exists for this server");
        }
        long epoch = SERVER_EPOCH_SEQUENCE.incrementAndGet();
        var wakeup=new CompletionWakeup(action->server.tell(new TickTask(0,action)),()->{
            var current=STATES.get(server);
            if(current==null||current.stopResult!=null)return;
            com.wormzjl.icheme.network.ProcessSolveCoordinator.drainCompletedCalculations(server);
        });
        BoundedCpuSolveService<SolveTarget, ProcessSolveCommand, ProcessSolveResult> service =
                new BoundedCpuSolveService<>(epoch, new BoundedCpuSolveService.Config(
                        config.workerCount(),
                        config.readyCapacity(),
                        "icheme-cpu-solve-",
                        true,
                        config.gracefulShutdown(),
                        config.forcedShutdown()),wakeup::signal);
        STATES.put(server, new ServerState(service, config,wakeup));
        if(config.dynamicWorkers())service.setWorkerLimit(1);
        BoundedCpuSolveService.Diagnostics diagnostics = service.diagnostics();
        return new ServerStarted(
                epoch,
                diagnostics.workerCount(),
                diagnostics.readyCapacity(),
                config.solveDeadline().toMillis(),
                config.gracefulShutdown().toMillis(),
                config.forcedShutdown().toMillis());
    }

    /** A running column's solve (batch 2026-10-28-column-operation, plan section 6): a V3 job under the solve deadline. */
    public static AdmissionResult submitColumnUnit(MinecraftServer server,ColumnUnitRequest request,ColumnUnitCommand command) {
        requireServerThread(server);Objects.requireNonNull(request);Objects.requireNonNull(command);
        return submit(server,request,command,command.model().hydrocarbon.revision());
    }

    /** A batch still's column solve (batch 2026-11-09-batch-distiller, W3): {@link com.wormzjl.icheme.science.fluid.network.StillHead#run} under the solve deadline. */
    public static AdmissionResult submitStill(MinecraftServer server,StillRequest request,StillCommand command) {
        requireServerThread(server);Objects.requireNonNull(request);Objects.requireNonNull(command);
        return submit(server,request,command,command.model().hydrocarbon.revision());
    }

    /** Fluid islands share the existing workers and queue with V3; their wall budget starts on the worker. */
    public static AdmissionResult submitFluidIsland(MinecraftServer server,FluidIslandRequest request,FluidIslandCommand command) {
        requireServerThread(server);Objects.requireNonNull(request);Objects.requireNonNull(command);
        var state=STATES.get(server);
        if(state!=null) {
            var diagnostics=Diagnostics.from(state.service.diagnostics());
            if(state.requestsBySequence.values().stream().anyMatch(context->context.request().target().equals(request.target())))return new AdmissionResult(Admission.OWNER_BUSY,diagnostics);
            // Fluid readiness stays in the coordinator's owner set. Fill all idle workers, but do not
            // occupy every queue slot with catch-up jobs ahead of newly arriving calculator work.
            if(diagnostics.readyJobs()>0||diagnostics.activeWorkers()>=diagnostics.workerCount())return new AdmissionResult(Admission.QUEUE_FULL,diagnostics);
        }
        return submit(server,request,command,command.model().hydrocarbon.revision()+":"+command.model().viscosity.revision());
    }

    public static Diagnostics diagnostics(MinecraftServer server) {
        requireServerThread(server);var state=STATES.get(server);return state==null?Diagnostics.EMPTY:Diagnostics.from(state.service.diagnostics());
    }
    /** Independent fluid owners report readiness without queueing their snapshots. Other
     * calculator jobs already owned by the service share the same demand budget. */
    public static void fluidWorkerDemand(MinecraftServer server,int eligibleOwners) {
        requireServerThread(server);if(eligibleOwners<0)throw new IllegalArgumentException("Negative fluid demand");
        var state=STATES.get(server);if(state==null||state.stopResult!=null)return;
        state.fluidDemand=eligibleOwners;allocateWorkers(server,state,0);
    }
    /** Ticks until the automatic allocator would apply its pending lower-demand shrink when observed, or -1 when
     * none is pending or the pool is fixed. The fluid scheduler answers it with one deadline instead of
     * observing demand on every tick. */
    public static long fluidDemandShrinkDelay(MinecraftServer server) {
        requireServerThread(server);var state=STATES.get(server);
        if(state==null||state.stopResult!=null||state.allocator==null)return -1;
        long due=state.allocator.shrinkTick();
        return due<0?-1:Math.max(0,due-Integer.toUnsignedLong(server.getTickCount()));
    }
    private static void allocateWorkers(MinecraftServer server,ServerState state,int incoming) {
        if(state.allocator==null)return;
        int demand=Math.addExact(state.service.diagnostics().outstandingJobs(),Math.addExact(state.fluidDemand,incoming));
        state.service.setWorkerLimit(state.allocator.observe(demand,Integer.toUnsignedLong(server.getTickCount())));
    }
    /** Installs the one world coordinator's owner-thread readiness pump; workers never invoke it. */
    public static Runnable setReadinessPump(MinecraftServer server,Runnable pump) {
        requireServerThread(server);var state=STATES.get(server);if(state==null)throw new IllegalStateException("Process service is not started");var previous=state.readinessPump;state.readinessPump=Objects.requireNonNull(pump);return previous;
    }
    public static void pumpReady(MinecraftServer server) {
        requireServerThread(server);var state=STATES.get(server);if(state!=null&&state.stopResult==null)state.readinessPump.run();
    }

    /** Cancellation does not release owner/worker capacity until the terminal completion is drained. */
    public static BoundedCpuSolveService.CancellationResult cancelRequest(MinecraftServer server,long requestId) {
        requireServerThread(server);var state=STATES.get(server);var context=state==null?null:state.requestsBySequence.get(requestId);
        return context==null?BoundedCpuSolveService.CancellationResult.NOT_FOUND:state.service.cancel(context.stamp(),BoundedCpuSolveService.CancelReason.STALE_REVISION);
    }

    private static AdmissionResult submit(
            MinecraftServer server,
            ProcessSolveRequest request,
            ProcessSolveCommand command,
            String datasetRevision) {
        ServerState state = STATES.get(server);
        if (state == null) {
            return new AdmissionResult(Admission.SERVICE_UNAVAILABLE, Diagnostics.EMPTY);
        }
        if (state.requestsBySequence.containsKey(request.requestId())) {
            throw new IllegalStateException("Duplicate process-solve request sequence");
        }

        allocateWorkers(server,state,request instanceof FluidIslandRequest&&state.fluidDemand>0?0:1);
        long now = System.nanoTime();
        long deadline = request instanceof FluidIslandRequest?BoundedCpuSolveService.NO_DEADLINE:now + state.config.solveDeadline().toNanos();
        var stamp = new BoundedCpuSolveService.JobStamp<SolveTarget>(
                state.service.serverEpoch(),
                request.requestId(),
                request.target(),
                request.inputRevision(),
                datasetRevision,
                deadline);
        BoundedCpuSolveService.Admission serviceAdmission = state.service.trySubmit(
                stamp, command, ProcessSolveServices::solveCommand);
        BoundedCpuSolveService.Diagnostics serviceDiagnostics = state.service.diagnostics();
        Diagnostics diagnostics = Diagnostics.from(serviceDiagnostics);
        Admission admission = Admission.from(serviceAdmission);
        if (admission == Admission.ACCEPTED) {
            if(request instanceof FluidIslandRequest&&state.fluidDemand>0)state.fluidDemand--;
            RequestContext old = state.requestsBySequence.put(
                    request.requestId(), new RequestContext(request, diagnostics, stamp));
            if (old != null) {
                throw new IllegalStateException("Accepted process-solve request replaced existing context");
            }
        }
        return new AdmissionResult(admission, diagnostics);
    }

    /** Drains safely-published worker completions on the logical server thread. */
    public static List<ProcessSolveCompletion> drainCompletions(MinecraftServer server, int maximum) {
        requireServerThread(server);
        if(maximum<1)throw new IllegalArgumentException("maximum must be positive");
        ServerState state = STATES.get(server);
        if (state == null) {
            return List.of();
        }
        int tick=server.getTickCount();if(state.lastDrainTick!=tick){state.lastDrainTick=tick;state.drainedThisTick=0;}
        int remaining=Math.min(maximum,Math.max(0,MAXIMUM_COMPLETIONS_PER_TICK-state.drainedThisTick));
        // A wakeup can arrive after this tick has consumed its budget. Keep the terminal
        // message queued for the next tick; the underlying service requires a positive drain.
        if(remaining==0){owe(server,state,tick);return List.of();}
        var result=drain(state,remaining);
        state.drainedThisTick+=result.size();
        if(state.drainedThisTick>=MAXIMUM_COMPLETIONS_PER_TICK)owe(server,state,tick);
        return result;
    }
    /**
     * A drain that stopped at the per-tick budget with completions left behind owes exactly one continuation.
     * It is posted once per exhausted tick and never drains inside that tick, so it cannot spin; it drains
     * once the tick has advanced, so completed work cannot strand even with no further worker activity.
     *
     * <p>The TickTask tick is not a delay in this Minecraft version (it only marks a task as late), so the
     * posted task normally runs in the same tick's idle phase, finds the tick unchanged and leaves the owed
     * drain to the next tick's completion hook, which drains before anything else that tick. If the task
     * instead runs in a later tick (a blocked mailbox), it performs the drain itself.
     */
    private static void owe(MinecraftServer server,ServerState state,int exhaustedTick) {
        if(state.continuationTick==exhaustedTick||state.service.diagnostics().pendingCompletions()==0)return;
        state.continuationTick=exhaustedTick;
        com.wormzjl.icheme.runtime.fluid.FluidRuntimeDiagnostics.count(com.wormzjl.icheme.runtime.fluid.FluidRuntimeDiagnostics.drainContinuations);
        server.tell(new TickTask(1,()->{
            if(STATES.get(server)!=state||state.stopResult!=null)return;
            if(server.getTickCount()==exhaustedTick) {
                com.wormzjl.icheme.runtime.fluid.FluidRuntimeDiagnostics.count(com.wormzjl.icheme.runtime.fluid.FluidRuntimeDiagnostics.drainContinuationsDeferred);
                return;
            }
            com.wormzjl.icheme.network.ProcessSolveCoordinator.drainCompletedCalculations(server);
        }));
    }

    /** Stops admission and performs the owned bounded two-phase shutdown. */
    public static StopResult stopServer(MinecraftServer server) {
        requireServerThread(server);
        ServerState state = STATES.get(server);
        if (state == null) {
            return StopResult.missing();
        }
        if (state.stopResult != null) {
            return StopResult.alreadyStopped(state.stopResult.shutdownReport());
        }

        state.wakeup.close();
        BoundedCpuSolveService.ShutdownReport shutdown =
                state.service.shutdown(state.config.gracefulShutdown(), state.config.forcedShutdown());
        List<ProcessSolveCompletion> completions = drain(state, Integer.MAX_VALUE);
        List<ProcessSolveRequest> abandoned = state.requestsBySequence.values().stream()
                .map(RequestContext::request)
                .toList();
        state.requestsBySequence.clear();
        state.stopResult = new StopResult(true, shutdown, completions, abandoned);
        return state.stopResult;
    }

    /** Removes the stopped server identity after the final NeoForge lifecycle event. */
    public static int removeStoppedServer(MinecraftServer server) {
        requireServerThread(server);
        ServerState state = STATES.remove(server);
        if (state == null) {
            return 0;
        }
        if (state.stopResult == null) {
            BoundedCpuSolveService.ShutdownReport ignored =
                    state.service.shutdown(state.config.gracefulShutdown(), state.config.forcedShutdown());
            drain(state, Integer.MAX_VALUE);
            int abandoned = state.requestsBySequence.size();
            state.requestsBySequence.clear();
            return abandoned;
        }
        return state.requestsBySequence.size();
    }

    private static ProcessSolveResult solveCommand(
            ProcessSolveCommand command, BoundedCpuSolveService.CancellationToken cancellationToken) {
        return command.solve(cancellationToken);
    }

    private static List<ProcessSolveCompletion> drain(ServerState state, int maximum) {
        List<BoundedCpuSolveService.Completion<SolveTarget, ProcessSolveResult>> completed =
                state.service.drainCompletions(maximum);
        if (completed.isEmpty()) {
            return List.of();
        }
        List<ProcessSolveCompletion> result = new ArrayList<>(completed.size());
        for (var completion : completed) {
            RequestContext context = state.requestsBySequence.remove(completion.stamp().sequence());
            if (context == null) {
                throw new IllegalStateException("Process-solve completion has no request context");
            }
            if(context.request() instanceof FluidIslandRequest fluidRequest) {
                result.add(new FluidIslandCompletion(fluidRequest,context.admissionDiagnostics(),fluidCompletion(completion)));
            } else if(context.request() instanceof ColumnUnitRequest columnRequest) {
                // A worker failure, a cancellation or the deadline is a failed solve, named by its status.
                var outcome=completion.result().map(value->{if(!(value instanceof ColumnUnitSolveResult column))throw new IllegalStateException("Column unit request completed with a different result family");return column.outcome();})
                        .orElseGet(()->new com.wormzjl.icheme.science.column.operation.ColumnSolve.Failed(completion.status().name()+(completion.detail()==null?"":": "+completion.detail())));
                result.add(new ColumnUnitCompletion(columnRequest,outcome));
            } else if(context.request() instanceof StillRequest stillRequest) {
                var outcome=completion.result().map(value->{if(!(value instanceof StillSolveResult still))throw new IllegalStateException("Still request completed with a different result family");return still.outcome();})
                        .orElseGet(()->new com.wormzjl.icheme.science.fluid.network.StillHead.Failed(completion.status().name()+(completion.detail()==null?"":": "+completion.detail())));
                result.add(new StillCompletion(stillRequest,outcome));
            } else {
                throw new IllegalStateException("Unknown process-solve request family");
            }
        }
        return List.copyOf(result);
    }

    private static <T extends SolveTarget> BoundedCpuSolveService.JobStamp<T> stampFor(BoundedCpuSolveService.JobStamp<SolveTarget> stamp,Class<T> type) {
        return new BoundedCpuSolveService.JobStamp<>(stamp.serverEpoch(),stamp.sequence(),type.cast(stamp.owner()),stamp.inputRevision(),stamp.datasetRevision(),stamp.deadlineNanos());
    }
    private static BoundedCpuSolveService.Completion<FluidIslandTarget,FluidIslandSolveResult> fluidCompletion(BoundedCpuSolveService.Completion<SolveTarget,ProcessSolveResult> completion) {
        var result=completion.result().map(value->{if(!(value instanceof FluidIslandSolveResult fluid))throw new IllegalStateException("Fluid request completed with a different result family");return fluid;});
        return new BoundedCpuSolveService.Completion<>(stampFor(completion.stamp(),FluidIslandTarget.class),completion.status(),result,completion.failure(),completion.detail(),completion.enqueuedNanos(),completion.startedNanos(),completion.completedNanos());
    }

    private static void requireServerThread(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        if (!server.isSameThread()) {
            throw new IllegalStateException("Process solve services must be coordinated on the server thread");
        }
    }

    public enum Admission {
        ACCEPTED,
        OWNER_BUSY,
        QUEUE_FULL,
        STALE_EPOCH,
        STOPPING,
        SERVICE_UNAVAILABLE;

        private static Admission from(BoundedCpuSolveService.Admission admission) {
            return switch (admission) {
                case ACCEPTED -> ACCEPTED;
                case OWNER_BUSY -> OWNER_BUSY;
                case QUEUE_FULL -> QUEUE_FULL;
                case STALE_EPOCH -> STALE_EPOCH;
                case STOPPING -> STOPPING;
            };
        }
    }

    /** Immutable worker envelope. Adding a job family extends this sealed protocol, never the executor count. */
    public sealed interface ProcessSolveCommand permits FluidIslandCommand,ColumnUnitCommand,StillCommand {
        ProcessSolveResult solve(BoundedCpuSolveService.CancellationToken cancellationToken);
    }

    /** Immutable worker result envelope routed only after main-thread completion draining. */
    public sealed interface ProcessSolveResult permits FluidIslandSolveResult,ColumnUnitSolveResult,StillSolveResult {}

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

    /** Stable block identity; no loaded level or block entity is retained. */
    public sealed interface SolveTarget permits FluidIslandTarget,ColumnUnitTarget,StillTarget {}

    /** A running column unit, by its identity (batch 2026-10-28-column-operation). */
    public record ColumnUnitTarget(ResourceKey<Level> dimension,long unit) implements SolveTarget {
        public ColumnUnitTarget {Objects.requireNonNull(dimension);if(unit<=0)throw new IllegalArgumentException("Invalid column unit identity");}
    }
    /** Server-only delivery context of a column unit's solve; the handler gets the outcome, a failed one for a worker failure or a shutdown. */
    public record ColumnUnitRequest(long requestId,ColumnUnitTarget target,long inputRevision,java.util.function.Consumer<com.wormzjl.icheme.science.column.operation.ColumnSolve.Outcome> handler) implements ProcessSolveRequest {
        public ColumnUnitRequest {Objects.requireNonNull(target);Objects.requireNonNull(handler);if(requestId<=0||inputRevision<0)throw new IllegalArgumentException("Invalid column unit request stamp");}
    }
    /** A column unit's solve: the job and its feed island's model (its compound list's view, which V3 reads too), read on the server at admission. */
    public record ColumnUnitCommand(com.wormzjl.icheme.science.column.operation.ColumnSolve.Job job,FluidThermodynamics model,double liquidSupplyScreenRatio) implements ProcessSolveCommand {
        public ColumnUnitCommand {Objects.requireNonNull(job);Objects.requireNonNull(model);}
        @Override public ProcessSolveResult solve(BoundedCpuSolveService.CancellationToken cancellationToken) {
            return new ColumnUnitSolveResult(com.wormzjl.icheme.science.column.operation.ColumnSolve.run(job,model,liquidSupplyScreenRatio,cancellationToken::throwIfCancellationRequested));
        }
    }
    public record ColumnUnitSolveResult(com.wormzjl.icheme.science.column.operation.ColumnSolve.Outcome outcome) implements ProcessSolveResult {
        public ColumnUnitSolveResult {Objects.requireNonNull(outcome);}
    }
    public record ColumnUnitCompletion(ColumnUnitRequest request,com.wormzjl.icheme.science.column.operation.ColumnSolve.Outcome outcome) implements ProcessSolveCompletion {
        public ColumnUnitCompletion {Objects.requireNonNull(request);Objects.requireNonNull(outcome);}
    }

    /** A batch still, by its pot's identity (batch 2026-11-09-batch-distiller, W3). */
    public record StillTarget(ResourceKey<Level> dimension,long pot) implements SolveTarget {
        public StillTarget {Objects.requireNonNull(dimension);if(pot<=0)throw new IllegalArgumentException("Invalid still identity");}
    }
    /** Server-only delivery context of a still's column solve; the handler gets the outcome, a failed one for a worker failure or a shutdown. */
    public record StillRequest(long requestId,StillTarget target,long inputRevision,java.util.function.Consumer<com.wormzjl.icheme.science.fluid.network.StillHead.Outcome> handler) implements ProcessSolveRequest {
        public StillRequest {Objects.requireNonNull(target);Objects.requireNonNull(handler);if(requestId<=0||inputRevision<0)throw new IllegalArgumentException("Invalid still request stamp");}
    }
    /** A still's column solve: the job and its pot island's model, read on the server at admission. */
    public record StillCommand(com.wormzjl.icheme.science.fluid.network.StillHead.Job job,FluidThermodynamics model) implements ProcessSolveCommand {
        public StillCommand {Objects.requireNonNull(job);Objects.requireNonNull(model);}
        @Override public ProcessSolveResult solve(BoundedCpuSolveService.CancellationToken cancellationToken) {
            return new StillSolveResult(com.wormzjl.icheme.science.fluid.network.StillHead.run(job,model,cancellationToken::throwIfCancellationRequested));
        }
    }
    public record StillSolveResult(com.wormzjl.icheme.science.fluid.network.StillHead.Outcome outcome) implements ProcessSolveResult {
        public StillSolveResult {Objects.requireNonNull(outcome);}
    }
    public record StillCompletion(StillRequest request,com.wormzjl.icheme.science.fluid.network.StillHead.Outcome outcome) implements ProcessSolveCompletion {
        public StillCompletion {Objects.requireNonNull(request);Objects.requireNonNull(outcome);}
    }

    public record FluidIslandTarget(ResourceKey<Level> dimension,long islandId) implements SolveTarget {
        public FluidIslandTarget {Objects.requireNonNull(dimension);if(islandId<=0)throw new IllegalArgumentException("Invalid island identity");}
    }

    /** Immutable server-thread request context retained until exactly one terminal completion is drained. */
    public sealed interface ProcessSolveRequest permits FluidIslandRequest,ColumnUnitRequest,StillRequest {
        long requestId();

        SolveTarget target();

        long inputRevision();
    }

    /** Server-only delivery context; the handler is never included in the worker command. */
    public record FluidIslandRequest(long requestId,FluidIslandTarget target,long inputRevision,FluidCompletionHandler handler) implements ProcessSolveRequest {
        public FluidIslandRequest {Objects.requireNonNull(target);Objects.requireNonNull(handler);if(requestId<=0||inputRevision<0)throw new IllegalArgumentException("Invalid fluid request stamp");}
    }
    public interface FluidCompletionHandler {
        void completed(FluidIslandCompletion completion);
        void abandoned(FluidIslandRequest request);
    }
    public record FluidIslandCompletion(FluidIslandRequest request,Diagnostics admissionDiagnostics,
            BoundedCpuSolveService.Completion<FluidIslandTarget,FluidIslandSolveResult> completion) implements ProcessSolveCompletion {
        public FluidIslandCompletion {
            Objects.requireNonNull(request);Objects.requireNonNull(admissionDiagnostics);Objects.requireNonNull(completion);
            if(request.requestId()!=completion.stamp().sequence()||!request.target().equals(completion.stamp().owner())||request.inputRevision()!=completion.stamp().inputRevision())throw new IllegalArgumentException("Fluid completion stamp mismatch");
        }
    }

    /** Marker for the central router; only it may drain the shared completion queue. */
    public sealed interface ProcessSolveCompletion permits FluidIslandCompletion,ColumnUnitCompletion,StillCompletion {}

    public record AdmissionResult(Admission admission, Diagnostics diagnostics) {
        public AdmissionResult {
            Objects.requireNonNull(admission, "admission");
            Objects.requireNonNull(diagnostics, "diagnostics");
        }

        public boolean accepted() {
            return admission == Admission.ACCEPTED;
        }
    }

    public record Diagnostics(
            int workerCount,
            int activeWorkers,
            int readyJobs,
            int readyCapacity,
            int outstandingJobs,
            int pendingCompletions) {
        public static final Diagnostics EMPTY = new Diagnostics(0, 0, 0, 0, 0, 0);

        private static Diagnostics from(BoundedCpuSolveService.Diagnostics diagnostics) {
            return new Diagnostics(
                    diagnostics.workerCount(),
                    diagnostics.activeWorkers(),
                    diagnostics.readyJobs(),
                    diagnostics.readyCapacity(),
                    diagnostics.outstandingJobs(),
                    diagnostics.pendingCompletions());
        }
    }

    public record ServerStarted(
            long serverEpoch,
            int workerCount,
            int readyCapacity,
            long solveDeadlineMilliseconds,
            long gracefulShutdownMilliseconds,
            long forcedShutdownMilliseconds) {}

    public record Config(
            int workerCount,
            int readyCapacity,
            Duration solveDeadline,
            Duration gracefulShutdown,
            Duration forcedShutdown,boolean dynamicWorkers) {
        public Config {
            if (workerCount < 1 || readyCapacity < 1) {
                throw new IllegalArgumentException("Worker and ready capacities must be positive");
            }
            Objects.requireNonNull(solveDeadline, "solveDeadline");
            Objects.requireNonNull(gracefulShutdown, "gracefulShutdown");
            Objects.requireNonNull(forcedShutdown, "forcedShutdown");
            if (solveDeadline.isZero()
                    || solveDeadline.isNegative()
                    || gracefulShutdown.isNegative()
                    || forcedShutdown.isNegative()) {
                throw new IllegalArgumentException("Invalid solve-service duration");
            }
        }
    }

    public record StopResult(
            boolean shutdownPerformed,
            BoundedCpuSolveService.ShutdownReport shutdownReport,
            List<ProcessSolveCompletion> completions,
            List<ProcessSolveRequest> abandonedRequests) {
        public StopResult {
            completions = List.copyOf(Objects.requireNonNull(completions, "completions"));
            abandonedRequests = List.copyOf(Objects.requireNonNull(abandonedRequests, "abandonedRequests"));
        }

        private static StopResult missing() {
            return new StopResult(
                    false,
                    new BoundedCpuSolveService.ShutdownReport(true, false, false, 0),
                    List.of(),
                    List.of());
        }

        private static StopResult alreadyStopped(BoundedCpuSolveService.ShutdownReport shutdownReport) {
            return new StopResult(false, shutdownReport, List.of(), List.of());
        }
    }

    private record RequestContext(
            ProcessSolveRequest request,
            Diagnostics admissionDiagnostics,
            BoundedCpuSolveService.JobStamp<SolveTarget> stamp) {}

    private static final class ServerState {
        private final BoundedCpuSolveService<SolveTarget, ProcessSolveCommand, ProcessSolveResult> service;
        private final Config config;
        private final CompletionWakeup wakeup;
        private final WorkerAllocation.Demand allocator;
        private int fluidDemand;
        private Runnable readinessPump=()->{};
        private int lastDrainTick=Integer.MIN_VALUE,drainedThisTick,continuationTick=Integer.MIN_VALUE;
        private final Map<Long, RequestContext> requestsBySequence = new LinkedHashMap<>();
        private StopResult stopResult;

        private ServerState(
                BoundedCpuSolveService<SolveTarget, ProcessSolveCommand, ProcessSolveResult> service,
                Config config,CompletionWakeup wakeup) {
            this.service = service;
            this.config = config;
            this.wakeup=wakeup;
            this.allocator=config.dynamicWorkers()?new WorkerAllocation.Demand(config.workerCount()):null;
        }
    }

    /** Coalesces terminal notifications into at most one pending owner-thread mailbox task.
     * The executor must enqueue asynchronously onto the constructing thread; it must never run inline.
     * A signal carries no result and never releases service capacity. Only the owner pump may drain results.
     */
    public static final class CompletionWakeup implements AutoCloseable {
        private final Thread owner=Thread.currentThread();
        private final java.util.concurrent.Executor mailbox;
        private final Runnable pump;
        private final java.util.concurrent.atomic.AtomicBoolean pending=new java.util.concurrent.atomic.AtomicBoolean(),closed=new java.util.concurrent.atomic.AtomicBoolean();
        public CompletionWakeup(java.util.concurrent.Executor mailbox,Runnable pump){this.mailbox=Objects.requireNonNull(mailbox);this.pump=Objects.requireNonNull(pump);}
        public void signal() {
            if(closed.get()||!pending.compareAndSet(false,true))return;
            try{mailbox.execute(()->{owned();pending.set(false);if(!closed.get())pump.run();});}
            catch(RuntimeException failure){pending.set(false);throw failure;}
        }
        @Override public void close(){owned();closed.set(true);}
        private void owned(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Completion pump must run on its owning thread");}
    }
}
