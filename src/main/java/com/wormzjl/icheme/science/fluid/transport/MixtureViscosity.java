package com.wormzjl.icheme.science.fluid.transport;

import com.wormzjl.icheme.science.math.DeterministicMath;

import com.wormzjl.icheme.science.fluid.thermo.WaterRegion1;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.ViscosityCorrelation;
import java.util.Arrays;
import java.util.Map;

/** Reference-pressure transport approximation: logarithmic liquid mixing and Wilke vapor mixing. */
public final class MixtureViscosity {
    private final Map<String,ViscosityCorrelation> conditional;
    private final String revision;
    private final com.wormzjl.icheme.science.material.LiquidMixtureCorrection liquidMixture;
    private final MaterialCatalog.Package propertyPackage;
    private final ViscosityCorrelation[] liquidCorrelations,vaporCorrelations;
    private final ViscosityCorrelation waterLiquidCorrelation;
    /** The compounds (water not among them) in package order, their package indices, and the correction's participants on the conserved basis. */
    private final java.util.List<String> compounds;
    private final int[] packageIndex,correctionParticipants;
    private final double[][] wilkeMassQuarter,wilkeDenominator;
    /** Per conserved component (water last), the residual's constants: Tc (K), Pc (Pa), molar mass (kg/mol), critical volume (m3/mol). */
    private final double[] criticalTemperature,criticalPressure,molarMass,criticalVolume;

    public MixtureViscosity(MaterialCatalog catalog,String packageId) {
        propertyPackage=catalog.requirePackage(packageId);
        conditional=catalog.fluidData().conditionalLiquidViscosities();
        liquidMixture=catalog.liquidMixture(packageId);
        // The declared revisions of the data (the package's records, its water, the fluid reference data) and the
        // formulation labels; nothing is hashed (owner decision 2026-10-26).
        // W3b (owner, 2026-11-11): one log-mixing rule over a liquid's components on the conserved basis, water last with its
        // own liquid viscosity; the vapour's Wilke rule over the same basis.
        revision=catalog.fluidRevision(packageId)+":log-liquid-pair-groups-wilke-v2:catalog-conditional-solute-v1:supercritical-liquid-slot-dilute-v1:range-continuation-v1:water-in-liquid-log-v1:dense-residual-jst-v1";
        var list=new java.util.ArrayList<>(propertyPackage.components());list.remove("Water");compounds=java.util.List.copyOf(list);
        int count=compounds.size();liquidCorrelations=new ViscosityCorrelation[count];vaporCorrelations=new ViscosityCorrelation[count];packageIndex=new int[count];
        double[] mw=new double[count+1];
        for(int i=0;i<count;i++) {
            String component=compounds.get(i);packageIndex[i]=propertyPackage.components().indexOf(component);
            liquidCorrelations[i]=catalog.viscosity(packageId,component,ViscosityCorrelation.Phase.LIQUID).orElse(null);
            vaporCorrelations[i]=catalog.viscosity(packageId,component,ViscosityCorrelation.Phase.VAPOR).orElse(null);
            mw[i]=propertyPackage.properties().get(packageIndex[i]).molecularWeight();
        }
        var participants=new java.util.ArrayList<Integer>();
        for(int i=0;i<count;i++)if(liquidMixture.participants().contains(packageIndex[i]))participants.add(i);
        correctionParticipants=participants.stream().mapToInt(Integer::intValue).toArray();
        mw[count]=propertyPackage.water().molarMass();waterLiquidCorrelation=catalog.viscosity(packageId,"Water",ViscosityCorrelation.Phase.LIQUID).orElse(null);
        criticalTemperature=new double[count+1];criticalPressure=new double[count+1];molarMass=mw.clone();criticalVolume=new double[count+1];
        for(int i=0;i<count;i++)residualConstants(i,propertyPackage.properties().get(packageIndex[i]));
        // Water: its dataset where the package names it (the network's cubic water), else the water model's constants with
        // the cubic's own critical volume.
        int waterIndex=propertyPackage.components().indexOf("Water");
        if(waterIndex>=0)residualConstants(count,propertyPackage.properties().get(waterIndex));
        else {
            var water=propertyPackage.water();
            criticalTemperature[count]=water.criticalTemperature();criticalPressure[count]=water.criticalPressure();
            criticalVolume[count]=MaterialCatalog.CriticalVolume.CUBIC_RATIO*0.07780*com.wormzjl.icheme.science.thermo.PengRobinsonKernel.GAS_CONSTANT
                    *water.criticalTemperature()/water.criticalPressure();
        }
        wilkeMassQuarter=new double[count+1][count+1];wilkeDenominator=new double[count+1][count+1];
        for(int i=0;i<=count;i++)for(int j=0;j<=count;j++){wilkeMassQuarter[i][j]=Math.sqrt(Math.sqrt(mw[j]/mw[i]));wilkeDenominator[i][j]=1/Math.sqrt(8*(1+mw[i]/mw[j]));}
    }
    private void residualConstants(int i,MaterialCatalog.Property property) {
        var pr=property.pr();
        criticalTemperature[i]=pr==null?Double.NaN:pr.criticalTemperature();criticalPressure[i]=pr==null?Double.NaN:pr.criticalPressure();
        molarMass[i]=property.molecularWeight();criticalVolume[i]=property.criticalMolarVolume();
    }
    public String revision() {
        return revision;
    }

