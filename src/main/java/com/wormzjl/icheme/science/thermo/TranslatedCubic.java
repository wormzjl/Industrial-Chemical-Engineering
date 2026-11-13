package com.wormzjl.icheme.science.thermo;

import com.wormzjl.icheme.science.math.DeterministicMath;
import java.util.List;
import java.util.Objects;

/**
 * The residual half of the constant volume-translated PR78: the shared {@link PengRobinsonKernel} on its pair list, the
 * constant volume translation and the analytic fixed-composition volumetric block (dv/dT, dv/dP, d2v/dT2, cp, dh/dP,
 * the isothermal compressibility and the partial molar volumes), explicit algebra in
 * {@code (v, a, b, da/dT, d2a/dT2, S_i)}. Math only: it reads no material type (batch 2026-11-13-codebase-review, C1b
 * item 4, moved out of the network's {@code TranslatedPengRobinson} operation for operation).
 *
 * <p>The phase engine evaluates it {@link #residualOnly}: no ideal-gas part, so {@link Values#molarEnthalpy()} is
 * {@code h^R + P c} bit for bit and {@link Values#heatCapacity()} the residual {@code c_p}, whose sign is not checked.
 * The network's adapter ({@code science.fluid.thermo.TranslatedPengRobinson}) extends it with the ideal-gas caloric
 * functions: it fills each component's ideal-gas heat capacity and enthalpy at a prepared temperature
 * ({@link #idealGasTerms}), which the caloric sums of the volumetric block then weight by composition, in the order
 * the whole model always summed them.</p>
 *
 * <p>Diagnostics: {@link #countPreparationsWith} installs the counter of temperature preparations (the network's
 * {@code SolverDiagnostics.temperatureTermsCalls}); a preparation reads it once, a volatile read as the network's
 * {@code ENABLED} switch was, and none is installed outside the network.</p>
 */
public class TranslatedCubic {
    private static final double R = PengRobinsonKernel.GAS_CONSTANT;
    private static final double SQRT_TWO = Math.sqrt(2);
    /** Run at every temperature preparation; null (nothing counted) until the network installs one. */
    private static volatile Runnable preparationCounter;
    private final List<ThermoComponent> components;
    private final double[] translations;
    private final PengRobinsonKernel kernel;
    /** See {@link #residualOnly}: no ideal-gas part, so the heat capacity is the residual one and may be any sign. */
    private final boolean residualOnly;

    /**
     * The same equation of state without an ideal-gas part, for an evaluator that adds its own ideal-gas functions
     * (the phase engine's {@code science.thermo.phase.CubicPhaseEvaluator}). The ideal-gas enthalpy and heat capacity are
     * exact zeros: {@link Values#molarEnthalpy()} is then {@code h^R + P c} bit for bit and {@link Values#heatCapacity()}
     * is the residual {@code c_p}, whose sign is not checked. Volume, fugacity coefficients and every volumetric
     * derivative are the ones the full model computes.
     */
    public static TranslatedCubic residualOnly(List<ThermoComponent> components, double[][] interactions,
                                               double[] translations) {
        return new TranslatedCubic(components, interactions, null, translations, true);
    }

    /**
     * {@link #residualOnly(List, double[][], double[])} on a pair-interaction model: the kernel reads the model's pairs,
     * so a group-contribution (E-PPR78) pair's {@code k_ij(T)} and its temperature derivatives enter every value. On a
     * model of constant pairs it is the matrix factory's model bit for bit.
     */
    public static TranslatedCubic residualOnly(List<ThermoComponent> components, PairInteractions interactions,
                                               double[] translations) {
        return new TranslatedCubic(components, null, Objects.requireNonNull(interactions, "interactions"), translations, true);
    }

