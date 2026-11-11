package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.runtime.ProcessSolveServices;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import java.util.*;
import java.util.function.LongSupplier;

/** Owner-thread bridge to the existing shared pool. The world authority owns this bridge's lifetime. */
public final class MinecraftFluidRuntime implements AutoCloseable {
    private final MinecraftServer server;
    private final IslandCoordinator coordinator;
    private final Map<Long,ResourceKey<Level>> dimensions=new HashMap<>();
    private final Runnable previousPump;
    private boolean closed;

    /** {@code epoch} is the world's online tick, advanced by its owner before {@link #tick()}; null gives this
     * runtime's coordinator its own epoch, advanced by {@link #tick()}. */
    public MinecraftFluidRuntime(MinecraftServer server,IslandCoordinator.Publisher publisher,IslandCoordinator.Settings settings,LongSupplier epoch) {
        this.server=Objects.requireNonNull(server);owned();
        coordinator=new IslandCoordinator(new IslandCoordinator.Dispatcher() {
            public void demand(int eligibleOwners){ProcessSolveServices.fluidWorkerDemand(server,eligibleOwners);}
            public long demandShrinkDelay(){return ProcessSolveServices.fluidDemandShrinkDelay(server);}
            public int availableWorkers(){var d=ProcessSolveServices.diagnostics(server);return d.readyJobs()>0?0:Math.max(0,d.workerCount()-d.activeWorkers());}
            public long nextRequestId(){return ProcessSolveServices.nextRequestId();}
            public boolean submit(IslandCoordinator.Attempt attempt,ProcessSolveServices.FluidIslandCommand command) {
                var target=new ProcessSolveServices.FluidIslandTarget(Objects.requireNonNull(dimensions.get(attempt.islandId())),attempt.islandId());
                var handler=new ProcessSolveServices.FluidCompletionHandler() {
                    public void completed(ProcessSolveServices.FluidIslandCompletion result){var c=result.completion();coordinator.completed(attempt,c.result(),c.result().isPresent()?null:failure(attempt.islandId(),c));}
                    public void abandoned(ProcessSolveServices.FluidIslandRequest request){coordinator.completed(attempt,Optional.empty(),"worker abandoned the solve");}
                };
                return ProcessSolveServices.submitFluidIsland(server,new ProcessSolveServices.FluidIslandRequest(attempt.slice().requestId(),target,attempt.revision(),handler),command).admission()==ProcessSolveServices.Admission.ACCEPTED;
            }
            public void cancel(long requestId){ProcessSolveServices.cancelRequest(server,requestId);}
        },publisher,System::nanoTime,settings,epoch);
        // Called after every completion drain: pumps only when a completion, a newly ready owner, a due round or
        // allocator deadline, or free capacity for an already ready owner gives the pump something to do.
        previousPump=ProcessSolveServices.setReadinessPump(server,coordinator::pumpIfUseful);
    }
    /** The held status's words for a completion without a result: "worker failure: {type}: {message}" (its stack to the log),
     * or the terminal status and detail (a cancellation, the worker's deadline). */
    static String failure(long island,com.wormzjl.icheme.runtime.BoundedCpuSolveService.Completion<?,?> completion) {
        var failure=completion.failure().orElse(null);
        if(failure!=null) {
            var thrown=new IllegalStateException(failure.type()+": "+failure.message());thrown.setStackTrace(failure.stackTrace().toArray(StackTraceElement[]::new));
            com.wormzjl.icheme.IChemE.LOGGER.warn("fluid island {} worker failure",island,thrown);
            String type=failure.type().substring(failure.type().lastIndexOf('.')+1),message=failure.message().replaceAll("\\s+"," ").strip();
            if(message.length()>200)message=message.substring(0,200)+"...";
            return "worker failure: "+type+(message.isEmpty()?"":": "+message);
        }
        var detail=completion.detail();
        return "worker "+completion.status().name().toLowerCase(java.util.Locale.ROOT).replace('_',' ')+(detail==null||detail.isBlank()?"":": "+detail);
    }
    private void owned(){if(!server.isSameThread())throw new IllegalStateException("Fluid runtime requires the logical server thread");}
    public IslandCoordinator coordinator(){owned();return coordinator;}
    public void register(ResourceKey<Level> dimension,IslandCoordinator.Snapshot snapshot,FluidThermodynamics model) {
        owned();Objects.requireNonNull(dimension);if(closed||dimensions.containsKey(snapshot.id()))throw new IllegalStateException("Closed/duplicate runtime island");
        coordinator.register(snapshot,model);dimensions.put(snapshot.id(),dimension);
    }
    /** A batch of events of one tick in one dimension, applied as one change (see {@link IslandCoordinator#topology(Set,Set,List,FluidThermodynamics,long,long,Map,Set,Map,Runnable)}). */
    public void topology(ResourceKey<Level> dimension,Set<UUID> events,Set<Long> affected,List<IslandCoordinator.Replacement> replacements,FluidThermodynamics model,long committed,long online,Map<Long,com.wormzjl.icheme.science.fluid.network.PassiveNetwork.Reservoir> additions,Set<Long> removals,Map<Long,com.wormzjl.icheme.science.fluid.network.InlineFilter> releasedFilters,Runnable metadataCommit) {
        owned();Objects.requireNonNull(dimension);
        if(affected.stream().anyMatch(id->!dimension.equals(dimensions.get(id))))throw new IllegalStateException("Cross-dimension topology event");
        coordinator.topology(events,affected,replacements,model,committed,online,additions,removals,releasedFilters,()->{
            metadataCommit.run();affected.forEach(dimensions::remove);for(var replacement:replacements)dimensions.put(replacement.id(),dimension);
        });
    }
    /** Once per server tick, after the epoch advanced: resets the dispatch budget and acts on due deadlines. */
    public void tick(){owned();if(!closed)coordinator.tick();}
    @Override public void close(){owned();if(closed)return;closed=true;coordinator.stop();ProcessSolveServices.setReadinessPump(server,previousPump);}
}
