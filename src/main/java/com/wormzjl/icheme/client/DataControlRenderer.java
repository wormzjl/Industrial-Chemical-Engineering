package com.wormzjl.icheme.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.runtime.control.*;
import com.wormzjl.icheme.runtime.instrument.InstrumentHosts;
import com.wormzjl.icheme.science.equipment.*;
import com.wormzjl.icheme.world.level.block.entity.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import java.util.*;

/** Draws structural socket/cable metadata only. No process readings or background network subscriptions. */
@EventBusSubscriber(modid=IChemE.MOD_ID,value=Dist.CLIENT)
public final class DataControlRenderer {
    private static final Set<ControlPanelBlockEntity> PANELS=Collections.newSetFromMap(new WeakHashMap<>());
    private static final ResourceLocation WHITE=ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID,"textures/misc/instrument.png");
    private static final RenderType SOLID=RenderType.entitySolid(WHITE),GHOST=RenderType.entityTranslucent(WHITE);
    private static final RenderType JACKET=RenderType.entityCutoutNoCull(net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS);
    private static final ResourceLocation CABLE_TEXTURE=ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID,"block/data_cable");
    private static final Map<DataPort,List<Mesh.Polygon>> PORTS=new HashMap<>();
    private DataControlRenderer(){}
    public static void loaded(ControlPanelBlockEntity box){PANELS.add(box);}public static void unloaded(ControlPanelBlockEntity box){PANELS.remove(box);}
    public static Map<UUID,DataPort> ports(Level level,Vec3 centre,double radius){
        var ports=new HashMap<UUID,DataPort>();
        for(var p:InstrumentHosts.dataPorts(level,centre,radius))ports.put(p.id(),p);
        for(var panel:List.copyOf(PANELS))if(panel.getLevel()==level&&!panel.isRemoved()&&panel.dataPort()!=null&&panel.dataPort().centre().distanceToSqr(centre)<radius*radius)ports.put(panel.dataPort().id(),panel.dataPort());
        return Map.copyOf(ports);
    }
    public static DataPort aim(Map<UUID,DataPort> ports,Vec3 eye,Vec3 look,double range){
        return ports.values().stream().filter(p->new AABB(p.centre(),p.centre()).inflate(.18).clip(eye,eye.add(look.scale(range))).isPresent()).min(Comparator.comparingDouble(p->p.centre().distanceToSqr(eye))).orElse(null);
    }
    private static boolean connected(Level level,DataPort port){
        if(!level.hasChunkAt(port.cell()))return false;var be=level.getBlockEntity(port.cell());
        var wires=be instanceof DataCableBlockEntity c?List.of(c.cable()):be instanceof PipeRackBlockEntity r?r.cables():List.<DataBus.Cable>of();
        for(var c:wires)if((c.geometry().joints()&(1<<port.face().ordinal()))!=0){var point=c.geometry().point(port.face().ordinal());if(new Vec3(point[0]+port.cell().getX(),point[1]+port.cell().getY(),point[2]+port.cell().getZ()).distanceToSqr(port.cablePoint())<1e-12)return true;}return false;
    }
    @SubscribeEvent public static void hud(RenderGuiEvent.Post event){
        var mc=Minecraft.getInstance();if(mc.player==null||mc.level==null||mc.screen!=null||!DataCableLayingClient.holding(mc.player))return;
        var port=aim(ports(mc.level,mc.player.getEyePosition(),mc.player.blockInteractionRange()+1),mc.player.getEyePosition(),mc.player.getLookAngle(),mc.player.blockInteractionRange());
        if(port==null||mc.hitResult!=null&&port.centre().distanceTo(mc.player.getEyePosition())>mc.hitResult.getLocation().distanceTo(mc.player.getEyePosition())+.2)return;
        String id=port.host()>0?String.format(Locale.ROOT,"#%04d",port.host()):"LC-"+port.id().toString().substring(0,8).toUpperCase(Locale.ROOT);
        String text=net.minecraft.network.chat.Component.translatable("gui.icheme.data.port",id).getString();
        if(mc.level.getBlockEntity(port.owner()) instanceof com.wormzjl.icheme.runtime.instrument.Instrument.Holder holder&&!holder.instruments().sensorIds().isEmpty())text+=" / "+String.join(", ",holder.instruments().sensorIds().values().stream().sorted().toList());
        var g=event.getGuiGraphics();var lines=mc.font.split(net.minecraft.network.chat.Component.literal(text),Math.max(32,Math.min(260,g.guiWidth()-16)));
        int width=lines.stream().mapToInt(mc.font::width).max().orElse(0),x=Math.max(4,Math.min(g.guiWidth()/2+14,g.guiWidth()-width-4)),y=g.guiHeight()/2+27;
        g.fill(x-3,y-2,x+width+3,y+lines.size()*10,0xe0202a31);for(int i=0;i<lines.size();i++)g.drawString(mc.font,lines.get(i),x,y+i*10,0xffe9d598,false);
    }
    @SubscribeEvent public static void render(RenderLevelStageEvent event){
        if(event.getStage()!=RenderLevelStageEvent.Stage.AFTER_PARTICLES)return;var mc=Minecraft.getInstance();if(mc.level==null||mc.player==null)return;
        var pose=event.getPoseStack();var camera=event.getCamera().getPosition();var buffers=mc.renderBuffers().bufferSource();
        if(PORTS.size()>2048)PORTS.clear();
        for(var port:ports(mc.level,mc.player.position(),96).values()){
            var polygons=PORTS.computeIfAbsent(port,p->DataPortGeometry.polygons(new double[]{0,0,0},new double[]{-p.face().getStepX(),-p.face().getStepY(),-p.face().getStepZ()}));
            draw(pose,buffers,camera,port.centre(),polygons,0,255);
            if(connected(mc.level,port)){var end=port.cablePoint().subtract(port.centre());drawCable(pose,buffers,camera,port.centre(),DataCableGeometry.path(List.of(new double[]{0,0,0},new double[]{end.x,end.y,end.z})));}
            if(DataCableLayingClient.holding(mc.player))LevelRenderer.renderLineBox(pose,buffers.getBuffer(RenderType.lines()),new AABB(port.centre(),port.centre()).inflate(.14).move(camera.scale(-1)),1,.8f,.22f,1);
        }
        for(var rack:PipeRackBlockEntity.clientRacks())if(rack.getLevel()==mc.level&&rack.getBlockPos().getCenter().distanceToSqr(camera)<96*96)for(var cable:rack.cables())drawCable(pose,buffers,camera,Vec3.atLowerCornerOf(rack.getBlockPos()),DataCableGeometry.path(RackGeometry.path(rack.rack(),rack.cableLane(cable))));
        DataCableLayingClient.draw(pose,buffers,camera);
        buffers.endBatch(SOLID);buffers.endBatch(JACKET);buffers.endBatch(GHOST);buffers.endBatch(RenderType.lines());
    }
    /** Rack lanes, flexible socket leads and chunk-baked cable blocks all sample the same insulating jacket. */
    private static void drawCable(PoseStack pose,MultiBufferSource buffers,Vec3 camera,Vec3 origin,List<Mesh.Polygon> polygons){
        var mc=Minecraft.getInstance();var sprite=mc.getModelManager().getAtlas(net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS).getSprite(CABLE_TEXTURE);var consumer=buffers.getBuffer(JACKET);
        var shift=origin.subtract(camera);pose.pushPose();pose.translate(shift.x,shift.y,shift.z);int light=LevelRenderer.getLightColor(mc.level,BlockPos.containing(origin));
        for(var q:polygons){var p=q.positions();var n=q.normals();var uv=q.uvs();
            for(int first=1;first+1<q.size();first+=2)for(int corner:new int[]{0,first,first+1,Math.min(first+2,q.size()-1)})consumer.addVertex(pose.last(),(float)p[corner*3],(float)p[corner*3+1],(float)p[corner*3+2]).setColor(255,255,255,255).setUv(sprite.getU((float)uv[corner*2]),sprite.getV((float)uv[corner*2+1])).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose.last(),(float)n[corner*3],(float)n[corner*3+1],(float)n[corner*3+2]);
        }pose.popPose();
    }
    public static void draw(PoseStack pose,MultiBufferSource buffers,Vec3 camera,Vec3 origin,List<Mesh.Polygon> polygons,int tint,int alpha){
        var consumer=buffers.getBuffer(alpha==255?SOLID:GHOST);var shift=origin.subtract(camera);pose.pushPose();pose.translate(shift.x,shift.y,shift.z);
        int light=alpha==255?LevelRenderer.getLightColor(Minecraft.getInstance().level,BlockPos.containing(origin)):LightTexture.FULL_BRIGHT;
        for(var q:polygons){int colour=tint!=0?tint:switch(q.material()){case OPENING->0x17282d;case GUARD->0xb99d38;case FLANGE->0x747a7d;default->0xa0a6a8;};var p=q.positions();var n=q.normals();
            for(int first=1;first+1<q.size();first+=2)for(int corner:new int[]{0,first,first+1,Math.min(first+2,q.size()-1)})consumer.addVertex(pose.last(),(float)p[corner*3],(float)p[corner*3+1],(float)p[corner*3+2]).setColor(colour>>16&255,colour>>8&255,colour&255,alpha).setUv(0,0).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose.last(),(float)n[corner*3],(float)n[corner*3+1],(float)n[corner*3+2]);
        }pose.popPose();
    }
}
