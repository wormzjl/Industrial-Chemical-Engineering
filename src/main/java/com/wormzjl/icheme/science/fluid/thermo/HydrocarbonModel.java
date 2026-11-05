package com.wormzjl.icheme.science.fluid.thermo;

import com.wormzjl.icheme.science.math.DeterministicMath;

import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;
import com.wormzjl.icheme.science.thermo.ThermoComponent;
import com.wormzjl.icheme.science.thermo.phase.CubicPhaseEvaluator;
import com.wormzjl.icheme.science.thermo.phase.PhaseIdentification;
import com.wormzjl.icheme.science.thermo.phase.ThermoIdentity;
import com.wormzjl.icheme.science.thermo.phase.WaterParticipation;
import java.util.List;

/**
 * Fluid-specific property snapshot. Every hydrocarbon phase is the translated PR78 evaluated directly at its own
 * pressure: a liquid at the state pressure, a vapour at its hydrocarbon partial pressure; a liquid's volume, enthalpy,
 * {@code ln phi} and compressibility are the equation of state's own, and each component's volume translation is
 * anchored at its reference point. Two paths, chosen by the package: a package without a reference spine takes the
 * coefficient path (the {@code shifted_polynomial_5} ideal-gas fits, the constant interaction matrix and the global
 * calibration anchors, pinned by {@code LegacyNetworkPathPinTest}); a package that declares a {@code spine} takes the
 * spine path ({@link SpinePackageData}: the spine records as the ideal gas with each species' formation enthalpy
 * subtracted, the network's sensible datum, the E-PPR78 pair interactions, the records' own {@code volume_translation}
 * anchors, and its {@link ThermoIdentity} as the thermodynamic revision), on which a phase's volume and {@code ln phi}
 * are bit for bit those of {@code CubicPhaseEvaluator.forPackage} and its enthalpy the evaluator's minus
 * {@code sum x_i DeltafH_i}.
 */
public final class HydrocarbonModel {
    /**
     * The formulation tag this model's revision carries: liquids at the state pressure, anchors at their own
     * ({@code direct-liquid}), and the network's phases decided by the equilibrium engine with the phase identification
     * parameter as the one liquid/vapour rule ({@code tp-engine}: the Wilson-started flash and the root heuristic
     * retired), and every property continued past its data range ({@code range-continuation}: owner rule of 2026-10-16).
     * Both paths' revisions carry it, so a change here is a fresh world.
     */
    public static final String FORMULATION="direct-liquid-v1:tp-engine-v1:range-continuation-v1";
    /**
     * The datum and anchor rule of a spine package: each spine's formation enthalpy subtracted per species (D10, so a
     * species' ideal-gas enthalpy is 0 at 298.15 K as on the coefficient path), and a record's {@code volume_translation}
     * anchoring its species before the global calibration point.
     */
    public static final String SPINE_DATUM="spine-sensible-datum-v1";
    /**
     * The network's formulation of a spine package, the {@code evaluatorFamily} part of its {@link ThermoIdentity}: the
     * engine's residual family ({@link CubicPhaseEvaluator#FAMILY}), this model's direct liquid path ({@link #FORMULATION})
     * and {@link #SPINE_DATUM}. It differs from the engine's own family because the network's energies are on another
     * datum and its liquid slot follows its own root rule.
     */
    public static final String NETWORK_FAMILY=CubicPhaseEvaluator.FAMILY+":"+FORMULATION+":"+SPINE_DATUM;
    private final MaterialCatalog.Package propertyPackage;
    private final TranslatedPengRobinson translated;
    private final String revision;
    /** Every temperature and pressure range comes from the package's data; see {@link FluidDomain}. */
    private final FluidDomain domain;
    /** The resolved spine inputs; {@code null} for a package without a spine (the coefficient path). */
    private final SpinePackageData spine;

