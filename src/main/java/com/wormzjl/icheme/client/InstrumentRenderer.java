package com.wormzjl.icheme.client;

import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Axis;
import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.network.FluidNetwork;
import net.minecraft.core.BlockPos;
import com.wormzjl.icheme.runtime.instrument.*;
import com.wormzjl.icheme.science.equipment.*;
import com.wormzjl.icheme.world.item.*;
import com.wormzjl.icheme.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import java.util.*;

/** Shared metre-sized geometry for installed dials and placement ghosts. Only needles read live publications. */
@EventBusSubscriber(modid=IChemE.MOD_ID,value=Dist.CLIENT)
public final class InstrumentRenderer {
    private InstrumentRenderer(){}
    /** Synced structural labels, shared by the host's fluid and machinery GUIs; no process subscription or solve. */
    public static List<String> identities(net.minecraft.world.level.Level level,BlockPos position){
        if(level==null)return List.of();
        var host=InstrumentHosts.at(level,position);
        var metadata=host!=null?host.metadata():level.getBlockEntity(position) instanceof Instrument.Holder holder?holder.instruments():null;
        if(metadata==null)return List.of();
        return metadata.sensors().stream().filter(s->metadata.sensorIds().containsKey(s.instance())).map(s->{
            String label=s.kind()==Sensor.Kind.SPEED?"item.icheme.speed_sensor":"gui.icheme.fluid."+switch(s.kind()){
                case PRESSURE->"pressure_probe";case TEMPERATURE->"temperature_probe";case FLOW->"flowmeter";
                case LEVEL->s.key().mount().equals("level_boot")?"boot_level":"body_level";case SPEED,ALARM->throw new IllegalStateException();};
            return metadata.sensorIds().get(s.instance())+" / "+Component.translatable(label).getString();
        }).sorted().toList();
    }
    private static final ResourceLocation WHITE=ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID,"textures/misc/instrument.png");
    private static final RenderType TYPE=RenderType.entitySolid(WHITE);
    private static final RenderType GHOST=RenderType.entityTranslucentCull(WHITE);
    private record Connector(List<Double> tap,boolean bottom,boolean temperature,double probeDepth){}
    private static final Map<Connector,List<InstrumentGeometry.Quad>> CONNECTORS=new HashMap<>();
    // ---- level of detail (batch 2026-11-09-lod-rendering, LOD_RENDERING_PLAN.md A2): sizes in metres, tiers in pixels ----
    /** A dial's face, a transmitter's head, a connector's stem and a wiring ribbon. */
    private static final double DIAL=2*InstrumentGeometry.RADIUS,HEAD=.23,STEM=.028,WIRE=.025;
    /** Dial: full (text, 48 sides), plain (24 sides, no text), far (12 sides, needle only), hidden. Connectors end with plain, where the stem reaches a pixel. */
    private static final int FULL=0,PLAIN=1,FAR=2;
    private static final RenderLod.Tiers<Seen> DIALS=new RenderLod.Tiers<>(48,12,3);
    /** Transmitter: symbol and ID (to 4 m at 1080 lines), symbol (to 13 m), head only (to 59 m), hidden. */
    private static final RenderLod.Tiers<Seen> HEADS=new RenderLod.Tiers<>(44,14,3);
    /** A structure's wiring while its ribbon covers a pixel. */
    private static final RenderLod.Tiers<Seen> WIRES=new RenderLod.Tiers<>(1);
    private record Seen(long host,String mount){}
    /** Hosts with installed instruments, gathered every {@link #HOST_TICKS} ticks while no placing item is held. */
    private static final int HOST_TICKS=5;
    private static List<InstrumentHosts.Host> shown=List.of();
    private static long shownAt=Long.MIN_VALUE;
    private static net.minecraft.world.level.Level shownIn;
    @SubscribeEvent public static void render(RenderLevelStageEvent event) {
        if(event.getStage()!=RenderLevelStageEvent.Stage.AFTER_PARTICLES)return;
        var mc=Minecraft.getInstance();if(mc.level==null||mc.player==null)return;
        var camera=event.getCamera().getPosition();var buffers=mc.renderBuffers().bufferSource();var pose=event.getPoseStack();
        var view=ClientConfig.lodView(event.getProjectionMatrix().m11(),mc.getWindow().getHeight());var frustum=event.getFrustum();
        GaugeSettings draft=null;Sensor.Kind sensorDraft=null;boolean wrench=false,levelGauge=false;
        for(var hand:net.minecraft.world.InteractionHand.values()) {
            var stack=mc.player.getItemInHand(hand);
            if(stack.is(ModItems.LIQUID_LEVEL_GAUGE.get()))levelGauge=true;
            if(stack.getItem() instanceof SensorItem sensorItem)sensorDraft=sensorItem.kind();
            if(stack.getItem() instanceof GaugeItem)draft=stack.get(ModItems.GAUGE_SETTINGS.get());
            if(stack.getItem() instanceof AccessoryWrenchItem&&mc.player.isShiftKeyDown())wrench=true;
        }
        boolean placing=draft!=null||sensorDraft!=null||wrench||levelGauge;
        var hosts=placing?InstrumentHosts.nearby(mc.level,mc.player.position(),120,false):shownHosts(mc);
        var selected=draft==null?null:InstrumentHosts.select(hosts,mc.player.getEyePosition(),mc.player.getLookAngle(),mc.player.blockInteractionRange(),draft.kind(),false);
        for(var host:hosts) {
            if(host.entity().isRemoved())continue;
            drawWiring(pose,buffers,host,camera,view,frustum);
            for(var sensor:host.metadata().sensors())for(var mount:Sensor.mounts(host,sensor.kind()))if(mount.id().equals(sensor.key().mount())){
                double d2=mount.centre().distanceToSqr(camera);int tier=HEADS.of(new Seen(host.identity(),mount.id()),view.pixels(HEAD,d2));
                if(tier==HEADS.hidden()||!visible(frustum,mount))continue;
                drawSensor(pose,buffers,mount,camera,sensor.kind(),255,host.metadata().sensorIds().getOrDefault(sensor.instance(),""),!host.pipe(),tier,view.pixels(STEM,d2)>=1);
            }
            if(sensorDraft!=null)for(var mount:Sensor.mounts(host,sensorDraft)){
                if(mount.centre().distanceToSqr(mc.player.getEyePosition())>64)continue;
                boolean occupied=host.metadata().sensors().stream().anyMatch(s->s.key().mount().equals(mount.id()))||host.metadata().installed().stream().anyMatch(s->s.key().mount().equals(mount.id()))||host.pipe()&&!mount.id().equals("valve_sensor")&&host.occupied(mount);
                boolean blocked=occupied||!InstrumentHosts.clear(mc.level,host,mount);
                if(!occupied){drawSensor(pose,buffers,mount,camera,sensorDraft,70,"",!host.pipe(),FULL,true);drawRootGhost(pose,buffers,host,mount,camera,0x779ba9,70);}
                outline(pose,buffers,mount,camera,blocked?0xad7770:0x73a38e);
            }
            for(var installed:host.metadata().installed()) {
                var mount=host.mount(installed.key().mount());if(mount==null)continue;
                int tier=DIALS.of(new Seen(host.identity(),mount.id()),view.pixels(DIAL,mount.centre().distanceToSqr(camera)));
                if(tier==DIALS.hidden()||!visible(frustum,mount))continue;
                var display=get(new FluidNetwork.InstrumentTarget(host.entity().getBlockPos(),installed.key(),installed.instance()));
                double ratio=display==null||display.sample().availability()==Instrument.Availability.UNAVAILABLE?0:installed.settings().needle(display.sample().value());
                draw(pose,buffers,mount,camera,installed.settings(),ratio,0,255,!host.pipe(),tier);
            }
            if(levelGauge){
                var mount=InstrumentHosts.LevelGauge.of(host);
                if(mount!=null&&mount.bounds().distanceToSqr(mc.player.getEyePosition())<=64&&!host.metadata().levelGauge()){
                    boolean clear=InstrumentHosts.clear(mc.level,host,mount);
                    var hit=mount.bounds().clip(mc.player.getEyePosition(),mc.player.getEyePosition().add(mc.player.getLookAngle().scale(mc.player.blockInteractionRange())));
                    boolean aimed=hit.isPresent()&&InstrumentHosts.reachable(mc.player,host,mount);
                    drawLevelGhost(pose,buffers,host,camera,clear?(aimed?0x73a38e:0x779ba9):0xad7770,aimed?64:40);
                }
            }
            if(draft!=null&&!host.pipe())for(var mount:host.mounts()) {
                if(!mount.kinds().contains(draft.kind())||mount.centre().distanceToSqr(mc.player.getEyePosition())>64)continue;
                boolean blocked=host.occupied(mount)||!InstrumentHosts.clear(mc.level,host,mount);
                boolean aimed=selected!=null&&selected.host().identity()==host.identity()&&selected.mount().id().equals(mount.id())&&InstrumentHosts.reachable(mc.player,host,mount);
                int colour=blocked?0xad7770:aimed?0x73a38e:0x779ba9;
                // Occupied instruments already supply the silhouette. An outline avoids coincident ghost/model planes.
                if(!blocked){draw(pose,buffers,mount,camera,draft,.5,0,aimed?88:56,true,FULL);drawRootGhost(pose,buffers,host,mount,camera,colour,aimed?88:56);}
                outline(pose,buffers,mount,camera,colour);
            }
        }
        if(wrench) {
            var target=InstrumentHosts.select(hosts,mc.player.getEyePosition(),mc.player.getLookAngle(),mc.player.blockInteractionRange(),null,true);
            if(target!=null&&!target.host().pipe()&&InstrumentHosts.reachable(mc.player,target.host(),target.mount()))outline(pose,buffers,target.mount(),camera,0x73a38e);

        }
        buffers.endBatch(TYPE);buffers.endBatch(GHOST);buffers.endBatch(RenderType.lines());
    }
    private static void drawRootGhost(PoseStack pose,MultiBufferSource buffers,InstrumentHosts.Host host,Instrument.Mount mount,Vec3 camera,int colour,int alpha){
        if(!(host.entity() instanceof com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity shell))return;
        EquipmentInstruments.of(shell.shape()).probes().stream().filter(p->p.id().equals(mount.id())).findFirst().ifPresent(p->DataControlRenderer.draw(pose,buffers,camera,Vec3.atLowerCornerOf(shell.origin()),InstrumentWiring.rootHardware(p,com.wormzjl.icheme.client.model.EquipmentShellModel.detail()),colour,alpha));
    }
    /** A structure's baked wiring, kept while its shape, installed mounts, nozzle codes, detail and atlas hold. */
    private record Wiring(EquipmentShape shape,Set<String> installed,List<Integer> codes,RenderDetail detail,Object atlas,List<net.minecraft.client.renderer.block.model.BakedQuad> quads){}
    private static final Map<BlockPos,Wiring> WIRING=new HashMap<>();
    private static void drawWiring(PoseStack pose,MultiBufferSource buffers,InstrumentHosts.Host host,Vec3 camera,RenderLod.View view,net.minecraft.client.renderer.culling.Frustum frustum){
        if(!(host.entity() instanceof com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity shell)||!shell.cell().equals(ShapeRasterizer.fluidCell(shell.shape())))return;
        var shape=shell.shape();var origin=shell.origin();
        double x0=origin.getX(),y0=origin.getY(),z0=origin.getZ(),x1=x0+shape.sizeX(),y1=y0+shape.sizeY(),z1=z0+shape.sizeZ();
        if(WIRES.of(new Seen(host.identity(),""),view.pixels(WIRE,camera.x,camera.y,camera.z,x0,y0,z0,x1,y1,z1))==0&&frustum.isVisible(new net.minecraft.world.phys.AABB(x0,y0,z0,x1,y1,z1))){
            var quads=wiring(shell);
            if(quads!=null&&!quads.isEmpty()){
                pose.pushPose();pose.translate(x0-camera.x,y0-camera.y,z0-camera.z);var consumer=buffers.getBuffer(RenderType.entityCutoutNoCull(net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS));int light=LevelRenderer.getLightColor(Minecraft.getInstance().level,shell.getBlockPos());
                for(var q:quads)consumer.putBulkData(pose.last(),q,1,1,1,1,light,OverlayTexture.NO_OVERLAY,true);pose.popPose();
            }
        }
        for(var p:EquipmentInstruments.of(shape).probes())if(!p.manual()&&p.display()){
            var mount=InstrumentHosts.equipmentMount(p,origin);
            int tier=HEADS.of(new Seen(host.identity(),"factory:"+p.id()),view.pixels(HEAD,mount.centre().distanceToSqr(camera)));
            if(tier==HEADS.hidden()||!visible(frustum,mount))continue;
            drawSensor(pose,buffers,mount,camera,Sensor.Kind.valueOf(p.quantity().name()),255,"EQ-"+host.identity(),true,tier,false);
        }
    }
    /** The wiring's quads, baked again only when what they are drawn from changed; null while a sprite is missing. */
    private static List<net.minecraft.client.renderer.block.model.BakedQuad> wiring(com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity shell){
        var shape=shell.shape();var host=shell.instruments();
        var installed=new HashSet<String>();host.installed().forEach(i->installed.add(i.key().mount()));host.sensors().forEach(i->installed.add(i.key().mount()));
        var codes=new ArrayList<Integer>(java.util.Collections.nCopies(shape.nozzles().size(),0));
        if(shape.kind()==EquipmentShape.Kind.EXCHANGER)for(var port:ShapeRasterizer.ports(shape)){var c=port.cell();var at=shell.origin().offset(c.x(),c.y(),c.z());if(shell.getLevel().hasChunkAt(at)&&shell.getLevel().getBlockEntity(at) instanceof com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity piece)codes.set(port.nozzle(),ShapeRasterizer.nozzleCode(shape,piece.cell(),port.nozzle(),piece.nozzles()));}
        var detail=com.wormzjl.icheme.client.model.EquipmentShellModel.detail();var atlas=com.wormzjl.icheme.client.model.EquipmentShellModel.sprite(Mesh.Material.values()[0]);if(atlas==null)return null;
        var kept=WIRING.get(shell.getBlockPos());
        if(kept!=null&&kept.atlas()==atlas&&kept.detail()==detail&&kept.shape().equals(shape)&&kept.installed().equals(installed)&&kept.codes().equals(codes))return kept.quads();
        var drawing=ShapeRasterizer.instrumentWiring(shape,installed,detail,codes);List<net.minecraft.client.renderer.block.model.BakedQuad> quads=List.of();
        if(!drawing.polygons().isEmpty()){
            var sprites=new net.minecraft.client.renderer.texture.TextureAtlasSprite[Mesh.Material.values().length];for(var m:Mesh.Material.values()){sprites[m.ordinal()]=com.wormzjl.icheme.client.model.EquipmentShellModel.sprite(m);if(sprites[m.ordinal()]==null)return null;}
            quads=com.wormzjl.icheme.client.model.EquipmentShellModel.bake(drawing.polygons(),sprites,true);
        }
        if(WIRING.size()>=256)WIRING.clear();
        WIRING.put(shell.getBlockPos(),new Wiring(shape,Set.copyOf(installed),List.copyOf(codes),detail,atlas,quads));
        return quads;
    }
    /** Whether a mount's dial or head, with its tap, can be on the screen. */
    private static boolean visible(net.minecraft.client.renderer.culling.Frustum frustum,Instrument.Mount mount){
        var c=mount.centre();var t=mount.tap();
        return frustum.isVisible(new net.minecraft.world.phys.AABB(Math.min(c.x,t.x)-.2,Math.min(c.y,t.y)-.2,Math.min(c.z,t.z)-.2,Math.max(c.x,t.x)+.2,Math.max(c.y,t.y)+.2,Math.max(c.z,t.z)+.2));
    }
    /** Hosts with installed instruments near the player, gathered again every {@link #HOST_TICKS} ticks or on a new level. */
    private static List<InstrumentHosts.Host> shownHosts(Minecraft mc){
        long now=mc.level.getGameTime();
        if(shownIn!=mc.level||now<shownAt||now-shownAt>=HOST_TICKS){shown=InstrumentHosts.nearby(mc.level,mc.player.position(),120,true);shownAt=now;shownIn=mc.level;}
        return shown;
    }
    private static void outline(PoseStack pose,MultiBufferSource buffers,Instrument.Selectable selection,Vec3 camera,int colour) {
        var lines=buffers.getBuffer(RenderType.lines());
        if(selection instanceof Instrument.Mount mount) {
            for(int i=0;i<32;i++) {
                double a=2*Math.PI*i/32,b=2*Math.PI*(i+1)/32;
                var first=mount.point(.177*Math.cos(a),.177*Math.sin(a),.073).subtract(camera);
                var second=mount.point(.177*Math.cos(b),.177*Math.sin(b),.073).subtract(camera);
                var direction=second.subtract(first).normalize();
                for(var point:List.of(first,second))lines.addVertex(pose.last(),(float)point.x,(float)point.y,(float)point.z).setColor(colour>>16&255,colour>>8&255,colour&255,135).setNormal(pose.last(),(float)direction.x,(float)direction.y,(float)direction.z);
            }
        } else LevelRenderer.renderLineBox(pose,lines,selection.bounds().move(camera.scale(-1)),(colour>>16&255)/255f,(colour>>8&255)/255f,(colour&255)/255f,.53f);
    }
    /** A transmitter head at {@code tier} of {@link #HEADS}: text by tier, the connector while {@code stems}. */
    private static void drawSensor(PoseStack pose,MultiBufferSource buffers,Instrument.Mount mount,Vec3 camera,Sensor.Kind kind,int alpha,String id,boolean equipment,int tier,boolean stems){
        var consumer=buffers.getBuffer(alpha==255?TYPE:GHOST);var frame=frame(mount);int light=light(mount,0);
        for(var q:InstrumentGeometry.transmitter(kind==Sensor.Kind.LEVEL))quad(pose,consumer,frame,camera,q,0,alpha,light);
        if(!equipment&&stems){
            var tap=mount.localTap();var key=new Connector(List.of(tap[0],tap[1],tap[2]),mount.bottomEntry(),kind==Sensor.Kind.TEMPERATURE,mount.probeDepth());if(CONNECTORS.size()>2048)CONNECTORS.clear();
            for(var q:CONNECTORS.computeIfAbsent(key,k->InstrumentGeometry.connection(tap,k.bottom(),k.temperature(),k.probeDepth())))quad(pose,consumer,frame,camera,q,0,alpha,light);
        }
        // Font rendering may flush the shared geometry buffer. Finish every connector before requesting font buffers.
        if(alpha==255&&tier<=PLAIN)text(pose,buffers,mount,camera,kind.symbol(),0,.038,.011f,.074,0xffa6d4d9);
        if(alpha==255&&tier==FULL&&!id.isEmpty())text(pose,buffers,mount,camera,id,0,-.072,(float)Math.min(.0013,.15/Math.max(1,Minecraft.getInstance().font.width(id))),.074,0xff152126);
    }
    /** A dial at {@code tier} of {@link #DIALS}: its housing, connector and text by tier, its needle always. */
    private static void draw(PoseStack pose,MultiBufferSource buffers,Instrument.Mount mount,Vec3 camera,GaugeSettings settings,double ratio,int tint,int alpha,boolean equipment,int tier){
        var consumer=buffers.getBuffer(alpha==255?TYPE:GHOST);var frame=frame(mount);int light=light(mount,tint);
        for(var q:tier==FULL?InstrumentGeometry.housing():tier==PLAIN?InstrumentGeometry.housingPlain():InstrumentGeometry.housingFar())quad(pose,consumer,frame,camera,q,tint,alpha,light);
        if(!equipment&&tier<=PLAIN){
            var tap=mount.localTap();var key=new Connector(List.of(tap[0],tap[1],tap[2]),mount.bottomEntry(),settings.kind()==GaugeSettings.Kind.TEMPERATURE,mount.probeDepth());
            if(CONNECTORS.size()>2048)CONNECTORS.clear();
            for(var q:CONNECTORS.computeIfAbsent(key,k->InstrumentGeometry.connection(new double[]{tap[0],tap[1],tap[2]},k.bottom,k.temperature,k.probeDepth)))quad(pose,consumer,frame,camera,q,tint,alpha,light);
        }
        quad(pose,consumer,frame,camera,InstrumentGeometry.needle(ratio),tint,alpha,light);
        if(tint==0&&alpha==255&&tier==FULL){
            text(pose,buffers,mount,camera,settings.label(),0,-.098,.0025f);
            for(int i=0;i<=4;i++){
                double fraction=i/4.0,angle=Math.PI*(1.25-1.5*fraction);
                String label=compact(settings.display(settings.minimum()+fraction*(settings.maximum()-settings.minimum())));
                float scale=Math.min(.0025f,.061f/Math.max(1,Minecraft.getInstance().font.width(label)));
                text(pose,buffers,mount,camera,label,.081*Math.cos(angle),.081*Math.sin(angle)+.010,scale);
            }
        }
    }
    private static String compact(double value){return String.format(Locale.ROOT,Math.abs(value)>=10000?"%.1e":"%.3g",value);}
    /** A mount's centre and outward normal, the pose of every quad drawn on it. */
    private record Frame(double[] centre,double[] normal){}
    private static Frame frame(Instrument.Mount mount){var c=mount.centre();var n=mount.normal();return new Frame(new double[]{c.x,c.y,c.z},new double[]{n.x,n.y,n.z});}
    /** One light per instrument, at its centre (each vertex used to read the same point); ghosts full bright. */
    private static int light(Instrument.Mount mount,int tint){return tint==0?LevelRenderer.getLightColor(Minecraft.getInstance().level,net.minecraft.core.BlockPos.containing(mount.centre())):LightTexture.FULL_BRIGHT;}
    private static void quad(PoseStack pose,VertexConsumer consumer,Frame frame,Vec3 camera,InstrumentGeometry.Quad q,int tint,int alpha,int light){
        var v=InstrumentGeometry.pose(q,frame.centre(),frame.normal()).vertices();
        double ax=v[3]-v[0],ay=v[4]-v[1],az=v[5]-v[2],bx=v[6]-v[0],by=v[7]-v[1],bz=v[8]-v[2];
        double nx=ay*bz-az*by,ny=az*bx-ax*bz,nz=ax*by-ay*bx,length=Math.sqrt(nx*nx+ny*ny+nz*nz);
        // As Vec3.normalize: a degenerate normal is zero.
        if(length<1e-4){nx=0;ny=0;nz=0;}else{nx/=length;ny/=length;nz/=length;}
        int c=tint==0?q.colour():tint;
        for(int j=0;j<4;j++)consumer.addVertex(pose.last(),(float)(v[j*3]-camera.x),(float)(v[j*3+1]-camera.y),(float)(v[j*3+2]-camera.z)).setColor(c>>16&255,c>>8&255,c&255,alpha).setUv(0,0).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose.last(),(float)nx,(float)ny,(float)nz);
    }
    private static void text(PoseStack pose,MultiBufferSource buffers,Instrument.Mount m,Vec3 camera,String text,double x,double y,float scale){text(pose,buffers,m,camera,text,x,y,scale,InstrumentGeometry.LABEL,0xff252b2e);}
    private static void text(PoseStack pose,MultiBufferSource buffers,Instrument.Mount m,Vec3 camera,String text,double x,double y,float scale,double depth,int colour){var mc=Minecraft.getInstance();var p=m.point(x,y,depth).subtract(camera);pose.pushPose();pose.translate(p.x,p.y,p.z);float angle=(float)Math.toDegrees(Math.atan2(m.normal().x,m.normal().z));pose.mulPose(Axis.YP.rotationDegrees(angle));pose.scale(scale,-scale,scale);mc.font.drawInBatch(text,-mc.font.width(text)/2f,0,colour,false,pose.last().pose(),buffers,net.minecraft.client.gui.Font.DisplayMode.NORMAL,0,LightTexture.FULL_BRIGHT);pose.popPose();}
    private static void drawLevelGhost(PoseStack pose,MultiBufferSource buffers,InstrumentHosts.Host host,Vec3 camera,int tint,int alpha){
        if(!(host.entity() instanceof com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity shell))return;
        var origin=shell.origin();var consumer=buffers.getBuffer(GHOST);
        pose.pushPose();pose.translate(origin.getX()-camera.x,origin.getY()-camera.y,origin.getZ()-camera.z);
        for(var face:com.wormzjl.icheme.science.equipment.ShapeRasterizer.tankGauge(shell.shape())){
            if(face.material()==com.wormzjl.icheme.science.equipment.Mesh.Material.GLASS)continue;
            var p=face.positions();var n=face.normals();
            for(int first=1;first+1<face.size();first+=2)for(int c:new int[]{0,first,first+1,Math.min(first+2,face.size()-1)})
                consumer.addVertex(pose.last(),(float)p[3*c],(float)p[3*c+1],(float)p[3*c+2]).setColor(tint>>16&255,tint>>8&255,tint&255,alpha).setUv(0,0).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(pose.last(),(float)n[3*c],(float)n[3*c+1],(float)n[3*c+2]);
        }
        pose.popPose();
    }
    @SubscribeEvent public static void hud(RenderGuiEvent.Post event){
        var mc=Minecraft.getInstance();if(mc.level==null||mc.player==null||mc.screen!=null)return;
        var hosts=InstrumentHosts.nearby(mc.level,mc.player.getEyePosition(),8,true);
        var selected=InstrumentHosts.select(hosts,mc.player.getEyePosition(),mc.player.getLookAngle(),mc.player.blockInteractionRange(),null,true);
        var fitting=InstrumentHosts.selectFitting(hosts,mc.player.getEyePosition(),mc.player.getLookAngle(),mc.player.blockInteractionRange(),true);
        Component line;
        String sensorId=sensorIdAt(hosts,mc.player);
        if(sensorId!=null)line=Component.literal(sensorId);
        else if(fitting!=null&&fitting.mount() instanceof InstrumentHosts.LevelGauge&&(selected==null||fitting.distance()<selected.distance())&&InstrumentHosts.reachable(mc.player,fitting.host(),fitting.mount())){
            var display=TankRenderer.get(fitting.host().entity().getBlockPos(),mc.level.getGameTime());
            line=display==null?Component.translatable("gui.icheme.gauge.unavailable"):Component.literal(String.format(Locale.ROOT,"%.3f m",display.level()));
        }else{
            if(selected==null||!InstrumentHosts.reachable(mc.player,selected.host(),selected.mount()))return;
            var h=selected.host();var installed=h.metadata().installed().stream().filter(v->v.key().mount().equals(selected.mount().id())).findFirst().orElse(null);if(installed==null)return;
            var settings=installed.settings();var display=get(new FluidNetwork.InstrumentTarget(h.entity().getBlockPos(),installed.key(),installed.instance()));var sample=display==null?null:display.sample();
            line=sample==null||sample.availability()==Instrument.Availability.UNAVAILABLE?Component.translatable("gui.icheme.gauge.unavailable"):Component.literal(settings.number(sample.value())+" "+settings.label());
        }
        var g=event.getGuiGraphics();int x=g.guiWidth()/2+14,y=g.guiHeight()/2+12;
        g.fill(x-3,y-2,x+mc.font.width(line)+3,y+10,0xe0202a31);g.drawString(mc.font,line,x,y,0xffeaf1f5,false);
    }

    private static String sensorIdAt(List<InstrumentHosts.Host> hosts,net.minecraft.client.player.LocalPlayer player){
        var eye=player.getEyePosition();var end=eye.add(player.getLookAngle().scale(player.blockInteractionRange()));double nearest=Double.POSITIVE_INFINITY;String id=null;
        for(var host:hosts)for(var sensor:host.metadata().sensors())for(var mount:Sensor.mounts(host,sensor.kind())){
            if(!mount.id().equals(sensor.key().mount()))continue;var hit=mount.bounds().clip(eye,end).orElse(null);
            if(hit==null||hit.distanceTo(eye)>=nearest||!InstrumentHosts.reachable(player,host,mount))continue;nearest=hit.distanceTo(eye);
            id=host.metadata().sensorIds().getOrDefault(sensor.instance(),sensor.kind().name())+" / "+sensor.kind().name();
        }return id;
    }

    // ---- the client's live readings: ephemeral, identity/instance-bound, the nearest 64 within 120 m, requested explicitly ----
    private static final Map<FluidNetwork.InstrumentTarget,FluidNetwork.InstrumentDisplay> CACHE=new HashMap<>();
    private static final Map<FluidNetwork.InstrumentTarget,Long> RECEIVED=new HashMap<>();
    public static double age(FluidNetwork.InstrumentDisplay d){var mc=Minecraft.getInstance();long elapsed=mc.level==null?0:Math.max(0,mc.level.getGameTime()-RECEIVED.getOrDefault(d.target(),mc.level.getGameTime()));return (d.publishedTick()-d.sample().tick()+elapsed)/20d;}
    private static final Set<FluidNetwork.InstrumentTarget> WATCHED=new HashSet<>();
    private static net.minecraft.world.level.Level level;
    private static int ticks;
    public static FluidNetwork.InstrumentDisplay get(FluidNetwork.InstrumentTarget target){return CACHE.get(target);}
    public static void accept(FluidNetwork.InstrumentDisplay display){if(WATCHED.contains(display.target())){CACHE.put(display.target(),display);var mc=Minecraft.getInstance();if(mc.level!=null)RECEIVED.put(display.target(),mc.level.getGameTime());}}
    public static void forget(BlockPos pos){var removed=WATCHED.stream().filter(t->t.position().equals(pos)).toList();WATCHED.removeAll(removed);removed.forEach(CACHE::remove);removed.forEach(RECEIVED::remove);if(Minecraft.getInstance().getConnection()!=null)FluidNetwork.requestInstruments(List.of(),removed);}
    public static void clear(){WIRING.clear();DIALS.clear();HEADS.clear();WIRES.clear();shown=List.of();shownIn=null;shownAt=Long.MIN_VALUE;CACHE.clear();RECEIVED.clear();WATCHED.clear();level=null;ticks=0;}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){var mc=Minecraft.getInstance();if(mc.level!=level){clear();level=mc.level;}if(mc.level==null||mc.player==null)return;if(++ticks%20!=0)return;var nearby=new ArrayList<FluidNetwork.InstrumentTarget>();var distances=new HashMap<FluidNetwork.InstrumentTarget,Double>();for(var h:InstrumentHosts.nearby(mc.level,mc.player.position(),120,true))for(var i:h.metadata().installed()){var mount=h.mount(i.key().mount());if(mount!=null&&mount.centre().distanceToSqr(mc.player.position())<=120*120){var target=new FluidNetwork.InstrumentTarget(h.entity().getBlockPos(),i.key(),i.instance());nearby.add(target);distances.put(target,mount.centre().distanceToSqr(mc.player.position()));}}nearby.sort(Comparator.comparingDouble(distances::get));var wanted=new HashSet<>(nearby.subList(0,Math.min(64,nearby.size())));var add=wanted.stream().filter(t->!WATCHED.contains(t)).toList();var remove=WATCHED.stream().filter(t->!wanted.contains(t)).toList();WATCHED.removeAll(remove);remove.forEach(CACHE::remove);remove.forEach(RECEIVED::remove);WATCHED.addAll(add);FluidNetwork.requestInstruments(add,remove);}
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event){clear();}
}
