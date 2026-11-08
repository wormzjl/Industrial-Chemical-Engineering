package com.wormzjl.icheme.runtime.instrument;

import com.wormzjl.icheme.world.item.GaugeSettings;
import com.wormzjl.icheme.world.item.GaugeSettings.Kind;
import com.wormzjl.icheme.world.item.GaugeSettings.Unit;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Backward compatibility of the accessory store (AGENTS.md, "Saves and data formats"; batch
 * 2026-10-26-accessory-store-compat): its facts are read by name with defaults, a later schema, a field from a newer
 * build, an unknown name or damaged facts refuse the world with its file as it is, state it cannot use is dropped with
 * every fact kept, and every corpus image under {@code compat/accessories/} still reads to what it was saved with.
 */
class AccessoryStoreCompatTest {
    /** Gauges of both kinds on pipe and reservoir mounts, a fitting of every type, slot tombstones, two pending operations. */
    static InstrumentService.Store corpusStore() {
        var s=new InstrumentService.Store();var player=new UUID(0x5eed,1);
        install(s,11,"pipe_north",new GaugeSettings(Kind.PRESSURE,0,500000,Unit.KPA,false),1);
        install(s,12,"pipe_east",new GaugeSettings(Kind.TEMPERATURE,250,600,Unit.KELVIN,false),2);
        install(s,21,"pressure_top",GaugeSettings.PRESSURE,3);
        install(s,21,"pressure_bottom",new GaugeSettings(Kind.PRESSURE,0,5e6,Unit.MPA,false),4);
        install(s,21,"temperature_top",GaugeSettings.TEMPERATURE,5);
        var removed=install(s,21,"temperature_bottom",GaugeSettings.TEMPERATURE,6);assertNotNull(s.remove(removed.key(),removed.instance()));
        var tombstone=install(s,41,"pipe_west",new GaugeSettings(Kind.PRESSURE,101325,201325,Unit.PA,true),7);assertNotNull(s.remove(tombstone.key(),tombstone.instance()));
        long host=31;
        for(var type:List.of(Instrument.Type.SIGHT_GLASS,Instrument.Type.SHUTOFF,Instrument.Type.CHECK,Instrument.Type.PRESSURE_VALVE,Instrument.Type.FILTER,Instrument.Type.PUMP,Instrument.Type.COMPRESSOR))s.installLegacy(host++,type);
        var first=s.installLegacy(51,Instrument.Type.FILTER);assertNotNull(s.removeLegacy(51,first.instance()));s.installLegacy(51,Instrument.Type.PUMP);
        assertTrue(s.queue(new InstrumentService.Store.Pending(new UUID(0xa11,1),player,new Instrument.Key(21,"temperature_bottom"),new UUID(0x9a,8),s.revision(21),true,Instrument.Type.TEMPERATURE,
                new GaugeSettings(Kind.TEMPERATURE,273.15,373.15,Unit.CELSIUS,false),"minecraft:overworld",new BlockPos(4,70,-9).asLong(),3,false)));
        var gauge=s.get(new Instrument.Key(11,"pipe_north"));
        assertTrue(s.queue(new InstrumentService.Store.Pending(new UUID(0xa11,2),player,gauge.key(),gauge.instance(),s.revision(11),false,Instrument.Type.PRESSURE,null,"minecraft:the_nether",new BlockPos(-30,40,12).asLong(),0,true)));
        s.installSensor(new Instrument.Key(61,"pipe_north"),new UUID(0x5e,1),Sensor.Kind.PRESSURE,0);
        s.installSensor(new Instrument.Key(62,"pipe_east"),new UUID(0x5e,2),Sensor.Kind.FLOW,0);
        s.installSensor(new Instrument.Key(63,"level_body"),new UUID(0x5e,3),Sensor.Kind.LEVEL,0);
        s.installSensor(new Instrument.Key(63,"level_boot"),new UUID(0x5e,4),Sensor.Kind.LEVEL,s.revision(63));
        s.installLegacy(64,Instrument.Type.PRESSURE_VALVE);s.installSensor(new Instrument.Key(64,"valve_sensor"),new UUID(0x5e,5),Sensor.Kind.PRESSURE,s.revision(64));
        // Accessory facts schema 3 (batch 2026-11-03-rotating-machinery W3): a steam turbine's speed pickup.
        s.installSensor(new Instrument.Key(65,Sensor.SPEED_PICKUP),new UUID(0x5e,6),Sensor.Kind.SPEED,0);
        s.nextDue(123456);
        return s;
    }
    private static Instrument.Installed install(InstrumentService.Store s,long host,String mount,GaugeSettings settings,int n) {
        var type=settings.kind()==Kind.PRESSURE?Instrument.Type.PRESSURE:Instrument.Type.TEMPERATURE;
        var p=new InstrumentService.Store.Pending(new UUID(0xbeef,n),new UUID(0x5eed,1),new Instrument.Key(host,mount),new UUID(0x9a,n),s.revision(host),true,type,settings,"minecraft:overworld",new BlockPos(n,64,n).asLong(),n%9,false);
        assertTrue(s.queue(p));var installed=s.install(p);assertNotNull(installed);s.finish(p.request());return installed;
    }
    /** Every fact (and the state) of {@code s} on {@code hosts}, field by field: what a corpus image records it saved. */
    static String describe(List<Long> hosts,InstrumentService.Store s) {
        var out=new StringBuilder();
        for(var i:s.installed()){var g=i.settings();out.append("installed ").append(i.key().host()).append('/').append(i.key().mount()).append(' ').append(i.instance()).append(" r").append(i.revision())
                .append(' ').append(g.kind()).append(' ').append(g.minimum()).append("..").append(g.maximum()).append(' ').append(g.unit()).append(g.relative()?" relative":"").append("; ");}
        for(long h:hosts){var f=s.legacy(h);if(f!=null)out.append("fitting ").append(h).append(' ').append(f.instance()).append(" r").append(f.revision()).append(' ').append(f.type()).append("; ");}
        for(long h:hosts)out.append("revision ").append(h).append('=').append(s.revision(h)).append("; ");
        for(var p:s.pending()){out.append("pending ").append(p.request()).append(' ').append(p.player()).append(' ').append(p.key().host()).append('/').append(p.key().mount()).append(' ').append(p.instance())
                .append(" r").append(p.expectedRevision()).append(p.install()?" install ":" remove ").append(p.type()).append(' ').append(p.settings()==null?"-":p.settings().kind()+" "+p.settings().minimum()+".."+p.settings().maximum()+" "+p.settings().unit()+(p.settings().relative()?" relative":""))
                .append(' ').append(p.dimension()).append(' ').append(p.position()).append(" slot ").append(p.slot()).append(p.offhand()?" offhand":"").append("; ");}
        for(long host:hosts)for(var sensor:s.sensors(host))out.append("sensor ").append(sensor.key().host()).append('/').append(sensor.key().mount()).append(' ').append(sensor.instance()).append(" r").append(sensor.revision()).append(' ').append(sensor.kind()).append("; ");
        for(long host:new TreeSet<>(s.modular()))out.append("modular ").append(host).append("; ");
        return out.append("next_due ").append(s.nextDue()).toString();
    }
    static String describe(InstrumentService.Store s){return describe(new TreeSet<>(s.revisions().keySet()).stream().toList(),s);}
    private static void assertSameFacts(InstrumentService.Store expected,InstrumentService.Store actual) {
        assertEquals(expected.installed(),actual.installed());assertEquals(expected.revisions(),actual.revisions());
        for(long host:expected.revisions().keySet())assertEquals(expected.legacy(host),actual.legacy(host),"fitting on host "+host);
    }
    private static String refusal(CompoundTag saved){return assertThrows(IllegalArgumentException.class,()->InstrumentService.Store.load(saved)).getMessage();}

