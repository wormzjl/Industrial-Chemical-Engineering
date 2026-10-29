package com.wormzjl.icheme.science.column.v3;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.wormzjl.icheme.science.column.v3.thermo.V3FeedPhase;
import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.icheme.science.column.v3.thermo.V3WaterProperties;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.*;

/** Offline cold-start population screen. Workers own their solves; only the coordinator writes files. */
public final class VacuumBulkScreen {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private static final Gson PRETTY = new GsonBuilder().serializeNulls().setPrettyPrinting().create();
    private VacuumBulkScreen() {}
    private record Scenario(int index, String distribution, V3ColumnInput input) {}
    private record Completed(Scenario scenario, Map<String,Object> row, V3ColumnOutcome outcome) {}

    public static void main(String[] args) throws Exception {
        if (args.length != 5) throw new IllegalArgumentException("output count workers seconds seed");
        Path out = Path.of(args[0]);
        int count = Integer.parseInt(args[1]), workers = Integer.parseInt(args[2]);
        int seconds = Integer.parseInt(args[3]);
        long seed = Long.parseLong(args[4]);
        if (count < 1 || count > 10000 || workers < 1 || workers > 10 || seconds < 1 || seconds > 180)
            throw new IllegalArgumentException("Invalid screen limits");
        Files.createDirectories(out);
        if (Files.exists(out.resolve("cases.jsonl"))) throw new IllegalArgumentException("Preserve the existing screen before rerunning");
        var catalog = MaterialCatalog.bundled();
        MaterialRuntime.publish(catalog);
        String preset = catalog.presets().network().defaultColumnPreset();
        var cduInput = catalog.presets().column(preset).input(catalog);
        var cduOutcome = solve(cduInput, null, 180);
        write(out.resolve("cdu.json"), snapshot(cduInput,cduOutcome));
        var cdu = requireSuccess(cduOutcome,"fresh source CDU").result();
        var bottoms = cdu.streams().stream().filter(s -> s.streamId().equals("bottoms_liquid")).findFirst().orElseThrow();
        double[] feed = new double[cduInput.componentBasis().componentCount()];
        for (var f : bottoms.moleFractions()) {
            int c = cduInput.componentBasis().componentIds().indexOf(f.componentId());
            if (c >= 0) feed[c] = bottoms.molarFlowMolPerSecond()*f.moleFraction();
        }
        double total = Arrays.stream(feed).sum();
        double[] z = Arrays.stream(feed).map(f -> f/total).toArray();
        var thermo = V3PengRobinsonThermo.fromRegisteredPackage(cduInput.packageId());
        double targetH = thermo.flashTP(673.15,bottoms.pressurePascal(),z,thermo.newWorkspace()).molarEnthalpyJoulesPerMol();
        double nominalT = inletTemperature(thermo,z,targetH,2000);
        var base = column(cduInput,feed,nominalT,10,8,2000,15,333.15,0.01,
                new double[]{0.10,0.25},new double[]{10e6,15e6},new int[]{3,6},new int[]{2,5});
        // Full chain control: close the heater against the published feed pressure, then test exact reuse.
        var conditioning = new ArrayList<Object>();
        V3ColumnInput conditioned = base;
        V3NeuralSeed warm = null;
        boolean heaterMatched = false;
        for (int iteration=0; iteration<6; iteration++) {
            var outcome = solve(conditioned,warm,60);
            write(out.resolve("heater-step-"+iteration+".json"),snapshot(conditioned,outcome));
            if (!(outcome instanceof V3ColumnOutcome.Success success)) break;
            var p = success.result().problem();
            double pressure = p.nodePressurePascal(p.topology().feedTrayNumber());
            double actualH = thermo.flashTP(conditioned.feedTemperatureKelvin(),pressure,z,thermo.newWorkspace()).molarEnthalpyJoulesPerMol();
            double errorWatts = total*(actualH-targetH);
            conditioning.add(Map.of("iteration",iteration,"inletK",conditioned.feedTemperatureKelvin(),
                    "feedPressurePa",pressure,"heaterMismatchWatts",errorWatts));
            warm = success.result().warmSeed().orElseThrow();
            if (Math.abs(errorWatts)<=100) { heaterMatched=true; break; }
            conditioned = withTemperature(conditioned,inletTemperature(thermo,z,targetH,pressure));
        }
        write(out.resolve("heater-conditioning.json"),Map.of("matchedWithin100Watts",heaterMatched,"iterations",conditioning,
                "targetFeedEnthalpyJPerMol",targetH,"sourceBottoms",bottoms));
        if (heaterMatched) {
            write(out.resolve("full-vdu-cold.json"),snapshot(conditioned,solve(conditioned,null,60)));
            write(out.resolve("full-vdu-warm.json"),snapshot(conditioned,solve(conditioned,warm,60)));
        }
        // The population is defined around the original independently accepted fixed-temperature design.
        // Every population case is cold, with no conditioning state or previous case available as a seed.
        var scenarios = new ArrayList<Scenario>(count);
        for (int i=0;i<count;i++) scenarios.add(scenario(i,seed,base));
        write(out.resolve("manifest.json"),Map.of("count",count,"workers",workers,"secondsPerCase",seconds,
                "seed",seed,"strategy","AGGRESSIVE cold","packageRevision",thermo.datasetRevision(),
                "baselineInput",base,"heaterMatched",heaterMatched,
                "postcheckCriteria", "component/water error <= 1e-6*max(1,HC feed); energy <= 1e-5*largest boundary term; dry HC beta >= 1-1e-6"));
        try (var inputs=Files.newBufferedWriter(out.resolve("inputs.jsonl"),StandardOpenOption.CREATE_NEW)) {
            for (var s:scenarios) { inputs.write(JSON.toJson(s)); inputs.newLine(); }
        }
        var factory = Thread.ofPlatform().name("vacuum-screen-",0).daemon(false).factory();
        var executor = new ThreadPoolExecutor(workers,workers,0,TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(workers),factory,new ThreadPoolExecutor.AbortPolicy());
        var completion = new ExecutorCompletionService<Completed>(executor,new ArrayBlockingQueue<>(workers));
        Set<Future<Completed>> outstanding = new HashSet<>();
        var counts = new TreeMap<String,Integer>();
        var distributions = new TreeMap<String,Map<String,Integer>>();
        Set<String> savedSamples = new HashSet<>();
        int submitted=0, completed=0;
        try (var rows=Files.newBufferedWriter(out.resolve("cases.jsonl"),StandardOpenOption.CREATE_NEW)) {
            while (submitted<Math.min(count,workers)) {
                var s=scenarios.get(submitted++);
                outstanding.add(completion.submit(() -> MaterialRuntime.with(catalog,s.input().packageId(),() -> run(s,seconds))));
            }
            while (completed<count) {
                var future=completion.take();
                outstanding.remove(future);
                Completed c=future.get();
                rows.write(JSON.toJson(c.row())); rows.newLine(); rows.flush();
                String status=(String)c.row().get("classification");
                counts.merge(status,1,Integer::sum);
                distributions.computeIfAbsent(c.scenario().distribution(),k -> new TreeMap<>()).merge(status,1,Integer::sum);
                String sample=c.scenario().distribution()+"-"+status;
                if (savedSamples.add(sample)) write(out.resolve("sample-"+sample+".json"),snapshot(c.scenario().input(),c.outcome()));
                completed++;
                if (completed%25==0 || completed==count) {
                    var progress=Map.of("completed",completed,"total",count,"counts",counts,"distributions",distributions);
                    write(out.resolve("progress.json"),progress);
                    System.out.println(JSON.toJson(progress));
                }
                if (submitted<count) {
                    var s=scenarios.get(submitted++);
                    outstanding.add(completion.submit(() -> MaterialRuntime.with(catalog,s.input().packageId(),() -> run(s,seconds))));
                }
            }
        } finally {
            for (var future:outstanding) future.cancel(true);
            executor.shutdownNow();
            if (!executor.awaitTermination(30,TimeUnit.SECONDS)) throw new IllegalStateException("Screen workers did not stop cooperatively");
        }
        write(out.resolve("summary.json"),Map.of("completed",completed,"total",count,"counts",counts,"distributions",distributions,
                "secondsPerCase",seconds,"workers",workers,"heaterMatched",heaterMatched));
    }

