package com.wormzjl.icheme.science.fluid.thermo;

import com.wormzjl.icheme.science.math.DeterministicMath;

import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.thermo.IdealGasFunction;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;
import com.wormzjl.icheme.science.thermo.ThermoComponent;
import com.wormzjl.icheme.science.thermo.phase.CubicPhaseEvaluator;
import com.wormzjl.icheme.science.thermo.phase.PhaseContract;
import com.wormzjl.icheme.science.thermo.phase.PhaseIdentification;
import com.wormzjl.icheme.science.thermo.phase.WaterShare;
import java.util.ArrayList;
import java.util.List;

/**
 * The fluid network's phase model (batch 2026-11-11-aqueous-solvents, W3b step 3): the derived equation of state of the
 * package ({@link CubicPhaseEvaluator#derived}: PR78 with the Huron-Vidal rule of {@code DerivedPairs}, water a component
 * of the cubic with its Mathias-Copeman alpha), the same one the still and the column use. Every fluid phase of a node,
 * the vapour and up to two liquids, is this cubic evaluated at the state pressure on its own root; the phase's volume and
 * enthalpy are the balance book (the cubic plus water's IF97 share, {@link WaterShare}), its {@code ln phi} the
 * equilibrium book (the cubic alone). One model per compound list: water is in the kernel only on a list that carries it
 * (an island that can hold water, option A of 2026-11-11), last; the conserved basis is always the compounds and then
 * water, so a dry model's phases hold no water and its water {@code ln phi} is not a number.
 *
 * <p>The ideal gas: on a package with a reference spine the spine records with each species' formation enthalpy
 * subtracted (the network's sensible datum, as before), on any other package the {@code shifted_polynomial_5} fits on
 * that datum ({@link CoefficientIdealGas}); the engine's evaluator is the same kernel and translations on the spine's own
 * datum, which {@code FluidThermodynamics.engineEnergy} bridges.</p>
 */
public final class HydrocarbonModel {
    /**
     * The formulation tag this model's revision carries: phases of the derived cubic at the state pressure, water among
     * its components ({@code derived-network}), the phases decided by the equilibrium engine ({@code tp-engine}), every
     * property continued past its data ({@code range-continuation}), and up to three fluid phases. A change here is a
     * fresh state basis (the engine recomputes energies).
     */
    public static final String FORMULATION="derived-network-v1:tp-engine-v1:range-continuation-v1:three-fluid-phases-v1";
    private final MaterialCatalog.Package propertyPackage;
    private final CubicPhaseEvaluator evaluator;
    private final TranslatedPengRobinson translated;
    private final List<String> species,compounds;
    /** Water's index in the kernel's basis (the last), or -1 on a list without water. */
    private final int water;
    private final WaterShare share;
    private final ThreadLocal<WaterShare.Workspace> shareScratch;
    private final String revision;
    private final FluidDomain domain;
    private final SpinePackageData spine;
    private final double[] molecularWeights;