    /**
     * {@link #residualOnly(List, double[][], double[])} on a prebuilt kernel (batch 2026-11-11-aqueous-solvents, W3a): a
     * list whose pairs are derived ({@code science.material.DerivedPairs}, the Huron-Vidal rule and per-compound alphas)
     * hands its own kernel over. The kernel's components must be {@code components} in order (critical constants and
     * acentric factors equal); the translation and the volumetric block are this class's expressions on it.
     */
    public static TranslatedCubic residualOnly(List<ThermoComponent> components, PengRobinsonKernel kernel,
                                               double[] translations) {
        return new TranslatedCubic(components, Objects.requireNonNull(kernel, "kernel"), translations, true);
    }

    /**
     * Installs the counter run at every temperature preparation ({@link #prepare(double, Workspace)}), or none for
     * {@code null}. The network installs its diagnostics counter, which counts only while its diagnostics are on.
     */
    public static void countPreparationsWith(Runnable counter) { preparationCounter = counter; }

    /**
     * The model on a kernel built elsewhere. {@code residualOnly}: whether the heat capacity is the residual one (no
     * ideal-gas terms, sign unchecked); a subclass that fills {@link #idealGasTerms} passes {@code false}.
     */
    protected TranslatedCubic(List<ThermoComponent> components, PengRobinsonKernel kernel, double[] translations,
                              boolean residualOnly) {
        this.residualOnly = residualOnly;
        this.components = List.copyOf(components);
        int count = components.size();
        if (count == 0 || kernel.componentCount() != count || translations.length != count)
            throw new IllegalArgumentException("Inconsistent property basis");
        this.translations = translations.clone();
        for (int i = 0; i < count; i++) finite(this.translations[i]);
        this.kernel = kernel;
    }

    /**
     * The model on its own kernel: exactly one of {@code interactions} (the constant matrix, {@code SPARSE_PAIRS}) and
     * {@code pairModel} is set. {@code residualOnly} as for the kernel-taking constructor.
     */
    protected TranslatedCubic(List<ThermoComponent> components, double[][] interactions, PairInteractions pairModel,
                              double[] translations, boolean residualOnly) {
        this.residualOnly = residualOnly;
        this.components = List.copyOf(components);
        int count = components.size();
        if (count == 0 || (pairModel == null ? interactions.length != count : pairModel.componentCount() != count)
                || translations.length != count) throw new IllegalArgumentException("Inconsistent property basis");
        this.translations = translations.clone();
        double[] criticalTemperatures=new double[count],criticalPressures=new double[count],acentricFactors=new double[count];
        for (int i = 0; i < count; i++) {
            if (pairModel == null && interactions[i].length != count) {
                throw new IllegalArgumentException("Invalid property matrix dimensions");
            }
            if (pairModel == null) for (double value : interactions[i]) finite(value);
            finite(this.translations[i]);
            var component=components.get(i);
            criticalTemperatures[i]=component.criticalTemperatureKelvin();
            criticalPressures[i]=component.criticalPressurePascal();
            acentricFactors[i]=component.acentricFactor();
        }
        if (pairModel == null) for (int i = 0; i < count; i++) for (int j = 0; j < count; j++) {
            if (interactions[i][j] != interactions[j][i]) throw new IllegalArgumentException("Asymmetric interactions");
        }
        // The kernel's own domain is left open: this class validates every state itself, with the messages it
        // always produced, and a property package's validity range is the caller's business, not the EOS's.
        kernel=pairModel == null
                ? new PengRobinsonKernel(criticalTemperatures,criticalPressures,acentricFactors,interactions,
                        Double.MIN_VALUE,Double.MAX_VALUE,Double.MIN_VALUE,Double.MAX_VALUE)
                : new PengRobinsonKernel(criticalTemperatures,criticalPressures,acentricFactors,pairModel,
                        Double.MIN_VALUE,Double.MAX_VALUE,Double.MIN_VALUE,Double.MAX_VALUE);
    }

    /** The equation of state this model translates; the phase-stability test runs on it (the translation cancels). */
    public PengRobinsonKernel kernel() { return kernel; }

    /** The number of components of the basis. */
    protected final int componentCount() { return components.size(); }