    private static Completed run(Scenario s,int seconds) {
        var row=new LinkedHashMap<String,Object>();
        row.put("index",s.index()); row.put("distribution",s.distribution()); row.put("input",s.input());
        V3ColumnOutcome outcome;
        long start=System.nanoTime();
        try {
            outcome=solve(s.input(),null,seconds);
            row.put("diagnostics",outcome.diagnostics());
            if (outcome instanceof V3ColumnOutcome.Success success) {
                var metrics=check(success.result()); row.put("checks",metrics);
                row.put("classification",Boolean.TRUE.equals(metrics.get("passed"))?"ACCEPTED":"POSTCHECK");
                row.put("streams",success.result().streams());
                row.put("hydraulics",success.result().trayHydraulics().orElse(null));
            } else {
                var failure=(V3ColumnOutcome.Failure)outcome;
                row.put("classification",failure.code().name()); row.put("summary",failure.summary());
            }
        } catch (CancellationException timeout) {
            outcome=null; row.put("classification","TIME_BUDGET"); row.put("summary",timeout.getMessage());
        } catch (RuntimeException failure) {
            outcome=null; row.put("classification","HARNESS_EXCEPTION"); row.put("summary",failure.toString());
        }
        row.put("elapsedMillis",(System.nanoTime()-start)/1e6);
        return new Completed(s,row,outcome);
    }

