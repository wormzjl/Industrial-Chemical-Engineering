import com.wormzjl.icheme.science.column.v3.*;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.flowsheet.*;
import com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.google.gson.JsonParser;
import java.nio.file.*;
import java.util.*;

/** W6 (batch 2026-11-11-aqueous-solvents): writes the ethanol_rectifier example (a wash, a preheater, E1's rectifier) on the bundled catalog, after running it. */
public class WriteRectifier {
    public static void main(String[] a) throws Exception {
        var catalog = MaterialCatalog.bundled();
        String network = catalog.presets().network().packageId();
        var axis = FluidThermodynamics.forNetwork(catalog, network).components();
        double atm = 101_325;
        var design = new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, network, "icheme:none", new V3ComponentBasis(List.of("Ethanol", "Water")), new double[]{10, 90}, 360,
                10, 8, atm, 0, List.of(new V3ColumnSpecification.CondenserOutletTemperature(343.15), new V3ColumnSpecification.OrganicRefluxRatio(3),
                new V3ColumnSpecification.ReboilerDuty(1.77e6)), List.of(), List.of(), List.of(), V3ColumnInput.PRESCRIBED_DROP_DIAMETER);
        var heater = new Params(JsonParser.parseString("{\"target\":\"outlet\",\"side\":\"tube\",\"temperature\":359.84,\"duty\":3000000,\"area\":100,\"tube_drop\":50000,\"shell_drop\":30000}").getAsJsonObject());
        var units = List.of(new Flowsheet.Unit("E-1", "exchanger", "E-1", 90, 150, heater),
                new Flowsheet.Unit("T-1", "column", "T-1", 260, 40, ColumnDoor.from(design, ColumnComponents.Condenser.EXCHANGER)));
        var wash = new LinkedHashMap<String, Double>(); wash.put("Ethanol", 10.); wash.put("Water", 90.);
        var streams = List.of(new Flowsheet.Stream("WASH", "WASH", null, new Flowsheet.End("E-1", "tube_a"), new Flowsheet.Feed(298.15, 200_000, wash, List.of())),
                new Flowsheet.Stream("STEAM", "STEAM", null, new Flowsheet.End("E-1", "shell_a"), new Flowsheet.Feed(420, 400_000, Map.of("Water", 20.), List.of())),
                new Flowsheet.Stream("CONDENSATE", "CONDENSATE", new Flowsheet.End("E-1", "shell_b"), null, null),
                new Flowsheet.Stream("S-1", "S-1", new Flowsheet.End("E-1", "tube_b"), new Flowsheet.End("T-1", "feed"), null),
                new Flowsheet.Stream("VENT", "VENT", new Flowsheet.End("T-1", "overhead"), null, null),
                new Flowsheet.Stream("SPIRIT", "SPIRIT", new Flowsheet.End("T-1", "distillate"), null, null),
                new Flowsheet.Stream("WATER", "WATER", new Flowsheet.End("T-1", "water"), null, null),
                new Flowsheet.Stream("STILLAGE", "STILLAGE", new Flowsheet.End("T-1", "bottoms"), null, null));
        var sheet = RevisionNotice.stamped(new Flowsheet(new Flowsheet.Basis(network, "", axis), units, streams, FlowsheetSolver.Settings.DEFAULT), catalog);
        var run = FlowsheetRuns.run(sheet, catalog, () -> {});
        System.out.println("status " + run.status() + " log " + run.log());
        for (var e : run.streams().entrySet()) {
            var s = e.getValue(); if (s == null) continue;
            System.out.printf(Locale.ROOT, "%s total %.4f ethanol %.4f T %.2f%n", e.getKey(), s.total(), s.total() > 0 ? s.amounts()[axis.indexOf("Ethanol")] / s.total() : 0, s.temperature());
        }
        if (a.length > 0) { Files.write(Path.of(a[0]), FlowsheetFile.write(sheet)); System.out.println("wrote " + a[0]); }
    }
}
