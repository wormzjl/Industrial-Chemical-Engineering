package com.wormzjl.icheme.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.network.*;
import com.wormzjl.icheme.registry.ModItems;
import com.wormzjl.icheme.runtime.control.DataBus;
import com.wormzjl.icheme.science.equipment.*;
import com.wormzjl.icheme.world.item.PipeLayingToolItem;
import com.wormzjl.icheme.world.level.block.*;
import com.wormzjl.icheme.world.level.block.entity.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import java.util.*;

/** Same two-click pipe route and rack search, isolated from fluid actions and previews. */
@EventBusSubscriber(modid=IChemE.MOD_ID,value=Dist.CLIENT)
public final class CableLayingClient {
    private CableLayingClient(){}
    private static CableLaying.End start,end,hovered;private static net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension;
    private static CableMedium startedMedium;
    public static CableMedium medium(){return PipeLayingToolItem.medium(LayingToolAim.held(Minecraft.getInstance().player));}
    private static UUID request;private static boolean armed=true,sent;private static int age;private static List<DataBus.Cable> ghost=List.of();private static PipeRoute.Result route;
    private static boolean tool(ItemStack item){return item.is(ModItems.PIPE_LAYING_TOOL.get())&&PipeLayingToolItem.conduit(item);}
    public static boolean holding(LocalPlayer p){return LayingToolAim.holding(p)&&PipeLayingToolItem.conduit(LayingToolAim.held(p));}
    private static CableLaying.End aimed(){
        var mc=Minecraft.getInstance();var p=mc.player;var ports=DataControlRenderer.ports(mc.level,p.getEyePosition(),p.blockInteractionRange()+1,medium());
        var port=LayingToolAim.port(mc,ports.values());
        double solid=mc.hitResult==null?Double.POSITIVE_INFINITY:mc.hitResult.getLocation().distanceTo(p.getEyePosition());
        if(port!=null)return new CableLaying.End(port.id(),port.owner(),port.face().getOpposite(),new Vec3(.5,.5,.5),0);
        CableLaying.End picked=null;double nearest=solid+.04;var eye=p.getEyePosition();var end=eye.add(p.getLookAngle().scale(p.blockInteractionRange()));
        for(var rack:PipeRackBlockEntity.clientRacks())if(rack.getLevel()==mc.level&&rack.getBlockPos().getCenter().distanceToSqr(eye)<Math.pow(p.blockInteractionRange()+2,2))for(var cable:rack.cables()){
            if(cable.geometry().medium()!=medium())continue;
            var lane=rack.cableLane(cable);
            for(var box:RackGeometry.boxes(rack.rack(),lane)){
                var hit=new AABB(box[0],box[1],box[2],box[3],box[4],box[5]).move(rack.getBlockPos()).inflate(.025).clip(eye,end).orElse(null);
                if(hit==null||hit.distanceTo(eye)>=nearest)continue;nearest=hit.distanceTo(eye);var local=hit.subtract(Vec3.atLowerCornerOf(rack.getBlockPos()));
                int face=-1;double closest=Double.POSITIVE_INFINITY;for(int f=0;f<6;f++)if((cable.arms()&(1<<f))!=0){var q=RackGeometry.facePoint(rack.rack(),lane,f);double distance=local.distanceToSqr(new Vec3(q[0],q[1],q[2]));if(distance<closest){closest=distance;face=f;}}
                picked=new CableLaying.End(null,rack.getBlockPos(),Direction.values()[face],new Vec3(Math.clamp(local.x,0,1),Math.clamp(local.y,0,1),Math.clamp(local.z,0,1)),cable.site().lane());
            }
        }
        if(picked!=null)return picked;
        var click=LayingToolAim.aimed(mc,(rack,hit)->false);
        return click==null?null:new CableLaying.End(null,click.clicked(),click.face(),click.local(),0);
    }
    @SubscribeEvent public static void use(InputEvent.InteractionKeyMappingTriggered event){
        var mc=Minecraft.getInstance();if(!event.isUseItem()||mc.player==null||mc.level==null||!holding(mc.player)||!tool(mc.player.getItemInHand(event.getHand())))return;
        event.setCanceled(true);event.setSwingHand(false);if(!armed)return;armed=false;var aim=aimed();boolean off=event.getHand()==InteractionHand.OFF_HAND;
        if(mc.player.isShiftKeyDown()){
            if(start!=null){clear();say("gui.icheme.data.cancelled");}
            else if(aim!=null&&(mc.level.getBlockEntity(aim.clicked()) instanceof ConduitBlockEntity||aim.lane()>0))request=ControlNetwork.cable(off,true,medium(),aim,aim,List.of(),List.of());
            else FluidNetwork.openPipeLaying(off);return;
        }
        if(aim==null||sent)return;
        var ports=DataControlRenderer.ports(mc.level,mc.player.position(),96,medium());
        var socket=aim.port()==null?null:ports.get(aim.port());
        if(socket!=null&&mc.level.getBlockEntity(socket.cell()) instanceof ConduitBlockEntity wire&&wire.geometry().medium()==medium()
                &&!Objects.equals(wire.cable().endpoints().get(socket.face()),socket.id())){
            sent=true;request=ControlNetwork.cable(off,false,medium(),aim,aim,List.of(),List.of());return;
        }
        if(start==null){if(CableLaying.resolve(mc.level,aim,ports,medium())==null){say("gui.icheme.data.no_route");return;}start=aim;startedMedium=medium();dimension=mc.level.dimension();end=null;say("gui.icheme.data.started");return;}
        plan(aim);if(route==null||!route.found()){say("gui.icheme.data.no_route");return;}
        sent=true;request=ControlNetwork.cable(off,false,medium(),start,aim,route.cells().stream().map(CableLaying::block).toList(),route.options());
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        var mc=Minecraft.getInstance();if(!mc.options.keyUse.isDown())armed=true;
        if(mc.player==null||mc.level==null||!holding(mc.player)||startedMedium!=null&&startedMedium!=medium()||dimension!=null&&!dimension.equals(mc.level.dimension())){clear();return;}
        age++;if(start!=null&&start.clicked().getCenter().distanceToSqr(mc.player.position())>96*96){clear();say("gui.icheme.data.cancelled");}
        hovered=mc.screen==null?aimed():null;if(start!=null&&!sent&&age%5==0)plan(hovered);
    }
    private static void plan(CableLaying.End aim){
        if(Objects.equals(end,aim)&&route!=null&&age%20!=0)return;
        end=aim;ghost=List.of();route=null;if(aim==null)return;var mc=Minecraft.getInstance();var ports=DataControlRenderer.ports(mc.level,mc.player.position(),96,medium());
        var a=CableLaying.resolve(mc.level,start,ports,medium());var b=CableLaying.resolve(mc.level,aim,ports,medium());if(a==null||b==null)return;
        if(PipeAnchor.refuses(a.placement(),b.placement(),medium().outside())!=null)return;
        route=PipeRoute.search(a.end(),a.heading(),b.end(),medium().outside(),PipeAnchor.space(mc.level),PipeRoute.Limits.DEFAULT);
        if(route.found())ghost=CableLaying.design(mc.level,a,b,route,medium());
    }
    public static void answered(UUID ticket,boolean success,String detail){if(!Objects.equals(ticket,request))return;request=null;sent=false;if(Minecraft.getInstance().player==null){clear();return;}if(success){clear();say("gui.icheme.data.laid");}else Minecraft.getInstance().player.displayClientMessage(Component.literal(detail),true);}
    private static void clear(){hovered=null;request=null;start=null;end=null;dimension=null;startedMedium=null;route=null;ghost=List.of();sent=false;}
    private static void say(String key){if(medium()==CableMedium.POWER)key=key.replace(".data.",".power.");Minecraft.getInstance().player.displayClientMessage(Component.translatable(key),true);}
    public static void draw(PoseStack pose,MultiBufferSource buffers,Vec3 camera){
        var mc=Minecraft.getInstance();if(mc.player==null||!holding(mc.player))return;
        if(hovered!=null){
            if(mc.player.isShiftKeyDown()&&start==null){
                if(mc.level.getBlockEntity(hovered.clicked()) instanceof ConduitBlockEntity wire)for(var b:ConduitGeometry.boxes(wire.geometry()))box(pose,buffers,camera,new AABB(b[0],b[1],b[2],b[3],b[4],b[5]).move(hovered.clicked()),true);
                else if(mc.level.getBlockEntity(hovered.clicked()) instanceof PipeRackBlockEntity rack)for(var cable:rack.cables())if(cable.site().lane()==hovered.lane())for(var b:RackGeometry.boxes(rack.rack(),rack.cableLane(cable)))box(pose,buffers,camera,new AABB(b[0],b[1],b[2],b[3],b[4],b[5]).move(rack.getBlockPos()),true);
            }else if(hovered.port()==null&&hovered.lane()==0&&mc.level.getBlockEntity(hovered.clicked()) instanceof PipeRackBlockEntity rack){
                for(var free:LayingToolAim.freeSlots(rack))box(pose,buffers,camera,free,false);
                if(start==null||route==null||!route.found()){
                    var slot=LayingToolAim.slot(mc.level,new PipeAnchor.Click(hovered.clicked(),hovered.face(),hovered.local()),medium().outside());
                    if(slot!=null){boolean refused=!slot.fits()||start!=null&&route!=null&&!route.found();DataControlRenderer.draw(pose,buffers,camera,Vec3.atLowerCornerOf(rack.getBlockPos()),ConduitGeometry.lane(rack.rack(),slot.lane()),refused?0xff5555:medium().colour(),135);}
                }
            }
        }
        if(start!=null){var anchor=CableLaying.resolve(mc.level,start,DataControlRenderer.ports(mc.level,mc.player.position(),96,medium()),medium());if(anchor!=null){var point=anchor.port()==null?CableLaying.block(anchor.end().cell()).getCenter():anchor.port().centre();net.minecraft.client.renderer.LevelRenderer.renderLineBox(pose,buffers.getBuffer(net.minecraft.client.renderer.RenderType.lines()),new AABB(point,point).inflate(.17).move(camera.scale(-1)),.3f,1,.35f,1);}}
        for(var cable:ghost){var at=cable.site().cell();List<Mesh.Polygon> polygons;if(cable.site().lane()>0&&mc.level.getBlockEntity(at) instanceof PipeRackBlockEntity rack)polygons=ConduitGeometry.lane(rack.rack(),rack.cableLane(cable));else polygons=ConduitGeometry.polygons(cable.geometry());DataControlRenderer.draw(pose,buffers,camera,Vec3.atLowerCornerOf(at),polygons,medium().colour(),135);}
        // Show the added branch on an existing cable as part of the same preview.
        if(start!=null&&end!=null&&route!=null&&route.found())for(var click:List.of(start,end)){
            var a=CableLaying.resolve(mc.level,click,DataControlRenderer.ports(mc.level,mc.player.position(),96,medium()),medium());if(a==null||a.host()==null||a.host().lane()!=0||!(mc.level.getBlockEntity(a.host().cell()) instanceof ConduitBlockEntity wire))continue;
            var key=wire.geometry();int u=a.point()&31,v=a.point()>>5&31;key=key.connect(a.face(),u>=16?u-32:u,v>=16?v-32:v);DataControlRenderer.draw(pose,buffers,camera,Vec3.atLowerCornerOf(a.host().cell()),ConduitGeometry.polygons(key),medium().colour(),135);
        }
    }
    private static void box(PoseStack pose,MultiBufferSource buffers,Vec3 camera,AABB bounds,boolean refused){net.minecraft.client.renderer.LevelRenderer.renderLineBox(pose,buffers.getBuffer(net.minecraft.client.renderer.RenderType.lines()),bounds.move(camera.scale(-1)),refused?1:.3f,refused?.3f:1,refused?.3f:.35f,1);}

}
