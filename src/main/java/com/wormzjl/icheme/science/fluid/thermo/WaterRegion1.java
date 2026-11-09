package com.wormzjl.icheme.science.fluid.thermo;

import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import com.wormzjl.icheme.science.math.DeterministicMath;

/**
 * IF97 Region 1 in SI units, with its original datum. Hybrid-reference offsets are applied by the caller.
 *
 * <p>The fluid network's liquid water at the state pressure, at any positive pressure: the stable liquid of the
 * region's range of validity, {@code ps(T) <= p <= 100 MPa} (IAPWS R7-97(2012), section 5.1), and its continuation
 * past it (owner rule of 2026-10-16, plan RANGE_EXTRAPOLATION_PLAN section 3). Below saturation it is the metastable
 * superheated liquid, which the release covers qualitatively ("In addition to the properties in the stable
 * single-phase liquid region, Eq. (7) also yields reasonable values in the metastable superheated-liquid region close
 * to the saturated liquid line", section 5.1); above 100 MPa the region's own sums. Outside the temperatures from the
 * water model's triple point to the region's 623.15 K boundary every property is held at the nearer end and the
 * volume and enthalpy continue with the held slopes {@code dv/dT} and {@code c_p}, so both are continuous with their
 * slopes: the region's own sums pass their check down to about 100 K but diverge below about 230 K (c_p 911 J/(mol K)
 * and 664 kg/m3 at 200 K). The data ranges are what a notice reports. A result that fails the sums' own check
 * ({@code v > 0}, {@code c_p > 0}, {@code dv/dp < 0}) stays a refusal, a rejected trial.
 */
public final class WaterRegion1 {
    private static final double R = 461.526, P_STAR = 16.53e6, T_STAR = 1386;
    // IAPWS Region 1 table, expressed in (pi - 7.1): odd-I coefficients have reversed signs.
    private static final int[] I = {0,0,0,0,0,0,0,0,1,1,1,1,1,1,2,2,2,2,2,3,3,3,4,4,4,5,8,8,21,23,29,30,31,32};
    private static final int[] J = {-2,-1,0,1,2,3,4,5,-9,-7,-1,0,1,3,-3,0,1,3,17,-4,0,6,-5,-2,10,-8,-11,-6,-29,-31,-38,-39,-40,-41};
    private static final double[] N = {
            .14632971213167,-.84548187169114,-3.756360367204,3.3855169168385,-.95791963387872,
            .15772038513228,-.016616417199501,.00081214629983568,-.00028319080123804,
            .00060706301565874,.018990068218419,.032529748770505,.021841717175414,.00005283835796993,
            -.00047184321073267,-.00030001780793026,.000047661393906987,-4.4141845330846e-6,
            -7.2694996297594e-16,.000031679644845054,2.8270797985312e-6,8.5205128120103e-10,
            -.0000022425281908,-6.5171222895601e-7,-1.4341729937924e-13,4.0516996860117e-7,
            -1.2734301741641e-9,-1.7424871230634e-10,6.8762131295531e-19,-1.4478307828521e-20,
            -2.6335781662795e-23,-1.1947622640071e-23,-1.8228094581404e-24,-9.3537087292458e-26};
    // Exponent span the 34 terms and their first two derivatives reach: x^0..x^32 and y^-43..y^17.
    private static final int HIGHEST_X = 32, LOWEST_Y = -43, HIGHEST_Y = 17;
    /**
     * The declared bound of the metastable liquid's data: the release's "close to the saturated liquid line" is its only
     * statement of validity, and liquid water down to {@code ps(T) - 2 MPa} is what the retired reference-pressure path
     * covered (it evaluated Region 1 at 2 MPa for every state, so every envelope pressure wherever {@code ps(T) <= 2 MPa},
     * T up to 485.5 K). Below it the liquid continues on the region's own sums; a notice reports it.
     */
    public static final double METASTABLE_MARGIN_PASCAL = 2e6;

    private WaterRegion1() {}

    public static State evaluate(double temperatureKelvin,double pressurePascal) {
        return evaluate(MaterialRuntime.water(),temperatureKelvin,pressurePascal);
    }

