package com.wormzjl.icheme.runtime.fluid;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Pending physical removal of standalone devices, appended after integrity. Earlier schemas have none. */
final class RuptureFacts implements FactsSection {
    private static final Comparator<PhysicalFluidTopology.Position> ORDER=Comparator
            .comparing(PhysicalFluidTopology.Position::dimension).thenComparingInt(PhysicalFluidTopology.Position::x)
            .thenComparingInt(PhysicalFluidTopology.Position::y).thenComparingInt(PhysicalFluidTopology.Position::z);

    @Override public void write(FluidFacts.Fields fields,WorldTopologyLedger.Snapshot world) {
        var cells=world.builds().ruptures().entrySet().stream().sorted(Map.Entry.comparingByKey(ORDER)).toList();
        fields.list(cells,(cell,entry)->{
            FluidFacts.position(cell,entry.getKey());
            cell.list(new TreeSet<>(entry.getValue()),(item,id)->item.count(id));
        });
    }

    @Override public void read(FluidFacts.Record fields,FluidFacts.World.Builder world) {
        var cells=new HashMap<PhysicalFluidTopology.Position,Set<Long>>();
        for(var entry:fields.list(List.<Map.Entry<PhysicalFluidTopology.Position,Set<Long>>>of(),cell->{
            var pos=FluidFacts.required(FluidFacts.position(cell),cell,"rupture position");
            var ids=cell.list(List.<Long>of(),item->item.count(0));
            var retired=Set.copyOf(ids);
            if(ids.size()!=retired.size())throw cell.invalid("duplicate destroyed identity");
            return Map.entry(pos,retired);
        })) {
            if(cells.put(entry.getKey(),entry.getValue())!=null)throw fields.invalid("duplicate rupture at "+entry.getKey());
        }
        world.ruptures(cells);
    }
}
