package com.wormzjl.icheme.runtime.control;

import com.wormzjl.icheme.science.equipment.ConduitGeometry;
import com.wormzjl.icheme.science.equipment.CableMedium;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.*;
import java.util.*;

/** Disposable cable connectivity built from registered physical facts; it never loads a chunk. */
public final class DataBus {
    public record Site(String dimension,BlockPos cell,int lane) {
        public Site {
            Objects.requireNonNull(dimension);Objects.requireNonNull(cell);cell=cell.immutable();
            if(net.minecraft.resources.ResourceLocation.tryParse(dimension)==null||dimension.isBlank()||dimension.length()>128||lane<0||lane>com.wormzjl.icheme.science.equipment.RackGeometry.OPTIONS)throw new IllegalArgumentException("Invalid cable site");
        }
    }
    /** lane 0 is a cable block; higher lanes are rack options + 1. Endpoints belong to individual faces. */
    public record Cable(Site site,ConduitGeometry.Key geometry,int arms,Map<Direction,UUID> endpoints) {
        public Cable {
            Objects.requireNonNull(site);Objects.requireNonNull(geometry);endpoints=Map.copyOf(endpoints);
            if(site.lane()==0&&Integer.bitCount(geometry.joints())>4||(arms&~63)!=0||site.lane()>0&&(Integer.bitCount(arms)!=2||(geometry.joints()&~arms)!=0)
                    ||endpoints.size()>6||endpoints.keySet().stream().anyMatch(d->(geometry.joints()&(1<<d.ordinal()))==0))
                throw new IllegalArgumentException("Invalid data cable");
        }
        public Cable connect(int face,int u,int v,UUID endpoint){
            var ends=new EnumMap<Direction,UUID>(Direction.class);ends.putAll(endpoints);
            if(endpoint!=null)ends.put(Direction.values()[face],endpoint);
            return new Cable(site,geometry.connect(face,u,v),arms,ends);
        }
        public CompoundTag save(){
            var t=new CompoundTag();t.putString("dimension",site.dimension());t.putLong("cell",site.cell().asLong());t.putInt("lane",site.lane());
            if(geometry.medium()!=CableMedium.DATA)t.putString("medium",geometry.medium().name());t.putInt("joints",geometry.joints());t.putLong("offsets",geometry.offsets());t.putInt("arms",arms);
            t.put("endpoints",saveEndpoints(endpoints));return t;
        }
        public static Cable load(CompoundTag t){
            if(!t.getAllKeys().equals(t.contains("medium")?Set.of("dimension","cell","lane","joints","offsets","arms","endpoints","medium"):Set.of("dimension","cell","lane","joints","offsets","arms","endpoints"))
                    ||t.contains("medium")&&!t.contains("medium",8)||!t.contains("dimension",8)||!t.contains("cell",4)||!t.contains("lane",3)||!t.contains("joints",3)
                    ||!t.contains("offsets",4)||!t.contains("arms",3)||!t.contains("endpoints",9))throw new IllegalArgumentException("Damaged cable facts");
            var ends=loadEndpoints((ListTag)t.get("endpoints"));
            return new Cable(new Site(t.getString("dimension"),BlockPos.of(t.getLong("cell")),t.getInt("lane")),
                    new ConduitGeometry.Key(t.getInt("joints"),t.getLong("offsets"),t.contains("medium")?CableMedium.valueOf(t.getString("medium")):CableMedium.DATA),t.getInt("arms"),ends);
        }
    }
    public static ListTag saveEndpoints(Map<Direction,UUID> endpoints){
        var tags=new ListTag();endpoints.forEach((face,id)->{var e=new CompoundTag();e.putString("face",face.name());e.putUUID("port",id);tags.add(e);});return tags;
    }
    public static Map<Direction,UUID> loadEndpoints(ListTag tags){
        if(!tags.isEmpty()&&tags.getElementType()!=10||tags.size()>6)throw new IllegalArgumentException("Invalid cable endpoints list");
        var ends=new EnumMap<Direction,UUID>(Direction.class);
        for(var tag:tags){var e=(CompoundTag)tag;if(!e.getAllKeys().equals(Set.of("face","port"))||!e.contains("face",8)||!e.hasUUID("port")
                ||ends.put(Direction.valueOf(e.getString("face")),e.getUUID("port"))!=null)throw new IllegalArgumentException("Invalid cable endpoint");}
        return Map.copyOf(ends);
    }
    private record End(String dimension,long x,long y,long z,int axis){}
    private final Map<UUID,Set<UUID>> components;
    private final Map<Site,UUID> sitePorts;
    private final List<Set<UUID>> circuits;
    public DataBus(Collection<Cable> cables,Map<UUID,DataPort> ports){this(cables,ports,CableMedium.DATA);}
    public DataBus(Collection<Cable> cables,Map<UUID,DataPort> ports,CableMedium medium){
        var byEnd=new HashMap<End,List<Site>>();var bySite=new HashMap<Site,Cable>();
        for(var cable:cables){
            if(cable.geometry().medium()!=medium)continue;
            if(bySite.put(cable.site(),cable)!=null)throw new IllegalArgumentException("Duplicate cable site");
            for(int face=0;face<6;face++)if((cable.geometry().joints()&(1<<face))!=0){
                var p=cable.geometry().point(face);var cell=cable.site().cell();
                var end=new End(cable.site().dimension(),Math.round((cell.getX()+p[0])*32),Math.round((cell.getY()+p[1])*32),Math.round((cell.getZ()+p[2])*32),face/2);
                byEnd.computeIfAbsent(end,k->new ArrayList<>()).add(cable.site());
            }
        }
        var neighbours=new HashMap<Site,Set<Site>>();
        for(var ends:byEnd.values())for(var a:ends)for(var b:ends)if(!a.equals(b))neighbours.computeIfAbsent(a,k->new HashSet<>()).add(b);
        var representatives=new HashMap<Site,UUID>();
        var linked=new HashMap<UUID,Set<UUID>>();var seen=new HashSet<Site>();
        for(var first:bySite.keySet()){
            if(!seen.add(first))continue;
            var sites=new ArrayList<Site>();
            var queue=new ArrayDeque<Site>();queue.add(first);var members=new HashSet<UUID>();
            while(!queue.isEmpty()){
                var site=queue.removeFirst();sites.add(site);var cable=bySite.get(site);
                for(var entry:cable.endpoints().entrySet()){
                    var port=ports.get(entry.getValue());
                    if(port==null||port.kind().medium()!=medium||!port.dimension().equals(site.dimension())||!port.cell().equals(site.cell())||port.face()!=entry.getKey())continue;
                    var point=cable.geometry().point(entry.getKey().ordinal());var expected=port.cablePoint().subtract(site.cell().getX(),site.cell().getY(),site.cell().getZ());
                    if(expected.distanceToSqr(new net.minecraft.world.phys.Vec3(point[0],point[1],point[2]))<1e-12)members.add(port.id());
                }
                for(var next:neighbours.getOrDefault(site,Set.of()))if(seen.add(next))queue.addLast(next);
            }
            // A socket is one bus even when several otherwise disjoint branches terminate there.
            boolean expanded;do{int size=members.size();for(var id:List.copyOf(members))members.addAll(linked.getOrDefault(id,Set.of()));expanded=members.size()!=size;}while(expanded);
            if(!members.isEmpty()){var representative=members.iterator().next();for(var site:sites)representatives.put(site,representative);}
            var group=Set.copyOf(members);for(var id:group)linked.put(id,group);
        }
        components=Map.copyOf(linked);sitePorts=Map.copyOf(representatives);
        var unique=Collections.newSetFromMap(new IdentityHashMap<Set<UUID>,Boolean>());var groups=new ArrayList<Set<UUID>>();
        for(var group:components.values())if(unique.add(group))groups.add(group);
        circuits=List.copyOf(groups);
    }
    public Set<UUID> connected(Site site){var id=sitePorts.get(site);return id==null?Set.of():connected(id);}
    public Set<UUID> connected(UUID port){var group=components.get(port);return group==null?Set.of(port):group;}
    public boolean connected(UUID a,UUID b){return connected(a).contains(b);}
    /** Unique wired components, compiled once with the topology; unconnected sockets do not form circuits. */
    public List<Set<UUID>> circuits(){return circuits;}
}
