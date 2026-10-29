package com.wormzjl.icheme.science.column.v3;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

/** Offline experiment using production column equations and publication gates, without Minecraft. */
public final class VacuumColumnTest {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private VacuumColumnTest() {}

    public static void main(String[] args) throws IOException {
        if (args.length < 2) throw new IllegalArgumentException("Usage: output-folder cdu|vdu [settings.json]");
        Path out = Path.of(args[0]);
        Files.createDirectories(out);
        var catalog = MaterialCatalog.bundled();
        MaterialRuntime.publish(catalog);
        String preset = catalog.presets().network().defaultColumnPreset();
        V3ColumnInput cdu = catalog.presets().column(preset).input(catalog);
        if (args[1].equals("cdu")) {
            Map<String, Object> result = solve(cdu);
            result.put("sourcePreset", preset);
            write(out.resolve("cdu.json"), result);
        } else if (args[1].equals("vdu")) {
            if (args.length != 3) throw new IllegalArgumentException("VDU requires settings.json");
            JsonObject source = read(out.resolve("cdu.json"));
            if (!source.get("success").getAsBoolean()) throw new IllegalStateException("CDU did not publish a feed");
            V3ColumnStreamProperties bottoms = null;
            for (var row : source.getAsJsonArray("streams")) {
                var stream = JSON.fromJson(row, V3ColumnStreamProperties.class);
                if (stream.streamId().equals("bottoms_liquid")) bottoms = stream;
            }
            if (bottoms == null) throw new IllegalStateException("No CDU bottoms product");
            JsonObject settings = read(Path.of(args[2]));
            double[] feed = new double[cdu.componentBasis().componentCount()];
            for (var fraction : bottoms.moleFractions()) {
                int index = cdu.componentBasis().componentIds().indexOf(fraction.componentId());
                if (index < 0 && fraction.moleFraction() > 0) throw new IllegalArgumentException("Feed has non-hydrocarbon component");
                if (index >= 0) feed[index] = bottoms.molarFlowMolPerSecond() * fraction.moleFraction();
            }
            double total = Arrays.stream(feed).sum();
            int stages = settings.get("stages").getAsInt();
            double steamRate = bottoms.massFlowKgPerSecond() * settings.get("steamMassFraction").getAsDouble() / 0.01801528;
            List<V3SteamFeedSpec> steam = steamRate == 0 ? List.of() : List.of(new V3SteamFeedSpec(stages + 1, steamRate, 533.15));
            var draws = new java.util.ArrayList<V3SideDrawSpec>();
            for (var row : settings.getAsJsonArray("draws")) {
                var draw = row.getAsJsonObject();
                draws.add(new V3SideDrawSpec(draw.get("stage").getAsInt(), total * draw.get("feedMoleFraction").getAsDouble()));
            }
            var coolers = new java.util.ArrayList<V3PumparoundSpec>();
            for (var row : settings.getAsJsonArray("pumparounds")) {
                var pa = row.getAsJsonObject();
                coolers.add(new V3PumparoundSpec(pa.get("returnStage").getAsInt(), pa.get("drawStage").getAsInt(),
                        -pa.get("coolingMW").getAsDouble() * 1e6, V3PumparoundSpec.Split.UNIFORM));
            }
            V3ColumnInput input = new V3ColumnInput(1, cdu.packageId(), cdu.assayId(), cdu.componentBasis(), feed,
                    settings.get("feedTemperatureK").getAsDouble(), stages, settings.get("feedStage").getAsInt(),
                    settings.get("topPressurePa").getAsDouble(), settings.get("nominalDropPa").getAsDouble(),
                    List.of(new V3ColumnSpecification.CondenserOutletTemperature(settings.get("condenserTemperatureK").getAsDouble()),
                            new V3ColumnSpecification.OrganicRefluxRatio(settings.get("refluxRatio").getAsDouble()),
                            new V3ColumnSpecification.ReboilerDuty(0)), draws, steam, coolers, settings.get("diameterM").getAsDouble());
            var feedRecord = new LinkedHashMap<String, Object>();
            feedRecord.put("sourcePreset", preset);
            feedRecord.put("sourceInputDigest", source.get("inputDigest").getAsString());
            feedRecord.put("sourceBottoms", bottoms);
            feedRecord.put("feedComponentMolarFlowsMolPerSecond", feed);
            feedRecord.put("componentIds", cdu.componentBasis().componentIds());
            feedRecord.put("reheatedTemperatureK", input.feedTemperatureKelvin());
            var thermo = V3PengRobinsonThermo.fromRegisteredPackage(input.packageId());
            double pressure = input.topPressurePascal() + input.feedStageNumber() * input.stagePressureDropPascal();
            var composition = Arrays.stream(feed).map(flow -> flow / total).toArray();
            var flash = thermo.flashTP(input.feedTemperatureKelvin(), pressure, composition, thermo.newWorkspace());
            feedRecord.put("hydrocarbonOnlyFeedFlashAtNominalFeedPressure", flash);
            var sourceFlash = thermo.flashTP(bottoms.temperatureKelvin(), bottoms.pressurePascal(), composition, thermo.newWorkspace());
            feedRecord.put("heatingAndLetdownDutyMW", total * (flash.molarEnthalpyJoulesPerMol() - sourceFlash.molarEnthalpyJoulesPerMol()) / 1e6);
            var heater = thermo.flashTP(673.15, bottoms.pressurePascal(), composition, thermo.newWorkspace());
            feedRecord.put("heaterOutlet400CAtSourcePressure", heater);
            double low = bottoms.temperatureKelvin(), high = 673.15;
            for (int iteration = 0; iteration < 40; iteration++) {
                double middle = (low + high) / 2;
                double enthalpy = thermo.flashTP(middle, pressure, composition, thermo.newWorkspace()).molarEnthalpyJoulesPerMol();
                if (enthalpy < heater.molarEnthalpyJoulesPerMol()) low = middle; else high = middle;
            }
            double letdownTemperature = (low + high) / 2;
            feedRecord.put("adiabaticLetdownTemperatureKFrom400CHeater", letdownTemperature);
            if (settings.has("use400CHeaterEnthalpy") && settings.get("use400CHeaterEnthalpy").getAsBoolean()) {
                input = new V3ColumnInput(input.schemaVersion(), input.packageId(), input.assayId(), input.componentBasis(),
                        feed, letdownTemperature, input.stageCount(), input.feedStageNumber(), input.topPressurePascal(),
                        input.stagePressureDropPascal(), input.specifications(), input.sideDraws(), input.steamFeeds(),
                        input.pumparounds(), input.columnDiameterMetres());
                flash = thermo.flashTP(letdownTemperature, pressure, composition, thermo.newWorkspace());
                if (Math.abs(flash.molarEnthalpyJoulesPerMol() - heater.molarEnthalpyJoulesPerMol()) > 1e-5)
                    throw new IllegalStateException("Isenthalpic inlet conversion failed");
                feedRecord.put("reheatedTemperatureK", letdownTemperature);
                feedRecord.put("hydrocarbonOnlyFeedFlashAtNominalFeedPressure", flash);
                feedRecord.put("heatingAndLetdownDutyMW", total * (flash.molarEnthalpyJoulesPerMol() - sourceFlash.molarEnthalpyJoulesPerMol()) / 1e6);
            }
            System.out.printf(java.util.Locale.ROOT, "400 C heater at source pressure gives %.3f C after isenthalpic letdown%n", letdownTemperature - 273.15);
            System.out.printf(java.util.Locale.ROOT, "HC feed TP flash at %.1f Pa: vapor mole fraction %.6f%n", pressure, flash.vaporFraction());
            write(out.resolve("feed.json"), feedRecord);
            Map<String, Object> result = solve(input, settings.has("strategy") ? settings.get("strategy").getAsString() : "AGGRESSIVE");
            result.put("settings", settings);
            result.put("sourceCduInputDigest", source.get("inputDigest").getAsString());
            write(out.resolve(settings.get("name").getAsString() + ".json"), result);
        } else throw new IllegalArgumentException("Unknown phase: " + args[1]);
    }

