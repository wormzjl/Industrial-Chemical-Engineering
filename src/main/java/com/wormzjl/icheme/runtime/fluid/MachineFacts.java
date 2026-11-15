package com.wormzjl.icheme.runtime.fluid;

import static com.wormzjl.icheme.runtime.fluid.FluidFacts.position;
import static com.wormzjl.icheme.runtime.fluid.FluidFacts.required;
import static com.wormzjl.icheme.runtime.fluid.FluidFacts.valid;

import com.wormzjl.icheme.runtime.fluid.FluidFacts.Fields;
import com.wormzjl.icheme.runtime.fluid.FluidFacts.Record;
import java.util.*;

/**
 * The machines' facts section (batch 2026-11-03-rotating-machinery): three fields, the shaft units by identity (schema
 * 10, W3), the electric stores by unit identity (schema 11, W5; a world written before them has null, read as unmetered
 * stores for its powered units: {@link FluidFacts.World#electricStores}) and the pumps' health by device identity (schema
 * 12, W10b; only pumps below 1, every pump new before it).
 */
final class MachineFacts implements FactsSection {
    @Override public void write(Fields f,WorldTopologyLedger.Snapshot s) {
        // Schema 10: the shaft units, by identity.
        f.list(new TreeMap<>(s.shafts()).values(),MachineFacts::shaft);
        // Schema 11: the electric stores, by unit identity: content (J) and whether metered.
        f.list(new TreeMap<>(s.stores()).entrySet(),(q,e)->q.count(e.getKey()).f64(e.getValue().joules()).bool(e.getValue().metered()));
        // Schema 12: the pumps' health, by device identity, only pumps below 1 (a pump without an entry is new).
        f.list(new TreeMap<>(s.health()).entrySet(),(q,e)->q.count(e.getKey()).f64(e.getValue()));
    }
    @Override public void read(Record f,FluidFacts.World.Builder world) {
        // A world written before schema 10 has no shaft units: no part turned before them.
        var shafts=f.list(List.<ShaftUnit>of(),MachineFacts::shaft);
        // A world written before schema 11 has no electric stores: null, read as unmetered stores for its powered units.
        var saved=f.list(null,q->{long id=q.count(0);double joules=q.f64(0);boolean metered=q.bool(true);
            return valid(q,"electric store "+id,()->Map.entry(id,new com.wormzjl.icheme.science.machine.ElectricStore.Facts(joules,metered)));});
        Map<Long,com.wormzjl.icheme.science.machine.ElectricStore.Facts> stores=null;
        if(saved!=null){stores=new HashMap<>();for(var e:saved)if(stores.put(e.getKey(),e.getValue())!=null)throw f.invalid("duplicate electric store "+e.getKey());}
        // A world written before schema 12 has no pump health: every pump is new (health 1).
        var health=new HashMap<Long,Double>();
        for(var e:f.list(List.<Map.Entry<Long,Double>>of(),q->{long id=q.count(0);double value=q.f64(1);
            if(!(value>=0&&value<=1))throw q.invalid("pump health "+id+": "+value);return Map.entry(id,value);}))
            if(health.put(e.getKey(),e.getValue())!=null)throw f.invalid("duplicate pump health "+e.getKey());
        world.shafts(shafts).stores(stores).health(health);
    }
    /**
     * A shaft unit (schema 10, batch 2026-11-03-rotating-machinery W3): its identity, kind by name, position, axis, ends (each a
     * cell, its step and whether power leaves), fluid device, rated power, design speed, poles, drive by name, settings
     * (running, mode by name, set point, power limit, ratio) and trip by name. Its engine state is in the topology state.
     */
    private static void shaft(Fields f,ShaftUnit u) {
        var s=u.settings();
        f.count(u.id()).name(u.kind());position(f,u.position());f.count(u.axis())
                .list(u.ends(),(q,e)->{position(q,e.cell());q.i32(e.dx()).i32(e.dy()).i32(e.dz()).bool(e.output());})
                .count(u.fluid()).f64(u.ratedPower()).f64(u.designSpeed()).count(u.poles()).name(u.drive())
                .record(q->q.bool(s.running()).name(s.mode()).f64(s.setPoint()).f64(s.powerLimit()).f64(s.ratio()))
                .name(u.trip());
    }
    private static ShaftUnit shaft(Record f) {
        long id=f.count(0);var kind=f.name(com.wormzjl.icheme.science.machine.ShaftNetwork.Kind.class,null);var position=position(f);long axis=f.count(0);
        var ends=f.list(List.<ShaftUnit.End>of(),q->{var cell=required(position(q),q,"shaft end cell");int dx=q.i32(0),dy=q.i32(0),dz=q.i32(0);boolean output=q.bool(false);
            return valid(q,"shaft end",()->new ShaftUnit.End(cell,dx,dy,dz,output));});
        long fluid=f.count(0);double power=f.f64(0),speed=f.f64(0);long poles=f.count(0);
        var drive=f.name(com.wormzjl.icheme.science.equipment.MachineDuty.Drive.class,com.wormzjl.icheme.science.equipment.MachineDuty.Drive.FIXED);
        var settings=f.record(ShaftUnit.Settings.DEFAULT,q->{boolean running=q.bool(true);var mode=q.name(com.wormzjl.icheme.science.machine.ShaftTrain.Mode.class,com.wormzjl.icheme.science.machine.ShaftTrain.Mode.SPEED);
            double set=q.f64(0),limit=q.f64(0),ratio=q.f64(0);return valid(q,"shaft settings",()->new ShaftUnit.Settings(running,mode,set,limit,ratio));});
        var trip=f.name(com.wormzjl.icheme.science.machine.ShaftTrain.Trip.class,com.wormzjl.icheme.science.machine.ShaftTrain.Trip.NONE);
        required(kind,f,"shaft unit kind");required(position,f,"shaft unit position");
        if(axis>2||poles>12||ends.size()>ShaftUnit.MAXIMUM_ENDS)throw f.invalid("shaft unit "+id);
        return valid(f,"shaft unit "+id,()->new ShaftUnit(id,kind,position,(int)axis,ends,fluid,power,speed,(int)poles,drive,settings,trip,null));
    }
}