    /** One standard atmosphere, Pa: the Jossi-Stiel-Thodos correlation takes Pc in atm. */
    private static final double ATMOSPHERE=101325.0;
    /** The Jossi-Stiel-Thodos polynomial in the reduced density, as Lohrenz, Bray and Clark state it. */
    private static final double JST_0=0.1023,JST_1=0.023364,JST_2=0.058533,JST_3=-0.040758,JST_4=0.0093324;

    /**
     * The dense-fluid residual viscosity, Pa s, of one fluid of conserved {@code amounts} (water last) at molar density
     * {@code molarDensity} (mol/m3): the Jossi-Stiel-Thodos form as Lohrenz, Bray and Clark state it (J. Pet. Technol. 16
     * (1964) 1171, after Jossi, Stiel and Thodos, AIChE J. 8 (1962) 59), on mole-fraction pseudo-critical constants
     * ({@link #jossiStielThodos}): {@code rho_r = rho sum(x_i Vc_i)} with each dataset's critical volume
     * ({@link MaterialCatalog.Property#criticalMolarVolume}), {@code Tc}, {@code Pc} and the molar mass averaged the same way.
     * Zero at zero density, exactly. Owner decision O5 A (batch 2026-11-15-supercritical-fluids, S3); nothing calls it in
     * the network yet (S2 wires it for dense one-fluid nodes).
     */
    public double residual(double molarDensity,double[] amounts) {
        checkBasis(amounts);
        if(!(molarDensity>=0)||!Double.isFinite(molarDensity))throw new IllegalArgumentException("Invalid molar density");
        double sum=0,t=0,p=0,m=0,v=0;
        for(int i=0;i<amounts.length;i++) {
            double n=amounts[i];if(n==0)continue;
            sum+=n;t+=n*criticalTemperature[i];p+=n*criticalPressure[i];m+=n*molarMass[i];v+=n*criticalVolume[i];
        }
        if(!(sum>0))throw new IllegalArgumentException("Invalid transport amount");
        double result=jossiStielThodos(molarDensity*v/sum,t/sum,p/sum,m/sum);
        if(!(result>=0)||!Double.isFinite(result))throw new IllegalArgumentException("PROPERTY_UNAVAILABLE: residual viscosity");
        return result;
    }

