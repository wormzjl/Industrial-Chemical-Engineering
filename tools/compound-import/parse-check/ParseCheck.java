import com.google.gson.JsonParser;
import com.wormzjl.icheme.science.fluid.thermo.HydrocarbonModel;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.stream.Stream;

/**
 * Loads the bundled material resources plus an import output folder through MaterialCatalog.parse, builds a check
 * package (the network package extended by every imported compound, on crude_pr78 with the imported pair library
 * after tjl20) and its HydrocarbonModel, which requires a PR78 liquid root at every volume anchor.
 * Usage: java -cp <classes>;<gson.jar> ParseCheck <src/main/resources> <import out dir>
 */
public final class ParseCheck {
    public static void main(String[] args) throws Exception {
        Path resources = Path.of(args[0]), imported = Path.of(args[1]);
        var map = new LinkedHashMap<String, String>();
        for (var entry : JsonParser.parseString(Files.readString(resources.resolve("materials-index.json"))).getAsJsonArray())
            map.put(entry.getAsString(), Files.readString(resources.resolve(entry.getAsString())));
        var ids = new java.util.ArrayList<String>();
        String library = null;
        for (String kind : new String[] {"components", "properties", "interactions", "transport"}) {
            Path dir = imported.resolve(kind);
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> files = Files.list(dir)) {
                for (Path file : files.sorted().toList()) {
                    String text = Files.readString(file);
                    map.put("data/icheme/materials/" + kind + "/" + file.getFileName(), text);
                    var o = JsonParser.parseString(text).getAsJsonObject();
                    if (kind.equals("components")) ids.add('"' + o.get("id").getAsString() + '"');
                    if (kind.equals("interactions")) library = o.get("id").getAsString();
                }
            }
        }
        String method = Files.readString(resources.resolve("data/icheme/materials/methods/crude_pr78.json"))
                .replace("\"icheme:crude_pr78\"", "\"icheme:import_check\"")
                .replace("\"icheme:tjl20\"", "\"icheme:tjl20\"" + (library == null ? "" : ", \"" + library + "\""));
        map.put("data/icheme/materials/methods/import_check.json", method);
        map.put("data/icheme/materials/packages/import_check.json", "{\"schema_version\":1,\"id\":\"icheme:import_check\",\"revision\":\"r1\","
                + "\"method\":\"icheme:import_check\",\"extends\":\"icheme:tjl20_methane_nitrogen\",\"components\":[" + String.join(",", ids) + "]}");
        var catalog = MaterialCatalog.parse(map);
        var p = catalog.requirePackage("icheme:import_check");
        int nonzero = 0;
        for (var row : p.interactions()) for (double k : row) if (k != 0) nonzero++;
        if (library != null) for (var v : JsonParser.parseString(map.get("data/icheme/materials/interactions/" + library.substring(library.indexOf(58) + 1) + ".json")).getAsJsonObject().getAsJsonArray("pairs")) {
            var pair = v.getAsJsonObject(); int i = p.components().indexOf(pair.get("first").getAsString()), j = p.components().indexOf(pair.get("second").getAsString());
            if (i < 0 || j < 0) { System.out.println("outside the check package (skipped by the catalog): " + pair.get("first").getAsString() + "/" + pair.get("second").getAsString()); continue; }
            if (p.interactions().get(i).get(j) != pair.get("kij").getAsDouble())
                throw new IllegalStateException("pair not resolved as written: " + pair);
        }
        System.out.println("parsed: " + p.components().size() + " components, " + nonzero / 2 + " non-zero k_ij pairs");
        System.out.println("column range " + p.minimumTemperature() + ".." + p.maximumTemperature() + " K");
        System.out.println("fluid domain " + catalog.fluidValidity("icheme:import_check").orElseThrow());
        System.out.println("network package fluid domain " + catalog.fluidValidity("icheme:tjl20_methane_nitrogen").orElseThrow());
        System.out.println("scientificRevision " + p.scientificRevision());
        for (var c : p.components()) {
            for (var phase : com.wormzjl.icheme.science.material.ViscosityCorrelation.Phase.values())
                catalog.viscosity("icheme:import_check", c, phase).ifPresent(v -> v.dynamicViscosityPascalSeconds(300, phase));
        }
        new HydrocarbonModel(catalog, "icheme:import_check");
        System.out.println("HydrocarbonModel built: every volume anchor has a PR78 liquid root");
    }
}
