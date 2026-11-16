package com.wormzjl.icheme.runtime.fluid;

import static com.wormzjl.icheme.runtime.fluid.FluidFacts.account;
import static com.wormzjl.icheme.runtime.fluid.FluidFacts.inventory;
import static com.wormzjl.icheme.runtime.fluid.FluidFacts.position;
import static com.wormzjl.icheme.runtime.fluid.FluidFacts.required;
import static com.wormzjl.icheme.runtime.fluid.FluidFacts.setpoints;
import static com.wormzjl.icheme.runtime.fluid.FluidFacts.solids;
import static com.wormzjl.icheme.runtime.fluid.FluidFacts.valid;

import com.wormzjl.icheme.runtime.fluid.FluidFacts.Fields;
import com.wormzjl.icheme.runtime.fluid.FluidFacts.Record;
import com.wormzjl.icheme.runtime.fluid.FluidFacts.Solid;
import com.wormzjl.icheme.science.material.SolidMaterialCatalog.SolidMaterial;
import java.util.*;

/**
 * The running columns' facts section (schema 6, batch 2026-10-28-column-operation): one field, the column units by
 * identity; a world written before schema 6 has none. A unit's own record grows inside it (schemas 8, 9, 21 and 29). Its
 * setpoints and accounts are records the topology state's column states share, and stay in {@link FluidFacts}.
 */