    public HydrocarbonModel(MaterialCatalog catalog,String packageId) {
        propertyPackage=catalog.requirePackage(packageId);
        domain=FluidDomain.of(catalog,packageId);
        if(!propertyPackage.spine().isEmpty()) {
            // The spine path: spine ideal gas on the sensible datum, E-PPR78 pairs, one anchor per species.
            var data=SpinePackageData.of(catalog,packageId);
            spine=data;
            translated=new TranslatedPengRobinson(data.components(),data.interactions(),data.idealGas(),
                    data.formationEnthalpies(),data.translations());
            for(int i=0;i<data.anchors().size();i++)
                requireLiquidRoot(packageId,data.components().get(i),i,data.anchors().get(i).temperature(),data.anchors().get(i).pressure());
            revision=ThermoIdentity.of(catalog,packageId,NETWORK_FAMILY,catalog.spineRevision(packageId),WaterParticipation.SEPARATE_FREE_WATER).revision();
            return;
        }
        spine=null;
        List<ThermoComponent> components=propertyPackage.properties().stream().map(p->new ThermoComponent(
                p.component(),p.pr().criticalTemperature(),p.pr().criticalPressure(),p.pr().acentricFactor(),p.molecularWeight())).toList();
        int count=components.size();
        double[][] interactions=new double[count][count], cp=new double[count][6];
        double[] fitMinimum=new double[count],fitMaximum=new double[count];
        for(int i=0;i<count;i++) {
            for(int j=0;j<count;j++) interactions[i][j]=propertyPackage.interactions().get(i).get(j);
            var property=propertyPackage.properties().get(i);
            for(int j=0;j<6;j++) cp[i][j]=property.cp().get(j);
            double[] fit=CoefficientIdealGas.fitRange(catalog,packageId,property);
            fitMinimum[i]=fit[0];fitMaximum[i]=fit[1];
        }
        var calibrations=catalog.fluidData().volumeReferences();
        double[] shifts=new double[count],anchorT=new double[count],anchorP=new double[count];
        for(int i=0;i<count;i++) {
            var property=propertyPackage.properties().get(i);
            var point=calibrations.get(property.component());
            double t=point==null?property.standardTemperature():point.temperatureKelvin();
            double targetVolume=point==null?property.molecularWeight()/property.density():point.molarVolumeCubicMetres();
            double referenceP=point==null?property.standardPressure():point.pressurePascal();
            // The translation makes the model's liquid volume at the anchor's own (T, P) the target: the untranslated
            // liquid root there, with no compressibility carrying the target to another pressure.
            double rawVolume=pureLiquidCompressibility(components.get(i),interactions[i][i],t,referenceP)
                    *FluidThermodynamics.R*t/referenceP;
            shifts[i]=targetVolume-rawVolume;
            anchorT[i]=t;anchorP[i]=referenceP;
        }
        translated=new TranslatedPengRobinson(components,interactions,cp,shifts,fitMinimum,fitMaximum);
        // An anchor is a liquid volume, so the equation of state must have a liquid root at the anchor's state: a
        // single vapour-like root there would translate the component by a gas volume.
        for(int i=0;i<count;i++)requireLiquidRoot(packageId,components.get(i),i,anchorT[i],anchorP[i]);
        // The free water's formulation and revision, as a spine package's identity carries them (WP4: its ice).
        // A package that admits crystals adds their records' revisions (MaterialCatalog.spineRevision), as a spine package's
        // identity does (E27); a view without them keeps its revision.
        String crystals=propertyPackage.crystals().isEmpty()?"":":"+catalog.spineRevision(packageId);
        revision=catalog.fluidRevision(packageId)+":"+FORMULATION+":"+ThermoIdentity.SEPARATE_FREE_WATER_FORMULATION+":"+propertyPackage.water().revision()
                +":fluid-domain-data-v1:catalog-volume-reference-v1"+crystals;
    }

    /**
     * The phase engine's evaluator of this model's coefficient path (a package without a reference spine): the package's
     * PR78 constants and constant interactions over its whole basis in package order, this model's translations and the
     * {@link CoefficientIdealGas} fits over this model's own ranges, so volume and {@code ln phi} are bit for bit this
     * model's and the ideal gas is on its datum. A spine package's evaluator is {@link CubicPhaseEvaluator#forPackage}.
     */
    public CubicPhaseEvaluator coefficientEvaluator() {
        if(spine!=null)throw new IllegalStateException("Package "+propertyPackage.id()+" takes its ideal gas from its reference spine");
        int n=propertyPackage.components().size();double[] translations=new double[n];
        var gas=new java.util.ArrayList<com.wormzjl.icheme.science.thermo.IdealGasFunction>(n);
        for(int i=0;i<n;i++) {
            translations[i]=translated.translation(i);
            gas.add(new CoefficientIdealGas(propertyPackage.properties().get(i),translated.idealGasMinimumTemperature(i),translated.idealGasMaximumTemperature(i)));
        }
        return CubicPhaseEvaluator.fromPackage(propertyPackage,propertyPackage.components(),translations,gas);
    }

