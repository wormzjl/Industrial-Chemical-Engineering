package com.wormzjl.icheme.science.material;

import com.wormzjl.icheme.science.math.DeterministicMath;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;
import com.wormzjl.icheme.science.thermo.ThermoComponent;

/**
 * The constant volume translations of a package's coefficient evaluator, beside {@link VolumeTranslation} (the record's
 * own anchor): each component's untranslated pure liquid at its volume anchor's own (T, P), moved to the anchor's
 * volume. The anchor is the catalog's {@code liquid_calibration.json} point of the component, else the record's standard
 * density. The pure liquid is PR78 with classical mixing on its own pure-component cubic
 * ({@link #pureLiquidCompressibility}, as the test tree's {@code PengRobinson78}): the same constants and kappa rule as
 * {@link PengRobinsonKernel}, its own root policy (the smallest real root above {@code B + 1e-12}, no refinement).
 *
 * <p>Read by the phase engine's evaluator of a package without a spine (outside the network since W3b) and by
 * {@code SpinePackageData} for a spine component without its own {@code volume_translation}. Moved here from the
 * network's {@code HydrocarbonModel} (batch 2026-11-13-codebase-review, C1b item 5), operation for operation.</p>
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
        for(int i=0;i<count;i++) {
            var property=pack.properties().get(i);var point=calibrations.get(property.component());
            var component=new ThermoComponent(property.component(),property.pr().criticalTemperature(),property.pr().criticalPressure(),property.pr().acentricFactor(),property.molecularWeight());
            double t=point==null?property.standardTemperature():point.temperatureKelvin();
            double targetVolume=point==null?property.molecularWeight()/property.density():point.molarVolumeCubicMetres();
            double referenceP=point==null?property.standardPressure():point.pressurePascal();
            shifts[i]=targetVolume-pureLiquidCompressibility(component,pack.interactions().get(i).get(i),t,referenceP)*PengRobinsonKernel.GAS_CONSTANT*t/referenceP;
        }
        return shifts;
    }
    /** The liquid-root compressibility factor of pure {@code c} on PR78 with classical mixing (as {@code PengRobinson78}). */
    public static double pureLiquidCompressibility(ThermoComponent c,double kcc,double t,double p) {
        if(!Double.isFinite(t)||t<=0.0||!Double.isFinite(p)||p<=0.0)throw new IllegalArgumentException("Temperature and pressure must be finite and positive");
        double r=PengRobinsonKernel.GAS_CONSTANT,tc=c.criticalTemperatureKelvin(),pc=c.criticalPressurePascal(),omega=c.acentricFactor();
        double criticalA=0.45724*square(r)*square(tc)/pc;
        double coVolume=0.07780*r*tc/pc;
        double kappa=omega<=0.491?0.37464+1.54226*omega-0.26992*square(omega)
                :0.379642+1.48503*omega-0.164423*square(omega)+0.016666*omega*square(omega);
        double alphaTerm=1.0+kappa*(1.0-Math.sqrt(t/tc));
        double a=criticalA*square(alphaTerm);
        double aMix=Math.sqrt(a*a)*(1.0-kcc),bMix=coVolume;
        double reducedA=aMix*p/(square(r)*square(t));
        double reducedB=bMix*p/(r*t);
        if(Math.abs(reducedA)<1.0e-14&&Math.abs(reducedB)<1.0e-14)return 1.0;
        double c2=-(1.0-reducedB);
        double c1=reducedA-3.0*square(reducedB)-2.0*reducedB;
        double c0=-(reducedA*reducedB-square(reducedB)-reducedB*square(reducedB));
        double[] roots=realCubicRoots(c2,c1,c0);
        java.util.Arrays.sort(roots);
        for(double root:roots)if(root>reducedB+1.0e-12)return root;
        throw new IllegalStateException("Peng-Robinson equation has no physical liquid root");
    }
    private static double[] realCubicRoots(double c2,double c1,double c0) {
        double p=c1-square(c2)/3.0;
        double q=2.0*c2*square(c2)/27.0-c2*c1/3.0+c0;
        double discriminant=square(q)/4.0+p*p*p/27.0;
        double offset=c2/3.0;
        if(discriminant>1.0e-16) {
            double root=Math.sqrt(discriminant);
            return new double[]{DeterministicMath.cbrt(-q/2.0+root)+DeterministicMath.cbrt(-q/2.0-root)-offset};
        }
        if(Math.abs(discriminant)<=1.0e-16) {
            double u=DeterministicMath.cbrt(-q/2.0);
            return new double[]{2.0*u-offset,-u-offset};
        }
        double radius=2.0*Math.sqrt(-p/3.0);
        double angle=DeterministicMath.acos(Math.clamp((3.0*q/(2.0*p))*Math.sqrt(-3.0/p),-1.0,1.0))/3.0;
        return new double[]{radius*DeterministicMath.cos(angle)-offset,radius*DeterministicMath.cos(angle-2.0*Math.PI/3.0)-offset,
                radius*DeterministicMath.cos(angle-4.0*Math.PI/3.0)-offset};
    }
    private static double square(double value){return value*value;}
}
