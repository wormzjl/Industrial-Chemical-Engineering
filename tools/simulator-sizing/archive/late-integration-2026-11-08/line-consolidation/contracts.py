from pathlib import Path
r=Path('src/main/java/com/wormzjl/icheme')
p=r/'world/level/block/LineLayout.java';s=p.read_text();s=s.replace('    public record HostEdit(', '    public record Connection(LineBackend.Host host,int face,int point){}\n    public record HostChanges(List<HostEdit> edits,Component refusal){public HostChanges{edits=List.copyOf(edits);}}\n    public record HostEdit(');i=s.index('    public static Built build(');s=s[:i]+'''    /** Combine both route ends before enforcing a retained segment's joint/point constraints. */
    public static HostChanges mergeHosts(List<Connection> connections){
        var edits=new LinkedHashMap<Site,HostEdit>();
        for(var connection:connections){
            var host=connection.host();int face=connection.face();if(face<0||face>5||connection.point()<0||connection.point()>1023)throw new IllegalArgumentException("Invalid host connection");
            var old=edits.get(host.site());int before=old==null?host.joints():old.joints();
            int after=host.automatic()?before:PipeRoute.hostJoints(before,1<<face,host.lane()>0);
            if(after<0)return new HostChanges(List.of(),PipeLaying.message(host.lane()>0?"anchor.lane_closed":"anchor.junction"));
            edits.put(host.site(),new HostEdit(host,after,(old==null?0:old.changedFaces())|1<<face,point(old==null?host.points():old.points(),face,connection.point())));
        }return new HostChanges(List.copyOf(edits.values()),null);
    }
'''+s[i:];s=s.replace('var edits=new LinkedHashMap<Site,HostEdit>();var endpoints=', 'var connections=new ArrayList<Connection>();var endpoints=');a=s.index('            var old=edits.get(host.site());',s.index('public static Built build'));b=s.index('            int packed=anchor.point()',a);s=s[:a]+s[b:];s=s.replace('edits.put(host.site(),new HostEdit(host,after,(old==null?0:old.changedFaces())|1<<anchor.face(),point(old==null?host.points():old.points(),anchor.face(),packed)));','connections.add(new Connection(host,anchor.face(),packed));');s=s.replace('return new Built(new LineLayout(spatial,cells,List.copyOf(edits.values()),endpoints),null);','var hosts=mergeHosts(connections);return hosts.refusal()!=null?new Built(null,hosts.refusal()):new Built(new LineLayout(spatial,cells,hosts.edits(),endpoints),null);');p.write_text(s)
p=r/'world/level/block/LineLaying.java';s=p.read_text();s=s.replace('int joints=PipeRoute.hostJoints(host.joints(),1<<port.face().ordinal(),false);if(joints<0)return failed(PipeLaying.message("anchor.junction"));','var edits=LineLayout.mergeHosts(List.of(new LineLayout.Connection(host,port.face().ordinal(),port.point())));if(edits.refusal()!=null)return failed(edits.refusal());');a=s.index('        long points=host.points()');b=s.index('        var spatial=',a);s=s[:a]+s[b:];s=s.replace('List.of(),List.of(edit),','List.of(),edits.edits(),');p.write_text(s)
# Explicit validation also protects non-network callers and cached client projections.
p=r/'world/level/block/LineBackend.java';s=p.read_text().replace('public Host{cell=cell.immutable();}','public Host{cell=cell.immutable();if(lane<0||lane>com.wormzjl.icheme.science.equipment.RackGeometry.OPTIONS||!Double.isFinite(bore)||bore<=0||joints<0||joints>63||arms<0||arms>63||facing<0||facing>5)throw new IllegalArgumentException("Invalid retained line");}');p.write_text(s)
p=r/'world/level/block/LineLayout.java';s=p.read_text().replace('public Site{cell=cell.immutable();}','public Site{cell=cell.immutable();if(lane<0||lane>RackGeometry.OPTIONS)throw new IllegalArgumentException("Invalid line lane");}');p.write_text(s)
# Direction changes in menus/tool modes invalidate replies before starting another transaction.
p=r/'client/LineToolSession.java';s=p.read_text().replace('if(active!=session||activeMode!=mode){if(active!=null)active.deactivate();','if(active!=session||activeMode!=mode){if(active!=null)active.disconnect();');p.write_text(s)
# Occupied proxy cells can only be removed as part of their owning connection. Invalid copies are disposable.
p=r/'world/level/block/LinePieceBlock.java';s=p.read_text();i=s.index('    @Override public BlockState playerWillDestroy');s=s[:i]+'''    @Override protected void tick(BlockState state,net.minecraft.server.level.ServerLevel level,BlockPos pos,net.minecraft.util.RandomSource random){
        if(level.getBlockEntity(pos) instanceof LinePieceBlockEntity copy&&copy.linePieces().isEmpty())level.removeBlock(pos,false);
    }
'''+s[i:];p.write_text(s)
p=r/'world/level/block/entity/LinePieceBlockEntity.java';s=p.read_text().replace('if(level instanceof ServerLevel server)for(var p:pieces)LinePieces.queue(server.getServer(),p.parent());}', 'if(level instanceof ServerLevel server){if(pieces.isEmpty())server.scheduleTick(worldPosition,getBlockState().getBlock(),1);else for(var p:pieces)LinePieces.queue(server.getServer(),p.parent());}}',1);p.write_text(s)
# Remove stale imports and obsolete helper types left by deleted planning paths.
p=r/'world/level/block/PipeLaying.java';p.write_text('''package com.wormzjl.icheme.world.level.block;
import com.wormzjl.icheme.science.equipment.PipeRoute;
import net.minecraft.network.chat.Component;
import java.util.Locale;
/** Fluid vocabulary shared by every line mode. Commands are owned by LineLaying. */
public final class PipeLaying {
    public static final double START_RANGE=LineLaying.START_RANGE;
    private PipeLaying(){}
    private static String name(Enum<?> value){return value.name().toLowerCase(Locale.ROOT);}
    public static Component refusal(PipeAnchor.Refusal reason){return message("anchor."+name(reason));}
    public static Component refusal(PipeRoute.Refusal reason){return message("route."+name(reason));}
    public static Component describe(PipeAnchor anchor){return message("end."+name(anchor.kind()));}
    public static Component message(String key,Object...args){return Component.translatable("message.icheme.pipe_laying."+key,args);}
}
''')
# Existing cable-host fixture moves to the shared owner, so fluid cannot miss the same invariant.
p=Path('src/test/java/com/wormzjl/icheme/runtime/control/CableConnectionsTest.java');s=p.read_text();a=s.index('    @Test void previewAndCommit');b=s.index('    @Test void terminalMarks',a);s=s[:a]+s[b:];p.write_text(s)
