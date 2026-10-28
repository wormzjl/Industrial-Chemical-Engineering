package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.Paint;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.world.level.block.EquipmentInteriorBlock;
import com.wormzjl.icheme.world.level.block.EquipmentStructure;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.gametest.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Probe of the skylight GameTest fix (batch 2026-10-27-skirt-shadow), not a gate: the light engine's thread is put to
 * sleep for {@link #STALL_MS} just before the drum's interior turns opaque, as a loaded machine delays it. The old form
 * polls the floor's light against the tick budget; the new form waits for the engine's run of the queued work. In the
 * rule-off run ({@code -Dicheme.probe.ruleOff=true}, interior cells never pass skylight) only the gate as merged runs.
 */
@GameTestHolder("icheme_skylight_probe")
@PrefixGameTestTemplate(false)
public final class SkylightWaitProbe {
    private SkylightWaitProbe() {}
    static final long STALL_MS=8000;
    static final boolean RULE_OFF=Boolean.getBoolean("icheme.probe.ruleOff");

    @GameTest(template="empty",timeoutTicks=400,batch="probe-gate")
    public static void theGateAsMerged(GameTestHelper helper) {
        EquipmentCompatGameTests.aSkirtsFloorTakesTheSkylightAboveTheDrum(helper);
    }

    @GameTest(template="empty",timeoutTicks=400,batch="probe-old")
    public static void theOldPollUnderAStalledLightEngine(GameTestHelper helper) {
        if(RULE_OFF){helper.succeed();return;}
        run(helper,false);
    }

    @GameTest(template="empty",timeoutTicks=400,batch="probe-new")
    public static void theNewWaitUnderAStalledLightEngine(GameTestHelper helper) {
        if(RULE_OFF){helper.succeed();return;}
        run(helper,true);
    }

    private static void run(GameTestHelper helper,boolean wait) {
        String form=wait?"new":"old";
        var level=helper.getLevel();var world=FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var shape=EquipmentShape.drum(6,4,EquipmentShape.Orientation.VERTICAL,EquipmentShape.Heading.NORTH,3);
        var origin=helper.absolutePos(new BlockPos(1,1,1));var layout=ShapeRasterizer.layout(shape);
        int mid=shape.sizeX()/2;var floor=origin.offset(mid,0,mid);var above=origin.offset(mid,shape.sizeY(),mid);
        var node=ShapeRasterizer.fluidCell(shape);var nodeAt=origin.offset(node.x(),node.y(),node.z());
        var position=new PhysicalFluidTopology.Position(level.dimension().location().toString(),nodeAt.getX(),nodeAt.getY(),nodeAt.getZ());
        EquipmentStructure.place(level,origin,shape,Paint.DEFAULT,Integer.MAX_VALUE);
        var high=origin.offset(shape.sizeX()-1,shape.sizeY(),shape.sizeZ()-1);
        CompletableFuture<?>[] light={null};AtomicBoolean stalled=new AtomicBoolean();long[] t0={0,0};long[] printed={-1};
        Runnable opaque=()->{
            for(var c:layout.interior()) {
                var at=origin.offset(c.x(),c.y(),c.z());
                level.setBlock(at,level.getBlockState(at).setValue(EquipmentInteriorBlock.SKYLIGHT,false),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);
            }
            t0[0]=helper.getTick();t0[1]=System.nanoTime();
            System.out.println("PROBE "+form+": interior opaque at tick "+t0[0]);
        };
        Runnable report=()->{
            long ticks=helper.getTick()-t0[0];
            if(ticks/50!=printed[0]){printed[0]=ticks/50;System.out.println("PROBE "+form+": "+ticks+" ticks, "+(System.nanoTime()-t0[1])/1_000_000+" ms since the interior turned opaque; floor "+level.getBrightness(LightLayer.SKY,floor));}
        };
        var sequence=helper.startSequence().thenWaitUntil(()->helper.assertTrue(world.at(position).isPresent(),"Waiting for the drum's registration"));
        if(wait)sequence.thenExecute(()->light[0]=lightQueued(level,origin,high)).thenWaitUntil(()->waitForLight(level,light[0]))
                .thenExecute(()->helper.assertTrue(level.getBrightness(LightLayer.SKY,floor)==level.getBrightness(LightLayer.SKY,above),"the floor's skylight "+level.getBrightness(LightLayer.SKY,floor)));
        else sequence.thenWaitUntil(()->helper.assertTrue(level.getBrightness(LightLayer.SKY,floor)==level.getBrightness(LightLayer.SKY,above),"Waiting for the floor's skylight "+level.getBrightness(LightLayer.SKY,floor)));
        sequence.thenExecute(()->level.getChunkSource().getLightEngine().waitForPendingTasks(floor.getX()>>4,floor.getZ()>>4).thenRun(()->{
            stalled.set(true);System.out.println("PROBE "+form+": light engine stalled "+STALL_MS+" ms on "+Thread.currentThread().getName());
            try{Thread.sleep(STALL_MS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
        })).thenWaitUntil(()->helper.assertTrue(stalled.get(),"Waiting for the light engine's stall")).thenExecute(opaque);
        if(wait)sequence.thenExecute(()->light[0]=lightQueued(level,origin,high)).thenWaitUntil(()->{report.run();waitForLight(level,light[0]);})
                .thenExecute(()->{report.run();helper.assertTrue(level.getBrightness(LightLayer.SKY,floor)<level.getBrightness(LightLayer.SKY,above),"the old shadow on the floor: "+level.getBrightness(LightLayer.SKY,floor));});
        else sequence.thenWaitUntil(()->{report.run();helper.assertTrue(level.getBrightness(LightLayer.SKY,floor)<level.getBrightness(LightLayer.SKY,above),"Waiting for the old shadow on the floor: "+level.getBrightness(LightLayer.SKY,floor));});
        sequence.thenExecute(()->{
            System.out.println("PROBE "+form+": shadow seen after "+(helper.getTick()-t0[0])+" ticks, "+(System.nanoTime()-t0[1])/1_000_000+" ms");
            level.removeBlock(nodeAt,false);
        }).thenWaitUntil(()->helper.assertTrue(world.at(position).isEmpty(),"Waiting for the drum's registration to leave")).thenSucceed();
    }

    // Copies of EquipmentCompatGameTests' private helpers.
    private static CompletableFuture<?> lightQueued(ServerLevel level,BlockPos low,BlockPos high) {
        var engine=level.getChunkSource().getLightEngine();var chunks=new ArrayList<CompletableFuture<?>>();
        for(int x=low.getX()>>4;x<=high.getX()>>4;x++)for(int z=low.getZ()>>4;z<=high.getZ()>>4;z++)chunks.add(engine.waitForPendingTasks(x,z));
        return CompletableFuture.allOf(chunks.toArray(CompletableFuture[]::new));
    }

    private static void waitForLight(ServerLevel level,CompletableFuture<?> light) {
        level.getChunkSource().getLightEngine().tryScheduleUpdate();
        try{light.get(50,TimeUnit.MILLISECONDS);}
        catch(TimeoutException e){throw new GameTestAssertException("Waiting for the light engine");}
        catch(InterruptedException|ExecutionException e){throw new IllegalStateException("the light engine's wait",e);}
    }
}
