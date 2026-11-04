package com.wormzjl.icheme.science.material;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.function.Supplier;

/** Frozen literature fixture, parsed independently of the production material index. */
public final class Cdu17TestCatalog {
    public static final String PACKAGE_ID = "icheme:cdu17_tjl_acs2018";
    /** The fixture's Tia Juana Light assay; production's {@code icheme:tia_juana_light} (TJL19) keeps its own id. */
    public static final String ASSAY_ID = "icheme:tia_juana_light_kl1976";
    private static final String PREFIX = "/materials/cdu17-reference/";
    private static final MaterialCatalog CATALOG = load();

    private Cdu17TestCatalog() {}

    public static MaterialCatalog catalog() { return CATALOG; }

    /** Scope the fixture to the calling test without publishing a process-wide catalog. */
    public static <T> T with(Supplier<T> action) {
        return MaterialRuntime.with(CATALOG, PACKAGE_ID, action);
    }

    /** The Tia Juana Light assay on a package: the fixture's own on the fixture package, production's TJL19 assay elsewhere. */
    public static String tiaJuanaAssay(String packageId) {
        return PACKAGE_ID.equals(packageId) ? ASSAY_ID : "icheme:tia_juana_light";
    }

    /**
     * The fixture over the production resources. The fixture wins every record both hold, except that a compound record
     * shared with production (Methane, Ethane, Propane) keeps production's default dataset, so the production packages
     * resolve exactly as without the fixture; the fixture package names its own datasets.
     */
    public static MaterialCatalog withProduction() {
        var resources=new LinkedHashMap<>(MaterialRuntime.current().resources());
        for (var entry : CATALOG.resources().entrySet()) {
            String production=resources.get(entry.getKey());
            resources.put(entry.getKey(), production!=null && entry.getKey().contains("materials/components/")
                    ? withDefaultDataset(entry.getValue(), production) : entry.getValue());
        }
        return MaterialCatalog.parse(resources);
    }

    private static String withDefaultDataset(String fixture, String production) {
        var record=JsonParser.parseString(fixture).getAsJsonObject();
        var defaults=JsonParser.parseString(production).getAsJsonObject();
        record.remove("properties");
        if (defaults.has("properties")) record.add("properties", defaults.get("properties"));
        return record.toString();
    }

    public static com.wormzjl.icheme.science.column.v3.V3ColumnInput pilotInput() {
        return with(() -> {
            var thermo=com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo.fromRegisteredPackage(PACKAGE_ID);
            var crude=thermo.crudeFeed(ASSAY_ID);var feed=crude.moleFractions();
            for(int i=0;i<feed.length;i++)feed[i]*=2610.7/3.6;
            return new com.wormzjl.icheme.science.column.v3.V3ColumnInput(1,PACKAGE_ID,crude.assayId(),crude.componentBasis(),feed,638.15,29,24,150000,750,
                java.util.List.of(new com.wormzjl.icheme.science.column.v3.V3ColumnSpecification.CondenserOutletTemperature(400),
                    new com.wormzjl.icheme.science.column.v3.V3ColumnSpecification.OrganicRefluxRatio(2),
                    new com.wormzjl.icheme.science.column.v3.V3ColumnSpecification.ReboilerDuty(8e6)),
                java.util.List.of(new com.wormzjl.icheme.science.column.v3.V3SideDrawSpec(13,92.3/3.6),
                    new com.wormzjl.icheme.science.column.v3.V3SideDrawSpec(17,131.85/3.6),
                    new com.wormzjl.icheme.science.column.v3.V3SideDrawSpec(22,32.96/3.6)));
        });
    }

    private static MaterialCatalog load() {
        var resources = new LinkedHashMap<String, String>();
        for (var entry : JsonParser.parseString(read("index.json")).getAsJsonArray()) {
            String path = entry.getAsString();
            if (resources.putIfAbsent(path, read(path)) != null)
                throw new IllegalArgumentException("Duplicate CDU17 fixture resource: " + path);
        }
        return MaterialCatalog.parse(resources);
    }

    private static String read(String path) {
        try (var stream = Cdu17TestCatalog.class.getResourceAsStream(PREFIX + path)) {
            if (stream == null) throw new IllegalArgumentException("Missing CDU17 fixture: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(path, e);
        }
    }
}