    /**
     * The ideal-gas heat capacity (J/(mol K)) and enthalpy (J/mol) of each component at {@code temperature}, written
     * into the workspace's arrays at every preparation before the cubic's terms. The residual model writes none: both
     * stay exact zeros.
     */
    protected void idealGasTerms(double temperature, double[] heatCapacity, double[] enthalpy) {}

    public Phase evaluate(double temperature, double pressure, double[] amounts, PengRobinsonKernel.Root root) {
        return evaluate(temperature,pressure,amounts,root,prepare(temperature));
    }

    /**
     * One exact temperature's prepared state, and the scratch every evaluation at it reuses: the kernel's 3n
     * pure-component vectors, the composition-dependent sums formed per evaluation in O(n + pairs). Mutable scratch, so
     * it belongs to one solving thread at a time; the caller that holds it (a node's {@code FluidThermodynamics.Prepared})
     * already has that confinement.
     */
    public static final class Workspace {
        private final TranslatedCubic owner;
        private final PengRobinsonKernel.Workspace mixture;
        private final PengRobinsonKernel.Evaluation evaluation;
        private final Values values;
        private final double[] heatCapacity,enthalpy;
        private final double[] rootDt,rootDt2,crossDt;
        private double temperature=Double.NaN;
        /** Bumped by every preparation and evaluation: the values' second-derivative terms read the mixture it stamps. */
        private long generation;
        private Workspace(TranslatedCubic owner) {
            this.owner=owner;int count=owner.components.size();
            mixture=owner.kernel.newWorkspace();evaluation=owner.kernel.newEvaluation();values=new Values(count);
            heatCapacity=new double[count];enthalpy=new double[count];
            rootDt=new double[count];rootDt2=new double[count];crossDt=new double[count];
        }
        public double temperature(){return temperature;}
        /**
         * The compounds the caller's mixture carries ({@link PengRobinsonKernel.Workspace#carry}): its feed's nonzero
         * amounts, set at every entry from the feed, never from one phase. Every value on them keeps its bits; outside
         * them {@code ln phi} and the partial volumes are the kernel's fill (zero). A changed support at a prepared
         * temperature prepares what it adds.
         */
        public void carry(double[] feed){if(mixture.carry(feed))supportChanged();}
        /** The whole list (a new workspace's support). */
        public void carryAll(){if(mixture.carryAll())supportChanged();}
        /** Whether the support holds component {@code i}. */
        public boolean carries(int i){return mixture.carries(i);}
        /** The support as a mask never mutated ({@link PengRobinsonKernel.Workspace#supportSnapshot}); null for the whole list. */
        public boolean[] supportSnapshot(){return mixture.supportSnapshot();}
        /** This workspace's support onto a kernel workspace of the same kernel (a derivative evaluation of the same phase). */
        public void carryOnto(PengRobinsonKernel.Workspace target){target.carrySame(mixture);}
        private void supportChanged(){if(!Double.isNaN(temperature))owner.prepareSupport(this);}
        /** Component {@code i}'s ideal-gas heat capacity at the prepared temperature, J/(mol K) ({@link #idealGasTerms}). */
        public double idealGasHeatCapacity(int i){return heatCapacity[i];}
        /** Component {@code i}'s ideal-gas enthalpy at the prepared temperature on the model's datum, J/mol. */
        public double idealGasEnthalpy(int i){return enthalpy[i];}
    }

