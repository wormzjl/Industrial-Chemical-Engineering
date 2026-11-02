package com.wormzjl.icheme.client.gui.screens.inventory;

import com.google.gson.*;
import com.wormzjl.icheme.science.column.v3.*;
import com.wormzjl.icheme.runtime.fluid.SlurryFeed;
import com.wormzjl.icheme.science.column.v3.V3ColumnSpecification.ControlledQuantity;
import com.wormzjl.icheme.science.fluid.state.ParticleSize;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import static com.wormzjl.icheme.science.material.JsonFields.*;

/** Explicitly refreshed, client-owned JSON libraries. Neither custom paths nor files cross the wire. */
final class V3ClientPresets {
    static final int MAX_BYTES=65536,MAX_FILES=256;
    /**
     * {@code oldest} to {@code schema} are the versions a file of the kind may carry, {@code schema} the one written. Column 2
     * added {@code stage_pressure_drop_kpa}; since 2026-10-28 (owner choice (a)) it is no longer written, and a file's is
     * ignored: the tray hydraulics give the drop. Column 3 holds no feed ({@link V3ColumnTemplate}). A mixture's {@code package},
     * which the older builds wrote, is ignored: a stream sits on whatever holds its parts.
     */
    enum Kind {
        COLUMN("columnpreset","column",2,3),MIXTURE("mixturepreset","mixture",1,1);
        final String folder,id;final int oldest,schema;
        Kind(String folder,String id,int oldest,int schema){this.folder=folder;this.id=id;this.oldest=oldest;this.schema=schema;}
    }
    sealed interface Preset permits Column,Mixture {String name();String translationKey();}
    record Column(String name,String translationKey,V3ColumnTemplate template) implements Preset {}
    /**
     * {@code solids} is the optional {@code "solids"} object of a mixture file (a fluid generator loads it; the column ignores
     * it). {@code temperature} (K), {@code pressure} (bar) and {@code flow} (kmol/h) are the stream's conditions where it has them.
     */
    record Mixture(String name,String translationKey,boolean mass,Map<String,Double> amounts,SlurryFeed solids,
            OptionalDouble temperature,OptionalDouble pressure,OptionalDouble flow) implements Preset {
        Mixture {amounts=Collections.unmodifiableMap(new LinkedHashMap<>(amounts));Objects.requireNonNull(solids);}
    }
    record Entry(Preset preset,boolean bundled,String file){}
    record Problem(String file,String reason){}
    record Snapshot(List<Entry> entries,List<Problem> problems){
        Snapshot{entries=List.copyOf(entries);problems=List.copyOf(problems);}
    }
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Path root;
    private final Function<String,InputStream> resources;
    V3ClientPresets(Path gameDirectory,Function<String,InputStream> resources){
        root=gameDirectory.toAbsolutePath().normalize().resolve("IChemE");this.resources=resources;
    }
    Path directory(Kind kind){return root.resolve(kind.folder);}
    Snapshot refresh(Kind kind){
        List<Entry> entries=new ArrayList<>();List<Problem> problems=new ArrayList<>();
        String prefix="assets/icheme/"+kind.folder+"/";
        try(InputStream stream=resources.apply(prefix+"index.json")){
            if(stream==null)throw new IOException("Missing bundled index");
            var index=JsonParser.parseString(read(stream)).getAsJsonArray();
            if(index.size()>MAX_FILES)throw new IOException("Too many bundled files");
            for(var item:index){
                String file=item.getAsString();
                if(!file.matches("[a-z0-9_-]+\\.json"))throw new IOException("Invalid bundled file");
                try(InputStream resource=resources.apply(prefix+file)){
                    if(resource==null)throw new IOException("Missing bundled file");
                    entries.add(new Entry(decode(read(resource),kind),true,file));
                }catch(RuntimeException|IOException e){problems.add(new Problem(file,e.getMessage()));}
            }
        }catch(RuntimeException|IOException e){problems.add(new Problem("index.json",e.getMessage()));}
        Path directory=directory(kind);
        try{Files.createDirectories(directory);}catch(IOException e){problems.add(new Problem(directory.toString(),e.getMessage()));}
        if(Files.exists(directory)){
            try(var stream=Files.list(directory)){
                var files=stream.filter(p->p.getFileName().toString().endsWith(".json")).sorted().limit(MAX_FILES+1L).toList();
                if(files.size()>MAX_FILES)problems.add(new Problem(directory.getFileName().toString(),"Too many custom files"));
                for(Path path:files.stream().limit(MAX_FILES).toList()){
                    String file=path.getFileName().toString();
                    try{
                        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>MAX_BYTES)throw new IOException("Invalid preset file");
                        try(InputStream input=Files.newInputStream(path)){entries.add(new Entry(decode(read(input),kind),false,file));}
                    }catch(RuntimeException|IOException e){problems.add(new Problem(file,e.getMessage()));}
                }
            }catch(IOException e){problems.add(new Problem(directory.toString(),e.getMessage()));}
        }
        return new Snapshot(entries,problems);
    }
    Path save(Kind kind,String name,String encoded)throws IOException{
        decode(encoded,kind);
        if(name==null||name.isBlank()||name.length()>64||!Character.isLetterOrDigit(name.codePointAt(0))||!name.codePoints().allMatch(c->Character.isLetterOrDigit(c)||c==32||c==95||c==45))throw new IllegalArgumentException("Invalid preset filename");
        byte[] bytes=encoded.getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_BYTES)throw new IOException("Preset exceeds size limit");
        Path directory=directory(kind);Files.createDirectories(directory);
        String stem=name.strip().replace(' ','_');
        for(int i=0;i<10000;i++){
            Path path=directory.resolve(stem+(i==0?"":"_"+i)+".json").normalize();
            if(!path.getParent().equals(directory))throw new IOException("Invalid preset path");
            try{Files.write(path,bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);return path;}
            catch(FileAlreadyExistsException exists){/* Preserve an existing user file. */}
        }
        throw new IOException("No available preset filename");
    }
    private static String read(InputStream stream)throws IOException{
        byte[] bytes=stream.readNBytes(MAX_BYTES+1);
        if(bytes.length>MAX_BYTES)throw new IOException("Preset exceeds size limit");
        return new String(bytes,StandardCharsets.UTF_8);
    }
    static Preset decode(String json,Kind kind){
        try{
            JsonObject o=JsonParser.parseString(json).getAsJsonObject();int schema=integer(o,"schema_version");
            if(schema<kind.oldest||schema>kind.schema||!string(o,"kind").equals(kind.id))throw new IllegalArgumentException("Unsupported preset schema");
            String name=string(o,"name"),key=string(o,"translation_key");
            if(name.isBlank()||name.length()>128||key.length()>128)throw new IllegalArgumentException("Invalid preset name");
            if(kind==Kind.COLUMN)return new Column(name,key,V3ColumnTemplate.read(o));
            String basis=string(o,"basis");
            if(!Set.of("mole","mass").contains(basis))throw new IllegalArgumentException("Invalid mixture basis");
            Map<String,Double> amounts=new LinkedHashMap<>();double total=0;
            for(var element:bounded(o,"components",64)){
                var c=element.getAsJsonObject();String id=string(c,"id");double value=number(c,"amount");
                if(!id.matches("[A-Za-z][A-Za-z0-9_.:-]{0,63}")||value<0||amounts.putIfAbsent(id,value)!=null)throw new IllegalArgumentException("Invalid component amount");
                total+=value;
            }
            if(!Double.isFinite(total)||total<=0)throw new IllegalArgumentException("Empty mixture");
            return new Mixture(name,key,basis.equals("mass"),amounts,solids(o),condition(o,"temperature_kelvin"),condition(o,"pressure_bar"),condition(o,"flow_kmol_h"));
        }catch(IllegalStateException|NullPointerException|ClassCastException e){throw new IllegalArgumentException("Invalid preset JSON",e);}
    }
    private static OptionalDouble condition(JsonObject o,String key){return o.has(key)?OptionalDouble.of(positive(o,key)):OptionalDouble.empty();}
    /**
     * A mixture's optional solids: {@code {"volume_fraction_percent": 10, "grades": [{"material": "icheme:demo_particle",
     * "diameter_um": 100, "mass_share": 1}]}} (at most 64 grades). Absent means no solids; the record checks the ranges.
     */
    private static SlurryFeed solids(JsonObject o){
        var value=o.get("solids");if(value==null)return SlurryFeed.NONE;
        if(!value.isJsonObject())throw new IllegalArgumentException("Invalid solids");
        var s=value.getAsJsonObject();var grades=new ArrayList<SlurryFeed.Grade>();
        for(var element:bounded(s,"grades",64)){
            var g=element.getAsJsonObject();number(g,"diameter_um");
            grades.add(new SlurryFeed.Grade(string(g,"material"),ParticleSize.micrometres(g.getAsJsonPrimitive("diameter_um").getAsBigDecimal().toPlainString()),number(g,"mass_share")));
        }
        return new SlurryFeed(number(s,"volume_fraction_percent")/100,grades);
    }
    static String mixtureJson(String name,V3CompositionDraft composition){
        JsonObject o=header(name,Kind.MIXTURE);
        o.addProperty("basis",composition.mass()?"mass":"mole");JsonArray rows=new JsonArray();
        composition.relativeAmounts().forEach((id,amount)->{var c=new JsonObject();c.addProperty("id",id);c.addProperty("amount",amount);rows.add(c);});
        o.add("components",rows);return JSON.toJson(o);
    }
    static String columnJson(String name,V3ColumnInput input){
        JsonObject o=header(name,Kind.COLUMN);template(input).write(o);return JSON.toJson(o);
    }
    /** The column of {@code input}, with its feed as the design feed. */
    private static V3ColumnTemplate template(V3ColumnInput input){
        return new V3ColumnTemplate(Arrays.stream(input.feedComponentMolarFlowsMolPerSecond()).sum()*3.6,input.stageCount()+2,input.feedStageNumber()+1,
            V3EditorDraft.value(input,ControlledQuantity.CONDENSER_OUTLET_TEMPERATURE),V3EditorDraft.value(input,ControlledQuantity.REBOILER_DUTY)/1e6,
            V3EditorDraft.value(input,ControlledQuantity.ORGANIC_REFLUX_RATIO),input.topPressurePascal()/1e5,input.columnDiameterMetres(),
            input.sideDraws().stream().map(d->new V3ColumnTemplate.Draw(d.trayNumber()+1,d.molarFlowMolPerSecond()*3.6)).toList(),
            input.steamFeeds().stream().map(t->new V3ColumnTemplate.Steam(t.stageNumber()+1,t.molarFlowMolPerSecond()*3.6,t.temperatureKelvin())).toList(),
            input.pumparounds().stream().map(c->new V3ColumnTemplate.Pumparound(c.drawTray()+1,c.returnTray()+1,-c.dutyWatts()/1e6,c.split())).toList());
    }
    private static JsonObject header(String name,Kind kind){
        var o=new JsonObject();o.addProperty("schema_version",kind.schema);o.addProperty("kind",kind.id);o.addProperty("name",name);o.addProperty("translation_key","");return o;
    }
    private static String string(JsonObject o,String key){
        var v=o.get(key);if(v==null||!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isString())throw new IllegalArgumentException("Missing string: "+key);
        return v.getAsString();
    }
    private static JsonArray bounded(JsonObject o,String key,int max){var a=array(o,key);if(a.size()>max)throw new IllegalArgumentException("Invalid list: "+key);return a;}
}
