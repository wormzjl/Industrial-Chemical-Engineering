package com.wormzjl.icheme.science.column.v3.thermo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;
import com.wormzjl.icheme.science.thermo.PengRobinson78;
import com.wormzjl.icheme.science.thermo.PhaseProperties;
import com.wormzjl.icheme.science.thermo.ThermoComponent;
import org.junit.jupiter.api.Test;

class V3PengRobinsonKernelTest {
    @Test
    void rankOneKernelMatchesIndependentClassicalPrReference() {
        V3Cdu17TiaJuanaPackage propertyPackage = V3Cdu17TiaJuanaPackage.INSTANCE;
        PengRobinsonKernel kernel = V3PengRobinsonSession.kernelFor(propertyPackage);
        double[] feed = propertyPackage.crudeFeed(V3Cdu17TiaJuanaPackage.ASSAY_ID).moleFractions();
        PengRobinsonKernel.Workspace workspace = kernel.newWorkspace();
        PengRobinsonKernel.Evaluation liquid = kernel.newEvaluation();
        PengRobinsonKernel.Evaluation vapor = kernel.newEvaluation();
        kernel.evaluate(500.0, 250_000.0, feed, PengRobinsonKernel.Root.LIQUID, workspace, liquid);
        kernel.evaluate(500.0, 250_000.0, feed, PengRobinsonKernel.Root.VAPOR, workspace, vapor);

        PengRobinson78 reference = PengRobinson78.withoutBinaryInteractions(java.util.stream.IntStream
                .range(0, propertyPackage.componentBasis().componentCount())
                .mapToObj(component -> propertyPackage.component(component))
                .map(component -> new ThermoComponent(component.id(), component.criticalTemperatureKelvin(),
                        component.criticalPressurePascal(), component.acentricFactor(), component.molecularWeightKgPerMol()))
                .toList());
        PhaseProperties expectedLiquid = reference.evaluate(500.0, 250_000.0, feed, PengRobinsonKernel.Root.LIQUID);
        PhaseProperties expectedVapor = reference.evaluate(500.0, 250_000.0, feed, PengRobinsonKernel.Root.VAPOR);

        assertTrue(kernel.usesRankOneMixing());
        assertEquals(expectedLiquid.compressibilityFactor(), liquid.compressibility(), 1e-11);
        assertEquals(expectedVapor.compressibilityFactor(), vapor.compressibility(), 1e-11);
        for (int index = 0; index < 16; index++) {
            assertEquals(expectedLiquid.logFugacityCoefficients()[index], liquid.logFugacityCoefficient(index), 1e-10);
            assertEquals(expectedVapor.logFugacityCoefficients()[index], vapor.logFugacityCoefficient(index), 1e-10);
        }
    }

    @Test
    void sharedPairWorkspaceDoesNotNeedANewEosObjectPerPhase() {
        PengRobinsonKernel kernel = V3PengRobinsonSession.kernelFor(V3Cdu17TiaJuanaPackage.INSTANCE);
        double[] composition = new double[16];
        composition[1] = 0.2;
        composition[5] = 0.3;
        composition[10] = 0.5;
        PengRobinsonKernel.Workspace workspace = kernel.newWorkspace();
        PengRobinsonKernel.Evaluation liquid = kernel.newEvaluation();
        PengRobinsonKernel.Evaluation vapor = kernel.newEvaluation();

        kernel.evaluate(450.0, 300_000.0, composition, PengRobinsonKernel.Root.LIQUID, workspace, liquid);
        kernel.evaluate(450.0, 300_000.0, composition, PengRobinsonKernel.Root.VAPOR, workspace, vapor);
        assertTrue(Double.isFinite(liquid.residualEnthalpyJoulesPerMol()));
        assertTrue(Double.isFinite(vapor.residualEnthalpyJoulesPerMol()));
    }

    @Test
    void packageEnvelopeBoundsBothWilsonAndRigorousPaths() {
        PengRobinsonKernel kernel = V3PengRobinsonSession.kernelFor(V3Cdu17TiaJuanaPackage.INSTANCE);
        assertThrows(IllegalArgumentException.class, () -> kernel.wilsonK(6001.0, 250_000.0, new double[16]));
        assertThrows(IllegalArgumentException.class, () -> kernel.wilsonK(500.0, 1.1e9, new double[16]));
    }

    @Test
    void evaluationDoesNotExposeMutableFugacityWorkspace() {
        PengRobinsonKernel kernel = V3PengRobinsonSession.kernelFor(V3Cdu17TiaJuanaPackage.INSTANCE);
        double[] composition = new double[16];
        composition[1] = 0.4;
        composition[10] = 0.6;
        PengRobinsonKernel.Evaluation evaluation = kernel.newEvaluation();
        kernel.evaluate(450.0, 300_000.0, composition, PengRobinsonKernel.Root.LIQUID,
                kernel.newWorkspace(), evaluation);

        double original = evaluation.logFugacityCoefficient(1);
        double[] copy = evaluation.logFugacityCoefficients();
        copy[1] = Double.NaN;
        assertEquals(original, evaluation.logFugacityCoefficient(1), 0.0);
    }
}
