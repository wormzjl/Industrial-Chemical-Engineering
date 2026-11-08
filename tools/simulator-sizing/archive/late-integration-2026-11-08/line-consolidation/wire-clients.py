from pathlib import Path
r=Path('src/main/java/com/wormzjl/icheme')
p=r/'world/level/block/LineLayout.java';s=p.read_text();a=s.index('        public double[] point(');b=s.index('\n',a);s=s[:a]+s[b:];p.write_text(s)
p=r/'world/level/block/PipeLaying.java';s=p.read_text();a=s.index('    /** Why a command');b=s.index('    private static String name(',a);s=s[:a]+s[b:];s=s.replace('Server: the laying tool\'s commands','Messages for the laying tool');p.write_text(s)
p=r/'client/LineToolSession.java';s=p.read_text().replace('import java.util.Objects;','import java.util.*;\nimport com.wormzjl.icheme.world.level.block.*;');s=s.replace('private boolean pending,serverRefused;', 'private UUID ticket;\n    private boolean pending,serverRefused;');s=s.replace('private void reset(){generation++;','private void reset(){pending=false;ticket=null;generation++;');s=s.replace('if(!pending)adapter.dismantle(offHand,aim);','if(!pending){begin();adapter.dismantle(offHand,aim);}');i=s.index('    void answered(Component');s=s[:i]+'''    /** All media share request correlation, including removal and late replies after switching tools/worlds. */
    public static void command(boolean offHand,LineLaying.Action action,LineTarget first,LineTarget last,List<BlockPos> path,List<Integer> options){
        var mc=Minecraft.getInstance();if(active==null||mc.player==null||mc.level==null)return;
        active.ticket=UUID.randomUUID();
        com.wormzjl.icheme.network.LineNetwork.send(new LineLaying.Request(active.ticket,offHand,LineProfile.of(LayingToolAim.held(mc.player)),action,first,last,path,options));
    }
    public static void answered(UUID ticket,Component message,boolean success){
        if(active==null||!Objects.equals(active.ticket,ticket))return;
        active.ticket=null;active.answered(message,success);
    }
'''+s[i:];p.write_text(s)
# Fluid drawing consumes common layout, no route or host-edit geometry decisions here.
p=r/'client/PipeLayingClient.java';s=p.read_text().replace('import com.wormzjl.icheme.world.level.block.LinePlacement;','import com.wormzjl.icheme.world.level.block.*;');s=s.replace('@Nullable PipeRoute.Placement placement, List<Junction> junctions)', '@Nullable LineLayout layout, List<Junction> junctions)').replace('@Nullable PipeRoute.Placement placement) {','@Nullable LineLayout layout) {').replace('refusal, placement, List.of()', 'refusal, layout, List.of()').replace('plan.placement,plan.junctions','plan.layout,plan.junctions');s=s.replace('FluidNetwork.dismantlePipe(offHand,click);','LineToolSession.command(offHand,LineLaying.Action.DISMANTLE,LineTarget.of(click),LineTarget.of(click),List.of(),List.of());').replace('FluidNetwork.layPipe(offHand,click,end,plan.route().cells().stream().map(PipeAnchor::block).toList(),plan.route().options());','LineToolSession.command(offHand,LineLaying.Action.LAY,LineTarget.of(click),LineTarget.of(end),plan.route().cells().stream().map(PipeAnchor::block).toList(),plan.route().options());');s=s.replace('    public static void answered(Component message,boolean laid){SESSION.answered(message,laid);}\n','');a=s.index('        double outside = PipeSizes.outside(diameter);',s.index('private static Plan plan('));b=s.index('    /** Ports',a) if '    /** Ports' in s[a:] else -1
# remove plan(), junctions(), lanePoint() up to next function (portLabel)
end=s.index('    private static @Nullable Component portLabel',a)
# retain comment just before portLabel by replacing complete region
s=s[:a]+'''        var design=LineLaying.search(level,LineTarget.of(selected.click()),LineTarget.of(end),LineProfile.FLUID.settings(held(Minecraft.getInstance().player)));
        var layout=design.layout();var shown=new ArrayList<Junction>();
        if(layout!=null)for(var edit:layout.hosts()){
            var host=edit.before();
            if(host.lane()>0&&level.getBlockEntity(host.cell()) instanceof PipeRackBlockEntity rack)
                shown.add(new Junction(host.cell(),RackGeometry.lane(rack.frame(),new RackGeometry.Lane(host.lane()-1,RackGeometry.width(PipeSizes.drawn(host.bore())),host.arms(),edit.joints(),PipeSizes.code(PipeSizes.outside(host.bore()))))));
            else if(level.getBlockEntity(host.cell()) instanceof FluidDeviceBlockEntity pipe){
                var after=FluidDeviceModel.blockKey(pipe.getBlockState(),pipe.getModelData());
                for(int face=0;face<6;face++)if((edit.changedFaces()&1<<face)!=0){int packed=(int)(edit.points()>>10*face)&1023;int u=packed&31,v=packed>>5&31;after=after.withConnection(face,PipeSizes.code(PipeSizes.outside(diameter)),u>=16?u-32:u,v>=16?v-32:v);}
                shown.add(new Junction(host.cell(),LineGeometry.polygons(after)));
            }
        }
        return new Plan(end,design.end()==null?null:design.end().placement(),design.route()==null?new PipeRoute.Result(List.of(),List.of(),null):design.route(),design.refusal(),layout,List.copyOf(shown));
    }

'''+s[end:]
s=s.replace('shown.placement.joints()[i]','shown.layout.cells().get(i).joints()').replace('shown.placement.arms()[i]','shown.layout.cells().get(i).arms()').replace('shown.placement.facings()[i]','shown.layout.cells().get(i).facing()');a=s.index('                    var at = lanePoint(');b=s.index('\n',s.index('if (at != null)',a));s=s[:a]+'''                    offsets=shown.layout.cells().get(i).points();'''+s[b:];p.write_text(s)
# Common LineTarget/Anchor are used directly by conduit drawing.
p=r/'client/CableLayingClient.java';s=p.read_text();s=s.replace('CableLaying.End','LineTarget').replace('CableLaying.Anchor','LineLayout.Anchor').replace('CableLaying.Attachment','LineLaying.Design').replace('CableLaying.block','PipeAnchor.block').replace('CableLaying.cables','ConduitLineBackend.cables');s=s.replace('private static UUID request;private static boolean removal;', '');s=s.replace('removal=true;request=ControlNetwork.cable(offHand,true,medium(),click,click,List.of(),List.of());','LineToolSession.command(offHand,LineLaying.Action.DISMANTLE,click,click,List.of(),List.of());');s=s.replace('removal=false;request=ControlNetwork.cable(offHand,false,medium(),click,click,List.of(),List.of());','LineToolSession.command(offHand,LineLaying.Action.ATTACH,click,click,List.of(),List.of());');s=s.replace('removal=false;request=ControlNetwork.cable(offHand,false,medium(),click,end,plan.route().cells().stream().map(PipeAnchor::block).toList(),plan.route().options());','LineToolSession.command(offHand,LineLaying.Action.LAY,click,end,plan.route().cells().stream().map(PipeAnchor::block).toList(),plan.route().options());')
s=s.replace('CableLaying.resolve(mc.level,click,DataControlRenderer.ports(mc.level,mc.player.position(),96,medium()),medium())','LineAnchors.resolve(mc.level,click,settings()).anchor()').replace('CableLaying.resolve(level,start,DataControlRenderer.ports(level,centre,96,medium()),medium())','LineAnchors.resolve(level,start,settings()).anchor()')
s=s.replace('CableLaying.attachment(mc.level,click,ports,medium())','attachment(click)').replace('CableLaying.attachment(mc.level,hovered,DataControlRenderer.ports(mc.level,mc.player.position(),96,medium()),medium())','attachment(hovered)').replace('LinePlacement.check(mc.level,attachment.plan())','attachment.refusal()').replace('LinePlacement.check(mc.level,attachGhost.plan())','attachGhost.refusal()')
s=s.replace('plan.from().placement(),plan.to().placement()', 'plan.from()==null?null:plan.from().placement(),plan.to()==null?null:plan.to().placement()')
a=s.index('        var mc=Minecraft.getInstance();var ports=',s.index('private static Plan build('));b=s.index('    private static void project(',a);s=s[:a]+'''        var mc=Minecraft.getInstance();var design=LineLaying.search(mc.level,selected,end,settings());
        var all=design.layout()==null?List.<DataBus.Cable>of():ConduitLineBackend.wires(mc.level,design.layout(),settings().profile());
        int count=design.layout()==null?0:design.layout().cells().size();
        return new Plan(end,design.start(),design.end(),design.route(),design.refusal(),all.subList(0,count),all.subList(count,all.size()));
    }
    private static LineProfile.Settings settings(){return LineProfile.of(medium()).settings(LayingToolAim.held(Minecraft.getInstance().player));}
    private static LineLaying.Design attachment(LineTarget target){var design=LineLaying.attachment(Minecraft.getInstance().level,target,settings());return design.layout()==null?null:design;}
'''+s[b:]
s=s.replace('anchor.port()==null?PipeAnchor.block(anchor.end().cell()).getCenter():anchor.port().centre()', 'anchor.socket()==null?anchor.placement().cell().getCenter():anchor.socket().centre()').replace('PipeAnchor.block(from.end().cell())','from.placement().cell()').replace('PipeAnchor.block(to.end().cell())','to.placement().cell()');s=s.replace('var at=attachment.cable().site().cell();','var cable=ConduitLineBackend.wires(mc.level,attachment.layout(),settings().profile()).getFirst();var at=cable.site().cell();').replace('attachment.cable().geometry()','cable.geometry()').replace('attachment.port(),true','DataControlRenderer.ports(mc.level,mc.player.position(),96,medium()).get(hovered.port()),true');p.write_text(s)
# Public snapshot for client mesh projection only
p=r/'world/level/block/ConduitLineBackend.java';s=p.read_text().replace('    static List<DataBus.Cable> cables','    public static List<DataBus.Cable> cables');p.write_text(s)
# Old orchestrators have no remaining production callers except hand placement. Retain native hand registration separately for now.
p=r/'world/level/block/CableLaying.java';s=p.read_text();a=s.index('    /** BlockItem placement');b=s.index('    public record Hosts',a);hand=s[a:b];hand=hand.replace('new End(', 'new LineTarget(').replace('var attachment=attachment(level,click,world.controls().facts().ports(),key.medium());','var attachment=LineLaying.attachment(level,click,new LineProfile.Settings(LineProfile.of(key.medium()),key.medium().outside(),key.medium().outside(),0,0));').replace('attachment==null||LinePlacement.check(level,attachment.plan())!=null||LinePlacement.touched(attachment.plan())','attachment.layout()==null||attachment.refusal()!=null||LinePlacement.touched(attachment.layout().spatial())');
p.write_text('''package com.wormzjl.icheme.world.level.block;
import com.wormzjl.icheme.runtime.control.*;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.science.equipment.*;
import com.wormzjl.icheme.world.level.block.entity.*;
import net.minecraft.core.*;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import java.util.*;
/** Native block-item registration. Tool commands and physical design live exclusively in LineLaying/LineLayout. */
public final class CableLaying {
    private CableLaying(){}
    private static List<DataBus.Cable> cables(Level level,BlockPos p){return ConduitLineBackend.cables(level,p);}
    private static ConduitGeometry.Key connect(ConduitGeometry.Key k,int face,int packed){int u=packed&31,v=packed>>5&31;return k.connect(face,u>=16?u-32:u,v>=16?v-32:v);}
'''+hand+'}\n')