    public HydrocarbonModel(MaterialCatalog catalog,String packageId) {
        propertyPackage=catalog.requirePackage(packageId);
        domain=FluidDomain.of(catalog,packageId);
        species=PhaseContract.derivedBasis(catalog,packageId);
        evaluator=CubicPhaseEvaluator.derived(catalog,packageId,species);
        water=evaluator.waterComponent();
        var list=new ArrayList<>(species);list.remove(CubicPhaseEvaluator.WATER);compounds=List.copyOf(list);
        int n=species.size();
        List<ThermoComponent> components=new ArrayList<>(n);
        for(int i=0;i<n;i++)components.add(evaluator.component(i));
        spine=propertyPackage.spine().isEmpty()?null:SpinePackageData.of(catalog,packageId);
        List<IdealGasFunction> gas=new ArrayList<>(n);double[] offsets=new double[n];
        double[] formation=spine==null?null:spine.formationEnthalpies();
        for(int i=0;i<n;i++) {
            gas.add(evaluator.idealGas(i));
            if(spine!=null)offsets[i]=formation[propertyPackage.components().indexOf(species.get(i))];
        }
        translated=new TranslatedPengRobinson(components,evaluator.kernel(),gas,offsets,evaluator.translations());
        if(water>=0) {
            var derivation=evaluator.derivation();
            share=new WaterShare(derivation.criticalTemperatures()[water],derivation.criticalPressures()[water],
                    derivation.acentricFactors()[water],derivation.mathiasCopeman()[water]);
            shareScratch=ThreadLocal.withInitial(share::newWorkspace);
        } else {share=null;shareScratch=null;}
        molecularWeights=new double[compounds.size()+1];
        for(int i=0;i<compounds.size();i++)molecularWeights[i]=propertyPackage.properties().get(propertyPackage.components().indexOf(compounds.get(i))).molecularWeight();
        int packageWater=propertyPackage.components().indexOf(CubicPhaseEvaluator.WATER);
        molecularWeights[compounds.size()]=packageWater>=0?propertyPackage.properties().get(packageWater).molecularWeight():propertyPackage.water().molarMass();
        revision=PhaseContract.forDerived(catalog,packageId,evaluator).identity().revision()+":"+FORMULATION;
    }

    /** The engine's evaluator of this model: the same kernel and translations ({@code NetworkPhaseEngine}). */
    public CubicPhaseEvaluator evaluator() { return evaluator; }
    /** The model's revision: the derived contract's identity (package data, derivation, family, spine, water) and {@link #FORMULATION}. */
    public String revision() { return revision; }
    /** The resolved spine inputs; empty for a package without a spine. */
    public java.util.Optional<SpinePackageData> spine() { return java.util.Optional.ofNullable(spine); }
    /** The package's data ranges ({@link FluidDomain}). */
    public FluidDomain domain() { return domain; }
    /** The compounds of this model's list, water not among them: the conserved basis less its last entry. */
    public List<String> components() { return compounds; }
    public int componentCount() { return compounds.size(); }
    /** The critical temperature (K) of conserved component {@code i} (water last): the derivation's, the package's water
     * record's for water on a list whose cubic does not carry it. */
    public double criticalTemperature(int i) {
        if(i<compounds.size()||water>=0)return evaluator.derivation().criticalTemperatures()[i];
        return propertyPackage.water().criticalTemperature();
    }
    /** The kernel's basis: the compounds, then water where the list carries it. */
    public List<String> species() { return species; }
    /** Whether water is a component of this model's cubic (option A: an island that can hold water). */
    public boolean wet() { return water>=0; }
    /** Molecular weight, kg/mol, on the conserved basis (water last). */
    public double molecularWeight(int i) { return molecularWeights[i]; }
    public MaterialCatalog.Package propertyPackage() { return propertyPackage; }
    public TranslatedPengRobinson.Workspace prepare(double t){return translated.prepare(t);}
    /** The translated EOS this model evaluates. */
    TranslatedPengRobinson translated(){return translated;}
    /** Conserved component {@code i}'s ideal-gas enthalpy, J/mol, on the model's datum (water last; a wet list only for water). */
    public double idealGasEnthalpy(int i,double t){return translated.idealGasEnthalpy(kernelIndex(i),t);}
    /** Conserved component {@code i}'s ideal-gas heat capacity, J/(mol K). */
    public double idealGasHeatCapacity(int i,double t){return translated.idealGasHeatCapacity(kernelIndex(i),t);}
    private int kernelIndex(int i){if(i==compounds.size()&&water<0)throw new IllegalArgumentException("Water on a list without water");return i;}