    /** A liquid root of pure component {@code i} at its volume anchor's own state ({@code t}, {@code p}), on either path. */
    private void requireLiquidRoot(String packageId,ThermoComponent component,int i,double t,double p) {
        double[] pure=new double[translated.kernel().componentCount()];pure[i]=1;
        var values=translated.evaluateValues(t,p,pure,PengRobinsonKernel.Root.LIQUID,translated.prepare(t));
        if(values.physicalRootCount()==1&&!liquidLike(values))
            throw new IllegalArgumentException("Package "+packageId+": component "+component.id()+" has no liquid root at its volume anchor "
                    +t+" K, "+p+" Pa; the translation needs a liquid there");
    }

    /**
     * The network's thermodynamic revision. For a package without a reference spine it is the coefficient path's
     * string (the package data's declared revisions, the formulation tags and the free-water revision); for a spine package it
     * is its {@link ThermoIdentity}'s revision: the package data's declared revisions, the {@link #NETWORK_FAMILY}, the spine
     * revision and the free-water revision.
     */
    public String revision() { return revision; }
    /** The resolved spine inputs; empty for a package without a spine. */
    public java.util.Optional<SpinePackageData> spine() { return java.util.Optional.ofNullable(spine); }
    /** The package's data ranges ({@link FluidDomain}). */
    public FluidDomain domain() { return domain; }
    public int componentCount() { return propertyPackage.components().size(); }
    public List<String> components() { return propertyPackage.components(); }
    public double molecularWeight(int i) { return propertyPackage.properties().get(i).molecularWeight(); }
    public MaterialCatalog.Package propertyPackage() { return propertyPackage; }

    public Phase phase(double t,double p,double[] amounts,PengRobinsonKernel.Root root) {
        return phase(t,p,amounts,root,translated.prepare(t));
    }
    public TranslatedPengRobinson.Workspace prepare(double t){return translated.prepare(t);}
    /** The translated EOS this model evaluates: the network's phase engine takes its translations, and the flash adapter
     * labels a one-root phase by its phase identification parameter. */
    TranslatedPengRobinson translated(){return translated;}
    /** {@link #phase(double, double, double[], PengRobinsonKernel.Root, TranslatedPengRobinson.Workspace, boolean)} of a
     * phase with no vapour beside it: a liquid on a single vapour-like root is accepted. */
    public Phase phase(double t,double p,double[] amounts,PengRobinsonKernel.Root root,TranslatedPengRobinson.Workspace terms) {
        return phase(t,p,amounts,root,terms,false);
    }
    /**
     * One hydrocarbon phase at {@code p}: the state pressure for a liquid, the hydrocarbon partial pressure for a vapour.
     * The slot's root must exist, of the slot's kind, or the evaluation is refused with the typed {@link RootAbsent} (an
     * {@link IllegalArgumentException}: the line search backtracks, and for a liquid slot the outer check re-decides the
     * regime). A root is absent where floating point loses the cubic's physical root
     * ({@link PengRobinsonKernel.NoPhysicalRoot}: a trial far outside any physical state, the coefficients overflowing at
     * 1.5e292 Pa or the root's distance above the co-volume below one ulp of it at 6.6e25 Pa), and where at the state
     * pressure the cubic has one physical root that is vapour-like ({@link PhaseIdentification#vapourLike(double, double)}):
     * the liquid root is then absent if the phase's node also carries a hydrocarbon vapour ({@code vaporCoexists}), since
     * the two slots would evaluate the same kind of root; in a liquid-only node the single root is the same fluid
     * continuing and is accepted.
     */
    public Phase phase(double t,double p,double[] amounts,PengRobinsonKernel.Root root,TranslatedPengRobinson.Workspace terms,boolean vaporCoexists) {
        if(amounts.length!=componentCount())throw new IllegalArgumentException("Hydrocarbon basis mismatch");
        if(!Double.isFinite(t)||!Double.isFinite(p))throw new IllegalArgumentException("Fluid hydrocarbon state is not finite");
        // No data range bounds a phase (owner rule of 2026-10-16); the state that carries it is inside the numerical box,
        // its vapour's partial pressure too (NumericalBox.requirePartialPressure).
        TranslatedPengRobinson.Values values;
        try{values=translated.evaluateValues(t,p,amounts,root,terms);}
        catch(PengRobinsonKernel.NoPhysicalRoot lost){throw RootAbsent.lost(root,t,p,lost);}
        if(vaporCoexists&&root==PengRobinsonKernel.Root.LIQUID&&values.physicalRootCount()==1&&!liquidLike(values))throw RootAbsent.liquidBesideVapour(t,p);
        return new Phase(values.molarVolume(),values.molarEnthalpy(),values.molarInternalEnergy(),
                values.volumePressureDerivative(),values.logFugacityCoefficientsView().clone(),
                root==PengRobinsonKernel.Root.VAPOR&&PhaseIdentification.vapourLike(values.phaseIdentificationParameter(),values.compressibility()));
    }

