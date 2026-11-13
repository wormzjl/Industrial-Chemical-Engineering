package com.wormzjl.icheme.runtime.fluid;

import static com.wormzjl.icheme.runtime.fluid.FluidFacts.position;
import static com.wormzjl.icheme.runtime.fluid.FluidFacts.required;
import static com.wormzjl.icheme.runtime.fluid.FluidFacts.valid;
import static com.wormzjl.icheme.runtime.fluid.FluidFacts.warn;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.runtime.fluid.FluidFacts.Fields;
import com.wormzjl.icheme.runtime.fluid.FluidFacts.Record;
import java.util.*;

/**
 * The registry's build facts section (schema 15, batch 2026-11-03-save-corruption, registry first): four fields, the
 * painted lines' paints by identity, the equipment structures by origin, the rack lanes' rack frames by identity and the
 * named one-block devices' names by identity, read into one {@link WorldTopologyLedger.Builds}. A record written before
 * schema 15 holds none, and the blocks teach them at their next bind.
 */
final class BuildFacts implements FactsSection {
    @Override public void write(Fields f,WorldTopologyLedger.Snapshot s) {
        // Schema 15: the build facts, line paints by identity and structures by origin. A structure's shape is its
        // shell's own SNBT, made once when it was recorded.
        var builds=s.builds();
        f.list(new TreeMap<>(builds.paints()).entrySet(),(q,e)->q.count(e.getKey()).i32(e.getValue().primary()).i32(e.getValue().secondary()));
        var structures=new ArrayList<>(builds.structures().entrySet());structures.sort(Map.Entry.comparingByKey(ORIGIN_ORDER));
        f.list(structures,(q,e)->{position(q,e.getKey());var t=e.getValue();q.text(t.text()).i32(t.paint().body()).i32(t.paint().structure()).text(t.name());});
        // Schema 15: each rack lane's rack frame, by identity.
        f.list(new TreeMap<>(builds.racks()).entrySet(),(q,e)->q.count(e.getKey()).name(e.getValue().facing()).name(e.getValue().shape()));
        // Schema 15: each named one-block device's name (N1), by identity.
        f.list(new TreeMap<>(builds.names()).entrySet(),(q,e)->q.count(e.getKey()).text(e.getValue()));
    }
    @Override public void read(Record f,FluidFacts.World.Builder world) {
        // Schema 15: the build facts. A record written before them holds none, and the blocks teach them later.
        var paints=new HashMap<Long,WorldTopologyLedger.LinePaint>();
        for(var e:f.list(List.<Map.Entry<Long,WorldTopologyLedger.LinePaint>>of(),q->{long id=q.count(0);int primary=q.i32(com.wormzjl.icheme.science.equipment.Paint.BARE),secondary=q.i32(primary);
                var paint=valid(q,"line paint of "+id,()->new WorldTopologyLedger.LinePaint(primary,secondary));if(id<=0||paint.bare())throw q.invalid("line paint of "+id);return Map.entry(id,paint);}))
            if(paints.put(e.getKey(),e.getValue())!=null)throw f.invalid("duplicate line paint "+e.getKey());
        var structures=new HashMap<PhysicalFluidTopology.Position,WorldTopologyLedger.Structure>();var origins=new HashSet<PhysicalFluidTopology.Position>();
        for(var e:f.list(List.<Map.Entry<PhysicalFluidTopology.Position,Optional<WorldTopologyLedger.Structure>>>of(),q->{var origin=required(position(q),q,"structure origin");var text=q.text("");
                int body=q.i32(0),structure=q.i32(0);var name=q.text("");if(origin.lane()!=0||text.isEmpty())throw q.invalid("structure at "+origin);
                return Map.entry(origin,Optional.ofNullable(structure(q,origin,text,body,structure,name)));})) {
            if(!origins.add(e.getKey()))throw f.invalid("duplicate structure at "+e.getKey());
            e.getValue().ifPresent(s->structures.put(e.getKey(),s));
        }
        // Schema 15: the rack frames. A record written before them holds none, and the racks teach them at bind.
        var racks=new HashMap<Long,WorldTopologyLedger.RackFrame>();
        for(var e:f.list(List.<Map.Entry<Long,WorldTopologyLedger.RackFrame>>of(),q->{long id=q.count(0);var facing=q.name(PhysicalFluidTopology.Direction.class,null);var shape=q.name(WorldTopologyLedger.RackShape.class,null);
                if(id<=0||facing==null||shape==null)throw q.invalid("rack frame of "+id);return Map.entry(id,valid(q,"rack frame of "+id,()->new WorldTopologyLedger.RackFrame(facing,shape)));}))
            if(racks.put(e.getKey(),e.getValue())!=null)throw f.invalid("duplicate rack frame "+e.getKey());
        // Schema 15: the device names. A record written before them holds none, and the blocks teach them at bind.
        var names=new HashMap<Long,String>();
        for(var e:f.list(List.<Map.Entry<Long,String>>of(),q->{long id=q.count(0);var name=q.text("");
                if(id<=0||!WorldTopologyLedger.named(name))throw q.invalid("device name of "+id);return Map.entry(id,name);}))
            if(names.put(e.getKey(),e.getValue())!=null)throw f.invalid("duplicate device name "+e.getKey());
        world.builds(new WorldTopologyLedger.Builds(paints,structures,racks,names));
    }
    private static final Comparator<PhysicalFluidTopology.Position> ORIGIN_ORDER=Comparator.comparing(PhysicalFluidTopology.Position::dimension)
            .thenComparingInt(PhysicalFluidTopology.Position::x).thenComparingInt(PhysicalFluidTopology.Position::y).thenComparingInt(PhysicalFluidTopology.Position::z);
    /**
     * Schema 8: a structure's record. Its shape reads through the shell's own reader and repairs ({@code ShapeRepair}),
     * repairs logged as a shell logs them. A shape of a kind this build retired is dropped: the shell's load removes the
     * structure whole (backward-compat decision D6), and the registry cannot place it again. Returns null for that.
     */
    private static WorldTopologyLedger.Structure structure(Record q,PhysicalFluidTopology.Position origin,String text,int body,int structure,String name) {
        net.minecraft.nbt.CompoundTag tag;
        try{tag=net.minecraft.nbt.TagParser.parseTag(text);}
        catch(com.mojang.brigadier.exceptions.CommandSyntaxException invalid){throw q.invalid("structure at "+origin+": its shape does not read: "+invalid.getMessage());}
        var read=com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity.readShape(tag);
        if(read.retired()!=null){warn("structure at "+origin+" is a retired "+read.retired()+"; the registry drops it");return null;}
        if(read.shape()==null)throw q.invalid("structure at "+origin+": no shape");
        for(var repair:read.repairs())IChemE.LOGGER.warn("equipment_compat kind={} at={} repair={}",read.shape().kind().name().toLowerCase(Locale.ROOT),origin,repair);
        var paint=valid(q,"structure paint at "+origin,()->new com.wormzjl.icheme.science.equipment.Paint(body,structure));
        if(!name.isEmpty()&&!WorldTopologyLedger.named(name))throw q.invalid("structure name at "+origin);
        return WorldTopologyLedger.Structure.of(read.shape(),paint,name);
    }
}
