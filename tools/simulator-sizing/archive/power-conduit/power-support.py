from pathlib import Path
exec(Path('tmp/power-conduit/integration.py').read_text().split("edit('registry/ModBlocks.java'")[0])
edit('runtime/control/DataBus.java','    private final Map<UUID,Set<UUID>> components;','    private final Map<UUID,Set<UUID>> components;\n    private final Map<Site,UUID> sitePorts;')
edit('runtime/control/DataBus.java','var linked=new HashMap<UUID,Set<UUID>>();','var representatives=new HashMap<Site,UUID>();\n        var linked=new HashMap<UUID,Set<UUID>>();')
edit('runtime/control/DataBus.java','var queue=new ArrayDeque<Site>();queue.add(first);','var sites=new ArrayList<Site>();\n            var queue=new ArrayDeque<Site>();queue.add(first);')
edit('runtime/control/DataBus.java','var site=queue.removeFirst();var cable','var site=queue.removeFirst();sites.add(site);var cable')
edit('runtime/control/DataBus.java','var group=Set.copyOf(members);','if(!members.isEmpty()){var representative=members.iterator().next();for(var site:sites)representatives.put(site,representative);}\n            var group=Set.copyOf(members);')
edit('runtime/control/DataBus.java','components=Map.copyOf(linked);','components=Map.copyOf(linked);sitePorts=Map.copyOf(representatives);')
edit('runtime/control/DataBus.java','    public Set<UUID> connected(UUID port)','    public Set<UUID> connected(Site site){var id=sitePorts.get(site);return id==null?Set.of():connected(id);}\n    public Set<UUID> connected(UUID port)')
edit('runtime/control/DataBus.java','||!t.contains("dimension",8)','||t.contains("medium")&&!t.contains("medium",8)||!t.contains("dimension",8)')
edit('runtime/control/DataPorts.java','    private static DataPort create(','    public static DataPort power(UUID id,DataPort.Kind kind,Level level,BlockPos owner,Vec3 centre,Direction outward){if(!kind.power())throw new IllegalArgumentException("Not a power port");return create(id,kind,0,level,owner,centre,outward);}\n    private static DataPort create(')
edit('runtime/control/DataPorts.java','        var host=InstrumentHosts.at(level,port.owner());','''        if(port.kind().power()){
            if(level.getBlockEntity(port.owner()) instanceof EquipmentShellBlockEntity shell&&shell.shape()!=null){var socket=DataSocketGeometry.power(shell.shape());if(socket!=null)return Direction.values()[socket.face()];}
            return port.face().getOpposite();
        }
        var host=InstrumentHosts.at(level,port.owner());''')
edit('science/equipment/DataSocketGeometry.java','    public static Socket surface(List<Mesh.Polygon> polygons){','''    /** The terminal-box side at its centre height, rather than a process nozzle or an arbitrary large body face. */
    public static Socket power(EquipmentShape shape){
        if(!shape.designed())return null;
        var unit=ProcessUnit.of(shape);var centre=unit.terminal();if(centre==null)return null;
        double x=Double.POSITIVE_INFINITY;
        for(var facet:unit.mesh().facets()){
            var v=Mesh.whole(facet).positions();boolean positive=false,negative=false;
            for(int k=0;k<v.length;k+=3){int next=(k+3)%v.length;double side=(v[next+1]-v[k+1])*(centre[2]-v[k+2])-(v[next+2]-v[k+2])*(centre[1]-v[k+1]);positive|=side>1e-9;negative|=side< -1e-9;}
            if(positive&&negative)continue;
            var a=new double[]{v[3]-v[0],v[4]-v[1],v[5]-v[2]};var b=new double[]{v[6]-v[0],v[7]-v[1],v[8]-v[2]};var n=Mesh.cross(a,b);if(Math.abs(n[0])<1e-9)continue;
            double at=v[0]-(n[1]*(centre[1]-v[1])+n[2]*(centre[2]-v[2]))/n[0];if(at>=centre[0]-.001)x=Math.min(x,at);
        }
        if(!Double.isFinite(x))x=centre[0]+.15;
        var p=shape.transform().point(new double[]{x+.14,centre[1],centre[2]});var n=shape.transform().direction(new double[]{1,0,0});int face=n[0]>.5?5:n[0]<-.5?4:n[2]>.5?3:2;
        return new Socket(p[0],p[1],p[2],face);
    }
    public static Socket surface(List<Mesh.Polygon> polygons){''')
edit('world/level/block/entity/CreativePowerBlockEntity.java','implements IEnergyStorage','implements IEnergyStorage,com.wormzjl.icheme.runtime.instrument.Instrument.Holder')
edit('world/level/block/entity/CreativePowerBlockEntity.java','    public CreativePowerBlockEntity(','''    private final com.wormzjl.icheme.runtime.instrument.Instrument.Metadata instruments=new com.wormzjl.icheme.runtime.instrument.Instrument.Metadata();
    @Override public com.wormzjl.icheme.runtime.instrument.Instrument.Metadata instruments(){return instruments;}
    @Override public void onLoad(){super.onLoad();com.wormzjl.icheme.runtime.instrument.InstrumentHosts.loaded(this);}
    @Override public void setRemoved(){com.wormzjl.icheme.runtime.instrument.InstrumentHosts.unloaded(this);super.setRemoved();}
    @Override public void onChunkUnloaded(){com.wormzjl.icheme.runtime.instrument.InstrumentHosts.unloaded(this);super.onChunkUnloaded();}
    @Override protected void saveAdditional(net.minecraft.nbt.CompoundTag tag,HolderLookup.Provider provider){super.saveAdditional(tag,provider);instruments.write(tag);}
    @Override protected void loadAdditional(net.minecraft.nbt.CompoundTag tag,HolderLookup.Provider provider){super.loadAdditional(tag,provider);instruments.read(tag);com.wormzjl.icheme.runtime.instrument.InstrumentHosts.loaded(this);}
    @Override public net.minecraft.nbt.CompoundTag getUpdateTag(HolderLookup.Provider provider){return saveWithoutMetadata(provider);}
    @Override public net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket getUpdatePacket(){return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);}
    public CreativePowerBlockEntity(''')
# Finish the English labels after the initial script's Windows-codepage read failed.
import json
lang=Path('src/main/resources/assets/icheme/lang/en_us.json');s=lang.read_text(encoding='utf-8');i=s.rfind('}')
extra={
 'block.icheme.power_cable':'Power conduit','item.icheme.power_cable':'Power conduit',
 'gui.icheme.pipeline_tool.mode.power':'Power','gui.icheme.pipeline_tool.mode.power.hint':'Lay 1/8 m electrical conduit. Connects FE power ports and uses pipe routing and rack slots.',
 'item.icheme.pipe_laying_tool.power.lay':'Use twice to lay power conduit between FE ports.',
 'item.icheme.pipe_laying_tool.power.remove':'Shift + use removes one conduit.',
 'item.icheme.pipe_laying_tool.power.configure':'Shift + use in the air opens settings.',
 'item.icheme.pipe_laying_tool.power.items':'Each laid cell consumes one power conduit item.',
 'gui.icheme.power.input':'FE input','gui.icheme.power.output':'FE output','gui.icheme.power.both':'FE connection'}
lang.write_text(s[:i].rstrip()+',\n'+',\n'.join('  '+json.dumps(k)+': '+json.dumps(v) for k,v in extra.items())+'\n}\n',encoding='utf-8')
