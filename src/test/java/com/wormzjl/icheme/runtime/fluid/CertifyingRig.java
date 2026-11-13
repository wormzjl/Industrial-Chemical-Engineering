package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.runtime.BoundedCpuSolveService;
import com.wormzjl.icheme.runtime.ProcessSolveJobs;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialTestBasis;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A coordinator on its own online epoch whose worker solves each admitted slice at once, as the pool would; what a
 * save captures is every island materialised to now. {@link Islands} holds the island fixtures the checkpoint tests
 * share.
 */
final class CertifyingRig {
    static final String PACKAGE="icheme:tjl20_methane_nitrogen";
    final long[] epoch;final FluidThermodynamics model;
    final Map<Long,IslandCoordinator.Attempt> attempts=new LinkedHashMap<>();
    final Map<Long,ProcessSolveJobs.FluidIslandCommand> commands=new HashMap<>();
    /** What the coordinator published and replayed, recorded only after {@link #recording()}. */
    final List<IslandCoordinator.Snapshot> published=new ArrayList<>(),replayed=new ArrayList<>();
    final IslandCoordinator coordinator;
    /** Told of every publication after it is recorded, as FluidWorldAuthority.published is. */
    java.util.function.Consumer<List<IslandCoordinator.Snapshot>> onPublished=changed->{};
    long requests;int solves;private boolean recording;

    CertifyingRig(CertificatePolicy policy,FluidThermodynamics model,long start) {
        this.model=model;epoch=new long[]{start};
        coordinator=new IslandCoordinator(new IslandCoordinator.Dispatcher() {
            public int availableWorkers(){return 64-attempts.size();}
            public long nextRequestId(){return ++requests;}
            public boolean submit(IslandCoordinator.Attempt attempt,ProcessSolveJobs.FluidIslandCommand command){attempts.put(attempt.slice().requestId(),attempt);commands.put(attempt.slice().requestId(),command);return true;}
            public void cancel(long request){throw new AssertionError("no deadlines here");}
        },changed->{if(recording)published.addAll(changed);onPublished.accept(changed);},()->0L,new IslandCoordinator.Settings(30_000_000_000L,20_000_000_000L,64,false,100,policy),()->epoch[0]);
        coordinator.onReplayed(changed->{if(recording)replayed.addAll(changed);});
    }
    /** Records what the coordinator publishes and replays from here on. */
    CertifyingRig recording(){recording=true;return this;}
    void register(long id,PassiveNetwork graph) {
        coordinator.register(new IslandCoordinator.Snapshot(id,0,graph,new IslandClock.Snapshot(epoch[0],epoch[0],0,100),Optional.empty(),"READY"),model);
    }
    void load(FluidCheckpointCodec.Checkpoint checkpoint){for(var entry:checkpoint.islands())coordinator.register(entry.snapshot(),model);}
    /** Solves and completes everything admitted, then pumps, until nothing is outstanding: one completion drain. */
    void drain() {
        while(!attempts.isEmpty()) {
            for(long request:List.copyOf(attempts.keySet())) {
                var attempt=attempts.remove(request);var command=commands.remove(request);solves++;
                coordinator.completed(attempt,Optional.of((ProcessSolveJobs.FluidIslandSolveResult)command.solve(FluidTestSupport.NEVER)));
            }
            coordinator.pump();
        }
    }
    void tick(){epoch[0]++;coordinator.tick();drain();}
    void run(long ticks){for(long i=0;i<ticks;i++)tick();}
    /** Runs until every island certifies, at most the given number of ticks, and asserts that they did. */
    void runUntilCertified(Collection<Long> ids,int ticks) {
        for(int i=0;i<ticks&&!ids.stream().allMatch(id->stored(id).certificate().isPresent());i++)tick();
        for(long id:ids)assertTrue(stored(id).certificate().isPresent(),"island "+id+" did not certify: "+coordinator.certificationRefusal(id));
    }
    /** Runs until the island certifies, at most the given number of ticks; the tick it certified at, or -1. */
    long certifiedAt(long id,int ticks) {
        for(int i=0;i<ticks;i++){tick();if(stored(id).certificate().isPresent())return epoch[0];}
        return -1;
    }
    IslandCoordinator.Snapshot stored(long id){return coordinator.observe(id);}
    IslandCoordinator.Snapshot read(long id){return coordinator.snapshot(id);}
    /** What a save captures: every island materialised to now, under the rig's model's package. */
    FluidCheckpointCodec.Checkpoint checkpoint() {
        return new FluidCheckpointCodec.Checkpoint(coordinator.snapshots().stream().map(s->new FluidCheckpointCodec.IslandEntry("minecraft:overworld",model.domain().packageId(),s)).toList());
    }

