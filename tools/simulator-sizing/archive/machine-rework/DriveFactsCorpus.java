package com.wormzjl.icheme.runtime.fluid;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.icheme.science.machine.InlineMachines;
import java.nio.file.*;
import java.util.*;
import net.minecraft.nbt.NbtIo;
public class DriveFactsCorpus {
    public static void main(String[] args)throws Exception {
        var catalog=MaterialCatalog.bundled();var basis=WorldTopologyLedger.FluidBasis.capture(catalog);
        var spec=FluidDeviceSpec.nitrogen(catalog);var geometry=new PipeResistance.Geometry(1,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0);
        var device=new PhysicalFluidTopology.Device(1,new PhysicalFluidTopology.Position("minecraft:overworld",0,64,0),TopologyCompiler.Kind.PIPE,PhysicalFluidTopology.Direction.EAST,geometry,new FlowControl.Compressor(.01,3,true,.008,220000));
        var inline=new PhysicalFluidTopology.Device(2,new PhysicalFluidTopology.Position("minecraft:overworld",4,64,0),TopologyCompiler.Kind.PIPE,PhysicalFluidTopology.Direction.EAST,geometry,InlineMachines.design(.05,true));
        var registrations=Map.of(1L,new WorldTopologyLedger.Registration(device,spec,0),2L,new WorldTopologyLedger.Registration(inline,spec,0));
        var total=WorldTopologyLedger.MaterialTotal.empty(basis.components().size());
        var snapshot=new WorldTopologyLedger.Snapshot(0,3,registrations,List.of(),total,total,basis,Map.of(),new WorldTopologyLedger.Restated(0,Map.of(),Map.of()),Map.of(),Map.of(),Map.of(),Map.of());
        var store=FluidCheckpointStore.memory();var plan=store.prepare(new FluidCheckpointCodec.Checkpoint(List.of()),Optional.of(snapshot),0,key->{throw new IllegalStateException("No island model is needed for an empty-inventory fixture");});store.commit(plan);
        var image=store.image();var folder=Path.of("src/test/resources/compat/fluid/schema-14");Files.createDirectories(folder);
        NbtIo.writeCompressed(image.core(),folder.resolve("core.dat"));for(var pack:image.packs().entrySet())Files.write(folder.resolve("pack-"+pack.getKey()+".bin"),pack.getValue());
        Files.writeString(folder.resolve("expected.properties"),"registrations=2\nlaid=0\nlanes=0\nshaped=0\ncontrolValves=0\nheatSides=\nsuspended=0\ncolumns=\nshafts=\nstores=\nhealth=\nvessels=0\nmoles=0.0\n");
        System.out.println("Wrote schema-14 drive-demand and integral-drive facts image");
    }
}
