import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wormzjl.icheme.science.column.v3.V3ColumnCalculator;
import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.column.v3.V3ColumnOutcome;
import com.wormzjl.icheme.science.column.v3.V3ColumnSpecification;
import com.wormzjl.icheme.science.column.v3.V3ColumnTemplate;
import com.wormzjl.icheme.science.column.v3.V3PumparoundSpec;
import com.wormzjl.icheme.science.column.v3.V3SideDrawSpec;
import com.wormzjl.icheme.science.column.v3.V3SolveControl;
import com.wormzjl.icheme.science.column.v3.V3SteamFeedSpec;
import com.wormzjl.icheme.science.material.MaterialCatalog;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * The column calculator on a bundled stream and tower pair (P4, owner's D4 option A): the input as {@code V3TestInputs.crude}
 * builds it (the path {@code V3ClientPresetsTest} proves equal to the calculator's Templates), solved once by the classical
 * request {@code V3TestInputs.calculate} makes, and each product's share of the feed by hydrocarbon mass. -Dpair names both files
 * (default small_crude); -DfeedK overrides the stream's temperature, K, comma separated for several solves.
 */
public final class PairCheck {
    public static void main(String[] args) {
        var catalog = MaterialCatalog.bundled();
        String name = System.getProperty("pair", "small_crude");
        var stream = bundled("assets/icheme/mixturepreset/" + name + ".json");
        String temperatures = System.getProperty("feedK", "");
        for (var t : (temperatures.isBlank() ? stream.get("temperature_kelvin").getAsString() : temperatures).split(",")) {
            var input = crude(catalog, name, stream, Double.parseDouble(t));
            var outcome = V3ColumnCalculator.calculate(input, V3SolveControl.UNBOUNDED, 0.0, 0.0, V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO);
            System.out.printf(Locale.ROOT, "%s at %.2f K (%.1f C): %s%n", name, input.feedTemperatureKelvin(), input.feedTemperatureKelvin() - 273.15, outcome.getClass().getSimpleName());
            if (!(outcome instanceof V3ColumnOutcome.Success success)) {
                System.out.println("  " + outcome);
                continue;
            }
            var properties = catalog.requirePackage(input.packageId());
            var ids = input.componentBasis().componentIds();
            double feed = 0;
            double[] f = input.feedComponentMolarFlowsMolPerSecond();
            for (int i = 0; i < f.length; i++) feed += f[i] * weight(properties, ids.get(i));
            for (var product : success.result().streams()) {
                double mass = 0, water = 0;
                for (var x : product.moleFractions()) {
                    double m = product.molarFlowMolPerSecond() * x.moleFraction() * weight(properties, x.componentId());
                    if (x.componentId().toLowerCase(Locale.ROOT).contains("water")) water += m;
                    else mass += m;
                }
                System.out.printf(Locale.ROOT, "  %-24s %8.2f kmol/h  hydrocarbon %6.1f kg/h  %5.1f %% of the feed  water %6.1f kg/h%n", product.streamId(),
                        product.molarFlowMolPerSecond() * 3.6, mass * 3600, 100 * mass / feed, water * 3600);
            }
        }
    }

    private static double weight(MaterialCatalog.Package properties, String id) {
        int index = properties.components().indexOf(id);
        return index < 0 ? 0 : properties.properties().get(index).molecularWeight();
    }

    /** {@code V3TestInputs.crude}, with the stream's temperature as given. */
    static V3ColumnInput crude(MaterialCatalog catalog, String name, JsonObject stream, double temperature) {
        var base = catalog.presets().column(catalog.presets().network().defaultColumnPreset()).input(catalog);
        double rate = stream.get("flow_kmol_h").getAsDouble();
        var tower = V3ColumnTemplate.read(bundled("assets/icheme/columnpreset/" + name + ".json")).scaledTo(rate);
        var ids = base.componentBasis().componentIds();
        double[] feed = new double[ids.size()];
        double total = 0;
        for (var row : stream.getAsJsonArray("components")) {
            double amount = row.getAsJsonObject().get("amount").getAsDouble();
            feed[ids.indexOf(row.getAsJsonObject().get("id").getAsString())] = amount;
            total += amount;
        }
        for (int i = 0; i < feed.length; i++) feed[i] *= rate / 3.6 / total;
        String assay = catalog.requirePackage(base.packageId()).assays().containsKey("icheme:" + name) ? "icheme:" + name : base.assayId();
        return new V3ColumnInput(1, base.packageId(), assay, base.componentBasis(), feed, temperature,
                tower.trays() - 2, tower.feedTray() - 1, tower.topPressure() * 1e5, base.stagePressureDropPascal(),
                List.of(new V3ColumnSpecification.CondenserOutletTemperature(tower.topTemperature()),
                        new V3ColumnSpecification.OrganicRefluxRatio(tower.refluxRatio()), new V3ColumnSpecification.ReboilerDuty(tower.reboilerDuty() * 1e6)),
                tower.draws().stream().map(d -> new V3SideDrawSpec(d.tray() - 1, d.flow() / 3.6)).toList(),
                tower.steam().stream().map(s -> new V3SteamFeedSpec(s.tray() - 1, s.flow() / 3.6, s.temperature())).toList(),
                tower.pumparounds().stream().map(p -> new V3PumparoundSpec(p.returnTray() - 1, p.drawTray() - 1, -p.cooling() * 1e6, p.split())).toList(),
                tower.diameter());
    }

    private static JsonObject bundled(String path) {
        try (var in = PairCheck.class.getClassLoader().getResourceAsStream(path)) {
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
