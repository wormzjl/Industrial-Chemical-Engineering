package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import net.minecraft.nbt.NbtIo;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction;

/** Writes, with the build before batch 2026-10-28-old-mover-removal, a saved world holding one-block movers. */
class RemovedMoverImageWriter {
    private static final MaterialCatalog CATALOG=EventPathWorld.CATALOG;
    private static final String PACKAGE=FluidMaterialCatalog.networkPackage(CATALOG);
    private final FluidThermodynamics model=FluidTestSupport.networkModel();

    @Test void write() throws Exception {
        var world=new EventPathWorld();
        // A compressor line: a 3 bar, 340 K nitrogen generator, a pipe, a one-block compressor, a pipe, a tank.
        world.place(0,64,0,Kind.GENERATOR,Direction.EAST,new FluidDeviceSpec(1,340,300000,FluidDeviceSpec.nitrogen(CATALOG).composition()),.05);
        world.place(1,64,0,Kind.PIPE,Direction.EAST,.05);
        world.place(2,64,0,Kind.COMPRESSOR,Direction.EAST,new FlowControl.Compressor(.004,2.5),null,.05);
        world.place(3,64,0,Kind.PIPE,Direction.EAST,.05);
        world.place(4,64,0,Kind.RESERVOIR,Direction.EAST);
        // A pump line: a 2 bar, 298.15 K water generator, a pipe, a one-block pump, a pipe, a tank.
        world.place(0,64,4,Kind.GENERATOR,Direction.EAST,EventPathWorld.pure(com.wormzjl.icheme.science.material.MaterialTestBasis.NETWORK,298.15,200000),.05);
        world.place(1,64,4,Kind.PIPE,Direction.EAST,.05);
        world.place(2,64,4,Kind.PUMP,Direction.EAST,new FlowControl.Pump(.0005,300000),null,.05);
        world.place(3,64,4,Kind.PIPE,Direction.EAST,.05);
        world.place(4,64,4,Kind.RESERVOIR,Direction.EAST);
        world.runTo(600);
        var checkpoint=new FluidCheckpointCodec.Checkpoint(world.coordinator.snapshots().stream().map(s->new FluidCheckpointCodec.IslandEntry(EventPathWorld.DIM,PACKAGE,s)).toList());
        var store=FluidCheckpointStore.memory();store.commit(store.prepare(checkpoint,Optional.of(world.ledger.snapshot()),world.ledger.onlineTick(),key->model));
        var image=store.image();
        var folder=Path.of("build/compat/removed-movers");Files.createDirectories(folder);
        NbtIo.writeCompressed(image.core(),folder.resolve("core.dat"));
        for(var pack:image.packs().entrySet())Files.write(folder.resolve("pack-"+pack.getKey()+".bin"),pack.getValue());
        var ledger=world.ledger.snapshot();
        long movers=ledger.active().values().stream().filter(r->r.device().kind()==Kind.PUMP||r.device().kind()==Kind.COMPRESSOR).count();
        double moles=0;for(var e:checkpoint.islands())for(var n:e.snapshot().graph().reservoirs())if(n.kind()==PassiveNetwork.NodeKind.RESERVOIR)for(double m:n.inventory().moles())moles+=m;
        var props=new Properties();
        props.setProperty("registrations",Integer.toString(ledger.active().size()));props.setProperty("movers",Long.toString(movers));
        props.setProperty("vessels","2");props.setProperty("moles",Double.toString(moles));
        try(var out=Files.newBufferedWriter(folder.resolve("expected.properties"))){props.store(out,"A world with one-block movers, written by main 96806b8a (facts schema "+FluidFacts.SCHEMA+", state format "+FluidCheckpointCodec.VERSION+")");}
        System.out.println("REMOVED_MOVER_IMAGE "+props);
    }
}
