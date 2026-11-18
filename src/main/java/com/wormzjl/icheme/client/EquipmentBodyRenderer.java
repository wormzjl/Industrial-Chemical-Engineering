package com.wormzjl.icheme.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.client.model.EquipmentShellModel;
import com.wormzjl.icheme.client.model.PolygonBake;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.Mesh;
import com.wormzjl.icheme.science.equipment.Paint;
import com.wormzjl.icheme.science.equipment.RenderDetail;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;
import com.wormzjl.icheme.world.level.block.entity.LoadedEquipment;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Render thread only: coarse equipment bodies (and the normal tower shells), each drawn whole, independent of its
 * occupied block count. The bodies whose origins share a {@link #REGION}-block square are merged into one mesh and drawn
 * in one call (batch 2026-11-09-lod-rendering, HEAVY_BENCH_REVIEW.md: one draw per structure cost low detail about
 * 0.8 ms over 250 structures). A changed region is merged again, one per frame; meanwhile its last mesh stands.
 */
@EventBusSubscriber(modid=IChemE.MOD_ID,value=Dist.CLIENT)
public final class EquipmentBodyRenderer {
    /** The side of a region, blocks. */
    private static final int REGION=32;
    /** Each structure's light is read again once a second, the structures spread over the second. */
    private static final int LIGHT_TICKS=20;

    private record Key(EquipmentShape shape,Paint paint,RenderDetail detail) {}
    /** A structure in a region, as its region's mesh holds it. */
    private record Member(BlockPos origin,EquipmentShape shape,Paint paint,int light) {}
    private static final class Region {
        final BlockPos corner;
        long signature;
        boolean dirty;
        List<Member> members=List.of();
        VertexBuffer buffer;
        AABB box;
        Region(BlockPos corner){this.corner=corner;}
        void close(){if(buffer!=null)buffer.close();buffer=null;}
    }
    private static final class Light {int value;long due=Long.MIN_VALUE;}

    /**
     * Bodies by shape, paint and detail, each one's triangles from its origin without light ({@link PolygonBake}); equal
     * structures share one. Bounded: a region merge rebuilds what it misses.
     */
    private static final Map<Key,PolygonBake.Triangles> BODIES=new LinkedHashMap<>(16,.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<Key,PolygonBake.Triangles> eldest){return size()>32;}
    };
    private static final Map<Long,Region> REGIONS=new HashMap<>();
    private static final Map<LoadedEquipment.Structure,Light> LIGHTS=new WeakHashMap<>();
    private static final Matrix4f POSE=new Matrix4f();
    private static TextureAtlasSprite atlasMarker;
    private static RenderDetail builtDetail;
    /** Whether the bodies kept bake the sheen ({@code ShellVariant.sheen}): off while the reflection pass draws. */
    private static boolean builtSheen;
    /** What the last region map was built from: the frame's hash of every member and its count; -1 after {@link #release} (C14). */
    private static long seenHash;
    private static int seenCount=-1;
    private EquipmentBodyRenderer() {}

    @SubscribeEvent public static void render(RenderLevelStageEvent event){
        if(event.getStage()!=RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS)return;
        var mc=Minecraft.getInstance();if(mc.level==null)return;
        var marker=EquipmentShellModel.sprite(Mesh.Material.CONCRETE);if(marker==null)return;
        var detail=EquipmentShellModel.detail();boolean sheen=EquipmentShellModel.variant().sheen();
        if(marker!=atlasMarker||detail!=builtDetail||sheen!=builtSheen){release();atlasMarker=marker;builtDetail=detail;builtSheen=sheen;}
        long tick=mc.level.getGameTime();

        // Each structure's light on its timer, and a hash of what the region map is built from: the level, every member's
        // origin, shape and paint objects and light. A frame whose hash and count are the last ones allocates nothing and
        // keeps the map (finding C14, batch 2026-11-13-codebase-review): the members, and so every region signature, are equal.
        var structures=EquipmentShellBlockEntity.clientStructures(mc.level);
        long hash=System.identityHashCode(mc.level);int count=0;
        for(var structure:structures){
            var shape=structure.shape();var origin=structure.origin();
            if(shape==null||detail==RenderDetail.NORMAL&&!shape.kind().coolingTower())continue;
            var light=LIGHTS.computeIfAbsent(structure,s->new Light());
            if(tick>=light.due||light.due-tick>2L*LIGHT_TICKS){
                light.value=LevelRenderer.getLightColor(mc.level,BlockPos.containing(origin.getX()+shape.sizeX()/2.0,origin.getY()+shape.sizeY(),origin.getZ()+shape.sizeZ()/2.0));
                light.due=tick+LIGHT_TICKS+Math.floorMod(origin.hashCode(),LIGHT_TICKS);
            }
            hash=(((hash*31+origin.asLong())*31+System.identityHashCode(shape))*31+System.identityHashCode(structure.paint()))*31+light.value;
            count++;
        }
        if(hash!=seenHash||count!=seenCount){seenHash=hash;seenCount=count;regions(structures,detail);}

        // Merge at most one changed region this frame, one in view first.
        var frustum=event.getFrustum();Region next=null;
        for(var region:REGIONS.values()){
            if(!region.dirty)continue;
            if(region.box!=null&&frustum.isVisible(region.box)){next=region;break;}
            if(next==null)next=region;
        }
        if(next!=null)merge(next,detail);

        var camera=event.getCamera().getPosition();var type=RenderType.solid();
        net.minecraft.client.renderer.ShaderInstance shader=null;
        for(var region:REGIONS.values()){
            if(region.buffer==null||!frustum.isVisible(region.box))continue;
            if(shader==null){
                type.setupRenderState();shader=RenderSystem.getShader();
                if(shader==null){type.clearRenderState();return;}
                shader.setDefaultUniforms(VertexFormat.Mode.TRIANGLES,event.getModelViewMatrix(),event.getProjectionMatrix(),mc.getWindow());
                if(shader.CHUNK_OFFSET!=null)shader.CHUNK_OFFSET.set(0F,0F,0F);
                shader.apply();
            }
            if(shader.MODEL_VIEW_MATRIX==null)continue;
            var c=region.corner;
            shader.MODEL_VIEW_MATRIX.set(POSE.set(event.getModelViewMatrix()).translate((float)(c.getX()-camera.x),(float)(c.getY()-camera.y),(float)(c.getZ()-camera.z)));
            shader.MODEL_VIEW_MATRIX.upload();region.buffer.bind();region.buffer.draw();
        }
        if(shader!=null){shader.clear();VertexBuffer.unbind();type.clearRenderState();}
    }

    /** This frame's members of each region, from the structures as they are now; a region whose signature changed is marked to merge. */
    private static void regions(java.util.Collection<LoadedEquipment.Structure> structures,RenderDetail detail){
        var now=new HashMap<Long,List<Member>>();
        for(var structure:structures){
            var shape=structure.shape();var origin=structure.origin();
            if(shape==null||detail==RenderDetail.NORMAL&&!shape.kind().coolingTower())continue;
            long id=(long)Math.floorDiv(origin.getX(),REGION)<<32^Math.floorDiv(origin.getZ(),REGION)&0xffffffffL;
            now.computeIfAbsent(id,k->new ArrayList<>()).add(new Member(origin,shape,structure.paint(),LIGHTS.get(structure).value));
        }
        var gone=REGIONS.entrySet().iterator();
        while(gone.hasNext()){var e=gone.next();if(!now.containsKey(e.getKey())){e.getValue().close();gone.remove();}}
        for(var e:now.entrySet()){
            long id=e.getKey();
            var region=REGIONS.get(id);
            if(region==null){region=new Region(new BlockPos((int)(id>>32)*REGION,0,(int)id*REGION));region.dirty=true;REGIONS.put(id,region);}
            long signature=signature(e.getValue());
            if(region.dirty||signature!=region.signature){region.signature=signature;region.members=e.getValue();region.dirty=true;}
        }
    }

    /** Changes with a member's origin, shape object, paint object or light (identities: a frame hashes no shape). */
    private static long signature(List<Member> members){
        long h=members.size();
        for(var m:members)h=((h*31+m.origin().asLong())*31+System.identityHashCode(m.shape()))*31+System.identityHashCode(m.paint())*31L+m.light();
        return h;
    }

    /** One region's mesh from its members' bodies, offset from the region's corner, each lit at its own top. */
    private static void merge(Region region,RenderDetail detail){
        region.dirty=false;
        var bodies=new ArrayList<PolygonBake.Triangles>(region.members.size());int vertices=0;
        double x0=Double.MAX_VALUE,y0=Double.MAX_VALUE,z0=Double.MAX_VALUE,x1=-Double.MAX_VALUE,y1=-Double.MAX_VALUE,z1=-Double.MAX_VALUE;
        for(var m:region.members){
            var body=BODIES.get(new Key(m.shape(),m.paint(),detail));
            if(body==null){body=body(m.shape(),m.paint(),detail);if(body==null){region.dirty=true;return;}BODIES.put(new Key(m.shape(),m.paint(),detail),body);}
            bodies.add(body);vertices+=body.vertices();
            var o=m.origin();var s=m.shape();
            x0=Math.min(x0,o.getX());y0=Math.min(y0,o.getY());z0=Math.min(z0,o.getZ());
            x1=Math.max(x1,o.getX()+s.sizeX());y1=Math.max(y1,o.getY()+s.sizeY());z1=Math.max(z1,o.getZ()+s.sizeZ());
        }
        region.close();
        if(vertices==0)return;
        var c=region.corner;
        try(var bytes=new ByteBufferBuilder(vertices*DefaultVertexFormat.BLOCK.getVertexSize())){
            var builder=new BufferBuilder(bytes,VertexFormat.Mode.TRIANGLES,DefaultVertexFormat.BLOCK);
            for(int i=0;i<bodies.size();i++){
                var b=bodies.get(i);var m=region.members.get(i);
                float dx=m.origin().getX()-c.getX(),dy=m.origin().getY()-c.getY(),dz=m.origin().getZ()-c.getZ();
                var p=b.positions();var uv=b.uvs();var n=b.normals();var colour=b.colours();
                for(int v=0;v<colour.length;v++)
                    builder.addVertex(p[3*v]+dx,p[3*v+1]+dy,p[3*v+2]+dz).setColor(colour[v]).setUv(uv[2*v],uv[2*v+1]).setLight(m.light()).setNormal(n[3*v],n[3*v+1],n[3*v+2]);
            }
            var mesh=builder.build();if(mesh==null)return;
            var buffer=new VertexBuffer(VertexBuffer.Usage.STATIC);buffer.bind();buffer.upload(mesh);VertexBuffer.unbind();
            region.buffer=buffer;region.box=new AABB(x0,y0,z0,x1,y1,z1);
        }
    }

    /**
     * A body's triangles as the shell model shades and tints them ({@link PolygonBake#triangles}), each corner's shade
     * weathered by {@link ShapeRasterizer#towerStain} (1 for all but a natural tower); null while a sprite is missing.
     */
    private static PolygonBake.Triangles body(EquipmentShape shape,Paint paint,RenderDetail detail){
        var polygons=detail==RenderDetail.NORMAL?ShapeRasterizer.towerShell(shape):ShapeRasterizer.renderBody(shape,detail);
        int[] tints={Paint.tint(paint.body()),Paint.tint(paint.structure())};
        return PolygonBake.triangles(polygons,tints,EquipmentShellModel.ATLAS,(polygon,index)->{
            var p=polygon.positions();
            return (int)Math.min(255,EquipmentShellModel.shade(polygon,index,builtSheen)*ShapeRasterizer.towerStain(shape,new double[]{p[3*index],p[3*index+1],p[3*index+2]}));
        });
    }

    public static void release(){REGIONS.values().forEach(Region::close);REGIONS.clear();BODIES.clear();seenCount=-1;}
}
