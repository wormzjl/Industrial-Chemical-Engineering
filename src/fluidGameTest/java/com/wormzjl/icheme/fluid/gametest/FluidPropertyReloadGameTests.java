package com.wormzjl.icheme.fluid.gametest;

import com.google.gson.JsonParser;
import com.wormzjl.icheme.network.ProcessSolveCoordinator;
import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.runtime.fluid.*;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.*;
import com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder("icheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class FluidPropertyReloadGameTests {
    private FluidPropertyReloadGameTests() {}
    @GameTest(template="empty",timeoutTicks=10000,batch="fluid-property-reload")
    public static void betweenTickRoutingHoldsChangedScienceAndRestoringItResumesConservedState(GameTestHelper helper) {
        var level=helper.getLevel();var server=level.getServer();var world=FluidWorldAuthority.find(server).orElseThrow();
        var original=MaterialRuntime.active();var resources=new HashMap<>(original.resources());
        String path="data/icheme/materials/properties/crude_pc07.json";
        // Nothing is hashed (owner decisions 2026-10-26): the edit declares itself with the record's own revision.
        var property=JsonParser.parseString(resources.get(path)).getAsJsonObject();property.addProperty("molecular_weight_kg_per_mol",.31);
        property.addProperty("revision",property.get("revision").getAsString()+"-edited");resources.put(path,property.toString());
        var changed=MaterialCatalog.parse(resources);
        var pos=helper.absolutePos(new BlockPos(0,1,0));level.setBlock(pos,ModBlocks.FLUID_RESERVOIR.get().defaultBlockState(),3);
        long id=((FluidDeviceBlockEntity)level.getBlockEntity(pos)).fluidIdentity();
        IslandCoordinator.Snapshot[] before={null};
        java.util.function.Supplier<IslandCoordinator.Snapshot> snapshot=()->world.capture().checkpoint().islands().stream().map(FluidCheckpointCodec.IslandEntry::snapshot)
                .filter(s->s.graph().reservoirs().stream().anyMatch(n->n.id()==id)).findFirst().orElseThrow();
        helper.startSequence().thenWaitUntil(()->helper.assertTrue(snapshot.get().lastResult().isPresent(),"Waiting for initial FULL interval"))
        .thenExecute(()->{
            before[0]=snapshot.get();
            try {
                MaterialRuntime.publish(changed);
                // Exercise the real between-tick callback entry, before the next normal world tick.
                ProcessSolveCoordinator.drainCompletedCalculations(server);
                var held=snapshot.get();helper.assertTrue(held.status().startsWith("HELD: property data"),"Old properties were not held before completion routing");
                helper.assertTrue(held.clock().committedTick()==before[0].clock().committedTick(),"Reload advanced old scientific state");
                helper.assertTrue(world.view(id).status().contains("property data changed"),"Loaded view hides the property-data hold");
                var capture=world.capture();
                var model=FluidThermodynamics.forNetwork(original,FluidPresetCatalog.NETWORK_PACKAGE);
                var encoded=FluidCheckpointCodec.encode(capture.checkpoint(),capture.world().onlineTick(),key->key.on(model));
                var roundTrip=FluidCheckpointCodec.decode(encoded,key->key.on(model));
                boolean refused=false;try{FluidCheckpointCodec.decode(encoded,key->FluidThermodynamics.forNetwork(changed,key.packageId()));}
                catch(IllegalArgumentException expected){refused=true;}
                helper.assertTrue(refused,"Restart could silently reinterpret saved energy under changed science");
            }catch(RuntimeException|AssertionError failure){MaterialRuntime.publish(original);FluidWorldAuthority.refreshProperties(server);helper.fail("Property reload scenario failed: "+failure);}
        }).thenIdle(10).thenExecute(()->{
            try {
                var held=snapshot.get();
                helper.assertTrue(held.clock().committedTick()==before[0].clock().committedTick(),"Held world advanced");
                helper.assertTrue(held.clock().onlineTick()>before[0].clock().onlineTick(),"Held world discarded online debt");
                helper.assertTrue(held.graph().equals(before[0].graph()),"Property reload changed canonical inventory or energy");
            }finally{MaterialRuntime.publish(original);FluidWorldAuthority.refreshProperties(server);}
        }).thenWaitUntil(()->helper.assertTrue(world.view(id).committedTick()>before[0].clock().committedTick(),"Waiting for FULL recovery with restored qualified science"))
        .thenExecute(()->{
            helper.assertTrue(snapshot.get().graph().reservoirs().getFirst().inventory().equals(before[0].graph().reservoirs().getFirst().inventory()),"Recovery recreated or lost isolated nitrogen");
            level.removeBlock(pos,false);
        }).thenWaitUntil(()->helper.assertTrue(!world.capture().world().active().containsKey(id),"Waiting for topology cleanup")).thenSucceed();
    }
}
