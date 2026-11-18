package com.wormzjl.icheme.runtime.fluid;

import static com.wormzjl.icheme.runtime.fluid.FluidFacts.account;
import static com.wormzjl.icheme.runtime.fluid.FluidFacts.required;
import static com.wormzjl.icheme.runtime.fluid.FluidFacts.valid;

import com.wormzjl.icheme.runtime.fluid.FluidFacts.Fields;
import com.wormzjl.icheme.runtime.fluid.FluidFacts.Record;
import java.util.*;

/**
 * The batch stills' facts section (schema 16, batch 2026-11-09-batch-distiller): one field, the stills by their pots'
 * identities; a world written before schema 16 has none.
 */
final class StillFacts implements FactsSection {
    @Override public void write(Fields f,WorldTopologyLedger.Snapshot s) {
        // Schema 16: the batch stills, by their pots' identities.
        f.list(new TreeMap<>(s.stills()).values(),StillFacts::still);
    }
    @Override public void read(Record f,FluidFacts.World.Builder world) {
        // Schema 16: the batch stills. A world written before schema 16 has none.
        var stills=f.list(List.<StillUnit>of(),StillFacts::still);
        world.stills(stills);
    }
    /**
     * A batch still (schema 16, batch 2026-11-09-batch-distiller): its pot's identity, its water side, distillate, vent and
     * burners (0 for none), its design (pot volume, heater by name, built-in condenser, trays), its head's two accounts, the
     * heat left in its burning item (J), its firing (0 to 1), its reflux valve (the ratio, then whether it is closed: a closed
     * valve's ratio is +Infinity, which a facts number cannot hold, so it is written as the default ratio and the flag) and its
     * totals (J).
     */
    private static void still(Fields f,StillUnit u) {
        f.count(u.id()).count(u.water()).count(u.distillate()).count(u.vent()).count(u.burners())
                .f64(u.volume()).name(u.heater()).bool(u.condenser()).count(u.stages())
                .record(a->account(a,u.head())).record(a->account(a,u.venting()))
                .f64(u.burning()).f64(u.firing()).f64(u.closedValve()?StillUnit.DEFAULT_REFLUX:u.reflux()).bool(u.closedValve()).f64(u.heated()).f64(u.condensed());
        if(u.fuelStack()!=null)f.text(u.fuelStack());
    }
    private static StillUnit still(Record f) {
        long id=f.count(0),water=f.count(0),distillate=f.count(0),vent=f.count(0),burners=f.count(0);
        double volume=f.f64(0);var heater=f.name(com.wormzjl.icheme.science.plant.still.BatchStill.Heater.class,null);boolean condenser=f.bool(false);long stages=f.count(0);
        var head=f.record(null,FluidFacts::account);var venting=f.record(null,FluidFacts::account);
        double burning=f.f64(0),firing=f.f64(StillUnit.DEFAULT_FIRING),valve=f.f64(StillUnit.DEFAULT_REFLUX);double reflux=f.bool(false)?Double.POSITIVE_INFINITY:valve;double heated=f.f64(0),condensed=f.f64(0);
        String fuel=f.text(null);
        required(heater,f,"still heater");required(head,f,"still head account");required(venting,f,"still vent account");
        if(stages>com.wormzjl.icheme.science.column.still.StillColumn.MAX_STAGES)throw f.invalid("still trays "+stages);
        return valid(f,"batch still "+id,()->new StillUnit(id,water,distillate,vent,burners,volume,heater,condenser,(int)stages,head,venting,burning,firing,reflux,heated,condensed,fuel));
    }
}