    /**
     * The same numbers a {@link Phase} carries, in a buffer the caller keeps instead of a record it allocates.
     * {@link #evaluate} fills the workspace's own and copies out of it; {@link #evaluateValues} returns that buffer
     * itself.
     */
    public static final class Values {
        private final double[] logFugacityCoefficients,partialMolarVolumes;
        private double molarVolume,molarEnthalpy,molarInternalEnergy,volumeTemperatureDerivative,
                volumePressureDerivative,heatCapacity,enthalpyPressureDerivative,isothermalCompressibility,
                volumeSecondTemperatureDerivative,compressibility,idealGasHeatCapacity;
        /** The phase identification inputs of the evaluated root: its untranslated volume and the mixture a, b, da/dT. */
        private double temperature,untranslatedVolume,attraction,coVolume,attractionTemperatureDerivative;
        private int physicalRootCount;
        /**
         * The terms c_p and d2v/dT2 add to on d2a/dT2, which only they read: formed on the first read from the mixture
         * the evaluation left in its workspace ({@link #secondOrder}), so an evaluation that reads neither skips it.
         */
        private Workspace source;
        private long generation;
        private boolean secondFormed;
        private double heatCapacityBase,heatCapacityTail,logTerm,dpdv,denominator,dpdtv,dpdvv;
        private Values(int count) {
            logFugacityCoefficients=new double[count];partialMolarVolumes=new double[count];
        }
        private void secondOrder() {
            if(secondFormed)return;
            if(source.generation!=generation)throw new IllegalStateException("The workspace was evaluated again since these values");
            Workspace w=source;
            double dda=w.owner.kernel.mixtureSecondTemperatureDerivative(w.mixture,w.rootDt,w.rootDt2,w.crossDt);
            double dvdt=volumeTemperatureDerivative;
            // The operations of the closed form, in its order: the same double as forming it at once.
            double t=temperature,b=coVolume;
            double cp=heatCapacityBase+t*dda/(2*SQRT_TWO*b)*logTerm+heatCapacityTail;
            if(!(w.owner.residualOnly||cp>0)||!Double.isFinite(cp))throw new ThermoRefusal("Invalid translated phase properties");
            heatCapacity=cp;
            volumeSecondTemperatureDerivative=-(-dda/denominator+2*dpdtv*dvdt+dpdvv*dvdt*dvdt)/dpdv;
            secondFormed=true;
        }
        public double molarVolume(){return molarVolume;}
        public double molarEnthalpy(){return molarEnthalpy;}
        public double molarInternalEnergy(){return molarInternalEnergy;}
        public double volumeTemperatureDerivative(){return volumeTemperatureDerivative;}
        public double volumePressureDerivative(){return volumePressureDerivative;}
        public double heatCapacity(){secondOrder();return heatCapacity;}
        public double enthalpyPressureDerivative(){return enthalpyPressureDerivative;}
        public double isothermalCompressibility(){return isothermalCompressibility;}
        public double volumeSecondTemperatureDerivative(){secondOrder();return volumeSecondTemperatureDerivative;}
        public double compressibility(){return compressibility;}
        /** The ideal-gas half of {@link #heatCapacity()}; the residual half is the kernel's dH^R/dT. */
        public double idealGasHeatCapacity(){return idealGasHeatCapacity;}
        /**
         * The phase identification parameter of the evaluated root (Venkatarathnam and Oellrich 2011) on the untranslated
         * cubic, {@link PhaseIdentification#parameter}: the root is liquid-like when it is above one and above
         * {@link #compressibility()} ({@link PhaseIdentification#liquidLike(double, double)}). It is the network's one
         * phase-identification rule (replacing the retired root heuristic), computed on demand from the root's own
         * volume and mixture parameters, so an evaluation that does not ask for it pays nothing.
         */
        public double phaseIdentificationParameter() {
            return PhaseIdentification.parameter(temperature,untranslatedVolume,attraction,coVolume,attractionTemperatureDerivative);
        }
        /** Physical roots of the cubic at this state and composition: 1, or 3 where both roots exist. */
        public int physicalRootCount(){return physicalRootCount;}
        /** The support these values were evaluated on ({@link Workspace#supportSnapshot}); null for the whole list. */
        public boolean[] supportSnapshot(){return carried;}
        private boolean[] carried;
        /** The caller must not mutate these; they are refilled by the next evaluation. */
        public double[] logFugacityCoefficientsView(){return logFugacityCoefficients;}
        public double[] partialMolarVolumesView(){return partialMolarVolumes;}
        /** The root's untranslated molar volume, m3/mol, and the mixture's co-volume (water's share reads their ratio). */
        public double untranslatedVolume(){return untranslatedVolume;}
        public double coVolume(){return coVolume;}
        /** The mixture's attraction parameter and its temperature derivative at the evaluated composition. */
        public double attraction(){return attraction;}
        public double attractionTemperatureDerivative(){return attractionTemperatureDerivative;}
        /** {@code N da_mix/dn_i} of the evaluated mixture: {@code 2 (S_i - a_mix)}, the rows the partial molar volumes read. */
        public double attractionAmountDerivative(int i) {
            if(source.generation!=generation)throw new IllegalStateException("The workspace was evaluated again since these values");
            if(!source.mixture.carries(i))return PengRobinsonKernel.outsideSupportFill();
            return 2*(source.mixture.attractionRowsView()[i]-attraction);
        }
        /** {@code (dP/dv)_T} of the cubic at an untranslated volume, attraction and co-volume: the closed form of {@code fill}. */
        public static double pressureVolumeDerivative(double temperature,double v,double a,double b) {
            return PengRobinsonKernel.pressureVolumeDerivative(temperature,v,a,b);
        }
        private Phase phase() {
            secondOrder();
            return new Phase(molarVolume,molarEnthalpy,molarInternalEnergy,volumeTemperatureDerivative,
                    volumePressureDerivative,heatCapacity,enthalpyPressureDerivative,isothermalCompressibility,
                    logFugacityCoefficients.clone(),volumeSecondTemperatureDerivative,
                    partialMolarVolumes.clone(),phaseIdentificationParameter());
        }
    }