    /**
     * The Jossi-Stiel-Thodos residual viscosity, Pa s, at reduced density {@code reducedDensity} of a fluid with critical
     * temperature (K), critical pressure (Pa) and molar mass (kg/mol): {@code (f(rho_r)^4 - f(0)^4) / xi} cP,
     * {@code f(rho_r) = 0.1023 + 0.023364 rho_r + 0.058533 rho_r^2 - 0.040758 rho_r^3 + 0.0093324 rho_r^4},
     * {@code xi = Tc^(1/6) M^(-1/2) Pc^(-2/3)} in K, g/mol and atm. The published form subtracts {@code 1e-4}; {@code f(0)^4}
     * is {@code 1.0952e-4}, so the published residual is {@code 9.52e-6 / xi} cP at zero density (carbon dioxide: 0.42
     * uPa s, 3 % of its 15 uPa s dilute viscosity at 300 K). Subtracting {@code f(0)^4} makes it exactly zero there, so a gas keeps
     * its dilute viscosity. Increasing in {@code rho_r} from 0 to beyond 3. Elementary functions through
     * {@link DeterministicMath}.
     */
    public static double jossiStielThodos(double reducedDensity,double criticalTemperature,double criticalPressure,double molarMass) {
        double r=reducedDensity;
        double xi=DeterministicMath.exp(DeterministicMath.log(criticalTemperature)/6-2.0/3*DeterministicMath.log(criticalPressure/ATMOSPHERE))
                /Math.sqrt(molarMass*1000);
        double f=JST_0+r*(JST_1+r*(JST_2+r*(JST_3+r*JST_4)));
        double f2=f*f,f02=JST_0*JST_0;
        return (f2*f2-f02*f02)/xi*1e-3;
    }

    /**
     * The dense-fluid viscosity, Pa s: the vapour's Wilke dilute viscosity ({@link #vapor}) plus the residual at the
     * fluid's molar density ({@link #residual}). Equal to the dilute value at zero density.
     */
    public double dense(double temperature,double[] amounts,double molarDensity,Workspace workspace,Prepared prepared) {
        return vapor(temperature,amounts,workspace,prepared)+residual(molarDensity,amounts);
    }

