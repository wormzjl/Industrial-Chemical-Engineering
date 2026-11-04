package com.wormzjl.icheme.runtime.instrument;

import com.wormzjl.icheme.world.item.GaugeSettings;
import net.minecraft.nbt.*;
import java.util.*;
import java.util.function.Supplier;

/**
 * The records of the accessory store (AGENTS.md, "Saves and data formats"). Its facts are what the player attached: the
 * installed gauges with their settings, the pipe fittings, and the per-host slot revisions those records carry. Its
 * state, which the service computes again, is the pending operations and the retry deadline.
 *
 * <p>Facts schema {@link #SCHEMA}. A record is a compound read by name. A field is only ever added and never changes
 * meaning or unit, so a record an older build wrote lacks the newer fields only, and each reads the default written
 * beside its read: the behaviour before the field existed. A field of schema 1 has no earlier behaviour: a record without
 * it is damaged. A field this build does not read, like a later schema, was written by a newer build and refuses the
 * world. Enum constants and mount names are written by name; {@link #ALIASES} maps a renamed or removed one.</p>
 *
 * <p>State format {@link #STATE}, saved beside the facts. Another format, or state that does not validate, is dropped
 * and the facts load without it: an operation is pending until it commits, and only the commit shrinks the held stack
 * or refunds an item, so a dropped operation loses nothing.</p>
 */
final class AccessoryFacts {
    /** Schema 3 (batch 2026-11-03-rotating-machinery W3): the sensor kind SPEED and its mount speed_pickup; no new field. */
    static final int SCHEMA=3,STATE=1;
    /**
     * Renamed or removed saved names, per name set: the saved name to the current one. A gauge's kind and unit share the
     * item component's tables, so one alias serves both. None at schema 1.
     */
    private static final Map<String,Map<String,String>> ALIASES=Map.of("type",Map.of(),"mount",Map.of(),"kind",GaugeSettings.KIND_ALIASES,"unit",GaugeSettings.UNIT_ALIASES);
    private AccessoryFacts(){}

    /** The current name of {@code saved} in the name set {@code set}, through {@code aliases}. */
    static String canonical(String set,String saved,Map<String,Map<String,String>> aliases){return aliases.getOrDefault(set,Map.of()).getOrDefault(saved,saved);}

    /** One saved record read by name: a read without a default refuses a missing field; {@link #end} refuses the fields no read took. */
    static final class Fields {
        private final CompoundTag tag;private final String what;private final Set<String> read=new HashSet<>();
        Fields(CompoundTag tag,String what){this.tag=Objects.requireNonNull(tag);this.what=what;}
        IllegalArgumentException invalid(String reason){return new IllegalArgumentException(what+": "+reason);}
        private Tag field(String key,int type){read.add(key);var value=tag.get(key);if(value!=null&&value.getId()!=type)throw invalid(key+" is a tag of type "+value.getId()+", not "+type);return value;}
        private Tag required(String key,int type){var value=field(key,type);if(value==null)throw invalid("missing "+key);return value;}
        /** Marks {@code keys} as this build's fields without reading them: another reader takes them. */
        void claim(String... keys){read.addAll(List.of(keys));}
        boolean has(String key){return tag.contains(key);}
        int i32(String key,int absent){var value=field(key,Tag.TAG_INT);return value==null?absent:((NumericTag)value).getAsInt();}
        int i32(String key){return ((NumericTag)required(key,Tag.TAG_INT)).getAsInt();}
        long i64(String key,long absent){var value=field(key,Tag.TAG_LONG);return value==null?absent:((NumericTag)value).getAsLong();}
        long i64(String key){return ((NumericTag)required(key,Tag.TAG_LONG)).getAsLong();}
        double f64(String key){return ((NumericTag)required(key,Tag.TAG_DOUBLE)).getAsDouble();}
        boolean bool(String key){byte b=((NumericTag)required(key,Tag.TAG_BYTE)).getAsByte();if(b!=0&&b!=1)throw invalid(key+" is not a flag");return b==1;}
        String text(String key){return required(key,Tag.TAG_STRING).getAsString();}
        UUID uuid(String key){var value=(IntArrayTag)required(key,Tag.TAG_INT_ARRAY);if(value.size()!=4)throw invalid(key+" is not an identity");return NbtUtils.loadUUID(value);}
        <E extends Enum<E>> E name(String key,String set,Class<E> type) {
            String saved=text(key);
            try{return Enum.valueOf(type,canonical(set,saved,ALIASES));}catch(IllegalArgumentException unknown){throw invalid("unknown "+set+" "+saved);}
        }
        String mount(String key){String saved=text(key),name=canonical("mount",saved,ALIASES);if(!Instrument.MOUNTS.contains(name))throw invalid("unknown mount "+saved);return name;}
        CompoundTag record(String key){return (CompoundTag)required(key,Tag.TAG_COMPOUND);}
        List<CompoundTag> list(String key,List<CompoundTag> absent){var value=field(key,Tag.TAG_LIST);return value==null?absent:compounds(key,(ListTag)value);}
        List<CompoundTag> list(String key){return compounds(key,(ListTag)required(key,Tag.TAG_LIST));}
        private List<CompoundTag> compounds(String key,ListTag list){if(!list.isEmpty()&&list.getElementType()!=Tag.TAG_COMPOUND)throw invalid(key+" is not a list of records");return list.stream().map(CompoundTag.class::cast).toList();}
        /** Refuses the fields this build does not read: a newer build wrote them. */
        void end(){var extra=new TreeSet<>(tag.getAllKeys());extra.removeAll(read);if(!extra.isEmpty())throw invalid("fields "+extra+" this build does not read: written by a newer build");}
        /** {@code build}'s value, its validation failure refused as this record's. */
        <T> T valid(String record,Supplier<T> build){try{return build.get();}catch(IllegalArgumentException|NullPointerException failure){throw invalid(record+": "+failure.getMessage());}}
    }

