package com.wormzjl.icheme.runtime.fluid;

import static com.wormzjl.icheme.runtime.fluid.FluidFacts.position;
import static com.wormzjl.icheme.runtime.fluid.FluidFacts.registration;
import static com.wormzjl.icheme.runtime.fluid.FluidFacts.required;
import static com.wormzjl.icheme.runtime.fluid.FluidFacts.solids;
import static com.wormzjl.icheme.runtime.fluid.FluidFacts.uuid;
import static com.wormzjl.icheme.runtime.fluid.FluidFacts.valid;
import static com.wormzjl.icheme.runtime.fluid.FluidFacts.warn;

import com.wormzjl.icheme.runtime.fluid.FluidFacts.Fields;
import com.wormzjl.icheme.runtime.fluid.FluidFacts.Record;
import com.wormzjl.icheme.runtime.fluid.FluidFacts.Recovery;
import com.wormzjl.icheme.runtime.fluid.FluidFacts.Solid;
import java.util.*;

/**
 * The registry's facts section, the record's first fields since schema 1: the identity counter, the {@link
 * WorldTopologyLedger.FluidBasis basis}, every registration (a one-block mover read whole and left out, its name in
 * {@code removedMovers}), the constructed and destroyed totals, the pending solid recoveries and the restated totals. The
 * registration's own record is shared with the topology state's queued events and stays in {@link FluidFacts}.
 */
final class RegistryFacts implements FactsSection {
    @Override public void write(Fields f,WorldTopologyLedger.Snapshot s) {
        var b=s.basis();f.count(s.nextIdentity()).record(q->q.text(b.packageId()).list(b.components(),(c,id)->c.text(id)).text("").text(b.revision()));
        var active=new ArrayList<>(s.active().values());active.sort(Comparator.comparingLong(r->r.device().id()));
        f.list(active,FluidFacts::registration);
        f.record(t->total(t,s.constructed())).record(t->total(t,s.destroyed()));
        var recoveries=new TreeMap<>(s.recoveries());
        f.list(recoveries.entrySet(),(q,e)->{var r=e.getValue();uuid(q,e.getKey());position(q,r.position());q.list(r.player()==null?List.<UUID>of():List.of(r.player()),FluidFacts::uuid);
            solids(q,Solid.of(r.solids()));q.f64(r.energyJoule());});
        var restated=s.restated();
        f.record(q->q.f64(restated.energy()).list(new TreeMap<>(restated.removedMoles()).entrySet(),(e,m)->e.text(m.getKey()).f64(m.getValue()))
                .list(new TreeMap<>(restated.removedSolids()).entrySet(),(e,m)->e.text(m.getKey()).f64(m.getValue())));
    }
    @Override public void read(Record f,FluidFacts.World.Builder world) {
        long next=f.count(0);
        // The third field is retired (schema 1's hash of the package data, owner decision 2026-10-26): written empty,
        // read as empty or as that hash, never used. A schema 1 basis has no revision: "" restates the world once.
        var basis=f.record(null,q->{var id=q.text("");var components=q.list(List.of(),c->c.text(""));var retired=q.text("");var revision=q.text("");
            if(!retired.isEmpty()&&!retired.matches("[0-9a-f]{64}:[0-9a-f]{64}"))throw q.invalid("basis: invalid retired fingerprint");
            return valid(q,"basis",()->new WorldTopologyLedger.FluidBasis(id,components,revision));});
        var removedMovers=new ArrayList<String>();
        var registrations=new ArrayList<>(f.list(List.of(),q->registration(q,removedMovers)));registrations.removeIf(Objects::isNull);
        if(!removedMovers.isEmpty())warn("one-block movers removed (batch 2026-10-28-old-mover-removal): "+removedMovers);
        var constructed=f.record(null,RegistryFacts::total);var destroyed=f.record(null,RegistryFacts::total);
        var recoveries=f.list(List.of(),q->{var id=required(uuid(q),q,"recovery identity");var position=required(position(q),q,"recovery position");
            var player=q.list(List.of(),FluidFacts::uuid);var solids=solids(q);double energy=q.f64(0);
            return new Recovery(id,position,player.isEmpty()?null:player.getFirst(),solids,energy);});
        var restated=f.record(WorldTopologyLedger.Restated.NONE,q->{double energy=q.f64(0);
            var moles=new HashMap<String,Double>();for(var e:q.list(List.of(),e->Map.entry(e.text(""),e.f64(0))))moles.put(e.getKey(),e.getValue());
            var solids=new HashMap<String,Double>();for(var e:q.list(List.of(),e->Map.entry(e.text(""),e.f64(0))))solids.put(e.getKey(),e.getValue());
            return valid(q,"restated totals",()->new WorldTopologyLedger.Restated(energy,moles,solids));});
        world.nextIdentity(next).basis(basis).registrations(registrations).removedMovers(List.copyOf(removedMovers)).constructed(constructed).destroyed(destroyed).recoveries(recoveries).restated(restated);
    }
    private static void total(Fields f,WorldTopologyLedger.MaterialTotal t) {
        f.doubles(t.moles()).f64(t.totalEnergy()).list(new TreeMap<>(t.solidMasses()).entrySet(),(e,m)->e.text(m.getKey()).f64(m.getValue()));
    }
    private static WorldTopologyLedger.MaterialTotal total(Record f) {
        var moles=f.doubles(null);double energy=f.f64(0);var solids=new HashMap<String,Double>();
        for(var e:f.list(List.of(),e->Map.entry(e.text(""),e.f64(0))))if(solids.put(e.getKey(),e.getValue())!=null)throw f.invalid("duplicate solid total");
        required(moles,f,"total moles");return valid(f,"total",()->new WorldTopologyLedger.MaterialTotal(moles,energy,solids));
    }
}