    private static Map<String, Object> solve(V3ColumnInput input) {
        return solve(input, "AGGRESSIVE");
    }

    private static Map<String, Object> solve(V3ColumnInput input, String strategy) {
        var record = new LinkedHashMap<String, Object>();
        record.put("input", input);
        record.put("packageRevision", MaterialRuntime.current().requirePackage(input.packageId()).scientificRevision());
        record.put("route", strategy + " cold; default cutoff/closure policy; production supply screen; see certificate for final closure");
        System.out.println("Solving " + input.stageCount() + " interior stages at " + input.topPressurePascal() + " Pa, feed "
                + Arrays.stream(input.feedComponentMolarFlowsMolPerSecond()).sum() * 3.6 + " kmol/h");
        long deadline = System.nanoTime() + 180_000_000_000L;
        V3ColumnOutcome outcome;
        try {
            outcome = V3ColumnCalculator.calculateWithStrategy(input, () -> {
                if (System.nanoTime() > deadline) throw new CancellationException("Experiment safety deadline (180 s)");
            }, V3SolveStrategy.valueOf(strategy), null, V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO);
        } catch (CancellationException cancelled) {
            record.put("success", false);
            record.put("failureCode", "EXPERIMENT_DEADLINE");
            record.put("summary", cancelled.getMessage());
            System.out.println(cancelled.getMessage());
            return record;
        }
        record.put("success", outcome.isSuccess());
        record.put("diagnostics", outcome.diagnostics());
        if (outcome instanceof V3ColumnOutcome.Success success) {
            var result = success.result();
            record.put("inputDigest", result.inputDigest().toString());
            record.put("streams", result.streams());
            record.put("duties", result.dutyLedger().orElseThrow());
            record.put("inspection", result.inspection().orElseThrow());
            record.put("hydraulics", result.trayHydraulics().orElse(null));
            var dryPhaseChecks = new java.util.ArrayList<Object>();
            var thermo = V3PengRobinsonThermo.fromRegisteredPackage(input.packageId());
            for (var node : result.inspection().orElseThrow().nodes()) {
                if (node.liquidMolPerSecond() != 0 || node.vaporMolPerSecond() == 0) continue;
                double p = node.pressurePascal() * node.vaporMolPerSecond()
                        / (node.vaporMolPerSecond() + node.waterVaporMolPerSecond());
                var flash = thermo.flashTP(node.temperatureKelvin(),p,
                        node.vaporFractions().stream().mapToDouble(Double::doubleValue).toArray(),thermo.newWorkspace());
                dryPhaseChecks.add(Map.of("temperatureK", node.temperatureKelvin(), "hydrocarbonPressurePa", p,
                        "phase", flash.phase(), "beta", flash.vaporFraction()));
            }
            record.put("dryHydrocarbonPhaseChecks", dryPhaseChecks);
            for (var stream : result.streams()) System.out.printf(java.util.Locale.ROOT,
                    "%s: %.6f kmol/h, %.6f t/h, %.3f C, %.3f Pa%n", stream.streamId(),
                    stream.molarFlowMolPerSecond() * 3.6, stream.massFlowKgPerSecond() * 3.6, stream.temperatureKelvin() - 273.15, stream.pressurePascal());
        } else {
            var failure = (V3ColumnOutcome.Failure) outcome;
            record.put("failureCode", failure.code());
            record.put("summary", failure.summary());
            System.out.println(failure.code() + ": " + failure.summary());
            System.out.println(String.join("\n", failure.diagnostics().events()));
        }
        return record;
    }

    private static JsonObject read(Path path) throws IOException {
        try (var reader = Files.newBufferedReader(path)) { return JSON.fromJson(reader, JsonObject.class); }
    }

    private static void write(Path path, Object value) throws IOException {
        try (var writer = Files.newBufferedWriter(path)) { JSON.toJson(value, writer); }
    }
}
