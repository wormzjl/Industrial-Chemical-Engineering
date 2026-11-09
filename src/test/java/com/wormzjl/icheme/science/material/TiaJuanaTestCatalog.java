package com.wormzjl.icheme.science.material;

import com.google.gson.JsonParser;
import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The bundled material resources with Tia Juana Light added as a test-only fixture (batch 2026-11-04-open-thermo-data,
 * O3, owner decision E1: "Tia Juana is an assay"). The game ships the generated grades; the tests that pin physics on Tia
 * Juana keep it here, with the ids and values it had in {@code src/main}: the TJL20 assay {@code
 * icheme:tia_juana_light_methane} with its fluid preset and the column presets {@code tia_juana} and
 * {@code tia_juana_nitrogen}, and the literature case (the TJL19 assay {@code icheme:tia_juana_light}, the package
 * {@code icheme:tjl19_dwsim} and the hidden preset {@code literature_tia_juana}). The files are listed in
 * {@code /materials/tia-juana/index.json}; they live outside {@code data/} on the test classpath, so the bundled catalog
 * of every other test is the game's. A package's assays are derived, so every package holding Methane holds TJL20 here.
 */
public final class TiaJuanaTestCatalog {
    /** TJL20, Tia Juana Light with 0.5 mol % methane. */
    public static final String ASSAY_ID = "icheme:tia_juana_light_methane";
    /** TJL19, the methane-free literature assay. */
    public static final String LITERATURE_ASSAY_ID = "icheme:tia_juana_light";
    public static final String LITERATURE_PACKAGE_ID = "icheme:tjl19_dwsim";
    public static final String PACKAGE_ID = "icheme:tjl20_methane";
    private static final String PREFIX = "materials/tia-juana/";
    private static final Map<String, String> RESOURCES = load();
    private static final MaterialCatalog CATALOG = MaterialCatalog.parse(RESOURCES);

    private TiaJuanaTestCatalog() {}

    /** The bundled catalog with the Tia Juana fixture. */
    public static MaterialCatalog catalog() { return CATALOG; }

    /** A copy of the resource set, for tests that edit one record and parse again. */
    public static Map<String, String> resources() { return new LinkedHashMap<>(RESOURCES); }

    /** Runs {@code action} in this catalog, on the TJL20 package, without publishing it. */
    public static <T> T with(Supplier<T> action) { return MaterialRuntime.with(CATALOG, PACKAGE_ID, action); }

    /**
     * The registered thermodynamics of {@code packageId} in this catalog: its {@code crudeFeed} knows Tia Juana. The column
     * refuses a package that admits crystals (the world's package since O11 of batch 2026-11-04-open-thermo-data, CO2-I), so
     * there it is the view without the crystals' compounds, which an assay does not hold. That view's axis is the package's
     * less its tail (the crystals' compounds are the package's last), so a feed on it padded with zeros is the package's.
     */
    public static V3PengRobinsonThermo thermo(String packageId) {
        var pkg = CATALOG.requirePackage(packageId);
        if (pkg.crystals().isEmpty()) return MaterialRuntime.with(CATALOG, packageId, () -> V3PengRobinsonThermo.fromRegisteredPackage(packageId));
        var crystalSpecies = pkg.crystals().stream().map(id -> CATALOG.crystals().get(id).component()).collect(java.util.stream.Collectors.toSet());
        var kept = pkg.components().stream().filter(c -> !crystalSpecies.contains(c)).toList();
        if (!pkg.components().subList(0, kept.size()).equals(kept))
            throw new IllegalStateException(packageId + ": the crystals' compounds " + crystalSpecies + " are not the package's last");
        var view = CATALOG.restrict(packageId, kept);
        return MaterialRuntime.with(view, packageId, () -> V3PengRobinsonThermo.fromRegisteredPackage(packageId));
    }

    /** Tia Juana Light (TJL20) as mole fractions on {@code packageId}'s axis, through the column thermodynamics. */
    public static double[] feed(String packageId) {
        return java.util.Arrays.copyOf(thermo(packageId).crudeFeed(ASSAY_ID).moleFractions(), CATALOG.requirePackage(packageId).components().size());
    }

    private static Map<String, String> load() {
        var resources = new LinkedHashMap<>(MaterialCatalog.bundled().resources());
        for (var entry : JsonParser.parseString(read(PREFIX + "index.json")).getAsJsonArray()) {
            String path = entry.getAsString();
            if (resources.put(path, read(PREFIX + path)) != null) throw new IllegalArgumentException("Duplicate Tia Juana resource " + path);
        }
        return Map.copyOf(resources);
    }

    private static String read(String path) {
        try (var stream = TiaJuanaTestCatalog.class.getClassLoader().getResourceAsStream(path)) {
            if (stream == null) throw new IllegalArgumentException("Missing Tia Juana resource " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(path, e);
        }
    }
}
