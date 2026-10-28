package com.wormzjl.icheme.science.fluid.thermo;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.icheme.science.thermo.phase.SteamVirial;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class FluidPropertyCoverageTest {
    @Test void gameplayBasisWithNitrogenHasFinitePropertiesAtGridAndInteriorSamples() throws Exception {
        var catalog = MaterialCatalog.bundled();
        var rows = new ArrayList<Map<String,Object>>();
        var failures = new ArrayList<String>();
        // One gameplay model on the registered network package - the crude packages share its
        // twenty-component basis exactly and differ only in their assay, which is what production
        // does: {@code FluidPresetCatalog} computes each crude composition on its own package and
        // pads it onto this one basis.
        var model = FluidThermodynamics.forNetwork(catalog, FluidMaterialCatalog.NETWORK_PACKAGE);
        for (String packageId : List.of("icheme:tjl20_methane", "icheme:wti_light_export_tjl20", "icheme:cold_lake_blend_tjl20", "icheme:upper_zakum_tjl20", "icheme:bonga_tjl20", "icheme:dalia_tjl20")) {
            var propertyPackage = catalog.requirePackage(packageId);
            var n = Arrays.copyOf(V3PengRobinsonThermo.fromRegisteredPackage(packageId)
                    .crudeFeed(propertyPackage.assays().keySet().iterator().next()).moleFractions(), model.componentCount());
            n[model.components().indexOf("Nitrogen")] = .1; n[model.componentCount()-1] = .2;
            double[] temperatures = {293.15, 298.15, 300, 325, 350, 375};
            double[] pressures = {50000, 101325, 500000, 1e6, 2e6};
            for (boolean interior : new boolean[]{false, true}) {
                int temperatureCount = temperatures.length-(interior?1:0), pressureCount=pressures.length-(interior?1:0);
                for (int ti = 0; ti < temperatureCount; ti++) for (int pi = 0; pi < pressureCount; pi++) {
                    double t = interior ? (temperatures[ti] + temperatures[ti + 1]) / 2 : temperatures[ti];
                    double p = interior ? Math.sqrt(pressures[pi] * pressures[pi + 1]) : pressures[pi];
                    var row = new LinkedHashMap<String,Object>(); rows.add(row);
                    row.put("package", packageId); row.put("temperatureKelvin", t);
                    row.put("pressurePa", p); row.put("interiorSample", interior);
                    try {
                        var state = model.flashTP(t, p, n, () -> {});
                        double[] reconstructed = Arrays.copyOf(state.liquid(), model.componentCount());
                        for (int c = 0; c < model.hydrocarbon.componentCount(); c++) reconstructed[c] += state.vapor()[c];
                        reconstructed[model.componentCount()-1] = state.waterLiquid() + state.waterVapor();
                        assertArrayEquals(n, reconstructed, 1e-8);
                        assertTrue(Double.isFinite(state.internalEnergy()));
                        assertTrue(state.mass() > 0 && Double.isFinite(state.mass()));
                        assertTrue(state.volume() > 0 && Double.isFinite(state.volume()));
                        assertEquals(state.volume(), state.liquidVolume() + state.waterVolume() + state.vaporVolume(), 1e-12);
                        row.put("densityKgPerM3", state.mass() / state.volume());
                        row.put("liquidVolumeFraction", state.liquidVolume() / state.volume());
                        row.put("waterVolumeFraction", state.waterVolume() / state.volume());
                        row.put("vaporVolumeFraction", state.vaporVolume() / state.volume());
                        if (state.liquidVolume() > 0) {
                            var viscosity = model.viscosity.liquid(t, state.liquid());
                            row.put("liquidViscosityPaS", viscosity.pascalSeconds());
                            row.put("conditionalSoluteViscosity", viscosity.conditionalSoluteApproximation());
                        }
                        if (state.waterVolume() > 0) row.put("waterViscosityPaS", model.viscosity.waterLiquid(t));
                        if (state.vaporVolume() > 0) row.put("vaporViscosityPaS", model.viscosity.vapor(t, state.vapor(), state.waterVapor()));
                        row.put("status", "PASS");
                    } catch (IllegalArgumentException failure) {
                        row.put("status", "FAIL"); row.put("reason", failure.getMessage());
                        failures.add(packageId + " T=" + t + " P=" + p + ": " + failure.getMessage());
                    }
                }
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }
    /**
     * The NIST (IAPWS-95) steam points, all at or below 2 MPa: every one lies inside the second-virial steam's qualified
     * partial pressure, where its volume {@code R T/p + B} matches NIST within 0.5 % (the ideal gas's errors there reach
     * 2.0 %); the dilute viscosity within 5 %. The states past the qualified pressure are judged against the full IF97
     * Region 2 ({@code SteamVirialTest}): these points no longer reach them.
     */
    @Test void steamVolumeAndViscosityMatchIndependentReferencesInsideTheQualifiedPressure() throws Exception {
        try(var in=getClass().getResourceAsStream("/fluid/reference/water-vapor-checks.json")) {
            var rows=JsonParser.parseReader(new InputStreamReader(Objects.requireNonNull(in),StandardCharsets.UTF_8)).getAsJsonArray();
            int accepted=0;
            for(var value:rows) {
                var row=value.getAsJsonObject();double t=row.get("temperatureKelvin").getAsDouble(),p=row.get("partialPressurePascal").getAsDouble();
                double virial=SteamVirial.molarVolume(t,p),reference=row.get("referenceVolume").getAsDouble();
                assertTrue(p<=FluidThermodynamics.waterVaporPressureLimit(t),row.toString());
                assertTrue(Math.abs(virial/reference-1)<=.005,row+": "+(virial/reference-1));accepted++;
                double mu=WaterRegion1.diluteVaporViscosity(t);assertEquals(row.get("referenceViscosity").getAsDouble(),mu,.05*mu);
            }
            assertEquals(13,accepted);
        }
    }
    /** The NIST compressibilities at the same points: the virial's {@code 1/(p Z)} within 0.5 %. */
    @Test void steamCompressionMatchesIndependentPressureDifferencesWithinTheSampledEnvelope() throws Exception {
        try(var in=getClass().getResourceAsStream("/fluid/reference/water-vapor-compression.json")) {
            var rows=JsonParser.parseReader(new InputStreamReader(Objects.requireNonNull(in),StandardCharsets.UTF_8)).getAsJsonArray();
            int accepted=0;
            for(var value:rows) {
                var row=value.getAsJsonObject();double t=row.get("temperatureKelvin").getAsDouble(),p=row.get("partialPressurePascal").getAsDouble();
                double virial=FluidThermodynamics.R*t/(p*p*SteamVirial.molarVolume(t,p));
                assertTrue(p<=FluidThermodynamics.waterVaporPressureLimit(t),row.toString());
                assertTrue(Math.abs(virial/row.get("referenceCompressibility").getAsDouble()-1)<=.005,row+": "+(virial/row.get("referenceCompressibility").getAsDouble()-1));accepted++;
            }
            assertEquals(13,accepted);
        }
    }
}
