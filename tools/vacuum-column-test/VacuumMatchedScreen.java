package com.wormzjl.icheme.science.column.v3;

import com.google.gson.*;
import com.wormzjl.icheme.science.material.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Revision-neutral raw production acceptance comparison on identical frozen requests. */
public final class VacuumMatchedScreen {
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    private VacuumMatchedScreen() {}
    private record Case(int index,String distribution,V3ColumnInput input) {}
    public static void main(String[] args) throws Exception {
        Path out=Path.of(args[0]), inputs=Path.of(args[1]);
        int count=Integer.parseInt(args[2]),workers=Integer.parseInt(args[3]),seconds=Integer.parseInt(args[4]);
        if(count<1||workers<1||workers>10||seconds<1)throw new IllegalArgumentException("Invalid limits");
        Files.createDirectories(out);
        var source=Files.readAllLines(inputs).subList(0,count);
        var cases=source.stream().map(line -> {
            var row=JsonParser.parseString(line).getAsJsonObject();
            return new Case(row.get("index").getAsInt(),row.get("distribution").getAsString(),input(row.getAsJsonObject("input")));
        }).toList();
        Files.write(out.resolve("inputs.jsonl"),source,StandardOpenOption.CREATE_NEW);
        Files.writeString(out.resolve("manifest.json"),JSON.toJson(Map.of("count",count,"workers",workers,
                "secondsPerCase",seconds,"strategy","AGGRESSIVE cold","acceptanceBasis","version-own production audit")),StandardOpenOption.CREATE_NEW);
        var catalog=MaterialCatalog.bundled();MaterialRuntime.publish(catalog);
        var executor=new ThreadPoolExecutor(workers,workers,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(workers),
                Thread.ofPlatform().name("matched-column-",0).daemon(false).factory(),new ThreadPoolExecutor.AbortPolicy());
        var completion=new ExecutorCompletionService<Map<String,Object>>(executor,new ArrayBlockingQueue<>(workers));
        Set<Future<Map<String,Object>>> outstanding=new HashSet<>();
        var counts=new TreeMap<String,Integer>();var groups=new TreeMap<String,Map<String,Integer>>();
        int submitted=0,done=0;
        try(var writer=Files.newBufferedWriter(out.resolve("cases.jsonl"),StandardOpenOption.CREATE_NEW)) {
            while(submitted<Math.min(count,workers)) {
                var c=cases.get(submitted++);
                outstanding.add(completion.submit(() -> MaterialRuntime.with(catalog,c.input().packageId(),() -> run(c,seconds))));
            }
            while(done<count) {
                var future=completion.take();outstanding.remove(future);var row=future.get();
                writer.write(JSON.toJson(row));writer.newLine();writer.flush();
                String status=(String)row.get("classification"),group=(String)row.get("distribution");
                counts.merge(status,1,Integer::sum);groups.computeIfAbsent(group,k->new TreeMap<>()).merge(status,1,Integer::sum);done++;
                if(done%25==0||done==count)System.out.println(JSON.toJson(Map.of("completed",done,"counts",counts,"groups",groups)));
                if(submitted<count) {
                    var c=cases.get(submitted++);
                    outstanding.add(completion.submit(() -> MaterialRuntime.with(catalog,c.input().packageId(),() -> run(c,seconds))));
                }
            }
        } finally {
            for(var future:outstanding)future.cancel(true);executor.shutdownNow();
            if(!executor.awaitTermination(30,TimeUnit.SECONDS))throw new IllegalStateException("Matched workers did not stop");
        }
        Files.writeString(out.resolve("summary.json"),new GsonBuilder().setPrettyPrinting().create().toJson(
                Map.of("completed",done,"counts",counts,"groups",groups,"acceptanceBasis","version-own production audit")));
    }
    private static Map<String,Object> run(Case c,int seconds) {
        var row=new LinkedHashMap<String,Object>();row.put("index",c.index());row.put("distribution",c.distribution());row.put("input",c.input());
        long start=System.nanoTime(),deadline=start+TimeUnit.SECONDS.toNanos(seconds);
        try {
            var outcome=V3ColumnCalculator.calculateWithStrategy(c.input(),() -> {
                if(Thread.currentThread().isInterrupted()||System.nanoTime()>deadline)throw new CancellationException("cooperative deadline");
            },V3SolveStrategy.AGGRESSIVE,null,V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO);
            row.put("diagnostics",outcome.diagnostics());
            if(outcome instanceof V3ColumnOutcome.Success success) {
                row.put("classification","ACCEPTED");row.put("streams",success.result().streams());
                row.put("inspection",success.result().inspection().orElseThrow());row.put("duties",success.result().dutyLedger().orElseThrow());
                row.put("hydraulics",success.result().trayHydraulics().orElse(null));
            } else {var failure=(V3ColumnOutcome.Failure)outcome;row.put("classification",failure.code().name());row.put("summary",failure.summary());}
        } catch(CancellationException timeout) {row.put("classification","TIME_BUDGET");}
        catch(RuntimeException failure) {row.put("classification","HARNESS_EXCEPTION");row.put("summary",failure.toString());}
        row.put("elapsedMillis",(System.nanoTime()-start)/1e6);return row;
    }
    private static V3ColumnInput input(JsonObject p) {
        var ids=new ArrayList<String>();for(var id:p.getAsJsonObject("componentBasis").getAsJsonArray("componentIds"))ids.add(id.getAsString());
        var specs=new ArrayList<V3ColumnSpecification>();
        for(var element:p.getAsJsonArray("specifications")) {
            var s=element.getAsJsonObject();
            if(s.has("kelvin"))specs.add(new V3ColumnSpecification.CondenserOutletTemperature(s.get("kelvin").getAsDouble()));
            else if(s.has("ratio"))specs.add(new V3ColumnSpecification.OrganicRefluxRatio(s.get("ratio").getAsDouble()));
            else if(s.has("watts"))specs.add(new V3ColumnSpecification.ReboilerDuty(s.get("watts").getAsDouble()));
            else throw new IllegalArgumentException("Unknown frozen specification");
        }
        return new V3ColumnInput(p.get("schemaVersion").getAsInt(),p.get("packageId").getAsString(),p.get("assayId").getAsString(),
                new V3ComponentBasis(ids),JSON.fromJson(p.get("feedComponentMolarFlowsMolPerSecond"),double[].class),
                p.get("feedTemperatureKelvin").getAsDouble(),p.get("stageCount").getAsInt(),p.get("feedStageNumber").getAsInt(),
                p.get("topPressurePascal").getAsDouble(),p.get("stagePressureDropPascal").getAsDouble(),specs,
                Arrays.asList(JSON.fromJson(p.get("sideDraws"),V3SideDrawSpec[].class)),
                Arrays.asList(JSON.fromJson(p.get("steamFeeds"),V3SteamFeedSpec[].class)),
                Arrays.asList(JSON.fromJson(p.get("pumparounds"),V3PumparoundSpec[].class)),p.get("columnDiameterMetres").getAsDouble());
    }
}
