package s4;

import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.machine.CentrifugalCompressor;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.thermo.reference.HelmholtzFluid;
import com.wormzjl.icheme.science.thermo.reference.HelmholtzState;
import java.util.Locale;

/**
 * S4 step 5: the compressor's head law at low compressibility, measured, no change. For a suction state and a discharge
 * pressure: the isentropic enthalpy rise and discharge temperature of the law (sigma = k/(k - 1) on the ideal-gas k the
 * network reads, the suction's real P/rho, efficiency 1), the engine's discharge temperature at the law's enthalpy, the
 * simulator sizing's ideal-gas T r^((k-1)/k), against the Helmholtz reference (CoolProp's equation, isentrope at s1).
 */
public final class CompressorLawProbe {
    static final FluidThermodynamics MODEL=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"icheme:refinery");
    static String f(String s,Object... a){return String.format(Locale.ROOT,s,a);}

    static HelmholtzState real(HelmholtzFluid fluid,double t,double p) {
        // Above Tc the two roots are the same; below it, a gas.
        // Above Tc one root; below it, the liquid root above the critical pressure (compressed liquid), else the gas.
        return fluid.stateAtPressure(t,p,t<fluid.criticalTemperature()&&p>fluid.criticalPressure()?HelmholtzFluid.Root.LIQUID:HelmholtzFluid.Root.VAPOUR);
    }
    static HelmholtzState isentrope(HelmholtzFluid fluid,double s,double p,double tGuess) {
        double lo=tGuess,hi=tGuess*2;
        for(int i=0;i<200;i++){double mid=(lo+hi)/2;if(real(fluid,mid,p).entropy()<s)lo=mid;else hi=mid;}
        return real(fluid,(lo+hi)/2,p);
    }

    static void run(String component,String file,double t1,double p1,double p2,double molarMass) {
        double[] n=new double[MODEL.componentCount()];n[MODEL.components().indexOf(component)]=1;
        var s=MODEL.flashTP(t1,p1,n,()->{});
        double rho=s.mass()/s.volume(),k=MODEL.heatCapacityRatio(s),sigma=k/(k-1),r=p2/p1;
        double headLaw=CentrifugalCompressor.polytropicHead(r,p1,rho,sigma);                  // J/kg, efficiency 1
        double h1=s.fluidEnthalpy()/s.totals()[MODEL.components().indexOf(component)];      // J/mol, engine
        var out=MODEL.equilibriumPH(t1,p2,(h1+headLaw*molarMass),n,()->{});
        double tEngine=out.temperature(),tIdeal=t1*Math.pow(r,(k-1)/k);
        var fluid=HelmholtzFluid.load(file);
        var a=real(fluid,t1,p1);var b=isentrope(fluid,a.entropy(),p2,t1);
        double dhReal=(b.enthalpy()-a.enthalpy())/molarMass;                                   // J/kg
        double rhoReal=a.density()*molarMass,zReal=a.compressibility();
        // The real gas's isentropic exponent at the suction, for the record: c_p/c_v times (rho/P)(dP/drho)_T.
        System.err.println("case "+component+" "+t1+" "+p1);
        System.out.println(f("%s %.2f K, %.3f to %.3f MPa (ratio %.3f):",component,t1,p1/1e6,p2/1e6,r));
        System.out.println(f("  suction: engine rho %.2f kg/m3, Z %.4f, delta %.4f, slot %d; reference rho %.2f kg/m3, Z %.4f; ideal-gas k %.4f (sigma %.4f)",
                rho,p1/(rho*8.314462618*t1/molarMass),s.reducedDensity(),s.oneFluidSlot(),rhoReal,zReal,k,sigma));
        System.out.println(f("  isentropic rise: law %.1f J/kg, reference %.1f J/kg, error %+.2f %%",headLaw,dhReal,100*(headLaw/dhReal-1)));
        System.out.println(f("  discharge T: engine at the law's enthalpy %.2f K, sizing ideal-gas %.2f K, reference %.2f K (errors %+.2f K, %+.2f K; rise errors %+.1f %%, %+.1f %%)",
                tEngine,tIdeal,b.temperature(),tEngine-b.temperature(),tIdeal-b.temperature(),100*((tEngine-t1)/(b.temperature()-t1)-1),100*((tIdeal-t1)/(b.temperature()-t1)-1)));
        double p2Law=p1+CentrifugalCompressor.pressureRise(dhReal,p1,rho,sigma);
        System.out.println(f("  the reference's isentropic rise %.1f J/kg as the law's head gives a discharge of %.3f MPa (asked %.3f MPa)",dhReal,p2Law/1e6,p2/1e6));
    }

    public static void main(String[] args) {
        run("CarbonDioxide","CarbonDioxide",320,8e6,16e6,0.0440098);
        run("Nitrogen","Nitrogen",300,10e6,20e6,0.0280134);
        // Two gas-like references: the law should be close there.
        run("CarbonDioxide","CarbonDioxide",320,1e6,2e6,0.0440098);
        run("Nitrogen","Nitrogen",300,1e6,2e6,0.0280134);
        // Denser suctions: near the critical density and on the liquid side.
        run("CarbonDioxide","CarbonDioxide",310,8.3e6,16.6e6,0.0440098);
        run("CarbonDioxide","CarbonDioxide",320,10e6,20e6,0.0440098);
        run("CarbonDioxide","CarbonDioxide",320,15e6,30e6,0.0440098);
    }
}
