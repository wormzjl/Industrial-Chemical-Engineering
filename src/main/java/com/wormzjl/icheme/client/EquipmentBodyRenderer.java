package com.wormzjl.icheme.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.client.model.EquipmentShellModel;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.Mesh;
import com.wormzjl.icheme.science.equipment.Paint;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import java.util.LinkedHashMap;
import java.util.Map;

/** Render thread only: a coarse equipment body (or the normal tower shell) uploads once and draws once, independent of its occupied block count. */
@EventBusSubscriber(modid=IChemE.MOD_ID,value=Dist.CLIENT)
public final class EquipmentBodyRenderer {
    private record Key(EquipmentShape shape,Paint paint,int light,com.wormzjl.icheme.science.equipment.RenderDetail detail) {}
    private static final Map<Key,VertexBuffer> BUFFERS=new LinkedHashMap<>(16,.75f,true);
    private static TextureAtlasSprite atlasMarker;
    private EquipmentBodyRenderer() {}

    @SubscribeEvent public static void render(RenderLevelStageEvent event){
        if(event.getStage()!=RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS)return;
        var mc=Minecraft.getInstance();if(mc.level==null)return;
        var marker=EquipmentShellModel.sprite(Mesh.Material.CONCRETE);if(marker==null)return;
        if(marker!=atlasMarker){release();atlasMarker=marker;}
        var camera=event.getCamera().getPosition();var type=RenderType.solid();
        var detail=EquipmentShellModel.detail();
        net.minecraft.client.renderer.ShaderInstance shader=null;
        for(var tower:EquipmentShellBlockEntity.clientStructures(mc.level)){
            var shape=tower.shape();var origin=tower.origin();
            if(detail==com.wormzjl.icheme.science.equipment.RenderDetail.NORMAL&&!shape.kind().coolingTower())continue;
            if(!event.getFrustum().isVisible(new AABB(origin.getX(),origin.getY(),origin.getZ(),origin.getX()+shape.sizeX(),origin.getY()+shape.sizeY(),origin.getZ()+shape.sizeZ())))continue;
            int light=LevelRenderer.getLightColor(mc.level,BlockPos.containing(origin.getX()+shape.sizeX()/2.0,origin.getY()+shape.sizeY(),origin.getZ()+shape.sizeZ()/2.0));
            var key=new Key(shape,tower.paint(),light,detail);var buffer=BUFFERS.get(key);
            if(buffer==null){
                buffer=build(shape,tower.paint(),light,detail);if(buffer==null)continue;
                if(BUFFERS.size()>=64){var oldest=BUFFERS.entrySet().iterator();oldest.next().getValue().close();oldest.remove();}
                BUFFERS.put(key,buffer);
            }
            if(shader==null){
                type.setupRenderState();shader=RenderSystem.getShader();
                if(shader==null){type.clearRenderState();return;}
                shader.setDefaultUniforms(VertexFormat.Mode.TRIANGLES,event.getModelViewMatrix(),event.getProjectionMatrix(),mc.getWindow());
                if(shader.CHUNK_OFFSET!=null)shader.CHUNK_OFFSET.set(0F,0F,0F);
                shader.apply();
            }
            if(shader.MODEL_VIEW_MATRIX==null)continue;
            shader.MODEL_VIEW_MATRIX.set(new Matrix4f(event.getModelViewMatrix()).translate((float)(origin.getX()-camera.x),(float)(origin.getY()-camera.y),(float)(origin.getZ()-camera.z)));
            shader.MODEL_VIEW_MATRIX.upload();buffer.bind();buffer.draw();
        }
        if(shader!=null){shader.clear();VertexBuffer.unbind();type.clearRenderState();}
    }

    private static VertexBuffer build(EquipmentShape shape,Paint paint,int light,com.wormzjl.icheme.science.equipment.RenderDetail detail){
        var polygons=detail==com.wormzjl.icheme.science.equipment.RenderDetail.NORMAL?ShapeRasterizer.towerShell(shape):ShapeRasterizer.renderBody(shape,detail);int vertices=0;
        for(var polygon:polygons)vertices+=3*(polygon.size()-2);
        int[] tints={Paint.tint(paint.body()),Paint.tint(paint.structure())};
        if(vertices==0)return null;
        try(var bytes=new ByteBufferBuilder(vertices*DefaultVertexFormat.BLOCK.getVertexSize())){
            var builder=new BufferBuilder(bytes,VertexFormat.Mode.TRIANGLES,DefaultVertexFormat.BLOCK);
            for(var polygon:polygons){
                var sprite=EquipmentShellModel.sprite(polygon.material());if(sprite==null)return null;
                int channel=polygon.material().channel(),tint=channel<0||channel>=tints.length?0xFFFFFF:tints[channel];
                var positions=polygon.positions();var normals=polygon.normals();var uv=polygon.uvs();
                for(int corner=1;corner+1<polygon.size();corner++)for(int index:new int[]{0,corner,corner+1}){
                    int shade=(int)Math.min(255,EquipmentShellModel.shade(polygon,index)*ShapeRasterizer.towerStain(shape,new double[]{positions[3*index],positions[3*index+1],positions[3*index+2]}));
                    builder.addVertex((float)positions[3*index],(float)positions[3*index+1],(float)positions[3*index+2])
                        .setColor(shade*(tint>>16&255)/255,shade*(tint>>8&255)/255,shade*(tint&255)/255,255).setUv(sprite.getU((float)uv[2*index]),sprite.getV((float)uv[2*index+1]))
                        .setLight(light).setNormal((float)normals[3*index],(float)normals[3*index+1],(float)normals[3*index+2]);
                }
            }
            var mesh=builder.build();if(mesh==null)return null;
            var buffer=new VertexBuffer(VertexBuffer.Usage.STATIC);buffer.bind();buffer.upload(mesh);VertexBuffer.unbind();return buffer;
        }
    }
    public static void release(){BUFFERS.values().forEach(VertexBuffer::close);BUFFERS.clear();}
}
