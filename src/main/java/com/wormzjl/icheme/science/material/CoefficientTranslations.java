package com.wormzjl.icheme.science.material;

import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;
import com.wormzjl.icheme.science.thermo.ThermoComponent;
import java.util.List;

/**
 * The constant volume translations of a package's coefficient evaluator, beside {@link VolumeTranslation} (the record's
 * own anchor): each component's untranslated pure liquid at its volume anchor's own (T, P), moved to the anchor's
 * volume. The anchor is the catalog's {@code liquid_calibration.json} point of the component, else the record's standard
 * density. The pure liquid is the kernel's ({@link #pureLiquidVolume}): its {@link PengRobinsonKernel.Root#LIQUID} root,
 * as the phase engine's anchoring of a derived evaluator evaluates it, with no liquid test, so an anchor whose cubic has
 * one real root keeps the translation onto that root, as before.
 *
 * <p>Read by the phase engine's evaluator of a package without a spine (outside the network since W3b) and by
 * {@code SpinePackageData} for a spine component without its own {@code volume_translation}. Moved here from the
 * network's {@code HydrocarbonModel} (batch 2026-11-13-codebase-review, C1b item 5); its own cubic solver gave way to the
 * kernel's (C1b item 7, finding D09): the translations move by the root's rounding.</p>
 */
public final class CoefficientTranslations {
    private CoefficientTranslations() {}

    /**
     * The translations of a package's coefficient evaluator, m3/mol, in package order: each component's untranslated pure
     * liquid on the package's constant matrix at its volume anchor's own (T, P) moved to the anchor's volume.
     */
    public static double[] of(MaterialCatalog catalog,String packageId) {
        var pack=catalog.requirePackage(packageId);int count=pack.components().size();
        var calibrations=catalog.fluidData().volumeReferences();double[] shifts=new double[count];
        List<ThermoComponent> components=new java.util.ArrayList<>(count);double[][] constants=new double[count][count];
        for(int i=0;i<count;i++) {
            var property=pack.properties().get(i);
            components.add(new ThermoComponent(property.component(),property.pr().criticalTemperature(),property.pr().criticalPressure(),property.pr().acentricFactor(),property.molecularWeight()));
            for(int j=0;j<count;j++)constants[i][j]=pack.interactions().get(i).get(j);
        }
        var kernel=pureKernel(components,constants);
        for(int i=0;i<count;i++) {
            var property=pack.properties().get(i);var point=calibrations.get(property.component());
            double t=point==null?property.standardTemperature():point.temperatureKelvin();
            double targetVolume=point==null?property.molecularWeight()/property.density():point.molarVolumeCubicMetres();
            double referenceP=point==null?property.standardPressure():point.pressurePascal();
            shifts[i]=targetVolume-pureLiquidVolume(kernel,i,t,referenceP);
        }
        return shifts;
    }

    /**
     * The kernel over {@code components} on the constant matrix {@code constants} (square, symmetric, zero diagonal), open
     * over every temperature and pressure: the kernel a pure liquid of {@link #pureLiquidVolume} is evaluated on. A pure
     * liquid sees no pair, so the matrix only has to be a valid one.
     */
    public static PengRobinsonKernel pureKernel(List<ThermoComponent> components,double[][] constants) {
        int n=components.size();double[] tc=new double[n],pc=new double[n],omega=new double[n];
        for(int i=0;i<n;i++) {
            var c=components.get(i);tc[i]=c.criticalTemperatureKelvin();pc[i]=c.criticalPressurePascal();omega[i]=c.acentricFactor();
        }
        return new PengRobinsonKernel(tc,pc,omega,constants,Double.MIN_VALUE,Double.MAX_VALUE,Double.MIN_VALUE,Double.MAX_VALUE);
    }

    /**
     * The untranslated molar volume, m3/mol, of pure {@code component} of {@code kernel} at (T, P) on the kernel's liquid
     * root ({@code Z R T / P}): the smallest physical root, the only one where the cubic has one. A state without a
     * physical root is the kernel's {@link PengRobinsonKernel.NoPhysicalRoot}.
     */
    public static double pureLiquidVolume(PengRobinsonKernel kernel,int component,double t,double p) {
        if(!Double.isFinite(t)||t<=0.0||!Double.isFinite(p)||p<=0.0)throw new IllegalArgumentException("Temperature and pressure must be finite and positive");
        double[] pure=new double[kernel.componentCount()];pure[component]=1.0;
        var evaluation=kernel.newEvaluation();
        kernel.evaluate(t,p,pure,PengRobinsonKernel.Root.LIQUID,kernel.newWorkspace(),evaluation);
        return evaluation.compressibility()*PengRobinsonKernel.GAS_CONSTANT*t/p;
    }
}