final class ColumnFacts implements FactsSection {
    @Override public void write(Fields f,WorldTopologyLedger.Snapshot s) {
        // Schema 6: the column units, by identity.
        f.list(new TreeMap<>(s.columns()).values(),ColumnFacts::column);
    }
    @Override public void read(Record f,FluidFacts.World.Builder world) {
        var columns=f.list(List.<ColumnUnit>of(),ColumnFacts::column);
        world.columns(columns);
    }
    /**
     * A column unit (schema 6, batch 2026-10-28-column-operation): its identity, origin, run switch, setpoints, stations
     * (each a port by name, which one, its port device, its valve, its account and its solids) and totals; its further
     * condenser water sides (schema 8), its air condenser surface (schema 9), its utility sides and members (schema 21), its
     * tray fouling, wall loss and material of construction (schema 29, WP8).
     */
    private static void column(Fields f,ColumnUnit u) {
        var s=u.setpoints();var t=u.totals();
        f.count(u.id());position(f,u.master());f.bool(u.running())
                .record(q->setpoints(q,s))
                .list(u.stations(),(q,st)->{q.name(st.port()).count(st.index()).count(st.device()).count(st.valve()).record(a->account(a,st.account()));solids(q,Solid.of(st.solids()));})
                .record(q->q.record(a->account(a,t.taken())).record(a->account(a,t.produced())).f64(t.reboiler()).f64(t.condenser()).f64(t.pumparounds()))
                .count(u.condenser()).bool(u.leaving())
                // Schema 8 (S3b): the condenser's further water sides, one a shell in parallel.
                .list(u.condensers().isEmpty()?List.<Long>of():u.condensers().subList(1,u.condensers().size()),(q,side)->q.count(side))
                // Schema 9 (U2): the air condenser's bare tube, m².
                .f64(u.airSurface())
                // Schema 21 (W4): the utility sides of the reboiler and of each pumparound's cooler.
                .record(q->q.list(u.utilities().reboiler(),(p,side)->p.count(side)).list(u.utilities().pumparounds(),(p,sides)->p.list(sides,(e,side)->e.count(side))))
                // Schema 21 (W5): the powered members, the fan sides, the reflux pump's movers and each pumparound pump's.
                .record(q->q.list(u.members().fans(),(p,m)->p.count(m)).list(u.members().reflux(),(p,m)->p.count(m)).list(u.members().pumparounds(),(p,ms)->p.list(ms,(e,m)->e.count(m))))
                // Schema 29 (WP8): the trays' fouling (0 to 1), the wall lost (mm) and the material of construction, by name.
                .f64(u.trayFouling()).f64(u.wallLoss()).name(u.material());
    }
    private static ColumnUnit column(Record f) {
        long id=f.count(0);var master=position(f);boolean running=f.bool(false);
        var setpoints=f.record(null,FluidFacts::setpoints);
        var stations=f.list(List.of(),q->{var port=q.name(com.wormzjl.icheme.science.plant.column.Port.class,null);long index=q.count(0),device=q.count(0),valve=q.count(0);
            var account=q.record(null,FluidFacts::account);required(port,q,"station port");required(account,q,"station account");var held=solids(q);
            if(index>=ColumnUnit.MAXIMUM_STATIONS)throw q.invalid("station index "+index);
            // Crystals melt where they arrive, so what an account holds is inert: its saved record is the whole material.
            return valid(q,"column station",()->new ColumnUnit.Station(port,(int)index,device,valve,account,inventory(held,s->new SolidMaterial(s.id(),s.revision(),s.density(),s.heatCapacity(),null))));});
        var totals=f.record(null,q->{var taken=q.record(null,FluidFacts::account);var produced=q.record(null,FluidFacts::account);double reboiler=q.f64(0),condenser=q.f64(0),pumparounds=q.f64(0);
            required(taken,q,"taken total");required(produced,q,"produced total");
            return valid(q,"column totals",()->new ColumnUnit.Totals(taken,produced,reboiler,condenser,pumparounds));});
        long condenser=f.count(0);boolean leaving=f.bool(false);
        // A unit written before schema 8 has one water side at most.
        var further=f.list(List.<Long>of(),q->q.count(0));
        var condensers=new ArrayList<Long>();if(condenser!=0)condensers.add(condenser);condensers.addAll(further);
        if(condenser==0&&!further.isEmpty())throw f.invalid("column condenser sides");
        // A unit written before schema 9 has no air condenser surface: its air takes any duty, as before.
        double air=f.f64(0);
        // A unit written before schema 21 has no utility sides: its reboiler and pumparound duties stay free until its next Start.
        var utilities=f.record(ColumnUnit.Utilities.NONE,q->{var reboiler=q.list(List.<Long>of(),p->p.count(0));var pumparounds=q.list(List.<List<Long>>of(),p->p.list(List.<Long>of(),s->s.count(0)));
            return valid(q,"column utilities",()->new ColumnUnit.Utilities(reboiler,pumparounds));});
        // A unit written before schema 21 has no members: none draws power until its next Start.
        var members=f.record(ColumnUnit.Members.NONE,q->{var fans=q.list(List.<Long>of(),p->p.count(0));var reflux=q.list(List.<Long>of(),p->p.count(0));var pumparounds=q.list(List.<List<Long>>of(),p->p.list(List.<Long>of(),s->s.count(0)));
            return valid(q,"column members",()->new ColumnUnit.Members(fans,reflux,pumparounds));});
        // A unit written before schema 29 (WP8) is a clean, new carbon steel column. A saved material is read through the
        // material's own aliases (Metallurgy.byName); a name it does not know refuses the world, as a device spec's does.
        double fouling=f.f64(0),loss=f.f64(0);String named=f.text(null);var material=com.wormzjl.icheme.science.integrity.Metallurgy.byName(named);
        if(named!=null&&com.wormzjl.icheme.science.integrity.Metallurgy.named(named)==null)throw f.invalid("unknown Metallurgy "+named);
        if(!(fouling>=0&&fouling<=1))throw f.invalid("column tray fouling "+fouling);
        if(!(loss>=0&&loss<=WorldTopologyLedger.IntegrityFacts.MAX_LOSS))throw f.invalid("column wall loss "+loss);
        required(master,f,"column origin");required(setpoints,f,"column setpoints");required(totals,f,"column totals");
        return valid(f,"column unit "+id,()->new ColumnUnit(id,master,running,setpoints,stations,totals,condensers,null,leaving,air,utilities,members,fouling,loss,material));
    }
}
