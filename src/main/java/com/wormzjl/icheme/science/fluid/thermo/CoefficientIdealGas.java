package com.wormzjl.icheme.science.fluid.thermo;

import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.math.DeterministicMath;
import com.wormzjl.icheme.science.thermo.IdealGasFunction;

/**
 * A {@code shifted_polynomial_5} ideal-gas fit as an {@link IdealGasFunction} on the network's sensible datum:
 * {@code c_p = sum a_k (T - 298.15)^k}, the enthalpy its integral from 298.15 K (the network's own ideal-gas
 * enthalpy), the entropy {@code integral c_p/T dT} from 298.15 K minus {@code R ln(P/1e5)}. Past the fit's range
 * {@code [minimum, maximum]} ({@link #fitRange}, the translated model's own ends, so both continue at the same
 * temperatures) the heat capacity is held at the end value and the enthalpy and entropy integrate it (owner rule of
 * 2026-10-16), as {@link TranslatedPengRobinson}'s coefficient path does.
 *
 * <p>The ideal gas of a package without a reference spine, shared by the network ({@link HydrocarbonModel}'s
 * coefficient path), the phase engine's evaluator of that package ({@link HydrocarbonModel#coefficientEvaluator}) and
 * the package's crystals ({@code CrystalPhaseEvaluator.forPackage}). A crystal anchored to this family's liquid at its
 * triple point is then on the network's datum itself, as the free water's ice is: its formation offset is 0 (decision
 * E27 of batch 2026-11-04-open-thermo-data).</p>
 */
public final class CoefficientIdealGas implements IdealGasFunction {
    private static final double REFERENCE=298.15;
    private final double[] main,mainPowers;
    private final double minimum,maximum;

    public CoefficientIdealGas(MaterialCatalog.Property property,double minimum,double maximum) {
        main=property.cp().stream().mapToDouble(Double::doubleValue).toArray();
        mainPowers=powers(main);
        this.minimum=minimum;this.maximum=maximum;
    }

    /** The fit of component {@code componentId} of package {@code packageId} over its {@link #fitRange}. */
    public static CoefficientIdealGas of(MaterialCatalog catalog,String packageId,String componentId) {
        var propertyPackage=catalog.requirePackage(packageId);
        int i=propertyPackage.components().indexOf(propertyPackage.canonicalId(componentId));
        if(i<0)throw new IllegalArgumentException("Component outside package "+packageId+": "+componentId);
        var property=propertyPackage.properties().get(i);
        double[] range=fitRange(catalog,packageId,property);
        return new CoefficientIdealGas(property,range[0],range[1]);
    }

    /**
     * The data range of a coefficient record's ideal-gas fit on package {@code packageId}, K, as {@code {minimum,
     * maximum}}: the component's fluid range where it declares one (the fit qualified there), its record's otherwise,
     * raised to the fit's own lower end where the record declares one (nitrogen's 273.16 K, owner decision D37,
     * simplification S4: the heat capacity held below it is within 0.05 % of the Span et al. 2000 ideal-gas value down to the
     * triple point). Past it the translated model and this function hold the heat capacity.
     */
    public static double[] fitRange(MaterialCatalog catalog,String packageId,MaterialCatalog.Property property) {
        var range=catalog.fluidValidity(packageId,property.component());
        double minimum=range.map(MaterialCatalog.Validity::minimumTemperature).orElse(property.minimumTemperature());
        if(property.idealGasMinimumTemperature()!=null)minimum=Math.max(minimum,property.idealGasMinimumTemperature());
        return new double[]{minimum,range.map(MaterialCatalog.Validity::maximumTemperature).orElse(property.maximumTemperature())};
    }

    @Override public double heatCapacity(double t) {
        return fitHeatCapacity(edge(t));
    }
    @Override public double enthalpy(double t) {
        double e=edge(t),h=antiderivative(main,e);
        return e==t?h:h+fitHeatCapacity(e)*(t-e);
    }
    @Override public double entropy(double t,double p) {
        double e=edge(t);if(!(p>0))throw new IllegalArgumentException("Ideal-gas entropy needs a positive pressure");
        double integral=entropyIntegral(mainPowers,e);
        if(e!=t)integral+=fitHeatCapacity(e)*DeterministicMath.log(t/e);
        return integral-FluidThermodynamics.R*DeterministicMath.log(p/1.0e5);
    }
    @Override public double minimumTemperature(){return minimum;}
    @Override public double maximumTemperature(){return maximum;}
    private double fitHeatCapacity(double t) {
        double delta=t-REFERENCE,cp=0;
        for(int k=main.length-1;k>=0;k--)cp=cp*delta+main[k];
        return cp;
    }
    /** {@code t} inside the fit's range, the nearer end outside it; a temperature that is not positive is refused. */
    private double edge(double t) {
        if(!(t>0)||!Double.isFinite(t))throw new IllegalArgumentException("Ideal-gas temperature must be positive and finite: "+t);
        return t<minimum?minimum:t>maximum?maximum:t;
    }
    /** {@code integral_298.15^T c_p dT}, the Horner form the translated model uses. */
    private static double antiderivative(double[] a,double t) {
        double delta=t-REFERENCE,h=0;
        for(int k=a.length-1;k>=0;k--)h=h*delta+a[k]/(k+1);
        return h*delta;
    }
    /** The same polynomial in powers of {@code T}: {@code sum_m p_m T^m}. */
    private static double[] powers(double[] a) {
        double[] p=new double[a.length];
        for(int k=0;k<a.length;k++) {
            double binomial=1;
            for(int m=0;m<=k;m++) {
                p[m]+=a[k]*binomial*DeterministicMath.pow(-REFERENCE,k-m);
                binomial=binomial*(k-m)/(m+1);
            }
        }
        return p;
    }
    /** {@code integral_298.15^T c_p/T dT} of the polynomial in powers of {@code T}. */
    private static double entropyIntegral(double[] p,double t) {
        double integral=p[0]*DeterministicMath.log(t/REFERENCE);
        double tm=1,rm=1;
        for(int m=1;m<p.length;m++){tm*=t;rm*=REFERENCE;integral+=p[m]*(tm-rm)/m;}
        return integral;
    }
}