    /**
     * The pure-component terms of one exact temperature: the logarithm of each liquid viscosity
     * with the regime it came from, each vapor viscosity and its square root, and the water liquid
     * value. Composition-dependent mixing - the logarithmic weighting and Wilke's double loop -
     * stays per evaluation, but nothing here depends on composition or pressure.
     *
     * <p>Each component is filled the first time a mixture actually contains it, never in advance:
     * a component without data is refused, and which refusal a caller meets must stay exactly what
     * the composition it passed would have produced. Past its data range a correlation continues
     * ({@link ViscosityCorrelation#dynamicViscosityPascalSeconds}).
     */
    public static final class Prepared {
        private static final byte UNFILLED=0,CARRIER=1,CONDITIONAL_SOLUTE=2;
        private final MixtureViscosity owner;
        private final double temperature;
        private final double[] liquidLog,vaporMu,vaporSqrt;
        private final byte[] liquidKind;
        private double water=Double.NaN;
        private Prepared(MixtureViscosity owner,double temperature) {
            this.owner=owner;this.temperature=temperature;
            int count=owner.compounds.size();
            liquidLog=new double[count];liquidKind=new byte[count];
            vaporMu=new double[count+1];vaporSqrt=new double[count+1];
            Arrays.fill(vaporMu,Double.NaN);
        }
        private byte liquid(int component) {
            if(liquidKind[component]==UNFILLED) {
                owner.fillLiquid(this,component);
            }
            return liquidKind[component];
        }
        private double vapor(int component) {
            if(Double.isNaN(vaporMu[component])) {
                vaporMu[component]=owner.pureVapor(temperature,component);vaporSqrt[component]=Math.sqrt(vaporMu[component]);
            }
            return vaporMu[component];
        }
    }
    /** The bundle for one exact temperature; a caller holds one per node and reuses it across trials. */
    public Prepared prepare(double temperature) { return new Prepared(this,temperature); }
    private Prepared own(Prepared prepared,double temperature) {
        if(prepared!=null&&(prepared.owner!=this||prepared.temperature!=temperature)) {
            throw new IllegalArgumentException("Prepared viscosities belong to another model/temperature");
        }
        return prepared;
    }
    private static final int LIQUID_TABLE=0,VAPOR_DATA=1,SOLUTE_CURVE=2;
    /**
     * Which data a component's liquid viscosity comes from at {@code temperature}. Up to the end of its liquid table, the
     * table (continued below it). Above it: a supercritical component (at or above its critical temperature, with vapour
     * data, below any dissolved-solute curve) takes its dilute vapour viscosity, because the one-root slot is the phase
     * identification parameter's, so a dense supercritical fluid (nitrogen above 126.192 K at pressures to 10 MPa) is
     * liquid-like there; the vapour slot gives the same root that value, so the slot flip does not jump a pure fluid's
     * viscosity (declared: no dense-fluid viscosity model exists; transport of dense states above 2 MPa is unqualified).
     * Else its dissolved-solute curve from the curve's start, and else the liquid table's end value, held. A component
     * without a liquid table is refused: no data.
     */
    private int liquidSource(int component,double temperature) {
        var correlation=liquidCorrelations[component];
        if(correlation==null)throw new IllegalArgumentException("PROPERTY_UNAVAILABLE: liquid viscosity for "+compounds.get(component));
        if(temperature<=correlation.maximumTemperatureKelvin())return LIQUID_TABLE;
        var curve=conditional.get(compounds.get(component));
        if(temperature>=propertyPackage.properties().get(packageIndex[component]).pr().criticalTemperature()&&vaporCorrelations[component]!=null
                &&(curve==null||temperature<curve.minimumTemperatureKelvin()))return VAPOR_DATA;
        return curve!=null&&temperature>=curve.minimumTemperatureKelvin()?SOLUTE_CURVE:LIQUID_TABLE;
    }
    /** The correlation a component's liquid viscosity is read from at {@code temperature} ({@link #liquidSource}): the data
     * range a notice compares the temperature with; null for a component without liquid viscosity data. */
    public ViscosityCorrelation liquidData(int component,double temperature) {
        if(liquidCorrelations[component]==null)return null;
        return switch(liquidSource(component,temperature)) {
            case VAPOR_DATA->vaporCorrelations[component];
            case SOLUTE_CURVE->conditional.get(compounds.get(component));
            default->liquidCorrelations[component];
        };
    }
    /** A hydrocarbon component's vapour viscosity correlation, or null without one. */
    public ViscosityCorrelation vaporData(int component){return vaporCorrelations[component];}
    /** The water liquid viscosity correlation, or null without one. */
    public ViscosityCorrelation waterLiquidData(){return waterLiquidCorrelation;}
    private void fillLiquid(Prepared prepared,int component) {
        double temperature=prepared.temperature,mu;
        switch(liquidSource(component,temperature)) {
            case VAPOR_DATA->{mu=pureVapor(temperature,component);prepared.liquidKind[component]=Prepared.CARRIER;}
            case SOLUTE_CURVE->{
                mu=conditional.get(compounds.get(component)).dynamicViscosityPascalSeconds(temperature,ViscosityCorrelation.Phase.LIQUID);
                prepared.liquidKind[component]=Prepared.CONDITIONAL_SOLUTE;
            }
            default->{mu=liquidCorrelations[component].dynamicViscosityPascalSeconds(temperature,ViscosityCorrelation.Phase.LIQUID);prepared.liquidKind[component]=Prepared.CARRIER;}
        }
        prepared.liquidLog[component]=DeterministicMath.log(mu);
    }
    private double pureVapor(double temperature,int component) {
        if(component==liquidCorrelations.length)return WaterRegion1.diluteVaporViscosity(temperature);
        var c=vaporCorrelations[component];if(c==null)throw new IllegalArgumentException("PROPERTY_UNAVAILABLE: vapor viscosity");
        return c.dynamicViscosityPascalSeconds(temperature,ViscosityCorrelation.Phase.VAPOR);
    }