    /** Islands with distinct identities on the network model, the diagnostics counters, and rigs on that model. */
    abstract static class Islands {
        static final String PACKAGE=CertifyingRig.PACKAGE;
        static final PipeResistance.Geometry BLOCK=new PipeResistance.Geometry(1,.05,.000045,0);
        static final BoundedCpuSolveService.CancellationToken NEVER=FluidTestSupport.NEVER;
        final FluidThermodynamics model=FluidTestSupport.networkModel();
        final int components=model.components().size();
        final int water=components-1,nitrogen=MaterialTestBasis.NITROGEN;

        @AfterEach void counters(){FluidRuntimeDiagnostics.ENABLED=false;FluidRuntimeDiagnostics.reset();}
        static long counter(String name){return FluidRuntimeDiagnostics.sample().get(name);}
        static void countFromHere(){FluidRuntimeDiagnostics.reset();FluidRuntimeDiagnostics.ENABLED=true;}

        CertifyingRig rig(CertificatePolicy policy){return new CertifyingRig(policy,model,0);}
        CertifyingRig rig(CertificatePolicy policy,FluidThermodynamics model,long start){return new CertifyingRig(policy,model,start);}
        /** An empty world ledger at the given online tick. */
        static WorldTopologyLedger.Snapshot world(long onlineTick) {
            var empty=WorldTopologyLedger.Snapshot.empty(MaterialCatalog.bundled());
            return new WorldTopologyLedger.Snapshot(onlineTick,empty.nextIdentity(),empty.active(),empty.events(),empty.constructed(),empty.destroyed(),empty.basis(),empty.recoveries());
        }

        double[] pure(int component){var n=new double[components];n[component]=1;return n;}
        /** A line of blocks along x in row z, identities first, first + 1, ...; pipes between the ends. */
        static List<PhysicalFluidTopology.Device> line(long first,int z,Kind... kinds) {
            var devices=new ArrayList<PhysicalFluidTopology.Device>();
            for(int i=0;i<kinds.length;i++)devices.add(PhysicalFluidTopology.Device.builder(first+i,new PhysicalFluidTopology.Position("minecraft:overworld",i,0,z),kinds[i],PhysicalFluidTopology.Direction.EAST,BLOCK,new FlowControl.Passive()).build());
            return devices;
        }
        PassiveNetwork island(List<PhysicalFluidTopology.Device> devices,Map<Long,FluidDeviceSpec> specs) {
            var stock=new LinkedHashMap<Long,PassiveNetwork.Reservoir>();
            for(var d:devices){var spec=specs.get(d.id());if(spec!=null)stock.put(d.id(),FluidTestSupport.chargedBoundary(spec,d,model));}
            var connected=PhysicalFluidTopology.compile(devices,stock).islands().stream().filter(i->i.physicalIds().size()==devices.size()).toList();
            assertEquals(1,connected.size());return connected.getFirst().graph();
        }
        /** Water at 1 atm against nitrogen at 2 bar: the line cannot flow, it certifies REST. */
        PassiveNetwork deadHeadedLine(long first,int z) {
            return island(line(first,z,Kind.GENERATOR,Kind.PIPE,Kind.PIPE,Kind.PIPE,Kind.RESERVOIR),
                    Map.of(first,new FluidDeviceSpec(1,298.15,101325,pure(water)),first+4,new FluidDeviceSpec(1,298.15,200000,pure(nitrogen))));
        }
        /** Two nitrogen tanks a kilopascal apart: they settle, and every job starting from the committed interval, they certify the identity map (drift 0). */
        PassiveNetwork closedPair(long first,int z) {
            return island(line(first,z,Kind.RESERVOIR,Kind.PIPE,Kind.PIPE,Kind.PIPE,Kind.RESERVOIR),
                    Map.of(first,new FluidDeviceSpec(1,298.15,150000,pure(nitrogen)),first+4,new FluidDeviceSpec(1,298.15,149000,pure(nitrogen))));
        }
        /** A nitrogen generator feeding a void: a certified through-flow with no finite node, rechecked at its horizon. */
        PassiveNetwork generatorToVoid(long first,int z) {
            return island(line(first,z,Kind.GENERATOR,Kind.PIPE,Kind.PIPE,Kind.PIPE,Kind.PIPE,Kind.VOID),
                    Map.of(first,new FluidDeviceSpec(1,298.15,150000,pure(nitrogen)),first+5,new FluidDeviceSpec(1,298.15,101325,pure(nitrogen))));
        }
        /** A 1000 m3 tank filling from a 150 kPa generator: it stays awake for hours. */
        PassiveNetwork slowFill(long first,int z) {
            return island(line(first,z,Kind.GENERATOR,Kind.PIPE,Kind.PIPE,Kind.PIPE,Kind.PIPE,Kind.RESERVOIR),
                    Map.of(first,new FluidDeviceSpec(1,298.15,150000,pure(nitrogen)),first+5,new FluidDeviceSpec(1000,298.15,101325,pure(nitrogen))));
        }
        PassiveNetwork loneTank(long node) {
            return new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(node,0,model.initialNitrogenCharge(1,298.15,101325,()->{}))),List.of());
        }
    }
}