    @Test void aStoreRoundTripsEveryRecordExactly() {
        var s=corpusStore();var saved=s.save(new CompoundTag());var loaded=InstrumentService.Store.load(saved);
        assertEquals(describe(s),describe(loaded));assertEquals(saved,loaded.save(new CompoundTag()));
    }

    @Test void aLaterSchemaOrAFieldFromANewerBuildRefuses() {
        var saved=corpusStore().save(new CompoundTag());
        var later=saved.copy();later.putInt("format",AccessoryFacts.SCHEMA+1);
        var reason=refusal(later);assertTrue(reason.contains("facts schema "+(AccessoryFacts.SCHEMA+1))&&reason.contains("newer build"),reason);
        var root=saved.copy();root.putString("paint","red");assertTrue(refusal(root).contains("[paint] this build does not read: written by a newer build"));
        var gauge=saved.copy();gauge.getList("installed",10).getCompound(0).putDouble("damping",2);assertTrue(refusal(gauge).contains("newer build"));
        var scale=saved.copy();scale.getList("installed",10).getCompound(0).getCompound("settings").putBoolean("logarithmic",true);assertTrue(refusal(scale).contains("newer build"));
        var fitting=saved.copy();fitting.getList("fittings",10).getCompound(0).putInt("opening",50);assertTrue(refusal(fitting).contains("newer build"));
        var revision=saved.copy();revision.getList("revisions",10).getCompound(0).putLong("since",1);assertTrue(refusal(revision).contains("newer build"));
    }