    /** Liquid water at {@code (T, p)} as a record; {@code water} supplies the triple point the low end holds at. */
    public static State evaluate(MaterialCatalog.Water water,double temperatureKelvin,double pressurePascal) {
        var workspace=new Workspace();
        evaluateLiquid(water,temperatureKelvin,pressurePascal,workspace);
        return workspace.state();
    }
    /** IF97 Region 1's own upper temperature boundary: a boundary of the formulation, where its properties are held. */
    public static final double REGION_1_MAXIMUM_TEMPERATURE=623.15,REGION_1_MAXIMUM_PRESSURE=100e6;

    /**
     * Liquid water at the state pressure, into {@code workspace}, without allocating: Region 1 at the temperature held
     * inside {@code [triple point, 623.15 K]}, continued past it (class comment). A non-finite or non-positive state
     * is refused.
     */
    public static void evaluateLiquid(MaterialCatalog.Water water,double temperatureKelvin,double pressurePascal,Workspace workspace) {
        if (!(temperatureKelvin > 0) || !Double.isFinite(temperatureKelvin) || !Double.isFinite(pressurePascal) || pressurePascal <= 0)
            throw new IllegalArgumentException("Water state is not finite and positive");
        double held=temperatureKelvin<water.triplePoint()?water.triplePoint():Math.min(temperatureKelvin,REGION_1_MAXIMUM_TEMPERATURE);
        fill(workspace,held,pressurePascal);
        if (held!=temperatureKelvin) {
            double step=temperatureKelvin-held;
            workspace.specificVolume+=workspace.volumeTemperatureDerivative*step;
            workspace.specificEnthalpy+=workspace.specificHeatCapacity*step;
            workspace.specificInternalEnergy=workspace.specificEnthalpy-pressurePascal*workspace.specificVolume;
        }
        if (!(workspace.specificVolume>0)) throw new IllegalArgumentException("Invalid Region 1 result");
    }

    /**
     * The Gibbs-function sums at (T, p), with no range check. The powers of {@code tau - 1.222} depend on the
     * temperature alone and are kept for the workspace's last temperature; the powers of {@code pi - 7.1} are
     * rebuilt per call. The arithmetic and its order are fixed, so a reused workspace and a fresh one give the same
     * bits.
     */
    private static void fill(Workspace w,double temperatureKelvin,double pressurePascal) {
        double tau=T_STAR/temperatureKelvin;
        if (temperatureKelvin!=w.temperature) {
            double y=tau-1.222;double[] yp=w.yp;
            yp[-LOWEST_Y]=1;
            for (int k=-LOWEST_Y+1;k<yp.length;k++) yp[k]=yp[k-1]*y;
            double inverse=1/y;
            for (int k=-LOWEST_Y-1;k>=0;k--) yp[k]=yp[k+1]*inverse;
            w.temperature=temperatureKelvin;
        }
        double pi = pressurePascal/P_STAR, x=pi-7.1;
        // The integer powers, built once by multiplication instead of six Math.pow calls per term.
        double[] xp=w.xp,yp=w.yp;xp[0]=1;
        for (int k=1;k<xp.length;k++) xp[k]=xp[k-1]*x;
        double gp=0,gpp=0,gt=0,gtt=0,gpt=0,gptt=0;
        for (int k=0;k<N.length;k++) {
            int i=I[k],j=J[k]-LOWEST_Y; double n=N[k];
            if(i!=0) gp+=n*i*xp[i-1]*yp[j];
            if(i>1) gpp+=n*i*(i-1)*xp[i-2]*yp[j];
            if(J[k]!=0) gt+=n*J[k]*xp[i]*yp[j-1];
            if(J[k]!=0 && J[k]!=1) gtt+=n*J[k]*(J[k]-1)*xp[i]*yp[j-2];
            if(i!=0 && J[k]!=0) gpt+=n*i*J[k]*xp[i-1]*yp[j-1];
            if(i!=0 && J[k]!=0 && J[k]!=1) gptt+=n*i*J[k]*(J[k]-1)*xp[i-1]*yp[j-2];
        }
        double v=R*temperatureKelvin*gp/P_STAR;
        double h=R*temperatureKelvin*tau*gt;
        double cp=-R*tau*tau*gtt;
        double vt=R/P_STAR*(gp-tau*gpt);
        double vp=R*temperatureKelvin*gpp/(P_STAR*P_STAR);
        if (!(v>0) || !(cp>0) || !(vp<0) || !Double.isFinite(h)) throw new IllegalArgumentException("Invalid Region 1 result");
        w.specificVolume=v;w.specificEnthalpy=h;w.specificInternalEnergy=h-pressurePascal*v;w.specificHeatCapacity=cp;
        w.volumeTemperatureDerivative=vt;w.volumePressureDerivative=vp;w.enthalpyPressureDerivative=v-temperatureKelvin*vt;
        w.volumeSecondTemperatureDerivative=R*tau*tau*gptt/(P_STAR*temperatureKelvin);
    }