    private static Map<String,Object> check(V3ColumnResult result) {
        var problem=result.problem(); var input=problem.input();
        var thermo=V3PengRobinsonThermo.fromRegisteredPackage(input.packageId());
        double[] feed=input.feedComponentMolarFlowsMolPerSecond(), products=new double[feed.length];
        double waterOut=0, productEnergy=0;
        for (var stream:result.streams()) {
            double[] hc=new double[feed.length]; double water=0;
            for (var f:stream.moleFractions()) {
                double flow=stream.molarFlowMolPerSecond()*f.moleFraction();
                int c=input.componentBasis().componentIds().indexOf(f.componentId());
                if (c>=0) { hc[c]=flow; products[c]+=flow; }
                else if (f.componentId().equals("Water")) water+=flow;
                else throw new IllegalArgumentException("Unrecognized boundary component "+f.componentId());
            }
            boolean liquid=stream.phase().equals("LIQUID");
            productEnergy+=phaseEnergy(thermo,stream.temperatureKelvin(),stream.pressurePascal(),hc,liquid);
            productEnergy+=water*(liquid?V3WaterProperties.liquidMolarEnthalpy(stream.temperatureKelvin())
                    :V3WaterProperties.vaporMolarEnthalpy(stream.temperatureKelvin()));
            waterOut+=water;
        }
        double total=Arrays.stream(feed).sum(), maxComponent=0;
        for(int c=0;c<feed.length;c++) maxComponent=Math.max(maxComponent,Math.abs(feed[c]-products[c]));
        double pressure=problem.nodePressurePascal(problem.topology().feedTrayNumber());
        double h=thermo.flashTP(input.feedTemperatureKelvin(),pressure,feed,thermo.newWorkspace()).molarEnthalpyJoulesPerMol();
        double steam=0, waterIn=0;
        for(var source:input.steamFeeds()) { waterIn+=source.molarFlowMolPerSecond(); steam+=source.molarFlowMolPerSecond()*V3WaterProperties.vaporMolarEnthalpy(source.temperatureKelvin()); }
        double stageHeat=input.pumparounds().stream().mapToDouble(V3PumparoundSpec::dutyWatts).sum();
        double reboiler=input.specifications().stream().filter(V3ColumnSpecification.ReboilerDuty.class::isInstance)
                .map(V3ColumnSpecification.ReboilerDuty.class::cast).findFirst().orElseThrow().watts();
        var nodes=result.inspection().orElseThrow().nodes();
        double condenser=nodeEnergy(thermo,nodes.get(0),true)+nodeEnergy(thermo,nodes.get(0),false)
                -nodeEnergy(thermo,nodes.get(1),false);
        double error=total*h+steam+stageHeat+reboiler+condenser-productEnergy;
        double largest=Math.max(1,Math.max(Math.abs(productEnergy),Math.max(Math.abs(total*h),Math.abs(condenser))));
        boolean dry=true; double minimumBeta=1;
        for(int n=1;n<=problem.topology().dryTopTrayCount();n++) {
            var node=nodes.get(n); dry&=node.liquidMolPerSecond()==0;
            double hp=node.pressurePascal()*node.vaporMolPerSecond()/(node.vaporMolPerSecond()+node.waterVaporMolPerSecond());
            double[] y=node.vaporFractions().stream().mapToDouble(Double::doubleValue).toArray();
            minimumBeta=Math.min(minimumBeta,thermo.flashTP(node.temperatureKelvin(),hp,y,thermo.newWorkspace()).vaporFraction());
        }
        var state=result.warmSeed().orElseThrow().stateFor(problem);
        var audit=new V3AcceptanceAuditor(problem,thermo,h,result.closureTolerance()).audit(state,thermo.newWorkspace(),V3SolveControl.UNBOUNDED);
        double componentLimit=1e-6*Math.max(1,total), energyLimit=1e-5*largest;
        return Map.of("passed",audit.accepted()&&dry&&minimumBeta>=1-1e-6&&maxComponent<=componentLimit
                        &&Math.abs(waterOut-waterIn)<=componentLimit&&Math.abs(error)<=energyLimit,
                "maxComponentErrorMolPerSecond",maxComponent,"waterErrorMolPerSecond",waterOut-waterIn,
                "energyErrorWatts",error,"energyLimitWatts",energyLimit,"dryPrefixCount",problem.topology().dryTopTrayCount(),
                "dryPrefixExactlyZeroLiquid",dry,"minimumDryGasBeta",minimumBeta,"freshAuditAccepted",audit.accepted());
    }