    @Test void anUnknownNameRefusesAndAnAliasMapsItToTheCurrentOne() {
        var saved=corpusStore().save(new CompoundTag());
        var type=saved.copy();type.getList("fittings",10).getCompound(0).putString("type","GEARBOX");assertTrue(refusal(type).contains("unknown type GEARBOX"));
        var mount=saved.copy();mount.getList("installed",10).getCompound(0).putString("mount","pipe_up");assertTrue(refusal(mount).contains("unknown mount pipe_up"));
        var unit=saved.copy();unit.getList("installed",10).getCompound(0).getCompound("settings").putString("unit","PSI");assertTrue(refusal(unit).contains("unknown unit PSI"));
        var aliases=Map.of("unit",Map.of("PSIG","BAR"));
        assertEquals("BAR",AccessoryFacts.canonical("unit","PSIG",aliases));assertEquals("KPA",AccessoryFacts.canonical("unit","KPA",aliases));assertEquals("PSIG",AccessoryFacts.canonical("kind","PSIG",aliases));
    }

    @Test void damagedFactsRefuse() {
        var saved=corpusStore().save(new CompoundTag());
        var missing=saved.copy();missing.remove("installed");assertTrue(refusal(missing).contains("missing installed"));
        var unversioned=saved.copy();unversioned.remove("format");assertTrue(refusal(unversioned).contains("missing format"));
        var host=saved.copy();host.getList("installed",10).getCompound(0).putString("host","eleven");assertTrue(refusal(host).contains("host is a tag of type"));
        var ahead=saved.copy();ahead.getList("installed",10).getCompound(0).putLong("revision",99);assertTrue(refusal(ahead).contains("conflicts with its host's records"));
        var scale=saved.copy();scale.getList("installed",10).getCompound(0).getCompound("settings").putDouble("minimum",1e9);assertTrue(refusal(scale).contains("scale"));
        var twice=saved.copy();twice.getList("revisions",10).add(twice.getList("revisions",10).getCompound(0).copy());assertTrue(refusal(twice).contains("two revisions"));
        var identity=saved.copy();identity.getList("fittings",10).getCompound(0).putIntArray("instance",new int[]{1,2,3});assertTrue(refusal(identity).contains("not an identity"));
    }

    /** State is what the service computes again: another state format, or state that does not validate, is dropped and every fact is kept. */
    @Test void anotherStateFormatOrUnusableStateIsDroppedWithEveryFactKept() {
        var s=corpusStore();var saved=s.save(new CompoundTag());
        var other=saved.copy();other.putInt("state_format",AccessoryFacts.STATE+1);
        var damaged=saved.copy();damaged.getList("pending",10).getCompound(0).putString("settings","none");
        var newer=saved.copy();newer.getList("pending",10).getCompound(1).putLong("deadline",5);
        var deadline=saved.copy();deadline.putInt("next_due",5);
        for(var image:List.of(other,damaged,newer,deadline)) {
            var loaded=InstrumentService.Store.load(image);
            assertSameFacts(s,loaded);assertTrue(loaded.pending().isEmpty());assertEquals(0,loaded.nextDue());
        }
    }

