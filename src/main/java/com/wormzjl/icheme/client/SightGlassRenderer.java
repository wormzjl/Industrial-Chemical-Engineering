package com.wormzjl.icheme.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.client.model.FluidDeviceModel;
import com.wormzjl.icheme.network.FluidNetwork;
import com.wormzjl.icheme.network.FluidNetwork.Target;
import com.wormzjl.icheme.runtime.fluid.PipePresentation;
import net.minecraft.core.BlockPos;
import com.wormzjl.icheme.science.equipment.Mesh;
import com.wormzjl.icheme.science.equipment.SightGlassGeometry;
import com.wormzjl.icheme.science.math.DeterministicMath;
import com.wormzjl.icheme.world.level.block.FluidDeviceBlock;
import com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Liquid and illustrative bubble motion are dynamic; metal/glass remain chunk-baked. Ordinary pipes do not render here. */
@EventBusSubscriber(modid=IChemE.MOD_ID,value=Dist.CLIENT)
public final class SightGlassRenderer {
    private static final double RANGE=120;
    private static final RenderType LIQUID=RenderType.entityTranslucentCull(InventoryMenu.BLOCK_ATLAS);
    private static final ResourceLocation WHITE=ResourceLocation.withDefaultNamespace("block/white_concrete");
    private record Surface(List<Mesh.Polygon> liquid,SightGlassGeometry.Window window){}
    private static final Map<SightGlassGeometry.Key,Surface> SURFACES=new HashMap<>();
    /**
     * Level of detail (batch 2026-11-09-lod-rendering, A3), by the window's diameter in pixels: liquid and bubbles at
     * 12 px or more (a bubble is a tenth of the window, a pixel there), liquid alone down to 2 px, then nothing.
     */
    private static final RenderLod.Tiers<BlockPos> GLASSES=new RenderLod.Tiers<>(12,2);
    private static final float[][] RING=ring();
    private static ClientLevel world;
    private static int age;
    private static com.wormzjl.icheme.science.equipment.RenderDetail detail;
    private SightGlassRenderer(){}

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        var mc=Minecraft.getInstance();
        if(world!=mc.level){if(mc.getConnection()!=null)track(List.of());else clear();SURFACES.clear();world=mc.level;age=0;}
        if(mc.level==null||mc.player==null||mc.getConnection()==null||age++%20!=0)return;
        var point=mc.player.position();var near=new ArrayList<FluidDeviceBlockEntity>();
        for(var entity:FluidDeviceBlockEntity.clientSightGlasses())if(!entity.isRemoved()&&entity.getLevel()==mc.level&&entity.fluidIdentity()>0
                &&FluidDeviceBlock.hasSightGlass(entity.getBlockState())&&entity.getBlockPos().distToCenterSqr(point)<=RANGE*RANGE)near.add(entity);
        near.sort(Comparator.comparingDouble((FluidDeviceBlockEntity entity)->entity.getBlockPos().distToCenterSqr(point)).thenComparingLong(entity->entity.getBlockPos().asLong()));
        track(near.stream().map(entity->new Target(entity.getBlockPos(),entity.fluidIdentity())).toList());
    }

    @SubscribeEvent public static void render(RenderLevelStageEvent event) {
        // Liquid must be drawn before the terrain's translucent glass writes depth in front of it.
        if(event.getStage()!=RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES)return;
        var mc=Minecraft.getInstance();var level=mc.level;if(level==null)return;
        var selected=com.wormzjl.icheme.client.model.EquipmentShellModel.detail();
        if(selected!=detail){SURFACES.clear();detail=selected;}
        var sprite=mc.getModelManager().getAtlas(InventoryMenu.BLOCK_ATLAS).getSprite(WHITE);
        float u=sprite.getU(.5F),v=sprite.getV(.5F);var camera=event.getCamera().getPosition();
        var pose=event.getPoseStack();var buffers=mc.renderBuffers().bufferSource();VertexConsumer consumer=null;
        var view=ClientConfig.lodView(event.getProjectionMatrix().m11(),mc.getWindow().getHeight());
        for(var entity:FluidDeviceBlockEntity.clientSightGlasses()) {
            var sample=display(entity);int argb=sample.argb();var pos=entity.getBlockPos();
            if(argb>>>24==0||entity.isRemoved()||entity.getLevel()!=level||!FluidDeviceBlock.hasSightGlass(entity.getBlockState())
                    ||pos.distToCenterSqr(camera)>128*128||!event.getFrustum().isVisible(new AABB(pos).inflate(.15)))continue;
            var key=FluidDeviceModel.sightGlassKey(entity.getBlockState(),entity.getModelData());
            if(!SightGlassGeometry.supports(key.pipe().connections()))continue;
            if(SURFACES.size()>=256)SURFACES.clear();
            var surface=SURFACES.computeIfAbsent(key,k->new Surface(SightGlassGeometry.build(k,false,selected).liquid(),SightGlassGeometry.window(k)));
            int tier=GLASSES.of(pos,view.pixels(2*surface.window().radius(),pos.distToCenterSqr(camera)));
            if(tier==GLASSES.hidden())continue;
            int light=LevelRenderer.getLightColor(level,pos);
            if(consumer==null)consumer=buffers.getBuffer(LIQUID);
            pose.pushPose();pose.translate(pos.getX()-camera.x,pos.getY()-camera.y,pos.getZ()-camera.z);
            for(var polygon:surface.liquid())for(int first=1;first+1<polygon.size();first+=2)
                for(int corner:new int[]{0,first,first+1,Math.min(first+2,polygon.size()-1)}) {
                    var p=polygon.positions();var n=polygon.normals();
                    consumer.addVertex(pose.last(),(float)p[3*corner],(float)p[3*corner+1],(float)p[3*corner+2])
                            .setColor(argb>>16&255,argb>>8&255,argb&255,argb>>>24).setUv(u,v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
                            .setNormal(pose.last(),(float)n[3*corner],(float)n[3*corner+1],(float)n[3*corner+2]);
                }
            if(tier==0&&sample.bubbles()>0){
                double ticks=level.getGameTime()+event.getPartialTick().getGameTimeDeltaPartialTick(false);
                var direction=sample.flowDirection();
                var run=switch(direction){case DOWN,UP->SightGlassGeometry.Axis.Y;case NORTH,SOUTH->SightGlassGeometry.Axis.Z;case WEST,EAST->SightGlassGeometry.Axis.X;};
                // A stale sample can outlive a static pipe/window rotation until the next scheduled publication.
                if(run!=surface.window().axis()){
                    boolean positive=switch(direction){case UP,SOUTH,EAST->true;default->false;};
                    var motion=SightGlassGeometry.Motion.along(surface.window().axis(),run,positive);
                    bubbles(pose,consumer,surface.window(),SightGlassGeometry.bubbles(ticks,entity.fluidIdentity()^pos.asLong(),sample.bubbles(),motion),argb,sample.bubbles(),light,u,v);
                }
            }
            pose.popPose();
        }
        if(consumer!=null)buffers.endBatch(LIQUID);
    }

    private static void bubbles(PoseStack pose,VertexConsumer consumer,SightGlassGeometry.Window window,List<SightGlassGeometry.Bubble> bubbles,int argb,int strength,int light,float u,float v){
        int colour=SightGlassGeometry.bubbleColour(argb,strength),red=colour>>16&255,green=colour>>8&255,blue=colour&255,alpha=colour>>>24;
        pose.pushPose();pose.translate(.5,.5,.5);
        switch(window.axis()){case X->pose.mulPose(Axis.YP.rotationDegrees(90));case Y->pose.mulPose(Axis.XP.rotationDegrees(-90));default->{}}
        for(int side:new int[]{1,-1}){
            pose.pushPose();if(side<0)pose.mulPose(Axis.YP.rotationDegrees(180));pose.translate(0,0,window.plane());
            for(var bubble:bubbles){
                if(bubble.radius()<.0001)continue;
                for(int i=0;i<RING.length;i++)for(int corner=0;corner<4;corner++){
                    int angle=(corner==0||corner==3)?i:(i+1)%RING.length;double radius=bubble.radius()*(corner<2?1:.72);
                    consumer.addVertex(pose.last(),(float)(window.radius()*(side*bubble.x()+RING[angle][0]*radius)),(float)(window.radius()*(bubble.y()+RING[angle][1]*radius)),0)
                            .setColor(red,green,blue,alpha).setUv(u,v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose.last(),0,0,1);
                }
            }
            pose.popPose();
        }
        pose.popPose();
    }
    private static float[][] ring(){var points=new float[12][2];for(int i=0;i<points.length;i++){double a=2*Math.PI*i/points.length;points[i][0]=(float)DeterministicMath.cos(a);points[i][1]=(float)DeterministicMath.sin(a);}return points;}

    // ---- the client's sight-glass displays ----
    // Ephemeral and identity-bound; never obtained through a block update tag. Client thread only.
    private static final Map<Target,PipePresentation.SightGlass> DISPLAYS=new HashMap<>();
    private static final Set<Target> WATCHED=new HashSet<>();
    public static void accept(Target target,PipePresentation.SightGlass sample) {
        var level=Minecraft.getInstance().level;
        if(level!=null&&WATCHED.contains(target)&&FluidDeviceBlock.hasSightGlass(level.getBlockState(target.position()))
                &&level.getBlockEntity(target.position()) instanceof FluidDeviceBlockEntity entity&&entity.fluidIdentity()==target.identity())DISPLAYS.put(target,sample);
    }
    static PipePresentation.SightGlass display(FluidDeviceBlockEntity entity){long id=entity.fluidIdentity();return id<=0?PipePresentation.SightGlass.CLEAR:DISPLAYS.getOrDefault(new Target(entity.getBlockPos(),id),PipePresentation.SightGlass.CLEAR);}
    /** Nearest first, bounded independently of tanks. */
    static void track(List<Target> near) {
        var wanted=new LinkedHashSet<Target>();for(var target:near){if(wanted.size()>=FluidNetwork.MAXIMUM_WATCHED)break;wanted.add(target);}
        var watch=new ArrayList<Target>();for(var target:wanted)if(!WATCHED.contains(target))watch.add(target);
        var unwatch=new ArrayList<Target>();for(var target:WATCHED)if(!wanted.contains(target))unwatch.add(target);
        WATCHED.removeAll(unwatch);unwatch.forEach(DISPLAYS::remove);WATCHED.addAll(watch);
        FluidNetwork.request(FluidNetwork.Watch.SIGHT_GLASSES,watch,unwatch);
    }
    /** A replaced/unloaded client binding must explicitly request again even if its chunk reloads within one poll. */
    public static void forget(BlockPos pos) {
        var gone=WATCHED.stream().filter(t->t.position().equals(pos)).toList();
        WATCHED.removeAll(gone);gone.forEach(DISPLAYS::remove);
        if(Minecraft.getInstance().getConnection()!=null)FluidNetwork.request(FluidNetwork.Watch.SIGHT_GLASSES,List.of(),gone);
    }
    public static void clear(){DISPLAYS.clear();WATCHED.clear();}
}