    /** Conditional solute factors are only used in a mixture with a supported liquid carrier. */
    public Result liquid(double temperature,double[] amounts) {
        return liquid(temperature,amounts,null);
    }
    public Result liquid(double temperature,double[] amounts,Prepared prepared) {
        checkBasis(amounts);own(prepared,temperature);
        var terms=prepared==null?prepare(temperature):prepared;
        double sum=0,logSum=0,carrier=0,conditional=0;int count=compounds.size();
        for(int i=0;i<count;i++) {
            double n=amounts[i];if(n==0)continue;sum+=n;
            if(terms.liquid(i)==Prepared.CARRIER)carrier+=n;else conditional+=n;
            logSum+=n*terms.liquidLog[i];
        }
        // Water is a carrier with its own liquid viscosity (the package's water record, held in Region 1's range).
        double water=amounts[count];
        if(water>0){sum+=water;carrier+=water;logSum+=water*DeterministicMath.log(waterLiquid(temperature,prepared));}
        if(!(sum>0) || !Double.isFinite(sum) || !(carrier>0)) {
            throw new IllegalArgumentException("Liquid mixture needs a supported carrier; no pure-liquid claim for conditional solutes");
        }
        double x=0,x2=0;
        for(int i:correctionParticipants){double v=amounts[i]/sum;x+=v;x2+=v*v;}
        return new Result(positive(DeterministicMath.exp(logSum/sum+liquidMixture.coefficient()*(x*x-x2))),conditional>0);
    }

    public double waterLiquid(double temperature) {
        return waterLiquid(temperature,null);
    }
    /**
     * Liquid water's viscosity: its correlation at the temperature held inside Region 1's liquid, from the water model's
     * triple point to 623.15 K, as the free water's other properties are ({@code WaterRegion1}; SR6's own sum diverges
     * below about 230 K, 5.3 Pa s at 200 K).
     */
    public double waterLiquid(double temperature,Prepared prepared) {
        own(prepared,temperature);
        if(prepared!=null&&!Double.isNaN(prepared.water))return prepared.water;
        var c=waterLiquidCorrelation;if(c==null)throw new IllegalArgumentException("PROPERTY_UNAVAILABLE: water liquid viscosity");
        double held=Math.min(Math.max(temperature,propertyPackage.water().triplePoint()),WaterRegion1.REGION_1_MAXIMUM_TEMPERATURE);
        double value=c.dynamicViscosityPascalSeconds(held,ViscosityCorrelation.Phase.LIQUID);
        if(prepared!=null)prepared.water=value;
        return value;
    }

    /** Per-thread scratch for {@link #vapor}: the amount vector over the basis with water, refilled
     * by the solver that owns it instead of allocated per decoded node. */
    public static final class Workspace {
        private double[] n=new double[0];
        private double[] amounts(int count) {
            if(n.length!=count)n=new double[count];
            return n;
        }
    }
    public double vapor(double temperature,double[] amounts) {
        return vapor(temperature,amounts,null,null);
    }
    /** The vapour's Wilke viscosity of conserved {@code amounts} (water last). */
    public double vapor(double temperature,double[] amounts,Workspace workspace,Prepared prepared) {
        checkBasis(amounts);own(prepared,temperature);
        var terms=prepared==null?prepare(temperature):prepared;
        int count=amounts.length;
        double[] n=amounts;
        // Pure-component viscosity and its square root depend on temperature alone; fill in index
        // order, so the first component without a correlation still refuses the same mixture.
        for(int i=0;i<count;i++)if(n[i]>0)terms.vapor(i);
        double[] mu=terms.vaporMu,sqrtMu=terms.vaporSqrt;
        double result=0;
        for(int i=0;i<count;i++) {
            if(n[i]==0)continue;
            double denominator=0;
            for(int j=0;j<count;j++)if(n[j]>0) {
                double factor=1+sqrtMu[i]/sqrtMu[j]*wilkeMassQuarter[i][j];
                denominator+=n[j]*factor*factor*wilkeDenominator[i][j];
            }
            result+=n[i]*mu[i]/denominator;
        }
        return positive(result);
    }

    private void checkBasis(double[] amounts) {
        if(amounts.length!=compounds.size()+1)throw new IllegalArgumentException("Transport basis mismatch");
        for(double n:amounts)if(!Double.isFinite(n)||n<0)throw new IllegalArgumentException("Invalid transport amount");
    }
    private static double positive(double value) {
        if(!(value>0)||!Double.isFinite(value))throw new IllegalArgumentException("Invalid viscosity result");return value;
    }
    public record Result(double pascalSeconds,boolean conditionalSoluteApproximation) {}
}