    public Workspace newWorkspace() { return new Workspace(this); }

    /** A workspace prepared at one temperature; a solve reuses it across every composition and pressure trial. */
    public Workspace prepare(double temperature) {
        Workspace workspace=new Workspace(this);
        prepare(temperature,workspace);
        return workspace;
    }
    public void prepare(double temperature,Workspace workspace) {
        Runnable counter=preparationCounter;
        if(counter!=null)counter.run();
        if(workspace.owner!=this)throw new IllegalArgumentException("Temperature coefficients belong to another state/model");
        if(!Double.isFinite(temperature)||temperature<=0)throw new ThermoRefusal("Invalid coefficient temperature");
        idealGasTerms(temperature,workspace.heatCapacity,workspace.enthalpy);
        prepareCubic(temperature,workspace);
        workspace.temperature=temperature;
    }

    /** The kernel's part of {@link #prepare} on the workspace's support: at its temperature, again when the support changes. */
    private void prepareCubic(double temperature,Workspace workspace) {
        int count=components.size();
        kernel.prepareTemperature(temperature,workspace.mixture);
        // sqrt(a_i) vanishes exactly where the PR alpha term does, which is where every d/dT below divides
        // by zero. The same state the dense form refused as a degenerate alpha derivative.
        double[] roots=workspace.mixture.attractionRootsView();
        for(int i=0;i<count;i++)if(workspace.mixture.carries(i)&&roots[i]==0)throw new ThermoRefusal("Degenerate PR alpha derivative");
        // The pure-component root derivatives depend on temperature alone, exactly like the dense form's
        // three matrices did; only the composition-weighted sums below are per evaluation.
        kernel.prepareRootDerivatives(temperature,workspace.mixture,workspace.rootDt,workspace.rootDt2);
        workspace.generation++;
    }

    /**
     * A changed support at the prepared temperature: its new compounds' terms (the kernel's stamps keep the others). A
     * refusal leaves the workspace unprepared, so no evaluation reads the new compounds' missing terms.
     */
    private void prepareSupport(Workspace workspace) {
        double temperature=workspace.temperature;
        workspace.temperature=Double.NaN;
        prepareCubic(temperature,workspace);
        workspace.temperature=temperature;
    }