    private static double nodeEnergy(V3PengRobinsonThermo thermo,V3ColumnInspection.Node node,boolean liquid) {
        double flow=liquid?node.liquidMolPerSecond():node.vaporMolPerSecond();
        double[] z=(liquid?node.liquidFractions():node.vaporFractions()).stream().mapToDouble(Double::doubleValue).toArray();
        double e=flow==0?0:flow*thermo.molarEnthalpy(node.temperatureKelvin(),node.pressurePascal(),z,liquid?Root.LIQUID:Root.VAPOR,thermo.newWorkspace());
        double water=liquid?node.freeWaterMolPerSecond():node.waterVaporMolPerSecond();
        return e+water*(liquid?V3WaterProperties.liquidMolarEnthalpy(node.temperatureKelvin()):V3WaterProperties.vaporMolarEnthalpy(node.temperatureKelvin()));
    }
    private static double phaseEnergy(V3PengRobinsonThermo thermo,double t,double p,double[] flows,boolean liquid) {
        double total=Arrays.stream(flows).sum();
        return total==0?0:total*thermo.molarEnthalpy(t,p,flows,liquid?Root.LIQUID:Root.VAPOR,thermo.newWorkspace());
    }
    private static V3ColumnOutcome solve(V3ColumnInput input,V3NeuralSeed warm,int seconds) {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
        return V3ColumnCalculator.calculateWithStrategy(input,() -> {
            if(Thread.currentThread().isInterrupted()) throw new CancellationException("screen interrupted");
            if(System.nanoTime()>deadline) throw new CancellationException("per-case "+seconds+" s cooperative budget");
        },V3SolveStrategy.AGGRESSIVE,warm,V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO);
    }
    private static V3ColumnOutcome.Success requireSuccess(V3ColumnOutcome outcome,String label) {
        if(outcome instanceof V3ColumnOutcome.Success success) return success;
        throw new IllegalStateException(label+": "+outcome);
    }
    private static double inletTemperature(V3PengRobinsonThermo thermo,double[] z,double h,double pressure) {
        double low=400,high=800;
        for(int i=0;i<55;i++) {
            double middle=(low+high)/2;
            if(thermo.flashTP(middle,pressure,z,thermo.newWorkspace()).molarEnthalpyJoulesPerMol()<h) low=middle; else high=middle;
        }
        return (low+high)/2;
    }
    private static V3ColumnInput withTemperature(V3ColumnInput p,double t) {
        return new V3ColumnInput(p.schemaVersion(),p.packageId(),p.assayId(),p.componentBasis(),p.feedComponentMolarFlowsMolPerSecond(),t,
                p.stageCount(),p.feedStageNumber(),p.topPressurePascal(),p.stagePressureDropPascal(),p.specifications(),p.sideDraws(),p.steamFeeds(),p.pumparounds(),p.columnDiameterMetres());
    }
    private static V3ColumnInput column(V3ColumnInput p,double[] feed,double t,int stages,int feedStage,double pressure,
            double diameter,double condenser,double steamFraction,double[] cuts,double[] duties,int[] draws,int[] returns) {
        double total=Arrays.stream(feed).sum(),mass=0;
        var thermo=V3PengRobinsonThermo.fromRegisteredPackage(p.packageId());
        for(int c=0;c<feed.length;c++) mass+=feed[c]*thermo.componentMolecularWeightKgPerMol(c);
        var side=new ArrayList<V3SideDrawSpec>(); var cooling=new ArrayList<V3PumparoundSpec>();
        for(int i=0;i<2;i++) {
            if(cuts[i]>0) side.add(new V3SideDrawSpec(draws[i],total*cuts[i]));
            if(duties[i]>0) cooling.add(new V3PumparoundSpec(returns[i],draws[i],-duties[i],V3PumparoundSpec.Split.UNIFORM));
        }
        var steam=steamFraction==0?List.<V3SteamFeedSpec>of():List.of(new V3SteamFeedSpec(stages+1,mass*steamFraction/V3WaterProperties.molarMass(),533.15));
        return new V3ColumnInput(1,p.packageId(),p.assayId(),p.componentBasis(),feed,t,stages,feedStage,pressure,0,
                List.of(new V3ColumnSpecification.CondenserOutletTemperature(condenser),new V3ColumnSpecification.OrganicRefluxRatio(0),
                        new V3ColumnSpecification.ReboilerDuty(0)),side,steam,cooling,diameter);
    }
    private static Scenario scenario(int i,long seed,V3ColumnInput base) {
        if(i<4) {
            if(i==0) return new Scenario(i,"control",base);
            double[] cuts=i==1?new double[]{0,0}:new double[]{.1,.25};
            double[] duties=i==1?new double[]{0,0}:i==2?new double[]{1e6,2e6}:new double[]{3e6,5e6};
            return new Scenario(i,"control",column(base,base.feedComponentMolarFlowsMolPerSecond(),base.feedTemperatureKelvin(),10,8,2000,15,333.15,.01,cuts,duties,new int[]{3,6},new int[]{2,5}));
        }
        var r=new SplittableRandom(seed+i);
        String distribution=i%5<2?"near":i%5<4?"broad":"boundary";
        boolean near=distribution.equals("near");
        int[] choices=near?new int[]{6,8,10,12,14,16}:new int[]{4,6,10,14,20,30,40};
        int n=choices[r.nextInt(choices.length)];
        double scale=near?r.nextDouble(.8,1.2):r.nextDouble(.4,1.6);
        double tilt=near?r.nextDouble(-.1,.1):r.nextDouble(-.4,.4);
        double[] feed=base.feedComponentMolarFlowsMolPerSecond(); double original=Arrays.stream(feed).sum();
        for(int c=0;c<feed.length;c++) feed[c]*=Math.exp(tilt*(2.*c/(feed.length-1)-1));
        double tilted=Arrays.stream(feed).sum();
        for(int c=0;c<feed.length;c++) feed[c]*=scale*original/tilted;
        double t=near?base.feedTemperatureKelvin()+r.nextDouble(-15,15):r.nextDouble(593.15,683.15);
        double pressure=near?r.nextDouble(1500,3000):Math.exp(r.nextDouble(Math.log(1000),Math.log(10000)));
        double diameter=near?r.nextDouble(14,15):r.nextDouble(10,15);
        double condenser=near?r.nextDouble(323.15,343.15):r.nextDouble(303.15,363.15);
        double steam=near?r.nextDouble(.005,.015):r.nextDouble(.002,.03);
        double[] cuts=near?new double[]{r.nextDouble(.08,.12),r.nextDouble(.20,.30)}:new double[]{r.nextDouble(.03,.25),r.nextDouble(.10,.40)};
        double cooling=scale*(near?r.nextDouble(20e6,30e6):r.nextDouble(5e6,50e6));
        double split=r.nextDouble(.3,.5);
        if(distribution.equals("boundary")) {
            switch(i%11) {
                case 0 -> n=64;
                case 1 -> steam=0;
                case 2 -> cooling=0;
                case 3 -> cuts=new double[]{0,0};
                case 4 -> cuts=new double[]{.35,.50};
                case 5 -> diameter=6;
                case 6 -> pressure=1000;
                case 7 -> pressure=20000;
                case 8 -> cooling=scale*60e6;
                case 9 -> steam=.05;
                default -> t=573.15;
            }
        }
        int d1=Math.max(2,(int)Math.round(n*.30)),d2=Math.max(d1+1,(int)Math.round(n*.60));
        int[] draws={d1,d2},returns={Math.max(1,d1-Math.max(1,n/10)),Math.max(1,d2-Math.max(1,n/10))};
        int feedStage=Math.min(n,Math.max(d2+1,(int)Math.round(n*r.nextDouble(.7,.9))));
        return new Scenario(i,distribution,column(base,feed,t,n,feedStage,pressure,diameter,condenser,steam,cuts,
                new double[]{cooling*split,cooling*(1-split)},draws,returns));
    }
    private static Map<String,Object> snapshot(V3ColumnInput input,V3ColumnOutcome outcome) {
        var record=new LinkedHashMap<String,Object>(); record.put("input",input);
        record.put("success",outcome instanceof V3ColumnOutcome.Success);
        if(outcome==null) { record.put("failureCode","TIME_BUDGET_OR_EXCEPTION"); return record; }
        record.put("diagnostics",outcome.diagnostics());
        if(outcome instanceof V3ColumnOutcome.Success success) {
            var result=success.result(); record.put("inputDigest",result.inputDigest().toString()); record.put("streams",result.streams());
            record.put("inspection",result.inspection().orElseThrow());record.put("duties",result.dutyLedger().orElseThrow());
            record.put("hydraulics",result.trayHydraulics().orElse(null));record.put("checks",check(result));
        } else { var failure=(V3ColumnOutcome.Failure)outcome;record.put("failureCode",failure.code());record.put("summary",failure.summary()); }
        return record;
    }
    private static void write(Path path,Object value) throws IOException { Files.writeString(path,PRETTY.toJson(value)); }
}