    /** A host's block-entity copy is state the store restores at bind: a copy this build cannot read is dropped, never thrown at the block entity's load. */
    @Test void aHostCopyThisBuildCannotReadIsDroppedNotThrown() {
        var s=corpusStore();var copy=new Instrument.Metadata();copy.set(s.host(21),s.revision(21));
        var tag=new CompoundTag();copy.write(tag);var read=new Instrument.Metadata();read.read(tag);
        assertEquals(s.host(21),read.installed());assertEquals(s.revision(21),read.revision());
        tag.getList("Instruments",10).getCompound(0).getCompound("settings").putBoolean("logarithmic",true);
        assertDoesNotThrow(()->read.read(tag));assertTrue(read.installed().isEmpty());assertEquals(0,read.revision());
    }

    /** A world's store file: none starts empty; one this build reads loads; one it cannot read refuses the world, the file left as it is. */
    @Test void anUnreadableStoreFileRefusesTheWorldAndIsLeftAsItIs(@TempDir Path folder) throws IOException {
        var file=folder.resolve(InstrumentService.Store.DATA_NAME+".dat");
        assertTrue(InstrumentService.Store.read(file).installed().isEmpty());assertFalse(Files.exists(file),"reading writes nothing");
        var s=corpusStore();var root=new CompoundTag();root.put("data",s.save(new CompoundTag()));root.putInt("DataVersion",3955);NbtIo.writeCompressed(root,file);
        assertSameFacts(s,InstrumentService.Store.read(file));
        root.getCompound("data").putInt("format",AccessoryFacts.SCHEMA+1);NbtIo.writeCompressed(root,file);
        for(var image:List.of(Files.readAllBytes(file),new byte[]{0x1f,(byte)0x8b,8,0,1,2,3})) {
            Files.write(file,image);var modified=Files.getLastModifiedTime(file);
            var refused=assertThrows(IllegalStateException.class,()->InstrumentService.Store.read(file)).getMessage();
            assertTrue(refused.contains("Refusing to open the world")&&refused.contains(file.toString()),refused);
            assertArrayEquals(image,Files.readAllBytes(file));assertEquals(modified,Files.getLastModifiedTime(file));
        }
    }

    // ---------------- the corpus: one image per facts schema ----------------

    private static final Path CORPUS=Path.of("src/test/resources/compat/accessories");

    /**
     * Every corpus image reads to the facts and state recorded beside it, and saves back to a store that reads the same.
     * The image of this build's schema must exist (AGENTS.md: a facts-schema change adds one); when it is missing the test
     * writes it under {@code build/compat/accessories/} to be copied into the corpus. The schema-1 image was written by
     * 0.13.0, before the state format existed.
     */
    @Test void everyCorpusImageReadsToWhatItWasSavedWith() throws IOException, com.mojang.brigadier.exceptions.CommandSyntaxException {
        var current=CORPUS.resolve("schema-"+AccessoryFacts.SCHEMA+".snbt");
        if(!Files.exists(current)) {
            var s=corpusStore();var written=Path.of("build/compat/accessories/schema-"+AccessoryFacts.SCHEMA+".snbt");Files.createDirectories(written.getParent());
            var entry=new CompoundTag();entry.put("Saved",s.save(new CompoundTag()));entry.putString("Expected",describe(s));
            Files.write(written,List.of(entry.toString()),StandardCharsets.UTF_8);
            fail("No corpus image of facts schema "+AccessoryFacts.SCHEMA+": copy "+written.toAbsolutePath()+" to "+current);
        }
        try(var files=Files.list(CORPUS)) {
            for(var file:files.sorted().toList())
                for(var line:Files.readAllLines(file,StandardCharsets.UTF_8)) {
                    if(line.isBlank())continue;
                    var entry=TagParser.parseTag(line);var loaded=InstrumentService.Store.load(entry.getCompound("Saved"));
                    assertEquals(entry.getString("Expected"),describe(loaded),file.toString());
                    assertEquals(entry.getString("Expected"),describe(InstrumentService.Store.load(loaded.save(new CompoundTag()))),file+" saved again");
                }
        }
    }
}