    /** The kernel's amounts of conserved {@code amounts} (water last): the array itself on a wet list; on a dry one its compounds, water refused. */
    double[] kernelAmounts(double[] amounts) {
        if(amounts.length!=compounds.size()+1)throw new IllegalArgumentException("Fluid basis mismatch");
        if(water>=0)return amounts;
        if(amounts[compounds.size()]!=0)throw new IllegalArgumentException("Water in a phase of a list without water (package "+propertyPackage.id()+")");
        return java.util.Arrays.copyOf(amounts,compounds.size());
    }

    public Phase phase(double t,double p,double[] amounts,PengRobinsonKernel.Root root) {
        return phase(t,p,amounts,root,translated.prepare(t),false);
    }
    /**
     * One fluid phase of conserved {@code amounts} (water last) at the state's {@code t} and {@code p} on {@code root}: the
     * vapour slot on the vapour root, a liquid slot on the liquid root. The slot's root must exist, of the slot's kind, or
     * the evaluation is refused with the typed {@link RootAbsent} (an {@link IllegalArgumentException}: the line search
     * backtracks). A root is absent where floating point loses the cubic's physical root
     * ({@link PengRobinsonKernel.NoPhysicalRoot}) and where at the state pressure the cubic has one physical root that is
     * vapour-like while the node also carries a vapour ({@code vaporCoexists}): the two slots would evaluate the same kind
     * of root. In a node with no vapour the single root is the same fluid continuing and is accepted.
     */
    public Phase phase(double t,double p,double[] amounts,PengRobinsonKernel.Root root,TranslatedPengRobinson.Workspace terms,boolean vaporCoexists) {
        if(!Double.isFinite(t)||!Double.isFinite(p))throw new IllegalArgumentException("Fluid state is not finite");
        double[] kernel=kernelAmounts(amounts);
        TranslatedPengRobinson.Values values;
        try{values=translated.evaluateValues(t,p,kernel,root,terms);}
        catch(PengRobinsonKernel.NoPhysicalRoot lost){throw RootAbsent.lost(root,t,p,lost);}
        if(vaporCoexists&&root==PengRobinsonKernel.Root.LIQUID&&values.physicalRootCount()==1&&!liquidLike(values))throw RootAbsent.liquidBesideVapour(t,p);
        double v=values.molarVolume(),h=values.molarEnthalpy(),dvdp=values.volumePressureDerivative();
        if(water>=0&&kernel[water]>0) {
            double total=0;for(double x:kernel)total+=x;
            double[] out=new double[WaterShare.SIZE];
            share.phase(t,p,kernel[water]/total,values.untranslatedVolume(),values.coVolume(),values.volumeTemperatureDerivative(),dvdp,shareScratch.get(),out);
            v+=out[WaterShare.V];h+=out[WaterShare.H];dvdp+=out[WaterShare.V_P];
        }
        double[] logPhi=new double[compounds.size()+1];
        System.arraycopy(values.logFugacityCoefficientsView(),0,logPhi,0,kernel.length);
        if(water<0)logPhi[compounds.size()]=Double.NaN;
        return new Phase(v,h,h-p*v,dvdp,logPhi,root==PengRobinsonKernel.Root.VAPOR&&PhaseIdentification.vapourLike(values.phaseIdentificationParameter(),values.compressibility()));
    }

    /**
     * Whether an evaluated root is liquid-like: {@link PhaseIdentification}'s parameter of the root above one and above the
     * root's compressibility factor ({@link PhaseIdentification#liquidLike(double, double)}).
     */
    static boolean liquidLike(TranslatedPengRobinson.Values values) {
        return PhaseIdentification.liquidLike(values.phaseIdentificationParameter(),values.compressibility());
    }
    /** Whether conserved {@code amounts} on one root at {@code (t, p)} are liquid-like ({@link #liquidLike(TranslatedPengRobinson.Values)}). */
    boolean liquidLike(double t,double p,double[] amounts,PengRobinsonKernel.Root root,TranslatedPengRobinson.Workspace terms) {
        return liquidLike(translated.evaluateValues(t,p,kernelAmounts(amounts),root,terms));
    }

