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
     * refuses a package that admits crystals (the world's package since O11 of batch 2026-11-04-open-thermo-data, CO2-I), and a
     * crude carries no water-miscible solvent, so on such a package it is the view of the compounds that are neither a
     * crystal's compound nor a solvent that names a homomorph (ethanol since W6 of batch 2026-11-11-aqueous-solvents): by
     * rule and by id, in the package's order, the list a crude feed holds. A solvent at zero amount would put the
     * kernel's activity branch on every pair with it, on a list no world runs.
     */
    public static V3PengRobinsonThermo thermo(String packageId) {
        var pkg = CATALOG.requirePackage(packageId);
        var crystalSpecies = pkg.crystals().stream().map(id -> CATALOG.crystals().get(id).component()).collect(java.util.stream.Collectors.toSet());
        var kept = pkg.components().stream().filter(c -> !crystalSpecies.contains(c) && CATALOG.homomorph(packageId, c).isEmpty()).toList();
        if (kept.equals(pkg.components())) return MaterialRuntime.with(CATALOG, packageId, () -> V3PengRobinsonThermo.fromRegisteredPackage(packageId));
        var view = CATALOG.restrict(packageId, kept);
        return MaterialRuntime.with(view, packageId, () -> V3PengRobinsonThermo.fromRegisteredPackage(packageId));
    }

    /** Tia Juana Light (TJL20) as mole fractions on {@code axis}, by component id; a compound of the axis it lacks takes 0. */
    public static double[] crude(String packageId, java.util.List<String> axis) {
        var thermo = thermo(packageId);
        double[] x = thermo.crudeFeed(ASSAY_ID).moleFractions(), out = new double[axis.size()];
        var ids = thermo.componentBasis().componentIds();
        for (int i = 0; i < x.length; i++) {
            int at = axis.indexOf(ids.get(i));
            if (at < 0) throw new IllegalArgumentException(ids.get(i) + " is not on the axis " + axis);
            out[at] = x[i];
        }
        return out;
    }

    /** Tia Juana Light (TJL20) on the network package's conserved list (the world list, water last), by component id. */
    public static double[] worldCrude() {
        String network = CATALOG.presets().network().packageId();
        return crude(network, CATALOG.conservedComponents(network));
    }

    /** Tia Juana Light (TJL20) as mole fractions on {@code packageId}'s axis, by component id. */
    public static double[] feed(String packageId) {
        return crude(packageId, CATALOG.requirePackage(packageId).components());
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