    /**
     * Whether an evaluated root is liquid-like: {@link PhaseIdentification}'s parameter of the root above one and above the
     * root's compressibility factor ({@link PhaseIdentification#liquidLike(double, double)}; the one implementation of
     * the network's liquid/vapour rule, with the compressibility guard, since a hot dilute gas has a parameter just above
     * one).
     */
    static boolean liquidLike(TranslatedPengRobinson.Values values) {
        return PhaseIdentification.liquidLike(values.phaseIdentificationParameter(),values.compressibility());
    }

    /**
     * A trial phase slot whose equation of state has no root of the slot's kind ({@link #phase}): the numerical guard "no
     * equation-of-state root" of the owner rule of 2026-10-16. It is an {@link IllegalArgumentException}, so a Newton trial
     * or a Jacobian column that meets it is refused like any unevaluable trial and the step backtracks; it is not a
     * {@link ThermoHold}, because only the trial is wrong.
     */
    public static final class RootAbsent extends IllegalArgumentException {
        private RootAbsent(String message,Throwable cause){super(message,cause);}
        /** Floating point lost the cubic's physical root at this trial ({@link PengRobinsonKernel.NoPhysicalRoot}). */
        static RootAbsent lost(PengRobinsonKernel.Root root,double temperature,double pressure,PengRobinsonKernel.NoPhysicalRoot cause) {
            return new RootAbsent("Root absent: the hydrocarbon "+(root==PengRobinsonKernel.Root.LIQUID?"liquid":"vapour")
                    +"'s cubic has no physical root in floating point at "+temperature+" K, "+pressure+" Pa",cause);
        }
        /** A liquid slot whose node also carries a hydrocarbon vapour, on a single vapour-like root. */
        static RootAbsent liquidBesideVapour(double temperature,double pressure) {
            return new RootAbsent("Liquid root absent: the hydrocarbon liquid of a two-phase node has only a vapour-like root at "
                    +temperature+" K, "+pressure+" Pa",null);
        }
    }

    /**
     * The coefficients belong to the record from construction on. {@link #phase} is the only place one is built, from a
     * copy of the evaluation's buffer. {@code vaporLike} says a vapour evaluation's root is vapour-like by the phase
     * identification parameter ({@link PhaseIdentification#vapourLike(double, double)}, at most one or at most its
     * {@code Z}; false for a liquid).
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
     * The liquid-root compressibility factor of pure component {@code c} at {@code t} and {@code p} on the Peng-Robinson
     * 1978 cubic with classical quadratic mixing, written operation for operation as the reference {@code PengRobinson78}
     * (test tree, pinned by {@code PengRobinson78Test}) evaluates a pure composition: for one component the mixing rules
     * give {@code a_mix = sqrt(a a) (1 - k_cc)} and {@code b_mix = b} exactly, so the result is the reference's bit for bit.
     */
    static double pureLiquidCompressibility(ThermoComponent c,double kcc,double t,double p) {
        if(!Double.isFinite(t)||t<=0.0||!Double.isFinite(p)||p<=0.0)throw new IllegalArgumentException("Temperature and pressure must be finite and positive");
        double r=FluidThermodynamics.R,tc=c.criticalTemperatureKelvin(),pc=c.criticalPressurePascal(),omega=c.acentricFactor();
        double criticalA=0.45724*square(r)*square(tc)/pc;
        double coVolume=0.07780*r*tc/pc;
        double kappa=omega<=0.491?0.37464+1.54226*omega-0.26992*square(omega)
                :0.379642+1.48503*omega-0.164423*square(omega)+0.016666*omega*square(omega);
        double sqrtReducedTemperature=Math.sqrt(t/tc);
        double alphaTerm=1.0+kappa*(1.0-sqrtReducedTemperature);
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
    /** The real roots of z^3 + c2 z^2 + c1 z + c0 = 0, as {@code PengRobinson78} finds them. */
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
