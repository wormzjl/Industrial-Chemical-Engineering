package com.wormzjl.icheme.client;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.science.equipment.CoolingTowerBasin;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Render thread only. One cached surface per shape, driven entirely by scheduled open-basin snapshots. */
@EventBusSubscriber(modid=IChemE.MOD_ID,value=Dist.CLIENT)
public final class CoolingTowerBasinRenderer {
    private static final Map<EquipmentShape,List<CoolingTowerBasin.Triangle>> SURFACES=new HashMap<>();
    private CoolingTowerBasinRenderer() {}

    @SubscribeEvent public static void render(RenderLevelStageEvent event){
        if(event.getStage()!=RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES)return;
        var mc=Minecraft.getInstance();if(mc.level==null)return;
        var camera=event.getCamera().getPosition();var pose=event.getPoseStack();
        var sprite=LiquidRenderer.sprite();
        float u=sprite.getU(.5f),v=sprite.getV(.5f);
        var buffers=mc.renderBuffers().bufferSource();boolean drawn=false;
        double ticks=mc.level.getGameTime()+event.getPartialTick().getGameTimeDeltaPartialTick(false);
        for(var cell:EquipmentShellBlockEntity.clientTankCells()){
            var shape=cell.shape();var origin=cell.origin();
            if(cell.isRemoved()||cell.getLevel()!=mc.level||shape==null||origin==null||!shape.kind().coolingTower())continue;
            var display=TankRenderer.get(cell.getBlockPos(),ticks);if(display==null||display.waterLevel()<=0)continue;
            double height=CoolingTowerBasin.height(display.waterLevel());
            if(!event.getFrustum().isVisible(new AABB(origin.getX(),origin.getY(),origin.getZ(),origin.getX()+shape.sizeX(),origin.getY()+3,origin.getZ()+shape.sizeZ())))continue;
            if(SURFACES.size()>64)SURFACES.clear();
            var surface=SURFACES.computeIfAbsent(shape,CoolingTowerBasin::surface);
            int tint=display.waterArgb();
            int light=LevelRenderer.getLightColor(mc.level,BlockPos.containing(origin.getX()+shape.sizeX()/2.0,origin.getY()+height+.1,origin.getZ()+shape.sizeZ()/2.0));
            pose.pushPose();pose.translate(origin.getX()-camera.x,origin.getY()+height-camera.y,origin.getZ()-camera.z);
            var consumer=buffers.getBuffer(LiquidRenderer.TYPE);var matrix=pose.last();
            for(var triangle:surface)for(var point:List.of(triangle.a(),triangle.b(),triangle.c(),triangle.c()))
                LiquidRenderer.vertex(consumer,matrix,(float)point.x(),0,(float)point.z(),tint,u,v,light,0,1,0);
            pose.popPose();drawn=true;
        }
        if(drawn)buffers.endBatch(LiquidRenderer.TYPE);
    }
}
