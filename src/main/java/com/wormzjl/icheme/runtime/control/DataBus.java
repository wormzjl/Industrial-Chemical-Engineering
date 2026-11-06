package com.wormzjl.icheme.runtime.control;

import com.wormzjl.icheme.science.equipment.DataCableGeometry;
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
    public record Cable(Site site,DataCableGeometry.Key geometry,int arms,Map<Direction,UUID> endpoints) {
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
            t.putInt("joints",geometry.joints());t.putLong("offsets",geometry.offsets());t.putInt("arms",arms);
            var ends=new ListTag();endpoints.forEach((face,id)->{var e=new CompoundTag();e.putString("face",face.name());e.putUUID("port",id);ends.add(e);});
            t.put("endpoints",ends);return t;
        }
        public static Cable load(CompoundTag t){
            if(!t.getAllKeys().equals(Set.of("dimension","cell","lane","joints","offsets","arms","endpoints"))
                    ||!t.contains("dimension",8)||!t.contains("cell",4)||!t.contains("lane",3)||!t.contains("joints",3)
                    ||!t.contains("offsets",4)||!t.contains("arms",3)||!t.contains("endpoints",9))throw new IllegalArgumentException("Damaged cable facts");
            var ends=new EnumMap<Direction,UUID>(Direction.class);var tags=t.getList("endpoints",10);
            var saved=(ListTag)t.get("endpoints");if(!saved.isEmpty()&&saved.getElementType()!=10)throw new IllegalArgumentException("Invalid cable endpoints list");
            if(tags.size()>6)throw new IllegalArgumentException("Too many cable endpoints");
            for(var tag:tags){var e=(CompoundTag)tag;
                if(!e.getAllKeys().equals(Set.of("face","port"))||!e.contains("face",8)||!e.hasUUID("port")
                        ||ends.put(Direction.valueOf(e.getString("face")),e.getUUID("port"))!=null)throw new IllegalArgumentException("Invalid cable endpoint");}
            return new Cable(new Site(t.getString("dimension"),BlockPos.of(t.getLong("cell")),t.getInt("lane")),
                    new DataCableGeometry.Key(t.getInt("joints"),t.getLong("offsets")),t.getInt("arms"),ends);
        }
    }
    private record End(String dimension,long x,long y,long z,int axis){}
    private final Map<UUID,Set<UUID>> components;
    public DataBus(Collection<Cable> cables,Map<UUID,DataPort> ports){
        var byEnd=new HashMap<End,List<Site>>();var bySite=new HashMap<Site,Cable>();
        for(var cable:cables){
            if(bySite.put(cable.site(),cable)!=null)throw new IllegalArgumentException("Duplicate cable site");
            for(int face=0;face<6;face++)if((cable.geometry().joints()&(1<<face))!=0){
                var p=cable.geometry().point(face);var cell=cable.site().cell();
                var end=new End(cable.site().dimension(),Math.round((cell.getX()+p[0])*32),Math.round((cell.getY()+p[1])*32),Math.round((cell.getZ()+p[2])*32),face/2);
                byEnd.computeIfAbsent(end,k->new ArrayList<>()).add(cable.site());
            }
        }
        var neighbours=new HashMap<Site,Set<Site>>();
        for(var ends:byEnd.values())for(var a:ends)for(var b:ends)if(!a.equals(b))neighbours.computeIfAbsent(a,k->new HashSet<>()).add(b);
        var linked=new HashMap<UUID,Set<UUID>>();var seen=new HashSet<Site>();
        for(var first:bySite.keySet()){
            if(!seen.add(first))continue;
            var queue=new ArrayDeque<Site>();queue.add(first);var members=new HashSet<UUID>();
            while(!queue.isEmpty()){
                var site=queue.removeFirst();var cable=bySite.get(site);
                for(var entry:cable.endpoints().entrySet()){
                    var port=ports.get(entry.getValue());
                    if(port==null||!port.dimension().equals(site.dimension())||!port.cell().equals(site.cell())||port.face()!=entry.getKey())continue;
                    var point=cable.geometry().point(entry.getKey().ordinal());var expected=port.cablePoint().subtract(site.cell().getX(),site.cell().getY(),site.cell().getZ());
                    if(expected.distanceToSqr(new net.minecraft.world.phys.Vec3(point[0],point[1],point[2]))<1e-12)members.add(port.id());
                }
                for(var next:neighbours.getOrDefault(site,Set.of()))if(seen.add(next))queue.addLast(next);
            }
            // A socket is one bus even when several otherwise disjoint branches terminate there.
            boolean expanded;do{int size=members.size();for(var id:List.copyOf(members))members.addAll(linked.getOrDefault(id,Set.of()));expanded=members.size()!=size;}while(expanded);
            var group=Set.copyOf(members);for(var id:group)linked.put(id,group);
        }
        components=Map.copyOf(linked);
    }
    public Set<UUID> connected(UUID port){return components.getOrDefault(port,Set.of(port));}
    public boolean connected(UUID a,UUID b){return connected(a).contains(b);}
}
