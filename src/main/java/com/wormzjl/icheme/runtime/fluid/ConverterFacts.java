package com.wormzjl.icheme.runtime.fluid;

import static com.wormzjl.icheme.runtime.fluid.FluidFacts.valid;

import com.wormzjl.icheme.runtime.fluid.FluidFacts.Fields;
import com.wormzjl.icheme.runtime.fluid.FluidFacts.Record;
import java.util.*;

/**
 * The fluid converters' facts section (schema 24, batch 2026-11-12-fluid-conversion), the record's last field today: the
 * converters by their vessels' identities; a world written before schema 24 has none.
 */
final class ConverterFacts implements FactsSection {
    @Override public void write(Fields f,WorldTopologyLedger.Snapshot s) {
        // Schema 24, last: the fluid converters, by their vessels' identities.
        f.list(new TreeMap<>(s.converters()).values(),ConverterFacts::converter);
    }
    @Override public void read(Record f,FluidFacts.World.Builder world) {
        // Schema 24: the fluid converters. A world written before schema 24 has none.
        var converters=f.list(List.<ConverterUnit>of(),ConverterFacts::converter);
        world.converters(converters);
    }
    /**
     * A fluid converter (schema 24, batch 2026-11-12-fluid-conversion): its vessel's identity, its drain port and valve, its
     * fill port and valve (0 for none), whether it is the closed variant, its mode by name, its rate (L per process second),
     * deadband and four level thresholds (fill fractions; a facts number is finite, so none is written -1), its Forge tank's capacity, fluid id and contents
     * (mB), its remainder (L), and its account's fluid id and litres. Every field absent reads as a converter built empty and off.
     */
    private static void converter(Fields f,ConverterUnit u) {
        f.count(u.id()).count(u.drain()).count(u.drainValve()).count(u.fill()).count(u.fillValve()).bool(u.closed())
                .name(u.mode()).f64(u.maxRate()).f64(u.deadband()).list(Arrays.stream(u.thresholds()).boxed().toList(),(q,t)->q.f64(Double.isNaN(t)?-1:t))
                .count(u.capacity()).text(u.fluid()).count(u.stored()).f64(u.remainder()).text(u.accountFluid()).f64(u.account());
    }
    private static ConverterUnit converter(Record f) {
        long id=f.count(0),drain=f.count(0),drainValve=f.count(0),fill=f.count(0),fillValve=f.count(0);boolean closed=f.bool(false);
        var mode=f.name(ConverterUnit.Mode.class,ConverterUnit.Mode.OFF);double rate=f.f64(0),deadband=f.f64(ConverterUnit.DEFAULT_DEADBAND);
        var none=new double[ConverterUnit.THRESHOLDS];Arrays.fill(none,Double.NaN);
        var saved=f.list(Arrays.stream(none).boxed().toList(),q->{double t=q.f64(-1);return t<0?Double.NaN:t;});
        if(saved.size()!=ConverterUnit.THRESHOLDS)throw f.invalid("fluid converter "+id+": "+saved.size()+" level thresholds");
        var thresholds=saved.stream().mapToDouble(Double::doubleValue).toArray();
        long capacity=f.count(0);var fluid=f.text("");long stored=f.count(0);double remainder=f.f64(0);var accountFluid=f.text("");double account=f.f64(0);
        return valid(f,"fluid converter "+id,()->new ConverterUnit(id,drain,drainValve,fill,fillValve,closed,mode,rate,deadband,thresholds,capacity,fluid,stored,remainder,accountFluid,account));
    }
}
