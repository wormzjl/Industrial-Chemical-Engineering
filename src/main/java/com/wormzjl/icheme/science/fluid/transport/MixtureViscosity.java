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

    public MixtureViscosity(MaterialCatalog catalog,String packageId) {
        propertyPackage=catalog.requirePackage(packageId);
        conditional=catalog.fluidData().conditionalLiquidViscosities();
        liquidMixture=catalog.liquidMixture(packageId);
        // The declared revisions of the data (the package's records, its water, the fluid reference data) and the
        // formulation labels; nothing is hashed (owner decision 2026-10-26).
        // W3b (owner, 2026-11-11): one log-mixing rule over a liquid's components on the conserved basis, water last with its
        // own liquid viscosity; the vapour's Wilke rule over the same basis.
        revision=catalog.fluidRevision(packageId)+":log-liquid-pair-groups-wilke-v2:catalog-conditional-solute-v1:supercritical-liquid-slot-dilute-v1:range-continuation-v1:water-in-liquid-log-v1";
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
        wilkeMassQuarter=new double[count+1][count+1];wilkeDenominator=new double[count+1][count+1];
        for(int i=0;i<=count;i++)for(int j=0;j<=count;j++){wilkeMassQuarter[i][j]=Math.sqrt(Math.sqrt(mw[j]/mw[i]));wilkeDenominator[i][j]=1/Math.sqrt(8*(1+mw[i]/mw[j]));}
    }
    public String revision() {
        return revision;
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
