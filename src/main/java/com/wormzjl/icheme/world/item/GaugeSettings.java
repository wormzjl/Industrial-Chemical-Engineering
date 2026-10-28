package com.wormzjl.icheme.world.item;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.wormzjl.icheme.science.display.DisplayUnit.PressureUnit;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import java.util.Locale;
import java.util.Map;

/** Absolute SI endpoints. Changing display units/reference preserves both physical endpoints. */
public record GaugeSettings(Kind kind, double minimum, double maximum, Unit unit, boolean relative) {
    public enum Kind { PRESSURE, TEMPERATURE; public int code(){return this==PRESSURE?0:1;} public static Kind fromCode(int c){return switch(c){case 0->PRESSURE;case 1->TEMPERATURE;default->throw new IllegalArgumentException("Invalid gauge kind");};} }
    public enum Unit {
        PA("Pa",1,0), KPA("kPa",1000,0), BAR("bar",100000,0), MPA("MPa",1000000,0),
        CELSIUS("°C",1,273.15), KELVIN("K",1,0);
        public final String symbol;
        public final double factor, offset;
        Unit(String symbol,double factor,double offset){this.symbol=symbol;this.factor=factor;this.offset=offset;}
        public int code(){return switch(this){case PA->0;case KPA->1;case BAR->2;case MPA->3;case CELSIUS->4;case KELVIN->5;};}
        public static Unit fromCode(int code){return switch(code){case 0->PA;case 1->KPA;case 2->BAR;case 3->MPA;case 4->CELSIUS;case 5->KELVIN;default->throw new IllegalArgumentException("Invalid gauge unit");};}
        /** Whether this unit reads {@code kind}: a pressure unit has a factor (Pa its own), a temperature unit none. */
        public boolean measures(Kind kind){return (kind==Kind.PRESSURE)==(factor!=1||this==PA);}
        /** The units of {@code kind}, smallest first, as the gauge's screen offers them. */
        public static java.util.List<Unit> of(Kind kind){return java.util.Arrays.stream(values()).filter(u->u.measures(kind)).toList();}
    }
    public static final double ATMOSPHERE=101325;
    public static final GaugeSettings PRESSURE=new GaugeSettings(Kind.PRESSURE,ATMOSPHERE,ATMOSPHERE+1e6,Unit.BAR,true);
    public static final GaugeSettings TEMPERATURE=new GaugeSettings(Kind.TEMPERATURE,273.15,473.15,Unit.CELSIUS,false);
    private record Encoded(String kind,double min,double max,String unit,boolean relative) {}
    /**
     * Renamed or removed saved names of {@link Kind} and {@link Unit} constants: the saved name to the current one (AGENTS.md,
     * "Saves and data formats"). The item component and the accessory store read the names through them. None yet.
     */
    public static final Map<String,String> KIND_ALIASES=Map.of(),UNIT_ALIASES=Map.of();
    public static final Codec<GaugeSettings> CODEC=codec(KIND_ALIASES,UNIT_ALIASES);
    /**
     * The item component's codec, its names read through {@code kinds} and {@code units}. A value it cannot read fails the
     * component: Minecraft drops that component and the stack keeps its item's default settings (owner decision 2026-10-26).
     */
    public static Codec<GaugeSettings> codec(Map<String,String> kinds,Map<String,String> units) {
        return RecordCodecBuilder.<Encoded>create(i->i.group(
            Codec.STRING.fieldOf("kind").forGetter(Encoded::kind),Codec.DOUBLE.fieldOf("minimum").forGetter(Encoded::min),
            Codec.DOUBLE.fieldOf("maximum").forGetter(Encoded::max),Codec.STRING.fieldOf("unit").forGetter(Encoded::unit),
            Codec.BOOL.fieldOf("relative").forGetter(Encoded::relative)).apply(i,Encoded::new)).comapFlatMap(e->{
                try{return DataResult.success(new GaugeSettings(Kind.valueOf(kinds.getOrDefault(e.kind,e.kind)),e.min,e.max,Unit.valueOf(units.getOrDefault(e.unit,e.unit)),e.relative));}
                catch(IllegalArgumentException ex){return DataResult.error(()->"Invalid gauge settings: "+ex.getMessage());}
            },s->new Encoded(s.kind.name(),s.minimum,s.maximum,s.unit.name(),s.relative));
    }
    public static final StreamCodec<ByteBuf,GaugeSettings> STREAM_CODEC=new StreamCodec<>() {
        public GaugeSettings decode(ByteBuf b){int kind=b.readUnsignedByte(),unit=b.readUnsignedByte();if(kind>=Kind.values().length||unit>=Unit.values().length)throw new IllegalArgumentException("Invalid gauge units");return new GaugeSettings(Kind.fromCode(kind),b.readDouble(),b.readDouble(),Unit.fromCode(unit),b.readBoolean());}
        public void encode(ByteBuf b,GaugeSettings s){b.writeByte(s.kind.code());b.writeByte(s.unit.code());b.writeDouble(s.minimum);b.writeDouble(s.maximum);b.writeBoolean(s.relative);}
    };
    public GaugeSettings {
        if(kind==null||unit==null||!Double.isFinite(minimum)||!Double.isFinite(maximum)||minimum<0||!(maximum>minimum)||!Double.isFinite(maximum-minimum))throw new IllegalArgumentException("Finite non-negative endpoints and a positive finite span required");
        if(!unit.measures(kind)||kind==Kind.TEMPERATURE&&relative)throw new IllegalArgumentException("Gauge kind/unit mismatch");
    }
    public double display(double si){return (si-unit.offset-(relative?ATMOSPHERE:0))/unit.factor;}
    public double si(double displayed){return displayed*unit.factor+unit.offset+(relative?ATMOSPHERE:0);}
    public String label(){return unit.symbol+(kind==Kind.PRESSURE?(relative?"(g)":"(a)"):"");}
    public String number(double value){if(kind==Kind.PRESSURE)return pressureUnit().number(value-(relative?ATMOSPHERE:0));return String.format(Locale.ROOT,"%.1f",display(value));}
    private PressureUnit pressureUnit(){return switch(unit){case PA->PressureUnit.PASCAL;case KPA->PressureUnit.KILOPASCAL;case BAR->PressureUnit.BAR;case MPA->PressureUnit.MEGAPASCAL;default->throw new IllegalStateException();};}
    public GaugeSettings displayAs(Unit unit,boolean relative){return new GaugeSettings(kind,minimum,maximum,unit,relative);}
    public double needle(double value){return Math.clamp((value-minimum)/(maximum-minimum),0,1);}
    public boolean outside(double value){return value<minimum||value>maximum;}
}