    /**
     * Caller-owned Region 1 scratch and result: the two power tables and the last evaluation's properties. Mutable,
     * so - like the Peng-Robinson workspace a node's prepared temperature holds - it belongs to one solving thread.
     */
    public static final class Workspace {
        private final double[] xp=new double[HIGHEST_X+1],yp=new double[HIGHEST_Y-LOWEST_Y+1];
        private double temperature=Double.NaN;
        private double specificVolume,specificEnthalpy,specificInternalEnergy,specificHeatCapacity,
                volumeTemperatureDerivative,volumePressureDerivative,enthalpyPressureDerivative,volumeSecondTemperatureDerivative;
        public Workspace() {}
        public double specificVolume(){return specificVolume;}
        public double specificEnthalpy(){return specificEnthalpy;}
        /** c_p, J/(kg K): the region's at the evaluated temperature, the held end value past its range. */
        public double specificHeatCapacity(){return specificHeatCapacity;}
        public double volumePressureDerivative(){return volumePressureDerivative;}
        /** A copy of the last evaluation as the immutable record. */
        public State state() {
            return new State(specificVolume,specificEnthalpy,specificInternalEnergy,specificHeatCapacity,volumeTemperatureDerivative,
                    volumePressureDerivative,enthalpyPressureDerivative,volumeSecondTemperatureDerivative);
        }
    }

    /** The dilute-gas term's data floor, K, and its value (Pa.s) and log slope {@code d ln mu / d ln T} there. */
    public static final double VISCOSITY_LOW = 273.16;
    private static final double VISCOSITY_AT_LOW = diluteFormula(VISCOSITY_LOW / 647.096);
    private static final double VISCOSITY_EXPONENT_AT_LOW = diluteExponent(VISCOSITY_LOW / 647.096);

    /**
     * IAPWS 2008 dilute-gas viscosity term (Pa.s); not a liquid or dense-steam correlation. Its data range is 273.16 to
     * 900 K. Above it the formula continues itself; below it the viscosity goes as a power of the temperature with the
     * formula's own exponent at 273.16 K ({@code d ln mu / d ln T}, 0.876), value and slope continuous, because
     * the formula's denominator has a root near 134.5 K.
     */
    public static double diluteVaporViscosity(double temperatureKelvin) {
        if (!(temperatureKelvin > 0) || !Double.isFinite(temperatureKelvin))
            throw new IllegalArgumentException("Vapor viscosity temperature must be positive and finite: " + temperatureKelvin);
        if (temperatureKelvin < VISCOSITY_LOW)
            return VISCOSITY_AT_LOW * DeterministicMath.pow(temperatureKelvin / VISCOSITY_LOW, VISCOSITY_EXPONENT_AT_LOW);
        return diluteFormula(temperatureKelvin / 647.096);
    }

    private static double diluteFormula(double reduced) {
        return 1e-4*Math.sqrt(reduced)/(1.67752+2.20462/reduced+.6366564/(reduced*reduced)-.241605/(reduced*reduced*reduced));
    }
    /** {@code d ln mu / d ln T} of {@link #diluteFormula}: {@code 1/2 + (a1/x + 2 a2/x^2 + 3 a3/x^3) / D(x)}. */
    private static double diluteExponent(double reduced) {
        double denominator=1.67752+2.20462/reduced+.6366564/(reduced*reduced)-.241605/(reduced*reduced*reduced);
        return .5+(2.20462/reduced+2*.6366564/(reduced*reduced)-3*.241605/(reduced*reduced*reduced))/denominator;
    }

    public record State(double specificVolume,double specificEnthalpy,double specificInternalEnergy,
                        double specificHeatCapacity,double volumeTemperatureDerivative,
                        double volumePressureDerivative,double enthalpyPressureDerivative,
                        double volumeSecondTemperatureDerivative) {}
}