    /** {@link #evaluate} without the record and its two
     * array copies; the returned buffer is the workspace's own and is refilled by the next evaluation. */
    public Values evaluateValues(double temperature,double pressure,double[] amounts,PengRobinsonKernel.Root root,Workspace workspace) {
        requireState(temperature,pressure,amounts,workspace);
        kernel.evaluate(temperature,pressure,amounts,root,workspace.mixture,workspace.evaluation);
        fill(temperature,pressure,workspace,workspace.evaluation,workspace.values);
        return workspace.values;
    }
    /**
     * {@link #evaluateValues(double, double, double[], PengRobinsonKernel.Root, Workspace)} and the kernel's derivative
     * block of the same root from the same evaluation ({@link PengRobinsonKernel#differentiatePrepared}): the kernel
     * evaluates the state once, into {@code derivatives.evaluation()}, and the values are filled before the derivatives,
     * so a refusal comes from the same check as the two calls made apart. {@code derivatives} belongs to this model's kernel.
     */
    public Values evaluateValues(double temperature,double pressure,double[] amounts,PengRobinsonKernel.Root root,Workspace workspace,
                                 PengRobinsonKernel.Derivatives derivatives) {
        requireState(temperature,pressure,amounts,workspace);
        PengRobinsonKernel.Evaluation evaluation=derivatives.evaluation();
        kernel.evaluate(temperature,pressure,amounts,root,workspace.mixture,evaluation);
        fill(temperature,pressure,workspace,evaluation,workspace.values);
        kernel.differentiatePrepared(temperature,pressure,root,workspace.mixture,derivatives);
        return workspace.values;
    }
    public Phase evaluate(double temperature,double pressure,double[] amounts,PengRobinsonKernel.Root root,Workspace workspace) {
        requireState(temperature,pressure,amounts,workspace);
        kernel.evaluate(temperature,pressure,amounts,root,workspace.mixture,workspace.evaluation);
        fill(temperature,pressure,workspace,workspace.evaluation,workspace.values);
        return workspace.values.phase();
    }

    private void requireState(double temperature,double pressure,double[] amounts,Workspace workspace) {
        if (!Double.isFinite(temperature) || temperature <= 0 || !Double.isFinite(pressure) || pressure <= 0) {
            throw new ThermoRefusal("Positive finite temperature and pressure required");
        }
        if(workspace.owner!=this||temperature!=workspace.temperature)throw new IllegalArgumentException("Temperature coefficients belong to another state/model");
        if(amounts.length!=components.size())throw new IllegalArgumentException("Composition basis mismatch");
    }

