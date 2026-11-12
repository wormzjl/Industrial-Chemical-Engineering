package com.wormzjl.icheme.science.fluid.thermo;

import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Nitrogen is valid - not merely allowed - from its triple point to the package maximum (F4, T1.3). Every reference is
 * the Span et al. 2000 equation of state and the Lemmon and Jacobsen 2004 viscosity as CoolProp 8.0.0 states them (MIT),
 * evaluated by tools/open-thermo-data (the Java Helmholtz oracle and lib.mjs); until batch 2026-11-04-open-thermo-data they
 * were the NIST WebBook values of the same equations, which agree to 1e-6. The model is the network's own: PR78 with CoolProp constants, the
 * liquid at the state pressure (the direct liquid path of P3 WP4; before it, the 2 MPa reference carried by a global
 * 1e-9 1/Pa compressibility), the translation anchored at the reference liquid volume at 90 K and 2 MPa at that state, the
 * ideal-gas fit to CoolProp's alpha0, its heat capacity held below the fit's 273.16 K lower end (owner decision D37, simplification
 * S4, which deleted F4's segment below it).
 *
 * <p>Tolerances are chosen from the measured numbers, not beyond them: saturation pressure measured +1.21 % (PR78 with
 * CoolProp's acentric factor; tolerance 1.5 %; +1.23 % on the retired 2 MPa path), saturated liquid density -0.07 %
 * (tolerance 0.5 %; +0.34 % on the retired path, whose global 1e-9 1/Pa expanded the liquid too little from 2 MPa to
 * 101 kPa), vapour Cp at 1 atm -0.96 % at 100 K and +0.04 % at 200 K (tolerance 1 %, the brief's; at 100 K PR78's
 * residual heat capacity near saturation is 0.3 J/mol/K low, the held ideal-gas part 0.03 % above the reference; -0.99 % and
 * +0.03 % with F4's segment).
 */
class FluidNitrogenCryogenicTest {
    private static final String NETWORK="icheme:tjl20_methane_nitrogen";
    private final MaterialCatalog catalog=MaterialCatalog.bundled();
    private final FluidThermodynamics model=FluidThermodynamics.forNetwork(catalog,NETWORK);
    private final int nitrogen=model.hydrocarbon.components().indexOf("Nitrogen");
    private final double[] pure=pure();
    private double[] pure(){double[] x=new double[model.hydrocarbon.componentCount()];x[model.hydrocarbon.components().indexOf("Nitrogen")]=1;return x;}
    /** Span et al. 2000 at 77.355 K (CoolProp 8.0.0): saturation 101,325.073 Pa, saturated liquid 806.0845 kg/m3. */
    private static final double NBP=77.355,REFERENCE_SATURATION=101325.0727426844,REFERENCE_LIQUID_DENSITY=806.0845073859737;

    private double logFugacity(double t,double p,PengRobinsonKernel.Root root){return model.hydrocarbon.phase(t,p,TestPhases.conserved(model.hydrocarbon,pure),root).logFugacityView()[nitrogen];}
    /** The pressure where the network's liquid and vapour fugacities of pure nitrogen agree, by bisection in log P. */
    private double saturation(double t) {
        double low=2e4,high=4e5;
        for(int k=0;k<200;k++){double p=Math.sqrt(low*high);if(logFugacity(t,p,PengRobinsonKernel.Root.LIQUID)>logFugacity(t,p,PengRobinsonKernel.Root.VAPOR))low=p;else high=p;}
        return Math.sqrt(low*high);
    }

    @Test void saturationPressureAtTheNormalBoilingPointAgreesWithTheReference() {
        double p=saturation(NBP);
        System.out.printf(Locale.ROOT,"nitrogen Psat(77.355 K): model %.3f Pa, reference %.3f Pa, %+.3f %%%n",p,REFERENCE_SATURATION,100*(p/REFERENCE_SATURATION-1));
        assertEquals(1,p/REFERENCE_SATURATION,.015,"PR78 saturation at the normal boiling point");
    }

    @Test void saturatedLiquidDensityAtTheNormalBoilingPointAgreesWithTheReference() {
        double density=model.hydrocarbon.molecularWeight(nitrogen)/model.hydrocarbon.phase(NBP,REFERENCE_SATURATION,TestPhases.conserved(model.hydrocarbon,pure),PengRobinsonKernel.Root.LIQUID).molarVolume();
        System.out.printf(Locale.ROOT,"nitrogen saturated liquid density (77.355 K): model %.4f kg/m3, reference %.4f, %+.3f %%%n",density,REFERENCE_LIQUID_DENSITY,100*(density/REFERENCE_LIQUID_DENSITY-1));
        assertEquals(1,density/REFERENCE_LIQUID_DENSITY,.005,"translated PR78 liquid density");
    }

    /** The vapour heat capacity the network integrates: the enthalpy of the network's own vapour state, differenced. */
    @Test void vapourHeatCapacityAt100And200KelvinAgreesWithTheReferenceWithinOnePercent() {
        double[][] reference={{100,30.024929077681016},{200,29.23213134307229}};
        for(double[] row:reference) {
            double t=row[0],h=1e-3;
            double cp=(vapourEnthalpy(t+h,101325)-vapourEnthalpy(t-h,101325))/(2*h);
            System.out.printf(Locale.ROOT,"nitrogen vapour Cp(%.0f K, 1 atm): model %.5f J/mol/K, reference %.5f, %+.3f %%%n",t,cp,row[1],100*(cp/row[1]-1));
            assertEquals(1,cp/row[1],.01,"vapour Cp at "+t+" K");
        }
    }
    private double vapourEnthalpy(double t,double p) {
        double[] none=new double[pure.length];
        return TestPhases.state(model,t,p,none,pure,0,0).enthalpy();
    }

    /**
     * The zero-pressure heat capacity of Span et al. 2000 (CoolProp's alpha0). Below the fit's 273.16 K lower end the network
     * holds the fit's value there, which is the reference's, 29.116 J/(mol K): within 0.05 % (0.047 %) of it down to the triple
     * point (D37 S4). Until batch 2026-11-04-open-thermo-data the held value was a NIST Shomate fit's, 29.111 J/(mol K).
     */
    @Test void theHeldIdealGasHeatCapacityBelow273KelvinFollowsTheReference() {
        double[][] reference={{63.16,29.102550434806076},{78.16,29.103014989583812},{98.16,29.103661202070708},{148.16,29.105410375587933},
                {198.16,29.107449326612468},{248.16,29.111475246622835},{273.16,29.116325189137022}};
        var eos=model.hydrocarbon.translated();
        double worst=0;
        for(double[] row:reference) {
            double cp0=eos.evaluateValues(row[0],100,TestPhases.conserved(model.hydrocarbon,pure),PengRobinsonKernel.Root.VAPOR,eos.prepare(row[0])).idealGasHeatCapacity();
            assertEquals(1,cp0/row[1],5e-4,"ideal-gas Cp at "+row[0]+" K: "+cp0);
            worst=Math.max(worst,Math.abs(cp0/row[1]-1));
        }
        System.out.printf(Locale.ROOT,"nitrogen ideal-gas Cp held below 273.16 K: worst %.4f %% of the zero-pressure Cp (63.16-273.16 K)%n",100*worst);
    }

    @Test void theNetworkPackageDeclaresTheNitrogenTriplePointAsItsMinimum() {
        var envelope=catalog.fluidValidity(NETWORK).orElseThrow();var own=catalog.fluidValidity(NETWORK,"Nitrogen").orElseThrow();
        assertEquals(63.151,envelope.minimumTemperature());assertEquals(63.151,own.minimumTemperature());
        // 10 MPa since P3 WP7 (design section 8.1: nitrogen and the network envelope widened; the cuts keep 2 MPa).
        assertEquals(900,envelope.maximumTemperature());assertEquals(100,envelope.minimumPressure());assertEquals(1e7,envelope.maximumPressure());
        assertEquals(63.151,catalog.requirePackage(NETWORK).properties().get(nitrogen).minimumTemperature(),"the record's own range starts at its triple point");
        // A vapour at 5 kPa (below its 12.5 kPa saturation pressure) is a state just above the triple point and, since the
        // owner rule of 2026-10-16 (a freezing point is never a validity bound), just below it too.
        double[] n=new double[model.componentCount()];n[nitrogen]=1;
        assertDoesNotThrow(()->model.flashTP(63.2,5000,n,()->{}));
        var below=assertDoesNotThrow(()->model.flashTP(63.1,5000,n,()->{}));
        assertTrue(below.vaporVolume()>0&&TestPhases.liquidVolume(below)==0,"a gas below the triple point");
    }

    /** Liquid nitrogen at its normal boiling point is a whole network state, with a viscosity from the reference table. */
    @Test void liquidNitrogenIsAWholeNetworkState() {
        double[] n=new double[model.componentCount()];n[nitrogen]=1;
        var state=model.flashTP(NBP,2e5,n,()->{});
        assertTrue(TestPhases.liquidVolume(state)>0&&state.vaporVolume()==0,"compressed liquid below its saturation temperature");
        double mu=model.viscosity.liquid(NBP,TestPhases.oil(state)).pascalSeconds();
        // Lemmon and Jacobsen 2004 saturated liquid at 77.355 K: 160.662 uPa.s (the table is the saturated liquid).
        assertEquals(1,mu/160.66150338022911e-6,.005,"liquid viscosity "+mu);
        assertTrue(model.viscosity.vapor(100,TestPhases.conserved(model.hydrocarbon,pure))>0);
    }
}