    // ---------------- the facts records ----------------

    static CompoundTag settings(GaugeSettings s) {
        var t=new CompoundTag();t.putString("kind",s.kind().name());t.putDouble("minimum",s.minimum());t.putDouble("maximum",s.maximum());t.putString("unit",s.unit().name());t.putBoolean("relative",s.relative());return t;
    }
    static GaugeSettings settings(CompoundTag tag,String what) {
        var f=new Fields(tag,what+" settings");var kind=f.name("kind","kind",GaugeSettings.Kind.class);double minimum=f.f64("minimum"),maximum=f.f64("maximum");
        var unit=f.name("unit","unit",GaugeSettings.Unit.class);boolean relative=f.bool("relative");f.end();
        return f.valid("scale",()->new GaugeSettings(kind,minimum,maximum,unit,relative));
    }
    /** An installed gauge: the store's record, and the host's copy of it. */
    static CompoundTag installed(Instrument.Installed i) {
        var t=new CompoundTag();t.putLong("host",i.key().host());t.putString("mount",i.key().mount());t.putUUID("instance",i.instance());t.putLong("revision",i.revision());t.put("settings",settings(i.settings()));return t;
    }
    static Instrument.Installed installed(CompoundTag tag) {
        var f=new Fields(tag,"installed gauge");long host=f.i64("host");var mount=f.mount("mount");var instance=f.uuid("instance");long revision=f.i64("revision");
        var settings=settings(f.record("settings"),"installed gauge");f.end();
        return f.valid("gauge on host "+host,()->new Instrument.Installed(new Instrument.Key(host,mount),instance,revision,settings));
    }
    static CompoundTag fitting(InstrumentService.Store.Legacy fitting) {
        var t=new CompoundTag();t.putLong("host",fitting.key().host());t.putUUID("instance",fitting.instance());t.putLong("revision",fitting.revision());t.putString("type",fitting.type().name());return t;
    }
    static InstrumentService.Store.Legacy fitting(CompoundTag tag) {
        var f=new Fields(tag,"pipe fitting");long host=f.i64("host");var instance=f.uuid("instance");long revision=f.i64("revision");var type=f.name("type","type",Instrument.Type.class);f.end();
        return f.valid("fitting on host "+host,()->new InstrumentService.Store.Legacy(new Instrument.Key(host,"pipe_accessory"),instance,revision,type));
    }
    /** A host's slot revision: the counter its records and its block entity's copy are checked against. */
    static CompoundTag revision(long host,long revision){var t=new CompoundTag();t.putLong("host",host);t.putLong("revision",revision);return t;}
    static Map.Entry<Long,Long> revision(CompoundTag tag) {
        var f=new Fields(tag,"slot revision");long host=f.i64("host"),revision=f.i64("revision");f.end();
        if(host<=0||revision<=0)throw f.invalid("host "+host+" at revision "+revision);return Map.entry(host,revision);
    }

    // ---------------- the state records ----------------

    static CompoundTag pending(InstrumentService.Store.Pending p) {
        var t=new CompoundTag();t.putUUID("request",p.request());t.putUUID("player",p.player());t.putLong("host",p.key().host());t.putString("mount",p.key().mount());t.putUUID("instance",p.instance());
        t.putLong("revision",p.expectedRevision());t.putBoolean("install",p.install());t.putString("type",p.type().name());if(p.settings()!=null)t.put("settings",settings(p.settings()));
        t.putString("dimension",p.dimension());t.putLong("position",p.position());t.putInt("slot",p.slot());t.putBoolean("offhand",p.offhand());return t;
    }
    static InstrumentService.Store.Pending pending(CompoundTag tag) {
        var f=new Fields(tag,"pending operation");var request=f.uuid("request");var player=f.uuid("player");long host=f.i64("host");var mount=f.mount("mount");var instance=f.uuid("instance");
        long revision=f.i64("revision");boolean install=f.bool("install");var type=f.name("type","type",Instrument.Type.class);var settings=f.has("settings")?settings(f.record("settings"),"pending operation"):null;
        var dimension=f.text("dimension");long position=f.i64("position");int slot=f.i32("slot");boolean offhand=f.bool("offhand");f.end();
        return f.valid("operation "+request,()->new InstrumentService.Store.Pending(request,player,new Instrument.Key(host,mount),instance,revision,install,type,settings,dimension,position,slot,offhand));
    }
}
