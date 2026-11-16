package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.runtime.fluid.FluidFacts.Fields;
import com.wormzjl.icheme.runtime.fluid.FluidFacts.Record;
import java.util.*;

/**
 * The walls' facts section (facts schema 29, batch 2026-11-16-fouling-corrosion, WP2): one list, per device identity in
 * identity order, its wall loss (mm), its fouling deposit (m²K/W) and whether a blast wrecked it
 * ({@link WorldTopologyLedger.IntegrityFacts}). An entry only for a device away from new and clean; a world written before
 * schema 29 has none (every wall new and clean, none wrecked: the behaviour before the fields existed). The last section of
 * the root record ({@link FactsSection}: the machines' section, which holds the health list, is not last). A control
 * valve's trim health is in the machines' health list.
 */
final class IntegritySection implements FactsSection {
    @Override public void write(Fields f,WorldTopologyLedger.Snapshot s) {
        f.list(new TreeMap<>(s.integrity()).entrySet().stream().filter(e->!e.getValue().isNew()).toList(),
                (q,e)->q.count(e.getKey()).f64(e.getValue().wallLoss()).f64(e.getValue().fouling()).bool(e.getValue().wrecked()));
    }
    @Override public void read(Record f,FluidFacts.World.Builder world) {
        var walls=new HashMap<Long,WorldTopologyLedger.IntegrityFacts>();
        for(var e:f.list(List.<Map.Entry<Long,WorldTopologyLedger.IntegrityFacts>>of(),q->{long id=q.count(0);double loss=q.f64(0),fouling=q.f64(0);boolean wrecked=q.bool(false);
            if(id<=0)throw q.invalid("wall facts of device "+id);
            return FluidFacts.valid(q,"wall facts "+id,()->Map.entry(id,new WorldTopologyLedger.IntegrityFacts(loss,fouling,wrecked)));}))
            if(walls.put(e.getKey(),e.getValue())!=null)throw f.invalid("duplicate wall facts "+e.getKey());
        world.integrity(walls);
    }
}
