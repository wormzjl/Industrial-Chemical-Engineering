package com.wormzjl.icheme.runtime.instrument;

import com.wormzjl.icheme.world.item.GaugeSettings;
import net.minecraft.nbt.*;
import java.nio.file.*;
import java.util.*;

public class ExportGaugeCorpus {
    private static InstrumentService.Store.Pending operation(InstrumentService.Store store,long host,int number,Instrument.Type type) {
        boolean dial=type==Instrument.Type.PRESSURE;
        return new InstrumentService.Store.Pending(new UUID(0xadd,number),new UUID(0x5eed,1),
                new Instrument.Key(host,dial?"pressure_bottom":"tank_level"),new UUID(0x1e,number),store.revision(host),true,type,
                dial?GaugeSettings.PRESSURE:null,"minecraft:overworld",0,0,false);
    }
    private static void install(InstrumentService.Store store,long host,int number,Instrument.Type type) {
        var op=operation(store,host,number,type);
        if(!store.queue(op))throw new IllegalStateException("Could not create corpus operation");
        if(type==Instrument.Type.LEVEL_GAUGE)store.installFitting(op);else store.install(op);
        store.finish(op.request());
    }
    public static void main(String[] args)throws Exception {
        var entry=TagParser.parseTag(Files.readString(Path.of("src/test/resources/compat/accessories/schema-3.snbt")).strip());
        var current=InstrumentService.Store.load(entry.getCompound("Saved"));
        install(current,21,21,Instrument.Type.LEVEL_GAUGE);
        install(current,63,63,Instrument.Type.LEVEL_GAUGE);
        current.queue(operation(current,41,41,Instrument.Type.LEVEL_GAUGE));
        write(current,current.save(new CompoundTag()),"schema-4.snbt");
        var earlier=new InstrumentService.Store();
        install(earlier,7,7,Instrument.Type.LEVEL_GAUGE);
        install(earlier,7,8,Instrument.Type.PRESSURE);
        var image=earlier.save(new CompoundTag());image.putInt("format",3);image.remove("sensors");image.remove("modular_valves");
        write(earlier,image,"schema-3-gauge.snbt");
    }
    private static void write(InstrumentService.Store store,CompoundTag image,String name)throws Exception {
        var entry=new CompoundTag();entry.put("Saved",image);entry.putString("Expected",AccessoryStoreCompatTest.describe(store));
        Files.writeString(Path.of("tmp/gauge-merge",name),entry+System.lineSeparator());
        System.out.println("Wrote "+name);
    }
}