    /**
     * A trial phase slot whose equation of state has no root of the slot's kind ({@link #phase}): the numerical guard "no
     * equation-of-state root" of the owner rule of 2026-10-16. It is an {@link IllegalArgumentException}, so a Newton trial
     * or a Jacobian column that meets it is refused like any unevaluable trial and the step backtracks; it is not a
     * {@link ThermoHold}, because only the trial is wrong.
     */
    public static final class RootAbsent extends IllegalArgumentException {
        private RootAbsent(String message,Throwable cause){super(message,cause);}
        static RootAbsent lost(PengRobinsonKernel.Root root,double temperature,double pressure,PengRobinsonKernel.NoPhysicalRoot cause) {
            return new RootAbsent("Root absent: the "+(root==PengRobinsonKernel.Root.LIQUID?"liquid":"vapour")
                    +"'s cubic has no physical root in floating point at "+temperature+" K, "+pressure+" Pa",cause);
        }
        static RootAbsent liquidBesideVapour(double temperature,double pressure) {
            return new RootAbsent("Liquid root absent: a liquid of a node with a vapour has only a vapour-like root at "
                    +temperature+" K, "+pressure+" Pa",null);
        }
    }

    /**
     * One phase's molar properties on the balance book (volume and enthalpy with water's share) and its {@code ln phi} on
     * the conserved basis (water last; not a number on a list without water). {@code vaporLike}: a vapour evaluation's
     * root is vapour-like by the phase identification parameter.
     */
    public record Phase(double molarVolume,double molarEnthalpy,double molarInternalEnergy,
                        double volumePressureDerivative,double[] logFugacity,boolean vaporLike) {
        @Override public double[] logFugacity() { return logFugacity.clone(); }
        /** The coefficients themselves, for the solver packages. The caller must not mutate them. */
        public double[] logFugacityView() { return logFugacity; }
        /** {@code -(dv/dP)/v}: the phase's own isothermal compressibility, 1/Pa. */
        public double isothermalCompressibility() { return -volumePressureDerivative/molarVolume; }
    }

    /**
     * The translations of a package's coefficient evaluator ({@link CubicPhaseEvaluator#forPackage} on a package without a
     * spine, outside the network since W3b): each component's untranslated pure liquid on the package's constant matrix at
     * its volume anchor's own (T, P) moved to the anchor's volume.
     */
    public static double[] coefficientTranslations(MaterialCatalog catalog,String packageId) {
        var pack=catalog.requirePackage(packageId);int count=pack.components().size();
        var calibrations=catalog.fluidData().volumeReferences();double[] shifts=new double[count];
        for(int i=0;i<count;i++) {
            var property=pack.properties().get(i);var point=calibrations.get(property.component());
            var component=new ThermoComponent(property.component(),property.pr().criticalTemperature(),property.pr().criticalPressure(),property.pr().acentricFactor(),property.molecularWeight());
            double t=point==null?property.standardTemperature():point.temperatureKelvin();
            double targetVolume=point==null?property.molecularWeight()/property.density():point.molarVolumeCubicMetres();
            double referenceP=point==null?property.standardPressure():point.pressurePascal();
            shifts[i]=targetVolume-pureLiquidCompressibility(component,pack.interactions().get(i).get(i),t,referenceP)*FluidThermodynamics.R*t/referenceP;
        }
        return shifts;
    }
    /** The liquid-root compressibility factor of pure {@code c} on PR78 with classical mixing (as {@code PengRobinson78}). */
    static double pureLiquidCompressibility(ThermoComponent c,double kcc,double t,double p) {
        if(!Double.isFinite(t)||t<=0.0||!Double.isFinite(p)||p<=0.0)throw new IllegalArgumentException("Temperature and pressure must be finite and positive");
        double r=FluidThermodynamics.R,tc=c.criticalTemperatureKelvin(),pc=c.criticalPressurePascal(),omega=c.acentricFactor();
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