    /** The translation, the caloric terms and the volumetric block, on the mixture the kernel just evaluated. */
    private void fill(double temperature,double pressure,Workspace workspace,
                      PengRobinsonKernel.Evaluation evaluation,Values out) {
        double[] x=workspace.mixture.compositionView(),attractionRows=workspace.mixture.attractionRowsView();
        double a=evaluation.aMix(),b=evaluation.bMix(),da=evaluation.daMixDt();
        double shift = 0, idealH = 0, idealCp = 0;
        for (int i = 0; i < x.length; i++) {
            shift += x[i] * translations[i];
            idealCp+=x[i]*workspace.heatCapacity[i];idealH+=x[i]*workspace.enthalpy[i];
        }
        double rt=R*temperature;
        double z=evaluation.compressibility(),v=z*rt/pressure;
        double denominator = PengRobinsonKernel.cubicDenominator(v,b);
        double dpdv = PengRobinsonKernel.pressureVolumeDerivative(temperature,v,a,b);
        double dpdt = PengRobinsonKernel.pressureTemperatureDerivative(v,b,da);
        if (!(dpdv < 0)) throw new ThermoRefusal("Mechanically unstable PR root");
        double dvdp = 1/dpdv, dvdt = -dpdt/dpdv;
        double dpdvv = PengRobinsonKernel.pressureSecondVolumeDerivative(temperature,v,a,b);
        double dpdtv = PengRobinsonKernel.pressureTemperatureVolumeDerivative(v,b,da);
        double physicalV = v + shift;
        double log = DeterministicMath.log((v+(1+SQRT_TWO)*b)/(v+(1-SQRT_TWO)*b));
        double h=idealH+evaluation.residualEnthalpyJoulesPerMol()+pressure*shift;
        double dlogdv = 1/(v+(1+SQRT_TWO)*b) - 1/(v+(1-SQRT_TWO)*b);
        // c_p = (the terms below) + T log/(2 sqrt2 b) d2a/dT2; the last term is formed when read (Values.secondOrder).
        double cpBase = idealCp + pressure*dvdt - R, cpTail = (temperature*da-a)/(2*SQRT_TWO*b)*dlogdv*dvdt;
        double dhdp = physicalV - temperature*dvdt;
        double[] logPhi = out.logFugacityCoefficients;
        double[] partialVolumes = out.partialMolarVolumes;
        double[] mixtureLogPhi=evaluation.logFugacityCoefficientsView();
        double outside=PengRobinsonKernel.outsideSupportFill();
        for (int i=0;i<x.length;i++) {
            // A compound the workspace does not carry has no kernel terms: its entries are the kernel's fill.
            if(!workspace.mixture.carries(i)){logPhi[i]=outside;partialVolumes[i]=outside;continue;}
            logPhi[i]=mixtureLogPhi[i]+pressure*translations[i]/rt;
            double db=coVolume(i)-b;
            double pressureCompositionDerivative=-2*(attractionRows[i]-a)/denominator
                    +db*(R*temperature/((v-b)*(v-b))+2*a*(v-b)/(denominator*denominator));
            partialVolumes[i]=v-pressureCompositionDerivative/dpdv+translations[i];
            if(!Double.isFinite(logPhi[i])||!Double.isFinite(partialVolumes[i]))throw new ThermoRefusal("Nonfinite chemical-potential derivative");
        }
        if (!(physicalV > 0) || !Double.isFinite(h) || !Double.isFinite(cpBase + cpTail)
                || !Double.isFinite(dvdp) || !Double.isFinite(dvdt) || !Double.isFinite(dhdp)) {
            throw new ThermoRefusal("Invalid translated phase properties");
        }
        out.molarVolume=physicalV;out.molarEnthalpy=h;out.molarInternalEnergy=h-pressure*physicalV;
        out.volumeTemperatureDerivative=dvdt;out.volumePressureDerivative=dvdp;
        out.enthalpyPressureDerivative=dhdp;out.isothermalCompressibility=-dvdp/physicalV;
        out.compressibility=z;out.idealGasHeatCapacity=idealCp;
        out.source=workspace;out.generation=++workspace.generation;out.secondFormed=false;
        out.heatCapacityBase=cpBase;out.heatCapacityTail=cpTail;out.logTerm=log;
        out.dpdv=dpdv;out.denominator=denominator;out.dpdtv=dpdtv;out.dpdvv=dpdvv;
        out.temperature=temperature;out.untranslatedVolume=v;out.attraction=a;out.coVolume=b;out.attractionTemperatureDerivative=da;
        out.physicalRootCount=evaluation.physicalRootCount();
        out.carried=workspace.mixture.supportSnapshot();
    }

    private double coVolume(int component) { return kernel.coVolume(component); }

    /** The constant volume translation of a component, m3/mol ({@code v = v_PR + c}). */
    public double translation(int component) { return translations[component]; }

    /** A finite coefficient, else the refusal every property coefficient of this model shares. */
    protected static void finite(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Nonfinite property coefficient");
    }

    /** The arrays belong to the record from construction on; {@link #evaluate} builds them for it. */
    public record Phase(double molarVolume, double molarEnthalpy, double molarInternalEnergy,
                        double volumeTemperatureDerivative, double volumePressureDerivative,
                        double heatCapacity, double enthalpyPressureDerivative,
                        double isothermalCompressibility, double[] logFugacityCoefficients,
                        double volumeSecondTemperatureDerivative,double[] partialMolarVolumes,double phaseIdentificationParameter) {
        @Override public double[] logFugacityCoefficients() { return logFugacityCoefficients.clone(); }
        @Override public double[] partialMolarVolumes() { return partialMolarVolumes.clone(); }
    }
}
