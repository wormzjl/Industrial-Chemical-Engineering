package com.wormzjl.icheme.science.column.v3.thermo;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.extension.ExtendWith(com.wormzjl.icheme.science.material.Cdu17FixtureExtension.class)
class V3PengRobinsonThermoTest {
    private static final String PACKAGE_ID = "icheme:cdu17_tjl_acs2018";
    private static final String ASSAY_ID = "icheme:tia_juana_light_kl1976";

    @Test
    void registeredSampleCrudeHasTheDeclaredSixteenHydrocarbonBasisAndDefensiveAssayVector() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(PACKAGE_ID);
        V3CrudeFeed crude = thermo.crudeFeed(ASSAY_ID);
        double[] copy = crude.moleFractions();
        copy[0] = 1.0;

        assertEquals(PACKAGE_ID, thermo.packageId());
        assertEquals(com.wormzjl.icheme.science.material.Cdu17TestCatalog.catalog().requirePackage(PACKAGE_ID).scientificRevision(), thermo.datasetRevision());
        assertEquals(16, thermo.componentBasis().componentCount());
        assertEquals("Methane", thermo.componentBasis().componentId(0));
        assertEquals("cdu17_pc12", thermo.componentBasis().componentId(15));
        assertEquals(1.0, sum(crude.moleFractions()), 1.0e-12);
        assertTrue(crude.moleFractions()[0] < 1.0);
    }

    @Test
    void sampleCrudeFlashesAtTheDeclaredColumnFeedConditionAsTwoPhase() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(PACKAGE_ID);
        V3CrudeFeed crude = thermo.crudeFeed(ASSAY_ID);
        V3ThermoWorkspace workspace = thermo.newWorkspace();

        V3FlashResult result = thermo.flashTP(638.15, 267_250.0, crude.moleFractions(), workspace);

        assertEquals(V3FeedPhase.TWO_PHASE, result.phase(), result::detail);
        assertTrue(result.vaporFraction() > 0.0 && result.vaporFraction() < 1.0);
        assertEquals(1.0, sum(result.liquidComposition()), 1.0e-12);
        assertEquals(1.0, sum(result.vaporComposition()), 1.0e-12);
        assertTrue(Double.isFinite(result.molarEnthalpyJoulesPerMol()));
        for (int component = 0; component < crude.moleFractions().length; component++) {
            assertEquals(crude.moleFractions()[component], (1.0 - result.vaporFraction())
                    * result.liquidComposition()[component] + result.vaporFraction() * result.vaporComposition()[component],
                    1.0e-9);
        }
    }

    @Test
    void sampleCrudeTreatsBothSinglePhaseEndpointsAsNormalOutcomes() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(PACKAGE_ID);
        V3CrudeFeed crude = thermo.crudeFeed(ASSAY_ID);

        V3FlashResult liquid = thermo.flashTP(298.15, 250_000.0, crude.moleFractions(), thermo.newWorkspace());
        V3FlashResult vapor = thermo.flashTP(900.0, 50_000.0, crude.moleFractions(), thermo.newWorkspace());

        assertEquals(V3FeedPhase.LIQUID, liquid.phase(), liquid::detail);
        assertEquals(0.0, liquid.vaporFraction());
        assertEquals(16, liquid.liquidComposition().length);
        assertEquals(0, liquid.vaporComposition().length);
        assertEquals(V3FeedPhase.VAPOR, vapor.phase(), vapor::detail);
        assertEquals(1.0, vapor.vaporFraction());
        assertEquals(0, vapor.liquidComposition().length);
        assertEquals(16, vapor.vaporComposition().length);
    }

    @Test
    void fugacityAndEnthalpyUseTheSamePhaseEvaluationAndRejectOutOfDomainRequests() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(PACKAGE_ID);
        V3CrudeFeed crude = thermo.crudeFeed(ASSAY_ID);
        V3ThermoWorkspace workspace = thermo.newWorkspace();

        V3FugacityResult fugacity = thermo.fugacity(500.0, 250_000.0, crude.moleFractions(), Root.LIQUID, workspace);
        double enthalpy = thermo.molarEnthalpy(500.0, 250_000.0, crude.moleFractions(), Root.LIQUID, workspace);
        double[] copy = fugacity.logFugacityCoefficients();
        copy[0] = Double.NaN;

        assertEquals(enthalpy, fugacity.molarEnthalpyJoulesPerMol(), 1.0e-9);
        assertTrue(Arrays.stream(fugacity.logFugacityCoefficients()).allMatch(Double::isFinite));
        assertThrows(V3ThermoException.class,
                () -> thermo.flashTP(901.0, 250_000.0, crude.moleFractions(), thermo.newWorkspace()));
    }

    private static double sum(double[] values) {
        double total = 0.0;
        for (double value : values) total += value;
        return total;
    }

    // Merged from V3PengRobinsonRootPrecisionTest: * The V3 half of the PR cubic's root-precision regression: a
    // captured two-phase feed whose flash only converges inside its iteration budget when the kernel's Cardano
    // repair is in place. * <p>The independent ninety-digit oracle and the physical-branch regressions for the
    // cubic itself moved to {@code science.thermo.PengRobinsonKernelRootPrecisionTest} with the kernel. This case
    // stayed here because it drives {@code V3FeedFlash}, which is the column's own flash, not the kernel's.</p>
    private static final double[] CAPTURED_FEED = {
            0.0, 0.0012538307614088564, 0.01125827896184405, 0.030567148542406393,
            0.1378591287206576, 0.06609502137128358, 0.11425564814222493, 0.05540489460148969,
            0.10058448104420363, 0.08801950204022142, 0.07490279910729795, 0.06483752146890542,
            0.05504527455357128, 0.0621402109396859, 0.0961061758627012, 0.04167008388209813};

    @Test
    void exactCapturedFeedFlashConvergesWithinTheUnchangedSixtyFourIterationBudget() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("icheme:cdu17_tjl_acs2018");
        V3ThermoWorkspace workspace = thermo.newWorkspace();
        // Capture contains z after normalization: replay these exact doubles without normalizing a second time.
        System.arraycopy(CAPTURED_FEED, 0, workspace.normalizedOverall, 0, CAPTURED_FEED.length);
        V3FlashResult flash = V3FeedFlash.resolve(thermo, 638.15, 137250.0, workspace);

        assertEquals(V3FeedPhase.TWO_PHASE, flash.phase());
        assertTrue(flash.iterations() > 0 && flash.iterations() <= 64, flash::detail);
        assertEquals(0.7855276025697608, flash.vaporFraction(), 1.0e-9);
        double[] x = flash.liquidComposition();
        double[] y = flash.vaporComposition();
        V3FugacityResult liquid = thermo.fugacity(638.15, 137250.0, x, Root.LIQUID, thermo.newWorkspace());
        V3FugacityResult vapor = thermo.fugacity(638.15, 137250.0, y, Root.VAPOR, thermo.newWorkspace());
        for (int component = 0; component < CAPTURED_FEED.length; component++) {
            assertEquals(CAPTURED_FEED[component], (1.0 - flash.vaporFraction()) * x[component]
                    + flash.vaporFraction() * y[component], 1.0e-12);
            if (CAPTURED_FEED[component] == 0.0) continue;
            double equilibrium = Math.log(y[component]) + vapor.logFugacityCoefficient(component)
                    - Math.log(x[component]) - liquid.logFugacityCoefficient(component);
            assertTrue(Math.abs(equilibrium) <= 1.0e-10, "component " + component + ": " + equilibrium);
        }
    }
}
