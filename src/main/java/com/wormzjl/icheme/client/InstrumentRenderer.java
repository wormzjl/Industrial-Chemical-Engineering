package com.wormzjl.icheme.client;

import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Axis;
import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.network.FluidNetwork;
import net.minecraft.core.BlockPos;
import com.wormzjl.icheme.runtime.instrument.*;
import com.wormzjl.icheme.science.equipment.InstrumentGeometry;
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
    private static final ResourceLocation WHITE=ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID,"textures/misc/instrument.png");
    private static final RenderType TYPE=RenderType.entitySolid(WHITE);
    private static final RenderType GHOST=RenderType.entityTranslucentCull(WHITE);
    private record Connector(List<Double> tap,boolean bottom,boolean temperature,double probeDepth){}
    private static final Map<Connector,List<InstrumentGeometry.Quad>> CONNECTORS=new HashMap<>();
    @SubscribeEvent public static void render(RenderLevelStageEvent event) {
        if(event.getStage()!=RenderLevelStageEvent.Stage.AFTER_PARTICLES)return;
        var mc=Minecraft.getInstance();if(mc.level==null||mc.player==null)return;
        var camera=event.getCamera().getPosition();var buffers=mc.renderBuffers().bufferSource();var pose=event.getPoseStack();
        GaugeSettings draft=null;boolean wrench=false,levelGauge=false;
        for(var hand:net.minecraft.world.InteractionHand.values()) {
            var stack=mc.player.getItemInHand(hand);
            if(stack.is(ModItems.LIQUID_LEVEL_GAUGE.get()))levelGauge=true;
            if(stack.getItem() instanceof GaugeItem)draft=stack.get(ModItems.GAUGE_SETTINGS.get());
            if(stack.getItem() instanceof AccessoryWrenchItem&&mc.player.isShiftKeyDown())wrench=true;
        }
        var hosts=InstrumentHosts.nearby(mc.level,mc.player.position(),120,draft==null&&!wrench&&!levelGauge);
        var selected=draft==null?null:InstrumentHosts.select(hosts,mc.player.getEyePosition(),mc.player.getLookAngle(),mc.player.blockInteractionRange(),draft.kind(),false);
        for(var host:hosts) {
            for(var installed:host.metadata().installed()) {
                var mount=host.mount(installed.key().mount());if(mount==null)continue;
                var display=get(new FluidNetwork.InstrumentTarget(host.entity().getBlockPos(),installed.key(),installed.instance()));
                double ratio=display==null||display.sample().availability()==Instrument.Availability.UNAVAILABLE?0:installed.settings().needle(display.sample().value());
                draw(pose,buffers,mount,camera,installed.settings(),ratio,0,255);
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
                if(!blocked)draw(pose,buffers,mount,camera,draft,.5,0,aimed?88:56);
                outline(pose,buffers,mount,camera,colour);
            }
        }
        if(wrench) {
            var target=InstrumentHosts.select(hosts,mc.player.getEyePosition(),mc.player.getLookAngle(),mc.player.blockInteractionRange(),null,true);
            if(target!=null&&!target.host().pipe()&&InstrumentHosts.reachable(mc.player,target.host(),target.mount()))outline(pose,buffers,target.mount(),camera,0x73a38e);

        }
        buffers.endBatch(TYPE);buffers.endBatch(GHOST);buffers.endBatch(RenderType.lines());
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
    private static void draw(PoseStack pose,MultiBufferSource buffers,Instrument.Mount mount,Vec3 camera,GaugeSettings settings,double ratio,int tint,int alpha){
        var consumer=buffers.getBuffer(alpha==255?TYPE:GHOST);
        for(var q:InstrumentGeometry.housing())quad(pose,consumer,mount,camera,q,tint,alpha);
        var tap=mount.localTap();var key=new Connector(List.of(tap[0],tap[1],tap[2]),mount.bottomEntry(),settings.kind()==GaugeSettings.Kind.TEMPERATURE,mount.probeDepth());
        if(CONNECTORS.size()>2048)CONNECTORS.clear();
        for(var q:CONNECTORS.computeIfAbsent(key,k->InstrumentGeometry.connection(new double[]{tap[0],tap[1],tap[2]},k.bottom,k.temperature,k.probeDepth)))quad(pose,consumer,mount,camera,q,tint,alpha);
        quad(pose,consumer,mount,camera,InstrumentGeometry.needle(ratio),tint,alpha);
        if(tint==0&&alpha==255){
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
    private static void quad(PoseStack pose,VertexConsumer consumer,Instrument.Mount mount,Vec3 camera,InstrumentGeometry.Quad q,int tint,int alpha){var v=q.vertices();Vec3 p0=mount.point(v[0],v[1],v[2]),p1=mount.point(v[3],v[4],v[5]),p2=mount.point(v[6],v[7],v[8]);var n=p1.subtract(p0).cross(p2.subtract(p0)).normalize();int c=tint==0?q.colour():tint;for(int j=0;j<4;j++){var p=mount.point(v[j*3],v[j*3+1],v[j*3+2]).subtract(camera);consumer.addVertex(pose.last(),(float)p.x,(float)p.y,(float)p.z).setColor(c>>16&255,c>>8&255,c&255,alpha).setUv(0,0).setOverlay(OverlayTexture.NO_OVERLAY).setLight(tint==0?LevelRenderer.getLightColor(Minecraft.getInstance().level,net.minecraft.core.BlockPos.containing(mount.centre())):LightTexture.FULL_BRIGHT).setNormal(pose.last(),(float)n.x,(float)n.y,(float)n.z);}}
    private static void text(PoseStack pose,MultiBufferSource buffers,Instrument.Mount m,Vec3 camera,String text,double x,double y,float scale){var mc=Minecraft.getInstance();var p=m.point(x,y,InstrumentGeometry.LABEL).subtract(camera);pose.pushPose();pose.translate(p.x,p.y,p.z);float angle=switch(m.face()){case NORTH->180;case EAST->90;case WEST->-90;default->0;};pose.mulPose(Axis.YP.rotationDegrees(angle));pose.scale(scale,-scale,scale);mc.font.drawInBatch(text,-mc.font.width(text)/2f,0,0xff252b2e,false,pose.last().pose(),buffers,net.minecraft.client.gui.Font.DisplayMode.NORMAL,0,LightTexture.FULL_BRIGHT);pose.popPose();}
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
        if(fitting!=null&&fitting.mount() instanceof InstrumentHosts.LevelGauge&&(selected==null||fitting.distance()<selected.distance())&&InstrumentHosts.reachable(mc.player,fitting.host(),fitting.mount())){
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
    public static void clear(){CACHE.clear();RECEIVED.clear();WATCHED.clear();level=null;ticks=0;}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){var mc=Minecraft.getInstance();if(mc.level!=level){clear();level=mc.level;}if(mc.level==null||mc.player==null)return;if(++ticks%20!=0)return;var nearby=new ArrayList<FluidNetwork.InstrumentTarget>();var distances=new HashMap<FluidNetwork.InstrumentTarget,Double>();for(var h:InstrumentHosts.nearby(mc.level,mc.player.position(),120,true))for(var i:h.metadata().installed()){var mount=h.mount(i.key().mount());if(mount!=null&&mount.centre().distanceToSqr(mc.player.position())<=120*120){var target=new FluidNetwork.InstrumentTarget(h.entity().getBlockPos(),i.key(),i.instance());nearby.add(target);distances.put(target,mount.centre().distanceToSqr(mc.player.position()));}}nearby.sort(Comparator.comparingDouble(distances::get));var wanted=new HashSet<>(nearby.subList(0,Math.min(64,nearby.size())));var add=wanted.stream().filter(t->!WATCHED.contains(t)).toList();var remove=WATCHED.stream().filter(t->!wanted.contains(t)).toList();WATCHED.removeAll(remove);remove.forEach(CACHE::remove);remove.forEach(RECEIVED::remove);WATCHED.addAll(add);FluidNetwork.requestInstruments(add,remove);}
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event){clear();}
}
