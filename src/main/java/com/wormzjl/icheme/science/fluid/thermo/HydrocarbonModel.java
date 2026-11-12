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
    private final ThreadLocal<TangentScratch> tangentScratch;
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
        tangentScratch=ThreadLocal.withInitial(()->new TangentScratch(evaluator.kernel()));
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

    /**
     * The compounds {@code terms}' evaluations carry in the kernel ({@link TranslatedPengRobinson.Workspace#carry}):
     * those nonzero in conserved {@code support} (water last, dropped on a list without water), or the whole list for
     * null. Set at every use by the caller that knows what it reads ({@code FluidThermodynamics.state}).
     */
    public void carry(TranslatedPengRobinson.Workspace terms,double[] support) {
        if(support==null){terms.carryAll();return;}
        if(support.length!=compounds.size()+1)throw new IllegalArgumentException("Fluid basis mismatch");
        terms.carry(water>=0?support:java.util.Arrays.copyOf(support,compounds.size()));
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
        return phase(t,p,kernel,root,values(t,p,kernel,root,terms,vaporCoexists));
    }
    /** The slot's root of the kernel's {@code amounts}, or the typed refusal of {@link #phase}. */
    private TranslatedPengRobinson.Values values(double t,double p,double[] kernel,PengRobinsonKernel.Root root,TranslatedPengRobinson.Workspace terms,boolean vaporCoexists) {
        TranslatedPengRobinson.Values values;
        try{values=translated.evaluateValues(t,p,kernel,root,terms);}
        catch(PengRobinsonKernel.NoPhysicalRoot lost){throw RootAbsent.lost(root,t,p,lost);}
        if(vaporCoexists&&root==PengRobinsonKernel.Root.LIQUID&&values.physicalRootCount()==1&&!liquidLike(values))throw RootAbsent.liquidBesideVapour(t,p);
        return values;
    }
    private Phase phase(double t,double p,double[] kernel,PengRobinsonKernel.Root root,TranslatedPengRobinson.Values values) {
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
        return new Phase(v,h,h-p*v,dvdp,logPhi,root==PengRobinsonKernel.Root.VAPOR&&PhaseIdentification.vapourLike(values.phaseIdentificationParameter(),values.compressibility()),
                values.supportSnapshot());
    }

    /**
     * The first-order model of one phase about a base point (ANALYTIC_JACOBIAN_PLAN.md section 3.1): the base
     * {@link #phase} and, from one derivative evaluation of the kernel on the same root, the derivatives of the kernel
     * part of its volume, enthalpy and {@code ln phi} in temperature, pressure and every amount. Null where the root is
     * too close to coalescence to differentiate (the kernel's guard): the caller evaluates that phase exactly. The
     * refusals of {@link #phase} are this entry's too.
     */
    public Tangent tangent(double t,double p,double[] amounts,PengRobinsonKernel.Root root,TranslatedPengRobinson.Workspace terms,boolean vaporCoexists) {
        if(!Double.isFinite(t)||!Double.isFinite(p))throw new IllegalArgumentException("Fluid state is not finite");
        double[] kernel=kernelAmounts(amounts);int n=kernel.length;
        TranslatedPengRobinson.Values values=values(t,p,kernel,root,terms,vaporCoexists);
        Phase base=phase(t,p,kernel,root,values);
        double total=0;for(double x:kernel)total+=x;
        double rt=FluidThermodynamics.R*t;
        // The kernel part: the translated cubic and the ideal gas, without water's share.
        double v=values.molarVolume(),h=values.molarEnthalpy(),dvdt=values.volumeTemperatureDerivative(),dvdp=values.volumePressureDerivative();
        double cp=values.heatCapacity(),dhdp=values.enthalpyPressureDerivative(),raw=values.untranslatedVolume(),b=values.coVolume();
        double attraction=values.attraction(),attractionT=values.attractionTemperatureDerivative();
        double[] attractionN=new double[n];
        for(int i=0;i<n;i++)attractionN[i]=values.attractionAmountDerivative(i)/total;
        double[] partialVolume=values.partialMolarVolumesView().clone();
        double[] logPhi=java.util.Arrays.copyOf(values.logFugacityCoefficientsView(),n);
        TangentScratch scratch=tangentScratch.get();
        // The same root on the kernel's own workspace, on the same support: d ln phi/dn per mole of phase, the untranslated d ln phi/dT.
        terms.carryOnto(scratch.mixture);
        try{evaluator.kernel().evaluateDerivatives(t,p,kernel,root,scratch.mixture,scratch.derivatives);}
        catch(IllegalStateException coalescing){return null;}
        double[] kernelDt=scratch.derivatives.dLogPhiDtView(),residual=scratch.derivatives.partialMolarResidualEnthalpyView();
        double[] logPhiT=new double[n],logPhiP=new double[n],partialEnthalpy=new double[n],rawPartialVolume=new double[n],coVolumes=new double[n];
        double[][] logPhiN=new double[n][n];
        double outside=PengRobinsonKernel.outsideSupportFill();
        for(int i=0;i<n;i++) {
            coVolumes[i]=evaluator.kernel().coVolume(i);
            if(!terms.carries(i)) {
                // A compound the support does not carry: the kernel's fill in every derivative.
                logPhiT[i]=logPhiP[i]=partialEnthalpy[i]=rawPartialVolume[i]=outside;
                java.util.Arrays.fill(logPhiN[i],outside);
                continue;
            }
            double c=translated.translation(i);
            // ln phi_i carries +P c_i/(RT) from the translation; h carries +P c_i.
            logPhiT[i]=kernelDt[i]-p*c/(rt*t);
            logPhiP[i]=partialVolume[i]/rt-1/p;
            partialEnthalpy[i]=terms.idealGasEnthalpy(i)+residual[i]+p*c;
            rawPartialVolume[i]=partialVolume[i]-c;
            double[] row=scratch.derivatives.dLogPhiDnRowView(i);
            for(int j=0;j<n;j++)logPhiN[i][j]=row[j]/total;
            if(!Double.isFinite(logPhiT[i])||!Double.isFinite(logPhiP[i])||!Double.isFinite(partialEnthalpy[i]))throw new IllegalArgumentException("Nonfinite chemical-potential derivative");
        }
        return new Tangent(this,base,t,p,kernel.clone(),total,v,h,raw,b,total*dvdt,total*dvdp,total*cp,total*dhdp,dvdt,dvdp,attraction,attractionT,attractionN,
                partialVolume,partialEnthalpy,rawPartialVolume,coVolumes,logPhi,logPhiT,logPhiP,logPhiN);
    }

    /** One thread's scratch for {@link #tangent}: the kernel's own workspace and derivative bundle. */
    private static final class TangentScratch {
        final PengRobinsonKernel.Workspace mixture;final PengRobinsonKernel.Derivatives derivatives;
        TangentScratch(PengRobinsonKernel kernel){mixture=kernel.newWorkspace();derivatives=kernel.newDerivativesWithoutHeatCapacity();}
    }

    /**
     * One phase about its base point {@code (T0, P0, n0)}, to first order ({@link #tangent}). {@link #at} gives the phase
     * at a nearby point: the kernel part of its volume and enthalpy from the extensive derivatives
     * ({@code V = N0 v0 + N0 v_T dT + N0 v_P dP + sum vbar_i dn_i}, the enthalpy the same with {@code c_p}, {@code h_P}
     * and {@code hbar_i}), {@code ln phi} from its three derivative blocks, and water's share, a closed form with no
     * kernel call, exactly at the nearby point's temperature, pressure, water fraction, untranslated volume and
     * co-volume. {@code dv/dP} is to first order too: the cubic's closed form {@code 1/(dP/dv)(T, v, a, b)} at the nearby
     * point's temperature, untranslated volume, co-volume and an attraction parameter linear in the step, less its value
     * at the base, added to the base's (a row at a velocity cap reads the acoustic bound, whose compliance is
     * {@code -n dv/dP}: in a node holding gas beside liquid that bound stands near 30 m/s, under the 100 m/s cap). The
     * vapour-like flag is the base's. At the base point itself the phase is the base phase, bit for bit.
     */
    public static final class Tangent {
        private final HydrocarbonModel model;
        private final Phase base;
        private final double t0,p0,total,v0,h0,raw0,b0,volumeT,volumeP,enthalpyT,enthalpyP,dvdt,dvdp,attraction,attractionT;
        private final double[] n0,attractionN,partialVolume,partialEnthalpy,rawPartialVolume,coVolumes,logPhi,logPhiT,logPhiP;
        private final double[][] logPhiN;
        private Tangent(HydrocarbonModel model,Phase base,double t0,double p0,double[] n0,double total,double v0,double h0,double raw0,double b0,
                        double volumeT,double volumeP,double enthalpyT,double enthalpyP,double dvdt,double dvdp,
                        double attraction,double attractionT,double[] attractionN,double[] partialVolume,double[] partialEnthalpy,double[] rawPartialVolume,double[] coVolumes,
                        double[] logPhi,double[] logPhiT,double[] logPhiP,double[][] logPhiN) {
            this.model=model;this.base=base;this.t0=t0;this.p0=p0;this.n0=n0;this.total=total;this.v0=v0;this.h0=h0;this.raw0=raw0;this.b0=b0;
            this.volumeT=volumeT;this.volumeP=volumeP;this.enthalpyT=enthalpyT;this.enthalpyP=enthalpyP;this.dvdt=dvdt;this.dvdp=dvdp;
            this.attraction=attraction;this.attractionT=attractionT;this.attractionN=attractionN;
            this.partialVolume=partialVolume;this.partialEnthalpy=partialEnthalpy;this.rawPartialVolume=rawPartialVolume;this.coVolumes=coVolumes;
            this.logPhi=logPhi;this.logPhiT=logPhiT;this.logPhiP=logPhiP;this.logPhiN=logPhiN;
        }
        /** The exact phase at the base point. */
        public Phase base() { return base; }
        public double temperature() { return t0; }
        public double pressure() { return p0; }
        /** The kernel part of the base's molar volume and enthalpy (the translated cubic and the ideal gas, no share). */
        public double kernelVolume() { return v0; }
        /** The kernel part of the base's {@code dv/dP}, and the closed form {@link #at} differences, at the base: the same number to rounding. */
        public double kernelPressureSlope() { return dvdp; }
        public double closedPressureSlope() { return 1/TranslatedPengRobinson.Values.pressureVolumeDerivative(t0,raw0,attraction,b0); }
        public double kernelEnthalpy() { return h0; }
        /** Partial molar volume and enthalpy of kernel component {@code i} (kernel part). */
        public double partialMolarVolume(int i) { return partialVolume[i]; }
        public double partialMolarEnthalpy(int i) { return partialEnthalpy[i]; }
        /** {@code d ln phi_i / d n_j} per mole of amount (the kernel's per mole of phase, over the base's total). */
        public double logFugacityAmountDerivative(int i,int j) { return logPhiN[i][j]; }
        public double logFugacityTemperatureDerivative(int i) { return logPhiT[i]; }
        public double logFugacityPressureDerivative(int i) { return logPhiP[i]; }
        /** The largest change of a mole fraction between the base and conserved {@code amounts} (water last). */
        public double compositionShift(double[] amounts) {
            int n=n0.length;double sum=0,worst=0;
            for(int i=0;i<n;i++)sum+=amounts[i];
            for(int i=0;i<n;i++)worst=Math.max(worst,Math.abs(amounts[i]/sum-n0[i]/total));
            return worst;
        }
        /** The phase of conserved {@code amounts} (water last) at {@code (t, p)}, to first order about the base. */
        public Phase at(double t,double p,double[] amounts) {
            int n=n0.length;
            if(amounts.length!=model.compounds.size()+1)throw new IllegalArgumentException("Fluid basis mismatch");
            if(n<amounts.length&&amounts[n]!=0)throw new IllegalArgumentException("Water in a phase of a list without water (package "+model.propertyPackage.id()+")");
            double dT=t-t0,dP=p-p0,sum=0;
            for(int i=0;i<n;i++)sum+=amounts[i];
            if(!(sum>0)||!Double.isFinite(t)||!Double.isFinite(p))throw new IllegalArgumentException("Fluid state is not finite");
            double[] out=new double[amounts.length];
            for(int i=0;i<n;i++)out[i]=logPhi[i]+logPhiT[i]*dT+logPhiP[i]*dP;
            // Extensive increments less the base's molar value times the amount's: exactly zero at the base point.
            double dv=volumeT*dT+volumeP*dP,dh=enthalpyT*dT+enthalpyP*dP,draw=dv,db=0,da=attractionT*dT;
            for(int j=0;j<n;j++) {
                double dn=amounts[j]-n0[j];
                if(dn==0)continue;
                dv+=(partialVolume[j]-v0)*dn;dh+=(partialEnthalpy[j]-h0)*dn;draw+=(rawPartialVolume[j]-raw0)*dn;db+=(coVolumes[j]-b0)*dn;
                da+=attractionN[j]*dn;
                for(int i=0;i<n;i++)out[i]+=logPhiN[i][j]*dn;
            }
            double v=v0+dv/sum,h=h0+dh/sum,raw=raw0+draw/sum,b=b0+db/sum;
            // dv/dP of the kernel part: the cubic's closed form at the nearby point, less its value at the base (zero there).
            double slope=dvdp;
            if(dT!=0||dP!=0||da!=0||raw!=raw0||b!=b0)slope+=1/TranslatedPengRobinson.Values.pressureVolumeDerivative(t,raw,attraction+da,b)
                    -1/TranslatedPengRobinson.Values.pressureVolumeDerivative(t0,raw0,attraction,b0);
            double phaseSlope=slope;
            if(model.water>=0&&amounts[model.water]>0) {
                double[] share=new double[WaterShare.SIZE];
                model.share.phase(t,p,amounts[model.water]/sum,raw,b,dvdt,slope,model.shareScratch.get(),share);
                v+=share[WaterShare.V];h+=share[WaterShare.H];phaseSlope+=share[WaterShare.V_P];
            }
            if(n<amounts.length)out[n]=Double.NaN;
            if(!(v>0)||!Double.isFinite(h))throw new IllegalArgumentException("Invalid translated phase properties");
            if(!(phaseSlope<0))throw new IllegalArgumentException("Invalid translated phase properties");
            return new Phase(v,h,h-p*v,phaseSlope,out,base.vaporLike(),base.carried());
        }
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
     * root is vapour-like by the phase identification parameter. {@code carried}: the kernel support it was evaluated on
     * (kernel basis, never mutated), or null for the whole list; outside it {@code ln phi} is the kernel's fill.
     */
    public record Phase(double molarVolume,double molarEnthalpy,double molarInternalEnergy,
                        double volumePressureDerivative,double[] logFugacity,boolean vaporLike,boolean[] carried) {
        /** A phase evaluated on the whole list. */
        public Phase(double molarVolume,double molarEnthalpy,double molarInternalEnergy,double volumePressureDerivative,double[] logFugacity,boolean vaporLike) {
            this(molarVolume,molarEnthalpy,molarInternalEnergy,volumePressureDerivative,logFugacity,vaporLike,null);
        }
        /** Whether {@code ln phi} of conserved component {@code i} is the equation of state's (its support carried it). */
        public boolean carries(int i) { return carried==null||i<carried.length&&carried[i]; }
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
