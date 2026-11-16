package com.wormzjl.icheme.science.thermo.phase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.icheme.science.column.v3.thermo.V3FeedPhase;
import com.wormzjl.icheme.science.column.v3.thermo.V3FlashResult;
import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.icheme.science.column.v3.thermo.V3ThermoException;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The column's feed flash against the equilibrium engine on the same PR78 kernel and pairs (batch
 * 2026-11-15-supercritical-fluids, D1, COLUMN_FEED_REVIEW.md). Every feed the flash answers by its one-phase rule is one
 * phase by the engine; its label is the network's slot rule on the engine's own phase traits (liquid where the reduced
 * density is at least 1); its residual enthalpy is the engine's less the engine's volume translation {@code P c}
 * ({@link TranslatedCubic}: the engine's {@code h^R + P c}), to rounding. The ideal-gas parts differ by the two
 * packages' fits (the column's degree-five Cp fit, the engine's {@code CoefficientIdealGas}); they are not compared.
 */
class ColumnFeedOnePhaseOracleTest {
    private static final String NETWORK = "icheme:tjl20_methane_nitrogen";
    /** Carbon dioxide on the network's package without its crystal: the column refuses a package that admits crystals. */
    private static final String CARBON_DIOXIDE = "test:methane_carbon_dioxide";

    @Test
    void oneFluidFeedsAreOnePhaseByTheEngineWithItsLabelAndResidualEnthalpy() {
        var resources = new LinkedHashMap<>(MaterialCatalog.bundled().resources());
        resources.put("data/icheme/materials/packages/test_methane_carbon_dioxide.json",
                "{\"schema_version\":1,\"id\":\"" + CARBON_DIOXIDE + "\",\"revision\":\"test-r1\",\"method\":\"icheme:crude_pr78\","
                        + "\"extends\":\"" + NETWORK + "\",\"components\":[{\"id\":\"CarbonDioxide\",\"properties\":\"icheme:chemsep_carbon_dioxide\"}]}");
        MaterialCatalog catalog = MaterialCatalog.parse(resources);
        int[] answered = {
                count(catalog, NETWORK, Map.of("Methane", 0.5, "Ethane", 0.5)),
                count(catalog, CARBON_DIOXIDE, Map.of("CarbonDioxide", 0.9, "Methane", 0.1)),
                count(catalog, NETWORK, Map.of("Methane", 0.90, "Ethane", 0.06, "Propane", 0.03, "Nitrogen", 0.01)),
                count(catalog, NETWORK, Map.of("Methane", 0.9, "crude_pc03", 0.1))};
        // The review's grid: 13, 1, 13 and 0 refusals of the frozen flash are one-phase feeds the rule now answers.
        assertEquals(List.of(13, 1, 13, 0), List.of(answered[0], answered[1], answered[2], answered[3]));
    }

    private static int count(MaterialCatalog catalog, String packageId, Map<String, Double> parts) {
        return MaterialRuntime.with(catalog, packageId, () -> {
            List<String> all = catalog.requirePackage(packageId).components();
            double[] z = new double[all.size()];
            parts.forEach((id, x) -> z[all.indexOf(id)] = x);
            List<String> basis = new ArrayList<>(parts.keySet());
            basis.sort((a, b) -> Integer.compare(all.indexOf(a), all.indexOf(b)));
            double[] zs = new double[basis.size()];
            for (int i = 0; i < zs.length; i++) zs[i] = parts.get(basis.get(i));
            V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(packageId);
            CubicPhaseEvaluator evaluator = PackageBinding.derivedEvaluator(catalog, packageId, basis);
            var identity = new ThermoIdentity("test:" + packageId, evaluator.family(), ThermoIdentity.NO_SPINE, ThermoIdentity.NO_WATER);
            var contract = new PhaseContract("test:open", identity, evaluator.components(), WaterParticipation.NONE, null,
                    Set.of(PhaseCompetition.FLUID_ONLY), Set.of(), PhaseDomain.open("test:open", evaluator.components()),
                    new EquilibriumResult.Coverage(EquilibriumResult.CoverageGrade.RESEARCH_ONLY, "test contract, open domain"));
            var engine = new FluidTpEquilibrium(contract, evaluator, FluidTpEquilibrium.Settings.DEFAULT);
            var workspace = engine.newWorkspace();
            double translation = 0.0;
            for (int i = 0; i < zs.length; i++) translation += zs[i] * evaluator.translation(i);
            int answered = 0;
            for (double t = 250.0; t <= 400.0; t += 25.0) {
                for (double pressureMegapascal : new double[] {2, 4, 6, 8, 10, 12, 15, 20, 25, 30}) {
                    double p = pressureMegapascal * 1.0e6;
                    String at = packageId + " " + parts + " at " + t + " K, " + pressureMegapascal + " MPa";
                    V3FlashResult flash;
                    try {
                        flash = thermo.flashTP(t, p, z, thermo.newWorkspace());
                    } catch (V3ThermoException refusal) {
                        continue;
                    }
                    if (!flash.detail().startsWith("one phase, no two-phase split")) continue;
                    var result = engine.tp(EquilibriumRequest.tp(t, p, basis, zs, PhaseCompetition.FLUID_ONLY), workspace);
                    assertTrue(result.converged(), at + ": " + result);
                    assertEquals(1, result.phases().size(), at + ": the engine splits a feed the column answers as one phase");
                    var phase = result.phases().get(0);
                    boolean liquid = phase.traits().physicalRootCount() > 1 ? phase.kind() == PhaseKind.LIQUID
                            : phase.traits().reducedDensity() >= 1.0;
                    assertEquals(liquid ? V3FeedPhase.LIQUID : V3FeedPhase.VAPOR, flash.phase(), at);
                    double columnResidual = flash.molarEnthalpyJoulesPerMol() - thermo.idealGasMolarEnthalpy(t, z);
                    double engineResidual = phase.state().residualEnthalpy() - p * translation;
                    assertEquals(engineResidual, columnResidual, 1.0e-6 * Math.max(1.0, Math.abs(engineResidual)), at);
                    assertFalse(Double.isNaN(columnResidual));
                    System.out.printf(java.util.Locale.ROOT, "ONE-PHASE %s | %s | %d it | H %.2f J/mol | h^R column %.2f, engine-Pc %.2f | engine %s %s | %s%n",
                            at, flash.phase(), flash.iterations(), flash.molarEnthalpyJoulesPerMol(), columnResidual,
                            engineResidual, result.classification(), phase.kind(), flash.detail());
                    answered++;
                }
            }
            return answered;
        });
    }
}
